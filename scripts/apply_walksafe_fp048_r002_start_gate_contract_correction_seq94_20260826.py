#!/usr/bin/env python3
"""Prepare the reviewed zero-credit FP048 R002 seq94 R005-to-R006 correction."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import os
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import (
    apply_walksafe_fp048_r002_goal_start_branch_semantics_reanchor_seq93_20260826
    as seq93,
)
from scripts import (
    apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826 as seq90,
)
from scripts import check_walksafe_project_continuation_v2_4 as continuation


class ContractCorrectionError(seq90.ControlReanchorError):
    """The seq94 contract correction cannot be proven or published."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractCorrectionError(message)


CHECKPOINT_REL = seq93.CHECKPOINT_REL
checkpoint_json_bytes = seq93.checkpoint_json_bytes
GOAL_ID = seq93.GOAL_ID
GOAL_PATH = seq93.GOAL_PATH
GOAL_SHA256 = seq93.GOAL_SHA256
WORK_ITEM_ID = seq93.WORK_ITEM_ID
MANIFEST_SHA256 = seq93.MANIFEST_SHA256
EVENT_FIELDS = seq93.EVENT_FIELDS
CLAIM_BOUNDARY = copy.deepcopy(seq93.CLAIM_BOUNDARY)

SOURCE_SEQUENCE = 93
SOURCE_EVENT_ID = seq93.REANCHOR_EVENT_ID
SOURCE_EVENT_SHA256 = (
    "d3c34e1bed776676cf340e0f097c90a7d81483bf2db30a344e4e9063cd777a75"
)
SOURCE_CHECKPOINT_SHA256 = (
    "1d1455a1e5a454a84f3e04da17faf9c2804cee64d193db462417b85635edc455"
)
SOURCE_CHECKPOINT_BYTE_LENGTH = 3_438_449

CORRECTION_SEQUENCE = 94
CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-GATE-CONTRACT-CORRECTED-"
    "FP048-R002-20260826-002"
)
CORRECTION_EVENT_TYPE = "GOAL_START_GATE_CONTRACT_CORRECTED"
STARTED_SEQUENCE = 95
# A preflight failure before namespace creation does not burn the event identity.
STARTED_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-004"

PHYSICAL_GIT_BRANCH = seq93.PHYSICAL_GIT_BRANCH
LOGICAL_BRANCH = seq93.LOGICAL_BRANCH
LOGICAL_BRANCH_SEMANTICS = seq93.LOGICAL_BRANCH_SEMANTICS
BRANCH_MISMATCH_REASON_CODE = seq93.BRANCH_MISMATCH_REASON_CODE

PASSED_GATE_EVENT_ID = seq93.UNCONSUMED_GATE_EVENT_ID
PASSED_GATE_DIR_REL = seq93.UNCONSUMED_GATE_DIR_REL
PASSED_GATE_FILE_PINS = seq93.UNCONSUMED_GATE_FILE_PINS

PREFLIGHT_ATTEMPT_EVENT_ID = STARTED_EVENT_ID
PREFLIGHT_ATTEMPT_DIR_REL = (
    Path("docs/control/execution/goal-gates") / PREFLIGHT_ATTEMPT_EVENT_ID
)
PREFLIGHT_ATTEMPT_RECEIPT_REL = (
    PREFLIGHT_ATTEMPT_DIR_REL / "implementation-start-gate-receipt.json"
)
PREFLIGHT_ATTEMPT_REASON_CODE = "R005_PREVIEW_FAILED_NO_NAMESPACE_NONAUTHORITY"
PREFLIGHT_ATTEMPT_STATUS = "PREFLIGHT_FAILED_NO_GATE_NAMESPACE_CREATED"
PREFLIGHT_ATTEMPT_AUTHORITY_STATUS = "NONAUTHORITY"
PREFLIGHT_ATTEMPT_EVENT_IDENTITY_STATUS = "REUSABLE_UNCONSUMED"

FROZEN_SEQ93_R003_REVIEW_BINDINGS = {
    "assignment": {
        "path": seq93.REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": "38399cfa82dd34ac0f7e83b839994d75c8af3ea0eba68af1892bf961c395e8be",
        "byte_length": 12_859,
    },
    "review_result": {
        "path": seq93.REVIEW_RESULT_REL.as_posix(),
        "sha256": "eba2bb918ee0e97eac9f5a62f1d41e80fb6949dfd8ff4ed599d63232392889a7",
        "byte_length": 5_381,
    },
    "independent_review": {
        "path": seq93.INDEPENDENT_REVIEW_REL.as_posix(),
        "sha256": "43281f1d6dcdbae3a047f46b668a965f9757e356b3290f45a47a7716336f385f",
        "byte_length": 6_187,
    },
}
FROZEN_SEQ93_AUTHORIZATION_BINDING = {
    "path": seq93.AUTHORIZATION_REL.as_posix(),
    "sha256": "fa55622718962ecb05c714013a5e90b0b8c0187fc5e93d740fcb37bc81e8724a",
    "byte_length": 9_064,
}
FROZEN_SEQ93_R003_REVIEWED_INPUTS: tuple[tuple[str, str, int], ...] = (
    (
        "scripts/apply_walksafe_fp048_r002_goal_start_branch_semantics_reanchor_seq93_20260826.py",
        "4579e906027f7b9f72b1cffe5fe512ec56753afb963768535015267fc0d04d38",
        71_483,
    ),
    (
        "tests/test_apply_walksafe_fp048_r002_goal_start_branch_semantics_reanchor_seq93_20260826.py",
        "6f19187e66c1221ae17ef3d7caeff288310232ff00714d476e111d147683f89c",
        21_933,
    ),
    (
        "scripts/apply_walksafe_fp048_r002_start_gate_contract_correction_seq92_20260826.py",
        "57a047a2f6cfe8f2d7ab26b561929e1814563e8ad69e57162d0903fd29252c0f",
        59_286,
    ),
    (
        "tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_seq92_20260826.py",
        "aec5080a3b6751a7f697dc98a13fc5d375f717627d113f9907472620a185efc4",
        27_050,
    ),
    (
        "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/initial-start-gate-contract-r005.json",
        "ee38d33edecb44cfd4f0fd4cebc1190db79809c8ad81c60a8d9567792eb27506",
        3_514,
    ),
    (
        "docs/control/execution/workstream-transitions/seq93-94/authorization.json",
        "fa55622718962ecb05c714013a5e90b0b8c0187fc5e93d740fcb37bc81e8724a",
        9_064,
    ),
    (
        "scripts/check_walksafe_project_continuation_v2_4.py",
        "a72d4f1f523dd35230a7caf8d91d9ccf600a50677ef58947da820a0a4af0d584",
        548_430,
    ),
    (
        "tests/test_walksafe_project_continuation_v2_4.py",
        "d7e4cb78f00a76e0ca2792e3b63f8ef18cca5ae3b8635ee69b48f9a701a9c86c",
        279_648,
    ),
    (
        "scripts/check_walksafe_goal_graph_v2_4.py",
        "df83834a0db9655a7b81ba504aae50f0aeedc62b4a1470ad7e99b9dfc4eedf17",
        815_158,
    ),
    (
        "tests/test_walksafe_goal_graph_v2_4.py",
        "ce5e65868ee09b05b88eaccce430af3f401cc19e3594a2e2d00cb4872741c510",
        530_919,
    ),
    (
        "scripts/run_walksafe_test_layers_current.sh",
        "9b9247860621aecd7d31efbc0420630ccd1ca5fefc10656e4b5b4a490e4b35f7",
        27_085,
    ),
    (
        "scripts/run_walksafe_fp048_r002_goal_start_gate_r005_20260826.py",
        "cbb567020fa911955c3988056e0d4a98ded782fe1a852a5c2f3919c5c5589be9",
        25_271,
    ),
    (
        "tests/test_walksafe_fp048_r002_goal_start_gate_r005_20260826.py",
        "1c8aada17623a657a7d46da282d8a5b98aedc5f4517313552de018e0b8539d49",
        23_755,
    ),
    (
        "scripts/apply_walksafe_fp048_r002_goal_started_seq94_20260826.py",
        "78e3102d6f0042d983078485c12ee0c4adf6771c4655a38c8b49641da1b9d0b3",
        76_842,
    ),
    (
        "tests/test_apply_walksafe_fp048_r002_goal_started_seq94_20260826.py",
        "ed8a4f770927af9d401ff46cd522ff24e49ef2f641870c06758a3577396ef7b5",
        56_426,
    ),
)
FROZEN_SEQ93_R003_INDEPENDENT_CHECKS = [
    "EXACT_PUBLISHED_SEQ92_SOURCE_CAS_PASS",
    "R004_PASS_SIX_FILE_PRIVATE_EVIDENCE_EXACT_PASS",
    "R004_PASS_SEALED_AS_PASS_UNCONSUMED_PASS",
    "LOGICAL_AND_PHYSICAL_BRANCH_SEMANTICS_SEPARATED_PASS",
    "R004_TO_R005_CONTRACT_SUPERSESSION_PASS",
    "SEQ93_READY_TO_READY_ZERO_CREDIT_PASS",
    "SEQ93_TO_SEQ92_EXACT_BYTE_RECONSTRUCTION_PASS",
    "FINAL_MANAGED_AND_GIT_VISIBLE_COHORT_RESEAL_PASS",
    "PRIVATE_0600_CHECKPOINT_MODE_PRECOMMIT_AND_POSTCOMMIT_PASS",
    "PROJECTED_CONTINUATION_AND_GOAL_GRAPH_PASS",
]

R005_CONTRACT_BINDING = {
    "schema_version": "1.2",
    "document_id": "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-005",
    "path": seq93.R005_CONTRACT_REL.as_posix(),
    "file_sha256": seq93.R005_FILE_SHA256,
    "contract_id": seq93.R005_CONTRACT_ID,
    "contract_version": seq93.R005_CONTRACT_VERSION,
    "canonical_contract_sha256": seq93.R005_CANONICAL_SHA256,
}
R005_RUNNER_BINDING = {
    "path": seq93.R005_RUNNER_REL.as_posix(),
    "sha256": "cbb567020fa911955c3988056e0d4a98ded782fe1a852a5c2f3919c5c5589be9",
    "byte_length": 25_271,
}

R006_CONTRACT_REL = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r006.json"
)
R006_RUNNER_REL = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r006_20260826.py"
)
R006_RUNNER_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r006_20260826.py"
)
POST_SEQ93_STAGE_REGRESSION_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_post_seq93_stage_regression_20260826.py"
)
SEQ95_STARTER_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq95_20260826.py"
)
SEQ95_STARTER_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq95_20260826.py"
)
R006_CONTRACT_ID = "WS-FP048-R002-INTERNAL-START-GATE-R006"
R006_CONTRACT_VERSION = "2026-08-26.5"
R006_DOCUMENT_ID = "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-006"
R006_FILE_SHA256 = (
    "ec2d0a34a9622d2fcb144602e0b53f388dfaf43c021d4ca8b8a815e5b7ec7509"
)
R006_CANONICAL_SHA256 = (
    "da78b752d95efc8631b3a650b3ee2305dd3c357b3ad5aec8546eb2ff492c4253"
)
R006_BYTE_LENGTH = 3_563
R006_SUCCESSOR_REASON_CODE = (
    "R005_PREDECESSOR_LIVE_SOURCE_REGRESSION_NOT_POSTPUBLICATION_SAFE"
)
R006_RUNNER_SHA256 = (
    "de7755cda818e2c60ac23af0c1b6dc5a3def4b71f2b64e41d7fa7a38c1044e75"
)
R006_RUNNER_BYTE_LENGTH = 26_746
SEQ95_STARTER_SHA256 = (
    "cf52e1008bfe95a088cff5a60bc24187a3de1b5b778d6e95eaa21ef9d2f5ddd1"
)
SEQ95_STARTER_BYTE_LENGTH = 78_649
SEQ95_STARTER_TEST_SHA256 = (
    "6951956c461620116893e4656d37917ed102b7d9f555bc92c5e96925e95022e9"
)
SEQ95_STARTER_TEST_BYTE_LENGTH = 57_966

SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp048_r002_start_gate_contract_correction_seq94_20260826.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_seq94_20260826.py"
)
CONTINUATION_SCRIPT_REL = Path("scripts/check_walksafe_project_continuation_v2_4.py")
CONTINUATION_TEST_REL = Path("tests/test_walksafe_project_continuation_v2_4.py")
GOAL_GRAPH_SCRIPT_REL = Path("scripts/check_walksafe_goal_graph_v2_4.py")
GOAL_GRAPH_TEST_REL = Path("tests/test_walksafe_goal_graph_v2_4.py")
LAYER_RUNNER_REL = Path("scripts/run_walksafe_test_layers_current.sh")
R006_COHORT_PATHS = (
    R006_CONTRACT_REL,
    R006_RUNNER_REL,
    POST_SEQ93_STAGE_REGRESSION_TEST_REL,
    TEST_REL,
    R006_RUNNER_TEST_REL,
    SEQ95_STARTER_REL,
    SEQ95_STARTER_TEST_REL,
)
R006_FIXED_COHORT_BINDINGS = {
    R006_CONTRACT_REL: {
        "path": R006_CONTRACT_REL.as_posix(),
        "sha256": R006_FILE_SHA256,
        "byte_length": R006_BYTE_LENGTH,
    },
    R006_RUNNER_REL: {
        "path": R006_RUNNER_REL.as_posix(),
        "sha256": R006_RUNNER_SHA256,
        "byte_length": R006_RUNNER_BYTE_LENGTH,
    },
    R006_RUNNER_TEST_REL: {
        "path": R006_RUNNER_TEST_REL.as_posix(),
        "sha256": "d46d3428d61cdd487e9fc84f283baf77200ad2b2f2810b7579a8630832c99fef",
        "byte_length": 31_162,
    },
    POST_SEQ93_STAGE_REGRESSION_TEST_REL: {
        "path": POST_SEQ93_STAGE_REGRESSION_TEST_REL.as_posix(),
        "sha256": "3bc8f553b4377637a151ce835c787f6476a60e254738527510b2bb28cd92f3d5",
        "byte_length": 9_155,
    },
    SEQ95_STARTER_REL: {
        "path": SEQ95_STARTER_REL.as_posix(),
        "sha256": SEQ95_STARTER_SHA256,
        "byte_length": SEQ95_STARTER_BYTE_LENGTH,
    },
    SEQ95_STARTER_TEST_REL: {
        "path": SEQ95_STARTER_TEST_REL.as_posix(),
        "sha256": SEQ95_STARTER_TEST_SHA256,
        "byte_length": SEQ95_STARTER_TEST_BYTE_LENGTH,
    },
    CONTINUATION_SCRIPT_REL: {
        "path": CONTINUATION_SCRIPT_REL.as_posix(),
        "sha256": "d769d1493c2c9993db91888865eb2b51b94c505154cb8bcc5c019cc2f48fdbdb",
        "byte_length": 580_589,
    },
    CONTINUATION_TEST_REL: {
        "path": CONTINUATION_TEST_REL.as_posix(),
        "sha256": "efe407f2ed2df20689915dcf78ae9ee68a16377725520621fbedba94ffdedbad",
        "byte_length": 303_017,
    },
    GOAL_GRAPH_SCRIPT_REL: {
        "path": GOAL_GRAPH_SCRIPT_REL.as_posix(),
        "sha256": "799ec4cb7c950c21112f5773a3b33b62680d5f64023e915d2bcec92b3efcf576",
        "byte_length": 839_059,
    },
    GOAL_GRAPH_TEST_REL: {
        "path": GOAL_GRAPH_TEST_REL.as_posix(),
        "sha256": "bc41cd90540ada0ec2360a2dbf7726ae8a6c03b5dac72b99901fc3113733868e",
        "byte_length": 559_614,
    },
    LAYER_RUNNER_REL: {
        "path": LAYER_RUNNER_REL.as_posix(),
        "sha256": "ba81da03cf47b35dcf3f445e6ea79d43d6b238608279fad9681b001beff2e9d6",
        "byte_length": 27_382,
    },
}
REVIEWED_CONTROL_PATHS = tuple(
    sorted(
        {
            SCRIPT_REL,
            TEST_REL,
            CONTINUATION_SCRIPT_REL,
            CONTINUATION_TEST_REL,
            GOAL_GRAPH_SCRIPT_REL,
            GOAL_GRAPH_TEST_REL,
            LAYER_RUNNER_REL,
            *R006_COHORT_PATHS,
        },
        key=lambda path: path.as_posix(),
    )
)

TRANSITION_ROOT = Path("docs/control/execution/workstream-transitions/seq94-95")
R001_AUTHORIZATION_REL = TRANSITION_ROOT / "authorization.json"
R001_AUTHORIZATION_SHA256 = (
    "220cec5e173a6f0cecdda5590902c598b24419c32dfbd08e805797c3af715c08"
)
R001_AUTHORIZATION_BYTE_LENGTH = 11_131
AUTHORIZATION_REL = TRANSITION_ROOT / "authorization-r002.json"
R001_REVIEW_ROOT = TRANSITION_ROOT / "review-rounds/R001"
R001_REVIEW_ASSIGNMENT_REL = R001_REVIEW_ROOT / "review-assignment.json"
R001_REVIEW_RESULT_REL = R001_REVIEW_ROOT / "review-result.json"
R001_INDEPENDENT_REVIEW_REL = R001_REVIEW_ROOT / "independent-review.json"
R001_REVIEW_ASSIGNMENT_SHA256 = (
    "3d20ec3529930e996c7d3ee059b8a75b52cee15faf74b5413c2ab8924fce3b29"
)
R001_REVIEW_ASSIGNMENT_BYTE_LENGTH = 17_641
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

R001_REVIEW_FINDING_COUNTS = {"P0": 0, "P1": 4, "P2": 2}
R001_REVIEW_FINDINGS = [
    {
        "finding_id": "P1_LIVE_SEQ93_FIXED_CAS_BREAKS_POST_SEQ94_GATE",
        "severity": "P1",
        "evidence": [
            {
                "path": POST_SEQ93_STAGE_REGRESSION_TEST_REL.as_posix(),
                "locator": "lines 42-53",
                "observation": (
                    "the stage adapter treated the live checkpoint as exact seq93 "
                    "instead of reconstructing the frozen seq93 prefix"
                ),
            }
        ],
    },
    {
        "finding_id": (
            "P1_CANDIDATE_REVIEW_ABSENCE_ASSERTION_BREAKS_PUBLISHED_REVIEW"
        ),
        "severity": "P1",
        "evidence": [
            {
                "path": TEST_REL.as_posix(),
                "locator": "lines 585-593",
                "observation": (
                    "the candidate manifest test required authority paths and the "
                    "transition root to be absent after publication"
                ),
            }
        ],
    },
    {
        "finding_id": "P1_POST_R006_NAMESPACE_REJECTED_BY_CURRENT_REGRESSION",
        "severity": "P1",
        "evidence": [
            {
                "path": POST_SEQ93_STAGE_REGRESSION_TEST_REL.as_posix(),
                "locator": "lines 87-91",
                "observation": "the regression required permanent live -004 absence",
            },
            {
                "path": TEST_REL.as_posix(),
                "locator": "lines 280-295",
                "observation": "the seq94 test required permanent live -004 absence",
            },
            {
                "path": R006_RUNNER_TEST_REL.as_posix(),
                "locator": "lines 248-267",
                "observation": (
                    "the gate test did not prove exact sealed post-R006 namespace "
                    "acceptance and forged namespace rejection"
                ),
            },
        ],
    },
    {
        "finding_id": "P1_GOAL_GRAPH_LIVE_SEQ93_REPLAYS_DYNAMIC_R003_AUTHORITY",
        "severity": "P1",
        "evidence": [
            {
                "path": GOAL_GRAPH_SCRIPT_REL.as_posix(),
                "locator": "line 9618",
                "observation": (
                    "live seq93 replayed dynamic R003 authority and failed with "
                    "'seq93 review assignment differs'"
                ),
            }
        ],
    },
    {
        "finding_id": "P2_AUTHORIZATION_004_ABSENCE_TEMPORAL_SCOPE_AMBIGUOUS",
        "severity": "P2",
        "evidence": [
            {
                "path": R001_AUTHORIZATION_REL.as_posix(),
                "locator": "authorization_scope.conditions",
                "observation": (
                    "R005_PREFLIGHT_004_NAMESPACE_AND_RECEIPT_MUST_REMAIN_ABSENT "
                    "did not limit physical absence to seq94 publication and the "
                    "pre-R006 gate stage"
                ),
            }
        ],
    },
    {
        "finding_id": "P2_GOAL_GRAPH_SYNTHETIC_CORRECTION_AUTHORITY_MOCK_STALE",
        "severity": "P2",
        "evidence": [
            {
                "path": GOAL_GRAPH_TEST_REL.as_posix(),
                "locator": "line 13927",
                "observation": (
                    "the synthetic correction authority mock was stale; the related "
                    "class reported 24 passed and 1 failed"
                ),
            }
        ],
    },
]

PREFLIGHT_ATTEMPT_004_TEMPORAL_SCOPE = {
    "historical_r005_observation_is_permanent": True,
    "historical_namespace_present": False,
    "historical_receipt_present": False,
    "live_absence_required_stages": [
        "SEQ94_PUBLICATION",
        "R006_PRE_GATE_VALIDATION",
    ],
    "fresh_r006_same_004_namespace_and_receipt_permitted_after_gate": True,
    "seq95_may_consume_exact_fresh_r006_pass": True,
}

CORRECTION_REASON = {
    "failed_contract_id": seq93.R005_CONTRACT_ID,
    "failed_contract_version": seq93.R005_CONTRACT_VERSION,
    "preflight_attempt_004": {
        "event_id": PREFLIGHT_ATTEMPT_EVENT_ID,
        "directory": PREFLIGHT_ATTEMPT_DIR_REL.as_posix(),
        "receipt_path": PREFLIGHT_ATTEMPT_RECEIPT_REL.as_posix(),
        "status": PREFLIGHT_ATTEMPT_STATUS,
        "authority_status": PREFLIGHT_ATTEMPT_AUTHORITY_STATUS,
        "event_identity_status": PREFLIGHT_ATTEMPT_EVENT_IDENTITY_STATUS,
        "namespace_present": False,
        "receipt_present": False,
        "reason_code": PREFLIGHT_ATTEMPT_REASON_CODE,
    },
    "reason_code": R006_SUCCESSOR_REASON_CODE,
    "remediation": "SUPERSEDE_R005_WITH_STAGE_AWARE_R006_BEFORE_SEQ95_START",
}
PROJECTED_TRANSITION = {
    "contract_correction_event_id": CORRECTION_EVENT_ID,
    "contract_correction_sequence": CORRECTION_SEQUENCE,
    "contract_correction_transition": "READY_TO_READY",
    "started_event_id": STARTED_EVENT_ID,
    "started_sequence": STARTED_SEQUENCE,
    "started_transition": "READY_TO_IN_PROGRESS_AFTER_FRESH_R006_PASS",
}
REVIEWER = {
    "id": "codex-fp048-r002-seq94-contract-independent-reviewer-20260826",
    "task_id": "/root/seq94_contract_correction_independent_review_r002",
}
INDEPENDENT_CHECKS = [
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
REVIEW_RESULT_FIELDS = frozenset(
    {
        "assignment_binding",
        "claim_boundary",
        "correction_reason",
        "decision",
        "document_id",
        "external_independence_claimed",
        "findings",
        "frozen_seq93_r003_review_binding",
        "goal_id",
        "passed_gate_attempt_003_binding",
        "preflight_attempt_004",
        "preflight_attempt_004_temporal_scope",
        "previous_contract_binding",
        "projected_transition",
        "replacement_contract_binding",
        "reviewed_at",
        "reviewer",
        "round_id",
        "schema_version",
        "source_checkpoint_binding",
    }
)
INDEPENDENT_REVIEW_FIELDS = REVIEW_RESULT_FIELDS | {
    "independent_checks",
    "review_result_binding",
}

SCOPE = (
    "Graph v2.4 FP-048 R002 remains READY through zero-credit seq94 R005-to-"
    "R006 gate-contract correction; R004 PASS -003 remains PASS_UNCONSUMED, "
    "R005 preflight -004 created no namespace and is NONAUTHORITY, while the "
    "reusable -004 R006 gate and seq95 start remain NOT_RUN."
)
CURRENT_FOCUS = (
    "FP-048 R002/GAP-057 READY; R004 PASS -003 remains PASS_UNCONSUMED; "
    "R005 -004 preflight has no namespace/nonauthority; R006 gate armed; "
    "seq95 start -004 NOT_RUN"
)
NEXT_ACTION = (
    "R006 event-scoped five-check gate를 reusable -004 event ID로 실행하고 "
    "PASS receipt로 seq95 start를 검증한다."
)
HANDOFF_EPIC = (
    "EPIC-03 / FP-048 R002/GAP-057 READY_R004_PASS_UNCONSUMED_"
    "R005_PREFLIGHT_NONAUTHORITY_R006_GATE_NOT_RUN"
)
VERIFICATION_STATUS = (
    "FP048_R002_SEQ94_ZERO_CREDIT_R006_CONTRACT_CORRECTED_GATE_NOT_RUN"
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


def _r001_authorization_binding(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, R001_AUTHORIZATION_REL).raw
    expected = {
        "path": R001_AUTHORIZATION_REL.as_posix(),
        "sha256": R001_AUTHORIZATION_SHA256,
        "byte_length": R001_AUTHORIZATION_BYTE_LENGTH,
    }
    require(
        _binding(R001_AUTHORIZATION_REL, raw) == expected,
        "seq94 R001 authorization bytes differ",
    )
    document = seq90.strict_json(raw, R001_AUTHORIZATION_REL.as_posix())
    conditions = document.get("authorization_scope", {}).get("conditions")
    require(
        raw == seq90.canonical_json_bytes(document)
        and document.get("schema_version") == "1.0"
        and document.get("document_id")
        == "WS-FP048-R002-SEQ94-95-AUTHORIZATION-20260826-001"
        and document.get("goal_id") == GOAL_ID
        and document.get("preflight_attempt_004")
        == _stored_preflight_attempt_004()
        and document.get("target_started_event_id") == STARTED_EVENT_ID
        and isinstance(conditions, list)
        and "R005_PREFLIGHT_004_NAMESPACE_AND_RECEIPT_MUST_REMAIN_ABSENT"
        in conditions,
        "seq94 R001 authorization authority differs",
    )
    return expected


def _r001_rejected_assignment_binding(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, R001_REVIEW_ASSIGNMENT_REL).raw
    expected = {
        "path": R001_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": R001_REVIEW_ASSIGNMENT_SHA256,
        "byte_length": R001_REVIEW_ASSIGNMENT_BYTE_LENGTH,
    }
    require(
        _binding(R001_REVIEW_ASSIGNMENT_REL, raw) == expected,
        "seq94 R001 review assignment bytes differ",
    )
    document = seq90.strict_json(raw, R001_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        raw == seq90.canonical_json_bytes(document)
        and document.get("schema_version") == "1.0"
        and document.get("document_id")
        == "WS-FP048-R002-SEQ94-95-REVIEW-ASSIGNMENT-20260826-R001"
        and document.get("round_id") == "R001"
        and document.get("goal_id") == GOAL_ID
        and document.get("authorization_binding") == _r001_authorization_binding(root)
        and document.get("required_reviewer", {}).get("task_id")
        == "/root/seq94_contract_correction_independent_review_r001",
        "seq94 R001 review assignment authority differs",
    )
    require(
        not os.path.lexists(root / R001_REVIEW_RESULT_REL)
        and not os.path.lexists(root / R001_INDEPENDENT_REVIEW_REL),
        "seq94 R001 review result and independent review must remain absent",
    )
    return {
        **expected,
        "round_id": "R001",
        "disposition": "REJECTED_BEFORE_REVIEW_RESULT",
        "finding_counts": copy.deepcopy(R001_REVIEW_FINDING_COUNTS),
        "findings": copy.deepcopy(R001_REVIEW_FINDINGS),
    }


def _parse_time(value: str, label: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value)
    except (TypeError, ValueError) as exc:
        raise ContractCorrectionError(f"{label} differs") from exc
    require(parsed.tzinfo is not None and parsed.microsecond == 0, f"{label} differs")
    return parsed


def _stored_passed_gate_attempt_003_binding() -> dict[str, Any]:
    return copy.deepcopy(seq93._stored_passed_gate_attempt_003_binding())


def passed_gate_attempt_003_binding(root: Path = ROOT) -> dict[str, Any]:
    observed = seq93.passed_gate_attempt_003_binding(root)
    require(
        observed == _stored_passed_gate_attempt_003_binding(),
        "R004 PASS -003 binding differs",
    )
    return copy.deepcopy(observed)


def _stored_preflight_attempt_004() -> dict[str, Any]:
    return copy.deepcopy(CORRECTION_REASON["preflight_attempt_004"])


def preflight_attempt_004_observation(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    require(
        not os.path.lexists(root / PREFLIGHT_ATTEMPT_DIR_REL)
        and not os.path.lexists(root / PREFLIGHT_ATTEMPT_RECEIPT_REL),
        "R005 preflight -004 namespace or receipt must remain absent",
    )
    return _stored_preflight_attempt_004()


def frozen_seq93_r003_review_binding(
    root: Path = ROOT,
    source_event: Mapping[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    """Validate R003 from frozen bytes and inputs without replaying its cohort."""

    root = seq90._safe_root(root)
    documents: dict[str, dict[str, Any]] = {}
    observed: dict[str, dict[str, Any]] = {}
    for role, relative in (
        ("assignment", seq93.REVIEW_ASSIGNMENT_REL),
        ("review_result", seq93.REVIEW_RESULT_REL),
        ("independent_review", seq93.INDEPENDENT_REVIEW_REL),
    ):
        raw = seq90._stable_read(root, relative).raw
        expected = FROZEN_SEQ93_R003_REVIEW_BINDINGS[role]
        require(
            _binding(relative, raw) == expected,
            f"frozen seq93 R003 {role} bytes differ",
        )
        document = seq90.strict_json(raw, relative.as_posix())
        require(
            raw == seq90.canonical_json_bytes(document),
            f"frozen seq93 R003 {role} is noncanonical",
        )
        documents[role] = document
        observed[role] = copy.deepcopy(expected)

    assignment = documents["assignment"]
    result = documents["review_result"]
    independent = documents["independent_review"]
    historical_inputs = [
        {"path": path, "sha256": digest, "byte_length": length}
        for path, digest, length in FROZEN_SEQ93_R003_REVIEWED_INPUTS
    ]
    require(
        len(historical_inputs) == 15
        and len({row["path"] for row in historical_inputs}) == 15
        and assignment.get("reviewed_control_inputs") == historical_inputs
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ93-94-REVIEW-ASSIGNMENT-20260826-R003"
        and assignment.get("round_id") == "R003"
        and assignment.get("goal_id") == GOAL_ID
        and assignment.get("authorization_binding")
        == FROZEN_SEQ93_AUTHORIZATION_BINDING
        and result.get("document_id")
        == "WS-FP048-R002-SEQ93-94-REVIEW-RESULT-20260826-R003"
        and result.get("assignment_binding") == observed["assignment"]
        and result.get("decision")
        == "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and result.get("findings") == []
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ93-94-INDEPENDENT-REVIEW-20260826-R003"
        and independent.get("assignment_binding") == observed["assignment"]
        and independent.get("review_result_binding") == observed["review_result"]
        and independent.get("decision")
        == "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and independent.get("findings") == []
        and independent.get("independent_checks")
        == FROZEN_SEQ93_R003_INDEPENDENT_CHECKS,
        "frozen seq93 R003 review authority differs",
    )
    if source_event is not None:
        require(
            source_event.get("transition_control_review_binding") == observed,
            "seq93 source frozen R003 review binding differs",
        )
    return copy.deepcopy(observed)


def r005_contract_binding(root: Path = ROOT) -> dict[str, Any]:
    _document, observed = seq93.load_r005_contract(root)
    require(observed == R005_CONTRACT_BINDING, "R005 contract binding differs")
    return copy.deepcopy(observed)


def r005_runner_binding(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    observed = _binding(
        seq93.R005_RUNNER_REL,
        seq90._stable_read(root, seq93.R005_RUNNER_REL).raw,
    )
    require(observed == R005_RUNNER_BINDING, "R005 runner binding differs")
    return copy.deepcopy(observed)


def load_r006_contract(
    root: Path = ROOT,
) -> tuple[dict[str, Any], dict[str, Any]]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, R006_CONTRACT_REL).raw
    require(
        len(raw) == R006_BYTE_LENGTH and sha256_bytes(raw) == R006_FILE_SHA256,
        "R006 contract bytes differ",
    )
    document = seq90.strict_json(raw, R006_CONTRACT_REL.as_posix())
    require(
        raw == seq90.canonical_json_bytes(document)
        and continuation.canonical_json_sha256(document) == R006_CANONICAL_SHA256,
        "R006 contract canonical authority differs",
    )
    checks = document.get("ordered_checks")
    supersedes = document.get("supersedes")
    previous = r005_contract_binding(root)
    require(
        document.get("schema_version") == "1.2"
        and document.get("document_id") == R006_DOCUMENT_ID
        and document.get("contract_id") == R006_CONTRACT_ID
        and document.get("contract_version") == R006_CONTRACT_VERSION
        and document.get("gate_purpose") == "INITIAL_START"
        and document.get("target_goal_id") == GOAL_ID
        and document.get("target_goal_content_sha256") == GOAL_SHA256
        and document.get("successor_reason_code") == R006_SUCCESSOR_REASON_CODE
        and isinstance(checks, list)
        and [row.get("check_id") for row in checks]
        == [
            "CONTINUATION",
            "GOAL_GRAPH",
            "TEST_LAYER_REGISTRY_VALIDATE",
            "ROOT_FP048_R002_CONTROL_REGRESSION",
            "REPOSITORY_STATE",
        ]
        and all(
            isinstance(row, dict)
            and set(row) == {"check_id", "command"}
            and isinstance(row["command"], str)
            and row["command"]
            for row in checks
        )
        and isinstance(supersedes, dict)
        and supersedes.get("path") == previous["path"]
        and supersedes.get("file_sha256") == previous["file_sha256"]
        and supersedes.get("canonical_sha256")
        == previous["canonical_contract_sha256"]
        and supersedes.get("contract_id") == previous["contract_id"]
        and supersedes.get("contract_version") == previous["contract_version"]
        and supersedes.get("document_id") == previous["document_id"]
        and supersedes.get("byte_length") == seq93.R005_BYTE_LENGTH
        and supersedes.get("source_correction_event_id") == CORRECTION_EVENT_ID
        and supersedes.get("source_correction_event_sequence")
        == CORRECTION_SEQUENCE,
        "R006 contract semantics differ",
    )
    claim = document.get("claim_boundary")
    require(
        isinstance(claim, dict)
        and claim.get("goal_started") is False
        and claim.get("start_gate_status") == "NOT_RUN"
        and claim.get("release_status") == "NOT_ELIGIBLE"
        and claim.get("intended_use")
        == "ADD_ONLY_SEQ94_CONTRACT_CORRECTION_THEN_PRIVATE_GATE_AND_SEQ95_START"
        and claim.get("seq93_reanchor_event_modified") is False
        and all(
            type(value) is int and value == 0
            for key, value in claim.items()
            if key.endswith("_credit_delta")
        ),
        "R006 contract claim boundary differs",
    )
    binding = {
        "schema_version": "1.2",
        "document_id": R006_DOCUMENT_ID,
        "path": R006_CONTRACT_REL.as_posix(),
        "file_sha256": R006_FILE_SHA256,
        "contract_id": R006_CONTRACT_ID,
        "contract_version": R006_CONTRACT_VERSION,
        "canonical_contract_sha256": R006_CANONICAL_SHA256,
    }
    return document, binding


def r006_contract_binding(root: Path = ROOT) -> dict[str, Any]:
    return load_r006_contract(root)[1]


def r006_runner_binding(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    raw = seq90._stable_read(root, R006_RUNNER_REL).raw
    observed = _binding(R006_RUNNER_REL, raw)
    require(
        observed
        == {
            "path": R006_RUNNER_REL.as_posix(),
            "sha256": R006_RUNNER_SHA256,
            "byte_length": R006_RUNNER_BYTE_LENGTH,
        },
        "R006 runner binding differs",
    )
    return observed


def _source_checkpoint_binding(
    passed_gate_binding: Mapping[str, Any] | None = None,
    preflight_attempt: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "path": CHECKPOINT_REL.as_posix(),
        "sha256": SOURCE_CHECKPOINT_SHA256,
        "byte_length": SOURCE_CHECKPOINT_BYTE_LENGTH,
        "sequence": SOURCE_SEQUENCE,
        "tail_event_id": SOURCE_EVENT_ID,
        "tail_event_sha256": SOURCE_EVENT_SHA256,
        "passed_gate_attempt_003": copy.deepcopy(
            dict(passed_gate_binding or _stored_passed_gate_attempt_003_binding())
        ),
        "preflight_attempt_004": copy.deepcopy(
            dict(preflight_attempt or _stored_preflight_attempt_004())
        ),
    }


def require_exact_seq93_source(
    raw: bytes,
    source: Mapping[str, Any],
    root: Path = ROOT,
) -> None:
    root = seq90._safe_root(root)
    require(len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH, "seq93 source length differs")
    require(sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256, "seq93 source CAS differs")
    require(raw == checkpoint_json_bytes(source), "seq93 source is noncanonical")
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
        "seq1-93 source lineage differs",
    )
    event = history[-1]
    frozen_review = frozen_seq93_r003_review_binding(root, event)
    passed_gate = passed_gate_attempt_003_binding(root)
    authorization_raw = seq90._stable_read(root, seq93.AUTHORIZATION_REL).raw
    require(
        _binding(seq93.AUTHORIZATION_REL, authorization_raw)
        == FROZEN_SEQ93_AUTHORIZATION_BINDING,
        "frozen seq93 authorization bytes differ",
    )
    after = event.get("repository_context_reanchor", {}).get("after")
    require(
        type(event.get("sequence")) is int
        and event.get("sequence") == SOURCE_SEQUENCE
        and event.get("event_id") == SOURCE_EVENT_ID
        and event.get("event_sha256") == SOURCE_EVENT_SHA256
        and event.get("event_type") == seq93.REANCHOR_EVENT_TYPE
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("authorization_binding")
        == FROZEN_SEQ93_AUTHORIZATION_BINDING
        and event.get("transition_control_review_binding") == frozen_review
        and event.get("source_checkpoint_binding", {}).get(
            "passed_gate_attempt_003"
        )
        == passed_gate
        and event.get("contract_supersession", {}).get(
            "replacement_contract_binding"
        )
        == r005_contract_binding(root)
        and event.get("start_gate_runner_binding") == r005_runner_binding(root)
        and isinstance(after, dict)
        and after.get("logical_branch") == LOGICAL_BRANCH
        and after.get("logical_branch_semantics") == LOGICAL_BRANCH_SEMANTICS
        and after.get("physical_git_branch") == PHYSICAL_GIT_BRANCH
        and after.get("branch_mismatch_reason_code")
        == BRANCH_MISMATCH_REASON_CODE,
        "seq93 source authority differs",
    )
    require(
        state.get("goal_status") == "READY"
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values()
        and source.get("current_work", {}).get("status") == "READY"
        and source.get("approved_state", {}).get("formal_test_not_run_count") == 279
        and source.get("verification_boundary", {}).get("release_eligible") is False,
        "seq93 source gained formal, implementation, or release credit",
    )


def load_exact_seq93_source(
    root: Path = ROOT,
) -> tuple[bytes, dict[str, Any]]:
    """Load exact seq93 bytes from either the pre- or post-seq94 stage."""

    root = seq90._safe_root(root)
    read = seq90._stable_read(root, CHECKPOINT_REL)
    live = seq90.strict_json(read.raw, CHECKPOINT_REL.as_posix())
    history = live.get("goal_execution", {}).get("transition_history")
    if isinstance(history, list) and len(history) == SOURCE_SEQUENCE:
        require_exact_seq93_source(read.raw, live, root)
        return read.raw, live
    raw = reconstructed_seq93_checkpoint_bytes(root, live)
    source = seq90.strict_json(raw, "stage-aware exact seq93 source")
    require_exact_seq93_source(raw, source, root)
    return raw, source


def build_authorization(
    root: Path,
    source: Mapping[str, Any],
    *,
    require_live_preflight_absence: bool = True,
) -> bytes:
    root = seq90._safe_root(root)
    _r001_rejected_assignment_binding(root)
    source_raw = checkpoint_json_bytes(source)
    require_exact_seq93_source(source_raw, source, root)
    passed_gate = passed_gate_attempt_003_binding(root)
    preflight_attempt = (
        preflight_attempt_004_observation(root)
        if require_live_preflight_absence
        else _stored_preflight_attempt_004()
    )
    value = {
        "authorization_scope": {
            "authorized_actions": [
                "SEQ94_ZERO_CREDIT_R005_TO_R006_CONTRACT_CORRECTION",
                "R006_PRIVATE_INITIAL_START_GATE_AFTER_SEQ94",
                "SEQ95_GOAL_STARTED_AFTER_FRESH_R006_FIVE_CHECK_PASS",
            ],
            "conditions": [
                "EXACT_PUBLISHED_SEQ93_SOURCE_CAS_REQUIRED",
                "FROZEN_SEQ93_R003_REVIEW_MUST_REMAIN_EXACT",
                "R004_PASS_003_MUST_REMAIN_PASS_UNCONSUMED",
                (
                    "R005_PREFLIGHT_004_NAMESPACE_AND_RECEIPT_MUST_REMAIN_"
                    "ABSENT_THROUGH_SEQ94_PUBLICATION_AND_R006_PRE_GATE"
                ),
                (
                    "FRESH_R006_MAY_CREATE_THE_SAME_004_NAMESPACE_AND_RECEIPT_"
                    "FOR_SEQ95_CONSUMPTION"
                ),
                "SEQ94_MUST_PRESERVE_READY_STATUS_AND_ZERO_CREDIT",
                "LIVE_PHYSICAL_GIT_BRANCH_MUST_MATCH_SEQ93_SEAL",
                "SEQ95_REQUIRES_A_FRESH_R006_FIVE_CHECK_PASS_RECEIPT",
            ],
            "excluded_actions": [
                "TREAT_R005_PREFLIGHT_004_AS_GATE_EVIDENCE",
                "BURN_REUSABLE_004_EVENT_ID_WITHOUT_A_NAMESPACE",
                "FORMAL_OR_ACTUAL_DEVICE_TEST_CREDIT",
                "EXTERNAL_REVIEW_OR_DEPLOYMENT_CREDIT",
                "RELEASE_OR_COMPLETION_CREDIT",
            ],
        },
        "authorization_status": (
            "AUTHORIZED_FOR_CONDITIONAL_SEQ94_95_CONTRACT_CORRECTION_CONTINUATION"
        ),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "contract_correction_event_id": CORRECTION_EVENT_ID,
        "correction_reason": copy.deepcopy(CORRECTION_REASON),
        "document_id": "WS-FP048-R002-SEQ94-95-AUTHORIZATION-20260826-002",
        "evidence_type": "USER_CONTINUATION_AUTHORIZATION",
        "frozen_seq93_r003_review_binding": frozen_seq93_r003_review_binding(
            root, source["goal_execution"]["transition_history"][-1]
        ),
        "goal_id": GOAL_ID,
        "passed_gate_attempt_003_binding": passed_gate,
        "preflight_attempt_004": preflight_attempt,
        "preflight_attempt_004_temporal_scope": copy.deepcopy(
            PREFLIGHT_ATTEMPT_004_TEMPORAL_SCOPE
        ),
        "previous_contract_binding": r005_contract_binding(root),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "replacement_contract_binding": r006_contract_binding(root),
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(
            passed_gate, preflight_attempt
        ),
        "supersedes": _r001_authorization_binding(root),
        "target_started_event_id": STARTED_EVENT_ID,
    }
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
        "seq94 authorization differs",
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
            for path, expected in R006_FIXED_COHORT_BINDINGS.items()
        ),
        "fixed R006 review cohort binding differs",
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
    require_exact_seq93_source(source_raw, source, root)
    passed_gate = passed_gate_attempt_003_binding(root)
    preflight_attempt = (
        preflight_attempt_004_observation(root)
        if require_live_preflight_absence
        else _stored_preflight_attempt_004()
    )
    frozen_review = frozen_seq93_r003_review_binding(
        root, source["goal_execution"]["transition_history"][-1]
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
        "document_id": "WS-FP048-R002-SEQ94-95-REVIEW-ASSIGNMENT-20260826-R002",
        "executor": {
            "id": "codex-fp048-r002-seq94-contract-correction-executor-20260826",
            "task_id": "/root/seq94_contract_correction_impl",
        },
        "frozen_seq93_r003_review_binding": frozen_review,
        "frozen_seq93_r003_reviewed_control_inputs": [
            {"path": path, "sha256": digest, "byte_length": length}
            for path, digest, length in FROZEN_SEQ93_R003_REVIEWED_INPUTS
        ],
        "goal_id": GOAL_ID,
        "passed_gate_attempt_003_binding": passed_gate,
        "preflight_attempt_004": preflight_attempt,
        "preflight_attempt_004_temporal_scope": copy.deepcopy(
            PREFLIGHT_ATTEMPT_004_TEMPORAL_SCOPE
        ),
        "predecessor_review_finding_counts": copy.deepcopy(
            R001_REVIEW_FINDING_COUNTS
        ),
        "predecessor_review_findings": copy.deepcopy(R001_REVIEW_FINDINGS),
        "previous_contract_binding": r005_contract_binding(root),
        "previous_runner_binding": r005_runner_binding(root),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "replacement_contract_binding": r006_contract_binding(root),
        "replacement_runner_binding": r006_runner_binding(root),
        "required_reviewer": copy.deepcopy(REVIEWER),
        "review_scope": [
            "exact published seq93 source CAS and event lineage",
            "frozen seq93 R003 triad and its exact fifteen historical inputs",
            "exact R004 PASS -003 six-file private PASS_UNCONSUMED inventory",
            (
                "R005 historical preflight -004 metadata remains permanently "
                "nonauthority with namespace_present=false and receipt_present=false"
            ),
            (
                "physical -004 absence applies through seq94 publication and R006 "
                "pre-gate only; exact fresh R006 PASS may create and seq95 may consume it"
            ),
            "-004 identity remains reusable and unconsumed for the R006 gate and seq95",
            "exact R005 contract and runner remain preserved",
            "R005-to-R006 append-only contract supersession",
            "all modified and new stage-aware R006 control inputs",
            "seq94 READY-to-READY transition with every credit delta zero",
            "exact seq94-to-seq93 byte reconstruction",
            "physical Git branch, managed cohort, and Git-visible cohort reseal",
            "atomic replacement and private 0600 checkpoint mode",
        ],
        "reviewed_control_inputs": _reviewed_control_bindings(root),
        "round_id": ROUND_ID,
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(
            passed_gate, preflight_attempt
        ),
        "supersedes": _r001_rejected_assignment_binding(root),
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
        "seq94 review assignment differs",
    )
    assignment = seq90.strict_json(assignment_raw, REVIEW_ASSIGNMENT_REL.as_posix())
    result = seq90.strict_json(result_raw, REVIEW_RESULT_REL.as_posix())
    independent = seq90.strict_json(independent_raw, INDEPENDENT_REVIEW_REL.as_posix())
    require(
        set(result) == REVIEW_RESULT_FIELDS
        and set(independent) == INDEPENDENT_REVIEW_FIELDS
        and result_raw == seq90.canonical_json_bytes(result)
        and independent_raw == seq90.canonical_json_bytes(independent),
        "seq94 review documents are noncanonical",
    )
    assignment_binding_value = _binding(REVIEW_ASSIGNMENT_REL, assignment_raw)
    result_binding_value = _binding(REVIEW_RESULT_REL, result_raw)
    passed_gate = passed_gate_attempt_003_binding(root)
    preflight_attempt = (
        preflight_attempt_004_observation(root)
        if require_live_preflight_absence
        else _stored_preflight_attempt_004()
    )
    frozen_review = frozen_seq93_r003_review_binding(
        root, source["goal_execution"]["transition_history"][-1]
    )
    common = {
        "claim_boundary": CLAIM_BOUNDARY,
        "correction_reason": CORRECTION_REASON,
        "frozen_seq93_r003_review_binding": frozen_review,
        "goal_id": GOAL_ID,
        "passed_gate_attempt_003_binding": passed_gate,
        "preflight_attempt_004": preflight_attempt,
        "preflight_attempt_004_temporal_scope": PREFLIGHT_ATTEMPT_004_TEMPORAL_SCOPE,
        "previous_contract_binding": r005_contract_binding(root),
        "projected_transition": PROJECTED_TRANSITION,
        "replacement_contract_binding": r006_contract_binding(root),
        "reviewer": REVIEWER,
        "round_id": ROUND_ID,
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(
            passed_gate, preflight_attempt
        ),
    }
    require(
        assignment.get("authorization_binding") == expected_authorization
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ94-95-REVIEW-ASSIGNMENT-20260826-R002"
        and assignment.get("round_id") == ROUND_ID
        and assignment.get("required_reviewer") == REVIEWER
        and assignment.get("predecessor_review_finding_counts")
        == R001_REVIEW_FINDING_COUNTS
        and assignment.get("predecessor_review_findings") == R001_REVIEW_FINDINGS
        and assignment.get("supersedes") == _r001_rejected_assignment_binding(root)
        and result.get("document_id")
        == "WS-FP048-R002-SEQ94-95-REVIEW-RESULT-20260826-R002"
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ94-95-INDEPENDENT-REVIEW-20260826-R002"
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
        "seq94 review approval differs",
    )
    result_at = _parse_time(result.get("reviewed_at"), "seq94 result reviewed_at")
    independent_at = _parse_time(
        independent.get("reviewed_at"), "seq94 independent reviewed_at"
    )
    source_at = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq93 occurred_at",
    )
    require(
        result_at > source_at and independent_at > result_at,
        "seq94 review time order differs",
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
        _parse_time(supplied, "seq94 occurred_at")
        if supplied is not None
        else datetime.now().astimezone().replace(microsecond=0)
    )
    source_time = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq93 occurred_at",
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


def project_seq94(
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
    preflight_attempt_binding: Mapping[str, Any],
    replacement_contract_binding: Mapping[str, Any],
    replacement_runner_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    root = seq90._safe_root(root)
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
        "seq94 managed paths differ",
    )
    require(
        dict(passed_gate_binding) == _stored_passed_gate_attempt_003_binding(),
        "seq94 PASS_UNCONSUMED -003 binding differs",
    )
    require(
        dict(preflight_attempt_binding) == _stored_preflight_attempt_004(),
        "seq94 preflight -004 observation differs",
    )
    require(
        dict(replacement_contract_binding) == r006_contract_binding(root),
        "seq94 R006 contract binding differs",
    )
    require(
        dict(replacement_runner_binding) == r006_runner_binding(root),
        "seq94 R006 runner binding differs",
    )
    require(
        source_event.get("contract_supersession", {}).get(
            "replacement_contract_binding"
        )
        == r005_contract_binding(root)
        and source_event.get("start_gate_runner_binding")
        == r005_runner_binding(root),
        "seq94 R005 predecessor authority differs",
    )
    _parse_time(occurred_at, "seq94 occurred_at")

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
        passed_gate_binding, preflight_attempt_binding
    )
    event: dict[str, Any] = {
        "sequence": CORRECTION_SEQUENCE,
        "event_id": CORRECTION_EVENT_ID,
        "event_type": CORRECTION_EVENT_TYPE,
        "occurred_on": _parse_time(
            occurred_at, "seq94 occurred_at"
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
            "FP048-R002_EXACT_SEQ93_BRANCH_SEMANTICS_SOURCE",
            "FP048-R002_SEQ93_R003_FROZEN_REVIEW_AUTHORITY",
            "FP048-R002_R004_PASS_003_REMAINS_PASS_UNCONSUMED",
            "FP048-R002_R005_PREFLIGHT_004_NO_NAMESPACE_NONAUTHORITY",
            "FP048-R002_R006_STAGE_AWARE_GATE_CONTRACT",
            "FP048-R002_SEQ94_95_TRANSITION_CONTROL_REVIEW",
        ],
        "source_checkpoint_binding": source_binding,
        "source_ready_event_binding": copy.deepcopy(
            source_event["source_ready_event_binding"]
        ),
        "authorization_binding": copy.deepcopy(dict(authorization_binding_value)),
        "contract_supersession": {
            "previous_contract_binding": copy.deepcopy(
                source_event["contract_supersession"]["replacement_contract_binding"]
            ),
            "reason_code": R006_SUCCESSOR_REASON_CODE,
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
        "unchanged_control_projection": seq93.seq92.correction._unchanged_projection(
            projected
        ),
        "canonical_binding_snapshot_after": copy.deepcopy(
            source_event["canonical_binding_snapshot_after"]
        ),
        "correction_reason": copy.deepcopy(CORRECTION_REASON),
        "previous_event_sha256": SOURCE_EVENT_SHA256,
    }
    event["event_sha256"] = continuation.event_sha256(event)
    require(set(event) == EVENT_FIELDS, "seq94 correction event field set differs")
    state = projected["goal_execution"]
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = occurred_at
    require(
        state["goal_status"] == "READY"
        and state["status_by_goal"][GOAL_ID] == "READY"
        and current["status"] == "READY"
        and "IN_PROGRESS" not in state["status_by_goal"].values(),
        "seq94 READY state differs",
    )
    for field in (
        "approved_state",
        "authority_boundary",
        "canonical_bindings",
        "verification_boundary",
    ):
        require(
            _strict_json_equal(projected[field], source[field]),
            f"seq94 changed {field}",
        )
    require(
        all(
            type(value) is int and value == 0
            for key, value in CLAIM_BOUNDARY.items()
            if key.endswith("_credit_delta") or key == "goal_status_change_count"
        )
        and CLAIM_BOUNDARY["implementation_start_authorized"] is False,
        "seq94 credit boundary differs",
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
        "seq94 repository context differs",
    )
    return before


def _restored_seq93_checkpoint(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "seq93 inverse source is not exact seq94",
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
        "checkpoint is not exact seq94 contract correction",
    )
    source_event = history[SOURCE_SEQUENCE - 1]
    event = history[CORRECTION_SEQUENCE - 1]
    passed_gate = passed_gate_attempt_003_binding(root)
    preflight_attempt = (
        preflight_attempt_004_observation(root)
        if require_live_snapshot
        else _stored_preflight_attempt_004()
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
        == _source_checkpoint_binding(passed_gate, preflight_attempt)
        and event.get("correction_reason") == CORRECTION_REASON
        and event.get("claim_boundary") == CLAIM_BOUNDARY,
        "seq94 correction authority differs",
    )
    before = _repository_before(event)
    source_raw = checkpoint_json_bytes(_restored_seq93_checkpoint(checkpoint))
    source = seq90.strict_json(source_raw, "restored seq93 source")
    require_exact_seq93_source(source_raw, source, root)
    review_binding, result_at, independent_at = _load_physical_review(
        root,
        source,
        require_live_preflight_absence=require_live_snapshot,
    )
    event_at = _parse_time(event.get("occurred_at"), "seq94 occurred_at")
    source_at = _parse_time(source_event.get("occurred_at"), "seq93 occurred_at")
    require(
        source_at < result_at < independent_at < event_at,
        "seq94 event and physical review chronology differs",
    )
    snapshot = checkpoint.get("working_tree_snapshot")
    paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
    require(isinstance(paths, list), "seq94 managed paths differ")
    expected_checkpoint, expected_event = project_seq94(
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
        preflight_attempt_binding=preflight_attempt,
        replacement_contract_binding=r006_contract_binding(root),
        replacement_runner_binding=r006_runner_binding(root),
    )
    require(
        _strict_json_equal(event, expected_event)
        and _strict_json_equal(checkpoint, expected_checkpoint),
        "seq94 exact producer projection differs",
    )
    require(
        event.get("contract_supersession")
        == {
            "previous_contract_binding": r005_contract_binding(root),
            "reason_code": R006_SUCCESSOR_REASON_CODE,
            "replacement_contract_binding": r006_contract_binding(root),
        }
        and event.get("start_gate_runner_binding") == r006_runner_binding(root)
        and event.get("runtime_after") == source_event.get("runtime_after")
        and event.get("blockers_after") == source_event.get("blockers_after")
        and event.get("blocker_resolution_ids_after")
        == source_event.get("blocker_resolution_ids_after")
        and event.get("noncredit_successor_edges")
        == source_event.get("noncredit_successor_edges"),
        "seq94 successor authority differs",
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
        "seq1-94 lineage differs",
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
        "seq94 READY projection differs",
    )
    for field in (
        "approved_state",
        "authority_boundary",
        "canonical_bindings",
        "verification_boundary",
    ):
        require(
            _strict_json_equal(checkpoint.get(field), source.get(field)),
            f"seq94 changed {field}",
        )
    require(
        checkpoint.get("approved_state", {}).get("formal_test_not_run_count") == 279
        and checkpoint.get("verification_boundary", {}).get("release_eligible")
        is False
        and before["current_work"]["status"] == "READY",
        "seq94 gained formal, implementation, or release credit",
    )
    if require_live_snapshot:
        require(
            continuation.working_snapshot_hashes(root, paths)
            == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
            "seq94 managed snapshot differs",
        )
    if run_external_validators:
        seq90._validate_projected_with_consumers(root, checkpoint)


def reconstructed_seq93_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    if isinstance(history, list) and len(history) == SOURCE_SEQUENCE:
        raw = checkpoint_json_bytes(checkpoint)
        require_exact_seq93_source(raw, checkpoint, root)
        return raw
    require_contract_corrected_checkpoint(
        root, checkpoint, require_live_snapshot=False
    )
    raw = checkpoint_json_bytes(_restored_seq93_checkpoint(checkpoint))
    require(
        len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH
        and sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256,
        "seq94 inverse does not reconstruct exact seq93",
    )
    return raw


def canonical_seq94_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    require_contract_corrected_checkpoint(
        root, checkpoint, require_live_snapshot=False
    )
    return checkpoint_json_bytes(checkpoint)


def reconstructed_seq94_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(
        isinstance(history, list) and len(history) == CORRECTION_SEQUENCE,
        "seq95 inverse is owned by the seq95 starter",
    )
    return canonical_seq94_checkpoint_bytes(root, checkpoint)


@dataclass(frozen=True)
class Prepared:
    transport: seq90.Prepared

    @property
    def projected(self) -> Mapping[str, Any]:
        return self.transport.projected

    @property
    def event(self) -> Mapping[str, Any]:
        return self.transport.event


_AFTER_CAPTURE_HOOK: Callable[[Path], None] | None = None


def _require_private_checkpoint_mode(root: Path, phase: str) -> None:
    mode = stat.S_IMODE((root / CHECKPOINT_REL).lstat().st_mode)
    require(mode == 0o600, f"checkpoint mode differs {phase}")


def _require_prepared_exact(prepared: Prepared) -> None:
    transport = prepared.transport
    _require_private_checkpoint_mode(transport.root, "during seq94 preflight reseal")
    passed_gate_attempt_003_binding(transport.root)
    preflight_attempt_004_observation(transport.root)
    _r001_authorization_binding(transport.root)
    _r001_rejected_assignment_binding(transport.root)
    r005_contract_binding(transport.root)
    r005_runner_binding(transport.root)
    r006_contract_binding(transport.root)
    r006_runner_binding(transport.root)
    seq90._require_preflight_cohort_unchanged(
        transport.root,
        source=seq90.ReadResult(transport.source_raw, transport.source_identity),
        retained=transport.retained_inputs,
        managed=transport.managed_inputs,
        git_visible=transport.git_visible_inputs,
        git_status_raw=transport.git_status_raw,
        git_head=transport.git_head,
        git_branch=transport.git_branch,
        phase="seq94 contract-correction commit guard",
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
    require(len(candidates) == 1, "Git-visible cohort changed at seq94 commit guard")
    return candidates[0]


def _require_commit_exact(prepared: Prepared) -> None:
    transport = prepared.transport
    root = transport.root
    _require_private_checkpoint_mode(root, "at seq94 commit guard")
    passed_gate_attempt_003_binding(root)
    preflight_attempt_004_observation(root)
    _r001_authorization_binding(root)
    _r001_rejected_assignment_binding(root)
    r005_contract_binding(root)
    r005_runner_binding(root)
    r006_contract_binding(root)
    r006_runner_binding(root)
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
        "source changed at seq94 commit guard",
    )
    status_raw, _visible = seq90.capture_git_visible_paths(root)
    require(
        _without_seq90_writer_temporary_status(status_raw, transport.git_status_raw)
        == transport.git_status_raw,
        "Git-visible cohort changed at seq94 commit guard",
    )


def prepare(
    root: Path = ROOT,
    *,
    occurred_at: str | None = None,
    validate_consumers: bool = True,
) -> Prepared:
    root = seq90._safe_root(root)
    source_read = seq90._stable_read(root, CHECKPOINT_REL)
    _require_private_checkpoint_mode(root, "before seq94 preflight")
    source = seq90.strict_json(source_read.raw, CHECKPOINT_REL.as_posix())
    require_exact_seq93_source(source_read.raw, source, root)
    passed_gate = passed_gate_attempt_003_binding(root)
    preflight_attempt = preflight_attempt_004_observation(root)
    replacement = r006_contract_binding(root)
    replacement_runner = r006_runner_binding(root)
    git_head, git_branch = seq90._capture_git_context(root)
    expected_head = source["session_handoff"]["source_commit_or_snapshot"][
        "current_head"
    ]
    require(git_head == expected_head, "live Git HEAD differs from seq93 source")
    require(
        git_branch == PHYSICAL_GIT_BRANCH,
        "live Git branch differs from exact seq93 physical branch",
    )
    status_raw, visible = seq90.capture_git_visible_paths(root)
    paths = _final_managed_paths(source, visible)
    managed = seq90._capture_managed_inputs(root, paths)
    visible_inputs = seq90._capture_managed_inputs(root, visible)
    path_sha256, content_sha256 = seq90._managed_input_snapshot_hashes(managed)
    retained_paths = set(REVIEWED_CONTROL_PATHS) | set(REVIEW_PATHS) | {
        AUTHORIZATION_REL,
        R001_AUTHORIZATION_REL,
        R001_REVIEW_ASSIGNMENT_REL,
        seq93.AUTHORIZATION_REL,
        seq93.REVIEW_ASSIGNMENT_REL,
        seq93.REVIEW_RESULT_REL,
        seq93.INDEPENDENT_REVIEW_REL,
        seq93.R005_CONTRACT_REL,
        seq93.R005_RUNNER_REL,
    }
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
    projected, event = project_seq94(
        root,
        source,
        managed_paths=paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        occurred_at=_event_time(source, occurred_at, independent_at),
        authorization_binding_value=authorization,
        review_binding=review,
        passed_gate_binding=passed_gate,
        preflight_attempt_binding=preflight_attempt,
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
            prepared.transport.root, "after seq94 publication"
        )
    except BaseException as exc:
        raise seq90.PostcommitUncertain(
            "published seq94 checkpoint mode is uncertain"
        ) from exc


def _candidate_requires_live_preflight_absence(root: Path) -> bool:
    root = seq90._safe_root(root)
    read = seq90._stable_read(root, CHECKPOINT_REL)
    checkpoint = seq90.strict_json(read.raw, CHECKPOINT_REL.as_posix())
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    require(isinstance(history, list) and history, "live checkpoint history differs")
    sequence = history[-1].get("sequence") if isinstance(history[-1], dict) else None
    require(type(sequence) is int and sequence == len(history), "live stage differs")
    return sequence < STARTED_SEQUENCE


def prepare_review_manifest(root: Path = ROOT) -> dict[str, Any]:
    root = seq90._safe_root(root)
    _raw, source = load_exact_seq93_source(root)
    require_live_preflight_absence = _candidate_requires_live_preflight_absence(root)
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
    preflight_attempt = (
        preflight_attempt_004_observation(root)
        if require_live_preflight_absence
        else _stored_preflight_attempt_004()
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
            preflight_attempt,
        ),
        "status": "CANDIDATE_ONLY_NOT_PUBLISHED",
        "supersedes_authorization": _r001_authorization_binding(root),
        "supersedes_review_assignment": _r001_rejected_assignment_binding(root),
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
            _raw, source = load_exact_seq93_source(args.root)
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
        message = (
            "FP048-R002 seq94 gate-contract correction: PASS "
            f"event_sha256={prepared.event['event_sha256']} "
            f"occurred_at={prepared.event['occurred_at']}\n"
        )
        try:
            sys.stdout.write(message)
            sys.stdout.flush()
        except BaseException as exc:
            if published:
                raise seq90.PostcommitUncertain(
                    "published seq94 PASS output delivery is uncertain"
                ) from exc
            raise
    except seq90.PostcommitUncertain as exc:
        print(
            f"FP048-R002 seq94 gate-contract correction: POSTCOMMIT-UNCERTAIN: {exc}",
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
            f"FP048-R002 seq94 gate-contract correction: FAIL: {exc}",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
