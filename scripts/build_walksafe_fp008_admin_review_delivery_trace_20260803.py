#!/usr/bin/env python3
"""Build the FP-008 internal admin-review and manual-delivery evidence trace.

The producer is deliberately evidence-only.  It never runs a formal test, talks to
an institution, deploys an application, or updates the continuation checkpoint.
Four already-captured *internal* lane observations are required.  Official output
publication is add-only and is available only through the explicit ``--write``
mode; importing the module and calling :func:`build_outputs` is side-effect free.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat
from typing import Any, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = "WS-GOAL-EPIC-03-FP-008-R001"
POLICY_ID = "FP-008"
GAP_ID = "GAP-017"
RESULT_DIR_REL = Path(f"docs/control/execution/goal-results/{GOAL_ID}")
GOAL_REL = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-fp008-admin-review-delivery-r001.md"
)
POLICY_CONTRACT_REL = RESULT_DIR_REL / "policy-contract.json"
INITIAL_GATE_DIR_REL = Path(
    "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-002"
)
INITIAL_GATE_REL = INITIAL_GATE_DIR_REL / "implementation-start-gate-receipt.json"
INITIAL_GATE_REPOSITORY_STATE_REL = INITIAL_GATE_DIR_REL / "09-REPOSITORY_STATE.log"
RESUME_GATE_DIR_REL = Path(
    "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-FP008-20260809-005"
)
RESUME_GATE_REL = Path(
    RESUME_GATE_DIR_REL / "implementation-resume-gate-receipt.json"
)
RESUME_GATE_REPOSITORY_STATE_REL = RESUME_GATE_DIR_REL / "09-REPOSITORY_STATE.log"
IMPLEMENTATION_REL = RESULT_DIR_REL / "implementation-record.json"
VERIFICATION_REL = RESULT_DIR_REL / "verification-result.json"
SUCCESSOR_REL = RESULT_DIR_REL / "successor-trace.json"
REVIEW_SUBJECT_REL = RESULT_DIR_REL / "review-subject.json"
REVIEW_ATTESTATION_REL = RESULT_DIR_REL / "review-attestation.json"
INDEPENDENT_REVIEW_REL = RESULT_DIR_REL / "independent-review.json"
COMPLETION_RECEIPT_REL = RESULT_DIR_REL / "completion-receipt.json"

EXPECTED_GOAL_SHA256 = "2654fe5f595aefaecf974a4de70bad7946c2385cba9716200f2d2f49d849dd8e"
EXPECTED_POLICY_CONTRACT_SHA256 = "59f4ce8d160a4736b3f3820d3b60f0a805ef7cd887f6451ca5424917412b8a16"
EXPECTED_INITIAL_GATE_SHA256 = "c60c0da7311e86d3b3ee0c8282719468474d5356c267d283e049c911f550b47a"
EXPECTED_INITIAL_GATE_REPOSITORY_STATE_SHA256 = "5e6832dd3a34b65e8baf8ba2b4ae1133edc623df613d5339eca617eeeb27e16c"
EXPECTED_RESUME_GATE_SHA256 = "394c9775c8ed3b2fbdac1b3cf8354ff839006b91998adc8cbbbb7c5f13dd8c5e"
EXPECTED_RESUME_GATE_REPOSITORY_STATE_SHA256 = "8faf37bf0f95bf19645141e8878d3294c1f9ae4f809d4c49290651a0fe996684"
EXPECTED_RESUME_EVENT_SEQUENCE = 48
EXPECTED_RESUME_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-FP008-20260809-005"
)

# Keep the mutable integration surface in this one block.  The parallel product
# branches may add a small number of files; changing this tuple is the only scope
# edit required by the final integrator.  Order is part of the contract.
ANDROID_ADMINAPP_PATHS = (
    "apps/android/settings.gradle.kts",
    "apps/android/build.gradle.kts",
    "apps/android/gradlew",
    "apps/android/gradle/wrapper/gradle-wrapper.jar",
    "apps/android/gradle/wrapper/gradle-wrapper.properties",
    "apps/android/gradle/verification-metadata.xml",
    "apps/android/adminapp/README.md",
    "apps/android/adminapp/build.gradle.kts",
    "apps/android/adminapp/gradle.lockfile",
    "apps/android/adminapp/proguard-rules.pro",
    "apps/android/adminapp/src/main/AndroidManifest.xml",
    "apps/android/adminapp/src/debug/AndroidManifest.xml",
    "apps/android/adminapp/src/debug/res/xml/admin_debug_network_security_config.xml",
    "apps/android/adminapp/src/main/res/xml/admin_data_extraction_rules.xml",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminCanonicalEncoding.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeviceKeyStore.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeviceProof.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminEndpointPolicy.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGate.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminInstitutionDelivery.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOperationsApi.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOperationsHttpClient.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminRecoveryMessagePolicy.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminReportDecision.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityApi.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityApiException.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityState.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityTelemetry.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminStrictJson.java",
    "apps/android/adminapp/src/main/res/values/strings.xml",
    "apps/android/adminapp/src/main/res/values/styles.xml",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminCanonicalEncodingTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeviceKeyDescriptorTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeviceProofTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminEndpointPolicyTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGateTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOperationModelsTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOperationsHttpClientTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminRecoveryMessagePolicyTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityBoundaryStaticTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityControllerTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClientTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminStrictJsonTest.java",
)

BACKEND_FP008_PATHS = (
    "backend/README.md",
    "backend/app/main.py",
    "backend/app/models.py",
    "backend/alembic/versions/202608080001_fp008_admin_review_delivery.py",
    "backend/app/api/admin_security.py",
    "backend/app/api/health.py",
    "backend/app/api/reports.py",
    "backend/app/config.py",
    "backend/app/field_test_security.py",
    "backend/app/openapi_contract.py",
    "backend/app/request_limits.py",
    "backend/app/services/admin_device_proof.py",
    "backend/app/services/admin_report_workflow.py",
    "backend/app/services/admin_security.py",
    "backend/app/schemas.py",
    "scripts/provision_walksafe_admin_device_key.py",
    "contracts/walksafe.openapi.json",
    "backend/tests/test_admin_device_proof.py",
    "backend/tests/test_admin_report_workflow.py",
    "backend/tests/test_fp008_postgres_integration.py",
    "backend/tests/conftest.py",
    "backend/tests/test_openapi_contract.py",
)

DOCUMENTATION_REGISTRY_PATHS = (
    "README.md",
    "docs/backend/api_reference.md",
    "scripts/README.md",
    "scripts/check_code_documentation_20260710.py",
    "scripts/run_walksafe_test_layers_20260711.sh",
    "configs/walksafe_product_boundary_20260722.json",
    "tests/test_walksafe_android_product_boundary.py",
)

BUILDER_TOOLING_PATHS = (
    "scripts/build_walksafe_fp008_admin_review_delivery_trace_20260803.py",
    "scripts/build_walksafe_fp008_gap_backlog_r024_20260803.py",
    "scripts/build_walksafe_fp008_artifact_trace_successor_20260803.py",
    "scripts/build_walksafe_phase1_exact257_successor_r013_20260803.py",
    "scripts/build_walksafe_fp008_strict_review_gate_20260803.py",
    "tests/walksafe_fp008_builder_test_support.py",
    "tests/test_walksafe_fp008_admin_review_delivery_trace_20260803.py",
    "tests/test_walksafe_fp008_gap_backlog_r024_20260803.py",
    "tests/test_walksafe_fp008_artifact_trace_successor_20260803.py",
    "tests/test_walksafe_phase1_exact257_successor_r013_20260803.py",
    "tests/test_walksafe_fp008_strict_review_gate_20260803.py",
)

IMPLEMENTATION_PATHS = (
    ANDROID_ADMINAPP_PATHS
    + BACKEND_FP008_PATHS
    + DOCUMENTATION_REGISTRY_PATHS
    + BUILDER_TOOLING_PATHS
)

VERIFICATION_INPUT_PATHS = (
    "tests/test_walksafe_fp008_policy_contract_20260809.py",
    "backend/tests/test_admin_device_proof.py",
    "backend/tests/test_admin_report_workflow.py",
    "backend/tests/conftest.py",
    "backend/tests/test_admin_security.py",
    "backend/tests/test_fp008_postgres_integration.py",
    "backend/tests/test_openapi_contract.py",
    "backend/tests/test_reports.py",
    "backend/tests/test_reports_v2.py",
    "backend/tests/test_field_test_security.py",
    "apps/android/settings.gradle.kts",
    "apps/android/build.gradle.kts",
    "apps/android/gradlew",
    "apps/android/gradle/wrapper/gradle-wrapper.jar",
    "apps/android/gradle/wrapper/gradle-wrapper.properties",
    "apps/android/gradle/verification-metadata.xml",
    "apps/android/adminapp/build.gradle.kts",
    "apps/android/adminapp/gradle.lockfile",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminCanonicalEncodingTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeviceKeyDescriptorTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeviceProofTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminEndpointPolicyTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGateTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOperationModelsTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOperationsHttpClientTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminRecoveryMessagePolicyTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityBoundaryStaticTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityControllerTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClientTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminStrictJsonTest.java",
    "tests/test_walksafe_fp008_admin_review_delivery_trace_20260803.py",
    "tests/test_walksafe_fp008_gap_backlog_r024_20260803.py",
    "tests/test_walksafe_fp008_artifact_trace_successor_20260803.py",
    "tests/test_walksafe_phase1_exact257_successor_r013_20260803.py",
    "tests/test_walksafe_fp008_strict_review_gate_20260803.py",
)

FORMAL_TEST_IDS = tuple(f"TC-FP-008-{number:02d}" for number in range(1, 5))
FORBIDDEN_SOURCE_FRAGMENTS = (
    "legacy1",
    "legacy2",
    "legacy3",
    "r034",
    "r035",
    "apps/web",
    "/web/",
    "pwa",
    "docs/submission",
    "old_submission",
    "old-submission",
    ".docx",
    ".pptx",
    "review-subject",
    "review_subject",
    "review-attestation",
    "review_attestation",
    "independent-review",
    "independent_review",
    "completion-receipt",
    "completion_receipt",
)
FORBIDDEN_PUBLICATION_FRAGMENTS = (
    "walksafe-project-continuation-checkpoint",
    "daylog",
    "checkpoint",
    "goal-gates",
    "control-publication",
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class BuildError(RuntimeError):
    """Raised when an FP-008 producer boundary fails closed."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BuildError(message)


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n"


def bytes_sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def object_sha256(value: Any) -> str:
    return bytes_sha256(canonical_json(value).encode("utf-8"))


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON member: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise BuildError(f"non-finite JSON number is forbidden: {value}")


def strict_json_bytes(content: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(
            content.decode("utf-8"),
            object_pairs_hook=_reject_duplicates,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BuildError(f"invalid JSON for {label}: {exc}") from exc
    require(type(value) is dict, f"{label} must be a JSON object")
    return value


def require_document_matches_raw(
    document: Mapping[str, Any], raw: bytes, label: str
) -> None:
    require(
        dict(document) == strict_json_bytes(raw, label),
        f"{label} document/raw binding differs",
    )


def _validate_relative(relative: Path) -> None:
    require(not relative.is_absolute(), f"absolute repository path forbidden: {relative}")
    require(bool(relative.parts), "empty repository path forbidden")
    require(all(part not in {"", ".", ".."} for part in relative.parts), f"unsafe path: {relative}")


def safe_file(root: Path, relative: Path) -> Path:
    _validate_relative(relative)
    root = root.resolve(strict=True)
    cursor = root
    for component in relative.parts:
        cursor = cursor / component
        try:
            info = cursor.lstat()
        except FileNotFoundError as exc:
            raise BuildError(f"required source missing: {relative.as_posix()}") from exc
        require(not stat.S_ISLNK(info.st_mode), f"symlink source forbidden: {relative.as_posix()}")
    info = cursor.stat()
    require(stat.S_ISREG(info.st_mode), f"source is not a regular file: {relative.as_posix()}")
    require(cursor.resolve(strict=True).is_relative_to(root), f"source escapes root: {relative.as_posix()}")
    return cursor


def read_bytes(root: Path, relative: Path) -> bytes:
    path = safe_file(root, relative)
    before = path.stat()
    content = path.read_bytes()
    after = path.stat()
    identity = lambda info: (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
    require(identity(before) == identity(after), f"source changed while read: {relative.as_posix()}")
    return content


def load_json(root: Path, relative: Path) -> dict[str, Any]:
    return strict_json_bytes(read_bytes(root, relative), relative.as_posix())


def file_binding(root: Path, relative: Path, role: str) -> dict[str, Any]:
    raw = read_bytes(root, relative)
    return {
        "role": role,
        "path": relative.as_posix(),
        "byte_length": len(raw),
        "sha256": bytes_sha256(raw),
    }


def sealed(value: Mapping[str, Any], field: str) -> dict[str, Any]:
    result = deepcopy(dict(value))
    result.pop(field, None)
    result[field] = object_sha256(result)
    return result


def verify_seal(value: Mapping[str, Any], field: str, label: str) -> str:
    expected = value.get(field)
    require(type(expected) is str and SHA256_RE.fullmatch(expected), f"{label} seal missing")
    projection = deepcopy(dict(value))
    projection.pop(field, None)
    require(object_sha256(projection) == expected, f"{label} seal differs")
    return expected


def completion_boundary() -> dict[str, Any]:
    return {
        "scope": "REPOSITORY_INTERNAL_FP008_IMPLEMENTATION_AND_AUTOMATED_VERIFICATION_ONLY",
        "planned_test_ids": list(FORMAL_TEST_IDS),
        "formal_test_status": "NOT_RUN",
        "formal_test_credit_count": 0,
        "actual_device_status": "NOT_RUN",
        "actual_device_credit_count": 0,
        "external_institution_status": "NOT_RUN",
        "external_authentication_status": "NOT_RUN",
        "external_security_review_status": "NOT_RUN",
        "external_privacy_review_status": "NOT_RUN",
        "operational_database_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_gate_status": "NOT_RUN",
        "release_gates_waived": False,
        "artifact_approval_claimed": False,
        "release_status": "NOT_ELIGIBLE",
        "release_credit_count": 0,
    }


@dataclass(frozen=True)
class LaneSpec:
    lane_id: str
    title: str
    receipt_name: str
    expected_command: str
    expected_metrics: Mapping[str, Any]
    result_kind: str

    @property
    def receipt_rel(self) -> Path:
        return RESULT_DIR_REL / "evidence" / f"{self.receipt_name}-receipt.json"

    @property
    def log_rel(self) -> Path:
        return RESULT_DIR_REL / "logs" / f"{self.receipt_name}.log"


TEST_DATABASE_ENV = (
    "env WALKSAFE_TEST_DATABASE_URL='postgresql+psycopg://walksafe_test:"
    "walksafe_test_password@127.0.0.1:32768/walksafe_fp008_test' PYTHONPATH=. "
)
LOCKED_TEST_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)
PROOF_LANE_COMMAND = (
    TEST_DATABASE_ENV
    + LOCKED_TEST_PYTHON
    + " -m pytest -p no:cacheprovider -q backend/tests/test_admin_device_proof.py "
    "backend/tests/test_fp008_postgres_integration.py::"
    "test_fp008_postgres_schema_device_key_and_single_use_proof"
)
WORKFLOW_LANE_COMMAND = (
    TEST_DATABASE_ENV
    + LOCKED_TEST_PYTHON
    + " -m pytest -p no:cacheprovider -q backend/tests/test_admin_report_workflow.py "
    "backend/tests/test_fp008_postgres_integration.py::"
    "test_fp008_postgres_review_delivery_authority_and_append_only_history"
)
ANDROID_LANE_COMMAND = (
    "./gradlew --offline :adminapp:testDebugUnitTest :adminapp:assembleDebug "
    ":adminapp:lintDebug --no-daemon --max-workers=1 --rerun-tasks"
)
POLICY_LANE_COMMAND = (
    "PYTHONDONTWRITEBYTECODE=1 "
    + LOCKED_TEST_PYTHON
    + " -B -m pytest -p no:cacheprovider -q "
    "tests/test_walksafe_fp008_policy_contract_20260809.py "
    "tests/test_walksafe_fp008_admin_review_delivery_trace_20260803.py "
    "tests/test_walksafe_fp008_gap_backlog_r024_20260803.py "
    "tests/test_walksafe_fp008_artifact_trace_successor_20260803.py "
    "tests/test_walksafe_phase1_exact257_successor_r013_20260803.py "
    "tests/test_walksafe_fp008_strict_review_gate_20260803.py"
)


def _pytest_metrics(passed: int) -> dict[str, Any]:
    return {
        "result_format": "PYTEST_TERMINAL_SUMMARY_V1",
        "working_directory": ".",
        "passed": passed,
        "failed": 0,
        "errors": 0,
        "skipped": 0,
    }


ANDROID_EXPECTED_METRICS = {
    "result_format": "GRADLE_ANDROID_SUMMARY_V1",
    "working_directory": "apps/android",
    "build_status": "SUCCESSFUL",
    "junit_tests": 70,
    "junit_failures": 0,
    "junit_errors": 0,
    "junit_skipped": 0,
    "lint_errors": 0,
    "lint_warnings": 1,
    "apk_byte_length": 921914,
    "apk_sha256": "9afeb2bc1243603c3022074d9faefe6c647d65be3a9cbbab5f48dccab9b7ed58",
}

LANES = (
    LaneSpec(
        "BACKEND_ADMIN_DEVICE_PROOF_POSTGRES",
        "Registered-device and additional-auth proof",
        "backend-admin-device-proof",
        PROOF_LANE_COMMAND,
        _pytest_metrics(34),
        "PYTEST",
    ),
    LaneSpec(
        "BACKEND_ADMIN_REVIEW_DELIVERY_POSTGRES",
        "Review decision and manual-delivery workflow",
        "backend-admin-review-delivery",
        WORKFLOW_LANE_COMMAND,
        _pytest_metrics(70),
        "PYTEST",
    ),
    LaneSpec(
        "ANDROID_ADMINAPP_OFFLINE",
        "Separate Android admin application boundary",
        "android-adminapp-offline",
        ANDROID_LANE_COMMAND,
        ANDROID_EXPECTED_METRICS,
        "ANDROID_GRADLE",
    ),
    LaneSpec(
        "POLICY_ARTIFACT_CONTRACTS",
        "FP-008 policy and result contracts",
        "policy-artifact-contracts",
        POLICY_LANE_COMMAND,
        _pytest_metrics(30),
        "PYTEST",
    ),
)

LANE_BY_ID = {lane.lane_id: lane for lane in LANES}


def _parse_time(value: Any, label: str) -> datetime:
    require(type(value) is str, f"{label} must be a timestamp")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise BuildError(f"invalid {label}: {value}") from exc
    require(parsed.tzinfo is not None, f"{label} must include an offset")
    require(
        parsed.isoformat() == value,
        f"{label} must use canonical ISO-8601",
    )
    return parsed


def validate_authority(root: Path = ROOT) -> dict[str, Any]:
    goal = file_binding(root, GOAL_REL, "FP008_GOAL")
    require(goal["sha256"] == EXPECTED_GOAL_SHA256, "FP-008 goal bytes differ")
    initial_raw = read_bytes(root, INITIAL_GATE_REL)
    require(bytes_sha256(initial_raw) == EXPECTED_INITIAL_GATE_SHA256, "initial 9-check receipt differs")
    initial = strict_json_bytes(initial_raw, "initial 9-check receipt")
    gate_raw = read_bytes(root, RESUME_GATE_REL)
    require(bytes_sha256(gate_raw) == EXPECTED_RESUME_GATE_SHA256, "seq48 resume receipt differs")
    gate = strict_json_bytes(gate_raw, "seq48 resume receipt")
    for label, value, purpose, repository_state_rel, repository_state_sha in (
        ("initial", initial, "INITIAL_START", INITIAL_GATE_REPOSITORY_STATE_REL, EXPECTED_INITIAL_GATE_REPOSITORY_STATE_SHA256),
        ("resume", gate, "SESSION_RESUME", RESUME_GATE_REPOSITORY_STATE_REL, EXPECTED_RESUME_GATE_REPOSITORY_STATE_SHA256),
    ):
        require(value.get("status") == "PASS" and value.get("gate_purpose") == purpose, f"{label} gate identity differs")
        checks = value.get("check_runs")
        require(type(checks) is list and len(checks) == 9, f"{label} gate is not exact 9-check")
        require(checks[-1].get("check_id") == "REPOSITORY_STATE", f"{label} repository-state check differs")
        require(checks[-1].get("output_path") == repository_state_rel.as_posix(), f"{label} repository-state filename is not 09-REPOSITORY_STATE.log")
        repository_state_raw = read_bytes(root, repository_state_rel)
        require(bytes_sha256(repository_state_raw) == repository_state_sha, f"{label} repository-state bytes differ")
        require(value.get("repository_snapshot", {}).get("gate_repository_state_output_sha256") == repository_state_sha, f"{label} repository-state binding differs")
    require(gate.get("status") == "PASS", "seq48 resume receipt did not pass")
    require(gate.get("target_goal_id") == GOAL_ID, "seq48 target goal differs")
    require(gate.get("target_transition_event_id") == EXPECTED_RESUME_EVENT_ID, "seq48 event differs")
    require(gate.get("target_goal_content_sha256") == EXPECTED_GOAL_SHA256, "seq48 goal binding differs")
    policy = load_json(root, POLICY_CONTRACT_REL)
    require(policy.get("goal_id") == GOAL_ID and policy.get("policy_id") == POLICY_ID, "policy contract identity differs")
    # The policy contract uses a nested integrity seal.
    integrity = policy.get("integrity")
    require(type(integrity) is dict and type(integrity.get("content_sha256")) is str, "policy contract integrity missing")
    projection = deepcopy(policy)
    projection["integrity"].pop("content_sha256")
    require(object_sha256(projection) == integrity["content_sha256"], "policy contract integrity differs")
    policy_binding = file_binding(root, POLICY_CONTRACT_REL, "FP008_POLICY_CONTRACT")
    require(policy_binding["sha256"] == EXPECTED_POLICY_CONTRACT_SHA256, "pinned immutable policy contract bytes differ")
    return {
        "goal_binding": goal,
        "initial_gate_binding": {
            "role": "INITIAL_EXACT9_IMPLEMENTATION_START_GATE",
            "path": INITIAL_GATE_REL.as_posix(),
            "byte_length": len(initial_raw),
            "sha256": bytes_sha256(initial_raw),
            "repository_state_path": INITIAL_GATE_REPOSITORY_STATE_REL.as_posix(),
            "repository_state_sha256": EXPECTED_INITIAL_GATE_REPOSITORY_STATE_SHA256,
        },
        "resume_gate_binding": {
            "role": "SEQ48_WORK_SESSION_RESUME_GATE",
            "path": RESUME_GATE_REL.as_posix(),
            "byte_length": len(gate_raw),
            "sha256": bytes_sha256(gate_raw),
            "event_sequence": EXPECTED_RESUME_EVENT_SEQUENCE,
            "event_id": EXPECTED_RESUME_EVENT_ID,
        },
        "policy_contract_binding": policy_binding,
        "gate_ended_at": gate["execution_window"]["ended_at"],
    }


def _path_forbidden(path: str) -> bool:
    normalized = path.replace("\\", "/").lower()
    collapsed = re.sub(r"[^a-z0-9]", "", normalized)
    components = {re.sub(r"[^a-z0-9]", "", component) for component in normalized.split("/")}
    return (
        any(fragment in normalized for fragment in FORBIDDEN_SOURCE_FRAGMENTS)
        or any(marker in collapsed for marker in ("legacy1", "legacy2", "legacy3", "r034", "r035", "oldsubmission"))
        or bool(components & {"web", "pwa", "submission"})
    )


def snapshot_paths(root: Path, paths: Sequence[str], role: str) -> list[dict[str, Any]]:
    require(len(paths) == len(set(paths)), f"duplicate {role} path")
    rows: list[dict[str, Any]] = []
    for path in paths:
        require(not _path_forbidden(path), f"forbidden {role} source: {path}")
        rows.append(file_binding(root, Path(path), role))
    return rows


def _validate_lane_result(
    spec: LaneSpec, value: Mapping[str, Any], raw_output: bytes
) -> None:
    require(value.get("command") == spec.expected_command, f"{spec.lane_id} command differs")
    metrics = value.get("metrics")
    require(type(metrics) is dict, f"{spec.lane_id} metrics must be an object")
    require(set(metrics) == set(spec.expected_metrics), f"{spec.lane_id} metric fields differ")
    for name, expected in spec.expected_metrics.items():
        require(
            type(metrics[name]) is type(expected) and metrics[name] == expected,
            f"{spec.lane_id} metric differs: {name}",
        )
    try:
        output = raw_output.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BuildError(f"{spec.lane_id} raw output is not UTF-8") from exc
    lines = output.splitlines()
    command_lines = [line for line in lines if line.startswith("WALKSAFE_FP008_COMMAND ")]
    require(
        command_lines == [f"WALKSAFE_FP008_COMMAND {spec.expected_command}"],
        f"{spec.lane_id} raw output command marker differs",
    )
    if spec.result_kind == "PYTEST":
        long_duration_suffix = r"(?: \([0-9]+:[0-5][0-9]:[0-5][0-9]\))?"
        summary_pattern = re.compile(
            r"(?:\d+ (?:passed|failed|errors?|skipped|xfailed|xpassed|warnings?)"
            r"(?:, )?)+ in [0-9]+(?:\.[0-9]+)?s" + long_duration_suffix
        )
        summaries = [line for line in lines if summary_pattern.fullmatch(line)]
        expected_summary = f"{spec.expected_metrics['passed']} passed in "
        require(
            len(summaries) == 1
            and summaries[0].startswith(expected_summary)
            and re.fullmatch(
                rf"{spec.expected_metrics['passed']} passed in "
                rf"[0-9]+(?:\.[0-9]+)?s{long_duration_suffix}",
                summaries[0],
            )
            is not None,
            f"{spec.lane_id} pytest terminal summary differs",
        )
    elif spec.result_kind == "ANDROID_GRADLE":
        build_lines = [
            line for line in lines if re.fullmatch(r"BUILD (?:SUCCESSFUL|FAILED)(?: in .*)?", line)
        ]
        require(
            len(build_lines) == 1 and build_lines[0].startswith("BUILD SUCCESSFUL"),
            "Android lane Gradle result differs",
        )
        markers = (
            "WALKSAFE_FP008_JUNIT tests=70 failures=0 errors=0 skipped=0",
            "WALKSAFE_FP008_LINT errors=0 warnings=1",
            "WALKSAFE_FP008_APK byte_length=921914 "
            "sha256=9afeb2bc1243603c3022074d9faefe6c647d65be3a9cbbab5f48dccab9b7ed58",
        )
        for marker in markers:
            prefix = marker.split(" ", 1)[0]
            require(
                [line for line in lines if line.startswith(prefix + " ")] == [marker],
                f"Android lane result marker differs: {prefix}",
            )
    else:
        raise BuildError(f"unsupported lane result kind: {spec.result_kind}")


def _validate_lane_observation(
    spec: LaneSpec,
    value: Mapping[str, Any],
    gate_ended_at: str,
    raw_output: bytes,
) -> dict[str, Any]:
    exact = {
        "schema_version",
        "lane_id",
        "status",
        "command",
        "exit_code",
        "started_at",
        "ended_at",
        "raw_output_sha256",
        "raw_output_byte_length",
        "metrics",
        "evidence_boundary",
    }
    require(set(value) == exact, f"{spec.lane_id} observation fields differ")
    require(value.get("schema_version") == "walksafe.fp008-internal-lane-observation.v1", f"{spec.lane_id} schema differs")
    require(value.get("lane_id") == spec.lane_id, f"{spec.lane_id} identity differs")
    require(value.get("status") == "PASS" and type(value.get("exit_code")) is int and value["exit_code"] == 0, f"{spec.lane_id} did not pass")
    require(type(raw_output) is bytes, f"{spec.lane_id} raw output must be bytes")
    require(type(value.get("raw_output_sha256")) is str and SHA256_RE.fullmatch(value["raw_output_sha256"]), f"{spec.lane_id} raw output digest differs")
    require(type(value.get("raw_output_byte_length")) is int and value["raw_output_byte_length"] >= 0, f"{spec.lane_id} byte length differs")
    require(type(value.get("metrics")) is dict and value["metrics"], f"{spec.lane_id} metrics missing")
    expected_boundary = {
        "evidence_kind": "REPOSITORY_INTERNAL_AUTOMATED_CHECK",
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "external_status": "NOT_RUN",
        "production_status": "NOT_RUN",
    }
    require(value.get("evidence_boundary") == expected_boundary, f"{spec.lane_id} evidence boundary differs")
    require(len(raw_output) == value["raw_output_byte_length"], f"{spec.lane_id} raw output byte length differs")
    require(bytes_sha256(raw_output) == value["raw_output_sha256"], f"{spec.lane_id} raw output digest differs")
    _validate_lane_result(spec, value, raw_output)
    started = _parse_time(value["started_at"], f"{spec.lane_id} start")
    ended = _parse_time(value["ended_at"], f"{spec.lane_id} end")
    require(_parse_time(gate_ended_at, "seq48 gate end") <= started <= ended, f"{spec.lane_id} predates seq48 or has reversed time")
    return deepcopy(dict(value))


def _lane_receipt(spec: LaneSpec, observation: Mapping[str, Any], raw_output: bytes, authority: Mapping[str, Any], content_set: str) -> dict[str, Any]:
    return sealed(
        {
            "schema_version": "walksafe.fp008-internal-lane-receipt.v1",
            "document_id": f"WS-FP008-{spec.lane_id}-RECEIPT-20260803-001",
            "goal_id": GOAL_ID,
            "lane_id": spec.lane_id,
            "title": spec.title,
            "status": "PASS",
            "implementation_content_set_sha256": content_set,
            "resume_gate_binding": deepcopy(authority["resume_gate_binding"]),
            "raw_output_binding": {
                "path": spec.log_rel.as_posix(),
                "byte_length": len(raw_output),
                "sha256": bytes_sha256(raw_output),
            },
            "observation": deepcopy(dict(observation)),
            "completion_boundary": completion_boundary(),
        },
        "receipt_content_sha256",
    )


def consumer_contract_manifest() -> list[dict[str, Any]]:
    from_paths = (
        ("GAP_R024", "docs/control/audits/walksafe-implementation-gap-analysis-20260809-r024.json"),
        ("BACKLOG_R024", "docs/control/audits/walksafe-implementation-remediation-backlog-20260809-r024.json"),
        ("ARTIFACT_CHANGE_LOG", "docs/deliverables/00-control/artifact-change-log.json"),
        ("ARTIFACT_REGISTER", "docs/deliverables/00-control/artifact-register.json"),
        ("REQUIREMENTS_TRACEABILITY", "docs/deliverables/03-requirements/rtm.json"),
        ("DESIGN_TRACEABILITY", "docs/deliverables/04-design/design-traceability-register.json"),
        ("IMPLEMENTATION_MANIFEST", "docs/deliverables/05-implementation/implementation-manifest.json"),
        ("MODULE_REGISTER", "docs/deliverables/05-implementation/module-register.json"),
        ("EXACT257_R013_LEDGER", "docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-exact257-successor-r013/phase1-exact257-successor-ledger-r013.json"),
        ("EXACT257_R013_EVIDENCE", "docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-exact257-successor-r013/evidence.json"),
        ("EXACT257_R013_CHECK_RECEIPT", "docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-exact257-successor-r013/phase1-exact257-successor-check-receipt-r013.json"),
    )
    return [{"role": role, "path": path, "binding_stage": "POST_IMPLEMENTATION_PRE_REVIEW"} for role, path in from_paths]


def build_pre_review_outputs(
    *,
    root: Path = ROOT,
    lane_observations: Mapping[str, Mapping[str, Any]],
    lane_raw_outputs: Mapping[str, bytes],
    implementation_paths: Sequence[str] = IMPLEMENTATION_PATHS,
    verification_input_paths: Sequence[str] = VERIFICATION_INPUT_PATHS,
    authority: Mapping[str, Any] | None = None,
) -> dict[Path, str]:
    root = root.resolve(strict=True)
    require(tuple(implementation_paths) == tuple(dict.fromkeys(implementation_paths)), "implementation scope order/uniqueness differs")
    require(set(lane_observations) == set(LANE_BY_ID), "lane observation set differs")
    require(set(lane_raw_outputs) == set(LANE_BY_ID), "lane raw-output set differs")
    auth = deepcopy(dict(authority)) if authority is not None else validate_authority(root)
    implementation_rows = snapshot_paths(root, implementation_paths, "FP008_IMPLEMENTATION_SOURCE")
    verification_rows = snapshot_paths(root, verification_input_paths, "FP008_VERIFICATION_INPUT")
    implementation_content_set = object_sha256(implementation_rows)
    verification_input_content_set = object_sha256(verification_rows)
    observations = {
        lane.lane_id: _validate_lane_observation(
            lane,
            lane_observations[lane.lane_id],
            auth["gate_ended_at"],
            lane_raw_outputs[lane.lane_id],
        )
        for lane in LANES
    }
    logs: dict[Path, str] = {}
    for lane in LANES:
        raw = lane_raw_outputs[lane.lane_id]
        require(type(raw) is bytes, f"{lane.lane_id} raw output must be bytes")
        observation = observations[lane.lane_id]
        require(len(raw) == observation["raw_output_byte_length"], f"{lane.lane_id} raw output byte length differs")
        require(bytes_sha256(raw) == observation["raw_output_sha256"], f"{lane.lane_id} raw output digest differs")
        try:
            logs[lane.log_rel] = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise BuildError(f"{lane.lane_id} raw output is not UTF-8") from exc
    receipts = {
        lane.receipt_rel: json_text(_lane_receipt(lane, observations[lane.lane_id], lane_raw_outputs[lane.lane_id], auth, implementation_content_set))
        for lane in LANES
    }
    receipt_manifest = [
        {
            "lane_id": lane.lane_id,
            "path": lane.receipt_rel.as_posix(),
            "sha256": bytes_sha256(receipts[lane.receipt_rel].encode("utf-8")),
            "log_path": lane.log_rel.as_posix(),
            "log_sha256": bytes_sha256(lane_raw_outputs[lane.lane_id]),
        }
        for lane in LANES
    ]
    observed_at = max(observation["ended_at"] for observation in observations.values())
    implementation = sealed(
        {
            "schema_version": "walksafe.fp008-implementation-record.v1",
            "document_id": "WS-FP008-ADMIN-REVIEW-DELIVERY-IMPLEMENTATION-20260803-001",
            "goal_id": GOAL_ID,
            "policy_id": POLICY_ID,
            "gap_id": GAP_ID,
            "kind": "IMPLEMENTATION_RECORD",
            "status": "PASS",
            "observed_at": observed_at,
            "scope_kind": "EXACT_ORDERED_FP008_IMPLEMENTATION_PATH_SET",
            "exact_path_count": len(implementation_rows),
            "changed_artifacts": implementation_rows,
            "implementation_content_set_sha256": implementation_content_set,
            "authority_bindings": {
                "goal": deepcopy(auth["goal_binding"]),
                "initial_exact9_gate": deepcopy(auth["initial_gate_binding"]),
                "seq48_resume_gate": deepcopy(auth["resume_gate_binding"]),
                "policy_contract": deepcopy(auth["policy_contract_binding"]),
            },
            "implemented_controls": [
                "separate Android admin application identity, signing boundary and session namespace",
                "registered-device proof plus additional designated-admin authentication",
                "approve, reject and duplicate review decisions with reason, administrator and time",
                "manual institution-delivery receipt and status recording with later lookup",
                "admin-path failure isolation from a healthy user walking session",
            ],
            "completion_boundary": completion_boundary(),
        },
        "implementation_record_content_sha256",
    )
    verification = sealed(
        {
            "schema_version": "walksafe.fp008-verification-result.v1",
            "document_id": "WS-FP008-ADMIN-REVIEW-DELIVERY-VERIFICATION-20260803-001",
            "goal_id": GOAL_ID,
            "kind": "VERIFICATION_RESULT",
            "status": "PASS",
            "observed_at": observed_at,
            "implementation_content_set_sha256": implementation_content_set,
            "verification_input_manifest": verification_rows,
            "verification_input_content_set_sha256": verification_input_content_set,
            "lane_receipts": receipt_manifest,
            "internal_lane_count": len(LANES),
            "completion_boundary": completion_boundary(),
        },
        "verification_result_content_sha256",
    )
    implementation_text = json_text(implementation)
    verification_text = json_text(verification)
    producer_results = {
        "IMPLEMENTATION_RECORD": {
            "path": IMPLEMENTATION_REL.as_posix(),
            "sha256": bytes_sha256(implementation_text.encode("utf-8")),
        },
        "VERIFICATION_RESULT": {
            "path": VERIFICATION_REL.as_posix(),
            "sha256": bytes_sha256(verification_text.encode("utf-8")),
        },
    }
    successor = sealed(
        {
            "schema_version": "walksafe.fp008-successor-trace.v1",
            "document_id": "WS-FP008-ADMIN-REVIEW-DELIVERY-SUCCESSOR-20260803-001",
            "goal_id": GOAL_ID,
            "kind": "SUCCESSOR_TRACE",
            "status": "INTERNAL_VERIFICATION_RECORDED_REVIEW_PENDING",
            "observed_at": observed_at,
            "producer_results": producer_results,
            "downstream_consumer_contracts": consumer_contract_manifest(),
            "consumer_physical_hashes_bound": False,
            "cycle_boundary": "R024, six artifact successors and exact257 R013 consume producer results and never feed them",
            "next_single_action": "MATERIALIZE_DOWNSTREAM_CONSUMERS_THEN_INDEPENDENT_REVIEW",
            "completion_boundary": completion_boundary(),
        },
        "successor_trace_content_sha256",
    )
    successor_text = json_text(successor)
    subject = sealed(
        {
            "schema_version": "walksafe.fp008-review-subject.v1",
            "document_id": "WS-FP008-ADMIN-REVIEW-DELIVERY-REVIEW-SUBJECT-20260803-001",
            "goal_id": GOAL_ID,
            "kind": "INTERNAL_REVIEW_SUBJECT",
            "status": "REVIEW_PENDING",
            "observed_at": observed_at,
            "reviewed_result_sha256_by_kind": {
                "IMPLEMENTATION_RECORD": producer_results["IMPLEMENTATION_RECORD"]["sha256"],
                "VERIFICATION_RESULT": producer_results["VERIFICATION_RESULT"]["sha256"],
                "SUCCESSOR_TRACE": bytes_sha256(successor_text.encode("utf-8")),
            },
            "implementation_scope": {
                "scope_kind": implementation["scope_kind"],
                "exact_path_count": len(implementation_rows),
                "paths": [row["path"] for row in implementation_rows],
                "content_set_sha256": implementation_content_set,
            },
            "verification_receipts": receipt_manifest,
            "policy_contract_binding": deepcopy(auth["policy_contract_binding"]),
            "downstream_consumer_contracts": consumer_contract_manifest(),
            "consumer_physical_hashes_bound": False,
            "reviewer_must_be_independent_of_executor": True,
            "completion_boundary": completion_boundary(),
        },
        "review_subject_content_sha256",
    )
    return {
        **logs,
        **receipts,
        IMPLEMENTATION_REL: implementation_text,
        VERIFICATION_REL: verification_text,
        SUCCESSOR_REL: successor_text,
        REVIEW_SUBJECT_REL: json_text(subject),
    }


def load_lane_observations(directory: Path) -> tuple[dict[str, dict[str, Any]], dict[str, bytes]]:
    directory = directory.resolve(strict=True)
    require(directory.is_dir(), "lane observation directory is not a directory")
    result: dict[str, dict[str, Any]] = {}
    raw_outputs: dict[str, bytes] = {}
    for lane in LANES:
        path = directory / f"{lane.receipt_name}.json"
        require(path.parent == directory and path.is_file() and not path.is_symlink(), f"lane observation missing: {path.name}")
        result[lane.lane_id] = strict_json_bytes(path.read_bytes(), path.name)
        log = directory / f"{lane.receipt_name}.log"
        require(log.parent == directory and log.is_file() and not log.is_symlink(), f"lane raw output missing: {log.name}")
        raw_outputs[lane.lane_id] = log.read_bytes()
    expected_names = {name for lane in LANES for name in (f"{lane.receipt_name}.json", f"{lane.receipt_name}.log")}
    require({path.name for path in directory.iterdir()} == expected_names, "lane observation directory has extra entries")
    return result, raw_outputs


def _fsync_output_directory(path: Path) -> None:
    descriptor = os.open(
        path,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _add_only_stage_relative(relative: Path) -> Path:
    token = bytes_sha256(relative.as_posix().encode("utf-8"))
    return Path(f".walksafe-fp008-add-only-{token}.stage")


def _directory_open_flags() -> int:
    directory = getattr(os, "O_DIRECTORY", None)
    nofollow = getattr(os, "O_NOFOLLOW", None)
    require(type(directory) is int and directory != 0, "O_DIRECTORY is required")
    require(type(nofollow) is int and nofollow != 0, "O_NOFOLLOW is required")
    return os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | directory | nofollow


def _inode_identity(info: os.stat_result) -> tuple[int, int]:
    return info.st_dev, info.st_ino


def _stable_file_identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
        info.st_size,
        info.st_mtime_ns,
        info.st_ctime_ns,
    )


def _write_add_only_stage_at(
    directory_descriptor: int, name: str, raw: bytes
) -> None:
    require("/" not in name and name not in {"", ".", ".."}, "unsafe stage name")
    anonymous = getattr(os, "O_TMPFILE", None)
    require(type(anonymous) is int and anonymous != 0, "O_TMPFILE is required")
    flags = (
        os.O_RDWR
        | anonymous
        | getattr(os, "O_CLOEXEC", 0)
    )
    descriptor = os.open(".", flags, 0o600, dir_fd=directory_descriptor)
    failure: BaseException | None = None
    try:
        opened = os.fstat(descriptor)
        require(
            stat.S_ISREG(opened.st_mode)
            and opened.st_nlink == 0
            and stat.S_IMODE(opened.st_mode) == 0o600,
            f"anonymous add-only stage authority differs: {name}",
        )
        written = 0
        while written < len(raw):
            count = os.write(descriptor, raw[written:])
            require(count > 0, f"short add-only stage write: {name}")
            written += count
        os.fsync(descriptor)
        after_write = _require_open_regular_bytes(
            descriptor, raw, f"anonymous stage for {name}"
        )
        require(
            _inode_identity(after_write) == _inode_identity(opened)
            and after_write.st_nlink == 0,
            f"anonymous add-only stage changed: {name}",
        )
        proc_source = f"/proc/self/fd/{descriptor}"
        require(
            _inode_identity(os.stat(proc_source, follow_symlinks=True))
            == _inode_identity(opened),
            f"anonymous add-only stage FD binding differs: {name}",
        )
        os.link(
            proc_source,
            name,
            dst_dir_fd=directory_descriptor,
            follow_symlinks=True,
        )
        # The recovery name becomes durable before any post-link operation can
        # fail.  A later retry may therefore rely on this exact named stage.
        os.fsync(directory_descriptor)
        linked_entry = os.stat(
            name, dir_fd=directory_descriptor, follow_symlinks=False
        )
        linked_descriptor = os.fstat(descriptor)
        require(
            _inode_identity(linked_entry) == _inode_identity(opened)
            and _inode_identity(linked_descriptor) == _inode_identity(opened)
            and linked_descriptor.st_nlink == 1,
            f"linked add-only stage authority differs: {name}",
        )
    except BaseException as exc:
        failure = exc
    try:
        os.close(descriptor)
    except BaseException as exc:
        if failure is None:
            failure = exc
    if failure is not None:
        raise failure


def _write_add_only_stage(path: Path, raw: bytes) -> None:
    parent_descriptor = os.open(path.parent, _directory_open_flags())
    try:
        _write_add_only_stage_at(parent_descriptor, path.name, raw)
        os.fsync(parent_descriptor)
    finally:
        os.close(parent_descriptor)


def _open_output_parent(
    root_descriptor: int, relative: Path, *, create: bool
) -> tuple[int, list[int], list[tuple[int, str, tuple[int, int]]]]:
    current_descriptor = root_descriptor
    opened_descriptors: list[int] = []
    ancestry: list[tuple[int, str, tuple[int, int]]] = []
    try:
        for component in relative.parent.parts:
            try:
                child_descriptor = os.open(
                    component,
                    _directory_open_flags(),
                    dir_fd=current_descriptor,
                )
            except FileNotFoundError:
                require(create, f"output parent missing: {relative}")
                os.mkdir(component, 0o700, dir_fd=current_descriptor)
                os.fsync(current_descriptor)
                child_descriptor = os.open(
                    component,
                    _directory_open_flags(),
                    dir_fd=current_descriptor,
                )
            except OSError as exc:
                raise BuildError(
                    f"cannot safely open output parent: {relative}: {exc}"
                ) from exc
            opened_descriptors.append(child_descriptor)
            child_info = os.fstat(child_descriptor)
            entry_info = os.stat(
                component, dir_fd=current_descriptor, follow_symlinks=False
            )
            require(
                stat.S_ISDIR(child_info.st_mode)
                and stat.S_ISDIR(entry_info.st_mode)
                and _inode_identity(child_info) == _inode_identity(entry_info),
                f"output parent authority differs: {relative}",
            )
            ancestry.append(
                (current_descriptor, component, _inode_identity(child_info))
            )
            current_descriptor = child_descriptor
        return current_descriptor, opened_descriptors, ancestry
    except BaseException:
        _close_descriptors(opened_descriptors)
        raise


def _validate_output_ancestry(
    root: Path,
    root_descriptor: int,
    ancestry: Sequence[tuple[int, str, tuple[int, int]]],
    relative: Path,
) -> None:
    root_entry = root.lstat()
    require(
        stat.S_ISDIR(root_entry.st_mode)
        and not stat.S_ISLNK(root_entry.st_mode)
        and _inode_identity(root_entry)
        == _inode_identity(os.fstat(root_descriptor)),
        f"output root identity differs: {relative}",
    )
    for parent_descriptor, component, expected_identity in ancestry:
        try:
            current = os.stat(
                component,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
        except OSError as exc:
            raise BuildError(
                f"output parent changed during publication: {relative}"
            ) from exc
        require(
            stat.S_ISDIR(current.st_mode)
            and _inode_identity(current) == expected_identity,
            f"output parent changed during publication: {relative}",
        )


def _read_open_regular(
    directory_descriptor: int,
    name: str,
    expected: bytes,
    label: str,
) -> tuple[int, os.stat_result]:
    nonblock = getattr(os, "O_NONBLOCK", None)
    require(type(nonblock) is int and nonblock != 0, "O_NONBLOCK is required")
    try:
        descriptor = os.open(
            name,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
            | nonblock,
            dir_fd=directory_descriptor,
        )
    except OSError as exc:
        raise BuildError(f"cannot safely open {label}: {exc}") from exc
    try:
        before = os.fstat(descriptor)
        require(
            stat.S_ISREG(before.st_mode)
            and before.st_uid == os.getuid()
            and stat.S_IMODE(before.st_mode) == 0o600
            and before.st_size == len(expected),
            f"file authority differs: {label}",
        )
        after = _require_open_regular_bytes(descriptor, expected, label)
        entry = os.stat(name, dir_fd=directory_descriptor, follow_symlinks=False)
        require(
            _inode_identity(entry) == _inode_identity(before),
            f"file identity changed while reading: {label}",
        )
        return descriptor, after
    except BaseException:
        os.close(descriptor)
        raise


def _require_open_regular_bytes(
    descriptor: int, expected: bytes, label: str
) -> os.stat_result:
    before = os.fstat(descriptor)
    require(stat.S_ISREG(before.st_mode), f"not a regular file: {label}")
    os.lseek(descriptor, 0, os.SEEK_SET)
    chunks: list[bytes] = []
    while chunk := os.read(descriptor, 1024 * 1024):
        chunks.append(chunk)
    after = os.fstat(descriptor)
    require(
        _stable_file_identity(after) == _stable_file_identity(before),
        f"file identity changed while reading: {label}",
    )
    require(b"".join(chunks) == expected, f"existing output differs: {label}")
    return after


def _try_read_open_regular(
    directory_descriptor: int,
    name: str,
    expected: bytes,
    label: str,
) -> tuple[int, os.stat_result] | None:
    try:
        os.stat(name, dir_fd=directory_descriptor, follow_symlinks=False)
    except FileNotFoundError:
        return None
    return _read_open_regular(directory_descriptor, name, expected, label)


def _close_descriptors(descriptors: Sequence[int]) -> None:
    failure: OSError | None = None
    for descriptor in reversed(descriptors):
        try:
            os.close(descriptor)
        except OSError as exc:
            if failure is None:
                failure = exc
    if failure is not None:
        raise failure


def _rollback_linked_target(
    parent_descriptor: int,
    target_name: str,
    stage_descriptor: int,
) -> None:
    """Remove only the entry just linked from the held destination directory."""
    try:
        current = os.stat(
            target_name,
            dir_fd=parent_descriptor,
            follow_symlinks=False,
        )
    except FileNotFoundError:
        os.fsync(parent_descriptor)
        return
    stage_info = os.fstat(stage_descriptor)
    if (
        stat.S_ISREG(current.st_mode)
        and _inode_identity(current) == _inode_identity(stage_info)
        and stage_info.st_nlink >= 2
    ):
        os.unlink(target_name, dir_fd=parent_descriptor)
    # Persist either the rollback or the fact that a concurrently substituted
    # foreign target was deliberately left untouched.
    os.fsync(parent_descriptor)


def _compensate_held_target(
    parent_descriptor: int,
    target_name: str,
    source_descriptor: int,
    expected: bytes,
) -> None:
    """Restore the held target if a post-rollback recovery-stage check fails."""
    source_info = _require_open_regular_bytes(
        source_descriptor, expected, f"compensation source for {target_name}"
    )
    require(
        source_info.st_uid == os.getuid()
        and stat.S_IMODE(source_info.st_mode) == 0o600,
        f"compensation source authority differs: {target_name}",
    )
    try:
        current = os.stat(
            target_name,
            dir_fd=parent_descriptor,
            follow_symlinks=False,
        )
    except FileNotFoundError:
        proc_source = f"/proc/self/fd/{source_descriptor}"
        require(
            _inode_identity(os.stat(proc_source, follow_symlinks=True))
            == _inode_identity(source_info),
            f"compensation source FD binding differs: {target_name}",
        )
        os.link(
            proc_source,
            target_name,
            dst_dir_fd=parent_descriptor,
            follow_symlinks=True,
        )
    else:
        require(
            _inode_identity(current) == _inode_identity(source_info),
            f"foreign target blocks rollback compensation: {target_name}",
        )
    os.fsync(parent_descriptor)
    target_descriptor, target_info = _read_open_regular(
        parent_descriptor,
        target_name,
        expected,
        target_name,
    )
    try:
        require(
            _inode_identity(target_info) == _inode_identity(source_info),
            f"compensated target identity differs: {target_name}",
        )
    finally:
        os.close(target_descriptor)


def _restore_stage_then_rollback_target(
    root_descriptor: int,
    stage_name: str,
    parent_descriptor: int,
    target_name: str,
    source_descriptor: int,
    expected: bytes,
) -> None:
    """Restore durable recovery authority before removing our displaced target."""
    source_info = _require_open_regular_bytes(
        source_descriptor, expected, f"recovery source for {target_name}"
    )
    require(
        source_info.st_uid == os.getuid()
        and stat.S_IMODE(source_info.st_mode) == 0o600
        and source_info.st_nlink >= 1,
        f"recovery source authority differs: {target_name}",
    )
    try:
        current_stage = os.stat(
            stage_name,
            dir_fd=root_descriptor,
            follow_symlinks=False,
        )
    except FileNotFoundError:
        proc_source = f"/proc/self/fd/{source_descriptor}"
        require(
            _inode_identity(os.stat(proc_source, follow_symlinks=True))
            == _inode_identity(source_info),
            f"recovery source FD binding differs: {target_name}",
        )
        os.link(
            proc_source,
            stage_name,
            dst_dir_fd=root_descriptor,
            follow_symlinks=True,
        )
    else:
        require(
            _inode_identity(current_stage) == _inode_identity(source_info),
            f"foreign recovery stage blocks rollback: {target_name}",
        )
    os.fsync(root_descriptor)
    restored_descriptor, restored_info = _read_open_regular(
        root_descriptor,
        stage_name,
        expected,
        stage_name,
    )
    try:
        require(
            _inode_identity(restored_info) == _inode_identity(source_info)
            and restored_info.st_nlink >= 2,
            f"restored recovery stage changed before rollback: {target_name}",
        )
    finally:
        os.close(restored_descriptor)
    try:
        _rollback_linked_target(
            parent_descriptor, target_name, source_descriptor
        )
        stage_descriptor, stage_info = _read_open_regular(
            root_descriptor,
            stage_name,
            expected,
            stage_name,
        )
        try:
            require(
                _inode_identity(stage_info) == _inode_identity(source_info)
                and stage_info.st_nlink == 1,
                f"restored recovery stage has an unknown alias: {target_name}",
            )
        finally:
            os.close(stage_descriptor)
    except BaseException:
        _compensate_held_target(
            parent_descriptor,
            target_name,
            source_descriptor,
            expected,
        )
        raise


def _validate_final_targets(
    root: Path,
    root_descriptor: int,
    outputs: Mapping[Path, str],
) -> None:
    for relative, content in outputs.items():
        parent_descriptor, parent_descriptors, ancestry = _open_output_parent(
            root_descriptor, relative, create=False
        )
        target_descriptor: int | None = None
        try:
            _validate_output_ancestry(root, root_descriptor, ancestry, relative)
            target_descriptor, target_info = _read_open_regular(
                parent_descriptor,
                relative.name,
                content.encode("utf-8"),
                relative.as_posix(),
            )
            require(
                target_info.st_nlink == 1,
                f"final output has an unknown hard-link alias: {relative}",
            )
            current = os.stat(
                relative.name,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
            require(
                _inode_identity(current) == _inode_identity(target_info),
                f"final output entry changed: {relative}",
            )
            _validate_output_ancestry(root, root_descriptor, ancestry, relative)
        finally:
            _close_descriptors(
                tuple(parent_descriptors)
                + (() if target_descriptor is None else (target_descriptor,))
            )


def write_or_check_outputs(root: Path, outputs: Mapping[Path, str], *, write: bool) -> None:
    root = root.resolve(strict=True)
    root_before_open = root.lstat()
    require(
        stat.S_ISDIR(root_before_open.st_mode)
        and not stat.S_ISLNK(root_before_open.st_mode),
        "output root authority differs before open",
    )
    stage_paths = tuple(_add_only_stage_relative(relative) for relative in outputs)
    require(
        len(stage_paths) == len(set(stage_paths)),
        "deterministic add-only stage names are not unique",
    )
    require(
        set(outputs).isdisjoint(stage_paths),
        "requested output collides with the internal stage namespace",
    )
    for relative, content in outputs.items():
        _validate_relative(relative)
        require(type(content) is str, f"output content must be text: {relative}")
        require(
            not (
                relative.parent == Path(".")
                and relative.name.startswith(".walksafe-fp008-add-only-")
                and relative.name.endswith(".stage")
            ),
            f"internal stage namespace is not publishable: {relative}",
        )
        lower = relative.as_posix().lower()
        require(
            not any(fragment in lower for fragment in FORBIDDEN_PUBLICATION_FRAGMENTS),
            f"forbidden publication target: {relative}",
        )
    if not write:
        for relative, content in outputs.items():
            require(
                read_bytes(root, relative) == content.encode("utf-8"),
                f"existing output differs: {relative}",
            )
        return

    root_descriptor = os.open(root, _directory_open_flags())
    try:
        require(
            _inode_identity(os.fstat(root_descriptor))
            == _inode_identity(root_before_open),
            "output root changed while opening",
        )
        fcntl.flock(root_descriptor, fcntl.LOCK_EX)
        _validate_output_ancestry(root, root_descriptor, (), Path("."))
        missing: list[Path] = []
        for relative, content in outputs.items():
            stage_relative = _add_only_stage_relative(relative)
            require(stage_relative.parent == Path("."), "stage escaped repository root")
            expected = content.encode("utf-8")
            parent_descriptor, parent_descriptors, ancestry = _open_output_parent(
                root_descriptor, relative, create=True
            )
            target_descriptor: int | None = None
            stage_descriptor: int | None = None
            try:
                _validate_output_ancestry(root, root_descriptor, ancestry, relative)
                target_opened = _try_read_open_regular(
                    parent_descriptor,
                    relative.name,
                    expected,
                    relative.as_posix(),
                )
                if target_opened is not None:
                    target_descriptor, target_info = target_opened
                stage_opened = _try_read_open_regular(
                    root_descriptor,
                    stage_relative.name,
                    expected,
                    stage_relative.as_posix(),
                )
                if stage_opened is not None:
                    stage_descriptor, stage_info = stage_opened
                if target_opened is None:
                    if stage_opened is None:
                        _write_add_only_stage_at(
                            root_descriptor, stage_relative.name, expected
                        )
                        os.fsync(root_descriptor)
                    else:
                        require(
                            stage_info.st_nlink == 1,
                            f"uncommitted add-only stage link count differs: {relative}",
                        )
                        # Recovery may follow a prior process failure after the
                        # named link but before its directory fsync.  Re-sync it
                        # unconditionally before creating the final hard link.
                        os.fsync(root_descriptor)
                        stage_info = _require_open_regular_bytes(
                            stage_descriptor,
                            expected,
                            stage_relative.as_posix(),
                        )
                        require(
                            stage_info.st_nlink == 1,
                            f"uncommitted add-only stage changed: {relative}",
                        )
                    missing.append(relative)
                    continue

                if stage_opened is not None:
                    require(
                        _inode_identity(stage_info)
                        == _inode_identity(target_info)
                        and stage_info.st_nlink == 2
                        and target_info.st_nlink == 2,
                        f"add-only recovery link identity differs: {relative}",
                    )
                    # The prior link may have committed immediately before a
                    # crash or fsync failure.  Make the target entry durable
                    # before removing the only already-durable root link.
                    os.fsync(parent_descriptor)
                    target_after = _require_open_regular_bytes(
                        target_descriptor, expected, relative.as_posix()
                    )
                    stage_after = _require_open_regular_bytes(
                        stage_descriptor, expected, stage_relative.as_posix()
                    )
                    require(
                        _inode_identity(target_after)
                        == _inode_identity(stage_after)
                        and target_after.st_nlink == 2
                        and stage_after.st_nlink == 2,
                        f"add-only recovery link changed: {relative}",
                    )
                    _validate_output_ancestry(
                        root, root_descriptor, ancestry, relative
                    )
                    current_target = os.stat(
                        relative.name,
                        dir_fd=parent_descriptor,
                        follow_symlinks=False,
                    )
                    current_stage = os.stat(
                        stage_relative.name,
                        dir_fd=root_descriptor,
                        follow_symlinks=False,
                    )
                    require(
                        _inode_identity(current_target)
                        == _inode_identity(target_after)
                        and _inode_identity(current_stage)
                        == _inode_identity(stage_after),
                        f"add-only recovery entry changed: {relative}",
                    )
                    try:
                        os.unlink(stage_relative.name, dir_fd=root_descriptor)
                        os.fsync(root_descriptor)
                        require(
                            os.fstat(target_descriptor).st_nlink == 1,
                            f"add-only recovered target link count differs: {relative}",
                        )
                        reopened_descriptor, reopened_info = _read_open_regular(
                            parent_descriptor,
                            relative.name,
                            expected,
                            relative.as_posix(),
                        )
                        try:
                            require(
                                _inode_identity(reopened_info)
                                == _inode_identity(target_after)
                                and reopened_info.st_nlink == 1,
                                f"add-only recovered target entry differs: {relative}",
                            )
                        finally:
                            os.close(reopened_descriptor)
                        _validate_output_ancestry(
                            root, root_descriptor, ancestry, relative
                        )
                    except BaseException:
                        _restore_stage_then_rollback_target(
                            root_descriptor,
                            stage_relative.name,
                            parent_descriptor,
                            relative.name,
                            stage_descriptor,
                            expected,
                        )
                        raise
                else:
                    require(
                        target_info.st_nlink == 1,
                        f"existing output has an unknown hard-link alias: {relative}",
                    )
            finally:
                _close_descriptors(
                    tuple(parent_descriptors)
                    + (() if target_descriptor is None else (target_descriptor,))
                    + (() if stage_descriptor is None else (stage_descriptor,))
                )

        for relative in missing:
            stage_relative = _add_only_stage_relative(relative)
            expected = outputs[relative].encode("utf-8")
            parent_descriptor, parent_descriptors, ancestry = _open_output_parent(
                root_descriptor, relative, create=False
            )
            stage_descriptor: int | None = None
            target_descriptor: int | None = None
            try:
                _validate_output_ancestry(root, root_descriptor, ancestry, relative)
                appeared = _try_read_open_regular(
                    parent_descriptor,
                    relative.name,
                    expected,
                    relative.as_posix(),
                )
                if appeared is not None:
                    target_descriptor, _ = appeared
                require(
                    appeared is None,
                    f"output appeared before add-only commit: {relative}",
                )
                stage_descriptor, stage_info = _read_open_regular(
                    root_descriptor,
                    stage_relative.name,
                    expected,
                    stage_relative.as_posix(),
                )
                require(
                    stage_info.st_nlink == 1,
                    f"add-only stage link count differs: {relative}",
                )
                proc_source = f"/proc/self/fd/{stage_descriptor}"
                require(
                    _inode_identity(os.stat(proc_source, follow_symlinks=True))
                    == _inode_identity(stage_info),
                    f"add-only stage FD binding differs: {relative}",
                )
                try:
                    # Source and destination are descriptor-anchored: the source
                    # cannot be replaced after validation and no intermediate
                    # destination component is resolved again by pathname.
                    os.link(
                        proc_source,
                        relative.name,
                        dst_dir_fd=parent_descriptor,
                        follow_symlinks=True,
                    )
                except FileExistsError as exc:
                    raise BuildError(
                        f"output appeared before add-only commit: {relative}"
                    ) from exc
                # A failed target-directory fsync is recovered on retry while
                # both links remain.  Do not attempt rollback before durability
                # of the destination directory is known.
                os.fsync(parent_descriptor)
                try:
                    target_descriptor, target_info = _read_open_regular(
                        parent_descriptor,
                        relative.name,
                        expected,
                        relative.as_posix(),
                    )
                    stage_after = _require_open_regular_bytes(
                        stage_descriptor, expected, stage_relative.as_posix()
                    )
                    require(
                        _inode_identity(stage_after) == _inode_identity(target_info)
                        and stage_after.st_nlink == 2
                        and target_info.st_nlink == 2,
                        f"add-only link identity differs: {relative}",
                    )
                    _validate_output_ancestry(
                        root, root_descriptor, ancestry, relative
                    )
                    current_stage = os.stat(
                        stage_relative.name,
                        dir_fd=root_descriptor,
                        follow_symlinks=False,
                    )
                    require(
                        _inode_identity(current_stage)
                        == _inode_identity(stage_info),
                        f"add-only stage changed during commit: {relative}",
                    )
                except BaseException:
                    _rollback_linked_target(
                        parent_descriptor, relative.name, stage_descriptor
                    )
                    raise
                try:
                    os.unlink(stage_relative.name, dir_fd=root_descriptor)
                    os.fsync(root_descriptor)
                    require(
                        os.fstat(target_descriptor).st_nlink == 1,
                        f"add-only committed target link count differs: {relative}",
                    )
                    reopened_descriptor, reopened_info = _read_open_regular(
                        parent_descriptor,
                        relative.name,
                        expected,
                        relative.as_posix(),
                    )
                    try:
                        require(
                            _inode_identity(reopened_info)
                            == _inode_identity(target_info)
                            and reopened_info.st_nlink == 1,
                            f"add-only committed target entry differs: {relative}",
                        )
                    finally:
                        os.close(reopened_descriptor)
                    _validate_output_ancestry(
                        root, root_descriptor, ancestry, relative
                    )
                except BaseException:
                    _restore_stage_then_rollback_target(
                        root_descriptor,
                        stage_relative.name,
                        parent_descriptor,
                        relative.name,
                        stage_descriptor,
                        expected,
                    )
                    raise
            finally:
                _close_descriptors(
                    tuple(parent_descriptors)
                    + (() if target_descriptor is None else (target_descriptor,))
                    + (() if stage_descriptor is None else (stage_descriptor,))
                )
        _validate_final_targets(root, root_descriptor, outputs)
        _validate_output_ancestry(root, root_descriptor, (), Path("."))
    finally:
        try:
            fcntl.flock(root_descriptor, fcntl.LOCK_UN)
        finally:
            os.close(root_descriptor)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--lane-observation-dir", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        observations, raw_outputs = load_lane_observations(args.lane_observation_dir)
        outputs = build_pre_review_outputs(root=args.root, lane_observations=observations, lane_raw_outputs=raw_outputs)
        write_or_check_outputs(args.root, outputs, write=args.write)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"FP-008 admin review/delivery trace: FAIL: {exc}")
        return 1
    print(f"FP-008 admin review/delivery trace: PASS outputs={len(outputs)} mode={'WRITE' if args.write else 'CHECK'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
