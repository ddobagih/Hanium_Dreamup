from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path
import stat
from unittest import mock

import pytest

from scripts import (
    apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812 as subject,
)


ROOT = Path(__file__).resolve().parents[1]
SYNTHETIC_CONTENT_SET_SHA256 = "a" * 64


def _load_source() -> dict[str, object]:
    return json.loads((ROOT / subject.CHECKPOINT_RELATIVE).read_bytes())


def _path_set_sha256(paths: list[str]) -> str:
    return hashlib.sha256(("\n".join(paths) + "\n").encode("utf-8")).hexdigest()


def _project() -> tuple[
    dict[str, object],
    dict[str, object],
    dict[str, object],
    list[str],
    str,
]:
    source = _load_source()
    required = set(subject.contract.EXPECTED_CONTROLLED_PATHS) | set(
        subject.contract.expected_goal_paths(source["goal_execution"])
    )
    paths = sorted(required | set(subject._derive_live_managed_paths(ROOT)))
    path_set_sha256 = _path_set_sha256(paths)
    projected, event = subject.project_seq58(
        source,
        managed_paths=paths,
        path_set_sha256=path_set_sha256,
        content_set_sha256=SYNTHETIC_CONTENT_SET_SHA256,
        previous_contract_binding=subject._previous_contract_binding(),
        replacement_contract_binding=subject._replacement_contract_binding(),
        authorization_binding=subject._authorization_binding(),
        independent_review_binding=subject._independent_review_binding(),
    )
    return source, projected, event, paths, path_set_sha256


def _reseal_tail(checkpoint: dict[str, object]) -> None:
    state = checkpoint["goal_execution"]
    event = state["transition_history"][-1]
    event["event_sha256"] = subject.contract.event_sha256(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]


def test_exact_seq57_physical_and_semantic_source_is_accepted() -> None:
    raw = (ROOT / subject.CHECKPOINT_RELATIVE).read_bytes()
    source = json.loads(raw)

    assert len(raw) == subject.SOURCE_CHECKPOINT_BYTE_COUNT == 1_796_959
    assert hashlib.sha256(raw).hexdigest() == subject.SOURCE_CHECKPOINT_FILE_SHA256
    assert source["schema_version"] == subject.SOURCE_CHECKPOINT_SCHEMA_VERSION
    subject.require_exact_source(source)
    assert (
        subject._control_projection_hashes(source)
        == subject.SOURCE_UNCHANGED_CONTROL_SHA256
    )


def test_exact_add_only_cohort_is_sorted_unique_and_complete() -> None:
    witness_objects = [
        path
        for path in subject.SOURCE_PATHS
        if path.startswith(
            "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/"
        )
    ]

    assert len(subject.SOURCE_PATHS) == 67
    assert subject.SOURCE_PATHS == tuple(sorted(set(subject.SOURCE_PATHS)))
    assert len(witness_objects) == 48
    assert subject.R002_CONTRACT_RELATIVE.as_posix() in subject.SOURCE_PATHS
    assert subject.AUTHORIZATION_RELATIVE.as_posix() in subject.SOURCE_PATHS
    assert subject.INDEPENDENT_REVIEW_RELATIVE.as_posix() in subject.SOURCE_PATHS
    assert (
        "scripts/apply_walksafe_npc_single_admin_recovery_goal_started_seq59_20260812.py"
        in subject.SOURCE_PATHS
    )
    assert (
        "tests/test_apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812.py"
        in subject.SOURCE_PATHS
    )
    assert "scripts/run_walksafe_test_layers_current.sh" in subject.SOURCE_PATHS
    assert "scripts/generate_repository_catalogs.py" in subject.SOURCE_PATHS


def test_public_constants_match_the_strict_checker_contract() -> None:
    checker = subject.contract

    assert subject.EVENT_FIELDS == checker.GOAL_START_CONTROL_REANCHOR_EVENT_FIELDS
    assert (
        subject.CLAIM_BOUNDARY
        == checker.NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_CLAIM_BOUNDARY
    )
    assert (
        subject._source_repository_context_binding()
        == checker.NPC_SINGLE_ADMIN_RECOVERY_SOURCE_REPOSITORY_CONTEXT
    )
    assert (
        subject.SOURCE_UNCHANGED_CONTROL_SHA256
        == checker.NPC_SINGLE_ADMIN_RECOVERY_UNCHANGED_CONTROL_SHA256
    )
    assert (
        subject._replacement_contract_binding()
        == checker.NPC_SINGLE_ADMIN_RECOVERY_R002_BINDING
    )
    assert tuple(checker.NPC_SINGLE_ADMIN_RECOVERY_START_GATE_CHECK_IDS) == (
        subject.EXPECTED_CHECK_IDS
    )


def test_projection_appends_only_exact_zero_credit_seq58_control_event() -> None:
    source = _load_source()
    source_before = copy.deepcopy(source)
    source, projected, event, paths, path_set_sha256 = _project()
    source_state = source["goal_execution"]
    projected_state = projected["goal_execution"]

    assert source == source_before
    assert projected_state["transition_history"][:-1] == source_state[
        "transition_history"
    ]
    assert projected_state["transition_history"][-1] == event
    assert len(projected_state["transition_history"]) == subject.CONTROL_REANCHOR_SEQUENCE
    assert set(event) == subject.EVENT_FIELDS
    assert event["event_type"] == "GOAL_START_CONTROL_REANCHORED"
    assert event["sequence"] == 58
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert event["previous_event_sha256"] == subject.SOURCE_READY_EVENT_SHA256
    assert event["event_sha256"] == subject.contract.event_sha256(event)
    assert event["source_ready_event_binding"] == {
        "sequence": 57,
        "event_id": subject.SOURCE_READY_EVENT_ID,
        "event_sha256": subject.SOURCE_READY_EVENT_SHA256,
        "goal_id": subject.TARGET_GOAL_ID,
        "status": "READY",
    }
    assert event["contract_supersession"] == {
        "previous_contract_binding": subject._previous_contract_binding(),
        "replacement_contract_binding": subject._replacement_contract_binding(),
        "reason_code": "CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED",
    }
    assert event["claim_boundary"] == subject.CLAIM_BOUNDARY
    assert event["claim_boundary"]["implementation_start_authorized"] is False
    assert event["claim_boundary"]["release_status"] == "NOT_ELIGIBLE"
    assert all(
        value == 0
        for key, value in event["claim_boundary"].items()
        if key.endswith("_credit_delta")
    )
    assert event["unchanged_control_projection"] == {
        name: {"before_sha256": digest, "after_sha256": digest}
        for name, digest in sorted(
            subject.SOURCE_UNCHANGED_CONTROL_SHA256.items()
        )
    }
    assert (
        subject._control_projection_hashes(projected)
        == subject.SOURCE_UNCHANGED_CONTROL_SHA256
    )
    assert projected_state["status_by_goal"][subject.TARGET_GOAL_ID] == "READY"
    assert "IN_PROGRESS" not in projected_state["status_by_goal"].values()
    assert projected_state["blockers_by_goal"] == {}
    assert projected_state["pending_questions"] == []

    for key in source:
        if key not in {
            "goal_execution",
            "repository",
            "working_tree_snapshot",
            "session_handoff",
        }:
            assert projected[key] == source[key]
    for key in source_state:
        if key not in {
            "transition_history",
            "transition_history_anchor_sha256",
            "validation_cutoff_at",
        }:
            assert projected_state[key] == source_state[key]

    expected_after = {
        "branch": subject.AUTHORIZED_BRANCH,
        "base_commit": subject.AUTHORIZED_BASE_COMMIT,
        "current_head": subject.AUTHORIZED_HEAD_COMMIT,
        "managed_changed_path_count": len(paths),
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": SYNTHETIC_CONTENT_SET_SHA256,
    }
    assert event["repository_context_reanchor"] == {
        "before": subject._source_repository_context_binding(),
        "after": expected_after,
    }
    snapshot = projected["working_tree_snapshot"]
    handoff = projected["session_handoff"]
    assert snapshot["managed_changed_paths"] == paths
    assert snapshot["managed_changed_path_count"] == len(paths)
    assert snapshot["path_set_sha256"] == path_set_sha256
    assert snapshot["content_set_sha256"] == SYNTHETIC_CONTENT_SET_SHA256
    assert handoff["changed_files"] == paths
    assert handoff["source_commit_or_snapshot"]["file_count"] == len(paths)

    subject._validate_projected_structure(projected)


def test_public_validator_checks_live_bindings_without_external_suite() -> None:
    _, projected, _, _, path_set_sha256 = _project()
    with (
        mock.patch.object(subject, "_require_live_repository"),
        mock.patch.object(subject, "_require_live_managed_membership"),
        mock.patch.object(
            subject.contract,
            "working_snapshot_hashes",
            return_value=(path_set_sha256, SYNTHETIC_CONTENT_SET_SHA256),
        ),
    ):
        subject.require_control_reanchored_checkpoint(
            ROOT,
            projected,
            run_external_validators=False,
        )


@pytest.mark.parametrize(
    "mutation",
    (
        lambda source: source.update({"schema_version": "1.25.1"}),
        lambda source: source["goal_execution"]["status_by_goal"].update(
            {subject.TARGET_GOAL_ID: "IN_PROGRESS"}
        ),
        lambda source: source["current_work"].update({"status": "DRIFT"}),
        lambda source: source["working_tree_snapshot"]["managed_changed_paths"].append(
            "surplus.txt"
        ),
    ),
)
def test_source_drift_is_rejected(mutation: object) -> None:
    source = _load_source()
    assert callable(mutation)
    mutation(source)

    with pytest.raises(subject.ControlReanchorError):
        subject.require_exact_source(source)


@pytest.mark.parametrize(
    "mutation",
    (
        lambda checkpoint: checkpoint["goal_execution"]["transition_history"][-1][
            "claim_boundary"
        ].update({"implementation_start_authorized": True}),
        lambda checkpoint: checkpoint["goal_execution"]["transition_history"][-1].update(
            {"surplus": True}
        ),
        lambda checkpoint: checkpoint["goal_execution"]["transition_history"][-1][
            "contract_supersession"
        ].update({"reason_code": "UNBOUND"}),
        lambda checkpoint: checkpoint["current_work"].update({"status": "DRIFT"}),
        lambda checkpoint: checkpoint["goal_execution"]["status_by_goal"].update(
            {subject.TARGET_GOAL_ID: "IN_PROGRESS"}
        ),
    ),
)
def test_projected_control_or_credit_drift_is_rejected(mutation: object) -> None:
    _, projected, _, _, _ = _project()
    assert callable(mutation)
    mutation(projected)
    _reseal_tail(projected)

    with pytest.raises(subject.ControlReanchorError):
        subject._validate_projected_structure(projected)


def test_public_validator_rejects_repository_context_drift() -> None:
    _, projected, _, _, path_set_sha256 = _project()
    event = projected["goal_execution"]["transition_history"][-1]
    event["repository_context_reanchor"]["after"]["current_head"] = "0" * 40
    _reseal_tail(projected)

    with (
        mock.patch.object(subject, "_require_live_repository"),
        mock.patch.object(
            subject.contract,
            "working_snapshot_hashes",
            return_value=(path_set_sha256, SYNTHETIC_CONTENT_SET_SHA256),
        ),
        pytest.raises(subject.ControlReanchorError),
    ):
        subject.require_control_reanchored_checkpoint(
            ROOT,
            projected,
            run_external_validators=False,
        )


def test_authorized_base_changes_must_be_subset_of_controlled_snapshot() -> None:
    expected = ["a.txt", "nested/b.txt"]
    closure = list(subject.SOURCE_PATHS)
    live = expected
    managed = sorted(set(live) | set(closure) | {"controlled/base.txt"})
    with mock.patch.object(subject, "_derive_live_managed_paths", return_value=live):
        subject._require_live_managed_membership(ROOT, managed)
        with pytest.raises(subject.ControlReanchorError, match="escape"):
            subject._require_live_managed_membership(ROOT, closure)


def test_live_managed_paths_are_authorized_base_diff_plus_untracked_only() -> None:
    managed = subject._derive_live_managed_paths(ROOT)

    assert managed == sorted(set(managed))
    assert subject.CHECKPOINT_RELATIVE.as_posix() not in managed
    assert not any(
        path.startswith("docs/control/execution/goal-gates/") for path in managed
    )
    projected = set(_project()[3])
    assert set(subject.SOURCE_PATHS).issubset(projected)
    assert set(managed).issubset(projected)


def test_public_candidate_validation_uses_both_complete_validator_entrypoints(
    tmp_path: Path,
) -> None:
    _, projected, _, _, _ = _project()
    checkpoint = tmp_path / subject.CHECKPOINT_RELATIVE
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes((ROOT / subject.CHECKPOINT_RELATIVE).read_bytes())
    checkpoint.chmod(0o600)
    (tmp_path / subject.VALIDATION_CANDIDATE_ROOT_RELATIVE.parent).mkdir(
        parents=True,
    )
    continuation_validate = mock.Mock(return_value=[])
    goal_graph_validate = mock.Mock(return_value=[])

    with (
        mock.patch.object(subject, "require_control_reanchored_checkpoint"),
        mock.patch.object(subject.contract, "validate", continuation_validate),
        mock.patch.object(subject.goal_graph, "validate", goal_graph_validate),
    ):
        subject._validate_candidate_with_public_validators(tmp_path, projected)

    continuation_validate.assert_called_once()
    goal_graph_validate.assert_called_once()
    candidate_relative = continuation_validate.call_args.args[1]
    assert candidate_relative.parent == subject.VALIDATION_CANDIDATE_ROOT_RELATIVE
    assert not (tmp_path / candidate_relative).exists()
    assert not (tmp_path / subject.VALIDATION_CANDIDATE_ROOT_RELATIVE).exists()


def test_public_candidate_validation_cleans_candidate_after_failure(
    tmp_path: Path,
) -> None:
    _, projected, _, _, _ = _project()
    checkpoint = tmp_path / subject.CHECKPOINT_RELATIVE
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes((ROOT / subject.CHECKPOINT_RELATIVE).read_bytes())
    checkpoint.chmod(0o600)
    (tmp_path / subject.VALIDATION_CANDIDATE_ROOT_RELATIVE.parent).mkdir(
        parents=True,
    )

    with (
        mock.patch.object(subject, "require_control_reanchored_checkpoint"),
        mock.patch.object(subject.contract, "validate", return_value=["rejected"]),
        mock.patch.object(subject.goal_graph, "validate", return_value=[]),
        pytest.raises(subject.ControlReanchorError, match="public candidate"),
    ):
        subject._validate_candidate_with_public_validators(tmp_path, projected)

    assert not (tmp_path / subject.VALIDATION_CANDIDATE_ROOT_RELATIVE).exists()


def test_safe_regular_file_rejects_symlink_hardlink_and_mode_drift(
    tmp_path: Path,
) -> None:
    regular = tmp_path / "regular.txt"
    regular.write_text("sealed\n", encoding="utf-8")
    regular.chmod(0o600)
    assert subject._safe_regular_file(tmp_path, "regular.txt", mode=0o600) == regular

    symlink = tmp_path / "symlink.txt"
    symlink.symlink_to(regular)
    with pytest.raises(subject.ControlReanchorError, match="symlink"):
        subject._safe_regular_file(tmp_path, "symlink.txt")

    hardlink = tmp_path / "hardlink.txt"
    os.link(regular, hardlink)
    with pytest.raises(subject.ControlReanchorError, match="single-link"):
        subject._safe_regular_file(tmp_path, "regular.txt")

    hardlink.unlink()
    regular.chmod(0o644)
    with pytest.raises(subject.ControlReanchorError, match="mode"):
        subject._safe_regular_file(tmp_path, "regular.txt", mode=0o600)


def test_pinned_git_authority_rejects_same_byte_ref_aba(tmp_path: Path) -> None:
    head = tmp_path / ".git/HEAD"
    branch = tmp_path / ".git/refs/heads/current"
    branch.parent.mkdir(parents=True)
    head.write_text("ref: refs/heads/current\n", encoding="ascii")
    branch.write_text(subject.AUTHORIZED_HEAD_COMMIT + "\n", encoding="ascii")
    authority = subject.retained.PinnedCohort.capture(
        tmp_path,
        list(subject.GIT_AUTHORITY_PATHS),
    )
    replacement = branch.with_name("replacement")
    replacement.write_bytes(branch.read_bytes())
    os.replace(replacement, branch)
    try:
        with pytest.raises(
            subject.retained.PublicationError,
            match="metadata changed|identity changed",
        ):
            authority.verify()
    finally:
        authority.close()


class _FakeCohort:
    def __init__(self, *, failure: BaseException | None = None) -> None:
        self.failure = failure
        self.verify_count = 0
        self.closed = False
        self.primary: BaseException | None = None

    def verify(self) -> None:
        self.verify_count += 1
        if self.failure is not None:
            raise self.failure

    def close(self, primary: BaseException | None = None) -> None:
        self.closed = True
        self.primary = primary


def _prepared_tmp_projection(
    tmp_path: Path,
    *,
    cohort: _FakeCohort,
) -> subject.PreparedProjection:
    source, projected, event, _, _ = _project()
    raw = (json.dumps(source, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    checkpoint = tmp_path / "checkpoint.json"
    checkpoint.write_bytes(raw)
    checkpoint.chmod(0o600)
    return subject.PreparedProjection(
        root=tmp_path,
        checkpoint_path=checkpoint,
        source=subject.SourceCheckpoint(raw=raw, document=source),
        projected_checkpoint=projected,
        projected_checkpoint_bytes=subject._json_bytes(projected),
        event=event,
        cohort=cohort,
        git_authority=_FakeCohort(),
    )


def test_atomic_writer_uses_cas_and_preserves_regular_0600_single_link(
    tmp_path: Path,
) -> None:
    cohort = _FakeCohort()
    prepared = _prepared_tmp_projection(tmp_path, cohort=cohort)

    with (
        mock.patch.object(subject, "_require_live_repository"),
        mock.patch.object(
            subject,
            "_load_contract_bindings",
            return_value=(
                subject._previous_contract_binding(),
                subject._replacement_contract_binding(),
            ),
        ),
        mock.patch.object(
            subject,
            "_load_markdown_bindings",
            return_value=(
                subject._authorization_binding(),
                subject._independent_review_binding(),
            ),
        ),
        mock.patch.object(subject, "require_control_reanchored_checkpoint"),
    ):
        subject.write_projection(prepared)

    metadata = prepared.checkpoint_path.lstat()
    assert stat.S_ISREG(metadata.st_mode)
    assert stat.S_IMODE(metadata.st_mode) == 0o600
    assert metadata.st_nlink == 1
    assert prepared.checkpoint_path.read_bytes() == prepared.projected_checkpoint_bytes
    assert cohort.verify_count >= 3
    assert cohort.closed is True
    assert cohort.primary is None


def test_transaction_guard_fails_before_cas_when_cohort_drifts(
    tmp_path: Path,
) -> None:
    failure = subject.ControlReanchorError("retained input drift")
    cohort = _FakeCohort(failure=failure)
    prepared = _prepared_tmp_projection(tmp_path, cohort=cohort)
    writer = mock.Mock()

    with pytest.raises(subject.ControlReanchorError, match="retained input drift"):
        subject.write_projection(prepared, atomic_writer=writer)

    writer.assert_not_called()
    assert cohort.closed is True
    assert cohort.primary is failure
    assert prepared.checkpoint_path.read_bytes() == prepared.source.raw


def test_transaction_guard_rejects_checkpoint_cas_source_drift(
    tmp_path: Path,
) -> None:
    cohort = _FakeCohort()
    prepared = _prepared_tmp_projection(tmp_path, cohort=cohort)
    prepared.checkpoint_path.write_bytes(b"drift\n")
    prepared.checkpoint_path.chmod(0o600)
    writer = mock.Mock()

    with pytest.raises(subject.ControlReanchorError, match="transaction boundary"):
        subject.write_projection(prepared, atomic_writer=writer)

    writer.assert_not_called()
    assert cohort.verify_count == 0
    assert cohort.closed is True


def test_transaction_guard_rejects_candidate_document_byte_divergence(
    tmp_path: Path,
) -> None:
    cohort = _FakeCohort()
    prepared = _prepared_tmp_projection(tmp_path, cohort=cohort)
    prepared.projected_checkpoint["schema_version"] = "DRIFT"
    writer = mock.Mock()

    with (
        mock.patch.object(subject, "_require_live_repository"),
        mock.patch.object(
            subject,
            "_load_contract_bindings",
            return_value=(
                subject._previous_contract_binding(),
                subject._replacement_contract_binding(),
            ),
        ),
        mock.patch.object(
            subject,
            "_load_markdown_bindings",
            return_value=(
                subject._authorization_binding(),
                subject._independent_review_binding(),
            ),
        ),
        mock.patch.object(subject, "require_control_reanchored_checkpoint"),
        pytest.raises(subject.ControlReanchorError, match="document/bytes diverged"),
    ):
        subject.write_projection(prepared, atomic_writer=writer)

    writer.assert_not_called()
    assert prepared.checkpoint_path.read_bytes() == prepared.source.raw
    assert cohort.closed is True


def test_cli_requires_explicit_exact_mode_and_check_never_writes() -> None:
    with pytest.raises(SystemExit):
        subject.parse_args([])
    with pytest.raises(SystemExit):
        subject.parse_args(["--ch"])
    with pytest.raises(SystemExit):
        subject.parse_args(["--check", "--write"])

    cohort = _FakeCohort()
    _, projected, event, _, _ = _project()
    prepared = mock.Mock(
        cohort=cohort,
        git_authority=_FakeCohort(),
        event=event,
        projected_checkpoint=projected,
    )
    with (
        mock.patch.object(subject, "prepare_projection", return_value=prepared),
        mock.patch.object(subject, "write_projection") as write,
    ):
        assert subject.main(["--check", "--root", str(ROOT)]) == 0
    write.assert_not_called()
    assert cohort.closed is True
