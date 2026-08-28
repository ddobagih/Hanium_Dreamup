from __future__ import annotations

import copy
import hashlib
import inspect
import os
from pathlib import Path
import stat
import subprocess
import sys
import textwrap
from types import SimpleNamespace
from typing import Any

import pytest

from scripts import apply_walksafe_fp048_r002_goal_started_seq92_20260826 as start
from scripts import (
    apply_walksafe_fp048_r002_goal_start_control_correction_seq91_20260826
    as correction,
)
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import run_walksafe_fp048_r002_goal_start_gate_r003_20260826 as gate


GOAL_SHA256 = "c7632800335cd9f816b382d91ea0420a53741ae7648c04c5e9c36c3e9fc42014"


def _source() -> dict[str, Any]:
    history: list[dict[str, Any]] = []
    previous = "0" * 64
    for sequence in range(1, 89):
        event = {
            "sequence": sequence,
            "event_id": f"SYNTHETIC-{sequence:03d}",
            "event_type": "SYNTHETIC",
            "occurred_at": "2026-08-26T00:00:00+09:00",
            "previous_event_sha256": previous,
        }
        event["event_sha256"] = start.contract.event_sha256(event)
        history.append(event)
        previous = event["event_sha256"]
    ready = {
        "sequence": 89,
        "event_id": "SYNTHETIC-FP048-R002-READY",
        "event_type": "GOAL_READY",
        "occurred_at": "2026-08-26T01:29:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "previous_event_sha256": previous,
    }
    ready["event_sha256"] = start.contract.event_sha256(ready)
    seq90 = {
        "sequence": 90,
        "event_id": "SYNTHETIC-FP048-R002-SEQ90",
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "occurred_at": "2026-08-26T01:30:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "runtime_after": {
            "focus_goal_id": gate.TARGET_GOAL_ID,
            "focus_source": "IMPLEMENTATION_BACKLOG",
        },
        "previous_event_sha256": ready["event_sha256"],
    }
    seq90["event_sha256"] = start.contract.event_sha256(seq90)
    binding = {
        "schema_version": "1.1",
        "document_id": "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-003",
        "path": gate.CONTRACT_RELATIVE.as_posix(),
        "file_sha256": "b" * 64,
        "contract_id": "WS-FP048-R002-INTERNAL-START-GATE-R003",
        "contract_version": "2026-08-26.2",
        "canonical_contract_sha256": "c" * 64,
    }
    correction = {
        "sequence": 91,
        "event_id": (
            "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
            "FP048-R002-CORRECTION-20260826-001"
        ),
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "occurred_at": "2026-08-26T01:31:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "runtime_after": copy.deepcopy(seq90["runtime_after"]),
        "contract_supersession": {"replacement_contract_binding": binding},
        "previous_event_sha256": seq90["event_sha256"],
    }
    correction["event_sha256"] = start.contract.event_sha256(correction)
    history.extend((ready, seq90, correction))
    return {
        "schema_version": "1.25.0",
        "approved_state": {
            "formal_test_count": 279,
            "formal_test_not_run_count": 279,
            "remaining_gate_count": 5,
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "authority_boundary": {"normative_policy_source": "synthetic"},
        "verification_boundary": {
            "actual_device_test_status": "NOT_RUN",
            "all_remaining_gate_status": "NOT_RUN",
            "formal_test_pass_claimed": False,
            "implementation_conformance_claimed": False,
            "release_eligible": False,
        },
        "canonical_bindings": [{"role": "SYNTHETIC"}],
        "current_work": {
            "work_item_id": "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT",
            "status": "READY",
            "current_focus": "FP048 R002 READY",
            "next_action": start.STARTED_WORK_NEXT_ACTION,
            "release_completion_claimed": False,
        },
        "goal_execution": {
            "transition_history": history,
            "transition_history_anchor_sha256": correction["event_sha256"],
            "validation_cutoff_at": correction["occurred_at"],
            "goal_status": "READY",
            "status_by_goal": {
                gate.TARGET_GOAL_ID: "READY",
                gate.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
            },
            "focus_goal_id": gate.TARGET_GOAL_ID,
            "focus_goal_path": "synthetic/fp048-r002.md",
            "focus_work_item_id": "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT",
            "focus_source": "IMPLEMENTATION_BACKLOG",
            "ready_frontier_goal_ids": [gate.TARGET_GOAL_ID],
            "blockers_by_goal": {},
            "blocker_resolution_history": [],
            "blocked_goal_ids": [],
            "pending_questions": [],
            "open_question_count": 0,
            "completion_evidence_by_goal": {},
            "archived_completion_evidence_by_goal": {},
            "imported_predecessor_goal_bindings": {},
            "verification_evidence_refs": [],
            "artifact_work_queue": {},
            "completion_boundary": {},
            "dynamic_goal_inventory": {},
            "materialized_child_goal_ids_by_parent": {},
        },
        "working_tree_snapshot": {
            "scope": "synthetic seq91",
            "managed_changed_paths": ["synthetic/source.txt"],
            "managed_changed_path_count": 1,
            "path_set_sha256": "d" * 64,
            "content_set_sha256": "e" * 64,
        },
        "session_handoff": {
            "changed_files": ["synthetic/source.txt"],
            "source_commit_or_snapshot": {
                "file_count": 1,
                "path_set_sha256": "d" * 64,
                "content_set_sha256": "e" * 64,
            },
            "current_epic": "EPIC-03",
            "last_updated_by_work_item": "synthetic",
            "last_verification_status": "READY",
            "next_single_action": "synthetic gate action",
        },
    }


def _evidence(root: Path, source: dict[str, Any]) -> start.GateEvidence:
    receipt = {
        "source_checkpoint_sha256": start.sha256_bytes(start.json_bytes(source)),
        "repository_snapshot": {
            "gate_event_id": gate.STARTED_EVENT_ID,
            "snapshot_scope": "AFTER_EXACT_TRANSACTION_EXCLUSIONS",
        },
    }
    receipt_bytes = start.json_bytes(receipt)
    receipt_relative = gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID / gate.RECEIPT_NAME
    receipt_path = root / receipt_relative
    receipt_path.parent.mkdir(parents=True, mode=0o700)
    receipt_path.parent.chmod(0o700)
    receipt_path.write_bytes(receipt_bytes)
    receipt_path.chmod(0o600)
    return start.GateEvidence(
        receipt=receipt,
        receipt_bytes=receipt_bytes,
        receipt_binding={
            "document_id": gate._document_id(gate.STARTED_EVENT_ID),
            "path": receipt_relative.as_posix(),
            "file_sha256": start.sha256_bytes(receipt_bytes),
        },
        repository_payload={"synthetic": True},
        event_occurred_at="2026-08-26T01:32:01+09:00",
    )


def _patch_runtime(
    source: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    ready = source["goal_execution"]["transition_history"][88]
    correction_event = source["goal_execution"]["transition_history"][90]
    binding = correction_event["contract_supersession"]["replacement_contract_binding"]
    correction = SimpleNamespace(
        WORK_ITEM_ID="EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT",
        READY_EVENT_ID=ready["event_id"],
        CORRECTION_SEQUENCE=91,
        CORRECTION_EVENT_ID=correction_event["event_id"],
        CORRECTION_NEXT_ACTION=source["session_handoff"]["next_single_action"],
        require_control_corrected_checkpoint=lambda _root, value: (
            None
            if value is source or value.get("goal_execution", {}).get("transition_history", [])[:91]
            == source["goal_execution"]["transition_history"]
            else (_ for _ in ()).throw(ValueError("wrong checkpoint"))
        ),
        reconstructed_seq91_checkpoint_bytes=lambda _root, _value: start.json_bytes(source),
    )
    authority = SimpleNamespace(
        ready_event_sha256=ready["event_sha256"],
        correction_event_sha256=correction_event["event_sha256"],
        contract_binding=binding,
    )
    monkeypatch.setattr(start, "_correction", lambda: correction)
    monkeypatch.setattr(gate, "bind_corrected_source", lambda _root, value: (
        authority
        if value is source
        else (_ for _ in ()).throw(ValueError("wrong checkpoint"))
    ))
    monkeypatch.setattr(gate._impl, "TARGET_GOAL_SHA256", GOAL_SHA256)
    monkeypatch.setitem(gate.__dict__, "TARGET_GOAL_SHA256", GOAL_SHA256)


def _final_paths(root: Path) -> dict[Path, str]:
    content_by_path = {
        Path("synthetic/source.txt"): b"source\n",
        start.SCRIPT_RELATIVE: b"starter\n",
        start.TEST_RELATIVE: b"test\n",
    }
    for relative, content in content_by_path.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    return {
        relative: start.sha256_bytes(content)
        for relative, content in content_by_path.items()
    }


def _sealed(path: Path, marker: str) -> dict[str, object]:
    return {
        "path": path.as_posix(),
        "sha256": marker * 64,
        "byte_length": 1,
    }


def _gate_namespace(
    root: Path,
    *,
    receipt: bytes = b'{"x":1}\n',
) -> tuple[SimpleNamespace, dict[Path, Any]]:
    checks = tuple((check_id, f"command-{index}") for index, check_id in enumerate(
        gate.EXPECTED_CHECK_IDS, start=1
    ))
    context = SimpleNamespace(checks=checks)
    event_root = root / gate.GATE_ROOT_RELATIVE / start.EVENT_ID
    event_root.mkdir(parents=True, mode=0o700)
    event_root.chmod(0o700)
    for index, (check_id, _command) in enumerate(checks, start=1):
        path = event_root / f"{index:02d}-{check_id}.log"
        path.write_bytes(f"log-{index}\n".encode())
        path.chmod(0o600)
    receipt_path = event_root / gate.RECEIPT_NAME
    receipt_path.write_bytes(receipt)
    receipt_path.chmod(0o600)
    return context, start._capture_gate_inputs(root, context, start.EVENT_ID)


def _minimal_projected_checkpoint(
) -> tuple[dict[str, Any], dict[str, Any], bytes]:
    history = [{"sequence": sequence} for sequence in range(1, start.EVENT_SEQUENCE)]
    event = {
        "sequence": start.EVENT_SEQUENCE,
        "event_id": start.EVENT_ID,
        "runtime_after": {"open_question_count": 0},
    }
    history.append(event)
    projected = {"goal_execution": {"transition_history": history}}
    return projected, event, start.json_bytes(projected)


def _reviewed_seq91_source() -> dict[str, Any]:
    _raw, source = correction.load_exact_seq90_source(start.ROOT)
    managed_paths = list(source["working_tree_snapshot"]["managed_changed_paths"])
    projected, _event = correction.project_seq91(
        start.ROOT,
        source,
        managed_paths=managed_paths,
        path_set_sha256="d" * 64,
        content_set_sha256="e" * 64,
        occurred_at="2026-08-26T05:30:00+09:00",
        authorization_binding=_sealed(correction.AUTHORIZATION_REL, "a"),
        review_binding={
            "assignment": _sealed(correction.REVIEW_ASSIGNMENT_REL, "b"),
            "review_result": _sealed(correction.REVIEW_RESULT_REL, "c"),
            "independent_review": _sealed(correction.INDEPENDENT_REVIEW_REL, "d"),
        },
        contract_binding=correction.r003_contract_binding(start.ROOT),
        runner_binding=_sealed(correction.R003_GATE_REL, "e"),
    )
    return projected


def test_seq92_projection_is_exact_one_ready_to_in_progress_zero_credit_change(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    projected, event = start.project_seq92(
        tmp_path,
        source,
        _evidence(tmp_path, source),
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=_final_paths(tmp_path),
    )
    assert set(event) == start.EVENT_FIELDS
    assert event["sequence"] == 92
    assert event["event_id"] == gate.STARTED_EVENT_ID
    assert event["previous_event_sha256"] == source["goal_execution"]["transition_history"][-1]["event_sha256"]
    assert event["status_changes"] == {gate.TARGET_GOAL_ID: "IN_PROGRESS"}
    assert projected["goal_execution"]["transition_history"][:91] == source["goal_execution"]["transition_history"]
    assert projected["goal_execution"]["status_by_goal"] == {
        gate.TARGET_GOAL_ID: "IN_PROGRESS",
        gate.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
    }
    assert projected["goal_execution"]["goal_status"] == "IN_PROGRESS"
    assert projected["approved_state"] == source["approved_state"]
    assert projected["verification_boundary"] == source["verification_boundary"]
    assert projected["canonical_bindings"] == source["canonical_bindings"]
    assert projected["current_work"]["status"] == "IN_PROGRESS"
    assert "GAP-057" in projected["current_work"]["current_focus"]
    assert start.validate_history_suffix(tmp_path, projected) == []
    projected["goal_execution"]["goal_status"] = "READY"
    assert start.validate_history_suffix(tmp_path, projected) == [
        "seq92 state projection differs"
    ]


@pytest.mark.parametrize(
    "mutation",
    [
        "event_extra",
        "event_focus_content",
        "event_repository_snapshot",
        "goal_status",
        "anchor",
        "cutoff",
        "work_item",
        "work_status",
        "work_focus",
        "work_next_action",
        "work_release",
        "snapshot_scope",
        "snapshot_count",
        "snapshot_path_hash",
        "snapshot_content_hash",
        "handoff_changed_files",
        "handoff_current_epic",
        "handoff_last_updated",
        "handoff_last_verification",
        "handoff_next_action",
        "mirror_count",
        "mirror_path_hash",
        "mirror_content_hash",
    ],
)
def test_seq92_suffix_rejects_each_forged_inverse_delta_field(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    projected, event = start.project_seq92(
        tmp_path,
        source,
        _evidence(tmp_path, source),
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=_final_paths(tmp_path),
    )
    state = projected["goal_execution"]
    current = projected["current_work"]
    snapshot = projected["working_tree_snapshot"]
    handoff = projected["session_handoff"]
    mirror = handoff["source_commit_or_snapshot"]
    if mutation == "event_extra":
        event["forged"] = True
    elif mutation == "event_focus_content":
        event["focus_goal_content_sha256"] = "f" * 64
    elif mutation == "event_repository_snapshot":
        event["repository_snapshot_before"] = {"forged": True}
    elif mutation == "goal_status":
        state["goal_status"] = "READY"
    elif mutation == "anchor":
        state["transition_history_anchor_sha256"] = "f" * 64
    elif mutation == "cutoff":
        state["validation_cutoff_at"] = "2026-08-26T01:32:02+09:00"
    elif mutation == "work_item":
        current["work_item_id"] = "FORGED"
    elif mutation == "work_status":
        current["status"] = "READY"
    elif mutation == "work_focus":
        current["current_focus"] = "FORGED"
    elif mutation == "work_next_action":
        current["next_action"] = "FORGED"
    elif mutation == "work_release":
        current["release_completion_claimed"] = True
    elif mutation == "snapshot_scope":
        snapshot["scope"] = "FORGED"
    elif mutation == "snapshot_count":
        snapshot["managed_changed_path_count"] += 1
    elif mutation == "snapshot_path_hash":
        snapshot["path_set_sha256"] = "f" * 64
    elif mutation == "snapshot_content_hash":
        snapshot["content_set_sha256"] = "f" * 64
    elif mutation == "handoff_changed_files":
        handoff["changed_files"] = list(reversed(handoff["changed_files"]))
    elif mutation == "handoff_current_epic":
        handoff["current_epic"] = "FORGED"
    elif mutation == "handoff_last_updated":
        handoff["last_updated_by_work_item"] = "FORGED"
    elif mutation == "handoff_last_verification":
        handoff["last_verification_status"] = "FORGED"
    elif mutation == "handoff_next_action":
        handoff["next_single_action"] = "FORGED"
    elif mutation == "mirror_count":
        mirror["file_count"] += 1
    elif mutation == "mirror_path_hash":
        mirror["path_set_sha256"] = "f" * 64
    else:
        mirror["content_set_sha256"] = "f" * 64
    if mutation.startswith("event_"):
        event["event_sha256"] = start.contract.event_sha256(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
    assert start.validate_history_suffix(tmp_path, projected)


def test_actual_seq92_projection_passes_goal_graph_state_validator(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = _reviewed_seq91_source()
    _patch_runtime(source, monkeypatch)
    managed = set(source["working_tree_snapshot"]["managed_changed_paths"])
    managed.update(
        {start.SCRIPT_RELATIVE.as_posix(), start.TEST_RELATIVE.as_posix()}
    )
    final_sha256_by_path = {
        Path(path): start.sha256_bytes((start.ROOT / path).read_bytes())
        for path in managed
    }
    projected, _event = start.project_seq92(
        start.ROOT,
        source,
        _evidence(tmp_path, source),
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=final_sha256_by_path,
    )
    assert goal_graph.validate_fp048_r002_seq91_92(
        start.ROOT, projected
    ) == []


def test_seq92_test_authority_overrides_a_shadowed_gate_goal_sha256(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _source()
    monkeypatch.setitem(gate.__dict__, "TARGET_GOAL_SHA256", "0" * 64)
    _patch_runtime(source, monkeypatch)
    assert gate.TARGET_GOAL_SHA256 == GOAL_SHA256
    assert gate._impl.TARGET_GOAL_SHA256 == GOAL_SHA256


def test_seq92_rejects_burned_event_id_and_changed_seq91_authority(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    with pytest.raises(start.StartApplyError, match="event ID"):
        start.project_seq92(
            tmp_path,
            source,
            _evidence(tmp_path, source),
            event_id=gate.BURNED_STARTED_EVENT_ID,
            final_sha256_by_path=_final_paths(tmp_path),
        )
    source["goal_execution"]["goal_status"] = "IN_PROGRESS"
    with pytest.raises(start.StartApplyError, match="exclusively READY"):
        start.require_exact_source(tmp_path, source)
    source["goal_execution"]["goal_status"] = "READY"
    source["goal_execution"]["transition_history"][-1]["status_changes"] = {
        gate.TARGET_GOAL_ID: "IN_PROGRESS"
    }
    with pytest.raises(start.StartApplyError, match="seq91"):
        start.require_exact_source(tmp_path, source)


def test_seq92_post_validator_rejects_resealed_wrong_source_checkpoint_sha256(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    evidence = _evidence(tmp_path, source)
    projected, _ = start.project_seq92(
        tmp_path,
        source,
        evidence,
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=_final_paths(tmp_path),
    )
    assert start.validate_history_suffix(tmp_path, projected) == []
    tampered_receipt = copy.deepcopy(evidence.receipt)
    tampered_receipt["source_checkpoint_sha256"] = "f" * 64
    tampered_bytes = start.json_bytes(tampered_receipt)
    receipt_path = tmp_path / evidence.receipt_binding["path"]
    receipt_path.write_bytes(tampered_bytes)
    receipt_path.chmod(0o600)
    event = projected["goal_execution"]["transition_history"][91]
    event["implementation_start_gate_binding"]["file_sha256"] = start.sha256_bytes(tampered_bytes)
    event["event_sha256"] = start.contract.event_sha256(event)
    projected["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    assert start.validate_history_suffix(tmp_path, projected) == [
        "seq92 gate receipt source checkpoint SHA-256 differs"
    ]


def test_failed_preflight_never_calls_publication(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    writes: list[str] = []

    def fail(*_args: object, **_kwargs: object) -> Any:
        raise start.StartApplyError("synthetic missing fresh R003 PASS receipt")

    monkeypatch.setattr(start, "prepare_projection", fail)
    monkeypatch.setattr(start, "write_projection", lambda *_args, **_kwargs: writes.append("checkpoint"))
    result = start.main(
        ["--write", "--event-id", gate.STARTED_EVENT_ID, "--root", str(tmp_path)]
    )
    assert result == 1
    assert writes == []


def test_seq92_import_never_loads_legacy_starter_runtimes() -> None:
    program = textwrap.dedent(
        """
        import builtins
        import importlib.util
        from pathlib import Path
        import sys

        blocked_names = {
            "scripts.apply_walksafe_fp048_r002_goal_started_seq91_20260826",
            "scripts.apply_walksafe_fp046_r002_goal_started_seq78_20260823",
        }
        blocked_files = {
            "apply_walksafe_fp048_r002_goal_started_seq91_20260826.py",
            "apply_walksafe_fp046_r002_goal_started_seq78_20260823.py",
        }
        counts = {"import": 0, "spec": 0}
        original_import = builtins.__import__
        original_spec = importlib.util.spec_from_file_location

        def guarded_import(name, *args, **kwargs):
            if name in blocked_names:
                counts["import"] += 1
                raise AssertionError(f"legacy import: {name}")
            return original_import(name, *args, **kwargs)

        def guarded_spec(name, location, *args, **kwargs):
            if Path(location).name in blocked_files:
                counts["spec"] += 1
                raise AssertionError(f"legacy spec load: {location}")
            return original_spec(name, location, *args, **kwargs)

        builtins.__import__ = guarded_import
        importlib.util.spec_from_file_location = guarded_spec
        from scripts import apply_walksafe_fp048_r002_goal_started_seq92_20260826 as start

        assert start.EVENT_SEQUENCE == 92
        assert start.write_projection.__globals__["EVENT_SEQUENCE"] == 92
        assert not hasattr(start, "_impl")
        assert blocked_names.isdisjoint(sys.modules)
        print(counts["import"], counts["spec"])
        """
    )
    completed = subprocess.run(
        [sys.executable, "-B", "-c", program],
        cwd=start.ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr
    assert completed.stdout == "0 0\n"


def test_seq92_runtime_closure_loads_only_after_lazy_goal_graph_validator() -> None:
    program = textwrap.dedent(
        """
        from pathlib import Path
        import sys

        from scripts import apply_walksafe_fp048_r002_goal_started_seq92_20260826 as start

        root = start.ROOT.resolve()
        expected = set(start.RUNTIME_TRUST_CLOSURE_PATHS)

        def loaded_repository_files():
            loaded = set()
            for module in tuple(sys.modules.values()):
                filename = getattr(module, "__file__", None)
                if filename is None:
                    continue
                try:
                    loaded.add(Path(filename).resolve().relative_to(root))
                except (OSError, ValueError):
                    pass
            return loaded

        assert expected.isdisjoint(loaded_repository_files())
        start.run_goal_graph_checker(start.ROOT, start.CHECKPOINT_RELATIVE)
        assert expected <= loaded_repository_files()
        print("lazy-runtime-closure-ok")
        """
    )
    completed = subprocess.run(
        [sys.executable, "-B", "-c", program],
        cwd=start.ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr
    assert completed.stdout == "lazy-runtime-closure-ok\n"


def test_seq92_uses_local_prepared_projection_and_snapshot_digest() -> None:
    transport = SimpleNamespace(
        root=Path("/synthetic"),
        source_raw=b"source",
        source={"sequence": 91},
        projected={"sequence": 92},
        projected_raw=b"projected",
        event={"sequence": 92},
        managed_inputs={
            Path("b.txt"): SimpleNamespace(sha256="2" * 64),
            Path("a.txt"): SimpleNamespace(sha256="1" * 64),
        },
    )
    evidence = start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00")
    prepared = start.PreparedProjection(
        transport=transport,
        checkpoint_path=Path("/synthetic/checkpoint.json"),
        event_id=start.EVENT_ID,
        context=SimpleNamespace(),
        evidence=evidence,
    )
    assert prepared.source_checkpoint_bytes == b"source"
    assert prepared.projected_checkpoint_bytes == b"projected"
    assert prepared.event == {"sequence": 92}
    assert prepared.final_sha256_by_path == {
        Path("a.txt"): "1" * 64,
        Path("b.txt"): "2" * 64,
    }
    expected_paths = hashlib.sha256(b"a.txt\nb.txt\n").hexdigest()
    content = hashlib.sha256()
    for path, digest in (("a.txt", "1" * 64), ("b.txt", "2" * 64)):
        content.update(path.encode())
        content.update(b"\0")
        content.update(digest.encode())
        content.update(b"\n")
    assert start._snapshot_hashes_from_digests(prepared.final_sha256_by_path) == (
        expected_paths,
        content.hexdigest(),
    )


def test_seq92_runtime_trust_closure_is_always_managed() -> None:
    expected = (
        Path("scripts/apply_walksafe_fp046_goal_completed_seq54_55_20260810.py"),
        Path("scripts/build_walksafe_fp008_admin_review_delivery_trace_20260803.py"),
        Path("scripts/build_walksafe_fp008_artifact_trace_successor_20260803.py"),
        Path("scripts/build_walksafe_fp008_gap_backlog_r024_20260803.py"),
        Path("scripts/build_walksafe_fp046_artifact_trace_successor_20260810.py"),
        Path("scripts/build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810.py"),
        Path("scripts/build_walksafe_fp046_gap_backlog_r025_20260810.py"),
        Path("scripts/build_walksafe_fp046_strict_review_gate_20260810.py"),
        Path("scripts/build_walksafe_phase1_exact257_successor_r014_20260810.py"),
        Path("scripts/materialize_walksafe_fp048_goal_20260802.py"),
    )
    assert start.RUNTIME_TRUST_CLOSURE_PATHS == expected
    assert gate.IMPORT_TRUST_RUNTIME_CLOSURE_RELATIVES == expected
    managed = set(start._final_managed_paths(_source(), ()))
    assert {path.as_posix() for path in expected} <= managed
    implementation = inspect.getsource(start.prepare_projection)
    assert implementation.index("source = _strict_json") < implementation.index(
        "managed ="
    ) < implementation.index("require_exact_source(") < implementation.index(
        "live_errors ="
    )


def test_default_writer_receives_exact_seq92_commit_guard(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []
    prepared = start.PreparedProjection(
        transport=SimpleNamespace(),
        checkpoint_path=Path("checkpoint.json"),
        event_id=start.EVENT_ID,
        context=SimpleNamespace(),
        evidence=start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00"),
    )

    def require_exact(_prepared: start.PreparedProjection) -> None:
        calls.append("guard")

    def write_checkpoint(_transport: Any, *, commit_guard: Any) -> None:
        calls.append("write")
        commit_guard()

    monkeypatch.setattr(start, "_require_prepared_exact", require_exact)
    monkeypatch.setattr(start.seq90, "write_checkpoint", write_checkpoint)
    monkeypatch.setattr(
        start,
        "_verify_published_projection",
        lambda _prepared: calls.append("verify"),
    )
    start.write_projection(prepared)
    assert calls == ["guard", "write", "guard", "verify"]


def test_seq92_cli_keeps_only_preflight_and_write_modes() -> None:
    preflight = start.parse_args(
        ["--preflight", "--event-id", start.EVENT_ID, "--root", str(start.ROOT)]
    )
    write = start.parse_args(
        ["--write", "--event-id", start.EVENT_ID, "--root", str(start.ROOT)]
    )
    assert preflight.preflight is True and preflight.write is False
    assert write.write is True and write.preflight is False
    with pytest.raises(SystemExit):
        start.parse_args(
            ["--refresh-catalogs", "--event-id", start.EVENT_ID]
        )


def test_checkpoint_transport_uses_source_cas_and_commit_guard(tmp_path: Path) -> None:
    target = tmp_path / "checkpoint.json"
    source = b"source\n"
    projected = b"projected\n"
    target.write_bytes(source)
    target.chmod(0o600)
    calls: list[str] = []

    def guard() -> None:
        calls.append("guard")

    def writer(path: Path, content: bytes, *, expected_source: bytes, commit_guard: Any) -> None:
        calls.append("writer")
        assert path.read_bytes() == expected_source == source
        commit_guard()
        path.write_bytes(content)
        path.chmod(0o600)

    start._write_projection_transport(
        writer,
        target,
        projected,
        expected_source=source,
        commit_guard=guard,
    )
    assert target.read_bytes() == projected
    assert calls == ["writer", "guard"]


def test_checkpoint_transport_rejects_writer_that_skips_commit_guard(
    tmp_path: Path,
) -> None:
    target = tmp_path / "checkpoint.json"
    target.write_bytes(b"source\n")

    def writer(path: Path, content: bytes, **_kwargs: Any) -> None:
        path.write_bytes(content)

    with pytest.raises(start.seq90.PostcommitUncertain, match="without its commit guard"):
        start._write_projection_transport(
            writer,
            target,
            b"projected\n",
            expected_source=b"source\n",
            commit_guard=lambda: None,
        )


def test_checkpoint_transport_classifies_error_after_replace_as_postcommit(
    tmp_path: Path,
) -> None:
    target = tmp_path / "checkpoint.json"
    target.write_bytes(b"source\n")

    def writer(
        path: Path,
        content: bytes,
        *,
        commit_guard: Any,
        **_kwargs: Any,
    ) -> None:
        commit_guard()
        path.write_bytes(content)
        raise start.seq90.ControlReanchorError("synthetic failure after replace")

    with pytest.raises(start.seq90.PostcommitUncertain, match="unclassified failure"):
        start._write_projection_transport(
            writer,
            target,
            b"projected\n",
            expected_source=b"source\n",
            commit_guard=lambda: None,
        )


def test_checkpoint_transport_same_bytes_new_identity_is_postcommit_uncertain(
    tmp_path: Path,
) -> None:
    target = tmp_path / "checkpoint.json"
    source = b"source\n"
    target.write_bytes(source)
    target.chmod(0o600)
    source_inode = target.stat().st_ino

    def writer(
        path: Path,
        _content: bytes,
        *,
        expected_source: bytes,
        commit_guard: Any,
    ) -> None:
        commit_guard()
        temporary = path.with_name(".same-byte-replacement")
        temporary.write_bytes(expected_source)
        temporary.chmod(0o644)
        os.replace(temporary, path)
        raise start.seq90.ControlReanchorError("synthetic failure after replacement")

    with pytest.raises(start.seq90.PostcommitUncertain, match="unclassified failure"):
        start._write_projection_transport(
            writer,
            target,
            b"projected\n",
            expected_source=source,
            commit_guard=lambda: None,
        )
    assert target.read_bytes() == source
    assert target.stat().st_ino != source_inode
    assert stat.S_IMODE(target.stat().st_mode) == 0o644


@pytest.mark.parametrize("published_mode", (0o600, 0o644))
def test_injected_writer_checkpoint_mode_is_resealed_after_publication(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    published_mode: int,
) -> None:
    checkpoint_path = tmp_path / start.CHECKPOINT_RELATIVE
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    source_raw = b'{"sequence":91}\n'
    checkpoint_path.write_bytes(source_raw)
    checkpoint_path.chmod(0o600)
    projected, event, projected_raw = _minimal_projected_checkpoint()
    transport = SimpleNamespace(
        root=tmp_path,
        source_raw=source_raw,
        projected=projected,
        projected_raw=projected_raw,
        event=event,
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=b"",
        git_head="head",
        git_branch="branch",
    )
    prepared = start.PreparedProjection(
        transport=transport,
        checkpoint_path=checkpoint_path,
        event_id=start.EVENT_ID,
        context=SimpleNamespace(),
        evidence=start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00"),
    )
    consumer_calls: list[str] = []

    def writer(
        path: Path,
        content: bytes,
        *,
        expected_source: bytes,
        commit_guard: Any,
    ) -> None:
        assert path.read_bytes() == expected_source
        commit_guard()
        temporary = path.with_name(".injected-checkpoint")
        temporary.write_bytes(content)
        temporary.chmod(published_mode)
        os.replace(temporary, path)

    def terminal_consumer(_root: Path, candidate: dict[str, Any]) -> list[str]:
        consumer_calls.append("terminal")
        candidate["goal_execution"]["transition_history"][-1]["event_id"] = "FORGED"
        return []

    monkeypatch.setattr(start, "_require_prepared_exact", lambda *_args: None)
    monkeypatch.setattr(start, "_capture_gate_inputs", lambda *_args: {})
    monkeypatch.setattr(start, "validate_projected_checkpoint", terminal_consumer)
    monkeypatch.setattr(start.seq90, "_require_git_context", lambda *_args: None)
    monkeypatch.setattr(
        start.seq90,
        "capture_git_visible_paths",
        lambda _root: (b"", ()),
    )
    if published_mode == 0o600:
        start.write_projection(prepared, atomic_writer=writer)
    else:
        with pytest.raises(start.seq90.PostcommitUncertain, match="terminal validation"):
            start.write_projection(prepared, atomic_writer=writer)
    assert checkpoint_path.read_bytes() == projected_raw
    assert stat.S_IMODE(checkpoint_path.stat().st_mode) == published_mode
    if published_mode == 0o600:
        assert consumer_calls == ["terminal"]
        assert transport.projected["goal_execution"]["transition_history"][-1] == event
        assert transport.projected_raw == start.json_bytes(transport.projected)
    else:
        assert consumer_calls == []


def test_gate_evidence_requires_private_modes_and_canonical_receipt(
    tmp_path: Path,
) -> None:
    context, retained = _gate_namespace(tmp_path)
    receipt_path = gate.GATE_ROOT_RELATIVE / start.EVENT_ID / gate.RECEIPT_NAME
    with pytest.raises(start.StartApplyError, match="canonical"):
        start.validate_gate_evidence(
            tmp_path,
            {},
            b"source",
            context,
            event_id=start.EVENT_ID,
            capture_repository_state=lambda *_args: {},
            retained_inputs=retained,
        )
    physical = tmp_path / receipt_path
    physical.chmod(0o644)
    with pytest.raises(start.StartApplyError, match="gate evidence"):
        start._capture_gate_inputs(tmp_path, context, start.EVENT_ID)


@pytest.mark.parametrize("reordered", ["top", "window", "run"])
def test_gate_evidence_rejects_reordered_pretty_receipt(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    reordered: str,
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    binding = source["goal_execution"]["transition_history"][-1][
        "contract_supersession"
    ]["replacement_contract_binding"]
    checks = tuple(
        (check_id, f"command-{index}")
        for index, check_id in enumerate(gate.EXPECTED_CHECK_IDS, start=1)
    )
    source_raw = start.json_bytes(source)
    context = SimpleNamespace(
        checks=checks,
        checkpoint_sha256=start.sha256_bytes(source_raw),
        target_goal_sha256=GOAL_SHA256,
        source_activation_event_sha256=source["goal_execution"][
            "transition_history"
        ][-1]["event_sha256"],
        source_ready_event_sha256=source["goal_execution"]["transition_history"][
            88
        ]["event_sha256"],
        contract_binding=binding,
        runtime_bindings=(),
    )
    event_root = tmp_path / gate.GATE_ROOT_RELATIVE / start.EVENT_ID
    event_root.mkdir(parents=True, mode=0o700)
    event_root.chmod(0o700)
    runs: list[dict[str, Any]] = []
    for index, (check_id, command) in enumerate(checks, start=1):
        output = (
            start.contract.canonical_json_bytes({}) + b"\n"
            if check_id == "REPOSITORY_STATE"
            else f"log-{index}\n".encode()
        )
        relative = (
            gate.GATE_ROOT_RELATIVE
            / start.EVENT_ID
            / f"{index:02d}-{check_id}.log"
        )
        path = tmp_path / relative
        path.write_bytes(output)
        path.chmod(0o600)
        runs.append(
            {
                "check_id": check_id,
                "command": command,
                "output_path": relative.as_posix(),
                "output_sha256": start.sha256_bytes(output),
                "exit_code": 0,
                "executed_at": f"2026-08-26T01:32:0{index}+09:00",
            }
        )
    snapshot = {"synthetic": True}
    monkeypatch.setattr(
        gate._impl,
        "repository_snapshot_from_payload",
        lambda *_args, **_kwargs: snapshot,
    )
    receipt = {
        "schema_version": "1.1",
        "document_id": gate._document_id(start.EVENT_ID),
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": "INITIAL_START",
        "status": "PASS",
        "package_id": gate.PACKAGE_ID,
        "target_transition_event_id": start.EVENT_ID,
        "target_goal_id": gate.TARGET_GOAL_ID,
        "target_goal_content_sha256": context.target_goal_sha256,
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "source_activation_event_sha256": context.source_activation_event_sha256,
        "source_checkpoint_sha256": context.checkpoint_sha256,
        "source_ready_event_sha256": context.source_ready_event_sha256,
        "check_command_contract_version": binding["contract_version"],
        "check_command_contract_sha256": binding[
            "canonical_contract_sha256"
        ],
        "implementation_start_gate_contract_binding": binding,
        "runtime_bindings": [],
        "execution_window": {
            "started_at": "2026-08-26T01:32:00+09:00",
            "ended_at": "2026-08-26T01:32:06+09:00",
        },
        "check_runs": runs,
        "repository_snapshot": snapshot,
        "generated_at": "2026-08-26T01:32:07+09:00",
    }
    if reordered == "top":
        receipt = dict(reversed(tuple(receipt.items())))
    elif reordered == "window":
        receipt["execution_window"] = dict(
            reversed(tuple(receipt["execution_window"].items()))
        )
    else:
        receipt["check_runs"][0] = dict(
            reversed(tuple(receipt["check_runs"][0].items()))
        )
    receipt_path = event_root / gate.RECEIPT_NAME
    receipt_path.write_bytes(start.json_bytes(receipt))
    receipt_path.chmod(0o600)
    retained = start._capture_gate_inputs(tmp_path, context, start.EVENT_ID)
    with pytest.raises(start.StartApplyError, match="exact gate producer order"):
        start.validate_gate_evidence(
            tmp_path,
            source,
            source_raw,
            context,
            event_id=start.EVENT_ID,
            capture_repository_state=lambda *_args: {},
            retained_inputs=retained,
        )


def test_gate_namespace_membership_is_resealed_before_and_after_publication(
    tmp_path: Path,
) -> None:
    context, retained = _gate_namespace(tmp_path)
    transport = SimpleNamespace(root=tmp_path, retained_inputs=retained)
    prepared = start.PreparedProjection(
        transport=transport,
        checkpoint_path=tmp_path / start.CHECKPOINT_RELATIVE,
        event_id=start.EVENT_ID,
        context=context,
        evidence=start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00"),
    )
    unexpected = (
        tmp_path / gate.GATE_ROOT_RELATIVE / start.EVENT_ID / ".DS_Store"
    )
    unexpected.write_bytes(b"ignored\n")
    unexpected.chmod(0o600)
    with pytest.raises(start.StartApplyError, match="file set differs"):
        start._require_prepared_exact(prepared)
    with pytest.raises(start.seq90.PostcommitUncertain, match="terminal validation"):
        start._verify_published_projection(prepared)


@pytest.mark.parametrize(
    "mutation",
    ("checkpoint_and_receipt", "receipt_only", "managed", "git_status"),
)
def test_terminal_consumers_cannot_return_success_after_publication_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    context, retained = _gate_namespace(tmp_path)
    checkpoint_path = tmp_path / start.CHECKPOINT_RELATIVE
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    projected, event, projected_raw = _minimal_projected_checkpoint()
    checkpoint_path.write_bytes(projected_raw)
    checkpoint_path.chmod(0o600)
    managed_path = tmp_path / "managed.txt"
    managed_path.write_bytes(b"managed\n")
    receipt_path = (
        tmp_path
        / gate.GATE_ROOT_RELATIVE
        / start.EVENT_ID
        / gate.RECEIPT_NAME
    )
    status_changed = False
    transport = SimpleNamespace(
        root=tmp_path,
        projected=projected,
        projected_raw=projected_raw,
        event=event,
        retained_inputs=retained,
        managed_inputs=start.seq90._capture_managed_inputs(
            tmp_path,
            ("managed.txt",),
        ),
        git_visible_inputs={},
        git_status_raw=b"status",
        git_head="head",
        git_branch="branch",
    )
    prepared = start.PreparedProjection(
        transport=transport,
        checkpoint_path=checkpoint_path,
        event_id=start.EVENT_ID,
        context=context,
        evidence=start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00"),
    )

    def malicious_terminal_consumer(*_args: Any, **_kwargs: Any) -> list[str]:
        nonlocal status_changed
        if mutation == "checkpoint_and_receipt":
            checkpoint_path.write_bytes(b"forged checkpoint\n")
            receipt_path.write_bytes(b"forged receipt\n")
        elif mutation == "receipt_only":
            receipt_path.write_bytes(b"forged receipt\n")
        elif mutation == "managed":
            managed_path.write_bytes(b"forged managed\n")
        else:
            status_changed = True
        return []

    monkeypatch.setattr(start, "validate_projected_checkpoint", malicious_terminal_consumer)
    monkeypatch.setattr(start.seq90, "_require_git_context", lambda *_args: None)
    monkeypatch.setattr(
        start.seq90,
        "capture_git_visible_paths",
        lambda _root: (b"changed" if status_changed else b"status", ()),
    )
    with pytest.raises(start.seq90.PostcommitUncertain, match="terminal validation"):
        start._verify_published_projection(prepared)
    if mutation == "checkpoint_and_receipt":
        assert checkpoint_path.read_bytes() == b"forged checkpoint\n"
        assert receipt_path.read_bytes() == b"forged receipt\n"


@pytest.mark.parametrize("forged_authority", ("projected", "event"))
def test_terminal_verifier_rejects_raw_object_or_event_divergence(
    tmp_path: Path,
    forged_authority: str,
) -> None:
    checkpoint_path = tmp_path / start.CHECKPOINT_RELATIVE
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    projected, event, projected_raw = _minimal_projected_checkpoint()
    checkpoint_path.write_bytes(projected_raw)
    checkpoint_path.chmod(0o600)
    if forged_authority == "projected":
        projected = copy.deepcopy(projected)
        projected["goal_execution"]["transition_history"][-1]["event_id"] = "FORGED"
    else:
        event = copy.deepcopy(event)
        event["event_id"] = "FORGED"
    prepared = start.PreparedProjection(
        transport=SimpleNamespace(
            root=tmp_path,
            projected=projected,
            projected_raw=projected_raw,
            event=event,
            retained_inputs={},
            managed_inputs={},
            git_visible_inputs={},
            git_status_raw=b"",
            git_head="head",
            git_branch="branch",
        ),
        checkpoint_path=checkpoint_path,
        event_id=start.EVENT_ID,
        context=SimpleNamespace(),
        evidence=start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00"),
    )
    with pytest.raises(start.seq90.PostcommitUncertain, match="terminal validation"):
        start._verify_published_projection(prepared)


@pytest.mark.parametrize(
    ("forged_authority", "expected_error"),
    (
        ("projected_only", "projected checkpoint authority differs"),
        ("aliased_event", "projected checkpoint authority differs"),
        ("detached_event", "projected event authority differs"),
    ),
)
def test_projection_authority_rejects_nested_integer_boolean_type_confusion(
    forged_authority: str,
    expected_error: str,
) -> None:
    projected, event, projected_raw = _minimal_projected_checkpoint()
    if forged_authority == "projected_only":
        projected = copy.deepcopy(projected)
        projected["goal_execution"]["transition_history"][-1]["runtime_after"][
            "open_question_count"
        ] = False
    elif forged_authority == "aliased_event":
        event["runtime_after"]["open_question_count"] = False
    else:
        event = copy.deepcopy(event)
        event["runtime_after"]["open_question_count"] = False
    prepared = start.PreparedProjection(
        transport=SimpleNamespace(
            root=Path("/synthetic"),
            projected=projected,
            projected_raw=projected_raw,
            event=event,
        ),
        checkpoint_path=Path("/synthetic/checkpoint.json"),
        event_id=start.EVENT_ID,
        context=SimpleNamespace(),
        evidence=start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00"),
    )
    with pytest.raises(start.StartApplyError, match=expected_error):
        start._fresh_exact_projected_checkpoint(prepared, projected_raw)


def test_managed_cohort_is_resealed_after_projected_consumers(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []
    projected, event, projected_raw = _minimal_projected_checkpoint()
    retained = {Path("receipt.json"): SimpleNamespace(identity="id", raw=b"receipt")}
    context = SimpleNamespace()
    evidence = start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00")
    transport = SimpleNamespace(
        root=tmp_path,
        source_raw=b"{}\n",
        source_identity=SimpleNamespace(),
        source={},
        projected=projected,
        projected_raw=projected_raw,
        event=event,
        retained_inputs=retained,
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=b"",
        git_head="head",
        git_branch="branch",
    )
    prepared = start.PreparedProjection(
        transport=transport,
        checkpoint_path=tmp_path / start.CHECKPOINT_RELATIVE,
        event_id=start.EVENT_ID,
        context=context,
        evidence=evidence,
    )

    monkeypatch.setattr(start, "_capture_gate_inputs", lambda *_args: retained)
    monkeypatch.setattr(
        start.seq90,
        "_require_preflight_cohort_unchanged",
        lambda *_args, **_kwargs: calls.append("cohort"),
    )
    monkeypatch.setattr(start.gate, "load_gate_context", lambda *_args, **_kwargs: context)
    monkeypatch.setattr(start, "validate_gate_evidence", lambda *_args, **_kwargs: evidence)

    def projected_validator(_root: Path, candidate: dict[str, Any]) -> list[str]:
        calls.append("projected")
        candidate["goal_execution"]["transition_history"][-1]["event_id"] = "FORGED"
        return []

    start._require_prepared_exact(prepared, projected_validator=projected_validator)
    assert calls == ["cohort", "projected", "cohort"]
    assert transport.projected["goal_execution"]["transition_history"][-1] == event
    assert transport.projected_raw == start.json_bytes(transport.projected)


def test_zero_credit_boundary_distinguishes_bool_from_integer(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    source["approved_state"]["formal_test_count"] = True
    source["approved_state"]["formal_test_not_run_count"] = True
    with pytest.raises(start.StartApplyError, match="approved zero-credit"):
        start._require_zero_credit_source(source)
