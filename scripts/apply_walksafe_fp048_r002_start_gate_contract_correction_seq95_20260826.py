#!/usr/bin/env python3
"""Prepare the reviewed zero-credit FP048 R002 seq95 R006-to-R007 correction."""

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
    apply_walksafe_fp048_r002_start_gate_contract_correction_seq94_20260826
    as seq94,
)


class ContractCorrectionError(seq94.ContractCorrectionError):
    """The seq95 contract correction cannot be proven or published."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractCorrectionError(message)


seq90 = seq94.seq90
continuation = seq94.continuation
CHECKPOINT_REL = seq94.CHECKPOINT_REL
checkpoint_json_bytes = seq94.checkpoint_json_bytes
GOAL_ID = seq94.GOAL_ID
GOAL_PATH = seq94.GOAL_PATH
GOAL_SHA256 = seq94.GOAL_SHA256
WORK_ITEM_ID = seq94.WORK_ITEM_ID
MANIFEST_SHA256 = seq94.MANIFEST_SHA256
EVENT_FIELDS = seq94.EVENT_FIELDS
CLAIM_BOUNDARY = copy.deepcopy(seq94.CLAIM_BOUNDARY)

SOURCE_SEQUENCE = 94
SOURCE_EVENT_ID = seq94.CORRECTION_EVENT_ID
SOURCE_EVENT_SHA256 = (
    "5a055099dbb60ccfecbfa60bf52394b165f8aca71e0e063bed8e657bb4827739"
)
SOURCE_CHECKPOINT_SHA256 = (
    "e361140b2faa1ab5f8d00145251db5b990b33f140d5de5610289fbbd0dc62b12"
)
SOURCE_CHECKPOINT_BYTE_LENGTH = 3_758_409

CORRECTION_SEQUENCE = 95
CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-GATE-CONTRACT-CORRECTED-"
    "FP048-R002-20260826-003"
)
CORRECTION_EVENT_TYPE = "GOAL_START_GATE_CONTRACT_CORRECTED"
STARTED_SEQUENCE = 96
STARTED_EVENT_ID = seq94.STARTED_EVENT_ID

PHYSICAL_GIT_BRANCH = seq94.PHYSICAL_GIT_BRANCH
LOGICAL_BRANCH = seq94.LOGICAL_BRANCH
LOGICAL_BRANCH_SEMANTICS = seq94.LOGICAL_BRANCH_SEMANTICS
BRANCH_MISMATCH_REASON_CODE = seq94.BRANCH_MISMATCH_REASON_CODE

PASSED_GATE_EVENT_ID = seq94.PASSED_GATE_EVENT_ID
PASSED_GATE_DIR_REL = seq94.PASSED_GATE_DIR_REL
PASSED_GATE_FILE_PINS = seq94.PASSED_GATE_FILE_PINS

PREFLIGHT_ATTEMPT_EVENT_ID = STARTED_EVENT_ID
PREFLIGHT_ATTEMPT_DIR_REL = seq94.PREFLIGHT_ATTEMPT_DIR_REL
PREFLIGHT_ATTEMPT_RECEIPT_REL = seq94.PREFLIGHT_ATTEMPT_RECEIPT_REL
R006_PREFLIGHT_STATUS = "PREVIEW_FAILED_BEFORE_NAMESPACE"
R006_PREFLIGHT_AUTHORITY_STATUS = "NONAUTHORITY"
R006_PREFLIGHT_EVENT_IDENTITY_STATUS = "REUSABLE_UNCONSUMED"
R006_PREFLIGHT_ERROR = "checkpoint gate evidence reference is malformed"
R007_SUCCESSOR_REASON_CODE = (
    "R006_CHECKPOINT_NONAUTHORITY_RECEIPT_PATH_NOT_STAGE_AWARE"
)

FROZEN_SEQ94_R002_AUTHORIZATION_BINDING = {
    "path": (
        "docs/control/execution/workstream-transitions/seq94-95/"
        "authorization-r002.json"
    ),
    "sha256": "150e2c69509906af2046bdd249ce1138594f209abd53d862c938ba226b6bac7c",
    "byte_length": 11_902,
}
FROZEN_SEQ94_R002_REVIEW_BINDINGS = {
    "assignment": {
        "path": (
            "docs/control/execution/workstream-transitions/seq94-95/"
            "review-rounds/R002/review-assignment.json"
        ),
        "sha256": "c1bf3db201ccd8545c5fb2006cf2ac84e2d7c1f3e0d1e9520a0aef8c48f644b2",
        "byte_length": 24_966,
    },
    "review_result": {
        "path": (
            "docs/control/execution/workstream-transitions/seq94-95/"
            "review-rounds/R002/review-result.json"
        ),
        "sha256": "3ccd8eae0e316dfb30b2a901a204bb6e17fa585d9dcb53239e20e5a82656ccc7",
        "byte_length": 10_872,
    },
    "independent_review": {
        "path": (
            "docs/control/execution/workstream-transitions/seq94-95/"
            "review-rounds/R002/independent-review.json"
        ),
        "sha256": "17a4ddef7911dbd9addcbdff164a594bfe4c59b0d381d9ca13b256ce9b17ece7",
        "byte_length": 11_756,
    },
}
FROZEN_SEQ94_R002_REVIEWED_INPUTS: tuple[tuple[str, str, int], ...] = (
    (
        "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
        "initial-start-gate-contract-r006.json",
        "ec2d0a34a9622d2fcb144602e0b53f388dfaf43c021d4ca8b8a815e5b7ec7509",
        3_563,
    ),
    (
        "scripts/apply_walksafe_fp048_r002_goal_started_seq95_20260826.py",
        "cf52e1008bfe95a088cff5a60bc24187a3de1b5b778d6e95eaa21ef9d2f5ddd1",
        78_649,
    ),
    (
        "scripts/apply_walksafe_fp048_r002_start_gate_contract_correction_"
        "seq94_20260826.py",
        "463382baa3dbaf45bb07a0960a126563322f24bb8f6c5346d21543843ba47ca9",
        86_111,
    ),
    (
        "scripts/check_walksafe_goal_graph_v2_4.py",
        "799ec4cb7c950c21112f5773a3b33b62680d5f64023e915d2bcec92b3efcf576",
        839_059,
    ),
    (
        "scripts/check_walksafe_project_continuation_v2_4.py",
        "d769d1493c2c9993db91888865eb2b51b94c505154cb8bcc5c019cc2f48fdbdb",
        580_589,
    ),
    (
        "scripts/run_walksafe_fp048_r002_goal_start_gate_r006_20260826.py",
        "de7755cda818e2c60ac23af0c1b6dc5a3def4b71f2b64e41d7fa7a38c1044e75",
        26_746,
    ),
    (
        "scripts/run_walksafe_test_layers_current.sh",
        "ba81da03cf47b35dcf3f445e6ea79d43d6b238608279fad9681b001beff2e9d6",
        27_382,
    ),
    (
        "tests/test_apply_walksafe_fp048_r002_goal_started_seq95_20260826.py",
        "6951956c461620116893e4656d37917ed102b7d9f555bc92c5e96925e95022e9",
        57_966,
    ),
    (
        "tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_"
        "seq94_20260826.py",
        "69601271179ef881af3b8b21b8ab55d965dfacef597ee1ab6d1993d1285431ab",
        34_630,
    ),
    (
        "tests/test_walksafe_fp048_r002_goal_start_gate_r006_20260826.py",
        "d46d3428d61cdd487e9fc84f283baf77200ad2b2f2810b7579a8630832c99fef",
        31_162,
    ),
    (
        "tests/test_walksafe_fp048_r002_post_seq93_stage_regression_20260826.py",
        "3bc8f553b4377637a151ce835c787f6476a60e254738527510b2bb28cd92f3d5",
        9_155,
    ),
    (
        "tests/test_walksafe_goal_graph_v2_4.py",
        "bc41cd90540ada0ec2360a2dbf7726ae8a6c03b5dac72b99901fc3113733868e",
        559_614,
    ),
    (
        "tests/test_walksafe_project_continuation_v2_4.py",
        "efe407f2ed2df20689915dcf78ae9ee68a16377725520621fbedba94ffdedbad",
        303_017,
    ),
)
FROZEN_SEQ94_R002_INDEPENDENT_CHECKS = [
    "EXACT_PUBLISHED_SEQ93_SOURCE_CAS_PASS",
    "FROZEN_SEQ93_R003_REVIEW_TRIAD_AND_INPUTS_PASS",
    "R004_PASS_003_REMAINS_PRIVATE_PASS_UNCONSUMED_PASS",
    "R005_PREFLIGHT_004_HISTORICAL_NONAUTHORITY_AND_TEMPORAL_SCOPE_PASS",
    "R005_EXACT_CONTRACT_AND_RUNNER_PRESERVED_PASS",
    "R005_TO_R006_CONTRACT_SUPERSESSION_PASS",
    "SEQ94_READY_TO_READY_ZERO_CREDIT_PASS",
    "SEQ94_TO_SEQ93_EXACT_BYTE_RECONSTRUCTION_PASS",
    "FINAL_MANAGED_AND_GIT_VISIBLE_COHORT_RESEAL_PASS",
    "PRIVATE_0600_CHECKPOINT_MODE_PRECOMMIT_AND_POSTCOMMIT_PASS",
    "PROJECTED_CONTINUATION_AND_GOAL_GRAPH_PASS",
]

R006_CONTRACT_BINDING = {
    "schema_version": "1.2",
    "document_id": seq94.R006_DOCUMENT_ID,
    "path": seq94.R006_CONTRACT_REL.as_posix(),
    "file_sha256": seq94.R006_FILE_SHA256,
    "contract_id": seq94.R006_CONTRACT_ID,
    "contract_version": seq94.R006_CONTRACT_VERSION,
    "canonical_contract_sha256": seq94.R006_CANONICAL_SHA256,
}
R006_RUNNER_BINDING = {
    "path": seq94.R006_RUNNER_REL.as_posix(),
    "sha256": seq94.R006_RUNNER_SHA256,
    "byte_length": seq94.R006_RUNNER_BYTE_LENGTH,
}

R007_CONTRACT_REL = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r007.json"
)
R007_RUNNER_REL = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r007_20260826.py"
)
R007_RUNNER_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r007_20260826.py"
)
POST_SEQ94_STAGE_REGRESSION_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_post_seq94_stage_regression_20260826.py"
)
SEQ96_STARTER_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq96_20260826.py"
)
SEQ96_STARTER_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq96_20260826.py"
)
R007_CONTRACT_ID = "WS-FP048-R002-INTERNAL-START-GATE-R007"
R007_CONTRACT_VERSION = "2026-08-26.6"
R007_DOCUMENT_ID = "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-007"
# Replaced with the producing agent's exact final pins before review publication.
R007_FILE_SHA256 = (
    "ba18500d312c447b0f2107d63e1165d80bb2fc232afaba4a98310620b50fed1e"
)
R007_CANONICAL_SHA256 = (
    "9fbb5c244c538641dcde2035d4903faa0ff09692dedacb99e63d05261ecf7581"
)
R007_BYTE_LENGTH = 3_567
R007_RUNNER_SHA256 = (
    "6c4bbbad097e783d10951b212d5ef285c7c8cc51fe6fbe0212528bb0c5cdd9e8"
)
R007_RUNNER_BYTE_LENGTH = 32_322
R007_CONTRACT_BINDING = {
    "schema_version": "1.2",
    "document_id": R007_DOCUMENT_ID,
    "path": R007_CONTRACT_REL.as_posix(),
    "file_sha256": R007_FILE_SHA256,
    "contract_id": R007_CONTRACT_ID,
    "contract_version": R007_CONTRACT_VERSION,
    "canonical_contract_sha256": R007_CANONICAL_SHA256,
}
R007_RUNNER_BINDING = {
    "path": R007_RUNNER_REL.as_posix(),
    "sha256": R007_RUNNER_SHA256,
    "byte_length": R007_RUNNER_BYTE_LENGTH,
}

SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp048_r002_start_gate_contract_correction_"
    "seq95_20260826.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_"
    "seq95_20260826.py"
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
            R007_CONTRACT_REL,
            R007_RUNNER_REL,
            R007_RUNNER_TEST_REL,
            POST_SEQ94_STAGE_REGRESSION_TEST_REL,
            SEQ96_STARTER_REL,
            SEQ96_STARTER_TEST_REL,
            CONTINUATION_SCRIPT_REL,
            CONTINUATION_TEST_REL,
            GOAL_GRAPH_SCRIPT_REL,
            GOAL_GRAPH_TEST_REL,
            LAYER_RUNNER_REL,
        },
        key=lambda path: path.as_posix(),
    )
)
FIXED_REVIEWED_CONTROL_BINDINGS: dict[Path, dict[str, Any]] = {
    R007_CONTRACT_REL: {
        "path": R007_CONTRACT_REL.as_posix(),
        "sha256": R007_FILE_SHA256,
        "byte_length": R007_BYTE_LENGTH,
    },
    R007_RUNNER_REL: {
        "path": R007_RUNNER_REL.as_posix(),
        "sha256": R007_RUNNER_SHA256,
        "byte_length": R007_RUNNER_BYTE_LENGTH,
    },
    R007_RUNNER_TEST_REL: {
        "path": R007_RUNNER_TEST_REL.as_posix(),
        "sha256": "dcf1469e31b16d2472eff7806fa5d3dfc16be6696908918dc9b087ad5ed32463",
        "byte_length": 18_141,
    },
    POST_SEQ94_STAGE_REGRESSION_TEST_REL: {
        "path": POST_SEQ94_STAGE_REGRESSION_TEST_REL.as_posix(),
        "sha256": "b7fa5518748e7da3d2a23f3dfab940320baff4bcd0cf02490e1b93b8cb142fe3",
        "byte_length": 8_319,
    },
    SEQ96_STARTER_REL: {
        "path": SEQ96_STARTER_REL.as_posix(),
        "sha256": "05fb2ef21595fba711d1ac06161174ae130085fd43de99d9bd7d7382c49f554a",
        "byte_length": 81_024,
    },
    SEQ96_STARTER_TEST_REL: {
        "path": SEQ96_STARTER_TEST_REL.as_posix(),
        "sha256": "6caf02bbd640ba1b063d916be43a44d2a4b9d0376e050b27469201dd03ab4fa3",
        "byte_length": 64_288,
    },
    GOAL_GRAPH_SCRIPT_REL: {
        "path": GOAL_GRAPH_SCRIPT_REL.as_posix(),
        "sha256": "9b989341dd28813ae04fec675579f8b4b0de728674697fd7de51a38cd08daea0",
        "byte_length": 862_644,
    },
    GOAL_GRAPH_TEST_REL: {
        "path": GOAL_GRAPH_TEST_REL.as_posix(),
        "sha256": "17ab24852f3c37efb56cb21263363e65321cf54a64d0d6ea49d049c6bdabbcce",
        "byte_length": 590_149,
    },
    LAYER_RUNNER_REL: {
        "path": LAYER_RUNNER_REL.as_posix(),
        "sha256": "cac4c8b452983cce77f1874b49d14acd8336dc57f5c88f5ac3ba880999d315a6",
        "byte_length": 27_679,
    },
    CONTINUATION_SCRIPT_REL: {
        "path": CONTINUATION_SCRIPT_REL.as_posix(),
        "sha256": "0df90bf720a59879be4fdb0385e7b51db89d3a7c5a97dd73ef3282b5f9f11e25",
        "byte_length": 608_527,
    },
    CONTINUATION_TEST_REL: {
        "path": CONTINUATION_TEST_REL.as_posix(),
        "sha256": "4b3d95f6cf8633eb30ea01dd8e5ad45575bf79ed2e4abdefce2e8fd7ce20f752",
        "byte_length": 326_539,
    },
}

TRANSITION_ROOT = Path("docs/control/execution/workstream-transitions/seq95-96")
AUTHORIZATION_REL = TRANSITION_ROOT / "authorization.json"
FROZEN_AUTHORIZATION_BINDING = {
    "path": AUTHORIZATION_REL.as_posix(),
    "sha256": "28e53c257a2090901f5a7760bf6d09c7033d525616eb157f92eccff3655778aa",
    "byte_length": 13_457,
}
R001_ROUND_ID = "R001"
R001_REVIEW_ROOT = TRANSITION_ROOT / f"review-rounds/{R001_ROUND_ID}"
FROZEN_R001_REVIEW_BINDINGS = {
    "assignment": {
        "path": (R001_REVIEW_ROOT / "review-assignment.json").as_posix(),
        "sha256": "40fc9a52664c49692b60944e9a9aa16a942bca02deb510adb64a369683683740",
        "byte_length": 19_458,
    },
    "review_result": {
        "path": (R001_REVIEW_ROOT / "review-result.json").as_posix(),
        "sha256": "854505320b3113948e9f62bb6692ded4661479262802c2a4ac779a4fdb638d40",
        "byte_length": 12_302,
    },
    "independent_review": {
        "path": (R001_REVIEW_ROOT / "independent-review.json").as_posix(),
        "sha256": "b43e3ea619fa30c632bd20a1fd60682d01599ee0917783583413496dcecdcf75",
        "byte_length": 13_231,
    },
}
R001_INDEPENDENT_REVIEWED_AT = "2026-08-26T18:39:25+09:00"
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

HISTORICAL_R005_PREFLIGHT_ATTEMPT_004 = copy.deepcopy(
    seq94._stored_preflight_attempt_004()
)
R006_PREFLIGHT_ATTEMPT_004 = {
    "event_id": PREFLIGHT_ATTEMPT_EVENT_ID,
    "contract_id": R006_CONTRACT_BINDING["contract_id"],
    "contract_version": R006_CONTRACT_BINDING["contract_version"],
    "directory": PREFLIGHT_ATTEMPT_DIR_REL.as_posix(),
    "receipt_path": PREFLIGHT_ATTEMPT_RECEIPT_REL.as_posix(),
    "status": R006_PREFLIGHT_STATUS,
    "authority_status": R006_PREFLIGHT_AUTHORITY_STATUS,
    "event_identity_status": R006_PREFLIGHT_EVENT_IDENTITY_STATUS,
    "namespace_present": False,
    "receipt_present": False,
    "error": R006_PREFLIGHT_ERROR,
    "reason_code": R007_SUCCESSOR_REASON_CODE,
}
CORRECTION_REASON = {
    "failed_contract_id": R006_CONTRACT_BINDING["contract_id"],
    "failed_contract_version": R006_CONTRACT_BINDING["contract_version"],
    "r006_preflight_attempt_004": copy.deepcopy(R006_PREFLIGHT_ATTEMPT_004),
    "reason_code": R007_SUCCESSOR_REASON_CODE,
    "remediation": "SUPERSEDE_R006_WITH_STAGE_AWARE_R007_BEFORE_SEQ96_START",
}
PROJECTED_TRANSITION = {
    "contract_correction_event_id": CORRECTION_EVENT_ID,
    "contract_correction_sequence": CORRECTION_SEQUENCE,
    "contract_correction_transition": "READY_TO_READY",
    "started_event_id": STARTED_EVENT_ID,
    "started_sequence": STARTED_SEQUENCE,
    "started_transition": "READY_TO_IN_PROGRESS_AFTER_FRESH_R007_PASS",
}
REVIEWER = {
    "id": "codex-fp048-r002-seq95-contract-independent-reviewer-20260826",
    "task_id": "/root/seq95_contract_correction_independent_review_r002",
}
SUPERSEDES_ROUND_ID = R001_ROUND_ID
SUPERSESSION_REASON = "R001_PROJECTED_PREFLIGHT_FAILED_BEFORE_WRITE"
R001_PROJECTED_PREFLIGHT_FAILURE = {
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
    "error_summary": (
        "projected Goal graph failed: FP048 R002 seq95 R007 correction authority "
        "differs: fixed R006 review cohort binding differs"
    ),
    "preflight_attempt_004": {
        "directory": PREFLIGHT_ATTEMPT_DIR_REL.as_posix(),
        "receipt_path": PREFLIGHT_ATTEMPT_RECEIPT_REL.as_posix(),
        "namespace_present": False,
        "receipt_present": False,
    },
    "status": "PROJECTED_PREFLIGHT_FAILED_BEFORE_WRITE",
    "validator_stage": "_validate_projected_with_consumers",
}
INDEPENDENT_CHECKS = [
    "EXACT_PUBLISHED_SEQ94_SOURCE_CAS_PASS",
    "FROZEN_SEQ94_R002_AUTHORIZATION_AND_REVIEW_TRIAD_PASS",
    "R004_PASS_003_REMAINS_PRIVATE_PASS_UNCONSUMED_PASS",
    "R005_PREFLIGHT_004_HISTORICAL_NONAUTHORITY_PASS",
    "R006_PREFLIGHT_004_NO_NAMESPACE_NONAUTHORITY_PASS",
    "R006_EXACT_CONTRACT_AND_RUNNER_PRESERVED_PASS",
    "R006_TO_R007_CONTRACT_SUPERSESSION_PASS",
    "SEQ95_READY_TO_READY_ZERO_CREDIT_PASS",
    "SEQ95_TO_SEQ94_EXACT_BYTE_RECONSTRUCTION_PASS",
    "FINAL_MANAGED_AND_GIT_VISIBLE_COHORT_RESEAL_PASS",
    "PRIVATE_0600_CHECKPOINT_MODE_PRECOMMIT_AND_POSTCOMMIT_PASS",
    "PROJECTED_CONTINUATION_AND_GOAL_GRAPH_PASS",
    "R001_REVIEW_TRIAD_PRESERVED_AND_SUPERSEDED_PASS",
    "R001_PROJECTED_PREFLIGHT_FAILURE_BOUNDARY_FIXED_PASS",
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
        "frozen_seq94_r002_review_binding",
        "goal_id",
        "historical_r005_preflight_attempt_004",
        "passed_gate_attempt_003_binding",
        "previous_contract_binding",
        "projected_transition",
        "r001_projected_preflight_failure",
        "r006_preflight_attempt_004",
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

SCOPE = (
    "Graph v2.4 FP-048 R002 remains READY through zero-credit seq95 R006-to-"
    "R007 gate-contract correction; -003 remains PASS_UNCONSUMED, R005 and R006 "
    "-004 preflights remain NONAUTHORITY, and fresh R007 gate is NOT_RUN."
)
CURRENT_FOCUS = (
    "FP-048 R002/GAP-057 READY; -003 PASS_UNCONSUMED; R005/R006 -004 "
    "preflights NONAUTHORITY; R007 gate armed; seq96 start -004 NOT_RUN"
)
NEXT_ACTION = (
    "R007 event-scoped five-check gate를 reusable -004 event ID로 실행하고 "
    "PASS receipt로 seq96 start를 검증한다."
)
HANDOFF_EPIC = (
    "EPIC-03 / FP-048 R002/GAP-057 READY_R006_PREFLIGHT_NONAUTHORITY_"
    "R007_GATE_NOT_RUN"
)
VERIFICATION_STATUS = (
    "FP048_R002_SEQ95_ZERO_CREDIT_R007_CONTRACT_CORRECTED_GATE_NOT_RUN"
)


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _strict_json_equal(left: Any, right: Any) -> bool:
    return seq90.canonical_json_bytes({"value": left}) == seq90.canonical_json_bytes(
        {"value": right}
    )


def _binding(relative: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": relative.as_posix(),
        "sha256": sha256_bytes(raw),
        "byte_length": len(raw),
    }


def _parse_time(value: str, label: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value)
    except (TypeError, ValueError) as exc:
        raise ContractCorrectionError(f"{label} differs") from exc
    require(parsed.tzinfo is not None and parsed.microsecond == 0, f"{label} differs")
    return parsed


def _stored_passed_gate_attempt_003_binding() -> dict[str, Any]:
    return copy.deepcopy(seq94._stored_passed_gate_attempt_003_binding())


def passed_gate_attempt_003_binding(root: Path = ROOT) -> dict[str, Any]:
    observed = seq94.passed_gate_attempt_003_binding(root)
    require(
        observed == _stored_passed_gate_attempt_003_binding(),
        "R004 PASS -003 binding differs",
    )
    return copy.deepcopy(observed)


def _stored_r006_preflight_attempt_004() -> dict[str, Any]:
    return copy.deepcopy(R006_PREFLIGHT_ATTEMPT_004)


def r006_preflight_attempt_004_observation(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    require(
        not os.path.lexists(root / PREFLIGHT_ATTEMPT_DIR_REL)
        and not os.path.lexists(root / PREFLIGHT_ATTEMPT_RECEIPT_REL),
        "R006 preflight -004 namespace or receipt must remain absent before R007",
    )
    return _stored_r006_preflight_attempt_004()


def r001_projected_preflight_failure_observation(
    root: Path = ROOT,
) -> dict[str, Any]:
    """Reconfirm the failed R001 preflight left source and gate paths untouched."""

    root = seq90._safe_root(root)
    read = seq90._stable_read(root, CHECKPOINT_REL)
    checkpoint = seq90.strict_json(read.raw, CHECKPOINT_REL.as_posix())
    require(
        len(read.raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(read.raw) == SOURCE_CHECKPOINT_SHA256
        and read.raw == checkpoint_json_bytes(checkpoint),
        "R001 failed preflight did not preserve exact seq94 checkpoint",
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
    return copy.deepcopy(R001_PROJECTED_PREFLIGHT_FAILURE)


def _stage_aware_r001_projected_preflight_failure(
    root: Path,
) -> dict[str, Any]:
    root = seq90._safe_root(root)
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
    return copy.deepcopy(R001_PROJECTED_PREFLIGHT_FAILURE)


def frozen_r001_review_binding(
    root: Path = ROOT,
) -> dict[str, dict[str, Any]]:
    """Validate immutable R001 bytes without rebuilding its stale cohort."""

    root = seq90._safe_root(root)
    authorization_raw = seq90._stable_read(root, AUTHORIZATION_REL).raw
    require(
        _binding(AUTHORIZATION_REL, authorization_raw) == FROZEN_AUTHORIZATION_BINDING,
        "frozen seq95 R001 authorization bytes differ",
    )
    authorization = seq90.strict_json(
        authorization_raw, AUTHORIZATION_REL.as_posix()
    )
    require(
        authorization_raw == seq90.canonical_json_bytes(authorization)
        and authorization.get("document_id")
        == "WS-FP048-R002-SEQ95-96-AUTHORIZATION-20260826-001"
        and authorization.get("goal_id") == GOAL_ID,
        "frozen seq95 R001 authorization authority differs",
    )

    documents: dict[str, dict[str, Any]] = {}
    observed: dict[str, dict[str, Any]] = {}
    for role, expected in FROZEN_R001_REVIEW_BINDINGS.items():
        relative = Path(expected["path"])
        raw = seq90._stable_read(root, relative).raw
        require(
            _binding(relative, raw) == expected,
            f"frozen seq95 R001 {role} bytes differ",
        )
        document = seq90.strict_json(raw, relative.as_posix())
        require(
            raw == seq90.canonical_json_bytes(document),
            f"frozen seq95 R001 {role} is noncanonical",
        )
        documents[role] = document
        observed[role] = copy.deepcopy(expected)

    assignment = documents["assignment"]
    result = documents["review_result"]
    independent = documents["independent_review"]
    require(
        assignment.get("document_id")
        == "WS-FP048-R002-SEQ95-96-REVIEW-ASSIGNMENT-20260826-R001"
        and assignment.get("round_id") == R001_ROUND_ID
        and assignment.get("authorization_binding") == FROZEN_AUTHORIZATION_BINDING
        and result.get("document_id")
        == "WS-FP048-R002-SEQ95-96-REVIEW-RESULT-20260826-R001"
        and result.get("round_id") == R001_ROUND_ID
        and result.get("assignment_binding") == observed["assignment"]
        and result.get("decision")
        == "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and result.get("findings") == []
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ95-96-INDEPENDENT-REVIEW-20260826-R001"
        and independent.get("round_id") == R001_ROUND_ID
        and independent.get("assignment_binding") == observed["assignment"]
        and independent.get("review_result_binding") == observed["review_result"]
        and independent.get("decision")
        == "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and independent.get("findings") == []
        and independent.get("reviewed_at") == R001_INDEPENDENT_REVIEWED_AT,
        "frozen seq95 R001 review authority differs",
    )
    return copy.deepcopy(observed)


def r006_contract_binding(root: Path = ROOT) -> dict[str, Any]:
    observed = seq94.r006_contract_binding(root)
    require(observed == R006_CONTRACT_BINDING, "R006 contract binding differs")
    return copy.deepcopy(observed)


def r006_runner_binding(root: Path = ROOT) -> dict[str, Any]:
    observed = seq94.r006_runner_binding(root)
    require(observed == R006_RUNNER_BINDING, "R006 runner binding differs")
    return copy.deepcopy(observed)


def frozen_seq94_r002_review_binding(
    root: Path = ROOT,
    source_event: Mapping[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    """Validate the published seq94 R002 authority from frozen physical bytes."""

    root = seq90._safe_root(root)
    authorization_rel = Path(FROZEN_SEQ94_R002_AUTHORIZATION_BINDING["path"])
    authorization_raw = seq90._stable_read(root, authorization_rel).raw
    require(
        _binding(authorization_rel, authorization_raw)
        == FROZEN_SEQ94_R002_AUTHORIZATION_BINDING,
        "frozen seq94 R002 authorization bytes differ",
    )
    authorization = seq90.strict_json(
        authorization_raw, authorization_rel.as_posix()
    )
    require(
        authorization_raw == seq90.canonical_json_bytes(authorization)
        and authorization.get("schema_version") == "1.0"
        and authorization.get("document_id")
        == "WS-FP048-R002-SEQ94-95-AUTHORIZATION-20260826-002"
        and authorization.get("goal_id") == GOAL_ID
        and authorization.get("preflight_attempt_004")
        == HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        and authorization.get("target_started_event_id") == STARTED_EVENT_ID,
        "frozen seq94 R002 authorization authority differs",
    )

    documents: dict[str, dict[str, Any]] = {}
    observed: dict[str, dict[str, Any]] = {}
    for role, expected in FROZEN_SEQ94_R002_REVIEW_BINDINGS.items():
        relative = Path(expected["path"])
        raw = seq90._stable_read(root, relative).raw
        require(
            _binding(relative, raw) == expected,
            f"frozen seq94 R002 {role} bytes differ",
        )
        document = seq90.strict_json(raw, relative.as_posix())
        require(
            raw == seq90.canonical_json_bytes(document),
            f"frozen seq94 R002 {role} is noncanonical",
        )
        documents[role] = document
        observed[role] = copy.deepcopy(expected)

    historical_inputs = [
        {"path": path, "sha256": digest, "byte_length": length}
        for path, digest, length in FROZEN_SEQ94_R002_REVIEWED_INPUTS
    ]
    assignment = documents["assignment"]
    result = documents["review_result"]
    independent = documents["independent_review"]
    require(
        len(historical_inputs) == 13
        and len({row["path"] for row in historical_inputs}) == 13
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ94-95-REVIEW-ASSIGNMENT-20260826-R002"
        and assignment.get("round_id") == "R002"
        and assignment.get("goal_id") == GOAL_ID
        and assignment.get("authorization_binding")
        == FROZEN_SEQ94_R002_AUTHORIZATION_BINDING
        and assignment.get("reviewed_control_inputs") == historical_inputs
        and result.get("document_id")
        == "WS-FP048-R002-SEQ94-95-REVIEW-RESULT-20260826-R002"
        and result.get("assignment_binding") == observed["assignment"]
        and result.get("decision")
        == "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and result.get("findings") == []
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ94-95-INDEPENDENT-REVIEW-20260826-R002"
        and independent.get("assignment_binding") == observed["assignment"]
        and independent.get("review_result_binding") == observed["review_result"]
        and independent.get("decision")
        == "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and independent.get("findings") == []
        and independent.get("independent_checks")
        == FROZEN_SEQ94_R002_INDEPENDENT_CHECKS,
        "frozen seq94 R002 review authority differs",
    )
    if source_event is not None:
        require(
            source_event.get("authorization_binding")
            == FROZEN_SEQ94_R002_AUTHORIZATION_BINDING
            and source_event.get("transition_control_review_binding") == observed,
            "seq94 source frozen R002 authority differs",
        )
    return copy.deepcopy(observed)


def load_r007_contract(
    root: Path = ROOT,
) -> tuple[dict[str, Any], dict[str, Any]]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, R007_CONTRACT_REL).raw
    require(
        R007_BYTE_LENGTH > 0
        and len(raw) == R007_BYTE_LENGTH
        and sha256_bytes(raw) == R007_FILE_SHA256,
        "R007 contract bytes differ",
    )
    document = seq90.strict_json(raw, R007_CONTRACT_REL.as_posix())
    require(
        raw == seq90.canonical_json_bytes(document),
        "R007 contract is noncanonical",
    )
    observed = {
        "schema_version": document.get("schema_version"),
        "document_id": document.get("document_id"),
        "path": R007_CONTRACT_REL.as_posix(),
        "file_sha256": R007_FILE_SHA256,
        "contract_id": document.get("contract_id"),
        "contract_version": document.get("contract_version"),
        "canonical_contract_sha256": continuation.canonical_json_sha256(document),
    }
    require(
        document.get("schema_version") == "1.2"
        and document.get("document_id") == R007_DOCUMENT_ID
        and document.get("contract_id") == R007_CONTRACT_ID
        and document.get("contract_version") == R007_CONTRACT_VERSION
        and document.get("target_goal_id") == GOAL_ID
        and document.get("target_goal_content_sha256") == GOAL_SHA256
        and document.get("successor_reason_code") == R007_SUCCESSOR_REASON_CODE
        and document.get("supersedes", {}).get("contract_id")
        == R006_CONTRACT_BINDING["contract_id"]
        and document.get("supersedes", {}).get("source_correction_event_id")
        == CORRECTION_EVENT_ID
        and document.get("supersedes", {}).get("source_correction_event_sequence")
        == CORRECTION_SEQUENCE
        and observed["canonical_contract_sha256"] == R007_CANONICAL_SHA256,
        "R007 contract authority differs",
    )
    return document, observed


def r007_contract_binding(root: Path = ROOT) -> dict[str, Any]:
    _document, binding = load_r007_contract(root)
    return copy.deepcopy(binding)


def r007_runner_binding(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, R007_RUNNER_REL).raw
    observed = _binding(R007_RUNNER_REL, raw)
    require(
        R007_RUNNER_BYTE_LENGTH > 0
        and observed
        == {
            "path": R007_RUNNER_REL.as_posix(),
            "sha256": R007_RUNNER_SHA256,
            "byte_length": R007_RUNNER_BYTE_LENGTH,
        },
        "R007 runner binding differs",
    )
    return observed


def _source_checkpoint_binding(
    passed_gate: Mapping[str, Any] | None = None,
    r006_preflight: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "path": CHECKPOINT_REL.as_posix(),
        "sha256": SOURCE_CHECKPOINT_SHA256,
        "byte_length": SOURCE_CHECKPOINT_BYTE_LENGTH,
        "sequence": SOURCE_SEQUENCE,
        "tail_event_id": SOURCE_EVENT_ID,
        "tail_event_sha256": SOURCE_EVENT_SHA256,
        "passed_gate_attempt_003": copy.deepcopy(
            dict(passed_gate)
            if passed_gate is not None
            else _stored_passed_gate_attempt_003_binding()
        ),
        "preflight_attempt_004": copy.deepcopy(
            HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        ),
        "r006_preflight_attempt_004": copy.deepcopy(
            dict(r006_preflight)
            if r006_preflight is not None
            else _stored_r006_preflight_attempt_004()
        ),
    }


def require_exact_seq94_source(
    raw: bytes,
    source: Mapping[str, Any],
    root: Path = ROOT,
) -> None:
    root = seq90._safe_root(root)
    require(
        len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256,
        "seq94 source CAS differs",
    )
    require(
        raw == checkpoint_json_bytes(source),
        "seq94 source is noncanonical",
    )
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list)
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
        "seq1-94 source lineage differs",
    )
    event = history[-1]
    frozen_review = frozen_seq94_r002_review_binding(root, event)
    passed_gate = passed_gate_attempt_003_binding(root)
    require(
        type(event.get("sequence")) is int
        and event.get("sequence") == SOURCE_SEQUENCE
        and event.get("event_id") == SOURCE_EVENT_ID
        and event.get("event_sha256") == SOURCE_EVENT_SHA256
        and event.get("event_type") == seq94.CORRECTION_EVENT_TYPE
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("authorization_binding")
        == FROZEN_SEQ94_R002_AUTHORIZATION_BINDING
        and event.get("transition_control_review_binding") == frozen_review
        and event.get("source_checkpoint_binding", {}).get(
            "passed_gate_attempt_003"
        )
        == passed_gate
        and event.get("source_checkpoint_binding", {}).get(
            "preflight_attempt_004"
        )
        == HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        and event.get("contract_supersession", {}).get(
            "replacement_contract_binding"
        )
        == r006_contract_binding(root)
        and event.get("start_gate_runner_binding") == r006_runner_binding(root),
        "seq94 source authority differs",
    )
    require(
        state.get("goal_status") == "READY"
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values()
        and source.get("current_work", {}).get("status") == "READY"
        and source.get("approved_state", {}).get("formal_test_not_run_count") == 279
        and source.get("verification_boundary", {}).get("release_eligible") is False,
        "seq94 source gained formal, implementation, or release credit",
    )


def load_exact_seq94_source(
    root: Path = ROOT,
) -> tuple[bytes, dict[str, Any]]:
    """Load exact seq94 bytes from the pre- or post-seq95 stage."""

    root = seq90._safe_root(root)
    read = seq90._stable_read(root, CHECKPOINT_REL)
    live = seq90.strict_json(read.raw, CHECKPOINT_REL.as_posix())
    history = live.get("goal_execution", {}).get("transition_history")
    if isinstance(history, list) and len(history) == SOURCE_SEQUENCE:
        require_exact_seq94_source(read.raw, live, root)
        return read.raw, live
    if isinstance(history, list) and len(history) == CORRECTION_SEQUENCE:
        raw = reconstructed_seq94_checkpoint_bytes(root, live)
    elif isinstance(history, list) and len(history) == STARTED_SEQUENCE:
        started = importlib.import_module(
            "scripts.apply_walksafe_fp048_r002_goal_started_seq96_20260826"
        )
        seq95_raw = started.reconstructed_seq95_checkpoint_bytes(root, live)
        seq95 = seq90.strict_json(seq95_raw, "reconstructed seq95 correction")
        raw = reconstructed_seq94_checkpoint_bytes(root, seq95)
    else:
        raise ContractCorrectionError("live checkpoint is not a supported seq94-96 stage")
    source = seq90.strict_json(raw, "stage-aware exact seq94 source")
    require_exact_seq94_source(raw, source, root)
    return raw, source


def build_authorization(
    root: Path,
    source: Mapping[str, Any],
    *,
    require_live_preflight_absence: bool = True,
) -> bytes:
    root = seq90._safe_root(root)
    source_raw = checkpoint_json_bytes(source)
    require_exact_seq94_source(source_raw, source, root)
    passed_gate = passed_gate_attempt_003_binding(root)
    r006_preflight = (
        r006_preflight_attempt_004_observation(root)
        if require_live_preflight_absence
        else _stored_r006_preflight_attempt_004()
    )
    value = {
        "authorization_scope": {
            "authorized_actions": [
                "SEQ95_ZERO_CREDIT_R006_TO_R007_CONTRACT_CORRECTION",
                "R007_PRIVATE_INITIAL_START_GATE_AFTER_SEQ95",
                "SEQ96_GOAL_STARTED_AFTER_FRESH_R007_FIVE_CHECK_PASS",
            ],
            "conditions": [
                "EXACT_PUBLISHED_SEQ94_SOURCE_CAS_REQUIRED",
                "FROZEN_SEQ94_R002_AUTHORIZATION_AND_REVIEW_MUST_REMAIN_EXACT",
                "R004_PASS_003_MUST_REMAIN_PASS_UNCONSUMED",
                "R005_PREFLIGHT_004_HISTORICAL_METADATA_MUST_REMAIN_NONAUTHORITY",
                (
                    "R006_PREFLIGHT_004_NAMESPACE_AND_RECEIPT_MUST_REMAIN_"
                    "ABSENT_THROUGH_SEQ95_PUBLICATION_AND_R007_PRE_GATE"
                ),
                (
                    "FRESH_R007_MAY_CREATE_THE_SAME_004_NAMESPACE_AND_RECEIPT_"
                    "FOR_SEQ96_CONSUMPTION"
                ),
                "SEQ95_MUST_PRESERVE_READY_STATUS_AND_ZERO_CREDIT",
                "LIVE_PHYSICAL_GIT_BRANCH_MUST_MATCH_SEQ94_SEAL",
                "SEQ96_REQUIRES_A_FRESH_R007_FIVE_CHECK_PASS_RECEIPT",
            ],
            "excluded_actions": [
                "TREAT_R006_PREFLIGHT_004_AS_GATE_EVIDENCE",
                "BURN_REUSABLE_004_EVENT_ID_WITHOUT_A_NAMESPACE",
                "FORMAL_OR_ACTUAL_DEVICE_TEST_CREDIT",
                "EXTERNAL_REVIEW_OR_DEPLOYMENT_CREDIT",
                "RELEASE_OR_COMPLETION_CREDIT",
            ],
        },
        "authorization_status": (
            "AUTHORIZED_FOR_CONDITIONAL_SEQ95_96_CONTRACT_CORRECTION_CONTINUATION"
        ),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "contract_correction_event_id": CORRECTION_EVENT_ID,
        "correction_reason": copy.deepcopy(CORRECTION_REASON),
        "document_id": "WS-FP048-R002-SEQ95-96-AUTHORIZATION-20260826-001",
        "evidence_type": "USER_CONTINUATION_AUTHORIZATION",
        "frozen_seq94_r002_authorization_binding": copy.deepcopy(
            FROZEN_SEQ94_R002_AUTHORIZATION_BINDING
        ),
        "frozen_seq94_r002_review_binding": frozen_seq94_r002_review_binding(
            root, source["goal_execution"]["transition_history"][-1]
        ),
        "goal_id": GOAL_ID,
        "historical_r005_preflight_attempt_004": copy.deepcopy(
            HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        ),
        "passed_gate_attempt_003_binding": passed_gate,
        "previous_contract_binding": r006_contract_binding(root),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "r006_preflight_attempt_004": r006_preflight,
        "replacement_contract_binding": r007_contract_binding(root),
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(
            passed_gate, r006_preflight
        ),
        "target_started_event_id": STARTED_EVENT_ID,
    }
    return seq90.canonical_json_bytes(value)


def candidate_authorization_binding(
    root: Path,
    source: Mapping[str, Any],
    *,
    require_live_preflight_absence: bool = True,
) -> dict[str, Any]:
    observed = _binding(
        AUTHORIZATION_REL,
        build_authorization(
            root,
            source,
            require_live_preflight_absence=require_live_preflight_absence,
        ),
    )
    require(
        observed == FROZEN_AUTHORIZATION_BINDING,
        "seq95 candidate authorization must reuse frozen bytes",
    )
    return observed


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
        "seq95 authorization differs",
    )
    return _binding(AUTHORIZATION_REL, raw)


def _reviewed_control_bindings(root: Path) -> list[dict[str, Any]]:
    observed = [
        _binding(path, seq90._stable_read(root, path).raw)
        for path in REVIEWED_CONTROL_PATHS
    ]
    by_path = {row["path"]: row for row in observed}
    require(
        all(
            by_path.get(path.as_posix()) == expected
            for path, expected in FIXED_REVIEWED_CONTROL_BINDINGS.items()
        ),
        "fixed seq95 review cohort binding differs",
    )
    return observed


def build_review_assignment(
    root: Path,
    source: Mapping[str, Any],
    *,
    require_live_preflight_absence: bool = True,
) -> bytes:
    root = seq90._safe_root(root)
    source_raw = checkpoint_json_bytes(source)
    require_exact_seq94_source(source_raw, source, root)
    passed_gate = passed_gate_attempt_003_binding(root)
    r006_preflight = (
        r006_preflight_attempt_004_observation(root)
        if require_live_preflight_absence
        else _stored_r006_preflight_attempt_004()
    )
    frozen_review = frozen_seq94_r002_review_binding(
        root, source["goal_execution"]["transition_history"][-1]
    )
    superseded_review = frozen_r001_review_binding(root)
    failed_preflight = (
        _stage_aware_r001_projected_preflight_failure(root)
        if require_live_preflight_absence
        else copy.deepcopy(R001_PROJECTED_PREFLIGHT_FAILURE)
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
        "document_id": "WS-FP048-R002-SEQ95-96-REVIEW-ASSIGNMENT-20260826-R002",
        "executor": {
            "id": "codex-fp048-r002-seq95-contract-correction-executor-20260826",
            "task_id": "/root/seq94_contract_correction_impl",
        },
        "frozen_seq94_r002_authorization_binding": copy.deepcopy(
            FROZEN_SEQ94_R002_AUTHORIZATION_BINDING
        ),
        "frozen_seq94_r002_review_binding": frozen_review,
        "frozen_seq94_r002_reviewed_control_inputs": [
            {"path": path, "sha256": digest, "byte_length": length}
            for path, digest, length in FROZEN_SEQ94_R002_REVIEWED_INPUTS
        ],
        "goal_id": GOAL_ID,
        "historical_r005_preflight_attempt_004": copy.deepcopy(
            HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        ),
        "passed_gate_attempt_003_binding": passed_gate,
        "previous_contract_binding": r006_contract_binding(root),
        "previous_runner_binding": r006_runner_binding(root),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "r001_projected_preflight_failure": failed_preflight,
        "r006_preflight_attempt_004": r006_preflight,
        "replacement_contract_binding": r007_contract_binding(root),
        "replacement_runner_binding": r007_runner_binding(root),
        "required_reviewer": copy.deepcopy(REVIEWER),
        "review_scope": [
            (
                "immutable R001 review triad and the projected preflight failure "
                "that supersedes it"
            ),
            "exact published seq94 source CAS and event lineage",
            "frozen seq94 R002 authorization, review triad, and thirteen inputs",
            "exact R004 PASS -003 six-file private PASS_UNCONSUMED inventory",
            "R005 historical preflight -004 metadata remains permanent nonauthority",
            (
                "R006 preflight -004 failed before namespace with exact malformed "
                "checkpoint evidence error and remains nonauthority"
            ),
            (
                "physical -004 absence applies through seq95 publication and R007 "
                "pre-gate only; exact fresh R007 PASS may create and seq96 consume it"
            ),
            "exact R006 contract and runner remain preserved",
            "R006-to-R007 append-only contract supersession",
            "all modified and new stage-aware R007 control inputs",
            "seq95 READY-to-READY transition with every credit delta zero",
            "exact seq95-to-seq94 byte reconstruction",
            "physical Git branch, managed cohort, and Git-visible cohort reseal",
            "atomic replacement and private 0600 checkpoint mode",
        ],
        "reviewed_control_inputs": _reviewed_control_bindings(root),
        "round_id": ROUND_ID,
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(
            passed_gate, r006_preflight
        ),
        "superseded_review_binding": superseded_review,
        "supersedes_round_id": SUPERSEDES_ROUND_ID,
        "supersession_reason": SUPERSESSION_REASON,
    }
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
        "seq95 review assignment differs",
    )
    assignment = seq90.strict_json(assignment_raw, REVIEW_ASSIGNMENT_REL.as_posix())
    result = seq90.strict_json(result_raw, REVIEW_RESULT_REL.as_posix())
    independent = seq90.strict_json(independent_raw, INDEPENDENT_REVIEW_REL.as_posix())
    require(
        set(result) == REVIEW_RESULT_FIELDS
        and set(independent) == INDEPENDENT_REVIEW_FIELDS
        and result_raw == seq90.canonical_json_bytes(result)
        and independent_raw == seq90.canonical_json_bytes(independent),
        "seq95 review documents are noncanonical",
    )
    assignment_binding_value = _binding(REVIEW_ASSIGNMENT_REL, assignment_raw)
    result_binding_value = _binding(REVIEW_RESULT_REL, result_raw)
    passed_gate = passed_gate_attempt_003_binding(root)
    r006_preflight = (
        r006_preflight_attempt_004_observation(root)
        if require_live_preflight_absence
        else _stored_r006_preflight_attempt_004()
    )
    frozen_review = frozen_seq94_r002_review_binding(
        root, source["goal_execution"]["transition_history"][-1]
    )
    superseded_review = frozen_r001_review_binding(root)
    failed_preflight = (
        _stage_aware_r001_projected_preflight_failure(root)
        if require_live_preflight_absence
        else copy.deepcopy(R001_PROJECTED_PREFLIGHT_FAILURE)
    )
    common = {
        "claim_boundary": CLAIM_BOUNDARY,
        "correction_reason": CORRECTION_REASON,
        "frozen_seq94_r002_review_binding": frozen_review,
        "goal_id": GOAL_ID,
        "historical_r005_preflight_attempt_004": (
            HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        ),
        "passed_gate_attempt_003_binding": passed_gate,
        "previous_contract_binding": r006_contract_binding(root),
        "projected_transition": PROJECTED_TRANSITION,
        "r001_projected_preflight_failure": failed_preflight,
        "r006_preflight_attempt_004": r006_preflight,
        "replacement_contract_binding": r007_contract_binding(root),
        "reviewer": REVIEWER,
        "round_id": ROUND_ID,
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(
            passed_gate, r006_preflight
        ),
        "superseded_review_binding": superseded_review,
        "supersedes_round_id": SUPERSEDES_ROUND_ID,
        "supersession_reason": SUPERSESSION_REASON,
    }
    require(
        assignment.get("authorization_binding") == expected_authorization
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ95-96-REVIEW-ASSIGNMENT-20260826-R002"
        and assignment.get("round_id") == ROUND_ID
        and assignment.get("required_reviewer") == REVIEWER
        and result.get("document_id")
        == "WS-FP048-R002-SEQ95-96-REVIEW-RESULT-20260826-R002"
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ95-96-INDEPENDENT-REVIEW-20260826-R002"
        and result.get("assignment_binding") == assignment_binding_value
        and independent.get("assignment_binding") == assignment_binding_value
        and independent.get("review_result_binding") == result_binding_value
        and result.get("decision")
        == "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and independent.get("decision")
        == "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and result.get("findings") == []
        and independent.get("findings") == []
        and result.get("external_independence_claimed") is False
        and independent.get("external_independence_claimed") is False
        and independent.get("independent_checks") == INDEPENDENT_CHECKS
        and all(_strict_json_equal(result.get(key), value) for key, value in common.items())
        and all(
            _strict_json_equal(independent.get(key), value)
            for key, value in common.items()
        ),
        "seq95 review approval differs",
    )
    result_at = _parse_time(result.get("reviewed_at"), "seq95 result reviewed_at")
    independent_at = _parse_time(
        independent.get("reviewed_at"), "seq95 independent reviewed_at"
    )
    source_at = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq94 occurred_at",
    )
    require(
        result_at
        > _parse_time(
            R001_INDEPENDENT_REVIEWED_AT,
            "seq95 R001 independent reviewed_at",
        )
        and result_at > source_at
        and independent_at > result_at,
        "seq95 review time order differs",
    )
    return (
        {
            "assignment": assignment_binding_value,
            "review_result": result_binding_value,
            "independent_review": _binding(INDEPENDENT_REVIEW_REL, independent_raw),
        },
        result_at,
        independent_at,
    )


def _event_time(
    source: Mapping[str, Any],
    supplied: str | None,
    independent_reviewed_at: datetime,
) -> str:
    candidate = (
        _parse_time(supplied, "seq95 occurred_at")
        if supplied is not None
        else datetime.now().astimezone().replace(microsecond=0)
    )
    source_time = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq94 occurred_at",
    )
    return max(
        candidate,
        source_time + timedelta(seconds=1),
        independent_reviewed_at + timedelta(seconds=1),
    ).isoformat()


def _final_managed_paths(
    source: Mapping[str, Any], visible: Sequence[str]
) -> tuple[str, ...]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    paths.update(
        path
        for path in visible
        if not path.startswith("docs/control/execution/goal-gates/")
    )
    paths.discard(CHECKPOINT_REL.as_posix())
    paths.update(path.as_posix() for path in REVIEWED_CONTROL_PATHS)
    paths.add(AUTHORIZATION_REL.as_posix())
    paths.update(path.as_posix() for path in REVIEW_PATHS)
    return tuple(sorted(paths))


def project_seq95(
    root: Path,
    source: Mapping[str, Any],
    *,
    managed_paths: Sequence[str],
    path_set_sha256: str,
    content_set_sha256: str,
    occurred_at: str,
    authorization_binding_value: Mapping[str, Any],
    review_binding: Mapping[str, Any],
    passed_gate_binding: Mapping[str, Any],
    r006_preflight_binding: Mapping[str, Any],
    replacement_contract_binding: Mapping[str, Any],
    replacement_runner_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    root = seq90._safe_root(root)
    source_raw = checkpoint_json_bytes(source)
    require_exact_seq94_source(source_raw, source, root)
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
        "seq95 managed paths differ",
    )
    require(
        _strict_json_equal(
            dict(passed_gate_binding), _stored_passed_gate_attempt_003_binding()
        ),
        "seq95 PASS_UNCONSUMED -003 binding differs",
    )
    require(
        _strict_json_equal(
            dict(r006_preflight_binding), _stored_r006_preflight_attempt_004()
        ),
        "seq95 R006 preflight -004 observation differs",
    )
    require(
        _strict_json_equal(
            dict(replacement_contract_binding), r007_contract_binding(root)
        ),
        "seq95 R007 contract binding differs",
    )
    require(
        _strict_json_equal(
            dict(replacement_runner_binding), r007_runner_binding(root)
        ),
        "seq95 R007 runner binding differs",
    )
    require(
        _strict_json_equal(
            source_event.get("contract_supersession", {}).get(
                "replacement_contract_binding"
            ),
            r006_contract_binding(root),
        )
        and _strict_json_equal(
            source_event.get("start_gate_runner_binding"),
            r006_runner_binding(root),
        ),
        "seq95 R006 predecessor authority differs",
    )
    _parse_time(occurred_at, "seq95 occurred_at")

    projected = copy.deepcopy(source)
    current = projected["current_work"]
    current["status"] = "READY"
    current["current_focus"] = CURRENT_FOCUS
    current["next_action"] = NEXT_ACTION
    current["release_completion_claimed"] = False
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
    mirror["file_count"] = len(paths)
    mirror["path_set_sha256"] = path_set_sha256
    mirror["content_set_sha256"] = content_set_sha256
    handoff["current_epic"] = HANDOFF_EPIC
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    handoff["last_verification_status"] = VERIFICATION_STATUS
    handoff["next_single_action"] = NEXT_ACTION

    source_binding = _source_checkpoint_binding(
        passed_gate_binding, r006_preflight_binding
    )
    event: dict[str, Any] = {
        "sequence": CORRECTION_SEQUENCE,
        "event_id": CORRECTION_EVENT_ID,
        "event_type": CORRECTION_EVENT_TYPE,
        "occurred_on": _parse_time(
            occurred_at, "seq95 occurred_at"
        ).date().isoformat(),
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
            "FP048-R002_EXACT_PUBLISHED_SEQ94_SOURCE",
            "FP048-R002_SEQ94_R002_FROZEN_REVIEW_AUTHORITY",
            "FP048-R002_R004_PASS_003_REMAINS_PASS_UNCONSUMED",
            "FP048-R002_R005_PREFLIGHT_004_HISTORICAL_NONAUTHORITY",
            "FP048-R002_R006_PREFLIGHT_004_NO_NAMESPACE_NONAUTHORITY",
            "FP048-R002_R007_STAGE_AWARE_GATE_CONTRACT",
            "FP048-R002_SEQ95_96_TRANSITION_CONTROL_REVIEW",
        ],
        "source_checkpoint_binding": source_binding,
        "source_ready_event_binding": copy.deepcopy(
            source_event["source_ready_event_binding"]
        ),
        "authorization_binding": copy.deepcopy(dict(authorization_binding_value)),
        "contract_supersession": {
            "previous_contract_binding": r006_contract_binding(root),
            "reason_code": R007_SUCCESSOR_REASON_CODE,
            "replacement_contract_binding": copy.deepcopy(
                dict(replacement_contract_binding)
            ),
        },
        "start_gate_runner_binding": copy.deepcopy(
            dict(replacement_runner_binding)
        ),
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
                "current_head": source["session_handoff"]
                ["source_commit_or_snapshot"]["current_head"],
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
            seq94.seq93.seq92.correction._unchanged_projection(projected)
        ),
        "canonical_binding_snapshot_after": copy.deepcopy(
            source_event["canonical_binding_snapshot_after"]
        ),
        "correction_reason": copy.deepcopy(CORRECTION_REASON),
        "previous_event_sha256": SOURCE_EVENT_SHA256,
    }
    event["event_sha256"] = continuation.event_sha256(event)
    require(set(event) == EVENT_FIELDS, "seq95 correction event field set differs")
    state = projected["goal_execution"]
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = occurred_at
    require(
        state["goal_status"] == "READY"
        and state["status_by_goal"][GOAL_ID] == "READY"
        and current["status"] == "READY"
        and "IN_PROGRESS" not in state["status_by_goal"].values(),
        "seq95 READY state differs",
    )
    for field in (
        "approved_state",
        "authority_boundary",
        "canonical_bindings",
        "verification_boundary",
    ):
        require(
            _strict_json_equal(projected[field], source[field]),
            f"seq95 changed {field}",
        )
    require(
        all(
            type(value) is int and value == 0
            for key, value in CLAIM_BOUNDARY.items()
            if key.endswith("_credit_delta") or key == "goal_status_change_count"
        )
        and CLAIM_BOUNDARY["implementation_start_authorized"] is False,
        "seq95 credit boundary differs",
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
        == set(source_binding)
        | {"current_work", "working_tree_snapshot", "session_handoff"}
        and {key: before.get(key) for key in source_binding} == source_binding
        and isinstance(before.get("current_work"), dict)
        and isinstance(before.get("working_tree_snapshot"), dict)
        and isinstance(before.get("session_handoff"), dict)
        and isinstance(after, dict)
        and set(after)
        == {
            "base_commit",
            "branch",
            "logical_branch",
            "logical_branch_semantics",
            "physical_git_branch",
            "branch_mismatch_reason_code",
            "current_head",
            "managed_changed_path_count",
            "path_set_sha256",
            "content_set_sha256",
        }
        and after.get("branch") == after.get("logical_branch") == LOGICAL_BRANCH
        and after.get("logical_branch_semantics") == LOGICAL_BRANCH_SEMANTICS
        and after.get("physical_git_branch") == PHYSICAL_GIT_BRANCH
        and after.get("branch_mismatch_reason_code")
        == BRANCH_MISMATCH_REASON_CODE,
        "seq95 repository context differs",
    )
    return before


def _restored_seq94_checkpoint(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "seq94 inverse source is not exact seq95",
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
        "checkpoint is not exact seq95 contract correction",
    )
    source_event = history[SOURCE_SEQUENCE - 1]
    event = history[CORRECTION_SEQUENCE - 1]
    passed_gate = passed_gate_attempt_003_binding(root)
    r006_preflight = (
        r006_preflight_attempt_004_observation(root)
        if require_live_snapshot
        else _stored_r006_preflight_attempt_004()
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
        and event.get("subject_goal_id") == GOAL_ID
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("previous_event_sha256") == SOURCE_EVENT_SHA256
        and event.get("event_sha256") == continuation.event_sha256(event)
        and event.get("source_checkpoint_binding")
        == _source_checkpoint_binding(passed_gate, r006_preflight)
        and event.get("correction_reason") == CORRECTION_REASON
        and event.get("claim_boundary") == CLAIM_BOUNDARY,
        "seq95 correction authority differs",
    )
    before = _repository_before(event)
    source_raw = checkpoint_json_bytes(_restored_seq94_checkpoint(checkpoint))
    source = seq90.strict_json(source_raw, "restored seq94 source")
    require_exact_seq94_source(source_raw, source, root)
    review_binding, result_at, independent_at = _load_physical_review(
        root,
        source,
        require_live_preflight_absence=require_live_snapshot,
    )
    event_at = _parse_time(event.get("occurred_at"), "seq95 occurred_at")
    source_at = _parse_time(source_event.get("occurred_at"), "seq94 occurred_at")
    require(
        source_at < result_at < independent_at < event_at,
        "seq95 event and physical review chronology differs",
    )
    snapshot = checkpoint.get("working_tree_snapshot")
    paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
    require(isinstance(paths, list), "seq95 managed paths differ")
    expected_checkpoint, expected_event = project_seq95(
        root,
        source,
        managed_paths=paths,
        path_set_sha256=snapshot.get("path_set_sha256"),
        content_set_sha256=snapshot.get("content_set_sha256"),
        occurred_at=event.get("occurred_at"),
        authorization_binding_value=authorization_binding(
            root,
            source,
            require_live_preflight_absence=require_live_snapshot,
        ),
        review_binding=review_binding,
        passed_gate_binding=passed_gate,
        r006_preflight_binding=r006_preflight,
        replacement_contract_binding=r007_contract_binding(root),
        replacement_runner_binding=r007_runner_binding(root),
    )
    require(
        _strict_json_equal(event, expected_event)
        and _strict_json_equal(checkpoint, expected_checkpoint),
        "seq95 exact producer projection differs",
    )
    require(
        event.get("contract_supersession")
        == {
            "previous_contract_binding": r006_contract_binding(root),
            "reason_code": R007_SUCCESSOR_REASON_CODE,
            "replacement_contract_binding": r007_contract_binding(root),
        }
        and event.get("start_gate_runner_binding") == r007_runner_binding(root)
        and event.get("runtime_after") == source_event.get("runtime_after")
        and event.get("blockers_after") == source_event.get("blockers_after")
        and event.get("blocker_resolution_ids_after")
        == source_event.get("blocker_resolution_ids_after")
        and event.get("noncredit_successor_edges")
        == source_event.get("noncredit_successor_edges"),
        "seq95 successor authority differs",
    )
    require(
        all(
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
        "seq1-95 lineage differs",
    )
    current = checkpoint.get("current_work")
    handoff = checkpoint.get("session_handoff")
    mirror = handoff.get("source_commit_or_snapshot") if isinstance(handoff, dict) else None
    after = event["repository_context_reanchor"]["after"]
    require(
        state.get("goal_status") == "READY"
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values()
        and state.get("transition_history_anchor_sha256") == event["event_sha256"]
        and state.get("validation_cutoff_at") == event["occurred_at"]
        and isinstance(current, dict)
        and current.get("status") == "READY"
        and current.get("current_focus") == CURRENT_FOCUS
        and current.get("next_action") == NEXT_ACTION
        and current.get("release_completion_claimed") is False
        and isinstance(snapshot, dict)
        and paths == sorted(set(paths))
        and snapshot.get("scope") == SCOPE
        and snapshot.get("managed_changed_path_count") == len(paths)
        and snapshot.get("managed_changed_path_count")
        == after.get("managed_changed_path_count")
        and snapshot.get("path_set_sha256") == after.get("path_set_sha256")
        and snapshot.get("content_set_sha256") == after.get("content_set_sha256")
        and isinstance(handoff, dict)
        and handoff.get("branch") == LOGICAL_BRANCH
        and handoff.get("changed_files") == paths
        and handoff.get("current_epic") == HANDOFF_EPIC
        and handoff.get("last_updated_by_work_item") == WORK_ITEM_ID
        and handoff.get("last_verification_status") == VERIFICATION_STATUS
        and handoff.get("next_single_action") == NEXT_ACTION
        and isinstance(mirror, dict)
        and mirror.get("file_count") == len(paths)
        and mirror.get("path_set_sha256") == snapshot.get("path_set_sha256")
        and mirror.get("content_set_sha256") == snapshot.get("content_set_sha256"),
        "seq95 READY projection differs",
    )
    for field in (
        "approved_state",
        "authority_boundary",
        "canonical_bindings",
        "verification_boundary",
    ):
        require(
            _strict_json_equal(checkpoint.get(field), source.get(field)),
            f"seq95 changed {field}",
        )
    require(
        checkpoint.get("approved_state", {}).get("formal_test_not_run_count") == 279
        and checkpoint.get("verification_boundary", {}).get("release_eligible")
        is False
        and before["current_work"]["status"] == "READY",
        "seq95 gained formal, implementation, or release credit",
    )
    if require_live_snapshot:
        require(
            continuation.working_snapshot_hashes(root, paths)
            == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
            "seq95 managed snapshot differs",
        )
    if run_external_validators:
        seq90._validate_projected_with_consumers(root, checkpoint)


def reconstructed_seq94_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    if isinstance(history, list) and len(history) == SOURCE_SEQUENCE:
        raw = checkpoint_json_bytes(checkpoint)
        require_exact_seq94_source(raw, checkpoint, root)
        return raw
    require_contract_corrected_checkpoint(
        root, checkpoint, require_live_snapshot=False
    )
    raw = checkpoint_json_bytes(_restored_seq94_checkpoint(checkpoint))
    require(
        len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256,
        "seq95 inverse does not reconstruct exact seq94",
    )
    return raw


def _require_frozen_seq94_checkpoint(
    raw: bytes,
    checkpoint: Mapping[str, Any],
) -> None:
    """Validate the published seq94 CAS without reopening historical evidence."""

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
        "frozen seq94 checkpoint differs",
    )
    event = history[-1]
    require(
        event.get("event_id") == SOURCE_EVENT_ID
        and event.get("event_sha256") == SOURCE_EVENT_SHA256
        and event.get("event_type") == seq94.CORRECTION_EVENT_TYPE
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("authorization_binding")
        == FROZEN_SEQ94_R002_AUTHORIZATION_BINDING
        and event.get("transition_control_review_binding")
        == FROZEN_SEQ94_R002_REVIEW_BINDINGS
        and event.get("source_checkpoint_binding", {}).get(
            "preflight_attempt_004"
        )
        == HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        and event.get("contract_supersession", {}).get(
            "replacement_contract_binding"
        )
        == R006_CONTRACT_BINDING
        and event.get("start_gate_runner_binding") == R006_RUNNER_BINDING
        and state.get("transition_history_anchor_sha256") == SOURCE_EVENT_SHA256
        and state.get("goal_status") == "READY"
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values()
        and checkpoint.get("current_work", {}).get("status") == "READY"
        and checkpoint.get("approved_state", {}).get("formal_test_not_run_count")
        == 279
        and checkpoint.get("verification_boundary", {}).get("release_eligible")
        is False,
        "frozen seq94 authority differs",
    )


def require_frozen_seq94_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> None:
    """Validate the exact published seq94 CAS without physical evidence replay."""

    seq90._safe_root(root)
    raw = checkpoint_json_bytes(checkpoint)
    _require_frozen_seq94_checkpoint(raw, checkpoint)


def canonical_frozen_seq94_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    """Return exact seq94 bytes after successor-owned frozen-CAS validation."""

    require_frozen_seq94_checkpoint(root, checkpoint)
    return checkpoint_json_bytes(checkpoint)


def _require_frozen_seq93_checkpoint(
    raw: bytes,
    checkpoint: Mapping[str, Any],
) -> None:
    """Validate the frozen seq93 CAS without replaying a later review cohort."""

    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        len(raw) == seq94.SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(raw) == seq94.SOURCE_CHECKPOINT_SHA256
        and raw == checkpoint_json_bytes(checkpoint)
        and isinstance(history, list)
        and len(history) == seq94.SOURCE_SEQUENCE
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
        "frozen seq93 checkpoint differs",
    )
    event = history[-1]
    require(
        event.get("event_id") == seq94.SOURCE_EVENT_ID
        and event.get("event_sha256") == seq94.SOURCE_EVENT_SHA256
        and event.get("event_type") == seq94.seq93.REANCHOR_EVENT_TYPE
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("authorization_binding")
        == seq94.FROZEN_SEQ93_AUTHORIZATION_BINDING
        and event.get("transition_control_review_binding")
        == seq94.FROZEN_SEQ93_R003_REVIEW_BINDINGS
        and state.get("transition_history_anchor_sha256")
        == seq94.SOURCE_EVENT_SHA256
        and state.get("goal_status") == "READY"
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values()
        and checkpoint.get("current_work", {}).get("status") == "READY"
        and checkpoint.get("approved_state", {}).get("formal_test_not_run_count")
        == 279
        and checkpoint.get("verification_boundary", {}).get("release_eligible")
        is False,
        "frozen seq93 authority differs",
    )


def _restored_seq93_checkpoint_from_seq94(
    checkpoint: Mapping[str, Any],
) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == SOURCE_SEQUENCE,
        "seq93 inverse source is not exact seq94",
    )
    event = history[-1]
    context = event.get("repository_context_reanchor")
    before = context.get("before") if isinstance(context, dict) else None
    require(
        isinstance(before, dict)
        and isinstance(before.get("current_work"), dict)
        and isinstance(before.get("working_tree_snapshot"), dict)
        and isinstance(before.get("session_handoff"), dict),
        "seq94 repository before-state differs",
    )
    restored = copy.deepcopy(checkpoint)
    restored_state = restored["goal_execution"]
    source_event = restored_state["transition_history"][seq94.SOURCE_SEQUENCE - 1]
    restored_state["transition_history"] = restored_state["transition_history"][
        : seq94.SOURCE_SEQUENCE
    ]
    restored_state["transition_history_anchor_sha256"] = source_event[
        "event_sha256"
    ]
    restored_state["validation_cutoff_at"] = source_event["occurred_at"]
    restored["current_work"] = copy.deepcopy(before["current_work"])
    restored["working_tree_snapshot"] = copy.deepcopy(
        before["working_tree_snapshot"]
    )
    restored["session_handoff"] = copy.deepcopy(before["session_handoff"])
    return restored


def _restored_seq95_checkpoint_from_seq96(
    checkpoint: Mapping[str, Any],
) -> dict[str, Any]:
    restored = copy.deepcopy(checkpoint)
    state = restored.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == STARTED_SEQUENCE,
        "seq95 inverse source is not exact seq96",
    )
    source_event = history[CORRECTION_SEQUENCE - 1]
    event = history[STARTED_SEQUENCE - 1]
    require(
        isinstance(source_event, dict)
        and type(source_event.get("sequence")) is int
        and source_event.get("sequence") == CORRECTION_SEQUENCE
        and source_event.get("event_id") == CORRECTION_EVENT_ID
        and source_event.get("event_type") == CORRECTION_EVENT_TYPE
        and source_event.get("event_sha256")
        == continuation.event_sha256(source_event)
        and isinstance(event, dict)
        and type(event.get("sequence")) is int
        and event.get("sequence") == STARTED_SEQUENCE
        and event.get("event_id") == STARTED_EVENT_ID
        and event.get("event_type") == "GOAL_STARTED"
        and event.get("previous_event_sha256") == source_event.get("event_sha256")
        and event.get("event_sha256") == continuation.event_sha256(event),
        "seq96 descendant authority differs",
    )
    state["transition_history"] = history[:CORRECTION_SEQUENCE]
    state["transition_history_anchor_sha256"] = source_event["event_sha256"]
    state["validation_cutoff_at"] = source_event["occurred_at"]
    state["status_by_goal"][GOAL_ID] = "READY"
    state["goal_status"] = "READY"

    current = restored["current_work"]
    current["status"] = "READY"
    current["current_focus"] = CURRENT_FOCUS
    current["next_action"] = NEXT_ACTION
    current["release_completion_claimed"] = False

    snapshot = restored["working_tree_snapshot"]
    snapshot["scope"] = SCOPE
    repository_context = source_event.get("repository_context_reanchor")
    repository_after = (
        repository_context.get("after")
        if isinstance(repository_context, dict)
        else None
    )
    require(isinstance(repository_after, dict), "seq95 repository seal differs")
    for field in (
        "scope",
        "managed_changed_paths",
        "managed_changed_path_count",
        "path_set_sha256",
        "content_set_sha256",
    ):
        if field in repository_after:
            snapshot[field] = copy.deepcopy(repository_after[field])

    handoff = restored["session_handoff"]
    handoff["current_epic"] = HANDOFF_EPIC
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    handoff["last_verification_status"] = VERIFICATION_STATUS
    handoff["next_single_action"] = NEXT_ACTION
    paths = repository_after.get("managed_changed_paths")
    if isinstance(paths, list):
        handoff["changed_files"] = copy.deepcopy(paths)
    mirror = handoff.get("source_commit_or_snapshot")
    require(isinstance(mirror, dict), "seq95 handoff mirror differs")
    mirror["file_count"] = repository_after.get("managed_changed_path_count")
    mirror["path_set_sha256"] = repository_after.get("path_set_sha256")
    mirror["content_set_sha256"] = repository_after.get("content_set_sha256")
    return restored


def _require_embedded_seq95_checkpoint(checkpoint: Mapping[str, Any]) -> None:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "embedded seq95 descendant differs",
    )
    source_event = history[SOURCE_SEQUENCE - 1]
    event = history[CORRECTION_SEQUENCE - 1]
    require(
        isinstance(source_event, dict)
        and type(source_event.get("sequence")) is int
        and source_event.get("sequence") == SOURCE_SEQUENCE
        and source_event.get("event_id") == SOURCE_EVENT_ID
        and source_event.get("event_sha256") == SOURCE_EVENT_SHA256
        and isinstance(event, dict)
        and type(event.get("sequence")) is int
        and event.get("sequence") == CORRECTION_SEQUENCE
        and event.get("event_id") == CORRECTION_EVENT_ID
        and event.get("event_type") == CORRECTION_EVENT_TYPE
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("previous_event_sha256") == SOURCE_EVENT_SHA256
        and event.get("event_sha256") == continuation.event_sha256(event)
        and _strict_json_equal(
            event.get("source_checkpoint_binding"), _source_checkpoint_binding()
        )
        and event.get("correction_reason") == CORRECTION_REASON
        and event.get("claim_boundary") == CLAIM_BOUNDARY
        and _strict_json_equal(
            event.get("contract_supersession"),
            {
                "previous_contract_binding": R006_CONTRACT_BINDING,
                "reason_code": R007_SUCCESSOR_REASON_CODE,
                "replacement_contract_binding": R007_CONTRACT_BINDING,
            },
        )
        and _strict_json_equal(
            event.get("start_gate_runner_binding"), R007_RUNNER_BINDING
        ),
        "embedded seq95 correction authority differs",
    )


def reconstructed_seq93_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    """Reconstruct frozen seq93 from any exact seq93-96 descendant stage."""

    seq90._safe_root(root)
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    sequence = len(history) if isinstance(history, list) else -1
    if sequence == seq94.SOURCE_SEQUENCE:
        raw = checkpoint_json_bytes(checkpoint)
        _require_frozen_seq93_checkpoint(raw, checkpoint)
        return raw
    if sequence == SOURCE_SEQUENCE:
        seq94_raw = checkpoint_json_bytes(checkpoint)
        seq94_checkpoint = checkpoint
        _require_frozen_seq94_checkpoint(seq94_raw, seq94_checkpoint)
    elif sequence == CORRECTION_SEQUENCE:
        _require_embedded_seq95_checkpoint(checkpoint)
        seq94_checkpoint = _restored_seq94_checkpoint(checkpoint)
        seq94_raw = checkpoint_json_bytes(seq94_checkpoint)
        _require_frozen_seq94_checkpoint(seq94_raw, seq94_checkpoint)
    elif sequence == STARTED_SEQUENCE:
        seq95 = _restored_seq95_checkpoint_from_seq96(checkpoint)
        _require_embedded_seq95_checkpoint(seq95)
        seq94_checkpoint = _restored_seq94_checkpoint(seq95)
        seq94_raw = checkpoint_json_bytes(seq94_checkpoint)
        _require_frozen_seq94_checkpoint(seq94_raw, seq94_checkpoint)
    else:
        raise ContractCorrectionError(
            "seq93 inverse source is not an exact seq93-96 stage"
        )
    require(
        len(seq94_raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(seq94_raw) == SOURCE_CHECKPOINT_SHA256,
        "seq93 inverse intermediate is not exact seq94",
    )
    raw = checkpoint_json_bytes(
        _restored_seq93_checkpoint_from_seq94(seq94_checkpoint)
    )
    restored = seq90.strict_json(raw, "reconstructed frozen seq93 source")
    _require_frozen_seq93_checkpoint(raw, restored)
    return raw


def canonical_seq95_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    require_contract_corrected_checkpoint(
        root, checkpoint, require_live_snapshot=False
    )
    return checkpoint_json_bytes(checkpoint)


def reconstructed_seq95_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "seq96 inverse is owned by the seq96 starter",
    )
    return canonical_seq95_checkpoint_bytes(root, checkpoint)


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


def _require_prepared_exact(prepared: Prepared) -> None:
    transport = prepared.transport
    _require_private_checkpoint_mode(transport.root, "during seq95 preflight reseal")
    passed_gate_attempt_003_binding(transport.root)
    r006_preflight_attempt_004_observation(transport.root)
    r006_contract_binding(transport.root)
    r006_runner_binding(transport.root)
    r007_contract_binding(transport.root)
    r007_runner_binding(transport.root)
    seq90._require_preflight_cohort_unchanged(
        transport.root,
        source=seq90.ReadResult(transport.source_raw, transport.source_identity),
        retained=transport.retained_inputs,
        managed=transport.managed_inputs,
        git_visible=transport.git_visible_inputs,
        git_status_raw=transport.git_status_raw,
        git_head=transport.git_head,
        git_branch=transport.git_branch,
        phase="seq95 contract-correction commit guard",
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
    candidates: list[bytes] = []
    for index, record in enumerate(records):
        if record.startswith(prefix) and record.endswith(b".json"):
            candidate = b"\0".join(records[:index] + records[index + 1 :])
            if candidate == expected:
                candidates.append(candidate)
    require(len(candidates) == 1, "Git-visible cohort changed at seq95 commit guard")
    return candidates[0]


def _require_commit_exact(prepared: Prepared) -> None:
    transport = prepared.transport
    root = transport.root
    _require_private_checkpoint_mode(root, "at seq95 commit guard")
    passed_gate_attempt_003_binding(root)
    r006_preflight_attempt_004_observation(root)
    r006_contract_binding(root)
    r006_runner_binding(root)
    r007_contract_binding(root)
    r007_runner_binding(root)
    seq90._require_inputs_unchanged(root, transport.retained_inputs)
    seq90._require_managed_inputs_unchanged(root, transport.managed_inputs)
    seq90._require_managed_inputs_unchanged(
        root, transport.git_visible_inputs, label="full Git-visible input"
    )
    seq90._require_git_context(root, transport.git_head, transport.git_branch)
    source = seq90._stable_read(root, CHECKPOINT_REL)
    require(
        source.identity == transport.source_identity
        and source.raw == transport.source_raw,
        "source changed at seq95 commit guard",
    )
    status_raw, _visible = seq90.capture_git_visible_paths(root)
    require(
        _without_seq90_writer_temporary_status(status_raw, transport.git_status_raw)
        == transport.git_status_raw,
        "Git-visible cohort changed at seq95 commit guard",
    )


def prepare(
    root: Path = ROOT,
    *,
    occurred_at: str | None = None,
    validate_consumers: bool = True,
) -> Prepared:
    root = seq90._safe_root(root)
    source_read = seq90._stable_read(root, CHECKPOINT_REL)
    _require_private_checkpoint_mode(root, "before seq95 preflight")
    source = seq90.strict_json(source_read.raw, CHECKPOINT_REL.as_posix())
    require_exact_seq94_source(source_read.raw, source, root)
    passed_gate = passed_gate_attempt_003_binding(root)
    r006_preflight = r006_preflight_attempt_004_observation(root)
    replacement = r007_contract_binding(root)
    replacement_runner = r007_runner_binding(root)
    git_head, git_branch = seq90._capture_git_context(root)
    expected_head = source["session_handoff"]["source_commit_or_snapshot"][
        "current_head"
    ]
    require(git_head == expected_head, "live Git HEAD differs from seq94 source")
    require(
        git_branch == PHYSICAL_GIT_BRANCH,
        "live Git branch differs from exact seq94 physical branch",
    )
    status_raw, visible = seq90.capture_git_visible_paths(root)
    paths = _final_managed_paths(source, visible)
    managed = seq90._capture_managed_inputs(root, paths)
    visible_inputs = seq90._capture_managed_inputs(root, visible)
    path_sha256, content_sha256 = seq90._managed_input_snapshot_hashes(managed)
    frozen_seq94_paths = {
        Path(FROZEN_SEQ94_R002_AUTHORIZATION_BINDING["path"]),
        *(
            Path(binding["path"])
            for binding in FROZEN_SEQ94_R002_REVIEW_BINDINGS.values()
        ),
    }
    retained_paths = (
        set(REVIEWED_CONTROL_PATHS)
        | set(REVIEW_PATHS)
        | {
            Path(binding["path"])
            for binding in FROZEN_R001_REVIEW_BINDINGS.values()
        }
        | frozen_seq94_paths
        | {
            AUTHORIZATION_REL,
            seq94.R006_CONTRACT_REL,
            seq94.R006_RUNNER_REL,
            R007_CONTRACT_REL,
            R007_RUNNER_REL,
        }
    )
    retained_paths.update(
        PASSED_GATE_DIR_REL / name
        for name, _digest, _length in PASSED_GATE_FILE_PINS
    )
    retained = {
        path: seq90._stable_read(root, path)
        for path in sorted(retained_paths, key=lambda item: item.as_posix())
    }
    authorization = authorization_binding(root, source)
    review, _review_result_at, independent_at = _load_physical_review(root, source)
    projected, event = project_seq95(
        root,
        source,
        managed_paths=paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        occurred_at=_event_time(source, occurred_at, independent_at),
        authorization_binding_value=authorization,
        review_binding=review,
        passed_gate_binding=passed_gate,
        r006_preflight_binding=r006_preflight,
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
    seq90.write_checkpoint(
        prepared.transport,
        commit_guard=lambda: _require_commit_exact(prepared),
    )
    try:
        _require_private_checkpoint_mode(
            prepared.transport.root, "after seq95 publication"
        )
    except BaseException as exc:
        raise seq90.PostcommitUncertain(
            "published seq95 checkpoint mode is uncertain"
        ) from exc


def _candidate_requires_live_preflight_absence(root: Path) -> bool:
    root = seq90._safe_root(root)
    read = seq90._stable_read(root, CHECKPOINT_REL)
    checkpoint = seq90.strict_json(read.raw, CHECKPOINT_REL.as_posix())
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(isinstance(history, list) and history, "live checkpoint history differs")
    sequence = history[-1].get("sequence") if isinstance(history[-1], dict) else None
    require(type(sequence) is int and sequence == len(history), "live stage differs")
    # Candidate replay is pre-R007-gate until seq96 owns the exact PASS receipt.
    return sequence < STARTED_SEQUENCE


def prepare_review_manifest(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    _raw, source = load_exact_seq94_source(root)
    require_live_preflight_absence = _candidate_requires_live_preflight_absence(root)
    if require_live_preflight_absence:
        _stage_aware_r001_projected_preflight_failure(root)
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
    r006_preflight = (
        r006_preflight_attempt_004_observation(root)
        if require_live_preflight_absence
        else _stored_r006_preflight_attempt_004()
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
        "source_checkpoint_binding": _source_checkpoint_binding(
            passed_gate_attempt_003_binding(root),
            r006_preflight,
        ),
        "status": "CANDIDATE_ONLY_NOT_PUBLISHED",
        "superseded_review_binding": frozen_r001_review_binding(root),
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
            _raw, source = load_exact_seq94_source(args.root)
            sys.stdout.buffer.write(
                build_authorization(
                    args.root,
                    source,
                    require_live_preflight_absence=(
                        _candidate_requires_live_preflight_absence(args.root)
                    ),
                )
            )
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
        message = (
            "FP048-R002 seq95 gate-contract correction: PASS "
            f"event_sha256={prepared.event['event_sha256']} "
            f"occurred_at={prepared.event['occurred_at']}\n"
        )
        try:
            sys.stdout.write(message)
            sys.stdout.flush()
        except BaseException as exc:
            if published:
                raise seq90.PostcommitUncertain(
                    "published seq95 PASS output delivery is uncertain"
                ) from exc
            raise
    except seq90.PostcommitUncertain as exc:
        print(
            f"FP048-R002 seq95 gate-contract correction: POSTCOMMIT-UNCERTAIN: {exc}",
            file=sys.stderr,
        )
        return 2
    except (
        ContractCorrectionError,
        seq90.ControlReanchorError,
        KeyError,
        OSError,
        TypeError,
        ValueError,
    ) as exc:
        print(
            f"FP048-R002 seq95 gate-contract correction: FAIL: {exc}",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
