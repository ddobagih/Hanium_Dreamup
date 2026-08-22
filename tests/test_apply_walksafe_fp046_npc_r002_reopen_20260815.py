from __future__ import annotations

from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest

from scripts import apply_walksafe_fp046_npc_r002_reopen_20260815 as subject


ROOT = Path(__file__).resolve().parents[1]


def _write(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)


def _copy_source(root: Path) -> None:
    paths = (
        subject.CHECKPOINT_REL,
        *subject.R028_PATHS,
        subject.FP046_R001_REL,
        subject.NPC_R001_REL,
        subject.FP022_R001_REL,
        subject.EPIC03_REL,
        *subject.r029_candidate.CURRENT_SOURCE_PATHS,
    )
    for path in paths:
        _write(root, path, (ROOT / path).read_bytes())


def _r029(root: Path) -> None:
    for path, text in subject.r029_candidate.build_outputs(root).items():
        _write(root, path, text.encode())


def _fixture(root: Path) -> None:
    _copy_source(root)
    _r029(root)


def _snapshot(root: Path) -> dict[str, bytes]:
    return {
        path.relative_to(root).as_posix(): path.read_bytes()
        for path in root.rglob("*")
        if path.is_file()
    }


def _build(root: Path) -> dict:
    _fixture(root)
    return subject.build_preflight(root)


def test_preflight_builds_exact_five_events_and_two_r002_docs_without_writing(
    tmp_path: Path,
) -> None:
    _fixture(tmp_path)
    before = _snapshot(tmp_path)

    plan = subject.build_preflight(tmp_path)
    cbu, fp046, npc, epic03_ready, fp046_ready = plan["events"]

    assert plan["transaction_status"] == "PREFLIGHT_ONLY_NOT_AUTHORIZED"
    assert plan["final_state_projection_only"] is True
    assert [event["sequence"] for event in plan["events"]] == [72, 73, 74, 75, 76]
    assert [event["event_type"] for event in plan["events"]] == [
        "CANONICAL_BINDINGS_UPDATED",
        "GOAL_SUPERSEDED",
        "GOAL_SUPERSEDED",
        "GOAL_READY",
        "GOAL_READY",
    ]
    assert cbu["status_changes"] == {subject.EPIC03: "PLANNED"}
    assert cbu["changed_subject_ids_by_role"] == {
        "IMPLEMENTATION_BACKLOG": [subject.FP046_POLICY_ID],
        "IMPLEMENTATION_GAP": [subject.FP046_POLICY_ID, subject.FP046_GAP_ID],
    }
    assert cbu["impact_closure_goal_ids"] == [
        subject.EPIC03,
        subject.FP046_R001,
        subject.NPC_R001,
    ]
    assert cbu["impact_disposition_by_goal"] == {
        subject.FP046_R001: {"result": "REOPEN_REQUIRED"},
        subject.NPC_R001: {"result": "REOPEN_REQUIRED"},
        subject.EPIC03: {
            "result": "REOPEN_CONTAINER",
            "target_status": "PLANNED",
        },
    }
    assert fp046["status_changes"] == {
        subject.FP046_R001: "SUPERSEDED",
        subject.FP046_R002: "PLANNED",
    }
    assert npc["status_changes"] == {
        subject.NPC_R001: "SUPERSEDED",
        subject.NPC_R002: "PLANNED",
    }
    assert all(
        "canonical_update_event_sha256" not in event
        and not {
            "target_completion_event_sha256",
            "target_completion_occurred_at",
            "decided_at",
        }.intersection(event)
        and event["reopen_trigger"]["canonical_update_event_sha256"] == cbu["event_sha256"]
        and event["materialized_from_role"] == "IMPLEMENTATION_BACKLOG"
        for event in plan["events"][1:3]
    )
    assert epic03_ready["subject_goal_id"] == subject.EPIC03
    assert fp046_ready["subject_goal_id"] == subject.FP046_R002
    assert epic03_ready["readiness_basis"] == {
        "mode": "CANONICAL_DEPENDENCY_CLOSURE_REOPEN",
        "canonical_update_event_sha256": cbu["event_sha256"],
        "successor_event_sha256_by_goal": {
            subject.FP046_R002: fp046["event_sha256"],
            subject.NPC_R002: npc["event_sha256"],
        },
        "archived_completion_event_sha256": cbu[
            "reopened_completion_event_sha256_by_goal"
        ][subject.EPIC03],
    }
    assert fp046_ready["reopened_container_ready_event_sha256"] == epic03_ready["event_sha256"]
    assert set(plan["documents"]) == {
        subject.FP046_R002_REL.as_posix(),
        subject.NPC_R002_REL.as_posix(),
    }
    assert cbu["source_bindings"]["last_completed_execution_leaf"] == {
        "goal_id": subject.FP022_R001,
        "path": subject.FP022_R001_REL.as_posix(),
        "sha256": subject.bytes_sha256((ROOT / subject.FP022_R001_REL).read_bytes()),
        "completion_event_sha256": cbu["previous_event_sha256"],
        "completion_evidence_refs": [
            "WORK_ITEM_COMPLETION::WS-GOAL-EPIC-04-FP-022-R001"
        ],
        "current_focus_goal_id": subject.EPIC04,
        "current_focus_is_workstream": True,
        "current_focus_work_item_id": "",
    }
    assert plan["required_before_apply"] == list(
        subject.r029_candidate.OPERATIONAL_APPLICATION_PREREQUISITES
    )
    assert cbu["source_bindings"][
        "regression_trigger_discovery"
    ]["role"] == "REGRESSION_TRIGGER_DISCOVERY_CANDIDATE"

    fp046, _ = subject._goal_parts(
        plan["documents"][subject.FP046_R002_REL.as_posix()].encode(), "FP046 R002"
    )
    npc, _ = subject._goal_parts(
        plan["documents"][subject.NPC_R002_REL.as_posix()].encode(), "NPC R002"
    )
    assert fp046["predecessor_goal_id"] == subject.FP022_R001
    assert npc["predecessor_goal_id"] == subject.FP022_R001
    assert npc["start_requires"] == [subject.FP046_R002]
    assert npc["completion_requires"] == [subject.FP046_R002]
    assert plan["final_state"]["archived_completion_evidence_by_goal"].keys() >= {
        subject.EPIC03,
        subject.FP046_R001,
        subject.NPC_R001,
    }
    assert {
        subject.FP046_R001,
        subject.NPC_R001,
        subject.FP046_R002,
        subject.NPC_R002,
    }.issubset(
        plan["final_state"]["materialized_child_goal_ids_by_parent"][
            subject.EPIC03
        ]
    )
    assert "dynamic_goal_inventory_after" not in fp046
    assert "dynamic_goal_inventory_after" not in npc
    assert epic03_ready["dynamic_goal_inventory_after"] == plan["final_state"]["dynamic_goal_inventory"]
    assert epic03_ready["materialized_child_goal_ids_by_parent_after"] == plan["final_state"]["materialized_child_goal_ids_by_parent"]
    assert before == _snapshot(tmp_path)
    subject.validate_preflight(tmp_path, plan)


def test_apply_guard_fails_closed_and_never_writes(tmp_path: Path) -> None:
    _fixture(tmp_path)
    before = _snapshot(tmp_path)

    assert subject.main(["--root", str(tmp_path)]) == 0
    assert subject.main(["--root", str(tmp_path), "--apply"]) == 1
    assert before == _snapshot(tmp_path)


def test_transition_package_is_read_only_and_apply_stays_in_actual_writer(
    tmp_path: Path,
) -> None:
    _copy_source(tmp_path)
    for path in subject.R007_REVIEW_PINS:
        _write(tmp_path, path, (ROOT / path).read_bytes())
    before = _snapshot(tmp_path)

    assert subject.main(["--root", str(tmp_path), "--print-transition-package"]) == 0
    assert subject.main(["--root", str(tmp_path), "--apply"]) == 1
    assert before == _snapshot(tmp_path)


def test_authorization_uses_exact_latest_user_instruction_and_stops_before_seq77(
) -> None:
    expected_quote = "계획 세워서 단계적으로 진행해 어떻게 진행해야 하는지 알지?"

    authorization = subject._authorization_document()

    assert subject.USER_AUTHORIZATION_QUOTE == expected_quote
    assert authorization["authorization_quote"] == expected_quote
    assert authorization["authorization_status"] == "AUTHORIZED_FOR_SEQ72_76_ONLY"
    assert authorization["sequence_77_status"] == "NOT_AUTHORIZED"
    assert {
        "SEQ77_GOAL_STARTED",
        "PRODUCT_CODE_CHANGE",
    }.issubset(authorization["authorization_scope"]["excluded_actions"])


def test_transition_review_binds_separate_reviewer_and_review_time(
    tmp_path: Path,
) -> None:
    _copy_source(tmp_path)
    for path in subject.R007_REVIEW_PINS:
        _write(tmp_path, path, (ROOT / path).read_bytes())
    assignment_raw = subject.build_transition_assignment(
        tmp_path,
        assigned_at="2026-08-15T12:00:00+09:00",
    ).encode("utf-8")
    assignment = json.loads(assignment_raw)
    result = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP046_NPC_R002_REOPEN_TRANSITION_REVIEWER_AUTHORED_RESULT"
        ),
        "goal_id": subject.TRANSITION_GOAL_ID,
        "round_id": subject.TRANSITION_ROUND_ID,
        "reviewed_at": "2026-08-15T12:01:00+09:00",
        "reviewer": deepcopy(assignment["reviewer"]),
        "assignment_binding": subject._binding(
            subject.TRANSITION_ASSIGNMENT_REL,
            assignment_raw,
        ),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_scope": deepcopy(assignment["review_scope"]),
        "review_boundary": deepcopy(assignment["review_boundary"]),
    }
    result_raw = subject.json_text(result).encode("utf-8")

    subject._validate_transition_result_document(
        result,
        result_raw,
        assignment,
        assignment_raw,
    )
    independent = json.loads(
        subject.build_transition_independent_review(
            assignment,
            assignment_raw,
            result,
            result_raw,
        )
    )
    assert independent["reviewer"] == assignment["reviewer"]
    assert independent["reviewed_at"] == result["reviewed_at"]

    for field, value, message in (
        ("reviewer", assignment["executor"], "reviewer identity differs"),
        ("reviewed_at", "2026-08-15T11:59:59+09:00", "predates assignment"),
    ):
        changed = deepcopy(result)
        changed[field] = value
        with pytest.raises(subject.BuildError, match=message):
            subject._validate_transition_result_document(
                changed,
                subject.json_text(changed).encode("utf-8"),
                assignment,
                assignment_raw,
            )


def test_transition_tool_has_no_reviewer_result_writer() -> None:
    assert not hasattr(subject, "write_transition_review_result")
    with pytest.raises(SystemExit):
        subject.main(["--write-transition-review-result"])


def test_plain_subprocess_does_not_write_pyc_in_isolated_copy(
    tmp_path: Path,
) -> None:
    isolated = tmp_path / "isolated"
    for relative in (
        Path("scripts/apply_walksafe_fp046_npc_r002_reopen_20260815.py"),
            Path("scripts/build_walksafe_fp008_admin_review_delivery_trace_20260803.py"),
            Path("scripts/build_walksafe_fp046_gap_backlog_r029_candidate_20260815.py"),
            Path("scripts/build_walksafe_fp046_gap_backlog_r029_20260815.py"),
        ):
        _write(isolated, relative, (ROOT / relative).read_bytes())
    _fixture(isolated)
    environment = os.environ.copy()
    for key in ("PYTHONDONTWRITEBYTECODE", "PYTHONPYCACHEPREFIX", "PYTHONPATH"):
        environment.pop(key, None)

    result = subprocess.run(
        [
            sys.executable,
            str(
                isolated
                / "scripts/apply_walksafe_fp046_npc_r002_reopen_20260815.py"
            ),
            "--root",
            str(isolated),
        ],
        cwd=isolated,
        env=environment,
        text=True,
        capture_output=True,
        check=False,
    )

    assert result.returncode == 0, result.stderr
    assert not list(isolated.rglob("*.pyc"))


def test_r029_absence_fails_closed(tmp_path: Path) -> None:
    _fixture(tmp_path)
    (tmp_path / subject.R029_BACKLOG_JSON_REL).unlink()

    with pytest.raises(subject.BuildError, match="required input missing"):
        subject.build_preflight(tmp_path)


@pytest.mark.parametrize(
    "mutate",
    [
        pytest.param(
            lambda plan: plan["documents"].pop(
                subject.NPC_R002_REL.as_posix()
            ),
            id="one-successor",
        ),
        pytest.param(
            lambda plan: plan["events"].__setitem__(
                slice(1, 3), [plan["events"][2], plan["events"][1]]
            ),
            id="reversed-supersession-order",
        ),
        pytest.param(
            lambda plan: plan["documents"].__setitem__(
                subject.NPC_R002_REL.as_posix(),
                plan["documents"][subject.NPC_R002_REL.as_posix()].replace(
                    subject.FP046_R002, subject.FP046_R001
                ),
            ),
            id="stale-npc-dependency",
        ),
        pytest.param(
            lambda plan: plan["events"][0]["status_changes"].__setitem__(
                subject.EPIC03, "READY"
            ),
            id="wrong-epic03-ready-in-cbu",
        ),
        pytest.param(
            lambda plan: plan["final_state"][
                "archived_completion_evidence_by_goal"
            ].pop(subject.NPC_R001),
            id="completion-archive-missing",
        ),
        pytest.param(
            lambda plan: plan["events"][0]["status_changes"].__setitem__(
                subject.FP046_R001, "PLANNED"
            ),
            id="extra-status",
        ),
        pytest.param(
            lambda plan: plan["events"].insert(2, deepcopy(plan["events"][1])),
            id="interleaving",
        ),
    ],
)
def test_strict_replay_rejects_invalid_transaction_shapes(
    tmp_path: Path, mutate
) -> None:
    plan = _build(tmp_path)
    mutate(plan)

    with pytest.raises(subject.BuildError):
        subject.validate_preflight(tmp_path, plan)


def test_stale_r028_source_fails_closed(tmp_path: Path) -> None:
    _fixture(tmp_path)
    target = tmp_path / subject.R028_GAP_JSON_REL
    target.write_bytes(target.read_bytes() + b" ")

    with pytest.raises(subject.BuildError, match="immutable R028 bytes differ"):
        subject.build_preflight(tmp_path)


def test_seq71_last_completed_leaf_sha_drift_fails_closed(tmp_path: Path) -> None:
    _fixture(tmp_path)
    target = tmp_path / subject.FP022_R001_REL
    target.write_bytes(target.read_bytes() + b"\nleaf drift\n")

    with pytest.raises(
        subject.BuildError,
        match="last completed leaf binding differs",
    ):
        subject.build_preflight(tmp_path)
