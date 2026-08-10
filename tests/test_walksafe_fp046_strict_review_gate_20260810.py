from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path

import pytest

from scripts import build_walksafe_fp046_strict_review_gate_20260810 as gate


trace = gate.trace
REVIEWED_AT = "2026-08-10T13:00:00+09:00"


def review_context() -> gate.ReviewContext:
    consumers = tuple(
        {
            "role": role,
            "path": relative.as_posix(),
            "schema_version": "1.0",
            "sha256": f"{index:064x}",
        }
        for index, (role, relative) in enumerate(gate._consumer_specs(), start=1)
    )
    return gate.ReviewContext(
        result_raw={trace.REVIEW_SUBJECT_REL: b"review-subject-final-bytes"},
        result_documents={},
        consumer_bindings=consumers,
        evidence_manifest=(
            {"path": trace.REVIEW_SUBJECT_REL.as_posix(), "sha256": "a" * 64},
        ),
        review_subject_sha256="b" * 64,
        reviewed_result_sha256_by_kind={
            "IMPLEMENTATION_RECORD": "c" * 64,
            "VERIFICATION_RESULT": "d" * 64,
            "SUCCESSOR_TRACE": "e" * 64,
        },
        implementation_started_at="2026-08-10T11:00:00+09:00",
        implementation_ended_at="2026-08-10T12:00:00+09:00",
    )


def test_strict_equal_ignores_dict_order_but_preserves_exact_shape_and_list_order() -> None:
    expected = {
        "first": {"alpha": 1, "beta": ["x", "y"]},
        "second": False,
    }
    reordered = {
        "second": False,
        "first": {"beta": ["x", "y"], "alpha": 1},
    }
    gate._strict_equal(reordered, expected, label="reordered canonical JSON")

    with pytest.raises(trace.BuildError, match="fields differ"):
        gate._strict_equal(
            {"second": False, "first": {"alpha": 1}},
            expected,
            label="missing nested key",
        )
    with pytest.raises(trace.BuildError, match="value differs"):
        gate._strict_equal(
            {"first": {"alpha": 1, "beta": ["y", "x"]}, "second": False},
            expected,
            label="reordered list",
        )
    with pytest.raises(trace.BuildError, match="type differs"):
        gate._strict_equal(
            {"first": {"alpha": True, "beta": ["x", "y"]}, "second": False},
            expected,
            label="bool is not int",
        )


def test_prepare_review_uses_checked_live_six_successors(monkeypatch: pytest.MonkeyPatch) -> None:
    checked = 0
    original = gate.artifact_builder.check_successor

    def check_successor(root: Path) -> dict[Path, str]:
        nonlocal checked
        checked += 1
        return original(root)

    def forbidden_build_outputs(_root: Path) -> dict[Path, str]:
        raise AssertionError("post-publication review must not rebuild FP008 predecessors")

    monkeypatch.setattr(gate.artifact_builder, "check_successor", check_successor)
    monkeypatch.setattr(
        gate.artifact_builder,
        "build_outputs",
        forbidden_build_outputs,
    )
    context = gate.prepare_review_context(gate.ROOT)
    assert checked == 1
    assert len(context.consumer_bindings) == 11

    def drifted_successor(root: Path) -> dict[Path, str]:
        outputs = original(root)
        first = gate.artifact_builder.OUTPUT_PATHS[0]
        outputs[first] += " "
        return outputs

    monkeypatch.setattr(
        gate.artifact_builder,
        "check_successor",
        drifted_successor,
    )
    with pytest.raises(trace.BuildError, match="six-artifact successor full producer rebuild"):
        gate.prepare_review_context(gate.ROOT)


def test_internal_review_binds_final_r025_six_deliverables_r014_and_attestation() -> None:
    context = review_context()
    assert len(context.consumer_bindings) == 11
    assert [row[0] for row in gate._consumer_specs()] == [
        "GAP_R025",
        "BACKLOG_R025",
        "ARTIFACT_CHANGE_LOG",
        "ARTIFACT_REGISTER",
        "REQUIREMENTS_TRACEABILITY",
        "DESIGN_TRACEABILITY",
        "IMPLEMENTATION_MANIFEST",
        "MODULE_REGISTER",
        "EXACT257_R014_LEDGER",
        "EXACT257_R014_EVIDENCE",
        "EXACT257_R014_CHECK_RECEIPT",
    ]

    attestation = gate.build_attestation(context, REVIEWED_AT)
    attestation_raw = trace.json_text(attestation).encode("utf-8")
    outputs = gate.build_post_review_outputs(context, attestation, attestation_raw)
    review = json.loads(outputs[gate.INDEPENDENT_REVIEW_REL])
    completion = json.loads(outputs[gate.COMPLETION_RECEIPT_REL])

    expected_attestation_hash = trace.bytes_sha256(attestation_raw)
    assert review["status"] == "PASS"
    assert review["review_subject_sha256"] == context.review_subject_sha256
    assert review["reviewed_consumer_bindings"] == list(context.consumer_bindings)
    assert review["attestation_provenance"] == {
        "path": gate.REVIEW_ATTESTATION_REL.as_posix(),
        "sha256": expected_attestation_hash,
    }
    assert completion["status"] == "ACCEPTED"
    assert completion["result"] == "PASS"
    assert completion["review_attestation_provenance"] == review["attestation_provenance"]
    assert completion["downstream_consumer_bindings"] == list(context.consumer_bindings)
    assert completion["execution_start_event"] == gate.EXPECTED_START_EVENT
    assert completion["implementation_start_gate_binding"] == gate.EXPECTED_START_GATE

    for boundary in (review["review_boundary"], completion["completion_boundary"]):
        assert boundary["formal_test_status"] == "NOT_RUN"
        assert boundary["actual_device_status"] == "NOT_RUN"
        assert boundary["external_legal_review_status"] == "NOT_RUN"
        assert boundary["external_privacy_review_status"] == "NOT_RUN"
        assert boundary["external_processor_status"] == "NOT_RUN"
        assert boundary["production_deployment_status"] == "NOT_RUN"
        assert boundary["release_status"] == "NOT_ELIGIBLE"
        assert boundary["release_gates_waived"] is False


def test_attestation_is_exact_and_fail_closed_for_executor_time_and_hash_tamper() -> None:
    context = review_context()
    with pytest.raises(trace.BuildError, match="second precision"):
        gate.build_attestation(context, "2026-08-10T13:00:00.000001+09:00")
    with pytest.raises(trace.BuildError, match="executor"):
        gate.build_attestation(
            context,
            REVIEWED_AT,
            reviewer_id=gate.EXECUTOR_ID,
            reviewer_task=gate.EXECUTOR_TASK,
        )

    attestation = gate.build_attestation(context, REVIEWED_AT)
    tampered = deepcopy(attestation)
    tampered["review_subject_sha256"] = "f" * 64
    with pytest.raises(trace.BuildError, match="review attestation"):
        gate.validate_attestation(tampered, context)

    raw = trace.json_text(attestation).encode("utf-8") + b"\n"
    with pytest.raises(trace.BuildError, match="noncanonical"):
        gate.build_post_review_outputs(context, attestation, raw)


def test_final_producer_rebuild_comparison_rejects_byte_drift(tmp_path: Path) -> None:
    relative = Path("final/r025.json")
    target = tmp_path / relative
    target.parent.mkdir(parents=True)
    target.write_bytes(b'{"final":true}\n')
    expected = {relative: '{"final":true}\n'}
    gate._assert_exact_producer_outputs(
        tmp_path,
        expected,
        (relative,),
        label="synthetic final",
    )
    target.write_bytes(b'{"final":false}\n')
    with pytest.raises(trace.BuildError, match="full producer rebuild"):
        gate._assert_exact_producer_outputs(
            tmp_path,
            expected,
            (relative,),
            label="synthetic final",
        )


def test_result_inventory_rejects_partial_review_and_extra_files(
    tmp_path: Path,
) -> None:
    result_dir = tmp_path / trace.RESULT_DIR_REL
    for relative in (
        *gate._result_paths(),
        *(lane.log_rel for lane in trace.LANES),
    ):
        destination = tmp_path / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(b"{}\n")
    gate._validate_exact_result_inventory(tmp_path)

    (tmp_path / gate.REVIEW_ATTESTATION_REL).write_bytes(b"{}\n")
    (tmp_path / gate.INDEPENDENT_REVIEW_REL).write_bytes(b"{}\n")
    with pytest.raises(trace.BuildError, match="inventory"):
        gate._validate_exact_result_inventory(tmp_path)

    (tmp_path / gate.INDEPENDENT_REVIEW_REL).unlink()
    (tmp_path / gate.REVIEW_ATTESTATION_REL).unlink()
    (result_dir / "unexpected.json").write_bytes(b"{}\n")
    with pytest.raises(trace.BuildError, match="inventory"):
        gate._validate_exact_result_inventory(tmp_path)


def test_post_review_transaction_round_trip_and_interrupted_roll_forward(
    tmp_path: Path,
) -> None:
    result_dir = tmp_path / trace.RESULT_DIR_REL
    result_dir.mkdir(parents=True)
    result_dir.chmod(0o700)
    context = review_context()
    attestation = gate.build_attestation(context, REVIEWED_AT)
    raw = trace.json_text(attestation).encode("utf-8")
    outputs = gate.build_post_review_outputs(context, attestation, raw)

    assert gate.write_post_review_outputs(tmp_path, outputs) == "PUBLISHED_POST_REVIEW"
    assert gate.write_post_review_outputs(tmp_path, outputs) == "ALREADY_CURRENT"
    for relative, expected in outputs.items():
        assert (tmp_path / relative).read_text(encoding="utf-8") == expected

    second_root = tmp_path / "interrupted"
    second_result = second_root / trace.RESULT_DIR_REL
    second_result.mkdir(parents=True)
    second_result.chmod(0o700)
    manifest, journal_raw = gate._post_review_manifest(outputs)
    rows = gate._validate_post_review_manifest(manifest, journal_raw)
    gate.transaction_io._write_private_stage(
        second_root / gate.POST_REVIEW_JOURNAL_REL,
        journal_raw,
        0o600,
    )
    for relative in gate.POST_REVIEW_OUTPUT_PATHS:
        gate.transaction_io._write_private_stage(
            second_root / Path(rows[relative]["stage_path"]),
            outputs[relative].encode("utf-8"),
            0o600,
        )
    first = gate.POST_REVIEW_OUTPUT_PATHS[0]
    (second_root / Path(rows[first]["stage_path"])).replace(second_root / first)

    assert gate.recover_post_review_transaction(second_root) == "COMPLETED_POST_REVIEW"
    assert not (second_root / gate.POST_REVIEW_JOURNAL_REL).exists()
    for relative, expected in outputs.items():
        assert (second_root / relative).read_text(encoding="utf-8") == expected
        assert not (second_root / Path(rows[relative]["stage_path"])).exists()


def test_post_review_manifest_rejects_extra_fields() -> None:
    context = review_context()
    attestation = gate.build_attestation(context, REVIEWED_AT)
    raw = trace.json_text(attestation).encode("utf-8")
    outputs = gate.build_post_review_outputs(context, attestation, raw)
    manifest, _journal_raw = gate._post_review_manifest(outputs)
    manifest["outputs"][0]["unexpected"] = True
    manifest.pop("transaction_content_sha256")
    manifest = trace.sealed(manifest, "transaction_content_sha256")
    tampered_raw = trace.json_text(manifest).encode("utf-8")
    with pytest.raises(trace.BuildError, match="transaction binding"):
        gate._validate_post_review_manifest(manifest, tampered_raw)
