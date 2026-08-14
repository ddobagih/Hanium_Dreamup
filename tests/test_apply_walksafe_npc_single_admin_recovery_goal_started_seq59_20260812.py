from __future__ import annotations

import copy
from functools import lru_cache
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
from typing import Any

import pytest

from scripts import apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812 as reanchor
from scripts import apply_walksafe_npc_single_admin_recovery_goal_started_seq59_20260812 as started
from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812 as gate


ROOT = Path(__file__).resolve().parents[1]
EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-SINGLE-ADMIN-RECOVERY-"
    "20260812-001"
)


@pytest.fixture(autouse=True)
def _isolate_seq59_from_seq58_live_dirty_membership(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """seq58 owns live Git-membership integration; these tests own seq59."""
    monkeypatch.setattr(
        reanchor,
        "_require_live_managed_membership",
        lambda _root, _paths: None,
    )


@lru_cache(maxsize=1)
def _projected_seq58() -> dict[str, Any]:
    source = json.loads((ROOT / reanchor.CHECKPOINT_RELATIVE).read_bytes())
    previous_contract, replacement_contract = reanchor._load_contract_bindings(ROOT)
    authorization, review = reanchor._load_markdown_bindings(ROOT)
    managed_paths = sorted(
        set(source["working_tree_snapshot"]["managed_changed_paths"])
        | set(reanchor.SOURCE_PATHS)
    )
    path_sha256, content_sha256 = continuation.working_snapshot_hashes(
        ROOT,
        managed_paths,
    )
    projected, _ = reanchor.project_seq58(
        source,
        managed_paths=managed_paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        previous_contract_binding=previous_contract,
        replacement_contract_binding=replacement_contract,
        authorization_binding=authorization,
        independent_review_binding=review,
    )
    return projected


def _source() -> dict[str, Any]:
    return copy.deepcopy(_projected_seq58())


def _evidence() -> started.GateEvidence:
    snapshot = {
        "gate_event_id": EVENT_ID,
        "snapshot_scope": "AFTER_EXACT_TRANSACTION_EXCLUSIONS",
        "head_commit": "a" * 40,
        "branch": "current",
        "object_format": "sha1",
        "git_status_raw_sha256": "1" * 64,
        "git_status_raw_byte_count": 1,
        "git_status_raw_record_count": 1,
        "dirty_path_count": 1,
        "path_set_sha256": "2" * 64,
        "content_set_sha256": "3" * 64,
        "index_state_sha256": "4" * 64,
        "checkpoint_base_head": "b" * 40,
        "checkpoint_managed_path_count": 1,
        "checkpoint_path_set_sha256": "5" * 64,
        "checkpoint_content_set_sha256": "6" * 64,
        "gate_repository_state_output_sha256": "7" * 64,
    }
    return started.GateEvidence(
        receipt={"repository_snapshot": snapshot},
        receipt_bytes=b"{}\n",
        receipt_binding={
            "document_id": gate._document_id(EVENT_ID),
            "path": (
                f"docs/control/execution/goal-gates/{EVENT_ID}/"
                f"{gate.RECEIPT_NAME}"
            ),
            "file_sha256": "d" * 64,
        },
        repository_payload={},
        event_occurred_at="2026-08-12T22:32:21+09:00",
    )


def test_seq59_is_one_ready_to_in_progress_transition_after_exact_seq58() -> None:
    source = _source()
    projected, event = started.project_seq59(
        ROOT,
        source,
        _evidence(),
        event_id=EVENT_ID,
    )

    assert set(event) == continuation.V24_FIRST_START_EVENT_FIELDS
    assert event["sequence"] == 59
    assert event["event_type"] == "GOAL_STARTED"
    assert event["subject_goal_id"] == gate.TARGET_GOAL_ID
    assert (event["from_status"], event["to_status"]) == (
        "READY",
        "IN_PROGRESS",
    )
    assert event["status_changes"] == {gate.TARGET_GOAL_ID: "IN_PROGRESS"}
    assert event["previous_event_sha256"] == source["goal_execution"][
        "transition_history"
    ][-1]["event_sha256"]
    assert event["previous_event_sha256"] != gate.SOURCE_READY_EVENT_SHA256
    assert event["event_sha256"] == continuation.event_sha256(event)

    state = projected["goal_execution"]
    assert len(state["transition_history"]) == 59
    assert state["transition_history"][:58] == source["goal_execution"][
        "transition_history"
    ]
    assert state["transition_history_anchor_sha256"] == event["event_sha256"]
    assert state["status_by_goal"][gate.TARGET_GOAL_ID] == "IN_PROGRESS"
    assert list(state["status_by_goal"].values()).count("IN_PROGRESS") == 1
    assert projected["current_work"]["status"] == "IN_PROGRESS"


def test_seq59_preserves_focus_frontier_and_all_zero_credit_boundaries() -> None:
    source = _source()
    projected, event = started.project_seq59(
        ROOT,
        source,
        _evidence(),
        event_id=EVENT_ID,
    )
    before = source["goal_execution"]
    after = projected["goal_execution"]

    for field in (
        "focus_goal_id",
        "focus_goal_path",
        "focus_work_item_id",
        "focus_source",
        "ready_frontier_goal_ids",
    ):
        assert after[field] == before[field]
    for key in (
        "approved_state",
        "authority_boundary",
        "verification_boundary",
        "canonical_bindings",
    ):
        assert projected[key] == source[key]
    for key in (
        "completion_evidence_by_goal",
        "archived_completion_evidence_by_goal",
        "verification_evidence_refs",
        "artifact_work_queue",
        "completion_boundary",
        "blockers_by_goal",
    ):
        assert after[key] == before[key]
    assert event["evidence_refs"] == []
    assert projected["approved_state"]["formal_test_not_run_count"] == 279
    assert projected["verification_boundary"]["actual_device_test_status"] == (
        "NOT_RUN"
    )
    assert projected["verification_boundary"]["formal_test_pass_claimed"] is False
    assert projected["verification_boundary"]["release_eligible"] is False
    assert projected["approved_state"]["release_status"] == "NOT_ELIGIBLE"
    assert projected["current_work"]["release_completion_claimed"] is False


def test_seq59_recalculates_managed_and_handoff_hashes_with_official_helper() -> None:
    projected, _ = started.project_seq59(
        ROOT,
        _source(),
        _evidence(),
        event_id=EVENT_ID,
    )
    snapshot = projected["working_tree_snapshot"]
    paths = snapshot["managed_changed_paths"]
    path_sha256, content_sha256 = continuation.working_snapshot_hashes(ROOT, paths)

    assert paths == sorted(set(paths))
    assert started.SCRIPT_RELATIVE.as_posix() in paths
    assert started.TEST_RELATIVE.as_posix() in paths
    assert snapshot["managed_changed_path_count"] == len(paths)
    assert snapshot["path_set_sha256"] == path_sha256
    assert snapshot["content_set_sha256"] == content_sha256
    handoff = projected["session_handoff"]
    assert handoff["changed_files"] == paths
    assert handoff["source_commit_or_snapshot"]["file_count"] == len(paths)
    assert handoff["source_commit_or_snapshot"]["path_set_sha256"] == path_sha256
    assert handoff["source_commit_or_snapshot"]["content_set_sha256"] == (
        content_sha256
    )


@pytest.mark.parametrize(
    ("mutator", "message"),
    (
        (
            lambda source: source["goal_execution"]["transition_history"][-2].update(
                {"event_sha256": "0" * 64}
            ),
            "57",
        ),
        (
            lambda source: source["goal_execution"]["transition_history"][-1].update(
                {"previous_event_sha256": "0" * 64}
            ),
            "seq58|lineage",
        ),
        (
            lambda source: source["goal_execution"]["transition_history"][-1][
                "contract_supersession"
            ].update({"replacement_contract_binding": {}}),
            "contract|seq58",
        ),
    ),
)
def test_exact_source_rejects_ready_reanchor_or_r002_tampering(
    mutator: Any,
    message: str,
) -> None:
    source = _source()
    mutator(source)
    with pytest.raises((started.StartApplyError, reanchor.ControlReanchorError), match=message):
        started.require_exact_source(ROOT, source)


def _repository_payload(event_id: str) -> dict[str, Any]:
    return {
        "schema_version": "1.0.0",
        "evidence_type": "GATE_REPOSITORY_STATE",
        "gate_event_id": event_id,
        "repository": {
            "head_commit": "a" * 40,
            "branch": "current",
            "object_format": "sha1",
        },
        "git_status_raw": {
            "scope": gate.SNAPSHOT_SCOPE,
            "sha256": "1" * 64,
            "byte_count": 10,
            "record_count": 2,
        },
        "dirty_snapshot": {
            "dirty_path_count": 2,
            "path_set_sha256": "2" * 64,
            "content_set_sha256": "3" * 64,
            "index_state_sha256": "4" * 64,
        },
        "checkpoint_controlled_working_snapshot": {
            "base_head": "b" * 40,
            "managed_changed_path_count": 878,
            "path_set_sha256": "5" * 64,
            "content_set_sha256": "6" * 64,
        },
        "transaction_exclusions": {
            "allowed_rule_count": 2,
            "checkpoint_exact_path": gate.CHECKPOINT_RELATIVE.as_posix(),
            "gate_event_exact_prefix": (
                gate.GATE_ROOT_RELATIVE / event_id
            ).as_posix()
            + "/",
        },
    }


def _write_private(path: Path, content: bytes) -> None:
    path.write_bytes(content)
    path.chmod(0o600)


def _build_private_gate(
    root: Path,
    source: dict[str, Any],
    source_bytes: bytes,
    *,
    receipt_ready_sha256: str,
    repository_payload: dict[str, Any] | None = None,
) -> tuple[gate.GateContext, dict[str, Any]]:
    activation = next(
        event
        for event in source["goal_execution"]["transition_history"]
        if event["event_type"] == "PACKAGE_ACTIVATED"
    )
    checks = (
        ("CONTINUATION", "python continuation.py"),
        ("REPOSITORY_STATE", "python repository.py"),
    )
    context = gate.GateContext(
        checks=checks,
        checkpoint_sha256=hashlib.sha256(source_bytes).hexdigest(),
        target_goal_sha256=gate.TARGET_GOAL_SHA256,
        source_activation_event_sha256=activation["event_sha256"],
        source_ready_event_sha256=gate.SOURCE_READY_EVENT_SHA256,
        source_ready_occurred_at=(
            __import__("datetime").datetime.fromisoformat(
                source["goal_execution"]["transition_history"][-1]["occurred_at"]
            )
        ),
        contract_binding=gate.expected_contract_binding(),
        runtime_bindings=(),
    )
    event_dir = root / gate.GATE_ROOT_RELATIVE / EVENT_ID
    event_dir.mkdir(parents=True)
    event_dir.chmod(0o700)
    if repository_payload is None:
        repository_payload = _repository_payload(EVENT_ID)
    log_contents = (
        b"WalkSafe continuation: PASS\n",
        continuation.canonical_json_bytes(repository_payload) + b"\n",
    )
    runs: list[dict[str, Any]] = []
    for index, ((check_id, command), output) in enumerate(
        zip(checks, log_contents, strict=True),
        start=1,
    ):
        relative = (
            gate.GATE_ROOT_RELATIVE
            / EVENT_ID
            / f"{index:02d}-{check_id}.log"
        )
        _write_private(root / relative, output)
        runs.append(
            {
                "check_id": check_id,
                "command": command,
                "executed_at": f"2026-08-12T22:33:00.{index:06d}+09:00",
                "exit_code": 0,
                "output_path": relative.as_posix(),
                "output_sha256": hashlib.sha256(output).hexdigest(),
            }
        )
    repository_snapshot = gate.repository_snapshot_from_payload(
        repository_payload,
        event_id=EVENT_ID,
        output_sha256=runs[-1]["output_sha256"],
    )
    receipt = {
        "schema_version": "1.1",
        "document_id": gate._document_id(EVENT_ID),
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": "INITIAL_START",
        "status": "PASS",
        "package_id": gate.PACKAGE_ID,
        "target_transition_event_id": EVENT_ID,
        "target_goal_id": gate.TARGET_GOAL_ID,
        "target_goal_content_sha256": gate.TARGET_GOAL_SHA256,
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "source_activation_event_sha256": activation["event_sha256"],
        "source_checkpoint_sha256": hashlib.sha256(source_bytes).hexdigest(),
        "source_ready_event_sha256": receipt_ready_sha256,
        "check_command_contract_version": gate.CONTRACT_VERSION,
        "check_command_contract_sha256": gate.CONTRACT_CANONICAL_SHA256,
        "implementation_start_gate_contract_binding": gate.expected_contract_binding(),
        "runtime_bindings": [],
        "execution_window": {
            "started_at": "2026-08-12T22:33:00+09:00",
            "ended_at": "2026-08-12T22:34:00+09:00",
        },
        "check_runs": runs,
        "repository_snapshot": repository_snapshot,
        "generated_at": "2026-08-12T22:34:01+09:00",
    }
    _write_private(
        event_dir / gate.RECEIPT_NAME,
        (json.dumps(receipt, ensure_ascii=False, indent=2) + "\n").encode(),
    )
    return context, repository_payload


def test_pass_receipt_separately_binds_seq57_and_raw_seq58_checkpoint(
    tmp_path: Path,
) -> None:
    source = _source()
    source_bytes = started.json_bytes(source)
    context, payload = _build_private_gate(
        tmp_path,
        source,
        source_bytes,
        receipt_ready_sha256=gate.SOURCE_READY_EVENT_SHA256,
    )
    evidence = started.validate_gate_evidence(
        tmp_path,
        source,
        source_bytes,
        context,
        event_id=EVENT_ID,
        capture_repository_state=lambda _root, _checkpoint, _event: payload,
    )

    assert context.source_ready_event_sha256 == gate.SOURCE_READY_EVENT_SHA256
    assert context.checkpoint_sha256 == hashlib.sha256(source_bytes).hexdigest()
    assert context.checkpoint_sha256 != context.source_ready_event_sha256
    assert evidence.receipt["source_ready_event_sha256"] == (
        gate.SOURCE_READY_EVENT_SHA256
    )
    assert evidence.receipt["source_checkpoint_sha256"] == context.checkpoint_sha256


def test_pass_receipt_rejects_seq58_tail_as_the_seq57_ready_anchor(
    tmp_path: Path,
) -> None:
    source = _source()
    source_bytes = started.json_bytes(source)
    seq58_sha256 = source["goal_execution"]["transition_history"][-1][
        "event_sha256"
    ]
    context, payload = _build_private_gate(
        tmp_path,
        source,
        source_bytes,
        receipt_ready_sha256=seq58_sha256,
    )
    with pytest.raises(started.StartApplyError, match="source_ready_event_sha256"):
        started.validate_gate_evidence(
            tmp_path,
            source,
            source_bytes,
            context,
            event_id=EVENT_ID,
            capture_repository_state=lambda _root, _checkpoint, _event: payload,
        )


def test_pass_receipt_rejects_a_different_raw_seq58_checkpoint_hash(
    tmp_path: Path,
) -> None:
    source = _source()
    source_bytes = started.json_bytes(source)
    context, payload = _build_private_gate(
        tmp_path,
        source,
        source_bytes,
        receipt_ready_sha256=gate.SOURCE_READY_EVENT_SHA256,
    )
    receipt_path = tmp_path / gate.GATE_ROOT_RELATIVE / EVENT_ID / gate.RECEIPT_NAME
    receipt = json.loads(receipt_path.read_bytes())
    receipt["source_checkpoint_sha256"] = "0" * 64
    _write_private(
        receipt_path,
        (json.dumps(receipt, ensure_ascii=False, indent=2) + "\n").encode(),
    )

    with pytest.raises(started.StartApplyError, match="source_checkpoint_sha256"):
        started.validate_gate_evidence(
            tmp_path,
            source,
            source_bytes,
            context,
            event_id=EVENT_ID,
            capture_repository_state=lambda _root, _checkpoint, _event: payload,
        )


def test_publication_passes_exact_source_to_cas_writer_and_preserves_0600(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    checkpoint_path = tmp_path / "checkpoint.json"
    source_bytes = b'{"source":true}\n'
    projected_bytes = b'{"projected":true}\n'
    checkpoint_path.write_bytes(source_bytes)
    checkpoint_path.chmod(0o600)
    source = {
        "working_tree_snapshot": {
            "managed_changed_paths": [],
            "path_set_sha256": "p",
            "content_set_sha256": "c",
        }
    }
    projected = {
        "working_tree_snapshot": {
            "managed_changed_paths": [],
            "path_set_sha256": "p",
            "content_set_sha256": "c",
        }
    }
    evidence = _evidence()
    prepared = started.PreparedProjection(
        root=tmp_path,
        checkpoint_path=checkpoint_path,
        event_id=EVENT_ID,
        source_checkpoint_bytes=source_bytes,
        source_checkpoint=source,
        projected_checkpoint=projected,
        projected_checkpoint_bytes=projected_bytes,
        event={},
        context=object(),  # type: ignore[arg-type]
        evidence=evidence,
    )
    monkeypatch.setattr(started, "require_exact_source", lambda _root, _source: None)
    monkeypatch.setattr(started, "_require_static_context", lambda _prepared: None)
    monkeypatch.setattr(
        started,
        "validate_gate_evidence",
        lambda *_args, **_kwargs: evidence,
    )
    monkeypatch.setattr(
        started.contract,
        "working_snapshot_hashes",
        lambda _root, _paths: ("p", "c"),
    )
    calls: list[bytes] = []

    def cas_writer(
        path: Path,
        content: bytes,
        *,
        expected_source: bytes,
        commit_guard: Any,
    ) -> None:
        assert path.read_bytes() == expected_source == source_bytes
        commit_guard()
        calls.append(expected_source)
        path.write_bytes(content)
        path.chmod(0o600)

    started.write_projection(prepared, atomic_writer=cas_writer)

    metadata = checkpoint_path.lstat()
    assert calls == [source_bytes]
    assert checkpoint_path.read_bytes() == projected_bytes
    assert stat.S_IMODE(metadata.st_mode) == 0o600
    assert metadata.st_nlink == 1
    assert metadata.st_uid == os.geteuid()
