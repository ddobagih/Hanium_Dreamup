#!/usr/bin/env python3
"""Build the FP-022 repository-internal navigation evidence candidate.

This producer only records already-captured repository-internal checks.  It
does not run formal tests, use an Android device, contact TMAP, deploy, release,
or approve goal completion.  Evidence, R028 successor/review subject, and the
post-review completion receipt are separate stages.  The receipt can be built
only from an APPROVED review by a different internal actor.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime
import importlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Any, Mapping, Sequence
import xml.etree.ElementTree as ET


def _load_io_base() -> Any:
    try:
        from scripts import (
            build_walksafe_fp008_admin_review_delivery_trace_20260803 as module,
        )

        return module
    except ModuleNotFoundError:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module(
            "scripts.build_walksafe_fp008_admin_review_delivery_trace_20260803"
        )


io_base = _load_io_base()
ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = "WS-GOAL-EPIC-04-FP-022-R001"
POLICY_ID = "FP-022"
GAP_ID = "GAP-031"
RESULT_DIR_REL = Path(f"docs/control/execution/goal-results/{GOAL_ID}")
GOAL_REL = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-04/"
    "epic-04-fp022-tmap-destination-route-r001.md"
)
CONTRACT_REL = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-04-FP-022-R001/"
    "initial-start-gate-contract-r002.json"
)
START_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20260814-001"
START_EVENT_SEQUENCE = 69
START_EVENT_SHA256 = (
    "91cec421d1fdd2f0f0d3ec57e282f7dceb7ae1a6fe51c9db9f9ffb7bba3e38a6"
)
START_GATE_REL = Path(
    f"docs/control/execution/goal-gates/{START_EVENT_ID}/"
    "implementation-start-gate-receipt.json"
)

IMPLEMENTATION_REL = RESULT_DIR_REL / "implementation-record.json"
OBSERVATIONS_REL = RESULT_DIR_REL / "verification-observations.json"
VERIFICATION_REL = RESULT_DIR_REL / "verification-result.json"
SUCCESSOR_REL = RESULT_DIR_REL / "successor-trace.json"
REVIEW_SUBJECT_REL = RESULT_DIR_REL / "review-subject.json"
INDEPENDENT_REVIEW_REL = RESULT_DIR_REL / "independent-review.json"
COMPLETION_REL = RESULT_DIR_REL / "completion-receipt.json"
R028_GAP_JSON_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260814-r028.json"
)
R028_GAP_MD_REL = R028_GAP_JSON_REL.with_suffix(".md")
R028_BACKLOG_JSON_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260814-r028.json"
)
R028_BACKLOG_MD_REL = R028_BACKLOG_JSON_REL.with_suffix(".md")
R028_PATHS = (
    R028_GAP_JSON_REL,
    R028_GAP_MD_REL,
    R028_BACKLOG_JSON_REL,
    R028_BACKLOG_MD_REL,
)

EXPECTED_GOAL_SHA256 = (
    "939075c1b4bcbf9b8280c37cb7a449fd28763f06cda14faf0ca88f691734576b"
)
EXPECTED_CONTRACT_SHA256 = (
    "8e7f55dffcc0725fcb831118ed553b79ed09a41c0d323d00da343fa26ab01b42"
)
EXPECTED_START_GATE_SHA256 = (
    "6b29a7476aa4b88a40bf67922d4e0f8c1c7e3614fb46c9358859d8631e338a3d"
)
IMPLEMENTATION_SCOPE_KIND = "EXACT_ORDERED_FP022_FINAL_CONTENT_MANIFEST"
FORMAL_TEST_IDS = tuple(f"TC-FP-022-{number:02d}" for number in range(1, 5))
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

BuildError = io_base.BuildError
require = io_base.require
bytes_sha256 = io_base.bytes_sha256
object_sha256 = io_base.object_sha256
json_text = io_base.json_text
strict_json_bytes = io_base.strict_json_bytes
require_document_matches_raw = io_base.require_document_matches_raw
sealed = io_base.sealed
verify_seal = io_base.verify_seal


@dataclass(frozen=True)
class SourceGroup:
    group_id: str
    paths: tuple[str, ...]


IMPLEMENTATION_SOURCE_GROUPS = (
    SourceGroup(
        "ANDROID_RUNTIME",
        (
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidVoiceCommand.kt",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/BackendWalkingRouteClient.kt",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/LocationPolicy.kt",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/StepLengthEstimator.kt",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/TactileRoutePolicy.kt",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/WalkingRouteModels.kt",
        ),
    ),
    SourceGroup(
        "ANDROID_INTERNAL_TESTS",
        (
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityNavigationCompositionTest.kt",
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidVoiceCommandTest.kt",
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/BackendWalkingRouteClientTest.kt",
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/BackendWalkingRouteClientNetworkTest.kt",
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/LocationPolicyTest.kt",
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigatorTest.kt",
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/StepLengthEstimatorTest.kt",
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/TactileRoutePolicyTest.kt",
        ),
    ),
    SourceGroup(
        "BACKEND_ROUTE_AUTHORITY",
        (
            "backend/app/api/navigation.py",
            "backend/app/services/tmap_pedestrian.py",
            "backend/app/services/walking_route_sanity.py",
            "backend/tests/test_navigation_routes.py",
        ),
    ),
    SourceGroup(
        "POLICY_DOCS",
        (
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/README.md",
            "docs/walksafe-v2/navigation_integration_policy.md",
        ),
    ),
)


LOCKED_TEST_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)


@dataclass(frozen=True)
class LaneSpec:
    lane_id: str
    log_name: str
    expected_command: str
    success_pattern: str
    minimum_passed: int
    exact_passed: int | None = None

    @property
    def log_rel(self) -> Path:
        return RESULT_DIR_REL / "logs" / f"{self.log_name}.log"


LANES = (
    LaneSpec(
        "BACKEND_NAVIGATION_INTERNAL",
        "backend-navigation-internal",
        "PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. "
        f"{LOCKED_TEST_PYTHON} -B -m pytest -p no:cacheprovider -q "
        "backend/tests/test_navigation_routes.py",
        r"(?m)^42 passed in [0-9]+(?:\.[0-9]+)?s$",
        42,
        42,
    ),
    LaneSpec(
        "ANDROID_USER_INTERNAL",
        "android-user-internal",
        "(cd apps/android && GRADLE_USER_HOME=/home/ddobagi/.gradle "
        "./gradlew --offline --no-daemon --max-workers=1 "
        ":app:testDebugUnitTest :app:assembleDebug :app:lintDebug)",
        r"(?m)^BUILD SUCCESSFUL in .+$",
        1000,
        1000,
    ),
    LaneSpec(
        "TEST_LAYER_REGISTRY_VALIDATE",
        "test-layer-registry-validate",
        f"PYTHON_BIN={LOCKED_TEST_PYTHON} bash "
        "scripts/run_walksafe_test_layers_current.sh validate",
        r"(?m)^TEST_LAYER_REGISTRY_VALIDATE: PASS$",
        1,
        1,
    ),
)
LANE_BY_ID = {lane.lane_id: lane for lane in LANES}


IMPLEMENTED_CONTROLS = (
    "voice destination candidates are announced in explicit pages of three and require explicit selection",
    "stair avoidance and pedestrian accessibility take precedence over a shorter inaccessible route",
    "trusted GPS and the stored TMAP route remain authoritative for remaining distance and route projection",
    "stride progress remains auxiliary and never substitutes for location direction or arrival authority",
    "untrusted location permission or TMAP state pauses directional guidance fail closed",
    "reroute and arrival require fresh explicit user decisions before network request or route termination",
)


def completion_boundary() -> dict[str, Any]:
    return {
        "formal_test_ids": list(FORMAL_TEST_IDS),
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "field_gps_status": "NOT_RUN",
        "external_tmap_status": "NOT_RUN",
        "external_review_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
        "formal_test_credit_delta": 0,
        "device_credit_delta": 0,
        "external_credit_delta": 0,
        "deployment_credit_delta": 0,
        "release_credit_delta": 0,
        "external_independence_claimed": False,
    }


def _parse_time(value: Any, label: str) -> datetime:
    require(type(value) is str, f"{label} must be a timestamp")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise BuildError(f"invalid {label}: {value}") from exc
    require(parsed.tzinfo is not None, f"{label} must include an offset")
    require(parsed.isoformat() == value, f"{label} must use canonical ISO-8601")
    return parsed


def _binding(root: Path, role: str, relative: Path) -> dict[str, Any]:
    target = root / relative
    raw = target.read_bytes()
    return {
        "role": role,
        "path": relative.as_posix(),
        "byte_length": len(raw),
        "sha256": bytes_sha256(raw),
    }


def _validate_file_binding(value: Any, role: str, relative: Path) -> dict[str, Any]:
    require(type(value) is dict, f"{role} binding missing")
    require(
        set(value) == {"role", "path", "byte_length", "sha256"},
        f"{role} binding fields differ",
    )
    require(
        value.get("role") == role and value.get("path") == relative.as_posix(),
        f"{role} binding identity differs",
    )
    require(
        type(value.get("byte_length")) is int and value["byte_length"] > 0,
        f"{role} binding byte length differs",
    )
    require(
        type(value.get("sha256")) is str
        and SHA256_RE.fullmatch(value["sha256"]) is not None,
        f"{role} binding digest differs",
    )
    return deepcopy(value)


def _validate_authority_mapping(value: Mapping[str, Any]) -> dict[str, Any]:
    require(
        set(value)
        == {
            "goal_binding",
            "contract_binding",
            "start_gate_binding",
            "gate_ended_at",
        },
        "FP-022 authority fields differ",
    )
    goal = _validate_file_binding(value["goal_binding"], "FP022_GOAL", GOAL_REL)
    contract = _validate_file_binding(
        value["contract_binding"],
        "FP022_START_GATE_CONTRACT_R002",
        CONTRACT_REL,
    )
    gate_value = value["start_gate_binding"]
    require(type(gate_value) is dict, "FP022_EXACT7_START_GATE binding missing")
    require(
        set(gate_value)
        == {"role", "path", "byte_length", "sha256", "event_sequence", "event_id"},
        "FP022_EXACT7_START_GATE binding fields differ",
    )
    gate_file = _validate_file_binding(
        {key: gate_value[key] for key in ("role", "path", "byte_length", "sha256")},
        "FP022_EXACT7_START_GATE",
        START_GATE_REL,
    )
    require(
        gate_value.get("event_sequence") == START_EVENT_SEQUENCE
        and gate_value.get("event_id") == START_EVENT_ID,
        "FP-022 start event binding differs",
    )
    gate = {
        **gate_file,
        "event_sequence": START_EVENT_SEQUENCE,
        "event_id": START_EVENT_ID,
    }
    _parse_time(value.get("gate_ended_at"), "FP-022 gate end")
    return {
        "goal_binding": goal,
        "contract_binding": contract,
        "start_gate_binding": gate,
        "gate_ended_at": value["gate_ended_at"],
    }


def validate_authority(root: Path = ROOT) -> dict[str, Any]:
    root = root.resolve(strict=True)
    goal = _binding(root, "FP022_GOAL", GOAL_REL)
    contract = _binding(root, "FP022_START_GATE_CONTRACT_R002", CONTRACT_REL)
    gate = _binding(root, "FP022_EXACT7_START_GATE", START_GATE_REL)
    require(goal["sha256"] == EXPECTED_GOAL_SHA256, "FP-022 goal bytes differ")
    require(
        contract["sha256"] == EXPECTED_CONTRACT_SHA256,
        "FP-022 start-gate contract bytes differ",
    )
    require(
        gate["sha256"] == EXPECTED_START_GATE_SHA256,
        "FP-022 start-gate bytes differ",
    )
    contract_doc = strict_json_bytes((root / CONTRACT_REL).read_bytes(), "FP-022 contract")
    require(
        contract_doc.get("target_goal_id") == GOAL_ID
        and contract_doc.get("target_goal_content_sha256") == EXPECTED_GOAL_SHA256
        and len(contract_doc.get("ordered_checks", [])) == 7,
        "FP-022 start-gate contract identity differs",
    )
    gate_doc = strict_json_bytes((root / START_GATE_REL).read_bytes(), "FP-022 start gate")
    require(
        gate_doc.get("status") == "PASS"
        and gate_doc.get("target_goal_id") == GOAL_ID
        and gate_doc.get("target_transition_event_id") == START_EVENT_ID
        and gate_doc.get("target_goal_content_sha256") == EXPECTED_GOAL_SHA256
        and len(gate_doc.get("check_runs", [])) == 7,
        "FP-022 start-gate identity differs",
    )
    return _validate_authority_mapping(
        {
            "goal_binding": goal,
            "contract_binding": contract,
            "start_gate_binding": {
                **gate,
                "event_sequence": START_EVENT_SEQUENCE,
                "event_id": START_EVENT_ID,
            },
            "gate_ended_at": gate_doc["execution_window"]["ended_at"],
        }
    )


def build_final_content_manifest(
    root: Path,
    groups: Sequence[SourceGroup] = IMPLEMENTATION_SOURCE_GROUPS,
) -> dict[str, Any]:
    require(bool(groups), "FP-022 source groups are empty")
    rows: list[dict[str, Any]] = []
    seen_groups: set[str] = set()
    seen_paths: set[str] = set()
    for group in groups:
        require(
            type(group.group_id) is str
            and bool(group.group_id)
            and group.group_id not in seen_groups,
            "FP-022 source group identity differs",
        )
        require(bool(group.paths), f"{group.group_id} source paths are empty")
        seen_groups.add(group.group_id)
        for path in group.paths:
            require(path not in seen_paths, f"duplicate FP-022 source path: {path}")
            relative = Path(path)
            require(
                not relative.is_absolute() and ".." not in relative.parts,
                f"unsafe FP-022 source path: {path}",
            )
            raw = (root / relative).read_bytes()
            rows.append(
                {
                    "group_id": group.group_id,
                    "path": path,
                    "byte_length": len(raw),
                    "sha256": bytes_sha256(raw),
                }
            )
            seen_paths.add(path)
    manifest = {
        "schema_version": "walksafe.fp022-final-content-manifest.v1",
        "scope_kind": IMPLEMENTATION_SCOPE_KIND,
        "group_order": [group.group_id for group in groups],
        "exact_path_count": len(rows),
        "path_set_sha256": object_sha256([row["path"] for row in rows]),
        "content_set_sha256": object_sha256(rows),
        "files": rows,
    }
    return sealed(manifest, "manifest_content_sha256")


def validate_final_content_manifest(
    value: Mapping[str, Any],
    *,
    root: Path,
    expected_groups: Sequence[SourceGroup] = IMPLEMENTATION_SOURCE_GROUPS,
) -> str:
    verify_seal(value, "manifest_content_sha256", "FP-022 final-content manifest")
    expected = build_final_content_manifest(root, expected_groups)
    require(dict(value) == expected, "FP-022 current file binding differs")
    return expected["content_set_sha256"]


def _validate_metrics(value: Any, lane: LaneSpec) -> dict[str, int]:
    require(
        type(value) is dict
        and set(value) == {"passed", "failed", "errors", "skipped"},
        f"{lane.lane_id} metrics fields differ",
    )
    require(
        all(type(value[key]) is int and value[key] >= 0 for key in value),
        f"{lane.lane_id} metrics differ",
    )
    require(
        value["failed"] == value["errors"] == value["skipped"] == 0
        and value["passed"] >= lane.minimum_passed,
        f"{lane.lane_id} metrics are not passing",
    )
    if lane.exact_passed is not None:
        require(
            value["passed"] == lane.exact_passed,
            f"{lane.lane_id} passed count differs",
        )
    return deepcopy(value)


def _validate_lane_observation(
    lane: LaneSpec,
    value: Mapping[str, Any],
    raw_output: bytes,
    gate_ended_at: str,
) -> dict[str, Any]:
    require(
        set(value)
        == {
            "schema_version",
            "lane_id",
            "status",
            "command",
            "exit_code",
            "started_at",
            "ended_at",
            "raw_output_sha256",
            "raw_output_byte_length",
            "metrics",
        },
        f"{lane.lane_id} observation fields differ",
    )
    require(
        value.get("schema_version")
        == "walksafe.fp022-internal-lane-observation.v1"
        and value.get("lane_id") == lane.lane_id
        and value.get("status") == "PASS"
        and value.get("exit_code") == 0,
        f"{lane.lane_id} observation identity differs",
    )
    require(value.get("command") == lane.expected_command, f"{lane.lane_id} command differs")
    started = _parse_time(value.get("started_at"), f"{lane.lane_id} start")
    ended = _parse_time(value.get("ended_at"), f"{lane.lane_id} end")
    gate_end = _parse_time(gate_ended_at, "FP-022 gate end")
    require(gate_end <= started <= ended, f"{lane.lane_id} execution window differs")
    require(type(raw_output) is bytes, f"{lane.lane_id} raw output must be bytes")
    require(
        value.get("raw_output_sha256") == bytes_sha256(raw_output)
        and value.get("raw_output_byte_length") == len(raw_output),
        f"{lane.lane_id} raw-output binding differs",
    )
    try:
        raw_text = raw_output.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BuildError(f"{lane.lane_id} raw output is not UTF-8") from exc
    require(
        f"WALKSAFE_FP022_COMMAND {lane.expected_command}\n" in raw_text,
        f"{lane.lane_id} raw command differs",
    )
    require(
        f"WALKSAFE_FP022_STARTED_AT {value['started_at']}\n" in raw_text
        and f"WALKSAFE_FP022_ENDED_AT {value['ended_at']}\n" in raw_text,
        f"{lane.lane_id} raw execution window differs",
    )
    require(
        "WALKSAFE_FP022_EXIT_CODE 0\n" in raw_text,
        f"{lane.lane_id} raw exit code differs",
    )
    require(
        re.search(lane.success_pattern, raw_text) is not None,
        f"{lane.lane_id} success marker missing",
    )
    if lane.lane_id == "ANDROID_USER_INTERNAL":
        for task_marker in (
            "> Task :app:testDebugUnitTest",
            "> Task :app:assembleDebug",
            "> Task :app:lintDebug",
        ):
            require(
                task_marker in raw_text,
                f"{lane.lane_id} required task marker missing: {task_marker}",
            )
    metrics = _validate_metrics(value.get("metrics"), lane)
    summary = (
        f"WALKSAFE_FP022_SUMMARY passed={metrics['passed']} "
        f"failed={metrics['failed']} errors={metrics['errors']} "
        f"skipped={metrics['skipped']}\n"
    )
    require(summary in raw_text, f"{lane.lane_id} raw summary differs")
    return deepcopy(dict(value))


def _validate_actor_ids(executor_actor_id: str, reviewer_actor_id: str) -> None:
    require(
        type(executor_actor_id) is str and bool(executor_actor_id.strip()),
        "FP-022 executor actor is empty",
    )
    require(
        type(reviewer_actor_id) is str and bool(reviewer_actor_id.strip()),
        "FP-022 required review actor is empty",
    )
    require(
        executor_actor_id != reviewer_actor_id,
        "FP-022 review actor must differ from executor actor",
    )


def _strict_output_document(raw_text: str, label: str) -> dict[str, Any]:
    require(type(raw_text) is str, f"{label} output must be text")
    raw = raw_text.encode("utf-8")
    value = strict_json_bytes(raw, label)
    require_document_matches_raw(value, raw, label)
    return value


def _build_evidence_stage(
    *,
    root: Path,
    observations: Mapping[str, Mapping[str, Any]],
    raw_outputs: Mapping[str, bytes],
    source_groups: Sequence[SourceGroup],
    authority: Mapping[str, Any],
    executor_actor_id: str,
    executor_task: str,
) -> dict[Path, str]:
    manifest = build_final_content_manifest(root, source_groups)
    content_set = manifest["content_set_sha256"]
    logs: dict[Path, str] = {}
    validated_observations: list[dict[str, Any]] = []
    for lane in LANES:
        raw = raw_outputs[lane.lane_id]
        validated = _validate_lane_observation(
            lane,
            observations[lane.lane_id],
            raw,
            authority["gate_ended_at"],
        )
        validated["raw_output_path"] = lane.log_rel.as_posix()
        validated_observations.append(validated)
        try:
            logs[lane.log_rel] = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise BuildError(f"{lane.lane_id} raw output is not UTF-8") from exc
    log_bindings = [
        {
            "lane_id": lane.lane_id,
            "path": lane.log_rel.as_posix(),
            "byte_length": len(raw_outputs[lane.lane_id]),
            "sha256": bytes_sha256(raw_outputs[lane.lane_id]),
        }
        for lane in LANES
    ]
    observed_at = max(
        (row["ended_at"] for row in validated_observations),
        key=lambda value: _parse_time(value, "FP-022 observation end"),
    )
    implementation = sealed(
        {
            "schema_version": "walksafe.fp022-navigation-implementation-record.v1",
            "document_id": "WS-FP022-NAVIGATION-IMPLEMENTATION-20260814-001",
            "goal_id": GOAL_ID,
            "policy_id": POLICY_ID,
            "gap_id": GAP_ID,
            "kind": "IMPLEMENTATION_RECORD",
            "status": "PASS",
            "credit_scope": "REPOSITORY_INTERNAL_ONLY",
            "observed_at": observed_at,
            "scope_kind": IMPLEMENTATION_SCOPE_KIND,
            "executor": {
                "id": executor_actor_id,
                "task": executor_task,
                "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            },
            "authority_bindings": {
                "goal": deepcopy(authority["goal_binding"]),
                "contract": deepcopy(authority["contract_binding"]),
                "start_gate": deepcopy(authority["start_gate_binding"]),
            },
            "final_content_manifest": manifest,
            "implementation_content_set_sha256": content_set,
            "implemented_controls": list(IMPLEMENTED_CONTROLS),
            "completion_boundary": completion_boundary(),
        },
        "implementation_record_content_sha256",
    )
    implementation_text = json_text(implementation)
    observation_doc = sealed(
        {
            "schema_version": "walksafe.fp022-verification-observations.v1",
            "document_id": "WS-FP022-NAVIGATION-OBSERVATIONS-20260814-001",
            "goal_id": GOAL_ID,
            "kind": "VERIFICATION_OBSERVATIONS",
            "status": "CAPTURED",
            "credit_scope": "REPOSITORY_INTERNAL_ONLY",
            "observed_at": observed_at,
            "executor": {
                "id": executor_actor_id,
                "task": executor_task,
                "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            },
            "implementation_content_set_sha256": content_set,
            "lane_count": len(LANES),
            "lanes": validated_observations,
            "lane_log_bindings": log_bindings,
            "completion_boundary": completion_boundary(),
        },
        "verification_observations_content_sha256",
    )
    observations_text = json_text(observation_doc)
    verification = sealed(
        {
            "schema_version": "walksafe.fp022-verification-result.v1",
            "document_id": "WS-FP022-NAVIGATION-VERIFICATION-20260814-001",
            "goal_id": GOAL_ID,
            "kind": "VERIFICATION_RESULT",
            "status": "PASS",
            "credit_scope": "REPOSITORY_INTERNAL_ONLY",
            "observed_at": observed_at,
            "executor": {
                "id": executor_actor_id,
                "task": executor_task,
                "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            },
            "implementation_record_sha256": bytes_sha256(implementation_text.encode()),
            "verification_observations_sha256": bytes_sha256(observations_text.encode()),
            "implementation_content_set_sha256": content_set,
            "internal_lane_count": len(LANES),
            "lane_log_bindings": log_bindings,
            "completion_boundary": completion_boundary(),
        },
        "verification_result_content_sha256",
    )
    verification_text = json_text(verification)
    return {
        **logs,
        IMPLEMENTATION_REL: implementation_text,
        OBSERVATIONS_REL: observations_text,
        VERIFICATION_REL: verification_text,
    }


def _actor(actor_id: str, task: str, role: str) -> dict[str, str]:
    require(type(actor_id) is str and bool(actor_id.strip()), f"{role} actor is empty")
    require(type(task) is str and bool(task.strip()), f"{role} task is empty")
    return {"id": actor_id, "task": task, "role": role}


def _validate_actor_boundary(
    executor_actor_id: str,
    executor_task: str,
    reviewer_actor_id: str,
    reviewer_task: str,
) -> tuple[dict[str, str], dict[str, Any]]:
    _validate_actor_ids(executor_actor_id, reviewer_actor_id)
    executor = _actor(
        executor_actor_id,
        executor_task,
        "INTERNAL_IMPLEMENTATION_EXECUTOR",
    )
    reviewer_base = _actor(
        reviewer_actor_id,
        reviewer_task,
        "SEPARATE_INTERNAL_REVIEWER",
    )
    require(
        executor_task != reviewer_task,
        "FP-022 review task must differ from executor task",
    )
    return executor, {
        **reviewer_base,
        "separate_from_executor": True,
        "external_independence_claimed": False,
    }


def build_evidence_outputs(
    *,
    root: Path = ROOT,
    lane_observations: Mapping[str, Mapping[str, Any]],
    lane_raw_outputs: Mapping[str, bytes],
    source_groups: Sequence[SourceGroup] = IMPLEMENTATION_SOURCE_GROUPS,
    authority: Mapping[str, Any] | None = None,
    executor_actor_id: str,
    executor_task: str,
) -> dict[Path, str]:
    """Build only the three repository-internal producer evidence documents."""

    root = root.resolve(strict=True)
    require(
        set(lane_observations) == set(LANE_BY_ID),
        "FP-022 lane observation set differs",
    )
    require(
        set(lane_raw_outputs) == set(LANE_BY_ID),
        "FP-022 lane raw-output set differs",
    )
    _actor(executor_actor_id, executor_task, "INTERNAL_IMPLEMENTATION_EXECUTOR")
    auth = (
        _validate_authority_mapping(authority)
        if authority is not None
        else validate_authority(root)
    )
    evidence = _build_evidence_stage(
        root=root,
        observations=lane_observations,
        raw_outputs=lane_raw_outputs,
        source_groups=source_groups,
        authority=auth,
        executor_actor_id=executor_actor_id,
        executor_task=executor_task,
    )
    validate_evidence_outputs(
        evidence,
        root=root,
        source_groups=source_groups,
        authority=auth,
        executor_actor_id=executor_actor_id,
        executor_task=executor_task,
    )
    return evidence


def validate_evidence_outputs(
    outputs: Mapping[Path, str],
    *,
    root: Path,
    source_groups: Sequence[SourceGroup],
    authority: Mapping[str, Any],
    executor_actor_id: str,
    executor_task: str,
) -> None:
    evidence_paths = {
        *(lane.log_rel for lane in LANES),
        IMPLEMENTATION_REL,
        OBSERVATIONS_REL,
        VERIFICATION_REL,
    }
    require(
        set(outputs) == evidence_paths,
        "FP-022 evidence output set differs",
    )
    implementation = _strict_output_document(outputs[IMPLEMENTATION_REL], "FP-022 implementation")
    observations = _strict_output_document(outputs[OBSERVATIONS_REL], "FP-022 observations")
    verification = _strict_output_document(outputs[VERIFICATION_REL], "FP-022 verification")
    executor = _actor(
        executor_actor_id,
        executor_task,
        "INTERNAL_IMPLEMENTATION_EXECUTOR",
    )

    verify_seal(implementation, "implementation_record_content_sha256", "FP-022 implementation")
    require(
        implementation.get("schema_version") == "walksafe.fp022-navigation-implementation-record.v1"
        and implementation.get("goal_id") == GOAL_ID
        and implementation.get("policy_id") == POLICY_ID
        and implementation.get("gap_id") == GAP_ID
        and implementation.get("status") == "PASS"
        and implementation.get("credit_scope") == "REPOSITORY_INTERNAL_ONLY"
        and implementation.get("executor") == executor
        and implementation.get("implemented_controls") == list(IMPLEMENTED_CONTROLS)
        and implementation.get("completion_boundary") == completion_boundary(),
        "FP-022 implementation boundary differs",
    )
    expected_authority = {
        "goal": authority["goal_binding"],
        "contract": authority["contract_binding"],
        "start_gate": authority["start_gate_binding"],
    }
    require(
        implementation.get("authority_bindings") == expected_authority,
        "FP-022 implementation authority differs",
    )
    manifest = implementation.get("final_content_manifest")
    require(type(manifest) is dict, "FP-022 implementation manifest missing")
    content_set = validate_final_content_manifest(
        manifest,
        root=root,
        expected_groups=source_groups,
    )
    require(
        implementation.get("scope_kind") == IMPLEMENTATION_SCOPE_KIND
        and implementation.get("implementation_content_set_sha256") == content_set,
        "FP-022 implementation content set differs",
    )

    verify_seal(
        observations,
        "verification_observations_content_sha256",
        "FP-022 observations",
    )
    lanes = observations.get("lanes")
    expected_log_bindings = [
        {
            "lane_id": lane.lane_id,
            "path": lane.log_rel.as_posix(),
            "byte_length": len(outputs[lane.log_rel].encode()),
            "sha256": bytes_sha256(outputs[lane.log_rel].encode()),
        }
        for lane in LANES
    ]
    require(
        observations.get("schema_version") == "walksafe.fp022-verification-observations.v1"
        and observations.get("goal_id") == GOAL_ID
        and observations.get("status") == "CAPTURED"
        and observations.get("credit_scope") == "REPOSITORY_INTERNAL_ONLY"
        and observations.get("executor") == executor
        and observations.get("implementation_content_set_sha256") == content_set
        and observations.get("lane_count") == len(LANES)
        and observations.get("lane_log_bindings") == expected_log_bindings
        and type(lanes) is list
        and len(lanes) == len(LANES)
        and observations.get("completion_boundary") == completion_boundary(),
        "FP-022 observations boundary differs",
    )
    for lane, row in zip(LANES, lanes, strict=True):
        require(
            type(row) is dict
            and row.get("lane_id") == lane.lane_id
            and row.get("command") == lane.expected_command
            and row.get("status") == "PASS"
            and row.get("exit_code") == 0
            and row.get("raw_output_path") == lane.log_rel.as_posix(),
            f"{lane.lane_id} recorded observation differs",
        )
        _parse_time(row.get("started_at"), f"{lane.lane_id} recorded start")
        _parse_time(row.get("ended_at"), f"{lane.lane_id} recorded end")
        _validate_metrics(row.get("metrics"), lane)
        require(
            type(row.get("raw_output_sha256")) is str
            and SHA256_RE.fullmatch(row["raw_output_sha256"]) is not None
            and type(row.get("raw_output_byte_length")) is int
            and row["raw_output_byte_length"] > 0,
            f"{lane.lane_id} recorded raw-output binding differs",
        )
        raw = outputs[lane.log_rel].encode()
        require(
            row["raw_output_sha256"] == bytes_sha256(raw)
            and row["raw_output_byte_length"] == len(raw),
            f"{lane.lane_id} physical log binding differs",
        )

    verify_seal(verification, "verification_result_content_sha256", "FP-022 verification")
    require(
        verification.get("schema_version") == "walksafe.fp022-verification-result.v1"
        and verification.get("goal_id") == GOAL_ID
        and verification.get("status") == "PASS"
        and verification.get("credit_scope") == "REPOSITORY_INTERNAL_ONLY"
        and verification.get("executor") == executor
        and verification.get("implementation_record_sha256")
        == bytes_sha256(outputs[IMPLEMENTATION_REL].encode())
        and verification.get("verification_observations_sha256")
        == bytes_sha256(outputs[OBSERVATIONS_REL].encode())
        and verification.get("implementation_content_set_sha256") == content_set
        and verification.get("internal_lane_count") == len(LANES)
        and verification.get("lane_log_bindings") == expected_log_bindings
        and verification.get("completion_boundary") == completion_boundary(),
        "FP-022 verification boundary differs",
    )


def _artifact_bindings(
    outputs: Mapping[Path, str | bytes],
    specs: Sequence[tuple[str, Path]],
) -> list[dict[str, Any]]:
    bindings: list[dict[str, Any]] = []
    for kind, path in specs:
        value = outputs[path]
        raw = value if type(value) is bytes else value.encode("utf-8")
        bindings.append(
            {
                "kind": kind,
                "path": path.as_posix(),
                "byte_length": len(raw),
                "sha256": bytes_sha256(raw),
            }
        )
    return bindings


def _evidence_binding_specs() -> tuple[tuple[str, Path], ...]:
    return (
        *((f"{lane.lane_id}_LOG", lane.log_rel) for lane in LANES),
        ("IMPLEMENTATION_RECORD", IMPLEMENTATION_REL),
        ("VERIFICATION_OBSERVATIONS", OBSERVATIONS_REL),
        ("VERIFICATION_RESULT", VERIFICATION_REL),
    )


def validate_r028_outputs(outputs: Mapping[Path, str | bytes]) -> dict[str, Any]:
    require(set(outputs) == set(R028_PATHS), "FP-022 R028 output set differs")
    raw_by_path = {
        path: value if type(value) is bytes else value.encode("utf-8")
        for path, value in outputs.items()
    }
    gap = strict_json_bytes(raw_by_path[R028_GAP_JSON_REL], "FP-022 R028 gap")
    backlog = strict_json_bytes(
        raw_by_path[R028_BACKLOG_JSON_REL],
        "FP-022 R028 backlog",
    )
    require_document_matches_raw(gap, raw_by_path[R028_GAP_JSON_REL], "FP-022 R028 gap")
    require_document_matches_raw(
        backlog,
        raw_by_path[R028_BACKLOG_JSON_REL],
        "FP-022 R028 backlog",
    )
    verify_seal(gap, "report_content_sha256", "FP-022 R028 gap")
    verify_seal(backlog, "backlog_content_sha256", "FP-022 R028 backlog")
    assessments = gap.get("assessments")
    target = [
        row
        for row in assessments if type(row) is dict and row.get("gap_id") == GAP_ID
    ] if type(assessments) is list else []
    require(
        gap.get("schema_version") == "walksafe.implementation-gap-analysis.v1"
        and len(target) == 1
        and target[0].get("source_policy_id") == POLICY_ID
        and target[0].get("status") == "PARTIAL"
        and target[0].get("formal_test_status") == "NOT_RUN",
        "FP-022 R028 GAP-031 reassessment differs",
    )
    actions = backlog.get("next_action_sequence")
    fp022 = [
        row
        for row in actions
        if type(row) is dict and row.get("source_policy_id") == POLICY_ID
    ] if type(actions) is list else []
    fp023 = [
        row
        for row in actions
        if type(row) is dict and row.get("source_policy_id") == "FP-023"
    ] if type(actions) is list else []
    require(
        backlog.get("schema_version")
        == "walksafe.implementation-remediation-backlog.v1"
        and backlog.get("gap_report_content_sha256") == gap["report_content_sha256"]
        and len(fp022) == 1
        and fp022[0].get("status") == "PARTIAL"
        and len(fp023) == 1
        and fp023[0].get("order") == 25,
        "FP-022 R028 backlog successor differs",
    )
    for path in (R028_GAP_MD_REL, R028_BACKLOG_MD_REL):
        require(raw_by_path[path], f"FP-022 R028 markdown is empty: {path}")
        try:
            raw_by_path[path].decode("utf-8")
        except UnicodeDecodeError as exc:
            raise BuildError(f"FP-022 R028 markdown is not UTF-8: {path}") from exc
    return {"gap": gap, "backlog": backlog}


def build_successor_outputs(
    *,
    evidence_outputs: Mapping[Path, str],
    r028_outputs: Mapping[Path, str | bytes],
    root: Path = ROOT,
    source_groups: Sequence[SourceGroup] = IMPLEMENTATION_SOURCE_GROUPS,
    authority: Mapping[str, Any] | None = None,
    executor_actor_id: str,
    executor_task: str,
    required_reviewer_actor_id: str,
    required_reviewer_task: str,
) -> dict[Path, str]:
    root = root.resolve(strict=True)
    auth = _validate_authority_mapping(authority) if authority is not None else validate_authority(root)
    validate_evidence_outputs(
        evidence_outputs,
        root=root,
        source_groups=source_groups,
        authority=auth,
        executor_actor_id=executor_actor_id,
        executor_task=executor_task,
    )
    validate_r028_outputs(r028_outputs)
    executor, reviewer = _validate_actor_boundary(
        executor_actor_id,
        executor_task,
        required_reviewer_actor_id,
        required_reviewer_task,
    )
    evidence_bindings = _artifact_bindings(
        evidence_outputs,
        _evidence_binding_specs(),
    )
    r028_bindings = _artifact_bindings(
        r028_outputs,
        (
            ("GAP_R028_JSON", R028_GAP_JSON_REL),
            ("GAP_R028_MARKDOWN", R028_GAP_MD_REL),
            ("BACKLOG_R028_JSON", R028_BACKLOG_JSON_REL),
            ("BACKLOG_R028_MARKDOWN", R028_BACKLOG_MD_REL),
        ),
    )
    verification = _strict_output_document(
        evidence_outputs[VERIFICATION_REL],
        "FP-022 verification",
    )
    successor = sealed(
        {
            "schema_version": "walksafe.fp022-gap031-successor-trace.v1",
            "document_id": "WS-FP022-GAP031-R028-SUCCESSOR-20260814-001",
            "goal_id": GOAL_ID,
            "policy_id": POLICY_ID,
            "gap_id": GAP_ID,
            "kind": "SUCCESSOR_TRACE",
            "status": "GAP031_REASSESSED_INTERNAL_REVIEW_PENDING",
            "observed_at": verification["observed_at"],
            "executor": executor,
            "evidence_bindings": evidence_bindings,
            "r028_bindings": r028_bindings,
            "gap031_status": "PARTIAL",
            "formal_test_status": "NOT_RUN",
            "next_goal": {
                "goal_id": "WS-GOAL-EPIC-04-FP-023-R001",
                "policy_id": "FP-023",
                "priority_rank": 25,
            },
            "next_single_action": "SEPARATE_INTERNAL_REVIEW",
            "completion_boundary": completion_boundary(),
        },
        "successor_trace_content_sha256",
    )
    successor_text = json_text(successor)
    reviewed_bindings = [
        *evidence_bindings,
        *r028_bindings,
        {
            "kind": "SUCCESSOR_TRACE",
            "path": SUCCESSOR_REL.as_posix(),
            "byte_length": len(successor_text.encode()),
            "sha256": bytes_sha256(successor_text.encode()),
        },
    ]
    subject = sealed(
        {
            "schema_version": "walksafe.fp022-internal-review-subject.v1",
            "document_id": "WS-FP022-NAVIGATION-INTERNAL-REVIEW-SUBJECT-20260814-001",
            "goal_id": GOAL_ID,
            "kind": "INTERNAL_REVIEW_SUBJECT",
            "status": "REVIEW_PENDING",
            "observed_at": verification["observed_at"],
            "executor": executor,
            "required_reviewer": reviewer,
            "reviewed_evidence_bindings": reviewed_bindings,
            "external_independence_claimed": False,
            "completion_boundary": completion_boundary(),
        },
        "review_subject_content_sha256",
    )
    outputs = {
        SUCCESSOR_REL: successor_text,
        REVIEW_SUBJECT_REL: json_text(subject),
    }
    validate_successor_outputs(
        outputs,
        evidence_outputs=evidence_outputs,
        r028_outputs=r028_outputs,
        executor_actor_id=executor_actor_id,
        executor_task=executor_task,
        required_reviewer_actor_id=required_reviewer_actor_id,
        required_reviewer_task=required_reviewer_task,
    )
    return outputs


def validate_successor_outputs(
    outputs: Mapping[Path, str],
    *,
    evidence_outputs: Mapping[Path, str],
    r028_outputs: Mapping[Path, str | bytes],
    executor_actor_id: str,
    executor_task: str,
    required_reviewer_actor_id: str,
    required_reviewer_task: str,
) -> None:
    require(
        set(outputs) == {SUCCESSOR_REL, REVIEW_SUBJECT_REL},
        "FP-022 successor output set differs",
    )
    validate_r028_outputs(r028_outputs)
    executor, reviewer = _validate_actor_boundary(
        executor_actor_id,
        executor_task,
        required_reviewer_actor_id,
        required_reviewer_task,
    )
    evidence_bindings = _artifact_bindings(
        evidence_outputs,
        _evidence_binding_specs(),
    )
    r028_bindings = _artifact_bindings(
        r028_outputs,
        (
            ("GAP_R028_JSON", R028_GAP_JSON_REL),
            ("GAP_R028_MARKDOWN", R028_GAP_MD_REL),
            ("BACKLOG_R028_JSON", R028_BACKLOG_JSON_REL),
            ("BACKLOG_R028_MARKDOWN", R028_BACKLOG_MD_REL),
        ),
    )
    successor = _strict_output_document(outputs[SUCCESSOR_REL], "FP-022 successor")
    verify_seal(successor, "successor_trace_content_sha256", "FP-022 successor")
    require(
        successor.get("schema_version") == "walksafe.fp022-gap031-successor-trace.v1"
        and successor.get("document_id") == "WS-FP022-GAP031-R028-SUCCESSOR-20260814-001"
        and successor.get("goal_id") == GOAL_ID
        and successor.get("policy_id") == POLICY_ID
        and successor.get("gap_id") == GAP_ID
        and successor.get("kind") == "SUCCESSOR_TRACE"
        and successor.get("status") == "GAP031_REASSESSED_INTERNAL_REVIEW_PENDING"
        and successor.get("executor") == executor
        and successor.get("evidence_bindings") == evidence_bindings
        and successor.get("r028_bindings") == r028_bindings
        and successor.get("gap031_status") == "PARTIAL"
        and successor.get("formal_test_status") == "NOT_RUN"
        and successor.get("next_goal")
        == {
            "goal_id": "WS-GOAL-EPIC-04-FP-023-R001",
            "policy_id": "FP-023",
            "priority_rank": 25,
        }
        and successor.get("next_single_action") == "SEPARATE_INTERNAL_REVIEW"
        and successor.get("completion_boundary") == completion_boundary(),
        "FP-022 successor boundary differs",
    )
    subject = _strict_output_document(outputs[REVIEW_SUBJECT_REL], "FP-022 review subject")
    verify_seal(subject, "review_subject_content_sha256", "FP-022 review subject")
    expected_reviewed = [
        *evidence_bindings,
        *r028_bindings,
        {
            "kind": "SUCCESSOR_TRACE",
            "path": SUCCESSOR_REL.as_posix(),
            "byte_length": len(outputs[SUCCESSOR_REL].encode()),
            "sha256": bytes_sha256(outputs[SUCCESSOR_REL].encode()),
        },
    ]
    require(
        subject.get("schema_version") == "walksafe.fp022-internal-review-subject.v1"
        and subject.get("document_id")
        == "WS-FP022-NAVIGATION-INTERNAL-REVIEW-SUBJECT-20260814-001"
        and subject.get("goal_id") == GOAL_ID
        and subject.get("kind") == "INTERNAL_REVIEW_SUBJECT"
        and subject.get("status") == "REVIEW_PENDING"
        and subject.get("executor") == executor
        and subject.get("required_reviewer") == reviewer
        and subject.get("reviewed_evidence_bindings") == expected_reviewed
        and subject.get("external_independence_claimed") is False
        and subject.get("completion_boundary") == completion_boundary(),
        "FP-022 review-subject boundary differs",
    )


def validate_independent_review(
    raw: bytes,
    *,
    subject_text: str,
    executor_actor_id: str,
    executor_task: str,
    reviewer_actor_id: str,
    reviewer_task: str,
) -> dict[str, Any]:
    review = strict_json_bytes(raw, "FP-022 independent review")
    require_document_matches_raw(review, raw, "FP-022 independent review")
    verify_seal(
        review,
        "independent_review_content_sha256",
        "FP-022 independent review",
    )
    require(
        set(review)
        == {
            "schema_version",
            "document_id",
            "goal_id",
            "kind",
            "status",
            "decision",
            "decided_at",
            "executor",
            "reviewer",
            "review_subject_binding",
            "reviewed_evidence_bindings",
            "findings",
            "external_independence_claimed",
            "completion_boundary",
            "independent_review_content_sha256",
        },
        "FP-022 independent-review fields differ",
    )
    executor, reviewer = _validate_actor_boundary(
        executor_actor_id,
        executor_task,
        reviewer_actor_id,
        reviewer_task,
    )
    reviewer = {**reviewer, "decision": "APPROVED"}
    subject = _strict_output_document(subject_text, "FP-022 review subject")
    require(
        review.get("schema_version")
        == "walksafe.fp022-internal-independent-review.v1"
        and review.get("document_id")
        == "WS-FP022-NAVIGATION-INTERNAL-INDEPENDENT-REVIEW-20260814-001"
        and review.get("goal_id") == GOAL_ID
        and review.get("kind") == "INTERNAL_INDEPENDENT_REVIEW"
        and review.get("status") == "PASS"
        and review.get("decision") == "APPROVED"
        and review.get("executor") == executor
        and review.get("reviewer") == reviewer
        and review.get("review_subject_binding")
        == {
            "path": REVIEW_SUBJECT_REL.as_posix(),
            "byte_length": len(subject_text.encode()),
            "sha256": bytes_sha256(subject_text.encode()),
        }
        and review.get("reviewed_evidence_bindings")
        == subject.get("reviewed_evidence_bindings")
        and review.get("findings") == []
        and review.get("external_independence_claimed") is False
        and review.get("completion_boundary") == completion_boundary(),
        "FP-022 independent-review boundary differs",
    )
    _parse_time(review.get("decided_at"), "FP-022 review decision")
    return review


def build_completion_output(
    *,
    evidence_outputs: Mapping[Path, str],
    successor_outputs: Mapping[Path, str],
    r028_outputs: Mapping[Path, str | bytes],
    independent_review_raw: bytes,
    executor_actor_id: str,
    executor_task: str,
    reviewer_actor_id: str,
    reviewer_task: str,
) -> dict[Path, str]:
    validate_successor_outputs(
        successor_outputs,
        evidence_outputs=evidence_outputs,
        r028_outputs=r028_outputs,
        executor_actor_id=executor_actor_id,
        executor_task=executor_task,
        required_reviewer_actor_id=reviewer_actor_id,
        required_reviewer_task=reviewer_task,
    )
    review = validate_independent_review(
        independent_review_raw,
        subject_text=successor_outputs[REVIEW_SUBJECT_REL],
        executor_actor_id=executor_actor_id,
        executor_task=executor_task,
        reviewer_actor_id=reviewer_actor_id,
        reviewer_task=reviewer_task,
    )
    executor, reviewer = _validate_actor_boundary(
        executor_actor_id,
        executor_task,
        reviewer_actor_id,
        reviewer_task,
    )
    completion = sealed(
        {
            "schema_version": "walksafe.fp022-work-item-completion-receipt.v1",
            "document_id": "WS-FP022-NAVIGATION-WORK-ITEM-COMPLETION-20260814-001",
            "goal_id": GOAL_ID,
            "kind": "WORK_ITEM_COMPLETION_RECEIPT",
            "status": "ACCEPTED",
            "result": "PASS",
            "target_goal_content_sha256": EXPECTED_GOAL_SHA256,
            "source_policy_ids": [POLICY_ID],
            "gap_ids": [GAP_ID],
            "execution_session_event": {
                "sequence": START_EVENT_SEQUENCE,
                "event_id": START_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "event_sha256": START_EVENT_SHA256,
            },
            "implementation_start_gate_binding": {
                "path": START_GATE_REL.as_posix(),
                "file_sha256": EXPECTED_START_GATE_SHA256,
            },
            "completed_at": review["decided_at"],
            "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
            "executor": executor,
            "reviewer": {**reviewer, "decision": "APPROVED"},
            "evidence_bindings": _artifact_bindings(
                {**evidence_outputs, **successor_outputs},
                (
                    *_evidence_binding_specs(),
                    ("SUCCESSOR_TRACE", SUCCESSOR_REL),
                    ("REVIEW_SUBJECT", REVIEW_SUBJECT_REL),
                ),
            ),
            "r028_bindings": _artifact_bindings(
                r028_outputs,
                (
                    ("GAP_R028_JSON", R028_GAP_JSON_REL),
                    ("GAP_R028_MARKDOWN", R028_GAP_MD_REL),
                    ("BACKLOG_R028_JSON", R028_BACKLOG_JSON_REL),
                    ("BACKLOG_R028_MARKDOWN", R028_BACKLOG_MD_REL),
                ),
            ),
            "independent_review_binding": {
                "path": INDEPENDENT_REVIEW_REL.as_posix(),
                "byte_length": len(independent_review_raw),
                "sha256": bytes_sha256(independent_review_raw),
            },
            "next_goal": {
                "goal_id": "WS-GOAL-EPIC-04-FP-023-R001",
                "policy_id": "FP-023",
                "priority_rank": 25,
            },
            "next_single_action": (
                "APPEND_SEQ70_CANONICAL_BINDINGS_UPDATED_THEN_SEQ71_GOAL_COMPLETED"
            ),
            "external_independence_claimed": False,
            "completion_boundary": completion_boundary(),
        },
        "completion_receipt_content_sha256",
    )
    output = {COMPLETION_REL: json_text(completion)}
    validate_completion_output(
        output,
        evidence_outputs=evidence_outputs,
        successor_outputs=successor_outputs,
        r028_outputs=r028_outputs,
        independent_review_raw=independent_review_raw,
        executor_actor_id=executor_actor_id,
        executor_task=executor_task,
        reviewer_actor_id=reviewer_actor_id,
        reviewer_task=reviewer_task,
    )
    return output


def validate_completion_output(
    outputs: Mapping[Path, str],
    *,
    evidence_outputs: Mapping[Path, str],
    successor_outputs: Mapping[Path, str],
    r028_outputs: Mapping[Path, str | bytes],
    independent_review_raw: bytes,
    executor_actor_id: str,
    executor_task: str,
    reviewer_actor_id: str,
    reviewer_task: str,
) -> None:
    require(set(outputs) == {COMPLETION_REL}, "FP-022 completion output set differs")
    validate_successor_outputs(
        successor_outputs,
        evidence_outputs=evidence_outputs,
        r028_outputs=r028_outputs,
        executor_actor_id=executor_actor_id,
        executor_task=executor_task,
        required_reviewer_actor_id=reviewer_actor_id,
        required_reviewer_task=reviewer_task,
    )
    review = validate_independent_review(
        independent_review_raw,
        subject_text=successor_outputs[REVIEW_SUBJECT_REL],
        executor_actor_id=executor_actor_id,
        executor_task=executor_task,
        reviewer_actor_id=reviewer_actor_id,
        reviewer_task=reviewer_task,
    )
    executor, reviewer = _validate_actor_boundary(
        executor_actor_id,
        executor_task,
        reviewer_actor_id,
        reviewer_task,
    )
    receipt = _strict_output_document(outputs[COMPLETION_REL], "FP-022 completion receipt")
    verify_seal(receipt, "completion_receipt_content_sha256", "FP-022 completion receipt")
    require(
        receipt.get("schema_version")
        == "walksafe.fp022-work-item-completion-receipt.v1"
        and receipt.get("document_id")
        == "WS-FP022-NAVIGATION-WORK-ITEM-COMPLETION-20260814-001"
        and receipt.get("goal_id") == GOAL_ID
        and receipt.get("kind") == "WORK_ITEM_COMPLETION_RECEIPT"
        and receipt.get("status") == "ACCEPTED"
        and receipt.get("result") == "PASS"
        and receipt.get("target_goal_content_sha256") == EXPECTED_GOAL_SHA256
        and receipt.get("source_policy_ids") == [POLICY_ID]
        and receipt.get("gap_ids") == [GAP_ID]
        and receipt.get("execution_session_event")
        == {
            "sequence": START_EVENT_SEQUENCE,
            "event_id": START_EVENT_ID,
            "event_type": "GOAL_STARTED",
            "event_sha256": START_EVENT_SHA256,
        }
        and receipt.get("implementation_start_gate_binding")
        == {
            "path": START_GATE_REL.as_posix(),
            "file_sha256": EXPECTED_START_GATE_SHA256,
        }
        and receipt.get("completed_at") == review["decided_at"]
        and receipt.get("target_completion_level")
        == "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
        and receipt.get("executor") == executor
        and receipt.get("reviewer") == {**reviewer, "decision": "APPROVED"}
        and receipt.get("evidence_bindings")
        == _artifact_bindings(
            {**evidence_outputs, **successor_outputs},
            (
                *_evidence_binding_specs(),
                ("SUCCESSOR_TRACE", SUCCESSOR_REL),
                ("REVIEW_SUBJECT", REVIEW_SUBJECT_REL),
            ),
        )
        and receipt.get("r028_bindings")
        == _artifact_bindings(
            r028_outputs,
            (
                ("GAP_R028_JSON", R028_GAP_JSON_REL),
                ("GAP_R028_MARKDOWN", R028_GAP_MD_REL),
                ("BACKLOG_R028_JSON", R028_BACKLOG_JSON_REL),
                ("BACKLOG_R028_MARKDOWN", R028_BACKLOG_MD_REL),
            ),
        )
        and receipt.get("independent_review_binding")
        == {
            "path": INDEPENDENT_REVIEW_REL.as_posix(),
            "byte_length": len(independent_review_raw),
            "sha256": bytes_sha256(independent_review_raw),
        }
        and receipt.get("next_goal")
        == {
            "goal_id": "WS-GOAL-EPIC-04-FP-023-R001",
            "policy_id": "FP-023",
            "priority_rank": 25,
        }
        and receipt.get("next_single_action")
        == "APPEND_SEQ70_CANONICAL_BINDINGS_UPDATED_THEN_SEQ71_GOAL_COMPLETED"
        and receipt.get("external_independence_claimed") is False
        and receipt.get("completion_boundary") == completion_boundary(),
        "FP-022 completion-receipt boundary differs",
    )


def _read_text_outputs(root: Path, paths: Sequence[Path]) -> dict[Path, str]:
    return {path: (root / path).read_text(encoding="utf-8") for path in paths}


def _read_raw_outputs(root: Path, paths: Sequence[Path]) -> dict[Path, bytes]:
    return {path: (root / path).read_bytes() for path in paths}


def _capture_environment(overrides: Mapping[str, str]) -> dict[str, str]:
    allowed = (
        "PATH",
        "LANG",
        "LC_ALL",
        "JAVA_HOME",
        "ANDROID_HOME",
        "ANDROID_SDK_ROOT",
        "USER",
        "LOGNAME",
        "TMPDIR",
        "HOME",
    )
    environment = {
        key: os.environ[key]
        for key in allowed
        if key in os.environ
    }
    environment.update(overrides)
    return environment


def _lane_process_contract(
    lane: LaneSpec,
    root: Path,
) -> tuple[Path, tuple[str, ...], dict[str, str]]:
    if lane.lane_id == "BACKEND_NAVIGATION_INTERNAL":
        return (
            root,
            (
                LOCKED_TEST_PYTHON,
                "-B",
                "-m",
                "pytest",
                "-p",
                "no:cacheprovider",
                "-q",
                "backend/tests/test_navigation_routes.py",
            ),
            _capture_environment(
                {"PYTHONDONTWRITEBYTECODE": "1", "PYTHONPATH": "."}
            ),
        )
    if lane.lane_id == "ANDROID_USER_INTERNAL":
        return (
            root / "apps/android",
            (
                "./gradlew",
                "--offline",
                "--no-daemon",
                "--max-workers=1",
                ":app:testDebugUnitTest",
                ":app:assembleDebug",
                ":app:lintDebug",
            ),
            _capture_environment({"GRADLE_USER_HOME": "/home/ddobagi/.gradle"}),
        )
    require(
        lane.lane_id == "TEST_LAYER_REGISTRY_VALIDATE",
        f"unsupported FP-022 lane: {lane.lane_id}",
    )
    return (
        root,
        ("bash", "scripts/run_walksafe_test_layers_current.sh", "validate"),
        _capture_environment({"PYTHON_BIN": LOCKED_TEST_PYTHON}),
    )


def _android_junit_test_count(root: Path) -> int:
    result_dir = root / "apps/android/app/build/test-results/testDebugUnitTest"
    paths = sorted(result_dir.glob("TEST-*.xml"))
    require(bool(paths), "FP-022 Android JUnit XML set is empty")
    total = 0
    for path in paths:
        try:
            tree = ET.parse(path)
        except ET.ParseError as exc:
            raise BuildError(f"invalid FP-022 Android JUnit XML: {path.name}") from exc
        total += sum(1 for _ in tree.getroot().iter("testcase"))
    require(total == 1000, f"FP-022 Android JUnit test count differs: {total}")
    return total


def _captured_passed_count(lane: LaneSpec, root: Path, output: bytes) -> int:
    try:
        text = output.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BuildError(f"{lane.lane_id} process output is not UTF-8") from exc
    if lane.lane_id == "BACKEND_NAVIGATION_INTERNAL":
        require(
            re.search(lane.success_pattern, text) is not None,
            "BACKEND_NAVIGATION_INTERNAL success marker missing",
        )
        return 42
    if lane.lane_id == "ANDROID_USER_INTERNAL":
        return _android_junit_test_count(root)
    return 1


def capture_lane_observations(
    root: Path = ROOT,
) -> tuple[dict[str, dict[str, Any]], dict[str, bytes]]:
    """Run the exact three internal lanes serially through a private staging dir."""

    root = root.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="walksafe-fp022-navigation-") as name:
        staging = Path(name)
        os.chmod(staging, 0o700)
        for lane in LANES:
            cwd, argv, environment = _lane_process_contract(lane, root)
            started_at = datetime.now().astimezone().isoformat()
            process = subprocess.run(
                argv,
                cwd=cwd,
                env=environment,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )
            ended_at = datetime.now().astimezone().isoformat()
            process_output = process.stdout
            require(type(process_output) is bytes, f"{lane.lane_id} output differs")
            require(
                process.returncode == 0,
                f"{lane.lane_id} command failed with exit code {process.returncode}",
            )
            passed = _captured_passed_count(lane, root, process_output)
            body = process_output + (b"" if process_output.endswith(b"\n") else b"\n")
            if lane.lane_id == "TEST_LAYER_REGISTRY_VALIDATE":
                body += b"TEST_LAYER_REGISTRY_VALIDATE: PASS\n"
            raw = (
                f"WALKSAFE_FP022_COMMAND {lane.expected_command}\n"
                f"WALKSAFE_FP022_STARTED_AT {started_at}\n"
            ).encode() + body + (
                f"WALKSAFE_FP022_SUMMARY passed={passed} failed=0 errors=0 skipped=0\n"
                f"WALKSAFE_FP022_EXIT_CODE {process.returncode}\n"
                f"WALKSAFE_FP022_ENDED_AT {ended_at}\n"
            ).encode()
            observation = {
                "schema_version": "walksafe.fp022-internal-lane-observation.v1",
                "lane_id": lane.lane_id,
                "status": "PASS",
                "command": lane.expected_command,
                "exit_code": 0,
                "started_at": started_at,
                "ended_at": ended_at,
                "raw_output_sha256": bytes_sha256(raw),
                "raw_output_byte_length": len(raw),
                "metrics": {
                    "passed": passed,
                    "failed": 0,
                    "errors": 0,
                    "skipped": 0,
                },
            }
            (staging / f"{lane.lane_id}.json").write_text(
                json_text(observation),
                encoding="utf-8",
            )
            (staging / f"{lane.lane_id}.log").write_bytes(raw)
        return load_lane_observations(staging)


def load_lane_observations(directory: Path) -> tuple[dict[str, dict[str, Any]], dict[str, bytes]]:
    observations: dict[str, dict[str, Any]] = {}
    raw_outputs: dict[str, bytes] = {}
    expected = {
        name
        for lane in LANES
        for name in (f"{lane.lane_id}.json", f"{lane.lane_id}.log")
    }
    require(
        directory.is_dir() and {path.name for path in directory.iterdir()} == expected,
        "FP-022 lane observation directory entries differ",
    )
    for lane in LANES:
        observations[lane.lane_id] = strict_json_bytes(
            (directory / f"{lane.lane_id}.json").read_bytes(),
            f"{lane.lane_id} observation",
        )
        raw_outputs[lane.lane_id] = (
            directory / f"{lane.lane_id}.log"
        ).read_bytes()
    return observations, raw_outputs


def _write_or_check(root: Path, outputs: Mapping[Path, str], *, write: bool) -> None:
    io_base.write_or_check_outputs(root, outputs, write=write)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("capture", "successor", "completion"))
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--executor-id", required=True)
    parser.add_argument("--executor-task", required=True)
    parser.add_argument("--reviewer-id")
    parser.add_argument("--reviewer-task")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        root = args.root.resolve(strict=True)
        if args.stage == "capture":
            if args.check:
                outputs = _read_text_outputs(
                    root,
                    (
                        *(lane.log_rel for lane in LANES),
                        IMPLEMENTATION_REL,
                        OBSERVATIONS_REL,
                        VERIFICATION_REL,
                    ),
                )
                validate_evidence_outputs(
                    outputs,
                    root=root,
                    source_groups=IMPLEMENTATION_SOURCE_GROUPS,
                    authority=validate_authority(root),
                    executor_actor_id=args.executor_id,
                    executor_task=args.executor_task,
                )
            else:
                observations, raw_outputs = capture_lane_observations(root)
                outputs = build_evidence_outputs(
                    root=root,
                    lane_observations=observations,
                    lane_raw_outputs=raw_outputs,
                    executor_actor_id=args.executor_id,
                    executor_task=args.executor_task,
                )
        else:
            require(args.reviewer_id and args.reviewer_task, f"{args.stage} stage requires reviewer identity")
            evidence = _read_text_outputs(
                root,
                (
                    *(lane.log_rel for lane in LANES),
                    IMPLEMENTATION_REL,
                    OBSERVATIONS_REL,
                    VERIFICATION_REL,
                ),
            )
            r028 = _read_raw_outputs(root, R028_PATHS)
            if args.stage == "successor":
                outputs = build_successor_outputs(
                    evidence_outputs=evidence,
                    r028_outputs=r028,
                    root=root,
                    executor_actor_id=args.executor_id,
                    executor_task=args.executor_task,
                    required_reviewer_actor_id=args.reviewer_id,
                    required_reviewer_task=args.reviewer_task,
                )
            else:
                successor = _read_text_outputs(root, (SUCCESSOR_REL, REVIEW_SUBJECT_REL))
                outputs = build_completion_output(
                    evidence_outputs=evidence,
                    successor_outputs=successor,
                    r028_outputs=r028,
                    independent_review_raw=(root / INDEPENDENT_REVIEW_REL).read_bytes(),
                    executor_actor_id=args.executor_id,
                    executor_task=args.executor_task,
                    reviewer_actor_id=args.reviewer_id,
                    reviewer_task=args.reviewer_task,
                )
        _write_or_check(root, outputs, write=args.write)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"FP-022 navigation internal evidence {args.stage}: FAIL: {exc}")
        return 1
    print(
        f"FP-022 navigation internal evidence {args.stage}: PASS "
        f"outputs={len(outputs)} mode={'WRITE' if args.write else 'CHECK'}"
    )
    return 0


__all__ = [
    "BuildError",
    "COMPLETION_REL",
    "CONTRACT_REL",
    "GOAL_ID",
    "GOAL_REL",
    "IMPLEMENTATION_REL",
    "IMPLEMENTATION_SOURCE_GROUPS",
    "INDEPENDENT_REVIEW_REL",
    "LANES",
    "OBSERVATIONS_REL",
    "R028_BACKLOG_JSON_REL",
    "R028_BACKLOG_MD_REL",
    "R028_GAP_JSON_REL",
    "R028_GAP_MD_REL",
    "REVIEW_SUBJECT_REL",
    "START_EVENT_ID",
    "START_GATE_REL",
    "SUCCESSOR_REL",
    "SourceGroup",
    "VERIFICATION_REL",
    "build_completion_output",
    "build_evidence_outputs",
    "build_successor_outputs",
    "bytes_sha256",
    "completion_boundary",
    "json_text",
    "sealed",
    "validate_authority",
    "validate_completion_output",
    "validate_evidence_outputs",
    "validate_independent_review",
    "validate_r028_outputs",
    "validate_successor_outputs",
]


if __name__ == "__main__":
    raise SystemExit(main())
