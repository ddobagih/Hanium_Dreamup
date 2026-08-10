#!/usr/bin/env python3
"""Build the focused FP-015 withdrawal-account-deletion implementation trace."""

from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Any
from xml.etree import ElementTree

try:
    from scripts import build_walksafe_fp013_integrated_consent_trace_20260725 as predecessor
except ModuleNotFoundError:
    import build_walksafe_fp013_integrated_consent_trace_20260725 as predecessor


base = predecessor.base
ROOT = Path(__file__).resolve().parents[1]
BUILDER = Path(__file__).resolve()
BUILDER_TEST = (
    ROOT / "tests/test_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py"
)

GOAL_ID = "WS-GOAL-EPIC-02-FP-015-R001"
GOAL_PATH = (
    ROOT
    / "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-02/"
    "epic-02-fp015-withdrawal-account-deletion-r001.md"
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
    "./gradlew :app:testDebugUnitTest --tests '*MainActivityWithdrawalStaticTest' "
    "--tests '*MainActivityAccountDeletionStaticTest' "
    "--tests '*IntegratedConsentWithdrawalTest' "
    "--tests '*PrivacyAccountDeletionPolicyTest' "
    "--tests '*AndroidPrivacyDeletionAccountDeletionTest' "
    "--tests '*ReportPrivacyAccountDeletionTest' "
    "--tests '*AndroidReportPurposeHeaderStaticTest' "
    "--tests '*MainActivityReportUploadStaticTest' "
    "--tests '*AndroidReportUploaderTest' "
    "--tests '*ReportPrivacyConsentSessionTest' "
    "--tests '*MainActivityWithdrawalRestartStaticTest' "
    "--tests '*PrivacyDeletionHardeningTest' "
    "--tests '*IntegratedConsentRevisionHardeningTest' "
    "--tests '*AndroidPrivacyDeletionOriginHardeningTest' "
    "--tests '*FieldSessionAccountDeletionPrivacyFenceTest' "
    "--rerun-tasks --offline --no-daemon "
    "--max-workers=1 -Dkotlin.compiler.execution.strategy=in-process"
)
FULL_ANDROID_COMMAND = (
    "rm -rf app/build/test-results/testDebugUnitTest && "
    "./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug "
    "--rerun-tasks --offline --no-daemon --max-workers=1 "
    "-Dkotlin.compiler.execution.strategy=in-process"
)
FULL_ANDROID_MIN_TESTS = 663
GATEWAY_COMMAND = "cd apps/android-gateway && npm run typecheck && npm test"
BOUNDARY_COMMAND = (
    "python3 -B scripts/check_walksafe_android_gateway_boundary_20260723.py --root ."
)
CONTROL_PLANE_COMMAND = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python -m py_compile scripts/"
    "build_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py && "
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python -m pytest -q tests/"
    "test_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py"
)

GAP_R016_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260725-r016.json"
)
BACKLOG_R016_JSON = (
    ROOT
    / "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260725-r016.json"
)
FP013_OVERLAY_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-fp013-integrated-consent-active-ledger-"
    "overlay-20260725-r001.json"
)
GAP_R017_JSON = (
    ROOT / "docs/control/audits/walksafe-implementation-gap-analysis-20260725-r017.json"
)
GAP_R016_MD = GAP_R017_JSON.with_suffix(".md")
BACKLOG_R017_JSON = (
    ROOT
    / "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260725-r017.json"
)
BACKLOG_R016_MD = BACKLOG_R017_JSON.with_suffix(".md")
FP015_OVERLAY_JSON = (
    ROOT
    / "docs/control/execution/"
    "walksafe-epic-02-fp015-withdrawal-account-deletion-active-ledger-"
    "overlay-20260725-r001.json"
)
FP015_OVERLAY_MD = FP015_OVERLAY_JSON.with_suffix(".md")
CHECKPOINT = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"
START_GATE_RECEIPT = (
    ROOT
    / "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP015-20260725-001/"
    "implementation-start-gate-receipt.json"
)
START_GATE_REPOSITORY_STATE_LOG = START_GATE_RECEIPT.parent / "19-REPOSITORY_STATE.log"

EXPECTED_PREDECESSOR_SHA256 = {
    GAP_R016_JSON: "0a3eff37fd822fb58e647669d178160c69db66d739db0962956b39b47e7af9dd",
    BACKLOG_R016_JSON: "8c06265e4a721634d27ff82f57284a3c8ba9e033ecfe64d489194f82b80936e8",
    FP013_OVERLAY_JSON: "d6337f2c67cff562bd011032033d5c473b437ca608e3ec811998cf116fd6607c",
}
EXPECTED_GOAL_SHA256 = (
    "9b5374de0e8a95140ec83b5f70bfe0c85cc0353c474c93ccdf4dbe9d27a9a0f3"
)
EXPECTED_START_GATE_RECEIPT_SHA256 = (
    "6abcad07e31d2426624f6a5fab429cdc3aaf363cc291fec60e63cbe9de8c6dcf"
)
EXPECTED_START_GATE_RECEIPT_ID = (
    "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP015-20260725-001"
)
EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256 = (
    "5f21947da794bb83029de217c56298b4b3e3089a052b5e798b8913f58fa41ff0"
)
EXPECTED_PINNED_HEAD = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"
EXPECTED_EXECUTION_EVENT_SEQUENCE = 14
EXPECTED_EXECUTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP015-20260725-001"
)
EXPECTED_EXECUTION_EVENT_SHA256 = (
    "d4402d0dcecc7c622bc58af6cbe7c0bc3523f3baa712dadc7ef508a2d54330d3"
)
EXPECTED_EXECUTION_STARTED_AT = "2026-07-25T18:05:40+09:00"

IMPLEMENTATION_PATHS = (
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
    "AndroidIntegratedConsentClient.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
    "AndroidNetworkTransferPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/"
    "AndroidReportUploader.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "IntegratedConsentPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "PermissionSessionPolicy.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityIntegratedConsentStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityPhoneMountingStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/"
    "AndroidReportUploaderTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/"
    "ReportPrivacyConsentSessionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "IntegratedConsentPolicyTest.kt",
    "apps/android-gateway/README.md",
    "apps/android-gateway/src/backend.ts",
    "apps/android-gateway/src/withdrawal-account-deletion.ts",
    "apps/android-gateway/src/routes.ts",
    "apps/android-gateway/test/gateway-contract.test.ts",
    "apps/android-gateway/test/withdrawal-account-deletion.test.ts",
)
IMPLEMENTATION_PATHS = (
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "IntegratedConsentPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "PrivacyDeletionPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
    "AndroidPrivacyDeletionClient.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
    "AndroidGatewaySessionStore.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/"
    "ReportPrivacyConsentSession.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/"
    "AndroidReportUploader.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/"
    "FieldSessionLog.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityWithdrawalStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityAccountDeletionStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "IntegratedConsentWithdrawalTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "PrivacyAccountDeletionPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/"
    "AndroidPrivacyDeletionAccountDeletionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/"
    "ReportPrivacyAccountDeletionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/"
    "AndroidReportPurposeHeaderStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/"
    "AndroidReportUploaderTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/"
    "ReportPrivacyConsentSessionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/"
    "MainActivityReportUploadStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityWithdrawalRestartStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "PrivacyDeletionHardeningTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "IntegratedConsentRevisionHardeningTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/"
    "AndroidPrivacyDeletionOriginHardeningTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/fieldlog/"
    "FieldSessionAccountDeletionPrivacyFenceTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
    "MainActivityPhoneMountingStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/"
    "AndroidGatewaySessionStoreStaticTest.kt",
    "apps/android-gateway/src/privacy-rights.ts",
    "apps/android-gateway/src/routes.ts",
    "apps/android-gateway/test/privacy-rights.test.ts",
    "apps/android-gateway/test/gateway-contract.test.ts",
    "apps/android-gateway/README.md",
    "apps/android-gateway/openapi.json",
    "scripts/build_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py",
    "tests/test_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py",
    "scripts/run_walksafe_test_layers_20260711.sh",
)

OUTPUT_PATHS = (
    IMPLEMENTATION_JSON,
    VERIFICATION_JSON,
    SUCCESSOR_JSON,
    REVIEW_SUBJECT_JSON,
    REVIEW_JSON,
    RECEIPT_JSON,
    GAP_R017_JSON,
    GAP_R016_MD,
    BACKLOG_R017_JSON,
    BACKLOG_R016_MD,
    FP015_OVERLAY_JSON,
    FP015_OVERLAY_MD,
)


class BuildError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BuildError(message)


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


def validate_inputs() -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    require(GOAL_PATH.is_file(), "FP-015 Goal is missing")
    require(base.sha256_file(GOAL_PATH) == EXPECTED_GOAL_SHA256, "FP-015 Goal changed")
    for path, expected in EXPECTED_PREDECESSOR_SHA256.items():
        require(path.is_file(), f"predecessor is missing: {relative(path)}")
        require(base.sha256_file(path) == expected, f"predecessor changed: {relative(path)}")
    require(
        base.sha256_file(START_GATE_RECEIPT) == EXPECTED_START_GATE_RECEIPT_SHA256,
        "FP-015 implementation-start receipt changed",
    )
    require(
        base.sha256_file(START_GATE_REPOSITORY_STATE_LOG)
        == EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256,
        "FP-015 implementation-start repository-state log changed",
    )
    gate = base.load_json(START_GATE_RECEIPT)
    require(
        gate.get("document_id") == EXPECTED_START_GATE_RECEIPT_ID,
        "FP-015 start-gate document differs",
    )
    require(
        gate.get("target_transition_event_id") == EXPECTED_EXECUTION_EVENT_ID,
        "FP-015 start-gate target event differs",
    )
    checkpoint = base.load_json(CHECKPOINT)
    event = latest_execution_event(checkpoint["goal_execution"])
    require(event is not None, "FP-015 execution event is missing")
    require(
        event.get("sequence") == EXPECTED_EXECUTION_EVENT_SEQUENCE,
        "FP-015 execution sequence differs",
    )
    require(event.get("event_id") == EXPECTED_EXECUTION_EVENT_ID, "FP-015 event differs")
    require(
        event.get("event_sha256") == EXPECTED_EXECUTION_EVENT_SHA256,
        "FP-015 event hash differs",
    )
    require(
        event.get("occurred_at") == EXPECTED_EXECUTION_STARTED_AT,
        "FP-015 event time differs",
    )
    return (
        base.load_json(GAP_R016_JSON),
        base.load_json(BACKLOG_R016_JSON),
        base.load_json(FP013_OVERLAY_JSON),
    )


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
        len(IMPLEMENTATION_PATHS) == 34
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


def validate_inputs() -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
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
    execution = latest_execution_event(base.load_json(CHECKPOINT)["goal_execution"])
    require(
        execution is not None
        and execution.get("sequence") == EXPECTED_EXECUTION_EVENT_SEQUENCE
        and execution.get("event_id") == EXPECTED_EXECUTION_EVENT_ID
        and execution.get("event_type") == "GOAL_STARTED"
        and execution.get("event_sha256") == EXPECTED_EXECUTION_EVENT_SHA256
        and execution.get("occurred_at") == EXPECTED_EXECUTION_STARTED_AT,
        "FP-015 execution event differs",
    )
    gap = base.load_json(GAP_R016_JSON)
    backlog = base.load_json(BACKLOG_R016_JSON)
    overlay = base.load_json(FP013_OVERLAY_JSON)
    require(
        gap.get("metadata", {}).get("report_id")
        == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260725-016",
        "r016 gap identity differs",
    )
    require(
        backlog.get("metadata", {}).get("backlog_id")
        == "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260725-016",
        "r016 backlog identity differs",
    )
    require(
        overlay.get("metadata", {}).get("overlay_id")
        == "WS-EPIC-02-FP013-INTEGRATED-CONSENT-ACTIVE-LEDGER-OVERLAY-20260725-001",
        "FP-013 predecessor overlay identity differs",
    )
    return gap, backlog, overlay


def completion_boundary() -> dict[str, Any]:
    return {
        "formal_test_ids": [f"TC-FP-015-{index:02d}" for index in range(1, 6)],
        "formal_test_status": "NOT_RUN",
        "planned_formal_test_total": 279,
        "planned_formal_test_total_status": "NOT_RUN",
        "legal_privacy_review_status": "NOT_RUN",
        "legal_hold_determination_status": "NOT_RUN",
        "approved_final_privacy_copy_status": "NOT_RUN",
        "actual_user_status": "NOT_RUN",
        "actual_guardian_status": "NOT_RUN",
        "actual_talkback_user_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "actual_network_status": "NOT_RUN",
        "actual_external_storage_deletion_status": "NOT_RUN",
        "actual_backup_deletion_status": "NOT_RUN",
        "external_rights_intake_provisioning_status": "NOT_RUN",
        "deletion_sla_certification_status": "NOT_RUN",
        "production_credentials_status": "NOT_RUN",
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
        "document_id": "WS-FP015-WITHDRAWAL-ACCOUNT-DELETION-IMPLEMENTATION-20260725-001",
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
            "purpose withdrawal and account deletion use distinct explicit state machines",
            "withdrawal immediately closes new purpose collection, reporting, transfer and training reuse",
            "account deletion immediately closes every new personal-data processing entry point",
            "phone, server, derived-data, report and backup deletion items remain independently tracked",
            "phone, server, derived-data and backup deadlines are 24 hours, 7 days, 30 days and 35 days",
            "failed, retry-waiting and legal-hold items cannot be promoted to complete",
            "idempotent requests and monotonic revisions reject delayed, duplicate and reversed responses",
            "logout ends only login while app removal never claims server deletion",
            "local unsent data is removed by purpose without claiming remote or backup completion",
            "app and public privacy-rights requests converge on one server processing record",
            "re-registration creates a new account and new consent without restoring prior choices",
            "Android progress, failure, remaining-item and contact states expose accessibility semantics",
        ],
        "remaining_implementation_boundaries": [
            "final legal, privacy and legal-hold determinations were not performed",
            "actual users, guardians, TalkBack users and Android devices were not tested",
            "actual external stores, derived datasets, reports and backups were not deleted",
            "external rights intake, alternative identity proof and deletion SLA were not certified",
            "production credentials, networks and deployment were not used",
            "TC-FP-015-01 through TC-FP-015-05, the 279 formal tests and release gates were not run",
        ],
        "completion_boundary": completion_boundary(),
    }


def checked_log(
    source_path: Path,
    *,
    output_path: Path,
    name: str,
    command: str,
    required: tuple[str, ...],
) -> dict[str, Any]:
    require(source_path.is_file(), f"verification log is missing: {source_path}")
    content = source_path.read_text(encoding="utf-8")
    require(
        content.count("WALKSAFE_COMMAND_EXIT_CODE=0") == 1,
        f"{name} log lacks one successful command receipt",
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
    require(
        len(run_id_matches) == 1,
        f"{name} log lacks one run id",
    )
    require(
        command_hash_matches == [expected_command_sha256],
        f"{name} log command hash differs",
    )
    for pattern in (
        r"BUILD FAILED",
        r"\bFAILED\b",
        r"(?:#\s*)?fail [1-9][0-9]*",
        r"WALKSAFE_COMMAND_EXIT_CODE=(?!0)",
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
    try:
        started_at = datetime.fromisoformat(started_matches[0])
        ended_at = datetime.fromisoformat(ended_matches[0])
    except ValueError as exc:
        raise ValueError(f"{name} log contains an invalid ISO timestamp") from exc
    require(
        started_at.tzinfo is not None and ended_at.tzinfo is not None,
        f"{name} log timestamps must include an offset",
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
        "output_sha256": base.sha256_bytes(content.encode("utf-8")),
        "started_at": started_matches[0],
        "executed_at": ended_matches[0],
    }


def build_verification(
    *,
    focused_log: Path,
    full_android_log: Path,
    gateway_log: Path,
    boundary_log: Path,
    control_plane_log: Path,
) -> dict[str, Any]:
    checks = [
        checked_log(
            focused_log,
            output_path=FOCUSED_LOG,
            name="FP-015 focused Android consent tests",
            command=FOCUSED_COMMAND,
            required=("BUILD SUCCESSFUL",),
        ),
        checked_log(
            full_android_log,
            output_path=FULL_ANDROID_LOG,
            name="full Android unit, build and lint verification",
            command=FULL_ANDROID_COMMAND,
            required=("BUILD SUCCESSFUL",),
        ),
        checked_log(
            gateway_log,
            output_path=GATEWAY_LOG,
            name="Android Gateway withdrawal-account-deletion typecheck and tests",
            command=GATEWAY_COMMAND,
            required=("fail 0",),
        ),
        checked_log(
            boundary_log,
            output_path=BOUNDARY_LOG,
            name="Android Gateway frozen public-boundary check",
            command=BOUNDARY_COMMAND,
            required=("boundary check: PASS",),
        ),
        checked_log(
            control_plane_log,
            output_path=CONTROL_PLANE_LOG,
            name="FP-015 deterministic trace regression",
            command=CONTROL_PLANE_COMMAND,
            required=("passed",),
        ),
    ]
    focused_android_junit = parse_junit_log(
        focused_log.read_text(encoding="utf-8"),
        minimum_tests=None,
        label="focused Android",
    )
    full_android_junit = parse_junit_log(
        full_android_log.read_text(encoding="utf-8")
    )
    gateway_content = gateway_log.read_text(encoding="utf-8")
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
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP015-WITHDRAWAL-ACCOUNT-DELETION-VERIFICATION-20260725-001",
        "goal_id": GOAL_ID,
        "kind": "VERIFICATION_RESULT",
        "status": "PASS",
        "observed_at": max(check["executed_at"] for check in checks),
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


def build_gap(
    predecessor_gap: dict[str, Any],
    implementation: dict[str, Any],
    implementation_text: str,
    verification: dict[str, Any],
    verification_text: str,
) -> dict[str, Any]:
    counts = predecessor_gap["summary"]["status_counts"]
    require(
        counts["MISSING"] == 14
        and counts["PARTIAL"] == 27
        and counts["IMPLEMENTED"] == 0,
        "r016 status counts differ",
    )
    report = deepcopy(predecessor_gap)
    report.pop("report_content_sha256", None)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260725-017",
        "version": "0.17.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": verification["observed_at"],
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": predecessor_gap["metadata"]["report_id"],
    }
    report["purpose"] = (
        "FP-015의 독립·버전형 철회·계정 삭제, 서버 현재성 원장과 Android·Gateway "
        "fail-closed 경계를 저장소 내부에서 구현·검증해 GAP-024만 직접 "
        "재평가하고 나머지 67개 평가는 r016에서 보존한다."
    )
    report["source_bindings"] = predecessor_gap["source_bindings"] + [
        {**base.file_record(BUILDER), "name": "fp015_withdrawal_account_deletion_trace_builder"},
        {
            **base.file_record(BUILDER_TEST),
            "name": "fp015_withdrawal_account_deletion_builder_test",
        },
        {
            **base.file_record(START_GATE_RECEIPT),
            "name": "fp015_implementation_start_gate_receipt",
        },
        generated_record(
            IMPLEMENTATION_JSON,
            implementation_text,
            name="fp015_withdrawal_account_deletion_implementation",
        ),
        generated_record(
            VERIFICATION_JSON,
            verification_text,
            name="fp015_withdrawal_account_deletion_verification",
        ),
    ]
    report["source_binding_sha256"] = base.object_sha256(report["source_bindings"])

    files = implementation_files()
    snapshot = {
        "scope_kind": "EPIC_02_FP015_WITHDRAWAL_ACCOUNT_DELETION_EXACT_CONTROLLED_PATH_SET",
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "excluded_from_scope": [
            "unrelated dirty-tree paths",
            "approved final legal and privacy wording",
            "actual-user, guardian, TalkBack-user and Android-device testing",
            "production credentials, networks and deployment",
            "formal acceptance testing and the 279-test formal set",
            "release-gate closure",
        ],
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
            "MISSING": 13,
            "PARTIAL": 28,
            "IMPLEMENTED": 0,
        },
        "headline": (
            "GAP-024의 네 가지 독립 동의, 서버 현재성 원장과 민감 경로 "
            "fail-closed 경계를 내부 구현했지만 최종 법률 문구·실제 사용자·보호자·"
            "TalkBack·실기기·운영 배포·정식 검증은 남아 PARTIAL이다."
        ),
    }
    implementation_hash = base.sha256_bytes(implementation_text.encode("utf-8"))
    verification_hash = base.sha256_bytes(verification_text.encode("utf-8"))
    evidence_id = "EVD-FP015-WITHDRAWAL-ACCOUNT-DELETION-20260725"
    report["evidence_catalog"] = predecessor_gap["evidence_catalog"] + [
        {
            "evidence_id": evidence_id,
            "kind": "INTERNAL_PRODUCER_RESULT",
            "claim": (
                "FP-015 저장소 내부 철회·계정 삭제 상태기계, 서버 원장과 "
                "Android·Gateway fail-closed 회귀"
            ),
            "producer_goal_id": GOAL_ID,
            "producer_completion_receipt_role": f"WORK_ITEM_COMPLETION::{GOAL_ID}",
            "result_evidence_sha256_by_kind": {
                "IMPLEMENTATION_RECORD": implementation_hash,
                "VERIFICATION_RESULT": verification_hash,
            },
            "files": [
                {"path": relative(IMPLEMENTATION_JSON), "sha256": implementation_hash},
                {"path": relative(VERIFICATION_JSON), "sha256": verification_hash},
            ],
            "formal_test_evidence": False,
            "legal_review_evidence": False,
            "actual_user_evidence": False,
            "actual_guardian_evidence": False,
            "actual_talkback_user_evidence": False,
            "actual_device_evidence": False,
            "actual_network_evidence": False,
            "production_deployment_evidence": False,
            "release_evidence": False,
        }
    ]
    assessment = next(
        item
        for item in report["assessments"]
        if item.get("gap_id") == "GAP-024"
    )
    require(assessment.get("status") == "MISSING", "GAP-024 predecessor is not MISSING")
    require(
        assessment.get("planned_test_ids")
        == [f"TC-FP-015-{index:02d}" for index in range(1, 6)],
        "GAP-024 formal test ids differ",
    )
    assessment["status"] = "PARTIAL"
    assessment["current_implementation_in_plain_language"] = (
        "첫 실행 화면은 원본 수집·자동신고·이동통신망 전송·학습 재사용을 서로 "
        "다른 선택으로 제시하고 동일 화면에서 자료·목적·시점·보존·제공·철회를 "
        "설명한다. Gateway는 설치 식별자별 버전·revision·선택·이전 영수증을 "
        "hash-chain 원장에 원자 저장하고 현재 항목 버전·영수증·인증 field actor가 "
        "모두 일치할 때만 신고에 허용한다. Android는 서버 확인 전 모두 거부하며 "
        "철회 시 의존 호출을 취소하고 실제 선택한 Android Network로 소켓을 연다. "
        "신고 JPEG, 자동신고, 이동통신망, 디버그 원본·메타데이터와 학습 재사용 "
        "상태는 각각 현재 확인이 없으면 fail closed한다."
    )
    assessment["rationale"] = (
        "저장소 내부 Android 집중·전체 단위 회귀, APK 조립, lint, Gateway "
        "typecheck·전체 테스트 및 공개 경계 검사는 통과했다. 그러나 승인된 "
        "최종 법률·개인정보 문구, 실제 미성년 사용자·보호자·TalkBack 사용자·"
        "Android 기기·운영 네트워크·배포가 없고 TC-FP-015-01~05, 정식 279개와 "
        "출시 Gate를 실행하지 않았으므로 최대 PARTIAL이다."
    )
    assessment["evidence_ids"] = [evidence_id]
    assessment["remediation"] = (
        "법률·개인정보 검토를 거친 최종 항목별 문구와 보호자 증거 흐름을 승인하고 "
        "실제 TalkBack 사용자·미성년 사용자·보호자·Android 기기·운영 네트워크에서 "
        "재동의·철회·오래된 응답·실패 복구를 검증한다. 보존·삭제와 외부 학습 제공 "
        "승인을 분리한 뒤 TC-FP-015-01~05, 정식 279개와 출시 Gate를 실행한다."
    )
    assessment["fp015_reassessment"] = {
        "review_kind": "DIRECT_REASSESSMENT",
        "previous_status": "MISSING",
        "new_status": "PARTIAL",
        **completion_boundary(),
        "policy_baseline_modified": False,
    }
    base.rehash_assessment(assessment)
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "legal_review_evidence": False,
        "actual_user_evidence": False,
        "actual_guardian_evidence": False,
        "actual_talkback_user_evidence": False,
        "actual_device_evidence": False,
        "actual_network_evidence": False,
        "production_deployment_evidence": False,
        "source": relative(VERIFICATION_JSON),
        "verification_result_sha256": verification_hash,
        "interpretation": (
            "저장소 내부 회귀이며 법률 승인·정식·실제 사용자·보호자·TalkBack·"
            "실기기·운영 네트워크·배포·출시 증거가 아니다."
        ),
    }
    report["limitations"] = [
        "이번 r017은 GAP-024만 직접 재평가하고 나머지 67개는 r016에서 승계했다.",
        "승인된 최종 법률·개인정보 문구와 보호자 적법성 검토는 NOT_RUN이다.",
        "TC-FP-015-01~05, 정식 279개, 실제 사용자·보호자·TalkBack·기기 시험은 NOT_RUN이다.",
        "운영 자격정보·네트워크·배포와 보존·삭제·외부 학습 제공 승인은 NOT_RUN이다.",
        "5개 Gate는 미면제 NOT_RUN이며 출시는 NOT_ELIGIBLE이다.",
    ]
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC02_FP015_GAP022_REASSESSMENT_WITH_R015_CARRY_FORWARD",
        "directly_reassessed_gap_ids": ["GAP-024"],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": ["GAP-024"],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [
            item["gap_id"]
            for item in report["assessments"]
            if item.get("gap_id") != "GAP-024"
        ],
        "carry_forward_warning": "나머지 67개 판정은 r016에서 그대로 보존했다.",
        "next_adjacent_gap": {
            "gap_id": "GAP-024",
            "source_policy_id": "FP-015",
            "reason": "EPIC-02 의존 순서상 다음 미완료 정책·Gap 쌍이다.",
        },
        "predecessor": {
            "report_id": predecessor_gap["metadata"]["report_id"],
            "path": relative(GAP_R016_JSON),
            "file_sha256": EXPECTED_PREDECESSOR_SHA256[GAP_R016_JSON],
            "evidence_count": len(predecessor_gap["evidence_catalog"]),
            "revalidated_wholesale_in_r017": False,
        },
    }
    return base.seal(report, "report_content_sha256")


def build_backlog(
    predecessor_backlog: dict[str, Any],
    gap: dict[str, Any],
    observed_at: str,
) -> dict[str, Any]:
    backlog = deepcopy(predecessor_backlog)
    backlog.pop("backlog_content_sha256", None)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260725-017",
        "version": "0.17.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": observed_at,
        "predecessor_backlog_id": predecessor_backlog["metadata"]["backlog_id"],
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    changed = 0
    for item in backlog["next_action_sequence"]:
        if item.get("source_policy_id") == "FP-015":
            changed += 1
            require(item.get("status") == "MISSING", "FP-015 backlog status differs")
            item["status"] = "PARTIAL"
            item["action"] = (
                "FP-015 내부 철회·계정 삭제·서버 원장·민감 경로 fail-closed 구현을 "
                "법률·개인정보 승인과 실제 사용자·보호자·TalkBack·실기기·운영 "
                "배포·정식 검증 Workstream에 연결한다."
            )
    require(changed == 1, "FP-015 backlog row count differs")
    epic = next(
        item for item in backlog["epics"] if item.get("epic_id") == "EPIC-02"
    )
    epic["current_status"] = "IN_PROGRESS"
    epic["current_status_reason"] = (
        "FP-017·FP-018·NPC·FP-004·FP-005·FP-006·FP-010·FP-011·FP-015 내부 "
        "구현은 진행됐지만 EPIC-02의 후속 정책과 법률·운영·사용자·기기·정식 "
        "검증이 남아 있다."
    )
    backlog["source_predecessor"] = {
        "path": relative(BACKLOG_R016_JSON),
        "file_sha256": EXPECTED_PREDECESSOR_SHA256[BACKLOG_R016_JSON],
        "preserved_unchanged": True,
    }
    backlog["next_single_action"] = {
        "epic_id": "EPIC-02",
        "work_item_id": "EPIC-02-FP015-WITHDRAWAL-ACCOUNT-DELETION",
        "source_policy_id": "FP-015",
        "gap_id": "GAP-024",
        "status": "PLANNED_NEXT_WITHIN_EPIC",
        "action": (
            "동의 철회나 계정 삭제가 시작되면 해당 수집·자동신고·학습재사용을 "
            "즉시 중지하고 휴대전화 미전송 자료 삭제와 서버 삭제요청·완료확인을 "
            "연결한다."
        ),
    }
    return base.seal(backlog, "backlog_content_sha256")


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
    events = []
    previous_events = {
        item["artifact_code"]: item
        for item in predecessor_overlay["events"]
        if isinstance(item, dict) and isinstance(item.get("artifact_code"), str)
    }
    for index, code in enumerate(sorted(previous_events), start=1):
        previous = previous_events[code]
        event = deepcopy(previous)
        event["event_id"] = f"WS-EPIC02-FP015-WITHDRAWAL-ACCOUNT-DELETION-ACTIVE-{index:03d}"
        event["predecessor_event_id"] = previous["event_id"]
        event["predecessor_overlay_id"] = predecessor_overlay["metadata"]["overlay_id"]
        event["summary"] = (
            "FP-015 네 가지 독립 동의, 서버 현재성 원장과 민감 경로 "
            "fail-closed 내부 구현·검증 및 GAP-024 PARTIAL 재평가를 연결한다."
        )
        event["evidence_record_ids"] = [
            implementation["document_id"],
            verification["document_id"],
            gap["metadata"]["report_id"],
            backlog["metadata"]["backlog_id"],
        ]
        events.append(event)
    overlay = {
        "schema_version": "1.0.0",
        "metadata": {
            "overlay_id": (
                "WS-EPIC-02-FP015-WITHDRAWAL-ACCOUNT-DELETION-ACTIVE-LEDGER-"
                "OVERLAY-20260725-001"
            ),
            "version": "0.1.0",
            "as_of": "2026-07-25",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-02 FP-015 철회·계정 삭제 Active successor overlay",
        },
        "authority_boundary": {
            **predecessor_overlay["authority_boundary"],
            "formal_test_completion_claimed": False,
            "legal_privacy_approval_claimed": False,
            "actual_user_test_completion_claimed": False,
            "actual_guardian_test_completion_claimed": False,
            "actual_talkback_user_test_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "actual_network_test_completion_claimed": False,
            "production_deployment_completion_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            {
                **base.file_record(FP013_OVERLAY_JSON),
                "name": "fp011_overlay_predecessor",
            },
            generated_record(
                IMPLEMENTATION_JSON,
                implementation_text,
                name="fp015_withdrawal_account_deletion_implementation",
            ),
            generated_record(
                VERIFICATION_JSON,
                verification_text,
                name="fp015_withdrawal_account_deletion_verification",
            ),
            generated_record(GAP_R017_JSON, gap_text, name="gap_r017"),
            generated_record(BACKLOG_R017_JSON, backlog_text, name="backlog_r017"),
        ],
        "application_rule": predecessor_overlay["application_rule"],
        "events": events,
        "draft_observations": predecessor_overlay["draft_observations"],
        "open_evidence_boundaries": {
            **predecessor_overlay["open_evidence_boundaries"],
            "fp015_withdrawal_account_deletion": "INTERNAL_PARTIAL_IMPLEMENTATION_VERIFIED",
            "fp015_legal_privacy_review_status": "NOT_RUN",
            "fp015_approved_final_copy_status": "NOT_RUN",
            "fp015_actual_user_status": "NOT_RUN",
            "fp015_actual_guardian_status": "NOT_RUN",
            "fp015_actual_talkback_user_status": "NOT_RUN",
            "fp015_actual_device_status": "NOT_RUN",
            "fp015_actual_network_status": "NOT_RUN",
            "fp015_production_deployment_status": "NOT_RUN",
            "fp015_formal_test_total": 279,
            "fp015_formal_test_total_status": "NOT_RUN",
            "fp015_withdrawal_account_deletion": "PLANNED_NEXT",
        },
        "formal_boundary": predecessor_overlay["formal_boundary"],
        "next_single_action": backlog["next_single_action"],
    }
    return base.seal(overlay, "overlay_content_sha256")


def build_successor(
    gap: dict[str, Any],
    gap_text: str,
    backlog: dict[str, Any],
    backlog_text: str,
    observed_at: str,
) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP015-WITHDRAWAL-ACCOUNT-DELETION-SUCCESSOR-20260725-001",
        "goal_id": GOAL_ID,
        "kind": "SUCCESSOR_TRACE",
        "status": "PASS",
        "observed_at": observed_at,
        "canonical_update_event_type": "CANONICAL_BINDINGS_UPDATED",
        "resulting_canonical_bindings": {
            "IMPLEMENTATION_BACKLOG": generated_binding(
                "IMPLEMENTATION_BACKLOG",
                BACKLOG_R017_JSON,
                backlog["metadata"]["backlog_id"],
                backlog_text,
            ),
            "IMPLEMENTATION_GAP": generated_binding(
                "IMPLEMENTATION_GAP",
                GAP_R017_JSON,
                gap["metadata"]["report_id"],
                gap_text,
            ),
        },
        "changed_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-015"],
            "IMPLEMENTATION_GAP": ["FP-015", "GAP-024"],
        },
        "next_policy_gap_pair": {
            "source_policy_id": "FP-015",
            "gap_id": "GAP-024",
        },
    }


def build_review(
    result_hashes: dict[str, str],
    review_subject_sha256: str,
    attestation: dict[str, Any],
    attestation_path: Path,
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
    reviewed_at = attestation.get("reviewed_at")
    require(
        isinstance(reviewer_id, str)
        and reviewer_id.startswith("CODEX-FP015-")
        and isinstance(reviewed_at, str)
        and reviewed_at.endswith("+09:00"),
        "review attestation provenance is malformed",
    )
    return {
        "schema_version": "1.0",
        "document_id": "WS-FP015-WITHDRAWAL-ACCOUNT-DELETION-INTERNAL-REVIEW-20260725-001",
        "evidence_type": "INDEPENDENT_INTERNAL_REVIEW",
        "goal_id": GOAL_ID,
        "status": "PASS",
        "reviewer_id": reviewer_id,
        "review_subject_sha256": review_subject_sha256,
        "reviewed_result_sha256_by_kind": deepcopy(
            attestation["reviewed_result_sha256_by_kind"]
        ),
        "reviewed_at": reviewed_at,
        "attestation_provenance": {
            "path": relative(REVIEW_ATTESTATION_JSON),
            "sha256": base.sha256_file(attestation_path),
        },
        "findings": deepcopy(attestation["findings"]),
        "review_boundary": deepcopy(attestation["review_boundary"]),
    }


def build_receipt(
    result_hashes: dict[str, str],
    review: dict[str, Any],
    review_text: str,
) -> dict[str, Any]:
    boundary = completion_boundary()
    boundary.pop("formal_test_ids")
    boundary.pop("release_gate_count")
    execution_session = latest_execution_event(
        base.load_json(CHECKPOINT)["goal_execution"]
    )
    require(execution_session is not None, "FP-015 execution event is missing")
    return {
        "schema_version": "1.0",
        "document_id": (
            "WS-FP015-WITHDRAWAL-ACCOUNT-DELETION-WORK-ITEM-COMPLETION-20260725-001"
        ),
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": base.sha256_file(GOAL_PATH),
        "work_item_id": "EPIC-02-FP015-WITHDRAWAL-ACCOUNT-DELETION",
        "source_policy_ids": ["FP-015"],
        "gap_ids": ["GAP-024"],
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
            "id": "CODEX-FP015-WITHDRAWAL-ACCOUNT-DELETION-IMPLEMENTER-20260725-001",
            "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            "authority": "GRAPH_V2_4_STANDING_EXECUTION_AUTHORITY",
        },
        "reviewer": {
            "id": review["reviewer_id"],
            "role": "SEPARATE_INTERNAL_REVIEWER",
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
        counts["MISSING"] == 13
        and counts["PARTIAL"] == 28
        and counts["IMPLEMENTED"] == 0,
        "r016 status counts differ",
    )
    report = deepcopy(predecessor_gap)
    report.pop("report_content_sha256", None)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260725-017",
        "version": "0.17.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": verification["observed_at"],
        "baseline_id": predecessor_gap["metadata"]["baseline_id"],
        "baseline_version": predecessor_gap["metadata"]["baseline_version"],
        "predecessor_report_id": predecessor_gap["metadata"]["report_id"],
    }
    report["purpose"] = (
        "FP-015 withdrawal and account-deletion state machines, immediate "
        "fail-closed processing boundaries and per-store deletion tracking are "
        "implemented and verified internally. GAP-024 alone is reassessed while "
        "the other 67 assessments are carried forward from r016."
    )
    report["source_bindings"] = predecessor_gap["source_bindings"] + [
        {**base.file_record(BUILDER), "name": "fp015_deletion_trace_builder"},
        {**base.file_record(BUILDER_TEST), "name": "fp015_deletion_builder_test"},
        {**base.file_record(START_GATE_RECEIPT), "name": "fp015_start_gate"},
        {
            **base.file_record(START_GATE_REPOSITORY_STATE_LOG),
            "name": "fp015_gate_repository_state",
        },
        generated_record(
            IMPLEMENTATION_JSON,
            implementation_text,
            name="fp015_deletion_implementation",
        ),
        generated_record(
            VERIFICATION_JSON,
            verification_text,
            name="fp015_deletion_verification",
        ),
    ]
    report["source_binding_sha256"] = base.object_sha256(report["source_bindings"])
    files = implementation_files()
    snapshot = {
        "scope_kind": "EPIC_02_FP015_EXACT_34_PATH_SET",
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
            "GAP-024 internal withdrawal and deletion controls are verified, "
            "but formal, legal, real-device, external-store and release evidence "
            "remain NOT_RUN; the result is PARTIAL."
        ),
    }
    implementation_hash = base.sha256_bytes(implementation_text.encode("utf-8"))
    verification_hash = base.sha256_bytes(verification_text.encode("utf-8"))
    evidence_id = "EVD-FP015-WITHDRAWAL-DELETION-20260725"
    report["evidence_catalog"] = predecessor_gap["evidence_catalog"] + [
        {
            "evidence_id": evidence_id,
            "kind": "INTERNAL_PRODUCER_RESULT",
            "claim": (
                "FP-015 repository-internal withdrawal and account-deletion "
                "state machines and Android/Gateway fail-closed regressions"
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
        item for item in report["assessments"] if item["gap_id"] == "GAP-024"
    ]
    require(len(matches) == 1, "GAP-024 assessment count differs")
    assessment = matches[0]
    require(
        assessment.get("source_policy_id") == "FP-015"
        and assessment.get("status") == "MISSING",
        "GAP-024 predecessor assessment differs",
    )
    assessment["status"] = "PARTIAL"
    assessment["formal_test_status"] = "NOT_RUN"
    assessment["current_implementation_in_plain_language"] = (
        "Android and Gateway now distinguish purpose withdrawal from account "
        "deletion, close new processing immediately, preserve monotonic "
        "per-store deletion states and expose retry, hold and contact status."
    )
    assessment["rationale"] = (
        "The exact 24-path internal implementation and five canonical regression "
        "layers passed. Actual deletion, legal review, formal tests, real devices, "
        "production operation and release gates remain NOT_RUN."
    )
    assessment["evidence_ids"] = list(
        dict.fromkeys([*assessment.get("evidence_ids", []), evidence_id])
    )
    assessment["fp015_reassessment"] = {
        "goal_id": GOAL_ID,
        "implementation_record_sha256": implementation_hash,
        "verification_result_sha256": verification_hash,
        "internal_control_status": "PASS",
        "formal_test_status": "NOT_RUN",
        "actual_external_deletion_status": "NOT_RUN",
        "legal_privacy_review_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
    }
    assessment.pop("assessment_sha256", None)
    assessment["assessment_sha256"] = base.object_sha256(assessment)
    carried_ids = [
        item["gap_id"]
        for item in report["assessments"]
        if item["gap_id"] != "GAP-024"
    ]
    require(len(carried_ids) == 67, "r017 carry-forward gap count differs")
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC02_FP015_GAP024_REASSESSMENT_WITH_R016_CARRY_FORWARD",
        "directly_reassessed_gap_ids": ["GAP-024"],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": ["GAP-024"],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": carried_ids,
        "carry_forward_warning": (
            "The other 67 assessments are preserved from r016."
        ),
        "next_adjacent_gap": {
            "gap_id": "GAP-023",
            "source_policy_id": "FP-014",
            "reason": "EPIC-02 deterministic execution order advances to item 16.",
        },
        "predecessor": {
            "report_id": predecessor_gap["metadata"]["report_id"],
            "path": relative(GAP_R016_JSON),
            "file_sha256": EXPECTED_PREDECESSOR_SHA256[GAP_R016_JSON],
            "evidence_count": len(predecessor_gap["evidence_catalog"]),
            "revalidated_wholesale_in_r017": False,
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
        "Only GAP-024 is directly reassessed; 67 assessments are carried from r016.",
        "TC-FP-015-01 through TC-FP-015-05 and all 279 formal tests are NOT_RUN.",
        "Legal, privacy, real-user, TalkBack and real-device review is NOT_RUN.",
        "Actual external-store and backup deletion and SLA certification are NOT_RUN.",
        "Production credentials, deployment and five unwaived release gates are NOT_RUN.",
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
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260725-017",
        "version": "0.17.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": observed_at,
        "predecessor_backlog_id": predecessor_backlog["metadata"]["backlog_id"],
    }
    backlog["source_predecessor"] = {
        "path": relative(BACKLOG_R016_JSON),
        "file_sha256": EXPECTED_PREDECESSOR_SHA256[BACKLOG_R016_JSON],
        "preserved_unchanged": False,
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    matching = [
        item
        for item in backlog["next_action_sequence"]
        if item.get("source_policy_id") == "FP-015"
    ]
    require(len(matching) == 1, "FP-015 backlog action count differs")
    require(matching[0].get("status") == "MISSING", "FP-015 backlog status differs")
    matching[0]["status"] = "PARTIAL"
    matching[0]["action"] = (
        "Connect the verified internal withdrawal and deletion controls to legal, "
        "real-user, real-device, external-store, formal and release verification."
    )
    epic = next(item for item in backlog["epics"] if item["epic_id"] == "EPIC-02")
    epic["current_status"] = "IN_PROGRESS"
    epic["current_status_reason"] = (
        "FP-015 internal withdrawal and account-deletion controls are verified; "
        "FP-014 and later policies plus external and release verification remain."
    )
    next_row = next(
        item
        for item in backlog["next_action_sequence"]
        if item.get("source_policy_id") == "FP-014"
    )
    backlog["next_single_action"] = {
        "epic_id": "EPIC-02",
        "source_policy_id": "FP-014",
        "gap_id": "GAP-023",
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
                "WS-EPIC-02-FP015-WITHDRAWAL-ACCOUNT-DELETION-"
                "ACTIVE-LEDGER-OVERLAY-20260725-001"
            ),
            "version": "0.1.0",
            "as_of": "2026-07-25",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-02 FP-015 withdrawal/account-deletion successor overlay",
        },
        "authority_boundary": {
            "predecessor_overlay_preserved": True,
            "canonical_active_files_modified_by_builder": False,
            "approved_baseline_or_predecessor_modified": False,
            "creates_new_product_policy": False,
            "formal_test_completion_claimed": False,
            "actual_external_deletion_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "production_deployment_completion_claimed": False,
            "epic_in_progress_claimed": True,
            "epic_complete_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": {
            "predecessor_overlay": base.file_record(FP013_OVERLAY_JSON),
            "implementation_record": generated_record(
                IMPLEMENTATION_JSON,
                implementation_text,
                name="fp015_implementation",
            ),
            "verification_result": generated_record(
                VERIFICATION_JSON,
                verification_text,
                name="fp015_verification",
            ),
            "implementation_gap": generated_binding(
                "IMPLEMENTATION_GAP",
                GAP_R017_JSON,
                gap["metadata"]["report_id"],
                gap_text,
            ),
            "implementation_backlog": generated_binding(
                "IMPLEMENTATION_BACKLOG",
                BACKLOG_R017_JSON,
                backlog["metadata"]["backlog_id"],
                backlog_text,
            ),
        },
        "goal_state": {
            "goal_id": GOAL_ID,
            "internal_result": "PARTIAL_IMPLEMENTATION_VERIFIED",
            "gap_id": "GAP-024",
            "gap_status": "PARTIAL",
            "next_source_policy_id": "FP-014",
            "next_gap_id": "GAP-023",
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
        "document_id": "WS-FP015-WITHDRAWAL-ACCOUNT-DELETION-SUCCESSOR-20260725-001",
        "goal_id": GOAL_ID,
        "kind": "SUCCESSOR_TRACE",
        "status": "PASS",
        "observed_at": observed_at,
        "canonical_update_event_type": "CANONICAL_BINDINGS_UPDATED",
        "resulting_canonical_bindings": {
            "IMPLEMENTATION_BACKLOG": generated_binding(
                "IMPLEMENTATION_BACKLOG",
                BACKLOG_R017_JSON,
                backlog["metadata"]["backlog_id"],
                backlog_text,
            ),
            "IMPLEMENTATION_GAP": generated_binding(
                "IMPLEMENTATION_GAP",
                GAP_R017_JSON,
                gap["metadata"]["report_id"],
                gap_text,
            ),
        },
        "changed_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-015"],
            "IMPLEMENTATION_GAP": ["FP-015", "GAP-024"],
        },
        "next_policy_gap_pair": {
            "source_policy_id": "FP-014",
            "gap_id": "GAP-023",
        },
    }


def gap_markdown(value: dict[str, Any]) -> str:
    row = next(item for item in value["assessments"] if item["gap_id"] == "GAP-024")
    return (
        "# WalkSafe 구현 Gap 분석 r017\n\n"
        "- 직접 재평가: `FP-015 / GAP-024`\n"
        f"- 판정: `{row['status']}` (`MISSING → PARTIAL`)\n"
        "- Android·Gateway focused/전체 회귀와 build·lint/typecheck·경계 검사: `PASS`\n"
        "- 실제 외부 삭제·법률·TC-FP-015-01~05·정식 279개·실제 사용자·"
        "TalkBack·기기·운영 배포: `NOT_RUN`\n"
        "- 출시 Gate 5개: `NOT_RUN` / 미면제\n"
        "- 출시: `NOT_ELIGIBLE`\n\n"
        f"{row['rationale']}\n"
    )


def backlog_markdown(value: dict[str, Any]) -> str:
    action = value["next_single_action"]
    return (
        "# WalkSafe 구현 보완 Backlog r017\n\n"
        "- EPIC-02: `IN_PROGRESS`\n"
        f"- 다음 정책·Gap: `{action['source_policy_id']} / {action['gap_id']}`\n"
        "- 다음 정책: `권한 상태 변경·재검사`\n"
        f"- 다음 작업: {action['action']}\n"
    )


def overlay_markdown(value: dict[str, Any]) -> str:
    return (
        "# EPIC-02 FP-015 철회·계정 삭제 Active ledger overlay\n\n"
        f"- Overlay: `{value['metadata']['overlay_id']}`\n"
        "- FP-015 내부 철회·삭제 구현·검증: `PARTIAL_IMPLEMENTATION_VERIFIED`\n"
        "- 실제 삭제·법률·정식 279개·사용자·TalkBack·기기·배포: `NOT_RUN`\n"
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
        len(changed_artifacts) == 34,
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
            "scope": "EXACT_34_PATH_SET",
            "exact_path_count": 34,
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
    if not test_mode:
        for supplied, canonical in canonical_inputs:
            require(
                supplied.resolve() == canonical.resolve(),
                f"noncanonical evidence input: {supplied}",
            )
    predecessor_gap, predecessor_backlog, predecessor_overlay = validate_inputs()
    verification = build_verification(
        focused_log=focused_log,
        full_android_log=full_android_log,
        gateway_log=gateway_log,
        boundary_log=boundary_log,
        control_plane_log=control_plane_log,
    )
    verification_text = json_text(verification)
    implementation = build_implementation(
        observed_at=min(check["started_at"] for check in verification["checks"]),
        expected_content_set_sha256=expected_implementation_content_set_sha256,
    )
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
    require(review_subject.is_file(), "review subject is missing")
    require(
        review_subject.read_bytes() == review_subject_text.encode("utf-8"),
        "review subject differs from current implementation or verification",
    )
    review_subject_sha256 = base.sha256_bytes(review_subject_text.encode("utf-8"))
    attestation = (
        json.loads(review_attestation.read_text(encoding="utf-8"))
        if test_mode
        else base.load_json(review_attestation)
    )
    review = build_review(
        result_hashes,
        review_subject_sha256,
        attestation,
        review_attestation,
    )
    review_text = json_text(review)
    receipt = build_receipt(result_hashes, review, review_text)
    outputs = {
        IMPLEMENTATION_JSON: implementation_text,
        VERIFICATION_JSON: verification_text,
        SUCCESSOR_JSON: successor_text,
        REVIEW_SUBJECT_JSON: review_subject_text,
        REVIEW_JSON: review_text,
        RECEIPT_JSON: json_text(receipt),
        GAP_R017_JSON: gap_text,
        GAP_R016_MD: gap_markdown(gap),
        BACKLOG_R017_JSON: backlog_text,
        BACKLOG_R016_MD: backlog_markdown(backlog),
        FP015_OVERLAY_JSON: json_text(overlay),
        FP015_OVERLAY_MD: overlay_markdown(overlay),
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
                "FP-015 withdrawal-account-deletion trace: PASS "
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
        print(f"FP-015 withdrawal-account-deletion trace: FAIL: {exc}", file=sys.stderr)
        return 1
    mode_name = "WRITE" if args.write else "CHECK"
    print(
        f"FP-015 withdrawal-account-deletion trace: PASS outputs={len(outputs)} "
        f"next=FP-015/GAP-024 mode={mode_name}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
