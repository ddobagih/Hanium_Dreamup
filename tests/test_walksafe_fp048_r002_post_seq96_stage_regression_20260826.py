"""Stage-safe FP048 R002 witnesses from exact seq96 through seq98."""

from __future__ import annotations

import hashlib
import importlib
import importlib.util
import os
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
EXACT_SEQ96_BYTE_LENGTH = 4_414_443
EXACT_SEQ96_SHA256 = (
    "c64919299dea316339afd777c37a33575458a94a158acd4c04ceed8b3075d85f"
)
EXACT_SEQ96_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-GATE-CONTRACT-CORRECTED-"
    "FP048-R002-20260826-004"
)
EXACT_SEQ96_EVENT_SHA256 = (
    "ff0c91a5a98df66d6e44007c5c5fd51c50793fb6b896938418104aba05798dea"
)


def _source_owner() -> Any:
    return importlib.import_module(
        "scripts.apply_walksafe_fp048_r002_start_gate_contract_correction_"
        "seq96_20260826"
    )


def _correction() -> Any:
    return importlib.import_module(
        "scripts.apply_walksafe_fp048_r002_start_gate_snapshot_hygiene_"
        "correction_seq97_20260826"
    )


def _started() -> Any:
    return importlib.import_module(
        "scripts.apply_walksafe_fp048_r002_goal_started_seq98_20260826"
    )


def _gate() -> Any:
    return importlib.import_module(
        "scripts.run_walksafe_fp048_r002_goal_start_gate_r009_20260826"
    )


def _correction_available() -> bool:
    return importlib.util.find_spec(
        "scripts.apply_walksafe_fp048_r002_start_gate_snapshot_hygiene_"
        "correction_seq97_20260826"
    ) is not None


def _started_available() -> bool:
    return importlib.util.find_spec(
        "scripts.apply_walksafe_fp048_r002_goal_started_seq98_20260826"
    ) is not None


def _owner_validated_exact_seq96(
    root: Path,
) -> tuple[bytes, dict[str, Any], bytes, dict[str, Any], int]:
    owner = _source_owner()
    live_read = owner.seq90._stable_read(root, owner.CHECKPOINT_REL)
    live = owner.seq90.strict_json(live_read.raw, owner.CHECKPOINT_REL.as_posix())
    assert live_read.raw == owner.checkpoint_json_bytes(live)
    history = live.get("goal_execution", {}).get("transition_history")
    assert isinstance(history, list)
    sequence = len(history)
    if sequence == owner.CORRECTION_SEQUENCE:
        owner.require_contract_corrected_checkpoint(
            root,
            live,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        raw, source = live_read.raw, live
    elif sequence == 97:
        correction = _correction()
        correction.require_snapshot_hygiene_corrected_checkpoint(
            root,
            live,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        raw = correction.reconstructed_seq96_checkpoint_bytes(root, live)
        source = owner.seq90.strict_json(raw, "reconstructed seq96")
    elif sequence == 98:
        started = _started()
        started.require_started_checkpoint(
            root,
            live,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        seq97_raw = started.reconstructed_seq97_checkpoint_bytes(root, live)
        seq97 = owner.seq90.strict_json(seq97_raw, "reconstructed seq97")
        correction = _correction()
        correction.require_snapshot_hygiene_corrected_checkpoint(
            root,
            seq97,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        raw = correction.reconstructed_seq96_checkpoint_bytes(root, seq97)
        source = owner.seq90.strict_json(raw, "reconstructed seq96")
    else:
        raise AssertionError(f"unsupported live FP048 R002 stage: seq{sequence}")
    assert len(raw) == EXACT_SEQ96_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == EXACT_SEQ96_SHA256
    assert raw == owner.checkpoint_json_bytes(source)
    source_history = source["goal_execution"]["transition_history"]
    assert len(source_history) == 96
    assert source_history[-1]["event_id"] == EXACT_SEQ96_EVENT_ID
    assert source_history[-1]["event_sha256"] == EXACT_SEQ96_EVENT_SHA256
    return raw, source, live_read.raw, live, sequence


def _require_live_004_physical_state(
    root: Path,
    live_raw: bytes,
    live: dict[str, Any],
    sequence: int,
) -> str:
    gate = _gate()
    event_root = root / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
    receipt_path = event_root / gate.RECEIPT_NAME
    if not os.path.lexists(event_root):
        assert not os.path.lexists(receipt_path)
        assert sequence in {96, 97}
        return "PRE_R009_GATE_ABSENT"
    assert sequence in {97, 98}
    started = _started()
    if sequence == 97:
        evidence = started.require_published_r009_gate_for_seq97(
            root,
            live,
            live_raw,
        )
        assert evidence.receipt["status"] == "PASS"
        assert evidence.receipt["implementation_start_gate_contract_binding"] == (
            gate.expected_r009_binding()
        )
    else:
        started.require_started_checkpoint(
            root,
            live,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        binding = started.goal_start_gate_receipt_binding(root, live)
        assert binding["path"] == receipt_path.relative_to(root).as_posix()
    return "POST_R009_EXACT_SEALED_PASS"


def test_live_descendant_is_canonical_and_reduces_to_exact_seq96() -> None:
    raw, source, _live_raw, _live, sequence = _owner_validated_exact_seq96(ROOT)
    assert sequence in {96, 97, 98}
    assert hashlib.sha256(raw).hexdigest() == EXACT_SEQ96_SHA256
    assert source["goal_execution"]["transition_history"][-1]["event_id"] == (
        EXACT_SEQ96_EVENT_ID
    )


def test_live_004_namespace_is_absent_or_exact_post_r009_pass() -> None:
    _raw, _source, live_raw, live, sequence = _owner_validated_exact_seq96(ROOT)
    state = _require_live_004_physical_state(ROOT, live_raw, live, sequence)
    assert state in {"PRE_R009_GATE_ABSENT", "POST_R009_EXACT_SEALED_PASS"}


def test_four_historical_nonauthority_records_remain_exact_by_stage() -> None:
    _raw, source, _live_raw, live, sequence = _owner_validated_exact_seq96(ROOT)
    gate = _gate()
    source_binding = source["goal_execution"]["transition_history"][-1][
        "source_checkpoint_binding"
    ]
    assert gate._strict_json_equal(
        source_binding["preflight_attempt_004"],
        gate.expected_historical_preflight_attempt_004_binding(),
    )
    assert gate._strict_json_equal(
        source_binding["r006_preflight_attempt_004"],
        gate.expected_r006_preflight_attempt_004_binding(),
    )
    assert gate._strict_json_equal(
        source_binding["r007_preflight_attempt_004"],
        gate.expected_r007_preflight_attempt_004_binding(),
    )
    if sequence >= 97:
        if sequence == 98:
            started = _started()
            seq97_raw = started.reconstructed_seq97_checkpoint_bytes(ROOT, live)
            live = _source_owner().seq90.strict_json(
                seq97_raw, "reconstructed seq97"
            )
        binding = live["goal_execution"]["transition_history"][-1][
            "source_checkpoint_binding"
        ]
        assert gate._strict_json_equal(
            binding["r008_preflight_attempt_004"],
            gate.expected_preflight_attempt_004_binding(),
        )


def test_stage_parser_preserves_sealed_evidence_and_scopes_004() -> None:
    _raw, _source, live_raw, _live, sequence = _owner_validated_exact_seq96(ROOT)
    owner = _source_owner()
    gate = _gate()
    directories, paths = gate._checkpoint_bound_gate_event_directories(live_raw)
    passed_receipt = (
        gate.GATE_ROOT_RELATIVE / owner.PASSED_GATE_EVENT_ID / gate.RECEIPT_NAME
    )
    current_receipt = (
        gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID / gate.RECEIPT_NAME
    )
    assert passed_receipt.parent in directories
    assert passed_receipt in paths
    if sequence == 98:
        assert current_receipt in paths
    else:
        assert current_receipt not in paths


def test_stage_owner_public_apis_are_lazy_and_sequence_exact() -> None:
    owner = _source_owner()
    gate = _gate()
    assert owner.CORRECTION_SEQUENCE == 96
    assert gate.SOURCE_SEQUENCE == 97
    assert gate.EVENT_SEQUENCE == 98
    assert gate.STARTED_EVENT_ID.endswith("20260826-004")
    assert callable(owner.canonical_seq96_checkpoint_bytes)
    assert callable(owner.reconstructed_seq96_checkpoint_bytes)
    if _correction_available():
        correction = _correction()
        assert correction.SOURCE_SEQUENCE == owner.CORRECTION_SEQUENCE
        assert correction.CORRECTION_SEQUENCE == gate.SOURCE_SEQUENCE
        assert correction.STARTED_SEQUENCE == gate.EVENT_SEQUENCE
        assert correction.CORRECTION_EVENT_ID == gate.CORRECTION_EVENT_ID
        assert correction.STARTED_EVENT_ID == gate.STARTED_EVENT_ID
        assert callable(correction.require_snapshot_hygiene_corrected_checkpoint)
        assert callable(correction.reconstructed_seq96_checkpoint_bytes)
    if _correction_available() and _started_available():
        started = _started()
        assert started.SOURCE_SEQUENCE == gate.SOURCE_SEQUENCE
        assert started.EVENT_SEQUENCE == gate.EVENT_SEQUENCE
        assert started.STARTED_EVENT_ID == gate.STARTED_EVENT_ID
        assert callable(started.reconstructed_seq97_checkpoint_bytes)
        assert callable(started.require_started_checkpoint)
