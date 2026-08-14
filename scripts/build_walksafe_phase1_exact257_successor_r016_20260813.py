#!/usr/bin/env python3
"""Build the add-only exact257 R016 NPC v2 correction successor packet."""

from __future__ import annotations

import argparse
from copy import deepcopy
from pathlib import Path
from typing import Any, Mapping, Sequence

from scripts import build_walksafe_npc_single_admin_recovery_artifact_trace_correction_v2_20260813 as artifact_builder
from scripts import build_walksafe_npc_single_admin_recovery_gap_backlog_r027_20260813 as gap_builder
from scripts import build_walksafe_npc_single_admin_recovery_trace_20260812 as trace
from scripts import build_walksafe_phase1_exact257_successor_r015_20260812 as r015_builder


ROOT = Path(__file__).resolve().parents[1]
RUN_DIR_REL = Path("docs/control/execution/artifact-closure/run-20260727-001")
R015_DIR_REL = RUN_DIR_REL / "packets/phase1-exact257-successor-r015"
R015_LEDGER_REL = R015_DIR_REL / "phase1-exact257-successor-ledger-r015.json"
R015_EVIDENCE_REL = R015_DIR_REL / "evidence.json"
R015_RECEIPT_REL = R015_DIR_REL / "phase1-exact257-successor-check-receipt-r015.json"
R016_DIR_REL = RUN_DIR_REL / "packets/phase1-exact257-successor-r016"
R016_LEDGER_REL = R016_DIR_REL / "phase1-exact257-successor-ledger-r016.json"
R016_EVIDENCE_REL = R016_DIR_REL / "evidence.json"
R016_RECEIPT_REL = R016_DIR_REL / "phase1-exact257-successor-check-receipt-r016.json"
PREDECESSOR_PATHS = (R015_LEDGER_REL, R015_EVIDENCE_REL, R015_RECEIPT_REL)
OUTPUT_PATHS = (R016_LEDGER_REL, R016_EVIDENCE_REL, R016_RECEIPT_REL)
EXPECTED_R015_SHA256_BY_PATH = {
    R015_LEDGER_REL: "dfb74531e1922b639667645ab9949fa754a3b93474215f65b93481c8c939f4e9",
    R015_EVIDENCE_REL: "e4d5134703909d91b9b043626903cd5f5601043d8d43f3cf2b02f78aa2c52104",
    R015_RECEIPT_REL: "2e693d3ee0d5259b2542734444af7b19ec94ea230899ffaf7ceb50919137ce04",
}
TARGET_ARTIFACT_PATHS = (
    ("DLV-DOC-05", artifact_builder.DOC05_REL),
    ("DLV-DOC-01", artifact_builder.DOC01_REL),
    ("DLV-REQ-16", artifact_builder.RTM_REL),
    ("DLV-DES-06", artifact_builder.DESIGN_REL),
    ("DLV-DEV-01", artifact_builder.IMPLEMENTATION_MANIFEST_REL),
    ("DLV-DEV-18", artifact_builder.MODULE_REGISTER_REL),
)
TARGET_ARTIFACT_IDS = tuple(artifact_id for artifact_id, _ in TARGET_ARTIFACT_PATHS)
TARGET_ARTIFACT_ID_SET = frozenset(TARGET_ARTIFACT_IDS)
ARTIFACT_BINDING_ID_BY_ID = {
    artifact_id: f"R016-SRC-{index:03d}"
    for index, (artifact_id, _) in enumerate(TARGET_ARTIFACT_PATHS, start=4)
}
PREPARED_ON = "2026-08-13"
VERDICT = (
    "PASS_FOR_NPC_SINGLE_ADMIN_RECOVERY_GAP008_R027_EXACT6_V2_CORRECTION_"
    "PROGRESS_BINDING_WITH_ZERO_CREDIT_ONLY"
)
ZERO_CREDITS = {
    "acceptance_count": 0,
    "actual_device_event_count": 0,
    "attestation_approval_count": 0,
    "execution_count": 0,
    "formal279_pass_count": 0,
    "formal_evidence_count": 0,
    "in_scope_substantive_credit_count": 0,
    "owner_approval_count": 0,
    "real_event_count": 0,
    "release_eligible_count": 0,
    "verified_rights_or_external_fact_count": 0,
}

BuildError = trace.BuildError
require = trace.require
bytes_sha256 = trace.bytes_sha256


def _binding(binding_id: str, path: Path, raw: bytes, role: str) -> dict[str, Any]:
    return {
        "binding_id": binding_id,
        "path": path.as_posix(),
        "byte_length": len(raw),
        "sha256": bytes_sha256(raw),
        "subject_role": role,
    }


def _record_map(ledger: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    records = ledger.get("records")
    require(type(records) is list and len(records) == 257, "exact257 record count differs")
    by_id = {
        row.get("artifact_type_code"): row
        for row in records
        if type(row) is dict
    }
    require(len(by_id) == 257 and None not in by_id, "exact257 record IDs differ")
    return by_id


def validate_predecessor_packet(
    documents: Mapping[Path, Mapping[str, Any]],
    raw_by_path: Mapping[Path, bytes],
) -> None:
    require(
        set(documents) == set(raw_by_path) == set(PREDECESSOR_PATHS),
        "R015 predecessor inventory differs",
    )
    for path in PREDECESSOR_PATHS:
        require(
            bytes_sha256(raw_by_path[path]) == EXPECTED_R015_SHA256_BY_PATH[path],
            f"R015 predecessor SHA-256 differs: {path}",
        )
        require(
            raw_by_path[path] == trace.json_text(documents[path]).encode("utf-8"),
            f"R015 predecessor JSON is noncanonical: {path}",
        )
        r015_builder.r014_builder.verify_nonself(documents[path], path)
    ledger = documents[R015_LEDGER_REL]
    receipt = documents[R015_RECEIPT_REL]
    require(
        ledger.get("schema_version") == "walksafe.phase1-exact257-successor-ledger.v15"
        and ledger.get("ledger_id")
        == "WS-PHASE1-EXACT257-SUCCESSOR-LEDGER-20260812-R015",
        "R015 ledger identity differs",
    )
    require(
        receipt.get("status") == "PASS"
        and receipt.get("summary", {}).get("record_count") == 257
        and receipt.get("summary", {}).get("unchanged_record_count") == 251
        and receipt.get("summary", {}).get("progress_binding_record_count") == 6
        and receipt.get("summary", {}).get("status_delta_count") == 0
        and receipt.get("summary", {}).get("credit_delta_count") == 0,
        "R015 exact251+6 zero-delta receipt differs",
    )
    _record_map(ledger)


def _require_r027_rebuild_exact(
    gap: Mapping[str, Any],
    gap_raw: bytes,
    rebuilt_outputs: Mapping[Path, str],
) -> None:
    require(
        set(rebuilt_outputs) == set(gap_builder.OUTPUT_PATHS),
        "R027 deep rebuild output inventory differs",
    )
    expected = rebuilt_outputs.get(gap_builder.R027_GAP_JSON_REL)
    require(type(expected) is str, "R027 deep rebuild gap output missing")
    require(
        gap_raw == expected.encode("utf-8"),
        "R027 source does not match the public deep rebuild exact bytes",
    )
    require(
        gap_raw == trace.json_text(gap).encode("utf-8"),
        "R027 source JSON is noncanonical",
    )
    trace.verify_seal(gap, "report_content_sha256", "R027 gap")
    metadata = gap.get("metadata")
    assessments = gap.get("assessments")
    summary = gap.get("summary")
    require(
        type(metadata) is dict
        and metadata.get("report_id")
        == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260813-027"
        and type(assessments) is list
        and type(summary) is dict,
        "R027 identity or structure differs",
    )
    gap008 = [row for row in assessments if row.get("gap_id") == trace.GAP_ID]
    gap068 = [row for row in assessments if row.get("gap_id") == "GAP-068"]
    require(
        len(gap008) == 1
        and gap008[0].get("status") == "PARTIAL"
        and gap008[0].get("formal_test_status") == "NOT_RUN"
        and len(gap068) == 1
        and gap068[0].get("status") == "BLOCKED"
        and gap068[0].get("formal_test_status") == "NOT_RUN"
        and summary.get("implemented_and_formally_verified_count") == 0
        and summary.get("release_status") == "NOT_ELIGIBLE",
        "R027 status, drill, formal, or release boundary differs",
    )


def _require_exact_six_deep_validation_exact(
    documents: Mapping[Path, Mapping[str, Any]],
    raw_by_path: Mapping[Path, bytes],
    validated_outputs: Mapping[Path, str],
) -> None:
    expected_paths = {path for _, path in TARGET_ARTIFACT_PATHS}
    require(
        set(documents) == set(raw_by_path) == set(validated_outputs) == expected_paths,
        "exact-six v2 deep validation inventory differs",
    )
    for _, path in TARGET_ARTIFACT_PATHS:
        expected = validated_outputs[path]
        require(type(expected) is str, f"exact-six v2 validated output differs: {path}")
        require(
            raw_by_path[path] == expected.encode("utf-8"),
            f"exact-six v2 source does not match deep-validated exact bytes: {path}",
        )
    artifact_builder.validate_correction_documents(documents, raw_by_path)
    for _, path in TARGET_ARTIFACT_PATHS:
        marker = documents[path].get(artifact_builder.MARKER_FIELD)
        require(
            type(marker) is dict
            and marker.get("successor_id") == artifact_builder.SUCCESSOR_ID
            and marker.get("physical_path") == path.as_posix()
            and marker.get("semantic_change") == "V2_CORRECTION_PROGRESS_BINDING_ONLY"
            and marker.get("trace_boundary") == artifact_builder.boundary(),
            f"exact-six v2 correction marker differs: {path}",
        )
        bindings = marker.get("input_bindings")
        require(
            type(bindings) is list
            and any(row.get("path") == gap_builder.R027_GAP_JSON_REL.as_posix() for row in bindings),
            f"exact-six v2 marker does not bind R027: {path}",
        )


def validate_sources(
    root: Path,
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
    artifact_documents: Mapping[Path, Mapping[str, Any]],
    *,
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
    artifact_raw: Mapping[Path, bytes],
    rebuilt_r027_outputs: Mapping[Path, str] | None = None,
    validated_artifact_outputs: Mapping[Path, str] | None = None,
) -> None:
    require(
        implementation_raw == trace.json_text(implementation).encode("utf-8")
        and verification_raw == trace.json_text(verification).encode("utf-8"),
        "v2 producer JSON is noncanonical",
    )
    trace.verify_seal(
        implementation, "implementation_record_content_sha256", "v2 implementation"
    )
    trace.verify_seal(
        verification, "verification_result_content_sha256", "v2 verification"
    )
    require(
        implementation.get("evidence_schema") == "V2_CORRECTION_ONLY"
        and implementation.get("goal_id") == trace.GOAL_ID
        and implementation.get("status") == "PASS_INTERNAL"
        and implementation.get("completion_boundary") == trace.completion_boundary()
        and verification.get("evidence_schema") == "V2_CORRECTION_ONLY"
        and verification.get("goal_id") == trace.GOAL_ID
        and verification.get("status") == "PASS_INTERNAL"
        and verification.get("implementation_record_sha256")
        == bytes_sha256(implementation_raw)
        and verification.get("completion_boundary") == trace.completion_boundary(),
        "v2 producer identity, status, or boundary differs",
    )
    if rebuilt_r027_outputs is None:
        rebuilt_r027_outputs = gap_builder.build_outputs(root)
    _require_r027_rebuild_exact(gap, gap_raw, rebuilt_r027_outputs)
    if validated_artifact_outputs is None:
        validated_artifact_outputs = artifact_builder.check_successor(root)
    _require_exact_six_deep_validation_exact(
        artifact_documents,
        artifact_raw,
        validated_artifact_outputs,
    )


def _source_bindings(
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
    artifact_raw: Mapping[Path, bytes],
) -> list[dict[str, Any]]:
    bindings = [
        _binding(
            "R016-SRC-001",
            trace.V2_IMPLEMENTATION_REL,
            implementation_raw,
            "NPC_V2_CORRECTION_IMPLEMENTATION_RESULT",
        ),
        _binding(
            "R016-SRC-002",
            trace.V2_VERIFICATION_REL,
            verification_raw,
            "NPC_V2_CORRECTION_VERIFICATION_RESULT",
        ),
        _binding(
            "R016-SRC-003",
            gap_builder.R027_GAP_JSON_REL,
            gap_raw,
            "GAP008_R027_V2_CORRECTION_SUCCESSOR",
        ),
    ]
    for index, (artifact_id, path) in enumerate(TARGET_ARTIFACT_PATHS, start=4):
        bindings.append(
            _binding(
                f"R016-SRC-{index:03d}",
                path,
                artifact_raw[path],
                f"NPC_{artifact_id}_V2_CORRECTION_PHYSICAL_SUCCESSOR",
            )
        )
    require(len(bindings) == 9, "R016 source binding count differs")
    return bindings


def _progress_entries(
    artifact_id: str,
    source_by_id: Mapping[str, Mapping[str, Any]],
) -> dict[str, dict[str, Any]]:
    binding_id = ARTIFACT_BINDING_ID_BY_ID[artifact_id]
    physical = source_by_id[binding_id]
    physical_binding = {
        key: physical[key]
        for key in ("binding_id", "path", "byte_length", "sha256")
    }
    return {
        "content_authored": {
            "status": "NPC_V2_CORRECTION_PHYSICAL_DOCUMENT_PROGRESS_BOUND_NO_STATE_PROMOTION",
            "artifact_type_code": artifact_id,
            "evidence_schema": "V2_CORRECTION_ONLY",
            "physical_document_binding": physical_binding,
            "producer_result_binding_ids": {
                "implementation_record_v2": "R016-SRC-001",
                "verification_result_v2": "R016-SRC-002",
                "gap008_r027_successor": "R016-SRC-003",
            },
            "artifact_content_accepted": False,
            "completion_claimed": False,
            "owner_approved": False,
            "state_promotion": False,
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "actual_recovery_drill_status": "NOT_RUN",
            "external_evidence_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
        },
        "internal_validation": {
            "status": "NPC_V2_CORRECTION_INTERNAL_VERIFICATION_OBSERVATION_BOUND_NO_CREDIT",
            "artifact_type_code": artifact_id,
            "evidence_schema": "V2_CORRECTION_ONLY",
            "implementation_binding_id": "R016-SRC-001",
            "verification_binding_id": "R016-SRC-002",
            "gap008_r027_binding_id": "R016-SRC-003",
            "physical_document_binding_id": binding_id,
            "internal_verification_pass_observed": True,
            "credit_count": 0,
            "completion_claimed": False,
            "state_promotion": False,
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "actual_recovery_drill_status": "NOT_RUN",
            "external_evidence_status": "NOT_RUN",
            "production_deployment_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
        },
        "packet_materialization": {
            "status": "NPC_V2_CORRECTION_PHYSICAL_DOCUMENT_MATERIALIZED_PROGRESS_ONLY",
            "artifact_type_code": artifact_id,
            "evidence_schema": "V2_CORRECTION_ONLY",
            "coverage_basis": "EXACT_ARTIFACT_ID_TO_V2_CORRECTED_PHYSICAL_DOCUMENT_BINDING",
            "physical_document_binding": physical_binding,
            "completion_claimed": False,
            "state_promotion": False,
        },
    }


def _verify_record_delta(
    predecessor: Mapping[str, Any],
    successor: Mapping[str, Any],
    source_bindings: Sequence[Mapping[str, Any]],
) -> None:
    before = _record_map(predecessor)
    after = _record_map(successor)
    require(list(before) == list(after), "R016 record order differs")
    source_by_id = {row["binding_id"]: row for row in source_bindings}
    unchanged = changed = 0
    for artifact_id, before_row in before.items():
        after_row = after[artifact_id]
        if artifact_id not in TARGET_ARTIFACT_ID_SET:
            require(after_row == before_row, f"non-target R016 record changed: {artifact_id}")
            unchanged += 1
            continue
        expected = deepcopy(before_row)
        entries = _progress_entries(artifact_id, source_by_id)
        expected["progress_axes"]["content_authored"]["observations"].append(
            entries["content_authored"]
        )
        expected["progress_axes"]["internal_validation"]["observations"].append(
            entries["internal_validation"]
        )
        expected["progress_axes"]["packet_materialization"].append(
            entries["packet_materialization"]
        )
        require(after_row == expected, f"R016 target has a non-progress delta: {artifact_id}")
        require(
            after_row["queue_route"] == before_row["queue_route"]
            and after_row["artifact_closure"] == before_row["artifact_closure"]
            and after_row["claim_boundary"] == before_row["claim_boundary"]
            and after_row["release_eligibility"] == before_row["release_eligibility"],
            f"R016 target status or credit boundary changed: {artifact_id}",
        )
        changed += 1
    require((unchanged, changed) == (251, 6), "R016 exact 251/6 record split differs")
    require(successor["summaries"] == predecessor["summaries"], "R016 summaries changed")
    require(
        successor["authorization_boundary"] == predecessor["authorization_boundary"],
        "R016 authorization boundary changed",
    )


def build_ledger(
    predecessor: Mapping[str, Any],
    predecessor_bindings: Sequence[Mapping[str, Any]],
    source_bindings: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    ledger = deepcopy(dict(predecessor))
    ledger.pop("integrity", None)
    ledger.pop("nonself_digest_check", None)
    ledger["schema_version"] = "walksafe.phase1-exact257-successor-ledger.v16"
    ledger["ledger_id"] = "WS-PHASE1-EXACT257-SUCCESSOR-LEDGER-20260813-R016"
    ledger["prepared_on"] = PREPARED_ON
    ledger["r016_predecessor_packet_bindings"] = deepcopy(list(predecessor_bindings))
    ledger["r016_source_bindings"] = deepcopy(list(source_bindings))
    ledger["r016_npc_single_admin_recovery_gap008_r027_exact6_v2_correction_application"] = {
        "application_id": "WS-PHASE1-EXACT257-NPC-GAP008-R027-V2-CORRECTION-20260813-R016",
        "verdict": VERDICT,
        "record_count": 257,
        "record_order_preserved": True,
        "unchanged_record_count": 251,
        "progress_binding_record_count": 6,
        "target_artifact_ids": list(TARGET_ARTIFACT_IDS),
        "evidence_schema": "V2_CORRECTION_ONLY",
        "summary_delta_count": 0,
        "queue_route_delta_count": 0,
        "authorization_delta_count": 0,
        "status_delta_count": 0,
        "credit_delta_count": 0,
        "formal_test_ids": ["TC-NPC-SINGLE-ADMIN-RECOVERY-01"],
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "actual_recovery_drill_status": "NOT_RUN",
        "external_evidence_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
        "zero_credits": deepcopy(ZERO_CREDITS),
    }
    ledger.setdefault("exact_set_fingerprints", {})[
        "r016_npc_gap008_r027_v2_correction_exact6_set"
    ] = {
        "count": 6,
        "ordered_ids": list(TARGET_ARTIFACT_IDS),
        "sha256": trace.object_sha256(list(TARGET_ARTIFACT_IDS)),
    }
    source_by_id = {row["binding_id"]: row for row in source_bindings}
    for row in ledger["records"]:
        artifact_id = row["artifact_type_code"]
        if artifact_id not in TARGET_ARTIFACT_ID_SET:
            continue
        entries = _progress_entries(artifact_id, source_by_id)
        row["progress_axes"]["content_authored"]["observations"].append(
            entries["content_authored"]
        )
        row["progress_axes"]["internal_validation"]["observations"].append(
            entries["internal_validation"]
        )
        row["progress_axes"]["packet_materialization"].append(
            entries["packet_materialization"]
        )
    _verify_record_delta(predecessor, ledger, source_bindings)
    return ledger


def build_documents(
    predecessor_documents: Mapping[Path, Mapping[str, Any]],
    predecessor_raw: Mapping[Path, bytes],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
    artifact_documents: Mapping[Path, Mapping[str, Any]],
    *,
    root: Path,
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
    artifact_raw: Mapping[Path, bytes],
    rebuilt_r027_outputs: Mapping[Path, str] | None = None,
    validated_artifact_outputs: Mapping[Path, str] | None = None,
) -> dict[Path, bytes]:
    validate_predecessor_packet(predecessor_documents, predecessor_raw)
    validate_sources(
        root,
        implementation,
        verification,
        gap,
        artifact_documents,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
        artifact_raw=artifact_raw,
        rebuilt_r027_outputs=rebuilt_r027_outputs,
        validated_artifact_outputs=validated_artifact_outputs,
    )
    predecessor_bindings = [
        _binding(f"R016-PRE-{index:03d}", path, predecessor_raw[path], f"R015_{role}")
        for index, (path, role) in enumerate(
            zip(PREDECESSOR_PATHS, ("LEDGER", "EVIDENCE", "RECEIPT"), strict=True),
            start=1,
        )
    ]
    source_bindings = _source_bindings(
        implementation_raw,
        verification_raw,
        gap_raw,
        artifact_raw,
    )
    ledger = build_ledger(
        predecessor_documents[R015_LEDGER_REL],
        predecessor_bindings,
        source_bindings,
    )
    ledger_raw = r015_builder.r014_builder.seal_json(ledger, R016_LEDGER_REL)
    evidence = {
        "schema_version": "walksafe.phase1-exact257-successor-evidence.v16",
        "packet_id": "WS-PHASE1-EXACT257-SUCCESSOR-EVIDENCE-20260813-R016",
        "prepared_on": PREPARED_ON,
        "run_id": "WS-ARTIFACT-CLOSURE-RUN-20260727-001",
        "verdict": VERDICT,
        "claim_semantics": "NPC_GAP008_R027_EXACT6_V2_CORRECTION_PROGRESS_ONLY_NO_STATE_OR_CREDIT_PROMOTION",
        "predecessor_packet_bindings": deepcopy(predecessor_bindings),
        "source_bindings": deepcopy(source_bindings),
        "deep_validation": {
            "r027_public_rebuild_exact_bytes": True,
            "exact_six_public_deep_validator_exact_bytes": True,
            "self_seal_alone_accepted": False,
        },
        "row_delta": {
            "record_count": 257,
            "record_order_preserved": True,
            "unchanged_record_count": 251,
            "progress_binding_record_count": 6,
            "target_artifact_ids": list(TARGET_ARTIFACT_IDS),
            "other_record_delta_count": 0,
            "status_delta_count": 0,
            "credit_delta_count": 0,
        },
        "preserved_invariants": {
            "summaries_deep_equal": True,
            "authorization_boundary_deep_equal": True,
            "all_queue_routes_deep_equal": True,
            "zero_credits": deepcopy(ZERO_CREDITS),
        },
        "formal_device_external_release_boundary": {
            "formal_test_ids": ["TC-NPC-SINGLE-ADMIN-RECOVERY-01"],
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "actual_recovery_drill_status": "NOT_RUN",
            "external_evidence_status": "NOT_RUN",
            "production_deployment_status": "NOT_RUN",
            "release_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "ledger_binding": _binding(
            "R016-OUT-001",
            R016_LEDGER_REL,
            ledger_raw,
            "R016_FULL_EXACT257_LEDGER",
        ),
    }
    evidence_raw = r015_builder.r014_builder.seal_json(evidence, R016_EVIDENCE_REL)
    checks = [
        {"check_id": "R016-CHECK-R015-IMMUTABLE-PACKET", "expected": 3, "observed": 3, "status": "PASS"},
        {"check_id": "R016-CHECK-R027-PUBLIC-DEEP-REBUILD-EXACT", "expected": True, "observed": True, "status": "PASS"},
        {"check_id": "R016-CHECK-EXACT6-V2-PUBLIC-DEEP-VALIDATOR-EXACT", "expected": 6, "observed": 6, "status": "PASS"},
        {"check_id": "R016-CHECK-EXACT257-ORDER", "expected": {"records": 257, "ordered": True}, "observed": {"records": 257, "ordered": True}, "status": "PASS"},
        {"check_id": "R016-CHECK-ROW-DELTA-ALLOWLIST", "expected": {"unchanged": 251, "progress_only": 6}, "observed": {"unchanged": 251, "progress_only": 6}, "status": "PASS"},
        {"check_id": "R016-CHECK-STATUS-CREDIT-DELTA", "expected": {"status": 0, "credit": 0}, "observed": {"status": 0, "credit": 0}, "status": "PASS"},
        {"check_id": "R016-CHECK-FORMAL-DEVICE-DRILL-EXTERNAL-DEPLOYMENT", "expected": "NOT_RUN", "observed": "NOT_RUN", "status": "PASS"},
    ]
    receipt = {
        "schema_version": "walksafe.phase1-exact257-successor-check-receipt.v16",
        "receipt_id": "WS-PHASE1-EXACT257-SUCCESSOR-CHECK-RECEIPT-20260813-R016",
        "prepared_on": PREPARED_ON,
        "run_id": "WS-ARTIFACT-CLOSURE-RUN-20260727-001",
        "status": "PASS",
        "verdict": VERDICT,
        "summary": {
            "check_count": len(checks),
            "pass_count": len(checks),
            "fail_count": 0,
            "record_count": 257,
            "unchanged_record_count": 251,
            "progress_binding_record_count": 6,
            "status_delta_count": 0,
            "credit_delta_count": 0,
            "release_status": "NOT_ELIGIBLE",
            "zero_credits": deepcopy(ZERO_CREDITS),
        },
        "checks": checks,
        "predecessor_packet_bindings": deepcopy(predecessor_bindings),
        "source_bindings": deepcopy(source_bindings),
        "output_bindings": [
            _binding("R016-OUT-001", R016_LEDGER_REL, ledger_raw, "R016_FULL_EXACT257_LEDGER"),
            _binding("R016-OUT-002", R016_EVIDENCE_REL, evidence_raw, "R016_NPC_GAP008_R027_EXACT6_V2_CORRECTION_EVIDENCE"),
        ],
        "physical_output_contract": {
            "paths": [path.as_posix() for path in OUTPUT_PATHS],
            "add_only": True,
            "overwrite_allowed": False,
            "checkpoint_or_daylog_publication": False,
        },
    }
    receipt_raw = r015_builder.r014_builder.seal_json(receipt, R016_RECEIPT_REL)
    return {
        R016_LEDGER_REL: ledger_raw,
        R016_EVIDENCE_REL: evidence_raw,
        R016_RECEIPT_REL: receipt_raw,
    }


def build_outputs(root: Path = ROOT) -> dict[Path, bytes]:
    root = root.resolve()
    input_paths = (
        *PREDECESSOR_PATHS,
        trace.V2_IMPLEMENTATION_REL,
        trace.V2_VERIFICATION_REL,
        gap_builder.R027_GAP_JSON_REL,
        *(path for _, path in TARGET_ARTIFACT_PATHS),
    )
    raw = {path: trace.read_bytes(root, path) for path in input_paths}
    predecessor_documents = {
        path: trace.strict_json_bytes(raw[path], path.as_posix())
        for path in PREDECESSOR_PATHS
    }
    implementation = trace.strict_json_bytes(
        raw[trace.V2_IMPLEMENTATION_REL], trace.V2_IMPLEMENTATION_REL.as_posix()
    )
    verification = trace.strict_json_bytes(
        raw[trace.V2_VERIFICATION_REL], trace.V2_VERIFICATION_REL.as_posix()
    )
    gap = trace.strict_json_bytes(
        raw[gap_builder.R027_GAP_JSON_REL], gap_builder.R027_GAP_JSON_REL.as_posix()
    )
    artifact_raw = {path: raw[path] for _, path in TARGET_ARTIFACT_PATHS}
    artifact_documents = {
        path: trace.strict_json_bytes(artifact_raw[path], path.as_posix())
        for _, path in TARGET_ARTIFACT_PATHS
    }
    outputs = build_documents(
        predecessor_documents,
        {path: raw[path] for path in PREDECESSOR_PATHS},
        implementation,
        verification,
        gap,
        artifact_documents,
        root=root,
        implementation_raw=raw[trace.V2_IMPLEMENTATION_REL],
        verification_raw=raw[trace.V2_VERIFICATION_REL],
        gap_raw=raw[gap_builder.R027_GAP_JSON_REL],
        artifact_raw=artifact_raw,
    )
    require(
        {path: trace.read_bytes(root, path) for path in input_paths} == raw,
        "R016 inputs changed during build",
    )
    return outputs


def write_or_check_outputs(
    root: Path,
    outputs: Mapping[Path, bytes],
    *,
    write: bool,
) -> None:
    require(set(outputs) == set(OUTPUT_PATHS), "R016 output inventory differs")
    r015_builder.write_or_check_outputs(root, outputs, write=write)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        root = args.root.resolve()
        outputs = build_outputs(root)
        write_or_check_outputs(root, outputs, write=args.write)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"WalkSafe phase1 exact257 successor R016: FAIL: {exc}")
        return 1
    print("WalkSafe phase1 exact257 successor R016: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
