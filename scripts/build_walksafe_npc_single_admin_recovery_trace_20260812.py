#!/usr/bin/env python3
"""Build the two-stage NPC single-admin recovery evidence trace.

The v2 observation hash is derived from the canonical bytes supplied to each
call.  It is not patched into this consumer because the consumer source is
itself part of the producer's complete execution-input closure.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
from typing import Any, Mapping, Sequence

from scripts import build_walksafe_fp008_admin_review_delivery_trace_20260803 as io_base
from scripts import run_walksafe_npc_single_admin_recovery_verification_20260813 as verification_runner


ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001"
WORK_ITEM_ID = "EPIC-03-NPC-SINGLE-ADMIN-RECOVERY"
POLICY_ID = "NPC-SINGLE-ADMIN-RECOVERY"
GAP_ID = "GAP-008"
GOAL_REL = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-npc-single-admin-recovery-r001.md"
)
EXPECTED_GOAL_SHA256 = (
    "234a224883779208ba7878a9865076083bb9cfd205dfdb7a760b043f7af6b16d"
)
EXPECTED_START_EVENT_SEQUENCE = 60
EXPECTED_START_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-SINGLE-ADMIN-RECOVERY-20260812-006"
)
EXPECTED_START_EVENT_SHA256 = (
    "b3a6482c8ea951df03e820faab36604f5694527f5f462ede7f1b406cdffa4998"
)
START_GATE_RECEIPT_REL = Path(
    "docs/control/execution/goal-gates/"
    f"{EXPECTED_START_EVENT_ID}/implementation-start-gate-receipt.json"
)
EXPECTED_START_GATE_RECEIPT_SHA256 = (
    "f2c2464b9d2719eab6b6716bda05a00dc20dea236487b10b858b9e0d20f56a76"
)
START_GATE_REPOSITORY_STATE_REL = START_GATE_RECEIPT_REL.parent / "08-REPOSITORY_STATE.log"
EXPECTED_START_GATE_REPOSITORY_STATE_SHA256 = (
    "d4ed0d9fcf5d32363f58d4b425169b2d76b6c3f31dc292a20b324820ab8d7319"
)
CHECKPOINT_REL = Path("docs/control/walksafe-project-continuation-checkpoint.json")

RESULT_DIR_REL = Path("docs/control/execution/goal-results") / GOAL_ID
CORRECTION_DIR_REL = RESULT_DIR_REL / "verification-correction-v2"
# Legacy v1 aliases remain stable for already-published builders and validators.
OBSERVATION_MANIFEST_REL = RESULT_DIR_REL / "verification-observations.json"
IMPLEMENTATION_REL = RESULT_DIR_REL / "implementation-record.json"
VERIFICATION_REL = RESULT_DIR_REL / "verification-result.json"
SUCCESSOR_REL = RESULT_DIR_REL / "successor-trace.json"
REVIEW_SUBJECT_REL = RESULT_DIR_REL / "review-subject.json"
V2_OBSERVATION_MANIFEST_REL = CORRECTION_DIR_REL / "verification-observations-v2.json"
V2_IMPLEMENTATION_REL = CORRECTION_DIR_REL / "implementation-record-v2.json"
V2_VERIFICATION_REL = CORRECTION_DIR_REL / "verification-result-v2.json"
V2_SUCCESSOR_REL = CORRECTION_DIR_REL / "successor-trace-v2.json"
V2_REVIEW_SUBJECT_REL = CORRECTION_DIR_REL / "review-subject-v2.json"
SUPERSEDED_V1_OBSERVATION_REL = verification_runner.V1_OBSERVATION_MANIFEST_REL
SUPERSEDED_V1_OBSERVATION_SHA256 = (
    verification_runner.SUPERSEDED_V1_OBSERVATION_SHA256
)

# The v2 producer contains this consumer in its complete execution closure.
# Therefore its current byte hash is intentionally call-local, never patched
# back into this source (which would create a hash cycle).  The fixed pin below
# is used only for the immutable v1 predecessor.
PENDING_OBSERVATION_MANIFEST_SHA256 = "PENDING_PRODUCT_VERIFICATION_MANIFEST_SHA256"
EXPECTED_OBSERVATION_MANIFEST_SHA256: str | None = None
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

EXPECTED_LANE_IDS = (
    "ADMIN_ANDROID_UNIT",
    "ADMIN_ANDROID_ASSEMBLE_LINT",
    "BACKEND_RECOVERY_PYTEST",
    "RECOVERY_GATE_CLI_PYTEST",
)
FORBIDDEN_SOURCE_FRAGMENTS = (
    "build_walksafe_npc_single_admin_recovery_",
    "build_walksafe_phase1_exact257_successor_r015_",
    "apply_walksafe_npc_single_admin_recovery_goal_completed_",
    "check_walksafe_goal_graph_",
    "check_walksafe_project_continuation_",
    "daylog/",
    "docs/control/",
    "docs/deliverables/",
    "legacy1/",
    "legacy2/",
    "legacy3/",
    "web/",
)


@dataclass(frozen=True)
class LaneSpec:
    lane_id: str
    receipt_rel: Path


LANES = tuple(
    LaneSpec(
        lane_id,
        CORRECTION_DIR_REL / "lanes" / f"{lane_id.lower()}-v2.json",
    )
    for lane_id in EXPECTED_LANE_IDS
)
LANE_BY_ID = {lane.lane_id: lane for lane in LANES}

GAP_R026_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260812-r026.json"
)
BACKLOG_R026_REL = Path(
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260812-r026.json"
)
GAP_R027_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260813-r027.json"
)
BACKLOG_R027_REL = Path(
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260813-r027.json"
)
EXACT6_CONSUMERS = (
    ("ARTIFACT_CHANGE_LOG", Path("docs/deliverables/00-control/artifact-change-log.json")),
    ("ARTIFACT_REGISTER", Path("docs/deliverables/00-control/artifact-register.json")),
    ("REQUIREMENTS_TRACEABILITY", Path("docs/deliverables/03-requirements/rtm.json")),
    ("DESIGN_TRACEABILITY", Path("docs/deliverables/04-design/design-traceability-register.json")),
    ("IMPLEMENTATION_MANIFEST", Path("docs/deliverables/05-implementation/implementation-manifest.json")),
    ("MODULE_REGISTER", Path("docs/deliverables/05-implementation/module-register.json")),
)
R015_DIR_REL = Path(
    "docs/control/execution/artifact-closure/run-20260727-001/packets/"
    "phase1-exact257-successor-r015"
)
R015_CONSUMERS = (
    ("EXACT257_R015_LEDGER", R015_DIR_REL / "phase1-exact257-successor-ledger-r015.json"),
    ("EXACT257_R015_EVIDENCE", R015_DIR_REL / "evidence.json"),
    ("EXACT257_R015_CHECK_RECEIPT", R015_DIR_REL / "phase1-exact257-successor-check-receipt-r015.json"),
)
R016_DIR_REL = Path(
    "docs/control/execution/artifact-closure/run-20260727-001/packets/"
    "phase1-exact257-successor-r016"
)
R016_CONSUMERS = (
    ("EXACT257_R016_LEDGER", R016_DIR_REL / "phase1-exact257-successor-ledger-r016.json"),
    ("EXACT257_R016_EVIDENCE", R016_DIR_REL / "evidence.json"),
    ("EXACT257_R016_CHECK_RECEIPT", R016_DIR_REL / "phase1-exact257-successor-check-receipt-r016.json"),
)
R001_REJECTED_REVIEW_REL = (
    RESULT_DIR_REL / "review-rounds" / "R001" / "review-result.json"
)
CONSUMER_SPECS = (
    ("GAP_R027", GAP_R027_REL),
    ("BACKLOG_R027", BACKLOG_R027_REL),
    *EXACT6_CONSUMERS,
    *R016_CONSUMERS,
    ("REJECTED_REVIEW_R001", R001_REJECTED_REVIEW_REL),
)


class BuildError(RuntimeError):
    """Raised when evidence would be ambiguous or over-credit completion."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BuildError(message)


def bytes_sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def object_sha256(value: Any) -> str:
    return bytes_sha256(canonical_json_bytes(value))


def json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n"


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def strict_json_bytes(raw: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicate_pairs)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BuildError(f"invalid JSON for {label}: {exc}") from exc
    require(type(value) is dict, f"JSON object required: {label}")
    return value


def _parse_time(value: Any, label: str) -> datetime:
    require(type(value) is str and value, f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise BuildError(f"{label} is not ISO-8601") from exc
    require(parsed.tzinfo is not None, f"{label} must include timezone")
    return parsed


def _seal(document: Mapping[str, Any], field: str) -> dict[str, Any]:
    value = deepcopy(dict(document))
    value.pop(field, None)
    value[field] = object_sha256(value)
    return value


def verify_seal(document: Mapping[str, Any], field: str, label: str) -> None:
    value = deepcopy(dict(document))
    observed = value.pop(field, None)
    require(type(observed) is str and observed == object_sha256(value), f"{label} seal differs")


def completion_boundary() -> dict[str, Any]:
    return {
        "scope": "REPOSITORY_INTERNAL_NPC_SINGLE_ADMIN_RECOVERY_IMPLEMENTATION_AND_AUTOMATED_VERIFICATION_ONLY",
        "planned_test_ids": ["TC-NPC-SINGLE-ADMIN-RECOVERY-01"],
        "formal_test_status": "NOT_RUN",
        "formal_test_credit_count": 0,
        "actual_device_status": "NOT_RUN",
        "actual_device_credit_count": 0,
        "actual_recovery_drill_status": "NOT_RUN",
        "external_security_review_status": "NOT_RUN",
        "external_legal_review_status": "NOT_RUN",
        "external_accessibility_review_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_gate_status": "NOT_RUN",
        "release_gates_waived": False,
        "release_credit_count": 0,
        "release_status": "NOT_ELIGIBLE",
        "artifact_approval_claimed": False,
    }


def require_resolved_observation_pin(value: str) -> None:
    require(
        value != PENDING_OBSERVATION_MANIFEST_SHA256 and bool(SHA256_RE.fullmatch(value)),
        "unresolved product verification input: seal the exact verification-observations.json SHA-256 before --write-results or --check-results",
    )


def _validate_source_path(path: str) -> None:
    require(path and not path.startswith("/"), f"source path must be repository-relative: {path}")
    require(".." not in Path(path).parts, f"source path escapes repository: {path}")
    allowed = (
        "apps/android/adminapp/",
        "backend/",
        "deploy/",
        "docs/guides/",
        "scripts/",
        "tests/",
    )
    require(
        path.startswith(allowed) or path == "contracts/walksafe.openapi.json",
        f"source path is outside NPC implementation scope: {path}",
    )
    require(
        not any(fragment in path for fragment in FORBIDDEN_SOURCE_FRAGMENTS),
        f"control, legacy, or completion-pipeline source is forbidden: {path}",
    )


def validate_start_authority(root: Path) -> datetime:
    goal_raw = read_bytes(root, GOAL_REL)
    require(bytes_sha256(goal_raw) == EXPECTED_GOAL_SHA256, "goal contract SHA-256 differs")

    receipt_raw = read_bytes(root, START_GATE_RECEIPT_REL)
    require(
        bytes_sha256(receipt_raw) == EXPECTED_START_GATE_RECEIPT_SHA256,
        "implementation start gate receipt SHA-256 differs",
    )
    receipt = strict_json_bytes(receipt_raw, START_GATE_RECEIPT_REL.as_posix())
    require(receipt.get("status") == "PASS", "implementation start gate did not pass")
    require(
        receipt.get("target_transition_event_id") == EXPECTED_START_EVENT_ID,
        "implementation start gate event differs",
    )
    require(receipt.get("target_goal_id") == GOAL_ID, "implementation start gate goal differs")
    require(
        receipt.get("target_goal_content_sha256") == EXPECTED_GOAL_SHA256,
        "implementation start gate goal binding differs",
    )

    repository_state_raw = read_bytes(root, START_GATE_REPOSITORY_STATE_REL)
    require(
        bytes_sha256(repository_state_raw)
        == EXPECTED_START_GATE_REPOSITORY_STATE_SHA256,
        "implementation start repository-state SHA-256 differs",
    )
    repository_snapshot = receipt.get("repository_snapshot")
    require(type(repository_snapshot) is dict, "implementation start repository snapshot missing")
    require(
        repository_snapshot.get("gate_event_id") == EXPECTED_START_EVENT_ID
        and repository_snapshot.get("gate_repository_state_output_sha256")
        == EXPECTED_START_GATE_REPOSITORY_STATE_SHA256,
        "implementation start repository snapshot binding differs",
    )

    checkpoint = strict_json_bytes(read_bytes(root, CHECKPOINT_REL), CHECKPOINT_REL.as_posix())
    goal_execution = checkpoint.get("goal_execution")
    require(type(goal_execution) is dict, "checkpoint goal execution missing")
    history = goal_execution.get("transition_history")
    require(type(history) is list, "checkpoint transition history missing")
    candidates = [
        row
        for row in history
        if type(row) is dict and row.get("sequence") == EXPECTED_START_EVENT_SEQUENCE
    ]
    require(len(candidates) == 1, "checkpoint start event inventory differs")
    event = candidates[0]
    verify_seal(event, "event_sha256", "checkpoint start event")
    require(
        event.get("event_id") == EXPECTED_START_EVENT_ID
        and event.get("event_type") == "GOAL_STARTED"
        and event.get("subject_goal_id") == GOAL_ID
        and event.get("from_status") == "READY"
        and event.get("to_status") == "IN_PROGRESS"
        and event.get("event_sha256") == EXPECTED_START_EVENT_SHA256,
        "checkpoint start event binding differs",
    )
    require(
        event.get("implementation_start_gate_binding")
        == {
            "document_id": receipt.get("document_id"),
            "path": START_GATE_RECEIPT_REL.as_posix(),
            "file_sha256": EXPECTED_START_GATE_RECEIPT_SHA256,
        },
        "checkpoint start gate binding differs",
    )
    status_by_goal = goal_execution.get("status_by_goal")
    require(type(status_by_goal) is dict, "checkpoint goal status map missing")
    require(
        status_by_goal.get(GOAL_ID)
        in {"IN_PROGRESS", "COMPLETED", "COMPLETE_AT_TARGET"},
        "checkpoint goal was not started",
    )
    return _parse_time(event.get("occurred_at"), "checkpoint start event occurred_at")


def validate_observations(
    observation: Mapping[str, Any],
    observation_raw: bytes,
    *,
    root: Path,
    expected_sha256: str | None = None,
    not_before: datetime | None = None,
    allow_cached_toolchain: bool = True,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    observed_sha256 = bytes_sha256(observation_raw)
    if expected_sha256 is not None:
        require_resolved_observation_pin(expected_sha256)
        require(
            observed_sha256 == expected_sha256,
            "verification observation manifest SHA-256 differs",
        )
    require(
        observation_raw == json_text(observation).encode("utf-8"),
        "verification observation manifest is noncanonical",
    )
    if observation.get("schema_version") == "1.0":
        raise BuildError("v1 verification evidence is superseded and cannot authorize promotion")
    require(
        set(observation)
        == {
            "schema_version",
            "goal_id",
            "run_id",
            "observed_at",
            "source_content_set_sha256",
            "lanes",
            "source_files",
            "execution_input_closure",
            "runner_toolchain_receipt",
            "lane_environment_receipt",
            "database_preflight_receipt",
            "database_runtime_receipt",
            "execution_source_boundary",
            "correction",
            "completion_boundary",
        },
        "observation manifest fields differ",
    )
    require(
        observation.get("schema_version") == verification_runner.OBSERVATION_SCHEMA,
        "observation schema differs; only v2 correction evidence may promote",
    )
    require(observation.get("goal_id") == GOAL_ID, "observation goal differs")
    require(
        observation.get("execution_source_boundary")
        == verification_runner.execution_source_boundary(),
        "observation execution source boundary differs",
    )
    require(
        verification_runner.completion_boundary() == completion_boundary()
        and observation.get("completion_boundary") == completion_boundary(),
        "observation credit boundary differs",
    )
    run_id = observation.get("run_id")
    require(
        type(run_id) is str and verification_runner.RUN_ID_RE.fullmatch(run_id) is not None,
        "observation run ID differs",
    )
    source_set = observation.get("source_content_set_sha256")
    require(
        type(source_set) is str and SHA256_RE.fullmatch(source_set) is not None,
        "observation source content-set SHA-256 differs",
    )
    correction = observation.get("correction")
    require(type(correction) is dict, "v2 correction binding is missing")
    v1_raw = read_bytes(root, verification_runner.V1_OBSERVATION_MANIFEST_REL)
    require(
        len(v1_raw) == verification_runner.SUPERSEDED_V1_OBSERVATION_BYTE_COUNT
        and bytes_sha256(v1_raw)
        == verification_runner.SUPERSEDED_V1_OBSERVATION_SHA256,
        "fixed v1 predecessor byte binding differs",
    )
    require(
        correction.get("kind") == "ADDITIVE_SUPERSESSION"
        and correction.get("supersedes_schema") == "1.0"
        and correction.get("supersedes_path")
        == verification_runner.V1_OBSERVATION_MANIFEST_REL.as_posix()
        and correction.get("v1_must_remain_add_only") is True
        and correction.get("promotion_authority") == "V2_ONLY"
        and correction.get("superseded_evidence_binding")
        == {
            "schema_version": "1.0",
            "path": verification_runner.V1_OBSERVATION_MANIFEST_REL.as_posix(),
            "byte_count": verification_runner.SUPERSEDED_V1_OBSERVATION_BYTE_COUNT,
            "sha256": verification_runner.SUPERSEDED_V1_OBSERVATION_SHA256,
        },
        "v1 supersession binding differs",
    )

    closure = observation.get("execution_input_closure")
    require(type(closure) is dict, "execution input closure is missing")
    try:
        current_bindings = verification_runner._capture_visible_inventory(root)
        current_closure = verification_runner.execution_input_closure(current_bindings)
    except verification_runner.VerificationError as exc:
        raise BuildError(f"execution input closure cannot be recaptured: {exc}") from exc
    require(
        closure == current_closure,
        "execution input closure differs (path, mode, byte count, or SHA-256 drift)",
    )

    environment_receipt = observation.get("lane_environment_receipt")
    require(type(environment_receipt) is dict, "lane environment receipt is missing")
    bindings = environment_receipt.get("non_secret_value_bindings")
    require(type(bindings) is list, "lane environment bindings are missing")
    names = [row.get("name") for row in bindings if type(row) is dict]
    require(
        len(names) == len(bindings)
        and names == sorted(set(names))
        and all(
            set(row) == {"name", "value_sha256"}
            and type(row.get("name")) is str
            and type(row.get("value_sha256")) is str
            and SHA256_RE.fullmatch(row["value_sha256"])
            for row in bindings
        )
        and not {
            "GRADLE_OPTS",
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "PYTEST_ADDOPTS",
            "PYTEST_PLUGINS",
            "PYTHONPATH",
            "PYTHONSTARTUP",
        }.intersection(names)
        and environment_receipt.get("database_url")
        == {
            "source_name": "WALKSAFE_TEST_DATABASE_URL",
            "logical_reference": verification_runner.DATABASE_URL_REFERENCE,
            "value_recorded": False,
        },
        "lane environment receipt differs",
    )

    toolchain_receipt = observation.get("runner_toolchain_receipt")
    require(type(toolchain_receipt) is dict, "runner/toolchain receipt is missing")
    python_binding = toolchain_receipt.get("python")
    require(type(python_binding) is dict, "Python toolchain binding is missing")
    python_invocation_path = python_binding.get("invocation_path")
    python_path = python_binding.get("resolved_path")
    require(
        type(python_invocation_path) is str
        and python_invocation_path
        and type(python_path) is str
        and python_path,
        "Python toolchain path is missing",
    )
    try:
        require(
            Path(python_invocation_path).resolve(strict=True) == Path(python_path),
            "Python invocation and executable target differ",
        )
    except OSError as exc:
        raise BuildError("Python toolchain path cannot be resolved") from exc
    try:
        expected_toolchain = verification_runner._toolchain_receipt(
            root,
            closure,
            python_invocation_path,
            allow_cached=allow_cached_toolchain,
        )
    except (OSError, verification_runner.VerificationError) as exc:
        raise BuildError(f"runner/toolchain binding cannot be recaptured: {exc}") from exc
    require(
        toolchain_receipt == expected_toolchain,
        "runner/toolchain binding differs",
    )
    environment_receipt_sha256 = bytes_sha256(
        verification_runner.canonical_json_bytes(environment_receipt)
    )
    toolchain_receipt_sha256 = bytes_sha256(
        verification_runner.canonical_json_bytes(toolchain_receipt)
    )

    database_receipt = observation.get("database_preflight_receipt")
    require(
        type(database_receipt) is dict
        and database_receipt.get("schema")
        == "walksafe.npc-database-preflight-receipt.v2"
        and database_receipt.get("lane_id") == "BACKEND_RECOVERY_PYTEST"
        and database_receipt.get("status") == "PASS"
        and database_receipt.get("database_url_recorded") is False
        and database_receipt.get("database_url_logical_reference")
        == verification_runner.DATABASE_URL_REFERENCE
        and type(database_receipt.get("redacted_output_sha256")) is str
        and SHA256_RE.fullmatch(database_receipt["redacted_output_sha256"]),
        "database preflight receipt differs",
    )
    database_receipt_sha256 = bytes_sha256(
        verification_runner.canonical_json_bytes(database_receipt)
    )
    database_runtime_receipt = observation.get("database_runtime_receipt")
    require(
        type(database_runtime_receipt) is dict,
        "database runtime state receipt is missing",
    )
    try:
        verification_runner._validate_database_runtime_chain(
            database_runtime_receipt,
            read_bytes(
                root,
                verification_runner.LANE_SPEC_BY_ID[
                    "BACKEND_RECOVERY_PYTEST"
                ].log_relative,
            ),
        )
    except verification_runner.VerificationError as exc:
        raise BuildError(f"database runtime state receipt differs: {exc}") from exc
    database_runtime_receipt_sha256 = bytes_sha256(
        verification_runner.canonical_json_bytes(database_runtime_receipt)
    )
    lanes = observation.get("lanes")
    require(type(lanes) is list, "observation lanes missing")
    by_id = {row.get("lane_id"): row for row in lanes if type(row) is dict}
    require(
        [row.get("lane_id") for row in lanes if type(row) is dict]
        == list(EXPECTED_LANE_IDS)
        and tuple(by_id) == EXPECTED_LANE_IDS
        and len(lanes) == len(by_id),
        "observation lane inventory differs",
    )
    normalized_lanes: list[dict[str, Any]] = []
    lane_end_times: list[datetime] = []
    previous_end = not_before
    for lane_id in EXPECTED_LANE_IDS:
        row = deepcopy(by_id[lane_id])
        require(row.get("run_id") == run_id, f"lane run ID differs: {lane_id}")
        require(
            row.get("source_content_set_sha256") == source_set,
            f"lane source content-set differs: {lane_id}",
        )
        require(
            row.get("execution_input_content_set_sha256")
            == closure.get("content_set_sha256")
            and row.get("environment_receipt_sha256")
            == environment_receipt_sha256
            and row.get("toolchain_receipt_sha256")
            == toolchain_receipt_sha256
            and row.get("database_preflight_receipt_sha256")
            == database_receipt_sha256
            and row.get("database_runtime_receipt_sha256")
            == database_runtime_receipt_sha256,
            f"lane execution receipt binding differs: {lane_id}",
        )
        log_path = row.get("log_path")
        require(type(log_path) is str, f"lane log path missing: {lane_id}")
        raw = read_bytes(root, Path(log_path))
        try:
            verification_runner.validate_lane_observation(row, raw)
        except verification_runner.VerificationError as exc:
            raise BuildError(f"invalid verification lane {lane_id}: {exc}") from exc
        started_at = _parse_time(row.get("started_at"), f"{lane_id}.started_at")
        ended_at = _parse_time(row.get("ended_at"), f"{lane_id}.ended_at")
        require(
            previous_end is None or started_at >= previous_end,
            f"verification lane timeline overlaps or predates its authority: {lane_id}",
        )
        previous_end = ended_at
        lane_end_times.append(ended_at)
        normalized_lanes.append(row)

    files = observation.get("source_files")
    require(type(files) is list and files, "implementation source manifest missing")
    execution_paths = tuple(
        row["path"]
        for row in closure["files"]
        if type(row) is dict and type(row.get("path")) is str
    )
    try:
        expected_product_paths = verification_runner.product_source_paths_for_inventory(
            execution_paths
        )
    except verification_runner.VerificationError as exc:
        raise BuildError(f"implementation source contract differs: {exc}") from exc
    require(
        [row.get("path") for row in files if type(row) is dict]
        == list(expected_product_paths)
        and len(files) == len(expected_product_paths),
        "implementation source inventory differs",
    )
    normalized_files: list[dict[str, Any]] = []
    seen: set[str] = set()
    for row in files:
        require(type(row) is dict and type(row.get("path")) is str, "source file row malformed")
        path = row["path"]
        _validate_source_path(path)
        require(path not in seen, f"duplicate source path: {path}")
        seen.add(path)
        raw = read_bytes(root, Path(path))
        require(row.get("sha256") == bytes_sha256(raw), f"source SHA-256 differs: {path}")
        require(row.get("byte_count") == len(raw), f"source byte count differs: {path}")
        normalized_files.append(deepcopy(row))
    require([row["path"] for row in normalized_files] == sorted(seen), "source files must be path-sorted")
    try:
        calculated_source_set = verification_runner.source_content_set_sha256(normalized_files)
    except verification_runner.VerificationError as exc:
        raise BuildError(f"invalid implementation source manifest: {exc}") from exc
    require(
        source_set == calculated_source_set,
        "observation source content-set SHA-256 differs",
    )
    observed_at = _parse_time(observation.get("observed_at"), "observed_at")
    require(
        observed_at.utcoffset() == verification_runner.KST_OFFSET,
        "observed_at must use Asia/Seoul offset",
    )
    require(
        bool(lane_end_times) and observed_at >= max(lane_end_times),
        "observation time precedes lane completion",
    )
    return normalized_lanes, normalized_files, deepcopy(closure)


def build_results_outputs(
    observation: Mapping[str, Any],
    observation_raw: bytes,
    *,
    root: Path,
    expected_sha256: str | None = None,
    allow_cached_toolchain: bool = True,
) -> dict[Path, str]:
    implementation_started_after = validate_start_authority(root)
    lanes, files, execution_closure = validate_observations(
        observation,
        observation_raw,
        root=root,
        expected_sha256=expected_sha256,
        not_before=implementation_started_after,
        allow_cached_toolchain=allow_cached_toolchain,
    )
    manifest = {
        "files": files,
        "file_count": len(files),
        "path_set_sha256": object_sha256([row["path"] for row in files]),
        "content_set_sha256": object_sha256(
            [{"path": row["path"], "sha256": row["sha256"]} for row in files]
        ),
    }
    manifest["manifest_content_sha256"] = object_sha256(manifest)
    observed_at = observation.get("observed_at")
    _parse_time(observed_at, "observed_at")
    receipts: dict[Path, str] = {}
    for row in lanes:
        lane = LANE_BY_ID[row["lane_id"]]
        receipt = _seal(
            {
                "schema_version": "1.0",
                "document_id": f"WS-NPC-SINGLE-ADMIN-RECOVERY-{lane.lane_id}-CORRECTION-RECEIPT-20260813-002",
                "evidence_type": "INTERNAL_VERIFICATION_LANE_RECEIPT",
                "evidence_schema": "V2_CORRECTION_ONLY",
                "goal_id": GOAL_ID,
                "status": "PASS",
                "observation": row,
                "raw_output_binding": {
                    "path": row["log_path"],
                    "byte_length": row["log_byte_count"],
                    "sha256": row["log_sha256"],
                },
                "completion_boundary": completion_boundary(),
            },
            "receipt_content_sha256",
        )
        receipts[lane.receipt_rel] = json_text(receipt)

    implementation = _seal(
        {
            "schema_version": "1.0",
            "document_id": "WS-NPC-SINGLE-ADMIN-RECOVERY-IMPLEMENTATION-CORRECTION-20260813-002",
            "kind": "IMPLEMENTATION_RECORD",
            "evidence_schema": "V2_CORRECTION_ONLY",
            "goal_id": GOAL_ID,
            "work_item_id": WORK_ITEM_ID,
            "policy_id": POLICY_ID,
            "gap_id": GAP_ID,
            "status": "PASS_INTERNAL",
            "observed_at": observed_at,
            "scope_kind": "EXACT_ORDERED_NPC_SINGLE_ADMIN_RECOVERY_FINAL_CONTENT_MANIFEST",
            "final_content_manifest": manifest,
            "execution_input_closure": execution_closure,
            "execution_source_boundary": verification_runner.execution_source_boundary(),
            "verification_observation_binding": {
                "path": V2_OBSERVATION_MANIFEST_REL.as_posix(),
                "sha256": bytes_sha256(observation_raw),
                "schema_version": verification_runner.OBSERVATION_SCHEMA,
            },
            "superseded_v1_binding": deepcopy(
                observation["correction"]["superseded_evidence_binding"]
            ),
            "implemented_controls": [
                "ADDITIONAL_AUTHENTICATION_OR_PASSKEY_NO_SHARED_BYPASS",
                "OFF_DEVICE_RECOVERY_CUSTODY_FAIL_CLOSED",
                "REMOTE_LOST_DEVICE_SESSION_REVOCATION_AND_REUSE_REJECTION",
                "HIGH_RISK_ACTION_FREEZE_UNTIL_RECOVERY_AND_REAUTHENTICATION",
                "SEPARATED_KEY_AND_RECOVERY_MATERIAL_CUSTODY",
                "RECOVERY_REVOCATION_FREEZE_AND_REAUTHENTICATION_AUDIT",
            ],
            "execution_start_event": {
                "sequence": EXPECTED_START_EVENT_SEQUENCE,
                "event_id": EXPECTED_START_EVENT_ID,
                "event_sha256": EXPECTED_START_EVENT_SHA256,
            },
            "implementation_start_gate_binding": {
                "path": START_GATE_RECEIPT_REL.as_posix(),
                "file_sha256": EXPECTED_START_GATE_RECEIPT_SHA256,
            },
            "completion_boundary": completion_boundary(),
        },
        "implementation_record_content_sha256",
    )
    implementation_raw = json_text(implementation).encode("utf-8")
    verification = _seal(
        {
            "schema_version": "1.0",
            "document_id": "WS-NPC-SINGLE-ADMIN-RECOVERY-VERIFICATION-CORRECTION-20260813-002",
            "kind": "VERIFICATION_RESULT",
            "evidence_schema": "V2_CORRECTION_ONLY",
            "goal_id": GOAL_ID,
            "status": "PASS_INTERNAL",
            "observed_at": observed_at,
            "implementation_record_sha256": bytes_sha256(implementation_raw),
            "implementation_content_set_sha256": manifest["content_set_sha256"],
            "execution_input_content_set_sha256": execution_closure[
                "content_set_sha256"
            ],
            "execution_source_boundary": verification_runner.execution_source_boundary(),
            "verification_observation_binding": {
                "path": V2_OBSERVATION_MANIFEST_REL.as_posix(),
                "sha256": bytes_sha256(observation_raw),
                "schema_version": verification_runner.OBSERVATION_SCHEMA,
            },
            "internal_lane_count": len(lanes),
            "lane_receipts": [
                {
                    "lane_id": lane.lane_id,
                    "path": lane.receipt_rel.as_posix(),
                    "sha256": bytes_sha256(receipts[lane.receipt_rel].encode("utf-8")),
                }
                for lane in LANES
            ],
            "completion_boundary": completion_boundary(),
        },
        "verification_result_content_sha256",
    )
    return {
        **receipts,
        V2_IMPLEMENTATION_REL: json_text(implementation),
        V2_VERIFICATION_REL: json_text(verification),
    }


def _document_id(value: Mapping[str, Any], relative: Path) -> str:
    metadata = value.get("metadata")
    for candidate in (
        value.get("document_id"),
        metadata.get("document_id") if type(metadata) is dict else None,
        metadata.get("register_id") if type(metadata) is dict else None,
        metadata.get("report_id") if type(metadata) is dict else None,
        metadata.get("backlog_id") if type(metadata) is dict else None,
        value.get("ledger_id"),
        value.get("packet_id"),
        value.get("receipt_id"),
    ):
        if type(candidate) is str and candidate:
            return candidate
    artifact_ids = metadata.get("artifact_type_ids") if type(metadata) is dict else None
    if type(artifact_ids) is list and artifact_ids:
        return str(artifact_ids[0])
    return relative.as_posix()


def _deep_validate_producers(
    root: Path,
    implementation_raw: bytes,
    verification_raw: bytes,
    *,
    allow_cached_toolchain: bool = True,
) -> None:
    observation_raw = read_bytes(root, V2_OBSERVATION_MANIFEST_REL)
    observation = strict_json_bytes(
        observation_raw, V2_OBSERVATION_MANIFEST_REL.as_posix()
    )
    rebuilt = build_results_outputs(
        observation,
        observation_raw,
        root=root,
        allow_cached_toolchain=allow_cached_toolchain,
    )
    require(
        rebuilt[V2_IMPLEMENTATION_REL].encode("utf-8") == implementation_raw
        and rebuilt[V2_VERIFICATION_REL].encode("utf-8") == verification_raw,
        "producer results do not reproduce from the live v2 observation",
    )


def _deep_validate_consumers(
    root: Path, consumer_raw_by_path: Mapping[Path, bytes]
) -> None:
    # Runtime imports avoid a module initialization cycle: these builders use
    # this trace module as their v2 producer API.
    from scripts import (  # noqa: PLC0415
        build_walksafe_npc_single_admin_recovery_artifact_trace_correction_v2_20260813
        as artifact_v2,
    )
    from scripts import (  # noqa: PLC0415
        build_walksafe_npc_single_admin_recovery_gap_backlog_r027_20260813 as r027,
    )
    from scripts import (  # noqa: PLC0415
        build_walksafe_npc_single_admin_recovery_strict_review_gate_20260812 as review,
    )
    from scripts import (  # noqa: PLC0415
        build_walksafe_phase1_exact257_successor_r016_20260813 as r016,
    )

    rebuilt_r027 = r027.build_outputs(root)
    for relative in (GAP_R027_REL, BACKLOG_R027_REL):
        require(
            rebuilt_r027[relative].encode("utf-8") == consumer_raw_by_path[relative],
            f"successor R027 consumer does not reproduce: {relative}",
        )
    rebuilt_artifacts = artifact_v2.build_outputs(root)
    for _, relative in EXACT6_CONSUMERS:
        require(
            rebuilt_artifacts[relative].encode("utf-8")
            == consumer_raw_by_path[relative],
            f"successor exact-six consumer does not reproduce: {relative}",
        )
    rebuilt_r016 = r016.build_outputs(root)
    for _, relative in R016_CONSUMERS:
        require(
            rebuilt_r016[relative] == consumer_raw_by_path[relative],
            f"successor R016 consumer does not reproduce: {relative}",
        )
    predecessor = review._load_predecessor_review(root)
    review_raw = consumer_raw_by_path[R001_REJECTED_REVIEW_REL]
    require(
        predecessor.get("decision") == "REJECTED"
        and predecessor.get("sha256") == bytes_sha256(review_raw),
        "successor R001 rejection does not reproduce",
    )


def build_successor_outputs(
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    *,
    implementation_raw: bytes,
    verification_raw: bytes,
    consumer_raw_by_path: Mapping[Path, bytes],
    observed_at: str,
    root: Path = ROOT,
    allow_cached_toolchain: bool = True,
) -> dict[Path, str]:
    verify_seal(implementation, "implementation_record_content_sha256", "implementation record")
    verify_seal(verification, "verification_result_content_sha256", "verification result")
    require(
        implementation.get("evidence_schema") == "V2_CORRECTION_ONLY"
        and verification.get("evidence_schema") == "V2_CORRECTION_ONLY",
        "v1 producer results cannot authorize a v2 successor",
    )
    require(implementation_raw == json_text(implementation).encode("utf-8"), "implementation record is noncanonical")
    require(verification_raw == json_text(verification).encode("utf-8"), "verification result is noncanonical")
    _deep_validate_producers(
        root,
        implementation_raw,
        verification_raw,
        allow_cached_toolchain=allow_cached_toolchain,
    )
    require(implementation.get("completion_boundary") == completion_boundary(), "implementation boundary differs")
    require(verification.get("completion_boundary") == completion_boundary(), "verification boundary differs")
    require(set(consumer_raw_by_path) == {path for _, path in CONSUMER_SPECS}, "successor consumer inventory differs")
    _deep_validate_consumers(root, consumer_raw_by_path)
    successor_observed_at = _parse_time(observed_at, "successor observed_at")
    implementation_observed_at = _parse_time(
        implementation.get("observed_at"), "implementation observed_at"
    )
    verification_observed_at = _parse_time(
        verification.get("observed_at"), "verification observed_at"
    )
    require(
        implementation_observed_at == verification_observed_at
        and successor_observed_at >= verification_observed_at,
        "successor timeline predates producer results",
    )
    bindings: list[dict[str, Any]] = []
    for role, relative in CONSUMER_SPECS:
        raw = consumer_raw_by_path[relative]
        document = strict_json_bytes(raw, relative.as_posix())
        bindings.append(
            {
                "role": role,
                "path": relative.as_posix(),
                "document_id": _document_id(document, relative),
                "sha256": bytes_sha256(raw),
                "byte_length": len(raw),
            }
        )
    require(
        len(bindings) == len(CONSUMER_SPECS),
        "successor must bind the exact v2 downstream consumer set",
    )
    producer_results = {
        "IMPLEMENTATION_RECORD": {
            "path": V2_IMPLEMENTATION_REL.as_posix(),
            "sha256": bytes_sha256(implementation_raw),
        },
        "VERIFICATION_RESULT": {
            "path": V2_VERIFICATION_REL.as_posix(),
            "sha256": bytes_sha256(verification_raw),
        },
    }
    successor = _seal(
        {
            "schema_version": "1.0",
            "document_id": "WS-NPC-SINGLE-ADMIN-RECOVERY-SUCCESSOR-TRACE-CORRECTION-20260813-002",
            "kind": "SUCCESSOR_TRACE",
            "evidence_schema": "V2_CORRECTION_ONLY",
            "goal_id": GOAL_ID,
            "status": "PASS_INTERNAL",
            "observed_at": observed_at,
            "producer_results": producer_results,
            "downstream_consumer_bindings": bindings,
            "next_single_action": {
                "epic_id": "EPIC-04",
                "source_policy_id": "FP-022",
                "gap_id": "GAP-031",
                "priority_rank": 24,
            },
            "completion_boundary": completion_boundary(),
        },
        "successor_trace_content_sha256",
    )
    successor_raw = json_text(successor).encode("utf-8")
    subject = _seal(
        {
            "schema_version": "1.0",
            "document_id": "WS-NPC-SINGLE-ADMIN-RECOVERY-REVIEW-SUBJECT-CORRECTION-20260813-002",
            "kind": "INTERNAL_REVIEW_SUBJECT",
            "evidence_schema": "V2_CORRECTION_ONLY",
            "goal_id": GOAL_ID,
            "status": "READY_FOR_SEPARATE_INTERNAL_REVIEW",
            "observed_at": observed_at,
            "reviewer_must_be_independent_of_executor": True,
            "reviewed_result_sha256_by_kind": {
                "IMPLEMENTATION_RECORD": bytes_sha256(implementation_raw),
                "VERIFICATION_RESULT": bytes_sha256(verification_raw),
                "SUCCESSOR_TRACE": bytes_sha256(successor_raw),
            },
            "reviewed_consumer_bindings": bindings,
            "completion_boundary": completion_boundary(),
        },
        "review_subject_content_sha256",
    )
    return {
        V2_SUCCESSOR_REL: json_text(successor),
        V2_REVIEW_SUBJECT_REL: json_text(subject),
    }


def read_bytes(root: Path, relative: Path) -> bytes:
    try:
        return io_base.read_bytes(root, relative)
    except io_base.BuildError as exc:
        raise BuildError(str(exc)) from exc


def write_or_check_outputs(root: Path, outputs: Mapping[Path, str], *, write: bool) -> None:
    try:
        io_base.write_or_check_outputs(root, outputs, write=write)
    except io_base.BuildError as exc:
        raise BuildError(str(exc)) from exc


def _guard_results_write_boundary(
    root: Path,
    observation_relative: Path,
    observation_raw: bytes,
    outputs: Mapping[Path, str],
) -> None:
    """Rebuild the exact current closure immediately before add-only write."""

    try:
        current_raw = read_bytes(root, observation_relative)
        require(current_raw == observation_raw, "observation changed")
        current = strict_json_bytes(current_raw, observation_relative.as_posix())
        rebuilt = build_results_outputs(
            current,
            current_raw,
            root=root,
            expected_sha256=bytes_sha256(observation_raw),
            allow_cached_toolchain=False,
        )
        require(dict(rebuilt) == dict(outputs), "rebuilt results differ")
    except (BuildError, OSError, ValueError, TypeError) as exc:
        raise BuildError(
            "results source/execution closure changed at CAS boundary"
        ) from exc


def _guard_successor_write_boundary(
    root: Path,
    *,
    implementation_raw: bytes,
    verification_raw: bytes,
    consumer_raw_by_path: Mapping[Path, bytes],
    observed_at: str,
    outputs: Mapping[Path, str],
) -> None:
    """Rebuild live producers/consumers immediately before successor write."""

    try:
        implementation, live_implementation_raw, verification, live_verification_raw = (
            _load_results(root)
        )
        require(
            live_implementation_raw == implementation_raw
            and live_verification_raw == verification_raw,
            "producer changed",
        )
        live_consumers = {
            path: read_bytes(root, path) for _, path in CONSUMER_SPECS
        }
        require(
            live_consumers == dict(consumer_raw_by_path), "consumer changed"
        )
        rebuilt = build_successor_outputs(
            implementation,
            verification,
            implementation_raw=live_implementation_raw,
            verification_raw=live_verification_raw,
            consumer_raw_by_path=live_consumers,
            observed_at=observed_at,
            root=root,
            allow_cached_toolchain=False,
        )
        require(dict(rebuilt) == dict(outputs), "rebuilt successor differs")
    except (BuildError, OSError, ValueError, TypeError) as exc:
        raise BuildError(
            "successor producer or consumer closure changed at CAS boundary"
        ) from exc


def _load_results(root: Path) -> tuple[dict[str, Any], bytes, dict[str, Any], bytes]:
    implementation_raw = read_bytes(root, V2_IMPLEMENTATION_REL)
    verification_raw = read_bytes(root, V2_VERIFICATION_REL)
    return (
        strict_json_bytes(implementation_raw, V2_IMPLEMENTATION_REL.as_posix()),
        implementation_raw,
        strict_json_bytes(verification_raw, V2_VERIFICATION_REL.as_posix()),
        verification_raw,
    )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument(
        "--observation-manifest",
        type=Path,
        default=V2_OBSERVATION_MANIFEST_REL,
    )
    parser.add_argument("--observed-at")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write-results", action="store_true")
    mode.add_argument("--check-results", action="store_true")
    mode.add_argument("--write-successor", action="store_true")
    mode.add_argument("--check-successor", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    root = args.root.resolve()
    try:
        if args.write_results or args.check_results:
            observation_raw = read_bytes(root, args.observation_manifest)
            observation = strict_json_bytes(observation_raw, args.observation_manifest.as_posix())
            outputs = build_results_outputs(
                observation,
                observation_raw,
                root=root,
            )
            if args.write_results:
                _guard_results_write_boundary(
                    root,
                    args.observation_manifest,
                    observation_raw,
                    outputs,
                )
            write_or_check_outputs(root, outputs, write=args.write_results)
            detail = "RESULTS"
        else:
            require(type(args.observed_at) is str, "--observed-at is required for successor stage")
            validate_start_authority(root)
            implementation, implementation_raw, verification, verification_raw = _load_results(root)
            consumers = {path: read_bytes(root, path) for _, path in CONSUMER_SPECS}
            outputs = build_successor_outputs(
                implementation,
                verification,
                implementation_raw=implementation_raw,
                verification_raw=verification_raw,
                consumer_raw_by_path=consumers,
                observed_at=args.observed_at,
                root=root,
            )
            if args.write_successor:
                _guard_successor_write_boundary(
                    root,
                    implementation_raw=implementation_raw,
                    verification_raw=verification_raw,
                    consumer_raw_by_path=consumers,
                    observed_at=args.observed_at,
                    outputs=outputs,
                )
            write_or_check_outputs(root, outputs, write=args.write_successor)
            detail = "SUCCESSOR"
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"NPC single-admin recovery trace: FAIL: {exc}")
        return 1
    print(f"NPC single-admin recovery trace: PASS stage={detail}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
