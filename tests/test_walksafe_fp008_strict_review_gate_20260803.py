from __future__ import annotations

import json
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import walksafe_fp008_builder_test_support as support
from scripts import build_walksafe_fp008_admin_review_delivery_trace_20260803 as trace
from scripts import build_walksafe_fp008_artifact_trace_successor_20260803 as artifact_builder
from scripts import build_walksafe_fp008_strict_review_gate_20260803 as gate


def test_strict_review_round_trip_binds_policy_exact11_and_not_run_boundaries(tmp_path: Path) -> None:
    support.materialize_complete_fixture(tmp_path)
    context = gate.prepare_review_context(tmp_path)
    assert len(context.consumer_bindings) == 11
    with pytest.raises(trace.BuildError, match="second precision"):
        gate.build_attestation(context, "2026-08-09T13:00:00.000001+09:00")
    assert context.result_documents[trace.REVIEW_SUBJECT_REL]["policy_contract_binding"]["sha256"] == trace.bytes_sha256(
        (tmp_path / trace.POLICY_CONTRACT_REL).read_bytes()
    )
    attestation = gate.build_attestation(context, "2026-08-09T13:00:00+09:00")
    raw = gate.json_text(attestation).encode()
    outputs = gate.build_post_review_outputs(context, attestation, raw)
    review = json.loads(outputs[trace.INDEPENDENT_REVIEW_REL])
    completion = json.loads(outputs[trace.COMPLETION_RECEIPT_REL])
    assert review["status"] == "PASS"
    assert review["findings"] == {"blocking": 0, "major_open": 0, "minor_open": 0}
    assert completion["status"] == "ACCEPTED"
    assert completion["execution_session_event"]["sequence"] == 48
    assert completion["completion_boundary"]["planned_test_ids"] == list(trace.FORMAL_TEST_IDS)
    assert completion["completion_boundary"]["formal_test_status"] == "NOT_RUN"
    assert completion["completion_boundary"]["actual_device_status"] == "NOT_RUN"
    assert completion["completion_boundary"]["external_institution_status"] == "NOT_RUN"
    assert completion["completion_boundary"]["production_deployment_status"] == "NOT_RUN"


def test_executor_extra_inventory_and_boundary_tamper_fail_closed(tmp_path: Path) -> None:
    support.materialize_complete_fixture(tmp_path)
    context = gate.prepare_review_context(tmp_path)
    with pytest.raises(trace.BuildError, match="executor"):
        gate.build_attestation(
            context,
            "2026-08-09T13:00:00+09:00",
            reviewer_id=gate.EXECUTOR_ID,
            reviewer_task=gate.EXECUTOR_TASK,
        )
    (tmp_path / trace.RESULT_DIR_REL / "daylog.json").write_text("{}")
    with pytest.raises(trace.BuildError, match="inventory"):
        gate.prepare_review_context(tmp_path)


def test_strict_review_rejects_source_drift_and_lane_receipt_tamper(tmp_path: Path) -> None:
    support.materialize_complete_fixture(tmp_path)
    source = tmp_path / trace.IMPLEMENTATION_PATHS[0]
    source.write_bytes(source.read_bytes() + b"\nsource-drift")
    with pytest.raises(trace.BuildError, match="pre-review producer rebuild"):
        gate.prepare_review_context(tmp_path)

    support.materialize_complete_fixture(tmp_path)
    receipt_path = tmp_path / trace.LANES[0].receipt_rel
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    receipt["status"] = "FAIL"
    receipt_path.write_text(trace.json_text(receipt), encoding="utf-8")
    with pytest.raises(trace.BuildError, match="pre-review producer rebuild"):
        gate.prepare_review_context(tmp_path)


def test_strict_review_rejects_gate_authority_and_resealed_formal_tamper(tmp_path: Path) -> None:
    support.materialize_complete_fixture(tmp_path)
    repository_state = tmp_path / trace.RESUME_GATE_REPOSITORY_STATE_REL
    repository_state.write_bytes(repository_state.read_bytes() + b"\nauthority-drift")
    with pytest.raises(trace.BuildError, match="repository-state bytes"):
        gate.prepare_review_context(tmp_path)

    support.materialize_complete_fixture(tmp_path)
    rtm_path = tmp_path / artifact_builder.RTM_REL
    rtm = json.loads(rtm_path.read_text(encoding="utf-8"))
    requirement = next(
        row for row in rtm["requirements"] if row["requirement_id"] == "RQ-FP-008-001"
    )
    requirement["acceptance_conditions"][0]["test_execution_status"] = "PASS"
    requirement["acceptance_conditions"][0]["pass_claimed"] = True
    requirement.pop("content_sha256")
    requirement["content_sha256"] = trace.object_sha256(requirement)
    rtm["requirement_binding_sha256"] = trace.object_sha256(rtm["requirements"])
    rtm.pop("document_content_sha256")
    rtm["document_content_sha256"] = trace.object_sha256(rtm)
    rtm_path.write_text(trace.json_text(rtm), encoding="utf-8")
    with pytest.raises(trace.BuildError, match="artifact successor bytes|recovered canonical"):
        gate.prepare_review_context(tmp_path)


def test_interrupted_post_review_pair_rolls_forward_and_cleans_journal(tmp_path: Path) -> None:
    support.materialize_complete_fixture(tmp_path)
    context = gate.prepare_review_context(tmp_path)
    attestation = gate.build_attestation(context, "2026-08-09T13:00:00+09:00")
    attestation_raw = gate.json_text(attestation).encode()
    support.write(trace.REVIEW_ATTESTATION_REL, attestation_raw, tmp_path)
    outputs = gate.build_post_review_outputs(context, attestation, attestation_raw)
    manifest, journal_raw = gate._post_review_manifest(outputs)
    rows = gate._validate_post_review_manifest(manifest, journal_raw)
    gate.artifact_builder._write_private_stage(
        tmp_path / gate.POST_REVIEW_JOURNAL_REL, journal_raw, 0o600
    )
    for relative in gate.POST_REVIEW_OUTPUT_PATHS:
        gate.artifact_builder._write_private_stage(
            tmp_path / Path(rows[relative]["stage_path"]),
            outputs[relative].encode(),
            0o600,
        )
    first = gate.POST_REVIEW_OUTPUT_PATHS[0]
    (tmp_path / Path(rows[first]["stage_path"])).replace(tmp_path / first)

    assert gate.recover_post_review_transaction(tmp_path) == "COMPLETED_POST_REVIEW"
    assert gate.write_post_review_outputs(tmp_path, outputs) == "ALREADY_CURRENT"
    assert not (tmp_path / gate.POST_REVIEW_JOURNAL_REL).exists()
    for relative in gate.POST_REVIEW_OUTPUT_PATHS:
        assert (tmp_path / relative).read_text(encoding="utf-8") == outputs[relative]
        assert not (tmp_path / Path(rows[relative]["stage_path"])).exists()
