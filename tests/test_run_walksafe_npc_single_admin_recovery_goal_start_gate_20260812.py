from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

import pytest

from scripts import run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812 as gate


ROOT = Path(__file__).resolve().parents[1]
EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-SINGLE-ADMIN-RECOVERY-"
    "20260812-002"
)


def _event(value: dict[str, object]) -> dict[str, object]:
    result = copy.deepcopy(value)
    result["event_sha256"] = gate.event_sha256(result)
    return result


def _seq59_source() -> dict[str, object]:
    live = json.loads((ROOT / gate.CHECKPOINT_RELATIVE).read_bytes())
    candidate = copy.deepcopy(live)
    state = candidate["goal_execution"]
    history = state["transition_history"]
    del history[57:]
    ready = history[-1]
    assert ready["sequence"] == 57
    assert ready["event_sha256"] == gate.SOURCE_READY_EVENT_SHA256

    runtime_after = copy.deepcopy(ready["runtime_after"])
    reanchor = _event(
        {
            "sequence": 58,
            "event_id": gate.CONTROL_REANCHOR_EVENT_ID,
            "event_type": "GOAL_START_CONTROL_REANCHORED",
            "occurred_on": "2026-08-12",
            "occurred_at": "2026-08-12T22:32:20+09:00",
            "previous_focus_goal_id": gate.TARGET_GOAL_ID,
            "previous_focus_content_sha256": gate.TARGET_GOAL_SHA256,
            "focus_goal_id": gate.TARGET_GOAL_ID,
            "focus_goal_content_sha256": gate.TARGET_GOAL_SHA256,
            "subject_goal_id": gate.TARGET_GOAL_ID,
            "from_status": "READY",
            "to_status": "READY",
            "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
            "status_changes": {},
            "runtime_after": runtime_after,
            "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": candidate["schema_version"],
            "evidence_refs": ["GOAL_START_CONTROL_REANCHOR_AUTHORIZATION"],
            "source_ready_event_binding": {
                "sequence": 57,
                "event_id": gate.READY_EVENT_ID,
                "event_sha256": gate.SOURCE_READY_EVENT_SHA256,
                "goal_id": gate.TARGET_GOAL_ID,
                "status": "READY",
            },
            "contract_supersession": {
                "previous_contract_binding": gate.R001_CONTRACT_BINDING,
                "replacement_contract_binding": gate.expected_contract_binding(),
                "reason_code": "CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED",
            },
            "repository_context_reanchor": {
                "before": (
                    gate._control_reanchor._source_repository_context_binding()
                ),
                "after": {},
            },
            "authorization_binding": {},
            "independent_review_binding": {},
            "claim_boundary": {},
            "unchanged_control_projection": {"before": {}, "after": {}},
            "previous_event_sha256": gate.SOURCE_READY_EVENT_SHA256,
        }
    )
    history.append(reanchor)
    correction = _event(
        {
            "sequence": 59,
            "event_id": gate.CONTROL_CORRECTION_EVENT_ID,
            "event_type": "GOAL_START_CONTROL_REANCHORED",
            "occurred_on": "2026-08-12",
            "occurred_at": "2026-08-12T23:18:02+09:00",
            "previous_focus_goal_id": gate.TARGET_GOAL_ID,
            "previous_focus_content_sha256": gate.TARGET_GOAL_SHA256,
            "focus_goal_id": gate.TARGET_GOAL_ID,
            "focus_goal_content_sha256": gate.TARGET_GOAL_SHA256,
            "subject_goal_id": gate.TARGET_GOAL_ID,
            "from_status": "READY",
            "to_status": "READY",
            "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
            "status_changes": {},
            "runtime_after": runtime_after,
            "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": candidate["schema_version"],
            "evidence_refs": ["GOAL_START_CONTROL_REANCHOR_CORRECTION"],
            "previous_event_sha256": reanchor["event_sha256"],
        }
    )
    history.append(correction)
    state["transition_history_anchor_sha256"] = correction["event_sha256"]
    state["package_id"] = gate.PACKAGE_ID
    state["package_status"] = "ACTIVE"
    state["activation_status"] = "ACTIVE"
    state["focus_goal_id"] = gate.TARGET_GOAL_ID
    state["focus_goal_path"] = gate.GOAL_RELATIVE.as_posix()
    state["focus_work_item_id"] = gate.WORK_ITEM_ID
    state["focus_source"] = "IMPLEMENTATION_BACKLOG"
    state["ready_frontier_goal_ids"] = list(gate.READY_FRONTIER)
    current = candidate["current_work"]
    current["work_item_id"] = gate.WORK_ITEM_ID
    current["status"] = "READY"
    current["current_focus"] = (
        "NPC-SINGLE-ADMIN-RECOVERY/GAP-008 Goal READY; internal start gate not run"
    )
    current["release_completion_claimed"] = False
    return candidate


def test_r002_contract_has_exact_private_internal_eight_check_order() -> None:
    checks, contract = gate._load_gate_contract(ROOT)
    raw = (ROOT / gate.CONTRACT_RELATIVE).read_bytes()

    assert hashlib.sha256(raw).hexdigest() == gate.CONTRACT_FILE_SHA256
    assert gate.canonical_sha256(contract) == gate.CONTRACT_CANONICAL_SHA256
    assert tuple(check_id for check_id, _ in checks) == gate.EXPECTED_CHECK_IDS
    commands = dict(checks)
    assert "run_walksafe_test_layers_current.sh validate" in commands[
        "TEST_LAYER_REGISTRY_VALIDATE"
    ]
    assert "backend/tests/test_admin_security.py" in commands[
        "BACKEND_ADMIN_SECURITY_RECOVERY_POSTGRES"
    ]
    assert ":adminapp:testDebugUnitTest" in commands["ANDROID_ADMIN_INTERNAL"]
    assert ":adminapp:assembleDebug" in commands["ANDROID_ADMIN_INTERNAL"]
    assert ":adminapp:lintDebug" in commands["ANDROID_ADMIN_INTERNAL"]
    assert "--max-workers=1" in commands["ANDROID_ADMIN_INTERNAL"]
    assert gate.ROOT_CONTROL_TEST_RELATIVE.as_posix() in commands[
        "ROOT_NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REGRESSION"
    ]
    for _, command in checks:
        for fragment in gate.FORBIDDEN_COMMAND_FRAGMENTS:
            assert fragment not in command.lower()


def test_exact_seq59_control_correction_preserves_seq57_ready_receipt_anchor() -> None:
    source = _seq59_source()
    ready_sha256, occurred_at = gate._validate_ready_source(
        source,
        contract_binding=gate.expected_contract_binding(),
    )

    assert ready_sha256 == gate.SOURCE_READY_EVENT_SHA256
    assert occurred_at.isoformat() == "2026-08-12T23:18:02+09:00"
    assert hashlib.sha256(
        json.dumps(source, ensure_ascii=False, indent=2).encode("utf-8")
    ).hexdigest() != ready_sha256


def test_gate_context_binds_seq59_checkpoint_bytes_and_seq57_ready(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _seq59_source()
    for relative in gate.SOURCE_GUARD_RELATIVES:
        destination = tmp_path / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        if relative == gate.CHECKPOINT_RELATIVE:
            destination.write_text(
                json.dumps(source, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            destination.chmod(0o600)
        else:
            destination.write_bytes((ROOT / relative).read_bytes())

    validated: list[tuple[Path, object, bool]] = []

    def validate_control_correction(
        root: Path,
        checkpoint: object,
        *,
        run_external_validators: bool,
    ) -> None:
        validated.append((root, checkpoint, run_external_validators))

    monkeypatch.setattr(
        gate._control_correction,
        "require_control_corrected_checkpoint",
        validate_control_correction,
    )

    context = gate.load_gate_context(tmp_path)
    checkpoint_bytes = (tmp_path / gate.CHECKPOINT_RELATIVE).read_bytes()
    assert context.checkpoint_sha256 == hashlib.sha256(checkpoint_bytes).hexdigest()
    assert context.source_ready_event_sha256 == gate.SOURCE_READY_EVENT_SHA256
    assert context.source_ready_occurred_at.isoformat() == (
        "2026-08-12T23:18:02+09:00"
    )
    assert context.contract_binding == gate.expected_contract_binding()
    assert tuple(check_id for check_id, _ in context.checks) == gate.EXPECTED_CHECK_IDS
    assert validated == [(tmp_path.resolve(), source, False)]


@pytest.mark.parametrize(
    ("mutator", "message"),
    (
        (
            lambda source: source["goal_execution"]["transition_history"][-1].update(
                {"previous_event_sha256": "0" * 64}
            ),
            "seq59 control-correction event differs",
        ),
        (
            lambda source: (
                source["goal_execution"]["transition_history"][-2][
                    "contract_supersession"
                ].update({"reason_code": "UNBOUND"}),
                source["goal_execution"]["transition_history"][-2].update(
                    {
                        "event_sha256": gate.event_sha256(
                            source["goal_execution"]["transition_history"][-2]
                        )
                    }
                ),
            ),
            "contract supersession differs",
        ),
        (
            lambda source: source["goal_execution"]["transition_history"][-3].update(
                {"event_sha256": "0" * 64}
            ),
            "seq57 READY event differs",
        ),
    ),
)
def test_seq59_source_rejects_lineage_contract_or_schema_tampering(
    mutator: object,
    message: str,
) -> None:
    source = _seq59_source()
    assert callable(mutator)
    mutator(source)
    with pytest.raises(gate.GateError, match=message):
        gate._validate_ready_source(
            source,
            contract_binding=gate.expected_contract_binding(),
        )


def test_event_and_receipt_identity_are_goal_scoped() -> None:
    assert gate._document_id(EVENT_ID) == (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-"
        "NPC-SINGLE-ADMIN-RECOVERY-20260812-002"
    )
    for invalid in (
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-SINGLE-ADMIN-RECOVERY-20260812-001",
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-20260812-001",
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-SINGLE-ADMIN-RECOVERY-20260812-1",
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260812-001",
    ):
        with pytest.raises(gate.GateError):
            gate._document_id(invalid)


def test_wrapper_exposes_the_exact_seq59_and_receipt_contract() -> None:
    assert gate.SOURCE_SEQUENCE == 59
    assert gate.CONTROL_REANCHOR_SEQUENCE == 58
    assert gate.CONTROL_CORRECTION_SEQUENCE == 59
    assert gate.CONTROL_REANCHOR_EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-NPC-20260812-001"
    )
    assert gate.CONTROL_CORRECTION_EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
        "NPC-CORRECTION-20260812-001"
    )
    assert gate.RECEIPT_FIELDS == gate._impl.RECEIPT_FIELDS
    assert "source_checkpoint_sha256" in gate.RECEIPT_FIELDS
    assert "source_ready_event_sha256" in gate.RECEIPT_FIELDS
    assert gate.expected_contract_binding() == {
        "schema_version": "1.1",
        "document_id": gate.CONTRACT_DOCUMENT_ID,
        "path": gate.CONTRACT_RELATIVE.as_posix(),
        "file_sha256": gate.CONTRACT_FILE_SHA256,
        "contract_id": gate.CONTRACT_ID,
        "contract_version": gate.CONTRACT_VERSION,
        "canonical_contract_sha256": gate.CONTRACT_CANONICAL_SHA256,
    }
    assert tuple(gate.RUNTIME_BINDING_RELATIVES) == (
        Path("apps/android/gradle/wrapper/gradle-wrapper.properties"),
        Path("apps/android/gradle/wrapper/gradle-wrapper.jar"),
        Path("apps/android/gradle/verification-metadata.xml"),
        Path("apps/android/adminapp/gradle.lockfile"),
    )
    assert gate.CONTROL_REANCHOR_RELATIVE in gate.SOURCE_GUARD_RELATIVES
    assert gate.CONTROL_CORRECTION_RELATIVE in gate.SOURCE_GUARD_RELATIVES
