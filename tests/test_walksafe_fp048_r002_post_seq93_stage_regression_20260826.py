"""Post-seq93 witnesses that do not replay the live-sensitive predecessor suite."""

from __future__ import annotations

import copy
import hashlib
import importlib
from pathlib import Path
from typing import Any

import pytest

from scripts import (
    apply_walksafe_fp048_r002_goal_start_branch_semantics_reanchor_seq93_20260826
    as reanchor,
)
ROOT = Path(__file__).resolve().parents[1]
EXACT_SEQ93_BYTE_LENGTH = 3_438_449
EXACT_SEQ93_SHA256 = (
    "1d1455a1e5a454a84f3e04da17faf9c2804cee64d193db462417b85635edc455"
)
FROZEN_SEQ93_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_start_branch_semantics_"
    "reanchor_seq93_20260826.py"
)
FROZEN_SEQ93_TEST_BYTE_LENGTH = 21_933
FROZEN_SEQ93_TEST_SHA256 = (
    "6f19187e66c1221ae17ef3d7caeff288310232ff00714d476e111d147683f89c"
)


def _correction() -> Any:
    """Import lazily so this adapter remains collectible before seq94 lands."""
    return importlib.import_module(
        "scripts.apply_walksafe_fp048_r002_start_gate_contract_correction_"
        "seq94_20260826"
    )


def _started() -> Any:
    return importlib.import_module(
        "scripts.apply_walksafe_fp048_r002_goal_started_seq95_20260826"
    )


def _binding(path: Path, marker: str) -> dict[str, Any]:
    return {
        "path": path.as_posix(),
        "sha256": marker * 64,
        "byte_length": 1,
    }


def _owner_validated_exact_seq93(root: Path) -> tuple[bytes, dict[str, Any], int]:
    correction = _correction()
    live_read = correction.seq90._stable_read(root, correction.CHECKPOINT_REL)
    live = correction.seq90.strict_json(
        live_read.raw, correction.CHECKPOINT_REL.as_posix()
    )
    assert live_read.raw == correction.checkpoint_json_bytes(live)
    history = live.get("goal_execution", {}).get("transition_history")
    assert isinstance(history, list)
    sequence = len(history)
    if sequence == correction.SOURCE_SEQUENCE:
        correction.require_exact_seq93_source(live_read.raw, live, root)
    elif sequence == correction.CORRECTION_SEQUENCE:
        correction.require_contract_corrected_checkpoint(
            root,
            live,
            require_live_snapshot=False,
        )
    elif sequence == correction.STARTED_SEQUENCE:
        started = _started()
        started.require_started_checkpoint(
            root,
            live,
            require_live_snapshot=False,
        )
        seq94_raw = started.reconstructed_seq94_checkpoint_bytes(root, live)
        seq94 = correction.seq90.strict_json(seq94_raw, "reconstructed seq94")
        correction.require_contract_corrected_checkpoint(
            root,
            seq94,
            require_live_snapshot=False,
        )
        raw = correction.reconstructed_seq93_checkpoint_bytes(root, seq94)
        source = correction.seq90.strict_json(raw, "reconstructed seq93")
    else:
        raise AssertionError(f"unsupported live FP048 R002 stage: seq{sequence}")
    if sequence in {correction.SOURCE_SEQUENCE, correction.CORRECTION_SEQUENCE}:
        raw, source = correction.load_exact_seq93_source(root)
    correction.require_exact_seq93_source(raw, source, root)
    assert len(raw) == EXACT_SEQ93_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == EXACT_SEQ93_SHA256
    assert raw == correction.checkpoint_json_bytes(source)
    return raw, source, sequence


def _synthetic_seq94() -> tuple[bytes, dict[str, Any], dict[str, Any]]:
    correction = _correction()
    raw, source = correction.load_exact_seq93_source(ROOT)
    snapshot = source["working_tree_snapshot"]
    authorization = _binding(correction.AUTHORIZATION_REL, "a")
    review = {
        "assignment": _binding(correction.REVIEW_ASSIGNMENT_REL, "b"),
        "review_result": _binding(correction.REVIEW_RESULT_REL, "c"),
        "independent_review": _binding(correction.INDEPENDENT_REVIEW_REL, "d"),
    }
    projected, _event = correction.project_seq94(
        ROOT,
        source,
        managed_paths=snapshot["managed_changed_paths"],
        path_set_sha256=snapshot["path_set_sha256"],
        content_set_sha256=snapshot["content_set_sha256"],
        occurred_at="2026-08-26T15:30:00+09:00",
        authorization_binding_value=authorization,
        review_binding=review,
        passed_gate_binding=correction.passed_gate_attempt_003_binding(ROOT),
        preflight_attempt_binding=correction.preflight_attempt_004_observation(ROOT),
        replacement_contract_binding=correction.r006_contract_binding(ROOT),
        replacement_runner_binding=correction.r006_runner_binding(ROOT),
    )
    return raw, projected, {"authorization": authorization, "review": review}


def test_live_descendant_is_strict_canonical_and_reduces_to_exact_seq93() -> None:
    _raw, source, sequence = _owner_validated_exact_seq93(ROOT)
    assert sequence in {93, 94, 95}
    history = source["goal_execution"]["transition_history"]
    assert len(history) == reanchor.REANCHOR_SEQUENCE == 93
    assert history[-1]["event_id"] == reanchor.REANCHOR_EVENT_ID
    assert history[-1]["event_sha256"] == reanchor.continuation.event_sha256(
        history[-1]
    )


def test_synthetic_seq94_descendant_uses_owner_inverse_for_exact_seq93(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    correction = _correction()
    expected_raw, projected, authorities = _synthetic_seq94()
    original = correction.seq90._stable_read
    identity = original(ROOT, correction.CHECKPOINT_REL).identity
    projected_raw = correction.checkpoint_json_bytes(projected)

    def stable_read(root: Path, relative: Path) -> object:
        if relative == correction.CHECKPOINT_REL:
            return correction.seq90.ReadResult(projected_raw, identity)
        return original(root, relative)

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)
    monkeypatch.setattr(
        correction,
        "authorization_binding",
        lambda _root, _source, **_kwargs: copy.deepcopy(
            authorities["authorization"]
        ),
    )
    monkeypatch.setattr(
        correction,
        "_load_physical_review",
        lambda _root, _source, **_kwargs: (
            copy.deepcopy(authorities["review"]),
            correction._parse_time(
                "2026-08-26T15:29:58+09:00", "result reviewed_at"
            ),
            correction._parse_time(
                "2026-08-26T15:29:59+09:00", "independent reviewed_at"
            ),
        ),
    )
    reconstructed, _source, sequence = _owner_validated_exact_seq93(ROOT)
    assert sequence == 94
    assert reconstructed == expected_raw


def test_seq94_owner_loads_seq93_with_fixed_r003_authority() -> None:
    correction = _correction()
    raw, source, _sequence = _owner_validated_exact_seq93(ROOT)
    assert len(raw) == correction.SOURCE_CHECKPOINT_BYTE_LENGTH
    event = source["goal_execution"]["transition_history"][-1]
    assert event["transition_control_review_binding"] == (
        correction.FROZEN_SEQ93_R003_REVIEW_BINDINGS
    )
    assert event["authorization_binding"] == (
        correction.FROZEN_SEQ93_AUTHORIZATION_BINDING
    )
    historical = correction.FROZEN_SEQ93_R003_REVIEWED_INPUTS
    assert len(historical) == len({path for path, _digest, _length in historical}) == 15
    assert (
        FROZEN_SEQ93_TEST_RELATIVE.as_posix(),
        FROZEN_SEQ93_TEST_SHA256,
        FROZEN_SEQ93_TEST_BYTE_LENGTH,
    ) in historical
    frozen_test_raw = (ROOT / FROZEN_SEQ93_TEST_RELATIVE).read_bytes()
    assert len(frozen_test_raw) == FROZEN_SEQ93_TEST_BYTE_LENGTH
    assert hashlib.sha256(frozen_test_raw).hexdigest() == FROZEN_SEQ93_TEST_SHA256


def test_predecessor_exact_seq92_loader_still_rejects_live_seq93() -> None:
    with pytest.raises(
        reanchor.BranchSemanticsReanchorError,
        match="seq92 source length differs",
    ):
        reanchor.load_exact_seq92_source(ROOT)


def test_current_r001_authorization_and_assignment_are_canonical_inputs() -> None:
    correction = _correction()
    for relative in (
        correction.R001_AUTHORIZATION_REL,
        correction.R001_REVIEW_ASSIGNMENT_REL,
    ):
        raw = (ROOT / relative).read_bytes()
        value = correction.seq90.strict_json(raw, relative.as_posix())
        assert raw == correction.seq90.canonical_json_bytes(value)


def test_historical_r005_preflight_metadata_stays_false_across_descendants() -> None:
    correction = _correction()
    expected = correction._stored_preflight_attempt_004()
    assert expected == correction.CORRECTION_REASON["preflight_attempt_004"]
    live = correction.seq90.strict_json(
        correction.seq90._stable_read(ROOT, correction.CHECKPOINT_REL).raw,
        correction.CHECKPOINT_REL.as_posix(),
    )
    history = live["goal_execution"]["transition_history"]
    if len(history) >= correction.CORRECTION_SEQUENCE:
        observed = history[correction.CORRECTION_SEQUENCE - 1][
            "source_checkpoint_binding"
        ]["preflight_attempt_004"]
        assert observed == expected
    else:
        assert len(history) == correction.SOURCE_SEQUENCE
    assert expected["namespace_present"] is False
    assert expected["receipt_present"] is False
