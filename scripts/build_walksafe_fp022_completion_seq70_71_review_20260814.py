#!/usr/bin/env python3
"""Build and validate the actor-separated FP-022 seq70/71 completion review.

Only the assignment and the deterministic independent validation artifact may
be written by this tool.  The reviewer-authored result must be supplied by the
separate reviewer named in the assignment.
"""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timezone
import os
from pathlib import Path
import sys
from typing import Any, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp022_goal_completed_seq70_71_20260814 as completion
from scripts import build_walksafe_fp022_gap_backlog_r028_20260814 as r028_builder
from scripts import build_walksafe_fp022_navigation_internal_evidence_20260814 as evidence
from scripts import build_walksafe_fp022_seq68_69_review_20260814 as start_review
from scripts import (
    build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813
    as npc_r004_review,
)
from scripts import check_walksafe_project_continuation_v2_4 as continuation


ReviewError = evidence.BuildError
require = evidence.require
bytes_sha256 = evidence.bytes_sha256
object_sha256 = evidence.object_sha256
json_text = evidence.json_text
strict_json_bytes = evidence.strict_json_bytes

GOAL_ID = evidence.GOAL_ID
CHECKPOINT_REL = Path("docs/control/walksafe-project-continuation-checkpoint.json")
SOURCE_CHECKPOINT = {
    "path": CHECKPOINT_REL.as_posix(),
    "sequence": 69,
    "sha256": "5f260789269a5936517620b55262215b77797e4b2ec83220ec94d20cf951c25f",
    "byte_length": 2_032_842,
    "tail_event_id": "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20260814-001",
    "tail_event_sha256": (
        "91cec421d1fdd2f0f0d3ec57e282f7dceb7ae1a6fe51c9db9f9ffb7bba3e38a6"
    ),
}

START_REVIEW_PATHS = (
    start_review.ASSIGNMENT_REL,
    start_review.RESULT_REL,
    start_review.INDEPENDENT_REL,
)
START_REVIEW_PINS = {
    start_review.ASSIGNMENT_REL: (
        "59ab697506c6583765dc601967c121bd37965b6fa2575d43d99d4f8b655723d0",
        21_453,
    ),
    start_review.RESULT_REL: (
        "7e56214469952ccdcf947689fdca820ffb35dbfc975c1743c1d956a72c0099c0",
        21_506,
    ),
    start_review.INDEPENDENT_REL: (
        "edd71466f9176697d1e640703c43d2dad404902c4a9abc741ad16fe96a07dd38",
        21_784,
    ),
}

EVIDENCE_PATHS = (
    evidence.IMPLEMENTATION_REL,
    evidence.OBSERVATIONS_REL,
    *(lane.log_rel for lane in evidence.LANES),
    evidence.VERIFICATION_REL,
    *evidence.R028_PATHS,
    evidence.SUCCESSOR_REL,
    evidence.REVIEW_SUBJECT_REL,
    evidence.INDEPENDENT_REVIEW_REL,
    evidence.COMPLETION_REL,
)
UNMANAGED_EVIDENCE_PATHS = tuple(lane.log_rel for lane in evidence.LANES)

SCRIPT_REL = Path(
    "scripts/build_walksafe_fp022_completion_seq70_71_review_20260814.py"
)
TEST_REL = Path(
    "tests/test_build_walksafe_fp022_completion_seq70_71_review_20260814.py"
)
EVIDENCE_SCRIPT_REL = Path(
    "scripts/build_walksafe_fp022_navigation_internal_evidence_20260814.py"
)
EVIDENCE_TEST_REL = Path(
    "tests/test_build_walksafe_fp022_navigation_internal_evidence_20260814.py"
)
R028_SCRIPT_REL = Path(
    "scripts/build_walksafe_fp022_gap_backlog_r028_20260814.py"
)
R028_TEST_REL = Path(
    "tests/test_build_walksafe_fp022_gap_backlog_r028_20260814.py"
)
CONTROL_PATHS = (
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("scripts/generate_repository_catalogs.py"),
    Path("tests/test_repository_catalogs.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    EVIDENCE_SCRIPT_REL,
    EVIDENCE_TEST_REL,
    R028_SCRIPT_REL,
    R028_TEST_REL,
    completion.SCRIPT_REL,
    completion.TEST_REL,
    SCRIPT_REL,
    TEST_REL,
)

REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq70-71/review-rounds"
)
R001_DIR = REVIEW_ROOT / "R001"
R001_ASSIGNMENT_REL = R001_DIR / "assignment.json"
R001_RESULT_REL = R001_DIR / "review-result.json"
R001_INDEPENDENT_REL = R001_DIR / "independent-review.json"
R002_DIR = REVIEW_ROOT / "R002"
R002_ASSIGNMENT_REL = R002_DIR / "assignment.json"
R002_RESULT_REL = R002_DIR / "review-result.json"
R002_INDEPENDENT_REL = R002_DIR / "independent-review.json"
R003_DIR = REVIEW_ROOT / "R003"
R003_ASSIGNMENT_REL = R003_DIR / "assignment.json"
R003_RESULT_REL = R003_DIR / "review-result.json"
R003_INDEPENDENT_REL = R003_DIR / "independent-review.json"
R004_DIR = REVIEW_ROOT / "R004"
R004_ASSIGNMENT_REL = R004_DIR / "assignment.json"
R004_RESULT_REL = R004_DIR / "review-result.json"
R004_INDEPENDENT_REL = R004_DIR / "independent-review.json"
R005_DIR = REVIEW_ROOT / "R005"
R005_ASSIGNMENT_REL = R005_DIR / "assignment.json"
R005_RESULT_REL = R005_DIR / "review-result.json"
R005_INDEPENDENT_REL = R005_DIR / "independent-review.json"
R006_DIR = REVIEW_ROOT / "R006"
R006_ASSIGNMENT_REL = R006_DIR / "assignment.json"
R006_RESULT_REL = R006_DIR / "review-result.json"
R006_INDEPENDENT_REL = R006_DIR / "independent-review.json"
R007_DIR = REVIEW_ROOT / "R007"
R007_ASSIGNMENT_REL = R007_DIR / "assignment.json"
R007_RESULT_REL = R007_DIR / "review-result.json"
R007_INDEPENDENT_REL = R007_DIR / "independent-review.json"
R008_DIR = REVIEW_ROOT / "R008"
R008_ASSIGNMENT_REL = R008_DIR / "assignment.json"
R008_RESULT_REL = R008_DIR / "review-result.json"
R008_INDEPENDENT_REL = R008_DIR / "independent-review.json"
R009_DIR = REVIEW_ROOT / "R009"
R009_ASSIGNMENT_REL = R009_DIR / "assignment.json"
R009_RESULT_REL = R009_DIR / "review-result.json"
R009_INDEPENDENT_REL = R009_DIR / "independent-review.json"
R010_DIR = REVIEW_ROOT / "R010"
R010_ASSIGNMENT_REL = R010_DIR / "assignment.json"
R010_RESULT_REL = R010_DIR / "review-result.json"
R010_INDEPENDENT_REL = R010_DIR / "independent-review.json"
R011_DIR = REVIEW_ROOT / "R011"
R011_ASSIGNMENT_REL = R011_DIR / "assignment.json"
R011_RESULT_REL = R011_DIR / "review-result.json"
R011_INDEPENDENT_REL = R011_DIR / "independent-review.json"
R012_DIR = REVIEW_ROOT / "R012"
R012_ASSIGNMENT_REL = R012_DIR / "assignment.json"
R012_RESULT_REL = R012_DIR / "review-result.json"
R012_INDEPENDENT_REL = R012_DIR / "independent-review.json"
R013_DIR = REVIEW_ROOT / "R013"
R013_ASSIGNMENT_REL = R013_DIR / "assignment.json"
R013_RESULT_REL = R013_DIR / "review-result.json"
R013_INDEPENDENT_REL = R013_DIR / "independent-review.json"
SUPERSEDED_ASSIGNMENTS = (
    {
        "path": R001_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "ff8f90f87ae184423a6af793a651fb47cbef7056cdef1ce5ab4b4f2d89e52ff4"
        ),
        "byte_length": 10_872,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "CATALOGS_WERE_NOT_SEEDED_IN_INITIAL_MANAGED_CLOSURE",
    },
    {
        "path": R002_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "51a141e80d0a6dd63a23ecfe381167cb40af3c76b359193abc02f3f61f0e4a79"
        ),
        "byte_length": 11_284,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "COMPLETION_REVIEW_PATH_AND_HISTORICAL_SEQ68_SNAPSHOT_WERE_NOT_SUCCESSOR_AWARE",
    },
    {
        "path": R003_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "4146230011874697d4b72222a0697981b490c1e70af5da26fafdad3aa23c3212"
        ),
        "byte_length": 11_677,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "HISTORICAL_NPC_AND_FP022_PRODUCT_SUCCESSOR_OVERLAYS_WERE_NOT_COMPOSED",
    },
    {
        "path": R004_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "e021af8c11ff6b50648d5b1f36a41386c27eed2e27ce68c6f69020d11fb4d850"
        ),
        "byte_length": 12_062,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "HISTORICAL_SEQ55_REGRESSION_REPLAYED_MUTABLE_NPC_COMPLETION_AUTHORITY",
    },
    {
        "path": R005_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "d1b21d6548a73ed01c503ac671adb6e63197c004d8a7176b8ab33090656c01ba"
        ),
        "byte_length": 12_447,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "DEFAULT_PRODUCT_COMPATIBILITY_REPLAYED_MUTABLE_COMPLETION_AUTHORITY",
    },
    {
        "path": R006_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "16070d016fb8f01910ef9a07f7c5369b2950d4c73e8351a67c8249675a625152"
        ),
        "byte_length": 12_830,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "IGNORED_EVIDENCE_LOGS_WERE_INCLUDED_IN_THE_MANAGED_SOURCE_UNIVERSE",
    },
    {
        "path": R007_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "4e372c5c36c01f7416742e1ed411bae54561ca796814da364c67009fe89d5d2f"
        ),
        "byte_length": 13_212,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "MANAGED_SEQUENCE_PATHS_STILL_INCLUDED_IGNORED_EVIDENCE_LOGS",
    },
    {
        "path": R008_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "bcfaa4f23c7858173bc0c396e405e7ff5ebe98e5508288daf7c65569dfd117ce"
        ),
        "byte_length": 13_587,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "FORCED_GIT_VISIBLE_IGNORED_LOG_COULD_REENTER_MANAGED_CLOSURE",
    },
    {
        "path": R009_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "9d3075a1c5cbcb75025acaf060e443c24e95da57cdf8635e2965cdf64a982cb4"
        ),
        "byte_length": 13_963,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ71_DID_NOT_REWIND_SEQ67_AUTHORITY_OR_MANAGE_ALL_PRODUCT_PATHS",
    },
    {
        "path": R010_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "06b803513d79ac0da6ee8bc18d4ae067a828a9a2af355ef60ced995384174664"
        ),
        "byte_length": 14_344,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ71_REWIND_DID_NOT_RESTORE_SEQ67_CURRENT_WORK_ITEM_ID",
    },
    {
        "path": R011_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "9ef4c4574e171d1f3ba79bce01f0578772689382f73684855f1704f5aa34dd33"
        ),
        "byte_length": 14_715,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "ATOMIC_COMMIT_GUARD_REJECTED_THE_EXACT_PROJECTED_CHECKPOINT_PHASE",
    },
    {
        "path": R012_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "b0ddf7ce9d9970e6111f03d6b681ad06683a1f84602896562907c071373598e0"
        ),
        "byte_length": 15_096,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "POSTPUBLICATION_REGRESSIONS_RETAINED_PRECOMPLETION_FIXTURE_ASSUMPTIONS",
    },
    {
        "path": R013_ASSIGNMENT_REL.as_posix(),
        "sha256": (
            "d8e415055f89f318565fb181a6c44336eba899899f1f4376c2da6e80af73fbe5"
        ),
        "byte_length": 15_482,
        "status": "SUPERSEDED_BEFORE_REVIEW_RESULT",
        "reason": "SEQ55_SUCCESSOR_REGRESSION_TRUNCATED_A_VALID_COMPLETION_SUFFIX",
    },
)
REVIEW_DIR = REVIEW_ROOT / "R014"
ASSIGNMENT_REL = REVIEW_DIR / "assignment.json"
RESULT_REL = REVIEW_DIR / "review-result.json"
INDEPENDENT_REL = REVIEW_DIR / "independent-review.json"
ROUND_ID = "WS-FP022-SEQ70-71-COMPLETION-REVIEW-20260814-R014"

ASSIGNER_ID = "codex-root-fp022-completion-transition-assigner-20260814"
ASSIGNER_TASK = "/root"
EXECUTOR_ID = "codex-root-fp022-completion-transition-executor-20260814"
EXECUTOR_TASK = "/root"
REVIEWER_ID = "codex-fp022-completion-transition-reviewer-20260814"
REVIEWER_TASK = "/root/fp022_completion_transition_reviewer"

COMPLETED_REVIEW_PATHS = (ASSIGNMENT_REL, RESULT_REL, INDEPENDENT_REL)
COMPLETED_REVIEW_PINS = {
    ASSIGNMENT_REL: (
        "3b7db286bdfbed30351bfef306786d3a282cc8e6a355d2dac6b32de60c4f68f3",
        15_860,
    ),
    RESULT_REL: (
        "839f39ae8fc99725447f8c3a0d05c0dc2e1751e431281cdf0b1bb82f320b8188",
        15_890,
    ),
    INDEPENDENT_REL: (
        "3353365c08475fe41237974c08932108dd93daf15f015af81def33cd5bf9bab2",
        16_168,
    ),
}
CONTROL_SUCCESSOR_REVIEW_DIR = (
    Path("docs/control/execution/workstream-transitions/seq70-71")
    / "control-successor-reviews/20260815/R001"
)
CONTROL_SUCCESSOR_ASSIGNMENT_REL = (
    CONTROL_SUCCESSOR_REVIEW_DIR / "assignment.json"
)
CONTROL_SUCCESSOR_RESULT_REL = (
    CONTROL_SUCCESSOR_REVIEW_DIR / "review-result.json"
)
CONTROL_SUCCESSOR_INDEPENDENT_REL = (
    CONTROL_SUCCESSOR_REVIEW_DIR / "independent-review.json"
)
CONTROL_SUCCESSOR_ROUND_ID = (
    "WS-FP022-SEQ70-71-CURRENT-ACCEPTANCE-CONTROL-SUCCESSOR-20260815-R001"
)
CONTROL_SUCCESSOR_ASSIGNER_ID = (
    "codex-root-current-acceptance-control-assigner-20260815"
)
CONTROL_SUCCESSOR_ASSIGNER_TASK = "/root"
CONTROL_SUCCESSOR_EXECUTOR_ID = (
    "codex-root-current-acceptance-control-executor-20260815"
)
CONTROL_SUCCESSOR_EXECUTOR_TASK = "/root"
CONTROL_SUCCESSOR_REVIEWER_ID = (
    "codex-control-successor-alt-reviewer-20260815"
)
CONTROL_SUCCESSOR_REVIEWER_TASK = "/root/control_successor_alt"
CONTROL_SUCCESSOR_CHANGED_PATHS = {
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    Path("tests/test_repository_catalogs.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    SCRIPT_REL,
    TEST_REL,
}
CONTROL_SUCCESSOR_EVIDENCE_PATHS = (
    Path(
        "backend/alembic/versions/"
        "202608150001_admin_recovery_expiry_candidate.py"
    ),
    Path(
        "docs/planning/repository-modernization-20260811/"
        "current-security-database-compatibility-binding-20260815.json"
    ),
    Path(
        "tests/"
        "test_run_walksafe_npc_single_admin_recovery_verification_20260813.py"
    ),
    Path("tests/test_walksafe_test_database_preflight.py"),
)
CONTROL_SUCCESSOR_R001_PATHS = (
    CONTROL_SUCCESSOR_ASSIGNMENT_REL,
    CONTROL_SUCCESSOR_RESULT_REL,
    CONTROL_SUCCESSOR_INDEPENDENT_REL,
)
CONTROL_SUCCESSOR_R001_PINS = {
    CONTROL_SUCCESSOR_ASSIGNMENT_REL: (
        "efa407b37c7d13810ca5d4840e22e1abb3207ffbfcb9cb40938db4bce66e621f",
        10_649,
    ),
    CONTROL_SUCCESSOR_RESULT_REL: (
        "faaa9118650a788c04cddc7c9a28c97200ae91f56ab2852c0676172fd2074649",
        10_698,
    ),
    CONTROL_SUCCESSOR_INDEPENDENT_REL: (
        "637c35a8c2747325f5d25ef6d56c47003e03c3e7462318d52e6ce58251af0f10",
        10_999,
    ),
}
CONTROL_SUCCESSOR_R002_DIR = (
    Path("docs/control/execution/workstream-transitions/seq70-71")
    / "control-successor-reviews/20260815/R002"
)
CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL = (
    CONTROL_SUCCESSOR_R002_DIR / "assignment.json"
)
CONTROL_SUCCESSOR_R002_RESULT_REL = (
    CONTROL_SUCCESSOR_R002_DIR / "review-result.json"
)
CONTROL_SUCCESSOR_R002_INDEPENDENT_REL = (
    CONTROL_SUCCESSOR_R002_DIR / "independent-review.json"
)
CONTROL_SUCCESSOR_R002_PATHS = (
    CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL,
    CONTROL_SUCCESSOR_R002_RESULT_REL,
    CONTROL_SUCCESSOR_R002_INDEPENDENT_REL,
)
CONTROL_SUCCESSOR_R002_PINS = {
    CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL: (
        "e3a84b7608e4c1a6f3c94201ff78627d9f4d0686b5105b470de49ada1562b501",
        10_620,
    ),
    CONTROL_SUCCESSOR_R002_RESULT_REL: (
        "6e2f9b27ab3d8a5de78a01aa6ce4f703a0e6eadbe15000042f4a0b6a3225e6e9",
        10_659,
    ),
    CONTROL_SUCCESSOR_R002_INDEPENDENT_REL: (
        "c7dff21f81533fe83ab168fae64eae2a76cc039b528df0a43be1eb1b6b32d7b0",
        10_960,
    ),
}
CONTROL_SUCCESSOR_R002_ROUND_ID = (
    "WS-FP022-SEQ70-71-CURRENT-ACCEPTANCE-CONTROL-SUCCESSOR-20260815-R002"
)
CONTROL_SUCCESSOR_R002_ASSIGNER_ID = (
    "codex-root-current-acceptance-control-r002-assigner-20260815"
)
CONTROL_SUCCESSOR_R002_ASSIGNER_TASK = "/root"
CONTROL_SUCCESSOR_R002_EXECUTOR_ID = (
    "codex-root-current-acceptance-control-r002-executor-20260815"
)
CONTROL_SUCCESSOR_R002_EXECUTOR_TASK = "/root"
CONTROL_SUCCESSOR_R002_REVIEWER_ID = (
    "codex-control-successor-r002-alt-reviewer-20260815"
)
CONTROL_SUCCESSOR_R002_REVIEWER_TASK = "/root/control_successor_r002_alt"
CONTROL_SUCCESSOR_R002_CHANGED_PATHS = {
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    SCRIPT_REL,
    TEST_REL,
}
CONTROL_SUCCESSOR_R002_EVIDENCE_PATHS = (
    Path(
        "backend/alembic/versions/"
        "202608150001_admin_recovery_expiry_candidate.py"
    ),
    Path(
        "docs/planning/repository-modernization-20260811/"
        "current-security-database-compatibility-binding-20260815.json"
    ),
    Path(
        "scripts/"
        "run_walksafe_npc_single_admin_recovery_verification_20260813.py"
    ),
    Path(
        "tests/"
        "test_run_walksafe_npc_single_admin_recovery_verification_20260813.py"
    ),
    Path("tests/test_walksafe_test_database_preflight.py"),
)
CONTROL_SUCCESSOR_R002_MANAGED_CLOSURE_SOURCE_PINS = {
    Path(
        "scripts/"
        "run_walksafe_npc_single_admin_recovery_verification_20260813.py"
    ): {
        "predecessor_byte_length": 194_311,
        "predecessor_sha256": (
            "c63ecfcdba92d93fdeb5a31e8cfdad1fe069d356776b41a942072aa53f85d195"
        ),
        "successor_byte_length": 194_397,
        "successor_sha256": (
            "06b3873f889498523be2503bdf920fc7264c01682173a796605d509ad558a18c"
        ),
    },
    Path(
        "tests/"
        "test_run_walksafe_npc_single_admin_recovery_verification_20260813.py"
    ): {
        "predecessor_byte_length": 38_085,
        "predecessor_sha256": (
            "4d841a53deab3669d29e39a929151728024ea16d0fc4b734f7a7e5640b49756f"
        ),
        "successor_byte_length": 40_942,
        "successor_sha256": (
            "14bd8f6bae65ecd5958a21b05206948f1c75db3f359572406f1c09b0826fcc99"
        ),
    },
}
CONTROL_SUCCESSOR_R003_DIR = (
    Path("docs/control/execution/workstream-transitions/seq70-71")
    / "control-successor-reviews/20260815/R003"
)
CONTROL_SUCCESSOR_R003_ASSIGNMENT_REL = (
    CONTROL_SUCCESSOR_R003_DIR / "assignment.json"
)
CONTROL_SUCCESSOR_R003_RESULT_REL = (
    CONTROL_SUCCESSOR_R003_DIR / "review-result.json"
)
CONTROL_SUCCESSOR_R003_INDEPENDENT_REL = (
    CONTROL_SUCCESSOR_R003_DIR / "independent-review.json"
)
CONTROL_SUCCESSOR_R003_PATHS = (
    CONTROL_SUCCESSOR_R003_ASSIGNMENT_REL,
    CONTROL_SUCCESSOR_R003_RESULT_REL,
    CONTROL_SUCCESSOR_R003_INDEPENDENT_REL,
)
CONTROL_SUCCESSOR_R003_PINS = {
    CONTROL_SUCCESSOR_R003_ASSIGNMENT_REL: (
        "51c0350e884ecc386619c0c0072fc88b95b3141a3fe6a9e35faa5ea57061ff19",
        13_074,
    ),
    CONTROL_SUCCESSOR_R003_RESULT_REL: (
        "178b53e19022631320c208d245e0b058e7d8536830baf761f5196f25135844a9",
        13_113,
    ),
    CONTROL_SUCCESSOR_R003_INDEPENDENT_REL: (
        "8babb6664c7cb4b18fe2bc5e56af7d032d21ce33d6b7072846b4670d3e36daa6",
        13_414,
    ),
}
CONTROL_SUCCESSOR_R003_ROUND_ID = (
    "WS-FP022-SEQ70-71-CURRENT-ACCEPTANCE-CONTROL-SUCCESSOR-20260815-R003"
)
CONTROL_SUCCESSOR_R003_ASSIGNER_ID = (
    "codex-root-current-acceptance-control-r003-assigner-20260815"
)
CONTROL_SUCCESSOR_R003_ASSIGNER_TASK = "/root"
CONTROL_SUCCESSOR_R003_EXECUTOR_ID = (
    "codex-root-current-acceptance-control-r003-executor-20260815"
)
CONTROL_SUCCESSOR_R003_EXECUTOR_TASK = "/root"
CONTROL_SUCCESSOR_R003_REVIEWER_ID = (
    "codex-control-successor-r003-alt-reviewer-20260815"
)
CONTROL_SUCCESSOR_R003_REVIEWER_TASK = "/root/control_successor_r003_alt"
CONTROL_SUCCESSOR_R003_CHANGED_PATHS = {
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    SCRIPT_REL,
    TEST_REL,
}
CONTROL_SUCCESSOR_R003_ADDED_SOURCE_PATHS = (
    Path(
        "backend/alembic/versions/"
        "202608150001_admin_recovery_expiry_candidate.py"
    ),
    Path(
        "backend/alembic/versions/"
        "202608150002_admin_recovery_expired_proof.py"
    ),
)
CONTROL_SUCCESSOR_R003_AMENDMENT_PATHS = (
    Path("backend/app/api/health.py"),
    Path("backend/app/services/admin_device_proof.py"),
    Path("backend/tests/test_admin_device_proof.py"),
    Path("backend/tests/test_admin_runtime_acl_hardening.py"),
    Path("backend/tests/test_admin_security.py"),
    Path("backend/tests/test_admin_credential_issuer_binding.py"),
    Path("backend/tests/test_fp046_postgres_integration.py"),
)
CONTROL_SUCCESSOR_R003_COMPATIBILITY_REL = Path(
    "docs/planning/repository-modernization-20260811/"
    "current-security-database-compatibility-binding-20260815.json"
)
CONTROL_SUCCESSOR_R003_EVIDENCE_PATHS = (
    CONTROL_SUCCESSOR_R003_COMPATIBILITY_REL,
    *CONTROL_SUCCESSOR_R003_ADDED_SOURCE_PATHS,
    *CONTROL_SUCCESSOR_R003_AMENDMENT_PATHS,
    Path(
        "scripts/"
        "run_walksafe_npc_single_admin_recovery_verification_20260813.py"
    ),
    Path(
        "tests/"
        "test_run_walksafe_npc_single_admin_recovery_verification_20260813.py"
    ),
)
CONTROL_SUCCESSOR_R003_EVIDENCE_PINS = {
    CONTROL_SUCCESSOR_R003_COMPATIBILITY_REL: (
        "2931c49d5459d8cf023eb1925f2c0bdf89ed90f567f51e1feb2a71ad68b4a0e8",
        4_240,
    ),
    CONTROL_SUCCESSOR_R003_ADDED_SOURCE_PATHS[0]: (
        "45f335f0309fe45af107a1b4814efdcf3b3460c52400016954e7b05f804a593a",
        14_917,
    ),
    CONTROL_SUCCESSOR_R003_ADDED_SOURCE_PATHS[1]: (
        "e90f1cf797b6e07efff4828818b15410e8ec0b1597435faafef02442144fc590",
        31_645,
    ),
    CONTROL_SUCCESSOR_R003_AMENDMENT_PATHS[0]: (
        "5d4f29aae15af069dff62a466b4dbebc88a886f3322ee5de5aa0529639be3401",
        19_738,
    ),
    CONTROL_SUCCESSOR_R003_AMENDMENT_PATHS[1]: (
        "46126623c650f4eab00062df02c119aa4bf7ed60c77d7e1afa71a94e613cb99a",
        41_147,
    ),
    CONTROL_SUCCESSOR_R003_AMENDMENT_PATHS[2]: (
        "0276194d37cefa955cca09933ec2fe7f236f6d67c5199711228adc06d6d499cb",
        87_799,
    ),
    CONTROL_SUCCESSOR_R003_AMENDMENT_PATHS[3]: (
        "4ba8105bb51838bcc148e669adb87fce9abdef5064430e5ea31e567397427977",
        51_596,
    ),
    CONTROL_SUCCESSOR_R003_AMENDMENT_PATHS[4]: (
        "97c08b17800d76c720a0fa615fee9025e2ad57c5278e01b2d04c901dc49f9acd",
        189_546,
    ),
    CONTROL_SUCCESSOR_R003_AMENDMENT_PATHS[5]: (
        "a6c60a4b32b83bbd49a1a711c12526e24a93ef2687bd883b8b080c47748ae602",
        14_953,
    ),
    CONTROL_SUCCESSOR_R003_AMENDMENT_PATHS[6]: (
        "fba65428ac42eb506b6952f18319fd7b33e00695cd26b7278c2edda9699061e2",
        89_608,
    ),
    CONTROL_SUCCESSOR_R003_EVIDENCE_PATHS[-2]: (
        "418aff95c40ee3485e1a003008175a3ff4a8f0d3c4e4e0926f9f1f3dcac62c7c",
        194_397,
    ),
    CONTROL_SUCCESSOR_R003_EVIDENCE_PATHS[-1]: (
        "9487937c2c9cd972a005b675ee8122630b0fe6b5bddf20a976e5351b258b3c7d",
        41_373,
    ),
}
CONTROL_SUCCESSOR_R003_MANAGED_CLOSURE_SOURCE_PINS = {
    Path(
        "scripts/"
        "run_walksafe_npc_single_admin_recovery_verification_20260813.py"
    ): {
        "predecessor_byte_length": 194_397,
        "predecessor_sha256": (
            "06b3873f889498523be2503bdf920fc7264c01682173a796605d509ad558a18c"
        ),
        "successor_byte_length": 194_397,
        "successor_sha256": (
            "418aff95c40ee3485e1a003008175a3ff4a8f0d3c4e4e0926f9f1f3dcac62c7c"
        ),
    },
    Path(
        "tests/"
        "test_run_walksafe_npc_single_admin_recovery_verification_20260813.py"
    ): {
        "predecessor_byte_length": 40_942,
        "predecessor_sha256": (
            "14bd8f6bae65ecd5958a21b05206948f1c75db3f359572406f1c09b0826fcc99"
        ),
        "successor_byte_length": 41_373,
        "successor_sha256": (
            "9487937c2c9cd972a005b675ee8122630b0fe6b5bddf20a976e5351b258b3c7d"
        ),
    },
}
CONTROL_SUCCESSOR_R004_DIR = (
    Path("docs/control/execution/workstream-transitions/seq70-71")
    / "control-successor-reviews/20260815/R004"
)
CONTROL_SUCCESSOR_R004_ASSIGNMENT_REL = (
    CONTROL_SUCCESSOR_R004_DIR / "assignment.json"
)
CONTROL_SUCCESSOR_R004_RESULT_REL = (
    CONTROL_SUCCESSOR_R004_DIR / "review-result.json"
)
CONTROL_SUCCESSOR_R004_INDEPENDENT_REL = (
    CONTROL_SUCCESSOR_R004_DIR / "independent-review.json"
)
CONTROL_SUCCESSOR_R004_PATHS = (
    CONTROL_SUCCESSOR_R004_ASSIGNMENT_REL,
    CONTROL_SUCCESSOR_R004_RESULT_REL,
    CONTROL_SUCCESSOR_R004_INDEPENDENT_REL,
)
CONTROL_SUCCESSOR_R004_PINS = {
    CONTROL_SUCCESSOR_R004_ASSIGNMENT_REL: (
        "6e7e0b2e3d356307c84f5126766c67cd5d3ca39f585385180e27a93d242d314f",
        9_191,
    ),
    CONTROL_SUCCESSOR_R004_RESULT_REL: (
        "8271a124974f0a87412afda30c3c04cd5f7f20693bfe2b78038953fb1f766d9b",
        9_208,
    ),
    CONTROL_SUCCESSOR_R004_INDEPENDENT_REL: (
        "364c83ab29a8ff96fba1c3a7c5b9340115bb1d2f62a94d30188ebff2da03fd90",
        9_508,
    ),
}
CONTROL_SUCCESSOR_R004_ROUND_ID = (
    "WS-FP022-SEQ70-71-CURRENT-ACCEPTANCE-CONTROL-SUCCESSOR-20260815-R004"
)
CONTROL_SUCCESSOR_R004_ASSIGNER_ID = (
    "codex-root-current-acceptance-control-r004-assigner-20260815"
)
CONTROL_SUCCESSOR_R004_ASSIGNER_TASK = "/root"
CONTROL_SUCCESSOR_R004_EXECUTOR_ID = (
    "codex-control-successor-r004-implementation-executor-20260815"
)
CONTROL_SUCCESSOR_R004_EXECUTOR_TASK = "/root/r004_implementation"
CONTROL_SUCCESSOR_R004_REVIEWER_ID = (
    "codex-control-successor-r004-alt-reviewer-20260815"
)
CONTROL_SUCCESSOR_R004_REVIEWER_TASK = "/root/control_successor_r004_alt"
CONTROL_SUCCESSOR_R004_CHANGED_PATHS = {
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    SCRIPT_REL,
    TEST_REL,
}
CONTROL_SUCCESSOR_R004_EVIDENCE_PATHS = (
    Path(
        "tests/"
        "test_run_walksafe_npc_single_admin_recovery_verification_20260813.py"
    ),
)
CONTROL_SUCCESSOR_R004_EVIDENCE_PINS = {
    CONTROL_SUCCESSOR_R004_EVIDENCE_PATHS[0]: (
        "550f959c13307f16038357e6de0e209a33c67cf027dba9e85026a60e90fc5a18",
        41_794,
    ),
}
CONTROL_SUCCESSOR_R004_MANAGED_CLOSURE_SOURCE_PINS = {
    CONTROL_SUCCESSOR_R004_EVIDENCE_PATHS[0]: {
        "predecessor_byte_length": 41_373,
        "predecessor_sha256": (
            "9487937c2c9cd972a005b675ee8122630b0fe6b5bddf20a976e5351b258b3c7d"
        ),
        "successor_byte_length": 41_794,
        "successor_sha256": (
            "550f959c13307f16038357e6de0e209a33c67cf027dba9e85026a60e90fc5a18"
        ),
    },
}
CONTROL_SUCCESSOR_R005_DIR = (
    Path("docs/control/execution/workstream-transitions/seq70-71")
    / "control-successor-reviews/20260815/R005"
)
CONTROL_SUCCESSOR_R005_ASSIGNMENT_REL = (
    CONTROL_SUCCESSOR_R005_DIR / "assignment.json"
)
CONTROL_SUCCESSOR_R005_RESULT_REL = (
    CONTROL_SUCCESSOR_R005_DIR / "review-result.json"
)
CONTROL_SUCCESSOR_R005_INDEPENDENT_REL = (
    CONTROL_SUCCESSOR_R005_DIR / "independent-review.json"
)
CONTROL_SUCCESSOR_R005_PATHS = (
    CONTROL_SUCCESSOR_R005_ASSIGNMENT_REL,
    CONTROL_SUCCESSOR_R005_RESULT_REL,
    CONTROL_SUCCESSOR_R005_INDEPENDENT_REL,
)
CONTROL_SUCCESSOR_R005_PINS = {
    CONTROL_SUCCESSOR_R005_ASSIGNMENT_REL: (
        "861ea47e1948efe71e344d69f323dfb2c1d3406576c20d08feba8cb887f5e328",
        9_882,
    ),
    CONTROL_SUCCESSOR_R005_RESULT_REL: (
        "53c534fb0c6bd4caed10d02871ad4c590fa232d480ee0d727bd93bb100debd0e",
        9_892,
    ),
    CONTROL_SUCCESSOR_R005_INDEPENDENT_REL: (
        "5eee7aa7573e9fb5d27c2e8dc12eca172988d45b9c6024b8a5800158e4834638",
        10_192,
    ),
}
CONTROL_SUCCESSOR_R005_ROUND_ID = (
    "WS-FP022-SEQ70-71-CURRENT-ACCEPTANCE-CONTROL-SUCCESSOR-20260815-R005"
)
CONTROL_SUCCESSOR_R005_ASSIGNER_ID = (
    "codex-root-current-acceptance-control-r005-assigner-20260815"
)
CONTROL_SUCCESSOR_R005_ASSIGNER_TASK = "/root"
CONTROL_SUCCESSOR_R005_EXECUTOR_ID = (
    "codex-control-successor-r005-implementation-executor-20260815"
)
CONTROL_SUCCESSOR_R005_EXECUTOR_TASK = "/root/fix_continuation_bootstrap"
CONTROL_SUCCESSOR_R005_REVIEWER_ID = (
    "codex-control-successor-r005-independent-reviewer-20260815"
)
CONTROL_SUCCESSOR_R005_REVIEWER_TASK = "/root/r005_final_review"
CONTROL_SUCCESSOR_R005_CHANGED_PATHS = {
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    SCRIPT_REL,
    TEST_REL,
}
CONTROL_SUCCESSOR_R006_DIR = (
    Path("docs/control/execution/workstream-transitions/seq70-71")
    / "control-successor-reviews/20260815/R006"
)
CONTROL_SUCCESSOR_R006_ASSIGNMENT_REL = (
    CONTROL_SUCCESSOR_R006_DIR / "assignment.json"
)
CONTROL_SUCCESSOR_R006_RESULT_REL = (
    CONTROL_SUCCESSOR_R006_DIR / "review-result.json"
)
CONTROL_SUCCESSOR_R006_INDEPENDENT_REL = (
    CONTROL_SUCCESSOR_R006_DIR / "independent-review.json"
)
CONTROL_SUCCESSOR_R006_PATHS = (
    CONTROL_SUCCESSOR_R006_ASSIGNMENT_REL,
    CONTROL_SUCCESSOR_R006_RESULT_REL,
    CONTROL_SUCCESSOR_R006_INDEPENDENT_REL,
)
CONTROL_SUCCESSOR_R006_PINS = {
    CONTROL_SUCCESSOR_R006_ASSIGNMENT_REL: (
        "27ec1a8abc5405caddfcef9ed62dda3edb21db61e2411acc6bc8a2f1aa7bc3fa",
        8_779,
    ),
    CONTROL_SUCCESSOR_R006_RESULT_REL: (
        "e3b615955562bb690ab0db8864f123a28729c5ebffde732d22ec1d864ebf2ab2",
        8_789,
    ),
    CONTROL_SUCCESSOR_R006_INDEPENDENT_REL: (
        "01ede84b9f590fbf698317b36fd00a1ebe446f44ef589013874a1be9b5d0efa8",
        9_089,
    ),
}
CONTROL_SUCCESSOR_R006_ROUND_ID = (
    "WS-FP022-SEQ70-71-CURRENT-ACCEPTANCE-CONTROL-SUCCESSOR-20260815-R006"
)
CONTROL_SUCCESSOR_R006_ASSIGNER_ID = (
    "codex-root-current-acceptance-control-r006-assigner-20260815"
)
CONTROL_SUCCESSOR_R006_ASSIGNER_TASK = "/root"
CONTROL_SUCCESSOR_R006_EXECUTOR_ID = (
    "codex-control-successor-r006-implementation-executor-20260815"
)
CONTROL_SUCCESSOR_R006_EXECUTOR_TASK = "/root/fix_continuation_bootstrap"
CONTROL_SUCCESSOR_R006_REVIEWER_ID = (
    "codex-control-successor-r006-independent-reviewer-20260815"
)
CONTROL_SUCCESSOR_R006_REVIEWER_TASK = "/root/r006_final_review"
CONTROL_SUCCESSOR_R006_CHANGED_PATHS = {
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    SCRIPT_REL,
    TEST_REL,
}
CONTROL_SUCCESSOR_R007_DIR = (
    Path("docs/control/execution/workstream-transitions/seq70-71")
    / "control-successor-reviews/20260815/R007"
)
CONTROL_SUCCESSOR_R007_ASSIGNMENT_REL = (
    CONTROL_SUCCESSOR_R007_DIR / "assignment.json"
)
CONTROL_SUCCESSOR_R007_RESULT_REL = (
    CONTROL_SUCCESSOR_R007_DIR / "review-result.json"
)
CONTROL_SUCCESSOR_R007_INDEPENDENT_REL = (
    CONTROL_SUCCESSOR_R007_DIR / "independent-review.json"
)
CONTROL_SUCCESSOR_R007_PATHS = (
    CONTROL_SUCCESSOR_R007_ASSIGNMENT_REL,
    CONTROL_SUCCESSOR_R007_RESULT_REL,
    CONTROL_SUCCESSOR_R007_INDEPENDENT_REL,
)
CONTROL_SUCCESSOR_R007_PINS = {
    CONTROL_SUCCESSOR_R007_ASSIGNMENT_REL: (
        "7acb9a1eb5fcd79da3d0532daca0a7153f0c2a6ecb45922c957a66e827a9714c",
        9_908,
    ),
    CONTROL_SUCCESSOR_R007_RESULT_REL: (
        "c744f4066daef97a14879ed1407b16982211ef0ae581da04322c110d83268471",
        9_918,
    ),
    CONTROL_SUCCESSOR_R007_INDEPENDENT_REL: (
        "bee57d7f30fb1d8bd7607baa001cde76ee0f5df5a1b0a88efe06362cc0c48e13",
        10_218,
    ),
}
CONTROL_SUCCESSOR_R007_ROUND_ID = (
    "WS-FP022-SEQ70-71-CURRENT-ACCEPTANCE-CONTROL-SUCCESSOR-20260815-R007"
)
CONTROL_SUCCESSOR_R007_ASSIGNER_ID = (
    "codex-root-current-acceptance-control-r007-assigner-20260815"
)
CONTROL_SUCCESSOR_R007_ASSIGNER_TASK = "/root"
CONTROL_SUCCESSOR_R007_EXECUTOR_ID = (
    "codex-control-successor-r007-implementation-executor-20260815"
)
CONTROL_SUCCESSOR_R007_EXECUTOR_TASK = "/root/fix_continuation_bootstrap"
CONTROL_SUCCESSOR_R007_REVIEWER_ID = (
    "codex-control-successor-r007-independent-reviewer-20260815"
)
CONTROL_SUCCESSOR_R007_REVIEWER_TASK = "/root/r007_final_review"
CONTROL_SUCCESSOR_R007_CHANGED_PATHS = {
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    SCRIPT_REL,
    TEST_REL,
}
CONTROL_SUCCESSOR_R008_DIR = (
    Path("docs/control/execution/workstream-transitions/seq70-71")
    / "control-successor-reviews/20260815/R008"
)
CONTROL_SUCCESSOR_R008_ASSIGNMENT_REL = (
    CONTROL_SUCCESSOR_R008_DIR / "assignment.json"
)
CONTROL_SUCCESSOR_R008_RESULT_REL = (
    CONTROL_SUCCESSOR_R008_DIR / "review-result.json"
)
CONTROL_SUCCESSOR_R008_INDEPENDENT_REL = (
    CONTROL_SUCCESSOR_R008_DIR / "independent-review.json"
)
CONTROL_SUCCESSOR_R008_PATHS = (
    CONTROL_SUCCESSOR_R008_ASSIGNMENT_REL,
    CONTROL_SUCCESSOR_R008_RESULT_REL,
    CONTROL_SUCCESSOR_R008_INDEPENDENT_REL,
)
CONTROL_SUCCESSOR_R008_ROUND_ID = (
    "WS-FP022-SEQ70-71-CURRENT-ACCEPTANCE-CONTROL-SUCCESSOR-20260815-R008"
)
CONTROL_SUCCESSOR_R008_ASSIGNER_ID = (
    "codex-root-current-acceptance-control-r008-assigner-20260815"
)
CONTROL_SUCCESSOR_R008_ASSIGNER_TASK = "/root"
CONTROL_SUCCESSOR_R008_EXECUTOR_ID = (
    "codex-control-successor-r008-implementation-executor-20260815"
)
CONTROL_SUCCESSOR_R008_EXECUTOR_TASK = "/root/r008_control_tail_impl"
CONTROL_SUCCESSOR_R008_REVIEWER_ID = (
    "codex-control-successor-r008-independent-reviewer-20260815"
)
CONTROL_SUCCESSOR_R008_REVIEWER_TASK = "/root/r008_final_review"
CONTROL_SUCCESSOR_R008_CHANGED_PATHS = {
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("scripts/generate_repository_catalogs.py"),
    Path("tests/test_repository_catalogs.py"),
    SCRIPT_REL,
    TEST_REL,
}
CONTROL_SUCCESSOR_R008_ADDED_CONTROL_PATHS = (
    Path("scripts/build_walksafe_fp046_gap_backlog_r029_candidate_20260815.py"),
    Path("tests/test_build_walksafe_fp046_gap_backlog_r029_candidate_20260815.py"),
    Path("scripts/apply_walksafe_fp046_npc_r002_reopen_20260815.py"),
    Path("tests/test_apply_walksafe_fp046_npc_r002_reopen_20260815.py"),
    Path("scripts/build_walksafe_fp046_gap_backlog_r029_20260815.py"),
    Path("tests/test_build_walksafe_fp046_gap_backlog_r029_20260815.py"),
    Path("scripts/apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py"),
    Path("tests/test_apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py"),
)
CONTROL_SUCCESSOR_R008_COHORT_PATHS = (
    *CONTROL_PATHS,
    *CONTROL_SUCCESSOR_R008_ADDED_CONTROL_PATHS,
)

PRODUCT_EXECUTOR_ID = "codex-fp022-navigation-implementer-20260814"
PRODUCT_EXECUTOR_TASK = "/root"
PRODUCT_REVIEWER_ID = "codex-fp022-navigation-internal-reviewer-20260814"
PRODUCT_REVIEWER_TASK = "/root/fp022_evidence_independent_reviewer"

PROJECTED_TRANSITION = {
    "canonical_bindings_updated": {
        "sequence": 70,
        "event_id": completion.UPDATE_EVENT_ID,
        "event_type": "CANONICAL_BINDINGS_UPDATED",
        "from_status": "IN_PROGRESS",
        "to_status": "IN_PROGRESS",
        "status_changes": {},
        "transition_review_binding_required": True,
    },
    "goal_completed": {
        "sequence": 71,
        "event_id": completion.COMPLETION_EVENT_ID,
        "event_type": "GOAL_COMPLETED",
        "subject_goal_id": GOAL_ID,
        "from_status": "IN_PROGRESS",
        "to_status": "COMPLETE_AT_TARGET",
        "status_changes": {GOAL_ID: "COMPLETE_AT_TARGET"},
    },
    "final_focus_goal_id": completion.PARENT_GOAL_ID,
    "final_status": "COMPLETE_AT_TARGET",
    "next_policy_id": "FP-023",
    "next_priority_rank": 25,
}

BOUNDARY = {
    "product_implementation_credit_added": 0,
    "artifact_completion_credit_added": 0,
    "formal_test_credit_added": 0,
    "actual_device_credit_added": 0,
    "external_review_credit_added": 0,
    "deployment_credit_added": 0,
    "approval_credit_added": 0,
    "release_credit_added": 0,
    "external_independence_claimed": False,
    "release_status": "NOT_ELIGIBLE",
}


@dataclass(frozen=True)
class ReviewContext:
    root: Path
    start_review_bindings: tuple[dict[str, Any], ...]
    completion_evidence_bindings: tuple[dict[str, Any], ...]
    superseded_assignment_bindings: tuple[dict[str, Any], ...]
    control_code_cohort: tuple[dict[str, Any], ...]
    control_code_cohort_sha256: str


@dataclass(frozen=True)
class ControlSuccessorR002Context:
    root: Path
    current: ReviewContext
    predecessor: ReviewContext
    predecessor_review_bindings: tuple[dict[str, Any], ...]
    control_code_successors: tuple[dict[str, Any], ...]
    acceptance_evidence_sources: tuple[dict[str, Any], ...]
    managed_closure_source_successors: tuple[dict[str, Any], ...]


@dataclass(frozen=True)
class ControlSuccessorR003Context:
    root: Path
    current: ReviewContext
    predecessor: ReviewContext
    predecessor_review_bindings: tuple[dict[str, Any], ...]
    control_code_successors: tuple[dict[str, Any], ...]
    acceptance_evidence_sources: tuple[dict[str, Any], ...]
    added_sources: tuple[dict[str, Any], ...]
    predecessor_managed_closure_source_successors: tuple[dict[str, Any], ...]
    managed_closure_source_successors: tuple[dict[str, Any], ...]


@dataclass(frozen=True)
class ControlSuccessorR004Context:
    root: Path
    current: ReviewContext
    predecessor: ControlSuccessorR003Context
    predecessor_review_bindings: tuple[dict[str, Any], ...]
    control_code_successors: tuple[dict[str, Any], ...]
    acceptance_evidence_sources: tuple[dict[str, Any], ...]
    predecessor_managed_closure_source_successors: tuple[dict[str, Any], ...]
    managed_closure_source_successors: tuple[dict[str, Any], ...]


@dataclass(frozen=True)
class ControlSuccessorR005Context:
    root: Path
    current: ReviewContext
    predecessor: ControlSuccessorR004Context
    predecessor_review_bindings: tuple[dict[str, Any], ...]
    control_code_successors: tuple[dict[str, Any], ...]


@dataclass(frozen=True)
class ControlSuccessorR006Context:
    root: Path
    current: ReviewContext
    predecessor: ControlSuccessorR005Context
    predecessor_review_bindings: tuple[dict[str, Any], ...]
    control_code_successors: tuple[dict[str, Any], ...]


@dataclass(frozen=True)
class ControlSuccessorR007Context:
    root: Path
    current: ReviewContext
    predecessor: ControlSuccessorR006Context
    predecessor_review_bindings: tuple[dict[str, Any], ...]
    control_code_successors: tuple[dict[str, Any], ...]


@dataclass(frozen=True)
class ControlSuccessorR008Context:
    root: Path
    current: ReviewContext
    predecessor: ControlSuccessorR007Context
    predecessor_review_bindings: tuple[dict[str, Any], ...]
    control_code_successors: tuple[dict[str, Any], ...]
    added_control_code_bindings: tuple[dict[str, Any], ...]


def _safe_path(root: Path, relative: Path) -> Path:
    require(
        not relative.is_absolute() and ".." not in relative.parts,
        f"unsafe review path: {relative}",
    )
    root = root.resolve(strict=True)
    current = root
    for part in relative.parts:
        current = current / part
        require(not current.is_symlink(), f"symlink review path rejected: {relative}")
    require(current.is_file(), f"review input is missing: {relative}")
    return current


def _raw(root: Path, relative: Path) -> bytes:
    return _safe_path(root, relative).read_bytes()


def _document(root: Path, relative: Path) -> tuple[dict[str, Any], bytes]:
    raw = _raw(root, relative)
    return strict_json_bytes(raw, relative.as_posix()), raw


def _binding(relative: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": relative.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
    }


def _bind_paths(root: Path, paths: Sequence[Path]) -> tuple[dict[str, Any], ...]:
    bindings = tuple(_binding(path, _raw(root, path)) for path in paths)
    require(
        len(bindings) == len(paths)
        and len({row["path"] for row in bindings}) == len(paths),
        "review binding cohort is not exact",
    )
    return bindings


def _bind_pinned_paths(
    root: Path,
    paths: Sequence[Path],
    pins: Mapping[Path, tuple[str, int]],
    label: str,
) -> tuple[dict[str, Any], ...]:
    require(
        tuple(pins) == tuple(paths),
        f"{label} pin inventory differs",
    )
    bindings = _bind_paths(root, paths)
    for path, binding in zip(paths, bindings, strict=True):
        expected_sha256, expected_length = pins[path]
        require(
            binding["sha256"] == expected_sha256
            and binding["byte_length"] == expected_length,
            f"{label} differs: {path}",
        )
    return bindings


def _parse_time(value: Any, label: str) -> datetime:
    require(type(value) is str and bool(value), f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise ReviewError(f"{label} is not ISO-8601") from exc
    require(
        parsed.tzinfo is not None and parsed.isoformat() == value,
        f"{label} is not canonical offset ISO-8601",
    )
    return parsed


def _require_source(root: Path, *, require_exact: bool) -> None:
    raw = _raw(root, CHECKPOINT_REL)
    exact = (
        len(raw) == SOURCE_CHECKPOINT["byte_length"]
        and bytes_sha256(raw) == SOURCE_CHECKPOINT["sha256"]
    )
    if require_exact:
        require(exact, "FP-022 completion review source is not exact seq69")
    checkpoint = strict_json_bytes(raw, CHECKPOINT_REL.as_posix())
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    require(
        type(history) is list and len(history) >= SOURCE_CHECKPOINT["sequence"],
        "FP-022 completion review seq69 lineage is missing",
    )
    tail = history[SOURCE_CHECKPOINT["sequence"] - 1]
    require(
        type(tail) is dict
        and tail.get("sequence") == SOURCE_CHECKPOINT["sequence"]
        and tail.get("event_id") == SOURCE_CHECKPOINT["tail_event_id"]
        and tail.get("event_sha256") == SOURCE_CHECKPOINT["tail_event_sha256"]
        and continuation.event_sha256(tail)
        == SOURCE_CHECKPOINT["tail_event_sha256"],
        "FP-022 completion review seq69 event differs",
    )
    if len(history) == SOURCE_CHECKPOINT["sequence"]:
        require(exact, "live seq69 checkpoint bytes differ")


def _frozen_start_review(
    root: Path = ROOT,
) -> tuple[start_review.ReviewContext, dict[Path, bytes]]:
    root = root.resolve(strict=True)
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative in START_REVIEW_PATHS:
        document, raw = _document(root, relative)
        expected_sha256, expected_length = START_REVIEW_PINS[relative]
        require(
            bytes_sha256(raw) == expected_sha256 and len(raw) == expected_length,
            f"exact FP-022 seq68/69 R031 review differs: {relative}",
        )
        documents[relative] = document
        raw_by_path[relative] = raw

    assignment = documents[start_review.ASSIGNMENT_REL]
    result = documents[start_review.RESULT_REL]
    scope = assignment.get("review_scope")
    require(type(scope) is dict, "FP-022 seq68/69 R031 scope is missing")
    frozen_context = start_review.ReviewContext(
        root=root,
        predecessor_review_bindings=tuple(scope["predecessor_review_bindings"]),
        superseded_assignment_bindings=tuple(
            scope["superseded_review_assignments"]
        ),
        contract_r001_evidence=copy.deepcopy(
            scope["superseded_start_gate_contract_evidence"]
        ),
        contract_r002_evidence=copy.deepcopy(
            scope["successor_start_gate_contract_evidence"]
        ),
        control_code_cohort=tuple(scope["reviewed_control_code_cohort"]),
        control_code_cohort_sha256=scope[
            "reviewed_control_code_cohort_sha256"
        ],
    )
    start_review.validate_review_result(
        result,
        raw_by_path[start_review.RESULT_REL],
        assignment,
        raw_by_path[start_review.ASSIGNMENT_REL],
        frozen_context,
    )
    require(
        start_review.build_independent_review(
            frozen_context,
            assignment,
            raw_by_path[start_review.ASSIGNMENT_REL],
            result,
            raw_by_path[start_review.RESULT_REL],
        ).encode()
        == raw_by_path[start_review.INDEPENDENT_REL],
        "exact FP-022 seq68/69 R031 independent review differs",
    )
    return frozen_context, raw_by_path


def prepare_frozen_start_review_context(
    root: Path = ROOT,
) -> start_review.ReviewContext:
    context, _ = _frozen_start_review(root)
    return context


def prepare_frozen_start_review(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    _, raw_by_path = _frozen_start_review(root)
    return tuple(_binding(path, raw_by_path[path]) for path in START_REVIEW_PATHS)


def _validate_product_chain(root: Path) -> tuple[dict[str, Any], ...]:
    initial_paths = (
        *(lane.log_rel for lane in evidence.LANES),
        evidence.IMPLEMENTATION_REL,
        evidence.OBSERVATIONS_REL,
        evidence.VERIFICATION_REL,
    )
    initial = {
        path: _raw(root, path).decode("utf-8") for path in initial_paths
    }
    evidence.validate_evidence_outputs(
        initial,
        root=root,
        source_groups=evidence.IMPLEMENTATION_SOURCE_GROUPS,
        authority=evidence.validate_authority(root),
        executor_actor_id=PRODUCT_EXECUTOR_ID,
        executor_task=PRODUCT_EXECUTOR_TASK,
    )

    r028 = {path: _raw(root, path) for path in evidence.R028_PATHS}
    expected_r028 = r028_builder.build_outputs(root)
    require(
        all(r028[path] == expected_r028[path].encode() for path in evidence.R028_PATHS),
        "FP-022 exact R028 builder outputs differ",
    )
    evidence.validate_r028_outputs(r028)

    successor = {
        path: _raw(root, path).decode("utf-8")
        for path in (evidence.SUCCESSOR_REL, evidence.REVIEW_SUBJECT_REL)
    }
    evidence.validate_successor_outputs(
        successor,
        evidence_outputs=initial,
        r028_outputs=r028,
        executor_actor_id=PRODUCT_EXECUTOR_ID,
        executor_task=PRODUCT_EXECUTOR_TASK,
        required_reviewer_actor_id=PRODUCT_REVIEWER_ID,
        required_reviewer_task=PRODUCT_REVIEWER_TASK,
    )
    independent_raw = _raw(root, evidence.INDEPENDENT_REVIEW_REL)
    evidence.validate_independent_review(
        independent_raw,
        subject_text=successor[evidence.REVIEW_SUBJECT_REL],
        executor_actor_id=PRODUCT_EXECUTOR_ID,
        executor_task=PRODUCT_EXECUTOR_TASK,
        reviewer_actor_id=PRODUCT_REVIEWER_ID,
        reviewer_task=PRODUCT_REVIEWER_TASK,
    )
    completion_output = {
        evidence.COMPLETION_REL: _raw(root, evidence.COMPLETION_REL).decode("utf-8")
    }
    evidence.validate_completion_output(
        completion_output,
        evidence_outputs=initial,
        successor_outputs=successor,
        r028_outputs=r028,
        independent_review_raw=independent_raw,
        executor_actor_id=PRODUCT_EXECUTOR_ID,
        executor_task=PRODUCT_EXECUTOR_TASK,
        reviewer_actor_id=PRODUCT_REVIEWER_ID,
        reviewer_task=PRODUCT_REVIEWER_TASK,
    )
    return _bind_paths(root, EVIDENCE_PATHS)


def _require_transition_constants() -> None:
    require(
        completion.SOURCE_CHECKPOINT_RAW_SHA256 == SOURCE_CHECKPOINT["sha256"]
        and completion.SOURCE_CHECKPOINT_BYTE_COUNT
        == SOURCE_CHECKPOINT["byte_length"]
        and completion.SOURCE_EVENT_ID == SOURCE_CHECKPOINT["tail_event_id"]
        and completion.SOURCE_EVENT_SHA256
        == SOURCE_CHECKPOINT["tail_event_sha256"]
        and completion.UPDATE_EVENT_ID
        == PROJECTED_TRANSITION["canonical_bindings_updated"]["event_id"]
        and completion.COMPLETION_EVENT_ID
        == PROJECTED_TRANSITION["goal_completed"]["event_id"]
        and completion.GOAL_ID == GOAL_ID,
        "FP-022 seq70/71 sealed transition constants differ",
    )
    require(
        completion.CHANGED_ROLES
        == ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP", completion.COMPLETION_ROLE]
        and completion.PRODUCED_ROLES
        == ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"]
        and completion.TRANSITION_REVIEW_PATHS
        == (ASSIGNMENT_REL, RESULT_REL, INDEPENDENT_REL),
        "FP-022 seq70/71 review or canonical-role contract differs",
    )


def prepare_review_context(
    root: Path = ROOT, *, require_exact_source: bool = False
) -> ReviewContext:
    root = root.resolve(strict=True)
    _require_source(root, require_exact=require_exact_source)
    start = prepare_frozen_start_review(root)
    evidence_bindings = _validate_product_chain(root)
    _require_transition_constants()
    superseded = tuple(copy.deepcopy(SUPERSEDED_ASSIGNMENTS))
    for row in superseded:
        relative = Path(row["path"])
        raw = _raw(root, relative)
        require(
            _binding(relative, raw)
            == {key: row[key] for key in ("path", "sha256", "byte_length")},
            f"superseded completion review assignment differs: {relative}",
        )
        for output_name in ("review-result.json", "independent-review.json"):
            require(
                not os.path.lexists(root / relative.parent / output_name),
                f"superseded completion review produced an output: {relative.parent / output_name}",
            )
    controls = _bind_paths(root, CONTROL_PATHS)
    return ReviewContext(
        root=root,
        start_review_bindings=start,
        completion_evidence_bindings=evidence_bindings,
        superseded_assignment_bindings=superseded,
        control_code_cohort=controls,
        control_code_cohort_sha256=object_sha256(list(controls)),
    )


def _scope(context: ReviewContext) -> dict[str, Any]:
    return {
        "source_checkpoint": copy.deepcopy(SOURCE_CHECKPOINT),
        "start_transition_review_bindings": copy.deepcopy(
            list(context.start_review_bindings)
        ),
        "completion_evidence_bindings": copy.deepcopy(
            list(context.completion_evidence_bindings)
        ),
        "superseded_review_assignments": copy.deepcopy(
            list(context.superseded_assignment_bindings)
        ),
        "projected_transition": copy.deepcopy(PROJECTED_TRANSITION),
        "reviewed_control_code_cohort": copy.deepcopy(
            list(context.control_code_cohort)
        ),
        "reviewed_control_code_cohort_sha256": context.control_code_cohort_sha256,
        "acceptance": {
            "source_checkpoint_is_exact_seq69_at_assignment": True,
            "seq68_69_r031_start_transition_review_is_exact": True,
            "six_initial_product_evidence_files_are_validated": True,
            "four_r028_successor_files_are_exact": True,
            "successor_review_subject_product_independent_review_and_completion_receipt_are_validated": True,
            "seq70_is_adjacent_canonical_bindings_update_and_binds_this_review": True,
            "seq71_is_adjacent_fp022_goal_completion": True,
            "fp022_finishes_complete_at_target_and_focuses_epic04": True,
            "fp023_gap032_rank25_remains_the_semantic_next_pointer": True,
            "formal_device_external_deployment_approval_and_release_credit_remain_zero": True,
            "atomic_checkpoint_and_catalog_publication_remains_fail_closed": True,
        },
    }


def _identity(value: Any, role: str, actor_id: str, task: str) -> dict[str, str]:
    expected = {
        "role": role,
        "agent_instance_id": actor_id,
        "canonical_task": task,
    }
    label = {
        "INTERNAL_REVIEW_ASSIGNER": "assigner",
        "INTERNAL_IMPLEMENTATION_EXECUTOR": "executor",
        "SEPARATE_INTERNAL_REVIEWER": "reviewer",
    }[role]
    require(
        type(value) is dict and value == expected,
        f"{label} identity differs",
    )
    return dict(value)


def validate_assignment(
    assignment: Mapping[str, Any], raw: bytes, context: ReviewContext
) -> None:
    require(raw == json_text(assignment).encode(), "assignment is noncanonical")
    require(
        set(assignment)
        == {
            "schema_version",
            "evidence_type",
            "goal_id",
            "round_id",
            "assigned_at",
            "assigner",
            "executor",
            "reviewer",
            "review_scope",
            "review_boundary",
        }
        and assignment.get("schema_version") == "1.0"
        and assignment.get("evidence_type")
        == "FP022_SEQ70_71_COMPLETION_REVIEW_ASSIGNMENT"
        and assignment.get("goal_id") == GOAL_ID
        and assignment.get("round_id") == ROUND_ID,
        "assignment identity differs",
    )
    _parse_time(assignment.get("assigned_at"), "assigned_at")
    assigner = _identity(
        assignment.get("assigner"),
        "INTERNAL_REVIEW_ASSIGNER",
        ASSIGNER_ID,
        ASSIGNER_TASK,
    )
    executor = _identity(
        assignment.get("executor"),
        "INTERNAL_IMPLEMENTATION_EXECUTOR",
        EXECUTOR_ID,
        EXECUTOR_TASK,
    )
    reviewer = _identity(
        assignment.get("reviewer"),
        "SEPARATE_INTERNAL_REVIEWER",
        REVIEWER_ID,
        REVIEWER_TASK,
    )
    require(
        reviewer["agent_instance_id"]
        not in {assigner["agent_instance_id"], executor["agent_instance_id"]}
        and reviewer["canonical_task"]
        not in {assigner["canonical_task"], executor["canonical_task"]},
        "reviewer is not separate",
    )
    require(assignment.get("review_scope") == _scope(context), "assignment scope differs")
    require(assignment.get("review_boundary") == BOUNDARY, "assignment boundary differs")


def validate_review_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ReviewContext,
) -> None:
    validate_assignment(assignment, assignment_raw, context)
    require(raw == json_text(result).encode(), "review result is noncanonical")
    require(
        set(result)
        == {
            "schema_version",
            "evidence_type",
            "goal_id",
            "round_id",
            "reviewed_at",
            "reviewer",
            "assignment_binding",
            "review_scope",
            "decision",
            "findings",
            "finding_dispositions",
            "review_boundary",
        }
        and result.get("schema_version") == "1.0"
        and result.get("evidence_type")
        == "FP022_SEQ70_71_COMPLETION_REVIEWER_AUTHORED_RESULT"
        and result.get("goal_id") == GOAL_ID
        and result.get("round_id") == ROUND_ID,
        "review result identity differs",
    )
    require(result.get("reviewer") == assignment.get("reviewer"), "reviewer identity differs")
    require(
        result.get("assignment_binding") == _binding(ASSIGNMENT_REL, assignment_raw),
        "assignment binding differs",
    )
    require(result.get("review_scope") == _scope(context), "review result scope differs")
    require(
        result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and result.get("finding_dispositions") == [],
        "review is not finding-free approved",
    )
    require(result.get("review_boundary") == BOUNDARY, "review result boundary differs")
    require(
        _parse_time(result.get("reviewed_at"), "reviewed_at")
        >= _parse_time(assignment.get("assigned_at"), "assigned_at"),
        "review predates assignment",
    )


def build_independent_review(
    context: ReviewContext,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> str:
    validate_review_result(result, result_raw, assignment, assignment_raw, context)
    return json_text(
        {
            "schema_version": "1.0",
            "evidence_type": "FP022_SEQ70_71_COMPLETION_INDEPENDENT_INTERNAL_REVIEW",
            "goal_id": GOAL_ID,
            "round_id": ROUND_ID,
            "status": "PASS",
            "decision": result["decision"],
            "reviewed_at": result["reviewed_at"],
            "reviewer": result["reviewer"],
            "assignment_provenance": _binding(ASSIGNMENT_REL, assignment_raw),
            "review_result_provenance": _binding(RESULT_REL, result_raw),
            "review_scope": result["review_scope"],
            "findings": result["findings"],
            "finding_dispositions": result["finding_dispositions"],
            "review_boundary": result["review_boundary"],
        }
    )


def _validated_binding(
    value: Any,
    expected_path: str,
    label: str,
) -> dict[str, Any]:
    require(
        type(value) is dict
        and set(value) == {"path", "sha256", "byte_length"}
        and value.get("path") == expected_path
        and type(value.get("sha256")) is str
        and len(value["sha256"]) == 64
        and type(value.get("byte_length")) is int
        and value["byte_length"] >= 0,
        f"{label} binding differs: {expected_path}",
    )
    return copy.deepcopy(value)


def _validated_binding_cohort(
    value: Any,
    expected_paths: Sequence[Path],
    label: str,
) -> tuple[dict[str, Any], ...]:
    require(type(value) is list, f"{label} binding cohort is missing")
    expected = tuple(path.as_posix() for path in expected_paths)
    actual = tuple(
        row.get("path") if type(row) is dict else None for row in value
    )
    require(actual == expected, f"{label} binding cohort path order differs")
    return tuple(
        _validated_binding(row, path, label)
        for row, path in zip(value, expected, strict=True)
    )


def _validated_managed_source_successors(
    value: Any,
    pins: Mapping[Path, Mapping[str, Any]],
    label: str,
) -> tuple[dict[str, Any], ...]:
    require(type(value) is list, f"{label} source successors are missing")
    expected_paths = tuple(path.as_posix() for path in pins)
    actual_paths = tuple(
        row.get("path") if type(row) is dict else None for row in value
    )
    require(
        actual_paths == expected_paths,
        f"{label} source successor path order differs",
    )
    rows: list[dict[str, Any]] = []
    for row, (relative, pin) in zip(value, pins.items(), strict=True):
        expected = {
            "path": relative.as_posix(),
            "predecessor": {
                "path": relative.as_posix(),
                "sha256": pin["predecessor_sha256"],
                "byte_length": pin["predecessor_byte_length"],
            },
            "successor": {
                "path": relative.as_posix(),
                "sha256": pin["successor_sha256"],
                "byte_length": pin["successor_byte_length"],
            },
        }
        require(row == expected, f"{label} source successor differs: {relative}")
        rows.append(copy.deepcopy(row))
    return tuple(rows)


def _validate_control_successor_assignment_envelope(
    assignment: Mapping[str, Any],
    raw: bytes,
    expected_scope: Mapping[str, Any],
    *,
    round_id: str,
    assigner_id: str,
    assigner_task: str,
    executor_id: str,
    executor_task: str,
    reviewer_id: str,
    reviewer_task: str,
    label: str,
) -> None:
    require(raw == json_text(assignment).encode(), f"{label} assignment is noncanonical")
    require(
        set(assignment)
        == {
            "schema_version",
            "evidence_type",
            "goal_id",
            "round_id",
            "assigned_at",
            "assigner",
            "executor",
            "reviewer",
            "review_scope",
            "review_boundary",
        }
        and assignment.get("schema_version") == "1.0"
        and assignment.get("evidence_type")
        == "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_ASSIGNMENT"
        and assignment.get("goal_id") == GOAL_ID
        and assignment.get("round_id") == round_id,
        f"{label} assignment identity differs",
    )
    _parse_time(assignment.get("assigned_at"), f"{label} assigned_at")
    assigner = _identity(
        assignment.get("assigner"),
        "INTERNAL_REVIEW_ASSIGNER",
        assigner_id,
        assigner_task,
    )
    executor = _identity(
        assignment.get("executor"),
        "INTERNAL_IMPLEMENTATION_EXECUTOR",
        executor_id,
        executor_task,
    )
    reviewer = _identity(
        assignment.get("reviewer"),
        "SEPARATE_INTERNAL_REVIEWER",
        reviewer_id,
        reviewer_task,
    )
    require(
        reviewer["agent_instance_id"]
        not in {assigner["agent_instance_id"], executor["agent_instance_id"]}
        and reviewer["canonical_task"]
        not in {assigner["canonical_task"], executor["canonical_task"]},
        f"{label} reviewer is not separate",
    )
    require(
        assignment.get("review_scope") == expected_scope,
        f"{label} assignment scope differs",
    )
    require(
        assignment.get("review_boundary") == BOUNDARY,
        f"{label} assignment boundary differs",
    )


def _validate_control_successor_result_envelope(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    expected_scope: Mapping[str, Any],
    *,
    round_id: str,
    assignment_rel: Path,
    label: str,
) -> None:
    require(raw == json_text(result).encode(), f"{label} result is noncanonical")
    require(
        set(result)
        == {
            "schema_version",
            "evidence_type",
            "goal_id",
            "round_id",
            "reviewed_at",
            "reviewer",
            "assignment_binding",
            "review_scope",
            "decision",
            "findings",
            "finding_dispositions",
            "review_boundary",
        }
        and result.get("schema_version") == "1.0"
        and result.get("evidence_type")
        == "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_REVIEW_RESULT"
        and result.get("goal_id") == GOAL_ID
        and result.get("round_id") == round_id,
        f"{label} result identity differs",
    )
    require(
        result.get("reviewer") == assignment.get("reviewer"),
        f"{label} reviewer identity differs",
    )
    require(
        result.get("assignment_binding")
        == _binding(assignment_rel, assignment_raw),
        f"{label} assignment binding differs",
    )
    require(
        result.get("review_scope") == expected_scope,
        f"{label} result scope differs",
    )
    require(
        result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and result.get("finding_dispositions") == [],
        f"{label} review is not finding-free approved",
    )
    require(
        result.get("review_boundary") == BOUNDARY,
        f"{label} result boundary differs",
    )
    require(
        _parse_time(result.get("reviewed_at"), f"{label} reviewed_at")
        >= _parse_time(assignment.get("assigned_at"), f"{label} assigned_at"),
        f"{label} review predates assignment",
    )


def _build_control_successor_independent(
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
    *,
    round_id: str,
    assignment_rel: Path,
    result_rel: Path,
) -> str:
    return json_text(
        {
            "schema_version": "1.0",
            "evidence_type": (
                "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_"
                "INDEPENDENT_REVIEW"
            ),
            "goal_id": GOAL_ID,
            "round_id": round_id,
            "status": "PASS",
            "decision": result["decision"],
            "reviewed_at": result["reviewed_at"],
            "reviewer": result["reviewer"],
            "assignment_provenance": _binding(assignment_rel, assignment_raw),
            "review_result_provenance": _binding(result_rel, result_raw),
            "review_scope": result["review_scope"],
            "findings": result["findings"],
            "finding_dispositions": result["finding_dispositions"],
            "review_boundary": result["review_boundary"],
        }
    )


def prepare_frozen_completed_review(
    root: Path = ROOT,
) -> tuple[ReviewContext, tuple[dict[str, Any], ...]]:
    """Replay the approved R014 review against its immutable reviewed scope."""

    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative in COMPLETED_REVIEW_PATHS:
        document, raw = _document(root, relative)
        expected_sha256, expected_length = COMPLETED_REVIEW_PINS[relative]
        require(
            bytes_sha256(raw) == expected_sha256
            and len(raw) == expected_length,
            f"exact FP-022 seq70/71 R014 review differs: {relative}",
        )
        documents[relative] = document
        raw_by_path[relative] = raw

    assignment = documents[ASSIGNMENT_REL]
    result = documents[RESULT_REL]
    scope = assignment.get("review_scope")
    require(type(scope) is dict, "FP-022 seq70/71 R014 scope is missing")
    frozen = ReviewContext(
        root=root.resolve(strict=True),
        start_review_bindings=tuple(
            scope["start_transition_review_bindings"]
        ),
        completion_evidence_bindings=tuple(
            scope["completion_evidence_bindings"]
        ),
        superseded_assignment_bindings=tuple(
            scope["superseded_review_assignments"]
        ),
        control_code_cohort=tuple(scope["reviewed_control_code_cohort"]),
        control_code_cohort_sha256=scope[
            "reviewed_control_code_cohort_sha256"
        ],
    )
    validate_review_result(
        result,
        raw_by_path[RESULT_REL],
        assignment,
        raw_by_path[ASSIGNMENT_REL],
        frozen,
    )
    require(
        raw_by_path[INDEPENDENT_REL]
        == build_independent_review(
            frozen,
            assignment,
            raw_by_path[ASSIGNMENT_REL],
            result,
            raw_by_path[RESULT_REL],
        ).encode(),
        "exact FP-022 seq70/71 R014 independent review differs",
    )
    return frozen, tuple(
        _binding(path, raw_by_path[path]) for path in COMPLETED_REVIEW_PATHS
    )


def prepare_frozen_control_successor_r001(
    root: Path = ROOT,
) -> tuple[ReviewContext, tuple[dict[str, Any], ...]]:
    """Replay approved control-successor R001 without consulting live bytes."""

    predecessor, predecessor_bindings = prepare_frozen_completed_review(root)
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative in CONTROL_SUCCESSOR_R001_PATHS:
        document, raw = _document(root, relative)
        expected_sha256, expected_length = CONTROL_SUCCESSOR_R001_PINS[relative]
        require(
            bytes_sha256(raw) == expected_sha256
            and len(raw) == expected_length,
            f"exact control successor R001 review differs: {relative}",
        )
        documents[relative] = document
        raw_by_path[relative] = raw

    assignment = documents[CONTROL_SUCCESSOR_ASSIGNMENT_REL]
    result = documents[CONTROL_SUCCESSOR_RESULT_REL]
    scope = assignment.get("review_scope")
    require(
        type(scope) is dict
        and set(scope)
        == {
            "predecessor_transition_review_bindings",
            "predecessor_control_code_cohort_sha256",
            "reviewed_control_code_successors",
            "reviewed_current_control_code_cohort",
            "reviewed_current_control_code_cohort_sha256",
            "reviewed_acceptance_evidence_sources",
            "change_purpose",
            "acceptance",
        },
        "control successor R001 scope differs",
    )
    require(
        scope["predecessor_transition_review_bindings"]
        == list(predecessor_bindings)
        and scope["predecessor_control_code_cohort_sha256"]
        == predecessor.control_code_cohort_sha256,
        "control successor R001 predecessor binding differs",
    )
    current_cohort = _validated_binding_cohort(
        scope["reviewed_current_control_code_cohort"],
        CONTROL_PATHS,
        "control successor R001 current control",
    )
    current_cohort_sha256 = scope["reviewed_current_control_code_cohort_sha256"]
    require(
        type(current_cohort_sha256) is str
        and current_cohort_sha256 == object_sha256(list(current_cohort)),
        "control successor R001 current control aggregate differs",
    )
    _validated_binding_cohort(
        scope["reviewed_acceptance_evidence_sources"],
        CONTROL_SUCCESSOR_EVIDENCE_PATHS,
        "control successor R001 acceptance evidence",
    )

    before_by_path = {
        row["path"]: row for row in predecessor.control_code_cohort
    }
    after_by_path = {row["path"]: row for row in current_cohort}
    successors = scope["reviewed_control_code_successors"]
    require(type(successors) is list, "control successor R001 changes are missing")
    successor_paths = tuple(
        row.get("path") if type(row) is dict else None for row in successors
    )
    require(
        len(successor_paths) == len(set(successor_paths))
        and set(successor_paths)
        == {path.as_posix() for path in CONTROL_SUCCESSOR_CHANGED_PATHS},
        "control successor R001 changed path set differs",
    )
    for row in successors:
        relative = row.get("path") if type(row) is dict else None
        require(
            type(row) is dict
            and set(row) == {"path", "predecessor", "successor"}
            and type(relative) is str
            and row.get("predecessor") == before_by_path.get(relative)
            and row.get("successor") == after_by_path.get(relative),
            f"control successor R001 change binding differs: {relative}",
        )

    frozen = ReviewContext(
        root=root.resolve(strict=True),
        start_review_bindings=predecessor.start_review_bindings,
        completion_evidence_bindings=predecessor.completion_evidence_bindings,
        superseded_assignment_bindings=predecessor.superseded_assignment_bindings,
        control_code_cohort=current_cohort,
        control_code_cohort_sha256=current_cohort_sha256,
    )
    _validate_control_successor_assignment_envelope(
        assignment,
        raw_by_path[CONTROL_SUCCESSOR_ASSIGNMENT_REL],
        scope,
        round_id=CONTROL_SUCCESSOR_ROUND_ID,
        assigner_id=CONTROL_SUCCESSOR_ASSIGNER_ID,
        assigner_task=CONTROL_SUCCESSOR_ASSIGNER_TASK,
        executor_id=CONTROL_SUCCESSOR_EXECUTOR_ID,
        executor_task=CONTROL_SUCCESSOR_EXECUTOR_TASK,
        reviewer_id=CONTROL_SUCCESSOR_REVIEWER_ID,
        reviewer_task=CONTROL_SUCCESSOR_REVIEWER_TASK,
        label="control successor R001",
    )
    _validate_control_successor_result_envelope(
        result,
        raw_by_path[CONTROL_SUCCESSOR_RESULT_REL],
        assignment,
        raw_by_path[CONTROL_SUCCESSOR_ASSIGNMENT_REL],
        scope,
        round_id=CONTROL_SUCCESSOR_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_ASSIGNMENT_REL,
        label="control successor R001",
    )
    require(
        raw_by_path[CONTROL_SUCCESSOR_INDEPENDENT_REL]
        == _build_control_successor_independent(
            raw_by_path[CONTROL_SUCCESSOR_ASSIGNMENT_REL],
            result,
            raw_by_path[CONTROL_SUCCESSOR_RESULT_REL],
            round_id=CONTROL_SUCCESSOR_ROUND_ID,
            assignment_rel=CONTROL_SUCCESSOR_ASSIGNMENT_REL,
            result_rel=CONTROL_SUCCESSOR_RESULT_REL,
        ).encode(),
        "exact control successor R001 independent review differs",
    )
    return frozen, tuple(
        _binding(path, raw_by_path[path])
        for path in CONTROL_SUCCESSOR_R001_PATHS
    )


def _control_successor_scope(
    current: ReviewContext,
    predecessor: ReviewContext,
    predecessor_review_bindings: tuple[dict[str, Any], ...],
) -> dict[str, Any]:
    before_by_path = {
        row["path"]: row for row in predecessor.control_code_cohort
    }
    after_by_path = {row["path"]: row for row in current.control_code_cohort}
    require(
        set(before_by_path) == set(after_by_path),
        "control successor cohort path set differs",
    )
    changed = [
        path.as_posix()
        for path in CONTROL_PATHS
        if before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
    ]
    require(
        set(changed)
        == {path.as_posix() for path in CONTROL_SUCCESSOR_CHANGED_PATHS},
        "control successor changed path set differs",
    )
    return {
        "predecessor_transition_review_bindings": copy.deepcopy(
            list(predecessor_review_bindings)
        ),
        "predecessor_control_code_cohort_sha256": (
            predecessor.control_code_cohort_sha256
        ),
        "reviewed_control_code_successors": [
            {
                "path": relative,
                "predecessor": copy.deepcopy(before_by_path[relative]),
                "successor": copy.deepcopy(after_by_path[relative]),
            }
            for relative in changed
        ],
        "reviewed_current_control_code_cohort": copy.deepcopy(
            list(current.control_code_cohort)
        ),
        "reviewed_current_control_code_cohort_sha256": (
            current.control_code_cohort_sha256
        ),
        "reviewed_acceptance_evidence_sources": copy.deepcopy(
            list(_bind_paths(current.root, CONTROL_SUCCESSOR_EVIDENCE_PATHS))
        ),
        "change_purpose": (
            "Repair current acceptance by separating completed transition "
            "snapshots, making verifier tests independent of the host Gradle "
            "cache, and binding the forward recovery-expiry migration plus "
            "current database assertions without rewriting seq70/71 "
            "completion evidence."
        ),
        "acceptance": {
            "r014_transition_review_remains_immutable": True,
            "only_declared_control_paths_changed_within_reviewed_cohort": True,
            "historical_transition_tests_leave_current_unit_layer": True,
            "python_verifiers_precede_android_gradle_execution": True,
            "verifier_tests_are_host_gradle_cache_independent": True,
            "recovery_expiry_fix_uses_a_forward_migration": True,
            "migration_compatibility_and_cache_regressions_are_exact": True,
            "runtime_acl_database_tests_run_in_functional": True,
            "fp046_current_acl_test_successor_is_exact": True,
            "completion_formal_external_and_release_credit_remain_zero": True,
        },
    }


def build_control_successor_assignment(
    context: ReviewContext,
    predecessor: ReviewContext,
    predecessor_review_bindings: tuple[dict[str, Any], ...],
    *,
    assigned_at: str,
) -> str:
    assignment = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_ASSIGNMENT"
        ),
        "goal_id": GOAL_ID,
        "round_id": CONTROL_SUCCESSOR_ROUND_ID,
        "assigned_at": assigned_at,
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": CONTROL_SUCCESSOR_ASSIGNER_ID,
            "canonical_task": CONTROL_SUCCESSOR_ASSIGNER_TASK,
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": CONTROL_SUCCESSOR_EXECUTOR_ID,
            "canonical_task": CONTROL_SUCCESSOR_EXECUTOR_TASK,
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": CONTROL_SUCCESSOR_REVIEWER_ID,
            "canonical_task": CONTROL_SUCCESSOR_REVIEWER_TASK,
        },
        "review_scope": _control_successor_scope(
            context,
            predecessor,
            predecessor_review_bindings,
        ),
        "review_boundary": copy.deepcopy(BOUNDARY),
    }
    raw = json_text(assignment)
    validate_control_successor_assignment(
        assignment,
        raw.encode(),
        context,
        predecessor,
        predecessor_review_bindings,
    )
    return raw


def validate_control_successor_assignment(
    assignment: Mapping[str, Any],
    raw: bytes,
    context: ReviewContext,
    predecessor: ReviewContext,
    predecessor_review_bindings: tuple[dict[str, Any], ...],
) -> None:
    require(raw == json_text(assignment).encode(), "control successor assignment is noncanonical")
    require(
        set(assignment)
        == {
            "schema_version",
            "evidence_type",
            "goal_id",
            "round_id",
            "assigned_at",
            "assigner",
            "executor",
            "reviewer",
            "review_scope",
            "review_boundary",
        }
        and assignment.get("schema_version") == "1.0"
        and assignment.get("evidence_type")
        == "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_ASSIGNMENT"
        and assignment.get("goal_id") == GOAL_ID
        and assignment.get("round_id") == CONTROL_SUCCESSOR_ROUND_ID,
        "control successor assignment identity differs",
    )
    _parse_time(assignment.get("assigned_at"), "control successor assigned_at")
    assigner = _identity(
        assignment.get("assigner"),
        "INTERNAL_REVIEW_ASSIGNER",
        CONTROL_SUCCESSOR_ASSIGNER_ID,
        CONTROL_SUCCESSOR_ASSIGNER_TASK,
    )
    executor = _identity(
        assignment.get("executor"),
        "INTERNAL_IMPLEMENTATION_EXECUTOR",
        CONTROL_SUCCESSOR_EXECUTOR_ID,
        CONTROL_SUCCESSOR_EXECUTOR_TASK,
    )
    reviewer = _identity(
        assignment.get("reviewer"),
        "SEPARATE_INTERNAL_REVIEWER",
        CONTROL_SUCCESSOR_REVIEWER_ID,
        CONTROL_SUCCESSOR_REVIEWER_TASK,
    )
    require(
        reviewer["agent_instance_id"]
        not in {assigner["agent_instance_id"], executor["agent_instance_id"]}
        and reviewer["canonical_task"]
        not in {assigner["canonical_task"], executor["canonical_task"]},
        "control successor reviewer is not separate",
    )
    require(
        assignment.get("review_scope")
        == _control_successor_scope(
            context,
            predecessor,
            predecessor_review_bindings,
        ),
        "control successor assignment scope differs",
    )
    require(
        assignment.get("review_boundary") == BOUNDARY,
        "control successor assignment boundary differs",
    )


def validate_control_successor_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ReviewContext,
    predecessor: ReviewContext,
    predecessor_review_bindings: tuple[dict[str, Any], ...],
) -> None:
    validate_control_successor_assignment(
        assignment,
        assignment_raw,
        context,
        predecessor,
        predecessor_review_bindings,
    )
    require(raw == json_text(result).encode(), "control successor result is noncanonical")
    require(
        set(result)
        == {
            "schema_version",
            "evidence_type",
            "goal_id",
            "round_id",
            "reviewed_at",
            "reviewer",
            "assignment_binding",
            "review_scope",
            "decision",
            "findings",
            "finding_dispositions",
            "review_boundary",
        }
        and result.get("schema_version") == "1.0"
        and result.get("evidence_type")
        == "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_REVIEW_RESULT"
        and result.get("goal_id") == GOAL_ID
        and result.get("round_id") == CONTROL_SUCCESSOR_ROUND_ID,
        "control successor result identity differs",
    )
    require(
        result.get("reviewer") == assignment.get("reviewer"),
        "control successor reviewer identity differs",
    )
    require(
        result.get("assignment_binding")
        == _binding(CONTROL_SUCCESSOR_ASSIGNMENT_REL, assignment_raw),
        "control successor assignment binding differs",
    )
    require(
        result.get("review_scope")
        == _control_successor_scope(
            context,
            predecessor,
            predecessor_review_bindings,
        ),
        "control successor result scope differs",
    )
    require(
        result.get("decision") == "APPROVED"
        and result.get("findings")
        == {"blocking": [], "major_open": [], "minor_open": []}
        and result.get("finding_dispositions") == [],
        "control successor review is not finding-free approved",
    )
    require(
        result.get("review_boundary") == BOUNDARY,
        "control successor result boundary differs",
    )
    require(
        _parse_time(result.get("reviewed_at"), "control successor reviewed_at")
        >= _parse_time(
            assignment.get("assigned_at"),
            "control successor assigned_at",
        ),
        "control successor review predates assignment",
    )


def build_control_successor_independent_review(
    context: ReviewContext,
    predecessor: ReviewContext,
    predecessor_review_bindings: tuple[dict[str, Any], ...],
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> str:
    validate_control_successor_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
        predecessor,
        predecessor_review_bindings,
    )
    return json_text(
        {
            "schema_version": "1.0",
            "evidence_type": (
                "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_"
                "INDEPENDENT_REVIEW"
            ),
            "goal_id": GOAL_ID,
            "round_id": CONTROL_SUCCESSOR_ROUND_ID,
            "status": "PASS",
            "decision": result["decision"],
            "reviewed_at": result["reviewed_at"],
            "reviewer": result["reviewer"],
            "assignment_provenance": _binding(
                CONTROL_SUCCESSOR_ASSIGNMENT_REL,
                assignment_raw,
            ),
            "review_result_provenance": _binding(
                CONTROL_SUCCESSOR_RESULT_REL,
                result_raw,
            ),
            "review_scope": result["review_scope"],
            "findings": result["findings"],
            "finding_dispositions": result["finding_dispositions"],
            "review_boundary": result["review_boundary"],
        }
    )


def validate_control_successor_review(
    root: Path,
    context: ReviewContext,
    predecessor: ReviewContext,
    predecessor_review_bindings: tuple[dict[str, Any], ...],
) -> None:
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_RESULT_REL)
    validate_control_successor_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
        predecessor,
        predecessor_review_bindings,
    )
    require(
        _raw(root, CONTROL_SUCCESSOR_INDEPENDENT_REL)
        == build_control_successor_independent_review(
            context,
            predecessor,
            predecessor_review_bindings,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ).encode(),
        "control successor independent review differs",
    )


def _control_successor_r002_managed_closure_sources(
    root: Path,
) -> tuple[dict[str, Any], ...]:
    authority = npc_r004_review.prepare_frozen_r003_context(root)
    authority_by_path = {
        row["path"]: row for row in authority.control_code_cohort
    }
    rows: list[dict[str, Any]] = []
    for relative, pin in CONTROL_SUCCESSOR_R002_MANAGED_CLOSURE_SOURCE_PINS.items():
        predecessor = {
            "path": relative.as_posix(),
            "sha256": pin["predecessor_sha256"],
            "byte_length": pin["predecessor_byte_length"],
        }
        require(
            authority_by_path.get(relative.as_posix()) == predecessor,
            f"control successor R002 managed predecessor differs: {relative}",
        )
        raw = _raw(root, relative)
        require(
            len(raw) == pin["successor_byte_length"]
            and bytes_sha256(raw) == pin["successor_sha256"],
            f"control successor R002 managed source differs: {relative}",
        )
        rows.append(
            {
                "path": relative.as_posix(),
                "predecessor": predecessor,
                "successor": _binding(relative, raw),
            }
        )
    return tuple(rows)


def prepare_control_successor_r002_context(
    root: Path = ROOT,
) -> ControlSuccessorR002Context:
    predecessor, predecessor_bindings = prepare_frozen_control_successor_r001(
        root
    )
    current = prepare_review_context(root)
    before_by_path = {
        row["path"]: row for row in predecessor.control_code_cohort
    }
    after_by_path = {row["path"]: row for row in current.control_code_cohort}
    require(
        set(before_by_path) == set(after_by_path),
        "control successor R002 cohort path set differs",
    )
    changed = tuple(
        path.as_posix()
        for path in CONTROL_PATHS
        if before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
    )
    require(
        set(changed)
        == {path.as_posix() for path in CONTROL_SUCCESSOR_R002_CHANGED_PATHS},
        "control successor R002 changed path set differs",
    )
    return ControlSuccessorR002Context(
        root=root.resolve(strict=True),
        current=current,
        predecessor=predecessor,
        predecessor_review_bindings=predecessor_bindings,
        control_code_successors=tuple(
            {
                "path": relative,
                "predecessor": copy.deepcopy(before_by_path[relative]),
                "successor": copy.deepcopy(after_by_path[relative]),
            }
            for relative in changed
        ),
        acceptance_evidence_sources=_bind_paths(
            root,
            CONTROL_SUCCESSOR_R002_EVIDENCE_PATHS,
        ),
        managed_closure_source_successors=(
            _control_successor_r002_managed_closure_sources(root)
        ),
    )


def _control_successor_r002_scope(
    context: ControlSuccessorR002Context,
) -> dict[str, Any]:
    return {
        "predecessor_control_successor_review_bindings": copy.deepcopy(
            list(context.predecessor_review_bindings)
        ),
        "predecessor_current_control_code_cohort_sha256": (
            context.predecessor.control_code_cohort_sha256
        ),
        "reviewed_control_code_successors": copy.deepcopy(
            list(context.control_code_successors)
        ),
        "reviewed_current_control_code_cohort": copy.deepcopy(
            list(context.current.control_code_cohort)
        ),
        "reviewed_current_control_code_cohort_sha256": (
            context.current.control_code_cohort_sha256
        ),
        "reviewed_acceptance_evidence_sources": copy.deepcopy(
            list(context.acceptance_evidence_sources)
        ),
        "reviewed_managed_closure_source_successors": copy.deepcopy(
            list(context.managed_closure_source_successors)
        ),
        "change_purpose": (
            "Bind the production single-admin verifier and its strengthened "
            "receipt tests as exact managed-closure source successors, then "
            "consume them in current goal-graph acceptance without rewriting "
            "the approved R001 or seq70/71 evidence."
        ),
        "acceptance": {
            "r014_transition_review_remains_immutable": True,
            "r001_control_successor_review_remains_immutable": True,
            "only_declared_control_paths_changed_within_reviewed_cohort": True,
            "production_verifier_is_bound_as_acceptance_evidence": True,
            "managed_closure_source_successors_are_exact": True,
            "managed_closure_predecessors_match_frozen_authority": True,
            "current_goal_graph_consumes_validated_successors": True,
            "full_receipt_and_missing_component_regressions_are_exact": True,
            "completion_formal_external_and_release_credit_remain_zero": True,
        },
    }


def build_control_successor_r002_assignment(
    context: ControlSuccessorR002Context,
    *,
    assigned_at: str,
) -> str:
    assignment = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_ASSIGNMENT"
        ),
        "goal_id": GOAL_ID,
        "round_id": CONTROL_SUCCESSOR_R002_ROUND_ID,
        "assigned_at": assigned_at,
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": CONTROL_SUCCESSOR_R002_ASSIGNER_ID,
            "canonical_task": CONTROL_SUCCESSOR_R002_ASSIGNER_TASK,
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": CONTROL_SUCCESSOR_R002_EXECUTOR_ID,
            "canonical_task": CONTROL_SUCCESSOR_R002_EXECUTOR_TASK,
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": CONTROL_SUCCESSOR_R002_REVIEWER_ID,
            "canonical_task": CONTROL_SUCCESSOR_R002_REVIEWER_TASK,
        },
        "review_scope": _control_successor_r002_scope(context),
        "review_boundary": copy.deepcopy(BOUNDARY),
    }
    raw = json_text(assignment)
    validate_control_successor_r002_assignment(
        assignment,
        raw.encode(),
        context,
    )
    return raw


def validate_control_successor_r002_assignment(
    assignment: Mapping[str, Any],
    raw: bytes,
    context: ControlSuccessorR002Context,
) -> None:
    _validate_control_successor_assignment_envelope(
        assignment,
        raw,
        _control_successor_r002_scope(context),
        round_id=CONTROL_SUCCESSOR_R002_ROUND_ID,
        assigner_id=CONTROL_SUCCESSOR_R002_ASSIGNER_ID,
        assigner_task=CONTROL_SUCCESSOR_R002_ASSIGNER_TASK,
        executor_id=CONTROL_SUCCESSOR_R002_EXECUTOR_ID,
        executor_task=CONTROL_SUCCESSOR_R002_EXECUTOR_TASK,
        reviewer_id=CONTROL_SUCCESSOR_R002_REVIEWER_ID,
        reviewer_task=CONTROL_SUCCESSOR_R002_REVIEWER_TASK,
        label="control successor R002",
    )


def validate_control_successor_r002_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ControlSuccessorR002Context,
) -> None:
    expected_scope = _control_successor_r002_scope(context)
    validate_control_successor_r002_assignment(
        assignment,
        assignment_raw,
        context,
    )
    _validate_control_successor_result_envelope(
        result,
        raw,
        assignment,
        assignment_raw,
        expected_scope,
        round_id=CONTROL_SUCCESSOR_R002_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL,
        label="control successor R002",
    )


def build_control_successor_r002_independent_review(
    context: ControlSuccessorR002Context,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> str:
    validate_control_successor_r002_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    return _build_control_successor_independent(
        assignment_raw,
        result,
        result_raw,
        round_id=CONTROL_SUCCESSOR_R002_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL,
        result_rel=CONTROL_SUCCESSOR_R002_RESULT_REL,
    )


def validate_control_successor_r002_review(
    root: Path,
    context: ControlSuccessorR002Context,
) -> None:
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_R002_RESULT_REL)
    validate_control_successor_r002_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    require(
        _raw(root, CONTROL_SUCCESSOR_R002_INDEPENDENT_REL)
        == build_control_successor_r002_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ).encode(),
        "control successor R002 independent review differs",
    )


def prepare_frozen_control_successor_r002(
    root: Path = ROOT,
) -> tuple[ControlSuccessorR002Context, tuple[dict[str, Any], ...]]:
    """Replay approved control-successor R002 from its immutable scope."""

    predecessor, predecessor_bindings = prepare_frozen_control_successor_r001(
        root
    )
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative in CONTROL_SUCCESSOR_R002_PATHS:
        document, raw = _document(root, relative)
        expected_sha256, expected_length = CONTROL_SUCCESSOR_R002_PINS[relative]
        require(
            bytes_sha256(raw) == expected_sha256
            and len(raw) == expected_length,
            f"exact control successor R002 review differs: {relative}",
        )
        documents[relative] = document
        raw_by_path[relative] = raw

    assignment = documents[CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL]
    result = documents[CONTROL_SUCCESSOR_R002_RESULT_REL]
    scope = assignment.get("review_scope")
    require(
        type(scope) is dict
        and set(scope)
        == {
            "predecessor_control_successor_review_bindings",
            "predecessor_current_control_code_cohort_sha256",
            "reviewed_control_code_successors",
            "reviewed_current_control_code_cohort",
            "reviewed_current_control_code_cohort_sha256",
            "reviewed_acceptance_evidence_sources",
            "reviewed_managed_closure_source_successors",
            "change_purpose",
            "acceptance",
        },
        "control successor R002 scope differs",
    )
    require(
        scope["predecessor_control_successor_review_bindings"]
        == list(predecessor_bindings)
        and scope["predecessor_current_control_code_cohort_sha256"]
        == predecessor.control_code_cohort_sha256,
        "control successor R002 predecessor binding differs",
    )
    current_cohort = _validated_binding_cohort(
        scope["reviewed_current_control_code_cohort"],
        CONTROL_PATHS,
        "control successor R002 current control",
    )
    current_cohort_sha256 = scope["reviewed_current_control_code_cohort_sha256"]
    require(
        type(current_cohort_sha256) is str
        and current_cohort_sha256 == object_sha256(list(current_cohort)),
        "control successor R002 current control aggregate differs",
    )
    acceptance_evidence = _validated_binding_cohort(
        scope["reviewed_acceptance_evidence_sources"],
        CONTROL_SUCCESSOR_R002_EVIDENCE_PATHS,
        "control successor R002 acceptance evidence",
    )
    managed_sources = _validated_managed_source_successors(
        scope["reviewed_managed_closure_source_successors"],
        CONTROL_SUCCESSOR_R002_MANAGED_CLOSURE_SOURCE_PINS,
        "control successor R002 managed closure",
    )

    before_by_path = {
        row["path"]: row for row in predecessor.control_code_cohort
    }
    after_by_path = {row["path"]: row for row in current_cohort}
    successors = scope["reviewed_control_code_successors"]
    require(type(successors) is list, "control successor R002 changes are missing")
    successor_paths = tuple(
        row.get("path") if type(row) is dict else None for row in successors
    )
    require(
        len(successor_paths) == len(set(successor_paths))
        and set(successor_paths)
        == {path.as_posix() for path in CONTROL_SUCCESSOR_R002_CHANGED_PATHS},
        "control successor R002 changed path set differs",
    )
    for row in successors:
        relative = row.get("path") if type(row) is dict else None
        require(
            type(row) is dict
            and set(row) == {"path", "predecessor", "successor"}
            and type(relative) is str
            and row.get("predecessor") == before_by_path.get(relative)
            and row.get("successor") == after_by_path.get(relative),
            f"control successor R002 change binding differs: {relative}",
        )

    current = ReviewContext(
        root=root.resolve(strict=True),
        start_review_bindings=predecessor.start_review_bindings,
        completion_evidence_bindings=predecessor.completion_evidence_bindings,
        superseded_assignment_bindings=predecessor.superseded_assignment_bindings,
        control_code_cohort=current_cohort,
        control_code_cohort_sha256=current_cohort_sha256,
    )
    frozen = ControlSuccessorR002Context(
        root=root.resolve(strict=True),
        current=current,
        predecessor=predecessor,
        predecessor_review_bindings=predecessor_bindings,
        control_code_successors=tuple(copy.deepcopy(successors)),
        acceptance_evidence_sources=acceptance_evidence,
        managed_closure_source_successors=managed_sources,
    )
    validate_control_successor_r002_result(
        result,
        raw_by_path[CONTROL_SUCCESSOR_R002_RESULT_REL],
        assignment,
        raw_by_path[CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL],
        frozen,
    )
    require(
        raw_by_path[CONTROL_SUCCESSOR_R002_INDEPENDENT_REL]
        == build_control_successor_r002_independent_review(
            frozen,
            assignment,
            raw_by_path[CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL],
            result,
            raw_by_path[CONTROL_SUCCESSOR_R002_RESULT_REL],
        ).encode(),
        "exact control successor R002 independent review differs",
    )
    return frozen, tuple(
        _binding(path, raw_by_path[path])
        for path in CONTROL_SUCCESSOR_R002_PATHS
    )


def validated_control_successor_r002_context(
    root: Path = ROOT,
) -> ControlSuccessorR002Context:
    """Return the approved R002 context replayed from immutable bytes."""

    context, _bindings = prepare_frozen_control_successor_r002(root)
    return context


def _control_successor_r003_managed_closure_sources(
    root: Path,
    predecessor: ControlSuccessorR002Context,
) -> tuple[dict[str, Any], ...]:
    predecessor_by_path = {
        row["path"]: row["successor"]
        for row in predecessor.managed_closure_source_successors
    }
    expected_paths = tuple(
        path.as_posix()
        for path in CONTROL_SUCCESSOR_R003_MANAGED_CLOSURE_SOURCE_PINS
    )
    require(
        tuple(predecessor_by_path) == expected_paths,
        "control successor R003 managed predecessor path set differs",
    )
    rows: list[dict[str, Any]] = []
    for relative, pin in CONTROL_SUCCESSOR_R003_MANAGED_CLOSURE_SOURCE_PINS.items():
        predecessor_binding = {
            "path": relative.as_posix(),
            "sha256": pin["predecessor_sha256"],
            "byte_length": pin["predecessor_byte_length"],
        }
        require(
            predecessor_by_path.get(relative.as_posix())
            == predecessor_binding,
            f"control successor R003 managed predecessor differs: {relative}",
        )
        raw = _raw(root, relative)
        require(
            len(raw) == pin["successor_byte_length"]
            and bytes_sha256(raw) == pin["successor_sha256"],
            f"control successor R003 managed source differs: {relative}",
        )
        rows.append(
            {
                "path": relative.as_posix(),
                "predecessor": predecessor_binding,
                "successor": _binding(relative, raw),
            }
        )
    return tuple(rows)


def _validate_control_successor_r003_compatibility_record(
    root: Path,
    evidence: tuple[dict[str, Any], ...],
) -> None:
    record, _raw_record = _document(
        root,
        CONTROL_SUCCESSOR_R003_COMPATIBILITY_REL,
    )
    evidence_by_path = {row["path"]: row for row in evidence}
    added = record.get("added_sources")
    amendments = record.get("amendments")
    added_paths = tuple(
        row.get("path") if type(row) is dict else None
        for row in added
    ) if type(added) is list else ()
    require(
        type(added) is list
        and len(added_paths) == len(set(added_paths))
        and set(added_paths)
        == {
            path.as_posix()
            for path in CONTROL_SUCCESSOR_R003_ADDED_SOURCE_PATHS
        },
        "control successor R003 compatibility added source paths differ",
    )
    amendment_paths = tuple(
        row.get("path") if type(row) is dict else None
        for row in amendments
    ) if type(amendments) is list else ()
    require(
        type(amendments) is list
        and len(amendment_paths) == len(set(amendment_paths))
        and set(amendment_paths)
        == {
            path.as_posix()
            for path in CONTROL_SUCCESSOR_R003_AMENDMENT_PATHS
        },
        "control successor R003 compatibility amendment paths differ",
    )
    for row in added:
        relative = row["path"]
        binding = evidence_by_path.get(relative)
        require(
            type(binding) is dict
            and row.get("source")
            == {
                "sha256": binding["sha256"],
                "byte_length": binding["byte_length"],
            },
            f"control successor R003 compatibility added source differs: {relative}",
        )
    for row in amendments:
        relative = row["path"]
        binding = evidence_by_path.get(relative)
        require(
            type(binding) is dict
            and row.get("successor_source")
            == {
                "sha256": binding["sha256"],
                "byte_length": binding["byte_length"],
            },
            f"control successor R003 compatibility amendment differs: {relative}",
        )


def prepare_control_successor_r003_context(
    root: Path = ROOT,
) -> ControlSuccessorR003Context:
    predecessor_review, predecessor_bindings = (
        prepare_frozen_control_successor_r002(root)
    )
    predecessor = predecessor_review.current
    current = prepare_review_context(root)
    before_by_path = {
        row["path"]: row for row in predecessor.control_code_cohort
    }
    after_by_path = {row["path"]: row for row in current.control_code_cohort}
    require(
        set(before_by_path) == set(after_by_path),
        "control successor R003 cohort path set differs",
    )
    changed = tuple(
        path.as_posix()
        for path in CONTROL_PATHS
        if before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
    )
    require(
        set(changed)
        == {path.as_posix() for path in CONTROL_SUCCESSOR_R003_CHANGED_PATHS},
        "control successor R003 changed path set differs",
    )
    evidence = _bind_pinned_paths(
        root,
        CONTROL_SUCCESSOR_R003_EVIDENCE_PATHS,
        CONTROL_SUCCESSOR_R003_EVIDENCE_PINS,
        "control successor R003 acceptance evidence",
    )
    _validate_control_successor_r003_compatibility_record(root, evidence)
    added_sources = _bind_paths(root, CONTROL_SUCCESSOR_R003_ADDED_SOURCE_PATHS)
    evidence_by_path = {row["path"]: row for row in evidence}
    require(
        all(
            evidence_by_path.get(row["path"]) == row
            for row in added_sources
        ),
        "control successor R003 added source evidence differs",
    )
    return ControlSuccessorR003Context(
        root=root.resolve(strict=True),
        current=current,
        predecessor=predecessor,
        predecessor_review_bindings=predecessor_bindings,
        control_code_successors=tuple(
            {
                "path": relative,
                "predecessor": copy.deepcopy(before_by_path[relative]),
                "successor": copy.deepcopy(after_by_path[relative]),
            }
            for relative in changed
        ),
        acceptance_evidence_sources=evidence,
        added_sources=added_sources,
        predecessor_managed_closure_source_successors=copy.deepcopy(
            predecessor_review.managed_closure_source_successors
        ),
        managed_closure_source_successors=(
            _control_successor_r003_managed_closure_sources(
                root,
                predecessor_review,
            )
        ),
    )


def _control_successor_r003_scope(
    context: ControlSuccessorR003Context,
) -> dict[str, Any]:
    return {
        "predecessor_control_successor_review_bindings": copy.deepcopy(
            list(context.predecessor_review_bindings)
        ),
        "predecessor_current_control_code_cohort_sha256": (
            context.predecessor.control_code_cohort_sha256
        ),
        "reviewed_control_code_successors": copy.deepcopy(
            list(context.control_code_successors)
        ),
        "reviewed_current_control_code_cohort": copy.deepcopy(
            list(context.current.control_code_cohort)
        ),
        "reviewed_current_control_code_cohort_sha256": (
            context.current.control_code_cohort_sha256
        ),
        "reviewed_acceptance_evidence_sources": copy.deepcopy(
            list(context.acceptance_evidence_sources)
        ),
        "reviewed_added_sources": copy.deepcopy(list(context.added_sources)),
        "reviewed_managed_closure_source_successors": copy.deepcopy(
            list(context.managed_closure_source_successors)
        ),
        "change_purpose": (
            "Bind the final add-only recovery-expiry migrations, exact current "
            "database and device-proof compatibility evidence, and the latest "
            "production verifier receipts without rewriting R001, R002, or "
            "the seq70/71 transition review."
        ),
        "acceptance": {
            "r014_transition_review_remains_immutable": True,
            "r001_control_successor_review_remains_immutable": True,
            "r002_control_successor_review_remains_immutable": True,
            "only_declared_control_paths_changed_within_reviewed_cohort": True,
            "all_acceptance_evidence_is_directly_byte_bound": True,
            "added_source_inventory_is_distinct_from_acceptance_credit": True,
            "both_forward_migrations_are_add_only_sources": True,
            "managed_closure_predecessors_match_frozen_r002_successors": True,
            "managed_closure_source_successors_are_exact": True,
            "completion_formal_external_and_release_credit_remain_zero": True,
        },
    }


def build_control_successor_r003_assignment(
    context: ControlSuccessorR003Context,
    *,
    assigned_at: str,
) -> str:
    assignment = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_ASSIGNMENT"
        ),
        "goal_id": GOAL_ID,
        "round_id": CONTROL_SUCCESSOR_R003_ROUND_ID,
        "assigned_at": assigned_at,
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": CONTROL_SUCCESSOR_R003_ASSIGNER_ID,
            "canonical_task": CONTROL_SUCCESSOR_R003_ASSIGNER_TASK,
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": CONTROL_SUCCESSOR_R003_EXECUTOR_ID,
            "canonical_task": CONTROL_SUCCESSOR_R003_EXECUTOR_TASK,
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": CONTROL_SUCCESSOR_R003_REVIEWER_ID,
            "canonical_task": CONTROL_SUCCESSOR_R003_REVIEWER_TASK,
        },
        "review_scope": _control_successor_r003_scope(context),
        "review_boundary": copy.deepcopy(BOUNDARY),
    }
    raw = json_text(assignment)
    validate_control_successor_r003_assignment(
        assignment,
        raw.encode(),
        context,
    )
    return raw


def validate_control_successor_r003_assignment(
    assignment: Mapping[str, Any],
    raw: bytes,
    context: ControlSuccessorR003Context,
) -> None:
    _validate_control_successor_assignment_envelope(
        assignment,
        raw,
        _control_successor_r003_scope(context),
        round_id=CONTROL_SUCCESSOR_R003_ROUND_ID,
        assigner_id=CONTROL_SUCCESSOR_R003_ASSIGNER_ID,
        assigner_task=CONTROL_SUCCESSOR_R003_ASSIGNER_TASK,
        executor_id=CONTROL_SUCCESSOR_R003_EXECUTOR_ID,
        executor_task=CONTROL_SUCCESSOR_R003_EXECUTOR_TASK,
        reviewer_id=CONTROL_SUCCESSOR_R003_REVIEWER_ID,
        reviewer_task=CONTROL_SUCCESSOR_R003_REVIEWER_TASK,
        label="control successor R003",
    )


def validate_control_successor_r003_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ControlSuccessorR003Context,
) -> None:
    expected_scope = _control_successor_r003_scope(context)
    validate_control_successor_r003_assignment(
        assignment,
        assignment_raw,
        context,
    )
    _validate_control_successor_result_envelope(
        result,
        raw,
        assignment,
        assignment_raw,
        expected_scope,
        round_id=CONTROL_SUCCESSOR_R003_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_R003_ASSIGNMENT_REL,
        label="control successor R003",
    )


def build_control_successor_r003_independent_review(
    context: ControlSuccessorR003Context,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> str:
    validate_control_successor_r003_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    return _build_control_successor_independent(
        assignment_raw,
        result,
        result_raw,
        round_id=CONTROL_SUCCESSOR_R003_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_R003_ASSIGNMENT_REL,
        result_rel=CONTROL_SUCCESSOR_R003_RESULT_REL,
    )


def validate_control_successor_r003_review(
    root: Path,
    context: ControlSuccessorR003Context,
) -> None:
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_R003_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_R003_RESULT_REL)
    validate_control_successor_r003_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    require(
        _raw(root, CONTROL_SUCCESSOR_R003_INDEPENDENT_REL)
        == build_control_successor_r003_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ).encode(),
        "control successor R003 independent review differs",
    )


def prepare_frozen_control_successor_r003(
    root: Path = ROOT,
) -> tuple[ControlSuccessorR003Context, tuple[dict[str, Any], ...]]:
    """Replay approved control-successor R003 without consulting live evidence."""

    predecessor, predecessor_bindings = prepare_frozen_control_successor_r002(root)
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative in CONTROL_SUCCESSOR_R003_PATHS:
        document, raw = _document(root, relative)
        expected_sha256, expected_length = CONTROL_SUCCESSOR_R003_PINS[relative]
        require(
            bytes_sha256(raw) == expected_sha256 and len(raw) == expected_length,
            f"exact control successor R003 review differs: {relative}",
        )
        documents[relative] = document
        raw_by_path[relative] = raw

    assignment = documents[CONTROL_SUCCESSOR_R003_ASSIGNMENT_REL]
    result = documents[CONTROL_SUCCESSOR_R003_RESULT_REL]
    scope = assignment.get("review_scope")
    require(
        type(scope) is dict
        and set(scope)
        == {
            "predecessor_control_successor_review_bindings",
            "predecessor_current_control_code_cohort_sha256",
            "reviewed_control_code_successors",
            "reviewed_current_control_code_cohort",
            "reviewed_current_control_code_cohort_sha256",
            "reviewed_acceptance_evidence_sources",
            "reviewed_added_sources",
            "reviewed_managed_closure_source_successors",
            "change_purpose",
            "acceptance",
        },
        "control successor R003 scope differs",
    )
    require(
        scope["predecessor_control_successor_review_bindings"]
        == list(predecessor_bindings)
        and scope["predecessor_current_control_code_cohort_sha256"]
        == predecessor.current.control_code_cohort_sha256,
        "control successor R003 predecessor binding differs",
    )
    current_cohort = _validated_binding_cohort(
        scope["reviewed_current_control_code_cohort"],
        CONTROL_PATHS,
        "control successor R003 current control",
    )
    current_cohort_sha256 = scope["reviewed_current_control_code_cohort_sha256"]
    require(
        type(current_cohort_sha256) is str
        and current_cohort_sha256 == object_sha256(list(current_cohort)),
        "control successor R003 current control aggregate differs",
    )
    acceptance_evidence = _validated_binding_cohort(
        scope["reviewed_acceptance_evidence_sources"],
        CONTROL_SUCCESSOR_R003_EVIDENCE_PATHS,
        "control successor R003 acceptance evidence",
    )
    for relative, binding in zip(
        CONTROL_SUCCESSOR_R003_EVIDENCE_PATHS,
        acceptance_evidence,
        strict=True,
    ):
        expected_sha256, expected_length = CONTROL_SUCCESSOR_R003_EVIDENCE_PINS[
            relative
        ]
        require(
            binding["sha256"] == expected_sha256
            and binding["byte_length"] == expected_length,
            f"control successor R003 frozen evidence differs: {relative}",
        )
    added_sources = _validated_binding_cohort(
        scope["reviewed_added_sources"],
        CONTROL_SUCCESSOR_R003_ADDED_SOURCE_PATHS,
        "control successor R003 added sources",
    )
    evidence_by_path = {row["path"]: row for row in acceptance_evidence}
    require(
        all(evidence_by_path.get(row["path"]) == row for row in added_sources),
        "control successor R003 added source evidence differs",
    )
    managed_sources = _validated_managed_source_successors(
        scope["reviewed_managed_closure_source_successors"],
        CONTROL_SUCCESSOR_R003_MANAGED_CLOSURE_SOURCE_PINS,
        "control successor R003 managed closure",
    )

    before_by_path = {
        row["path"]: row for row in predecessor.current.control_code_cohort
    }
    after_by_path = {row["path"]: row for row in current_cohort}
    successors = scope["reviewed_control_code_successors"]
    require(type(successors) is list, "control successor R003 changes are missing")
    successor_paths = tuple(
        row.get("path") if type(row) is dict else None for row in successors
    )
    require(
        len(successor_paths) == len(set(successor_paths))
        and set(successor_paths)
        == {path.as_posix() for path in CONTROL_SUCCESSOR_R003_CHANGED_PATHS},
        "control successor R003 changed path set differs",
    )
    for row in successors:
        relative = row.get("path") if type(row) is dict else None
        require(
            type(row) is dict
            and set(row) == {"path", "predecessor", "successor"}
            and type(relative) is str
            and row.get("predecessor") == before_by_path.get(relative)
            and row.get("successor") == after_by_path.get(relative),
            f"control successor R003 change binding differs: {relative}",
        )

    current = ReviewContext(
        root=root.resolve(strict=True),
        start_review_bindings=predecessor.current.start_review_bindings,
        completion_evidence_bindings=(
            predecessor.current.completion_evidence_bindings
        ),
        superseded_assignment_bindings=(
            predecessor.current.superseded_assignment_bindings
        ),
        control_code_cohort=current_cohort,
        control_code_cohort_sha256=current_cohort_sha256,
    )
    frozen = ControlSuccessorR003Context(
        root=root.resolve(strict=True),
        current=current,
        predecessor=predecessor.current,
        predecessor_review_bindings=predecessor_bindings,
        control_code_successors=tuple(copy.deepcopy(successors)),
        acceptance_evidence_sources=acceptance_evidence,
        added_sources=added_sources,
        predecessor_managed_closure_source_successors=(
            predecessor.managed_closure_source_successors
        ),
        managed_closure_source_successors=managed_sources,
    )
    validate_control_successor_r003_result(
        result,
        raw_by_path[CONTROL_SUCCESSOR_R003_RESULT_REL],
        assignment,
        raw_by_path[CONTROL_SUCCESSOR_R003_ASSIGNMENT_REL],
        frozen,
    )
    require(
        raw_by_path[CONTROL_SUCCESSOR_R003_INDEPENDENT_REL]
        == build_control_successor_r003_independent_review(
            frozen,
            assignment,
            raw_by_path[CONTROL_SUCCESSOR_R003_ASSIGNMENT_REL],
            result,
            raw_by_path[CONTROL_SUCCESSOR_R003_RESULT_REL],
        ).encode(),
        "exact control successor R003 independent review differs",
    )
    return frozen, tuple(
        _binding(path, raw_by_path[path])
        for path in CONTROL_SUCCESSOR_R003_PATHS
    )


def validated_control_successor_r003_context(
    root: Path = ROOT,
) -> ControlSuccessorR003Context:
    """Return the approved R003 context replayed from immutable bytes."""

    context, _bindings = prepare_frozen_control_successor_r003(root)
    return context


def validated_control_successor_r003_managed_closure_sources(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    context = validated_control_successor_r003_context(root)
    return tuple(
        copy.deepcopy(row)
        for row in context.managed_closure_source_successors
    )


def _control_successor_r004_managed_closure_sources(
    root: Path,
    predecessor: ControlSuccessorR003Context,
) -> tuple[dict[str, Any], ...]:
    predecessor_by_path = {
        row["path"]: row["successor"]
        for row in predecessor.managed_closure_source_successors
    }
    rows: list[dict[str, Any]] = []
    for relative, pin in CONTROL_SUCCESSOR_R004_MANAGED_CLOSURE_SOURCE_PINS.items():
        predecessor_binding = {
            "path": relative.as_posix(),
            "sha256": pin["predecessor_sha256"],
            "byte_length": pin["predecessor_byte_length"],
        }
        require(
            predecessor_by_path.get(relative.as_posix()) == predecessor_binding,
            f"control successor R004 managed predecessor differs: {relative}",
        )
        raw = _raw(root, relative)
        require(
            len(raw) == pin["successor_byte_length"]
            and bytes_sha256(raw) == pin["successor_sha256"],
            f"control successor R004 managed source differs: {relative}",
        )
        rows.append(
            {
                "path": relative.as_posix(),
                "predecessor": predecessor_binding,
                "successor": _binding(relative, raw),
            }
        )
    return tuple(rows)


def _control_successor_r004_context(
    root: Path,
    predecessor: ControlSuccessorR003Context,
    predecessor_bindings: tuple[dict[str, Any], ...],
    current: ReviewContext,
    successors: Sequence[Mapping[str, Any]],
    evidence: tuple[dict[str, Any], ...],
    managed_sources: tuple[dict[str, Any], ...],
) -> ControlSuccessorR004Context:
    before_by_path = {
        row["path"]: row for row in predecessor.current.control_code_cohort
    }
    after_by_path = {row["path"]: row for row in current.control_code_cohort}
    require(
        set(before_by_path) == set(after_by_path),
        "control successor R004 cohort path set differs",
    )
    expected = tuple(
        {
            "path": path.as_posix(),
            "predecessor": copy.deepcopy(before_by_path[path.as_posix()]),
            "successor": copy.deepcopy(after_by_path[path.as_posix()]),
        }
        for path in CONTROL_PATHS
        if before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
    )
    require(
        tuple(successors) == expected
        and {row["path"] for row in expected}
        == {path.as_posix() for path in CONTROL_SUCCESSOR_R004_CHANGED_PATHS},
        "control successor R004 changed path set differs",
    )
    predecessor_managed_by_path = {
        row["path"]: row["successor"]
        for row in predecessor.managed_closure_source_successors
    }
    for row in managed_sources:
        require(
            predecessor_managed_by_path.get(row["path"])
            == row["predecessor"],
            f"control successor R004 managed predecessor differs: {row['path']}",
        )
    return ControlSuccessorR004Context(
        root=root.resolve(strict=True),
        current=current,
        predecessor=predecessor,
        predecessor_review_bindings=predecessor_bindings,
        control_code_successors=tuple(copy.deepcopy(successors)),
        acceptance_evidence_sources=evidence,
        predecessor_managed_closure_source_successors=copy.deepcopy(
            predecessor.managed_closure_source_successors
        ),
        managed_closure_source_successors=managed_sources,
    )


def prepare_control_successor_r004_context(
    root: Path = ROOT,
) -> ControlSuccessorR004Context:
    predecessor, predecessor_bindings = prepare_frozen_control_successor_r003(root)
    current = prepare_review_context(root)
    evidence = _bind_pinned_paths(
        root,
        CONTROL_SUCCESSOR_R004_EVIDENCE_PATHS,
        CONTROL_SUCCESSOR_R004_EVIDENCE_PINS,
        "control successor R004 acceptance evidence",
    )
    before_by_path = {
        row["path"]: row for row in predecessor.current.control_code_cohort
    }
    after_by_path = {row["path"]: row for row in current.control_code_cohort}
    require(
        set(before_by_path) == set(after_by_path),
        "control successor R004 cohort path set differs",
    )
    return _control_successor_r004_context(
        root,
        predecessor,
        predecessor_bindings,
        current,
        tuple(
            {
                "path": path.as_posix(),
                "predecessor": copy.deepcopy(before_by_path[path.as_posix()]),
                "successor": copy.deepcopy(after_by_path[path.as_posix()]),
            }
            for path in CONTROL_PATHS
            if before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
        ),
        evidence,
        _control_successor_r004_managed_closure_sources(root, predecessor),
    )


def _control_successor_r004_scope(
    context: ControlSuccessorR004Context,
) -> dict[str, Any]:
    return {
        "predecessor_control_successor_review_bindings": copy.deepcopy(
            list(context.predecessor_review_bindings)
        ),
        "predecessor_current_control_code_cohort_sha256": (
            context.predecessor.current.control_code_cohort_sha256
        ),
        "reviewed_control_code_successors": copy.deepcopy(
            list(context.control_code_successors)
        ),
        "reviewed_current_control_code_cohort": copy.deepcopy(
            list(context.current.control_code_cohort)
        ),
        "reviewed_current_control_code_cohort_sha256": (
            context.current.control_code_cohort_sha256
        ),
        "reviewed_acceptance_evidence_sources": copy.deepcopy(
            list(context.acceptance_evidence_sources)
        ),
        "reviewed_managed_closure_source_successors": copy.deepcopy(
            list(context.managed_closure_source_successors)
        ),
        "change_purpose": (
            "Bind the hermetic JAVA_HOME mismatch regression as the exact "
            "successor of the R003 verifier-test evidence and compose that "
            "single managed-closure delta into current goal-graph acceptance "
            "without rewriting R001, R002, R003, or the seq70/71 transition "
            "review."
        ),
        "acceptance": {
            "r014_transition_review_remains_immutable": True,
            "r001_control_successor_review_remains_immutable": True,
            "r002_control_successor_review_remains_immutable": True,
            "r003_control_successor_review_remains_immutable": True,
            "only_declared_control_paths_changed_within_reviewed_cohort": True,
            "r003_verifier_test_predecessor_is_exact": True,
            "hermetic_java_home_fixture_successor_is_exact": True,
            "managed_closure_source_successor_is_exact": True,
            "current_goal_graph_composes_r002_r003_r004": True,
            "completion_formal_external_and_release_credit_remain_zero": True,
        },
    }


def build_control_successor_r004_assignment(
    context: ControlSuccessorR004Context,
    *,
    assigned_at: str,
) -> str:
    assignment = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_ASSIGNMENT"
        ),
        "goal_id": GOAL_ID,
        "round_id": CONTROL_SUCCESSOR_R004_ROUND_ID,
        "assigned_at": assigned_at,
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": CONTROL_SUCCESSOR_R004_ASSIGNER_ID,
            "canonical_task": CONTROL_SUCCESSOR_R004_ASSIGNER_TASK,
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": CONTROL_SUCCESSOR_R004_EXECUTOR_ID,
            "canonical_task": CONTROL_SUCCESSOR_R004_EXECUTOR_TASK,
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": CONTROL_SUCCESSOR_R004_REVIEWER_ID,
            "canonical_task": CONTROL_SUCCESSOR_R004_REVIEWER_TASK,
        },
        "review_scope": _control_successor_r004_scope(context),
        "review_boundary": copy.deepcopy(BOUNDARY),
    }
    raw = json_text(assignment)
    validate_control_successor_r004_assignment(
        assignment,
        raw.encode(),
        context,
    )
    return raw


def validate_control_successor_r004_assignment(
    assignment: Mapping[str, Any],
    raw: bytes,
    context: ControlSuccessorR004Context,
) -> None:
    _validate_control_successor_assignment_envelope(
        assignment,
        raw,
        _control_successor_r004_scope(context),
        round_id=CONTROL_SUCCESSOR_R004_ROUND_ID,
        assigner_id=CONTROL_SUCCESSOR_R004_ASSIGNER_ID,
        assigner_task=CONTROL_SUCCESSOR_R004_ASSIGNER_TASK,
        executor_id=CONTROL_SUCCESSOR_R004_EXECUTOR_ID,
        executor_task=CONTROL_SUCCESSOR_R004_EXECUTOR_TASK,
        reviewer_id=CONTROL_SUCCESSOR_R004_REVIEWER_ID,
        reviewer_task=CONTROL_SUCCESSOR_R004_REVIEWER_TASK,
        label="control successor R004",
    )


def validate_control_successor_r004_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ControlSuccessorR004Context,
) -> None:
    validate_control_successor_r004_assignment(
        assignment,
        assignment_raw,
        context,
    )
    _validate_control_successor_result_envelope(
        result,
        raw,
        assignment,
        assignment_raw,
        _control_successor_r004_scope(context),
        round_id=CONTROL_SUCCESSOR_R004_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_R004_ASSIGNMENT_REL,
        label="control successor R004",
    )


def build_control_successor_r004_independent_review(
    context: ControlSuccessorR004Context,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> str:
    validate_control_successor_r004_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    return _build_control_successor_independent(
        assignment_raw,
        result,
        result_raw,
        round_id=CONTROL_SUCCESSOR_R004_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_R004_ASSIGNMENT_REL,
        result_rel=CONTROL_SUCCESSOR_R004_RESULT_REL,
    )


def validate_control_successor_r004_review(
    root: Path,
    context: ControlSuccessorR004Context,
) -> None:
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_R004_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_R004_RESULT_REL)
    validate_control_successor_r004_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    require(
        _raw(root, CONTROL_SUCCESSOR_R004_INDEPENDENT_REL)
        == build_control_successor_r004_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ).encode(),
        "control successor R004 independent review differs",
    )


def validated_control_successor_r004_context(
    root: Path = ROOT,
) -> ControlSuccessorR004Context:
    context, _bindings = prepare_frozen_control_successor_r004(root)
    return context


def prepare_frozen_control_successor_r004(
    root: Path = ROOT,
) -> tuple[ControlSuccessorR004Context, tuple[dict[str, Any], ...]]:
    """Replay approved control-successor R004 without live cohort bytes."""

    predecessor, predecessor_bindings = prepare_frozen_control_successor_r003(
        root
    )
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative in CONTROL_SUCCESSOR_R004_PATHS:
        document, raw = _document(root, relative)
        expected_sha256, expected_length = CONTROL_SUCCESSOR_R004_PINS[relative]
        require(
            bytes_sha256(raw) == expected_sha256 and len(raw) == expected_length,
            f"exact control successor R004 review differs: {relative}",
        )
        documents[relative] = document
        raw_by_path[relative] = raw

    assignment = documents[CONTROL_SUCCESSOR_R004_ASSIGNMENT_REL]
    scope = assignment.get("review_scope")
    require(
        type(scope) is dict
        and set(scope)
        == {
            "predecessor_control_successor_review_bindings",
            "predecessor_current_control_code_cohort_sha256",
            "reviewed_control_code_successors",
            "reviewed_current_control_code_cohort",
            "reviewed_current_control_code_cohort_sha256",
            "reviewed_acceptance_evidence_sources",
            "reviewed_managed_closure_source_successors",
            "change_purpose",
            "acceptance",
        },
        "control successor R004 scope differs",
    )
    require(
        scope["predecessor_control_successor_review_bindings"]
        == list(predecessor_bindings)
        and scope["predecessor_current_control_code_cohort_sha256"]
        == predecessor.current.control_code_cohort_sha256,
        "control successor R004 predecessor binding differs",
    )
    current_cohort = _validated_binding_cohort(
        scope["reviewed_current_control_code_cohort"],
        CONTROL_PATHS,
        "control successor R004 current control",
    )
    current_cohort_sha256 = scope["reviewed_current_control_code_cohort_sha256"]
    require(
        type(current_cohort_sha256) is str
        and current_cohort_sha256 == object_sha256(list(current_cohort)),
        "control successor R004 current control aggregate differs",
    )
    acceptance_evidence = tuple(
        {
            "path": relative.as_posix(),
            "sha256": pin[0],
            "byte_length": pin[1],
        }
        for relative, pin in CONTROL_SUCCESSOR_R004_EVIDENCE_PINS.items()
    )
    require(
        scope["reviewed_acceptance_evidence_sources"]
        == list(acceptance_evidence),
        "control successor R004 frozen evidence differs",
    )
    managed_sources = _validated_managed_source_successors(
        scope["reviewed_managed_closure_source_successors"],
        CONTROL_SUCCESSOR_R004_MANAGED_CLOSURE_SOURCE_PINS,
        "control successor R004 managed closure",
    )
    successors = scope["reviewed_control_code_successors"]
    require(type(successors) is list, "control successor R004 changes are missing")
    current = ReviewContext(
        root=root.resolve(strict=True),
        start_review_bindings=predecessor.current.start_review_bindings,
        completion_evidence_bindings=(
            predecessor.current.completion_evidence_bindings
        ),
        superseded_assignment_bindings=(
            predecessor.current.superseded_assignment_bindings
        ),
        control_code_cohort=current_cohort,
        control_code_cohort_sha256=current_cohort_sha256,
    )
    frozen = _control_successor_r004_context(
        root,
        predecessor,
        predecessor_bindings,
        current,
        successors,
        acceptance_evidence,
        managed_sources,
    )
    validate_control_successor_r004_review(root, frozen)
    return frozen, tuple(
        _binding(path, raw_by_path[path])
        for path in CONTROL_SUCCESSOR_R004_PATHS
    )


def _control_successor_live_context(
    root: Path,
    predecessor: Any,
    predecessor_bindings: tuple[dict[str, Any], ...],
    current: ReviewContext,
    *,
    changed_paths: set[Path],
    label: str,
    context_ctor: Any,
    successors: Sequence[Mapping[str, Any]] | None = None,
) -> Any:
    before_by_path = {
        row["path"]: row for row in predecessor.current.control_code_cohort
    }
    after_by_path = {row["path"]: row for row in current.control_code_cohort}
    require(
        set(before_by_path) == set(after_by_path),
        f"control successor {label} cohort path set differs",
    )
    expected = tuple(
        {
            "path": path.as_posix(),
            "predecessor": copy.deepcopy(before_by_path[path.as_posix()]),
            "successor": copy.deepcopy(after_by_path[path.as_posix()]),
        }
        for path in CONTROL_PATHS
        if before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
    )
    require(
        {row["path"] for row in expected}
        == {path.as_posix() for path in changed_paths},
        f"control successor {label} changed path set differs",
    )
    if successors is not None:
        require(
            tuple(successors) == expected,
            f"control successor {label} change binding differs",
        )
    return context_ctor(
        root=root.resolve(strict=True),
        current=current,
        predecessor=predecessor,
        predecessor_review_bindings=predecessor_bindings,
        control_code_successors=copy.deepcopy(
            expected if successors is None else tuple(successors)
        ),
    )


def _control_successor_r005_context(
    root: Path,
    predecessor: ControlSuccessorR004Context,
    predecessor_bindings: tuple[dict[str, Any], ...],
    current: ReviewContext,
    successors: Sequence[Mapping[str, Any]] | None = None,
) -> ControlSuccessorR005Context:
    return _control_successor_live_context(
        root,
        predecessor,
        predecessor_bindings,
        current,
        changed_paths=CONTROL_SUCCESSOR_R005_CHANGED_PATHS,
        label="R005",
        context_ctor=ControlSuccessorR005Context,
        successors=successors,
    )


def prepare_control_successor_r005_context(
    root: Path = ROOT,
) -> ControlSuccessorR005Context:
    predecessor, predecessor_bindings = prepare_frozen_control_successor_r004(root)
    return _control_successor_r005_context(
        root,
        predecessor,
        predecessor_bindings,
        prepare_review_context(root),
    )


def _control_successor_r005_scope(
    context: ControlSuccessorR005Context,
) -> dict[str, Any]:
    return {
        "predecessor_control_successor_review_bindings": copy.deepcopy(
            list(context.predecessor_review_bindings)
        ),
        "predecessor_current_control_code_cohort_sha256": (
            context.predecessor.current.control_code_cohort_sha256
        ),
        "reviewed_control_code_successors": copy.deepcopy(
            list(context.control_code_successors)
        ),
        "reviewed_current_control_code_cohort": copy.deepcopy(
            list(context.current.control_code_cohort)
        ),
        "reviewed_current_control_code_cohort_sha256": (
            context.current.control_code_cohort_sha256
        ),
        "change_purpose": (
            "Bind the standalone continuation-checker import bootstrap and "
            "the corrected current-control tests as the exact successor of "
            "the frozen R004 control cohort without rewriting R001, R002, "
            "R003, R004, or the seq70/71 transition review."
        ),
        "acceptance": {
            "r014_transition_review_remains_immutable": True,
            "r001_control_successor_review_remains_immutable": True,
            "r002_control_successor_review_remains_immutable": True,
            "r003_control_successor_review_remains_immutable": True,
            "r004_control_successor_review_remains_immutable": True,
            "only_declared_control_paths_changed_within_reviewed_cohort": True,
            "r004_review_triplet_is_replayed_from_exact_pins": True,
            "standalone_continuation_import_bootstrap_is_exact": True,
            "corrected_targeted_control_tests_are_exact": True,
            "current_goal_graph_composes_r002_r003_r004_r005": True,
            "completion_formal_external_and_release_credit_remain_zero": True,
        },
    }


def build_control_successor_r005_assignment(
    context: ControlSuccessorR005Context,
    *,
    assigned_at: str,
) -> str:
    assignment = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_ASSIGNMENT"
        ),
        "goal_id": GOAL_ID,
        "round_id": CONTROL_SUCCESSOR_R005_ROUND_ID,
        "assigned_at": assigned_at,
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": CONTROL_SUCCESSOR_R005_ASSIGNER_ID,
            "canonical_task": CONTROL_SUCCESSOR_R005_ASSIGNER_TASK,
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": CONTROL_SUCCESSOR_R005_EXECUTOR_ID,
            "canonical_task": CONTROL_SUCCESSOR_R005_EXECUTOR_TASK,
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": CONTROL_SUCCESSOR_R005_REVIEWER_ID,
            "canonical_task": CONTROL_SUCCESSOR_R005_REVIEWER_TASK,
        },
        "review_scope": _control_successor_r005_scope(context),
        "review_boundary": copy.deepcopy(BOUNDARY),
    }
    raw = json_text(assignment)
    validate_control_successor_r005_assignment(assignment, raw.encode(), context)
    return raw


def validate_control_successor_r005_assignment(
    assignment: Mapping[str, Any],
    raw: bytes,
    context: ControlSuccessorR005Context,
) -> None:
    _validate_control_successor_assignment_envelope(
        assignment,
        raw,
        _control_successor_r005_scope(context),
        round_id=CONTROL_SUCCESSOR_R005_ROUND_ID,
        assigner_id=CONTROL_SUCCESSOR_R005_ASSIGNER_ID,
        assigner_task=CONTROL_SUCCESSOR_R005_ASSIGNER_TASK,
        executor_id=CONTROL_SUCCESSOR_R005_EXECUTOR_ID,
        executor_task=CONTROL_SUCCESSOR_R005_EXECUTOR_TASK,
        reviewer_id=CONTROL_SUCCESSOR_R005_REVIEWER_ID,
        reviewer_task=CONTROL_SUCCESSOR_R005_REVIEWER_TASK,
        label="control successor R005",
    )


def validate_control_successor_r005_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ControlSuccessorR005Context,
) -> None:
    validate_control_successor_r005_assignment(assignment, assignment_raw, context)
    _validate_control_successor_result_envelope(
        result,
        raw,
        assignment,
        assignment_raw,
        _control_successor_r005_scope(context),
        round_id=CONTROL_SUCCESSOR_R005_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_R005_ASSIGNMENT_REL,
        label="control successor R005",
    )


def build_control_successor_r005_independent_review(
    context: ControlSuccessorR005Context,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> str:
    validate_control_successor_r005_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    return _build_control_successor_independent(
        assignment_raw,
        result,
        result_raw,
        round_id=CONTROL_SUCCESSOR_R005_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_R005_ASSIGNMENT_REL,
        result_rel=CONTROL_SUCCESSOR_R005_RESULT_REL,
    )


def validate_control_successor_r005_review(
    root: Path,
    context: ControlSuccessorR005Context,
) -> None:
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_R005_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_R005_RESULT_REL)
    validate_control_successor_r005_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    require(
        _raw(root, CONTROL_SUCCESSOR_R005_INDEPENDENT_REL)
        == build_control_successor_r005_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ).encode(),
        "control successor R005 independent review differs",
    )


def prepare_frozen_control_successor_r005(
    root: Path = ROOT,
) -> tuple[ControlSuccessorR005Context, tuple[dict[str, Any], ...]]:
    """Replay approved control-successor R005 without live cohort bytes."""

    predecessor, predecessor_bindings = prepare_frozen_control_successor_r004(root)
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative in CONTROL_SUCCESSOR_R005_PATHS:
        document, raw = _document(root, relative)
        expected_sha256, expected_length = CONTROL_SUCCESSOR_R005_PINS[relative]
        require(
            bytes_sha256(raw) == expected_sha256 and len(raw) == expected_length,
            f"exact control successor R005 review differs: {relative}",
        )
        documents[relative] = document
        raw_by_path[relative] = raw

    assignment = documents[CONTROL_SUCCESSOR_R005_ASSIGNMENT_REL]
    scope = assignment.get("review_scope")
    require(
        type(scope) is dict
        and set(scope)
        == {
            "predecessor_control_successor_review_bindings",
            "predecessor_current_control_code_cohort_sha256",
            "reviewed_control_code_successors",
            "reviewed_current_control_code_cohort",
            "reviewed_current_control_code_cohort_sha256",
            "change_purpose",
            "acceptance",
        },
        "control successor R005 scope differs",
    )
    require(
        scope["predecessor_control_successor_review_bindings"]
        == list(predecessor_bindings)
        and scope["predecessor_current_control_code_cohort_sha256"]
        == predecessor.current.control_code_cohort_sha256,
        "control successor R005 predecessor binding differs",
    )
    current_cohort = _validated_binding_cohort(
        scope["reviewed_current_control_code_cohort"],
        CONTROL_PATHS,
        "control successor R005 current control",
    )
    current_cohort_sha256 = scope["reviewed_current_control_code_cohort_sha256"]
    require(
        type(current_cohort_sha256) is str
        and current_cohort_sha256 == object_sha256(list(current_cohort)),
        "control successor R005 current control aggregate differs",
    )
    successors = scope["reviewed_control_code_successors"]
    require(type(successors) is list, "control successor R005 changes are missing")
    current = ReviewContext(
        root=root.resolve(strict=True),
        start_review_bindings=predecessor.current.start_review_bindings,
        completion_evidence_bindings=predecessor.current.completion_evidence_bindings,
        superseded_assignment_bindings=predecessor.current.superseded_assignment_bindings,
        control_code_cohort=current_cohort,
        control_code_cohort_sha256=current_cohort_sha256,
    )
    frozen = _control_successor_r005_context(
        root,
        predecessor,
        predecessor_bindings,
        current,
        successors,
    )
    validate_control_successor_r005_review(root, frozen)
    return frozen, tuple(
        _binding(path, raw_by_path[path])
        for path in CONTROL_SUCCESSOR_R005_PATHS
    )


def validated_control_successor_r005_context(
    root: Path = ROOT,
) -> ControlSuccessorR005Context:
    context, _bindings = prepare_frozen_control_successor_r005(root)
    return context


def prepare_control_successor_r006_context(
    root: Path = ROOT,
) -> ControlSuccessorR006Context:
    predecessor, predecessor_bindings = prepare_frozen_control_successor_r005(root)
    return _control_successor_live_context(
        root,
        predecessor,
        predecessor_bindings,
        prepare_review_context(root),
        changed_paths=CONTROL_SUCCESSOR_R006_CHANGED_PATHS,
        label="R006",
        context_ctor=ControlSuccessorR006Context,
    )


def _control_successor_r006_scope(
    context: ControlSuccessorR006Context,
) -> dict[str, Any]:
    return {
        "predecessor_control_successor_review_bindings": copy.deepcopy(
            list(context.predecessor_review_bindings)
        ),
        "predecessor_current_control_code_cohort_sha256": (
            context.predecessor.current.control_code_cohort_sha256
        ),
        "reviewed_control_code_successors": copy.deepcopy(
            list(context.control_code_successors)
        ),
        "reviewed_current_control_code_cohort": copy.deepcopy(
            list(context.current.control_code_cohort)
        ),
        "reviewed_current_control_code_cohort_sha256": (
            context.current.control_code_cohort_sha256
        ),
        "change_purpose": (
            "Correct stale FP014 successor fixtures to compose the exact "
            "zero-credit current-security compatibility successors without "
            "rewriting R001, R002, R003, R004, R005, or the seq70/71 "
            "transition review."
        ),
        "acceptance": {
            "r014_transition_review_remains_immutable": True,
            "r001_control_successor_review_remains_immutable": True,
            "r002_control_successor_review_remains_immutable": True,
            "r003_control_successor_review_remains_immutable": True,
            "r004_control_successor_review_remains_immutable": True,
            "r005_control_successor_review_remains_immutable": True,
            "r005_review_triplet_is_replayed_from_exact_pins": True,
            "only_declared_control_paths_changed_within_reviewed_cohort": True,
            "current_security_zero_credit_fixture_is_exact": True,
            "current_goal_graph_composes_r002_r003_r004_r005_r006": True,
            "completion_formal_external_and_release_credit_remain_zero": True,
        },
    }


def build_control_successor_r006_assignment(
    context: ControlSuccessorR006Context,
    *,
    assigned_at: str,
) -> str:
    assignment = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_ASSIGNMENT"
        ),
        "goal_id": GOAL_ID,
        "round_id": CONTROL_SUCCESSOR_R006_ROUND_ID,
        "assigned_at": assigned_at,
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": CONTROL_SUCCESSOR_R006_ASSIGNER_ID,
            "canonical_task": CONTROL_SUCCESSOR_R006_ASSIGNER_TASK,
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": CONTROL_SUCCESSOR_R006_EXECUTOR_ID,
            "canonical_task": CONTROL_SUCCESSOR_R006_EXECUTOR_TASK,
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": CONTROL_SUCCESSOR_R006_REVIEWER_ID,
            "canonical_task": CONTROL_SUCCESSOR_R006_REVIEWER_TASK,
        },
        "review_scope": _control_successor_r006_scope(context),
        "review_boundary": copy.deepcopy(BOUNDARY),
    }
    raw = json_text(assignment)
    validate_control_successor_r006_assignment(assignment, raw.encode(), context)
    return raw


def validate_control_successor_r006_assignment(
    assignment: Mapping[str, Any],
    raw: bytes,
    context: ControlSuccessorR006Context,
) -> None:
    _validate_control_successor_assignment_envelope(
        assignment,
        raw,
        _control_successor_r006_scope(context),
        round_id=CONTROL_SUCCESSOR_R006_ROUND_ID,
        assigner_id=CONTROL_SUCCESSOR_R006_ASSIGNER_ID,
        assigner_task=CONTROL_SUCCESSOR_R006_ASSIGNER_TASK,
        executor_id=CONTROL_SUCCESSOR_R006_EXECUTOR_ID,
        executor_task=CONTROL_SUCCESSOR_R006_EXECUTOR_TASK,
        reviewer_id=CONTROL_SUCCESSOR_R006_REVIEWER_ID,
        reviewer_task=CONTROL_SUCCESSOR_R006_REVIEWER_TASK,
        label="control successor R006",
    )


def validate_control_successor_r006_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ControlSuccessorR006Context,
) -> None:
    validate_control_successor_r006_assignment(assignment, assignment_raw, context)
    _validate_control_successor_result_envelope(
        result,
        raw,
        assignment,
        assignment_raw,
        _control_successor_r006_scope(context),
        round_id=CONTROL_SUCCESSOR_R006_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_R006_ASSIGNMENT_REL,
        label="control successor R006",
    )


def build_control_successor_r006_independent_review(
    context: ControlSuccessorR006Context,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> str:
    validate_control_successor_r006_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    return _build_control_successor_independent(
        assignment_raw,
        result,
        result_raw,
        round_id=CONTROL_SUCCESSOR_R006_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_R006_ASSIGNMENT_REL,
        result_rel=CONTROL_SUCCESSOR_R006_RESULT_REL,
    )


def validate_control_successor_r006_review(
    root: Path,
    context: ControlSuccessorR006Context,
) -> None:
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_R006_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_R006_RESULT_REL)
    validate_control_successor_r006_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    require(
        _raw(root, CONTROL_SUCCESSOR_R006_INDEPENDENT_REL)
        == build_control_successor_r006_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ).encode(),
        "control successor R006 independent review differs",
    )


def prepare_frozen_control_successor_r006(
    root: Path = ROOT,
) -> tuple[ControlSuccessorR006Context, tuple[dict[str, Any], ...]]:
    """Replay approved control-successor R006 without live cohort bytes."""

    predecessor, predecessor_bindings = prepare_frozen_control_successor_r005(root)
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative in CONTROL_SUCCESSOR_R006_PATHS:
        document, raw = _document(root, relative)
        expected_sha256, expected_length = CONTROL_SUCCESSOR_R006_PINS[relative]
        require(
            bytes_sha256(raw) == expected_sha256 and len(raw) == expected_length,
            f"exact control successor R006 review differs: {relative}",
        )
        documents[relative] = document
        raw_by_path[relative] = raw

    assignment = documents[CONTROL_SUCCESSOR_R006_ASSIGNMENT_REL]
    scope = assignment.get("review_scope")
    require(
        type(scope) is dict
        and set(scope)
        == {
            "predecessor_control_successor_review_bindings",
            "predecessor_current_control_code_cohort_sha256",
            "reviewed_control_code_successors",
            "reviewed_current_control_code_cohort",
            "reviewed_current_control_code_cohort_sha256",
            "change_purpose",
            "acceptance",
        },
        "control successor R006 scope differs",
    )
    require(
        scope["predecessor_control_successor_review_bindings"]
        == list(predecessor_bindings)
        and scope["predecessor_current_control_code_cohort_sha256"]
        == predecessor.current.control_code_cohort_sha256,
        "control successor R006 predecessor binding differs",
    )
    current_cohort = _validated_binding_cohort(
        scope["reviewed_current_control_code_cohort"],
        CONTROL_PATHS,
        "control successor R006 current control",
    )
    current_cohort_sha256 = scope["reviewed_current_control_code_cohort_sha256"]
    require(
        type(current_cohort_sha256) is str
        and current_cohort_sha256 == object_sha256(list(current_cohort)),
        "control successor R006 current control aggregate differs",
    )
    successors = scope["reviewed_control_code_successors"]
    require(type(successors) is list, "control successor R006 changes are missing")
    current = ReviewContext(
        root=root.resolve(strict=True),
        start_review_bindings=predecessor.current.start_review_bindings,
        completion_evidence_bindings=predecessor.current.completion_evidence_bindings,
        superseded_assignment_bindings=predecessor.current.superseded_assignment_bindings,
        control_code_cohort=current_cohort,
        control_code_cohort_sha256=current_cohort_sha256,
    )
    frozen = _control_successor_live_context(
        root,
        predecessor,
        predecessor_bindings,
        current,
        changed_paths=CONTROL_SUCCESSOR_R006_CHANGED_PATHS,
        label="R006",
        context_ctor=ControlSuccessorR006Context,
        successors=successors,
    )
    validate_control_successor_r006_review(root, frozen)
    return frozen, tuple(
        _binding(path, raw_by_path[path])
        for path in CONTROL_SUCCESSOR_R006_PATHS
    )


def validated_control_successor_r006_context(
    root: Path = ROOT,
) -> ControlSuccessorR006Context:
    context, _bindings = prepare_frozen_control_successor_r006(root)
    return context


def prepare_control_successor_r007_context(
    root: Path = ROOT,
) -> ControlSuccessorR007Context:
    predecessor, predecessor_bindings = prepare_frozen_control_successor_r006(root)
    return _control_successor_live_context(
        root,
        predecessor,
        predecessor_bindings,
        prepare_review_context(root),
        changed_paths=CONTROL_SUCCESSOR_R007_CHANGED_PATHS,
        label="R007",
        context_ctor=ControlSuccessorR007Context,
    )


def _control_successor_r007_scope(
    context: ControlSuccessorR007Context,
) -> dict[str, Any]:
    return {
        "predecessor_control_successor_review_bindings": copy.deepcopy(
            list(context.predecessor_review_bindings)
        ),
        "predecessor_current_control_code_cohort_sha256": (
            context.predecessor.current.control_code_cohort_sha256
        ),
        "reviewed_control_code_successors": copy.deepcopy(
            list(context.control_code_successors)
        ),
        "reviewed_current_control_code_cohort": copy.deepcopy(
            list(context.current.control_code_cohort)
        ),
        "reviewed_current_control_code_cohort_sha256": (
            context.current.control_code_cohort_sha256
        ),
        "change_purpose": (
            "Bind the generic future canonical dependency-closure replay "
            "grammar and its exact control tests without rewriting R001, "
            "R002, R003, R004, R005, R006, or the seq70/71 transition review."
        ),
        "acceptance": {
            "r014_transition_review_remains_immutable": True,
            "r001_control_successor_review_remains_immutable": True,
            "r002_control_successor_review_remains_immutable": True,
            "r003_control_successor_review_remains_immutable": True,
            "r004_control_successor_review_remains_immutable": True,
            "r005_control_successor_review_remains_immutable": True,
            "r006_control_successor_review_remains_immutable": True,
            "r006_review_triplet_is_replayed_from_exact_pins": True,
            "only_declared_control_paths_changed_within_reviewed_cohort": True,
            "generic_dependency_closure_replay_is_exact": True,
            "current_goal_graph_composes_r002_r003_r004_r005_r006_r007": True,
            "completion_formal_external_and_release_credit_remain_zero": True,
        },
    }


def build_control_successor_r007_assignment(
    context: ControlSuccessorR007Context,
    *,
    assigned_at: str,
) -> str:
    assignment = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_ASSIGNMENT"
        ),
        "goal_id": GOAL_ID,
        "round_id": CONTROL_SUCCESSOR_R007_ROUND_ID,
        "assigned_at": assigned_at,
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": CONTROL_SUCCESSOR_R007_ASSIGNER_ID,
            "canonical_task": CONTROL_SUCCESSOR_R007_ASSIGNER_TASK,
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": CONTROL_SUCCESSOR_R007_EXECUTOR_ID,
            "canonical_task": CONTROL_SUCCESSOR_R007_EXECUTOR_TASK,
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": CONTROL_SUCCESSOR_R007_REVIEWER_ID,
            "canonical_task": CONTROL_SUCCESSOR_R007_REVIEWER_TASK,
        },
        "review_scope": _control_successor_r007_scope(context),
        "review_boundary": copy.deepcopy(BOUNDARY),
    }
    raw = json_text(assignment)
    validate_control_successor_r007_assignment(assignment, raw.encode(), context)
    return raw


def validate_control_successor_r007_assignment(
    assignment: Mapping[str, Any],
    raw: bytes,
    context: ControlSuccessorR007Context,
) -> None:
    _validate_control_successor_assignment_envelope(
        assignment,
        raw,
        _control_successor_r007_scope(context),
        round_id=CONTROL_SUCCESSOR_R007_ROUND_ID,
        assigner_id=CONTROL_SUCCESSOR_R007_ASSIGNER_ID,
        assigner_task=CONTROL_SUCCESSOR_R007_ASSIGNER_TASK,
        executor_id=CONTROL_SUCCESSOR_R007_EXECUTOR_ID,
        executor_task=CONTROL_SUCCESSOR_R007_EXECUTOR_TASK,
        reviewer_id=CONTROL_SUCCESSOR_R007_REVIEWER_ID,
        reviewer_task=CONTROL_SUCCESSOR_R007_REVIEWER_TASK,
        label="control successor R007",
    )


def validate_control_successor_r007_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ControlSuccessorR007Context,
) -> None:
    validate_control_successor_r007_assignment(assignment, assignment_raw, context)
    _validate_control_successor_result_envelope(
        result,
        raw,
        assignment,
        assignment_raw,
        _control_successor_r007_scope(context),
        round_id=CONTROL_SUCCESSOR_R007_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_R007_ASSIGNMENT_REL,
        label="control successor R007",
    )


def build_control_successor_r007_independent_review(
    context: ControlSuccessorR007Context,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> str:
    validate_control_successor_r007_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    return _build_control_successor_independent(
        assignment_raw,
        result,
        result_raw,
        round_id=CONTROL_SUCCESSOR_R007_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_R007_ASSIGNMENT_REL,
        result_rel=CONTROL_SUCCESSOR_R007_RESULT_REL,
    )


def validate_control_successor_r007_review(
    root: Path,
    context: ControlSuccessorR007Context,
) -> None:
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_R007_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_R007_RESULT_REL)
    validate_control_successor_r007_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    require(
        _raw(root, CONTROL_SUCCESSOR_R007_INDEPENDENT_REL)
        == build_control_successor_r007_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ).encode(),
        "control successor R007 independent review differs",
    )


def prepare_frozen_control_successor_r007(
    root: Path = ROOT,
) -> tuple[ControlSuccessorR007Context, tuple[dict[str, Any], ...]]:
    """Replay approved control-successor R007 without live cohort bytes."""

    predecessor, predecessor_bindings = prepare_frozen_control_successor_r006(root)
    documents: dict[Path, dict[str, Any]] = {}
    raw_by_path: dict[Path, bytes] = {}
    for relative in CONTROL_SUCCESSOR_R007_PATHS:
        document, raw = _document(root, relative)
        expected_sha256, expected_length = CONTROL_SUCCESSOR_R007_PINS[relative]
        require(
            bytes_sha256(raw) == expected_sha256 and len(raw) == expected_length,
            f"exact control successor R007 review differs: {relative}",
        )
        documents[relative] = document
        raw_by_path[relative] = raw

    assignment = documents[CONTROL_SUCCESSOR_R007_ASSIGNMENT_REL]
    scope = assignment.get("review_scope")
    require(
        type(scope) is dict
        and set(scope)
        == {
            "predecessor_control_successor_review_bindings",
            "predecessor_current_control_code_cohort_sha256",
            "reviewed_control_code_successors",
            "reviewed_current_control_code_cohort",
            "reviewed_current_control_code_cohort_sha256",
            "change_purpose",
            "acceptance",
        },
        "control successor R007 scope differs",
    )
    require(
        scope["predecessor_control_successor_review_bindings"]
        == list(predecessor_bindings)
        and scope["predecessor_current_control_code_cohort_sha256"]
        == predecessor.current.control_code_cohort_sha256,
        "control successor R007 predecessor binding differs",
    )
    current_cohort = _validated_binding_cohort(
        scope["reviewed_current_control_code_cohort"],
        CONTROL_PATHS,
        "control successor R007 current control",
    )
    current_cohort_sha256 = scope["reviewed_current_control_code_cohort_sha256"]
    require(
        type(current_cohort_sha256) is str
        and current_cohort_sha256 == object_sha256(list(current_cohort)),
        "control successor R007 current control aggregate differs",
    )
    successors = scope["reviewed_control_code_successors"]
    require(type(successors) is list, "control successor R007 changes are missing")
    current = ReviewContext(
        root=root.resolve(strict=True),
        start_review_bindings=predecessor.current.start_review_bindings,
        completion_evidence_bindings=predecessor.current.completion_evidence_bindings,
        superseded_assignment_bindings=predecessor.current.superseded_assignment_bindings,
        control_code_cohort=current_cohort,
        control_code_cohort_sha256=current_cohort_sha256,
    )
    frozen = _control_successor_live_context(
        root,
        predecessor,
        predecessor_bindings,
        current,
        changed_paths=CONTROL_SUCCESSOR_R007_CHANGED_PATHS,
        label="R007",
        context_ctor=ControlSuccessorR007Context,
        successors=successors,
    )
    validate_control_successor_r007_review(root, frozen)
    return frozen, tuple(
        _binding(path, raw_by_path[path])
        for path in CONTROL_SUCCESSOR_R007_PATHS
    )


def validated_control_successor_r007_context(
    root: Path = ROOT,
) -> ControlSuccessorR007Context:
    context, _bindings = prepare_frozen_control_successor_r007(root)
    return context


def _r008_live_current_review_context(root: Path) -> ReviewContext:
    current = prepare_review_context(root)
    require(
        tuple(row["path"] for row in current.control_code_cohort)
        == tuple(path.as_posix() for path in CONTROL_PATHS),
        "control successor R008 live predecessor universe differs",
    )
    added = _bind_paths(root, CONTROL_SUCCESSOR_R008_ADDED_CONTROL_PATHS)
    cohort = (*current.control_code_cohort, *added)
    require(
        tuple(row["path"] for row in cohort)
        == tuple(path.as_posix() for path in CONTROL_SUCCESSOR_R008_COHORT_PATHS)
        and len({row["path"] for row in cohort}) == len(cohort),
        "control successor R008 current cohort inventory differs",
    )
    return ReviewContext(
        root=current.root,
        start_review_bindings=current.start_review_bindings,
        completion_evidence_bindings=current.completion_evidence_bindings,
        superseded_assignment_bindings=current.superseded_assignment_bindings,
        control_code_cohort=cohort,
        control_code_cohort_sha256=object_sha256(list(cohort)),
    )


def prepare_control_successor_r008_context(
    root: Path = ROOT,
) -> ControlSuccessorR008Context:
    predecessor, predecessor_bindings = prepare_frozen_control_successor_r007(root)
    current = _r008_live_current_review_context(root)
    before_by_path = {
        row["path"]: row for row in predecessor.current.control_code_cohort
    }
    after_by_path = {row["path"]: row for row in current.control_code_cohort}
    require(
        tuple(before_by_path)
        == tuple(path.as_posix() for path in CONTROL_PATHS)
        and tuple(after_by_path)
        == tuple(path.as_posix() for path in CONTROL_SUCCESSOR_R008_COHORT_PATHS),
        "control successor R008 cohort path order differs",
    )
    successors = tuple(
        {
            "path": path.as_posix(),
            "predecessor": copy.deepcopy(before_by_path[path.as_posix()]),
            "successor": copy.deepcopy(after_by_path[path.as_posix()]),
        }
        for path in CONTROL_PATHS
        if before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
    )
    require(
        {row["path"] for row in successors}
        == {path.as_posix() for path in CONTROL_SUCCESSOR_R008_CHANGED_PATHS},
        "control successor R008 changed path set differs",
    )
    added = tuple(
        _validated_binding(
            after_by_path[path.as_posix()],
            path.as_posix(),
            "control successor R008 added control",
        )
        for path in CONTROL_SUCCESSOR_R008_ADDED_CONTROL_PATHS
    )
    require(
        {row["path"] for row in added}
        == {path.as_posix() for path in CONTROL_SUCCESSOR_R008_ADDED_CONTROL_PATHS}
        and not ({row["path"] for row in successors} & {row["path"] for row in added}),
        "control successor R008 added path set differs",
    )
    return ControlSuccessorR008Context(
        root=root.resolve(strict=True),
        current=current,
        predecessor=predecessor,
        predecessor_review_bindings=predecessor_bindings,
        control_code_successors=copy.deepcopy(successors),
        added_control_code_bindings=copy.deepcopy(added),
    )


def _control_successor_r008_scope(
    context: ControlSuccessorR008Context,
) -> dict[str, Any]:
    existing = context.control_code_successors
    added = context.added_control_code_bindings
    require(
        len(context.predecessor.current.control_code_cohort) == len(CONTROL_PATHS)
        and len(context.current.control_code_cohort)
        == len(CONTROL_SUCCESSOR_R008_COHORT_PATHS)
        and len(existing) == len(CONTROL_SUCCESSOR_R008_CHANGED_PATHS)
        and len(added) == len(CONTROL_SUCCESSOR_R008_ADDED_CONTROL_PATHS),
        "control successor R008 cohort counts differ",
    )
    return {
        "predecessor_control_successor_review_bindings": copy.deepcopy(
            list(context.predecessor_review_bindings)
        ),
        "predecessor_current_control_code_cohort_sha256": (
            context.predecessor.current.control_code_cohort_sha256
        ),
        "predecessor_control_code_cohort_path_count": len(
            context.predecessor.current.control_code_cohort
        ),
        "reviewed_control_code_successors": copy.deepcopy(list(existing)),
        "reviewed_existing_control_code_successor_path_count": len(existing),
        "reviewed_added_control_code_bindings": copy.deepcopy(list(added)),
        "reviewed_added_control_code_binding_path_count": len(added),
        "reviewed_semantic_control_code_path_count": len(existing) + len(added),
        "reviewed_current_control_code_cohort": copy.deepcopy(
            list(context.current.control_code_cohort)
        ),
        "reviewed_current_control_code_cohort_path_count": len(
            context.current.control_code_cohort
        ),
        "reviewed_current_control_code_cohort_sha256": (
            context.current.control_code_cohort_sha256
        ),
        "implementation_contributor_tasks": [
            "/root/r008_control_tail_impl",
            "/root/r008_continuation_fix",
            "/root/r008_goalgraph_archive_fix",
            "/root/r029_canonical_bridge_impl",
            "/root/r008_transaction_impl",
        ],
        "change_purpose": (
            "Bind the exact R008 semantic control cohort: eight changed "
            "existing controls and eight newly introduced R029/reopen "
            "controls, while replaying frozen R007 rather than treating "
            "the new paths as if they had predecessor bytes."
        ),
        "acceptance": {
            "r014_transition_review_remains_immutable": True,
            "r001_control_successor_review_remains_immutable": True,
            "r002_control_successor_review_remains_immutable": True,
            "r003_control_successor_review_remains_immutable": True,
            "r004_control_successor_review_remains_immutable": True,
            "r005_control_successor_review_remains_immutable": True,
            "r006_control_successor_review_remains_immutable": True,
            "r007_control_successor_review_remains_immutable": True,
            "r007_review_triplet_is_replayed_from_exact_pins": True,
            "predecessor_control_cohort_has_exactly_15_paths": True,
            "current_control_cohort_has_exactly_23_paths": True,
            "semantic_control_delta_has_exactly_16_paths": True,
            "eight_existing_paths_have_exact_predecessor_successor_bindings": True,
            "eight_added_paths_have_bindings_without_fabricated_predecessors": True,
            "r029_canonical_publication_is_add_only_and_byte_exact": True,
            "r029_discovery_outputs_remain_candidate_only": True,
            "seq72_binds_canonical_r029_not_candidate_outputs": True,
            "legacy_backlog_wildcard_narrows_only_under_exact_compatibility": True,
            "seq72_through_seq76_reopen_only_the_declared_fp046_npc_epic03_closure": True,
            "catalog_script_policy_is_exact_for_all_r008_controls": True,
            "completion_formal_external_and_release_credit_remain_zero": True,
        },
    }


def build_control_successor_r008_assignment(
    context: ControlSuccessorR008Context,
    *,
    assigned_at: str,
) -> str:
    assignment = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_ASSIGNMENT"
        ),
        "goal_id": GOAL_ID,
        "round_id": CONTROL_SUCCESSOR_R008_ROUND_ID,
        "assigned_at": assigned_at,
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": CONTROL_SUCCESSOR_R008_ASSIGNER_ID,
            "canonical_task": CONTROL_SUCCESSOR_R008_ASSIGNER_TASK,
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": CONTROL_SUCCESSOR_R008_EXECUTOR_ID,
            "canonical_task": CONTROL_SUCCESSOR_R008_EXECUTOR_TASK,
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": CONTROL_SUCCESSOR_R008_REVIEWER_ID,
            "canonical_task": CONTROL_SUCCESSOR_R008_REVIEWER_TASK,
        },
        "review_scope": _control_successor_r008_scope(context),
        "review_boundary": copy.deepcopy(BOUNDARY),
    }
    raw = json_text(assignment)
    validate_control_successor_r008_assignment(assignment, raw.encode(), context)
    return raw


def validate_control_successor_r008_assignment(
    assignment: Mapping[str, Any],
    raw: bytes,
    context: ControlSuccessorR008Context,
) -> None:
    _validate_control_successor_assignment_envelope(
        assignment,
        raw,
        _control_successor_r008_scope(context),
        round_id=CONTROL_SUCCESSOR_R008_ROUND_ID,
        assigner_id=CONTROL_SUCCESSOR_R008_ASSIGNER_ID,
        assigner_task=CONTROL_SUCCESSOR_R008_ASSIGNER_TASK,
        executor_id=CONTROL_SUCCESSOR_R008_EXECUTOR_ID,
        executor_task=CONTROL_SUCCESSOR_R008_EXECUTOR_TASK,
        reviewer_id=CONTROL_SUCCESSOR_R008_REVIEWER_ID,
        reviewer_task=CONTROL_SUCCESSOR_R008_REVIEWER_TASK,
        label="control successor R008",
    )


def validate_control_successor_r008_result(
    result: Mapping[str, Any],
    raw: bytes,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    context: ControlSuccessorR008Context,
) -> None:
    validate_control_successor_r008_assignment(assignment, assignment_raw, context)
    _validate_control_successor_result_envelope(
        result,
        raw,
        assignment,
        assignment_raw,
        _control_successor_r008_scope(context),
        round_id=CONTROL_SUCCESSOR_R008_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_R008_ASSIGNMENT_REL,
        label="control successor R008",
    )


def build_control_successor_r008_independent_review(
    context: ControlSuccessorR008Context,
    assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
) -> str:
    validate_control_successor_r008_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    return _build_control_successor_independent(
        assignment_raw,
        result,
        result_raw,
        round_id=CONTROL_SUCCESSOR_R008_ROUND_ID,
        assignment_rel=CONTROL_SUCCESSOR_R008_ASSIGNMENT_REL,
        result_rel=CONTROL_SUCCESSOR_R008_RESULT_REL,
    )


def validate_control_successor_r008_review(
    root: Path,
    context: ControlSuccessorR008Context,
) -> None:
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_R008_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_R008_RESULT_REL)
    validate_control_successor_r008_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    require(
        _raw(root, CONTROL_SUCCESSOR_R008_INDEPENDENT_REL)
        == build_control_successor_r008_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ).encode(),
        "control successor R008 independent review differs",
    )


def validated_control_successor_r008_context(
    root: Path = ROOT,
) -> ControlSuccessorR008Context:
    context = prepare_control_successor_r008_context(root)
    validate_control_successor_r008_review(root, context)
    return context


def validated_control_successor_managed_closure_sources(
    root: Path = ROOT,
) -> tuple[dict[str, Any], ...]:
    """Return the frozen R004 source successors for the current graph."""

    context = validated_control_successor_r004_context(root)
    return tuple(
        copy.deepcopy(row)
        for row in context.managed_closure_source_successors
    )


def validate_post_review(root: Path = ROOT) -> ReviewContext:
    return validated_control_successor_r008_context(root).current


def transition_review_binding(root: Path = ROOT) -> dict[str, dict[str, Any]]:
    validate_post_review(root)
    return {
        "assignment": _binding(ASSIGNMENT_REL, _raw(root, ASSIGNMENT_REL)),
        "review_result": _binding(RESULT_REL, _raw(root, RESULT_REL)),
        "independent_review": _binding(INDEPENDENT_REL, _raw(root, INDEPENDENT_REL)),
    }


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _write_add_only(root: Path, relative: Path, text: str) -> None:
    require(not relative.is_absolute() and ".." not in relative.parts, "unsafe output path")
    root = root.resolve(strict=True)
    target = root / relative
    current = root
    for part in relative.parent.parts:
        current = current / part
        require(not current.is_symlink(), f"symlink output path rejected: {relative}")
    target.parent.mkdir(parents=True, exist_ok=True)
    require(not os.path.lexists(target), f"review output already exists: {relative}")
    with target.open("x", encoding="utf-8", newline="") as handle:
        handle.write(text)


def write_assignment(root: Path) -> None:
    context = prepare_review_context(root, require_exact_source=True)
    assignment = {
        "schema_version": "1.0",
        "evidence_type": "FP022_SEQ70_71_COMPLETION_REVIEW_ASSIGNMENT",
        "goal_id": GOAL_ID,
        "round_id": ROUND_ID,
        "assigned_at": _now(),
        "assigner": {
            "role": "INTERNAL_REVIEW_ASSIGNER",
            "agent_instance_id": ASSIGNER_ID,
            "canonical_task": ASSIGNER_TASK,
        },
        "executor": {
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "agent_instance_id": EXECUTOR_ID,
            "canonical_task": EXECUTOR_TASK,
        },
        "reviewer": {
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "agent_instance_id": REVIEWER_ID,
            "canonical_task": REVIEWER_TASK,
        },
        "review_scope": _scope(context),
        "review_boundary": copy.deepcopy(BOUNDARY),
    }
    text = json_text(assignment)
    validate_assignment(assignment, text.encode(), context)
    _write_add_only(root, ASSIGNMENT_REL, text)


def write_independent(root: Path) -> None:
    context = prepare_review_context(root)
    assignment, assignment_raw = _document(root, ASSIGNMENT_REL)
    result, result_raw = _document(root, RESULT_REL)
    _write_add_only(
        root,
        INDEPENDENT_REL,
        build_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ),
    )


def write_control_successor_r002_assignment(root: Path) -> None:
    context = prepare_control_successor_r002_context(root)
    _write_add_only(
        root,
        CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL,
        build_control_successor_r002_assignment(
            context,
            assigned_at=_now(),
        ),
    )


def write_control_successor_r002_independent(root: Path) -> None:
    context = prepare_control_successor_r002_context(root)
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_R002_RESULT_REL)
    _write_add_only(
        root,
        CONTROL_SUCCESSOR_R002_INDEPENDENT_REL,
        build_control_successor_r002_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ),
    )


def write_control_successor_r003_assignment(root: Path) -> None:
    context = prepare_control_successor_r003_context(root)
    _write_add_only(
        root,
        CONTROL_SUCCESSOR_R003_ASSIGNMENT_REL,
        build_control_successor_r003_assignment(
            context,
            assigned_at=_now(),
        ),
    )


def write_control_successor_r003_independent(root: Path) -> None:
    context = prepare_control_successor_r003_context(root)
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_R003_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_R003_RESULT_REL)
    _write_add_only(
        root,
        CONTROL_SUCCESSOR_R003_INDEPENDENT_REL,
        build_control_successor_r003_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ),
    )


def write_control_successor_r004_assignment(root: Path) -> None:
    context = prepare_control_successor_r004_context(root)
    _write_add_only(
        root,
        CONTROL_SUCCESSOR_R004_ASSIGNMENT_REL,
        build_control_successor_r004_assignment(
            context,
            assigned_at=_now(),
        ),
    )


def write_control_successor_r004_independent(root: Path) -> None:
    context = prepare_control_successor_r004_context(root)
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_R004_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_R004_RESULT_REL)
    _write_add_only(
        root,
        CONTROL_SUCCESSOR_R004_INDEPENDENT_REL,
        build_control_successor_r004_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ),
    )


def write_control_successor_r005_assignment(root: Path) -> None:
    context = prepare_control_successor_r005_context(root)
    _write_add_only(
        root,
        CONTROL_SUCCESSOR_R005_ASSIGNMENT_REL,
        build_control_successor_r005_assignment(context, assigned_at=_now()),
    )


def write_control_successor_r005_independent(root: Path) -> None:
    context = prepare_control_successor_r005_context(root)
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_R005_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_R005_RESULT_REL)
    _write_add_only(
        root,
        CONTROL_SUCCESSOR_R005_INDEPENDENT_REL,
        build_control_successor_r005_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ),
    )


def write_control_successor_r006_assignment(root: Path) -> None:
    context = prepare_control_successor_r006_context(root)
    _write_add_only(
        root,
        CONTROL_SUCCESSOR_R006_ASSIGNMENT_REL,
        build_control_successor_r006_assignment(context, assigned_at=_now()),
    )


def write_control_successor_r006_independent(root: Path) -> None:
    context = prepare_control_successor_r006_context(root)
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_R006_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_R006_RESULT_REL)
    _write_add_only(
        root,
        CONTROL_SUCCESSOR_R006_INDEPENDENT_REL,
        build_control_successor_r006_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ),
    )


def write_control_successor_r007_assignment(root: Path) -> None:
    context = prepare_control_successor_r007_context(root)
    _write_add_only(
        root,
        CONTROL_SUCCESSOR_R007_ASSIGNMENT_REL,
        build_control_successor_r007_assignment(context, assigned_at=_now()),
    )


def write_control_successor_r007_independent(root: Path) -> None:
    context = prepare_control_successor_r007_context(root)
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_R007_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_R007_RESULT_REL)
    _write_add_only(
        root,
        CONTROL_SUCCESSOR_R007_INDEPENDENT_REL,
        build_control_successor_r007_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ),
    )


def write_control_successor_r008_assignment(root: Path) -> None:
    context = prepare_control_successor_r008_context(root)
    _write_add_only(
        root,
        CONTROL_SUCCESSOR_R008_ASSIGNMENT_REL,
        build_control_successor_r008_assignment(context, assigned_at=_now()),
    )


def write_control_successor_r008_independent(root: Path) -> None:
    context = prepare_control_successor_r008_context(root)
    assignment, assignment_raw = _document(
        root,
        CONTROL_SUCCESSOR_R008_ASSIGNMENT_REL,
    )
    result, result_raw = _document(root, CONTROL_SUCCESSOR_R008_RESULT_REL)
    _write_add_only(
        root,
        CONTROL_SUCCESSOR_R008_INDEPENDENT_REL,
        build_control_successor_r008_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ),
    )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write-assignment", action="store_true")
    mode.add_argument("--check-assignment", action="store_true")
    mode.add_argument("--check-review-result", action="store_true")
    mode.add_argument("--write-independent", action="store_true")
    mode.add_argument("--check-post-review", action="store_true")
    mode.add_argument(
        "--write-control-successor-r002-assignment",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r002-assignment",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r002-review-result",
        action="store_true",
    )
    mode.add_argument(
        "--write-control-successor-r002-independent",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r002-post-review",
        action="store_true",
    )
    mode.add_argument(
        "--write-control-successor-r003-assignment",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r003-assignment",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r003-review-result",
        action="store_true",
    )
    mode.add_argument(
        "--write-control-successor-r003-independent",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r003-post-review",
        action="store_true",
    )
    mode.add_argument(
        "--write-control-successor-r004-assignment",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r004-assignment",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r004-review-result",
        action="store_true",
    )
    mode.add_argument(
        "--write-control-successor-r004-independent",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r004-post-review",
        action="store_true",
    )
    mode.add_argument(
        "--write-control-successor-r005-assignment",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r005-assignment",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r005-review-result",
        action="store_true",
    )
    mode.add_argument(
        "--write-control-successor-r005-independent",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r005-post-review",
        action="store_true",
    )
    mode.add_argument(
        "--write-control-successor-r006-assignment",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r006-assignment",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r006-review-result",
        action="store_true",
    )
    mode.add_argument(
        "--write-control-successor-r006-independent",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r006-post-review",
        action="store_true",
    )
    mode.add_argument(
        "--write-control-successor-r007-assignment",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r007-assignment",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r007-review-result",
        action="store_true",
    )
    mode.add_argument(
        "--write-control-successor-r007-independent",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r007-post-review",
        action="store_true",
    )
    mode.add_argument(
        "--write-control-successor-r008-assignment",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r008-assignment",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r008-review-result",
        action="store_true",
    )
    mode.add_argument(
        "--write-control-successor-r008-independent",
        action="store_true",
    )
    mode.add_argument(
        "--check-control-successor-r008-post-review",
        action="store_true",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    root = args.root.resolve(strict=True)
    try:
        if args.write_assignment:
            write_assignment(root)
        elif args.check_assignment:
            context = prepare_review_context(root)
            assignment, raw = _document(root, ASSIGNMENT_REL)
            validate_assignment(assignment, raw, context)
        elif args.check_review_result:
            context = prepare_review_context(root)
            assignment, assignment_raw = _document(root, ASSIGNMENT_REL)
            result, result_raw = _document(root, RESULT_REL)
            validate_review_result(
                result,
                result_raw,
                assignment,
                assignment_raw,
                context,
            )
        elif args.write_independent:
            write_independent(root)
        elif args.check_post_review:
            validate_post_review(root)
        elif args.write_control_successor_r002_assignment:
            write_control_successor_r002_assignment(root)
        elif args.check_control_successor_r002_assignment:
            context, _bindings = prepare_frozen_control_successor_r002(root)
            assignment, raw = _document(
                root,
                CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL,
            )
            validate_control_successor_r002_assignment(
                assignment,
                raw,
                context,
            )
        elif args.check_control_successor_r002_review_result:
            context, _bindings = prepare_frozen_control_successor_r002(root)
            assignment, assignment_raw = _document(
                root,
                CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL,
            )
            result, result_raw = _document(
                root,
                CONTROL_SUCCESSOR_R002_RESULT_REL,
            )
            validate_control_successor_r002_result(
                result,
                result_raw,
                assignment,
                assignment_raw,
                context,
            )
        elif args.write_control_successor_r002_independent:
            write_control_successor_r002_independent(root)
        elif args.check_control_successor_r002_post_review:
            prepare_frozen_control_successor_r002(root)
        elif args.write_control_successor_r003_assignment:
            write_control_successor_r003_assignment(root)
        elif args.check_control_successor_r003_assignment:
            prepare_frozen_control_successor_r003(root)
        elif args.check_control_successor_r003_review_result:
            prepare_frozen_control_successor_r003(root)
        elif args.write_control_successor_r003_independent:
            write_control_successor_r003_independent(root)
        elif args.check_control_successor_r003_post_review:
            prepare_frozen_control_successor_r003(root)
        elif args.write_control_successor_r004_assignment:
            write_control_successor_r004_assignment(root)
        elif args.check_control_successor_r004_assignment:
            prepare_frozen_control_successor_r004(root)
        elif args.check_control_successor_r004_review_result:
            prepare_frozen_control_successor_r004(root)
        elif args.write_control_successor_r004_independent:
            write_control_successor_r004_independent(root)
        elif args.check_control_successor_r004_post_review:
            prepare_frozen_control_successor_r004(root)
        elif args.write_control_successor_r005_assignment:
            write_control_successor_r005_assignment(root)
        elif args.check_control_successor_r005_assignment:
            context, _bindings = prepare_frozen_control_successor_r005(root)
            assignment, raw = _document(
                root,
                CONTROL_SUCCESSOR_R005_ASSIGNMENT_REL,
            )
            validate_control_successor_r005_assignment(assignment, raw, context)
        elif args.check_control_successor_r005_review_result:
            context, _bindings = prepare_frozen_control_successor_r005(root)
            assignment, assignment_raw = _document(
                root,
                CONTROL_SUCCESSOR_R005_ASSIGNMENT_REL,
            )
            result, result_raw = _document(
                root,
                CONTROL_SUCCESSOR_R005_RESULT_REL,
            )
            validate_control_successor_r005_result(
                result,
                result_raw,
                assignment,
                assignment_raw,
                context,
            )
        elif args.write_control_successor_r005_independent:
            write_control_successor_r005_independent(root)
        elif args.check_control_successor_r005_post_review:
            validated_control_successor_r005_context(root)
        elif args.write_control_successor_r006_assignment:
            write_control_successor_r006_assignment(root)
        elif args.check_control_successor_r006_assignment:
            context = prepare_control_successor_r006_context(root)
            assignment, raw = _document(
                root,
                CONTROL_SUCCESSOR_R006_ASSIGNMENT_REL,
            )
            validate_control_successor_r006_assignment(assignment, raw, context)
        elif args.check_control_successor_r006_review_result:
            context = prepare_control_successor_r006_context(root)
            assignment, assignment_raw = _document(
                root,
                CONTROL_SUCCESSOR_R006_ASSIGNMENT_REL,
            )
            result, result_raw = _document(
                root,
                CONTROL_SUCCESSOR_R006_RESULT_REL,
            )
            validate_control_successor_r006_result(
                result,
                result_raw,
                assignment,
                assignment_raw,
                context,
            )
        elif args.write_control_successor_r006_independent:
            write_control_successor_r006_independent(root)
        elif args.check_control_successor_r006_post_review:
            validated_control_successor_r006_context(root)
        elif args.write_control_successor_r007_assignment:
            write_control_successor_r007_assignment(root)
        elif args.check_control_successor_r007_assignment:
            context = prepare_control_successor_r007_context(root)
            assignment, raw = _document(
                root,
                CONTROL_SUCCESSOR_R007_ASSIGNMENT_REL,
            )
            validate_control_successor_r007_assignment(assignment, raw, context)
        elif args.check_control_successor_r007_review_result:
            context = prepare_control_successor_r007_context(root)
            assignment, assignment_raw = _document(
                root,
                CONTROL_SUCCESSOR_R007_ASSIGNMENT_REL,
            )
            result, result_raw = _document(
                root,
                CONTROL_SUCCESSOR_R007_RESULT_REL,
            )
            validate_control_successor_r007_result(
                result,
                result_raw,
                assignment,
                assignment_raw,
                context,
            )
        elif args.write_control_successor_r007_independent:
            write_control_successor_r007_independent(root)
        elif args.check_control_successor_r007_post_review:
            validated_control_successor_r007_context(root)
        elif args.write_control_successor_r008_assignment:
            write_control_successor_r008_assignment(root)
        elif args.check_control_successor_r008_assignment:
            context = prepare_control_successor_r008_context(root)
            assignment, raw = _document(
                root,
                CONTROL_SUCCESSOR_R008_ASSIGNMENT_REL,
            )
            validate_control_successor_r008_assignment(assignment, raw, context)
        elif args.check_control_successor_r008_review_result:
            context = prepare_control_successor_r008_context(root)
            assignment, assignment_raw = _document(
                root,
                CONTROL_SUCCESSOR_R008_ASSIGNMENT_REL,
            )
            result, result_raw = _document(
                root,
                CONTROL_SUCCESSOR_R008_RESULT_REL,
            )
            validate_control_successor_r008_result(
                result,
                result_raw,
                assignment,
                assignment_raw,
                context,
            )
        elif args.write_control_successor_r008_independent:
            write_control_successor_r008_independent(root)
        elif args.check_control_successor_r008_post_review:
            validate_post_review(root)
        else:
            raise ReviewError("unsupported completion review mode")
    except (OSError, ValueError, TypeError, ReviewError) as exc:
        print(f"FP-022 seq70/71 completion review: FAIL: {exc}")
        return 1
    print("FP-022 seq70/71 completion review: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
