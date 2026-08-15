#!/usr/bin/env python3
"""Capture add-only internal verification for NPC single-admin recovery.

All four lanes must pass before any formal evidence is published.  The module
also exposes the exact lane and log contract used by the downstream trace
validator.  It intentionally has no dependency on that trace builder, so a
pending observation pin cannot block capture and the builder may safely import
this module.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime, timedelta
import fcntl
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shlex
import shutil
import stat
import subprocess
import sys
import sysconfig
import tempfile
from typing import Any, Callable, Mapping, Sequence
from zoneinfo import ZoneInfo


sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import (  # noqa: E402
    build_walksafe_fp008_admin_review_delivery_trace_20260803 as io_base,
)


TIMEZONE = ZoneInfo("Asia/Seoul")
KST_OFFSET = timedelta(hours=9)
GOAL_ID = "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001"
RESULT_DIR_REL = Path("docs/control/execution/goal-results") / GOAL_ID
V1_OBSERVATION_MANIFEST_REL = RESULT_DIR_REL / "verification-observations.json"
SUPERSEDED_V1_OBSERVATION_SHA256 = (
    "b07615579c4c65d156e4d21c020b21cf7d63ca0bf0a22a4337d03c5dd52f0525"
)
SUPERSEDED_V1_OBSERVATION_BYTE_COUNT = 18691
CORRECTION_DIR_REL = RESULT_DIR_REL / "verification-correction-v2"
OBSERVATION_MANIFEST_REL = CORRECTION_DIR_REL / "verification-observations-v2.json"
LOG_ROOT_REL = CORRECTION_DIR_REL / "logs"
TRANSACTION_JOURNAL_REL = CORRECTION_DIR_REL / ".publication-transaction.json"
PUBLICATION_STAGE_REL = RESULT_DIR_REL / ".verification-correction-v2.publish-stage"

OBSERVATION_SCHEMA = "walksafe.npc-single-admin-recovery-verification.v2"
LOG_SCHEMA = "walksafe.npc-single-admin-recovery-verification-lane.v2"
LOG_HEADER_MARKER = "WALKSAFE_NPC_VERIFICATION_LOG_BEGIN"
LOG_TAIL_MARKER = "WALKSAFE_NPC_VERIFICATION_LOG_END"
LOG_FIELD_PREFIX = "WALKSAFE_NPC_VERIFICATION_"
LOG_LIMIT_BYTES = 32 * 1024 * 1024
SOURCE_LIST_LIMIT_BYTES = 1024 * 1024
DATABASE_URL_REFERENCE = "${WALKSAFE_TEST_DATABASE_URL}"
DATABASE_STATE_MARKER = "WALKSAFE_NPC_DATABASE_STATE_RECEIPT "
DATABASE_PRE_MIGRATION_HEAD = "202608120001"
DATABASE_POST_MIGRATION_HEAD = "202608150002"
GIT_EXECUTABLE = "/usr/bin/git"
SNAPSHOT_DIRECTORY_MODE = 0o700
OUTPUT_FILE_MODE = 0o600
RUN_ID_RE = re.compile(r"^NPC-RECOVERY-[0-9a-f]{32}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
RUNNER_REL = Path("scripts/run_walksafe_npc_single_admin_recovery_verification_20260813.py")
EXECUTION_INPUT_EXCLUSION_POLICY = {
    "schema": "walksafe.execution-input-exclusion-policy.v1",
    "excluded_prefixes": [
        "daylog/",
        "docs/control/",
        "docs/deliverables/",
        "legacy/",
        "legacy1/",
        "legacy2/",
        "legacy3/",
    ],
    "excluded_exact": [
        "docs/catalogs/repository-paths.json",
        "docs/catalogs/scripts.json",
        "docs/catalogs/tests.json",
    ],
    "excluded_name_prefixes": [".walksafe-"],
    "rationale": "Generated, control, evidence, legacy, and transaction outputs are not command execution inputs.",
}
SAFE_AMBIENT_ENVIRONMENT_KEYS = (
    "ANDROID_HOME",
    "ANDROID_SDK_ROOT",
    "GRADLE_USER_HOME",
    "HOME",
    "JAVA_HOME",
    "LANG",
    "LC_ALL",
    "PATH",
    "TMPDIR",
    "TZ",
)

def _production_executor_authority_factory() -> Callable[..., object]:
    """Hide the imported stdlib executor in a closure, not a mutable alias."""

    imported_run = subprocess.run

    def resolve() -> Callable[..., subprocess.CompletedProcess[bytes]]:
        require(
            subprocess.run is imported_run
            and _SYSTEM_SUBPROCESS_RUN is imported_run,
            "process subprocess executor was replaced before production capture",
        )
        return imported_run

    return resolve


_SYSTEM_SUBPROCESS_RUN = subprocess.run
_resolve_production_executor = _production_executor_authority_factory()


def _bind_production_executor(
    resolve: Callable[[], Callable[..., subprocess.CompletedProcess[bytes]]],
) -> Callable[[Callable[..., dict[str, object]]], Callable[..., dict[str, object]]]:
    """Bind production execution authority in a lexical closure."""

    def decorate(
        implementation: Callable[..., dict[str, object]],
    ) -> Callable[..., dict[str, object]]:
        def bound(
            *, root: Path, source_path_file: Path | None = None
        ) -> dict[str, object]:
            return implementation(
                root=root,
                source_path_file=source_path_file,
                production_run=resolve(),
            )

        bound.__name__ = "capture_and_publish"
        bound.__doc__ = implementation.__doc__
        return bound

    return decorate


_DIRECTORY_BINDING_CACHE: dict[
    tuple[str, str, tuple[tuple[str, tuple[int, ...]], ...]], dict[str, object]
] = {}
_EXTERNAL_BINDING_CACHE: dict[
    tuple[str, str, tuple[int, ...]], dict[str, object]
] = {}

ALLOWLISTED_SNAPSHOT_OUTPUT_PARTS = frozenset({".gradle", "build"})

OFFLINE_GATE_SOURCE = "scripts/walksafe_admin_high_risk_gate.py"
OFFLINE_GATE_TEST = "tests/test_walksafe_admin_high_risk_data_delete_gate.py"
PROVISIONING_CLI_SOURCE = "scripts/provision_walksafe_admin_device_key.py"
ISSUER_BINDING_CLI_SOURCE = "scripts/bind_walksafe_admin_credential_issuer_key.py"
ISSUER_BINDING_CLI_TEST = "tests/test_bind_walksafe_admin_credential_issuer_key.py"
OPENAPI_SOURCE = "contracts/walksafe.openapi.json"
NPC_PRODUCT_SOURCE_PATHS = (
    "apps/android/adminapp/README.md",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeviceProof.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGate.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminRecoveryCustodyState.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminRecoveryMessagePolicy.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityApi.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityApiException.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGateTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminRecoveryMessagePolicyTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityBoundaryStaticTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityControllerTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClientTest.java",
    "backend/.env.example",
    "backend/README.md",
    "backend/alembic/README.md",
    "backend/alembic/versions/202608120001_admin_recovery_custody.py",
    "backend/app/api/README.md",
    "backend/app/api/admin_security.py",
    "backend/app/api/health.py",
    "backend/app/api/uploads.py",
    "backend/app/config.py",
    "backend/app/database.py",
    "backend/app/field_test_security.py",
    "backend/app/main.py",
    "backend/app/models.py",
    "backend/app/openapi_contract.py",
    "backend/app/services/README.md",
    "backend/app/services/admin_credential_issuer_key.py",
    "backend/app/services/admin_device_proof.py",
    "backend/app/services/admin_security.py",
    "backend/app/services/report_image_keys.py",
    "backend/app/services/report_original_access.py",
    "backend/tests/README.md",
    "backend/tests/conftest.py",
    "backend/tests/test_admin_credential_issuer_binding.py",
    "backend/tests/test_admin_credential_issuer_key.py",
    "backend/tests/test_admin_device_proof.py",
    "backend/tests/test_admin_runtime_acl_hardening.py",
    "backend/tests/test_admin_security.py",
    "backend/tests/test_field_test_security.py",
    "backend/tests/test_fp008_postgres_integration.py",
    "backend/tests/test_health_readiness.py",
    "backend/tests/test_inference_process.py",
    "backend/tests/test_openapi_contract.py",
    "backend/tests/test_report_image_keyring.py",
    "backend/tests/test_report_original_access.py",
    "backend/tests/test_report_storage_reconciliation.py",
    OPENAPI_SOURCE,
    "deploy/config/walksafe-backend-migration.env.example",
    "deploy/config/walksafe-backend.env.example",
    "deploy/systemd/walksafe-admin-issuer-bind.service",
    "deploy/systemd/walksafe-backend-migrate.service",
    "deploy/systemd/walksafe-backend.service",
    "deploy/sysusers.d/walksafe-backend.conf",
    "docs/guides/code/android-admin.md",
    "docs/guides/security-privacy-guide.md",
    "docs/guides/testing-guide.md",
    ISSUER_BINDING_CLI_SOURCE,
    "scripts/build_walksafe_full_rc_20260713.py",
    PROVISIONING_CLI_SOURCE,
    "scripts/validate_walksafe_full_rc_20260713.py",
    OFFLINE_GATE_SOURCE,
    ISSUER_BINDING_CLI_TEST,
    OFFLINE_GATE_TEST,
    "tests/test_walksafe_full_rc_tooling.py",
)

_DATABASE_URL_RE = re.compile(
    rb"(?i)\bpostgres(?:ql)?(?:\+[a-z0-9_.-]+)?://[^\s\x00\"']+"
)
_USERINFO_URL_RE = re.compile(
    rb"(?i)\b[a-z][a-z0-9+.-]*://[^\s/:@]+:[^\s/@]+@[^\s\x00\"']+"
)
_ENV_REFERENCE_RE = re.compile(r"^\$\{([A-Z][A-Z0-9_]*)\}$")
_GRADLE_TERMINAL_RE = re.compile(r"^BUILD [^\r\n]+$", flags=re.MULTILINE)
_GRADLE_SUCCESS_RE = re.compile(
    r"^BUILD SUCCESSFUL(?:\s+in\s+.*)?$", flags=re.MULTILINE
)
_PYTEST_SUCCESS_RE = re.compile(
    r"^(?:=+\s*)?([1-9][0-9]*) passed in "
    r"[0-9]+(?:\.[0-9]+)?s(?: \([0-9]+:[0-5][0-9]:[0-5][0-9]\))?"
    r"(?:\s*=+)?$",
    flags=re.MULTILINE,
)
_PYTEST_TERMINAL_RE = re.compile(
    r"^(?:=+\s*)?(?:[0-9]+ (?:passed|failed|errors?|skipped|xfailed|xpassed)"
    r"|no tests ran)\b.*(?:\bin\s+[0-9]+(?:\.[0-9]+)?s)?(?:\s*=+)?$",
    flags=re.IGNORECASE | re.MULTILINE,
)


class VerificationError(RuntimeError):
    """Verification cannot safely publish an observation."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


@dataclass(frozen=True)
class CommandSpec:
    argv: tuple[str, ...]
    logical_environment: tuple[tuple[str, str], ...] = ()
    success_kind: str = "EXIT_ZERO"
    required_output_markers: tuple[str, ...] = ()

    def logical_command(self) -> str:
        prefix = " ".join(
            f"{key}={value}" for key, value in self.logical_environment
        )
        command = shlex.join(self.argv)
        return f"{prefix} {command}" if prefix else command

    def execution_argv(
        self,
        python_executable: str,
        environment: Mapping[str, str] | None = None,
    ) -> tuple[str, ...]:
        """Resolve the stable ``python3`` logical token to this runner's Python."""

        require(
            bool(python_executable) and "\x00" not in python_executable,
            "Python executable is invalid",
        )
        argv = (
            (python_executable, *self.argv[1:])
            if self.argv[0] == "python3"
            else self.argv
        )
        if "-Dorg.gradle.java.home=${JAVA_HOME}" not in argv:
            return argv
        java_home = (environment or {}).get("JAVA_HOME", "").strip()
        require(bool(java_home), "JAVA_HOME is required for Gradle execution")
        return tuple(
            f"-Dorg.gradle.java.home={java_home}"
            if value == "-Dorg.gradle.java.home=${JAVA_HOME}"
            else value
            for value in argv
        )


@dataclass(frozen=True)
class LaneSpec:
    lane_id: str
    cwd: str
    commands: tuple[CommandSpec, ...]

    @property
    def log_relative(self) -> Path:
        return LOG_ROOT_REL / f"{self.lane_id.lower()}.log"

    @property
    def logical_commands(self) -> tuple[str, ...]:
        return tuple(command.logical_command() for command in self.commands)


@dataclass(frozen=True)
class CommandResult:
    logical_command: str
    stdout: bytes
    stderr: bytes


@dataclass(frozen=True)
class LaneResult:
    spec: LaneSpec
    started_at: str
    ended_at: str
    commands: tuple[CommandResult, ...]
    log: bytes


@dataclass(frozen=True)
class VisibleFileBinding:
    path: str
    mode: int
    byte_count: int
    sha256: str


@dataclass(frozen=True)
class SnapshotFileBinding:
    source: VisibleFileBinding
    snapshot_identity: tuple[int, ...]


_PYTHON_ENVIRONMENT = (
    ("PYTHONDONTWRITEBYTECODE", "1"),
    ("PYTHONPATH", "."),
)
LANE_SPECS = (
    LaneSpec(
        "ADMIN_ANDROID_UNIT",
        "apps/android",
        (
            CommandSpec(
                (
                    "./gradlew",
                    "--offline",
                    "--no-daemon",
                    "--max-workers=1",
                    "--no-build-cache",
                    "--no-configuration-cache",
                    "--no-watch-fs",
                    "-Dorg.gradle.java.home=${JAVA_HOME}",
                    ":adminapp:testDebugUnitTest",
                    "--rerun-tasks",
                ),
                success_kind="GRADLE",
                required_output_markers=(":adminapp:testDebugUnitTest",),
            ),
        ),
    ),
    LaneSpec(
        "ADMIN_ANDROID_ASSEMBLE_LINT",
        "apps/android",
        (
            CommandSpec(
                (
                    "./gradlew",
                    "--offline",
                    "--no-daemon",
                    "--max-workers=1",
                    "--no-build-cache",
                    "--no-configuration-cache",
                    "--no-watch-fs",
                    "-Dorg.gradle.java.home=${JAVA_HOME}",
                    ":adminapp:assembleDebug",
                    ":adminapp:lintDebug",
                    "--rerun-tasks",
                ),
                success_kind="GRADLE",
                required_output_markers=(
                    ":adminapp:assembleDebug",
                    ":adminapp:lintDebug",
                ),
            ),
        ),
    ),
    LaneSpec(
        "BACKEND_RECOVERY_PYTEST",
        ".",
        (
            CommandSpec(
                (
                    "python3",
                    "-B",
                    "scripts/check_walksafe_test_database_20260713.py",
                ),
                _PYTHON_ENVIRONMENT,
                success_kind="DATABASE_PREFLIGHT",
            ),
            CommandSpec(
                (
                    "python3",
                    "-B",
                    RUNNER_REL.as_posix(),
                    "--emit-database-state-receipt",
                    "PRE_MIGRATION",
                ),
                (*_PYTHON_ENVIRONMENT, ("DATABASE_URL", DATABASE_URL_REFERENCE)),
                success_kind="DATABASE_STATE",
            ),
            CommandSpec(
                (
                    "python3",
                    "-B",
                    "-m",
                    "alembic",
                    "-c",
                    "backend/alembic.ini",
                    "upgrade",
                    "head",
                ),
                (*_PYTHON_ENVIRONMENT, ("DATABASE_URL", DATABASE_URL_REFERENCE)),
            ),
            CommandSpec(
                (
                    "python3",
                    "-B",
                    RUNNER_REL.as_posix(),
                    "--emit-database-state-receipt",
                    "AFTER_MIGRATION",
                ),
                (*_PYTHON_ENVIRONMENT, ("DATABASE_URL", DATABASE_URL_REFERENCE)),
                success_kind="DATABASE_STATE",
            ),
            CommandSpec(
                (
                    "python3",
                    "-B",
                    "-m",
                    "pytest",
                    "-p",
                    "no:cacheprovider",
                    "-q",
                    "backend/tests/test_admin_security.py",
                    "backend/tests/test_admin_credential_issuer_binding.py",
                    "backend/tests/test_admin_credential_issuer_key.py",
                    "backend/tests/test_admin_device_proof.py",
                    "backend/tests/test_admin_runtime_acl_hardening.py",
                    "backend/tests/test_field_test_security.py",
                    "backend/tests/test_fp008_postgres_integration.py",
                    "backend/tests/test_health_readiness.py",
                    "backend/tests/test_inference_process.py",
                    "backend/tests/test_openapi_contract.py",
                    "backend/tests/test_report_image_keyring.py",
                    "backend/tests/test_report_original_access.py",
                    "backend/tests/test_report_storage_reconciliation.py",
                    ISSUER_BINDING_CLI_TEST,
                    "tests/test_walksafe_full_rc_tooling.py",
                ),
                _PYTHON_ENVIRONMENT,
                success_kind="PYTEST",
            ),
            CommandSpec(
                (
                    "python3",
                    "-B",
                    RUNNER_REL.as_posix(),
                    "--emit-database-state-receipt",
                    "AFTER_TEST",
                ),
                (*_PYTHON_ENVIRONMENT, ("DATABASE_URL", DATABASE_URL_REFERENCE)),
                success_kind="DATABASE_STATE",
            ),
        ),
    ),
    LaneSpec(
        "RECOVERY_GATE_CLI_PYTEST",
        ".",
        (
            CommandSpec(
                (
                    "python3",
                    "-B",
                    "-m",
                    "pytest",
                    "-p",
                    "no:cacheprovider",
                    "-q",
                    OFFLINE_GATE_TEST,
                ),
                _PYTHON_ENVIRONMENT,
                success_kind="PYTEST",
            ),
        ),
    ),
)
LANE_IDS = tuple(spec.lane_id for spec in LANE_SPECS)
LANE_SPEC_BY_ID = {spec.lane_id: spec for spec in LANE_SPECS}
LANE_COMMANDS = {
    spec.lane_id: spec.logical_commands
    for spec in LANE_SPECS
}
LANE_OBSERVATION_FIELDS = frozenset(
    {
        "lane_id",
        "command",
        "cwd",
        "run_id",
        "source_content_set_sha256",
        "execution_input_content_set_sha256",
        "environment_receipt_sha256",
        "toolchain_receipt_sha256",
        "database_preflight_receipt_sha256",
        "database_runtime_receipt_sha256",
        "started_at",
        "ended_at",
        "exit_code",
        "status",
        "log_path",
        "log_sha256",
        "log_byte_count",
        "log_header_marker",
        "log_tail_marker",
    }
)


def lane_specs() -> tuple[LaneSpec, ...]:
    """Return the immutable exact lane contract used for capture and review."""

    return LANE_SPECS


def completion_boundary() -> dict[str, Any]:
    """Return the non-formal credit boundary shared with the trace builder."""

    return {
        "scope": "REPOSITORY_INTERNAL_NPC_SINGLE_ADMIN_RECOVERY_IMPLEMENTATION_AND_AUTOMATED_VERIFICATION_ONLY",
        "planned_test_ids": ["TC-NPC-SINGLE-ADMIN-RECOVERY-01"],
        "formal_test_status": "NOT_RUN",
        "formal_test_credit_count": 0,
        "actual_device_status": "NOT_RUN",
        "actual_device_credit_count": 0,
        "actual_recovery_drill_status": "NOT_RUN",
        "external_security_review_status": "NOT_RUN",
        "external_legal_review_status": "NOT_RUN",
        "external_accessibility_review_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_gate_status": "NOT_RUN",
        "release_gates_waived": False,
        "release_credit_count": 0,
        "release_status": "NOT_ELIGIBLE",
        "artifact_approval_claimed": False,
    }


def execution_source_boundary() -> dict[str, object]:
    """State the exact freshness/atomicity claim made by this evidence."""

    return {
        "kind": "BYTE_SEALED_DISPOSABLE_SNAPSHOT",
        "live_tree_atomic_binding_claimed": False,
        "pre_publication_live_match_checked": True,
        "consumer_must_revalidate_current_tree": True,
        "concurrent_uncooperative_same_uid_writer_in_scope": False,
    }


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_json_bytes(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def json_text(value: object) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        indent=2,
        allow_nan=False,
    ) + "\n"


def new_run_id() -> str:
    return "NPC-RECOVERY-" + secrets.token_hex(16)


def _validate_run_id(value: object) -> str:
    require(
        type(value) is str and RUN_ID_RE.fullmatch(value) is not None,
        "verification run ID differs",
    )
    return value


def _parse_timestamp(value: object, label: str) -> datetime:
    require(type(value) is str, f"{label} must be an ISO-8601 timestamp")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise VerificationError(f"{label} must be an ISO-8601 timestamp") from exc
    require(
        parsed.tzinfo is not None and parsed.utcoffset() == KST_OFFSET,
        f"{label} must use Asia/Seoul offset",
    )
    return parsed


def _now(clock: Callable[[], datetime], label: str) -> tuple[datetime, str]:
    value = clock()
    require(value.tzinfo is not None, f"{label} clock value must include timezone")
    localized = value.astimezone(TIMEZONE)
    return localized, localized.isoformat(timespec="microseconds")


def _stable_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_nlink,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _directory_authority_identity(metadata: os.stat_result) -> tuple[int, ...]:
    """Identity fields that do not change when legitimate children change."""

    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_uid,
        stat.S_IFMT(metadata.st_mode),
        stat.S_IMODE(metadata.st_mode),
    )


def _reject_symlink_components(path: Path, label: str) -> Path:
    absolute = path if path.is_absolute() else path.absolute()
    require(".." not in absolute.parts, f"{label} contains parent traversal")
    cursor = Path(absolute.anchor)
    for component in absolute.parts[1:]:
        cursor /= component
        try:
            metadata = cursor.lstat()
        except OSError as exc:
            raise VerificationError(f"{label} is unavailable") from exc
        require(not stat.S_ISLNK(metadata.st_mode), f"{label} contains a symlink")
    return absolute


def _read_stable_external_file(path: Path, label: str, maximum_bytes: int) -> bytes:
    absolute = _reject_symlink_components(path, label)
    nofollow = getattr(os, "O_NOFOLLOW", None)
    require(type(nofollow) is int and nofollow != 0, "O_NOFOLLOW is required")
    try:
        descriptor = os.open(absolute, os.O_RDONLY | os.O_CLOEXEC | nofollow)
    except OSError as exc:
        raise VerificationError(f"{label} cannot be opened safely") from exc
    try:
        before = os.fstat(descriptor)
        named_before = absolute.lstat()
        require(
            stat.S_ISREG(before.st_mode)
            and stat.S_ISREG(named_before.st_mode)
            and not stat.S_ISLNK(named_before.st_mode)
            and before.st_nlink == named_before.st_nlink == 1
            and before.st_dev == named_before.st_dev
            and before.st_ino == named_before.st_ino,
            f"{label} is not a stable regular file",
        )
        require(0 < before.st_size <= maximum_bytes, f"{label} size is invalid")
        chunks: list[bytes] = []
        remaining = before.st_size
        while remaining:
            chunk = os.read(descriptor, min(1024 * 1024, remaining))
            require(bool(chunk), f"{label} became short while read")
            chunks.append(chunk)
            remaining -= len(chunk)
        require(not os.read(descriptor, 1), f"{label} grew while read")
        after = os.fstat(descriptor)
        named_after = absolute.lstat()
        require(
            _stable_identity(before) == _stable_identity(after)
            and _stable_identity(named_before) == _stable_identity(named_after)
            and after.st_dev == named_after.st_dev
            and after.st_ino == named_after.st_ino,
            f"{label} changed while read",
        )
        return b"".join(chunks)
    finally:
        os.close(descriptor)


def _safe_root(root: Path) -> Path:
    candidate = root if root.is_absolute() else root.absolute()
    require(".." not in candidate.parts, "repository root contains parent traversal")
    try:
        metadata = candidate.lstat()
    except OSError as exc:
        raise VerificationError("repository root is unavailable") from exc
    require(
        stat.S_ISDIR(metadata.st_mode) and not stat.S_ISLNK(metadata.st_mode),
        "repository root must be a non-symlink directory",
    )
    resolved = candidate.resolve(strict=True)
    require(resolved == candidate, "repository root must be canonical")
    return resolved


def _required_open_flag(name: str) -> int:
    value = getattr(os, name, None)
    require(type(value) is int and value != 0, f"{name} is required")
    return value


def _inode_identity(metadata: os.stat_result) -> tuple[int, int]:
    return metadata.st_dev, metadata.st_ino


def _open_directory_root(path: Path, label: str) -> tuple[int, tuple[int, ...]]:
    try:
        named_before = path.lstat()
        descriptor = os.open(
            path,
            os.O_RDONLY
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_DIRECTORY")
            | _required_open_flag("O_NOFOLLOW"),
        )
    except OSError as exc:
        raise VerificationError(f"{label} cannot be opened safely") from exc
    try:
        opened = os.fstat(descriptor)
        named_after = path.lstat()
        require(
            stat.S_ISDIR(named_before.st_mode)
            and stat.S_ISDIR(opened.st_mode)
            and stat.S_ISDIR(named_after.st_mode)
            and not stat.S_ISLNK(named_before.st_mode)
            and _stable_identity(named_before) == _stable_identity(opened)
            and _stable_identity(opened) == _stable_identity(named_after),
            f"{label} changed while opening",
        )
        return descriptor, _stable_identity(opened)
    except Exception:
        os.close(descriptor)
        raise


def _validate_open_root(
    path: Path, descriptor: int, identity: tuple[int, ...], label: str
) -> None:
    try:
        opened = os.fstat(descriptor)
        named = path.lstat()
    except OSError as exc:
        raise VerificationError(f"{label} became unavailable") from exc
    require(
        _stable_identity(opened) == identity
        and _stable_identity(named) == identity
        and stat.S_ISDIR(opened.st_mode)
        and not stat.S_ISLNK(named.st_mode),
        f"{label} changed while in use",
    )


def _validate_git_visible_path(value: str) -> None:
    require(bool(value) and "\x00" not in value, "Git-visible path is empty or unsafe")
    relative = Path(value)
    require(
        not relative.is_absolute()
        and relative.as_posix() == value
        and all(part not in {"", ".", ".."} for part in relative.parts),
        "Git-visible path is non-canonical",
    )
    lowered_parts = tuple(part.lower() for part in relative.parts)
    require(".git" not in lowered_parts, "Git metadata cannot enter execution snapshot")


def _validate_snapshot_input_path(value: str) -> None:
    _validate_git_visible_path(value)
    relative = Path(value)
    lowered_parts = tuple(part.lower() for part in relative.parts)
    require(
        not any(
            part in {"build", ".gradle", "__pycache__"}
            for part in lowered_parts
        )
        and not value.lower().endswith((".pyc", ".pyo")),
        "generated build input cannot enter execution snapshot",
    )


def _is_execution_input(value: str) -> bool:
    """Apply the exact, manifest-published non-execution exclusion policy."""

    path = Path(value)
    prefixes = tuple(EXECUTION_INPUT_EXCLUSION_POLICY["excluded_prefixes"])
    exact = set(EXECUTION_INPUT_EXCLUSION_POLICY["excluded_exact"])
    name_prefixes = tuple(
        EXECUTION_INPUT_EXCLUSION_POLICY["excluded_name_prefixes"]
    )
    return not (
        value in exact
        or value.startswith(prefixes)
        or any(part.startswith(name_prefixes) for part in path.parts)
    )


def _parse_git_paths(raw: bytes, label: str) -> tuple[str, ...]:
    require(isinstance(raw, bytes), f"{label} output must be bytes")
    require(not raw or raw.endswith(b"\x00"), f"{label} output framing differs")
    values: list[str] = []
    for encoded in raw.split(b"\x00")[:-1] if raw else ():
        try:
            value = encoded.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise VerificationError(f"{label} contains a non-UTF-8 path") from exc
        _validate_git_visible_path(value)
        values.append(value)
    require(len(values) == len(set(values)), f"{label} contains duplicate paths")
    return tuple(sorted(values))


def _git_paths(root: Path, arguments: Sequence[str], label: str) -> tuple[str, ...]:
    git_environment = {
        key: value for key, value in os.environ.items() if not key.startswith("GIT_")
    }
    git_environment.update(
        {
            "GIT_OPTIONAL_LOCKS": "0",
            "GIT_CONFIG_NOSYSTEM": "1",
            "LC_ALL": "C",
        }
    )
    try:
        completed = _SYSTEM_SUBPROCESS_RUN(
            [
                GIT_EXECUTABLE,
                "-c",
                "core.fsmonitor=false",
                "-c",
                "core.untrackedCache=false",
                "ls-files",
                *arguments,
                "-z",
            ],
            cwd=root,
            env=git_environment,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=60,
        )
    except Exception:
        raise VerificationError(f"{label} cannot be enumerated safely") from None
    require(completed.returncode == 0, f"{label} cannot be enumerated safely")
    return _parse_git_paths(completed.stdout, label)


def _git_visible_paths(root: Path) -> tuple[str, ...]:
    listed = _git_paths(
        root,
        ("--cached", "--others", "--exclude-standard", "--deduplicate"),
        "Git-visible source universe",
    )
    deleted = _git_paths(
        root,
        ("--deleted", "--deduplicate"),
        "tracked-deleted source universe",
    )
    require(
        set(deleted).issubset(listed),
        "tracked-deleted source universe is inconsistent",
    )
    deleted_set = set(deleted)
    visible = tuple(
        value
        for value in listed
        if value not in deleted_set and _is_execution_input(value)
    )
    for value in visible:
        _validate_snapshot_input_path(value)
    return visible


def _open_parent_chain(
    root_descriptor: int, relative: Path, label: str
) -> tuple[int, list[int], list[tuple[int, str, tuple[int, ...]]]]:
    current = root_descriptor
    opened_descriptors: list[int] = []
    ancestry: list[tuple[int, str, tuple[int, ...]]] = []
    for component in relative.parent.parts:
        descriptor: int | None = None
        try:
            named_before = os.stat(
                component,
                dir_fd=current,
                follow_symlinks=False,
            )
            descriptor = os.open(
                component,
                os.O_RDONLY
                | _required_open_flag("O_CLOEXEC")
                | _required_open_flag("O_DIRECTORY")
                | _required_open_flag("O_NOFOLLOW"),
                dir_fd=current,
            )
            opened = os.fstat(descriptor)
            named_after = os.stat(
                component,
                dir_fd=current,
                follow_symlinks=False,
            )
        except OSError as exc:
            if descriptor is not None:
                os.close(descriptor)
            for opened_descriptor in reversed(opened_descriptors):
                os.close(opened_descriptor)
            raise VerificationError(f"{label} has an unsafe parent namespace") from exc
        if not (
            stat.S_ISDIR(named_before.st_mode)
            and stat.S_ISDIR(opened.st_mode)
            and _stable_identity(named_before) == _stable_identity(opened)
            and _stable_identity(opened) == _stable_identity(named_after)
        ):
            os.close(descriptor)
            for opened_descriptor in reversed(opened_descriptors):
                os.close(opened_descriptor)
            raise VerificationError(f"{label} has an unsafe parent namespace")
        ancestry.append((current, component, _stable_identity(opened)))
        opened_descriptors.append(descriptor)
        current = descriptor
    return current, opened_descriptors, ancestry


def _validate_open_ancestry(
    ancestry: Sequence[tuple[int, str, tuple[int, ...]]], label: str
) -> None:
    for parent_descriptor, component, identity in ancestry:
        try:
            named = os.stat(
                component,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
        except OSError as exc:
            raise VerificationError(f"{label} parent namespace changed") from exc
        require(
            stat.S_ISDIR(named.st_mode) and _stable_identity(named) == identity,
            f"{label} parent namespace changed",
        )


def _read_regular_binding(
    root_descriptor: int,
    path: str,
    *,
    label: str,
    destination_descriptor: int | None = None,
) -> tuple[VisibleFileBinding, tuple[int, ...]]:
    relative = Path(path)
    parent_descriptor, opened_descriptors, ancestry = _open_parent_chain(
        root_descriptor, relative, label
    )
    file_descriptor: int | None = None
    try:
        try:
            named_before = os.stat(
                relative.name,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
            file_descriptor = os.open(
                relative.name,
                os.O_RDONLY
                | _required_open_flag("O_CLOEXEC")
                | _required_open_flag("O_NOFOLLOW")
                | _required_open_flag("O_NONBLOCK"),
                dir_fd=parent_descriptor,
            )
            opened_before = os.fstat(file_descriptor)
        except OSError as exc:
            raise VerificationError(f"{label} is not a stable regular file") from exc
        mode = stat.S_IMODE(opened_before.st_mode)
        require(
            stat.S_ISREG(named_before.st_mode)
            and stat.S_ISREG(opened_before.st_mode)
            and opened_before.st_nlink == 1
            and _stable_identity(named_before) == _stable_identity(opened_before)
            and mode & 0o7000 == 0,
            f"{label} is not a stable regular file",
        )
        digest = hashlib.sha256()
        remaining = opened_before.st_size
        while remaining:
            chunk = os.read(file_descriptor, min(1024 * 1024, remaining))
            require(bool(chunk), f"{label} became short while read")
            digest.update(chunk)
            if destination_descriptor is not None:
                view = memoryview(chunk)
                while view:
                    written = os.write(destination_descriptor, view)
                    require(written > 0, f"{label} snapshot write became short")
                    view = view[written:]
            remaining -= len(chunk)
        require(not os.read(file_descriptor, 1), f"{label} grew while read")
        opened_after = os.fstat(file_descriptor)
        named_after = os.stat(
            relative.name,
            dir_fd=parent_descriptor,
            follow_symlinks=False,
        )
        _validate_open_ancestry(ancestry, label)
        require(
            _stable_identity(opened_before) == _stable_identity(opened_after)
            and _stable_identity(opened_after) == _stable_identity(named_after),
            f"{label} changed while read",
        )
        return (
            VisibleFileBinding(path, mode, opened_before.st_size, digest.hexdigest()),
            _stable_identity(opened_after),
        )
    finally:
        if file_descriptor is not None:
            os.close(file_descriptor)
        for opened_descriptor in reversed(opened_descriptors):
            os.close(opened_descriptor)


def _open_snapshot_parent(
    root_descriptor: int, relative: Path
) -> tuple[
    int,
    list[int],
    list[tuple[int, str, tuple[int, int]]],
]:
    current = root_descriptor
    opened_descriptors: list[int] = []
    ancestry: list[tuple[int, str, tuple[int, int]]] = []
    for component in relative.parent.parts:
        descriptor: int | None = None
        try:
            os.mkdir(component, SNAPSHOT_DIRECTORY_MODE, dir_fd=current)
        except FileExistsError:
            pass
        except OSError as exc:
            for opened_descriptor in reversed(opened_descriptors):
                os.close(opened_descriptor)
            raise VerificationError("execution snapshot directory cannot be created") from exc
        try:
            named_before = os.stat(
                component,
                dir_fd=current,
                follow_symlinks=False,
            )
            descriptor = os.open(
                component,
                os.O_RDONLY
                | _required_open_flag("O_CLOEXEC")
                | _required_open_flag("O_DIRECTORY")
                | _required_open_flag("O_NOFOLLOW"),
                dir_fd=current,
            )
            metadata = os.fstat(descriptor)
            named_after = os.stat(
                component,
                dir_fd=current,
                follow_symlinks=False,
            )
        except OSError as exc:
            if descriptor is not None:
                os.close(descriptor)
            for opened_descriptor in reversed(opened_descriptors):
                os.close(opened_descriptor)
            raise VerificationError("execution snapshot directory is unsafe") from exc
        if not (
            stat.S_ISDIR(metadata.st_mode)
            and stat.S_ISDIR(named_before.st_mode)
            and _stable_identity(named_before) == _stable_identity(metadata)
            and _stable_identity(metadata) == _stable_identity(named_after)
            and stat.S_IMODE(metadata.st_mode) == SNAPSHOT_DIRECTORY_MODE
        ):
            os.close(descriptor)
            for opened_descriptor in reversed(opened_descriptors):
                os.close(opened_descriptor)
            raise VerificationError("execution snapshot directory mode differs")
        ancestry.append((current, component, _inode_identity(metadata)))
        opened_descriptors.append(descriptor)
        current = descriptor
    return current, opened_descriptors, ancestry


def _copy_visible_file(
    source_root_descriptor: int,
    snapshot_root_descriptor: int,
    path: str,
) -> SnapshotFileBinding:
    relative = Path(path)
    parent_descriptor, opened_descriptors, ancestry = _open_snapshot_parent(
        snapshot_root_descriptor, relative
    )
    destination_descriptor: int | None = None
    try:
        try:
            destination_descriptor = os.open(
                relative.name,
                os.O_WRONLY
                | os.O_CREAT
                | os.O_EXCL
                | _required_open_flag("O_CLOEXEC")
                | _required_open_flag("O_NOFOLLOW"),
                0o600,
                dir_fd=parent_descriptor,
            )
        except OSError as exc:
            raise VerificationError(
                f"execution snapshot target cannot be created: {path}"
            ) from exc
        source, _ = _read_regular_binding(
            source_root_descriptor,
            path,
            label=f"Git-visible source {path}",
            destination_descriptor=destination_descriptor,
        )
        os.fchmod(destination_descriptor, source.mode)
        destination = os.fstat(destination_descriptor)
        named = os.stat(
            relative.name,
            dir_fd=parent_descriptor,
            follow_symlinks=False,
        )
        require(
            stat.S_ISREG(destination.st_mode)
            and destination.st_nlink == 1
            and stat.S_IMODE(destination.st_mode) == source.mode
            and destination.st_size == source.byte_count
            and _stable_identity(destination) == _stable_identity(named),
            f"execution snapshot target differs: {path}",
        )
        for ancestor_parent, component, identity in ancestry:
            ancestor = os.stat(
                component,
                dir_fd=ancestor_parent,
                follow_symlinks=False,
            )
            require(
                stat.S_ISDIR(ancestor.st_mode)
                and _inode_identity(ancestor) == identity
                and stat.S_IMODE(ancestor.st_mode) == SNAPSHOT_DIRECTORY_MODE,
                f"execution snapshot namespace changed: {path}",
            )
        return SnapshotFileBinding(source, _stable_identity(destination))
    finally:
        if destination_descriptor is not None:
            os.close(destination_descriptor)
        for opened_descriptor in reversed(opened_descriptors):
            os.close(opened_descriptor)


def _collect_visible_bindings(
    root: Path, paths: Sequence[str], label: str
) -> tuple[VisibleFileBinding, ...]:
    root_descriptor, root_identity = _open_directory_root(root, label)
    try:
        bindings = tuple(
            _read_regular_binding(
                root_descriptor,
                path,
                label=f"{label} {path}",
            )[0]
            for path in paths
        )
        _validate_open_root(root, root_descriptor, root_identity, label)
        return bindings
    finally:
        os.close(root_descriptor)


def _capture_visible_inventory(root: Path) -> tuple[VisibleFileBinding, ...]:
    paths_before = _git_visible_paths(root)
    bindings = _collect_visible_bindings(root, paths_before, "repository source")
    paths_after = _git_visible_paths(root)
    require(
        paths_after == paths_before,
        "repository Git-visible source universe changed while captured",
    )
    return bindings


def _prepare_execution_snapshot(
    repository: Path, snapshot: Path
) -> tuple[SnapshotFileBinding, ...]:
    metadata = snapshot.lstat()
    require(
        stat.S_ISDIR(metadata.st_mode)
        and not stat.S_ISLNK(metadata.st_mode)
        and stat.S_IMODE(metadata.st_mode) == SNAPSHOT_DIRECTORY_MODE
        and metadata.st_uid == os.geteuid(),
        "execution snapshot root authority differs",
    )
    paths_before = _git_visible_paths(repository)
    source_descriptor, source_identity = _open_directory_root(
        repository, "repository root"
    )
    snapshot_descriptor, _ = _open_directory_root(snapshot, "execution snapshot root")
    try:
        copied = tuple(
            _copy_visible_file(source_descriptor, snapshot_descriptor, path)
            for path in paths_before
        )
        _validate_open_root(
            repository,
            source_descriptor,
            source_identity,
            "repository root",
        )
        snapshot_named = snapshot.lstat()
        snapshot_opened = os.fstat(snapshot_descriptor)
        require(
            _inode_identity(snapshot_named) == _inode_identity(snapshot_opened)
            and stat.S_IMODE(snapshot_opened.st_mode) == SNAPSHOT_DIRECTORY_MODE,
            "execution snapshot root changed while copied",
        )
    finally:
        os.close(snapshot_descriptor)
        os.close(source_descriptor)
    paths_after = _git_visible_paths(repository)
    require(
        paths_after == paths_before,
        "repository Git-visible source universe changed while copied",
    )
    copied_sources = tuple(value.source for value in copied)
    require(
        _collect_visible_bindings(
            repository, paths_after, "repository source after snapshot copy"
        )
        == copied_sources,
        "repository source changed while copied",
    )
    current_snapshot = _collect_visible_bindings(
        snapshot, paths_before, "execution snapshot input"
    )
    require(
        current_snapshot == copied_sources,
        "execution snapshot bytes differ after copy",
    )
    _assert_snapshot_inputs_unchanged(snapshot, copied)
    return copied


def _assert_snapshot_inputs_unchanged(
    snapshot: Path, expected: Sequence[SnapshotFileBinding]
) -> None:
    snapshot_metadata = snapshot.lstat()
    require(
        stat.S_ISDIR(snapshot_metadata.st_mode)
        and not stat.S_ISLNK(snapshot_metadata.st_mode)
        and stat.S_IMODE(snapshot_metadata.st_mode) == SNAPSHOT_DIRECTORY_MODE
        and snapshot_metadata.st_uid == os.geteuid(),
        "execution snapshot root authority changed",
    )
    root_descriptor, root_identity = _open_directory_root(
        snapshot, "execution snapshot root"
    )
    try:
        for expected_file in expected:
            current, identity = _read_regular_binding(
                root_descriptor,
                expected_file.source.path,
                label=f"execution snapshot input {expected_file.source.path}",
            )
            require(
                current == expected_file.source
                and identity == expected_file.snapshot_identity,
                f"execution snapshot input changed: {expected_file.source.path}",
            )
        _validate_open_root(snapshot, root_descriptor, root_identity, "execution snapshot root")
    finally:
        os.close(root_descriptor)
    require(
        stat.S_IMODE(snapshot.lstat().st_mode) == SNAPSHOT_DIRECTORY_MODE,
        "execution snapshot root authority changed",
    )


def _assert_repository_matches_snapshot(
    repository: Path, expected: Sequence[SnapshotFileBinding]
) -> None:
    current = _capture_visible_inventory(repository)
    require(
        current == tuple(value.source for value in expected),
        "repository Git-visible source changed during verification",
    )


def _snapshot_namespace(root: Path) -> tuple[str, ...]:
    """Inventory the disposable snapshot without following runtime links."""

    rows: list[str] = []
    for current, directories, files in os.walk(root, topdown=True, followlinks=False):
        current_path = Path(current)
        for name in sorted((*directories, *files)):
            path = current_path / name
            relative = path.relative_to(root).as_posix()
            metadata = path.lstat()
            require(
                not stat.S_ISLNK(metadata.st_mode),
                f"snapshot runtime symlink differs: {relative}",
            )
            require(
                stat.S_ISDIR(metadata.st_mode) or stat.S_ISREG(metadata.st_mode),
                f"snapshot runtime member type differs: {relative}",
            )
            rows.append(relative + ("/" if stat.S_ISDIR(metadata.st_mode) else ""))
    return tuple(sorted(rows))


def _is_allowlisted_snapshot_output(relative: str) -> bool:
    return any(
        part in ALLOWLISTED_SNAPSHOT_OUTPUT_PARTS
        for part in Path(relative.rstrip("/")).parts
    )


def _remove_allowlisted_snapshot_outputs(
    root: Path, baseline: Sequence[str]
) -> None:
    """Scrub generated build trees so no lane can consume a prior lane's files."""

    baseline_set = set(baseline)
    current = _snapshot_namespace(root)
    unexpected = [
        relative
        for relative in current
        if relative not in baseline_set and not _is_allowlisted_snapshot_output(relative)
    ]
    require(
        not unexpected,
        "lane created a non-allowlisted runtime input: " + ", ".join(unexpected[:4]),
    )
    generated_roots: set[Path] = set()
    for relative in current:
        if relative in baseline_set or not _is_allowlisted_snapshot_output(relative):
            continue
        parts = Path(relative.rstrip("/")).parts
        for index, part in enumerate(parts):
            if part in ALLOWLISTED_SNAPSHOT_OUTPUT_PARTS:
                generated_roots.add(root.joinpath(*parts[: index + 1]))
                break
    for target in sorted(generated_roots, key=lambda path: len(path.parts), reverse=True):
        if not target.exists():
            continue
        metadata = target.lstat()
        require(
            stat.S_ISDIR(metadata.st_mode) and not stat.S_ISLNK(metadata.st_mode),
            f"allowlisted runtime output root differs: {target.relative_to(root)}",
        )
        shutil.rmtree(target)
    require(
        _snapshot_namespace(root) == tuple(baseline),
        "execution snapshot did not return to its sealed pre-lane namespace",
    )


def _open_lane_cwd(root: Path, relative_value: str, lane_id: str) -> tuple[int, Path]:
    relative = Path(relative_value)
    require(
        relative_value == "."
        or (
            not relative.is_absolute()
            and relative.as_posix() == relative_value
            and all(part not in {"", ".", ".."} for part in relative.parts)
        ),
        f"lane cwd is unsafe: {lane_id}",
    )
    root_descriptor, _ = _open_directory_root(root, "execution snapshot root")
    current = root_descriptor
    opened_descriptors: list[int] = []
    try:
        for component in (() if relative_value == "." else relative.parts):
            descriptor: int | None = None
            try:
                named_before = os.stat(
                    component,
                    dir_fd=current,
                    follow_symlinks=False,
                )
                descriptor = os.open(
                    component,
                    os.O_RDONLY
                    | _required_open_flag("O_CLOEXEC")
                    | _required_open_flag("O_DIRECTORY")
                    | _required_open_flag("O_NOFOLLOW"),
                    dir_fd=current,
                )
                opened = os.fstat(descriptor)
                named_after = os.stat(
                    component,
                    dir_fd=current,
                    follow_symlinks=False,
                )
            except OSError as exc:
                if descriptor is not None:
                    os.close(descriptor)
                raise VerificationError(f"lane cwd is unsafe: {lane_id}") from exc
            if not (
                stat.S_ISDIR(named_before.st_mode)
                and _stable_identity(named_before) == _stable_identity(opened)
                and _stable_identity(opened) == _stable_identity(named_after)
            ):
                os.close(descriptor)
                raise VerificationError(f"lane cwd is unsafe: {lane_id}")
            opened_descriptors.append(descriptor)
            current = descriptor
        if opened_descriptors:
            lane_descriptor = opened_descriptors.pop()
        else:
            lane_descriptor = os.dup(root_descriptor)
        return lane_descriptor, Path(f"/proc/self/fd/{lane_descriptor}")
    finally:
        for opened_descriptor in reversed(opened_descriptors):
            os.close(opened_descriptor)
        os.close(root_descriptor)


def _validate_source_path(value: str) -> None:
    require(value == value.strip() and bool(value), "source path is blank or padded")
    require("\\" not in value and "\x00" not in value, "source path is unsafe")
    path = Path(value)
    require(not path.is_absolute(), "source path must be repository-relative")
    require(
        path.as_posix() == value
        and all(part not in {"", ".", ".."} for part in path.parts),
        "source path is non-canonical",
    )
    allowed = (
        value.startswith("apps/android/adminapp/"),
        value.startswith("backend/"),
        value.startswith("deploy/"),
        value.startswith("docs/guides/"),
        value == ISSUER_BINDING_CLI_SOURCE,
        value == PROVISIONING_CLI_SOURCE,
        value == OFFLINE_GATE_SOURCE,
        value == ISSUER_BINDING_CLI_TEST,
        value == OFFLINE_GATE_TEST,
        value == OPENAPI_SOURCE,
        value == "scripts/build_walksafe_full_rc_20260713.py",
        value == "scripts/validate_walksafe_full_rc_20260713.py",
        value == "tests/test_walksafe_full_rc_tooling.py",
    )
    require(any(allowed), "source path is outside NPC product scope")
    forbidden = (
        "__pycache__",
        "/build/",
        "/.gradle/",
        ".pyc",
        "docs/control/",
        "goal_completed",
        "strict_review",
        "gap_backlog",
        "artifact_trace",
        "project_continuation",
        "goal_graph",
        "build_walksafe_npc_single_admin_recovery_",
        "run_walksafe_npc_single_admin_recovery_verification_",
    )
    require(
        not any(fragment in value.lower() for fragment in forbidden),
        "control, generated, or completion-pipeline source is forbidden",
    )


def load_source_paths(path: Path) -> tuple[str, ...]:
    raw = _read_stable_external_file(
        path, "source path file", SOURCE_LIST_LIMIT_BYTES
    )
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise VerificationError("source path file is not UTF-8") from exc
    require("\r" not in text, "source path file must use LF line endings")
    rows = text.splitlines()
    require(bool(rows), "source path file is empty")
    for row in rows:
        _validate_source_path(row)
    require(len(rows) == len(set(rows)), "source path file contains duplicates")
    paths = tuple(sorted(rows))
    require(
        paths == NPC_PRODUCT_SOURCE_PATHS,
        "source path file must contain the exact NPC product source set",
    )
    coverage = {
        "android_product": any(
            value.startswith("apps/android/adminapp/src/main/") for value in paths
        ),
        "android_test": any(
            value.startswith("apps/android/adminapp/src/test/") for value in paths
        ),
        "backend_product": any(
            value.startswith(("backend/app/", "backend/alembic/"))
            for value in paths
        ),
        "backend_test": any(value.startswith("backend/tests/") for value in paths),
        "offline_gate": OFFLINE_GATE_SOURCE in paths,
        "offline_gate_test": OFFLINE_GATE_TEST in paths,
        "provisioning_cli": PROVISIONING_CLI_SOURCE in paths,
        "guide": any(
            value.startswith("docs/guides/")
            or value
            in {
                "apps/android/adminapp/README.md",
                "backend/README.md",
                "backend/tests/README.md",
            }
            for value in paths
        ),
        "openapi": OPENAPI_SOURCE in paths,
    }
    missing = sorted(name for name, present in coverage.items() if not present)
    require(not missing, "source path coverage is incomplete: " + ", ".join(missing))
    return paths


def product_source_paths_for_inventory(
    execution_paths: Sequence[str],
) -> tuple[str, ...]:
    """Resolve the product set while allowing add-only backend migrations.

    The fixed base remains exact.  A successor migration is admitted only when
    it is a regular Git-visible execution input under the canonical Alembic
    versions directory; unrelated files cannot widen the product contract.
    """

    visible = set(execution_paths)
    require(
        set(NPC_PRODUCT_SOURCE_PATHS).issubset(visible),
        "NPC product source is not Git-visible",
    )
    migrations = {
        value
        for value in visible
        if value.startswith("backend/alembic/versions/")
        and value.endswith(".py")
        and Path(value).name != "__init__.py"
    }
    base_migrations = {
        value
        for value in NPC_PRODUCT_SOURCE_PATHS
        if value.startswith("backend/alembic/versions/") and value.endswith(".py")
    }
    return tuple(sorted(set(NPC_PRODUCT_SOURCE_PATHS) | (migrations - base_migrations)))


def collect_source_files(
    root: Path, paths: Sequence[str]
) -> tuple[dict[str, object], ...]:
    safe_root = _safe_root(root)
    bindings = _collect_visible_bindings(safe_root, paths, "NPC product source")
    rows = [
        {
            "path": binding.path,
            "sha256": binding.sha256,
            "byte_count": binding.byte_count,
        }
        for binding in bindings
    ]
    require(
        [row["path"] for row in rows] == sorted(row["path"] for row in rows),
        "source file rows are not sorted",
    )
    return tuple(rows)


def source_content_set_sha256(rows: Sequence[Mapping[str, object]]) -> str:
    normalized: list[dict[str, object]] = []
    for row in rows:
        require(
            set(row) == {"path", "sha256", "byte_count"}
            and type(row.get("path")) is str
            and type(row.get("sha256")) is str
            and SHA256_RE.fullmatch(str(row.get("sha256"))) is not None
            and type(row.get("byte_count")) is int
            and int(row.get("byte_count", -1)) >= 0,
            "source file binding differs",
        )
        normalized.append(dict(row))
    require(
        [row["path"] for row in normalized]
        == sorted(row["path"] for row in normalized),
        "source content set is not sorted",
    )
    return sha256_bytes(canonical_json_bytes(normalized))


def execution_input_closure(
    bindings: Sequence[VisibleFileBinding],
) -> dict[str, object]:
    files = [
        {
            "path": binding.path,
            "mode": binding.mode,
            "byte_count": binding.byte_count,
            "sha256": binding.sha256,
        }
        for binding in bindings
    ]
    require(
        [row["path"] for row in files] == sorted(row["path"] for row in files),
        "execution input closure is not sorted",
    )
    return {
        "inventory_kind": "EXACT_GIT_VISIBLE_EXECUTION_INPUTS_AFTER_EXPLICIT_EXCLUSIONS",
        "exclusion_policy": deepcopy(EXECUTION_INPUT_EXCLUSION_POLICY),
        "file_count": len(files),
        "files": files,
        "path_set_sha256": sha256_bytes(
            canonical_json_bytes([row["path"] for row in files])
        ),
        "content_set_sha256": sha256_bytes(canonical_json_bytes(files)),
    }


def _secret_values(environment: Mapping[str, str]) -> tuple[bytes, ...]:
    values: set[bytes] = set()
    for key, value in environment.items():
        upper = key.upper()
        sensitive_name = any(
            fragment in upper
            for fragment in ("PASSWORD", "TOKEN", "SECRET", "DATABASE_URL")
        )
        sensitive_url = "URL" in upper and "://" in value and "@" in value
        if (sensitive_name or sensitive_url) and len(value) >= 4:
            values.add(value.encode("utf-8", errors="ignore"))
    return tuple(sorted(values, key=len, reverse=True))


def _production_environment() -> dict[str, str]:
    """Return a narrow environment with no ambient test/Gradle injection."""

    environment: dict[str, str] = {}
    for key in SAFE_AMBIENT_ENVIRONMENT_KEYS:
        value = os.environ.get(key)
        if value is not None:
            require("\x00" not in value, f"unsafe ambient environment value: {key}")
            environment[key] = value
    database_url = os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip()
    require(bool(database_url), "WALKSAFE_TEST_DATABASE_URL is required")
    java_home_value = os.environ.get("JAVA_HOME", "").strip()
    require(bool(java_home_value), "JAVA_HOME is required")
    java_home = _reject_symlink_components(
        Path(java_home_value).resolve(strict=True), "JAVA_HOME"
    )
    gradle_home_value = os.environ.get("GRADLE_USER_HOME", "").strip()
    gradle_home = _reject_symlink_components(
        Path(gradle_home_value or (Path.home() / ".gradle")).resolve(strict=True),
        "GRADLE_USER_HOME",
    )
    environment["WALKSAFE_TEST_DATABASE_URL"] = database_url
    environment["JAVA_HOME"] = str(java_home)
    environment["GRADLE_USER_HOME"] = str(gradle_home)
    environment["PATH"] = f"{java_home / 'bin'}:/usr/bin:/bin"
    environment["PYTHONNOUSERSITE"] = "1"
    return environment


def _environment_receipt(environment: Mapping[str, str]) -> dict[str, object]:
    non_secret = {
        key: value
        for key, value in environment.items()
        if key != "WALKSAFE_TEST_DATABASE_URL"
    }
    require(
        not any(
            key.startswith(("PYTEST_", "GRADLE_"))
            and key != "GRADLE_USER_HOME"
            for key in non_secret
        )
        and not any(
            key in non_secret
            for key in (
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "PYTHONPATH",
                "PYTHONSTARTUP",
            )
        ),
        "ambient test or Gradle injection entered execution environment",
    )
    return {
        "schema": "walksafe.npc-verification-environment-receipt.v2",
        "non_secret_value_bindings": [
            {"name": key, "value_sha256": sha256_bytes(value.encode("utf-8"))}
            for key, value in sorted(non_secret.items())
        ],
        "database_url": {
            "source_name": "WALKSAFE_TEST_DATABASE_URL",
            "logical_reference": DATABASE_URL_REFERENCE,
            "value_recorded": False,
        },
        "ambient_injection_keys_absent": [
            "GRADLE_OPTS",
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "PYTEST_ADDOPTS",
            "PYTEST_PLUGINS",
            "PYTHONPATH",
            "PYTHONSTARTUP",
        ],
    }


def _database_component_seal(
    rows: Sequence[Sequence[object]],
) -> dict[str, object]:
    return {
        "row_count": len(rows),
        "sha256": sha256_bytes(canonical_json_bytes(rows)),
    }


def _capture_database_state_receipt(
    database_url: str, phase: str
) -> dict[str, object]:
    """Capture a non-secret PostgreSQL catalog seal in one repeatable-read txn."""

    require(
        phase in {"PRE_MIGRATION", "AFTER_MIGRATION", "AFTER_TEST"},
        "database state phase differs",
    )
    require(bool(database_url) and "\x00" not in database_url, "database URL is missing")
    try:
        from sqlalchemy import create_engine, text  # type: ignore[import-not-found]

        engine = create_engine(database_url, pool_pre_ping=True)
        try:
            with engine.connect() as connection:
                connection.execute(
                    text(
                        "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY"
                    )
                )
                identity_row = connection.execute(
                    text(
                        "SELECT current_database()::text AS database_name, "
                        "current_user::text AS current_user, "
                        "current_setting('server_version_num')::text AS server_version_num, "
                        "COALESCE(current_setting('cluster_name', true), '') "
                        "AS cluster_name"
                    )
                ).mappings().one()
                migration_heads = sorted(
                    str(row[0])
                    for row in connection.execute(
                        text("SELECT version_num::text FROM public.alembic_version")
                    ).all()
                )
                queries = {
                    "relations": (
                        "SELECT n.nspname, c.relname, c.relkind::text, "
                        "pg_get_userbyid(c.relowner), "
                        "c.relrowsecurity::text, c.relforcerowsecurity::text "
                        "FROM pg_catalog.pg_class AS c "
                        "JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace "
                        "WHERE n.nspname = 'public' "
                        "ORDER BY c.relname, c.relkind::text"
                    ),
                    "columns": (
                        "SELECT table_name, column_name, ordinal_position::text, "
                        "data_type, udt_name, is_nullable, COALESCE(column_default, '') "
                        "FROM information_schema.columns WHERE table_schema = 'public' "
                        "ORDER BY table_name, ordinal_position"
                    ),
                    "constraints": (
                        "SELECT c.conname, c.contype::text, "
                        "COALESCE(c.conrelid::regclass::text, ''), "
                        "COALESCE(c.confrelid::regclass::text, ''), "
                        "pg_get_constraintdef(c.oid, true), c.convalidated::text "
                        "FROM pg_catalog.pg_constraint AS c "
                        "JOIN pg_catalog.pg_namespace AS n ON n.oid = c.connamespace "
                        "WHERE n.nspname = 'public' ORDER BY 3, c.conname"
                    ),
                    "indexes": (
                        "SELECT schemaname, tablename, indexname, indexdef "
                        "FROM pg_catalog.pg_indexes WHERE schemaname = 'public' "
                        "ORDER BY tablename, indexname"
                    ),
                    "triggers": (
                        "SELECT c.relname, t.tgname, t.tgenabled::text, "
                        "pg_get_triggerdef(t.oid, true) "
                        "FROM pg_catalog.pg_trigger AS t "
                        "JOIN pg_catalog.pg_class AS c ON c.oid = t.tgrelid "
                        "JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace "
                        "WHERE n.nspname = 'public' AND NOT t.tgisinternal "
                        "ORDER BY c.relname, t.tgname"
                    ),
                    "functions": (
                        "SELECT p.proname, pg_get_function_identity_arguments(p.oid), "
                        "pg_get_function_result(p.oid), pg_get_userbyid(p.proowner), "
                        "p.prosecdef::text, p.provolatile::text, "
                        "COALESCE(array_to_string(p.proconfig, ','), ''), "
                        "pg_get_functiondef(p.oid) "
                        "FROM pg_catalog.pg_proc AS p "
                        "JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace "
                        "WHERE n.nspname = 'public' AND p.prokind = 'f' "
                        "ORDER BY p.proname, pg_get_function_identity_arguments(p.oid)"
                    ),
                    "rls_policies": (
                        "SELECT schemaname, tablename, policyname, permissive, "
                        "COALESCE((SELECT jsonb_agg(CASE WHEN role_name = 'public' THEN "
                        "'PUBLIC' ELSE role_name::text END ORDER BY CASE WHEN role_name = "
                        "'public' THEN 'PUBLIC' ELSE role_name::text END) FROM "
                        "pg_catalog.unnest(roles) AS role_name), '[]')::text, cmd, "
                        "COALESCE(qual, ''), "
                        "COALESCE(with_check, '') FROM pg_catalog.pg_policies "
                        "WHERE schemaname = 'public' ORDER BY tablename, policyname"
                    ),
                    "schema_database_default_acl": (
                        "SELECT 'schema', n.nspname, pg_get_userbyid(n.nspowner), "
                        "'' FROM pg_catalog.pg_namespace AS n "
                        "WHERE n.nspname = 'public' UNION ALL "
                        "SELECT 'database', 'CURRENT_DATABASE', "
                        "pg_get_userbyid(d.datdba), '' FROM pg_catalog.pg_database AS d "
                        "WHERE d.datname = current_database() UNION ALL "
                        "SELECT 'default_acl', r.rolname, COALESCE(n.nspname, ''), "
                        "a.defaclobjtype::text "
                        "FROM pg_catalog.pg_default_acl AS a "
                        "JOIN pg_catalog.pg_roles AS r ON r.oid = a.defaclrole "
                        "LEFT JOIN pg_catalog.pg_namespace AS n ON n.oid = a.defaclnamespace "
                        "WHERE n.nspname = 'public' OR n.nspname IS NULL ORDER BY 1,2,3,4"
                    ),
                    "effective_acl_privileges": (
                        "SELECT * FROM (SELECT 'relation', namespace.nspname, "
                        "relation.relname, '', pg_catalog.pg_get_userbyid("
                        "relation.relowner), pg_catalog.pg_get_userbyid(acl.grantor), "
                        "CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE "
                        "pg_catalog.pg_get_userbyid(acl.grantee) END, acl.privilege_type, "
                        "acl.is_grantable::text FROM pg_catalog.pg_class AS relation "
                        "JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = "
                        "relation.relnamespace CROSS JOIN LATERAL pg_catalog.aclexplode("
                        "COALESCE(relation.relacl, pg_catalog.acldefault(CASE WHEN "
                        "relation.relkind = 'S' THEN 'S'::\"char\" ELSE 'r'::\"char\" "
                        "END, relation.relowner))) AS acl WHERE namespace.nspname = "
                        "'public' AND relation.relkind IN ('r','p','v','m','f','S') "
                        "UNION ALL SELECT 'column', namespace.nspname, relation.relname, "
                        "attribute.attname, pg_catalog.pg_get_userbyid(relation.relowner), "
                        "pg_catalog.pg_get_userbyid(acl.grantor), CASE WHEN "
                        "acl.grantee = 0 THEN 'PUBLIC' ELSE "
                        "pg_catalog.pg_get_userbyid(acl.grantee) "
                        "END, acl.privilege_type, acl.is_grantable::text FROM "
                        "pg_catalog.pg_attribute AS attribute JOIN pg_catalog.pg_class "
                        "AS relation ON relation.oid = attribute.attrelid JOIN "
                        "pg_catalog.pg_namespace AS namespace ON namespace.oid = "
                        "relation.relnamespace CROSS JOIN LATERAL pg_catalog.aclexplode("
                        "COALESCE(attribute.attacl, pg_catalog.acldefault('c', "
                        "relation.relowner))) AS acl WHERE namespace.nspname = 'public' "
                        "AND attribute.attnum > 0 AND NOT attribute.attisdropped "
                        "UNION ALL SELECT 'function', namespace.nspname, function.proname, "
                        "pg_catalog.pg_get_function_identity_arguments(function.oid), "
                        "pg_catalog.pg_get_userbyid(function.proowner), "
                        "pg_catalog.pg_get_userbyid(acl.grantor), CASE WHEN acl.grantee = 0 THEN "
                        "'PUBLIC' ELSE pg_catalog.pg_get_userbyid(acl.grantee) END, "
                        "acl.privilege_type, acl.is_grantable::text FROM "
                        "pg_catalog.pg_proc AS function JOIN pg_catalog.pg_namespace AS "
                        "namespace ON namespace.oid = function.pronamespace CROSS JOIN "
                        "LATERAL pg_catalog.aclexplode(COALESCE(function.proacl, "
                        "pg_catalog.acldefault('f', function.proowner))) AS acl WHERE "
                        "namespace.nspname = 'public' AND function.prokind = 'f' "
                        "UNION ALL SELECT 'schema', '', namespace.nspname, '', "
                        "pg_catalog.pg_get_userbyid(namespace.nspowner), "
                        "pg_catalog.pg_get_userbyid(acl.grantor), CASE WHEN acl.grantee = 0 THEN "
                        "'PUBLIC' ELSE pg_catalog.pg_get_userbyid(acl.grantee) END, "
                        "acl.privilege_type, acl.is_grantable::text FROM "
                        "pg_catalog.pg_namespace AS namespace CROSS JOIN LATERAL "
                        "pg_catalog.aclexplode(COALESCE(namespace.nspacl, "
                        "pg_catalog.acldefault('n', namespace.nspowner))) AS acl WHERE "
                        "namespace.nspname = 'public' UNION ALL SELECT 'database', '', "
                        "'CURRENT_DATABASE', '', pg_catalog.pg_get_userbyid(database.datdba), "
                        "pg_catalog.pg_get_userbyid(acl.grantor), CASE "
                        "WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE pg_catalog.pg_get_userbyid("
                        "acl.grantee) END, acl.privilege_type, acl.is_grantable::text "
                        "FROM pg_catalog.pg_database AS database CROSS JOIN LATERAL "
                        "pg_catalog.aclexplode(COALESCE(database.datacl, "
                        "pg_catalog.acldefault('d', database.datdba))) AS acl WHERE "
                        "database.datname = current_database() UNION ALL SELECT "
                        "'default_acl', COALESCE(namespace.nspname, ''), owner.rolname, "
                        "default_acl.defaclobjtype::text, owner.rolname, "
                        "pg_catalog.pg_get_userbyid("
                        "acl.grantor), CASE WHEN acl.grantee = 0 THEN 'PUBLIC' ELSE "
                        "pg_catalog.pg_get_userbyid(acl.grantee) END, acl.privilege_type, "
                        "acl.is_grantable::text FROM pg_catalog.pg_default_acl AS "
                        "default_acl JOIN pg_catalog.pg_roles AS owner ON owner.oid = "
                        "default_acl.defaclrole LEFT JOIN pg_catalog.pg_namespace AS "
                        "namespace ON namespace.oid = default_acl.defaclnamespace CROSS "
                        "JOIN LATERAL pg_catalog.aclexplode(default_acl.defaclacl) AS acl "
                        "WHERE namespace.nspname = "
                        "'public' OR namespace.nspname IS NULL) AS privileges ORDER BY "
                        "1,2,3,4,5,6,7,8,9"
                    ),
                    "walksafe_roles": (
                        "SELECT rolname, rolsuper::text, rolinherit::text, "
                        "rolcreaterole::text, rolcreatedb::text, rolcanlogin::text, "
                        "rolreplication::text, rolbypassrls::text "
                        "FROM pg_catalog.pg_roles "
                        "WHERE rolname LIKE 'walksafe\\_%' ESCAPE '\\' ORDER BY rolname"
                    ),
                    "walksafe_role_memberships": (
                        "SELECT parent.rolname, member.rolname, grantor.rolname, "
                        "membership.admin_option::text, membership.inherit_option::text, "
                        "membership.set_option::text "
                        "FROM pg_catalog.pg_auth_members AS membership "
                        "JOIN pg_catalog.pg_roles AS parent ON parent.oid = membership.roleid "
                        "JOIN pg_catalog.pg_roles AS member ON member.oid = membership.member "
                        "JOIN pg_catalog.pg_roles AS grantor ON grantor.oid = membership.grantor "
                        "WHERE parent.rolname LIKE 'walksafe\\_%' ESCAPE '\\' "
                        "OR member.rolname LIKE 'walksafe\\_%' ESCAPE '\\' "
                        "ORDER BY parent.rolname, member.rolname, "
                        "grantor.rolname, membership.admin_option::text, "
                        "membership.inherit_option::text, membership.set_option::text"
                    ),
                }
                components = {
                    name: [
                        [str(value) if value is not None else None for value in row]
                        for row in connection.execute(text(query)).all()
                    ]
                    for name, query in queries.items()
                }
                baseline_exists = connection.execute(
                    text(
                        "SELECT to_regclass('public.walksafe_fp046_runtime_acl_baseline') "
                        "IS NOT NULL"
                    )
                ).scalar_one()
                components["safe_control_rows"] = (
                    [
                        [str(value) if value is not None else None for value in row]
                        for row in connection.execute(
                            text(
                                "SELECT object_kind, object_schema, object_name, "
                                "privilege_type, is_grantable::text FROM public."
                                "walksafe_fp046_runtime_acl_baseline ORDER BY 1,2,3,4"
                            )
                        ).all()
                    ]
                    if baseline_exists
                    else []
                )
        finally:
            engine.dispose()
    except Exception as exc:
        raise VerificationError("database runtime identity could not be captured") from exc

    database_name = str(identity_row["database_name"])
    server_identity = {
        key: str(identity_row[key])
        for key in (
            "current_user",
            "server_version_num",
            "cluster_name",
        )
    }
    component_seals = {
        name: _database_component_seal(rows)
        for name, rows in sorted(components.items())
    }
    receipt: dict[str, object] = {
        "schema": "walksafe.npc-database-runtime-state.v2",
        "phase": phase,
        "current_database_sha256": sha256_bytes(database_name.encode("utf-8")),
        "server_identity_sha256": sha256_bytes(canonical_json_bytes(server_identity)),
        "schema_migration_heads": migration_heads,
        "catalog_component_seals": component_seals,
        "relevant_state_sha256": sha256_bytes(
            canonical_json_bytes(
                {"migration_heads": migration_heads, "components": component_seals}
            )
        ),
        "application_rows_recorded": False,
        "database_url_recorded": False,
    }
    _validate_database_state_receipt(receipt, expected_phase=phase)
    return receipt


def _validate_database_state_receipt(
    value: Mapping[str, object], *, expected_phase: str
) -> dict[str, object]:
    require(
        set(value)
        == {
            "schema",
            "phase",
            "current_database_sha256",
            "server_identity_sha256",
            "schema_migration_heads",
            "catalog_component_seals",
            "relevant_state_sha256",
            "application_rows_recorded",
            "database_url_recorded",
        }
        and value.get("schema") == "walksafe.npc-database-runtime-state.v2"
        and value.get("phase") == expected_phase
        and value.get("application_rows_recorded") is False
        and value.get("database_url_recorded") is False,
        "database runtime state receipt identity differs",
    )
    for field in (
        "current_database_sha256",
        "server_identity_sha256",
        "relevant_state_sha256",
    ):
        require(
            type(value.get(field)) is str
            and SHA256_RE.fullmatch(str(value.get(field))) is not None,
            f"database runtime state {field} differs",
        )
    heads = value.get("schema_migration_heads")
    require(
        type(heads) is list
        and bool(heads)
        and heads == sorted(set(heads))
        and all(type(head) is str and head for head in heads),
        "database migration head receipt differs",
    )
    expected_head = (
        DATABASE_PRE_MIGRATION_HEAD
        if expected_phase == "PRE_MIGRATION"
        else DATABASE_POST_MIGRATION_HEAD
    )
    require(heads == [expected_head], "database migration phase head differs")
    components = value.get("catalog_component_seals")
    expected_components = {
        "relations",
        "columns",
        "constraints",
        "indexes",
        "triggers",
        "functions",
        "rls_policies",
        "schema_database_default_acl",
        "effective_acl_privileges",
        "walksafe_roles",
        "walksafe_role_memberships",
        "safe_control_rows",
    }
    require(
        type(components) is dict and set(components) == expected_components,
        "database catalog component inventory differs",
    )
    for name, seal in components.items():
        require(
            type(seal) is dict
            and set(seal) == {"row_count", "sha256"}
            and type(seal.get("row_count")) is int
            and int(seal.get("row_count", -1)) >= 0
            and type(seal.get("sha256")) is str
            and SHA256_RE.fullmatch(str(seal.get("sha256"))) is not None,
            f"database catalog component seal differs: {name}",
        )
    return dict(value)


def _parse_database_state_output(raw: bytes, expected_phase: str) -> dict[str, object]:
    prefix = DATABASE_STATE_MARKER.encode("ascii")
    rows = [line[len(prefix) :] for line in raw.splitlines() if line.startswith(prefix)]
    require(len(rows) == 1, "database runtime state marker count differs")
    try:
        value = json.loads(rows[0].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise VerificationError("database runtime state receipt is invalid JSON") from exc
    require(type(value) is dict, "database runtime state receipt must be an object")
    canonical = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    require(rows[0] == canonical, "database runtime state receipt is noncanonical")
    return _validate_database_state_receipt(value, expected_phase=expected_phase)


def _external_tool_binding(
    path_value: str,
    role: str,
    *,
    allow_cached: bool = True,
    executable_required: bool = False,
) -> dict[str, object]:
    path = _reject_symlink_components(
        Path(path_value).resolve(strict=True), f"external tool {role}"
    )
    parent_descriptor, parent_identity = _open_directory_root(
        path.parent, f"external tool parent {role}"
    )
    descriptor: int | None = None
    try:
        named_before = os.stat(
            path.name, dir_fd=parent_descriptor, follow_symlinks=False
        )
        descriptor = os.open(
            path.name,
            os.O_RDONLY
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_NOFOLLOW"),
            dir_fd=parent_descriptor,
        )
        opened_before = os.fstat(descriptor)
        require(
            stat.S_ISREG(named_before.st_mode)
            and stat.S_ISREG(opened_before.st_mode)
            and named_before.st_nlink == opened_before.st_nlink == 1
            and named_before.st_uid == opened_before.st_uid
            and opened_before.st_uid in {0, os.geteuid()}
            and _stable_identity(named_before) == _stable_identity(opened_before),
            f"external tool authority differs: {role}",
        )
        mode = stat.S_IMODE(opened_before.st_mode)
        if executable_required:
            require(
                mode & 0o111 != 0 and os.access(path, os.X_OK),
                f"external tool is not executable: {role}",
            )
        cache_key = (role, str(path), _stable_identity(opened_before))
        cached = _EXTERNAL_BINDING_CACHE.get(cache_key) if allow_cached else None
        if cached is None:
            digest = hashlib.sha256()
            byte_count = 0
            while chunk := os.read(descriptor, 1024 * 1024):
                digest.update(chunk)
                byte_count += len(chunk)
            require(byte_count == opened_before.st_size, f"external tool read became short: {role}")
            result: dict[str, object] = {
                "role": role,
                "resolved_path": str(path),
                "owner_uid": opened_before.st_uid,
                "mode": mode,
                "byte_count": byte_count,
                "sha256": digest.hexdigest(),
            }
        else:
            result = deepcopy(cached)
        opened_after = os.fstat(descriptor)
        named_after = os.stat(
            path.name, dir_fd=parent_descriptor, follow_symlinks=False
        )
        _validate_open_root(
            path.parent,
            parent_descriptor,
            parent_identity,
            f"external tool parent {role}",
        )
        require(
            _stable_identity(opened_before) == _stable_identity(opened_after)
            and _stable_identity(named_before) == _stable_identity(named_after)
            and _stable_identity(opened_after) == _stable_identity(named_after),
            f"external tool changed while bound: {role}",
        )
        _EXTERNAL_BINDING_CACHE[cache_key] = deepcopy(result)
        return result
    except OSError as exc:
        raise VerificationError(f"external tool cannot be bound: {role}") from exc
    finally:
        if descriptor is not None:
            os.close(descriptor)
        os.close(parent_descriptor)


def _directory_content_binding(
    path: Path,
    role: str,
    *,
    allow_cached: bool,
    allow_file_symlinks: bool = False,
) -> dict[str, object]:
    """Bind every regular file below one external toolchain directory."""

    directory = path.resolve(strict=True)
    require(directory.is_dir(), f"toolchain directory is missing: {role}")
    identities: list[tuple[str, tuple[int, ...]]] = []
    paths: list[tuple[str, Path | None, str | None]] = []
    for current, directories, files in os.walk(
        directory, topdown=True, followlinks=False
    ):
        current_path = Path(current)
        current_metadata = current_path.lstat()
        require(
            stat.S_ISDIR(current_metadata.st_mode)
            and not stat.S_ISLNK(current_metadata.st_mode),
            f"toolchain directory changed: {role}",
        )
        for name in sorted(directories):
            child = current_path / name
            metadata = child.lstat()
            require(
                stat.S_ISDIR(metadata.st_mode) and not stat.S_ISLNK(metadata.st_mode),
                f"toolchain directory contains a link: {role}",
            )
        for name in sorted(files):
            child = current_path / name
            metadata = child.lstat()
            relative = child.relative_to(directory).as_posix()
            if stat.S_ISLNK(metadata.st_mode):
                require(
                    allow_file_symlinks,
                    f"toolchain directory contains a link: {role}/{relative}",
                )
                link_target = os.readlink(child)
                try:
                    resolved: Path | None = child.resolve(strict=True)
                except FileNotFoundError:
                    resolved = None
                if resolved is None:
                    identities.append((relative + "->" + link_target, _stable_identity(metadata)))
                    paths.append((relative, None, link_target))
                    continue
                target_metadata = resolved.stat()
                require(
                    stat.S_ISREG(target_metadata.st_mode)
                    and target_metadata.st_nlink == 1
                    and target_metadata.st_uid in {0, os.geteuid()},
                    f"toolchain link target authority differs: {role}/{relative}",
                )
                identities.append(
                    (
                        relative + "->" + link_target,
                        (*_stable_identity(metadata), *_stable_identity(target_metadata)),
                    )
                )
                paths.append((relative, resolved, link_target))
                continue
            require(
                stat.S_ISREG(metadata.st_mode)
                and metadata.st_nlink == 1,
                f"toolchain file authority differs: {role}/{relative}",
            )
            identities.append((relative, _stable_identity(metadata)))
            paths.append((relative, child, None))
    identities.sort()
    paths.sort()
    cache_key = (role, str(directory), tuple(identities))
    if allow_cached and cache_key in _DIRECTORY_BINDING_CACHE:
        return deepcopy(_DIRECTORY_BINDING_CACHE[cache_key])
    rows: list[dict[str, object]] = []
    total = 0
    for relative, child, link_target in paths:
        if child is None:
            rows.append(
                {
                    "path": relative,
                    "link_target": link_target,
                    "resolved_target": None,
                }
            )
            continue
        binding = _external_tool_binding(
            str(child),
            f"{role}/{relative}",
            allow_cached=allow_cached,
        )
        total += int(binding["byte_count"])
        row: dict[str, object] = {
            "path": relative,
            "owner_uid": binding["owner_uid"],
            "mode": binding["mode"],
            "byte_count": binding["byte_count"],
            "sha256": binding["sha256"],
        }
        if link_target is not None:
            row["link_target"] = link_target
            row["resolved_target"] = binding["resolved_path"]
        rows.append(row)
    result: dict[str, object] = {
        "role": role,
        "resolved_path": str(directory),
        "file_count": len(rows),
        "total_byte_count": total,
        "content_set_sha256": sha256_bytes(canonical_json_bytes(rows)),
    }
    _DIRECTORY_BINDING_CACHE[cache_key] = deepcopy(result)
    return result


def _python_invocation_path(path_value: str) -> str:
    """Preserve the venv launcher while requiring this interpreter target."""

    require(
        type(path_value) is str and bool(path_value) and "\x00" not in path_value,
        "Python invocation path is invalid",
    )
    supplied = Path(path_value)
    current = Path(sys.executable)
    require(
        supplied.is_absolute()
        and current.is_absolute()
        and ".." not in supplied.parts
        and ".." not in current.parts,
        "Python invocation path must be absolute without parent traversal",
    )
    supplied = Path(os.path.normpath(path_value))
    current = Path(os.path.normpath(sys.executable))
    try:
        supplied_target = supplied.resolve(strict=True)
        current_target = current.resolve(strict=True)
    except OSError as exc:
        raise VerificationError("Python invocation path is unavailable") from exc
    require(
        supplied_target == current_target,
        "Python executor differs from the receipt interpreter",
    )
    require(
        current.is_file() and os.access(current, os.X_OK),
        "Python invocation path is not executable",
    )
    return str(current)


def _python_executor_binding(
    python_executable: str, *, allow_cached: bool
) -> dict[str, object]:
    """Bind the complete venv-sensitive launcher chain and executable bytes."""

    invocation = Path(_python_invocation_path(python_executable))
    held: list[
        tuple[
            Path,
            int,
            tuple[int, ...],
            str,
            os.stat_result,
            str | None,
        ]
    ] = []
    rows: list[dict[str, object]] = []
    current = invocation
    seen: set[str] = set()
    try:
        for _ in range(40):
            current_text = str(current)
            require(
                current.is_absolute()
                and ".." not in current.parts
                and current_text not in seen,
                "Python invocation chain differs",
            )
            seen.add(current_text)
            parent = _reject_symlink_components(
                current.parent, "Python invocation chain parent"
            )
            parent_descriptor, parent_identity = _open_directory_root(
                parent, "Python invocation chain parent"
            )
            try:
                named_before = os.stat(
                    current.name,
                    dir_fd=parent_descriptor,
                    follow_symlinks=False,
                )
                require(
                    (
                        stat.S_ISREG(named_before.st_mode)
                        or stat.S_ISLNK(named_before.st_mode)
                    )
                    and named_before.st_nlink == 1
                    and named_before.st_uid in {0, os.geteuid()},
                    "Python invocation authority differs",
                )
                link_target = (
                    os.readlink(current.name, dir_fd=parent_descriptor)
                    if stat.S_ISLNK(named_before.st_mode)
                    else None
                )
                held.append(
                    (
                        parent,
                        parent_descriptor,
                        parent_identity,
                        current.name,
                        named_before,
                        link_target,
                    )
                )
                parent_descriptor = -1
            finally:
                if parent_descriptor >= 0:
                    os.close(parent_descriptor)
            row: dict[str, object] = {
                "path": current_text,
                "kind": "SYMLINK" if link_target is not None else "REGULAR_FILE",
                "owner_uid": named_before.st_uid,
                "mode": stat.S_IMODE(named_before.st_mode),
            }
            if link_target is None:
                rows.append(row)
                resolved_before = current
                break
            row["link_target"] = link_target
            row["link_target_sha256"] = sha256_bytes(link_target.encode("utf-8"))
            rows.append(row)
            target = Path(link_target)
            current = Path(
                os.path.normpath(
                    str(target if target.is_absolute() else current.parent / target)
                )
            )
        else:
            raise VerificationError("Python invocation chain is too deep")

        binding = _external_tool_binding(
            str(resolved_before),
            "PYTHON_EXECUTOR",
            allow_cached=allow_cached,
            executable_required=True,
        )
        rows[-1].update(
            {
                "byte_count": binding["byte_count"],
                "sha256": binding["sha256"],
            }
        )
        for (
            parent,
            parent_descriptor,
            parent_identity,
            name,
            named_before,
            link_target_before,
        ) in held:
            named_after = os.stat(
                name, dir_fd=parent_descriptor, follow_symlinks=False
            )
            link_target_after = (
                os.readlink(name, dir_fd=parent_descriptor)
                if stat.S_ISLNK(named_after.st_mode)
                else None
            )
            _validate_open_root(
                parent,
                parent_descriptor,
                parent_identity,
                "Python invocation chain parent",
            )
            require(
                _stable_identity(named_before) == _stable_identity(named_after)
                and link_target_before == link_target_after,
                "Python invocation path changed while bound",
            )
        require(
            invocation.resolve(strict=True) == resolved_before
            and binding.get("resolved_path") == str(resolved_before),
            "Python invocation path changed while bound",
        )
        return {
            **binding,
            "invocation_path": str(invocation),
            "invocation_chain": rows,
            "invocation_chain_sha256": sha256_bytes(canonical_json_bytes(rows)),
        }
    except OSError as exc:
        raise VerificationError("Python invocation path cannot be bound") from exc
    finally:
        for _, descriptor, _, _, _, _ in reversed(held):
            os.close(descriptor)


def _python_runtime_identity(
    python_binding: Mapping[str, object], *, allow_cached: bool
) -> dict[str, object]:
    invocation_path = python_binding.get("invocation_path")
    resolved_path = python_binding.get("resolved_path")
    require(
        type(invocation_path) is str
        and type(resolved_path) is str
        and Path(invocation_path).resolve(strict=True) == Path(resolved_path),
        "Python runtime executable identity differs",
    )
    prefix_values = {
        "prefix": sys.prefix,
        "exec_prefix": sys.exec_prefix,
        "base_prefix": sys.base_prefix,
        "base_exec_prefix": sys.base_exec_prefix,
    }
    require(
        all(
            type(value) is str
            and Path(value).is_absolute()
            and ".." not in Path(value).parts
            for value in prefix_values.values()
        ),
        "Python runtime prefix identity differs",
    )
    resolved_prefixes = {
        name: str(Path(value).resolve(strict=True))
        for name, value in prefix_values.items()
    }
    prefix = Path(prefix_values["prefix"])
    base_prefix = Path(prefix_values["base_prefix"])
    require(
        resolved_prefixes["prefix"] == resolved_prefixes["exec_prefix"]
        and resolved_prefixes["base_prefix"]
        == resolved_prefixes["base_exec_prefix"]
        and prefix.resolve(strict=True) != base_prefix.resolve(strict=True)
        and Path(invocation_path).parent.parent.resolve(strict=True)
        == prefix.resolve(strict=True),
        "Python verification virtual environment identity differs",
    )
    base_executable_value = getattr(sys, "_base_executable", None)
    require(
        type(base_executable_value) is str
        and Path(base_executable_value).is_absolute()
        and ".." not in Path(base_executable_value).parts,
        "Python base executable identity differs",
    )
    base_executable = Path(base_executable_value)
    require(
        base_executable.resolve(strict=True) == Path(resolved_path),
        "Python base executable target differs",
    )
    sysconfig_paths = {
        name: value
        for name, value in sorted(sysconfig.get_paths().items())
    }
    require(
        bool(sysconfig_paths)
        and all(
            type(value) is str
            and Path(value).is_absolute()
            and ".." not in Path(value).parts
            for value in sysconfig_paths.values()
        )
        and Path(sysconfig_paths["purelib"]).resolve(strict=True).is_relative_to(
            prefix.resolve(strict=True)
        )
        and Path(sysconfig_paths["platlib"]).resolve(strict=True).is_relative_to(
            prefix.resolve(strict=True)
        ),
        "Python sysconfig path identity differs",
    )
    pyvenv_cfg = prefix / "pyvenv.cfg"
    return {
        "implementation": sys.implementation.name,
        "cache_tag": sys.implementation.cache_tag,
        "version": [
            sys.version_info.major,
            sys.version_info.minor,
            sys.version_info.micro,
            sys.version_info.releaselevel,
            sys.version_info.serial,
        ],
        "prefixes": prefix_values,
        "resolved_prefixes": resolved_prefixes,
        "base_executable_invocation_path": str(base_executable),
        "base_executable": _external_tool_binding(
            str(base_executable.resolve(strict=True)),
            "PYTHON_BASE_EXECUTABLE",
            allow_cached=allow_cached,
            executable_required=True,
        ),
        "sysconfig_paths": sysconfig_paths,
        "executable_invocation_path": invocation_path,
        "executable_resolved_path": resolved_path,
        "pyvenv_cfg": _external_tool_binding(
            str(pyvenv_cfg),
            "PYTHON_VENV_CONFIGURATION",
            allow_cached=allow_cached,
        ),
    }


def _python_distribution_binding(python_executable: str) -> dict[str, object]:
    require(
        Path(python_executable).resolve(strict=True)
        == Path(sys.executable).resolve(strict=True),
        "Python executor differs from the receipt interpreter",
    )
    rows: list[dict[str, object]] = []
    for distribution in importlib.metadata.distributions():
        name = distribution.metadata.get("Name")
        require(type(name) is str and name.strip(), "installed distribution name differs")
        metadata_path = Path(str(distribution._path)).resolve(strict=True)  # type: ignore[attr-defined]
        records: list[dict[str, object]] = []
        for filename in ("METADATA", "RECORD", "direct_url.json", "INSTALLER"):
            candidate = metadata_path / filename
            if not candidate.exists():
                continue
            raw = _read_stable_external_file(
                candidate, f"Python distribution {name}/{filename}", 64 * 1024 * 1024
            )
            records.append(
                {
                    "name": filename,
                    "byte_count": len(raw),
                    "sha256": sha256_bytes(raw),
                }
            )
        rows.append(
            {
                "name": name.strip().lower().replace("_", "-"),
                "version": distribution.version,
                "metadata_path": str(metadata_path),
                "records": records,
            }
        )
    rows.sort(key=lambda row: (str(row["name"]), str(row["metadata_path"])))
    return {
        "distribution_count": len(rows),
        "content_set_sha256": sha256_bytes(canonical_json_bytes(rows)),
    }


def _single_directory(candidates: Sequence[Path], label: str) -> Path:
    values = sorted({candidate.resolve(strict=True) for candidate in candidates})
    require(len(values) == 1, f"{label} resolution differs")
    return values[0]


def _gradle_offline_module_seed_directories(module_root: Path) -> dict[str, Path]:
    directories = {
        path.name: path.resolve(strict=True)
        for path in module_root.iterdir()
        if path.is_dir()
        and (
            path.name == "files-2.1"
            or path.name.startswith("metadata-")
            or path.name.startswith("resources-")
        )
    }
    require(
        {"files-2.1"}.issubset(directories)
        and any(name.startswith("metadata-") for name in directories),
        "Gradle offline module seed is incomplete",
    )
    return directories


def _toolchain_receipt(
    root: Path,
    closure: Mapping[str, object],
    python_executable: str,
    *,
    environment: Mapping[str, str] | None = None,
    allow_cached: bool = True,
) -> dict[str, object]:
    python_executable = _python_invocation_path(python_executable)
    rows = closure.get("files")
    require(type(rows) is list, "execution input closure files are missing")
    by_path = {
        row.get("path"): row
        for row in rows
        if type(row) is dict and type(row.get("path")) is str
    }
    runner_row = by_path.get(RUNNER_REL.as_posix())
    gradlew_row = by_path.get("apps/android/gradlew")
    require(type(runner_row) is dict, "verification runner is not execution-bound")
    require(type(gradlew_row) is dict, "Gradle wrapper is not execution-bound")
    requirements_row = by_path.get("backend/requirements.lock")
    wrapper_properties_row = by_path.get(
        "apps/android/gradle/wrapper/gradle-wrapper.properties"
    )
    require(type(requirements_row) is dict, "Python requirements lock is not bound")
    require(type(wrapper_properties_row) is dict, "Gradle distribution lock is not bound")

    wrapper_properties = (root / "apps/android/gradle/wrapper/gradle-wrapper.properties").read_text(
        encoding="utf-8"
    )
    version_match = re.search(r"gradle-([0-9][0-9.]*)-bin\.zip", wrapper_properties)
    require(version_match is not None, "Gradle distribution version cannot be resolved")
    gradle_version = version_match.group(1)
    ambient = {
        key: value
        for key, value in os.environ.items()
        if key in SAFE_AMBIENT_ENVIRONMENT_KEYS
    }
    if environment is not None:
        ambient.update(environment)
    gradle_home = Path(
        ambient.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))
    ).resolve(strict=True)
    gradle_distribution = _single_directory(
        tuple(
            gradle_home.glob(
                f"wrapper/dists/gradle-{gradle_version}-bin/*/gradle-{gradle_version}"
            )
        ),
        "Gradle distribution",
    )
    gradle_module_root = (gradle_home / "caches/modules-2").resolve(strict=True)
    gradle_seed_directories = _gradle_offline_module_seed_directories(
        gradle_module_root
    )
    forbidden_gradle_injection = (
        gradle_home / "gradle.properties",
        gradle_home / "init.gradle",
        gradle_home / "init.gradle.kts",
        gradle_home / "init.d",
        root / "gradle.properties",
        root / "apps/android/gradle.properties",
    )
    require(
        not any(path.exists() or path.is_symlink() for path in forbidden_gradle_injection),
        "ambient or project Gradle configuration injection is forbidden",
    )

    java_home_value = ambient.get("JAVA_HOME", "").strip()
    require(bool(java_home_value), "JAVA_HOME is required for toolchain binding")
    java_home = _reject_symlink_components(
        Path(java_home_value).resolve(strict=True), "JAVA_HOME"
    )
    java_executable = Path(
        shutil.which("java", path=ambient.get("PATH")) or ""
    ).resolve(strict=True)
    javac_executable = Path(
        shutil.which("javac", path=ambient.get("PATH")) or ""
    ).resolve(strict=True)
    require(
        java_executable == (java_home / "bin/java").resolve(strict=True)
        and javac_executable == (java_home / "bin/javac").resolve(strict=True),
        "JAVA_HOME and PATH-selected Java/Javac differ",
    )
    release_raw = _read_stable_external_file(
        java_home / "release", "JDK release", 1024 * 1024
    )
    require(
        re.search(rb'^JAVA_VERSION="21(?:[.\"]|$)', release_raw, re.MULTILINE)
        is not None,
        "JDK major version differs from 21",
    )
    sdk_value = ambient.get("ANDROID_SDK_ROOT") or ambient.get("ANDROID_HOME")
    require(type(sdk_value) is str and sdk_value, "Android SDK root is not set")
    sdk_root = Path(sdk_value).resolve(strict=True)
    sdk_platform = (sdk_root / "platforms/android-36").resolve(strict=True)
    sdk_build_tools = (sdk_root / "build-tools").resolve(strict=True)
    python_runtime_paths = {
        "stdlib": Path(sysconfig.get_path("stdlib")).resolve(strict=True),
        "platform_stdlib": Path(sysconfig.get_path("platstdlib")).resolve(strict=True),
        "site_packages": Path(sysconfig.get_path("purelib")).resolve(strict=True),
    }
    lane_contract = [
        {
            "lane_id": spec.lane_id,
            "cwd": spec.cwd,
            "commands": [
                {
                    "logical_command": command.logical_command(),
                    "execution_argv": list(
                        command.execution_argv(python_executable, ambient)
                    ),
                    "success_kind": command.success_kind,
                    "required_output_markers": list(command.required_output_markers),
                }
                for command in spec.commands
            ],
        }
        for spec in LANE_SPECS
    ]
    python_binding = _python_executor_binding(
        python_executable, allow_cached=allow_cached
    )
    return {
        "schema": "walksafe.npc-verification-toolchain-receipt.v3",
        "runner": deepcopy(runner_row),
        "gradle_wrapper": deepcopy(gradlew_row),
        "python": python_binding,
        "python_runtime_identity": _python_runtime_identity(
            python_binding, allow_cached=allow_cached
        ),
        "python_installed_distributions": _python_distribution_binding(
            python_executable
        ),
        "python_runtime": {
            name: _directory_content_binding(
                path,
                "PYTHON_" + name.upper(),
                allow_cached=allow_cached,
            )
            for name, path in python_runtime_paths.items()
        },
        "python_requirements_lock": deepcopy(requirements_row),
        "git": _external_tool_binding(
            GIT_EXECUTABLE,
            "GIT_INVENTORY_TOOL",
            allow_cached=allow_cached,
            executable_required=True,
        ),
        "gradle_distribution_lock": deepcopy(wrapper_properties_row),
        "gradle_distribution": _directory_content_binding(
            gradle_distribution, "GRADLE_DISTRIBUTION", allow_cached=allow_cached
        ),
        "gradle_distribution_ready_marker": _external_tool_binding(
            str(gradle_distribution.parent / f"gradle-{gradle_version}-bin.zip.ok"),
            "GRADLE_DISTRIBUTION_READY_MARKER",
            allow_cached=allow_cached,
        ),
        "gradle_offline_module_seed": {
            name: _directory_content_binding(
                path,
                "GRADLE_OFFLINE_MODULE_SEED_" + name.upper(),
                allow_cached=allow_cached,
            )
            for name, path in sorted(gradle_seed_directories.items())
        },
        "gradle_user_home_boundary": {
            "canonical_path": str(gradle_home),
            "ambient_init_and_properties_absent": True,
            "project_gradle_properties_absent": True,
            "immutable_seed_members": sorted(gradle_seed_directories),
            "excluded_mutable_derived_namespaces": [
                ".tmp",
                "caches/9.3.1",
                "daemon",
                "native",
                "notifications",
                "workers",
            ],
            "build_cache_disabled": True,
            "configuration_cache_disabled": True,
            "file_system_watch_disabled": True,
        },
        "jdk": {
            "java": _external_tool_binding(
                str(java_executable), "JAVA_EXECUTOR", allow_cached=allow_cached
                , executable_required=True
            ),
            "javac": _external_tool_binding(
                str(javac_executable), "JAVAC_EXECUTOR", allow_cached=allow_cached
                , executable_required=True
            ),
            "release": _external_tool_binding(
                str(java_home / "release"), "JDK_RELEASE", allow_cached=allow_cached
            ),
            "modules": _external_tool_binding(
                str(java_home / "lib/modules"), "JDK_MODULES", allow_cached=allow_cached
            ),
            "executables": _directory_content_binding(
                java_home / "bin",
                "JDK_EXECUTABLE_CLOSURE",
                allow_cached=allow_cached,
            ),
            "runtime_native": _directory_content_binding(
                java_home / "lib",
                "JDK_RUNTIME_NATIVE_CLOSURE",
                allow_cached=allow_cached,
                allow_file_symlinks=True,
            ),
            "configuration": _directory_content_binding(
                java_home / "conf",
                "JDK_CONFIGURATION_CLOSURE",
                allow_cached=allow_cached,
                allow_file_symlinks=True,
            ),
        },
        "android_sdk": {
            "platform_android_36": _directory_content_binding(
                sdk_platform, "ANDROID_PLATFORM_36", allow_cached=allow_cached
            ),
            "build_tools": _directory_content_binding(
                sdk_build_tools, "ANDROID_BUILD_TOOLS", allow_cached=allow_cached
            ),
        },
        "lane_contract": lane_contract,
        "lane_contract_sha256": sha256_bytes(canonical_json_bytes(lane_contract)),
    }


def _database_preflight_receipt(
    lane_results: Sequence[LaneResult],
) -> dict[str, object]:
    backend = next(
        result
        for result in lane_results
        if result.spec.lane_id == "BACKEND_RECOVERY_PYTEST"
    )
    output = backend.commands[0].stdout + backend.commands[0].stderr
    require(
        re.search(rb"^test database preflight PASS:[^\r\n]*$", output, re.MULTILINE)
        is not None,
        "database preflight receipt marker differs",
    )
    return {
        "schema": "walksafe.npc-database-preflight-receipt.v2",
        "lane_id": backend.spec.lane_id,
        "command": backend.commands[0].logical_command,
        "status": "PASS",
        "redacted_output_sha256": sha256_bytes(output),
        "database_url_recorded": False,
        "database_url_logical_reference": DATABASE_URL_REFERENCE,
    }


def _database_runtime_receipt(
    lane_results: Sequence[LaneResult],
) -> dict[str, object]:
    backend = next(
        result
        for result in lane_results
        if result.spec.lane_id == "BACKEND_RECOVERY_PYTEST"
    )
    states: list[dict[str, object]] = []
    for spec, result in zip(backend.spec.commands, backend.commands, strict=True):
        if spec.success_kind != "DATABASE_STATE":
            continue
        states.append(
            _parse_database_state_output(
                result.stdout + result.stderr,
                spec.argv[-1],
            )
        )
    require(
        [state["phase"] for state in states]
        == ["PRE_MIGRATION", "AFTER_MIGRATION", "AFTER_TEST"],
        "database runtime state phases differ",
    )
    identity_fields = (
        "current_database_sha256",
        "server_identity_sha256",
    )
    require(
        all(
            states[0][field] == states[1][field] == states[2][field]
            for field in identity_fields
        )
        and states[0]["schema_migration_heads"] == [DATABASE_PRE_MIGRATION_HEAD]
        and states[1]["schema_migration_heads"]
        == states[2]["schema_migration_heads"]
        == [DATABASE_POST_MIGRATION_HEAD]
        and states[1]["catalog_component_seals"]
        == states[2]["catalog_component_seals"]
        and states[1]["relevant_state_sha256"]
        == states[2]["relevant_state_sha256"],
        "database identity, migration, or post-test catalog continuity differs",
    )
    return {
        "schema": "walksafe.npc-database-runtime-state-chain.v2",
        "lane_id": backend.spec.lane_id,
        "states": states,
        "continuity_sha256": sha256_bytes(
            canonical_json_bytes(
                {
                    "identity": {field: states[2][field] for field in identity_fields},
                    "pre_migration_head": states[0]["schema_migration_heads"],
                    "post_migration_head": states[2]["schema_migration_heads"],
                    "post_catalog": states[2]["catalog_component_seals"],
                }
            )
        ),
        "database_url_recorded": False,
    }


def _validate_database_runtime_chain(
    value: Mapping[str, object], backend_log: bytes
) -> dict[str, object]:
    require(
        set(value)
        == {
            "schema",
            "lane_id",
            "states",
            "continuity_sha256",
            "database_url_recorded",
        }
        and value.get("schema")
        == "walksafe.npc-database-runtime-state-chain.v2"
        and value.get("lane_id") == "BACKEND_RECOVERY_PYTEST"
        and value.get("database_url_recorded") is False,
        "database runtime state chain identity differs",
    )
    raw_states = value.get("states")
    require(type(raw_states) is list and len(raw_states) == 3, "database runtime state count differs")
    states = [
        _validate_database_state_receipt(state, expected_phase=phase)
        for state, phase in zip(
            raw_states,
            ("PRE_MIGRATION", "AFTER_MIGRATION", "AFTER_TEST"),
            strict=True,
        )
        if type(state) is dict
    ]
    require(len(states) == 3, "database runtime state rows differ")
    identity_fields = (
        "current_database_sha256",
        "server_identity_sha256",
    )
    require(
        all(
            states[0][field] == states[1][field] == states[2][field]
            for field in identity_fields
        )
        and states[1]["catalog_component_seals"]
        == states[2]["catalog_component_seals"]
        and states[1]["relevant_state_sha256"]
        == states[2]["relevant_state_sha256"],
        "database runtime state continuity differs",
    )
    expected_continuity = sha256_bytes(
        canonical_json_bytes(
            {
                "identity": {field: states[2][field] for field in identity_fields},
                "pre_migration_head": states[0]["schema_migration_heads"],
                "post_migration_head": states[2]["schema_migration_heads"],
                "post_catalog": states[2]["catalog_component_seals"],
            }
        )
    )
    require(
        value.get("continuity_sha256") == expected_continuity,
        "database runtime state continuity seal differs",
    )
    for state in states:
        marker = (
            "| " + DATABASE_STATE_MARKER + json.dumps(
                state,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            )
        ).encode("utf-8")
        require(
            backend_log.splitlines().count(marker) == 1,
            f"database runtime state log binding differs: {state['phase']}",
        )
    return dict(value)


def redact_output(raw: bytes, secret_values: Sequence[bytes]) -> bytes:
    value = raw
    for secret_value in secret_values:
        if secret_value:
            value = value.replace(secret_value, b"[REDACTED_SECRET]")
    value = _DATABASE_URL_RE.sub(b"[REDACTED_SECRET_URL]", value)
    value = _USERINFO_URL_RE.sub(b"[REDACTED_SECRET_URL]", value)
    return value


def _assert_secret_free(
    content: bytes, secret_values: Sequence[bytes], label: str
) -> None:
    for secret_value in secret_values:
        require(
            not secret_value or secret_value not in content,
            f"secret leaked into {label}",
        )
    require(
        _DATABASE_URL_RE.search(content) is None,
        f"database URL leaked into {label}",
    )
    require(
        _USERINFO_URL_RE.search(content) is None,
        f"credential URL leaked into {label}",
    )


def _resolved_environment(
    base: Mapping[str, str], command: CommandSpec
) -> dict[str, str]:
    environment = dict(base)
    for key, logical_value in command.logical_environment:
        match = _ENV_REFERENCE_RE.fullmatch(logical_value)
        if match is None:
            environment[key] = logical_value
            continue
        source_key = match.group(1)
        source_value = base.get(source_key, "").strip()
        require(bool(source_value), f"{source_key} is required")
        environment[key] = source_value
    return environment


def _stream_lines(raw: bytes) -> list[str]:
    if not raw:
        return ["| <empty>"]
    lines = raw.decode("utf-8", errors="replace").splitlines()
    return ["| " + line for line in lines] or ["| <empty>"]


def _combined_output(stdout_lines: Sequence[str], stderr_lines: Sequence[str]) -> str:
    values = [*stdout_lines, *stderr_lines]
    return "\n".join(value for value in values if value != "<empty>")


def _validate_command_success(
    lane_id: str,
    command_index: int,
    command: CommandSpec,
    stdout_lines: Sequence[str],
    stderr_lines: Sequence[str],
) -> dict[str, object]:
    output = _combined_output(stdout_lines, stderr_lines)
    label = f"{lane_id} command {command_index}"
    if command.success_kind == "EXIT_ZERO":
        return {}
    if command.success_kind == "DATABASE_PREFLIGHT":
        markers = re.findall(
            r"^test database preflight (?:PASS|FAIL):[^\r\n]*$",
            output,
            flags=re.MULTILINE,
        )
        require(
            len(markers) == 1 and markers[0].startswith("test database preflight PASS:"),
            f"{label} database preflight marker differs",
        )
        return {}
    if command.success_kind == "DATABASE_STATE":
        phase = command.argv[-1]
        receipt = _parse_database_state_output(
            ("\n".join(stdout_lines) + "\n").encode("utf-8"), phase
        )
        require(
            not any(line != "<empty>" for line in stderr_lines),
            f"{label} database runtime state emitted stderr",
        )
        return {"database_state_receipt_sha256": sha256_bytes(canonical_json_bytes(receipt))}
    if command.success_kind == "GRADLE":
        terminals = _GRADLE_TERMINAL_RE.findall(output)
        require(
            len(terminals) == 1
            and _GRADLE_SUCCESS_RE.fullmatch(terminals[0]) is not None,
            f"{label} Gradle terminal marker differs",
        )
        for marker in command.required_output_markers:
            task_lines = [
                line
                for line in output.splitlines()
                if re.fullmatch(rf"(?:> Task )?{re.escape(marker)}(?:\s.*)?", line)
            ]
            require(len(task_lines) == 1, f"{label} Gradle task marker differs")
            require(
                not re.search(
                    r"\b(?:UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE|FAILED)\b",
                    task_lines[0],
                ),
                f"{label} Gradle task was not freshly successful",
            )
        return {}
    if command.success_kind == "PYTEST":
        terminal_candidates = _PYTEST_TERMINAL_RE.findall(output)
        success = _PYTEST_SUCCESS_RE.findall(output)
        require(
            len(terminal_candidates) == 1 and len(success) == 1,
            f"{label} pytest terminal summary differs",
        )
        return {"pytest_passed": int(success[0])}
    raise VerificationError(f"{label} success contract is unsupported")


def _build_lane_log(
    result: LaneResult,
    *,
    run_id: str,
    source_set_sha256: str,
) -> bytes:
    spec = result.spec
    lines = [
        LOG_HEADER_MARKER,
        f"{LOG_FIELD_PREFIX}SCHEMA {LOG_SCHEMA}",
        f"{LOG_FIELD_PREFIX}RUN_ID {run_id}",
        f"{LOG_FIELD_PREFIX}SOURCE_CONTENT_SET_SHA256 {source_set_sha256}",
        f"{LOG_FIELD_PREFIX}LANE_ID {spec.lane_id}",
        f"{LOG_FIELD_PREFIX}CWD {spec.cwd}",
        f"{LOG_FIELD_PREFIX}STARTED_AT {result.started_at}",
        f"{LOG_FIELD_PREFIX}COMMAND_COUNT {len(result.commands)}",
    ]
    for index, command in enumerate(result.commands, start=1):
        lines.extend(
            (
                f"{LOG_FIELD_PREFIX}COMMAND_BEGIN {index}",
                f"{LOG_FIELD_PREFIX}LOGICAL_COMMAND "
                + json.dumps(command.logical_command, ensure_ascii=True),
                f"{LOG_FIELD_PREFIX}STDOUT_BEGIN {index}",
                *_stream_lines(command.stdout),
                f"{LOG_FIELD_PREFIX}STDOUT_END {index}",
                f"{LOG_FIELD_PREFIX}STDERR_BEGIN {index}",
                *_stream_lines(command.stderr),
                f"{LOG_FIELD_PREFIX}STDERR_END {index}",
                f"{LOG_FIELD_PREFIX}COMMAND_EXIT_CODE {index} 0",
                f"{LOG_FIELD_PREFIX}COMMAND_END {index}",
            )
        )
    lines.extend(
        (
            f"{LOG_FIELD_PREFIX}ENDED_AT {result.ended_at}",
            f"{LOG_FIELD_PREFIX}EXIT_CODE 0",
            f"{LOG_FIELD_PREFIX}STATUS PASS",
            LOG_TAIL_MARKER,
        )
    )
    raw = ("\n".join(lines) + "\n").encode("utf-8")
    require(0 < len(raw) <= LOG_LIMIT_BYTES, f"lane log is too large: {spec.lane_id}")
    return raw


def _take_exact(lines: Sequence[str], index: int, expected: str, label: str) -> int:
    require(index < len(lines) and lines[index] == expected, f"{label} differs")
    return index + 1


def _take_stream(
    lines: Sequence[str], index: int, end_marker: str, label: str
) -> tuple[list[str], int]:
    values: list[str] = []
    while index < len(lines) and lines[index] != end_marker:
        require(lines[index].startswith("| "), f"{label} framing differs")
        values.append(lines[index][2:])
        index += 1
    require(values, f"{label} is missing")
    return values, _take_exact(lines, index, end_marker, f"{label} end marker")


def validate_lane_log(
    raw: bytes,
    spec: LaneSpec,
    *,
    run_id: str,
    source_content_set_sha256: str,
    started_at: str,
    ended_at: str,
) -> dict[str, object]:
    """Validate exact framing, commands, and success evidence for one lane log."""

    require(spec in LANE_SPECS, "lane log specification is not canonical")
    _validate_run_id(run_id)
    require(
        SHA256_RE.fullmatch(source_content_set_sha256) is not None,
        "lane log source content-set SHA-256 differs",
    )
    started = _parse_timestamp(started_at, f"{spec.lane_id}.started_at")
    ended = _parse_timestamp(ended_at, f"{spec.lane_id}.ended_at")
    require(ended >= started, f"lane timestamp order differs: {spec.lane_id}")
    require(
        isinstance(raw, bytes) and 0 < len(raw) <= LOG_LIMIT_BYTES,
        f"lane log size differs: {spec.lane_id}",
    )
    _assert_secret_free(raw, (), f"{spec.lane_id} log")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise VerificationError(f"lane log is not UTF-8: {spec.lane_id}") from exc
    require(text.endswith("\n") and "\r" not in text, f"lane log text framing differs: {spec.lane_id}")
    lines = text.splitlines()
    index = 0
    index = _take_exact(lines, index, LOG_HEADER_MARKER, "lane log header marker")
    fixed = (
        f"{LOG_FIELD_PREFIX}SCHEMA {LOG_SCHEMA}",
        f"{LOG_FIELD_PREFIX}RUN_ID {run_id}",
        f"{LOG_FIELD_PREFIX}SOURCE_CONTENT_SET_SHA256 {source_content_set_sha256}",
        f"{LOG_FIELD_PREFIX}LANE_ID {spec.lane_id}",
        f"{LOG_FIELD_PREFIX}CWD {spec.cwd}",
        f"{LOG_FIELD_PREFIX}STARTED_AT {started_at}",
        f"{LOG_FIELD_PREFIX}COMMAND_COUNT {len(spec.commands)}",
    )
    for marker in fixed:
        index = _take_exact(lines, index, marker, f"{spec.lane_id} log marker")
    command_evidence: list[dict[str, object]] = []
    for command_index, command in enumerate(spec.commands, start=1):
        index = _take_exact(
            lines,
            index,
            f"{LOG_FIELD_PREFIX}COMMAND_BEGIN {command_index}",
            f"{spec.lane_id} command begin marker",
        )
        index = _take_exact(
            lines,
            index,
            f"{LOG_FIELD_PREFIX}LOGICAL_COMMAND "
            + json.dumps(command.logical_command(), ensure_ascii=True),
            f"{spec.lane_id} logical command marker",
        )
        index = _take_exact(
            lines,
            index,
            f"{LOG_FIELD_PREFIX}STDOUT_BEGIN {command_index}",
            f"{spec.lane_id} stdout begin marker",
        )
        stdout_lines, index = _take_stream(
            lines,
            index,
            f"{LOG_FIELD_PREFIX}STDOUT_END {command_index}",
            f"{spec.lane_id} stdout",
        )
        index = _take_exact(
            lines,
            index,
            f"{LOG_FIELD_PREFIX}STDERR_BEGIN {command_index}",
            f"{spec.lane_id} stderr begin marker",
        )
        stderr_lines, index = _take_stream(
            lines,
            index,
            f"{LOG_FIELD_PREFIX}STDERR_END {command_index}",
            f"{spec.lane_id} stderr",
        )
        index = _take_exact(
            lines,
            index,
            f"{LOG_FIELD_PREFIX}COMMAND_EXIT_CODE {command_index} 0",
            f"{spec.lane_id} command exit marker",
        )
        index = _take_exact(
            lines,
            index,
            f"{LOG_FIELD_PREFIX}COMMAND_END {command_index}",
            f"{spec.lane_id} command end marker",
        )
        command_evidence.append(
            _validate_command_success(
                spec.lane_id,
                command_index,
                command,
                stdout_lines,
                stderr_lines,
            )
        )
    tail = (
        f"{LOG_FIELD_PREFIX}ENDED_AT {ended_at}",
        f"{LOG_FIELD_PREFIX}EXIT_CODE 0",
        f"{LOG_FIELD_PREFIX}STATUS PASS",
        LOG_TAIL_MARKER,
    )
    for marker in tail:
        index = _take_exact(lines, index, marker, f"{spec.lane_id} log tail marker")
    require(index == len(lines), f"lane log has trailing content: {spec.lane_id}")
    require(lines.count(LOG_HEADER_MARKER) == 1, "lane log header marker count differs")
    require(lines.count(LOG_TAIL_MARKER) == 1, "lane log tail marker count differs")
    return {
        "lane_id": spec.lane_id,
        "command": list(spec.logical_commands),
        "command_evidence": command_evidence,
    }


def validate_lane_observation(
    value: Mapping[str, object], raw_log: bytes
) -> dict[str, object]:
    """Validate one manifest lane row and its exact event-local log."""

    require(
        set(value) == LANE_OBSERVATION_FIELDS,
        "lane observation fields differ",
    )
    lane_id = value.get("lane_id")
    require(type(lane_id) is str and lane_id in LANE_SPEC_BY_ID, "lane ID differs")
    spec = LANE_SPEC_BY_ID[lane_id]
    require(value.get("cwd") == spec.cwd, f"lane cwd differs: {lane_id}")
    require(
        type(value.get("command")) is list
        and value.get("command") == list(spec.logical_commands),
        f"lane commands differ: {lane_id}",
    )
    run_id = _validate_run_id(value.get("run_id"))
    source_set = value.get("source_content_set_sha256")
    require(
        type(source_set) is str and SHA256_RE.fullmatch(source_set) is not None,
        f"lane source content-set SHA-256 differs: {lane_id}",
    )
    for field in (
        "execution_input_content_set_sha256",
        "environment_receipt_sha256",
        "toolchain_receipt_sha256",
        "database_preflight_receipt_sha256",
        "database_runtime_receipt_sha256",
    ):
        require(
            type(value.get(field)) is str
            and SHA256_RE.fullmatch(str(value.get(field))) is not None,
            f"lane {field} differs: {lane_id}",
        )
    require(
        type(value.get("exit_code")) is int
        and value.get("exit_code") == 0
        and value.get("status") == "PASS",
        f"lane did not pass: {lane_id}",
    )
    require(
        value.get("log_path") == spec.log_relative.as_posix(),
        f"lane log path differs: {lane_id}",
    )
    require(
        value.get("log_header_marker") == LOG_HEADER_MARKER
        and value.get("log_tail_marker") == LOG_TAIL_MARKER,
        f"lane log boundary markers differ: {lane_id}",
    )
    require(
        type(value.get("log_byte_count")) is int
        and value.get("log_byte_count") == len(raw_log),
        f"lane log byte count differs: {lane_id}",
    )
    require(
        value.get("log_sha256") == sha256_bytes(raw_log),
        f"lane log SHA-256 differs: {lane_id}",
    )
    started_at = value.get("started_at")
    ended_at = value.get("ended_at")
    require(
        type(started_at) is str and type(ended_at) is str,
        f"lane timestamps differ: {lane_id}",
    )
    return validate_lane_log(
        raw_log,
        spec,
        run_id=run_id,
        source_content_set_sha256=source_set,
        started_at=started_at,
        ended_at=ended_at,
    )


def _run_lane(
    root: Path,
    spec: LaneSpec,
    *,
    run_id: str,
    source_set_sha256: str,
    python_executable: str,
    environment: Mapping[str, str],
    run: Callable[..., subprocess.CompletedProcess[bytes]],
    pass_lane_fd: bool,
    clock: Callable[[], datetime],
    not_before: datetime | None,
    after_command: Callable[[], None] | None = None,
) -> LaneResult:
    lane_descriptor, descriptor_cwd = _open_lane_cwd(root, spec.cwd, spec.lane_id)
    try:
        started, started_at = _now(clock, f"{spec.lane_id}.started_at")
        require(
            not_before is None or started >= not_before,
            f"global lane timestamp order differs: {spec.lane_id}",
        )
        secret_values = _secret_values(environment)
        results: list[CommandResult] = []
        for index, command in enumerate(spec.commands, start=1):
            command_environment = _resolved_environment(environment, command)
            command_environment["PWD"] = str(descriptor_cwd)
            command_environment.pop("OLDPWD", None)
            try:
                run_arguments: dict[str, object] = {
                    "cwd": descriptor_cwd,
                    "env": command_environment,
                    "check": False,
                    "stdout": subprocess.PIPE,
                    "stderr": subprocess.PIPE,
                }
                if pass_lane_fd:
                    run_arguments["pass_fds"] = (lane_descriptor,)
                completed = run(
                    list(command.execution_argv(python_executable, command_environment)),
                    **run_arguments,
                )
            except Exception:
                raise VerificationError(
                    f"lane command could not execute: {spec.lane_id} command {index}"
                ) from None
            require(
                type(completed.returncode) is int,
                f"lane exit code is invalid: {spec.lane_id} command {index}",
            )
            stdout = completed.stdout
            stderr = completed.stderr
            require(
                isinstance(stdout, bytes) and isinstance(stderr, bytes),
                f"lane output must be bytes: {spec.lane_id} command {index}",
            )
            require(
                len(stdout) <= LOG_LIMIT_BYTES and len(stderr) <= LOG_LIMIT_BYTES,
                f"lane output is too large: {spec.lane_id} command {index}",
            )
            if completed.returncode != 0:
                raise VerificationError(
                    f"lane failed closed: {spec.lane_id} command {index} "
                    f"exit {completed.returncode}"
                )
            results.append(
                CommandResult(
                    command.logical_command(),
                    redact_output(stdout, secret_values),
                    redact_output(stderr, secret_values),
                )
            )
            if after_command is not None:
                after_command()
        ended, ended_at = _now(clock, f"{spec.lane_id}.ended_at")
    finally:
        os.close(lane_descriptor)
    require(ended >= started, f"lane timestamp order differs: {spec.lane_id}")
    provisional = LaneResult(spec, started_at, ended_at, tuple(results), b"")
    log = _build_lane_log(
        provisional,
        run_id=run_id,
        source_set_sha256=source_set_sha256,
    )
    validate_lane_log(
        log,
        spec,
        run_id=run_id,
        source_content_set_sha256=source_set_sha256,
        started_at=started_at,
        ended_at=ended_at,
    )
    return LaneResult(spec, started_at, ended_at, tuple(results), log)


def _validate_capture_timeline(
    lane_results: Sequence[LaneResult], observed_at: str
) -> None:
    require(
        tuple(result.spec for result in lane_results) == LANE_SPECS,
        "capture lane order differs",
    )
    previous_end: datetime | None = None
    for result in lane_results:
        started = _parse_timestamp(
            result.started_at, f"{result.spec.lane_id}.started_at"
        )
        ended = _parse_timestamp(
            result.ended_at, f"{result.spec.lane_id}.ended_at"
        )
        require(
            ended >= started
            and (previous_end is None or started >= previous_end),
            f"global lane timestamp order differs: {result.spec.lane_id}",
        )
        previous_end = ended
    observed = _parse_timestamp(observed_at, "observed_at")
    require(
        previous_end is not None and observed >= previous_end,
        "observed_at precedes lane completion",
    )


def _v1_supersession_binding(root: Path) -> dict[str, object]:
    path = root / V1_OBSERVATION_MANIFEST_REL
    try:
        metadata = path.lstat()
    except FileNotFoundError as exc:
        raise VerificationError("v1 observation evidence is required for v2 correction") from exc
    require(
        stat.S_ISREG(metadata.st_mode)
        and not stat.S_ISLNK(metadata.st_mode)
        and stat.S_IMODE(metadata.st_mode) == OUTPUT_FILE_MODE
        and metadata.st_nlink == 1,
        "v1 observation evidence authority differs",
    )
    raw = _read_stable_external_file(
        path, V1_OBSERVATION_MANIFEST_REL.as_posix(), LOG_LIMIT_BYTES
    )
    require(
        len(raw) == SUPERSEDED_V1_OBSERVATION_BYTE_COUNT
        and sha256_bytes(raw) == SUPERSEDED_V1_OBSERVATION_SHA256,
        "v1 observation evidence differs from the rejected predecessor",
    )
    return {
        "schema_version": "1.0",
        "path": V1_OBSERVATION_MANIFEST_REL.as_posix(),
        "byte_count": len(raw),
        "sha256": sha256_bytes(raw),
    }


def _strict_json_object(raw: bytes, label: str) -> dict[str, object]:
    def reject_duplicates(pairs: list[tuple[str, object]]) -> dict[str, object]:
        value: dict[str, object] = {}
        for key, child in pairs:
            require(key not in value, f"{label} has duplicate JSON key: {key}")
            value[key] = child
        return value

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise VerificationError(f"{label} is invalid JSON") from exc
    require(type(value) is dict, f"{label} must be a JSON object")
    require(raw == json_text(value).encode("utf-8"), f"{label} is noncanonical")
    return value


def _validate_publication_outputs(
    root: Path,
    outputs: Mapping[Path, str],
    *,
    runtime_environment: Mapping[str, str] | None = None,
    refresh_toolchain: bool = False,
) -> dict[str, object]:
    """Replay the complete runner-owned semantic package before publication."""

    expected = tuple(spec.log_relative for spec in LANE_SPECS) + (
        OBSERVATION_MANIFEST_REL,
    )
    require(tuple(outputs) == expected, "publication output inventory differs")
    raw_by_path = {
        relative: content.encode("utf-8")
        for relative, content in outputs.items()
    }
    manifest_raw = raw_by_path[OBSERVATION_MANIFEST_REL]
    manifest = _strict_json_object(manifest_raw, "verification observation manifest")
    require(
        set(manifest)
        == {
            "schema_version",
            "goal_id",
            "run_id",
            "observed_at",
            "source_content_set_sha256",
            "lanes",
            "source_files",
            "execution_input_closure",
            "runner_toolchain_receipt",
            "lane_environment_receipt",
            "database_preflight_receipt",
            "database_runtime_receipt",
            "execution_source_boundary",
            "correction",
            "completion_boundary",
        },
        "verification observation fields differ",
    )
    require(
        manifest.get("schema_version") == OBSERVATION_SCHEMA
        and manifest.get("goal_id") == GOAL_ID
        and manifest.get("execution_source_boundary") == execution_source_boundary()
        and manifest.get("completion_boundary") == completion_boundary(),
        "verification observation identity or boundary differs",
    )
    run_id = _validate_run_id(manifest.get("run_id"))
    correction = manifest.get("correction")
    require(type(correction) is dict, "verification correction binding is missing")
    v1_binding = _v1_supersession_binding(root)
    require(
        correction.get("kind") == "ADDITIVE_SUPERSESSION"
        and correction.get("supersedes_schema") == "1.0"
        and correction.get("supersedes_path")
        == V1_OBSERVATION_MANIFEST_REL.as_posix()
        and correction.get("superseded_evidence_binding") == v1_binding
        and correction.get("v1_must_remain_add_only") is True
        and correction.get("promotion_authority") == "V2_ONLY",
        "verification correction predecessor differs",
    )

    visible = _capture_visible_inventory(root)
    closure = execution_input_closure(visible)
    require(
        manifest.get("execution_input_closure") == closure,
        "publication execution-input closure differs",
    )
    source_paths = product_source_paths_for_inventory(
        tuple(binding.path for binding in visible)
    )
    source_files = collect_source_files(root, source_paths)
    source_set = source_content_set_sha256(source_files)
    require(
        manifest.get("source_files") == list(source_files)
        and manifest.get("source_content_set_sha256") == source_set,
        "publication product source binding differs",
    )

    toolchain = manifest.get("runner_toolchain_receipt")
    require(type(toolchain) is dict, "publication toolchain receipt is missing")
    python = toolchain.get("python")
    require(type(python) is dict, "publication Python binding is missing")
    python_invocation_path = python.get("invocation_path")
    python_path = python.get("resolved_path")
    require(
        type(python_invocation_path) is str
        and python_invocation_path
        and type(python_path) is str
        and python_path
        and Path(python_invocation_path).resolve(strict=True) == Path(python_path),
        "publication Python path differs",
    )
    require(
        toolchain
        == _toolchain_receipt(
            root,
            closure,
            python_invocation_path,
            environment=runtime_environment,
            allow_cached=not refresh_toolchain,
        ),
        "publication toolchain receipt differs",
    )
    toolchain_hash = sha256_bytes(canonical_json_bytes(toolchain))
    environment_receipt = manifest.get("lane_environment_receipt")
    require(type(environment_receipt) is dict, "publication environment receipt is missing")
    environment_hash = sha256_bytes(canonical_json_bytes(environment_receipt))
    database = manifest.get("database_preflight_receipt")
    require(
        type(database) is dict
        and database.get("schema") == "walksafe.npc-database-preflight-receipt.v2"
        and database.get("status") == "PASS"
        and database.get("database_url_recorded") is False
        and database.get("database_url_logical_reference") == DATABASE_URL_REFERENCE,
        "publication database receipt differs",
    )
    database_hash = sha256_bytes(canonical_json_bytes(database))
    database_runtime = manifest.get("database_runtime_receipt")
    require(type(database_runtime) is dict, "publication database runtime receipt is missing")
    backend_log = raw_by_path[LANE_SPEC_BY_ID["BACKEND_RECOVERY_PYTEST"].log_relative]
    _validate_database_runtime_chain(database_runtime, backend_log)
    database_runtime_hash = sha256_bytes(canonical_json_bytes(database_runtime))
    if runtime_environment is not None:
        database_url = runtime_environment.get("WALKSAFE_TEST_DATABASE_URL", "").strip()
        live_database_state = _capture_database_state_receipt(database_url, "AFTER_TEST")
        require(
            database_runtime["states"][2] == live_database_state,
            "live database runtime state differs from verified final state",
        )

    lanes = manifest.get("lanes")
    require(
        type(lanes) is list
        and [row.get("lane_id") for row in lanes if type(row) is dict]
        == list(LANE_IDS)
        and len(lanes) == len(LANE_SPECS),
        "publication lane inventory differs",
    )
    timeline: list[LaneResult] = []
    for spec, row in zip(LANE_SPECS, lanes, strict=True):
        require(type(row) is dict, f"publication lane row differs: {spec.lane_id}")
        raw_log = raw_by_path[spec.log_relative]
        validate_lane_observation(row, raw_log)
        require(
            row.get("run_id") == run_id
            and row.get("source_content_set_sha256") == source_set
            and row.get("execution_input_content_set_sha256")
            == closure.get("content_set_sha256")
            and row.get("environment_receipt_sha256") == environment_hash
            and row.get("toolchain_receipt_sha256") == toolchain_hash
            and row.get("database_preflight_receipt_sha256") == database_hash
            and row.get("database_runtime_receipt_sha256")
            == database_runtime_hash,
            f"publication lane binding differs: {spec.lane_id}",
        )
        timeline.append(
            LaneResult(
                spec,
                str(row["started_at"]),
                str(row["ended_at"]),
                (),
                raw_log,
            )
        )
    _validate_capture_timeline(timeline, str(manifest.get("observed_at")))
    for relative, raw in raw_by_path.items():
        _assert_secret_free(raw, (), relative.as_posix())
    return manifest


def _capture_observation_core(
    *,
    root: Path,
    source_path_file: Path | None = None,
    environment: Mapping[str, str],
    python_executable: str,
    run: Callable[..., subprocess.CompletedProcess[bytes]],
    clock: Callable[[], datetime],
    run_id_factory: Callable[[], str],
    pass_lane_fd: bool = False,
) -> tuple[dict[str, object], dict[Path, str]]:
    """Common in-memory capture implementation; never publishes."""

    repository = _safe_root(root)
    python_executable = _python_invocation_path(python_executable)
    v1_binding = _v1_supersession_binding(repository)
    if source_path_file is not None:
        # Compatibility input is validation-only; it cannot narrow dynamically
        # discovered successor migrations out of the product binding.
        load_source_paths(source_path_file)
    run_id = _validate_run_id(run_id_factory())
    base_environment = dict(environment)
    try:
        with tempfile.TemporaryDirectory(
            prefix="walksafe-npc-recovery-verification-"
        ) as snapshot_name:
            snapshot = Path(snapshot_name)
            os.chmod(snapshot, SNAPSHOT_DIRECTORY_MODE, follow_symlinks=False)
            snapshot = _safe_root(snapshot)
            snapshot_inputs = _prepare_execution_snapshot(repository, snapshot)
            sealed_snapshot_namespace = _snapshot_namespace(snapshot)
            snapshot_paths = tuple(value.source.path for value in snapshot_inputs)
            source_paths = product_source_paths_for_inventory(snapshot_paths)
            for source_path in source_paths:
                _validate_source_path(source_path)
            require(
                all(source_path in snapshot_paths for source_path in source_paths),
                "NPC product source is not Git-visible",
            )
            initial_sources = collect_source_files(snapshot, source_paths)
            source_set_sha256 = source_content_set_sha256(initial_sources)
            closure = execution_input_closure(
                tuple(value.source for value in snapshot_inputs)
            )
            environment_receipt = _environment_receipt(base_environment)
            toolchain_receipt = _toolchain_receipt(
                snapshot,
                closure,
                python_executable,
                environment=base_environment,
                allow_cached=not pass_lane_fd,
            )
            environment_receipt_sha256 = sha256_bytes(
                canonical_json_bytes(environment_receipt)
            )
            toolchain_receipt_sha256 = sha256_bytes(
                canonical_json_bytes(toolchain_receipt)
            )

            lane_results: list[LaneResult] = []
            previous_end: datetime | None = None

            def seal_command_boundary() -> None:
                _assert_snapshot_inputs_unchanged(snapshot, snapshot_inputs)
                _remove_allowlisted_snapshot_outputs(
                    snapshot, sealed_snapshot_namespace
                )
                _assert_snapshot_inputs_unchanged(snapshot, snapshot_inputs)

            for spec in LANE_SPECS:
                lane_result = _run_lane(
                    snapshot,
                    spec,
                    run_id=run_id,
                    source_set_sha256=source_set_sha256,
                    python_executable=python_executable,
                    environment=base_environment,
                    run=run,
                    pass_lane_fd=pass_lane_fd,
                    clock=clock,
                    not_before=previous_end,
                    after_command=seal_command_boundary,
                )
                lane_results.append(lane_result)
                previous_end = _parse_timestamp(
                    lane_result.ended_at, f"{spec.lane_id}.ended_at"
                )
                seal_command_boundary()

            _assert_snapshot_inputs_unchanged(snapshot, snapshot_inputs)
            _assert_repository_matches_snapshot(repository, snapshot_inputs)
            final_snapshot_sources = collect_source_files(snapshot, source_paths)
            current_repository_sources = collect_source_files(
                repository, source_paths
            )
            require(
                initial_sources
                == final_snapshot_sources
                == current_repository_sources,
                "NPC product source differs from execution snapshot",
            )
            require(
                source_set_sha256
                == source_content_set_sha256(final_snapshot_sources),
                "implementation source content-set changed during verification",
            )
            if pass_lane_fd:
                require(
                    toolchain_receipt
                    == _toolchain_receipt(
                        snapshot,
                        closure,
                        python_executable,
                        environment=base_environment,
                        allow_cached=False,
                    ),
                    "runner toolchain changed during verification",
                )

            _, observed_at = _now(clock, "observed_at")
            _validate_capture_timeline(lane_results, observed_at)
            database_preflight_receipt = _database_preflight_receipt(lane_results)
            database_preflight_receipt_sha256 = sha256_bytes(
                canonical_json_bytes(database_preflight_receipt)
            )
            database_runtime_receipt = _database_runtime_receipt(lane_results)
            database_runtime_receipt_sha256 = sha256_bytes(
                canonical_json_bytes(database_runtime_receipt)
            )
            lane_rows: list[dict[str, object]] = []
            for result in lane_results:
                row: dict[str, object] = {
                    "lane_id": result.spec.lane_id,
                    "command": list(result.spec.logical_commands),
                    "cwd": result.spec.cwd,
                    "run_id": run_id,
                    "source_content_set_sha256": source_set_sha256,
                    "execution_input_content_set_sha256": closure[
                        "content_set_sha256"
                    ],
                    "environment_receipt_sha256": environment_receipt_sha256,
                    "toolchain_receipt_sha256": toolchain_receipt_sha256,
                    "database_preflight_receipt_sha256": (
                        database_preflight_receipt_sha256
                    ),
                    "database_runtime_receipt_sha256": (
                        database_runtime_receipt_sha256
                    ),
                    "started_at": result.started_at,
                    "ended_at": result.ended_at,
                    "exit_code": 0,
                    "status": "PASS",
                    "log_path": result.spec.log_relative.as_posix(),
                    "log_sha256": sha256_bytes(result.log),
                    "log_byte_count": len(result.log),
                    "log_header_marker": LOG_HEADER_MARKER,
                    "log_tail_marker": LOG_TAIL_MARKER,
                }
                validate_lane_observation(row, result.log)
                lane_rows.append(row)

            manifest: dict[str, object] = {
                "schema_version": OBSERVATION_SCHEMA,
                "goal_id": GOAL_ID,
                "run_id": run_id,
                "observed_at": observed_at,
                "source_content_set_sha256": source_set_sha256,
                "lanes": lane_rows,
                "source_files": [
                    deepcopy(row) for row in final_snapshot_sources
                ],
                "execution_input_closure": closure,
                "runner_toolchain_receipt": toolchain_receipt,
                "lane_environment_receipt": environment_receipt,
                "database_preflight_receipt": database_preflight_receipt,
                "database_runtime_receipt": database_runtime_receipt,
                "execution_source_boundary": execution_source_boundary(),
                "correction": {
                    "kind": "ADDITIVE_SUPERSESSION",
                    "supersedes_schema": "1.0",
                    "supersedes_path": V1_OBSERVATION_MANIFEST_REL.as_posix(),
                    "superseded_evidence_binding": v1_binding,
                    "v1_must_remain_add_only": True,
                    "promotion_authority": "V2_ONLY",
                    "reason": "Bind full execution closure and remove injected publication execution.",
                },
                "completion_boundary": completion_boundary(),
            }
            outputs = {
                **{
                    result.spec.log_relative: result.log.decode("utf-8")
                    for result in lane_results
                },
                OBSERVATION_MANIFEST_REL: json_text(manifest),
            }
    except OSError as exc:
        raise VerificationError("execution snapshot lifecycle failed") from exc

    secret_values = _secret_values(base_environment)
    for relative, content in outputs.items():
        _assert_secret_free(content.encode("utf-8"), secret_values, relative.as_posix())
    return manifest, outputs


def _capture_observation_for_test(
    *,
    root: Path,
    source_path_file: Path | None = None,
    environment: Mapping[str, str],
    python_executable: str,
    run: Callable[..., subprocess.CompletedProcess[bytes]],
    clock: Callable[[], datetime],
    run_id_factory: Callable[[], str],
    pass_lane_fd: bool = False,
) -> tuple[dict[str, object], dict[Path, str]]:
    """Test-only in-memory seam; it has no publication primitive."""

    return _capture_observation_core(
        root=root,
        source_path_file=source_path_file,
        environment=environment,
        python_executable=python_executable,
        run=run,
        clock=clock,
        run_id_factory=run_id_factory,
        pass_lane_fd=pass_lane_fd,
    )


def _open_held_directory_chain(
    root_descriptor: int, relative: Path, label: str
) -> tuple[int, tuple[int, ...], tuple[tuple[int, int, str, tuple[int, ...]], ...]]:
    """Open and retain every namespace component below a held root fd."""

    require(
        not relative.is_absolute()
        and all(part not in {"", ".", ".."} for part in relative.parts),
        f"{label} path differs",
    )
    descriptors: list[int] = [os.dup(root_descriptor)]
    steps: list[tuple[int, int, str, tuple[int, ...]]] = []
    try:
        current = descriptors[0]
        for component in relative.parts:
            named_before = os.stat(
                component, dir_fd=current, follow_symlinks=False
            )
            child = os.open(
                component,
                os.O_RDONLY
                | _required_open_flag("O_CLOEXEC")
                | _required_open_flag("O_DIRECTORY")
                | _required_open_flag("O_NOFOLLOW"),
                dir_fd=current,
            )
            opened = os.fstat(child)
            named_after = os.stat(
                component, dir_fd=current, follow_symlinks=False
            )
            require(
                stat.S_ISDIR(named_before.st_mode)
                and stat.S_ISDIR(opened.st_mode)
                and _directory_authority_identity(named_before)
                == _directory_authority_identity(opened)
                and _directory_authority_identity(opened)
                == _directory_authority_identity(named_after)
                and opened.st_uid == os.geteuid(),
                f"{label} namespace differs: {component}",
            )
            descriptors.append(child)
            steps.append(
                (current, child, component, _directory_authority_identity(opened))
            )
            current = child
        return current, tuple(descriptors), tuple(steps)
    except Exception:
        for descriptor in reversed(descriptors):
            os.close(descriptor)
        raise


def _validate_held_directory_chain(
    steps: Sequence[tuple[int, int, str, tuple[int, ...]]], label: str
) -> None:
    for parent, child, component, identity in steps:
        opened = os.fstat(child)
        named = os.stat(component, dir_fd=parent, follow_symlinks=False)
        require(
            stat.S_ISDIR(opened.st_mode)
            and _directory_authority_identity(opened) == identity
            and _directory_authority_identity(named) == identity,
            f"{label} namespace changed: {component}",
        )


def _write_private_exclusive_at(
    directory_descriptor: int, relative: Path, content: bytes
) -> None:
    require(
        not relative.is_absolute()
        and all(part not in {"", ".", ".."} for part in relative.parts),
        "publication member path differs",
    )
    current = os.dup(directory_descriptor)
    opened_directories: list[int] = [current]
    descriptor: int | None = None
    try:
        for component in relative.parts[:-1]:
            try:
                os.mkdir(component, SNAPSHOT_DIRECTORY_MODE, dir_fd=current)
                os.fsync(current)
            except FileExistsError:
                pass
            child = os.open(
                component,
                os.O_RDONLY
                | _required_open_flag("O_CLOEXEC")
                | _required_open_flag("O_DIRECTORY")
                | _required_open_flag("O_NOFOLLOW"),
                dir_fd=current,
            )
            metadata = os.fstat(child)
            require(
                stat.S_ISDIR(metadata.st_mode)
                and metadata.st_uid == os.geteuid()
                and stat.S_IMODE(metadata.st_mode) == SNAPSHOT_DIRECTORY_MODE,
                "publication staging directory authority differs",
            )
            opened_directories.append(child)
            current = child
        descriptor = os.open(
            relative.name,
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_NOFOLLOW"),
            OUTPUT_FILE_MODE,
            dir_fd=current,
        )
        view = memoryview(content)
        while view:
            written = os.write(descriptor, view)
            require(written > 0, "publication staging write became short")
            view = view[written:]
        os.fchmod(descriptor, OUTPUT_FILE_MODE)
        os.fsync(descriptor)
        metadata = os.fstat(descriptor)
        named = os.stat(relative.name, dir_fd=current, follow_symlinks=False)
        require(
            stat.S_ISREG(metadata.st_mode)
            and metadata.st_uid == os.geteuid()
            and metadata.st_nlink == 1
            and stat.S_IMODE(metadata.st_mode) == OUTPUT_FILE_MODE
            and _stable_identity(metadata) == _stable_identity(named),
            "publication staging member authority differs",
        )
        os.fsync(current)
    finally:
        if descriptor is not None:
            os.close(descriptor)
        for opened in reversed(opened_directories):
            os.close(opened)


def _remove_directory_tree_at(
    parent_descriptor: int, name: str, label: str
) -> None:
    named_before = os.stat(name, dir_fd=parent_descriptor, follow_symlinks=False)
    directory = os.open(
        name,
        os.O_RDONLY
        | _required_open_flag("O_CLOEXEC")
        | _required_open_flag("O_DIRECTORY")
        | _required_open_flag("O_NOFOLLOW"),
        dir_fd=parent_descriptor,
    )
    try:
        opened = os.fstat(directory)
        require(
            stat.S_ISDIR(named_before.st_mode)
            and _stable_identity(named_before) == _stable_identity(opened)
            and opened.st_uid == os.geteuid()
            and stat.S_IMODE(opened.st_mode) == SNAPSHOT_DIRECTORY_MODE,
            f"{label} directory authority differs",
        )
        for child_name in sorted(os.listdir(directory)):
            require(child_name not in {"", ".", ".."}, f"{label} member name differs")
            metadata = os.stat(
                child_name, dir_fd=directory, follow_symlinks=False
            )
            if stat.S_ISDIR(metadata.st_mode):
                _remove_directory_tree_at(directory, child_name, label)
                continue
            require(
                stat.S_ISREG(metadata.st_mode)
                and metadata.st_uid == os.geteuid()
                and metadata.st_nlink == 1
                and stat.S_IMODE(metadata.st_mode) == OUTPUT_FILE_MODE,
                f"{label} member authority differs",
            )
            os.unlink(child_name, dir_fd=directory)
        os.fsync(directory)
        named_after = os.stat(name, dir_fd=parent_descriptor, follow_symlinks=False)
        require(
            _directory_authority_identity(named_before)
            == _directory_authority_identity(named_after),
            f"{label} directory changed during cleanup",
        )
    finally:
        os.close(directory)
    os.rmdir(name, dir_fd=parent_descriptor)
    os.fsync(parent_descriptor)


def _private_output_inventory_at(
    directory_descriptor: int, label: str
) -> set[str]:
    values: set[str] = set()

    def visit(descriptor: int, prefix: str) -> None:
        for name in sorted(os.listdir(descriptor)):
            metadata = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
            relative = f"{prefix}/{name}" if prefix else name
            if stat.S_ISDIR(metadata.st_mode):
                require(
                    metadata.st_uid == os.geteuid()
                    and stat.S_IMODE(metadata.st_mode) == SNAPSHOT_DIRECTORY_MODE,
                    f"{label} namespace differs",
                )
                child = os.open(
                    name,
                    os.O_RDONLY
                    | _required_open_flag("O_CLOEXEC")
                    | _required_open_flag("O_DIRECTORY")
                    | _required_open_flag("O_NOFOLLOW"),
                    dir_fd=descriptor,
                )
                try:
                    visit(child, relative)
                finally:
                    os.close(child)
                continue
            require(
                stat.S_ISREG(metadata.st_mode)
                and metadata.st_uid == os.geteuid()
                and metadata.st_nlink == 1
                and stat.S_IMODE(metadata.st_mode) == OUTPUT_FILE_MODE,
                f"{label} member differs",
            )
            values.add(relative)

    visit(directory_descriptor, "")
    return values


def _read_private_relative_at(directory_descriptor: int, relative: Path) -> bytes:
    current = os.dup(directory_descriptor)
    opened: list[int] = [current]
    descriptor: int | None = None
    try:
        for component in relative.parts[:-1]:
            child = os.open(
                component,
                os.O_RDONLY
                | _required_open_flag("O_CLOEXEC")
                | _required_open_flag("O_DIRECTORY")
                | _required_open_flag("O_NOFOLLOW"),
                dir_fd=current,
            )
            opened.append(child)
            current = child
        before = os.stat(relative.name, dir_fd=current, follow_symlinks=False)
        descriptor = os.open(
            relative.name,
            os.O_RDONLY
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_NOFOLLOW"),
            dir_fd=current,
        )
        opened_file = os.fstat(descriptor)
        require(
            stat.S_ISREG(before.st_mode)
            and before.st_nlink == 1
            and before.st_uid == os.geteuid()
            and stat.S_IMODE(before.st_mode) == OUTPUT_FILE_MODE
            and _stable_identity(before) == _stable_identity(opened_file),
            "publication staged output authority differs",
        )
        chunks: list[bytes] = []
        while chunk := os.read(descriptor, 1024 * 1024):
            chunks.append(chunk)
        after = os.fstat(descriptor)
        named_after = os.stat(
            relative.name, dir_fd=current, follow_symlinks=False
        )
        require(
            _stable_identity(before) == _stable_identity(after)
            and _stable_identity(after) == _stable_identity(named_after),
            "publication staged output changed while read",
        )
        return b"".join(chunks)
    finally:
        if descriptor is not None:
            os.close(descriptor)
        for child in reversed(opened):
            os.close(child)


def _staged_outputs_seal_at(
    result_descriptor: int,
    stage_descriptor: int,
    stage_name: str,
    outputs: Mapping[Path, str],
) -> dict[str, object]:
    """Recursively seal one held stage after all legitimate writes are done."""

    named_before = os.stat(
        stage_name, dir_fd=result_descriptor, follow_symlinks=False
    )
    opened_before = os.fstat(stage_descriptor)
    authority = _directory_authority_identity(opened_before)
    require(
        stat.S_ISDIR(named_before.st_mode)
        and _directory_authority_identity(named_before) == authority
        and opened_before.st_uid == os.geteuid()
        and stat.S_IMODE(opened_before.st_mode) == SNAPSHOT_DIRECTORY_MODE,
        "publication stage namespace differs",
    )
    expected = {
        path.relative_to(CORRECTION_DIR_REL).as_posix(): content.encode("utf-8")
        for path, content in outputs.items()
    }
    require(
        _private_output_inventory_at(stage_descriptor, "publication staging")
        == set(expected),
        "publication staging inventory differs",
    )
    file_rows: list[dict[str, object]] = []
    for relative_value, expected_raw in sorted(expected.items()):
        relative = Path(relative_value)
        raw = _read_private_relative_at(stage_descriptor, relative)
        require(raw == expected_raw, f"publication staging bytes differ: {relative_value}")
        current = os.dup(stage_descriptor)
        opened: list[int] = [current]
        try:
            for component in relative.parts[:-1]:
                child = os.open(
                    component,
                    os.O_RDONLY
                    | _required_open_flag("O_CLOEXEC")
                    | _required_open_flag("O_DIRECTORY")
                    | _required_open_flag("O_NOFOLLOW"),
                    dir_fd=current,
                )
                opened.append(child)
                current = child
            metadata = os.stat(
                relative.name, dir_fd=current, follow_symlinks=False
            )
            require(
                stat.S_ISREG(metadata.st_mode)
                and metadata.st_uid == os.geteuid()
                and metadata.st_nlink == 1
                and stat.S_IMODE(metadata.st_mode) == OUTPUT_FILE_MODE
                and metadata.st_size == len(raw),
                f"publication staging member authority differs: {relative_value}",
            )
            file_rows.append(
                {
                    "path": relative_value,
                    "device": metadata.st_dev,
                    "inode": metadata.st_ino,
                    "owner_uid": metadata.st_uid,
                    "mode": stat.S_IMODE(metadata.st_mode),
                    "link_count": metadata.st_nlink,
                    "byte_count": len(raw),
                    "sha256": sha256_bytes(raw),
                }
            )
        finally:
            for child in reversed(opened):
                os.close(child)

    directory_rows: list[dict[str, object]] = []

    def visit(descriptor: int, relative: str) -> None:
        metadata = os.fstat(descriptor)
        require(
            stat.S_ISDIR(metadata.st_mode)
            and metadata.st_uid == os.geteuid()
            and stat.S_IMODE(metadata.st_mode) == SNAPSHOT_DIRECTORY_MODE,
            "publication staging directory authority differs",
        )
        directory_rows.append(
            {
                "path": relative,
                "device": metadata.st_dev,
                "inode": metadata.st_ino,
                "owner_uid": metadata.st_uid,
                "mode": stat.S_IMODE(metadata.st_mode),
            }
        )
        for name in sorted(os.listdir(descriptor)):
            child_metadata = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
            if not stat.S_ISDIR(child_metadata.st_mode):
                continue
            child = os.open(
                name,
                os.O_RDONLY
                | _required_open_flag("O_CLOEXEC")
                | _required_open_flag("O_DIRECTORY")
                | _required_open_flag("O_NOFOLLOW"),
                dir_fd=descriptor,
            )
            try:
                visit(child, f"{relative}/{name}" if relative else name)
            finally:
                os.close(child)

    visit(stage_descriptor, "")
    opened_after = os.fstat(stage_descriptor)
    named_after = os.stat(
        stage_name, dir_fd=result_descriptor, follow_symlinks=False
    )
    require(
        _stable_identity(opened_before) == _stable_identity(opened_after)
        and _directory_authority_identity(named_after) == authority
        and _directory_authority_identity(opened_after) == authority,
        "publication stage changed while sealed",
    )
    seal = {
        "stage_authority": list(authority),
        "directories": directory_rows,
        "files": file_rows,
    }
    return {
        **seal,
        "content_sha256": sha256_bytes(canonical_json_bytes(seal)),
    }


def _recover_publication_locked(result_descriptor: int) -> str:
    """Rollback only a descriptor-held unpublished stage; never promote it."""

    final_name = CORRECTION_DIR_REL.name
    try:
        final_metadata = os.stat(
            final_name, dir_fd=result_descriptor, follow_symlinks=False
        )
    except FileNotFoundError:
        final_metadata = None
    if final_metadata is not None:
        require(
            stat.S_ISDIR(final_metadata.st_mode),
            "published correction namespace differs",
        )
        final_descriptor = os.open(
            final_name,
            os.O_RDONLY
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_DIRECTORY")
            | _required_open_flag("O_NOFOLLOW"),
            dir_fd=result_descriptor,
        )
        try:
            try:
                os.stat(
                    TRANSACTION_JOURNAL_REL.name,
                    dir_fd=final_descriptor,
                    follow_symlinks=False,
                )
            except FileNotFoundError:
                pass
            else:
                raise VerificationError(
                    "legacy or forged publication journal requires manual quarantine"
                )
        finally:
            os.close(final_descriptor)
    stage_name = PUBLICATION_STAGE_REL.name
    try:
        os.stat(stage_name, dir_fd=result_descriptor, follow_symlinks=False)
    except FileNotFoundError:
        return "NONE"
    _remove_directory_tree_at(
        result_descriptor, stage_name, "publication staging"
    )
    return "ROLLED_BACK_PREPARATION"


def recover_publication_transaction(root: Path = ROOT) -> str:
    repository = _safe_root(root)
    descriptor, root_identity = _open_directory_root(
        repository, "publication repository root"
    )
    held: tuple[int, ...] = ()
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        result_descriptor, held, steps = _open_held_directory_chain(
            descriptor, RESULT_DIR_REL, "publication result directory"
        )
        _validate_open_root(
            repository,
            descriptor,
            root_identity,
            "publication repository root",
        )
        _validate_held_directory_chain(steps, "publication result directory")
        return _recover_publication_locked(result_descriptor)
    finally:
        for opened in reversed(held):
            os.close(opened)
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def _load_current_publication(
    root: Path,
    *,
    runtime_environment: Mapping[str, str] | None = None,
    refresh_toolchain: bool = False,
) -> tuple[dict[str, object], dict[Path, str]] | None:
    repository = _safe_root(root)
    root_descriptor, root_identity = _open_directory_root(
        repository, "published repository root"
    )
    held: tuple[int, ...] = ()
    final_descriptor: int | None = None
    try:
        result_descriptor, held, steps = _open_held_directory_chain(
            root_descriptor, RESULT_DIR_REL, "published result directory"
        )
        try:
            metadata = os.stat(
                CORRECTION_DIR_REL.name,
                dir_fd=result_descriptor,
                follow_symlinks=False,
            )
        except FileNotFoundError:
            return None
        final_descriptor = os.open(
            CORRECTION_DIR_REL.name,
            os.O_RDONLY
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_DIRECTORY")
            | _required_open_flag("O_NOFOLLOW"),
            dir_fd=result_descriptor,
        )
        opened = os.fstat(final_descriptor)
        require(
            stat.S_ISDIR(metadata.st_mode)
            and metadata.st_uid == os.geteuid()
            and stat.S_IMODE(metadata.st_mode) == SNAPSHOT_DIRECTORY_MODE
            and _stable_identity(metadata) == _stable_identity(opened),
            "published correction directory authority differs",
        )
        owned_paths = tuple(spec.log_relative for spec in LANE_SPECS) + (
            OBSERVATION_MANIFEST_REL,
        )
        allowed_files = {
            *(path.relative_to(CORRECTION_DIR_REL).as_posix() for path in owned_paths),
            *(f"lanes/{lane.lower()}-v2.json" for lane in LANE_IDS),
            "implementation-record-v2.json",
            "verification-result-v2.json",
            "successor-trace-v2.json",
            "review-subject-v2.json",
            "completion-receipt-v2.json",
            "completion-receipt-v3.json",
        }
        observed_files = _private_output_inventory_at(
            final_descriptor, "published correction"
        )
        for relative in observed_files:
            require(
                relative in allowed_files,
                f"published correction member differs: {relative}",
            )
        require(
            {path.relative_to(CORRECTION_DIR_REL).as_posix() for path in owned_paths}
            .issubset(observed_files),
            "published correction runner-owned output set is incomplete",
        )
        outputs = {
            path: _read_private_relative_at(
                final_descriptor, path.relative_to(CORRECTION_DIR_REL)
            ).decode("utf-8")
            for path in owned_paths
        }
        _validate_open_root(
            repository,
            root_descriptor,
            root_identity,
            "published repository root",
        )
        _validate_held_directory_chain(steps, "published result directory")
        require(
            _stable_identity(os.fstat(final_descriptor)) == _stable_identity(opened)
            == _stable_identity(
                os.stat(
                    CORRECTION_DIR_REL.name,
                    dir_fd=result_descriptor,
                    follow_symlinks=False,
                )
            ),
            "published correction namespace changed while read",
        )
    finally:
        if final_descriptor is not None:
            os.close(final_descriptor)
        for descriptor in reversed(held):
            os.close(descriptor)
        os.close(root_descriptor)
    manifest = _validate_publication_outputs(
        repository,
        outputs,
        runtime_environment=runtime_environment,
        refresh_toolchain=refresh_toolchain,
    )
    return manifest, outputs


@_bind_production_executor(_resolve_production_executor)
def capture_and_publish(
    *,
    root: Path,
    source_path_file: Path | None = None,
    production_run: Callable[..., subprocess.CompletedProcess[bytes]],
) -> dict[str, object]:
    """Run real subprocesses and atomically publish v2 evidence.

    This production boundary intentionally accepts no executor, clock,
    environment, Python, or run-ID injection.
    """

    repository = _safe_root(root)
    recover_publication_transaction(repository)
    environment = _production_environment()
    current = _load_current_publication(
        repository,
        runtime_environment=environment,
        refresh_toolchain=True,
    )
    if current is not None:
        return current[0]
    # Production capture is deliberately performed in this publication call
    # frame.  The module-level in-memory test seam and its injectable core are
    # not an authority boundary and cannot supply bytes to publication.
    python_executable = _python_invocation_path(sys.executable)
    v1_binding = _v1_supersession_binding(repository)
    if source_path_file is not None:
        load_source_paths(source_path_file)
    run_id = _validate_run_id(new_run_id())
    production_clock = lambda: datetime.now(TIMEZONE)
    base_environment = dict(environment)
    try:
        with tempfile.TemporaryDirectory(
            prefix="walksafe-npc-recovery-verification-"
        ) as snapshot_name:
            snapshot = Path(snapshot_name)
            os.chmod(snapshot, SNAPSHOT_DIRECTORY_MODE, follow_symlinks=False)
            snapshot = _safe_root(snapshot)
            snapshot_inputs = _prepare_execution_snapshot(repository, snapshot)
            sealed_snapshot_namespace = _snapshot_namespace(snapshot)
            snapshot_paths = tuple(value.source.path for value in snapshot_inputs)
            source_paths = product_source_paths_for_inventory(snapshot_paths)
            for source_path in source_paths:
                _validate_source_path(source_path)
            require(
                all(source_path in snapshot_paths for source_path in source_paths),
                "NPC product source is not Git-visible",
            )
            initial_sources = collect_source_files(snapshot, source_paths)
            source_set_sha256 = source_content_set_sha256(initial_sources)
            closure = execution_input_closure(
                tuple(value.source for value in snapshot_inputs)
            )
            environment_receipt = _environment_receipt(base_environment)
            toolchain_receipt = _toolchain_receipt(
                snapshot,
                closure,
                python_executable,
                environment=base_environment,
                allow_cached=False,
            )
            environment_receipt_sha256 = sha256_bytes(
                canonical_json_bytes(environment_receipt)
            )
            toolchain_receipt_sha256 = sha256_bytes(
                canonical_json_bytes(toolchain_receipt)
            )

            lane_results: list[LaneResult] = []
            previous_end: datetime | None = None

            def seal_command_boundary() -> None:
                _assert_snapshot_inputs_unchanged(snapshot, snapshot_inputs)
                _remove_allowlisted_snapshot_outputs(
                    snapshot, sealed_snapshot_namespace
                )
                _assert_snapshot_inputs_unchanged(snapshot, snapshot_inputs)

            for spec in LANE_SPECS:
                lane_result = _run_lane(
                    snapshot,
                    spec,
                    run_id=run_id,
                    source_set_sha256=source_set_sha256,
                    python_executable=python_executable,
                    environment=base_environment,
                    run=production_run,
                    pass_lane_fd=True,
                    clock=production_clock,
                    not_before=previous_end,
                    after_command=seal_command_boundary,
                )
                lane_results.append(lane_result)
                previous_end = _parse_timestamp(
                    lane_result.ended_at, f"{spec.lane_id}.ended_at"
                )
                seal_command_boundary()

            _assert_snapshot_inputs_unchanged(snapshot, snapshot_inputs)
            _assert_repository_matches_snapshot(repository, snapshot_inputs)
            final_snapshot_sources = collect_source_files(snapshot, source_paths)
            current_repository_sources = collect_source_files(
                repository, source_paths
            )
            require(
                initial_sources
                == final_snapshot_sources
                == current_repository_sources,
                "NPC product source differs from execution snapshot",
            )
            require(
                source_set_sha256
                == source_content_set_sha256(final_snapshot_sources),
                "implementation source content-set changed during verification",
            )
            require(
                toolchain_receipt
                == _toolchain_receipt(
                    snapshot,
                    closure,
                    python_executable,
                    environment=base_environment,
                    allow_cached=False,
                ),
                "runner toolchain changed during verification",
            )

            _, observed_at = _now(production_clock, "observed_at")
            _validate_capture_timeline(lane_results, observed_at)
            database_preflight_receipt = _database_preflight_receipt(lane_results)
            database_preflight_receipt_sha256 = sha256_bytes(
                canonical_json_bytes(database_preflight_receipt)
            )
            database_runtime_receipt = _database_runtime_receipt(lane_results)
            database_runtime_receipt_sha256 = sha256_bytes(
                canonical_json_bytes(database_runtime_receipt)
            )
            lane_rows: list[dict[str, object]] = []
            for result in lane_results:
                row: dict[str, object] = {
                    "lane_id": result.spec.lane_id,
                    "command": list(result.spec.logical_commands),
                    "cwd": result.spec.cwd,
                    "run_id": run_id,
                    "source_content_set_sha256": source_set_sha256,
                    "execution_input_content_set_sha256": closure[
                        "content_set_sha256"
                    ],
                    "environment_receipt_sha256": environment_receipt_sha256,
                    "toolchain_receipt_sha256": toolchain_receipt_sha256,
                    "database_preflight_receipt_sha256": (
                        database_preflight_receipt_sha256
                    ),
                    "database_runtime_receipt_sha256": (
                        database_runtime_receipt_sha256
                    ),
                    "started_at": result.started_at,
                    "ended_at": result.ended_at,
                    "exit_code": 0,
                    "status": "PASS",
                    "log_path": result.spec.log_relative.as_posix(),
                    "log_sha256": sha256_bytes(result.log),
                    "log_byte_count": len(result.log),
                    "log_header_marker": LOG_HEADER_MARKER,
                    "log_tail_marker": LOG_TAIL_MARKER,
                }
                validate_lane_observation(row, result.log)
                lane_rows.append(row)

            manifest = {
                "schema_version": OBSERVATION_SCHEMA,
                "goal_id": GOAL_ID,
                "run_id": run_id,
                "observed_at": observed_at,
                "source_content_set_sha256": source_set_sha256,
                "lanes": lane_rows,
                "source_files": [deepcopy(row) for row in final_snapshot_sources],
                "execution_input_closure": closure,
                "runner_toolchain_receipt": toolchain_receipt,
                "lane_environment_receipt": environment_receipt,
                "database_preflight_receipt": database_preflight_receipt,
                "database_runtime_receipt": database_runtime_receipt,
                "execution_source_boundary": execution_source_boundary(),
                "correction": {
                    "kind": "ADDITIVE_SUPERSESSION",
                    "supersedes_schema": "1.0",
                    "supersedes_path": V1_OBSERVATION_MANIFEST_REL.as_posix(),
                    "superseded_evidence_binding": v1_binding,
                    "v1_must_remain_add_only": True,
                    "promotion_authority": "V2_ONLY",
                    "reason": "Bind full execution closure and remove injected publication execution.",
                },
                "completion_boundary": completion_boundary(),
            }
            outputs = {
                **{
                    result.spec.log_relative: result.log.decode("utf-8")
                    for result in lane_results
                },
                OBSERVATION_MANIFEST_REL: json_text(manifest),
            }
    except OSError as exc:
        raise VerificationError("execution snapshot lifecycle failed") from exc

    secret_values = _secret_values(base_environment)
    for relative, content in outputs.items():
        _assert_secret_free(
            content.encode("utf-8"), secret_values, relative.as_posix()
        )
    # No module-level function can convert arbitrary/test bytes into production
    # authority.  Publication exists only in this real-executor call frame.
    expected = tuple(spec.log_relative for spec in LANE_SPECS) + (
        OBSERVATION_MANIFEST_REL,
    )
    require(tuple(outputs) == expected, "publication output inventory differs")
    _validate_publication_outputs(
        repository,
        outputs,
        runtime_environment=environment,
        refresh_toolchain=True,
    )

    descriptor, root_identity = _open_directory_root(
        repository, "publication repository root"
    )
    held: tuple[int, ...] = ()
    stage_descriptor: int | None = None
    stage_created = False
    renamed = False
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        result_descriptor, held, steps = _open_held_directory_chain(
            descriptor, RESULT_DIR_REL, "publication result directory"
        )
        recovery = _recover_publication_locked(result_descriptor)
        require(
            recovery in {"NONE", "ROLLED_BACK_PREPARATION"},
            "publication recovery status differs",
        )
        current = _load_current_publication(
            repository,
            runtime_environment=environment,
            refresh_toolchain=True,
        )
        if current is not None:
            _, existing_outputs = current
            require(existing_outputs == outputs, "existing publication differs")
            return current[0]

        _validate_open_root(
            repository,
            descriptor,
            root_identity,
            "publication repository root",
        )
        _validate_held_directory_chain(steps, "publication result directory")
        _validate_publication_outputs(
            repository,
            outputs,
            runtime_environment=environment,
            refresh_toolchain=True,
        )
        stage_name = PUBLICATION_STAGE_REL.name
        final_name = CORRECTION_DIR_REL.name
        try:
            os.stat(final_name, dir_fd=result_descriptor, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            raise VerificationError("publication correction directory already exists")
        os.mkdir(stage_name, SNAPSHOT_DIRECTORY_MODE, dir_fd=result_descriptor)
        os.fsync(result_descriptor)
        stage_created = True
        stage_descriptor = os.open(
            stage_name,
            os.O_RDONLY
            | _required_open_flag("O_CLOEXEC")
            | _required_open_flag("O_DIRECTORY")
            | _required_open_flag("O_NOFOLLOW"),
            dir_fd=result_descriptor,
        )
        for relative, content in outputs.items():
            _write_private_exclusive_at(
                stage_descriptor,
                relative.relative_to(CORRECTION_DIR_REL),
                content.encode("utf-8"),
            )
        stage_seal = _staged_outputs_seal_at(
            result_descriptor, stage_descriptor, stage_name, outputs
        )

        # First re-read every held staged child.  Then refresh the live tree,
        # toolchain and final DB state, and rename without another publication
        # callback.  Same-UID non-cooperating writers are explicitly outside
        # the evidence boundary recorded in the manifest.
        require(
            _staged_outputs_seal_at(
                result_descriptor, stage_descriptor, stage_name, outputs
            )
            == stage_seal,
            "publication staging recursive seal differs before rename",
        )
        _validate_publication_outputs(
            repository,
            outputs,
            runtime_environment=environment,
            refresh_toolchain=True,
        )
        _validate_open_root(
            repository,
            descriptor,
            root_identity,
            "publication repository root",
        )
        _validate_held_directory_chain(steps, "publication result directory")
        require(
            _directory_authority_identity(
                os.stat(stage_name, dir_fd=result_descriptor, follow_symlinks=False)
            )
            == tuple(stage_seal["stage_authority"])
            == _directory_authority_identity(os.fstat(stage_descriptor)),
            "publication stage namespace changed before rename",
        )
        try:
            os.stat(final_name, dir_fd=result_descriptor, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            raise VerificationError("publication final namespace changed before rename")
        os.rename(
            stage_name,
            final_name,
            src_dir_fd=result_descriptor,
            dst_dir_fd=result_descriptor,
        )
        renamed = True
        stage_created = False
        os.fsync(result_descriptor)
    except Exception:
        if stage_created and not renamed and held:
            if stage_descriptor is not None:
                os.close(stage_descriptor)
                stage_descriptor = None
            _remove_directory_tree_at(
                held[-1], PUBLICATION_STAGE_REL.name, "publication staging"
            )
        raise
    finally:
        if stage_descriptor is not None:
            os.close(stage_descriptor)
        for opened in reversed(held):
            os.close(opened)
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)

    published = _load_current_publication(repository)
    require(published is not None and published[1] == outputs, "published evidence differs")
    for relative in outputs:
        metadata = (repository / relative).lstat()
        require(
            stat.S_ISREG(metadata.st_mode)
            and not stat.S_ISLNK(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == OUTPUT_FILE_MODE,
            f"published evidence mode differs: {relative}",
        )
    return manifest


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--source-path-file", type=Path)
    parser.add_argument("--recover-only", action="store_true")
    parser.add_argument(
        "--emit-database-state-receipt",
        choices=("PRE_MIGRATION", "AFTER_MIGRATION", "AFTER_TEST"),
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.emit_database_state_receipt is not None:
            database_url = os.environ.get("DATABASE_URL", "").strip()
            receipt = _capture_database_state_receipt(
                database_url, args.emit_database_state_receipt
            )
            print(
                DATABASE_STATE_MARKER
                + json.dumps(
                    receipt,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                    allow_nan=False,
                )
            )
            return 0
        if args.recover_only:
            status = recover_publication_transaction(args.root)
            print("NPC single-admin recovery verification recovery: " + status)
            return 0
        capture_and_publish(root=args.root, source_path_file=args.source_path_file)
    except VerificationError as exc:
        secret_values = _secret_values(os.environ)
        safe = redact_output(str(exc).encode("utf-8", errors="replace"), secret_values)
        print(
            "NPC single-admin recovery verification FAIL: "
            + safe.decode("utf-8", errors="replace"),
            file=sys.stderr,
        )
        return 1
    print(
        "NPC single-admin recovery verification PASS: "
        + OBSERVATION_MANIFEST_REL.as_posix()
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
