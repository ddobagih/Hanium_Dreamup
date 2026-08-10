#!/usr/bin/env python3
"""Build the add-only exact257 R013 FP-008 progress successor.

R012 is immutable.  R013 preserves record order, changes exactly the six
DOC-05/DOC-01/REQ-16/DES-06/DEV-01/DEV-18 progress records, leaves the other
251 records deep-equal, and creates no status or credit promotion.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import importlib
import json
from pathlib import Path
import sys
from typing import Any, Mapping, Sequence


def _load(name: str) -> Any:
    filename = {
        "trace": "build_walksafe_fp008_admin_review_delivery_trace_20260803.py",
        "gap": "build_walksafe_fp008_gap_backlog_r024_20260803.py",
        "artifact": "build_walksafe_fp008_artifact_trace_successor_20260803.py",
    }[name]
    try:
        if name == "trace":
            from scripts import build_walksafe_fp008_admin_review_delivery_trace_20260803 as module
        elif name == "gap":
            from scripts import build_walksafe_fp008_gap_backlog_r024_20260803 as module
        else:
            from scripts import build_walksafe_fp008_artifact_trace_successor_20260803 as module
        return module
    except ModuleNotFoundError:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module(f"scripts.{filename.removesuffix('.py')}")


trace = _load("trace")
gap_builder = _load("gap")
artifact_builder = _load("artifact")
ROOT = Path(__file__).resolve().parents[1]
RUN_DIR_REL = Path("docs/control/execution/artifact-closure/run-20260727-001")
R012_DIR_REL = RUN_DIR_REL / "packets/phase1-exact257-successor-r012"
R012_LEDGER_REL = R012_DIR_REL / "phase1-exact257-successor-ledger-r012.json"
R012_EVIDENCE_REL = R012_DIR_REL / "evidence.json"
R012_RECEIPT_REL = R012_DIR_REL / "phase1-exact257-successor-check-receipt-r012.json"
R013_DIR_REL = RUN_DIR_REL / "packets/phase1-exact257-successor-r013"
R013_LEDGER_REL = R013_DIR_REL / "phase1-exact257-successor-ledger-r013.json"
R013_EVIDENCE_REL = R013_DIR_REL / "evidence.json"
R013_RECEIPT_REL = R013_DIR_REL / "phase1-exact257-successor-check-receipt-r013.json"
OUTPUT_PATHS = (R013_LEDGER_REL, R013_EVIDENCE_REL, R013_RECEIPT_REL)
PREDECESSOR_PATHS = (R012_LEDGER_REL, R012_EVIDENCE_REL, R012_RECEIPT_REL)
EXPECTED_R012_SHA256_BY_PATH = {
    R012_LEDGER_REL: "526033bb5ad0abbc870d6c57ab20b7a0e7f2aee9f43a20baef55c8557aa40c94",
    R012_EVIDENCE_REL: "19a014d0563e2ecacf8a3cb783b748a6b4cd87d9accd27f89890e90597aa63a1",
    R012_RECEIPT_REL: "1a7e0c5d54878d93dfb5e3cdb3740f775e9e451e7d01c771be0f10ac5a5ee53c",
}
TARGET_ARTIFACT_PATHS = (
    ("DLV-DOC-05", artifact_builder.DOC05_REL),
    ("DLV-DOC-01", artifact_builder.DOC01_REL),
    ("DLV-REQ-16", artifact_builder.RTM_REL),
    ("DLV-DES-06", artifact_builder.DESIGN_REL),
    ("DLV-DEV-01", artifact_builder.IMPLEMENTATION_MANIFEST_REL),
    ("DLV-DEV-18", artifact_builder.MODULE_REGISTER_REL),
)
TARGET_ARTIFACT_IDS = tuple(row[0] for row in TARGET_ARTIFACT_PATHS)
TARGET_ARTIFACT_ID_SET = frozenset(TARGET_ARTIFACT_IDS)
ARTIFACT_BINDING_ID_BY_ID = {artifact_id: f"R013-SRC-{index:03d}" for index, (artifact_id, _) in enumerate(TARGET_ARTIFACT_PATHS, start=4)}
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
VERDICT = "PASS_FOR_FP008_EXACT6_RECORD_PROGRESS_BINDING_WITH_ZERO_CREDIT_ONLY"
PREPARED_ON = "2026-08-09"

BuildError = trace.BuildError
require = trace.require
bytes_sha256 = trace.bytes_sha256
object_sha256 = trace.object_sha256


def _binding(binding_id: str, relative: Path, raw: bytes, role: str) -> dict[str, Any]:
    require(not trace._path_forbidden(relative.as_posix()), f"forbidden R013 source: {relative}")
    return {
        "binding_id": binding_id,
        "path": relative.as_posix(),
        "byte_length": len(raw),
        "sha256": bytes_sha256(raw),
        "subject_role": role,
    }


def _nonself_contract(target: Path) -> dict[str, Any]:
    return {
        "algorithm": "SHA-256",
        "encoding": "UTF-8",
        "recursive_key_order": "LEXICOGRAPHIC",
        "ensure_ascii": False,
        "json_separators": [",", ":"],
        "projection_path": "/",
        "projection_null_paths": [
            "/integrity/canonical_byte_count",
            "/integrity/content_sha256",
            "/nonself_digest_check/canonical_byte_count",
            "/nonself_digest_check/content_sha256",
        ],
        "final_lf": True,
        "canonical_byte_count": None,
        "content_sha256": None,
        "target_path": target.as_posix(),
    }


def _projection(document: Mapping[str, Any]) -> bytes:
    value = deepcopy(dict(document))
    value["integrity"]["canonical_byte_count"] = None
    value["integrity"]["content_sha256"] = None
    value["nonself_digest_check"]["canonical_byte_count"] = None
    value["nonself_digest_check"]["content_sha256"] = None
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")


def seal_json(document: Mapping[str, Any], target: Path) -> bytes:
    value = deepcopy(dict(document))
    value["integrity"] = _nonself_contract(target)
    value["nonself_digest_check"] = {**_nonself_contract(target), "status": "PASS"}
    projection = _projection(value)
    digest = bytes_sha256(projection)
    for key in ("integrity", "nonself_digest_check"):
        value[key]["canonical_byte_count"] = len(projection)
        value[key]["content_sha256"] = digest
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n").encode("utf-8")


def verify_nonself(document: Mapping[str, Any], target: Path) -> None:
    require(type(document.get("integrity")) is dict and type(document.get("nonself_digest_check")) is dict, f"nonself sections missing: {target}")
    projection = _projection(document)
    digest = bytes_sha256(projection)
    for key in ("integrity", "nonself_digest_check"):
        require(document[key].get("target_path") == target.as_posix(), f"nonself target differs: {target}")
        require(document[key].get("canonical_byte_count") == len(projection), f"nonself byte count differs: {target}")
        require(document[key].get("content_sha256") == digest, f"nonself digest differs: {target}")
    require(document["nonself_digest_check"].get("status") == "PASS", f"nonself status differs: {target}")


def _record_map(ledger: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    rows = ledger.get("records")
    require(type(rows) is list and len(rows) == 257, "exact257 record count differs")
    result = {row.get("artifact_type_code"): row for row in rows if type(row) is dict}
    require(len(result) == 257 and None not in result, "exact257 record IDs differ")
    return result


def validate_inputs(
    predecessor_ledger: Mapping[str, Any],
    predecessor_evidence: Mapping[str, Any],
    predecessor_receipt: Mapping[str, Any],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
    artifact_documents: Mapping[Path, Mapping[str, Any]],
    *,
    predecessor_raw: Mapping[Path, bytes],
) -> None:
    require(set(predecessor_raw) == set(PREDECESSOR_PATHS), "R012 predecessor byte set differs")
    for relative in PREDECESSOR_PATHS:
        require(
            bytes_sha256(predecessor_raw[relative]) == EXPECTED_R012_SHA256_BY_PATH[relative],
            f"canonical R012 predecessor bytes differ: {relative}",
        )
    verify_nonself(predecessor_ledger, R012_LEDGER_REL)
    verify_nonself(predecessor_evidence, R012_EVIDENCE_REL)
    verify_nonself(predecessor_receipt, R012_RECEIPT_REL)
    require(predecessor_ledger.get("ledger_id") == "WS-PHASE1-EXACT257-SUCCESSOR-LEDGER-20260802-R012", "R012 ledger identity differs")
    require(predecessor_receipt.get("status") == "PASS", "R012 receipt did not pass")
    _record_map(predecessor_ledger)
    artifact_builder.validate_inputs(implementation, verification, gap)
    require(set(artifact_documents) == {path for _, path in TARGET_ARTIFACT_PATHS}, "R013 six-artifact input set differs")
    seals = {
        artifact_builder.DOC05_REL: "content_sha256",
        artifact_builder.DOC01_REL: "content_sha256",
        artifact_builder.RTM_REL: "document_content_sha256",
        artifact_builder.DESIGN_REL: "register_content_sha256",
        artifact_builder.IMPLEMENTATION_MANIFEST_REL: "fp008_successor_content_sha256",
        artifact_builder.MODULE_REGISTER_REL: "fp008_successor_content_sha256",
    }
    for relative, document in artifact_documents.items():
        trace.verify_seal(document, seals[relative], f"R013 FP-008 artifact {relative}")
        marker = document.get("fp008_artifact_trace_successor")
        require(type(marker) is dict and marker.get("successor_id") == artifact_builder.SUCCESSOR_ID, f"FP-008 artifact marker missing: {relative}")
    module = artifact_documents[artifact_builder.MODULE_REGISTER_REL]
    admin = [row for row in module.get("modules", []) if row.get("module_id") == "MOD-ANDROID-ADMIN"]
    require(len(admin) == 1 and admin[0].get("paths") == ["apps/android/adminapp"], "DEV-18 does not bind actual adminapp path")


def _source_bindings(
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
    artifact_raw: Mapping[Path, bytes],
) -> list[dict[str, Any]]:
    rows = [
        _binding("R013-SRC-001", trace.IMPLEMENTATION_REL, implementation_raw, "FP008_IMPLEMENTATION_RESULT"),
        _binding("R013-SRC-002", trace.VERIFICATION_REL, verification_raw, "FP008_VERIFICATION_RESULT"),
        _binding("R013-SRC-003", gap_builder.R024_GAP_JSON_REL, gap_raw, "GAP017_R024_SUCCESSOR"),
    ]
    for index, (artifact_id, relative) in enumerate(TARGET_ARTIFACT_PATHS, start=4):
        rows.append(_binding(f"R013-SRC-{index:03d}", relative, artifact_raw[relative], f"FP008_{artifact_id}_PHYSICAL_SUCCESSOR"))
    require(len(rows) == 9, "R013 exact source binding count differs")
    return rows


def _progress_entries(artifact_id: str, source_by_id: Mapping[str, Mapping[str, Any]]) -> dict[str, dict[str, Any]]:
    binding_id = ARTIFACT_BINDING_ID_BY_ID[artifact_id]
    physical = source_by_id[binding_id]
    physical_binding = {key: physical[key] for key in ("binding_id", "path", "byte_length", "sha256")}
    producer_ids = {"implementation_record": "R013-SRC-001", "verification_result": "R013-SRC-002", "gap017_r024_successor": "R013-SRC-003"}
    return {
        "content_authored": {
            "status": "FP008_PHYSICAL_DOCUMENT_PROGRESS_BOUND_NO_STATE_PROMOTION",
            "artifact_type_code": artifact_id,
            "physical_document_binding": physical_binding,
            "producer_result_binding_ids": producer_ids,
            "artifact_content_accepted": False,
            "owner_approved": False,
            "completion_claimed": False,
            "state_promotion": False,
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "external_evidence_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
        },
        "packet_materialization": {
            "status": "FP008_PHYSICAL_DOCUMENT_MATERIALIZED_PROGRESS_ONLY",
            "artifact_type_code": artifact_id,
            "physical_document_binding": physical_binding,
            "coverage_basis": "EXACT_ARTIFACT_ID_TO_PHYSICAL_DOCUMENT_BINDING",
            "state_promotion": False,
            "completion_claimed": False,
        },
        "internal_validation": {
            "status": "FP008_INTERNAL_VERIFICATION_OBSERVATION_BOUND_NO_CREDIT",
            "artifact_type_code": artifact_id,
            "implementation_binding_id": "R013-SRC-001",
            "verification_binding_id": "R013-SRC-002",
            "gap017_r024_binding_id": "R013-SRC-003",
            "physical_document_binding_id": binding_id,
            "internal_verification_pass_observed": True,
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "external_evidence_status": "NOT_RUN",
            "state_promotion": False,
            "completion_claimed": False,
            "release_status": "NOT_ELIGIBLE",
            "credit_count": 0,
        },
    }


def _build_ledger(predecessor: Mapping[str, Any], predecessor_bindings: list[dict[str, Any]], source_bindings: list[dict[str, Any]]) -> dict[str, Any]:
    ledger = deepcopy(dict(predecessor))
    ledger.pop("integrity", None)
    ledger.pop("nonself_digest_check", None)
    ledger["schema_version"] = "walksafe.phase1-exact257-successor-ledger.v13"
    ledger["ledger_id"] = "WS-PHASE1-EXACT257-SUCCESSOR-LEDGER-20260803-R013"
    ledger["prepared_on"] = PREPARED_ON
    ledger["predecessor_r012_packet"] = {
        "ledger_binding_id": "R013-PRE-001",
        "evidence_binding_id": "R013-PRE-002",
        "receipt_binding_id": "R013-PRE-003",
        "semantics": "IMMUTABLE_AUTHORITATIVE_R012_PACKET",
    }
    ledger["r013_predecessor_packet_bindings"] = deepcopy(predecessor_bindings)
    ledger["r013_source_bindings"] = deepcopy(source_bindings)
    ledger["r013_fp008_artifact_progress_application"] = {
        "application_id": "WS-PHASE1-EXACT257-FP008-PROGRESS-20260803-R013",
        "verdict": VERDICT,
        "record_count": 257,
        "record_order_preserved": True,
        "unchanged_record_count": 251,
        "progress_binding_record_count": 6,
        "target_artifact_ids": list(TARGET_ARTIFACT_IDS),
        "queue_route_delta_count": 0,
        "summary_delta_count": 0,
        "authorization_delta_count": 0,
        "formal_test_ids": list(trace.FORMAL_TEST_IDS),
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "external_evidence_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
        "zero_credits": deepcopy(ZERO_CREDITS),
    }
    ledger.setdefault("exact_set_fingerprints", {})["r013_fp008_exact6_set"] = {
        "count": 6,
        "ordered_ids": list(TARGET_ARTIFACT_IDS),
        "sha256": object_sha256(list(TARGET_ARTIFACT_IDS)),
    }
    by_binding = {row["binding_id"]: row for row in source_bindings}
    for row in ledger["records"]:
        artifact_id = row["artifact_type_code"]
        if artifact_id not in TARGET_ARTIFACT_ID_SET:
            continue
        entries = _progress_entries(artifact_id, by_binding)
        row["progress_axes"]["content_authored"]["observations"].append(entries["content_authored"])
        row["progress_axes"]["packet_materialization"].append(entries["packet_materialization"])
        row["progress_axes"]["internal_validation"]["observations"].append(entries["internal_validation"])
    return ledger


def _changed_record_counts(before: Mapping[str, Any], after: Mapping[str, Any]) -> tuple[int, int]:
    before_rows = _record_map(before)
    after_rows = _record_map(after)
    require(list(before_rows) == list(after_rows), "R013 record order differs")
    unchanged = 0
    changed = 0
    for artifact_id, before_row in before_rows.items():
        after_row = after_rows[artifact_id]
        if artifact_id not in TARGET_ARTIFACT_ID_SET:
            require(after_row == before_row, f"non-target R013 record changed: {artifact_id}")
            unchanged += 1
            continue
        require(after_row != before_row, f"target R013 record did not change: {artifact_id}")
        expected = deepcopy(before_row)
        source_by_id = {row["binding_id"]: row for row in after["r013_source_bindings"]}
        entries = _progress_entries(artifact_id, source_by_id)
        expected["progress_axes"]["content_authored"]["observations"].append(entries["content_authored"])
        expected["progress_axes"]["packet_materialization"].append(entries["packet_materialization"])
        expected["progress_axes"]["internal_validation"]["observations"].append(entries["internal_validation"])
        require(after_row == expected, f"R013 target has a non-progress delta: {artifact_id}")
        require(after_row["queue_route"] == before_row["queue_route"], f"R013 queue route changed: {artifact_id}")
        require(after_row["release_eligibility"] == before_row["release_eligibility"], f"R013 release status changed: {artifact_id}")
        changed += 1
    require((unchanged, changed) == (251, 6), "R013 exact 251/6 record split differs")
    require(after["summaries"] == before["summaries"], "R013 summaries changed")
    require(after["authorization_boundary"] == before["authorization_boundary"], "R013 authorization boundary changed")
    return unchanged, changed


def build_documents(
    predecessor_ledger: Mapping[str, Any],
    predecessor_evidence: Mapping[str, Any],
    predecessor_receipt: Mapping[str, Any],
    *,
    predecessor_raw: Mapping[Path, bytes],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
    artifact_documents: Mapping[Path, Mapping[str, Any]],
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
    artifact_raw: Mapping[Path, bytes],
) -> dict[Path, bytes]:
    require(set(predecessor_raw) == set(PREDECESSOR_PATHS), "R012 predecessor document/raw set differs")
    for relative, document in zip(
        PREDECESSOR_PATHS,
        (predecessor_ledger, predecessor_evidence, predecessor_receipt),
        strict=True,
    ):
        trace.require_document_matches_raw(document, predecessor_raw[relative], f"R012 predecessor {relative}")
    trace.require_document_matches_raw(implementation, implementation_raw, "FP-008 implementation")
    trace.require_document_matches_raw(verification, verification_raw, "FP-008 verification")
    trace.require_document_matches_raw(gap, gap_raw, "R024 gap")
    validate_inputs(
        predecessor_ledger,
        predecessor_evidence,
        predecessor_receipt,
        implementation,
        verification,
        gap,
        artifact_documents,
        predecessor_raw=predecessor_raw,
    )
    artifact_builder.validate_successor_documents(
        artifact_documents,
        artifact_raw,
        implementation,
        verification,
        gap,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
    )
    register = artifact_documents[artifact_builder.DOC01_REL]
    register_rows = {row.get("display_code"): row for row in register.get("artifacts", [])}
    for artifact_id, relative in TARGET_ARTIFACT_PATHS:
        code = artifact_id.removeprefix("DLV-")
        row = register_rows.get(code)
        require(type(row) is dict, f"R013 DOC-01 target row missing: {artifact_id}")
        rebinding = row.get("fp008_internal_rebinding")
        require(type(rebinding) is dict and rebinding.get("physical_path") == relative.as_posix(), f"R013 DOC-01 rebinding differs: {artifact_id}")
        expected_sha = None if relative == artifact_builder.DOC01_REL else bytes_sha256(artifact_raw[relative])
        require(rebinding.get("physical_sha256") == expected_sha, f"R013 DOC-01 physical digest differs: {artifact_id}")
    predecessor_bindings = [
        _binding(f"R013-PRE-{index:03d}", relative, predecessor_raw[relative], f"R012_{role}")
        for index, (relative, role) in enumerate(zip(PREDECESSOR_PATHS, ("LEDGER", "EVIDENCE", "RECEIPT"), strict=True), start=1)
    ]
    source_bindings = _source_bindings(implementation_raw, verification_raw, gap_raw, artifact_raw)
    ledger = _build_ledger(predecessor_ledger, predecessor_bindings, source_bindings)
    unchanged, changed = _changed_record_counts(predecessor_ledger, ledger)
    ledger_raw = seal_json(ledger, R013_LEDGER_REL)
    evidence = {
        "schema_version": "walksafe.phase1-exact257-successor-evidence.v13",
        "packet_id": "WS-PHASE1-EXACT257-SUCCESSOR-EVIDENCE-20260803-R013",
        "prepared_on": PREPARED_ON,
        "run_id": "WS-ARTIFACT-CLOSURE-RUN-20260727-001",
        "verdict": VERDICT,
        "claim_semantics": "RECORD_PROGRESS_BINDING_ONLY_NO_STATE_OR_CREDIT_PROMOTION",
        "predecessor_packet_bindings": deepcopy(predecessor_bindings),
        "source_bindings": deepcopy(source_bindings),
        "row_delta": {
            "record_count": 257,
            "record_order_preserved": True,
            "unchanged_record_count": unchanged,
            "progress_binding_record_count": changed,
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
            "formal_test_ids": list(trace.FORMAL_TEST_IDS),
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "external_evidence_status": "NOT_RUN",
            "production_deployment_status": "NOT_RUN",
            "release_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "ledger_binding": _binding("R013-OUT-001", R013_LEDGER_REL, ledger_raw, "R013_FULL_EXACT257_LEDGER"),
    }
    evidence_raw = seal_json(evidence, R013_EVIDENCE_REL)
    checks = [
        {"check_id": "R013-CHECK-R012-IMMUTABLE-PACKET", "expected": 3, "observed": 3, "status": "PASS"},
        {"check_id": "R013-CHECK-EXACT257-ORDER", "expected": {"records": 257, "ordered": True}, "observed": {"records": 257, "ordered": True}, "status": "PASS"},
        {"check_id": "R013-CHECK-ROW-DELTA-ALLOWLIST", "expected": {"unchanged": 251, "progress_only": 6}, "observed": {"unchanged": unchanged, "progress_only": changed}, "status": "PASS"},
        {"check_id": "R013-CHECK-STATUS-CREDIT-DELTA", "expected": {"status": 0, "credit": 0}, "observed": {"status": 0, "credit": 0}, "status": "PASS"},
        {"check_id": "R013-CHECK-FORMAL-DEVICE-EXTERNAL-DEPLOYMENT", "expected": "NOT_RUN", "observed": "NOT_RUN", "status": "PASS"},
        {"check_id": "R013-CHECK-DEV18-ADMINAPP-PATH", "expected": "apps/android/adminapp", "observed": "apps/android/adminapp", "status": "PASS"},
    ]
    receipt = {
        "schema_version": "walksafe.phase1-exact257-successor-check-receipt.v13",
        "receipt_id": "WS-PHASE1-EXACT257-SUCCESSOR-CHECK-RECEIPT-20260803-R013",
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
            _binding("R013-OUT-001", R013_LEDGER_REL, ledger_raw, "R013_FULL_EXACT257_LEDGER"),
            _binding("R013-OUT-002", R013_EVIDENCE_REL, evidence_raw, "R013_FP008_EXACT6_PROGRESS_EVIDENCE"),
        ],
        "physical_output_contract": {
            "paths": [path.as_posix() for path in OUTPUT_PATHS],
            "add_only": True,
            "overwrite_allowed": False,
            "checkpoint_or_daylog_publication": False,
        },
    }
    receipt_raw = seal_json(receipt, R013_RECEIPT_REL)
    return {R013_LEDGER_REL: ledger_raw, R013_EVIDENCE_REL: evidence_raw, R013_RECEIPT_REL: receipt_raw}


def build_outputs(root: Path = ROOT) -> dict[Path, bytes]:
    predecessor_raw = {path: trace.read_bytes(root, path) for path in PREDECESSOR_PATHS}
    predecessors = {path: trace.strict_json_bytes(raw, path.as_posix()) for path, raw in predecessor_raw.items()}
    implementation_raw = trace.read_bytes(root, trace.IMPLEMENTATION_REL)
    verification_raw = trace.read_bytes(root, trace.VERIFICATION_REL)
    gap_raw = trace.read_bytes(root, gap_builder.R024_GAP_JSON_REL)
    implementation = trace.strict_json_bytes(implementation_raw, trace.IMPLEMENTATION_REL.as_posix())
    verification = trace.strict_json_bytes(verification_raw, trace.VERIFICATION_REL.as_posix())
    gap = trace.strict_json_bytes(gap_raw, gap_builder.R024_GAP_JSON_REL.as_posix())
    artifact_raw = {path: trace.read_bytes(root, path) for _, path in TARGET_ARTIFACT_PATHS}
    artifact_documents = {path: trace.strict_json_bytes(raw, path.as_posix()) for path, raw in artifact_raw.items()}
    return build_documents(
        predecessors[R012_LEDGER_REL], predecessors[R012_EVIDENCE_REL], predecessors[R012_RECEIPT_REL],
        predecessor_raw=predecessor_raw,
        implementation=implementation, verification=verification, gap=gap,
        artifact_documents=artifact_documents,
        implementation_raw=implementation_raw, verification_raw=verification_raw, gap_raw=gap_raw,
        artifact_raw=artifact_raw,
    )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        outputs = build_outputs(args.root)
        text_outputs = {path: raw.decode("utf-8") for path, raw in outputs.items()}
        trace.write_or_check_outputs(args.root, text_outputs, write=args.write)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"WalkSafe exact257 R013 successor: FAIL: {exc}")
        return 1
    print(f"WalkSafe exact257 R013 successor: PASS outputs={len(outputs)} mode={'WRITE' if args.write else 'CHECK'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
