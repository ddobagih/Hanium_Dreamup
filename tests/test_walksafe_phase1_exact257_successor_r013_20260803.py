from __future__ import annotations

import copy
import json
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import walksafe_fp008_builder_test_support as support
from scripts import build_walksafe_phase1_exact257_successor_r013_20260803 as builder


def test_r013_exact6_changed_251_unchanged_and_zero_status_credit(tmp_path: Path) -> None:
    trace_outputs = support.build_trace(tmp_path)
    r024_outputs = support.build_r024(trace_outputs)
    artifact_outputs = support.build_artifacts(trace_outputs, r024_outputs)
    first = support.build_r013(trace_outputs, r024_outputs, artifact_outputs)
    second = support.build_r013(trace_outputs, r024_outputs, artifact_outputs)
    assert first == second
    ledger = json.loads(first[builder.R013_LEDGER_REL])
    receipt = json.loads(first[builder.R013_RECEIPT_REL])
    predecessor = json.loads((support.REPO_ROOT / builder.R012_LEDGER_REL).read_text())
    before = {row["artifact_type_code"]: row for row in predecessor["records"]}
    after = {row["artifact_type_code"]: row for row in ledger["records"]}
    changed = [artifact_id for artifact_id in before if before[artifact_id] != after[artifact_id]]
    assert set(changed) == set(builder.TARGET_ARTIFACT_IDS)
    assert len(changed) == 6
    assert sum(before[key] == after[key] for key in before if key not in changed) == 251
    assert ledger["summaries"] == predecessor["summaries"]
    assert ledger["authorization_boundary"] == predecessor["authorization_boundary"]
    assert receipt["summary"]["unchanged_record_count"] == 251
    assert receipt["summary"]["progress_binding_record_count"] == 6
    assert receipt["summary"]["status_delta_count"] == 0
    assert receipt["summary"]["credit_delta_count"] == 0
    assert all(value == 0 for value in receipt["summary"]["zero_credits"].values())


def test_r013_rejects_missing_adminapp_marker(tmp_path: Path) -> None:
    trace_outputs = support.build_trace(tmp_path)
    r024_outputs = support.build_r024(trace_outputs)
    artifacts = support.build_artifacts(trace_outputs, r024_outputs)
    module = json.loads(artifacts[builder.artifact_builder.MODULE_REGISTER_REL])
    next(row for row in module["modules"] if row["module_id"] == "MOD-ANDROID-ADMIN")["paths"] = ["apps/android/admin"]
    module.pop("fp008_successor_content_sha256")
    module = builder.trace.sealed(module, "fp008_successor_content_sha256")
    artifacts[builder.artifact_builder.MODULE_REGISTER_REL] = builder.trace.json_text(module)
    with pytest.raises(builder.BuildError, match="successor|adminapp"):
        support.build_r013(trace_outputs, r024_outputs, artifacts)


def test_resealed_forged_r012_is_rejected_by_raw_pin(tmp_path: Path) -> None:
    trace_outputs = support.build_trace(tmp_path)
    r024_outputs = support.build_r024(trace_outputs)
    artifacts = support.build_artifacts(trace_outputs, r024_outputs)
    predecessor_raw = {
        path: (support.REPO_ROOT / path).read_bytes() for path in builder.PREDECESSOR_PATHS
    }
    ledger = builder.trace.strict_json_bytes(
        predecessor_raw[builder.R012_LEDGER_REL], "R012 ledger"
    )
    target = next(
        row for row in ledger["records"]
        if row["artifact_type_code"] not in builder.TARGET_ARTIFACT_ID_SET
    )
    target["forged_progress"] = True
    forged_ledger_raw = builder.seal_json(ledger, builder.R012_LEDGER_REL)
    forged_raw = {**predecessor_raw, builder.R012_LEDGER_REL: forged_ledger_raw}
    implementation_raw = trace_outputs[builder.trace.IMPLEMENTATION_REL].encode()
    verification_raw = trace_outputs[builder.trace.VERIFICATION_REL].encode()
    gap_raw = r024_outputs[builder.gap_builder.R024_GAP_JSON_REL].encode()
    artifact_raw = {path: artifacts[path].encode() for _, path in builder.TARGET_ARTIFACT_PATHS}
    artifact_documents = {
        path: builder.trace.strict_json_bytes(raw, path.as_posix())
        for path, raw in artifact_raw.items()
    }
    with pytest.raises(builder.BuildError, match="canonical R012 predecessor bytes"):
        builder.validate_inputs(
            builder.trace.strict_json_bytes(forged_ledger_raw, "forged R012 ledger"),
            builder.trace.strict_json_bytes(predecessor_raw[builder.R012_EVIDENCE_REL], "R012 evidence"),
            builder.trace.strict_json_bytes(predecessor_raw[builder.R012_RECEIPT_REL], "R012 receipt"),
            builder.trace.strict_json_bytes(implementation_raw, "implementation"),
            builder.trace.strict_json_bytes(verification_raw, "verification"),
            builder.trace.strict_json_bytes(gap_raw, "gap"),
            artifact_documents,
            predecessor_raw=forged_raw,
        )
