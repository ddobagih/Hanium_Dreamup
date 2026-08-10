#!/usr/bin/env python3
"""Build the FP-046 repository-internal consent/deletion evidence trace.

The producer consumes four already-captured internal lane observations.  It does
not run formal tests, use a device, contact an external party, delete real data,
restore an operational backup, deploy, or update live control state.  Every
implementation source is bound by final byte length and SHA-256, not by path
alone.  Publication is add-only through the hardened FP-008 writer.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime
import importlib
from pathlib import Path
import re
import stat
import sys
from typing import Any, Mapping, Sequence


def _load_io_base() -> Any:
    try:
        from scripts import (
            build_walksafe_fp008_admin_review_delivery_trace_20260803 as module,
        )

        return module
    except ModuleNotFoundError:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module(
            "scripts.build_walksafe_fp008_admin_review_delivery_trace_20260803"
        )


io_base = _load_io_base()
ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R001"
POLICY_ID = "FP-046"
GAP_ID = "GAP-055"
RESULT_DIR_REL = Path(f"docs/control/execution/goal-results/{GOAL_ID}")
GOAL_REL = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-fp046-consent-withdrawal-deletion-r001.md"
)
START_GATE_DIR_REL = Path(
    "docs/control/execution/goal-gates/"
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-005"
)
START_GATE_REL = START_GATE_DIR_REL / "implementation-start-gate-receipt.json"
START_GATE_REPOSITORY_STATE_REL = START_GATE_DIR_REL / "09-REPOSITORY_STATE.log"

IMPLEMENTATION_REL = RESULT_DIR_REL / "implementation-record.json"
VERIFICATION_REL = RESULT_DIR_REL / "verification-result.json"
SUCCESSOR_REL = RESULT_DIR_REL / "successor-trace.json"
REVIEW_SUBJECT_REL = RESULT_DIR_REL / "review-subject.json"

EXPECTED_GOAL_SHA256 = (
    "f8f1fc9e9b8c1eaabe543cd64130b5a6dfa3b908b318033cb5969d4a724e8d79"
)
EXPECTED_START_GATE_SHA256 = (
    "ec17d2ad7f9a1216a9f0174588fe39e3411e9a9757c2dce6b3db48915978801e"
)
EXPECTED_START_GATE_REPOSITORY_STATE_SHA256 = (
    "f46e42faf5e824322036713759954bb38277cdf9a3f66752e79459164f6e9087"
)
EXPECTED_START_EVENT_SEQUENCE = 53
EXPECTED_START_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-005"
)

IMPLEMENTATION_SCOPE_KIND = "EXACT_ORDERED_FP046_FINAL_CONTENT_MANIFEST"
FORMAL_TEST_IDS = tuple(f"TC-FP-046-{number:02d}" for number in range(1, 6))
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
IMPLEMENTED_CONTROLS = (
    "service consent and optional model-improvement consent remain purpose-separated",
    "withdrawal and account deletion fail closed against new collection and transfer",
    "device, server, derived-data and backup deletion states remain independently monotonic and retryable",
    "restored backups reapply deletion tombstones before serving data",
    "source-less deletion receipts retain only bounded pseudonymous evidence",
)

BuildError = io_base.BuildError
require = io_base.require
canonical_json = io_base.canonical_json
json_text = io_base.json_text
bytes_sha256 = io_base.bytes_sha256
object_sha256 = io_base.object_sha256
strict_json_bytes = io_base.strict_json_bytes
require_document_matches_raw = io_base.require_document_matches_raw
read_bytes = io_base.read_bytes
file_binding = io_base.file_binding
sealed = io_base.sealed
verify_seal = io_base.verify_seal


@dataclass(frozen=True)
class SourceGroup:
    group_id: str
    paths: tuple[str, ...]


ANDROID_PATHS = (
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/AccountDeletionResetCoordinator.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/IntegratedConsentPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/PrivacyDeletionPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/AccountDeletionIntentFence.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/AccountDeletionDualAuthority.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/AccountDeletionIntentAuthority.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/AccountDeletionCallExecution.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/AccountDeletionRev0RecoveryPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidIntegratedConsentClient.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidPrivacyDeletionClient.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewaySessionProcessCoordinator.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSession.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayActivityCallbackPolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidAccountDeletionFallbackMarker.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/security/AndroidSensitivePreferenceStore.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionLog.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccountDeletionStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityWithdrawalRestartStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityIntegratedConsentStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/AccountDeletionResetCoordinatorTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/IntegratedConsentRevisionHardeningTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/PrivacyDeletionHardeningTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/PrivacyAccountDeletionPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/AccountDeletionIntentFenceTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/AccountDeletionCallExecutionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/AccountDeletionDualAuthorityTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/AccountDeletionIntentAuthorityTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/AccountDeletionAuthorityRestartTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/AccountDeletionRev0RecoveryPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/AndroidPrivacyDeletionAccountDeletionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/AndroidPrivacyDeletionOriginHardeningTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/GatewaySessionProcessCoordinatorTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSessionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/GatewayActivityCallbackPolicyTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/AndroidAccountDeletionFallbackMarkerTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/security/AndroidSensitivePreferenceStoreTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionAccountDeletionPrivacyFenceTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityNavigationCompositionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityLongLivedLoginStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/PriorityUserOnboardingStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/PersistentReportQueueDisabledStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/PermissionSessionLifecycleStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityWalkSessionLifecycleStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/fieldlog/PersistentFieldSessionLogTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityFirstRunRegistrationStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityFp016StaticTest.kt",
)

GATEWAY_PATHS = (
    "apps/android-gateway/README.md",
    "apps/android-gateway/server.ts",
    "apps/android-gateway/src/integrated-consent.ts",
    "apps/android-gateway/src/privacy-rights.ts",
    "apps/android-gateway/src/privacy-deletion-v2.ts",
    "apps/android-gateway/test/integrated-consent.test.ts",
    "apps/android-gateway/test/privacy-rights.test.ts",
    "apps/android-gateway/test/privacy-deletion-v2.test.ts",
    "apps/android-gateway/test/privacy-deletion-v2-crash.test.ts",
    "apps/android-gateway/test/privacy-deletion-v2-crash-worker.ts",
    "apps/android-gateway/openapi.json",
    "apps/android-gateway/src/auth.ts",
    "apps/android-gateway/src/backend.ts",
    "apps/android-gateway/src/field-long-session.ts",
    "apps/android-gateway/src/routes.ts",
    "apps/android-gateway/test/field-long-session.test.ts",
    "apps/android-gateway/test/gateway-contract.test.ts",
    "apps/android-gateway/test/node-adapter.test.ts",
)

BACKEND_MANIFEST_DRIFT_PATHS = (
    "backend/app/services/duplicates.py",
    "backend/app/services/actor_rate_limit.py",
)
BACKEND_MANIFEST_DRIFT_SEQ53_SHA256_BY_PATH = {
    Path("backend/app/services/duplicates.py"): (
        "a634823967b88e5c83d005960011b52eafc4821d22bdb7f7d9512d6a4113ce88"
    ),
    Path("backend/app/services/actor_rate_limit.py"): (
        "f43e142286e60e9f10222621bef01c2be9571d560aee0490dc0998a6ec050f3c"
    ),
}

BACKEND_PATHS = (
    "backend/alembic/versions/202608090001_fp046_privacy_lifecycle.py",
    "backend/app/main.py",
    "backend/app/models.py",
    "backend/app/schemas.py",
    "backend/app/openapi_contract.py",
    "backend/app/api/privacy.py",
    "backend/app/services/privacy_lifecycle.py",
    "backend/tests/test_privacy_lifecycle.py",
    "backend/tests/test_fp046_postgres_integration.py",
    "contracts/walksafe.openapi.json",
    "deploy/config/walksafe-backend.env.example",
    "backend/.env.example",
    "backend/app/api/health.py",
    "backend/app/api/reports.py",
    "backend/app/config.py",
    "backend/app/field_test_security.py",
    "backend/app/request_limits.py",
    *BACKEND_MANIFEST_DRIFT_PATHS,
    "backend/tests/conftest.py",
    "backend/tests/test_field_test_security.py",
    "backend/tests/test_health_readiness.py",
    "backend/tests/test_openapi_contract.py",
    "backend/tests/test_reports.py",
    "backend/tests/test_reports_v2.py",
)

BACKEND_TRANSITIVE_INPUT_SHA256_BY_PATH = {
    Path("backend/app/api/admin_security.py"): (
        "32fed4e8334f3946e9ed337309b1b6676a9097edbcb7b19265f2a6f6cbec8f19"
    ),
    Path("backend/app/api/android_debug.py"): (
        "6d10c1bc5f3a817ae267591c5f4ba9e30722a3400791f5e0bd6e7aa88293b0a7"
    ),
    Path("backend/app/api/detect.py"): (
        "bb3ef7520024f0845d2b9064dbb484654e74d3d23ba9938e003328bec98e3d60"
    ),
    Path("backend/app/api/navigation.py"): (
        "c2e30b526a9aefa2aefd8ef0589b790ac6b9dae0d8446a4a82549a5c40e36383"
    ),
    Path("backend/app/api/uploads.py"): (
        "d7864f59d0212abaa7b0982ca05fd5d818ceac570facd59d7b23d0f1f9c4709c"
    ),
    Path("backend/app/database.py"): (
        "a67304aadd42e976794918571f900b19195975b0ab2058d214edcd77b977efca"
    ),
    Path("backend/app/services/detect_v2.py"): (
        "26daecba7466585abde6a5d7f7162a0ec8a118858d3442993322cc1af8a38121"
    ),
    Path("backend/app/services/report_image_keys.py"): (
        "2d5b0f062128b0cf5634f1e9997a57ff14ee948d8be658f70783570ea1cc22cd"
    ),
    Path("backend/app/services/report_storage.py"): (
        "384c44d3094f4f5ef3f0a0c252c42ffd85728861d620542f188af15069929566"
    ),
    Path("backend/app/services/admin_device_proof.py"): (
        "78159a32bb43dec76a02147748b2357b1e766a5eb607b8422695e5c511476f08"
    ),
    Path("backend/app/services/admin_security.py"): (
        "8de2dbd58bce32ac84399c02aeabd5aa513d07cf86e80ae97ef3b86b0f1ece04"
    ),
    Path("model/two_model_runtime.py"): (
        "3350ab18b138956214b6db664adb9505757e66a898e44aee2cae78b10c8638a4"
    ),
    Path("backend/app/uploads.py"): (
        "edb6e460e6088029747249e8135d86fa05621fe6963031159d0dc361a3fdec16"
    ),
    Path("backend/app/detector.py"): (
        "beb342c27e0b0ea12b57b2b885940a17b10eaf98c94ec37afc33e4177ca5f24e"
    ),
    Path("backend/app/services/inference_process.py"): (
        "b22bf645b6a9d1e1519cdae22f9418fb285316cd369ac469745018a7ad9a9de5"
    ),
    Path("backend/app/services/tmap_pedestrian.py"): (
        "369eaee6d42be77965e77734a5eb94572bbdd8d71836d90f6091cc402347cd06"
    ),
    Path("backend/app/services/report_policy.py"): (
        "bd2b9920fe99b86de9bb7020acee69d5f0a5f7a46d4351cbc5e6ac66576de84b"
    ),
    Path("backend/app/services/report_read_audit.py"): (
        "a83595d1f45870c11ba7bdbd37741a7fbcb500ef64e665d9e4df7024d9b09e2e"
    ),
    Path("backend/app/services/report_serialization.py"): (
        "6be40db21117f51efaf780d64bcd031873726a72ad2a996efbb7b9bd98f79336"
    ),
    Path("backend/app/services/report_image_crypto.py"): (
        "da2016d076e33dfe08403c310dba08f5c402359abe05d265a370d44af8be0fb9"
    ),
    Path("backend/app/services/report_original_access.py"): (
        "014dd04a2cc645f93496d3587c99d1321ac289e0d41f21c1d6e6b264c7031298"
    ),
    Path("backend/app/services/admin_report_workflow.py"): (
        "2e323ccd88be95078d53bc5cd2e375933ae341a0d89c4f8c057c92a83bd3c8d4"
    ),
    Path("backend/app/services/yolo_inference_adapter.py"): (
        "631f61f18a179ffdb9378c460767c9037f8d97423ab8d0ec39906a451e37925f"
    ),
    Path("backend/app/services/walking_route_sanity.py"): (
        "7b136a395e71f2b1a1e7342f9ce311481047c9aad41256f25bafaf02096f880e"
    ),
    Path("backend/__init__.py"): (
        "c1553d7ecbd74ffe3d18502efbb9e899053eabc8ba7fd2e7c6e6f497594dd52b"
    ),
    Path("backend/app/__init__.py"): (
        "45390889d8a4a81a685fa8f8fb38422e5f6ff8840d7df1405f088ab5347ae8e2"
    ),
    Path("backend/app/api/__init__.py"): (
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    ),
    Path("backend/app/services/__init__.py"): (
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    ),
}

BACKEND_EXPLICIT_IMPORT_CLOSURE_PATHS = (
    "backend/app/api/admin_security.py",
    "backend/app/api/android_debug.py",
    "backend/app/api/detect.py",
    "backend/app/api/navigation.py",
    "backend/app/api/uploads.py",
    "backend/app/database.py",
    "backend/app/services/detect_v2.py",
    "backend/app/services/report_image_keys.py",
    "backend/app/services/report_storage.py",
    "backend/app/services/admin_device_proof.py",
    "backend/app/services/admin_security.py",
    "model/two_model_runtime.py",
    "backend/app/uploads.py",
    "backend/app/detector.py",
    "backend/app/services/inference_process.py",
    "backend/app/services/tmap_pedestrian.py",
    "backend/app/services/duplicates.py",
    "backend/app/services/report_policy.py",
    "backend/app/services/report_read_audit.py",
    "backend/app/services/report_serialization.py",
    "backend/app/services/report_image_crypto.py",
    "backend/app/services/report_original_access.py",
    "backend/app/services/admin_report_workflow.py",
    "backend/app/services/actor_rate_limit.py",
    "backend/app/services/yolo_inference_adapter.py",
    "backend/app/services/walking_route_sanity.py",
    "backend/__init__.py",
    "backend/app/__init__.py",
    "backend/app/api/__init__.py",
    "backend/app/services/__init__.py",
)

RETENTION_AND_TOOLING_PATHS = (
    "scripts/backup_walksafe_data_20260711.sh",
    "scripts/restore_walksafe_backup_drill_20260711.sh",
    "scripts/walksafe_backup_integrity.py",
    "tests/test_walksafe_backup_integrity.py",
    "tests/test_report_retention_operational_safety.py",
    "tests/test_report_retention_encrypted_objects.py",
    "scripts/build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810.py",
    "tests/test_walksafe_fp046_consent_withdrawal_deletion_trace_20260810.py",
    "scripts/build_walksafe_fp046_gap_backlog_r025_20260810.py",
    "tests/test_walksafe_fp046_gap_backlog_r025_20260810.py",
    "docs/backend/api_reference.md",
    "docs/backend/backend_environment.md",
    "scripts/run_walksafe_test_layers_20260711.sh",
)

IMPLEMENTATION_SOURCE_GROUPS = (
    SourceGroup("ANDROID_USER_APP_PRIVACY", ANDROID_PATHS),
    SourceGroup("ANDROID_GATEWAY_PRIVACY", GATEWAY_PATHS),
    SourceGroup("BACKEND_PRIVACY_LIFECYCLE", BACKEND_PATHS),
    SourceGroup("RETENTION_BACKUP_AND_TRACE_TOOLING", RETENTION_AND_TOOLING_PATHS),
)

FORBIDDEN_SOURCE_FRAGMENTS = (
    "legacy1",
    "legacy2",
    "legacy3",
    "r034",
    "r035",
    "apps/web",
    "/web/",
    "pwa",
    "adminapp",
    "docs/submission",
    "old_submission",
    "old-submission",
    "review-subject",
    "review-attestation",
    "independent-review",
    "completion-receipt",
)


def _path_forbidden(path: str) -> bool:
    normalized = path.replace("\\", "/").lower()
    collapsed = re.sub(r"[^a-z0-9]", "", normalized)
    components = {
        re.sub(r"[^a-z0-9]", "", component)
        for component in normalized.split("/")
    }
    return (
        any(fragment in normalized for fragment in FORBIDDEN_SOURCE_FRAGMENTS)
        or any(
            marker in collapsed
            for marker in ("legacy1", "legacy2", "legacy3", "r034", "r035")
        )
        or bool(components & {"web", "pwa", "submission", "adminapp"})
    )


def validate_backend_explicit_import_closure(
    root: Path,
    *,
    source_groups: Sequence[SourceGroup] = IMPLEMENTATION_SOURCE_GROUPS,
) -> dict[str, tuple[dict[str, Any], ...]]:
    groups = tuple(source_groups)
    backend_groups = [
        group
        for group in groups
        if group.group_id == "BACKEND_PRIVACY_LIFECYCLE"
    ]
    require(
        len(backend_groups) == 1,
        "FP-046 backend manifest group differs",
    )
    manifest_paths = tuple(path for group in groups for path in group.paths)
    backend_paths = backend_groups[0].paths
    drift_paths = BACKEND_MANIFEST_DRIFT_PATHS
    seq53_paths = tuple(
        path.as_posix()
        for path in BACKEND_MANIFEST_DRIFT_SEQ53_SHA256_BY_PATH
    )
    transitive_paths = tuple(
        path.as_posix() for path in BACKEND_TRANSITIVE_INPUT_SHA256_BY_PATH
    )
    closure_paths = BACKEND_EXPLICIT_IMPORT_CLOSURE_PATHS
    drift_set = set(drift_paths)
    transitive_set = set(transitive_paths)
    closure_set = set(closure_paths)

    require(
        len(drift_paths) == len(drift_set) == 2
        and seq53_paths == drift_paths,
        "FP-046 backend manifest drift partition differs",
    )
    require(
        len(transitive_paths) == len(transitive_set) == 28,
        "FP-046 backend transitive input partition differs",
    )
    require(
        len(closure_paths) == len(closure_set) == 30
        and not (drift_set & transitive_set)
        and drift_set | transitive_set == closure_set,
        "FP-046 backend explicit import closure partition differs",
    )
    require(
        tuple(path for path in closure_paths if path in drift_set)
        == drift_paths
        and tuple(path for path in closure_paths if path in transitive_set)
        == transitive_paths,
        "FP-046 backend explicit import closure order differs",
    )
    require(
        tuple(path for path in backend_paths if path in drift_set)
        == drift_paths
        and drift_set <= set(backend_paths)
        and transitive_set.isdisjoint(manifest_paths),
        "FP-046 backend manifest/transitive partition differs",
    )

    drift_bindings: list[dict[str, Any]] = []
    for relative, seq53_sha256 in (
        BACKEND_MANIFEST_DRIFT_SEQ53_SHA256_BY_PATH.items()
    ):
        require(
            SHA256_RE.fullmatch(seq53_sha256) is not None,
            "FP-046 backend seq53 drift digest differs",
        )
        binding = file_binding(
            root,
            relative,
            "FP046_BACKEND_MANIFEST_DRIFT_SOURCE",
        )
        require(
            binding["sha256"] != seq53_sha256,
            f"FP-046 backend source did not drift after seq53: {relative}",
        )
        drift_bindings.append(binding)

    transitive_bindings: list[dict[str, Any]] = []
    for relative, expected_sha256 in (
        BACKEND_TRANSITIVE_INPUT_SHA256_BY_PATH.items()
    ):
        require(
            SHA256_RE.fullmatch(expected_sha256) is not None
            and not _path_forbidden(relative.as_posix()),
            "FP-046 backend transitive input pin differs",
        )
        binding = file_binding(
            root,
            relative,
            "FP046_BACKEND_TRANSITIVE_INPUT",
        )
        require(
            binding["sha256"] == expected_sha256,
            f"FP-046 backend transitive source SHA-256 differs: {relative}",
        )
        transitive_bindings.append(binding)

    return {
        "manifest_drift": tuple(drift_bindings),
        "transitive_inputs": tuple(transitive_bindings),
    }


def completion_boundary() -> dict[str, Any]:
    return {
        "scope": "REPOSITORY_INTERNAL_FP046_IMPLEMENTATION_AND_AUTOMATED_VERIFICATION_ONLY",
        "planned_test_ids": list(FORMAL_TEST_IDS),
        "formal_test_status": "NOT_RUN",
        "formal_test_credit_count": 0,
        "actual_device_status": "NOT_RUN",
        "actual_device_credit_count": 0,
        "actual_user_or_guardian_status": "NOT_RUN",
        "actual_rights_request_status": "NOT_RUN",
        "actual_personal_data_deletion_status": "NOT_RUN",
        "external_legal_review_status": "NOT_RUN",
        "external_privacy_review_status": "NOT_RUN",
        "external_processor_status": "NOT_RUN",
        "operational_database_status": "NOT_RUN",
        "operational_backup_restore_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_gate_status": "NOT_RUN",
        "release_gates_waived": False,
        "artifact_approval_claimed": False,
        "release_status": "NOT_ELIGIBLE",
        "release_credit_count": 0,
    }


def lane_evidence_boundary() -> dict[str, Any]:
    return {
        "evidence_kind": "REPOSITORY_INTERNAL_AUTOMATED_CHECK",
        "credit_scope": "INTERNAL_ONLY",
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "external_status": "NOT_RUN",
        "deployment_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
    }


@dataclass(frozen=True)
class LaneSpec:
    lane_id: str
    title: str
    receipt_name: str
    expected_command: str
    expected_passed: int
    runner_summary_pattern: str

    @property
    def receipt_rel(self) -> Path:
        return RESULT_DIR_REL / "evidence" / f"{self.receipt_name}-receipt.json"

    @property
    def log_rel(self) -> Path:
        return RESULT_DIR_REL / "logs" / f"{self.receipt_name}.log"


LOCKED_TEST_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)
LANES = (
    LaneSpec(
        "ANDROID_CONSENT_DELETION",
        "Android consent, withdrawal and deletion fail-closed boundary",
        "android-consent-deletion",
        "cd apps/android && ./gradlew :app:testDebugUnitTest --offline --no-daemon "
        "--rerun-tasks",
        981,
        r"JUnit tests=981 failures=0 errors=0 skipped=0",
    ),
    LaneSpec(
        "GATEWAY_PRIVACY_LEDGER",
        "Android Gateway consent revision and deletion ledger",
        "gateway-privacy-ledger",
        "cd apps/android-gateway && npm run typecheck && npm test",
        88,
        r"Node tests=88 pass=88 fail=0; typecheck=PASS; build=PASS",
    ),
    LaneSpec(
        "BACKEND_PRIVACY_POSTGRES",
        "Backend privacy lifecycle and PostgreSQL invariants",
        "backend-privacy-postgres",
        "env WALKSAFE_TEST_DATABASE_URL='postgresql+psycopg://walksafe_test:"
        "walksafe_test_password@127.0.0.1:32768/walksafe_fp008_test' PYTHONPATH=. "
        f"{LOCKED_TEST_PYTHON} -m pytest -p no:cacheprovider -q "
        "backend/tests/test_privacy_lifecycle.py "
        "backend/tests/test_fp046_postgres_integration.py",
        57,
        r"57 passed in [0-9]+(?:\.[0-9]+)?s",
    ),
    LaneSpec(
        "RETENTION_BACKUP_DELETION",
        "Retention, backup and deletion-tombstone boundary",
        "retention-backup-deletion",
        ".venv/bin/python -m pytest -p no:cacheprovider -q "
        "tests/test_report_retention_operational_safety.py "
        "tests/test_report_retention_encrypted_objects.py "
        "tests/test_walksafe_backup_integrity.py",
        92,
        r"92 passed in [0-9]+(?:\.[0-9]+)?s",
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
    require(parsed.isoformat() == value, f"{label} must use canonical ISO-8601")
    return parsed


def _validate_binding(value: Any, role: str, path: Path) -> dict[str, Any]:
    require(type(value) is dict, f"{role} binding missing")
    require(value.get("role") == role, f"{role} binding role differs")
    require(value.get("path") == path.as_posix(), f"{role} binding path differs")
    require(
        type(value.get("byte_length")) is int and value["byte_length"] >= 0,
        f"{role} binding byte length differs",
    )
    require(
        type(value.get("sha256")) is str
        and SHA256_RE.fullmatch(value["sha256"]) is not None,
        f"{role} binding digest differs",
    )
    return deepcopy(value)


def _validate_authority_mapping(value: Mapping[str, Any]) -> dict[str, Any]:
    require(
        set(value) == {"goal_binding", "start_gate_binding", "gate_ended_at"},
        "FP-046 authority fields differ",
    )
    goal = _validate_binding(value["goal_binding"], "FP046_GOAL", GOAL_REL)
    gate = _validate_binding(
        value["start_gate_binding"], "FP046_EXACT9_START_GATE", START_GATE_REL
    )
    require(
        gate.get("repository_state_path")
        == START_GATE_REPOSITORY_STATE_REL.as_posix(),
        "FP-046 gate repository-state path differs",
    )
    require(
        type(gate.get("repository_state_sha256")) is str
        and SHA256_RE.fullmatch(gate["repository_state_sha256"]) is not None,
        "FP-046 gate repository-state digest differs",
    )
    require(
        gate.get("event_sequence") == EXPECTED_START_EVENT_SEQUENCE
        and gate.get("event_id") == EXPECTED_START_EVENT_ID,
        "FP-046 start event binding differs",
    )
    _parse_time(value["gate_ended_at"], "FP-046 gate end")
    return {
        "goal_binding": goal,
        "start_gate_binding": gate,
        "gate_ended_at": value["gate_ended_at"],
    }


def validate_authority(root: Path = ROOT) -> dict[str, Any]:
    goal_raw = read_bytes(root, GOAL_REL)
    require(bytes_sha256(goal_raw) == EXPECTED_GOAL_SHA256, "FP-046 goal bytes differ")
    gate_raw = read_bytes(root, START_GATE_REL)
    require(
        bytes_sha256(gate_raw) == EXPECTED_START_GATE_SHA256,
        "FP-046 start-gate bytes differ",
    )
    gate = strict_json_bytes(gate_raw, "FP-046 start gate")
    require(
        gate.get("status") == "PASS"
        and gate.get("gate_purpose") == "INITIAL_START"
        and gate.get("target_goal_id") == GOAL_ID
        and gate.get("target_transition_event_id") == EXPECTED_START_EVENT_ID
        and gate.get("target_goal_content_sha256") == EXPECTED_GOAL_SHA256,
        "FP-046 start-gate identity differs",
    )
    checks = gate.get("check_runs")
    require(type(checks) is list and len(checks) == 9, "FP-046 gate is not exact 9-check")
    require(
        checks[-1].get("check_id") == "REPOSITORY_STATE"
        and checks[-1].get("output_path")
        == START_GATE_REPOSITORY_STATE_REL.as_posix(),
        "FP-046 repository-state check differs",
    )
    repository_state_raw = read_bytes(root, START_GATE_REPOSITORY_STATE_REL)
    require(
        bytes_sha256(repository_state_raw)
        == EXPECTED_START_GATE_REPOSITORY_STATE_SHA256,
        "FP-046 repository-state bytes differ",
    )
    require(
        gate.get("repository_snapshot", {}).get(
            "gate_repository_state_output_sha256"
        )
        == EXPECTED_START_GATE_REPOSITORY_STATE_SHA256,
        "FP-046 repository-state binding differs",
    )
    return _validate_authority_mapping(
        {
            "goal_binding": {
                "role": "FP046_GOAL",
                "path": GOAL_REL.as_posix(),
                "byte_length": len(goal_raw),
                "sha256": bytes_sha256(goal_raw),
            },
            "start_gate_binding": {
                "role": "FP046_EXACT9_START_GATE",
                "path": START_GATE_REL.as_posix(),
                "byte_length": len(gate_raw),
                "sha256": bytes_sha256(gate_raw),
                "repository_state_path": START_GATE_REPOSITORY_STATE_REL.as_posix(),
                "repository_state_sha256": bytes_sha256(repository_state_raw),
                "event_sequence": EXPECTED_START_EVENT_SEQUENCE,
                "event_id": EXPECTED_START_EVENT_ID,
            },
            "gate_ended_at": gate["execution_window"]["ended_at"],
        }
    )


def build_final_content_manifest(
    root: Path,
    groups: Sequence[SourceGroup] = IMPLEMENTATION_SOURCE_GROUPS,
) -> dict[str, Any]:
    require(groups, "FP-046 source groups are empty")
    if tuple(groups) == IMPLEMENTATION_SOURCE_GROUPS:
        validate_backend_explicit_import_closure(
            root,
            source_groups=groups,
        )
    group_ids = [group.group_id for group in groups]
    require(
        len(group_ids) == len(set(group_ids))
        and all(type(group_id) is str and group_id for group_id in group_ids),
        "FP-046 source group IDs differ",
    )
    files: list[dict[str, Any]] = []
    all_paths: list[str] = []
    for group in groups:
        require(group.paths, f"empty FP-046 source group: {group.group_id}")
        require(
            len(group.paths) == len(set(group.paths)),
            f"duplicate FP-046 source in group: {group.group_id}",
        )
        for path in group.paths:
            require(type(path) is str and path, "FP-046 source path is empty")
            require(not _path_forbidden(path), f"forbidden FP-046 source: {path}")
            binding = file_binding(root, Path(path), "FP046_FINAL_SOURCE_CONTENT")
            binding["group_id"] = group.group_id
            files.append(binding)
            all_paths.append(path)
    require(len(all_paths) == len(set(all_paths)), "duplicate FP-046 source path")
    manifest = {
        "schema_version": "walksafe.fp046-final-content-manifest.v1",
        "scope_kind": IMPLEMENTATION_SCOPE_KIND,
        "group_order": group_ids,
        "exact_path_count": len(files),
        "path_set_sha256": object_sha256(all_paths),
        "content_set_sha256": object_sha256(files),
        "files": files,
    }
    return sealed(manifest, "manifest_content_sha256")


def validate_final_content_manifest(
    value: Mapping[str, Any],
    *,
    root: Path | None = None,
    expected_groups: Sequence[SourceGroup] | None = None,
) -> str:
    expected_fields = {
        "schema_version",
        "scope_kind",
        "group_order",
        "exact_path_count",
        "path_set_sha256",
        "content_set_sha256",
        "files",
        "manifest_content_sha256",
    }
    require(set(value) == expected_fields, "FP-046 final manifest fields differ")
    require(
        value.get("schema_version") == "walksafe.fp046-final-content-manifest.v1"
        and value.get("scope_kind") == IMPLEMENTATION_SCOPE_KIND,
        "FP-046 final manifest identity differs",
    )
    files = value.get("files")
    require(type(files) is list and files, "FP-046 final manifest files missing")
    paths: list[str] = []
    groups: list[str] = []
    for row in files:
        require(
            type(row) is dict
            and set(row)
            == {"role", "path", "byte_length", "sha256", "group_id"},
            "FP-046 final file binding fields differ",
        )
        require(
            row.get("role") == "FP046_FINAL_SOURCE_CONTENT"
            and type(row.get("path")) is str
            and row["path"]
            and not _path_forbidden(row["path"]),
            "FP-046 final file identity differs",
        )
        require(
            type(row.get("byte_length")) is int and row["byte_length"] >= 0,
            "FP-046 final file byte length differs",
        )
        require(
            type(row.get("sha256")) is str
            and SHA256_RE.fullmatch(row["sha256"]) is not None,
            "FP-046 final file digest differs",
        )
        require(
            type(row.get("group_id")) is str and row["group_id"],
            "FP-046 final file group differs",
        )
        paths.append(row["path"])
        groups.append(row["group_id"])
    require(
        len(paths) == len(set(paths)) == value.get("exact_path_count"),
        "FP-046 final manifest path count differs",
    )
    require(
        type(value.get("group_order")) is list
        and value["group_order"]
        and list(dict.fromkeys(groups)) == value["group_order"],
        "FP-046 final manifest group order differs",
    )
    if expected_groups is not None:
        expected_group_order = [group.group_id for group in expected_groups]
        expected_layout = [
            (path, group.group_id)
            for group in expected_groups
            for path in group.paths
        ]
        require(
            value["group_order"] == expected_group_order,
            "FP-046 final manifest canonical group order differs",
        )
        require(
            list(zip(paths, groups, strict=True)) == expected_layout,
            "FP-046 final manifest canonical source set differs",
        )
    require(
        value.get("path_set_sha256") == object_sha256(paths)
        and value.get("content_set_sha256") == object_sha256(files),
        "FP-046 final manifest derived hash differs",
    )
    verify_seal(value, "manifest_content_sha256", "FP-046 final manifest")
    if root is not None:
        if (
            expected_groups is not None
            and tuple(expected_groups) == IMPLEMENTATION_SOURCE_GROUPS
        ):
            validate_backend_explicit_import_closure(
                root,
                source_groups=expected_groups,
            )
        resolved_root = root.resolve(strict=True)
        for row in files:
            current = file_binding(
                resolved_root,
                Path(row["path"]),
                "FP046_FINAL_SOURCE_CONTENT",
            )
            current["group_id"] = row["group_id"]
            require(
                current == row,
                f"FP-046 current file binding differs: {row['path']}",
            )
    return value["content_set_sha256"]


def _validate_metrics(metrics: Any, spec: LaneSpec) -> dict[str, Any]:
    lane_id = spec.lane_id
    expected_fields = {
        "result_format",
        "passed",
        "failed",
        "errors",
        "skipped",
    }
    require(type(metrics) is dict and set(metrics) == expected_fields, f"{lane_id} metric fields differ")
    require(metrics.get("result_format") == "INTERNAL_TEST_SUMMARY_V1", f"{lane_id} result format differs")
    for key in ("passed", "failed", "errors", "skipped"):
        require(type(metrics.get(key)) is int and metrics[key] >= 0, f"{lane_id} metric differs: {key}")
    require(
        metrics["passed"] == spec.expected_passed,
        f"{lane_id} exact passing-test count differs",
    )
    require(
        metrics["failed"] == 0
        and metrics["errors"] == 0
        and metrics["skipped"] == 0,
        f"{lane_id} did not pass without skips",
    )
    return deepcopy(metrics)


def _validate_lane_result(
    spec: LaneSpec, observation: Mapping[str, Any], raw_output: bytes
) -> dict[str, Any]:
    require(observation.get("command") == spec.expected_command, f"{spec.lane_id} command differs")
    metrics = _validate_metrics(observation.get("metrics"), spec)
    try:
        output = raw_output.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BuildError(f"{spec.lane_id} raw output is not UTF-8") from exc
    lines = output.splitlines()
    command_marker = f"WALKSAFE_FP046_COMMAND {spec.expected_command}"
    summary_marker = (
        "WALKSAFE_FP046_SUMMARY "
        f"passed={metrics['passed']} failed={metrics['failed']} "
        f"errors={metrics['errors']} skipped={metrics['skipped']}"
    )
    require(
        [line for line in lines if line.startswith("WALKSAFE_FP046_COMMAND ")]
        == [command_marker],
        f"{spec.lane_id} raw command marker differs",
    )
    require(
        [line for line in lines if line.startswith("WALKSAFE_FP046_SUMMARY ")]
        == [summary_marker],
        f"{spec.lane_id} raw summary marker differs",
    )
    require(
        [line for line in lines if line.startswith("WALKSAFE_FP046_STARTED_AT ")]
        == [f"WALKSAFE_FP046_STARTED_AT {observation['started_at']}"],
        f"{spec.lane_id} raw start marker differs",
    )
    require(
        [line for line in lines if line.startswith("WALKSAFE_FP046_EXIT_CODE ")]
        == ["WALKSAFE_FP046_EXIT_CODE 0"],
        f"{spec.lane_id} raw exit marker differs",
    )
    require(
        [line for line in lines if line.startswith("WALKSAFE_FP046_ENDED_AT ")]
        == [f"WALKSAFE_FP046_ENDED_AT {observation['ended_at']}"],
        f"{spec.lane_id} raw end marker differs",
    )
    require(
        len(
            [
                line
                for line in lines
                if re.fullmatch(spec.runner_summary_pattern, line) is not None
            ]
        )
        == 1,
        f"{spec.lane_id} runner summary differs",
    )
    return metrics


def _validate_lane_observation(
    spec: LaneSpec,
    value: Mapping[str, Any],
    raw_output: bytes,
    gate_ended_at: str,
) -> dict[str, Any]:
    exact_fields = {
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
    require(set(value) == exact_fields, f"{spec.lane_id} observation fields differ")
    require(
        value.get("schema_version") == "walksafe.fp046-internal-lane-observation.v1"
        and value.get("lane_id") == spec.lane_id,
        f"{spec.lane_id} observation identity differs",
    )
    require(
        value.get("status") == "PASS"
        and type(value.get("exit_code")) is int
        and value["exit_code"] == 0,
        f"{spec.lane_id} observation did not pass",
    )
    require(value.get("evidence_boundary") == lane_evidence_boundary(), f"{spec.lane_id} evidence boundary differs")
    require(
        type(value.get("raw_output_byte_length")) is int
        and value["raw_output_byte_length"] == len(raw_output),
        f"{spec.lane_id} raw byte length differs",
    )
    require(
        value.get("raw_output_sha256") == bytes_sha256(raw_output),
        f"{spec.lane_id} raw digest differs",
    )
    started = _parse_time(value.get("started_at"), f"{spec.lane_id} start")
    ended = _parse_time(value.get("ended_at"), f"{spec.lane_id} end")
    require(
        _parse_time(gate_ended_at, "FP-046 gate end") <= started <= ended,
        f"{spec.lane_id} observation time differs",
    )
    _validate_lane_result(spec, value, raw_output)
    return deepcopy(dict(value))


def _lane_receipt(
    spec: LaneSpec,
    observation: Mapping[str, Any],
    raw_output: bytes,
    authority: Mapping[str, Any],
    implementation_content_set_sha256: str,
) -> dict[str, Any]:
    return sealed(
        {
            "schema_version": "walksafe.fp046-internal-lane-receipt.v1",
            "document_id": f"WS-FP046-{spec.lane_id}-RECEIPT-20260810-001",
            "goal_id": GOAL_ID,
            "lane_id": spec.lane_id,
            "title": spec.title,
            "status": "PASS",
            "credit_scope": "INTERNAL_ONLY",
            "implementation_content_set_sha256": implementation_content_set_sha256,
            "start_gate_binding": deepcopy(authority["start_gate_binding"]),
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


def _validate_implementation_authority(
    value: Any,
    expected_authority: Mapping[str, Any] | None,
) -> dict[str, Any]:
    require(
        type(value) is dict and set(value) == {"goal", "start_gate"},
        "FP-046 implementation authority fields differ",
    )
    goal = _validate_binding(value["goal"], "FP046_GOAL", GOAL_REL)
    start = _validate_binding(
        value["start_gate"], "FP046_EXACT9_START_GATE", START_GATE_REL
    )
    require(
        set(goal) == {"role", "path", "byte_length", "sha256"},
        "FP-046 implementation goal authority fields differ",
    )
    require(
        set(start)
        == {
            "role",
            "path",
            "byte_length",
            "sha256",
            "repository_state_path",
            "repository_state_sha256",
            "event_sequence",
            "event_id",
        },
        "FP-046 implementation start authority fields differ",
    )
    require(
        start.get("repository_state_path")
        == START_GATE_REPOSITORY_STATE_REL.as_posix()
        and type(start.get("repository_state_sha256")) is str
        and SHA256_RE.fullmatch(start["repository_state_sha256"]) is not None
        and start.get("event_sequence") == EXPECTED_START_EVENT_SEQUENCE
        and start.get("event_id") == EXPECTED_START_EVENT_ID,
        "FP-046 implementation start authority differs",
    )
    normalized = {"goal": goal, "start_gate": start}
    if expected_authority is not None:
        expected = _validate_authority_mapping(expected_authority)
        require(
            normalized
            == {
                "goal": expected["goal_binding"],
                "start_gate": expected["start_gate_binding"],
            },
            "FP-046 implementation authority binding differs",
        )
    return normalized


def _validate_receipt_manifest_rows(
    value: Any,
) -> list[dict[str, Any]]:
    require(
        type(value) is list and len(value) == len(LANES),
        "FP-046 verification lane count differs",
    )
    rows: list[dict[str, Any]] = []
    for spec, row in zip(LANES, value, strict=True):
        require(
            type(row) is dict
            and set(row)
            == {
                "lane_id",
                "receipt_path",
                "receipt_sha256",
                "log_path",
                "log_byte_length",
                "log_sha256",
            },
            f"{spec.lane_id} verification receipt fields differ",
        )
        require(
            row.get("lane_id") == spec.lane_id
            and row.get("receipt_path") == spec.receipt_rel.as_posix()
            and row.get("log_path") == spec.log_rel.as_posix(),
            f"{spec.lane_id} verification receipt identity differs",
        )
        require(
            type(row.get("receipt_sha256")) is str
            and SHA256_RE.fullmatch(row["receipt_sha256"]) is not None
            and type(row.get("log_sha256")) is str
            and SHA256_RE.fullmatch(row["log_sha256"]) is not None
            and type(row.get("log_byte_length")) is int
            and row["log_byte_length"] > 0,
            f"{spec.lane_id} verification receipt binding differs",
        )
        rows.append(deepcopy(row))
    return rows


def validate_implementation_record(
    value: Mapping[str, Any],
    *,
    root: Path | None = None,
    expected_groups: Sequence[SourceGroup] | None = None,
    expected_authority: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    require(
        set(value)
        == {
            "schema_version",
            "document_id",
            "goal_id",
            "policy_id",
            "gap_id",
            "kind",
            "status",
            "observed_at",
            "scope_kind",
            "final_content_manifest",
            "implementation_content_set_sha256",
            "authority_bindings",
            "implemented_controls",
            "completion_boundary",
            "implementation_record_content_sha256",
        },
        "FP-046 implementation fields differ",
    )
    require(
        value.get("schema_version") == "walksafe.fp046-implementation-record.v1"
        and value.get("document_id")
        == "WS-FP046-CONSENT-WITHDRAWAL-DELETION-IMPLEMENTATION-20260810-001"
        and value.get("goal_id") == GOAL_ID
        and value.get("policy_id") == POLICY_ID
        and value.get("gap_id") == GAP_ID
        and value.get("kind") == "IMPLEMENTATION_RECORD"
        and value.get("status") == "PASS",
        "FP-046 implementation identity differs",
    )
    _parse_time(value.get("observed_at"), "FP-046 implementation observation")
    verify_seal(
        value,
        "implementation_record_content_sha256",
        "FP-046 implementation",
    )
    manifest = value.get("final_content_manifest")
    require(type(manifest) is dict, "FP-046 implementation manifest missing")
    content_set = validate_final_content_manifest(
        manifest,
        root=root,
        expected_groups=expected_groups,
    )
    require(
        value.get("scope_kind") == IMPLEMENTATION_SCOPE_KIND
        and value.get("implementation_content_set_sha256") == content_set,
        "FP-046 implementation content set differs",
    )
    _validate_implementation_authority(
        value.get("authority_bindings"), expected_authority
    )
    require(
        value.get("implemented_controls") == list(IMPLEMENTED_CONTROLS),
        "FP-046 implemented-control set differs",
    )
    require(
        value.get("completion_boundary") == completion_boundary(),
        "FP-046 implementation boundary differs",
    )
    return deepcopy(dict(value))


def validate_verification_result(value: Mapping[str, Any]) -> dict[str, Any]:
    require(
        set(value)
        == {
            "schema_version",
            "document_id",
            "goal_id",
            "kind",
            "status",
            "credit_scope",
            "observed_at",
            "implementation_content_set_sha256",
            "final_content_manifest_sha256",
            "lane_receipts",
            "internal_lane_count",
            "completion_boundary",
            "verification_result_content_sha256",
        },
        "FP-046 verification fields differ",
    )
    require(
        value.get("schema_version") == "walksafe.fp046-verification-result.v1"
        and value.get("document_id")
        == "WS-FP046-CONSENT-WITHDRAWAL-DELETION-VERIFICATION-20260810-001"
        and value.get("goal_id") == GOAL_ID
        and value.get("kind") == "VERIFICATION_RESULT"
        and value.get("status") == "PASS"
        and value.get("credit_scope") == "INTERNAL_ONLY",
        "FP-046 verification identity differs",
    )
    _parse_time(value.get("observed_at"), "FP-046 verification observation")
    require(
        type(value.get("implementation_content_set_sha256")) is str
        and SHA256_RE.fullmatch(value["implementation_content_set_sha256"])
        is not None
        and type(value.get("final_content_manifest_sha256")) is str
        and SHA256_RE.fullmatch(value["final_content_manifest_sha256"])
        is not None,
        "FP-046 verification content binding differs",
    )
    verify_seal(
        value,
        "verification_result_content_sha256",
        "FP-046 verification",
    )
    require(
        value.get("internal_lane_count") == len(LANES),
        "FP-046 verification lane count differs",
    )
    _validate_receipt_manifest_rows(value.get("lane_receipts"))
    require(
        value.get("completion_boundary") == completion_boundary(),
        "FP-046 verification boundary differs",
    )
    return deepcopy(dict(value))


def validate_lane_artifacts(
    verification: Mapping[str, Any],
    implementation: Mapping[str, Any],
    *,
    receipt_raw_by_lane: Mapping[str, bytes],
    log_raw_by_lane: Mapping[str, bytes],
    authority: Mapping[str, Any],
) -> None:
    validate_verification_result(verification)
    auth = _validate_authority_mapping(authority)
    _validate_implementation_authority(
        implementation.get("authority_bindings"), auth
    )
    manifest = implementation.get("final_content_manifest")
    require(type(manifest) is dict, "FP-046 bundle manifest missing")
    require(
        verification.get("final_content_manifest_sha256")
        == manifest.get("manifest_content_sha256"),
        "FP-046 bundle manifest binding differs",
    )
    require(
        set(receipt_raw_by_lane) == set(LANE_BY_ID),
        "FP-046 receipt artifact set differs",
    )
    require(
        set(log_raw_by_lane) == set(LANE_BY_ID),
        "FP-046 log artifact set differs",
    )
    rows = _validate_receipt_manifest_rows(verification["lane_receipts"])
    observed_ends: list[str] = []
    for spec, row in zip(LANES, rows, strict=True):
        receipt_raw = receipt_raw_by_lane[spec.lane_id]
        log_raw = log_raw_by_lane[spec.lane_id]
        require(
            type(receipt_raw) is bytes and type(log_raw) is bytes,
            f"{spec.lane_id} artifact bytes differ",
        )
        require(
            row["receipt_sha256"] == bytes_sha256(receipt_raw)
            and row["log_byte_length"] == len(log_raw)
            and row["log_sha256"] == bytes_sha256(log_raw),
            f"{spec.lane_id} physical artifact binding differs",
        )
        receipt = strict_json_bytes(receipt_raw, f"{spec.lane_id} receipt")
        require_document_matches_raw(
            receipt, receipt_raw, f"{spec.lane_id} receipt"
        )
        require(
            set(receipt)
            == {
                "schema_version",
                "document_id",
                "goal_id",
                "lane_id",
                "title",
                "status",
                "credit_scope",
                "implementation_content_set_sha256",
                "start_gate_binding",
                "raw_output_binding",
                "observation",
                "completion_boundary",
                "receipt_content_sha256",
            },
            f"{spec.lane_id} receipt fields differ",
        )
        require(
            receipt.get("schema_version")
            == "walksafe.fp046-internal-lane-receipt.v1"
            and receipt.get("document_id")
            == f"WS-FP046-{spec.lane_id}-RECEIPT-20260810-001"
            and receipt.get("goal_id") == GOAL_ID
            and receipt.get("lane_id") == spec.lane_id
            and receipt.get("title") == spec.title
            and receipt.get("status") == "PASS"
            and receipt.get("credit_scope") == "INTERNAL_ONLY",
            f"{spec.lane_id} receipt identity differs",
        )
        verify_seal(
            receipt,
            "receipt_content_sha256",
            f"{spec.lane_id} receipt",
        )
        require(
            receipt.get("implementation_content_set_sha256")
            == implementation.get("implementation_content_set_sha256")
            == verification.get("implementation_content_set_sha256"),
            f"{spec.lane_id} implementation content binding differs",
        )
        require(
            receipt.get("start_gate_binding") == auth["start_gate_binding"],
            f"{spec.lane_id} start-gate binding differs",
        )
        require(
            receipt.get("raw_output_binding")
            == {
                "path": spec.log_rel.as_posix(),
                "byte_length": len(log_raw),
                "sha256": bytes_sha256(log_raw),
            },
            f"{spec.lane_id} raw-output binding differs",
        )
        observation = receipt.get("observation")
        require(type(observation) is dict, f"{spec.lane_id} observation missing")
        validated_observation = _validate_lane_observation(
            spec,
            observation,
            log_raw,
            auth["gate_ended_at"],
        )
        observed_ends.append(validated_observation["ended_at"])
        require(
            receipt.get("completion_boundary") == completion_boundary(),
            f"{spec.lane_id} receipt boundary differs",
        )
    latest_end = max(
        observed_ends,
        key=lambda timestamp: _parse_time(timestamp, "FP-046 lane end"),
    )
    require(
        verification.get("observed_at") == latest_end
        and implementation.get("observed_at") == latest_end,
        "FP-046 producer observation time differs",
    )


def build_pre_review_outputs(
    *,
    root: Path = ROOT,
    lane_observations: Mapping[str, Mapping[str, Any]],
    lane_raw_outputs: Mapping[str, bytes],
    source_groups: Sequence[SourceGroup] = IMPLEMENTATION_SOURCE_GROUPS,
    authority: Mapping[str, Any] | None = None,
) -> dict[Path, str]:
    root = root.resolve(strict=True)
    require(set(lane_observations) == set(LANE_BY_ID), "FP-046 lane observation set differs")
    require(set(lane_raw_outputs) == set(LANE_BY_ID), "FP-046 lane raw-output set differs")
    auth = _validate_authority_mapping(authority) if authority is not None else validate_authority(root)
    manifest = build_final_content_manifest(root, source_groups)
    content_set = validate_final_content_manifest(
        manifest,
        root=root,
        expected_groups=source_groups,
    )
    observations = {
        lane.lane_id: _validate_lane_observation(
            lane,
            lane_observations[lane.lane_id],
            lane_raw_outputs[lane.lane_id],
            auth["gate_ended_at"],
        )
        for lane in LANES
    }
    logs: dict[Path, str] = {}
    receipts: dict[Path, str] = {}
    for lane in LANES:
        raw = lane_raw_outputs[lane.lane_id]
        require(type(raw) is bytes, f"{lane.lane_id} raw output must be bytes")
        try:
            logs[lane.log_rel] = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise BuildError(f"{lane.lane_id} raw output is not UTF-8") from exc
        receipt = _lane_receipt(
            lane,
            observations[lane.lane_id],
            raw,
            auth,
            content_set,
        )
        receipts[lane.receipt_rel] = json_text(receipt)
    receipt_manifest = [
        {
            "lane_id": lane.lane_id,
            "receipt_path": lane.receipt_rel.as_posix(),
            "receipt_sha256": bytes_sha256(receipts[lane.receipt_rel].encode("utf-8")),
            "log_path": lane.log_rel.as_posix(),
            "log_byte_length": len(lane_raw_outputs[lane.lane_id]),
            "log_sha256": bytes_sha256(lane_raw_outputs[lane.lane_id]),
        }
        for lane in LANES
    ]
    observed_at = max(
        (observation["ended_at"] for observation in observations.values()),
        key=lambda timestamp: _parse_time(timestamp, "FP-046 observation end"),
    )
    implementation = sealed(
        {
            "schema_version": "walksafe.fp046-implementation-record.v1",
            "document_id": "WS-FP046-CONSENT-WITHDRAWAL-DELETION-IMPLEMENTATION-20260810-001",
            "goal_id": GOAL_ID,
            "policy_id": POLICY_ID,
            "gap_id": GAP_ID,
            "kind": "IMPLEMENTATION_RECORD",
            "status": "PASS",
            "observed_at": observed_at,
            "scope_kind": IMPLEMENTATION_SCOPE_KIND,
            "final_content_manifest": manifest,
            "implementation_content_set_sha256": content_set,
            "authority_bindings": {
                "goal": deepcopy(auth["goal_binding"]),
                "start_gate": deepcopy(auth["start_gate_binding"]),
            },
            "implemented_controls": list(IMPLEMENTED_CONTROLS),
            "completion_boundary": completion_boundary(),
        },
        "implementation_record_content_sha256",
    )
    validate_implementation_record(
        implementation,
        root=root,
        expected_groups=source_groups,
        expected_authority=auth,
    )
    implementation_text = json_text(implementation)
    verification = sealed(
        {
            "schema_version": "walksafe.fp046-verification-result.v1",
            "document_id": "WS-FP046-CONSENT-WITHDRAWAL-DELETION-VERIFICATION-20260810-001",
            "goal_id": GOAL_ID,
            "kind": "VERIFICATION_RESULT",
            "status": "PASS",
            "credit_scope": "INTERNAL_ONLY",
            "observed_at": observed_at,
            "implementation_content_set_sha256": content_set,
            "final_content_manifest_sha256": manifest["manifest_content_sha256"],
            "lane_receipts": receipt_manifest,
            "internal_lane_count": len(LANES),
            "completion_boundary": completion_boundary(),
        },
        "verification_result_content_sha256",
    )
    validate_verification_result(verification)
    validate_lane_artifacts(
        verification,
        implementation,
        receipt_raw_by_lane={
            lane.lane_id: receipts[lane.receipt_rel].encode("utf-8")
            for lane in LANES
        },
        log_raw_by_lane=lane_raw_outputs,
        authority=auth,
    )
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
            "schema_version": "walksafe.fp046-successor-trace.v1",
            "document_id": "WS-FP046-CONSENT-WITHDRAWAL-DELETION-SUCCESSOR-20260810-001",
            "goal_id": GOAL_ID,
            "kind": "SUCCESSOR_TRACE",
            "status": "INTERNAL_VERIFICATION_RECORDED_REVIEW_PENDING",
            "observed_at": observed_at,
            "producer_results": producer_results,
            "required_r025_input_bindings": {
                "implementation_record_sha256": producer_results["IMPLEMENTATION_RECORD"]["sha256"],
                "verification_result_sha256": producer_results["VERIFICATION_RESULT"]["sha256"],
                "final_content_manifest_sha256": manifest["manifest_content_sha256"],
                "implementation_content_set_sha256": content_set,
            },
            "r025_output_path": "docs/control/audits/walksafe-implementation-gap-analysis-20260810-r025.json",
            "r025_materialized_by_this_builder": False,
            "next_single_action": "BUILD_R025_THEN_INDEPENDENT_REVIEW",
            "completion_boundary": completion_boundary(),
        },
        "successor_trace_content_sha256",
    )
    successor_text = json_text(successor)
    subject = sealed(
        {
            "schema_version": "walksafe.fp046-review-subject.v1",
            "document_id": "WS-FP046-CONSENT-WITHDRAWAL-DELETION-REVIEW-SUBJECT-20260810-001",
            "goal_id": GOAL_ID,
            "kind": "INTERNAL_REVIEW_SUBJECT",
            "status": "REVIEW_PENDING",
            "observed_at": observed_at,
            "reviewed_result_sha256_by_kind": {
                "IMPLEMENTATION_RECORD": producer_results["IMPLEMENTATION_RECORD"]["sha256"],
                "VERIFICATION_RESULT": producer_results["VERIFICATION_RESULT"]["sha256"],
                "SUCCESSOR_TRACE": bytes_sha256(successor_text.encode("utf-8")),
            },
            "final_content_manifest": {
                "scope_kind": IMPLEMENTATION_SCOPE_KIND,
                "exact_path_count": manifest["exact_path_count"],
                "path_set_sha256": manifest["path_set_sha256"],
                "content_set_sha256": content_set,
                "manifest_content_sha256": manifest["manifest_content_sha256"],
            },
            "verification_receipts": receipt_manifest,
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


def load_lane_observations(
    directory: Path,
) -> tuple[dict[str, dict[str, Any]], dict[str, bytes]]:
    original = directory.absolute()
    info = original.lstat()
    require(
        stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode),
        "lane observation directory is unsafe",
    )
    directory = original.resolve(strict=True)
    require(
        directory == original,
        "lane observation directory identity differs",
    )
    expected_names = {
        name
        for lane in LANES
        for name in (f"{lane.receipt_name}.json", f"{lane.receipt_name}.log")
    }
    require({path.name for path in directory.iterdir()} == expected_names, "lane observation directory entries differ")
    observations: dict[str, dict[str, Any]] = {}
    raw_outputs: dict[str, bytes] = {}
    for lane in LANES:
        observation_path = directory / f"{lane.receipt_name}.json"
        log_path = directory / f"{lane.receipt_name}.log"
        for path in (observation_path, log_path):
            entry = path.lstat()
            require(stat.S_ISREG(entry.st_mode) and not stat.S_ISLNK(entry.st_mode), f"unsafe lane observation entry: {path.name}")
        observations[lane.lane_id] = strict_json_bytes(
            observation_path.read_bytes(), observation_path.name
        )
        raw_outputs[lane.lane_id] = log_path.read_bytes()
    return observations, raw_outputs


def write_or_check_outputs(
    root: Path, outputs: Mapping[Path, str], *, write: bool
) -> None:
    """Reuse the descriptor-anchored, recoverable, add-only FP-008 publisher."""

    io_base.write_or_check_outputs(root, outputs, write=write)


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
        observations, raw_outputs = load_lane_observations(
            args.lane_observation_dir
        )
        outputs = build_pre_review_outputs(
            root=args.root,
            lane_observations=observations,
            lane_raw_outputs=raw_outputs,
        )
        write_or_check_outputs(args.root, outputs, write=args.write)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"FP-046 consent/withdrawal/deletion trace: FAIL: {exc}")
        return 1
    print(
        "FP-046 consent/withdrawal/deletion trace: PASS "
        f"outputs={len(outputs)} mode={'WRITE' if args.write else 'CHECK'}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
