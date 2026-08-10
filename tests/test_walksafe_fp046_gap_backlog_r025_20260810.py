from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
from typing import Any

import pytest

from scripts import (
    build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810 as trace,
)
from scripts import build_walksafe_fp046_gap_backlog_r025_20260810 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]


def _write(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)


def _source_groups(root: Path) -> tuple[trace.SourceGroup, ...]:
    groups = (
        trace.SourceGroup("ANDROID", ("product/android.kt",)),
        trace.SourceGroup("GATEWAY", ("product/gateway.ts",)),
        trace.SourceGroup("BACKEND", ("product/backend.py",)),
        trace.SourceGroup("TOOLING", ("product/trace.py",)),
    )
    for index, group in enumerate(groups, start=1):
        _write(
            root,
            Path(group.paths[0]),
            f"fp046-final-content-{index}\n".encode("utf-8"),
        )
    return groups


def _authority() -> dict[str, Any]:
    return {
        "goal_binding": {
            "role": "FP046_GOAL",
            "path": trace.GOAL_REL.as_posix(),
            "byte_length": 1,
            "sha256": "a" * 64,
        },
        "start_gate_binding": {
            "role": "FP046_EXACT9_START_GATE",
            "path": trace.START_GATE_REL.as_posix(),
            "byte_length": 1,
            "sha256": "b" * 64,
            "repository_state_path": (
                trace.START_GATE_REPOSITORY_STATE_REL.as_posix()
            ),
            "repository_state_sha256": "c" * 64,
            "event_sequence": trace.EXPECTED_START_EVENT_SEQUENCE,
            "event_id": trace.EXPECTED_START_EVENT_ID,
        },
        "gate_ended_at": "2026-08-09T20:14:48+09:00",
    }


def _lane_inputs() -> tuple[dict[str, dict[str, Any]], dict[str, bytes]]:
    observations: dict[str, dict[str, Any]] = {}
    raw_outputs: dict[str, bytes] = {}
    for index, lane in enumerate(trace.LANES, start=1):
        passed = lane.expected_passed
        started_at = f"2026-08-10T02:0{index}:00+09:00"
        ended_at = f"2026-08-10T02:0{index}:30+09:00"
        if lane.lane_id == "ANDROID_CONSENT_DELETION":
            runner_summary = (
                f"JUnit tests={passed} failures=0 errors=0 skipped=0"
            )
        elif lane.lane_id == "GATEWAY_PRIVACY_LEDGER":
            runner_summary = (
                f"Node tests={passed} pass={passed} fail=0; "
                "typecheck=PASS; build=PASS"
            )
        else:
            runner_summary = f"{passed} passed in 1.00s"
        raw = (
            f"WALKSAFE_FP046_COMMAND {lane.expected_command}\n"
            f"WALKSAFE_FP046_STARTED_AT {started_at}\n"
            f"{runner_summary}\n"
            "WALKSAFE_FP046_SUMMARY "
            f"passed={passed} failed=0 errors=0 skipped=0\n"
            "WALKSAFE_FP046_EXIT_CODE 0\n"
            f"WALKSAFE_FP046_ENDED_AT {ended_at}\n"
        ).encode("utf-8")
        raw_outputs[lane.lane_id] = raw
        observations[lane.lane_id] = {
            "schema_version": "walksafe.fp046-internal-lane-observation.v1",
            "lane_id": lane.lane_id,
            "status": "PASS",
            "command": lane.expected_command,
            "exit_code": 0,
            "started_at": f"2026-08-10T02:0{index}:00+09:00",
            "ended_at": f"2026-08-10T02:0{index}:30+09:00",
            "raw_output_sha256": trace.bytes_sha256(raw),
            "raw_output_byte_length": len(raw),
            "metrics": {
                "result_format": "INTERNAL_TEST_SUMMARY_V1",
                "passed": passed,
                "failed": 0,
                "errors": 0,
                "skipped": 0,
            },
            "evidence_boundary": trace.lane_evidence_boundary(),
        }
    return observations, raw_outputs


def _trace_outputs(root: Path) -> dict[Path, str]:
    observations, raw_outputs = _lane_inputs()
    return trace.build_pre_review_outputs(
        root=root,
        lane_observations=observations,
        lane_raw_outputs=raw_outputs,
        source_groups=_source_groups(root),
        authority=_authority(),
    )


def _artifact_validation_kwargs(
    trace_outputs: dict[Path, str],
    root: Path,
) -> dict[str, Any]:
    return {
        "lane_receipt_raw_by_id": {
            lane.lane_id: trace_outputs[lane.receipt_rel].encode("utf-8")
            for lane in trace.LANES
        },
        "lane_log_raw_by_id": {
            lane.lane_id: trace_outputs[lane.log_rel].encode("utf-8")
            for lane in trace.LANES
        },
        "root": root,
        "source_groups": _source_groups(root),
        "authority": _authority(),
    }


def _predecessor_inputs() -> tuple[bytes, bytes, dict[str, Any], dict[str, Any]]:
    gap_raw = (REPO_ROOT / builder.R024_GAP_REL).read_bytes()
    backlog_raw = (REPO_ROOT / builder.R024_BACKLOG_REL).read_bytes()
    return (
        gap_raw,
        backlog_raw,
        trace.strict_json_bytes(gap_raw, "R024 gap"),
        trace.strict_json_bytes(backlog_raw, "R024 backlog"),
    )


def _build_r025(
    trace_outputs: dict[Path, str],
    root: Path,
) -> tuple[dict[Path, str], tuple[bytes, bytes, dict[str, Any], dict[str, Any]]]:
    gap_raw, backlog_raw, gap, backlog = _predecessor_inputs()
    implementation_raw = trace_outputs[trace.IMPLEMENTATION_REL].encode("utf-8")
    verification_raw = trace_outputs[trace.VERIFICATION_REL].encode("utf-8")
    outputs = builder.build_documents(
        gap,
        backlog,
        trace.strict_json_bytes(implementation_raw, "FP-046 implementation"),
        trace.strict_json_bytes(verification_raw, "FP-046 verification"),
        predecessor_gap_raw=gap_raw,
        predecessor_backlog_raw=backlog_raw,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        **_artifact_validation_kwargs(trace_outputs, root),
    )
    return outputs, (gap_raw, backlog_raw, gap, backlog)


def test_r025_changes_only_fp046_and_preserves_all_credit_boundaries(
    tmp_path: Path,
) -> None:
    trace_outputs = _trace_outputs(tmp_path)
    first, predecessor_inputs = _build_r025(trace_outputs, tmp_path)
    second, _ = _build_r025(trace_outputs, tmp_path)
    assert first == second
    assert tuple(first) == builder.OUTPUT_PATHS

    _, _, predecessor_gap, predecessor_backlog = predecessor_inputs
    gap = json.loads(first[builder.R025_GAP_JSON_REL])
    backlog = json.loads(first[builder.R025_BACKLOG_JSON_REL])
    trace.verify_seal(gap, "report_content_sha256", "R025 gap")
    trace.verify_seal(backlog, "backlog_content_sha256", "R025 backlog")

    before_by_id = {
        row["gap_id"]: row for row in predecessor_gap["assessments"]
    }
    after_by_id = {row["gap_id"]: row for row in gap["assessments"]}
    assert len(before_by_id) == len(after_by_id) == 68
    assert all(
        after_by_id[gap_id] == row
        for gap_id, row in before_by_id.items()
        if gap_id != builder.GAP_ID
    )
    assert before_by_id[builder.GAP_ID]["status"] == "MISSING"
    assert after_by_id[builder.GAP_ID]["status"] == "PARTIAL"
    assert after_by_id[builder.GAP_ID]["planned_test_ids"] == list(
        trace.FORMAL_TEST_IDS
    )
    assert (
        after_by_id[builder.GAP_ID]["formal_test_status"]
        == before_by_id[builder.GAP_ID]["formal_test_status"]
        == "NOT_RUN"
    )
    assert (
        after_by_id[builder.GAP_ID]["waived"]
        == before_by_id[builder.GAP_ID]["waived"]
    )
    assert gap["summary"]["status_counts"] == builder.EXPECTED_AFTER_COUNTS
    assert gap["summary"]["implemented_and_formally_verified_count"] == 0
    assert gap["summary"]["release_status"] == "NOT_ELIGIBLE"
    assert gap["fp046_verification_boundary"] == trace.completion_boundary()
    assert gap["reassessment_scope"]["next_adjacent_gap"] == {
        "gap_id": builder.NEXT_GAP_ID,
        "source_policy_id": builder.NEXT_POLICY_ID,
        "priority_rank": builder.NEXT_PRIORITY_RANK,
    }

    implementation = json.loads(trace_outputs[trace.IMPLEMENTATION_REL])
    snapshot = gap["implementation_snapshot"]
    manifest = implementation["final_content_manifest"]
    fixture_paths = [
        path for group in _source_groups(tmp_path) for path in group.paths
    ]
    assert snapshot["file_count"] == manifest["exact_path_count"] == len(
        fixture_paths
    )
    assert snapshot["paths"] == fixture_paths
    assert snapshot["files"] == manifest["files"]
    assert snapshot["path_set_sha256"] == manifest["path_set_sha256"]
    assert snapshot["content_set_sha256"] == manifest["content_set_sha256"]
    assert snapshot["manifest_content_sha256"] == manifest[
        "manifest_content_sha256"
    ]
    snapshot_projection = deepcopy(snapshot)
    snapshot_sha256 = snapshot_projection.pop("snapshot_sha256")
    assert snapshot_sha256 == trace.object_sha256(snapshot_projection)

    before_actions = {
        row["source_policy_id"]: row
        for row in predecessor_backlog["next_action_sequence"]
    }
    after_actions = {
        row["source_policy_id"]: row
        for row in backlog["next_action_sequence"]
    }
    assert set(before_actions) == set(after_actions)
    assert all(
        after_actions[policy_id] == row
        for policy_id, row in before_actions.items()
        if policy_id != builder.POLICY_ID
    )
    assert before_actions[builder.POLICY_ID]["status"] == "MISSING"
    assert after_actions[builder.POLICY_ID]["status"] == "PARTIAL"
    assert after_actions[builder.NEXT_POLICY_ID]["order"] == 22
    assert after_actions[builder.NEXT_POLICY_ID]["status"] == "MISSING"
    assert backlog["next_single_action"] == {
        "epic_id": "EPIC-03",
        "source_policy_id": builder.NEXT_POLICY_ID,
        "gap_id": builder.NEXT_GAP_ID,
        "priority_rank": 22,
        "status": "PLANNED_NEXT",
        "action": after_actions[builder.NEXT_POLICY_ID]["action"],
    }
    before_epic = next(
        row for row in predecessor_backlog["epics"] if row["epic_id"] == "EPIC-03"
    )
    after_epic = next(
        row for row in backlog["epics"] if row["epic_id"] == "EPIC-03"
    )
    assert after_epic["current_status"] == before_epic["current_status"]
    assert (
        after_epic["deferred_release_gate_ids"]
        == before_epic["deferred_release_gate_ids"]
    )
    assert backlog["authorization_boundary"] == predecessor_backlog[
        "authorization_boundary"
    ]


def test_r025_live_snapshot_binds_all_103_current_sources() -> None:
    groups = trace.IMPLEMENTATION_SOURCE_GROUPS
    canonical_paths = [path for group in groups for path in group.paths]
    assert len(canonical_paths) == len(set(canonical_paths)) == 103
    manifest = trace.build_final_content_manifest(REPO_ROOT, groups)
    implementation = {
        "final_content_manifest": manifest,
        "scope_kind": trace.IMPLEMENTATION_SCOPE_KIND,
        "implementation_content_set_sha256": manifest["content_set_sha256"],
    }

    snapshot = builder._implementation_snapshot(
        implementation,
        root=REPO_ROOT,
        source_groups=groups,
    )

    assert snapshot["file_count"] == len(canonical_paths)
    assert snapshot["paths"] == canonical_paths
    assert snapshot["files"] == manifest["files"]
    for row in snapshot["files"]:
        raw = (REPO_ROOT / row["path"]).read_bytes()
        assert row["byte_length"] == len(raw)
        assert row["sha256"] == trace.bytes_sha256(raw)


def test_build_outputs_reads_only_bound_inputs_and_writes_nothing(
    tmp_path: Path,
) -> None:
    trace_outputs = _trace_outputs(tmp_path)
    expected, _ = _build_r025(trace_outputs, tmp_path)
    for relative in (builder.R024_GAP_REL, builder.R024_BACKLOG_REL):
        _write(tmp_path, relative, (REPO_ROOT / relative).read_bytes())
    _write(
        tmp_path,
        trace.IMPLEMENTATION_REL,
        trace_outputs[trace.IMPLEMENTATION_REL].encode("utf-8"),
    )
    _write(
        tmp_path,
        trace.VERIFICATION_REL,
        trace_outputs[trace.VERIFICATION_REL].encode("utf-8"),
    )

    for lane in trace.LANES:
        _write(
            tmp_path,
            lane.receipt_rel,
            trace_outputs[lane.receipt_rel].encode("utf-8"),
        )
        _write(
            tmp_path,
            lane.log_rel,
            trace_outputs[lane.log_rel].encode("utf-8"),
        )
    assert (
        builder.build_outputs(
            tmp_path,
            source_groups=_source_groups(tmp_path),
            authority=_authority(),
        )
        == expected
    )
    assert all(not (tmp_path / relative).exists() for relative in builder.OUTPUT_PATHS)

    missing_receipt = trace.LANES[0].receipt_rel
    saved_receipt = (tmp_path / missing_receipt).read_bytes()
    (tmp_path / missing_receipt).unlink()
    with pytest.raises(trace.BuildError, match="required source missing"):
        builder.build_outputs(
            tmp_path,
            source_groups=_source_groups(tmp_path),
            authority=_authority(),
        )
    _write(tmp_path, missing_receipt, saved_receipt)

    changed_log = trace.LANES[0].log_rel
    (tmp_path / changed_log).write_bytes(
        (tmp_path / changed_log).read_bytes() + b"changed\n"
    )
    with pytest.raises(trace.BuildError, match="physical artifact binding differs"):
        builder.build_outputs(
            tmp_path,
            source_groups=_source_groups(tmp_path),
            authority=_authority(),
        )


def test_r024_raw_pin_and_document_binding_fail_closed(tmp_path: Path) -> None:
    trace_outputs = _trace_outputs(tmp_path)
    gap_raw, backlog_raw, gap, backlog = _predecessor_inputs()
    implementation_raw = trace_outputs[trace.IMPLEMENTATION_REL].encode("utf-8")
    verification_raw = trace_outputs[trace.VERIFICATION_REL].encode("utf-8")
    implementation = trace.strict_json_bytes(implementation_raw, "implementation")
    verification = trace.strict_json_bytes(verification_raw, "verification")

    object_only_tamper = deepcopy(gap)
    object_only_tamper["assessments"][0]["status"] = "IMPLEMENTED"
    with pytest.raises(trace.BuildError, match="document/raw binding differs"):
        builder.build_documents(
            object_only_tamper,
            backlog,
            implementation,
            verification,
            predecessor_gap_raw=gap_raw,
            predecessor_backlog_raw=backlog_raw,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
            **_artifact_validation_kwargs(trace_outputs, tmp_path),
        )

    forged_gap = deepcopy(gap)
    forged_row = forged_gap["assessments"][0]
    forged_row["rationale"] = f"{forged_row['rationale']} forged"
    forged_row.pop("assessment_sha256")
    forged_row["assessment_sha256"] = trace.object_sha256(forged_row)
    forged_gap.pop("report_content_sha256")
    forged_gap["report_content_sha256"] = trace.object_sha256(forged_gap)
    forged_gap_raw = trace.json_text(forged_gap).encode("utf-8")
    with pytest.raises(trace.BuildError, match="canonical R024 gap bytes differ"):
        builder.build_documents(
            forged_gap,
            backlog,
            implementation,
            verification,
            predecessor_gap_raw=forged_gap_raw,
            predecessor_backlog_raw=backlog_raw,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
            **_artifact_validation_kwargs(trace_outputs, tmp_path),
        )


def test_resealed_implementation_with_forged_manifest_fails_closed(
    tmp_path: Path,
) -> None:
    trace_outputs = _trace_outputs(tmp_path)
    gap_raw, backlog_raw, gap, backlog = _predecessor_inputs()
    implementation_raw = trace_outputs[trace.IMPLEMENTATION_REL].encode("utf-8")
    verification_raw = trace_outputs[trace.VERIFICATION_REL].encode("utf-8")
    implementation = trace.strict_json_bytes(implementation_raw, "implementation")
    verification = trace.strict_json_bytes(verification_raw, "verification")

    forged = deepcopy(implementation)
    forged["final_content_manifest"]["files"][0]["sha256"] = "f" * 64
    forged.pop("implementation_record_content_sha256")
    forged["implementation_record_content_sha256"] = trace.object_sha256(forged)
    forged_raw = trace.json_text(forged).encode("utf-8")
    with pytest.raises(trace.BuildError, match="final manifest derived hash differs"):
        builder.build_documents(
            gap,
            backlog,
            forged,
            verification,
            predecessor_gap_raw=gap_raw,
            predecessor_backlog_raw=backlog_raw,
            implementation_raw=forged_raw,
            verification_raw=verification_raw,
            **_artifact_validation_kwargs(trace_outputs, tmp_path),
        )


def test_r025_rejects_resealed_authority_and_formal_credit_forgery(
    tmp_path: Path,
) -> None:
    trace_outputs = _trace_outputs(tmp_path)
    gap_raw, backlog_raw, gap, backlog = _predecessor_inputs()
    implementation_raw = trace_outputs[trace.IMPLEMENTATION_REL].encode("utf-8")
    verification_raw = trace_outputs[trace.VERIFICATION_REL].encode("utf-8")
    implementation = trace.strict_json_bytes(implementation_raw, "implementation")
    verification = trace.strict_json_bytes(verification_raw, "verification")
    validation = _artifact_validation_kwargs(trace_outputs, tmp_path)

    forged_implementation = deepcopy(implementation)
    forged_implementation["authority_bindings"]["start_gate"]["sha256"] = (
        "f" * 64
    )
    forged_implementation.pop("implementation_record_content_sha256")
    forged_implementation = trace.sealed(
        forged_implementation,
        "implementation_record_content_sha256",
    )
    forged_implementation_raw = trace.json_text(forged_implementation).encode(
        "utf-8"
    )
    with pytest.raises(trace.BuildError, match="authority binding differs"):
        builder.build_documents(
            gap,
            backlog,
            forged_implementation,
            verification,
            predecessor_gap_raw=gap_raw,
            predecessor_backlog_raw=backlog_raw,
            implementation_raw=forged_implementation_raw,
            verification_raw=verification_raw,
            **validation,
        )

    forged_verification = deepcopy(verification)
    forged_verification["credit_scope"] = "FORMAL"
    forged_verification.pop("verification_result_content_sha256")
    forged_verification = trace.sealed(
        forged_verification,
        "verification_result_content_sha256",
    )
    forged_verification_raw = trace.json_text(forged_verification).encode(
        "utf-8"
    )
    with pytest.raises(trace.BuildError, match="verification identity differs"):
        builder.build_documents(
            gap,
            backlog,
            implementation,
            forged_verification,
            predecessor_gap_raw=gap_raw,
            predecessor_backlog_raw=backlog_raw,
            implementation_raw=implementation_raw,
            verification_raw=forged_verification_raw,
            **validation,
        )


def test_r025_requires_exact_receipt_and_log_artifact_bytes(tmp_path: Path) -> None:
    trace_outputs = _trace_outputs(tmp_path)
    gap_raw, backlog_raw, gap, backlog = _predecessor_inputs()
    implementation_raw = trace_outputs[trace.IMPLEMENTATION_REL].encode("utf-8")
    verification_raw = trace_outputs[trace.VERIFICATION_REL].encode("utf-8")
    implementation = trace.strict_json_bytes(implementation_raw, "implementation")
    verification = trace.strict_json_bytes(verification_raw, "verification")
    validation = _artifact_validation_kwargs(trace_outputs, tmp_path)

    missing_receipt = deepcopy(validation)
    receipt_raw = dict(missing_receipt["lane_receipt_raw_by_id"])
    receipt_raw.pop(trace.LANES[0].lane_id)
    missing_receipt["lane_receipt_raw_by_id"] = receipt_raw
    with pytest.raises(trace.BuildError, match="receipt artifact set differs"):
        builder.build_documents(
            gap,
            backlog,
            implementation,
            verification,
            predecessor_gap_raw=gap_raw,
            predecessor_backlog_raw=backlog_raw,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
            **missing_receipt,
        )

    changed_log = deepcopy(validation)
    log_raw = dict(changed_log["lane_log_raw_by_id"])
    log_raw[trace.LANES[0].lane_id] += b"changed\n"
    changed_log["lane_log_raw_by_id"] = log_raw
    with pytest.raises(trace.BuildError, match="physical artifact binding differs"):
        builder.build_documents(
            gap,
            backlog,
            implementation,
            verification,
            predecessor_gap_raw=gap_raw,
            predecessor_backlog_raw=backlog_raw,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
            **changed_log,
        )


def test_r025_rejects_final_source_byte_drift(tmp_path: Path) -> None:
    trace_outputs = _trace_outputs(tmp_path)
    gap_raw, backlog_raw, gap, backlog = _predecessor_inputs()
    implementation_raw = trace_outputs[trace.IMPLEMENTATION_REL].encode("utf-8")
    verification_raw = trace_outputs[trace.VERIFICATION_REL].encode("utf-8")
    validation = _artifact_validation_kwargs(trace_outputs, tmp_path)
    (tmp_path / "product/android.kt").write_bytes(b"drifted-after-trace\n")

    with pytest.raises(trace.BuildError, match="current file binding differs"):
        builder.build_documents(
            gap,
            backlog,
            trace.strict_json_bytes(implementation_raw, "implementation"),
            trace.strict_json_bytes(verification_raw, "verification"),
            predecessor_gap_raw=gap_raw,
            predecessor_backlog_raw=backlog_raw,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
            **validation,
        )
