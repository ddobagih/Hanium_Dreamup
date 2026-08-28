#!/usr/bin/env python3
"""Run the private five-check FP-046 R002 initial-start gate.

The hardened filesystem, process, repository, and add-only receipt runtime is
shared with the sealed FP-008 runner.  This wrapper supplies only the FP-046
R002 identity, its R002 contract, and the exact seq84 corrected READY source.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, replace
import importlib.util
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
from contextvars import ContextVar
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import (
    apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824
    as correction_authority,
)
from scripts import (
    build_walksafe_fp046_r002_seq78_79_recovery_review_20260824
    as review_authority,
)

_IMPLEMENTATION_PATH = (
    ROOT / "scripts/run_walksafe_fp008_goal_start_gate_20260803.py"
)
_SPEC = importlib.util.spec_from_file_location(
    "_walksafe_fp046_r002_private_gate_runtime_20260823",
    _IMPLEMENTATION_PATH,
)
if _SPEC is None or _SPEC.loader is None:
    raise RuntimeError("FP008 hardened gate runtime cannot be loaded")
_impl = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = _impl
_SPEC.loader.exec_module(_impl)
_base_load_checkpoint = _impl._load_checkpoint
_base_load_gate_context = _impl.load_gate_context
_base_checkpoint_bound_gate_event_directories = (
    _impl._checkpoint_bound_gate_event_directories
)


CHECKPOINT_RELATIVE = Path(
    "docs/control/walksafe-project-continuation-checkpoint.json"
)
MANIFEST_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/"
    "static-plan-manifest-v2.4.0.json"
)
GATE_ROOT_RELATIVE = Path("docs/control/execution/goal-gates")
ROOT_CONTROL_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp046_r002_goal_start_gate_20260823.py"
)
RUNNER_RELATIVE = Path(
    "scripts/run_walksafe_fp046_r002_goal_start_gate_20260823.py"
)
CONTROL_REANCHOR_RELATIVE = Path(
    "scripts/"
    "apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823.py"
)
CONTROL_CORRECTION_RELATIVE = Path(
    "scripts/"
    "apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824.py"
)
START_APPLY_RELATIVE = Path(
    "scripts/apply_walksafe_fp046_r002_goal_started_seq79_20260824.py"
)
REVIEW_BUILDER_RELATIVE = Path(
    "scripts/build_walksafe_fp046_r002_seq78_79_recovery_review_20260824.py"
)
FP008_RUNTIME_RELATIVE = Path(
    "scripts/run_walksafe_fp008_goal_start_gate_20260803.py"
)
CONTINUATION_CHECKER_RELATIVE = Path(
    "scripts/check_walksafe_project_continuation_v2_4.py"
)
CONTINUATION_TEST_RELATIVE = Path(
    "tests/test_walksafe_project_continuation_v2_4.py"
)
GOAL_CHECKER_RELATIVE = Path("scripts/check_walksafe_goal_graph_v2_4.py")
GOAL_TEST_RELATIVE = Path("tests/test_walksafe_goal_graph_v2_4.py")
TEST_LAYER_RUNNER_RELATIVE = Path(
    "scripts/run_walksafe_test_layers_current.sh"
)
CATALOG_GENERATOR_RELATIVE = Path("scripts/generate_repository_catalogs.py")
CATALOG_TEST_RELATIVE = Path("tests/test_repository_catalogs.py")
CONTROL_REANCHOR_TEST_RELATIVE = Path(
    "tests/"
    "test_apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823.py"
)
CONTROL_CORRECTION_TEST_RELATIVE = Path(
    "tests/"
    "test_apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824.py"
)
START_APPLY_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp046_r002_goal_started_seq79_20260824.py"
)
REVIEW_BUILDER_TEST_RELATIVE = Path(
    "tests/test_build_walksafe_fp046_r002_seq78_79_recovery_review_20260824.py"
)
AUTHORIZATION_RELATIVE = review_authority.AUTHORIZATION_REL
REVIEW_DIRECTORY_RELATIVE = review_authority.REVIEW_DIR
REVIEW_RECORD_RELATIVES = review_authority.REVIEW_PATHS
PRESERVED_REVIEW_RECORD_RELATIVES = review_authority.PRESERVED_REVIEW_PATHS
SESSION_ARTIFACT_RELATIVES = review_authority.SESSION_ARTIFACT_PATHS
FP022_PRIVATE_LOG_PINS = {
    Path(
        "docs/control/execution/goal-results/WS-GOAL-EPIC-04-FP-022-R001/"
        "logs/android-user-internal.log"
    ): (
        3_701,
        "cdf1a98a74eb416ed30689d71e509f44155596ceb8d05a3df9c211014c904838",
    ),
    Path(
        "docs/control/execution/goal-results/WS-GOAL-EPIC-04-FP-022-R001/"
        "logs/backend-navigation-internal.log"
    ): (
        531,
        "249031160869dc49edbec23a88808d17dadda9e174a1d5e97a0568361123d951",
    ),
    Path(
        "docs/control/execution/goal-results/WS-GOAL-EPIC-04-FP-022-R001/"
        "logs/test-layer-registry-validate.log"
    ): (
        421,
        "e6502409d2de507ce23ef8dff787545a5b835d3862f3f1f362a326df71190e02",
    ),
}
FP022_PRIVATE_LOG_RELATIVES = tuple(FP022_PRIVATE_LOG_PINS)
CONTRACT_RELATIVE = Path(
    "docs/control/execution/goal-contracts/"
    "WS-GOAL-EPIC-03-FP-046-R002/initial-start-gate-contract-r002.json"
)
R001_CONTRACT_RELATIVE = CONTRACT_RELATIVE.with_name(
    "initial-start-gate-contract-r001.json"
)
GOAL_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-03/epic-03-fp046-consent-withdrawal-deletion-r002.md"
)
RUNTIME_BINDING_RELATIVES: tuple[Path, ...] = ()
SOURCE_GUARD_RELATIVES = (
    CHECKPOINT_RELATIVE,
    R001_CONTRACT_RELATIVE,
    CONTRACT_RELATIVE,
    GOAL_RELATIVE,
    MANIFEST_RELATIVE,
    FP008_RUNTIME_RELATIVE,
    CONTINUATION_CHECKER_RELATIVE,
    CONTINUATION_TEST_RELATIVE,
    GOAL_CHECKER_RELATIVE,
    GOAL_TEST_RELATIVE,
    TEST_LAYER_RUNNER_RELATIVE,
    CATALOG_GENERATOR_RELATIVE,
    CATALOG_TEST_RELATIVE,
    ROOT_CONTROL_TEST_RELATIVE,
    RUNNER_RELATIVE,
    CONTROL_REANCHOR_RELATIVE,
    CONTROL_REANCHOR_TEST_RELATIVE,
    CONTROL_CORRECTION_RELATIVE,
    CONTROL_CORRECTION_TEST_RELATIVE,
    START_APPLY_RELATIVE,
    START_APPLY_TEST_RELATIVE,
    REVIEW_BUILDER_RELATIVE,
    REVIEW_BUILDER_TEST_RELATIVE,
    AUTHORIZATION_RELATIVE,
    *PRESERVED_REVIEW_RECORD_RELATIVES,
    *REVIEW_RECORD_RELATIVES,
    *SESSION_ARTIFACT_RELATIVES,
    *FP022_PRIVATE_LOG_RELATIVES,
)

PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4"
TARGET_GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R002"
TARGET_GOAL_SHA256 = (
    "4627c19b421f626323778fcdfd01cc2edbc36644c48c7d65c2b4847e698ac429"
)
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
PREDECESSOR_GOAL_ID = "WS-GOAL-EPIC-04-FP-022-R001"
DEPENDENCY_GOAL_ID = "WS-GOAL-EPIC-03-FP-008-R001"
DEPENDENCY_COMPLETION_EVENT_SHA256 = (
    "caa5d73aa19416d997e7ef416bfa9a9af5383bde95541ad93fd31e44ee6cafd4"
)
WORK_ITEM_ID = TARGET_GOAL_ID
SOURCE_SEQUENCE = 84
SOURCE_READY_SEQUENCE = 76
CONTROL_REANCHOR_SEQUENCE = 77
SEQ78_CORRECTION_SEQUENCE = correction_authority.SEQ78_CORRECTION_SEQUENCE
SEQ79_CORRECTION_SEQUENCE = correction_authority.SEQ79_CORRECTION_SEQUENCE
SEQ80_CORRECTION_SEQUENCE = correction_authority.SEQ80_CORRECTION_SEQUENCE
SEQ81_CORRECTION_SEQUENCE = correction_authority.SEQ81_CORRECTION_SEQUENCE
SEQ82_CORRECTION_SEQUENCE = correction_authority.SEQ82_CORRECTION_SEQUENCE
SEQ83_CORRECTION_SEQUENCE = correction_authority.SEQ83_CORRECTION_SEQUENCE
CONTROL_CORRECTION_SEQUENCE = correction_authority.CONTROL_CORRECTION_SEQUENCE
READY_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP046-R002-20260815-001"
)
SOURCE_READY_EVENT_SHA256 = (
    "f2a529bae5aa808fccb64191a42902d7e717d0340531410213486bdcfa279070"
)
CONTROL_REANCHOR_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP046-R002-20260823-001"
)
SEQ78_CORRECTION_EVENT_ID = correction_authority.SEQ78_CORRECTION_EVENT_ID
SEQ78_CORRECTION_EVENT_SHA256 = (
    "9728db8dcbb747c2596fa48983932e4c7f303b3877407c39782945839ede836c"
)
SEQ79_CORRECTION_EVENT_ID = correction_authority.SEQ79_CORRECTION_EVENT_ID
SEQ79_CORRECTION_EVENT_SHA256 = (
    "e7ca0e7660d1506e3831f6a031ca7c1eb8951e484afe19bca5c3c180b2c543c2"
)
SEQ80_CORRECTION_EVENT_ID = correction_authority.SEQ80_CORRECTION_EVENT_ID
SEQ80_CORRECTION_EVENT_SHA256 = (
    "17ed039241e754554a98b5f1a1951f3a46bc80afcf2401b75623b7603d792866"
)
SEQ81_CORRECTION_EVENT_ID = correction_authority.SEQ81_CORRECTION_EVENT_ID
SEQ81_CORRECTION_EVENT_SHA256 = (
    "e8778adcf23cf8f05b9b2cac6e0de119d3a27cdb4f60390f2a212dd224e895b1"
)
SEQ82_CORRECTION_EVENT_ID = correction_authority.SEQ82_CORRECTION_EVENT_ID
SEQ82_CORRECTION_EVENT_SHA256 = (
    "33204f6ded1b4b2cf05fe039b409d072addc26ed78e4da867deb02632ba6ba53"
)
SEQ83_CORRECTION_EVENT_ID = correction_authority.SEQ83_CORRECTION_EVENT_ID
SEQ83_CORRECTION_EVENT_SHA256 = correction_authority.SOURCE_EVENT_SHA256
CONTROL_CORRECTION_EVENT_ID = correction_authority.CONTROL_CORRECTION_EVENT_ID
STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-001"
)
FAILED_RECOVERY_STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-002"
)
FAILED_SECOND_RECOVERY_STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-003"
)
PASSED_RECOVERY_STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-004"
)
RECOVERY_STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-005"
)
BURNED_EVENT_IDS = frozenset(
    {
        STARTED_EVENT_ID,
        FAILED_RECOVERY_STARTED_EVENT_ID,
        FAILED_SECOND_RECOVERY_STARTED_EVENT_ID,
        PASSED_RECOVERY_STARTED_EVENT_ID,
    }
)
READY_FRONTIER = (
    TARGET_GOAL_ID,
    PARENT_GOAL_ID,
    "WS-GOAL-EPIC-04",
    "WS-GOAL-EPIC-12",
)
READY_CURRENT_FOCUS = (
    "EPIC-03 FP-046/GAP-055 R002 READY; seq84 recovery start gate "
    "AUTHORIZED/NOT_RUN"
)
GATE_PURPOSE = "INITIAL_START"
MANIFEST_SHA256 = (
    "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
)
LOCKED_TEST_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)
CONTRACT_DOCUMENT_ID = (
    "WS-FP046-R002-INITIAL-START-GATE-CONTRACT-20260823-002"
)
CONTRACT_ID = "WS-FP046-R002-INTERNAL-START-GATE-R002"
CONTRACT_VERSION = "2026-08-23.1"
CONTRACT_FILE_SHA256 = (
    "62311945a57cd96bbaaf10e66ce6f4114835324f74f10ca00443b49482d96a6e"
)
CONTRACT_CANONICAL_SHA256 = (
    "aaf46ea8c8ab8e817ecd30ea3402265d0dcda6cbbe66b49e4fff2fffd77e203c"
)
EXPECTED_CHECK_IDS = (
    "CONTINUATION",
    "GOAL_GRAPH",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "ROOT_FP046_R002_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)
FORBIDDEN_COMMAND_FRAGMENTS = (
    "apps/web",
    "connecteddebugandroidtest",
    " adb ",
    "device",
    "external",
    "formal",
    "deploy",
    "release",
)
EVENT_ID_RE = re.compile(
    r"^WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-(\d{8})-(\d{3})$"
)
SUCCESSOR_REASON_CODE = (
    "SEQ76_READY_TRUST_ANCHOR_AND_CURRENT_CONTROL_COHORT_REQUIRED"
)
R001_CONTRACT_BINDING = {
    "document_id": "WS-FP046-R002-INITIAL-START-GATE-CONTRACT-20260815-001",
    "contract_id": "WS-FP046-R002-INTERNAL-START-GATE-R001",
    "contract_version": "2026-08-15.1",
    "path": R001_CONTRACT_RELATIVE.as_posix(),
    "file_sha256": (
        "506d6afb202b63e4e30f3bb5ba1a560b18b8f76679a37a18a144a442a4a0fe5e"
    ),
    "canonical_sha256": (
        "a8295f1a693b41bbc3864f882fdd8ba9f17e0ddba49562517fcfc3bb26679214"
    ),
}
R001_CONTRACT_EVIDENCE = {**R001_CONTRACT_BINDING, "byte_length": 1_011}
_R002_CONTRACT_FIELDS = {
    "schema_version",
    "document_id",
    "contract_id",
    "contract_version",
    "target_goal_id",
    "target_goal_content_sha256",
    "gate_purpose",
    "successor_reason_code",
    "supersedes",
    "ordered_checks",
    "claim_boundary",
}
CONTRACT_CLAIM_BOUNDARY = {
    "seq76_ready_event_modified": False,
    "binding_replacement_effective_without_reanchor_event": False,
    "append_only_reanchor_event_required": True,
    "goal_started": False,
    "start_gate_status": "NOT_RUN",
    "product_implementation_credit_delta": 0,
    "artifact_completion_credit_delta": 0,
    "test_credit_delta": 0,
    "formal_test_credit_delta": 0,
    "approval_credit_delta": 0,
    "actual_event_credit_delta": 0,
    "release_status": "NOT_ELIGIBLE",
    "intended_use": "ADD_ONLY_SEQ77_REANCHOR_THEN_PRIVATE_GATE_AND_SEQ78_START",
}


for _name, _value in {
    "CHECKPOINT_RELATIVE": CHECKPOINT_RELATIVE,
    "MANIFEST_RELATIVE": MANIFEST_RELATIVE,
    "GATE_ROOT_RELATIVE": GATE_ROOT_RELATIVE,
    "ROOT_CONTROL_TEST_RELATIVE": ROOT_CONTROL_TEST_RELATIVE,
    "RUNNER_RELATIVE": RUNNER_RELATIVE,
    "CONTROL_REANCHOR_RELATIVE": CONTROL_REANCHOR_RELATIVE,
    "CONTROL_CORRECTION_RELATIVE": CONTROL_CORRECTION_RELATIVE,
    "START_APPLY_RELATIVE": START_APPLY_RELATIVE,
    "REVIEW_BUILDER_RELATIVE": REVIEW_BUILDER_RELATIVE,
    "CONTRACT_RELATIVE": CONTRACT_RELATIVE,
    "GOAL_RELATIVE": GOAL_RELATIVE,
    "RUNTIME_BINDING_RELATIVES": RUNTIME_BINDING_RELATIVES,
    "SOURCE_GUARD_RELATIVES": SOURCE_GUARD_RELATIVES,
    "PACKAGE_ID": PACKAGE_ID,
    "TARGET_GOAL_ID": TARGET_GOAL_ID,
    "TARGET_GOAL_SHA256": TARGET_GOAL_SHA256,
    "PARENT_GOAL_ID": PARENT_GOAL_ID,
    "PREDECESSOR_GOAL_ID": PREDECESSOR_GOAL_ID,
    "WORK_ITEM_ID": WORK_ITEM_ID,
    "SOURCE_SEQUENCE": SOURCE_SEQUENCE,
    "CONTROL_CORRECTION_SEQUENCE": CONTROL_CORRECTION_SEQUENCE,
    "READY_EVENT_ID": READY_EVENT_ID,
    "SOURCE_READY_EVENT_SHA256": SOURCE_READY_EVENT_SHA256,
    "CONTROL_CORRECTION_EVENT_ID": CONTROL_CORRECTION_EVENT_ID,
    "STARTED_EVENT_ID": RECOVERY_STARTED_EVENT_ID,
    "READY_FRONTIER": READY_FRONTIER,
    "GATE_PURPOSE": GATE_PURPOSE,
    "MANIFEST_SHA256": MANIFEST_SHA256,
    "LOCKED_TEST_PYTHON": LOCKED_TEST_PYTHON,
    "CONTRACT_DOCUMENT_ID": CONTRACT_DOCUMENT_ID,
    "CONTRACT_ID": CONTRACT_ID,
    "CONTRACT_VERSION": CONTRACT_VERSION,
    "CONTRACT_FILE_SHA256": CONTRACT_FILE_SHA256,
    "CONTRACT_CANONICAL_SHA256": CONTRACT_CANONICAL_SHA256,
    "EXPECTED_CHECK_IDS": EXPECTED_CHECK_IDS,
    "FORBIDDEN_COMMAND_FRAGMENTS": FORBIDDEN_COMMAND_FRAGMENTS,
    "EVENT_ID_RE": EVENT_ID_RE,
    "BURNED_EVENT_IDS": BURNED_EVENT_IDS,
}.items():
    setattr(_impl, _name, _value)


GateError = _impl.GateError
GateCheckFailed = _impl.GateCheckFailed
GatePostCommitUncertain = _impl.GatePostCommitUncertain
GateContext = _impl.GateContext
RECEIPT_FIELDS = _impl.RECEIPT_FIELDS
RECEIPT_NAME = _impl.RECEIPT_NAME
canonical_sha256 = _impl.canonical_sha256
event_sha256 = _impl.event_sha256
_private_file_bytes = _impl._private_file_bytes
repository_snapshot_from_payload = _impl.repository_snapshot_from_payload
capture_repository_state = _impl.capture_repository_state
_REQUIRE_LIVE_SNAPSHOT = ContextVar(
    "fp046_r002_gate_require_live_snapshot",
    default=True,
)
_SOURCE_CORRECTION_SHA256: ContextVar[str | None] = ContextVar(
    "fp046_r002_gate_source_correction_sha256",
    default=None,
)
_GATE_RUN_ROOT: ContextVar[Path | None] = ContextVar(
    "fp046_r002_gate_run_root",
    default=None,
)


def expected_contract_binding() -> dict[str, Any]:
    return {
        "schema_version": "1.1",
        "document_id": CONTRACT_DOCUMENT_ID,
        "path": CONTRACT_RELATIVE.as_posix(),
        "file_sha256": CONTRACT_FILE_SHA256,
        "contract_id": CONTRACT_ID,
        "contract_version": CONTRACT_VERSION,
        "canonical_contract_sha256": CONTRACT_CANONICAL_SHA256,
    }


def _load_gate_contract(
    root: Path,
    *,
    retained_content: bytes | None = None,
) -> tuple[tuple[tuple[str, str], ...], dict[str, Any]]:
    if not CONTRACT_FILE_SHA256 or not CONTRACT_CANONICAL_SHA256:
        raise GateError("FP046 R002 initial-start contract authority is not sealed")
    content = (
        retained_content
        if retained_content is not None
        else _impl.repo_file(root, CONTRACT_RELATIVE).read_bytes()
    )
    if _impl.sha256_bytes(content) != CONTRACT_FILE_SHA256:
        raise GateError("FP046 R002 initial-start contract file SHA-256 differs")
    try:
        contract = json.loads(content)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("FP046 R002 initial-start contract is not valid JSON") from exc
    if not isinstance(contract, dict) or set(contract) != _R002_CONTRACT_FIELDS:
        raise GateError("FP046 R002 initial-start contract field set differs")
    if canonical_sha256(contract) != CONTRACT_CANONICAL_SHA256:
        raise GateError("FP046 R002 canonical contract SHA-256 differs")
    if (
        contract.get("schema_version") != "1.1"
        or contract.get("document_id") != CONTRACT_DOCUMENT_ID
        or contract.get("contract_id") != CONTRACT_ID
        or contract.get("contract_version") != CONTRACT_VERSION
        or contract.get("target_goal_id") != TARGET_GOAL_ID
        or contract.get("target_goal_content_sha256") != TARGET_GOAL_SHA256
        or contract.get("gate_purpose") != GATE_PURPOSE
        or contract.get("successor_reason_code") != SUCCESSOR_REASON_CODE
    ):
        raise GateError("FP046 R002 initial-start contract identity differs")
    if contract.get("supersedes") != {
        **R001_CONTRACT_EVIDENCE,
        "source_ready_event_sequence": SOURCE_READY_SEQUENCE,
        "source_ready_event_id": READY_EVENT_ID,
        "source_ready_event_sha256": SOURCE_READY_EVENT_SHA256,
    }:
        raise GateError("FP046 R002 predecessor contract binding differs")
    if contract.get("claim_boundary") != CONTRACT_CLAIM_BOUNDARY:
        raise GateError("FP046 R002 contract claim boundary differs")
    raw_checks = contract.get("ordered_checks")
    if not isinstance(raw_checks, list):
        raise GateError("FP046 R002 ordered checks are missing")
    checks: list[tuple[str, str]] = []
    for item in raw_checks:
        if (
            not isinstance(item, dict)
            or set(item) != {"check_id", "command"}
            or not isinstance(item.get("check_id"), str)
            or not isinstance(item.get("command"), str)
            or not item["command"].strip()
        ):
            raise GateError("FP046 R002 ordered check differs")
        checks.append((item["check_id"], item["command"]))
    frozen = tuple(checks)
    if tuple(check_id for check_id, _ in frozen) != EXPECTED_CHECK_IDS:
        raise GateError("FP046 R002 check order differs")
    if len(set(EXPECTED_CHECK_IDS)) != len(EXPECTED_CHECK_IDS):
        raise GateError("FP046 R002 check IDs are not unique")
    root_regression = dict(frozen)["ROOT_FP046_R002_CONTROL_REGRESSION"]
    if ROOT_CONTROL_TEST_RELATIVE.as_posix() not in root_regression:
        raise GateError("FP046 R002 does not execute its gate regression")
    for check_id, command in frozen:
        lowered = command.lower()
        forbidden = [
            fragment
            for fragment in FORBIDDEN_COMMAND_FRAGMENTS
            if fragment in lowered
        ]
        if forbidden:
            raise GateError(
                f"{check_id} contains forbidden command scope: {forbidden[0]}"
            )
    return frozen, contract


def _document_id(event_id: str) -> str:
    match = EVENT_ID_RE.fullmatch(event_id)
    if match is None:
        raise GateError(
            "event ID must match WS-GOAL-GRAPH-V2-4-GOAL-STARTED-"
            "FP046-R002-YYYYMMDD-NNN"
        )
    if event_id not in BURNED_EVENT_IDS | {RECOVERY_STARTED_EVENT_ID}:
        raise GateError(
            "event ID is neither the immutable failed attempt nor the exact "
            "recovery successor"
        )
    return (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP046-R002-"
        f"{match.group(1)}-{match.group(2)}"
    )


def _recovery_document_id(event_id: str) -> str:
    if event_id in BURNED_EVENT_IDS:
        raise GateError("event ID belongs to an immutable prior gate attempt")
    if event_id != RECOVERY_STARTED_EVENT_ID:
        raise GateError(f"event ID must equal {RECOVERY_STARTED_EVENT_ID}")
    return _document_id(event_id)


def _parse_timestamp(value: Any, *, label: str) -> datetime:
    if not isinstance(value, str):
        raise GateError(f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise GateError(f"{label} is not valid ISO-8601") from exc
    if parsed.utcoffset() is None:
        raise GateError(f"{label} must include a timezone")
    return parsed


def _failed_gate_attempt_binding() -> dict[str, Any]:
    return {
        "event_id": STARTED_EVENT_ID,
        "directory": review_authority.FAILED_GATE_DIR.as_posix(),
        "directory_mode": f"{review_authority.FAILED_GATE_DIRECTORY_MODE:04o}",
        "only_log_binding": {
            "path": review_authority.FAILED_GATE_LOG_REL.as_posix(),
            "sha256": review_authority.FAILED_GATE_LOG_SHA256,
            "byte_length": review_authority.FAILED_GATE_LOG_BYTE_LENGTH,
        },
        "log_mode": f"{review_authority.FAILED_GATE_LOG_MODE:04o}",
        "receipt_present": False,
        "later_log_count": 0,
    }


def _burned_empty_gate_attempt_binding() -> dict[str, Any]:
    directory = GATE_ROOT_RELATIVE / FAILED_RECOVERY_STARTED_EVENT_ID
    return {
        "event_id": FAILED_RECOVERY_STARTED_EVENT_ID,
        "directory": directory.as_posix(),
        "directory_mode": "0700",
        "directory_inventory": [],
        "log_count": 0,
        "receipt_present": False,
    }


def _burned_logged_gate_attempt_binding() -> dict[str, Any]:
    return {
        "event_id": FAILED_SECOND_RECOVERY_STARTED_EVENT_ID,
        "directory": review_authority.FAILED_GATE_003_DIR.as_posix(),
        "directory_mode": "0700",
        "only_log_binding": {
            "path": review_authority.FAILED_GATE_003_LOG_REL.as_posix(),
            "sha256": review_authority.FAILED_GATE_003_LOG_SHA256,
            "byte_length": review_authority.FAILED_GATE_003_LOG_BYTE_LENGTH,
        },
        "log_mode": "0600",
        "receipt_present": False,
        "later_log_count": 0,
    }


def _stable_namespace_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_nlink,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


_STABLE_SNAPSHOT_DIRECTORY_MODES = frozenset({0o700, 0o755, 0o775})


def _require_exact_fp022_log_guard(guard: Any) -> None:
    guard.verify()
    pins = {pin.relative: pin for pin in guard.pins}
    if set(pins) != set(FP022_PRIVATE_LOG_PINS):
        raise GateError("FP022 private snapshot log membership differs")
    for relative, (byte_count, sha256) in FP022_PRIVATE_LOG_PINS.items():
        pin = pins[relative]
        if (
            stat.S_IMODE(pin.metadata[2]) != 0o600
            or pin.metadata[3] != os.geteuid()
            or pin.metadata[4] != os.getegid()
            or pin.metadata[5] != 1
            or pin.metadata[6] != byte_count
            or len(pin.content) != byte_count
            or _impl.sha256_bytes(pin.content) != sha256
        ):
            raise GateError(f"FP022 private snapshot log differs: {relative}")
    guard.verify()


class _Fp046SnapshotEvidenceGuard:
    def __init__(
        self,
        checkpoint_evidence: Any,
        checkpoint_guard: Any,
        private_log_guard: Any,
    ) -> None:
        self.checkpoint_evidence = checkpoint_evidence
        self.checkpoint_guard = checkpoint_guard
        self.private_log_guard = private_log_guard

    def verify(self) -> None:
        self.checkpoint_evidence.require_snapshot_exact(
            self.checkpoint_guard
        )
        _require_exact_fp022_log_guard(self.private_log_guard)

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        for guard in (self.private_log_guard, self.checkpoint_guard):
            try:
                guard.close(primary or first)
            except BaseException as exc:
                if first is None:
                    first = exc
        if primary is None and first is not None:
            raise first


def _close_snapshot_mutation_guards(snapshot: Any) -> None:
    snapshot.verify()
    guards = (snapshot.namespace_guard, snapshot.snapshot_evidence_guard)
    snapshot.namespace_guard = None
    snapshot.snapshot_evidence_guard = None
    first: BaseException | None = None
    for guard in guards:
        if guard is None:
            continue
        try:
            guard.close(first)
        except BaseException as exc:
            if first is None:
                first = exc
    if first is not None:
        raise first
    snapshot.verify()


def _copy_exact_fp022_private_logs(live_root: Path, snapshot_root: Path) -> None:
    log_directory_relative = FP022_PRIVATE_LOG_RELATIVES[0].parent
    live_directory = live_root / log_directory_relative
    live_before = live_directory.lstat()
    if (
        live_directory.is_symlink()
        or not stat.S_ISDIR(live_before.st_mode)
        or stat.S_IMODE(live_before.st_mode) != 0o700
        or live_before.st_uid != os.geteuid()
        or live_before.st_gid != os.getegid()
    ):
        raise GateError("FP022 private live log directory differs")
    snapshot_directory = snapshot_root / log_directory_relative
    snapshot_directory.mkdir(mode=0o700, exist_ok=False)
    snapshot_directory.chmod(0o700)
    for relative, (byte_count, sha256) in FP022_PRIVATE_LOG_PINS.items():
        source = _impl.repo_file(live_root, relative)
        before = source.lstat()
        if (
            not stat.S_ISREG(before.st_mode)
            or stat.S_IMODE(before.st_mode) != 0o600
            or before.st_uid != os.geteuid()
            or before.st_gid != os.getegid()
            or before.st_nlink != 1
            or before.st_size != byte_count
        ):
            raise GateError(f"FP022 private live log differs: {relative}")
        raw = _private_file_bytes(
            source,
            allow_empty=False,
            maximum_bytes=byte_count,
        )
        if len(raw) != byte_count or _impl.sha256_bytes(raw) != sha256:
            raise GateError(f"FP022 private live log differs: {relative}")
        destination = snapshot_root / relative
        _impl._copy_snapshot_regular_file(source, destination)
        after = source.lstat()
        if _stable_namespace_identity(before) != _stable_namespace_identity(
            after
        ):
            raise GateError(f"FP022 private live log changed: {relative}")
    live_after = live_directory.lstat()
    if _stable_namespace_identity(live_before) != _stable_namespace_identity(
        live_after
    ):
        raise GateError("FP022 private live log directory changed")


def _normalize_snapshot_directory_modes(
    live_root: Path,
    snapshot_root: Path,
) -> None:
    directories: list[Path] = []
    for current_text, names, _files in os.walk(
        snapshot_root,
        topdown=True,
        followlinks=False,
    ):
        current = Path(current_text)
        names[:] = sorted(name for name in names if name != ".git")
        if current != snapshot_root:
            directories.append(current)
    for directory in directories:
        relative = directory.relative_to(snapshot_root)
        live_directory = live_root / relative
        live_before = live_directory.lstat()
        snapshot_before = directory.lstat()
        mode = stat.S_IMODE(live_before.st_mode)
        if (
            live_directory.is_symlink()
            or directory.is_symlink()
            or not stat.S_ISDIR(live_before.st_mode)
            or not stat.S_ISDIR(snapshot_before.st_mode)
            or mode not in _STABLE_SNAPSHOT_DIRECTORY_MODES
            or live_before.st_uid != os.geteuid()
            or live_before.st_gid != os.getegid()
            or snapshot_before.st_uid != os.geteuid()
            or snapshot_before.st_gid != os.getegid()
        ):
            raise GateError(
                f"FP046 snapshot directory authority differs: {relative}"
            )
        directory.chmod(mode)
        live_after = live_directory.lstat()
        snapshot_after = directory.lstat()
        if (
            _stable_namespace_identity(live_before)
            != _stable_namespace_identity(live_after)
            or not stat.S_ISDIR(snapshot_after.st_mode)
            or stat.S_IMODE(snapshot_after.st_mode) != mode
        ):
            raise GateError(
                f"FP046 snapshot directory normalization differs: {relative}"
            )


def _capture_fp046_production_snapshot(
    live_root: Path,
    event_id: str,
    authority: Any,
    repository_guard: Any,
    repository_state_guard: Callable[[Path, Path, str], dict[str, Any]],
) -> Any:
    snapshot = _impl._RetainedIsolatedRepositorySnapshot.capture(
        live_root,
        event_id,
        authority,
        repository_guard,
        repository_state_guard,
    )
    try:
        if authority is None or authority.authorized_gate_evidence is None:
            raise GateError("FP046 production snapshot evidence is unavailable")
        _close_snapshot_mutation_guards(snapshot)
        _copy_exact_fp022_private_logs(live_root, snapshot.root)
        _normalize_snapshot_directory_modes(live_root, snapshot.root)
        checkpoint_guard = _impl._RetainedSourceGuard.capture(
            snapshot.root,
            authority.authorized_gate_evidence.paths,
            maximum_bytes=_impl.LOG_MAX_BYTES,
            total_maximum_bytes=_impl.BOUND_GATE_TOTAL_MAX_BYTES,
        )
        private_log_guard = None
        try:
            authority.authorized_gate_evidence.require_snapshot_exact(
                checkpoint_guard
            )
            private_log_guard = _impl._RetainedSourceGuard.capture(
                snapshot.root,
                FP022_PRIVATE_LOG_RELATIVES,
                maximum_bytes=max(
                    byte_count
                    for byte_count, _sha256 in FP022_PRIVATE_LOG_PINS.values()
                ),
                total_maximum_bytes=sum(
                    byte_count
                    for byte_count, _sha256 in FP022_PRIVATE_LOG_PINS.values()
                ),
            )
            _require_exact_fp022_log_guard(private_log_guard)
            snapshot.snapshot_evidence_guard = _Fp046SnapshotEvidenceGuard(
                authority.authorized_gate_evidence,
                checkpoint_guard,
                private_log_guard,
            )
            checkpoint_guard = None
            private_log_guard = None
        finally:
            if private_log_guard is not None:
                private_log_guard.close()
            if checkpoint_guard is not None:
                checkpoint_guard.close()
        snapshot.namespace_guard = (
            _impl.RetainedRepositoryAuthorityGuard.capture(snapshot.root)
        )
        snapshot.verify_repository()
        return snapshot
    except BaseException as exc:
        snapshot.close(exc)
        raise


def _require_burned_empty_gate_namespace(root: Path) -> None:
    relative = GATE_ROOT_RELATIVE / FAILED_RECOVERY_STARTED_EVENT_ID
    directory = root.resolve(strict=True) / relative
    try:
        before = directory.lstat()
        if (
            directory.is_symlink()
            or not stat.S_ISDIR(before.st_mode)
            or stat.S_IMODE(before.st_mode) != 0o700
            or before.st_nlink != 2
            or before.st_uid != os.geteuid()
            or before.st_gid != os.getegid()
        ):
            raise GateError("burned -002 gate namespace authority differs")
        names = tuple(sorted(entry.name for entry in directory.iterdir()))
        after = directory.lstat()
    except OSError as exc:
        raise GateError("burned -002 gate namespace is unavailable") from exc
    if names or _stable_namespace_identity(before) != _stable_namespace_identity(
        after
    ):
        raise GateError("burned -002 gate namespace authority differs")


def _require_burned_logged_gate_namespace(root: Path) -> None:
    directory = root.resolve(strict=True) / review_authority.FAILED_GATE_003_DIR
    log = root.resolve(strict=True) / review_authority.FAILED_GATE_003_LOG_REL
    try:
        directory_before = directory.lstat()
        if (
            directory.is_symlink()
            or not stat.S_ISDIR(directory_before.st_mode)
            or stat.S_IMODE(directory_before.st_mode) != 0o700
            or directory_before.st_nlink != 2
            or directory_before.st_uid != os.geteuid()
            or directory_before.st_gid != os.getegid()
        ):
            raise GateError("burned -003 gate namespace authority differs")
        names = tuple(sorted(entry.name for entry in directory.iterdir()))
        log_before = log.lstat()
        if (
            log.is_symlink()
            or not stat.S_ISREG(log_before.st_mode)
            or stat.S_IMODE(log_before.st_mode) != 0o600
            or log_before.st_nlink != 1
            or log_before.st_uid != os.geteuid()
            or log_before.st_gid != os.getegid()
        ):
            raise GateError("burned -003 gate log authority differs")
        raw = _private_file_bytes(
            log,
            allow_empty=False,
            maximum_bytes=review_authority.FAILED_GATE_003_LOG_BYTE_LENGTH,
        )
        log_after = log.lstat()
        directory_after = directory.lstat()
    except OSError as exc:
        raise GateError("burned -003 gate namespace is unavailable") from exc
    if (
        names != (review_authority.FAILED_GATE_003_LOG_REL.name,)
        or _stable_namespace_identity(directory_before)
        != _stable_namespace_identity(directory_after)
        or _stable_namespace_identity(log_before)
        != _stable_namespace_identity(log_after)
        or len(raw) != review_authority.FAILED_GATE_003_LOG_BYTE_LENGTH
        or _impl.sha256_bytes(raw) != review_authority.FAILED_GATE_003_LOG_SHA256
    ):
        raise GateError("burned -003 gate namespace authority differs")


def _checkpoint_bound_gate_event_directories(
    checkpoint_bytes: bytes,
) -> tuple[tuple[Path, ...], tuple[Path, ...]]:
    """Normalize exact prior gate directories for the generic file scanner."""

    try:
        checkpoint = json.loads(checkpoint_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return _base_checkpoint_bound_gate_event_directories(checkpoint_bytes)
    state = checkpoint.get("goal_execution") if isinstance(checkpoint, dict) else None
    history = state.get("transition_history") if isinstance(state, dict) else None
    expected = _failed_gate_attempt_binding()
    if isinstance(history, list):
        for sequence, event_id in (
            (SEQ78_CORRECTION_SEQUENCE, SEQ78_CORRECTION_EVENT_ID),
            (SEQ79_CORRECTION_SEQUENCE, SEQ79_CORRECTION_EVENT_ID),
        ):
            event = history[sequence - 1] if len(history) >= sequence else None
            source_binding = (
                event.get("source_checkpoint_binding")
                if isinstance(event, dict)
                else None
            )
            failed = (
                source_binding.get("failed_gate_attempt")
                if isinstance(source_binding, dict)
                else None
            )
            if (
                isinstance(event, dict)
                and event.get("sequence") == sequence
                and event.get("event_id") == event_id
                and correction_authority.strict_json_equal(failed, expected)
            ):
                del failed["directory"]
        event = (
            history[SEQ80_CORRECTION_SEQUENCE - 1]
            if len(history) >= SEQ80_CORRECTION_SEQUENCE
            else None
        )
        source_binding = (
            event.get("source_checkpoint_binding")
            if isinstance(event, dict)
            else None
        )
        failed = (
            source_binding.get("failed_gate_attempt")
            if isinstance(source_binding, dict)
            else None
        )
        if (
            isinstance(event, dict)
            and event.get("sequence") == SEQ80_CORRECTION_SEQUENCE
            and event.get("event_id") == SEQ80_CORRECTION_EVENT_ID
            and correction_authority.strict_json_equal(
                failed,
                _burned_empty_gate_attempt_binding(),
            )
        ):
            run_root = _GATE_RUN_ROOT.get()
            if run_root is not None:
                _require_burned_empty_gate_namespace(run_root)
                del failed["directory"]
        for sequence, event_id in (
            (SEQ81_CORRECTION_SEQUENCE, SEQ81_CORRECTION_EVENT_ID),
            (SEQ82_CORRECTION_SEQUENCE, SEQ82_CORRECTION_EVENT_ID),
            (SEQ83_CORRECTION_SEQUENCE, SEQ83_CORRECTION_EVENT_ID),
        ):
            event = history[sequence - 1] if len(history) >= sequence else None
            source_binding = (
                event.get("source_checkpoint_binding")
                if isinstance(event, dict)
                else None
            )
            failed = (
                source_binding.get("failed_gate_attempt")
                if isinstance(source_binding, dict)
                else None
            )
            if (
                isinstance(event, dict)
                and event.get("sequence") == sequence
                and event.get("event_id") == event_id
                and correction_authority.strict_json_equal(
                    failed,
                    _burned_logged_gate_attempt_binding(),
                )
            ):
                run_root = _GATE_RUN_ROOT.get()
                if run_root is not None:
                    _require_burned_logged_gate_namespace(run_root)
                del failed["directory"]
        event = (
            history[CONTROL_CORRECTION_SEQUENCE - 1]
            if len(history) >= CONTROL_CORRECTION_SEQUENCE
            else None
        )
        source_binding = (
            event.get("source_checkpoint_binding")
            if isinstance(event, dict)
            else None
        )
        passed = (
            source_binding.get("passed_gate_attempt_004")
            if isinstance(source_binding, dict)
            else None
        )
        if (
            isinstance(event, dict)
            and event.get("sequence") == CONTROL_CORRECTION_SEQUENCE
            and event.get("event_id") == CONTROL_CORRECTION_EVENT_ID
            and correction_authority.strict_json_equal(
                passed,
                review_authority.STORED_PASSED_GATE_ATTEMPT_004,
            )
        ):
            run_root = _GATE_RUN_ROOT.get()
            if run_root is not None:
                observed = review_authority.passed_gate_attempt_004_binding(
                    run_root
                )
                if not correction_authority.strict_json_equal(passed, observed):
                    raise GateError("passed -004 gate evidence authority differs")
            del passed["directory"]
    normalized = json.dumps(
        checkpoint,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode()
    return _base_checkpoint_bound_gate_event_directories(normalized)


def _validate_ready_source(
    checkpoint: dict[str, Any],
    *,
    contract_binding: dict[str, Any],
) -> tuple[str, datetime]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    if not isinstance(state, dict) or not isinstance(history, list):
        raise GateError("checkpoint goal execution is missing")
    if len(history) != SOURCE_SEQUENCE:
        raise GateError("FP046 R002 gate requires the exact seq84 correction")
    ready = history[SOURCE_READY_SEQUENCE - 1]
    reanchor = history[CONTROL_REANCHOR_SEQUENCE - 1]
    seq78_correction = history[SEQ78_CORRECTION_SEQUENCE - 1]
    seq79_correction = history[SEQ79_CORRECTION_SEQUENCE - 1]
    seq80_correction = history[SEQ80_CORRECTION_SEQUENCE - 1]
    seq81_correction = history[SEQ81_CORRECTION_SEQUENCE - 1]
    seq82_correction = history[SEQ82_CORRECTION_SEQUENCE - 1]
    seq83_correction = history[SEQ83_CORRECTION_SEQUENCE - 1]
    correction = history[CONTROL_CORRECTION_SEQUENCE - 1]
    if not all(
        isinstance(event, dict)
        for event in (
            ready,
            reanchor,
            seq78_correction,
            seq79_correction,
            seq80_correction,
            seq81_correction,
            seq82_correction,
            seq83_correction,
            correction,
        )
    ):
        raise GateError(
            "FP046 R002 seq76/77/78/79/80/81/82/83/84 source events are missing"
        )
    if (
        ready.get("sequence") != SOURCE_READY_SEQUENCE
        or ready.get("event_id") != READY_EVENT_ID
        or ready.get("event_type") != "GOAL_READY"
        or ready.get("subject_goal_id") != TARGET_GOAL_ID
        or ready.get("focus_goal_id") != TARGET_GOAL_ID
        or ready.get("focus_goal_content_sha256") != TARGET_GOAL_SHA256
        or ready.get("from_status") != "PLANNED"
        or ready.get("to_status") != "READY"
        or ready.get("event_sha256") != SOURCE_READY_EVENT_SHA256
        or ready.get("event_sha256") != event_sha256(ready)
    ):
        raise GateError("FP046 R002 seq76 READY event differs")

    from scripts import (
        apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823
        as reanchor_authority,
    )

    expected_source_ready_binding = reanchor_authority.source_ready_event_binding()
    if (
        set(reanchor) != reanchor_authority.EVENT_FIELDS
        or reanchor.get("sequence") != CONTROL_REANCHOR_SEQUENCE
        or reanchor.get("event_id") != CONTROL_REANCHOR_EVENT_ID
        or reanchor.get("event_type") != "GOAL_START_CONTROL_REANCHORED"
        or reanchor.get("subject_goal_id") != TARGET_GOAL_ID
        or reanchor.get("focus_goal_id") != TARGET_GOAL_ID
        or reanchor.get("focus_goal_content_sha256") != TARGET_GOAL_SHA256
        or reanchor.get("from_status") != "READY"
        or reanchor.get("to_status") != "READY"
        or reanchor.get("status_changes") != {}
        or reanchor.get("previous_event_sha256") != SOURCE_READY_EVENT_SHA256
        or reanchor.get("source_ready_event_binding")
        != expected_source_ready_binding
        or reanchor.get("contract_supersession")
        != {
            "previous_contract_binding": R001_CONTRACT_BINDING,
            "replacement_contract_binding": contract_binding,
            "reason_code": SUCCESSOR_REASON_CODE,
        }
        or reanchor.get("event_sha256") != event_sha256(reanchor)
    ):
        raise GateError("FP046 R002 seq77 start-control reanchor differs")
    expected_supersession = {
        "previous_contract_binding": R001_CONTRACT_BINDING,
        "replacement_contract_binding": contract_binding,
        "reason_code": SUCCESSOR_REASON_CODE,
    }
    if (
        set(seq78_correction) != correction_authority.EVENT_FIELDS
        or seq78_correction.get("sequence") != SEQ78_CORRECTION_SEQUENCE
        or seq78_correction.get("event_id") != SEQ78_CORRECTION_EVENT_ID
        or seq78_correction.get("event_type")
        != "GOAL_START_CONTROL_REANCHORED"
        or seq78_correction.get("subject_goal_id") != TARGET_GOAL_ID
        or seq78_correction.get("focus_goal_id") != TARGET_GOAL_ID
        or seq78_correction.get("focus_goal_content_sha256")
        != TARGET_GOAL_SHA256
        or seq78_correction.get("from_status") != "READY"
        or seq78_correction.get("to_status") != "READY"
        or not correction_authority.strict_json_equal(
            seq78_correction.get("status_changes"),
            {},
        )
        or seq78_correction.get("previous_event_sha256")
        != reanchor.get("event_sha256")
        or seq78_correction.get("event_sha256")
        != SEQ78_CORRECTION_EVENT_SHA256
        or not correction_authority.strict_json_equal(
            seq78_correction.get("source_ready_event_binding"),
            expected_source_ready_binding,
        )
        or not correction_authority.strict_json_equal(
            seq78_correction.get("contract_supersession"),
            expected_supersession,
        )
        or seq78_correction.get("event_sha256")
        != event_sha256(seq78_correction)
    ):
        raise GateError("FP046 R002 seq78 start-control correction differs")
    seq79_source_binding = seq79_correction.get("source_checkpoint_binding")
    if (
        set(seq79_correction) != correction_authority.EVENT_FIELDS
        or seq79_correction.get("sequence") != SEQ79_CORRECTION_SEQUENCE
        or seq79_correction.get("event_id") != SEQ79_CORRECTION_EVENT_ID
        or seq79_correction.get("event_type")
        != "GOAL_START_CONTROL_REANCHORED"
        or seq79_correction.get("subject_goal_id") != TARGET_GOAL_ID
        or seq79_correction.get("focus_goal_id") != TARGET_GOAL_ID
        or seq79_correction.get("focus_goal_content_sha256")
        != TARGET_GOAL_SHA256
        or seq79_correction.get("from_status") != "READY"
        or seq79_correction.get("to_status") != "READY"
        or not correction_authority.strict_json_equal(
            seq79_correction.get("status_changes"),
            {},
        )
        or seq79_correction.get("previous_event_sha256")
        != seq78_correction.get("event_sha256")
        or not isinstance(seq79_source_binding, dict)
        or seq79_source_binding.get("sequence") != SEQ78_CORRECTION_SEQUENCE
        or seq79_source_binding.get("tail_event_id")
        != SEQ78_CORRECTION_EVENT_ID
        or seq79_source_binding.get("tail_event_sha256")
        != SEQ78_CORRECTION_EVENT_SHA256
        or not correction_authority.strict_json_equal(
            seq79_correction.get("source_ready_event_binding"),
            expected_source_ready_binding,
        )
        or not correction_authority.strict_json_equal(
            seq79_correction.get("contract_supersession"),
            expected_supersession,
        )
        or seq79_correction.get("event_sha256")
        != SEQ79_CORRECTION_EVENT_SHA256
        or seq79_correction.get("event_sha256")
        != event_sha256(seq79_correction)
    ):
        raise GateError("FP046 R002 seq79 start-control correction differs")
    seq80_source_binding = seq80_correction.get("source_checkpoint_binding")
    if (
        set(seq80_correction) != correction_authority.EVENT_FIELDS
        or seq80_correction.get("sequence") != SEQ80_CORRECTION_SEQUENCE
        or seq80_correction.get("event_id") != SEQ80_CORRECTION_EVENT_ID
        or seq80_correction.get("event_type")
        != "GOAL_START_CONTROL_REANCHORED"
        or seq80_correction.get("subject_goal_id") != TARGET_GOAL_ID
        or seq80_correction.get("focus_goal_id") != TARGET_GOAL_ID
        or seq80_correction.get("focus_goal_content_sha256")
        != TARGET_GOAL_SHA256
        or seq80_correction.get("from_status") != "READY"
        or seq80_correction.get("to_status") != "READY"
        or not correction_authority.strict_json_equal(
            seq80_correction.get("status_changes"),
            {},
        )
        or seq80_correction.get("previous_event_sha256")
        != seq79_correction.get("event_sha256")
        or not isinstance(seq80_source_binding, dict)
        or seq80_source_binding.get("sequence") != SEQ79_CORRECTION_SEQUENCE
        or seq80_source_binding.get("tail_event_id")
        != SEQ79_CORRECTION_EVENT_ID
        or seq80_source_binding.get("tail_event_sha256")
        != SEQ79_CORRECTION_EVENT_SHA256
        or not correction_authority.strict_json_equal(
            seq80_source_binding.get("failed_gate_attempt"),
            _burned_empty_gate_attempt_binding(),
        )
        or not correction_authority.strict_json_equal(
            seq80_correction.get("source_ready_event_binding"),
            expected_source_ready_binding,
        )
        or not correction_authority.strict_json_equal(
            seq80_correction.get("contract_supersession"),
            expected_supersession,
        )
        or seq80_correction.get("event_sha256")
        != SEQ80_CORRECTION_EVENT_SHA256
        or seq80_correction.get("event_sha256")
        != event_sha256(seq80_correction)
    ):
        raise GateError("FP046 R002 seq80 start-control correction differs")

    seq81_source_binding = seq81_correction.get("source_checkpoint_binding")
    if (
        set(seq81_correction) != correction_authority.EVENT_FIELDS
        or seq81_correction.get("sequence") != SEQ81_CORRECTION_SEQUENCE
        or seq81_correction.get("event_id") != SEQ81_CORRECTION_EVENT_ID
        or seq81_correction.get("event_type")
        != "GOAL_START_CONTROL_REANCHORED"
        or seq81_correction.get("subject_goal_id") != TARGET_GOAL_ID
        or seq81_correction.get("focus_goal_id") != TARGET_GOAL_ID
        or seq81_correction.get("focus_goal_content_sha256")
        != TARGET_GOAL_SHA256
        or seq81_correction.get("from_status") != "READY"
        or seq81_correction.get("to_status") != "READY"
        or not correction_authority.strict_json_equal(
            seq81_correction.get("status_changes"),
            {},
        )
        or seq81_correction.get("previous_event_sha256")
        != seq80_correction.get("event_sha256")
        or not isinstance(seq81_source_binding, dict)
        or seq81_source_binding.get("sequence") != SEQ80_CORRECTION_SEQUENCE
        or seq81_source_binding.get("tail_event_id")
        != SEQ80_CORRECTION_EVENT_ID
        or seq81_source_binding.get("tail_event_sha256")
        != SEQ80_CORRECTION_EVENT_SHA256
        or not correction_authority.strict_json_equal(
            seq81_source_binding.get("failed_gate_attempt"),
            _burned_logged_gate_attempt_binding(),
        )
        or not correction_authority.strict_json_equal(
            seq81_correction.get("source_ready_event_binding"),
            expected_source_ready_binding,
        )
        or not correction_authority.strict_json_equal(
            seq81_correction.get("contract_supersession"),
            expected_supersession,
        )
        or seq81_correction.get("event_sha256")
        != SEQ81_CORRECTION_EVENT_SHA256
        or seq81_correction.get("event_sha256")
        != event_sha256(seq81_correction)
    ):
        raise GateError("FP046 R002 seq81 start-control correction differs")

    seq82_source_binding = seq82_correction.get("source_checkpoint_binding")
    if (
        set(seq82_correction) != correction_authority.EVENT_FIELDS
        or seq82_correction.get("sequence") != SEQ82_CORRECTION_SEQUENCE
        or seq82_correction.get("event_id") != SEQ82_CORRECTION_EVENT_ID
        or seq82_correction.get("event_type")
        != "GOAL_START_CONTROL_REANCHORED"
        or seq82_correction.get("subject_goal_id") != TARGET_GOAL_ID
        or seq82_correction.get("focus_goal_id") != TARGET_GOAL_ID
        or seq82_correction.get("focus_goal_content_sha256")
        != TARGET_GOAL_SHA256
        or seq82_correction.get("from_status") != "READY"
        or seq82_correction.get("to_status") != "READY"
        or not correction_authority.strict_json_equal(
            seq82_correction.get("status_changes"),
            {},
        )
        or seq82_correction.get("previous_event_sha256")
        != seq81_correction.get("event_sha256")
        or not isinstance(seq82_source_binding, dict)
        or seq82_source_binding.get("sequence") != SEQ81_CORRECTION_SEQUENCE
        or seq82_source_binding.get("tail_event_id")
        != SEQ81_CORRECTION_EVENT_ID
        or seq82_source_binding.get("tail_event_sha256")
        != SEQ81_CORRECTION_EVENT_SHA256
        or not correction_authority.strict_json_equal(
            seq82_source_binding.get("failed_gate_attempt"),
            _burned_logged_gate_attempt_binding(),
        )
        or not correction_authority.strict_json_equal(
            seq82_correction.get("source_ready_event_binding"),
            expected_source_ready_binding,
        )
        or not correction_authority.strict_json_equal(
            seq82_correction.get("contract_supersession"),
            expected_supersession,
        )
        or seq82_correction.get("event_sha256")
        != SEQ82_CORRECTION_EVENT_SHA256
        or seq82_correction.get("event_sha256")
        != event_sha256(seq82_correction)
    ):
        raise GateError("FP046 R002 seq82 start-control correction differs")

    seq83_source_binding = seq83_correction.get("source_checkpoint_binding")
    if (
        set(seq83_correction) != correction_authority.EVENT_FIELDS
        or seq83_correction.get("sequence") != SEQ83_CORRECTION_SEQUENCE
        or seq83_correction.get("event_id") != SEQ83_CORRECTION_EVENT_ID
        or seq83_correction.get("event_type")
        != "GOAL_START_CONTROL_REANCHORED"
        or seq83_correction.get("subject_goal_id") != TARGET_GOAL_ID
        or seq83_correction.get("focus_goal_id") != TARGET_GOAL_ID
        or seq83_correction.get("focus_goal_content_sha256")
        != TARGET_GOAL_SHA256
        or seq83_correction.get("from_status") != "READY"
        or seq83_correction.get("to_status") != "READY"
        or not correction_authority.strict_json_equal(
            seq83_correction.get("status_changes"),
            {},
        )
        or seq83_correction.get("previous_event_sha256")
        != seq82_correction.get("event_sha256")
        or not isinstance(seq83_source_binding, dict)
        or seq83_source_binding.get("sequence") != SEQ82_CORRECTION_SEQUENCE
        or seq83_source_binding.get("tail_event_id")
        != SEQ82_CORRECTION_EVENT_ID
        or seq83_source_binding.get("tail_event_sha256")
        != SEQ82_CORRECTION_EVENT_SHA256
        or not correction_authority.strict_json_equal(
            seq83_source_binding.get("failed_gate_attempt"),
            _burned_logged_gate_attempt_binding(),
        )
        or not correction_authority.strict_json_equal(
            seq83_correction.get("source_ready_event_binding"),
            expected_source_ready_binding,
        )
        or not correction_authority.strict_json_equal(
            seq83_correction.get("contract_supersession"),
            expected_supersession,
        )
        or seq83_correction.get("event_sha256")
        != SEQ83_CORRECTION_EVENT_SHA256
        or seq83_correction.get("event_sha256")
        != event_sha256(seq83_correction)
    ):
        raise GateError("FP046 R002 seq83 start-control correction differs")

    source_binding = correction.get("source_checkpoint_binding")
    if (
        set(correction) != correction_authority.EVENT_FIELDS
        or correction.get("sequence") != CONTROL_CORRECTION_SEQUENCE
        or correction.get("event_id") != CONTROL_CORRECTION_EVENT_ID
        or correction.get("event_type") != "GOAL_START_CONTROL_REANCHORED"
        or correction.get("subject_goal_id") != TARGET_GOAL_ID
        or correction.get("focus_goal_id") != TARGET_GOAL_ID
        or correction.get("focus_goal_content_sha256") != TARGET_GOAL_SHA256
        or correction.get("from_status") != "READY"
        or correction.get("to_status") != "READY"
        or not correction_authority.strict_json_equal(
            correction.get("status_changes"),
            {},
        )
        or correction.get("previous_event_sha256")
        != seq83_correction.get("event_sha256")
        or not isinstance(source_binding, dict)
        or source_binding.get("sequence") != SEQ83_CORRECTION_SEQUENCE
        or source_binding.get("tail_event_id") != SEQ83_CORRECTION_EVENT_ID
        or source_binding.get("tail_event_sha256")
        != SEQ83_CORRECTION_EVENT_SHA256
        or not correction_authority.strict_json_equal(
            source_binding.get("passed_gate_attempt_004"),
            review_authority.STORED_PASSED_GATE_ATTEMPT_004,
        )
        or not correction_authority.strict_json_equal(
            correction.get("source_ready_event_binding"),
            expected_source_ready_binding,
        )
        or not correction_authority.strict_json_equal(
            correction.get("contract_supersession"),
            expected_supersession,
        )
        or correction.get("event_sha256") != event_sha256(correction)
    ):
        raise GateError("FP046 R002 seq84 start-control correction differs")
    if state.get("transition_history_anchor_sha256") != correction.get(
        "event_sha256"
    ):
        raise GateError("FP046 R002 seq84 history anchor differs")
    if ready.get("readiness_basis") != {
        "dependency_completion_events": [
            {
                "goal_id": DEPENDENCY_GOAL_ID,
                "event_sha256": DEPENDENCY_COMPLETION_EVENT_SHA256,
            }
        ]
    }:
        raise GateError("FP046 R002 seq76 readiness basis differs")
    if (
        state.get("package_id") != PACKAGE_ID
        or state.get("package_status") != "ACTIVE"
        or state.get("activation_status") != "ACTIVE"
        or state.get("focus_goal_id") != TARGET_GOAL_ID
        or state.get("focus_goal_path") != GOAL_RELATIVE.as_posix()
        or state.get("focus_work_item_id") != WORK_ITEM_ID
        or state.get("focus_source") != "IMPLEMENTATION_GAP"
        or state.get("ready_frontier_goal_ids") != list(READY_FRONTIER)
    ):
        raise GateError("FP046 R002 seq84 active focus differs")
    statuses = state.get("status_by_goal")
    if (
        not isinstance(statuses, dict)
        or statuses.get(TARGET_GOAL_ID) != "READY"
        or statuses.get(PREDECESSOR_GOAL_ID) != "COMPLETE_AT_TARGET"
        or statuses.get(DEPENDENCY_GOAL_ID) != "COMPLETE_AT_TARGET"
        or "IN_PROGRESS" in statuses.values()
    ):
        raise GateError("FP046 R002 seq84 Goal statuses differ")
    blockers = state.get("blockers_by_goal")
    if (
        state.get("blocked_goal_ids") != []
        or state.get("pending_questions") != []
        or state.get("open_question_count") != 0
        or (isinstance(blockers, dict) and blockers.get(TARGET_GOAL_ID))
    ):
        raise GateError("FP046 R002 seq84 has an unresolved blocker or question")
    inventory = state.get("dynamic_goal_inventory")
    goal = inventory.get(TARGET_GOAL_ID) if isinstance(inventory, dict) else None
    if (
        not isinstance(goal, dict)
        or goal.get("goal_id") != TARGET_GOAL_ID
        or goal.get("path") != GOAL_RELATIVE.as_posix()
        or goal.get("sha256") != TARGET_GOAL_SHA256
        or goal.get("predecessor_goal_id") != PREDECESSOR_GOAL_ID
    ):
        raise GateError("FP046 R002 dynamic Goal inventory differs")
    children = state.get("materialized_child_goal_ids_by_parent")
    parent_children = (
        children.get(PARENT_GOAL_ID) if isinstance(children, dict) else None
    )
    if (
        not isinstance(parent_children, list)
        or parent_children.count(TARGET_GOAL_ID) != 1
    ):
        raise GateError("FP046 R002 parent/child materialization differs")
    if any(
        isinstance(event, dict)
        and event.get("event_type") == "GOAL_STARTED"
        and event.get("subject_goal_id") == TARGET_GOAL_ID
        for event in history
    ):
        raise GateError("FP046 R002 has already been started")
    current = checkpoint.get("current_work")
    if (
        not isinstance(current, dict)
        or current.get("work_item_id") != WORK_ITEM_ID
        or current.get("current_focus") != READY_CURRENT_FOCUS
        or current.get("release_completion_claimed") is not False
    ):
        raise GateError("FP046 R002 current-work READY pointer differs")
    _SOURCE_CORRECTION_SHA256.set(correction["event_sha256"])
    return SOURCE_READY_EVENT_SHA256, _parse_timestamp(
        correction.get("occurred_at"),
        label="FP046 R002 seq84 occurred_at",
    )


def _load_checkpoint(
    root: Path,
    *,
    retained_content: bytes | None = None,
) -> tuple[bytes, dict[str, Any]]:
    content, checkpoint = _base_load_checkpoint(
        root,
        retained_content=retained_content,
    )
    if (
        correction_authority.CONTROL_CORRECTION_SEQUENCE
        != CONTROL_CORRECTION_SEQUENCE
        or correction_authority.CONTROL_CORRECTION_EVENT_ID
        != CONTROL_CORRECTION_EVENT_ID
        or not hasattr(
            correction_authority,
            "reconstructed_seq84_checkpoint_bytes",
        )
    ):
        raise GateError("FP046 R002 seq84 correction authority is unavailable")
    try:
        correction_authority.require_control_corrected_checkpoint(
            root,
            checkpoint,
            run_external_validators=False,
            require_live_snapshot=_REQUIRE_LIVE_SNAPSHOT.get(),
        )
    except correction_authority.ControlCorrectionError as exc:
        raise GateError(
            f"FP046 R002 seq84 start-control correction differs: {exc}"
        ) from exc
    if _REQUIRE_LIVE_SNAPSHOT.get():
        _require_burned_empty_gate_namespace(root)
        _require_burned_logged_gate_namespace(root)
    return content, checkpoint


_impl.expected_contract_binding = expected_contract_binding
_impl._load_gate_contract = _load_gate_contract
_impl._load_checkpoint = _load_checkpoint
_impl._checkpoint_bound_gate_event_directories = (
    _checkpoint_bound_gate_event_directories
)
_impl._document_id = _recovery_document_id
_impl._validate_ready_source = _validate_ready_source


def _load_gate_context_with_correction(
    root: Path,
    retained_contents: Any = None,
) -> GateContext:
    token = _SOURCE_CORRECTION_SHA256.set(None)
    try:
        context = _base_load_gate_context(root, retained_contents)
        correction_sha256 = _SOURCE_CORRECTION_SHA256.get()
        if (
            not isinstance(correction_sha256, str)
            or _impl.SHA256_RE.fullmatch(correction_sha256) is None
        ):
            raise GateError("FP046 R002 seq84 correction SHA-256 is not sealed")
        return replace(
            context,
            source_activation_event_sha256=correction_sha256,
        )
    finally:
        _SOURCE_CORRECTION_SHA256.reset(token)


_impl.load_gate_context = _load_gate_context_with_correction


def load_gate_context(
    root: Path,
    retained_contents: Any = None,
    *,
    require_live_snapshot: bool = True,
) -> GateContext:
    token = _REQUIRE_LIVE_SNAPSHOT.set(require_live_snapshot)
    try:
        return _impl.load_gate_context(root, retained_contents)
    finally:
        _REQUIRE_LIVE_SNAPSHOT.reset(token)


def run_gate(root: Path, event_id: str, **kwargs: Any) -> Path:
    repository_state_guard = kwargs.get(
        "repository_state_guard",
        capture_repository_state,
    )
    if (
        kwargs.get("isolated_snapshot_factory") is None
        and repository_state_guard is capture_repository_state
    ):
        kwargs["isolated_snapshot_factory"] = (
            _capture_fp046_production_snapshot
        )
    token = _GATE_RUN_ROOT.set(root.resolve(strict=True))
    try:
        return _impl.run_gate(root, event_id, **kwargs)
    finally:
        _GATE_RUN_ROOT.reset(token)


@dataclass(frozen=True)
class PreflightCheckResult:
    check_id: str
    command: str
    output_sha256: str
    output_byte_length: int
    exit_code: int


def preflight_gate(
    root: Path,
    event_id: str,
    *,
    process_runner: Callable[..., subprocess.CompletedProcess[Any]] = (
        subprocess.run
    ),
    repository_state_guard: Callable[
        [Path, Path, str], dict[str, Any]
    ] = capture_repository_state,
    isolated_snapshot_factory: Callable[
        [
            Path,
            str,
            Any,
            Any,
            Callable[[Path, Path, str], dict[str, Any]],
        ],
        Any,
    ]
    | None = None,
) -> tuple[PreflightCheckResult, ...]:
    """Run all five checks in an isolated snapshot without a gate namespace."""

    _recovery_document_id(event_id)
    root = root.resolve(strict=True)
    token = _GATE_RUN_ROOT.set(root)
    resources = None
    primary: BaseException | None = None
    try:
        resources = _impl._GateRunResources.capture(root)
        resources.verify_source()
        context = load_gate_context(root, resources.source_guard.contents)
        resources.verify_source()
        authority = _impl._RepositoryStateAuthority.capture(
            resources.repository_guard,
            root / CHECKPOINT_RELATIVE,
            event_id,
            repository_state_guard,
        )
        resources.repository_authority = authority
        snapshot_factory = isolated_snapshot_factory
        if snapshot_factory is None:
            snapshot_factory = (
                _capture_fp046_production_snapshot
                if repository_state_guard is capture_repository_state
                else _impl._RetainedIsolatedRepositorySnapshot.capture
            )
        resources.snapshot = snapshot_factory(
            root,
            event_id,
            authority,
            resources.repository_guard,
            repository_state_guard,
        )
        snapshot = resources.snapshot
        resources.verify_all()
        environment = _impl._sanitized_environment(event_id)
        environment.update(
            {
                "PATH": (
                    os.fspath(resources.repository_guard.workspace / "commands")
                    + ":/usr/bin:/bin"
                ),
                "HOME": os.fspath(
                    resources.repository_guard.workspace / "home"
                ),
                "LANG": "C.UTF-8",
                "LC_ALL": "C",
                "GIT_TERMINAL_PROMPT": "0",
                "GIT_OPTIONAL_LOCKS": "0",
                "TMPDIR": os.fspath(
                    resources.repository_guard.workspace / "runtime" / "tmp"
                ),
            }
        )
        results: list[PreflightCheckResult] = []
        repository_output: bytes | None = None
        for check_id, command in context.checks:
            resources.verify_source()
            snapshot.verify()
            with tempfile.TemporaryFile(
                dir=resources.repository_guard.workspace / "runtime" / "tmp"
            ) as output:
                try:
                    completed = process_runner(
                        command,
                        shell=True,
                        executable="/bin/bash",
                        cwd=Path(f"/proc/self/fd/{snapshot.root_descriptor}"),
                        pass_fds=(snapshot.root_descriptor,),
                        env=environment,
                        stdout=output,
                        stderr=subprocess.STDOUT,
                        check=False,
                    )
                except Exception as exc:
                    raise GateError(
                        f"{check_id} preflight runner raised an exception"
                    ) from exc
                exit_code = int(completed.returncode)
                size = output.tell()
                if (
                    exit_code == 0
                    and size == 0
                    and check_id == "TEST_LAYER_REGISTRY_VALIDATE"
                ):
                    output.write(b"TEST_LAYER_REGISTRY_VALIDATE: PASS\n")
                    size = output.tell()
                if size > _impl.LOG_MAX_BYTES:
                    raise GateError(f"{check_id} preflight output is too large")
                output.seek(0)
                content = output.read()
            resources.verify_source()
            snapshot.verify()
            if exit_code != 0:
                raise GateCheckFailed(check_id, exit_code, "PREVIEW_ONLY")
            if not content:
                raise GateError(f"{check_id} preflight output is empty")
            result = PreflightCheckResult(
                check_id=check_id,
                command=command,
                output_sha256=_impl.sha256_bytes(content),
                output_byte_length=len(content),
                exit_code=0,
            )
            results.append(result)
            if check_id == "REPOSITORY_STATE":
                repository_output = content
        if tuple(result.check_id for result in results) != EXPECTED_CHECK_IDS:
            raise GateError("preflight check order differs")
        if repository_output is None:
            raise GateError("REPOSITORY_STATE preflight output is missing")
        try:
            repository_payload = json.loads(repository_output)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise GateError(
                "REPOSITORY_STATE preflight output is not valid JSON"
            ) from exc
        authority.require_exact(
            repository_payload,
            label="REPOSITORY_STATE preflight output",
        )
        if repository_output != authority.cli_output:
            raise GateError(
                "REPOSITORY_STATE preflight output bytes differ from captured "
                "repository authority"
            )
        snapshot.verify_repository()
        resources.verify_source()
        if load_gate_context(root, resources.source_guard.contents) != context:
            raise GateError("FP046 R002 source changed during preflight")
        guarded_payload = resources.repository_guard.capture_state(
            root / CHECKPOINT_RELATIVE,
            event_id,
            capture=repository_state_guard,
        )
        authority.require_exact(
            guarded_payload,
            label="FP046 R002 repository after preflight",
        )
        resources.verify_all()
        return tuple(results)
    except BaseException as exc:
        primary = exc
        raise
    finally:
        try:
            if resources is not None:
                resources.close(primary)
        finally:
            _GATE_RUN_ROOT.reset(token)


def __getattr__(name: str) -> Any:
    return getattr(_impl, name)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--preflight",
        action="store_true",
        help="run the isolated five checks without creating the gate namespace",
    )
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def _write_pass_result(receipt_path: Path) -> None:
    try:
        _impl._write_raw_exact(
            sys.stdout,
            f"FP-046 R002 initial-start gate: PASS: {receipt_path}\n".encode(),
        )
    except BaseException as exc:
        raise GatePostCommitUncertain(
            "published receipt PASS output delivery is uncertain"
        ) from exc


def _write_postcommit_diagnostic(message: str) -> None:
    try:
        _impl._write_raw_exact(sys.stderr, f"{message}\n".encode())
    except BaseException:
        pass


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.preflight:
            results = preflight_gate(args.root, args.event_id)
            _impl._write_raw_exact(
                sys.stdout,
                (
                    "FP-046 R002 initial-start gate preflight: PASS: "
                    f"{len(results)} checks; no gate namespace created\n"
                ).encode(),
            )
            return 0
        receipt_path = run_gate(args.root, args.event_id)
        _write_pass_result(receipt_path)
    except GateCheckFailed as exc:
        print(f"FP-046 R002 initial-start gate: FAIL: {exc}", file=sys.stderr)
        return exc.exit_code if exc.exit_code > 0 else 1
    except GatePostCommitUncertain as exc:
        _write_postcommit_diagnostic(
            f"FP-046 R002 initial-start gate: POSTCOMMIT-UNCERTAIN: {exc}",
        )
        return 2
    except (GateError, OSError, ValueError) as exc:
        print(f"FP-046 R002 initial-start gate: ERROR: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
