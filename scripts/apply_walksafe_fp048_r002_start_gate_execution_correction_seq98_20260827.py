#!/usr/bin/env python3
"""Prepare the append-only FP048 R002 seq98 R009 execution correction.

The published seq97 checkpoint and the consumed failed R009 -004 namespace are
immutable.  This producer records the failed R009 execution as nonauthority,
reanchors the start gate to R010, and keeps FP048 R002 READY with zero product
or release credit.  It never writes authorization, review, gate, or checkpoint
documents while peer bindings remain provisional.
"""

from __future__ import annotations

import argparse
import copy
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import stat
import sys
from typing import Any, Iterator, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import (  # noqa: E402
    apply_walksafe_fp048_goal_completed_seq43_44_20260802 as cas,
    apply_walksafe_fp048_r002_start_gate_snapshot_hygiene_correction_seq97_20260826
    as seq97,
)


class StartGateExecutionCorrectionError(RuntimeError):
    """The seq98 R009 execution correction cannot be proven or published."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise StartGateExecutionCorrectionError(message)


_SEQ97_SOURCE_VALIDATION_CALL_CACHE: ContextVar[
    dict[tuple[str, int, str], Exception | None] | None
] = ContextVar("_SEQ97_SOURCE_VALIDATION_CALL_CACHE", default=None)
_SEQ97_SOURCE_VALIDATION_CACHE_MISS = object()


@contextmanager
def seq97_source_validation_call_scope() -> Iterator[None]:
    """Reuse one exact seq97 proof only inside the current validation call."""

    if _SEQ97_SOURCE_VALIDATION_CALL_CACHE.get() is not None:
        yield
        return
    token = _SEQ97_SOURCE_VALIDATION_CALL_CACHE.set({})
    try:
        yield
    finally:
        _SEQ97_SOURCE_VALIDATION_CALL_CACHE.reset(token)


seq90 = seq97.seq90
continuation = seq97.continuation
CHECKPOINT_REL = seq97.CHECKPOINT_REL
checkpoint_json_bytes = seq97.checkpoint_json_bytes
GOAL_ID = seq97.GOAL_ID
GOAL_PATH = seq97.GOAL_PATH
GOAL_SHA256 = seq97.GOAL_SHA256
WORK_ITEM_ID = seq97.WORK_ITEM_ID
MANIFEST_SHA256 = seq97.MANIFEST_SHA256
EVENT_FIELDS = seq97.EVENT_FIELDS

SOURCE_SEQUENCE = 97
SOURCE_EVENT_ID = seq97.CORRECTION_EVENT_ID
SOURCE_EVENT_SHA256 = (
    "2dce092fa3e01ea767e931cbe130aed32ce3eed96d50c05ef86cde9ff5a96bf1"
)
SOURCE_CHECKPOINT_SHA256 = (
    "007732d63e71a199c3c2a4b275d8e6878ae06385b730220407d4649cfa0247ed"
)
SOURCE_CHECKPOINT_BYTE_LENGTH = 4_740_758
SOURCE_CHECKPOINT_MODE = 0o600

CORRECTION_SEQUENCE = 98
CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-GATE-EXECUTION-CORRECTED-"
    "FP048-R002-20260827-006"
)
CORRECTION_EVENT_TYPE = "GOAL_START_GATE_EXECUTION_CORRECTED"
CORRECTION_REASON_CODE = (
    "R009_ROOT_REGRESSION_INCLUDED_PRE_SEQ97_CORRECTION_TESTS"
)
STARTED_SEQUENCE = 99
STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260827-005"
)

R009_CONTRACT_BINDING = {
    "schema_version": "1.2",
    "document_id": "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-009",
    "path": (
        "docs/control/execution/goal-contracts/"
        "WS-GOAL-EPIC-03-FP-048-R002/initial-start-gate-contract-r009.json"
    ),
    "file_sha256": (
        "cd027dde66924a19e1a7942c347b4b4a0e2bea44a8e6e0752a1a42993f9d20ff"
    ),
    "contract_id": "WS-FP048-R002-INTERNAL-START-GATE-R009",
    "contract_version": "2026-08-26.8",
    "canonical_contract_sha256": (
        "9e82e6c2ef8a35b412efa44567025b0316e42f2f3840a4ef290f426aadbd531b"
    ),
}
R009_RUNNER_BINDING = {
    "path": "scripts/run_walksafe_fp048_r002_goal_start_gate_r009_20260826.py",
    "sha256": "f5ed6e22507e3e08413dbc089accd73e791bda02d20a88ff8b50c9914c1c1e24",
    "byte_length": 33_639,
}

R009_GATE_REL = Path(
    "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-004"
)
R009_RECEIPT_REL = R009_GATE_REL / "implementation-start-gate-receipt.json"
R009_LOG_BINDINGS = (
    {
        "path": (R009_GATE_REL / "01-CONTINUATION.log").as_posix(),
        "sha256": "25b5c8cf65854e11a04a9d0810dd2503585037683809c153f578c08a076ac5a7",
        "byte_length": 39,
        "mode": "0600",
    },
    {
        "path": (R009_GATE_REL / "02-GOAL_GRAPH.log").as_posix(),
        "sha256": "f880c51e169f2a6f03875ab90156c15b9c5ce260ed0761b0048556b08d089516",
        "byte_length": 108,
        "mode": "0600",
    },
    {
        "path": (R009_GATE_REL / "03-TEST_LAYER_REGISTRY_VALIDATE.log").as_posix(),
        "sha256": "1eef448a998bf080cbe648b330b4c9369d49ea95bbccf662ce8d077b56c96187",
        "byte_length": 35,
        "mode": "0600",
    },
    {
        "path": (
            R009_GATE_REL / "04-ROOT_FP048_R002_CONTROL_REGRESSION.log"
        ).as_posix(),
        "sha256": "9dd070b01d5cf99c0923f2c38858d1d416500584d8988b5b513503dff16758b1",
        "byte_length": 32_612,
        "mode": "0600",
    },
)

R010_CONTRACT_ID = "WS-FP048-R002-INTERNAL-START-GATE-R010"
R010_CONTRACT_VERSION = "2026-08-27.1"
R010_DOCUMENT_ID = "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260827-010"
R010_CONTRACT_REL = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r010.json"
)
R010_RUNNER_REL = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r010_20260827.py"
)
R010_RUNNER_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r010_20260827.py"
)
POST_SEQ97_STAGE_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_post_seq97_stage_regression_20260827.py"
)
SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp048_r002_start_gate_execution_correction_"
    "seq98_20260827.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_start_gate_execution_correction_"
    "seq98_20260827.py"
)
SEQ99_STARTER_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq99_20260827.py"
)
SEQ99_STARTER_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq99_20260827.py"
)
CONTINUATION_SCRIPT_REL = Path("scripts/check_walksafe_project_continuation_v2_4.py")
CONTINUATION_TEST_REL = Path("tests/test_walksafe_project_continuation_v2_4.py")
GOAL_GRAPH_SCRIPT_REL = Path("scripts/check_walksafe_goal_graph_v2_4.py")
GOAL_GRAPH_TEST_REL = Path("tests/test_walksafe_goal_graph_v2_4.py")
LAYER_RUNNER_REL = Path("scripts/run_walksafe_test_layers_current.sh")
GITIGNORE_REL = Path(".gitignore")
SEQ90_WRITER_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826.py"
)
SEQ90_WRITER_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826.py"
)
GATE_EVIDENCE_PREFIX = "docs/control/execution/goal-gates/"
CAS_WRITER_REL = Path(
    "scripts/apply_walksafe_fp048_goal_completed_seq43_44_20260802.py"
)
CAS_WRITER_TEST_REL = Path(
    "tests/test_walksafe_fp048_goal_completed_seq43_44_20260802.py"
)
CAS_WRITE_TEMP_PREFIX = (
    ".walksafe-project-continuation-checkpoint.json.fp048-seq43-44."
)
CAS_WRITE_TEMP_SUFFIX = ".tmp"

TRANSITION_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq98-99"
)
R001_AUTHORIZATION_REL = TRANSITION_ROOT / "authorization.json"
R001_AUTHORIZATION_BINDING = {
    "path": R001_AUTHORIZATION_REL.as_posix(),
    "sha256": "6bba0a1010ba8beb6b0bc4439b4b4e1b846c585a16b4b5a4c66e3d24753e394e",
    "byte_length": 8_003,
}
R001_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R001"
R001_REVIEW_ASSIGNMENT_REL = R001_REVIEW_ROOT / "review-assignment.json"
R001_REVIEW_RESULT_REL = R001_REVIEW_ROOT / "review-result.json"
R001_INDEPENDENT_REVIEW_REL = R001_REVIEW_ROOT / "independent-review.json"
R001_REVIEW_ASSIGNMENT_BINDING = {
    "path": R001_REVIEW_ASSIGNMENT_REL.as_posix(),
    "sha256": "aadcefcb308b9ac4e091c70e61ce512234eef2975df62961cb702fb56bca19a5",
    "byte_length": 10_754,
}
R001_STALE_PHYSICAL_PATHS = (
    R001_AUTHORIZATION_REL,
    R001_REVIEW_ASSIGNMENT_REL,
)
R001_FORBIDDEN_OUTPUT_PATHS = (
    R001_REVIEW_RESULT_REL,
    R001_INDEPENDENT_REVIEW_REL,
)

R002_AUTHORIZATION_REL = TRANSITION_ROOT / "authorization-r002.json"
R002_AUTHORIZATION_BINDING = {
    "path": R002_AUTHORIZATION_REL.as_posix(),
    "sha256": "1d3a658594086de77572a9a1f44eeaa3dd96e987576af5b3bce76696dab45091",
    "byte_length": 8_696,
}
R002_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R002"
R002_REVIEW_ASSIGNMENT_REL = R002_REVIEW_ROOT / "review-assignment.json"
R002_REVIEW_RESULT_REL = R002_REVIEW_ROOT / "review-result.json"
R002_INDEPENDENT_REVIEW_REL = R002_REVIEW_ROOT / "independent-review.json"
R002_REVIEW_ASSIGNMENT_BINDING = {
    "path": R002_REVIEW_ASSIGNMENT_REL.as_posix(),
    "sha256": "996ac3758ff2c96915194e9486ccda8d31a4554e762e4c96ce4f0a70cadcac1e",
    "byte_length": 11_451,
}
R002_STALE_PHYSICAL_PATHS = (
    R002_AUTHORIZATION_REL,
    R002_REVIEW_ASSIGNMENT_REL,
)
R002_FORBIDDEN_OUTPUT_PATHS = (
    R002_REVIEW_RESULT_REL,
    R002_INDEPENDENT_REVIEW_REL,
)
R002_REVIEW_FINDING_COUNTS = {"P0": 0, "P1": 2, "P2": 1}

R003_AUTHORIZATION_REL = TRANSITION_ROOT / "authorization-r003.json"
R003_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R003"
R003_REVIEW_ASSIGNMENT_REL = R003_REVIEW_ROOT / "review-assignment.json"
R003_REVIEW_RESULT_REL = R003_REVIEW_ROOT / "review-result.json"
R003_INDEPENDENT_REVIEW_REL = R003_REVIEW_ROOT / "independent-review.json"
R003_AUTHORIZATION_BINDING = {
    "path": R003_AUTHORIZATION_REL.as_posix(),
    "sha256": "717ec775a9fabe380b01c0921c39aa3b6f68ee8ec295bc4d63c4959519071a30",
    "byte_length": 9_464,
}
R003_REVIEW_ASSIGNMENT_BINDING = {
    "path": R003_REVIEW_ASSIGNMENT_REL.as_posix(),
    "sha256": "d6115b7775831f6b1298c34c92d531b303ea8f5c92ef6b189bb7283ae5a89b22",
    "byte_length": 12_219,
}
R003_REVIEW_RESULT_BINDING = {
    "path": R003_REVIEW_RESULT_REL.as_posix(),
    "sha256": "e927e198f888578f3548d3e1e4ee3f7cd19223e6f17ab88770b0f9256ae398c8",
    "byte_length": 753,
}
R003_INDEPENDENT_REVIEW_BINDING = {
    "path": R003_INDEPENDENT_REVIEW_REL.as_posix(),
    "sha256": "666ecb520e4e1e114165a41e903db1103db9d30927d7477e81068300a89c3b16",
    "byte_length": 1_010,
}
R003_PHYSICAL_PATHS = (
    R003_AUTHORIZATION_REL,
    R003_REVIEW_ASSIGNMENT_REL,
    R003_REVIEW_RESULT_REL,
    R003_INDEPENDENT_REVIEW_REL,
)
R003_REVIEWER = {
    "id": "codex-fp048-r002-seq98-99-correction-reviewer-20260827",
    "task_id": "/root/seq96_goalgraph_terminal_dispatch_audit",
}
R003_REVIEW_RESULT_AT = "2026-08-27T05:27:40+09:00"
R003_INDEPENDENT_REVIEW_AT = "2026-08-27T05:27:41+09:00"
R003_FAILURE_REASON = "Git-visible cohort changed at seq98 commit point"

R004_AUTHORIZATION_REL = TRANSITION_ROOT / "authorization-r004.json"
R004_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R004"
R004_REVIEW_ASSIGNMENT_REL = R004_REVIEW_ROOT / "review-assignment.json"
R004_REVIEW_RESULT_REL = R004_REVIEW_ROOT / "review-result.json"
R004_INDEPENDENT_REVIEW_REL = R004_REVIEW_ROOT / "independent-review.json"
R004_AUTHORIZATION_BINDING = {
    "path": R004_AUTHORIZATION_REL.as_posix(),
    "sha256": "630b76859dd9fade1ef013fd0a562c312ae1ab6a83ad5ef163b2fbce90c36a4e",
    "byte_length": 14_247,
}
R004_REVIEW_ASSIGNMENT_BINDING = {
    "path": R004_REVIEW_ASSIGNMENT_REL.as_posix(),
    "sha256": "07e2a024ed5c56cf46129aee6b2976a5b09cc93ed054aeb79ca45587b759b0ea",
    "byte_length": 18_009,
}
R004_STALE_PHYSICAL_PATHS = (
    R004_AUTHORIZATION_REL,
    R004_REVIEW_ASSIGNMENT_REL,
)
R004_FORBIDDEN_OUTPUT_PATHS = (
    R004_REVIEW_RESULT_REL,
    R004_INDEPENDENT_REVIEW_REL,
)
R004_REVIEW_FINDING_COUNTS = {"P0": 0, "P1": 1, "P2": 0}
R004_REVIEWER = {
    "id": "codex-fp048-r002-seq98-99-correction-reviewer-r004-20260827",
    "task_id": "/root/seq96_goalgraph_terminal_dispatch_audit",
}

AUTHORIZATION_REL = TRANSITION_ROOT / "authorization-r005.json"
REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R005"
REVIEW_ASSIGNMENT_REL = REVIEW_ROOT / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_ROOT / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_ROOT / "independent-review.json"
REVIEW_PATHS = (
    REVIEW_ASSIGNMENT_REL,
    REVIEW_RESULT_REL,
    INDEPENDENT_REVIEW_REL,
)
REVIEW_ROUND_ID = "R005"
AUTHORIZATION_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ98-99-AUTHORIZATION-20260827-R005"
)
REVIEW_ASSIGNMENT_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ98-99-REVIEW-ASSIGNMENT-20260827-R005"
)
REVIEW_RESULT_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ98-99-REVIEW-RESULT-20260827-R005"
)
INDEPENDENT_REVIEW_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ98-99-INDEPENDENT-REVIEW-20260827-R005"
)
REVIEWER = {
    "id": "codex-fp048-r002-seq98-99-correction-reviewer-r005-20260827",
    "task_id": "/root/seq96_product_reanchor_goalgraph",
}
EXECUTOR = {
    "id": "codex-fp048-r002-seq98-gate-execution-correction-executor-20260827",
    "task_id": "/root/seq96_review_r002_update",
}

UNFROZEN_SHA256 = "0" * 64
UNFROZEN_BYTE_LENGTH = -1
FINAL_AUTHORIZATION_STATUS = (
    "AUTHORIZED_FOR_CONDITIONAL_SEQ98_99_R009_EXECUTION_CORRECTION_CONTINUATION"
)
FINAL_CANDIDATE_STATUS = "FINAL_REVIEW_CANDIDATE"
PROVISIONAL_STATUS = "PROVISIONAL_DO_NOT_PUBLISH"


def _provisional_binding(path: Path) -> dict[str, Any]:
    return {
        "path": path.as_posix(),
        "sha256": UNFROZEN_SHA256,
        "byte_length": UNFROZEN_BYTE_LENGTH,
    }


REVIEWED_CONTROL_PATHS = tuple(
    sorted(
        {
            R010_CONTRACT_REL,
            R010_RUNNER_REL,
            R010_RUNNER_TEST_REL,
            POST_SEQ97_STAGE_TEST_REL,
            SCRIPT_REL,
            TEST_REL,
            SEQ99_STARTER_REL,
            SEQ99_STARTER_TEST_REL,
            CONTINUATION_SCRIPT_REL,
            CONTINUATION_TEST_REL,
            GOAL_GRAPH_SCRIPT_REL,
            GOAL_GRAPH_TEST_REL,
            LAYER_RUNNER_REL,
            GITIGNORE_REL,
            SEQ90_WRITER_REL,
            SEQ90_WRITER_TEST_REL,
            CAS_WRITER_REL,
            CAS_WRITER_TEST_REL,
        },
        key=lambda value: value.as_posix(),
    )
)
DYNAMIC_REVIEWED_CONTROL_PATHS = frozenset(
    {SCRIPT_REL, TEST_REL, SEQ99_STARTER_REL, SEQ99_STARTER_TEST_REL}
)
FIXED_REVIEWED_CONTROL_BINDINGS: dict[Path, dict[str, Any]] = {
    GITIGNORE_REL: {
        "path": GITIGNORE_REL.as_posix(),
        "sha256": "2870d3d01da10787855b5af666788f9f0f697c233b53394104eef831deb2c3e1",
        "byte_length": 4_485,
    },
    R010_CONTRACT_REL: {
        "path": R010_CONTRACT_REL.as_posix(),
        "sha256": "6bbb426e5b00f7f886e376587d06b42a97b1a5b04683d685f701e2ca0a04120e",
        "byte_length": 3_935,
    },
    R010_RUNNER_REL: {
        "path": R010_RUNNER_REL.as_posix(),
        "sha256": "10c60b71893a84f28466331b26efde212af3d13c8a996c6e350b3ec5faacd6e5",
        "byte_length": 39_666,
    },
    R010_RUNNER_TEST_REL: {
        "path": R010_RUNNER_TEST_REL.as_posix(),
        "sha256": "54617a5e21ad7f7a0ae71a982d055409f2b1ca25a90d420dc5a63f1ae24cc483",
        "byte_length": 21_003,
    },
    POST_SEQ97_STAGE_TEST_REL: {
        "path": POST_SEQ97_STAGE_TEST_REL.as_posix(),
        "sha256": "b77ad70fbaf28ade6dcfb78181f2511b72bddbc4b2ad25f92bdda9027e946281",
        "byte_length": 9_667,
    },
    CONTINUATION_SCRIPT_REL: {
        "path": CONTINUATION_SCRIPT_REL.as_posix(),
        "sha256": "002396f990125a739dd5b7b8ba2ae80b657eec29f7d82a1e12d72d52e2829b92",
        "byte_length": 690_873,
    },
    CONTINUATION_TEST_REL: {
        "path": CONTINUATION_TEST_REL.as_posix(),
        "sha256": "a8bbc0b01231fd1b2f7898b9123bc2ffc0db14326b45289881c5a5b0af653ad6",
        "byte_length": 363_091,
    },
    GOAL_GRAPH_SCRIPT_REL: {
        "path": GOAL_GRAPH_SCRIPT_REL.as_posix(),
        "sha256": "fac35318edf85fd5428fe0abe4d12942720eba5fb9a11e9a8f09fb3e7f7a3c18",
        "byte_length": 953_946,
    },
    GOAL_GRAPH_TEST_REL: {
        "path": GOAL_GRAPH_TEST_REL.as_posix(),
        "sha256": "2f8149cf8c4397c3467849aa6fc21fc6a738d4e2f560fd4d0a71225d329afc11",
        "byte_length": 682_080,
    },
    LAYER_RUNNER_REL: {
        "path": LAYER_RUNNER_REL.as_posix(),
        "sha256": "b56e8a06fd240a9faec66d234b9da701141b0703da4b09037b37fc8a6e4175e2",
        "byte_length": 28_807,
    },
    SEQ90_WRITER_REL: {
        "path": SEQ90_WRITER_REL.as_posix(),
        "sha256": "f5d8ac24d05b3a0e98ece7d5d977219c37859d44d5540ba991749cbffdd63ac2",
        "byte_length": 105_892,
    },
    SEQ90_WRITER_TEST_REL: {
        "path": SEQ90_WRITER_TEST_REL.as_posix(),
        "sha256": "a6d12e95e169b563f4d1642460da36c27cc43884836931ea65f81276c4fa71ad",
        "byte_length": 26_198,
    },
    CAS_WRITER_REL: {
        "path": CAS_WRITER_REL.as_posix(),
        "sha256": "6b1038129a8783ad1c7c11f6066d1c1758705b358c8c0a6034553fcda4d17f7c",
        "byte_length": 73_137,
    },
    CAS_WRITER_TEST_REL: {
        "path": CAS_WRITER_TEST_REL.as_posix(),
        "sha256": "6004935a29f7c7727bfcf65dba1fea5ebef73b0a26f6494af9338cf732ede589",
        "byte_length": 41_222,
    },
}
R010_CANONICAL_SHA256 = (
    "0428342409bb1210563a579d153ed025ff6ce1ca636a874c1532bb2423fedf1e"
)

AUTHORIZATION_FIELDS = {
    "schema_version",
    "document_id",
    "evidence_type",
    "authorization_status",
    "goal_id",
    "source_checkpoint_binding",
    "r009_execution_failure",
    "previous_contract_binding",
    "replacement_contract_binding",
    "contract_correction_event_id",
    "projected_transition",
    "claim_boundary",
    "authorized_executor",
    "required_independent_reviewer",
    "stale_r001_preparation",
    "rejected_r002_review",
    "failed_r003_execution",
    "rejected_r004_review",
}
ASSIGNMENT_FIELDS = {
    "schema_version",
    "document_id",
    "evidence_type",
    "round_id",
    "candidate_status",
    "goal_id",
    "authorization_binding",
    "source_checkpoint_binding",
    "r009_execution_failure",
    "previous_contract_binding",
    "replacement_contract_binding",
    "replacement_runner_binding",
    "reviewed_control_inputs",
    "required_reviewer",
    "executor",
    "claim_boundary",
    "required_findings",
    "stale_r001_preparation",
    "rejected_r002_review",
    "failed_r003_execution",
    "rejected_r004_review",
}
R004_AUTHORIZATION_FIELDS = AUTHORIZATION_FIELDS - {"rejected_r004_review"}
R004_ASSIGNMENT_FIELDS = ASSIGNMENT_FIELDS - {"rejected_r004_review"}
R003_AUTHORIZATION_FIELDS = R004_AUTHORIZATION_FIELDS - {"failed_r003_execution"}
R003_ASSIGNMENT_FIELDS = R004_ASSIGNMENT_FIELDS - {"failed_r003_execution"}
REVIEW_RESULT_FIELDS = {
    "schema_version",
    "document_id",
    "evidence_type",
    "round_id",
    "assignment_binding",
    "reviewer",
    "decision",
    "findings",
    "external_independence_claimed",
    "reviewed_at",
}
INDEPENDENT_REVIEW_FIELDS = REVIEW_RESULT_FIELDS | {"review_result_binding"}

CLAIM_BOUNDARY = copy.deepcopy(seq97.CLAIM_BOUNDARY)
CLAIM_BOUNDARY.update(
    {
        "goal_status_change_count": 0,
        "implementation_start_authorized": False,
    }
)
REVIEWED_CONTROL_SUCCESSOR_AUTHORITY_LABEL = (
    "NONCREDIT_REVIEWED_CONTROL_CONTEXT_ONLY"
)
REVIEWED_CONTROL_SUCCESSOR_CREDIT_BOUNDARY = {
    "implementation_completion_credit_delta": 0,
    "formal_test_credit_delta": 0,
    "actual_device_test_credit_delta": 0,
    "external_review_credit_delta": 0,
    "deployment_credit_delta": 0,
    "release_credit_delta": 0,
}
CURRENT_FOCUS = (
    "FP-048 R002 READY after seq98 R009 execution-failure correction; "
    "R009 -004 is consumed failed nonauthority and R010 is not yet run"
)
NEXT_ACTION = (
    "독립 검토된 R010 five-check preflight를 fresh -005 identity로 실행한다."
)
SCOPE = (
    "Add-only seq98 READY-to-READY correction after exact R009 -004 root "
    "regression failure; no product, formal, device, external, deployment, "
    "approval, or release credit."
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
    return seq90.canonical_json_bytes(left) == seq90.canonical_json_bytes(right)


def _parse_time(value: Any, label: str) -> datetime:
    require(isinstance(value, str), f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise StartGateExecutionCorrectionError(f"{label} is invalid") from exc
    require(parsed.tzinfo is not None, f"{label} lacks timezone")
    return parsed


def _is_final_binding(binding: Mapping[str, Any], path: Path) -> bool:
    return (
        set(binding) == {"path", "sha256", "byte_length"}
        and binding.get("path") == path.as_posix()
        and isinstance(binding.get("sha256"), str)
        and seq90.SHA256_RE.fullmatch(binding["sha256"]) is not None
        and binding["sha256"] != UNFROZEN_SHA256
        and type(binding.get("byte_length")) is int
        and binding["byte_length"] >= 0
    )


def _require_file_binding(binding: Any, path: Path, label: str) -> None:
    require(
        isinstance(binding, Mapping)
        and set(binding) == {"path", "sha256", "byte_length"}
        and binding.get("path") == path.as_posix()
        and isinstance(binding.get("sha256"), str)
        and seq90.SHA256_RE.fullmatch(binding["sha256"]) is not None
        and binding["sha256"] != UNFROZEN_SHA256
        and type(binding.get("byte_length")) is int
        and binding["byte_length"] >= 0,
        f"{label} differs",
    )


def _observed_binding(root: Path, path: Path) -> dict[str, Any]:
    return _binding(path, seq90._stable_read(root, path).raw)


def _stale_r001_preparation(root: Path) -> dict[str, Any]:
    root = seq90._safe_root(root)
    try:
        reads = {
            path: seq90._stable_read(root, path)
            for path in R001_STALE_PHYSICAL_PATHS
        }
    except Exception as exc:
        raise StartGateExecutionCorrectionError(
            "stale R001 preparation differs"
        ) from exc
    expected = {
        R001_AUTHORIZATION_REL: R001_AUTHORIZATION_BINDING,
        R001_REVIEW_ASSIGNMENT_REL: R001_REVIEW_ASSIGNMENT_BINDING,
    }
    for path, read in reads.items():
        try:
            metadata = (root / path).lstat()
            document = seq90.strict_json(read.raw, path.as_posix())
        except Exception as exc:
            raise StartGateExecutionCorrectionError(
                f"stale R001 preparation differs: {path}"
            ) from exc
        require(
            _binding(path, read.raw) == expected[path]
            and stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_nlink == 1
            and read.raw == seq90.canonical_json_bytes(document) + b"\n",
            f"stale R001 preparation differs: {path}",
        )
    try:
        authorization = seq90.strict_json(
            reads[R001_AUTHORIZATION_REL].raw,
            R001_AUTHORIZATION_REL.as_posix(),
        )
        assignment = seq90.strict_json(
            reads[R001_REVIEW_ASSIGNMENT_REL].raw,
            R001_REVIEW_ASSIGNMENT_REL.as_posix(),
        )
    except Exception as exc:
        raise StartGateExecutionCorrectionError(
            "stale R001 preparation authority differs"
        ) from exc
    require(
        authorization.get("document_id")
        == "WS-FP048-R002-SEQ98-99-AUTHORIZATION-20260827-001"
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ98-99-REVIEW-ASSIGNMENT-20260827-R001"
        and assignment.get("round_id") == "R001"
        and assignment.get("authorization_binding")
        != R001_AUTHORIZATION_BINDING,
        "stale R001 preparation authority differs",
    )
    require(
        all(not os.path.lexists(root / path) for path in R001_FORBIDDEN_OUTPUT_PATHS),
        "stale R001 review outputs must remain absent",
    )
    return {
        "round_id": "R001",
        "authority_status": "NONAUTHORITY_FAILED_PREPARATION",
        "authorization_binding": copy.deepcopy(R001_AUTHORIZATION_BINDING),
        "assignment_binding": copy.deepcopy(R001_REVIEW_ASSIGNMENT_BINDING),
        "review_result_present": False,
        "independent_review_present": False,
    }


def _rejected_r002_review(root: Path) -> dict[str, Any]:
    root = seq90._safe_root(root)
    stale_r001 = _stale_r001_preparation(root)
    try:
        reads = {
            path: seq90._stable_read(root, path)
            for path in R002_STALE_PHYSICAL_PATHS
        }
    except Exception as exc:
        raise StartGateExecutionCorrectionError(
            "rejected R002 review differs"
        ) from exc
    expected = {
        R002_AUTHORIZATION_REL: R002_AUTHORIZATION_BINDING,
        R002_REVIEW_ASSIGNMENT_REL: R002_REVIEW_ASSIGNMENT_BINDING,
    }
    documents: dict[Path, dict[str, Any]] = {}
    for path, read in reads.items():
        try:
            metadata = (root / path).lstat()
            document = seq90.strict_json(read.raw, path.as_posix())
        except Exception as exc:
            raise StartGateExecutionCorrectionError(
                f"rejected R002 review differs: {path}"
            ) from exc
        require(
            _binding(path, read.raw) == expected[path]
            and stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_nlink == 1
            and read.raw == seq90.canonical_json_bytes(document),
            f"rejected R002 review differs: {path}",
        )
        documents[path] = document
    authorization = documents[R002_AUTHORIZATION_REL]
    assignment = documents[R002_REVIEW_ASSIGNMENT_REL]
    require(
        authorization.get("document_id")
        == "WS-FP048-R002-SEQ98-99-AUTHORIZATION-20260827-R002"
        and authorization.get("authorization_status") == FINAL_AUTHORIZATION_STATUS
        and authorization.get("stale_r001_preparation") == stale_r001
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ98-99-REVIEW-ASSIGNMENT-20260827-R002"
        and assignment.get("round_id") == "R002"
        and assignment.get("candidate_status") == FINAL_CANDIDATE_STATUS
        and assignment.get("authorization_binding") == R002_AUTHORIZATION_BINDING
        and assignment.get("stale_r001_preparation") == stale_r001,
        "rejected R002 review authority differs",
    )
    require(
        all(not os.path.lexists(root / path) for path in R002_FORBIDDEN_OUTPUT_PATHS),
        "rejected R002 review outputs must remain absent",
    )
    return {
        "round_id": "R002",
        "authority_status": "NONAUTHORITY_REVIEW_REJECTED",
        "authorization_binding": copy.deepcopy(R002_AUTHORIZATION_BINDING),
        "assignment_binding": copy.deepcopy(R002_REVIEW_ASSIGNMENT_BINDING),
        "review_result_present": False,
        "independent_review_present": False,
        "finding_counts": copy.deepcopy(R002_REVIEW_FINDING_COUNTS),
    }


def _failed_r003_review_authority(root: Path) -> dict[str, Any]:
    root = seq90._safe_root(root)
    stale_r001 = _stale_r001_preparation(root)
    rejected_r002 = _rejected_r002_review(root)
    expected = {
        R003_AUTHORIZATION_REL: R003_AUTHORIZATION_BINDING,
        R003_REVIEW_ASSIGNMENT_REL: R003_REVIEW_ASSIGNMENT_BINDING,
        R003_REVIEW_RESULT_REL: R003_REVIEW_RESULT_BINDING,
        R003_INDEPENDENT_REVIEW_REL: R003_INDEPENDENT_REVIEW_BINDING,
    }
    try:
        reads = {
            path: seq90._stable_read(root, path) for path in R003_PHYSICAL_PATHS
        }
        documents = {
            path: seq90.strict_json(read.raw, path.as_posix())
            for path, read in reads.items()
        }
    except Exception as exc:
        raise StartGateExecutionCorrectionError(
            "failed R003 execution authority differs"
        ) from exc
    for path, read in reads.items():
        metadata = (root / path).lstat()
        require(
            _binding(path, read.raw) == expected[path]
            and stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_uid == os.geteuid()
            and metadata.st_nlink == 1
            and read.raw == seq90.canonical_json_bytes(documents[path]),
            f"failed R003 execution authority differs: {path}",
        )
    authorization = documents[R003_AUTHORIZATION_REL]
    assignment = documents[R003_REVIEW_ASSIGNMENT_REL]
    result = documents[R003_REVIEW_RESULT_REL]
    independent = documents[R003_INDEPENDENT_REVIEW_REL]
    result_at = _parse_time(result.get("reviewed_at"), "R003 review result time")
    independent_at = _parse_time(
        independent.get("reviewed_at"), "R003 independent review time"
    )
    require(
        set(authorization) == R003_AUTHORIZATION_FIELDS
        and authorization.get("document_id")
        == "WS-FP048-R002-SEQ98-99-AUTHORIZATION-20260827-R003"
        and authorization.get("authorization_status") == FINAL_AUTHORIZATION_STATUS
        and authorization.get("stale_r001_preparation") == stale_r001
        and authorization.get("rejected_r002_review") == rejected_r002
        and set(assignment) == R003_ASSIGNMENT_FIELDS
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ98-99-REVIEW-ASSIGNMENT-20260827-R003"
        and assignment.get("round_id") == "R003"
        and assignment.get("candidate_status") == FINAL_CANDIDATE_STATUS
        and assignment.get("authorization_binding") == R003_AUTHORIZATION_BINDING
        and assignment.get("required_reviewer") == R003_REVIEWER
        and assignment.get("required_findings") == {"P0": 0, "P1": 0, "P2": 0}
        and assignment.get("stale_r001_preparation") == stale_r001
        and assignment.get("rejected_r002_review") == rejected_r002
        and set(result) == REVIEW_RESULT_FIELDS
        and result.get("document_id")
        == "WS-FP048-R002-SEQ98-99-REVIEW-RESULT-20260827-R003"
        and result.get("round_id") == "R003"
        and result.get("assignment_binding") == R003_REVIEW_ASSIGNMENT_BINDING
        and result.get("reviewer") == R003_REVIEWER
        and result.get("decision") == "APPROVED"
        and result.get("findings") == {"P0": 0, "P1": 0, "P2": 0}
        and result.get("external_independence_claimed") is False
        and result.get("reviewed_at") == R003_REVIEW_RESULT_AT
        and set(independent) == INDEPENDENT_REVIEW_FIELDS
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ98-99-INDEPENDENT-REVIEW-20260827-R003"
        and independent.get("round_id") == "R003"
        and independent.get("assignment_binding")
        == R003_REVIEW_ASSIGNMENT_BINDING
        and independent.get("review_result_binding") == R003_REVIEW_RESULT_BINDING
        and independent.get("reviewer") == R003_REVIEWER
        and independent.get("decision") == "APPROVED"
        and independent.get("findings") == {"P0": 0, "P1": 0, "P2": 0}
        and independent.get("external_independence_claimed") is False
        and independent.get("reviewed_at") == R003_INDEPENDENT_REVIEW_AT
        and result_at < independent_at,
        "failed R003 execution review authority differs",
    )
    return {
        "round_id": "R003",
        "authority_status": "NONAUTHORITY_EXECUTION_FAILED_PRE_CAS",
        "authorization_binding": copy.deepcopy(R003_AUTHORIZATION_BINDING),
        "review_binding": {
            "assignment": copy.deepcopy(R003_REVIEW_ASSIGNMENT_BINDING),
            "review_result": copy.deepcopy(R003_REVIEW_RESULT_BINDING),
            "independent_review": copy.deepcopy(R003_INDEPENDENT_REVIEW_BINDING),
        },
        "review_decision": "APPROVED",
        "finding_counts": {"P0": 0, "P1": 0, "P2": 0},
        "review_chronology": {
            "review_result_at": R003_REVIEW_RESULT_AT,
            "independent_review_at": R003_INDEPENDENT_REVIEW_AT,
        },
        "physical_file_mode": "0600",
        "physical_link_count": 1,
        "observation_basis": "EXECUTOR_OBSERVED_TERMINAL_OUTPUT_UNSEALED",
        "observed_command": "--write",
        "failure_phase": "AT_COMMIT_GUARD_BEFORE_REPLACE",
        "failure_reason": R003_FAILURE_REASON,
        "observation_timestamp_available": False,
        "checkpoint_published": False,
        "source_checkpoint_binding": _source_checkpoint_binding(
            r009_execution_failure_binding(root)
        ),
    }


def _failed_r003_execution_for_source(
    root: Path,
    source_raw: bytes,
    source: Mapping[str, Any],
) -> dict[str, Any]:
    root = seq90._safe_root(root)
    observation = _failed_r003_review_authority(root)
    require(
        _binding(CHECKPOINT_REL, source_raw)
        == {
            "path": CHECKPOINT_REL.as_posix(),
            "sha256": SOURCE_CHECKPOINT_SHA256,
            "byte_length": SOURCE_CHECKPOINT_BYTE_LENGTH,
        },
        "failed R003 execution retained source differs",
    )
    require_exact_seq97_source(source_raw, source, root)
    return observation


def _failed_r003_execution(root: Path) -> dict[str, Any]:
    root = seq90._safe_root(root)
    source_read = seq90._stable_read(root, CHECKPOINT_REL)
    require(
        stat.S_IMODE(source_read.identity.mode) == SOURCE_CHECKPOINT_MODE,
        "failed R003 execution live source mode differs",
    )
    source = seq90.strict_json(source_read.raw, CHECKPOINT_REL.as_posix())
    return _failed_r003_execution_for_source(root, source_read.raw, source)


def _rejected_r004_review_for_source(
    root: Path,
    source_raw: bytes,
    source: Mapping[str, Any],
) -> dict[str, Any]:
    root = seq90._safe_root(root)
    stale_r001 = _stale_r001_preparation(root)
    rejected_r002 = _rejected_r002_review(root)
    failed_r003 = _failed_r003_execution_for_source(root, source_raw, source)
    expected = {
        R004_AUTHORIZATION_REL: R004_AUTHORIZATION_BINDING,
        R004_REVIEW_ASSIGNMENT_REL: R004_REVIEW_ASSIGNMENT_BINDING,
    }
    try:
        reads = {
            path: seq90._stable_read(root, path)
            for path in R004_STALE_PHYSICAL_PATHS
        }
        documents = {
            path: seq90.strict_json(read.raw, path.as_posix())
            for path, read in reads.items()
        }
    except Exception as exc:
        raise StartGateExecutionCorrectionError(
            "rejected R004 review differs"
        ) from exc
    for path, read in reads.items():
        metadata = (root / path).lstat()
        require(
            _binding(path, read.raw) == expected[path]
            and stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_uid == os.geteuid()
            and metadata.st_nlink == 1
            and read.raw == seq90.canonical_json_bytes(documents[path]),
            f"rejected R004 review differs: {path}",
        )
    authorization = documents[R004_AUTHORIZATION_REL]
    assignment = documents[R004_REVIEW_ASSIGNMENT_REL]
    rows = assignment.get("reviewed_control_inputs")
    require(
        set(authorization) == R004_AUTHORIZATION_FIELDS
        and authorization.get("document_id")
        == "WS-FP048-R002-SEQ98-99-AUTHORIZATION-20260827-R004"
        and authorization.get("authorization_status") == FINAL_AUTHORIZATION_STATUS
        and authorization.get("required_independent_reviewer") == R004_REVIEWER
        and authorization.get("stale_r001_preparation") == stale_r001
        and authorization.get("rejected_r002_review") == rejected_r002
        and authorization.get("failed_r003_execution") == failed_r003
        and set(assignment) == R004_ASSIGNMENT_FIELDS
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ98-99-REVIEW-ASSIGNMENT-20260827-R004"
        and assignment.get("round_id") == "R004"
        and assignment.get("candidate_status") == FINAL_CANDIDATE_STATUS
        and assignment.get("authorization_binding")
        == R004_AUTHORIZATION_BINDING
        and assignment.get("required_reviewer") == R004_REVIEWER
        and assignment.get("required_findings") == {"P0": 0, "P1": 0, "P2": 0}
        and assignment.get("stale_r001_preparation") == stale_r001
        and assignment.get("rejected_r002_review") == rejected_r002
        and assignment.get("failed_r003_execution") == failed_r003
        and isinstance(rows, list)
        and len(rows) == len(REVIEWED_CONTROL_PATHS)
        and [row.get("path") for row in rows if isinstance(row, dict)]
        == [path.as_posix() for path in REVIEWED_CONTROL_PATHS],
        "rejected R004 review authority differs",
    )
    require(
        all(not os.path.lexists(root / path) for path in R004_FORBIDDEN_OUTPUT_PATHS),
        "rejected R004 review outputs must remain absent",
    )
    return {
        "round_id": "R004",
        "authority_status": "NONAUTHORITY_REVIEW_REJECTED",
        "authorization_binding": copy.deepcopy(R004_AUTHORIZATION_BINDING),
        "assignment_binding": copy.deepcopy(R004_REVIEW_ASSIGNMENT_BINDING),
        "review_result_present": False,
        "independent_review_present": False,
        "finding_counts": copy.deepcopy(R004_REVIEW_FINDING_COUNTS),
    }


def _rejected_r004_review(root: Path) -> dict[str, Any]:
    root = seq90._safe_root(root)
    source_read = seq90._stable_read(root, CHECKPOINT_REL)
    require(
        stat.S_IMODE(source_read.identity.mode) == SOURCE_CHECKPOINT_MODE,
        "rejected R004 review live source mode differs",
    )
    source = seq90.strict_json(source_read.raw, CHECKPOINT_REL.as_posix())
    return _rejected_r004_review_for_source(root, source_read.raw, source)


def _source_checkpoint_binding(
    failure: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "path": CHECKPOINT_REL.as_posix(),
        "sha256": SOURCE_CHECKPOINT_SHA256,
        "byte_length": SOURCE_CHECKPOINT_BYTE_LENGTH,
        "sequence": SOURCE_SEQUENCE,
        "tail_event_id": SOURCE_EVENT_ID,
        "tail_event_sha256": SOURCE_EVENT_SHA256,
        "r009_execution_failure": copy.deepcopy(
            dict(failure) if failure is not None else r009_execution_failure_binding()
        ),
    }


def _projected_transition() -> dict[str, Any]:
    return {
        "execution_correction_sequence": CORRECTION_SEQUENCE,
        "execution_correction_transition": "READY_TO_READY",
        "started_event_id": STARTED_EVENT_ID,
        "started_sequence": STARTED_SEQUENCE,
        "started_transition": "READY_TO_IN_PROGRESS_AFTER_FRESH_R010_PASS",
    }


def r009_execution_failure_binding(root: Path = ROOT) -> dict[str, Any]:
    """Return the exact consumed failed R009 -004 evidence as nonauthority."""

    root = seq90._safe_root(root)
    gate = root / R009_GATE_REL
    metadata = gate.lstat()
    require(
        stat.S_ISDIR(metadata.st_mode)
        and stat.S_IMODE(metadata.st_mode) == 0o700,
        "R009 failed namespace mode or type differs",
    )
    require(
        not os.path.lexists(root / R009_RECEIPT_REL),
        "R009 failed namespace unexpectedly acquired a receipt",
    )
    expected_names = {Path(row["path"]).name for row in R009_LOG_BINDINGS}
    require(
        {entry.name for entry in gate.iterdir()} == expected_names,
        "R009 failed namespace inventory differs",
    )
    observed_logs: list[dict[str, Any]] = []
    root_log_raw = b""
    for expected in R009_LOG_BINDINGS:
        path = Path(expected["path"])
        observed = seq90._stable_read(root, path)
        log_metadata = (root / path).lstat()
        row = {
            **_binding(path, observed.raw),
            "mode": f"{stat.S_IMODE(log_metadata.st_mode):04o}",
        }
        require(
            row == expected
            and stat.S_ISREG(log_metadata.st_mode)
            and log_metadata.st_nlink == 1,
            f"R009 failed log differs: {path}",
        )
        if path.name == "04-ROOT_FP048_R002_CONTROL_REGRESSION.log":
            root_log_raw = observed.raw
        observed_logs.append(row)
    require(
        b"17 failed, 63 passed in 8.04s" in root_log_raw
        and b"exact published seq96 source differs" in root_log_raw
        and TEST_REL.name.replace(
            "test_apply_walksafe_fp048_r002_start_gate_execution_correction_seq98_20260827.py",
            "test_apply_walksafe_fp048_r002_start_gate_snapshot_hygiene_correction_seq97_20260826.py",
        ).encode() in root_log_raw,
        "R009 root regression summary differs",
    )
    value = {
        "event_id": "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-004",
        "contract_id": R009_CONTRACT_BINDING["contract_id"],
        "contract_version": R009_CONTRACT_BINDING["contract_version"],
        "directory": R009_GATE_REL.as_posix(),
        "receipt_path": R009_RECEIPT_REL.as_posix(),
        "status": "FAILED_BEFORE_RECEIPT",
        "authority_status": "NONAUTHORITY",
        "event_identity_status": "CONSUMED_FAILED_NO_RECEIPT",
        "namespace_present": True,
        "receipt_present": False,
        "failed_check_id": "ROOT_FP048_R002_CONTROL_REGRESSION",
        "exit_code": 1,
        "error": "ROOT_FP048_R002_CONTROL_REGRESSION failed with exit code 1",
        "reason_code": CORRECTION_REASON_CODE,
        "replacement_event_id": STARTED_EVENT_ID,
        "logs": observed_logs,
        "root_regression": {
            "failed": 17,
            "passed": 63,
            "scope_mismatch": "PRE_SEQ97_ONLY_TESTS_INCLUDED",
        },
    }
    require(len(value) == 17, "R009 failure binding field count differs")
    return value


def require_exact_seq97_source(
    raw: bytes,
    checkpoint: Mapping[str, Any],
    root: Path = ROOT,
) -> None:
    root = seq90._safe_root(root)
    canonical_checkpoint_raw = checkpoint_json_bytes(checkpoint)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256
        and raw == canonical_checkpoint_raw
        and isinstance(history, list)
        and len(history) == SOURCE_SEQUENCE
        and isinstance(history[-1], dict)
        and type(history[-1].get("sequence")) is int
        and history[-1].get("sequence") == SOURCE_SEQUENCE
        and history[-1].get("event_id") == SOURCE_EVENT_ID
        and history[-1].get("event_sha256") == SOURCE_EVENT_SHA256
        and history[-1].get("event_sha256") == continuation.event_sha256(history[-1])
        and state.get("transition_history_anchor_sha256") == SOURCE_EVENT_SHA256
        and state.get("goal_status") == "READY"
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values(),
        "exact published seq97 source differs",
    )
    cache = _SEQ97_SOURCE_VALIDATION_CALL_CACHE.get()
    key = (root.as_posix(), len(canonical_checkpoint_raw), sha256_bytes(raw))
    if cache is not None:
        cached = cache.get(key, _SEQ97_SOURCE_VALIDATION_CACHE_MISS)
        if cached is not _SEQ97_SOURCE_VALIDATION_CACHE_MISS:
            if cached is not None:
                raise cached
            return
    try:
        require(
            seq97.canonical_seq97_checkpoint_bytes(root, checkpoint) == raw,
            "seq97 historical/inverse authority differs",
        )
    except Exception as exc:
        if cache is not None:
            cache[key] = exc
        raise
    if cache is not None:
        cache[key] = None


def load_exact_seq97_source(root: Path = ROOT) -> tuple[bytes, dict[str, Any]]:
    root = seq90._safe_root(root)
    observed = seq90._stable_read(root, CHECKPOINT_REL)
    checkpoint = seq90.strict_json(observed.raw, CHECKPOINT_REL.as_posix())
    require_exact_seq97_source(observed.raw, checkpoint, root)
    require(
        stat.S_IMODE((root / CHECKPOINT_REL).lstat().st_mode)
        == SOURCE_CHECKPOINT_MODE,
        "seq97 checkpoint mode differs",
    )
    return observed.raw, checkpoint


def pins_are_final() -> bool:
    return (
        isinstance(R010_CANONICAL_SHA256, str)
        and seq90.SHA256_RE.fullmatch(R010_CANONICAL_SHA256) is not None
        and R010_CANONICAL_SHA256 != UNFROZEN_SHA256
        and all(
            _is_final_binding(binding, path)
            for path, binding in FIXED_REVIEWED_CONTROL_BINDINGS.items()
        )
    )


def _r010_contract_shape(binding: Any, *, allow_provisional: bool) -> None:
    require(
        isinstance(binding, Mapping)
        and set(binding)
        == {
            "schema_version",
            "document_id",
            "path",
            "file_sha256",
            "contract_id",
            "contract_version",
            "canonical_contract_sha256",
        }
        and binding.get("schema_version") == "1.2"
        and binding.get("document_id") == R010_DOCUMENT_ID
        and binding.get("path") == R010_CONTRACT_REL.as_posix()
        and binding.get("contract_id") == R010_CONTRACT_ID
        and binding.get("contract_version") == R010_CONTRACT_VERSION
        and isinstance(binding.get("file_sha256"), str)
        and seq90.SHA256_RE.fullmatch(binding["file_sha256"]) is not None
        and isinstance(binding.get("canonical_contract_sha256"), str)
        and seq90.SHA256_RE.fullmatch(binding["canonical_contract_sha256"])
        is not None
        and (
            allow_provisional
            or (
                binding["file_sha256"] != UNFROZEN_SHA256
                and binding["canonical_contract_sha256"] != UNFROZEN_SHA256
            )
        ),
        "R010 contract binding differs",
    )


def r010_contract_binding(
    root: Path = ROOT,
    *,
    allow_provisional: bool = False,
) -> dict[str, Any]:
    root = seq90._safe_root(root)
    expected = FIXED_REVIEWED_CONTROL_BINDINGS[R010_CONTRACT_REL]
    if not _is_final_binding(expected, R010_CONTRACT_REL):
        require(allow_provisional, "R010 contract pin is not final")
        value = {
            "schema_version": "1.2",
            "document_id": R010_DOCUMENT_ID,
            "path": R010_CONTRACT_REL.as_posix(),
            "file_sha256": UNFROZEN_SHA256,
            "contract_id": R010_CONTRACT_ID,
            "contract_version": R010_CONTRACT_VERSION,
            "canonical_contract_sha256": UNFROZEN_SHA256,
        }
        _r010_contract_shape(value, allow_provisional=True)
        return value
    observed = _observed_binding(root, R010_CONTRACT_REL)
    require(observed == expected, "R010 contract file differs")
    value = {
        "schema_version": "1.2",
        "document_id": R010_DOCUMENT_ID,
        "path": R010_CONTRACT_REL.as_posix(),
        "file_sha256": observed["sha256"],
        "contract_id": R010_CONTRACT_ID,
        "contract_version": R010_CONTRACT_VERSION,
        "canonical_contract_sha256": R010_CANONICAL_SHA256,
    }
    _r010_contract_shape(value, allow_provisional=False)
    return value


def r010_runner_binding(
    root: Path = ROOT,
    *,
    allow_provisional: bool = False,
) -> dict[str, Any]:
    root = seq90._safe_root(root)
    expected = FIXED_REVIEWED_CONTROL_BINDINGS[R010_RUNNER_REL]
    if not _is_final_binding(expected, R010_RUNNER_REL):
        require(allow_provisional, "R010 runner pin is not final")
        return copy.deepcopy(expected)
    observed = _observed_binding(root, R010_RUNNER_REL)
    require(observed == expected, "R010 runner binding differs")
    return observed


def _reviewed_control_bindings(
    root: Path,
    *,
    allow_provisional: bool,
) -> list[dict[str, Any]]:
    root = seq90._safe_root(root)
    rows: list[dict[str, Any]] = []
    for path in REVIEWED_CONTROL_PATHS:
        if path in DYNAMIC_REVIEWED_CONTROL_PATHS:
            if os.path.lexists(root / path):
                rows.append(_observed_binding(root, path))
            else:
                require(allow_provisional, f"reviewed input is absent: {path}")
                rows.append(_provisional_binding(path))
            continue
        expected = FIXED_REVIEWED_CONTROL_BINDINGS[path]
        if not _is_final_binding(expected, path):
            require(allow_provisional, f"reviewed pin is not final: {path}")
            rows.append(copy.deepcopy(expected))
            continue
        observed = _observed_binding(root, path)
        require(observed == expected, f"reviewed pin differs: {path}")
        rows.append(observed)
    return rows


def _validated_reviewed_control_rows(value: Any) -> list[dict[str, Any]]:
    require(
        isinstance(value, list)
        and len(value) == len(REVIEWED_CONTROL_PATHS)
        and all(isinstance(row, Mapping) for row in value),
        "seq98 reviewed cohort differs",
    )
    rows = [dict(row) for row in value]
    expected_paths = [path.as_posix() for path in REVIEWED_CONTROL_PATHS]
    require(
        [row.get("path") for row in rows] == expected_paths
        and len({row.get("path") for row in rows}) == len(rows),
        "seq98 reviewed cohort differs",
    )
    for path, row in zip(REVIEWED_CONTROL_PATHS, rows, strict=True):
        _require_file_binding(row, path, f"seq98 reviewed input {path}")
        if path in FIXED_REVIEWED_CONTROL_BINDINGS:
            require(
                row == FIXED_REVIEWED_CONTROL_BINDINGS[path],
                f"seq98 fixed reviewed input differs: {path}",
            )
    return copy.deepcopy(rows)


def _candidate_status(allow_provisional: bool) -> str:
    final = pins_are_final()
    require(final or allow_provisional, "seq98 reviewed pins are not final")
    return FINAL_CANDIDATE_STATUS if final else PROVISIONAL_STATUS


def _authorization_status(allow_provisional: bool) -> str:
    final = pins_are_final()
    require(final or allow_provisional, "seq98 reviewed pins are not final")
    return FINAL_AUTHORIZATION_STATUS if final else PROVISIONAL_STATUS


def build_authorization(
    root: Path,
    source: Mapping[str, Any],
    *,
    allow_provisional: bool = False,
) -> bytes:
    root = seq90._safe_root(root)
    require_exact_seq97_source(checkpoint_json_bytes(source), source, root)
    source_raw = checkpoint_json_bytes(source)
    failure = r009_execution_failure_binding(root)
    stale_r001 = _stale_r001_preparation(root)
    rejected_r002 = _rejected_r002_review(root)
    failed_r003 = _failed_r003_execution_for_source(root, source_raw, source)
    rejected_r004 = _rejected_r004_review_for_source(root, source_raw, source)
    value = {
        "schema_version": "1.0",
        "document_id": AUTHORIZATION_DOCUMENT_ID,
        "evidence_type": "USER_CONTINUATION_AUTHORIZATION",
        "authorization_status": _authorization_status(allow_provisional),
        "goal_id": GOAL_ID,
        "source_checkpoint_binding": _source_checkpoint_binding(failure),
        "r009_execution_failure": failure,
        "previous_contract_binding": copy.deepcopy(R009_CONTRACT_BINDING),
        "replacement_contract_binding": r010_contract_binding(
            root, allow_provisional=allow_provisional
        ),
        "contract_correction_event_id": CORRECTION_EVENT_ID,
        "projected_transition": _projected_transition(),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "authorized_executor": copy.deepcopy(EXECUTOR),
        "required_independent_reviewer": copy.deepcopy(REVIEWER),
        "stale_r001_preparation": stale_r001,
        "rejected_r002_review": rejected_r002,
        "failed_r003_execution": failed_r003,
        "rejected_r004_review": rejected_r004,
    }
    require(set(value) == AUTHORIZATION_FIELDS, "seq98 authorization fields differ")
    return seq90.canonical_json_bytes(value)


def build_review_assignment(
    root: Path,
    source: Mapping[str, Any],
    *,
    allow_provisional: bool = False,
) -> bytes:
    root = seq90._safe_root(root)
    stale_r001 = _stale_r001_preparation(root)
    rejected_r002 = _rejected_r002_review(root)
    source_raw = checkpoint_json_bytes(source)
    failed_r003 = _failed_r003_execution_for_source(root, source_raw, source)
    rejected_r004 = _rejected_r004_review_for_source(root, source_raw, source)
    authorization = build_authorization(
        root, source, allow_provisional=allow_provisional
    )
    failure = r009_execution_failure_binding(root)
    value = {
        "schema_version": "1.0",
        "document_id": REVIEW_ASSIGNMENT_DOCUMENT_ID,
        "evidence_type": "TRANSITION_CONTROL_REVIEW_ASSIGNMENT",
        "round_id": REVIEW_ROUND_ID,
        "candidate_status": _candidate_status(allow_provisional),
        "goal_id": GOAL_ID,
        "authorization_binding": _binding(AUTHORIZATION_REL, authorization),
        "source_checkpoint_binding": _source_checkpoint_binding(failure),
        "r009_execution_failure": failure,
        "previous_contract_binding": copy.deepcopy(R009_CONTRACT_BINDING),
        "replacement_contract_binding": r010_contract_binding(
            root, allow_provisional=allow_provisional
        ),
        "replacement_runner_binding": r010_runner_binding(
            root, allow_provisional=allow_provisional
        ),
        "reviewed_control_inputs": _reviewed_control_bindings(
            root, allow_provisional=allow_provisional
        ),
        "required_reviewer": copy.deepcopy(REVIEWER),
        "executor": copy.deepcopy(EXECUTOR),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "required_findings": {"P0": 0, "P1": 0, "P2": 0},
        "stale_r001_preparation": stale_r001,
        "rejected_r002_review": rejected_r002,
        "failed_r003_execution": failed_r003,
        "rejected_r004_review": rejected_r004,
    }
    require(set(value) == ASSIGNMENT_FIELDS, "seq98 assignment fields differ")
    return seq90.canonical_json_bytes(value)


def prepare_review_manifest(
    root: Path = ROOT,
    *,
    allow_provisional: bool = False,
) -> dict[str, Any]:
    _raw, source = load_exact_seq97_source(root)
    authorization = build_authorization(
        root, source, allow_provisional=allow_provisional
    )
    assignment = build_review_assignment(
        root, source, allow_provisional=allow_provisional
    )
    return {
        "publishable": pins_are_final(),
        "candidate_status": _candidate_status(allow_provisional),
        "authorization": _binding(AUTHORIZATION_REL, authorization),
        "assignment": _binding(REVIEW_ASSIGNMENT_REL, assignment),
        "review_root": REVIEW_ROOT.as_posix(),
        "required_reviewer": copy.deepcopy(REVIEWER),
        "required_absent_before_review": [
            REVIEW_RESULT_REL.as_posix(),
            INDEPENDENT_REVIEW_REL.as_posix(),
        ],
    }


def _load_physical_authorization(
    root: Path,
    source: Mapping[str, Any],
    *,
    require_live_inputs: bool,
    replacement_contract_binding: Mapping[str, Any],
) -> dict[str, Any]:
    stale_r001 = _stale_r001_preparation(root)
    rejected_r002 = _rejected_r002_review(root)
    source_raw = checkpoint_json_bytes(source)
    failed_r003 = _failed_r003_execution_for_source(root, source_raw, source)
    rejected_r004 = _rejected_r004_review_for_source(root, source_raw, source)
    raw = seq90._stable_read(root, AUTHORIZATION_REL).raw
    document = seq90.strict_json(raw, AUTHORIZATION_REL.as_posix())
    require(
        raw == seq90.canonical_json_bytes(document)
        and stat.S_IMODE((root / AUTHORIZATION_REL).lstat().st_mode) == 0o600
        and set(document) == AUTHORIZATION_FIELDS,
        "seq98 authorization bytes, mode, or fields differ",
    )
    if require_live_inputs:
        require(
            raw == build_authorization(root, source),
            "seq98 authorization differs",
        )
    failure = r009_execution_failure_binding(root)
    require(
        document.get("schema_version") == "1.0"
        and document.get("document_id") == AUTHORIZATION_DOCUMENT_ID
        and document.get("evidence_type") == "USER_CONTINUATION_AUTHORIZATION"
        and document.get("authorization_status") == FINAL_AUTHORIZATION_STATUS
        and document.get("goal_id") == GOAL_ID
        and _strict_json_equal(
            document.get("source_checkpoint_binding"),
            _source_checkpoint_binding(failure),
        )
        and _strict_json_equal(document.get("r009_execution_failure"), failure)
        and _strict_json_equal(
            document.get("previous_contract_binding"), R009_CONTRACT_BINDING
        )
        and _strict_json_equal(
            document.get("replacement_contract_binding"),
            replacement_contract_binding,
        )
        and document.get("contract_correction_event_id") == CORRECTION_EVENT_ID
        and document.get("projected_transition") == _projected_transition()
        and document.get("claim_boundary") == CLAIM_BOUNDARY
        and document.get("authorized_executor") == EXECUTOR
        and document.get("required_independent_reviewer") == REVIEWER
        and document.get("stale_r001_preparation") == stale_r001
        and document.get("rejected_r002_review") == rejected_r002
        and document.get("failed_r003_execution") == failed_r003
        and document.get("rejected_r004_review") == rejected_r004,
        "seq98 authorization authority differs",
    )
    return _binding(AUTHORIZATION_REL, raw)


def _load_physical_review(
    root: Path,
    source: Mapping[str, Any],
    *,
    require_live_inputs: bool,
    authorization_binding_value: Mapping[str, Any],
    replacement_contract_binding: Mapping[str, Any],
    replacement_runner_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], datetime, datetime]:
    stale_r001 = _stale_r001_preparation(root)
    rejected_r002 = _rejected_r002_review(root)
    source_raw = checkpoint_json_bytes(source)
    failed_r003 = _failed_r003_execution_for_source(root, source_raw, source)
    rejected_r004 = _rejected_r004_review_for_source(root, source_raw, source)
    raws = {path: seq90._stable_read(root, path).raw for path in REVIEW_PATHS}
    documents = {
        path: seq90.strict_json(raw, path.as_posix()) for path, raw in raws.items()
    }
    assignment = documents[REVIEW_ASSIGNMENT_REL]
    result = documents[REVIEW_RESULT_REL]
    independent = documents[INDEPENDENT_REVIEW_REL]
    assignment_binding = _binding(REVIEW_ASSIGNMENT_REL, raws[REVIEW_ASSIGNMENT_REL])
    result_binding = _binding(REVIEW_RESULT_REL, raws[REVIEW_RESULT_REL])
    review_binding = {
        "assignment": assignment_binding,
        "review_result": result_binding,
        "independent_review": _binding(
            INDEPENDENT_REVIEW_REL, raws[INDEPENDENT_REVIEW_REL]
        ),
    }
    require(
        all(
            raw == seq90.canonical_json_bytes(documents[path])
            and stat.S_IMODE((root / path).lstat().st_mode) == 0o600
            for path, raw in raws.items()
        ),
        "seq98 review bytes or modes differ",
    )
    if require_live_inputs:
        require(
            raws[REVIEW_ASSIGNMENT_REL] == build_review_assignment(root, source),
            "seq98 review assignment differs",
        )
    failure = r009_execution_failure_binding(root)
    require(
        set(assignment) == ASSIGNMENT_FIELDS
        and set(result) == REVIEW_RESULT_FIELDS
        and set(independent) == INDEPENDENT_REVIEW_FIELDS
        and assignment.get("schema_version") == "1.0"
        and assignment.get("document_id") == REVIEW_ASSIGNMENT_DOCUMENT_ID
        and assignment.get("evidence_type")
        == "TRANSITION_CONTROL_REVIEW_ASSIGNMENT"
        and assignment.get("round_id") == REVIEW_ROUND_ID
        and assignment.get("candidate_status") == FINAL_CANDIDATE_STATUS
        and assignment.get("goal_id") == GOAL_ID
        and assignment.get("authorization_binding")
        == authorization_binding_value
        and _strict_json_equal(
            assignment.get("source_checkpoint_binding"),
            _source_checkpoint_binding(failure),
        )
        and _strict_json_equal(assignment.get("r009_execution_failure"), failure)
        and _strict_json_equal(
            assignment.get("previous_contract_binding"), R009_CONTRACT_BINDING
        )
        and _strict_json_equal(
            assignment.get("replacement_contract_binding"),
            replacement_contract_binding,
        )
        and _strict_json_equal(
            assignment.get("replacement_runner_binding"),
            replacement_runner_binding,
        )
        and assignment.get("required_reviewer") == REVIEWER
        and assignment.get("executor") == EXECUTOR
        and assignment.get("claim_boundary") == CLAIM_BOUNDARY
        and assignment.get("required_findings") == {"P0": 0, "P1": 0, "P2": 0}
        and assignment.get("stale_r001_preparation") == stale_r001
        and assignment.get("rejected_r002_review") == rejected_r002
        and assignment.get("failed_r003_execution") == failed_r003
        and assignment.get("rejected_r004_review") == rejected_r004
        and result.get("schema_version") == "1.0"
        and result.get("document_id") == REVIEW_RESULT_DOCUMENT_ID
        and result.get("evidence_type") == "TRANSITION_CONTROL_REVIEW_RESULT"
        and result.get("round_id") == REVIEW_ROUND_ID
        and result.get("assignment_binding") == assignment_binding
        and result.get("reviewer") == REVIEWER
        and result.get("decision") == "APPROVED"
        and result.get("findings") == {"P0": 0, "P1": 0, "P2": 0}
        and result.get("external_independence_claimed") is False
        and independent.get("schema_version") == "1.0"
        and independent.get("document_id") == INDEPENDENT_REVIEW_DOCUMENT_ID
        and independent.get("evidence_type")
        == "TRANSITION_CONTROL_INDEPENDENT_REVIEW"
        and independent.get("round_id") == REVIEW_ROUND_ID
        and independent.get("assignment_binding") == assignment_binding
        and independent.get("review_result_binding") == result_binding
        and independent.get("reviewer") == REVIEWER
        and independent.get("decision") == "APPROVED"
        and independent.get("findings") == {"P0": 0, "P1": 0, "P2": 0}
        and independent.get("external_independence_claimed") is False,
        "seq98 independent review authority differs",
    )
    _validated_reviewed_control_rows(assignment.get("reviewed_control_inputs"))
    result_at = _parse_time(result.get("reviewed_at"), "seq98 review result time")
    independent_at = _parse_time(
        independent.get("reviewed_at"), "seq98 independent review time"
    )
    require(result_at < independent_at, "seq98 review chronology differs")
    return review_binding, result_at, independent_at


def project_seq98(
    root: Path,
    source: Mapping[str, Any],
    *,
    managed_paths: Sequence[str],
    path_set_sha256: str,
    content_set_sha256: str,
    occurred_at: str,
    authorization_binding_value: Mapping[str, Any],
    review_binding: Mapping[str, Any],
    r009_failure_binding: Mapping[str, Any],
    replacement_contract_binding: Mapping[str, Any],
    replacement_runner_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    root = seq90._safe_root(root)
    require_exact_seq97_source(checkpoint_json_bytes(source), source, root)
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
        and not any(path.startswith(GATE_EVIDENCE_PREFIX) for path in paths)
        and path_set_sha256
        == hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest()
        and isinstance(content_set_sha256, str)
        and seq90.SHA256_RE.fullmatch(content_set_sha256) is not None,
        "seq98 managed paths differ",
    )
    require(
        _strict_json_equal(
            r009_failure_binding, r009_execution_failure_binding(root)
        ),
        "seq98 R009 failure binding differs",
    )
    require(
        isinstance(review_binding, Mapping)
        and set(review_binding)
        == {"assignment", "review_result", "independent_review"},
        "seq98 review binding differs",
    )
    for binding, path, label in (
        (authorization_binding_value, AUTHORIZATION_REL, "authorization"),
        (review_binding["assignment"], REVIEW_ASSIGNMENT_REL, "assignment"),
        (review_binding["review_result"], REVIEW_RESULT_REL, "review result"),
        (
            review_binding["independent_review"],
            INDEPENDENT_REVIEW_REL,
            "independent review",
        ),
    ):
        _require_file_binding(binding, path, f"seq98 {label} binding")
    _r010_contract_shape(replacement_contract_binding, allow_provisional=False)
    _require_file_binding(
        replacement_runner_binding,
        R010_RUNNER_REL,
        "seq98 R010 runner binding",
    )
    occurred = _parse_time(occurred_at, "seq98 occurred_at")
    source_event = source["goal_execution"]["transition_history"][-1]
    require(
        source_event.get("contract_supersession", {}).get(
            "replacement_contract_binding"
        )
        == R009_CONTRACT_BINDING
        and source_event.get("start_gate_runner_binding") == R009_RUNNER_BINDING,
        "seq98 R009 predecessor authority differs",
    )
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
            "current_epic": "EPIC-03 / FP-048 R002 R009 execution correction",
            "last_updated_by_work_item": WORK_ITEM_ID,
            "last_verification_status": "SEQ98_R010_CORRECTION_REVIEWED_ZERO_CREDIT",
            "next_single_action": NEXT_ACTION,
        }
    )
    source_binding = _source_checkpoint_binding(r009_failure_binding)
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
            "FP048-R002_EXACT_PUBLISHED_SEQ97_SOURCE",
            "FP048-R002_R009_004_EXECUTION_FAILURE_NONAUTHORITY",
            "FP048-R002_R010_FRESH_START_GATE_CONTRACT",
            "FP048-R002_SEQ98_99_TRANSITION_CONTROL_REVIEW",
        ],
        "source_checkpoint_binding": source_binding,
        "source_ready_event_binding": copy.deepcopy(
            source_event["source_ready_event_binding"]
        ),
        "authorization_binding": copy.deepcopy(dict(authorization_binding_value)),
        "contract_supersession": {
            "previous_contract_binding": copy.deepcopy(R009_CONTRACT_BINDING),
            "reason_code": CORRECTION_REASON_CODE,
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
                "branch": source["session_handoff"]["branch"],
                "logical_branch": source["session_handoff"]["branch"],
                "logical_branch_semantics": source_event[
                    "repository_context_reanchor"
                ]["after"]["logical_branch_semantics"],
                "physical_git_branch": source_event[
                    "repository_context_reanchor"
                ]["after"]["physical_git_branch"],
                "branch_mismatch_reason_code": source_event[
                    "repository_context_reanchor"
                ]["after"]["branch_mismatch_reason_code"],
                "current_head": mirror["current_head"],
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
            seq97.seq96.seq95.seq94.seq93.seq92.correction._unchanged_projection(
                projected
            )
        ),
        "canonical_binding_snapshot_after": copy.deepcopy(
            source_event["canonical_binding_snapshot_after"]
        ),
        "correction_reason": {
            "failed_contract_id": R009_CONTRACT_BINDING["contract_id"],
            "failed_contract_version": R009_CONTRACT_BINDING["contract_version"],
            "r009_execution_failure": copy.deepcopy(dict(r009_failure_binding)),
            "reason_code": CORRECTION_REASON_CODE,
            "remediation": "SUPERSEDE_R009_WITH_FRESH_STAGE_AWARE_R010_BEFORE_SEQ99_START",
        },
        "previous_event_sha256": SOURCE_EVENT_SHA256,
    }
    event["event_sha256"] = continuation.event_sha256(event)
    require(set(event) == EVENT_FIELDS, "seq98 correction event field set differs")
    state = projected["goal_execution"]
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = occurred_at
    require(
        state["goal_status"] == "READY"
        and state["status_by_goal"][GOAL_ID] == "READY"
        and current["status"] == "READY"
        and "IN_PROGRESS" not in state["status_by_goal"].values(),
        "seq98 READY state differs",
    )
    for field in (
        "approved_state",
        "authority_boundary",
        "canonical_bindings",
        "verification_boundary",
    ):
        require(
            event["unchanged_control_projection"][field]
            == source_event["unchanged_control_projection"][field],
            f"seq98 changed frozen control projection: {field}",
        )
    require(
        all(
            type(value) is int and value == 0
            for key, value in CLAIM_BOUNDARY.items()
            if key.endswith("_delta") or key == "goal_status_change_count"
        )
        and CLAIM_BOUNDARY["formal_test_not_run_count"] == 279
        and CLAIM_BOUNDARY["remaining_gate_count"] == 5
        and CLAIM_BOUNDARY["release_status"] == "NOT_ELIGIBLE"
        and CLAIM_BOUNDARY["implementation_start_authorized"] is False,
        "seq98 zero-credit boundary differs",
    )
    return projected, event


def _repository_before(event: Mapping[str, Any]) -> dict[str, Any]:
    context = event.get("repository_context_reanchor")
    before = context.get("before") if isinstance(context, dict) else None
    source_binding = _source_checkpoint_binding(
        event.get("source_checkpoint_binding", {}).get("r009_execution_failure", {})
    )
    require(
        isinstance(context, dict)
        and set(context) == {"before", "after"}
        and isinstance(before, dict)
        and all(key in before for key in source_binding)
        and _strict_json_equal(
            {key: before.get(key) for key in source_binding}, source_binding
        )
        and all(
            key in before
            for key in ("current_work", "working_tree_snapshot", "session_handoff")
        ),
        "seq98 repository before-context differs",
    )
    return before


def _restored_seq97_checkpoint(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "seq97 inverse source is not exact seq98",
    )
    before = _repository_before(history[-1])
    restored = copy.deepcopy(checkpoint)
    restored_state = restored["goal_execution"]
    source_event = restored_state["transition_history"][SOURCE_SEQUENCE - 1]
    restored_state["transition_history"] = restored_state["transition_history"][
        :SOURCE_SEQUENCE
    ]
    restored_state["transition_history_anchor_sha256"] = source_event["event_sha256"]
    restored_state["validation_cutoff_at"] = source_event["occurred_at"]
    restored["current_work"] = copy.deepcopy(before["current_work"])
    restored["working_tree_snapshot"] = copy.deepcopy(before["working_tree_snapshot"])
    restored["session_handoff"] = copy.deepcopy(before["session_handoff"])
    return restored


def _require_start_gate_execution_corrected_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
    run_external_validators: bool = False,
) -> None:
    root = seq90._safe_root(root)
    _stale_r001_preparation(root)
    _rejected_r002_review(root)
    require(type(require_live_snapshot) is bool, "seq98 live-snapshot flag differs")
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "checkpoint is not exact seq98 R009 execution correction",
    )
    event = history[-1]
    failure = r009_execution_failure_binding(root)
    require(
        isinstance(event, dict)
        and set(event) == EVENT_FIELDS
        and type(event.get("sequence")) is int
        and event.get("sequence") == CORRECTION_SEQUENCE
        and event.get("event_id") == CORRECTION_EVENT_ID
        and event.get("event_type") == CORRECTION_EVENT_TYPE
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("previous_event_sha256") == SOURCE_EVENT_SHA256
        and event.get("event_sha256") == continuation.event_sha256(event)
        and _strict_json_equal(
            event.get("source_checkpoint_binding"),
            _source_checkpoint_binding(failure),
        )
        and event.get("claim_boundary") == CLAIM_BOUNDARY
        and event.get("contract_supersession", {}).get("previous_contract_binding")
        == R009_CONTRACT_BINDING
        and event.get("contract_supersession", {}).get("reason_code")
        == CORRECTION_REASON_CODE,
        "seq98 correction authority differs",
    )
    source = _restored_seq97_checkpoint(checkpoint)
    source_raw = checkpoint_json_bytes(source)
    require_exact_seq97_source(source_raw, source, root)
    replacement_contract = event["contract_supersession"][
        "replacement_contract_binding"
    ]
    replacement_runner = event["start_gate_runner_binding"]
    authorization = _load_physical_authorization(
        root,
        source,
        require_live_inputs=require_live_snapshot,
        replacement_contract_binding=replacement_contract,
    )
    require(
        event.get("authorization_binding") == authorization,
        "seq98 event authorization binding differs",
    )
    review, result_at, independent_at = _load_physical_review(
        root,
        source,
        require_live_inputs=require_live_snapshot,
        authorization_binding_value=authorization,
        replacement_contract_binding=replacement_contract,
        replacement_runner_binding=replacement_runner,
    )
    event_at = _parse_time(event.get("occurred_at"), "seq98 occurred_at")
    source_at = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq97 occurred_at",
    )
    require(
        source_at < result_at < independent_at < event_at,
        "seq98 event/review chronology differs",
    )
    snapshot = checkpoint.get("working_tree_snapshot")
    paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
    require(isinstance(paths, list), "seq98 managed paths differ")
    expected, expected_event = project_seq98(
        root,
        source,
        managed_paths=paths,
        path_set_sha256=snapshot.get("path_set_sha256"),
        content_set_sha256=snapshot.get("content_set_sha256"),
        occurred_at=event.get("occurred_at"),
        authorization_binding_value=event["authorization_binding"],
        review_binding=review,
        r009_failure_binding=failure,
        replacement_contract_binding=replacement_contract,
        replacement_runner_binding=replacement_runner,
    )
    require(
        checkpoint_json_bytes(expected) == checkpoint_json_bytes(checkpoint)
        and expected_event == event,
        "seq98 projected checkpoint differs",
    )
    if require_live_snapshot:
        require(
            continuation.working_snapshot_hashes(root, paths)
            == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
            "seq98 managed snapshot differs",
        )
    if run_external_validators:
        seq90._validate_projected_with_consumers(root, checkpoint)


def require_start_gate_execution_corrected_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
    run_external_validators: bool = False,
) -> None:
    with seq97_source_validation_call_scope():
        _require_start_gate_execution_corrected_checkpoint(
            root,
            checkpoint,
            require_live_snapshot=require_live_snapshot,
            run_external_validators=run_external_validators,
        )


def reconstructed_seq97_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    with seq97_source_validation_call_scope():
        require_start_gate_execution_corrected_checkpoint(
            root,
            checkpoint,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        restored = _restored_seq97_checkpoint(checkpoint)
        raw = checkpoint_json_bytes(restored)
        require_exact_seq97_source(raw, restored, root)
        return raw


def canonical_seq98_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    with seq97_source_validation_call_scope():
        require_start_gate_execution_corrected_checkpoint(
            root,
            checkpoint,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        return checkpoint_json_bytes(checkpoint)


def _exact_review_control_seq98_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool,
) -> dict[str, Any]:
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(isinstance(history, list), "reviewed control history differs")
    if len(history) == CORRECTION_SEQUENCE:
        candidate = copy.deepcopy(dict(checkpoint))
        require_start_gate_execution_corrected_checkpoint(
            root,
            candidate,
            require_live_snapshot=require_live_snapshot,
            run_external_validators=False,
        )
        return candidate
    require(
        len(history) == STARTED_SEQUENCE,
        "reviewed control authority requires exact seq98 or seq99",
    )
    started = importlib.import_module(
        "scripts.apply_walksafe_fp048_r002_goal_started_seq99_20260827"
    )
    validator = getattr(started, "require_started_checkpoint", None)
    inverse = getattr(started, "reconstructed_seq98_checkpoint_bytes", None)
    require(
        callable(validator) and callable(inverse),
        "seq99 reviewed control authority API differs",
    )
    validator(
        root,
        checkpoint,
        require_live_snapshot=require_live_snapshot,
        run_external_validators=False,
    )
    raw = inverse(root, checkpoint)
    candidate = seq90.strict_json(raw, "reconstructed seq98 review authority")
    require(
        raw == checkpoint_json_bytes(candidate),
        "reconstructed seq98 review authority bytes differ",
    )
    require_start_gate_execution_corrected_checkpoint(
        root,
        candidate,
        require_live_snapshot=False,
        run_external_validators=False,
    )
    return candidate


def noncredit_reviewed_control_successor_bindings(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool,
) -> dict[str, Any]:
    """Return the event-bound reviewed control cohort with zero product credit."""

    root = seq90._safe_root(root)
    seq98_checkpoint = _exact_review_control_seq98_checkpoint(
        root,
        checkpoint,
        require_live_snapshot=require_live_snapshot,
    )
    event = seq98_checkpoint["goal_execution"]["transition_history"][-1]
    review_binding = event.get("transition_control_review_binding")
    require(
        isinstance(review_binding, Mapping)
        and set(review_binding)
        == {"assignment", "review_result", "independent_review"},
        "reviewed control event binding differs",
    )
    assignment_read = seq90._stable_read(root, REVIEW_ASSIGNMENT_REL)
    require(
        _binding(REVIEW_ASSIGNMENT_REL, assignment_read.raw)
        == review_binding.get("assignment"),
        "reviewed control assignment bytes differ",
    )
    assignment = seq90.strict_json(
        assignment_read.raw, REVIEW_ASSIGNMENT_REL.as_posix()
    )
    require(
        assignment_read.raw == seq90.canonical_json_bytes(assignment)
        and set(assignment) == ASSIGNMENT_FIELDS
        and assignment.get("document_id") == REVIEW_ASSIGNMENT_DOCUMENT_ID
        and assignment.get("round_id") == REVIEW_ROUND_ID
        and assignment.get("candidate_status") == FINAL_CANDIDATE_STATUS,
        "reviewed control assignment authority differs",
    )
    rows = _validated_reviewed_control_rows(
        assignment.get("reviewed_control_inputs")
    )
    if require_live_snapshot:
        for path, row in zip(REVIEWED_CONTROL_PATHS, rows, strict=True):
            require(
                _observed_binding(root, path) == row,
                f"reviewed control live binding differs: {path}",
            )
    require(
        all(
            type(value) is int and value == 0
            for value in REVIEWED_CONTROL_SUCCESSOR_CREDIT_BOUNDARY.values()
        ),
        "reviewed control credit boundary differs",
    )
    return {
        "authority_label": REVIEWED_CONTROL_SUCCESSOR_AUTHORITY_LABEL,
        "bindings": rows,
        "credit_boundary": copy.deepcopy(
            REVIEWED_CONTROL_SUCCESSOR_CREDIT_BOUNDARY
        ),
    }


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


def _require_prepared_exact(prepared: Prepared) -> None:
    transport = prepared.transport
    require(
        transport.source_raw == checkpoint_json_bytes(transport.source)
        and transport.projected_raw == checkpoint_json_bytes(transport.projected)
        and transport.event
        == transport.projected["goal_execution"]["transition_history"][-1],
        "prepared seq98 payload differs",
    )
    require_exact_seq97_source(transport.source_raw, transport.source, transport.root)


def _require_prepared_live_exact(prepared: Prepared, *, phase: str) -> None:
    transport = prepared.transport
    _require_prepared_exact(prepared)
    _stale_r001_preparation(transport.root)
    _rejected_r002_review(transport.root)
    _failed_r003_execution(transport.root)
    _rejected_r004_review(transport.root)
    source_read = seq90.ReadResult(transport.source_raw, transport.source_identity)
    try:
        seq90._require_preflight_cohort_unchanged(
            transport.root,
            source=source_read,
            retained=transport.retained_inputs,
            managed=transport.managed_inputs,
            git_visible=transport.git_visible_inputs,
            git_status_raw=transport.git_status_raw,
            git_head=transport.git_head,
            git_branch=transport.git_branch,
            phase=phase,
        )
    except Exception as exc:
        raise StartGateExecutionCorrectionError(str(exc)) from exc
    require(
        stat.S_IMODE((transport.root / CHECKPOINT_REL).lstat().st_mode)
        == SOURCE_CHECKPOINT_MODE,
        "live seq97 checkpoint mode differs",
    )
    require_start_gate_execution_corrected_checkpoint(
        transport.root,
        transport.projected,
        require_live_snapshot=True,
        run_external_validators=False,
    )
    snapshot = transport.projected["working_tree_snapshot"]
    paths = sorted(path.as_posix() for path in transport.managed_inputs)
    require(
        paths == snapshot["managed_changed_paths"]
        and seq90._managed_input_snapshot_hashes(transport.managed_inputs)
        == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
        "prepared seq98 managed snapshot differs",
    )


def _cas_write_temp_relatives(status_raw: bytes) -> tuple[Path, ...]:
    candidates: list[Path] = []
    for record in status_raw.split(b"\0"):
        if not record.startswith(b"?? "):
            continue
        try:
            relative = Path(record[3:].decode("utf-8"))
        except UnicodeDecodeError as exc:
            raise StartGateExecutionCorrectionError(
                "seq98 CAS temporary status is not UTF-8"
            ) from exc
        name = relative.name
        token = name[len(CAS_WRITE_TEMP_PREFIX) : -len(CAS_WRITE_TEMP_SUFFIX)]
        if (
            relative.parent == CHECKPOINT_REL.parent
            and name.startswith(CAS_WRITE_TEMP_PREFIX)
            and name.endswith(CAS_WRITE_TEMP_SUFFIX)
            and re.fullmatch(r"[0-9a-f]{24}", token) is not None
        ):
            candidates.append(relative)
    return tuple(candidates)


def _validated_atomic_write_temporary(
    prepared: Prepared,
) -> tuple[str, bytes, tuple[str, ...], Path, Any]:
    transport = prepared.transport
    try:
        _require_prepared_exact(prepared)
        before = seq90._stable_read(transport.root, CHECKPOINT_REL)
        if (
            before.identity == transport.source_identity
            and before.raw == transport.source_raw
        ):
            phase = "SOURCE"
            expected_temporary = transport.projected_raw
        elif before.raw == transport.projected_raw:
            phase = "PROJECTED"
            expected_temporary = transport.source_raw
        else:
            raise StartGateExecutionCorrectionError(
                "seq98 CAS live checkpoint phase differs"
            )
        checkpoint_info = (transport.root / CHECKPOINT_REL).lstat()
        require(
            stat.S_ISREG(checkpoint_info.st_mode)
            and stat.S_IMODE(checkpoint_info.st_mode) == SOURCE_CHECKPOINT_MODE
            and checkpoint_info.st_uid == os.geteuid()
            and checkpoint_info.st_nlink == 1,
            "seq98 CAS live checkpoint authority differs",
        )
        _stale_r001_preparation(transport.root)
        _rejected_r002_review(transport.root)
        if phase == "SOURCE":
            _failed_r003_execution(transport.root)
            _rejected_r004_review(transport.root)
        else:
            _failed_r003_execution_for_source(
                transport.root,
                transport.source_raw,
                transport.source,
            )
            _rejected_r004_review_for_source(
                transport.root,
                transport.source_raw,
                transport.source,
            )
        seq90._require_inputs_unchanged(
            transport.root,
            transport.retained_inputs,
        )
        seq90._require_managed_inputs_unchanged(
            transport.root,
            transport.managed_inputs,
        )
        seq90._require_managed_inputs_unchanged(
            transport.root,
            {
                path: value
                for path, value in transport.git_visible_inputs.items()
                if path != CHECKPOINT_REL
            },
            label="noncheckpoint Git-visible input",
        )
        seq90._require_git_context(
            transport.root,
            transport.git_head,
            transport.git_branch,
        )
        observed_status, observed_visible = seq90.capture_git_visible_paths(
            transport.root
        )
        candidates = _cas_write_temp_relatives(observed_status)
        require(
            len(candidates) == 1,
            "seq98 commit boundary requires one CAS temporary",
        )
        relative = candidates[0]
        reduced_status = seq90._without_ephemeral_status_record(
            observed_status,
            relative,
        )
        require(
            reduced_status == transport.git_status_raw,
            "Git-visible cohort changed at seq98 commit point",
        )
        expected_visible = tuple(
            sorted(
                {
                    *(path.as_posix() for path in transport.git_visible_inputs),
                    relative.as_posix(),
                }
            )
        )
        require(
            observed_visible == expected_visible,
            "seq98 commit boundary Git-visible inventory differs",
        )
        temporary = seq90._stable_read(transport.root, relative)
        require(
            stat.S_IMODE(temporary.identity.mode) == SOURCE_CHECKPOINT_MODE
            and temporary.identity.uid == os.geteuid()
            and temporary.identity.links == 1
            and temporary.raw == expected_temporary,
            "seq98 CAS temporary authority differs",
        )
        after = seq90._stable_read(transport.root, CHECKPOINT_REL)
        require(
            after.identity == before.identity and after.raw == before.raw,
            "seq98 CAS live checkpoint changed during commit guard",
        )
        return phase, observed_status, observed_visible, relative, temporary
    except StartGateExecutionCorrectionError:
        raise
    except Exception as exc:
        raise StartGateExecutionCorrectionError(str(exc)) from exc


def _require_seq98_atomic_boundary_exact(prepared: Prepared) -> str:
    phase, _status, _visible, _relative, _temporary = (
        _validated_atomic_write_temporary(prepared)
    )
    return phase


def _require_seq98_commit_boundary_exact(prepared: Prepared) -> str:
    return _require_seq98_atomic_boundary_exact(prepared)


def _write_checkpoint_atomic(
    prepared: Prepared,
    *,
    atomic_writer: Any = cas.atomic_write,
    exchanger: Any = cas._rename_exchange_at,
    directory_syncer: Any = os.fsync,
) -> None:
    transport = prepared.transport
    phases: list[str] = []

    def commit_guard() -> None:
        phases.append(_require_seq98_atomic_boundary_exact(prepared))

    try:
        atomic_writer(
            transport.root / CHECKPOINT_REL,
            transport.projected_raw,
            expected_source=transport.source_raw,
            exchanger=exchanger,
            directory_syncer=directory_syncer,
            commit_guard=commit_guard,
        )
    except cas.CompletionPostCommitError as exc:
        raise seq90.PostcommitUncertain(str(exc)) from exc
    except cas.CompletionApplyError as exc:
        raise StartGateExecutionCorrectionError(str(exc)) from exc
    require(
        phases == ["SOURCE", "PROJECTED", "PROJECTED"],
        "seq98 CAS commit guard sequence differs",
    )


def _final_managed_paths(
    source: Mapping[str, Any],
    visible: Sequence[str],
) -> tuple[str, ...]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    paths.update(visible)
    paths = {
        path for path in paths if not path.startswith(GATE_EVIDENCE_PREFIX)
    }
    paths.discard(CHECKPOINT_REL.as_posix())
    paths.update(path.as_posix() for path in REVIEWED_CONTROL_PATHS)
    paths.update(path.as_posix() for path in REVIEW_PATHS)
    paths.add(AUTHORIZATION_REL.as_posix())
    paths.update(path.as_posix() for path in R001_STALE_PHYSICAL_PATHS)
    paths.update(path.as_posix() for path in R002_STALE_PHYSICAL_PATHS)
    paths.update(path.as_posix() for path in R003_PHYSICAL_PATHS)
    paths.update(path.as_posix() for path in R004_STALE_PHYSICAL_PATHS)
    require(
        not any(path.startswith(GATE_EVIDENCE_PREFIX) for path in paths),
        "seq98 managed snapshot includes direct gate evidence",
    )
    return tuple(sorted(paths))


def _retained_input_paths(failure: Mapping[str, Any]) -> tuple[Path, ...]:
    paths = set(REVIEWED_CONTROL_PATHS) | set(REVIEW_PATHS) | {
        AUTHORIZATION_REL,
        *R001_STALE_PHYSICAL_PATHS,
        *R002_STALE_PHYSICAL_PATHS,
        *R003_PHYSICAL_PATHS,
        *R004_STALE_PHYSICAL_PATHS,
        *(Path(row["path"]) for row in failure["logs"]),
    }
    return tuple(sorted(paths, key=lambda value: value.as_posix()))


def prepare(root: Path = ROOT, *, occurred_at: str | None = None) -> Prepared:
    root = seq90._safe_root(root)
    require(pins_are_final(), "seq98 reviewed pins are not final")
    source_read = seq90._stable_read(root, CHECKPOINT_REL)
    source = seq90.strict_json(source_read.raw, CHECKPOINT_REL.as_posix())
    require_exact_seq97_source(source_read.raw, source, root)
    require(
        stat.S_IMODE(source_read.identity.mode) == SOURCE_CHECKPOINT_MODE,
        "seq97 checkpoint mode differs",
    )
    failure = r009_execution_failure_binding(root)
    authorization_raw = seq90._stable_read(root, AUTHORIZATION_REL).raw
    replacement_contract = r010_contract_binding(root)
    replacement_runner = r010_runner_binding(root)
    authorization = _load_physical_authorization(
        root,
        source,
        require_live_inputs=True,
        replacement_contract_binding=replacement_contract,
    )
    require(
        authorization == _binding(AUTHORIZATION_REL, authorization_raw),
        "seq98 authorization read differs",
    )
    review, _result_at, independent_at = _load_physical_review(
        root,
        source,
        require_live_inputs=True,
        authorization_binding_value=authorization,
        replacement_contract_binding=replacement_contract,
        replacement_runner_binding=replacement_runner,
    )
    source_at = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq97 occurred_at",
    )
    candidate_at = (
        _parse_time(occurred_at, "seq98 occurred_at")
        if occurred_at is not None
        else datetime.now().astimezone().replace(microsecond=0)
    )
    event_at = max(
        candidate_at,
        source_at + timedelta(seconds=1),
        independent_at + timedelta(seconds=1),
    ).isoformat()
    git_head, git_branch = seq90._capture_git_context(root)
    status_raw, visible = seq90.capture_git_visible_paths(root)
    failure_log_paths = tuple(Path(row["path"]) for row in failure["logs"])
    require(
        all(path.as_posix() in visible for path in failure_log_paths),
        "R009 failure logs are not Git-visible",
    )
    managed_paths = _final_managed_paths(source, visible)
    managed = seq90._capture_managed_inputs(root, managed_paths)
    visible_inputs = seq90._capture_managed_inputs(root, visible)
    path_sha256, content_sha256 = seq90._managed_input_snapshot_hashes(managed)
    projected, event = project_seq98(
        root,
        source,
        managed_paths=managed_paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        occurred_at=event_at,
        authorization_binding_value=authorization,
        review_binding=review,
        r009_failure_binding=failure,
        replacement_contract_binding=replacement_contract,
        replacement_runner_binding=replacement_runner,
    )
    retained = {
        path: seq90._stable_read(root, path)
        for path in _retained_input_paths(failure)
    }
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
    _require_prepared_live_exact(prepared, phase="during seq98 preflight")
    seq90._validate_projected_with_consumers(root, projected)
    _require_prepared_live_exact(prepared, phase="after seq98 consumer preflight")
    return prepared


def write_checkpoint(
    prepared: Prepared,
    *,
    writer: Any | None = None,
) -> None:
    transport = prepared.transport
    current = seq90._stable_read(transport.root, CHECKPOINT_REL)
    if current.raw == transport.projected_raw:
        require(
            stat.S_IMODE(current.identity.mode) == SOURCE_CHECKPOINT_MODE,
            "published seq98 checkpoint mode differs",
        )
        published = seq90.strict_json(current.raw, CHECKPOINT_REL.as_posix())
        require_start_gate_execution_corrected_checkpoint(
            transport.root,
            published,
            require_live_snapshot=True,
            run_external_validators=True,
        )
        terminal = seq90._stable_read(transport.root, CHECKPOINT_REL)
        require(
            terminal.identity == current.identity and terminal.raw == current.raw,
            "published seq98 checkpoint changed during terminal validation",
        )
        return
    require(
        current.identity == transport.source_identity
        and current.raw == transport.source_raw,
        "seq97 source changed before seq98 publication",
    )
    _require_prepared_live_exact(prepared, phase="before seq98 publication")
    try:
        if writer is None:
            _write_checkpoint_atomic(prepared)
        else:
            writer(
                transport,
                commit_guard=lambda: _require_seq98_commit_boundary_exact(
                    prepared
                ),
            )
    except seq90.PostcommitUncertain:
        raise
    except BaseException as exc:
        observed = seq90._stable_read(transport.root, CHECKPOINT_REL)
        if observed.raw == transport.projected_raw:
            raise seq90.PostcommitUncertain(
                "seq98 writer failed after publishing exact bytes"
            ) from exc
        raise
    published_read = seq90._stable_read(transport.root, CHECKPOINT_REL)
    if published_read.raw != transport.projected_raw:
        raise seq90.PostcommitUncertain(
            "published seq98 checkpoint bytes are uncertain"
        )
    try:
        require(
            stat.S_IMODE(published_read.identity.mode) == SOURCE_CHECKPOINT_MODE,
            "published seq98 checkpoint mode differs",
        )
        published = seq90.strict_json(
            published_read.raw,
            CHECKPOINT_REL.as_posix(),
        )
        require_start_gate_execution_corrected_checkpoint(
            transport.root,
            published,
            require_live_snapshot=True,
            run_external_validators=True,
        )
        require(
            reconstructed_seq97_checkpoint_bytes(transport.root, published)
            == transport.source_raw,
            "published seq98 inverse source differs",
        )
        terminal = seq90._stable_read(transport.root, CHECKPOINT_REL)
        require(
            terminal.identity == published_read.identity
            and terminal.raw == published_read.raw,
            "published seq98 checkpoint changed during terminal validation",
        )
    except BaseException as exc:
        raise seq90.PostcommitUncertain(
            "seq98 publication completed but terminal authority is uncertain"
        ) from exc


def _published_seq98_event_if_exact(root: Path) -> dict[str, Any] | None:
    root = seq90._safe_root(root)
    observed = seq90._stable_read(root, CHECKPOINT_REL)
    checkpoint = seq90.strict_json(observed.raw, CHECKPOINT_REL.as_posix())
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    if not isinstance(history, list) or len(history) != CORRECTION_SEQUENCE:
        return None
    require(
        stat.S_IMODE(observed.identity.mode) == SOURCE_CHECKPOINT_MODE,
        "published seq98 checkpoint mode differs",
    )
    require_start_gate_execution_corrected_checkpoint(
        root,
        checkpoint,
        require_live_snapshot=True,
        run_external_validators=True,
    )
    terminal = seq90._stable_read(root, CHECKPOINT_REL)
    require(
        terminal.identity == observed.identity and terminal.raw == observed.raw,
        "published seq98 checkpoint changed during terminal validation",
    )
    event = history[-1]
    require(isinstance(event, dict), "published seq98 event differs")
    return copy.deepcopy(event)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare-authorization", action="store_true")
    mode.add_argument("--prepare-review", action="store_true")
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--occurred-at")
    parser.add_argument("--allow-provisional", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    published = False
    try:
        if args.prepare_authorization:
            _raw, source = load_exact_seq97_source(args.root)
            raw = build_authorization(
                args.root,
                source,
                allow_provisional=args.allow_provisional,
            )
            sys.stdout.buffer.write(raw)
            return 0 if pins_are_final() else 1
        if args.prepare_review:
            manifest = prepare_review_manifest(
                args.root,
                allow_provisional=args.allow_provisional,
            )
            print(json.dumps(manifest, ensure_ascii=False, sort_keys=True))
            return 0 if manifest["publishable"] else 1
        if args.write:
            published_event = _published_seq98_event_if_exact(args.root)
            if published_event is not None:
                print(
                    "FP048-R002 seq98 R009 execution correction: PASS "
                    f"event_sha256={published_event['event_sha256']} "
                    "published=true"
                )
                return 0
        prepared = prepare(args.root, occurred_at=args.occurred_at)
        if args.write:
            write_checkpoint(prepared)
            published = True
        print(
            "FP048-R002 seq98 R009 execution correction: PASS "
            f"event_sha256={prepared.event['event_sha256']} "
            f"published={str(published).lower()}"
        )
        return 0
    except seq90.PostcommitUncertain as exc:
        print(
            "FP048-R002 seq98 R009 execution correction: "
            "POSTCOMMIT-UNCERTAIN: " + str(exc),
            file=sys.stderr,
        )
        return 2
    except (OSError, RuntimeError, TypeError, ValueError) as exc:
        print(
            "FP048-R002 seq98 R009 execution correction: FAIL: " + str(exc),
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
