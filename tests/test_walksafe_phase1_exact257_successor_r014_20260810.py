from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
import sys
from typing import Any

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts import build_walksafe_phase1_exact257_successor_r014_20260810 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]


def _raw(document: dict[str, Any]) -> bytes:
    return builder.trace.json_text(document).encode("utf-8")


def _authority() -> dict[str, object]:
    return {
        "goal_binding": {
            "role": "FP046_GOAL",
            "path": builder.trace.GOAL_REL.as_posix(),
            "byte_length": 1,
            "sha256": "a" * 64,
        },
        "start_gate_binding": {
            "role": "FP046_EXACT9_START_GATE",
            "path": builder.trace.START_GATE_REL.as_posix(),
            "byte_length": 1,
            "sha256": "b" * 64,
            "repository_state_path": (
                builder.trace.START_GATE_REPOSITORY_STATE_REL.as_posix()
            ),
            "repository_state_sha256": "c" * 64,
            "event_sequence": builder.trace.EXPECTED_START_EVENT_SEQUENCE,
            "event_id": builder.trace.EXPECTED_START_EVENT_ID,
        },
        "gate_ended_at": "2026-08-09T20:14:48+09:00",
    }


def _lane_raw_outputs() -> dict[str, bytes]:
    result: dict[str, bytes] = {}
    for index, lane in enumerate(builder.trace.LANES, start=1):
        started_at = f"2026-08-10T01:0{index}:00+09:00"
        ended_at = f"2026-08-10T01:0{index}:30+09:00"
        runner_summary = {
            "ANDROID_CONSENT_DELETION": (
                "JUnit tests=981 failures=0 errors=0 skipped=0"
            ),
            "GATEWAY_PRIVACY_LEDGER": (
                "Node tests=88 pass=88 fail=0; typecheck=PASS; build=PASS"
            ),
            "BACKEND_PRIVACY_POSTGRES": "57 passed in 1.00s",
            "RETENTION_BACKUP_DELETION": "92 passed in 1.00s",
        }[lane.lane_id]
        result[lane.lane_id] = (
            f"WALKSAFE_FP046_STARTED_AT {started_at}\n"
            f"WALKSAFE_FP046_COMMAND {lane.expected_command}\n"
            f"{runner_summary}\n"
            "WALKSAFE_FP046_SUMMARY "
            f"passed={lane.expected_passed} failed=0 errors=0 skipped=0\n"
            "WALKSAFE_FP046_EXIT_CODE 0\n"
            f"WALKSAFE_FP046_ENDED_AT {ended_at}\n"
        ).encode("utf-8")
    return result


def _lane_observations() -> dict[str, dict[str, object]]:
    raw_outputs = _lane_raw_outputs()
    result: dict[str, dict[str, object]] = {}
    for index, lane in enumerate(builder.trace.LANES, start=1):
        raw = raw_outputs[lane.lane_id]
        result[lane.lane_id] = {
            "schema_version": "walksafe.fp046-internal-lane-observation.v1",
            "lane_id": lane.lane_id,
            "status": "PASS",
            "command": lane.expected_command,
            "exit_code": 0,
            "started_at": f"2026-08-10T01:0{index}:00+09:00",
            "ended_at": f"2026-08-10T01:0{index}:30+09:00",
            "raw_output_sha256": builder.bytes_sha256(raw),
            "raw_output_byte_length": len(raw),
            "metrics": {
                "result_format": "INTERNAL_TEST_SUMMARY_V1",
                "passed": lane.expected_passed,
                "failed": 0,
                "errors": 0,
                "skipped": 0,
            },
            "evidence_boundary": builder.trace.lane_evidence_boundary(),
        }
    return result


def _artifact_predecessors() -> tuple[dict[Path, bytes], dict[Path, dict[str, Any]]]:
    artifact_builder = builder.artifact_builder
    raw = {
        relative: (REPO_ROOT / relative).read_bytes()
        for relative in artifact_builder.OUTPUT_PATHS
    }
    documents = {
        relative: builder.trace.strict_json_bytes(content, relative.as_posix())
        for relative, content in raw.items()
    }
    predecessor_flags = {
        relative: builder.bytes_sha256(raw[relative])
        == artifact_builder.EXPECTED_PREDECESSOR_SHA256_BY_PATH[relative]
        for relative in artifact_builder.OUTPUT_PATHS
    }
    if all(predecessor_flags.values()):
        artifact_builder.validate_predecessors(documents, raw)
        return raw, documents
    builder.require(
        not any(predecessor_flags.values()),
        "live six-artifact fixture mixes FP008 predecessor and FP046 successor",
    )
    recovered = artifact_builder.validate_successor_structure(documents, raw)
    recovered_raw = {
        relative: builder.trace.json_text(recovered[relative]).encode("utf-8")
        for relative in artifact_builder.OUTPUT_PATHS
    }
    artifact_builder.validate_predecessors(recovered, recovered_raw)
    return recovered_raw, recovered


def _sealed_artifacts() -> tuple[dict[Path, dict[str, Any]], dict[Path, bytes]]:
    seal_field_by_path = {
        builder.artifact_builder.DOC05_REL: "content_sha256",
        builder.artifact_builder.RTM_REL: "document_content_sha256",
        builder.artifact_builder.DESIGN_REL: "register_content_sha256",
        builder.artifact_builder.IMPLEMENTATION_MANIFEST_REL: "fp046_successor_content_sha256",
        builder.artifact_builder.MODULE_REGISTER_REL: "fp046_successor_content_sha256",
    }
    documents: dict[Path, dict[str, Any]] = {}
    raws: dict[Path, bytes] = {}
    for relative, seal_field in seal_field_by_path.items():
        document = builder.trace.sealed(
            {
                "document_id": f"FIXTURE-{relative.name}",
                "fp046_artifact_trace_successor": {
                    "successor_id": builder.artifact_builder.SUCCESSOR_ID,
                    "policy_id": "FP-046",
                    "gap_id": "GAP-055",
                    "gap_revision": "R025",
                },
            },
            seal_field,
        )
        documents[relative] = document
        raws[relative] = _raw(document)

    register_rows = []
    for artifact_id, relative in builder.TARGET_ARTIFACT_PATHS:
        register_rows.append(
            {
                "display_code": artifact_id.removeprefix("DLV-"),
                "fp046_internal_rebinding": {
                    "physical_path": relative.as_posix(),
                    "physical_sha256": (
                        None
                        if relative == builder.artifact_builder.DOC01_REL
                        else builder.bytes_sha256(raws[relative])
                    ),
                },
            }
        )
    register = builder.trace.sealed(
        {
            "document_id": "FIXTURE-DOC-01",
            "artifacts": register_rows,
            "fp046_artifact_trace_successor": {
                "successor_id": builder.artifact_builder.SUCCESSOR_ID,
                "policy_id": "FP-046",
                "gap_id": "GAP-055",
                "gap_revision": "R025",
            },
        },
        "content_sha256",
    )
    documents[builder.artifact_builder.DOC01_REL] = register
    raws[builder.artifact_builder.DOC01_REL] = _raw(register)
    return documents, raws


def _fixture(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    monkeypatch.setattr(builder.artifact_builder, "validate_inputs", lambda *args, **kwargs: None)
    monkeypatch.setattr(
        builder.artifact_builder,
        "validate_successor_documents",
        lambda *args, **kwargs: None,
    )
    predecessor_raw = {
        path: (REPO_ROOT / path).read_bytes() for path in builder.PREDECESSOR_PATHS
    }
    predecessors = {
        path: builder.trace.strict_json_bytes(raw, path.as_posix())
        for path, raw in predecessor_raw.items()
    }
    implementation = {"document_id": "WS-FP046-IMPLEMENTATION-FIXTURE"}
    verification = {"document_id": "WS-FP046-VERIFICATION-FIXTURE"}
    gap = {
        "document_id": "WS-GAP055-R025-FIXTURE",
        "policy_id": "FP-046",
        "gap_id": "GAP-055",
        "revision": "R025",
    }
    implementation_raw = _raw(implementation)
    verification_raw = _raw(verification)
    gap_raw = _raw(gap)
    artifacts, artifact_raw = _sealed_artifacts()
    return {
        "predecessor_raw": predecessor_raw,
        "predecessors": predecessors,
        "implementation": implementation,
        "verification": verification,
        "gap": gap,
        "implementation_raw": implementation_raw,
        "verification_raw": verification_raw,
        "gap_raw": gap_raw,
        "artifacts": artifacts,
        "artifact_raw": artifact_raw,
    }


def _build(fixture: dict[str, Any]) -> dict[Path, bytes]:
    predecessors = fixture["predecessors"]
    return builder.build_documents(
        predecessors[builder.R013_LEDGER_REL],
        predecessors[builder.R013_EVIDENCE_REL],
        predecessors[builder.R013_RECEIPT_REL],
        predecessor_raw=fixture["predecessor_raw"],
        implementation=fixture["implementation"],
        verification=fixture["verification"],
        gap=fixture["gap"],
        artifact_documents=fixture["artifacts"],
        implementation_raw=fixture["implementation_raw"],
        verification_raw=fixture["verification_raw"],
        gap_raw=fixture["gap_raw"],
        artifact_raw=fixture["artifact_raw"],
    )


def test_r014_exact6_changed_251_unchanged_and_zero_credit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture = _fixture(monkeypatch)
    first = _build(fixture)
    second = _build(fixture)
    assert first == second
    assert set(first) == set(builder.OUTPUT_PATHS)

    ledger = json.loads(first[builder.R014_LEDGER_REL])
    evidence = json.loads(first[builder.R014_EVIDENCE_REL])
    receipt = json.loads(first[builder.R014_RECEIPT_REL])
    predecessor = fixture["predecessors"][builder.R013_LEDGER_REL]
    before = {row["artifact_type_code"]: row for row in predecessor["records"]}
    after = {row["artifact_type_code"]: row for row in ledger["records"]}
    changed = [artifact_id for artifact_id in before if before[artifact_id] != after[artifact_id]]
    assert set(changed) == set(builder.TARGET_ARTIFACT_IDS)
    assert len(changed) == 6
    assert sum(before[key] == after[key] for key in before if key not in changed) == 251
    assert ledger["summaries"] == predecessor["summaries"]
    assert ledger["authorization_boundary"] == predecessor["authorization_boundary"]

    application = ledger["r014_fp046_gap055_r025_artifact_progress_application"]
    assert application["target_artifact_ids"] == list(builder.TARGET_ARTIFACT_IDS)
    assert application["formal_test_status"] == "NOT_RUN"
    assert application["actual_device_status"] == "NOT_RUN"
    assert application["external_evidence_status"] == "NOT_RUN"
    assert application["production_deployment_status"] == "NOT_RUN"
    assert application["release_status"] == "NOT_ELIGIBLE"
    assert all(value == 0 for value in application["zero_credits"].values())

    roles = {row["binding_id"]: row["subject_role"] for row in ledger["r014_source_bindings"]}
    assert roles["R014-SRC-001"] == "FP046_IMPLEMENTATION_RESULT"
    assert roles["R014-SRC-002"] == "FP046_VERIFICATION_RESULT"
    assert roles["R014-SRC-003"] == "GAP055_R025_SUCCESSOR"
    assert all(roles[f"R014-SRC-{index:03d}"].startswith("FP046_DLV-") for index in range(4, 10))

    for artifact_id in builder.TARGET_ARTIFACT_IDS:
        row = after[artifact_id]
        authored = row["progress_axes"]["content_authored"]["observations"][-1]
        materialized = row["progress_axes"]["packet_materialization"][-1]
        validated = row["progress_axes"]["internal_validation"]["observations"][-1]
        assert authored["status"].startswith("FP046_")
        assert authored["producer_result_binding_ids"]["gap055_r025_successor"] == "R014-SRC-003"
        assert materialized["status"].startswith("FP046_")
        assert validated["gap055_r025_binding_id"] == "R014-SRC-003"
        assert validated["production_deployment_status"] == "NOT_RUN"
        assert validated["credit_count"] == 0

    assert evidence["row_delta"] == {
        "record_count": 257,
        "record_order_preserved": True,
        "unchanged_record_count": 251,
        "progress_binding_record_count": 6,
        "target_artifact_ids": list(builder.TARGET_ARTIFACT_IDS),
        "other_record_delta_count": 0,
        "status_delta_count": 0,
        "credit_delta_count": 0,
    }
    assert receipt["summary"]["unchanged_record_count"] == 251
    assert receipt["summary"]["progress_binding_record_count"] == 6
    assert receipt["summary"]["status_delta_count"] == 0
    assert receipt["summary"]["credit_delta_count"] == 0
    assert receipt["summary"]["release_status"] == "NOT_ELIGIBLE"
    assert all(value == 0 for value in receipt["summary"]["zero_credits"].values())
    assert receipt["physical_output_contract"] == {
        "paths": [path.as_posix() for path in builder.OUTPUT_PATHS],
        "add_only": True,
        "overwrite_allowed": False,
        "checkpoint_or_daylog_publication": False,
    }
    for relative, raw in first.items():
        builder.verify_nonself(json.loads(raw), relative)


def test_r014_rejects_resealed_forged_r013_raw_pin(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture = _fixture(monkeypatch)
    forged = deepcopy(fixture["predecessors"][builder.R013_LEDGER_REL])
    target = next(
        row
        for row in forged["records"]
        if row["artifact_type_code"] not in builder.TARGET_ARTIFACT_ID_SET
    )
    target["forged_progress"] = True
    forged_raw = builder.seal_json(forged, builder.R013_LEDGER_REL)
    predecessor_raw = {
        **fixture["predecessor_raw"],
        builder.R013_LEDGER_REL: forged_raw,
    }
    with pytest.raises(builder.BuildError, match="canonical R013 predecessor bytes"):
        builder.validate_inputs(
            builder.trace.strict_json_bytes(forged_raw, "forged R013 ledger"),
            fixture["predecessors"][builder.R013_EVIDENCE_REL],
            fixture["predecessors"][builder.R013_RECEIPT_REL],
            fixture["implementation"],
            fixture["verification"],
            fixture["gap"],
            fixture["artifacts"],
            predecessor_raw=predecessor_raw,
        )


def test_r014_rejects_missing_fp046_artifact_marker(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture = _fixture(monkeypatch)
    relative = builder.artifact_builder.DOC05_REL
    document = deepcopy(fixture["artifacts"][relative])
    document.pop("fp046_artifact_trace_successor")
    document.pop("content_sha256")
    document = builder.trace.sealed(document, "content_sha256")
    fixture["artifacts"][relative] = document
    fixture["artifact_raw"][relative] = _raw(document)
    with pytest.raises(builder.BuildError, match="FP-046 artifact marker missing"):
        _build(fixture)


def test_r014_publication_is_add_only_in_tmp(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    outputs = _build(_fixture(monkeypatch))
    text_outputs = {path: raw.decode("utf-8") for path, raw in outputs.items()}
    builder.trace.write_or_check_outputs(tmp_path, text_outputs, write=True)
    builder.trace.write_or_check_outputs(tmp_path, text_outputs, write=True)
    builder.trace.write_or_check_outputs(tmp_path, text_outputs, write=False)

    changed = dict(text_outputs)
    changed[builder.R014_EVIDENCE_REL] = "{}\n"
    with pytest.raises(builder.BuildError, match="file authority differs"):
        builder.trace.write_or_check_outputs(tmp_path, changed, write=True)


def test_r014_integrates_actual_fp046_r025_and_six_artifact_successors(
    tmp_path: Path,
) -> None:
    source_paths = (
        ("ANDROID", "apps/android/app/fp046-fixture.kt"),
        ("GATEWAY", "apps/android-gateway/fp046-fixture.ts"),
        ("BACKEND", "backend/fp046_fixture.py"),
        ("TOOLING", "product/fp046-trace-fixture.py"),
    )
    for index, (_, name) in enumerate(source_paths, start=1):
        target = tmp_path / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(f"fp046-final-content-{index}\n".encode("utf-8"))
    groups = tuple(
        builder.trace.SourceGroup(group_id, (name,))
        for group_id, name in source_paths
    )
    producer_outputs = builder.trace.build_pre_review_outputs(
        root=tmp_path,
        lane_observations=_lane_observations(),
        lane_raw_outputs=_lane_raw_outputs(),
        source_groups=groups,
        authority=_authority(),
    )
    implementation_raw = producer_outputs[builder.trace.IMPLEMENTATION_REL].encode(
        "utf-8"
    )
    verification_raw = producer_outputs[builder.trace.VERIFICATION_REL].encode(
        "utf-8"
    )
    implementation = builder.trace.strict_json_bytes(
        implementation_raw, "FP046 implementation"
    )
    verification = builder.trace.strict_json_bytes(
        verification_raw, "FP046 verification"
    )
    lane_receipt_raw_by_id = {
        lane.lane_id: producer_outputs[lane.receipt_rel].encode("utf-8")
        for lane in builder.trace.LANES
    }
    lane_log_raw_by_id = {
        lane.lane_id: producer_outputs[lane.log_rel].encode("utf-8")
        for lane in builder.trace.LANES
    }

    gap_predecessor_raw = (REPO_ROOT / builder.gap_builder.R024_GAP_REL).read_bytes()
    backlog_predecessor_raw = (
        REPO_ROOT / builder.gap_builder.R024_BACKLOG_REL
    ).read_bytes()
    r025 = builder.gap_builder.build_documents(
        builder.trace.strict_json_bytes(gap_predecessor_raw, "R024 gap"),
        builder.trace.strict_json_bytes(backlog_predecessor_raw, "R024 backlog"),
        implementation,
        verification,
        predecessor_gap_raw=gap_predecessor_raw,
        predecessor_backlog_raw=backlog_predecessor_raw,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        lane_receipt_raw_by_id=lane_receipt_raw_by_id,
        lane_log_raw_by_id=lane_log_raw_by_id,
        root=tmp_path,
        source_groups=groups,
        authority=_authority(),
    )
    gap_raw = r025[builder.gap_builder.R025_GAP_JSON_REL].encode("utf-8")
    gap = builder.trace.strict_json_bytes(gap_raw, "R025 gap")

    artifact_predecessor_raw, artifact_predecessors = _artifact_predecessors()
    artifact_text = builder.artifact_builder.build_documents(
        artifact_predecessors,
        artifact_predecessor_raw,
        implementation,
        verification,
        gap,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
    )
    artifact_raw = {
        path: artifact_text[path].encode("utf-8")
        for _, path in builder.TARGET_ARTIFACT_PATHS
    }
    artifact_documents = {
        path: builder.trace.strict_json_bytes(raw, path.as_posix())
        for path, raw in artifact_raw.items()
    }
    predecessor_raw = {
        path: (REPO_ROOT / path).read_bytes() for path in builder.PREDECESSOR_PATHS
    }
    predecessors = {
        path: builder.trace.strict_json_bytes(raw, path.as_posix())
        for path, raw in predecessor_raw.items()
    }
    outputs = builder.build_documents(
        predecessors[builder.R013_LEDGER_REL],
        predecessors[builder.R013_EVIDENCE_REL],
        predecessors[builder.R013_RECEIPT_REL],
        predecessor_raw=predecessor_raw,
        implementation=implementation,
        verification=verification,
        gap=gap,
        artifact_documents=artifact_documents,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
        artifact_raw=artifact_raw,
    )
    ledger = json.loads(outputs[builder.R014_LEDGER_REL])
    assert ledger["ledger_id"] == "WS-PHASE1-EXACT257-SUCCESSOR-LEDGER-20260810-R014"
    assert (
        ledger["r014_source_bindings"][2]["subject_role"]
        == "GAP055_R025_SUCCESSOR"
    )
