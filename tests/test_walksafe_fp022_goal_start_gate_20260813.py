from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest

from scripts import run_walksafe_fp022_goal_start_gate_20260813 as gate
from scripts import apply_walksafe_fp022_goal_seq66_67_20260813 as projector
from scripts import apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814 as reanchor
from scripts import check_walksafe_project_continuation_v2_4 as continuation


ROOT = Path(__file__).resolve().parents[1]
MATERIALIZED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-FP022-20260814-001"
)
READY_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP022-20260814-001"


def _event(payload: dict[str, object]) -> dict[str, object]:
    result = copy.deepcopy(payload)
    result["event_sha256"] = gate.event_sha256(result)
    return result


def test_context_loader_scopes_live_snapshot_override(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    observed: list[bool] = []
    monkeypatch.setattr(
        gate,
        "_base_load_checkpoint",
        lambda *_args, **_kwargs: (b"{}", {}),
    )
    monkeypatch.setattr(
        reanchor,
        "require_control_reanchored_checkpoint",
        lambda *_args, **kwargs: observed.append(kwargs["require_live_snapshot"]),
    )
    monkeypatch.setattr(
        gate._impl,
        "load_gate_context",
        lambda root, retained=None: gate._load_checkpoint(root)[0],
    )

    gate.load_gate_context(ROOT, require_live_snapshot=False)
    gate.load_gate_context(ROOT)

    assert observed == [False, True]


def _source() -> tuple[dict[str, object], str]:
    filler = [
        {
            "sequence": sequence,
            "event_id": f"HISTORICAL-{sequence:03d}",
            "event_type": "HISTORICAL_TEST_FIXTURE",
        }
        for sequence in range(1, gate.MATERIALIZED_SEQUENCE)
    ]
    materialized = _event(
        {
            "sequence": gate.MATERIALIZED_SEQUENCE,
            "event_id": MATERIALIZED_EVENT_ID,
            "event_type": "GOAL_MATERIALIZED",
            "occurred_at": "2026-08-13T23:59:00+09:00",
            "materialized_goal_id": gate.TARGET_GOAL_ID,
            "materialized_goal_path": gate.GOAL_RELATIVE.as_posix(),
            "materialized_goal_content_sha256": gate.TARGET_GOAL_SHA256,
            "predecessor_goal_id": gate.PREDECESSOR_GOAL_ID,
            "to_status": "PLANNED",
        }
    )
    ready = _event(
        {
            "sequence": gate.SOURCE_READY_SEQUENCE,
            "event_id": READY_EVENT_ID,
            "event_type": "GOAL_READY",
            "occurred_at": "2026-08-13T23:59:01+09:00",
            "subject_goal_id": gate.TARGET_GOAL_ID,
            "focus_goal_id": gate.TARGET_GOAL_ID,
            "focus_goal_content_sha256": gate.TARGET_GOAL_SHA256,
            "from_status": "PLANNED",
            "to_status": "READY",
            "previous_event_sha256": materialized["event_sha256"],
            "readiness_basis": {
                "dependency_completion_events": [
                    {
                        "goal_id": gate.DEPENDENCY_GOAL_ID,
                        "event_sha256": (
                            gate.DEPENDENCY_COMPLETION_EVENT_SHA256
                        ),
                    }
                ],
                "predecessor_goal_id": gate.PREDECESSOR_GOAL_ID,
                "predecessor_completion_event_sha256": (
                    gate.PREDECESSOR_COMPLETION_EVENT_SHA256
                ),
            },
            "implementation_start_gate_contract_binding": (
                gate.R001_CONTRACT_BINDING
            ),
        }
    )
    ready_sha256 = str(ready["event_sha256"])
    control = _event(
        {
            "sequence": gate.CONTROL_REANCHOR_SEQUENCE,
            "event_id": gate.CONTROL_REANCHOR_EVENT_ID,
            "event_type": "GOAL_START_CONTROL_REANCHORED",
            "occurred_on": "2026-08-14",
            "occurred_at": reanchor.OCCURRED_AT,
            "previous_focus_goal_id": gate.TARGET_GOAL_ID,
            "previous_focus_content_sha256": gate.TARGET_GOAL_SHA256,
            "focus_goal_id": gate.TARGET_GOAL_ID,
            "focus_goal_content_sha256": gate.TARGET_GOAL_SHA256,
            "subject_goal_id": gate.TARGET_GOAL_ID,
            "from_status": "READY",
            "to_status": "READY",
            "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
            "status_changes": {},
            "runtime_after": {},
            "blockers_after": [],
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": "1.25.0",
            "evidence_refs": [],
            "source_checkpoint_binding": reanchor.source_checkpoint_binding(),
            "source_ready_event_binding": {
                "sequence": gate.SOURCE_READY_SEQUENCE,
                "event_id": READY_EVENT_ID,
                "event_sha256": ready_sha256,
                "goal_id": gate.TARGET_GOAL_ID,
                "status": "READY",
            },
            "contract_supersession": {
                "previous_contract_binding": reanchor.r001_contract_binding(),
                "replacement_contract_binding": gate.expected_contract_binding(),
                "reason_code": gate.SUCCESSOR_REASON_CODE,
            },
            "start_gate_runner_binding": {
                "path": gate.RUNNER_RELATIVE.as_posix(),
                "sha256": "a" * 64,
                "byte_length": 1,
            },
            "transition_control_review_binding": {},
            "repository_context_reanchor": {},
            "claim_boundary": copy.deepcopy(reanchor.CLAIM_BOUNDARY),
            "unchanged_control_projection": {},
            "canonical_binding_snapshot_after": {},
            "previous_event_sha256": ready_sha256,
        }
    )
    checkpoint: dict[str, object] = {
        "goal_execution": {
            "transition_history": [*filler, materialized, ready, control],
            "transition_history_anchor_sha256": control["event_sha256"],
            "package_id": gate.PACKAGE_ID,
            "package_status": "ACTIVE",
            "activation_status": "ACTIVE",
            "focus_goal_id": gate.TARGET_GOAL_ID,
            "focus_goal_path": gate.GOAL_RELATIVE.as_posix(),
            "focus_work_item_id": gate.WORK_ITEM_ID,
            "focus_source": "IMPLEMENTATION_BACKLOG",
            "ready_frontier_goal_ids": list(gate.READY_FRONTIER),
            "status_by_goal": {
                gate.TARGET_GOAL_ID: "READY",
                gate.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
                gate.DEPENDENCY_GOAL_ID: "COMPLETE_AT_TARGET",
            },
            "blockers_by_goal": {},
            "blocked_goal_ids": [],
            "pending_questions": [],
            "open_question_count": 0,
            "dynamic_goal_inventory": {
                gate.TARGET_GOAL_ID: {
                    "goal_id": gate.TARGET_GOAL_ID,
                    "path": gate.GOAL_RELATIVE.as_posix(),
                    "sha256": gate.TARGET_GOAL_SHA256,
                    "materialized_event_sha256": (
                        materialized["event_sha256"]
                    ),
                    "predecessor_goal_id": gate.PREDECESSOR_GOAL_ID,
                }
            },
            "materialized_child_goal_ids_by_parent": {
                gate.PARENT_GOAL_ID: [gate.TARGET_GOAL_ID]
            },
        },
        "current_work": {
            "work_item_id": gate.WORK_ITEM_ID,
            "current_focus": gate.READY_CURRENT_FOCUS,
            "release_completion_claimed": False,
        },
    }
    return checkpoint, ready_sha256


def test_exact_private_contract_and_internal_scope() -> None:
    checks, contract = gate._load_gate_contract(ROOT)
    checkpoint = json.loads((ROOT / gate.CHECKPOINT_RELATIVE).read_bytes())

    assert contract == json.loads((ROOT / gate.CONTRACT_RELATIVE).read_bytes())
    history = checkpoint["goal_execution"]["transition_history"]
    expected_focus = (
        "FP-022/GAP-031 GOAL_STARTED/IN_PROGRESS; repository-internal TMAP "
        "destination route implementation authorized"
        if len(history) == 69
        else gate.READY_CURRENT_FOCUS
    )
    assert checkpoint["current_work"]["current_focus"] == expected_focus
    assert gate._impl.sha256_bytes((ROOT / gate.GOAL_RELATIVE).read_bytes()) == (
        gate.TARGET_GOAL_SHA256
    )
    assert tuple(check_id for check_id, _ in checks) == gate.EXPECTED_CHECK_IDS
    assert checks[-1][0] == "REPOSITORY_STATE"
    assert dict(checks)["CONTINUATION"].startswith("PYTHONPATH=. ")
    assert "PYTHONPATH=. " in dict(checks)["REPOSITORY_STATE"]
    assert "backend/tests/test_navigation_routes.py" in dict(checks)[
        "BACKEND_NAVIGATION_INTERNAL"
    ]
    android = dict(checks)["ANDROID_USER_INTERNAL"]
    assert (
        "GRADLE_USER_HOME=/home/ddobagi/.gradle ./gradlew --offline"
        in android
    )
    assert all(
        task in android
        for task in (
            ":app:testDebugUnitTest",
            ":app:assembleDebug",
            ":app:lintDebug",
        )
    )
    assert not any(
        fragment in command.lower()
        for _, command in checks
        for fragment in gate.FORBIDDEN_COMMAND_FRAGMENTS
    )


def test_runtime_bindings_are_android_user_only_and_base_guard_is_sealed() -> None:
    assert gate.RUNTIME_BINDING_RELATIVES == (
        Path("apps/android/gradle/wrapper/gradle-wrapper.properties"),
        Path("apps/android/gradle/wrapper/gradle-wrapper.jar"),
        Path("apps/android/gradle/verification-metadata.xml"),
        Path("apps/android/app/gradle.lockfile"),
    )
    assert gate._impl.RetainedRepositoryAuthorityGuard is (
        gate._impl.__dict__["RetainedRepositoryAuthorityGuard"]
    )
    assert "Fp046" not in gate._impl.RetainedRepositoryAuthorityGuard.__name__


def test_gate_rejects_a_symlinked_gradle_cache(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    real = tmp_path / "real-gradle"
    real.mkdir()
    linked = tmp_path / "linked-gradle"
    linked.symlink_to(real, target_is_directory=True)
    monkeypatch.setattr(gate, "GRADLE_USER_HOME", linked)
    with pytest.raises(gate.GateError, match="Gradle cache authority differs"):
        gate.run_gate(ROOT, "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20260814-999")


def test_event_identity_and_private_receipt_v11_contract() -> None:
    event_id = gate.STARTED_EVENT_ID
    assert gate._document_id(event_id) == (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP022-"
        "20260814-001"
    )
    assert {
        "source_checkpoint_sha256",
        "source_ready_event_sha256",
        "implementation_start_gate_contract_binding",
        "runtime_bindings",
    }.issubset(gate.RECEIPT_FIELDS)
    with pytest.raises(gate.GateError, match="event ID must match"):
        gate._document_id("FP022-INVALID")
    with pytest.raises(gate.GateError, match="event ID must equal"):
        gate._document_id(
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20991231-999"
        )


def test_unsealed_source_fails_closed(monkeypatch: pytest.MonkeyPatch) -> None:
    checkpoint, _ = _source()
    monkeypatch.setattr(gate, "MATERIALIZED_EVENT_ID", None)
    monkeypatch.setattr(gate, "READY_EVENT_ID", None)
    monkeypatch.setattr(gate, "SOURCE_READY_EVENT_SHA256", None)
    with pytest.raises(gate.GateError, match="event IDs are not sealed"):
        gate._validate_ready_source(
            checkpoint,
            contract_binding=gate.expected_contract_binding(),
        )


def test_injected_seq67_trust_anchor_validates_exact_ready_source() -> None:
    checkpoint, ready_sha256 = _source()
    actual, occurred_at = gate._validate_ready_source(
        checkpoint,
        contract_binding=gate.expected_contract_binding(),
        expected_materialized_event_id=MATERIALIZED_EVENT_ID,
        expected_ready_event_id=READY_EVENT_ID,
        expected_ready_event_sha256=ready_sha256,
    )

    assert actual == ready_sha256
    assert occurred_at.isoformat() == "2026-08-13T23:59:01+09:00"


def test_seq67_validation_rejects_credit_or_lineage_changes() -> None:
    checkpoint, ready_sha256 = _source()
    checkpoint["current_work"]["release_completion_claimed"] = True  # type: ignore[index]
    with pytest.raises(gate.GateError, match="current-work READY pointer"):
        gate._validate_ready_source(
            checkpoint,
            contract_binding=gate.expected_contract_binding(),
            expected_materialized_event_id=MATERIALIZED_EVENT_ID,
            expected_ready_event_id=READY_EVENT_ID,
            expected_ready_event_sha256=ready_sha256,
        )

    checkpoint, ready_sha256 = _source()
    checkpoint["goal_execution"]["transition_history"][-2][  # type: ignore[index]
        "readiness_basis"
    ]["dependency_completion_events"][0]["event_sha256"] = "0" * 64
    with pytest.raises(gate.GateError, match="readiness event differs"):
        gate._validate_ready_source(
            checkpoint,
            contract_binding=gate.expected_contract_binding(),
            expected_materialized_event_id=MATERIALIZED_EVENT_ID,
            expected_ready_event_id=READY_EVENT_ID,
            expected_ready_event_sha256=ready_sha256,
        )


def test_continuation_checker_accepts_only_the_exact_fp022_contract(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw = (ROOT / projector.CHECKPOINT_REL).read_bytes()
    checkpoint = json.loads(raw)
    history = checkpoint["goal_execution"]["transition_history"]
    if len(history) == reanchor.SEQUENCE + 1:
        assert history[-1]["event_id"] == gate.STARTED_EVENT_ID
    elif len(history) == reanchor.SEQUENCE:
        assert history[-1]["event_id"] == reanchor.EVENT_ID
    else:
        assert len(history) == reanchor.SOURCE_SEQUENCE
        final_sha256_by_path = reanchor.aggregate._snapshot_digests(ROOT, checkpoint)
        final_sha256_by_path.update(
            {
                path: reanchor.bytes_sha256((ROOT / path).read_bytes())
                for path in reanchor.REQUIRED_CONTROL_PATHS
                if (ROOT / path).is_file()
            }
        )
        checkpoint, _ = reanchor.project(
            checkpoint,
            final_sha256_by_path,
            successor_contract=gate.expected_contract_binding(),
            runner_binding=reanchor.start_gate_runner_binding(ROOT),
            transition_review={
                role: {
                    "path": f"fixture/{role}.json",
                    "sha256": str(index) * 64,
                    "byte_length": index,
                }
                for index, role in enumerate(
                    ("assignment", "review_result", "independent_review"),
                    start=1,
                )
            },
        )
    monkeypatch.setattr(
        continuation,
        "_validate_fp022_control_reanchor_seq68",
        lambda *_args, **_kwargs: [],
    )
    event = {
        "sequence": 69,
        "event_id": gate.STARTED_EVENT_ID,
        "previous_event_sha256": checkpoint["goal_execution"][
            "transition_history"
        ][reanchor.SEQUENCE - 1]["event_sha256"],
    }
    errors, checks, binding, ready = continuation._fp022_start_gate_contract(
        ROOT,
        event=event,
        checkpoint=checkpoint,
    )
    assert errors == []
    assert [row["check_id"] for row in checks] == continuation.FP022_START_GATE_CHECK_IDS
    assert binding == gate.expected_contract_binding()
    assert ready["event_sha256"] == continuation.event_sha256(ready)

    tampered = copy.deepcopy(checkpoint)
    tampered["goal_execution"]["transition_history"][reanchor.SEQUENCE - 1][
        "contract_supersession"
    ]["replacement_contract_binding"]["file_sha256"] = "0" * 64
    errors, _, _, _ = continuation._fp022_start_gate_contract(
        ROOT,
        event=event,
        checkpoint=tampered,
    )
    assert errors
