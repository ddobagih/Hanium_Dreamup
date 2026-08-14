from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import stat
from unittest import mock

import pytest

from scripts import (
    apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814 as subject,
)


ROOT = Path(__file__).resolve().parents[1]
POST_SEQ67_PATHS = {
    subject.R002_CONTRACT_PATH.as_posix(),
    subject.SCRIPT_REL.as_posix(),
    subject.TEST_REL.as_posix(),
    "scripts/apply_walksafe_fp022_goal_started_seq69_20260814.py",
    "tests/test_apply_walksafe_fp022_goal_started_seq69_20260814.py",
    "scripts/build_walksafe_fp022_seq68_69_review_20260814.py",
    "tests/test_build_walksafe_fp022_seq68_69_review_20260814.py",
}
REVIEW_ROOT = "docs/control/execution/workstream-transitions/seq68-69/review-rounds/"


def _source() -> tuple[bytes, dict[str, object]]:
    raw = (ROOT / subject.CHECKPOINT_REL).read_bytes()
    source = json.loads(raw)
    if hashlib.sha256(raw).hexdigest() == subject.SOURCE_SHA256:
        return raw, source
    history = source["goal_execution"]["transition_history"]
    if len(history) == subject.SEQUENCE + 1:
        started = history.pop()
        assert started["event_id"] == "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20260814-001"
        state = source["goal_execution"]
        state["transition_history_anchor_sha256"] = history[-1]["event_sha256"]
        state["validation_cutoff_at"] = history[-1]["occurred_at"]
        state["status_by_goal"][subject.GOAL_ID] = "READY"
        current = source["current_work"]
        current["status"] = "READY"
        current["current_focus"] = (
            "FP-022/GAP-031 Goal READY; active internal start gate not run"
        )
        current["release_completion_claimed"] = False
        handoff = source["session_handoff"]
        handoff["current_epic"] = "EPIC-04 / FP-022/GAP-031 READY_NOT_STARTED"
        handoff["last_verification_status"] = (
            "PASS_INTERNAL_ONLY_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
        )
    assert len(history) == subject.SEQUENCE
    assert history[-1]["event_id"] == subject.EVENT_ID
    history.pop()
    state = source["goal_execution"]
    state["transition_history_anchor_sha256"] = subject.SOURCE_TAIL_SHA256
    state["validation_cutoff_at"] = subject.SOURCE_CUTOFF_AT
    paths = [
        path
        for path in source["working_tree_snapshot"]["managed_changed_paths"]
        if path not in POST_SEQ67_PATHS
        and not path.startswith(REVIEW_ROOT)
        and not path.startswith("docs/control/execution/goal-gates/")
    ]
    assert len(paths) == subject.SOURCE_MANAGED_PATH_COUNT
    snapshot = source["working_tree_snapshot"]
    snapshot.update(
        {
            "managed_changed_paths": paths,
            "managed_changed_path_count": subject.SOURCE_MANAGED_PATH_COUNT,
            "path_set_sha256": subject.SOURCE_PATH_SET_SHA256,
            "content_set_sha256": subject.SOURCE_CONTENT_SET_SHA256,
            "scope": subject.fp022.FINAL_SCOPE,
        }
    )
    handoff = source["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(paths)
    handoff["source_commit_or_snapshot"].update(
        {
            "file_count": subject.SOURCE_MANAGED_PATH_COUNT,
            "path_set_sha256": subject.SOURCE_PATH_SET_SHA256,
            "content_set_sha256": subject.SOURCE_CONTENT_SET_SHA256,
        }
    )
    raw = subject.npc.trace.json_text(source).encode()
    assert len(raw) == subject.SOURCE_BYTE_COUNT
    assert hashlib.sha256(raw).hexdigest() == subject.SOURCE_SHA256
    return raw, source


def _successor() -> dict[str, str]:
    return subject._load_r002_contract(ROOT)[1]


def _review() -> dict[str, dict[str, object]]:
    return {
        "assignment": {"path": "assignment.json", "sha256": "a" * 64, "byte_length": 1},
        "review_result": {"path": "result.json", "sha256": "b" * 64, "byte_length": 1},
        "independent_review": {"path": "independent.json", "sha256": "c" * 64, "byte_length": 1},
    }


def _runner() -> dict[str, object]:
    return {
        "path": subject.START_GATE_RUNNER_PATH.as_posix(),
        "sha256": "d" * 64,
        "byte_length": 1,
    }


def _digests(source: dict[str, object]) -> dict[Path, str]:
    paths = {
        Path(path)
        for path in source["working_tree_snapshot"]["managed_changed_paths"]
    } | set(subject.REQUIRED_CONTROL_PATHS)
    return {path: hashlib.sha256(path.as_posix().encode()).hexdigest() for path in paths}


def _project() -> tuple[dict[str, object], dict[str, object], dict[str, object]]:
    _, source = _source()
    projected, event = subject.project(
        source,
        _digests(source),
        successor_contract=_successor(),
        runner_binding=_runner(),
        transition_review=_review(),
    )
    return source, projected, event


def _reseal(checkpoint: dict[str, object]) -> None:
    state = checkpoint["goal_execution"]
    event = state["transition_history"][-1]
    event["event_sha256"] = subject.continuation.event_sha256(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]


def test_exact_seq67_physical_and_semantic_source_is_accepted() -> None:
    raw, source = _source()

    assert len(raw) == subject.SOURCE_BYTE_COUNT == 1_994_168
    assert hashlib.sha256(raw).hexdigest() == subject.SOURCE_SHA256
    subject.require_source(raw, source)
    assert (
        subject.control_projection_hashes(source)
        == subject.SOURCE_UNCHANGED_CONTROL_SHA256
    )


def test_r002_successor_contract_is_exact_and_bound_to_seq67() -> None:
    document, binding = subject._load_r002_contract(ROOT)

    assert binding == {
        "schema_version": "1.1",
        "document_id": subject.R002_DOCUMENT_ID,
        "path": subject.R002_CONTRACT_PATH.as_posix(),
        "file_sha256": subject.R002_FILE_SHA256,
        "contract_id": subject.R002_CONTRACT_ID,
        "contract_version": subject.R002_CONTRACT_VERSION,
        "canonical_contract_sha256": subject.R002_CANONICAL_SHA256,
    }
    assert document["supersedes"] == {
        **subject.r001_contract_binding(),
        "source_ready_event_sequence": subject.SOURCE_SEQUENCE,
        "source_ready_event_id": subject.READY_EVENT_ID,
        "source_ready_event_sha256": subject.SOURCE_TAIL_SHA256,
    }


def test_projection_appends_only_zero_credit_ready_to_ready_seq68() -> None:
    source, projected, event = _project()
    before = source["goal_execution"]
    after = projected["goal_execution"]

    assert after["transition_history"][:-1] == before["transition_history"]
    assert after["transition_history"][-1] == event
    assert len(after["transition_history"]) == subject.SEQUENCE
    assert set(event) == subject.EVENT_FIELDS
    assert event["event_id"] == subject.EVENT_ID
    assert event["event_type"] == "GOAL_START_CONTROL_REANCHORED"
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert event["previous_event_sha256"] == subject.SOURCE_TAIL_SHA256
    assert event["event_sha256"] == subject.continuation.event_sha256(event)
    assert event["contract_supersession"] == {
        "previous_contract_binding": subject.r001_contract_binding(),
        "replacement_contract_binding": _successor(),
        "reason_code": subject.SUCCESSOR_REASON_CODE,
    }
    assert event["claim_boundary"] == subject.CLAIM_BOUNDARY
    assert event["claim_boundary"]["implementation_start_authorized"] is False
    assert all(
        value == 0
        for key, value in event["claim_boundary"].items()
        if key.endswith("_credit_delta")
    )
    assert after["status_by_goal"][subject.GOAL_ID] == "READY"
    assert "IN_PROGRESS" not in after["status_by_goal"].values()
    assert subject.control_projection_hashes(projected) == (
        subject.SOURCE_UNCHANGED_CONTROL_SHA256
    )
    subject.validate_projection(
        source,
        projected,
        event,
        successor_contract=_successor(),
        runner_binding=_runner(),
        transition_review=_review(),
    )


@pytest.mark.parametrize(
    "mutation",
    (
        lambda source: source.update({"schema_version": "1.25.1"}),
        lambda source: source["goal_execution"]["status_by_goal"].update(
            {subject.GOAL_ID: "IN_PROGRESS"}
        ),
        lambda source: source["goal_execution"]["transition_history"][-1].update(
            {"event_sha256": "0" * 64}
        ),
        lambda source: source["working_tree_snapshot"].update(
            {"content_set_sha256": "0" * 64}
        ),
    ),
)
def test_source_drift_is_rejected(mutation: object) -> None:
    raw, source = _source()
    assert callable(mutation)
    mutation(source)

    with pytest.raises(subject.ControlReanchorError):
        subject.require_source(raw, source)


@pytest.mark.parametrize(
    "mutation",
    (
        lambda checkpoint: checkpoint["goal_execution"]["transition_history"][-1][
            "claim_boundary"
        ].update({"implementation_start_authorized": True}),
        lambda checkpoint: checkpoint["goal_execution"]["transition_history"][-1][
            "contract_supersession"
        ].update({"reason_code": "UNBOUND"}),
        lambda checkpoint: checkpoint["goal_execution"]["status_by_goal"].update(
            {subject.GOAL_ID: "IN_PROGRESS"}
        ),
        lambda checkpoint: checkpoint["current_work"].update({"status": "IN_PROGRESS"}),
    ),
)
def test_projected_credit_or_control_drift_is_rejected(mutation: object) -> None:
    source, projected, event = _project()
    assert callable(mutation)
    mutation(projected)
    _reseal(projected)

    with pytest.raises(subject.ControlReanchorError):
        subject.validate_projection(
            source,
            projected,
            projected["goal_execution"]["transition_history"][-1],
            successor_contract=_successor(),
            runner_binding=_runner(),
            transition_review=_review(),
        )


def test_postpublication_validator_does_not_require_physical_seq67() -> None:
    _, projected, _ = _project()
    paths = projected["working_tree_snapshot"]["managed_changed_paths"]
    snapshot_hashes = (
        projected["working_tree_snapshot"]["path_set_sha256"],
        projected["working_tree_snapshot"]["content_set_sha256"],
    )

    with (
        mock.patch.object(subject, "_load_r002_contract", return_value=({}, _successor())),
        mock.patch.object(subject, "start_gate_runner_binding", return_value=_runner()),
        mock.patch.object(subject, "transition_review_binding", return_value=_review()),
        mock.patch.object(
            subject.continuation,
            "working_snapshot_hashes",
            return_value=snapshot_hashes,
        ) as hashes,
    ):
        subject.require_control_reanchored_checkpoint(
            ROOT, projected, run_external_validators=False
        )

    hashes.assert_called_once_with(ROOT.resolve(), paths)

    with (
        mock.patch.object(subject, "_load_r002_contract", return_value=({}, _successor())),
        mock.patch.object(subject, "start_gate_runner_binding", return_value=_runner()),
        mock.patch.object(subject, "transition_review_binding", return_value=_review()),
        mock.patch.object(subject.continuation, "working_snapshot_hashes") as hashes,
    ):
        subject.require_control_reanchored_checkpoint(
            ROOT,
            projected,
            run_external_validators=False,
            require_live_snapshot=False,
        )

    hashes.assert_not_called()


def test_catalog_projection_reaches_fixed_point_with_mocked_successor_authority(
    tmp_path: Path,
) -> None:
    source_raw, _ = _source()
    source_checkpoint = tmp_path / subject.CHECKPOINT_REL.name
    source_checkpoint.write_bytes(source_raw)
    source_checkpoint.chmod(0o600)
    original_safe_file = subject.npc._safe_file

    def safe_file(root: Path, relative: Path, **kwargs: object) -> Path:
        if relative == subject.CHECKPOINT_REL:
            return source_checkpoint
        return original_safe_file(root, relative, **kwargs)

    existing_required = tuple(
        path for path in subject.REQUIRED_CONTROL_PATHS if (ROOT / path).exists()
    )
    with (
        mock.patch.object(subject.npc, "_safe_file", side_effect=safe_file),
        mock.patch.object(subject, "REQUIRED_CONTROL_PATHS", existing_required),
        mock.patch.object(subject, "transition_review_binding", return_value=_review()),
        mock.patch.object(
            subject.catalogs,
            "KNOWN_SCRIPT_PATHS",
            subject.catalogs.KNOWN_SCRIPT_PATHS
            | {
                path.as_posix()
                for path in subject.REQUIRED_CONTROL_PATHS
                if path.parts[0] == "scripts"
            },
        ),
    ):
        prepared = subject.prepare(
            ROOT,
            allow_stale_catalogs=True,
            projected_validator=mock.Mock(),
        )
        assert subject.npc._build_candidate_catalog_bytes(
            ROOT, prepared.source_universe, prepared.projected
        ) == prepared.candidate_catalogs
    assert prepared.projected["goal_execution"]["transition_history"][-1][
        "sequence"
    ] == subject.SEQUENCE


class _FakeCohort:
    def __init__(self, failure: BaseException | None = None) -> None:
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


def _prepared_tmp(tmp_path: Path, cohort: _FakeCohort) -> subject.Prepared:
    source, projected, _ = _project()
    source_bytes = subject.npc.trace.json_text(source).encode()
    projected_bytes = subject.npc.trace.json_text(projected).encode()
    checkpoint = tmp_path / "checkpoint.json"
    checkpoint.write_bytes(source_bytes)
    checkpoint.chmod(0o600)
    return subject.Prepared(
        root=tmp_path,
        checkpoint_path=checkpoint,
        source_bytes=source_bytes,
        projected=projected,
        projected_bytes=projected_bytes,
        final_sha256_by_path={},
        physical_sha256_by_path={},
        source_universe=(),
        candidate_catalogs={},
        catalogs_verified=True,
        projected_validator=mock.Mock(),
    )


def test_write_reprepares_before_retaining_or_publishing(tmp_path: Path) -> None:
    prepared = _prepared_tmp(tmp_path, _FakeCohort())
    drifted = copy.copy(prepared)
    drifted = subject.Prepared(
        **{**drifted.__dict__, "projected_bytes": prepared.projected_bytes + b" "}
    )

    with (
        mock.patch.object(subject, "prepare", return_value=drifted),
        mock.patch.object(subject.npc, "retain_physical_pin_cohort") as retain,
        mock.patch.object(subject.npc, "atomic_write") as writer,
        pytest.raises(subject.ControlReanchorError, match="changed before write"),
    ):
        subject.write_checkpoint(prepared)

    retain.assert_not_called()
    writer.assert_not_called()


def test_atomic_writer_aborts_when_commit_guard_detects_retained_input_drift(
    tmp_path: Path,
) -> None:
    failure = subject.ControlReanchorError("retained input drift")
    cohort = _FakeCohort(failure)
    prepared = _prepared_tmp(tmp_path, cohort)
    writer = mock.Mock(
        side_effect=lambda *args, **kwargs: kwargs["commit_guard"]()
    )

    with (
        mock.patch.object(subject, "prepare", return_value=prepared),
        mock.patch.object(
            subject.npc, "retain_physical_pin_cohort", return_value=cohort
        ),
        mock.patch.object(subject.npc, "atomic_write", writer),
        pytest.raises(subject.ControlReanchorError, match="retained input drift"),
    ):
        subject.write_checkpoint(prepared)

    writer.assert_called_once()
    assert cohort.closed is True
    assert cohort.primary is failure
    assert prepared.checkpoint_path.read_bytes() == prepared.source_bytes


def test_catalog_atomic_writer_allows_only_its_verified_staging_file(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    relative = Path("docs/catalogs/repository-paths.json")
    target = tmp_path / relative
    target.parent.mkdir(parents=True)
    source = b'{"generation":1}\n'
    wanted = b'{"generation":2}\n'
    target.write_bytes(source)
    target.chmod(0o600)
    expected = (relative.as_posix(),)

    def discover(root: Path) -> tuple[str, ...]:
        paths = [relative.as_posix()]
        paths.extend(
            path.relative_to(root).as_posix()
            for path in target.parent.iterdir()
            if path != target
        )
        return tuple(sorted(paths))

    monkeypatch.setattr(subject.catalogs, "discover_source_paths", discover)

    subject.npc.atomic_write(
        target,
        wanted,
        expected_source=source,
        commit_guard=lambda: subject._catalog_source_universe_during_atomic_write(
            tmp_path,
            expected,
            target=target,
            source=source,
            wanted=wanted,
        ),
    )

    assert target.read_bytes() == wanted
    assert discover(tmp_path) == expected

    monkeypatch.setattr(subject.catalogs, "discover_source_paths", lambda _root: expected)
    stage = target.parent / (
        f".{target.name}.fp048-seq43-44.{'a' * 24}.tmp"
    )
    stage.write_bytes(wanted)
    stage.chmod(0o600)
    assert subject._catalog_source_universe_during_atomic_write(
        tmp_path,
        expected,
        target=target,
        source=source,
        wanted=wanted,
    ) == expected
    stage.unlink()

    stage = target.parent / (
        f".{target.name}.fp048-seq43-44.{'a' * 24}.tmp"
    )
    stage.symlink_to(target)
    with pytest.raises(subject.ControlReanchorError, match="authority differs"):
        subject._catalog_source_universe_during_atomic_write(
            tmp_path,
            expected,
            target=target,
            source=source,
            wanted=wanted,
        )


def test_cli_requires_explicit_nonabbreviated_mode_and_preflight_never_writes() -> None:
    with pytest.raises(SystemExit):
        subject.parse_args([])
    with pytest.raises(SystemExit):
        subject.parse_args(["--pre"])
    with pytest.raises(SystemExit):
        subject.parse_args(["--preflight", "--write"])

    prepared = mock.Mock()
    with (
        mock.patch.object(subject, "prepare", return_value=prepared),
        mock.patch.object(subject, "write_checkpoint") as writer,
    ):
        assert subject.main(["--preflight", "--root", str(ROOT)]) == 0
    writer.assert_not_called()
