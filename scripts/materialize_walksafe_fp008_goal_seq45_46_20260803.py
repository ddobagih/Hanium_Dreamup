#!/usr/bin/env python3
"""Preflight the add-only FP-008 Goal materialization and READY projection.

This tool never writes the live checkpoint.  It accepts only the byte-exact
seq44 checkpoint and builds seq45/46 in memory for validation.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import stat
import sys
from pathlib import Path
from typing import Any, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as contract


CHECKPOINT = Path("docs/control/walksafe-project-continuation-checkpoint.json")
SOURCE_CHECKPOINT_SHA256 = (
    "093fe8cdb96cf82ba72b871da91a981795b9baefdf7752d9306244bcebbac6b2"
)
SOURCE_CHECKPOINT_BYTE_COUNT = 1_461_025
SOURCE_TAIL_SHA256 = (
    "ad4addfbb44baa7a33e9e6640f591f20bbce22397fb3a120e26b8844a3f8b665"
)
SOURCE_SEQUENCE = 44
READY_SEQUENCE = 46
SOURCE_HISTORY_PREFIX_SHA256 = (
    "1de115133b6f0d8c5f534d17f99b044439d4b5ef69a02a332a3021f097211bf3"
)
SOURCE_VERIFICATION_BOUNDARY_SHA256 = (
    "ce630d0f71a0e259894b56a100e9132ea8043aba0745d39489fffd485e0706c1"
)
SOURCE_COMPLETION_EVIDENCE_SHA256 = (
    "67eae6b182bdf1c7e49df21b25bb1cf95247b6db5406f8c43394582febe950cb"
)
SOURCE_ARCHIVED_COMPLETION_EVIDENCE_SHA256 = (
    "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
)
SOURCE_VERIFICATION_REFS_SHA256 = (
    "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945"
)
READY_CHECKPOINT_SEMANTIC_SHA256 = (
    "7a83803fd3a043cbfa6171309f9e6c52f3ed5abca44aac4f1758fd9d75bdfa30"
)
_WORKING_CONTENT_NORMALIZATION = "__FP008_CONTROLLED_WORKING_CONTENT_SHA256__"

GOAL_ID = "WS-GOAL-EPIC-03-FP-008-R001"
GOAL_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/"
    "epic-03/epic-03-fp008-admin-review-delivery-r001.md"
)
GOAL_SHA256 = (
    "2654fe5f595aefaecf974a4de70bad7946c2385cba9716200f2d2f49d849dd8e"
)
WORK_ITEM_ID = "EPIC-03-FP008-ADMIN-REVIEW-DELIVERY"
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
PARENT_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-03-account-admin-security.md"
)
PARENT_GOAL_SHA256 = (
    "7d2dfb7fe89bd30fc1f6113acb0b533a9fd91a6106cc2ab4798091763beae832"
)
PREDECESSOR_GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R001"
PREDECESSOR_GOAL_SHA256 = (
    "043b5a463914015a5918b885c3f69c242ece190358bd18ab1f884de46f553740"
)
PREDECESSOR_COMPLETION_EVENT_SHA256 = SOURCE_TAIL_SHA256

BACKLOG_PATH = (
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260802-r023.json"
)
BACKLOG_ID = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260802-023"
BACKLOG_SHA256 = (
    "eabd987cff1086c6a45b9b6eee9166213727ab59d63cd5b7075da01e27651021"
)
GAP_PATH = (
    "docs/control/audits/"
    "walksafe-implementation-gap-analysis-20260802-r023.json"
)
GAP_ID = "WS-IMPLEMENTATION-GAP-ANALYSIS-20260802-023"
GAP_SHA256 = (
    "34d4b8a4a05ac346e48293344dbf17e10cb65a792e85c5df42f69a7d63c09f82"
)

MATERIALIZED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-FP008-20260803-001"
)
READY_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP008-20260803-001"
MATERIALIZED_AT = "2026-08-03T13:20:00+09:00"
READY_AT = "2026-08-03T13:20:01+09:00"

START_GATE_CONTRACT_PATH = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-008-R001/"
    "initial-start-gate-contract-r001.json"
)
START_GATE_CONTRACT_DOCUMENT_ID = (
    "WS-FP008-INITIAL-START-GATE-CONTRACT-20260803-001"
)
START_GATE_CONTRACT_ID = "WS-FP008-INTERNAL-START-GATE-R001"
START_GATE_CONTRACT_VERSION = "2026-08-03.1"
START_GATE_CONTRACT_FILE_SHA256 = (
    "ef6a9a555bbc46375d655c1d1d6a79ada715b02b164d01b80d5d5a5823d15599"
)
START_GATE_CONTRACT_CANONICAL_SHA256 = (
    "b3498acc49f388b9b17f3739ce7e895d33e040c1482386144c0bf33b5241f220"
)
START_GATE_CHECK_IDS = (
    "CONTINUATION",
    "V24_ARTIFACT_WORK_QUEUE",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "BACKEND_TEST_DATABASE_PREFLIGHT",
    "BACKEND_ADMIN_REPORTS_OPENAPI_POSTGRES",
    "ANDROID_USER_INTERNAL",
    "ANDROID_ADMIN_INTERNAL",
    "ROOT_FP008_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)

SOURCE_WORKING_SNAPSHOT_PATH_SET_SHA256 = (
    "1b86c51ea2070f645ad5de4944f944f618dc1c35460c0ba807e37b2186c26c09"
)
SOURCE_WORKING_SNAPSHOT_CONTENT_SET_SHA256 = (
    "d869dc30e621b0a60db4dfe301b0f40de972747dd666cfa6774a1b1ca4e1ed69"
)
AUTHORIZED_SOURCE_DELTAS = (
    {
        "path": "backend/tests/conftest.py",
        "before_sha256": "68b29a7706d5b474010829da88697f37252843c3673ff1705f088be8ad017055",
        "after_sha256": "c5c38fbe9e6689fa1d48e3c0bbfa8fcb592250c65d8a8412f18dccaf73565cb0",
        "reason": "FP008 dedicated PostgreSQL fixture must truncate report FK dependents together",
    },
    {
        "path": "scripts/check_walksafe_project_continuation_v2_4.py",
        "before_sha256": "a9fca00ff58a8f30c73ea572d9e2418b1524a09b44467b27d7e5c4b3b51c91d3",
        "after_sha256": "8df411669773e614a6b66b38ba59b5649614931a14849d791321768d238e2364",
        "reason": "FP008 target-scoped start-gate receipt validation while preserving FP048 replay",
    },
    {
        "path": "scripts/run_walksafe_test_layers_20260711.sh",
        "before_sha256": "d5fee09a882a0e72f15317a87d61ebc2ebd88b4f480f0d8312f1704322ec26fe",
        "after_sha256": "993fd8edb96c69aa2e7f35cc0989ec2664d7a5ce1a3b3f7dc41a33558a263108",
        "reason": "FP048 and FP008 active control regressions must remain explicitly inventoried",
    },
    {
        "path": "tests/test_walksafe_project_continuation_v2_4.py",
        "before_sha256": "ce3c76b94a3b4360c3961704fdef82ccedcb2c08c4c11dccaf383974f8d0afc0",
        "after_sha256": "92f9a6fea8a57e3e1ae846ae1fa1ef7966b255f88e88fbde06fa46c8d42ca234",
        "reason": "FP008 receipt forward compatibility and FP048 19-check backward compatibility",
    },
)

EXPECTED_QUEUE_SHA256 = (
    "d628cd2b5767d2f30a919770e71ed69eb5b9138cd1ae39d5caf15a964edce04e"
)
EXPECTED_MATERIALIZED_BOUNDARY_SHA256 = (
    "787c2e044b38bdba0852a34fad8e4375a22406598bce2f9511abafab380dc74b"
)
EXPECTED_READY_BOUNDARY_SHA256 = (
    "3cf29ba9c32ccfeeaa4881ddd6b14b20538e340b01f2725658efc8fbde521bed"
)
EXPECTED_MATERIALIZED_EVENT_SHA256 = (
    "11e8328e2a08bc7e5facc2e95e1aba47d754a7cf3e12a9ac5055bd283861a260"
)
EXPECTED_READY_EVENT_SHA256 = (
    "c34c4ab98cba1ff9a26ddb0d86b65f20b8f5cf53f9082543d4fa05881597c07d"
)
EXPECTED_MATERIALIZED_EVENT_FIELDS = frozenset(
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
        "from_status",
        "to_status",
        "static_plan_manifest_sha256",
        "status_changes",
        "runtime_after",
        "blockers_after",
        "blocker_resolution_ids_after",
        "source_checkpoint_version",
        "evidence_refs",
        "materialized_goal_id",
        "materialized_goal_path",
        "materialized_goal_content_sha256",
        "materialized_from_role",
        "materialized_from_path",
        "materialized_from_document_id",
        "materialized_from_sha256",
        "predecessor_goal_id",
        "predecessor_goal_content_sha256",
        "supersedes_goal_id",
        "supersedes_goal_content_sha256",
        "artifact_work_reason",
        "artifact_trigger_evidence_refs",
        "artifact_trigger_evidence_bindings",
        "source_working_snapshot_reconciliation",
        "previous_event_sha256",
        "canonical_binding_snapshot_after",
        "event_sha256",
    }
)
EXPECTED_READY_EVENT_FIELDS = frozenset(
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
        "from_status",
        "to_status",
        "static_plan_manifest_sha256",
        "status_changes",
        "runtime_after",
        "blockers_after",
        "blocker_resolution_ids_after",
        "source_checkpoint_version",
        "evidence_refs",
        "subject_goal_id",
        "readiness_basis",
        "start_evidence_bindings",
        "start_evidence_provenance",
        "implementation_start_gate_contract_binding",
        "dynamic_goal_inventory_after",
        "materialized_child_goal_ids_by_parent_after",
        "previous_event_sha256",
        "canonical_binding_snapshot_after",
        "event_sha256",
    }
)
MATERIALIZED_FRONTIER = [PARENT_GOAL_ID, "WS-GOAL-EPIC-12"]
READY_FRONTIER = [GOAL_ID, PARENT_GOAL_ID, "WS-GOAL-EPIC-12"]
SOURCE_PATHS = (
    GOAL_PATH.as_posix(),
    START_GATE_CONTRACT_PATH.as_posix(),
    "apps/android/gradle/wrapper/gradle-wrapper.jar",
    "backend/tests/conftest.py",
    "scripts/apply_walksafe_fp048_goal_completed_seq43_44_20260802.py",
    "scripts/apply_walksafe_fp008_goal_seq45_46_20260803.py",
    "scripts/check_walksafe_project_continuation_v2_4.py",
    "scripts/materialize_walksafe_fp008_goal_seq45_46_20260803.py",
    "scripts/run_walksafe_fp008_goal_start_gate_20260803.py",
    "scripts/run_walksafe_test_layers_20260711.sh",
    "tests/test_walksafe_fp008_goal_seq45_46_20260803.py",
    "tests/test_walksafe_fp008_goal_start_gate_20260803.py",
    "tests/test_walksafe_project_continuation_v2_4.py",
)
READY_FROZEN_SOURCE_SHA256 = {
    "apps/android/gradle/wrapper/gradle-wrapper.jar": (
        "b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13"
    ),
    "backend/tests/conftest.py": (
        "c5c38fbe9e6689fa1d48e3c0bbfa8fcb592250c65d8a8412f18dccaf73565cb0"
    ),
    "scripts/apply_walksafe_fp048_goal_completed_seq43_44_20260802.py": (
        "273e8f62472a326b07a4af52da5bce310b188604e6bf944814f6ca8d24d82752"
    ),
    "scripts/apply_walksafe_fp008_goal_seq45_46_20260803.py": (
        "7016d10d754eccbb8faab21a06b745a8507705aca91abe683c22aa3269ffeadd"
    ),
    "scripts/check_walksafe_project_continuation_v2_4.py": (
        "8df411669773e614a6b66b38ba59b5649614931a14849d791321768d238e2364"
    ),
    "scripts/run_walksafe_fp008_goal_start_gate_20260803.py": (
        "13b581e74543fe2d1e4a6f6bc4b0964f09c6e992e70a549276ae9b280366fa42"
    ),
    "scripts/run_walksafe_test_layers_20260711.sh": (
        "993fd8edb96c69aa2e7f35cc0989ec2664d7a5ce1a3b3f7dc41a33558a263108"
    ),
    "tests/test_walksafe_fp008_goal_seq45_46_20260803.py": (
        "ee97bb82552f4fc509cec641a7f07adb81ea62d838b0a1379c5b32faf12e3707"
    ),
    "tests/test_walksafe_fp008_goal_start_gate_20260803.py": (
        "eda256efdea272e16817e3e512620dd732e2c38cae736eaf1f7be17c54f64fbf"
    ),
    "tests/test_walksafe_project_continuation_v2_4.py": (
        "92f9a6fea8a57e3e1ae846ae1fa1ef7966b255f88e88fbde06fa46c8d42ca234"
    ),
}
READY_CONTROL_SUCCESSOR_SHA256 = {
    "scripts/apply_walksafe_fp008_goal_seq45_46_20260803.py": (
        "ac7a9a1344f1bfcbf1a6f60a8f1675e91046a9e6eec4ffa7839c7d50108a7fd8"
    ),
    "scripts/check_walksafe_project_continuation_v2_4.py": (
        "09dcc2e7a16ab964838e5fe259f56f0be5a94f52391959a1945692aa101caa4f"
    ),
    "scripts/run_walksafe_test_layers_20260711.sh": (
        "0d413e7297caaced6d4cbb9739c44877b6995a8202a089461cacb263cd441ae3"
    ),
    "scripts/run_walksafe_fp008_goal_start_gate_20260803.py": (
        "8d48b7685e0d8809c2982007218189a019745b3f0d7e614dee78f08c44805430"
    ),
    "tests/test_walksafe_fp008_goal_seq45_46_20260803.py": (
        "41b4fd8e455e1ee9d973afb157b35d41e236fb5122206d4afb5e37244fd04367"
    ),
    "tests/test_walksafe_project_continuation_v2_4.py": (
        "e5822ad1a99f63dc7989f595b4b7c50e28984ff984e0c15bfb3815566504ce7e"
    ),
    "tests/test_walksafe_fp008_goal_start_gate_20260803.py": (
        "c84914444ae5bbe34ed794f5da48089b3f3332fa30fdcdf2b9e33c18d8fdcb75"
    ),
}
EXACT_SEQ47_SUCCESSOR_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-002"
)
EXACT_SEQ47_SUCCESSOR_EVENT_SHA256 = (
    "82ad77e33eaa55530f53f5ee315807ef66e21fbfc8be2511b004abe99505db90"
)
EXACT_SEQ47_SOURCE_CHECKPOINT_SHA256 = (
    "fcef757fced84595c7150baf4dc4641f4f20d3b9819a4406f67e9274d0d14eca"
)
READY_MANAGED_PATH_COUNT = 617
READY_MANAGED_PATH_SET_SHA256 = (
    "8ed473ae6a2f1e7b72f2f2afab7f86091509f1d562b5ebb509345963d654c993"
)
READY_MANAGED_CONTENT_SET_SHA256 = (
    "398014015fecbf682a92ccdf913da37518edf9bde5f2c9176c764db03a6055d2"
)
SEQ47_REQUIRED_CONTROL_PATHS = frozenset(
    {
        "scripts/apply_walksafe_fp008_goal_started_seq47_20260803.py",
        "tests/test_walksafe_fp008_goal_started_seq47_20260803.py",
    }
)
SESSION_POST_READY_CONTROL_PATHS = frozenset(
    {
        "scripts/apply_walksafe_fp008_goal_started_seq47_20260803.py",
        "scripts/apply_walksafe_fp008_work_session_resumed_seq48_20260809.py",
        "scripts/reconcile_walksafe_fp008_session_snapshot_seq47_20260808.py",
        "scripts/run_walksafe_fp008_session_resume_gate_20260809.py",
        "tests/test_walksafe_fp008_goal_started_seq47_20260803.py",
        "tests/test_walksafe_fp008_session_resume_gate_20260809.py",
        "tests/test_walksafe_fp008_session_snapshot_reconcile_20260808.py",
        "tests/test_walksafe_fp008_work_session_resumed_seq48_20260809.py",
    }
)
ISOLATED_SNAPSHOT_FIX_CONTROL_GENERATIONS = (
    frozenset(
        {
            "scripts/reconcile_walksafe_fp008_isolated_snapshot_fix_seq47a_20260809.py",
            "tests/test_walksafe_fp008_isolated_snapshot_fix_reconcile_20260809.py",
        }
    ),
    frozenset(
        {
            "scripts/reconcile_walksafe_fp008_isolated_snapshot_fix_seq47b_20260809.py",
            "tests/test_walksafe_fp008_isolated_snapshot_fix_reconcile_seq47b_20260809.py",
        }
    ),
    frozenset(
        {
            "scripts/reconcile_walksafe_fp008_isolated_snapshot_fix_seq47c_20260809.py",
            "tests/test_walksafe_fp008_isolated_snapshot_fix_reconcile_seq47c_20260809.py",
        }
    ),
)
POST_READY_CONTROL_PATHS = set(SESSION_POST_READY_CONTROL_PATHS)
for _generation_paths in ISOLATED_SNAPSHOT_FIX_CONTROL_GENERATIONS:
    POST_READY_CONTROL_PATHS.update(_generation_paths)


class ProjectionError(RuntimeError):
    """The exact seq44 source cannot safely produce the FP-008 candidate."""


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ProjectionError(message)


def _content_set_sha256_from_digests(
    paths: list[str],
    digests: dict[str, str],
) -> str:
    value = hashlib.sha256()
    for relative in sorted(paths):
        value.update(relative.encode("utf-8"))
        value.update(b"\0")
        value.update(digests[relative].encode("ascii"))
        value.update(b"\n")
    return value.hexdigest()


def require_authorized_source_delta(
    root: Path,
    source: dict[str, Any],
) -> dict[str, Any]:
    """Prove that every post-seq44 controlled byte change is enumerated."""

    snapshot = source.get("working_tree_snapshot")
    _require(isinstance(snapshot, dict), "source working snapshot is missing")
    paths = snapshot.get("managed_changed_paths")
    _require(
        isinstance(paths, list)
        and len(paths) == 608
        and len(set(paths)) == 608
        and all(isinstance(path, str) for path in paths),
        "source managed path inventory differs",
    )
    normalized = sorted(paths)
    path_set_sha256 = hashlib.sha256(
        ("\n".join(normalized) + "\n").encode("utf-8")
    ).hexdigest()
    _require(
        path_set_sha256 == SOURCE_WORKING_SNAPSHOT_PATH_SET_SHA256
        and snapshot.get("path_set_sha256") == path_set_sha256,
        "source working snapshot path set differs",
    )
    _require(
        snapshot.get("content_set_sha256")
        == SOURCE_WORKING_SNAPSHOT_CONTENT_SET_SHA256,
        "source working snapshot authority differs",
    )

    actual: dict[str, str] = {}
    for relative in normalized:
        relative_path = Path(relative)
        _require(
            not relative_path.is_absolute() and ".." not in relative_path.parts,
            f"source managed path is unsafe: {relative}",
        )
        path = root / relative_path
        _require(
            not path.is_symlink() and path.is_file(),
            f"source managed path is missing or unsafe: {relative}",
        )
        actual[relative] = contract.sha256_file(path)

    authorized = {item["path"]: item for item in AUTHORIZED_SOURCE_DELTAS}
    _require(
        len(authorized) == len(AUTHORIZED_SOURCE_DELTAS)
        and set(authorized).issubset(actual),
        "authorized source delta inventory differs",
    )
    for relative, item in authorized.items():
        before = item["before_sha256"]
        after = item["after_sha256"]
        _require(
            isinstance(before, str)
            and isinstance(after, str)
            and len(before) == 64
            and len(after) == 64
            and all(character in "0123456789abcdef" for character in before + after),
            f"authorized source delta digest is not finalized: {relative}",
        )
        _require(actual[relative] == after, f"authorized source delta differs: {relative}")
        _require(before != after, f"authorized source delta is not a change: {relative}")

    reconstructed = copy.deepcopy(actual)
    for relative, item in authorized.items():
        reconstructed[relative] = item["before_sha256"]
    _require(
        _content_set_sha256_from_digests(normalized, reconstructed)
        == SOURCE_WORKING_SNAPSHOT_CONTENT_SET_SHA256,
        "unlisted controlled-tree drift exists after seq44",
    )
    return {
        "source_path_set_sha256": SOURCE_WORKING_SNAPSHOT_PATH_SET_SHA256,
        "source_content_set_sha256": SOURCE_WORKING_SNAPSHOT_CONTENT_SET_SHA256,
        "verified_current_content_set_sha256": _content_set_sha256_from_digests(
            normalized,
            actual,
        ),
        "authorized_changed_paths": copy.deepcopy(list(AUTHORIZED_SOURCE_DELTAS)),
    }


def load_start_gate_contract(root: Path) -> dict[str, Any]:
    path = root / START_GATE_CONTRACT_PATH
    _require(not path.is_symlink() and path.is_file(), "FP008 start-gate contract is missing or unsafe")
    _require(path.stat().st_nlink == 1, "FP008 start-gate contract must have one hard link")
    raw = path.read_bytes()
    _require(
        sha256_bytes(raw) == START_GATE_CONTRACT_FILE_SHA256,
        "FP008 start-gate contract file SHA-256 differs",
    )
    try:
        value = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProjectionError("FP008 start-gate contract is not valid JSON") from exc
    _require(isinstance(value, dict), "FP008 start-gate contract root differs")
    _require(
        contract.canonical_json_sha256(value)
        == START_GATE_CONTRACT_CANONICAL_SHA256,
        "FP008 start-gate canonical SHA-256 differs",
    )
    _require(
        set(value)
        == {
            "schema_version",
            "document_id",
            "contract_id",
            "contract_version",
            "target_goal_id",
            "target_goal_content_sha256",
            "gate_purpose",
            "ordered_checks",
        },
        "FP008 start-gate contract field set differs",
    )
    _require(
        value.get("schema_version") == "1.0"
        and value.get("document_id") == START_GATE_CONTRACT_DOCUMENT_ID
        and value.get("contract_id") == START_GATE_CONTRACT_ID
        and value.get("contract_version") == START_GATE_CONTRACT_VERSION
        and value.get("target_goal_id") == GOAL_ID
        and value.get("target_goal_content_sha256") == GOAL_SHA256
        and value.get("gate_purpose") == "INITIAL_START",
        "FP008 start-gate contract identity differs",
    )
    checks = value.get("ordered_checks")
    _require(
        isinstance(checks, list)
        and len(checks) == len(START_GATE_CHECK_IDS)
        and all(
            isinstance(item, dict)
            and set(item) == {"check_id", "command"}
            and isinstance(item.get("check_id"), str)
            and isinstance(item.get("command"), str)
            and item["command"]
            for item in checks
        ),
        "FP008 start-gate ordered check records differ",
    )
    _require(
        tuple(item["check_id"] for item in checks) == START_GATE_CHECK_IDS,
        "FP008 start-gate ordered check IDs differ",
    )
    forbidden = (
        "apps/web",
        "npm ",
        "npx ",
        "connectedDebugAndroidTest",
        " adb ",
        "validate_walksafe_full_rc_20260713.py",
        "build_walksafe_full_rc_20260713.py",
    )
    _require(
        not any(token in item["command"] for item in checks for token in forbidden),
        "FP008 start-gate contract contains a forbidden legacy, Web, or device command",
    )
    return value


def start_gate_contract_binding() -> dict[str, str]:
    return {
        "schema_version": "1.0",
        "document_id": START_GATE_CONTRACT_DOCUMENT_ID,
        "path": START_GATE_CONTRACT_PATH.as_posix(),
        "file_sha256": START_GATE_CONTRACT_FILE_SHA256,
        "contract_id": START_GATE_CONTRACT_ID,
        "contract_version": START_GATE_CONTRACT_VERSION,
        "canonical_contract_sha256": START_GATE_CONTRACT_CANONICAL_SHA256,
    }


def load_exact_source(root: Path) -> tuple[bytes, dict[str, Any]]:
    path = root / CHECKPOINT
    _require(not path.is_symlink() and path.is_file(), "checkpoint is missing or unsafe")
    metadata = path.stat()
    _require(stat.S_IMODE(metadata.st_mode) == 0o600, "checkpoint mode is not 0600")
    source_bytes = path.read_bytes()
    _require(
        len(source_bytes) == SOURCE_CHECKPOINT_BYTE_COUNT,
        "checkpoint byte count differs from seq44",
    )
    _require(
        sha256_bytes(source_bytes) == SOURCE_CHECKPOINT_SHA256,
        "checkpoint SHA-256 differs from seq44",
    )
    try:
        source = json.loads(source_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProjectionError("checkpoint is not valid JSON") from exc
    _require(isinstance(source, dict), "checkpoint root is not an object")
    require_exact_source(source)
    return source_bytes, source


def require_exact_source(source: dict[str, Any]) -> None:
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    statuses = state.get("status_by_goal") if isinstance(state, dict) else None
    _require(source.get("schema_version") == "1.25.0", "source schema differs")
    _require(isinstance(history, list) and len(history) == SOURCE_SEQUENCE, "source history differs")
    tail = history[-1] if isinstance(history, list) and history else {}
    _require(
        isinstance(tail, dict)
        and tail.get("sequence") == SOURCE_SEQUENCE
        and tail.get("event_type") == "GOAL_COMPLETED"
        and tail.get("subject_goal_id") == PREDECESSOR_GOAL_ID
        and tail.get("event_sha256") == SOURCE_TAIL_SHA256,
        "source tail is not the exact FP048 completion",
    )
    _require(
        state.get("transition_history_anchor_sha256") == SOURCE_TAIL_SHA256,
        "source history anchor differs",
    )
    _require(
        state.get("focus_goal_id") == PARENT_GOAL_ID
        and state.get("focus_goal_path") == PARENT_GOAL_PATH
        and state.get("focus_work_item_id") == ""
        and state.get("focus_source") == "WORKSTREAM_GRAPH",
        "source focus is not the EPIC-03 workstream",
    )
    _require(
        isinstance(statuses, dict)
        and statuses.get(PREDECESSOR_GOAL_ID) == "COMPLETE_AT_TARGET"
        and statuses.get(PARENT_GOAL_ID) == "READY"
        and GOAL_ID not in statuses
        and "IN_PROGRESS" not in statuses.values(),
        "source Goal status boundary differs",
    )
    _require(
        state.get("ready_frontier_goal_ids") == MATERIALIZED_FRONTIER,
        "source ready frontier differs",
    )
    _require(
        state.get("blocked_goal_ids") == []
        and state.get("pending_questions") == []
        and state.get("open_question_count") == 0,
        "source has an unresolved blocker or question",
    )
    _require(
        state.get("activation_status") == "ACTIVE"
        and state.get("package_status") == "ACTIVE",
        "source package is not active",
    )
    current = source.get("current_work")
    _require(
        isinstance(current, dict)
        and current.get("work_item_id") == WORK_ITEM_ID
        and current.get("current_focus")
        == "FP048/GAP-057 COMPLETE_AT_TARGET; FP008/GAP-017 PLANNED_NEXT",
        "source current-work pointer differs",
    )


def require_static_inputs(root: Path, source: dict[str, Any]) -> None:
    for relative, digest, label in (
        (GOAL_PATH.as_posix(), GOAL_SHA256, "Goal"),
        (BACKLOG_PATH, BACKLOG_SHA256, "backlog r023"),
        (GAP_PATH, GAP_SHA256, "gap r023"),
    ):
        path = root / relative
        _require(not path.is_symlink() and path.is_file(), f"{label} is missing or unsafe")
        _require(contract.sha256_file(path) == digest, f"{label} SHA-256 differs")

    load_start_gate_contract(root)

    node, _ = goal_graph.frozen_goal.parse_goal(root / GOAL_PATH)
    expected = {
        "goal_id": GOAL_ID,
        "goal_kind": "WORK_ITEM",
        "parent_goal_id": PARENT_GOAL_ID,
        "work_item_type": "POLICY_GAP_WORK",
        "priority_rank": 20,
        "initial_status": "PLANNED",
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "work_item_id": WORK_ITEM_ID,
        "start_requires": [PREDECESSOR_GOAL_ID],
        "completion_requires": [PREDECESSOR_GOAL_ID],
        "source_policy_ids": ["FP-008"],
        "gap_ids": ["GAP-017"],
        "materialized_from_role": "IMPLEMENTATION_BACKLOG",
        "materialized_from_path": BACKLOG_PATH,
        "materialized_from_document_id": BACKLOG_ID,
        "materialized_from_sha256": BACKLOG_SHA256,
        "predecessor_goal_id": PREDECESSOR_GOAL_ID,
        "predecessor_goal_content_sha256": PREDECESSOR_GOAL_SHA256,
    }
    _require(
        all(node.get(key) == value for key, value in expected.items()),
        "FP008 Goal metadata differs",
    )
    snapshot = contract.canonical_binding_snapshot(source)
    _require(len(snapshot) == 39, "canonical binding count differs")
    _require(
        snapshot.get("IMPLEMENTATION_BACKLOG")
        == {
            "role": "IMPLEMENTATION_BACKLOG",
            "document_id": BACKLOG_ID,
            "path": BACKLOG_PATH,
            "file_sha256": BACKLOG_SHA256,
        },
        "canonical backlog binding differs",
    )
    _require(
        snapshot.get("IMPLEMENTATION_GAP")
        == {
            "role": "IMPLEMENTATION_GAP",
            "document_id": GAP_ID,
            "path": GAP_PATH,
            "file_sha256": GAP_SHA256,
        },
        "canonical gap binding differs",
    )


def _runtime_projection(
    state: dict[str, Any],
    *,
    focus_goal_id: str,
    focus_goal_path: str,
    focus_work_item_id: str,
    focus_source: str,
    frontier: list[str],
    queue: dict[str, Any],
    boundary: dict[str, Any],
) -> dict[str, Any]:
    return {
        "focus_goal_id": focus_goal_id,
        "focus_goal_path": focus_goal_path,
        "focus_work_item_id": focus_work_item_id,
        "focus_source": focus_source,
        "ready_frontier_goal_ids": copy.deepcopy(frontier),
        "blocked_goal_ids": copy.deepcopy(state["blocked_goal_ids"]),
        "pending_questions": copy.deepcopy(state["pending_questions"]),
        "open_question_count": state["open_question_count"],
        "artifact_work_queue_sha256": contract.canonical_json_sha256(queue),
        "completion_boundary_sha256": contract.canonical_json_sha256(boundary),
        "activation_status": state["activation_status"],
        "package_status": state["package_status"],
    }


def _derive_queue_and_boundary(
    root: Path,
    checkpoint: dict[str, Any],
    frontier: list[str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    state = checkpoint["goal_execution"]
    bindings = goal_graph.frozen_goal.canonical_binding_map(checkpoint)
    register_binding = bindings.get("ARTIFACT_REGISTER")
    _require(isinstance(register_binding, dict), "ARTIFACT_REGISTER binding is missing")
    register = contract.load_json(root / register_binding["path"])
    node_errors, nodes = goal_graph.frozen_goal.current_goal_nodes(root, state)
    _require(not node_errors, "Goal node derivation failed: " + "; ".join(node_errors))
    queue_errors, queue = goal_graph.derive_v24_artifact_work_queue_from_register(
        register_binding,
        register,
        nodes,
        state["status_by_goal"],
    )
    _require(not queue_errors, "artifact queue derivation failed: " + "; ".join(queue_errors))
    boundary_errors, boundary = goal_graph.frozen_goal.derive_completion_boundary(
        nodes,
        state["status_by_goal"],
        frontier,
        state["blockers_by_goal"],
        queue,
        package_status=state["package_status"],
    )
    _require(
        not boundary_errors,
        "completion boundary derivation failed: " + "; ".join(boundary_errors),
    )
    return queue, boundary


def _inventory_record(materialized_event_sha256: str) -> dict[str, Any]:
    return {
        "goal_id": GOAL_ID,
        "path": GOAL_PATH.as_posix(),
        "sha256": GOAL_SHA256,
        "goal_kind": "WORK_ITEM",
        "work_item_type": "POLICY_GAP_WORK",
        "parent_goal_id": PARENT_GOAL_ID,
        "materialized_from_role": "IMPLEMENTATION_BACKLOG",
        "materialized_from_path": BACKLOG_PATH,
        "materialized_from_document_id": BACKLOG_ID,
        "materialized_from_sha256": BACKLOG_SHA256,
        "predecessor_goal_id": PREDECESSOR_GOAL_ID,
        "predecessor_goal_content_sha256": PREDECESSOR_GOAL_SHA256,
        "supersedes_goal_id": "",
        "supersedes_goal_content_sha256": "",
        "artifact_work_reason": "",
        "artifact_trigger_evidence_refs": [],
        "initial_status": "PLANNED",
        "materialized_event_sha256": materialized_event_sha256,
    }


def project(root: Path, source: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    require_exact_source(source)
    require_static_inputs(root, source)
    source_delta = require_authorized_source_delta(root, source)
    source_history = copy.deepcopy(source["goal_execution"]["transition_history"])
    source_verification_boundary = copy.deepcopy(source["verification_boundary"])
    source_completion_evidence = copy.deepcopy(
        source["goal_execution"]["completion_evidence_by_goal"]
    )
    source_archived_completion_evidence = copy.deepcopy(
        source["goal_execution"]["archived_completion_evidence_by_goal"]
    )
    source_verification_refs = copy.deepcopy(
        source["goal_execution"]["verification_evidence_refs"]
    )
    checkpoint = copy.deepcopy(source)
    state = checkpoint["goal_execution"]
    canonical_snapshot = contract.canonical_binding_snapshot(checkpoint)

    goal_paths = sorted([*state["goal_document_paths"], GOAL_PATH.as_posix()])
    managed_goal_paths = sorted([*state["managed_goal_paths"], GOAL_PATH.as_posix()])
    _require(len(goal_paths) == 28 and len(set(goal_paths)) == 28, "Goal path count differs")
    _require(
        len(managed_goal_paths) == 34 and len(set(managed_goal_paths)) == 34,
        "managed Goal path count differs",
    )
    state["goal_document_paths"] = goal_paths
    state["goal_document_count"] = len(goal_paths)
    state["managed_goal_paths"] = managed_goal_paths
    state["managed_goal_path_count"] = len(managed_goal_paths)
    state["path_set_sha256"], state["content_set_sha256"] = contract.package_hashes(
        root,
        managed_goal_paths,
    )

    inventory = copy.deepcopy(state["dynamic_goal_inventory"])
    inventory[GOAL_ID] = _inventory_record("0" * 64)
    children = copy.deepcopy(state["materialized_child_goal_ids_by_parent"])
    _require(GOAL_ID not in children.get(PARENT_GOAL_ID, []), "FP008 child is already present")
    children[PARENT_GOAL_ID] = [*children.get(PARENT_GOAL_ID, []), GOAL_ID]
    state["dynamic_goal_inventory"] = inventory
    state["materialized_child_goal_ids_by_parent"] = children
    state["status_by_goal"][GOAL_ID] = "PLANNED"

    planned_queue, planned_boundary = _derive_queue_and_boundary(
        root,
        checkpoint,
        MATERIALIZED_FRONTIER,
    )
    _require(
        contract.canonical_json_sha256(planned_queue) == EXPECTED_QUEUE_SHA256,
        "seq45 artifact queue SHA-256 differs",
    )
    _require(
        contract.canonical_json_sha256(planned_boundary)
        == EXPECTED_MATERIALIZED_BOUNDARY_SHA256,
        "seq45 completion boundary SHA-256 differs",
    )
    materialized: dict[str, Any] = {
        "sequence": 45,
        "event_id": MATERIALIZED_EVENT_ID,
        "event_type": "GOAL_MATERIALIZED",
        "occurred_on": "2026-08-03",
        "occurred_at": MATERIALIZED_AT,
        "previous_focus_goal_id": PARENT_GOAL_ID,
        "previous_focus_content_sha256": PARENT_GOAL_SHA256,
        "focus_goal_id": PARENT_GOAL_ID,
        "focus_goal_content_sha256": PARENT_GOAL_SHA256,
        "from_status": None,
        "to_status": "PLANNED",
        "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
        "status_changes": {GOAL_ID: "PLANNED"},
        "runtime_after": _runtime_projection(
            state,
            focus_goal_id=PARENT_GOAL_ID,
            focus_goal_path=PARENT_GOAL_PATH,
            focus_work_item_id="",
            focus_source="WORKSTREAM_GRAPH",
            frontier=MATERIALIZED_FRONTIER,
            queue=planned_queue,
            boundary=planned_boundary,
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"],
        "materialized_goal_id": GOAL_ID,
        "materialized_goal_path": GOAL_PATH.as_posix(),
        "materialized_goal_content_sha256": GOAL_SHA256,
        "materialized_from_role": "IMPLEMENTATION_BACKLOG",
        "materialized_from_path": BACKLOG_PATH,
        "materialized_from_document_id": BACKLOG_ID,
        "materialized_from_sha256": BACKLOG_SHA256,
        "predecessor_goal_id": PREDECESSOR_GOAL_ID,
        "predecessor_goal_content_sha256": PREDECESSOR_GOAL_SHA256,
        "supersedes_goal_id": "",
        "supersedes_goal_content_sha256": "",
        "artifact_work_reason": "",
        "artifact_trigger_evidence_refs": [],
        "artifact_trigger_evidence_bindings": {},
        "source_working_snapshot_reconciliation": source_delta,
        "previous_event_sha256": SOURCE_TAIL_SHA256,
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_snapshot),
    }
    materialized["event_sha256"] = contract.event_sha256(materialized)
    _require(
        set(materialized) == EXPECTED_MATERIALIZED_EVENT_FIELDS,
        "seq45 event field set differs",
    )
    _require(
        materialized["event_sha256"] == EXPECTED_MATERIALIZED_EVENT_SHA256,
        "seq45 event SHA-256 differs",
    )

    inventory[GOAL_ID] = _inventory_record(materialized["event_sha256"])
    state["dynamic_goal_inventory"] = inventory
    state["status_by_goal"][GOAL_ID] = "READY"
    ready_queue, ready_boundary = _derive_queue_and_boundary(
        root,
        checkpoint,
        READY_FRONTIER,
    )
    _require(
        contract.canonical_json_sha256(ready_queue) == EXPECTED_QUEUE_SHA256,
        "seq46 artifact queue SHA-256 differs",
    )
    _require(
        contract.canonical_json_sha256(ready_boundary) == EXPECTED_READY_BOUNDARY_SHA256,
        "seq46 completion boundary SHA-256 differs",
    )
    ready: dict[str, Any] = {
        "sequence": 46,
        "event_id": READY_EVENT_ID,
        "event_type": "GOAL_READY",
        "occurred_on": "2026-08-03",
        "occurred_at": READY_AT,
        "previous_focus_goal_id": PARENT_GOAL_ID,
        "previous_focus_content_sha256": PARENT_GOAL_SHA256,
        "focus_goal_id": GOAL_ID,
        "focus_goal_content_sha256": GOAL_SHA256,
        "from_status": "PLANNED",
        "to_status": "READY",
        "static_plan_manifest_sha256": state["static_plan_manifest_sha256"],
        "status_changes": {GOAL_ID: "READY"},
        "runtime_after": _runtime_projection(
            state,
            focus_goal_id=GOAL_ID,
            focus_goal_path=GOAL_PATH.as_posix(),
            focus_work_item_id=WORK_ITEM_ID,
            focus_source="IMPLEMENTATION_BACKLOG",
            frontier=READY_FRONTIER,
            queue=ready_queue,
            boundary=ready_boundary,
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": ["IMPLEMENTATION_GAP"],
        "subject_goal_id": GOAL_ID,
        "readiness_basis": {
            "dependency_completion_events": [
                {
                    "goal_id": PREDECESSOR_GOAL_ID,
                    "event_sha256": PREDECESSOR_COMPLETION_EVENT_SHA256,
                }
            ],
            "predecessor_goal_id": PREDECESSOR_GOAL_ID,
            "predecessor_completion_event_sha256": PREDECESSOR_COMPLETION_EVENT_SHA256,
        },
        "start_evidence_bindings": {},
        "start_evidence_provenance": {},
        "implementation_start_gate_contract_binding": start_gate_contract_binding(),
        "dynamic_goal_inventory_after": copy.deepcopy(inventory),
        "materialized_child_goal_ids_by_parent_after": copy.deepcopy(children),
        "previous_event_sha256": materialized["event_sha256"],
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_snapshot),
    }
    ready["event_sha256"] = contract.event_sha256(ready)
    _require(set(ready) == EXPECTED_READY_EVENT_FIELDS, "seq46 event field set differs")
    _require(
        ready["event_sha256"] == EXPECTED_READY_EVENT_SHA256,
        "seq46 event SHA-256 differs",
    )

    state["transition_history"].extend([materialized, ready])
    state["transition_history_anchor_sha256"] = ready["event_sha256"]
    state["validation_cutoff_at"] = READY_AT
    state["focus_goal_id"] = GOAL_ID
    state["focus_goal_path"] = GOAL_PATH.as_posix()
    state["focus_work_item_id"] = WORK_ITEM_ID
    state["focus_source"] = "IMPLEMENTATION_BACKLOG"
    state["ready_frontier_goal_ids"] = copy.deepcopy(READY_FRONTIER)
    state["artifact_work_queue"] = ready_queue
    state["completion_boundary"] = ready_boundary

    current = checkpoint["current_work"]
    current["work_item_id"] = WORK_ITEM_ID
    current["current_focus"] = "FP008/GAP-017 Goal READY; active internal start gate not run"
    current["release_completion_claimed"] = False

    snapshot = checkpoint["working_tree_snapshot"]
    managed_paths = sorted(set(snapshot["managed_changed_paths"]) | set(SOURCE_PATHS))
    path_hash, content_hash = contract.working_snapshot_hashes(root, managed_paths)
    snapshot["scope"] = (
        "Graph v2.4 FP008/GAP-017 materialization seq45 and readiness seq46 "
        "candidate; no GOAL_STARTED, implementation, external, device, deployment, "
        "formal-test, or release credit."
    )
    snapshot["managed_changed_paths"] = managed_paths
    snapshot["managed_changed_path_count"] = len(managed_paths)
    snapshot["path_set_sha256"] = path_hash
    snapshot["content_set_sha256"] = content_hash

    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(managed_paths)
    handoff["source_commit_or_snapshot"]["file_count"] = len(managed_paths)
    handoff["source_commit_or_snapshot"]["path_set_sha256"] = path_hash
    handoff["source_commit_or_snapshot"]["content_set_sha256"] = content_hash
    handoff["current_epic"] = "EPIC-03 / FP008/GAP-017 READY_NOT_STARTED"
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    handoff["last_verification_status"] = (
        "READY_NOT_STARTED_INTERNAL_EXTERNAL_DEVICE_DEPLOY_RELEASE_NOT_RUN"
    )
    _require(
        state["transition_history"][:SOURCE_SEQUENCE] == source_history,
        "pre-seq45 transition history changed",
    )
    _require(
        checkpoint["verification_boundary"] == source_verification_boundary,
        "verification boundary changed before FP008 start",
    )
    _require(
        state["completion_evidence_by_goal"] == source_completion_evidence
        and state["archived_completion_evidence_by_goal"]
        == source_archived_completion_evidence
        and state["verification_evidence_refs"] == source_verification_refs,
        "predecessor completion or verification evidence changed",
    )
    return checkpoint, materialized, ready


def _validate_projection(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    include_working_snapshot: bool,
) -> list[str]:
    errors, archive = contract.validate_frozen_v23_boundary(
        root,
        contract.V23_ARCHIVE_RELATIVE,
    )
    errors.extend(contract.validate_seq39_canonical_binding_authorization_request(root, checkpoint))
    errors.extend(contract.validate_seq39_canonical_binding_update(root, checkpoint))
    if archive:
        prepared = (
            None
            if contract.EXPECTED_V24_PREPARED_EVENT_SHA256.startswith("__FINALIZE_")
            else contract.EXPECTED_V24_PREPARED_EVENT_SHA256
        )
        authorization = (
            None
            if contract.EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256.startswith("__FINALIZE_")
            else contract.EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256
        )
        errors.extend(contract.validate_prepared_checkpoint_projection(checkpoint, archive))
        errors.extend(
            contract.validate_transition_replay(
                root,
                checkpoint,
                archive,
                expected_prepared_sha256=prepared,
                expected_authorization_sha256=authorization,
            )
        )
    if include_working_snapshot:
        errors.extend(contract.validate_working_snapshot(root, checkpoint))
    errors.extend(goal_graph.validate_v24_artifact_work_queue(root, checkpoint))
    errors.extend(contract.validate_generic_event_order(checkpoint["goal_execution"]["transition_history"]))
    return errors


def validate_projection(root: Path, checkpoint: dict[str, Any]) -> list[str]:
    return _validate_projection(
        root,
        checkpoint,
        include_working_snapshot=True,
    )


def ready_checkpoint_semantic_sha256(checkpoint: dict[str, Any]) -> str:
    """Hash every projected field except the two self-referential content copies."""

    normalized = copy.deepcopy(checkpoint)
    try:
        snapshot = normalized["working_tree_snapshot"]
        handoff_snapshot = normalized["session_handoff"][
            "source_commit_or_snapshot"
        ]
        snapshot["content_set_sha256"] = _WORKING_CONTENT_NORMALIZATION
        handoff_snapshot["content_set_sha256"] = _WORKING_CONTENT_NORMALIZATION
    except (KeyError, TypeError) as exc:
        raise ProjectionError("ready checkpoint self-reference fields are missing") from exc
    return contract.canonical_json_sha256(normalized)


def require_frozen_ready_sources(root: Path) -> bool:
    """Require one exact seq46 or controlled-successor source generation."""

    controlled_generation: set[str] = set()
    for relative, expected in READY_FROZEN_SOURCE_SHA256.items():
        relative_path = Path(relative)
        _require(
            not relative_path.is_absolute() and ".." not in relative_path.parts,
            f"frozen ready source path is unsafe: {relative}",
        )
        path = root
        for part in relative_path.parts:
            path /= part
            _require(
                not path.is_symlink(),
                f"frozen ready source path contains a symlink: {relative}",
            )
        _require(path.is_file(), f"frozen ready source is missing: {relative}")
        metadata = path.stat()
        _require(
            stat.S_ISREG(metadata.st_mode),
            f"frozen ready source authority differs: {relative}",
        )
        observed = contract.sha256_file(path)
        successor = READY_CONTROL_SUCCESSOR_SHA256.get(relative)
        if successor is None:
            _require(
                observed == expected,
                f"frozen ready source SHA-256 differs: {relative}",
            )
            continue
        if observed == expected:
            controlled_generation.add("READY")
        elif observed == successor:
            controlled_generation.add("SUCCESSOR")
        else:
            raise ProjectionError(
                f"frozen ready source SHA-256 differs: {relative}"
            )
    _require(
        len(controlled_generation) == 1,
        "frozen ready source generation is mixed",
    )
    return controlled_generation == {"SUCCESSOR"}


def require_ready_checkpoint(root: Path, checkpoint: dict[str, Any]) -> None:
    """Validate the exact published seq46 state without requiring seq44 bytes."""

    successor_sources = require_frozen_ready_sources(root)
    _require(
        ready_checkpoint_semantic_sha256(checkpoint)
        == READY_CHECKPOINT_SEMANTIC_SHA256,
        "published seq46 semantic SHA-256 differs",
    )
    _require(checkpoint.get("schema_version") == "1.25.0", "ready schema differs")
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(isinstance(history, list) and len(history) == 46, "ready history differs")
    _require(
        contract.canonical_json_sha256(history[:SOURCE_SEQUENCE])
        == SOURCE_HISTORY_PREFIX_SHA256,
        "pre-seq45 history prefix differs",
    )
    materialized, ready = history[-2:]
    _require(
        isinstance(materialized, dict)
        and set(materialized) == EXPECTED_MATERIALIZED_EVENT_FIELDS
        and materialized.get("sequence") == 45
        and materialized.get("event_id") == MATERIALIZED_EVENT_ID
        and materialized.get("event_type") == "GOAL_MATERIALIZED"
        and materialized.get("previous_event_sha256") == SOURCE_TAIL_SHA256
        and materialized.get("event_sha256") == EXPECTED_MATERIALIZED_EVENT_SHA256
        and contract.event_sha256(materialized) == EXPECTED_MATERIALIZED_EVENT_SHA256,
        "published seq45 event differs",
    )
    _require(
        isinstance(ready, dict)
        and set(ready) == EXPECTED_READY_EVENT_FIELDS
        and ready.get("sequence") == 46
        and ready.get("event_id") == READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == GOAL_ID
        and ready.get("previous_event_sha256") == EXPECTED_MATERIALIZED_EVENT_SHA256
        and ready.get("event_sha256") == EXPECTED_READY_EVENT_SHA256
        and contract.event_sha256(ready) == EXPECTED_READY_EVENT_SHA256,
        "published seq46 event differs",
    )
    _require(
        state.get("transition_history_anchor_sha256") == EXPECTED_READY_EVENT_SHA256
        and state.get("status_by_goal", {}).get(GOAL_ID) == "READY"
        and state.get("focus_goal_id") == GOAL_ID
        and state.get("focus_goal_path") == GOAL_PATH.as_posix()
        and state.get("focus_work_item_id") == WORK_ITEM_ID
        and state.get("focus_source") == "IMPLEMENTATION_BACKLOG"
        and state.get("ready_frontier_goal_ids") == READY_FRONTIER
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values(),
        "published seq46 runtime state differs",
    )
    _require(
        state.get("dynamic_goal_inventory", {}).get(GOAL_ID)
        == _inventory_record(EXPECTED_MATERIALIZED_EVENT_SHA256),
        "published FP008 Goal inventory differs",
    )
    _require(
        contract.canonical_json_sha256(checkpoint.get("verification_boundary"))
        == SOURCE_VERIFICATION_BOUNDARY_SHA256
        and contract.canonical_json_sha256(state.get("completion_evidence_by_goal"))
        == SOURCE_COMPLETION_EVIDENCE_SHA256
        and contract.canonical_json_sha256(
            state.get("archived_completion_evidence_by_goal")
        )
        == SOURCE_ARCHIVED_COMPLETION_EVIDENCE_SHA256
        and contract.canonical_json_sha256(state.get("verification_evidence_refs"))
        == SOURCE_VERIFICATION_REFS_SHA256,
        "predecessor completion or verification evidence differs",
    )
    current = checkpoint.get("current_work")
    _require(
        isinstance(current, dict)
        and current.get("work_item_id") == WORK_ITEM_ID
        and current.get("current_focus")
        == "FP008/GAP-017 Goal READY; active internal start gate not run"
        and current.get("release_completion_claimed") is False,
        "published current-work boundary differs",
    )
    snapshot = checkpoint.get("working_tree_snapshot")
    handoff = checkpoint.get("session_handoff")
    handoff_snapshot = (
        handoff.get("source_commit_or_snapshot")
        if isinstance(handoff, dict)
        else None
    )
    _require(
        isinstance(snapshot, dict)
        and isinstance(handoff_snapshot, dict)
        and handoff_snapshot.get("content_set_sha256")
        == snapshot.get("content_set_sha256"),
        "published snapshot and handoff content authorities differ",
    )
    if successor_sources:
        _require(
            snapshot.get("content_set_sha256")
            == READY_MANAGED_CONTENT_SET_SHA256,
            "working snapshot content-set SHA-256 differs",
        )
    require_static_inputs(root, checkpoint)
    errors = _validate_projection(
        root,
        checkpoint,
        include_working_snapshot=not successor_sources,
    )
    _require(not errors, "published seq46 validation failed: " + "; ".join(errors))


def ready_checkpoint_from_exact_seq47_successor(
    root: Path,
    checkpoint: dict[str, Any],
) -> dict[str, Any]:
    """Recover the exact seq46 view from the pinned add-only seq47 successor."""

    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(
        isinstance(history, list) and len(history) == READY_SEQUENCE + 1,
        "exact seq47 successor history differs",
    )
    event = history[-1]
    expected_fields = contract.V24_FIRST_START_EVENT_FIELDS | {
        "source_checkpoint_sha256"
    }
    _require(
        isinstance(event, dict)
        and set(event) == expected_fields
        and event.get("sequence") == READY_SEQUENCE + 1
        and event.get("event_id") == EXACT_SEQ47_SUCCESSOR_EVENT_ID
        and event.get("event_type") == "GOAL_STARTED"
        and event.get("previous_event_sha256") == EXPECTED_READY_EVENT_SHA256
        and event.get("source_checkpoint_sha256")
        == EXACT_SEQ47_SOURCE_CHECKPOINT_SHA256
        and event.get("event_sha256") == EXACT_SEQ47_SUCCESSOR_EVENT_SHA256
        and contract.event_sha256(event) == EXACT_SEQ47_SUCCESSOR_EVENT_SHA256,
        "exact seq47 successor event differs",
    )
    _require(
        state.get("transition_history_anchor_sha256")
        == EXACT_SEQ47_SUCCESSOR_EVENT_SHA256
        and state.get("status_by_goal", {}).get(GOAL_ID) == "IN_PROGRESS"
        and list(state.get("status_by_goal", {}).values()).count("IN_PROGRESS")
        == 1,
        "exact seq47 successor runtime differs",
    )
    repository_snapshot = event.get("repository_snapshot_before")
    _require(
        isinstance(repository_snapshot, dict)
        and repository_snapshot.get("checkpoint_managed_path_count")
        == READY_MANAGED_PATH_COUNT
        and repository_snapshot.get("checkpoint_path_set_sha256")
        == READY_MANAGED_PATH_SET_SHA256
        and repository_snapshot.get("checkpoint_content_set_sha256")
        == READY_MANAGED_CONTENT_SET_SHA256,
        "exact seq47 source snapshot binding differs",
    )
    snapshot = checkpoint.get("working_tree_snapshot")
    paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
    _require(
        isinstance(paths, list) and paths == sorted(set(paths)),
        "seq47 successor managed paths differ",
    )
    post_ready_paths = set(paths).intersection(POST_READY_CONTROL_PATHS)
    allowed_post_ready_path_sets = [
        set(SEQ47_REQUIRED_CONTROL_PATHS),
        set(SESSION_POST_READY_CONTROL_PATHS),
    ]
    cumulative_post_ready_paths = set(SESSION_POST_READY_CONTROL_PATHS)
    for generation_paths in ISOLATED_SNAPSHOT_FIX_CONTROL_GENERATIONS:
        cumulative_post_ready_paths.update(generation_paths)
        allowed_post_ready_path_sets.append(set(cumulative_post_ready_paths))
    _require(
        post_ready_paths in allowed_post_ready_path_sets,
        "seq47 successor control membership differs",
    )
    ready_paths = sorted(set(paths) - POST_READY_CONTROL_PATHS)
    ready_path_sha256 = hashlib.sha256(
        ("\n".join(ready_paths) + "\n").encode("utf-8")
    ).hexdigest()
    _require(
        len(ready_paths) == READY_MANAGED_PATH_COUNT
        and ready_path_sha256 == READY_MANAGED_PATH_SET_SHA256,
        "recovered seq46 managed path boundary differs",
    )

    ready_checkpoint = copy.deepcopy(checkpoint)
    ready_state = ready_checkpoint["goal_execution"]
    ready_state["transition_history"].pop()
    ready_state["transition_history_anchor_sha256"] = EXPECTED_READY_EVENT_SHA256
    ready_state["validation_cutoff_at"] = READY_AT
    ready_state["status_by_goal"][GOAL_ID] = "READY"
    ready_checkpoint["current_work"]["current_focus"] = (
        "FP008/GAP-017 Goal READY; active internal start gate not run"
    )
    ready_snapshot = ready_checkpoint["working_tree_snapshot"]
    ready_snapshot["scope"] = (
        "Graph v2.4 FP008/GAP-017 materialization seq45 and readiness seq46 "
        "candidate; no GOAL_STARTED, implementation, external, device, deployment, "
        "formal-test, or release credit."
    )
    ready_snapshot["managed_changed_paths"] = ready_paths
    ready_snapshot["managed_changed_path_count"] = READY_MANAGED_PATH_COUNT
    ready_snapshot["path_set_sha256"] = READY_MANAGED_PATH_SET_SHA256
    ready_snapshot["content_set_sha256"] = READY_MANAGED_CONTENT_SET_SHA256
    ready_handoff = ready_checkpoint["session_handoff"]
    ready_handoff["changed_files"] = copy.deepcopy(ready_paths)
    ready_handoff_snapshot = ready_handoff["source_commit_or_snapshot"]
    ready_handoff_snapshot["file_count"] = READY_MANAGED_PATH_COUNT
    ready_handoff_snapshot["path_set_sha256"] = READY_MANAGED_PATH_SET_SHA256
    ready_handoff_snapshot["content_set_sha256"] = (
        READY_MANAGED_CONTENT_SET_SHA256
    )
    ready_handoff["current_epic"] = "EPIC-03 / FP008/GAP-017 READY_NOT_STARTED"
    ready_handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    ready_handoff["last_verification_status"] = (
        "READY_NOT_STARTED_INTERNAL_EXTERNAL_DEVICE_DEPLOY_RELEASE_NOT_RUN"
    )
    require_ready_checkpoint(root, ready_checkpoint)
    return ready_checkpoint


def preflight(root: Path) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    root = root.resolve(strict=True)
    source_bytes, source = load_exact_source(root)
    projected, materialized, ready = project(root, source)
    errors = validate_projection(root, projected)
    _require(not errors, "projected seq46 validation failed: " + "; ".join(errors))
    require_ready_checkpoint(root, projected)
    _require((root / CHECKPOINT).read_bytes() == source_bytes, "live checkpoint changed during preflight")
    return projected, materialized, ready


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preflight", action="store_true", required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        projected, materialized, ready = preflight(args.root)
    except (KeyError, OSError, TypeError, ValueError, ProjectionError) as exc:
        print(f"WalkSafe FP008 seq45/46 materialization: FAIL: {exc}")
        return 1
    state = projected["goal_execution"]
    print(
        "WalkSafe FP008 seq45/46 materialization: PASS mode=PREFLIGHT "
        f"source={SOURCE_CHECKPOINT_SHA256} "
        f"seq45={materialized['event_sha256']} "
        f"seq46={ready['event_sha256']} "
        f"focus={state['focus_goal_id']} status={state['status_by_goal'][GOAL_ID]} "
        "live_checkpoint_written=false release=NOT_ELIGIBLE"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
