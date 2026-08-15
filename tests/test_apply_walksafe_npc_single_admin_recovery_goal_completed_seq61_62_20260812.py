from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Callable

import pytest

from scripts import apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812 as subject


ROOT = Path(__file__).resolve().parents[1]
TEST_UNIVERSE = tuple(
    sorted(
        {
            subject.CHECKPOINT_REL.as_posix(),
            "managed.txt",
            *(path.as_posix() for path in subject.CATALOG_PATHS),
        }
    )
)


def _catalog_builder(
    _root: Path,
    paths: tuple[str, ...],
    *,
    checkpoint_override: dict[str, Any],
) -> dict[str, bytes]:
    assert paths == TEST_UNIVERSE
    assert checkpoint_override["goal_execution"]["transition_history"][-1]["sequence"] == 62
    return {path.as_posix(): b"managed" for path in subject.CATALOG_PATHS}


def _source() -> dict[str, Any]:
    return json.loads((ROOT / subject.CHECKPOINT_REL).read_text())


def _evidence(
    source: dict[str, Any],
    *,
    managed: dict[Path, str] | None = None,
    production_pins: dict[Path, str] | None = None,
) -> subject.CompletionEvidence:
    source_bindings = {row["role"]: row for row in source["canonical_bindings"]}
    bindings: dict[str, dict[str, Any]] = {}
    for index, (role, path) in enumerate(subject.CANONICAL_PATH_BY_ROLE.items(), start=1):
        if role == subject.COMPLETION_ROLE:
            bindings[role] = {
                "role": role,
                "document_id": "TEST-NPC-COMPLETION",
                "path": path.as_posix(),
                "file_sha256": "f" * 64,
            }
            continue
        binding = deepcopy(source_bindings[role])
        binding["path"] = path.as_posix()
        binding["file_sha256"] = f"{index:x}" * 64
        if role == "IMPLEMENTATION_GAP":
            binding["document_id"] = "WS-IMPLEMENTATION-GAP-ANALYSIS-20260813-027"
        elif role == "IMPLEMENTATION_BACKLOG":
            binding["document_id"] = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260813-027"
        bindings[role] = {
            key: binding[key]
            for key in ("role", "document_id", "path", "file_sha256")
        }
    gap = {
        "metadata": {
            "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260813-027",
            "version": "0.27.0",
        },
        "assessments": [{"gap_id": "GAP-008", "status": "PARTIAL"}],
        "summary": {
            "status_counts": {
                "BLOCKED": 5,
                "CONFLICTING": 16,
                "EVIDENCE_MISSING": 4,
                "IMPLEMENTED": 0,
                "MISSING": 8,
                "PARTIAL": 35,
            }
        },
        "implementation_snapshot": {"content_set_sha256": "a" * 64},
    }
    backlog = {
        "metadata": {
            "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260813-027"
        },
        "epics": [
            {
                "epic_id": "EPIC-04",
                "current_status": "PLANNED",
                "deferred_release_gate_ids": ["GATE-TEST"],
                "gap_ids": ["GAP-007", "GAP-031"],
                "source_policy_ids": ["FP-022", "FP-023"],
                "target_completion_level": "IMPLEMENTATION_READY",
                "title": "경로·도착·이탈 사용자 결정 흐름",
            }
        ],
        "next_single_action": {
            "epic_id": "EPIC-04",
            "source_policy_id": "FP-022",
            "gap_id": "GAP-031",
            "priority_rank": 24,
            "status": "PLANNED_NEXT",
            "work_item_id": "WS-GOAL-EPIC-04-FP-022-R001",
            "action": "FP-022/GAP-031 repository-internal implementation is next.",
        },
    }
    managed_map = (
        {Path("managed.txt"): "a" * 64} if managed is None else dict(managed)
    )
    return subject.CompletionEvidence(
        documents_by_path={
            subject.gap_builder.R027_GAP_JSON_REL: gap,
            subject.gap_builder.R027_BACKLOG_JSON_REL: backlog,
        },
        bindings_by_role=bindings,
        update_occurred_at="2026-08-12T23:59:00+09:00",
        completion_occurred_at="2026-08-12T23:59:01+09:00",
        physical_sha256_by_path=managed_map,
        final_managed_sha256_by_path=managed_map,
        production_sha256_by_path=production_pins,
    )


def _pins() -> dict[Path, str]:
    return {path: "a" * 64 for path in subject.PINNED_PRODUCTION_PATHS}


def _review_result_with_production_pins(
    pins: dict[Path, str],
) -> dict[str, Any]:
    paths = sorted(pins, key=Path.as_posix)
    split = len(paths) // 2
    evidence = [
        {"path": path.as_posix(), "sha256": pins[path]}
        for path in paths[:split]
    ]
    controls = [
        {
            "path": path.as_posix(),
            "sha256": pins[path],
            "byte_length": 1,
        }
        for path in paths[split:]
    ]
    return {
        "review_scope": {
            "reviewed_evidence_manifest": evidence,
            "reviewed_evidence_manifest_sha256": subject.trace.object_sha256(
                evidence
            ),
            "reviewed_control_code_cohort": controls,
            "reviewed_control_code_cohort_sha256": subject.trace.object_sha256(
                controls
            ),
        }
    }


def _runtime_deriver(
    _root: Path,
    checkpoint: dict[str, Any],
    _ready: list[str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    state = checkpoint["goal_execution"]
    return deepcopy(state["artifact_work_queue"]), deepcopy(state["completion_boundary"])


def _snapshot_hasher(_root: Path, paths: list[str]) -> tuple[str, str]:
    return subject._snapshot_hashes_from_digests(
        {Path(path): subject.bytes_sha256(b"managed") for path in paths}
    )


def _fixture_root(tmp_path: Path) -> tuple[Path, Path, subject.CompletionEvidence]:
    checkpoint = tmp_path / subject.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes((ROOT / subject.CHECKPOINT_REL).read_bytes())
    managed = tmp_path / "managed.txt"
    managed.write_bytes(b"managed")
    digest = subject.bytes_sha256(b"managed")
    managed_map = {Path("managed.txt"): digest}
    for relative in subject.CATALOG_PATHS:
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"managed")
        managed_map[relative] = digest
    return tmp_path, managed, _evidence(_source(), managed=managed_map)


def _prepare_fixture(
    tmp_path: Path,
    *,
    evidence_loader: Callable[..., subject.CompletionEvidence] | None = None,
) -> tuple[subject.PreparedProjection, Path, subject.CompletionEvidence]:
    root, managed, evidence = _fixture_root(tmp_path)
    loader = (
        (lambda *_args, **_kwargs: evidence)
        if evidence_loader is None
        else evidence_loader
    )
    prepared = subject.prepare_projection(
        root,
        production_sha256_by_path=_pins(),
        evidence_loader=loader,
        runtime_deriver=_runtime_deriver,
        snapshot_hasher=_snapshot_hasher,
        changed_path_loader=lambda _root: {Path("managed.txt")},
        catalog_source_loader=lambda _root: TEST_UNIVERSE,
        catalog_builder=_catalog_builder,
        continuation_checker=lambda _root, _checkpoint: [],
        goal_graph_checker=lambda _root, _checkpoint: [],
    )
    return prepared, managed, evidence


def test_seq61_62_are_adjacent_and_bind_final_working_snapshot() -> None:
    source = _source()
    evidence = _evidence(source)
    projected, update, completion = subject.project_seq61_62(source, evidence)
    subject.validate_projection(source, projected, evidence)

    assert update["sequence"] == 61
    assert update["event_type"] == "CANONICAL_BINDINGS_UPDATED"
    assert update["changed_subject_ids_by_role"] == (
        subject.CHANGED_SUBJECT_IDS_BY_ROLE
    )
    assert update["changed_subject_ids_by_role"] == {
        "ARTIFACT_CHANGE_LOG": subject.DLV_SUBJECT_IDS,
        "ARTIFACT_REGISTER": subject.DLV_SUBJECT_IDS,
        "REQUIREMENTS_TRACEABILITY": ["NPC-SINGLE-ADMIN-RECOVERY"],
        "DESIGN_TRACEABILITY": ["NPC-SINGLE-ADMIN-RECOVERY"],
        "MODULE_REGISTER": ["NPC-SINGLE-ADMIN-RECOVERY"],
        "IMPLEMENTATION_GAP": [
            "GAP-008",
            "NPC-SINGLE-ADMIN-RECOVERY",
        ],
        "IMPLEMENTATION_BACKLOG": ["NPC-SINGLE-ADMIN-RECOVERY"],
    }
    assert subject.COMPLETION_ROLE not in update["changed_subject_ids_by_role"]
    assert update["producer_output_subject_ids_by_role"] == {
        "IMPLEMENTATION_BACKLOG": ["NPC-SINGLE-ADMIN-RECOVERY"],
        "IMPLEMENTATION_GAP": [
            "GAP-008",
            "NPC-SINGLE-ADMIN-RECOVERY",
        ],
    }
    assert update["previous_event_sha256"] == subject.SOURCE_START_EVENT_SHA256
    assert update["changed_binding_roles"] == subject.CHANGED_ROLES
    assert completion["sequence"] == 62
    assert completion["event_type"] == "GOAL_COMPLETED"
    assert completion["previous_event_sha256"] == update["event_sha256"]
    assert completion["canonical_update_event_sha256"] == update["event_sha256"]
    assert projected["goal_execution"]["status_by_goal"][subject.GOAL_ID] == "COMPLETE_AT_TARGET"
    state = projected["goal_execution"]
    assert state["focus_goal_id"] == subject.FOCUS_GOAL_ID
    assert state["ready_frontier_goal_ids"] == [
        "WS-GOAL-EPIC-02",
        "WS-GOAL-EPIC-03",
        "WS-GOAL-EPIC-12",
    ]
    assert projected["current_work"]["epic_id"] == "WS-GOAL-EPIC-04"
    assert projected["current_work"]["status"] == "PLANNED"
    assert projected["current_work"]["work_item_id"] == (
        "WS-GOAL-EPIC-04-FP-022-R001"
    )
    assert projected["current_work"]["scope_kind"] == (
        "BACKLOG_EPIC_AGGREGATE"
    )
    assert projected["current_work"]["deferred_release_gate_ids"] == [
        "GATE-TEST"
    ]
    assert projected["session_handoff"]["current_epic"] == (
        "EPIC-04 / FP-022/GAP-031 PLANNED_NEXT"
    )
    assert projected["session_handoff"]["last_updated_by_work_item"] == (
        subject.trace.WORK_ITEM_ID
    )
    assert projected["session_handoff"]["last_verification_status"] == (
        "PASS_INTERNAL_ONLY_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
    )
    assert projected["working_tree_snapshot"]["managed_changed_paths"] == ["managed.txt"]
    assert len(projected["canonical_bindings"]) == len(source["canonical_bindings"]) + 1


def test_planned_tests_and_dev01_do_not_become_canonical_changes() -> None:
    source = _source()
    projected, _, _ = subject.project_seq61_62(source, _evidence(source))
    before = subject.contract.canonical_binding_snapshot(source)
    after = subject.contract.canonical_binding_snapshot(projected)

    assert before["PLANNED_TEST_CASES"] == after["PLANNED_TEST_CASES"]
    assert "PLANNED_TEST_CASES" not in subject.CHANGED_ROLES
    assert "IMPLEMENTATION_MANIFEST" not in subject.CHANGED_ROLES
    assert subject.artifact_builder.IMPLEMENTATION_MANIFEST_REL.as_posix() not in {
        row["path"] for row in projected["canonical_bindings"]
    }


def test_projection_allowlist_rejects_unrelated_nested_mutation() -> None:
    source = _source()
    evidence = _evidence(source)
    projected, _, _ = subject.project_seq61_62(source, evidence)
    projected["goal_execution"]["standing_execution_authority"] = {"expanded": True}

    with pytest.raises(subject.BuildError, match="unauthorized goal-execution mutation"):
        subject.validate_projection(source, projected, evidence)


def test_seq60_source_snapshot_reconstructs_exact_645_path_authority() -> None:
    source = _source()
    sha256_by_path, start_physical = subject._source_snapshot_authority(ROOT, source)
    snapshot = source["working_tree_snapshot"]

    assert len(sha256_by_path) == 645
    assert subject._snapshot_hashes_from_digests(sha256_by_path) == (
        snapshot["path_set_sha256"],
        snapshot["content_set_sha256"],
    )
    assert start_physical[subject.trace.START_GATE_RECEIPT_REL] == (
        subject.trace.EXPECTED_START_GATE_RECEIPT_SHA256
    )


def test_review_scope_resolves_all_production_pins_without_pending_static_map() -> None:
    pins = _pins()

    assert subject._production_pins_from_review_scope(
        _review_result_with_production_pins(pins)
    ) == pins
    assert "PENDING_FINAL_ARTIFACT_SHA256" not in subject.__dict__
    assert "PINNED_PRODUCTION_SHA256_BY_PATH" not in subject.__dict__


def test_projected_checkpoint_is_fsynced_and_sent_to_both_explicit_checkers(
    tmp_path: Path,
) -> None:
    checkpoint = tmp_path / subject.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(b"{}\n")
    projected = b'{"projected":true}\n'
    calls: list[tuple[str, Path]] = []

    def checker(name: str) -> Callable[[Path, Path], list[str]]:
        def run(root: Path, relative: Path) -> list[str]:
            assert relative != subject.CHECKPOINT_REL
            assert not relative.is_absolute()
            assert (root / relative).read_bytes() == projected
            calls.append((name, relative))
            return []

        return run

    subject.validate_projected_with_both_checkers(
        tmp_path,
        projected,
        continuation_checker=checker("continuation"),
        goal_graph_checker=checker("goal_graph"),
    )

    assert [name for name, _ in calls] == ["continuation", "goal_graph"]
    assert not list(checkpoint.parent.glob(".walksafe-npc-seq61-62-preflight.*"))


@pytest.mark.parametrize("failing", ["continuation", "goal_graph"])
def test_projected_checker_failure_is_fail_closed(tmp_path: Path, failing: str) -> None:
    checkpoint = tmp_path / subject.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(b"{}\n")

    def result(name: str) -> Callable[[Path, Path], list[str]]:
        return lambda _root, _checkpoint: ["tampered"] if name == failing else []

    with pytest.raises(subject.BuildError, match=f"projected {failing.replace('_', '-')} v2.4 check failed"):
        subject.validate_projected_with_both_checkers(
            tmp_path,
            b"{}\n",
            continuation_checker=result("continuation"),
            goal_graph_checker=result("goal_graph"),
        )


def test_safe_evidence_reader_rejects_symlink(tmp_path: Path) -> None:
    real = tmp_path / "real.json"
    real.write_text("{}\n")
    linked = tmp_path / "linked.json"
    linked.symlink_to(real)

    with pytest.raises(subject.CompletionApplyError, match="symlink is forbidden"):
        subject._read_safe_bytes(tmp_path, Path("linked.json"))


def test_auxiliary_closure_accepts_only_catalog_runner_and_canonical_daylog_paths(
    tmp_path: Path,
) -> None:
    catalog = Path("docs/catalogs/scripts.json")
    feature_catalog = Path("docs/planning/walksafe_feature_implementation_catalog.html")
    daylog = Path("daylog/2026-08-13.md")
    arbitrary = Path("daylog/not-a-day.md")
    for relative in (catalog, feature_catalog, daylog, arbitrary):
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("managed\n")

    selected = subject._auxiliary_final_paths(
        tmp_path,
        changed_path_loader=lambda _root: {
            catalog,
            feature_catalog,
            daylog,
            arbitrary,
        },
    )

    assert selected == {catalog, feature_catalog, daylog}


def test_sequence_authority_includes_verification_runner_and_dedicated_test() -> None:
    assert len(subject.SEQUENCE_AUTHORITY_PATHS) == 43
    assert Path(
        "scripts/run_walksafe_npc_single_admin_recovery_verification_20260813.py"
    ) in subject.SEQUENCE_AUTHORITY_PATHS
    assert Path(
        "tests/test_run_walksafe_npc_single_admin_recovery_verification_20260813.py"
    ) in subject.SEQUENCE_AUTHORITY_PATHS
    assert Path("scripts/check_walksafe_goal_graph_v2_4.py") in (
        subject.SEQUENCE_AUTHORITY_PATHS
    )
    assert Path("tests/test_walksafe_goal_graph_v2_4.py") in (
        subject.SEQUENCE_AUTHORITY_PATHS
    )
    assert Path("scripts/check_walksafe_project_continuation_v2_4.py") in (
        subject.SEQUENCE_AUTHORITY_PATHS
    )
    assert Path("tests/test_walksafe_project_continuation_v2_4.py") in (
        subject.SEQUENCE_AUTHORITY_PATHS
    )
    assert Path("scripts/generate_repository_catalogs.py") in (
        subject.SEQUENCE_AUTHORITY_PATHS
    )
    assert Path("tests/test_repository_catalogs.py") in (
        subject.SEQUENCE_AUTHORITY_PATHS
    )
    assert Path("scripts/run_walksafe_test_layers_current.sh") in (
        subject.SEQUENCE_AUTHORITY_PATHS
    )
    assert subject.r004_review.CONTROL_PATHS[-2:] == (
        Path(
            "scripts/build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813.py"
        ),
        Path(
            "tests/test_build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813.py"
        ),
    )
    assert set(subject.r004_review.CONTROL_PATHS[-2:]).issubset(
        subject.SEQUENCE_AUTHORITY_PATHS
    )
    assert set(subject.r005_review.CONTROL_PATHS[-2:]).issubset(
        subject.SEQUENCE_AUTHORITY_PATHS
    )
    assert set(subject.r006_review.CONTROL_PATHS[-2:]).issubset(
        subject.SEQUENCE_AUTHORITY_PATHS
    )
    assert set(subject.r007_review.CONTROL_PATHS[-2:]).issubset(
        subject.SEQUENCE_AUTHORITY_PATHS
    )
    assert set(subject.r008_review.CONTROL_PATHS[-2:]).issubset(
        subject.SEQUENCE_AUTHORITY_PATHS
    )
    assert set(subject.r009_review.CONTROL_PATHS[-2:]).issubset(
        subject.SEQUENCE_AUTHORITY_PATHS
    )
    assert set(subject.r010_review.CONTROL_PATHS[-2:]).issubset(
        subject.SEQUENCE_AUTHORITY_PATHS
    )
    assert set(subject.r011_review.CONTROL_PATHS[-2:]).issubset(
        subject.SEQUENCE_AUTHORITY_PATHS
    )


def test_review_inputs_and_outputs_are_dynamic_to_avoid_control_hash_cycle() -> None:
    assert subject.REVIEW_DYNAMIC_PATHS == (
        subject.review_builder.REVIEW_ASSIGNMENT_REL,
        subject.review_builder.REVIEW_RESULT_REL,
        subject.review_builder.INDEPENDENT_REVIEW_REL,
        subject.review_builder.COMPLETION_RECEIPT_REL,
        subject.r004_review.REVIEW_ASSIGNMENT_REL,
        subject.r005_review.REVIEW_ASSIGNMENT_REL,
        subject.r006_review.REVIEW_ASSIGNMENT_REL,
        subject.r007_review.REVIEW_ASSIGNMENT_REL,
        *subject.r008_review.REVIEW_PATHS,
        *subject.r009_review.REVIEW_PATHS,
        *subject.r010_review.REVIEW_PATHS,
        *subject.r011_review.REVIEW_PATHS,
    )
    assert set(subject.REVIEW_DYNAMIC_PATHS).isdisjoint(subject.PINNED_PRODUCTION_PATHS)
    assert all(path.name != "review-attestation.json" for path in subject.PINNED_PRODUCTION_PATHS)


def test_dynamic_review_bytes_are_not_looked_up_in_static_pin_map() -> None:
    raw = {path: b"pinned" for path in subject.PINNED_PRODUCTION_PATHS}
    raw.update({path: b"dynamic" for path in subject.REVIEW_DYNAMIC_PATHS})
    pins = {
        path: subject.bytes_sha256(raw[path])
        for path in subject.PINNED_PRODUCTION_PATHS
    }

    subject._require_pinned_production_hashes(raw, pins)


def test_static_pin_tamper_is_rejected_while_dynamic_bytes_remain_replay_only() -> None:
    raw = {path: b"pinned" for path in subject.PINNED_PRODUCTION_PATHS}
    raw.update({path: b"dynamic" for path in subject.REVIEW_DYNAMIC_PATHS})
    pins = {
        path: subject.bytes_sha256(raw[path])
        for path in subject.PINNED_PRODUCTION_PATHS
    }
    target = subject.PINNED_PRODUCTION_PATHS[0]
    raw[target] = b"tampered"

    with pytest.raises(subject.BuildError, match="production output SHA-256 differs"):
        subject._require_pinned_production_hashes(raw, pins)


def test_review_result_manifest_pin_rejects_different_production_bytes() -> None:
    pins = _pins()
    reviewed = subject._production_pins_from_review_scope(
        _review_result_with_production_pins(pins)
    )
    raw = {path: b"a" for path in subject.PINNED_PRODUCTION_PATHS}
    target = subject.PINNED_PRODUCTION_PATHS[0]
    raw[target] = b"different-after-review"

    with pytest.raises(subject.BuildError, match="production output SHA-256 differs"):
        subject._require_pinned_production_hashes(raw, reviewed)


def test_review_result_pin_inventory_must_cover_each_production_path_once() -> None:
    pins = _pins()
    result = _review_result_with_production_pins(pins)
    scope = result["review_scope"]
    duplicated = deepcopy(scope["reviewed_evidence_manifest"][0])
    scope["reviewed_control_code_cohort"].append(
        {**duplicated, "byte_length": 1}
    )
    scope["reviewed_control_code_cohort_sha256"] = subject.trace.object_sha256(
        scope["reviewed_control_code_cohort"]
    )

    with pytest.raises(subject.BuildError, match="duplicated"):
        subject._production_pins_from_review_scope(result)


def test_default_prepare_uses_review_returned_pins_without_test_override(
    tmp_path: Path,
) -> None:
    root, _managed, evidence = _fixture_root(tmp_path)
    pins = _pins()
    reviewed_evidence = subject.CompletionEvidence(
        documents_by_path=evidence.documents_by_path,
        bindings_by_role=evidence.bindings_by_role,
        update_occurred_at=evidence.update_occurred_at,
        completion_occurred_at=evidence.completion_occurred_at,
        physical_sha256_by_path=evidence.physical_sha256_by_path,
        final_managed_sha256_by_path=evidence.final_managed_sha256_by_path,
        production_sha256_by_path=pins,
    )

    def loader(
        _root: Path,
        _source: dict[str, Any],
        *,
        pins: dict[Path, str] | None,
    ) -> subject.CompletionEvidence:
        assert pins is None
        return reviewed_evidence

    prepared = subject.prepare_projection(
        root,
        evidence_loader=loader,
        runtime_deriver=_runtime_deriver,
        snapshot_hasher=_snapshot_hasher,
        changed_path_loader=lambda _root: {Path("managed.txt")},
        catalog_source_loader=lambda _root: TEST_UNIVERSE,
        catalog_builder=_catalog_builder,
        continuation_checker=lambda _root, _checkpoint: [],
        goal_graph_checker=lambda _root, _checkpoint: [],
    )

    assert prepared.production_sha256_by_path == pins
    assert prepared.test_only_pin_override_used is False


def test_completion_uses_v2_producers_and_pins_v1_only_as_immutable_history() -> None:
    required = {
        subject.trace.V2_OBSERVATION_MANIFEST_REL,
        subject.trace.V2_IMPLEMENTATION_REL,
        subject.trace.V2_VERIFICATION_REL,
        subject.trace.V2_SUCCESSOR_REL,
        subject.trace.V2_REVIEW_SUBJECT_REL,
    }
    v1_history = {
        subject.trace.OBSERVATION_MANIFEST_REL,
        subject.trace.IMPLEMENTATION_REL,
        subject.trace.VERIFICATION_REL,
        subject.trace.SUCCESSOR_REL,
        subject.trace.REVIEW_SUBJECT_REL,
    }
    assert required <= set(subject.PINNED_PRODUCTION_PATHS)
    assert v1_history <= set(subject.review_builder.IMMUTABLE_PREDECESSOR_HISTORY_PATHS)
    assert set(subject.review_builder.IMMUTABLE_PREDECESSOR_HISTORY_PATHS) <= set(
        subject.PINNED_PRODUCTION_PATHS
    )
    assert subject.CANONICAL_PATH_BY_ROLE[subject.COMPLETION_ROLE] == (
        subject.review_builder.COMPLETION_RECEIPT_REL
    )


def test_immutable_history_inventory_is_exactly_20_and_not_auxiliary() -> None:
    history = subject.review_builder.IMMUTABLE_PREDECESSOR_HISTORY_PATHS

    assert len(history) == len(set(history)) == 20
    assert set(history).isdisjoint(subject.AUXILIARY_FINAL_PATHS)
    assert set(history) <= set(subject.PINNED_PRODUCTION_PATHS)


def test_unmanaged_git_visible_path_is_rejected() -> None:
    managed = {Path("managed.txt"): "a" * 64}

    with pytest.raises(subject.BuildError, match="outside the final managed closure"):
        subject._require_git_visible_changes_are_managed(
            ROOT,
            managed,
            changed_path_loader=lambda _root: {
                Path("managed.txt"),
                Path("unapproved.txt"),
            },
        )


def test_checker_authority_paths_are_inside_managed_closure() -> None:
    checker_paths = {
        Path("scripts/check_walksafe_goal_graph_v2_4.py"),
        Path("tests/test_walksafe_goal_graph_v2_4.py"),
    }
    managed = {path: "a" * 64 for path in checker_paths}

    subject._require_git_visible_changes_are_managed(
        ROOT,
        managed,
        changed_path_loader=lambda _root: checker_paths,
    )


def test_strict_review_replay_rejects_live_completion_tamper(tmp_path: Path) -> None:
    assignment_raw = b'{"assignment":true}\n'
    result_raw = b'{"result":true}\n'
    review_text = '{"review":true}\n'
    completion_text = '{"completion":true}\n'
    raw = {
        subject.review_builder.REVIEW_ASSIGNMENT_REL: assignment_raw,
        subject.review_builder.REVIEW_RESULT_REL: result_raw,
        subject.review_builder.INDEPENDENT_REVIEW_REL: review_text.encode(),
        subject.review_builder.COMPLETION_RECEIPT_REL: b'{"completion":"tampered"}\n',
    }
    context = SimpleNamespace()

    with pytest.raises(subject.BuildError, match="strict post-review rebuild differs"):
        subject._replay_strict_review(
            tmp_path,
            raw,
            context_preparer=lambda _root: context,
            review_input_loader=lambda _root, _context: (
                {"assignment": True},
                assignment_raw,
                {"result": True},
                result_raw,
            ),
            post_review_builder=lambda _context, _assignment, _assignment_raw, _result, _result_raw: {
                subject.review_builder.INDEPENDENT_REVIEW_REL: review_text,
                subject.review_builder.COMPLETION_RECEIPT_REL: completion_text,
            },
        )


def test_r016_relation_rejects_credit_tamper() -> None:
    required = {
        subject.trace.V2_IMPLEMENTATION_REL,
        subject.trace.V2_VERIFICATION_REL,
        subject.gap_builder.R027_GAP_JSON_REL,
        *(path for _, path in subject.r016_builder.TARGET_ARTIFACT_PATHS),
        *subject.r016_builder.PREDECESSOR_PATHS,
        *subject.r016_builder.OUTPUT_PATHS,
    }
    raw = {path: subject.trace.read_bytes(ROOT, path) for path in required}
    documents = {
        path: subject.trace.strict_json_bytes(raw[path], path.as_posix())
        for path in (*subject.r016_builder.PREDECESSOR_PATHS, *subject.r016_builder.OUTPUT_PATHS)
    }
    documents[subject.r016_builder.R016_RECEIPT_REL]["summary"][
        "credit_delta_count"
    ] = 1
    raw[subject.r016_builder.R016_RECEIPT_REL] = (
        subject.r016_builder.r015_builder.r014_builder.seal_json(
            documents[subject.r016_builder.R016_RECEIPT_REL],
            subject.r016_builder.R016_RECEIPT_REL,
        )
    )
    documents[subject.r016_builder.R016_RECEIPT_REL] = (
        subject.trace.strict_json_bytes(
            raw[subject.r016_builder.R016_RECEIPT_REL],
            subject.r016_builder.R016_RECEIPT_REL.as_posix(),
        )
    )

    with pytest.raises(subject.BuildError, match="zero-credit relation differs"):
        subject._validate_r016_relation(
            ROOT,
            raw,
            documents,
        )


def test_r016_relation_uses_frozen_documents_without_product_rebuild(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    required = {
        subject.trace.V2_IMPLEMENTATION_REL,
        subject.trace.V2_VERIFICATION_REL,
        subject.gap_builder.R027_GAP_JSON_REL,
        *(path for _, path in subject.r016_builder.TARGET_ARTIFACT_PATHS),
        *subject.r016_builder.PREDECESSOR_PATHS,
        *subject.r016_builder.OUTPUT_PATHS,
    }
    raw = {path: subject.trace.read_bytes(ROOT, path) for path in required}
    documents = {
        path: subject.trace.strict_json_bytes(raw[path], path.as_posix())
        for path in (*subject.r016_builder.PREDECESSOR_PATHS, *subject.r016_builder.OUTPUT_PATHS)
    }
    monkeypatch.setattr(
        subject.r016_builder,
        "build_outputs",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("product rebuild must not run")
        ),
    )

    subject._validate_r016_relation(ROOT, raw, documents)


def test_prepare_rejects_source_race_during_evidence_validation(tmp_path: Path) -> None:
    root, _managed, evidence = _fixture_root(tmp_path)

    def race_loader(*_args: Any, **_kwargs: Any) -> subject.CompletionEvidence:
        checkpoint = root / subject.CHECKPOINT_REL
        checkpoint.write_bytes(checkpoint.read_bytes() + b" ")
        return evidence

    with pytest.raises(subject.BuildError, match="source changed during evidence validation"):
        subject.prepare_projection(
            root,
            production_sha256_by_path=_pins(),
            evidence_loader=race_loader,
            runtime_deriver=_runtime_deriver,
            snapshot_hasher=_snapshot_hasher,
            changed_path_loader=lambda _root: {Path("managed.txt")},
            catalog_source_loader=lambda _root: TEST_UNIVERSE,
            catalog_builder=_catalog_builder,
            continuation_checker=lambda _root, _checkpoint: [],
            goal_graph_checker=lambda _root, _checkpoint: [],
        )


def test_catalog_projection_reaches_q0_c0_q1_c1_fixed_point(tmp_path: Path) -> None:
    root, _managed, evidence = _fixture_root(tmp_path)
    stale_digest = subject.bytes_sha256(b"stale")
    stale_final = dict(evidence.final_managed_sha256_by_path)
    stale_physical = dict(evidence.physical_sha256_by_path)
    for path in subject.CATALOG_PATHS:
        stale_final[path] = stale_digest
        stale_physical[path] = stale_digest
    draft_evidence = subject.CompletionEvidence(
        documents_by_path=evidence.documents_by_path,
        bindings_by_role=evidence.bindings_by_role,
        update_occurred_at=evidence.update_occurred_at,
        completion_occurred_at=evidence.completion_occurred_at,
        physical_sha256_by_path=stale_physical,
        final_managed_sha256_by_path=stale_final,
    )
    observed_snapshot_hashes: list[str] = []

    def builder(
        _root: Path,
        paths: tuple[str, ...],
        *,
        checkpoint_override: dict[str, Any],
    ) -> dict[str, bytes]:
        assert paths == TEST_UNIVERSE
        observed_snapshot_hashes.append(
            checkpoint_override["working_tree_snapshot"]["content_set_sha256"]
        )
        return {path.as_posix(): b"managed" for path in subject.CATALOG_PATHS}

    prepared = subject.prepare_projection(
        root,
        production_sha256_by_path=_pins(),
        evidence_loader=lambda *_args, **_kwargs: draft_evidence,
        runtime_deriver=_runtime_deriver,
        snapshot_hasher=_snapshot_hasher,
        changed_path_loader=lambda _root: {Path("managed.txt")},
        catalog_source_loader=lambda _root: TEST_UNIVERSE,
        catalog_builder=builder,
        continuation_checker=lambda _root, _checkpoint: [],
        goal_graph_checker=lambda _root, _checkpoint: [],
    )

    assert len(observed_snapshot_hashes) == 4
    assert observed_snapshot_hashes[0] != observed_snapshot_hashes[1]
    assert observed_snapshot_hashes[1:] == [observed_snapshot_hashes[1]] * 3
    assert prepared.catalog_source_universe == TEST_UNIVERSE
    assert prepared.catalog_physical_verified is True
    assert all(
        (root / path).read_bytes() == prepared.candidate_catalog_bytes_by_path[path]
        for path in subject.CATALOG_PATHS
    )


def test_non_fixed_point_catalog_projection_is_rejected_before_checkers(
    tmp_path: Path,
) -> None:
    root, _managed, evidence = _fixture_root(tmp_path)
    calls = 0
    checker_called = False

    def alternating_builder(
        _root: Path,
        _paths: tuple[str, ...],
        *,
        checkpoint_override: dict[str, Any],
    ) -> dict[str, bytes]:
        nonlocal calls
        assert checkpoint_override["goal_execution"]["transition_history"][-1]["sequence"] == 62
        calls += 1
        return {
            path.as_posix(): (b"first" if calls == 1 else b"second")
            for path in subject.CATALOG_PATHS
        }

    def checker(_root: Path, _checkpoint: Path) -> list[str]:
        nonlocal checker_called
        checker_called = True
        return []

    with pytest.raises(subject.BuildError, match="did not reach a fixed point"):
        subject.prepare_projection(
            root,
            production_sha256_by_path=_pins(),
            evidence_loader=lambda *_args, **_kwargs: evidence,
            runtime_deriver=_runtime_deriver,
            snapshot_hasher=_snapshot_hasher,
            changed_path_loader=lambda _root: {Path("managed.txt")},
            catalog_source_loader=lambda _root: TEST_UNIVERSE,
            catalog_builder=alternating_builder,
            continuation_checker=checker,
            goal_graph_checker=checker,
        )

    assert checker_called is False


@pytest.mark.parametrize("mode", ["preflight", "write"])
def test_stale_candidate_catalog_blocks_normal_mode_before_checkers(
    tmp_path: Path,
    mode: str,
) -> None:
    root, _managed, evidence = _fixture_root(tmp_path)
    (root / subject.CATALOG_PATHS[0]).write_bytes(b"stale")
    checker_called = False

    def checker(_root: Path, _checkpoint: Path) -> list[str]:
        nonlocal checker_called
        checker_called = True
        return []

    with pytest.raises(subject.BuildError, match="candidate catalog is stale"):
        subject.prepare_projection(
            root,
            production_sha256_by_path=_pins(),
            evidence_loader=lambda *_args, **_kwargs: evidence,
            runtime_deriver=_runtime_deriver,
            snapshot_hasher=_snapshot_hasher,
            changed_path_loader=lambda _root: {Path("managed.txt")},
            catalog_source_loader=lambda _root: TEST_UNIVERSE,
            catalog_builder=_catalog_builder,
            continuation_checker=checker,
            goal_graph_checker=checker,
        )

    assert mode in {"preflight", "write"}
    assert checker_called is False


def test_catalog_source_universe_change_after_checker_is_fail_closed(
    tmp_path: Path,
) -> None:
    root, _managed, evidence = _fixture_root(tmp_path)
    changed = False

    def universe(_root: Path) -> tuple[str, ...]:
        return TEST_UNIVERSE + (("attacker.txt",) if changed else ())

    def continuation(_root: Path, _checkpoint: Path) -> list[str]:
        nonlocal changed
        changed = True
        return []

    with pytest.raises(subject.BuildError, match="source universe changed"):
        subject.prepare_projection(
            root,
            production_sha256_by_path=_pins(),
            evidence_loader=lambda *_args, **_kwargs: evidence,
            runtime_deriver=_runtime_deriver,
            snapshot_hasher=_snapshot_hasher,
            changed_path_loader=lambda _root: {Path("managed.txt")},
            catalog_source_loader=universe,
            catalog_builder=_catalog_builder,
            continuation_checker=continuation,
            goal_graph_checker=lambda _root, _checkpoint: [],
        )


def test_catalog_semantic_input_change_after_checker_is_fail_closed(
    tmp_path: Path,
) -> None:
    root, _managed, evidence = _fixture_root(tmp_path)
    changed = False

    def builder(
        _root: Path,
        paths: tuple[str, ...],
        *,
        checkpoint_override: dict[str, Any],
    ) -> dict[str, bytes]:
        assert paths == TEST_UNIVERSE
        assert checkpoint_override["goal_execution"]["transition_history"][-1]["sequence"] == 62
        raw = b"attacker" if changed else b"managed"
        return {path.as_posix(): raw for path in subject.CATALOG_PATHS}

    def continuation(_root: Path, _checkpoint: Path) -> list[str]:
        nonlocal changed
        changed = True
        return []

    with pytest.raises(subject.BuildError, match="candidate catalog semantics changed"):
        subject.prepare_projection(
            root,
            production_sha256_by_path=_pins(),
            evidence_loader=lambda *_args, **_kwargs: evidence,
            runtime_deriver=_runtime_deriver,
            snapshot_hasher=_snapshot_hasher,
            changed_path_loader=lambda _root: {Path("managed.txt")},
            catalog_source_loader=lambda _root: TEST_UNIVERSE,
            catalog_builder=builder,
            continuation_checker=continuation,
            goal_graph_checker=lambda _root, _checkpoint: [],
        )


def test_explicit_catalog_refresh_safely_records_candidate_bytes(tmp_path: Path) -> None:
    root, _managed, evidence = _fixture_root(tmp_path)
    for path in subject.CATALOG_PATHS:
        (root / path).write_bytes(b"stale")
    refresh = subject.prepare_projection(
        root,
        production_sha256_by_path=_pins(),
        evidence_loader=lambda *_args, **_kwargs: evidence,
        runtime_deriver=_runtime_deriver,
        snapshot_hasher=_snapshot_hasher,
        changed_path_loader=lambda _root: {Path("managed.txt")},
        catalog_source_loader=lambda _root: TEST_UNIVERSE,
        catalog_builder=_catalog_builder,
        continuation_checker=lambda _root, _checkpoint: [],
        goal_graph_checker=lambda _root, _checkpoint: [],
        _allow_stale_catalogs_for_refresh=True,
    )
    writes: list[Path] = []

    def writer(
        path: Path,
        content: bytes,
        *,
        expected_source: bytes,
        commit_guard: Callable[[], None],
    ) -> None:
        assert path.read_bytes() == expected_source
        commit_guard()
        path.write_bytes(content)
        commit_guard()
        writes.append(path.relative_to(root))

    subject.write_candidate_catalogs(
        refresh,
        catalog_source_loader=lambda _root: TEST_UNIVERSE,
        catalog_builder=_catalog_builder,
        atomic_writer=writer,
    )

    assert writes == list(subject.CATALOG_PATHS)
    assert all((root / path).read_bytes() == b"managed" for path in subject.CATALOG_PATHS)


def test_write_commit_guard_rejects_live_managed_source_race(tmp_path: Path) -> None:
    prepared, managed, evidence = _prepare_fixture(tmp_path)

    def racing_writer(
        _path: Path,
        _content: bytes,
        *,
        expected_source: bytes,
        commit_guard: Callable[[], None],
    ) -> None:
        assert expected_source == prepared.source_bytes
        managed.write_bytes(b"tampered")
        commit_guard()

    with pytest.raises((subject.BuildError, subject.CompletionApplyError)):
        subject.write_projection(
            prepared,
            evidence_loader=lambda *_args, **_kwargs: evidence,
            runtime_deriver=_runtime_deriver,
            snapshot_hasher=_snapshot_hasher,
            changed_path_loader=lambda _root: {Path("managed.txt")},
            catalog_source_loader=lambda _root: TEST_UNIVERSE,
            catalog_builder=_catalog_builder,
            continuation_checker=lambda _root, _checkpoint: [],
            goal_graph_checker=lambda _root, _checkpoint: [],
            atomic_writer=racing_writer,
        )


def test_write_commit_guard_rejects_catalog_universe_race_before_checker(
    tmp_path: Path,
) -> None:
    prepared, _managed, evidence = _prepare_fixture(tmp_path)
    changed = False
    checker_calls = 0

    def universe(_root: Path) -> tuple[str, ...]:
        return TEST_UNIVERSE + (("attacker.txt",) if changed else ())

    def checker(_root: Path, _checkpoint: Path) -> list[str]:
        nonlocal checker_calls
        checker_calls += 1
        return []

    def racing_writer(
        _path: Path,
        _content: bytes,
        *,
        expected_source: bytes,
        commit_guard: Callable[[], None],
    ) -> None:
        nonlocal changed
        assert expected_source == prepared.source_bytes
        changed = True
        commit_guard()

    with pytest.raises(subject.BuildError, match="source universe changed"):
        subject.write_projection(
            prepared,
            evidence_loader=lambda *_args, **_kwargs: evidence,
            runtime_deriver=_runtime_deriver,
            snapshot_hasher=_snapshot_hasher,
            changed_path_loader=lambda _root: {Path("managed.txt")},
            catalog_source_loader=universe,
            catalog_builder=_catalog_builder,
            continuation_checker=checker,
            goal_graph_checker=checker,
            atomic_writer=racing_writer,
        )

    assert checker_calls == 2


def test_checkpoint_transport_stage_is_narrowly_excluded_at_commit(
    tmp_path: Path,
) -> None:
    checkpoint = tmp_path / subject.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    source = b"source-checkpoint\n"
    projected = b"projected-checkpoint\n"
    checkpoint.write_bytes(source)
    checkpoint.chmod(0o600)
    stage = checkpoint.parent / (
        ".walksafe-project-continuation-checkpoint.json.fp048-seq43-44."
        "0123456789abcdef01234567.tmp"
    )
    stage.write_bytes(projected)
    stage.chmod(0o600)
    stage_relative = stage.relative_to(tmp_path)
    universe = (
        subject.CHECKPOINT_REL.as_posix(),
        stage_relative.as_posix(),
        "managed.txt",
    )

    assert subject._catalog_source_universe_at_checkpoint_commit(
        tmp_path,
        source,
        projected,
        loader=lambda _root: universe,
    ) == (subject.CHECKPOINT_REL.as_posix(), "managed.txt")
    assert subject._git_visible_paths_at_checkpoint_commit(
        tmp_path,
        source,
        projected,
        loader=lambda _root: {Path("managed.txt")},
    ) == {Path("managed.txt")}

    with pytest.raises(subject.BuildError, match="leaked into Git-visible"):
        subject._git_visible_paths_at_checkpoint_commit(
            tmp_path,
            source,
            projected,
            loader=lambda _root: {stage_relative, Path("managed.txt")},
        )


def test_checkpoint_transport_stage_tamper_or_alias_is_rejected(
    tmp_path: Path,
) -> None:
    checkpoint = tmp_path / subject.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    source = b"source-checkpoint\n"
    projected = b"projected-checkpoint\n"
    checkpoint.write_bytes(source)
    checkpoint.chmod(0o600)
    stage = checkpoint.parent / (
        ".walksafe-project-continuation-checkpoint.json.fp048-seq43-44."
        "0123456789abcdef01234567.tmp"
    )
    stage.write_bytes(b"attacker\n")
    stage.chmod(0o600)
    with pytest.raises(subject.BuildError, match="stage bytes differ"):
        subject._validated_checkpoint_transport_stage(
            tmp_path,
            source,
            projected,
        )

    stage.write_bytes(projected)
    alias = checkpoint.parent / (
        ".walksafe-project-continuation-checkpoint.json.fp048-seq43-44."
        "89abcdef0123456789abcdef.tmp"
    )
    alias.write_bytes(projected)
    alias.chmod(0o600)
    with pytest.raises(subject.BuildError, match="stage inventory differs"):
        subject._validated_checkpoint_transport_stage(
            tmp_path,
            source,
            projected,
        )


def test_production_write_reloads_and_rejects_review_pin_authority_race(
    tmp_path: Path,
) -> None:
    root, _managed, evidence = _fixture_root(tmp_path)
    pins = _pins()
    reviewed_evidence = subject.CompletionEvidence(
        documents_by_path=evidence.documents_by_path,
        bindings_by_role=evidence.bindings_by_role,
        update_occurred_at=evidence.update_occurred_at,
        completion_occurred_at=evidence.completion_occurred_at,
        physical_sha256_by_path=evidence.physical_sha256_by_path,
        final_managed_sha256_by_path=evidence.final_managed_sha256_by_path,
        production_sha256_by_path=pins,
    )

    def loader(
        _root: Path,
        _source: dict[str, Any],
        *,
        pins: dict[Path, str] | None,
    ) -> subject.CompletionEvidence:
        assert pins is None
        return reviewed_evidence

    prepared = subject.prepare_projection(
        root,
        evidence_loader=loader,
        runtime_deriver=_runtime_deriver,
        snapshot_hasher=_snapshot_hasher,
        changed_path_loader=lambda _root: {Path("managed.txt")},
        catalog_source_loader=lambda _root: TEST_UNIVERSE,
        catalog_builder=_catalog_builder,
        continuation_checker=lambda _root, _checkpoint: [],
        goal_graph_checker=lambda _root, _checkpoint: [],
    )
    changed_pins = dict(pins)
    changed_pins[subject.PINNED_PRODUCTION_PATHS[0]] = "b" * 64

    def writer(
        _path: Path,
        _content: bytes,
        *,
        expected_source: bytes,
        commit_guard: Callable[[], None],
    ) -> None:
        assert expected_source == prepared.source_bytes
        commit_guard()

    with pytest.raises(
        subject.BuildError,
        match="reviewed production pin authority changed at commit",
    ):
        subject.write_projection(
            prepared,
            evidence_loader=loader,
            runtime_deriver=_runtime_deriver,
            snapshot_hasher=_snapshot_hasher,
            changed_path_loader=lambda _root: {Path("managed.txt")},
            catalog_source_loader=lambda _root: TEST_UNIVERSE,
            catalog_builder=_catalog_builder,
            continuation_checker=lambda _root, _checkpoint: [],
            goal_graph_checker=lambda _root, _checkpoint: [],
            review_pin_loader=lambda _root: changed_pins,
            atomic_writer=writer,
        )


def test_production_transport_reuses_fp046_hardened_atomic_writer() -> None:
    assert subject.atomic_write is subject.fp046_apply.atomic_write
