from __future__ import annotations

from copy import deepcopy
from dataclasses import replace
import json
from pathlib import Path

import pytest

from scripts import build_walksafe_npc_single_admin_recovery_strict_review_gate_20260812 as subject


ROOT = Path(__file__).resolve().parents[1]


ASSIGNER = {
    "agent_instance_id": "agent-assigner-20260813-0001",
    "canonical_task": "/root",
    "role": "INTERNAL_REVIEW_ASSIGNER",
}
EXECUTOR = {
    "agent_instance_id": "agent-executor-20260813-0001",
    "canonical_task": "/root",
    "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
}
REVIEWER = {
    "agent_instance_id": "agent-reviewer-20260813-0002",
    "canonical_task": "/root/npc_reviewer_r002",
    "role": "SEPARATE_INTERNAL_REVIEWER",
}


def _context() -> subject.ReviewContext:
    consumers = tuple(
        {
            "role": role,
            "path": path.as_posix(),
            "document_id": role,
            "sha256": f"{index:x}" * 64,
            "byte_length": index,
        }
        for index, (role, path) in enumerate(subject.trace.CONSUMER_SPECS, start=1)
    )
    result_hashes = {
        "IMPLEMENTATION_RECORD": "a" * 64,
        "VERIFICATION_RESULT": "b" * 64,
        "SUCCESSOR_TRACE": "c" * 64,
    }
    chronology = (
        {
            "path": subject.trace.V2_OBSERVATION_MANIFEST_REL.as_posix(),
            "json_pointer": "/observed_at",
            "timestamp": "2026-08-12T21:00:00+09:00",
        },
        {
            "path": subject.trace.V2_REVIEW_SUBJECT_REL.as_posix(),
            "json_pointer": "/observed_at",
            "timestamp": "2026-08-12T21:00:00+09:00",
        },
    )
    controls = tuple(
        {
            "path": path.as_posix(),
            "sha256": f"{index + 6:x}" * 64,
            "byte_length": index + 100,
        }
        for index, path in enumerate(subject.CONTROL_CODE_PATHS)
    )
    return subject.ReviewContext(
        result_raw={},
        consumer_bindings=consumers,
        evidence_manifest=(
            {"path": "fixture/implementation.json", "sha256": "a" * 64},
            {"path": "fixture/verification.json", "sha256": "b" * 64},
            {
                "path": subject.IMMUTABLE_PREDECESSOR_HISTORY_PATHS[0].as_posix(),
                "sha256": "c" * 64,
            },
        ),
        review_subject_sha256="d" * 64,
        reviewed_result_sha256_by_kind=result_hashes,
        implementation_started_at="2026-08-12T20:00:00+09:00",
        implementation_ended_at="2026-08-12T21:00:00+09:00",
        chronology=chronology,
        latest_reviewable_at="2026-08-12T21:00:00+09:00",
        control_code_cohort=controls,
        control_code_cohort_sha256=subject.trace.object_sha256(list(controls)),
        prior_control_code_cohort_sha256="8" * 64,
        control_code_changes_from_r002=(),
        predecessor_review={
            "path": subject.R001_REVIEW_RESULT_REL.as_posix(),
            "sha256": "9" * 64,
            "document_id": "WS-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-REVIEW-REJECTED-20260813-001",
            "round_id": subject.R001_ROUND_ID,
            "decision": "REJECTED",
            "reviewed_at": "2026-08-12T21:00:00+09:00",
            "finding_ids": [
                f"NPC-R001-{'B' if index <= 7 else 'M'}{index:03d}"
                for index in range(1, 10)
            ],
        },
        prior_approved_review={
            "round_id": subject.R002_ROUND_ID,
            "decision": "APPROVED",
            "reviewed_at": "2026-08-12T21:01:00+09:00",
            "assignment": {"path": "fixture/r002-assignment.json", "sha256": "1" * 64},
            "result": {"path": "fixture/r002-result.json", "sha256": "2" * 64},
            "independent_review": {
                "path": "fixture/r002-independent.json",
                "sha256": "3" * 64,
            },
            "completion_receipt": {
                "path": "fixture/r002-completion.json",
                "sha256": "4" * 64,
            },
        },
    )


def _assignment(context: subject.ReviewContext) -> dict[str, object]:
    return {
        "schema_version": "2.0",
        "evidence_type": "INTERNAL_REVIEW_ASSIGNMENT",
        "goal_id": subject.trace.GOAL_ID,
        "round_id": subject.R003_ROUND_ID,
        "assigned_at": "2026-08-12T21:05:00+09:00",
        "assigner": deepcopy(ASSIGNER),
        "executor": deepcopy(EXECUTOR),
        "reviewer": deepcopy(REVIEWER),
        "review_source_kind": subject.REVIEW_SOURCE_KIND,
        "review_lineage": subject._review_lineage(context),
        "additional_v2_audit_findings": deepcopy(
            list(subject.ADDITIONAL_V2_AUDIT_FINDINGS)
        ),
        "review_scope": subject._review_scope(context),
        "review_boundary": subject._review_boundary(),
    }


def _result(
    context: subject.ReviewContext,
    assignment: dict[str, object],
    *,
    decision: str = "APPROVED",
    findings: dict[str, list[dict[str, object]]] | None = None,
) -> tuple[dict[str, object], bytes]:
    assignment_raw = subject.json_text(assignment).encode()
    result = {
        "schema_version": "2.0",
        "evidence_type": "REVIEWER_AUTHORED_INTERNAL_REVIEW_RESULT",
        "goal_id": subject.trace.GOAL_ID,
        "round_id": assignment["round_id"],
        "assignment_binding": {
            "path": subject.REVIEW_ASSIGNMENT_REL.as_posix(),
            "sha256": subject.bytes_sha256(assignment_raw),
        },
        "assignment_participants": {
            "assigner": deepcopy(ASSIGNER),
            "executor": deepcopy(EXECUTOR),
        },
        "reviewer": deepcopy(REVIEWER),
        "reviewed_at": "2026-08-12T21:30:00+09:00",
        "review_source_kind": subject.REVIEW_SOURCE_KIND,
        "review_lineage": subject._review_lineage(context),
        "additional_v2_audit_findings": deepcopy(
            list(subject.ADDITIONAL_V2_AUDIT_FINDINGS)
        ),
        "review_scope": subject._review_scope(context),
        "decision": decision,
        "findings": findings
        if findings is not None
        else {"blocking": [], "major_open": [], "minor_open": []},
        "predecessor_finding_dispositions": [
            {
                "finding_id": f"NPC-R001-{'B' if index <= 7 else 'M'}{index:03d}",
                "disposition": "CLOSED",
                "rationale": "synthetic remediation was reviewed",
                "remediation_evidence_paths": ["fixture/implementation.json"],
            }
            for index in range(1, 10)
        ],
        "additional_v2_audit_finding_dispositions": [
            {
                "finding_id": finding["finding_id"],
                "disposition": "CLOSED",
                "rationale": "synthetic adversarial retest passed",
                "adversarial_retest_evidence_paths": [
                    *(
                        [
                            subject.IMMUTABLE_PREDECESSOR_HISTORY_PATHS[
                                0
                            ].as_posix(),
                            context.control_code_cohort[0]["path"],
                        ]
                        if finding["finding_id"] == "NPC-R003-AUDIT-P1-001"
                        else ["fixture/verification.json"]
                    )
                ],
            }
            for finding in subject.ADDITIONAL_V2_AUDIT_FINDINGS
        ],
        "review_boundary": subject._review_boundary(),
    }
    return result, assignment_raw


def _finding(path: str = "fixture/implementation.json") -> dict[str, object]:
    return {
        "finding_id": "NPC-R002-B001",
        "summary": "synthetic blocking finding",
        "evidence_paths": [path],
    }


def _r027_fixture_outputs(root: Path) -> dict[Path, str]:
    outputs = {
        path: f"physical output for {path.as_posix()}\n"
        for path in subject.gap_builder.OUTPUT_PATHS
    }
    for path, text in outputs.items():
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(text.encode("utf-8"))
    return outputs


def test_r027_physical_evidence_binds_json_and_markdown_exact_bytes(
    tmp_path: Path,
) -> None:
    outputs = _r027_fixture_outputs(tmp_path)
    consumer_raw = {
        subject.gap_builder.R027_GAP_JSON_REL: outputs[
            subject.gap_builder.R027_GAP_JSON_REL
        ].encode("utf-8"),
        subject.gap_builder.R027_BACKLOG_JSON_REL: outputs[
            subject.gap_builder.R027_BACKLOG_JSON_REL
        ].encode("utf-8"),
    }

    rows = subject._r027_physical_evidence_rows(
        tmp_path,
        outputs,
        consumer_raw,
    )

    assert [row["path"] for row in rows] == [
        path.as_posix() for path in subject.gap_builder.OUTPUT_PATHS
    ]
    assert {
        subject.gap_builder.R027_GAP_MD_REL.as_posix(),
        subject.gap_builder.R027_BACKLOG_MD_REL.as_posix(),
    } <= {row["path"] for row in rows}
    assert {
        row["path"]: row["sha256"] for row in rows
    } == {
        path.as_posix(): subject.bytes_sha256(outputs[path].encode("utf-8"))
        for path in subject.gap_builder.OUTPUT_PATHS
    }


def test_r027_markdown_physical_tamper_is_rejected(tmp_path: Path) -> None:
    outputs = _r027_fixture_outputs(tmp_path)
    target = tmp_path / subject.gap_builder.R027_BACKLOG_MD_REL
    target.write_bytes(b"tampered markdown\n")

    with pytest.raises(subject.BuildError, match="physical output is not reproducible"):
        subject._r027_physical_evidence_rows(tmp_path, outputs, {})


def test_r027_exact_reread_conflict_and_duplicate_inventory_are_rejected(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    outputs = _r027_fixture_outputs(tmp_path)
    with pytest.raises(subject.BuildError, match="changed during exact raw reread"):
        subject._r027_physical_evidence_rows(
            tmp_path,
            outputs,
            {subject.gap_builder.R027_GAP_JSON_REL: b"stale consumer bytes"},
        )

    monkeypatch.setattr(
        subject.gap_builder,
        "OUTPUT_PATHS",
        (
            subject.gap_builder.R027_GAP_JSON_REL,
            subject.gap_builder.R027_GAP_MD_REL,
            subject.gap_builder.R027_BACKLOG_JSON_REL,
            subject.gap_builder.R027_GAP_MD_REL,
        ),
    )
    with pytest.raises(subject.BuildError, match="inventory differs or is duplicated"):
        subject._r027_physical_evidence_rows(tmp_path, outputs, {})


def test_synthetic_separate_reviewer_roundtrip_binds_both_authored_inputs() -> None:
    context = _context()
    assignment = _assignment(context)
    result, assignment_raw = _result(context, assignment)
    result_raw = subject.json_text(result).encode()

    subject.validate_review_result(result, result_raw, assignment, assignment_raw, context)
    outputs = subject.build_post_review_outputs(
        context, assignment, assignment_raw, result, result_raw
    )
    review = json.loads(outputs[subject.INDEPENDENT_REVIEW_REL])
    completion = json.loads(outputs[subject.COMPLETION_RECEIPT_REL])

    assert review["status"] == "PASS"
    assert review["reviewer"] == REVIEWER
    assert review["assignment_provenance"]["sha256"] == subject.bytes_sha256(assignment_raw)
    assert review["review_result_provenance"]["sha256"] == subject.bytes_sha256(result_raw)
    assert completion["target_goal_id"] == subject.trace.GOAL_ID
    assert len(completion["downstream_consumer_bindings"]) == len(
        subject.trace.CONSUMER_SPECS
    )
    assert completion["completion_boundary"]["formal_test_status"] == "NOT_RUN"
    assert completion["completion_boundary"]["release_status"] == "NOT_ELIGIBLE"
    assert completion["reviewer"]["external_independence_claimed"] is False
    assert completion["reviewed_control_code_cohort"] == list(context.control_code_cohort)


def test_missing_assignment_or_result_is_rejected(tmp_path: Path) -> None:
    with pytest.raises((subject.BuildError, OSError), match="review-assignment"):
        subject.load_review_inputs(tmp_path, _context())

    assignment = _assignment(_context())
    target = tmp_path / subject.REVIEW_ASSIGNMENT_REL
    target.parent.mkdir(parents=True)
    target.write_text(subject.json_text(assignment))
    with pytest.raises((subject.BuildError, OSError), match="review-result"):
        subject.load_review_inputs(tmp_path, _context())


def test_result_assignment_hash_mismatch_is_rejected() -> None:
    context = _context()
    assignment = _assignment(context)
    result, assignment_raw = _result(context, assignment)
    result["assignment_binding"]["sha256"] = "f" * 64  # type: ignore[index]
    result_raw = subject.json_text(result).encode()

    with pytest.raises(subject.BuildError, match="assignment hash differs"):
        subject.validate_review_result(result, result_raw, assignment, assignment_raw, context)


def test_executor_cannot_self_review_by_instance_or_task() -> None:
    context = _context()
    assignment = _assignment(context)
    assignment["reviewer"] = {
        **deepcopy(REVIEWER),
        "agent_instance_id": EXECUTOR["agent_instance_id"],
    }

    with pytest.raises(subject.BuildError, match="executor cannot review"):
        subject.validate_assignment(assignment, context)


def test_reviewer_cannot_assign_own_review() -> None:
    context = _context()
    assignment = _assignment(context)
    assignment["assigner"] = {
        **deepcopy(ASSIGNER),
        "agent_instance_id": REVIEWER["agent_instance_id"],
    }

    with pytest.raises(subject.BuildError, match="reviewer cannot assign own review"):
        subject.validate_assignment(assignment, context)

    assignment = _assignment(context)
    assignment["reviewer"] = {
        **deepcopy(REVIEWER),
        "canonical_task": EXECUTOR["canonical_task"],
    }
    with pytest.raises(subject.BuildError, match="executor cannot review"):
        subject.validate_assignment(assignment, context)


def test_review_scope_hash_mismatch_is_rejected() -> None:
    context = _context()
    assignment = _assignment(context)
    assignment["review_scope"]["review_subject"]["sha256"] = "e" * 64  # type: ignore[index]

    with pytest.raises(subject.BuildError, match="review assignment scope"):
        subject.validate_assignment(assignment, context)


def test_review_timestamp_must_follow_observation_successor_consumers_and_assignment() -> None:
    context = _context()
    assignment = _assignment(context)
    result, assignment_raw = _result(context, assignment)
    result["reviewed_at"] = "2026-08-12T20:59:59+09:00"
    result_raw = subject.json_text(result).encode()

    with pytest.raises(subject.BuildError, match="predates reviewed evidence or assignment"):
        subject.validate_review_result(result, result_raw, assignment, assignment_raw, context)


def test_control_code_drift_invalidates_preexisting_assignment() -> None:
    context = _context()
    assignment = _assignment(context)
    changed_controls = list(deepcopy(context.control_code_cohort))
    changed_controls[0]["sha256"] = "f" * 64
    drifted = replace(
        context,
        control_code_cohort=tuple(changed_controls),
        control_code_cohort_sha256=subject.trace.object_sha256(changed_controls),
    )

    with pytest.raises(subject.BuildError, match="review assignment scope"):
        subject.validate_assignment(assignment, drifted)


def test_control_code_inventory_must_equal_completion_authority(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from scripts import apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812 as apply_builder

    monkeypatch.setattr(
        apply_builder,
        "SEQUENCE_AUTHORITY_PATHS",
        apply_builder.SEQUENCE_AUTHORITY_PATHS[:-1],
    )
    with pytest.raises(subject.BuildError, match="authority inventory differs"):
        subject.control_code_paths()


@pytest.mark.parametrize("decision", ["CHANGES_REQUESTED", "REJECTED"])
def test_nonapproval_is_recordable_but_cannot_authorize_completion(decision: str) -> None:
    context = _context()
    assignment = _assignment(context)
    findings = {"blocking": [_finding()], "major_open": [], "minor_open": []}
    result, assignment_raw = _result(
        context, assignment, decision=decision, findings=findings
    )
    result_raw = subject.json_text(result).encode()

    subject.validate_review_result(result, result_raw, assignment, assignment_raw, context)
    with pytest.raises(subject.BuildError, match="does not authorize completion"):
        subject.build_post_review_outputs(
            context, assignment, assignment_raw, result, result_raw
        )


def test_finding_must_reference_immutable_reviewed_evidence() -> None:
    context = _context()
    assignment = _assignment(context)
    findings = {
        "blocking": [_finding("unreviewed/file.txt")],
        "major_open": [],
        "minor_open": [],
    }
    result, assignment_raw = _result(
        context, assignment, decision="REJECTED", findings=findings
    )
    result_raw = subject.json_text(result).encode()

    with pytest.raises(subject.BuildError, match="review finding content differs"):
        subject.validate_review_result(result, result_raw, assignment, assignment_raw, context)


def test_all_nine_predecessor_findings_require_evidence_bound_dispositions() -> None:
    context = _context()
    assignment = _assignment(context)
    result, assignment_raw = _result(context, assignment)
    result["predecessor_finding_dispositions"] = result[
        "predecessor_finding_dispositions"
    ][:-1]
    result_raw = subject.json_text(result).encode()

    with pytest.raises(subject.BuildError, match="predecessor finding dispositions differ"):
        subject.validate_review_result(result, result_raw, assignment, assignment_raw, context)

    result, assignment_raw = _result(context, assignment)
    result["predecessor_finding_dispositions"][0]["finding_id"] = "NPC-R001-UNKNOWN"
    result_raw = subject.json_text(result).encode()
    with pytest.raises(subject.BuildError, match="disposition identities differ"):
        subject.validate_review_result(result, result_raw, assignment, assignment_raw, context)

    result, assignment_raw = _result(context, assignment)
    result["predecessor_finding_dispositions"][0][
        "remediation_evidence_paths"
    ] = ["not-reviewed.txt"]
    result_raw = subject.json_text(result).encode()
    with pytest.raises(subject.BuildError, match="disposition content differs"):
        subject.validate_review_result(result, result_raw, assignment, assignment_raw, context)


def test_all_nine_additional_v2_audit_findings_require_closed_retest_evidence() -> None:
    context = _context()
    assignment = _assignment(context)
    result, assignment_raw = _result(context, assignment)
    result["additional_v2_audit_finding_dispositions"] = result[
        "additional_v2_audit_finding_dispositions"
    ][:-1]
    result_raw = subject.json_text(result).encode()

    with pytest.raises(subject.BuildError, match="additional v2 audit finding dispositions differ"):
        subject.validate_review_result(result, result_raw, assignment, assignment_raw, context)

    result, assignment_raw = _result(context, assignment)
    result["additional_v2_audit_finding_dispositions"][0]["disposition"] = "OPEN"
    result_raw = subject.json_text(result).encode()
    with pytest.raises(subject.BuildError, match="audit finding disposition content differs"):
        subject.validate_review_result(result, result_raw, assignment, assignment_raw, context)


def test_r001_predecessor_requires_authoritative_physical_chain_validation(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    calls: list[Path] = []

    def reject_forged_physical_chain(root: Path) -> tuple[dict, bytes]:
        calls.append(root)
        raise subject.BuildError("R001 evidence manifest physical bytes differ")

    monkeypatch.setattr(
        subject.gap_builder,
        "load_validated_r001_review",
        reject_forged_physical_chain,
    )

    with pytest.raises(subject.BuildError, match="physical bytes differ"):
        subject._load_predecessor_review(tmp_path)

    assert calls == [tmp_path]


def test_r001_predecessor_rechecks_authoritative_exact_raw_pin(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monkeypatch.setattr(
        subject.gap_builder,
        "load_validated_r001_review",
        lambda _root: ({}, b"forged-r001-review"),
    )

    with pytest.raises(subject.BuildError, match="exact raw SHA-256"):
        subject._load_predecessor_review(tmp_path)

def test_r003_lineage_preserves_exact_r002_approved_history() -> None:
    prior = subject._load_prior_approved_review(ROOT)
    lineage = subject._review_lineage(_context())

    assert prior["round_id"] == subject.R002_ROUND_ID
    assert prior["decision"] == "APPROVED"
    assert set(subject.R002_REVIEW_HISTORY_PATHS) == set(
        subject.R002_REVIEW_HISTORY_SHA256_BY_PATH
    )
    assert len(subject.R002_REVIEW_HISTORY_PATHS) == 4
    assert lineage["predecessor_round_id"] == subject.R002_ROUND_ID
    assert lineage["predecessor_result"]["round_id"] == subject.R002_ROUND_ID
    assert lineage["origin_rejected_review"]["round_id"] == subject.R001_ROUND_ID


def test_r003_review_scope_directly_pins_exact_20_file_predecessor_history() -> None:
    history = subject.load_immutable_predecessor_history_sha256_by_path(ROOT)

    assert tuple(history) == subject.IMMUTABLE_PREDECESSOR_HISTORY_PATHS
    assert len(history) == len(set(history)) == 20
    assert history == {
        path: subject.bytes_sha256((ROOT / path).read_bytes())
        for path in subject.IMMUTABLE_PREDECESSOR_HISTORY_PATHS
    }


def test_r002_approved_history_tamper_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    target = subject.R002_REVIEW_RESULT_REL
    original = subject._read_review_bytes

    def tampered(root: Path, path: Path) -> bytes:
        if path == target:
            return b"tampered\n"
        return original(root, path)

    monkeypatch.setattr(subject, "_read_review_bytes", tampered)
    with pytest.raises(subject.BuildError, match="R002 approved review history differs"):
        subject._load_prior_approved_review(ROOT)


def test_r003_context_reuses_frozen_r002_product_evidence_and_reviews_current_controls() -> None:
    context = subject.prepare_review_context(ROOT)
    assignment_raw = subject.trace.read_bytes(ROOT, subject.R002_REVIEW_ASSIGNMENT_REL)
    assignment = subject.trace.strict_json_bytes(
        assignment_raw, subject.R002_REVIEW_ASSIGNMENT_REL.as_posix()
    )
    frozen_scope = assignment["review_scope"]
    evidence_paths = {row["path"] for row in context.evidence_manifest}

    assert context.reviewed_result_sha256_by_kind == frozen_scope[
        "reviewed_result_sha256_by_kind"
    ]
    assert context.implementation_started_at == "2026-08-13T12:44:52.402728+09:00"
    assert context.implementation_ended_at == "2026-08-13T12:54:15.395296+09:00"
    assert context.latest_reviewable_at == "2026-08-13T13:14:41+09:00"
    assert {
        path.as_posix()
        for path in (
            *subject.IMMUTABLE_PREDECESSOR_HISTORY_PATHS,
            *subject.R002_REVIEW_HISTORY_PATHS,
        )
    } <= evidence_paths
    assert tuple(row["path"] for row in context.control_code_cohort) == tuple(
        path.as_posix() for path in subject.control_code_paths()
    )
    assert context.control_code_changes_from_r002
    assert subject._review_scope(context)["completion_authority"] == {
        "completion_only_successor": True,
        "product_execution_authority_round_id": subject.R002_ROUND_ID,
        "product_execution_rerun_claimed": False,
    }


def test_r003_context_rejects_product_bytes_that_differ_from_frozen_r002(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    target = subject.trace.V2_VERIFICATION_REL
    original = subject._read_review_bytes

    def tampered(root: Path, path: Path) -> bytes:
        raw = original(root, path)
        return raw + b" " if path == target else raw

    monkeypatch.setattr(subject, "_read_review_bytes", tampered)
    with pytest.raises(subject.BuildError, match="R002 reviewed product evidence differs"):
        subject.prepare_review_context(ROOT)


def test_r003_context_never_replays_product_producers(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def forbidden(*_args: object, **_kwargs: object) -> object:
        raise AssertionError("R003 completion-only validation must not rebuild product outputs")

    for owner, name in (
        (subject.trace, "build_results_outputs"),
        (subject.trace, "build_successor_outputs"),
        (subject.gap_builder, "build_outputs"),
        (subject.artifact_builder, "build_outputs"),
        (subject.artifact_builder, "check_successor"),
        (subject.r016_builder, "build_outputs"),
    ):
        monkeypatch.setattr(owner, name, forbidden)

    context = subject.prepare_review_context(ROOT)

    assert len(context.evidence_manifest) == 138


def test_r003_history_omission_finding_cannot_be_not_applicable() -> None:
    context = _context()
    assignment = _assignment(context)
    result, assignment_raw = _result(context, assignment)
    result["additional_v2_audit_finding_dispositions"][-1][
        "disposition"
    ] = "NOT_APPLICABLE"
    result_raw = subject.json_text(result).encode()

    with pytest.raises(subject.BuildError, match="audit finding disposition content"):
        subject.validate_review_result(
            result,
            result_raw,
            assignment,
            assignment_raw,
            context,
        )


def test_post_review_write_guard_rejects_stale_outputs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    context = _context()
    assignment = _assignment(context)
    result, assignment_raw = _result(context, assignment)
    result_raw = subject.json_text(result).encode()
    current = {subject.INDEPENDENT_REVIEW_REL: "current\n"}
    monkeypatch.setattr(subject, "prepare_review_context", lambda _root: context)
    monkeypatch.setattr(
        subject,
        "load_review_inputs",
        lambda _root, _context: (
            assignment,
            assignment_raw,
            result,
            result_raw,
        ),
    )
    monkeypatch.setattr(
        subject,
        "build_post_review_outputs",
        lambda *_args, **_kwargs: current,
    )

    with pytest.raises(subject.BuildError, match="changed before add-only publication"):
        subject._guard_post_review_write_boundary(
            ROOT,
            {subject.INDEPENDENT_REVIEW_REL: "stale\n"},
        )


def test_review_reader_rejects_hard_link_alias(tmp_path: Path) -> None:
    relative = Path("evidence.json")
    target = tmp_path / relative
    target.write_bytes(b"evidence\n")
    (tmp_path / "outside-alias.json").hardlink_to(target)

    with pytest.raises(subject.BuildError, match="file authority differs"):
        subject._read_review_bytes(tmp_path, relative)


def test_review_reader_uses_one_descriptor_and_detects_ctime_change(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    relative = Path("evidence.json")
    target = tmp_path / relative
    target.write_bytes(b"evidence\n")
    original_read = subject.os.read
    changed = False

    def read_then_change(descriptor: int, count: int) -> bytes:
        nonlocal changed
        raw = original_read(descriptor, count)
        if raw and not changed:
            changed = True
            target.write_bytes(b"changed!\n")
            target.write_bytes(b"evidence\n")
        return raw

    monkeypatch.setattr(subject.os, "read", read_then_change)
    with pytest.raises(subject.BuildError, match="changed while reading"):
        subject._read_review_bytes(tmp_path, relative)


def test_review_reader_rejects_parent_entry_replacement_during_read(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    relative = Path("a/b/evidence.json")
    target = tmp_path / relative
    target.parent.mkdir(parents=True)
    target.write_bytes(b"evidence\n")
    original_read = subject.os.read
    replaced = False

    def read_then_replace(descriptor: int, count: int) -> bytes:
        nonlocal replaced
        raw = original_read(descriptor, count)
        if raw and not replaced:
            replaced = True
            (tmp_path / "a").rename(tmp_path / "a.old")
            replacement = tmp_path / relative
            replacement.parent.mkdir(parents=True)
            replacement.write_bytes(b"evidence\n")
        return raw

    monkeypatch.setattr(subject.os, "read", read_then_replace)
    with pytest.raises(subject.BuildError, match="parent changed while reading"):
        subject._read_review_bytes(tmp_path, relative)


def test_post_review_publisher_holds_checkpoint_lock_across_guard_and_write(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    checkpoint = tmp_path / subject.trace.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(b"checkpoint\n")
    checkpoint.chmod(0o600)
    calls: list[str] = []

    def guard(_root: Path, _outputs: object) -> None:
        calls.append("guard")

    def writer(_root: Path, _outputs: object, *, write: bool) -> None:
        calls.append("write")
        assert write is True
        contender = subject.os.open(
            checkpoint.parent,
            subject.os.O_RDONLY | subject.os.O_DIRECTORY,
        )
        try:
            with pytest.raises(BlockingIOError):
                subject.fcntl.flock(
                    contender,
                    subject.fcntl.LOCK_EX | subject.fcntl.LOCK_NB,
                )
        finally:
            subject.os.close(contender)

    monkeypatch.setattr(subject, "_guard_post_review_write_boundary", guard)
    monkeypatch.setattr(subject.trace, "write_or_check_outputs", writer)

    subject._write_post_review_outputs(tmp_path, {})

    assert calls == ["guard", "write"]


def test_post_review_publisher_rejects_checkpoint_replacement_before_write(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    checkpoint = tmp_path / subject.trace.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(b"checkpoint\n")
    checkpoint.chmod(0o600)
    writer_called = False

    def replace_checkpoint(_root: Path, _outputs: object) -> None:
        replacement = checkpoint.parent / "replacement.json"
        replacement.write_bytes(b"replacement\n")
        replacement.chmod(0o600)
        replacement.replace(checkpoint)

    def writer(_root: Path, _outputs: object, *, write: bool) -> None:
        nonlocal writer_called
        writer_called = True

    monkeypatch.setattr(
        subject,
        "_guard_post_review_write_boundary",
        replace_checkpoint,
    )
    monkeypatch.setattr(subject.trace, "write_or_check_outputs", writer)

    with pytest.raises(subject.BuildError, match="changed before add-only publication"):
        subject._write_post_review_outputs(tmp_path, {})

    assert writer_called is False


def test_post_review_publisher_rejects_same_inode_checkpoint_change(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    checkpoint = tmp_path / subject.trace.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(b"checkpoint\n")
    checkpoint.chmod(0o600)

    def writer(_root: Path, _outputs: object, *, write: bool) -> None:
        assert write is True
        checkpoint.write_bytes(b"same-inode-change\n")

    monkeypatch.setattr(
        subject,
        "_guard_post_review_write_boundary",
        lambda *_args: None,
    )
    monkeypatch.setattr(subject.trace, "write_or_check_outputs", writer)

    with pytest.raises(
        subject.BuildError,
        match="descriptor changed during add-only publication",
    ):
        subject._write_post_review_outputs(tmp_path, {})


def test_post_review_publisher_rejects_same_inode_change_before_writer(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    checkpoint = tmp_path / subject.trace.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(b"checkpoint\n")
    checkpoint.chmod(0o600)
    writer_called = False

    def guard(_root: Path, _outputs: object) -> None:
        checkpoint.write_bytes(b"guard-changed-same-inode\n")

    def writer(_root: Path, _outputs: object, *, write: bool) -> None:
        nonlocal writer_called
        writer_called = True

    monkeypatch.setattr(subject, "_guard_post_review_write_boundary", guard)
    monkeypatch.setattr(subject.trace, "write_or_check_outputs", writer)

    with pytest.raises(
        subject.BuildError,
        match="changed before add-only publication",
    ):
        subject._write_post_review_outputs(tmp_path, {})

    assert writer_called is False


def test_gate_has_no_default_attestation_or_decision_generator() -> None:
    assert not hasattr(subject, "build_attestation")
    assert not hasattr(subject, "load_attestation")


@pytest.mark.parametrize(
    "forbidden",
    [
        "--write-attestation",
        "--reviewed-at",
        "--reviewer-id",
        "--decision",
        "--write-review-result",
        "--write-assignment",
    ],
)
def test_cli_forbids_identity_decision_and_input_generators(forbidden: str) -> None:
    with pytest.raises(SystemExit):
        subject.parse_args([forbidden])


def test_cli_exposes_only_validator_and_post_review_modes() -> None:
    for mode in (
        "--check-review-result",
        "--write-post-review",
        "--check-post-review",
    ):
        args = subject.parse_args([mode])
        assert getattr(args, mode.removeprefix("--").replace("-", "_")) is True


def test_check_fails_while_observation_pin_is_unresolved() -> None:
    with pytest.raises(subject.BuildError, match="unresolved product verification input"):
        subject.trace.require_resolved_observation_pin(
            subject.trace.PENDING_OBSERVATION_MANIFEST_SHA256
        )


def test_review_context_inventory_uses_only_v2_correction_results() -> None:
    paths = set(subject._result_paths())
    assert {
        subject.trace.V2_IMPLEMENTATION_REL,
        subject.trace.V2_VERIFICATION_REL,
        subject.trace.V2_SUCCESSOR_REL,
        subject.trace.V2_REVIEW_SUBJECT_REL,
    } <= paths
    assert paths.isdisjoint(
        {
            subject.trace.IMPLEMENTATION_REL,
            subject.trace.VERIFICATION_REL,
            subject.trace.SUCCESSOR_REL,
            subject.trace.REVIEW_SUBJECT_REL,
        }
    )


def test_review_window_orders_instant_not_timestamp_text() -> None:
    starts = ["2026-08-12T20:30:00+09:00", "2026-08-12T12:00:00+00:00"]
    assert min(
        starts,
        key=lambda value: subject.trace._parse_time(value, "started_at"),
    ) == "2026-08-12T20:30:00+09:00"
