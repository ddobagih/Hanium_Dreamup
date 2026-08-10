#!/usr/bin/env python3
"""Build the focused FP-047 user/admin login and authorization separation trace."""

from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
from typing import Any
from xml.etree import ElementTree

try:
    from scripts import build_walksafe_fp012_multi_device_session_ledger_trace_20260726 as predecessor
except ModuleNotFoundError:
    import build_walksafe_fp012_multi_device_session_ledger_trace_20260726 as predecessor


base = predecessor.base
ROOT = Path(__file__).resolve().parents[1]
BUILDER = Path(__file__).resolve()
BUILDER_TEST = (
    ROOT
    / "tests/test_walksafe_fp047_user_admin_login_authorization_separation_trace_20260726.py"
)

GOAL_ID = "WS-GOAL-EPIC-03-FP-047-R001"
GOAL_PATH = (
    ROOT
    / "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-fp047-user-admin-login-authorization-separation-r001.md"
)
RESULT_DIR = ROOT / f"docs/control/execution/goal-results/{GOAL_ID}"
IMPLEMENTATION_JSON = RESULT_DIR / "implementation-record.json"
VERIFICATION_JSON = RESULT_DIR / "verification-result.json"
SUCCESSOR_JSON = RESULT_DIR / "successor-trace.json"
REVIEW_SUBJECT_JSON = RESULT_DIR / "review-subject.json"
REVIEW_JSON = RESULT_DIR / "independent-review.json"
REVIEW_ATTESTATION_JSON = RESULT_DIR / "review-attestation.json"
RECEIPT_JSON = RESULT_DIR / "completion-receipt.json"
FOCUSED_LOG = RESULT_DIR / "logs/focused-admin-security-tests.log"
BACKEND_LOG = RESULT_DIR / "logs/backend-internal-verification.log"
ADMINAPP_LOG = RESULT_DIR / "logs/adminapp-verification.log"
BOUNDARY_LOG = RESULT_DIR / "logs/user-admin-boundary-verification.log"
CONTROL_PLANE_LOG = RESULT_DIR / "logs/control-plane-verification.log"

LOCKED_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)
FOCUSED_COMMAND = (
    f"PYTHONPATH=backend {LOCKED_PYTHON} -m pytest -q "
    "backend/tests/test_admin_security.py"
)
BACKEND_COMMAND = (
    f"PYTHONPATH=backend {LOCKED_PYTHON} -m pytest -q backend/tests "
    "--ignore=backend/tests/test_reports.py "
    "--ignore=backend/tests/test_reports_v2.py "
    '-k "not postgres and not '
    'backend_database_connections_have_a_bounded_statement_timeout"'
)
ADMINAPP_COMMAND = (
    "cd apps/android && ./gradlew --no-daemon --max-workers=1 "
    ":adminapp:testDebugUnitTest :adminapp:assembleDebug :adminapp:lintDebug"
)
BOUNDARY_COMMAND = (
    "npm --prefix apps/android-gateway run typecheck && "
    "npm --prefix apps/android-gateway test && "
    f"{LOCKED_PYTHON} -B "
    "scripts/check_walksafe_android_gateway_boundary_20260723.py && "
    f"PYTHONPATH=. {LOCKED_PYTHON} -B "
    "scripts/generate_walksafe_openapi.py --check"
)
CONTROL_PLANE_COMMAND = (
    f"{LOCKED_PYTHON} -m py_compile "
    "scripts/build_walksafe_fp047_user_admin_login_authorization_separation_trace_20260726.py "
    f"&& {LOCKED_PYTHON} -m pytest -q "
    "tests/test_walksafe_fp047_user_admin_login_authorization_separation_trace_20260726.py"
)

GAP_R020_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260726-r020.json"
)
BACKLOG_R020_JSON = (
    ROOT
    / "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260726-r020.json"
)
FP012_OVERLAY_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-fp012-multi-device-session-ledger-active-ledger-"
    "overlay-20260726-r001.json"
)
GAP_R021_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260726-r021.json"
)
GAP_R021_MD = GAP_R021_JSON.with_suffix(".md")
BACKLOG_R021_JSON = (
    ROOT
    / "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260726-r021.json"
)
BACKLOG_R021_MD = BACKLOG_R021_JSON.with_suffix(".md")
FP047_OVERLAY_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-03-fp047-user-admin-login-authorization-separation-"
    "active-ledger-overlay-20260726-r001.json"
)
FP047_OVERLAY_MD = FP047_OVERLAY_JSON.with_suffix(".md")
CHECKPOINT = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"
START_GATE_RECEIPT = (
    ROOT
    / "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP047-20260726-005/"
    "implementation-start-gate-receipt.json"
)
START_GATE_REPOSITORY_STATE_LOG = START_GATE_RECEIPT.parent / "19-REPOSITORY_STATE.log"

EXPECTED_PREDECESSOR_SHA256 = {
    GAP_R020_JSON: "6c6bd3e1db80cfcca05361afedb6f2eb4de0cc4a67bf76b9a6830c71cf2a70b7",
    BACKLOG_R020_JSON: "20b103c83f397894a7b9d5c9eedd696b0aa977b02552f49614876714b540dd6f",
    FP012_OVERLAY_JSON: "b4f30dc0f2c1279f491eee42675004e36399378a0389c0e22775aaf73652f572",
}
EXPECTED_GOAL_SHA256 = (
    "2ff79dde64cb113d755855f05dafb6bab1393717f060b4db387bb2d48bc53b06"
)
EXPECTED_START_GATE_RECEIPT_SHA256 = (
    "ef2ab8785e3ade490c1342895c62320d62d44a5164aab2a4d89a16c8d0fb4a6d"
)
EXPECTED_START_GATE_RECEIPT_ID = (
    "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP047-20260726-005"
)
EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256 = (
    "85a159407968746aeccd9031a4431dc87c6dc80db2791580db989c878de1e457"
)
EXPECTED_PINNED_HEAD = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"
EXPECTED_EXECUTION_EVENT_SEQUENCE = 34
EXPECTED_EXECUTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP047-20260726-005"
)
EXPECTED_EXECUTION_EVENT_SHA256 = (
    "1f1959cdf831b5b20dc10be77fcbc727d6541b3b2dac6e78007554315771324a"
)
EXPECTED_EXECUTION_STARTED_AT = "2026-07-26T13:09:50+09:00"
EXPECTED_EXECUTION_SESSION_EVENT_SEQUENCE = 36
EXPECTED_EXECUTION_SESSION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-FP047-20260726-002"
)
EXPECTED_EXECUTION_SESSION_EVENT_SHA256 = (
    "6f68014716055713d91bf51a8f5dcb12bb1db4e5821985c0d63ad9ce817d8f7b"
)
EXPECTED_EXECUTION_SESSION_STARTED_AT = "2026-07-26T17:09:58+09:00"
EXECUTOR_ID = (
    "CODEX-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION-"
    "IMPLEMENTER-20260726-001"
)
EXECUTOR_TASK = "/root"
EXPECTED_REVIEWER_ID = "WS-FP047-INDEPENDENT-REVIEWER-001"
EXPECTED_REVIEWER_TASK = "/root/fp047_final_evidence_review"

COMMAND_EXIT_CODE_MARKER = "WALKSAFE_COMMAND_EXIT_CODE"
CANONICAL_LOG_EXECUTION_SEQUENCE_MARKER = "WALKSAFE_EXECUTION_EVENT_SEQUENCE"
CANONICAL_LOG_EXECUTION_EVENT_ID_MARKER = "WALKSAFE_EXECUTION_EVENT_ID"
CANONICAL_LOG_EXECUTION_EVENT_SHA256_MARKER = "WALKSAFE_EXECUTION_EVENT_SHA256"
CANONICAL_LOG_IMPLEMENTATION_CONTENT_SET_SHA256_MARKER = (
    "WALKSAFE_IMPLEMENTATION_EXACT_31_CONTENT_SET_SHA256"
)

EXPECTED_REVIEW_BOUNDARY = {
    "separate_internal_review_pass": True,
    "external_independence_claimed": False,
    "formal_tests_remain_not_run": True,
    "external_postgis_integration_remains_not_run": True,
    "external_reports_integration_remains_not_run": True,
    "postgresql_concurrency_verification_remains_not_run": True,
    "actual_user_tests_remain_not_run": True,
    "actual_admin_tests_remain_not_run": True,
    "actual_device_tests_remain_not_run": True,
    "single_admin_recovery_drill_remains_not_run": True,
    "external_security_review_remains_not_run": True,
    "external_legal_review_remains_not_run": True,
    "external_privacy_review_remains_not_run": True,
    "external_accessibility_review_remains_not_run": True,
    "production_deployment_remains_not_run": True,
    "release_remains_not_eligible": True,
}

IMPLEMENTATION_PATHS = (
    "apps/android-gateway/src/exclusive-file-lock.ts",
    "apps/android-gateway/src/auth.ts",
    "apps/android-gateway/src/backend.ts",
    "apps/android-gateway/src/routes.ts",
    "apps/android-gateway/src/field-long-session.ts",
    "apps/android-gateway/src/field-walk-ledger.ts",
    "apps/android-gateway/test/field-long-session.test.ts",
    "apps/android-gateway/test/field-walk-ledger.test.ts",
    "apps/android-gateway/test/exclusive-file-lock.test.ts",
    "apps/android-gateway/test/gateway-contract.test.ts",
    "backend/app/field_test_security.py",
    "backend/app/services/admin_security.py",
    "backend/app/api/admin_security.py",
    "backend/app/api/reports.py",
    "backend/app/models.py",
    "backend/app/api/health.py",
    "backend/alembic/versions/202607260001_admin_security_action_bound_audit.py",
    "backend/tests/test_admin_security.py",
    "contracts/walksafe.openapi.json",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityApi.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGate.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClientTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityControllerTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGateTest.java",
)
TOOLING_PATHS = (
    "scripts/build_walksafe_fp047_user_admin_login_authorization_separation_trace_20260726.py",
    "tests/test_walksafe_fp047_user_admin_login_authorization_separation_trace_20260726.py",
    "scripts/run_walksafe_test_layers_20260711.sh",
    "scripts/check_walksafe_goal_graph_v2_4.py",
    "tests/test_walksafe_goal_graph_v2_4.py",
)
EXACT_SCOPE_PATHS = IMPLEMENTATION_PATHS + TOOLING_PATHS

PRE_REVIEW_OUTPUT_PATHS = (
    IMPLEMENTATION_JSON,
    VERIFICATION_JSON,
    SUCCESSOR_JSON,
    REVIEW_SUBJECT_JSON,
)

OUTPUT_PATHS = PRE_REVIEW_OUTPUT_PATHS + (
    REVIEW_JSON,
    RECEIPT_JSON,
    GAP_R021_JSON,
    GAP_R021_MD,
    BACKLOG_R021_JSON,
    BACKLOG_R021_MD,
    FP047_OVERLAY_JSON,
    FP047_OVERLAY_MD,
)


class BuildError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BuildError(message)


def parse_aware_timestamp(value: Any, label: str) -> datetime:
    require(isinstance(value, str), f"{label} is not an ISO timestamp")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise BuildError(f"{label} is not an ISO timestamp") from exc
    require(
        parsed.tzinfo is not None and parsed.utcoffset() is not None,
        f"{label} must include an offset",
    )
    return parsed


def snapshot_file(
    path: Path,
    *,
    expected_path: Path | None = None,
    confinement_root: Path | None = None,
) -> bytes:
    supplied = path.absolute()
    if expected_path is not None:
        require(
            supplied == expected_path.absolute(),
            f"noncanonical evidence input: {path}",
        )
    if confinement_root is not None:
        root = confinement_root.absolute()
        require(
            supplied == root or root in supplied.parents,
            f"canonical evidence input escapes root: {path}",
        )
        current = root
        require(not current.is_symlink(), f"canonical evidence root is a symlink: {root}")
        for part in supplied.relative_to(root).parts:
            current = current / part
            require(
                not current.is_symlink(),
                f"canonical evidence input contains a symlink: {path}",
            )
    else:
        require(
            not supplied.is_symlink(),
            f"canonical evidence input is a symlink: {path}",
        )
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(supplied, flags)
    except OSError as exc:
        raise BuildError(f"canonical evidence input cannot be opened: {path}") from exc
    try:
        require(
            stat.S_ISREG(os.fstat(descriptor).st_mode),
            f"canonical evidence input is not a regular file: {path}",
        )
        with os.fdopen(descriptor, "rb", closefd=False) as handle:
            return handle.read()
    finally:
        os.close(descriptor)


def json_from_snapshot(content: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(content.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BuildError(f"{label} is not valid UTF-8 JSON") from exc
    require(isinstance(value, dict), f"{label} is not a JSON object")
    return value


def relative(path: Path) -> str:
    return path.resolve().relative_to(ROOT.resolve()).as_posix()


def json_text(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def generated_record(path: Path, content: str, *, name: str) -> dict[str, Any]:
    return {
        "name": name,
        "path": relative(path),
        "sha256": base.sha256_bytes(content.encode("utf-8")),
    }


def generated_binding(
    role: str,
    path: Path,
    document_id: str,
    content: str,
) -> dict[str, Any]:
    return {
        "role": role,
        "path": relative(path),
        "document_id": document_id,
        "file_sha256": base.sha256_bytes(content.encode("utf-8")),
    }


def validate_execution_history(goal_execution: dict[str, Any]) -> dict[str, Any]:
    history = goal_execution.get("transition_history")
    require(isinstance(history, list) and history, "transition history is missing")
    sequences = [event.get("sequence") for event in history]
    require(
        all(isinstance(value, int) and not isinstance(value, bool) for value in sequences)
        and sequences == sorted(sequences)
        and len(sequences) == len(set(sequences)),
        "transition history sequence order or uniqueness differs",
    )
    event_ids = [event.get("event_id") for event in history]
    require(
        all(isinstance(value, str) and value for value in event_ids)
        and len(event_ids) == len(set(event_ids)),
        "transition history event-id uniqueness differs",
    )
    for event in history:
        stored_sha256 = event.get("event_sha256")
        payload = {key: value for key, value in event.items() if key != "event_sha256"}
        require(
            isinstance(stored_sha256, str)
            and re.fullmatch(r"[0-9a-f]{64}", stored_sha256) is not None
            and base.object_sha256(payload) == stored_sha256,
            f"transition event canonical SHA-256 differs: {event.get('event_id')}",
        )
    by_sequence = [
        event
        for event in history
        if event.get("sequence") == EXPECTED_EXECUTION_EVENT_SEQUENCE
    ]
    by_id = [
        event
        for event in history
        if event.get("event_id") == EXPECTED_EXECUTION_EVENT_ID
    ]
    require(
        len(by_sequence) == 1 and len(by_id) == 1 and by_sequence[0] is by_id[0],
        "FP-047 execution event is not exact-one",
    )
    execution_start = by_sequence[0]
    require(
        execution_start.get("event_type") == "GOAL_STARTED"
        and execution_start.get("subject_goal_id") == GOAL_ID
        and execution_start.get("event_sha256") == EXPECTED_EXECUTION_EVENT_SHA256
        and execution_start.get("occurred_at") == EXPECTED_EXECUTION_STARTED_AT,
        "FP-047 execution event differs",
    )
    execution_sessions = [
        event
        for event in history
        if event.get("event_type") in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
        and event.get("subject_goal_id") == GOAL_ID
    ]
    require(execution_sessions, "FP-047 execution session is missing")
    execution_session = execution_sessions[-1]
    require(
        execution_session.get("sequence")
        == EXPECTED_EXECUTION_SESSION_EVENT_SEQUENCE
        and execution_session.get("event_id") == EXPECTED_EXECUTION_SESSION_EVENT_ID
        and execution_session.get("event_type") == "WORK_SESSION_RESUMED"
        and execution_session.get("event_sha256")
        == EXPECTED_EXECUTION_SESSION_EVENT_SHA256
        and execution_session.get("occurred_at")
        == EXPECTED_EXECUTION_SESSION_STARTED_AT
        and execution_session.get("from_status") == "IN_PROGRESS"
        and execution_session.get("to_status") == "IN_PROGRESS"
        and execution_session.get("status_changes") == {},
        "FP-047 execution session differs",
    )
    return execution_session


def load_gate_repository_state() -> dict[str, Any]:
    require(
        base.sha256_file(START_GATE_REPOSITORY_STATE_LOG)
        == EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256,
        "start-gate repository-state log differs",
    )
    state = json.loads(START_GATE_REPOSITORY_STATE_LOG.read_text(encoding="utf-8"))
    require(
        state.get("evidence_type") == "GATE_REPOSITORY_STATE"
        and state.get("gate_event_id") == EXPECTED_EXECUTION_EVENT_ID,
        "start-gate repository-state identity differs",
    )
    repository = state.get("repository", {})
    require(
        repository.get("head_commit") == EXPECTED_PINNED_HEAD
        and repository.get("object_format") == "sha1",
        "start-gate pinned HEAD differs",
    )
    return state


def pinned_head_blob(path: str) -> bytes | None:
    listing = subprocess.run(
        ["git", "ls-tree", "-z", "--full-tree", EXPECTED_PINNED_HEAD, "--", path],
        cwd=ROOT,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    require(listing.returncode == 0, f"cannot inspect pinned HEAD path: {path}")
    if not listing.stdout:
        return None
    records = [record for record in listing.stdout.split(b"\0") if record]
    require(len(records) == 1, f"pinned HEAD path count differs: {path}")
    metadata, separator, listed_path = records[0].partition(b"\t")
    require(
        separator == b"\t"
        and listed_path.decode("utf-8") == path
        and metadata.split()[1:2] == [b"blob"],
        f"pinned HEAD path is not one exact blob: {path}",
    )
    loaded = subprocess.run(
        ["git", "show", f"{EXPECTED_PINNED_HEAD}:{path}"],
        cwd=ROOT,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    require(loaded.returncode == 0, f"cannot read pinned HEAD path: {path}")
    return loaded.stdout


def before_state(
    path: str,
    repository_state: dict[str, Any],
) -> tuple[bool, str | None, str]:
    entries = [
        item
        for item in repository_state.get("dirty_snapshot", {}).get("paths", [])
        if item.get("path") == path and item.get("path_role") == "CURRENT"
    ]
    require(len(entries) <= 1, f"duplicate gate-state path: {path}")
    if entries:
        worktree = entries[0].get("worktree", {})
        state = worktree.get("state")
        if state == "PRESENT":
            digest = worktree.get("sha256")
            require(
                isinstance(digest, str)
                and re.fullmatch(r"[0-9a-f]{64}", digest) is not None,
                f"gate-state content hash is malformed: {path}",
            )
            return True, digest, "GATE_DIRTY_SNAPSHOT"
        require(
            state in {"ABSENT", "DELETED"},
            f"unsupported gate-state worktree state: {path}",
        )
        return False, None, "GATE_DIRTY_SNAPSHOT"
    blob = pinned_head_blob(path)
    if blob is None:
        return False, None, "GATE_PINNED_HEAD_ABSENT"
    return True, base.sha256_bytes(blob), "GATE_PINNED_HEAD"


def implementation_files() -> list[dict[str, Any]]:
    require(
        len(EXACT_SCOPE_PATHS) == 31
        and len(IMPLEMENTATION_PATHS) == 26
        and len(TOOLING_PATHS) == 5
        and len(set(EXACT_SCOPE_PATHS)) == len(EXACT_SCOPE_PATHS),
        "implementation path set differs",
    )
    repository_state = load_gate_repository_state()
    files: list[dict[str, Any]] = []
    for relative_path in EXACT_SCOPE_PATHS:
        path = ROOT / relative_path
        require(
            path.is_file() and not path.is_symlink(),
            f"implementation path is not a regular file: {relative_path}",
        )
        after_sha256 = base.sha256_file(path)
        existed, before_sha256, before_source = before_state(
            relative_path,
            repository_state,
        )
        if existed:
            require(
                after_sha256 != before_sha256,
                f"implementation path did not change after start gate: {relative_path}",
            )
            change_kind = "MODIFIED"
        else:
            change_kind = "ADDED"
        files.append(
            {
                "path": relative_path,
                "sha256": after_sha256,
                "before_sha256": before_sha256,
                "before_source": before_source,
                "change_kind": change_kind,
            }
        )
    return files


def validate_inputs(
    checkpoint: dict[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], dict[str, Any]]:
    for path, expected_sha256 in EXPECTED_PREDECESSOR_SHA256.items():
        require(
            base.sha256_file(path) == expected_sha256,
            f"predecessor differs: {relative(path)}",
        )
    require(base.sha256_file(GOAL_PATH) == EXPECTED_GOAL_SHA256, "goal differs")
    require(
        base.sha256_file(START_GATE_RECEIPT)
        == EXPECTED_START_GATE_RECEIPT_SHA256,
        "implementation start-gate receipt differs",
    )
    gate = base.load_json(START_GATE_RECEIPT)
    require(
        gate.get("document_id") == EXPECTED_START_GATE_RECEIPT_ID
        and gate.get("target_goal_id") == GOAL_ID
        and gate.get("target_goal_content_sha256") == EXPECTED_GOAL_SHA256
        and gate.get("status") == "PASS",
        "implementation start-gate binding differs",
    )
    require(
        gate.get("repository_snapshot", {}).get("head_commit")
        == EXPECTED_PINNED_HEAD
        and gate.get("repository_snapshot", {}).get(
            "gate_repository_state_output_sha256"
        )
        == EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256,
        "implementation start-gate repository binding differs",
    )
    load_gate_repository_state()
    if checkpoint is None:
        checkpoint = json_from_snapshot(
            snapshot_file(
                CHECKPOINT,
                expected_path=CHECKPOINT,
                confinement_root=ROOT,
            ),
            "continuation checkpoint",
        )
    execution = validate_execution_history(checkpoint.get("goal_execution", {}))
    gap = base.load_json(GAP_R020_JSON)
    backlog = base.load_json(BACKLOG_R020_JSON)
    overlay = base.load_json(FP012_OVERLAY_JSON)
    require(
        gap.get("metadata", {}).get("report_id")
        == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260726-020",
        "r020 gap identity differs",
    )
    require(
        backlog.get("metadata", {}).get("backlog_id")
        == "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-020",
        "r020 backlog identity differs",
    )
    require(
        overlay.get("metadata", {}).get("overlay_id")
        == (
            "WS-EPIC-02-FP012-MULTI-DEVICE-SESSION-LEDGER-"
            "ACTIVE-LEDGER-OVERLAY-20260726-001"
        ),
        "FP-012 predecessor overlay identity differs",
    )
    return gap, backlog, overlay, execution


def completion_boundary() -> dict[str, Any]:
    return {
        "formal_test_ids": [f"TC-FP-047-{index:02d}" for index in range(1, 8)],
        "formal_test_status": "NOT_RUN",
        "external_postgis_integration_status": "NOT_RUN",
        "external_reports_integration_status": "NOT_RUN",
        "postgresql_concurrency_status": "NOT_RUN",
        "actual_user_status": "NOT_RUN",
        "actual_admin_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "single_admin_recovery_drill_status": "NOT_RUN",
        "external_security_review_status": "NOT_RUN",
        "external_legal_review_status": "NOT_RUN",
        "external_privacy_review_status": "NOT_RUN",
        "external_accessibility_review_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_gate_count": 5,
        "release_gate_status": "NOT_RUN",
        "release_gates_waived": False,
        "external_independence_claimed": False,
        "release_status": "NOT_ELIGIBLE",
    }


def build_implementation(
    *,
    observed_at: str,
    expected_content_set_sha256: str | None = None,
) -> dict[str, Any]:
    files = implementation_files()
    content_set_sha256 = base.object_sha256(
        [{"path": item["path"], "sha256": item["sha256"]} for item in files]
    )
    if expected_content_set_sha256 is not None:
        require(
            content_set_sha256 == expected_content_set_sha256,
            "implementation content set differs",
        )
    return {
        "schema_version": "1.0",
        "document_id": (
            "WS-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION-"
            "IMPLEMENTATION-20260726-001"
        ),
        "goal_id": GOAL_ID,
        "kind": "IMPLEMENTATION_RECORD",
        "status": "PASS",
        "observed_at": observed_at,
        "implementation_content_set_sha256": content_set_sha256,
        "changed_artifacts": [
            {
                "path": item["path"],
                "before_sha256": item["before_sha256"],
                "after_sha256": item["sha256"],
                "before_source": item["before_source"],
                "change_kind": item["change_kind"],
            }
            for item in files
        ],
        "implemented_controls": [
            "user and administrator entry points use separate application, audience and token authority boundaries",
            "server-side role and ownership authorization rejects unregistered administrator method-route pairs by default",
            "user device sessions support rotation, expiry, selective revocation, revoke-all and previous-proof replay rejection",
            "administrator login requires password and TOTP before an administrator session is issued",
            "high-risk administrator authorization is bound to session, action, method, route, nonce and expiry",
            "high-risk middleware consumes and binds each nonce once in its own committed SessionLocal transaction before endpoint execution",
            "the endpoint transaction separately revalidates control and session state and holds the protected-work lock through mutation/read, so endpoint failure cannot roll back or reuse the consumed nonce",
            "administrator recovery freezes high-risk actions and has no shared-password bypass",
            "authentication, authorization, step-up and revocation denials are persisted with actor, device, time and reason",
            "the Android Gateway administrator v3 surface is absent and the legacy web administrator boundary remains disabled",
            "gateway, backend and adminapp regressions cover default deny, replay, revocation, recovery and authority separation",
            "the trace binds exact product-twenty-six and tooling-five scopes as one canonical exact-thirty-one content set",
        ],
        "remaining_implementation_boundaries": [
            "TC-FP-047-01 through TC-FP-047-07 were not run",
            "external PostGIS and reports integration was excluded from internal verification",
            "external PostgreSQL concurrency verification was not performed",
            "actual user, administrator and device scenarios were not tested",
            "the single-administrator recovery drill was not run",
            "external security, legal, privacy and accessibility reviews were not run",
            "production credentials, networks and deployment were not used",
            "the five release gates were not run or waived",
        ],
        "completion_boundary": completion_boundary(),
    }


def checked_log(
    source_path: Path,
    *,
    output_path: Path,
    name: str,
    command: str,
    implementation_content_set_sha256: str,
    required: tuple[str, ...],
    content_bytes: bytes | None = None,
) -> dict[str, Any]:
    if content_bytes is None:
        content_bytes = snapshot_file(source_path)
    try:
        content = content_bytes.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BuildError(f"{name} log is not UTF-8") from exc
    exit_matches = re.findall(
        rf"^{re.escape(COMMAND_EXIT_CODE_MARKER)}=([^\r\n]*)$",
        content,
        flags=re.MULTILINE,
    )
    require(
        exit_matches == ["0"],
        f"{name} log lacks exactly one anchored successful command exit marker",
    )
    run_id_matches = re.findall(
        r"^WALKSAFE_RUN_ID=([A-Za-z0-9._:-]{8,128})$",
        content,
        flags=re.MULTILINE,
    )
    command_hash_matches = re.findall(
        r"^WALKSAFE_COMMAND_SHA256=([0-9a-f]{64})$",
        content,
        flags=re.MULTILINE,
    )
    expected_command_sha256 = base.sha256_bytes(command.encode("utf-8"))
    require(len(run_id_matches) == 1, f"{name} log lacks one run id")
    require(
        command_hash_matches == [expected_command_sha256],
        f"{name} log command hash differs",
    )
    require(
        re.fullmatch(r"[0-9a-f]{64}", implementation_content_set_sha256) is not None,
        "implementation content set SHA-256 is malformed",
    )
    for marker, expected in (
        (
            CANONICAL_LOG_EXECUTION_SEQUENCE_MARKER,
            str(EXPECTED_EXECUTION_SESSION_EVENT_SEQUENCE),
        ),
        (
            CANONICAL_LOG_EXECUTION_EVENT_ID_MARKER,
            EXPECTED_EXECUTION_SESSION_EVENT_ID,
        ),
        (
            CANONICAL_LOG_EXECUTION_EVENT_SHA256_MARKER,
            EXPECTED_EXECUTION_SESSION_EVENT_SHA256,
        ),
        (
            CANONICAL_LOG_IMPLEMENTATION_CONTENT_SET_SHA256_MARKER,
            implementation_content_set_sha256,
        ),
    ):
        matches = re.findall(
            rf"^{re.escape(marker)}=([^\r\n]*)$",
            content,
            flags=re.MULTILINE,
        )
        require(matches == [expected], f"{name} log {marker} binding differs")
    for pattern in (r"BUILD FAILED", r"\bFAILED\b", r"(?:#\s*)?fail [1-9][0-9]*"):
        require(
            re.search(pattern, content) is None,
            f"{name} log contains a failure marker",
        )
    for marker in required:
        require(marker in content, f"{name} log lacks marker: {marker}")
    started_matches = re.findall(
        r"^WALKSAFE_COMMAND_STARTED_AT=(.+)$",
        content,
        flags=re.MULTILINE,
    )
    ended_matches = re.findall(
        r"^WALKSAFE_COMMAND_ENDED_AT=(.+)$",
        content,
        flags=re.MULTILINE,
    )
    require(
        len(started_matches) == 1 and len(ended_matches) == 1,
        f"{name} log lacks one start/end timestamp pair",
    )
    started_at = parse_aware_timestamp(started_matches[0], f"{name} start timestamp")
    ended_at = parse_aware_timestamp(ended_matches[0], f"{name} end timestamp")
    execution_started_at = parse_aware_timestamp(
        EXPECTED_EXECUTION_SESSION_STARTED_AT,
        "FP-047 execution session start timestamp",
    )
    require(started_at >= execution_started_at, f"{name} starts before FP-047")
    require(started_at <= ended_at, f"{name} end precedes start")
    return {
        "name": name,
        "command": command,
        "command_sha256": expected_command_sha256,
        "run_id": run_id_matches[0],
        "exit_code": 0,
        "output_path": relative(output_path),
        "output_sha256": base.sha256_bytes(content_bytes),
        "started_at": started_matches[0],
        "executed_at": ended_matches[0],
        "execution_event_sequence": EXPECTED_EXECUTION_SESSION_EVENT_SEQUENCE,
        "execution_event_id": EXPECTED_EXECUTION_SESSION_EVENT_ID,
        "execution_event_sha256": EXPECTED_EXECUTION_SESSION_EVENT_SHA256,
        "implementation_content_set_sha256": implementation_content_set_sha256,
        "binding_markers_exact_once": True,
    }


JUNIT_MARKERS = (
    ("xml_files", "WALKSAFE_JUNIT_XML_FILES"),
    ("tests", "WALKSAFE_JUNIT_TESTS"),
    ("failures", "WALKSAFE_JUNIT_FAILURES"),
    ("errors", "WALKSAFE_JUNIT_ERRORS"),
    ("skipped", "WALKSAFE_JUNIT_SKIPPED"),
    ("xml_content_set_sha256", "WALKSAFE_JUNIT_XML_CONTENT_SET_SHA256"),
    ("summarizer_sha256", "WALKSAFE_JUNIT_SUMMARIZER_SHA256"),
)


def summarize_junit_xml(root: Path) -> dict[str, Any]:
    root = root.resolve()
    require(root.is_dir(), f"JUnit XML root is missing: {root}")
    xml_files = sorted(
        (
            path
            for path in root.rglob("*.xml")
            if path.is_file() and not path.is_symlink()
        ),
        key=lambda path: path.relative_to(root).as_posix(),
    )
    require(xml_files, f"JUnit XML files are missing: {root}")
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    manifest = []
    for path in xml_files:
        try:
            suite = ElementTree.parse(path).getroot()
        except ElementTree.ParseError as exc:
            raise BuildError(
                f"JUnit XML parse failed: {path.relative_to(root).as_posix()}: {exc}"
            ) from exc
        require(
            suite.tag.rsplit("}", 1)[-1] == "testsuite",
            f"JUnit XML root is not testsuite: {path.relative_to(root).as_posix()}",
        )
        for key in totals:
            raw = suite.get(key)
            require(
                isinstance(raw, str)
                and re.fullmatch(r"(?:0|[1-9][0-9]*)", raw) is not None,
                f"JUnit XML {key} is not a nonnegative integer: "
                f"{path.relative_to(root).as_posix()}",
            )
            totals[key] += int(raw)
        manifest.append(
            {
                "path": path.relative_to(root).as_posix(),
                "sha256": base.sha256_file(path),
            }
        )
    return {
        "xml_files": len(xml_files),
        **totals,
        "xml_content_set_sha256": base.object_sha256(manifest),
        "summarizer_sha256": base.sha256_file(BUILDER),
    }


def junit_summary_text(summary: dict[str, Any]) -> str:
    return "\n".join(f"{marker}={summary[key]}" for key, marker in JUNIT_MARKERS)


def parse_junit_log(content: str) -> dict[str, Any]:
    parsed: dict[str, Any] = {}
    numeric_keys = {"xml_files", "tests", "failures", "errors", "skipped"}
    for key, marker in JUNIT_MARKERS:
        matches = re.findall(
            rf"^{re.escape(marker)}=([^\r\n]+)$",
            content,
            flags=re.MULTILINE,
        )
        require(len(matches) == 1, f"{marker} marker count differs")
        value = matches[0]
        if key in numeric_keys:
            require(
                re.fullmatch(r"(?:0|[1-9][0-9]*)", value) is not None,
                f"{marker} is not a nonnegative integer",
            )
            parsed[key] = int(value)
        else:
            require(
                re.fullmatch(r"[0-9a-f]{64}", value) is not None,
                f"{marker} is not a lowercase SHA-256",
            )
            parsed[key] = value
    require(parsed["xml_files"] > 0, "adminapp JUnit XML count is not positive")
    require(parsed["tests"] > 0, "adminapp JUnit test count is not positive")
    require(parsed["failures"] == 0, "adminapp JUnit failures are nonzero")
    require(parsed["errors"] == 0, "adminapp JUnit errors are nonzero")
    require(parsed["skipped"] == 0, "adminapp JUnit skipped tests are nonzero")
    require(
        parsed["summarizer_sha256"] == base.sha256_file(BUILDER),
        "adminapp JUnit summarizer SHA-256 differs",
    )
    return parsed


def parse_pytest_summary(content: str, label: str) -> dict[str, int]:
    summaries = re.findall(
        r"^(?:=+\s*)?("
        r"[0-9]+ passed"
        r"(?:, [0-9]+ skipped)?"
        r"(?:, [0-9]+ deselected)?"
        r" in [0-9]+(?:\.[0-9]+)?s"
        r")(?:\s*=+)?$",
        content,
        flags=re.MULTILINE,
    )
    require(len(summaries) == 1, f"{label} pytest summary count differs")
    summary = summaries[0]
    values: dict[str, int] = {}
    for status in ("passed", "skipped", "deselected"):
        match = re.search(rf"(?:^|, )([0-9]+) {status}(?:,| in )", summary)
        values[status] = int(match.group(1)) if match else 0
    return values


def build_verification(
    *,
    focused_log: Path,
    backend_log: Path,
    adminapp_log: Path,
    boundary_log: Path,
    control_plane_log: Path,
    implementation_content_set_sha256: str,
    log_snapshots: dict[Path, bytes] | None = None,
) -> dict[str, Any]:
    snapshots = log_snapshots or {}

    def log_bytes(path: Path) -> bytes:
        return snapshots.get(path) or snapshot_file(path)

    checks = [
        checked_log(
            focused_log,
            output_path=FOCUSED_LOG,
            name="FP-047 focused administrator security tests",
            command=FOCUSED_COMMAND,
            implementation_content_set_sha256=implementation_content_set_sha256,
            required=("32 passed, 1 skipped",),
            content_bytes=log_bytes(focused_log),
        ),
        checked_log(
            backend_log,
            output_path=BACKEND_LOG,
            name="backend internal verification with external integrations excluded",
            command=BACKEND_COMMAND,
            implementation_content_set_sha256=implementation_content_set_sha256,
            required=("18 deselected",),
            content_bytes=log_bytes(backend_log),
        ),
        checked_log(
            adminapp_log,
            output_path=ADMINAPP_LOG,
            name="administrator Android app unit, build and lint verification",
            command=ADMINAPP_COMMAND,
            implementation_content_set_sha256=implementation_content_set_sha256,
            required=("BUILD SUCCESSFUL",),
            content_bytes=log_bytes(adminapp_log),
        ),
        checked_log(
            boundary_log,
            output_path=BOUNDARY_LOG,
            name="user and administrator authority boundary verification",
            command=BOUNDARY_COMMAND,
            implementation_content_set_sha256=implementation_content_set_sha256,
            required=(
                "WalkSafe Android Gateway boundary check: PASS",
                "Canonical OpenAPI and walking-route fixture are current",
            ),
            content_bytes=log_bytes(boundary_log),
        ),
        checked_log(
            control_plane_log,
            output_path=CONTROL_PLANE_LOG,
            name="FP-047 deterministic trace regression",
            command=CONTROL_PLANE_COMMAND,
            implementation_content_set_sha256=implementation_content_set_sha256,
            required=("112 passed",),
            content_bytes=log_bytes(control_plane_log),
        ),
    ]
    focused_summary = parse_pytest_summary(
        log_bytes(focused_log).decode("utf-8"),
        "focused administrator security",
    )
    require(
        focused_summary
        == {"passed": 32, "skipped": 1, "deselected": 0},
        "focused administrator security pytest counts differ",
    )
    backend_summary = parse_pytest_summary(
        log_bytes(backend_log).decode("utf-8"),
        "backend internal",
    )
    require(
        backend_summary
        == {"passed": 361, "skipped": 0, "deselected": 18},
        "backend internal pytest counts differ",
    )
    adminapp_summary = parse_junit_log(log_bytes(adminapp_log).decode("utf-8"))
    require(
        adminapp_summary["xml_files"] == 6
        and adminapp_summary["tests"] == 37
        and adminapp_summary["failures"] == 0
        and adminapp_summary["errors"] == 0
        and adminapp_summary["skipped"] == 0,
        "administrator Android app JUnit counts differ",
    )
    control_summary = parse_pytest_summary(
        log_bytes(control_plane_log).decode("utf-8"),
        "FP-047 deterministic trace regression",
    )
    require(
        control_summary
        == {"passed": 112, "skipped": 0, "deselected": 0},
        "FP-047 deterministic trace regression pytest counts differ",
    )
    boundary_content = log_bytes(boundary_log).decode("utf-8")
    gateway_counts: dict[str, int] = {}
    for label in ("tests", "pass", "fail"):
        matches = re.findall(
            rf"^(?:#\s*)?{label}\s+([0-9]+)$",
            boundary_content,
            flags=re.MULTILINE,
        )
        require(len(matches) == 1, f"boundary log lacks one gateway {label} count")
        gateway_counts[label] = int(matches[0])
    require(
        gateway_counts == {"tests": 60, "pass": 60, "fail": 0},
        "gateway boundary test summary differs",
    )
    latest_log_ended_at = max(
        checks,
        key=lambda check: parse_aware_timestamp(
            check["executed_at"],
            f"{check['name']} log end timestamp",
        ),
    )["executed_at"]
    return {
        "schema_version": "1.0",
        "document_id": (
            "WS-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION-"
            "VERIFICATION-20260726-001"
        ),
        "goal_id": GOAL_ID,
        "kind": "VERIFICATION_RESULT",
        "status": "PASS",
        "observed_at": latest_log_ended_at,
        "checks": checks,
        "backend_test_summary": {
            "focused": "PASS",
            "focused_passed": focused_summary["passed"],
            "focused_skipped_external": focused_summary["skipped"],
            "internal": "PASS",
            "internal_passed": backend_summary["passed"],
            "external_deselected": backend_summary["deselected"],
            "external_postgis_integration": "NOT_RUN",
            "external_reports_integration": "NOT_RUN",
            "postgresql_concurrency": "NOT_RUN",
        },
        "adminapp_test_summary": {
            "unit_tests": "PASS",
            "test_count": adminapp_summary["tests"],
            "failures": adminapp_summary["failures"],
            "errors": adminapp_summary["errors"],
            "skipped": adminapp_summary["skipped"],
            "xml_file_count": adminapp_summary["xml_files"],
            "xml_content_set_sha256": adminapp_summary["xml_content_set_sha256"],
            "summarizer_sha256": adminapp_summary["summarizer_sha256"],
            "debug_build": "PASS",
            "lint": "PASS",
        },
        "authority_boundary_summary": {
            "gateway_typecheck": "PASS",
            "gateway_tests": "PASS",
            "gateway_test_count": gateway_counts["tests"],
            "android_gateway_public_boundary": "PASS",
            "canonical_openapi": "PASS",
            "trace_builder_tests": "PASS",
        },
        "resource_safety": {
            "execution_mode": "SERIAL_SYSTEMD_USER_UNITS",
            "gradle_max_workers": 1,
            "largest_observed_memory_peak": "NOT_CAPTURED",
            "observed_swap_peak": "NOT_CAPTURED",
            "memory_max": "6G",
        },
        "evidence_boundary": completion_boundary(),
    }


def build_successor(
    gap: dict[str, Any],
    gap_text: str,
    backlog: dict[str, Any],
    backlog_text: str,
    observed_at: str,
) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": (
            "WS-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION-"
            "SUCCESSOR-20260726-001"
        ),
        "goal_id": GOAL_ID,
        "kind": "SUCCESSOR_TRACE",
        "status": "PASS",
        "observed_at": observed_at,
        "canonical_update_event_type": "CANONICAL_BINDINGS_UPDATED",
        "resulting_canonical_bindings": {
            "IMPLEMENTATION_BACKLOG": generated_binding(
                "IMPLEMENTATION_BACKLOG",
                BACKLOG_R021_JSON,
                backlog["metadata"]["backlog_id"],
                backlog_text,
            ),
            "IMPLEMENTATION_GAP": generated_binding(
                "IMPLEMENTATION_GAP",
                GAP_R021_JSON,
                gap["metadata"]["report_id"],
                gap_text,
            ),
        },
        "changed_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-047"],
            "IMPLEMENTATION_GAP": ["FP-047", "GAP-056"],
        },
        "next_policy_gap_pair": {
            "source_policy_id": "FP-048",
            "gap_id": "GAP-057",
        },
        "completion_boundary": completion_boundary(),
    }


def build_gap(
    predecessor_gap: dict[str, Any],
    implementation: dict[str, Any],
    implementation_text: str,
    verification: dict[str, Any],
    verification_text: str,
) -> dict[str, Any]:
    counts = predecessor_gap["summary"]["status_counts"]
    require(
        counts["BLOCKED"] == 5
        and counts["CONFLICTING"] == 17
        and counts["EVIDENCE_MISSING"] == 4
        and counts["MISSING"] == 11
        and counts["PARTIAL"] == 31
        and counts["IMPLEMENTED"] == 0,
        "r020 status counts differ",
    )
    report = deepcopy(predecessor_gap)
    report.pop("report_content_sha256", None)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260726-021",
        "version": "0.21.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": verification["observed_at"],
        "baseline_id": predecessor_gap["metadata"]["baseline_id"],
        "baseline_version": predecessor_gap["metadata"]["baseline_version"],
        "predecessor_report_id": predecessor_gap["metadata"]["report_id"],
    }
    report["purpose"] = (
        "FP-047 internal user and administrator login, authorization, revocation, "
        "step-up, recovery-freeze and denial-audit controls are implemented and "
        "verified. GAP-056 alone is reassessed while 67 assessments are carried "
        "forward from r020."
    )
    report["source_bindings"] = predecessor_gap["source_bindings"] + [
        {**base.file_record(BUILDER), "name": "fp047_authority_separation_trace_builder"},
        {**base.file_record(BUILDER_TEST), "name": "fp047_authority_separation_builder_test"},
        {**base.file_record(START_GATE_RECEIPT), "name": "fp047_start_gate"},
        {
            **base.file_record(START_GATE_REPOSITORY_STATE_LOG),
            "name": "fp047_gate_repository_state",
        },
        generated_record(
            IMPLEMENTATION_JSON,
            implementation_text,
            name="fp047_authority_separation_implementation",
        ),
        generated_record(
            VERIFICATION_JSON,
            verification_text,
            name="fp047_authority_separation_verification",
        ),
    ]
    report["source_binding_sha256"] = base.object_sha256(report["source_bindings"])
    files = implementation_files()
    snapshot = {
        "scope_kind": "EPIC_03_FP047_EXACT_31_PATH_SET",
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "before_state_authority": "START_GATE_DIRTY_SNAPSHOT_THEN_PINNED_HEAD",
        "pinned_head": EXPECTED_PINNED_HEAD,
        "file_count": len(files),
        "path_set_sha256": base.object_sha256([item["path"] for item in files]),
        "content_set_sha256": base.object_sha256(
            [{"path": item["path"], "sha256": item["sha256"]} for item in files]
        ),
        "files": files,
    }
    require(
        snapshot["content_set_sha256"]
        == implementation["implementation_content_set_sha256"],
        "implementation snapshot content set differs",
    )
    snapshot["snapshot_sha256"] = base.object_sha256(snapshot)
    report["implementation_snapshot"] = snapshot
    report["summary"] = {
        **predecessor_gap["summary"],
        "status_counts": {
            **counts,
            "BLOCKED": 5,
            "CONFLICTING": 16,
            "EVIDENCE_MISSING": 4,
            "MISSING": 11,
            "PARTIAL": 32,
            "IMPLEMENTED": 0,
        },
        "headline": (
            "GAP-056 internal user/admin authority separation, server authorization, "
            "session revocation, administrator step-up, recovery freeze and denial "
            "audit controls are verified; formal, external integration, actual, "
            "review, production and release evidence remain NOT_RUN, so the result "
            "is PARTIAL."
        ),
    }
    implementation_hash = base.sha256_bytes(implementation_text.encode("utf-8"))
    verification_hash = base.sha256_bytes(verification_text.encode("utf-8"))
    evidence_id = "EVD-FP047-USER-ADMIN-AUTHORITY-SEPARATION-20260726"
    report["evidence_catalog"] = predecessor_gap["evidence_catalog"] + [
        {
            "evidence_id": evidence_id,
            "kind": "INTERNAL_PRODUCER_RESULT",
            "claim": (
                "FP-047 repository-internal user/admin authority separation, "
                "server default-deny authorization, revocation, request-bound "
                "single-use step-up, recovery freeze and denial audit regressions"
            ),
            "producer_goal_id": GOAL_ID,
            "producer_completion_receipt_role": f"WORK_ITEM_COMPLETION::{GOAL_ID}",
            "result_evidence_sha256": {
                "IMPLEMENTATION_RECORD": implementation_hash,
                "VERIFICATION_RESULT": verification_hash,
            },
            "formal_test_status": "NOT_RUN",
            "release_status": "NOT_ELIGIBLE",
        }
    ]
    matches = [
        item for item in report["assessments"] if item["gap_id"] == "GAP-056"
    ]
    require(len(matches) == 1, "GAP-056 assessment count differs")
    assessment = matches[0]
    require(
        assessment.get("source_policy_id") == "FP-047"
        and assessment.get("status") == "CONFLICTING",
        "GAP-056 predecessor assessment differs",
    )
    assessment["status"] = "PARTIAL"
    assessment["formal_test_status"] = "NOT_RUN"
    assessment["current_implementation_in_plain_language"] = (
        "The user Android app, Android Gateway and administrator app/backend use "
        "separate authority boundaries. The server decides roles and ownership, "
        "rejects unknown administrator routes, rotates and revokes user device "
        "sessions, requires administrator password and TOTP, binds high-risk "
        "assertions to one request and records both allowed and denied outcomes."
    )
    assessment["rationale"] = (
        "The exact 31-path internal implementation and five canonical verification "
        "layers passed. Formal TC-FP-047-01 through 07, external PostGIS/reports "
        "integration, PostgreSQL concurrency, actual user/admin/device, recovery "
        "drill, external reviews, production and release gates remain NOT_RUN."
    )
    assessment["evidence_ids"] = list(
        dict.fromkeys([*assessment.get("evidence_ids", []), evidence_id])
    )
    assessment["fp047_reassessment"] = {
        "goal_id": GOAL_ID,
        "implementation_record_sha256": implementation_hash,
        "verification_result_sha256": verification_hash,
        "internal_control_status": "PASS",
        **completion_boundary(),
    }
    assessment.pop("assessment_sha256", None)
    assessment["assessment_sha256"] = base.object_sha256(assessment)
    carried_ids = [
        item["gap_id"]
        for item in report["assessments"]
        if item["gap_id"] != "GAP-056"
    ]
    require(len(carried_ids) == 67, "r020 carry-forward gap count differs")
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC03_FP047_GAP056_REASSESSMENT_WITH_R020_CARRY_FORWARD",
        "directly_reassessed_gap_ids": ["GAP-056"],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": ["GAP-056"],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": carried_ids,
        "carry_forward_warning": "The other 67 assessments are preserved from r020.",
        "next_adjacent_gap": {
            "gap_id": "GAP-057",
            "source_policy_id": "FP-048",
            "reason": "EPIC-03 deterministic execution order advances to order 20.",
        },
        "predecessor": {
            "report_id": predecessor_gap["metadata"]["report_id"],
            "path": relative(GAP_R020_JSON),
            "file_sha256": EXPECTED_PREDECESSOR_SHA256[GAP_R020_JSON],
            "evidence_count": len(predecessor_gap["evidence_catalog"]),
            "revalidated_wholesale_in_r021": False,
        },
    }
    report["authorization_boundary"] = {
        **predecessor_gap["authorization_boundary"],
        "diagnosis_only": True,
        "implementation_modified": False,
        "implementation_modified_by_this_report": False,
        "implementation_change_observed": True,
        "formal_test_completion_claimed": False,
        "remaining_gates_waived": False,
        "release_status": "NOT_ELIGIBLE",
    }
    report["limitations"] = [
        "Only GAP-056 is directly reassessed; 67 assessments are carried from r020.",
        "TC-FP-047-01 through TC-FP-047-07 are NOT_RUN.",
        "External PostGIS/reports integration and PostgreSQL concurrency are NOT_RUN.",
        "Actual user, administrator and device verification is NOT_RUN.",
        "The single-administrator recovery drill is NOT_RUN.",
        "External security, legal, privacy and accessibility reviews are NOT_RUN.",
        "Production deployment and five unwaived release gates are NOT_RUN.",
    ]
    report["report_content_sha256"] = base.object_sha256(report)
    return report


def build_backlog(
    predecessor_backlog: dict[str, Any],
    gap: dict[str, Any],
    observed_at: str,
) -> dict[str, Any]:
    backlog = deepcopy(predecessor_backlog)
    backlog.pop("backlog_content_sha256", None)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-021",
        "version": "0.21.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": observed_at,
        "predecessor_backlog_id": predecessor_backlog["metadata"]["backlog_id"],
    }
    backlog["source_predecessor"] = {
        "path": relative(BACKLOG_R020_JSON),
        "file_sha256": EXPECTED_PREDECESSOR_SHA256[BACKLOG_R020_JSON],
        "preserved_unchanged": False,
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    matching = [
        item
        for item in backlog["next_action_sequence"]
        if item.get("source_policy_id") == "FP-047"
    ]
    require(len(matching) == 1, "FP-047 backlog action count differs")
    require(
        matching[0].get("status") == "CONFLICTING",
        "FP-047 backlog status differs",
    )
    matching[0]["status"] = "PARTIAL"
    matching[0]["action"] = (
        "Connect the verified internal user/admin authority separation, revocation, "
        "step-up, recovery-freeze and denial-audit controls to formal tests, external "
        "database integration, actual roles/devices, recovery drill, external reviews "
        "and release verification."
    )
    epic = next(item for item in backlog["epics"] if item["epic_id"] == "EPIC-03")
    epic["current_status"] = "IN_PROGRESS"
    epic["current_status_reason"] = (
        "FP-047 internal user/admin login and authorization separation controls are "
        "verified; FP-048 and later policies plus external and release verification remain."
    )
    next_row = next(
        item
        for item in backlog["next_action_sequence"]
        if item.get("source_policy_id") == "FP-048"
    )
    backlog["next_single_action"] = {
        "epic_id": "EPIC-03",
        "source_policy_id": "FP-048",
        "gap_id": "GAP-057",
        "status": "PLANNED_NEXT",
        "action": next_row["action"],
    }
    backlog["backlog_content_sha256"] = base.object_sha256(backlog)
    return backlog


def build_overlay(
    predecessor_overlay: dict[str, Any],
    implementation: dict[str, Any],
    implementation_text: str,
    verification: dict[str, Any],
    verification_text: str,
    gap: dict[str, Any],
    gap_text: str,
    backlog: dict[str, Any],
    backlog_text: str,
) -> dict[str, Any]:
    return {
        "schema_version": "1.0.0",
        "metadata": {
            "overlay_id": (
                "WS-EPIC-03-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION-"
                "ACTIVE-LEDGER-OVERLAY-20260726-001"
            ),
            "version": "0.1.0",
            "as_of": "2026-07-26",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": (
                "EPIC-03 FP-047 user/admin login and authorization separation "
                "successor overlay"
            ),
        },
        "authority_boundary": {
            "predecessor_overlay_preserved": True,
            "canonical_active_files_modified_by_builder": False,
            "approved_baseline_or_predecessor_modified": False,
            "creates_new_product_policy": False,
            "formal_test_completion_claimed": False,
            "external_database_integration_completion_claimed": False,
            "actual_user_admin_device_completion_claimed": False,
            "single_admin_recovery_drill_completion_claimed": False,
            "external_review_completion_claimed": False,
            "production_deployment_completion_claimed": False,
            "epic_in_progress_claimed": True,
            "epic_complete_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": {
            "predecessor_overlay": base.file_record(FP012_OVERLAY_JSON),
            "implementation_record": generated_record(
                IMPLEMENTATION_JSON,
                implementation_text,
                name="fp047_implementation",
            ),
            "verification_result": generated_record(
                VERIFICATION_JSON,
                verification_text,
                name="fp047_verification",
            ),
            "implementation_gap": generated_binding(
                "IMPLEMENTATION_GAP",
                GAP_R021_JSON,
                gap["metadata"]["report_id"],
                gap_text,
            ),
            "implementation_backlog": generated_binding(
                "IMPLEMENTATION_BACKLOG",
                BACKLOG_R021_JSON,
                backlog["metadata"]["backlog_id"],
                backlog_text,
            ),
        },
        "goal_state": {
            "goal_id": GOAL_ID,
            "internal_result": "PARTIAL_IMPLEMENTATION_VERIFIED",
            "gap_id": "GAP-056",
            "gap_status": "PARTIAL",
            "next_source_policy_id": "FP-048",
            "next_gap_id": "GAP-057",
        },
        "completion_boundary": completion_boundary(),
    }


def gap_markdown(value: dict[str, Any]) -> str:
    row = next(item for item in value["assessments"] if item["gap_id"] == "GAP-056")
    return (
        "# WalkSafe 구현 Gap 분석 r021\n\n"
        "- 직접 재평가: `FP-047 / GAP-056`\n"
        f"- 판정: `{row['status']}` (`CONFLICTING → PARTIAL`)\n"
        "- 사용자·관리자 권한 분리, 서버 default-deny, 폐기, step-up, 복구 동결, "
        "거부 감사 내부 회귀: `PASS`\n"
        "- TC-FP-047-01~07·외부 DB 통합·실사용자/관리자/기기·복구훈련·"
        "외부 검토·운영 배포: `NOT_RUN`\n"
        "- 출시 Gate 5개: `NOT_RUN` / 미면제\n"
        "- 출시: `NOT_ELIGIBLE`\n\n"
        f"{row['rationale']}\n"
    )


def backlog_markdown(value: dict[str, Any]) -> str:
    action = value["next_single_action"]
    return (
        "# WalkSafe 구현 보완 Backlog r021\n\n"
        "- EPIC-03: `IN_PROGRESS`\n"
        f"- 다음 정책·Gap: `{action['source_policy_id']} / {action['gap_id']}`\n"
        "- 다음 정책: `저장 위치별 암호화와 키 분리·회전·감사`\n"
        f"- 다음 작업: {action['action']}\n"
    )


def overlay_markdown(value: dict[str, Any]) -> str:
    return (
        "# EPIC-03 FP-047 사용자·관리자 로그인·권한 분리 Active ledger overlay\n\n"
        f"- Overlay: `{value['metadata']['overlay_id']}`\n"
        "- FP-047 내부 권한 분리·폐기·재확인·복구 동결·감사 구현·검증: "
        "`PARTIAL_IMPLEMENTATION_VERIFIED`\n"
        "- 정식·외부 DB 통합·실사용자/관리자/기기·복구훈련·외부 검토·배포: "
        "`NOT_RUN`\n"
        "- 출시 Gate 5개: `NOT_RUN` / 미면제\n"
        "- 출시: `NOT_ELIGIBLE`\n"
    )


def build_review_subject(
    implementation: dict[str, Any],
    verification: dict[str, Any],
    result_hashes: dict[str, str],
) -> dict[str, Any]:
    changed_artifacts = implementation["changed_artifacts"]
    require(
        [item["path"] for item in changed_artifacts] == list(EXACT_SCOPE_PATHS),
        "review-subject implementation path order differs",
    )
    require(
        len(changed_artifacts) == 31,
        "review-subject implementation path count differs",
    )
    content_set_sha256 = base.object_sha256(
        [
            {"path": item["path"], "sha256": item["after_sha256"]}
            for item in changed_artifacts
        ]
    )
    require(
        content_set_sha256 == implementation["implementation_content_set_sha256"],
        "review-subject implementation content set differs",
    )
    receipts = {item["output_path"]: item for item in verification["checks"]}
    require(
        len(receipts) == len(verification["checks"]),
        "review-subject verification receipt paths are not unique",
    )
    summary = verification["adminapp_test_summary"]
    canonical_log_path = relative(ADMINAPP_LOG)
    require(
        canonical_log_path in receipts,
        "review-subject adminapp JUnit log receipt is missing",
    )
    require(
        summary["xml_file_count"] > 0
        and summary["test_count"] > 0
        and summary["failures"] == 0
        and summary["errors"] == 0
        and summary["skipped"] == 0,
        "review-subject adminapp JUnit counts differ",
    )
    require(
        summary["summarizer_sha256"] == base.sha256_file(BUILDER),
        "review-subject adminapp JUnit summarizer differs",
    )
    return {
        "schema_version": "1.0",
        "evidence_type": "INTERNAL_REVIEW_SUBJECT",
        "goal_id": GOAL_ID,
        "reviewed_result_sha256_by_kind": result_hashes,
        "implementation_scope": {
            "scope": "EXACT_31_PATH_SET",
            "exact_path_count": 31,
            "product_path_count": 26,
            "tooling_path_count": 5,
            "product_paths": list(IMPLEMENTATION_PATHS),
            "tooling_paths": list(TOOLING_PATHS),
            "content_set_sha256": content_set_sha256,
        },
        "adminapp_junit_evidence": {
            "canonical_log_path": canonical_log_path,
            "canonical_log_sha256": receipts[canonical_log_path]["output_sha256"],
            "markers_exact_once": True,
            "xml_files": summary["xml_file_count"],
            "tests": summary["test_count"],
            "failures": summary["failures"],
            "errors": summary["errors"],
            "skipped": summary["skipped"],
            "xml_content_set_sha256": summary["xml_content_set_sha256"],
            "summarizer_path": relative(BUILDER),
            "summarizer_sha256": summary["summarizer_sha256"],
        },
        "verification_receipts": deepcopy(verification["checks"]),
        "completion_boundary": completion_boundary(),
    }


def build_review(
    result_hashes: dict[str, str],
    review_subject_sha256: str,
    attestation: dict[str, Any],
    attestation_sha256: str,
    latest_log_ended_at: str,
) -> dict[str, Any]:
    require(
        attestation.get("schema_version") == "1.0"
        and attestation.get("evidence_type") == "INTERNAL_REVIEW_ATTESTATION"
        and attestation.get("goal_id") == GOAL_ID,
        "review attestation identity differs",
    )
    require(
        attestation.get("review_subject_sha256") == review_subject_sha256
        and attestation.get("reviewed_result_sha256_by_kind") == result_hashes,
        "review attestation subject binding differs",
    )
    require(
        attestation.get("decision") == "APPROVED"
        and attestation.get("findings", {}).get("blocking") == 0
        and attestation.get("findings", {}).get("major_open") == 0,
        "review attestation is not approval without blockers",
    )
    reviewer_id = attestation.get("reviewer_id")
    reviewer_task = attestation.get("reviewer_task")
    reviewed_at = attestation.get("reviewed_at")
    require(
        reviewer_id == EXPECTED_REVIEWER_ID
        and reviewer_id == reviewer_id.strip()
        and reviewer_id.strip() != EXECUTOR_ID.strip(),
        "executor and reviewer identities must differ",
    )
    require(
        reviewer_task == EXPECTED_REVIEWER_TASK
        and reviewer_task == reviewer_task.strip()
        and reviewer_task.strip() != EXECUTOR_TASK.strip(),
        "reviewer task is not separate from the executor task",
    )
    require(
        parse_aware_timestamp(reviewed_at, "review attestation reviewed_at")
        >= parse_aware_timestamp(
            latest_log_ended_at,
            "latest canonical log end timestamp",
        ),
        "review attestation predates the latest canonical log end",
    )
    require(
        attestation.get("review_boundary") == EXPECTED_REVIEW_BOUNDARY,
        "review attestation boundary differs",
    )
    return {
        "schema_version": "1.0",
        "document_id": (
            "WS-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION-"
            "INTERNAL-REVIEW-20260726-001"
        ),
        "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
        "goal_id": GOAL_ID,
        "status": "PASS",
        "reviewer_id": reviewer_id,
        "reviewer_task": reviewer_task,
        "review_subject_sha256": review_subject_sha256,
        "reviewed_result_sha256_by_kind": deepcopy(
            attestation["reviewed_result_sha256_by_kind"]
        ),
        "reviewed_at": reviewed_at,
        "attestation_provenance": {
            "path": relative(REVIEW_ATTESTATION_JSON),
            "sha256": attestation_sha256,
        },
        "findings": deepcopy(attestation["findings"]),
        "review_boundary": deepcopy(attestation["review_boundary"]),
    }


def build_receipt(
    result_hashes: dict[str, str],
    review: dict[str, Any],
    review_text: str,
    execution_session: dict[str, Any],
    output_manifest: list[dict[str, str]],
) -> dict[str, Any]:
    require(
        review.get("reviewer_id") != EXECUTOR_ID,
        "executor and reviewer identities must differ",
    )
    boundary = completion_boundary()
    boundary.pop("formal_test_ids")
    boundary.pop("release_gate_count")
    require(
        len(output_manifest) == len(OUTPUT_PATHS) - 1
        and [item["path"] for item in output_manifest]
        == [relative(path) for path in OUTPUT_PATHS if path != RECEIPT_JSON],
        "completion output manifest differs",
    )
    return {
        "schema_version": "1.0",
        "document_id": (
            "WS-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION-"
            "WORK-ITEM-COMPLETION-20260726-001"
        ),
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": base.sha256_file(GOAL_PATH),
        "work_item_id": (
            "EPIC-03-FP047-USER-ADMIN-LOGIN-AUTHORIZATION-SEPARATION"
        ),
        "source_policy_ids": ["FP-047"],
        "gap_ids": ["GAP-056"],
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "execution_start_event_sha256": EXPECTED_EXECUTION_EVENT_SHA256,
        "execution_session_event": {
            "sequence": execution_session["sequence"],
            "event_id": execution_session["event_id"],
            "event_type": execution_session["event_type"],
            "event_sha256": execution_session["event_sha256"],
        },
        "implementation_start_gate_binding": {
            "document_id": EXPECTED_START_GATE_RECEIPT_ID,
            "path": relative(START_GATE_RECEIPT),
            "file_sha256": EXPECTED_START_GATE_RECEIPT_SHA256,
        },
        "execution_window": {
            "started_at": execution_session["occurred_at"],
            "ended_at": review["reviewed_at"],
        },
        "completed_at": review["reviewed_at"],
        "executor": {
            "id": EXECUTOR_ID,
            "task": EXECUTOR_TASK,
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "authority": "GRAPH_V2_4_STANDING_EXECUTION_AUTHORITY",
        },
        "reviewer": {
            "id": review["reviewer_id"],
            "task": review["reviewer_task"],
            "role": "SEPARATE_INTERNAL_REVIEWER",
            "separate_internal_review_pass": True,
            "external_independence_claimed": False,
            "authority": "INTERNAL_REPOSITORY_CONTROL",
            "decision": "APPROVED",
            "decided_at": review["reviewed_at"],
        },
        "reviewer_provenance": {
            "path": relative(REVIEW_JSON),
            "sha256": base.sha256_bytes(review_text.encode("utf-8")),
        },
        "result_evidence": [
            {
                "kind": kind,
                "path": relative(path),
                "sha256": result_hashes[kind],
            }
            for kind, path in (
                ("IMPLEMENTATION_RECORD", IMPLEMENTATION_JSON),
                ("VERIFICATION_RESULT", VERIFICATION_JSON),
                ("SUCCESSOR_TRACE", SUCCESSOR_JSON),
            )
        ],
        "output_evidence_manifest": output_manifest,
        "output_evidence_manifest_sha256": base.object_sha256(output_manifest),
        "completion_boundary": boundary,
        "generated_at": review["reviewed_at"],
    }


def build_outputs(
    *,
    focused_log: Path = FOCUSED_LOG,
    backend_log: Path = BACKEND_LOG,
    adminapp_log: Path = ADMINAPP_LOG,
    boundary_log: Path = BOUNDARY_LOG,
    control_plane_log: Path = CONTROL_PLANE_LOG,
    review_subject: Path = REVIEW_SUBJECT_JSON,
    review_attestation: Path = REVIEW_ATTESTATION_JSON,
    test_mode: bool = False,
    review_subject_only: bool = False,
    expected_implementation_content_set_sha256: str | None = None,
) -> dict[Path, str]:
    canonical_inputs = [
        (focused_log, FOCUSED_LOG),
        (backend_log, BACKEND_LOG),
        (adminapp_log, ADMINAPP_LOG),
        (boundary_log, BOUNDARY_LOG),
        (control_plane_log, CONTROL_PLANE_LOG),
    ]
    if not review_subject_only:
        canonical_inputs.extend(
            [
                (review_subject, REVIEW_SUBJECT_JSON),
                (review_attestation, REVIEW_ATTESTATION_JSON),
            ]
        )
    evidence_snapshots: dict[Path, bytes] = {}
    for supplied, canonical in canonical_inputs:
        evidence_snapshots[supplied] = snapshot_file(
            supplied,
            expected_path=canonical if not test_mode else None,
            confinement_root=ROOT if not test_mode else None,
        )
    checkpoint = json_from_snapshot(
        snapshot_file(
            CHECKPOINT,
            expected_path=CHECKPOINT,
            confinement_root=ROOT,
        ),
        "continuation checkpoint",
    )
    (
        predecessor_gap,
        predecessor_backlog,
        predecessor_overlay,
        execution_session,
    ) = validate_inputs(checkpoint)
    implementation = build_implementation(
        observed_at=EXPECTED_EXECUTION_STARTED_AT,
        expected_content_set_sha256=expected_implementation_content_set_sha256,
    )
    verification = build_verification(
        focused_log=focused_log,
        backend_log=backend_log,
        adminapp_log=adminapp_log,
        boundary_log=boundary_log,
        control_plane_log=control_plane_log,
        implementation_content_set_sha256=implementation[
            "implementation_content_set_sha256"
        ],
        log_snapshots=evidence_snapshots,
    )
    implementation["observed_at"] = min(
        verification["checks"],
        key=lambda check: parse_aware_timestamp(
            check["started_at"],
            f"{check['name']} log start timestamp",
        ),
    )["started_at"]
    implementation_text = json_text(implementation)
    verification_text = json_text(verification)
    gap = build_gap(
        predecessor_gap,
        implementation,
        implementation_text,
        verification,
        verification_text,
    )
    gap_text = json_text(gap)
    backlog = build_backlog(
        predecessor_backlog,
        gap,
        verification["observed_at"],
    )
    backlog_text = json_text(backlog)
    overlay = build_overlay(
        predecessor_overlay,
        implementation,
        implementation_text,
        verification,
        verification_text,
        gap,
        gap_text,
        backlog,
        backlog_text,
    )
    successor = build_successor(
        gap,
        gap_text,
        backlog,
        backlog_text,
        verification["observed_at"],
    )
    successor_text = json_text(successor)
    result_hashes = {
        "IMPLEMENTATION_RECORD": base.sha256_bytes(implementation_text.encode("utf-8")),
        "VERIFICATION_RESULT": base.sha256_bytes(verification_text.encode("utf-8")),
        "SUCCESSOR_TRACE": base.sha256_bytes(successor_text.encode("utf-8")),
    }
    review_subject_value = build_review_subject(
        implementation,
        verification,
        result_hashes,
    )
    review_subject_text = json_text(review_subject_value)
    if review_subject_only:
        pre_review_outputs = {
            IMPLEMENTATION_JSON: implementation_text,
            VERIFICATION_JSON: verification_text,
            SUCCESSOR_JSON: successor_text,
            REVIEW_SUBJECT_JSON: review_subject_text,
        }
        require(
            tuple(pre_review_outputs) == PRE_REVIEW_OUTPUT_PATHS,
            "pre-review output path order or membership differs",
        )
        return pre_review_outputs
    require(
        evidence_snapshots[review_subject] == review_subject_text.encode("utf-8"),
        "review subject differs from current implementation or verification",
    )
    review_subject_sha256 = base.sha256_bytes(review_subject_text.encode("utf-8"))
    attestation_bytes = evidence_snapshots[review_attestation]
    attestation = json_from_snapshot(attestation_bytes, "review attestation")
    review = build_review(
        result_hashes,
        review_subject_sha256,
        attestation,
        base.sha256_bytes(attestation_bytes),
        verification["observed_at"],
    )
    review_text = json_text(review)
    non_receipt_outputs = {
        IMPLEMENTATION_JSON: implementation_text,
        VERIFICATION_JSON: verification_text,
        SUCCESSOR_JSON: successor_text,
        REVIEW_SUBJECT_JSON: review_subject_text,
        REVIEW_JSON: review_text,
        GAP_R021_JSON: gap_text,
        GAP_R021_MD: gap_markdown(gap),
        BACKLOG_R021_JSON: backlog_text,
        BACKLOG_R021_MD: backlog_markdown(backlog),
        FP047_OVERLAY_JSON: json_text(overlay),
        FP047_OVERLAY_MD: overlay_markdown(overlay),
    }
    output_manifest = [
        {
            "path": relative(path),
            "sha256": base.sha256_bytes(non_receipt_outputs[path].encode("utf-8")),
        }
        for path in OUTPUT_PATHS
        if path != RECEIPT_JSON
    ]
    receipt = build_receipt(
        result_hashes,
        review,
        review_text,
        execution_session,
        output_manifest,
    )
    outputs = {
        IMPLEMENTATION_JSON: implementation_text,
        VERIFICATION_JSON: verification_text,
        SUCCESSOR_JSON: successor_text,
        REVIEW_SUBJECT_JSON: review_subject_text,
        REVIEW_JSON: review_text,
        RECEIPT_JSON: json_text(receipt),
        GAP_R021_JSON: gap_text,
        GAP_R021_MD: non_receipt_outputs[GAP_R021_MD],
        BACKLOG_R021_JSON: backlog_text,
        BACKLOG_R021_MD: non_receipt_outputs[BACKLOG_R021_MD],
        FP047_OVERLAY_JSON: non_receipt_outputs[FP047_OVERLAY_JSON],
        FP047_OVERLAY_MD: non_receipt_outputs[FP047_OVERLAY_MD],
    }
    require(tuple(outputs) == OUTPUT_PATHS, "output path order or membership differs")
    return outputs


def output_destination(path: Path, output_root: Path) -> Path:
    require(path in OUTPUT_PATHS, f"unapproved output path: {path}")
    root = output_root.resolve()
    destination = (root / path.relative_to(ROOT)).resolve()
    require(
        destination == root or root in destination.parents,
        "staged output escapes output root",
    )
    return destination


def write_or_check_outputs(
    outputs: dict[Path, str],
    *,
    write: bool,
    output_root: Path = ROOT,
) -> None:
    output_paths = tuple(outputs)
    require(
        output_paths in (PRE_REVIEW_OUTPUT_PATHS, OUTPUT_PATHS),
        "output path order or membership differs",
    )
    destinations = {
        path: output_destination(path, output_root)
        for path in outputs
    }
    if write:
        if output_paths == PRE_REVIEW_OUTPUT_PATHS:
            staged: dict[Path, Path] = {}
            try:
                for path, content in outputs.items():
                    destination = destinations[path]
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    temporary = destination.with_name(
                        f".{destination.name}.fp047-pre-review.tmp"
                    )
                    require(
                        not os.path.lexists(temporary),
                        f"temporary output already exists: {temporary}",
                    )
                    staged[path] = temporary
                    with temporary.open("xb") as handle:
                        handle.write(content.encode("utf-8"))
                        handle.flush()
                        os.fsync(handle.fileno())
                for path in outputs:
                    os.replace(staged[path], destinations[path])
            finally:
                for temporary in staged.values():
                    temporary.unlink(missing_ok=True)
            return
        for path, content in outputs.items():
            destination = destinations[path]
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(content, encoding="utf-8")
        return
    for path, content in outputs.items():
        destination = destinations[path]
        require(destination.is_file(), f"output is missing: {destination}")
        require(not destination.is_symlink(), f"output is a symlink: {destination}")
        require(
            destination.read_bytes() == content.encode("utf-8"),
            f"output differs: {destination}",
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--write-review-subject", action="store_true")
    mode.add_argument("--summarize-junit-xml", type=Path)
    parser.add_argument("--output-root", type=Path, default=ROOT)
    parser.add_argument("--implementation-content-set-sha256")
    args = parser.parse_args(argv)
    try:
        if args.summarize_junit_xml is not None:
            print(junit_summary_text(summarize_junit_xml(args.summarize_junit_xml)))
            return 0
        if args.write_review_subject:
            subject_output = build_outputs(
                review_subject_only=True,
                expected_implementation_content_set_sha256=(
                    args.implementation_content_set_sha256
                ),
            )
            write_or_check_outputs(
                subject_output,
                write=True,
                output_root=args.output_root,
            )
            subject_text = subject_output[REVIEW_SUBJECT_JSON]
            print(
                "FP-047 user/admin authority-separation trace: PASS "
                f"outputs={len(subject_output)} "
                f"review_subject_sha256={base.sha256_bytes(subject_text.encode('utf-8'))} "
                "mode=WRITE_REVIEW_SUBJECT"
            )
            return 0
        outputs = build_outputs(
            expected_implementation_content_set_sha256=(
                args.implementation_content_set_sha256
            ),
        )
        write_or_check_outputs(
            outputs,
            write=args.write,
            output_root=args.output_root,
        )
    except (
        BuildError,
        base.BuildError,
        OSError,
        ValueError,
        KeyError,
        json.JSONDecodeError,
    ) as exc:
        print(f"FP-047 user/admin authority-separation trace: FAIL: {exc}", file=sys.stderr)
        return 1
    mode_name = "WRITE" if args.write else "CHECK"
    print(
        f"FP-047 user/admin authority-separation trace: PASS outputs={len(outputs)} "
        f"next=FP-048/GAP-057 mode={mode_name}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
