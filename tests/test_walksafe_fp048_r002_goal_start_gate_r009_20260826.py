from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
from typing import Any

import pytest

from scripts import run_walksafe_fp048_r002_goal_start_gate_r008_20260826 as r008
from scripts import run_walksafe_fp048_r002_goal_start_gate_r009_20260826 as gate


ROOT = Path(__file__).resolve().parents[1]


def _json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def _contract() -> tuple[bytes, dict[str, Any]]:
    raw = (ROOT / gate.CONTRACT_RELATIVE).read_bytes()
    return raw, json.loads(raw)


def _sealed_event(sequence: int, previous: str) -> dict[str, Any]:
    event = {
        "sequence": sequence,
        "event_id": f"SYNTHETIC-{sequence:03d}",
        "event_type": "SYNTHETIC",
        "occurred_at": "2026-08-26T16:00:00+09:00",
        "previous_event_sha256": previous,
    }
    event["event_sha256"] = gate.event_sha256(event)
    return event


def _fixture(
    tmp_path: Path,
) -> tuple[Path, dict[str, Any], SimpleNamespace, dict[str, Any], dict[str, Any]]:
    gate.base._sync_runtime_paths()
    prior = gate.base._correction()
    ready_event_id = gate.base.READY_EVENT_ID
    ready_frontier = gate.base.READY_FRONTIER
    goal_path = Path(prior.GOAL_PATH)
    contract_raw, _ = _contract()
    for relative, raw in (
        (goal_path, (ROOT / goal_path).read_bytes()),
        (gate.MANIFEST_RELATIVE, (ROOT / gate.MANIFEST_RELATIVE).read_bytes()),
        (gate.CONTRACT_RELATIVE, contract_raw),
    ):
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)

    history: list[dict[str, Any]] = []
    previous = "0" * 64
    for sequence in range(1, gate.SOURCE_READY_SEQUENCE):
        event = _sealed_event(sequence, previous)
        history.append(event)
        previous = event["event_sha256"]
    ready = {
        "sequence": gate.SOURCE_READY_SEQUENCE,
        "event_id": ready_event_id,
        "event_type": "GOAL_READY",
        "occurred_at": "2026-08-26T16:01:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "previous_event_sha256": previous,
    }
    ready["event_sha256"] = gate.event_sha256(ready)
    history.append(ready)
    previous = ready["event_sha256"]
    for sequence in range(90, r008.SOURCE_SEQUENCE):
        event = _sealed_event(sequence, previous)
        history.append(event)
        previous = event["event_sha256"]

    predecessor = {
        "sequence": r008.SOURCE_SEQUENCE,
        "event_id": r008.CORRECTION_EVENT_ID,
        "event_type": r008.CORRECTION_EVENT_TYPE,
        "occurred_at": "2026-08-26T16:02:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "source_checkpoint_binding": {
            "preflight_attempt_004": (
                gate.expected_historical_preflight_attempt_004_binding()
            ),
            "r006_preflight_attempt_004": (
                gate.expected_r006_preflight_attempt_004_binding()
            ),
            "r007_preflight_attempt_004": (
                gate.expected_r007_preflight_attempt_004_binding()
            ),
        },
        "previous_event_sha256": previous,
    }
    predecessor["event_sha256"] = gate.event_sha256(predecessor)
    history.append(predecessor)
    previous = predecessor["event_sha256"]

    contract_binding = gate.expected_r009_binding()
    correction_event = {
        "sequence": gate.SOURCE_SEQUENCE,
        "event_id": gate.CORRECTION_EVENT_ID,
        "event_type": gate.CORRECTION_EVENT_TYPE,
        "occurred_at": "2026-08-26T16:03:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "contract_supersession": {
            "replacement_contract_binding": copy.deepcopy(contract_binding),
        },
        "source_checkpoint_binding": {
            "r008_preflight_attempt_004": (
                gate.expected_preflight_attempt_004_binding()
            ),
        },
        "previous_event_sha256": previous,
    }
    correction_event["event_sha256"] = gate.event_sha256(correction_event)
    history.append(correction_event)
    checkpoint = {
        "schema_version": "1.25.0",
        "goal_execution": {
            "transition_history": history,
            "transition_history_anchor_sha256": correction_event["event_sha256"],
            "status_by_goal": {
                gate.TARGET_GOAL_ID: "READY",
                gate.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
            },
            "focus_goal_id": gate.TARGET_GOAL_ID,
        },
    }
    checkpoint_path = tmp_path / gate.CHECKPOINT_RELATIVE
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    checkpoint_path.write_bytes(_json_bytes(checkpoint))
    checkpoint_path.chmod(0o600)

    def require_snapshot_hygiene_corrected_checkpoint(
        root: Path, value: dict[str, Any], **_kwargs: Any
    ) -> None:
        assert root.resolve() == tmp_path.resolve()
        assert value["goal_execution"]["transition_history"][-1]["event_id"] == (
            gate.CORRECTION_EVENT_ID
        )

    fake = SimpleNamespace(
        GOAL_ID=gate.TARGET_GOAL_ID,
        GOAL_PATH=goal_path,
        GOAL_SHA256=prior.GOAL_SHA256,
        WORK_ITEM_ID=gate.WORK_ITEM_ID,
        PARENT_GOAL_ID=gate.PARENT_GOAL_ID,
        PREDECESSOR_GOAL_ID=gate.PREDECESSOR_GOAL_ID,
        READY_EVENT_ID=ready_event_id,
        READY_FRONTIER=ready_frontier,
        MANIFEST_SHA256=gate.MANIFEST_SHA256,
        SOURCE_SEQUENCE=r008.SOURCE_SEQUENCE,
        CORRECTION_SEQUENCE=gate.SOURCE_SEQUENCE,
        CORRECTION_EVENT_ID=gate.CORRECTION_EVENT_ID,
        STARTED_SEQUENCE=gate.EVENT_SEQUENCE,
        STARTED_EVENT_ID=gate.STARTED_EVENT_ID,
        r009_contract_binding=lambda _root: copy.deepcopy(contract_binding),
        require_snapshot_hygiene_corrected_checkpoint=(
            require_snapshot_hygiene_corrected_checkpoint
        ),
        canonical_seq97_checkpoint_bytes=lambda *_args, **_kwargs: b"seq97",
        reconstructed_seq96_checkpoint_bytes=lambda *_args, **_kwargs: b"seq96",
    )
    return tmp_path, checkpoint, fake, ready, correction_event


def _retained_contents(root: Path, checkpoint_raw: bytes) -> dict[Path, bytes]:
    gate._sync_runtime_paths()
    retained = {
        path: b"retained runtime\n" for path in gate._impl.SOURCE_GUARD_RELATIVES
    }
    retained[gate.CHECKPOINT_RELATIVE] = checkpoint_raw
    retained[gate.CONTRACT_RELATIVE] = (root / gate.CONTRACT_RELATIVE).read_bytes()
    retained[gate.MANIFEST_RELATIVE] = (root / gate.MANIFEST_RELATIVE).read_bytes()
    return retained


def test_r009_contract_is_canonical_exact_successor_of_r008() -> None:
    raw, contract = _contract()
    predecessor_raw = (ROOT / r008.CONTRACT_RELATIVE).read_bytes()
    predecessor = json.loads(predecessor_raw)
    assert raw == _json_bytes(contract)
    assert contract["document_id"] == gate.CONTRACT_DOCUMENT_ID
    assert contract["contract_id"] == gate.CONTRACT_ID
    assert contract["contract_version"] == gate.CONTRACT_VERSION
    assert contract["successor_reason_code"] == gate.PREFLIGHT_FAILURE_REASON_CODE
    assert contract["supersedes"] == {
        "byte_length": len(predecessor_raw),
        "canonical_sha256": r008.canonical_sha256(predecessor),
        "contract_id": predecessor["contract_id"],
        "contract_version": predecessor["contract_version"],
        "document_id": predecessor["document_id"],
        "file_sha256": hashlib.sha256(predecessor_raw).hexdigest(),
        "path": r008.CONTRACT_RELATIVE.as_posix(),
        "source_correction_event_id": gate.CORRECTION_EVENT_ID,
        "source_correction_event_sequence": gate.SOURCE_SEQUENCE,
    }
    assert len(raw) == gate.CONTRACT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == gate.CONTRACT_FILE_SHA256
    assert gate.canonical_sha256(contract) == gate.CONTRACT_CANONICAL_SHA256


def test_r009_contract_has_exact_five_post_seq96_checks() -> None:
    _raw, contract = _contract()
    assert tuple(row["check_id"] for row in contract["ordered_checks"]) == (
        gate.EXPECTED_CHECK_IDS
    )
    assert tuple(row["command"] for row in contract["ordered_checks"]) == tuple(
        gate.CONTRACT_COMMANDS[check_id] for check_id in gate.EXPECTED_CHECK_IDS
    )
    command = gate.CONTRACT_COMMANDS["ROOT_FP048_R002_CONTROL_REGRESSION"]
    required = (
        gate.POST_SEQ96_REGRESSION_TEST_RELATIVE,
        gate.CORRECTION_TEST_RELATIVE,
        gate.RUNNER_TEST_RELATIVE,
        gate.START_APPLY_TEST_RELATIVE,
    )
    assert all(path.as_posix() in command for path in required)
    assert command.count("tests/") == len(required)
    assert r008.CORRECTION_TEST_RELATIVE.as_posix() not in command


def test_r009_reuses_only_unused_004_for_seq98() -> None:
    assert gate.SOURCE_SEQUENCE == 97
    assert gate.EVENT_SEQUENCE == 98
    assert gate.STARTED_EVENT_ID == r008.STARTED_EVENT_ID
    assert gate._document_id(gate.STARTED_EVENT_ID).endswith("20260826-004")
    with pytest.raises(gate.GateError, match="unused post-seq96 start ID"):
        gate._document_id(gate.RESERVED_POST_NAMESPACE_FAILURE_EVENT_ID)


def test_r008_failure_is_exact_nonauthority_and_004_remains_reusable() -> None:
    assert gate.expected_preflight_attempt_004_binding() == {
        "event_id": gate.STARTED_EVENT_ID,
        "contract_id": r008.CONTRACT_ID,
        "contract_version": r008.CONTRACT_VERSION,
        "directory": (gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID).as_posix(),
        "receipt_path": (
            gate.GATE_ROOT_RELATIVE
            / gate.STARTED_EVENT_ID
            / gate.RECEIPT_NAME
        ).as_posix(),
        "status": "PREVIEW_FAILED_BEFORE_NAMESPACE",
        "authority_status": "NONAUTHORITY",
        "event_identity_status": "REUSABLE_UNCONSUMED",
        "namespace_present": False,
        "receipt_present": False,
        "failed_check_id": "ROOT_FP048_R002_CONTROL_REGRESSION",
        "exit_code": 2,
        "error": "retained source ancestor identity changed: docs/control",
        "reason_code": "R008_ROOT_REGRESSION_MUTATED_RETAINED_SOURCE_ANCESTOR",
    }


def test_stage_parser_ignores_four_exact_nonauthority_receipts_only() -> None:
    evidence = (
        gate.GATE_ROOT_RELATIVE
        / "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-003"
        / gate.RECEIPT_NAME
    )
    checkpoint = {
        "historical": gate.expected_historical_preflight_attempt_004_binding(),
        "r006": gate.expected_r006_preflight_attempt_004_binding(),
        "r007": gate.expected_r007_preflight_attempt_004_binding(),
        "r008": gate.expected_preflight_attempt_004_binding(),
        "sealed_evidence": {"path": evidence.as_posix()},
    }
    directories, paths = gate._checkpoint_bound_gate_event_directories(
        _json_bytes(checkpoint)
    )
    assert directories == (evidence.parent,)
    assert paths == (evidence,)


@pytest.mark.parametrize(
    ("key", "value"),
    (
        ("authority_status", "AUTHORITY"),
        ("receipt_present", True),
        ("namespace_present", True),
        ("status", "PASS"),
        ("reason_code", "FORGED"),
        ("exit_code", 2.0),
        ("failed_check_id", "CONTINUATION"),
        ("error", "forged"),
    ),
)
def test_stage_parser_rejects_forged_r008_metadata(key: str, value: object) -> None:
    metadata = gate.expected_preflight_attempt_004_binding()
    metadata[key] = value
    with pytest.raises(gate.GateError, match="metadata differs"):
        gate._checkpoint_bound_gate_event_directories(_json_bytes(metadata))


def test_r008_parser_failure_is_repaired_without_losing_evidence() -> None:
    sealed = (
        gate.GATE_ROOT_RELATIVE
        / "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-003"
        / gate.RECEIPT_NAME
    )
    raw = _json_bytes(
        {
            "historical": gate.expected_historical_preflight_attempt_004_binding(),
            "r006": gate.expected_r006_preflight_attempt_004_binding(),
            "r007": gate.expected_r007_preflight_attempt_004_binding(),
            "r008": gate.expected_preflight_attempt_004_binding(),
            "sealed_evidence": {"path": sealed.as_posix()},
        }
    )
    with pytest.raises(r008.GateError, match="metadata differs"):
        r008._impl._checkpoint_bound_gate_event_directories(raw)
    directories, paths = gate._checkpoint_bound_gate_event_directories(raw)
    assert sealed.parent in directories
    assert sealed in paths


def test_seq97_authority_and_context_bind_r009_and_four_preflight_records(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, ready, event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    authority = gate.bind_contract_corrected_source(root, source)
    assert authority.ready_event_sha256 == ready["event_sha256"]
    assert authority.correction_event_sha256 == event["event_sha256"]
    assert authority.contract_binding == gate.expected_r009_binding()
    assert authority.preflight_attempt_binding == (
        gate.expected_preflight_attempt_004_binding()
    )
    context = gate.load_gate_context(root, _retained_contents(root, _json_bytes(source)))
    assert tuple(check_id for check_id, _command in context.checks) == (
        gate.EXPECTED_CHECK_IDS
    )
    assert context.source_activation_event_sha256 == event["event_sha256"]
    assert context.contract_binding == gate.expected_r009_binding()


def test_seq97_authority_rejects_r008_metadata_drift(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    event = source["goal_execution"]["transition_history"][-1]
    event["source_checkpoint_binding"]["r008_preflight_attempt_004"][
        "receipt_present"
    ] = True
    event["event_sha256"] = gate.event_sha256(event)
    source["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    with pytest.raises(gate.GateError, match="historical preflight binding differs"):
        gate.bind_contract_corrected_source(root, source)


def test_pre_seq97_source_fails_closed_without_004_namespace(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    history = source["goal_execution"]["transition_history"]
    history.pop()
    source["goal_execution"]["transition_history_anchor_sha256"] = history[-1][
        "event_sha256"
    ]
    fake.require_snapshot_hygiene_corrected_checkpoint = lambda *_args, **_kwargs: None
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    directory = root / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
    assert not directory.exists()
    with pytest.raises(gate.GateError, match="not exact seq97"):
        gate.bind_contract_corrected_source(root, source)
    assert not directory.exists()


def test_seq96_candidate_tempfile_uses_snapshot_root_not_docs_control(
    tmp_path: Path,
) -> None:
    source = (
        ROOT
        / "tests/test_apply_walksafe_fp048_r002_start_gate_contract_"
        "correction_seq96_20260826.py"
    ).read_text(encoding="utf-8")
    assert "dir=ROOT," in source
    assert "dir=ROOT / correction.CHECKPOINT_REL.parent," not in source

    control = tmp_path / "docs/control"
    control.mkdir(parents=True)
    (control / "checkpoint.json").write_bytes(b"{}\n")
    old_location = gate._impl._RetainedDirectoryTree.capture(
        tmp_path, (Path("docs/control/checkpoint.json"),)
    )
    descriptor, name = tempfile.mkstemp(dir=control)
    os.close(descriptor)
    Path(name).unlink()
    with pytest.raises(
        gate.GateError,
        match="retained source ancestor identity changed: docs/control",
    ):
        old_location.verify()
    old_location.close()

    retained = gate._impl._RetainedDirectoryTree.capture(
        tmp_path, (Path("docs/control/checkpoint.json"),)
    )
    descriptor, name = tempfile.mkstemp(dir=tmp_path)
    os.close(descriptor)
    Path(name).unlink()
    retained.verify()
    retained.close()


def test_fresh_subprocess_repeated_private_install_does_not_recurse(
    tmp_path: Path,
) -> None:
    program = r'''
import sys
from pathlib import Path
from types import SimpleNamespace
from scripts import run_walksafe_fp048_r002_goal_start_gate_r009_20260826 as gate

gate.base._sync_runtime_paths()
prior = gate.base._correction()
fake = SimpleNamespace(
    GOAL_ID=gate.TARGET_GOAL_ID,
    GOAL_PATH=prior.GOAL_PATH,
    GOAL_SHA256=prior.GOAL_SHA256,
    WORK_ITEM_ID=gate.WORK_ITEM_ID,
    PARENT_GOAL_ID=gate.PARENT_GOAL_ID,
    PREDECESSOR_GOAL_ID=gate.PREDECESSOR_GOAL_ID,
    READY_EVENT_ID=gate.base.READY_EVENT_ID,
    READY_FRONTIER=gate.base.READY_FRONTIER,
    MANIFEST_SHA256=gate.MANIFEST_SHA256,
    SOURCE_SEQUENCE=gate.base.SOURCE_SEQUENCE,
    CORRECTION_SEQUENCE=gate.SOURCE_SEQUENCE,
    CORRECTION_EVENT_ID=gate.CORRECTION_EVENT_ID,
    STARTED_SEQUENCE=gate.EVENT_SEQUENCE,
    STARTED_EVENT_ID=gate.STARTED_EVENT_ID,
    r009_contract_binding=lambda *args, **kwargs: None,
    require_snapshot_hygiene_corrected_checkpoint=lambda *args, **kwargs: None,
    canonical_seq97_checkpoint_bytes=lambda *args, **kwargs: b"",
    reconstructed_seq96_checkpoint_bytes=lambda *args, **kwargs: b"",
)
gate._correction = lambda: fake
private = (
    gate.base._sync_runtime_paths,
    gate.base.base._sync_runtime_paths,
    gate.base.base.base._sync_runtime_paths,
    gate.base.base.base.base._sync_runtime_paths,
    gate.base.base.base.base.base._sync_runtime_paths,
    gate.base.base.base.base.base.base._sync_runtime_paths,
    gate.base.base.base.base.base.base.base._sync_runtime_paths,
)
gate._install_base_adapter()
gate._install_base_adapter()
assert gate.base._sync_runtime_paths is private[0]
assert gate.base.base._sync_runtime_paths is private[1]
assert gate.base.base.base._sync_runtime_paths is private[2]
assert gate.base.base.base.base._sync_runtime_paths is private[3]
assert gate.base.base.base.base.base._sync_runtime_paths is private[4]
assert gate.base.base.base.base.base.base._sync_runtime_paths is private[5]
assert gate.base.base.base.base.base.base.base._sync_runtime_paths is gate._sync_runtime_paths
assert private[6] is gate._PRIVATE_R002_SYNC_RUNTIME_PATHS
status = gate.main((
    "--preflight",
    "--root",
    sys.argv[1],
    "--event-id",
    gate.STARTED_EVENT_ID,
))
assert status == 2, status
assert not (
    Path(sys.argv[1]) / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
).exists()
'''
    completed = subprocess.run(
        [sys.executable, "-B", "-c", program, os.fspath(tmp_path)],
        cwd=ROOT,
        env={
            **os.environ,
            "PYTHONDONTWRITEBYTECODE": "1",
            "PYTHONPATH": ".",
        },
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )
    output = completed.stdout + completed.stderr
    assert completed.returncode == 0, output
    assert "RecursionError" not in output
    assert "Traceback" not in output
