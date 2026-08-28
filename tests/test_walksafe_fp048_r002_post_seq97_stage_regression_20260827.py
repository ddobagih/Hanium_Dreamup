"""Stage-safe FP048 R002 witnesses from exact seq97 through seq99."""

from __future__ import annotations

import hashlib
import importlib
import importlib.util
import os
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
EXACT_SEQ97_BYTE_LENGTH = 4_740_758
EXACT_SEQ97_SHA256 = (
    "007732d63e71a199c3c2a4b275d8e6878ae06385b730220407d4649cfa0247ed"
)
EXACT_SEQ97_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-GATE-CONTRACT-CORRECTED-"
    "FP048-R002-20260826-005"
)
EXACT_SEQ97_EVENT_SHA256 = (
    "2dce092fa3e01ea767e931cbe130aed32ce3eed96d50c05ef86cde9ff5a96bf1"
)
CORRECTION_MODULE = (
    "scripts.apply_walksafe_fp048_r002_start_gate_execution_"
    "correction_seq98_20260827"
)
STARTED_MODULE = "scripts.apply_walksafe_fp048_r002_goal_started_seq99_20260827"


def _source_owner() -> Any:
    return importlib.import_module(
        "scripts.apply_walksafe_fp048_r002_start_gate_snapshot_hygiene_"
        "correction_seq97_20260826"
    )


def _correction() -> Any:
    return importlib.import_module(CORRECTION_MODULE)


def _started() -> Any:
    return importlib.import_module(STARTED_MODULE)


def _gate() -> Any:
    return importlib.import_module(
        "scripts.run_walksafe_fp048_r002_goal_start_gate_r010_20260827"
    )


def _available(module: str) -> bool:
    return importlib.util.find_spec(module) is not None


def _optional_module(module: str) -> Any | None:
    if not _available(module):
        return None
    try:
        return importlib.import_module(module)
    except Exception:
        return None


def _owner_validated_exact_seq97(
    root: Path,
) -> tuple[bytes, dict[str, Any], bytes, dict[str, Any], int, dict[str, Any] | None]:
    owner = _source_owner()
    live_read = owner.seq90._stable_read(root, owner.CHECKPOINT_REL)
    live = owner.seq90.strict_json(live_read.raw, owner.CHECKPOINT_REL.as_posix())
    assert live_read.raw == owner.checkpoint_json_bytes(live)
    history = live.get("goal_execution", {}).get("transition_history")
    assert isinstance(history, list)
    sequence = len(history)
    seq98: dict[str, Any] | None = None
    if sequence == owner.CORRECTION_SEQUENCE:
        owner.require_snapshot_hygiene_corrected_checkpoint(
            root,
            live,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        raw, source = live_read.raw, live
    elif sequence == 98:
        correction = _correction()
        correction.require_start_gate_execution_corrected_checkpoint(
            root,
            live,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        seq98 = live
        raw = correction.reconstructed_seq97_checkpoint_bytes(root, live)
        source = owner.seq90.strict_json(raw, "reconstructed seq97")
    elif sequence == 99:
        started = _started()
        started.require_started_checkpoint(
            root,
            live,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        seq98_raw = started.reconstructed_seq98_checkpoint_bytes(root, live)
        seq98 = owner.seq90.strict_json(seq98_raw, "reconstructed seq98")
        correction = _correction()
        correction.require_start_gate_execution_corrected_checkpoint(
            root,
            seq98,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        raw = correction.reconstructed_seq97_checkpoint_bytes(root, seq98)
        source = owner.seq90.strict_json(raw, "reconstructed seq97")
    else:
        raise AssertionError(f"unsupported live FP048 R002 stage: seq{sequence}")
    assert len(raw) == EXACT_SEQ97_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == EXACT_SEQ97_SHA256
    assert raw == owner.checkpoint_json_bytes(source)
    assert raw == owner.canonical_seq97_checkpoint_bytes(root, source)
    source_history = source["goal_execution"]["transition_history"]
    assert len(source_history) == 97
    assert source_history[-1]["event_id"] == EXACT_SEQ97_EVENT_ID
    assert source_history[-1]["event_sha256"] == EXACT_SEQ97_EVENT_SHA256
    return raw, source, live_read.raw, live, sequence, seq98


def _require_live_005_physical_state(
    root: Path,
    live_raw: bytes,
    live: dict[str, Any],
    sequence: int,
    seq98: dict[str, Any] | None,
) -> str:
    gate = _gate()
    event_root = root / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
    receipt_path = event_root / gate.RECEIPT_NAME
    if not os.path.lexists(event_root):
        assert not os.path.lexists(receipt_path)
        assert sequence in {97, 98}
        return "PRE_R010_GATE_ABSENT"
    assert sequence in {98, 99}
    assert os.path.lexists(receipt_path)
    started = _started()
    if sequence == 98:
        loader = getattr(started, "require_published_r010_gate_for_seq98", None)
        assert callable(loader), "seq99 publisher lacks the R010 evidence loader"
        evidence = loader(root, live, live_raw)
        assert evidence.receipt["status"] == "PASS"
        assert evidence.receipt["implementation_start_gate_contract_binding"] == (
            gate.expected_r010_binding()
        )
    else:
        assert seq98 is not None
        started.require_started_checkpoint(
            root,
            live,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        binding = started.goal_start_gate_receipt_binding(root, live)
        assert binding["path"] == receipt_path.relative_to(root).as_posix()
    return "POST_R010_EXACT_SEALED_PASS"


def test_live_descendant_is_canonical_and_reduces_to_exact_seq97() -> None:
    raw, source, _live_raw, _live, sequence, _seq98 = (
        _owner_validated_exact_seq97(ROOT)
    )
    assert sequence in {97, 98, 99}
    assert hashlib.sha256(raw).hexdigest() == EXACT_SEQ97_SHA256
    assert source["goal_execution"]["transition_history"][-1]["event_id"] == (
        EXACT_SEQ97_EVENT_ID
    )


def test_live_r009_failure_namespace_is_exact_consumed_nonauthority() -> None:
    _raw, _source, _live_raw, _live, sequence, seq98 = (
        _owner_validated_exact_seq97(ROOT)
    )
    gate = _gate()
    gate.require_r009_execution_failure_namespace(ROOT)
    failure = gate.expected_r009_execution_failure_binding()
    assert failure["event_identity_status"] == "CONSUMED_FAILED_NO_RECEIPT"
    assert failure["authority_status"] == "NONAUTHORITY"
    assert failure["replacement_event_id"] == gate.STARTED_EVENT_ID
    if sequence >= 98:
        assert seq98 is not None
        source_binding = seq98["goal_execution"]["transition_history"][-1][
            "source_checkpoint_binding"
        ]
        assert gate._strict_json_equal(
            source_binding["r009_execution_failure"], failure
        )


def test_live_005_namespace_is_absent_or_exact_post_r010_pass() -> None:
    _raw, _source, live_raw, live, sequence, seq98 = (
        _owner_validated_exact_seq97(ROOT)
    )
    state = _require_live_005_physical_state(
        ROOT, live_raw, live, sequence, seq98
    )
    assert state in {"PRE_R010_GATE_ABSENT", "POST_R010_EXACT_SEALED_PASS"}


def test_stage_parser_preserves_r009_logs_and_excludes_missing_receipt() -> None:
    _raw, _source, live_raw, _live, sequence, _seq98 = (
        _owner_validated_exact_seq97(ROOT)
    )
    gate = _gate()
    _directories, paths = gate._checkpoint_bound_gate_event_directories(live_raw)
    failure = gate.expected_r009_execution_failure_binding()
    failure_logs = {Path(row["path"]) for row in failure["logs"]}
    receipt = Path(failure["receipt_path"])
    if sequence == 97:
        assert not (failure_logs & set(paths))
    else:
        assert failure_logs <= set(paths)
    assert receipt not in paths


def test_stage_owner_public_apis_are_lazy_and_sequence_exact() -> None:
    owner = _source_owner()
    gate = _gate()
    live = owner.seq90.strict_json(
        owner.seq90._stable_read(ROOT, owner.CHECKPOINT_REL).raw,
        owner.CHECKPOINT_REL.as_posix(),
    )
    sequence = len(live["goal_execution"]["transition_history"])
    assert owner.CORRECTION_SEQUENCE == 97
    assert gate.SOURCE_SEQUENCE == 98
    assert gate.EVENT_SEQUENCE == 99
    assert gate.CORRECTION_EVENT_ID.endswith("20260827-006")
    assert gate.STARTED_EVENT_ID.endswith("20260827-005")
    assert callable(owner.canonical_seq97_checkpoint_bytes)
    assert callable(owner.reconstructed_seq96_checkpoint_bytes)
    correction = _optional_module(CORRECTION_MODULE)
    if correction is None:
        assert sequence == 97
        return
    assert correction.SOURCE_SEQUENCE == owner.CORRECTION_SEQUENCE
    assert correction.CORRECTION_SEQUENCE == gate.SOURCE_SEQUENCE
    assert correction.STARTED_SEQUENCE == gate.EVENT_SEQUENCE
    assert correction.CORRECTION_EVENT_ID == gate.CORRECTION_EVENT_ID
    assert correction.STARTED_EVENT_ID == gate.STARTED_EVENT_ID
    assert callable(correction.require_start_gate_execution_corrected_checkpoint)
    assert callable(correction.reconstructed_seq97_checkpoint_bytes)
    assert callable(correction.r009_execution_failure_binding)
    started = _optional_module(STARTED_MODULE)
    if started is None:
        assert sequence in {97, 98}
        return
    assert started.SOURCE_SEQUENCE == gate.SOURCE_SEQUENCE
    assert started.EVENT_SEQUENCE == gate.EVENT_SEQUENCE
    assert started.STARTED_EVENT_ID == gate.STARTED_EVENT_ID
    assert callable(started.reconstructed_seq98_checkpoint_bytes)
    assert callable(started.require_started_checkpoint)
    assert callable(started.require_published_r010_gate_for_seq98)
