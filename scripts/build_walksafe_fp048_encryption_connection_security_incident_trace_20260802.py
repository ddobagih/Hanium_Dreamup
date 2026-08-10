#!/usr/bin/env python3
"""Build and capture the cycle-free FP-048 internal evidence trace."""

from __future__ import annotations

import argparse
import base64
import binascii
from copy import deepcopy
import ctypes
from dataclasses import dataclass
from datetime import datetime
import errno
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
from typing import Any, Callable, Mapping
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R001"
GOAL_REL = Path("docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/epic-03-fp048-encryption-connection-security-incident-r001.md")
RESULT_DIR_REL = Path(f"docs/control/execution/goal-results/{GOAL_ID}")
IMPLEMENTATION_REL = RESULT_DIR_REL / "implementation-record.json"
VERIFICATION_REL = RESULT_DIR_REL / "verification-result.json"
SUCCESSOR_REL = RESULT_DIR_REL / "successor-trace.json"
REVIEW_SUBJECT_REL = RESULT_DIR_REL / "review-subject.json"
REVIEW_ATTESTATION_REL = RESULT_DIR_REL / "review-attestation.json"
INDEPENDENT_REVIEW_REL = RESULT_DIR_REL / "independent-review.json"
COMPLETION_RECEIPT_REL = RESULT_DIR_REL / "completion-receipt.json"
START_GATE_DIR_REL = Path("docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-20260802-005")
START_GATE_RECEIPT_REL = START_GATE_DIR_REL / "implementation-start-gate-receipt.json"
START_GATE_REPOSITORY_STATE_REL = START_GATE_DIR_REL / "19-REPOSITORY_STATE.log"
CHECKPOINT_REL = Path("docs/control/walksafe-project-continuation-checkpoint.json")
EXPECTED_GOAL_SHA256 = "043b5a463914015a5918b885c3f69c242ece190358bd18ab1f884de46f553740"
EXPECTED_START_GATE_RECEIPT_SHA256 = "faba570d1e8d24651272b52e6ca0190ac43ff97c57bead00bcd5fd60e06951fd"
EXPECTED_START_GATE_REPOSITORY_STATE_SHA256 = "6516933756c6cca92aa34ad05c63fc1ae912151453304dfaa49007beec3e6f5d"
EXPECTED_START_GATE_RECEIPT_ID = "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP048-20260802-005"
EXPECTED_PINNED_HEAD = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"
EXPECTED_EXECUTION_EVENT_SEQUENCE = 42
EXPECTED_EXECUTION_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-20260802-005"
EXPECTED_EXECUTION_EVENT_SHA256 = "e8b4f8c91082e856934b93ea633047ddc32f8b1c7549c590b455483145fef1c9"
EXPECTED_EXECUTION_STARTED_AT = "2026-08-02T21:42:19+09:00"
LOCKED_PYTHON = "/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python"
BACKUP_PYTHON = "/home/linuxbrew/.linuxbrew/opt/python@3.14/bin/python3.14"
BACKUP_PYTHONPATH = "/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/lib/python3.12/site-packages"

EXPECTED_ANDROID_JUNIT_CLASSES = tuple(sorted((
    "kr.co.hanium.dreamup.walksafe.AccountDeletionResetCoordinatorTest",
    "kr.co.hanium.dreamup.walksafe.AndroidReportSuccessorStaticTest",
    "kr.co.hanium.dreamup.walksafe.BuildProvenanceStaticTest",
    "kr.co.hanium.dreamup.walksafe.MainActivityAccountDeletionStaticTest",
    "kr.co.hanium.dreamup.walksafe.MainActivityIntegratedConsentStaticTest",
    "kr.co.hanium.dreamup.walksafe.MainActivityWithdrawalRestartStaticTest",
    "kr.co.hanium.dreamup.walksafe.MainActivityWithdrawalStaticTest",
    "kr.co.hanium.dreamup.walksafe.PersistentReportQueueDisabledStaticTest",
    "kr.co.hanium.dreamup.walksafe.PriorityUserOnboardingStaticTest",
    "kr.co.hanium.dreamup.walksafe.fieldlog.FieldSessionAccountDeletionPrivacyFenceTest",
    "kr.co.hanium.dreamup.walksafe.fieldlog.PersistentFieldSessionLogTest",
    "kr.co.hanium.dreamup.walksafe.network.AndroidGatewaySessionCryptoLifecycleTest",
    "kr.co.hanium.dreamup.walksafe.network.AndroidGatewaySessionStoreStaticTest",
    "kr.co.hanium.dreamup.walksafe.network.UserNetworkSecurityBoundaryStaticTest",
    "kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarkerTest",
    "kr.co.hanium.dreamup.walksafe.report.MainActivityReportUploadStaticTest",
    "kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAeadStaticTest",
    "kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStoreTest",
    "kr.co.hanium.dreamup.walksafe.security.LocalAeadTest",
)))
ANDROID_JUNIT_RESULT_DIR_REL = Path("apps/android/app/build/test-results/testDebugUnitTest")
EXPECTED_ANDROID_JUNIT_XML_RELATIVES = tuple(
    ANDROID_JUNIT_RESULT_DIR_REL / f"TEST-{class_name}.xml"
    for class_name in EXPECTED_ANDROID_JUNIT_CLASSES
)
ANDROID_JUNIT_EMIT_COMMAND = (
    f"{LOCKED_PYTHON} -B scripts/build_walksafe_fp048_encryption_connection_security_incident_trace_20260802.py "
    "--root . --emit-android-junit-xml"
)

ANDROID_COMMAND = (
    "cd apps/android && ./gradlew :app:testDebugUnitTest --offline --no-daemon --rerun-tasks "
    "--tests 'kr.co.hanium.dreamup.walksafe.security.*' "
    "--tests 'kr.co.hanium.dreamup.walksafe.fieldlog.*' "
    "--tests 'kr.co.hanium.dreamup.walksafe.network.AndroidGatewaySessionStoreStaticTest' "
    "--tests 'kr.co.hanium.dreamup.walksafe.network.UserNetworkSecurityBoundaryStaticTest' "
    "--tests 'kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarkerTest' "
    "--tests 'kr.co.hanium.dreamup.walksafe.report.MainActivityReportUploadStaticTest' "
    "--tests 'kr.co.hanium.dreamup.walksafe.AndroidReportSuccessorStaticTest' "
    "--tests 'kr.co.hanium.dreamup.walksafe.MainActivityAccountDeletionStaticTest' "
    "--tests 'kr.co.hanium.dreamup.walksafe.MainActivityIntegratedConsentStaticTest' "
    "--tests 'kr.co.hanium.dreamup.walksafe.MainActivityWithdrawalRestartStaticTest' "
    "--tests 'kr.co.hanium.dreamup.walksafe.MainActivityWithdrawalStaticTest' "
    "--tests 'kr.co.hanium.dreamup.walksafe.PersistentReportQueueDisabledStaticTest' "
    "--tests 'kr.co.hanium.dreamup.walksafe.PriorityUserOnboardingStaticTest' "
    "--tests 'kr.co.hanium.dreamup.walksafe.AccountDeletionResetCoordinatorTest' "
    "--tests 'kr.co.hanium.dreamup.walksafe.BuildProvenanceStaticTest' "
    "--tests 'kr.co.hanium.dreamup.walksafe.network.AndroidGatewaySessionCryptoLifecycleTest' "
    f"&& cd ../.. && {ANDROID_JUNIT_EMIT_COMMAND} "
    f"&& {LOCKED_PYTHON} -B -m pytest -p no:cacheprovider -q "
    "tests/test_android_field_session_summary.py tests/test_release_evidence_gate.py "
    "&& cd apps/android && ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug --offline --no-daemon --rerun-tasks"
)
HTTPS_COMMAND = (
    "cd apps/android && ./gradlew :app:testDebugUnitTest --offline --no-daemon --rerun-tasks "
    "--tests 'kr.co.hanium.dreamup.walksafe.network.UserNetworkSecurityBoundaryStaticTest' "
    "&& cd ../android-gateway && npm run typecheck && npm test"
)
BACKUP_COMMAND = (
    f"PYTHONPATH={BACKUP_PYTHONPATH} {BACKUP_PYTHON} -B -m pytest -p no:cacheprovider -q "
    "tests/test_walksafe_backup_integrity.py tests/test_walksafe_backup_prune.py"
)
RETENTION_COMMAND = (
    f"PYTHONPATH=backend {LOCKED_PYTHON} -m pytest -q "
    "tests/test_report_retention_operational_safety.py tests/test_report_retention_encrypted_objects.py"
)
SERVER_COMMAND = (
    f"PYTHONPATH=backend {LOCKED_PYTHON} -m pytest -q backend/tests "
    "--ignore=backend/tests/test_reports.py --ignore=backend/tests/test_reports_v2.py "
    "--ignore=backend/tests/test_actor_rate_limit_store.py --ignore=backend/tests/test_test_storage_isolation.py "
    f"&& {RETENTION_COMMAND} && {BACKUP_COMMAND}"
)
KEY_ACCESS_COMMAND = (
    f"PYTHONPATH=backend {LOCKED_PYTHON} -m pytest -q "
    "backend/tests/test_report_image_crypto.py backend/tests/test_report_image_keyring.py "
    "backend/tests/test_report_original_access.py backend/tests/test_report_storage_reconciliation.py "
    "backend/tests/test_uploads.py backend/tests/test_report_policy.py backend/tests/test_field_test_security.py "
    "backend/tests/test_health_readiness.py backend/tests/test_admin_security.py backend/tests/test_openapi_contract.py "
    "tests/test_report_retention_encrypted_objects.py"
)

@dataclass(frozen=True)
class LaneSpec:
    lane_id: str
    name: str
    command: str
    slug: str
    exact_markers: tuple[tuple[str, str], ...]

    @property
    def log_rel(self) -> Path:
        return RESULT_DIR_REL / f"logs/{self.slug}.log"

    @property
    def receipt_rel(self) -> Path:
        return RESULT_DIR_REL / f"evidence/{self.slug}-receipt.json"

LANES = (
    LaneSpec("ANDROID_PROTECTED_STORAGE", "Android protected local storage", ANDROID_COMMAND, "android-protected-storage", (
        ("WALKSAFE_ANDROID_JUNIT_SUITES", "19"), ("WALKSAFE_ANDROID_JUNIT_TESTS", "147"),
        ("WALKSAFE_ANDROID_JUNIT_FAILURES", "0"), ("WALKSAFE_ANDROID_JUNIT_ERRORS", "0"),
        ("WALKSAFE_ANDROID_JUNIT_SKIPPED", "0"), ("WALKSAFE_ANDROID_HOST_PYTEST_PASSED", "303"),
        ("WALKSAFE_ANDROID_BUILD_STATUS", "PASS"),
    )),
    LaneSpec("HTTPS_NO_DOWNGRADE", "Android and Gateway HTTPS/no-downgrade boundary", HTTPS_COMMAND, "https-no-downgrade", (
        ("WALKSAFE_ANDROID_HTTPS_STATIC_STATUS", "PASS"), ("WALKSAFE_GATEWAY_TESTS", "77"),
        ("WALKSAFE_GATEWAY_PASS", "77"), ("WALKSAFE_GATEWAY_FAIL", "0"),
        ("WALKSAFE_GATEWAY_TYPECHECK_STATUS", "PASS"),
    )),
    LaneSpec("SERVER_ORIGINAL_DB_BACKUP_BOUNDARY", "Server original, database and backup encryption boundary", SERVER_COMMAND, "server-original-db-backup-boundary", (
        ("WALKSAFE_BACKEND_INTERNAL_PASSED", "441"), ("WALKSAFE_BACKEND_INTERNAL_SKIPPED", "11"),
        ("WALKSAFE_RETENTION_INTERNAL_PASSED", "38"), ("WALKSAFE_BACKUP_INTERNAL_PASSED", "64"), ("WALKSAFE_DATABASE_INTEGRATION_STATUS", "NOT_RUN"),
        ("WALKSAFE_BACKUP_RESTORE_STATUS", "NOT_RUN"),
    )),
    LaneSpec("KEY_LIFECYCLE_ORIGINAL_ACCESS", "Key lifecycle and purpose-bound original access", KEY_ACCESS_COMMAND, "key-lifecycle-original-access", (
        ("WALKSAFE_BACKEND_FOCUSED_PASSED", "254"), ("WALKSAFE_BACKEND_FOCUSED_SKIPPED", "11"),
        ("WALKSAFE_EXTERNAL_KMS_STATUS", "NOT_RUN"),
    )),
    LaneSpec("INCIDENT_STATE_NOTICE_DECISION", "Incident state and notification-decision boundary", BACKUP_COMMAND, "incident-state-notice-decision", (
        ("WALKSAFE_INCIDENT_INTERNAL_STATUS", "PASS"), ("WALKSAFE_INCIDENT_TEST_UNIVERSE_PASSED", "64"),
        ("WALKSAFE_LEGAL_REVIEW_STATUS", "NOT_RUN"), ("WALKSAFE_PRIVACY_REVIEW_STATUS", "NOT_RUN"),
        ("WALKSAFE_NOTIFICATION_EXECUTION_STATUS", "NOT_RUN"),
    )),
)

ANDROID_PATHS = (
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/AccountDeletionResetCoordinator.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/security/LocalAead.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/security/AndroidKeyStoreAead.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/security/AndroidSensitivePreferenceStore.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidGatewaySessionStore.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionLog.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/README.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidAccountDeletionFallbackMarker.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/AndroidManifest.xml", "apps/android/app/src/debug/AndroidManifest.xml",
    "apps/android/app/src/main/res/xml/data_extraction_rules.xml",
    "apps/android/app/src/debug/res/xml/debug_network_security_config.xml",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/security/LocalAeadTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/security/AndroidKeyStoreAeadStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/security/AndroidSensitivePreferenceStoreTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/AndroidGatewaySessionStoreStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/UserNetworkSecurityBoundaryStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/fieldlog/PersistentFieldSessionLogTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionAccountDeletionPrivacyFenceTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/fieldlog/TestFieldAead.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/AndroidAccountDeletionFallbackMarkerTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/AccountDeletionResetCoordinatorTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/BuildProvenanceStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccountDeletionStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/AndroidGatewaySessionCryptoLifecycleTest.kt",
    "apps/android/app/src/androidTest/java/kr/co/hanium/dreamup/walksafe/security/AndroidKeyStoreAeadInstrumentedTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityWithdrawalRestartStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/PriorityUserOnboardingStaticTest.kt",
    "scripts/pull_android_field_sessions_20260710.py", "scripts/summarize_android_field_sessions_20260710.py",
    "tests/test_android_field_session_summary.py", "tests/test_release_evidence_gate.py",
)
GATEWAY_PATHS = (
    "apps/android-gateway/README.md", "apps/android-gateway/package.json", "apps/android-gateway/server.ts",
    "apps/android-gateway/src/encrypted-json-store.ts", "apps/android-gateway/src/state-encryption-maintenance.ts",
    "apps/android-gateway/src/auth.ts", "apps/android-gateway/src/field-long-session.ts",
    "apps/android-gateway/src/field-walk-ledger.ts", "apps/android-gateway/src/integrated-consent.ts",
    "apps/android-gateway/src/privacy-rights.ts", "apps/android-gateway/src/config.ts",
    "apps/android-gateway/src/exclusive-file-lock.ts", "apps/android-gateway/test/state-encryption-fixture.ts",
    "apps/android-gateway/test/encrypted-json-store.test.ts", "apps/android-gateway/test/state-encryption-maintenance.test.ts",
    "apps/android-gateway/test/field-long-session.test.ts", "apps/android-gateway/test/field-walk-ledger.test.ts",
    "apps/android-gateway/test/integrated-consent.test.ts", "apps/android-gateway/test/privacy-rights.test.ts",
    "apps/android-gateway/test/gateway-contract.test.ts", "apps/android-gateway/test/node-adapter.test.ts",
    "deploy/config/walksafe-android-gateway.env.example", "deploy/systemd/walksafe-android-gateway.service",
)
BACKEND_PATHS = (
    "backend/.env.example", "backend/README.md", "backend/alembic/versions/202608020001_report_image_encryption.py",
    "backend/app/config.py", "backend/app/main.py", "backend/app/models.py", "backend/app/openapi_contract.py",
    "backend/app/schemas.py", "backend/app/field_test_security.py", "backend/app/api/health.py",
    "backend/app/api/reports.py", "backend/app/api/uploads.py", "backend/app/services/report_image_crypto.py",
    "backend/app/services/report_image_keys.py", "backend/app/services/report_original_access.py",
    "backend/app/services/report_storage.py", "backend/app/services/admin_security.py", "backend/requirements.txt",
    "backend/requirements.lock", "backend/tests/conftest.py", "backend/tests/test_field_test_security.py",
    "backend/tests/test_health_readiness.py", "backend/tests/test_openapi_contract.py",
    "backend/tests/test_report_image_crypto.py", "backend/tests/test_report_image_keyring.py",
    "backend/tests/test_report_original_access.py", "backend/tests/test_report_policy.py",
    "backend/tests/test_report_storage_reconciliation.py", "backend/tests/test_reports.py",
    "backend/tests/test_reports_v2.py", "backend/tests/test_uploads.py", "backend/tests/test_admin_security.py",
    "contracts/walksafe.openapi.json", "scripts/generate_walksafe_openapi.py",
    "tests/general-quality-cp312-linux-x86_64-cpu.lock",
    "deploy/config/walksafe-backend.env.example", "deploy/systemd/walksafe-backend.service",
    "scripts/check_walksafe_backup_source_20260713.py", "backend/tests/test_backup_source.py",
    "scripts/check_report_retention_dry_run.py", "backend/tests/test_report_retention.py",
    "tests/test_report_retention_encrypted_objects.py",
)
BACKUP_INCIDENT_PATHS = (
    "scripts/backup_walksafe_data_20260711.sh", "scripts/prune_walksafe_backups_20260711.py",
    "scripts/restore_walksafe_backup_drill_20260711.sh", "scripts/walksafe_backup_integrity.py",
    "tests/test_walksafe_backup_integrity.py", "tests/test_walksafe_backup_prune.py",
)
TOOLING_PATHS = (
    "scripts/build_walksafe_fp048_encryption_connection_security_incident_trace_20260802.py",
    "tests/test_walksafe_fp048_encryption_connection_security_incident_trace_20260802.py",
)
IMPLEMENTATION_PATHS = ANDROID_PATHS + GATEWAY_PATHS + BACKEND_PATHS + BACKUP_INCIDENT_PATHS + TOOLING_PATHS
R023_GAP_REL = Path("docs/control/audits/walksafe-implementation-gap-analysis-20260802-r023.json")
R023_BACKLOG_REL = Path("docs/control/audits/walksafe-implementation-remediation-backlog-20260802-r023.json")
EXPECTED_R023_REPORT_ID = "WS-IMPLEMENTATION-GAP-ANALYSIS-20260802-023"
EXPECTED_R023_BACKLOG_ID = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260802-023"
FP048_ARTIFACT_SUCCESSOR_ID = "WS-FP048-ARTIFACT-TRACE-SUCCESSOR-20260802-001"
FP048_ARTIFACT_CONSUMERS = (
    ("ARTIFACT_CHANGE_LOG", Path("docs/deliverables/00-control/artifact-change-log.json"), "walksafe.artifact-change-log.v1"),
    ("ARTIFACT_REGISTER", Path("docs/deliverables/00-control/artifact-register.json"), "walksafe.artifact-register.v1"),
    ("REQUIREMENTS_TRACEABILITY", Path("docs/deliverables/03-requirements/rtm.json"), "walksafe.requirements-traceability-draft.v1"),
    ("DESIGN_TRACEABILITY", Path("docs/deliverables/04-design/design-traceability-register.json"), "walksafe.design-traceability-register.v1"),
    ("IMPLEMENTATION_MANIFEST", Path("docs/deliverables/05-implementation/implementation-manifest.json"), "walksafe.implementation-manifest.v1"),
    ("MODULE_REGISTER", Path("docs/deliverables/05-implementation/module-register.json"), "walksafe.dev-module-register.v1"),
)
R012_DIR_REL = Path("docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-exact257-successor-r012")
CONSUMER_CONTRACTS = (
    ("GAP_R023", R023_GAP_REL, "walksafe.implementation-gap-analysis.v1"),
    ("BACKLOG_R023", R023_BACKLOG_REL, "walksafe.implementation-remediation-backlog.v1"),
    *FP048_ARTIFACT_CONSUMERS,
    ("EXACT257_R012_LEDGER", R012_DIR_REL / "phase1-exact257-successor-ledger-r012.json", "walksafe.phase1-exact257-successor-ledger.v12"),
    ("EXACT257_R012_EVIDENCE", R012_DIR_REL / "evidence.json", "walksafe.phase1-exact257-successor-evidence.v12"),
    ("EXACT257_R012_CHECK_RECEIPT", R012_DIR_REL / "phase1-exact257-successor-check-receipt-r012.json", "walksafe.phase1-exact257-successor-check-receipt.v12"),
)
R012_ZERO_CREDITS = {
    "acceptance_count": 0,
    "actual_device_event_count": 0,
    "attestation_approval_count": 0,
    "execution_count": 0,
    "formal279_pass_count": 0,
    "formal_evidence_count": 0,
    "in_scope_substantive_credit_count": 0,
    "owner_approval_count": 0,
    "real_event_count": 0,
    "release_eligible_count": 0,
    "verified_rights_or_external_fact_count": 0,
}
EXPECTED_WORK_ITEM_ID = "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT"
VERIFICATION_INPUT_PATHS = (Path("tests/test_report_retention_operational_safety.py"),)
EXPECTED_VERIFICATION_INPUT_SHA256 = {
    VERIFICATION_INPUT_PATHS[0]: "83def3826dbf3701029f2c1c66f9c4292aee50a809220b71556d6309cd8a7369",
}
EXPECTED_VERIFICATION_INPUT_BYTE_COUNT = {VERIFICATION_INPUT_PATHS[0]: 18873}
EXECUTOR_ID = "CODEX-FP048-ENCRYPTION-CONNECTION-SECURITY-IMPLEMENTER-20260802-001"
EXECUTOR_TASK = "/root"
EXPECTED_REVIEWER_ID = "WS-FP048-INDEPENDENT-REVIEWER-001"
EXPECTED_REVIEWER_TASK = "/root/fp048_independent_review"
RECEIPT_OUTPUTS = tuple(lane.receipt_rel for lane in LANES)
PRE_REVIEW_OUTPUTS = RECEIPT_OUTPUTS + (IMPLEMENTATION_REL, VERIFICATION_REL, SUCCESSOR_REL, REVIEW_SUBJECT_REL)
POST_REVIEW_OUTPUTS = (INDEPENDENT_REVIEW_REL, COMPLETION_RECEIPT_REL)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
RUN_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{8,128}$")
RAW_OUTPUT_BEGIN = "WALKSAFE_RAW_COMMAND_OUTPUT_BEGIN"
RAW_OUTPUT_END = "WALKSAFE_RAW_COMMAND_OUTPUT_END"

class BuildError(RuntimeError):
    pass

def require(condition: bool, message: str) -> None:
    if not condition:
        raise BuildError(message)


def _required_os_flag(name: str) -> int:
    value = getattr(os, name, None)
    require(type(value) is int and value != 0, f"required OS flag is unavailable: {name}")
    return value

def object_sha256(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()

def bytes_sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()

def _file_identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
        info.st_nlink,
        info.st_size,
        info.st_mtime_ns,
        info.st_ctime_ns,
    )


def _validate_regular_authority(
    info: os.stat_result,
    label: str,
    *,
    require_single_link: bool = False,
    required_mode: int | None = None,
    required_uid: int | None = None,
) -> None:
    require(stat.S_ISREG(info.st_mode), f"not regular: {label}")
    if require_single_link:
        require(info.st_nlink == 1, f"hard-linked file: {label}")
    if required_mode is not None:
        require(stat.S_IMODE(info.st_mode) == required_mode, f"file mode differs: {label}")
    if required_uid is not None:
        require(info.st_uid == required_uid, f"file owner differs: {label}")


def _read_regular_path(
    path: Path,
    label: str,
    *,
    require_single_link: bool = False,
    required_mode: int | None = None,
    required_uid: int | None = None,
) -> bytes:
    before = path.lstat()
    _validate_regular_authority(
        before,
        label,
        require_single_link=require_single_link,
        required_mode=required_mode,
        required_uid=required_uid,
    )
    descriptor = os.open(
        path,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | _required_os_flag("O_NOFOLLOW"),
    )
    try:
        opened = os.fstat(descriptor)
        require(_file_identity(opened) == _file_identity(before), f"file FD/path identity differs: {label}")
        chunks: list[bytes] = []
        while chunk := os.read(descriptor, 1024 * 1024):
            chunks.append(chunk)
        after_fd = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    after_path = path.lstat()
    require(_file_identity(after_fd) == _file_identity(before), f"file changed while reading: {label}")
    require(_file_identity(after_path) == _file_identity(before), f"file path changed while reading: {label}")
    return b"".join(chunks)


def _snapshot_regular_relative(
    root: Path,
    relative: Path,
    *,
    require_single_link: bool = False,
    required_mode: int | None = None,
    required_uid: int | None = None,
) -> bytes:
    require(
        not relative.is_absolute()
        and relative.parts
        and "." not in relative.parts
        and ".." not in relative.parts
        and "\\" not in relative.as_posix(),
        f"unsafe path: {relative}",
    )
    resolved_root = root.resolve(strict=True)
    directory_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | _required_os_flag("O_DIRECTORY")
        | _required_os_flag("O_NOFOLLOW")
    )
    file_flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | _required_os_flag("O_NOFOLLOW")
    directories: list[int] = []
    directory_entries: list[tuple[int, str, tuple[int, ...]]] = []
    descriptor: int | None = None
    try:
        root_descriptor = os.open(resolved_root, directory_flags)
        directories.append(root_descriptor)
        root_identity = _file_identity(os.fstat(root_descriptor))
        for part in relative.parts[:-1]:
            parent_descriptor = directories[-1]
            child_descriptor = os.open(part, directory_flags, dir_fd=parent_descriptor)
            child_info = os.fstat(child_descriptor)
            require(stat.S_ISDIR(child_info.st_mode), f"not a directory ancestor: {relative}")
            directory_entries.append((parent_descriptor, part, _file_identity(child_info)))
            directories.append(child_descriptor)
        descriptor = os.open(relative.parts[-1], file_flags, dir_fd=directories[-1])
        before = os.fstat(descriptor)
        _validate_regular_authority(
            before,
            relative.as_posix(),
            require_single_link=require_single_link,
            required_mode=required_mode,
            required_uid=required_uid,
        )
        chunks: list[bytes] = []
        while chunk := os.read(descriptor, 1024 * 1024):
            chunks.append(chunk)
        require(
            _file_identity(os.fstat(descriptor)) == _file_identity(before),
            f"file changed while reading: {relative}",
        )
        final_entry = os.stat(relative.parts[-1], dir_fd=directories[-1], follow_symlinks=False)
        require(_file_identity(final_entry) == _file_identity(before), f"file path changed while reading: {relative}")
        for parent_descriptor, part, identity in directory_entries:
            observed = os.stat(part, dir_fd=parent_descriptor, follow_symlinks=False)
            require(_file_identity(observed) == identity, f"ancestor changed while reading: {relative}")
        require(
            _file_identity(os.stat(resolved_root, follow_symlinks=False)) == root_identity,
            f"root changed while reading: {relative}",
        )
        return b"".join(chunks)
    except FileNotFoundError as exc:
        raise BuildError(f"file missing: {relative}") from exc
    except OSError as exc:
        raise BuildError(f"cannot safely snapshot: {relative}: {exc.strerror or exc}") from exc
    finally:
        if descriptor is not None:
            os.close(descriptor)
        for directory in reversed(directories):
            os.close(directory)


def file_sha256(path: Path) -> str:
    return bytes_sha256(_read_regular_path(path, str(path)))

def json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n"

def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        require(key not in value, f"duplicate JSON member: {key}")
        value[key] = item
    return value

def json_from_bytes(content: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(
            content.decode(),
            object_pairs_hook=_reject_duplicates,
            parse_constant=lambda token: (_ for _ in ()).throw(ValueError(token)),
        )
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise BuildError(f"{label} is not valid UTF-8 JSON") from exc
    require(isinstance(value, dict), f"{label} root is not an object")
    return value

def parse_time(value: Any, label: str) -> datetime:
    require(isinstance(value, str), f"{label} is not a timestamp")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise BuildError(f"{label} is not ISO") from exc
    require(parsed.tzinfo is not None and parsed.utcoffset() is not None, f"{label} lacks offset")
    return parsed

def safe_path(root: Path, relative: Path, *, must_exist: bool = True) -> Path:
    require(
        not relative.is_absolute()
        and relative.parts
        and ".." not in relative.parts
        and "." not in relative.parts,
        f"unsafe path: {relative}",
    )
    resolved_root = root.resolve()
    candidate = resolved_root
    observed: list[tuple[Path, tuple[int, ...]]] = []
    for part in relative.parts:
        candidate /= part
        if os.path.lexists(candidate):
            info = candidate.lstat()
            require(not stat.S_ISLNK(info.st_mode), f"symlink path: {relative}")
            observed.append((candidate, _file_identity(info)))
    resolved = candidate.resolve(strict=False)
    require(resolved_root in resolved.parents, f"path escapes root: {relative}")
    if must_exist:
        require(resolved.is_file(), f"file missing: {relative}")
    for path, identity in observed:
        require(_file_identity(path.lstat()) == identity, f"path changed during resolution: {relative}")
    return resolved

def snapshot_file(
    root: Path,
    relative: Path,
    *,
    require_single_link: bool = False,
    required_mode: int | None = None,
    required_uid: int | None = None,
) -> bytes:
    return _snapshot_regular_relative(
        root,
        relative,
        require_single_link=require_single_link,
        required_mode=required_mode,
        required_uid=required_uid,
    )


def snapshot_json(
    root: Path,
    relative: Path,
    *,
    require_single_link: bool = False,
    required_mode: int | None = None,
    required_uid: int | None = None,
) -> tuple[bytes, dict[str, Any]]:
    content = snapshot_file(
        root,
        relative,
        require_single_link=require_single_link,
        required_mode=required_mode,
        required_uid=required_uid,
    )
    return content, json_from_bytes(content, relative.as_posix())

def load_json(root: Path, relative: Path) -> dict[str, Any]:
    return snapshot_json(root, relative)[1]

def completion_boundary() -> dict[str, Any]:
    return {
        "formal_test_ids": [f"TC-FP-048-{number:02d}" for number in range(1, 8)],
        "formal_test_status": "NOT_RUN", "formal_279_status": "NOT_RUN", "actual_device_status": "NOT_RUN",
        "external_tls_status": "NOT_RUN", "external_kms_status": "NOT_RUN", "external_cloud_status": "NOT_RUN",
        "external_backup_restore_status": "NOT_RUN", "external_security_review_status": "NOT_RUN",
        "external_legal_review_status": "NOT_RUN", "external_privacy_review_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN", "release_gate_count": 2, "release_gate_status": "NOT_RUN",
        "release_gates_waived": False, "external_independence_claimed": False, "release_status": "NOT_ELIGIBLE",
    }

def expected_review_boundary() -> dict[str, Any]:
    value = completion_boundary()
    value.pop("formal_test_ids")
    value.pop("release_gate_count")
    value["separate_internal_review_pass"] = True
    return value

# authority and exact before/after binding

def validate_authority(root: Path = ROOT) -> dict[str, Any]:
    require(file_sha256(safe_path(root, GOAL_REL)) == EXPECTED_GOAL_SHA256, "goal differs")
    receipt_bytes = snapshot_file(root, START_GATE_RECEIPT_REL)
    require(bytes_sha256(receipt_bytes) == EXPECTED_START_GATE_RECEIPT_SHA256, "start receipt differs")
    receipt = json_from_bytes(receipt_bytes, "start receipt")
    require(
        receipt.get("document_id") == EXPECTED_START_GATE_RECEIPT_ID
        and receipt.get("target_goal_id") == GOAL_ID
        and receipt.get("target_goal_content_sha256") == EXPECTED_GOAL_SHA256
        and receipt.get("target_transition_event_id") == EXPECTED_EXECUTION_EVENT_ID
        and receipt.get("status") == "PASS",
        "start receipt identity differs",
    )
    state_bytes = snapshot_file(root, START_GATE_REPOSITORY_STATE_REL)
    require(bytes_sha256(state_bytes) == EXPECTED_START_GATE_REPOSITORY_STATE_SHA256, "repository-state differs")
    state = json_from_bytes(state_bytes, "repository-state")
    repository = state.get("repository", {})
    require(
        state.get("evidence_type") == "GATE_REPOSITORY_STATE"
        and state.get("gate_event_id") == EXPECTED_EXECUTION_EVENT_ID
        and repository.get("head_commit") == EXPECTED_PINNED_HEAD
        and repository.get("object_format") == "sha1",
        "repository-state identity differs",
    )
    snapshot = receipt.get("repository_snapshot", {})
    require(
        snapshot.get("head_commit") == EXPECTED_PINNED_HEAD
        and snapshot.get("gate_repository_state_output_sha256") == EXPECTED_START_GATE_REPOSITORY_STATE_SHA256,
        "receipt repository binding differs",
    )
    history = load_json(root, CHECKPOINT_REL).get("goal_execution", {}).get("transition_history")
    require(isinstance(history, list) and history, "execution history missing")
    sequences = [item.get("sequence") for item in history if isinstance(item, dict)]
    require(
        len(sequences) == len(history)
        and all(isinstance(value, int) and not isinstance(value, bool) for value in sequences)
        and sequences == sorted(sequences)
        and len(sequences) == len(set(sequences)),
        "execution sequence differs",
    )
    for event in history:
        payload = {key: value for key, value in event.items() if key != "event_sha256"}
        require(object_sha256(payload) == event.get("event_sha256"), f"event seal differs: {event.get('event_id')}")
    matches = [event for event in history if event.get("sequence") == 42 or event.get("event_id") == EXPECTED_EXECUTION_EVENT_ID]
    require(len(matches) == 1, "seq42 is not exact-one")
    event = matches[0]
    require(
        event.get("sequence") == 42 and event.get("event_id") == EXPECTED_EXECUTION_EVENT_ID
        and event.get("event_type") == "GOAL_STARTED" and event.get("subject_goal_id") == GOAL_ID
        and event.get("event_sha256") == EXPECTED_EXECUTION_EVENT_SHA256
        and event.get("occurred_at") == EXPECTED_EXECUTION_STARTED_AT
        and event.get("from_status") == "READY" and event.get("to_status") == "IN_PROGRESS"
        and event.get("implementation_start_gate_binding") == {
            "document_id": EXPECTED_START_GATE_RECEIPT_ID,
            "path": START_GATE_RECEIPT_REL.as_posix(),
            "file_sha256": EXPECTED_START_GATE_RECEIPT_SHA256,
        },
        "seq42 differs",
    )
    gate_ended_at = receipt.get("execution_window", {}).get("ended_at")
    parse_time(gate_ended_at, "gate end")
    return {
        "event": deepcopy(event), "gate_ended_at": gate_ended_at, "repository_state": state,
        "start_gate_binding": {"document_id": EXPECTED_START_GATE_RECEIPT_ID, "path": START_GATE_RECEIPT_REL.as_posix(), "sha256": EXPECTED_START_GATE_RECEIPT_SHA256},
        "repository_state_binding": {"path": START_GATE_REPOSITORY_STATE_REL.as_posix(), "sha256": EXPECTED_START_GATE_REPOSITORY_STATE_SHA256, "pinned_head": EXPECTED_PINNED_HEAD},
    }

def pinned_head_blob(root: Path, relative_path: str) -> bytes | None:
    listing = subprocess.run(["git", "ls-tree", "-z", "--full-tree", EXPECTED_PINNED_HEAD, "--", relative_path], cwd=root, check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    require(listing.returncode == 0, f"cannot inspect pinned HEAD: {relative_path}")
    if not listing.stdout:
        return None
    records = [record for record in listing.stdout.split(b"\0") if record]
    require(len(records) == 1, f"pinned path count differs: {relative_path}")
    metadata, separator, listed = records[0].partition(b"\t")
    require(separator == b"\t" and listed.decode() == relative_path and metadata.split()[1:2] == [b"blob"], f"not a pinned blob: {relative_path}")
    loaded = subprocess.run(["git", "show", f"{EXPECTED_PINNED_HEAD}:{relative_path}"], cwd=root, check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    require(loaded.returncode == 0, f"cannot read pinned HEAD: {relative_path}")
    return loaded.stdout

def before_state(root: Path, relative_path: str, repository_state: Mapping[str, Any]) -> tuple[bool, str | None, str]:
    paths = repository_state.get("dirty_snapshot", {}).get("paths", [])
    require(isinstance(paths, list), "dirty snapshot malformed")
    matches = [item for item in paths if isinstance(item, dict) and item.get("path") == relative_path and item.get("path_role") == "CURRENT"]
    require(len(matches) <= 1, f"duplicate dirty path: {relative_path}")
    if matches:
        worktree = matches[0].get("worktree", {})
        if worktree.get("state") == "PRESENT":
            digest = worktree.get("sha256")
            require(isinstance(digest, str) and SHA256_RE.fullmatch(digest), f"bad gate hash: {relative_path}")
            return True, digest, "SEQ42_START_GATE_DIRTY_SNAPSHOT"
        require(worktree.get("state") in {"ABSENT", "DELETED"}, f"bad gate state: {relative_path}")
        return False, None, "SEQ42_START_GATE_DIRTY_SNAPSHOT"
    blob = pinned_head_blob(root, relative_path)
    return (False, None, "SEQ42_PINNED_HEAD_ABSENT") if blob is None else (True, bytes_sha256(blob), "SEQ42_PINNED_HEAD")

def implementation_files(root: Path, repository_state: Mapping[str, Any]) -> list[dict[str, Any]]:
    require(len(IMPLEMENTATION_PATHS) == 106 and len(set(IMPLEMENTATION_PATHS)) == 106, "implementation path set differs")
    forbidden = ("review-subject", "review-attestation", "independent-review", "completion-receipt", "r023", "successor-r012")
    rows: list[dict[str, Any]] = []
    for relative_path in IMPLEMENTATION_PATHS:
        require(not any(fragment in relative_path for fragment in forbidden), f"consumer path in scope: {relative_path}")
        after_sha256 = file_sha256(safe_path(root, Path(relative_path)))
        existed, before_sha256, source = before_state(root, relative_path, repository_state)
        if existed:
            require(after_sha256 != before_sha256, f"path unchanged after seq42: {relative_path}")
        rows.append({
            "path": relative_path, "before_sha256": before_sha256, "after_sha256": after_sha256,
            "before_source": source, "change_kind": "MODIFIED" if existed else "ADDED",
        })
    return rows

def implementation_content_set(rows: list[dict[str, Any]]) -> str:
    require(rows and len({row.get("path") for row in rows}) == len(rows), "implementation rows differ")
    for row in rows:
        require(isinstance(row.get("path"), str) and isinstance(row.get("after_sha256"), str) and SHA256_RE.fullmatch(row["after_sha256"]), "implementation row malformed")
    return object_sha256([{"path": row["path"], "sha256": row["after_sha256"]} for row in rows])


def verification_input_manifest(
    root: Path,
    *,
    injected: list[dict[str, Any]] | None = None,
    test_mode: bool = False,
) -> tuple[list[dict[str, Any]], str]:
    if injected is not None:
        require(test_mode, "verification input injection is test-only")
        rows = deepcopy(injected)
    else:
        rows = []
        for relative in VERIFICATION_INPUT_PATHS:
            content = snapshot_file(root, relative)
            rows.append({
                "path": relative.as_posix(),
                "byte_count": len(content),
                "sha256": bytes_sha256(content),
            })
    require(
        [row.get("path") for row in rows] == [relative.as_posix() for relative in VERIFICATION_INPUT_PATHS],
        "verification input path set differs",
    )
    for row, relative in zip(rows, VERIFICATION_INPUT_PATHS, strict=True):
        require(
            set(row) == {"path", "byte_count", "sha256"}
            and type(row.get("byte_count")) is int
            and row["byte_count"] == EXPECTED_VERIFICATION_INPUT_BYTE_COUNT[relative]
            and row.get("sha256") == EXPECTED_VERIFICATION_INPUT_SHA256[relative],
            f"verification input identity differs: {relative}",
        )
    return rows, object_sha256(rows)

def _exact_line(content: str, marker: str, expected: str, label: str) -> None:
    matches = re.findall(rf"^{re.escape(marker)}=([^\r\n]*)$", content, flags=re.MULTILINE)
    require(matches == [expected], f"{label} {marker} binding differs")

def _raw_command_output(text: str, lane_id: str) -> str:
    begin = f"{RAW_OUTPUT_BEGIN}\n"
    end = f"\n{RAW_OUTPUT_END}\n"
    require(text.count(begin) == 1 and text.count(end) == 1, f"{lane_id} raw output boundary differs")
    prefix, raw_and_tail = text.split(begin, 1)
    raw, tail = raw_and_tail.split(end, 1)
    require(prefix and tail and raw, f"{lane_id} raw output is empty")
    require(
        re.search(r"^WALKSAFE_", raw, flags=re.MULTILINE) is None,
        f"{lane_id} raw output contains a reserved marker",
    )
    return raw + "\n"


def checked_log(
    spec: LaneSpec,
    content: bytes,
    *,
    root: Path,
    content_set_sha256: str,
    verification_input_content_set_sha256: str,
    event: Mapping[str, Any],
    junit_summary: Mapping[str, int] | None = None,
) -> dict[str, Any]:
    require(junit_summary is None, f"{spec.lane_id} injected JUnit summary is forbidden")
    try:
        text = content.decode()
    except UnicodeDecodeError as exc:
        raise BuildError(f"{spec.lane_id} log is not UTF-8") from exc
    command_sha256 = bytes_sha256(spec.command.encode())
    bindings = (
        ("WALKSAFE_EXECUTION_EVENT_SEQUENCE", str(event["sequence"])), ("WALKSAFE_EXECUTION_EVENT_ID", str(event["event_id"])),
        ("WALKSAFE_EXECUTION_EVENT_SHA256", str(event["event_sha256"])), ("WALKSAFE_IMPLEMENTATION_CONTENT_SET_SHA256", content_set_sha256),
        ("WALKSAFE_VERIFICATION_INPUT_CONTENT_SET_SHA256", verification_input_content_set_sha256),
        ("WALKSAFE_COMMAND_SHA256", command_sha256), ("WALKSAFE_COMMAND_EXIT_CODE", "0"),
        ("WALKSAFE_LANE_ID", spec.lane_id), ("WALKSAFE_LANE_STATUS", "PASS"), *spec.exact_markers,
    )
    for marker, expected in bindings:
        _exact_line(text, marker, expected, spec.lane_id)
    raw_output = _raw_command_output(text, spec.lane_id)
    observed = observed_lane_markers(
        spec,
        raw_output,
        root,
    )
    require(observed == dict(spec.exact_markers), f"{spec.lane_id} raw output assertions differ")
    run_ids = re.findall(r"^WALKSAFE_RUN_ID=([^\r\n]*)$", text, flags=re.MULTILINE)
    require(len(run_ids) == 1 and RUN_ID_RE.fullmatch(run_ids[0]), f"{spec.lane_id} run id differs")
    starts = re.findall(r"^WALKSAFE_COMMAND_STARTED_AT=([^\r\n]+)$", text, flags=re.MULTILINE)
    ends = re.findall(r"^WALKSAFE_COMMAND_ENDED_AT=([^\r\n]+)$", text, flags=re.MULTILINE)
    require(len(starts) == len(ends) == 1, f"{spec.lane_id} timestamp count differs")
    start, end = parse_time(starts[0], "log start"), parse_time(ends[0], "log end")
    require(start >= parse_time(EXPECTED_EXECUTION_STARTED_AT, "seq42") and start <= end, f"{spec.lane_id} time differs")
    return {
        "lane_id": spec.lane_id, "name": spec.name, "status": "PASS", "command": spec.command,
        "command_sha256": command_sha256, "run_id": run_ids[0], "exit_code": 0,
        "output_path": spec.log_rel.as_posix(), "output_sha256": bytes_sha256(content),
        "started_at": starts[0], "executed_at": ends[0], "execution_event_sequence": event["sequence"],
        "execution_event_id": event["event_id"], "execution_event_sha256": event["event_sha256"],
        "implementation_content_set_sha256": content_set_sha256, "binding_markers_exact_once": True,
        "verification_input_content_set_sha256": verification_input_content_set_sha256,
        "observed_assertions": dict(spec.exact_markers),
    }

def _seal(value: dict[str, Any], field: str) -> dict[str, Any]:
    value[field] = object_sha256(value)
    return value

def build_lane_receipt(
    check: Mapping[str, Any],
    authority: Mapping[str, Any],
    verification_inputs: list[dict[str, Any]],
    verification_input_content_set_sha256: str,
) -> dict[str, Any]:
    return _seal({
        "schema_version": "walksafe.fp048-internal-lane-receipt.v1",
        "document_id": f"WS-FP048-{check['lane_id']}-RECEIPT-20260802-001",
        "evidence_type": "INTERNAL_VERIFICATION_LANE_RECEIPT", "goal_id": GOAL_ID,
        "lane_id": check["lane_id"], "status": "PASS", "command_execution": deepcopy(dict(check)),
        "start_gate_binding": deepcopy(authority["start_gate_binding"]),
        "repository_state_binding": deepcopy(authority["repository_state_binding"]),
        "verification_input_manifest": deepcopy(verification_inputs),
        "verification_input_content_set_sha256": verification_input_content_set_sha256,
        "evidence_boundary": completion_boundary(),
    }, "receipt_content_sha256")

def build_implementation(rows: list[dict[str, Any]], content_set: str, observed_at: str, authority: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "1.0", "document_id": "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-IMPLEMENTATION-20260802-001",
        "goal_id": GOAL_ID, "kind": "IMPLEMENTATION_RECORD", "status": "PASS", "observed_at": observed_at,
        "scope_kind": "EXACT_ORDERED_FP048_IMPLEMENTATION_PATH_SET", "exact_path_count": len(rows),
        "implementation_content_set_sha256": content_set, "changed_artifacts": deepcopy(rows),
        "before_state_authority": "SEQ42_START_GATE_DIRTY_SNAPSHOT_THEN_PINNED_HEAD",
        "start_gate_binding": deepcopy(authority["start_gate_binding"]),
        "repository_state_binding": deepcopy(authority["repository_state_binding"]),
        "execution_event_binding": {"sequence": authority["event"]["sequence"], "event_id": authority["event"]["event_id"], "event_sha256": authority["event"]["event_sha256"]},
        "execution_session_event": {
            "sequence": authority["event"]["sequence"], "event_id": authority["event"]["event_id"],
            "event_type": authority["event"]["event_type"], "event_sha256": authority["event"]["event_sha256"],
            "occurred_at": authority["event"]["occurred_at"],
        },
        "implementation_start_gate_binding": deepcopy(authority["event"]["implementation_start_gate_binding"]),
        "implemented_controls": [
            "Android protected authenticated local storage with fail-closed key errors",
            "Android and Gateway HTTPS-only no-downgrade connection boundary",
            "separate encrypted server-original, database, backup and key-management boundaries",
            "active/decrypt-only/compromised key lifecycle without database key material",
            "purpose/scope/expiry/reconfirmation/audit-bound default-deny original access",
            "ordered incident detection, impact and containment stopping at unperformed legal and notice decisions",
        ],
        "evidence_boundary": completion_boundary(),
    }

def consumer_contract_manifest() -> list[dict[str, Any]]:
    return [{"role": role, "path": relative.as_posix(), "expected_schema_version": schema, "binding_stage": "OPTIONAL_LATER_POST_REVIEW_INPUT"} for role, relative, schema in CONSUMER_CONTRACTS]

def build_pre_review_outputs(
    *, root: Path = ROOT, log_snapshots: Mapping[str, bytes] | None = None,
    implementation_rows: list[dict[str, Any]] | None = None, authority: Mapping[str, Any] | None = None,
    junit_summary: Mapping[str, int] | None = None,
    verification_input_rows: list[dict[str, Any]] | None = None,
    test_mode: bool = False,
) -> dict[Path, str]:
    require(junit_summary is None, "injected Android JUnit summary is forbidden")
    root = root.resolve()
    auth = deepcopy(dict(authority)) if authority is not None else validate_authority(root)
    rows = deepcopy(implementation_rows) if implementation_rows is not None else implementation_files(root, auth["repository_state"])
    if not test_mode:
        require([row["path"] for row in rows] == list(IMPLEMENTATION_PATHS), "scope order differs")
    content_set = implementation_content_set(rows)
    verification_inputs, verification_input_content_set = verification_input_manifest(
        root,
        injected=verification_input_rows,
        test_mode=test_mode,
    )
    snapshots = log_snapshots or {}
    checks: list[dict[str, Any]] = []
    for spec in LANES:
        content = (
            snapshots[spec.lane_id]
            if spec.lane_id in snapshots
            else snapshot_file(
                root,
                spec.log_rel,
                require_single_link=True,
                required_mode=0o600,
                required_uid=os.getuid(),
            )
        )
        checks.append(
            checked_log(
                spec,
                content,
                root=root,
                content_set_sha256=content_set,
                verification_input_content_set_sha256=verification_input_content_set,
                event=auth["event"],
            )
        )
    first = min(checks, key=lambda item: parse_time(item["started_at"], "check start"))["started_at"]
    require(parse_time(auth["gate_ended_at"], "gate end") <= parse_time(first, "implementation"), "implementation predates gate")
    implementation = build_implementation(rows, content_set, first, auth)
    receipt_texts = {
        spec.receipt_rel: json_text(build_lane_receipt(check, auth, verification_inputs, verification_input_content_set))
        for spec, check in zip(LANES, checks, strict=True)
    }
    manifest = [
        {"lane_id": spec.lane_id, "path": spec.receipt_rel.as_posix(), "sha256": bytes_sha256(receipt_texts[spec.receipt_rel].encode()), "log_path": check["output_path"], "log_sha256": check["output_sha256"]}
        for spec, check in zip(LANES, checks, strict=True)
    ]
    latest = max(checks, key=lambda item: parse_time(item["executed_at"], "check end"))["executed_at"]
    verification = {
        "schema_version": "1.0", "document_id": "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-VERIFICATION-20260802-001",
        "goal_id": GOAL_ID, "kind": "VERIFICATION_RESULT", "status": "PASS", "observed_at": latest,
        "implementation_content_set_sha256": content_set, "checks": checks, "lane_receipts": manifest,
        "verification_input_manifest": deepcopy(verification_inputs),
        "verification_input_content_set_sha256": verification_input_content_set,
        "lane_receipt_content_set_sha256": object_sha256(manifest), "internal_lane_count": 5,
        "evidence_boundary": completion_boundary(),
    }
    implementation_text, verification_text = json_text(implementation), json_text(verification)
    successor = {
        "schema_version": "1.0", "document_id": "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-SUCCESSOR-20260802-001",
        "goal_id": GOAL_ID, "kind": "SUCCESSOR_TRACE", "status": "INTERNAL_VERIFICATION_RECORDED_REVIEW_PENDING",
        "observed_at": latest,
        "producer_results": {
            "IMPLEMENTATION_RECORD": {"path": IMPLEMENTATION_REL.as_posix(), "sha256": bytes_sha256(implementation_text.encode())},
            "VERIFICATION_RESULT": {"path": VERIFICATION_REL.as_posix(), "sha256": bytes_sha256(verification_text.encode())},
        },
        "downstream_consumer_contracts": consumer_contract_manifest(), "consumer_physical_hashes_bound": False,
        "cycle_boundary": "r023 and exact257 r012 consume implementation/verification and are never producer inputs",
        "gap_id": "GAP-057", "next_single_action": "SEPARATE_INTERNAL_REVIEW_AFTER_DOWNSTREAM_CONSUMER_MATERIALIZATION",
        "evidence_boundary": completion_boundary(),
    }
    successor_text = json_text(successor)
    hashes = {
        "IMPLEMENTATION_RECORD": bytes_sha256(implementation_text.encode()),
        "VERIFICATION_RESULT": bytes_sha256(verification_text.encode()),
        "SUCCESSOR_TRACE": bytes_sha256(successor_text.encode()),
    }
    subject = {
        "schema_version": "1.0", "evidence_type": "INTERNAL_REVIEW_SUBJECT", "goal_id": GOAL_ID,
        "reviewed_result_sha256_by_kind": hashes,
        "implementation_scope": {"scope_kind": implementation["scope_kind"], "exact_path_count": len(rows), "paths": [row["path"] for row in rows], "content_set_sha256": content_set},
        "verification_receipts": manifest, "verification_check_count": 5,
        "verification_input_manifest": deepcopy(verification_inputs),
        "verification_input_content_set_sha256": verification_input_content_set,
        "downstream_consumer_contracts": consumer_contract_manifest(), "consumer_physical_hashes_bound": False,
        "completion_boundary": completion_boundary(),
    }
    outputs = {**receipt_texts, IMPLEMENTATION_REL: implementation_text, VERIFICATION_REL: verification_text, SUCCESSOR_REL: successor_text, REVIEW_SUBJECT_REL: json_text(subject)}
    require(tuple(outputs) == PRE_REVIEW_OUTPUTS, "pre-review output set differs")
    return outputs

def _verify_json_seal(value: dict[str, Any], field: str, label: str) -> str:
    claimed = value.get(field)
    require(isinstance(claimed, str) and SHA256_RE.fullmatch(claimed) is not None, f"{label} seal missing")
    payload = deepcopy(value)
    payload.pop(field)
    require(object_sha256(payload) == claimed, f"{label} seal differs")
    return claimed


def _validate_exact_source_bindings(
    value: dict[str, Any],
    *,
    label: str,
    expected: Mapping[Path, tuple[bytes, str]],
) -> None:
    trace = value.get("fp048_artifact_trace_successor")
    require(isinstance(trace, dict) and trace.get("successor_id") == FP048_ARTIFACT_SUCCESSOR_ID, f"{label} FP-048 successor marker differs")
    raw = trace.get("input_bindings")
    require(isinstance(raw, list) and len(raw) == len(expected), f"{label} input binding count differs")
    by_path: dict[str, dict[str, Any]] = {}
    for binding in raw:
        require(isinstance(binding, dict), f"{label} input binding malformed")
        path = binding.get("path")
        require(isinstance(path, str) and path not in by_path, f"{label} input binding path differs")
        by_path[path] = binding
    require(set(by_path) == {path.as_posix() for path in expected}, f"{label} input binding path set differs")
    for relative, (content, digest) in expected.items():
        binding = by_path[relative.as_posix()]
        require(binding.get("sha256") == digest, f"{label} input binding SHA differs: {relative}")
        require(type(binding.get("byte_length")) is int and binding["byte_length"] == len(content), f"{label} input binding byte length differs: {relative}")


def _run_full_r023_validation(
    root: Path,
    snapshots: Mapping[Path, tuple[bytes, dict[str, Any]]],
) -> None:
    from scripts import build_walksafe_fp048_gap_backlog_r023_20260802 as r023_builder

    try:
        require(
            r023_builder.R023_GAP_JSON_REL == R023_GAP_REL
            and r023_builder.R023_BACKLOG_JSON_REL == R023_BACKLOG_REL,
            "r023 validator output paths differ",
        )
        expected = r023_builder.build_outputs(root)
        require(expected[R023_GAP_REL].encode() == snapshots[R023_GAP_REL][0], "r023 Gap bytes differ from production validator")
        require(expected[R023_BACKLOG_REL].encode() == snapshots[R023_BACKLOG_REL][0], "r023 Backlog bytes differ from production validator")
    except r023_builder.BuildError as exc:
        raise BuildError(f"r023 validation failed: {exc}") from exc


def _run_full_artifact_successor_validation(
    root: Path,
    snapshots: Mapping[Path, tuple[bytes, dict[str, Any]]],
    source_pins: Mapping[Path, str],
) -> None:
    from scripts import build_walksafe_fp048_artifact_trace_successor_20260802 as artifact_builder

    try:
        expected_paths = {relative for _, relative, _ in FP048_ARTIFACT_CONSUMERS}
        require(artifact_builder.SUCCESSOR_ID == FP048_ARTIFACT_SUCCESSOR_ID, "artifact validator successor ID differs")
        require(set(artifact_builder.OUTPUT_PATHS) == expected_paths, "artifact validator output path set differs")
        require(set(artifact_builder.INPUT_PATHS) == set(source_pins), "artifact validator input path set differs")
        ids = {
            IMPLEMENTATION_REL: "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-IMPLEMENTATION-20260802-001",
            VERIFICATION_REL: "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-VERIFICATION-20260802-001",
        }
        inputs = artifact_builder.validate_inputs(root, dict(source_pins), ids)
        values = {relative: deepcopy(snapshots[relative][1]) for relative in artifact_builder.OUTPUT_PATHS}
        actual = {relative: snapshots[relative][0] for relative in artifact_builder.OUTPUT_PATHS}
        for relative in artifact_builder.OUTPUT_PATHS:
            require(actual[relative] == artifact_builder.json_bytes(values[relative]), f"noncanonical artifact successor JSON: {relative}")
        predecessors = artifact_builder._recover_predecessors(values)
        expected = artifact_builder._build_from_predecessors(root, predecessors, inputs)
        artifact_builder._validate_built_outputs(root, predecessors, inputs, expected)
        require(actual == expected, "artifact successor bytes differ from production validator")
    except artifact_builder.BuildError as exc:
        raise BuildError(f"artifact successor validation failed: {exc}") from exc


def _r012_bytes_binding(relative: Path, content: bytes, binding_id: str, subject_role: str) -> dict[str, Any]:
    return {
        "binding_id": binding_id,
        "path": relative.as_posix(),
        "byte_length": len(content),
        "sha256": bytes_sha256(content),
        "subject_role": subject_role,
    }


def _validate_r012_snapshot_contract(
    snapshots: Mapping[Path, tuple[bytes, dict[str, Any]]],
    sources: Mapping[Path, tuple[bytes, str]],
) -> None:
    ledger_rel = R012_DIR_REL / "phase1-exact257-successor-ledger-r012.json"
    evidence_rel = R012_DIR_REL / "evidence.json"
    receipt_rel = R012_DIR_REL / "phase1-exact257-successor-check-receipt-r012.json"
    ledger_raw, ledger = snapshots[ledger_rel]
    evidence_raw, evidence = snapshots[evidence_rel]
    _receipt_raw, receipt = snapshots[receipt_rel]
    application = ledger.get("r012_fp048_artifact_progress_application")
    require(isinstance(application, dict), "r012 progress application missing")
    require(
        application.get("record_count") == 257
        and application.get("unchanged_record_count") == 251
        and application.get("progress_binding_record_count") == 6
        and application.get("forbidden_new_source_count") == 0,
        "r012 ledger 257/251/6 contract differs",
    )
    row_delta = evidence.get("row_delta")
    require(
        isinstance(row_delta, dict)
        and row_delta.get("record_count") == 257
        and row_delta.get("unchanged_record_count") == 251
        and row_delta.get("progress_binding_record_count") == 6
        and row_delta.get("other_record_delta_count") == 0,
        "r012 evidence 257/251/6 contract differs",
    )
    summary = receipt.get("summary")
    require(
        isinstance(summary, dict)
        and summary.get("record_count") == 257
        and summary.get("unchanged_record_count") == 251
        and summary.get("progress_binding_record_count") == 6,
        "r012 receipt 257/251/6 contract differs",
    )
    require(application.get("zero_credits") == R012_ZERO_CREDITS, "r012 ledger zero-credit boundary differs")
    require(evidence.get("preserved_invariants", {}).get("zero_credits") == R012_ZERO_CREDITS, "r012 evidence zero-credit boundary differs")
    require(summary.get("zero_credits") == R012_ZERO_CREDITS, "r012 receipt zero-credit boundary differs")
    require(receipt.get("status") == "PASS", "r012 receipt did not pass")

    source_rows = ledger.get("r012_source_bindings")
    require(source_rows == evidence.get("source_bindings") == receipt.get("source_bindings"), "r012 source bindings differ across outputs")
    require(isinstance(source_rows, list) and len(source_rows) == 9, "r012 source binding count differs")
    expected_source_paths = list(sources)
    require(
        [row.get("binding_id") for row in source_rows if isinstance(row, dict)]
        == [f"R012-SRC-{index:03d}" for index in range(1, 10)],
        "r012 source binding IDs differ",
    )
    require(
        [row.get("path") for row in source_rows if isinstance(row, dict)]
        == [relative.as_posix() for relative in expected_source_paths],
        "r012 source binding path order differs",
    )
    for row, relative in zip(source_rows, expected_source_paths, strict=True):
        content, digest = sources[relative]
        require(row.get("sha256") == digest and row.get("byte_length") == len(content), f"r012 source binding differs: {relative}")

    ledger_binding = _r012_bytes_binding(ledger_rel, ledger_raw, "R012-OUT-001", "R012_FULL_EXACT257_LEDGER")
    evidence_binding = _r012_bytes_binding(evidence_rel, evidence_raw, "R012-OUT-002", "R012_FP048_EXACT6_PROGRESS_EVIDENCE")
    require(evidence.get("subject_chain", {}).get("r012_ledger") == ledger_binding, "r012 evidence ledger binding differs")
    require(receipt.get("output_bindings") == [ledger_binding, evidence_binding], "r012 receipt output bindings differ")


def _run_full_r012_validation(
    root: Path,
    snapshots: Mapping[Path, tuple[bytes, dict[str, Any]]],
    source_pins: Mapping[Path, str],
) -> None:
    from scripts import build_walksafe_phase1_exact257_successor_r012_20260802 as r012_builder

    try:
        require(r012_builder.R012_PACKET_DIR_REL == R012_DIR_REL, "r012 validator packet path differs")
        require(set(r012_builder.PINNED_SOURCE_SHA256_BY_RELATIVE_PATH) == set(source_pins), "r012 validator source path set differs")
        source_state = r012_builder._load_source_state(root, source_pins)
        outputs = {
            root / r012_builder.R012_LEDGER_REL: snapshots[r012_builder.R012_LEDGER_REL][0],
            root / r012_builder.R012_EVIDENCE_REL: snapshots[r012_builder.R012_EVIDENCE_REL][0],
            root / r012_builder.R012_RECEIPT_REL: snapshots[r012_builder.R012_RECEIPT_REL][0],
        }
        r012_builder._validate_generated_outputs(outputs, source_state)
    except r012_builder.ValidationError as exc:
        raise BuildError(f"r012 validation failed: {exc}") from exc


def validate_consumer_bindings(root: Path, implementation_sha256: str, verification_sha256: str, *, required: bool = True) -> list[dict[str, Any]]:
    root = root.resolve()
    require(len(CONSUMER_CONTRACTS) == 11 and len({relative for _, relative, _ in CONSUMER_CONTRACTS}) == 11, "consumer contract set differs")
    exists = [os.path.lexists(root / relative) for _, relative, _ in CONSUMER_CONTRACTS]
    if not any(exists):
        require(not required, "downstream consumers absent")
        return []
    require(all(exists), "downstream consumer set is partial")
    snapshots = {
        relative: snapshot_json(root, relative, require_single_link=True)
        for _, relative, _ in CONSUMER_CONTRACTS
    }
    implementation = snapshot_json(root, IMPLEMENTATION_REL, require_single_link=True)
    verification = snapshot_json(root, VERIFICATION_REL, require_single_link=True)
    require(bytes_sha256(implementation[0]) == implementation_sha256, "implementation producer hash differs")
    require(bytes_sha256(verification[0]) == verification_sha256, "verification producer hash differs")
    gap_raw, gap = snapshots[R023_GAP_REL]
    backlog_raw, backlog = snapshots[R023_BACKLOG_REL]
    require(gap.get("schema_version") == CONSUMER_CONTRACTS[0][2], "r023 Gap schema differs")
    require(gap.get("metadata", {}).get("report_id") == EXPECTED_R023_REPORT_ID and gap.get("metadata", {}).get("version") == "0.23.0", "r023 Gap identity differs")
    gap_content_sha256 = _verify_json_seal(gap, "report_content_sha256", "r023 Gap")
    require(object_sha256(gap.get("source_bindings")) == gap.get("source_binding_sha256"), "r023 source binding seal differs")
    evidence_rows = [item for item in gap.get("evidence_catalog", []) if isinstance(item, dict) and item.get("evidence_id") == "EVD-FP048-INTERNAL-ENCRYPTION-SECURITY-20260802"]
    require(len(evidence_rows) == 1, "r023 evidence is not exact-one")
    producer_hashes = {"IMPLEMENTATION_RECORD": implementation_sha256, "VERIFICATION_RESULT": verification_sha256}
    require(evidence_rows[0].get("result_evidence_sha256") == producer_hashes, "r023 evidence producer binding differs")
    producer_sources = {
        IMPLEMENTATION_REL: (implementation[0], implementation_sha256),
        VERIFICATION_REL: (verification[0], verification_sha256),
        R023_GAP_REL: (gap_raw, bytes_sha256(gap_raw)),
    }
    gap_bindings = gap.get("source_bindings")
    require(isinstance(gap_bindings, list), "r023 source bindings malformed")
    for relative in (IMPLEMENTATION_REL, VERIFICATION_REL):
        matches = [row for row in gap_bindings if isinstance(row, dict) and row.get("path") == relative.as_posix()]
        require(len(matches) == 1, f"r023 producer source binding count differs: {relative}")
        require(matches[0].get("sha256") == producer_sources[relative][1] and matches[0].get("bytes") == len(producer_sources[relative][0]), f"r023 producer source binding differs: {relative}")

    require(backlog.get("schema_version") == CONSUMER_CONTRACTS[1][2], "r023 Backlog schema differs")
    require(backlog.get("metadata", {}).get("backlog_id") == EXPECTED_R023_BACKLOG_ID and backlog.get("metadata", {}).get("version") == "0.23.0", "r023 Backlog identity differs")
    _verify_json_seal(backlog, "backlog_content_sha256", "r023 Backlog")
    require(backlog.get("gap_report_content_sha256") == gap_content_sha256, "r023 Gap/Backlog binding differs")
    next_action = backlog.get("next_single_action")
    require(
        isinstance(next_action, dict)
        and set(next_action) == {"epic_id", "source_policy_id", "gap_id", "status", "action"}
        and next_action.get("epic_id") == "EPIC-03"
        and next_action.get("source_policy_id") == "FP-008"
        and next_action.get("gap_id") == "GAP-017"
        and next_action.get("status") == "PLANNED_NEXT"
        and isinstance(next_action.get("action"), str)
        and bool(next_action["action"]),
        "r023 FP008 PLANNED_NEXT action differs",
    )
    _run_full_r023_validation(
        root,
        {R023_GAP_REL: snapshots[R023_GAP_REL], R023_BACKLOG_REL: snapshots[R023_BACKLOG_REL]},
    )

    artifact_snapshots = {relative: snapshots[relative] for _, relative, _ in FP048_ARTIFACT_CONSUMERS}
    for role, relative, schema in FP048_ARTIFACT_CONSUMERS:
        value = snapshots[relative][1]
        require(value.get("schema_version") == schema, f"{role} schema differs")
        _validate_exact_source_bindings(value, label=role, expected=producer_sources)
    _run_full_artifact_successor_validation(
        root,
        artifact_snapshots,
        {IMPLEMENTATION_REL: implementation_sha256, VERIFICATION_REL: verification_sha256, R023_GAP_REL: bytes_sha256(gap_raw)},
    )

    r012_snapshots = {relative: snapshots[relative] for role, relative, _ in CONSUMER_CONTRACTS if role.startswith("EXACT257_R012_")}
    r012_sources = {
        IMPLEMENTATION_REL: producer_sources[IMPLEMENTATION_REL],
        VERIFICATION_REL: producer_sources[VERIFICATION_REL],
        R023_GAP_REL: producer_sources[R023_GAP_REL],
        **{relative: (snapshots[relative][0], bytes_sha256(snapshots[relative][0])) for _, relative, _ in FP048_ARTIFACT_CONSUMERS},
    }
    _validate_r012_snapshot_contract(r012_snapshots, r012_sources)
    _run_full_r012_validation(root, r012_snapshots, {relative: digest for relative, (_content, digest) in r012_sources.items()})

    bindings = []
    for role, relative, schema in CONSUMER_CONTRACTS:
        content, value = snapshots[relative]
        require(value.get("schema_version") == schema, f"{role} schema differs")
        bindings.append({"role": role, "path": relative.as_posix(), "schema_version": schema, "sha256": bytes_sha256(content)})
    return bindings

def _assert_disk_outputs(root: Path, outputs: Mapping[Path, str]) -> None:
    for relative, content in outputs.items():
        require(
            snapshot_file(
                root,
                relative,
                require_single_link=True,
                required_mode=0o600,
                required_uid=os.getuid(),
            )
            == content.encode(),
            f"output differs: {relative}",
        )

def build_post_review_outputs(*, root: Path = ROOT, pre_review_kwargs: Mapping[str, Any] | None = None) -> dict[Path, str]:
    root = root.resolve()
    pre = build_pre_review_outputs(root=root, **dict(pre_review_kwargs or {}))
    _assert_disk_outputs(root, pre)
    hashes = {
        "IMPLEMENTATION_RECORD": bytes_sha256(pre[IMPLEMENTATION_REL].encode()),
        "VERIFICATION_RESULT": bytes_sha256(pre[VERIFICATION_REL].encode()),
        "SUCCESSOR_TRACE": bytes_sha256(pre[SUCCESSOR_REL].encode()),
    }
    consumers = validate_consumer_bindings(root, hashes["IMPLEMENTATION_RECORD"], hashes["VERIFICATION_RESULT"])
    canonical_consumer_roles = {
        "ARTIFACT_CHANGE_LOG",
        "ARTIFACT_REGISTER",
        "DESIGN_TRACEABILITY",
        "MODULE_REGISTER",
        "REQUIREMENTS_TRACEABILITY",
        "GAP_R023",
        "BACKLOG_R023",
    }
    require(
        {row["role"] for row in consumers if row["role"] in canonical_consumer_roles}
        == canonical_consumer_roles,
        "canonical completion consumer hash set differs",
    )
    attestation_bytes = snapshot_file(
        root,
        REVIEW_ATTESTATION_REL,
        require_single_link=True,
        required_mode=0o600,
        required_uid=os.getuid(),
    )
    attestation = json_from_bytes(attestation_bytes, "review attestation")
    require(attestation.get("schema_version") == "1.0" and attestation.get("evidence_type") == "INTERNAL_REVIEW_ATTESTATION" and attestation.get("goal_id") == GOAL_ID, "attestation identity differs")
    require(attestation.get("review_subject_sha256") == bytes_sha256(pre[REVIEW_SUBJECT_REL].encode()) and attestation.get("reviewed_result_sha256_by_kind") == hashes and attestation.get("reviewed_consumer_bindings") == consumers, "attestation binding differs")
    require(attestation.get("decision") == "APPROVED" and attestation.get("findings", {}).get("blocking") == 0 and attestation.get("findings", {}).get("major_open") == 0, "attestation is not blocker-free approval")
    reviewer_id, reviewer_task = attestation.get("reviewer_id"), attestation.get("reviewer_task")
    require(reviewer_id == EXPECTED_REVIEWER_ID and reviewer_id != EXECUTOR_ID, "reviewer identity differs")
    require(reviewer_task == EXPECTED_REVIEWER_TASK and reviewer_task != EXECUTOR_TASK, "reviewer task differs")
    implementation = json_from_bytes(pre[IMPLEMENTATION_REL].encode(), "implementation")
    verification = json_from_bytes(pre[VERIFICATION_REL].encode(), "verification")
    authority_event = implementation.get("execution_session_event")
    require(isinstance(authority_event, dict), "implementation execution session event missing")
    reviewed_at = attestation.get("reviewed_at")
    require(parse_time(reviewed_at, "review time") >= parse_time(verification["observed_at"], "latest log"), "review predates logs")
    require(attestation.get("review_boundary") == expected_review_boundary(), "review boundary differs")
    review = {
        "schema_version": "1.0", "document_id": "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-INTERNAL-REVIEW-20260802-001",
        "evidence_type": "INDEPENDENT_INTERNAL_REVIEW", "goal_id": GOAL_ID, "status": "PASS",
        "reviewer_id": reviewer_id, "reviewer_task": reviewer_task, "reviewed_at": reviewed_at,
        "review_subject_sha256": bytes_sha256(pre[REVIEW_SUBJECT_REL].encode()),
        "reviewed_result_sha256_by_kind": hashes, "reviewed_consumer_bindings": consumers,
        "attestation_provenance": {"path": REVIEW_ATTESTATION_REL.as_posix(), "sha256": bytes_sha256(attestation_bytes)},
        "findings": deepcopy(attestation["findings"]), "review_boundary": expected_review_boundary(),
    }
    review_text = json_text(review)
    source = {**pre, INDEPENDENT_REVIEW_REL: review_text}
    manifest = [{"path": relative.as_posix(), "sha256": bytes_sha256(source[relative].encode())} for relative in (*PRE_REVIEW_OUTPUTS, INDEPENDENT_REVIEW_REL)]
    receipt = {
        "schema_version": "1.0", "document_id": "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-WORK-ITEM-COMPLETION-20260802-001",
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT", "target_goal_id": GOAL_ID, "status": "ACCEPTED", "result": "PASS",
        "target_goal_content_sha256": EXPECTED_GOAL_SHA256,
        "work_item_id": EXPECTED_WORK_ITEM_ID, "source_policy_ids": ["FP-048"], "gap_ids": ["GAP-057"],
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "execution_start_event_sha256": EXPECTED_EXECUTION_EVENT_SHA256,
        "execution_session_event": {
            "sequence": authority_event["sequence"], "event_id": authority_event["event_id"],
            "event_type": authority_event["event_type"], "event_sha256": authority_event["event_sha256"],
        },
        "implementation_start_gate_binding": deepcopy(implementation["implementation_start_gate_binding"]),
        "execution_window": {"started_at": implementation["observed_at"], "ended_at": verification["observed_at"]},
        "completed_at": reviewed_at,
        "executor": {"id": EXECUTOR_ID, "task": EXECUTOR_TASK, "role": "INTERNAL_IMPLEMENTATION_EXECUTOR", "authority": "GRAPH_V2_4_STANDING_EXECUTION_AUTHORITY"},
        "reviewer": {
            "id": reviewer_id, "task": reviewer_task, "role": "SEPARATE_INTERNAL_REVIEWER",
            "separate_internal_review_pass": True, "external_independence_claimed": False,
            "authority": "INTERNAL_REPOSITORY_CONTROL", "decision": "APPROVED", "decided_at": reviewed_at,
        },
        "reviewer_provenance": {"path": INDEPENDENT_REVIEW_REL.as_posix(), "sha256": bytes_sha256(review_text.encode())},
        "result_evidence": [
            {"kind": kind, "path": relative.as_posix(), "sha256": bytes_sha256(pre[relative].encode())}
            for kind, relative in (
                ("IMPLEMENTATION_RECORD", IMPLEMENTATION_REL),
                ("VERIFICATION_RESULT", VERIFICATION_REL),
                ("SUCCESSOR_TRACE", SUCCESSOR_REL),
            )
        ],
        "downstream_consumer_bindings": consumers, "output_evidence_manifest": manifest,
        "output_evidence_manifest_sha256": object_sha256(manifest), "self_excluded_from_output_manifest": True,
        "completion_boundary": completion_boundary(), "generated_at": reviewed_at,
    }
    return {INDEPENDENT_REVIEW_REL: review_text, COMPLETION_RECEIPT_REL: json_text(receipt)}

def _write_exclusive_file(path: Path, content: bytes) -> None:
    descriptor = os.open(
        path,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
        0o600,
    )
    try:
        position = 0
        while position < len(content):
            written = os.write(descriptor, content[position:])
            require(written > 0, f"short write: {path}")
            position += written
        os.fsync(descriptor)
        _validate_regular_authority(
            os.fstat(descriptor),
            str(path),
            require_single_link=True,
            required_mode=0o600,
            required_uid=os.getuid(),
        )
    finally:
        os.close(descriptor)


def _forward_transaction_manifest(outputs: Mapping[Path, str], group: str) -> tuple[dict[str, Any], bytes]:
    rows = [
        {
            "index": index,
            "path": relative.as_posix(),
            "stage_name": f"{index:02d}.stage",
            "byte_count": len(content.encode()),
            "sha256": bytes_sha256(content.encode()),
        }
        for index, (relative, content) in enumerate(outputs.items())
    ]
    value = {
        "schema_version": "walksafe.fp048-forward-output-transaction.v1",
        "goal_id": GOAL_ID,
        "group": group,
        "policy": "ADD_ONLY_FORWARD_RECOVERY",
        "outputs": rows,
    }
    value["manifest_content_sha256"] = object_sha256(value)
    return value, json_text(value).encode()


def _cleanup_forward_transaction(transaction: Path, manifest: dict[str, Any], manifest_bytes: bytes) -> None:
    for row in manifest["outputs"]:
        staged = transaction / row["stage_name"]
        if not os.path.lexists(staged):
            continue
        raw = _read_regular_path(staged, str(staged), require_single_link=True, required_mode=0o600, required_uid=os.getuid())
        require(bytes_sha256(raw) == row["sha256"] and len(raw) == row["byte_count"], f"staged output drift: {staged}")
        staged.unlink()
    manifest_path = transaction / "manifest.json"
    if os.path.lexists(manifest_path):
        require(_read_regular_path(manifest_path, str(manifest_path), require_single_link=True, required_mode=0o600, required_uid=os.getuid()) == manifest_bytes, "transaction manifest drift")
        manifest_path.unlink()
    require(not any(transaction.iterdir()), "transaction directory contains foreign entries")
    transaction.rmdir()
    _fsync_directory(transaction.parent)


def write_or_check_outputs(
    root: Path,
    outputs: Mapping[Path, str],
    *,
    write: bool,
    publish_hook: Callable[[int, Path], None] | None = None,
) -> None:
    root = root.resolve()
    require(tuple(outputs) in (PRE_REVIEW_OUTPUTS, POST_REVIEW_OUTPUTS), "output set differs")
    if not write:
        _assert_disk_outputs(root, outputs)
        return
    group = "pre-review" if tuple(outputs) == PRE_REVIEW_OUTPUTS else "post-review"
    manifest, manifest_bytes = _forward_transaction_manifest(outputs, group)
    transaction_relative = RESULT_DIR_REL / f".{group}-transaction"
    _ensure_parent_directories(root, transaction_relative / "placeholder")
    transaction = root / transaction_relative
    if os.path.lexists(transaction):
        info = transaction.lstat()
        require(stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode), "transaction path is unsafe")
        require(info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700, "transaction authority differs")
    else:
        os.mkdir(transaction, 0o700)
        _fsync_directory(transaction.parent)
    manifest_path = transaction / "manifest.json"
    if os.path.lexists(manifest_path):
        require(_read_regular_path(manifest_path, str(manifest_path), require_single_link=True, required_mode=0o600, required_uid=os.getuid()) == manifest_bytes, "transaction manifest differs")
    else:
        _write_exclusive_file(manifest_path, manifest_bytes)
        _fsync_directory(transaction)

    expected_entries = {"manifest.json"} | {row["stage_name"] for row in manifest["outputs"]}
    require({path.name for path in transaction.iterdir()} <= expected_entries, "transaction directory contains foreign entries")
    for row, (relative, content) in zip(manifest["outputs"], outputs.items(), strict=True):
        raw = content.encode()
        require(row["path"] == relative.as_posix() and row["sha256"] == bytes_sha256(raw), "transaction row differs")
        _ensure_parent_directories(root, relative)
        destination = root / relative
        staged = transaction / row["stage_name"]
        if os.path.lexists(destination):
            require(_read_regular_path(destination, relative.as_posix(), require_single_link=True, required_mode=0o600, required_uid=os.getuid()) == raw, f"existing output differs: {relative}")
            continue
        if os.path.lexists(staged):
            require(_read_regular_path(staged, str(staged), require_single_link=True, required_mode=0o600, required_uid=os.getuid()) == raw, f"staged output differs: {relative}")
        else:
            _write_exclusive_file(staged, raw)
            _fsync_directory(transaction)

    for index, (relative, content) in enumerate(outputs.items()):
        destination = root / relative
        if not os.path.lexists(destination):
            staged = transaction / manifest["outputs"][index]["stage_name"]
            _rename_noreplace(staged, destination)
            _fsync_directory(destination.parent)
        require(_read_regular_path(destination, relative.as_posix(), require_single_link=True, required_mode=0o600, required_uid=os.getuid()) == content.encode(), f"published output differs: {relative}")
        if publish_hook is not None:
            publish_hook(index, relative)
    _assert_disk_outputs(root, outputs)
    _cleanup_forward_transaction(transaction, manifest, manifest_bytes)

PYTEST_SUMMARY_RE = re.compile(
    r"^(?:=+\s*)?([0-9]+) passed(?:, ([0-9]+) skipped)?(?:, ([0-9]+) deselected)? "
    r"in [0-9]+(?:\.[0-9]+)?s(?:\s*=+)?$",
    flags=re.MULTILINE,
)
PYTEST_TERMINAL_CANDIDATE_RE = re.compile(
    r"^(?:=+\s*)?[0-9]+ (?:passed|failed|errors?|xfailed|xpassed|skipped)\b.* "
    r"in [0-9]+(?:\.[0-9]+)?s(?:\s*=+)?$",
    flags=re.IGNORECASE | re.MULTILINE,
)
ANDROID_JUNIT_XML_RE = re.compile(
    r"^ANDROID_JUNIT_XML_BYTES path=([^ \r\n]+) bytes=([0-9]+) "
    r"sha256=([0-9a-f]{64}) base64=([A-Za-z0-9+/]*={0,2})$",
    flags=re.MULTILINE,
)
GRADLE_TERMINAL_CANDIDATE_RE = re.compile(r"^BUILD [^\r\n]+$", flags=re.MULTILINE)
GRADLE_SUCCESS_RE = re.compile(r"^BUILD SUCCESSFUL(?:\s+in\s+.*)?$", flags=re.MULTILINE)
GRADLE_UP_TO_DATE_NOOP_TASKS = frozenset(
    {
        ":app:preBuild",
        ":app:preDebugBuild",
        ":app:preDebugUnitTestBuild",
        ":app:generateDebugAssets",
    }
)
TAP_PLAN_RE = re.compile(r"^\s*1\.\.([0-9]+)\s*$", flags=re.MULTILINE)
TAP_SUMMARY_FIELDS = ("tests", "suites", "pass", "fail", "cancelled", "skipped", "todo")

def pytest_summaries(output: str) -> list[tuple[int, int, int]]:
    return [(int(passed), int(skipped or 0), int(deselected or 0)) for passed, skipped, deselected in PYTEST_SUMMARY_RE.findall(output)]


def _reject_raw_failure_evidence(spec: LaneSpec, output: str) -> None:
    forbidden_patterns = (
        r"^BUILD FAILED(?:\s|$)",
        r"^FAILURE:\s+Build failed",
        r"^> Task\s+.*\sFAILED(?:\s|$)",
        r"^(?:=+\s*)?.*\b(?:failed|errors?|xfailed|xpassed)\b.*\bin [0-9]+(?:\.[0-9]+)?s(?:\s*=+)?$",
        r"^FAILED(?:\s|$)",
        r"^ERRORS?(?:\s|$)",
        r"^_+\s+ERROR collecting\b",
        r"^collected\b.*\b[1-9][0-9]* errors?\b",
        r"^\s*not ok(?:\s|$)",
        r"^\s*# fail [1-9][0-9]*\s*$",
    )
    for pattern in forbidden_patterns:
        require(
            re.search(pattern, output, flags=re.IGNORECASE | re.MULTILINE) is None,
            f"{spec.lane_id} raw output contains failure evidence",
        )
    require(
        re.search(r"\b(?:FROM-CACHE|FROM CACHE)\b", output, flags=re.IGNORECASE) is None,
        f"{spec.lane_id} contains stale or cached Gradle evidence",
    )
    for task in re.findall(r"^> Task (\S+) UP-TO-DATE$", output, flags=re.MULTILINE):
        require(
            task in GRADLE_UP_TO_DATE_NOOP_TASKS,
            f"{spec.lane_id} contains stale or cached Gradle evidence for {task}",
        )


def _require_fresh_gradle_tasks(output: str, tasks: tuple[str, ...], label: str) -> None:
    for task in tasks:
        lines = re.findall(
            rf"^> Task {re.escape(task)}(?:[ \t]+[^\r\n]+)?$",
            output,
            flags=re.MULTILINE,
        )
        require(
            lines == [f"> Task {task}"],
            f"{label} contains stale or cached Gradle evidence for {task}",
        )


def _exact_pytest_summaries(
    output: str,
    expected: list[tuple[int, int, int]],
    label: str,
) -> None:
    summaries = pytest_summaries(output)
    candidates = PYTEST_TERMINAL_CANDIDATE_RE.findall(output)
    require(summaries == expected, f"{label} pytest terminal summaries differ")
    require(len(candidates) == len(summaries), f"{label} non-success pytest terminal summary present")


def _exact_gradle_summaries(output: str, expected_count: int, label: str) -> None:
    candidates = GRADLE_TERMINAL_CANDIDATE_RE.findall(output)
    successes = GRADLE_SUCCESS_RE.findall(output)
    require(len(successes) == expected_count, f"{label} Gradle terminal summaries differ")
    require(len(candidates) == len(successes), f"{label} non-success Gradle terminal summary present")


def _exact_tap_summary(output: str, *, expected: bool, label: str) -> None:
    plans = [int(value) for value in TAP_PLAN_RE.findall(output)]
    observed: dict[str, list[int]] = {}
    for name in TAP_SUMMARY_FIELDS:
        observed[name] = [
            int(value)
            for value in re.findall(rf"^\s*# {re.escape(name)} ([0-9]+)\s*$", output, flags=re.MULTILINE)
        ]
    candidate_count = len(plans) + sum(len(values) for values in observed.values())
    if not expected:
        require(candidate_count == 0, f"{label} unexpected TAP terminal summary present")
        return
    require(plans == [77], f"{label} TAP plan differs")
    expected_values = {"tests": 77, "suites": 0, "pass": 77, "fail": 0, "cancelled": 0, "skipped": 0, "todo": 0}
    require(observed == {name: [value] for name, value in expected_values.items()}, f"{label} TAP terminal summary differs")


def emit_android_junit_xml(root: Path) -> None:
    for relative in EXPECTED_ANDROID_JUNIT_XML_RELATIVES:
        raw = snapshot_file(root, relative, require_single_link=True)
        print(
            f"ANDROID_JUNIT_XML_BYTES path={relative.as_posix()} bytes={len(raw)} "
            f"sha256={bytes_sha256(raw)} base64={base64.b64encode(raw).decode('ascii')}"
        )


def android_junit_summary(output: str) -> dict[str, int]:
    require("ANDROID_JUNIT_XML_SUMMARY" not in output, "legacy Android JUnit summary is forbidden")
    matches = ANDROID_JUNIT_XML_RE.findall(output)
    prefix_lines = re.findall(r"^ANDROID_JUNIT_XML_BYTES[^\r\n]*$", output, flags=re.MULTILINE)
    require(len(prefix_lines) == len(matches), "malformed Android JUnit XML record present")
    require(matches, "Android JUnit XML bytes missing from raw command output")
    paths = [match[0] for match in matches]
    require(paths == sorted(paths) and len(paths) == len(set(paths)), "Android JUnit XML path set differs")
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    expected_paths = [relative.as_posix() for relative in EXPECTED_ANDROID_JUNIT_XML_RELATIVES]
    require(paths == expected_paths, "Android JUnit exact XML path set differs")
    for path_text, byte_count, claimed_sha256, encoded in matches:
        relative = Path(path_text)
        require(
            not relative.is_absolute()
            and relative.suffix == ".xml"
            and "." not in relative.parts
            and ".." not in relative.parts,
            f"unsafe Android JUnit XML path: {path_text}",
        )
        try:
            raw = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise BuildError(f"Android JUnit base64 differs: {path_text}") from exc
        require(base64.b64encode(raw).decode("ascii") == encoded, f"Android JUnit base64 is noncanonical: {path_text}")
        require(len(raw) == int(byte_count), f"Android JUnit byte count differs: {path_text}")
        require(bytes_sha256(raw) == claimed_sha256, f"Android JUnit SHA-256 differs: {path_text}")
        require(b"<!DOCTYPE" not in raw.upper(), f"Android JUnit DTD is forbidden: {path_text}")
        try:
            suite = ElementTree.fromstring(raw)
        except ElementTree.ParseError as exc:
            raise BuildError(f"Android JUnit invalid: {path_text}") from exc
        require(suite.tag.rsplit("}", 1)[-1] == "testsuite", f"Android JUnit root differs: {path_text}")
        class_name = path_text.removeprefix(f"{ANDROID_JUNIT_RESULT_DIR_REL.as_posix()}/TEST-").removesuffix(".xml")
        require(suite.get("name") == class_name, f"Android JUnit suite identity differs: {path_text}")
        declared: dict[str, int] = {}
        for key in totals:
            attribute = suite.get(key)
            require(isinstance(attribute, str) and re.fullmatch(r"(?:0|[1-9][0-9]*)", attribute), f"Android JUnit {key} differs")
            declared[key] = int(attribute)
            totals[key] += declared[key]
        testcases = [node for node in suite.iter() if node.tag.rsplit("}", 1)[-1] == "testcase"]
        require(
            all(testcase.get("classname") == class_name and isinstance(testcase.get("name"), str) and testcase.get("name") for testcase in testcases),
            f"Android JUnit testcase identity differs: {path_text}",
        )
        require(
            len({(testcase.get("classname"), testcase.get("name")) for testcase in testcases}) == len(testcases),
            f"Android JUnit testcase identity is duplicated: {path_text}",
        )
        observed = {
            name: sum(
                1
                for testcase in testcases
                for child in testcase
                if child.tag.rsplit("}", 1)[-1] == name
            )
            for name in ("failure", "error", "skipped")
        }
        require(len(testcases) == declared["tests"], f"Android JUnit testcase count differs: {path_text}")
        require(observed["failure"] == declared["failures"], f"Android JUnit failure nodes differ: {path_text}")
        require(observed["error"] == declared["errors"], f"Android JUnit error nodes differ: {path_text}")
        require(observed["skipped"] == declared["skipped"], f"Android JUnit skipped nodes differ: {path_text}")
    return {"suites": len(matches), **totals}


def observed_lane_markers(spec: LaneSpec, output: str, root: Path) -> dict[str, str]:
    _ = root
    _reject_raw_failure_evidence(spec, output)
    expected_gradle = 2 if spec.lane_id == "ANDROID_PROTECTED_STORAGE" else 1 if spec.lane_id == "HTTPS_NO_DOWNGRADE" else 0
    _exact_gradle_summaries(output, expected_gradle, spec.lane_id)
    _exact_tap_summary(output, expected=spec.lane_id == "HTTPS_NO_DOWNGRADE", label=spec.lane_id)
    if spec.lane_id == "ANDROID_PROTECTED_STORAGE":
        _require_fresh_gradle_tasks(
            output,
            (
                ":app:testDebugUnitTest",
                ":app:compileDebugAndroidTestKotlin",
                ":app:assembleDebug",
                ":app:lintDebug",
            ),
            spec.lane_id,
        )
        junit = android_junit_summary(output)
        require(junit == {"suites": 19, "tests": 147, "failures": 0, "errors": 0, "skipped": 0}, "Android JUnit count differs")
        _exact_pytest_summaries(output, [(303, 0, 0)], spec.lane_id)
        values = {"WALKSAFE_ANDROID_JUNIT_SUITES": str(junit["suites"]), "WALKSAFE_ANDROID_JUNIT_TESTS": str(junit["tests"]), "WALKSAFE_ANDROID_JUNIT_FAILURES": "0", "WALKSAFE_ANDROID_JUNIT_ERRORS": "0", "WALKSAFE_ANDROID_JUNIT_SKIPPED": "0", "WALKSAFE_ANDROID_HOST_PYTEST_PASSED": "303", "WALKSAFE_ANDROID_BUILD_STATUS": "PASS"}
    elif spec.lane_id == "HTTPS_NO_DOWNGRADE":
        _require_fresh_gradle_tasks(
            output,
            (":app:testDebugUnitTest",),
            spec.lane_id,
        )
        _exact_pytest_summaries(output, [], spec.lane_id)
        tap = {}
        for name in ("tests", "pass", "fail"):
            matches = re.findall(rf"^# {name} ([0-9]+)$", output, flags=re.MULTILINE)
            require(len(matches) == 1, f"Gateway {name} differs")
            tap[name] = int(matches[0])
        require(tap == {"tests": 77, "pass": 77, "fail": 0}, "Gateway summary differs")
        values = {"WALKSAFE_ANDROID_HTTPS_STATIC_STATUS": "PASS", "WALKSAFE_GATEWAY_TESTS": "77", "WALKSAFE_GATEWAY_PASS": "77", "WALKSAFE_GATEWAY_FAIL": "0", "WALKSAFE_GATEWAY_TYPECHECK_STATUS": "PASS"}
    elif spec.lane_id == "SERVER_ORIGINAL_DB_BACKUP_BOUNDARY":
        _exact_pytest_summaries(output, [(441, 11, 0), (38, 0, 0), (64, 0, 0)], spec.lane_id)
        values = {"WALKSAFE_BACKEND_INTERNAL_PASSED": "441", "WALKSAFE_BACKEND_INTERNAL_SKIPPED": "11", "WALKSAFE_RETENTION_INTERNAL_PASSED": "38", "WALKSAFE_BACKUP_INTERNAL_PASSED": "64", "WALKSAFE_DATABASE_INTEGRATION_STATUS": "NOT_RUN", "WALKSAFE_BACKUP_RESTORE_STATUS": "NOT_RUN"}
    elif spec.lane_id == "KEY_LIFECYCLE_ORIGINAL_ACCESS":
        _exact_pytest_summaries(output, [(254, 11, 0)], spec.lane_id)
        values = {"WALKSAFE_BACKEND_FOCUSED_PASSED": "254", "WALKSAFE_BACKEND_FOCUSED_SKIPPED": "11", "WALKSAFE_EXTERNAL_KMS_STATUS": "NOT_RUN"}
    else:
        _exact_pytest_summaries(output, [(64, 0, 0)], spec.lane_id)
        values = {"WALKSAFE_INCIDENT_INTERNAL_STATUS": "PASS", "WALKSAFE_INCIDENT_TEST_UNIVERSE_PASSED": "64", "WALKSAFE_LEGAL_REVIEW_STATUS": "NOT_RUN", "WALKSAFE_PRIVACY_REVIEW_STATUS": "NOT_RUN", "WALKSAFE_NOTIFICATION_EXECUTION_STATUS": "NOT_RUN"}
    require(values == dict(spec.exact_markers), f"{spec.lane_id} marker contract differs")
    return values

def _fsync_directory(path: Path) -> None:
    descriptor = os.open(
        path,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _ensure_parent_directories(root: Path, relative: Path) -> Path:
    root = root.resolve()
    current = root
    for part in relative.parent.parts:
        parent = current
        current /= part
        if os.path.lexists(current):
            info = current.lstat()
            require(stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode), f"unsafe output parent: {relative}")
            require(info.st_uid == os.getuid() and not (stat.S_IMODE(info.st_mode) & 0o002), f"output parent authority differs: {relative}")
            continue
        os.mkdir(current, 0o700)
        _fsync_directory(parent)
    return current


def _rename_noreplace(source: Path, destination: Path) -> None:
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    require(renameat2 is not None, "renameat2(RENAME_NOREPLACE) is unavailable")
    renameat2.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    renameat2.restype = ctypes.c_int
    result = renameat2(
        -100,
        os.fsencode(source),
        -100,
        os.fsencode(destination),
        1,
    )
    if result == 0:
        return
    error = ctypes.get_errno()
    if error == errno.EEXIST:
        raise BuildError(f"destination already exists: {destination}")
    raise OSError(error, os.strerror(error), destination)


def _atomic_publish_log(root: Path, relative: Path, content: bytes) -> Path:
    _ensure_parent_directories(root, relative)
    destination = safe_path(root, relative, must_exist=False)
    if os.path.lexists(destination):
        require(_read_regular_path(destination, relative.as_posix(), require_single_link=True, required_mode=0o600, required_uid=os.getuid()) == content, f"existing log differs: {relative}")
        return destination
    temporary = destination.with_name(f".{destination.name}.capture.tmp")
    if os.path.lexists(temporary):
        require(_read_regular_path(temporary, str(temporary), require_single_link=True, required_mode=0o600, required_uid=os.getuid()) == content, f"capture temp differs: {temporary}")
    else:
        descriptor = os.open(
            temporary,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
            0o600,
        )
        try:
            position = 0
            while position < len(content):
                written = os.write(descriptor, content[position:])
                require(written > 0, "short capture write")
                position += written
            os.fsync(descriptor)
            _validate_regular_authority(os.fstat(descriptor), str(temporary), require_single_link=True, required_mode=0o600, required_uid=os.getuid())
        finally:
            os.close(descriptor)
        _fsync_directory(destination.parent)
    _rename_noreplace(temporary, destination)
    _fsync_directory(destination.parent)
    _read_regular_path(destination, relative.as_posix(), require_single_link=True, required_mode=0o600, required_uid=os.getuid())
    return destination

def capture_lane(
    lane_id: str, *, root: Path = ROOT, runner: Callable[..., Any] = subprocess.run,
    clock: Callable[[], datetime] | None = None, authority: Mapping[str, Any] | None = None,
    implementation_rows: list[dict[str, Any]] | None = None,
    junit_summary: Mapping[str, int] | None = None,
    verification_input_rows: list[dict[str, Any]] | None = None,
    test_mode: bool = False,
) -> Path:
    require(junit_summary is None, "injected Android JUnit summary is forbidden")
    matches = [spec for spec in LANES if spec.lane_id == lane_id]
    require(len(matches) == 1, f"unknown lane: {lane_id}")
    spec, root = matches[0], root.resolve()
    auth = deepcopy(dict(authority)) if authority is not None else validate_authority(root)
    rows = deepcopy(implementation_rows) if implementation_rows is not None else implementation_files(root, auth["repository_state"])
    if not test_mode:
        require([row["path"] for row in rows] == list(IMPLEMENTATION_PATHS), "capture scope differs")
    content_set = implementation_content_set(rows)
    _verification_inputs, verification_input_content_set = verification_input_manifest(
        root,
        injected=verification_input_rows,
        test_mode=test_mode,
    )
    destination = root / spec.log_rel
    temporary = destination.with_name(f".{destination.name}.capture.tmp")
    if os.path.lexists(destination):
        existing = _read_regular_path(destination, spec.log_rel.as_posix(), require_single_link=True, required_mode=0o600, required_uid=os.getuid())
        checked_log(
            spec,
            existing,
            root=root,
            content_set_sha256=content_set,
            verification_input_content_set_sha256=verification_input_content_set,
            event=auth["event"],
        )
        return destination
    if os.path.lexists(temporary):
        staged = _read_regular_path(temporary, str(temporary), require_single_link=True, required_mode=0o600, required_uid=os.getuid())
        checked_log(
            spec,
            staged,
            root=root,
            content_set_sha256=content_set,
            verification_input_content_set_sha256=verification_input_content_set,
            event=auth["event"],
        )
        return _atomic_publish_log(root, spec.log_rel, staged)
    now = clock or (lambda: datetime.now().astimezone())
    started = now()
    completed = runner(spec.command, cwd=root, shell=True, executable="/bin/bash", check=False, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    ended = now()
    require(started.tzinfo is not None and ended.tzinfo is not None and started <= ended, "capture clock differs")
    raw = completed.stdout if isinstance(completed.stdout, bytes) else str(completed.stdout or "").encode()
    require(completed.returncode == 0, f"{lane_id} command exited {completed.returncode}; log not published")
    try:
        raw_text = raw.decode()
    except UnicodeDecodeError as exc:
        raise BuildError(f"{lane_id} output is not UTF-8; log not published") from exc
    observed = observed_lane_markers(
        spec,
        raw_text,
        root,
    )
    run_seed = f"{lane_id}\0{started.isoformat()}\0{bytes_sha256(raw)}".encode()
    header = [
        f"WALKSAFE_EXECUTION_EVENT_SEQUENCE={auth['event']['sequence']}",
        f"WALKSAFE_EXECUTION_EVENT_ID={auth['event']['event_id']}",
        f"WALKSAFE_EXECUTION_EVENT_SHA256={auth['event']['event_sha256']}",
        f"WALKSAFE_IMPLEMENTATION_CONTENT_SET_SHA256={content_set}",
        f"WALKSAFE_VERIFICATION_INPUT_CONTENT_SET_SHA256={verification_input_content_set}",
        f"WALKSAFE_RUN_ID=fp048-{spec.slug}-{bytes_sha256(run_seed)[:16]}",
        f"WALKSAFE_COMMAND_SHA256={bytes_sha256(spec.command.encode())}",
        f"WALKSAFE_COMMAND_STARTED_AT={started.isoformat()}",
        f"WALKSAFE_LANE_ID={lane_id}", "WALKSAFE_LANE_STATUS=PASS",
    ]
    tail = [*(f"{name}={value}" for name, value in observed.items()), f"WALKSAFE_COMMAND_ENDED_AT={ended.isoformat()}", "WALKSAFE_COMMAND_EXIT_CODE=0"]
    content = "\n".join(header).encode() + f"\n{RAW_OUTPUT_BEGIN}\n".encode() + raw
    if not content.endswith(b"\n"):
        content += b"\n"
    content += (RAW_OUTPUT_END + "\n" + "\n".join(tail) + "\n").encode()
    checked_log(
        spec,
        content,
        root=root,
        content_set_sha256=content_set,
        verification_input_content_set_sha256=verification_input_content_set,
        event=auth["event"],
    )
    return _atomic_publish_log(root, spec.log_rel, content)

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--capture-lane", choices=[spec.lane_id for spec in LANES])
    mode.add_argument("--write-review-subject", action="store_true")
    mode.add_argument("--check-review-subject", action="store_true")
    mode.add_argument("--write-post-review", action="store_true")
    mode.add_argument("--check-post-review", action="store_true")
    mode.add_argument("--print-commands", action="store_true")
    mode.add_argument("--emit-android-junit-xml", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.print_commands:
            for spec in LANES:
                print(f"{spec.lane_id}\t{spec.log_rel}\t{spec.command}")
            return 0
        if args.emit_android_junit_xml:
            emit_android_junit_xml(args.root)
            return 0
        if args.capture_lane:
            output = capture_lane(args.capture_lane, root=args.root)
            print(f"FP-048 encryption/security trace: PASS lane={args.capture_lane} log={output.relative_to(args.root.resolve())} sha256={file_sha256(output)} mode=CAPTURE")
            return 0
        if args.write_review_subject or args.check_review_subject:
            outputs = build_pre_review_outputs(root=args.root)
            write_or_check_outputs(args.root, outputs, write=args.write_review_subject)
            mode_name = "WRITE_REVIEW_SUBJECT" if args.write_review_subject else "CHECK_REVIEW_SUBJECT"
        else:
            outputs = build_post_review_outputs(root=args.root)
            write_or_check_outputs(args.root, outputs, write=args.write_post_review)
            mode_name = "WRITE_POST_REVIEW" if args.write_post_review else "CHECK_POST_REVIEW"
    except (BuildError, OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(f"FP-048 encryption/security trace: FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"FP-048 encryption/security trace: PASS outputs={len(outputs)} mode={mode_name}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
