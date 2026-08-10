#!/usr/bin/env python3
"""Build the focused FP-014 permission-denial/revocation implementation trace."""

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
    from scripts import build_walksafe_fp015_withdrawal_account_deletion_trace_20260725 as predecessor
except ModuleNotFoundError:
    import build_walksafe_fp015_withdrawal_account_deletion_trace_20260725 as predecessor


base = predecessor.base
ROOT = Path(__file__).resolve().parents[1]
BUILDER = Path(__file__).resolve()
BUILDER_TEST = (
    ROOT / "tests/test_walksafe_fp014_permission_denial_revocation_trace_20260726.py"
)

GOAL_ID = "WS-GOAL-EPIC-02-FP-014-R001"
GOAL_PATH = (
    ROOT
    / "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-02/"
    "epic-02-fp014-permission-denial-revocation-r001.md"
)
RESULT_DIR = ROOT / f"docs/control/execution/goal-results/{GOAL_ID}"
IMPLEMENTATION_JSON = RESULT_DIR / "implementation-record.json"
VERIFICATION_JSON = RESULT_DIR / "verification-result.json"
SUCCESSOR_JSON = RESULT_DIR / "successor-trace.json"
REVIEW_SUBJECT_JSON = RESULT_DIR / "review-subject.json"
REVIEW_JSON = RESULT_DIR / "independent-review.json"
REVIEW_ATTESTATION_JSON = RESULT_DIR / "review-attestation.json"
RECEIPT_JSON = RESULT_DIR / "completion-receipt.json"
FOCUSED_LOG = RESULT_DIR / "logs/focused-android-tests.log"
FULL_ANDROID_LOG = RESULT_DIR / "logs/full-android-verification.log"
GATEWAY_LOG = RESULT_DIR / "logs/android-gateway-verification.log"
BOUNDARY_LOG = RESULT_DIR / "logs/android-gateway-boundary.log"
CONTROL_PLANE_LOG = RESULT_DIR / "logs/control-plane-verification.log"

FOCUSED_COMMAND = (
    "rm -rf app/build/test-results/testDebugUnitTest && "
    "./gradlew :app:testDebugUnitTest "
    "--tests '*PermissionSessionPolicyTest' "
    "--tests '*PermissionSessionLifecycleStaticTest' "
    "--tests '*MainActivityAccessibilityStaticTest' "
    "--tests '*MainActivityWalkSessionLifecycleStaticTest' "
    "--tests '*PersistentFieldSessionLogTest' "
    "--rerun-tasks --offline --no-daemon "
    "--max-workers=1 -Dkotlin.compiler.execution.strategy=in-process"
)
FULL_ANDROID_COMMAND = (
    "rm -rf app/build/test-results/testDebugUnitTest && "
    "./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug "
    "--rerun-tasks --offline --no-daemon --max-workers=1 "
    "-Dkotlin.compiler.execution.strategy=in-process"
)
FULL_ANDROID_MIN_TESTS = 665
GATEWAY_COMMAND = "cd apps/android-gateway && npm run typecheck && npm test"
BOUNDARY_COMMAND = (
    "python3 -B scripts/check_walksafe_android_gateway_boundary_20260723.py --root ."
)
CONTROL_PLANE_COMMAND = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python -m py_compile scripts/"
    "build_walksafe_fp014_permission_denial_revocation_trace_20260726.py && "
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python -m pytest -q tests/"
    "test_walksafe_fp014_permission_denial_revocation_trace_20260726.py"
)

GAP_R017_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260725-r017.json"
)
BACKLOG_R017_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-remediation-backlog-20260725-r017.json"
)
FP015_OVERLAY_JSON = (
    ROOT / "docs/control/execution/"
    "walksafe-epic-02-fp015-withdrawal-account-deletion-active-ledger-"
    "overlay-20260725-r001.json"
)
GAP_R018_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260726-r018.json"
)
GAP_R018_MD = GAP_R018_JSON.with_suffix(".md")
BACKLOG_R018_JSON = (
    ROOT / "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260726-r018.json"
)
BACKLOG_R018_MD = BACKLOG_R018_JSON.with_suffix(".md")
FP014_OVERLAY_JSON = (
    ROOT / "docs/control/execution/"
    "walksafe-epic-02-fp014-permission-denial-revocation-active-ledger-"
    "overlay-20260726-r001.json"
)
FP014_OVERLAY_MD = FP014_OVERLAY_JSON.with_suffix(".md")
CHECKPOINT = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"
START_GATE_RECEIPT = (
    ROOT / "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP014-20260726-001/"
    "implementation-start-gate-receipt.json"
)
START_GATE_REPOSITORY_STATE_LOG = START_GATE_RECEIPT.parent / "19-REPOSITORY_STATE.log"

EXPECTED_PREDECESSOR_SHA256 = {
    GAP_R017_JSON: "eb155dbec7228882871dea7df76c8e24cfef0fe51be9ec95e7f91c5e5b1e7be0",
    BACKLOG_R017_JSON: "0ca4724ffad40891503c7df1578e4bbb274991276ef852d1dd3b74ca3011dc35",
    FP015_OVERLAY_JSON: "26db3b5742dfaf722808a28c4112019c5ff1ceb740d6d63f973f742008fe2fd4",
}
EXPECTED_GOAL_SHA256 = (
    "8790f46e43d410c1fb4dc5c6c0b7013fa2dfc87b511a249068df4bdddbc2c06e"
)
EXPECTED_START_GATE_RECEIPT_SHA256 = (
    "5399d1c272d4c2a2d0d7fe4cd75c3e31de82a32362dd12ccc4dee454b4426720"
)
EXPECTED_START_GATE_RECEIPT_ID = (
    "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP014-20260726-001"
)
EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256 = (
    "3301e0793102e002aaa43067f133b86af5ccb1f0d5b9a97b9eedb220da8205a2"
)
EXPECTED_PINNED_HEAD = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"
EXPECTED_EXECUTION_EVENT_SEQUENCE = 19
EXPECTED_EXECUTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP014-20260726-001"
)
EXPECTED_EXECUTION_EVENT_SHA256 = (
    "2158aa372a00af3024f2a80eeb6e98471fd07b97ec570c07edd5b5ce07c3551e"
)
EXPECTED_EXECUTION_STARTED_AT = "2026-07-26T03:03:19+09:00"
EXECUTOR_ID = "CODEX-FP014-PERMISSION-DENIAL-REVOCATION-IMPLEMENTER-20260726-001"
EXECUTOR_TASK = "FP014_PERMISSION_DENIAL_REVOCATION_IMPLEMENTATION"
EXPECTED_REVIEWER_ID = "WS-FP014-INDEPENDENT-REVIEWER-001"
EXPECTED_REVIEWER_TASK = "/root/fp014_final_evidence_review"
COMMAND_EXIT_CODE_MARKER = "WALKSAFE_COMMAND_EXIT_CODE"
CANONICAL_LOG_EXECUTION_SEQUENCE_MARKER = "WALKSAFE_EXECUTION_EVENT_SEQUENCE"
CANONICAL_LOG_EXECUTION_EVENT_ID_MARKER = "WALKSAFE_EXECUTION_EVENT_ID"
CANONICAL_LOG_EXECUTION_EVENT_SHA256_MARKER = "WALKSAFE_EXECUTION_EVENT_SHA256"
CANONICAL_LOG_IMPLEMENTATION_CONTENT_SET_SHA256_MARKER = (
    "WALKSAFE_IMPLEMENTATION_EXACT_11_CONTENT_SET_SHA256"
)
EXPECTED_REVIEW_BOUNDARY = {
    "separate_internal_review_pass": True,
    "external_independence_claimed": False,
    "formal_tests_remain_not_run": True,
    "actual_user_tests_remain_not_run": True,
    "actual_talkback_user_tests_remain_not_run": True,
    "actual_device_tests_remain_not_run": True,
    "android_platform_review_remains_not_run": True,
    "operational_permission_profile_approval_remains_not_run": True,
    "production_deployment_remains_not_run": True,
    "release_remains_not_eligible": True,
}

IMPLEMENTATION_PATHS = (
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/PermissionSessionPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionLog.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/PermissionSessionPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/PermissionSessionLifecycleStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccessibilityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityWalkSessionLifecycleStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/fieldlog/PersistentFieldSessionLogTest.kt",
    "scripts/build_walksafe_fp014_permission_denial_revocation_trace_20260726.py",
    "tests/test_walksafe_fp014_permission_denial_revocation_trace_20260726.py",
    "scripts/run_walksafe_test_layers_20260711.sh",
)

OUTPUT_PATHS = (
    IMPLEMENTATION_JSON,
    VERIFICATION_JSON,
    SUCCESSOR_JSON,
    REVIEW_SUBJECT_JSON,
    REVIEW_JSON,
    RECEIPT_JSON,
    GAP_R018_JSON,
    GAP_R018_MD,
    BACKLOG_R018_JSON,
    BACKLOG_R018_MD,
    FP014_OVERLAY_JSON,
    FP014_OVERLAY_MD,
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


def validate_execution_history(goal_execution: dict[str, Any]) -> dict[str, Any]:
    history = goal_execution.get("transition_history")
    require(isinstance(history, list) and history, "transition history is missing")
    sequences = [event.get("sequence") for event in history]
    require(
        all(isinstance(sequence, int) and not isinstance(sequence, bool) for sequence in sequences)
        and sequences == sorted(sequences)
        and len(sequences) == len(set(sequences)),
        "transition history sequence order or uniqueness differs",
    )
    event_ids = [event.get("event_id") for event in history]
    require(
        all(isinstance(event_id, str) and event_id for event_id in event_ids)
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
        "FP-014 execution event is not exact-one",
    )
    execution = by_sequence[0]
    require(
        execution.get("event_type") == "GOAL_STARTED"
        and execution.get("subject_goal_id") == GOAL_ID
        and execution.get("event_sha256") == EXPECTED_EXECUTION_EVENT_SHA256
        and execution.get("occurred_at") == EXPECTED_EXECUTION_STARTED_AT,
        "FP-014 execution event differs",
    )
    return execution


def relative(path: Path) -> str:
    return path.resolve().relative_to(ROOT.resolve()).as_posix()


def json_text(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


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
    return "\n".join(
        f"{marker}={summary[key]}"
        for key, marker in JUNIT_MARKERS
    )


def parse_junit_log(
    content: str,
    *,
    minimum_tests: int | None = FULL_ANDROID_MIN_TESTS,
    label: str = "full Android",
) -> dict[str, Any]:
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
    require(parsed["xml_files"] > 0, "JUnit XML file count is not positive")
    require(parsed["tests"] > 0, f"{label} JUnit test count is not positive")
    if minimum_tests is not None:
        require(
            parsed["tests"] >= minimum_tests,
            f"{label} JUnit test count is below minimum",
        )
    require(
        parsed["failures"] == 0,
        f"{label} JUnit failures are nonzero",
    )
    require(parsed["errors"] == 0, f"{label} JUnit errors are nonzero")
    require(parsed["skipped"] == 0, f"{label} JUnit skipped tests are nonzero")
    require(
        parsed["summarizer_sha256"] == base.sha256_file(BUILDER),
        "JUnit summarizer SHA-256 differs",
    )
    return parsed


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


def latest_execution_event(state: dict[str, Any]) -> dict[str, Any] | None:
    candidates = [
        event
        for event in state["transition_history"]
        if event.get("event_type") in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
        and event.get("subject_goal_id") == GOAL_ID
    ]
    return max(candidates, key=lambda event: event.get("sequence", -1), default=None)




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
    spec = f"{EXPECTED_PINNED_HEAD}:{path}"
    listing = subprocess.run(
        ["git", "ls-tree", "-z", "--full-tree", EXPECTED_PINNED_HEAD, "--", path],
        cwd=ROOT,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    require(
        listing.returncode == 0,
        f"cannot inspect pinned HEAD path: {path}",
    )
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
        ["git", "show", spec],
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
                isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest),
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
        len(IMPLEMENTATION_PATHS) == 11
        and len(set(IMPLEMENTATION_PATHS)) == len(IMPLEMENTATION_PATHS),
        "implementation path set differs",
    )
    repository_state = load_gate_repository_state()
    files: list[dict[str, Any]] = []
    for relative_path in IMPLEMENTATION_PATHS:
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
    execution = validate_execution_history(
        checkpoint.get("goal_execution", {}),
    )
    gap = base.load_json(GAP_R017_JSON)
    backlog = base.load_json(BACKLOG_R017_JSON)
    overlay = base.load_json(FP015_OVERLAY_JSON)
    require(
        gap.get("metadata", {}).get("report_id")
        == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260725-017",
        "r017 gap identity differs",
    )
    require(
        backlog.get("metadata", {}).get("backlog_id")
        == "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260725-017",
        "r017 backlog identity differs",
    )
    require(
        overlay.get("metadata", {}).get("overlay_id")
        == "WS-EPIC-02-FP015-WITHDRAWAL-ACCOUNT-DELETION-ACTIVE-LEDGER-OVERLAY-20260725-001",
        "FP-015 predecessor overlay identity differs",
    )
    return gap, backlog, overlay, execution


def completion_boundary() -> dict[str, Any]:
    return {
        "formal_test_ids": [f"TC-FP-014-{index:02d}" for index in range(1, 4)],
        "formal_test_status": "NOT_RUN",
        "planned_formal_test_total": 279,
        "planned_formal_test_total_status": "NOT_RUN",
        "actual_user_status": "NOT_RUN",
        "actual_talkback_user_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "android_platform_review_status": "NOT_RUN",
        "operational_permission_profile_approval_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_gate_count": 5,
        "release_gate_status": "NOT_RUN",
        "release_gates_waived": False,
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
        "document_id": "WS-FP014-PERMISSION-DENIAL-REVOCATION-IMPLEMENTATION-20260726-001",
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
            "purpose-specific permission denial remains an explicit no-access state",
            "permission revocation closes active purpose access and walk-session processing",
            "permission restoration never silently resumes a previously stopped walk session",
            "lifecycle observation rechecks permission state before protected work continues",
            "camera and location entry points remain guarded by current permission state",
            "denial and revocation surfaces provide explicit recovery controls",
            "permission-state changes stop persistent field-session activity fail closed",
            "repeated denial and revocation transitions remain deterministic and idempotent",
            "accessibility semantics expose denial, revocation and recovery status",
            "focused Android regressions cover policy, lifecycle, accessibility and field logging",
            "the trace binds the exact eleven-file implementation scope to canonical verification logs",
        ],
        "remaining_implementation_boundaries": [
            "TC-FP-014-01 through TC-FP-014-03 and the 279 formal tests were not run",
            "actual users, TalkBack users and Android devices were not tested",
            "Android platform review was not performed",
            "the operational permission profile was not approved",
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
    exit_marker_matches = re.findall(
        rf"^{re.escape(COMMAND_EXIT_CODE_MARKER)}=([^\r\n]*)$",
        content,
        flags=re.MULTILINE,
    )
    require(
        exit_marker_matches == ["0"],
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
            str(EXPECTED_EXECUTION_EVENT_SEQUENCE),
        ),
        (CANONICAL_LOG_EXECUTION_EVENT_ID_MARKER, EXPECTED_EXECUTION_EVENT_ID),
        (
            CANONICAL_LOG_EXECUTION_EVENT_SHA256_MARKER,
            EXPECTED_EXECUTION_EVENT_SHA256,
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
        require(
            matches == [expected],
            f"{name} log {marker} binding differs",
        )
    for pattern in (
        r"BUILD FAILED",
        r"\bFAILED\b",
        r"(?:#\s*)?fail [1-9][0-9]*",
    ):
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
    started_at = parse_aware_timestamp(started_matches[0], f"{name} log start timestamp")
    ended_at = parse_aware_timestamp(ended_matches[0], f"{name} log end timestamp")
    execution_started_at = parse_aware_timestamp(
        EXPECTED_EXECUTION_STARTED_AT,
        "FP-014 execution start timestamp",
    )
    require(
        started_at >= execution_started_at,
        f"{name} log starts before the FP-014 execution event",
    )
    require(
        started_at <= ended_at,
        f"{name} log end timestamp precedes its start timestamp",
    )
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
        "execution_event_sequence": EXPECTED_EXECUTION_EVENT_SEQUENCE,
        "execution_event_id": EXPECTED_EXECUTION_EVENT_ID,
        "execution_event_sha256": EXPECTED_EXECUTION_EVENT_SHA256,
        "implementation_content_set_sha256": implementation_content_set_sha256,
        "binding_markers_exact_once": True,
    }

def build_verification(
    *,
    focused_log: Path,
    full_android_log: Path,
    gateway_log: Path,
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
            name="FP-014 focused Android consent tests",
            command=FOCUSED_COMMAND,
            implementation_content_set_sha256=implementation_content_set_sha256,
            required=("BUILD SUCCESSFUL",),
            content_bytes=log_bytes(focused_log),
        ),
        checked_log(
            full_android_log,
            output_path=FULL_ANDROID_LOG,
            name="full Android unit, build and lint verification",
            command=FULL_ANDROID_COMMAND,
            implementation_content_set_sha256=implementation_content_set_sha256,
            required=("BUILD SUCCESSFUL",),
            content_bytes=log_bytes(full_android_log),
        ),
        checked_log(
            gateway_log,
            output_path=GATEWAY_LOG,
            name="Android Gateway permission-denial-revocation typecheck and tests",
            command=GATEWAY_COMMAND,
            implementation_content_set_sha256=implementation_content_set_sha256,
            required=("fail 0",),
            content_bytes=log_bytes(gateway_log),
        ),
        checked_log(
            boundary_log,
            output_path=BOUNDARY_LOG,
            name="Android Gateway frozen public-boundary check",
            command=BOUNDARY_COMMAND,
            implementation_content_set_sha256=implementation_content_set_sha256,
            required=("boundary check: PASS",),
            content_bytes=log_bytes(boundary_log),
        ),
        checked_log(
            control_plane_log,
            output_path=CONTROL_PLANE_LOG,
            name="FP-014 deterministic trace regression",
            command=CONTROL_PLANE_COMMAND,
            implementation_content_set_sha256=implementation_content_set_sha256,
            required=("passed",),
            content_bytes=log_bytes(control_plane_log),
        ),
    ]
    focused_android_junit = parse_junit_log(
        log_bytes(focused_log).decode("utf-8"),
        minimum_tests=None,
        label="focused Android",
    )
    full_android_junit = parse_junit_log(
        log_bytes(full_android_log).decode("utf-8")
    )
    gateway_content = log_bytes(gateway_log).decode("utf-8")
    gateway_counts = {}
    for label in ("tests", "pass", "fail"):
        matches = re.findall(
            rf"^(?:#\s*)?{label}\s+([0-9]+)$",
            gateway_content,
            flags=re.MULTILINE,
        )
        require(len(matches) == 1, f"gateway log lacks one {label} count")
        gateway_counts[label] = int(matches[0])
    require(
        gateway_counts["tests"] > 0
        and gateway_counts["pass"] == gateway_counts["tests"]
        and gateway_counts["fail"] == 0,
        "gateway test summary is not an all-pass result",
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
        "document_id": "WS-FP014-PERMISSION-DENIAL-REVOCATION-VERIFICATION-20260726-001",
        "goal_id": GOAL_ID,
        "kind": "VERIFICATION_RESULT",
        "status": "PASS",
        "observed_at": latest_log_ended_at,
        "checks": checks,
        "android_test_summary": {
            "focused_tests": "PASS",
            "focused_test_count": focused_android_junit["tests"],
            "focused_test_failures": focused_android_junit["failures"],
            "focused_test_errors": focused_android_junit["errors"],
            "focused_test_skipped": focused_android_junit["skipped"],
            "focused_test_xml_file_count": focused_android_junit["xml_files"],
            "focused_test_xml_content_set_sha256": (
                focused_android_junit["xml_content_set_sha256"]
            ),
            "focused_test_summarizer_sha256": (
                focused_android_junit["summarizer_sha256"]
            ),
            "full_unit_tests": "PASS",
            "full_unit_test_count": full_android_junit["tests"],
            "full_unit_test_failures": full_android_junit["failures"],
            "full_unit_test_errors": full_android_junit["errors"],
            "full_unit_test_skipped": full_android_junit["skipped"],
            "full_unit_test_xml_file_count": full_android_junit["xml_files"],
            "full_unit_test_xml_content_set_sha256": (
                full_android_junit["xml_content_set_sha256"]
            ),
            "full_unit_test_summarizer_sha256": (
                full_android_junit["summarizer_sha256"]
            ),
            "debug_build": "PASS",
            "lint": "PASS",
            "gateway_build_typecheck_tests": "PASS",
            "gateway_test_count": gateway_counts["tests"],
            "public_boundary": "PASS",
            "trace_builder_tests": "PASS",
            "debug_apk": "apps/android/app/build/outputs/apk/debug/app-debug.apk",
            "lint_report": "apps/android/app/build/reports/lint-results-debug.html",
        },
        "resource_safety": {
            "execution_mode": "SERIAL_SYSTEMD_USER_UNITS",
            "gradle_max_workers": 1,
            "largest_observed_memory_peak": "1.9G",
            "observed_swap_peak": "0B",
            "memory_max": "2.5G",
        },
        "evidence_boundary": completion_boundary(),
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
    reviewed_timestamp = parse_aware_timestamp(
        reviewed_at,
        "review attestation reviewed_at",
    )
    latest_log_timestamp = parse_aware_timestamp(
        latest_log_ended_at,
        "latest canonical log end timestamp",
    )
    require(
        reviewed_timestamp >= latest_log_timestamp,
        "review attestation predates the latest canonical log end",
    )
    require(
        attestation.get("review_boundary") == EXPECTED_REVIEW_BOUNDARY,
        "review attestation boundary differs",
    )
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP014-PERMISSION-DENIAL-REVOCATION-INTERNAL-REVIEW-20260726-001",
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
            "WS-FP014-PERMISSION-DENIAL-REVOCATION-WORK-ITEM-COMPLETION-20260726-001"
        ),
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": base.sha256_file(GOAL_PATH),
        "work_item_id": "EPIC-02-FP014-PERMISSION-DENIAL-REVOCATION",
        "source_policy_ids": ["FP-014"],
        "gap_ids": ["GAP-023"],
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


def build_gap(
    predecessor_gap: dict[str, Any],
    implementation: dict[str, Any],
    implementation_text: str,
    verification: dict[str, Any],
    verification_text: str,
) -> dict[str, Any]:
    counts = predecessor_gap["summary"]["status_counts"]
    require(
        counts["MISSING"] == 12
        and counts["PARTIAL"] == 29
        and counts["IMPLEMENTED"] == 0,
        "r017 status counts differ",
    )
    report = deepcopy(predecessor_gap)
    report.pop("report_content_sha256", None)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260726-018",
        "version": "0.18.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": verification["observed_at"],
        "baseline_id": predecessor_gap["metadata"]["baseline_id"],
        "baseline_version": predecessor_gap["metadata"]["baseline_version"],
        "predecessor_report_id": predecessor_gap["metadata"]["report_id"],
    }
    report["purpose"] = (
        "FP-014 permission denial, revocation, expiry and settings-return "
        "fail-closed controls are implemented and verified internally. GAP-023 "
        "alone is reassessed while the other 67 assessments are carried forward "
        "from r017."
    )
    report["source_bindings"] = predecessor_gap["source_bindings"] + [
        {**base.file_record(BUILDER), "name": "fp014_permission_trace_builder"},
        {**base.file_record(BUILDER_TEST), "name": "fp014_permission_builder_test"},
        {**base.file_record(START_GATE_RECEIPT), "name": "fp014_start_gate"},
        {
            **base.file_record(START_GATE_REPOSITORY_STATE_LOG),
            "name": "fp014_gate_repository_state",
        },
        generated_record(
            IMPLEMENTATION_JSON,
            implementation_text,
            name="fp014_permission_implementation",
        ),
        generated_record(
            VERIFICATION_JSON,
            verification_text,
            name="fp014_permission_verification",
        ),
    ]
    report["source_binding_sha256"] = base.object_sha256(report["source_bindings"])
    files = implementation_files()
    snapshot = {
        "scope_kind": "EPIC_02_FP014_EXACT_11_PATH_SET",
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "before_state_authority": (
            "START_GATE_DIRTY_SNAPSHOT_THEN_PINNED_HEAD"
        ),
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
            "MISSING": 12,
            "PARTIAL": 29,
            "IMPLEMENTED": 0,
        },
        "headline": (
            "GAP-023 internal permission denial and revocation controls are verified, "
            "but formal, legal, real-device, external-store and release evidence "
            "remain NOT_RUN; the result is PARTIAL."
        ),
    }
    implementation_hash = base.sha256_bytes(implementation_text.encode("utf-8"))
    verification_hash = base.sha256_bytes(verification_text.encode("utf-8"))
    evidence_id = "EVD-FP014-PERMISSION-DENIAL-REVOCATION-20260726"
    report["evidence_catalog"] = predecessor_gap["evidence_catalog"] + [
        {
            "evidence_id": evidence_id,
            "kind": "INTERNAL_PRODUCER_RESULT",
            "claim": (
                "FP-014 repository-internal permission denial, revocation, expiry "
                "and settings-return fail-closed Android regressions"
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
        item for item in report["assessments"] if item["gap_id"] == "GAP-023"
    ]
    require(len(matches) == 1, "GAP-023 assessment count differs")
    assessment = matches[0]
    require(
        assessment.get("source_policy_id") == "FP-014"
        and assessment.get("status") == "PARTIAL",
        "GAP-023 predecessor assessment differs",
    )
    assessment["status"] = "PARTIAL"
    assessment["formal_test_status"] = "NOT_RUN"
    assessment["current_implementation_in_plain_language"] = (
        "Android now separates permission-dependent capabilities, rechecks actual "
        "permission and required-capability state after denial, revocation, expiry "
        "and settings return, and blocks walk, collection and transfer until an "
        "explicit user resume."
    )
    assessment["rationale"] = (
        "The exact 11-path internal implementation and five canonical regression "
        "layers passed. Formal tests, actual users, TalkBack users, Android devices, "
        "platform review, operational permission-profile approval, deployment and "
        "release gates remain NOT_RUN."
    )
    assessment["evidence_ids"] = list(
        dict.fromkeys([*assessment.get("evidence_ids", []), evidence_id])
    )
    assessment["fp014_reassessment"] = {
        "goal_id": GOAL_ID,
        "implementation_record_sha256": implementation_hash,
        "verification_result_sha256": verification_hash,
        "internal_control_status": "PASS",
        "formal_test_status": "NOT_RUN",
        "actual_user_status": "NOT_RUN",
        "actual_talkback_user_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "android_platform_review_status": "NOT_RUN",
        "operational_permission_profile_approval_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
    }
    assessment.pop("assessment_sha256", None)
    assessment["assessment_sha256"] = base.object_sha256(assessment)
    carried_ids = [
        item["gap_id"]
        for item in report["assessments"]
        if item["gap_id"] != "GAP-023"
    ]
    require(len(carried_ids) == 67, "r017 carry-forward gap count differs")
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC02_FP014_GAP023_REASSESSMENT_WITH_R017_CARRY_FORWARD",
        "directly_reassessed_gap_ids": ["GAP-023"],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": ["GAP-023"],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": carried_ids,
        "carry_forward_warning": (
            "The other 67 assessments are preserved from r017."
        ),
        "next_adjacent_gap": {
            "gap_id": "GAP-025",
            "source_policy_id": "FP-016",
            "reason": "EPIC-02 deterministic execution order advances to item 17.",
        },
        "predecessor": {
            "report_id": predecessor_gap["metadata"]["report_id"],
            "path": relative(GAP_R017_JSON),
            "file_sha256": EXPECTED_PREDECESSOR_SHA256[GAP_R017_JSON],
            "evidence_count": len(predecessor_gap["evidence_catalog"]),
            "revalidated_wholesale_in_r018": False,
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
        "Only GAP-023 is directly reassessed; 67 assessments are carried from r017.",
        "TC-FP-014-01 through TC-FP-014-03 and all 279 formal tests are NOT_RUN.",
        "Actual-user, TalkBack-user and Android-device verification is NOT_RUN.",
        "Android platform review and operational permission-profile approval are NOT_RUN.",
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
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-018",
        "version": "0.18.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": observed_at,
        "predecessor_backlog_id": predecessor_backlog["metadata"]["backlog_id"],
    }
    backlog["source_predecessor"] = {
        "path": relative(BACKLOG_R017_JSON),
        "file_sha256": EXPECTED_PREDECESSOR_SHA256[BACKLOG_R017_JSON],
        "preserved_unchanged": False,
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    matching = [
        item
        for item in backlog["next_action_sequence"]
        if item.get("source_policy_id") == "FP-014"
    ]
    require(len(matching) == 1, "FP-014 backlog action count differs")
    require(matching[0].get("status") == "PARTIAL", "FP-014 backlog status differs")
    matching[0]["status"] = "PARTIAL"
    matching[0]["action"] = (
        "Connect the verified internal permission denial and revocation controls "
        "to actual-user, TalkBack-user, Android-device, platform, operational "
        "permission-profile, formal and release verification."
    )
    epic = next(item for item in backlog["epics"] if item["epic_id"] == "EPIC-02")
    epic["current_status"] = "IN_PROGRESS"
    epic["current_status_reason"] = (
        "FP-014 internal permission denial and revocation controls are verified; "
        "FP-016 and later policies plus external and release verification remain."
    )
    next_row = next(
        item
        for item in backlog["next_action_sequence"]
        if item.get("source_policy_id") == "FP-016"
    )
    backlog["next_single_action"] = {
        "epic_id": "EPIC-02",
        "source_policy_id": "FP-016",
        "gap_id": "GAP-025",
        "status": "PLANNED_NEXT_WITHIN_EPIC",
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
                "WS-EPIC-02-FP014-PERMISSION-DENIAL-REVOCATION-"
                "ACTIVE-LEDGER-OVERLAY-20260726-001"
            ),
            "version": "0.1.0",
            "as_of": "2026-07-26",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-02 FP-014 permission-denial/revocation successor overlay",
        },
        "authority_boundary": {
            "predecessor_overlay_preserved": True,
            "canonical_active_files_modified_by_builder": False,
            "approved_baseline_or_predecessor_modified": False,
            "creates_new_product_policy": False,
            "formal_test_completion_claimed": False,
            "actual_talkback_user_completion_claimed": False,
            "android_platform_review_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "production_deployment_completion_claimed": False,
            "epic_in_progress_claimed": True,
            "epic_complete_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": {
            "predecessor_overlay": base.file_record(FP015_OVERLAY_JSON),
            "implementation_record": generated_record(
                IMPLEMENTATION_JSON,
                implementation_text,
                name="fp014_implementation",
            ),
            "verification_result": generated_record(
                VERIFICATION_JSON,
                verification_text,
                name="fp014_verification",
            ),
            "implementation_gap": generated_binding(
                "IMPLEMENTATION_GAP",
                GAP_R018_JSON,
                gap["metadata"]["report_id"],
                gap_text,
            ),
            "implementation_backlog": generated_binding(
                "IMPLEMENTATION_BACKLOG",
                BACKLOG_R018_JSON,
                backlog["metadata"]["backlog_id"],
                backlog_text,
            ),
        },
        "goal_state": {
            "goal_id": GOAL_ID,
            "internal_result": "PARTIAL_IMPLEMENTATION_VERIFIED",
            "gap_id": "GAP-023",
            "gap_status": "PARTIAL",
            "next_source_policy_id": "FP-016",
            "next_gap_id": "GAP-025",
        },
        "completion_boundary": completion_boundary(),
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
        "document_id": "WS-FP014-PERMISSION-DENIAL-REVOCATION-SUCCESSOR-20260726-001",
        "goal_id": GOAL_ID,
        "kind": "SUCCESSOR_TRACE",
        "status": "PASS",
        "observed_at": observed_at,
        "canonical_update_event_type": "CANONICAL_BINDINGS_UPDATED",
        "resulting_canonical_bindings": {
            "IMPLEMENTATION_BACKLOG": generated_binding(
                "IMPLEMENTATION_BACKLOG",
                BACKLOG_R018_JSON,
                backlog["metadata"]["backlog_id"],
                backlog_text,
            ),
            "IMPLEMENTATION_GAP": generated_binding(
                "IMPLEMENTATION_GAP",
                GAP_R018_JSON,
                gap["metadata"]["report_id"],
                gap_text,
            ),
        },
        "changed_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-014"],
            "IMPLEMENTATION_GAP": ["FP-014", "GAP-023"],
        },
        "next_policy_gap_pair": {
            "source_policy_id": "FP-016",
            "gap_id": "GAP-025",
        },
    }


def gap_markdown(value: dict[str, Any]) -> str:
    row = next(item for item in value["assessments"] if item["gap_id"] == "GAP-023")
    return (
        "# WalkSafe 구현 Gap 분석 r018\n\n"
        "- 직접 재평가: `FP-014 / GAP-023`\n"
        f"- 판정: `{row['status']}` (`PARTIAL → PARTIAL`)\n"
        "- Android·Gateway focused/전체 회귀와 build·lint/typecheck·경계 검사: `PASS`\n"
        "- TC-FP-014-01~03·정식 279개·실제 사용자·TalkBack·기기·플랫폼·"
        "운영 권한 프로파일·운영 배포: `NOT_RUN`\n"
        "- 출시 Gate 5개: `NOT_RUN` / 미면제\n"
        "- 출시: `NOT_ELIGIBLE`\n\n"
        f"{row['rationale']}\n"
    )

def backlog_markdown(value: dict[str, Any]) -> str:
    action = value["next_single_action"]
    return (
        "# WalkSafe 구현 보완 Backlog r018\n\n"
        "- EPIC-02: `IN_PROGRESS`\n"
        f"- 다음 정책·Gap: `{action['source_policy_id']} / {action['gap_id']}`\n"
        "- 다음 정책: `로그인 뒤 카메라 중심 무버튼 화면`\n"
        f"- 다음 작업: {action['action']}\n"
    )


def overlay_markdown(value: dict[str, Any]) -> str:
    return (
        "# EPIC-02 FP-014 권한 거부·철회 Active ledger overlay\n\n"
        f"- Overlay: `{value['metadata']['overlay_id']}`\n"
        "- FP-014 내부 권한 거부·철회 구현·검증: `PARTIAL_IMPLEMENTATION_VERIFIED`\n"
        "- 정식 279개·사용자·TalkBack·기기·플랫폼·배포: `NOT_RUN`\n"
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
        [item["path"] for item in changed_artifacts]
        == list(IMPLEMENTATION_PATHS),
        "review-subject implementation path order differs",
    )
    require(
        len(changed_artifacts) == 11,
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
    receipts = {
        item["output_path"]: item
        for item in verification["checks"]
    }
    require(
        len(receipts) == len(verification["checks"]),
        "review-subject verification receipt paths are not unique",
    )
    summary = verification["android_test_summary"]
    summarizer_path = relative(BUILDER)
    summarizer_sha256 = base.sha256_file(BUILDER)

    def junit_evidence(kind: str, log_path: Path) -> dict[str, Any]:
        prefix = "focused_test" if kind == "focused" else "full_unit_test"
        canonical_log_path = relative(log_path)
        require(
            canonical_log_path in receipts,
            f"review-subject {kind} JUnit log receipt is missing",
        )
        values = {
            "xml_files": summary[f"{prefix}_xml_file_count"],
            "tests": summary[f"{prefix}_count"],
            "failures": summary[f"{prefix}_failures"],
            "errors": summary[f"{prefix}_errors"],
            "skipped": summary[f"{prefix}_skipped"],
            "xml_content_set_sha256": summary[
                f"{prefix}_xml_content_set_sha256"
            ],
            "summarizer_sha256": summary[f"{prefix}_summarizer_sha256"],
        }
        require(
            isinstance(values["xml_files"], int)
            and values["xml_files"] > 0
            and isinstance(values["tests"], int)
            and values["tests"] > 0
            and values["failures"] == 0
            and values["errors"] == 0
            and values["skipped"] == 0,
            f"review-subject {kind} JUnit counts differ",
        )
        require(
            re.fullmatch(r"[0-9a-f]{64}", values["xml_content_set_sha256"])
            is not None,
            f"review-subject {kind} JUnit content-set SHA-256 differs",
        )
        require(
            values["summarizer_sha256"] == summarizer_sha256,
            f"review-subject {kind} JUnit summarizer SHA-256 differs",
        )
        return {
            "canonical_log_path": canonical_log_path,
            "canonical_log_sha256": receipts[canonical_log_path]["output_sha256"],
            "markers_exact_once": True,
            "xml_files": values["xml_files"],
            "tests": values["tests"],
            "failures": values["failures"],
            "errors": values["errors"],
            "skipped": values["skipped"],
            "xml_content_set_sha256": values["xml_content_set_sha256"],
            "summarizer_path": summarizer_path,
            "summarizer_sha256": summarizer_sha256,
        }

    return {
        "schema_version": "1.0",
        "evidence_type": "INTERNAL_REVIEW_SUBJECT",
        "goal_id": GOAL_ID,
        "reviewed_result_sha256_by_kind": result_hashes,
        "implementation_scope": {
            "scope": "EXACT_11_PATH_SET",
            "exact_path_count": 11,
            "content_set_sha256": content_set_sha256,
        },
        "android_junit_evidence": {
            "focused": junit_evidence("focused", FOCUSED_LOG),
            "full": junit_evidence("full", FULL_ANDROID_LOG),
        },
        "verification_receipts": deepcopy(verification["checks"]),
        "completion_boundary": completion_boundary(),
    }


def build_outputs(
    *,
    focused_log: Path = FOCUSED_LOG,
    full_android_log: Path = FULL_ANDROID_LOG,
    gateway_log: Path = GATEWAY_LOG,
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
        (full_android_log, FULL_ANDROID_LOG),
        (gateway_log, GATEWAY_LOG),
        (boundary_log, BOUNDARY_LOG),
        (control_plane_log, CONTROL_PLANE_LOG),
    ]
    if not review_subject_only:
        canonical_inputs.extend([
            (review_subject, REVIEW_SUBJECT_JSON),
            (review_attestation, REVIEW_ATTESTATION_JSON),
        ])
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
        full_android_log=full_android_log,
        gateway_log=gateway_log,
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
    verification_text = json_text(verification)
    implementation_text = json_text(implementation)
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
        return {REVIEW_SUBJECT_JSON: review_subject_text}
    require(
        evidence_snapshots[review_subject] == review_subject_text.encode("utf-8"),
        "review subject differs from current implementation or verification",
    )
    review_subject_sha256 = base.sha256_bytes(review_subject_text.encode("utf-8"))
    attestation_bytes = evidence_snapshots[review_attestation]
    attestation = json_from_snapshot(
        attestation_bytes,
        "review attestation",
    )
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
        GAP_R018_JSON: gap_text,
        GAP_R018_MD: gap_markdown(gap),
        BACKLOG_R018_JSON: backlog_text,
        BACKLOG_R018_MD: backlog_markdown(backlog),
        FP014_OVERLAY_JSON: json_text(overlay),
        FP014_OVERLAY_MD: overlay_markdown(overlay),
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
        GAP_R018_JSON: gap_text,
        GAP_R018_MD: non_receipt_outputs[GAP_R018_MD],
        BACKLOG_R018_JSON: backlog_text,
        BACKLOG_R018_MD: non_receipt_outputs[BACKLOG_R018_MD],
        FP014_OVERLAY_JSON: non_receipt_outputs[FP014_OVERLAY_JSON],
        FP014_OVERLAY_MD: non_receipt_outputs[FP014_OVERLAY_MD],
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
    require(tuple(outputs) == OUTPUT_PATHS, "output path order or membership differs")
    destinations = {
        path: output_destination(path, output_root)
        for path in outputs
    }
    if write:
        for path, content in outputs.items():
            destination = destinations[path]
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(content, encoding="utf-8")
        return
    for path, content in outputs.items():
        destination = destinations[path]
        require(destination.is_file(), f"output is missing: {destination}")
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
            subject_text = subject_output[REVIEW_SUBJECT_JSON]
            destination = output_destination(REVIEW_SUBJECT_JSON, args.output_root)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(subject_text, encoding="utf-8")
            print(
                "FP-014 permission-denial-revocation trace: PASS "
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
        print(f"FP-014 permission-denial-revocation trace: FAIL: {exc}", file=sys.stderr)
        return 1
    mode_name = "WRITE" if args.write else "CHECK"
    print(
        f"FP-014 permission-denial-revocation trace: PASS outputs={len(outputs)} "
        f"next=FP-016/GAP-025 mode={mode_name}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
