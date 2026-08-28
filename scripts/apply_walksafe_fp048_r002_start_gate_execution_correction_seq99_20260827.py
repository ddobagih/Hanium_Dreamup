#!/usr/bin/env python3
"""Publish the add-only FP048 R002 seq99 R010 execution correction.

The exact published seq98 checkpoint and the consumed R010 ``-005`` gate
namespace are immutable inputs.  The frozen R001/R002 reviews and their failed
or aborted preflights remain nonauthority.  The partial R003 review is frozen
after its primary reviewer rejected a P1 type-confusion defect.  The complete
R004 review is frozen after its preflight exposed a P0 continuation-performance
defect.  R005 is also frozen after its preflight exposed repeated GoalGraph
physical replay.  A separate R006 review reseals the R011 replacement before
its first formal primary review rejects stale catalogs.  Later R006 decision
documents and its timed-out preflight are retained as nonauthority.  R007 is
also frozen after its approved candidate timed out during projected GoalGraph
preflight before any write or CAS.  R008 is frozen after its approved candidate
failed projected GoalGraph preflight before any write or CAS.  A fresh R009
review later passed preflight, but its write failed without publication and the
exact seq98 source remained.  A projected CAS-guard failure followed by rollback
is a non-exclusive inference because the validator stack is unavailable.  A
fresh R010 review reseals the phase-aware CAS guard correction before
READY-to-READY publication without product or release credit.
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
import stat
import sys
from typing import Any, Iterator, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import (  # noqa: E402
    apply_walksafe_fp048_goal_completed_seq43_44_20260802 as cas,
    apply_walksafe_fp048_r002_start_gate_execution_correction_seq98_20260827
    as seq98,
)


class StartGateExecutionCorrectionError(RuntimeError):
    """The seq99 R010 execution correction cannot be proven or published."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise StartGateExecutionCorrectionError(message)


_SEQ98_REVIEW_CHAIN_CALL_CACHE: ContextVar[
    dict[tuple[str, int, str], tuple[bytes, str, Any]] | None
] = ContextVar("_SEQ98_REVIEW_CHAIN_CALL_CACHE", default=None)
_SEQ98_REVIEW_CHAIN_CACHE_MISS = object()


@contextmanager
def seq98_source_validation_call_scope() -> Iterator[None]:
    """Reuse exact seq98/review proofs only inside one validation call."""

    if _SEQ98_REVIEW_CHAIN_CALL_CACHE.get() is not None:
        yield
        return
    token = _SEQ98_REVIEW_CHAIN_CALL_CACHE.set({})
    try:
        with seq98.seq97_source_validation_call_scope():
            yield
    finally:
        _SEQ98_REVIEW_CHAIN_CALL_CACHE.reset(token)


seq90 = seq98.seq90
continuation = seq98.continuation
CHECKPOINT_REL = seq98.CHECKPOINT_REL
checkpoint_json_bytes = seq98.checkpoint_json_bytes
GOAL_ID = seq98.GOAL_ID
GOAL_PATH = seq98.GOAL_PATH
GOAL_SHA256 = seq98.GOAL_SHA256
WORK_ITEM_ID = seq98.WORK_ITEM_ID
MANIFEST_SHA256 = seq98.MANIFEST_SHA256
EVENT_FIELDS = seq98.EVENT_FIELDS

SOURCE_SEQUENCE = 98
SOURCE_EVENT_ID = seq98.CORRECTION_EVENT_ID
SOURCE_EVENT_SHA256 = (
    "13c1de237df551d943027aa5b944d84dcae24ce06132c80febbb69bf45220bbd"
)
SOURCE_CHECKPOINT_SHA256 = (
    "79c194f1293205b81c8d56920a9c610af5ba8d308a30c273c72eb4a05fec41f5"
)
SOURCE_CHECKPOINT_BYTE_LENGTH = 5_076_973
SOURCE_CHECKPOINT_MODE = 0o600

CORRECTION_SEQUENCE = 99
CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-GATE-EXECUTION-CORRECTED-"
    "FP048-R002-20260827-007"
)
CORRECTION_EVENT_TYPE = "GOAL_START_GATE_EXECUTION_CORRECTED"
CORRECTION_REASON_CODE = (
    "R010_POST_CHECK_CONTEXT_REVALIDATION_REJECTED_OWN_EVENT_NAMESPACE"
)
STARTED_SEQUENCE = 100
STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260827-006"
)

READY_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP048-R002-20260825-001"
)
READY_FRONTIER = (
    "WS-GOAL-EPIC-03-FP-048-R002",
    "WS-GOAL-EPIC-03",
    "WS-GOAL-EPIC-04",
    "WS-GOAL-EPIC-12",
)
FRONTIER = READY_FRONTIER

R010_CONTRACT_BINDING = {
    "schema_version": "1.2",
    "document_id": "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260827-010",
    "path": (
        "docs/control/execution/goal-contracts/"
        "WS-GOAL-EPIC-03-FP-048-R002/initial-start-gate-contract-r010.json"
    ),
    "file_sha256": (
        "6bbb426e5b00f7f886e376587d06b42a97b1a5b04683d685f701e2ca0a04120e"
    ),
    "contract_id": "WS-FP048-R002-INTERNAL-START-GATE-R010",
    "contract_version": "2026-08-27.1",
    "canonical_contract_sha256": (
        "0428342409bb1210563a579d153ed025ff6ce1ca636a874c1532bb2423fedf1e"
    ),
}
R010_CONTRACT_REL = Path(R010_CONTRACT_BINDING["path"])
R010_CONTRACT_FILE_BINDING = {
    "path": R010_CONTRACT_REL.as_posix(),
    "sha256": R010_CONTRACT_BINDING["file_sha256"],
    "byte_length": 3_935,
}
R010_CONTRACT_MODE = 0o664
R010_RUNNER_REL = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r010_20260827.py"
)
R010_RUNNER_BINDING = {
    "path": R010_RUNNER_REL.as_posix(),
    "sha256": "10c60b71893a84f28466331b26efde212af3d13c8a996c6e350b3ec5faacd6e5",
    "byte_length": 39_666,
}

R010_FAILED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260827-005"
)
R010_GATE_REL = Path("docs/control/execution/goal-gates") / R010_FAILED_EVENT_ID
R010_RECEIPT_REL = R010_GATE_REL / "implementation-start-gate-receipt.json"
R010_RECEIPT_STAGE_REL = (
    R010_GATE_REL / ".implementation-start-gate-receipt.json.staging"
)
R010_LOG_BINDINGS = (
    {
        "check_id": "CONTINUATION",
        "path": (R010_GATE_REL / "01-CONTINUATION.log").as_posix(),
        "sha256": "25b5c8cf65854e11a04a9d0810dd2503585037683809c153f578c08a076ac5a7",
        "byte_length": 39,
        "mode": "0600",
        "exit_code": 0,
    },
    {
        "check_id": "GOAL_GRAPH",
        "path": (R010_GATE_REL / "02-GOAL_GRAPH.log").as_posix(),
        "sha256": "f880c51e169f2a6f03875ab90156c15b9c5ce260ed0761b0048556b08d089516",
        "byte_length": 108,
        "mode": "0600",
        "exit_code": 0,
    },
    {
        "check_id": "TEST_LAYER_REGISTRY_VALIDATE",
        "path": (
            R010_GATE_REL / "03-TEST_LAYER_REGISTRY_VALIDATE.log"
        ).as_posix(),
        "sha256": "1eef448a998bf080cbe648b330b4c9369d49ea95bbccf662ce8d077b56c96187",
        "byte_length": 35,
        "mode": "0600",
        "exit_code": 0,
    },
    {
        "check_id": "ROOT_FP048_R002_CONTROL_REGRESSION",
        "path": (
            R010_GATE_REL / "04-ROOT_FP048_R002_CONTROL_REGRESSION.log"
        ).as_posix(),
        "sha256": "85e8a0864f4bd46be2f7b2f2dccbce80c712a9d300fa88c1f41a5fbdd6bb923a",
        "byte_length": 111,
        "mode": "0600",
        "exit_code": 0,
    },
    {
        "check_id": "REPOSITORY_STATE",
        "path": (R010_GATE_REL / "05-REPOSITORY_STATE.log").as_posix(),
        "sha256": "e3ef5e355c085633c057640739c7d94914065ea6e527a08faf19e742911a2853",
        "byte_length": 259_587,
        "mode": "0600",
        "exit_code": 0,
    },
)

R011_CONTRACT_ID = "WS-FP048-R002-INTERNAL-START-GATE-R011"
R011_CONTRACT_VERSION = "2026-08-27.2"
R011_DOCUMENT_ID = "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260827-011"
R011_CONTRACT_REL = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r011.json"
)
R011_RUNNER_REL = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r011_20260827.py"
)
R011_RUNNER_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r011_20260827.py"
)
POST_SEQ99_STAGE_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_post_seq99_stage_regression_20260827.py"
)
SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp048_r002_start_gate_execution_correction_"
    "seq99_20260827.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_start_gate_execution_correction_"
    "seq99_20260827.py"
)
SEQ98_EXECUTION_CORRECTION_REL = Path(
    "scripts/apply_walksafe_fp048_r002_start_gate_execution_correction_"
    "seq98_20260827.py"
)
SEQ98_EXECUTION_CORRECTION_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_start_gate_execution_correction_"
    "seq98_20260827.py"
)
SEQ100_STARTER_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq100_20260827.py"
)
SEQ100_STARTER_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq100_20260827.py"
)
PUBLIC_GATE_RUNTIME_REL = Path(
    "scripts/run_walksafe_fp008_goal_start_gate_20260803.py"
)
PUBLIC_GATE_RUNTIME_TEST_REL = Path(
    "tests/test_walksafe_fp008_goal_start_gate_20260803.py"
)
CONTINUATION_SCRIPT_REL = Path("scripts/check_walksafe_project_continuation_v2_4.py")
CONTINUATION_TEST_REL = Path("tests/test_walksafe_project_continuation_v2_4.py")
GOAL_GRAPH_SCRIPT_REL = Path("scripts/check_walksafe_goal_graph_v2_4.py")
GOAL_GRAPH_TEST_REL = Path("tests/test_walksafe_goal_graph_v2_4.py")
LAYER_RUNNER_REL = Path("scripts/run_walksafe_test_layers_current.sh")
REPOSITORY_CATALOG_TEST_REL = Path("tests/test_repository_catalogs.py")
NODE_TOOLCHAIN_CHECKER_REL = Path("scripts/check_walksafe_node_toolchain_20260715.py")
NODE_TOOLCHAIN_LOCK_REL = Path("configs/walksafe_node_toolchain_lock_20260715.json")
GITIGNORE_REL = Path(".gitignore")
SEQ90_WRITER_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826.py"
)
SEQ90_WRITER_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826.py"
)
CAS_WRITER_REL = Path("scripts/apply_walksafe_fp048_goal_completed_seq43_44_20260802.py")
CAS_WRITER_TEST_REL = Path("tests/test_walksafe_fp048_goal_completed_seq43_44_20260802.py")

TRANSITION_ROOT = Path("docs/control/execution/workstream-transitions/seq99-100")
R001_AUTHORIZATION_REL = TRANSITION_ROOT / "authorization.json"
R001_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R001"
R001_REVIEW_ASSIGNMENT_REL = R001_REVIEW_ROOT / "review-assignment.json"
R001_REVIEW_RESULT_REL = R001_REVIEW_ROOT / "review-result.json"
R001_INDEPENDENT_REVIEW_REL = R001_REVIEW_ROOT / "independent-review.json"
R001_FROZEN_BINDINGS = {
    "authorization": {
        "path": R001_AUTHORIZATION_REL.as_posix(),
        "sha256": "60496500b9f40cd68992eeb16881ce80e6545b22663414f91c2a73177d89ed01",
        "byte_length": 11_522,
    },
    "assignment": {
        "path": R001_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": "e02de031eab745aba5f65ccae297109b8fd6c336b6f4a5865219a63df29bf393",
        "byte_length": 16_270,
    },
    "review_result": {
        "path": R001_REVIEW_RESULT_REL.as_posix(),
        "sha256": "e9e9195b6d0f736c9954760c715f77fa4190e1b8d9237fe47e442baff4e5140e",
        "byte_length": 753,
    },
    "independent_review": {
        "path": R001_INDEPENDENT_REVIEW_REL.as_posix(),
        "sha256": "b6459d48984c7785941cde154f6a587f5dee4163ad57a04f4bb4b98c01b77d12",
        "byte_length": 1_018,
    },
}
R001_REVIEW_PATHS = (
    R001_AUTHORIZATION_REL,
    R001_REVIEW_ASSIGNMENT_REL,
    R001_REVIEW_RESULT_REL,
    R001_INDEPENDENT_REVIEW_REL,
)
R001_REVIEW_RESULT_AT = "2026-08-27T23:01:18+09:00"
R001_INDEPENDENT_REVIEW_AT = "2026-08-27T23:05:39+09:00"

R002_AUTHORIZATION_REL = TRANSITION_ROOT / "authorization-r002.json"
R002_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R002"
R002_REVIEW_ASSIGNMENT_REL = R002_REVIEW_ROOT / "review-assignment.json"
R002_REVIEW_RESULT_REL = R002_REVIEW_ROOT / "review-result.json"
R002_INDEPENDENT_REVIEW_REL = R002_REVIEW_ROOT / "independent-review.json"
R002_FROZEN_BINDINGS = {
    "authorization": {
        "path": R002_AUTHORIZATION_REL.as_posix(),
        "sha256": "4f894b0f7b4b594043b1f4dd592366346b478a461de616b0cfac21ac7d826028",
        "byte_length": 21_712,
    },
    "assignment": {
        "path": R002_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": "f9c530cb6dd93ba43047bbe1ff4b2b0cbec0a3fd6b7d5b437e2975678def5fd7",
        "byte_length": 26_465,
    },
    "review_result": {
        "path": R002_REVIEW_RESULT_REL.as_posix(),
        "sha256": "b33adaa172dce7f30b8b0097a6b7287b08c0d8ea009d64d2608e23efc82ffd36",
        "byte_length": 752,
    },
    "independent_review": {
        "path": R002_INDEPENDENT_REVIEW_REL.as_posix(),
        "sha256": "d4147196164f1fad7bbce9bb615fa292a1f9629a31551580ea23d11abdb69e40",
        "byte_length": 1_017,
    },
}
R002_REVIEW_PATHS = (
    R002_AUTHORIZATION_REL,
    R002_REVIEW_ASSIGNMENT_REL,
    R002_REVIEW_RESULT_REL,
    R002_INDEPENDENT_REVIEW_REL,
)
R002_REVIEW_RESULT_AT = "2026-08-28T00:04:33+09:00"
R002_INDEPENDENT_REVIEW_AT = "2026-08-28T00:10:27+09:00"
R002_PREFLIGHT_OBSERVED_AT = "2026-08-28T01:28:39+09:00"
R002_PREFLIGHT_VALIDATOR_STACK = (
    "_validate_projected_with_consumers",
    "goal_graph.validate",
    "validate_frozen_v23_semantics",
    "filter_frozen_v23_successor_errors",
    "_successor_lineage_reaches_live",
    "_fp048_r002_reviewed_noncredit_edge",
    "_compose_fp048_r002_reviewed_noncredit_successors",
    "_fp048_r002_repository_context_live_successors",
)
R002_PREFLIGHT_TERMINAL_ERROR = "KeyboardInterrupt"

R003_AUTHORIZATION_REL = TRANSITION_ROOT / "authorization-r003.json"
R003_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R003"
R003_REVIEW_ASSIGNMENT_REL = R003_REVIEW_ROOT / "review-assignment.json"
R003_REVIEW_RESULT_REL = R003_REVIEW_ROOT / "review-result.json"
R003_INDEPENDENT_REVIEW_REL = R003_REVIEW_ROOT / "independent-review.json"
R003_FROZEN_BINDINGS = {
    "authorization": {
        "path": R003_AUTHORIZATION_REL.as_posix(),
        "sha256": "d81bb92f1c10d59c0ebda6e8aa58be5a5bd9dbeddaa1762c2b91e93abb7ca7dd",
        "byte_length": 29_117,
    },
    "assignment": {
        "path": R003_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": "dc06fd6461718f58514fa689046cee83c6a833b8c87189d8199acf92232f92a6",
        "byte_length": 33_870,
    },
}
R003_PARTIAL_REVIEW_PATHS = (
    R003_AUTHORIZATION_REL,
    R003_REVIEW_ASSIGNMENT_REL,
)
R003_DECISION_PATHS = (
    R003_REVIEW_RESULT_REL,
    R003_INDEPENDENT_REVIEW_REL,
)
R003_PRIMARY_REVIEW_REJECTED_AT = "2026-08-28T02:00:00+09:00"

R004_AUTHORIZATION_REL = TRANSITION_ROOT / "authorization-r004.json"
R004_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R004"
R004_REVIEW_ASSIGNMENT_REL = R004_REVIEW_ROOT / "review-assignment.json"
R004_REVIEW_RESULT_REL = R004_REVIEW_ROOT / "review-result.json"
R004_INDEPENDENT_REVIEW_REL = R004_REVIEW_ROOT / "independent-review.json"
R004_FROZEN_BINDINGS = {
    "authorization": {
        "path": R004_AUTHORIZATION_REL.as_posix(),
        "sha256": "9ccf0bd9a43417b5fed8efd480dafef1b766262589a979ac1b9a916893c08378",
        "byte_length": 34_701,
    },
    "assignment": {
        "path": R004_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": "24fe0232f3e6d5aeb49bcb98fed1d29d3b28f8a91cb95acd440d3fbf3fbb5cc9",
        "byte_length": 39_455,
    },
    "review_result": {
        "path": R004_REVIEW_RESULT_REL.as_posix(),
        "sha256": "cfdbb9023e08076bc0c857c4e0e21d05b8ee1d2b1868670143ab3dad8d28f4a1",
        "byte_length": 752,
    },
    "independent_review": {
        "path": R004_INDEPENDENT_REVIEW_REL.as_posix(),
        "sha256": "1b23af84c185f3d88c3c17a9abb4e9b4449191d983b1365b1d56af9fe8cd1cda",
        "byte_length": 1_017,
    },
}
R004_REVIEW_PATHS = (
    R004_AUTHORIZATION_REL,
    R004_REVIEW_ASSIGNMENT_REL,
    R004_REVIEW_RESULT_REL,
    R004_INDEPENDENT_REVIEW_REL,
)
R004_REVIEW_RESULT_AT = "2026-08-28T02:28:16+09:00"
R004_INDEPENDENT_REVIEW_AT = "2026-08-28T02:33:40+09:00"
R004_PREFLIGHT_OBSERVED_AT = "2026-08-28T02:45:27+09:00"
R004_PREFLIGHT_VALIDATOR_STACK = (
    "_validate_projected_with_consumers",
    "continuation.validate",
    "validate_transition_replay",
    "_validate_npc_single_admin_recovery_control_reanchor",
    "_validate_fp048_r002_r009_execution_correction_seq98",
    "_require_fp048_r002_branch_semantics_descendant",
    "_fp048_r002_current_descendant_seq96_source",
    "seq98.reconstructed_seq97_checkpoint_bytes",
    "seq97.require_snapshot_hygiene_corrected_checkpoint",
    "seq96.canonical_seq96_checkpoint_bytes",
    "seq96.require_contract_corrected_checkpoint",
    "_require_frozen_seq95_checkpoint",
    "seq90.checkpoint_json_bytes",
    "json.dumps",
)
R004_PREFLIGHT_TERMINAL_ERROR = "KeyboardInterrupt"

R005_AUTHORIZATION_REL = TRANSITION_ROOT / "authorization-r005.json"
R005_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R005"
R005_REVIEW_ASSIGNMENT_REL = R005_REVIEW_ROOT / "review-assignment.json"
R005_REVIEW_RESULT_REL = R005_REVIEW_ROOT / "review-result.json"
R005_INDEPENDENT_REVIEW_REL = R005_REVIEW_ROOT / "independent-review.json"
R005_FROZEN_BINDINGS = {
    "authorization": {
        "path": R005_AUTHORIZATION_REL.as_posix(),
        "sha256": "4e4eb3312482082e031086f5241cea1ad6712679015b8d79e1707aa0cc853afa",
        "byte_length": 42_930,
    },
    "assignment": {
        "path": R005_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": "c72e15473c8f9d100c5605aa73ec131ed9810c65b8d7ac565ab94fae4d7155c8",
        "byte_length": 47_684,
    },
    "review_result": {
        "path": R005_REVIEW_RESULT_REL.as_posix(),
        "sha256": "7825341c2d87a589f2f4f641f18d7a94b301e67a828533ae5ef770416617c0cc",
        "byte_length": 756,
    },
    "independent_review": {
        "path": R005_INDEPENDENT_REVIEW_REL.as_posix(),
        "sha256": "4632d9c95b34e377fa806c11cb3b16644534be6e7efa565f1c703613635db4e0",
        "byte_length": 1_007,
    },
}
R005_REVIEW_PATHS = (
    R005_AUTHORIZATION_REL,
    R005_REVIEW_ASSIGNMENT_REL,
    R005_REVIEW_RESULT_REL,
    R005_INDEPENDENT_REVIEW_REL,
)
R005_REVIEW_RESULT_AT = "2026-08-28T03:22:33+09:00"
R005_INDEPENDENT_REVIEW_AT = "2026-08-28T03:28:13+09:00"
R005_PREFLIGHT_OBSERVED_AT = "2026-08-28T03:40:21+09:00"
R005_PREFLIGHT_VALIDATOR_STACK = (
    "_validate_projected_with_consumers",
    "goal_graph.validate",
    "validate_npc_single_admin_recovery_canonical_completion",
    "_npc_single_admin_recovery_sealed_product_successor_artifacts",
    "_npc_single_admin_recovery_completion_package",
    "_r002_legacy_completion_overlay",
    "validate_fp048_r002_seq91_97",
    "validate_fp048_r002_seq91_93",
    "_validate_fp048_r002_seq91_93",
    "_require_fp048_r002_seq98_execution_correction",
    "seq98.require_start_gate_execution_corrected_checkpoint",
    "seq98._load_physical_review",
    "seq98._rejected_r004_review_for_source",
    "seq98._failed_r003_execution_for_source",
    "seq98.require_exact_seq97_source",
    "seq97.require_snapshot_hygiene_corrected_checkpoint",
    "seq90.checkpoint_json_bytes",
    "json.dumps",
)
R005_PREFLIGHT_TERMINAL_ERROR = "KeyboardInterrupt"

R006_AUTHORIZATION_REL = TRANSITION_ROOT / "authorization-r006.json"
R006_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R006"
R006_REVIEW_ASSIGNMENT_REL = R006_REVIEW_ROOT / "review-assignment.json"
R006_REVIEW_RESULT_REL = R006_REVIEW_ROOT / "review-result.json"
R006_INDEPENDENT_REVIEW_REL = R006_REVIEW_ROOT / "independent-review.json"
R006_FROZEN_BINDINGS = {
    "authorization": {
        "path": R006_AUTHORIZATION_REL.as_posix(),
        "sha256": "4189050d81aa375fa8a1fe3ffbf90f9b8bbe4eb1d4355794f6e5ca817ee8801b",
        "byte_length": 50_899,
    },
    "assignment": {
        "path": R006_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": "006434e6f88b2ff1588655141a50c1387858c7538e6391fa1e11968297dfba6c",
        "byte_length": 55_653,
    },
    "review_result": {
        "path": R006_REVIEW_RESULT_REL.as_posix(),
        "sha256": "67358df4d23f7f8898ff5705683622e7e0d61a1a88feb99d243950481f1cdd1d",
        "byte_length": 756,
    },
    "independent_review": {
        "path": R006_INDEPENDENT_REVIEW_REL.as_posix(),
        "sha256": "9f6f7abd1095b0b8f2d4636abf2b5060aca930b692f0cbf0817330291fb7b5d9",
        "byte_length": 1_018,
    },
}
R006_REVIEW_PATHS = (
    R006_AUTHORIZATION_REL,
    R006_REVIEW_ASSIGNMENT_REL,
    R006_REVIEW_RESULT_REL,
    R006_INDEPENDENT_REVIEW_REL,
)
R006_REVIEW_RESULT_AT = "2026-08-28T04:20:43+09:00"
R006_INDEPENDENT_REVIEW_AT = "2026-08-28T04:23:49+09:00"
R006_PREFLIGHT_POST_ABORT_RECORDED_AT = "2026-08-28T04:33:00+09:00"
R006_PREFLIGHT_VALIDATOR_STACK = (
    "seq99.main",
    "seq99.prepare",
    "seq90._validate_projected_with_consumers",
    "goal_graph.validate",
    "goal_graph._validate",
    "validate_fp048_r002_seq91_98",
    "validate_fp048_r002_seq91_93",
    "_validate_fp048_r002_seq91_93",
    "_fp048_r002_successor_seq98_source",
    "reconstructed_seq98_checkpoint_bytes",
    "require_exact_seq98_source",
    "_require_exact_seq98_pins",
    "checkpoint_json_bytes",
    "json.dumps",
)
R006_PREFLIGHT_TERMINAL_ERROR = "KeyboardInterrupt"

R007_AUTHORIZATION_REL = TRANSITION_ROOT / "authorization-r007.json"
R007_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R007"
R007_REVIEW_ASSIGNMENT_REL = R007_REVIEW_ROOT / "review-assignment.json"
R007_REVIEW_RESULT_REL = R007_REVIEW_ROOT / "review-result.json"
R007_INDEPENDENT_REVIEW_REL = R007_REVIEW_ROOT / "independent-review.json"
R007_FROZEN_BINDINGS = {
    "authorization": {
        "path": R007_AUTHORIZATION_REL.as_posix(),
        "sha256": "da855dea2cad4476af38a9990ed9a5114544d0c1408f727833965ae9561ad2ed",
        "byte_length": 60_513,
    },
    "assignment": {
        "path": R007_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": "3d4c930732d16a6765a3820cdb9a52566c43aa32c2f4b14388efba0821fe05a9",
        "byte_length": 65_721,
    },
    "review_result": {
        "path": R007_REVIEW_RESULT_REL.as_posix(),
        "sha256": "eea72426a22710e933e5d5ab7b9c5379be5dd2d2044eba841ba085fdd3ad4f14",
        "byte_length": 756,
    },
    "independent_review": {
        "path": R007_INDEPENDENT_REVIEW_REL.as_posix(),
        "sha256": "70b49b9760a87c3b2724e7faa30b1be563c4f5b8f4ee5a80e9c3b943f909ca96",
        "byte_length": 1_018,
    },
}
R007_REVIEW_PATHS = (
    R007_AUTHORIZATION_REL,
    R007_REVIEW_ASSIGNMENT_REL,
    R007_REVIEW_RESULT_REL,
    R007_INDEPENDENT_REVIEW_REL,
)
R007_REVIEW_RESULT_AT = "2026-08-28T05:14:36+09:00"
R007_INDEPENDENT_REVIEW_AT = "2026-08-28T05:21:07+09:00"
R007_PREFLIGHT_POST_ABORT_RECORDED_AT = "2026-08-28T05:26:10+09:00"
R007_PREFLIGHT_VALIDATOR_STACK = (
    "seq99.main",
    "seq99.prepare",
    "seq90._validate_projected_with_consumers",
    "goal_graph.validate",
    "goal_graph._validate",
    "validate_npc_single_admin_recovery_canonical_completion",
    "_npc_single_admin_recovery_sealed_product_successor_artifacts",
    "_npc_single_admin_recovery_completion_package",
    "_fp022_seq66_67_successor_matches(current)",
    "_fp022_seq66_67_successor_matches(prefix)",
    "copy.deepcopy(history[:65])",
)
R007_PREFLIGHT_TERMINAL_ERROR = "KeyboardInterrupt"

R008_AUTHORIZATION_REL = TRANSITION_ROOT / "authorization-r008.json"
R008_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R008"
R008_REVIEW_ASSIGNMENT_REL = R008_REVIEW_ROOT / "review-assignment.json"
R008_REVIEW_RESULT_REL = R008_REVIEW_ROOT / "review-result.json"
R008_INDEPENDENT_REVIEW_REL = R008_REVIEW_ROOT / "independent-review.json"
R008_FROZEN_BINDINGS = {
    "authorization": {
        "path": R008_AUTHORIZATION_REL.as_posix(),
        "sha256": "262d7e076251ff0de231e8ba7dc83902f0f73f015d8ed7305fb86e15194520b6",
        "byte_length": 68_025,
    },
    "assignment": {
        "path": R008_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": "9a6b8e024dad855947eae819b5a1bfe1434d70264720ba7df8f1b3c6bfe403f2",
        "byte_length": 73_233,
    },
    "review_result": {
        "path": R008_REVIEW_RESULT_REL.as_posix(),
        "sha256": "a7aa97280df76e99b6bcadd3197de1c042df421fb7bb27f174ce3fcd3cfd35f0",
        "byte_length": 756,
    },
    "independent_review": {
        "path": R008_INDEPENDENT_REVIEW_REL.as_posix(),
        "sha256": "05591a76c6bad39ea0389c215cb028aeb897b56ab828f053eb0c2e62f90208c0",
        "byte_length": 1_020,
    },
}
R008_REVIEW_PATHS = (
    R008_AUTHORIZATION_REL,
    R008_REVIEW_ASSIGNMENT_REL,
    R008_REVIEW_RESULT_REL,
    R008_INDEPENDENT_REVIEW_REL,
)
R008_REVIEW_RESULT_AT = "2026-08-28T06:18:18+09:00"
R008_INDEPENDENT_REVIEW_AT = "2026-08-28T06:22:41+09:00"
R008_PREFLIGHT_TERMINAL_ERROR_PREFIX = (
    "FP048 R002 reviewed noncredit successor authority differs",
    "NPC/FP022 successor authority differs",
    "FP048 R002 reviewed successor composition differs",
)

R009_AUTHORIZATION_REL = TRANSITION_ROOT / "authorization-r009.json"
R009_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R009"
R009_REVIEW_ASSIGNMENT_REL = R009_REVIEW_ROOT / "review-assignment.json"
R009_REVIEW_RESULT_REL = R009_REVIEW_ROOT / "review-result.json"
R009_INDEPENDENT_REVIEW_REL = R009_REVIEW_ROOT / "independent-review.json"
R009_FROZEN_BINDINGS = {
    "authorization": {
        "path": R009_AUTHORIZATION_REL.as_posix(),
        "sha256": "ed15e72f3651df52e17bff822e9e640e3b403a942054880c2e2f7e88cb2ad51e",
        "byte_length": 74_981,
    },
    "assignment": {
        "path": R009_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": "bad7546feec5aa98d3d9baae6f980ffcd5de6f809552c1dff65be04933e21fac",
        "byte_length": 80_189,
    },
    "review_result": {
        "path": R009_REVIEW_RESULT_REL.as_posix(),
        "sha256": "db8f427ea965bae9bbf1d8aa6b80d68b6ecbc97cb96b7e9ed7a4fd257e064ea1",
        "byte_length": 756,
    },
    "independent_review": {
        "path": R009_INDEPENDENT_REVIEW_REL.as_posix(),
        "sha256": "c661cb8d829d6efebc04ae48e82e78af825f341b93e3cd6e068844ccb4eb1ebb",
        "byte_length": 1_020,
    },
}
R009_REVIEW_PATHS = (
    R009_AUTHORIZATION_REL,
    R009_REVIEW_ASSIGNMENT_REL,
    R009_REVIEW_RESULT_REL,
    R009_INDEPENDENT_REVIEW_REL,
)
R009_REVIEW_RESULT_AT = "2026-08-28T06:58:19+09:00"
R009_INDEPENDENT_REVIEW_AT = "2026-08-28T07:05:44+09:00"
R009_PROJECTED_EVENT_OCCURRED_AT = "2026-08-28T07:07:44+09:00"
R009_PROJECTED_EVENT_SHA256 = (
    "b33f1b38c063d3c3ac4da3eac8d683d7aa9e802b256674dcfef3832d01d6c26b"
)
R009_WRITE_TERMINAL_ERROR = "seq99 managed snapshot differs"

AUTHORIZATION_REL = TRANSITION_ROOT / "authorization-r010.json"
REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R010"
REVIEW_ASSIGNMENT_REL = REVIEW_ROOT / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_ROOT / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_ROOT / "independent-review.json"
REVIEW_PATHS = (
    REVIEW_ASSIGNMENT_REL,
    REVIEW_RESULT_REL,
    INDEPENDENT_REVIEW_REL,
)
REVIEW_ROUND_ID = "R010"
AUTHORIZATION_DOCUMENT_ID = "WS-FP048-R002-SEQ99-100-AUTHORIZATION-20260827-R010"
REVIEW_ASSIGNMENT_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ99-100-REVIEW-ASSIGNMENT-20260827-R010"
)
REVIEW_RESULT_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ99-100-REVIEW-RESULT-20260827-R010"
)
INDEPENDENT_REVIEW_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ99-100-INDEPENDENT-REVIEW-20260827-R010"
)

PRODUCER = {
    "id": "codex-fp048-r002-seq99-r010-correction-r010-producer-20260828",
    "task_id": "/root",
}
PRIMARY_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r010-primary-reviewer-20260828",
    "task_id": "/root/r007_preflight_perf_rootcause",
}
INDEPENDENT_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r010-independent-reviewer-20260828",
    "task_id": "/root/goalgraph_fp022_perf_test_design",
}

R009_PRODUCER = {
    "id": "codex-fp048-r002-seq99-r010-correction-r009-producer-20260828",
    "task_id": "/root",
}
R009_PRIMARY_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r009-primary-reviewer-20260828",
    "task_id": "/root/r007_preflight_perf_rootcause",
}
R009_INDEPENDENT_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r009-independent-reviewer-20260828",
    "task_id": "/root/goalgraph_fp022_perf_test_design",
}

R008_PRODUCER = {
    "id": "codex-fp048-r002-seq99-r010-correction-r008-producer-20260828",
    "task_id": "/root",
}
R008_PRIMARY_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r008-primary-reviewer-20260828",
    "task_id": "/root/r007_preflight_perf_rootcause",
}
R008_INDEPENDENT_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r008-independent-reviewer-20260828",
    "task_id": "/root/goalgraph_fp022_perf_test_design",
}

R007_PRODUCER = {
    "id": "codex-fp048-r002-seq99-r010-correction-r007-producer-20260828",
    "task_id": "/root",
}
R007_PRIMARY_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r007-primary-reviewer-20260828",
    "task_id": "/root/seq99_continuation_perf_audit",
}
R007_INDEPENDENT_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r007-independent-reviewer-20260828",
    "task_id": "/root/seq99_serialization_perf_audit",
}

R006_PRODUCER = {
    "id": "codex-fp048-r002-seq99-r010-correction-r006-producer-20260828",
    "task_id": "/root",
}
R006_PRIMARY_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r006-primary-reviewer-20260828",
    "task_id": "/root/seq99_continuation_perf_audit",
}
R006_INDEPENDENT_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r006-independent-reviewer-20260828",
    "task_id": "/root/seq99_serialization_perf_audit",
}

R005_PRODUCER = {
    "id": "codex-fp048-r002-seq99-r010-correction-r005-producer-20260828",
    "task_id": "/root/seq99_serialization_perf_audit",
}
R005_PRIMARY_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r005-primary-reviewer-20260828",
    "task_id": "/root/seq99_continuation_perf_audit",
}
R005_INDEPENDENT_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r005-independent-reviewer-20260828",
    "task_id": "/root/seq100_starter_impl",
}

R004_PRODUCER = {
    "id": "codex-fp048-r002-seq99-r010-correction-r004-producer-20260828",
    "task_id": "/root/seq99_r004_recovery_impl",
}
R004_PRIMARY_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r004-primary-reviewer-20260828",
    "task_id": "/root/seq99_r004_primary_review",
}
R004_INDEPENDENT_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r004-independent-reviewer-20260828",
    "task_id": "/root/seq99_r004_independent_review",
}

R003_PRODUCER = {
    "id": "codex-fp048-r002-seq99-r010-correction-r003-producer-20260828",
    "task_id": "/root/seq99_r003_recovery_impl",
}
R003_PRIMARY_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r003-primary-reviewer-20260828",
    "task_id": "/root/seq99_r003_primary_review",
}
R003_INDEPENDENT_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r003-independent-reviewer-20260828",
    "task_id": "/root/seq99_r003_independent_review",
}

R002_PRODUCER = {
    "id": "codex-fp048-r002-seq99-r010-correction-r002-producer-20260827",
    "task_id": "/root/seq99_r002_recovery_impl",
}
R002_PRIMARY_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r002-primary-reviewer-20260827",
    "task_id": "/root/seq99_r002_primary_review",
}
R002_INDEPENDENT_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-r002-independent-reviewer-20260827",
    "task_id": "/root/seq99_r002_independent_review",
}

R001_PRODUCER = {
    "id": "codex-fp048-r002-seq99-r010-correction-producer-20260827",
    "task_id": "/root/seq99_correction_impl",
}
R001_PRIMARY_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-primary-reviewer-20260827",
    "task_id": "/root/seq99_correction_primary_review",
}
R001_INDEPENDENT_REVIEWER = {
    "id": "codex-fp048-r002-seq99-correction-independent-reviewer-20260827",
    "task_id": "/root/seq99_correction_independent_review",
}

R001_PREFLIGHT_PIN_DRIFT_LABELS = (
    "FP048 R002 start-control reanchor authority differs: reviewed pin differs: "
    "scripts/check_walksafe_goal_graph_v2_4.py",
    "FP048 R002 start-control correction authority differs: reviewed pin differs: "
    "scripts/check_walksafe_goal_graph_v2_4.py",
    "FP048 R002 start-gate contract correction authority differs: reviewed pin "
    "differs: scripts/check_walksafe_goal_graph_v2_4.py",
    "FP048 R002 branch-semantics reanchor authority differs: reviewed pin differs: "
    "scripts/check_walksafe_goal_graph_v2_4.py",
    "FP048 R002 R006 start-gate contract correction authority differs: reviewed "
    "pin differs: scripts/check_walksafe_goal_graph_v2_4.py",
    "FP048 R002 R007 start-gate contract correction authority differs: reviewed "
    "pin differs: scripts/check_walksafe_goal_graph_v2_4.py",
    "FP048 R002 R008 start-gate contract correction authority differs: reviewed "
    "pin differs: scripts/check_walksafe_goal_graph_v2_4.py",
    "FP048 R002 R009 start-gate contract correction authority differs: reviewed "
    "pin differs: scripts/check_walksafe_goal_graph_v2_4.py",
    "FP048 R002 R009 execution-failure correction authority differs: reviewed pin "
    "differs: scripts/check_walksafe_goal_graph_v2_4.py",
)
R001_PREFLIGHT_TERMINAL_ERROR = (
    "FP048-R002 seq99 R010 execution correction: FAIL: projected continuation "
    "failed: " + " | ".join(R001_PREFLIGHT_PIN_DRIFT_LABELS)
)

REVIEWED_CONTROL_PATHS = tuple(
    sorted(
        {
            PUBLIC_GATE_RUNTIME_REL,
            PUBLIC_GATE_RUNTIME_TEST_REL,
            R011_CONTRACT_REL,
            R011_RUNNER_REL,
            R011_RUNNER_TEST_REL,
            POST_SEQ99_STAGE_TEST_REL,
            SCRIPT_REL,
            TEST_REL,
            SEQ98_EXECUTION_CORRECTION_REL,
            SEQ98_EXECUTION_CORRECTION_TEST_REL,
            SEQ100_STARTER_REL,
            SEQ100_STARTER_TEST_REL,
            CONTINUATION_SCRIPT_REL,
            CONTINUATION_TEST_REL,
            GOAL_GRAPH_SCRIPT_REL,
            GOAL_GRAPH_TEST_REL,
            LAYER_RUNNER_REL,
            REPOSITORY_CATALOG_TEST_REL,
            NODE_TOOLCHAIN_CHECKER_REL,
            NODE_TOOLCHAIN_LOCK_REL,
            GITIGNORE_REL,
            SEQ90_WRITER_REL,
            SEQ90_WRITER_TEST_REL,
            CAS_WRITER_REL,
            CAS_WRITER_TEST_REL,
        },
        key=lambda value: value.as_posix(),
    )
)

GATE_EVIDENCE_PREFIX = "docs/control/execution/goal-gates/"
CLAIM_BOUNDARY = copy.deepcopy(seq98.CLAIM_BOUNDARY)
CLAIM_BOUNDARY.update(
    {
        "goal_status_change_count": 0,
        "implementation_start_authorized": False,
    }
)
CURRENT_FOCUS = (
    "FP-048 R002 READY after seq99 R010 post-check failure correction; "
    "R010 -005 is consumed failed nonauthority and R011 is not yet run"
)
NEXT_ACTION = (
    "독립 검토된 R011 five-check preflight를 fresh -006 identity로 실행한다."
)
SCOPE = (
    "Add-only seq99 READY-to-READY correction after exact R010 -005 "
    "post-check context-revalidation failure; no product, formal, device, "
    "external, deployment, approval, or release credit."
)
HANDOFF_CURRENT_EPIC = "EPIC-03 / FP-048 R002 R010 execution correction"
HANDOFF_VERIFICATION_STATUS = "SEQ99_R011_CORRECTION_REVIEWED_ZERO_CREDIT"
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


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _binding(path: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": path.as_posix(),
        "sha256": sha256_bytes(raw),
        "byte_length": len(raw),
    }


def _strict_json_equal(left: Any, right: Any) -> bool:
    return type(left) is type(right) and left == right


def _is_exact_zero_findings(value: Any) -> bool:
    return (
        type(value) is dict
        and set(value) == {"P0", "P1", "P2"}
        and all(type(value[key]) is int and value[key] == 0 for key in value)
    )


def _parse_time(value: Any, label: str) -> datetime:
    require(type(value) is str, f"{label} differs")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise StartGateExecutionCorrectionError(f"{label} differs") from exc
    require(parsed.tzinfo is not None, f"{label} lacks timezone")
    return parsed


def _require_file_binding(value: Any, path: Path, label: str) -> None:
    require(
        isinstance(value, Mapping)
        and set(value) == {"path", "sha256", "byte_length"}
        and value.get("path") == path.as_posix()
        and type(value.get("sha256")) is str
        and seq90.SHA256_RE.fullmatch(value["sha256"]) is not None
        and type(value.get("byte_length")) is int
        and value["byte_length"] >= 0,
        f"{label} differs",
    )


def _observed_binding(root: Path, path: Path) -> dict[str, Any]:
    read = seq90._stable_read(root, path)
    return _binding(path, read.raw)


def _require_private_json(root: Path, path: Path) -> tuple[bytes, dict[str, Any]]:
    read = seq90._stable_read(root, path)
    info = (root / path).lstat()
    require(
        stat.S_ISREG(info.st_mode)
        and stat.S_IMODE(info.st_mode) == 0o600
        and info.st_uid == os.geteuid()
        and info.st_nlink == 1,
        f"private review authority differs: {path}",
    )
    value = seq90.strict_json(read.raw, path.as_posix())
    require(
        read.raw == seq90.canonical_json_bytes(value),
        f"private review canonical bytes differ: {path}",
    )
    return read.raw, value


def frozen_r001_review_chain(root: Path = ROOT) -> dict[str, dict[str, Any]]:
    """Validate R001 by frozen physical bytes, never by its stale live pins."""

    root = seq90._safe_root(root)
    paths = {
        "authorization": R001_AUTHORIZATION_REL,
        "assignment": R001_REVIEW_ASSIGNMENT_REL,
        "review_result": R001_REVIEW_RESULT_REL,
        "independent_review": R001_INDEPENDENT_REVIEW_REL,
    }
    documents: dict[str, dict[str, Any]] = {}
    observed: dict[str, dict[str, Any]] = {}
    for role, path in paths.items():
        try:
            raw, document = _require_private_json(root, path)
        except StartGateExecutionCorrectionError:
            raise
        except Exception as exc:
            raise StartGateExecutionCorrectionError(
                f"frozen seq99 R001 {role} physical authority differs"
            ) from exc
        binding = _binding(path, raw)
        require(
            binding == R001_FROZEN_BINDINGS[role],
            f"frozen seq99 R001 {role} bytes differ",
        )
        documents[role] = document
        observed[role] = binding

    authorization = documents["authorization"]
    assignment = documents["assignment"]
    result = documents["review_result"]
    independent = documents["independent_review"]
    reviewed_inputs = assignment.get("reviewed_control_inputs")
    require(
        authorization.get("document_id")
        == "WS-FP048-R002-SEQ99-100-AUTHORIZATION-20260827-R001"
        and authorization.get("authorized_producer") == R001_PRODUCER
        and authorization.get("required_primary_reviewer")
        == R001_PRIMARY_REVIEWER
        and authorization.get("required_independent_reviewer")
        == R001_INDEPENDENT_REVIEWER
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-ASSIGNMENT-20260827-R001"
        and assignment.get("round_id") == "R001"
        and assignment.get("authorization_binding") == observed["authorization"]
        and assignment.get("producer") == R001_PRODUCER
        and assignment.get("required_primary_reviewer") == R001_PRIMARY_REVIEWER
        and assignment.get("required_independent_reviewer")
        == R001_INDEPENDENT_REVIEWER
        and isinstance(reviewed_inputs, list)
        and all(isinstance(row, dict) for row in reviewed_inputs)
        and result.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-RESULT-20260827-R001"
        and result.get("round_id") == "R001"
        and result.get("assignment_binding") == observed["assignment"]
        and result.get("reviewer") == R001_PRIMARY_REVIEWER
        and result.get("decision") == "APPROVED"
        and result.get("findings") == {"P0": 0, "P1": 0, "P2": 0}
        and result.get("reviewed_at") == R001_REVIEW_RESULT_AT
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ99-100-INDEPENDENT-REVIEW-20260827-R001"
        and independent.get("round_id") == "R001"
        and independent.get("assignment_binding") == observed["assignment"]
        and independent.get("review_result_binding") == observed["review_result"]
        and independent.get("reviewer") == R001_INDEPENDENT_REVIEWER
        and independent.get("decision") == "APPROVED"
        and independent.get("findings") == {"P0": 0, "P1": 0, "P2": 0}
        and independent.get("reviewed_at") == R001_INDEPENDENT_REVIEW_AT,
        "frozen seq99 R001 internal chain differs",
    )
    require(
        _parse_time(R001_REVIEW_RESULT_AT, "R001 primary review time")
        < _parse_time(R001_INDEPENDENT_REVIEW_AT, "R001 independent review time"),
        "frozen seq99 R001 chronology differs",
    )
    return copy.deepcopy(observed)


def r001_projected_preflight_failure(
    root: Path,
    source: Mapping[str, Any],
) -> dict[str, Any]:
    """Bind the unsealed executor observation that superseded R001."""

    root = seq90._safe_root(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    failure = r010_execution_failure_binding(root)
    review_chain = frozen_r001_review_chain(root)
    require(
        len(R001_PREFLIGHT_PIN_DRIFT_LABELS) == 9
        and R001_PREFLIGHT_TERMINAL_ERROR.endswith(
            " | ".join(R001_PREFLIGHT_PIN_DRIFT_LABELS)
        )
        and all(
            label.endswith(
                "reviewed pin differs: scripts/check_walksafe_goal_graph_v2_4.py"
            )
            for label in R001_PREFLIGHT_PIN_DRIFT_LABELS
        ),
        "R001 projected preflight terminal error mapping differs",
    )
    return {
        "round_id": "R001",
        "status": "PROJECTED_PREFLIGHT_FAILED_BEFORE_WRITE",
        "authority_status": "NONAUTHORITY_EXECUTION_FAILED_PRE_CAS",
        "observed_command": "--preflight",
        "preflight_exit_code": 1,
        "validator_stage": "_validate_projected_with_consumers",
        "checkpoint_write_attempted": False,
        "checkpoint_published": False,
        "observation_basis": "EXECUTOR_OBSERVED_TERMINAL_OUTPUT_UNSEALED",
        "observation_timestamp_available": False,
        "review_decision": "APPROVED",
        "review_findings": {"P0": 0, "P1": 0, "P2": 0},
        "primary_reviewed_at": R001_REVIEW_RESULT_AT,
        "independent_reviewed_at": R001_INDEPENDENT_REVIEW_AT,
        "terminal_error": R001_PREFLIGHT_TERMINAL_ERROR,
        "terminal_error_label_count": len(R001_PREFLIGHT_PIN_DRIFT_LABELS),
        "terminal_error_labels": list(R001_PREFLIGHT_PIN_DRIFT_LABELS),
        "source_checkpoint_binding": _source_checkpoint_binding(failure),
        "review_chain_binding": review_chain,
    }


def frozen_r002_review_chain(
    root: Path = ROOT,
    source: Mapping[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    """Validate R002 by frozen physical bytes, never by stale reviewed pins."""

    root = seq90._safe_root(root)
    if source is None:
        _source_raw, source = load_exact_seq98_source(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    paths = {
        "authorization": R002_AUTHORIZATION_REL,
        "assignment": R002_REVIEW_ASSIGNMENT_REL,
        "review_result": R002_REVIEW_RESULT_REL,
        "independent_review": R002_INDEPENDENT_REVIEW_REL,
    }
    documents: dict[str, dict[str, Any]] = {}
    observed: dict[str, dict[str, Any]] = {}
    for role, path in paths.items():
        try:
            raw, document = _require_private_json(root, path)
        except StartGateExecutionCorrectionError:
            raise
        except Exception as exc:
            raise StartGateExecutionCorrectionError(
                f"frozen seq99 R002 {role} physical authority differs"
            ) from exc
        metadata = (root / path).lstat()
        require(
            stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_uid == 1000 == os.geteuid()
            and metadata.st_nlink == 1,
            f"frozen seq99 R002 {role} physical authority differs",
        )
        binding = _binding(path, raw)
        require(
            binding == R002_FROZEN_BINDINGS[role],
            f"frozen seq99 R002 {role} bytes differ",
        )
        documents[role] = document
        observed[role] = binding

    authorization = documents["authorization"]
    assignment = documents["assignment"]
    result = documents["review_result"]
    independent = documents["independent_review"]
    reviewed_inputs = assignment.get("reviewed_control_inputs")
    r001_failure = r001_projected_preflight_failure(root, source)
    require(
        authorization.get("document_id")
        == "WS-FP048-R002-SEQ99-100-AUTHORIZATION-20260827-R002"
        and authorization.get("authorized_producer") == R002_PRODUCER
        and authorization.get("required_primary_reviewer")
        == R002_PRIMARY_REVIEWER
        and authorization.get("required_independent_reviewer")
        == R002_INDEPENDENT_REVIEWER
        and authorization.get("supersedes_round_id") == "R001"
        and authorization.get("superseded_review_chain_binding")
        == R001_FROZEN_BINDINGS
        and authorization.get("r001_projected_preflight_failure") == r001_failure
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-ASSIGNMENT-20260827-R002"
        and assignment.get("round_id") == "R002"
        and assignment.get("authorization_binding") == observed["authorization"]
        and assignment.get("producer") == R002_PRODUCER
        and assignment.get("required_primary_reviewer") == R002_PRIMARY_REVIEWER
        and assignment.get("required_independent_reviewer")
        == R002_INDEPENDENT_REVIEWER
        and assignment.get("supersedes_round_id") == "R001"
        and assignment.get("superseded_review_chain_binding")
        == R001_FROZEN_BINDINGS
        and assignment.get("r001_projected_preflight_failure") == r001_failure
        and isinstance(reviewed_inputs, list)
        and all(isinstance(row, dict) for row in reviewed_inputs)
        and result.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-RESULT-20260827-R002"
        and result.get("round_id") == "R002"
        and result.get("assignment_binding") == observed["assignment"]
        and result.get("reviewer") == R002_PRIMARY_REVIEWER
        and result.get("decision") == "APPROVED"
        and result.get("findings") == {"P0": 0, "P1": 0, "P2": 0}
        and result.get("reviewed_at") == R002_REVIEW_RESULT_AT
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ99-100-INDEPENDENT-REVIEW-20260827-R002"
        and independent.get("round_id") == "R002"
        and independent.get("assignment_binding") == observed["assignment"]
        and independent.get("review_result_binding") == observed["review_result"]
        and independent.get("reviewer") == R002_INDEPENDENT_REVIEWER
        and independent.get("decision") == "APPROVED"
        and independent.get("findings") == {"P0": 0, "P1": 0, "P2": 0}
        and independent.get("reviewed_at") == R002_INDEPENDENT_REVIEW_AT,
        "frozen seq99 R002 internal chain differs",
    )
    require(
        _parse_time(R001_INDEPENDENT_REVIEW_AT, "R001 independent review time")
        < _parse_time(R002_REVIEW_RESULT_AT, "R002 primary review time")
        < _parse_time(R002_INDEPENDENT_REVIEW_AT, "R002 independent review time"),
        "frozen seq99 R002 chronology differs",
    )
    return copy.deepcopy(observed)


def r002_projected_preflight_failure(
    root: Path,
    source: Mapping[str, Any],
) -> dict[str, Any]:
    """Bind the unsealed R002 P0 performance abort before any CAS write."""

    root = seq90._safe_root(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    failure = r010_execution_failure_binding(root)
    review_chain = frozen_r002_review_chain(root, source)
    require(
        len(R002_PREFLIGHT_VALIDATOR_STACK) == 8
        and R002_PREFLIGHT_VALIDATOR_STACK[0]
        == "_validate_projected_with_consumers"
        and R002_PREFLIGHT_VALIDATOR_STACK[-1]
        == "_fp048_r002_repository_context_live_successors",
        "R002 projected preflight stack mapping differs",
    )
    require(
        _parse_time(R002_INDEPENDENT_REVIEW_AT, "R002 independent review time")
        < _parse_time(R002_PREFLIGHT_OBSERVED_AT, "R002 preflight observation time"),
        "R002 projected preflight observation chronology differs",
    )
    return {
        "round_id": "R002",
        "status": "PREFLIGHT_ABORTED_BY_OPERATOR_AFTER_POST_REVIEW_P0_BEFORE_CAS",
        "authority_status": (
            "NONAUTHORITY_POST_REVIEW_P0_PREFLIGHT_ABORTED_PRE_CAS"
        ),
        "observed_command": "--preflight",
        "preflight_exit_code": 130,
        "elapsed_time": "GREATER_THAN_74_MINUTES_CPU_BOUND",
        "operator_signal": "SIGINT",
        "validator_stage": R002_PREFLIGHT_VALIDATOR_STACK[0],
        "validator_stack": list(R002_PREFLIGHT_VALIDATOR_STACK),
        "post_review_finding": {
            "severity": "P0",
            "class": "PREFLIGHT_PERFORMANCE_DEFECT",
        },
        "checkpoint_write_attempted": False,
        "checkpoint_cas_attempted": False,
        "checkpoint_published": False,
        "temporary_artifact_cleaned": True,
        "observation_basis": "EXECUTOR_OBSERVED_KEYBOARD_INTERRUPT_UNSEALED",
        "observation_timestamp_available": True,
        "observed_at": R002_PREFLIGHT_OBSERVED_AT,
        "terminal_error": R002_PREFLIGHT_TERMINAL_ERROR,
        "terminal_output_sealed": False,
        "review_decision": "APPROVED",
        "review_findings": {"P0": 0, "P1": 0, "P2": 0},
        "primary_reviewed_at": R002_REVIEW_RESULT_AT,
        "independent_reviewed_at": R002_INDEPENDENT_REVIEW_AT,
        "source_checkpoint_binding": _source_checkpoint_binding(failure),
        "review_chain_binding": review_chain,
    }


def frozen_r003_partial_review(
    root: Path = ROOT,
    source: Mapping[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    """Validate R003 authorization/assignment and absent decision documents."""

    root = seq90._safe_root(root)
    if source is None:
        _source_raw, source = load_exact_seq98_source(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    r001_failure = r001_projected_preflight_failure(root, source)
    r002_failure = r002_projected_preflight_failure(root, source)
    paths = {
        "authorization": R003_AUTHORIZATION_REL,
        "assignment": R003_REVIEW_ASSIGNMENT_REL,
    }
    documents: dict[str, dict[str, Any]] = {}
    observed: dict[str, dict[str, Any]] = {}
    for role, path in paths.items():
        try:
            raw, document = _require_private_json(root, path)
        except StartGateExecutionCorrectionError:
            raise
        except Exception as exc:
            raise StartGateExecutionCorrectionError(
                f"frozen seq99 R003 {role} physical authority differs"
            ) from exc
        metadata = (root / path).lstat()
        require(
            stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_uid == 1000 == os.geteuid()
            and metadata.st_nlink == 1,
            f"frozen seq99 R003 {role} physical authority differs",
        )
        binding = _binding(path, raw)
        require(
            binding == R003_FROZEN_BINDINGS[role],
            f"frozen seq99 R003 {role} bytes differ",
        )
        documents[role] = document
        observed[role] = binding

    require(
        all(not os.path.lexists(root / path) for path in R003_DECISION_PATHS),
        "frozen seq99 R003 decision document unexpectedly exists",
    )
    authorization = documents["authorization"]
    assignment = documents["assignment"]
    reviewed_inputs = assignment.get("reviewed_control_inputs")
    require(
        authorization.get("document_id")
        == "WS-FP048-R002-SEQ99-100-AUTHORIZATION-20260827-R003"
        and authorization.get("authorization_status")
        == "AUTHORIZED_FOR_SEQ99_R010_EXECUTION_CORRECTION_R003_RECOVERY"
        and authorization.get("authorized_producer") == R003_PRODUCER
        and authorization.get("required_primary_reviewer")
        == R003_PRIMARY_REVIEWER
        and authorization.get("required_independent_reviewer")
        == R003_INDEPENDENT_REVIEWER
        and authorization.get("supersedes_round_id") == "R002"
        and authorization.get("superseded_review_chain_binding")
        == R002_FROZEN_BINDINGS
        and authorization.get("r001_projected_preflight_failure") == r001_failure
        and authorization.get("r002_projected_preflight_failure") == r002_failure
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-ASSIGNMENT-20260827-R003"
        and assignment.get("round_id") == "R003"
        and assignment.get("candidate_status")
        == "FINAL_REVIEW_CANDIDATE_R003_RECOVERY"
        and assignment.get("authorization_binding") == observed["authorization"]
        and assignment.get("producer") == R003_PRODUCER
        and assignment.get("required_primary_reviewer") == R003_PRIMARY_REVIEWER
        and assignment.get("required_independent_reviewer")
        == R003_INDEPENDENT_REVIEWER
        and assignment.get("supersedes_round_id") == "R002"
        and assignment.get("superseded_review_chain_binding")
        == R002_FROZEN_BINDINGS
        and assignment.get("r001_projected_preflight_failure") == r001_failure
        and assignment.get("r002_projected_preflight_failure") == r002_failure
        and _is_exact_zero_findings(assignment.get("required_findings"))
        and isinstance(reviewed_inputs, list)
        and all(isinstance(row, dict) for row in reviewed_inputs),
        "frozen seq99 R003 partial review chain differs",
    )
    return copy.deepcopy(observed)


def r003_primary_review_rejection(
    root: Path,
    source: Mapping[str, Any],
) -> dict[str, Any]:
    """Bind the R003 P1 rejection before result/independent documents."""

    root = seq90._safe_root(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    failure = r010_execution_failure_binding(root)
    partial = frozen_r003_partial_review(root, source)
    require(
        _parse_time(R002_PREFLIGHT_OBSERVED_AT, "R002 preflight observation time")
        < _parse_time(
            R003_PRIMARY_REVIEW_REJECTED_AT,
            "R003 primary review rejection time",
        ),
        "R003 primary review rejection chronology differs",
    )
    return {
        "round_id": "R003",
        "status": "NONAUTHORITY_PRIMARY_REVIEW_P1_REJECTED_BEFORE_RESULT",
        "authority_status": (
            "NONAUTHORITY_PRIMARY_REVIEW_P1_REJECTED_BEFORE_RESULT"
        ),
        "review_stage": "PRIMARY_REVIEW",
        "review_decision": "REJECTED",
        "review_findings": {"P0": 0, "P1": 1, "P2": 0},
        "finding": {
            "severity": "P1",
            "class": "BOOLEAN_INTEGER_TYPE_CONFUSION_IN_REVIEW_FINDINGS",
        },
        "review_result_written": False,
        "independent_review_written": False,
        "checkpoint_write_attempted": False,
        "checkpoint_cas_attempted": False,
        "checkpoint_published": False,
        "observation_basis": "PRIMARY_REVIEWER_REJECTED_BEFORE_RESULT_WRITE",
        "observed_at": R003_PRIMARY_REVIEW_REJECTED_AT,
        "source_checkpoint_binding": _source_checkpoint_binding(failure),
        "review_partial_binding": partial,
    }


def frozen_r004_review_chain(
    root: Path = ROOT,
    source: Mapping[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    """Validate the complete R004 review by exact immutable physical bytes."""

    root = seq90._safe_root(root)
    if source is None:
        _source_raw, source = load_exact_seq98_source(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    r001_failure = r001_projected_preflight_failure(root, source)
    r002_failure = r002_projected_preflight_failure(root, source)
    r003_rejection = r003_primary_review_rejection(root, source)
    paths = {
        "authorization": R004_AUTHORIZATION_REL,
        "assignment": R004_REVIEW_ASSIGNMENT_REL,
        "review_result": R004_REVIEW_RESULT_REL,
        "independent_review": R004_INDEPENDENT_REVIEW_REL,
    }
    documents: dict[str, dict[str, Any]] = {}
    observed: dict[str, dict[str, Any]] = {}
    for role, path in paths.items():
        try:
            raw, document = _require_private_json(root, path)
        except StartGateExecutionCorrectionError:
            raise
        except Exception as exc:
            raise StartGateExecutionCorrectionError(
                f"frozen seq99 R004 {role} physical authority differs"
            ) from exc
        metadata = (root / path).lstat()
        require(
            stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_uid == 1000 == os.geteuid()
            and metadata.st_nlink == 1,
            f"frozen seq99 R004 {role} physical authority differs",
        )
        binding = _binding(path, raw)
        require(
            binding == R004_FROZEN_BINDINGS[role],
            f"frozen seq99 R004 {role} bytes differ",
        )
        documents[role] = document
        observed[role] = binding

    authorization = documents["authorization"]
    assignment = documents["assignment"]
    result = documents["review_result"]
    independent = documents["independent_review"]
    reviewed_inputs = assignment.get("reviewed_control_inputs")
    require(
        authorization.get("document_id")
        == "WS-FP048-R002-SEQ99-100-AUTHORIZATION-20260827-R004"
        and authorization.get("authorization_status")
        == "AUTHORIZED_FOR_SEQ99_R010_EXECUTION_CORRECTION_R004_RECOVERY"
        and authorization.get("authorized_producer") == R004_PRODUCER
        and authorization.get("required_primary_reviewer")
        == R004_PRIMARY_REVIEWER
        and authorization.get("required_independent_reviewer")
        == R004_INDEPENDENT_REVIEWER
        and authorization.get("supersedes_round_id") == "R003"
        and authorization.get("superseded_review_partial_binding")
        == R003_FROZEN_BINDINGS
        and authorization.get("r001_projected_preflight_failure") == r001_failure
        and authorization.get("r002_projected_preflight_failure") == r002_failure
        and authorization.get("r003_primary_review_rejection") == r003_rejection
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-ASSIGNMENT-20260827-R004"
        and assignment.get("round_id") == "R004"
        and assignment.get("candidate_status")
        == "FINAL_REVIEW_CANDIDATE_R004_RECOVERY"
        and assignment.get("authorization_binding") == observed["authorization"]
        and assignment.get("producer") == R004_PRODUCER
        and assignment.get("required_primary_reviewer") == R004_PRIMARY_REVIEWER
        and assignment.get("required_independent_reviewer")
        == R004_INDEPENDENT_REVIEWER
        and assignment.get("supersedes_round_id") == "R003"
        and assignment.get("superseded_review_partial_binding")
        == R003_FROZEN_BINDINGS
        and assignment.get("r001_projected_preflight_failure") == r001_failure
        and assignment.get("r002_projected_preflight_failure") == r002_failure
        and assignment.get("r003_primary_review_rejection") == r003_rejection
        and _is_exact_zero_findings(assignment.get("required_findings"))
        and isinstance(reviewed_inputs, list)
        and all(isinstance(row, dict) for row in reviewed_inputs)
        and result.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-RESULT-20260827-R004"
        and result.get("round_id") == "R004"
        and result.get("assignment_binding") == observed["assignment"]
        and result.get("reviewer") == R004_PRIMARY_REVIEWER
        and result.get("decision") == "APPROVED"
        and _is_exact_zero_findings(result.get("findings"))
        and result.get("reviewed_at") == R004_REVIEW_RESULT_AT
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ99-100-INDEPENDENT-REVIEW-20260827-R004"
        and independent.get("round_id") == "R004"
        and independent.get("assignment_binding") == observed["assignment"]
        and independent.get("review_result_binding") == observed["review_result"]
        and independent.get("reviewer") == R004_INDEPENDENT_REVIEWER
        and independent.get("decision") == "APPROVED"
        and _is_exact_zero_findings(independent.get("findings"))
        and independent.get("reviewed_at") == R004_INDEPENDENT_REVIEW_AT,
        "frozen seq99 R004 internal chain differs",
    )
    require(
        _parse_time(
            R003_PRIMARY_REVIEW_REJECTED_AT,
            "R003 primary review rejection time",
        )
        < _parse_time(R004_REVIEW_RESULT_AT, "R004 primary review time")
        < _parse_time(R004_INDEPENDENT_REVIEW_AT, "R004 independent review time"),
        "frozen seq99 R004 chronology differs",
    )
    return copy.deepcopy(observed)


def r004_projected_preflight_failure(
    root: Path,
    source: Mapping[str, Any],
) -> dict[str, Any]:
    """Bind the R004 continuation-performance abort before any CAS write."""

    root = seq90._safe_root(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    failure = r010_execution_failure_binding(root)
    review_chain = frozen_r004_review_chain(root, source)
    require(
        len(R004_PREFLIGHT_VALIDATOR_STACK) == 14
        and R004_PREFLIGHT_VALIDATOR_STACK[0]
        == "_validate_projected_with_consumers"
        and R004_PREFLIGHT_VALIDATOR_STACK[-1] == "json.dumps"
        and "_fp048_r002_current_descendant_seq96_source"
        in R004_PREFLIGHT_VALIDATOR_STACK
        and "_require_frozen_seq95_checkpoint"
        in R004_PREFLIGHT_VALIDATOR_STACK,
        "R004 projected preflight stack mapping differs",
    )
    require(
        _parse_time(R004_INDEPENDENT_REVIEW_AT, "R004 independent review time")
        < _parse_time(R004_PREFLIGHT_OBSERVED_AT, "R004 preflight observation time"),
        "R004 projected preflight observation chronology differs",
    )
    return {
        "round_id": "R004",
        "status": "NONAUTHORITY_POST_REVIEW_P0_PREFLIGHT_ABORTED_PRE_CAS",
        "authority_status": (
            "NONAUTHORITY_POST_REVIEW_P0_PREFLIGHT_ABORTED_PRE_CAS"
        ),
        "observed_command": "--preflight",
        "preflight_exit_code": 1,
        "elapsed_time": "GREATER_THAN_10_MINUTES_CPU_BOUND",
        "operator_signal": "SIGINT",
        "validator_stage": R004_PREFLIGHT_VALIDATOR_STACK[0],
        "validator_stack": list(R004_PREFLIGHT_VALIDATOR_STACK),
        "post_review_finding": {
            "severity": "P0",
            "class": "CONTINUATION_DESCENDANT_RECONSTRUCTION_PERFORMANCE_DEFECT",
        },
        "checkpoint_write_attempted": False,
        "checkpoint_cas_attempted": False,
        "checkpoint_published": False,
        "temporary_artifact_cleaned": True,
        "observation_basis": "EXECUTOR_OBSERVED_KEYBOARD_INTERRUPT_UNSEALED",
        "observation_timestamp_available": True,
        "observed_at": R004_PREFLIGHT_OBSERVED_AT,
        "terminal_error": R004_PREFLIGHT_TERMINAL_ERROR,
        "terminal_output_sealed": False,
        "review_decision": "APPROVED",
        "review_findings": {"P0": 0, "P1": 0, "P2": 0},
        "primary_reviewed_at": R004_REVIEW_RESULT_AT,
        "independent_reviewed_at": R004_INDEPENDENT_REVIEW_AT,
        "source_checkpoint_binding": _source_checkpoint_binding(failure),
        "review_chain_binding": review_chain,
    }


def frozen_r005_review_chain(
    root: Path = ROOT,
    source: Mapping[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    """Validate the complete R005 review by exact immutable physical bytes."""

    root = seq90._safe_root(root)
    if source is None:
        _source_raw, source = load_exact_seq98_source(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    r001_failure = r001_projected_preflight_failure(root, source)
    r002_failure = r002_projected_preflight_failure(root, source)
    r003_rejection = r003_primary_review_rejection(root, source)
    r004_failure = r004_projected_preflight_failure(root, source)
    paths = {
        "authorization": R005_AUTHORIZATION_REL,
        "assignment": R005_REVIEW_ASSIGNMENT_REL,
        "review_result": R005_REVIEW_RESULT_REL,
        "independent_review": R005_INDEPENDENT_REVIEW_REL,
    }
    documents: dict[str, dict[str, Any]] = {}
    observed: dict[str, dict[str, Any]] = {}
    for role, path in paths.items():
        try:
            raw, document = _require_private_json(root, path)
        except StartGateExecutionCorrectionError:
            raise
        except Exception as exc:
            raise StartGateExecutionCorrectionError(
                f"frozen seq99 R005 {role} physical authority differs"
            ) from exc
        metadata = (root / path).lstat()
        require(
            stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_uid == 1000 == os.geteuid()
            and metadata.st_nlink == 1,
            f"frozen seq99 R005 {role} physical authority differs",
        )
        binding = _binding(path, raw)
        require(
            binding == R005_FROZEN_BINDINGS[role],
            f"frozen seq99 R005 {role} bytes differ",
        )
        documents[role] = document
        observed[role] = binding

    authorization = documents["authorization"]
    assignment = documents["assignment"]
    result = documents["review_result"]
    independent = documents["independent_review"]
    reviewed_inputs = assignment.get("reviewed_control_inputs")
    require(
        authorization.get("document_id")
        == "WS-FP048-R002-SEQ99-100-AUTHORIZATION-20260827-R005"
        and authorization.get("authorization_status")
        == "AUTHORIZED_FOR_SEQ99_R010_EXECUTION_CORRECTION_R005_RECOVERY"
        and authorization.get("authorized_producer") == R005_PRODUCER
        and authorization.get("required_primary_reviewer")
        == R005_PRIMARY_REVIEWER
        and authorization.get("required_independent_reviewer")
        == R005_INDEPENDENT_REVIEWER
        and authorization.get("supersedes_round_id") == "R004"
        and authorization.get("superseded_review_chain_binding")
        == R004_FROZEN_BINDINGS
        and authorization.get("r001_projected_preflight_failure") == r001_failure
        and authorization.get("r002_projected_preflight_failure") == r002_failure
        and authorization.get("r003_primary_review_rejection") == r003_rejection
        and authorization.get("r004_projected_preflight_failure") == r004_failure
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-ASSIGNMENT-20260827-R005"
        and assignment.get("round_id") == "R005"
        and assignment.get("candidate_status")
        == "FINAL_REVIEW_CANDIDATE_R005_RECOVERY"
        and assignment.get("authorization_binding") == observed["authorization"]
        and assignment.get("producer") == R005_PRODUCER
        and assignment.get("required_primary_reviewer") == R005_PRIMARY_REVIEWER
        and assignment.get("required_independent_reviewer")
        == R005_INDEPENDENT_REVIEWER
        and assignment.get("supersedes_round_id") == "R004"
        and assignment.get("superseded_review_chain_binding")
        == R004_FROZEN_BINDINGS
        and assignment.get("r001_projected_preflight_failure") == r001_failure
        and assignment.get("r002_projected_preflight_failure") == r002_failure
        and assignment.get("r003_primary_review_rejection") == r003_rejection
        and assignment.get("r004_projected_preflight_failure") == r004_failure
        and _is_exact_zero_findings(assignment.get("required_findings"))
        and isinstance(reviewed_inputs, list)
        and all(isinstance(row, dict) for row in reviewed_inputs)
        and result.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-RESULT-20260827-R005"
        and result.get("round_id") == "R005"
        and result.get("assignment_binding") == observed["assignment"]
        and result.get("reviewer") == R005_PRIMARY_REVIEWER
        and result.get("decision") == "APPROVED"
        and _is_exact_zero_findings(result.get("findings"))
        and result.get("reviewed_at") == R005_REVIEW_RESULT_AT
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ99-100-INDEPENDENT-REVIEW-20260827-R005"
        and independent.get("round_id") == "R005"
        and independent.get("assignment_binding") == observed["assignment"]
        and independent.get("review_result_binding") == observed["review_result"]
        and independent.get("reviewer") == R005_INDEPENDENT_REVIEWER
        and independent.get("decision") == "APPROVED"
        and _is_exact_zero_findings(independent.get("findings"))
        and independent.get("reviewed_at") == R005_INDEPENDENT_REVIEW_AT,
        "frozen seq99 R005 internal chain differs",
    )
    require(
        _parse_time(
            R004_PREFLIGHT_OBSERVED_AT,
            "R004 preflight observation time",
        )
        < _parse_time(R005_REVIEW_RESULT_AT, "R005 primary review time")
        < _parse_time(R005_INDEPENDENT_REVIEW_AT, "R005 independent review time"),
        "frozen seq99 R005 chronology differs",
    )
    return copy.deepcopy(observed)


def r005_projected_preflight_failure(
    root: Path,
    source: Mapping[str, Any],
) -> dict[str, Any]:
    """Bind the R005 GoalGraph replay abort before any CAS write."""

    root = seq90._safe_root(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    failure = r010_execution_failure_binding(root)
    review_chain = frozen_r005_review_chain(root, source)
    require(
        len(R005_PREFLIGHT_VALIDATOR_STACK) == 18
        and R005_PREFLIGHT_VALIDATOR_STACK[0]
        == "_validate_projected_with_consumers"
        and R005_PREFLIGHT_VALIDATOR_STACK[-1] == "json.dumps"
        and "_r002_legacy_completion_overlay"
        in R005_PREFLIGHT_VALIDATOR_STACK
        and "_require_fp048_r002_seq98_execution_correction"
        in R005_PREFLIGHT_VALIDATOR_STACK,
        "R005 projected preflight stack mapping differs",
    )
    require(
        _parse_time(R005_INDEPENDENT_REVIEW_AT, "R005 independent review time")
        < _parse_time(R005_PREFLIGHT_OBSERVED_AT, "R005 preflight observation time"),
        "R005 projected preflight observation chronology differs",
    )
    return {
        "round_id": "R005",
        "status": "NONAUTHORITY_POST_REVIEW_P0_PREFLIGHT_ABORTED_PRE_CAS",
        "authority_status": (
            "NONAUTHORITY_POST_REVIEW_P0_PREFLIGHT_ABORTED_PRE_CAS"
        ),
        "observed_command": "--preflight",
        "preflight_exit_code": 130,
        "elapsed_time": "GREATER_THAN_10_MINUTES_CPU_BOUND",
        "elapsed_seconds": "628.97",
        "cpu_percent": "99%",
        "max_rss_kb": 223_160,
        "operator_signal": "SIGINT",
        "validator_stage": R005_PREFLIGHT_VALIDATOR_STACK[0],
        "validator_stack": list(R005_PREFLIGHT_VALIDATOR_STACK),
        "post_review_finding": {
            "severity": "P0",
            "class": (
                "GOAL_GRAPH_LEGACY_COMPLETION_OVERLAY_REPLAY_"
                "PERFORMANCE_DEFECT"
            ),
        },
        "checkpoint_write_attempted": False,
        "checkpoint_cas_attempted": False,
        "checkpoint_published": False,
        "temporary_artifact_cleaned": True,
        "observation_basis": "EXECUTOR_OBSERVED_KEYBOARD_INTERRUPT_UNSEALED",
        "observation_timestamp_available": True,
        "observed_at": R005_PREFLIGHT_OBSERVED_AT,
        "terminal_error": R005_PREFLIGHT_TERMINAL_ERROR,
        "terminal_output_sealed": False,
        "review_decision": "APPROVED",
        "review_findings": {"P0": 0, "P1": 0, "P2": 0},
        "primary_reviewed_at": R005_REVIEW_RESULT_AT,
        "independent_reviewed_at": R005_INDEPENDENT_REVIEW_AT,
        "source_checkpoint_binding": _source_checkpoint_binding(failure),
        "review_chain_binding": review_chain,
    }


def frozen_r006_review_chain(
    root: Path = ROOT,
    source: Mapping[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    """Validate all R006 files while granting them no publication authority."""

    root = seq90._safe_root(root)
    if source is None:
        _source_raw, source = load_exact_seq98_source(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    paths = {
        "authorization": R006_AUTHORIZATION_REL,
        "assignment": R006_REVIEW_ASSIGNMENT_REL,
        "review_result": R006_REVIEW_RESULT_REL,
        "independent_review": R006_INDEPENDENT_REVIEW_REL,
    }
    documents: dict[str, dict[str, Any]] = {}
    observed: dict[str, dict[str, Any]] = {}
    for role, path in paths.items():
        try:
            raw, document = _require_private_json(root, path)
        except StartGateExecutionCorrectionError:
            raise
        except Exception as exc:
            raise StartGateExecutionCorrectionError(
                f"frozen seq99 R006 {role} physical authority differs"
            ) from exc
        metadata = (root / path).lstat()
        require(
            stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_uid == 1000 == os.geteuid()
            and metadata.st_nlink == 1,
            f"frozen seq99 R006 {role} physical authority differs",
        )
        binding = _binding(path, raw)
        require(
            binding == R006_FROZEN_BINDINGS[role],
            f"frozen seq99 R006 {role} bytes differ",
        )
        documents[role] = document
        observed[role] = binding

    authorization = documents["authorization"]
    assignment = documents["assignment"]
    result = documents["review_result"]
    independent = documents["independent_review"]
    reviewed_inputs = assignment.get("reviewed_control_inputs")
    require(
        authorization.get("document_id")
        == "WS-FP048-R002-SEQ99-100-AUTHORIZATION-20260827-R006"
        and authorization.get("authorization_status")
        == "AUTHORIZED_FOR_SEQ99_R010_EXECUTION_CORRECTION_R006_RECOVERY"
        and authorization.get("authorized_producer") == R006_PRODUCER
        and authorization.get("required_primary_reviewer")
        == R006_PRIMARY_REVIEWER
        and authorization.get("required_independent_reviewer")
        == R006_INDEPENDENT_REVIEWER
        and authorization.get("supersedes_round_id") == "R005"
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-ASSIGNMENT-20260827-R006"
        and assignment.get("round_id") == "R006"
        and assignment.get("candidate_status")
        == "FINAL_REVIEW_CANDIDATE_R006_RECOVERY"
        and assignment.get("authorization_binding") == observed["authorization"]
        and assignment.get("producer") == R006_PRODUCER
        and assignment.get("required_primary_reviewer") == R006_PRIMARY_REVIEWER
        and assignment.get("required_independent_reviewer")
        == R006_INDEPENDENT_REVIEWER
        and assignment.get("supersedes_round_id") == "R005"
        and _is_exact_zero_findings(assignment.get("required_findings"))
        and isinstance(reviewed_inputs, list)
        and all(isinstance(row, dict) for row in reviewed_inputs)
        and result.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-RESULT-20260827-R006"
        and result.get("round_id") == "R006"
        and result.get("assignment_binding") == observed["assignment"]
        and result.get("reviewer") == R006_PRIMARY_REVIEWER
        and result.get("decision") == "APPROVED"
        and _is_exact_zero_findings(result.get("findings"))
        and result.get("reviewed_at") == R006_REVIEW_RESULT_AT
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ99-100-INDEPENDENT-REVIEW-20260827-R006"
        and independent.get("round_id") == "R006"
        and independent.get("assignment_binding") == observed["assignment"]
        and independent.get("review_result_binding") == observed["review_result"]
        and independent.get("reviewer") == R006_INDEPENDENT_REVIEWER
        and independent.get("decision") == "APPROVED"
        and _is_exact_zero_findings(independent.get("findings"))
        and independent.get("reviewed_at") == R006_INDEPENDENT_REVIEW_AT,
        "frozen seq99 R006 internal chain differs",
    )
    require(
        _parse_time(R005_PREFLIGHT_OBSERVED_AT, "R005 preflight observation time")
        < _parse_time(R006_REVIEW_RESULT_AT, "R006 post-rejection result time")
        < _parse_time(
            R006_INDEPENDENT_REVIEW_AT,
            "R006 post-rejection independent time",
        ),
        "frozen seq99 R006 document chronology differs",
    )
    return copy.deepcopy(observed)


def r006_rejected_review_and_invalid_preflight(
    root: Path,
    source: Mapping[str, Any],
) -> dict[str, Any]:
    """Bind the consumed R006 rejection and every later nonauthority action."""

    root = seq90._safe_root(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    failure = r010_execution_failure_binding(root)
    review_chain = frozen_r006_review_chain(root, source)
    require(
        len(R006_PREFLIGHT_VALIDATOR_STACK) == 14
        and R006_PREFLIGHT_VALIDATOR_STACK[:2] == ("seq99.main", "seq99.prepare")
        and R006_PREFLIGHT_VALIDATOR_STACK[-1] == "json.dumps"
        and "_fp048_r002_successor_seq98_source"
        in R006_PREFLIGHT_VALIDATOR_STACK,
        "R006 invalid-reuse preflight stack mapping differs",
    )
    require(
        _parse_time(
            R006_INDEPENDENT_REVIEW_AT,
            "R006 post-rejection independent time",
        )
        < _parse_time(
            R006_PREFLIGHT_POST_ABORT_RECORDED_AT,
            "R006 invalid-reuse preflight post-abort record time",
        ),
        "R006 invalid-reuse record chronology differs",
    )
    return {
        "round_id": "R006",
        "status": "NONAUTHORITY_PRIMARY_REVIEW_P1_REJECTED",
        "authority_status": "NONAUTHORITY_PRIMARY_REVIEW_P1_REJECTED",
        "unsealed_formal_primary_review_observation": {
            "decision": "REJECTED",
            "findings": {"P0": 0, "P1": 1, "P2": 0},
            "finding_class": (
                "REPOSITORY_CATALOG_MISSING_R006_AUTHORIZATION_ASSIGNMENT"
            ),
            "stored_catalog_path_count": 4_220,
            "live_path_count": 4_222,
            "catalog_test_result": {"failed": 2, "passed": 17},
            "missing_paths": [
                R006_AUTHORIZATION_REL.as_posix(),
                R006_REVIEW_ASSIGNMENT_REL.as_posix(),
            ],
            "review_result_present_at_rejection": False,
            "independent_review_present_at_rejection": False,
            "observation_basis": "PRIMARY_REVIEWER_OBSERVED_TEST_OUTPUT_UNSEALED",
            "observation_timestamp_available": False,
        },
        "post_rejection_documents": {
            "authority_status": "NONAUTHORITY_POST_REJECTION_DOCUMENTS",
            "review_chain_binding": review_chain,
            "primary_document_decision": "APPROVED",
            "primary_document_reviewed_at": R006_REVIEW_RESULT_AT,
            "independent_document_decision": "APPROVED",
            "independent_document_reviewed_at": R006_INDEPENDENT_REVIEW_AT,
        },
        "invalid_reuse_preflight": {
            "status": "NONAUTHORITY_INVALID_ROUND_REUSE_TIMEOUT_PRE_CAS",
            "observed_command": "--preflight",
            "timeout_wrapper_exit_code": 124,
            "child_signal_number": 2,
            "child_signal_name": "SIGINT",
            "timeout_signal": "SIGINT",
            "elapsed_seconds": "300.08",
            "cpu_percent": "99%",
            "max_rss_kb": 217_872,
            "validator_stack": list(R006_PREFLIGHT_VALIDATOR_STACK),
            "terminal_error": R006_PREFLIGHT_TERMINAL_ERROR,
            "checkpoint_write_attempted": False,
            "checkpoint_cas_attempted": False,
            "checkpoint_published": False,
            "temporary_artifact_present": False,
            "termination_timestamp_available": False,
            "post_abort_recorded_at": R006_PREFLIGHT_POST_ABORT_RECORDED_AT,
        },
        "source_checkpoint_binding": _source_checkpoint_binding(failure),
        "review_chain_binding": review_chain,
    }


def frozen_r007_review_chain(
    root: Path = ROOT,
    source: Mapping[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    """Validate the approved R007 review as immutable preflight history."""

    root = seq90._safe_root(root)
    if source is None:
        _source_raw, source = load_exact_seq98_source(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    r006_rejection = r006_rejected_review_and_invalid_preflight(root, source)
    paths = {
        "authorization": R007_AUTHORIZATION_REL,
        "assignment": R007_REVIEW_ASSIGNMENT_REL,
        "review_result": R007_REVIEW_RESULT_REL,
        "independent_review": R007_INDEPENDENT_REVIEW_REL,
    }
    documents: dict[str, dict[str, Any]] = {}
    observed: dict[str, dict[str, Any]] = {}
    for role, path in paths.items():
        raw, document = _require_private_json(root, path)
        metadata = (root / path).lstat()
        require(
            stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_uid == 1000 == os.geteuid()
            and metadata.st_nlink == 1,
            f"frozen seq99 R007 {role} physical authority differs",
        )
        binding = _binding(path, raw)
        require(
            binding == R007_FROZEN_BINDINGS[role],
            f"frozen seq99 R007 {role} bytes differ",
        )
        documents[role] = document
        observed[role] = binding

    authorization = documents["authorization"]
    assignment = documents["assignment"]
    result = documents["review_result"]
    independent = documents["independent_review"]
    require(
        authorization.get("document_id")
        == "WS-FP048-R002-SEQ99-100-AUTHORIZATION-20260827-R007"
        and authorization.get("authorization_status")
        == "AUTHORIZED_FOR_SEQ99_R010_EXECUTION_CORRECTION_R007_RECOVERY"
        and authorization.get("authorized_producer") == R007_PRODUCER
        and authorization.get("required_primary_reviewer")
        == R007_PRIMARY_REVIEWER
        and authorization.get("required_independent_reviewer")
        == R007_INDEPENDENT_REVIEWER
        and authorization.get("supersedes_round_id") == "R006"
        and authorization.get("superseded_nonauthority_artifact_binding")
        == r006_rejection["review_chain_binding"]
        and authorization.get("r006_rejected_review_and_invalid_preflight")
        == r006_rejection
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-ASSIGNMENT-20260827-R007"
        and assignment.get("round_id") == "R007"
        and assignment.get("candidate_status")
        == "FINAL_REVIEW_CANDIDATE_R007_RECOVERY"
        and assignment.get("authorization_binding") == observed["authorization"]
        and assignment.get("producer") == R007_PRODUCER
        and assignment.get("required_primary_reviewer") == R007_PRIMARY_REVIEWER
        and assignment.get("required_independent_reviewer")
        == R007_INDEPENDENT_REVIEWER
        and assignment.get("supersedes_round_id") == "R006"
        and assignment.get("superseded_nonauthority_artifact_binding")
        == r006_rejection["review_chain_binding"]
        and assignment.get("r006_rejected_review_and_invalid_preflight")
        == r006_rejection
        and _is_exact_zero_findings(assignment.get("required_findings"))
        and result.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-RESULT-20260827-R007"
        and result.get("round_id") == "R007"
        and result.get("assignment_binding") == observed["assignment"]
        and result.get("reviewer") == R007_PRIMARY_REVIEWER
        and result.get("decision") == "APPROVED"
        and _is_exact_zero_findings(result.get("findings"))
        and result.get("reviewed_at") == R007_REVIEW_RESULT_AT
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ99-100-INDEPENDENT-REVIEW-20260827-R007"
        and independent.get("round_id") == "R007"
        and independent.get("assignment_binding") == observed["assignment"]
        and independent.get("review_result_binding") == observed["review_result"]
        and independent.get("reviewer") == R007_INDEPENDENT_REVIEWER
        and independent.get("decision") == "APPROVED"
        and _is_exact_zero_findings(independent.get("findings"))
        and independent.get("reviewed_at") == R007_INDEPENDENT_REVIEW_AT,
        "frozen seq99 R007 internal chain differs",
    )
    require(
        _parse_time(
            R006_PREFLIGHT_POST_ABORT_RECORDED_AT,
            "R006 post-abort record time",
        )
        < _parse_time(R007_REVIEW_RESULT_AT, "R007 primary review time")
        < _parse_time(R007_INDEPENDENT_REVIEW_AT, "R007 independent review time"),
        "frozen seq99 R007 chronology differs",
    )
    return copy.deepcopy(observed)


def r007_projected_preflight_failure(
    root: Path,
    source: Mapping[str, Any],
) -> dict[str, Any]:
    """Bind the approved R007 review and its timed-out pre-CAS preflight."""

    root = seq90._safe_root(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    failure = r010_execution_failure_binding(root)
    review_chain = frozen_r007_review_chain(root, source)
    require(
        len(R007_PREFLIGHT_VALIDATOR_STACK) == 11
        and R007_PREFLIGHT_VALIDATOR_STACK[:2] == ("seq99.main", "seq99.prepare")
        and R007_PREFLIGHT_VALIDATOR_STACK[-1]
        == "copy.deepcopy(history[:65])"
        and "_npc_single_admin_recovery_completion_package"
        in R007_PREFLIGHT_VALIDATOR_STACK,
        "R007 projected preflight stack mapping differs",
    )
    require(
        _parse_time(R007_INDEPENDENT_REVIEW_AT, "R007 independent review time")
        < _parse_time(
            R007_PREFLIGHT_POST_ABORT_RECORDED_AT,
            "R007 post-abort record time",
        ),
        "R007 projected preflight record chronology differs",
    )
    return {
        "round_id": "R007",
        "status": "NONAUTHORITY_POST_REVIEW_P0_PREFLIGHT_TIMEOUT_PRE_CAS",
        "authority_status": (
            "NONAUTHORITY_POST_REVIEW_P0_PREFLIGHT_TIMEOUT_PRE_CAS"
        ),
        "observed_command": "--preflight",
        "timeout_wrapper_exit_code": 124,
        "child_signal_number": 2,
        "child_signal_name": "SIGINT",
        "elapsed_seconds": "180.19",
        "cpu_percent": "99%",
        "max_rss_kb": 219_864,
        "validator_stage": R007_PREFLIGHT_VALIDATOR_STACK[2],
        "validator_stack": list(R007_PREFLIGHT_VALIDATOR_STACK),
        "post_review_finding": {
            "severity": "P0",
            "class": "GOAL_GRAPH_SEQ99_REVIEW_CHAIN_REPLAY_PERFORMANCE_DEFECT",
        },
        "terminal_error": R007_PREFLIGHT_TERMINAL_ERROR,
        "checkpoint_write_attempted": False,
        "checkpoint_cas_attempted": False,
        "checkpoint_published": False,
        "temporary_artifact_present": False,
        "termination_timestamp_available": False,
        "post_abort_recorded_at": R007_PREFLIGHT_POST_ABORT_RECORDED_AT,
        "review_decision": "APPROVED",
        "review_findings": {"P0": 0, "P1": 0, "P2": 0},
        "primary_reviewed_at": R007_REVIEW_RESULT_AT,
        "independent_reviewed_at": R007_INDEPENDENT_REVIEW_AT,
        "source_checkpoint_binding": _source_checkpoint_binding(failure),
        "review_chain_binding": review_chain,
    }


def frozen_r008_review_chain(
    root: Path = ROOT,
    source: Mapping[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    """Validate the approved R008 review as immutable preflight history."""

    root = seq90._safe_root(root)
    if source is None:
        _source_raw, source = load_exact_seq98_source(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    r007_failure = r007_projected_preflight_failure(root, source)
    paths = {
        "authorization": R008_AUTHORIZATION_REL,
        "assignment": R008_REVIEW_ASSIGNMENT_REL,
        "review_result": R008_REVIEW_RESULT_REL,
        "independent_review": R008_INDEPENDENT_REVIEW_REL,
    }
    documents: dict[str, dict[str, Any]] = {}
    observed: dict[str, dict[str, Any]] = {}
    for role, path in paths.items():
        raw, document = _require_private_json(root, path)
        metadata = (root / path).lstat()
        require(
            stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_uid == 1000 == os.geteuid()
            and metadata.st_nlink == 1,
            f"frozen seq99 R008 {role} physical authority differs",
        )
        binding = _binding(path, raw)
        require(
            binding == R008_FROZEN_BINDINGS[role],
            f"frozen seq99 R008 {role} bytes differ",
        )
        documents[role] = document
        observed[role] = binding

    authorization = documents["authorization"]
    assignment = documents["assignment"]
    result = documents["review_result"]
    independent = documents["independent_review"]
    require(
        authorization.get("document_id")
        == "WS-FP048-R002-SEQ99-100-AUTHORIZATION-20260827-R008"
        and authorization.get("authorization_status")
        == "AUTHORIZED_FOR_SEQ99_R010_EXECUTION_CORRECTION_R008_RECOVERY"
        and authorization.get("authorized_producer") == R008_PRODUCER
        and authorization.get("required_primary_reviewer")
        == R008_PRIMARY_REVIEWER
        and authorization.get("required_independent_reviewer")
        == R008_INDEPENDENT_REVIEWER
        and authorization.get("supersedes_round_id") == "R007"
        and authorization.get("superseded_review_chain_binding")
        == r007_failure["review_chain_binding"]
        and authorization.get("r007_projected_preflight_failure")
        == r007_failure
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-ASSIGNMENT-20260827-R008"
        and assignment.get("round_id") == "R008"
        and assignment.get("candidate_status")
        == "FINAL_REVIEW_CANDIDATE_R008_RECOVERY"
        and assignment.get("authorization_binding") == observed["authorization"]
        and assignment.get("producer") == R008_PRODUCER
        and assignment.get("required_primary_reviewer") == R008_PRIMARY_REVIEWER
        and assignment.get("required_independent_reviewer")
        == R008_INDEPENDENT_REVIEWER
        and assignment.get("supersedes_round_id") == "R007"
        and assignment.get("superseded_review_chain_binding")
        == r007_failure["review_chain_binding"]
        and assignment.get("r007_projected_preflight_failure") == r007_failure
        and _is_exact_zero_findings(assignment.get("required_findings"))
        and result.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-RESULT-20260827-R008"
        and result.get("round_id") == "R008"
        and result.get("assignment_binding") == observed["assignment"]
        and result.get("reviewer") == R008_PRIMARY_REVIEWER
        and result.get("decision") == "APPROVED"
        and _is_exact_zero_findings(result.get("findings"))
        and result.get("reviewed_at") == R008_REVIEW_RESULT_AT
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ99-100-INDEPENDENT-REVIEW-20260827-R008"
        and independent.get("round_id") == "R008"
        and independent.get("assignment_binding") == observed["assignment"]
        and independent.get("review_result_binding") == observed["review_result"]
        and independent.get("reviewer") == R008_INDEPENDENT_REVIEWER
        and independent.get("decision") == "APPROVED"
        and _is_exact_zero_findings(independent.get("findings"))
        and independent.get("reviewed_at") == R008_INDEPENDENT_REVIEW_AT,
        "frozen seq99 R008 internal chain differs",
    )
    require(
        _parse_time(
            R007_PREFLIGHT_POST_ABORT_RECORDED_AT,
            "R007 post-abort record time",
        )
        < _parse_time(R008_REVIEW_RESULT_AT, "R008 primary review time")
        < _parse_time(R008_INDEPENDENT_REVIEW_AT, "R008 independent review time"),
        "frozen seq99 R008 chronology differs",
    )
    return copy.deepcopy(observed)


def r008_projected_preflight_failure(
    root: Path,
    source: Mapping[str, Any],
) -> dict[str, Any]:
    """Bind the approved R008 review and its failed pre-CAS preflight."""

    root = seq90._safe_root(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    failure = r010_execution_failure_binding(root)
    review_chain = frozen_r008_review_chain(root, source)
    require(
        len(R008_PREFLIGHT_TERMINAL_ERROR_PREFIX) == 3
        and R008_PREFLIGHT_TERMINAL_ERROR_PREFIX
        == (
            "FP048 R002 reviewed noncredit successor authority differs",
            "NPC/FP022 successor authority differs",
            "FP048 R002 reviewed successor composition differs",
        ),
        "R008 projected preflight terminal-error prefix differs",
    )
    return {
        "round_id": "R008",
        "status": "NONAUTHORITY_POST_REVIEW_PREFLIGHT_FAILED_PRE_CAS",
        "authority_status": "NONAUTHORITY_POST_REVIEW_PREFLIGHT_FAILED_PRE_CAS",
        "observed_command": "--preflight",
        "preflight_exit_code": 1,
        "elapsed_seconds": "133.08",
        "cpu_percent": "100%",
        "max_rss_kb": 224_436,
        "ordered_terminal_error_prefix": list(
            R008_PREFLIGHT_TERMINAL_ERROR_PREFIX
        ),
        "validator_stack_available": False,
        "full_terminal_output_available": False,
        "termination_timestamp_available": False,
        "checkpoint_write_attempted": False,
        "checkpoint_cas_attempted": False,
        "checkpoint_published": False,
        "observation_basis": "EXECUTOR_OBSERVED_TERMINAL_OUTPUT_UNSEALED",
        "review_decision": "APPROVED",
        "review_findings": {"P0": 0, "P1": 0, "P2": 0},
        "primary_reviewed_at": R008_REVIEW_RESULT_AT,
        "independent_reviewed_at": R008_INDEPENDENT_REVIEW_AT,
        "source_checkpoint_binding": _source_checkpoint_binding(failure),
        "review_chain_binding": review_chain,
    }


def frozen_r009_review_chain(
    root: Path = ROOT,
    source: Mapping[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    """Validate the approved R009 review as immutable preflight history."""

    root = seq90._safe_root(root)
    if source is None:
        _source_raw, source = load_exact_seq98_source(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    r008_failure = r008_projected_preflight_failure(root, source)
    paths = {
        "authorization": R009_AUTHORIZATION_REL,
        "assignment": R009_REVIEW_ASSIGNMENT_REL,
        "review_result": R009_REVIEW_RESULT_REL,
        "independent_review": R009_INDEPENDENT_REVIEW_REL,
    }
    documents: dict[str, dict[str, Any]] = {}
    observed: dict[str, dict[str, Any]] = {}
    for role, path in paths.items():
        raw, document = _require_private_json(root, path)
        metadata = (root / path).lstat()
        require(
            stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_uid == 1000 == os.geteuid()
            and metadata.st_nlink == 1,
            f"frozen seq99 R009 {role} physical authority differs",
        )
        binding = _binding(path, raw)
        require(
            binding == R009_FROZEN_BINDINGS[role],
            f"frozen seq99 R009 {role} bytes differ",
        )
        documents[role] = document
        observed[role] = binding

    authorization = documents["authorization"]
    assignment = documents["assignment"]
    result = documents["review_result"]
    independent = documents["independent_review"]
    require(
        authorization.get("document_id")
        == "WS-FP048-R002-SEQ99-100-AUTHORIZATION-20260827-R009"
        and authorization.get("authorization_status")
        == "AUTHORIZED_FOR_SEQ99_R010_EXECUTION_CORRECTION_R009_RECOVERY"
        and authorization.get("authorized_producer") == R009_PRODUCER
        and authorization.get("required_primary_reviewer")
        == R009_PRIMARY_REVIEWER
        and authorization.get("required_independent_reviewer")
        == R009_INDEPENDENT_REVIEWER
        and authorization.get("supersedes_round_id") == "R008"
        and authorization.get("superseded_review_chain_binding")
        == r008_failure["review_chain_binding"]
        and authorization.get("r008_projected_preflight_failure")
        == r008_failure
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-ASSIGNMENT-20260827-R009"
        and assignment.get("round_id") == "R009"
        and assignment.get("candidate_status")
        == "FINAL_REVIEW_CANDIDATE_R009_RECOVERY"
        and assignment.get("authorization_binding") == observed["authorization"]
        and assignment.get("producer") == R009_PRODUCER
        and assignment.get("required_primary_reviewer") == R009_PRIMARY_REVIEWER
        and assignment.get("required_independent_reviewer")
        == R009_INDEPENDENT_REVIEWER
        and assignment.get("supersedes_round_id") == "R008"
        and assignment.get("superseded_review_chain_binding")
        == r008_failure["review_chain_binding"]
        and assignment.get("r008_projected_preflight_failure") == r008_failure
        and _is_exact_zero_findings(assignment.get("required_findings"))
        and result.get("document_id")
        == "WS-FP048-R002-SEQ99-100-REVIEW-RESULT-20260827-R009"
        and result.get("round_id") == "R009"
        and result.get("assignment_binding") == observed["assignment"]
        and result.get("reviewer") == R009_PRIMARY_REVIEWER
        and result.get("decision") == "APPROVED"
        and _is_exact_zero_findings(result.get("findings"))
        and result.get("reviewed_at") == R009_REVIEW_RESULT_AT
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ99-100-INDEPENDENT-REVIEW-20260827-R009"
        and independent.get("round_id") == "R009"
        and independent.get("assignment_binding") == observed["assignment"]
        and independent.get("review_result_binding") == observed["review_result"]
        and independent.get("reviewer") == R009_INDEPENDENT_REVIEWER
        and independent.get("decision") == "APPROVED"
        and _is_exact_zero_findings(independent.get("findings"))
        and independent.get("reviewed_at") == R009_INDEPENDENT_REVIEW_AT,
        "frozen seq99 R009 internal chain differs",
    )
    require(
        _parse_time(R008_INDEPENDENT_REVIEW_AT, "R008 independent review time")
        < _parse_time(R009_REVIEW_RESULT_AT, "R009 primary review time")
        < _parse_time(R009_INDEPENDENT_REVIEW_AT, "R009 independent review time"),
        "frozen seq99 R009 chronology differs",
    )
    return copy.deepcopy(observed)


def r009_post_review_write_failure(
    root: Path,
    source: Mapping[str, Any],
) -> dict[str, Any]:
    """Bind R009's passing preflight and failed write as nonauthority."""

    root = seq90._safe_root(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    failure = r010_execution_failure_binding(root)
    source_binding = _source_checkpoint_binding(failure)
    review_chain = frozen_r009_review_chain(root, source)
    require(
        _parse_time(R009_INDEPENDENT_REVIEW_AT, "R009 independent review time")
        < _parse_time(
            R009_PROJECTED_EVENT_OCCURRED_AT,
            "R009 projected event occurred_at",
        )
        and seq90.SHA256_RE.fullmatch(R009_PROJECTED_EVENT_SHA256) is not None
        and R009_WRITE_TERMINAL_ERROR == "seq99 managed snapshot differs",
        "R009 post-review execution observation differs",
    )
    return {
        "round_id": "R009",
        "status": "NONAUTHORITY_POST_REVIEW_WRITE_FAILED_NO_PUBLISH_SOURCE_RETAINED",
        "authority_status": (
            "NONAUTHORITY_POST_REVIEW_WRITE_FAILED_NO_PUBLISH_SOURCE_RETAINED"
        ),
        "preflight_observation": {
            "observed_command": "--preflight",
            "occurred_at": R009_PROJECTED_EVENT_OCCURRED_AT,
            "exit_code": 0,
            "elapsed_seconds": "195.88",
            "elapsed_wall_clock": "3:15.88",
            "cpu_percent": "100%",
            "max_rss_kb": 229_988,
            "event_sha256": R009_PROJECTED_EVENT_SHA256,
            "checkpoint_published": False,
        },
        "write_observation": {
            "direct_observation": {
                "observed_command": "--write",
                "occurred_at": R009_PROJECTED_EVENT_OCCURRED_AT,
                "exit_code": 1,
                "elapsed_seconds": "232.36",
                "elapsed_wall_clock": "3:52.36",
                "cpu_percent": "100%",
                "max_rss_kb": 244_252,
                "terminal_error": R009_WRITE_TERMINAL_ERROR,
                "checkpoint_write_attempted": True,
                "checkpoint_published": False,
                "terminal_checkpoint_published": False,
                "final_checkpoint_binding": copy.deepcopy(source_binding),
                "recovery_temp_present": False,
                "process_present": False,
                "termination_timestamp_available": False,
                "validator_stack_available": False,
                "full_terminal_output_available": False,
                "terminal_output_provenance": (
                    "EXECUTOR_OBSERVED_TERMINAL_OUTPUT_UNSEALED"
                ),
            },
            "control_flow_inference": {
                "classification": (
                    "LIKELY_POST_EXCHANGE_CAS_GUARD_FAILED_ROLLED_BACK"
                ),
                "basis": (
                    "CONSISTENT_WITH_PROJECTED_GUARD_AFTER_PREVIOUS_SAME_"
                    "OCCURRED_AT_PREFLIGHT_PASS_AND_FINAL_EXACT_SOURCE_NO_TEMP"
                ),
                "non_exclusive": True,
                "checkpoint_cas_attempted_inferred": True,
                "exchange_attempted_inferred": True,
                "transient_projected_checkpoint_inferred": True,
                "post_exchange_validation_failed_inferred": True,
                "rollback_attempted_inferred": True,
                "rollback_completed_inferred": True,
            },
        },
        "review_decision": "APPROVED",
        "review_findings": {"P0": 0, "P1": 0, "P2": 0},
        "primary_reviewed_at": R009_REVIEW_RESULT_AT,
        "independent_reviewed_at": R009_INDEPENDENT_REVIEW_AT,
        "source_checkpoint_binding": source_binding,
        "review_chain_binding": review_chain,
    }


def r010_contract_binding(root: Path = ROOT) -> dict[str, Any]:
    """Validate the exact physical R010 contract superseded by R011."""

    root = seq90._safe_root(root)
    read = seq90._stable_read(root, R010_CONTRACT_REL)
    contract = seq90.strict_json(read.raw, R010_CONTRACT_REL.as_posix())
    observed = {
        "schema_version": contract.get("schema_version"),
        "document_id": contract.get("document_id"),
        "path": R010_CONTRACT_REL.as_posix(),
        "file_sha256": sha256_bytes(read.raw),
        "contract_id": contract.get("contract_id"),
        "contract_version": contract.get("contract_version"),
        "canonical_contract_sha256": _canonical_contract_sha256(contract),
    }
    require(
        _binding(R010_CONTRACT_REL, read.raw) == R010_CONTRACT_FILE_BINDING
        and stat.S_IMODE(read.identity.mode) == R010_CONTRACT_MODE
        and observed == R010_CONTRACT_BINDING,
        "R010 failed contract differs",
    )
    return observed


def r010_execution_failure_binding(root: Path = ROOT) -> dict[str, Any]:
    """Return the exact consumed R010 -005 failure as zero-credit nonauthority."""

    root = seq90._safe_root(root)
    contract = r010_contract_binding(root)
    gate = root / R010_GATE_REL
    info = gate.lstat()
    require(
        stat.S_ISDIR(info.st_mode)
        and not stat.S_ISLNK(info.st_mode)
        and stat.S_IMODE(info.st_mode) == 0o700
        and info.st_uid == 1000 == os.geteuid()
        and info.st_nlink == 2,
        "R010 failed namespace authority differs",
    )
    require(
        not os.path.lexists(root / R010_RECEIPT_REL)
        and not os.path.lexists(root / R010_RECEIPT_STAGE_REL),
        "R010 failed namespace unexpectedly acquired receipt authority",
    )
    expected_names = {Path(row["path"]).name for row in R010_LOG_BINDINGS}
    require(
        {entry.name for entry in gate.iterdir()} == expected_names,
        "R010 failed namespace inventory differs",
    )
    observed_logs: list[dict[str, Any]] = []
    contents: dict[str, bytes] = {}
    for expected in R010_LOG_BINDINGS:
        path = Path(expected["path"])
        read = seq90._stable_read(root, path)
        metadata = (root / path).lstat()
        row = {
            "check_id": expected["check_id"],
            **_binding(path, read.raw),
            "mode": f"{stat.S_IMODE(metadata.st_mode):04o}",
            "exit_code": 0,
        }
        require(
            row == expected
            and stat.S_ISREG(metadata.st_mode)
            and metadata.st_uid == 1000 == os.geteuid()
            and metadata.st_nlink == 1,
            f"R010 failed log differs: {path}",
        )
        observed_logs.append(row)
        contents[expected["check_id"]] = read.raw
    require(
        contents["CONTINUATION"] == b"WalkSafe v2.4 continuation check: PASS\n"
        and b"Goal graph check: PASS" in contents["GOAL_GRAPH"]
        and contents["TEST_LAYER_REGISTRY_VALIDATE"]
        == b"TEST_LAYER_REGISTRY_VALIDATE: PASS\n"
        and b"59 passed in 105.77s" in contents["ROOT_FP048_R002_CONTROL_REGRESSION"],
        "R010 completed-check summaries differ",
    )
    repository_state = seq90.strict_json(
        contents["REPOSITORY_STATE"], R010_LOG_BINDINGS[-1]["path"]
    )
    controlled = repository_state.get("checkpoint_controlled_working_snapshot")
    require(
        repository_state.get("gate_event_id") == R010_FAILED_EVENT_ID
        and repository_state.get("evidence_type") == "GATE_REPOSITORY_STATE"
        and isinstance(controlled, dict)
        and controlled.get("path_set_sha256")
        == "e14749a68e4ad7d7efdf6ec801a070984b69bd709c25d5ca067e88f7adc7b5c2"
        and controlled.get("content_set_sha256")
        == "f9d55dd798a5e6ae753a7824dbb9692c4e1d818c4bf5e07750d76eb95496553b",
        "R010 repository-state evidence differs",
    )
    runner = _observed_binding(root, R010_RUNNER_REL)
    require(runner == R010_RUNNER_BINDING, "R010 failed runner binding differs")
    return {
        "event_id": R010_FAILED_EVENT_ID,
        "contract_id": R010_CONTRACT_BINDING["contract_id"],
        "contract_version": R010_CONTRACT_BINDING["contract_version"],
        "directory": R010_GATE_REL.as_posix(),
        "receipt_path": R010_RECEIPT_REL.as_posix(),
        "receipt_stage_path": R010_RECEIPT_STAGE_REL.as_posix(),
        "status": "FAILED_AFTER_ALL_CHECKS_BEFORE_RECEIPT",
        "authority_status": "NONAUTHORITY",
        "event_identity_status": "CONSUMED_FAILED_NO_RECEIPT",
        "namespace_present": True,
        "receipt_present": False,
        "receipt_stage_present": False,
        "all_checks_exit_code": 0,
        "failure_phase": "POST_CHECK_CONTEXT_REVALIDATION",
        "runner_exit_code": 2,
        "error_type": "GateError",
        "error": "R010 start namespace unexpectedly exists",
        "reason_code": CORRECTION_REASON_CODE,
        "replacement_event_id": STARTED_EVENT_ID,
        "directory_authority": {
            "mode": "0700",
            "owner_uid": 1000,
            "nlink": 2,
        },
        "contract_binding": contract,
        "runner_binding": runner,
        "logs": observed_logs,
    }


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
        "r010_execution_failure": copy.deepcopy(
            dict(failure) if failure is not None else r010_execution_failure_binding()
        ),
    }


def _require_exact_seq98_pins(
    raw: bytes,
    checkpoint: Mapping[str, Any],
) -> None:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256
        and raw == checkpoint_json_bytes(checkpoint)
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
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values()
        and history[-1].get("source_ready_event_binding", {}).get("event_id")
        == READY_EVENT_ID
        and tuple(history[-1].get("runtime_after", {}).get("ready_frontier_goal_ids", ()))
        == READY_FRONTIER,
        "exact published seq98 source differs",
    )


def require_exact_seq98_source(
    raw: bytes,
    checkpoint: Mapping[str, Any],
    root: Path = ROOT,
) -> None:
    root = seq90._safe_root(root)
    _require_exact_seq98_pins(raw, checkpoint)
    canonical_raw = seq98.canonical_seq98_checkpoint_bytes(root, checkpoint)
    restored_seq97_raw = checkpoint_json_bytes(
        seq98._restored_seq97_checkpoint(checkpoint)
    )
    require(
        canonical_raw == raw
        and len(restored_seq97_raw) == seq98.SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(restored_seq97_raw) == seq98.SOURCE_CHECKPOINT_SHA256,
        "seq98 historical authority differs",
    )


def load_exact_seq98_source(root: Path = ROOT) -> tuple[bytes, dict[str, Any]]:
    root = seq90._safe_root(root)
    read = seq90._stable_read(root, CHECKPOINT_REL)
    checkpoint = seq90.strict_json(read.raw, CHECKPOINT_REL.as_posix())
    require_exact_seq98_source(read.raw, checkpoint, root)
    require(
        stat.S_IMODE(read.identity.mode) == SOURCE_CHECKPOINT_MODE,
        "seq98 checkpoint mode differs",
    )
    return read.raw, checkpoint


def _canonical_contract_sha256(value: Mapping[str, Any]) -> str:
    raw = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return sha256_bytes(raw)


def r011_contract_binding(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    read = seq90._stable_read(root, R011_CONTRACT_REL)
    contract = seq90.strict_json(read.raw, R011_CONTRACT_REL.as_posix())
    checks = contract.get("ordered_checks")
    supersedes = contract.get("supersedes")
    claim = contract.get("claim_boundary")
    require(
        contract.get("schema_version") == "1.2"
        and contract.get("document_id") == R011_DOCUMENT_ID
        and contract.get("contract_id") == R011_CONTRACT_ID
        and contract.get("contract_version") == R011_CONTRACT_VERSION
        and contract.get("gate_purpose") == "INITIAL_START"
        and contract.get("target_goal_id") == GOAL_ID
        and contract.get("target_goal_content_sha256") == GOAL_SHA256
        and contract.get("successor_reason_code") == CORRECTION_REASON_CODE
        and isinstance(checks, list)
        and [row.get("check_id") for row in checks if isinstance(row, dict)]
        == [
            "CONTINUATION",
            "GOAL_GRAPH",
            "TEST_LAYER_REGISTRY_VALIDATE",
            "ROOT_FP048_R002_CONTROL_REGRESSION",
            "REPOSITORY_STATE",
        ]
        and isinstance(supersedes, dict)
        and supersedes.get("contract_id") == R010_CONTRACT_BINDING["contract_id"]
        and supersedes.get("contract_version")
        == R010_CONTRACT_BINDING["contract_version"]
        and supersedes.get("file_sha256") == R010_CONTRACT_BINDING["file_sha256"]
        and supersedes.get("canonical_sha256")
        == R010_CONTRACT_BINDING["canonical_contract_sha256"]
        and supersedes.get("source_correction_event_id") == CORRECTION_EVENT_ID
        and type(supersedes.get("source_correction_event_sequence")) is int
        and supersedes.get("source_correction_event_sequence")
        == CORRECTION_SEQUENCE
        and isinstance(claim, dict)
        and claim.get("goal_started") is False
        and claim.get("start_gate_status") == "NOT_RUN"
        and claim.get("release_status") == "NOT_ELIGIBLE"
        and all(
            type(value) is int and value == 0
            for key, value in claim.items()
            if key.endswith("_credit_delta")
        ),
        "R011 contract authority differs",
    )
    return {
        "schema_version": "1.2",
        "document_id": R011_DOCUMENT_ID,
        "path": R011_CONTRACT_REL.as_posix(),
        "file_sha256": sha256_bytes(read.raw),
        "contract_id": R011_CONTRACT_ID,
        "contract_version": R011_CONTRACT_VERSION,
        "canonical_contract_sha256": _canonical_contract_sha256(contract),
    }


def r011_runner_binding(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    return _observed_binding(root, R011_RUNNER_REL)


def _reviewed_control_bindings(root: Path) -> list[dict[str, Any]]:
    root = seq90._safe_root(root)
    return [_observed_binding(root, path) for path in REVIEWED_CONTROL_PATHS]


def _validate_reviewed_control_rows(value: Any) -> list[dict[str, Any]]:
    require(
        isinstance(value, list)
        and len(value) == len(REVIEWED_CONTROL_PATHS)
        and all(isinstance(row, Mapping) for row in value),
        "seq99 reviewed control cohort differs",
    )
    rows = [dict(row) for row in value]
    require(
        [row.get("path") for row in rows]
        == [path.as_posix() for path in REVIEWED_CONTROL_PATHS],
        "seq99 reviewed control inventory differs",
    )
    for row, path in zip(rows, REVIEWED_CONTROL_PATHS, strict=True):
        _require_file_binding(row, path, f"reviewed control input {path}")
    return rows


def _projected_transition() -> dict[str, Any]:
    return {
        "execution_correction_sequence": CORRECTION_SEQUENCE,
        "execution_correction_transition": "READY_TO_READY",
        "started_event_id": STARTED_EVENT_ID,
        "started_sequence": STARTED_SEQUENCE,
        "started_transition": "READY_TO_IN_PROGRESS_AFTER_FRESH_R011_PASS",
    }


def build_authorization(root: Path, source: Mapping[str, Any]) -> bytes:
    root = seq90._safe_root(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    failure = r010_execution_failure_binding(root)
    r001_failed_preflight = r001_projected_preflight_failure(root, source)
    r002_failed_preflight = r002_projected_preflight_failure(root, source)
    r003_rejection = r003_primary_review_rejection(root, source)
    r004_failed_preflight = r004_projected_preflight_failure(root, source)
    r005_failed_preflight = r005_projected_preflight_failure(root, source)
    r006_rejection = r006_rejected_review_and_invalid_preflight(root, source)
    r007_failed_preflight = r007_projected_preflight_failure(root, source)
    r008_failed_preflight = r008_projected_preflight_failure(root, source)
    r009_execution_failure = r009_post_review_write_failure(root, source)
    superseded_review = r009_execution_failure["review_chain_binding"]
    value = {
        "schema_version": "1.0",
        "document_id": AUTHORIZATION_DOCUMENT_ID,
        "evidence_type": "USER_CONTINUATION_AUTHORIZATION",
        "authorization_status": (
            "AUTHORIZED_FOR_SEQ99_R010_EXECUTION_CORRECTION_R010_RECOVERY"
        ),
        "goal_id": GOAL_ID,
        "source_checkpoint_binding": _source_checkpoint_binding(failure),
        "r010_execution_failure": failure,
        "supersedes_round_id": "R009",
        "superseded_review_chain_binding": superseded_review,
        "r001_projected_preflight_failure": r001_failed_preflight,
        "r002_projected_preflight_failure": r002_failed_preflight,
        "r003_primary_review_rejection": r003_rejection,
        "r004_projected_preflight_failure": r004_failed_preflight,
        "r005_projected_preflight_failure": r005_failed_preflight,
        "r006_rejected_review_and_invalid_preflight": r006_rejection,
        "r007_projected_preflight_failure": r007_failed_preflight,
        "r008_projected_preflight_failure": r008_failed_preflight,
        "r009_post_review_write_failure": r009_execution_failure,
        "previous_contract_binding": copy.deepcopy(R010_CONTRACT_BINDING),
        "replacement_contract_binding": r011_contract_binding(root),
        "contract_correction_event_id": CORRECTION_EVENT_ID,
        "projected_transition": _projected_transition(),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "authorized_producer": copy.deepcopy(PRODUCER),
        "required_primary_reviewer": copy.deepcopy(PRIMARY_REVIEWER),
        "required_independent_reviewer": copy.deepcopy(INDEPENDENT_REVIEWER),
    }
    return seq90.canonical_json_bytes(value)


def build_review_assignment(root: Path, source: Mapping[str, Any]) -> bytes:
    root = seq90._safe_root(root)
    authorization = build_authorization(root, source)
    failure = r010_execution_failure_binding(root)
    r001_failed_preflight = r001_projected_preflight_failure(root, source)
    r002_failed_preflight = r002_projected_preflight_failure(root, source)
    r003_rejection = r003_primary_review_rejection(root, source)
    r004_failed_preflight = r004_projected_preflight_failure(root, source)
    r005_failed_preflight = r005_projected_preflight_failure(root, source)
    r006_rejection = r006_rejected_review_and_invalid_preflight(root, source)
    r007_failed_preflight = r007_projected_preflight_failure(root, source)
    r008_failed_preflight = r008_projected_preflight_failure(root, source)
    r009_execution_failure = r009_post_review_write_failure(root, source)
    superseded_review = r009_execution_failure["review_chain_binding"]
    value = {
        "schema_version": "1.0",
        "document_id": REVIEW_ASSIGNMENT_DOCUMENT_ID,
        "evidence_type": "TRANSITION_CONTROL_REVIEW_ASSIGNMENT",
        "round_id": REVIEW_ROUND_ID,
        "candidate_status": "FINAL_REVIEW_CANDIDATE_R010_RECOVERY",
        "goal_id": GOAL_ID,
        "authorization_binding": _binding(AUTHORIZATION_REL, authorization),
        "source_checkpoint_binding": _source_checkpoint_binding(failure),
        "r010_execution_failure": failure,
        "supersedes_round_id": "R009",
        "superseded_review_chain_binding": superseded_review,
        "r001_projected_preflight_failure": r001_failed_preflight,
        "r002_projected_preflight_failure": r002_failed_preflight,
        "r003_primary_review_rejection": r003_rejection,
        "r004_projected_preflight_failure": r004_failed_preflight,
        "r005_projected_preflight_failure": r005_failed_preflight,
        "r006_rejected_review_and_invalid_preflight": r006_rejection,
        "r007_projected_preflight_failure": r007_failed_preflight,
        "r008_projected_preflight_failure": r008_failed_preflight,
        "r009_post_review_write_failure": r009_execution_failure,
        "previous_contract_binding": copy.deepcopy(R010_CONTRACT_BINDING),
        "replacement_contract_binding": r011_contract_binding(root),
        "replacement_runner_binding": r011_runner_binding(root),
        "reviewed_control_inputs": _reviewed_control_bindings(root),
        "producer": copy.deepcopy(PRODUCER),
        "required_primary_reviewer": copy.deepcopy(PRIMARY_REVIEWER),
        "required_independent_reviewer": copy.deepcopy(INDEPENDENT_REVIEWER),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "required_findings": {"P0": 0, "P1": 0, "P2": 0},
    }
    return seq90.canonical_json_bytes(value)


def build_review_result(assignment_raw: bytes, *, reviewed_at: str) -> bytes:
    primary_at = _parse_time(reviewed_at, "primary review time")
    require(
        _parse_time(R009_INDEPENDENT_REVIEW_AT, "R009 independent review time")
        < primary_at,
        "R010 primary review must follow R009 independent review",
    )
    value = {
        "schema_version": "1.0",
        "document_id": REVIEW_RESULT_DOCUMENT_ID,
        "evidence_type": "TRANSITION_CONTROL_PRIMARY_REVIEW",
        "round_id": REVIEW_ROUND_ID,
        "assignment_binding": _binding(REVIEW_ASSIGNMENT_REL, assignment_raw),
        "reviewer": copy.deepcopy(PRIMARY_REVIEWER),
        "decision": "APPROVED",
        "findings": {"P0": 0, "P1": 0, "P2": 0},
        "external_independence_claimed": False,
        "reviewed_at": reviewed_at,
    }
    return seq90.canonical_json_bytes(value)


def build_independent_review(
    assignment_raw: bytes,
    review_result_raw: bytes,
    *,
    reviewed_at: str,
) -> bytes:
    independent_at = _parse_time(reviewed_at, "independent review time")
    try:
        result = seq90.strict_json(review_result_raw, "R010 primary review")
    except Exception as exc:
        raise StartGateExecutionCorrectionError(
            "R010 primary review bytes differ"
        ) from exc
    require(
        review_result_raw == seq90.canonical_json_bytes(result)
        and type(result) is dict
        and set(result)
        == {
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
        and result.get("schema_version") == "1.0"
        and result.get("document_id") == REVIEW_RESULT_DOCUMENT_ID
        and result.get("evidence_type") == "TRANSITION_CONTROL_PRIMARY_REVIEW"
        and result.get("round_id") == REVIEW_ROUND_ID
        and result.get("assignment_binding")
        == _binding(REVIEW_ASSIGNMENT_REL, assignment_raw)
        and result.get("reviewer") == PRIMARY_REVIEWER
        and result.get("decision") == "APPROVED"
        and _is_exact_zero_findings(result.get("findings"))
        and result.get("external_independence_claimed") is False
        and _parse_time(
            R009_INDEPENDENT_REVIEW_AT,
            "R009 independent review time",
        )
        < _parse_time(result.get("reviewed_at"), "primary review time")
        < independent_at,
        "R010 independent review must follow exact primary review",
    )
    value = {
        "schema_version": "1.0",
        "document_id": INDEPENDENT_REVIEW_DOCUMENT_ID,
        "evidence_type": "TRANSITION_CONTROL_INDEPENDENT_REVIEW",
        "round_id": REVIEW_ROUND_ID,
        "assignment_binding": _binding(REVIEW_ASSIGNMENT_REL, assignment_raw),
        "review_result_binding": _binding(REVIEW_RESULT_REL, review_result_raw),
        "reviewer": copy.deepcopy(INDEPENDENT_REVIEWER),
        "decision": "APPROVED",
        "findings": {"P0": 0, "P1": 0, "P2": 0},
        "external_independence_claimed": False,
        "reviewed_at": reviewed_at,
    }
    return seq90.canonical_json_bytes(value)


def prepare_review_inputs(root: Path = ROOT) -> dict[Path, bytes]:
    root = seq90._safe_root(root)
    _raw, source = load_exact_seq98_source(root)
    require(
        not os.path.lexists(root / REVIEW_RESULT_REL)
        and not os.path.lexists(root / INDEPENDENT_REVIEW_REL),
        "seq99 R010 review decision paths are already consumed",
    )
    authorization = build_authorization(root, source)
    assignment = build_review_assignment(root, source)
    return {
        AUTHORIZATION_REL: authorization,
        REVIEW_ASSIGNMENT_REL: assignment,
    }


def _require_public_parent(root: Path, relative: Path) -> None:
    cursor = root
    for part in relative.parts:
        cursor /= part
        info = cursor.lstat()
        require(
            stat.S_ISDIR(info.st_mode)
            and not stat.S_ISLNK(info.st_mode)
            and info.st_uid == os.geteuid()
            and stat.S_IMODE(info.st_mode) in {0o755, 0o775},
            f"unsafe add-only parent: {relative}",
        )


def _ensure_review_directories(root: Path) -> None:
    for relative in (TRANSITION_ROOT, TRANSITION_ROOT / "review-rounds", REVIEW_ROOT):
        target = root / relative
        if not os.path.lexists(target):
            target.mkdir(mode=0o755)
            _fsync_directory(target.parent)
        _require_public_parent(root, relative)


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(
        path,
        os.O_RDONLY
        | os.O_DIRECTORY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _existing_add_only_state(root: Path, path: Path, raw: bytes) -> bool:
    target = root / path
    if not os.path.lexists(target):
        return False
    observed = seq90._stable_read(root, path)
    info = target.lstat()
    require(
        observed.raw == raw
        and stat.S_IMODE(info.st_mode) == 0o600
        and info.st_uid == os.geteuid()
        and info.st_nlink == 1,
        f"add-only collision differs: {path}",
    )
    return True


def _write_add_only(root: Path, path: Path, raw: bytes) -> None:
    if _existing_add_only_state(root, path, raw):
        return
    flags = (
        os.O_WRONLY
        | os.O_CREAT
        | os.O_EXCL
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    descriptor = os.open(root / path, flags, 0o600)
    try:
        offset = 0
        while offset < len(raw):
            written = os.write(descriptor, raw[offset:])
            require(written > 0, f"add-only short write: {path}")
            offset += written
        os.fsync(descriptor)
        info = os.fstat(descriptor)
        require(
            stat.S_ISREG(info.st_mode)
            and stat.S_IMODE(info.st_mode) == 0o600
            and info.st_uid == os.geteuid()
            and info.st_nlink == 1,
            f"add-only file authority differs: {path}",
        )
    finally:
        os.close(descriptor)
    _fsync_directory((root / path).parent)


def write_review_inputs(root: Path, outputs: Mapping[Path, bytes]) -> None:
    root = seq90._safe_root(root)
    require(
        set(outputs) == {AUTHORIZATION_REL, REVIEW_ASSIGNMENT_REL},
        "prepare-review may write only authorization and assignment",
    )
    _ensure_review_directories(root)
    states = {
        path: _existing_add_only_state(root, path, raw)
        for path, raw in outputs.items()
    }
    for path, raw in outputs.items():
        if not states[path]:
            _write_add_only(root, path, raw)


def _compute_load_review_chain(
    root: Path,
    source: Mapping[str, Any],
) -> tuple[dict[str, Any], datetime, datetime]:
    authorization_raw, authorization = _require_private_json(root, AUTHORIZATION_REL)
    assignment_raw, assignment = _require_private_json(root, REVIEW_ASSIGNMENT_REL)
    result_raw, result = _require_private_json(root, REVIEW_RESULT_REL)
    independent_raw, independent = _require_private_json(root, INDEPENDENT_REVIEW_REL)
    r001_failed_preflight = r001_projected_preflight_failure(root, source)
    r002_failed_preflight = r002_projected_preflight_failure(root, source)
    r003_rejection = r003_primary_review_rejection(root, source)
    r004_failed_preflight = r004_projected_preflight_failure(root, source)
    r005_failed_preflight = r005_projected_preflight_failure(root, source)
    r006_rejection = r006_rejected_review_and_invalid_preflight(root, source)
    r007_failed_preflight = r007_projected_preflight_failure(root, source)
    r008_failed_preflight = r008_projected_preflight_failure(root, source)
    r009_execution_failure = r009_post_review_write_failure(root, source)
    superseded_review = r009_execution_failure["review_chain_binding"]
    require(
        authorization_raw == build_authorization(root, source)
        and assignment_raw == build_review_assignment(root, source),
        "seq99 R010 authorization or assignment live binding differs",
    )
    require(
        authorization.get("supersedes_round_id") == "R009"
        and authorization.get("superseded_review_chain_binding")
        == superseded_review
        and authorization.get("r001_projected_preflight_failure")
        == r001_failed_preflight
        and authorization.get("r002_projected_preflight_failure")
        == r002_failed_preflight
        and authorization.get("r003_primary_review_rejection") == r003_rejection
        and authorization.get("r004_projected_preflight_failure")
        == r004_failed_preflight
        and authorization.get("r005_projected_preflight_failure")
        == r005_failed_preflight
        and authorization.get("r006_rejected_review_and_invalid_preflight")
        == r006_rejection
        and authorization.get("r007_projected_preflight_failure")
        == r007_failed_preflight
        and authorization.get("r008_projected_preflight_failure")
        == r008_failed_preflight
        and authorization.get("r009_post_review_write_failure")
        == r009_execution_failure
        and assignment.get("authorization_binding")
        == _binding(AUTHORIZATION_REL, authorization_raw)
        and assignment.get("supersedes_round_id") == "R009"
        and assignment.get("superseded_review_chain_binding")
        == superseded_review
        and assignment.get("r001_projected_preflight_failure")
        == r001_failed_preflight
        and assignment.get("r002_projected_preflight_failure")
        == r002_failed_preflight
        and assignment.get("r003_primary_review_rejection") == r003_rejection
        and assignment.get("r004_projected_preflight_failure")
        == r004_failed_preflight
        and assignment.get("r005_projected_preflight_failure")
        == r005_failed_preflight
        and assignment.get("r006_rejected_review_and_invalid_preflight")
        == r006_rejection
        and assignment.get("r007_projected_preflight_failure")
        == r007_failed_preflight
        and assignment.get("r008_projected_preflight_failure")
        == r008_failed_preflight
        and assignment.get("r009_post_review_write_failure")
        == r009_execution_failure
        and assignment.get("producer") == PRODUCER
        and assignment.get("required_primary_reviewer") == PRIMARY_REVIEWER
        and assignment.get("required_independent_reviewer") == INDEPENDENT_REVIEWER
        and assignment.get("claim_boundary") == CLAIM_BOUNDARY,
        "seq99 assignment authority differs",
    )
    rows = _validate_reviewed_control_rows(assignment.get("reviewed_control_inputs"))
    for path, row in zip(REVIEWED_CONTROL_PATHS, rows, strict=True):
        require(
            _observed_binding(root, path) == row,
            f"seq99 reviewed control changed: {path}",
        )
    require(
        set(result)
        == {
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
        and result.get("schema_version") == "1.0"
        and result.get("document_id") == REVIEW_RESULT_DOCUMENT_ID
        and result.get("evidence_type") == "TRANSITION_CONTROL_PRIMARY_REVIEW"
        and result.get("round_id") == REVIEW_ROUND_ID
        and result.get("assignment_binding")
        == _binding(REVIEW_ASSIGNMENT_REL, assignment_raw)
        and result.get("reviewer") == PRIMARY_REVIEWER
        and result.get("decision") == "APPROVED"
        and _is_exact_zero_findings(result.get("findings"))
        and result.get("external_independence_claimed") is False,
        "seq99 primary review differs",
    )
    require(
        set(independent)
        == {
            "schema_version",
            "document_id",
            "evidence_type",
            "round_id",
            "assignment_binding",
            "review_result_binding",
            "reviewer",
            "decision",
            "findings",
            "external_independence_claimed",
            "reviewed_at",
        }
        and independent.get("schema_version") == "1.0"
        and independent.get("document_id") == INDEPENDENT_REVIEW_DOCUMENT_ID
        and independent.get("evidence_type")
        == "TRANSITION_CONTROL_INDEPENDENT_REVIEW"
        and independent.get("round_id") == REVIEW_ROUND_ID
        and independent.get("assignment_binding")
        == _binding(REVIEW_ASSIGNMENT_REL, assignment_raw)
        and independent.get("review_result_binding")
        == _binding(REVIEW_RESULT_REL, result_raw)
        and independent.get("reviewer") == INDEPENDENT_REVIEWER
        and independent.get("decision") == "APPROVED"
        and _is_exact_zero_findings(independent.get("findings"))
        and independent.get("external_independence_claimed") is False,
        "seq99 independent review differs",
    )
    identities = {
        (PRODUCER["id"], PRODUCER["task_id"]),
        (PRIMARY_REVIEWER["id"], PRIMARY_REVIEWER["task_id"]),
        (INDEPENDENT_REVIEWER["id"], INDEPENDENT_REVIEWER["task_id"]),
        (R009_PRODUCER["id"], R009_PRODUCER["task_id"]),
        (R009_PRIMARY_REVIEWER["id"], R009_PRIMARY_REVIEWER["task_id"]),
        (
            R009_INDEPENDENT_REVIEWER["id"],
            R009_INDEPENDENT_REVIEWER["task_id"],
        ),
        (R008_PRODUCER["id"], R008_PRODUCER["task_id"]),
        (R008_PRIMARY_REVIEWER["id"], R008_PRIMARY_REVIEWER["task_id"]),
        (
            R008_INDEPENDENT_REVIEWER["id"],
            R008_INDEPENDENT_REVIEWER["task_id"],
        ),
        (R007_PRODUCER["id"], R007_PRODUCER["task_id"]),
        (R007_PRIMARY_REVIEWER["id"], R007_PRIMARY_REVIEWER["task_id"]),
        (
            R007_INDEPENDENT_REVIEWER["id"],
            R007_INDEPENDENT_REVIEWER["task_id"],
        ),
        (R006_PRODUCER["id"], R006_PRODUCER["task_id"]),
        (R006_PRIMARY_REVIEWER["id"], R006_PRIMARY_REVIEWER["task_id"]),
        (
            R006_INDEPENDENT_REVIEWER["id"],
            R006_INDEPENDENT_REVIEWER["task_id"],
        ),
        (R005_PRODUCER["id"], R005_PRODUCER["task_id"]),
        (R005_PRIMARY_REVIEWER["id"], R005_PRIMARY_REVIEWER["task_id"]),
        (
            R005_INDEPENDENT_REVIEWER["id"],
            R005_INDEPENDENT_REVIEWER["task_id"],
        ),
        (R004_PRODUCER["id"], R004_PRODUCER["task_id"]),
        (R004_PRIMARY_REVIEWER["id"], R004_PRIMARY_REVIEWER["task_id"]),
        (
            R004_INDEPENDENT_REVIEWER["id"],
            R004_INDEPENDENT_REVIEWER["task_id"],
        ),
        (R003_PRODUCER["id"], R003_PRODUCER["task_id"]),
        (R003_PRIMARY_REVIEWER["id"], R003_PRIMARY_REVIEWER["task_id"]),
        (
            R003_INDEPENDENT_REVIEWER["id"],
            R003_INDEPENDENT_REVIEWER["task_id"],
        ),
        (R002_PRODUCER["id"], R002_PRODUCER["task_id"]),
        (R002_PRIMARY_REVIEWER["id"], R002_PRIMARY_REVIEWER["task_id"]),
        (
            R002_INDEPENDENT_REVIEWER["id"],
            R002_INDEPENDENT_REVIEWER["task_id"],
        ),
        (R001_PRODUCER["id"], R001_PRODUCER["task_id"]),
        (R001_PRIMARY_REVIEWER["id"], R001_PRIMARY_REVIEWER["task_id"]),
        (
            R001_INDEPENDENT_REVIEWER["id"],
            R001_INDEPENDENT_REVIEWER["task_id"],
        ),
    }
    require(len(identities) == 30, "seq99 review identities are not distinct")
    result_at = _parse_time(result.get("reviewed_at"), "primary review time")
    independent_at = _parse_time(
        independent.get("reviewed_at"), "independent review time"
    )
    r009_independent_at = _parse_time(
        R009_INDEPENDENT_REVIEW_AT,
        "R009 independent review time",
    )
    require(
        r009_independent_at < result_at < independent_at,
        "seq99 R009-failure-to-R010 review chronology differs",
    )
    return (
        {
            "assignment": _binding(REVIEW_ASSIGNMENT_REL, assignment_raw),
            "review_result": _binding(REVIEW_RESULT_REL, result_raw),
            "independent_review": _binding(
                INDEPENDENT_REVIEW_REL, independent_raw
            ),
        },
        result_at,
        independent_at,
    )


def _cache_file_identity_seal(
    root: Path,
    paths: Sequence[Path],
) -> tuple[tuple[Any, ...], ...]:
    rows: list[tuple[Any, ...]] = []
    for path in sorted(set(paths), key=lambda value: value.as_posix()):
        read = seq90._stable_read(root, path)
        identity = read.identity
        rows.append(
            (
                "file",
                path.as_posix(),
                sha256_bytes(read.raw),
                len(read.raw),
                identity.device,
                identity.inode,
                identity.mode,
                identity.uid,
                identity.links,
                identity.size,
                identity.mtime_ns,
                identity.ctime_ns,
            )
        )
    return tuple(rows)


def _review_chain_call_cache_seal(
    root: Path,
    review: Mapping[str, Any],
) -> tuple[tuple[Any, ...], ...]:
    frozen_groups = (
        (R001_REVIEW_PATHS, R001_FROZEN_BINDINGS),
        (R002_REVIEW_PATHS, R002_FROZEN_BINDINGS),
        (R004_REVIEW_PATHS, R004_FROZEN_BINDINGS),
        (R005_REVIEW_PATHS, R005_FROZEN_BINDINGS),
        (R006_REVIEW_PATHS, R006_FROZEN_BINDINGS),
        (R007_REVIEW_PATHS, R007_FROZEN_BINDINGS),
        (R008_REVIEW_PATHS, R008_FROZEN_BINDINGS),
        (R009_REVIEW_PATHS, R009_FROZEN_BINDINGS),
    )
    roles = ("authorization", "assignment", "review_result", "independent_review")
    for paths, bindings in frozen_groups:
        for role, path in zip(roles, paths, strict=True):
            raw, _value = _require_private_json(root, path)
            require(
                _binding(path, raw) == bindings[role],
                f"cached historical review changed: {path}",
            )
    for role, path in zip(
        ("authorization", "assignment"),
        R003_PARTIAL_REVIEW_PATHS,
        strict=True,
    ):
        raw, _value = _require_private_json(root, path)
        require(
            _binding(path, raw) == R003_FROZEN_BINDINGS[role],
            f"cached historical partial review changed: {path}",
        )
    require(
        all(not os.path.lexists(root / path) for path in R003_DECISION_PATHS),
        "cached R003 decision-path absence changed",
    )

    active_paths = (
        AUTHORIZATION_REL,
        REVIEW_ASSIGNMENT_REL,
        REVIEW_RESULT_REL,
        INDEPENDENT_REVIEW_REL,
    )
    active_reads = {
        path: _require_private_json(root, path)
        for path in active_paths
    }
    active_bindings = {
        path: _binding(path, active_reads[path][0])
        for path in active_paths
    }
    authorization = active_reads[AUTHORIZATION_REL][1]
    assignment = active_reads[REVIEW_ASSIGNMENT_REL][1]
    result = active_reads[REVIEW_RESULT_REL][1]
    independent = active_reads[INDEPENDENT_REVIEW_REL][1]
    r010_failure = r010_execution_failure_binding(root)
    require(
        assignment.get("authorization_binding")
        == active_bindings[AUTHORIZATION_REL]
        and result.get("assignment_binding")
        == active_bindings[REVIEW_ASSIGNMENT_REL]
        and independent.get("assignment_binding")
        == active_bindings[REVIEW_ASSIGNMENT_REL]
        and independent.get("review_result_binding")
        == active_bindings[REVIEW_RESULT_REL]
        and review.get("assignment")
        == active_bindings[REVIEW_ASSIGNMENT_REL]
        and review.get("review_result")
        == active_bindings[REVIEW_RESULT_REL]
        and review.get("independent_review")
        == active_bindings[INDEPENDENT_REVIEW_REL]
        and authorization.get("document_id") == AUTHORIZATION_DOCUMENT_ID,
        "cached active review chain changed",
    )
    require(
        _strict_json_equal(
            authorization.get("r010_execution_failure"), r010_failure
        )
        and _strict_json_equal(
            assignment.get("r010_execution_failure"), r010_failure
        ),
        "cached R010 execution failure changed",
    )
    rows = _validate_reviewed_control_rows(
        assignment.get("reviewed_control_inputs")
    )
    for path, row in zip(REVIEWED_CONTROL_PATHS, rows, strict=True):
        require(
            _observed_binding(root, path) == row,
            f"cached reviewed control changed: {path}",
        )
    sealed_files = {
        *R001_REVIEW_PATHS,
        *R002_REVIEW_PATHS,
        *R003_PARTIAL_REVIEW_PATHS,
        *R004_REVIEW_PATHS,
        *R005_REVIEW_PATHS,
        *R006_REVIEW_PATHS,
        *R007_REVIEW_PATHS,
        *R008_REVIEW_PATHS,
        *R009_REVIEW_PATHS,
        *active_paths,
        *REVIEWED_CONTROL_PATHS,
        R010_CONTRACT_REL,
        R010_RUNNER_REL,
        *(Path(row["path"]) for row in R010_LOG_BINDINGS),
    }
    gate_info = (root / R010_GATE_REL).lstat()
    gate_identity = seq90._identity(gate_info)
    return _cache_file_identity_seal(root, tuple(sealed_files)) + (
        (
            "directory",
            R010_GATE_REL.as_posix(),
            gate_identity.device,
            gate_identity.inode,
            gate_identity.mode,
            gate_identity.uid,
            gate_identity.links,
            gate_identity.size,
            gate_identity.mtime_ns,
            gate_identity.ctime_ns,
        ),
    )


def _copy_review_chain_result(
    result: tuple[dict[str, Any], datetime, datetime],
) -> tuple[dict[str, Any], datetime, datetime]:
    review, result_at, independent_at = result
    return copy.deepcopy(review), result_at, independent_at


def _load_review_chain(
    root: Path,
    source: Mapping[str, Any],
) -> tuple[dict[str, Any], datetime, datetime]:
    root = seq90._safe_root(root)
    source_raw = checkpoint_json_bytes(source)
    _require_exact_seq98_pins(source_raw, source)
    cache = _SEQ98_REVIEW_CHAIN_CALL_CACHE.get()
    if cache is None:
        return _compute_load_review_chain(root, source)
    key = (root.as_posix(), len(source_raw), sha256_bytes(source_raw))
    cached = cache.get(key, _SEQ98_REVIEW_CHAIN_CACHE_MISS)
    if cached is not _SEQ98_REVIEW_CHAIN_CACHE_MISS:
        cached_raw, kind, payload = cached
        if cached_raw == source_raw:
            if kind == "error":
                raise payload
            result, expected_seal = payload
            require(
                _review_chain_call_cache_seal(root, result[0])
                == expected_seal,
                "cached review-chain input seal changed",
            )
            return _copy_review_chain_result(result)
    try:
        result = _compute_load_review_chain(root, source)
        seal = _review_chain_call_cache_seal(root, result[0])
    except Exception as exc:
        cache[key] = (source_raw, "error", exc)
        raise
    cache[key] = (source_raw, "value", (result, seal))
    return _copy_review_chain_result(result)


def project_seq99(
    root: Path,
    source: Mapping[str, Any],
    *,
    managed_paths: Sequence[str],
    path_set_sha256: str,
    content_set_sha256: str,
    occurred_at: str,
    authorization_binding: Mapping[str, Any],
    review_binding: Mapping[str, Any],
    r010_failure_binding: Mapping[str, Any],
    replacement_contract_binding: Mapping[str, Any],
    replacement_runner_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    root = seq90._safe_root(root)
    _require_exact_seq98_pins(checkpoint_json_bytes(source), source)
    paths = sorted(set(managed_paths))
    required_managed_paths = {
        AUTHORIZATION_REL.as_posix(),
        *(path.as_posix() for path in REVIEW_PATHS),
        *(path.as_posix() for path in R001_REVIEW_PATHS),
        *(path.as_posix() for path in R002_REVIEW_PATHS),
        *(path.as_posix() for path in R003_PARTIAL_REVIEW_PATHS),
        *(path.as_posix() for path in R004_REVIEW_PATHS),
        *(path.as_posix() for path in R005_REVIEW_PATHS),
        *(path.as_posix() for path in R006_REVIEW_PATHS),
        *(path.as_posix() for path in R007_REVIEW_PATHS),
        *(path.as_posix() for path in R008_REVIEW_PATHS),
        *(path.as_posix() for path in R009_REVIEW_PATHS),
        *(path.as_posix() for path in REVIEWED_CONTROL_PATHS),
    }
    require(
        paths == list(managed_paths)
        and all(
            type(path) is str
            and path
            and not Path(path).is_absolute()
            and ".." not in Path(path).parts
            for path in paths
        )
        and required_managed_paths.issubset(paths)
        and not any(path.startswith(GATE_EVIDENCE_PREFIX) for path in paths)
        and path_set_sha256
        == hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest()
        and type(content_set_sha256) is str
        and seq90.SHA256_RE.fullmatch(content_set_sha256) is not None,
        "seq99 managed snapshot differs",
    )
    require(
        _strict_json_equal(
            r010_failure_binding, r010_execution_failure_binding(root)
        ),
        "seq99 R010 failure binding differs",
    )
    _require_file_binding(authorization_binding, AUTHORIZATION_REL, "authorization")
    require(
        isinstance(review_binding, Mapping)
        and set(review_binding)
        == {"assignment", "review_result", "independent_review"},
        "seq99 review binding differs",
    )
    for key, path in (
        ("assignment", REVIEW_ASSIGNMENT_REL),
        ("review_result", REVIEW_RESULT_REL),
        ("independent_review", INDEPENDENT_REVIEW_REL),
    ):
        _require_file_binding(review_binding[key], path, f"seq99 {key}")
    require(
        replacement_contract_binding.get("contract_id") == R011_CONTRACT_ID
        and replacement_contract_binding.get("contract_version")
        == R011_CONTRACT_VERSION
        and replacement_contract_binding.get("path")
        == R011_CONTRACT_REL.as_posix(),
        "seq99 R011 contract binding differs",
    )
    _require_file_binding(
        replacement_runner_binding, R011_RUNNER_REL, "seq99 R011 runner"
    )
    source_event = source["goal_execution"]["transition_history"][-1]
    require(
        source_event.get("contract_supersession", {}).get(
            "replacement_contract_binding"
        )
        == R010_CONTRACT_BINDING
        and source_event.get("start_gate_runner_binding") == R010_RUNNER_BINDING,
        "seq99 R010 predecessor authority differs",
    )
    occurred = _parse_time(occurred_at, "seq99 occurred_at")
    require(
        _parse_time(source_event.get("occurred_at"), "seq98 occurred_at") < occurred,
        "seq99 event chronology differs",
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
            "current_epic": HANDOFF_CURRENT_EPIC,
            "last_updated_by_work_item": WORK_ITEM_ID,
            "last_verification_status": HANDOFF_VERIFICATION_STATUS,
            "next_single_action": NEXT_ACTION,
        }
    )
    source_binding = _source_checkpoint_binding(r010_failure_binding)
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
            "FP048-R002_EXACT_PUBLISHED_SEQ98_SOURCE",
            "FP048-R002_R010_005_ALL_CHECKS_POST_CONTEXT_FAILURE_NONAUTHORITY",
            "FP048-R002_SEQ99_R001_PREFLIGHT_FAILED_NONAUTHORITY",
            (
                "FP048-R002_SEQ99_R002_PREFLIGHT_ABORTED_POST_REVIEW_"
                "P0_NONAUTHORITY"
            ),
            "FP048-R002_SEQ99_R003_PRIMARY_REVIEW_P1_REJECTED_NONAUTHORITY",
            (
                "FP048-R002_SEQ99_R004_PREFLIGHT_ABORTED_CONTINUATION_"
                "DESCENDANT_PERFORMANCE_P0_NONAUTHORITY"
            ),
            (
                "FP048-R002_SEQ99_R005_PREFLIGHT_ABORTED_GOAL_GRAPH_"
                "REPLAY_PERFORMANCE_P0_NONAUTHORITY"
            ),
            (
                "FP048-R002_SEQ99_R006_PRIMARY_REVIEW_P1_REJECTED_"
                "INVALID_REUSE_TIMEOUT_NONAUTHORITY"
            ),
            (
                "FP048-R002_SEQ99_R007_POST_REVIEW_GOAL_GRAPH_"
                "PREFLIGHT_TIMEOUT_NONAUTHORITY"
            ),
            (
                "FP048-R002_SEQ99_R008_POST_REVIEW_GOAL_GRAPH_SUCCESSOR_"
                "AUTHORITY_PREFLIGHT_FAILED_NONAUTHORITY"
            ),
            (
                "FP048-R002_SEQ99_R009_POST_REVIEW_WRITE_FAILED_SOURCE_"
                "RETAINED_NONAUTHORITY"
            ),
            "FP048-R002_R011_FRESH_START_GATE_CONTRACT",
            "FP048-R002_SEQ99_TRANSITION_CONTROL_REVIEW_R010",
        ],
        "source_checkpoint_binding": source_binding,
        "source_ready_event_binding": copy.deepcopy(
            source_event["source_ready_event_binding"]
        ),
        "authorization_binding": copy.deepcopy(dict(authorization_binding)),
        "contract_supersession": {
            "previous_contract_binding": copy.deepcopy(R010_CONTRACT_BINDING),
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
        "unchanged_control_projection": copy.deepcopy(
            source_event["unchanged_control_projection"]
        ),
        "canonical_binding_snapshot_after": copy.deepcopy(
            source_event["canonical_binding_snapshot_after"]
        ),
        "correction_reason": {
            "failed_contract_id": R010_CONTRACT_BINDING["contract_id"],
            "failed_contract_version": R010_CONTRACT_BINDING["contract_version"],
            "r010_execution_failure": copy.deepcopy(dict(r010_failure_binding)),
            "reason_code": CORRECTION_REASON_CODE,
            "remediation": (
                "SUPERSEDE_R010_WITH_PHASE_AWARE_R011_BEFORE_SEQ100_START"
            ),
        },
        "previous_event_sha256": SOURCE_EVENT_SHA256,
    }
    event["event_sha256"] = continuation.event_sha256(event)
    require(set(event) == EVENT_FIELDS, "seq99 correction event field set differs")
    state = projected["goal_execution"]
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = occurred_at
    require(
        state["goal_status"] == "READY"
        and state["status_by_goal"][GOAL_ID] == "READY"
        and current["status"] == "READY"
        and "IN_PROGRESS" not in state["status_by_goal"].values()
        and all(
            type(value) is int and value == 0
            for key, value in CLAIM_BOUNDARY.items()
            if key.endswith("_delta") or key == "goal_status_change_count"
        )
        and CLAIM_BOUNDARY["formal_test_not_run_count"] == 279
        and CLAIM_BOUNDARY["remaining_gate_count"] == 5
        and CLAIM_BOUNDARY["release_status"] == "NOT_ELIGIBLE"
        and CLAIM_BOUNDARY["implementation_start_authorized"] is False,
        "seq99 zero-credit READY state differs",
    )
    return projected, event


def _restored_seq98_checkpoint(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list)
        and len(history) == CORRECTION_SEQUENCE
        and isinstance(history[-1], dict)
        and history[-1].get("event_id") == CORRECTION_EVENT_ID,
        "seq98 inverse source is not exact seq99",
    )
    context = history[-1].get("repository_context_reanchor")
    before = context.get("before") if isinstance(context, dict) else None
    require(
        isinstance(before, dict)
        and all(
            key in before
            for key in ("current_work", "working_tree_snapshot", "session_handoff")
        ),
        "seq99 inverse repository context differs",
    )
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


def _validated_seq98_source_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
    run_external_validators: bool = False,
) -> bytes:
    root = seq90._safe_root(root)
    require(type(require_live_snapshot) is bool, "seq99 live-snapshot flag differs")
    require(
        type(run_external_validators) is bool,
        "seq99 external-validator flag differs",
    )
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "checkpoint is not exact seq99 R010 execution correction",
    )
    event = history[-1]
    failure = r010_execution_failure_binding(root)
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
        and event.get("contract_supersession", {}).get(
            "previous_contract_binding"
        )
        == R010_CONTRACT_BINDING
        and event.get("contract_supersession", {}).get("reason_code")
        == CORRECTION_REASON_CODE,
        "seq99 correction authority differs",
    )
    source = _restored_seq98_checkpoint(checkpoint)
    source_raw = checkpoint_json_bytes(source)
    require_exact_seq98_source(source_raw, source, root)
    authorization_raw, _authorization = _require_private_json(root, AUTHORIZATION_REL)
    review, _result_at, independent_at = _load_review_chain(root, source)
    replacement_contract = r011_contract_binding(root)
    replacement_runner = r011_runner_binding(root)
    require(
        event.get("authorization_binding")
        == _binding(AUTHORIZATION_REL, authorization_raw)
        and event.get("transition_control_review_binding") == review
        and event.get("contract_supersession", {}).get(
            "replacement_contract_binding"
        )
        == replacement_contract
        and event.get("start_gate_runner_binding") == replacement_runner,
        "seq99 reviewed successor binding differs",
    )
    require(
        independent_at < _parse_time(event.get("occurred_at"), "seq99 event time"),
        "seq99 review/event chronology differs",
    )
    snapshot = checkpoint.get("working_tree_snapshot")
    paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
    require(isinstance(paths, list), "seq99 managed paths differ")
    expected, expected_event = project_seq99(
        root,
        source,
        managed_paths=paths,
        path_set_sha256=snapshot.get("path_set_sha256"),
        content_set_sha256=snapshot.get("content_set_sha256"),
        occurred_at=event.get("occurred_at"),
        authorization_binding=event["authorization_binding"],
        review_binding=review,
        r010_failure_binding=failure,
        replacement_contract_binding=replacement_contract,
        replacement_runner_binding=replacement_runner,
    )
    require(
        checkpoint_json_bytes(expected) == checkpoint_json_bytes(checkpoint)
        and expected_event == event,
        "seq99 projected checkpoint differs",
    )
    if require_live_snapshot:
        _status_raw, visible = seq90.capture_git_visible_paths(root)
        required_visible = {
            path
            for path in visible
            if path != CHECKPOINT_REL.as_posix()
            and not path.startswith(GATE_EVIDENCE_PREFIX)
        }
        require(
            required_visible.issubset(paths)
            and continuation.working_snapshot_hashes(root, paths)
            == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
            "seq99 managed snapshot differs",
        )
    if run_external_validators:
        seq90._validate_projected_with_consumers(root, checkpoint)
    return source_raw


def require_start_gate_execution_corrected_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool = True,
    run_external_validators: bool = False,
) -> None:
    with seq98_source_validation_call_scope():
        _validated_seq98_source_bytes(
            root,
            checkpoint,
            require_live_snapshot=require_live_snapshot,
            run_external_validators=run_external_validators,
        )


def reconstructed_seq98_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    with seq98_source_validation_call_scope():
        return _validated_seq98_source_bytes(
            root,
            checkpoint,
            require_live_snapshot=False,
            run_external_validators=False,
        )


def canonical_seq99_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    with seq98_source_validation_call_scope():
        require_start_gate_execution_corrected_checkpoint(
            root,
            checkpoint,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        return checkpoint_json_bytes(checkpoint)


def _exact_review_control_seq99_checkpoint(
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
        "reviewed control authority requires exact seq99 or seq100",
    )
    started = importlib.import_module(
        "scripts.apply_walksafe_fp048_r002_goal_started_seq100_20260827"
    )
    validator = getattr(started, "require_started_checkpoint", None)
    inverse = getattr(started, "reconstructed_seq99_checkpoint_bytes", None)
    require(
        callable(validator) and callable(inverse),
        "seq100 reviewed control authority API differs",
    )
    validator(
        root,
        checkpoint,
        require_live_snapshot=require_live_snapshot,
        run_external_validators=False,
    )
    raw = inverse(root, checkpoint)
    candidate = seq90.strict_json(raw, "reconstructed seq99 review authority")
    require(
        raw == checkpoint_json_bytes(candidate),
        "reconstructed seq99 review authority bytes differ",
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
    root = seq90._safe_root(root)
    candidate = _exact_review_control_seq99_checkpoint(
        root,
        checkpoint,
        require_live_snapshot=require_live_snapshot,
    )
    event = candidate["goal_execution"]["transition_history"][-1]
    _authorization_raw, _authorization = _require_private_json(root, AUTHORIZATION_REL)
    assignment_raw, assignment = _require_private_json(root, REVIEW_ASSIGNMENT_REL)
    review_binding = event.get("transition_control_review_binding")
    require(
        isinstance(review_binding, Mapping)
        and review_binding.get("assignment")
        == _binding(REVIEW_ASSIGNMENT_REL, assignment_raw),
        "reviewed control assignment event binding differs",
    )
    rows = _validate_reviewed_control_rows(assignment.get("reviewed_control_inputs"))
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


def _final_managed_paths(
    source: Mapping[str, Any],
    visible: Sequence[str],
) -> tuple[str, ...]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    paths.update(visible)
    paths.discard(CHECKPOINT_REL.as_posix())
    paths = {
        path for path in paths if not path.startswith(GATE_EVIDENCE_PREFIX)
    }
    paths.update(path.as_posix() for path in REVIEWED_CONTROL_PATHS)
    paths.update(path.as_posix() for path in R001_REVIEW_PATHS)
    paths.update(path.as_posix() for path in R002_REVIEW_PATHS)
    paths.update(path.as_posix() for path in R003_PARTIAL_REVIEW_PATHS)
    paths.update(path.as_posix() for path in R004_REVIEW_PATHS)
    paths.update(path.as_posix() for path in R005_REVIEW_PATHS)
    paths.update(path.as_posix() for path in R006_REVIEW_PATHS)
    paths.update(path.as_posix() for path in R007_REVIEW_PATHS)
    paths.update(path.as_posix() for path in R008_REVIEW_PATHS)
    paths.update(path.as_posix() for path in R009_REVIEW_PATHS)
    paths.add(AUTHORIZATION_REL.as_posix())
    paths.update(path.as_posix() for path in REVIEW_PATHS)
    require(
        not any(path.startswith(GATE_EVIDENCE_PREFIX) for path in paths),
        "seq99 managed snapshot includes direct gate evidence",
    )
    return tuple(sorted(paths))


def _retained_input_paths(failure: Mapping[str, Any]) -> tuple[Path, ...]:
    paths = {
        AUTHORIZATION_REL,
        *REVIEW_PATHS,
        *R001_REVIEW_PATHS,
        *R002_REVIEW_PATHS,
        *R003_PARTIAL_REVIEW_PATHS,
        *R004_REVIEW_PATHS,
        *R005_REVIEW_PATHS,
        *R006_REVIEW_PATHS,
        *R007_REVIEW_PATHS,
        *R008_REVIEW_PATHS,
        *R009_REVIEW_PATHS,
        *REVIEWED_CONTROL_PATHS,
        R010_CONTRACT_REL,
        R010_RUNNER_REL,
        *(Path(row["path"]) for row in failure["logs"]),
    }
    return tuple(sorted(paths, key=lambda path: path.as_posix()))


def _require_prepared_exact(prepared: Prepared) -> None:
    transport = prepared.transport
    require(
        transport.source_raw == checkpoint_json_bytes(transport.source)
        and transport.projected_raw == checkpoint_json_bytes(transport.projected)
        and transport.event
        == transport.projected["goal_execution"]["transition_history"][-1],
        "prepared seq99 payload differs",
    )
    _require_exact_seq98_pins(transport.source_raw, transport.source)


def _require_prepared_live_exact(prepared: Prepared, *, phase: str) -> None:
    transport = prepared.transport
    _require_prepared_exact(prepared)
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
    require_start_gate_execution_corrected_checkpoint(
        transport.root,
        transport.projected,
        require_live_snapshot=True,
        run_external_validators=False,
    )
    snapshot = transport.projected["working_tree_snapshot"]
    require(
        sorted(path.as_posix() for path in transport.managed_inputs)
        == snapshot["managed_changed_paths"]
        and seq90._managed_input_snapshot_hashes(transport.managed_inputs)
        == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
        "prepared seq99 managed snapshot differs",
    )


def prepare(root: Path = ROOT, *, occurred_at: str | None = None) -> Prepared:
    with seq98_source_validation_call_scope():
        return _prepare(root, occurred_at=occurred_at)


def _prepare(root: Path = ROOT, *, occurred_at: str | None = None) -> Prepared:
    root = seq90._safe_root(root)
    source_read = seq90._stable_read(root, CHECKPOINT_REL)
    source = seq90.strict_json(source_read.raw, CHECKPOINT_REL.as_posix())
    require_exact_seq98_source(source_read.raw, source, root)
    require(
        stat.S_IMODE(source_read.identity.mode) == SOURCE_CHECKPOINT_MODE,
        "seq98 checkpoint mode differs",
    )
    failure = r010_execution_failure_binding(root)
    authorization_raw, _authorization = _require_private_json(root, AUTHORIZATION_REL)
    review, _result_at, independent_at = _load_review_chain(root, source)
    replacement_contract = r011_contract_binding(root)
    replacement_runner = r011_runner_binding(root)
    source_at = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq98 event time",
    )
    candidate_at = (
        _parse_time(occurred_at, "seq99 occurred_at")
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
    require(
        all(row["path"] in visible for row in failure["logs"]),
        "R010 failure logs are not Git-visible",
    )
    managed_paths = _final_managed_paths(source, visible)
    managed = seq90._capture_managed_inputs(root, managed_paths)
    visible_inputs = seq90._capture_managed_inputs(root, visible)
    path_sha256, content_sha256 = seq90._managed_input_snapshot_hashes(managed)
    projected, event = project_seq99(
        root,
        source,
        managed_paths=managed_paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        occurred_at=event_at,
        authorization_binding=_binding(AUTHORIZATION_REL, authorization_raw),
        review_binding=review,
        r010_failure_binding=failure,
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
    _require_prepared_live_exact(prepared, phase="during seq99 preflight")
    seq90._validate_projected_with_consumers(root, projected)
    _require_prepared_live_exact(prepared, phase="after seq99 consumer preflight")
    return prepared


def _require_atomic_boundary(prepared: Prepared) -> str:
    transport = prepared.transport
    _require_prepared_exact(prepared)
    current = seq90._stable_read(transport.root, CHECKPOINT_REL)
    if current.raw == transport.source_raw:
        phase = "SOURCE"
    elif current.raw == transport.projected_raw:
        phase = "PROJECTED"
    else:
        raise StartGateExecutionCorrectionError(
            "seq99 CAS live checkpoint phase differs"
        )
    seq90._require_inputs_unchanged(transport.root, transport.retained_inputs)
    seq90._require_managed_inputs_unchanged(
        transport.root, transport.managed_inputs
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
        transport.root, transport.git_head, transport.git_branch
    )
    status_raw, _visible = seq90.capture_git_visible_paths(transport.root)
    candidates = seq98._cas_write_temp_relatives(status_raw)
    require(len(candidates) == 1, "seq99 CAS temporary inventory differs")
    require(
        seq90._without_ephemeral_status_record(status_raw, candidates[0])
        == transport.git_status_raw,
        "Git-visible cohort changed at seq99 commit point",
    )
    temporary = seq90._stable_read(transport.root, candidates[0])
    expected_temporary = (
        transport.projected_raw if phase == "SOURCE" else transport.source_raw
    )
    require(
        temporary.raw == expected_temporary
        and stat.S_IMODE(temporary.identity.mode) == SOURCE_CHECKPOINT_MODE,
        "seq99 CAS temporary authority differs",
    )
    if phase == "SOURCE":
        require_exact_seq98_source(current.raw, transport.source, transport.root)
    else:
        require_start_gate_execution_corrected_checkpoint(
            transport.root,
            transport.projected,
            require_live_snapshot=False,
            run_external_validators=False,
        )
    return phase


def _write_checkpoint_atomic(prepared: Prepared) -> None:
    phases: list[str] = []

    def commit_guard() -> None:
        phases.append(_require_atomic_boundary(prepared))

    try:
        cas.atomic_write(
            prepared.transport.root / CHECKPOINT_REL,
            prepared.transport.projected_raw,
            expected_source=prepared.transport.source_raw,
            commit_guard=commit_guard,
        )
    except cas.CompletionPostCommitError as exc:
        raise seq90.PostcommitUncertain(str(exc)) from exc
    except cas.CompletionApplyError as exc:
        raise StartGateExecutionCorrectionError(str(exc)) from exc
    require(
        phases == ["SOURCE", "PROJECTED", "PROJECTED"],
        "seq99 CAS commit guard sequence differs",
    )


def write_checkpoint(
    prepared: Prepared,
    *,
    writer: Any | None = None,
) -> None:
    transport = prepared.transport
    current = seq90._stable_read(transport.root, CHECKPOINT_REL)
    if current.raw == transport.projected_raw:
        published = seq90.strict_json(current.raw, CHECKPOINT_REL.as_posix())
        require_start_gate_execution_corrected_checkpoint(
            transport.root,
            published,
            require_live_snapshot=True,
            run_external_validators=True,
        )
        return
    require(
        current.identity == transport.source_identity
        and current.raw == transport.source_raw,
        "seq98 source changed before seq99 publication",
    )
    _require_prepared_live_exact(prepared, phase="before seq99 publication")
    try:
        if writer is None:
            _write_checkpoint_atomic(prepared)
        else:
            writer(transport, commit_guard=lambda: _require_atomic_boundary(prepared))
    except seq90.PostcommitUncertain:
        raise
    except BaseException as exc:
        observed = seq90._stable_read(transport.root, CHECKPOINT_REL)
        if observed.raw == transport.projected_raw:
            raise seq90.PostcommitUncertain(
                "seq99 writer failed after publishing exact bytes"
            ) from exc
        raise
    published_read = seq90._stable_read(transport.root, CHECKPOINT_REL)
    if published_read.raw != transport.projected_raw:
        raise seq90.PostcommitUncertain(
            "published seq99 checkpoint bytes are uncertain"
        )
    try:
        published = seq90.strict_json(
            published_read.raw, CHECKPOINT_REL.as_posix()
        )
        require_start_gate_execution_corrected_checkpoint(
            transport.root,
            published,
            require_live_snapshot=True,
            run_external_validators=True,
        )
        require(
            reconstructed_seq98_checkpoint_bytes(transport.root, published)
            == transport.source_raw,
            "published seq99 inverse source differs",
        )
        terminal = seq90._stable_read(transport.root, CHECKPOINT_REL)
        require(
            terminal.identity == published_read.identity
            and terminal.raw == published_read.raw,
            "published seq99 checkpoint changed during terminal validation",
        )
    except BaseException as exc:
        raise seq90.PostcommitUncertain(
            "seq99 publication completed but terminal authority is uncertain"
        ) from exc


def _published_event_if_exact(root: Path) -> dict[str, Any] | None:
    root = seq90._safe_root(root)
    observed = seq90._stable_read(root, CHECKPOINT_REL)
    checkpoint = seq90.strict_json(observed.raw, CHECKPOINT_REL.as_posix())
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    if not isinstance(history, list) or len(history) != CORRECTION_SEQUENCE:
        return None
    require_start_gate_execution_corrected_checkpoint(
        root,
        checkpoint,
        require_live_snapshot=True,
        run_external_validators=True,
    )
    return copy.deepcopy(history[-1])


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare-review", action="store_true")
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--occurred-at")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.prepare_review:
            outputs = prepare_review_inputs(args.root)
            write_review_inputs(args.root, outputs)
            print(
                json.dumps(
                    {
                        "authorization": _binding(
                            AUTHORIZATION_REL, outputs[AUTHORIZATION_REL]
                        ),
                        "assignment": _binding(
                            REVIEW_ASSIGNMENT_REL,
                            outputs[REVIEW_ASSIGNMENT_REL],
                        ),
                        "written_paths": [
                            AUTHORIZATION_REL.as_posix(),
                            REVIEW_ASSIGNMENT_REL.as_posix(),
                        ],
                    },
                    ensure_ascii=False,
                    sort_keys=True,
                )
            )
            return 0
        if args.write:
            published = _published_event_if_exact(args.root)
            if published is not None:
                print(
                    "FP048-R002 seq99 R010 execution correction: PASS "
                    f"event_sha256={published['event_sha256']} published=true"
                )
                return 0
        prepared = prepare(args.root, occurred_at=args.occurred_at)
        published = False
        if args.write:
            write_checkpoint(prepared)
            published = True
        print(
            "FP048-R002 seq99 R010 execution correction: PASS "
            f"event_sha256={prepared.event['event_sha256']} "
            f"published={str(published).lower()}"
        )
        return 0
    except seq90.PostcommitUncertain as exc:
        print(
            "FP048-R002 seq99 R010 execution correction: "
            "POSTCOMMIT-UNCERTAIN: " + str(exc),
            file=sys.stderr,
        )
        return 2
    except (OSError, RuntimeError, TypeError, ValueError) as exc:
        print(
            "FP048-R002 seq99 R010 execution correction: FAIL: " + str(exc),
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
