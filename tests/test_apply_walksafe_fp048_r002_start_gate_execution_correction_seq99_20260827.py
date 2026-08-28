from __future__ import annotations

import copy
from datetime import timedelta
from functools import lru_cache
import hashlib
import json
import os
from pathlib import Path
import shutil
from types import SimpleNamespace
from unittest.mock import patch

import pytest

from scripts import (
    apply_walksafe_fp048_r002_start_gate_execution_correction_seq99_20260827
    as correction,
)


ROOT = Path(__file__).resolve().parents[1]


def _copy_private(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    destination.chmod(0o600)


def _failure_fixture(tmp_path: Path) -> Path:
    gate_source = ROOT / correction.R010_GATE_REL
    gate_target = tmp_path / correction.R010_GATE_REL
    gate_target.mkdir(parents=True, mode=0o700)
    gate_target.chmod(0o700)
    for source in gate_source.iterdir():
        _copy_private(source, gate_target / source.name)
    _copy_private(
        ROOT / correction.R010_RUNNER_REL,
        tmp_path / correction.R010_RUNNER_REL,
    )
    _copy_private(
        ROOT / correction.R010_CONTRACT_REL,
        tmp_path / correction.R010_CONTRACT_REL,
    )
    (tmp_path / correction.R010_CONTRACT_REL).chmod(correction.R010_CONTRACT_MODE)
    return tmp_path


def _r001_review_fixture(tmp_path: Path) -> Path:
    for path in correction.R001_REVIEW_PATHS:
        _copy_private(ROOT / path, tmp_path / path)
    return tmp_path


def _r002_review_fixture(tmp_path: Path) -> Path:
    for path in correction.R002_REVIEW_PATHS:
        _copy_private(ROOT / path, tmp_path / path)
    return tmp_path


def _r003_partial_review_fixture(tmp_path: Path) -> Path:
    for path in correction.R003_PARTIAL_REVIEW_PATHS:
        _copy_private(ROOT / path, tmp_path / path)
    return tmp_path


def _r004_review_fixture(tmp_path: Path) -> Path:
    for path in correction.R004_REVIEW_PATHS:
        _copy_private(ROOT / path, tmp_path / path)
    return tmp_path


def _r005_review_fixture(tmp_path: Path) -> Path:
    for path in correction.R005_REVIEW_PATHS:
        _copy_private(ROOT / path, tmp_path / path)
    return tmp_path


def _r006_review_fixture(tmp_path: Path) -> Path:
    for path in correction.R006_REVIEW_PATHS:
        _copy_private(ROOT / path, tmp_path / path)
    return tmp_path


def _r007_review_fixture(tmp_path: Path) -> Path:
    for path in correction.R007_REVIEW_PATHS:
        _copy_private(ROOT / path, tmp_path / path)
    return tmp_path


def _r008_review_fixture(tmp_path: Path) -> Path:
    for path in correction.R008_REVIEW_PATHS:
        _copy_private(ROOT / path, tmp_path / path)
    return tmp_path


def _r009_review_fixture(tmp_path: Path) -> Path:
    for path in correction.R009_REVIEW_PATHS:
        _copy_private(ROOT / path, tmp_path / path)
    return tmp_path


@lru_cache(maxsize=1)
def _cached_source() -> tuple[bytes, dict]:
    return correction.load_exact_seq98_source(ROOT)


def _source() -> tuple[bytes, dict]:
    raw, source = _cached_source()
    return raw, copy.deepcopy(source)


def _quick_exact_source(raw: bytes, checkpoint: dict, root: Path = ROOT) -> None:
    assert root == ROOT
    assert len(raw) == correction.SOURCE_CHECKPOINT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == correction.SOURCE_CHECKPOINT_SHA256
    assert len(checkpoint["goal_execution"]["transition_history"]) == 98


def _fake_binding(path: Path, token: str) -> dict[str, object]:
    return {"path": path.as_posix(), "sha256": token * 64, "byte_length": 1}


def _projection() -> tuple[bytes, dict, dict]:
    source_raw, source = _source()
    failure = correction.r010_execution_failure_binding(ROOT)
    snapshot = source["working_tree_snapshot"]
    managed_paths = sorted(
        {
            *snapshot["managed_changed_paths"],
            *(path.as_posix() for path in correction.R001_REVIEW_PATHS),
            *(path.as_posix() for path in correction.R002_REVIEW_PATHS),
            *(path.as_posix() for path in correction.R003_PARTIAL_REVIEW_PATHS),
            *(path.as_posix() for path in correction.R004_REVIEW_PATHS),
            *(path.as_posix() for path in correction.R005_REVIEW_PATHS),
            *(path.as_posix() for path in correction.R006_REVIEW_PATHS),
            *(path.as_posix() for path in correction.R007_REVIEW_PATHS),
            *(path.as_posix() for path in correction.R008_REVIEW_PATHS),
            *(path.as_posix() for path in correction.R009_REVIEW_PATHS),
            correction.AUTHORIZATION_REL.as_posix(),
            *(path.as_posix() for path in correction.REVIEW_PATHS),
            *(path.as_posix() for path in correction.REVIEWED_CONTROL_PATHS),
        }
    )
    path_set_sha256 = hashlib.sha256(
        ("\n".join(managed_paths) + "\n").encode()
    ).hexdigest()
    source_at = correction._parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "source time",
    )
    review = {
        "assignment": _fake_binding(correction.REVIEW_ASSIGNMENT_REL, "2"),
        "review_result": _fake_binding(correction.REVIEW_RESULT_REL, "3"),
        "independent_review": _fake_binding(
            correction.INDEPENDENT_REVIEW_REL, "4"
        ),
    }
    with patch.object(
        correction, "require_exact_seq98_source", _quick_exact_source
    ):
        projected, event = correction.project_seq99(
            ROOT,
            source,
            managed_paths=managed_paths,
            path_set_sha256=path_set_sha256,
            content_set_sha256=snapshot["content_set_sha256"],
            occurred_at=(source_at + timedelta(seconds=1)).isoformat(),
            authorization_binding=_fake_binding(correction.AUTHORIZATION_REL, "1"),
            review_binding=review,
            r010_failure_binding=failure,
            replacement_contract_binding=correction.r011_contract_binding(ROOT),
            replacement_runner_binding=correction.r011_runner_binding(ROOT),
        )
    return source_raw, projected, event


def test_public_sequence_and_identity_contract_is_exact() -> None:
    assert correction.SOURCE_SEQUENCE == 98
    assert type(correction.SOURCE_SEQUENCE) is int
    assert correction.CORRECTION_SEQUENCE == 99
    assert type(correction.CORRECTION_SEQUENCE) is int
    assert correction.STARTED_SEQUENCE == 100
    assert correction.CORRECTION_EVENT_ID.endswith("20260827-007")
    assert correction.STARTED_EVENT_ID.endswith("20260827-006")
    assert correction.READY_EVENT_ID.endswith("20260825-001")
    assert correction.FRONTIER == correction.READY_FRONTIER
    assert correction.GOAL_PATH == correction.seq98.GOAL_PATH
    assert correction.GOAL_SHA256 == correction.seq98.GOAL_SHA256
    assert correction.SEQ98_EXECUTION_CORRECTION_REL in (
        correction.REVIEWED_CONTROL_PATHS
    )
    assert correction.SEQ98_EXECUTION_CORRECTION_TEST_REL in (
        correction.REVIEWED_CONTROL_PATHS
    )
    assert correction.REVIEW_ROUND_ID == "R010"
    assert correction.AUTHORIZATION_REL.name == "authorization-r010.json"
    assert correction.REVIEW_ROOT.name == "R010"
    assert correction.AUTHORIZATION_DOCUMENT_ID == (
        "WS-FP048-R002-SEQ99-100-AUTHORIZATION-20260827-R010"
    )
    assert correction.REVIEW_ASSIGNMENT_DOCUMENT_ID == (
        "WS-FP048-R002-SEQ99-100-REVIEW-ASSIGNMENT-20260827-R010"
    )
    assert correction.REVIEW_RESULT_DOCUMENT_ID == (
        "WS-FP048-R002-SEQ99-100-REVIEW-RESULT-20260827-R010"
    )
    assert correction.INDEPENDENT_REVIEW_DOCUMENT_ID == (
        "WS-FP048-R002-SEQ99-100-INDEPENDENT-REVIEW-20260827-R010"
    )
    assert correction.PRODUCER == {
        "id": "codex-fp048-r002-seq99-r010-correction-r010-producer-20260828",
        "task_id": "/root",
    }
    assert correction.PRIMARY_REVIEWER == {
        "id": "codex-fp048-r002-seq99-correction-r010-primary-reviewer-20260828",
        "task_id": "/root/r007_preflight_perf_rootcause",
    }
    assert correction.INDEPENDENT_REVIEWER == {
        "id": (
            "codex-fp048-r002-seq99-correction-r010-independent-reviewer-20260828"
        ),
        "task_id": "/root/goalgraph_fp022_perf_test_design",
    }
    identities = {
        (correction.PRODUCER["id"], correction.PRODUCER["task_id"]),
        (
            correction.PRIMARY_REVIEWER["id"],
            correction.PRIMARY_REVIEWER["task_id"],
        ),
        (
            correction.INDEPENDENT_REVIEWER["id"],
            correction.INDEPENDENT_REVIEWER["task_id"],
        ),
        (correction.R009_PRODUCER["id"], correction.R009_PRODUCER["task_id"]),
        (
            correction.R009_PRIMARY_REVIEWER["id"],
            correction.R009_PRIMARY_REVIEWER["task_id"],
        ),
        (
            correction.R009_INDEPENDENT_REVIEWER["id"],
            correction.R009_INDEPENDENT_REVIEWER["task_id"],
        ),
        (correction.R008_PRODUCER["id"], correction.R008_PRODUCER["task_id"]),
        (
            correction.R008_PRIMARY_REVIEWER["id"],
            correction.R008_PRIMARY_REVIEWER["task_id"],
        ),
        (
            correction.R008_INDEPENDENT_REVIEWER["id"],
            correction.R008_INDEPENDENT_REVIEWER["task_id"],
        ),
        (correction.R007_PRODUCER["id"], correction.R007_PRODUCER["task_id"]),
        (
            correction.R007_PRIMARY_REVIEWER["id"],
            correction.R007_PRIMARY_REVIEWER["task_id"],
        ),
        (
            correction.R007_INDEPENDENT_REVIEWER["id"],
            correction.R007_INDEPENDENT_REVIEWER["task_id"],
        ),
        (correction.R006_PRODUCER["id"], correction.R006_PRODUCER["task_id"]),
        (
            correction.R006_PRIMARY_REVIEWER["id"],
            correction.R006_PRIMARY_REVIEWER["task_id"],
        ),
        (
            correction.R006_INDEPENDENT_REVIEWER["id"],
            correction.R006_INDEPENDENT_REVIEWER["task_id"],
        ),
        (correction.R005_PRODUCER["id"], correction.R005_PRODUCER["task_id"]),
        (
            correction.R005_PRIMARY_REVIEWER["id"],
            correction.R005_PRIMARY_REVIEWER["task_id"],
        ),
        (
            correction.R005_INDEPENDENT_REVIEWER["id"],
            correction.R005_INDEPENDENT_REVIEWER["task_id"],
        ),
        (correction.R004_PRODUCER["id"], correction.R004_PRODUCER["task_id"]),
        (
            correction.R004_PRIMARY_REVIEWER["id"],
            correction.R004_PRIMARY_REVIEWER["task_id"],
        ),
        (
            correction.R004_INDEPENDENT_REVIEWER["id"],
            correction.R004_INDEPENDENT_REVIEWER["task_id"],
        ),
        (correction.R003_PRODUCER["id"], correction.R003_PRODUCER["task_id"]),
        (
            correction.R003_PRIMARY_REVIEWER["id"],
            correction.R003_PRIMARY_REVIEWER["task_id"],
        ),
        (
            correction.R003_INDEPENDENT_REVIEWER["id"],
            correction.R003_INDEPENDENT_REVIEWER["task_id"],
        ),
        (correction.R002_PRODUCER["id"], correction.R002_PRODUCER["task_id"]),
        (
            correction.R002_PRIMARY_REVIEWER["id"],
            correction.R002_PRIMARY_REVIEWER["task_id"],
        ),
        (
            correction.R002_INDEPENDENT_REVIEWER["id"],
            correction.R002_INDEPENDENT_REVIEWER["task_id"],
        ),
        (correction.R001_PRODUCER["id"], correction.R001_PRODUCER["task_id"]),
        (
            correction.R001_PRIMARY_REVIEWER["id"],
            correction.R001_PRIMARY_REVIEWER["task_id"],
        ),
        (
            correction.R001_INDEPENDENT_REVIEWER["id"],
            correction.R001_INDEPENDENT_REVIEWER["task_id"],
        ),
    }
    assert len(identities) == 30
    assert {task_id for _identity, task_id in identities} == {
        "/root",
        "/root/r007_preflight_perf_rootcause",
        "/root/goalgraph_fp022_perf_test_design",
        "/root/seq99_serialization_perf_audit",
        "/root/seq99_continuation_perf_audit",
        "/root/seq100_starter_impl",
        correction.R004_PRODUCER["task_id"],
        correction.R004_PRIMARY_REVIEWER["task_id"],
        correction.R004_INDEPENDENT_REVIEWER["task_id"],
        correction.R003_PRODUCER["task_id"],
        correction.R003_PRIMARY_REVIEWER["task_id"],
        correction.R003_INDEPENDENT_REVIEWER["task_id"],
        correction.R002_PRODUCER["task_id"],
        correction.R002_PRIMARY_REVIEWER["task_id"],
        correction.R002_INDEPENDENT_REVIEWER["task_id"],
        correction.R001_PRODUCER["task_id"],
        correction.R001_PRIMARY_REVIEWER["task_id"],
        correction.R001_INDEPENDENT_REVIEWER["task_id"],
    } == {
        "/root",
        "/root/r007_preflight_perf_rootcause",
        "/root/goalgraph_fp022_perf_test_design",
        "/root/seq99_serialization_perf_audit",
        "/root/seq99_continuation_perf_audit",
        "/root/seq100_starter_impl",
        "/root/seq99_r004_recovery_impl",
        "/root/seq99_r004_primary_review",
        "/root/seq99_r004_independent_review",
        "/root/seq99_r003_recovery_impl",
        "/root/seq99_r003_primary_review",
        "/root/seq99_r003_independent_review",
        "/root/seq99_r002_recovery_impl",
        "/root/seq99_r002_primary_review",
        "/root/seq99_r002_independent_review",
        "/root/seq99_correction_impl",
        "/root/seq99_correction_primary_review",
        "/root/seq99_correction_independent_review",
    }


def test_exact_seq98_source_pins_and_authority() -> None:
    raw, source = _source()
    assert len(raw) == 5_076_973
    assert hashlib.sha256(raw).hexdigest() == correction.SOURCE_CHECKPOINT_SHA256
    correction.require_exact_seq98_source(raw, source, ROOT)
    assert source["goal_execution"]["transition_history"][-1]["event_sha256"] == (
        correction.SOURCE_EVENT_SHA256
    )


def test_frozen_r001_review_chain_is_exact_without_live_pin_recomparison() -> None:
    chain = correction.frozen_r001_review_chain(ROOT)
    assert chain == correction.R001_FROZEN_BINDINGS
    assignment = json.loads((ROOT / correction.R001_REVIEW_ASSIGNMENT_REL).read_bytes())
    historical_goal_graph = next(
        row
        for row in assignment["reviewed_control_inputs"]
        if row["path"] == correction.GOAL_GRAPH_SCRIPT_REL.as_posix()
    )
    assert historical_goal_graph != correction._observed_binding(
        ROOT, correction.GOAL_GRAPH_SCRIPT_REL
    )
    assert chain["independent_review"]["sha256"] == (
        "b6459d48984c7785941cde154f6a587f5dee4163ad57a04f4bb4b98c01b77d12"
    )


@pytest.mark.parametrize(
    ("target", "mutation", "message"),
    [
        ("authorization", "tamper", "bytes differ"),
        ("assignment", "mode", "private review authority differs"),
        ("review_result", "hardlink", "physical authority differs"),
    ],
)
def test_frozen_r001_physical_or_byte_tamper_is_rejected(
    tmp_path: Path,
    target: str,
    mutation: str,
    message: str,
) -> None:
    root = _r001_review_fixture(tmp_path)
    path = Path(correction.R001_FROZEN_BINDINGS[target]["path"])
    physical = root / path
    if mutation == "tamper":
        physical.write_bytes(physical.read_bytes() + b" ")
        physical.chmod(0o600)
    elif mutation == "mode":
        physical.chmod(0o644)
    else:
        os.link(physical, root / "r001-hardlink")
    with pytest.raises(correction.StartGateExecutionCorrectionError, match=message):
        correction.frozen_r001_review_chain(root)


def test_r001_projected_preflight_failure_mapping_is_exact() -> None:
    _raw, source = _source()
    observed = correction.r001_projected_preflight_failure(ROOT, source)
    assert set(observed) == {
        "round_id",
        "status",
        "authority_status",
        "observed_command",
        "preflight_exit_code",
        "validator_stage",
        "checkpoint_write_attempted",
        "checkpoint_published",
        "observation_basis",
        "observation_timestamp_available",
        "review_decision",
        "review_findings",
        "primary_reviewed_at",
        "independent_reviewed_at",
        "terminal_error",
        "terminal_error_label_count",
        "terminal_error_labels",
        "source_checkpoint_binding",
        "review_chain_binding",
    }
    assert observed["status"] == "PROJECTED_PREFLIGHT_FAILED_BEFORE_WRITE"
    assert observed["authority_status"] == (
        "NONAUTHORITY_EXECUTION_FAILED_PRE_CAS"
    )
    assert observed["observed_command"] == "--preflight"
    assert observed["preflight_exit_code"] == 1
    assert observed["validator_stage"] == "_validate_projected_with_consumers"
    assert observed["checkpoint_write_attempted"] is False
    assert observed["checkpoint_published"] is False
    assert observed["observation_basis"] == (
        "EXECUTOR_OBSERVED_TERMINAL_OUTPUT_UNSEALED"
    )
    assert observed["observation_timestamp_available"] is False
    assert observed["review_decision"] == "APPROVED"
    assert observed["review_findings"] == {"P0": 0, "P1": 0, "P2": 0}
    assert observed["primary_reviewed_at"] == correction.R001_REVIEW_RESULT_AT
    assert observed["independent_reviewed_at"] == (
        correction.R001_INDEPENDENT_REVIEW_AT
    )
    assert observed["terminal_error"] == correction.R001_PREFLIGHT_TERMINAL_ERROR
    assert observed["terminal_error_label_count"] == 9
    assert observed["terminal_error_labels"] == list(
        correction.R001_PREFLIGHT_PIN_DRIFT_LABELS
    )
    assert observed["review_chain_binding"] == correction.R001_FROZEN_BINDINGS
    assert all(
        label.endswith(
            "reviewed pin differs: scripts/check_walksafe_goal_graph_v2_4.py"
        )
        for label in observed["terminal_error_labels"]
    )


def test_frozen_r002_review_chain_is_exact_without_live_pin_recomparison() -> None:
    _raw, source = _source()
    chain = correction.frozen_r002_review_chain(ROOT, source)
    assert chain == correction.R002_FROZEN_BINDINGS
    assignment = json.loads(
        (ROOT / correction.R002_REVIEW_ASSIGNMENT_REL).read_bytes()
    )
    historical_producer = next(
        row
        for row in assignment["reviewed_control_inputs"]
        if row["path"] == correction.SCRIPT_REL.as_posix()
    )
    assert historical_producer != correction._observed_binding(
        ROOT, correction.SCRIPT_REL
    )
    assert chain["authorization"]["sha256"] == (
        "4f894b0f7b4b594043b1f4dd592366346b478a461de616b0cfac21ac7d826028"
    )
    assert chain["assignment"]["byte_length"] == 26_465
    assert chain["review_result"]["sha256"] == (
        "b33adaa172dce7f30b8b0097a6b7287b08c0d8ea009d64d2608e23efc82ffd36"
    )
    assert chain["independent_review"]["sha256"] == (
        "d4147196164f1fad7bbce9bb615fa292a1f9629a31551580ea23d11abdb69e40"
    )


@pytest.mark.parametrize(
    ("target", "mutation", "message"),
    [
        ("authorization", "tamper", "bytes differ"),
        ("assignment", "mode", "private review authority differs"),
        ("independent_review", "hardlink", "physical authority differs"),
    ],
)
def test_frozen_r002_physical_or_byte_tamper_is_rejected(
    tmp_path: Path,
    target: str,
    mutation: str,
    message: str,
) -> None:
    root = _failure_fixture(tmp_path)
    _r001_review_fixture(root)
    _r002_review_fixture(root)
    path = Path(correction.R002_FROZEN_BINDINGS[target]["path"])
    physical = root / path
    if mutation == "tamper":
        physical.write_bytes(physical.read_bytes() + b" ")
        physical.chmod(0o600)
    elif mutation == "mode":
        physical.chmod(0o644)
    else:
        os.link(physical, root / "r002-hardlink")
    _raw, source = _source()
    with pytest.raises(correction.StartGateExecutionCorrectionError, match=message):
        correction.frozen_r002_review_chain(root, source)


def test_r002_projected_preflight_p0_abort_mapping_is_exact() -> None:
    _raw, source = _source()
    observed = correction.r002_projected_preflight_failure(ROOT, source)
    assert set(observed) == {
        "round_id",
        "status",
        "authority_status",
        "observed_command",
        "preflight_exit_code",
        "elapsed_time",
        "operator_signal",
        "validator_stage",
        "validator_stack",
        "post_review_finding",
        "checkpoint_write_attempted",
        "checkpoint_cas_attempted",
        "checkpoint_published",
        "temporary_artifact_cleaned",
        "observation_basis",
        "observation_timestamp_available",
        "observed_at",
        "terminal_error",
        "terminal_output_sealed",
        "review_decision",
        "review_findings",
        "primary_reviewed_at",
        "independent_reviewed_at",
        "source_checkpoint_binding",
        "review_chain_binding",
    }
    assert observed["round_id"] == "R002"
    assert observed["status"] == (
        "PREFLIGHT_ABORTED_BY_OPERATOR_AFTER_POST_REVIEW_P0_BEFORE_CAS"
    )
    assert observed["authority_status"] == (
        "NONAUTHORITY_POST_REVIEW_P0_PREFLIGHT_ABORTED_PRE_CAS"
    )
    assert observed["preflight_exit_code"] == 130
    assert observed["elapsed_time"] == "GREATER_THAN_74_MINUTES_CPU_BOUND"
    assert observed["operator_signal"] == "SIGINT"
    assert observed["validator_stack"] == list(
        correction.R002_PREFLIGHT_VALIDATOR_STACK
    )
    assert observed["post_review_finding"] == {
        "severity": "P0",
        "class": "PREFLIGHT_PERFORMANCE_DEFECT",
    }
    assert observed["checkpoint_write_attempted"] is False
    assert observed["checkpoint_cas_attempted"] is False
    assert observed["checkpoint_published"] is False
    assert observed["temporary_artifact_cleaned"] is True
    assert observed["observation_timestamp_available"] is True
    assert observed["observed_at"] == "2026-08-28T01:28:39+09:00"
    assert observed["terminal_error"] == "KeyboardInterrupt"
    assert observed["terminal_output_sealed"] is False
    assert observed["review_decision"] == "APPROVED"
    assert observed["review_findings"] == {"P0": 0, "P1": 0, "P2": 0}
    assert observed["primary_reviewed_at"] == correction.R002_REVIEW_RESULT_AT
    assert observed["independent_reviewed_at"] == (
        correction.R002_INDEPENDENT_REVIEW_AT
    )
    assert observed["review_chain_binding"] == correction.R002_FROZEN_BINDINGS


def test_frozen_r003_partial_review_is_exact_and_decisions_are_absent() -> None:
    _raw, source = _source()
    observed = correction.frozen_r003_partial_review(ROOT, source)
    assert observed == correction.R003_FROZEN_BINDINGS
    assert observed["authorization"] == {
        "path": correction.R003_AUTHORIZATION_REL.as_posix(),
        "sha256": (
            "d81bb92f1c10d59c0ebda6e8aa58be5a5bd9dbeddaa1762c2b91e93abb7ca7dd"
        ),
        "byte_length": 29_117,
    }
    assert observed["assignment"] == {
        "path": correction.R003_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "dc06fd6461718f58514fa689046cee83c6a833b8c87189d8199acf92232f92a6"
        ),
        "byte_length": 33_870,
    }
    assert all(not (ROOT / path).exists() for path in correction.R003_DECISION_PATHS)


@pytest.mark.parametrize(
    "mutation",
    ["authorization-bytes", "assignment-mode", "assignment-hardlink", "result"],
)
def test_frozen_r003_partial_review_tamper_or_decision_is_rejected(
    tmp_path: Path,
    mutation: str,
) -> None:
    root = _failure_fixture(tmp_path)
    _r001_review_fixture(root)
    _r002_review_fixture(root)
    _r003_partial_review_fixture(root)
    if mutation == "authorization-bytes":
        target = root / correction.R003_AUTHORIZATION_REL
        target.write_bytes(target.read_bytes() + b" ")
        target.chmod(0o600)
    elif mutation == "assignment-mode":
        (root / correction.R003_REVIEW_ASSIGNMENT_REL).chmod(0o644)
    elif mutation == "assignment-hardlink":
        os.link(root / correction.R003_REVIEW_ASSIGNMENT_REL, root / "r003-hardlink")
    else:
        target = root / correction.R003_REVIEW_RESULT_REL
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"{}\n")
        target.chmod(0o600)
    _raw, source = _source()
    with pytest.raises(correction.StartGateExecutionCorrectionError):
        correction.frozen_r003_partial_review(root, source)


def test_r003_primary_review_rejection_mapping_is_exact() -> None:
    _raw, source = _source()
    observed = correction.r003_primary_review_rejection(ROOT, source)
    assert observed["round_id"] == "R003"
    assert observed["status"] == (
        "NONAUTHORITY_PRIMARY_REVIEW_P1_REJECTED_BEFORE_RESULT"
    )
    assert observed["authority_status"] == observed["status"]
    assert observed["review_stage"] == "PRIMARY_REVIEW"
    assert observed["review_decision"] == "REJECTED"
    assert observed["review_findings"] == {"P0": 0, "P1": 1, "P2": 0}
    assert observed["finding"] == {
        "severity": "P1",
        "class": "BOOLEAN_INTEGER_TYPE_CONFUSION_IN_REVIEW_FINDINGS",
    }
    assert observed["review_result_written"] is False
    assert observed["independent_review_written"] is False
    assert observed["checkpoint_write_attempted"] is False
    assert observed["checkpoint_cas_attempted"] is False
    assert observed["checkpoint_published"] is False
    assert observed["observed_at"] == "2026-08-28T02:00:00+09:00"
    assert observed["review_partial_binding"] == correction.R003_FROZEN_BINDINGS


def test_frozen_r004_review_chain_is_exact_and_immutable() -> None:
    _raw, source = _source()
    observed = correction.frozen_r004_review_chain(ROOT, source)
    assert observed == correction.R004_FROZEN_BINDINGS
    assert observed["authorization"] == {
        "path": correction.R004_AUTHORIZATION_REL.as_posix(),
        "sha256": (
            "9ccf0bd9a43417b5fed8efd480dafef1b766262589a979ac1b9a916893c08378"
        ),
        "byte_length": 34_701,
    }
    assert observed["assignment"]["sha256"] == (
        "24fe0232f3e6d5aeb49bcb98fed1d29d3b28f8a91cb95acd440d3fbf3fbb5cc9"
    )
    assert observed["review_result"]["sha256"] == (
        "cfdbb9023e08076bc0c857c4e0e21d05b8ee1d2b1868670143ab3dad8d28f4a1"
    )
    assert observed["independent_review"]["sha256"] == (
        "1b23af84c185f3d88c3c17a9abb4e9b4449191d983b1365b1d56af9fe8cd1cda"
    )


@pytest.mark.parametrize(
    ("target", "mutation", "message"),
    [
        ("authorization", "tamper", "bytes differ"),
        ("assignment", "mode", "private review authority differs"),
        ("independent_review", "hardlink", "physical authority differs"),
    ],
)
def test_frozen_r004_physical_or_byte_tamper_is_rejected(
    tmp_path: Path,
    target: str,
    mutation: str,
    message: str,
) -> None:
    root = _failure_fixture(tmp_path)
    _r001_review_fixture(root)
    _r002_review_fixture(root)
    _r003_partial_review_fixture(root)
    _r004_review_fixture(root)
    path = Path(correction.R004_FROZEN_BINDINGS[target]["path"])
    physical = root / path
    if mutation == "tamper":
        physical.write_bytes(physical.read_bytes() + b" ")
        physical.chmod(0o600)
    elif mutation == "mode":
        physical.chmod(0o644)
    else:
        os.link(physical, root / "r004-hardlink")
    _raw, source = _source()
    with pytest.raises(correction.StartGateExecutionCorrectionError, match=message):
        correction.frozen_r004_review_chain(root, source)


def test_r004_projected_preflight_p0_abort_mapping_is_exact() -> None:
    _raw, source = _source()
    observed = correction.r004_projected_preflight_failure(ROOT, source)
    assert observed["round_id"] == "R004"
    assert observed["status"] == (
        "NONAUTHORITY_POST_REVIEW_P0_PREFLIGHT_ABORTED_PRE_CAS"
    )
    assert observed["authority_status"] == observed["status"]
    assert observed["observed_command"] == "--preflight"
    assert type(observed["preflight_exit_code"]) is int
    assert observed["preflight_exit_code"] == 1
    assert observed["elapsed_time"] == "GREATER_THAN_10_MINUTES_CPU_BOUND"
    assert observed["operator_signal"] == "SIGINT"
    assert observed["terminal_error"] == "KeyboardInterrupt"
    assert observed["observed_at"] == "2026-08-28T02:45:27+09:00"
    assert observed["validator_stack"] == list(
        correction.R004_PREFLIGHT_VALIDATOR_STACK
    )
    assert len(observed["validator_stack"]) == 14
    assert observed["validator_stack"][-4:] == [
        "seq96.require_contract_corrected_checkpoint",
        "_require_frozen_seq95_checkpoint",
        "seq90.checkpoint_json_bytes",
        "json.dumps",
    ]
    assert observed["post_review_finding"] == {
        "severity": "P0",
        "class": "CONTINUATION_DESCENDANT_RECONSTRUCTION_PERFORMANCE_DEFECT",
    }
    assert observed["checkpoint_write_attempted"] is False
    assert observed["checkpoint_cas_attempted"] is False
    assert observed["checkpoint_published"] is False
    assert observed["review_findings"] == {"P0": 0, "P1": 0, "P2": 0}
    assert observed["review_chain_binding"] == correction.R004_FROZEN_BINDINGS


def test_frozen_r005_review_and_preflight_abort_are_exact() -> None:
    _raw, source = _source()
    chain = correction.frozen_r005_review_chain(ROOT, source)
    assert chain == correction.R005_FROZEN_BINDINGS
    assert chain["authorization"]["sha256"] == (
        "4e4eb3312482082e031086f5241cea1ad6712679015b8d79e1707aa0cc853afa"
    )
    assert chain["assignment"]["sha256"] == (
        "c72e15473c8f9d100c5605aa73ec131ed9810c65b8d7ac565ab94fae4d7155c8"
    )
    observed = correction.r005_projected_preflight_failure(ROOT, source)
    assert observed["round_id"] == "R005"
    assert observed["status"] == (
        "NONAUTHORITY_POST_REVIEW_P0_PREFLIGHT_ABORTED_PRE_CAS"
    )
    assert observed["preflight_exit_code"] == 130
    assert observed["elapsed_time"] == "GREATER_THAN_10_MINUTES_CPU_BOUND"
    assert observed["elapsed_seconds"] == "628.97"
    assert observed["cpu_percent"] == "99%"
    assert observed["max_rss_kb"] == 223_160
    assert observed["operator_signal"] == "SIGINT"
    assert observed["observed_at"] == "2026-08-28T03:40:21+09:00"
    assert observed["terminal_error"] == "KeyboardInterrupt"
    assert observed["validator_stack"] == list(
        correction.R005_PREFLIGHT_VALIDATOR_STACK
    )
    assert len(observed["validator_stack"]) == 18
    assert observed["post_review_finding"] == {
        "severity": "P0",
        "class": "GOAL_GRAPH_LEGACY_COMPLETION_OVERLAY_REPLAY_PERFORMANCE_DEFECT",
    }
    assert observed["checkpoint_write_attempted"] is False
    assert observed["checkpoint_cas_attempted"] is False
    assert observed["checkpoint_published"] is False
    assert observed["temporary_artifact_cleaned"] is True
    assert observed["review_chain_binding"] == correction.R005_FROZEN_BINDINGS


@pytest.mark.parametrize(
    ("target", "mutation"),
    [("authorization", "tamper"), ("assignment", "mode")],
)
def test_frozen_r005_physical_or_byte_tamper_is_rejected(
    tmp_path: Path,
    target: str,
    mutation: str,
) -> None:
    root = _failure_fixture(tmp_path)
    _r001_review_fixture(root)
    _r002_review_fixture(root)
    _r003_partial_review_fixture(root)
    _r004_review_fixture(root)
    _r005_review_fixture(root)
    physical = root / Path(correction.R005_FROZEN_BINDINGS[target]["path"])
    if mutation == "tamper":
        physical.write_bytes(physical.read_bytes() + b" ")
        physical.chmod(0o600)
    else:
        physical.chmod(0o644)
    _raw, source = _source()
    with pytest.raises(correction.StartGateExecutionCorrectionError):
        correction.frozen_r005_review_chain(root, source)


def test_frozen_r006_rejection_post_documents_and_timeout_are_exact() -> None:
    _raw, source = _source()
    chain = correction.frozen_r006_review_chain(ROOT, source)
    assert chain == correction.R006_FROZEN_BINDINGS
    observed = correction.r006_rejected_review_and_invalid_preflight(ROOT, source)
    assert observed["round_id"] == "R006"
    assert observed["authority_status"] == (
        "NONAUTHORITY_PRIMARY_REVIEW_P1_REJECTED"
    )
    assert observed["unsealed_formal_primary_review_observation"] == {
        "decision": "REJECTED",
        "findings": {"P0": 0, "P1": 1, "P2": 0},
        "finding_class": (
            "REPOSITORY_CATALOG_MISSING_R006_AUTHORIZATION_ASSIGNMENT"
        ),
        "stored_catalog_path_count": 4_220,
        "live_path_count": 4_222,
        "catalog_test_result": {"failed": 2, "passed": 17},
        "missing_paths": [
            correction.R006_AUTHORIZATION_REL.as_posix(),
            correction.R006_REVIEW_ASSIGNMENT_REL.as_posix(),
        ],
        "review_result_present_at_rejection": False,
        "independent_review_present_at_rejection": False,
        "observation_basis": "PRIMARY_REVIEWER_OBSERVED_TEST_OUTPUT_UNSEALED",
        "observation_timestamp_available": False,
    }
    assert observed["post_rejection_documents"]["authority_status"] == (
        "NONAUTHORITY_POST_REJECTION_DOCUMENTS"
    )
    preflight = observed["invalid_reuse_preflight"]
    assert preflight["status"] == (
        "NONAUTHORITY_INVALID_ROUND_REUSE_TIMEOUT_PRE_CAS"
    )
    assert preflight["timeout_wrapper_exit_code"] == 124
    assert preflight["child_signal_number"] == 2
    assert preflight["child_signal_name"] == "SIGINT"
    assert preflight["elapsed_seconds"] == "300.08"
    assert preflight["max_rss_kb"] == 217_872
    assert preflight["terminal_error"] == "KeyboardInterrupt"
    assert preflight["checkpoint_write_attempted"] is False
    assert preflight["checkpoint_cas_attempted"] is False
    assert preflight["checkpoint_published"] is False
    assert preflight["termination_timestamp_available"] is False
    assert preflight["post_abort_recorded_at"] == (
        correction.R006_PREFLIGHT_POST_ABORT_RECORDED_AT
    )


@pytest.mark.parametrize(
    ("target", "mutation"),
    [("authorization", "tamper"), ("independent_review", "mode")],
)
def test_frozen_r006_physical_or_byte_tamper_is_rejected(
    tmp_path: Path,
    target: str,
    mutation: str,
) -> None:
    root = _failure_fixture(tmp_path)
    _r006_review_fixture(root)
    physical = root / Path(correction.R006_FROZEN_BINDINGS[target]["path"])
    if mutation == "tamper":
        physical.write_bytes(physical.read_bytes() + b" ")
        physical.chmod(0o600)
    else:
        physical.chmod(0o644)
    _raw, source = _source()
    with pytest.raises(correction.StartGateExecutionCorrectionError):
        correction.frozen_r006_review_chain(root, source)


def test_frozen_r007_review_chain_is_exact() -> None:
    _raw, source = _source()
    chain = correction.frozen_r007_review_chain(ROOT, source)
    assert chain == correction.R007_FROZEN_BINDINGS
    assert chain == {
        "authorization": {
            "path": correction.R007_AUTHORIZATION_REL.as_posix(),
            "sha256": (
                "da855dea2cad4476af38a9990ed9a5114544d0c1408f727833965ae9561ad2ed"
            ),
            "byte_length": 60_513,
        },
        "assignment": {
            "path": correction.R007_REVIEW_ASSIGNMENT_REL.as_posix(),
            "sha256": (
                "3d4c930732d16a6765a3820cdb9a52566c43aa32c2f4b14388efba0821fe05a9"
            ),
            "byte_length": 65_721,
        },
        "review_result": {
            "path": correction.R007_REVIEW_RESULT_REL.as_posix(),
            "sha256": (
                "eea72426a22710e933e5d5ab7b9c5379be5dd2d2044eba841ba085fdd3ad4f14"
            ),
            "byte_length": 756,
        },
        "independent_review": {
            "path": correction.R007_INDEPENDENT_REVIEW_REL.as_posix(),
            "sha256": (
                "70b49b9760a87c3b2724e7faa30b1be563c4f5b8f4ee5a80e9c3b943f909ca96"
            ),
            "byte_length": 1_018,
        },
    }


@pytest.mark.parametrize(
    ("target", "mutation", "message"),
    [
        ("authorization", "tamper", "bytes differ"),
        ("assignment", "mode", "private review authority differs"),
        ("review_result", "tamper", "bytes differ"),
        ("independent_review", "mode", "private review authority differs"),
    ],
)
def test_frozen_r007_physical_or_byte_tamper_is_rejected(
    tmp_path: Path,
    target: str,
    mutation: str,
    message: str,
) -> None:
    root = _failure_fixture(tmp_path)
    _r006_review_fixture(root)
    _r007_review_fixture(root)
    physical = root / Path(correction.R007_FROZEN_BINDINGS[target]["path"])
    if mutation == "tamper":
        physical.write_bytes(physical.read_bytes() + b" ")
        physical.chmod(0o600)
    elif mutation == "mode":
        physical.chmod(0o644)
    _raw, source = _source()
    with pytest.raises(correction.StartGateExecutionCorrectionError, match=message):
        correction.frozen_r007_review_chain(root, source)


def test_r007_projected_preflight_timeout_mapping_is_exact() -> None:
    _raw, source = _source()
    observed = correction.r007_projected_preflight_failure(ROOT, source)
    assert set(observed) == {
        "round_id",
        "status",
        "authority_status",
        "observed_command",
        "timeout_wrapper_exit_code",
        "child_signal_number",
        "child_signal_name",
        "elapsed_seconds",
        "cpu_percent",
        "max_rss_kb",
        "validator_stage",
        "validator_stack",
        "post_review_finding",
        "terminal_error",
        "checkpoint_write_attempted",
        "checkpoint_cas_attempted",
        "checkpoint_published",
        "temporary_artifact_present",
        "termination_timestamp_available",
        "post_abort_recorded_at",
        "review_decision",
        "review_findings",
        "primary_reviewed_at",
        "independent_reviewed_at",
        "source_checkpoint_binding",
        "review_chain_binding",
    }
    assert observed["round_id"] == "R007"
    assert observed["status"] == (
        "NONAUTHORITY_POST_REVIEW_P0_PREFLIGHT_TIMEOUT_PRE_CAS"
    )
    assert observed["authority_status"] == observed["status"]
    assert observed["observed_command"] == "--preflight"
    assert observed["timeout_wrapper_exit_code"] == 124
    assert observed["child_signal_number"] == 2
    assert observed["child_signal_name"] == "SIGINT"
    assert observed["elapsed_seconds"] == "180.19"
    assert observed["cpu_percent"] == "99%"
    assert observed["max_rss_kb"] == 219_864
    assert observed["validator_stage"] == "seq90._validate_projected_with_consumers"
    assert observed["validator_stack"] == list(
        correction.R007_PREFLIGHT_VALIDATOR_STACK
    )
    assert len(observed["validator_stack"]) == 11
    assert observed["validator_stack"][-1] == "copy.deepcopy(history[:65])"
    assert observed["post_review_finding"] == {
        "severity": "P0",
        "class": "GOAL_GRAPH_SEQ99_REVIEW_CHAIN_REPLAY_PERFORMANCE_DEFECT",
    }
    assert observed["terminal_error"] == correction.R007_PREFLIGHT_TERMINAL_ERROR
    assert observed["checkpoint_write_attempted"] is False
    assert observed["checkpoint_cas_attempted"] is False
    assert observed["checkpoint_published"] is False
    assert observed["temporary_artifact_present"] is False
    assert observed["termination_timestamp_available"] is False
    assert observed["post_abort_recorded_at"] == (
        correction.R007_PREFLIGHT_POST_ABORT_RECORDED_AT
    )
    assert observed["review_decision"] == "APPROVED"
    assert observed["review_findings"] == {"P0": 0, "P1": 0, "P2": 0}
    assert observed["primary_reviewed_at"] == correction.R007_REVIEW_RESULT_AT
    assert observed["independent_reviewed_at"] == (
        correction.R007_INDEPENDENT_REVIEW_AT
    )
    assert observed["source_checkpoint_binding"]["r010_execution_failure"] == (
        correction.r010_execution_failure_binding(ROOT)
    )
    assert observed["review_chain_binding"] == correction.R007_FROZEN_BINDINGS


def test_frozen_r008_review_chain_is_exact() -> None:
    _raw, source = _source()
    chain = correction.frozen_r008_review_chain(ROOT, source)
    assert chain == correction.R008_FROZEN_BINDINGS
    for path in correction.R008_REVIEW_PATHS:
        metadata = (ROOT / path).stat()
        assert metadata.st_mode & 0o777 == 0o600
        assert metadata.st_nlink == 1
    assert chain == {
        "authorization": {
            "path": correction.R008_AUTHORIZATION_REL.as_posix(),
            "sha256": (
                "262d7e076251ff0de231e8ba7dc83902f0f73f015d8ed7305fb86e15194520b6"
            ),
            "byte_length": 68_025,
        },
        "assignment": {
            "path": correction.R008_REVIEW_ASSIGNMENT_REL.as_posix(),
            "sha256": (
                "9a6b8e024dad855947eae819b5a1bfe1434d70264720ba7df8f1b3c6bfe403f2"
            ),
            "byte_length": 73_233,
        },
        "review_result": {
            "path": correction.R008_REVIEW_RESULT_REL.as_posix(),
            "sha256": (
                "a7aa97280df76e99b6bcadd3197de1c042df421fb7bb27f174ce3fcd3cfd35f0"
            ),
            "byte_length": 756,
        },
        "independent_review": {
            "path": correction.R008_INDEPENDENT_REVIEW_REL.as_posix(),
            "sha256": (
                "05591a76c6bad39ea0389c215cb028aeb897b56ab828f053eb0c2e62f90208c0"
            ),
            "byte_length": 1_020,
        },
    }


@pytest.mark.parametrize(
    ("target", "mutation", "message"),
    [
        ("authorization", "tamper", "bytes differ"),
        ("assignment", "mode", "private review authority differs"),
        ("review_result", "tamper", "bytes differ"),
        ("independent_review", "mode", "private review authority differs"),
    ],
)
def test_frozen_r008_physical_or_byte_tamper_is_rejected(
    tmp_path: Path,
    target: str,
    mutation: str,
    message: str,
) -> None:
    root = _failure_fixture(tmp_path)
    _r006_review_fixture(root)
    _r007_review_fixture(root)
    _r008_review_fixture(root)
    physical = root / Path(correction.R008_FROZEN_BINDINGS[target]["path"])
    if mutation == "tamper":
        physical.write_bytes(physical.read_bytes() + b" ")
        physical.chmod(0o600)
    elif mutation == "mode":
        physical.chmod(0o644)
    _raw, source = _source()
    with pytest.raises(correction.StartGateExecutionCorrectionError, match=message):
        correction.frozen_r008_review_chain(root, source)


def test_r008_projected_preflight_failure_mapping_is_exact() -> None:
    _raw, source = _source()
    observed = correction.r008_projected_preflight_failure(ROOT, source)
    assert set(observed) == {
        "round_id",
        "status",
        "authority_status",
        "observed_command",
        "preflight_exit_code",
        "elapsed_seconds",
        "cpu_percent",
        "max_rss_kb",
        "ordered_terminal_error_prefix",
        "checkpoint_write_attempted",
        "checkpoint_cas_attempted",
        "checkpoint_published",
        "observation_basis",
        "validator_stack_available",
        "full_terminal_output_available",
        "termination_timestamp_available",
        "review_decision",
        "review_findings",
        "primary_reviewed_at",
        "independent_reviewed_at",
        "source_checkpoint_binding",
        "review_chain_binding",
    }
    assert observed["round_id"] == "R008"
    assert observed["status"] == (
        "NONAUTHORITY_POST_REVIEW_PREFLIGHT_FAILED_PRE_CAS"
    )
    assert observed["authority_status"] == observed["status"]
    assert observed["observed_command"] == "--preflight"
    assert type(observed["preflight_exit_code"]) is int
    assert observed["preflight_exit_code"] == 1
    assert observed["elapsed_seconds"] == "133.08"
    assert observed["cpu_percent"] == "100%"
    assert observed["max_rss_kb"] == 224_436
    assert observed["ordered_terminal_error_prefix"] == [
        "FP048 R002 reviewed noncredit successor authority differs",
        "NPC/FP022 successor authority differs",
        "FP048 R002 reviewed successor composition differs",
    ]
    assert observed["checkpoint_write_attempted"] is False
    assert observed["checkpoint_cas_attempted"] is False
    assert observed["checkpoint_published"] is False
    assert observed["observation_basis"] == (
        "EXECUTOR_OBSERVED_TERMINAL_OUTPUT_UNSEALED"
    )
    assert observed["validator_stack_available"] is False
    assert observed["full_terminal_output_available"] is False
    assert observed["termination_timestamp_available"] is False
    assert observed["review_decision"] == "APPROVED"
    assert observed["review_findings"] == {"P0": 0, "P1": 0, "P2": 0}
    assert observed["primary_reviewed_at"] == correction.R008_REVIEW_RESULT_AT
    assert observed["independent_reviewed_at"] == (
        correction.R008_INDEPENDENT_REVIEW_AT
    )
    assert observed["source_checkpoint_binding"]["r010_execution_failure"] == (
        correction.r010_execution_failure_binding(ROOT)
    )
    assert observed["review_chain_binding"] == correction.R008_FROZEN_BINDINGS


def test_frozen_r009_review_chain_is_exact() -> None:
    _raw, source = _source()
    chain = correction.frozen_r009_review_chain(ROOT, source)
    assert chain == correction.R009_FROZEN_BINDINGS
    for path in correction.R009_REVIEW_PATHS:
        metadata = (ROOT / path).stat()
        assert metadata.st_mode & 0o777 == 0o600
        assert metadata.st_nlink == 1
    assert chain == {
        "authorization": {
            "path": correction.R009_AUTHORIZATION_REL.as_posix(),
            "sha256": (
                "ed15e72f3651df52e17bff822e9e640e3b403a942054880c2e2f7e88cb2ad51e"
            ),
            "byte_length": 74_981,
        },
        "assignment": {
            "path": correction.R009_REVIEW_ASSIGNMENT_REL.as_posix(),
            "sha256": (
                "bad7546feec5aa98d3d9baae6f980ffcd5de6f809552c1dff65be04933e21fac"
            ),
            "byte_length": 80_189,
        },
        "review_result": {
            "path": correction.R009_REVIEW_RESULT_REL.as_posix(),
            "sha256": (
                "db8f427ea965bae9bbf1d8aa6b80d68b6ecbc97cb96b7e9ed7a4fd257e064ea1"
            ),
            "byte_length": 756,
        },
        "independent_review": {
            "path": correction.R009_INDEPENDENT_REVIEW_REL.as_posix(),
            "sha256": (
                "c661cb8d829d6efebc04ae48e82e78af825f341b93e3cd6e068844ccb4eb1ebb"
            ),
            "byte_length": 1_020,
        },
    }


@pytest.mark.parametrize(
    ("target", "mutation", "message"),
    [
        ("authorization", "tamper", "bytes differ"),
        ("assignment", "mode", "private review authority differs"),
        ("review_result", "tamper", "bytes differ"),
        ("independent_review", "mode", "private review authority differs"),
    ],
)
def test_frozen_r009_physical_or_byte_tamper_is_rejected(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    target: str,
    mutation: str,
    message: str,
) -> None:
    _raw, source = _source()
    r008_failure = correction.r008_projected_preflight_failure(ROOT, source)
    monkeypatch.setattr(
        correction,
        "r008_projected_preflight_failure",
        lambda _root, _source: copy.deepcopy(r008_failure),
    )
    root = _r009_review_fixture(tmp_path)
    physical = root / Path(correction.R009_FROZEN_BINDINGS[target]["path"])
    if mutation == "tamper":
        physical.write_bytes(physical.read_bytes() + b" ")
        physical.chmod(0o600)
    else:
        physical.chmod(0o644)
    with pytest.raises(correction.StartGateExecutionCorrectionError, match=message):
        correction.frozen_r009_review_chain(root, source)


def test_r009_post_review_failure_is_generic_and_inference_is_nonexclusive() -> None:
    _raw, source = _source()
    observed = correction.r009_post_review_write_failure(ROOT, source)
    assert observed["round_id"] == "R009"
    assert observed["status"] == (
        "NONAUTHORITY_POST_REVIEW_WRITE_FAILED_NO_PUBLISH_SOURCE_RETAINED"
    )
    assert observed["authority_status"] == observed["status"]
    assert observed["preflight_observation"] == {
        "observed_command": "--preflight",
        "occurred_at": correction.R009_PROJECTED_EVENT_OCCURRED_AT,
        "exit_code": 0,
        "elapsed_seconds": "195.88",
        "elapsed_wall_clock": "3:15.88",
        "cpu_percent": "100%",
        "max_rss_kb": 229_988,
        "event_sha256": correction.R009_PROJECTED_EVENT_SHA256,
        "checkpoint_published": False,
    }
    direct = observed["write_observation"]["direct_observation"]
    assert direct["observed_command"] == "--write"
    assert direct["exit_code"] == 1
    assert direct["terminal_error"] == correction.R009_WRITE_TERMINAL_ERROR
    assert direct["checkpoint_write_attempted"] is True
    assert direct["checkpoint_published"] is False
    assert direct["terminal_checkpoint_published"] is False
    assert direct["final_checkpoint_binding"] == observed[
        "source_checkpoint_binding"
    ]
    assert direct["recovery_temp_present"] is False
    assert direct["process_present"] is False
    assert not {
        "checkpoint_cas_attempted",
        "exchange_attempted",
        "rollback_attempted",
        "rollback_completed",
    }.intersection(direct)
    inference = observed["write_observation"]["control_flow_inference"]
    assert inference["classification"] == (
        "LIKELY_POST_EXCHANGE_CAS_GUARD_FAILED_ROLLED_BACK"
    )
    assert inference["basis"] == (
        "CONSISTENT_WITH_PROJECTED_GUARD_AFTER_PREVIOUS_SAME_"
        "OCCURRED_AT_PREFLIGHT_PASS_AND_FINAL_EXACT_SOURCE_NO_TEMP"
    )
    assert inference["non_exclusive"] is True
    assert {
        key for key in inference if key.endswith("_inferred")
    } == {
        "checkpoint_cas_attempted_inferred",
        "exchange_attempted_inferred",
        "transient_projected_checkpoint_inferred",
        "post_exchange_validation_failed_inferred",
        "rollback_attempted_inferred",
        "rollback_completed_inferred",
    }
    assert all(
        value is True
        for key, value in inference.items()
        if key.endswith("_inferred")
    )
    assert observed["review_chain_binding"] == correction.R009_FROZEN_BINDINGS


def test_r010_authorization_and_assignment_bind_all_failed_rounds() -> None:
    _raw, source = _source()
    authorization_raw = correction.build_authorization(ROOT, source)
    assignment_raw = correction.build_review_assignment(ROOT, source)
    authorization = json.loads(authorization_raw)
    assignment = json.loads(assignment_raw)
    for document in (authorization, assignment):
        assert document["supersedes_round_id"] == "R009"
        assert document["superseded_review_chain_binding"] == (
            correction.R009_FROZEN_BINDINGS
        )
        assert document["r001_projected_preflight_failure"]["terminal_error"] == (
            correction.R001_PREFLIGHT_TERMINAL_ERROR
        )
        assert document["r001_projected_preflight_failure"][
            "checkpoint_published"
        ] is False
        assert document["r002_projected_preflight_failure"]["authority_status"] == (
            "NONAUTHORITY_POST_REVIEW_P0_PREFLIGHT_ABORTED_PRE_CAS"
        )
        assert document["r002_projected_preflight_failure"][
            "review_chain_binding"
        ] == correction.R002_FROZEN_BINDINGS
        assert document["r003_primary_review_rejection"]["status"] == (
            "NONAUTHORITY_PRIMARY_REVIEW_P1_REJECTED_BEFORE_RESULT"
        )
        assert document["r003_primary_review_rejection"]["review_partial_binding"] == (
            correction.R003_FROZEN_BINDINGS
        )
        assert document["r004_projected_preflight_failure"]["status"] == (
            "NONAUTHORITY_POST_REVIEW_P0_PREFLIGHT_ABORTED_PRE_CAS"
        )
        assert document["r004_projected_preflight_failure"][
            "review_chain_binding"
        ] == correction.R004_FROZEN_BINDINGS
        assert document["r005_projected_preflight_failure"]["elapsed_seconds"] == (
            "628.97"
        )
        assert document["r005_projected_preflight_failure"][
            "review_chain_binding"
        ] == correction.R005_FROZEN_BINDINGS
        assert document["r006_rejected_review_and_invalid_preflight"][
            "authority_status"
        ] == "NONAUTHORITY_PRIMARY_REVIEW_P1_REJECTED"
        assert document["r006_rejected_review_and_invalid_preflight"][
            "review_chain_binding"
        ] == correction.R006_FROZEN_BINDINGS
        assert document["r007_projected_preflight_failure"]["status"] == (
            "NONAUTHORITY_POST_REVIEW_P0_PREFLIGHT_TIMEOUT_PRE_CAS"
        )
        assert document["r007_projected_preflight_failure"][
            "review_chain_binding"
        ] == correction.R007_FROZEN_BINDINGS
        assert document["r008_projected_preflight_failure"]["status"] == (
            "NONAUTHORITY_POST_REVIEW_PREFLIGHT_FAILED_PRE_CAS"
        )
        assert document["r008_projected_preflight_failure"][
            "review_chain_binding"
        ] == correction.R008_FROZEN_BINDINGS
        assert document["r009_post_review_write_failure"]["status"] == (
            "NONAUTHORITY_POST_REVIEW_WRITE_FAILED_NO_PUBLISH_SOURCE_RETAINED"
        )
        assert document["r009_post_review_write_failure"][
            "review_chain_binding"
        ] == correction.R009_FROZEN_BINDINGS
        assert document["r009_post_review_write_failure"][
            "write_observation"
        ]["control_flow_inference"]["non_exclusive"] is True
    assert authorization["document_id"].endswith("R010")
    assert assignment["document_id"].endswith("R010")
    assert assignment["round_id"] == "R010"
    assert assignment["authorization_binding"] == correction._binding(
        correction.AUTHORIZATION_REL, authorization_raw
    )


def test_r010_failure_binding_is_exact_consumed_nonauthority() -> None:
    failure = correction.r010_execution_failure_binding(ROOT)
    assert failure["status"] == "FAILED_AFTER_ALL_CHECKS_BEFORE_RECEIPT"
    assert failure["authority_status"] == "NONAUTHORITY"
    assert failure["event_identity_status"] == "CONSUMED_FAILED_NO_RECEIPT"
    assert failure["failure_phase"] == "POST_CHECK_CONTEXT_REVALIDATION"
    assert failure["runner_exit_code"] == 2
    assert failure["error"] == "R010 start namespace unexpectedly exists"
    assert len(failure["logs"]) == 5
    assert {row["exit_code"] for row in failure["logs"]} == {0}


@pytest.mark.parametrize("target_name", ["01-CONTINUATION.log", "05-REPOSITORY_STATE.log"])
def test_r010_failure_log_tamper_is_rejected(
    tmp_path: Path, target_name: str
) -> None:
    root = _failure_fixture(tmp_path)
    target = root / correction.R010_GATE_REL / target_name
    target.write_bytes(target.read_bytes() + b"tamper")
    target.chmod(0o600)
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="R010 failed log differs",
    ):
        correction.r010_execution_failure_binding(root)


def test_r010_contract_tamper_is_rejected(tmp_path: Path) -> None:
    root = _failure_fixture(tmp_path)
    contract = root / correction.R010_CONTRACT_REL
    contract.write_bytes(contract.read_bytes() + b"\n")
    contract.chmod(correction.R010_CONTRACT_MODE)
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="R010 failed contract differs",
    ):
        correction.r010_execution_failure_binding(root)


def test_r010_receipt_or_staging_emergence_is_rejected(
    tmp_path: Path,
) -> None:
    root = _failure_fixture(tmp_path)
    receipt = root / correction.R010_RECEIPT_REL
    receipt.write_bytes(b"{}\n")
    receipt.chmod(0o600)
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="unexpectedly acquired receipt authority",
    ):
        correction.r010_execution_failure_binding(root)
    receipt.unlink()
    stage = root / correction.R010_RECEIPT_STAGE_REL
    stage.write_bytes(b"{}\n")
    stage.chmod(0o600)
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="unexpectedly acquired receipt authority",
    ):
        correction.r010_execution_failure_binding(root)


def test_r010_namespace_and_log_authority_are_enforced(tmp_path: Path) -> None:
    root = _failure_fixture(tmp_path)
    gate = root / correction.R010_GATE_REL
    gate.chmod(0o755)
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="namespace authority differs",
    ):
        correction.r010_execution_failure_binding(root)
    gate.chmod(0o700)
    log = gate / "02-GOAL_GRAPH.log"
    log.chmod(0o644)
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="failed log differs",
    ):
        correction.r010_execution_failure_binding(root)


def test_r011_contract_is_observed_and_canonical() -> None:
    binding = correction.r011_contract_binding(ROOT)
    assert binding == {
        "schema_version": "1.2",
        "document_id": correction.R011_DOCUMENT_ID,
        "path": correction.R011_CONTRACT_REL.as_posix(),
        "file_sha256": "05e771dcbdcca9c48c1fd2b6af77f50b5c6b7112aa712ff41e9a3326131b86df",
        "contract_id": correction.R011_CONTRACT_ID,
        "contract_version": "2026-08-27.2",
        "canonical_contract_sha256": (
            "0cfd22fef38acac7c61e8102ebc7b2c1c4f46c371e3b2a1c8c8a00afc449d43e"
        ),
    }


def test_projection_is_ready_to_ready_zero_credit_and_inverse_exact() -> None:
    source_raw, projected, event = _projection()
    assert event["sequence"] == 99
    assert type(event["sequence"]) is int
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert event["evidence_refs"] == [
        "FP048-R002_EXACT_PUBLISHED_SEQ98_SOURCE",
        "FP048-R002_R010_005_ALL_CHECKS_POST_CONTEXT_FAILURE_NONAUTHORITY",
        "FP048-R002_SEQ99_R001_PREFLIGHT_FAILED_NONAUTHORITY",
        "FP048-R002_SEQ99_R002_PREFLIGHT_ABORTED_POST_REVIEW_P0_NONAUTHORITY",
        "FP048-R002_SEQ99_R003_PRIMARY_REVIEW_P1_REJECTED_NONAUTHORITY",
        (
            "FP048-R002_SEQ99_R004_PREFLIGHT_ABORTED_CONTINUATION_"
            "DESCENDANT_PERFORMANCE_P0_NONAUTHORITY"
        ),
        (
            "FP048-R002_SEQ99_R005_PREFLIGHT_ABORTED_GOAL_GRAPH_"
            "REPLAY_PERFORMANCE_P0_NONAUTHORITY"
        ),
        (
            "FP048-R002_SEQ99_R006_PRIMARY_REVIEW_P1_REJECTED_"
            "INVALID_REUSE_TIMEOUT_NONAUTHORITY"
        ),
        (
            "FP048-R002_SEQ99_R007_POST_REVIEW_GOAL_GRAPH_"
            "PREFLIGHT_TIMEOUT_NONAUTHORITY"
        ),
        (
            "FP048-R002_SEQ99_R008_POST_REVIEW_GOAL_GRAPH_SUCCESSOR_"
            "AUTHORITY_PREFLIGHT_FAILED_NONAUTHORITY"
        ),
        (
            "FP048-R002_SEQ99_R009_POST_REVIEW_WRITE_FAILED_SOURCE_"
            "RETAINED_NONAUTHORITY"
        ),
        "FP048-R002_R011_FRESH_START_GATE_CONTRACT",
        "FP048-R002_SEQ99_TRANSITION_CONTROL_REVIEW_R010",
    ]
    assert event["correction_reason"]["r010_execution_failure"]["status"] == (
        "FAILED_AFTER_ALL_CHECKS_BEFORE_RECEIPT"
    )
    assert projected["goal_execution"]["goal_status"] == "READY"
    assert "IN_PROGRESS" not in projected["goal_execution"]["status_by_goal"].values()
    boundary = event["claim_boundary"]
    assert boundary["formal_test_not_run_count"] == 279
    assert boundary["remaining_gate_count"] == 5
    assert boundary["release_status"] == "NOT_ELIGIBLE"
    assert boundary["implementation_start_authorized"] is False
    assert all(
        type(value) is int and value == 0
        for key, value in boundary.items()
        if key.endswith("_delta") or key == "goal_status_change_count"
    )
    restored = correction._restored_seq98_checkpoint(projected)
    assert correction.checkpoint_json_bytes(restored) == source_raw


def test_inverse_reuses_the_source_bytes_proven_by_the_full_validator() -> None:
    source_raw = b"validated exact seq98 source\n"
    with patch.object(
        correction,
        "_validated_seq98_source_bytes",
        return_value=source_raw,
    ) as validate:
        assert correction.reconstructed_seq98_checkpoint_bytes(ROOT, {}) == source_raw
    validate.assert_called_once_with(
        ROOT,
        {},
        require_live_snapshot=False,
        run_external_validators=False,
    )


def test_full_validator_returns_exact_source_only_after_projection_proof() -> None:
    source_raw, projected, event = _projection()
    event_at = correction._parse_time(event["occurred_at"], "event time")
    review = event["transition_control_review_binding"]
    authorization_raw = b"authorization fixture\n"
    original_binding = correction._binding
    exact_calls = 0

    def exact_source(raw: bytes, checkpoint: dict, root: Path = ROOT) -> None:
        nonlocal exact_calls
        exact_calls += 1
        assert root == ROOT
        assert raw == source_raw
        assert correction.checkpoint_json_bytes(checkpoint) == source_raw

    def binding(path: Path, raw: bytes) -> dict[str, object]:
        if path == correction.AUTHORIZATION_REL and raw == authorization_raw:
            return event["authorization_binding"]
        return original_binding(path, raw)

    with (
        patch.object(correction, "require_exact_seq98_source", exact_source),
        patch.object(
            correction,
            "_require_private_json",
            return_value=(authorization_raw, {}),
        ),
        patch.object(
            correction,
            "_load_review_chain",
            return_value=(
                review,
                event_at - timedelta(seconds=2),
                event_at - timedelta(seconds=1),
            ),
        ),
        patch.object(correction, "_binding", binding),
    ):
        observed = correction._validated_seq98_source_bytes(
            ROOT,
            projected,
            require_live_snapshot=False,
            run_external_validators=False,
        )
    assert observed == source_raw
    assert exact_calls == 1

    with (
        patch.object(
            correction,
            "require_exact_seq98_source",
            side_effect=correction.StartGateExecutionCorrectionError(
                "source proof failed"
            ),
        ),
        patch.object(correction, "_require_private_json") as private_review,
        pytest.raises(
            correction.StartGateExecutionCorrectionError,
            match="source proof failed",
        ),
    ):
        correction._validated_seq98_source_bytes(
            ROOT,
            projected,
            require_live_snapshot=False,
            run_external_validators=False,
        )
    private_review.assert_not_called()


def test_projection_rejects_direct_gate_evidence_in_managed_snapshot() -> None:
    _raw, source = _source()
    failure = correction.r010_execution_failure_binding(ROOT)
    paths = sorted(
        [
            *source["working_tree_snapshot"]["managed_changed_paths"],
            correction.R010_LOG_BINDINGS[0]["path"],
        ]
    )
    path_hash = hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest()
    with patch.object(
        correction, "require_exact_seq98_source", _quick_exact_source
    ), pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="managed snapshot differs",
    ):
        correction.project_seq99(
            ROOT,
            source,
            managed_paths=paths,
            path_set_sha256=path_hash,
            content_set_sha256="0" * 64,
            occurred_at="2026-08-27T12:00:00+09:00",
            authorization_binding=_fake_binding(correction.AUTHORIZATION_REL, "1"),
            review_binding={
                "assignment": _fake_binding(correction.REVIEW_ASSIGNMENT_REL, "2"),
                "review_result": _fake_binding(correction.REVIEW_RESULT_REL, "3"),
                "independent_review": _fake_binding(
                    correction.INDEPENDENT_REVIEW_REL, "4"
                ),
            },
            r010_failure_binding=failure,
            replacement_contract_binding=correction.r011_contract_binding(ROOT),
            replacement_runner_binding=correction.r011_runner_binding(ROOT),
        )


def test_bool_sequence_and_bool_validator_flags_are_rejected() -> None:
    _source_raw, projected, _event = _projection()
    projected["goal_execution"]["transition_history"][-1]["sequence"] = True
    with pytest.raises(correction.StartGateExecutionCorrectionError):
        correction.require_start_gate_execution_corrected_checkpoint(
            ROOT,
            projected,
            require_live_snapshot=False,
            run_external_validators=False,
        )
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="live-snapshot flag differs",
    ):
        correction.require_start_gate_execution_corrected_checkpoint(
            ROOT,
            {},
            require_live_snapshot=1,  # type: ignore[arg-type]
            run_external_validators=False,
        )
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="external-validator flag differs",
    ):
        correction.require_start_gate_execution_corrected_checkpoint(
            ROOT,
            {},
            require_live_snapshot=False,
            run_external_validators=1,  # type: ignore[arg-type]
        )


def test_review_documents_form_an_internal_distinct_hash_chain() -> None:
    assignment = b"assignment\n"
    result = correction.build_review_result(
        assignment,
        reviewed_at="2026-08-28T07:05:45+09:00",
    )
    independent = correction.build_independent_review(
        assignment,
        result,
        reviewed_at="2026-08-28T07:05:46+09:00",
    )
    result_doc = json.loads(result)
    independent_doc = json.loads(independent)
    assert result_doc["assignment_binding"] == correction._binding(
        correction.REVIEW_ASSIGNMENT_REL, assignment
    )
    assert independent_doc["review_result_binding"] == correction._binding(
        correction.REVIEW_RESULT_REL, result
    )
    assert result_doc["external_independence_claimed"] is False
    assert independent_doc["external_independence_claimed"] is False
    assert correction._parse_time(
        correction.R009_INDEPENDENT_REVIEW_AT,
        "R009 independent review",
    ) < correction._parse_time(
        result_doc["reviewed_at"], "R010 primary"
    ) < correction._parse_time(
        independent_doc["reviewed_at"], "R010 independent"
    )
    identities = {
        tuple(correction.PRODUCER.values()),
        tuple(correction.PRIMARY_REVIEWER.values()),
        tuple(correction.INDEPENDENT_REVIEWER.values()),
    }
    assert len(identities) == 3


def test_review_builders_reject_chronology_before_consuming_r010_paths() -> None:
    assignment = b"assignment\n"
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="R010 primary review must follow R009 independent review",
    ):
        correction.build_review_result(
            assignment,
            reviewed_at=correction.R009_INDEPENDENT_REVIEW_AT,
        )
    result = correction.build_review_result(
        assignment,
        reviewed_at="2026-08-28T07:05:45+09:00",
    )
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="R010 independent review must follow exact primary review",
    ):
        correction.build_independent_review(
            assignment,
            result,
            reviewed_at="2026-08-28T07:05:45+09:00",
        )
    result_doc = json.loads(result)
    result_doc["reviewed_at"] = correction.R009_INDEPENDENT_REVIEW_AT
    stale_result = correction.seq90.canonical_json_bytes(result_doc)
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="R010 independent review must follow exact primary review",
    ):
        correction.build_independent_review(
            assignment,
            stale_result,
            reviewed_at="2026-08-28T07:05:46+09:00",
        )


@pytest.mark.parametrize(
    "findings",
    [
        {"P0": False, "P1": 0, "P2": 0},
        {"P0": 0.0, "P1": 0, "P2": 0},
        {"P0": 0, "P1": 0},
        {"P0": 0, "P1": 0, "P2": 0, "P3": 0},
    ],
)
def test_independent_review_builder_rejects_non_exact_zero_findings(
    findings: dict[str, object],
) -> None:
    assignment = b"assignment\n"
    result = correction.build_review_result(
        assignment,
        reviewed_at="2026-08-28T07:05:45+09:00",
    )
    result_doc = json.loads(result)
    result_doc["findings"] = findings
    tampered = correction.seq90.canonical_json_bytes(result_doc)
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="R010 independent review must follow exact primary review",
    ):
        correction.build_independent_review(
            assignment,
            tampered,
            reviewed_at="2026-08-28T07:05:46+09:00",
        )


@pytest.mark.parametrize(
    "mutation",
    [
        "extra-key",
        "missing-key",
        "schema",
        "evidence-type",
        "assignment-binding",
        "external-flag",
    ],
)
def test_independent_review_builder_rejects_malformed_primary_document(
    mutation: str,
) -> None:
    assignment = b"assignment\n"
    result = correction.build_review_result(
        assignment,
        reviewed_at="2026-08-28T07:05:45+09:00",
    )
    result_doc = json.loads(result)
    if mutation == "extra-key":
        result_doc["unexpected"] = True
    elif mutation == "missing-key":
        del result_doc["evidence_type"]
    elif mutation == "schema":
        result_doc["schema_version"] = "2.0"
    elif mutation == "evidence-type":
        result_doc["evidence_type"] = "TRANSITION_CONTROL_INDEPENDENT_REVIEW"
    elif mutation == "assignment-binding":
        result_doc["assignment_binding"] = _fake_binding(
            correction.REVIEW_ASSIGNMENT_REL,
            "f",
        )
    else:
        result_doc["external_independence_claimed"] = True
    malformed = correction.seq90.canonical_json_bytes(result_doc)
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="R010 independent review must follow exact primary review",
    ):
        correction.build_independent_review(
            assignment,
            malformed,
            reviewed_at="2026-08-28T07:05:46+09:00",
        )


def _install_review_loader_fixture(
    monkeypatch: pytest.MonkeyPatch,
    *,
    result_at: str = "2026-08-28T07:05:45+09:00",
    primary_findings: dict[str, object] | None = None,
    independent_findings: dict[str, object] | None = None,
) -> dict:
    _raw, source = _source()
    r001_failure = {"round_id": "R001"}
    r002_failure = {
        "round_id": "R002",
        "review_chain_binding": correction.R002_FROZEN_BINDINGS,
    }
    r003_rejection = {
        "round_id": "R003",
        "review_partial_binding": correction.R003_FROZEN_BINDINGS,
    }
    r004_failure = {
        "round_id": "R004",
        "review_chain_binding": correction.R004_FROZEN_BINDINGS,
    }
    r005_failure = {
        "round_id": "R005",
        "review_chain_binding": correction.R005_FROZEN_BINDINGS,
    }
    r006_rejection = {
        "round_id": "R006",
        "review_chain_binding": correction.R006_FROZEN_BINDINGS,
    }
    r007_failure = {
        "round_id": "R007",
        "review_chain_binding": correction.R007_FROZEN_BINDINGS,
    }
    r008_failure = {
        "round_id": "R008",
        "review_chain_binding": correction.R008_FROZEN_BINDINGS,
    }
    r009_failure = {
        "round_id": "R009",
        "review_chain_binding": correction.R009_FROZEN_BINDINGS,
    }
    rows = [
        _fake_binding(path, "a") for path in correction.REVIEWED_CONTROL_PATHS
    ]
    authorization_raw = b"authorization-r010\n"
    assignment_raw = b"assignment-r010\n"
    authorization = {
        "supersedes_round_id": "R009",
        "superseded_review_chain_binding": correction.R009_FROZEN_BINDINGS,
        "r001_projected_preflight_failure": r001_failure,
        "r002_projected_preflight_failure": r002_failure,
        "r003_primary_review_rejection": r003_rejection,
        "r004_projected_preflight_failure": r004_failure,
        "r005_projected_preflight_failure": r005_failure,
        "r006_rejected_review_and_invalid_preflight": r006_rejection,
        "r007_projected_preflight_failure": r007_failure,
        "r008_projected_preflight_failure": r008_failure,
        "r009_post_review_write_failure": r009_failure,
    }
    assignment = {
        "authorization_binding": correction._binding(
            correction.AUTHORIZATION_REL, authorization_raw
        ),
        "supersedes_round_id": "R009",
        "superseded_review_chain_binding": correction.R009_FROZEN_BINDINGS,
        "r001_projected_preflight_failure": r001_failure,
        "r002_projected_preflight_failure": r002_failure,
        "r003_primary_review_rejection": r003_rejection,
        "r004_projected_preflight_failure": r004_failure,
        "r005_projected_preflight_failure": r005_failure,
        "r006_rejected_review_and_invalid_preflight": r006_rejection,
        "r007_projected_preflight_failure": r007_failure,
        "r008_projected_preflight_failure": r008_failure,
        "r009_post_review_write_failure": r009_failure,
        "producer": correction.PRODUCER,
        "required_primary_reviewer": correction.PRIMARY_REVIEWER,
        "required_independent_reviewer": correction.INDEPENDENT_REVIEWER,
        "claim_boundary": correction.CLAIM_BOUNDARY,
        "reviewed_control_inputs": rows,
    }
    valid_result_raw = correction.build_review_result(
        assignment_raw,
        reviewed_at="2026-08-28T07:05:45+09:00",
    )
    valid_independent_raw = correction.build_independent_review(
        assignment_raw,
        valid_result_raw,
        reviewed_at="2026-08-28T07:05:46+09:00",
    )
    result_doc = json.loads(valid_result_raw)
    result_doc["reviewed_at"] = result_at
    if primary_findings is not None:
        result_doc["findings"] = primary_findings
    result_raw = correction.seq90.canonical_json_bytes(result_doc)
    independent_doc = json.loads(valid_independent_raw)
    independent_doc["review_result_binding"] = correction._binding(
        correction.REVIEW_RESULT_REL,
        result_raw,
    )
    if independent_findings is not None:
        independent_doc["findings"] = independent_findings
    independent_raw = correction.seq90.canonical_json_bytes(independent_doc)
    documents = {
        correction.AUTHORIZATION_REL: (authorization_raw, authorization),
        correction.REVIEW_ASSIGNMENT_REL: (assignment_raw, assignment),
        correction.REVIEW_RESULT_REL: (result_raw, json.loads(result_raw)),
        correction.INDEPENDENT_REVIEW_REL: (
            independent_raw,
            json.loads(independent_raw),
        ),
    }
    monkeypatch.setattr(
        correction,
        "_require_private_json",
        lambda _root, path: documents[path],
    )
    monkeypatch.setattr(
        correction, "build_authorization", lambda _root, _source: authorization_raw
    )
    monkeypatch.setattr(
        correction, "build_review_assignment", lambda _root, _source: assignment_raw
    )
    monkeypatch.setattr(
        correction,
        "r001_projected_preflight_failure",
        lambda _root, _source: r001_failure,
    )
    monkeypatch.setattr(
        correction,
        "r002_projected_preflight_failure",
        lambda _root, _source: r002_failure,
    )
    monkeypatch.setattr(
        correction,
        "r003_primary_review_rejection",
        lambda _root, _source: r003_rejection,
    )
    monkeypatch.setattr(
        correction,
        "r004_projected_preflight_failure",
        lambda _root, _source: r004_failure,
    )
    monkeypatch.setattr(
        correction,
        "r005_projected_preflight_failure",
        lambda _root, _source: r005_failure,
    )
    monkeypatch.setattr(
        correction,
        "r006_rejected_review_and_invalid_preflight",
        lambda _root, _source: r006_rejection,
    )
    monkeypatch.setattr(
        correction,
        "r007_projected_preflight_failure",
        lambda _root, _source: r007_failure,
    )
    monkeypatch.setattr(
        correction,
        "r008_projected_preflight_failure",
        lambda _root, _source: r008_failure,
    )
    monkeypatch.setattr(
        correction,
        "r009_post_review_write_failure",
        lambda _root, _source: r009_failure,
    )
    monkeypatch.setattr(
        correction,
        "_observed_binding",
        lambda _root, path: _fake_binding(path, "a"),
    )
    return source


def test_review_loader_rejects_r010_primary_before_r009_independent_review(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _install_review_loader_fixture(
        monkeypatch,
        result_at=correction.R009_INDEPENDENT_REVIEW_AT,
    )
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="R009-failure-to-R010 review chronology differs",
    ):
        correction._load_review_chain(ROOT, source)


@pytest.mark.parametrize(
    ("role", "bad_value", "message"),
    [
        ("primary", False, "primary review differs"),
        ("primary", 0.0, "primary review differs"),
        ("independent", False, "independent review differs"),
        ("independent", 0.0, "independent review differs"),
    ],
)
def test_review_loader_rejects_bool_or_float_findings(
    monkeypatch: pytest.MonkeyPatch,
    role: str,
    bad_value: object,
    message: str,
) -> None:
    findings = {"P0": bad_value, "P1": 0, "P2": 0}
    source = _install_review_loader_fixture(
        monkeypatch,
        primary_findings=findings if role == "primary" else None,
        independent_findings=findings if role == "independent" else None,
    )
    with pytest.raises(correction.StartGateExecutionCorrectionError, match=message):
        correction._load_review_chain(ROOT, source)


def _call_cache_review_result() -> tuple[dict, object, object]:
    return (
        {
            "assignment": _fake_binding(correction.REVIEW_ASSIGNMENT_REL, "1"),
            "review_result": _fake_binding(correction.REVIEW_RESULT_REL, "2"),
            "independent_review": _fake_binding(
                correction.INDEPENDENT_REVIEW_REL,
                "3",
            ),
        },
        correction._parse_time(
            "2026-08-28T07:05:45+09:00",
            "cached primary",
        ),
        correction._parse_time(
            "2026-08-28T07:05:46+09:00",
            "cached independent",
        ),
    )


def test_review_chain_call_scope_reuses_exact_source_and_resets(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = {"source": {"marker": "same"}}
    result = _call_cache_review_result()
    compute_calls: list[dict] = []
    seal_calls: list[dict] = []
    monkeypatch.setattr(correction, "_require_exact_seq98_pins", lambda *_: None)
    monkeypatch.setattr(
        correction,
        "_compute_load_review_chain",
        lambda _root, value: compute_calls.append(value) or result,
    )
    monkeypatch.setattr(
        correction,
        "_review_chain_call_cache_seal",
        lambda _root, review: seal_calls.append(review) or (("sealed", "1", 1),),
    )

    with correction.seq98_source_validation_call_scope():
        first = correction._load_review_chain(ROOT, source)
        first[0]["assignment"]["sha256"] = "f" * 64
        second = correction._load_review_chain(ROOT, copy.deepcopy(source))
        with correction.seq98_source_validation_call_scope():
            third = correction._load_review_chain(ROOT, source)
    assert len(compute_calls) == 1
    assert len(seal_calls) == 3
    assert second == result
    assert third == result
    assert correction._SEQ98_REVIEW_CHAIN_CALL_CACHE.get() is None

    with correction.seq98_source_validation_call_scope():
        correction._load_review_chain(ROOT, source)
    assert len(compute_calls) == 2


def test_review_chain_call_scope_separates_root_and_full_content(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = {"source": {"marker": "first"}}
    result = _call_cache_review_result()
    compute_calls: list[tuple[Path, dict]] = []
    monkeypatch.setattr(correction, "_require_exact_seq98_pins", lambda *_: None)
    monkeypatch.setattr(
        correction,
        "_compute_load_review_chain",
        lambda root, value: compute_calls.append((root, value)) or result,
    )
    monkeypatch.setattr(
        correction,
        "_review_chain_call_cache_seal",
        lambda *_: (("sealed", "1", 1),),
    )

    with correction.seq98_source_validation_call_scope():
        correction._load_review_chain(ROOT, source)
        correction._load_review_chain(ROOT, source)
        source["source"]["marker"] = "changed"
        correction._load_review_chain(ROOT, source)
        correction._load_review_chain(ROOT.parent, source)
    assert len(compute_calls) == 3


def test_review_chain_call_scope_caches_exception_not_interrupt(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = {"source": "failure"}
    monkeypatch.setattr(correction, "_require_exact_seq98_pins", lambda *_: None)
    failures: list[BaseException] = []

    def fail(*_args: object) -> tuple[dict, object, object]:
        error = ValueError("invalid review chain")
        failures.append(error)
        raise error

    monkeypatch.setattr(correction, "_compute_load_review_chain", fail)
    with correction.seq98_source_validation_call_scope():
        for _ in range(2):
            with pytest.raises(ValueError, match="invalid review chain"):
                correction._load_review_chain(ROOT, source)
    assert len(failures) == 1

    interruptions: list[BaseException] = []

    def interrupt(*_args: object) -> tuple[dict, object, object]:
        error = KeyboardInterrupt("stopped")
        interruptions.append(error)
        raise error

    monkeypatch.setattr(correction, "_compute_load_review_chain", interrupt)
    with correction.seq98_source_validation_call_scope():
        for _ in range(2):
            with pytest.raises(KeyboardInterrupt, match="stopped"):
                correction._load_review_chain(ROOT, source)
    assert len(interruptions) == 2


def test_review_chain_call_scope_rechecks_input_seal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = {"source": "seal"}
    result = _call_cache_review_result()
    seals = iter(
        (
            (("active", "1", 1),),
            (("active", "2", 1),),
        )
    )
    monkeypatch.setattr(correction, "_require_exact_seq98_pins", lambda *_: None)
    monkeypatch.setattr(
        correction,
        "_compute_load_review_chain",
        lambda *_: result,
    )
    monkeypatch.setattr(
        correction,
        "_review_chain_call_cache_seal",
        lambda *_: next(seals),
    )
    with correction.seq98_source_validation_call_scope():
        correction._load_review_chain(ROOT, source)
        with pytest.raises(
            correction.StartGateExecutionCorrectionError,
            match="cached review-chain input seal changed",
        ):
            correction._load_review_chain(ROOT, source)


def _actual_review_chain_seal_fixture(
    monkeypatch: pytest.MonkeyPatch,
    *,
    stored_r010: dict,
    observed_r010: dict,
) -> tuple[dict, list[Path], list[Path], list[bool]]:
    frozen: dict[Path, dict] = {}
    for paths, bindings in (
        (correction.R001_REVIEW_PATHS, correction.R001_FROZEN_BINDINGS),
        (correction.R002_REVIEW_PATHS, correction.R002_FROZEN_BINDINGS),
        (correction.R004_REVIEW_PATHS, correction.R004_FROZEN_BINDINGS),
        (correction.R005_REVIEW_PATHS, correction.R005_FROZEN_BINDINGS),
        (correction.R006_REVIEW_PATHS, correction.R006_FROZEN_BINDINGS),
        (correction.R007_REVIEW_PATHS, correction.R007_FROZEN_BINDINGS),
        (correction.R008_REVIEW_PATHS, correction.R008_FROZEN_BINDINGS),
        (correction.R009_REVIEW_PATHS, correction.R009_FROZEN_BINDINGS),
    ):
        for role, path in zip(
            ("authorization", "assignment", "review_result", "independent_review"),
            paths,
            strict=True,
        ):
            frozen[path] = bindings[role]
    for role, path in zip(
        ("authorization", "assignment"),
        correction.R003_PARTIAL_REVIEW_PATHS,
        strict=True,
    ):
        frozen[path] = correction.R003_FROZEN_BINDINGS[role]

    active_paths = (
        correction.AUTHORIZATION_REL,
        correction.REVIEW_ASSIGNMENT_REL,
        correction.REVIEW_RESULT_REL,
        correction.INDEPENDENT_REVIEW_REL,
    )
    active = {
        path: _fake_binding(path, str(index + 1))
        for index, path in enumerate(active_paths)
    }
    documents = {
        correction.AUTHORIZATION_REL: {
            "document_id": correction.AUTHORIZATION_DOCUMENT_ID,
            "r010_execution_failure": copy.deepcopy(stored_r010),
        },
        correction.REVIEW_ASSIGNMENT_REL: {
            "authorization_binding": active[correction.AUTHORIZATION_REL],
            "r010_execution_failure": copy.deepcopy(stored_r010),
            "reviewed_control_inputs": [],
        },
        correction.REVIEW_RESULT_REL: {
            "assignment_binding": active[correction.REVIEW_ASSIGNMENT_REL],
        },
        correction.INDEPENDENT_REVIEW_REL: {
            "assignment_binding": active[correction.REVIEW_ASSIGNMENT_REL],
            "review_result_binding": active[correction.REVIEW_RESULT_REL],
        },
    }
    reads: list[Path] = []
    identity_paths: list[Path] = []
    r010_calls: list[bool] = []

    def require_private(_root: Path, path: Path) -> tuple[bytes, dict]:
        reads.append(path)
        return path.as_posix().encode(), copy.deepcopy(documents.get(path, {}))

    def binding(path: Path, _raw: bytes) -> dict:
        return copy.deepcopy(frozen[path] if path in frozen else active[path])

    rows = [{"path": path.as_posix()} for path in correction.REVIEWED_CONTROL_PATHS]
    monkeypatch.setattr(correction, "_require_private_json", require_private)
    monkeypatch.setattr(correction, "_binding", binding)
    monkeypatch.setattr(
        correction,
        "_validate_reviewed_control_rows",
        lambda _value: copy.deepcopy(rows),
    )
    monkeypatch.setattr(
        correction,
        "_observed_binding",
        lambda _root, path: {"path": path.as_posix()},
    )
    monkeypatch.setattr(
        correction,
        "_cache_file_identity_seal",
        lambda _root, paths: identity_paths.extend(paths) or (("identity",),),
    )

    def r010_failure(_root: Path) -> dict:
        r010_calls.append(True)
        return copy.deepcopy(observed_r010)

    monkeypatch.setattr(correction, "r010_execution_failure_binding", r010_failure)
    review = {
        "assignment": active[correction.REVIEW_ASSIGNMENT_REL],
        "review_result": active[correction.REVIEW_RESULT_REL],
        "independent_review": active[correction.INDEPENDENT_REVIEW_REL],
    }
    return review, reads, identity_paths, r010_calls


def test_review_chain_seal_rechecks_r009_files_and_r010_identities(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    failure = {"status": "same"}
    review, reads, identity_paths, r010_calls = _actual_review_chain_seal_fixture(
        monkeypatch,
        stored_r010=failure,
        observed_r010=failure,
    )
    correction._review_chain_call_cache_seal(ROOT, review)
    assert set(correction.R009_REVIEW_PATHS).issubset(reads)
    assert set(correction.R009_REVIEW_PATHS).issubset(identity_paths)
    assert {
        correction.AUTHORIZATION_REL,
        correction.REVIEW_ASSIGNMENT_REL,
        correction.REVIEW_RESULT_REL,
        correction.INDEPENDENT_REVIEW_REL,
    }.issubset(identity_paths)
    assert correction.R010_CONTRACT_REL in identity_paths
    assert len(r010_calls) == 1


def test_review_chain_seal_rejects_r010_stored_failure_drift(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    review, _reads, _identity_paths, r010_calls = _actual_review_chain_seal_fixture(
        monkeypatch,
        stored_r010={"status": "old"},
        observed_r010={"status": "changed"},
    )
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="cached R010 execution failure changed",
    ):
        correction._review_chain_call_cache_seal(ROOT, review)
    assert len(r010_calls) == 1


def test_add_only_review_write_is_0600_idempotent_and_collision_safe(
    tmp_path: Path,
) -> None:
    _r001_review_fixture(tmp_path)
    _r002_review_fixture(tmp_path)
    _r003_partial_review_fixture(tmp_path)
    _r004_review_fixture(tmp_path)
    _r005_review_fixture(tmp_path)
    _r006_review_fixture(tmp_path)
    _r007_review_fixture(tmp_path)
    _r008_review_fixture(tmp_path)
    _r009_review_fixture(tmp_path)
    frozen_before = {
        path: (tmp_path / path).read_bytes()
        for path in (
            *correction.R001_REVIEW_PATHS,
            *correction.R002_REVIEW_PATHS,
            *correction.R003_PARTIAL_REVIEW_PATHS,
            *correction.R004_REVIEW_PATHS,
            *correction.R005_REVIEW_PATHS,
            *correction.R006_REVIEW_PATHS,
            *correction.R007_REVIEW_PATHS,
            *correction.R008_REVIEW_PATHS,
            *correction.R009_REVIEW_PATHS,
        )
    }
    outputs = {
        correction.AUTHORIZATION_REL: b"authorization\n",
        correction.REVIEW_ASSIGNMENT_REL: b"assignment\n",
    }
    correction.write_review_inputs(tmp_path, outputs)
    correction.write_review_inputs(tmp_path, outputs)
    for path, raw in outputs.items():
        target = tmp_path / path
        assert target.read_bytes() == raw
        assert target.stat().st_mode & 0o777 == 0o600
        assert target.stat().st_nlink == 1
    assert {
        path: (tmp_path / path).read_bytes()
        for path in (
            *correction.R001_REVIEW_PATHS,
            *correction.R002_REVIEW_PATHS,
            *correction.R003_PARTIAL_REVIEW_PATHS,
            *correction.R004_REVIEW_PATHS,
            *correction.R005_REVIEW_PATHS,
            *correction.R006_REVIEW_PATHS,
            *correction.R007_REVIEW_PATHS,
            *correction.R008_REVIEW_PATHS,
            *correction.R009_REVIEW_PATHS,
        )
    } == frozen_before
    (tmp_path / correction.AUTHORIZATION_REL).write_bytes(b"different\n")
    (tmp_path / correction.AUTHORIZATION_REL).chmod(0o600)
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="add-only collision differs",
    ):
        correction.write_review_inputs(tmp_path, outputs)


def test_preflight_cli_does_not_call_any_writer(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    event = {"event_sha256": "a" * 64}
    fake = SimpleNamespace(event=event)
    monkeypatch.setattr(correction, "prepare", lambda *_args, **_kwargs: fake)
    monkeypatch.setattr(
        correction,
        "write_checkpoint",
        lambda *_args, **_kwargs: pytest.fail("preflight wrote checkpoint"),
    )
    monkeypatch.setattr(
        correction,
        "write_review_inputs",
        lambda *_args, **_kwargs: pytest.fail("preflight wrote review inputs"),
    )
    assert correction.main(["--preflight", "--root", str(ROOT)]) == 0
    assert "published=false" in capsys.readouterr().out


def test_cli_exposes_only_three_mutually_exclusive_modes() -> None:
    assert correction.parse_args(["--prepare-review"]).prepare_review
    assert correction.parse_args(["--preflight"]).preflight
    assert correction.parse_args(["--write"]).write
    with pytest.raises(SystemExit):
        correction.parse_args([])
    with pytest.raises(SystemExit):
        correction.parse_args(["--preflight", "--write"])


def _fake_prepared(root: Path, source: bytes, projected: bytes) -> correction.Prepared:
    checkpoint = root / correction.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True, exist_ok=True)
    checkpoint.write_bytes(source)
    checkpoint.chmod(0o600)
    transport = SimpleNamespace(
        root=root,
        source_raw=source,
        projected_raw=projected,
    )
    return correction.Prepared(transport)


def _atomic_boundary_fixture(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    *,
    phase: str = "PROJECTED",
) -> tuple[correction.Prepared, Path, list[bytes]]:
    source = b"source\n"
    projected = b"projected\n"
    checkpoint = tmp_path / correction.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True, exist_ok=True)
    checkpoint.write_bytes(projected if phase == "PROJECTED" else source)
    checkpoint.chmod(0o600)
    temporary = checkpoint.parent / (
        f"{correction.seq98.CAS_WRITE_TEMP_PREFIX}{'a' * 24}"
        f"{correction.seq98.CAS_WRITE_TEMP_SUFFIX}"
    )
    temporary.write_bytes(source if phase == "PROJECTED" else projected)
    temporary.chmod(0o600)
    baseline_status = b" M tracked-control.py\0"
    statuses = [
        baseline_status
        + b"?? "
        + temporary.relative_to(tmp_path).as_posix().encode()
        + b"\0"
    ]
    transport = SimpleNamespace(
        root=tmp_path,
        source_raw=source,
        projected_raw=projected,
        source={},
        projected={"projected": True},
        event={},
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=baseline_status,
        git_head="head",
        git_branch="branch",
    )
    monkeypatch.setattr(correction, "_require_prepared_exact", lambda _value: None)
    monkeypatch.setattr(correction, "require_exact_seq98_source", lambda *_args: None)
    monkeypatch.setattr(correction.seq90, "_require_git_context", lambda *_args: None)
    monkeypatch.setattr(
        correction.seq90,
        "capture_git_visible_paths",
        lambda _root: (statuses[0], []),
    )
    return correction.Prepared(transport), temporary, statuses


def test_projected_atomic_boundary_uses_phase_local_not_live_validator(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared, _temporary, _statuses = _atomic_boundary_fixture(
        tmp_path,
        monkeypatch,
    )
    calls: list[dict[str, object]] = []
    monkeypatch.setattr(
        correction,
        "require_start_gate_execution_corrected_checkpoint",
        lambda _root, projected, **options: calls.append(
            {"projected": projected, **options}
        ),
    )
    assert correction._require_atomic_boundary(prepared) == "PROJECTED"
    assert calls == [
        {
            "projected": prepared.transport.projected,
            "require_live_snapshot": False,
            "run_external_validators": False,
        }
    ]


def test_terminal_existing_projection_requires_live_external_validation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    projected = {"published": True}
    projected_raw = b'{"published":true}\n'
    prepared = correction.Prepared(
        SimpleNamespace(
            root=tmp_path,
            projected=projected,
            projected_raw=projected_raw,
        )
    )
    monkeypatch.setattr(
        correction.seq90,
        "_stable_read",
        lambda _root, _path: SimpleNamespace(raw=projected_raw),
    )
    monkeypatch.setattr(
        correction.seq90,
        "strict_json",
        lambda raw, _label: projected if raw == projected_raw else pytest.fail(),
    )
    calls: list[dict[str, object]] = []
    monkeypatch.setattr(
        correction,
        "require_start_gate_execution_corrected_checkpoint",
        lambda _root, value, **options: calls.append(
            {"projected": value, **options}
        ),
    )
    correction.write_checkpoint(prepared)
    assert calls == [
        {
            "projected": projected,
            "require_live_snapshot": True,
            "run_external_validators": True,
        }
    ]


@pytest.mark.parametrize("mutation", ["bytes", "mode"])
def test_atomic_boundary_rejects_wrong_temporary_authority(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    prepared, temporary, _statuses = _atomic_boundary_fixture(
        tmp_path,
        monkeypatch,
    )
    if mutation == "bytes":
        temporary.write_bytes(b"wrong\n")
        temporary.chmod(0o600)
    else:
        temporary.chmod(0o644)
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="CAS temporary authority differs",
    ):
        correction._require_atomic_boundary(prepared)


@pytest.mark.parametrize("mutation", ["unrelated-status", "second-temporary"])
def test_atomic_boundary_rejects_status_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    prepared, temporary, statuses = _atomic_boundary_fixture(
        tmp_path,
        monkeypatch,
    )
    if mutation == "unrelated-status":
        statuses[0] = b" M changed-control.py\0" + statuses[0][
            len(prepared.transport.git_status_raw) :
        ]
        message = "Git-visible cohort changed"
    else:
        second = temporary.with_name(
            f"{correction.seq98.CAS_WRITE_TEMP_PREFIX}{'b' * 24}"
            f"{correction.seq98.CAS_WRITE_TEMP_SUFFIX}"
        )
        statuses[0] += (
            b"?? " + second.relative_to(tmp_path).as_posix().encode() + b"\0"
        )
        message = "temporary inventory differs"
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match=message,
    ):
        correction._require_atomic_boundary(prepared)


@pytest.mark.parametrize(
    ("cohort", "message"),
    [
        ("retained", "review input changed or ABA-replaced"),
        ("managed", "final managed input changed or ABA-replaced"),
        ("git-visible", "noncheckpoint Git-visible input changed or ABA-replaced"),
    ],
)
def test_atomic_boundary_rejects_input_cohort_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    cohort: str,
    message: str,
) -> None:
    prepared, _temporary, _statuses = _atomic_boundary_fixture(
        tmp_path,
        monkeypatch,
    )
    relative = Path(f"{cohort}.txt")
    target = tmp_path / relative
    target.write_bytes(b"before\n")
    target.chmod(0o600)
    if cohort == "retained":
        prepared.transport.retained_inputs = {
            relative: correction.seq90._stable_read(tmp_path, relative)
        }
    else:
        captured = correction.seq90._capture_managed_inputs(
            tmp_path,
            [relative.as_posix()],
        )
        if cohort == "managed":
            prepared.transport.managed_inputs = captured
        else:
            prepared.transport.git_visible_inputs = captured
    target.write_bytes(b"after\n")
    target.chmod(0o600)
    with pytest.raises(correction.seq90.ControlReanchorError, match=message):
        correction._require_atomic_boundary(prepared)


def test_atomic_boundary_rejects_git_context_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared, _temporary, _statuses = _atomic_boundary_fixture(
        tmp_path,
        monkeypatch,
    )

    def reject_context(*_args: object) -> None:
        raise correction.seq90.ControlReanchorError(
            "Git HEAD or symbolic branch changed"
        )

    monkeypatch.setattr(correction.seq90, "_require_git_context", reject_context)
    with pytest.raises(
        correction.seq90.ControlReanchorError,
        match="Git HEAD or symbolic branch changed",
    ):
        correction._require_atomic_boundary(prepared)


def test_atomic_cas_publishes_and_post_exchange_failure_rolls_back(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = b"source\n"
    projected = b"projected\n"
    prepared = _fake_prepared(tmp_path, source, projected)
    phases = iter(["SOURCE", "PROJECTED", "PROJECTED"])
    monkeypatch.setattr(correction, "_require_atomic_boundary", lambda _p: next(phases))
    correction._write_checkpoint_atomic(prepared)
    assert (tmp_path / correction.CHECKPOINT_REL).read_bytes() == projected
    assert not any(
        child.name.startswith(correction.seq98.CAS_WRITE_TEMP_PREFIX)
        for child in (tmp_path / correction.CHECKPOINT_REL).parent.iterdir()
    )

    rollback_root = tmp_path / "rollback"
    rollback = _fake_prepared(rollback_root, source, projected)
    calls = 0

    def fail_after_exchange(_prepared: correction.Prepared) -> str:
        nonlocal calls
        calls += 1
        if calls == 1:
            return "SOURCE"
        raise correction.StartGateExecutionCorrectionError("forced post-exchange failure")

    monkeypatch.setattr(correction, "_require_atomic_boundary", fail_after_exchange)
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="forced post-exchange failure",
    ):
        correction._write_checkpoint_atomic(rollback)
    assert (rollback_root / correction.CHECKPOINT_REL).read_bytes() == source
    assert not any(
        child.name.startswith(correction.seq98.CAS_WRITE_TEMP_PREFIX)
        for child in (rollback_root / correction.CHECKPOINT_REL).parent.iterdir()
    )


def test_final_managed_paths_exclude_gate_evidence_and_include_current_visible() -> None:
    _raw, source = _source()
    paths = correction._final_managed_paths(
        source,
        [
            correction.SCRIPT_REL.as_posix(),
            correction.R010_LOG_BINDINGS[0]["path"],
            "future-completion-control.py",
        ],
    )
    assert "future-completion-control.py" in paths
    assert correction.SCRIPT_REL.as_posix() in paths
    assert correction.AUTHORIZATION_REL.as_posix() in paths
    assert correction.REVIEW_RESULT_REL.as_posix() in paths
    assert all(path.as_posix() in paths for path in correction.R001_REVIEW_PATHS)
    assert all(path.as_posix() in paths for path in correction.R002_REVIEW_PATHS)
    assert all(
        path.as_posix() in paths for path in correction.R003_PARTIAL_REVIEW_PATHS
    )
    assert all(path.as_posix() in paths for path in correction.R004_REVIEW_PATHS)
    assert all(path.as_posix() in paths for path in correction.R005_REVIEW_PATHS)
    assert all(path.as_posix() in paths for path in correction.R006_REVIEW_PATHS)
    assert all(path.as_posix() in paths for path in correction.R007_REVIEW_PATHS)
    assert all(path.as_posix() in paths for path in correction.R008_REVIEW_PATHS)
    assert all(path.as_posix() in paths for path in correction.R009_REVIEW_PATHS)
    retained = correction._retained_input_paths(
        correction.r010_execution_failure_binding(ROOT)
    )
    assert all(path in retained for path in correction.R001_REVIEW_PATHS)
    assert all(path in retained for path in correction.R002_REVIEW_PATHS)
    assert all(path in retained for path in correction.R003_PARTIAL_REVIEW_PATHS)
    assert all(path in retained for path in correction.R004_REVIEW_PATHS)
    assert all(path in retained for path in correction.R005_REVIEW_PATHS)
    assert all(path in retained for path in correction.R006_REVIEW_PATHS)
    assert all(path in retained for path in correction.R007_REVIEW_PATHS)
    assert all(path in retained for path in correction.R008_REVIEW_PATHS)
    assert all(path in retained for path in correction.R009_REVIEW_PATHS)
    assert correction.AUTHORIZATION_REL in retained
    assert all(path in retained for path in correction.REVIEW_PATHS)
    assert not any(path.startswith(correction.GATE_EVIDENCE_PREFIX) for path in paths)
