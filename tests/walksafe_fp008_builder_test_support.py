from __future__ import annotations

import json
from copy import deepcopy
from pathlib import Path
from typing import Any

from scripts import build_walksafe_fp008_admin_review_delivery_trace_20260803 as trace
from scripts import build_walksafe_fp008_gap_backlog_r024_20260803 as gap_builder
from scripts import build_walksafe_fp008_artifact_trace_successor_20260803 as artifact_builder
from scripts import build_walksafe_phase1_exact257_successor_r013_20260803 as r013_builder


REPO_ROOT = Path(__file__).resolve().parents[1]
AUTHORITY_PATHS = (
    trace.GOAL_REL,
    trace.POLICY_CONTRACT_REL,
    trace.INITIAL_GATE_REL,
    trace.INITIAL_GATE_REPOSITORY_STATE_REL,
    trace.RESUME_GATE_REL,
    trace.RESUME_GATE_REPOSITORY_STATE_REL,
)


def lane_observations() -> dict[str, dict[str, Any]]:
    raw_outputs = lane_raw_outputs()
    result: dict[str, dict[str, Any]] = {}
    for index, lane in enumerate(trace.LANES, start=1):
        raw = raw_outputs[lane.lane_id]
        result[lane.lane_id] = {
            "schema_version": "walksafe.fp008-internal-lane-observation.v1",
            "lane_id": lane.lane_id,
            "status": "PASS",
            "command": lane.expected_command,
            "exit_code": 0,
            "started_at": f"2026-08-09T12:0{index}:00+09:00",
            "ended_at": f"2026-08-09T12:0{index}:30+09:00",
            "raw_output_sha256": trace.bytes_sha256(raw),
            "raw_output_byte_length": len(raw),
            "metrics": deepcopy(dict(lane.expected_metrics)),
            "evidence_boundary": {
                "evidence_kind": "REPOSITORY_INTERNAL_AUTOMATED_CHECK",
                "formal_test_status": "NOT_RUN",
                "actual_device_status": "NOT_RUN",
                "external_status": "NOT_RUN",
                "production_status": "NOT_RUN",
            },
        }
    return result


def lane_raw_outputs() -> dict[str, bytes]:
    result: dict[str, bytes] = {}
    for lane in trace.LANES:
        lines = [f"WALKSAFE_FP008_COMMAND {lane.expected_command}"]
        if lane.result_kind == "PYTEST":
            lines.append(f"{lane.expected_metrics['passed']} passed in 1.00s")
        else:
            lines.extend(
                (
                    "BUILD SUCCESSFUL in 1s",
                    "WALKSAFE_FP008_JUNIT tests=70 failures=0 errors=0 skipped=0",
                    "WALKSAFE_FP008_LINT errors=0 warnings=1",
                    "WALKSAFE_FP008_APK byte_length=921914 sha256=9afeb2bc1243603c3022074d9faefe6c647d65be3a9cbbab5f48dccab9b7ed58",
                )
            )
        result[lane.lane_id] = ("\n".join(lines) + "\n").encode()
    return result


def authority() -> dict[str, Any]:
    return {
        "goal_binding": {"role": "FP008_GOAL", "path": trace.GOAL_REL.as_posix(), "byte_length": 1, "sha256": "a" * 64},
        "initial_gate_binding": {
            "role": "INITIAL_EXACT9_IMPLEMENTATION_START_GATE",
            "path": trace.INITIAL_GATE_REL.as_posix(),
            "byte_length": 1,
            "sha256": trace.EXPECTED_INITIAL_GATE_SHA256,
            "repository_state_path": trace.INITIAL_GATE_REPOSITORY_STATE_REL.as_posix(),
            "repository_state_sha256": trace.EXPECTED_INITIAL_GATE_REPOSITORY_STATE_SHA256,
        },
        "resume_gate_binding": {
            "role": "SEQ48_WORK_SESSION_RESUME_GATE",
            "path": trace.RESUME_GATE_REL.as_posix(),
            "byte_length": 1,
            "sha256": trace.EXPECTED_RESUME_GATE_SHA256,
            "event_sequence": 48,
            "event_id": trace.EXPECTED_RESUME_EVENT_ID,
        },
        "policy_contract_binding": {"role": "FP008_POLICY_CONTRACT", "path": trace.POLICY_CONTRACT_REL.as_posix(), "byte_length": 1, "sha256": "b" * 64},
        "gate_ended_at": "2026-08-09T11:43:40+09:00",
    }


def write(relative: Path, raw: bytes, root: Path) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)


def materialize_authority(root: Path) -> dict[str, Any]:
    for relative in AUTHORITY_PATHS:
        write(relative, (REPO_ROOT / relative).read_bytes(), root)
    return trace.validate_authority(root)


def build_trace(root: Path, *, canonical_scope: bool = False) -> dict[Path, str]:
    if canonical_scope:
        for name in dict.fromkeys((*trace.IMPLEMENTATION_PATHS, *trace.VERIFICATION_INPUT_PATHS)):
            relative = Path(name)
            write(relative, (REPO_ROOT / relative).read_bytes(), root)
        implementation_paths = trace.IMPLEMENTATION_PATHS
        verification_input_paths = trace.VERIFICATION_INPUT_PATHS
    else:
        product = Path("product/adminapp-source.txt")
        test = Path("product/adminapp-test.txt")
        write(product, b"adminapp-source\n", root)
        write(test, b"adminapp-test\n", root)
        write(
            artifact_builder.BUILDER_REL,
            (REPO_ROOT / artifact_builder.BUILDER_REL).read_bytes(),
            root,
        )
        implementation_paths = (
            product.as_posix(),
            artifact_builder.BUILDER_REL.as_posix(),
        )
        verification_input_paths = (test.as_posix(),)
    auth = materialize_authority(root)
    return trace.build_pre_review_outputs(
        root=root,
        lane_observations=lane_observations(),
        lane_raw_outputs=lane_raw_outputs(),
        implementation_paths=implementation_paths,
        verification_input_paths=verification_input_paths,
        authority=auth,
    )


def build_r024(trace_outputs: dict[Path, str]) -> dict[Path, str]:
    gap_raw = (REPO_ROOT / gap_builder.R023_GAP_REL).read_bytes()
    backlog_raw = (REPO_ROOT / gap_builder.R023_BACKLOG_REL).read_bytes()
    predecessor_gap = trace.strict_json_bytes(gap_raw, "R023 gap")
    predecessor_backlog = trace.strict_json_bytes(backlog_raw, "R023 backlog")
    implementation_raw = trace_outputs[trace.IMPLEMENTATION_REL].encode()
    verification_raw = trace_outputs[trace.VERIFICATION_REL].encode()
    implementation = trace.strict_json_bytes(implementation_raw, "implementation")
    verification = trace.strict_json_bytes(verification_raw, "verification")
    return gap_builder.build_documents(
        predecessor_gap,
        predecessor_backlog,
        implementation,
        verification,
        predecessor_gap_raw=gap_raw,
        predecessor_backlog_raw=backlog_raw,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
    )


def build_artifacts(trace_outputs: dict[Path, str], r024_outputs: dict[Path, str]) -> dict[Path, str]:
    predecessor_raw = {path: (REPO_ROOT / path).read_bytes() for path in artifact_builder.OUTPUT_PATHS}
    predecessors = {path: trace.strict_json_bytes(raw, path.as_posix()) for path, raw in predecessor_raw.items()}
    implementation_raw = trace_outputs[trace.IMPLEMENTATION_REL].encode()
    verification_raw = trace_outputs[trace.VERIFICATION_REL].encode()
    gap_raw = r024_outputs[gap_builder.R024_GAP_JSON_REL].encode()
    implementation = trace.strict_json_bytes(implementation_raw, "implementation")
    verification = trace.strict_json_bytes(verification_raw, "verification")
    gap = trace.strict_json_bytes(gap_raw, "R024 gap")
    predecessor_flags = {
        path: trace.bytes_sha256(predecessor_raw[path])
        == artifact_builder.EXPECTED_PREDECESSOR_SHA256_BY_PATH[path]
        for path in artifact_builder.OUTPUT_PATHS
    }
    if all(predecessor_flags.values()):
        return artifact_builder.build_documents(
            predecessors,
            predecessor_raw,
            implementation,
            verification,
            gap,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
            gap_raw=gap_raw,
        )
    trace.require(
        not any(predecessor_flags.values()),
        "live six-artifact fixture source is mixed between FP048 and FP008",
    )
    live_input_raw = {
        path: (REPO_ROOT / path).read_bytes() for path in artifact_builder.INPUT_PATHS
    }
    live_inputs = {
        path: trace.strict_json_bytes(raw, path.as_posix())
        for path, raw in live_input_raw.items()
    }
    artifact_builder.validate_successor_documents(
        predecessors,
        predecessor_raw,
        live_inputs[trace.IMPLEMENTATION_REL],
        live_inputs[trace.VERIFICATION_REL],
        live_inputs[gap_builder.R024_GAP_JSON_REL],
        implementation_raw=live_input_raw[trace.IMPLEMENTATION_REL],
        verification_raw=live_input_raw[trace.VERIFICATION_REL],
        gap_raw=live_input_raw[gap_builder.R024_GAP_JSON_REL],
    )
    recovered = artifact_builder.recover_predecessors(predecessors)
    return artifact_builder._build_from_predecessors(
        recovered,
        artifact_builder.EXPECTED_PREDECESSOR_SHA256_BY_PATH,
        implementation,
        verification,
        gap,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
    )


def build_r013(
    trace_outputs: dict[Path, str],
    r024_outputs: dict[Path, str],
    artifact_outputs: dict[Path, str],
) -> dict[Path, bytes]:
    predecessor_raw = {path: (REPO_ROOT / path).read_bytes() for path in r013_builder.PREDECESSOR_PATHS}
    predecessors = {path: trace.strict_json_bytes(raw, path.as_posix()) for path, raw in predecessor_raw.items()}
    implementation_raw = trace_outputs[trace.IMPLEMENTATION_REL].encode()
    verification_raw = trace_outputs[trace.VERIFICATION_REL].encode()
    gap_raw = r024_outputs[gap_builder.R024_GAP_JSON_REL].encode()
    artifact_raw = {path: artifact_outputs[path].encode() for _, path in r013_builder.TARGET_ARTIFACT_PATHS}
    artifact_documents = {path: trace.strict_json_bytes(raw, path.as_posix()) for path, raw in artifact_raw.items()}
    return r013_builder.build_documents(
        predecessors[r013_builder.R012_LEDGER_REL],
        predecessors[r013_builder.R012_EVIDENCE_REL],
        predecessors[r013_builder.R012_RECEIPT_REL],
        predecessor_raw=predecessor_raw,
        implementation=trace.strict_json_bytes(implementation_raw, "implementation"),
        verification=trace.strict_json_bytes(verification_raw, "verification"),
        gap=trace.strict_json_bytes(gap_raw, "R024 gap"),
        artifact_documents=artifact_documents,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
        artifact_raw=artifact_raw,
    )


def materialize_complete_fixture(root: Path) -> tuple[dict[Path, str], dict[Path, str], dict[Path, str], dict[Path, bytes]]:
    trace_outputs = build_trace(root, canonical_scope=True)
    r024_outputs = build_r024(trace_outputs)
    artifact_outputs = build_artifacts(trace_outputs, r024_outputs)
    r013_outputs = build_r013(trace_outputs, r024_outputs, artifact_outputs)
    for relative, content in trace_outputs.items():
        write(relative, content.encode(), root)
    for relative, content in r024_outputs.items():
        write(relative, content.encode(), root)
    for relative, content in artifact_outputs.items():
        write(relative, content.encode(), root)
    for relative, raw in r013_outputs.items():
        write(relative, raw, root)
    for relative in (gap_builder.R023_GAP_REL, gap_builder.R023_BACKLOG_REL, *r013_builder.PREDECESSOR_PATHS):
        write(relative, (REPO_ROOT / relative).read_bytes(), root)
    return trace_outputs, r024_outputs, artifact_outputs, r013_outputs
