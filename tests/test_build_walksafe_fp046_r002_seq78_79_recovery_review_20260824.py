from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import stat

import pytest

from scripts import build_walksafe_fp046_r002_seq78_79_recovery_review_20260824 as review


ROOT = Path(__file__).resolve().parents[1]


def _copy_failed_attempt(root: Path) -> Path:
    directory = root / review.FAILED_GATE_DIR
    directory.mkdir(parents=True)
    directory.chmod(0o700)
    source = ROOT / review.FAILED_GATE_LOG_REL
    target = root / review.FAILED_GATE_LOG_REL
    target.write_bytes(source.read_bytes())
    target.chmod(0o600)
    return directory


def test_authority_paths_and_control_delta_are_exact() -> None:
    assert review.REVIEW_DIR == Path(
        "docs/control/execution/workstream-transitions/seq78-79/review-rounds/R014"
    )
    assert len(review.PRESERVED_REVIEW_PATHS) == 36
    assert review.REJECTED_R001_ASSIGNMENT_REL in review.PRESERVED_REVIEW_PATHS
    assert set(review.APPROVED_R002_REVIEW_PATHS).issubset(
        review.PRESERVED_REVIEW_PATHS
    )
    assert set(review.APPROVED_R003_REVIEW_PATHS).issubset(
        review.PRESERVED_REVIEW_PATHS
    )
    assert set(review.APPROVED_R004_REVIEW_PATHS).issubset(
        review.PRESERVED_REVIEW_PATHS
    )
    assert set(review.APPROVED_R005_REVIEW_PATHS).issubset(
        review.PRESERVED_REVIEW_PATHS
    )
    assert set(review.APPROVED_R006_REVIEW_PATHS).issubset(
        review.PRESERVED_REVIEW_PATHS
    )
    assert set(review.APPROVED_R007_REVIEW_PATHS).issubset(
        review.PRESERVED_REVIEW_PATHS
    )
    assert set(review.APPROVED_R008_REVIEW_PATHS).issubset(
        review.PRESERVED_REVIEW_PATHS
    )
    assert set(review.APPROVED_R009_REVIEW_PATHS).issubset(
        review.PRESERVED_REVIEW_PATHS
    )
    assert review.REJECTED_R010_ASSIGNMENT_REL in review.PRESERVED_REVIEW_PATHS
    assert set(review.APPROVED_R011_REVIEW_PATHS).issubset(
        review.PRESERVED_REVIEW_PATHS
    )
    assert set(review.APPROVED_R012_REVIEW_PATHS).issubset(
        review.PRESERVED_REVIEW_PATHS
    )
    assert review.REJECTED_R013_ASSIGNMENT_REL in review.PRESERVED_REVIEW_PATHS
    assert len(review.MODIFIED_CONTROL_PATHS) == 9
    assert len(review.ADDED_CONTROL_PATHS) == 6
    assert len(review.CURRENT_CONTROL_PATHS) == 37
    assert len(set(review.CURRENT_CONTROL_PATHS)) == 37
    assert review.SESSION_ARTIFACT_PATHS == ()
    assert len(review.PRESERVED_SESSION_ARTIFACT_PATHS) == 4
    assert review.STARTED_SCRIPT_REL in review.ADDED_CONTROL_PATHS
    assert review.STARTED_TEST_REL in review.ADDED_CONTROL_PATHS
    assert review.GATE_SCRIPT_REL in review.MODIFIED_CONTROL_PATHS
    assert review.GATE_TEST_REL in review.MODIFIED_CONTROL_PATHS


def test_source_r006_and_failed_attempt_are_exact() -> None:
    source = review.source_checkpoint_binding(ROOT)
    assert source["sha256"] == review.SOURCE_CHECKPOINT_SHA256
    assert source["byte_length"] == review.SOURCE_CHECKPOINT_BYTE_LENGTH
    assert source["tail_event_sha256"] == review.SOURCE_EVENT_SHA256
    review_source = review.review_source_checkpoint_binding(ROOT)
    assert review_source["sequence"] == review.REVIEW_SOURCE_SEQUENCE
    assert review_source["sha256"] == review.REVIEW_SOURCE_CHECKPOINT_SHA256
    assert review_source["byte_length"] == review.REVIEW_SOURCE_CHECKPOINT_BYTE_LENGTH
    assert review_source["tail_event_sha256"] == review.REVIEW_SOURCE_EVENT_SHA256

    predecessor = review.preserved_review_bindings(ROOT)
    assert tuple(row["path"] for row in predecessor) == tuple(
        path.as_posix() for path in review.R006_REVIEW_PATHS
    )
    assert tuple((row["sha256"], row["byte_length"]) for row in predecessor) == tuple(
        review.PRESERVED_REVIEW_PINS[path]
        for path in review.R006_REVIEW_PATHS
    )

    rejected = review.rejected_r001_assignment_binding(ROOT)
    assert rejected == {
        "path": review.REJECTED_R001_ASSIGNMENT_REL.as_posix(),
        "sha256": review.REJECTED_R001_ASSIGNMENT_SHA256,
        "byte_length": review.REJECTED_R001_ASSIGNMENT_BYTE_LENGTH,
    }
    assert not (ROOT / review.REJECTED_R001_RESULT_REL).exists()
    assert not (ROOT / review.REJECTED_R001_INDEPENDENT_REL).exists()

    approved_r002 = review.approved_r002_review_bindings(ROOT)
    assert tuple(row["path"] for row in approved_r002) == tuple(
        path.as_posix() for path in review.APPROVED_R002_REVIEW_PATHS
    )
    assert tuple(
        (row["sha256"], row["byte_length"]) for row in approved_r002
    ) == tuple(
        review.APPROVED_R002_REVIEW_PINS[path]
        for path in review.APPROVED_R002_REVIEW_PATHS
    )
    approved_r003 = review.approved_r003_review_bindings(ROOT)
    assert tuple(row["path"] for row in approved_r003) == tuple(
        path.as_posix() for path in review.APPROVED_R003_REVIEW_PATHS
    )
    assert tuple(
        (row["sha256"], row["byte_length"]) for row in approved_r003
    ) == tuple(
        review.APPROVED_R003_REVIEW_PINS[path]
        for path in review.APPROVED_R003_REVIEW_PATHS
    )
    approved_r004 = review.approved_r004_review_bindings(ROOT)
    assert tuple(row["path"] for row in approved_r004) == tuple(
        path.as_posix() for path in review.APPROVED_R004_REVIEW_PATHS
    )
    assert tuple(
        (row["sha256"], row["byte_length"]) for row in approved_r004
    ) == tuple(
        review.APPROVED_R004_REVIEW_PINS[path]
        for path in review.APPROVED_R004_REVIEW_PATHS
    )
    approved_r005 = review.approved_r005_review_bindings(ROOT)
    assert tuple(row["path"] for row in approved_r005) == tuple(
        path.as_posix() for path in review.APPROVED_R005_REVIEW_PATHS
    )
    assert tuple(
        (row["sha256"], row["byte_length"]) for row in approved_r005
    ) == tuple(
        review.APPROVED_R005_REVIEW_PINS[path]
        for path in review.APPROVED_R005_REVIEW_PATHS
    )
    approved_r006 = review.approved_r006_review_bindings(ROOT)
    assert tuple(row["path"] for row in approved_r006) == tuple(
        path.as_posix() for path in review.APPROVED_R006_REVIEW_PATHS
    )
    assert tuple(
        (row["sha256"], row["byte_length"]) for row in approved_r006
    ) == tuple(
        review.APPROVED_R006_REVIEW_PINS[path]
        for path in review.APPROVED_R006_REVIEW_PATHS
    )
    approved_r007 = review.approved_r007_review_bindings(ROOT)
    assert tuple(row["path"] for row in approved_r007) == tuple(
        path.as_posix() for path in review.APPROVED_R007_REVIEW_PATHS
    )
    assert tuple(
        (row["sha256"], row["byte_length"]) for row in approved_r007
    ) == tuple(
        review.APPROVED_R007_REVIEW_PINS[path]
        for path in review.APPROVED_R007_REVIEW_PATHS
    )
    approved_r008 = review.approved_r008_review_bindings(ROOT)
    assert tuple(row["path"] for row in approved_r008) == tuple(
        path.as_posix() for path in review.APPROVED_R008_REVIEW_PATHS
    )
    assert tuple(
        (row["sha256"], row["byte_length"]) for row in approved_r008
    ) == tuple(
        review.APPROVED_R008_REVIEW_PINS[path]
        for path in review.APPROVED_R008_REVIEW_PATHS
    )
    approved_r009 = review.approved_r009_review_bindings(ROOT)
    assert tuple(row["path"] for row in approved_r009) == tuple(
        path.as_posix() for path in review.APPROVED_R009_REVIEW_PATHS
    )
    assert tuple(
        (row["sha256"], row["byte_length"]) for row in approved_r009
    ) == tuple(
        review.APPROVED_R009_REVIEW_PINS[path]
        for path in review.APPROVED_R009_REVIEW_PATHS
    )
    assert review.rejected_r010_assignment_binding(ROOT) == {
        "path": review.REJECTED_R010_ASSIGNMENT_REL.as_posix(),
        "sha256": review.REJECTED_R010_ASSIGNMENT_SHA256,
        "byte_length": review.REJECTED_R010_ASSIGNMENT_BYTE_LENGTH,
    }
    assert not (ROOT / review.REJECTED_R010_RESULT_REL).exists()
    assert not (ROOT / review.REJECTED_R010_INDEPENDENT_REL).exists()
    approved_r011 = review.approved_r011_review_bindings(ROOT)
    assert tuple(row["path"] for row in approved_r011) == tuple(
        path.as_posix() for path in review.APPROVED_R011_REVIEW_PATHS
    )
    assert tuple(
        (row["sha256"], row["byte_length"]) for row in approved_r011
    ) == tuple(
        review.APPROVED_R011_REVIEW_PINS[path]
        for path in review.APPROVED_R011_REVIEW_PATHS
    )
    approved_r012 = review.approved_r012_review_bindings(ROOT)
    assert tuple(row["path"] for row in approved_r012) == tuple(
        path.as_posix() for path in review.APPROVED_R012_REVIEW_PATHS
    )
    assert tuple(
        (row["sha256"], row["byte_length"]) for row in approved_r012
    ) == tuple(
        review.APPROVED_R012_REVIEW_PINS[path]
        for path in review.APPROVED_R012_REVIEW_PATHS
    )
    assert review.rejected_r013_assignment_binding(ROOT) == {
        "path": review.REJECTED_R013_ASSIGNMENT_REL.as_posix(),
        "sha256": review.REJECTED_R013_ASSIGNMENT_SHA256,
        "byte_length": review.REJECTED_R013_ASSIGNMENT_BYTE_LENGTH,
    }
    assert not (ROOT / review.REJECTED_R013_RESULT_REL).exists()
    assert not (ROOT / review.REJECTED_R013_INDEPENDENT_REL).exists()

    failed = review.failed_gate_attempt_binding(ROOT)
    assert failed["directory_mode"] == "0700"
    assert failed["log_mode"] == "0600"
    assert failed["receipt_present"] is False
    assert failed["later_log_count"] == 0
    assert failed["only_log_binding"] == {
        "path": review.FAILED_GATE_LOG_REL.as_posix(),
        "sha256": review.FAILED_GATE_LOG_SHA256,
        "byte_length": 681,
    }
    assert review.failed_gate_attempt_002_binding(ROOT) == {
        "event_id": review.FAILED_GATE_002_EVENT_ID,
        "directory": review.FAILED_GATE_002_DIR.as_posix(),
        "directory_mode": "0700",
        "directory_inventory": [],
        "log_count": 0,
        "receipt_present": False,
    }
    failed_003 = review.failed_gate_attempt_003_binding(ROOT)
    assert failed_003["only_log_binding"] == {
        "path": review.FAILED_GATE_003_LOG_REL.as_posix(),
        "sha256": review.FAILED_GATE_003_LOG_SHA256,
        "byte_length": 848,
    }
    assert failed_003["receipt_present"] is False


def test_review_source_loader_accepts_seq83_descendant_and_rejects_seq82_tamper(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    live_raw = (ROOT / review.CHECKPOINT_REL).read_bytes()
    descendant = json.loads(live_raw)
    descendant["goal_execution"]["transition_history"].append({"sequence": 82})
    descendant_raw = (
        json.dumps(descendant, ensure_ascii=False, indent=2) + "\n"
    ).encode()
    original_reader = review._read_regular
    monkeypatch.setattr(
        review,
        "_read_regular",
        lambda root, relative, **kwargs: (
            descendant_raw
            if relative == review.CHECKPOINT_REL
            else original_reader(root, relative, **kwargs)
        ),
    )
    assert review.review_source_checkpoint_binding(ROOT)["sha256"] == (
        review.REVIEW_SOURCE_CHECKPOINT_SHA256
    )

    tampered = json.loads(live_raw)
    tampered["synthetic_tamper"] = True
    tampered_raw = review.json_text(tampered).encode()
    monkeypatch.setattr(
        review,
        "_read_regular",
        lambda root, relative, **kwargs: (
            tampered_raw
            if relative == review.CHECKPOINT_REL
            else original_reader(root, relative, **kwargs)
        ),
    )
    with pytest.raises(
        review.ReviewError, match="reconstructed seq83 review source checkpoint differs"
    ):
        review.review_source_checkpoint_binding(ROOT)


def test_review_reader_accepts_private_0700_snapshot(tmp_path: Path) -> None:
    path = tmp_path / review.ASSIGNMENT_REL
    path.parent.mkdir(parents=True)
    path.parent.chmod(0o700)
    path.write_bytes(b"{}\n")
    path.chmod(0o600)
    assert review._read_regular(tmp_path, review.ASSIGNMENT_REL) == b"{}\n"


def test_isolated_review_context_uses_stored_failed_gate_bindings(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def unexpected_live_read(*args: object, **kwargs: object) -> object:
        raise AssertionError("isolated review context performed a live gate read")

    monkeypatch.setattr(review, "failed_gate_attempt_binding", unexpected_live_read)
    monkeypatch.setattr(review, "failed_gate_attempt_002_binding", unexpected_live_read)
    monkeypatch.setattr(review, "failed_gate_attempt_003_binding", unexpected_live_read)
    monkeypatch.setattr(review, "passed_gate_attempt_004_binding", unexpected_live_read)
    context = review.prepare_review_context(
        ROOT,
        require_complete_control_cohort=False,
        require_live_snapshot=False,
    )
    assert context.failed_gate_attempt == review.STORED_FAILED_GATE_ATTEMPT
    assert context.failed_gate_attempt_002 == review.STORED_FAILED_GATE_ATTEMPT_002
    assert context.failed_gate_attempt_003 == review.STORED_FAILED_GATE_ATTEMPT_003
    assert context.passed_gate_attempt_004 == review.STORED_PASSED_GATE_ATTEMPT_004


@pytest.mark.parametrize("mode", [0o750, 0o770, 0o777])
def test_review_reader_rejects_unsafe_review_directory_modes(
    tmp_path: Path, mode: int
) -> None:
    path = tmp_path / review.ASSIGNMENT_REL
    path.parent.mkdir(parents=True)
    path.parent.chmod(mode)
    path.write_bytes(b"{}\n")
    path.chmod(0o600)
    with pytest.raises(review.ReviewError, match="review directory mode differs"):
        review._read_regular(tmp_path, review.ASSIGNMENT_REL)


def test_review_reader_accepts_executable_control_source() -> None:
    raw = review._read_regular(ROOT, Path("scripts/run_walksafe_test_layers_current.sh"))
    info = (ROOT / "scripts/run_walksafe_test_layers_current.sh").stat()
    assert raw
    assert stat.S_IMODE(info.st_mode) in {0o755, 0o775}


def test_failed_attempt_rejects_extra_file_and_receipt(tmp_path: Path) -> None:
    directory = _copy_failed_attempt(tmp_path)
    assert review.failed_gate_attempt_binding(tmp_path)["receipt_present"] is False
    extra = directory / "02-GOAL_GRAPH.log"
    extra.write_text("unexpected", encoding="utf-8")
    extra.chmod(0o600)
    with pytest.raises(review.ReviewError, match="inventory differs"):
        review.failed_gate_attempt_binding(tmp_path)
    extra.unlink()
    receipt = directory / "implementation-start-gate-receipt.json"
    receipt.write_text("{}\n", encoding="utf-8")
    receipt.chmod(0o600)
    with pytest.raises(review.ReviewError, match="inventory differs"):
        review.failed_gate_attempt_binding(tmp_path)


def test_failed_attempt_rejects_mode_and_link_drift(tmp_path: Path) -> None:
    directory = _copy_failed_attempt(tmp_path)
    log = tmp_path / review.FAILED_GATE_LOG_REL
    log.chmod(0o644)
    with pytest.raises(review.ReviewError, match="unsafe review input"):
        review.failed_gate_attempt_binding(tmp_path)
    log.chmod(0o600)
    alias = tmp_path / "alias.log"
    os.link(log, alias)
    with pytest.raises(review.ReviewError, match="unsafe review input"):
        review.failed_gate_attempt_binding(tmp_path)
    alias.unlink()
    directory.chmod(0o755)
    with pytest.raises(review.ReviewError, match="directory authority differs"):
        review.failed_gate_attempt_binding(tmp_path)


def test_pre_review_context_accepts_exact_r013_assignment_only_prefix() -> None:
    before = {
        path: path.exists()
        for path in (*review.REVIEW_PATHS, review.REJECTED_R013_ASSIGNMENT_REL)
    }
    context = review.prepare_review_context(
        ROOT, require_complete_control_cohort=False
    )
    assert context.source_checkpoint["sequence"] == 77
    assert context.review_source_checkpoint["sequence"] == 83
    assert context.failed_gate_attempt["event_id"] == review.FAILED_GATE_EVENT_ID
    assert context.failed_gate_attempt_002["event_id"] == (
        review.FAILED_GATE_002_EVENT_ID
    )
    assert context.failed_gate_attempt_003["event_id"] == (
        review.FAILED_GATE_003_EVENT_ID
    )
    assert context.passed_gate_attempt_004["event_id"] == (
        review.PASSED_GATE_004_EVENT_ID
    )
    assert set(context.missing_add_only_paths).issubset(
        {path.as_posix() for path in review.ADDED_CONTROL_PATHS}
    )
    assert context.rejected_r013_assignment_binding == {
        "path": review.REJECTED_R013_ASSIGNMENT_REL.as_posix(),
        "sha256": review.REJECTED_R013_ASSIGNMENT_SHA256,
        "byte_length": review.REJECTED_R013_ASSIGNMENT_BYTE_LENGTH,
    }
    assert {
        path: path.exists()
        for path in (*review.REVIEW_PATHS, review.REJECTED_R013_ASSIGNMENT_REL)
    } == before


def test_authorization_is_canonical_and_pins_explicit_current_quote() -> None:
    raw = (ROOT / review.AUTHORIZATION_REL).read_bytes()
    value = json.loads(raw)
    assert value["authorization_quote"] == "이제 진행해"
    assert raw == review.json_text(value).encode()
    assert hashlib.sha256(raw).hexdigest() == (
        review.AUTHORIZATION_SHA256
    )
    assert len(raw) == review.AUTHORIZATION_BYTE_LENGTH
    assert stat.S_IMODE((ROOT / review.AUTHORIZATION_REL).stat().st_mode) == 0o600
    assert stat.S_IMODE((ROOT / review.AUTHORIZATION_REL).parent.stat().st_mode) == 0o700


def test_review_writer_is_add_only_and_private(tmp_path: Path) -> None:
    review._write_add_only(tmp_path, review.ASSIGNMENT_REL, b"{}\n")
    path = tmp_path / review.ASSIGNMENT_REL
    assert path.read_bytes() == b"{}\n"
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert stat.S_IMODE(path.parent.stat().st_mode) == 0o700
    with pytest.raises(FileExistsError):
        review._write_add_only(tmp_path, review.ASSIGNMENT_REL, b"drift\n")


def test_r014_assignment_has_exact_separate_actor_authority() -> None:
    context = review.prepare_review_context(ROOT)
    raw = review.build_assignment(
        context, assigned_at="2026-08-25T00:20:00+09:00"
    ).encode()
    value = review._strict_json(raw, "assignment")
    review.validate_assignment(value, raw, context)
    assert value["round_id"].endswith("-R014")
    assert value["review_scope"]["r011_supersession_reason_code"] == (
        "R011_CHECKPOINT_AND_CATALOG_DESTINATION_PUBLICATION_NOT_ATOMIC"
    )
    assert value["review_scope"]["approved_r011_exact_predecessor"] == list(
        context.approved_r011_review_bindings
    )
    assert value["review_scope"]["approved_r012_exact_predecessor"] == list(
        context.approved_r012_review_bindings
    )
    assert value["review_scope"]["rejected_r013_assignment_binding"] == (
        context.rejected_r013_assignment_binding
    )
    assert value["review_scope"]["r013_supersession_reason_code"] == (
        "R013_ASSIGNMENT_ONLY_REVIEW_INTERRUPTED"
    )
    assert value["review_scope"]["rejected_r013_result_and_independent_absent"] is True
    assert value["assigner"]["canonical_task"] == "/root"
    assert value["executor"]["canonical_task"] == "/root/seq77_78_control_review"
    assert value["reviewer"]["canonical_task"] == "/root/seq77_78_final_review"
    assert len(
        {
            value["assigner"]["agent_instance_id"],
            value["executor"]["agent_instance_id"],
            value["reviewer"]["agent_instance_id"],
        }
    ) == 3


def test_reviewer_candidate_identity_provenance_and_findings_are_exact() -> None:
    context = review.prepare_review_context(ROOT)
    assignment_raw = review.build_assignment(
        context, assigned_at="2026-08-25T00:20:00+09:00"
    ).encode()
    assignment = review._strict_json(assignment_raw, "assignment")
    findings = {"blocking": [], "major_open": [], "minor_open": []}
    result_raw = review.build_review_result(
        assignment_raw,
        reviewed_at="2026-08-25T00:21:00+09:00",
        decision="APPROVED",
        findings=findings,
    ).encode()
    result = review._strict_json(result_raw, "result")
    review.validate_review_result_candidate(
        result, result_raw, assignment, assignment_raw, context
    )
    independent_raw = review.build_independent_review(
        assignment_raw, result_raw
    ).encode()
    independent = review._strict_json(independent_raw, "independent")
    review.validate_independent_review_candidate(
        independent,
        independent_raw,
        assignment,
        assignment_raw,
        result,
        result_raw,
        context,
    )

    wrong_reviewer = dict(result)
    wrong_reviewer["reviewer"] = dict(result["reviewer"])
    wrong_reviewer["reviewer"]["canonical_task"] = review.EXECUTOR_TASK
    wrong_raw = review.json_text(wrong_reviewer).encode()
    with pytest.raises(review.ReviewError, match="identity or provenance"):
        review.validate_review_result_candidate(
            wrong_reviewer, wrong_raw, assignment, assignment_raw, context
        )

    open_finding = dict(result)
    open_finding["findings"] = {
        "blocking": [{"finding_id": "R002-BLOCK-001"}],
        "major_open": [],
        "minor_open": [],
    }
    open_raw = review.json_text(open_finding).encode()
    with pytest.raises(review.ReviewError, match="open blocking"):
        review.validate_review_result_candidate(
            open_finding, open_raw, assignment, assignment_raw, context
        )

    erased = dict(independent)
    erased["findings"] = {
        "blocking": [{"finding_id": "ERASED-OR-INVENTED"}],
        "major_open": [],
        "minor_open": [],
    }
    erased_raw = review.json_text(erased).encode()
    with pytest.raises(review.ReviewError, match="findings, or provenance"):
        review.validate_independent_review_candidate(
            erased,
            erased_raw,
            assignment,
            assignment_raw,
            result,
            result_raw,
            context,
        )


def test_result_publishers_require_external_candidate_and_exact_reviewer_actor() -> None:
    with pytest.raises(SystemExit):
        review.parse_args(["--write-review-result"])
    with pytest.raises(review.ReviewError, match="assigned reviewer"):
        review.publish_review_result_candidate(
            b"{}\n",
            ROOT,
            actor_id=review.EXECUTOR_ID,
            actor_task=review.EXECUTOR_TASK,
        )
