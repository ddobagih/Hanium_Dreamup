"""Stage-aware FP048 R002 witnesses from exact seq94 through seq96."""

from __future__ import annotations

import hashlib
import importlib
import os
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
EXACT_SEQ94_BYTE_LENGTH = 3_758_409
EXACT_SEQ94_SHA256 = (
    "e361140b2faa1ab5f8d00145251db5b990b33f140d5de5610289fbbd0dc62b12"
)


def _correction() -> Any:
    return importlib.import_module(
        "scripts.apply_walksafe_fp048_r002_start_gate_contract_correction_"
        "seq95_20260826"
    )


def _started() -> Any:
    return importlib.import_module(
        "scripts.apply_walksafe_fp048_r002_goal_started_seq96_20260826"
    )


def _gate() -> Any:
    return importlib.import_module(
        "scripts.run_walksafe_fp048_r002_goal_start_gate_r007_20260826"
    )


def _owner_validated_exact_seq94(
    root: Path,
) -> tuple[bytes, dict[str, Any], bytes, dict[str, Any], int]:
    correction = _correction()
    live_read = correction.seq90._stable_read(root, correction.CHECKPOINT_REL)
    live = correction.seq90.strict_json(
        live_read.raw,
        correction.CHECKPOINT_REL.as_posix(),
    )
    assert live_read.raw == correction.checkpoint_json_bytes(live)
    history = live.get("goal_execution", {}).get("transition_history")
    assert isinstance(history, list)
    sequence = len(history)
    if sequence == correction.SOURCE_SEQUENCE:
        correction.require_exact_seq94_source(live_read.raw, live, root)
        raw, source = live_read.raw, live
    elif sequence == correction.CORRECTION_SEQUENCE:
        correction.require_contract_corrected_checkpoint(
            root,
            live,
            require_live_snapshot=False,
        )
        raw = correction.reconstructed_seq94_checkpoint_bytes(root, live)
        source = correction.seq90.strict_json(raw, "reconstructed seq94")
    elif sequence == correction.STARTED_SEQUENCE:
        started = _started()
        started.require_started_checkpoint(
            root,
            live,
            require_live_snapshot=False,
        )
        seq95_raw = started.reconstructed_seq95_checkpoint_bytes(root, live)
        seq95 = correction.seq90.strict_json(seq95_raw, "reconstructed seq95")
        correction.require_contract_corrected_checkpoint(
            root,
            seq95,
            require_live_snapshot=False,
        )
        raw = correction.reconstructed_seq94_checkpoint_bytes(root, seq95)
        source = correction.seq90.strict_json(raw, "reconstructed seq94")
    else:
        raise AssertionError(f"unsupported live FP048 R002 stage: seq{sequence}")
    correction.require_exact_seq94_source(raw, source, root)
    assert len(raw) == EXACT_SEQ94_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == EXACT_SEQ94_SHA256
    assert raw == correction.checkpoint_json_bytes(source)
    return raw, source, live_read.raw, live, sequence


def _require_live_004_physical_state(
    root: Path,
    live_raw: bytes,
    live: dict[str, Any],
    sequence: int,
) -> str:
    correction = _correction()
    gate = _gate()
    event_root = root / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
    receipt_path = event_root / gate.RECEIPT_NAME
    if not os.path.lexists(event_root):
        assert not os.path.lexists(receipt_path)
        assert sequence in {
            correction.SOURCE_SEQUENCE,
            correction.CORRECTION_SEQUENCE,
        }
        return "PRE_R007_GATE_ABSENT"
    assert sequence in {
        correction.CORRECTION_SEQUENCE,
        correction.STARTED_SEQUENCE,
    }
    started = _started()
    if sequence == correction.CORRECTION_SEQUENCE:
        context = gate.load_gate_context(root, require_live_snapshot=False)
        evidence = started.validate_gate_evidence(
            root,
            live,
            live_raw,
            context,
            event_id=gate.STARTED_EVENT_ID,
            capture_repository_state=started._sealed_gate_repository_payload,
        )
        assert evidence.receipt["status"] == "PASS"
        assert evidence.receipt["implementation_start_gate_contract_binding"] == (
            gate.expected_r007_binding()
        )
    else:
        started.require_started_checkpoint(
            root,
            live,
            require_live_snapshot=False,
        )
        binding = started.goal_start_gate_receipt_binding(root, live)
        assert binding["path"] == receipt_path.relative_to(root).as_posix()
    return "POST_R007_EXACT_SEALED_PASS"


def test_live_descendant_is_canonical_and_reduces_to_exact_seq94() -> None:
    raw, source, _live_raw, _live, sequence = _owner_validated_exact_seq94(ROOT)
    correction = _correction()
    assert sequence in {
        correction.SOURCE_SEQUENCE,
        correction.CORRECTION_SEQUENCE,
        correction.STARTED_SEQUENCE,
    }
    history = source["goal_execution"]["transition_history"]
    assert len(history) == correction.SOURCE_SEQUENCE == 94
    assert history[-1]["event_id"] == correction.SOURCE_EVENT_ID
    assert history[-1]["event_sha256"] == correction.SOURCE_EVENT_SHA256
    assert hashlib.sha256(raw).hexdigest() == correction.SOURCE_CHECKPOINT_SHA256


def test_live_004_namespace_is_absent_or_exact_post_r007_pass() -> None:
    _raw, _source, live_raw, live, sequence = _owner_validated_exact_seq94(ROOT)
    state = _require_live_004_physical_state(ROOT, live_raw, live, sequence)
    assert state in {"PRE_R007_GATE_ABSENT", "POST_R007_EXACT_SEALED_PASS"}


def test_historical_and_current_nonauthority_metadata_remain_separate() -> None:
    _raw, source, _live_raw, live, sequence = _owner_validated_exact_seq94(ROOT)
    correction = _correction()
    source_binding = source["goal_execution"]["transition_history"][-1][
        "source_checkpoint_binding"
    ]
    assert source_binding["preflight_attempt_004"] == (
        correction.HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
    )
    assert source_binding["preflight_attempt_004"]["namespace_present"] is False
    assert source_binding["preflight_attempt_004"]["receipt_present"] is False
    if sequence >= correction.CORRECTION_SEQUENCE:
        if sequence == correction.STARTED_SEQUENCE:
            started = _started()
            seq95_raw = started.reconstructed_seq95_checkpoint_bytes(ROOT, live)
            live = correction.seq90.strict_json(seq95_raw, "reconstructed seq95")
        binding = live["goal_execution"]["transition_history"][-1][
            "source_checkpoint_binding"
        ]
        assert binding["preflight_attempt_004"] == (
            correction.HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        )
        assert binding["r006_preflight_attempt_004"] == (
            correction.R006_PREFLIGHT_ATTEMPT_004
        )
        assert binding["r006_preflight_attempt_004"]["namespace_present"] is False
        assert binding["r006_preflight_attempt_004"]["receipt_present"] is False


def test_stage_parser_preserves_historical_evidence_and_scopes_004() -> None:
    _raw, _source, live_raw, _live, sequence = _owner_validated_exact_seq94(ROOT)
    correction = _correction()
    gate = _gate()
    directories, paths = gate._checkpoint_bound_gate_event_directories(live_raw)
    passed_receipt = (
        gate.GATE_ROOT_RELATIVE
        / correction.PASSED_GATE_EVENT_ID
        / gate.RECEIPT_NAME
    )
    current_receipt = (
        gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID / gate.RECEIPT_NAME
    )
    assert passed_receipt.parent in directories
    assert passed_receipt in paths
    if sequence == correction.STARTED_SEQUENCE:
        assert current_receipt in paths
    else:
        assert current_receipt not in paths


def test_stage_owner_public_apis_are_lazy_and_sequence_exact() -> None:
    correction = _correction()
    started = _started()
    gate = _gate()
    assert correction.SOURCE_SEQUENCE == 94
    assert correction.CORRECTION_SEQUENCE == gate.SOURCE_SEQUENCE == 95
    assert correction.STARTED_SEQUENCE == gate.EVENT_SEQUENCE == 96
    assert started.SOURCE_SEQUENCE == 95
    assert started.EVENT_SEQUENCE == 96
    assert started.STARTED_EVENT_ID == gate.STARTED_EVENT_ID
    assert callable(correction.reconstructed_seq94_checkpoint_bytes)
    assert callable(started.reconstructed_seq95_checkpoint_bytes)
    assert callable(started.require_started_checkpoint)
