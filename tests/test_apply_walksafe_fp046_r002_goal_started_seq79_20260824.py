from __future__ import annotations

import hashlib
import json
from datetime import datetime, timedelta
from pathlib import Path

import pytest

from scripts import (
    apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824
    as correction,
)
from scripts import apply_walksafe_fp046_r002_goal_started_seq79_20260824 as subject
from scripts import (
    build_walksafe_fp046_r002_seq78_79_recovery_review_20260824 as review,
)


ROOT = Path(__file__).resolve().parents[1]


def _seq84_source() -> dict:
    live = json.loads((ROOT / correction.CHECKPOINT_RELATIVE).read_bytes())
    source = json.loads(
        correction.reconstructed_seq83_checkpoint_bytes(
            ROOT,
            live["goal_execution"]["transition_history"][82],
        )
    )
    assert len(source["goal_execution"]["transition_history"]) == 83
    paths = correction.exact_seq84_managed_paths(source)
    assert len(paths) == 1020
    projected, _ = correction.project_seq84(
        source,
        managed_paths=paths,
        path_set_sha256=hashlib.sha256(
            ("\n".join(paths) + "\n").encode()
        ).hexdigest(),
        content_set_sha256="a" * 64,
        authorization_binding={
            "path": correction.AUTHORIZATION_RELATIVE.as_posix(),
            "sha256": correction.AUTHORIZATION_SHA256,
            "byte_length": correction.AUTHORIZATION_BYTE_COUNT,
        },
        transition_review_binding={},
        event_occurred_at=(
            datetime.fromisoformat(
                source["goal_execution"]["transition_history"][-1][
                    "occurred_at"
                ]
            )
            + timedelta(seconds=1)
        ).isoformat(),
        runner_binding={},
        failed_gate_binding=review.passed_gate_attempt_004_binding(ROOT),
    )
    return projected


def _evidence() -> subject.GateEvidence:
    receipt_relative = (
        subject.gate.GATE_ROOT_RELATIVE
        / subject.EVENT_ID
        / subject.gate.RECEIPT_NAME
    )
    return subject.GateEvidence(
        receipt={"repository_snapshot": {"sealed": True}},
        receipt_bytes=b"{}\n",
        receipt_binding={
            "document_id": subject.gate._recovery_document_id(subject.EVENT_ID),
            "path": receipt_relative.as_posix(),
            "file_sha256": "9" * 64,
        },
        repository_payload={"sealed": True},
        event_occurred_at="2026-08-25T00:19:05+09:00",
    )


def _final_hashes(source: dict) -> dict[Path, str]:
    paths = {
        Path(path)
        for path in source["working_tree_snapshot"]["managed_changed_paths"]
    }
    paths.update({subject.SCRIPT_RELATIVE, subject.TEST_RELATIVE})
    return {path: "a" * 64 for path in paths}


def test_seq85_identity_is_exact_and_does_not_reuse_burned_id() -> None:
    assert subject.EVENT_SEQUENCE == 85
    assert subject.EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-005"
    )
    assert subject.EVENT_ID == subject.gate.RECOVERY_STARTED_EVENT_ID
    assert subject.EVENT_ID not in subject.gate.BURNED_EVENT_IDS
    assert subject.correction.CONTROL_CORRECTION_SEQUENCE == 84


def test_seq85_projection_is_one_ready_to_in_progress_zero_credit_change(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _seq84_source()
    monkeypatch.setattr(
        subject,
        "require_exact_source",
        lambda *_args, **_kwargs: None,
    )
    projected, event = subject.project_seq85(
        ROOT,
        source,
        _evidence(),
        event_id=subject.EVENT_ID,
        final_sha256_by_path=_final_hashes(source),
    )
    assert set(event) == subject.EVENT_FIELDS
    assert event["sequence"] == 85
    assert event["event_id"] == subject.EVENT_ID
    assert event["from_status"] == "READY"
    assert event["to_status"] == "IN_PROGRESS"
    assert event["status_changes"] == {
        subject.gate.TARGET_GOAL_ID: "IN_PROGRESS"
    }
    assert event["previous_event_sha256"] == source["goal_execution"][
        "transition_history"
    ][-1]["event_sha256"]
    assert event["implementation_start_gate_binding"] == _evidence().receipt_binding
    statuses = projected["goal_execution"]["status_by_goal"]
    assert statuses[subject.gate.TARGET_GOAL_ID] == "IN_PROGRESS"
    assert list(statuses.values()).count("IN_PROGRESS") == 1
    assert projected["approved_state"] == source["approved_state"]
    assert projected["verification_boundary"] == source["verification_boundary"]
    assert projected["current_work"]["status"] == "IN_PROGRESS"
    assert projected["current_work"]["release_completion_claimed"] is False
    assert projected["goal_execution"]["transition_history"][:84] == source[
        "goal_execution"
    ]["transition_history"]


@pytest.mark.parametrize(
    "event_id",
    (
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-001",
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-002",
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-003",
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-004",
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-006",
        "wrong",
    ),
)
def test_seq85_projection_rejects_burned_or_non_exact_id(
    event_id: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _seq84_source()
    monkeypatch.setattr(
        subject,
        "require_exact_source",
        lambda *_args, **_kwargs: None,
    )
    with pytest.raises(subject.StartApplyError, match="seq85 event ID differs"):
        subject.project_seq85(
            ROOT,
            source,
            _evidence(),
            event_id=event_id,
            final_sha256_by_path=_final_hashes(source),
        )


def test_seq85_gate_evidence_requires_exact_private_pass_receipt(
    tmp_path: Path,
) -> None:
    source = _seq84_source()
    event_dir = tmp_path / subject.gate.GATE_ROOT_RELATIVE / subject.EVENT_ID
    event_dir.mkdir(parents=True)
    event_dir.chmod(0o700)
    checks = tuple(
        (check_id, f"command-{index}")
        for index, check_id in enumerate(subject.gate.EXPECTED_CHECK_IDS, start=1)
    )
    for index, (check_id, _) in enumerate(checks, start=1):
        output = event_dir / f"{index:02d}-{check_id}.log"
        output.write_bytes(b"not-an-exact-pass-log\n")
        output.chmod(0o600)
    receipt = event_dir / subject.gate.RECEIPT_NAME
    receipt.write_bytes(b"{}\n")
    receipt.chmod(0o600)
    context = subject.gate.GateContext(
        checks=checks,
        checkpoint_sha256=subject.sha256_bytes(b"source\n"),
        target_goal_sha256=subject.gate.TARGET_GOAL_SHA256,
        source_activation_event_sha256=source["goal_execution"][
            "transition_history"
        ][-1]["event_sha256"],
        source_ready_event_sha256=subject.gate.SOURCE_READY_EVENT_SHA256,
        source_ready_occurred_at=subject.datetime.fromisoformat(
            "2026-08-24T00:00:01+09:00"
        ),
        contract_binding=subject.gate.expected_contract_binding(),
        runtime_bindings=(),
    )
    with pytest.raises(subject.StartApplyError, match="PASS receipt field set differs"):
        subject.validate_gate_evidence(
            tmp_path,
            source,
            b"source\n",
            context,
            event_id=subject.EVENT_ID,
            capture_repository_state=lambda *_args: {},
        )


def test_seq85_accepts_only_an_exact_five_check_pass_receipt(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _seq84_source()
    source_bytes = b"exact seq84 source\n"
    control_time = datetime.fromisoformat(
        source["goal_execution"]["transition_history"][-1]["occurred_at"]
    )
    started_at = control_time + timedelta(seconds=1)
    ended_at = control_time + timedelta(seconds=2)
    generated_at = control_time + timedelta(seconds=3)
    event_occurred_at = control_time + timedelta(seconds=4)
    checks = tuple(
        (check_id, f"command-{index}")
        for index, check_id in enumerate(subject.gate.EXPECTED_CHECK_IDS, start=1)
    )
    event_dir_relative = subject.gate.GATE_ROOT_RELATIVE / subject.EVENT_ID
    event_dir = tmp_path / event_dir_relative
    event_dir.mkdir(parents=True)
    event_dir.chmod(0o700)
    repository_payload = {"repository": "sealed"}
    correction_after = source["goal_execution"]["transition_history"][-1][
        "repository_context_reanchor"
    ]["after"]
    repository_snapshot = {
        "checkpoint_managed_path_count": correction_after[
            "managed_changed_path_count"
        ],
        "checkpoint_path_set_sha256": correction_after["path_set_sha256"],
        "checkpoint_content_set_sha256": correction_after["content_set_sha256"],
    }
    runs = []
    for index, (check_id, command) in enumerate(checks, start=1):
        relative = event_dir_relative / f"{index:02d}-{check_id}.log"
        output = (
            subject.contract.canonical_json_bytes(repository_payload) + b"\n"
            if check_id == "REPOSITORY_STATE"
            else f"{check_id}: PASS\n".encode()
        )
        path = tmp_path / relative
        path.write_bytes(output)
        path.chmod(0o600)
        runs.append(
            {
                "check_id": check_id,
                "command": command,
                "executed_at": (
                    started_at + timedelta(microseconds=index)
                ).isoformat(),
                "exit_code": 0,
                "output_path": relative.as_posix(),
                "output_sha256": subject.sha256_bytes(output),
            }
        )
    receipt = {
        "schema_version": "1.1",
        "document_id": subject.gate._recovery_document_id(subject.EVENT_ID),
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": "INITIAL_START",
        "status": "PASS",
        "package_id": subject.gate.PACKAGE_ID,
        "target_transition_event_id": subject.EVENT_ID,
        "target_goal_id": subject.gate.TARGET_GOAL_ID,
        "target_goal_content_sha256": subject.gate.TARGET_GOAL_SHA256,
        "static_plan_manifest_sha256": subject.gate.MANIFEST_SHA256,
        "source_activation_event_sha256": source["goal_execution"][
            "transition_history"
        ][-1]["event_sha256"],
        "source_checkpoint_sha256": subject.sha256_bytes(source_bytes),
        "source_ready_event_sha256": subject.gate.SOURCE_READY_EVENT_SHA256,
        "check_command_contract_version": subject.gate.CONTRACT_VERSION,
        "check_command_contract_sha256": subject.gate.CONTRACT_CANONICAL_SHA256,
        "implementation_start_gate_contract_binding": (
            subject.gate.expected_contract_binding()
        ),
        "runtime_bindings": [],
        "execution_window": {
            "started_at": started_at.isoformat(),
            "ended_at": ended_at.isoformat(),
        },
        "check_runs": runs,
        "repository_snapshot": repository_snapshot,
        "generated_at": generated_at.isoformat(),
    }
    assert set(receipt) == subject.gate.RECEIPT_FIELDS
    receipt_bytes = subject.json_bytes(receipt)
    receipt_path = event_dir / subject.gate.RECEIPT_NAME
    receipt_path.write_bytes(receipt_bytes)
    receipt_path.chmod(0o600)
    context = subject.gate.GateContext(
        checks=checks,
        checkpoint_sha256=subject.sha256_bytes(source_bytes),
        target_goal_sha256=subject.gate.TARGET_GOAL_SHA256,
        source_activation_event_sha256=receipt[
            "source_activation_event_sha256"
        ],
        source_ready_event_sha256=subject.gate.SOURCE_READY_EVENT_SHA256,
        source_ready_occurred_at=subject.datetime.fromisoformat(
            "2026-08-24T00:00:01+09:00"
        ),
        contract_binding=subject.gate.expected_contract_binding(),
        runtime_bindings=(),
    )
    monkeypatch.setattr(
        subject.gate,
        "_load_gate_contract",
        lambda *_args, **_kwargs: (checks, {}),
    )
    monkeypatch.setattr(
        subject.gate,
        "repository_snapshot_from_payload",
        lambda *_args, **_kwargs: repository_snapshot,
    )
    evidence = subject.validate_gate_evidence(
        tmp_path,
        source,
        source_bytes,
        context,
        event_id=subject.EVENT_ID,
        capture_repository_state=lambda *_args: repository_payload,
    )
    assert evidence.receipt == receipt
    assert evidence.receipt_binding == {
        "document_id": receipt["document_id"],
        "path": (event_dir_relative / subject.gate.RECEIPT_NAME).as_posix(),
        "file_sha256": subject.sha256_bytes(receipt_bytes),
    }
    assert evidence.event_occurred_at == event_occurred_at.isoformat()
    monkeypatch.setattr(
        subject,
        "require_exact_source",
        lambda *_args, **_kwargs: None,
    )
    projected, _ = subject.project_seq85(
        ROOT,
        source,
        evidence,
        event_id=subject.EVENT_ID,
        final_sha256_by_path=_final_hashes(source),
    )
    monkeypatch.setattr(
        subject.correction,
        "validate_history_suffix",
        lambda *_args, **_kwargs: source["goal_execution"][
            "transition_history"
        ][83],
    )
    monkeypatch.setattr(
        subject.reanchor,
        "reconstructed_seq77_checkpoint_bytes",
        lambda *_args, **_kwargs: source_bytes,
    )
    assert subject.validate_history_suffix(tmp_path, projected) == []


def test_later_suffix_validator_uses_correction_prefix_not_ready_only_check(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _seq84_source()
    calls: list[tuple[int, bool]] = []

    def prefix_validator(_root: Path, checkpoint: dict, **kwargs: object) -> dict:
        calls.append(
            (
                len(checkpoint["goal_execution"]["transition_history"]),
                kwargs["require_live_snapshot"] is True,
            )
        )
        return checkpoint["goal_execution"]["transition_history"][83]

    monkeypatch.setattr(subject.correction, "validate_history_suffix", prefix_validator)
    monkeypatch.setattr(
        subject.correction,
        "require_control_corrected_checkpoint",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("READY-only correction validator must not run")
        ),
    )
    assert subject.reanchor.validate_history_suffix(ROOT, source) == []
    assert calls == [(84, False)]


def test_main_reports_postcommit_uncertainty_without_retry(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = type(
        "Prepared",
        (),
        {"event": {"event_sha256": "a" * 64, "occurred_at": "2026-08-24T00:00:03+09:00"}},
    )()
    writes = []
    monkeypatch.setattr(subject, "prepare_projection", lambda *_args, **_kwargs: prepared)

    def fail_once(value: object) -> None:
        writes.append(value)
        raise subject.atomic.CompletionPostCommitError("publication uncertain")

    monkeypatch.setattr(subject, "write_projection", fail_once)
    diagnostics = []
    monkeypatch.setattr(subject, "_write_postcommit_diagnostic", diagnostics.append)
    assert subject.main(
        ["--root", str(ROOT), "--event-id", subject.EVENT_ID, "--write"]
    ) == 2
    assert writes == [prepared]
    assert diagnostics == [
        "FP046-R002 GOAL_STARTED seq85: POSTCOMMIT-UNCERTAIN: publication uncertain"
    ]
