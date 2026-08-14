from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path

import pytest

from scripts import build_walksafe_npc_single_admin_recovery_gap_backlog_r027_20260813 as subject


ROOT = Path(__file__).resolve().parents[1]


def _predecessors() -> tuple[dict, dict, dict[Path, bytes]]:
    raw = {path: (ROOT / path).read_bytes() for path in subject.R026_INPUT_PATHS}
    return (
        subject.trace.strict_json_bytes(raw[subject.R026_GAP_JSON_REL], "R026 gap"),
        subject.trace.strict_json_bytes(
            raw[subject.R026_BACKLOG_JSON_REL], "R026 backlog"
        ),
        raw,
    )


def _v2_results() -> tuple[dict, bytes, dict, bytes]:
    manifest = {
        "files": [
            {
                "path": "backend/app/services/admin_security.py",
                "sha256": "a" * 64,
                "byte_count": 10,
            }
        ],
        "file_count": 1,
        "path_set_sha256": "b" * 64,
        "content_set_sha256": "c" * 64,
    }
    manifest["manifest_content_sha256"] = subject.object_sha256(manifest)
    implementation = subject.trace._seal(
        {
            "schema_version": "1.0",
            "document_id": "TEST-NPC-IMPLEMENTATION-CORRECTION-V2",
            "kind": "IMPLEMENTATION_RECORD",
            "evidence_schema": "V2_CORRECTION_ONLY",
            "goal_id": subject.GOAL_ID,
            "policy_id": subject.POLICY_ID,
            "gap_id": subject.GAP_ID,
            "status": "PASS_INTERNAL",
            "observed_at": "2026-08-13T08:00:00+09:00",
            "scope_kind": "EXACT_ORDERED_NPC_SINGLE_ADMIN_RECOVERY_FINAL_CONTENT_MANIFEST",
            "final_content_manifest": manifest,
            "completion_boundary": subject.trace.completion_boundary(),
        },
        "implementation_record_content_sha256",
    )
    implementation_raw = subject.json_text(implementation).encode()
    verification = subject.trace._seal(
        {
            "schema_version": "1.0",
            "document_id": "TEST-NPC-VERIFICATION-CORRECTION-V2",
            "kind": "VERIFICATION_RESULT",
            "evidence_schema": "V2_CORRECTION_ONLY",
            "goal_id": subject.GOAL_ID,
            "status": "PASS_INTERNAL",
            "observed_at": implementation["observed_at"],
            "implementation_record_sha256": subject.bytes_sha256(implementation_raw),
            "completion_boundary": subject.trace.completion_boundary(),
        },
        "verification_result_content_sha256",
    )
    verification_raw = subject.json_text(verification).encode()
    return implementation, implementation_raw, verification, verification_raw


def _v1_review_context() -> tuple[dict, bytes, bytes, bytes, bytes, dict[Path, bytes]]:
    subject_raw = (ROOT / subject.trace.REVIEW_SUBJECT_REL).read_bytes()
    review_subject = subject.trace.strict_json_bytes(subject_raw, "v1 review subject")
    historical_artifacts = subject._reviewed_v1_artifact_overrides(ROOT)
    evidence_raw = {
        Path(binding["path"]): (
            historical_artifacts[Path(binding["path"])]
            if Path(binding["path"]) in historical_artifacts
            else (ROOT / binding["path"]).read_bytes()
        )
        for binding in review_subject["reviewed_consumer_bindings"]
    }
    return (
        review_subject,
        subject_raw,
        (ROOT / subject.trace.IMPLEMENTATION_REL).read_bytes(),
        (ROOT / subject.trace.VERIFICATION_REL).read_bytes(),
        (ROOT / subject.trace.SUCCESSOR_REL).read_bytes(),
        evidence_raw,
    )


def _r001_review() -> tuple[dict, bytes]:
    raw = (ROOT / subject.R001_REJECTED_REVIEW_REL).read_bytes()
    return subject.trace.strict_json_bytes(raw, "R001 review"), raw


def _r001_history_raw() -> dict[Path, bytes]:
    return {path: (ROOT / path).read_bytes() for path in subject.R001_HISTORY_PATHS}


def _copy_r001_history(root: Path) -> tuple[dict, bytes]:
    for path in (*subject.R001_HISTORY_PATHS, subject.R001_REJECTED_REVIEW_REL):
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((ROOT / path).read_bytes())
    return _r001_review()


def _rejected_review(
    predecessor_gap: dict,
    review_subject_raw: bytes,
    v1_implementation_raw: bytes,
    v1_verification_raw: bytes,
    v1_successor_raw: bytes,
    reviewed_evidence_raw_by_path: dict[Path, bytes],
) -> tuple[dict, bytes]:
    old = next(
        row
        for row in predecessor_gap["assessments"]
        if row["gap_id"] == subject.GAP_ID
    )["npc_single_admin_recovery_reassessment"]
    findings = {
        "blocking": [
            {
                "finding_id": f"NPC-R001-B{index:03d}",
                "summary": f"synthetic blocking finding {index}",
                "evidence_paths": [next(iter(sorted(path.as_posix() for path in reviewed_evidence_raw_by_path)))],
            }
            for index in range(1, 8)
        ],
        "major_open": [
            {
                "finding_id": f"NPC-R001-M{index:03d}",
                "summary": f"synthetic major finding {index}",
                "evidence_paths": [next(iter(sorted(path.as_posix() for path in reviewed_evidence_raw_by_path)))],
            }
            for index in range(1, 3)
        ],
        "minor_open": [],
    }
    review = {
        "schema_version": "walksafe.npc-single-admin-recovery.review-result.v2",
        "document_id": subject.R001_DOCUMENT_ID,
        "goal_id": subject.GOAL_ID,
        "round_id": subject.R001_ROUND_ID,
        "assignment_status": "NOT_ISSUED_LEGACY_ROUND",
        "decision": "REJECTED",
        "reviewer": {
            "agent_instance_id": "agent-reviewer-20260813-r001",
            "canonical_task": "/root/npc_single_admin_recovery_independent_review",
            "role": "SEPARATE_INTERNAL_REVIEWER",
        },
        "reviewed_at": "2026-08-13T07:30:00+09:00",
        "finding_counts": deepcopy(subject.R001_FINDING_COUNTS),
        "findings": findings,
        "review_subject_binding": {
            "path": subject.trace.REVIEW_SUBJECT_REL.as_posix(),
            "sha256": subject.bytes_sha256(review_subject_raw),
        },
        "reviewed_result_sha256_by_kind": {
            "IMPLEMENTATION_RECORD": subject.bytes_sha256(v1_implementation_raw),
            "VERIFICATION_RESULT": subject.bytes_sha256(v1_verification_raw),
            "SUCCESSOR_TRACE": subject.bytes_sha256(v1_successor_raw),
        },
        "reviewed_evidence_manifest": [
            {"path": path.as_posix(), "sha256": subject.bytes_sha256(raw)}
            for path, raw in sorted(
                reviewed_evidence_raw_by_path.items(), key=lambda item: item[0].as_posix()
            )
        ],
        "completion_boundary": subject.trace.completion_boundary(),
    }
    return review, subject.json_text(review).encode()


def _documents() -> tuple[dict, dict, dict[Path, bytes], dict, bytes, dict, bytes, dict, bytes]:
    predecessor_gap, predecessor_backlog, predecessor_raw = _predecessors()
    implementation, implementation_raw, verification, verification_raw = _v2_results()
    (
        review_subject,
        review_subject_raw,
        v1_implementation_raw,
        v1_verification_raw,
        v1_successor_raw,
        reviewed_evidence_raw_by_path,
    ) = _v1_review_context()
    review, review_raw = _rejected_review(
        predecessor_gap,
        review_subject_raw,
        v1_implementation_raw,
        v1_verification_raw,
        v1_successor_raw,
        reviewed_evidence_raw_by_path,
    )
    return (
        predecessor_gap,
        predecessor_backlog,
        predecessor_raw,
        implementation,
        implementation_raw,
        verification,
        verification_raw,
        review,
        review_raw,
        review_subject,
        review_subject_raw,
        v1_implementation_raw,
        v1_verification_raw,
        v1_successor_raw,
        reviewed_evidence_raw_by_path,
    )


def _build(
    values: tuple,
    *,
    rebuilt_v2_outputs: dict[Path, str] | None = None,
    expected_review_sha256: str | None = None,
) -> dict[Path, str]:
    (
        gap,
        backlog,
        predecessor_raw,
        implementation,
        implementation_raw,
        verification,
        verification_raw,
        review,
        review_raw,
        review_subject,
        review_subject_raw,
        v1_implementation_raw,
        v1_verification_raw,
        v1_successor_raw,
        reviewed_evidence_raw_by_path,
    ) = values
    if rebuilt_v2_outputs is None:
        rebuilt_v2_outputs = {
            subject.trace.V2_IMPLEMENTATION_REL: subject.json_text(implementation),
            subject.trace.V2_VERIFICATION_REL: subject.json_text(verification),
        }
    return subject.build_documents(
        gap,
        backlog,
        implementation,
        verification,
        review,
        predecessor_raw_by_path=predecessor_raw,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        review_raw=review_raw,
        review_subject=review_subject,
        review_subject_raw=review_subject_raw,
        v1_implementation_raw=v1_implementation_raw,
        v1_verification_raw=v1_verification_raw,
        v1_successor_raw=v1_successor_raw,
        reviewed_evidence_raw_by_path=reviewed_evidence_raw_by_path,
        rebuilt_v2_outputs=rebuilt_v2_outputs,
        expected_review_sha256=(
            subject.bytes_sha256(review_raw)
            if expected_review_sha256 is None
            else expected_review_sha256
        ),
    )


def test_r027_corrects_only_current_gap008_internal_evidence() -> None:
    values = _documents()
    gap_before, backlog_before = values[:2]
    predecessor_hashes = {
        path: subject.bytes_sha256(raw) for path, raw in values[2].items()
    }
    outputs = _build(values)
    gap_after = json.loads(outputs[subject.R027_GAP_JSON_REL])
    backlog_after = json.loads(outputs[subject.R027_BACKLOG_JSON_REL])
    before_by_id = {row["gap_id"]: row for row in gap_before["assessments"]}
    after_by_id = {row["gap_id"]: row for row in gap_after["assessments"]}

    assert after_by_id["GAP-008"]["status"] == "PARTIAL"
    assert after_by_id["GAP-008"]["formal_test_status"] == "NOT_RUN"
    assert after_by_id["GAP-008"]["waived"] is False
    assert after_by_id["GAP-008"]["npc_single_admin_recovery_reassessment"][
        "evidence_schema"
    ] == "V2_CORRECTION_ONLY"
    assert after_by_id["GAP-068"] == before_by_id["GAP-068"]
    assert all(
        after_by_id[gap_id] == before_by_id[gap_id]
        for gap_id in before_by_id
        if gap_id != "GAP-008"
    )
    assert gap_after["summary"]["status_counts"] == subject.EXPECTED_STATUS_COUNTS
    assert gap_after["summary"]["implemented_and_formally_verified_count"] == 0
    assert gap_after["summary"]["release_status"] == "NOT_ELIGIBLE"
    correction = gap_after["npc_single_admin_recovery_evidence_correction"]
    assert correction["scope"] == "CURRENT_REPOSITORY_INTERNAL_EVIDENCE_ONLY"
    assert correction["predecessor_must_remain_add_only"] is True
    assert correction[
        "formal_device_drill_external_deployment_approval_release_credit_promoted"
    ] is False
    assert backlog_after["next_action_sequence"] == backlog_before["next_action_sequence"]
    assert backlog_after["epics"] == backlog_before["epics"]
    assert backlog_after["next_single_action"] == backlog_before["next_single_action"]
    assert backlog_after["next_single_action"]["work_item_id"] == "WS-GOAL-EPIC-04-FP-022-R001"
    assert {
        path: subject.bytes_sha256(raw) for path, raw in values[2].items()
    } == predecessor_hashes

    subject.trace.verify_seal(gap_after, "report_content_sha256", "R027 gap")
    subject.trace.verify_seal(backlog_after, "backlog_content_sha256", "R027 backlog")
    assert outputs[subject.R027_GAP_JSON_REL] == subject.json_text(gap_after)
    assert outputs[subject.R027_BACKLOG_JSON_REL] == subject.json_text(backlog_after)


def test_r027_rejects_any_r026_predecessor_byte_drift() -> None:
    values = list(_documents())
    raw = deepcopy(values[2])
    raw[subject.R026_GAP_MD_REL] += b"\n"
    values[2] = raw

    with pytest.raises(subject.BuildError, match="R026 predecessor bytes differ"):
        _build(tuple(values))


def test_r027_rejects_non_v2_or_credit_promoting_producer() -> None:
    values = list(_documents())
    implementation = deepcopy(values[3])
    implementation["evidence_schema"] = "V1"
    implementation = subject.trace._seal(
        implementation, "implementation_record_content_sha256"
    )
    values[3] = implementation
    values[4] = subject.json_text(implementation).encode()
    with pytest.raises(subject.BuildError, match="v2 implementation identity"):
        _build(tuple(values))

    values = list(_documents())
    implementation = deepcopy(values[3])
    implementation["completion_boundary"]["formal_test_status"] = "PASS"
    implementation = subject.trace._seal(
        implementation, "implementation_record_content_sha256"
    )
    values[3] = implementation
    values[4] = subject.json_text(implementation).encode()
    verification = deepcopy(values[5])
    verification["implementation_record_sha256"] = subject.bytes_sha256(values[4])
    verification = subject.trace._seal(
        verification, "verification_result_content_sha256"
    )
    values[5] = verification
    values[6] = subject.json_text(verification).encode()
    with pytest.raises(subject.BuildError, match="completion boundary"):
        _build(tuple(values))


def test_r027_rejects_self_sealed_producer_not_rebuilt_from_observations() -> None:
    values = list(_documents())
    rebuilt_v2_outputs = {
        subject.trace.V2_IMPLEMENTATION_REL: subject.json_text(values[3]),
        subject.trace.V2_VERIFICATION_REL: subject.json_text(values[5]),
    }
    forged = deepcopy(values[3])
    forged["implemented_controls"] = ["FORGED_BUT_SELF_SEALED"]
    forged = subject.trace._seal(forged, "implementation_record_content_sha256")
    values[3] = forged
    values[4] = subject.json_text(forged).encode()
    forged_verification = deepcopy(values[5])
    forged_verification["implementation_record_sha256"] = subject.bytes_sha256(values[4])
    forged_verification = subject.trace._seal(
        forged_verification, "verification_result_content_sha256"
    )
    values[5] = forged_verification
    values[6] = subject.json_text(forged_verification).encode()

    with pytest.raises(subject.BuildError, match="do not reproduce"):
        _build(tuple(values), rebuilt_v2_outputs=rebuilt_v2_outputs)


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        (lambda review: review.update(decision="APPROVED"), "review identity"),
        (
            lambda review: review["reviewed_result_sha256_by_kind"].update(
                IMPLEMENTATION_RECORD="0" * 64
            ),
            "does not bind",
        ),
        (lambda review: review.update(reviewed_at="2026-08-13T08:00:01+09:00"), "chronology"),
    ],
)
def test_r027_rejects_invalid_rejected_review(mutation, message: str) -> None:
    values = list(_documents())
    review = deepcopy(values[7])
    mutation(review)
    values[7] = review
    values[8] = subject.json_text(review).encode()

    baseline_pin = subject.bytes_sha256(_documents()[8])
    with pytest.raises(subject.BuildError, match="exact raw SHA-256"):
        _build(tuple(values), expected_review_sha256=baseline_pin)


@pytest.mark.parametrize("attack", ["subject", "manifest", "finding"])
def test_r027_rejects_pinned_r001_physical_binding_or_finding_forgery(
    attack: str,
) -> None:
    values = list(_documents())
    review = deepcopy(values[7])
    if attack == "subject":
        review["review_subject_binding"]["sha256"] = "0" * 64
    elif attack == "manifest":
        review["reviewed_evidence_manifest"][0]["sha256"] = "0" * 64
    else:
        review["findings"]["blocking"][0]["summary"] = "forged finding text"
    values[7] = review
    values[8] = subject.json_text(review).encode()
    expected_pin = (
        subject.bytes_sha256(_documents()[8])
        if attack == "finding"
        else subject.bytes_sha256(values[8])
    )

    with pytest.raises(
        subject.BuildError,
        match="exact raw SHA-256|physical binding|physical bytes|finding content",
    ):
        _build(tuple(values), expected_review_sha256=expected_pin)


def test_add_only_writer_rejects_overwrite_with_different_bytes(tmp_path: Path) -> None:
    relative = subject.R027_GAP_MD_REL
    subject.trace.write_or_check_outputs(tmp_path, {relative: "first\n"}, write=True)
    with pytest.raises(subject.BuildError, match="differs"):
        subject.trace.write_or_check_outputs(tmp_path, {relative: "second\n"}, write=True)


def test_r001_history_exporter_returns_exact_immutable_13_file_chain() -> None:
    result = subject.load_validated_r001_history_sha256_by_path(ROOT)

    assert tuple(result) == subject.R001_HISTORY_PATHS
    assert len(result) == 13
    assert sum(path.suffix == ".json" for path in result) == 9
    assert sum(path.suffix == ".log" for path in result) == 4
    assert result == {
        path: subject.bytes_sha256((ROOT / path).read_bytes())
        for path in subject.R001_HISTORY_PATHS
    }


@pytest.mark.parametrize(
    "path",
    [
        subject.trace.IMPLEMENTATION_REL,
        subject._R001_LANE_RECEIPT_PATH_BY_ID["ADMIN_ANDROID_UNIT"],
        subject._R001_LOG_PATH_BY_ID["ADMIN_ANDROID_UNIT"],
    ],
)
def test_r001_history_exporter_rejects_tampered_bytes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    path: Path,
) -> None:
    review, review_raw = _copy_r001_history(tmp_path)
    monkeypatch.setattr(
        subject,
        "load_validated_r001_review",
        lambda _root: (deepcopy(review), review_raw),
    )
    target = tmp_path / path
    target.write_bytes(target.read_bytes() + b"tampered")

    with pytest.raises(subject.BuildError):
        subject.load_validated_r001_history_sha256_by_path(tmp_path)


def test_r001_history_exporter_rejects_deleted_file(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    review, review_raw = _copy_r001_history(tmp_path)
    monkeypatch.setattr(
        subject,
        "load_validated_r001_review",
        lambda _root: (deepcopy(review), review_raw),
    )
    (tmp_path / subject._R001_LOG_PATH_BY_ID["BACKEND_RECOVERY_PYTEST"]).unlink()

    with pytest.raises(subject.BuildError, match="required source missing"):
        subject.load_validated_r001_history_sha256_by_path(tmp_path)


@pytest.mark.parametrize("attack", ["missing", "extra", "lane_missing"])
def test_r001_history_validator_rejects_nonexact_inventory(attack: str) -> None:
    review, _ = _r001_review()
    raw_by_path = _r001_history_raw()
    if attack == "missing":
        raw_by_path.pop(subject.trace.OBSERVATION_MANIFEST_REL)
    elif attack == "extra":
        raw_by_path[Path("unexpected-r001-history.json")] = b"{}\n"
    else:
        verification = subject.trace.strict_json_bytes(
            raw_by_path[subject.trace.VERIFICATION_REL], "v1 verification"
        )
        verification["lane_receipts"].pop()
        verification = subject.trace._seal(
            verification, "verification_result_content_sha256"
        )
        verification_raw = subject.json_text(verification).encode()
        raw_by_path[subject.trace.VERIFICATION_REL] = verification_raw
        next(
            row
            for row in review["reviewed_evidence_manifest"]
            if row["path"] == subject.trace.VERIFICATION_REL.as_posix()
        )["sha256"] = subject.bytes_sha256(verification_raw)

    with pytest.raises(subject.BuildError, match="inventory"):
        subject._validated_r001_history_sha256_by_path(review, raw_by_path)


def test_r001_history_validator_rejects_lane_receipt_log_rebinding() -> None:
    review, _ = _r001_review()
    raw_by_path = _r001_history_raw()
    lane_id = "ADMIN_ANDROID_UNIT"
    receipt_path = subject._R001_LANE_RECEIPT_PATH_BY_ID[lane_id]
    receipt = subject.trace.strict_json_bytes(raw_by_path[receipt_path], "lane receipt")
    receipt["raw_output_binding"]["sha256"] = "0" * 64
    receipt = subject.trace._seal(receipt, "receipt_content_sha256")
    receipt_raw = subject.json_text(receipt).encode()
    raw_by_path[receipt_path] = receipt_raw

    verification = subject.trace.strict_json_bytes(
        raw_by_path[subject.trace.VERIFICATION_REL], "v1 verification"
    )
    next(
        row for row in verification["lane_receipts"] if row["lane_id"] == lane_id
    )["sha256"] = subject.bytes_sha256(receipt_raw)
    verification = subject.trace._seal(
        verification, "verification_result_content_sha256"
    )
    verification_raw = subject.json_text(verification).encode()
    raw_by_path[subject.trace.VERIFICATION_REL] = verification_raw
    next(
        row
        for row in review["reviewed_evidence_manifest"]
        if row["path"] == subject.trace.VERIFICATION_REL.as_posix()
    )["sha256"] = subject.bytes_sha256(verification_raw)

    with pytest.raises(subject.BuildError, match="receipt/log binding"):
        subject._validated_r001_history_sha256_by_path(review, raw_by_path)


def test_check_reports_missing_inputs(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert subject.main(["--root", str(tmp_path), "--check"]) == 1
    assert "required source missing" in capsys.readouterr().out


def test_live_outputs_match_frozen_r002_review_when_chain_is_materialized() -> None:
    assignment_path = (
        subject.trace.RESULT_DIR_REL
        / "review-rounds"
        / "R002"
        / "review-assignment.json"
    )
    if not (ROOT / assignment_path).is_file():
        pytest.skip("R002 frozen review is not materialized yet")
    inputs = (
        *subject.R026_INPUT_PATHS,
        subject.trace.V2_OBSERVATION_MANIFEST_REL,
        subject.trace.V2_IMPLEMENTATION_REL,
        subject.trace.V2_VERIFICATION_REL,
        subject.R001_REJECTED_REVIEW_REL,
    )
    missing_inputs = [path for path in inputs if not (ROOT / path).is_file()]
    missing_outputs = [path for path in subject.OUTPUT_PATHS if not (ROOT / path).is_file()]
    assert missing_inputs == []
    assert missing_outputs == []

    assignment_raw = (ROOT / assignment_path).read_bytes()
    assignment = subject.trace.strict_json_bytes(
        assignment_raw,
        assignment_path.as_posix(),
    )
    frozen = {
        Path(row["path"]): row["sha256"]
        for row in assignment["review_scope"]["reviewed_evidence_manifest"]
        if Path(row["path"]) in subject.OUTPUT_PATHS
    }

    assert set(frozen) == set(subject.OUTPUT_PATHS)
    assert frozen == {
        path: subject.bytes_sha256((ROOT / path).read_bytes())
        for path in subject.OUTPUT_PATHS
    }
