from __future__ import annotations

import copy
import json
import os
from pathlib import Path
import subprocess

import pytest

from scripts import (
    apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826
    as reanchor,
)


ROOT = Path(__file__).resolve().parents[1]


def _source() -> tuple[bytes, dict[str, object]]:
    return reanchor.load_exact_source_checkpoint(ROOT)


def _init_git_repo(root: Path) -> None:
    subprocess.run(["git", "init", "-q"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.name", "Seq90 Test"], cwd=root, check=True)
    subprocess.run(
        ["git", "config", "user.email", "seq90-test@example.invalid"],
        cwd=root,
        check=True,
    )


def _commit_all(root: Path, message: str) -> None:
    subprocess.run(["git", "add", "--all"], cwd=root, check=True)
    subprocess.run(["git", "commit", "-q", "-m", message], cwd=root, check=True)


def _skip_unless_review_triad_exists() -> None:
    if any(not (ROOT / path).is_file() for path in reanchor.REVIEW_PATHS):
        pytest.skip("R002 reviewer-authored triad has not been published")


def test_r002_contract_is_exact_five_check_successor() -> None:
    document, binding = reanchor.load_r002_contract(ROOT)
    assert tuple(row["check_id"] for row in document["ordered_checks"]) == (
        reanchor.R002_CONTRACT_CHECK_IDS
    )
    assert binding == reanchor.r002_contract_binding(ROOT)
    assert document["claim_boundary"]["goal_started"] is False
    assert document["claim_boundary"]["product_implementation_credit_delta"] == 0


def test_noncredit_successor_roots_are_exact_modified_and_added() -> None:
    _raw, source = _source()
    edges = reanchor.compute_noncredit_successor_edges(ROOT, source)
    assert [row["path"] for row in edges["modified"]] == sorted(
        reanchor.SOURCE_PRODUCT_PREDECESSORS
    )
    assert len(edges["modified"]) == 32
    assert [row["path"] for row in edges["added"]] == [
        "apps/android-gateway/test/state-encryption-maintenance.test.ts",
        "tests/test_account_deletion_worker_operations.py",
        "tests/test_walksafe_backup_operations.py",
    ]
    assert reanchor._edge_set_sha256(edges) == (
        "bebe6ecf211f92fb1aaf934a72989b2930b15d2d164e1829e68478b360514c21"
    )
    assert {
        row["path"]: row["predecessor"] for row in edges["modified"]
    } == reanchor.SOURCE_PRODUCT_PREDECESSORS
    assert all(
        row["predecessor"]["sha256"] != row["successor"]["sha256"]
        or row["predecessor"]["byte_length"] != row["successor"]["byte_length"]
        for row in edges["modified"]
    )


def test_seq85_to_seq87_reviewed_successor_edges_are_exact() -> None:
    _raw, source = _source()
    edges = reanchor.validated_seq85_to_seq87_successor_edges(ROOT, source)
    assert len(edges["modified"]) == 11
    assert len(edges["added"]) == 4
    by_path = {row["path"]: row for row in edges["modified"]}
    assert by_path["backend/app/api/health.py"] == {
        "path": "backend/app/api/health.py",
        "predecessor": {
            "path": "backend/app/api/health.py",
            "sha256": (
                "5d4f29aae15af069dff62a466b4dbebc88a886f3322ee5de5aa0529639be3401"
            ),
            "byte_length": 19_738,
        },
        "scope": "CONCURRENT_LIVE_MANAGED_NONCREDIT",
        "successor": reanchor.SOURCE_PRODUCT_PREDECESSORS[
            "backend/app/api/health.py"
        ],
    }
    assert by_path["backend/tests/test_fp046_postgres_integration.py"][
        "successor"
    ] == reanchor.SOURCE_PRODUCT_PREDECESSORS[
        "backend/tests/test_fp046_postgres_integration.py"
    ]


def test_persisted_managed_cohort_excludes_direct_gate_evidence() -> None:
    _raw, source = _source()
    gate_path = (
        "docs/control/execution/goal-gates/"
        "WS-BURNED-EVENT/01-CONTINUATION.log"
    )
    ordinary_path = reanchor.R003_REVIEW_ASSIGNMENT_REL.as_posix()
    paths = reanchor._final_managed_paths(source, [gate_path, ordinary_path])
    assert gate_path not in paths
    assert ordinary_path in paths


def test_exact_source_cas_rejects_source_drift() -> None:
    raw, source = _source()
    tampered = copy.deepcopy(source)
    tampered["current_work"]["status"] = "IN_PROGRESS"
    with pytest.raises(reanchor.ControlReanchorError, match="source checkpoint"):
        reanchor.require_exact_source(reanchor.checkpoint_json_bytes(tampered), tampered)
    with pytest.raises(reanchor.ControlReanchorError, match="source checkpoint"):
        reanchor.require_exact_source(raw + b" ", source)


@pytest.mark.parametrize("drift", ["decision", "reviewer", "extra_field"])
def test_review_drift_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
    drift: str,
) -> None:
    _skip_unless_review_triad_exists()
    original = reanchor._stable_read

    def drift(root: Path, relative: Path) -> reanchor.ReadResult:
        observed = original(root, relative)
        if relative == reanchor.REVIEW_RESULT_REL:
            value = json.loads(observed.raw)
            if drift == "decision":
                value["decision"] = "REJECT"
            elif drift == "reviewer":
                value["reviewer"]["id"] = "unexpected-reviewer"
            else:
                value["unreviewed_extra"] = True
            return reanchor.ReadResult(
                reanchor.canonical_json_bytes(value),
                observed.identity,
            )
        return observed

    monkeypatch.setattr(reanchor, "_stable_read", drift)
    with pytest.raises(reanchor.ControlReanchorError, match="review"):
        reanchor._load_physical_review(ROOT)


@pytest.mark.parametrize("field", ["projected_transition", "review_scope"])
def test_assignment_scope_drift_is_rejected_after_consistent_rebinding(
    monkeypatch: pytest.MonkeyPatch,
    field: str,
) -> None:
    _skip_unless_review_triad_exists()
    original = reanchor._stable_read
    observed = {path: original(ROOT, path) for path in reanchor.REVIEW_PATHS}
    assignment = json.loads(observed[reanchor.REVIEW_ASSIGNMENT_REL].raw)
    assignment[field] = {} if field == "projected_transition" else []
    assignment_raw = reanchor.canonical_json_bytes(assignment)
    result = json.loads(observed[reanchor.REVIEW_RESULT_REL].raw)
    result["assignment_binding"] = reanchor._binding(
        reanchor.REVIEW_ASSIGNMENT_REL,
        assignment_raw,
    )
    result_raw = reanchor.canonical_json_bytes(result)
    independent = json.loads(observed[reanchor.INDEPENDENT_REVIEW_REL].raw)
    independent["assignment_binding"] = result["assignment_binding"]
    independent["review_result_binding"] = reanchor._binding(
        reanchor.REVIEW_RESULT_REL,
        result_raw,
    )
    replacements = {
        reanchor.REVIEW_ASSIGNMENT_REL: assignment_raw,
        reanchor.REVIEW_RESULT_REL: result_raw,
        reanchor.INDEPENDENT_REVIEW_REL: reanchor.canonical_json_bytes(independent),
    }

    def drift(root: Path, relative: Path) -> reanchor.ReadResult:
        value = original(root, relative)
        if relative in replacements:
            return reanchor.ReadResult(replacements[relative], value.identity)
        return value

    monkeypatch.setattr(reanchor, "_stable_read", drift)
    with pytest.raises(reanchor.ControlReanchorError, match="assignment authority"):
        reanchor._load_physical_review(ROOT)


def test_same_byte_aba_replacement_is_rejected(tmp_path: Path) -> None:
    target = tmp_path / "input.json"
    target.write_bytes(b"{}\n")
    retained = {Path("input.json"): reanchor._stable_read(tmp_path, Path("input.json"))}
    replacement = tmp_path / "replacement.json"
    replacement.write_bytes(b"{}\n")
    os.replace(replacement, target)
    with pytest.raises(reanchor.ControlReanchorError, match="ABA"):
        reanchor._require_inputs_unchanged(tmp_path, retained)


def test_git_visible_rename_is_rejected() -> None:
    with pytest.raises(reanchor.ControlReanchorError, match="rename is unsupported"):
        reanchor._git_visible_paths(b"R  renamed.txt\0original.txt\0")


def test_post_consumer_reseal_rejects_same_byte_input_replacement(
    tmp_path: Path,
) -> None:
    _init_git_repo(tmp_path)
    checkpoint = tmp_path / reanchor.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(b'{"source":true}\n')
    control_relative = Path("review/control.json")
    control = tmp_path / control_relative
    control.parent.mkdir(parents=True)
    control.write_bytes(b"{}\n")
    _commit_all(tmp_path, "source")
    source = reanchor._stable_read(tmp_path, reanchor.CHECKPOINT_REL)
    retained = {control_relative: reanchor._stable_read(tmp_path, control_relative)}
    managed = reanchor._capture_managed_inputs(
        tmp_path,
        [control_relative.as_posix()],
    )
    status_raw, visible = reanchor.capture_git_visible_paths(tmp_path)
    assert visible == ()
    git_head, git_branch = reanchor._capture_git_context(tmp_path)
    reanchor._require_preflight_cohort_unchanged(
        tmp_path,
        source=source,
        retained=retained,
        managed=managed,
        git_visible={},
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
        phase="after projected consumer validation",
    )
    replacement = tmp_path / "replacement.json"
    replacement.write_bytes(control.read_bytes())
    os.replace(replacement, control)
    assert reanchor.capture_git_visible_paths(tmp_path)[0] == status_raw
    with pytest.raises(reanchor.ControlReanchorError, match="ABA"):
        reanchor._require_preflight_cohort_unchanged(
            tmp_path,
            source=source,
            retained=retained,
            managed=managed,
            git_visible={},
            git_status_raw=status_raw,
            git_head=git_head,
            git_branch=git_branch,
            phase="after projected consumer validation",
        )


def test_projected_consumers_skip_duplicate_continuation_validation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from scripts import check_walksafe_goal_graph_v2_4 as goal_graph

    checkpoint_parent = tmp_path / reanchor.CHECKPOINT_REL.parent
    checkpoint_parent.mkdir(parents=True)
    calls: list[tuple[str, object]] = []

    def validate_continuation(
        root: Path,
        checkpoint: Path,
        archive: Path,
        manifest: Path,
    ) -> list[str]:
        calls.append(("continuation", checkpoint))
        assert root == tmp_path
        assert archive == reanchor.continuation.V23_ARCHIVE_RELATIVE
        assert manifest == reanchor.continuation.V24_MANIFEST_RELATIVE
        assert (root / checkpoint).is_file()
        return []

    def validate_goal_graph(
        root: Path,
        checkpoint: Path,
        **kwargs: object,
    ) -> list[str]:
        calls.append(("goal_graph", kwargs))
        assert root == tmp_path
        assert (root / checkpoint).is_file()
        return []

    monkeypatch.setattr(reanchor.continuation, "validate", validate_continuation)
    monkeypatch.setattr(goal_graph, "validate", validate_goal_graph)

    reanchor._validate_projected_with_consumers(tmp_path, {"projected": True})

    assert [name for name, _value in calls] == ["continuation", "goal_graph"]
    assert calls[1][1] == {"check_continuation": False}
    assert not list(
        checkpoint_parent.glob(".walksafe-fp048-r002-seq90-preflight.*.json")
    )


@pytest.mark.parametrize(
    ("failing_consumer", "message", "expected_goal_graph_calls"),
    [
        ("continuation", "projected continuation failed: continuation error", 0),
        ("goal_graph", "projected Goal graph failed: Goal graph error", 1),
    ],
)
def test_projected_consumer_errors_propagate_and_remove_temporary_checkpoint(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    failing_consumer: str,
    message: str,
    expected_goal_graph_calls: int,
) -> None:
    from scripts import check_walksafe_goal_graph_v2_4 as goal_graph

    checkpoint_parent = tmp_path / reanchor.CHECKPOINT_REL.parent
    checkpoint_parent.mkdir(parents=True)
    continuation_calls = 0
    goal_graph_calls = 0

    def validate_continuation(*_args: object, **_kwargs: object) -> list[str]:
        nonlocal continuation_calls
        continuation_calls += 1
        return ["continuation error"] if failing_consumer == "continuation" else []

    def validate_goal_graph(
        *_args: object,
        **kwargs: object,
    ) -> list[str]:
        nonlocal goal_graph_calls
        goal_graph_calls += 1
        assert kwargs == {"check_continuation": False}
        return ["Goal graph error"] if failing_consumer == "goal_graph" else []

    monkeypatch.setattr(reanchor.continuation, "validate", validate_continuation)
    monkeypatch.setattr(goal_graph, "validate", validate_goal_graph)

    with pytest.raises(reanchor.ControlReanchorError, match=message):
        reanchor._validate_projected_with_consumers(tmp_path, {"projected": True})

    assert continuation_calls == 1
    assert goal_graph_calls == expected_goal_graph_calls
    assert not list(
        checkpoint_parent.glob(".walksafe-fp048-r002-seq90-preflight.*.json")
    )


@pytest.mark.parametrize(
    "rejected_assignment",
    [
        reanchor.R001_REVIEW_ASSIGNMENT_REL,
        reanchor.R002_REVIEW_ASSIGNMENT_REL,
        reanchor.R003_REVIEW_ASSIGNMENT_REL,
        reanchor.R004_REVIEW_ASSIGNMENT_REL,
    ],
)
def test_rejected_assignment_chain_drift_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
    rejected_assignment: Path,
) -> None:
    _raw, source = _source()
    original = reanchor._stable_read

    def drift(root: Path, relative: Path) -> reanchor.ReadResult:
        observed = original(root, relative)
        if relative == rejected_assignment:
            return reanchor.ReadResult(b"{}\n", observed.identity)
        return observed

    monkeypatch.setattr(reanchor, "_stable_read", drift)
    with pytest.raises(reanchor.ControlReanchorError, match="assignment"):
        reanchor.build_review_assignment(ROOT, source)


def test_credit_change_is_rejected() -> None:
    _raw, source = _source()
    projected = copy.deepcopy(source)
    projected["approved_state"]["release_status"] = "ELIGIBLE"
    with pytest.raises(reanchor.ControlReanchorError, match="zero-credit"):
        reanchor._assert_zero_credit_projection(source, projected)


def test_exact_source_projection_accepts_array_canonical_bindings() -> None:
    _raw, source = _source()
    assert type(source["canonical_bindings"]) is list
    projection = reanchor._unchanged_projection(source)
    assert projection["canonical_bindings"] == (
        reanchor.continuation.canonical_json_sha256(source["canonical_bindings"])
    )


def test_active_work_uses_frozen_seq90_edge_only_after_exact_seq91_anchor() -> None:
    assert reanchor._live_successor_bytes_required([{}] * 90) is True
    control = {"event_sha256": "a" * 64}
    started = {
        "sequence": 91,
        "event_id": reanchor.STARTED_EVENT_ID,
        "event_type": "GOAL_STARTED",
        "subject_goal_id": reanchor.GOAL_ID,
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "status_changes": {reanchor.GOAL_ID: "IN_PROGRESS"},
        "previous_event_sha256": control["event_sha256"],
    }
    started["event_sha256"] = reanchor.continuation.event_sha256(started)
    history = [{}] * 89 + [control, started]
    assert reanchor._live_successor_bytes_required(history) is False
    started["previous_event_sha256"] = "b" * 64
    with pytest.raises(reanchor.ControlReanchorError, match="active-work anchor"):
        reanchor._live_successor_bytes_required(history)


def test_review_manifest_is_read_only_and_review_bound() -> None:
    before = (ROOT / reanchor.CHECKPOINT_REL).read_bytes()
    manifest = reanchor.prepare_review_manifest(ROOT)
    after = (ROOT / reanchor.CHECKPOINT_REL).read_bytes()
    assert before == after
    assert manifest["status"] == "PREPARED_NOT_PUBLISHED"
    assert manifest["noncredit_successor_edges"] == (
        reanchor.compute_noncredit_successor_edges(ROOT, _source()[1])
    )


def test_seq90_projection_is_ready_to_ready_and_zero_credit() -> None:
    _skip_unless_review_triad_exists()
    _raw, source = _source()
    _status_raw, visible = reanchor.capture_git_visible_paths(ROOT)
    projected, event = reanchor.project_seq90(
        ROOT,
        source,
        git_visible=visible,
        occurred_at="2026-08-26T03:02:00+09:00",
        final_hashes=("1" * 64, "2" * 64),
    )
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert event["claim_boundary"] == reanchor.CLAIM_BOUNDARY
    assert projected["goal_execution"]["status_by_goal"][reanchor.GOAL_ID] == "READY"
    assert projected["current_work"]["status"] == "READY"
    assert projected["approved_state"] == source["approved_state"]
    assert projected["verification_boundary"] == source["verification_boundary"]


def test_cli_requires_an_explicit_mode() -> None:
    with pytest.raises(SystemExit):
        reanchor.parse_args([])


def test_live_seq90_reanchor_checkpoint_is_valid() -> None:
    checkpoint = json.loads((ROOT / reanchor.CHECKPOINT_REL).read_bytes())
    history = checkpoint["goal_execution"]["transition_history"]
    if len(history) < reanchor.CONTROL_REANCHOR_SEQUENCE:
        pytest.skip("seq90 has not been published")
    reanchor.require_control_reanchored_checkpoint(ROOT, checkpoint)


def test_write_uses_checkpoint_inode_lock_without_git_visible_lock(
    tmp_path: Path,
) -> None:
    _init_git_repo(tmp_path)
    target = tmp_path / reanchor.CHECKPOINT_REL
    target.parent.mkdir(parents=True)
    source_raw = b'{"source":true}\n'
    target.write_bytes(source_raw)
    _commit_all(tmp_path, "source")
    source = reanchor._stable_read(tmp_path, reanchor.CHECKPOINT_REL)
    status_raw, _paths = reanchor.capture_git_visible_paths(tmp_path)
    git_head, git_branch = reanchor._capture_git_context(tmp_path)
    prepared = reanchor.Prepared(
        root=tmp_path,
        source_raw=source_raw,
        source_identity=source.identity,
        source={},
        projected={},
        projected_raw=b'{"projected":true}\n',
        event={},
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    reanchor.write_checkpoint(prepared)
    assert target.read_bytes() == prepared.projected_raw
    assert not list(tmp_path.rglob("*.lock"))


def test_write_rejects_managed_byte_drift_with_unchanged_git_status(
    tmp_path: Path,
) -> None:
    _init_git_repo(tmp_path)
    target = tmp_path / reanchor.CHECKPOINT_REL
    target.parent.mkdir(parents=True)
    source_raw = b'{"source":true}\n'
    target.write_bytes(source_raw)
    payload = tmp_path / "managed.txt"
    payload.write_bytes(b"version-one\n")
    _commit_all(tmp_path, "source")
    payload.write_bytes(b"version-two\n")
    source = reanchor._stable_read(tmp_path, reanchor.CHECKPOINT_REL)
    status_raw, _paths = reanchor.capture_git_visible_paths(tmp_path)
    managed = reanchor._capture_managed_inputs(tmp_path, ["managed.txt"])
    git_head, git_branch = reanchor._capture_git_context(tmp_path)
    prepared = reanchor.Prepared(
        root=tmp_path,
        source_raw=source_raw,
        source_identity=source.identity,
        source={},
        projected={},
        projected_raw=b'{"projected":true}\n',
        event={},
        retained_inputs={},
        managed_inputs=managed,
        git_visible_inputs={},
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    payload.write_bytes(b"version-six\n")
    assert reanchor.capture_git_visible_paths(tmp_path)[0] == status_raw
    with pytest.raises(reanchor.ControlReanchorError, match="final managed input"):
        reanchor.write_checkpoint(prepared)
    assert target.read_bytes() == source_raw


def test_write_rejects_excluded_gate_byte_drift_with_unchanged_git_status(
    tmp_path: Path,
) -> None:
    _init_git_repo(tmp_path)
    target = tmp_path / reanchor.CHECKPOINT_REL
    target.parent.mkdir(parents=True)
    source_raw = b'{"source":true}\n'
    target.write_bytes(source_raw)
    _commit_all(tmp_path, "source")
    gate_relative = Path(
        "docs/control/execution/goal-gates/WS-BURNED-EVENT/01-CONTINUATION.log"
    )
    gate = tmp_path / gate_relative
    gate.parent.mkdir(parents=True)
    gate.write_bytes(b"failed-one\n")
    source = reanchor._stable_read(tmp_path, reanchor.CHECKPOINT_REL)
    status_raw, visible = reanchor.capture_git_visible_paths(tmp_path)
    assert gate_relative.as_posix() in visible
    assert gate_relative.as_posix() not in reanchor._final_managed_paths(
        {"working_tree_snapshot": {"managed_changed_paths": []}},
        visible,
    )
    git_visible_inputs = reanchor._capture_managed_inputs(tmp_path, visible)
    git_head, git_branch = reanchor._capture_git_context(tmp_path)
    prepared = reanchor.Prepared(
        root=tmp_path,
        source_raw=source_raw,
        source_identity=source.identity,
        source={},
        projected={},
        projected_raw=b'{"projected":true}\n',
        event={},
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs=git_visible_inputs,
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    gate.write_bytes(b"failed-two\n")
    assert reanchor.capture_git_visible_paths(tmp_path)[0] == status_raw
    with pytest.raises(reanchor.ControlReanchorError, match="full Git-visible input"):
        reanchor.write_checkpoint(prepared)
    assert target.read_bytes() == source_raw


def test_write_rejects_retained_same_byte_aba_at_commit_point(
    tmp_path: Path,
) -> None:
    _init_git_repo(tmp_path)
    target = tmp_path / reanchor.CHECKPOINT_REL
    target.parent.mkdir(parents=True)
    source_raw = b'{"source":true}\n'
    target.write_bytes(source_raw)
    retained_relative = Path("review/rejected-assignment.json")
    retained_path = tmp_path / retained_relative
    retained_path.parent.mkdir(parents=True)
    retained_path.write_bytes(b"same-reviewed-bytes\n")
    _commit_all(tmp_path, "source")
    source = reanchor._stable_read(tmp_path, reanchor.CHECKPOINT_REL)
    retained = {
        retained_relative: reanchor._stable_read(tmp_path, retained_relative)
    }
    status_raw, _visible = reanchor.capture_git_visible_paths(tmp_path)
    git_head, git_branch = reanchor._capture_git_context(tmp_path)
    prepared = reanchor.Prepared(
        root=tmp_path,
        source_raw=source_raw,
        source_identity=source.identity,
        source={},
        projected={},
        projected_raw=b'{"projected":true}\n',
        event={},
        retained_inputs=retained,
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    replacement = tmp_path / "replacement.json"
    replacement.write_bytes(retained_path.read_bytes())
    os.replace(replacement, retained_path)
    assert reanchor.capture_git_visible_paths(tmp_path)[0] == status_raw
    with pytest.raises(reanchor.ControlReanchorError, match="review input.*ABA"):
        reanchor.write_checkpoint(prepared)
    assert target.read_bytes() == source_raw


def test_commit_guard_failure_preserves_exact_source_bytes(
    tmp_path: Path,
) -> None:
    _init_git_repo(tmp_path)
    target = tmp_path / reanchor.CHECKPOINT_REL
    target.parent.mkdir(parents=True)
    source_raw = b'{"source":true}\n'
    target.write_bytes(source_raw)
    _commit_all(tmp_path, "source")
    source = reanchor._stable_read(tmp_path, reanchor.CHECKPOINT_REL)
    status_raw, _visible = reanchor.capture_git_visible_paths(tmp_path)
    git_head, git_branch = reanchor._capture_git_context(tmp_path)
    prepared = reanchor.Prepared(
        root=tmp_path,
        source_raw=source_raw,
        source_identity=source.identity,
        source={},
        projected={},
        projected_raw=b'{"projected":true}\n',
        event={},
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    calls = 0

    def reject_commit() -> None:
        nonlocal calls
        calls += 1
        raise reanchor.ControlReanchorError("commit guard rejected mutation")

    with pytest.raises(reanchor.ControlReanchorError, match="commit guard"):
        reanchor.write_checkpoint(prepared, commit_guard=reject_commit)
    assert calls == 1
    assert target.read_bytes() == source_raw
    assert not list(
        target.parent.glob(".walksafe-fp048-r002-seq90-write.*.json")
    )


def test_write_reports_postcommit_uncertain_on_unlock_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _init_git_repo(tmp_path)
    target = tmp_path / reanchor.CHECKPOINT_REL
    target.parent.mkdir(parents=True)
    source_raw = b'{"source":true}\n'
    projected_raw = b'{"projected":true}\n'
    target.write_bytes(source_raw)
    _commit_all(tmp_path, "source")
    source = reanchor._stable_read(tmp_path, reanchor.CHECKPOINT_REL)
    status_raw, _visible = reanchor.capture_git_visible_paths(tmp_path)
    git_head, git_branch = reanchor._capture_git_context(tmp_path)
    prepared = reanchor.Prepared(
        root=tmp_path,
        source_raw=source_raw,
        source_identity=source.identity,
        source={},
        projected={},
        projected_raw=projected_raw,
        event={},
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    original_flock = reanchor.fcntl.flock

    def fail_unlock(descriptor: int, operation: int) -> None:
        if operation == reanchor.fcntl.LOCK_UN:
            raise OSError("simulated unlock failure")
        original_flock(descriptor, operation)

    monkeypatch.setattr(reanchor.fcntl, "flock", fail_unlock)
    with pytest.raises(reanchor.PostcommitUncertain, match="cleanup is uncertain"):
        reanchor.write_checkpoint(prepared)
    assert target.read_bytes() == projected_raw


def test_write_reports_postcommit_uncertain_when_replace_returns_by_exception(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _init_git_repo(tmp_path)
    target = tmp_path / reanchor.CHECKPOINT_REL
    target.parent.mkdir(parents=True)
    source_raw = b'{"source":true}\n'
    projected_raw = b'{"projected":true}\n'
    target.write_bytes(source_raw)
    _commit_all(tmp_path, "source")
    source = reanchor._stable_read(tmp_path, reanchor.CHECKPOINT_REL)
    status_raw, _visible = reanchor.capture_git_visible_paths(tmp_path)
    git_head, git_branch = reanchor._capture_git_context(tmp_path)
    prepared = reanchor.Prepared(
        root=tmp_path,
        source_raw=source_raw,
        source_identity=source.identity,
        source={},
        projected={},
        projected_raw=projected_raw,
        event={},
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    original_replace = reanchor.os.replace

    def replace_then_interrupt(source_path: Path, target_path: Path) -> None:
        original_replace(source_path, target_path)
        raise KeyboardInterrupt("simulated post-replace interruption")

    monkeypatch.setattr(reanchor.os, "replace", replace_then_interrupt)
    with pytest.raises(reanchor.PostcommitUncertain, match="uncertain reporting"):
        reanchor.write_checkpoint(prepared)
    assert target.read_bytes() == projected_raw


@pytest.mark.parametrize("drift", ["head", "branch"])
def test_write_rejects_head_or_branch_drift(tmp_path: Path, drift: str) -> None:
    _init_git_repo(tmp_path)
    target = tmp_path / reanchor.CHECKPOINT_REL
    target.parent.mkdir(parents=True)
    source_raw = b'{"source":true}\n'
    target.write_bytes(source_raw)
    _commit_all(tmp_path, "source")
    source = reanchor._stable_read(tmp_path, reanchor.CHECKPOINT_REL)
    status_raw, _paths = reanchor.capture_git_visible_paths(tmp_path)
    git_head, git_branch = reanchor._capture_git_context(tmp_path)
    prepared = reanchor.Prepared(
        root=tmp_path,
        source_raw=source_raw,
        source_identity=source.identity,
        source={},
        projected={},
        projected_raw=b'{"projected":true}\n',
        event={},
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    if drift == "head":
        subprocess.run(
            ["git", "commit", "-q", "--allow-empty", "-m", "move head"],
            cwd=tmp_path,
            check=True,
        )
    else:
        subprocess.run(["git", "branch", "-m", "moved-branch"], cwd=tmp_path, check=True)
    assert reanchor.capture_git_visible_paths(tmp_path)[0] == status_raw
    with pytest.raises(reanchor.ControlReanchorError, match="HEAD or symbolic branch"):
        reanchor.write_checkpoint(prepared)
    assert target.read_bytes() == source_raw
