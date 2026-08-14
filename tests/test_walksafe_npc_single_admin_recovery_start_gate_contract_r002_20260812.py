from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CONTRACT_ROOT = (
    ROOT
    / "docs/control/execution/goal-contracts/"
    "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001"
)
R001_PATH = CONTRACT_ROOT / "initial-start-gate-contract-r001.json"
R002_PATH = CONTRACT_ROOT / "initial-start-gate-contract-r002.json"
CHECKPOINT_PATH = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"

R001_FILE_SHA256 = "0e9b80005e3ad206af6d43d724dfc70688e6d7ef8907ad6ee2c72da2e60679d1"
R001_CANONICAL_SHA256 = "b6a8ada662716ee963f1248ffdf5fdd730c545640a35a7fc11ed134016321186"
SEQ57_EVENT_SHA256 = "08e25cd9808e2301a4af7a3b463d5a1cda795caf41eed57a35de29676f2210ef"
CURRENT_RUNNER_COMMAND = (
    "PYTHON_BIN=/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python "
    "bash scripts/run_walksafe_test_layers_current.sh validate"
)


def _canonical_sha256(value: Any) -> str:
    raw = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def _load(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_bytes())
    assert isinstance(value, dict)
    return value


def test_r002_is_an_add_only_successor_of_the_immutable_r001_contract() -> None:
    r001_raw = R001_PATH.read_bytes()
    r001 = json.loads(r001_raw)
    r002 = _load(R002_PATH)

    assert hashlib.sha256(r001_raw).hexdigest() == R001_FILE_SHA256
    assert _canonical_sha256(r001) == R001_CANONICAL_SHA256
    assert r002["schema_version"] == "1.1"
    assert r002["document_id"] == (
        "WS-NPC-SINGLE-ADMIN-RECOVERY-INITIAL-START-GATE-CONTRACT-20260812-002"
    )
    assert r002["contract_id"] == (
        "WS-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-START-GATE-R002"
    )
    assert r002["contract_version"] == "2026-08-12.1"
    assert r002["target_goal_id"] == r001["target_goal_id"]
    assert r002["target_goal_content_sha256"] == r001["target_goal_content_sha256"]
    assert r002["gate_purpose"] == r001["gate_purpose"] == "INITIAL_START"
    assert r002["successor_reason_code"] == (
        "CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED"
    )

    supersedes = r002["supersedes"]
    assert supersedes == {
        "document_id": r001["document_id"],
        "contract_id": r001["contract_id"],
        "contract_version": r001["contract_version"],
        "path": R001_PATH.relative_to(ROOT).as_posix(),
        "file_sha256": R001_FILE_SHA256,
        "canonical_sha256": R001_CANONICAL_SHA256,
        "source_ready_event_sequence": 57,
        "source_ready_event_id": (
            "WS-GOAL-GRAPH-V2-4-GOAL-READY-NPC-SINGLE-ADMIN-RECOVERY-"
            "20260810-001"
        ),
        "source_ready_event_sha256": SEQ57_EVENT_SHA256,
    }


def test_r002_preserves_check_order_and_changes_only_registry_and_self_test() -> None:
    r001_checks = _load(R001_PATH)["ordered_checks"]
    r002_checks = _load(R002_PATH)["ordered_checks"]

    assert [item["check_id"] for item in r002_checks] == [
        item["check_id"] for item in r001_checks
    ]
    assert len(r002_checks) == len(r001_checks) == 8
    assert r002_checks[2] == {
        "check_id": "TEST_LAYER_REGISTRY_VALIDATE",
        "command": CURRENT_RUNNER_COMMAND,
    }
    assert "run_walksafe_test_layers_20260711.sh" in r001_checks[2]["command"]
    assert (
        "tests/test_run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py"
        in r002_checks[6]["command"]
    )
    assert all(
        current == predecessor
        for index, (predecessor, current) in enumerate(zip(r001_checks, r002_checks))
        if index not in {2, 6}
    )


def test_r002_supersedes_the_exact_immutable_seq57_binding() -> None:
    checkpoint = _load(CHECKPOINT_PATH)
    history = checkpoint["goal_execution"]["transition_history"]
    seq57 = next(event for event in history if event.get("sequence") == 57)
    supersedes = _load(R002_PATH)["supersedes"]

    assert seq57["event_id"] == supersedes["source_ready_event_id"]
    assert seq57["event_sha256"] == supersedes["source_ready_event_sha256"]
    assert seq57["event_sha256"] == SEQ57_EVENT_SHA256
    assert seq57["implementation_start_gate_contract_binding"] == {
        "document_id": supersedes["document_id"],
        "contract_id": supersedes["contract_id"],
        "contract_version": supersedes["contract_version"],
        "path": supersedes["path"],
        "file_sha256": supersedes["file_sha256"],
        "canonical_sha256": supersedes["canonical_sha256"],
    }


def test_r002_claims_no_execution_completion_test_or_release_credit() -> None:
    assert _load(R002_PATH)["claim_boundary"] == {
        "seq57_ready_event_modified": False,
        "binding_replacement_effective_without_rebind_event": False,
        "append_only_rebind_event_required": True,
        "goal_started": False,
        "start_gate_status": "NOT_RUN",
        "product_implementation_credit_delta": 0,
        "artifact_completion_credit_delta": 0,
        "test_credit_delta": 0,
        "formal_test_credit_delta": 0,
        "approval_credit_delta": 0,
        "actual_event_credit_delta": 0,
        "release_status": "NOT_ELIGIBLE",
        "intended_use": (
            "ADD_ONLY_START_GATE_CONTRACT_SUCCESSOR_PENDING_EVENT_SOURCED_REBIND"
        ),
    }
