#!/usr/bin/env python3
"""Prepare the reviewed zero-credit FP048 R002 seq96 R007-to-R008 correction."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import importlib
import os
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import (
    apply_walksafe_fp048_r002_start_gate_contract_correction_seq95_20260826
    as seq95,
)


class ContractCorrectionError(seq95.ContractCorrectionError):
    """The seq96 contract correction cannot be proven or published."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractCorrectionError(message)


seq90 = seq95.seq90
continuation = seq95.continuation
CHECKPOINT_REL = seq95.CHECKPOINT_REL
checkpoint_json_bytes = seq95.checkpoint_json_bytes
GOAL_ID = seq95.GOAL_ID
GOAL_PATH = seq95.GOAL_PATH
GOAL_SHA256 = seq95.GOAL_SHA256
WORK_ITEM_ID = seq95.WORK_ITEM_ID
MANIFEST_SHA256 = seq95.MANIFEST_SHA256
EVENT_FIELDS = seq95.EVENT_FIELDS
CLAIM_BOUNDARY = copy.deepcopy(seq95.CLAIM_BOUNDARY)

SOURCE_SEQUENCE = 95
SOURCE_EVENT_ID = seq95.CORRECTION_EVENT_ID
SOURCE_EVENT_SHA256 = (
    "e22241ecddee3aba481dcfe5eaa948de067815e25936fd34c30d1cbf9ea8072d"
)
SOURCE_CHECKPOINT_SHA256 = (
    "bf65fcfc93fefeede55ebec628d57ab83dbb3907b902fd30b3c41098051ff00b"
)
SOURCE_CHECKPOINT_BYTE_LENGTH = 4_083_436

CORRECTION_SEQUENCE = 96
CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-GATE-CONTRACT-CORRECTED-"
    "FP048-R002-20260826-004"
)
CORRECTION_EVENT_TYPE = "GOAL_START_GATE_CONTRACT_CORRECTED"
STARTED_SEQUENCE = 97
STARTED_EVENT_ID = seq95.STARTED_EVENT_ID

PHYSICAL_GIT_BRANCH = seq95.PHYSICAL_GIT_BRANCH
LOGICAL_BRANCH = seq95.LOGICAL_BRANCH
LOGICAL_BRANCH_SEMANTICS = seq95.LOGICAL_BRANCH_SEMANTICS
BRANCH_MISMATCH_REASON_CODE = seq95.BRANCH_MISMATCH_REASON_CODE

PASSED_GATE_EVENT_ID = seq95.PASSED_GATE_EVENT_ID
PASSED_GATE_DIR_REL = seq95.PASSED_GATE_DIR_REL
PASSED_GATE_FILE_PINS = seq95.PASSED_GATE_FILE_PINS
PREFLIGHT_ATTEMPT_EVENT_ID = STARTED_EVENT_ID
PREFLIGHT_ATTEMPT_DIR_REL = seq95.PREFLIGHT_ATTEMPT_DIR_REL
PREFLIGHT_ATTEMPT_RECEIPT_REL = seq95.PREFLIGHT_ATTEMPT_RECEIPT_REL

HISTORICAL_R005_PREFLIGHT_ATTEMPT_004 = copy.deepcopy(
    seq95.HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
)
R006_PREFLIGHT_ATTEMPT_004 = copy.deepcopy(seq95.R006_PREFLIGHT_ATTEMPT_004)
R007_CONTRACT_BINDING = copy.deepcopy(seq95.R007_CONTRACT_BINDING)
R007_RUNNER_BINDING = copy.deepcopy(seq95.R007_RUNNER_BINDING)
R007_PREFLIGHT_ATTEMPT_004 = {
    "event_id": STARTED_EVENT_ID,
    "contract_id": R007_CONTRACT_BINDING["contract_id"],
    "contract_version": R007_CONTRACT_BINDING["contract_version"],
    "directory": PREFLIGHT_ATTEMPT_DIR_REL.as_posix(),
    "receipt_path": PREFLIGHT_ATTEMPT_RECEIPT_REL.as_posix(),
    "status": "PREVIEW_FAILED_BEFORE_NAMESPACE",
    "authority_status": "NONAUTHORITY",
    "event_identity_status": "REUSABLE_UNCONSUMED",
    "namespace_present": False,
    "receipt_present": False,
    "failed_check_id": "ROOT_FP048_R002_CONTROL_REGRESSION",
    "exit_code": 1,
    "error": (
        "ROOT_FP048_R002_CONTROL_REGRESSION failed with exit code 1; "
        "see PREVIEW_ONLY"
    ),
    "reason_code": "R007_ROOT_REGRESSION_INCLUDED_PREPUBLICATION_ONLY_SEQ95_TESTS",
}
R008_SUCCESSOR_REASON_CODE = (
    "R007_ROOT_REGRESSION_INCLUDED_PREPUBLICATION_ONLY_SEQ95_TESTS"
)
CORRECTION_REASON = {
    "failed_contract_id": R007_CONTRACT_BINDING["contract_id"],
    "failed_contract_version": R007_CONTRACT_BINDING["contract_version"],
    "r007_preflight_attempt_004": copy.deepcopy(R007_PREFLIGHT_ATTEMPT_004),
    "reason_code": R008_SUCCESSOR_REASON_CODE,
    "remediation": "SUPERSEDE_R007_WITH_STAGE_AWARE_R008_BEFORE_SEQ97_START",
}
PROJECTED_TRANSITION = {
    "contract_correction_event_id": CORRECTION_EVENT_ID,
    "contract_correction_sequence": CORRECTION_SEQUENCE,
    "contract_correction_transition": "READY_TO_READY",
    "started_event_id": STARTED_EVENT_ID,
    "started_sequence": STARTED_SEQUENCE,
    "started_transition": "READY_TO_IN_PROGRESS_AFTER_FRESH_R008_PASS",
}

FROZEN_SEQ95_AUTHORIZATION_BINDING = {
    "path": (
        "docs/control/execution/workstream-transitions/seq95-96/authorization.json"
    ),
    "sha256": "28e53c257a2090901f5a7760bf6d09c7033d525616eb157f92eccff3655778aa",
    "byte_length": 13_457,
}
FROZEN_SEQ95_R002_REVIEW_BINDINGS = {
    "assignment": {
        "path": (
            "docs/control/execution/workstream-transitions/seq95-96/"
            "review-rounds/R002/review-assignment.json"
        ),
        "sha256": "fdf2dd50934b0a4a6a2b67cd733be2575a29d4791844a3ca043120b6d6eef98b",
        "byte_length": 21_684,
    },
    "review_result": {
        "path": (
            "docs/control/execution/workstream-transitions/seq95-96/"
            "review-rounds/R002/review-result.json"
        ),
        "sha256": "92dcde86ae90e83dd19635b733ae6b3d31dc88291fd8a54413abbad533d9452b",
        "byte_length": 14_437,
    },
    "independent_review": {
        "path": (
            "docs/control/execution/workstream-transitions/seq95-96/"
            "review-rounds/R002/independent-review.json"
        ),
        "sha256": "62335664f51c82b12323e8c239220285f5f833efdda5a24f1d34e916eb0db599",
        "byte_length": 15_481,
    },
}
FROZEN_SEQ95_R002_REVIEWED_CONTROL_INPUTS = [
    {
        "path": (
            "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
            "initial-start-gate-contract-r007.json"
        ),
        "sha256": "ba18500d312c447b0f2107d63e1165d80bb2fc232afaba4a98310620b50fed1e",
        "byte_length": 3_567,
    },
    {
        "path": "scripts/apply_walksafe_fp048_r002_goal_started_seq96_20260826.py",
        "sha256": "05fb2ef21595fba711d1ac06161174ae130085fd43de99d9bd7d7382c49f554a",
        "byte_length": 81_024,
    },
    {
        "path": (
            "scripts/apply_walksafe_fp048_r002_start_gate_contract_correction_"
            "seq95_20260826.py"
        ),
        "sha256": "03518e3726afa6293fe5a386284747fcacb2b0b921534ed93529a0255ac0d2ff",
        "byte_length": 101_887,
    },
    {
        "path": "scripts/check_walksafe_goal_graph_v2_4.py",
        "sha256": "9b989341dd28813ae04fec675579f8b4b0de728674697fd7de51a38cd08daea0",
        "byte_length": 862_644,
    },
    {
        "path": "scripts/check_walksafe_project_continuation_v2_4.py",
        "sha256": "0df90bf720a59879be4fdb0385e7b51db89d3a7c5a97dd73ef3282b5f9f11e25",
        "byte_length": 608_527,
    },
    {
        "path": "scripts/run_walksafe_fp048_r002_goal_start_gate_r007_20260826.py",
        "sha256": "6c4bbbad097e783d10951b212d5ef285c7c8cc51fe6fbe0212528bb0c5cdd9e8",
        "byte_length": 32_322,
    },
    {
        "path": "scripts/run_walksafe_test_layers_current.sh",
        "sha256": "cac4c8b452983cce77f1874b49d14acd8336dc57f5c88f5ac3ba880999d315a6",
        "byte_length": 27_679,
    },
    {
        "path": "tests/test_apply_walksafe_fp048_r002_goal_started_seq96_20260826.py",
        "sha256": "6caf02bbd640ba1b063d916be43a44d2a4b9d0376e050b27469201dd03ab4fa3",
        "byte_length": 64_288,
    },
    {
        "path": (
            "tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_"
            "seq95_20260826.py"
        ),
        "sha256": "e8fd0d109781a944d59bb0c42a3811c94658c95a3028a8bcb3484b89196e21b9",
        "byte_length": 55_206,
    },
    {
        "path": "tests/test_walksafe_fp048_r002_goal_start_gate_r007_20260826.py",
        "sha256": "dcf1469e31b16d2472eff7806fa5d3dfc16be6696908918dc9b087ad5ed32463",
        "byte_length": 18_141,
    },
    {
        "path": "tests/test_walksafe_fp048_r002_post_seq94_stage_regression_20260826.py",
        "sha256": "b7fa5518748e7da3d2a23f3dfab940320baff4bcd0cf02490e1b93b8cb142fe3",
        "byte_length": 8_319,
    },
    {
        "path": "tests/test_walksafe_goal_graph_v2_4.py",
        "sha256": "17ab24852f3c37efb56cb21263363e65321cf54a64d0d6ea49d049c6bdabbcce",
        "byte_length": 590_149,
    },
    {
        "path": "tests/test_walksafe_project_continuation_v2_4.py",
        "sha256": "4b3d95f6cf8633eb30ea01dd8e5ad45575bf79ed2e4abdefce2e8fd7ce20f752",
        "byte_length": 326_539,
    },
]
SOURCE_EVENT_SOURCE_BINDING = copy.deepcopy(seq95._source_checkpoint_binding())

R008_CONTRACT_REL = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r008.json"
)
R008_RUNNER_REL = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r008_20260826.py"
)
R008_RUNNER_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r008_20260826.py"
)
POST_SEQ95_STAGE_REGRESSION_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_post_seq95_stage_regression_20260826.py"
)
SEQ97_STARTER_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq97_20260826.py"
)
SEQ97_STARTER_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq97_20260826.py"
)
SEQ98_99_COMPLETION_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_completed_seq98_99_20260826.py"
)
SEQ98_99_COMPLETION_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_completed_seq98_99_20260826.py"
)
NONCREDIT_FP023_PRODUCT_PATHS = (
    Path(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivity.kt"
    ),
    Path(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/"
        "AndroidFeedbackActuator.kt"
    ),
    Path(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/"
        "PendingSpeechQueue.kt"
    ),
    Path(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/"
        "AndroidVoiceCommand.kt"
    ),
    Path(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/"
        "BackendWalkingRouteClient.kt"
    ),
    Path(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/"
        "RouteNavigator.kt"
    ),
    Path(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/"
        "EncryptedRouteSnapshotStore.kt"
    ),
    Path(
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivityNavigationCompositionTest.kt"
    ),
    Path(
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/feedback/"
        "AndroidFeedbackActuatorStaticTest.kt"
    ),
    Path(
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/feedback/"
        "PendingSpeechQueueTest.kt"
    ),
    Path(
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/"
        "AndroidVoiceCommandTest.kt"
    ),
    Path(
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/"
        "BackendWalkingRouteClientNetworkTest.kt"
    ),
    Path(
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/"
        "BackendWalkingRouteClientTest.kt"
    ),
    Path(
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/"
        "RouteNavigatorTest.kt"
    ),
    Path(
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/"
        "EncryptedRouteSnapshotStoreTest.kt"
    ),
)
NONCREDIT_FP023_PRODUCT_AUTHORITY_LABEL = "NONCREDIT_REPOSITORY_CONTEXT_ONLY"
NONCREDIT_FP023_CREDIT_BOUNDARY = {
    "actual_device_test_credit_delta": 0,
    "deployment_credit_delta": 0,
    "external_review_credit_delta": 0,
    "formal_test_credit_delta": 0,
    "implementation_completion_credit_delta": 0,
    "release_credit_delta": 0,
}
R008_CONTRACT_ID = "WS-FP048-R002-INTERNAL-START-GATE-R008"
R008_CONTRACT_VERSION = "2026-08-26.7"
R008_DOCUMENT_ID = "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-008"
# These producing-agent pins are rechecked immediately before review publication.
R008_FILE_SHA256 = (
    "f3106c7038287399deac3bc711d1a7624dec3aa84b9c41abeb52300d5073828d"
)
R008_CANONICAL_SHA256 = (
    "4f39f9cc18a5fff55b1369c5c0ff15a50ec080e7cced227597adea0b902afb91"
)
R008_BYTE_LENGTH = 3_571
R008_RUNNER_SHA256 = (
    "9893913d951754cbdbb1991eea7eda76b30b2a878f1fc2d9e4a792ff515c9f7e"
)
R008_RUNNER_BYTE_LENGTH = 33_898
R008_CONTRACT_BINDING = {
    "schema_version": "1.2",
    "document_id": R008_DOCUMENT_ID,
    "path": R008_CONTRACT_REL.as_posix(),
    "file_sha256": R008_FILE_SHA256,
    "contract_id": R008_CONTRACT_ID,
    "contract_version": R008_CONTRACT_VERSION,
    "canonical_contract_sha256": R008_CANONICAL_SHA256,
}
R008_RUNNER_BINDING = {
    "path": R008_RUNNER_REL.as_posix(),
    "sha256": R008_RUNNER_SHA256,
    "byte_length": R008_RUNNER_BYTE_LENGTH,
}

SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp048_r002_start_gate_contract_correction_"
    "seq96_20260826.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_"
    "seq96_20260826.py"
)
CONTINUATION_SCRIPT_REL = Path("scripts/check_walksafe_project_continuation_v2_4.py")
CONTINUATION_TEST_REL = Path("tests/test_walksafe_project_continuation_v2_4.py")
GOAL_GRAPH_SCRIPT_REL = Path("scripts/check_walksafe_goal_graph_v2_4.py")
GOAL_GRAPH_TEST_REL = Path("tests/test_walksafe_goal_graph_v2_4.py")
LAYER_RUNNER_REL = Path("scripts/run_walksafe_test_layers_current.sh")
REVIEWED_CONTROL_PATHS = tuple(
    sorted(
        {
            SCRIPT_REL,
            TEST_REL,
            R008_CONTRACT_REL,
            R008_RUNNER_REL,
            R008_RUNNER_TEST_REL,
            POST_SEQ95_STAGE_REGRESSION_TEST_REL,
            SEQ97_STARTER_REL,
            SEQ97_STARTER_TEST_REL,
            SEQ98_99_COMPLETION_REL,
            SEQ98_99_COMPLETION_TEST_REL,
            CONTINUATION_SCRIPT_REL,
            CONTINUATION_TEST_REL,
            GOAL_GRAPH_SCRIPT_REL,
            GOAL_GRAPH_TEST_REL,
            LAYER_RUNNER_REL,
            *NONCREDIT_FP023_PRODUCT_PATHS,
        },
        key=lambda path: path.as_posix(),
    )
)
# Missing successor/checker pins intentionally fail closed until producing agents freeze.
FIXED_REVIEWED_CONTROL_BINDINGS: dict[Path, dict[str, Any]] = {
    R008_CONTRACT_REL: {
        "path": R008_CONTRACT_REL.as_posix(),
        "sha256": R008_FILE_SHA256,
        "byte_length": R008_BYTE_LENGTH,
    },
    R008_RUNNER_REL: copy.deepcopy(R008_RUNNER_BINDING),
    R008_RUNNER_TEST_REL: {
        "path": R008_RUNNER_TEST_REL.as_posix(),
        "sha256": "ce2d005c233a427409d3606680d25fbc5733d185e44883d6a3253a0e9648a261",
        "byte_length": 19_558,
    },
    POST_SEQ95_STAGE_REGRESSION_TEST_REL: {
        "path": POST_SEQ95_STAGE_REGRESSION_TEST_REL.as_posix(),
        "sha256": "be8579a893bbdd7426844c5a564438958b6024a6af956573d3c3ef00a60d11de",
        "byte_length": 9_432,
    },
    SEQ97_STARTER_REL: {
        "path": SEQ97_STARTER_REL.as_posix(),
        "sha256": "86eefaf1a56514d3aba0acefe0168ebee24a0ac70d86995ef5d6c48a0ba1b972",
        "byte_length": 83_555,
    },
    SEQ97_STARTER_TEST_REL: {
        "path": SEQ97_STARTER_TEST_REL.as_posix(),
        "sha256": "c7d8364158d77df7889238088002e9626b1d7cde1ad302272ae7c2349b332395",
        "byte_length": 66_855,
    },
    SEQ98_99_COMPLETION_REL: {
        "path": SEQ98_99_COMPLETION_REL.as_posix(),
        "sha256": "a7dc25aaf7663c97591437a9235c8f4bb8e138c83856d6806ff6d4aaa4c36151",
        "byte_length": 77_838,
    },
    SEQ98_99_COMPLETION_TEST_REL: {
        "path": SEQ98_99_COMPLETION_TEST_REL.as_posix(),
        "sha256": "b96df42805cdfd65b183ba709598645434c8edcf6677da950e8d0a8c770d6348",
        "byte_length": 33_081,
    },
    CONTINUATION_SCRIPT_REL: {
        "path": CONTINUATION_SCRIPT_REL.as_posix(),
        "sha256": "20b2517c8fea9d977ea6e60a4193c5f00442025a59684a3a43b823e5a4e2e0e5",
        "byte_length": 650_602,
    },
    CONTINUATION_TEST_REL: {
        "path": CONTINUATION_TEST_REL.as_posix(),
        "sha256": "2c0e7391663fe1758d2edaffd7eb2ef19f4ebc9a4733b7c9b364534c53a209ea",
        "byte_length": 347_060,
    },
    GOAL_GRAPH_SCRIPT_REL: {
        "path": GOAL_GRAPH_SCRIPT_REL.as_posix(),
        "sha256": "9272683a635a1dc47bf5bb17b15c728524e1e7aa868bb2eae60a64b5be739e0b",
        "byte_length": 916_625,
    },
    GOAL_GRAPH_TEST_REL: {
        "path": GOAL_GRAPH_TEST_REL.as_posix(),
        "sha256": "4fe5b5e557d18cfde07933e761c9736efcc1d151d85d2f15d465434c9e58af41",
        "byte_length": 636_916,
    },
    LAYER_RUNNER_REL: {
        "path": LAYER_RUNNER_REL.as_posix(),
        "sha256": "fbf48402b38b568f5a1776081c042043f299d056d6a3a47d1b9285c365d95770",
        "byte_length": 28_051,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[0]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[0].as_posix(),
        "sha256": "3c6e7070f518a5c5a57d6bf48b11bb5e14ac52f2d55b38e2324bbb352d21e5b7",
        "byte_length": 958_062,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[1]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[1].as_posix(),
        "sha256": "9ece6c2e3c2a7ea5872a2bf910b733efaff00c9bd2da6ec843772258f5b196f5",
        "byte_length": 43_204,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[2]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[2].as_posix(),
        "sha256": "60474dce53a7ba7b387df8d7be3981e44d0ae7c91a19b124b57df7bcf9aebb2d",
        "byte_length": 2_049,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[3]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[3].as_posix(),
        "sha256": "1b680ad776f3a5d5bf9568db232fc9f7f94507bd7852caa42f256a2d7ff9c1b0",
        "byte_length": 13_860,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[4]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[4].as_posix(),
        "sha256": "ccb6d19286f1c252d7c3850b3e1ccc77d9ee29e4a7147b5202f146c988a9fe3a",
        "byte_length": 29_357,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[5]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[5].as_posix(),
        "sha256": "1109965edc5ffe77d4475af5d0100e4c6301a225242c70b2a871bbab13fb9665",
        "byte_length": 31_790,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[6]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[6].as_posix(),
        "sha256": "2fd358afde99f06d3a48145daac6c0961c4ecf7fce090f48d4c12d91248b0732",
        "byte_length": 9_678,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[7]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[7].as_posix(),
        "sha256": "2bffef0907f88f8644597960ebdeecc2cb9ff1ed41e4d865cf92e29f1e21fd19",
        "byte_length": 19_431,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[8]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[8].as_posix(),
        "sha256": "59e8f2e6be7a7e0e4f0ede739efc3947cec7ee0ff1b5ea852a8be20199f7874a",
        "byte_length": 15_155,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[9]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[9].as_posix(),
        "sha256": "a4b99d3e86d9b0a566778fe3b7a18635a94a332b3e22a70e43675d4b6958cd3b",
        "byte_length": 2_719,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[10]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[10].as_posix(),
        "sha256": "84b0206cfcaf2eff3baca1f861cbdb56adecd24f5ad8b9da4cdc5b23ac12e74c",
        "byte_length": 12_449,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[11]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[11].as_posix(),
        "sha256": "3ac6abf7327f5da970462213a269e9a67821edb4afceff31f58ebe34068b5301",
        "byte_length": 8_695,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[12]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[12].as_posix(),
        "sha256": "7306a71891607b23f2fe760c2a1d1bcb814666d2b75d35c7c7ba8ff18db9d218",
        "byte_length": 13_914,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[13]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[13].as_posix(),
        "sha256": "2066177062eedd5406ec7fa850b9e30f26e89f8e5d4bc44ddcad1f6ae2c0e24e",
        "byte_length": 31_084,
    },
    NONCREDIT_FP023_PRODUCT_PATHS[14]: {
        "path": NONCREDIT_FP023_PRODUCT_PATHS[14].as_posix(),
        "sha256": "571afde942a3d5be550656c449f59a52b474578f0824b2442dc1e3854b7846d8",
        "byte_length": 6_347,
    },
}

TRANSITION_ROOT = Path("docs/control/execution/workstream-transitions/seq96-97")
AUTHORIZATION_REL = TRANSITION_ROOT / "authorization.json"
FROZEN_AUTHORIZATION_BINDING = {
    "path": AUTHORIZATION_REL.as_posix(),
    "sha256": "779a109cc7ce240c01ed657333f77c751201ac7aeef52fb82f48db2ad17d0644",
    "byte_length": 18_074,
}
R001_ROUND_ID = "R001"
R001_REVIEW_ROOT = TRANSITION_ROOT / f"review-rounds/{R001_ROUND_ID}"
FROZEN_R001_REVIEW_BINDINGS = {
    "assignment": {
        "path": (R001_REVIEW_ROOT / "review-assignment.json").as_posix(),
        "sha256": "1223b22f39ce23901ad093506d2ed4942c9572ade2a7a076861697dda6b64794",
        "byte_length": 21_485,
    }
}
ROUND_ID = "R002"
REVIEW_ROOT = TRANSITION_ROOT / f"review-rounds/{ROUND_ID}"
REVIEW_ASSIGNMENT_REL = REVIEW_ROOT / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_ROOT / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_ROOT / "independent-review.json"
REVIEW_PATHS = (
    REVIEW_ASSIGNMENT_REL,
    REVIEW_RESULT_REL,
    INDEPENDENT_REVIEW_REL,
)
REVIEWER = {
    "id": "codex-seq96-goalgraph-terminal-dispatch-auditor-20260826",
    "task_id": "/root/seq96_goalgraph_terminal_dispatch_audit",
}
SUPERSEDES_ROUND_ID = R001_ROUND_ID
SUPERSESSION_REASON = (
    "R001_PROJECTED_GOAL_GRAPH_REJECTED_UNSEALED_FP023_PRODUCT_SUCCESSOR"
)
R001_PROJECTED_PREFLIGHT_FAILURE = {
    "authorization_binding": copy.deepcopy(FROZEN_AUTHORIZATION_BINDING),
    "checkpoint_binding": {
        "path": CHECKPOINT_REL.as_posix(),
        "sha256": SOURCE_CHECKPOINT_SHA256,
        "byte_length": SOURCE_CHECKPOINT_BYTE_LENGTH,
        "sequence": SOURCE_SEQUENCE,
        "tail_event_id": SOURCE_EVENT_ID,
        "tail_event_sha256": SOURCE_EVENT_SHA256,
    },
    "checkpoint_mode": "0600",
    "checkpoint_unchanged": True,
    "checkpoint_write_attempted": False,
    "error_anchor": (
        "FP046 final source binding differs: apps/android/app/src/main/java/kr/co/"
        "hanium/dreamup/walksafe/MainActivity.kt"
    ),
    "exit_code": 1,
    "gate_attempt_004": {
        "directory": PREFLIGHT_ATTEMPT_DIR_REL.as_posix(),
        "receipt_path": PREFLIGHT_ATTEMPT_RECEIPT_REL.as_posix(),
        "namespace_present": False,
        "receipt_present": False,
        "write_attempted": False,
    },
    "independent_review_path": (
        R001_REVIEW_ROOT / "independent-review.json"
    ).as_posix(),
    "independent_review_present": False,
    "reason_code": SUPERSESSION_REASON,
    "review_assignment_binding": copy.deepcopy(
        FROZEN_R001_REVIEW_BINDINGS["assignment"]
    ),
    "review_result_path": (R001_REVIEW_ROOT / "review-result.json").as_posix(),
    "review_result_present": False,
    "status": "PROJECTED_PREFLIGHT_FAILED_BEFORE_WRITE",
    "validator_stage": "_validate_projected_with_consumers",
}
INDEPENDENT_CHECKS = [
    "EXACT_PUBLISHED_SEQ95_SOURCE_CAS_PASS",
    "FROZEN_SEQ95_R002_AUTHORIZATION_AND_REVIEW_TRIAD_PASS",
    "R004_PASS_003_REMAINS_PRIVATE_PASS_UNCONSUMED_PASS",
    "R005_AND_R006_PREFLIGHT_004_HISTORICAL_NONAUTHORITY_PASS",
    "R007_PREFLIGHT_004_ROOT_REGRESSION_NONAUTHORITY_PASS",
    "R007_EXACT_CONTRACT_AND_RUNNER_PRESERVED_PASS",
    "R007_TO_R008_CONTRACT_SUPERSESSION_PASS",
    "SEQ96_READY_TO_READY_ZERO_CREDIT_PASS",
    "SEQ96_TO_SEQ95_EXACT_BYTE_RECONSTRUCTION_PASS",
    "FINAL_MANAGED_AND_GIT_VISIBLE_COHORT_RESEAL_PASS",
    "PRIVATE_0600_CHECKPOINT_MODE_PRECOMMIT_AND_POSTCOMMIT_PASS",
    "PROJECTED_CONTINUATION_AND_GOAL_GRAPH_PASS",
    "R001_ASSIGNMENT_PRESERVED_AND_UNAPPROVED_PASS",
    "R001_PROJECTED_PREFLIGHT_FAILURE_BOUNDARY_FIXED_PASS",
    "FP023_PRODUCT_SUCCESSOR_EXACT_BYTES_NONCREDIT_REANCHOR_PASS",
]
REVIEW_RESULT_FIELDS = frozenset(
    {
        "assignment_binding",
        "claim_boundary",
        "correction_reason",
        "decision",
        "document_id",
        "external_independence_claimed",
        "findings",
        "frozen_seq95_r002_authorization_binding",
        "frozen_seq95_r002_review_binding",
        "frozen_seq95_r002_reviewed_control_inputs",
        "goal_id",
        "historical_r005_preflight_attempt_004",
        "noncredit_fp023_product_successor_bindings",
        "passed_gate_attempt_003_binding",
        "previous_contract_binding",
        "projected_transition",
        "r001_projected_preflight_failure",
        "r006_preflight_attempt_004",
        "r007_preflight_attempt_004",
        "replacement_contract_binding",
        "reviewed_at",
        "reviewer",
        "round_id",
        "schema_version",
        "source_checkpoint_binding",
        "superseded_review_binding",
        "supersedes_round_id",
        "supersession_reason",
    }
)
INDEPENDENT_REVIEW_FIELDS = REVIEW_RESULT_FIELDS | {
    "independent_checks",
    "review_result_binding",
}
AUTHORIZATION_FIELDS = frozenset(
    {
        "authorization_scope",
        "authorization_status",
        "claim_boundary",
        "contract_correction_event_id",
        "correction_reason",
        "document_id",
        "evidence_type",
        "frozen_seq95_r002_authorization_binding",
        "frozen_seq95_r002_review_binding",
        "frozen_seq95_r002_reviewed_control_inputs",
        "goal_id",
        "historical_r005_preflight_attempt_004",
        "passed_gate_attempt_003_binding",
        "previous_contract_binding",
        "projected_transition",
        "r006_preflight_attempt_004",
        "r007_preflight_attempt_004",
        "replacement_contract_binding",
        "schema_version",
        "source_checkpoint_binding",
        "target_started_event_id",
    }
)
REVIEW_ASSIGNMENT_FIELDS = frozenset(
    {
        "assigner",
        "authorization_binding",
        "claim_boundary",
        "correction_reason",
        "document_id",
        "executor",
        "frozen_seq95_r002_authorization_binding",
        "frozen_seq95_r002_review_binding",
        "frozen_seq95_r002_reviewed_control_inputs",
        "goal_id",
        "historical_r005_preflight_attempt_004",
        "noncredit_fp023_product_successor_bindings",
        "passed_gate_attempt_003_binding",
        "previous_contract_binding",
        "previous_runner_binding",
        "projected_transition",
        "r001_projected_preflight_failure",
        "r006_preflight_attempt_004",
        "r007_preflight_attempt_004",
        "replacement_contract_binding",
        "replacement_runner_binding",
        "required_reviewer",
        "review_scope",
        "reviewed_control_inputs",
        "round_id",
        "schema_version",
        "source_checkpoint_binding",
        "superseded_review_binding",
        "supersedes_round_id",
        "supersession_reason",
    }
)
AUTHORIZATION_SCOPE = {
    "authorized_actions": [
        "SEQ96_ZERO_CREDIT_R007_TO_R008_CONTRACT_CORRECTION",
        "R008_PRIVATE_INITIAL_START_GATE_AFTER_SEQ96",
        "SEQ97_GOAL_STARTED_AFTER_FRESH_R008_FIVE_CHECK_PASS",
    ],
    "conditions": [
        "EXACT_PUBLISHED_SEQ95_SOURCE_CAS_REQUIRED",
        "FROZEN_SEQ95_R002_AUTHORIZATION_AND_REVIEW_MUST_REMAIN_EXACT",
        "R007_PREFLIGHT_004_MUST_REMAIN_NONAUTHORITY",
        (
            "R007_PREFLIGHT_004_NAMESPACE_AND_RECEIPT_MUST_REMAIN_ABSENT_"
            "THROUGH_SEQ96_PUBLICATION_AND_R008_PRE_GATE"
        ),
        (
            "FRESH_R008_MAY_CREATE_THE_SAME_004_NAMESPACE_AND_RECEIPT_"
            "FOR_SEQ97_CONSUMPTION"
        ),
        "SEQ96_MUST_PRESERVE_READY_STATUS_AND_ZERO_CREDIT",
        "SEQ97_REQUIRES_A_FRESH_R008_FIVE_CHECK_PASS_RECEIPT",
    ],
    "excluded_actions": [
        "TREAT_R007_PREFLIGHT_004_AS_GATE_EVIDENCE",
        "BURN_REUSABLE_004_EVENT_ID_WITHOUT_A_NAMESPACE",
        "FORMAL_OR_ACTUAL_DEVICE_TEST_CREDIT",
        "EXTERNAL_REVIEW_OR_DEPLOYMENT_CREDIT",
        "RELEASE_OR_COMPLETION_CREDIT",
    ],
}
REVIEW_SCOPE = [
    "exact published seq95 source CAS and frozen R002 authority",
    "R007 preview ROOT regression exit 1 before namespace",
    "R007 nonauthority and reusable -004 identity",
    "R007-to-R008 append-only contract supersession",
    "all modified and new stage-aware R008 control inputs",
    "seq96 READY-to-READY transition with every credit delta zero",
    "exact seq96-to-seq95 byte reconstruction",
    "physical Git branch, managed cohort, and Git-visible cohort reseal",
    (
        "fifteen exact FP023 product successor bytes as "
        "NONCREDIT_REPOSITORY_CONTEXT_ONLY authority"
    ),
    "R001 assignment preserved with result and independent review absent",
    "R001 projected GoalGraph exit 1 left checkpoint and gate -004 unwritten",
    "atomic replacement and private 0600 checkpoint mode",
]

SCOPE = (
    "Graph v2.4 FP-048 R002 remains READY through zero-credit seq96 R007-to-"
    "R008 gate-contract correction; -004 remains reusable and fresh R008 gate "
    "is NOT_RUN."
)
CURRENT_FOCUS = (
    "FP-048 R002 READY after seq96 R008 contract correction; implementation has "
    "not started"
)
NEXT_ACTION = (
    "Run the private R008 five-check initial-start gate for reusable event -004; "
    "publish seq97 GOAL_STARTED only after its exact PASS receipt"
)
HANDOFF_EPIC = seq95.HANDOFF_EPIC
VERIFICATION_STATUS = (
    "INTERNAL_CONTROL_ONLY_SEQ96_R008_CONTRACT_CORRECTION; FORMAL_279_NOT_RUN; "
    "RELEASE_NOT_ELIGIBLE"
)


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _binding(path: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": path.as_posix(),
        "sha256": sha256_bytes(raw),
        "byte_length": len(raw),
    }


def _strict_json_equal(left: Any, right: Any) -> bool:
    return seq90.canonical_json_bytes({"value": left}) == seq90.canonical_json_bytes(
        {"value": right}
    )


def _parse_time(value: Any, label: str) -> datetime:
    require(isinstance(value, str), f"{label} differs")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise ContractCorrectionError(f"{label} differs") from exc
    require(parsed.tzinfo is not None and parsed.microsecond == 0, f"{label} differs")
    return parsed


def _stored_passed_gate_attempt_003_binding() -> dict[str, Any]:
    return copy.deepcopy(seq95._stored_passed_gate_attempt_003_binding())


def passed_gate_attempt_003_binding(root: Path = ROOT) -> dict[str, Any]:
    observed = seq95.passed_gate_attempt_003_binding(root)
    require(
        _strict_json_equal(observed, _stored_passed_gate_attempt_003_binding()),
        "R004 PASS_UNCONSUMED -003 binding differs",
    )
    return copy.deepcopy(observed)


def _stored_r007_preflight_attempt_004() -> dict[str, Any]:
    return copy.deepcopy(R007_PREFLIGHT_ATTEMPT_004)


def r007_preflight_attempt_004_observation(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    require(
        not os.path.lexists(root / PREFLIGHT_ATTEMPT_DIR_REL)
        and not os.path.lexists(root / PREFLIGHT_ATTEMPT_RECEIPT_REL),
        "R007 preflight -004 namespace or receipt must remain absent before R008",
    )
    return _stored_r007_preflight_attempt_004()


def _stored_r001_projected_preflight_failure() -> dict[str, Any]:
    require(
        R001_PROJECTED_PREFLIGHT_FAILURE.get("reason_code") == SUPERSESSION_REASON
        and type(R001_PROJECTED_PREFLIGHT_FAILURE.get("exit_code")) is int
        and R001_PROJECTED_PREFLIGHT_FAILURE["exit_code"] == 1
        and R001_PROJECTED_PREFLIGHT_FAILURE.get("checkpoint_write_attempted")
        is False
        and R001_PROJECTED_PREFLIGHT_FAILURE.get("review_result_present") is False
        and R001_PROJECTED_PREFLIGHT_FAILURE.get("independent_review_present")
        is False,
        "stored R001 failed preflight observation differs",
    )
    return copy.deepcopy(R001_PROJECTED_PREFLIGHT_FAILURE)


def frozen_r001_review_binding(
    root: Path = ROOT,
) -> dict[str, dict[str, Any]]:
    """Validate the exact unapproved R001 assignment without regenerating it."""

    root = seq90._safe_root(root)
    authorization_raw = seq90._stable_read(root, AUTHORIZATION_REL).raw
    require(
        _binding(AUTHORIZATION_REL, authorization_raw) == FROZEN_AUTHORIZATION_BINDING,
        "frozen seq96 R001 authorization bytes differ",
    )
    authorization = seq90.strict_json(
        authorization_raw, AUTHORIZATION_REL.as_posix()
    )
    require(
        authorization_raw == seq90.canonical_json_bytes(authorization)
        and authorization.get("document_id")
        == "WS-FP048-R002-SEQ96-97-AUTHORIZATION-20260826-001"
        and authorization.get("goal_id") == GOAL_ID,
        "frozen seq96 R001 authorization authority differs",
    )
    require(
        stat.S_IMODE((root / AUTHORIZATION_REL).lstat().st_mode) == 0o600,
        "frozen seq96 R001 authorization mode differs",
    )

    expected = FROZEN_R001_REVIEW_BINDINGS["assignment"]
    relative = Path(expected["path"])
    assignment_raw = seq90._stable_read(root, relative).raw
    require(
        _binding(relative, assignment_raw) == expected,
        "frozen seq96 R001 assignment bytes differ",
    )
    assignment = seq90.strict_json(assignment_raw, relative.as_posix())
    require(
        assignment_raw == seq90.canonical_json_bytes(assignment)
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ96-97-REVIEW-ASSIGNMENT-20260826-R001"
        and assignment.get("round_id") == R001_ROUND_ID
        and assignment.get("authorization_binding") == FROZEN_AUTHORIZATION_BINDING
        and assignment.get("required_reviewer", {}).get("task_id")
        == "/root/seq96_contract_correction_independent_review_r001",
        "frozen seq96 R001 assignment authority differs",
    )
    require(
        stat.S_IMODE((root / relative).lstat().st_mode) == 0o600,
        "frozen seq96 R001 assignment mode differs",
    )
    require(
        not os.path.lexists(root / R001_REVIEW_ROOT / "review-result.json")
        and not os.path.lexists(
            root / R001_REVIEW_ROOT / "independent-review.json"
        ),
        "seq96 R001 must remain unapproved",
    )
    return copy.deepcopy(FROZEN_R001_REVIEW_BINDINGS)


def r001_projected_preflight_failure_observation(
    root: Path = ROOT,
) -> dict[str, Any]:
    """Reconfirm R001 failed before checkpoint, review approval, or gate write."""

    root = seq90._safe_root(root)
    frozen_r001_review_binding(root)
    read = seq90._stable_read(root, CHECKPOINT_REL)
    checkpoint = seq90.strict_json(read.raw, CHECKPOINT_REL.as_posix())
    require(
        len(read.raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(read.raw) == SOURCE_CHECKPOINT_SHA256
        and read.raw == checkpoint_json_bytes(checkpoint),
        "R001 failed preflight did not preserve exact seq95 checkpoint",
    )
    require(
        stat.S_IMODE((root / CHECKPOINT_REL).lstat().st_mode) == 0o600,
        "R001 failed preflight checkpoint mode differs",
    )
    require(
        not os.path.lexists(root / PREFLIGHT_ATTEMPT_DIR_REL)
        and not os.path.lexists(root / PREFLIGHT_ATTEMPT_RECEIPT_REL),
        "R001 failed preflight unexpectedly created -004 evidence",
    )
    return _stored_r001_projected_preflight_failure()


def _stage_aware_r001_projected_preflight_failure(
    root: Path,
) -> dict[str, Any]:
    root = seq90._safe_root(root)
    frozen_r001_review_binding(root)
    read = seq90._stable_read(root, CHECKPOINT_REL)
    if (
        len(read.raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(read.raw) == SOURCE_CHECKPOINT_SHA256
    ):
        return r001_projected_preflight_failure_observation(root)
    checkpoint = seq90.strict_json(read.raw, CHECKPOINT_REL.as_posix())
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(
        isinstance(history, list)
        and len(history) in {CORRECTION_SEQUENCE, STARTED_SEQUENCE},
        "R001 failed preflight observation cannot be replayed at this stage",
    )
    return _stored_r001_projected_preflight_failure()


def r007_contract_binding(root: Path = ROOT) -> dict[str, Any]:
    observed = seq95.r007_contract_binding(root)
    require(observed == R007_CONTRACT_BINDING, "R007 contract binding differs")
    return copy.deepcopy(observed)


def r007_runner_binding(root: Path = ROOT) -> dict[str, Any]:
    observed = seq95.r007_runner_binding(root)
    require(observed == R007_RUNNER_BINDING, "R007 runner binding differs")
    return copy.deepcopy(observed)


def load_r008_contract(
    root: Path = ROOT,
) -> tuple[dict[str, Any], dict[str, Any]]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, R008_CONTRACT_REL).raw
    require(
        len(raw) == R008_BYTE_LENGTH and sha256_bytes(raw) == R008_FILE_SHA256,
        "R008 contract bytes differ",
    )
    document = seq90.strict_json(raw, R008_CONTRACT_REL.as_posix())
    require(raw == seq90.canonical_json_bytes(document), "R008 contract is noncanonical")
    observed = {
        "schema_version": document.get("schema_version"),
        "document_id": document.get("document_id"),
        "path": R008_CONTRACT_REL.as_posix(),
        "file_sha256": R008_FILE_SHA256,
        "contract_id": document.get("contract_id"),
        "contract_version": document.get("contract_version"),
        "canonical_contract_sha256": continuation.canonical_json_sha256(document),
    }
    supersedes = document.get("supersedes")
    require(
        document.get("schema_version") == "1.2"
        and document.get("document_id") == R008_DOCUMENT_ID
        and document.get("contract_id") == R008_CONTRACT_ID
        and document.get("contract_version") == R008_CONTRACT_VERSION
        and document.get("target_goal_id") == GOAL_ID
        and document.get("target_goal_content_sha256") == GOAL_SHA256
        and document.get("successor_reason_code") == R008_SUCCESSOR_REASON_CODE
        and isinstance(supersedes, dict)
        and supersedes.get("contract_id") == R007_CONTRACT_BINDING["contract_id"]
        and supersedes.get("contract_version")
        == R007_CONTRACT_BINDING["contract_version"]
        and supersedes.get("source_correction_event_id") == CORRECTION_EVENT_ID
        and supersedes.get("source_correction_event_sequence") == CORRECTION_SEQUENCE
        and observed == R008_CONTRACT_BINDING,
        "R008 contract authority differs",
    )
    return document, observed


def r008_contract_binding(root: Path = ROOT) -> dict[str, Any]:
    _document, binding = load_r008_contract(root)
    return copy.deepcopy(binding)


def r008_runner_binding(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, R008_RUNNER_REL).raw
    observed = _binding(R008_RUNNER_REL, raw)
    require(observed == R008_RUNNER_BINDING, "R008 runner binding differs")
    return observed


def frozen_seq95_r002_review_binding(
    root: Path = ROOT,
    source_event: Mapping[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    """Validate frozen R002 review bytes without rebuilding its old cohort."""

    root = seq90._safe_root(root)
    authorization_rel = Path(FROZEN_SEQ95_AUTHORIZATION_BINDING["path"])
    authorization_raw = seq90._stable_read(root, authorization_rel).raw
    require(
        _binding(authorization_rel, authorization_raw)
        == FROZEN_SEQ95_AUTHORIZATION_BINDING,
        "frozen seq95 authorization bytes differ",
    )
    authorization = seq90.strict_json(authorization_raw, authorization_rel.as_posix())
    require(
        authorization_raw == seq90.canonical_json_bytes(authorization)
        and authorization.get("document_id")
        == "WS-FP048-R002-SEQ95-96-AUTHORIZATION-20260826-001"
        and authorization.get("goal_id") == GOAL_ID,
        "frozen seq95 authorization authority differs",
    )
    observed: dict[str, dict[str, Any]] = {}
    documents: dict[str, dict[str, Any]] = {}
    for role, expected in FROZEN_SEQ95_R002_REVIEW_BINDINGS.items():
        relative = Path(expected["path"])
        raw = seq90._stable_read(root, relative).raw
        require(_binding(relative, raw) == expected, f"frozen seq95 {role} differs")
        document = seq90.strict_json(raw, relative.as_posix())
        require(raw == seq90.canonical_json_bytes(document), f"frozen {role} noncanonical")
        observed[role] = copy.deepcopy(expected)
        documents[role] = document
    assignment = documents["assignment"]
    result = documents["review_result"]
    independent = documents["independent_review"]
    require(
        assignment.get("round_id") == "R002"
        and assignment.get("authorization_binding")
        == FROZEN_SEQ95_AUTHORIZATION_BINDING
        and assignment.get("reviewed_control_inputs")
        == FROZEN_SEQ95_R002_REVIEWED_CONTROL_INPUTS
        and result.get("assignment_binding") == observed["assignment"]
        and result.get("decision") == "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and result.get("findings") == []
        and independent.get("assignment_binding") == observed["assignment"]
        and independent.get("review_result_binding") == observed["review_result"]
        and independent.get("decision") == "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and independent.get("findings") == [],
        "frozen seq95 R002 review authority differs",
    )
    if source_event is not None:
        require(
            source_event.get("authorization_binding")
            == FROZEN_SEQ95_AUTHORIZATION_BINDING
            and source_event.get("transition_control_review_binding") == observed,
            "seq95 source frozen R002 authority differs",
        )
    return copy.deepcopy(observed)


def frozen_seq95_r002_reviewed_control_inputs(
    root: Path = ROOT,
) -> list[dict[str, Any]]:
    """Return the exact 13-row cohort sealed by the frozen R002 assignment."""

    root = seq90._safe_root(root)
    expected = FROZEN_SEQ95_R002_REVIEW_BINDINGS["assignment"]
    relative = Path(expected["path"])
    raw = seq90._stable_read(root, relative).raw
    require(_binding(relative, raw) == expected, "frozen seq95 R002 assignment differs")
    assignment = seq90.strict_json(raw, relative.as_posix())
    rows = assignment.get("reviewed_control_inputs")
    require(
        raw == seq90.canonical_json_bytes(assignment)
        and _strict_json_equal(rows, FROZEN_SEQ95_R002_REVIEWED_CONTROL_INPUTS),
        "frozen seq95 R002 reviewed cohort differs",
    )
    return copy.deepcopy(rows)


def _require_frozen_seq95_checkpoint(
    raw: bytes,
    checkpoint: Mapping[str, Any],
) -> None:
    """Validate published seq95 CAS without current-file review replay."""

    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256
        and raw == checkpoint_json_bytes(checkpoint)
        and isinstance(history, list)
        and len(history) == SOURCE_SEQUENCE
        and all(
            isinstance(item, dict)
            and type(item.get("sequence")) is int
            and item.get("sequence") == sequence
            and item.get("event_sha256") == continuation.event_sha256(item)
            and (
                sequence == 1
                or item.get("previous_event_sha256")
                == history[sequence - 2].get("event_sha256")
            )
            for sequence, item in enumerate(history, start=1)
        ),
        "frozen seq95 checkpoint differs",
    )
    event = history[-1]
    require(
        event.get("event_id") == SOURCE_EVENT_ID
        and event.get("event_sha256") == SOURCE_EVENT_SHA256
        and event.get("event_type") == seq95.CORRECTION_EVENT_TYPE
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("authorization_binding") == FROZEN_SEQ95_AUTHORIZATION_BINDING
        and event.get("transition_control_review_binding")
        == FROZEN_SEQ95_R002_REVIEW_BINDINGS
        and event.get("source_checkpoint_binding") == SOURCE_EVENT_SOURCE_BINDING
        and event.get("contract_supersession", {}).get("replacement_contract_binding")
        == R007_CONTRACT_BINDING
        and event.get("start_gate_runner_binding") == R007_RUNNER_BINDING
        and state.get("transition_history_anchor_sha256") == SOURCE_EVENT_SHA256
        and state.get("goal_status") == "READY"
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values()
        and checkpoint.get("current_work", {}).get("status") == "READY"
        and checkpoint.get("approved_state", {}).get("formal_test_not_run_count") == 279
        and checkpoint.get("verification_boundary", {}).get("release_eligible") is False,
        "frozen seq95 authority differs",
    )


def require_frozen_seq95_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> None:
    seq90._safe_root(root)
    _require_frozen_seq95_checkpoint(checkpoint_json_bytes(checkpoint), checkpoint)


def canonical_frozen_seq95_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    require_frozen_seq95_checkpoint(root, checkpoint)
    return checkpoint_json_bytes(checkpoint)


def require_exact_seq95_source(
    raw: bytes,
    source: Mapping[str, Any],
    root: Path = ROOT,
) -> None:
    root = seq90._safe_root(root)
    _require_frozen_seq95_checkpoint(raw, source)
    event = source["goal_execution"]["transition_history"][-1]
    frozen_seq95_r002_review_binding(root, event)
    require(
        r007_contract_binding(root) == R007_CONTRACT_BINDING
        and r007_runner_binding(root) == R007_RUNNER_BINDING,
        "seq95 source R007 predecessor authority differs",
    )


def _source_checkpoint_binding(
    r007_preflight: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "path": CHECKPOINT_REL.as_posix(),
        "sha256": SOURCE_CHECKPOINT_SHA256,
        "byte_length": SOURCE_CHECKPOINT_BYTE_LENGTH,
        "sequence": SOURCE_SEQUENCE,
        "tail_event_id": SOURCE_EVENT_ID,
        "tail_event_sha256": SOURCE_EVENT_SHA256,
        "passed_gate_attempt_003": _stored_passed_gate_attempt_003_binding(),
        "preflight_attempt_004": copy.deepcopy(
            HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        ),
        "r006_preflight_attempt_004": copy.deepcopy(R006_PREFLIGHT_ATTEMPT_004),
        "r007_preflight_attempt_004": copy.deepcopy(
            dict(r007_preflight)
            if r007_preflight is not None
            else R007_PREFLIGHT_ATTEMPT_004
        ),
    }


def build_authorization(
    root: Path,
    source: Mapping[str, Any],
    *,
    require_live_preflight_absence: bool = True,
) -> bytes:
    root = seq90._safe_root(root)
    require_exact_seq95_source(checkpoint_json_bytes(source), source, root)
    preflight = (
        r007_preflight_attempt_004_observation(root)
        if require_live_preflight_absence
        else _stored_r007_preflight_attempt_004()
    )
    value = {
        "authorization_scope": copy.deepcopy(AUTHORIZATION_SCOPE),
        "authorization_status": (
            "AUTHORIZED_FOR_CONDITIONAL_SEQ96_97_CONTRACT_CORRECTION_CONTINUATION"
        ),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "contract_correction_event_id": CORRECTION_EVENT_ID,
        "correction_reason": copy.deepcopy(CORRECTION_REASON),
        "document_id": "WS-FP048-R002-SEQ96-97-AUTHORIZATION-20260826-001",
        "evidence_type": "USER_CONTINUATION_AUTHORIZATION",
        "frozen_seq95_r002_authorization_binding": copy.deepcopy(
            FROZEN_SEQ95_AUTHORIZATION_BINDING
        ),
        "frozen_seq95_r002_review_binding": frozen_seq95_r002_review_binding(
            root, source["goal_execution"]["transition_history"][-1]
        ),
        "frozen_seq95_r002_reviewed_control_inputs": (
            frozen_seq95_r002_reviewed_control_inputs(root)
        ),
        "goal_id": GOAL_ID,
        "historical_r005_preflight_attempt_004": copy.deepcopy(
            HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        ),
        "passed_gate_attempt_003_binding": passed_gate_attempt_003_binding(root),
        "previous_contract_binding": r007_contract_binding(root),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "r006_preflight_attempt_004": copy.deepcopy(R006_PREFLIGHT_ATTEMPT_004),
        "r007_preflight_attempt_004": preflight,
        "replacement_contract_binding": r008_contract_binding(root),
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(preflight),
        "target_started_event_id": STARTED_EVENT_ID,
    }
    require(set(value) == AUTHORIZATION_FIELDS, "seq96 authorization field set differs")
    return seq90.canonical_json_bytes(value)


def candidate_authorization_binding(
    root: Path,
    source: Mapping[str, Any],
    *,
    require_live_preflight_absence: bool = True,
) -> dict[str, Any]:
    return _binding(
        AUTHORIZATION_REL,
        build_authorization(
            root,
            source,
            require_live_preflight_absence=require_live_preflight_absence,
        ),
    )


def authorization_binding(
    root: Path,
    source: Mapping[str, Any],
    *,
    require_live_preflight_absence: bool = True,
) -> dict[str, Any]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, AUTHORIZATION_REL).raw
    require(
        raw
        == build_authorization(
            root,
            source,
            require_live_preflight_absence=require_live_preflight_absence,
        ),
        "seq96 authorization differs",
    )
    return _binding(AUTHORIZATION_REL, raw)


def _reviewed_control_bindings(root: Path) -> list[dict[str, Any]]:
    observed = [
        _binding(path, seq90._stable_read(root, path).raw)
        for path in REVIEWED_CONTROL_PATHS
    ]
    by_path = {row["path"]: row for row in observed}
    require(
        set(FIXED_REVIEWED_CONTROL_BINDINGS)
        == set(REVIEWED_CONTROL_PATHS) - {SCRIPT_REL, TEST_REL}
        and all(
            by_path.get(path.as_posix()) == expected
            for path, expected in FIXED_REVIEWED_CONTROL_BINDINGS.items()
        ),
        "fixed seq96 review cohort binding differs",
    )
    return observed


def _stored_noncredit_fp023_product_successor_bindings() -> dict[str, Any]:
    return {
        "authority_label": NONCREDIT_FP023_PRODUCT_AUTHORITY_LABEL,
        "bindings": [
            copy.deepcopy(FIXED_REVIEWED_CONTROL_BINDINGS[path])
            for path in NONCREDIT_FP023_PRODUCT_PATHS
        ],
        "credit_boundary": copy.deepcopy(NONCREDIT_FP023_CREDIT_BOUNDARY),
    }


def noncredit_fp023_product_successor_bindings(
    root: Path = ROOT,
) -> dict[str, Any]:
    """Bind current FP023 bytes as repository context without product credit."""

    root = seq90._safe_root(root)
    expected = _stored_noncredit_fp023_product_successor_bindings()
    observed = [
        _binding(path, seq90._stable_read(root, path).raw)
        for path in NONCREDIT_FP023_PRODUCT_PATHS
    ]
    require(
        len(NONCREDIT_FP023_PRODUCT_PATHS) == len(set(NONCREDIT_FP023_PRODUCT_PATHS)) == 15
        and observed == expected["bindings"]
        and expected["authority_label"] == "NONCREDIT_REPOSITORY_CONTEXT_ONLY"
        and all(
            type(value) is int and value == 0
            for value in expected["credit_boundary"].values()
        ),
        "noncredit FP023 product successor binding differs",
    )
    return copy.deepcopy(expected)


def build_review_assignment(
    root: Path,
    source: Mapping[str, Any],
    *,
    require_live_preflight_absence: bool = True,
) -> bytes:
    root = seq90._safe_root(root)
    require_exact_seq95_source(checkpoint_json_bytes(source), source, root)
    preflight = (
        r007_preflight_attempt_004_observation(root)
        if require_live_preflight_absence
        else _stored_r007_preflight_attempt_004()
    )
    superseded_review = frozen_r001_review_binding(root)
    failed_preflight = (
        _stage_aware_r001_projected_preflight_failure(root)
        if require_live_preflight_absence
        else _stored_r001_projected_preflight_failure()
    )
    value = {
        "assigner": {"id": "codex-root", "task_id": "/root"},
        "authorization_binding": candidate_authorization_binding(
            root,
            source,
            require_live_preflight_absence=require_live_preflight_absence,
        ),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "correction_reason": copy.deepcopy(CORRECTION_REASON),
        "document_id": "WS-FP048-R002-SEQ96-97-REVIEW-ASSIGNMENT-20260826-R002",
        "executor": {
            "id": "codex-fp048-r002-seq96-contract-correction-executor-20260826",
            "task_id": "/root/seq94_contract_correction_impl",
        },
        "frozen_seq95_r002_authorization_binding": copy.deepcopy(
            FROZEN_SEQ95_AUTHORIZATION_BINDING
        ),
        "frozen_seq95_r002_review_binding": frozen_seq95_r002_review_binding(root),
        "frozen_seq95_r002_reviewed_control_inputs": (
            frozen_seq95_r002_reviewed_control_inputs(root)
        ),
        "goal_id": GOAL_ID,
        "historical_r005_preflight_attempt_004": copy.deepcopy(
            HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        ),
        "noncredit_fp023_product_successor_bindings": (
            noncredit_fp023_product_successor_bindings(root)
        ),
        "passed_gate_attempt_003_binding": passed_gate_attempt_003_binding(root),
        "previous_contract_binding": r007_contract_binding(root),
        "previous_runner_binding": r007_runner_binding(root),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "r001_projected_preflight_failure": failed_preflight,
        "r006_preflight_attempt_004": copy.deepcopy(R006_PREFLIGHT_ATTEMPT_004),
        "r007_preflight_attempt_004": preflight,
        "replacement_contract_binding": r008_contract_binding(root),
        "replacement_runner_binding": r008_runner_binding(root),
        "required_reviewer": copy.deepcopy(REVIEWER),
        "review_scope": copy.deepcopy(REVIEW_SCOPE),
        "reviewed_control_inputs": _reviewed_control_bindings(root),
        "round_id": ROUND_ID,
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(preflight),
        "superseded_review_binding": superseded_review,
        "supersedes_round_id": SUPERSEDES_ROUND_ID,
        "supersession_reason": SUPERSESSION_REASON,
    }
    require(set(value) == REVIEW_ASSIGNMENT_FIELDS, "seq96 assignment field set differs")
    return seq90.canonical_json_bytes(value)


def _load_physical_review(
    root: Path,
    source: Mapping[str, Any],
    *,
    require_live_preflight_absence: bool = True,
) -> tuple[dict[str, dict[str, Any]], datetime, datetime]:
    root = seq90._safe_root(root)
    expected_authorization = authorization_binding(
        root,
        source,
        require_live_preflight_absence=require_live_preflight_absence,
    )
    assignment_raw = seq90._stable_read(root, REVIEW_ASSIGNMENT_REL).raw
    result_raw = seq90._stable_read(root, REVIEW_RESULT_REL).raw
    independent_raw = seq90._stable_read(root, INDEPENDENT_REVIEW_REL).raw
    require(
        assignment_raw
        == build_review_assignment(
            root,
            source,
            require_live_preflight_absence=require_live_preflight_absence,
        ),
        "seq96 review assignment differs",
    )
    assignment = seq90.strict_json(assignment_raw, REVIEW_ASSIGNMENT_REL.as_posix())
    result = seq90.strict_json(result_raw, REVIEW_RESULT_REL.as_posix())
    independent = seq90.strict_json(independent_raw, INDEPENDENT_REVIEW_REL.as_posix())
    require(
        set(result) == REVIEW_RESULT_FIELDS
        and set(independent) == INDEPENDENT_REVIEW_FIELDS
        and result_raw == seq90.canonical_json_bytes(result)
        and independent_raw == seq90.canonical_json_bytes(independent),
        "seq96 review documents are noncanonical",
    )
    assignment_binding_value = _binding(REVIEW_ASSIGNMENT_REL, assignment_raw)
    result_binding_value = _binding(REVIEW_RESULT_REL, result_raw)
    preflight = (
        r007_preflight_attempt_004_observation(root)
        if require_live_preflight_absence
        else _stored_r007_preflight_attempt_004()
    )
    passed_gate = passed_gate_attempt_003_binding(root)
    frozen_reviewed_inputs = frozen_seq95_r002_reviewed_control_inputs(root)
    superseded_review = frozen_r001_review_binding(root)
    failed_preflight = (
        _stage_aware_r001_projected_preflight_failure(root)
        if require_live_preflight_absence
        else _stored_r001_projected_preflight_failure()
    )
    product_successor = noncredit_fp023_product_successor_bindings(root)
    common = {
        "claim_boundary": CLAIM_BOUNDARY,
        "correction_reason": CORRECTION_REASON,
        "frozen_seq95_r002_authorization_binding": (
            FROZEN_SEQ95_AUTHORIZATION_BINDING
        ),
        "frozen_seq95_r002_review_binding": FROZEN_SEQ95_R002_REVIEW_BINDINGS,
        "frozen_seq95_r002_reviewed_control_inputs": frozen_reviewed_inputs,
        "goal_id": GOAL_ID,
        "historical_r005_preflight_attempt_004": (
            HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        ),
        "noncredit_fp023_product_successor_bindings": product_successor,
        "passed_gate_attempt_003_binding": passed_gate,
        "previous_contract_binding": R007_CONTRACT_BINDING,
        "projected_transition": PROJECTED_TRANSITION,
        "r001_projected_preflight_failure": failed_preflight,
        "r006_preflight_attempt_004": R006_PREFLIGHT_ATTEMPT_004,
        "r007_preflight_attempt_004": preflight,
        "replacement_contract_binding": R008_CONTRACT_BINDING,
        "reviewer": REVIEWER,
        "round_id": ROUND_ID,
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(preflight),
        "superseded_review_binding": superseded_review,
        "supersedes_round_id": SUPERSEDES_ROUND_ID,
        "supersession_reason": SUPERSESSION_REASON,
    }
    require(
        assignment.get("authorization_binding") == expected_authorization
        and assignment.get("required_reviewer") == REVIEWER
        and result.get("document_id")
        == "WS-FP048-R002-SEQ96-97-REVIEW-RESULT-20260826-R002"
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ96-97-INDEPENDENT-REVIEW-20260826-R002"
        and result.get("assignment_binding") == assignment_binding_value
        and independent.get("assignment_binding") == assignment_binding_value
        and independent.get("review_result_binding") == result_binding_value
        and result.get("decision") == "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and independent.get("decision") == "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and result.get("findings") == independent.get("findings") == []
        and result.get("external_independence_claimed") is False
        and independent.get("external_independence_claimed") is False
        and independent.get("independent_checks") == INDEPENDENT_CHECKS
        and all(_strict_json_equal(result.get(key), value) for key, value in common.items())
        and all(
            _strict_json_equal(independent.get(key), value)
            for key, value in common.items()
        ),
        "seq96 review approval differs",
    )
    result_at = _parse_time(result.get("reviewed_at"), "seq96 result reviewed_at")
    independent_at = _parse_time(
        independent.get("reviewed_at"), "seq96 independent reviewed_at"
    )
    source_at = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq95 occurred_at",
    )
    require(source_at < result_at < independent_at, "seq96 review time order differs")
    return (
        {
            "assignment": assignment_binding_value,
            "review_result": result_binding_value,
            "independent_review": _binding(INDEPENDENT_REVIEW_REL, independent_raw),
        },
        result_at,
        independent_at,
    )


def _published_authorization_document() -> dict[str, Any]:
    return {
        "authorization_scope": copy.deepcopy(AUTHORIZATION_SCOPE),
        "authorization_status": (
            "AUTHORIZED_FOR_CONDITIONAL_SEQ96_97_CONTRACT_CORRECTION_CONTINUATION"
        ),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "contract_correction_event_id": CORRECTION_EVENT_ID,
        "correction_reason": copy.deepcopy(CORRECTION_REASON),
        "document_id": "WS-FP048-R002-SEQ96-97-AUTHORIZATION-20260826-001",
        "evidence_type": "USER_CONTINUATION_AUTHORIZATION",
        "frozen_seq95_r002_authorization_binding": copy.deepcopy(
            FROZEN_SEQ95_AUTHORIZATION_BINDING
        ),
        "frozen_seq95_r002_review_binding": copy.deepcopy(
            FROZEN_SEQ95_R002_REVIEW_BINDINGS
        ),
        "frozen_seq95_r002_reviewed_control_inputs": copy.deepcopy(
            FROZEN_SEQ95_R002_REVIEWED_CONTROL_INPUTS
        ),
        "goal_id": GOAL_ID,
        "historical_r005_preflight_attempt_004": copy.deepcopy(
            HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        ),
        "passed_gate_attempt_003_binding": _stored_passed_gate_attempt_003_binding(),
        "previous_contract_binding": copy.deepcopy(R007_CONTRACT_BINDING),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "r006_preflight_attempt_004": copy.deepcopy(R006_PREFLIGHT_ATTEMPT_004),
        "r007_preflight_attempt_004": copy.deepcopy(R007_PREFLIGHT_ATTEMPT_004),
        "replacement_contract_binding": copy.deepcopy(R008_CONTRACT_BINDING),
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "target_started_event_id": STARTED_EVENT_ID,
    }


def _review_common_authority() -> dict[str, Any]:
    authorization = _published_authorization_document()
    return {
        "claim_boundary": authorization["claim_boundary"],
        "correction_reason": authorization["correction_reason"],
        "frozen_seq95_r002_authorization_binding": authorization[
            "frozen_seq95_r002_authorization_binding"
        ],
        "frozen_seq95_r002_review_binding": authorization[
            "frozen_seq95_r002_review_binding"
        ],
        "frozen_seq95_r002_reviewed_control_inputs": authorization[
            "frozen_seq95_r002_reviewed_control_inputs"
        ],
        "goal_id": GOAL_ID,
        "historical_r005_preflight_attempt_004": authorization[
            "historical_r005_preflight_attempt_004"
        ],
        "noncredit_fp023_product_successor_bindings": (
            _stored_noncredit_fp023_product_successor_bindings()
        ),
        "passed_gate_attempt_003_binding": authorization[
            "passed_gate_attempt_003_binding"
        ],
        "previous_contract_binding": authorization["previous_contract_binding"],
        "projected_transition": authorization["projected_transition"],
        "r001_projected_preflight_failure": (
            _stored_r001_projected_preflight_failure()
        ),
        "r006_preflight_attempt_004": authorization["r006_preflight_attempt_004"],
        "r007_preflight_attempt_004": authorization["r007_preflight_attempt_004"],
        "replacement_contract_binding": authorization[
            "replacement_contract_binding"
        ],
        "reviewer": copy.deepcopy(REVIEWER),
        "round_id": ROUND_ID,
        "schema_version": "1.0",
        "source_checkpoint_binding": authorization["source_checkpoint_binding"],
        "superseded_review_binding": copy.deepcopy(FROZEN_R001_REVIEW_BINDINGS),
        "supersedes_round_id": SUPERSEDES_ROUND_ID,
        "supersession_reason": SUPERSESSION_REASON,
    }


def _require_published_reviewed_control_inputs(value: Any) -> None:
    expected_paths = [path.as_posix() for path in REVIEWED_CONTROL_PATHS]
    require(
        isinstance(value, list)
        and len(value) == len(expected_paths)
        and all(isinstance(row, dict) for row in value)
        and [row.get("path") for row in value] == expected_paths
        and all(
            set(row) == {"path", "sha256", "byte_length"}
            and isinstance(row.get("sha256"), str)
            and seq90.SHA256_RE.fullmatch(row["sha256"]) is not None
            and type(row.get("byte_length")) is int
            and row["byte_length"] > 0
            for row in value
        )
        and set(FIXED_REVIEWED_CONTROL_BINDINGS)
        == set(REVIEWED_CONTROL_PATHS) - {SCRIPT_REL, TEST_REL}
        and all(
            value[expected_paths.index(path.as_posix())] == expected
            for path, expected in FIXED_REVIEWED_CONTROL_BINDINGS.items()
        ),
        "published seq96 reviewed control cohort differs",
    )


def _published_review(
    root: Path,
    event: Mapping[str, Any],
    *,
    require_live_preflight_absence: bool = True,
) -> tuple[dict[str, dict[str, Any]], datetime, datetime]:
    """Validate published review bytes without rebuilding its frozen cohort."""

    root = seq90._safe_root(root)
    authorization_expected = event.get("authorization_binding")
    require(isinstance(authorization_expected, dict), "seq96 authorization binding differs")
    authorization_raw = seq90._stable_read(root, AUTHORIZATION_REL).raw
    authorization = seq90.strict_json(authorization_raw, AUTHORIZATION_REL.as_posix())
    require(
        _binding(AUTHORIZATION_REL, authorization_raw) == authorization_expected
        and authorization_raw == seq90.canonical_json_bytes(authorization)
        and set(authorization) == AUTHORIZATION_FIELDS
        and _strict_json_equal(authorization, _published_authorization_document()),
        "published seq96 authorization authority differs",
    )
    review_expected = event.get("transition_control_review_binding")
    require(
        isinstance(review_expected, dict)
        and set(review_expected) == {"assignment", "review_result", "independent_review"},
        "published seq96 review binding differs",
    )
    documents: dict[str, dict[str, Any]] = {}
    for role, relative in {
        "assignment": REVIEW_ASSIGNMENT_REL,
        "review_result": REVIEW_RESULT_REL,
        "independent_review": INDEPENDENT_REVIEW_REL,
    }.items():
        raw = seq90._stable_read(root, relative).raw
        require(
            _binding(relative, raw) == review_expected[role],
            f"published seq96 {role} bytes differ",
        )
        document = seq90.strict_json(raw, relative.as_posix())
        require(raw == seq90.canonical_json_bytes(document), f"published {role} noncanonical")
        documents[role] = document
    assignment = documents["assignment"]
    result = documents["review_result"]
    independent = documents["independent_review"]
    reviewed_inputs = assignment.get("reviewed_control_inputs")
    _require_published_reviewed_control_inputs(reviewed_inputs)
    superseded_review = frozen_r001_review_binding(root)
    failed_preflight = (
        _stage_aware_r001_projected_preflight_failure(root)
        if require_live_preflight_absence
        else _stored_r001_projected_preflight_failure()
    )
    expected_assignment = {
        "assigner": {"id": "codex-root", "task_id": "/root"},
        "authorization_binding": copy.deepcopy(authorization_expected),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "correction_reason": copy.deepcopy(CORRECTION_REASON),
        "document_id": "WS-FP048-R002-SEQ96-97-REVIEW-ASSIGNMENT-20260826-R002",
        "executor": {
            "id": "codex-fp048-r002-seq96-contract-correction-executor-20260826",
            "task_id": "/root/seq94_contract_correction_impl",
        },
        "frozen_seq95_r002_authorization_binding": copy.deepcopy(
            FROZEN_SEQ95_AUTHORIZATION_BINDING
        ),
        "frozen_seq95_r002_review_binding": copy.deepcopy(
            FROZEN_SEQ95_R002_REVIEW_BINDINGS
        ),
        "frozen_seq95_r002_reviewed_control_inputs": copy.deepcopy(
            FROZEN_SEQ95_R002_REVIEWED_CONTROL_INPUTS
        ),
        "goal_id": GOAL_ID,
        "historical_r005_preflight_attempt_004": copy.deepcopy(
            HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        ),
        "noncredit_fp023_product_successor_bindings": (
            _stored_noncredit_fp023_product_successor_bindings()
        ),
        "passed_gate_attempt_003_binding": _stored_passed_gate_attempt_003_binding(),
        "previous_contract_binding": copy.deepcopy(R007_CONTRACT_BINDING),
        "previous_runner_binding": copy.deepcopy(R007_RUNNER_BINDING),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "r001_projected_preflight_failure": failed_preflight,
        "r006_preflight_attempt_004": copy.deepcopy(R006_PREFLIGHT_ATTEMPT_004),
        "r007_preflight_attempt_004": copy.deepcopy(R007_PREFLIGHT_ATTEMPT_004),
        "replacement_contract_binding": copy.deepcopy(R008_CONTRACT_BINDING),
        "replacement_runner_binding": copy.deepcopy(R008_RUNNER_BINDING),
        "required_reviewer": copy.deepcopy(REVIEWER),
        "review_scope": copy.deepcopy(REVIEW_SCOPE),
        "reviewed_control_inputs": copy.deepcopy(reviewed_inputs),
        "round_id": ROUND_ID,
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "superseded_review_binding": superseded_review,
        "supersedes_round_id": SUPERSEDES_ROUND_ID,
        "supersession_reason": SUPERSESSION_REASON,
    }
    common = _review_common_authority()
    require(
        set(assignment) == REVIEW_ASSIGNMENT_FIELDS
        and _strict_json_equal(assignment, expected_assignment)
        and set(result) == REVIEW_RESULT_FIELDS
        and set(independent) == INDEPENDENT_REVIEW_FIELDS
        and result.get("document_id")
        == "WS-FP048-R002-SEQ96-97-REVIEW-RESULT-20260826-R002"
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ96-97-INDEPENDENT-REVIEW-20260826-R002"
        and result.get("assignment_binding") == review_expected["assignment"]
        and independent.get("assignment_binding") == review_expected["assignment"]
        and independent.get("review_result_binding") == review_expected["review_result"]
        and result.get("decision") == "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and independent.get("decision") == "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and result.get("findings") == independent.get("findings") == []
        and result.get("external_independence_claimed") is False
        and independent.get("external_independence_claimed") is False
        and independent.get("independent_checks") == INDEPENDENT_CHECKS
        and all(_strict_json_equal(result.get(key), value) for key, value in common.items())
        and all(
            _strict_json_equal(independent.get(key), value)
            for key, value in common.items()
        ),
        "published seq96 review authority differs",
    )
    result_at = _parse_time(result.get("reviewed_at"), "seq96 result reviewed_at")
    independent_at = _parse_time(
        independent.get("reviewed_at"), "seq96 independent reviewed_at"
    )
    require(result_at < independent_at, "published seq96 review chronology differs")
    return copy.deepcopy(review_expected), result_at, independent_at


def _event_time(
    source: Mapping[str, Any],
    supplied: str | None,
    independent_reviewed_at: datetime,
) -> str:
    candidate = (
        _parse_time(supplied, "seq96 occurred_at")
        if supplied is not None
        else datetime.now().astimezone().replace(microsecond=0)
    )
    source_at = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq95 occurred_at",
    )
    return max(
        candidate,
        source_at + timedelta(seconds=1),
        independent_reviewed_at + timedelta(seconds=1),
    ).isoformat()


def _final_managed_paths(
    source: Mapping[str, Any], visible: Sequence[str]
) -> tuple[str, ...]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    paths.update(
        path for path in visible if not path.startswith("docs/control/execution/goal-gates/")
    )
    paths.discard(CHECKPOINT_REL.as_posix())
    paths.update(path.as_posix() for path in REVIEWED_CONTROL_PATHS)
    paths.add(AUTHORIZATION_REL.as_posix())
    paths.update(path.as_posix() for path in REVIEW_PATHS)
    return tuple(sorted(paths))


def project_seq96(
    root: Path,
    source: Mapping[str, Any],
    *,
    managed_paths: Sequence[str],
    path_set_sha256: str,
    content_set_sha256: str,
    occurred_at: str,
    authorization_binding_value: Mapping[str, Any],
    review_binding: Mapping[str, Any],
    r007_preflight_binding: Mapping[str, Any],
    replacement_contract_binding: Mapping[str, Any],
    replacement_runner_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    root = seq90._safe_root(root)
    require_exact_seq95_source(checkpoint_json_bytes(source), source, root)
    source_event = source["goal_execution"]["transition_history"][-1]
    paths = sorted(set(managed_paths))
    require(
        paths == list(managed_paths)
        and all(
            isinstance(path, str)
            and path
            and not Path(path).is_absolute()
            and ".." not in Path(path).parts
            for path in paths
        )
        and path_set_sha256
        == hashlib.sha256(("\n".join(paths) + "\n").encode("utf-8")).hexdigest()
        and isinstance(content_set_sha256, str)
        and seq90.SHA256_RE.fullmatch(content_set_sha256) is not None,
        "seq96 managed paths differ",
    )
    _require_embedded_binding(
        dict(authorization_binding_value),
        AUTHORIZATION_REL,
        "seq96 authorization binding",
    )
    require(
        isinstance(review_binding, Mapping)
        and set(review_binding)
        == {"assignment", "review_result", "independent_review"},
        "seq96 review binding differs",
    )
    for key, path in (
        ("assignment", REVIEW_ASSIGNMENT_REL),
        ("review_result", REVIEW_RESULT_REL),
        ("independent_review", INDEPENDENT_REVIEW_REL),
    ):
        _require_embedded_binding(
            review_binding.get(key), path, f"seq96 {key} binding"
        )
    require(
        _strict_json_equal(
            dict(r007_preflight_binding), _stored_r007_preflight_attempt_004()
        ),
        "seq96 R007 preflight -004 observation differs",
    )
    require(
        _strict_json_equal(dict(replacement_contract_binding), r008_contract_binding(root))
        and _strict_json_equal(dict(replacement_runner_binding), r008_runner_binding(root)),
        "seq96 R008 successor binding differs",
    )
    require(
        source_event.get("contract_supersession", {}).get("replacement_contract_binding")
        == R007_CONTRACT_BINDING
        and source_event.get("start_gate_runner_binding") == R007_RUNNER_BINDING,
        "seq96 R007 predecessor authority differs",
    )
    occurred = _parse_time(occurred_at, "seq96 occurred_at")
    projected = copy.deepcopy(source)
    current = projected["current_work"]
    current.update(
        {
            "status": "READY",
            "current_focus": CURRENT_FOCUS,
            "next_action": NEXT_ACTION,
            "release_completion_claimed": False,
        }
    )
    snapshot = projected["working_tree_snapshot"]
    snapshot.update(
        {
            "scope": SCOPE,
            "managed_changed_paths": paths,
            "managed_changed_path_count": len(paths),
            "path_set_sha256": path_set_sha256,
            "content_set_sha256": content_set_sha256,
        }
    )
    handoff = projected["session_handoff"]
    require(handoff.get("branch") == LOGICAL_BRANCH, "logical branch label differs")
    handoff["changed_files"] = copy.deepcopy(paths)
    mirror = handoff["source_commit_or_snapshot"]
    mirror.update(
        {
            "file_count": len(paths),
            "path_set_sha256": path_set_sha256,
            "content_set_sha256": content_set_sha256,
        }
    )
    handoff.update(
        {
            "current_epic": HANDOFF_EPIC,
            "last_updated_by_work_item": WORK_ITEM_ID,
            "last_verification_status": VERIFICATION_STATUS,
            "next_single_action": NEXT_ACTION,
        }
    )
    source_binding = _source_checkpoint_binding(r007_preflight_binding)
    event: dict[str, Any] = {
        "sequence": CORRECTION_SEQUENCE,
        "event_id": CORRECTION_EVENT_ID,
        "event_type": CORRECTION_EVENT_TYPE,
        "occurred_on": occurred.date().isoformat(),
        "occurred_at": occurred_at,
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": GOAL_ID,
        "focus_goal_content_sha256": GOAL_SHA256,
        "subject_goal_id": GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": copy.deepcopy(source_event["runtime_after"]),
        "blockers_after": copy.deepcopy(source_event["blockers_after"]),
        "blocker_resolution_ids_after": copy.deepcopy(
            source_event["blocker_resolution_ids_after"]
        ),
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [
            "FP048-R002_EXACT_PUBLISHED_SEQ95_SOURCE",
            "FP048-R002_SEQ95_R002_FROZEN_REVIEW_AUTHORITY",
            "FP048-R002_R007_ROOT_REGRESSION_PREVIEW_NONAUTHORITY",
            "FP048-R002_R008_STAGE_AWARE_GATE_CONTRACT",
            "FP048-R002_SEQ96_97_TRANSITION_CONTROL_REVIEW",
        ],
        "source_checkpoint_binding": source_binding,
        "source_ready_event_binding": copy.deepcopy(
            source_event["source_ready_event_binding"]
        ),
        "authorization_binding": copy.deepcopy(dict(authorization_binding_value)),
        "contract_supersession": {
            "previous_contract_binding": copy.deepcopy(R007_CONTRACT_BINDING),
            "reason_code": R008_SUCCESSOR_REASON_CODE,
            "replacement_contract_binding": copy.deepcopy(
                dict(replacement_contract_binding)
            ),
        },
        "start_gate_runner_binding": copy.deepcopy(dict(replacement_runner_binding)),
        "transition_control_review_binding": copy.deepcopy(dict(review_binding)),
        "repository_context_reanchor": {
            "before": {
                **copy.deepcopy(source_binding),
                "current_work": copy.deepcopy(source["current_work"]),
                "working_tree_snapshot": copy.deepcopy(
                    source["working_tree_snapshot"]
                ),
                "session_handoff": copy.deepcopy(source["session_handoff"]),
            },
            "after": {
                "base_commit": source["working_tree_snapshot"]["base_head"],
                "branch": LOGICAL_BRANCH,
                "logical_branch": LOGICAL_BRANCH,
                "logical_branch_semantics": LOGICAL_BRANCH_SEMANTICS,
                "physical_git_branch": PHYSICAL_GIT_BRANCH,
                "branch_mismatch_reason_code": BRANCH_MISMATCH_REASON_CODE,
                "current_head": source["session_handoff"]["source_commit_or_snapshot"][
                    "current_head"
                ],
                "managed_changed_path_count": len(paths),
                "path_set_sha256": path_set_sha256,
                "content_set_sha256": content_set_sha256,
            },
        },
        "noncredit_successor_edges": copy.deepcopy(
            source_event["noncredit_successor_edges"]
        ),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "unchanged_control_projection": (
            seq95.seq94.seq93.seq92.correction._unchanged_projection(projected)
        ),
        "canonical_binding_snapshot_after": copy.deepcopy(
            source_event["canonical_binding_snapshot_after"]
        ),
        "correction_reason": copy.deepcopy(CORRECTION_REASON),
        "previous_event_sha256": SOURCE_EVENT_SHA256,
    }
    event["event_sha256"] = continuation.event_sha256(event)
    require(set(event) == EVENT_FIELDS, "seq96 correction event field set differs")
    state = projected["goal_execution"]
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = occurred_at
    require(
        state["goal_status"] == "READY"
        and state["status_by_goal"][GOAL_ID] == "READY"
        and current["status"] == "READY"
        and "IN_PROGRESS" not in state["status_by_goal"].values(),
        "seq96 READY state differs",
    )
    for field in (
        "approved_state",
        "authority_boundary",
        "canonical_bindings",
        "verification_boundary",
    ):
        require(_strict_json_equal(projected[field], source[field]), f"seq96 changed {field}")
    require(
        all(
            type(value) is int and value == 0
            for key, value in CLAIM_BOUNDARY.items()
            if key.endswith("_credit_delta") or key == "goal_status_change_count"
        )
        and CLAIM_BOUNDARY["implementation_start_authorized"] is False,
        "seq96 credit boundary differs",
    )
    return projected, event


def _repository_before(event: Mapping[str, Any]) -> dict[str, Any]:
    context = event.get("repository_context_reanchor")
    before = context.get("before") if isinstance(context, dict) else None
    after = context.get("after") if isinstance(context, dict) else None
    source_binding = _source_checkpoint_binding()
    require(
        isinstance(context, dict)
        and set(context) == {"before", "after"}
        and isinstance(before, dict)
        and set(before)
        == set(source_binding) | {"current_work", "working_tree_snapshot", "session_handoff"}
        and {key: before.get(key) for key in source_binding} == source_binding
        and isinstance(after, dict)
        and after.get("logical_branch") == LOGICAL_BRANCH
        and after.get("logical_branch_semantics") == LOGICAL_BRANCH_SEMANTICS
        and after.get("physical_git_branch") == PHYSICAL_GIT_BRANCH,
        "seq96 repository context differs",
    )
    return before


def _restored_seq95_checkpoint(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "seq95 inverse source is not exact seq96",
    )
    before = _repository_before(history[-1])
    restored = copy.deepcopy(checkpoint)
    restored_state = restored["goal_execution"]
    source_event = restored_state["transition_history"][SOURCE_SEQUENCE - 1]
    restored_state["transition_history"] = restored_state["transition_history"][:SOURCE_SEQUENCE]
    restored_state["transition_history_anchor_sha256"] = source_event["event_sha256"]
    restored_state["validation_cutoff_at"] = source_event["occurred_at"]
    restored["current_work"] = copy.deepcopy(before["current_work"])
    restored["working_tree_snapshot"] = copy.deepcopy(before["working_tree_snapshot"])
    restored["session_handoff"] = copy.deepcopy(before["session_handoff"])
    return restored


def _require_embedded_binding(
    value: Any,
    path: Path,
    label: str,
) -> None:
    require(
        isinstance(value, dict)
        and set(value) == {"path", "sha256", "byte_length"}
        and value.get("path") == path.as_posix()
        and isinstance(value.get("sha256"), str)
        and seq90.SHA256_RE.fullmatch(value["sha256"]) is not None
        and type(value.get("byte_length")) is int
        and value["byte_length"] > 0,
        f"{label} differs",
    )


def _require_embedded_seq96_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> None:
    """Validate the sealed seq96 owner without reading current review files."""

    seq90._safe_root(root)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "checkpoint is not exact embedded seq96 contract correction",
    )
    event = history[-1]
    require(isinstance(event, dict), "embedded seq96 event is malformed")
    before = _repository_before(event)
    restored = _restored_seq95_checkpoint(checkpoint)
    source_raw = checkpoint_json_bytes(restored)
    _require_frozen_seq95_checkpoint(source_raw, restored)
    source_event = restored["goal_execution"]["transition_history"][-1]

    snapshot = checkpoint.get("working_tree_snapshot")
    paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
    require(
        isinstance(paths, list)
        and paths == sorted(set(paths))
        and all(
            isinstance(path, str)
            and path
            and not Path(path).is_absolute()
            and ".." not in Path(path).parts
            for path in paths
        )
        and snapshot.get("managed_changed_path_count") == len(paths)
        and snapshot.get("path_set_sha256")
        == hashlib.sha256(("\n".join(paths) + "\n").encode("utf-8")).hexdigest()
        and isinstance(snapshot.get("content_set_sha256"), str)
        and seq90.SHA256_RE.fullmatch(snapshot["content_set_sha256"]) is not None,
        "embedded seq96 managed snapshot differs",
    )

    expected = copy.deepcopy(restored)
    expected["current_work"].update(
        {
            "status": "READY",
            "current_focus": CURRENT_FOCUS,
            "next_action": NEXT_ACTION,
            "release_completion_claimed": False,
        }
    )
    expected["working_tree_snapshot"].update(
        {
            "scope": SCOPE,
            "managed_changed_paths": copy.deepcopy(paths),
            "managed_changed_path_count": len(paths),
            "path_set_sha256": snapshot["path_set_sha256"],
            "content_set_sha256": snapshot["content_set_sha256"],
        }
    )
    expected_handoff = expected["session_handoff"]
    expected_handoff["changed_files"] = copy.deepcopy(paths)
    expected_handoff["source_commit_or_snapshot"].update(
        {
            "file_count": len(paths),
            "path_set_sha256": snapshot["path_set_sha256"],
            "content_set_sha256": snapshot["content_set_sha256"],
        }
    )
    expected_handoff.update(
        {
            "current_epic": HANDOFF_EPIC,
            "last_updated_by_work_item": WORK_ITEM_ID,
            "last_verification_status": VERIFICATION_STATUS,
            "next_single_action": NEXT_ACTION,
        }
    )

    occurred = _parse_time(event.get("occurred_at"), "embedded seq96 occurred_at")
    expected_before = {
        **_source_checkpoint_binding(),
        "current_work": copy.deepcopy(restored["current_work"]),
        "working_tree_snapshot": copy.deepcopy(restored["working_tree_snapshot"]),
        "session_handoff": copy.deepcopy(restored["session_handoff"]),
    }
    expected_after = {
        "base_commit": restored["working_tree_snapshot"]["base_head"],
        "branch": LOGICAL_BRANCH,
        "logical_branch": LOGICAL_BRANCH,
        "logical_branch_semantics": LOGICAL_BRANCH_SEMANTICS,
        "physical_git_branch": PHYSICAL_GIT_BRANCH,
        "branch_mismatch_reason_code": BRANCH_MISMATCH_REASON_CODE,
        "current_head": restored["session_handoff"]["source_commit_or_snapshot"][
            "current_head"
        ],
        "managed_changed_path_count": len(paths),
        "path_set_sha256": snapshot["path_set_sha256"],
        "content_set_sha256": snapshot["content_set_sha256"],
    }
    authorization = event.get("authorization_binding")
    review = event.get("transition_control_review_binding")
    _require_embedded_binding(
        authorization, AUTHORIZATION_REL, "embedded seq96 authorization binding"
    )
    require(
        isinstance(review, dict)
        and set(review) == {"assignment", "review_result", "independent_review"},
        "embedded seq96 review binding differs",
    )
    for key, path in (
        ("assignment", REVIEW_ASSIGNMENT_REL),
        ("review_result", REVIEW_RESULT_REL),
        ("independent_review", INDEPENDENT_REVIEW_REL),
    ):
        _require_embedded_binding(
            review.get(key), path, f"embedded seq96 {key} binding"
        )
    require(
        set(event) == EVENT_FIELDS
        and type(event.get("sequence")) is int
        and event.get("sequence") == CORRECTION_SEQUENCE
        and event.get("event_id") == CORRECTION_EVENT_ID
        and event.get("event_type") == CORRECTION_EVENT_TYPE
        and event.get("occurred_on") == occurred.date().isoformat()
        and event.get("previous_focus_goal_id") == GOAL_ID
        and event.get("previous_focus_content_sha256") == GOAL_SHA256
        and event.get("focus_goal_id") == GOAL_ID
        and event.get("focus_goal_content_sha256") == GOAL_SHA256
        and event.get("subject_goal_id") == GOAL_ID
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("static_plan_manifest_sha256") == MANIFEST_SHA256
        and event.get("status_changes") == {}
        and _strict_json_equal(
            event.get("runtime_after"), source_event["runtime_after"]
        )
        and _strict_json_equal(
            event.get("blockers_after"), source_event["blockers_after"]
        )
        and _strict_json_equal(
            event.get("blocker_resolution_ids_after"),
            source_event["blocker_resolution_ids_after"],
        )
        and event.get("source_checkpoint_version") == restored["schema_version"]
        and event.get("evidence_refs")
        == [
            "FP048-R002_EXACT_PUBLISHED_SEQ95_SOURCE",
            "FP048-R002_SEQ95_R002_FROZEN_REVIEW_AUTHORITY",
            "FP048-R002_R007_ROOT_REGRESSION_PREVIEW_NONAUTHORITY",
            "FP048-R002_R008_STAGE_AWARE_GATE_CONTRACT",
            "FP048-R002_SEQ96_97_TRANSITION_CONTROL_REVIEW",
        ]
        and _strict_json_equal(
            event.get("source_checkpoint_binding"), _source_checkpoint_binding()
        )
        and _strict_json_equal(
            event.get("source_ready_event_binding"),
            source_event["source_ready_event_binding"],
        )
        and _strict_json_equal(
            event.get("contract_supersession"),
            {
                "previous_contract_binding": R007_CONTRACT_BINDING,
                "reason_code": R008_SUCCESSOR_REASON_CODE,
                "replacement_contract_binding": R008_CONTRACT_BINDING,
            },
        )
        and _strict_json_equal(
            event.get("start_gate_runner_binding"), R008_RUNNER_BINDING
        )
        and _strict_json_equal(
            event.get("repository_context_reanchor"),
            {"before": expected_before, "after": expected_after},
        )
        and _strict_json_equal(
            event.get("noncredit_successor_edges"),
            source_event["noncredit_successor_edges"],
        )
        and _strict_json_equal(event.get("claim_boundary"), CLAIM_BOUNDARY)
        and _strict_json_equal(
            event.get("unchanged_control_projection"),
            seq95.seq94.seq93.seq92.correction._unchanged_projection(expected),
        )
        and _strict_json_equal(
            event.get("canonical_binding_snapshot_after"),
            source_event["canonical_binding_snapshot_after"],
        )
        and _strict_json_equal(event.get("correction_reason"), CORRECTION_REASON)
        and event.get("previous_event_sha256") == SOURCE_EVENT_SHA256
        and event.get("event_sha256") == continuation.event_sha256(event),
        "embedded seq96 correction authority differs",
    )
    expected_state = expected["goal_execution"]
    expected_state["transition_history"].append(copy.deepcopy(event))
    expected_state["transition_history_anchor_sha256"] = event["event_sha256"]
    expected_state["validation_cutoff_at"] = event["occurred_at"]
    require(
        checkpoint_json_bytes(expected) == checkpoint_json_bytes(checkpoint),
        "embedded seq96 projection differs",
    )


def require_contract_corrected_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
    run_external_validators: bool = False,
) -> None:
    root = seq90._safe_root(root)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "checkpoint is not exact seq96 contract correction",
    )
    source_event = history[SOURCE_SEQUENCE - 1]
    event = history[CORRECTION_SEQUENCE - 1]
    preflight = (
        r007_preflight_attempt_004_observation(root)
        if require_live_snapshot
        else _stored_r007_preflight_attempt_004()
    )
    require(
        isinstance(event, dict)
        and set(event) == EVENT_FIELDS
        and source_event.get("event_id") == SOURCE_EVENT_ID
        and source_event.get("event_sha256") == SOURCE_EVENT_SHA256
        and type(event.get("sequence")) is int
        and event.get("sequence") == CORRECTION_SEQUENCE
        and event.get("event_id") == CORRECTION_EVENT_ID
        and event.get("event_type") == CORRECTION_EVENT_TYPE
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("previous_event_sha256") == SOURCE_EVENT_SHA256
        and event.get("event_sha256") == continuation.event_sha256(event)
        and event.get("source_checkpoint_binding") == _source_checkpoint_binding(preflight)
        and event.get("correction_reason") == CORRECTION_REASON
        and event.get("claim_boundary") == CLAIM_BOUNDARY
        and event.get("contract_supersession")
        == {
            "previous_contract_binding": R007_CONTRACT_BINDING,
            "reason_code": R008_SUCCESSOR_REASON_CODE,
            "replacement_contract_binding": R008_CONTRACT_BINDING,
        }
        and event.get("start_gate_runner_binding") == R008_RUNNER_BINDING,
        "seq96 correction authority differs",
    )
    source_raw = checkpoint_json_bytes(_restored_seq95_checkpoint(checkpoint))
    source = seq90.strict_json(source_raw, "restored frozen seq95 source")
    _require_frozen_seq95_checkpoint(source_raw, source)
    review_binding, result_at, independent_at = _published_review(
        root,
        event,
        require_live_preflight_absence=require_live_snapshot,
    )
    if require_live_snapshot:
        noncredit_fp023_product_successor_bindings(root)
    event_at = _parse_time(event.get("occurred_at"), "seq96 occurred_at")
    source_at = _parse_time(source_event.get("occurred_at"), "seq95 occurred_at")
    require(
        source_at < result_at < independent_at < event_at,
        "seq96 event and physical review chronology differs",
    )
    before = _repository_before(event)
    snapshot = checkpoint.get("working_tree_snapshot")
    paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
    require(isinstance(paths, list), "seq96 managed paths differ")
    expected, expected_event = project_seq96(
        root,
        source,
        managed_paths=paths,
        path_set_sha256=snapshot.get("path_set_sha256"),
        content_set_sha256=snapshot.get("content_set_sha256"),
        occurred_at=event.get("occurred_at"),
        authorization_binding_value=event["authorization_binding"],
        review_binding=review_binding,
        r007_preflight_binding=preflight,
        replacement_contract_binding=R008_CONTRACT_BINDING,
        replacement_runner_binding=R008_RUNNER_BINDING,
    )
    require(
        checkpoint_json_bytes(expected) == checkpoint_json_bytes(checkpoint)
        and expected_event == event,
        "seq96 projected checkpoint differs",
    )
    for field in (
        "approved_state",
        "authority_boundary",
        "canonical_bindings",
        "verification_boundary",
    ):
        require(_strict_json_equal(checkpoint.get(field), source.get(field)), f"seq96 changed {field}")
    require(
        checkpoint.get("approved_state", {}).get("formal_test_not_run_count") == 279
        and checkpoint.get("verification_boundary", {}).get("release_eligible") is False
        and before["current_work"]["status"] == "READY",
        "seq96 gained formal, implementation, or release credit",
    )
    if require_live_snapshot:
        require(
            continuation.working_snapshot_hashes(root, paths)
            == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
            "seq96 managed snapshot differs",
        )
    if run_external_validators:
        seq90._validate_projected_with_consumers(root, checkpoint)


def reconstructed_seq95_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    if isinstance(history, list) and len(history) == SOURCE_SEQUENCE:
        raw = checkpoint_json_bytes(checkpoint)
        _require_frozen_seq95_checkpoint(raw, checkpoint)
        return raw
    if isinstance(history, list) and len(history) == CORRECTION_SEQUENCE:
        _require_embedded_seq96_checkpoint(root, checkpoint)
        restored = _restored_seq95_checkpoint(checkpoint)
    elif isinstance(history, list) and len(history) == STARTED_SEQUENCE:
        started = importlib.import_module(
            "scripts.apply_walksafe_fp048_r002_goal_started_seq97_20260826"
        )
        seq96_raw = started.reconstructed_seq96_checkpoint_bytes(root, checkpoint)
        seq96 = seq90.strict_json(seq96_raw, "reconstructed seq96 correction")
        _require_embedded_seq96_checkpoint(root, seq96)
        restored = _restored_seq95_checkpoint(seq96)
    else:
        raise ContractCorrectionError("seq95 inverse source is not an exact seq95-97 stage")
    raw = checkpoint_json_bytes(restored)
    _require_frozen_seq95_checkpoint(raw, restored)
    return raw


def load_exact_seq95_source(root: Path = ROOT) -> tuple[bytes, dict[str, Any]]:
    root = seq90._safe_root(root)
    read = seq90._stable_read(root, CHECKPOINT_REL)
    live = seq90.strict_json(read.raw, CHECKPOINT_REL.as_posix())
    history = live.get("goal_execution", {}).get("transition_history")
    if isinstance(history, list) and len(history) == SOURCE_SEQUENCE:
        raw = read.raw
    else:
        raw = reconstructed_seq95_checkpoint_bytes(root, live)
    source = seq90.strict_json(raw, "stage-aware exact seq95 source")
    require_exact_seq95_source(raw, source, root)
    return raw, source


def canonical_seq96_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    require_contract_corrected_checkpoint(root, checkpoint, require_live_snapshot=False)
    return checkpoint_json_bytes(checkpoint)


def reconstructed_seq96_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "seq97 inverse is owned by the seq97 starter",
    )
    _require_embedded_seq96_checkpoint(root, checkpoint)
    return checkpoint_json_bytes(checkpoint)


def reconstructed_seq94_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    seq95_raw = reconstructed_seq95_checkpoint_bytes(root, checkpoint)
    source = seq90.strict_json(seq95_raw, "reconstructed frozen seq95")
    context = source["goal_execution"]["transition_history"][-1][
        "repository_context_reanchor"
    ]["before"]
    restored = copy.deepcopy(source)
    state = restored["goal_execution"]
    event = state["transition_history"][seq95.SOURCE_SEQUENCE - 1]
    state["transition_history"] = state["transition_history"][: seq95.SOURCE_SEQUENCE]
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = event["occurred_at"]
    restored["current_work"] = copy.deepcopy(context["current_work"])
    restored["working_tree_snapshot"] = copy.deepcopy(context["working_tree_snapshot"])
    restored["session_handoff"] = copy.deepcopy(context["session_handoff"])
    return seq95.canonical_frozen_seq94_checkpoint_bytes(root, restored)


def reconstructed_seq93_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    seq95_raw = reconstructed_seq95_checkpoint_bytes(root, checkpoint)
    source = seq90.strict_json(seq95_raw, "reconstructed frozen seq95")
    return seq95.reconstructed_seq93_checkpoint_bytes(root, source)


@dataclass(frozen=True)
class Prepared:
    transport: Any

    @property
    def source(self) -> dict[str, Any]:
        return self.transport.source

    @property
    def projected(self) -> dict[str, Any]:
        return self.transport.projected

    @property
    def event(self) -> dict[str, Any]:
        return self.transport.event


_AFTER_CAPTURE_HOOK: Callable[[Path], None] | None = None


def _require_private_checkpoint_mode(root: Path, phase: str) -> None:
    mode = stat.S_IMODE((root / CHECKPOINT_REL).lstat().st_mode)
    require(mode == 0o600, f"checkpoint mode differs {phase}")


def _require_prepared_payload(prepared: Prepared) -> None:
    transport = prepared.transport
    require(
        transport.source_raw == checkpoint_json_bytes(transport.source)
        and transport.projected_raw == checkpoint_json_bytes(transport.projected)
        and transport.event
        == transport.projected["goal_execution"]["transition_history"][-1],
        "prepared seq96 payload differs",
    )
    _require_frozen_seq95_checkpoint(transport.source_raw, transport.source)
    _require_embedded_seq96_checkpoint(transport.root, transport.projected)


def _require_prepared_exact(prepared: Prepared) -> None:
    transport = prepared.transport
    _require_prepared_payload(prepared)
    _require_private_checkpoint_mode(transport.root, "during seq96 preflight reseal")
    passed_gate_attempt_003_binding(transport.root)
    r007_preflight_attempt_004_observation(transport.root)
    r007_contract_binding(transport.root)
    r007_runner_binding(transport.root)
    r008_contract_binding(transport.root)
    r008_runner_binding(transport.root)
    _stage_aware_r001_projected_preflight_failure(transport.root)
    seq90._require_preflight_cohort_unchanged(
        transport.root,
        source=seq90.ReadResult(transport.source_raw, transport.source_identity),
        retained=transport.retained_inputs,
        managed=transport.managed_inputs,
        git_visible=transport.git_visible_inputs,
        git_status_raw=transport.git_status_raw,
        git_head=transport.git_head,
        git_branch=transport.git_branch,
        phase="seq96 contract-correction commit guard",
    )


def _without_seq90_writer_temporary_status(observed: bytes, expected: bytes) -> bytes:
    if observed == expected:
        return observed
    prefix = (
        b"?? "
        + CHECKPOINT_REL.parent.as_posix().encode("utf-8")
        + b"/.walksafe-fp048-r002-seq90-write."
    )
    records = observed.split(b"\0")
    candidates = []
    for index, record in enumerate(records):
        if record.startswith(prefix) and record.endswith(b".json"):
            candidate = b"\0".join(records[:index] + records[index + 1 :])
            if candidate == expected:
                candidates.append(candidate)
    require(len(candidates) == 1, "Git-visible cohort changed at seq96 commit guard")
    return candidates[0]


def _require_commit_exact(prepared: Prepared) -> None:
    transport = prepared.transport
    root = transport.root
    _require_prepared_payload(prepared)
    _require_private_checkpoint_mode(root, "at seq96 commit guard")
    passed_gate_attempt_003_binding(root)
    r007_preflight_attempt_004_observation(root)
    r007_contract_binding(root)
    r007_runner_binding(root)
    r008_contract_binding(root)
    r008_runner_binding(root)
    _stage_aware_r001_projected_preflight_failure(root)
    seq90._require_inputs_unchanged(root, transport.retained_inputs)
    seq90._require_managed_inputs_unchanged(root, transport.managed_inputs)
    seq90._require_managed_inputs_unchanged(
        root, transport.git_visible_inputs, label="full Git-visible input"
    )
    seq90._require_git_context(root, transport.git_head, transport.git_branch)
    source = seq90._stable_read(root, CHECKPOINT_REL)
    require(
        source.identity == transport.source_identity and source.raw == transport.source_raw,
        "source changed at seq96 commit guard",
    )
    status_raw, _visible = seq90.capture_git_visible_paths(root)
    require(
        _without_seq90_writer_temporary_status(status_raw, transport.git_status_raw)
        == transport.git_status_raw,
        "Git-visible cohort changed at seq96 commit guard",
    )


def prepare(
    root: Path = ROOT,
    *,
    occurred_at: str | None = None,
    validate_consumers: bool = True,
) -> Prepared:
    root = seq90._safe_root(root)
    source_read = seq90._stable_read(root, CHECKPOINT_REL)
    _require_private_checkpoint_mode(root, "before seq96 preflight")
    source = seq90.strict_json(source_read.raw, CHECKPOINT_REL.as_posix())
    require_exact_seq95_source(source_read.raw, source, root)
    passed_gate_attempt_003_binding(root)
    preflight = r007_preflight_attempt_004_observation(root)
    replacement = r008_contract_binding(root)
    replacement_runner = r008_runner_binding(root)
    git_head, git_branch = seq90._capture_git_context(root)
    expected_head = source["session_handoff"]["source_commit_or_snapshot"]["current_head"]
    require(git_head == expected_head, "live Git HEAD differs from seq95 source")
    require(git_branch == PHYSICAL_GIT_BRANCH, "live Git branch differs from seq95 source")
    status_raw, visible = seq90.capture_git_visible_paths(root)
    paths = _final_managed_paths(source, visible)
    managed = seq90._capture_managed_inputs(root, paths)
    visible_inputs = seq90._capture_managed_inputs(root, visible)
    path_sha256, content_sha256 = seq90._managed_input_snapshot_hashes(managed)
    retained_paths = (
        set(REVIEWED_CONTROL_PATHS)
        | set(REVIEW_PATHS)
        | {AUTHORIZATION_REL, seq95.R007_CONTRACT_REL, seq95.R007_RUNNER_REL}
        | {Path(FROZEN_SEQ95_AUTHORIZATION_BINDING["path"])}
        | {
            Path(binding["path"])
            for binding in FROZEN_R001_REVIEW_BINDINGS.values()
        }
        | {
            Path(binding["path"])
            for binding in FROZEN_SEQ95_R002_REVIEW_BINDINGS.values()
        }
    )
    retained_paths.update(
        PASSED_GATE_DIR_REL / name for name, _digest, _length in PASSED_GATE_FILE_PINS
    )
    retained = {
        path: seq90._stable_read(root, path)
        for path in sorted(retained_paths, key=lambda item: item.as_posix())
    }
    authorization = authorization_binding(root, source)
    review, _result_at, independent_at = _load_physical_review(root, source)
    projected, event = project_seq96(
        root,
        source,
        managed_paths=paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        occurred_at=_event_time(source, occurred_at, independent_at),
        authorization_binding_value=authorization,
        review_binding=review,
        r007_preflight_binding=preflight,
        replacement_contract_binding=replacement,
        replacement_runner_binding=replacement_runner,
    )
    transport = seq90.Prepared(
        root=root,
        source_raw=source_read.raw,
        source_identity=source_read.identity,
        source=source,
        projected=projected,
        projected_raw=checkpoint_json_bytes(projected),
        event=event,
        retained_inputs=retained,
        managed_inputs=managed,
        git_visible_inputs=visible_inputs,
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )
    prepared = Prepared(transport)
    if _AFTER_CAPTURE_HOOK is not None:
        _AFTER_CAPTURE_HOOK(root)
    _require_prepared_exact(prepared)
    if validate_consumers:
        seq90._validate_projected_with_consumers(root, projected)
        _require_prepared_exact(prepared)
    return prepared


def write_checkpoint(prepared: Prepared) -> None:
    _require_prepared_exact(prepared)
    seq90.write_checkpoint(
        prepared.transport,
        commit_guard=lambda: _require_commit_exact(prepared),
    )
    try:
        observed = seq90._stable_read(prepared.transport.root, CHECKPOINT_REL).raw
        require(
            observed == prepared.transport.projected_raw,
            "published seq96 checkpoint bytes differ",
        )
        published = seq90.strict_json(observed, CHECKPOINT_REL.as_posix())
        _require_embedded_seq96_checkpoint(prepared.transport.root, published)
        _require_private_checkpoint_mode(prepared.transport.root, "after seq96 publication")
    except BaseException as exc:
        raise seq90.PostcommitUncertain(
            "published seq96 checkpoint mode is uncertain"
        ) from exc


def prepare_review_manifest(
    root: Path = ROOT,
    *,
    require_live_preflight_absence: bool = True,
) -> dict[str, Any]:
    root = seq90._safe_root(root)
    _raw, source = load_exact_seq95_source(root)
    authorization = build_authorization(
        root,
        source,
        require_live_preflight_absence=require_live_preflight_absence,
    )
    assignment = build_review_assignment(
        root,
        source,
        require_live_preflight_absence=require_live_preflight_absence,
    )
    preflight = (
        r007_preflight_attempt_004_observation(root)
        if require_live_preflight_absence
        else _stored_r007_preflight_attempt_004()
    )
    return {
        "candidate_authorization": _binding(AUTHORIZATION_REL, authorization),
        "candidate_review_assignment": _binding(REVIEW_ASSIGNMENT_REL, assignment),
        "review_root": REVIEW_ROOT.as_posix(),
        "reviewer_authored_paths": [
            REVIEW_RESULT_REL.as_posix(),
            INDEPENDENT_REVIEW_REL.as_posix(),
        ],
        "round_id": ROUND_ID,
        "source_checkpoint_binding": _source_checkpoint_binding(preflight),
        "status": "CANDIDATE_ONLY_NOT_PUBLISHED",
        "superseded_review_binding": copy.deepcopy(FROZEN_R001_REVIEW_BINDINGS),
        "supersedes_round_id": SUPERSEDES_ROUND_ID,
        "supersession_reason": SUPERSESSION_REASON,
    }


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare-authorization", action="store_true")
    mode.add_argument("--prepare-review", action="store_true")
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--occurred-at")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    published = False
    try:
        if args.prepare_authorization:
            _raw, source = load_exact_seq95_source(args.root)
            sys.stdout.buffer.write(build_authorization(args.root, source))
            return 0
        if args.prepare_review:
            sys.stdout.buffer.write(
                seq90.canonical_json_bytes(prepare_review_manifest(args.root))
            )
            return 0
        prepared = prepare(args.root, occurred_at=args.occurred_at)
        if args.write:
            write_checkpoint(prepared)
            published = True
        try:
            print(
                "FP048-R002 seq96 gate-contract correction: PASS "
                f"event_sha256={prepared.event['event_sha256']} "
                f"published={str(published).lower()}"
            )
        except BaseException as exc:
            if published:
                raise seq90.PostcommitUncertain(
                    "seq96 was published but success reporting is uncertain"
                ) from exc
            raise
        return 0
    except seq90.PostcommitUncertain as exc:
        print(
            "FP048-R002 seq96 gate-contract correction: POSTCOMMIT-UNCERTAIN: "
            f"{exc}",
            file=sys.stderr,
        )
        return 2
    except Exception as exc:
        print(f"FP048-R002 seq96 gate-contract correction: FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
