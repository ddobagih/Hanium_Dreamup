#!/usr/bin/env python3
"""Prepare the append-only zero-credit FP-046 R002 recovery correction."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import build_walksafe_fp046_r002_seq78_79_recovery_review_20260824 as review
from scripts import apply_walksafe_fp048_goal_completed_seq43_44_20260802 as transport
from scripts import check_walksafe_project_continuation_v2_3 as git_utility
from scripts import generate_repository_catalogs as catalogs


class ControlCorrectionError(RuntimeError):
    """The exact recovery correction cannot be proven or published."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ControlCorrectionError(message)


def strict_json_equal(actual: Any, expected: Any) -> bool:
    if type(actual) is not type(expected):
        return False
    if type(actual) is dict:
        return set(actual) == set(expected) and all(
            strict_json_equal(actual[key], expected[key]) for key in actual
        )
    if type(actual) is list:
        return len(actual) == len(expected) and all(
            strict_json_equal(left, right)
            for left, right in zip(actual, expected, strict=True)
        )
    return actual == expected


def _json_bytes(value: Mapping[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def _canonical_sha256(value: Any, *, omit: set[str] | None = None) -> str:
    if type(value) is dict and omit:
        value = {key: item for key, item in value.items() if key not in omit}
    raw = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode()
    return hashlib.sha256(raw).hexdigest()


def _event_sha256(event: Mapping[str, Any]) -> str:
    return _canonical_sha256(dict(event), omit={"event_sha256"})


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


CHECKPOINT_RELATIVE = Path("docs/control/walksafe-project-continuation-checkpoint.json")
CATALOG_PATHS = tuple(Path(path) for path in catalogs.OUTPUT_PATHS)
TARGET_GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R002"
GOAL_ID = TARGET_GOAL_ID
TARGET_GOAL_SHA256 = review.GOAL_SHA256
SEQ78_CORRECTION_SEQUENCE = 78
SEQ78_CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP046-R002-CORRECTION-20260824-001"
)
SEQ79_CORRECTION_SEQUENCE = 79
SEQ79_CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP046-R002-CORRECTION-20260824-002"
)
SEQ80_CORRECTION_SEQUENCE = 80
SEQ80_CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP046-R002-CORRECTION-20260824-003"
)
SEQ81_CORRECTION_SEQUENCE = 81
SEQ81_CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP046-R002-CORRECTION-20260824-004"
)
SEQ82_CORRECTION_SEQUENCE = 82
SEQ82_CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP046-R002-CORRECTION-20260824-005"
)
SEQ83_CORRECTION_SEQUENCE = 83
SEQ83_CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP046-R002-CORRECTION-20260824-006"
)
CONTROL_CORRECTION_SEQUENCE = 84
CONTROL_CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP046-R002-CORRECTION-20260824-007"
)
SEQ77_SOURCE_SEQUENCE = review.SOURCE_SEQUENCE
SEQ77_SOURCE_EVENT_ID = review.SOURCE_EVENT_ID
SEQ77_SOURCE_EVENT_SHA256 = review.SOURCE_EVENT_SHA256
SEQ77_SOURCE_CHECKPOINT_FILE_SHA256 = review.SOURCE_CHECKPOINT_SHA256
SEQ77_SOURCE_CHECKPOINT_BYTE_COUNT = review.SOURCE_CHECKPOINT_BYTE_LENGTH
SOURCE_SEQUENCE = review.REVIEW_SOURCE_SEQUENCE
SOURCE_EVENT_ID = review.REVIEW_SOURCE_EVENT_ID
SOURCE_EVENT_SHA256 = review.REVIEW_SOURCE_EVENT_SHA256
SOURCE_CHECKPOINT_FILE_SHA256 = review.REVIEW_SOURCE_CHECKPOINT_SHA256
SOURCE_CHECKPOINT_BYTE_COUNT = review.REVIEW_SOURCE_CHECKPOINT_BYTE_LENGTH
SEQ79_SOURCE_SEQUENCE = 78
SEQ79_SOURCE_EVENT_ID = SEQ78_CORRECTION_EVENT_ID
SEQ79_SOURCE_EVENT_SHA256 = (
    "9728db8dcbb747c2596fa48983932e4c7f303b3877407c39782945839ede836c"
)
SEQ79_SOURCE_CHECKPOINT_FILE_SHA256 = (
    "f951ee08b0ef981dd3fae9d58eb1cc978ccddce06e11c8a91885345e4b5b5651"
)
SEQ79_SOURCE_CHECKPOINT_BYTE_COUNT = 2_280_465
SEQ80_SOURCE_SEQUENCE = 79
SEQ80_SOURCE_EVENT_ID = SEQ79_CORRECTION_EVENT_ID
SEQ80_SOURCE_EVENT_SHA256 = (
    "e7ca0e7660d1506e3831f6a031ca7c1eb8951e484afe19bca5c3c180b2c543c2"
)
SEQ80_SOURCE_CHECKPOINT_FILE_SHA256 = (
    "4156820b7db9f6aa35a41d2183008db50a69651384c0f25444bcfde93ef31446"
)
SEQ80_SOURCE_CHECKPOINT_BYTE_COUNT = 2_310_488
SEQ81_SOURCE_SEQUENCE = 80
SEQ81_SOURCE_EVENT_ID = SEQ80_CORRECTION_EVENT_ID
SEQ81_SOURCE_EVENT_SHA256 = (
    "17ed039241e754554a98b5f1a1951f3a46bc80afcf2401b75623b7603d792866"
)
SEQ81_SOURCE_CHECKPOINT_FILE_SHA256 = (
    "100ac3fde7e8867af5b143bee55d49ceaf426ea7b1b781aeb168c40ab0c0ef5b"
)
SEQ81_SOURCE_CHECKPOINT_BYTE_COUNT = 2_339_012
SEQ82_SOURCE_SEQUENCE = 81
SEQ82_SOURCE_EVENT_ID = SEQ81_CORRECTION_EVENT_ID
SEQ82_SOURCE_EVENT_SHA256 = (
    "e8778adcf23cf8f05b9b2cac6e0de119d3a27cdb4f60390f2a212dd224e895b1"
)
SEQ82_SOURCE_CHECKPOINT_FILE_SHA256 = (
    "0debcb6438dfdf4e3bda6165f19ac82fd51a6f25024b138e642db1499ac0151a"
)
SEQ82_SOURCE_CHECKPOINT_BYTE_COUNT = 2_367_900
SEQ83_SOURCE_SEQUENCE = 82
SEQ83_SOURCE_EVENT_ID = SEQ82_CORRECTION_EVENT_ID
SEQ83_SOURCE_EVENT_SHA256 = (
    "33204f6ded1b4b2cf05fe039b409d072addc26ed78e4da867deb02632ba6ba53"
)
SEQ83_SOURCE_CHECKPOINT_FILE_SHA256 = (
    "26a8f735effa785833ce8c369c47c593cb69eb1747499147f790fd1ec65cbf80"
)
SEQ83_SOURCE_CHECKPOINT_BYTE_COUNT = 2_396_998
SOURCE_CHECKPOINT_SCHEMA_VERSION = "1.25.0"
STATIC_PLAN_MANIFEST_SHA256 = (
    "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
)
AUTHORIZED_BRANCH = "current"
AUTHORIZED_BASE_COMMIT = "f0093863e82bfc80d9f11915cef33a51d44b8730"
AUTHORIZED_HEAD_COMMIT = "8a146bbf9c5a7612377abb939fc4d94570d45cca"
AUTHORIZATION_RELATIVE = review.AUTHORIZATION_REL
AUTHORIZATION_SHA256 = review.AUTHORIZATION_SHA256
AUTHORIZATION_BYTE_COUNT = review.AUTHORIZATION_BYTE_LENGTH
SEQ78_AUTHORIZATION_RELATIVE = Path(
    "docs/control/execution/workstream-transitions/seq78-79/authorization.json"
)
SEQ78_AUTHORIZATION_BINDING = {
    "path": SEQ78_AUTHORIZATION_RELATIVE.as_posix(),
    "sha256": "15c2c399296a6d5c9a59e7763e94c1f04efee1abbd942ba96fe689a500c3d9c7",
    "byte_length": 5_098,
}
SEQ79_AUTHORIZATION_RELATIVE = Path(
    "docs/control/execution/workstream-transitions/seq79-80/authorization.json"
)
SEQ79_AUTHORIZATION_BINDING = {
    "path": SEQ79_AUTHORIZATION_RELATIVE.as_posix(),
    "sha256": "f5776efa3df0bf3d94959c05aa0145098bb7ae56afad4a65d879ed50ea9b3ed0",
    "byte_length": 3_541,
}
SEQ80_AUTHORIZATION_RELATIVE = Path(
    "docs/control/execution/workstream-transitions/seq80-81/authorization.json"
)
SEQ80_AUTHORIZATION_BINDING = {
    "path": SEQ80_AUTHORIZATION_RELATIVE.as_posix(),
    "sha256": "18596eff28c6a1ff6baac16d2afef8a274a07015797a5694fdc5bc9c046b25ba",
    "byte_length": 3_626,
}
SEQ81_AUTHORIZATION_RELATIVE = Path(
    "docs/control/execution/workstream-transitions/seq81-82/authorization.json"
)
SEQ81_AUTHORIZATION_BINDING = {
    "path": SEQ81_AUTHORIZATION_RELATIVE.as_posix(),
    "sha256": "4d0cc266d314c47d1f405758bd9b1d556b938a339cd1a706dbff1c20c513a9a5",
    "byte_length": 3_775,
}
SEQ82_AUTHORIZATION_RELATIVE = Path(
    "docs/control/execution/workstream-transitions/seq82-83/authorization.json"
)
SEQ82_AUTHORIZATION_BINDING = {
    "path": SEQ82_AUTHORIZATION_RELATIVE.as_posix(),
    "sha256": "9357fabc81719cd5ed5057b48e50d6aa4cdb1c3116b3b96a16b069fed0e70d55",
    "byte_length": 3_924,
}
SEQ83_AUTHORIZATION_RELATIVE = Path(
    "docs/control/execution/workstream-transitions/seq83-84/authorization.json"
)
SEQ83_AUTHORIZATION_BINDING = {
    "path": SEQ83_AUTHORIZATION_RELATIVE.as_posix(),
    "sha256": "9f199ed6f03901b4d703b73c9b040b64bcf7454ca4ad09c79de8aa2d1f82e144",
    "byte_length": 4_132,
}
SEQ78_START_GATE_RUNNER_BINDING = {
    "path": "scripts/run_walksafe_fp046_r002_goal_start_gate_20260823.py",
    "sha256": "bbfbac240005ea87b03f92580b875512acb2d41e126b1caeabfad1d016713850",
    "byte_length": 28_831,
}
SEQ78_TRANSITION_REVIEW_BINDING = {
    "assignment": {
        "path": review.APPROVED_R004_REVIEW_PATHS[0].as_posix(),
        "sha256": review.APPROVED_R004_REVIEW_PINS[
            review.APPROVED_R004_REVIEW_PATHS[0]
        ][0],
        "byte_length": review.APPROVED_R004_REVIEW_PINS[
            review.APPROVED_R004_REVIEW_PATHS[0]
        ][1],
    },
    "review_result": {
        "path": review.APPROVED_R004_REVIEW_PATHS[1].as_posix(),
        "sha256": review.APPROVED_R004_REVIEW_PINS[
            review.APPROVED_R004_REVIEW_PATHS[1]
        ][0],
        "byte_length": review.APPROVED_R004_REVIEW_PINS[
            review.APPROVED_R004_REVIEW_PATHS[1]
        ][1],
    },
    "independent_review": {
        "path": review.APPROVED_R004_REVIEW_PATHS[2].as_posix(),
        "sha256": review.APPROVED_R004_REVIEW_PINS[
            review.APPROVED_R004_REVIEW_PATHS[2]
        ][0],
        "byte_length": review.APPROVED_R004_REVIEW_PINS[
            review.APPROVED_R004_REVIEW_PATHS[2]
        ][1],
    },
}
SEQ79_START_GATE_RUNNER_BINDING = {
    "path": "scripts/run_walksafe_fp046_r002_goal_start_gate_20260823.py",
    "sha256": "7a690dd25021e99e8c1e85a0bbe55f5959ea52b3105309be6995da6b7bfd7004",
    "byte_length": 30_877,
}
SEQ79_TRANSITION_REVIEW_BINDING = {
    "assignment": {
        "path": review.APPROVED_R007_REVIEW_PATHS[0].as_posix(),
        "sha256": review.APPROVED_R007_REVIEW_PINS[
            review.APPROVED_R007_REVIEW_PATHS[0]
        ][0],
        "byte_length": review.APPROVED_R007_REVIEW_PINS[
            review.APPROVED_R007_REVIEW_PATHS[0]
        ][1],
    },
    "review_result": {
        "path": review.APPROVED_R007_REVIEW_PATHS[1].as_posix(),
        "sha256": review.APPROVED_R007_REVIEW_PINS[
            review.APPROVED_R007_REVIEW_PATHS[1]
        ][0],
        "byte_length": review.APPROVED_R007_REVIEW_PINS[
            review.APPROVED_R007_REVIEW_PATHS[1]
        ][1],
    },
    "independent_review": {
        "path": review.APPROVED_R007_REVIEW_PATHS[2].as_posix(),
        "sha256": review.APPROVED_R007_REVIEW_PINS[
            review.APPROVED_R007_REVIEW_PATHS[2]
        ][0],
        "byte_length": review.APPROVED_R007_REVIEW_PINS[
            review.APPROVED_R007_REVIEW_PATHS[2]
        ][1],
    },
}
SEQ80_START_GATE_RUNNER_BINDING = {
    "path": "scripts/run_walksafe_fp046_r002_goal_start_gate_20260823.py",
    "sha256": "35f2241be3b1302b953656effb95f5807f74b663f06c2a6c81b03db12e4826d3",
    "byte_length": 39_572,
}
SEQ80_TRANSITION_REVIEW_BINDING = {
    "assignment": {
        "path": review.APPROVED_R008_REVIEW_PATHS[0].as_posix(),
        "sha256": review.APPROVED_R008_REVIEW_PINS[
            review.APPROVED_R008_REVIEW_PATHS[0]
        ][0],
        "byte_length": review.APPROVED_R008_REVIEW_PINS[
            review.APPROVED_R008_REVIEW_PATHS[0]
        ][1],
    },
    "review_result": {
        "path": review.APPROVED_R008_REVIEW_PATHS[1].as_posix(),
        "sha256": review.APPROVED_R008_REVIEW_PINS[
            review.APPROVED_R008_REVIEW_PATHS[1]
        ][0],
        "byte_length": review.APPROVED_R008_REVIEW_PINS[
            review.APPROVED_R008_REVIEW_PATHS[1]
        ][1],
    },
    "independent_review": {
        "path": review.APPROVED_R008_REVIEW_PATHS[2].as_posix(),
        "sha256": review.APPROVED_R008_REVIEW_PINS[
            review.APPROVED_R008_REVIEW_PATHS[2]
        ][0],
        "byte_length": review.APPROVED_R008_REVIEW_PINS[
            review.APPROVED_R008_REVIEW_PATHS[2]
        ][1],
    },
}
SEQ81_START_GATE_RUNNER_BINDING = {
    "path": "scripts/run_walksafe_fp046_r002_goal_start_gate_20260823.py",
    "sha256": "0aea8d3a2c322d26e663a50b26b41a0ec9fc9aa4b514f6fff7f9fd300431d344",
    "byte_length": 52_842,
}
SEQ81_TRANSITION_REVIEW_BINDING = {
    "assignment": {
        "path": review.APPROVED_R009_REVIEW_PATHS[0].as_posix(),
        "sha256": review.APPROVED_R009_REVIEW_PINS[
            review.APPROVED_R009_REVIEW_PATHS[0]
        ][0],
        "byte_length": review.APPROVED_R009_REVIEW_PINS[
            review.APPROVED_R009_REVIEW_PATHS[0]
        ][1],
    },
    "review_result": {
        "path": review.APPROVED_R009_REVIEW_PATHS[1].as_posix(),
        "sha256": review.APPROVED_R009_REVIEW_PINS[
            review.APPROVED_R009_REVIEW_PATHS[1]
        ][0],
        "byte_length": review.APPROVED_R009_REVIEW_PINS[
            review.APPROVED_R009_REVIEW_PATHS[1]
        ][1],
    },
    "independent_review": {
        "path": review.APPROVED_R009_REVIEW_PATHS[2].as_posix(),
        "sha256": review.APPROVED_R009_REVIEW_PINS[
            review.APPROVED_R009_REVIEW_PATHS[2]
        ][0],
        "byte_length": review.APPROVED_R009_REVIEW_PINS[
            review.APPROVED_R009_REVIEW_PATHS[2]
        ][1],
    },
}
SEQ82_START_GATE_RUNNER_BINDING = {
    "path": "scripts/run_walksafe_fp046_r002_goal_start_gate_20260823.py",
    "sha256": "17393a6bf77d4535a5e3a750bde70a29e0d25317a2c0d9383d0ddbe97e1ba6d6",
    "byte_length": 55_239,
}
SEQ82_TRANSITION_REVIEW_BINDING = {
    "assignment": {
        "path": review.APPROVED_R011_REVIEW_PATHS[0].as_posix(),
        "sha256": review.APPROVED_R011_REVIEW_PINS[
            review.APPROVED_R011_REVIEW_PATHS[0]
        ][0],
        "byte_length": review.APPROVED_R011_REVIEW_PINS[
            review.APPROVED_R011_REVIEW_PATHS[0]
        ][1],
    },
    "review_result": {
        "path": review.APPROVED_R011_REVIEW_PATHS[1].as_posix(),
        "sha256": review.APPROVED_R011_REVIEW_PINS[
            review.APPROVED_R011_REVIEW_PATHS[1]
        ][0],
        "byte_length": review.APPROVED_R011_REVIEW_PINS[
            review.APPROVED_R011_REVIEW_PATHS[1]
        ][1],
    },
    "independent_review": {
        "path": review.APPROVED_R011_REVIEW_PATHS[2].as_posix(),
        "sha256": review.APPROVED_R011_REVIEW_PINS[
            review.APPROVED_R011_REVIEW_PATHS[2]
        ][0],
        "byte_length": review.APPROVED_R011_REVIEW_PINS[
            review.APPROVED_R011_REVIEW_PATHS[2]
        ][1],
    },
}
FAILED_GATE_002_STORED_BINDING = {
    "event_id": review.FAILED_GATE_002_EVENT_ID,
    "directory": review.FAILED_GATE_002_DIR.as_posix(),
    "directory_mode": "0700",
    "directory_inventory": [],
    "log_count": 0,
    "receipt_present": False,
}
START_GATE_RUNNER_RELATIVE = review.GATE_SCRIPT_REL
SCRIPT_RELATIVE = review.CORRECTION_SCRIPT_REL
TEST_RELATIVE = review.CORRECTION_TEST_REL
FAILED_GATE_EVENT_ID = review.FAILED_GATE_EVENT_ID
FAILED_GATE_LOG_RELATIVE = review.FAILED_GATE_LOG_REL
FAILED_GATE_LOG_SHA256 = review.FAILED_GATE_LOG_SHA256
FAILED_GATE_LOG_BYTE_COUNT = review.FAILED_GATE_LOG_BYTE_LENGTH
SEQ78_EXACT_ADD_ONLY_PATHS = (
    SEQ78_AUTHORIZATION_RELATIVE,
    review.REJECTED_R001_ASSIGNMENT_REL,
    *review.APPROVED_R002_REVIEW_PATHS,
    *review.APPROVED_R003_REVIEW_PATHS,
    *review.APPROVED_R004_REVIEW_PATHS,
    *review.ADDED_CONTROL_PATHS,
)
SEQ79_EXACT_ADD_ONLY_PATHS = (
    SEQ79_AUTHORIZATION_RELATIVE,
    *review.APPROVED_R005_REVIEW_PATHS,
    *review.APPROVED_R006_REVIEW_PATHS,
    *review.APPROVED_R007_REVIEW_PATHS,
)
SEQ80_EXACT_ADD_ONLY_PATHS = (
    SEQ80_AUTHORIZATION_RELATIVE,
    *review.APPROVED_R008_REVIEW_PATHS,
)
SEQ81_EXACT_ADD_ONLY_PATHS = (
    SEQ81_AUTHORIZATION_RELATIVE,
    *review.APPROVED_R009_REVIEW_PATHS,
)
SEQ82_EXACT_ADD_ONLY_PATHS = (
    SEQ82_AUTHORIZATION_RELATIVE,
    review.REJECTED_R010_ASSIGNMENT_REL,
    *review.APPROVED_R011_REVIEW_PATHS,
)
SEQ83_EXACT_ADD_ONLY_PATHS = (
    SEQ83_AUTHORIZATION_RELATIVE,
    *review.APPROVED_R012_REVIEW_PATHS,
)
EXACT_ADD_ONLY_PATHS = (
    AUTHORIZATION_RELATIVE,
    review.REJECTED_R013_ASSIGNMENT_REL,
    *review.REVIEW_PATHS,
)

EVENT_FIELDS = {
    "sequence", "event_id", "event_type", "occurred_on", "occurred_at",
    "previous_focus_goal_id", "previous_focus_content_sha256",
    "focus_goal_id", "focus_goal_content_sha256", "subject_goal_id",
    "from_status", "to_status", "static_plan_manifest_sha256",
    "status_changes", "runtime_after", "blockers_after",
    "blocker_resolution_ids_after", "source_checkpoint_version",
    "evidence_refs", "source_checkpoint_binding",
    "source_ready_event_binding", "authorization_binding",
    "contract_supersession", "start_gate_runner_binding",
    "transition_control_review_binding", "repository_context_reanchor",
    "claim_boundary", "unchanged_control_projection",
    "canonical_binding_snapshot_after", "previous_event_sha256",
    "event_sha256",
}

READY_CURRENT_FOCUS = (
    "EPIC-03 FP-046/GAP-055 R002 READY; seq84 recovery start gate "
    "AUTHORIZED/NOT_RUN"
)
SEQ83_READY_CURRENT_FOCUS = (
    "EPIC-03 FP-046/GAP-055 R002 READY; seq83 recovery start gate "
    "AUTHORIZED/NOT_RUN"
)
SEQ82_READY_CURRENT_FOCUS = (
    "EPIC-03 FP-046/GAP-055 R002 READY; seq82 recovery start gate "
    "AUTHORIZED/NOT_RUN"
)
SEQ81_READY_CURRENT_FOCUS = (
    "EPIC-03 FP-046/GAP-055 R002 READY; seq81 recovery start gate "
    "AUTHORIZED/NOT_RUN"
)
SEQ80_READY_CURRENT_FOCUS = (
    "EPIC-03 FP-046/GAP-055 R002 READY; seq80 recovery start gate "
    "AUTHORIZED/NOT_RUN"
)
SEQ79_READY_CURRENT_FOCUS = (
    "EPIC-03 FP-046/GAP-055 R002 READY; seq79 recovery start gate "
    "AUTHORIZED/NOT_RUN"
)
SEQ78_READY_CURRENT_FOCUS = (
    "EPIC-03 FP-046/GAP-055 R002 READY; seq78 recovery start gate "
    "AUTHORIZED/NOT_RUN"
)
FINAL_SCOPE = (
    "Graph v2.4 FP-046 R002/GAP-055 READY through zero-credit recovery "
    "start-control correction seq84; seq78/79/80/81/82/83 and PASS -004 preserved, R002 retained. "
    "No GOAL_STARTED, implementation, formal, device, deployment, signing, or "
    "release credit."
)
SEQ83_FINAL_SCOPE = (
    "Graph v2.4 FP-046 R002/GAP-055 READY through zero-credit recovery "
    "start-control correction seq83; seq78/79/80/81/82 and failed -001/-002/-003 gates preserved, R002 retained. "
    "No GOAL_STARTED, implementation, formal, device, deployment, signing, or "
    "release credit."
)
SEQ82_FINAL_SCOPE = (
    "Graph v2.4 FP-046 R002/GAP-055 READY through zero-credit recovery "
    "start-control correction seq82; seq78/79/80/81 and failed -001/-002/-003 gates preserved, R002 retained. "
    "No GOAL_STARTED, implementation, formal, device, deployment, signing, or "
    "release credit."
)
SEQ81_FINAL_SCOPE = (
    "Graph v2.4 FP-046 R002/GAP-055 READY through zero-credit recovery "
    "start-control correction seq81; seq78/79/80 and failed -001/-002/-003 gates preserved, R002 retained. "
    "No GOAL_STARTED, implementation, formal, device, deployment, signing, or "
    "release credit."
)
SEQ80_FINAL_SCOPE = (
    "Graph v2.4 FP-046 R002/GAP-055 READY through zero-credit recovery "
    "start-control correction seq80; seq78/79 and failed -001/-002 gates preserved, R002 retained. "
    "No GOAL_STARTED, implementation, formal, device, deployment, signing, or "
    "release credit."
)
SEQ79_FINAL_SCOPE = (
    "Graph v2.4 FP-046 R002/GAP-055 READY through zero-credit recovery "
    "start-control correction seq79; seq78 and failed -001 gate preserved, R002 retained. "
    "No GOAL_STARTED, implementation, formal, device, deployment, signing, or "
    "release credit."
)
SEQ78_FINAL_SCOPE = (
    "Graph v2.4 FP-046 R002/GAP-055 READY through zero-credit recovery "
    "start-control correction seq78; failed -001 gate preserved, R002 retained. "
    "No GOAL_STARTED, implementation, formal, device, deployment, signing, or "
    "release credit."
)


def _safe_file(root: Path, relative: Path, *, modes: frozenset[int] | None = None) -> Path:
    _require(
        not relative.is_absolute()
        and relative.parts
        and all(part not in {"", ".", ".."} for part in relative.parts),
        f"unsafe path: {relative}",
    )
    root = root.resolve(strict=True)
    current = root
    for part in relative.parts:
        current /= part
        _require(not current.is_symlink(), f"symlink path: {relative}")
    info = current.lstat()
    _require(
        stat.S_ISREG(info.st_mode)
        and info.st_nlink == 1
        and info.st_uid == os.getuid()
        and info.st_gid == os.getgid(),
        f"unsafe file: {relative}",
    )
    if modes is not None:
        _require(stat.S_IMODE(info.st_mode) in modes, f"file mode differs: {relative}")
    return current


def _binding(root: Path, relative: Path) -> dict[str, Any]:
    raw = _safe_file(root, relative).read_bytes()
    return {"path": relative.as_posix(), "sha256": _sha256_bytes(raw), "byte_length": len(raw)}


def authorization_binding(root: Path = ROOT) -> dict[str, Any]:
    row = _binding(root, AUTHORIZATION_RELATIVE)
    _require(
        row["sha256"] == AUTHORIZATION_SHA256
        and row["byte_length"] == AUTHORIZATION_BYTE_COUNT,
        "seq82/83 authorization differs",
    )
    return row


def start_gate_runner_binding(root: Path = ROOT) -> dict[str, Any]:
    return _binding(root, START_GATE_RUNNER_RELATIVE)


def _runtime_projection(state: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "focus_goal_id": state.get("focus_goal_id"),
        "focus_goal_path": state.get("focus_goal_path"),
        "focus_work_item_id": state.get("focus_work_item_id"),
        "focus_source": state.get("focus_source"),
        "ready_frontier_goal_ids": copy.deepcopy(state.get("ready_frontier_goal_ids")),
        "blocked_goal_ids": copy.deepcopy(state.get("blocked_goal_ids")),
        "pending_questions": copy.deepcopy(state.get("pending_questions")),
        "open_question_count": state.get("open_question_count"),
        "artifact_work_queue_sha256": _canonical_sha256(state.get("artifact_work_queue")),
        "completion_boundary_sha256": _canonical_sha256(state.get("completion_boundary")),
        "activation_status": state.get("activation_status"),
        "package_status": state.get("package_status"),
    }


def _projection_values(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    state = checkpoint["goal_execution"]
    return {
        "artifact": {
            "artifact_work_queue": copy.deepcopy(state.get("artifact_work_queue")),
            "artifact_state_counts": copy.deepcopy(checkpoint.get("approved_state", {}).get("artifact_state_counts")),
        },
        "canonical": copy.deepcopy(checkpoint.get("canonical_bindings")),
        "completion": {
            "completion_boundary": copy.deepcopy(state.get("completion_boundary")),
            "completion_evidence_by_goal": copy.deepcopy(state.get("completion_evidence_by_goal")),
            "archived_completion_evidence_by_goal": copy.deepcopy(state.get("archived_completion_evidence_by_goal")),
            "pending_reopen_goal_ids": copy.deepcopy(state.get("pending_reopen_goal_ids")),
            "pending_producer_completion_goal_id": state.get("pending_producer_completion_goal_id"),
        },
        "current_work": copy.deepcopy(checkpoint.get("current_work")),
        "runtime": {
            "runtime": _runtime_projection(state),
            "blockers_by_goal": copy.deepcopy(state.get("blockers_by_goal")),
            "blocker_resolution_history": copy.deepcopy(state.get("blocker_resolution_history")),
            "dynamic_goal_inventory": copy.deepcopy(state.get("dynamic_goal_inventory")),
            "materialized_child_goal_ids_by_parent": copy.deepcopy(state.get("materialized_child_goal_ids_by_parent")),
        },
        "status": {"goal_status": state.get("goal_status"), "status_by_goal": copy.deepcopy(state.get("status_by_goal"))},
        "verification": {"approved_state": copy.deepcopy(checkpoint.get("approved_state")), "verification_boundary": copy.deepcopy(checkpoint.get("verification_boundary"))},
    }


def _projection_hashes(checkpoint: Mapping[str, Any]) -> dict[str, str]:
    return {name: _canonical_sha256(value) for name, value in _projection_values(checkpoint).items()}


def _require_exact_source(source: Mapping[str, Any]) -> dict[str, Any]:
    _require(source.get("schema_version") == SOURCE_CHECKPOINT_SCHEMA_VERSION, "source schema differs")
    state = source.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    _require(type(history) is list and len(history) == SEQ77_SOURCE_SEQUENCE, "source is not seq77")
    event = history[-1]
    _require(
        type(event) is dict
        and event.get("sequence") == SEQ77_SOURCE_SEQUENCE
        and event.get("event_id") == SEQ77_SOURCE_EVENT_ID
        and event.get("event_sha256") == SEQ77_SOURCE_EVENT_SHA256
        and event.get("event_sha256") == _event_sha256(event)
        and state.get("transition_history_anchor_sha256") == SEQ77_SOURCE_EVENT_SHA256
        and state.get("status_by_goal", {}).get(TARGET_GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values(),
        "source seq77 authority differs",
    )
    return event


def _after_context(source: Mapping[str, Any], paths: list[str], path_sha256: str, content_sha256: str) -> dict[str, Any]:
    mirror = source["session_handoff"]["source_commit_or_snapshot"]
    return {
        "branch": AUTHORIZED_BRANCH,
        "base_commit": mirror["base_commit"],
        "current_head": AUTHORIZED_HEAD_COMMIT,
        "managed_changed_path_count": len(paths),
        "path_set_sha256": path_sha256,
        "content_set_sha256": content_sha256,
    }


def project_seq78(
    source: Mapping[str, Any], *, managed_paths: list[str],
    path_set_sha256: str, content_set_sha256: str,
    authorization_binding: Mapping[str, Any],
    transition_review_binding: Mapping[str, Mapping[str, Any]],
    event_occurred_at: str | None = None,
    runner_binding: Mapping[str, Any] | None = None,
    failed_gate_binding: Mapping[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    seq77 = _require_exact_source(source)
    _require(managed_paths == sorted(set(managed_paths)), "managed paths differ")
    if event_occurred_at is None:
        event_occurred_at = (
            datetime.fromisoformat(str(seq77["occurred_at"])) + timedelta(seconds=1)
        ).isoformat()
    occurred_at = datetime.fromisoformat(event_occurred_at)
    _require(occurred_at.tzinfo is not None and occurred_at.utcoffset() is not None, "event time is naive")
    checkpoint = copy.deepcopy(dict(source))
    state = checkpoint["goal_execution"]
    before_hashes = _projection_hashes(source)
    current_work = checkpoint["current_work"]
    current_work["current_focus"] = SEQ78_READY_CURRENT_FOCUS
    current_work["next_action"] = "FP-046 R002 초기 시작 gate를 새 -002 event ID로 재실행한다."
    event = {
        "sequence": SEQ78_CORRECTION_SEQUENCE,
        "event_id": SEQ78_CORRECTION_EVENT_ID,
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "occurred_on": occurred_at.date().isoformat(),
        "occurred_at": event_occurred_at,
        "previous_focus_goal_id": TARGET_GOAL_ID,
        "previous_focus_content_sha256": TARGET_GOAL_SHA256,
        "focus_goal_id": TARGET_GOAL_ID,
        "focus_goal_content_sha256": TARGET_GOAL_SHA256,
        "subject_goal_id": TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "static_plan_manifest_sha256": STATIC_PLAN_MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": _runtime_projection(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [
            "FP046-R002_FAILED_INITIAL_START_GATE_001",
            "FP046-R002_SEQ78_79_RECOVERY_AUTHORIZATION",
            "FP046-R002_SEQ78_79_RECOVERY_REVIEW",
            "FP046-R002_INITIAL_START_GATE_CONTRACT_R002_RETAINED",
        ],
        "source_checkpoint_binding": {
            "path": CHECKPOINT_RELATIVE.as_posix(),
            "sha256": SEQ77_SOURCE_CHECKPOINT_FILE_SHA256,
            "byte_length": SEQ77_SOURCE_CHECKPOINT_BYTE_COUNT,
            "sequence": SEQ77_SOURCE_SEQUENCE,
            "tail_event_id": SEQ77_SOURCE_EVENT_ID,
            "tail_event_sha256": SEQ77_SOURCE_EVENT_SHA256,
            "failed_gate_attempt": copy.deepcopy(
                dict(failed_gate_binding or review.failed_gate_attempt_binding(ROOT))
            ),
        },
        "source_ready_event_binding": copy.deepcopy(seq77["source_ready_event_binding"]),
        "authorization_binding": copy.deepcopy(dict(authorization_binding)),
        "contract_supersession": copy.deepcopy(seq77["contract_supersession"]),
        "start_gate_runner_binding": copy.deepcopy(dict(runner_binding or {})),
        "transition_control_review_binding": copy.deepcopy({key: dict(value) for key, value in transition_review_binding.items()}),
        "repository_context_reanchor": {
            "before": copy.deepcopy(seq77["repository_context_reanchor"]["after"]),
            "after": _after_context(source, managed_paths, path_set_sha256, content_set_sha256),
        },
        "claim_boundary": copy.deepcopy(seq77["claim_boundary"]),
        "unchanged_control_projection": {},
        "canonical_binding_snapshot_after": copy.deepcopy(seq77["canonical_binding_snapshot_after"]),
        "previous_event_sha256": SEQ77_SOURCE_EVENT_SHA256,
    }
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = ""
    state["validation_cutoff_at"] = event_occurred_at
    snapshot = checkpoint["working_tree_snapshot"]
    snapshot.update({
        "base_head": AUTHORIZED_BASE_COMMIT,
        "managed_changed_paths": copy.deepcopy(managed_paths),
        "managed_changed_path_count": len(managed_paths),
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": content_set_sha256,
        "scope": SEQ78_FINAL_SCOPE,
    })
    handoff = checkpoint["session_handoff"]
    handoff["branch"] = AUTHORIZED_BRANCH
    handoff["changed_files"] = copy.deepcopy(managed_paths)
    handoff["current_epic"] = "EPIC-03 / FP-046/GAP-055 R002 READY"
    handoff["next_single_action"] = current_work["next_action"]
    handoff["source_commit_or_snapshot"].update({
        "base_commit": AUTHORIZED_BASE_COMMIT,
        "current_head": AUTHORIZED_HEAD_COMMIT,
        "file_count": len(managed_paths),
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": content_set_sha256,
    })
    after_hashes = _projection_hashes(checkpoint)
    event["unchanged_control_projection"] = {
        name: {"before_sha256": before_hashes[name], "after_sha256": after_hashes[name]}
        for name in sorted(before_hashes)
    }
    event["event_sha256"] = _event_sha256(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    _require(set(event) == EVENT_FIELDS, "correction event fields differ")
    return checkpoint, event


def _require_exact_seq78_source(source: Mapping[str, Any]) -> dict[str, Any]:
    _require(source.get("schema_version") == SOURCE_CHECKPOINT_SCHEMA_VERSION, "source schema differs")
    state = source.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    _require(type(history) is list and len(history) == SEQ79_SOURCE_SEQUENCE, "source is not seq78")
    event = history[-1]
    _require(
        type(event) is dict
        and event.get("sequence") == SEQ79_SOURCE_SEQUENCE
        and event.get("event_id") == SEQ79_SOURCE_EVENT_ID
        and event.get("event_sha256") == SEQ79_SOURCE_EVENT_SHA256
        and event.get("event_sha256") == _event_sha256(event)
        and state.get("transition_history_anchor_sha256") == SEQ79_SOURCE_EVENT_SHA256
        and state.get("status_by_goal", {}).get(TARGET_GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values(),
        "source seq78 authority differs",
    )
    return event


def project_seq79(
    source: Mapping[str, Any], *, managed_paths: list[str],
    path_set_sha256: str, content_set_sha256: str,
    authorization_binding: Mapping[str, Any],
    transition_review_binding: Mapping[str, Mapping[str, Any]],
    event_occurred_at: str | None = None,
    runner_binding: Mapping[str, Any] | None = None,
    failed_gate_binding: Mapping[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    source_event = _require_exact_seq78_source(source)
    _require(managed_paths == sorted(set(managed_paths)), "managed paths differ")
    if event_occurred_at is None:
        event_occurred_at = (
            datetime.fromisoformat(str(source_event["occurred_at"]))
            + timedelta(seconds=1)
        ).isoformat()
    occurred_at = datetime.fromisoformat(event_occurred_at)
    _require(occurred_at.tzinfo is not None and occurred_at.utcoffset() is not None, "event time is naive")
    checkpoint = copy.deepcopy(dict(source))
    state = checkpoint["goal_execution"]
    before_hashes = _projection_hashes(source)
    current_work = checkpoint["current_work"]
    current_work["current_focus"] = SEQ79_READY_CURRENT_FOCUS
    current_work["next_action"] = "FP-046 R002 초기 시작 gate를 -002 event ID로 재실행한다."
    event = {
        "sequence": SEQ79_CORRECTION_SEQUENCE,
        "event_id": SEQ79_CORRECTION_EVENT_ID,
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "occurred_on": occurred_at.date().isoformat(),
        "occurred_at": event_occurred_at,
        "previous_focus_goal_id": TARGET_GOAL_ID,
        "previous_focus_content_sha256": TARGET_GOAL_SHA256,
        "focus_goal_id": TARGET_GOAL_ID,
        "focus_goal_content_sha256": TARGET_GOAL_SHA256,
        "subject_goal_id": TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "static_plan_manifest_sha256": STATIC_PLAN_MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": _runtime_projection(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [
            "FP046-R002_FAILED_INITIAL_START_GATE_001",
            "FP046-R002_SEQ79_80_RECOVERY_AUTHORIZATION",
            "FP046-R002_SEQ79_80_RECOVERY_REVIEW",
            "FP046-R002_SEQ78_CORRECTION_PRESERVED",
            "FP046-R002_INITIAL_START_GATE_CONTRACT_R002_RETAINED",
        ],
        "source_checkpoint_binding": {
            "path": CHECKPOINT_RELATIVE.as_posix(),
            "sha256": SEQ79_SOURCE_CHECKPOINT_FILE_SHA256,
            "byte_length": SEQ79_SOURCE_CHECKPOINT_BYTE_COUNT,
            "sequence": SEQ79_SOURCE_SEQUENCE,
            "tail_event_id": SEQ79_SOURCE_EVENT_ID,
            "tail_event_sha256": SEQ79_SOURCE_EVENT_SHA256,
            "failed_gate_attempt": copy.deepcopy(
                dict(failed_gate_binding or review.failed_gate_attempt_binding(ROOT))
            ),
        },
        "source_ready_event_binding": copy.deepcopy(source_event["source_ready_event_binding"]),
        "authorization_binding": copy.deepcopy(dict(authorization_binding)),
        "contract_supersession": copy.deepcopy(source_event["contract_supersession"]),
        "start_gate_runner_binding": copy.deepcopy(dict(runner_binding or {})),
        "transition_control_review_binding": copy.deepcopy(
            {key: dict(value) for key, value in transition_review_binding.items()}
        ),
        "repository_context_reanchor": {
            "before": copy.deepcopy(source_event["repository_context_reanchor"]["after"]),
            "after": _after_context(source, managed_paths, path_set_sha256, content_set_sha256),
        },
        "claim_boundary": copy.deepcopy(source_event["claim_boundary"]),
        "unchanged_control_projection": {},
        "canonical_binding_snapshot_after": copy.deepcopy(source_event["canonical_binding_snapshot_after"]),
        "previous_event_sha256": SEQ79_SOURCE_EVENT_SHA256,
    }
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = ""
    state["validation_cutoff_at"] = event_occurred_at
    snapshot = checkpoint["working_tree_snapshot"]
    snapshot.update({
        "base_head": AUTHORIZED_BASE_COMMIT,
        "managed_changed_paths": copy.deepcopy(managed_paths),
        "managed_changed_path_count": len(managed_paths),
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": content_set_sha256,
        "scope": SEQ79_FINAL_SCOPE,
    })
    handoff = checkpoint["session_handoff"]
    handoff["branch"] = AUTHORIZED_BRANCH
    handoff["changed_files"] = copy.deepcopy(managed_paths)
    handoff["current_epic"] = "EPIC-03 / FP-046/GAP-055 R002 READY"
    handoff["next_single_action"] = current_work["next_action"]
    handoff["source_commit_or_snapshot"].update({
        "base_commit": AUTHORIZED_BASE_COMMIT,
        "current_head": AUTHORIZED_HEAD_COMMIT,
        "file_count": len(managed_paths),
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": content_set_sha256,
    })
    after_hashes = _projection_hashes(checkpoint)
    event["unchanged_control_projection"] = {
        name: {"before_sha256": before_hashes[name], "after_sha256": after_hashes[name]}
        for name in sorted(before_hashes)
    }
    event["event_sha256"] = _event_sha256(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    _require(set(event) == EVENT_FIELDS, "seq79 correction event fields differ")
    return checkpoint, event


def _require_exact_seq79_source(source: Mapping[str, Any]) -> dict[str, Any]:
    _require(
        source.get("schema_version") == SOURCE_CHECKPOINT_SCHEMA_VERSION,
        "source schema differs",
    )
    state = source.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    _require(
        type(history) is list and len(history) == SEQ80_SOURCE_SEQUENCE,
        "source is not seq79",
    )
    event = history[-1]
    _require(
        type(event) is dict
        and event.get("sequence") == SEQ80_SOURCE_SEQUENCE
        and event.get("event_id") == SEQ80_SOURCE_EVENT_ID
        and event.get("event_sha256") == SEQ80_SOURCE_EVENT_SHA256
        and event.get("event_sha256") == _event_sha256(event)
        and state.get("transition_history_anchor_sha256") == SEQ80_SOURCE_EVENT_SHA256
        and state.get("status_by_goal", {}).get(TARGET_GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values(),
        "source seq79 authority differs",
    )
    return event


def project_seq80(
    source: Mapping[str, Any], *, managed_paths: list[str],
    path_set_sha256: str, content_set_sha256: str,
    authorization_binding: Mapping[str, Any],
    transition_review_binding: Mapping[str, Mapping[str, Any]],
    event_occurred_at: str | None = None,
    runner_binding: Mapping[str, Any] | None = None,
    failed_gate_binding: Mapping[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    source_event = _require_exact_seq79_source(source)
    _require(managed_paths == sorted(set(managed_paths)), "managed paths differ")
    if event_occurred_at is None:
        event_occurred_at = (
            datetime.fromisoformat(str(source_event["occurred_at"]))
            + timedelta(seconds=1)
        ).isoformat()
    occurred_at = datetime.fromisoformat(event_occurred_at)
    _require(
        occurred_at.tzinfo is not None and occurred_at.utcoffset() is not None,
        "event time is naive",
    )
    checkpoint = copy.deepcopy(dict(source))
    state = checkpoint["goal_execution"]
    before_hashes = _projection_hashes(source)
    current_work = checkpoint["current_work"]
    current_work["current_focus"] = SEQ80_READY_CURRENT_FOCUS
    current_work["next_action"] = (
        "FP-046 R002 초기 시작 gate를 -003 event ID로 재실행한다."
    )
    event = {
        "sequence": SEQ80_CORRECTION_SEQUENCE,
        "event_id": SEQ80_CORRECTION_EVENT_ID,
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "occurred_on": occurred_at.date().isoformat(),
        "occurred_at": event_occurred_at,
        "previous_focus_goal_id": TARGET_GOAL_ID,
        "previous_focus_content_sha256": TARGET_GOAL_SHA256,
        "focus_goal_id": TARGET_GOAL_ID,
        "focus_goal_content_sha256": TARGET_GOAL_SHA256,
        "subject_goal_id": TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "static_plan_manifest_sha256": STATIC_PLAN_MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": _runtime_projection(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [
            "FP046-R002_FAILED_INITIAL_START_GATE_001",
            "FP046-R002_FAILED_RECOVERY_START_GATE_002",
            "FP046-R002_SEQ80_81_RECOVERY_AUTHORIZATION",
            "FP046-R002_SEQ80_81_RECOVERY_REVIEW",
            "FP046-R002_SEQ78_79_CORRECTIONS_PRESERVED",
            "FP046-R002_INITIAL_START_GATE_CONTRACT_R002_RETAINED",
        ],
        "source_checkpoint_binding": {
            "path": CHECKPOINT_RELATIVE.as_posix(),
            "sha256": SEQ80_SOURCE_CHECKPOINT_FILE_SHA256,
            "byte_length": SEQ80_SOURCE_CHECKPOINT_BYTE_COUNT,
            "sequence": SEQ80_SOURCE_SEQUENCE,
            "tail_event_id": SEQ80_SOURCE_EVENT_ID,
            "tail_event_sha256": SEQ80_SOURCE_EVENT_SHA256,
            "failed_gate_attempt": copy.deepcopy(
                dict(
                    failed_gate_binding
                    or review.failed_gate_attempt_002_binding(ROOT)
                )
            ),
        },
        "source_ready_event_binding": copy.deepcopy(
            source_event["source_ready_event_binding"]
        ),
        "authorization_binding": copy.deepcopy(dict(authorization_binding)),
        "contract_supersession": copy.deepcopy(
            source_event["contract_supersession"]
        ),
        "start_gate_runner_binding": copy.deepcopy(dict(runner_binding or {})),
        "transition_control_review_binding": copy.deepcopy(
            {
                key: dict(value)
                for key, value in transition_review_binding.items()
            }
        ),
        "repository_context_reanchor": {
            "before": copy.deepcopy(
                source_event["repository_context_reanchor"]["after"]
            ),
            "after": _after_context(
                source, managed_paths, path_set_sha256, content_set_sha256
            ),
        },
        "claim_boundary": copy.deepcopy(source_event["claim_boundary"]),
        "unchanged_control_projection": {},
        "canonical_binding_snapshot_after": copy.deepcopy(
            source_event["canonical_binding_snapshot_after"]
        ),
        "previous_event_sha256": SEQ80_SOURCE_EVENT_SHA256,
    }
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = ""
    state["validation_cutoff_at"] = event_occurred_at
    snapshot = checkpoint["working_tree_snapshot"]
    snapshot.update({
        "base_head": AUTHORIZED_BASE_COMMIT,
        "managed_changed_paths": copy.deepcopy(managed_paths),
        "managed_changed_path_count": len(managed_paths),
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": content_set_sha256,
        "scope": SEQ80_FINAL_SCOPE,
    })
    handoff = checkpoint["session_handoff"]
    handoff["branch"] = AUTHORIZED_BRANCH
    handoff["changed_files"] = copy.deepcopy(managed_paths)
    handoff["current_epic"] = "EPIC-03 / FP-046/GAP-055 R002 READY"
    handoff["next_single_action"] = current_work["next_action"]
    handoff["source_commit_or_snapshot"].update({
        "base_commit": AUTHORIZED_BASE_COMMIT,
        "current_head": AUTHORIZED_HEAD_COMMIT,
        "file_count": len(managed_paths),
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": content_set_sha256,
    })
    after_hashes = _projection_hashes(checkpoint)
    event["unchanged_control_projection"] = {
        name: {
            "before_sha256": before_hashes[name],
            "after_sha256": after_hashes[name],
        }
        for name in sorted(before_hashes)
    }
    event["event_sha256"] = _event_sha256(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    _require(set(event) == EVENT_FIELDS, "seq80 correction event fields differ")
    return checkpoint, event


def _require_exact_seq80_source(source: Mapping[str, Any]) -> dict[str, Any]:
    _require(
        source.get("schema_version") == SOURCE_CHECKPOINT_SCHEMA_VERSION,
        "source schema differs",
    )
    state = source.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    _require(
        type(history) is list and len(history) == SEQ81_SOURCE_SEQUENCE,
        "source is not seq80",
    )
    event = history[-1]
    _require(
        type(event) is dict
        and event.get("sequence") == SEQ81_SOURCE_SEQUENCE
        and event.get("event_id") == SEQ81_SOURCE_EVENT_ID
        and event.get("event_sha256") == SEQ81_SOURCE_EVENT_SHA256
        and event.get("event_sha256") == _event_sha256(event)
        and state.get("transition_history_anchor_sha256") == SEQ81_SOURCE_EVENT_SHA256
        and state.get("status_by_goal", {}).get(TARGET_GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values(),
        "source seq80 authority differs",
    )
    return event


def project_seq81(
    source: Mapping[str, Any], *, managed_paths: list[str],
    path_set_sha256: str, content_set_sha256: str,
    authorization_binding: Mapping[str, Any],
    transition_review_binding: Mapping[str, Mapping[str, Any]],
    event_occurred_at: str | None = None,
    runner_binding: Mapping[str, Any] | None = None,
    failed_gate_binding: Mapping[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    source_event = _require_exact_seq80_source(source)
    _require(managed_paths == sorted(set(managed_paths)), "managed paths differ")
    if event_occurred_at is None:
        event_occurred_at = (
            datetime.fromisoformat(str(source_event["occurred_at"]))
            + timedelta(seconds=1)
        ).isoformat()
    occurred_at = datetime.fromisoformat(event_occurred_at)
    _require(
        occurred_at.tzinfo is not None and occurred_at.utcoffset() is not None,
        "event time is naive",
    )
    checkpoint = copy.deepcopy(dict(source))
    state = checkpoint["goal_execution"]
    before_hashes = _projection_hashes(source)
    current_work = checkpoint["current_work"]
    current_work["current_focus"] = SEQ81_READY_CURRENT_FOCUS
    current_work["next_action"] = (
        "FP-046 R002 초기 시작 gate를 -004 event ID로 재실행한다."
    )
    event = {
        "sequence": SEQ81_CORRECTION_SEQUENCE,
        "event_id": SEQ81_CORRECTION_EVENT_ID,
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "occurred_on": occurred_at.date().isoformat(),
        "occurred_at": event_occurred_at,
        "previous_focus_goal_id": TARGET_GOAL_ID,
        "previous_focus_content_sha256": TARGET_GOAL_SHA256,
        "focus_goal_id": TARGET_GOAL_ID,
        "focus_goal_content_sha256": TARGET_GOAL_SHA256,
        "subject_goal_id": TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "static_plan_manifest_sha256": STATIC_PLAN_MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": _runtime_projection(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [
            "FP046-R002_FAILED_INITIAL_START_GATE_001",
            "FP046-R002_FAILED_RECOVERY_START_GATE_002",
            "FP046-R002_FAILED_RECOVERY_START_GATE_003",
            "FP046-R002_SEQ81_82_RECOVERY_AUTHORIZATION",
            "FP046-R002_SEQ81_82_RECOVERY_REVIEW",
            "FP046-R002_SEQ78_79_80_CORRECTIONS_PRESERVED",
            "FP046-R002_INITIAL_START_GATE_CONTRACT_R002_RETAINED",
        ],
        "source_checkpoint_binding": {
            "path": CHECKPOINT_RELATIVE.as_posix(),
            "sha256": SEQ81_SOURCE_CHECKPOINT_FILE_SHA256,
            "byte_length": SEQ81_SOURCE_CHECKPOINT_BYTE_COUNT,
            "sequence": SEQ81_SOURCE_SEQUENCE,
            "tail_event_id": SEQ81_SOURCE_EVENT_ID,
            "tail_event_sha256": SEQ81_SOURCE_EVENT_SHA256,
            "failed_gate_attempt": copy.deepcopy(
                dict(
                    failed_gate_binding
                    or review.failed_gate_attempt_003_binding(ROOT)
                )
            ),
        },
        "source_ready_event_binding": copy.deepcopy(
            source_event["source_ready_event_binding"]
        ),
        "authorization_binding": copy.deepcopy(dict(authorization_binding)),
        "contract_supersession": copy.deepcopy(
            source_event["contract_supersession"]
        ),
        "start_gate_runner_binding": copy.deepcopy(dict(runner_binding or {})),
        "transition_control_review_binding": copy.deepcopy(
            {key: dict(value) for key, value in transition_review_binding.items()}
        ),
        "repository_context_reanchor": {
            "before": copy.deepcopy(
                source_event["repository_context_reanchor"]["after"]
            ),
            "after": _after_context(
                source, managed_paths, path_set_sha256, content_set_sha256
            ),
        },
        "claim_boundary": copy.deepcopy(source_event["claim_boundary"]),
        "unchanged_control_projection": {},
        "canonical_binding_snapshot_after": copy.deepcopy(
            source_event["canonical_binding_snapshot_after"]
        ),
        "previous_event_sha256": SEQ81_SOURCE_EVENT_SHA256,
    }
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = ""
    state["validation_cutoff_at"] = event_occurred_at
    snapshot = checkpoint["working_tree_snapshot"]
    snapshot.update({
        "base_head": AUTHORIZED_BASE_COMMIT,
        "managed_changed_paths": copy.deepcopy(managed_paths),
        "managed_changed_path_count": len(managed_paths),
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": content_set_sha256,
        "scope": SEQ81_FINAL_SCOPE,
    })
    handoff = checkpoint["session_handoff"]
    handoff["branch"] = AUTHORIZED_BRANCH
    handoff["changed_files"] = copy.deepcopy(managed_paths)
    handoff["current_epic"] = "EPIC-03 / FP-046/GAP-055 R002 READY"
    handoff["next_single_action"] = current_work["next_action"]
    handoff["source_commit_or_snapshot"].update({
        "base_commit": AUTHORIZED_BASE_COMMIT,
        "current_head": AUTHORIZED_HEAD_COMMIT,
        "file_count": len(managed_paths),
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": content_set_sha256,
    })
    after_hashes = _projection_hashes(checkpoint)
    event["unchanged_control_projection"] = {
        name: {
            "before_sha256": before_hashes[name],
            "after_sha256": after_hashes[name],
        }
        for name in sorted(before_hashes)
    }
    event["event_sha256"] = _event_sha256(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    _require(set(event) == EVENT_FIELDS, "seq81 correction event fields differ")
    return checkpoint, event


def _require_exact_correction_source(
    source: Mapping[str, Any],
    *,
    sequence: int,
    event_id: str,
    event_sha256: str,
) -> dict[str, Any]:
    _require(
        source.get("schema_version") == SOURCE_CHECKPOINT_SCHEMA_VERSION,
        "source schema differs",
    )
    state = source.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    _require(
        type(history) is list and len(history) == sequence,
        f"source is not seq{sequence}",
    )
    event = history[-1]
    _require(
        type(event) is dict
        and event.get("sequence") == sequence
        and event.get("event_id") == event_id
        and event.get("event_sha256") == event_sha256
        and event.get("event_sha256") == _event_sha256(event)
        and state.get("transition_history_anchor_sha256") == event_sha256
        and state.get("status_by_goal", {}).get(TARGET_GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values(),
        f"source seq{sequence} authority differs",
    )
    return event


def _project_correction(
    source: Mapping[str, Any], *, managed_paths: list[str],
    path_set_sha256: str, content_set_sha256: str,
    authorization_binding: Mapping[str, Any],
    transition_review_binding: Mapping[str, Mapping[str, Any]],
    correction_sequence: int,
    correction_event_id: str,
    source_sequence: int,
    source_event_id: str,
    source_event_sha256: str,
    source_checkpoint_sha256: str,
    source_checkpoint_byte_count: int,
    ready_current_focus: str,
    final_scope: str,
    evidence_refs: list[str],
    event_occurred_at: str | None = None,
    runner_binding: Mapping[str, Any] | None = None,
    failed_gate_binding: Mapping[str, Any] | None = None,
    source_gate_binding_key: str = "failed_gate_attempt",
    next_gate_event_id: str = "004",
) -> tuple[dict[str, Any], dict[str, Any]]:
    source_event = _require_exact_correction_source(
        source,
        sequence=source_sequence,
        event_id=source_event_id,
        event_sha256=source_event_sha256,
    )
    _require(managed_paths == sorted(set(managed_paths)), "managed paths differ")
    if event_occurred_at is None:
        event_occurred_at = (
            datetime.fromisoformat(str(source_event["occurred_at"]))
            + timedelta(seconds=1)
        ).isoformat()
    occurred_at = datetime.fromisoformat(event_occurred_at)
    _require(
        occurred_at.tzinfo is not None and occurred_at.utcoffset() is not None,
        "event time is naive",
    )
    checkpoint = copy.deepcopy(dict(source))
    state = checkpoint["goal_execution"]
    before_hashes = _projection_hashes(source)
    current_work = checkpoint["current_work"]
    current_work["current_focus"] = ready_current_focus
    current_work["next_action"] = (
        f"FP-046 R002 초기 시작 gate를 -{next_gate_event_id} event ID로 재실행한다."
    )
    event = {
        "sequence": correction_sequence,
        "event_id": correction_event_id,
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "occurred_on": occurred_at.date().isoformat(),
        "occurred_at": event_occurred_at,
        "previous_focus_goal_id": TARGET_GOAL_ID,
        "previous_focus_content_sha256": TARGET_GOAL_SHA256,
        "focus_goal_id": TARGET_GOAL_ID,
        "focus_goal_content_sha256": TARGET_GOAL_SHA256,
        "subject_goal_id": TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "static_plan_manifest_sha256": STATIC_PLAN_MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": _runtime_projection(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": copy.deepcopy(evidence_refs),
        "source_checkpoint_binding": {
            "path": CHECKPOINT_RELATIVE.as_posix(),
            "sha256": source_checkpoint_sha256,
            "byte_length": source_checkpoint_byte_count,
            "sequence": source_sequence,
            "tail_event_id": source_event_id,
            "tail_event_sha256": source_event_sha256,
            source_gate_binding_key: copy.deepcopy(
                dict(
                    failed_gate_binding
                    or (
                        review.STORED_PASSED_GATE_ATTEMPT_004
                        if source_gate_binding_key
                        == "passed_gate_attempt_004"
                        else review.STORED_FAILED_GATE_ATTEMPT_003
                    )
                )
            ),
        },
        "source_ready_event_binding": copy.deepcopy(
            source_event["source_ready_event_binding"]
        ),
        "authorization_binding": copy.deepcopy(dict(authorization_binding)),
        "contract_supersession": copy.deepcopy(
            source_event["contract_supersession"]
        ),
        "start_gate_runner_binding": copy.deepcopy(dict(runner_binding or {})),
        "transition_control_review_binding": copy.deepcopy(
            {key: dict(value) for key, value in transition_review_binding.items()}
        ),
        "repository_context_reanchor": {
            "before": copy.deepcopy(
                source_event["repository_context_reanchor"]["after"]
            ),
            "after": _after_context(
                source, managed_paths, path_set_sha256, content_set_sha256
            ),
        },
        "claim_boundary": copy.deepcopy(source_event["claim_boundary"]),
        "unchanged_control_projection": {},
        "canonical_binding_snapshot_after": copy.deepcopy(
            source_event["canonical_binding_snapshot_after"]
        ),
        "previous_event_sha256": source_event_sha256,
    }
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = ""
    state["validation_cutoff_at"] = event_occurred_at
    snapshot = checkpoint["working_tree_snapshot"]
    snapshot.update({
        "base_head": AUTHORIZED_BASE_COMMIT,
        "managed_changed_paths": copy.deepcopy(managed_paths),
        "managed_changed_path_count": len(managed_paths),
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": content_set_sha256,
        "scope": final_scope,
    })
    handoff = checkpoint["session_handoff"]
    handoff["branch"] = AUTHORIZED_BRANCH
    handoff["changed_files"] = copy.deepcopy(managed_paths)
    handoff["current_epic"] = "EPIC-03 / FP-046/GAP-055 R002 READY"
    handoff["next_single_action"] = current_work["next_action"]
    handoff["source_commit_or_snapshot"].update({
        "base_commit": AUTHORIZED_BASE_COMMIT,
        "current_head": AUTHORIZED_HEAD_COMMIT,
        "file_count": len(managed_paths),
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": content_set_sha256,
    })
    after_hashes = _projection_hashes(checkpoint)
    event["unchanged_control_projection"] = {
        name: {
            "before_sha256": before_hashes[name],
            "after_sha256": after_hashes[name],
        }
        for name in sorted(before_hashes)
    }
    event["event_sha256"] = _event_sha256(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    _require(
        set(event) == EVENT_FIELDS,
        f"seq{correction_sequence} correction event fields differ",
    )
    return checkpoint, event


def project_seq82(
    source: Mapping[str, Any], *, managed_paths: list[str],
    path_set_sha256: str, content_set_sha256: str,
    authorization_binding: Mapping[str, Any],
    transition_review_binding: Mapping[str, Mapping[str, Any]],
    event_occurred_at: str | None = None,
    runner_binding: Mapping[str, Any] | None = None,
    failed_gate_binding: Mapping[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    return _project_correction(
        source,
        managed_paths=managed_paths,
        path_set_sha256=path_set_sha256,
        content_set_sha256=content_set_sha256,
        authorization_binding=authorization_binding,
        transition_review_binding=transition_review_binding,
        correction_sequence=SEQ82_CORRECTION_SEQUENCE,
        correction_event_id=SEQ82_CORRECTION_EVENT_ID,
        source_sequence=SEQ82_SOURCE_SEQUENCE,
        source_event_id=SEQ82_SOURCE_EVENT_ID,
        source_event_sha256=SEQ82_SOURCE_EVENT_SHA256,
        source_checkpoint_sha256=SEQ82_SOURCE_CHECKPOINT_FILE_SHA256,
        source_checkpoint_byte_count=SEQ82_SOURCE_CHECKPOINT_BYTE_COUNT,
        ready_current_focus=SEQ82_READY_CURRENT_FOCUS,
        final_scope=SEQ82_FINAL_SCOPE,
        evidence_refs=[
            "FP046-R002_FAILED_INITIAL_START_GATE_001",
            "FP046-R002_FAILED_RECOVERY_START_GATE_002",
            "FP046-R002_FAILED_RECOVERY_START_GATE_003",
            "FP046-R002_SEQ82_83_RECOVERY_AUTHORIZATION",
            "FP046-R002_SEQ82_83_RECOVERY_REVIEW",
            "FP046-R002_SEQ78_79_80_81_CORRECTIONS_PRESERVED",
            "FP046-R002_INITIAL_START_GATE_CONTRACT_R002_RETAINED",
        ],
        event_occurred_at=event_occurred_at,
        runner_binding=runner_binding,
        failed_gate_binding=failed_gate_binding,
    )


def project_seq83(
    source: Mapping[str, Any], *, managed_paths: list[str],
    path_set_sha256: str, content_set_sha256: str,
    authorization_binding: Mapping[str, Any],
    transition_review_binding: Mapping[str, Mapping[str, Any]],
    event_occurred_at: str | None = None,
    runner_binding: Mapping[str, Any] | None = None,
    failed_gate_binding: Mapping[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    return _project_correction(
        source,
        managed_paths=managed_paths,
        path_set_sha256=path_set_sha256,
        content_set_sha256=content_set_sha256,
        authorization_binding=authorization_binding,
        transition_review_binding=transition_review_binding,
        correction_sequence=SEQ83_CORRECTION_SEQUENCE,
        correction_event_id=SEQ83_CORRECTION_EVENT_ID,
        source_sequence=SEQ83_SOURCE_SEQUENCE,
        source_event_id=SEQ83_SOURCE_EVENT_ID,
        source_event_sha256=SEQ83_SOURCE_EVENT_SHA256,
        source_checkpoint_sha256=SEQ83_SOURCE_CHECKPOINT_FILE_SHA256,
        source_checkpoint_byte_count=SEQ83_SOURCE_CHECKPOINT_BYTE_COUNT,
        ready_current_focus=SEQ83_READY_CURRENT_FOCUS,
        final_scope=SEQ83_FINAL_SCOPE,
        evidence_refs=[
            "FP046-R002_FAILED_INITIAL_START_GATE_001",
            "FP046-R002_FAILED_RECOVERY_START_GATE_002",
            "FP046-R002_FAILED_RECOVERY_START_GATE_003",
            "FP046-R002_SEQ83_84_RECOVERY_AUTHORIZATION",
            "FP046-R002_SEQ83_84_RECOVERY_REVIEW",
            "FP046-R002_SEQ78_79_80_81_82_CORRECTIONS_PRESERVED",
            "FP046-R002_ATOMIC_CHECKPOINT_AND_CATALOG_PUBLICATION",
            "FP046-R002_INITIAL_START_GATE_CONTRACT_R002_RETAINED",
        ],
        event_occurred_at=event_occurred_at,
        runner_binding=runner_binding,
        failed_gate_binding=failed_gate_binding,
    )


def project_seq84(
    source: Mapping[str, Any], *, managed_paths: list[str],
    path_set_sha256: str, content_set_sha256: str,
    authorization_binding: Mapping[str, Any],
    transition_review_binding: Mapping[str, Mapping[str, Any]],
    event_occurred_at: str | None = None,
    runner_binding: Mapping[str, Any] | None = None,
    failed_gate_binding: Mapping[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    return _project_correction(
        source,
        managed_paths=managed_paths,
        path_set_sha256=path_set_sha256,
        content_set_sha256=content_set_sha256,
        authorization_binding=authorization_binding,
        transition_review_binding=transition_review_binding,
        correction_sequence=CONTROL_CORRECTION_SEQUENCE,
        correction_event_id=CONTROL_CORRECTION_EVENT_ID,
        source_sequence=SOURCE_SEQUENCE,
        source_event_id=SOURCE_EVENT_ID,
        source_event_sha256=SOURCE_EVENT_SHA256,
        source_checkpoint_sha256=SOURCE_CHECKPOINT_FILE_SHA256,
        source_checkpoint_byte_count=SOURCE_CHECKPOINT_BYTE_COUNT,
        ready_current_focus=READY_CURRENT_FOCUS,
        final_scope=FINAL_SCOPE,
        evidence_refs=[
            "FP046-R002_FAILED_INITIAL_START_GATE_001",
            "FP046-R002_FAILED_RECOVERY_START_GATE_002",
            "FP046-R002_FAILED_RECOVERY_START_GATE_003",
            "FP046-R002_UNCONSUMED_PASS_START_GATE_004",
            "FP046-R002_SEQ84_85_RECOVERY_AUTHORIZATION",
            "FP046-R002_SEQ84_85_RECOVERY_REVIEW",
            "FP046-R002_SEQ78_79_80_81_82_83_CORRECTIONS_PRESERVED",
            "FP046-R002_PROJECTED_START_ACTIVATION_VALIDATOR_CORRECTED",
            "FP046-R002_INITIAL_START_GATE_CONTRACT_R002_RETAINED",
        ],
        event_occurred_at=event_occurred_at,
        runner_binding=runner_binding,
        failed_gate_binding=failed_gate_binding,
        source_gate_binding_key="passed_gate_attempt_004",
        next_gate_event_id="005",
    )


def validate_seq78_history_suffix(root: Path, checkpoint: Mapping[str, Any], *, require_live_snapshot: bool = True) -> dict[str, Any]:
    root = root.resolve(strict=True)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    _require(type(history) is list and len(history) >= SEQ78_CORRECTION_SEQUENCE, "seq78 correction is absent")
    seq77, event = history[76], history[77]
    expected_evidence_refs = [
        "FP046-R002_FAILED_INITIAL_START_GATE_001",
        "FP046-R002_SEQ78_79_RECOVERY_AUTHORIZATION",
        "FP046-R002_SEQ78_79_RECOVERY_REVIEW",
        "FP046-R002_INITIAL_START_GATE_CONTRACT_R002_RETAINED",
    ]
    expected_source_binding = {
        "path": CHECKPOINT_RELATIVE.as_posix(),
        "sha256": SEQ77_SOURCE_CHECKPOINT_FILE_SHA256,
        "byte_length": SEQ77_SOURCE_CHECKPOINT_BYTE_COUNT,
        "sequence": SEQ77_SOURCE_SEQUENCE,
        "tail_event_id": SEQ77_SOURCE_EVENT_ID,
        "tail_event_sha256": SEQ77_SOURCE_EVENT_SHA256,
        "failed_gate_attempt": (
            review.failed_gate_attempt_binding(root)
            if require_live_snapshot and len(history) == SEQ78_CORRECTION_SEQUENCE
            else review.STORED_FAILED_GATE_ATTEMPT
        ),
    }
    try:
        occurred_at = datetime.fromisoformat(str(event.get("occurred_at")))
    except (TypeError, ValueError) as exc:
        raise ControlCorrectionError("seq78 correction timestamp differs") from exc
    _require(
        seq77.get("event_sha256") == SEQ77_SOURCE_EVENT_SHA256
        and type(event) is dict
        and set(event) == EVENT_FIELDS
        and event.get("sequence") == SEQ78_CORRECTION_SEQUENCE
        and event.get("event_id") == SEQ78_CORRECTION_EVENT_ID
        and event.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and event.get("from_status") == event.get("to_status") == "READY"
        and strict_json_equal(event.get("status_changes"), {})
        and event.get("previous_event_sha256") == SEQ77_SOURCE_EVENT_SHA256
        and event.get("previous_focus_goal_id") == TARGET_GOAL_ID
        and event.get("previous_focus_content_sha256") == TARGET_GOAL_SHA256
        and event.get("focus_goal_id") == TARGET_GOAL_ID
        and event.get("focus_goal_content_sha256") == TARGET_GOAL_SHA256
        and event.get("subject_goal_id") == TARGET_GOAL_ID
        and event.get("static_plan_manifest_sha256") == STATIC_PLAN_MANIFEST_SHA256
        and event.get("occurred_on") == occurred_at.date().isoformat()
        and occurred_at.tzinfo is not None
        and occurred_at.utcoffset() is not None
        and occurred_at > datetime.fromisoformat(str(seq77.get("occurred_at")))
        and strict_json_equal(event.get("runtime_after"), seq77.get("runtime_after"))
        and strict_json_equal(event.get("blockers_after"), seq77.get("blockers_after"))
        and strict_json_equal(
            event.get("blocker_resolution_ids_after"),
            seq77.get("blocker_resolution_ids_after"),
        )
        and event.get("source_checkpoint_version") == SOURCE_CHECKPOINT_SCHEMA_VERSION
        and strict_json_equal(event.get("evidence_refs"), expected_evidence_refs)
        and strict_json_equal(event.get("source_checkpoint_binding"), expected_source_binding)
        and strict_json_equal(
            event.get("authorization_binding"), SEQ78_AUTHORIZATION_BINDING
        )
        and strict_json_equal(
            event.get("start_gate_runner_binding"),
            SEQ78_START_GATE_RUNNER_BINDING,
        )
        and strict_json_equal(
            event.get("transition_control_review_binding"),
            SEQ78_TRANSITION_REVIEW_BINDING,
        )
        and strict_json_equal(
            event.get("source_ready_event_binding"),
            seq77.get("source_ready_event_binding"),
        )
        and strict_json_equal(
            event.get("contract_supersession"),
            seq77.get("contract_supersession"),
        )
        and strict_json_equal(event.get("claim_boundary"), seq77.get("claim_boundary"))
        and strict_json_equal(
            event.get("canonical_binding_snapshot_after"),
            seq77.get("canonical_binding_snapshot_after"),
        )
        and event.get("event_sha256") == _event_sha256(event),
        "seq78 correction event differs",
    )
    if len(history) == SEQ78_CORRECTION_SEQUENCE:
        _require(
            state.get("transition_history_anchor_sha256")
            == event.get("event_sha256"),
            "seq78 transition history anchor differs",
        )
    projection = event.get("unchanged_control_projection")
    source_projection = seq77.get("unchanged_control_projection")
    _require(
        type(projection) is dict
        and type(source_projection) is dict
        and set(projection) == set(source_projection)
        and all(
            type(projection[name]) is dict
            and projection[name].get("before_sha256")
            == source_projection[name].get("after_sha256")
            and (
                name == "current_work"
                or projection[name].get("after_sha256")
                == projection[name].get("before_sha256")
            )
            for name in projection
        ),
        "seq78 zero-credit control projection differs",
    )
    return event


def validate_seq79_history_suffix(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
) -> dict[str, Any]:
    root = root.resolve(strict=True)
    validate_seq78_history_suffix(
        root, checkpoint, require_live_snapshot=False
    )
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    _require(
        type(history) is list and len(history) >= SEQ79_CORRECTION_SEQUENCE,
        "seq79 correction is absent",
    )
    source_event = history[SEQ79_SOURCE_SEQUENCE - 1]
    event = history[SEQ79_CORRECTION_SEQUENCE - 1]
    expected_evidence_refs = [
        "FP046-R002_FAILED_INITIAL_START_GATE_001",
        "FP046-R002_SEQ79_80_RECOVERY_AUTHORIZATION",
        "FP046-R002_SEQ79_80_RECOVERY_REVIEW",
        "FP046-R002_SEQ78_CORRECTION_PRESERVED",
        "FP046-R002_INITIAL_START_GATE_CONTRACT_R002_RETAINED",
    ]
    expected_source_binding = {
        "path": CHECKPOINT_RELATIVE.as_posix(),
        "sha256": SEQ79_SOURCE_CHECKPOINT_FILE_SHA256,
        "byte_length": SEQ79_SOURCE_CHECKPOINT_BYTE_COUNT,
        "sequence": SEQ79_SOURCE_SEQUENCE,
        "tail_event_id": SEQ79_SOURCE_EVENT_ID,
        "tail_event_sha256": SEQ79_SOURCE_EVENT_SHA256,
        "failed_gate_attempt": (
            review.failed_gate_attempt_binding(root)
            if require_live_snapshot and len(history) == SEQ79_CORRECTION_SEQUENCE
            else review.STORED_FAILED_GATE_ATTEMPT
        ),
    }
    try:
        occurred_at = datetime.fromisoformat(str(event.get("occurred_at")))
    except (AttributeError, TypeError, ValueError) as exc:
        raise ControlCorrectionError("seq79 correction timestamp differs") from exc
    _require(
        source_event.get("event_sha256") == SEQ79_SOURCE_EVENT_SHA256
        and type(event) is dict
        and set(event) == EVENT_FIELDS
        and event.get("sequence") == SEQ79_CORRECTION_SEQUENCE
        and event.get("event_id") == SEQ79_CORRECTION_EVENT_ID
        and event.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and event.get("from_status") == event.get("to_status") == "READY"
        and strict_json_equal(event.get("status_changes"), {})
        and event.get("previous_event_sha256") == SEQ79_SOURCE_EVENT_SHA256
        and event.get("previous_focus_goal_id") == TARGET_GOAL_ID
        and event.get("previous_focus_content_sha256") == TARGET_GOAL_SHA256
        and event.get("focus_goal_id") == TARGET_GOAL_ID
        and event.get("focus_goal_content_sha256") == TARGET_GOAL_SHA256
        and event.get("subject_goal_id") == TARGET_GOAL_ID
        and event.get("static_plan_manifest_sha256") == STATIC_PLAN_MANIFEST_SHA256
        and event.get("occurred_on") == occurred_at.date().isoformat()
        and occurred_at.tzinfo is not None
        and occurred_at.utcoffset() is not None
        and occurred_at > datetime.fromisoformat(str(source_event.get("occurred_at")))
        and strict_json_equal(event.get("runtime_after"), source_event.get("runtime_after"))
        and strict_json_equal(event.get("blockers_after"), source_event.get("blockers_after"))
        and strict_json_equal(
            event.get("blocker_resolution_ids_after"),
            source_event.get("blocker_resolution_ids_after"),
        )
        and event.get("source_checkpoint_version") == SOURCE_CHECKPOINT_SCHEMA_VERSION
        and strict_json_equal(event.get("evidence_refs"), expected_evidence_refs)
        and strict_json_equal(event.get("source_checkpoint_binding"), expected_source_binding)
        and strict_json_equal(
            event.get("authorization_binding"), SEQ79_AUTHORIZATION_BINDING
        )
        and strict_json_equal(
            event.get("start_gate_runner_binding"),
            SEQ79_START_GATE_RUNNER_BINDING,
        )
        and strict_json_equal(
            event.get("transition_control_review_binding"),
            SEQ79_TRANSITION_REVIEW_BINDING,
        )
        and strict_json_equal(
            event.get("source_ready_event_binding"),
            source_event.get("source_ready_event_binding"),
        )
        and strict_json_equal(
            event.get("contract_supersession"),
            source_event.get("contract_supersession"),
        )
        and strict_json_equal(event.get("claim_boundary"), source_event.get("claim_boundary"))
        and strict_json_equal(
            event.get("canonical_binding_snapshot_after"),
            source_event.get("canonical_binding_snapshot_after"),
        )
        and event.get("event_sha256") == _event_sha256(event),
        "seq79 correction event differs",
    )
    if len(history) == SEQ79_CORRECTION_SEQUENCE:
        _require(
            state.get("transition_history_anchor_sha256")
            == event.get("event_sha256"),
            "seq79 transition history anchor differs",
        )
    projection = event.get("unchanged_control_projection")
    source_projection = source_event.get("unchanged_control_projection")
    _require(
        type(projection) is dict
        and type(source_projection) is dict
        and set(projection) == set(source_projection)
        and all(
            type(projection[name]) is dict
            and projection[name].get("before_sha256")
            == source_projection[name].get("after_sha256")
            and (
                name == "current_work"
                or projection[name].get("after_sha256")
                == projection[name].get("before_sha256")
            )
            for name in projection
        ),
        "seq79 zero-credit control projection differs",
    )
    return event


def validate_seq80_history_suffix(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
) -> dict[str, Any]:
    root = root.resolve(strict=True)
    validate_seq79_history_suffix(
        root, checkpoint, require_live_snapshot=False
    )
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    _require(
        type(history) is list and len(history) >= SEQ80_CORRECTION_SEQUENCE,
        "seq80 correction is absent",
    )
    source_event = history[SEQ80_SOURCE_SEQUENCE - 1]
    event = history[SEQ80_CORRECTION_SEQUENCE - 1]
    expected_evidence_refs = [
        "FP046-R002_FAILED_INITIAL_START_GATE_001",
        "FP046-R002_FAILED_RECOVERY_START_GATE_002",
        "FP046-R002_SEQ80_81_RECOVERY_AUTHORIZATION",
        "FP046-R002_SEQ80_81_RECOVERY_REVIEW",
        "FP046-R002_SEQ78_79_CORRECTIONS_PRESERVED",
        "FP046-R002_INITIAL_START_GATE_CONTRACT_R002_RETAINED",
    ]
    expected_source_binding = {
        "path": CHECKPOINT_RELATIVE.as_posix(),
        "sha256": SEQ80_SOURCE_CHECKPOINT_FILE_SHA256,
        "byte_length": SEQ80_SOURCE_CHECKPOINT_BYTE_COUNT,
        "sequence": SEQ80_SOURCE_SEQUENCE,
        "tail_event_id": SEQ80_SOURCE_EVENT_ID,
        "tail_event_sha256": SEQ80_SOURCE_EVENT_SHA256,
        "failed_gate_attempt": FAILED_GATE_002_STORED_BINDING,
    }
    try:
        occurred_at = datetime.fromisoformat(str(event.get("occurred_at")))
    except (AttributeError, TypeError, ValueError) as exc:
        raise ControlCorrectionError("seq80 correction timestamp differs") from exc
    _require(
        source_event.get("event_sha256") == SEQ80_SOURCE_EVENT_SHA256
        and type(event) is dict
        and set(event) == EVENT_FIELDS
        and event.get("sequence") == SEQ80_CORRECTION_SEQUENCE
        and event.get("event_id") == SEQ80_CORRECTION_EVENT_ID
        and event.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and event.get("from_status") == event.get("to_status") == "READY"
        and strict_json_equal(event.get("status_changes"), {})
        and event.get("previous_event_sha256") == SEQ80_SOURCE_EVENT_SHA256
        and event.get("previous_focus_goal_id") == TARGET_GOAL_ID
        and event.get("previous_focus_content_sha256") == TARGET_GOAL_SHA256
        and event.get("focus_goal_id") == TARGET_GOAL_ID
        and event.get("focus_goal_content_sha256") == TARGET_GOAL_SHA256
        and event.get("subject_goal_id") == TARGET_GOAL_ID
        and event.get("static_plan_manifest_sha256")
        == STATIC_PLAN_MANIFEST_SHA256
        and event.get("occurred_on") == occurred_at.date().isoformat()
        and occurred_at.tzinfo is not None
        and occurred_at.utcoffset() is not None
        and occurred_at
        > datetime.fromisoformat(str(source_event.get("occurred_at")))
        and strict_json_equal(
            event.get("runtime_after"), source_event.get("runtime_after")
        )
        and strict_json_equal(
            event.get("blockers_after"), source_event.get("blockers_after")
        )
        and strict_json_equal(
            event.get("blocker_resolution_ids_after"),
            source_event.get("blocker_resolution_ids_after"),
        )
        and event.get("source_checkpoint_version")
        == SOURCE_CHECKPOINT_SCHEMA_VERSION
        and strict_json_equal(event.get("evidence_refs"), expected_evidence_refs)
        and strict_json_equal(
            event.get("source_checkpoint_binding"), expected_source_binding
        )
        and strict_json_equal(
            event.get("authorization_binding"),
            {
                "path": SEQ80_AUTHORIZATION_RELATIVE.as_posix(),
                "sha256": SEQ80_AUTHORIZATION_BINDING["sha256"],
                "byte_length": SEQ80_AUTHORIZATION_BINDING["byte_length"],
            },
        )
        and strict_json_equal(
            event.get("start_gate_runner_binding"),
            SEQ80_START_GATE_RUNNER_BINDING,
        )
        and strict_json_equal(
            event.get("transition_control_review_binding"),
            SEQ80_TRANSITION_REVIEW_BINDING,
        )
        and strict_json_equal(
            event.get("source_ready_event_binding"),
            source_event.get("source_ready_event_binding"),
        )
        and strict_json_equal(
            event.get("contract_supersession"),
            source_event.get("contract_supersession"),
        )
        and strict_json_equal(
            event.get("claim_boundary"), source_event.get("claim_boundary")
        )
        and strict_json_equal(
            event.get("canonical_binding_snapshot_after"),
            source_event.get("canonical_binding_snapshot_after"),
        )
        and event.get("event_sha256") == _event_sha256(event),
        "seq80 correction event differs",
    )
    projection = event.get("unchanged_control_projection")
    source_projection = source_event.get("unchanged_control_projection")
    _require(
        type(projection) is dict
        and type(source_projection) is dict
        and set(projection) == set(source_projection)
        and all(
            type(projection[name]) is dict
            and projection[name].get("before_sha256")
            == source_projection[name].get("after_sha256")
            and (
                name == "current_work"
                or projection[name].get("after_sha256")
                == projection[name].get("before_sha256")
            )
            for name in projection
        ),
        "seq80 zero-credit control projection differs",
    )
    if len(history) == SEQ80_CORRECTION_SEQUENCE:
        _require(
            state.get("transition_history_anchor_sha256")
            == event.get("event_sha256"),
            "seq80 transition history anchor differs",
        )
        if require_live_snapshot:
            _require(
                strict_json_equal(
                    review.failed_gate_attempt_002_binding(root),
                    FAILED_GATE_002_STORED_BINDING,
                ),
                "failed gate 002 live authority differs",
            )
    return event


def validate_seq81_history_suffix(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
) -> dict[str, Any]:
    root = root.resolve(strict=True)
    validate_seq80_history_suffix(
        root, checkpoint, require_live_snapshot=False
    )
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    _require(
        type(history) is list and len(history) >= SEQ81_CORRECTION_SEQUENCE,
        "seq81 correction is absent",
    )
    source_event = history[SEQ81_SOURCE_SEQUENCE - 1]
    event = history[SEQ81_CORRECTION_SEQUENCE - 1]
    failed_gate_binding = (
        review.failed_gate_attempt_003_binding(root)
        if require_live_snapshot and len(history) == SEQ81_CORRECTION_SEQUENCE
        else review.STORED_FAILED_GATE_ATTEMPT_003
    )
    expected_source_binding = {
        "path": CHECKPOINT_RELATIVE.as_posix(),
        "sha256": SEQ81_SOURCE_CHECKPOINT_FILE_SHA256,
        "byte_length": SEQ81_SOURCE_CHECKPOINT_BYTE_COUNT,
        "sequence": SEQ81_SOURCE_SEQUENCE,
        "tail_event_id": SEQ81_SOURCE_EVENT_ID,
        "tail_event_sha256": SEQ81_SOURCE_EVENT_SHA256,
        "failed_gate_attempt": failed_gate_binding,
    }
    expected_evidence_refs = [
        "FP046-R002_FAILED_INITIAL_START_GATE_001",
        "FP046-R002_FAILED_RECOVERY_START_GATE_002",
        "FP046-R002_FAILED_RECOVERY_START_GATE_003",
        "FP046-R002_SEQ81_82_RECOVERY_AUTHORIZATION",
        "FP046-R002_SEQ81_82_RECOVERY_REVIEW",
        "FP046-R002_SEQ78_79_80_CORRECTIONS_PRESERVED",
        "FP046-R002_INITIAL_START_GATE_CONTRACT_R002_RETAINED",
    ]
    try:
        occurred_at = datetime.fromisoformat(str(event.get("occurred_at")))
    except (AttributeError, TypeError, ValueError) as exc:
        raise ControlCorrectionError("seq81 correction timestamp differs") from exc
    _require(
        source_event.get("event_sha256") == SEQ81_SOURCE_EVENT_SHA256
        and type(event) is dict
        and set(event) == EVENT_FIELDS
        and event.get("sequence") == SEQ81_CORRECTION_SEQUENCE
        and event.get("event_id") == SEQ81_CORRECTION_EVENT_ID
        and event.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and event.get("from_status") == event.get("to_status") == "READY"
        and strict_json_equal(event.get("status_changes"), {})
        and event.get("previous_event_sha256") == SEQ81_SOURCE_EVENT_SHA256
        and event.get("previous_focus_goal_id") == TARGET_GOAL_ID
        and event.get("previous_focus_content_sha256") == TARGET_GOAL_SHA256
        and event.get("focus_goal_id") == TARGET_GOAL_ID
        and event.get("focus_goal_content_sha256") == TARGET_GOAL_SHA256
        and event.get("subject_goal_id") == TARGET_GOAL_ID
        and event.get("static_plan_manifest_sha256")
        == STATIC_PLAN_MANIFEST_SHA256
        and event.get("occurred_on") == occurred_at.date().isoformat()
        and occurred_at.tzinfo is not None
        and occurred_at.utcoffset() is not None
        and occurred_at
        > datetime.fromisoformat(str(source_event.get("occurred_at")))
        and strict_json_equal(
            event.get("runtime_after"), source_event.get("runtime_after")
        )
        and strict_json_equal(
            event.get("blockers_after"), source_event.get("blockers_after")
        )
        and strict_json_equal(
            event.get("blocker_resolution_ids_after"),
            source_event.get("blocker_resolution_ids_after"),
        )
        and event.get("source_checkpoint_version")
        == SOURCE_CHECKPOINT_SCHEMA_VERSION
        and strict_json_equal(event.get("evidence_refs"), expected_evidence_refs)
        and strict_json_equal(
            event.get("source_checkpoint_binding"), expected_source_binding
        )
        and strict_json_equal(
            event.get("authorization_binding"),
            {
                "path": SEQ81_AUTHORIZATION_RELATIVE.as_posix(),
                "sha256": SEQ81_AUTHORIZATION_BINDING["sha256"],
                "byte_length": SEQ81_AUTHORIZATION_BINDING["byte_length"],
            },
        )
        and strict_json_equal(
            event.get("start_gate_runner_binding"),
            SEQ81_START_GATE_RUNNER_BINDING,
        )
        and strict_json_equal(
            event.get("transition_control_review_binding"),
            SEQ81_TRANSITION_REVIEW_BINDING,
        )
        and strict_json_equal(
            event.get("source_ready_event_binding"),
            source_event.get("source_ready_event_binding"),
        )
        and strict_json_equal(
            event.get("contract_supersession"),
            source_event.get("contract_supersession"),
        )
        and strict_json_equal(
            event.get("claim_boundary"), source_event.get("claim_boundary")
        )
        and strict_json_equal(
            event.get("canonical_binding_snapshot_after"),
            source_event.get("canonical_binding_snapshot_after"),
        )
        and event.get("event_sha256") == _event_sha256(event),
        "seq81 correction event differs",
    )
    projection = event.get("unchanged_control_projection")
    source_projection = source_event.get("unchanged_control_projection")
    _require(
        type(projection) is dict
        and type(source_projection) is dict
        and set(projection) == set(source_projection)
        and all(
            type(projection[name]) is dict
            and projection[name].get("before_sha256")
            == source_projection[name].get("after_sha256")
            and (
                name == "current_work"
                or projection[name].get("after_sha256")
                == projection[name].get("before_sha256")
            )
            for name in projection
        ),
        "seq81 zero-credit control projection differs",
    )
    if len(history) == SEQ81_CORRECTION_SEQUENCE:
        _require(
            state.get("transition_history_anchor_sha256")
            == event.get("event_sha256"),
            "seq81 transition history anchor differs",
        )
    return event


def _validate_correction_history_suffix(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    correction_sequence: int,
    correction_event_id: str,
    source_sequence: int,
    source_event_id: str,
    source_event_sha256: str,
    source_checkpoint_sha256: str,
    source_checkpoint_byte_count: int,
    expected_authorization_binding: Mapping[str, Any],
    expected_evidence_refs: list[str],
    require_live_snapshot: bool = True,
    source_gate_binding_key: str = "failed_gate_attempt",
    stored_gate_binding: Mapping[str, Any] | None = None,
    live_gate_binding_loader: Callable[[Path], Mapping[str, Any]] | None = None,
) -> dict[str, Any]:
    root = root.resolve(strict=True)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    _require(
        type(history) is list and len(history) >= correction_sequence,
        f"seq{correction_sequence} correction is absent",
    )
    source_event = history[source_sequence - 1]
    event = history[correction_sequence - 1]
    gate_binding = (
        (live_gate_binding_loader or review.failed_gate_attempt_003_binding)(root)
        if require_live_snapshot and len(history) == correction_sequence
        else dict(stored_gate_binding or review.STORED_FAILED_GATE_ATTEMPT_003)
    )
    expected_source_binding = {
        "path": CHECKPOINT_RELATIVE.as_posix(),
        "sha256": source_checkpoint_sha256,
        "byte_length": source_checkpoint_byte_count,
        "sequence": source_sequence,
        "tail_event_id": source_event_id,
        "tail_event_sha256": source_event_sha256,
        source_gate_binding_key: gate_binding,
    }
    try:
        occurred_at = datetime.fromisoformat(str(event.get("occurred_at")))
    except (AttributeError, TypeError, ValueError) as exc:
        raise ControlCorrectionError(
            f"seq{correction_sequence} correction timestamp differs"
        ) from exc
    _require(
        source_event.get("event_sha256") == source_event_sha256
        and type(event) is dict
        and set(event) == EVENT_FIELDS
        and event.get("sequence") == correction_sequence
        and event.get("event_id") == correction_event_id
        and event.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and event.get("from_status") == event.get("to_status") == "READY"
        and strict_json_equal(event.get("status_changes"), {})
        and event.get("previous_event_sha256") == source_event_sha256
        and event.get("previous_focus_goal_id") == TARGET_GOAL_ID
        and event.get("previous_focus_content_sha256") == TARGET_GOAL_SHA256
        and event.get("focus_goal_id") == TARGET_GOAL_ID
        and event.get("focus_goal_content_sha256") == TARGET_GOAL_SHA256
        and event.get("subject_goal_id") == TARGET_GOAL_ID
        and event.get("static_plan_manifest_sha256")
        == STATIC_PLAN_MANIFEST_SHA256
        and event.get("occurred_on") == occurred_at.date().isoformat()
        and occurred_at.tzinfo is not None
        and occurred_at.utcoffset() is not None
        and occurred_at
        > datetime.fromisoformat(str(source_event.get("occurred_at")))
        and strict_json_equal(
            event.get("runtime_after"), source_event.get("runtime_after")
        )
        and strict_json_equal(
            event.get("blockers_after"), source_event.get("blockers_after")
        )
        and strict_json_equal(
            event.get("blocker_resolution_ids_after"),
            source_event.get("blocker_resolution_ids_after"),
        )
        and event.get("source_checkpoint_version")
        == SOURCE_CHECKPOINT_SCHEMA_VERSION
        and strict_json_equal(event.get("evidence_refs"), expected_evidence_refs)
        and strict_json_equal(
            event.get("source_checkpoint_binding"), expected_source_binding
        )
        and strict_json_equal(
            event.get("authorization_binding"),
            expected_authorization_binding,
        )
        and strict_json_equal(
            event.get("source_ready_event_binding"),
            source_event.get("source_ready_event_binding"),
        )
        and strict_json_equal(
            event.get("contract_supersession"),
            source_event.get("contract_supersession"),
        )
        and strict_json_equal(
            event.get("claim_boundary"), source_event.get("claim_boundary")
        )
        and strict_json_equal(
            event.get("canonical_binding_snapshot_after"),
            source_event.get("canonical_binding_snapshot_after"),
        )
        and event.get("event_sha256") == _event_sha256(event),
        f"seq{correction_sequence} correction event differs",
    )
    projection = event.get("unchanged_control_projection")
    source_projection = source_event.get("unchanged_control_projection")
    _require(
        type(projection) is dict
        and type(source_projection) is dict
        and set(projection) == set(source_projection)
        and all(
            type(projection[name]) is dict
            and projection[name].get("before_sha256")
            == source_projection[name].get("after_sha256")
            and (
                name == "current_work"
                or projection[name].get("after_sha256")
                == projection[name].get("before_sha256")
            )
            for name in projection
        ),
        f"seq{correction_sequence} zero-credit control projection differs",
    )
    if len(history) == correction_sequence:
        _require(
            state.get("transition_history_anchor_sha256")
            == event.get("event_sha256"),
            f"seq{correction_sequence} transition history anchor differs",
        )
    return event


def validate_seq82_history_suffix(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
) -> dict[str, Any]:
    validate_seq81_history_suffix(root, checkpoint, require_live_snapshot=False)
    return _validate_correction_history_suffix(
        root,
        checkpoint,
        correction_sequence=SEQ82_CORRECTION_SEQUENCE,
        correction_event_id=SEQ82_CORRECTION_EVENT_ID,
        source_sequence=SEQ82_SOURCE_SEQUENCE,
        source_event_id=SEQ82_SOURCE_EVENT_ID,
        source_event_sha256=SEQ82_SOURCE_EVENT_SHA256,
        source_checkpoint_sha256=SEQ82_SOURCE_CHECKPOINT_FILE_SHA256,
        source_checkpoint_byte_count=SEQ82_SOURCE_CHECKPOINT_BYTE_COUNT,
        expected_authorization_binding=SEQ82_AUTHORIZATION_BINDING,
        expected_evidence_refs=[
            "FP046-R002_FAILED_INITIAL_START_GATE_001",
            "FP046-R002_FAILED_RECOVERY_START_GATE_002",
            "FP046-R002_FAILED_RECOVERY_START_GATE_003",
            "FP046-R002_SEQ82_83_RECOVERY_AUTHORIZATION",
            "FP046-R002_SEQ82_83_RECOVERY_REVIEW",
            "FP046-R002_SEQ78_79_80_81_CORRECTIONS_PRESERVED",
            "FP046-R002_INITIAL_START_GATE_CONTRACT_R002_RETAINED",
        ],
        require_live_snapshot=require_live_snapshot,
    )


def validate_seq83_history_suffix(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
) -> dict[str, Any]:
    validate_seq82_history_suffix(root, checkpoint, require_live_snapshot=False)
    return _validate_correction_history_suffix(
        root,
        checkpoint,
        correction_sequence=SEQ83_CORRECTION_SEQUENCE,
        correction_event_id=SEQ83_CORRECTION_EVENT_ID,
        source_sequence=SEQ83_SOURCE_SEQUENCE,
        source_event_id=SEQ83_SOURCE_EVENT_ID,
        source_event_sha256=SEQ83_SOURCE_EVENT_SHA256,
        source_checkpoint_sha256=SEQ83_SOURCE_CHECKPOINT_FILE_SHA256,
        source_checkpoint_byte_count=SEQ83_SOURCE_CHECKPOINT_BYTE_COUNT,
        expected_authorization_binding=SEQ83_AUTHORIZATION_BINDING,
        expected_evidence_refs=[
            "FP046-R002_FAILED_INITIAL_START_GATE_001",
            "FP046-R002_FAILED_RECOVERY_START_GATE_002",
            "FP046-R002_FAILED_RECOVERY_START_GATE_003",
            "FP046-R002_SEQ83_84_RECOVERY_AUTHORIZATION",
            "FP046-R002_SEQ83_84_RECOVERY_REVIEW",
            "FP046-R002_SEQ78_79_80_81_82_CORRECTIONS_PRESERVED",
            "FP046-R002_ATOMIC_CHECKPOINT_AND_CATALOG_PUBLICATION",
            "FP046-R002_INITIAL_START_GATE_CONTRACT_R002_RETAINED",
        ],
        require_live_snapshot=require_live_snapshot,
    )


def validate_history_suffix(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
) -> dict[str, Any]:
    validate_seq83_history_suffix(root, checkpoint, require_live_snapshot=False)
    return _validate_correction_history_suffix(
        root,
        checkpoint,
        correction_sequence=CONTROL_CORRECTION_SEQUENCE,
        correction_event_id=CONTROL_CORRECTION_EVENT_ID,
        source_sequence=SOURCE_SEQUENCE,
        source_event_id=SOURCE_EVENT_ID,
        source_event_sha256=SOURCE_EVENT_SHA256,
        source_checkpoint_sha256=SOURCE_CHECKPOINT_FILE_SHA256,
        source_checkpoint_byte_count=SOURCE_CHECKPOINT_BYTE_COUNT,
        expected_authorization_binding={
            "path": AUTHORIZATION_RELATIVE.as_posix(),
            "sha256": AUTHORIZATION_SHA256,
            "byte_length": AUTHORIZATION_BYTE_COUNT,
        },
        expected_evidence_refs=[
            "FP046-R002_FAILED_INITIAL_START_GATE_001",
            "FP046-R002_FAILED_RECOVERY_START_GATE_002",
            "FP046-R002_FAILED_RECOVERY_START_GATE_003",
            "FP046-R002_UNCONSUMED_PASS_START_GATE_004",
            "FP046-R002_SEQ84_85_RECOVERY_AUTHORIZATION",
            "FP046-R002_SEQ84_85_RECOVERY_REVIEW",
            "FP046-R002_SEQ78_79_80_81_82_83_CORRECTIONS_PRESERVED",
            "FP046-R002_PROJECTED_START_ACTIVATION_VALIDATOR_CORRECTED",
            "FP046-R002_INITIAL_START_GATE_CONTRACT_R002_RETAINED",
        ],
        require_live_snapshot=require_live_snapshot,
        source_gate_binding_key="passed_gate_attempt_004",
        stored_gate_binding=review.STORED_PASSED_GATE_ATTEMPT_004,
        live_gate_binding_loader=review.passed_gate_attempt_004_binding,
    )


def require_control_corrected_checkpoint(
    root: Path, checkpoint: Mapping[str, Any], *,
    run_external_validators: bool = False,
    require_live_snapshot: bool = True,
) -> None:
    root = root.resolve(strict=True)
    event = validate_history_suffix(root, checkpoint, require_live_snapshot=require_live_snapshot)
    _require(strict_json_equal(event["authorization_binding"], authorization_binding(root)), "authorization binding differs")
    _require(
        strict_json_equal(
            event["transition_control_review_binding"],
            review.transition_review_binding(
                root, require_live_snapshot=require_live_snapshot
            ),
        ),
        "review binding differs",
    )
    _require(strict_json_equal(event["start_gate_runner_binding"], start_gate_runner_binding(root)), "runner binding differs")
    if require_live_snapshot:
        review.passed_gate_attempt_004_binding(root)
    state = checkpoint["goal_execution"]
    _require(
        len(state["transition_history"]) == CONTROL_CORRECTION_SEQUENCE
        and state.get("transition_history_anchor_sha256") == event["event_sha256"]
        and state.get("validation_cutoff_at") == event["occurred_at"]
        and
        state["status_by_goal"].get(TARGET_GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state["status_by_goal"].values()
        and checkpoint["current_work"].get("current_focus") == READY_CURRENT_FOCUS,
        "zero-credit READY boundary differs",
    )
    reviewed_at = review.validated_reviewed_at(
        root, require_live_snapshot=require_live_snapshot
    )
    _require(
        datetime.fromisoformat(event["occurred_at"]) > reviewed_at,
        "correction does not follow recovery review",
    )
    projection = event["unchanged_control_projection"]
    _require(
        projection["current_work"]["after_sha256"]
        == _canonical_sha256(checkpoint["current_work"]),
        "corrected current-work projection differs",
    )
    if require_live_snapshot and len(state["transition_history"]) == CONTROL_CORRECTION_SEQUENCE:
        snapshot = checkpoint["working_tree_snapshot"]
        path_hash, content_hash = _working_snapshot_hashes(root, snapshot["managed_changed_paths"])
        _require(
            snapshot["path_set_sha256"] == path_hash
            and snapshot["content_set_sha256"] == content_hash
            and event["repository_context_reanchor"]["after"]["path_set_sha256"] == path_hash
            and event["repository_context_reanchor"]["after"]["content_set_sha256"] == content_hash,
            "live correction snapshot differs",
        )
    if run_external_validators:
        from scripts import check_walksafe_goal_graph_v2_4 as graph
        from scripts import check_walksafe_project_continuation_v2_4 as continuation
        errors = continuation.validate(root, CHECKPOINT_RELATIVE, continuation.V23_ARCHIVE_RELATIVE, continuation.V24_MANIFEST_RELATIVE)
        errors.extend(graph.validate(root, CHECKPOINT_RELATIVE, continuation.V23_ARCHIVE_RELATIVE, continuation.V24_MANIFEST_RELATIVE, check_continuation=False))
        _require(not errors, "public validation failed: " + "; ".join(errors))


def _working_snapshot_hashes(
    root: Path,
    paths: list[str],
    *,
    checkpoint_bytes: bytes | None = None,
    content_overrides: Mapping[Path, bytes] | None = None,
) -> tuple[str, str]:
    normalized = sorted(paths)
    _require(paths == normalized and len(paths) == len(set(paths)), "snapshot path order differs")
    path_hash = hashlib.sha256(("\n".join(normalized) + "\n").encode()).hexdigest()
    content = hashlib.sha256()
    for relative in normalized:
        path = Path(relative)
        if content_overrides is not None and path in content_overrides:
            raw = content_overrides[path]
        elif relative == CHECKPOINT_RELATIVE.as_posix() and checkpoint_bytes is not None:
            raw = checkpoint_bytes
        else:
            raw = _safe_file(root, path).read_bytes()
        content.update(relative.encode()); content.update(b"\0")
        content.update(_sha256_bytes(raw).encode()); content.update(b"\n")
    return path_hash, content.hexdigest()


def _build_candidate_catalog_bytes(
    root: Path,
    source_universe: tuple[str, ...],
    checkpoint: Mapping[str, Any],
) -> dict[Path, bytes]:
    built = catalogs.build_catalog_bytes(
        root,
        source_universe,
        checkpoint_override=checkpoint,
    )
    _require(
        tuple(built) == catalogs.OUTPUT_PATHS,
        "candidate catalog inventory or order differs",
    )
    return {Path(path): built[path] for path in catalogs.OUTPUT_PATHS}


def exact_seq78_managed_paths(source: Mapping[str, Any]) -> list[str]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    additions = {path.as_posix() for path in SEQ78_EXACT_ADD_ONLY_PATHS}
    _require(not paths.intersection(additions), "seq78 add-only path already exists in seq77 authority")
    result = sorted(paths | additions)
    _require(len(result) == 988, "seq78 managed path inventory differs")
    return result


def exact_seq79_managed_paths(source: Mapping[str, Any]) -> list[str]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    additions = {path.as_posix() for path in SEQ79_EXACT_ADD_ONLY_PATHS}
    _require(
        not paths.intersection(additions),
        "seq79 add-only path already exists in seq78 authority",
    )
    result = sorted(paths | additions)
    _require(len(result) == 998, "seq79 managed path inventory differs")
    return result


def exact_seq80_managed_paths(source: Mapping[str, Any]) -> list[str]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    additions = {path.as_posix() for path in SEQ80_EXACT_ADD_ONLY_PATHS}
    _require(
        not paths.intersection(additions),
        "seq80 add-only path already exists in seq79 authority",
    )
    result = sorted(paths | additions)
    _require(len(result) == 1002, "seq80 managed path inventory differs")
    return result


def exact_seq81_managed_paths(source: Mapping[str, Any]) -> list[str]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    additions = {path.as_posix() for path in SEQ81_EXACT_ADD_ONLY_PATHS}
    _require(
        not paths.intersection(additions),
        "seq81 add-only path already exists in seq80 authority",
    )
    result = sorted(paths | additions)
    _require(len(result) == 1006, "seq81 managed path inventory differs")
    return result


def exact_seq82_managed_paths(source: Mapping[str, Any]) -> list[str]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    additions = {path.as_posix() for path in SEQ82_EXACT_ADD_ONLY_PATHS}
    _require(
        not paths.intersection(additions),
        "seq82 add-only path already exists in seq81 authority",
    )
    result = sorted(paths | additions)
    _require(len(result) == 1011, "seq82 managed path inventory differs")
    return result


def exact_seq83_managed_paths(source: Mapping[str, Any]) -> list[str]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    additions = {path.as_posix() for path in SEQ83_EXACT_ADD_ONLY_PATHS}
    _require(
        not paths.intersection(additions),
        "seq83 add-only path already exists in seq82 authority",
    )
    result = sorted(paths | additions)
    _require(len(result) == 1015, "seq83 managed path inventory differs")
    return result


def exact_seq84_managed_paths(source: Mapping[str, Any]) -> list[str]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    additions = {path.as_posix() for path in EXACT_ADD_ONLY_PATHS}
    _require(
        not paths.intersection(additions),
        "seq84 add-only path already exists in seq83 authority",
    )
    result = sorted(paths | additions)
    _require(len(result) == 1020, "seq84 managed path inventory differs")
    _require(
        not any(
            path.startswith("docs/control/execution/goal-gates/")
            for path in result
        ),
        "seq84 managed paths include private gate evidence",
    )
    return result


def reconstructed_seq78_checkpoint_bytes(root: Path, correction_event: Mapping[str, Any]) -> bytes:
    root = root.resolve(strict=True)
    live = json.loads(
        _safe_file(
            root,
            CHECKPOINT_RELATIVE,
            modes=frozenset({0o600, 0o644}),
        ).read_bytes()
    )
    history = live.get("goal_execution", {}).get("transition_history", [])
    _require(
        len(history) >= 78
        and strict_json_equal(history[77], correction_event),
        "live history cannot reconstruct seq78",
    )
    from scripts import (
        apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823
        as reanchor
    )
    source = json.loads(reanchor.reconstructed_seq77_checkpoint_bytes(root, history[76]))
    paths = exact_seq78_managed_paths(source)
    repository = correction_event.get("repository_context_reanchor")
    after = repository.get("after") if type(repository) is dict else None
    _require(
        type(after) is dict
        and after.get("managed_changed_path_count") == len(paths)
        and after.get("path_set_sha256")
        == hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest(),
        "seq78 exact managed path authority differs",
    )
    projected, event = project_seq78(
        source,
        managed_paths=paths,
        path_set_sha256=after["path_set_sha256"],
        content_set_sha256=after["content_set_sha256"],
        authorization_binding=correction_event["authorization_binding"],
        transition_review_binding=correction_event[
            "transition_control_review_binding"
        ],
        event_occurred_at=correction_event["occurred_at"],
        runner_binding=correction_event["start_gate_runner_binding"],
        failed_gate_binding=correction_event["source_checkpoint_binding"][
            "failed_gate_attempt"
        ],
    )
    _require(strict_json_equal(event, correction_event), "seq78 reconstructed event differs")
    return _json_bytes(projected)


def reconstructed_seq79_checkpoint_bytes(
    root: Path, correction_event: Mapping[str, Any]
) -> bytes:
    root = root.resolve(strict=True)
    live = json.loads(
        _safe_file(
            root,
            CHECKPOINT_RELATIVE,
            modes=frozenset({0o600, 0o644}),
        ).read_bytes()
    )
    history = live.get("goal_execution", {}).get("transition_history", [])
    _require(
        len(history) >= SEQ79_CORRECTION_SEQUENCE
        and strict_json_equal(
            history[SEQ79_CORRECTION_SEQUENCE - 1], correction_event
        ),
        "live history cannot reconstruct seq79",
    )
    source = json.loads(
        reconstructed_seq78_checkpoint_bytes(
            root, history[SEQ78_CORRECTION_SEQUENCE - 1]
        )
    )
    paths = exact_seq79_managed_paths(source)
    repository = correction_event.get("repository_context_reanchor")
    after = repository.get("after") if type(repository) is dict else None
    _require(
        type(after) is dict
        and after.get("managed_changed_path_count") == len(paths)
        and after.get("path_set_sha256")
        == hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest(),
        "seq79 exact managed path authority differs",
    )
    projected, event = project_seq79(
        source,
        managed_paths=paths,
        path_set_sha256=after["path_set_sha256"],
        content_set_sha256=after["content_set_sha256"],
        authorization_binding=correction_event["authorization_binding"],
        transition_review_binding=correction_event[
            "transition_control_review_binding"
        ],
        event_occurred_at=correction_event["occurred_at"],
        runner_binding=correction_event["start_gate_runner_binding"],
        failed_gate_binding=correction_event["source_checkpoint_binding"][
            "failed_gate_attempt"
        ],
    )
    _require(
        strict_json_equal(event, correction_event),
        "seq79 reconstructed event differs",
    )
    return _json_bytes(projected)


def reconstructed_seq80_checkpoint_bytes(
    root: Path, correction_event: Mapping[str, Any]
) -> bytes:
    root = root.resolve(strict=True)
    live = json.loads(
        _safe_file(
            root,
            CHECKPOINT_RELATIVE,
            modes=frozenset({0o600, 0o644}),
        ).read_bytes()
    )
    history = live.get("goal_execution", {}).get("transition_history", [])
    _require(
        len(history) >= SEQ80_CORRECTION_SEQUENCE
        and strict_json_equal(
            history[SEQ80_CORRECTION_SEQUENCE - 1], correction_event
        ),
        "live history cannot reconstruct seq80",
    )
    source = json.loads(
        reconstructed_seq79_checkpoint_bytes(
            root, history[SEQ79_CORRECTION_SEQUENCE - 1]
        )
    )
    paths = exact_seq80_managed_paths(source)
    repository = correction_event.get("repository_context_reanchor")
    after = repository.get("after") if type(repository) is dict else None
    _require(
        type(after) is dict
        and after.get("managed_changed_path_count") == len(paths)
        and after.get("path_set_sha256")
        == hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest(),
        "seq80 exact managed path authority differs",
    )
    projected, event = project_seq80(
        source,
        managed_paths=paths,
        path_set_sha256=after["path_set_sha256"],
        content_set_sha256=after["content_set_sha256"],
        authorization_binding=correction_event["authorization_binding"],
        transition_review_binding=correction_event[
            "transition_control_review_binding"
        ],
        event_occurred_at=correction_event["occurred_at"],
        runner_binding=correction_event["start_gate_runner_binding"],
        failed_gate_binding=correction_event["source_checkpoint_binding"][
            "failed_gate_attempt"
        ],
    )
    _require(
        strict_json_equal(event, correction_event),
        "seq80 reconstructed event differs",
    )
    return _json_bytes(projected)


def reconstructed_seq81_checkpoint_bytes(
    root: Path, correction_event: Mapping[str, Any]
) -> bytes:
    root = root.resolve(strict=True)
    live = json.loads(
        _safe_file(
            root,
            CHECKPOINT_RELATIVE,
            modes=frozenset({0o600, 0o644}),
        ).read_bytes()
    )
    history = live.get("goal_execution", {}).get("transition_history", [])
    _require(
        len(history) >= SEQ81_CORRECTION_SEQUENCE
        and strict_json_equal(
            history[SEQ81_CORRECTION_SEQUENCE - 1], correction_event
        ),
        "live history cannot reconstruct seq81",
    )
    source = json.loads(
        reconstructed_seq80_checkpoint_bytes(
            root, history[SEQ80_CORRECTION_SEQUENCE - 1]
        )
    )
    paths = exact_seq81_managed_paths(source)
    repository = correction_event.get("repository_context_reanchor")
    after = repository.get("after") if type(repository) is dict else None
    _require(
        type(after) is dict
        and after.get("managed_changed_path_count") == len(paths)
        and after.get("path_set_sha256")
        == hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest(),
        "seq81 exact managed path authority differs",
    )
    projected, event = project_seq81(
        source,
        managed_paths=paths,
        path_set_sha256=after["path_set_sha256"],
        content_set_sha256=after["content_set_sha256"],
        authorization_binding=correction_event["authorization_binding"],
        transition_review_binding=correction_event[
            "transition_control_review_binding"
        ],
        event_occurred_at=correction_event["occurred_at"],
        runner_binding=correction_event["start_gate_runner_binding"],
        failed_gate_binding=correction_event["source_checkpoint_binding"][
            "failed_gate_attempt"
        ],
    )
    _require(
        strict_json_equal(event, correction_event),
        "seq81 reconstructed event differs",
    )
    return _json_bytes(projected)


def reconstructed_seq82_checkpoint_bytes(
    root: Path, correction_event: Mapping[str, Any]
) -> bytes:
    root = root.resolve(strict=True)
    live = json.loads(
        _safe_file(
            root,
            CHECKPOINT_RELATIVE,
            modes=frozenset({0o600, 0o644}),
        ).read_bytes()
    )
    history = live.get("goal_execution", {}).get("transition_history", [])
    _require(
        len(history) >= SEQ82_CORRECTION_SEQUENCE
        and strict_json_equal(
            history[SEQ82_CORRECTION_SEQUENCE - 1], correction_event
        ),
        "live history cannot reconstruct seq82",
    )
    source = json.loads(
        reconstructed_seq81_checkpoint_bytes(
            root, history[SEQ81_CORRECTION_SEQUENCE - 1]
        )
    )
    paths = exact_seq82_managed_paths(source)
    repository = correction_event.get("repository_context_reanchor")
    after = repository.get("after") if type(repository) is dict else None
    _require(
        type(after) is dict
        and after.get("managed_changed_path_count") == len(paths)
        and after.get("path_set_sha256")
        == hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest(),
        "seq82 exact managed path authority differs",
    )
    projected, event = project_seq82(
        source,
        managed_paths=paths,
        path_set_sha256=after["path_set_sha256"],
        content_set_sha256=after["content_set_sha256"],
        authorization_binding=correction_event["authorization_binding"],
        transition_review_binding=correction_event[
            "transition_control_review_binding"
        ],
        event_occurred_at=correction_event["occurred_at"],
        runner_binding=correction_event["start_gate_runner_binding"],
        failed_gate_binding=correction_event["source_checkpoint_binding"][
            "failed_gate_attempt"
        ],
    )
    _require(
        strict_json_equal(event, correction_event),
        "seq82 reconstructed event differs",
    )
    return _json_bytes(projected)


def reconstructed_seq83_checkpoint_bytes(
    root: Path, correction_event: Mapping[str, Any]
) -> bytes:
    root = root.resolve(strict=True)
    live = json.loads(
        _safe_file(
            root,
            CHECKPOINT_RELATIVE,
            modes=frozenset({0o600, 0o644}),
        ).read_bytes()
    )
    history = live.get("goal_execution", {}).get("transition_history", [])
    _require(
        len(history) >= SEQ83_CORRECTION_SEQUENCE
        and strict_json_equal(
            history[SEQ83_CORRECTION_SEQUENCE - 1], correction_event
        ),
        "live history cannot reconstruct seq83",
    )
    source = json.loads(
        reconstructed_seq82_checkpoint_bytes(
            root, history[SEQ82_CORRECTION_SEQUENCE - 1]
        )
    )
    paths = exact_seq83_managed_paths(source)
    repository = correction_event.get("repository_context_reanchor")
    after = repository.get("after") if type(repository) is dict else None
    _require(
        type(after) is dict
        and after.get("managed_changed_path_count") == len(paths)
        and after.get("path_set_sha256")
        == hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest(),
        "seq83 exact managed path authority differs",
    )
    projected, event = project_seq83(
        source,
        managed_paths=paths,
        path_set_sha256=after["path_set_sha256"],
        content_set_sha256=after["content_set_sha256"],
        authorization_binding=correction_event["authorization_binding"],
        transition_review_binding=correction_event[
            "transition_control_review_binding"
        ],
        event_occurred_at=correction_event["occurred_at"],
        runner_binding=correction_event["start_gate_runner_binding"],
        failed_gate_binding=correction_event["source_checkpoint_binding"][
            "failed_gate_attempt"
        ],
    )
    _require(
        strict_json_equal(event, correction_event),
        "seq83 reconstructed event differs",
    )
    return _json_bytes(projected)


def reconstructed_seq84_checkpoint_bytes(
    root: Path, correction_event: Mapping[str, Any]
) -> bytes:
    root = root.resolve(strict=True)
    live = json.loads(
        _safe_file(
            root,
            CHECKPOINT_RELATIVE,
            modes=frozenset({0o600, 0o644}),
        ).read_bytes()
    )
    history = live.get("goal_execution", {}).get("transition_history", [])
    _require(
        len(history) >= CONTROL_CORRECTION_SEQUENCE
        and strict_json_equal(
            history[CONTROL_CORRECTION_SEQUENCE - 1], correction_event
        ),
        "live history cannot reconstruct seq84",
    )
    source = json.loads(
        reconstructed_seq83_checkpoint_bytes(
            root, history[SEQ83_CORRECTION_SEQUENCE - 1]
        )
    )
    paths = exact_seq84_managed_paths(source)
    repository = correction_event.get("repository_context_reanchor")
    after = repository.get("after") if type(repository) is dict else None
    _require(
        type(after) is dict
        and after.get("managed_changed_path_count") == len(paths)
        and after.get("path_set_sha256")
        == hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest(),
        "seq84 exact managed path authority differs",
    )
    projected, event = project_seq84(
        source,
        managed_paths=paths,
        path_set_sha256=after["path_set_sha256"],
        content_set_sha256=after["content_set_sha256"],
        authorization_binding=correction_event["authorization_binding"],
        transition_review_binding=correction_event[
            "transition_control_review_binding"
        ],
        event_occurred_at=correction_event["occurred_at"],
        runner_binding=correction_event["start_gate_runner_binding"],
        failed_gate_binding=correction_event["source_checkpoint_binding"][
            "passed_gate_attempt_004"
        ],
    )
    _require(
        strict_json_equal(event, correction_event),
        "seq84 reconstructed event differs",
    )
    return _json_bytes(projected)


@dataclass
class PreparedProjection:
    root: Path
    checkpoint_path: Path
    source_raw: bytes
    projected_checkpoint: dict[str, Any]
    projected_checkpoint_bytes: bytes
    event: dict[str, Any]
    review_binding: dict[str, Any] | None = None
    authorization: dict[str, Any] | None = None
    runner: dict[str, Any] | None = None
    failed_gate: dict[str, Any] | None = None
    control_cohort: tuple[dict[str, Any], ...] | None = None
    git_paths: tuple[str, ...] | None = None
    managed_paths: tuple[str, ...] | None = None
    path_set_sha256: str | None = None
    content_set_sha256: str | None = None
    source_universe: tuple[str, ...] = ()
    source_catalogs: Mapping[Path, bytes] | None = None
    candidate_catalogs: Mapping[Path, bytes] | None = None
    source_catalog_bindings: tuple[dict[str, Any], ...] = ()
    already_committed: bool = False


def _git_paths(
    root: Path,
    *,
    transport_bytes: tuple[bytes, bytes] | None = None,
) -> list[str]:
    raw = git_utility._run_git_bytes(
        root,
        list(git_utility.GIT_STATUS_PORCELAIN_V2_COMMAND[1:]),
        config_overrides=git_utility.GIT_STATUS_CONFIG_OVERRIDES,
    ).stdout
    records = git_utility.parse_git_status_porcelain_v2(raw)
    observed = {
        entry["path"] for record in records for entry in record["paths"]
    }
    staging_prefix = (
        CHECKPOINT_RELATIVE.parent
        / f".{CHECKPOINT_RELATIVE.name}.fp048-seq43-44."
    ).as_posix()
    staging = sorted(
        path
        for path in observed
        if path.startswith(staging_prefix)
        and path.endswith(".tmp")
        and len(path[len(staging_prefix) : -4]) == 24
        and all(
            character in "0123456789abcdef"
            for character in path[len(staging_prefix) : -4]
        )
    )
    if staging:
        _require(
            transport_bytes is not None and len(staging) == 1,
            "checkpoint transport staging inventory differs",
        )
        stage = _safe_file(root, Path(staging[0]), modes=frozenset({0o600}))
        raw = stage.read_bytes()
        _require(raw in transport_bytes, "checkpoint transport staging bytes differ")
        observed.remove(staging[0])
    excluded = {
        path
        for path in observed
        if path == CHECKPOINT_RELATIVE.as_posix()
        or path.startswith("docs/control/execution/goal-gates/")
    }
    result = observed - excluded
    for path in result:
        _safe_file(root, Path(path))
    return sorted(result)


def _raw_transition_review_binding(root: Path) -> dict[str, Any]:
    raw = tuple(review._read_regular(root, path) for path in review.REVIEW_PATHS)
    return {
        "assignment": review.binding(review.ASSIGNMENT_REL, raw[0]),
        "review_result": review.binding(review.RESULT_REL, raw[1]),
        "independent_review": review.binding(review.INDEPENDENT_REL, raw[2]),
    }


def _catalog_transition_phase(
    root: Path,
    source_bindings: Sequence[Mapping[str, Any]],
    candidates: Mapping[Path, bytes],
) -> tuple[int, dict[Path, bytes]]:
    by_path = {Path(row["path"]): row for row in source_bindings}
    _require(
        tuple(by_path) == CATALOG_PATHS and set(candidates) == set(CATALOG_PATHS),
        "catalog transition inventory or order differs",
    )
    states: list[str] = []
    observed: dict[Path, bytes] = {}
    for path in CATALOG_PATHS:
        raw = _safe_file(root, path).read_bytes()
        observed[path] = raw
        source = by_path[path]
        source_match = (
            len(raw) == source["byte_length"]
            and _sha256_bytes(raw) == source["sha256"]
        )
        candidate_match = raw == candidates[path]
        if candidate_match and source_match:
            continue
        if candidate_match:
            states.append("C")
        elif source_match:
            states.append("S")
        else:
            raise ControlCorrectionError(
                f"catalog is neither reviewed source nor candidate: {path}"
            )
    phase = states.count("C")
    _require(
        states == ["C"] * phase + ["S"] * (len(states) - phase),
        "catalog transition is not an ordered candidate prefix",
    )
    return phase, observed


def _changed_catalog_paths(
    source_bindings: Sequence[Mapping[str, Any]],
    candidates: Mapping[Path, bytes],
) -> tuple[Path, ...]:
    by_path = {Path(row["path"]): row for row in source_bindings}
    return tuple(
        path
        for path in CATALOG_PATHS
        if (
            by_path[path]["byte_length"] != len(candidates[path])
            or by_path[path]["sha256"] != _sha256_bytes(candidates[path])
        )
    )


def prepare_projection(root: Path, *, run_external_validators: bool = True) -> PreparedProjection:
    root = root.resolve(strict=True)
    review_context = review.validate_post_review(root)
    path = _safe_file(root, CHECKPOINT_RELATIVE, modes=frozenset({0o600}))
    live_raw = path.read_bytes()
    live = json.loads(live_raw)
    live_history = live.get("goal_execution", {}).get("transition_history", [])
    already_committed = (
        type(live_history) is list
        and len(live_history) == CONTROL_CORRECTION_SEQUENCE
        and type(live_history[-1]) is dict
        and live_history[-1].get("event_id") == CONTROL_CORRECTION_EVENT_ID
    )
    if already_committed:
        validate_history_suffix(root, live, require_live_snapshot=False)
        raw = reconstructed_seq83_checkpoint_bytes(
            root, live_history[SEQ83_CORRECTION_SEQUENCE - 1]
        )
    else:
        raw = live_raw
    _require(len(raw) == SOURCE_CHECKPOINT_BYTE_COUNT and _sha256_bytes(raw) == SOURCE_CHECKPOINT_FILE_SHA256, "source checkpoint bytes differ")
    source = json.loads(raw)
    _require_exact_correction_source(
        source,
        sequence=SOURCE_SEQUENCE,
        event_id=SOURCE_EVENT_ID,
        event_sha256=SOURCE_EVENT_SHA256,
    )
    paths = exact_seq84_managed_paths(source)
    live_git_paths = tuple(_git_paths(root))
    _require(
        set(live_git_paths).issubset(set(paths)),
        "live path escaped the reviewed seq84 snapshot",
    )
    path_hash = hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest()
    transition_review = review.transition_review_binding(root)
    authorization = authorization_binding(root)
    runner = start_gate_runner_binding(root)
    failed_gate = review.passed_gate_attempt_004_binding(root)
    control_cohort = review.current_control_cohort(root)
    _require(len(control_cohort) == 37, "recovery control cohort is not exactly 37 paths")
    reviewed_at = review.validated_reviewed_at(root)
    now = datetime.now(timezone.utc)
    event_time = (
        str(live_history[-1]["occurred_at"])
        if already_committed
        else max(now, reviewed_at.astimezone(timezone.utc) + timedelta(seconds=1)).replace(microsecond=0).isoformat()
    )
    draft, _ = project_seq84(
        source, managed_paths=paths, path_set_sha256=path_hash,
        content_set_sha256="0" * 64,
        authorization_binding=authorization,
        transition_review_binding=transition_review,
        event_occurred_at=event_time,
        runner_binding=runner,
        failed_gate_binding=failed_gate,
    )
    source_universe = catalogs.discover_source_paths(root)
    candidate_catalogs = _build_candidate_catalog_bytes(
        root, source_universe, draft
    )
    _, content_hash = _working_snapshot_hashes(
        root,
        paths,
        checkpoint_bytes=raw,
        content_overrides=candidate_catalogs,
    )
    projected, event = project_seq84(
        source, managed_paths=paths, path_set_sha256=path_hash,
        content_set_sha256=content_hash,
        authorization_binding=authorization,
        transition_review_binding=transition_review,
        event_occurred_at=event_time,
        runner_binding=runner,
        failed_gate_binding=failed_gate,
    )
    _require(
        _build_candidate_catalog_bytes(root, source_universe, projected)
        == candidate_catalogs,
        "seq84 catalog/checkpoint projection did not reach a fixed point",
    )
    if already_committed:
        _require(
            _json_bytes(projected) == live_raw,
            "committed seq84 checkpoint differs from deterministic projection",
        )
    _, observed_catalogs = _catalog_transition_phase(
        root,
        review_context.source_catalog_bindings,
        candidate_catalogs,
    )
    if run_external_validators:
        from scripts import check_walksafe_goal_graph_v2_4 as graph
        from scripts import check_walksafe_project_continuation_v2_4 as continuation

        history = projected["goal_execution"]["transition_history"]
        errors = continuation._validate_fp046_r002_recovery_seq78_79(
            root, projected, history
        )
        errors.extend(graph.validate_fp046_r002_seq77_79(root, projected))
        _require(
            not errors,
            "candidate recovery validation failed: " + "; ".join(errors),
        )
    _require(
        path.read_bytes() == (live_raw if already_committed else raw),
        "checkpoint changed during preparation",
    )
    return PreparedProjection(
        root=root,
        checkpoint_path=path,
        source_raw=raw,
        projected_checkpoint=projected,
        projected_checkpoint_bytes=_json_bytes(projected),
        event=event,
        review_binding=copy.deepcopy(transition_review),
        authorization=copy.deepcopy(authorization),
        runner=copy.deepcopy(runner),
        failed_gate=copy.deepcopy(failed_gate),
        control_cohort=tuple(copy.deepcopy(control_cohort)),
        git_paths=live_git_paths,
        managed_paths=tuple(paths),
        path_set_sha256=path_hash,
        content_set_sha256=content_hash,
        source_universe=source_universe,
        source_catalogs=observed_catalogs,
        candidate_catalogs=candidate_catalogs,
        source_catalog_bindings=tuple(
            copy.deepcopy(review_context.source_catalog_bindings)
        ),
        already_committed=already_committed,
    )


def _stable_transport_target_state(
    path: Path, *, maximum_bytes: int
) -> tuple[tuple[int, ...], bytes] | None:
    descriptor: int | None = None
    try:
        before = path.lstat()
        identity = (
            before.st_dev,
            before.st_ino,
            before.st_mode,
            before.st_uid,
            before.st_gid,
            before.st_nlink,
            before.st_size,
            before.st_mtime_ns,
            before.st_ctime_ns,
        )
        if (
            not stat.S_ISREG(before.st_mode)
            or stat.S_IMODE(before.st_mode) != 0o600
            or before.st_uid != os.geteuid()
            or before.st_nlink != 1
            or before.st_size > maximum_bytes
        ):
            return None
        descriptor = os.open(
            path,
            os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
        )
        opened = os.fstat(descriptor)
        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = os.read(descriptor, min(1024 * 1024, maximum_bytes + 1 - total))
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
            if total > maximum_bytes:
                return None
        after = os.fstat(descriptor)
        named = path.lstat()
        named_identity = (
            named.st_dev,
            named.st_ino,
            named.st_mode,
            named.st_uid,
            named.st_gid,
            named.st_nlink,
            named.st_size,
            named.st_mtime_ns,
            named.st_ctime_ns,
        )
        opened_identity = (
            opened.st_dev,
            opened.st_ino,
            opened.st_mode,
            opened.st_uid,
            opened.st_gid,
            opened.st_nlink,
            opened.st_size,
            opened.st_mtime_ns,
            opened.st_ctime_ns,
        )
        after_identity = (
            after.st_dev,
            after.st_ino,
            after.st_mode,
            after.st_uid,
            after.st_gid,
            after.st_nlink,
            after.st_size,
            after.st_mtime_ns,
            after.st_ctime_ns,
        )
        if identity != opened_identity or identity != after_identity or identity != named_identity:
            return None
        return identity, b"".join(chunks)
    except BaseException:
        return None
    finally:
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError:
                pass


def _write_checkpoint_transport(
    atomic_writer: Callable[..., None],
    prepared: PreparedProjection,
    commit_guard: Callable[[], None],
) -> None:
    maximum = max(len(prepared.source_raw), len(prepared.projected_checkpoint_bytes))
    source_state = _stable_transport_target_state(
        prepared.checkpoint_path, maximum_bytes=maximum
    )
    try:
        atomic_writer(
            prepared.checkpoint_path,
            prepared.projected_checkpoint_bytes,
            expected_source=prepared.source_raw,
            commit_guard=commit_guard,
        )
    except (transport.CompletionApplyError, transport.CompletionPostCommitError):
        raise
    except BaseException as exc:
        current = _stable_transport_target_state(
            prepared.checkpoint_path, maximum_bytes=maximum
        )
        if source_state is not None and source_state[1] == prepared.source_raw and current == source_state:
            raise transport.CompletionApplyError(
                "seq84 correction transport failed before replacement; retry is safe"
            ) from exc
        raise transport.CompletionPostCommitError(
            "seq84 correction transport ended with uncertain published state"
        ) from exc


def write_projection(
    prepared: PreparedProjection,
    *,
    atomic_writer: Callable[..., None] = transport.atomic_write,
) -> None:
    _require(
        prepared.checkpoint_path.read_bytes()
        == (
            prepared.projected_checkpoint_bytes
            if prepared.already_committed
            else prepared.source_raw
        ),
        "checkpoint changed at CAS boundary",
    )
    _require(
        prepared.review_binding is not None
        and prepared.authorization is not None
        and prepared.runner is not None
        and prepared.failed_gate is not None
        and prepared.control_cohort is not None
        and prepared.git_paths is not None
        and prepared.managed_paths is not None
        and prepared.path_set_sha256 is not None
        and prepared.content_set_sha256 is not None,
        "prepared correction authority baseline is incomplete",
    )
    _require(
        prepared.source_catalogs is not None
        and prepared.candidate_catalogs is not None
        and len(prepared.source_catalog_bindings) == len(CATALOG_PATHS),
        "prepared catalog transaction baseline is incomplete",
    )

    def guard(
        *,
        allowed_catalog_phases: set[int],
        allow_staging: bool = False,
        allow_destination_checkpoint: bool = False,
    ) -> int:
        _require(
            _json_bytes(prepared.projected_checkpoint)
            == prepared.projected_checkpoint_bytes,
            "prepared correction bytes drifted",
        )
        _require(
            strict_json_equal(
                _raw_transition_review_binding(prepared.root),
                prepared.review_binding,
            ),
            "R002 transition review triad changed at commit",
        )
        _require(
            strict_json_equal(authorization_binding(prepared.root), prepared.authorization),
            "authorization changed at commit",
        )
        _require(
            strict_json_equal(start_gate_runner_binding(prepared.root), prepared.runner),
            "start gate runner changed at commit",
        )
        _require(
            strict_json_equal(
                review.passed_gate_attempt_004_binding(prepared.root),
                prepared.failed_gate,
            ),
            "failed gate evidence changed at commit",
        )
        live_cohort = review.current_control_cohort(prepared.root)
        _require(
            len(live_cohort) == 37
            and strict_json_equal(list(live_cohort), list(prepared.control_cohort)),
            "37-path recovery control cohort changed at commit",
        )
        checkpoint_raw = prepared.checkpoint_path.read_bytes()
        _require(
            checkpoint_raw == prepared.source_raw
            or (
                allow_destination_checkpoint
                and checkpoint_raw == prepared.projected_checkpoint_bytes
            ),
            "checkpoint changed at transaction boundary",
        )
        phase, _ = _catalog_transition_phase(
            prepared.root,
            prepared.source_catalog_bindings,
            prepared.candidate_catalogs,
        )
        _require(phase in allowed_catalog_phases, "catalog transition phase differs")
        if not allow_staging:
            _require(
                catalogs.discover_source_paths(prepared.root)
                == prepared.source_universe,
                "catalog source universe changed at commit",
            )
            _require(
                _build_candidate_catalog_bytes(
                    prepared.root,
                    prepared.source_universe,
                    prepared.projected_checkpoint,
                )
                == prepared.candidate_catalogs,
                "candidate catalog bytes changed at commit",
            )
            _require(
                tuple(
                    _git_paths(
                        prepared.root,
                        transport_bytes=(
                            prepared.source_raw,
                            prepared.projected_checkpoint_bytes,
                        ),
                    )
                )
                == prepared.git_paths,
                "Git path snapshot changed at commit",
            )
        path_hash, content_hash = _working_snapshot_hashes(
            prepared.root,
            list(prepared.managed_paths),
            checkpoint_bytes=prepared.source_raw,
            content_overrides=prepared.candidate_catalogs,
        )
        _require(
            path_hash == prepared.path_set_sha256
            and content_hash == prepared.content_set_sha256,
            "Git content snapshot changed at commit",
        )
        return phase

    changed_catalog_paths = _changed_catalog_paths(
        prepared.source_catalog_bindings,
        prepared.candidate_catalogs,
    )
    final_catalog_phase = len(changed_catalog_paths)
    phase = guard(
        allowed_catalog_phases=set(range(final_catalog_phase + 1)),
        allow_destination_checkpoint=prepared.already_committed,
    )
    if prepared.already_committed:
        _require(
            phase == final_catalog_phase,
            "committed checkpoint has incomplete catalog publication",
        )
        return
    if phase:
        descriptor = os.open(
            prepared.root / CATALOG_PATHS[0].parent,
            os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_CLOEXEC", 0),
        )
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    for index in range(phase, final_catalog_phase):
        relative = changed_catalog_paths[index]
        source = prepared.source_catalogs[relative]
        wanted = prepared.candidate_catalogs[relative]
        if source != wanted:
            guard(allowed_catalog_phases={index})
            try:
                atomic_writer(
                    prepared.root / relative,
                    wanted,
                    expected_source=source,
                    commit_guard=lambda index=index: guard(
                        allowed_catalog_phases={index, index + 1},
                        allow_staging=True,
                    ),
                )
            except BaseException as exc:
                try:
                    current_phase, _ = _catalog_transition_phase(
                        prepared.root,
                        prepared.source_catalog_bindings,
                        prepared.candidate_catalogs,
                    )
                except BaseException as classification_error:
                    raise transport.CompletionPostCommitError(
                        "seq84 catalog publication state is uncertain"
                    ) from classification_error
                if current_phase in {index, index + 1}:
                    raise transport.CompletionApplyError(
                        "seq84 catalog publication stopped at an exact resumable prefix"
                    ) from exc
                raise transport.CompletionPostCommitError(
                    "seq84 catalog publication phase is uncertain"
                ) from exc
        phase = guard(allowed_catalog_phases={index + 1})
    _require(phase == final_catalog_phase, "catalog publication did not complete")
    try:
        _write_checkpoint_transport(
            atomic_writer,
            prepared,
            lambda: guard(
                allowed_catalog_phases={final_catalog_phase},
                allow_staging=True,
                allow_destination_checkpoint=True,
            ),
        )
    except BaseException as exc:
        current = _stable_transport_target_state(
            prepared.checkpoint_path,
            maximum_bytes=max(
                len(prepared.source_raw), len(prepared.projected_checkpoint_bytes)
            ),
        )
        phase, _ = _catalog_transition_phase(
            prepared.root,
            prepared.source_catalog_bindings,
            prepared.candidate_catalogs,
        )
        if current is not None and current[1] == prepared.projected_checkpoint_bytes and phase == final_catalog_phase:
            descriptor = os.open(
                prepared.checkpoint_path.parent,
                os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_CLOEXEC", 0),
            )
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        elif current is not None and current[1] == prepared.source_raw and phase == final_catalog_phase:
            raise transport.CompletionApplyError(
                "seq84 catalogs are durable and checkpoint source remains; retry is safe"
            ) from exc
        else:
            raise transport.CompletionPostCommitError(
                "seq84 checkpoint/catalog publication state is uncertain"
            ) from exc
    current = _stable_transport_target_state(
        prepared.checkpoint_path,
        maximum_bytes=max(
            len(prepared.source_raw), len(prepared.projected_checkpoint_bytes)
        ),
    )
    if current is None or current[1] != prepared.projected_checkpoint_bytes:
        if current is not None and current[1] == prepared.source_raw:
            raise transport.CompletionApplyError(
                "seq84 correction transport returned without publication"
            )
        raise transport.CompletionPostCommitError(
            "seq84 correction publication bytes are uncertain"
        )
    guard(
        allowed_catalog_phases={final_catalog_phase},
        allow_destination_checkpoint=True,
    )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true"); mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        prepared = prepare_projection(args.root)
        if args.write: write_projection(prepared)
    except transport.CompletionPostCommitError as exc:
        print(
            f"FP046 R002 start-control correction seq84: POSTCOMMIT-UNCERTAIN: {exc}",
            file=sys.stderr,
        )
        return 2
    except (
        ControlCorrectionError,
        review.ReviewError,
        transport.CompletionApplyError,
        OSError,
        ValueError,
        TypeError,
        KeyError,
    ) as exc:
        print(f"FP046 R002 start-control correction seq84: FAIL: {exc}", file=sys.stderr); return 1
    print(f"FP046 R002 start-control correction seq84: PASS event_sha256={prepared.event['event_sha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
