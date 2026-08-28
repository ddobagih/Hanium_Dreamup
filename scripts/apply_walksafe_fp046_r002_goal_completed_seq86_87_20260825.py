#!/usr/bin/env python3
"""Prepare or apply the FP-046 R002 canonical update/completion seq86-87 pair."""

from __future__ import annotations

import argparse
import ast
from collections import Counter
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import importlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp046_goal_completed_seq54_55_20260810 as prior
from scripts import (
    apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812
    as snapshot_authority,
)
from scripts import check_walksafe_project_continuation_v2_4 as continuation

VERIFIER_PYTHON = Path(
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)


CHECKPOINT_REL = Path("docs/control/walksafe-project-continuation-checkpoint.json")
SOURCE_CHECKPOINT_SHA256 = (
    "b66ef10fecc540676aa5c47a2f5b842e12501e98c1bb2308762d09484ba91e79"
)
SOURCE_CHECKPOINT_BYTE_COUNT = 2_460_703
SOURCE_SEQUENCE = 85
SOURCE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-005"
)
SOURCE_EVENT_SHA256 = (
    "84758c3e54d38bde48fce3250e1af4a32376698801a8d3caf6be39d016d75bd3"
)
UPDATE_SEQUENCE = 86
COMPLETION_SEQUENCE = 87
UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP046-R002-20260825-001"
)
COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP046-R002-20260825-001"
)
MANIFEST_SHA256 = "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"

GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R002"
GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-fp046-consent-withdrawal-deletion-r002.md"
)
GOAL_SHA256 = "4627c19b421f626323778fcdfd01cc2edbc36644c48c7d65c2b4847e698ac429"
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
PARENT_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-03-account-admin-security.md"
)
PARENT_GOAL_SHA256 = "7d2dfb7fe89bd30fc1f6113acb0b533a9fd91a6106cc2ab4798091763beae832"
EPIC12_GOAL_ID = "WS-GOAL-EPIC-12"
EPIC04_GOAL_ID = "WS-GOAL-EPIC-04"
FP048_R001_GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R001"
FP048_R001_COMPLETION_EVENT_SHA256 = (
    "ad4addfbb44baa7a33e9e6640f591f20bbce22397fb3a120e26b8844a3f8b665"
)
SOURCE_START_GATE_RECEIPT_SHA256 = (
    "3bd3ba0bb29d85008f6f5697d807bce2731235362167e69e295e42dd20bff6f1"
)
NEXT_GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R002"
NEXT_WORK_ITEM_ID = "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT"
NEXT_ACTION = (
    "FP-048 R002에서 7종 state rotation을 all-or-nothing으로 재개한다."
)
NEXT_GAP_ID = "GAP-057"

COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
COMPLETION_DOCUMENT_ID = (
    "WS-FP046-R002-CONSENT-WITHDRAWAL-DELETION-WORK-ITEM-COMPLETION-"
    "20260825-R005"
)
RESULT_DIR = Path("docs/control/execution/goal-results") / GOAL_ID
IMPLEMENTATION_REL = RESULT_DIR / "implementation-record.json"
VERIFICATION_REL = RESULT_DIR / "verification-result.json"
RATE_LIMIT_LOG_REL = RESULT_DIR / "verification-rate-limit.log"
INGRESS_LOG_REL = RESULT_DIR / "verification-gateway-ingress.log"
VERIFICATION_RECEIPT_REL = RESULT_DIR / "verification-receipt.json"
SUCCESSOR_REL = RESULT_DIR / "successor-trace.json"
R001_REVIEW_SUBJECT_REL = RESULT_DIR / "review-subject.json"
R001_INDEPENDENT_REVIEW_REL = RESULT_DIR / "independent-review.json"
R002_GOAL_REVIEW_DIR = RESULT_DIR / "review-rounds/R002"
R002_REVIEW_SUBJECT_REL = R002_GOAL_REVIEW_DIR / "review-subject.json"
R002_INDEPENDENT_REVIEW_REL = R002_GOAL_REVIEW_DIR / "independent-review.json"
R003_GOAL_REVIEW_DIR = RESULT_DIR / "review-rounds/R003"
R003_REVIEW_SUBJECT_REL = R003_GOAL_REVIEW_DIR / "review-subject.json"
R003_INDEPENDENT_REVIEW_REL = R003_GOAL_REVIEW_DIR / "independent-review.json"
R004_GOAL_REVIEW_DIR = RESULT_DIR / "review-rounds/R004"
R004_REVIEW_SUBJECT_REL = R004_GOAL_REVIEW_DIR / "review-subject.json"
R004_INDEPENDENT_REVIEW_REL = R004_GOAL_REVIEW_DIR / "independent-review.json"
GOAL_REVIEW_DIR = RESULT_DIR / "review-rounds/R005"
REVIEW_SUBJECT_REL = GOAL_REVIEW_DIR / "review-subject.json"
INDEPENDENT_REVIEW_REL = GOAL_REVIEW_DIR / "independent-review.json"
R002_COMPLETION_REL = RESULT_DIR / "completion-receipt.json"
R003_COMPLETION_REL = RESULT_DIR / "completion-receipt-r003.json"
R004_COMPLETION_REL = RESULT_DIR / "completion-receipt-r004.json"
COMPLETION_REL = RESULT_DIR / "completion-receipt-r005.json"
R001_TRANSITION_REVIEW_DIR = Path(
    "docs/control/execution/workstream-transitions/seq86-87/review-rounds/R001"
)
R001_TRANSITION_ASSIGNMENT_REL = R001_TRANSITION_REVIEW_DIR / "assignment.json"
R002_TRANSITION_REVIEW_DIR = Path(
    "docs/control/execution/workstream-transitions/seq86-87/review-rounds/R002"
)
R002_TRANSITION_ASSIGNMENT_REL = R002_TRANSITION_REVIEW_DIR / "assignment.json"
R002_TRANSITION_RESULT_REL = R002_TRANSITION_REVIEW_DIR / "review-result.json"
R002_TRANSITION_INDEPENDENT_REL = (
    R002_TRANSITION_REVIEW_DIR / "independent-review.json"
)
R003_TRANSITION_REVIEW_DIR = Path(
    "docs/control/execution/workstream-transitions/seq86-87/review-rounds/R003"
)
R003_TRANSITION_ASSIGNMENT_REL = R003_TRANSITION_REVIEW_DIR / "assignment.json"
R003_TRANSITION_RESULT_REL = R003_TRANSITION_REVIEW_DIR / "review-result.json"
R003_TRANSITION_INDEPENDENT_REL = (
    R003_TRANSITION_REVIEW_DIR / "independent-review.json"
)
R004_TRANSITION_REVIEW_DIR = Path(
    "docs/control/execution/workstream-transitions/seq86-87/review-rounds/R004"
)
R004_TRANSITION_ASSIGNMENT_REL = R004_TRANSITION_REVIEW_DIR / "assignment.json"
R004_TRANSITION_RESULT_REL = R004_TRANSITION_REVIEW_DIR / "review-result.json"
R004_TRANSITION_INDEPENDENT_REL = (
    R004_TRANSITION_REVIEW_DIR / "independent-review.json"
)
TRANSITION_REVIEW_DIR = Path(
    "docs/control/execution/workstream-transitions/seq86-87/review-rounds/R005"
)
TRANSITION_ASSIGNMENT_REL = TRANSITION_REVIEW_DIR / "assignment.json"
TRANSITION_RESULT_REL = TRANSITION_REVIEW_DIR / "review-result.json"
TRANSITION_INDEPENDENT_REL = TRANSITION_REVIEW_DIR / "independent-review.json"
GAP_JSON_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260825-r030.json"
)
GAP_MD_REL = GAP_JSON_REL.with_suffix(".md")
BACKLOG_JSON_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260825-r030.json"
)
BACKLOG_MD_REL = BACKLOG_JSON_REL.with_suffix(".md")
R029_GAP_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260815-r029.json"
)
R029_BACKLOG_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260815-r029.json"
)
SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp046_r002_goal_completed_seq86_87_20260825.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp046_r002_goal_completed_seq86_87_20260825.py"
)
CONTINUATION_CHECKER_REL = Path("scripts/check_walksafe_project_continuation_v2_4.py")
GOAL_CHECKER_REL = Path("scripts/check_walksafe_goal_graph_v2_4.py")
SEQ83_RECONSTRUCTION_BUILDER_REL = Path(
    "scripts/build_walksafe_fp046_r002_seq78_79_recovery_review_20260824.py"
)
SEQ83_RECONSTRUCTION_TEST_REL = Path(
    "tests/test_build_walksafe_fp046_r002_seq78_79_recovery_review_20260824.py"
)
CONTROL_AUTHORITY_PATHS = (
    SCRIPT_REL,
    TEST_REL,
    CONTINUATION_CHECKER_REL,
    GOAL_CHECKER_REL,
    SEQ83_RECONSTRUCTION_BUILDER_REL,
    SEQ83_RECONSTRUCTION_TEST_REL,
)
COMPLETION_VALIDATOR_NAME = "validate_fp046_r002_completion_seq86_87"
IMPLEMENTATION_SCOPE_PATHS = (
    Path("backend/alembic/versions/202608250001_actor_rate_limit_privacy_group.py"),
    Path("backend/tests/test_actor_rate_limit_store.py"),
    Path("deploy/nginx/walksafe-android-gateway.conf.example"),
    Path("configs/walksafe_product_boundary_20260722.json"),
    Path("tests/test_walksafe_android_gateway_ingress_current.py"),
    Path("tests/test_walksafe_android_product_boundary.py"),
)
CONCURRENT_NONCREDIT_PATHS = (
    Path("backend/app/api/health.py"),
    Path("backend/tests/test_fp046_postgres_integration.py"),
    Path("backend/alembic/versions/202608250002_account_deletion_worker_role.py"),
    Path("scripts/account_deletion_worker.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    Path("scripts/generate_repository_catalogs.py"),
    Path("docs/catalogs/repository-paths.json"),
    Path("docs/catalogs/scripts.json"),
    Path("docs/catalogs/tests.json"),
)
R001_HISTORICAL_REVIEW_PATHS = (
    R001_REVIEW_SUBJECT_REL,
    R001_INDEPENDENT_REVIEW_REL,
    R001_TRANSITION_ASSIGNMENT_REL,
)
R001_HISTORICAL_REVIEW_PINS = {
    R001_REVIEW_SUBJECT_REL: (
        "2acc6d4b965f98dc776daaaf7772e72180e592e063c4202f3fcf628ff1ac1496",
        2_001,
    ),
    R001_INDEPENDENT_REVIEW_REL: (
        "abe2174cd7032e7bdc098170e217c13c34718b2c872ea4771bf11935371ce0a7",
        1_203,
    ),
    R001_TRANSITION_ASSIGNMENT_REL: (
        "6dcc86ef38a262fd5103217c06aff896f8dcea52360707cf9ceb18c8d36f8933",
        859,
    ),
}
R002_FAILED_REVIEW_PATHS = (
    R002_REVIEW_SUBJECT_REL,
    R002_INDEPENDENT_REVIEW_REL,
    R002_TRANSITION_ASSIGNMENT_REL,
    R002_TRANSITION_RESULT_REL,
    R002_TRANSITION_INDEPENDENT_REL,
)
R002_FAILED_REVIEW_PINS = {
    R002_REVIEW_SUBJECT_REL: (
        "7f566791361cb60a8cb75312130e21c423a79ed0e306b724ad11b36ad838adfc",
        27_349,
    ),
    R002_INDEPENDENT_REVIEW_REL: (
        "823f605789ebd17e19a4e079308d3766516e8a842f03000c347bbf9429f48a9b",
        1_231,
    ),
    R002_TRANSITION_ASSIGNMENT_REL: (
        "6f28eaf8cd7b910534f5adb8a943bb8f4a211394b87491ca4256996814ba7e9c",
        1_394,
    ),
    R002_TRANSITION_RESULT_REL: (
        "ab2281789a989391577eeabd2a0e9d6c089e3d7433ed253bcc5757f0732c6b67",
        1_074,
    ),
    R002_TRANSITION_INDEPENDENT_REL: (
        "0f3210c5f2261d840453514cfc0b6fbfc2488248f18eef57142831af734fee71",
        1_107,
    ),
}
SHARED_PUBLICATION_OUTPUT_PATHS = (
    GAP_JSON_REL,
    GAP_MD_REL,
    BACKLOG_JSON_REL,
    BACKLOG_MD_REL,
    IMPLEMENTATION_REL,
    VERIFICATION_REL,
    RATE_LIMIT_LOG_REL,
    INGRESS_LOG_REL,
    VERIFICATION_RECEIPT_REL,
    SUCCESSOR_REL,
)
R002_FAILED_ORPHAN_PATHS = (*SHARED_PUBLICATION_OUTPUT_PATHS, R002_COMPLETION_REL)
R002_FAILED_ORPHAN_PINS = {
    GAP_JSON_REL: ("ae37dc814f7bbc05861f329f4c0a05e01e2362a922ac6f232a3c86846c48df66", 532_206),
    GAP_MD_REL: ("b284f00e66e0cf7ee85b0f822d1f179f3323331fd3c40514917dcd2896c26f97", 475),
    BACKLOG_JSON_REL: ("e60301254dcdcdc18216e2640755f1726c5127d1e37bf520b167db34dd02ac83", 65_010),
    BACKLOG_MD_REL: ("c91552a1e20e60171f0eeaf393874688b1f95a8ac78f358fe92f54c3bf3d7d0c", 481),
    IMPLEMENTATION_REL: ("f2153bf35b11ea197d073b477634cb06172d15ed774a59020e1c40b43d77b8bc", 4_776),
    VERIFICATION_REL: ("567b781005b948008ebe24dec38b3a021d68880928789486eefe2b30c2480eee", 1_368),
    RATE_LIMIT_LOG_REL: ("8989a153a4f265bdf8bf8abfcbf7ce96cf91c69ee66d543244ebf0fc2c9d041d", 98),
    INGRESS_LOG_REL: ("9310d484212a1fe2c33b4b8accdf50a2639f09ba039354f8448f8aa8d173e1ff", 98),
    VERIFICATION_RECEIPT_REL: ("77e90547518971c82ce68bdd3a11bf59eaf0f63179d9b0278343c5a8a350a0d2", 1_800),
    SUCCESSOR_REL: ("2c760dc4df2b5cce930b5d6c6efa11322d06ef8340261725130b875e9caaa90a", 1_271),
    R002_COMPLETION_REL: ("cb122c9605c3c51bc4c27beb6136c11d523077045f65d5b088b814b6053746a1", 4_325),
}
R003_FAILED_REVIEW_PATHS = (
    R003_REVIEW_SUBJECT_REL,
    R003_INDEPENDENT_REVIEW_REL,
    R003_TRANSITION_ASSIGNMENT_REL,
    R003_TRANSITION_RESULT_REL,
    R003_TRANSITION_INDEPENDENT_REL,
)
R003_FAILED_REVIEW_PINS = {
    R003_REVIEW_SUBJECT_REL: (
        "c5542d27216753c7d6e269c00cdaa23b33e003917e4ed83948b1316f37f89c3c",
        35_199,
    ),
    R003_INDEPENDENT_REVIEW_REL: (
        "04a2b91995c940ec7553128d69d624bc22c0db00acb5bebddfb0d5bedea249ab",
        1_263,
    ),
    R003_TRANSITION_ASSIGNMENT_REL: (
        "c9ba35346166147e7bf3ab57ed782341d4adcea69f311e91c2427687009ecca0",
        1_421,
    ),
    R003_TRANSITION_RESULT_REL: (
        "3670ae15dfced4bc165dc93c20058ced820413e681a357fdeb88a356471e194c",
        1_092,
    ),
    R003_TRANSITION_INDEPENDENT_REL: (
        "94dca14121c2e9035895f7e2794e2db5753122491d10d0692e2c0573777e1040",
        1_130,
    ),
}
R003_FAILED_COMPLETION_PIN = (
    "ab45908c02c0620f8082639af3cfb6701a28d700273749082dbd72f4d046c7fd",
    5_666,
)
R004_FAILED_PREPARATION_PATHS = (
    R004_REVIEW_SUBJECT_REL,
    R004_TRANSITION_ASSIGNMENT_REL,
)
R004_FAILED_PREPARATION_PINS = {
    R004_REVIEW_SUBJECT_REL: (
        "d323d5edb7a141dc0e626ae6e72a6f81a60ee1ef7338e34f63b81020f0ceeeef",
        40_429,
    ),
    R004_TRANSITION_ASSIGNMENT_REL: (
        "b6c1ceff5fbcc8389da0b608fff2730367aafad98a91ab6e801386f1e6684359",
        1_442,
    ),
}
R004_REJECTED_ABSENT_PATHS = (
    R004_INDEPENDENT_REVIEW_REL,
    R004_TRANSITION_RESULT_REL,
    R004_TRANSITION_INDEPENDENT_REL,
    R004_COMPLETION_REL,
)
R004_BOUND_SEQ83_TEST_BINDING = {
    "path": SEQ83_RECONSTRUCTION_TEST_REL.as_posix(),
    "sha256": "412abac3ea8b1203d66127067e215aad88db6d9fd77c172451731ed6957626b4",
    "byte_length": 22_098,
}
R005_SEQ83_TEST_BINDING = {
    "path": SEQ83_RECONSTRUCTION_TEST_REL.as_posix(),
    "sha256": "a6e86d3959e0fe0b8620d70c780f16bfae017e5b506d787ff43da3667d0928c5",
    "byte_length": 22_101,
    "mode": "0664",
}
EVIDENCE_PATHS = (
    *SHARED_PUBLICATION_OUTPUT_PATHS,
    REVIEW_SUBJECT_REL,
    INDEPENDENT_REVIEW_REL,
    COMPLETION_REL,
    TRANSITION_ASSIGNMENT_REL,
    TRANSITION_RESULT_REL,
    TRANSITION_INDEPENDENT_REL,
)
PREPARED_REVIEW_PATHS = (REVIEW_SUBJECT_REL, TRANSITION_ASSIGNMENT_REL)
DISTINCT_REVIEW_INPUT_PATHS = (
    INDEPENDENT_REVIEW_REL,
    TRANSITION_RESULT_REL,
    TRANSITION_INDEPENDENT_REL,
)
PUBLICATION_OUTPUT_PATHS = tuple(
    path
    for path in EVIDENCE_PATHS
    if path not in PREPARED_REVIEW_PATHS + DISTINCT_REVIEW_INPUT_PATHS
)
ADDED_MANAGED_PATHS = (
    *EVIDENCE_PATHS,
    *R001_HISTORICAL_REVIEW_PATHS,
    *R002_FAILED_REVIEW_PATHS,
    R002_COMPLETION_REL,
    *R003_FAILED_REVIEW_PATHS,
    R003_COMPLETION_REL,
    *R004_FAILED_PREPARATION_PATHS,
    SCRIPT_REL,
    TEST_REL,
)
SOURCE_MANAGED_PATH_COUNT = 1_020
CHANGED_ROLES = ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP", COMPLETION_ROLE]
PRODUCED_ROLES = ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"]

ZERO_CREDIT_BOUNDARY = {
    "formal_test_status": "NOT_RUN",
    "actual_device_status": "NOT_RUN",
    "external_review_status": "NOT_RUN",
    "production_deployment_status": "NOT_RUN",
    "release_status": "NOT_ELIGIBLE",
    "formal_test_credit_delta": 0,
    "device_credit_delta": 0,
    "external_credit_delta": 0,
    "deployment_credit_delta": 0,
    "release_credit_delta": 0,
}

FAILED_R002_WRITE_STDOUT = (
    "FP-046 R002 GOAL_COMPLETED seq86-87: FAIL: projected continuation "
    "checker failed: FP046/NPC R002 seq72 approval-neutral reviewed core "
    "differs: post-seq76 no-op status boundary differs: 86 | seq85 PASS "
    "receipt authority differs\n"
).encode("utf-8")
FAILED_R002_WRITE_EXIT_CODE = 1
FAILED_R002_WRITE_WALL_TIME_SECONDS = "21.770233211"
FAILED_R002_FULL_GOAL_BASELINE_COUNT = 97
FAILED_R002_FULL_GOAL_BASELINE_SHA256 = (
    "39fa1fca89c2eab86fb00233aef96d5a7771ab71fe771579d77bdeecae690802"
)
FAILED_R003_WRITE_STDOUT = (
    "FP-046 R002 GOAL_COMPLETED seq86-87: FAIL: projected full Goal checker "
    "introduced new non-gate errors: FP046/NPC R002 reviewed authority differs: "
    "reconstructed seq83 review source checkpoint differs | FP046 R002 "
    "seq77/78/79/80/81/82/83/84/85 authority differs: reconstructed seq83 "
    "review source checkpoint differs\n"
).encode("utf-8")
FAILED_R003_WRITE_EXIT_CODE = 1
FAILED_R003_WRITE_WALL_TIME_SECONDS = "30.00092645"
FAILED_R004_PREPARATION_TEST_NODE = (
    "tests/test_build_walksafe_fp046_r002_seq78_79_recovery_review_20260824.py::"
    "test_review_source_loader_accepts_seq83_descendant_and_rejects_seq82_tamper"
)
FAILED_R004_ACTUAL_ERROR = "reconstructed seq83 review source checkpoint differs"
FAILED_R004_DIAGNOSTIC = "BOUND_TEST_EXPECTATION_MISMATCH"
FAILED_R004_TEST_EXIT_CODE = 1
FAILED_R004_TEST_COUNT = 1

UPDATE_FIELDS = {
    "sequence", "event_id", "event_type", "occurred_on", "occurred_at",
    "previous_focus_goal_id", "previous_focus_content_sha256", "focus_goal_id",
    "focus_goal_content_sha256", "from_status", "to_status",
    "static_plan_manifest_sha256", "status_changes", "runtime_after",
    "blockers_after", "blocker_resolution_ids_after", "source_checkpoint_version",
    "evidence_refs", "previous_event_sha256", "produced_by_goal_id",
    "produced_binding_roles", "producer_completion_receipt_binding",
    "changed_binding_roles", "changed_subject_ids_by_role",
    "producer_output_subject_ids_by_role", "impact_closure_goal_ids",
    "impact_disposition_by_goal", "reopened_completion_event_sha256_by_goal",
    "canonical_binding_snapshot_after", "event_sha256",
    "transition_control_review_binding",
}
COMPLETION_FIELDS = {
    "sequence", "event_id", "event_type", "occurred_on", "occurred_at",
    "previous_focus_goal_id", "previous_focus_content_sha256", "focus_goal_id",
    "focus_goal_content_sha256", "subject_goal_id", "from_status", "to_status",
    "static_plan_manifest_sha256", "status_changes", "runtime_after",
    "blockers_after", "blocker_resolution_ids_after", "source_checkpoint_version",
    "evidence_refs", "previous_event_sha256", "canonical_update_event_sha256",
    "completion_receipt_binding", "completion_evidence_bindings",
    "completion_evidence_by_goal_after", "canonical_binding_snapshot_after",
    "event_sha256",
}


class CompletionApplyError(RuntimeError):
    pass


@dataclass(frozen=True)
class CompletionEvidence:
    raw_by_path: Mapping[Path, bytes]
    bindings_by_role: Mapping[str, Mapping[str, Any]]
    update_occurred_at: str
    completion_occurred_at: str
    final_sha256_by_path: Mapping[Path, str]
    transition_review_binding: Mapping[str, Mapping[str, Any]]


@dataclass(frozen=True)
class PreparedProjection:
    root: Path
    source_bytes: bytes
    source: Mapping[str, Any]
    projected: Mapping[str, Any]
    projected_bytes: bytes
    evidence: CompletionEvidence
    update_event: Mapping[str, Any]
    completion_event: Mapping[str, Any]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CompletionApplyError(message)


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def json_bytes(value: Mapping[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")


def checkpoint_bytes(value: Mapping[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")


def _ordered_json_fragment(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        indent=2,
        sort_keys=False,
    ).encode("utf-8")


def strict_json(raw: bytes, label: str) -> dict[str, Any]:
    def reject(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            require(key not in result, f"duplicate JSON key: {label}: {key}")
            result[key] = value
        return result

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=reject)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise CompletionApplyError(f"invalid JSON: {label}") from exc
    require(type(value) is dict, f"JSON root differs: {label}")
    return value


def safe_regular_bytes(root: Path, relative: Path) -> bytes:
    require(not relative.is_absolute() and ".." not in relative.parts, "unsafe path")
    target = root / relative
    current = root
    for part in relative.parts:
        current /= part
        require(not current.is_symlink(), f"symlink path rejected: {relative}")
    require(target.is_file(), f"file is missing: {relative}")
    return target.read_bytes()


def _require_exact_or_absent_0600(
    root: Path,
    relative: Path,
    expected: bytes,
    *,
    label: str,
) -> bool:
    require(not relative.is_absolute() and ".." not in relative.parts, "unsafe path")
    target = root / relative
    current = root
    for part in relative.parts:
        current /= part
        require(not current.is_symlink(), f"{label} symlink path differs: {relative}")
    if not target.exists():
        require(not target.is_symlink(), f"{label} path type differs: {relative}")
        return False
    info = target.lstat()
    require(
        stat.S_ISREG(info.st_mode)
        and info.st_uid == os.geteuid()
        and info.st_nlink == 1
        and stat.S_IMODE(info.st_mode) == 0o600,
        f"{label} mode or regular-file authority differs: {relative}",
    )
    require(
        target.read_bytes() == expected,
        f"{label} bytes differ: {relative}",
    )
    return True


def _require_pinned_0600(
    root: Path,
    relative: Path,
    pin: tuple[str, int],
    *,
    label: str,
) -> bytes:
    raw = safe_regular_bytes(root, relative)
    _require_exact_or_absent_0600(root, relative, raw, label=label)
    require(
        (sha256_bytes(raw), len(raw)) == pin,
        f"{label} pin differs: {relative}",
    )
    return raw


def require_exact_source(raw: bytes, source: Mapping[str, Any]) -> None:
    require(len(raw) == SOURCE_CHECKPOINT_BYTE_COUNT, "source byte count differs")
    require(sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256, "source SHA-256 differs")
    require(
        checkpoint_bytes(source) == raw,
        "source checkpoint insertion-order round trip differs",
    )
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(isinstance(history, list) and len(history) == SOURCE_SEQUENCE, "source is not seq85")
    tail = history[-1]
    require(
        isinstance(tail, dict)
        and tail.get("sequence") == SOURCE_SEQUENCE
        and tail.get("event_id") == SOURCE_EVENT_ID
        and tail.get("event_type") == "GOAL_STARTED"
        and tail.get("subject_goal_id") == GOAL_ID
        and tail.get("event_sha256") == SOURCE_EVENT_SHA256
        and tail.get("implementation_start_gate_binding", {}).get("file_sha256")
        == SOURCE_START_GATE_RECEIPT_SHA256
        and continuation.event_sha256(tail) == SOURCE_EVENT_SHA256,
        "source seq85 seal differs",
    )
    snapshot = source.get("working_tree_snapshot")
    require(
        state.get("status_by_goal", {}).get(GOAL_ID) == "IN_PROGRESS"
        and state.get("focus_goal_id") == GOAL_ID
        and state.get("focus_goal_path") == GOAL_PATH
        and state.get("focus_work_item_id") == GOAL_ID
        and state.get("focus_source") == "IMPLEMENTATION_GAP"
        and state.get("ready_frontier_goal_ids")
        == [GOAL_ID, PARENT_GOAL_ID, EPIC04_GOAL_ID, EPIC12_GOAL_ID]
        and state.get("pending_producer_completion_goal_id") in {None, ""}
        and isinstance(snapshot, dict)
        and snapshot.get("managed_changed_path_count") == SOURCE_MANAGED_PATH_COUNT
        and len(snapshot.get("managed_changed_paths", ())) == SOURCE_MANAGED_PATH_COUNT,
        "source FP046 R002 runtime differs",
    )
    roles = continuation.canonical_binding_snapshot(source)
    require(
        source.get("schema_version") == "1.25.0"
        and len(roles) == 43
        and state.get("status_by_goal", {}).get(
            "WS-GOAL-EPIC-03-FP-046-R001"
        )
        == "SUPERSEDED"
        and "WS-GOAL-EPIC-03-FP-046-R001"
        in state.get("archived_completion_evidence_by_goal", {}),
        "source schema, role count, or R001 archive differs",
    )
    require(COMPLETION_ROLE not in roles, "source already contains completion role")
    require(roles.get("IMPLEMENTATION_GAP", {}).get("path") == R029_GAP_REL.as_posix(), "source R029 gap differs")
    require(roles.get("IMPLEMENTATION_BACKLOG", {}).get("path") == R029_BACKLOG_REL.as_posix(), "source R029 backlog differs")


def _binding(
    relative: Path,
    raw: bytes,
    *,
    role: str,
    document_id: str,
    identity_json_path: str,
) -> dict[str, Any]:
    del identity_json_path
    return {
        "role": role,
        "document_id": document_id,
        "path": relative.as_posix(),
        "file_sha256": sha256_bytes(raw),
    }


def _seal(value: dict[str, Any], field: str) -> None:
    value.pop(field, None)
    value[field] = continuation.canonical_json_sha256(value)


def _implementation_rows(root: Path) -> list[dict[str, Any]]:
    rows = []
    for relative in IMPLEMENTATION_SCOPE_PATHS:
        raw = safe_regular_bytes(root, relative)
        rows.append(
            {
                "path": relative.as_posix(),
                "byte_count": len(raw),
                "sha256": sha256_bytes(raw),
            }
        )
    return rows


def _build_r030(
    root: Path,
    update_at: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    gap = strict_json(safe_regular_bytes(root, R029_GAP_REL), "R029 gap")
    backlog = strict_json(
        safe_regular_bytes(root, R029_BACKLOG_REL), "R029 backlog"
    )
    gap_meta = gap["metadata"]
    gap_meta.update(
        {
            "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260825-030",
            "version": "0.30.0",
            "prepared_at": update_at,
            "predecessor_report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260815-029",
        }
    )
    gap["source_predecessor"] = {
        "path": R029_GAP_REL.as_posix(),
        "file_sha256": sha256_bytes(safe_regular_bytes(root, R029_GAP_REL)),
        "preserved_unchanged": True,
    }
    assessments = gap.get("assessments")
    require(isinstance(assessments, list), "R029 assessments differ")
    targets = [row for row in assessments if row.get("source_policy_id") == "FP-046"]
    require(len(targets) == 1 and targets[0].get("gap_id") == "GAP-055", "R029 FP046 assessment differs")
    target = targets[0]
    target["current_implementation_in_plain_language"] = (
        "privacy rate_group CHECK와 Android Gateway account-deletion ingress 회귀를 "
        "저장소 내부에서 수정하고 집중 회귀로 검증했다."
    )
    target["rationale"] = (
        "두 현재 회귀의 저장소 내부 수정과 자동 검증은 PASS이지만 "
        "정식·실기기·외부·운영·배포·출시 증거는 NOT_RUN이므로 PARTIAL을 유지한다."
    )
    target["regression_reopen"] = {
        "finding_ids": [
            "FP046-RATE-GROUP-PRIVACY-CHECK-OMISSION",
            "FP046-NGINX-ACCOUNT-DELETION-INGRESS-OMISSION",
        ],
        "goal_id": GOAL_ID,
        "start_event_id": SOURCE_EVENT_ID,
        "start_event_sequence": SOURCE_SEQUENCE,
        "status": "CLOSED_INTERNAL_ONLY",
        "reopen_required": False,
        "internal_implementation_status": "PASS",
        "internal_verification_status": "PASS",
        "next_action": {
            "action": NEXT_ACTION,
            "goal_successor_id": NEXT_GOAL_ID,
            "work_item_id": NEXT_WORK_ITEM_ID,
            "status": "PLANNED",
        },
        "completion_boundary": copy.deepcopy(ZERO_CREDIT_BOUNDARY),
    }
    target["fp046_reassessment"] = {
        "goal_id": GOAL_ID,
        "internal_implementation_status": "PASS",
        "internal_verification_status": "PASS",
        **copy.deepcopy(ZERO_CREDIT_BOUNDARY),
        "scope": "REPOSITORY_INTERNAL_FP046_R002_REGRESSION_REPAIR_ONLY",
    }
    target.pop("assessment_sha256", None)
    target["assessment_sha256"] = continuation.canonical_json_sha256(target)
    fp048_targets = [
        row for row in assessments if row.get("source_policy_id") == "FP-048"
    ]
    require(
        len(fp048_targets) == 1 and fp048_targets[0].get("gap_id") == NEXT_GAP_ID,
        "R029 FP048 assessment differs",
    )
    fp048 = fp048_targets[0]
    fp048["rationale"] = (
        "FP-046 R002 완료 재평가에서 server-capacity를 포함한 7종 암호화 "
        "state rotation의 all-or-nothing 보장이 완결 증명되지 않아 FP-048 R001 완료를 "
        "재개한다. 정식·실기기·외부·배포·출시 credit은 계속 0이다."
    )
    fp048["regression_reopen"] = {
        "finding_ids": ["FP048-SEVEN-STATE-ROTATION-ATOMICITY-GAP"],
        "reopened_goal_id": FP048_R001_GOAL_ID,
        "reopened_completion_event_sha256": FP048_R001_COMPLETION_EVENT_SHA256,
        "goal_successor_id": NEXT_GOAL_ID,
        "status": "REOPEN_REQUIRED",
        "next_action": NEXT_ACTION,
        "completion_boundary": copy.deepcopy(ZERO_CREDIT_BOUNDARY),
    }
    fp048.pop("assessment_sha256", None)
    fp048["assessment_sha256"] = continuation.canonical_json_sha256(fp048)
    gap["fp046_verification_boundary"] = copy.deepcopy(ZERO_CREDIT_BOUNDARY)
    gap["reassessment_scope"] = {
        "goal_id": GOAL_ID,
        "finding_ids": target["regression_reopen"]["finding_ids"],
        "implementation_files": _implementation_rows(root),
        "result": "PASS_INTERNAL_ONLY",
        "successor_goal_id": NEXT_GOAL_ID,
    }
    _seal(gap, "report_content_sha256")

    backlog_meta = backlog["metadata"]
    backlog_meta.update(
        {
            "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260825-030",
            "version": "0.30.0",
            "prepared_at": update_at,
            "predecessor_backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260815-029",
        }
    )
    backlog["source_predecessor"] = {
        "path": R029_BACKLOG_REL.as_posix(),
        "file_sha256": sha256_bytes(safe_regular_bytes(root, R029_BACKLOG_REL)),
        "preserved_unchanged": True,
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    backlog["next_single_action"] = {
        "action": NEXT_ACTION,
        "epic_id": "EPIC-03",
        "gap_id": NEXT_GAP_ID,
        "priority_rank": 23,
        "source_policy_id": "FP-048",
        "status": "PLANNED_NEXT",
        "goal_id": NEXT_GOAL_ID,
        "work_item_id": NEXT_WORK_ITEM_ID,
    }
    actions = backlog.get("next_action_sequence")
    require(isinstance(actions, list), "R029 action sequence differs")
    fp046 = [row for row in actions if row.get("source_policy_id") == "FP-046"]
    require(len(fp046) == 1, "R029 FP046 action differs")
    fp046[0]["status"] = "INTERNAL_REMEDIATION_COMPLETE"
    fp046[0]["action"] = (
        "GAP-055 PARTIAL을 유지하며 두 현재 회귀의 저장소 내부 수정과 "
        "자동 검증을 FP-046 R002로 완료했다."
    )
    fp048_actions = [
        row for row in actions if row.get("source_policy_id") == "FP-048"
    ]
    require(len(fp048_actions) == 1, "R029 FP048 action differs")
    fp048_actions[0]["status"] = "REOPEN_REQUIRED"
    fp048_actions[0]["action"] = NEXT_ACTION
    backlog["fp046_verification_boundary"] = copy.deepcopy(ZERO_CREDIT_BOUNDARY)
    _seal(backlog, "backlog_content_sha256")
    return gap, backlog


def _markdown_pair(gap: Mapping[str, Any], backlog: Mapping[str, Any]) -> tuple[bytes, bytes]:
    gap_text = f"""# WalkSafe Implementation Gap Analysis R030

- report: `{gap['metadata']['report_id']}`
- predecessor: `{gap['metadata']['predecessor_report_id']}`
- FP-046 R002: repository-internal regression repair `PASS`
- successor: `{NEXT_GOAL_ID}` / `{NEXT_WORK_ITEM_ID}`
- formal, device, external, deployment credit: `0`
- release: `NOT_ELIGIBLE`
- seal: `{gap['report_content_sha256']}`
"""
    backlog_text = f"""# WalkSafe Implementation Remediation Backlog R030

- backlog: `{backlog['metadata']['backlog_id']}`
- predecessor: `{backlog['metadata']['predecessor_backlog_id']}`
- next single action: `{NEXT_GOAL_ID}` / `{NEXT_WORK_ITEM_ID}`
- action: {NEXT_ACTION}
- release: `NOT_ELIGIBLE`
- seal: `{backlog['backlog_content_sha256']}`
"""
    return gap_text.encode("utf-8"), backlog_text.encode("utf-8")


def _artifact_binding(relative: Path, raw: bytes, document_id: str) -> dict[str, Any]:
    return {
        "document_id": document_id,
        "path": relative.as_posix(),
        "file_sha256": sha256_bytes(raw),
        "byte_count": len(raw),
        "mutable": False,
    }


def _run_focused_command(root: Path, node_id: str) -> tuple[list[str], bytes, int]:
    require(VERIFIER_PYTHON.is_file(), "focused verification Python is missing")
    argv = [
        str(VERIFIER_PYTHON),
        "-B",
        "-m",
        "pytest",
        "-p",
        "no:cacheprovider",
        "-q",
        node_id,
    ]
    environment = dict(os.environ)
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    environment["PYTHONPATH"] = "."
    completed = subprocess.run(
        argv,
        cwd=root,
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
        timeout=300,
    )
    return argv, completed.stdout, completed.returncode


def _run_fresh_recovery_gates(root: Path) -> list[dict[str, Any]]:
    results = []
    for label, node_id in (
        (
            "rate_limit_privacy_group",
            "backend/tests/test_actor_rate_limit_store.py::"
            "test_privacy_group_migration_replaces_only_the_named_check",
        ),
        ("gateway_ingress", "tests/test_walksafe_android_gateway_ingress_current.py"),
    ):
        argv, _stdout, exit_code = _run_focused_command(root, node_id)
        require(exit_code == 0, f"fresh R005 focused gate failed: {label}")
        results.append(
            {
                "gate": label,
                "argv": argv,
                "exit_code": exit_code,
                "stdout_retention": "NOT_RETAINED_NONCREDIT_NONAUTHORITY",
            }
        )
    return results


def _raw_review_binding(relative: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": relative.as_posix(),
        "sha256": sha256_bytes(raw),
        "byte_length": len(raw),
    }


def _physical_binding(root: Path, relative: Path) -> dict[str, Any]:
    raw = safe_regular_bytes(root, relative)
    return {
        "path": relative.as_posix(),
        "sha256": sha256_bytes(raw),
        "byte_length": len(raw),
    }


def _physical_control_binding(root: Path, relative: Path) -> dict[str, Any]:
    binding = _physical_binding(root, relative)
    info = (root / relative).stat()
    binding["mode"] = f"{stat.S_IMODE(info.st_mode):04o}"
    return binding


def _r001_historical_review_bindings(root: Path) -> list[dict[str, Any]]:
    rows = []
    for relative in R001_HISTORICAL_REVIEW_PATHS:
        raw = safe_regular_bytes(root, relative)
        digest, byte_length = R001_HISTORICAL_REVIEW_PINS[relative]
        require(
            len(raw) == byte_length and sha256_bytes(raw) == digest,
            f"R001 failed-preparation history differs: {relative}",
        )
        rows.append(
            {
                **_raw_review_binding(relative, raw),
                "status": "FAILED_PREPARATION_HISTORICAL_NONCREDIT",
                "reason": "TRANSITION_REVIEW_RESULT_AND_INDEPENDENT_REVIEW_ABSENT",
            }
        )
    old_review = strict_json(
        safe_regular_bytes(root, R001_INDEPENDENT_REVIEW_REL),
        "R001 goal independent review",
    )
    require(
        old_review.get("review_subject_binding", {}).get("file_sha256")
        == R001_HISTORICAL_REVIEW_PINS[R001_REVIEW_SUBJECT_REL][0]
        and old_review.get("decision") == "PASS_INTERNAL_ONLY"
        and old_review.get("external_independence_claimed") is False,
        "R001 historical goal review differs",
    )
    return rows


def _r002_failed_attempt_bindings(root: Path) -> dict[str, Any]:
    review_rows = []
    for relative in R002_FAILED_REVIEW_PATHS:
        raw = _require_pinned_0600(
            root,
            relative,
            R002_FAILED_REVIEW_PINS[relative],
            label="R002 failed review history",
        )
        review_rows.append(
            {
                **_raw_review_binding(relative, raw),
                "status": "FAILED_WRITE_REVIEW_HISTORICAL_NONAUTHORITY",
            }
        )
    orphan_rows = []
    for relative in R002_FAILED_ORPHAN_PATHS:
        reusable = relative in SHARED_PUBLICATION_OUTPUT_PATHS
        target = root / relative
        if not target.exists() and not target.is_symlink():
            _require_exact_or_absent_0600(
                root,
                relative,
                b"",
                label="R002 failed publication orphan",
            )
            digest, byte_length = R002_FAILED_ORPHAN_PINS[relative]
            orphan_rows.append(
                {
                    "path": relative.as_posix(),
                    "expected_candidate_sha256": digest,
                    "expected_candidate_byte_length": byte_length,
                    "status": "FAILED_ATTEMPT_OUTPUT_ABSENT_ADD_ONLY_RECOVERY_ALLOWED",
                    "authorizes_r003": False,
                    "authorizes_r004": False,
                }
            )
            continue
        raw = _require_pinned_0600(
            root,
            relative,
            R002_FAILED_ORPHAN_PINS[relative],
            label="R002 failed publication orphan",
        )
        orphan_rows.append(
            {
                **_raw_review_binding(relative, raw),
                "status": (
                    "RETAINED_REUSABLE_ONLY_IF_EXACT_CANDIDATE_BYTES"
                    if reusable
                    else "FAILED_WRITE_REVIEW_DEPENDENT_NONAUTHORITY"
                ),
                "authorizes_r003": False,
                "authorizes_r004": False,
            }
        )
    r002_subject = strict_json(
        safe_regular_bytes(root, R002_REVIEW_SUBJECT_REL),
        "R002 failed review subject",
    )
    baseline = (
        r002_subject.get("validation_gate_boundary", {})
        .get("full_goal_graph_baseline")
    )
    require(
        isinstance(baseline, dict)
        and baseline.get("error_count") == FAILED_R002_FULL_GOAL_BASELINE_COUNT
        and baseline.get("errors_sha256")
        == FAILED_R002_FULL_GOAL_BASELINE_SHA256,
        "R002 failed attempt full Goal baseline differs",
    )
    failure = {
        "stdout_utf8": FAILED_R002_WRITE_STDOUT.decode("utf-8").rstrip("\n"),
        "stdout_sha256": sha256_bytes(FAILED_R002_WRITE_STDOUT),
        "stdout_byte_count": len(FAILED_R002_WRITE_STDOUT),
        "exit_code": FAILED_R002_WRITE_EXIT_CODE,
        "wall_time_seconds": FAILED_R002_WRITE_WALL_TIME_SECONDS,
        "rollback_checkpoint_binding": {
            "path": CHECKPOINT_REL.as_posix(),
            "sha256": SOURCE_CHECKPOINT_SHA256,
            "byte_length": SOURCE_CHECKPOINT_BYTE_COUNT,
            "sequence": SOURCE_SEQUENCE,
            "tail_event_id": SOURCE_EVENT_ID,
            "tail_event_sha256": SOURCE_EVENT_SHA256,
        },
        "r002_full_goal_baseline": {
            "error_count": baseline["error_count"],
            "errors_sha256": baseline["errors_sha256"],
            "source_review_subject_binding": _physical_binding(
                root, R002_REVIEW_SUBJECT_REL
            ),
        },
    }
    return {
        "review_history": review_rows,
        "orphan_outputs": orphan_rows,
        "failed_write": failure,
        "r002_authorizes_r003": False,
        "r002_authorizes_r004": False,
    }


def _r003_failed_attempt_bindings(root: Path) -> dict[str, Any]:
    review_rows = []
    for relative in R003_FAILED_REVIEW_PATHS:
        raw = _require_pinned_0600(
            root,
            relative,
            R003_FAILED_REVIEW_PINS[relative],
            label="R003 failed review history",
        )
        review_rows.append(
            {
                **_raw_review_binding(relative, raw),
                "status": "FAILED_WRITE_REVIEW_HISTORICAL_NONAUTHORITY",
                "authorizes_r004": False,
            }
        )
    receipt_raw = _require_pinned_0600(
        root,
        R003_COMPLETION_REL,
        R003_FAILED_COMPLETION_PIN,
        label="R003 failed completion receipt",
    )
    r003_subject = strict_json(
        safe_regular_bytes(root, R003_REVIEW_SUBJECT_REL),
        "R003 failed review subject",
    )
    baseline = (
        r003_subject.get("validation_gate_boundary", {})
        .get("full_goal_graph_baseline")
    )
    require(
        isinstance(baseline, dict)
        and baseline.get("error_count") == FAILED_R002_FULL_GOAL_BASELINE_COUNT
        and baseline.get("errors_sha256")
        == FAILED_R002_FULL_GOAL_BASELINE_SHA256,
        "R003 failed attempt full Goal baseline differs",
    )
    return {
        "review_history": review_rows,
        "completion_receipt": {
            **_raw_review_binding(R003_COMPLETION_REL, receipt_raw),
            "status": "FAILED_WRITE_HISTORICAL_NONAUTHORITY",
            "authorizes_r004": False,
        },
        "failed_write": {
            "stdout_utf8": FAILED_R003_WRITE_STDOUT.decode("utf-8").rstrip("\n"),
            "stdout_sha256": sha256_bytes(FAILED_R003_WRITE_STDOUT),
            "stdout_byte_count": len(FAILED_R003_WRITE_STDOUT),
            "exit_code": FAILED_R003_WRITE_EXIT_CODE,
            "wall_time_seconds": FAILED_R003_WRITE_WALL_TIME_SECONDS,
            "rollback_checkpoint_binding": {
                "path": CHECKPOINT_REL.as_posix(),
                "sha256": SOURCE_CHECKPOINT_SHA256,
                "byte_length": SOURCE_CHECKPOINT_BYTE_COUNT,
                "sequence": SOURCE_SEQUENCE,
                "tail_event_id": SOURCE_EVENT_ID,
                "tail_event_sha256": SOURCE_EVENT_SHA256,
            },
            "r003_full_goal_baseline": {
                "error_count": baseline["error_count"],
                "errors_sha256": baseline["errors_sha256"],
                "source_review_subject_binding": _physical_binding(
                    root, R003_REVIEW_SUBJECT_REL
                ),
            },
        },
        "r003_authorizes_r004": False,
    }


def _r004_failed_preparation_bindings(root: Path) -> dict[str, Any]:
    prepared_rows = []
    for relative in R004_FAILED_PREPARATION_PATHS:
        raw = _require_pinned_0600(
            root,
            relative,
            R004_FAILED_PREPARATION_PINS[relative],
            label="R004 failed preparation history",
        )
        prepared_rows.append(
            {
                **_raw_review_binding(relative, raw),
                "status": "FAILED_PREPARATION_HISTORICAL_NONAUTHORITY",
                "authorizes_r005": False,
            }
        )
    for relative in R004_REJECTED_ABSENT_PATHS:
        target = root / relative
        require(
            not target.exists() and not target.is_symlink(),
            f"R004 rejected output unexpectedly exists: {relative}",
        )
    subject = strict_json(
        safe_regular_bytes(root, R004_REVIEW_SUBJECT_REL),
        "R004 failed preparation subject",
    )
    bound_controls = subject.get("control_code_bindings")
    require(
        isinstance(bound_controls, list)
        and R004_BOUND_SEQ83_TEST_BINDING in bound_controls,
        "R004 bound seq83 test identity differs",
    )
    current_test_binding = _physical_control_binding(
        root, SEQ83_RECONSTRUCTION_TEST_REL
    )
    require(
        current_test_binding == R005_SEQ83_TEST_BINDING,
        "R005 corrected seq83 test identity differs",
    )
    return {
        "prepared_history": prepared_rows,
        "rejected_absent_paths": [
            relative.as_posix() for relative in R004_REJECTED_ABSENT_PATHS
        ],
        "failed_test": {
            "node_id": FAILED_R004_PREPARATION_TEST_NODE,
            "exit_code": FAILED_R004_TEST_EXIT_CODE,
            "failed_test_count": FAILED_R004_TEST_COUNT,
            "diagnostic": FAILED_R004_DIAGNOSTIC,
            "actual_error": FAILED_R004_ACTUAL_ERROR,
            "previous_bound_test": copy.deepcopy(R004_BOUND_SEQ83_TEST_BINDING),
            "corrected_test": current_test_binding,
        },
        "r004_authorizes_r005": False,
    }


def _validate_failed_attempt_history(root: Path) -> None:
    _r001_historical_review_bindings(root)
    _r002_failed_attempt_bindings(root)
    _r003_failed_attempt_bindings(root)
    _r004_failed_preparation_bindings(root)


def _require_retained_candidate(
    root: Path,
    relative: Path,
    candidate: bytes,
) -> bytes:
    require(relative in SHARED_PUBLICATION_OUTPUT_PATHS, "retained path scope differs")
    require(
        (sha256_bytes(candidate), len(candidate))
        == R002_FAILED_ORPHAN_PINS[relative],
        f"R002 retained shared evidence candidate pin differs: {relative}",
    )
    present = _require_exact_or_absent_0600(
        root,
        relative,
        candidate,
        label="R002 retained shared evidence",
    )
    return safe_regular_bytes(root, relative) if present else candidate


def _transition_proposal(source_raw: bytes, source: Mapping[str, Any]) -> dict[str, Any]:
    history = source["goal_execution"]["transition_history"]
    tail = history[-1]
    skeleton = {
        "schema_version": "walksafe.fp046-r002.seq86-87-third-recovery-proposal.v4",
        "source_checkpoint_binding": {
            "path": CHECKPOINT_REL.as_posix(),
            "sha256": sha256_bytes(source_raw),
            "byte_length": len(source_raw),
            "sequence": SOURCE_SEQUENCE,
            "tail_event_id": tail["event_id"],
            "tail_event_sha256": tail["event_sha256"],
        },
        "history_prefix_contract": {
            "preserved_sequences": [1, SOURCE_SEQUENCE],
            "preserved_byte_exact": True,
            "source_tail_adjacency_sha256": SOURCE_EVENT_SHA256,
        },
        "canonical_update": {
            "sequence": UPDATE_SEQUENCE,
            "event_id": UPDATE_EVENT_ID,
            "event_type": "CANONICAL_BINDINGS_UPDATED",
            "previous_event_sha256": SOURCE_EVENT_SHA256,
            "previous_focus_goal_id": GOAL_ID,
            "focus_goal_id": GOAL_ID,
            "from_status": "IN_PROGRESS",
            "to_status": "IN_PROGRESS",
            "status_changes": {},
            "produced_by_goal_id": GOAL_ID,
            "produced_binding_roles": list(PRODUCED_ROLES),
            "changed_binding_roles": list(CHANGED_ROLES),
            "impact_closure_goal_ids": [PARENT_GOAL_ID, FP048_R001_GOAL_ID],
            "impact_disposition_by_goal": {
                PARENT_GOAL_ID: {
                    "result": "REVALIDATION_REFRESH_REQUIRED",
                    "target_status": "READY",
                },
                FP048_R001_GOAL_ID: {
                    "result": "REOPEN_REQUIRED",
                    "target_status": "SUPERSEDED",
                },
            },
            "reopened_completion_event_sha256_by_goal": {
                FP048_R001_GOAL_ID: FP048_R001_COMPLETION_EVENT_SHA256,
            },
            "pending_producer_completion_goal_id_after": GOAL_ID,
            "review_binding_source": "R005_PHYSICAL_THIRD_RECOVERY_REVIEW_INPUTS_AFTER_PROPOSAL",
        },
        "completion": {
            "sequence": COMPLETION_SEQUENCE,
            "event_id": COMPLETION_EVENT_ID,
            "event_type": "GOAL_COMPLETED",
            "previous_event_reference": "DERIVED_SEQ86_EVENT_SHA256",
            "canonical_update_event_reference": "DERIVED_SEQ86_EVENT_SHA256",
            "previous_focus_goal_id": GOAL_ID,
            "focus_goal_id": PARENT_GOAL_ID,
            "subject_goal_id": GOAL_ID,
            "from_status": "IN_PROGRESS",
            "to_status": "COMPLETE_AT_TARGET",
            "status_changes": {GOAL_ID: "COMPLETE_AT_TARGET"},
            "ready_frontier_goal_ids": [
                PARENT_GOAL_ID,
                EPIC04_GOAL_ID,
                EPIC12_GOAL_ID,
            ],
            "completion_role": COMPLETION_ROLE,
            "completion_receipt_path": COMPLETION_REL.as_posix(),
            "completion_receipt_document_id": COMPLETION_DOCUMENT_ID,
            "pending_producer_completion_goal_id_after": "",
            "fp048_r001_status_before_seq88": "COMPLETE_AT_TARGET",
            "fp048_r002_activation_before_seq89": False,
        },
        "review_dependent_fields_excluded": [
            "seq86.event_sha256",
            "seq86.transition_control_review_binding.sha256",
            "seq87.previous_event_sha256",
            "seq87.canonical_update_event_sha256",
            "seq87.event_sha256",
            "completion_receipt.file_sha256",
        ],
        "completion_boundary": copy.deepcopy(ZERO_CREDIT_BOUNDARY),
    }
    return {
        "skeleton": skeleton,
        "proposal_sha256": continuation.canonical_json_sha256(skeleton),
    }


def _validate_dispatch(source_raw: bytes, function_name: str, label: str) -> None:
    try:
        tree = ast.parse(source_raw.decode("utf-8"))
    except (SyntaxError, UnicodeError) as exc:
        raise CompletionApplyError(f"{label} source cannot be parsed") from exc
    validate_nodes = [
        node
        for node in tree.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        and node.name == "validate"
    ]
    require(len(validate_nodes) == 1, f"{label} validate dispatcher differs")
    called = False
    for node in ast.walk(validate_nodes[0]):
        if not isinstance(node, ast.Call):
            continue
        name = (
            node.func.id
            if isinstance(node.func, ast.Name)
            else node.func.attr
            if isinstance(node.func, ast.Attribute)
            else None
        )
        if name == function_name:
            called = True
            break
    require(called, f"{label} completion validator is not dispatched")


def _validator_authority(root: Path) -> list[dict[str, Any]]:
    modules = (
        ("continuation", continuation, CONTINUATION_CHECKER_REL),
        (
            "goal_graph",
            importlib.import_module("scripts.check_walksafe_goal_graph_v2_4"),
            GOAL_CHECKER_REL,
        ),
    )
    rows = []
    for label, module, relative in modules:
        validator = getattr(module, COMPLETION_VALIDATOR_NAME, None)
        require(callable(validator), f"{label} completion validator is unavailable")
        raw = safe_regular_bytes(root, relative)
        _validate_dispatch(raw, COMPLETION_VALIDATOR_NAME, label)
        rows.append(
            {
                "checker": label,
                "validator": COMPLETION_VALIDATOR_NAME,
                "dispatcher": "validate",
                "source_binding": _raw_review_binding(relative, raw),
            }
        )
    return rows


def _full_goal_graph_baseline_from_errors(
    errors: Sequence[str],
) -> dict[str, Any]:
    exact_errors = list(errors)
    return {
        "status": "EXISTING_UNRELATED_ERRORS_NOT_GATE_NOT_CREDIT",
        "comparison_rule": "PROJECTED_NEW_ERROR_MULTISET_DELTA_MUST_EQUAL_ZERO",
        "error_count": len(exact_errors),
        "errors_sha256": continuation.canonical_json_sha256(exact_errors),
        "errors": exact_errors,
    }


def _full_goal_graph_baseline(root: Path) -> dict[str, Any]:
    return _full_goal_graph_baseline_from_errors(_run_full_goal_checker(root))


def _implementation_document(
    root: Path,
    gap_binding: Mapping[str, Any],
    backlog_binding: Mapping[str, Any],
) -> dict[str, Any]:
    return {
        "document_id": "WS-FP046-R002-IMPLEMENTATION-RECORD-20260825-001",
        "goal_id": GOAL_ID,
        "source_event": {
            "sequence": SOURCE_SEQUENCE,
            "event_id": SOURCE_EVENT_ID,
            "event_sha256": SOURCE_EVENT_SHA256,
        },
        "result": "PASS_INTERNAL_STATIC_ONLY",
        "closed_findings": [
            "FP046-RATE-GROUP-PRIVACY-CHECK-OMISSION",
            "FP046-NGINX-ACCOUNT-DELETION-INGRESS-OMISSION",
        ],
        "implementation_files": _implementation_rows(root),
        "excluded_concurrent_noncredit_paths": [
            {
                "path": path.as_posix(),
                "reason": (
                    "mixed Wave2 capacity/worker, shared runner, or generated catalog "
                    "delta; present in the live snapshot but excluded from FP046 R002 credit"
                ),
            }
            for path in CONCURRENT_NONCREDIT_PATHS
        ],
        "r030_bindings": [copy.deepcopy(gap_binding), copy.deepcopy(backlog_binding)],
        "completion_boundary": copy.deepcopy(ZERO_CREDIT_BOUNDARY),
    }


def _review_candidates(
    root: Path,
    source_raw: bytes,
    source: Mapping[str, Any],
    gap_binding: Mapping[str, Any],
    backlog_binding: Mapping[str, Any],
) -> tuple[bytes, bytes]:
    require_exact_source(source_raw, source)
    r001_history = _r001_historical_review_bindings(root)
    r002_failed_attempt = _r002_failed_attempt_bindings(root)
    r003_failed_attempt = _r003_failed_attempt_bindings(root)
    r004_failed_preparation = _r004_failed_preparation_bindings(root)
    proposal = _transition_proposal(source_raw, source)
    validator_authority = _validator_authority(root)
    implementation = _implementation_document(root, gap_binding, backlog_binding)
    implementation_raw = json_bytes(implementation)
    _require_retained_candidate(root, IMPLEMENTATION_REL, implementation_raw)
    implementation_binding = _artifact_binding(
        IMPLEMENTATION_REL, implementation_raw, implementation["document_id"]
    )
    control_code_bindings = [
        _physical_control_binding(root, relative)
        for relative in CONTROL_AUTHORITY_PATHS
    ]
    seq83_test_binding = next(
        row
        for row in control_code_bindings
        if row["path"] == SEQ83_RECONSTRUCTION_TEST_REL.as_posix()
    )
    require(
        seq83_test_binding == R005_SEQ83_TEST_BINDING,
        "R005 seq83 reconstruction test control binding differs",
    )
    direct_credit_bindings = _implementation_rows(root)
    concurrent_noncredit_bindings = [
        {
            **_physical_binding(root, relative),
            "status": "LIVE_MANAGED_NONCREDIT",
            "reason": (
                "concurrent Wave2 capacity/worker, shared runner, or generated "
                "catalog delta; excluded from FP046 R002 completion credit"
            ),
        }
        for relative in CONCURRENT_NONCREDIT_PATHS
    ]
    review_subject = {
        "schema_version": "walksafe.fp046-r002.third-recovery-review-subject.v5",
        "document_id": "WS-FP046-R002-THIRD-RECOVERY-REVIEW-SUBJECT-20260825-R005",
        "review_round": "R005",
        "goal_id": GOAL_ID,
        "producer_role": "walksafe-fp046-r002-publisher",
        "required_reviewer_role": "walksafe-internal-independent-reviewer",
        "source_checkpoint_binding": copy.deepcopy(
            proposal["skeleton"]["source_checkpoint_binding"]
        ),
        "transition_proposal": proposal,
        "subject_bindings": [
            copy.deepcopy(gap_binding),
            copy.deepcopy(backlog_binding),
            implementation_binding,
        ],
        "control_code_bindings": control_code_bindings,
        "validator_authority": validator_authority,
        "validation_gate_boundary": {
            "required_zero_error_gates_after_exchange": [
                "FULL_PROJECT_CONTINUATION",
                "CONTINUATION_FP046_R002_EXACT_SEQ86_87_SUFFIX",
                "GOAL_GRAPH_FP046_R002_EXACT_SEQ86_87_SUFFIX",
            ],
            "full_goal_graph_baseline": _full_goal_graph_baseline(root),
            "full_goal_graph_is_completion_gate": False,
            "full_goal_graph_credit_delta": 0,
            "fresh_focused_gates": {
                "preflight_exit_code_must_equal": 0,
                "each_commit_guard_exit_code_must_equal": 0,
                "fresh_stdout_is_not_replayed_or_completion_credit": True,
                "retained_r002_logs_are_historical_exact_bytes": True,
            },
            "checkpoint_serialization_contract": {
                "source_round_trip": "UTF8_INDENT2_SORT_KEYS_FALSE_EXACT_BYTES",
                "projected_serializer": "CHECKPOINT_INSERTION_ORDER_SORT_KEYS_FALSE",
                "historical_event_ordered_fragments_preserved_sequences": [
                    1,
                    SOURCE_SEQUENCE,
                ],
                "canonical_sorted_evidence_serializer_allowed_for_checkpoint": False,
            },
        },
        "credit_scope": {
            "status": "FP046_R002_DIRECT_INTERNAL_STATIC_ONLY",
            "exact_file_count": len(IMPLEMENTATION_SCOPE_PATHS),
            "files": direct_credit_bindings,
        },
        "noncredit_scope": {
            "concurrent_live_managed_files": concurrent_noncredit_bindings,
            "r001_failed_preparation_history": r001_history,
            "r001_authorizes_publication": False,
            "r002_failed_attempt": r002_failed_attempt,
            "r002_authorizes_r004": False,
            "r002_authorizes_r005": False,
            "r003_failed_attempt": r003_failed_attempt,
            "r003_authorizes_r004": False,
            "r003_authorizes_r005": False,
            "r004_failed_preparation": r004_failed_preparation,
            "r004_authorizes_r005": False,
            "formal_device_external_deployment_release_credit": 0,
        },
        "successor_contract": {
            "reopened_goal_id": FP048_R001_GOAL_ID,
            "reopened_completion_event_sha256": FP048_R001_COMPLETION_EVENT_SHA256,
            "successor_goal_id": NEXT_GOAL_ID,
            "successor_work_item_id": NEXT_WORK_ITEM_ID,
            "successor_gap_id": NEXT_GAP_ID,
            "next_action": NEXT_ACTION,
        },
        "required_checks": [
            "seq1-85 byte-exact preservation",
            "seq1-85 insertion-order event fragments and checkpoint serializer preservation",
            "deterministic non-circular seq86/87 proposal seal",
            "both completion validators exist and are dispatched",
            "R030 add-only predecessor binding",
            "R002 shared orphan evidence exact retained-byte reconstruction",
            "R002 completion receipt and reviews are historical nonauthority",
            "R003 completion receipt and reviews are historical nonauthority",
            "R004 subject and assignment are failed-preparation nonauthority",
            "corrected seq83 test SHA, size, and mode replace the R004 bound identity",
            "fresh focused gates pass without replaying elapsed-time stdout bytes",
            "FP048 R001 reopen impact binding",
            "FP046 direct six-path scope and concurrent Wave2 noncredit exclusions",
            "zero formal/device/external/deployment/release credit",
        ],
        "completion_boundary": copy.deepcopy(ZERO_CREDIT_BOUNDARY),
        "external_independence_claimed": False,
    }
    review_subject_raw = json_bytes(review_subject)
    review_subject_binding = _artifact_binding(
        REVIEW_SUBJECT_REL, review_subject_raw, review_subject["document_id"]
    )
    assignment = {
        "schema_version": "walksafe.transition-control-review.v1",
        "document_id": "WS-FP046-R002-SEQ86-87-THIRD-RECOVERY-REVIEW-ASSIGNMENT-R005",
        "round_id": "FP046-R002-SEQ86-87-THIRD-RECOVERY-R005",
        "goal_id": GOAL_ID,
        "sequence_pair": [UPDATE_SEQUENCE, COMPLETION_SEQUENCE],
        "assigner": "walksafe-control-publisher",
        "executor": "walksafe-fp046-r002-publisher",
        "required_reviewer": "walksafe-internal-transition-reviewer",
        "review_scope": "SEQ86_87_CANONICAL_UPDATE_COMPLETION_AND_FP048_REOPEN",
        "review_subject_binding": review_subject_binding,
        "transition_proposal_sha256": proposal["proposal_sha256"],
        "source_checkpoint_binding": copy.deepcopy(
            proposal["skeleton"]["source_checkpoint_binding"]
        ),
        "external_independence_claimed": False,
    }
    return review_subject_raw, json_bytes(assignment)


def _validate_review_input(
    value: Mapping[str, Any],
    *,
    label: str,
    expected_binding_field: str,
    expected_binding: Mapping[str, Any],
    forbidden_reviewer: str,
) -> None:
    reviewer = value.get("reviewer_id")
    require(
        isinstance(reviewer, str)
        and reviewer
        and reviewer != forbidden_reviewer
        and isinstance(value.get("reviewer_task_id"), str)
        and bool(value["reviewer_task_id"]),
        f"{label} reviewer provenance differs",
    )
    require(
        value.get(expected_binding_field) == expected_binding
        and value.get("decision") == "PASS_INTERNAL_ONLY"
        and value.get("external_independence_claimed") is False
        and value.get("external_review_status") == "NOT_RUN"
        and value.get("completion_boundary") == ZERO_CREDIT_BOUNDARY,
        f"{label} decision or zero-credit boundary differs",
    )


def _write_add_only_exact(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    if _require_exact_or_absent_0600(
        root,
        relative,
        raw,
        label="add-only output",
    ):
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    if _require_exact_or_absent_0600(
        root,
        relative,
        raw,
        label="add-only output",
    ):
        return
    descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        raise


def prepare_review_inputs_r005(root: Path = ROOT) -> Mapping[Path, bytes]:
    root = Path(root).resolve(strict=True)
    source_raw = safe_regular_bytes(root, CHECKPOINT_REL)
    source = strict_json(source_raw, CHECKPOINT_REL.as_posix())
    require_exact_source(source_raw, source)
    gap, backlog = _build_r030(
        root,
        (
            datetime.fromisoformat(
                source["goal_execution"]["transition_history"][-1]["occurred_at"]
            )
            + timedelta(seconds=1)
        ).isoformat(),
    )
    gap_raw = json_bytes(gap)
    backlog_raw = json_bytes(backlog)
    gap_md, backlog_md = _markdown_pair(gap, backlog)
    for relative, candidate in (
        (GAP_JSON_REL, gap_raw),
        (GAP_MD_REL, gap_md),
        (BACKLOG_JSON_REL, backlog_raw),
        (BACKLOG_MD_REL, backlog_md),
    ):
        _require_retained_candidate(root, relative, candidate)
    gap_binding = _binding(
        GAP_JSON_REL,
        gap_raw,
        role="IMPLEMENTATION_GAP",
        document_id=gap["metadata"]["report_id"],
        identity_json_path="metadata.report_id",
    )
    backlog_binding = _binding(
        BACKLOG_JSON_REL,
        backlog_raw,
        role="IMPLEMENTATION_BACKLOG",
        document_id=backlog["metadata"]["backlog_id"],
        identity_json_path="metadata.backlog_id",
    )
    subject_raw, assignment_raw = _review_candidates(
        root, source_raw, source, gap_binding, backlog_binding
    )
    outputs = {
        REVIEW_SUBJECT_REL: subject_raw,
        TRANSITION_ASSIGNMENT_REL: assignment_raw,
    }
    for relative, raw in outputs.items():
        _write_add_only_exact(root, relative, raw)
    require(
        safe_regular_bytes(root, CHECKPOINT_REL) == source_raw,
        "checkpoint changed while preparing review inputs",
    )
    return outputs


# Compatibility spelling always targets the only current authority round, R005.
prepare_review_inputs = prepare_review_inputs_r005


def build_evidence(
    root: Path,
    source: Mapping[str, Any],
    *,
    update_occurred_at: str | None = None,
) -> CompletionEvidence:
    source_raw = safe_regular_bytes(root, CHECKPOINT_REL)
    require_exact_source(source_raw, source)
    source_tail_at = datetime.fromisoformat(
        source["goal_execution"]["transition_history"][-1]["occurred_at"]
    )
    update_dt = (
        datetime.fromisoformat(update_occurred_at)
        if update_occurred_at is not None
        else source_tail_at + timedelta(seconds=1)
    )
    require(update_dt > source_tail_at, "seq86 time does not follow seq85")
    completion_dt = update_dt + timedelta(seconds=1)
    update_at = update_dt.isoformat()
    completion_at = completion_dt.isoformat()
    _validate_failed_attempt_history(root)

    gap, backlog = _build_r030(root, update_at)
    gap_raw = json_bytes(gap)
    backlog_raw = json_bytes(backlog)
    gap_md, backlog_md = _markdown_pair(gap, backlog)
    for relative, candidate in (
        (GAP_JSON_REL, gap_raw),
        (GAP_MD_REL, gap_md),
        (BACKLOG_JSON_REL, backlog_raw),
        (BACKLOG_MD_REL, backlog_md),
    ):
        _require_retained_candidate(root, relative, candidate)
    raw_by_path: dict[Path, bytes] = {
        GAP_JSON_REL: gap_raw,
        GAP_MD_REL: gap_md,
        BACKLOG_JSON_REL: backlog_raw,
        BACKLOG_MD_REL: backlog_md,
    }
    gap_binding = _binding(
        GAP_JSON_REL,
        gap_raw,
        role="IMPLEMENTATION_GAP",
        document_id=gap["metadata"]["report_id"],
        identity_json_path="metadata.report_id",
    )
    backlog_binding = _binding(
        BACKLOG_JSON_REL,
        backlog_raw,
        role="IMPLEMENTATION_BACKLOG",
        document_id=backlog["metadata"]["backlog_id"],
        identity_json_path="metadata.backlog_id",
    )
    implementation = _implementation_document(root, gap_binding, backlog_binding)
    implementation_raw = json_bytes(implementation)
    _require_retained_candidate(root, IMPLEMENTATION_REL, implementation_raw)
    raw_by_path[IMPLEMENTATION_REL] = implementation_raw
    implementation_binding = _artifact_binding(
        IMPLEMENTATION_REL, implementation_raw, implementation["document_id"]
    )
    fresh_gate_results = _run_fresh_recovery_gates(root)
    rate_argv, ingress_argv = [row["argv"] for row in fresh_gate_results]
    rate_log = _require_pinned_0600(
        root,
        RATE_LIMIT_LOG_REL,
        R002_FAILED_ORPHAN_PINS[RATE_LIMIT_LOG_REL],
        label="R002 retained verification log",
    )
    ingress_log = _require_pinned_0600(
        root,
        INGRESS_LOG_REL,
        R002_FAILED_ORPHAN_PINS[INGRESS_LOG_REL],
        label="R002 retained verification log",
    )
    raw_by_path[RATE_LIMIT_LOG_REL] = rate_log
    raw_by_path[INGRESS_LOG_REL] = ingress_log
    verification_receipt = {
        "document_id": "WS-FP046-R002-VERIFICATION-RECEIPT-20260825-001",
        "goal_id": GOAL_ID,
        "status": "PASS_INTERNAL_STATIC_ONLY",
        "commands": [
            {
                "argv": rate_argv,
                "exit_code": 0,
                "output_path": RATE_LIMIT_LOG_REL.as_posix(),
                "output_sha256": sha256_bytes(rate_log),
                "output_byte_count": len(rate_log),
            },
            {
                "argv": ingress_argv,
                "exit_code": 0,
                "output_path": INGRESS_LOG_REL.as_posix(),
                "output_sha256": sha256_bytes(ingress_log),
                "output_byte_count": len(ingress_log),
            },
        ],
        "database_integration_status": "NOT_RUN",
        "completion_boundary": copy.deepcopy(ZERO_CREDIT_BOUNDARY),
    }
    verification_receipt_raw = json_bytes(verification_receipt)
    _require_retained_candidate(
        root, VERIFICATION_RECEIPT_REL, verification_receipt_raw
    )
    raw_by_path[VERIFICATION_RECEIPT_REL] = verification_receipt_raw
    verification_receipt_binding = _artifact_binding(
        VERIFICATION_RECEIPT_REL,
        verification_receipt_raw,
        verification_receipt["document_id"],
    )
    verification = {
        "document_id": "WS-FP046-R002-VERIFICATION-RESULT-20260825-001",
        "goal_id": GOAL_ID,
        "result": "PASS_INTERNAL_STATIC_ONLY",
        "implementation_record_binding": implementation_binding,
        "verification_receipt_binding": verification_receipt_binding,
        "claim_scope": "REPOSITORY_INTERNAL_STATIC_AND_INGRESS_REGRESSION_ONLY",
        "database_integration_status": "NOT_RUN",
        "completion_boundary": copy.deepcopy(ZERO_CREDIT_BOUNDARY),
    }
    verification_raw = json_bytes(verification)
    _require_retained_candidate(root, VERIFICATION_REL, verification_raw)
    raw_by_path[VERIFICATION_REL] = verification_raw
    verification_binding = _artifact_binding(
        VERIFICATION_REL, verification_raw, verification["document_id"]
    )
    successor = {
        "document_id": "WS-FP046-R002-SUCCESSOR-TRACE-20260825-001",
        "completed_goal_id": GOAL_ID,
        "successor_goal_id": NEXT_GOAL_ID,
        "successor_work_item_id": NEXT_WORK_ITEM_ID,
        "successor_gap_id": NEXT_GAP_ID,
        "next_action": NEXT_ACTION,
        "status": "PLANNED_NOT_ACTIVATED",
        "future_transition": "seq88 GOAL_SUPERSEDED then seq89 GOAL_READY",
        "ready_or_started_event_claimed": False,
        "verification_binding": verification_binding,
        "completion_boundary": copy.deepcopy(ZERO_CREDIT_BOUNDARY),
    }
    successor_raw = json_bytes(successor)
    _require_retained_candidate(root, SUCCESSOR_REL, successor_raw)
    raw_by_path[SUCCESSOR_REL] = successor_raw
    successor_binding = _artifact_binding(
        SUCCESSOR_REL, successor_raw, successor["document_id"]
    )
    expected_review_subject_raw, expected_assignment_raw = _review_candidates(
        root, source_raw, source, gap_binding, backlog_binding
    )
    review_subject_raw = safe_regular_bytes(root, REVIEW_SUBJECT_REL)
    require(
        review_subject_raw == expected_review_subject_raw,
        "prepared review subject bytes differ",
    )
    review_subject = strict_json(review_subject_raw, "review subject")
    raw_by_path[REVIEW_SUBJECT_REL] = review_subject_raw
    review_subject_binding = _artifact_binding(
        REVIEW_SUBJECT_REL, review_subject_raw, review_subject["document_id"]
    )
    independent_review_raw = safe_regular_bytes(root, INDEPENDENT_REVIEW_REL)
    independent_review = strict_json(independent_review_raw, "independent review")
    require(
        independent_review.get("document_id")
        == "WS-FP046-R002-THIRD-RECOVERY-INDEPENDENT-REVIEW-20260825-R005"
        and independent_review.get("goal_id") == GOAL_ID
        and independent_review.get("producer_role")
        == "walksafe-fp046-r002-publisher",
        "independent review identity differs",
    )
    _validate_review_input(
        independent_review,
        label="independent review",
        expected_binding_field="review_subject_binding",
        expected_binding=review_subject_binding,
        forbidden_reviewer="walksafe-fp046-r002-publisher",
    )
    require(
        independent_review.get("review_scope")
        == "FP046_R002_COMPLETION_AND_FP048_R001_REOPEN",
        "independent review scope differs",
    )
    raw_by_path[INDEPENDENT_REVIEW_REL] = independent_review_raw
    independent_review_binding = _artifact_binding(
        INDEPENDENT_REVIEW_REL,
        independent_review_raw,
        independent_review["document_id"],
    )
    transition_assignment_raw = safe_regular_bytes(root, TRANSITION_ASSIGNMENT_REL)
    require(
        transition_assignment_raw == expected_assignment_raw,
        "prepared transition assignment bytes differ",
    )
    transition_assignment = strict_json(
        transition_assignment_raw, "transition assignment"
    )
    raw_by_path[TRANSITION_ASSIGNMENT_REL] = transition_assignment_raw
    transition_assignment_binding = _raw_review_binding(
        TRANSITION_ASSIGNMENT_REL, transition_assignment_raw
    )
    transition_result_raw = safe_regular_bytes(root, TRANSITION_RESULT_REL)
    transition_result = strict_json(transition_result_raw, "transition review result")
    require(
        transition_result.get("document_id")
        == "WS-FP046-R002-SEQ86-87-THIRD-RECOVERY-REVIEW-RESULT-R005"
        and transition_result.get("goal_id") == GOAL_ID
        and transition_result.get("review_scope")
        == transition_assignment["review_scope"],
        "transition review result identity differs",
    )
    _validate_review_input(
        transition_result,
        label="transition review result",
        expected_binding_field="assignment_binding",
        expected_binding=transition_assignment_binding,
        forbidden_reviewer=transition_assignment["executor"],
    )
    raw_by_path[TRANSITION_RESULT_REL] = transition_result_raw
    transition_result_binding = _raw_review_binding(
        TRANSITION_RESULT_REL, transition_result_raw
    )
    transition_independent_raw = safe_regular_bytes(root, TRANSITION_INDEPENDENT_REL)
    transition_independent = strict_json(
        transition_independent_raw, "transition independent review"
    )
    require(
        transition_independent.get("document_id")
        == "WS-FP046-R002-SEQ86-87-THIRD-RECOVERY-INDEPENDENT-REVIEW-R005"
        and transition_independent.get("goal_id") == GOAL_ID
        and transition_independent.get("review_scope")
        == transition_assignment["review_scope"],
        "transition independent review identity differs",
    )
    _validate_review_input(
        transition_independent,
        label="transition independent review",
        expected_binding_field="review_result_binding",
        expected_binding=transition_result_binding,
        forbidden_reviewer=transition_assignment["executor"],
    )
    require(
        transition_independent["reviewer_id"]
        != transition_result["reviewer_id"],
        "transition independent reviewer is not distinct",
    )
    raw_by_path[TRANSITION_INDEPENDENT_REL] = transition_independent_raw
    transition_independent_binding = _raw_review_binding(
        TRANSITION_INDEPENDENT_REL, transition_independent_raw
    )
    transition_review_binding = {
        "assignment": transition_assignment_binding,
        "review_result": transition_result_binding,
        "independent_review": transition_independent_binding,
    }
    receipt = {
        "document_id": COMPLETION_DOCUMENT_ID,
        "goal_id": GOAL_ID,
        "goal_status": "COMPLETE_AT_TARGET",
        "source_sequence": SOURCE_SEQUENCE,
        "canonical_update_sequence": UPDATE_SEQUENCE,
        "completion_sequence": COMPLETION_SEQUENCE,
        "evidence_bindings": [
            gap_binding,
            backlog_binding,
            implementation_binding,
            verification_receipt_binding,
            verification_binding,
            successor_binding,
            review_subject_binding,
            independent_review_binding,
        ],
        "transition_control_review_binding": transition_review_binding,
        "recovery_authority_round": "R005",
        "failed_r002_completion_receipt": {
            **_physical_binding(root, R002_COMPLETION_REL),
            "status": "FAILED_WRITE_HISTORICAL_NONAUTHORITY",
            "authorizes_r003": False,
            "authorizes_r004": False,
            "authorizes_r005": False,
        },
        "failed_r003_completion_receipt": {
            **_physical_binding(root, R003_COMPLETION_REL),
            "status": "FAILED_WRITE_HISTORICAL_NONAUTHORITY",
            "authorizes_r004": False,
            "authorizes_r005": False,
        },
        "failed_r004_preparation": _r004_failed_preparation_bindings(root),
        "fresh_recovery_gate_results": fresh_gate_results,
        "successor": {
            "goal_id": NEXT_GOAL_ID,
            "work_item_id": NEXT_WORK_ITEM_ID,
            "status": "PLANNED_NOT_ACTIVATED",
        },
        "completion_boundary": copy.deepcopy(ZERO_CREDIT_BOUNDARY),
        "release_completion_claimed": False,
    }
    receipt_raw = json_bytes(receipt)
    raw_by_path[COMPLETION_REL] = receipt_raw
    completion_binding = _binding(
        COMPLETION_REL,
        receipt_raw,
        role=COMPLETION_ROLE,
        document_id=COMPLETION_DOCUMENT_ID,
        identity_json_path="document_id",
    )
    bindings = {
        "IMPLEMENTATION_GAP": gap_binding,
        "IMPLEMENTATION_BACKLOG": backlog_binding,
        COMPLETION_ROLE: completion_binding,
    }
    for relative in PUBLICATION_OUTPUT_PATHS:
        require(relative in raw_by_path, f"publication candidate is missing: {relative}")
        _require_exact_or_absent_0600(
            root,
            relative,
            raw_by_path[relative],
            label="idempotent orphan recovery",
        )

    source_paths = source["working_tree_snapshot"]["managed_changed_paths"]
    require(
        isinstance(source_paths, list)
        and len(source_paths) == SOURCE_MANAGED_PATH_COUNT
        and len(set(source_paths)) == SOURCE_MANAGED_PATH_COUNT,
        "source managed path inventory differs",
    )
    source_set = {Path(path) for path in source_paths}
    require(source_set.isdisjoint(ADDED_MANAGED_PATHS), "add-only path already managed")
    git_visible = snapshot_authority._git_visible_managed_paths(root)
    final_paths = source_set | git_visible | set(ADDED_MANAGED_PATHS)
    require(
        git_visible.issubset(final_paths)
        and len(final_paths) >= SOURCE_MANAGED_PATH_COUNT + len(ADDED_MANAGED_PATHS),
        "live successor managed path universe differs",
    )
    final_digests: dict[Path, str] = {}
    for relative in sorted(final_paths):
        if relative in raw_by_path:
            raw = raw_by_path[relative]
        else:
            raw = safe_regular_bytes(root, relative)
        final_digests[relative] = sha256_bytes(raw)
    return CompletionEvidence(
        raw_by_path=raw_by_path,
        bindings_by_role=bindings,
        update_occurred_at=update_at,
        completion_occurred_at=completion_at,
        final_sha256_by_path=final_digests,
        transition_review_binding=transition_review_binding,
    )


def _canonical_bindings(
    source: Mapping[str, Any], evidence: CompletionEvidence
) -> list[dict[str, Any]]:
    result = []
    seen: set[str] = set()
    for source_binding in source["canonical_bindings"]:
        role = source_binding["role"]
        require(role not in seen, f"duplicate source canonical role: {role}")
        seen.add(role)
        binding = copy.deepcopy(source_binding)
        binding.update(evidence.bindings_by_role.get(role, {}))
        result.append(binding)
    require(COMPLETION_ROLE not in seen, "completion role already present")
    result.append(
        {
            **copy.deepcopy(evidence.bindings_by_role[COMPLETION_ROLE]),
            "identity_json_path": "document_id",
            "mutable": False,
        }
    )
    return sorted(result, key=lambda row: row["role"])


def _runtime(state: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "focus_goal_id": state["focus_goal_id"],
        "focus_goal_path": state["focus_goal_path"],
        "focus_work_item_id": state["focus_work_item_id"],
        "focus_source": state["focus_source"],
        "ready_frontier_goal_ids": copy.deepcopy(state["ready_frontier_goal_ids"]),
        "blocked_goal_ids": copy.deepcopy(state["blocked_goal_ids"]),
        "pending_questions": copy.deepcopy(state["pending_questions"]),
        "open_question_count": state["open_question_count"],
        "artifact_work_queue_sha256": continuation.canonical_json_sha256(
            state["artifact_work_queue"]
        ),
        "completion_boundary_sha256": continuation.canonical_json_sha256(
            state["completion_boundary"]
        ),
        "activation_status": state["activation_status"],
        "package_status": state["package_status"],
    }


def _snapshot_hashes(digests: Mapping[Path, str]) -> tuple[str, str]:
    rows = sorted((path.as_posix(), digest) for path, digest in digests.items())
    path_hash = hashlib.sha256(
        ("\n".join(path for path, _ in rows) + "\n").encode("utf-8")
    ).hexdigest()
    content = hashlib.sha256()
    for path, digest in rows:
        require(len(digest) == 64, f"snapshot digest differs: {path}")
        content.update(path.encode("utf-8"))
        content.update(b"\0")
        content.update(digest.encode("ascii"))
        content.update(b"\n")
    return path_hash, content.hexdigest()


def _gap_snapshot(gap: Mapping[str, Any], backlog: Mapping[str, Any]) -> dict[str, Any]:
    snapshot = copy.deepcopy(gap.get("implementation_snapshot", {}))
    return {
        "report_id": gap["metadata"]["report_id"],
        "report_version": gap["metadata"]["version"],
        "assessment_count": len(gap["assessments"]),
        "status_counts": copy.deepcopy(gap["summary"]["status_counts"]),
        "backlog_id": backlog["metadata"]["backlog_id"],
        "epic_count": len(backlog["epics"]),
        "epic_status_counts": copy.deepcopy(
            source_epic_status_counts(backlog["epics"])
        ),
        "implementation_snapshot": snapshot,
    }


def source_epic_status_counts(epics: Sequence[Mapping[str, Any]]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for row in epics:
        status = row.get("current_status")
        require(isinstance(status, str), "R030 epic status differs")
        counts[status] = counts.get(status, 0) + 1
    return counts


def project_seq86_87(
    root: Path,
    source: Mapping[str, Any],
    evidence: CompletionEvidence,
    *,
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]], tuple[dict[str, Any], dict[str, Any]]
    ] = prior.derive_runtime,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    projected = copy.deepcopy(source)
    projected["canonical_bindings"] = _canonical_bindings(source, evidence)
    gap = strict_json(evidence.raw_by_path[GAP_JSON_REL], "R030 gap")
    backlog = strict_json(evidence.raw_by_path[BACKLOG_JSON_REL], "R030 backlog")
    projected["implementation_gap_snapshot"] = _gap_snapshot(gap, backlog)
    state = projected["goal_execution"]
    canonical_snapshot = continuation.canonical_binding_snapshot(projected)
    require(len(canonical_snapshot) == 44, "projected canonical role count differs")

    queue86, boundary86 = runtime_deriver(
        root, projected, [GOAL_ID, PARENT_GOAL_ID, EPIC04_GOAL_ID, EPIC12_GOAL_ID]
    )
    state["artifact_work_queue"] = queue86
    state["completion_boundary"] = boundary86
    completion_binding = copy.deepcopy(evidence.bindings_by_role[COMPLETION_ROLE])
    update = {
        "sequence": UPDATE_SEQUENCE,
        "event_id": UPDATE_EVENT_ID,
        "event_type": "CANONICAL_BINDINGS_UPDATED",
        "occurred_on": datetime.fromisoformat(evidence.update_occurred_at).date().isoformat(),
        "occurred_at": evidence.update_occurred_at,
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": GOAL_ID,
        "focus_goal_content_sha256": GOAL_SHA256,
        "from_status": "IN_PROGRESS",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": _runtime(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": list(CHANGED_ROLES),
        "previous_event_sha256": SOURCE_EVENT_SHA256,
        "produced_by_goal_id": GOAL_ID,
        "produced_binding_roles": list(PRODUCED_ROLES),
        "producer_completion_receipt_binding": completion_binding,
        "changed_binding_roles": list(CHANGED_ROLES),
        "changed_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-046", "FP-048"],
            "IMPLEMENTATION_GAP": ["FP-046", "FP-048", "GAP-055", "GAP-057"],
            COMPLETION_ROLE: [GOAL_ID],
        },
        "producer_output_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-046", "FP-048"],
            "IMPLEMENTATION_GAP": ["FP-046", "FP-048", "GAP-055", "GAP-057"],
        },
        "impact_closure_goal_ids": [PARENT_GOAL_ID, FP048_R001_GOAL_ID],
        "impact_disposition_by_goal": {
            PARENT_GOAL_ID: {
                "result": "REVALIDATION_REFRESH_REQUIRED",
                "target_status": "READY",
            },
            FP048_R001_GOAL_ID: {
                "result": "REOPEN_REQUIRED",
                "target_status": "SUPERSEDED",
            },
        },
        "reopened_completion_event_sha256_by_goal": {
            FP048_R001_GOAL_ID: FP048_R001_COMPLETION_EVENT_SHA256,
        },
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_snapshot),
        "transition_control_review_binding": copy.deepcopy(
            evidence.transition_review_binding
        ),
    }
    update["event_sha256"] = continuation.event_sha256(update)
    require(set(update) == UPDATE_FIELDS, "seq86 field set differs")
    state["transition_history"].append(update)
    state["pending_producer_completion_goal_id"] = GOAL_ID

    state["status_by_goal"][GOAL_ID] = "COMPLETE_AT_TARGET"
    state["focus_goal_id"] = PARENT_GOAL_ID
    state["focus_goal_path"] = PARENT_GOAL_PATH
    state["focus_work_item_id"] = ""
    state["focus_source"] = "WORKSTREAM_GRAPH"
    state["ready_frontier_goal_ids"] = [
        PARENT_GOAL_ID,
        EPIC04_GOAL_ID,
        EPIC12_GOAL_ID,
    ]
    queue87, boundary87 = runtime_deriver(
        root, projected, [PARENT_GOAL_ID, EPIC04_GOAL_ID, EPIC12_GOAL_ID]
    )
    state["artifact_work_queue"] = queue87
    state["completion_boundary"] = boundary87
    completed = copy.deepcopy(state["completion_evidence_by_goal"])
    completed[GOAL_ID] = [COMPLETION_ROLE]
    completion = {
        "sequence": COMPLETION_SEQUENCE,
        "event_id": COMPLETION_EVENT_ID,
        "event_type": "GOAL_COMPLETED",
        "occurred_on": datetime.fromisoformat(evidence.completion_occurred_at).date().isoformat(),
        "occurred_at": evidence.completion_occurred_at,
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": PARENT_GOAL_ID,
        "focus_goal_content_sha256": PARENT_GOAL_SHA256,
        "subject_goal_id": GOAL_ID,
        "from_status": "IN_PROGRESS",
        "to_status": "COMPLETE_AT_TARGET",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {GOAL_ID: "COMPLETE_AT_TARGET"},
        "runtime_after": _runtime(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [COMPLETION_ROLE],
        "previous_event_sha256": update["event_sha256"],
        "canonical_update_event_sha256": update["event_sha256"],
        "completion_receipt_binding": completion_binding,
        "completion_evidence_bindings": {COMPLETION_ROLE: completion_binding},
        "completion_evidence_by_goal_after": completed,
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_snapshot),
    }
    completion["event_sha256"] = continuation.event_sha256(completion)
    require(set(completion) == COMPLETION_FIELDS, "seq87 field set differs")
    state["transition_history"].append(completion)
    state["transition_history_anchor_sha256"] = completion["event_sha256"]
    state["validation_cutoff_at"] = evidence.completion_occurred_at
    state["completion_evidence_by_goal"] = completed
    state["pending_producer_completion_goal_id"] = ""

    current = projected["current_work"]
    current["work_item_id"] = ""
    current["status"] = "READY"
    current["title"] = "EPIC-03 workstream revalidation"
    current["last_completed_work_summary"] = (
        "FP-046 R002/GAP-055 privacy rate-limit and account-deletion ingress "
        "regressions repaired and verified inside the repository"
    )
    current["current_focus"] = (
        "EPIC-03 READY; FP-048 R002/GAP-057 is the planned successor pointer"
    )
    current["next_action"] = NEXT_ACTION
    current["release_completion_claimed"] = False
    handoff = projected["session_handoff"]
    handoff["current_epic"] = "EPIC-03 READY / FP-048 R002 PLANNED"
    handoff["next_single_action"] = NEXT_ACTION
    handoff["last_updated_by_work_item"] = GOAL_ID
    handoff["last_verification_status"] = (
        "PASS_INTERNAL_ONLY_FORMAL_EXTERNAL_DEVICE_DEPLOYMENT_RELEASE_NOT_RUN"
    )

    paths = sorted(path.as_posix() for path in evidence.final_sha256_by_path)
    path_hash, content_hash = _snapshot_hashes(evidence.final_sha256_by_path)
    working = projected["working_tree_snapshot"]
    working["managed_changed_paths"] = paths
    working["managed_changed_path_count"] = len(paths)
    working["path_set_sha256"] = path_hash
    working["content_set_sha256"] = content_hash
    working["scope"] = (
        "Graph v2.4 through FP046 R002 seq86 canonical update and seq87 completion; "
        "all live Git-visible non-gate paths are managed; formal, device, external, "
        "deployment and release credit remain zero."
    )
    mirror = handoff["source_commit_or_snapshot"]
    mirror["file_count"] = len(paths)
    mirror["path_set_sha256"] = path_hash
    mirror["content_set_sha256"] = content_hash
    handoff["changed_files"] = paths
    return projected, update, completion


def _changed_roles(
    source: Mapping[str, Any], projected: Mapping[str, Any]
) -> list[str]:
    before = continuation.canonical_binding_snapshot(source)
    after = continuation.canonical_binding_snapshot(projected)
    return sorted(
        role for role in set(before) | set(after) if before.get(role) != after.get(role)
    )


def validate_projection(
    source: Mapping[str, Any],
    projected: Mapping[str, Any],
    evidence: CompletionEvidence,
) -> None:
    source_state = source["goal_execution"]
    state = projected["goal_execution"]
    history = state.get("transition_history")
    require(isinstance(history, list) and len(history) == COMPLETION_SEQUENCE, "projection is not seq87")
    require(history[:SOURCE_SEQUENCE] == source_state["transition_history"], "seq1-85 history changed")
    update, completion = history[-2:]
    require(set(update) == UPDATE_FIELDS and set(completion) == COMPLETION_FIELDS, "seq86/87 fields differ")
    require(update["event_sha256"] == continuation.event_sha256(update), "seq86 seal differs")
    require(completion["event_sha256"] == continuation.event_sha256(completion), "seq87 seal differs")
    require(update["previous_event_sha256"] == SOURCE_EVENT_SHA256, "seq85/86 adjacency differs")
    require(
        completion["previous_event_sha256"]
        == completion["canonical_update_event_sha256"]
        == update["event_sha256"],
        "seq86/87 adjacency differs",
    )
    require(state["status_by_goal"].get(GOAL_ID) == "COMPLETE_AT_TARGET", "FP046 R002 final status differs")
    require(
        state["focus_goal_id"] == PARENT_GOAL_ID
        and state["ready_frontier_goal_ids"]
        == [PARENT_GOAL_ID, EPIC04_GOAL_ID, EPIC12_GOAL_ID],
        "final focus/frontier differs",
    )
    require(NEXT_GOAL_ID not in state["status_by_goal"], "FP048 R002 was activated early")
    require(
        state["status_by_goal"].get(FP048_R001_GOAL_ID) == "COMPLETE_AT_TARGET"
        and state["status_by_goal"].get("WS-GOAL-EPIC-03-FP-046-R001")
        == "SUPERSEDED"
        and state.get("archived_completion_evidence_by_goal")
        == source_state.get("archived_completion_evidence_by_goal"),
        "historical completion status/archive changed before seq88",
    )
    require(
        all(row.get("subject_goal_id") != NEXT_GOAL_ID for row in history),
        "FP048 R002 transition was published early",
    )
    require(state["completion_evidence_by_goal"].get(GOAL_ID) == [COMPLETION_ROLE], "completion role differs")
    require(state.get("pending_producer_completion_goal_id") in {None, ""}, "producer transaction remains open")
    require(_changed_roles(source, projected) == sorted(CHANGED_ROLES), "canonical role delta differs")
    require(
        update["impact_closure_goal_ids"]
        == [PARENT_GOAL_ID, FP048_R001_GOAL_ID]
        and update["impact_disposition_by_goal"][FP048_R001_GOAL_ID]
        == {"result": "REOPEN_REQUIRED", "target_status": "SUPERSEDED"}
        and update["reopened_completion_event_sha256_by_goal"]
        == {FP048_R001_GOAL_ID: FP048_R001_COMPLETION_EVENT_SHA256}
        and update["transition_control_review_binding"]
        == evidence.transition_review_binding,
        "FP048 reopen impact or transition review binding differs",
    )
    after = continuation.canonical_binding_snapshot(projected)
    require(
        update["canonical_binding_snapshot_after"]
        == completion["canonical_binding_snapshot_after"]
        == after,
        "canonical snapshots differ",
    )
    for role in CHANGED_ROLES:
        require(after[role] == evidence.bindings_by_role[role], f"canonical binding differs: {role}")
    for field in ("approved_state", "authority_boundary", "verification_boundary"):
        require(projected.get(field) == source.get(field), f"{field} changed")
    require(
        projected["approved_state"]["release_status"] == "NOT_ELIGIBLE"
        and projected["verification_boundary"]["formal_test_pass_claimed"] is False
        and projected["verification_boundary"]["release_eligible"] is False,
        "formal/release credit changed",
    )
    receipt = strict_json(evidence.raw_by_path[COMPLETION_REL], "completion receipt")
    require(receipt["completion_boundary"] == ZERO_CREDIT_BOUNDARY, "receipt credit boundary differs")
    successor = strict_json(evidence.raw_by_path[SUCCESSOR_REL], "successor trace")
    require(
        successor["successor_goal_id"] == NEXT_GOAL_ID
        and successor["successor_work_item_id"] == NEXT_WORK_ITEM_ID
        and successor["successor_gap_id"] == NEXT_GAP_ID
        and successor["status"] == "PLANNED_NOT_ACTIVATED",
        "FP048 successor pointer differs",
    )
    source_paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    final_paths = projected["working_tree_snapshot"]["managed_changed_paths"]
    require(
        isinstance(final_paths, list)
        and final_paths == sorted(set(final_paths))
        and source_paths.issubset(final_paths)
        and {path.as_posix() for path in ADDED_MANAGED_PATHS}.issubset(final_paths)
        and len(final_paths) == len(evidence.final_sha256_by_path)
        and projected["working_tree_snapshot"]["managed_changed_path_count"] == len(final_paths),
        "successor managed path closure differs",
    )
    expected_hashes = _snapshot_hashes(evidence.final_sha256_by_path)
    require(
        (
            projected["working_tree_snapshot"]["path_set_sha256"],
            projected["working_tree_snapshot"]["content_set_sha256"],
        )
        == expected_hashes,
        "projected working snapshot hashes differ",
    )
    allowed_top = {
        "canonical_bindings",
        "current_work",
        "goal_execution",
        "implementation_gap_snapshot",
        "session_handoff",
        "working_tree_snapshot",
    }
    for key in set(source) | set(projected):
        if key not in allowed_top:
            require(source.get(key) == projected.get(key), f"unauthorized top-level mutation: {key}")


def validate_checkpoint_serialization(
    source_raw: bytes,
    source: Mapping[str, Any],
    projected: Mapping[str, Any],
    projected_raw: bytes,
) -> None:
    require(
        checkpoint_bytes(source) == source_raw,
        "source checkpoint insertion-order serialization differs",
    )
    require(
        projected_raw == checkpoint_bytes(projected),
        "projected checkpoint serializer differs",
    )
    require(
        projected_raw != json_bytes(projected),
        "sorted-key evidence serializer was used for the checkpoint",
    )
    source_history = source["goal_execution"]["transition_history"]
    projected_history = projected["goal_execution"]["transition_history"]
    require(
        len(source_history) == SOURCE_SEQUENCE
        and len(projected_history) >= SOURCE_SEQUENCE,
        "checkpoint history prefix length differs",
    )
    for index, (before, after) in enumerate(
        zip(source_history, projected_history[:SOURCE_SEQUENCE]),
        start=1,
    ):
        require(
            list(before) == list(after)
            and _ordered_json_fragment(before) == _ordered_json_fragment(after),
            f"checkpoint historical event ordered fragment differs: seq{index}",
        )


def prepare_projection(
    root: Path = ROOT,
    *,
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]], tuple[dict[str, Any], dict[str, Any]]
    ] = prior.derive_runtime,
) -> PreparedProjection:
    root = Path(root).resolve(strict=True)
    checkpoint = root / CHECKPOINT_REL
    source_bytes = safe_regular_bytes(root, CHECKPOINT_REL)
    source = strict_json(source_bytes, CHECKPOINT_REL.as_posix())
    require_exact_source(source_bytes, source)
    evidence = build_evidence(root, source)
    projected, update, completion = project_seq86_87(
        root, source, evidence, runtime_deriver=runtime_deriver
    )
    validate_projection(source, projected, evidence)
    projected_bytes = checkpoint_bytes(projected)
    validate_checkpoint_serialization(
        source_bytes,
        source,
        projected,
        projected_bytes,
    )
    require(checkpoint.read_bytes() == source_bytes, "checkpoint changed during preflight")
    return PreparedProjection(
        root=root,
        source_bytes=source_bytes,
        source=source,
        projected=projected,
        projected_bytes=projected_bytes,
        evidence=evidence,
        update_event=update,
        completion_event=completion,
    )


def _run_continuation_checker(root: Path) -> list[str]:
    return continuation.validate(root, CHECKPOINT_REL)


def _checkpoint_for_suffix_checker(root: Path) -> dict[str, Any]:
    return strict_json(
        safe_regular_bytes(root, CHECKPOINT_REL),
        CHECKPOINT_REL.as_posix(),
    )


def _run_continuation_suffix_checker(root: Path) -> list[str]:
    return continuation.validate_fp046_r002_completion_seq86_87(
        _checkpoint_for_suffix_checker(root)
    )


def _run_goal_suffix_checker(root: Path) -> list[str]:
    goal_graph = importlib.import_module("scripts.check_walksafe_goal_graph_v2_4")
    return goal_graph.validate_fp046_r002_completion_seq86_87(
        root,
        _checkpoint_for_suffix_checker(root),
    )


def _run_full_goal_checker(root: Path) -> list[str]:
    goal_graph = importlib.import_module("scripts.check_walksafe_goal_graph_v2_4")
    return goal_graph.validate(
        root,
        CHECKPOINT_REL,
        check_continuation=False,
    )


def _phase_aware_checkpoint_guard(
    checkpoint: Path,
    source_bytes: bytes,
    projected_bytes: bytes,
    *,
    validate_source: Callable[[], None],
    validate_projected: Callable[[], None],
) -> str:
    current = checkpoint.read_bytes()
    if current == source_bytes:
        validate_source()
        return "SOURCE"
    if current == projected_bytes:
        validate_projected()
        return "PROJECTED"
    raise CompletionApplyError("checkpoint changed at commit")


def write_projection(
    prepared: PreparedProjection,
    *,
    atomic_writer: Callable[..., None] = prior.atomic_write,
    continuation_checker: Callable[[Path], Sequence[str]] = _run_continuation_checker,
    continuation_suffix_checker: Callable[
        [Path], Sequence[str]
    ] = _run_continuation_suffix_checker,
    goal_suffix_checker: Callable[[Path], Sequence[str]] = _run_goal_suffix_checker,
    full_goal_checker: Callable[[Path], Sequence[str]] = _run_full_goal_checker,
    fresh_gate_runner: Callable[
        [Path], list[dict[str, Any]]
    ] = _run_fresh_recovery_gates,
    historical_validator: Callable[[Path], None] = _validate_failed_attempt_history,
    source_checkpoint_validator: Callable[
        [bytes, Mapping[str, Any]], None
    ] = require_exact_source,
) -> None:
    checkpoint = prepared.root / CHECKPOINT_REL
    validate_checkpoint_serialization(
        prepared.source_bytes,
        prepared.source,
        prepared.projected,
        prepared.projected_bytes,
    )
    require(
        checkpoint.read_bytes() == prepared.source_bytes,
        "checkpoint changed before publication",
    )
    baseline_full_goal_errors = list(full_goal_checker(prepared.root))
    reviewed_goal_baseline = strict_json(
        prepared.evidence.raw_by_path[REVIEW_SUBJECT_REL],
        "R005 third recovery review subject",
    ).get("validation_gate_boundary", {}).get("full_goal_graph_baseline")
    require(
        reviewed_goal_baseline
        == _full_goal_graph_baseline_from_errors(baseline_full_goal_errors),
        "full Goal baseline differs from reviewed non-gate boundary",
    )
    for relative in PREPARED_REVIEW_PATHS + DISTINCT_REVIEW_INPUT_PATHS:
        require(
            _require_exact_or_absent_0600(
                prepared.root,
                relative,
                prepared.evidence.raw_by_path[relative],
                label="R005 review input",
            ),
            f"R005 review input is absent: {relative}",
        )
    for relative in PUBLICATION_OUTPUT_PATHS:
        _write_add_only_exact(
            prepared.root, relative, prepared.evidence.raw_by_path[relative]
        )

    managed = sorted(
        path.as_posix() for path in prepared.evidence.final_sha256_by_path
    )
    expected_hashes = _snapshot_hashes(prepared.evidence.final_sha256_by_path)
    completion_receipt = strict_json(
        prepared.evidence.raw_by_path[COMPLETION_REL],
        "R005 completion receipt",
    )
    expected_fresh_gate_results = completion_receipt.get(
        "fresh_recovery_gate_results"
    )

    def validate_evidence_and_snapshot() -> None:
        for relative, raw in prepared.evidence.raw_by_path.items():
            require(
                _require_exact_or_absent_0600(
                    prepared.root,
                    relative,
                    raw,
                    label="commit evidence",
                ),
                f"evidence is absent at commit: {relative}",
            )
        historical_validator(prepared.root)
        require(
            fresh_gate_runner(prepared.root) == expected_fresh_gate_results,
            "fresh R005 commit gate result differs",
        )
        live = snapshot_authority._git_visible_managed_paths(prepared.root)
        require(
            live.issubset(prepared.evidence.final_sha256_by_path),
            "new Git-visible path appeared after preflight",
        )
        require(
            continuation.working_snapshot_hashes(prepared.root, managed)
            == expected_hashes,
            "managed path/content set changed at commit",
        )

    def validate_source() -> None:
        validate_evidence_and_snapshot()
        source_checkpoint_validator(prepared.source_bytes, prepared.source)

    def validate_projected() -> None:
        validate_evidence_and_snapshot()
        continuation_errors = list(continuation_checker(prepared.root))
        require(
            not continuation_errors,
            "projected continuation checker failed: "
            + " | ".join(continuation_errors),
        )
        continuation_suffix_errors = list(
            continuation_suffix_checker(prepared.root)
        )
        require(
            not continuation_suffix_errors,
            "projected continuation exact suffix checker failed: "
            + " | ".join(continuation_suffix_errors),
        )
        goal_suffix_errors = list(goal_suffix_checker(prepared.root))
        require(
            not goal_suffix_errors,
            "projected Goal exact suffix checker failed: "
            + " | ".join(goal_suffix_errors),
        )
        projected_full_goal_errors = list(full_goal_checker(prepared.root))
        new_full_goal_errors = list(
            (Counter(projected_full_goal_errors) - Counter(baseline_full_goal_errors)).elements()
        )
        require(
            not new_full_goal_errors,
            "projected full Goal checker introduced new non-gate errors: "
            + " | ".join(new_full_goal_errors),
        )

    def commit_guard() -> None:
        _phase_aware_checkpoint_guard(
            checkpoint,
            prepared.source_bytes,
            prepared.projected_bytes,
            validate_source=validate_source,
            validate_projected=validate_projected,
        )

    atomic_writer(
        checkpoint,
        prepared.projected_bytes,
        expected_source=prepared.source_bytes,
        commit_guard=commit_guard,
    )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--prepare-review-r005",
        "--prepare-review",
        dest="prepare_review_r005",
        action="store_true",
    )
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.prepare_review_r005:
            outputs = prepare_review_inputs_r005(args.root)
            print(
                "FP-046 R002 seq86-87 third recovery review preparation R005: PASS "
                + " ".join(
                    f"{path.as_posix()}={sha256_bytes(raw)}"
                    for path, raw in outputs.items()
                )
            )
            return 0
        prepared = prepare_projection(args.root)
        if args.write:
            write_projection(prepared)
            mode = "WRITE"
        else:
            mode = "PREFLIGHT"
    except (CompletionApplyError, KeyError, OSError, subprocess.SubprocessError, TypeError, ValueError) as exc:
        print(f"FP-046 R002 GOAL_COMPLETED seq86-87: FAIL: {exc}")
        return 1
    print(
        "FP-046 R002 GOAL_COMPLETED seq86-87: PASS "
        f"mode={mode} managed_count={len(prepared.evidence.final_sha256_by_path)} "
        f"update_sha256={prepared.update_event['event_sha256']} "
        f"completion_sha256={prepared.completion_event['event_sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
