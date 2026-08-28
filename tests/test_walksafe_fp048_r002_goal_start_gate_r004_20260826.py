from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
from types import SimpleNamespace
from typing import Any

import pytest

from scripts import (
    apply_walksafe_fp048_r002_start_gate_contract_correction_seq92_20260826
    as contract_correction,
)
from scripts import run_walksafe_fp048_r002_goal_start_gate_r003_20260826 as r003
from scripts import run_walksafe_fp048_r002_goal_start_gate_r004_20260826 as gate


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
        "occurred_at": "2026-08-26T06:00:00+09:00",
        "previous_event_sha256": previous,
    }
    event["event_sha256"] = gate.event_sha256(event)
    return event


def _fixture(
    tmp_path: Path,
) -> tuple[Path, dict[str, Any], SimpleNamespace, dict[str, Any], dict[str, Any]]:
    prior = r003._correction()
    goal_raw = (ROOT / prior.GOAL_PATH).read_bytes()
    manifest_raw = (ROOT / gate.MANIFEST_RELATIVE).read_bytes()
    contract_raw, contract = _contract()
    for relative, raw in (
        (prior.GOAL_PATH, goal_raw),
        (gate.MANIFEST_RELATIVE, manifest_raw),
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
        "event_id": prior.READY_EVENT_ID,
        "event_type": "GOAL_READY",
        "occurred_at": "2026-08-26T06:01:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "previous_event_sha256": previous,
    }
    ready["event_sha256"] = gate.event_sha256(ready)
    history.append(ready)
    previous = ready["event_sha256"]
    for sequence in (90, 91):
        event = _sealed_event(sequence, previous)
        history.append(event)
        previous = event["event_sha256"]
    binding = gate.expected_r004_binding()
    correction_event = {
        "sequence": gate.SOURCE_SEQUENCE,
        "event_id": gate.CORRECTION_EVENT_ID,
        "event_type": gate.CORRECTION_EVENT_TYPE,
        "occurred_at": "2026-08-26T06:02:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "contract_supersession": {
            "replacement_contract_binding": copy.deepcopy(binding),
        },
        "previous_event_sha256": previous,
    }
    correction_event["event_sha256"] = gate.event_sha256(correction_event)
    history.append(correction_event)
    checkpoint = {
        "goal_execution": {
            "transition_history": history,
            "transition_history_anchor_sha256": correction_event["event_sha256"],
            "status_by_goal": {
                gate.TARGET_GOAL_ID: "READY",
                gate.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
            },
            "focus_goal_id": gate.TARGET_GOAL_ID,
        }
    }
    checkpoint_target = tmp_path / gate.CHECKPOINT_RELATIVE
    checkpoint_target.parent.mkdir(parents=True, exist_ok=True)
    checkpoint_target.write_bytes(_json_bytes(checkpoint))
    checkpoint_target.chmod(0o600)

    def require_contract_corrected_checkpoint(
        root: Path, value: dict[str, Any]
    ) -> None:
        assert root.resolve() == tmp_path.resolve()
        assert value["goal_execution"]["transition_history"][-1]["event_id"] == (
            gate.CORRECTION_EVENT_ID
        )

    def load_r004_contract(root: Path) -> tuple[dict[str, Any], dict[str, Any]]:
        assert root.resolve() == tmp_path.resolve()
        return copy.deepcopy(contract), copy.deepcopy(binding)

    fake = SimpleNamespace(
        GOAL_ID=gate.TARGET_GOAL_ID,
        GOAL_PATH=prior.GOAL_PATH,
        GOAL_SHA256=prior.GOAL_SHA256,
        WORK_ITEM_ID=gate.WORK_ITEM_ID,
        PARENT_GOAL_ID=gate.PARENT_GOAL_ID,
        PREDECESSOR_GOAL_ID=gate.PREDECESSOR_GOAL_ID,
        READY_EVENT_ID=prior.READY_EVENT_ID,
        READY_FRONTIER=prior.READY_FRONTIER,
        MANIFEST_SHA256=gate.MANIFEST_SHA256,
        CORRECTION_SEQUENCE=gate.SOURCE_SEQUENCE,
        CORRECTION_EVENT_ID=gate.CORRECTION_EVENT_ID,
        STARTED_SEQUENCE=gate.EVENT_SEQUENCE,
        STARTED_EVENT_ID=gate.STARTED_EVENT_ID,
        load_exact_seq91_source=lambda *_args, **_kwargs: None,
        load_r004_contract=load_r004_contract,
        r004_contract_binding=lambda root=tmp_path: load_r004_contract(root)[1],
        project_seq92=lambda *_args, **_kwargs: None,
        require_contract_corrected_checkpoint=require_contract_corrected_checkpoint,
        prepare=lambda *_args, **_kwargs: None,
        write_checkpoint=lambda *_args, **_kwargs: None,
    )
    return tmp_path, checkpoint, fake, ready, correction_event


def _binding(path: Path | str, marker: str) -> dict[str, object]:
    return {
        "path": path.as_posix() if isinstance(path, Path) else path,
        "sha256": marker * 64,
        "byte_length": 1,
    }


def _exact_seq91_source() -> tuple[bytes, dict[str, Any]]:
    live = json.loads((ROOT / gate.CHECKPOINT_RELATIVE).read_bytes())
    raw = contract_correction.reconstructed_seq91_checkpoint_bytes(ROOT, live)
    source = json.loads(raw)
    contract_correction.require_exact_seq91_source(raw, source, ROOT)
    return raw, source


def _write_stage_root(root: Path, checkpoint_raw: bytes) -> None:
    prior = r003._correction()
    for relative in (
        prior.GOAL_PATH,
        gate.MANIFEST_RELATIVE,
        gate.CONTRACT_RELATIVE,
    ):
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((ROOT / relative).read_bytes())
    checkpoint = root / gate.CHECKPOINT_RELATIVE
    checkpoint.parent.mkdir(parents=True, exist_ok=True)
    checkpoint.write_bytes(checkpoint_raw)
    checkpoint.chmod(0o600)


def _preflight_resources(
    root: Path,
) -> tuple[Any, tuple[Any, Any, Any], list[bool]]:
    payload = {"repository": "exact"}
    repository_output = gate._impl.canonical_json_bytes(payload) + b"\n"
    workspace = root / "preflight-authority"
    for relative in ("runtime/tmp", "commands", "home"):
        (workspace / relative).mkdir(parents=True)
    closed: list[bool] = []

    class Authority:
        cli_output = repository_output

        def require_exact(self, value: object, *, label: str) -> None:
            del label
            assert value == payload

    class RepositoryGuard:
        def __init__(self) -> None:
            self.workspace = workspace

        def capture_state(self, *_args: object, **_kwargs: object) -> dict[str, str]:
            return payload

    class SourceGuard:
        contents = None

    class Resources:
        def __init__(self) -> None:
            self.repository_guard = RepositoryGuard()
            self.source_guard = SourceGuard()
            self.repository_authority = None
            self.snapshot = None
            self.root_descriptor = os.open(root, os.O_RDONLY | os.O_DIRECTORY)

        def verify_source(self) -> None:
            pass

        def verify_all(self) -> None:
            pass

        def close(self, primary: BaseException | None = None) -> None:
            del primary
            os.close(self.root_descriptor)
            closed.append(True)

    resources: list[Resources] = []

    class Snapshot:
        def __init__(self, descriptor: int) -> None:
            self.root_descriptor = descriptor

        def verify(self) -> None:
            pass

        def verify_repository(self) -> None:
            pass

    def capture(_root: Path) -> Resources:
        value = Resources()
        resources.append(value)
        return value

    def snapshot_factory(*_args: object, **_kwargs: object) -> Snapshot:
        return Snapshot(resources[-1].root_descriptor)

    def runner(command: str, **kwargs: object) -> subprocess.CompletedProcess[str]:
        output = kwargs["stdout"]
        assert hasattr(output, "write")
        content = (
            repository_output
            if command == gate.CONTRACT_COMMANDS["REPOSITORY_STATE"]
            else b"PASS\n"
        )
        output.write(content)
        return subprocess.CompletedProcess(command, 0)

    return Authority(), (capture, snapshot_factory, runner), closed


def test_r004_contract_exactly_supersedes_r003_and_increments_identity() -> None:
    raw, contract = _contract()
    predecessor_raw = (ROOT / r003.CONTRACT_RELATIVE).read_bytes()
    predecessor = json.loads(predecessor_raw)
    assert contract["contract_id"] == gate.CONTRACT_ID
    assert contract["contract_version"] == gate.CONTRACT_VERSION
    assert contract["document_id"] == gate.CONTRACT_DOCUMENT_ID
    assert contract["supersedes"] == {
        "byte_length": len(predecessor_raw),
        "canonical_sha256": r003.canonical_sha256(predecessor),
        "contract_id": predecessor["contract_id"],
        "contract_version": predecessor["contract_version"],
        "document_id": predecessor["document_id"],
        "file_sha256": hashlib.sha256(predecessor_raw).hexdigest(),
        "path": r003.CONTRACT_RELATIVE.as_posix(),
        "source_correction_event_id": gate.CORRECTION_EVENT_ID,
        "source_correction_event_sequence": gate.SOURCE_SEQUENCE,
    }
    assert len(raw) == gate.CONTRACT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == gate.CONTRACT_FILE_SHA256
    assert gate.canonical_sha256(contract) == gate.CONTRACT_CANONICAL_SHA256


def test_r004_contract_has_exact_stage_aware_five_check_order() -> None:
    _raw, contract = _contract()
    assert tuple(row["check_id"] for row in contract["ordered_checks"]) == (
        gate.EXPECTED_CHECK_IDS
    )
    assert tuple(row["command"] for row in contract["ordered_checks"]) == tuple(
        gate.CONTRACT_COMMANDS[check_id] for check_id in gate.EXPECTED_CHECK_IDS
    )
    command = gate.CONTRACT_COMMANDS["ROOT_FP048_R002_CONTROL_REGRESSION"]
    required_tests = (
        gate.CORRECTION_TEST_RELATIVE,
        gate.RUNNER_TEST_RELATIVE,
        gate.START_APPLY_TEST_RELATIVE,
    )
    assert all(path.as_posix() in command for path in required_tests)
    assert command.count("tests/") == len(required_tests)
    assert "goal_started_seq92" not in command
    assert "goal_start_gate_r003" not in command
    assert "WalkSafeFp048R002Seq90Seq91BoundaryTest" not in command
    assert "WalkSafeFp048R002Seq91Seq92CorrectionBoundaryTest" not in command


def test_r004_claim_boundary_is_zero_credit_before_seq93() -> None:
    _raw, contract = _contract()
    claim = contract["claim_boundary"]
    assert claim["goal_started"] is False
    assert claim["start_gate_status"] == "NOT_RUN"
    assert claim["release_status"] == "NOT_ELIGIBLE"
    assert all(
        claim[key] == 0
        for key in (
            "actual_event_credit_delta",
            "approval_credit_delta",
            "artifact_completion_credit_delta",
            "formal_test_credit_delta",
            "product_implementation_credit_delta",
            "test_credit_delta",
        )
    )


def test_stage_aware_start_identity_is_exact_and_burned_id_is_rejected() -> None:
    assert gate.SOURCE_SEQUENCE == 92
    assert gate.EVENT_SEQUENCE == 93
    assert gate._document_id(gate.STARTED_EVENT_ID).endswith("20260826-003")
    with pytest.raises(gate.GateError, match="unused stage-aware start ID"):
        gate._document_id(r003.STARTED_EVENT_ID)


def test_seq92_fixed_interface_and_runtime_paths_use_only_new_stage_files(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _root, _source, fake, _ready, _correction = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    _unused, loaded = gate._sync_runtime_paths()
    assert loaded is fake
    assert gate._impl.SOURCE_SEQUENCE == gate.SOURCE_SEQUENCE
    assert gate._impl.GOAL_RELATIVE == fake.GOAL_PATH
    assert gate._impl.ROOT_CONTROL_TEST_RELATIVE == gate.RUNNER_TEST_RELATIVE
    assert all(
        path in gate._impl.SOURCE_GUARD_RELATIVES
        for path in (
            gate.CONTRACT_RELATIVE,
            gate.CORRECTION_RELATIVE,
            gate.CORRECTION_TEST_RELATIVE,
            gate.RUNNER_RELATIVE,
            gate.RUNNER_TEST_RELATIVE,
            gate.START_APPLY_RELATIVE,
            gate.START_APPLY_TEST_RELATIVE,
            gate.R003_GATE_RUNTIME_RELATIVE,
            gate.R003_GATE_RUNTIME_TEST_RELATIVE,
        )
    )


def test_seq92_contract_corrected_authority_is_distinct_from_seq89_ready(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    root, source, fake, ready, correction = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    authority = gate.bind_contract_corrected_source(root, source)
    assert authority.ready_event_sha256 == ready["event_sha256"]
    assert authority.correction_event_sha256 == correction["event_sha256"]
    assert authority.ready_event_sha256 != authority.correction_event_sha256
    assert authority.contract_binding == gate.expected_r004_binding()
    assert gate._impl.SOURCE_READY_EVENT_SHA256 == ready["event_sha256"]


def test_seq92_wrong_event_type_fails_closed(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    root, source, fake, _ready, _correction = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    event = source["goal_execution"]["transition_history"][-1]
    event["event_type"] = "GOAL_START_CONTROL_REANCHORED"
    event["event_sha256"] = gate.event_sha256(event)
    source["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    with pytest.raises(gate.GateError, match="seq92 contract correction differs"):
        gate.bind_contract_corrected_source(root, source)


def test_r004_contract_identity_replaces_inherited_receipt_globals(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    root, _source, fake, _ready, _correction = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    gate._load_gate_contract(root, binding=gate.expected_r004_binding())
    assert gate._impl.CONTRACT_ID == gate.CONTRACT_ID
    assert gate._impl.CONTRACT_VERSION == gate.CONTRACT_VERSION
    assert gate._impl.CONTRACT_FILE_SHA256 == gate.CONTRACT_FILE_SHA256
    assert gate._impl.CONTRACT_CANONICAL_SHA256 == gate.CONTRACT_CANONICAL_SHA256


def test_gate_context_uses_seq92_activation_and_retains_seq89_ready(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    root, source, fake, ready, correction = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    gate._sync_runtime_paths()
    retained = {
        path: b"retained runtime\n" for path in gate._impl.SOURCE_GUARD_RELATIVES
    }
    retained[gate.CHECKPOINT_RELATIVE] = _json_bytes(source)
    retained[gate.CONTRACT_RELATIVE] = (root / gate.CONTRACT_RELATIVE).read_bytes()
    retained[gate.MANIFEST_RELATIVE] = (root / gate.MANIFEST_RELATIVE).read_bytes()
    context = gate.load_gate_context(root, retained)
    assert context.source_ready_event_sha256 == ready["event_sha256"]
    assert context.source_activation_event_sha256 == correction["event_sha256"]
    assert context.source_ready_event_sha256 != context.source_activation_event_sha256
    assert context.contract_binding == gate.expected_r004_binding()


def test_private_r003_adapter_does_not_mutate_canonical_r003_module(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original = {
        "started": r003.STARTED_EVENT_ID,
        "sync": r003._sync_runtime_paths,
        "document": r003._document_id,
        "context": r003.load_gate_context,
        "impl_document": r003._impl._document_id,
    }
    private_original = {
        "installer": gate.base._install_base_adapter,
        "sync": gate.base._sync_runtime_paths,
        "document": gate.base._document_id,
        "context": gate.base.load_gate_context,
        "binding": gate.base.expected_contract_binding,
        "contract": gate.base._load_gate_contract,
        "checks": gate.base.EXPECTED_CHECK_IDS,
        "started": gate.base.STARTED_EVENT_ID,
    }
    downstream_original = {
        "sync": gate.base.base._sync_runtime_paths,
        "document": gate.base.base._document_id,
        "context": gate.base.base.load_gate_context,
        "binding": gate.base.base.expected_contract_binding,
        "contract": gate.base.base._load_gate_contract,
        "checks": gate.base.base.EXPECTED_CHECK_IDS,
        "started": gate.base.base.STARTED_EVENT_ID,
    }
    monkeypatch.setattr(gate, "_sync_runtime_paths", lambda: (None, object()))
    try:
        gate._install_base_adapter()
        gate._install_base_adapter()
        assert gate.base is not r003
        assert gate.base._sync_runtime_paths is private_original["sync"]
        assert gate.base._install_base_adapter is gate._install_base_adapter
        assert gate.base.base._sync_runtime_paths is gate._sync_runtime_paths
        assert r003.STARTED_EVENT_ID == original["started"]
        assert r003._sync_runtime_paths is original["sync"]
        assert r003._document_id is original["document"]
        assert r003.load_gate_context is original["context"]
        assert r003._impl._document_id is original["impl_document"]
    finally:
        gate.base._install_base_adapter = private_original["installer"]
        gate.base._sync_runtime_paths = private_original["sync"]
        gate.base._document_id = private_original["document"]
        gate.base.load_gate_context = private_original["context"]
        gate.base.expected_contract_binding = private_original["binding"]
        gate.base._load_gate_contract = private_original["contract"]
        gate.base.EXPECTED_CHECK_IDS = private_original["checks"]
        gate.base.STARTED_EVENT_ID = private_original["started"]
        gate.base.base._sync_runtime_paths = downstream_original["sync"]
        gate.base.base._document_id = downstream_original["document"]
        gate.base.base.load_gate_context = downstream_original["context"]
        gate.base.base.expected_contract_binding = downstream_original["binding"]
        gate.base.base._load_gate_contract = downstream_original["contract"]
        gate.base.base.EXPECTED_CHECK_IDS = downstream_original["checks"]
        gate.base.base.STARTED_EVENT_ID = downstream_original["started"]


def test_repeated_projected_exact_seq92_five_check_preflight_has_no_recursion(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _exact_seq91_source()
    authorization = _binding(contract_correction.AUTHORIZATION_REL, "a")
    review = {
        "assignment": _binding(contract_correction.REVIEW_ASSIGNMENT_REL, "b"),
        "review_result": _binding(contract_correction.REVIEW_RESULT_REL, "c"),
        "independent_review": _binding(
            contract_correction.INDEPENDENT_REVIEW_REL, "d"
        ),
    }
    runner_binding = _binding(gate.RUNNER_RELATIVE, "e")
    snapshot = source["working_tree_snapshot"]
    projected, _event = contract_correction.project_seq92(
        ROOT,
        source,
        managed_paths=snapshot["managed_changed_paths"],
        path_set_sha256=snapshot["path_set_sha256"],
        content_set_sha256=snapshot["content_set_sha256"],
        occurred_at="2026-08-26T10:30:00+09:00",
        authorization_binding_value=authorization,
        review_binding=review,
        replacement_contract_binding=gate.expected_r004_binding(),
        replacement_runner_binding=runner_binding,
    )
    _write_stage_root(tmp_path, contract_correction.checkpoint_json_bytes(projected))

    original_validator = contract_correction.require_contract_corrected_checkpoint
    monkeypatch.setattr(
        contract_correction,
        "authorization_binding",
        lambda _root, _source: copy.deepcopy(authorization),
    )
    monkeypatch.setattr(
        contract_correction,
        "_load_physical_review",
        lambda _root, _source: (
            copy.deepcopy(review),
            contract_correction._parse_time(
                "2026-08-26T10:29:59+09:00", "independent reviewed_at"
            ),
        ),
    )
    monkeypatch.setattr(
        contract_correction,
        "r003_contract_binding",
        lambda _root=ROOT: copy.deepcopy(contract_correction.R003_CONTRACT_BINDING),
    )
    monkeypatch.setattr(
        contract_correction,
        "r004_contract_binding",
        lambda _root=ROOT: gate.expected_r004_binding(),
    )
    monkeypatch.setattr(
        contract_correction,
        "r004_runner_binding",
        lambda _root=ROOT: copy.deepcopy(runner_binding),
    )
    monkeypatch.setattr(
        contract_correction,
        "require_contract_corrected_checkpoint",
        lambda root, checkpoint: original_validator(
            root, checkpoint, require_live_snapshot=False
        ),
    )

    authority, helpers, closed = _preflight_resources(tmp_path)
    capture, snapshot_factory, runner = helpers
    monkeypatch.setattr(gate._impl._GateRunResources, "capture", capture)
    monkeypatch.setattr(
        gate._impl._RepositoryStateAuthority,
        "capture",
        lambda *_args, **_kwargs: authority,
    )

    for _attempt in range(2):
        results = gate.preflight_gate(
            tmp_path,
            gate.STARTED_EVENT_ID,
            process_runner=runner,
            repository_state_guard=lambda *_args: {"repository": "exact"},
            isolated_snapshot_factory=snapshot_factory,
        )
        assert tuple(result.check_id for result in results) == gate.EXPECTED_CHECK_IDS
    assert closed == [True, True]
    assert not (tmp_path / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID).exists()


def test_seq91_cli_preflight_fails_closed_without_recursion(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    raw, _source = _exact_seq91_source()
    _write_stage_root(tmp_path, raw)
    _authority, helpers, closed = _preflight_resources(tmp_path)
    capture, _snapshot_factory, _runner = helpers
    monkeypatch.setattr(gate._impl._GateRunResources, "capture", capture)

    status = gate.main(
        (
            "--preflight",
            "--root",
            os.fspath(tmp_path),
            "--event-id",
            gate.STARTED_EVENT_ID,
        )
    )
    captured = capsys.readouterr()
    assert status == 2
    assert "checkpoint is not exact seq92 contract correction" in captured.err
    assert "Traceback" not in captured.err
    assert closed == [True]


@pytest.mark.parametrize("entrypoint", ["run_gate", "preflight_gate"])
def test_entrypoints_reuse_private_r003_atomic_add_only_runtime(
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


@pytest.mark.parametrize(
    "relative",
    (gate.R003_GATE_RUNTIME_RELATIVE, gate.R003_GATE_RUNTIME_TEST_RELATIVE),
)
def test_private_r003_runtime_is_pinned_against_same_byte_aba(
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
