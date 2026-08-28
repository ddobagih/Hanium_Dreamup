#!/usr/bin/env python3
"""Review, preflight, or publish the zero-credit FP-048 R002 seq90 reanchor.

The reanchor preserves the exact seq89 READY transition and replaces only the
private start-control authority.  ``--prepare-review`` and ``--preflight`` are
read-only.  ``--write`` is an explicit, CAS-protected checkpoint publication
mode and is intentionally separate from review preparation.
"""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import check_walksafe_project_continuation_v2_4 as continuation


CHECKPOINT_REL = Path("docs/control/walksafe-project-continuation-checkpoint.json")
SOURCE_SEQUENCE = 89
SOURCE_CHECKPOINT_SHA256 = (
    "c2ce759ff1b469d31dba266a47a91ca1e835869c011b6bed067fe6963c578e3d"
)
SOURCE_CHECKPOINT_BYTE_LENGTH = 2_600_767
SOURCE_TAIL_SHA256 = (
    "bb2594af4342c156df68a1b10b6c806ae91b4694edec864bb16f56236c8c53b0"
)
SOURCE_READY_OCCURRED_AT = "2026-08-25T03:08:34+09:00"
SOURCE_PATH_SET_SHA256 = (
    "b8cb56caf72a361cb9bf699bb407267c36d42f8a826de1bc33afe12414824a38"
)
SOURCE_CONTENT_SET_SHA256 = (
    "8da205e529841badd2dd34d0ecc936aa222a5225b00a43daf8cf4656a791172b"
)
SOURCE_MANAGED_PATH_COUNT = 1_085

CONTROL_REANCHOR_SEQUENCE = 90
CONTROL_REANCHOR_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP048-R002-20260826-001"
)
STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-001"
)
GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R002"
GOAL_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-fp048-encryption-connection-security-incident-r002.md"
)
GOAL_SHA256 = (
    "c7632800335cd9f816b382d91ea0420a53741ae7648c04c5e9c36c3e9fc42014"
)
WORK_ITEM_ID = "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT"
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
PREDECESSOR_GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R002"
READY_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP048-R002-20260825-001"
READY_FRONTIER = (
    GOAL_ID,
    PARENT_GOAL_ID,
    "WS-GOAL-EPIC-04",
    "WS-GOAL-EPIC-12",
)
MANIFEST_SHA256 = (
    "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
)

R001_CONTRACT_PATH = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r001.json"
)
R002_CONTRACT_PATH = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r002.json"
)
R002_DOCUMENT_ID = "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-002"
R002_CONTRACT_ID = "WS-FP048-R002-INTERNAL-START-GATE-R002"
R002_CONTRACT_VERSION = "2026-08-26.1"
R002_FILE_SHA256 = (
    "874a016a8f19b6238ea04cd17c73e06d7086cee0a4d013a66358893d59875211"
)
R002_CANONICAL_SHA256 = (
    "1664b922e6dbc0d7a07896b81de79cbcadbd548ce5de8646a21051fe72dce582"
)
R002_BYTE_LENGTH = 5_833
R002_CONTRACT_CHECK_IDS = (
    "CONTINUATION",
    "GOAL_GRAPH",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "ROOT_FP048_R002_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)
LOCKED_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)
R002_CONTRACT_COMMANDS = {
    "CONTINUATION": (
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {LOCKED_PYTHON} -B "
        "scripts/check_walksafe_project_continuation_v2_4.py --root . "
        "--checkpoint docs/control/walksafe-project-continuation-checkpoint.json"
    ),
    "GOAL_GRAPH": (
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {LOCKED_PYTHON} -B "
        "scripts/check_walksafe_goal_graph_v2_4.py --root . --checkpoint "
        "docs/control/walksafe-project-continuation-checkpoint.json"
    ),
    "TEST_LAYER_REGISTRY_VALIDATE": (
        f"PYTHON_BIN={LOCKED_PYTHON} "
        "bash scripts/run_walksafe_test_layers_current.sh validate"
    ),
    "ROOT_FP048_R002_CONTROL_REGRESSION": (
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {LOCKED_PYTHON} -B "
        "-m pytest -p no:cacheprovider -q "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_r002_contract_is_exact_five_check_successor "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_review_drift_is_rejected "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_assignment_scope_drift_is_rejected_after_"
        "consistent_rebinding "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_same_byte_aba_replacement_is_rejected "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_git_visible_rename_is_rejected "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_post_consumer_reseal_rejects_same_byte_input_"
        "replacement "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_active_work_uses_frozen_seq90_edge_only_after_"
        "exact_seq91_anchor "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_live_seq90_reanchor_checkpoint_is_valid "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_write_uses_checkpoint_inode_lock_without_"
        "git_visible_lock "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_write_rejects_managed_byte_drift_with_"
        "unchanged_git_status "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_write_rejects_excluded_gate_byte_drift_with_"
        "unchanged_git_status "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_write_rejects_retained_same_byte_aba_at_"
        "commit_point "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_write_rejects_head_or_branch_drift "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_write_reports_postcommit_uncertain_on_unlock_"
        "failure "
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
        "seq90_20260826.py::test_write_reports_postcommit_uncertain_when_"
        "replace_returns_by_exception "
        "tests/test_walksafe_fp048_r002_goal_start_gate_r002_20260826.py "
        "tests/test_apply_walksafe_fp048_r002_goal_started_seq91_20260826.py "
        "tests/test_walksafe_project_continuation_v2_4.py::"
        "WalkSafeFp048R002Seq90Seq91BoundaryTest "
        "tests/test_walksafe_goal_graph_v2_4.py::"
        "Fp048R002ReviewedNoncreditSuccessorTests"
    ),
    "REPOSITORY_STATE": (
        ': "${WALKSAFE_GATE_EVENT_ID:?required}" && '
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {LOCKED_PYTHON} -B "
        "scripts/check_walksafe_project_continuation_v2_4.py --root . "
        "--checkpoint docs/control/walksafe-project-continuation-checkpoint.json "
        '--print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"'
    ),
}

AUTHORIZATION_REL = Path(
    "docs/control/execution/workstream-transitions/seq90-91/authorization.json"
)
R001_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq90-91/review-rounds/R001"
)
R001_REVIEW_ASSIGNMENT_REL = R001_REVIEW_ROOT / "review-assignment.json"
R002_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq90-91/review-rounds/R002"
)
R002_REVIEW_ASSIGNMENT_REL = R002_REVIEW_ROOT / "review-assignment.json"
R003_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq90-91/review-rounds/R003"
)
R003_REVIEW_ASSIGNMENT_REL = R003_REVIEW_ROOT / "review-assignment.json"
R004_REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq90-91/review-rounds/R004"
)
R004_REVIEW_ASSIGNMENT_REL = R004_REVIEW_ROOT / "review-assignment.json"
REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq90-91/review-rounds/R005"
)
REVIEW_ASSIGNMENT_REL = REVIEW_ROOT / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_ROOT / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_ROOT / "independent-review.json"
REVIEW_PATHS = (
    REVIEW_ASSIGNMENT_REL,
    REVIEW_RESULT_REL,
    INDEPENDENT_REVIEW_REL,
)
ROUND_ID = "WS-FP048-R002-SEQ90-91-REVIEW-20260826-R005"
REVIEWER_ID = "codex-fp048-r002-seq90-independent-reviewer-20260826"
REVIEWER_TASK = (
    "/root/fp048_seq90_reanchor_impl/seq90_independent_review_r005"
)

SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_start_control_reanchor_"
    "seq90_20260826.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_"
    "seq90_20260826.py"
)
GATE_SCRIPT_REL = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r002_20260826.py"
)
GATE_TEST_REL = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r002_20260826.py"
)
STARTED_SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq91_20260826.py"
)
STARTED_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq91_20260826.py"
)
CONTINUATION_SCRIPT_REL = Path("scripts/check_walksafe_project_continuation_v2_4.py")
CONTINUATION_TEST_REL = Path("tests/test_walksafe_project_continuation_v2_4.py")
GOAL_GRAPH_SCRIPT_REL = Path("scripts/check_walksafe_goal_graph_v2_4.py")
GOAL_GRAPH_TEST_REL = Path("tests/test_walksafe_goal_graph_v2_4.py")
REVIEWED_CONTROL_PATHS = (
    R002_CONTRACT_PATH,
    SCRIPT_REL,
    TEST_REL,
    GATE_SCRIPT_REL,
    GATE_TEST_REL,
    STARTED_SCRIPT_REL,
    STARTED_TEST_REL,
    CONTINUATION_SCRIPT_REL,
    CONTINUATION_TEST_REL,
    GOAL_GRAPH_SCRIPT_REL,
    GOAL_GRAPH_TEST_REL,
)

# These are the exact minimum successor roots needed to reconcile the reviewed
# seq85/seq87 authority with the seq89 managed cohort.  The reanchor records
# them as zero-credit inputs; it does not authorize or complete their work.
NONCREDIT_PRODUCT_PATHS = (
    Path("apps/android-gateway/openapi.json"),
    Path("apps/android-gateway/src/routes.ts"),
    Path("apps/android-gateway/src/state-encryption-maintenance.ts"),
    Path("apps/android-gateway/test/field-long-session.test.ts"),
    Path("apps/android-gateway/test/gateway-contract.test.ts"),
    Path("apps/android-gateway/test/node-adapter.test.ts"),
    Path("apps/android-gateway/test/state-encryption-maintenance.test.ts"),
    Path(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivity.kt"
    ),
    Path(
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
        "GatewayFieldSession.kt"
    ),
    Path(
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/"
        "GatewayFieldSessionTest.kt"
    ),
    Path("backend/.env.example"),
    Path("backend/app/api/health.py"),
    Path("backend/app/api/reports.py"),
    Path("backend/app/config.py"),
    Path("backend/app/field_test_security.py"),
    Path("backend/app/main.py"),
    Path("backend/app/services/privacy_lifecycle.py"),
    Path("backend/tests/test_admin_device_proof.py"),
    Path("backend/tests/test_admin_runtime_acl_hardening.py"),
    Path("backend/tests/test_admin_security.py"),
    Path("backend/tests/test_field_test_security.py"),
    Path("backend/tests/test_fp046_postgres_integration.py"),
    Path("backend/tests/test_health_readiness.py"),
    Path("backend/tests/test_reports.py"),
    Path("contracts/walksafe.openapi.json"),
    Path("deploy/README.md"),
    Path("deploy/config/walksafe-backend.env.example"),
    Path("scripts/backup_walksafe_data_20260711.sh"),
    Path("scripts/restore_walksafe_backup_drill_20260711.sh"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    Path("scripts/walksafe_backup_integrity.py"),
    Path("tests/test_account_deletion_worker_operations.py"),
    Path("tests/test_report_retention_operational_safety.py"),
    Path("tests/test_walksafe_backup_integrity.py"),
    Path("tests/test_walksafe_backup_operations.py"),
)
NONCREDIT_ADDED_PATHS = frozenset(
    {
        "apps/android-gateway/test/state-encryption-maintenance.test.ts",
        "tests/test_account_deletion_worker_operations.py",
        "tests/test_walksafe_backup_operations.py",
    }
)
SOURCE_PRODUCT_MEMBERSHIP = {
    relative.as_posix(): relative.as_posix() not in NONCREDIT_ADDED_PATHS
    for relative in NONCREDIT_PRODUCT_PATHS
}
SOURCE_PRODUCT_PREDECESSORS = {
    "apps/android-gateway/openapi.json": {
        "path": "apps/android-gateway/openapi.json",
        "sha256": "be9f0758f7ae80bd7e1532b0e33cafedf157f9c213628bd61b4324da84f06ae8",
        "byte_length": 64_835,
    },
    "apps/android-gateway/src/routes.ts": {
        "path": "apps/android-gateway/src/routes.ts",
        "sha256": "804705498f65983300782fa4549b11f700ca0a964ede32a90f620a66140bc407",
        "byte_length": 38_187,
    },
    "apps/android-gateway/src/state-encryption-maintenance.ts": {
        "path": "apps/android-gateway/src/state-encryption-maintenance.ts",
        "sha256": "bcb80213d814ef348730ddd7115a54b873b70bdd5d6754bf55430e903ea4c68f",
        "byte_length": 16_946,
    },
    "apps/android-gateway/test/field-long-session.test.ts": {
        "path": "apps/android-gateway/test/field-long-session.test.ts",
        "sha256": "4e3c0bd978c41595e64323c9e1b92a0ef3ffef38cdf95c973b18c170e6b7cde2",
        "byte_length": 31_594,
    },
    "apps/android-gateway/test/gateway-contract.test.ts": {
        "path": "apps/android-gateway/test/gateway-contract.test.ts",
        "sha256": "b6e141da1a50fede21890dadcc6899646d92c044061975582c856338f6907fbe",
        "byte_length": 47_958,
    },
    "apps/android-gateway/test/node-adapter.test.ts": {
        "path": "apps/android-gateway/test/node-adapter.test.ts",
        "sha256": "99d5e52d3d313d21e8a191700cd3cef67c000b401dd8f60ebcaa437ea757bccb",
        "byte_length": 5_656,
    },
    (
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivity.kt"
    ): {
        "path": (
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
            "MainActivity.kt"
        ),
        "sha256": "7cf49ad23b268ebd52809055879b734d81e7cf7215c44c94f9196b20d5b4e865",
        "byte_length": 929_714,
    },
    (
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
        "GatewayFieldSession.kt"
    ): {
        "path": (
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
            "GatewayFieldSession.kt"
        ),
        "sha256": "da4188391201d2bc41ffe38dee98aef8939eba8068b522b2d14b02d3073b0fb3",
        "byte_length": 43_189,
    },
    (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/"
        "GatewayFieldSessionTest.kt"
    ): {
        "path": (
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/"
            "GatewayFieldSessionTest.kt"
        ),
        "sha256": "a9b6c144d225d05fa2f80c70f0b455088e284a301a81d78666e294af448affa5",
        "byte_length": 41_210,
    },
    "backend/.env.example": {
        "path": "backend/.env.example",
        "sha256": "70b43d743418672fcc47de0e60bdf9237389b89eb736e8d50cf467b6006e6c19",
        "byte_length": 5_615,
    },
    "backend/app/api/health.py": {
        "path": "backend/app/api/health.py",
        "sha256": "ab477d4ea0a9ec2ce68498950eb058121fe66c8a26d28d087a335bbae4a4b9f2",
        "byte_length": 19_738,
    },
    "backend/app/api/reports.py": {
        "path": "backend/app/api/reports.py",
        "sha256": "ec7d2fec00676fd54ee2278ba8b77dbc393e3d13cc68df9c060374445831b922",
        "byte_length": 99_900,
    },
    "backend/app/config.py": {
        "path": "backend/app/config.py",
        "sha256": "0101b5b7d1ab9c06590a270b464bb6ac48831bfa3b5cc84ceab587caab91106d",
        "byte_length": 44_292,
    },
    "backend/app/field_test_security.py": {
        "path": "backend/app/field_test_security.py",
        "sha256": "4f181ccc655ead15d3a58ff4b5cd88b299dbe39adb6572290ac9a7b562fd16e1",
        "byte_length": 48_282,
    },
    "backend/app/main.py": {
        "path": "backend/app/main.py",
        "sha256": "16799466412e8962c9df17c96fc8dc782d9a1b63e7208d219fb6baf3c29db77c",
        "byte_length": 6_269,
    },
    "backend/app/services/privacy_lifecycle.py": {
        "path": "backend/app/services/privacy_lifecycle.py",
        "sha256": "8043cf5c548b19d5258ba2abbf55dd33a37481acdc37d7f9156835026500ca32",
        "byte_length": 61_567,
    },
    "backend/tests/test_admin_device_proof.py": {
        "path": "backend/tests/test_admin_device_proof.py",
        "sha256": "0276194d37cefa955cca09933ec2fe7f236f6d67c5199711228adc06d6d499cb",
        "byte_length": 87_799,
    },
    "backend/tests/test_admin_runtime_acl_hardening.py": {
        "path": "backend/tests/test_admin_runtime_acl_hardening.py",
        "sha256": "4ba8105bb51838bcc148e669adb87fce9abdef5064430e5ea31e567397427977",
        "byte_length": 51_596,
    },
    "backend/tests/test_admin_security.py": {
        "path": "backend/tests/test_admin_security.py",
        "sha256": "97c08b17800d76c720a0fa615fee9025e2ad57c5278e01b2d04c901dc49f9acd",
        "byte_length": 189_546,
    },
    "backend/tests/test_field_test_security.py": {
        "path": "backend/tests/test_field_test_security.py",
        "sha256": "934f874f896430c4a76b9d4978e2fd39a1abdb177bf0df25c53f549d9f82a1b6",
        "byte_length": 48_389,
    },
    "backend/tests/test_fp046_postgres_integration.py": {
        "path": "backend/tests/test_fp046_postgres_integration.py",
        "sha256": "0685e211c488d7cacf2ade79c3904aaa032827c6bf30799bd40efe46be83dda3",
        "byte_length": 113_346,
    },
    "backend/tests/test_health_readiness.py": {
        "path": "backend/tests/test_health_readiness.py",
        "sha256": "510a28701666a25bc6ade6e2b2adbbdfa1360b7315f9724ebca5f310394db84a",
        "byte_length": 25_338,
    },
    "backend/tests/test_reports.py": {
        "path": "backend/tests/test_reports.py",
        "sha256": "7ff10bb428fcfd5977b7c51076488c6bd6eec3152f2255f297a5b47c57e98a83",
        "byte_length": 22_330,
    },
    "contracts/walksafe.openapi.json": {
        "path": "contracts/walksafe.openapi.json",
        "sha256": "7b6618ed7805c8cc0d25de78b40e7b9ec15da4a7aa4c367133aa39a030aa606c",
        "byte_length": 215_129,
    },
    "deploy/README.md": {
        "path": "deploy/README.md",
        "sha256": "670bcfe87f15020e86104b40e9877905a14d3735f1150f63a465b133ecd6c09f",
        "byte_length": 3_303,
    },
    "deploy/config/walksafe-backend.env.example": {
        "path": "deploy/config/walksafe-backend.env.example",
        "sha256": "bc9e34f91402b8b2e71d759294b0f56f54ce701e25402221ca3d61586bbb9618",
        "byte_length": 4_470,
    },
    "scripts/backup_walksafe_data_20260711.sh": {
        "path": "scripts/backup_walksafe_data_20260711.sh",
        "sha256": "e7f7b23667c3c5d461b4ee2fb91761c5bf451d33e20a062f3ea087fda56d5452",
        "byte_length": 37_926,
    },
    "scripts/restore_walksafe_backup_drill_20260711.sh": {
        "path": "scripts/restore_walksafe_backup_drill_20260711.sh",
        "sha256": "6e3f9ff83d9e73e8330285940fb1cf973ce96b45ed11806430d3dfa46e61c1db",
        "byte_length": 59_894,
    },
    "scripts/run_walksafe_test_layers_current.sh": {
        "path": "scripts/run_walksafe_test_layers_current.sh",
        "sha256": "5bceabcbee1b91667e5d9bfef5e43e2c92f248f5c7c7c77baa56fd3a0d118802",
        "byte_length": 25_755,
    },
    "scripts/walksafe_backup_integrity.py": {
        "path": "scripts/walksafe_backup_integrity.py",
        "sha256": "54370dcf6df5ce1efdcc813d2300e6cf6ecb55889c5efdb8a67b0abc04b0188a",
        "byte_length": 167_511,
    },
    "tests/test_report_retention_operational_safety.py": {
        "path": "tests/test_report_retention_operational_safety.py",
        "sha256": "83def3826dbf3701029f2c1c66f9c4292aee50a809220b71556d6309cd8a7369",
        "byte_length": 18_873,
    },
    "tests/test_walksafe_backup_integrity.py": {
        "path": "tests/test_walksafe_backup_integrity.py",
        "sha256": "3d0aefc9cc315ae2d316ca5c78c37e0cbc38dc8a88a853299ebad26e4bb7f730",
        "byte_length": 92_321,
    },
}
SEQ87_REVIEWED_CREDIT_PATHS = frozenset(
    {
        "backend/alembic/versions/202608250001_actor_rate_limit_privacy_group.py",
        "backend/tests/test_actor_rate_limit_store.py",
        "configs/walksafe_product_boundary_20260722.json",
        "deploy/nginx/walksafe-android-gateway.conf.example",
        "tests/test_walksafe_android_gateway_ingress_current.py",
        "tests/test_walksafe_android_product_boundary.py",
    }
)
SEQ87_REVIEWED_NONCREDIT_PATHS = frozenset(
    {
        "backend/alembic/versions/202608250002_account_deletion_worker_role.py",
        "backend/app/api/health.py",
        "backend/tests/test_fp046_postgres_integration.py",
        "docs/catalogs/repository-paths.json",
        "docs/catalogs/scripts.json",
        "docs/catalogs/tests.json",
        "scripts/account_deletion_worker.py",
        "scripts/generate_repository_catalogs.py",
        "scripts/run_walksafe_test_layers_current.sh",
    }
)
SEQ87_REVIEWED_ADDED_PATHS = frozenset(
    {
        "backend/alembic/versions/202608250001_actor_rate_limit_privacy_group.py",
        "backend/alembic/versions/202608250002_account_deletion_worker_role.py",
        "scripts/account_deletion_worker.py",
        "tests/test_walksafe_android_gateway_ingress_current.py",
    }
)
SEQ85_TO_SEQ87_EDGE_SET_SHA256 = (
    "2a5abff7617a7afa6a10e531690a09abe8238ff176ae08973731a27a2ef50022"
)
SEQ85_GATE_RECEIPT_REL = Path(
    "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-005/"
    "implementation-start-gate-receipt.json"
)
SEQ85_GATE_STATE_REL = SEQ85_GATE_RECEIPT_REL.parent / "05-REPOSITORY_STATE.log"
SEQ87_REVIEW_SUBJECT_REL = Path(
    "docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-046-R002/"
    "review-rounds/R005/review-subject.json"
)
SEQ87_COMPLETION_RECEIPT_REL = Path(
    "docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-046-R002/"
    "completion-receipt-r005.json"
)
SEQ85_GATE_RECEIPT_SHA256 = (
    "3bd3ba0bb29d85008f6f5697d807bce2731235362167e69e295e42dd20bff6f1"
)
SEQ85_GATE_RECEIPT_BYTE_LENGTH = 7_918
SEQ87_COMPLETION_RECEIPT_SHA256 = (
    "1298a60b3fc484280c41bd1409d9d503b4fc7581fc0fe59109af7d757584d409"
)
SEQ87_COMPLETION_RECEIPT_BYTE_LENGTH = 8_263
REVIEWED_SUCCESSOR_AUTHORITY_PATHS = (
    SEQ85_GATE_RECEIPT_REL,
    SEQ85_GATE_STATE_REL,
    SEQ87_COMPLETION_RECEIPT_REL,
    SEQ87_REVIEW_SUBJECT_REL,
)
REVIEW_INPUT_PATHS = (
    *REVIEWED_CONTROL_PATHS,
    *REVIEWED_SUCCESSOR_AUTHORITY_PATHS,
)

CLAIM_BOUNDARY = {
    "product_implementation_credit_delta": 0,
    "artifact_completion_credit_delta": 0,
    "test_credit_delta": 0,
    "formal_test_credit_delta": 0,
    "approval_credit_delta": 0,
    "actual_event_credit_delta": 0,
    "external_action_credit_delta": 0,
    "actual_device_credit_delta": 0,
    "deployment_credit_delta": 0,
    "signing_credit_delta": 0,
    "release_credit_delta": 0,
    "final_completion_credit_delta": 0,
    "formal_test_not_run_count": 279,
    "remaining_gate_count": 5,
    "remaining_gates_waived": False,
    "release_status": "NOT_ELIGIBLE",
    "goal_status_change_count": 0,
    "implementation_start_authorized": False,
}

EVENT_FIELDS = frozenset(
    {
        "sequence",
        "event_id",
        "event_type",
        "occurred_on",
        "occurred_at",
        "previous_focus_goal_id",
        "previous_focus_content_sha256",
        "focus_goal_id",
        "focus_goal_content_sha256",
        "subject_goal_id",
        "from_status",
        "to_status",
        "static_plan_manifest_sha256",
        "status_changes",
        "runtime_after",
        "blockers_after",
        "blocker_resolution_ids_after",
        "source_checkpoint_version",
        "evidence_refs",
        "source_checkpoint_binding",
        "source_ready_event_binding",
        "authorization_binding",
        "contract_supersession",
        "start_gate_runner_binding",
        "transition_control_review_binding",
        "repository_context_reanchor",
        "noncredit_successor_edges",
        "claim_boundary",
        "unchanged_control_projection",
        "canonical_binding_snapshot_after",
        "previous_event_sha256",
        "event_sha256",
    }
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
FINAL_SCOPE = (
    "Graph v2.4 FP-048 R002/GAP-057 READY through zero-credit start-control "
    "reanchor seq90; exact seq89 READY and reviewed live noncredit successor "
    "cohort preserved. Initial gate, GOAL_STARTED, completion, formal, device, "
    "external, deployment, approval, and release credit remain NOT_RUN."
)
SEQ90_CURRENT_FOCUS = "FP-048 R002/GAP-057 READY; initial five-check start gate NOT_RUN"
SEQ90_CURRENT_EPIC = "EPIC-03 / FP-048 R002/GAP-057 READY_NOT_STARTED"
SEQ90_LAST_UPDATED_BY_WORK_ITEM = WORK_ITEM_ID
SEQ90_VERIFICATION_STATUS = (
    "FP048_R002_SEQ90_ZERO_CREDIT_CONTROL_REANCHORED_START_GATE_NOT_RUN"
)
SEQ90_NEXT_SINGLE_ACTION = (
    "R002 event-scoped five-check start gate를 fresh event ID로 실행한다."
)


class ControlReanchorError(RuntimeError):
    """The source, review, or projected zero-credit transition is invalid."""


class PostcommitUncertain(ControlReanchorError):
    """The checkpoint replacement completed but final reporting is uncertain."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ControlReanchorError(message)


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_json_bytes(value: Mapping[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")


def checkpoint_json_bytes(value: Mapping[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")


def strict_json(raw: bytes, label: str) -> dict[str, Any]:
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            require(key not in result, f"duplicate JSON member: {label}: {key}")
            result[key] = value
        return result

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicates)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ControlReanchorError(f"invalid JSON: {label}") from exc
    require(type(value) is dict, f"JSON root differs: {label}")
    return value


def strict_json_equal(left: Any, right: Any) -> bool:
    return type(left) is type(right) and left == right


@dataclass(frozen=True)
class FileIdentity:
    device: int
    inode: int
    mode: int
    uid: int
    links: int
    size: int
    mtime_ns: int
    ctime_ns: int


@dataclass(frozen=True)
class ReadResult:
    raw: bytes
    identity: FileIdentity


@dataclass(frozen=True)
class ManagedInput:
    identity: FileIdentity
    sha256: str
    byte_length: int


@dataclass(frozen=True)
class Prepared:
    root: Path
    source_raw: bytes
    source_identity: FileIdentity
    source: Mapping[str, Any]
    projected: Mapping[str, Any]
    projected_raw: bytes
    event: Mapping[str, Any]
    retained_inputs: Mapping[Path, ReadResult]
    managed_inputs: Mapping[Path, ManagedInput]
    git_visible_inputs: Mapping[Path, ManagedInput]
    git_status_raw: bytes
    git_head: str
    git_branch: str


def _identity(info: os.stat_result) -> FileIdentity:
    return FileIdentity(
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_nlink,
        info.st_size,
        info.st_mtime_ns,
        info.st_ctime_ns,
    )


def _safe_relative(relative: Path) -> None:
    require(
        not relative.is_absolute()
        and bool(relative.parts)
        and all(part not in {"", ".", ".."} for part in relative.parts),
        f"unsafe relative path: {relative}",
    )


def _safe_root(root: Path) -> Path:
    candidate = Path(root)
    info = candidate.lstat()
    require(stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode), "unsafe root")
    return candidate.resolve(strict=True)


def _stable_read(root: Path, relative: Path) -> ReadResult:
    _safe_relative(relative)
    target = root / relative
    cursor = root
    for part in relative.parts[:-1]:
        cursor /= part
        info = cursor.lstat()
        require(
            stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode),
            f"unsafe parent: {relative}",
        )
    before = target.lstat()
    require(
        stat.S_ISREG(before.st_mode)
        and not stat.S_ISLNK(before.st_mode)
        and before.st_uid == os.geteuid()
        and before.st_nlink == 1
        and not (before.st_mode & (stat.S_ISUID | stat.S_ISGID)),
        f"unsafe file authority: {relative}",
    )
    descriptor = os.open(
        target,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        opened = os.fstat(descriptor)
        require(_identity(opened) == _identity(before), f"file changed opening: {relative}")
        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        after_fd = os.fstat(descriptor)
        after_name = target.lstat()
        require(
            _identity(after_fd) == _identity(before)
            and _identity(after_name) == _identity(before),
            f"file changed reading: {relative}",
        )
        return ReadResult(b"".join(chunks), _identity(after_fd))
    finally:
        os.close(descriptor)


def _binding(relative: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": relative.as_posix(),
        "sha256": sha256_bytes(raw),
        "byte_length": len(raw),
    }


def _read_binding(root: Path, relative: Path) -> tuple[ReadResult, dict[str, Any]]:
    result = _stable_read(root, relative)
    return result, _binding(relative, result.raw)


def _source_checkpoint_binding() -> dict[str, Any]:
    return {
        "path": CHECKPOINT_REL.as_posix(),
        "sha256": SOURCE_CHECKPOINT_SHA256,
        "byte_length": SOURCE_CHECKPOINT_BYTE_LENGTH,
        "sequence": SOURCE_SEQUENCE,
        "tail_event_id": READY_EVENT_ID,
        "tail_event_sha256": SOURCE_TAIL_SHA256,
    }


def _source_ready_event_binding() -> dict[str, Any]:
    return {
        "event_id": READY_EVENT_ID,
        "event_sha256": SOURCE_TAIL_SHA256,
        "goal_id": GOAL_ID,
        "sequence": SOURCE_SEQUENCE,
        "status": "READY",
    }


def _r001_contract_binding() -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260825-001",
        "contract_id": "WS-FP048-R002-INTERNAL-START-GATE-R001",
        "contract_version": "2026-08-25.1",
        "path": R001_CONTRACT_PATH.as_posix(),
        "file_sha256": (
            "45a2e1f6d330be6ce4f1438e79d2bb4bbebce5bcbc8a999251e3e10be99b90ed"
        ),
        "canonical_contract_sha256": (
            "5abeea4fbb5e16f93767884dd6b68e66721852709e0998098571aa495960b355"
        ),
    }


def build_r002_contract() -> dict[str, Any]:
    return {
        "claim_boundary": {
            "actual_event_credit_delta": 0,
            "append_only_reanchor_event_required": True,
            "approval_credit_delta": 0,
            "artifact_completion_credit_delta": 0,
            "binding_replacement_effective_without_reanchor_event": False,
            "formal_test_credit_delta": 0,
            "goal_started": False,
            "intended_use": (
                "ADD_ONLY_SEQ90_REANCHOR_THEN_PRIVATE_GATE_AND_SEQ91_START"
            ),
            "product_implementation_credit_delta": 0,
            "release_status": "NOT_ELIGIBLE",
            "seq89_ready_event_modified": False,
            "start_gate_status": "NOT_RUN",
            "test_credit_delta": 0,
        },
        "contract_id": R002_CONTRACT_ID,
        "contract_version": R002_CONTRACT_VERSION,
        "document_id": R002_DOCUMENT_ID,
        "gate_purpose": "INITIAL_START",
        "ordered_checks": [
            {"check_id": check_id, "command": R002_CONTRACT_COMMANDS[check_id]}
            for check_id in R002_CONTRACT_CHECK_IDS
        ],
        "schema_version": "1.1",
        "successor_reason_code": (
            "SEQ89_READY_TRUST_ANCHOR_AND_REVIEWED_NONCREDIT_ROOTS_REQUIRED"
        ),
        "supersedes": {
            "byte_length": 2_204,
            "canonical_sha256": (
                "5abeea4fbb5e16f93767884dd6b68e66721852709e0998098571aa495960b355"
            ),
            "contract_id": "WS-FP048-R002-INTERNAL-START-GATE-R001",
            "contract_version": "2026-08-25.1",
            "document_id": (
                "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260825-001"
            ),
            "file_sha256": (
                "45a2e1f6d330be6ce4f1438e79d2bb4bbebce5bcbc8a999251e3e10be99b90ed"
            ),
            "path": R001_CONTRACT_PATH.as_posix(),
            "source_ready_event_id": READY_EVENT_ID,
            "source_ready_event_sequence": SOURCE_SEQUENCE,
            "source_ready_event_sha256": SOURCE_TAIL_SHA256,
        },
        "target_goal_content_sha256": GOAL_SHA256,
        "target_goal_id": GOAL_ID,
    }


def load_r002_contract(
    root: Path = ROOT,
) -> tuple[dict[str, Any], dict[str, Any]]:
    root = _safe_root(root)
    read = _stable_read(root, R002_CONTRACT_PATH)
    expected = build_r002_contract()
    require(read.raw == canonical_json_bytes(expected), "R002 contract bytes differ")
    require(len(read.raw) == R002_BYTE_LENGTH, "R002 contract byte length differs")
    require(sha256_bytes(read.raw) == R002_FILE_SHA256, "R002 contract SHA-256 differs")
    require(
        continuation.canonical_json_sha256(expected) == R002_CANONICAL_SHA256,
        "R002 contract canonical SHA-256 differs",
    )
    return expected, {
        "schema_version": "1.1",
        "document_id": R002_DOCUMENT_ID,
        "contract_id": R002_CONTRACT_ID,
        "contract_version": R002_CONTRACT_VERSION,
        "path": R002_CONTRACT_PATH.as_posix(),
        "file_sha256": R002_FILE_SHA256,
        "canonical_contract_sha256": R002_CANONICAL_SHA256,
    }


def r002_contract_binding(root: Path | None = None) -> dict[str, Any]:
    return load_r002_contract(ROOT if root is None else root)[1]


def _require_zero_credit_source(source: Mapping[str, Any]) -> None:
    approved = source.get("approved_state")
    verification = source.get("verification_boundary")
    current = source.get("current_work")
    require(
        isinstance(approved, dict)
        and approved.get("formal_test_count") == 279
        and approved.get("formal_test_not_run_count") == 279
        and approved.get("remaining_gates_waived") is False
        and approved.get("release_status") == "NOT_ELIGIBLE",
        "source approved credit boundary differs",
    )
    require(
        isinstance(verification, dict)
        and verification.get("actual_device_test_status") == "NOT_RUN"
        and verification.get("all_remaining_gate_status") == "NOT_RUN"
        and verification.get("formal_test_pass_claimed") is False
        and verification.get("implementation_conformance_claimed") is False
        and verification.get("release_eligible") is False,
        "source verification credit boundary differs",
    )
    require(
        isinstance(current, dict)
        and current.get("work_item_id") == WORK_ITEM_ID
        and current.get("status") == "READY"
        and current.get("release_completion_claimed") is False,
        "source current-work READY boundary differs",
    )


def require_exact_source(raw: bytes, source: Mapping[str, Any]) -> None:
    require(type(raw) is bytes, "source checkpoint bytes differ")
    require(len(raw) == SOURCE_CHECKPOINT_BYTE_LENGTH, "source checkpoint length differs")
    require(sha256_bytes(raw) == SOURCE_CHECKPOINT_SHA256, "source checkpoint CAS differs")
    require(source.get("schema_version") == "1.25.0", "source schema differs")
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) == SOURCE_SEQUENCE,
        "source history is not exact seq89",
    )
    previous = ""
    for sequence, event in enumerate(history, start=1):
        require(type(event) is dict and event.get("sequence") == sequence, "source history differs")
        require(event.get("event_sha256") == continuation.event_sha256(event), "source event seal differs")
        if sequence > 1:
            require(event.get("previous_event_sha256") == previous, "source event lineage differs")
        previous = str(event["event_sha256"])
    ready = history[-1]
    require(
        ready.get("event_id") == READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("event_sha256") == SOURCE_TAIL_SHA256
        and ready.get("subject_goal_id") == GOAL_ID
        and ready.get("to_status") == "READY"
        and ready.get("status_changes") == {GOAL_ID: "READY"},
        "source seq89 READY event differs",
    )
    snapshot = source.get("working_tree_snapshot")
    require(
        isinstance(snapshot, dict)
        and snapshot.get("managed_changed_path_count") == SOURCE_MANAGED_PATH_COUNT
        and snapshot.get("path_set_sha256") == SOURCE_PATH_SET_SHA256
        and snapshot.get("content_set_sha256") == SOURCE_CONTENT_SET_SHA256,
        "source seq89 managed snapshot differs",
    )
    require(
        state.get("focus_goal_id") == GOAL_ID
        and state.get("focus_work_item_id") == WORK_ITEM_ID
        and state.get("goal_status") == "READY"
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and tuple(state.get("ready_frontier_goal_ids", ())) == READY_FRONTIER,
        "source seq89 READY runtime differs",
    )
    _require_zero_credit_source(source)


def load_exact_source_checkpoint(root: Path = ROOT) -> tuple[bytes, dict[str, Any]]:
    root = _safe_root(root)
    result = _stable_read(root, CHECKPOINT_REL)
    source = strict_json(result.raw, CHECKPOINT_REL.as_posix())
    require_exact_source(result.raw, source)
    return result.raw, source


def _git(root: Path, *arguments: str) -> bytes:
    completed = subprocess.run(
        ["git", *arguments],
        cwd=root,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    require(
        completed.returncode == 0,
        f"git command failed: {' '.join(arguments)}",
    )
    return completed.stdout


def _git_visible_paths(status_raw: bytes) -> tuple[str, ...]:
    records = status_raw.split(b"\0")
    result: list[str] = []
    index = 0
    while index < len(records) - 1:
        record = records[index]
        index += 1
        if not record:
            continue
        require(len(record) >= 4 and record[2:3] == b" ", "git status record differs")
        status_code = record[:2]
        try:
            path = record[3:].decode("utf-8")
        except UnicodeDecodeError as exc:
            raise ControlReanchorError("git-visible path is not UTF-8") from exc
        if b"R" in status_code:
            raise ControlReanchorError(f"git-visible rename is unsupported: {path}")
        if b"C" in status_code:
            require(index < len(records) - 1, "git rename record is truncated")
            index += 1
        relative = Path(path)
        _safe_relative(relative)
        # A deleted path cannot be part of the final byte-addressed snapshot.
        require(b"D" not in status_code, f"git-visible deletion is unsupported: {path}")
        result.append(relative.as_posix())
    require(len(result) == len(set(result)), "git-visible path inventory is duplicated")
    return tuple(sorted(result))


def capture_git_visible_paths(root: Path) -> tuple[bytes, tuple[str, ...]]:
    raw = _git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all")
    return raw, _git_visible_paths(raw)


def _capture_git_context(root: Path) -> tuple[str, str]:
    head = _git(root, "rev-parse", "--verify", "HEAD").decode("ascii").strip()
    branch = _git(root, "symbolic-ref", "--quiet", "--short", "HEAD").decode(
        "utf-8"
    ).strip()
    require(
        re.fullmatch(r"[0-9a-f]{40,64}", head) is not None,
        "Git HEAD differs",
    )
    require(
        bool(branch) and "\n" not in branch and "\r" not in branch,
        "Git symbolic branch differs",
    )
    require(
        _git(root, "rev-parse", "--verify", "HEAD").decode("ascii").strip()
        == head,
        "Git HEAD changed during context capture",
    )
    return head, branch


def _require_git_context(root: Path, head: str, branch: str) -> None:
    observed_head, observed_branch = _capture_git_context(root)
    require(
        observed_head == head and observed_branch == branch,
        "Git HEAD or symbolic branch changed",
    )


def _without_ephemeral_status_record(raw: bytes, relative: Path) -> bytes:
    record = b"?? " + relative.as_posix().encode("utf-8") + b"\0"
    require(raw.count(record) == 1, "seq90 temporary Git status record differs")
    return raw.replace(record, b"", 1)


def _binding_from_record(value: Mapping[str, Any], label: str) -> dict[str, Any]:
    path = value.get("path")
    sha256 = value.get("sha256", value.get("file_sha256"))
    length = value.get("byte_length", value.get("byte_count"))
    require(
        type(path) is str
        and type(sha256) is str
        and SHA256_RE.fullmatch(sha256) is not None
        and type(length) is int
        and length >= 0,
        f"{label} binding differs",
    )
    return {"path": path, "sha256": sha256, "byte_length": length}


def _reviewed_seq87_product_bindings(root: Path) -> dict[str, dict[str, Any]]:
    completion_raw = _stable_read(root, SEQ87_COMPLETION_RECEIPT_REL).raw
    require(
        len(completion_raw) == SEQ87_COMPLETION_RECEIPT_BYTE_LENGTH
        and sha256_bytes(completion_raw) == SEQ87_COMPLETION_RECEIPT_SHA256,
        "seq87 completion receipt bytes differ",
    )
    completion = strict_json(completion_raw, SEQ87_COMPLETION_RECEIPT_REL.as_posix())
    subject_rows = [
        row
        for row in completion.get("evidence_bindings", [])
        if isinstance(row, dict)
        and row.get("path") == SEQ87_REVIEW_SUBJECT_REL.as_posix()
    ]
    require(len(subject_rows) == 1, "seq87 review subject receipt binding differs")
    subject_raw = _stable_read(root, SEQ87_REVIEW_SUBJECT_REL).raw
    subject_binding = _binding_from_record(subject_rows[0], "seq87 review subject")
    require(
        subject_binding == _binding(SEQ87_REVIEW_SUBJECT_REL, subject_raw),
        "seq87 review subject bytes differ",
    )
    subject = strict_json(subject_raw, SEQ87_REVIEW_SUBJECT_REL.as_posix())
    credit = subject.get("credit_scope")
    noncredit = subject.get("noncredit_scope")
    require(isinstance(credit, dict) and isinstance(noncredit, dict), "seq87 review scope differs")
    rows = [
        *credit.get("files", []),
        *noncredit.get("concurrent_live_managed_files", []),
    ]
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        require(isinstance(row, dict), "seq87 reviewed file binding differs")
        binding = _binding_from_record(row, "seq87 reviewed file")
        require(binding["path"] not in result, "seq87 reviewed path is duplicated")
        result[binding["path"]] = binding
    return result


def _seq85_gate_context(
    root: Path,
) -> tuple[dict[str, dict[str, Any]], str]:
    receipt_raw = _stable_read(root, SEQ85_GATE_RECEIPT_REL).raw
    require(
        len(receipt_raw) == SEQ85_GATE_RECEIPT_BYTE_LENGTH
        and sha256_bytes(receipt_raw) == SEQ85_GATE_RECEIPT_SHA256,
        "seq85 gate receipt bytes differ",
    )
    receipt = strict_json(receipt_raw, SEQ85_GATE_RECEIPT_REL.as_posix())
    state_raw = _stable_read(root, SEQ85_GATE_STATE_REL).raw
    repository = receipt.get("repository_snapshot")
    require(
        isinstance(repository, dict)
        and repository.get("gate_repository_state_output_sha256")
        == sha256_bytes(state_raw),
        "seq85 gate repository-state binding differs",
    )
    state = strict_json(state_raw, SEQ85_GATE_STATE_REL.as_posix())
    dirty = state.get("dirty_snapshot")
    rows = dirty.get("paths") if isinstance(dirty, dict) else None
    require(isinstance(rows, list), "seq85 gate dirty snapshot differs")
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        require(isinstance(row, dict) and type(row.get("path")) is str, "seq85 gate path differs")
        worktree = row.get("worktree")
        if not isinstance(worktree, dict) or worktree.get("state") != "PRESENT":
            continue
        binding = {
            "path": row["path"],
            "sha256": worktree.get("sha256"),
            "byte_length": worktree.get("byte_count"),
        }
        binding = _binding_from_record(binding, "seq85 gate worktree")
        require(binding["path"] not in result, "seq85 gate path is duplicated")
        result[binding["path"]] = binding
    repository_context = state.get("repository")
    current_head = (
        repository_context.get("head_commit")
        if isinstance(repository_context, dict)
        else None
    )
    require(
        type(current_head) is str and re.fullmatch(r"[0-9a-f]{40,64}", current_head),
        "seq85 gate current head differs",
    )
    return result, current_head


def _git_blob_binding(root: Path, commit: str, relative: Path) -> dict[str, Any]:
    raw = _git(root, "show", f"{commit}:{relative.as_posix()}")
    return _binding(relative, raw)


def _source_product_binding(
    root: Path,
    relative: Path,
    *,
    reviewed: Mapping[str, Mapping[str, Any]],
    gate_dirty: Mapping[str, Mapping[str, Any]],
    source_head: str,
) -> dict[str, Any]:
    path = relative.as_posix()
    if path in reviewed:
        return copy.deepcopy(dict(reviewed[path]))
    if path in gate_dirty:
        return copy.deepcopy(dict(gate_dirty[path]))
    return _git_blob_binding(root, source_head, relative)


def validated_seq85_to_seq87_successor_edges(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> dict[str, list[dict[str, Any]]]:
    """Return the exact reviewed seq85-to-seq87 file successor authority."""
    root = _safe_root(root)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(isinstance(history, list), "seq85-to-seq87 checkpoint history differs")
    if len(history) == SOURCE_SEQUENCE:
        require_exact_source(checkpoint_json_bytes(checkpoint), checkpoint)
    else:
        require_control_reanchored_checkpoint(root, checkpoint)

    reviewed = _reviewed_seq87_product_bindings(root)
    expected_paths = SEQ87_REVIEWED_CREDIT_PATHS | SEQ87_REVIEWED_NONCREDIT_PATHS
    require(set(reviewed) == expected_paths, "seq87 reviewed successor inventory differs")
    gate_dirty, source_head = _seq85_gate_context(root)
    modified: list[dict[str, Any]] = []
    added: list[dict[str, Any]] = []
    for path in sorted(expected_paths):
        scope = (
            "FP046_R002_DIRECT_INTERNAL_STATIC_ONLY"
            if path in SEQ87_REVIEWED_CREDIT_PATHS
            else "CONCURRENT_LIVE_MANAGED_NONCREDIT"
        )
        successor = copy.deepcopy(reviewed[path])
        if path in SEQ87_REVIEWED_ADDED_PATHS:
            added.append(
                {
                    "path": path,
                    "scope": scope,
                    "successor": successor,
                }
            )
            continue
        predecessor = (
            copy.deepcopy(gate_dirty[path])
            if path in gate_dirty
            else _git_blob_binding(root, source_head, Path(path))
        )
        require(
            predecessor["sha256"] != successor["sha256"]
            or predecessor["byte_length"] != successor["byte_length"],
            f"seq85-to-seq87 successor has no byte change: {path}",
        )
        modified.append(
            {
                "path": path,
                "predecessor": predecessor,
                "scope": scope,
                "successor": successor,
            }
        )
    edges = {"modified": modified, "added": added}
    require(
        continuation.canonical_json_sha256(edges) == SEQ85_TO_SEQ87_EDGE_SET_SHA256,
        "seq85-to-seq87 reviewed successor edge set differs",
    )
    return edges


def compute_noncredit_successor_edges(
    root: Path,
    source: Mapping[str, Any],
) -> dict[str, list[dict[str, Any]]]:
    source_paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    require(
        set(SOURCE_PRODUCT_MEMBERSHIP)
        == set(SOURCE_PRODUCT_PREDECESSORS) | NONCREDIT_ADDED_PATHS
        and set(SOURCE_PRODUCT_PREDECESSORS).isdisjoint(NONCREDIT_ADDED_PATHS),
        "noncredit source authority inventory differs",
    )
    reviewed = _reviewed_seq87_product_bindings(root)
    gate_dirty, source_head = _seq85_gate_context(root)
    modified: list[dict[str, Any]] = []
    added: list[dict[str, Any]] = []
    for relative in NONCREDIT_PRODUCT_PATHS:
        current_raw = _stable_read(root, relative).raw
        successor = _binding(relative, current_raw)
        if SOURCE_PRODUCT_MEMBERSHIP[relative.as_posix()]:
            predecessor = _source_product_binding(
                root,
                relative,
                reviewed=reviewed,
                gate_dirty=gate_dirty,
                source_head=source_head,
            )
            require(
                predecessor == SOURCE_PRODUCT_PREDECESSORS.get(relative.as_posix()),
                f"noncredit predecessor authority differs: {relative}",
            )
            require(
                predecessor["sha256"] != successor["sha256"]
                or predecessor["byte_length"] != successor["byte_length"],
                f"noncredit modified edge has no byte change: {relative}",
            )
            modified.append(
                {
                    "path": relative.as_posix(),
                    "predecessor": predecessor,
                    "successor": successor,
                }
            )
        else:
            require(
                relative.as_posix() not in source_paths,
                f"noncredit added path was already managed in source: {relative}",
            )
            added.append({"path": relative.as_posix(), "successor": successor})
    require(
        [row["path"] for row in modified] == sorted(row["path"] for row in modified)
        and [row["path"] for row in added] == sorted(row["path"] for row in added),
        "noncredit successor edges are not sorted",
    )
    return {"modified": modified, "added": added}


def build_authorization() -> dict[str, Any]:
    return {
        "authorization_quote": (
            "알았어 해당 단계들 계획 먼저 세세하게 세우고 진행해. "
            "팀 꾸려서 병렬로 진행해."
        ),
        "authorization_scope": {
            "authorized_actions": [
                "SEQ90_ZERO_CREDIT_GOAL_START_CONTROL_REANCHOR",
                "R002_PRIVATE_INITIAL_START_GATE_AFTER_SEQ90",
                "SEQ91_GOAL_STARTED_AFTER_FRESH_FIVE_CHECK_PASS",
                "LOCAL_FP048_R002_IMPLEMENTATION_AFTER_SEQ91",
            ],
            "conditions": [
                "SEQ89_EXACT_SOURCE_CAS_REQUIRED",
                "SEQ90_MUST_PRESERVE_READY_STATUS",
                "FINAL_GIT_VISIBLE_MANAGED_COHORT_MUST_BE_RUNTIME_CALCULATED",
                "NONCREDIT_SUCCESSOR_ROOT_EDGES_MUST_BE_REVIEW_BOUND",
                "ALL_FIVE_EVENT_SCOPED_GATE_CHECKS_MUST_PASS_FRESH",
                "SEQ91_MUST_BIND_THE_EXACT_PASS_RECEIPT",
                "ALL_CREDIT_DELTAS_REMAIN_ZERO_UNTIL_SEPARATE_COMPLETION",
            ],
            "excluded_actions": [
                "RETROACTIVE_PRODUCT_IMPLEMENTATION_CREDIT",
                "FORMAL_TEST_PASS_CLAIM",
                "ACTUAL_DEVICE_OR_EXTERNAL_REVIEW_CREDIT",
                "DEPLOYMENT_OR_RELEASE_APPROVAL",
            ],
        },
        "authorization_status": "AUTHORIZED_FOR_CONDITIONAL_SEQ90_91_CONTINUATION",
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "document_id": "WS-FP048-R002-SEQ90-91-AUTHORIZATION-20260826-001",
        "evidence_type": "USER_CONTINUATION_AUTHORIZATION",
        "goal_id": GOAL_ID,
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "target_started_event_id": STARTED_EVENT_ID,
    }


def authorization_binding(root: Path) -> dict[str, Any]:
    read = _stable_read(root, AUTHORIZATION_REL)
    require(read.raw == canonical_json_bytes(build_authorization()), "seq90 authorization bytes differ")
    return _binding(AUTHORIZATION_REL, read.raw)


def _control_bindings(root: Path) -> list[dict[str, Any]]:
    return [_binding(path, _stable_read(root, path).raw) for path in REVIEW_INPUT_PATHS]


def _edge_set_sha256(edges: Mapping[str, Any]) -> str:
    return continuation.canonical_json_sha256(edges)


def _r001_review_assignment_binding(root: Path) -> dict[str, Any]:
    read = _stable_read(root, R001_REVIEW_ASSIGNMENT_REL)
    require(
        len(read.raw) == 6_622
        and sha256_bytes(read.raw)
        == "4d9cbec05434c08c56adfe088b8649dad6b6ec9a6d641660950eaf878a8c9e4e",
        "seq90 rejected R001 assignment bytes differ",
    )
    return {
        "byte_length": len(read.raw),
        "disposition": "REJECTED_BY_REVIEW_BLOCKER",
        "path": R001_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "REPOSITORY_LOCAL_LOCK_INVALIDATED_GIT_COHORT_CAS",
        "round_id": "WS-FP048-R002-SEQ90-91-REVIEW-20260826-R001",
        "sha256": sha256_bytes(read.raw),
    }


def _r002_review_assignment_binding(root: Path) -> dict[str, Any]:
    read = _stable_read(root, R002_REVIEW_ASSIGNMENT_REL)
    require(
        len(read.raw) == 7_047
        and sha256_bytes(read.raw)
        == "e661fdf0398ad622749a3d7df8ec31799072cb44fad528e30b591dab7a249072",
        "seq90 rejected R002 assignment bytes differ",
    )
    document = strict_json(read.raw, R002_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        document.get("round_id")
        == "WS-FP048-R002-SEQ90-91-REVIEW-20260826-R002"
        and document.get("supersedes") == _r001_review_assignment_binding(root),
        "seq90 rejected R002 assignment lineage differs",
    )
    return {
        "byte_length": len(read.raw),
        "disposition": "REJECTED_BY_PREFLIGHT_BLOCKER",
        "path": R002_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "CANONICAL_BINDINGS_ARRAY_REJECTED_BY_DICT_ONLY_PROJECTION_VALIDATOR",
        "round_id": "WS-FP048-R002-SEQ90-91-REVIEW-20260826-R002",
        "sha256": sha256_bytes(read.raw),
    }


def _r003_review_assignment_binding(root: Path) -> dict[str, Any]:
    read = _stable_read(root, R003_REVIEW_ASSIGNMENT_REL)
    require(
        len(read.raw) == 7_069
        and sha256_bytes(read.raw)
        == "66f023ae1b61542c17fe5dd2ef9155e2cd015ad197948a02e4b53bed9cf9d476",
        "seq90 rejected R003 assignment bytes differ",
    )
    document = strict_json(read.raw, R003_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        document.get("round_id")
        == "WS-FP048-R002-SEQ90-91-REVIEW-20260826-R003"
        and document.get("supersedes") == _r002_review_assignment_binding(root),
        "seq90 rejected R003 assignment lineage differs",
    )
    return {
        "byte_length": len(read.raw),
        "disposition": "REJECTED_BY_PREFLIGHT_BLOCKER",
        "path": R003_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "DIRECT_GATE_EVIDENCE_WAS_INCLUDED_IN_PERSISTED_MANAGED_SNAPSHOT",
        "round_id": "WS-FP048-R002-SEQ90-91-REVIEW-20260826-R003",
        "sha256": sha256_bytes(read.raw),
    }


def _r004_review_assignment_binding(root: Path) -> dict[str, Any]:
    read = _stable_read(root, R004_REVIEW_ASSIGNMENT_REL)
    require(
        len(read.raw) == 23_447
        and sha256_bytes(read.raw)
        == "af7f45bda43870ca1b92059ae080f039e7a90f7d4efc464c01300adaf8819620",
        "seq90 rejected R004 assignment bytes differ",
    )
    document = strict_json(read.raw, R004_REVIEW_ASSIGNMENT_REL.as_posix())
    require(
        document.get("round_id")
        == "WS-FP048-R002-SEQ90-91-REVIEW-20260826-R004"
        and document.get("supersedes") == _r003_review_assignment_binding(root),
        "seq90 rejected R004 assignment lineage differs",
    )
    return {
        "byte_length": len(read.raw),
        "disposition": "REJECTED_BY_PREFLIGHT_BLOCKER",
        "path": R004_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "TEST_LAYER_REGISTRY_UNASSIGNED_10_FILES",
        "round_id": "WS-FP048-R002-SEQ90-91-REVIEW-20260826-R004",
        "sha256": sha256_bytes(read.raw),
    }


def build_review_assignment(
    root: Path,
    source: Mapping[str, Any],
) -> bytes:
    edges = compute_noncredit_successor_edges(root, source)
    authorization = authorization_binding(root)
    contract_binding = r002_contract_binding(root)
    assignment = {
        "assigner": {"id": "codex-root", "task_id": "/root"},
        "authorization_binding": authorization,
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "document_id": "WS-FP048-R002-SEQ90-91-REVIEW-ASSIGNMENT-20260826-R005",
        "executor": {
            "id": "codex-fp048-r002-seq90-control-executor-20260826",
            "task_id": "/root/fp048_seq90_reanchor_impl",
        },
        "goal_id": GOAL_ID,
        "noncredit_successor_edges": edges,
        "noncredit_successor_edges_sha256": _edge_set_sha256(edges),
        "projected_transition": {
            "reanchor_event_id": CONTROL_REANCHOR_EVENT_ID,
            "reanchor_sequence": CONTROL_REANCHOR_SEQUENCE,
            "reanchor_status_change": {},
            "reanchor_transition": "READY_TO_READY",
            "started_event_id": STARTED_EVENT_ID,
            "started_sequence": 91,
            "started_transition": "READY_TO_IN_PROGRESS_AFTER_FRESH_PASS",
        },
        "replacement_contract_binding": contract_binding,
        "required_reviewer": {
            "id": REVIEWER_ID,
            "task_id": REVIEWER_TASK,
        },
        "review_scope": [
            "exact seq89 source CAS and append-only seq90 lineage",
            "runtime final Git-visible managed cohort",
            "exact reviewed noncredit root predecessor/successor edges",
            "READY-to-READY status and zero-credit boundary",
            "R002 five-check private gate and conditional seq91 start",
            "source/review/ABA drift fail-closed behavior",
        ],
        "reviewed_control_inputs": _control_bindings(root),
        "round_id": ROUND_ID,
        "schema_version": "1.0",
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "supersedes": _r004_review_assignment_binding(root),
    }
    return canonical_json_bytes(assignment)


def transition_review_binding(root: Path) -> dict[str, dict[str, Any]]:
    source_raw, source = load_exact_source_checkpoint(root)
    del source_raw
    expected_assignment = build_review_assignment(root, source)
    assignment_read = _stable_read(root, REVIEW_ASSIGNMENT_REL)
    require(
        assignment_read.raw == expected_assignment,
        "seq90 review assignment bytes differ",
    )
    _values, bindings = _load_physical_review(root)
    return bindings


def _reviewed_at(root: Path) -> datetime:
    independent = strict_json(
        _stable_read(root, INDEPENDENT_REVIEW_REL).raw,
        INDEPENDENT_REVIEW_REL.as_posix(),
    )
    value = independent.get("reviewed_at")
    require(type(value) is str, "seq90 reviewed_at differs")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise ControlReanchorError("seq90 reviewed_at is invalid") from exc
    require(parsed.tzinfo is not None and parsed.utcoffset() is not None, "seq90 reviewed_at lacks timezone")
    return parsed


def _parse_time(value: str, label: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise ControlReanchorError(f"{label} is invalid") from exc
    require(
        parsed.tzinfo is not None
        and parsed.utcoffset() is not None
        and parsed.microsecond == 0,
        f"{label} must be timezone-aware second precision",
    )
    return parsed


def _event_time(root: Path, source: Mapping[str, Any], supplied: str | None) -> str:
    ready_at = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq89 occurred_at",
    )
    reviewed_at = _reviewed_at(root)
    if supplied is None:
        candidate = datetime.now(timezone.utc).replace(microsecond=0)
    else:
        candidate = _parse_time(supplied, "seq90 occurred_at")
    require(candidate > ready_at and candidate > reviewed_at, "seq90 must follow source and review")
    return candidate.isoformat()


def _unchanged_projection(source: Mapping[str, Any]) -> dict[str, str]:
    result: dict[str, str] = {}
    for field in (
        "approved_state",
        "authority_boundary",
        "canonical_bindings",
        "current_work",
        "verification_boundary",
    ):
        value = source.get(field)
        expected_type = list if field == "canonical_bindings" else dict
        require(
            type(value) is expected_type,
            f"source control projection differs: {field}",
        )
        result[field] = continuation.canonical_json_sha256(value)
    state = source["goal_execution"]
    for field in (
        "artifact_work_queue",
        "completion_boundary",
        "completion_evidence_by_goal",
        "archived_completion_evidence_by_goal",
        "status_by_goal",
    ):
        value = state.get(field)
        require(isinstance(value, dict), f"source Goal projection differs: {field}")
        result[f"goal_execution.{field}"] = continuation.canonical_json_sha256(value)
    return result


def _final_snapshot_hashes(
    root: Path,
    paths: Sequence[str],
) -> tuple[str, str]:
    return continuation.working_snapshot_hashes(root, list(paths))


def _final_managed_paths(
    source: Mapping[str, Any],
    git_visible: Sequence[str],
) -> tuple[str, ...]:
    paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    paths.update(
        path
        for path in git_visible
        if not path.startswith("docs/control/execution/goal-gates/")
    )
    paths.discard(CHECKPOINT_REL.as_posix())
    paths = {
        path
        for path in paths
        if not path.startswith("docs/control/execution/goal-gates/")
    }
    paths.update(path.as_posix() for path in REVIEWED_CONTROL_PATHS)
    paths.add(AUTHORIZATION_REL.as_posix())
    paths.update(path.as_posix() for path in REVIEW_PATHS)
    result = tuple(sorted(paths))
    require(len(result) == len(set(result)), "final managed cohort is duplicated")
    return result


def project_seq90(
    root: Path,
    source: Mapping[str, Any],
    *,
    git_visible: Sequence[str],
    occurred_at: str,
    final_hashes: tuple[str, str] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    root = _safe_root(root)
    state = source["goal_execution"]
    ready = state["transition_history"][-1]
    edges = compute_noncredit_successor_edges(root, source)
    review = transition_review_binding(root)
    authorization = authorization_binding(root)
    contract_binding = r002_contract_binding(root)
    gate_binding = _binding(GATE_SCRIPT_REL, _stable_read(root, GATE_SCRIPT_REL).raw)
    paths = _final_managed_paths(source, git_visible)
    path_sha256, content_sha256 = (
        _final_snapshot_hashes(root, paths) if final_hashes is None else final_hashes
    )
    require(
        SHA256_RE.fullmatch(path_sha256) is not None
        and SHA256_RE.fullmatch(content_sha256) is not None,
        "final snapshot hashes differ",
    )
    event: dict[str, Any] = {
        "sequence": CONTROL_REANCHOR_SEQUENCE,
        "event_id": CONTROL_REANCHOR_EVENT_ID,
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "occurred_on": datetime.fromisoformat(occurred_at).date().isoformat(),
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
        "runtime_after": copy.deepcopy(ready["runtime_after"]),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [
            "FP048-R002_INITIAL_START_GATE_CONTRACT_SUCCESSOR",
            "FP048-R002_SEQ90_91_AUTHORIZATION",
            "FP048-R002_SEQ90_91_TRANSITION_CONTROL_REVIEW",
            "FP048-R002_REVIEWED_NONCREDIT_SUCCESSOR_ROOT_EDGES",
        ],
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "source_ready_event_binding": _source_ready_event_binding(),
        "authorization_binding": authorization,
        "contract_supersession": {
            "previous_contract_binding": _r001_contract_binding(),
            "reason_code": (
                "SEQ89_READY_TRUST_ANCHOR_AND_REVIEWED_NONCREDIT_ROOTS_REQUIRED"
            ),
            "replacement_contract_binding": contract_binding,
        },
        "start_gate_runner_binding": gate_binding,
        "transition_control_review_binding": review,
        "repository_context_reanchor": {
            "before": {
                **_source_checkpoint_binding(),
                "managed_changed_path_count": SOURCE_MANAGED_PATH_COUNT,
                "path_set_sha256": SOURCE_PATH_SET_SHA256,
                "content_set_sha256": SOURCE_CONTENT_SET_SHA256,
            },
            "after": {
                "base_commit": source["working_tree_snapshot"]["base_head"],
                "branch": source["session_handoff"]["branch"],
                "current_head": source["session_handoff"]["source_commit_or_snapshot"]["current_head"],
                "managed_changed_path_count": len(paths),
                "path_set_sha256": path_sha256,
                "content_set_sha256": content_sha256,
            },
        },
        "noncredit_successor_edges": edges,
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "unchanged_control_projection": _unchanged_projection(source),
        "canonical_binding_snapshot_after": copy.deepcopy(ready["canonical_binding_snapshot_after"]),
        "previous_event_sha256": SOURCE_TAIL_SHA256,
    }
    event["event_sha256"] = continuation.event_sha256(event)
    require(set(event) == EVENT_FIELDS, "seq90 event field set differs")

    projected = copy.deepcopy(source)
    projected_state = projected["goal_execution"]
    projected_state["transition_history"].append(event)
    projected_state["transition_history_anchor_sha256"] = event["event_sha256"]
    projected_state["validation_cutoff_at"] = occurred_at
    snapshot = projected["working_tree_snapshot"]
    snapshot["scope"] = FINAL_SCOPE
    snapshot["managed_changed_paths"] = list(paths)
    snapshot["managed_changed_path_count"] = len(paths)
    snapshot["path_set_sha256"] = path_sha256
    snapshot["content_set_sha256"] = content_sha256
    handoff = projected["session_handoff"]
    handoff["changed_files"] = list(paths)
    mirror = handoff["source_commit_or_snapshot"]
    mirror["file_count"] = len(paths)
    mirror["path_set_sha256"] = path_sha256
    mirror["content_set_sha256"] = content_sha256
    handoff["last_verification_status"] = SEQ90_VERIFICATION_STATUS
    handoff["next_single_action"] = SEQ90_NEXT_SINGLE_ACTION
    _assert_zero_credit_projection(source, projected)
    return projected, event


def _assert_zero_credit_projection(
    source: Mapping[str, Any], projected: Mapping[str, Any]
) -> None:
    for field in (
        "approved_state",
        "authority_boundary",
        "canonical_bindings",
        "current_work",
        "verification_boundary",
    ):
        require(
            strict_json_equal(source.get(field), projected.get(field)),
            f"seq90 changed zero-credit field: {field}",
        )
    before = source["goal_execution"]
    after = projected["goal_execution"]
    for field in before:
        if field in {
            "transition_history",
            "transition_history_anchor_sha256",
            "validation_cutoff_at",
        }:
            continue
        require(strict_json_equal(before[field], after[field]), f"seq90 changed Goal state: {field}")
    require(
        after["status_by_goal"].get(GOAL_ID) == "READY"
        and after["goal_status"] == "READY"
        and projected["current_work"]["status"] == "READY",
        "seq90 gained Goal-start credit",
    )


def _retained_input_paths() -> tuple[Path, ...]:
    return tuple(
        dict.fromkeys(
            (
                AUTHORIZATION_REL,
                R001_REVIEW_ASSIGNMENT_REL,
                R002_REVIEW_ASSIGNMENT_REL,
                R003_REVIEW_ASSIGNMENT_REL,
                R004_REVIEW_ASSIGNMENT_REL,
                *REVIEW_PATHS,
                *REVIEWED_CONTROL_PATHS,
                *NONCREDIT_PRODUCT_PATHS,
                SEQ85_GATE_RECEIPT_REL,
                SEQ85_GATE_STATE_REL,
                SEQ87_COMPLETION_RECEIPT_REL,
                SEQ87_REVIEW_SUBJECT_REL,
            )
        )
    )


def _capture_inputs(root: Path) -> dict[Path, ReadResult]:
    return {path: _stable_read(root, path) for path in _retained_input_paths()}


def _require_inputs_unchanged(root: Path, retained: Mapping[Path, ReadResult]) -> None:
    for path, expected in retained.items():
        observed = _stable_read(root, path)
        require(
            observed.identity == expected.identity and observed.raw == expected.raw,
            f"review input changed or ABA-replaced: {path}",
        )


def _capture_managed_inputs(
    root: Path,
    paths: Sequence[str],
) -> dict[Path, ManagedInput]:
    result: dict[Path, ManagedInput] = {}
    for value in paths:
        relative = Path(value)
        read = _stable_read(root, relative)
        result[relative] = ManagedInput(
            identity=read.identity,
            sha256=sha256_bytes(read.raw),
            byte_length=len(read.raw),
        )
    require(len(result) == len(paths), "managed input inventory is duplicated")
    return result


def _managed_input_snapshot_hashes(
    retained: Mapping[Path, ManagedInput],
) -> tuple[str, str]:
    paths = sorted(path.as_posix() for path in retained)
    path_sha256 = hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest()
    content = hashlib.sha256()
    for path in paths:
        content.update(path.encode("utf-8"))
        content.update(b"\0")
        content.update(retained[Path(path)].sha256.encode("ascii"))
        content.update(b"\n")
    return path_sha256, content.hexdigest()


def _require_managed_inputs_unchanged(
    root: Path,
    retained: Mapping[Path, ManagedInput],
    *,
    label: str = "final managed input",
) -> None:
    for path, expected in retained.items():
        read = _stable_read(root, path)
        require(
            read.identity == expected.identity
            and len(read.raw) == expected.byte_length
            and sha256_bytes(read.raw) == expected.sha256,
            f"{label} changed or ABA-replaced: {path}",
        )


def _require_preflight_cohort_unchanged(
    root: Path,
    *,
    source: ReadResult,
    retained: Mapping[Path, ReadResult],
    managed: Mapping[Path, ManagedInput],
    git_visible: Mapping[Path, ManagedInput],
    git_status_raw: bytes,
    git_head: str,
    git_branch: str,
    phase: str,
) -> None:
    _require_inputs_unchanged(root, retained)
    _require_managed_inputs_unchanged(root, managed)
    _require_managed_inputs_unchanged(
        root,
        git_visible,
        label="full Git-visible input",
    )
    _require_git_context(root, git_head, git_branch)
    observed_source = _stable_read(root, CHECKPOINT_REL)
    require(
        observed_source.identity == source.identity
        and observed_source.raw == source.raw,
        f"source changed {phase}",
    )
    observed_status, _ = capture_git_visible_paths(root)
    require(observed_status == git_status_raw, f"Git-visible cohort changed {phase}")


# Tests may replace this with a deterministic mutation between capture and seal.
_AFTER_CAPTURE_HOOK: Callable[[Path], None] | None = None


def _validate_projected_with_consumers(root: Path, projected: Mapping[str, Any]) -> None:
    descriptor, name = tempfile.mkstemp(
        dir=root / CHECKPOINT_REL.parent,
        prefix=".walksafe-fp048-r002-seq90-preflight.",
        suffix=".json",
    )
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(checkpoint_json_bytes(projected))
            stream.flush()
            os.fsync(stream.fileno())
        relative = temporary.relative_to(root)
        errors = continuation.validate(
            root,
            relative,
            continuation.V23_ARCHIVE_RELATIVE,
            continuation.V24_MANIFEST_RELATIVE,
        )
        require(not errors, "projected continuation failed: " + " | ".join(errors))
        from scripts import check_walksafe_goal_graph_v2_4 as goal_graph

        graph_errors = goal_graph.validate(
            root,
            relative,
            check_continuation=False,
        )
        require(not graph_errors, "projected Goal graph failed: " + " | ".join(graph_errors))
    finally:
        temporary.unlink(missing_ok=True)


def prepare(
    root: Path = ROOT,
    *,
    occurred_at: str | None = None,
    validate_consumers: bool = True,
) -> Prepared:
    root = _safe_root(root)
    source_read = _stable_read(root, CHECKPOINT_REL)
    source = strict_json(source_read.raw, CHECKPOINT_REL.as_posix())
    require_exact_source(source_read.raw, source)
    retained = _capture_inputs(root)
    git_head, git_branch = _capture_git_context(root)
    expected_head = source["session_handoff"]["source_commit_or_snapshot"][
        "current_head"
    ]
    require(git_head == expected_head, "live Git HEAD differs from seq89 source")
    status_raw, git_visible = capture_git_visible_paths(root)
    git_visible_inputs = _capture_managed_inputs(root, git_visible)
    _require_git_context(root, git_head, git_branch)
    event_time = _event_time(root, source, occurred_at)
    projected, event = project_seq90(
        root,
        source,
        git_visible=git_visible,
        occurred_at=event_time,
    )
    managed = _capture_managed_inputs(
        root,
        projected["working_tree_snapshot"]["managed_changed_paths"],
    )
    require(
        _managed_input_snapshot_hashes(managed)
        == (
            projected["working_tree_snapshot"]["path_set_sha256"],
            projected["working_tree_snapshot"]["content_set_sha256"],
        ),
        "captured final managed cohort differs from projected snapshot",
    )
    if _AFTER_CAPTURE_HOOK is not None:
        _AFTER_CAPTURE_HOOK(root)
    _require_preflight_cohort_unchanged(
        root,
        source=source_read,
        retained=retained,
        managed=managed,
        git_visible=git_visible_inputs,
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
        phase="during seq90 preflight",
    )
    if validate_consumers:
        _validate_projected_with_consumers(root, projected)
        _require_preflight_cohort_unchanged(
            root,
            source=source_read,
            retained=retained,
            managed=managed,
            git_visible=git_visible_inputs,
            git_status_raw=status_raw,
            git_head=git_head,
            git_branch=git_branch,
            phase="after projected consumer validation",
        )
    return Prepared(
        root=root,
        source_raw=source_read.raw,
        source_identity=source_read.identity,
        source=source,
        projected=projected,
        projected_raw=checkpoint_json_bytes(projected),
        event=event,
        retained_inputs=retained,
        managed_inputs=managed,
        git_visible_inputs=git_visible_inputs,
        git_status_raw=status_raw,
        git_head=git_head,
        git_branch=git_branch,
    )


def _load_physical_review(
    root: Path,
) -> tuple[
    dict[Path, dict[str, Any]],
    dict[str, dict[str, Any]],
]:
    values: dict[Path, dict[str, Any]] = {}
    bindings: dict[str, dict[str, Any]] = {}
    names = {
        REVIEW_ASSIGNMENT_REL: "assignment",
        REVIEW_RESULT_REL: "review_result",
        INDEPENDENT_REVIEW_REL: "independent_review",
    }
    for path in REVIEW_PATHS:
        read = _stable_read(root, path)
        value = strict_json(read.raw, path.as_posix())
        require(read.raw == canonical_json_bytes(value), f"noncanonical review: {path}")
        values[path] = value
        bindings[names[path]] = _binding(path, read.raw)
    assignment = values[REVIEW_ASSIGNMENT_REL]
    result = values[REVIEW_RESULT_REL]
    independent = values[INDEPENDENT_REVIEW_REL]
    require(
        set(assignment)
        == {
            "assigner",
            "authorization_binding",
            "claim_boundary",
            "document_id",
            "executor",
            "goal_id",
            "noncredit_successor_edges",
            "noncredit_successor_edges_sha256",
            "projected_transition",
            "replacement_contract_binding",
            "required_reviewer",
            "review_scope",
            "reviewed_control_inputs",
            "round_id",
            "schema_version",
            "source_checkpoint_binding",
            "supersedes",
        }
        and set(result)
        == {
            "assignment_binding",
            "claim_boundary",
            "decision",
            "document_id",
            "external_independence_claimed",
            "findings",
            "goal_id",
            "noncredit_successor_edges_sha256",
            "reviewed_at",
            "reviewer",
            "round_id",
            "schema_version",
        }
        and set(independent)
        == {
            "assignment_binding",
            "decision",
            "document_id",
            "external_independence_claimed",
            "goal_id",
            "independent_checks",
            "noncredit_successor_edges_sha256",
            "review_result_binding",
            "reviewed_at",
            "reviewer",
            "round_id",
            "schema_version",
        },
        "seq90 review field set differs",
    )
    reviewer = {"id": REVIEWER_ID, "task_id": REVIEWER_TASK}
    edges = _validate_edge_shape(assignment.get("noncredit_successor_edges"))
    edge_sha256 = _edge_set_sha256(edges)
    require(
        assignment.get("schema_version") == "1.0"
        and assignment.get("document_id")
        == "WS-FP048-R002-SEQ90-91-REVIEW-ASSIGNMENT-20260826-R005"
        and assignment.get("round_id") == ROUND_ID
        and assignment.get("goal_id") == GOAL_ID
        and assignment.get("assigner") == {"id": "codex-root", "task_id": "/root"}
        and assignment.get("executor")
        == {
            "id": "codex-fp048-r002-seq90-control-executor-20260826",
            "task_id": "/root/fp048_seq90_reanchor_impl",
        }
        and assignment.get("required_reviewer") == reviewer
        and assignment.get("source_checkpoint_binding") == _source_checkpoint_binding()
        and assignment.get("authorization_binding") == authorization_binding(root)
        and assignment.get("replacement_contract_binding") == r002_contract_binding(root)
        and assignment.get("claim_boundary") == CLAIM_BOUNDARY
        and assignment.get("noncredit_successor_edges_sha256") == edge_sha256
        and assignment.get("projected_transition")
        == {
            "reanchor_event_id": CONTROL_REANCHOR_EVENT_ID,
            "reanchor_sequence": CONTROL_REANCHOR_SEQUENCE,
            "reanchor_status_change": {},
            "reanchor_transition": "READY_TO_READY",
            "started_event_id": STARTED_EVENT_ID,
            "started_sequence": 91,
            "started_transition": "READY_TO_IN_PROGRESS_AFTER_FRESH_PASS",
        }
        and assignment.get("review_scope")
        == [
            "exact seq89 source CAS and append-only seq90 lineage",
            "runtime final Git-visible managed cohort",
            "exact reviewed noncredit root predecessor/successor edges",
            "READY-to-READY status and zero-credit boundary",
            "R002 five-check private gate and conditional seq91 start",
            "source/review/ABA drift fail-closed behavior",
        ]
        and assignment.get("supersedes") == _r004_review_assignment_binding(root),
        "seq90 review assignment authority differs",
    )
    require(
        result.get("assignment_binding") == bindings["assignment"]
        and independent.get("assignment_binding") == bindings["assignment"]
        and independent.get("review_result_binding") == bindings["review_result"],
        "seq90 review triad binding differs",
    )
    require(
        result.get("schema_version") == "1.0"
        and result.get("document_id")
        == "WS-FP048-R002-SEQ90-91-REVIEW-RESULT-20260826-R005"
        and result.get("round_id") == ROUND_ID
        and result.get("goal_id") == GOAL_ID
        and result.get("reviewer") == reviewer
        and result.get("decision") == "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and independent.get("decision") == "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY"
        and independent.get("schema_version") == "1.0"
        and independent.get("document_id")
        == "WS-FP048-R002-SEQ90-91-INDEPENDENT-REVIEW-20260826-R005"
        and independent.get("round_id") == ROUND_ID
        and independent.get("goal_id") == GOAL_ID
        and independent.get("reviewer") == reviewer
        and result.get("external_independence_claimed") is False
        and independent.get("external_independence_claimed") is False
        and result.get("claim_boundary") == CLAIM_BOUNDARY
        and result.get("noncredit_successor_edges_sha256") == edge_sha256
        and independent.get("noncredit_successor_edges_sha256") == edge_sha256
        and result.get("findings") == []
        and independent.get("independent_checks")
        == {
            "all_credit_deltas_zero": True,
            "exact_seq89_source_cas": True,
            "noncredit_successor_edges_exact": True,
            "ready_to_ready_only": True,
            "review_input_set_closed": True,
        },
        "seq90 review approval boundary differs",
    )
    require(
        type(result.get("reviewed_at")) is str
        and type(independent.get("reviewed_at")) is str,
        "seq90 reviewer-authored time differs",
    )
    result_at = _parse_time(result["reviewed_at"], "seq90 review result time")
    independent_at = _parse_time(
        independent["reviewed_at"],
        "seq90 independent review time",
    )
    require(
        result_at > _parse_time(SOURCE_READY_OCCURRED_AT, "seq89 occurred_at")
        and independent_at >= result_at,
        "seq90 reviewer-authored time order differs",
    )
    reviewed_inputs = assignment.get("reviewed_control_inputs")
    require(isinstance(reviewed_inputs, list), "seq90 reviewed input list differs")
    expected_paths = [path.as_posix() for path in REVIEW_INPUT_PATHS]
    require(
        [row.get("path") for row in reviewed_inputs if isinstance(row, dict)]
        == expected_paths,
        "seq90 reviewed control path inventory differs",
    )
    for path, row in zip(REVIEW_INPUT_PATHS, reviewed_inputs, strict=True):
        require(
            isinstance(row, dict)
            and _binding(path, _stable_read(root, path).raw) == row,
            f"seq90 reviewed control input changed: {path}",
        )
    return values, bindings


def _validate_edge_shape(edges: Any) -> dict[str, list[dict[str, Any]]]:
    require(type(edges) is dict and set(edges) == {"modified", "added"}, "noncredit edge set differs")
    modified = edges.get("modified")
    added = edges.get("added")
    require(type(modified) is list and type(added) is list, "noncredit edge rows differ")
    for row in modified:
        require(
            type(row) is dict
            and set(row) == {"path", "predecessor", "successor"}
            and type(row.get("path")) is str
            and row["path"] == row.get("predecessor", {}).get("path")
            and row["path"] == row.get("successor", {}).get("path"),
            "noncredit modified edge differs",
        )
        _binding_from_record(row["predecessor"], "noncredit predecessor")
        _binding_from_record(row["successor"], "noncredit successor")
    for row in added:
        require(
            type(row) is dict
            and set(row) == {"path", "successor"}
            and type(row.get("path")) is str
            and row["path"] == row.get("successor", {}).get("path"),
            "noncredit added edge differs",
        )
        _binding_from_record(row["successor"], "noncredit added successor")
    modified_paths = [row["path"] for row in modified]
    added_paths = [row["path"] for row in added]
    require(
        modified_paths == sorted(modified_paths)
        and added_paths == sorted(added_paths)
        and len(modified_paths) == len(set(modified_paths))
        and len(added_paths) == len(set(added_paths))
        and not (set(modified_paths) & set(added_paths)),
        "noncredit edge inventory order differs",
    )
    return copy.deepcopy(edges)


def _live_successor_bytes_required(history: Sequence[Mapping[str, Any]]) -> bool:
    if len(history) == CONTROL_REANCHOR_SEQUENCE:
        return True
    require(len(history) >= 91, "seq91 active-work anchor is missing")
    control = history[CONTROL_REANCHOR_SEQUENCE - 1]
    started = history[90]
    require(
        type(started) is dict
        and started.get("sequence") == 91
        and started.get("event_id") == STARTED_EVENT_ID
        and started.get("event_type") == "GOAL_STARTED"
        and started.get("subject_goal_id") == GOAL_ID
        and started.get("from_status") == "READY"
        and started.get("to_status") == "IN_PROGRESS"
        and started.get("status_changes") == {GOAL_ID: "IN_PROGRESS"}
        and started.get("previous_event_sha256") == control.get("event_sha256")
        and started.get("event_sha256") == continuation.event_sha256(started),
        "seq91 active-work anchor differs",
    )
    return False


def validated_noncredit_successor_edges(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> dict[str, list[dict[str, Any]]]:
    root = _safe_root(root)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) >= CONTROL_REANCHOR_SEQUENCE,
        "seq90 reanchor is missing",
    )
    event = history[CONTROL_REANCHOR_SEQUENCE - 1]
    require(isinstance(event, dict), "seq90 reanchor event differs")
    require_live_successor = _live_successor_bytes_required(history)
    values, _bindings = _load_physical_review(root)
    assignment = values[REVIEW_ASSIGNMENT_REL]
    edges = _validate_edge_shape(assignment.get("noncredit_successor_edges"))
    require(
        assignment.get("noncredit_successor_edges_sha256") == _edge_set_sha256(edges),
        "reviewed noncredit edge seal differs",
    )
    reviewed = _reviewed_seq87_product_bindings(root)
    gate_dirty, source_head = _seq85_gate_context(root)
    observed_membership: dict[str, bool] = {}
    for row in edges["modified"]:
        path = row["path"]
        require(SOURCE_PRODUCT_MEMBERSHIP.get(path) is True, "modified edge source membership differs")
        expected_predecessor = _source_product_binding(
            root,
            Path(path),
            reviewed=reviewed,
            gate_dirty=gate_dirty,
            source_head=source_head,
        )
        require(
            expected_predecessor == SOURCE_PRODUCT_PREDECESSORS.get(path)
            and row["predecessor"] == expected_predecessor,
            f"modified predecessor differs: {path}",
        )
        if require_live_successor:
            expected_successor = _binding(Path(path), _stable_read(root, Path(path)).raw)
            require(row["successor"] == expected_successor, f"modified successor differs: {path}")
        observed_membership[path] = True
    for row in edges["added"]:
        path = row["path"]
        require(SOURCE_PRODUCT_MEMBERSHIP.get(path) is False, "added edge source membership differs")
        if require_live_successor:
            expected_successor = _binding(Path(path), _stable_read(root, Path(path)).raw)
            require(row["successor"] == expected_successor, f"added successor differs: {path}")
        observed_membership[path] = False
    require(observed_membership == SOURCE_PRODUCT_MEMBERSHIP, "noncredit product edge closure differs")
    require(event.get("noncredit_successor_edges") == edges, "seq90 event noncredit edges differ")
    return edges


def _require_seq90_event(event: Mapping[str, Any], ready: Mapping[str, Any]) -> None:
    require(
        set(event) == EVENT_FIELDS
        and event.get("sequence") == CONTROL_REANCHOR_SEQUENCE
        and event.get("event_id") == CONTROL_REANCHOR_EVENT_ID
        and event.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and event.get("previous_focus_goal_id") == GOAL_ID
        and event.get("focus_goal_id") == GOAL_ID
        and event.get("subject_goal_id") == GOAL_ID
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("static_plan_manifest_sha256") == MANIFEST_SHA256
        and event.get("previous_event_sha256") == SOURCE_TAIL_SHA256
        and event.get("runtime_after") == ready.get("runtime_after")
        and event.get("claim_boundary") == CLAIM_BOUNDARY
        and event.get("source_checkpoint_binding") == _source_checkpoint_binding()
        and event.get("source_ready_event_binding") == _source_ready_event_binding()
        and event.get("event_sha256") == continuation.event_sha256(event),
        "seq90 zero-credit reanchor event differs",
    )


def require_control_reanchored_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> None:
    root = _safe_root(root)
    require(type(checkpoint) is dict, "reanchored checkpoint root differs")
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    require(
        isinstance(history, list) and len(history) >= CONTROL_REANCHOR_SEQUENCE,
        "seq90 reanchor is missing",
    )
    previous = ""
    for sequence, event in enumerate(history[:CONTROL_REANCHOR_SEQUENCE], start=1):
        require(type(event) is dict and event.get("sequence") == sequence, "seq1-90 order differs")
        require(event.get("event_sha256") == continuation.event_sha256(event), "seq1-90 seal differs")
        if sequence > 1:
            require(event.get("previous_event_sha256") == previous, "seq1-90 lineage differs")
        previous = str(event["event_sha256"])
    ready = history[SOURCE_SEQUENCE - 1]
    require(
        ready.get("event_id") == READY_EVENT_ID
        and ready.get("event_sha256") == SOURCE_TAIL_SHA256
        and ready.get("event_type") == "GOAL_READY",
        "seq89 READY anchor differs",
    )
    event = history[CONTROL_REANCHOR_SEQUENCE - 1]
    _require_seq90_event(event, ready)
    _contract, replacement = load_r002_contract(root)
    require(
        event.get("authorization_binding") == authorization_binding(root)
        and event.get("contract_supersession")
        == {
            "previous_contract_binding": _r001_contract_binding(),
            "reason_code": (
                "SEQ89_READY_TRUST_ANCHOR_AND_REVIEWED_NONCREDIT_ROOTS_REQUIRED"
            ),
            "replacement_contract_binding": replacement,
        }
        and event.get("start_gate_runner_binding")
        == _binding(GATE_SCRIPT_REL, _stable_read(root, GATE_SCRIPT_REL).raw),
        "seq90 successor authority differs",
    )
    _values, review = _load_physical_review(root)
    require(event.get("transition_control_review_binding") == review, "seq90 review binding differs")
    validated_noncredit_successor_edges(root, checkpoint)
    for field in ("approved_state", "authority_boundary", "canonical_bindings", "verification_boundary"):
        value = checkpoint.get(field)
        expected_type = list if field == "canonical_bindings" else dict
        require(
            type(value) is expected_type
            and event["unchanged_control_projection"].get(field)
            == continuation.canonical_json_sha256(value),
            f"seq90 credit projection changed: {field}",
        )
    require(
        checkpoint.get("approved_state", {}).get("release_status") == "NOT_ELIGIBLE"
        and checkpoint.get("verification_boundary", {}).get("formal_test_pass_claimed") is False
        and checkpoint.get("verification_boundary", {}).get("release_eligible") is False,
        "seq90 gained verification or release credit",
    )
    if len(history) == CONTROL_REANCHOR_SEQUENCE:
        require(
            state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
            and state.get("goal_status") == "READY"
            and checkpoint.get("current_work", {}).get("status") == "READY"
            and state.get("transition_history_anchor_sha256") == event["event_sha256"],
            "seq90 READY state differs",
        )
        snapshot = checkpoint.get("working_tree_snapshot")
        after = event["repository_context_reanchor"]["after"]
        require(
            isinstance(snapshot, dict)
            and isinstance(snapshot.get("managed_changed_paths"), list)
            and all(type(path) is str for path in snapshot["managed_changed_paths"])
            and snapshot["managed_changed_paths"]
            == sorted(set(snapshot["managed_changed_paths"]))
            and snapshot.get("managed_changed_path_count") == after["managed_changed_path_count"]
            and snapshot.get("path_set_sha256") == after["path_set_sha256"]
            and snapshot.get("content_set_sha256") == after["content_set_sha256"],
            "seq90 final managed snapshot differs",
        )
        require(
            _final_snapshot_hashes(root, tuple(snapshot["managed_changed_paths"]))
            == (after["path_set_sha256"], after["content_set_sha256"]),
            "seq90 final managed snapshot live bytes differ",
        )


def reconstructed_seq90_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    require_control_reanchored_checkpoint(root, checkpoint)
    history = checkpoint["goal_execution"]["transition_history"]
    if len(history) == CONTROL_REANCHOR_SEQUENCE:
        return checkpoint_json_bytes(checkpoint)
    require(len(history) == 91, "checkpoint is not exact seq90 or seq91 tail")
    _live_successor_bytes_required(history)
    reconstructed = copy.deepcopy(checkpoint)
    state = reconstructed["goal_execution"]
    control = state["transition_history"][CONTROL_REANCHOR_SEQUENCE - 1]
    state["transition_history"] = state["transition_history"][:CONTROL_REANCHOR_SEQUENCE]
    state["transition_history_anchor_sha256"] = control["event_sha256"]
    state["validation_cutoff_at"] = control["occurred_at"]
    state["status_by_goal"][GOAL_ID] = "READY"

    current = reconstructed["current_work"]
    current["status"] = "READY"
    current["current_focus"] = SEQ90_CURRENT_FOCUS
    current["release_completion_claimed"] = False

    after = control["repository_context_reanchor"]["after"]
    snapshot = reconstructed["working_tree_snapshot"]
    require(
        len(snapshot["managed_changed_paths"])
        == after["managed_changed_path_count"],
        "seq91 managed inventory cannot reconstruct seq90",
    )
    snapshot["scope"] = FINAL_SCOPE
    snapshot["managed_changed_path_count"] = after["managed_changed_path_count"]
    snapshot["path_set_sha256"] = after["path_set_sha256"]
    snapshot["content_set_sha256"] = after["content_set_sha256"]

    handoff = reconstructed["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(snapshot["managed_changed_paths"])
    mirror = handoff["source_commit_or_snapshot"]
    mirror["file_count"] = after["managed_changed_path_count"]
    mirror["path_set_sha256"] = after["path_set_sha256"]
    mirror["content_set_sha256"] = after["content_set_sha256"]
    handoff["current_epic"] = SEQ90_CURRENT_EPIC
    handoff["last_updated_by_work_item"] = SEQ90_LAST_UPDATED_BY_WORK_ITEM
    handoff["last_verification_status"] = SEQ90_VERIFICATION_STATUS
    handoff["next_single_action"] = SEQ90_NEXT_SINGLE_ACTION

    require(
        continuation.canonical_json_sha256(current)
        == control["unchanged_control_projection"]["current_work"],
        "seq91 current-work cannot reconstruct seq90",
    )
    require_control_reanchored_checkpoint(root, reconstructed)
    return checkpoint_json_bytes(reconstructed)


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(
        path,
        os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_CLOEXEC", 0),
    )
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_checkpoint(
    prepared: Prepared,
    *,
    commit_guard: Callable[[], None] | None = None,
) -> None:
    root = prepared.root
    target = root / CHECKPOINT_REL
    lock_descriptor = os.open(
        target,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
    )
    temporary: Path | None = None
    replaced = False
    replace_attempted = False
    primary: BaseException | None = None
    try:
        fcntl.flock(lock_descriptor, fcntl.LOCK_EX)
        source = _stable_read(root, CHECKPOINT_REL)
        require(
            source.identity == prepared.source_identity
            and source.raw == prepared.source_raw,
            "source changed before seq90 publication",
        )
        _require_inputs_unchanged(root, prepared.retained_inputs)
        _require_managed_inputs_unchanged(root, prepared.managed_inputs)
        _require_managed_inputs_unchanged(
            root,
            prepared.git_visible_inputs,
            label="full Git-visible input",
        )
        _require_git_context(root, prepared.git_head, prepared.git_branch)
        status_raw, _ = capture_git_visible_paths(root)
        require(status_raw == prepared.git_status_raw, "Git-visible cohort changed before publication")
        descriptor, name = tempfile.mkstemp(
            dir=target.parent,
            prefix=".walksafe-fp048-r002-seq90-write.",
            suffix=".json",
        )
        temporary = Path(name)
        os.fchmod(descriptor, stat.S_IMODE(target.lstat().st_mode))
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(prepared.projected_raw)
            stream.flush()
            os.fsync(stream.fileno())
        source = _stable_read(root, CHECKPOINT_REL)
        require(
            source.identity == prepared.source_identity
            and source.raw == prepared.source_raw,
            "source changed at seq90 commit point",
        )
        _require_inputs_unchanged(root, prepared.retained_inputs)
        _require_managed_inputs_unchanged(root, prepared.managed_inputs)
        _require_managed_inputs_unchanged(
            root,
            prepared.git_visible_inputs,
            label="full Git-visible input",
        )
        _require_git_context(root, prepared.git_head, prepared.git_branch)
        status_raw, _ = capture_git_visible_paths(root)
        if status_raw != prepared.git_status_raw:
            status_raw = _without_ephemeral_status_record(
                status_raw,
                temporary.relative_to(root),
            )
        require(
            status_raw == prepared.git_status_raw,
            "Git-visible cohort changed at seq90 commit point",
        )
        if commit_guard is not None:
            commit_guard()
        replace_attempted = True
        os.replace(temporary, target)
        temporary = None
        replaced = True
        _fsync_directory(target.parent)
        published = _stable_read(root, CHECKPOINT_REL).raw
        if published != prepared.projected_raw:
            raise PostcommitUncertain("published seq90 checkpoint bytes are uncertain")
    except PostcommitUncertain as exc:
        primary = exc
        raise
    except BaseException as exc:
        if replace_attempted:
            uncertain = PostcommitUncertain(
                "seq90 publication completed with uncertain reporting"
            )
            primary = uncertain
            raise uncertain from exc
        primary = exc
        raise
    finally:
        cleanup_error: BaseException | None = None
        try:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
        except BaseException as exc:
            cleanup_error = exc
        try:
            fcntl.flock(lock_descriptor, fcntl.LOCK_UN)
        except BaseException as exc:
            cleanup_error = cleanup_error or exc
        try:
            os.close(lock_descriptor)
        except BaseException as exc:
            cleanup_error = cleanup_error or exc
        if cleanup_error is not None:
            if replace_attempted or replaced:
                raise PostcommitUncertain(
                    "seq90 publication cleanup is uncertain after replace attempt"
                ) from cleanup_error
            if primary is None:
                raise cleanup_error


def prepare_review_manifest(root: Path = ROOT) -> dict[str, Any]:
    root = _safe_root(root)
    source_raw, source = load_exact_source_checkpoint(root)
    del source_raw
    assignment = build_review_assignment(root, source)
    return {
        "authorization": _binding(AUTHORIZATION_REL, _stable_read(root, AUTHORIZATION_REL).raw),
        "candidate_review_assignment": _binding(REVIEW_ASSIGNMENT_REL, assignment),
        "noncredit_successor_edges": compute_noncredit_successor_edges(root, source),
        "reviewer_authored_paths": [
            REVIEW_RESULT_REL.as_posix(),
            INDEPENDENT_REVIEW_REL.as_posix(),
        ],
        "round_id": ROUND_ID,
        "source_checkpoint_binding": _source_checkpoint_binding(),
        "status": "PREPARED_NOT_PUBLISHED",
    }


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
    published = False
    try:
        if args.prepare_review:
            manifest = prepare_review_manifest(args.root)
            sys.stdout.buffer.write(canonical_json_bytes(manifest))
            return 0
        prepared = prepare(args.root, occurred_at=args.occurred_at)
        if args.write:
            write_checkpoint(prepared)
            published = True
        message = (
            "FP048-R002 start-control reanchor seq90: PASS "
            f"event_sha256={prepared.event['event_sha256']}\n"
        ).encode("utf-8")
        try:
            sys.stdout.buffer.write(message)
            sys.stdout.buffer.flush()
        except BaseException as exc:
            if published:
                raise PostcommitUncertain("published seq90 PASS output is uncertain") from exc
            raise
    except PostcommitUncertain as exc:
        print(f"FP048-R002 start-control reanchor seq90: POSTCOMMIT-UNCERTAIN: {exc}", file=sys.stderr)
        return 2
    except (ControlReanchorError, OSError, subprocess.SubprocessError, TypeError, ValueError) as exc:
        print(f"FP048-R002 start-control reanchor seq90: FAIL: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
