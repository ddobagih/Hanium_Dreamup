from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

import pytest

from scripts import (
    apply_walksafe_fp048_r002_goal_start_control_correction_seq91_20260826
    as correction,
)
from scripts import run_walksafe_fp048_r002_goal_start_gate_r002_20260826 as r002
from scripts import run_walksafe_fp048_r002_goal_start_gate_r003_20260826 as gate


ROOT = Path(__file__).resolve().parents[1]


def _binding(path: str, marker: str = "a") -> dict[str, object]:
    return {"path": path, "sha256": marker * 64, "byte_length": 1}


def _seq91() -> tuple[dict[str, object], dict[str, object]]:
    _raw, source = correction.load_exact_seq90_source(ROOT)
    paths = list(source["working_tree_snapshot"]["managed_changed_paths"])
    replacement = {"contract_id": correction.R003_CONTRACT_ID}
    projected, event = correction.project_seq91(
        ROOT,
        source,
        managed_paths=paths,
        path_set_sha256="e" * 64,
        content_set_sha256="f" * 64,
        occurred_at="2026-08-26T05:30:00+09:00",
        authorization_binding=_binding("authorization.json"),
        review_binding={
            "assignment": _binding("assignment.json", "b"),
            "review_result": _binding("review-result.json", "c"),
            "independent_review": _binding("independent-review.json", "d"),
        },
        contract_binding=replacement,
        runner_binding=_binding("runner.py"),
    )
    return projected, event


def test_corrected_start_identity_is_exact_and_burned_id_is_rejected() -> None:
    assert gate.SOURCE_SEQUENCE == correction.CORRECTION_SEQUENCE == 91
    assert gate.EVENT_SEQUENCE == correction.STARTED_SEQUENCE == 92
    assert gate.STARTED_EVENT_ID == correction.STARTED_EVENT_ID
    assert gate._document_id(gate.STARTED_EVENT_ID).endswith("20260826-002")
    with pytest.raises(gate.GateError, match="unused corrected start ID"):
        gate._document_id(r002.STARTED_EVENT_ID)


def test_r003_contract_has_exact_reviewed_five_check_order_and_binding() -> None:
    contract, binding = correction.load_r003_contract(ROOT)
    assert tuple(item["check_id"] for item in contract["ordered_checks"]) == (
        correction.R003_CONTRACT_CHECK_IDS
    )
    assert tuple(
        item["command"] for item in contract["ordered_checks"]
    ) == tuple(
        correction.R003_CONTRACT_COMMANDS[check_id]
        for check_id in correction.R003_CONTRACT_CHECK_IDS
    )
    raw = (ROOT / gate.CONTRACT_RELATIVE).read_bytes()
    assert len(raw) == correction.R003_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == correction.R003_FILE_SHA256
    assert gate.canonical_sha256(json.loads(raw)) == correction.R003_CANONICAL_SHA256
    assert binding == correction.r003_contract_binding(ROOT)


def test_r003_contract_identity_replaces_fp008_receipt_builder_globals(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    binding = correction.r003_contract_binding(ROOT)
    stale = dict(binding)
    stale["contract_version"] = "2026-08-03.1"
    stale["canonical_contract_sha256"] = "b" * 64
    with pytest.raises(gate.GateError, match="reviewed binding differs"):
        gate._load_gate_contract(ROOT, binding=stale)

    monkeypatch.setattr(gate._impl, "CONTRACT_ID", "STALE-FP008")
    monkeypatch.setattr(gate._impl, "CONTRACT_VERSION", "2026-08-03.1")
    monkeypatch.setattr(gate._impl, "CONTRACT_FILE_SHA256", "a" * 64)
    monkeypatch.setattr(gate._impl, "CONTRACT_CANONICAL_SHA256", "b" * 64)
    gate._load_gate_contract(ROOT, binding=binding)
    receipt_builder_identity = {
        "contract_id": gate._impl.CONTRACT_ID,
        "check_command_contract_version": gate._impl.CONTRACT_VERSION,
        "contract_file_sha256": gate._impl.CONTRACT_FILE_SHA256,
        "check_command_contract_sha256": gate._impl.CONTRACT_CANONICAL_SHA256,
    }
    assert receipt_builder_identity == {
        "contract_id": correction.R003_CONTRACT_ID,
        "check_command_contract_version": correction.R003_CONTRACT_VERSION,
        "contract_file_sha256": correction.R003_FILE_SHA256,
        "check_command_contract_sha256": correction.R003_CANONICAL_SHA256,
    }


def test_runtime_sync_uses_actual_correction_authority() -> None:
    _unused, loaded = gate._sync_runtime_paths()
    assert loaded is correction
    assert gate._impl.GOAL_RELATIVE == correction.GOAL_PATH
    assert gate._impl.WORK_ITEM_ID == correction.WORK_ITEM_ID
    assert gate._impl.READY_EVENT_ID == correction.READY_EVENT_ID
    assert gate._impl.READY_FRONTIER == tuple(correction.READY_FRONTIER)
    assert gate._impl.SOURCE_SEQUENCE == correction.CORRECTION_SEQUENCE
    assert gate.FP008_RUNTIME_TEST_RELATIVE in gate._impl.SOURCE_GUARD_RELATIVES
    assert all(
        path in gate._impl.SOURCE_GUARD_RELATIVES
        for path in (
            *gate.GATE_ADAPTER_RUNTIME_CLOSURE_RELATIVES,
            *gate.IMPORT_TRUST_RUNTIME_CLOSURE_RELATIVES,
            *gate.SEQ90_TRANSPORT_TRUST_ROOT_RELATIVES,
        )
    )
    assert gate.GATE_ADAPTER_RUNTIME_CLOSURE_RELATIVES == (
        gate.R002_GATE_RUNTIME_RELATIVE,
        gate.R002_GATE_RUNTIME_TEST_RELATIVE,
        gate.SEQ88_89_RUNTIME_RELATIVE,
        gate.SEQ88_89_RUNTIME_TEST_RELATIVE,
    )


@pytest.mark.parametrize(
    "relative",
    (
        *gate.GATE_ADAPTER_RUNTIME_CLOSURE_RELATIVES,
        *gate.IMPORT_TRUST_RUNTIME_CLOSURE_RELATIVES,
        *gate.SEQ90_TRANSPORT_TRUST_ROOT_RELATIVES,
    ),
)
def test_each_reviewed_runtime_layer_is_pinned_against_same_byte_aba(
    tmp_path: Path,
    relative: Path,
) -> None:
    target = tmp_path / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(b"reviewed runtime\n")
    guard = gate._impl._RetainedSourceGuard.capture(tmp_path, (relative,))
    replacement = target.with_name(target.name + ".replacement")
    replacement.write_bytes(target.read_bytes())
    os.replace(replacement, target)
    try:
        with pytest.raises(gate.GateError, match="retained .*changed"):
            guard.verify()
    finally:
        guard.close(RuntimeError("expected same-byte ABA drift"))


def test_corrected_authority_separates_seq89_ready_from_seq91_activation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    projected, correction_event = _seq91()
    ready = projected["goal_execution"]["transition_history"][88]
    replacement = correction_event["contract_supersession"][
        "replacement_contract_binding"
    ]
    monkeypatch.setattr(
        correction, "require_control_corrected_checkpoint", lambda *_args: None
    )
    monkeypatch.setattr(correction, "r003_contract_binding", lambda _root: replacement)
    authority = gate.bind_corrected_source(ROOT, projected)
    assert authority.ready_event_sha256 == ready["event_sha256"]
    assert authority.correction_event_sha256 == correction_event["event_sha256"]
    assert authority.ready_event_sha256 != authority.correction_event_sha256
    assert gate._impl.SOURCE_READY_EVENT_SHA256 == ready["event_sha256"]


def test_production_bind_removes_forged_module_goal_sha256_shadow(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    projected, correction_event = _seq91()
    replacement = correction_event["contract_supersession"][
        "replacement_contract_binding"
    ]
    monkeypatch.setattr(
        correction, "require_control_corrected_checkpoint", lambda *_args: None
    )
    monkeypatch.setattr(correction, "r003_contract_binding", lambda _root: replacement)
    monkeypatch.setitem(gate.__dict__, "TARGET_GOAL_SHA256", "0" * 64)
    monkeypatch.setattr(gate._impl, "TARGET_GOAL_SHA256", "1" * 64)
    authority = gate.bind_corrected_source(ROOT, projected)
    assert "TARGET_GOAL_SHA256" not in gate.__dict__
    assert authority.goal_sha256 == correction.GOAL_SHA256
    assert gate._impl.TARGET_GOAL_SHA256 == correction.GOAL_SHA256
    assert gate.TARGET_GOAL_SHA256 == correction.GOAL_SHA256


def test_gate_context_keeps_ready_and_activation_hashes_distinct(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    projected, correction_event = _seq91()
    ready = projected["goal_execution"]["transition_history"][88]
    replacement = correction_event["contract_supersession"][
        "replacement_contract_binding"
    ]
    checkpoint_raw = correction.seq90.checkpoint_json_bytes(projected)
    contract_raw = (ROOT / gate.CONTRACT_RELATIVE).read_bytes()
    manifest_raw = (ROOT / gate.MANIFEST_RELATIVE).read_bytes()
    retained = {path: b"retained" for path in gate._impl.SOURCE_GUARD_RELATIVES}
    retained[gate.CHECKPOINT_RELATIVE] = checkpoint_raw
    retained[gate.CONTRACT_RELATIVE] = contract_raw
    retained[gate.MANIFEST_RELATIVE] = manifest_raw
    monkeypatch.setattr(
        correction, "require_control_corrected_checkpoint", lambda *_args: None
    )
    monkeypatch.setattr(correction, "r003_contract_binding", lambda _root: replacement)
    monkeypatch.setattr(
        correction,
        "reconstructed_seq91_checkpoint_bytes",
        lambda *_args: checkpoint_raw,
    )
    monkeypatch.setattr(
        gate,
        "_load_gate_contract",
        lambda *_args, **_kwargs: (
            tuple(
                (check_id, correction.R003_CONTRACT_COMMANDS[check_id])
                for check_id in correction.R003_CONTRACT_CHECK_IDS
            ),
            json.loads(contract_raw),
        ),
    )
    context = gate.load_gate_context(ROOT, retained)
    assert context.source_ready_event_sha256 == ready["event_sha256"]
    assert context.source_activation_event_sha256 == correction_event["event_sha256"]
    assert context.source_ready_event_sha256 != context.source_activation_event_sha256


def test_private_r002_adapter_does_not_mutate_canonical_r002_module() -> None:
    original = {
        "started": r002.STARTED_EVENT_ID,
        "sync": r002._sync_runtime_paths,
        "document": r002._document_id,
        "context": r002.load_gate_context,
        "impl_document": r002._impl._document_id,
    }
    gate._install_base_adapter()
    assert gate.base is not r002
    assert r002.STARTED_EVENT_ID == original["started"]
    assert r002._sync_runtime_paths is original["sync"]
    assert r002._document_id is original["document"]
    assert r002.load_gate_context is original["context"]
    assert r002._impl._document_id is original["impl_document"]


@pytest.mark.parametrize("entrypoint", ["run_gate", "preflight_gate"])
def test_entrypoints_delegate_without_snapshot_factory_override(
    monkeypatch: pytest.MonkeyPatch,
    entrypoint: str,
) -> None:
    captured: dict[str, object] = {}

    def delegate(_root: Path, _event_id: str, **kwargs: object) -> object:
        captured.update(kwargs)
        return object()

    monkeypatch.setattr(gate, "_install_base_adapter", lambda: None)
    monkeypatch.setattr(gate.base, entrypoint, delegate)
    getattr(gate, entrypoint)(ROOT, gate.STARTED_EVENT_ID, marker=True)
    assert captured == {"marker": True}
    assert "isolated_snapshot_factory" not in captured
