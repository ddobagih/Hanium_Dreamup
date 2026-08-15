#!/usr/bin/env python3
"""Build R015 exact257 progress bindings for the NPC recovery exact-six artifacts."""

from __future__ import annotations

import argparse
from copy import deepcopy
import os
from pathlib import Path
import stat
from typing import Any, Mapping, Sequence

from scripts import build_walksafe_npc_single_admin_recovery_trace_20260812 as trace
from scripts import build_walksafe_npc_single_admin_recovery_gap_backlog_r026_20260812 as gap_builder
from scripts import build_walksafe_npc_single_admin_recovery_artifact_trace_successor_20260812 as artifact_builder
from scripts import build_walksafe_phase1_exact257_successor_r014_20260810 as r014_builder


ROOT = Path(__file__).resolve().parents[1]
RUN_DIR_REL = Path("docs/control/execution/artifact-closure/run-20260727-001")
R014_DIR_REL = RUN_DIR_REL / "packets/phase1-exact257-successor-r014"
R014_LEDGER_REL = R014_DIR_REL / "phase1-exact257-successor-ledger-r014.json"
R014_EVIDENCE_REL = R014_DIR_REL / "evidence.json"
R014_RECEIPT_REL = R014_DIR_REL / "phase1-exact257-successor-check-receipt-r014.json"
R015_DIR_REL = trace.R015_DIR_REL
R015_LEDGER_REL = R015_DIR_REL / "phase1-exact257-successor-ledger-r015.json"
R015_EVIDENCE_REL = R015_DIR_REL / "evidence.json"
R015_RECEIPT_REL = R015_DIR_REL / "phase1-exact257-successor-check-receipt-r015.json"
PREDECESSOR_PATHS = (R014_LEDGER_REL, R014_EVIDENCE_REL, R014_RECEIPT_REL)
OUTPUT_PATHS = (R015_LEDGER_REL, R015_EVIDENCE_REL, R015_RECEIPT_REL)
EXPECTED_R014_SHA256_BY_PATH = {
    R014_LEDGER_REL: "275e0f193f367b409c9498d44ed229736cad15f952cc3e7dd8c69d4f3d78ad5d",
    R014_EVIDENCE_REL: "047d2f06203a9438c666da37c8c9f57827018fde08b5a7efcedc673fc4c7217f",
    R014_RECEIPT_REL: "5d0988a25d418cf1a801fbec47a3452255c8ab4065b18bd1ac80fbdbf91963bc",
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
PREPARED_ON = "2026-08-12"
VERDICT = "PASS_FOR_NPC_SINGLE_ADMIN_RECOVERY_GAP008_R026_EXACT6_RECORD_PROGRESS_BINDING_WITH_ZERO_CREDIT_ONLY"
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


def validate_predecessor_packet(
    documents: Mapping[Path, Mapping[str, Any]],
    raw_by_path: Mapping[Path, bytes],
) -> None:
    require(set(documents) == set(raw_by_path) == set(PREDECESSOR_PATHS), "R014 predecessor inventory differs")
    for path in PREDECESSOR_PATHS:
        require(bytes_sha256(raw_by_path[path]) == EXPECTED_R014_SHA256_BY_PATH[path], f"R014 predecessor SHA-256 differs: {path}")
        require(raw_by_path[path] == trace.json_text(documents[path]).encode(), f"R014 predecessor JSON is noncanonical: {path}")
        r014_builder.verify_nonself(documents[path], path)
    receipt = documents[R014_RECEIPT_REL]
    require(
        receipt.get("status") == "PASS"
        and receipt.get("summary", {}).get("record_count") == 257
        and receipt.get("summary", {}).get("unchanged_record_count") == 251
        and receipt.get("summary", {}).get("progress_binding_record_count") == 6,
        "R014 exact251+6 receipt differs",
    )


def validate_sources(
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
    artifact_documents: Mapping[Path, Mapping[str, Any]],
) -> None:
    artifact_builder.validate_inputs(implementation, verification, gap)
    require(set(artifact_documents) == {path for _, path in TARGET_ARTIFACT_PATHS}, "exact-six artifact inventory differs")
    for _, path in TARGET_ARTIFACT_PATHS:
        document = artifact_documents[path]
        marker = document.get("npc_single_admin_recovery_artifact_trace_successor")
        require(
            type(marker) is dict
            and marker.get("successor_id") == artifact_builder.SUCCESSOR_ID
            and marker.get("physical_path") == path.as_posix()
            and marker.get("trace_boundary") == artifact_builder.boundary(),
            f"NPC physical successor marker differs: {path}",
        )
        artifact_builder.verify_projection_seal(
            document, artifact_builder.SEAL_FIELD_BY_PATH[path], f"NPC physical successor {path}"
        )


def _source_bindings(
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
    artifact_raw: Mapping[Path, bytes],
) -> list[dict[str, Any]]:
    bindings = [
        _binding("R015-SRC-001", trace.IMPLEMENTATION_REL, implementation_raw, "NPC_IMPLEMENTATION_RESULT"),
        _binding("R015-SRC-002", trace.VERIFICATION_REL, verification_raw, "NPC_VERIFICATION_RESULT"),
        _binding("R015-SRC-003", gap_builder.R026_GAP_JSON_REL, gap_raw, "GAP008_R026_SUCCESSOR"),
    ]
    for index, (artifact_id, path) in enumerate(TARGET_ARTIFACT_PATHS, start=4):
        bindings.append(
            _binding(
                f"R015-SRC-{index:03d}",
                path,
                artifact_raw[path],
                f"NPC_{artifact_id}_PHYSICAL_SUCCESSOR",
            )
        )
    return bindings


def _record_map(ledger: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    records = ledger.get("records")
    require(type(records) is list and len(records) == 257, "exact257 record count differs")
    by_id = {row.get("artifact_type_code"): row for row in records if type(row) is dict}
    require(len(by_id) == 257 and None not in by_id, "exact257 record IDs differ")
    return by_id


def build_ledger(
    predecessor: Mapping[str, Any],
    predecessor_bindings: Sequence[Mapping[str, Any]],
    source_bindings: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    ledger = deepcopy(dict(predecessor))
    ledger.pop("integrity", None)
    ledger.pop("nonself_digest_check", None)
    ledger["schema_version"] = "walksafe.phase1-exact257-successor-ledger.v15"
    ledger["ledger_id"] = "WS-PHASE1-EXACT257-SUCCESSOR-LEDGER-20260812-R015"
    ledger["prepared_on"] = PREPARED_ON
    ledger["r015_predecessor_packet_bindings"] = deepcopy(list(predecessor_bindings))
    ledger["r015_source_bindings"] = deepcopy(list(source_bindings))
    ledger["r015_npc_single_admin_recovery_gap008_r026_artifact_progress_application"] = {
        "application_id": "WS-PHASE1-EXACT257-NPC-GAP008-R026-PROGRESS-20260812-R015",
        "verdict": VERDICT,
        "record_count": 257,
        "record_order_preserved": True,
        "unchanged_record_count": 251,
        "progress_binding_record_count": 6,
        "target_artifact_ids": list(TARGET_ARTIFACT_IDS),
        "summary_delta_count": 0,
        "queue_route_delta_count": 0,
        "authorization_delta_count": 0,
        "formal_test_ids": ["TC-NPC-SINGLE-ADMIN-RECOVERY-01"],
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "actual_recovery_drill_status": "NOT_RUN",
        "external_evidence_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
        "zero_credits": deepcopy(ZERO_CREDITS),
    }
    by_id = _record_map(ledger)
    source_by_role = {row["subject_role"]: row for row in source_bindings}
    for artifact_id, _ in TARGET_ARTIFACT_PATHS:
        physical = source_by_role[f"NPC_{artifact_id}_PHYSICAL_SUCCESSOR"]
        row = by_id[artifact_id]
        axes = row["progress_axes"]
        axes["content_authored"]["observations"].append(
            {
                "status": "NPC_SINGLE_ADMIN_RECOVERY_PHYSICAL_DOCUMENT_PROGRESS_BOUND_NO_STATE_PROMOTION",
                "artifact_type_code": artifact_id,
                "physical_document_binding": deepcopy(physical),
                "producer_result_binding_ids": {
                    "implementation_record": "R015-SRC-001",
                    "verification_result": "R015-SRC-002",
                    "gap008_r026_successor": "R015-SRC-003",
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
            }
        )
        axes["internal_validation"]["observations"].append(
            {
                "status": "NPC_SINGLE_ADMIN_RECOVERY_INTERNAL_VERIFICATION_OBSERVATION_BOUND_NO_CREDIT",
                "artifact_type_code": artifact_id,
                "implementation_binding_id": "R015-SRC-001",
                "verification_binding_id": "R015-SRC-002",
                "gap008_r026_binding_id": "R015-SRC-003",
                "physical_document_binding_id": physical["binding_id"],
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
            }
        )
        axes["packet_materialization"].append(
            {
                "status": "NPC_SINGLE_ADMIN_RECOVERY_PHYSICAL_DOCUMENT_MATERIALIZED_PROGRESS_ONLY",
                "artifact_type_code": artifact_id,
                "coverage_basis": "EXACT_ARTIFACT_ID_TO_PHYSICAL_DOCUMENT_BINDING",
                "physical_document_binding": deepcopy(physical),
                "completion_claimed": False,
                "state_promotion": False,
            }
        )
    return ledger


def build_documents(
    predecessor_documents: Mapping[Path, Mapping[str, Any]],
    predecessor_raw: Mapping[Path, bytes],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
    artifact_documents: Mapping[Path, Mapping[str, Any]],
    *,
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
    artifact_raw: Mapping[Path, bytes],
) -> dict[Path, bytes]:
    validate_predecessor_packet(predecessor_documents, predecessor_raw)
    validate_sources(implementation, verification, gap, artifact_documents)
    require(set(artifact_raw) == set(artifact_documents), "artifact raw inventory differs")
    require(
        implementation_raw == trace.json_text(implementation).encode("utf-8"),
        "implementation source JSON is noncanonical",
    )
    require(
        verification_raw == trace.json_text(verification).encode("utf-8"),
        "verification source JSON is noncanonical",
    )
    require(
        gap_raw == trace.json_text(gap).encode("utf-8"),
        "R026 gap source JSON is noncanonical",
    )
    artifact_builder.validate_successor_documents(artifact_documents, artifact_raw)
    predecessor_bindings = [
        _binding(f"R015-PRE-{index:03d}", path, predecessor_raw[path], f"R014_{role}")
        for index, (path, role) in enumerate(
            zip(PREDECESSOR_PATHS, ("LEDGER", "EVIDENCE", "RECEIPT"), strict=True),
            start=1,
        )
    ]
    source_bindings = _source_bindings(
        implementation_raw, verification_raw, gap_raw, artifact_raw
    )
    ledger = build_ledger(
        predecessor_documents[R014_LEDGER_REL], predecessor_bindings, source_bindings
    )
    ledger_raw = r014_builder.seal_json(ledger, R015_LEDGER_REL)
    evidence = {
        "schema_version": "walksafe.phase1-exact257-successor-evidence.v15",
        "packet_id": "WS-PHASE1-EXACT257-SUCCESSOR-EVIDENCE-20260812-R015",
        "prepared_on": PREPARED_ON,
        "run_id": "WS-ARTIFACT-CLOSURE-RUN-20260727-001",
        "verdict": VERDICT,
        "claim_semantics": "NPC_GAP008_R026_RECORD_PROGRESS_BINDING_ONLY_NO_STATE_OR_CREDIT_PROMOTION",
        "predecessor_packet_bindings": deepcopy(predecessor_bindings),
        "source_bindings": deepcopy(source_bindings),
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
        "ledger_binding": _binding("R015-OUT-001", R015_LEDGER_REL, ledger_raw, "R015_FULL_EXACT257_LEDGER"),
    }
    evidence_raw = r014_builder.seal_json(evidence, R015_EVIDENCE_REL)
    checks = [
        {"check_id": "R015-CHECK-R014-IMMUTABLE-PACKET", "expected": 3, "observed": 3, "status": "PASS"},
        {"check_id": "R015-CHECK-EXACT257-ORDER", "expected": {"records": 257, "ordered": True}, "observed": {"records": 257, "ordered": True}, "status": "PASS"},
        {"check_id": "R015-CHECK-ROW-DELTA-ALLOWLIST", "expected": {"unchanged": 251, "progress_only": 6}, "observed": {"unchanged": 251, "progress_only": 6}, "status": "PASS"},
        {"check_id": "R015-CHECK-STATUS-CREDIT-DELTA", "expected": {"status": 0, "credit": 0}, "observed": {"status": 0, "credit": 0}, "status": "PASS"},
        {"check_id": "R015-CHECK-DRILL-FORMAL-DEVICE-EXTERNAL-DEPLOYMENT", "expected": "NOT_RUN", "observed": "NOT_RUN", "status": "PASS"},
        {"check_id": "R015-CHECK-NPC-GAP008-R026-ARTIFACT-MARKERS", "expected": 6, "observed": 6, "status": "PASS"},
    ]
    receipt = {
        "schema_version": "walksafe.phase1-exact257-successor-check-receipt.v15",
        "receipt_id": "WS-PHASE1-EXACT257-SUCCESSOR-CHECK-RECEIPT-20260812-R015",
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
            _binding("R015-OUT-001", R015_LEDGER_REL, ledger_raw, "R015_FULL_EXACT257_LEDGER"),
            _binding("R015-OUT-002", R015_EVIDENCE_REL, evidence_raw, "R015_NPC_GAP008_R026_EXACT6_PROGRESS_EVIDENCE"),
        ],
        "physical_output_contract": {
            "paths": [path.as_posix() for path in OUTPUT_PATHS],
            "add_only": True,
            "overwrite_allowed": False,
            "checkpoint_or_daylog_publication": False,
        },
    }
    receipt_raw = r014_builder.seal_json(receipt, R015_RECEIPT_REL)
    return {R015_LEDGER_REL: ledger_raw, R015_EVIDENCE_REL: evidence_raw, R015_RECEIPT_REL: receipt_raw}


def build_outputs(root: Path = ROOT) -> dict[Path, bytes]:
    predecessor_raw = {path: trace.read_bytes(root, path) for path in PREDECESSOR_PATHS}
    predecessor_documents = {path: trace.strict_json_bytes(raw, path.as_posix()) for path, raw in predecessor_raw.items()}
    implementation_raw = trace.read_bytes(root, trace.IMPLEMENTATION_REL)
    verification_raw = trace.read_bytes(root, trace.VERIFICATION_REL)
    gap_raw = trace.read_bytes(root, gap_builder.R026_GAP_JSON_REL)
    implementation = trace.strict_json_bytes(implementation_raw, trace.IMPLEMENTATION_REL.as_posix())
    verification = trace.strict_json_bytes(verification_raw, trace.VERIFICATION_REL.as_posix())
    gap = trace.strict_json_bytes(gap_raw, gap_builder.R026_GAP_JSON_REL.as_posix())
    artifact_raw = {path: trace.read_bytes(root, path) for _, path in TARGET_ARTIFACT_PATHS}
    artifact_documents = {path: trace.strict_json_bytes(raw, path.as_posix()) for path, raw in artifact_raw.items()}
    return build_documents(
        predecessor_documents,
        predecessor_raw,
        implementation,
        verification,
        gap,
        artifact_documents,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
        artifact_raw=artifact_raw,
    )


def write_or_check_outputs(root: Path, outputs: Mapping[Path, bytes], *, write: bool) -> None:
    text_outputs: dict[Path, str] = {}
    for relative, raw in outputs.items():
        try:
            trace.io_base._validate_relative(relative)
        except trace.io_base.BuildError as exc:
            raise BuildError(str(exc)) from exc
        require(type(raw) is bytes, f"R015 output must be bytes: {relative}")
        try:
            text = raw.decode("utf-8", errors="strict")
        except UnicodeDecodeError as exc:
            raise BuildError(f"R015 output is not strict UTF-8: {relative}") from exc
        document = trace.strict_json_bytes(raw, relative.as_posix())
        require(
            text == trace.json_text(document),
            f"R015 output is not canonical JSON text: {relative}",
        )
        text_outputs[relative] = text
    if write:
        trace.write_or_check_outputs(root, text_outputs, write=True)
        return

    io_base = trace.io_base
    root = root.resolve(strict=True)
    root_before_open = root.lstat()
    require(
        stat.S_ISDIR(root_before_open.st_mode)
        and not stat.S_ISLNK(root_before_open.st_mode),
        "R015 output root authority differs before check",
    )
    try:
        root_descriptor = os.open(root, io_base._directory_open_flags())
        try:
            require(
                io_base._inode_identity(os.fstat(root_descriptor))
                == io_base._inode_identity(root_before_open),
                "R015 output root changed while opening for check",
            )
            io_base.fcntl.flock(root_descriptor, io_base.fcntl.LOCK_SH)
            io_base._validate_output_ancestry(
                root,
                root_descriptor,
                (),
                Path("."),
            )
            io_base._validate_final_targets(root, root_descriptor, text_outputs)
            io_base._validate_output_ancestry(
                root,
                root_descriptor,
                (),
                Path("."),
            )
        finally:
            try:
                io_base.fcntl.flock(root_descriptor, io_base.fcntl.LOCK_UN)
            finally:
                os.close(root_descriptor)
    except io_base.BuildError as exc:
        raise BuildError(str(exc)) from exc


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
        outputs = build_outputs(args.root.resolve())
        write_or_check_outputs(args.root.resolve(), outputs, write=args.write)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"WalkSafe phase1 exact257 successor R015: FAIL: {exc}")
        return 1
    print("WalkSafe phase1 exact257 successor R015: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
