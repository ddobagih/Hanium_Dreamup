from __future__ import annotations

import copy
from collections import Counter
from datetime import datetime, timedelta
import hashlib
import json
from pathlib import Path
import shutil
from typing import Any

import pytest

from scripts import apply_walksafe_fp048_r002_goal_seq88_89_20260825 as subject
from scripts import (
    apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824
    as correction
)
from scripts import apply_walksafe_fp046_r002_goal_started_seq79_20260824 as started
from scripts import (
    build_walksafe_fp046_r002_seq78_79_recovery_review_20260824 as recovery_review
)


ROOT = Path(__file__).resolve().parents[1]


def _binding(path: Path, raw: bytes, role: str, document_id: str) -> dict[str, Any]:
    return {
        "role": role,
        "document_id": document_id,
        "path": path.as_posix(),
        "file_sha256": subject.sha256_bytes(raw),
        "identity_json_path": "document_id",
        "mutable": False,
    }


def _runtime(state: dict[str, Any], focus_id: str, focus_path: str) -> dict[str, Any]:
    return {
        "focus_goal_id": focus_id,
        "focus_goal_path": focus_path,
        "focus_work_item_id": (
            subject.WORK_ITEM_ID if focus_id == subject.GOAL_ID else ""
        ),
        "focus_source": (
            subject.FOCUS_SOURCE
            if focus_id == subject.GOAL_ID
            else "WORKSTREAM_GRAPH"
        ),
        "ready_frontier_goal_ids": list(subject.SOURCE_READY_FRONTIER),
        "blocked_goal_ids": copy.deepcopy(state["blocked_goal_ids"]),
        "pending_questions": copy.deepcopy(state["pending_questions"]),
        "open_question_count": state["open_question_count"],
        "artifact_work_queue_sha256": subject.continuation.canonical_json_sha256(
            state["artifact_work_queue"]
        ),
        "completion_boundary_sha256": subject.continuation.canonical_json_sha256(
            state["completion_boundary"]
        ),
        "activation_status": state["activation_status"],
        "package_status": state["package_status"],
    }


def _synthetic_seq85(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    live = json.loads((ROOT / correction.CHECKPOINT_RELATIVE).read_bytes())
    source = json.loads(
        correction.reconstructed_seq83_checkpoint_bytes(
            ROOT, live["goal_execution"]["transition_history"][82]
        )
    )
    paths = correction.exact_seq84_managed_paths(source)
    source, _event84 = correction.project_seq84(
        source,
        managed_paths=paths,
        path_set_sha256=hashlib.sha256(
            ("\n".join(paths) + "\n").encode("utf-8")
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
                source["goal_execution"]["transition_history"][-1]["occurred_at"]
            )
            + timedelta(seconds=1)
        ).isoformat(),
        runner_binding={},
        failed_gate_binding=recovery_review.passed_gate_attempt_004_binding(ROOT),
    )
    monkeypatch.setattr(started, "require_exact_source", lambda *_a, **_k: None)
    receipt_relative = (
        started.gate.GATE_ROOT_RELATIVE / started.EVENT_ID / started.gate.RECEIPT_NAME
    )
    evidence = started.GateEvidence(
        receipt={"repository_snapshot": {"sealed": True}},
        receipt_bytes=b"{}\n",
        receipt_binding={
            "document_id": started.gate._recovery_document_id(started.EVENT_ID),
            "path": receipt_relative.as_posix(),
            "file_sha256": "9" * 64,
        },
        repository_payload={"sealed": True},
        event_occurred_at=(
            datetime.fromisoformat(
                source["goal_execution"]["transition_history"][-1]["occurred_at"]
            )
            + timedelta(seconds=1)
        ).isoformat(),
    )
    final_paths = {
        Path(path)
        for path in source["working_tree_snapshot"]["managed_changed_paths"]
    }
    final_paths.update({started.SCRIPT_RELATIVE, started.TEST_RELATIVE})
    source, _event85 = started.project_seq85(
        ROOT,
        source,
        evidence,
        event_id=started.EVENT_ID,
        final_sha256_by_path={path: "a" * 64 for path in final_paths},
    )
    assert len(source["goal_execution"]["transition_history"]) == 85
    return source


def _synthetic_seq87(
    monkeypatch: pytest.MonkeyPatch,
) -> tuple[bytes, dict[Path, bytes], dict[str, str]]:
    source = _synthetic_seq85(monkeypatch)
    state = source["goal_execution"]

    gap_path = Path(
        "docs/control/audits/walksafe-implementation-gap-analysis-20260825-r030.json"
    )
    backlog_path = Path(
        "docs/control/audits/"
        "walksafe-implementation-remediation-backlog-20260825-r030.json"
    )
    action = "FP-048 R002에서 7종 state rotation을 all-or-nothing으로 재개한다."
    gap = {
        "metadata": {"report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260825-030"},
        "assessments": [
            {
                "source_policy_id": subject.SOURCE_POLICY_ID,
                "gap_id": subject.GAP_ID,
                "status": "PARTIAL",
            }
        ],
    }
    backlog = {
        "metadata": {
            "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260825-030"
        },
        "next_single_action": {
            "action": action,
            "epic_id": "EPIC-03",
            "gap_id": subject.GAP_ID,
            "priority_rank": 23,
            "source_policy_id": subject.SOURCE_POLICY_ID,
            "status": "PLANNED_NEXT",
            "goal_id": subject.GOAL_ID,
            "work_item_id": subject.WORK_ITEM_ID,
        },
    }
    gap_raw = subject.canonical_json_bytes(gap)
    backlog_raw = subject.canonical_json_bytes(backlog)
    completion_role = f"WORK_ITEM_COMPLETION::{subject.PREDECESSOR_GOAL_ID}"
    completion_raw = b'{"document_id":"synthetic-fp046-r002-completion"}\n'
    completion_path = Path(
        "docs/control/execution/goal-results/"
        f"{subject.PREDECESSOR_GOAL_ID}/completion-receipt.json"
    )
    replacements = {
        "IMPLEMENTATION_GAP": _binding(
            gap_path, gap_raw, "IMPLEMENTATION_GAP", gap["metadata"]["report_id"]
        ),
        "IMPLEMENTATION_BACKLOG": _binding(
            backlog_path,
            backlog_raw,
            "IMPLEMENTATION_BACKLOG",
            backlog["metadata"]["backlog_id"],
        ),
    }
    source["canonical_bindings"] = [
        copy.deepcopy(replacements.get(row["role"], row))
        for row in source["canonical_bindings"]
    ]
    source["canonical_bindings"].append(
        _binding(
            completion_path,
            completion_raw,
            completion_role,
            "synthetic-fp046-r002-completion",
        )
    )
    source["canonical_bindings"] = sorted(
        source["canonical_bindings"], key=lambda row: row["role"]
    )
    canonical = subject.continuation.canonical_binding_snapshot(source)

    previous = state["transition_history"][-1]
    occurred = datetime.fromisoformat(previous["occurred_at"])
    r001_completion = next(
        event["event_sha256"]
        for event in state["transition_history"]
        if event.get("event_type") == "GOAL_COMPLETED"
        and event.get("subject_goal_id") == subject.SUPERSEDED_GOAL_ID
    )
    update = {
        "sequence": 86,
        "event_id": "WS-SYNTHETIC-SEQ86-FP046-R002",
        "event_type": "CANONICAL_BINDINGS_UPDATED",
        "occurred_on": (occurred + timedelta(seconds=1)).date().isoformat(),
        "occurred_at": (occurred + timedelta(seconds=1)).isoformat(),
        "previous_focus_goal_id": subject.PREDECESSOR_GOAL_ID,
        "previous_focus_content_sha256": previous["focus_goal_content_sha256"],
        "focus_goal_id": subject.PREDECESSOR_GOAL_ID,
        "focus_goal_content_sha256": previous["focus_goal_content_sha256"],
        "from_status": "IN_PROGRESS",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
        "status_changes": {},
        "runtime_after": copy.deepcopy(previous["runtime_after"]),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"],
        "previous_event_sha256": previous["event_sha256"],
        "produced_by_goal_id": subject.PREDECESSOR_GOAL_ID,
        "produced_binding_roles": ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"],
        "producer_completion_receipt_binding": canonical[completion_role],
        "changed_binding_roles": ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"],
        "changed_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-046", subject.SOURCE_POLICY_ID],
            "IMPLEMENTATION_GAP": [
                "FP-046",
                subject.SOURCE_POLICY_ID,
                "GAP-055",
                subject.GAP_ID,
            ],
        },
        "producer_output_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-046", subject.SOURCE_POLICY_ID],
            "IMPLEMENTATION_GAP": [
                "FP-046",
                subject.SOURCE_POLICY_ID,
                "GAP-055",
                subject.GAP_ID,
            ],
        },
        "impact_closure_goal_ids": [subject.PARENT_GOAL_ID, subject.SUPERSEDED_GOAL_ID],
        "impact_disposition_by_goal": {
            subject.PARENT_GOAL_ID: {
                "result": "REVALIDATION_REFRESH_REQUIRED",
                "target_status": "READY",
            },
            subject.SUPERSEDED_GOAL_ID: {
                "result": "REOPEN_REQUIRED",
                "target_status": "SUPERSEDED",
            },
        },
        "reopened_completion_event_sha256_by_goal": {
            subject.SUPERSEDED_GOAL_ID: r001_completion
        },
        "canonical_binding_snapshot_after": canonical,
    }
    update["event_sha256"] = subject.continuation.event_sha256(update)
    parent = subject._goal_record(state, subject.PARENT_GOAL_ID)
    completion = {
        "sequence": 87,
        "event_id": "WS-SYNTHETIC-SEQ87-FP046-R002",
        "event_type": "GOAL_COMPLETED",
        "occurred_on": (occurred + timedelta(seconds=2)).date().isoformat(),
        "occurred_at": (occurred + timedelta(seconds=2)).isoformat(),
        "previous_focus_goal_id": subject.PREDECESSOR_GOAL_ID,
        "previous_focus_content_sha256": previous["focus_goal_content_sha256"],
        "focus_goal_id": subject.PARENT_GOAL_ID,
        "focus_goal_content_sha256": parent["sha256"],
        "subject_goal_id": subject.PREDECESSOR_GOAL_ID,
        "from_status": "IN_PROGRESS",
        "to_status": "COMPLETE_AT_TARGET",
        "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
        "status_changes": {subject.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET"},
        "runtime_after": _runtime(state, subject.PARENT_GOAL_ID, parent["path"]),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [completion_role],
        "previous_event_sha256": update["event_sha256"],
        "canonical_update_event_sha256": update["event_sha256"],
        "canonical_binding_snapshot_after": canonical,
    }
    completion["event_sha256"] = subject.continuation.event_sha256(completion)
    state["transition_history"].extend([update, completion])
    state["transition_history_anchor_sha256"] = completion["event_sha256"]
    state["validation_cutoff_at"] = completion["occurred_at"]
    state["status_by_goal"][subject.PREDECESSOR_GOAL_ID] = "COMPLETE_AT_TARGET"
    state["focus_goal_id"] = subject.PARENT_GOAL_ID
    state["focus_goal_path"] = parent["path"]
    state["focus_work_item_id"] = ""
    state["focus_source"] = "WORKSTREAM_GRAPH"
    state["ready_frontier_goal_ids"] = list(subject.SOURCE_READY_FRONTIER)
    state["pending_producer_completion_goal_id"] = ""
    state["completion_evidence_by_goal"][subject.PREDECESSOR_GOAL_ID] = [
        completion_role
    ]
    source_raw = subject.checkpoint_json_bytes(source)
    overlay = {gap_path: gap_raw, backlog_path: backlog_raw}
    pins = {
        "source_checkpoint_sha256": subject.sha256_bytes(source_raw),
        "source_r030_gap_sha256": subject.sha256_bytes(gap_raw),
        "source_r030_backlog_sha256": subject.sha256_bytes(backlog_raw),
    }
    return source_raw, overlay, pins


def _prepared(monkeypatch: pytest.MonkeyPatch) -> subject.PreparedProjection:
    raw, overlay, pins = _synthetic_seq87(monkeypatch)
    return subject.prepare(ROOT, source_bytes=raw, source_overlay=overlay, **pins)


def test_synthetic_seq87_projects_exact_superseded_and_ready_pair(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared(monkeypatch)
    seq88, seq89 = prepared.materialized_event, prepared.ready_event
    assert seq88["sequence"] == subject.MATERIALIZED_SEQUENCE
    assert seq88["event_id"] == subject.SUPERSEDED_EVENT_ID
    assert seq88["event_type"] == "GOAL_SUPERSEDED"
    assert seq88["status_changes"] == {
        subject.SUPERSEDED_GOAL_ID: "SUPERSEDED",
        subject.GOAL_ID: "PLANNED",
    }
    assert seq88["reopen_trigger"]["canonical_update_event_sha256"] == (
        prepared.source["goal_execution"]["transition_history"][85][
            "event_sha256"
        ]
    )
    assert seq88["evidence_refs"] == []
    assert subject.SUPERSEDED_GOAL_ID not in seq88[
        "completion_evidence_by_goal_after"
    ]
    assert subject.SUPERSEDED_GOAL_ID in seq88[
        "archived_completion_evidence_by_goal_after"
    ]
    assert seq89["sequence"] == subject.READY_SEQUENCE
    assert seq89["event_type"] == "GOAL_READY"
    assert seq89["status_changes"] == {subject.GOAL_ID: "READY"}
    assert seq89["readiness_basis"]["dependency_completion_events"] == [
        {
            "goal_id": subject.DEPENDENCY_GOAL_ID,
            "event_sha256": subject._completion_event_sha256(
                prepared.source["goal_execution"]["transition_history"],
                subject.DEPENDENCY_GOAL_ID,
            ),
        }
    ]
    assert prepared.projected["goal_execution"]["focus_work_item_id"] == (
        subject.WORK_ITEM_ID
    )


def test_strict_continuation_rejects_sorted_historical_checkpoint_rewrite(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw, overlay, pins = _synthetic_seq87(monkeypatch)
    source = subject.strict_json(raw, "source")
    assert subject.checkpoint_json_bytes(source) == raw

    sorted_raw = subject.canonical_json_bytes(source)
    assert sorted_raw != raw
    assert subject.strict_json(sorted_raw, "sorted source") == source
    with pytest.raises(
        subject.ProjectionError,
        match="source checkpoint sorted-key historical rewrite differs",
    ):
        subject.prepare(
            ROOT,
            source_bytes=sorted_raw,
            source_overlay=overlay,
            **{
                **pins,
                "source_checkpoint_sha256": subject.sha256_bytes(sorted_raw),
            },
        )

    prepared = subject.prepare(
        ROOT, source_bytes=raw, source_overlay=overlay, **pins
    )
    assert prepared.projected_bytes == subject.checkpoint_json_bytes(
        prepared.projected
    )
    source_history = source["goal_execution"]["transition_history"]
    projected_history = prepared.projected["goal_execution"]["transition_history"]
    assert [list(event) for event in projected_history[:87]] == [
        list(event) for event in source_history
    ]


def test_goal_and_contract_are_dynamic_add_only_staged_outputs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared(monkeypatch)
    assert set(prepared.staged_outputs) == {subject.GOAL_PATH, subject.CONTRACT_PATH}
    assert not (ROOT / subject.GOAL_PATH).exists()
    assert not (ROOT / subject.CONTRACT_PATH).exists()
    goal, body = subject._parse_goal(
        prepared.staged_outputs[subject.GOAL_PATH], subject.GOAL_ID
    )
    assert goal["goal_id"] == subject.GOAL_ID
    assert goal["work_item_id"] == subject.WORK_ITEM_ID
    assert goal["start_requires"] == [subject.DEPENDENCY_GOAL_ID]
    assert goal["completion_requires"] == [subject.DEPENDENCY_GOAL_ID]
    assert goal["predecessor_goal_id"] == subject.PREDECESSOR_GOAL_ID
    assert goal["supersedes_goal_id"] == subject.SUPERSEDED_GOAL_ID
    assert goal["reopen_reason"] == "CANONICAL_INPUT_CHANGED"
    assert goal["reopen_evidence_refs"] == []
    assert "short-session" in body and "server-capacity" in body
    assert "crash-atomic 7파일 cohort rollback claim" in body
    contract = subject.strict_json(
        prepared.staged_outputs[subject.CONTRACT_PATH], "contract"
    )
    assert set(contract) == {
        "schema_version",
        "document_id",
        "contract_id",
        "contract_version",
        "target_goal_id",
        "target_goal_content_sha256",
        "gate_purpose",
        "ordered_checks",
    }
    assert tuple(row["check_id"] for row in contract["ordered_checks"]) == (
        subject.CONTRACT_CHECK_IDS
    )
    assert all(set(row) == {"check_id", "command"} for row in contract["ordered_checks"])
    assert {
        row["check_id"]: row["command"] for row in contract["ordered_checks"]
    } == subject.CONTRACT_COMMANDS
    assert contract["target_goal_content_sha256"] == subject.goal_binding_from_checkpoint(
        prepared.projected
    )["sha256"]


def test_explicit_source_pins_and_r030_next_action_fail_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw, overlay, pins = _synthetic_seq87(monkeypatch)
    with pytest.raises(subject.ProjectionError, match="checkpoint explicit SHA-256"):
        subject.prepare(
            ROOT,
            source_bytes=raw,
            source_overlay=overlay,
            **{**pins, "source_checkpoint_sha256": "0" * 64},
        )
    with pytest.raises(subject.ProjectionError, match="R030 explicit SHA-256"):
        subject.prepare(
            ROOT,
            source_bytes=raw,
            source_overlay=overlay,
            **{**pins, "source_r030_gap_sha256": "0" * 64},
        )
    backlog_path = next(path for path in overlay if "backlog" in path.name)
    tampered_overlay = dict(overlay)
    backlog = subject.strict_json(overlay[backlog_path], "backlog")
    backlog["next_single_action"]["gap_id"] = "GAP-999"
    tampered_overlay[backlog_path] = subject.canonical_json_bytes(backlog)
    with pytest.raises(subject.ProjectionError, match="live binding differs"):
        subject.prepare(
            ROOT,
            source_bytes=raw,
            source_overlay=tampered_overlay,
            **pins,
        )


def test_live_preflight_and_publication_modes_are_zero_write() -> None:
    checkpoint = ROOT / subject.CHECKPOINT_PATH
    before = checkpoint.read_bytes()
    state = json.loads(before)["goal_execution"]
    targets = [ROOT / subject.GOAL_PATH, ROOT / subject.CONTRACT_PATH]
    assert all(not path.exists() for path in targets)
    canonical = subject.continuation.canonical_binding_snapshot(json.loads(before))
    args = [
        "--root",
        str(ROOT),
        "--source-checkpoint-sha256",
        subject.sha256_bytes(before),
        "--source-r030-gap-sha256",
        str(canonical["IMPLEMENTATION_GAP"]["file_sha256"]),
        "--source-r030-backlog-sha256",
        str(canonical["IMPLEMENTATION_BACKLOG"]["file_sha256"]),
    ]
    assert subject.main([*args, "--write"]) == 2
    if len(state["transition_history"]) == 85:
        assert subject.main([*args, "--preflight"]) == 1
    assert checkpoint.read_bytes() == before
    assert all(not path.exists() for path in targets)


def test_gate_facing_ready_source_trust_anchor_rejects_transition_tamper(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared(monkeypatch)
    subject._require_exact_ready_projection(
        ROOT, prepared.projected, prepared.staged_outputs
    )
    tampered = copy.deepcopy(prepared.projected)
    tampered["goal_execution"]["transition_history"][87][
        "canonical_update_event_sha256"
    ] = "0" * 64
    with pytest.raises(subject.ProjectionError):
        subject._require_exact_ready_projection(
            ROOT, tampered, prepared.staged_outputs
        )


def test_real_seq87_post_cas_candidate_passes_strict_continuation(
    tmp_path: Path,
) -> None:
    from scripts import (
        publish_walksafe_fp048_r002_goal_seq88_89_20260825 as publisher,
    )

    raw = (ROOT / subject.CHECKPOINT_PATH).read_bytes()
    source = subject.strict_json(raw, "live seq87")
    assert len(source["goal_execution"]["transition_history"]) == 87
    canonical = subject.continuation.canonical_binding_snapshot(source)
    pins = {
        "source_checkpoint_sha256": subject.sha256_bytes(raw),
        "source_r030_gap_sha256": canonical["IMPLEMENTATION_GAP"][
            "file_sha256"
        ],
        "source_r030_backlog_sha256": canonical["IMPLEMENTATION_BACKLOG"][
            "file_sha256"
        ],
    }
    projection0 = subject.prepare(ROOT, source_bytes=raw, **pins)
    source_universe = publisher.catalogs.discover_source_paths(ROOT)
    final_universe = tuple(
        sorted(
            set(source_universe)
            | {path.as_posix() for path in publisher.ADD_ONLY_PATHS}
        )
    )
    candidate_catalogs = publisher._build_candidate_catalogs(
        ROOT,
        final_universe,
        projection0.projected,
        catalog_builder=publisher.catalogs.build_catalog_bytes,
    )
    projection = subject.prepare(
        ROOT,
        source_bytes=raw,
        source_overlay=candidate_catalogs,
        **pins,
    )
    assert candidate_catalogs == publisher._build_candidate_catalogs(
        ROOT,
        final_universe,
        projection.projected,
        catalog_builder=publisher.catalogs.build_catalog_bytes,
    )

    shutil.copystat(ROOT, tmp_path)

    def ensure_parent(relative: Path) -> None:
        cursor = Path()
        for part in relative.parent.parts:
            cursor /= part
            target_directory = tmp_path / cursor
            if not target_directory.exists():
                target_directory.mkdir()
                shutil.copystat(ROOT / cursor, target_directory)

    for path_text in source_universe:
        source_path = ROOT / path_text
        target_path = tmp_path / path_text
        ensure_parent(Path(path_text))
        shutil.copy2(source_path, target_path)
    shutil.copytree(ROOT / ".git", tmp_path / ".git", copy_function=shutil.copy2)
    artifacts = {
        **projection.staged_outputs,
        **candidate_catalogs,
        subject.CHECKPOINT_PATH: projection.projected_bytes,
    }
    for relative, content in artifacts.items():
        target = tmp_path / relative
        ensure_parent(relative)
        target.write_bytes(content)
        target.chmod(0o600)

    assert publisher.run_continuation_checker(
        tmp_path, subject.CHECKPOINT_PATH
    ) == []
    source_errors = Counter(
        publisher.run_goal_graph_checker(ROOT, subject.CHECKPOINT_PATH)
    )
    candidate_errors = Counter(
        publisher.run_goal_graph_checker(tmp_path, subject.CHECKPOINT_PATH)
    )
    assert candidate_errors - source_errors == Counter()
