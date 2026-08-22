#!/usr/bin/env python3
"""Build a noncanonical FP-046/GAP-055 regression-reopen R029 candidate.

The R028 audit/backlog pair is an immutable four-file predecessor.  This
builder records two source-bound regressions without applying a canonical
control transition, authorizing product work, or claiming formal, device,
external, deployment, approval, or release credit.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import importlib
from pathlib import Path
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

POLICY_ID = "FP-046"
GAP_ID = "GAP-055"
SUCCESSOR_GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R002"
CANDIDATE_PREPARED_AT = "2026-08-15T00:00:00+09:00"
REGRESSION_TRIGGER_ID = "WS-FP046-GAP055-REGRESSION-TRIGGER-20260815-R001"

R028_GAP_JSON_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260814-r028.json"
)
R028_GAP_MD_REL = R028_GAP_JSON_REL.with_suffix(".md")
R028_BACKLOG_JSON_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260814-r028.json"
)
R028_BACKLOG_MD_REL = R028_BACKLOG_JSON_REL.with_suffix(".md")
R028_INPUT_PATHS = (
    R028_GAP_JSON_REL,
    R028_GAP_MD_REL,
    R028_BACKLOG_JSON_REL,
    R028_BACKLOG_MD_REL,
)
EXPECTED_R028_BINDING_BY_PATH = {
    R028_GAP_JSON_REL: {
        "sha256": "f7be788ed4bcde8440d2539dceed9105421019f38ec1a4106648a5fab7120bab",
        "byte_length": 532549,
    },
    R028_GAP_MD_REL: {
        "sha256": "dbec9a7564c76cf28e559dcb3309834a7410dfb002fe0bf4782373788f8dd173",
        "byte_length": 547,
    },
    R028_BACKLOG_JSON_REL: {
        "sha256": "acf975cffcdec26906099236567f825a616226bfb030ef70d20bb7a21bcfbe55",
        "byte_length": 66305,
    },
    R028_BACKLOG_MD_REL: {
        "sha256": "3ae60a068ceaf2ec69a600b122f662a1e69dfd00cdfb5dd05fb773e58a773914",
        "byte_length": 332,
    },
}

FIELD_TEST_SECURITY_REL = Path("backend/app/field_test_security.py")
ACTOR_RATE_LIMITER_REL = Path("backend/app/services/actor_rate_limit.py")
RATE_LIMIT_MIGRATION_REL = Path(
    "backend/alembic/versions/202607130004_shared_actor_rate_limits.py"
)
GATEWAY_ROUTES_REL = Path("apps/android-gateway/src/routes.ts")
ANDROID_DELETION_CLIENT_REL = Path(
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
    "AndroidPrivacyDeletionClient.kt"
)
NGINX_GATEWAY_REL = Path("deploy/nginx/walksafe-android-gateway.conf.example")
CURRENT_SOURCE_PATHS = (
    FIELD_TEST_SECURITY_REL,
    ACTOR_RATE_LIMITER_REL,
    RATE_LIMIT_MIGRATION_REL,
    GATEWAY_ROUTES_REL,
    ANDROID_DELETION_CLIENT_REL,
    NGINX_GATEWAY_REL,
)
EXPECTED_CURRENT_SOURCE_BINDING_BY_PATH = {
    FIELD_TEST_SECURITY_REL: {
        "sha256": "4f181ccc655ead15d3a58ff4b5cd88b299dbe39adb6572290ac9a7b562fd16e1",
        "byte_length": 48282,
    },
    ACTOR_RATE_LIMITER_REL: {
        "sha256": "14b5c6b5bc71103639d61755a14748e6fee53764e5db1217d28a6c01e763012d",
        "byte_length": 4787,
    },
    RATE_LIMIT_MIGRATION_REL: {
        "sha256": "bc3daa16a9d44d394d68de9e1f3fcb40ec7b845da3e5e382deea36c5e113744a",
        "byte_length": 1635,
    },
    GATEWAY_ROUTES_REL: {
        "sha256": "804705498f65983300782fa4549b11f700ca0a964ede32a90f620a66140bc407",
        "byte_length": 38187,
    },
    ANDROID_DELETION_CLIENT_REL: {
        "sha256": "52ed367849a96ce3b54a5140f22f82dfa5caeeb72b76a52013e7737e0f28aef0",
        "byte_length": 20253,
    },
    NGINX_GATEWAY_REL: {
        "sha256": "a75eecac17b67eda8b05987c44e192765567552b95f90ba00194eb1393ae5fc4",
        "byte_length": 3053,
    },
}

CANDIDATE_DIR_REL = Path("docs/control/candidates/fp046-gap055-regression-r029")
R029_GAP_JSON_REL = (
    CANDIDATE_DIR_REL
    / "walksafe-implementation-gap-analysis-20260815-r029.candidate.json"
)
R029_GAP_MD_REL = R029_GAP_JSON_REL.with_suffix(".md")
R029_BACKLOG_JSON_REL = (
    CANDIDATE_DIR_REL
    / "walksafe-implementation-remediation-backlog-20260815-r029.candidate.json"
)
R029_BACKLOG_MD_REL = R029_BACKLOG_JSON_REL.with_suffix(".md")
DISCOVERY_JSON_REL = (
    CANDIDATE_DIR_REL
    / "walksafe-fp046-gap055-regression-trigger-discovery-20260815-r001.candidate.json"
)
DISCOVERY_MD_REL = DISCOVERY_JSON_REL.with_suffix(".md")
OUTPUT_PATHS = (
    R029_GAP_JSON_REL,
    R029_GAP_MD_REL,
    R029_BACKLOG_JSON_REL,
    R029_BACKLOG_MD_REL,
    DISCOVERY_JSON_REL,
    DISCOVERY_MD_REL,
)

EXPECTED_STATUS_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 15,
    "EVIDENCE_MISSING": 4,
    "IMPLEMENTED": 0,
    "MISSING": 8,
    "PARTIAL": 36,
}
FORMAL_TEST_IDS = tuple(f"TC-FP-046-{number:02d}" for number in range(1, 6))
ACCOUNT_DELETION_ROUTES = (
    "/privacy/account-deletions",
    "/privacy/account-deletions/{request_id}/status",
    "/privacy/account-deletions/{request_id}/device-evidence",
)
OPERATIONAL_APPLICATION_PREREQUISITES = (
    "EVENT_SCOPED_USER_TASK_AUTHORIZATION",
    "R007_CONTROL_SUCCESSOR_REVIEW",
    "R008_CONTROL_SUCCESSOR_REVIEW",
)

BuildError = io_base.BuildError
require = io_base.require
bytes_sha256 = io_base.bytes_sha256
object_sha256 = io_base.object_sha256
json_text = io_base.json_text
strict_json_bytes = io_base.strict_json_bytes
sealed = io_base.sealed
verify_seal = io_base.verify_seal


def _zero_credit_boundary(scope: str) -> dict[str, Any]:
    return {
        "scope": scope,
        "formal_test_ids": list(FORMAL_TEST_IDS),
        "formal_test_status": "NOT_RUN",
        "formal_test_credit_count": 0,
        "actual_device_status": "NOT_RUN",
        "actual_device_credit_count": 0,
        "external_legal_review_status": "NOT_RUN",
        "external_privacy_review_status": "NOT_RUN",
        "external_review_status": "NOT_RUN",
        "external_credit_count": 0,
        "operational_database_status": "NOT_RUN",
        "operational_backup_restore_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "deployment_credit_count": 0,
        "release_gate_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
        "release_credit_count": 0,
        "approval_claimed": False,
        "external_independence_claimed": False,
    }


def zero_credit_boundary() -> dict[str, Any]:
    return _zero_credit_boundary("REGRESSION_DISCOVERY_CANDIDATE_ONLY")


def canonical_reopen_zero_credit_boundary() -> dict[str, Any]:
    return _zero_credit_boundary(
        "REPOSITORY_INTERNAL_FP046_REGRESSION_REASSESSMENT_ONLY"
    )


def _binding(
    path: Path,
    raw: bytes,
    *,
    role: str,
) -> dict[str, Any]:
    return {
        "path": path.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
        "role": role,
    }


def operational_application_boundary() -> dict[str, Any]:
    return {
        "canonical_application_status": "NOT_APPLIED",
        "approval_status": "NOT_REQUESTED",
        "implementation_authorized": False,
        "required_before_apply": list(OPERATIONAL_APPLICATION_PREREQUISITES),
    }


def _next_single_action() -> dict[str, Any]:
    return {
        "epic_id": "EPIC-03",
        "source_policy_id": POLICY_ID,
        "gap_id": GAP_ID,
        "priority_rank": 21,
        "status": "REOPEN_REQUIRED",
        "work_item_id": SUCCESSOR_GOAL_ID,
        "action": (
            "FP-046/GAP-055의 privacy rate_group CHECK와 account deletion nginx "
            "ingress 회귀를 수정하기 전에, event-scoped 사용자 작업 권한과 R007/R008 "
            "control-successor review를 거쳐 canonical reopen 및 fresh start gate를 준비한다."
        ),
    }


def _validate_raw_cohort(
    raw_by_path: Mapping[Path, bytes],
    expected_binding_by_path: Mapping[Path, Mapping[str, Any]],
    expected_paths: Sequence[Path],
    label: str,
) -> None:
    require(set(raw_by_path) == set(expected_paths), f"{label} inventory differs")
    require(
        set(expected_binding_by_path) == set(expected_paths),
        f"{label} pin inventory differs",
    )
    for path in expected_paths:
        raw = raw_by_path[path]
        expected = expected_binding_by_path[path]
        require(
            bytes_sha256(raw) == expected.get("sha256")
            and len(raw) == expected.get("byte_length"),
            f"canonical {label} bytes differ: {path}",
        )


def _validate_predecessors(
    gap: Mapping[str, Any],
    backlog: Mapping[str, Any],
    raw_by_path: Mapping[Path, bytes],
    expected_binding_by_path: Mapping[Path, Mapping[str, Any]],
) -> None:
    _validate_raw_cohort(
        raw_by_path,
        expected_binding_by_path,
        R028_INPUT_PATHS,
        "R028 predecessor",
    )
    require(
        gap == strict_json_bytes(raw_by_path[R028_GAP_JSON_REL], "R028 gap"),
        "R028 gap document/raw binding differs",
    )
    require(
        backlog
        == strict_json_bytes(raw_by_path[R028_BACKLOG_JSON_REL], "R028 backlog"),
        "R028 backlog document/raw binding differs",
    )
    require(
        raw_by_path[R028_GAP_JSON_REL] == json_text(gap).encode("utf-8")
        and raw_by_path[R028_BACKLOG_JSON_REL] == json_text(backlog).encode("utf-8"),
        "R028 predecessor JSON is noncanonical",
    )
    verify_seal(gap, "report_content_sha256", "R028 gap")
    verify_seal(backlog, "backlog_content_sha256", "R028 backlog")
    require(
        gap.get("metadata", {}).get("report_id")
        == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260814-028"
        and backlog.get("metadata", {}).get("backlog_id")
        == "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260814-028",
        "R028 predecessor identity differs",
    )
    require(
        gap.get("summary", {}).get("status_counts") == EXPECTED_STATUS_COUNTS
        and gap["summary"].get("implemented_and_formally_verified_count") == 0
        and gap["summary"].get("release_status") == "NOT_ELIGIBLE",
        "R028 summary credit or status boundary differs",
    )
    assessments = gap.get("assessments")
    require(
        type(assessments) is list
        and len(assessments) == 68
        and len({row.get("gap_id") for row in assessments}) == 68,
        "R028 assessment inventory differs",
    )
    target = next((row for row in assessments if row.get("gap_id") == GAP_ID), None)
    require(
        type(target) is dict
        and target.get("source_policy_id") == POLICY_ID
        and target.get("status") == "PARTIAL"
        and target.get("planned_test_ids") == list(FORMAL_TEST_IDS)
        and target.get("formal_test_status") == "NOT_RUN"
        and target.get("waived") is False,
        "R028 GAP-055 identity or credit boundary differs",
    )
    actions = backlog.get("next_action_sequence")
    require(type(actions) is list, "R028 action sequence missing")
    fp046 = [row for row in actions if row.get("source_policy_id") == POLICY_ID]
    require(
        len(fp046) == 1 and fp046[0].get("status") == "PARTIAL",
        "R028 FP-046 backlog action differs",
    )
    require(
        backlog.get("authorization_boundary", {}).get(
            "formal_test_completion_claimed"
        )
        is False
        and backlog["authorization_boundary"].get("deployment_completion_claimed")
        is False
        and backlog["authorization_boundary"].get("release_status")
        == "NOT_ELIGIBLE",
        "R028 backlog authorization boundary differs",
    )


def _source_text(raw_by_path: Mapping[Path, bytes], path: Path) -> str:
    try:
        return raw_by_path[path].decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BuildError(f"current source is not UTF-8: {path}") from exc


def _validate_regression_source_semantics(raw_by_path: Mapping[Path, bytes]) -> None:
    field_security = _source_text(raw_by_path, FIELD_TEST_SECURITY_REL)
    rate_limiter = _source_text(raw_by_path, ACTOR_RATE_LIMITER_REL)
    migration = _source_text(raw_by_path, RATE_LIMIT_MIGRATION_REL)
    gateway_routes = _source_text(raw_by_path, GATEWAY_ROUTES_REL)
    android_client = _source_text(raw_by_path, ANDROID_DELETION_CLIENT_REL)
    nginx = _source_text(raw_by_path, NGINX_GATEWAY_REL)

    require(
        '"privacy": 12,' in field_security
        and 'if normalized_method == "POST" and path == "/privacy/consent-events":'
        in field_security
        and field_security.count('return "privacy"') >= 2,
        "privacy runtime rate_group assertion differs",
    )
    require(
        "except ActorRateLimitStoreUnavailable:" in field_security
        and "status_code=503," in field_security
        and "INSERT INTO actor_rate_limit_events (actor_digest, rate_group)" in rate_limiter
        and "raise ActorRateLimitStoreUnavailable from exc" in rate_limiter,
        "privacy rate-limit fail-closed assertion differs",
    )
    require(
        "ck_actor_rate_limit_events_group" in migration
        and "rate_group IN (" in migration,
        "privacy migration CHECK assertion differs",
    )
    require(
        "'privacy'" not in migration,
        "privacy migration CHECK does not omit privacy",
    )

    require(
        all(f'"{route}"' in gateway_routes for route in ACCOUNT_DELETION_ROUTES)
        and "ACCOUNT_DELETION_REQUEST_ID = /^[A-Za-z0-9_-]{16,128}$/" in gateway_routes,
        "gateway account deletion route inventory differs",
    )
    require(
        '"/privacy/account-deletions"' in android_client
        and '"/privacy/account-deletions/${journal.requestId}/status"'
        in android_client
        and '"/privacy/account-deletions/${journal.requestId}/device-evidence"'
        in android_client,
        "Android account deletion route inventory differs",
    )
    require(
        all(route not in nginx for route in ACCOUNT_DELETION_ROUTES)
        and "location / {" in nginx
        and "return 404;" in nginx
        and "proxy_pass http://127.0.0.1:8081;" in nginx,
        "nginx deletion ingress unexpectedly exists",
    )


def _validate_current_sources(
    raw_by_path: Mapping[Path, bytes],
    expected_binding_by_path: Mapping[Path, Mapping[str, Any]],
) -> None:
    _validate_raw_cohort(
        raw_by_path,
        expected_binding_by_path,
        CURRENT_SOURCE_PATHS,
        "regression source",
    )
    _validate_regression_source_semantics(raw_by_path)


def _r028_predecessor_bindings(
    raw_by_path: Mapping[Path, bytes],
) -> list[dict[str, Any]]:
    roles = (
        "IMMUTABLE_R028_GAP_JSON_PREDECESSOR",
        "IMMUTABLE_R028_GAP_MARKDOWN_PREDECESSOR",
        "IMMUTABLE_R028_BACKLOG_JSON_PREDECESSOR",
        "IMMUTABLE_R028_BACKLOG_MARKDOWN_PREDECESSOR",
    )
    return [
        _binding(path, raw_by_path[path], role=role)
        for path, role in zip(R028_INPUT_PATHS, roles, strict=True)
    ]


def _current_source_bindings(
    raw_by_path: Mapping[Path, bytes],
) -> list[dict[str, Any]]:
    roles = {
        FIELD_TEST_SECURITY_REL: "PRIVACY_RUNTIME_RATE_GROUP_SOURCE",
        ACTOR_RATE_LIMITER_REL: "POSTGRES_RATE_LIMIT_FAIL_CLOSED_SOURCE",
        RATE_LIMIT_MIGRATION_REL: "POSTGRES_RATE_GROUP_CHECK_SOURCE",
        GATEWAY_ROUTES_REL: "GATEWAY_ACCOUNT_DELETION_ROUTE_SOURCE",
        ANDROID_DELETION_CLIENT_REL: "ANDROID_ACCOUNT_DELETION_ROUTE_SOURCE",
        NGINX_GATEWAY_REL: "NGINX_ANDROID_GATEWAY_INGRESS_SOURCE",
    }
    return [
        _binding(path, raw_by_path[path], role=roles[path])
        for path in CURRENT_SOURCE_PATHS
    ]


def _reopen_record() -> dict[str, Any]:
    return {
        "status": "REOPEN_REQUIRED",
        "reopen_required": True,
        "finding_ids": [
            "FP046-RATE-GROUP-PRIVACY-CHECK-OMISSION",
            "FP046-NGINX-ACCOUNT-DELETION-INGRESS-OMISSION",
        ],
        "next_action": {
            "status": "PLANNED",
            "goal_successor_id": SUCCESSOR_GOAL_ID,
            "action": (
                "FP-046/GAP-055의 privacy rate_group CHECK와 account deletion nginx "
                "ingress 회귀를 수정하고 현재 회귀를 검증한다."
            ),
        },
        "completion_boundary": canonical_reopen_zero_credit_boundary(),
    }


def build_discovery(
    current_source_raw_by_path: Mapping[Path, bytes],
    *,
    r028_raw_by_path: Mapping[Path, bytes],
) -> dict[str, Any]:
    discovery = {
        "schema_version": "walksafe.fp046-gap055-regression-trigger-discovery-candidate.v1",
        "document_id": "WS-FP046-GAP055-REGRESSION-TRIGGER-DISCOVERY-20260815-R001",
        "kind": "REGRESSION_TRIGGER_DISCOVERY_CANDIDATE",
        "candidate_status": "DISCOVERED_NOT_CANONICALLY_APPLIED",
        "prepared_at": CANDIDATE_PREPARED_AT,
        "trigger_id": REGRESSION_TRIGGER_ID,
        "affected_subjects": [{"source_policy_id": POLICY_ID, "gap_id": GAP_ID}],
        "impact_scope": {
            "changed_subjects": [
                {"source_policy_id": POLICY_ID, "gap_id": GAP_ID}
            ],
            "derived_aggregate_subject": {
                "epic_id": "EPIC-03",
                "fields": ["current_status", "current_status_reason"],
            },
        },
        "proposed_next_single_action": _next_single_action(),
        "canonical_application_status": "NOT_APPLIED",
        "approval_status": "NOT_REQUESTED",
        "approval_claimed": False,
        "external_independence_claimed": False,
        "operational_application_boundary": operational_application_boundary(),
        "r028_predecessor_bindings": _r028_predecessor_bindings(r028_raw_by_path),
        "source_bindings": _current_source_bindings(current_source_raw_by_path),
        "reproduction_assertions": [
            {
                "assertion_id": "ASSERT-FP046-PRIVACY-RATE-GROUP-CHECK-001",
                "status": "UNEXECUTED_CURRENT_REGRESSION_ASSERTION",
                "given": (
                    "An authorized privacy request is admitted while the PostgreSQL "
                    "actor-rate limiter is selected."
                ),
                "assertion": (
                    "The runtime selects rate_group privacy, but the bound migration "
                    "CHECK omits privacy; the insert is therefore rejected and the "
                    "middleware's store-unavailable path is expected to return 503."
                ),
                "source_paths": [
                    FIELD_TEST_SECURITY_REL.as_posix(),
                    ACTOR_RATE_LIMITER_REL.as_posix(),
                    RATE_LIMIT_MIGRATION_REL.as_posix(),
                ],
            },
            {
                "assertion_id": "ASSERT-FP046-NGINX-DELETION-INGRESS-001",
                "status": "UNEXECUTED_CURRENT_REGRESSION_ASSERTION",
                "given": (
                    "Gateway and Android use the request, status, and device-evidence "
                    "account-deletion routes through the Android gateway host."
                ),
                "assertion": (
                    "The bound nginx example has no proxy location for any of those "
                    "routes and retains a catch-all 404, so those ingress requests are "
                    "expected to terminate at 404."
                ),
                "source_paths": [
                    GATEWAY_ROUTES_REL.as_posix(),
                    ANDROID_DELETION_CLIENT_REL.as_posix(),
                    NGINX_GATEWAY_REL.as_posix(),
                ],
            },
        ],
        "completion_boundary": zero_credit_boundary(),
        "limitations": [
            "This is a repository-source discovery candidate, not a canonical control transition.",
            "No database, nginx, device, external, deployment, approval, or release execution is claimed.",
            "The assertions must be re-executed only after event-scoped user task authorization, R007/R008 control-successor review, and a fresh authorized successor start gate.",
        ],
    }
    return sealed(discovery, "discovery_content_sha256")


def _status_counts(assessments: Sequence[Mapping[str, Any]]) -> dict[str, int]:
    counts = {status: 0 for status in EXPECTED_STATUS_COUNTS}
    for row in assessments:
        status = row.get("status")
        require(status in counts, f"unknown assessment status: {status}")
        counts[status] += 1
    return counts


def build_gap(predecessor: Mapping[str, Any]) -> dict[str, Any]:
    before = deepcopy(dict(predecessor))
    report = deepcopy(before)
    report.pop("report_content_sha256", None)
    metadata = deepcopy(report["metadata"])
    metadata.update(
        {
            "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260815-029",
            "version": "0.29.0",
            "prepared_at": CANDIDATE_PREPARED_AT,
            "predecessor_report_id": before["metadata"]["report_id"],
        }
    )
    report["metadata"] = metadata
    report["purpose"] = (
        "GAP-055 remains PARTIAL while recording the two current FP-046 regressions "
        "and the planned remediation work required to correct and verify them."
    )

    before_by_id = {
        row["gap_id"]: deepcopy(row) for row in before["assessments"]
    }
    target = next(row for row in report["assessments"] if row["gap_id"] == GAP_ID)
    require(target.get("status") == "PARTIAL", "R028 GAP-055 status differs")
    target["regression_reopen"] = _reopen_record()
    target["current_implementation_in_plain_language"] = (
        "기존 저장소 내부 구현은 남아 있지만, 현재 PostgreSQL rate_group CHECK가 privacy를 "
        "거부해 인가된 개인정보 요청을 503 fail-closed로 만들고, Android/Gateway가 사용하는 "
        "계정 삭제 세 경로는 nginx 예제에서 catch-all 404로 끝난다."
    )
    target["rationale"] = (
        "GAP-055는 기존처럼 PARTIAL이며 정식·실기기·외부·운영·배포·출시 증거는 모두 "
        "NOT_RUN이다. 두 현재 회귀의 수정·재현 검증을 위해 reopen_required와 planned "
        "remediation을 기록한다."
    )
    target["remediation"] = (
        "새 migration으로 actor_rate_limit_events CHECK에 privacy를 추가하고, Android "
        "Gateway nginx ingress에 요청·상태·기기증거 계정삭제 경로를 정확히 proxy한 뒤, "
        "직접 PostgreSQL 및 Gateway ingress 회귀를 실행한다."
    )
    target.pop("assessment_sha256", None)
    target["assessment_sha256"] = object_sha256(target)
    for row in report["assessments"]:
        if row["gap_id"] == GAP_ID:
            continue
        require(
            row == before_by_id[row["gap_id"]],
            f"non-target assessment changed: {row['gap_id']}",
        )
    require(
        target.get("status") == "PARTIAL"
        and target.get("formal_test_status") == "NOT_RUN"
        and target.get("planned_test_ids") == list(FORMAL_TEST_IDS)
        and target.get("waived") is False,
        "R029 GAP-055 status or formal boundary differs",
    )

    report["summary"] = deepcopy(before["summary"])
    report["summary"]["headline"] = (
        "GAP-055 remains PARTIAL with zero formal/device/external/deployment/release "
        "credit; two source-bound regressions require a controlled FP-046 reopen."
    )
    require(
        _status_counts(report["assessments"]) == EXPECTED_STATUS_COUNTS,
        "R029 derived status counts differ",
    )
    require(
        report["summary"].get("implemented_and_formally_verified_count") == 0
        and report["summary"].get("release_status") == "NOT_ELIGIBLE",
        "R029 summary credit or release boundary differs",
    )
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC03_FP046_GAP055_REGRESSION_REASSESSMENT_WITH_R028_CARRY_FORWARD",
        "directly_reassessed_gap_ids": [GAP_ID],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": [GAP_ID],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [
            row["gap_id"]
            for row in report["assessments"]
            if row["gap_id"] != GAP_ID
        ],
    }
    report["limitations"] = [
        "Only FP-046/GAP-055 is changed; the other 67 assessment objects are exact R028 carry-forward.",
        "Formal, device, external, operational, deployment and release evidence remain NOT_RUN with zero credit.",
    ]
    report["report_content_sha256"] = object_sha256(report)
    return report


def build_backlog(
    predecessor: Mapping[str, Any], gap: Mapping[str, Any]
) -> dict[str, Any]:
    before = deepcopy(dict(predecessor))
    backlog = deepcopy(before)
    backlog.pop("backlog_content_sha256", None)
    metadata = deepcopy(backlog["metadata"])
    metadata.update(
        {
            "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260815-029",
            "version": "0.29.0",
            "prepared_at": CANDIDATE_PREPARED_AT,
            "predecessor_backlog_id": before["metadata"]["backlog_id"],
        }
    )
    backlog["metadata"] = metadata
    backlog["source_predecessor"] = {
        "path": R028_BACKLOG_JSON_REL.as_posix(),
        "file_sha256": EXPECTED_R028_BINDING_BY_PATH[R028_BACKLOG_JSON_REL][
            "sha256"
        ],
        "preserved_unchanged": True,
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]

    before_actions = {
        row["source_policy_id"]: deepcopy(row)
        for row in before["next_action_sequence"]
    }
    target = next(
        row
        for row in backlog["next_action_sequence"]
        if row.get("source_policy_id") == POLICY_ID
    )
    require(target.get("status") == "PARTIAL", "R028 FP-046 backlog action differs")
    target["status"] = "REOPEN_REQUIRED"
    target["action"] = (
        "GAP-055는 PARTIAL을 유지한다. privacy rate_group CHECK omission과 account "
        "deletion nginx ingress omission을 수정하고 현재 회귀를 검증하는 FP-046 R002 "
        "remediation을 계획한다."
    )
    for row in backlog["next_action_sequence"]:
        if row["source_policy_id"] == POLICY_ID:
            continue
        require(
            row == before_actions[row["source_policy_id"]],
            f"non-target backlog action changed: {row['source_policy_id']}",
        )
    require(
        target.get("status") == "REOPEN_REQUIRED"
        and target.get("source_policy_id") == POLICY_ID,
        "R029 FP-046 regression reopen differs",
    )

    before_epics = {row["epic_id"]: deepcopy(row) for row in before["epics"]}
    epic03 = next(row for row in backlog["epics"] if row["epic_id"] == "EPIC-03")
    require(
        epic03.get("current_status") == "IMPLEMENTATION_READY",
        "R028 EPIC-03 status differs",
    )
    epic03["current_status"] = "PLANNED"
    epic03["current_status_reason"] = (
        "FP-046/GAP-055 has a reopen-required regression; the planned FP-046 R002 "
        "remediation is the next EPIC-03 internal work."
    )
    for epic in backlog["epics"]:
        if epic["epic_id"] == "EPIC-03":
            continue
        require(
            epic == before_epics[epic["epic_id"]],
            f"non-target epic changed: {epic['epic_id']}",
        )
    backlog["backlog_content_sha256"] = object_sha256(backlog)
    return backlog


def gap_markdown(gap: Mapping[str, Any]) -> str:
    return (
        "# WalkSafe implementation gap analysis R029\n\n"
        "- Subject: `FP-046 / GAP-055` remains `PARTIAL`; all other R028 assessments are deep-equal carry-forward.\n"
        "- Trigger 1: `privacy` is selected at runtime but omitted from the PostgreSQL rate-group CHECK, producing a fail-closed `503` admission failure.\n"
        "- Trigger 2: Android/Gateway account-deletion request, status and device-evidence routes have no nginx proxy and meet catch-all `404`.\n"
        "- Planned remediation: `WS-GOAL-EPIC-03-FP-046-R002`; EPIC-03 is `PLANNED`.\n"
        "- Credit boundary: formal/device/external/deployment `NOT_RUN`, all credit zero; release `NOT_ELIGIBLE`.\n"
    )


def backlog_markdown(backlog: Mapping[str, Any]) -> str:
    return (
        "# WalkSafe implementation remediation backlog R029\n\n"
        "- `FP-046 / GAP-055`: gap `PARTIAL`; backlog action `REOPEN_REQUIRED`.\n"
        "- Planned remediation work item: `WS-GOAL-EPIC-03-FP-046-R002`; EPIC-03 is `PLANNED`.\n"
        "- Only the FP-046 action and derived EPIC-03 progress move from R028.\n"
        "- Formal/device/external/deployment credit is zero; release remains `NOT_ELIGIBLE`.\n"
    )


def discovery_markdown(discovery: Mapping[str, Any]) -> str:
    return (
        "# FP-046/GAP-055 regression-trigger discovery candidate\n\n"
        f"- Trigger: `{discovery['trigger_id']}`\n"
        "- Candidate only: `canonical_application_status=NOT_APPLIED`, `approval_status=NOT_REQUESTED`, and no external-independence claim.\n"
        "- Reproduction assertions are source-bound and explicitly unexecuted pending event-scoped user task authorization, R007/R008 review, and a controlled successor start gate.\n"
        "- Credit boundary: formal/device/external/deployment `NOT_RUN`, all credit zero; release `NOT_ELIGIBLE`.\n"
    )


def build_documents(
    predecessor_gap: Mapping[str, Any],
    predecessor_backlog: Mapping[str, Any],
    *,
    r028_raw_by_path: Mapping[Path, bytes],
    current_source_raw_by_path: Mapping[Path, bytes],
    expected_r028_binding_by_path: Mapping[
        Path, Mapping[str, Any]
    ] = EXPECTED_R028_BINDING_BY_PATH,
    expected_current_source_binding_by_path: Mapping[
        Path, Mapping[str, Any]
    ] = EXPECTED_CURRENT_SOURCE_BINDING_BY_PATH,
) -> dict[Path, str]:
    _validate_predecessors(
        predecessor_gap,
        predecessor_backlog,
        r028_raw_by_path,
        expected_r028_binding_by_path,
    )
    _validate_current_sources(
        current_source_raw_by_path,
        expected_current_source_binding_by_path,
    )
    discovery = build_discovery(
        current_source_raw_by_path,
        r028_raw_by_path=r028_raw_by_path,
    )
    verify_seal(discovery, "discovery_content_sha256", "regression discovery")
    discovery_text = json_text(discovery)
    gap = build_gap(predecessor_gap)
    backlog = build_backlog(predecessor_backlog, gap)
    verify_seal(gap, "report_content_sha256", "R029 gap candidate")
    verify_seal(backlog, "backlog_content_sha256", "R029 backlog candidate")
    return {
        R029_GAP_JSON_REL: json_text(gap),
        R029_GAP_MD_REL: gap_markdown(gap),
        R029_BACKLOG_JSON_REL: json_text(backlog),
        R029_BACKLOG_MD_REL: backlog_markdown(backlog),
        DISCOVERY_JSON_REL: discovery_text,
        DISCOVERY_MD_REL: discovery_markdown(discovery),
    }


def build_outputs(root: Path = ROOT) -> dict[Path, str]:
    root = root.resolve(strict=True)
    r028_raw_by_path = {
        path: io_base.read_bytes(root, path) for path in R028_INPUT_PATHS
    }
    current_source_raw_by_path = {
        path: io_base.read_bytes(root, path) for path in CURRENT_SOURCE_PATHS
    }
    outputs = build_documents(
        strict_json_bytes(r028_raw_by_path[R028_GAP_JSON_REL], "R028 gap"),
        strict_json_bytes(r028_raw_by_path[R028_BACKLOG_JSON_REL], "R028 backlog"),
        r028_raw_by_path=r028_raw_by_path,
        current_source_raw_by_path=current_source_raw_by_path,
    )
    require(
        all(
            io_base.read_bytes(root, path) == raw
            for path, raw in r028_raw_by_path.items()
        )
        and all(
            io_base.read_bytes(root, path) == raw
            for path, raw in current_source_raw_by_path.items()
        ),
        "R029 input cohort changed before publication",
    )
    return outputs


def write_or_check_outputs(
    root: Path,
    outputs: Mapping[Path, str],
    *,
    write: bool,
) -> None:
    io_base.write_or_check_outputs(root, outputs, write=write)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        outputs = build_outputs(args.root)
        write_or_check_outputs(args.root, outputs, write=args.write)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"FP-046/GAP-055 regression R029 candidate: FAIL: {exc}")
        return 1
    print(
        "FP-046/GAP-055 regression R029 candidate: "
        f"PASS outputs={len(outputs)} mode={'WRITE' if args.write else 'CHECK'}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
