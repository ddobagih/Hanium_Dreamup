from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from unittest import mock

import pytest

from scripts import (
    apply_walksafe_npc_goal_start_control_correction_seq59_20260812 as subject,
)


ROOT = Path(__file__).resolve().parents[1]
SYNTHETIC_CONTENT_SHA256 = "a" * 64


def _source() -> dict[str, object]:
    return json.loads((ROOT / subject.CHECKPOINT_RELATIVE).read_bytes())


def _project() -> tuple[dict[str, object], dict[str, object], dict[str, object], list[str], str]:
    source = _source()
    required = set(subject.contract.EXPECTED_CONTROLLED_PATHS) | set(
        subject.contract.expected_goal_paths(source["goal_execution"])
    )
    paths = sorted(required | set(subject._live_paths(ROOT)))
    path_digest = hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest()
    with mock.patch.object(subject, "_require_exact_source"):
        projected, event = subject.project_seq59(
            source,
            managed_paths=paths,
            path_set_sha256=path_digest,
            content_set_sha256=SYNTHETIC_CONTENT_SHA256,
            authorization_binding=subject._authorization_binding(),
            independent_review_binding=subject._independent_review_binding(),
        )
    return source, projected, event, paths, path_digest


def _reseal(checkpoint: dict[str, object]) -> None:
    state = checkpoint["goal_execution"]
    event = state["transition_history"][-1]
    event["event_sha256"] = subject.contract.event_sha256(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]


def test_source_checkpoint_and_failed_gate_evidence_are_exact() -> None:
    raw = (ROOT / subject.CHECKPOINT_RELATIVE).read_bytes()
    assert len(raw) == subject.SOURCE_CHECKPOINT_BYTE_COUNT
    assert hashlib.sha256(raw).hexdigest() == subject.SOURCE_CHECKPOINT_FILE_SHA256
    log = (ROOT / subject.FAILED_GATE_LOG_RELATIVE).read_bytes()
    assert len(log) == subject.FAILED_GATE_LOG_BYTE_COUNT
    assert hashlib.sha256(log).hexdigest() == subject.FAILED_GATE_LOG_SHA256
    assert b"only the 9 ordered logs and receipt" in log
    assert b"only the ordered logs and receipt" in log


def test_projection_appends_zero_credit_ready_to_ready_correction() -> None:
    source, projected, event, paths, path_digest = _project()
    state = projected["goal_execution"]
    assert state["transition_history"][:-1] == source["goal_execution"]["transition_history"]
    assert len(state["transition_history"]) == subject.CONTROL_CORRECTION_SEQUENCE == 59
    assert set(event) == subject.EVENT_FIELDS
    assert event["event_type"] == "GOAL_START_CONTROL_REANCHORED"
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert event["previous_event_sha256"] == subject.SOURCE_CONTROL_EVENT_SHA256
    assert event["source_ready_event_binding"] == subject._source_ready_event_binding()
    assert event["contract_supersession"] == subject._contract_supersession()
    assert event["evidence_refs"] == subject.EVIDENCE_REFS
    assert event["claim_boundary"]["implementation_start_authorized"] is False
    assert all(
        value == 0
        for key, value in event["claim_boundary"].items()
        if key.endswith("_credit_delta")
    )
    assert event["event_sha256"] == subject.contract.event_sha256(event)
    assert event["repository_context_reanchor"] == {
        "before": subject._source_repository_context_binding(),
        "after": subject._after_context(paths, path_digest, SYNTHETIC_CONTENT_SHA256),
    }
    assert subject._control_projection_hashes(projected) == subject.SOURCE_UNCHANGED_CONTROL_SHA256


@pytest.mark.parametrize(
    "mutation",
    (
        lambda event: event.update({"surplus": True}),
        lambda event: event.update({"status_changes": {subject.TARGET_GOAL_ID: "IN_PROGRESS"}}),
        lambda event: event["claim_boundary"].update({"implementation_start_authorized": True}),
        lambda event: event["contract_supersession"].update({"reason_code": "DRIFT"}),
        lambda event: event.update({"previous_event_sha256": "0" * 64}),
    ),
)
def test_event_schema_lineage_status_or_credit_drift_is_rejected(mutation: object) -> None:
    _, projected, event, _, _ = _project()
    assert callable(mutation)
    mutation(event)
    _reseal(projected)
    with (
        mock.patch.object(subject, "_require_exact_source"),
        pytest.raises(subject.ControlCorrectionError),
    ):
        subject._validate_structure(projected)


def test_exact_add_only_paths_and_modified_digest_allowlist() -> None:
    assert subject.SOURCE_PATHS == tuple(sorted(set(subject.SOURCE_PATHS)))
    assert len(subject.SOURCE_PATHS) == 6
    assert subject.AUTHORIZATION_RELATIVE.as_posix() in subject.SOURCE_PATHS
    assert subject.INDEPENDENT_REVIEW_RELATIVE.as_posix() in subject.SOURCE_PATHS
    assert "scripts/apply_walksafe_npc_single_admin_recovery_goal_started_seq60_20260812.py" in subject.SOURCE_PATHS
    assert subject.MODIFIED_EXISTING_FINAL_SHA256 == {
        "scripts/check_walksafe_project_continuation_v2_4.py": subject.CHECKER_AFTER_SHA256,
        "scripts/generate_repository_catalogs.py": "ed08e07baefd6a014fb4bb529642e371614dc5f7472ffee0a848f0091c66971e",
        "scripts/run_walksafe_test_layers_current.sh": "f1a19f459ae5cd2cfeacd08bb5c64e64218f746f4ced8940ce3a2e8454f542f7",
        "tests/test_repository_catalogs.py": "f2f49c4849df32b26d955c611c989e67573c0904bec5bac26c519f6cc6ef1d28",
        "docs/catalogs/repository-paths.json": "45a5cb576f209af5e5123513712d19bae14436e4bb50e71320cfe7c53d446f76",
        "docs/catalogs/scripts.json": "22137678e8edc2d1dfdb4cc8a3ed01f203c4aebd84dd1ff010e3d5b8ab0c4985",
        "docs/catalogs/tests.json": "96a6fa4526706119997d8ae556bf0f2c13ab8b2cfa8bcf3a5d7a997ed3912668",
        "scripts/run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py": "6253f8ca5d54556ff5a0106c2243f0eb2b5a76ef8fc6a65f85491e439e70079d",
        "tests/test_run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py": "a371a08fc6936cac855f89e3aebbc82c37805931ed4c5f1ebc89bce349e3ad7f",
    }


def test_checker_change_is_the_reviewed_before_after_pair() -> None:
    assert subject.CHECKER_BEFORE_SHA256 == "a928e38a23f8e67c4ccb3c54521d2cb610e4afc70a91057acdb27d48cbc88600"
    assert len(subject.CHECKER_AFTER_SHA256) == 64


def test_reviewed_checker_binding_matches_integrated_public_validator() -> None:
    current = hashlib.sha256((ROOT / subject.CHECKER_RELATIVE).read_bytes()).hexdigest()
    assert current == subject.CHECKER_AFTER_SHA256


def test_cli_check_mode_never_writes() -> None:
    _, projected, event, _, _ = _project()
    prepared = mock.Mock(projected_checkpoint=projected, event=event)
    with (
        mock.patch.object(subject, "prepare_projection", return_value=prepared),
        mock.patch.object(subject, "write_projection") as write,
    ):
        assert subject.main(["--check", "--root", str(ROOT)]) == 0
    write.assert_not_called()


def test_cas_guard_rejects_source_drift(tmp_path: Path) -> None:
    _, projected, event, _, _ = _project()
    checkpoint = tmp_path / "checkpoint.json"
    checkpoint.write_bytes(b"drift\n")
    prepared = subject.PreparedProjection(
        root=tmp_path,
        checkpoint_path=checkpoint,
        source_raw=b"source\n",
        projected_checkpoint=projected,
        projected_checkpoint_bytes=subject._json_bytes(projected),
        event=event,
    )
    writer = mock.Mock()
    with pytest.raises(subject.ControlCorrectionError, match="CAS boundary"):
        subject.write_projection(prepared, atomic_writer=writer)
    writer.assert_not_called()
