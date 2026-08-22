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


def test_r004_successor_contract_freezes_r003_and_advances_current_aliases() -> None:
    assert subject.TRANSITION_R003_PINS == {
        subject.TRANSITION_R003_ASSIGNMENT_REL: (
            "d387f5058b00b50c15aadfa77b844d59d7b08529e4efadacf96719dccc2cbdd8",
            10715,
        ),
        subject.TRANSITION_R003_RESULT_REL: (
            "c4c1c8820df69ef0c6ac73f39ab175c5704e6c97200f8c73764975d1846799bc",
            10706,
        ),
        subject.TRANSITION_R003_INDEPENDENT_REL: (
            "a0ddc130d23048a6c5ab39e6dd78d8d485338caaee3192f83c01dcb3eca0210e",
            10964,
        ),
    }
    assert subject.TRANSITION_ASSIGNMENT_REL == subject.TRANSITION_R004_ASSIGNMENT_REL
    assert subject.TRANSITION_RESULT_REL == subject.TRANSITION_R004_RESULT_REL
    assert subject.TRANSITION_INDEPENDENT_REL == subject.TRANSITION_R004_INDEPENDENT_REL
    assert not set(subject.TRANSITION_R003_PATHS) & set(subject.TRANSITION_R004_PATHS)
    assert subject.TRANSITION_R004_ROUND_ID == (
        "WS-FP046-NPC-R002-REOPEN-TRANSITION-20260815-R004"
    )
    assert subject.TRANSITION_R004_ASSIGNER_TASK == "/root"
    assert subject.TRANSITION_R004_EXECUTOR_TASK == "/root/audit_transaction"
    assert subject.TRANSITION_R004_REVIEWER_TASK == (
        "/root/r004_transition_final_review"
    )
    assert len(
        {
            subject.TRANSITION_R004_ASSIGNER_ID,
            subject.TRANSITION_R004_EXECUTOR_ID,
            subject.TRANSITION_R004_REVIEWER_ID,
        }
    ) == 3


def test_r003_successor_contract_freezes_r002_and_advances_current_aliases() -> None:
    assert subject.TRANSITION_R002_PINS == {
        subject.TRANSITION_R002_ASSIGNMENT_REL: (
            "a15de9a0b57a7fa207dd8e2a4eab92c2ed1166496a561e289bccba7dec61096e",
            9731,
        ),
        subject.TRANSITION_R002_RESULT_REL: (
            "432a71a98b86796b836b031da30250daa745f82c49ce492cd52cedac60d103d8",
            9731,
        ),
        subject.TRANSITION_R002_INDEPENDENT_REL: (
            "5be5009cfa96193b8536b8fcbd451651a4460fecb4d41559b68e7c407dbee095",
            9988,
        ),
    }
    assert not set(subject.TRANSITION_R002_PATHS) & set(subject.TRANSITION_R003_PATHS)


def _frozen_r009_material() -> tuple[dict[str, dict], dict[Path, bytes]]:
    module = subject._control_successor_module()
    paths = tuple(Path(path) for path in module.CONTROL_SUCCESSOR_R009_PATHS)
    raw_by_path = {
        path: (ROOT / path).read_bytes()
        for path in paths
    }
    return subject._review_binding_by_role(paths, raw_by_path), raw_by_path


def _frozen_r010_material() -> tuple[dict[str, dict], dict[Path, bytes]]:
    module = subject._control_successor_module()
    paths = tuple(Path(path) for path in module.CONTROL_SUCCESSOR_R010_PATHS)
    raw_by_path = {
        path: (ROOT / path).read_bytes()
        for path in paths
    }
    return subject._review_binding_by_role(paths, raw_by_path), raw_by_path


def _fake_r011_material() -> tuple[dict[str, dict], dict[Path, bytes]]:
    module = subject._control_successor_module()
    paths = tuple(Path(path) for path in module.CONTROL_SUCCESSOR_R011_PATHS)
    raw_by_path = {
        path: f"reviewed R011 {index}\n".encode("utf-8")
        for index, path in enumerate(paths, start=1)
    }
    return subject._review_binding_by_role(paths, raw_by_path), raw_by_path


def _fake_r004_material() -> tuple[dict[str, dict], dict[Path, bytes]]:
    raw_by_path = {
        path: f"reviewed R004 {index}\n".encode("utf-8")
        for index, path in enumerate(subject.TRANSITION_R004_PATHS, start=1)
    }
    return subject._review_binding_by_role(
        subject.TRANSITION_R004_PATHS,
        raw_by_path,
    ), raw_by_path


@pytest.fixture(autouse=True)
def _control_review_stubs(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        subject,
        "load_frozen_control_successor_r009",
        lambda root: _frozen_r009_material(),
    )
    monkeypatch.setattr(
        subject,
        "load_frozen_control_successor_r010",
        lambda root: _frozen_r010_material(),
    )
    monkeypatch.setattr(
        subject,
        "load_validated_control_successor_r011",
        lambda root: _fake_r011_material(),
    )


def _write(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)


def _copy_source(root: Path) -> None:
    checkpoint = json.loads(
        (ROOT / subject.CHECKPOINT_REL).read_text(encoding="utf-8")
    )
    artifact_register = next(
        Path(binding["path"])
        for binding in checkpoint["canonical_bindings"]
        if binding["role"] == "ARTIFACT_REGISTER"
    )
    paths = (
        subject.CHECKPOINT_REL,
        artifact_register,
        *subject.R028_PATHS,
        subject.FP046_R001_REL,
        subject.NPC_R001_REL,
        subject.FP022_R001_REL,
        subject.EPIC03_REL,
        *subject.r029_candidate.CURRENT_SOURCE_PATHS,
        *subject.R007_REVIEW_PINS,
        *subject.TRANSITION_R001_PATHS,
        *subject.TRANSITION_R002_PATHS,
        *subject.TRANSITION_R003_PATHS,
        *(
            Path(path)
            for path in checkpoint["goal_execution"]["goal_document_paths"]
        ),
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


def _post_publish_fixture(root: Path) -> dict:
    _copy_source(root)
    package = subject.build_transition_package(root)
    subject.write_transition_assignment(
        root,
        assigned_at="2026-08-15T12:00:00+09:00",
    )
    assignment, assignment_raw, _current_package = subject._read_transition_assignment(
        root
    )
    result = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP046_NPC_R002_REOPEN_TRANSITION_REVIEWER_AUTHORED_RESULT"
        ),
        "goal_id": subject.TRANSITION_GOAL_ID,
        "round_id": subject.TRANSITION_ROUND_ID,
        "reviewed_at": assignment["assigned_at"],
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
    _write(
        root,
        subject.TRANSITION_RESULT_REL,
        subject.json_text(result).encode("utf-8"),
    )
    subject.write_transition_independent_review(root)
    review_raw = {
        path: (root / path).read_bytes() for path in subject.TRANSITION_R004_PATHS
    }
    review_binding = subject._review_binding_by_role(
        subject.TRANSITION_R004_PATHS,
        review_raw,
    )
    final_plan = subject.bind_transition_review_evidence(
        package["preflight"],
        predecessor_transition_review_binding=package[
            "predecessor_transition_review_bindings"
        ],
        transition_review_binding=review_binding,
        transition_review_subject_binding=package[
            "approval_neutral_plan_core_binding"
        ],
        r009_control_review_binding=package[
            "r009_control_successor_review_bindings"
        ],
        r010_control_review_binding=package[
            "r010_control_successor_review_bindings"
        ],
        r011_control_review_binding=package[
            "r011_control_successor_review_bindings"
        ],
    )
    canonical_outputs, _source_evidence = (
        subject.r029_bridge.build_outputs_and_source_evidence(root)
    )
    for path, text in canonical_outputs.items():
        _write(root, path, text.encode("utf-8"))
    for path, text in final_plan["documents"].items():
        _write(root, Path(path), text.encode("utf-8"))
    _write(
        root,
        subject.AUTHORIZATION_REL,
        subject.json_text(subject._authorization_document()).encode("utf-8"),
    )
    _write(
        root,
        subject.INITIAL_START_GATE_CONTRACT_REL,
        subject.json_text(subject._initial_start_gate_contract(final_plan)).encode(
            "utf-8"
        ),
    )
    checkpoint = json.loads(
        (root / subject.CHECKPOINT_REL).read_text(encoding="utf-8")
    )
    checkpoint["goal_execution"]["transition_history"].extend(final_plan["events"])
    _write(
        root,
        subject.CHECKPOINT_REL,
        subject.json_text(checkpoint).encode("utf-8"),
    )
    return checkpoint


def _move_seq72_before_exact_position(history: list[dict]) -> None:
    event = next(event for event in history if event.get("sequence") == 72)
    history.remove(event)
    history.insert(70, event)


def _insert_event_between_seq73_and_seq74(history: list[dict]) -> None:
    seq74_index = next(
        index for index, event in enumerate(history) if event.get("sequence") == 74
    )
    history.insert(
        seq74_index,
        {
            "sequence": 9_999,
            "event_id": "UNEXPECTED-INTERLEAVED-EVENT",
            "event_type": "UNEXPECTED_INTERLEAVING",
        },
    )


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
    assert {
        event["runtime_after"]["artifact_work_queue_sha256"]
        for event in plan["events"]
    } == {"ba5dc2d8cdd0d3956840a86c0f0a3b767b4fd4ca2dc54393ce05957f3ac022dc"}
    assert [
        event["runtime_after"]["completion_boundary_sha256"]
        for event in plan["events"]
    ] == [
        "2127b6d84ae4384962adecbc05209402e40453112c1cb51618496f8382397b32",
        "53654aabc9f05755069ded61795d7b759a5e863acbda038a91ecd8e875a7d4a0",
        "afd738793dd68bffe2ba9066f268b2b27f410f19c9f92fca704b84f8c40c9120",
        "23e17ac7f337528ca17d5f3dc4bd6c4ef95923facf807cad922055f4a7d6f5b7",
        "4f3f0e675ca6772dc95451e2f72d1f6170edbdc6b16b0ddc92586ce0699bb0cf",
    ]
    assert subject.goal_graph.continuation.canonical_json_sha256(
        plan["final_state"]["artifact_work_queue"]
    ) == (
        "ba5dc2d8cdd0d3956840a86c0f0a3b767b4fd4ca2dc54393ce05957f3ac022dc"
    )
    assert subject.goal_graph.continuation.canonical_json_sha256(
        plan["final_state"]["completion_boundary"]
    ) == (
        "4f3f0e675ca6772dc95451e2f72d1f6170edbdc6b16b0ddc92586ce0699bb0cf"
    )

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
    before = _snapshot(tmp_path)

    assert subject.main(["--root", str(tmp_path), "--print-transition-package"]) == 0
    assert subject.main(["--root", str(tmp_path), "--apply"]) == 1
    assert before == _snapshot(tmp_path)


def test_r003_is_exact_frozen_predecessor_and_r004_is_add_only_current(
    tmp_path: Path,
) -> None:
    _copy_source(tmp_path)
    before = {
        path: (tmp_path / path).read_bytes()
        for path in (
            *subject.TRANSITION_R001_PATHS,
            *subject.TRANSITION_R002_PATHS,
            *subject.TRANSITION_R003_PATHS,
        )
    }

    binding, raw_by_path = subject.load_frozen_transition_r003(tmp_path)

    assert binding == subject._review_binding_by_role(
        subject.TRANSITION_R003_PATHS,
        raw_by_path,
    )
    assert subject.load_validated_transition_r003(tmp_path) == (binding, raw_by_path)
    assert subject.TRANSITION_ASSIGNMENT_REL == subject.TRANSITION_R004_ASSIGNMENT_REL
    assert subject.TRANSITION_RESULT_REL == subject.TRANSITION_R004_RESULT_REL
    assert subject.TRANSITION_INDEPENDENT_REL == subject.TRANSITION_R004_INDEPENDENT_REL
    assert not set(subject.TRANSITION_R003_PATHS) & set(subject.TRANSITION_R004_PATHS)

    subject.write_transition_assignment(
        tmp_path,
        assigned_at="2026-08-15T12:00:00+09:00",
    )

    assert (tmp_path / subject.TRANSITION_R004_ASSIGNMENT_REL).is_file()
    assert before == {
        path: (tmp_path / path).read_bytes()
        for path in (
            *subject.TRANSITION_R001_PATHS,
            *subject.TRANSITION_R002_PATHS,
            *subject.TRANSITION_R003_PATHS,
        )
    }


def test_frozen_r001_loader_rejects_one_byte_drift(tmp_path: Path) -> None:
    _copy_source(tmp_path)
    target = tmp_path / subject.TRANSITION_R001_RESULT_REL
    target.write_bytes(target.read_bytes() + b" ")

    with pytest.raises(subject.BuildError, match="frozen transition R001 review differs"):
        subject.load_frozen_transition_r001(tmp_path)


@pytest.mark.parametrize("relative", subject.TRANSITION_R002_PATHS)
@pytest.mark.parametrize("operation", ("missing", "tampered"))
def test_frozen_r002_loader_rejects_each_missing_or_tampered_byte(
    tmp_path: Path,
    relative: Path,
    operation: str,
) -> None:
    _copy_source(tmp_path)
    target = tmp_path / relative
    if operation == "missing":
        target.unlink()
    else:
        target.write_bytes(target.read_bytes() + b" ")

    with pytest.raises(
        subject.BuildError,
        match="required frozen transition R002 review is missing|frozen transition R002 review differs",
    ):
        subject.load_frozen_transition_r002(tmp_path)


@pytest.mark.parametrize("relative", subject.TRANSITION_R003_PATHS)
@pytest.mark.parametrize("operation", ("missing", "tampered"))
def test_frozen_r003_loader_rejects_each_missing_or_tampered_byte(
    tmp_path: Path,
    relative: Path,
    operation: str,
) -> None:
    _copy_source(tmp_path)
    target = tmp_path / relative
    if operation == "missing":
        target.unlink()
    else:
        target.write_bytes(target.read_bytes() + b" ")

    with pytest.raises(
        subject.BuildError,
        match="required frozen transition R003 review is missing|frozen transition R003 review differs",
    ):
        subject.load_frozen_transition_r003(tmp_path)


@pytest.mark.parametrize(
    "loader_name",
    (
        "load_frozen_transition_r002",
        "load_frozen_control_successor_r009",
        "load_frozen_control_successor_r010",
    ),
)
def test_frozen_r003_loader_rejects_internal_predecessor_provenance_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    loader_name: str,
) -> None:
    _copy_source(tmp_path)
    frozen_r002 = subject.load_frozen_transition_r002(tmp_path)
    loader = getattr(subject, loader_name)
    binding, raw_by_path = loader(tmp_path)
    tampered = deepcopy(binding)
    tampered["assignment"]["sha256"] = "0" * 64
    monkeypatch.setattr(
        subject,
        loader_name,
        lambda root: (tampered, raw_by_path),
    )
    if loader_name == "load_frozen_control_successor_r009":
        monkeypatch.setattr(
            subject,
            "load_frozen_transition_r002",
            lambda root: frozen_r002,
        )

    with pytest.raises(
        subject.BuildError,
        match="frozen transition R003 approval provenance differs",
    ):
        subject.load_frozen_transition_r003(tmp_path)


@pytest.mark.parametrize(
    "loader_name",
    ("load_frozen_transition_r001", "load_frozen_control_successor_r009"),
)
def test_frozen_r002_loader_rejects_internal_predecessor_provenance_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    loader_name: str,
) -> None:
    _copy_source(tmp_path)
    loader = getattr(subject, loader_name)
    binding, raw_by_path = loader(tmp_path)
    tampered = deepcopy(binding)
    tampered["assignment"]["sha256"] = "0" * 64
    monkeypatch.setattr(
        subject,
        loader_name,
        lambda root: (tampered, raw_by_path),
    )

    with pytest.raises(
        subject.BuildError,
        match="frozen transition R002 approval provenance differs",
    ):
        subject.load_frozen_transition_r002(tmp_path)


def test_r004_scope_exactly_binds_neutral_core_and_eight_outputs(
    tmp_path: Path,
) -> None:
    _copy_source(tmp_path)
    package = subject.build_transition_package(tmp_path)
    scope = subject._transition_scope(package)

    assert package["schema_version"].endswith("transition-package.v4")
    assert "r007_control_successor_review_bindings" not in scope
    assert len(scope["corrected_add_only_output_bindings"]) == 8
    assert scope["corrected_plan_core_binding"] == subject.transition_plan_core_binding(
        package["approval_neutral_plan_core"]
    )
    seq72 = package["approval_neutral_plan_core"]["events"][0]
    assert seq72["predecessor_transition_review_binding"] == package[
        "predecessor_transition_review_bindings"
    ]
    assert seq72["r009_control_review_binding"] == package[
        "r009_control_successor_review_bindings"
    ]
    assert seq72["r010_control_review_binding"] == package[
        "r010_control_successor_review_bindings"
    ]
    assert seq72["r011_control_review_binding"] == package[
        "r011_control_successor_review_bindings"
    ]
    assert "transition_review_binding" not in seq72
    assert "transition_review_subject_binding" not in seq72

    current_binding = _fake_r004_material()[0]
    final_plan = subject.bind_transition_review_evidence(
        package["approval_neutral_plan_core"],
        predecessor_transition_review_binding=package[
            "predecessor_transition_review_bindings"
        ],
        transition_review_binding=current_binding,
        transition_review_subject_binding=package[
            "approval_neutral_plan_core_binding"
        ],
        r009_control_review_binding=package[
            "r009_control_successor_review_bindings"
        ],
        r010_control_review_binding=package[
            "r010_control_successor_review_bindings"
        ],
        r011_control_review_binding=package[
            "r011_control_successor_review_bindings"
        ],
    )
    assert subject.approval_neutral_plan_core(final_plan) == package[
        "approval_neutral_plan_core"
    ]
    subject.validate_reviewed_transition_plan(final_plan, package, current_binding)

    tampered = deepcopy(package)
    next(
        row
        for row in tampered["staged_subject_bindings"]
        if row["path"] == subject.AUTHORIZATION_REL.as_posix()
    )["sha256"] = "0" * 64
    with pytest.raises(subject.BuildError, match="add-only output bindings differ"):
        subject._transition_scope(tampered)


def test_post_publish_r004_loader_allows_seq77_and_seq78_suffix(
    tmp_path: Path,
) -> None:
    checkpoint = _post_publish_fixture(tmp_path)
    checkpoint["goal_execution"]["transition_history"].extend(
        [
            {
                "sequence": 77,
                "event_id": "TEST-SEQ77-GOAL-STARTED",
                "event_type": "GOAL_STARTED",
            },
            {
                "sequence": 78,
                "event_id": "TEST-SEQ78-GOAL-COMPLETED",
                "event_type": "GOAL_COMPLETED",
            },
        ]
    )
    _write(
        tmp_path,
        subject.CHECKPOINT_REL,
        subject.json_text(checkpoint).encode("utf-8"),
    )

    binding, raw_by_path = subject.load_validated_transition_r004(tmp_path)

    assert binding == subject._review_binding_by_role(
        subject.TRANSITION_R004_PATHS,
        raw_by_path,
    )


@pytest.mark.parametrize(
    ("mutate", "message"),
    (
        pytest.param(
            lambda history: history.append(
                deepcopy(next(event for event in history if event.get("sequence") == 72))
            ),
            "post-publish seq72 transition is missing or non-unique",
            id="duplicate-seq72",
        ),
        pytest.param(
            lambda history: history.__setitem__(
                slice(None),
                [event for event in history if event.get("sequence") != 74],
            ),
            "post-publish seq74 transition is missing or non-unique",
            id="missing-seq74",
        ),
        pytest.param(
            lambda history: next(
                event for event in history if event.get("sequence") == 75
            ).__setitem__("event_id", "TAMPERED-SEQ75-EVENT"),
            "post-publish seq75 transition identity differs",
            id="tampered-seq75-event-id",
        ),
        pytest.param(
            _move_seq72_before_exact_position,
            "post-publish seq72-76 transition position differs",
            id="seq72-moved-before-index-71",
        ),
        pytest.param(
            _insert_event_between_seq73_and_seq74,
            "post-publish seq72-76 transition position differs",
            id="event-inserted-between-seq73-and-seq74",
        ),
    ),
)
def test_post_publish_r004_loader_rejects_non_exact_seq72_76_history(
    tmp_path: Path,
    mutate,
    message: str,
) -> None:
    checkpoint = _post_publish_fixture(tmp_path)
    history = checkpoint["goal_execution"]["transition_history"]
    mutate(history)
    _write(
        tmp_path,
        subject.CHECKPOINT_REL,
        subject.json_text(checkpoint).encode("utf-8"),
    )

    with pytest.raises(subject.BuildError, match=message):
        subject.load_validated_transition_r004(tmp_path)


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
    for source in sorted((ROOT / "scripts").glob("*.py")):
        relative = Path("scripts") / source.name
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
