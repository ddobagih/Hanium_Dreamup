#!/usr/bin/env python3
"""Atomically append the exact FP-022 canonical update/completion seq70-71 pair."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from typing import Any, Callable, Mapping, Sequence

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp048_goal_completed_seq43_44_20260802 as atomic
from scripts import apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812 as npc_atomic
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import generate_repository_catalogs as catalogs


CHECKPOINT_REL = Path("docs/control/walksafe-project-continuation-checkpoint.json")
SOURCE_CHECKPOINT_RAW_SHA256 = (
    "5f260789269a5936517620b55262215b77797e4b2ec83220ec94d20cf951c25f"
)
SOURCE_CHECKPOINT_BYTE_COUNT = 2_032_842
SOURCE_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20260814-001"
SOURCE_EVENT_SHA256 = (
    "91cec421d1fdd2f0f0d3ec57e282f7dceb7ae1a6fe51c9db9f9ffb7bba3e38a6"
)
UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP022-20260814-001"
)
COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP022-20260814-001"
)
MANIFEST_SHA256 = "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
GOAL_ID = "WS-GOAL-EPIC-04-FP-022-R001"
GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-04/"
    "epic-04-fp022-tmap-destination-route-r001.md"
)
GOAL_SHA256 = "939075c1b4bcbf9b8280c37cb7a449fd28763f06cda14faf0ca88f691734576b"
PARENT_GOAL_ID = "WS-GOAL-EPIC-04"
PARENT_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-04-navigation-arrival-deviation.md"
)
PARENT_GOAL_SHA256 = "da4aa5a7ab2abe8c3a746c8df4edd69db77ea1dbc2b3e1bdc00399291b99ac6d"
EPIC12_GOAL_ID = "WS-GOAL-EPIC-12"
COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
IMPLEMENTATION_DOCUMENT_ID = "WS-FP022-NAVIGATION-IMPLEMENTATION-20260814-001"
VERIFICATION_DOCUMENT_ID = "WS-FP022-NAVIGATION-VERIFICATION-20260814-001"
SUCCESSOR_DOCUMENT_ID = "WS-FP022-GAP031-R028-SUCCESSOR-20260814-001"
INDEPENDENT_REVIEW_DOCUMENT_ID = (
    "WS-FP022-NAVIGATION-INTERNAL-INDEPENDENT-REVIEW-20260814-001"
)
COMPLETION_DOCUMENT_ID = (
    "WS-FP022-NAVIGATION-WORK-ITEM-COMPLETION-20260814-001"
)

RESULT_DIR = Path("docs/control/execution/goal-results") / GOAL_ID
IMPLEMENTATION_REL = RESULT_DIR / "implementation-record.json"
OBSERVATIONS_REL = RESULT_DIR / "verification-observations.json"
VERIFICATION_REL = RESULT_DIR / "verification-result.json"
SUCCESSOR_REL = RESULT_DIR / "successor-trace.json"
REVIEW_SUBJECT_REL = RESULT_DIR / "review-subject.json"
INDEPENDENT_REVIEW_REL = RESULT_DIR / "independent-review.json"
COMPLETION_REL = RESULT_DIR / "completion-receipt.json"
LANE_LOG_RELS = (
    RESULT_DIR / "logs/backend-navigation-internal.log",
    RESULT_DIR / "logs/android-user-internal.log",
    RESULT_DIR / "logs/test-layer-registry-validate.log",
)
GAP_JSON_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260814-r028.json"
)
GAP_MD_REL = GAP_JSON_REL.with_suffix(".md")
BACKLOG_JSON_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260814-r028.json"
)
BACKLOG_MD_REL = BACKLOG_JSON_REL.with_suffix(".md")
SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp022_goal_completed_seq70_71_20260814.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp022_goal_completed_seq70_71_20260814.py"
)
CHECKER_PATHS = (
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
)
TRANSITION_REVIEW_PATHS = (
    Path("docs/control/execution/workstream-transitions/seq70-71/review-rounds/R014/assignment.json"),
    Path("docs/control/execution/workstream-transitions/seq70-71/review-rounds/R014/review-result.json"),
    Path("docs/control/execution/workstream-transitions/seq70-71/review-rounds/R014/independent-review.json"),
)
CATALOG_PATHS = tuple(Path(path) for path in catalogs.OUTPUT_PATHS)
PRODUCTION_PATHS = (
    IMPLEMENTATION_REL,
    OBSERVATIONS_REL,
    *LANE_LOG_RELS,
    VERIFICATION_REL,
    SUCCESSOR_REL,
    REVIEW_SUBJECT_REL,
    INDEPENDENT_REVIEW_REL,
    COMPLETION_REL,
    GAP_JSON_REL,
    GAP_MD_REL,
    BACKLOG_JSON_REL,
    BACKLOG_MD_REL,
)
MANAGED_PRODUCTION_PATHS = tuple(
    path for path in PRODUCTION_PATHS if path not in LANE_LOG_RELS
)
SEQUENCE_PATHS = (
    *MANAGED_PRODUCTION_PATHS,
    SCRIPT_REL,
    TEST_REL,
    *CHECKER_PATHS,
)
CHANGED_ROLES = ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP", COMPLETION_ROLE]
PRODUCED_ROLES = ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"]
NEXT_ACTION = (
    "경로 정보는 암호화해 보존하고, 경로 이탈 뒤 전체 10단계 사용자 선택 "
    "흐름을 구현한다."
)
FINAL_SCOPE = (
    "Graph v2.4 through the atomic FP022 canonical producer seq70 and completion "
    "seq71 transaction; repository-internal navigation implementation, R028, "
    "review and completion evidence are bound; formal, device, external TMAP, "
    "deployment and release credit remain zero."
)
SHA256_RE = re.compile(r"[0-9a-f]{64}")

UPDATE_FIELDS = {
    "sequence", "event_id", "event_type", "occurred_on", "occurred_at",
    "previous_focus_goal_id", "previous_focus_content_sha256", "focus_goal_id",
    "focus_goal_content_sha256", "from_status", "to_status",
    "static_plan_manifest_sha256", "status_changes", "runtime_after",
    "blockers_after", "blocker_resolution_ids_after", "source_checkpoint_version",
    "evidence_refs", "previous_event_sha256", "produced_by_goal_id",
    "produced_binding_roles", "producer_completion_receipt_binding",
    "changed_binding_roles", "changed_subject_ids_by_role",
    "producer_output_subject_ids_by_role", "impact_closure_goal_ids",
    "impact_disposition_by_goal", "reopened_completion_event_sha256_by_goal",
    "canonical_binding_snapshot_after", "event_sha256",
    "transition_control_review_binding",
}
COMPLETION_FIELDS = {
    "sequence", "event_id", "event_type", "occurred_on", "occurred_at",
    "previous_focus_goal_id", "previous_focus_content_sha256", "focus_goal_id",
    "focus_goal_content_sha256", "subject_goal_id", "from_status", "to_status",
    "static_plan_manifest_sha256", "status_changes", "runtime_after",
    "blockers_after", "blocker_resolution_ids_after", "source_checkpoint_version",
    "evidence_refs", "previous_event_sha256", "canonical_update_event_sha256",
    "completion_receipt_binding", "completion_evidence_bindings",
    "completion_evidence_by_goal_after", "canonical_binding_snapshot_after",
    "event_sha256",
}


class CompletionApplyError(RuntimeError):
    pass


@dataclass(frozen=True)
class CompletionEvidence:
    documents_by_path: Mapping[Path, Mapping[str, Any]]
    bindings_by_role: Mapping[str, Mapping[str, Any]]
    update_occurred_at: str
    completion_occurred_at: str
    physical_sha256_by_path: Mapping[Path, str]
    final_managed_sha256_by_path: Mapping[Path, str]
    transition_review_binding: Mapping[str, Mapping[str, Any]]


@dataclass(frozen=True)
class PreparedProjection:
    root: Path
    checkpoint_path: Path
    source_bytes: bytes
    source: Mapping[str, Any]
    projected: Mapping[str, Any]
    projected_bytes: bytes
    update_event: Mapping[str, Any]
    completion_event: Mapping[str, Any]
    evidence: CompletionEvidence
    source_universe: tuple[str, ...]
    candidate_catalogs: Mapping[Path, bytes]
    catalogs_verified: bool


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CompletionApplyError(message)


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def json_bytes(value: Mapping[str, Any]) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, indent=2, sort_keys=True
    ).encode("utf-8") + b"\n"


def strict_json(raw: bytes, label: str) -> dict[str, Any]:
    def reject(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise CompletionApplyError(f"duplicate JSON key: {label}: {key}")
            result[key] = value
        return result

    value = json.loads(raw.decode("utf-8"), object_pairs_hook=reject)
    require(type(value) is dict, f"JSON root differs: {label}")
    return value


def safe_file(root: Path, relative: Path) -> Path:
    require(not relative.is_absolute() and ".." not in relative.parts, "unsafe path")
    root = root.resolve(strict=True)
    target = root / relative
    current = root
    for part in relative.parts:
        current = current / part
        require(not current.is_symlink(), f"symlink path rejected: {relative}")
    require(target.is_file(), f"file is missing: {relative}")
    return target


def parse_time(value: Any, label: str) -> datetime:
    require(type(value) is str, f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise CompletionApplyError(f"{label} differs") from exc
    require(parsed.tzinfo is not None, f"{label} timezone is missing")
    return parsed


def require_exact_source(content: bytes, source: Mapping[str, Any]) -> None:
    require(len(content) == SOURCE_CHECKPOINT_BYTE_COUNT, "source byte count differs")
    require(
        sha256_bytes(content) == SOURCE_CHECKPOINT_RAW_SHA256,
        "source checkpoint raw SHA-256 differs",
    )
    state = source.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    require(source.get("schema_version") == "1.25.0", "source schema differs")
    require(type(history) is list and len(history) == 69, "source is not exact seq69")
    tail = history[-1]
    require(
        type(tail) is dict
        and tail.get("sequence") == 69
        and tail.get("event_id") == SOURCE_EVENT_ID
        and tail.get("event_type") == "GOAL_STARTED"
        and tail.get("subject_goal_id") == GOAL_ID
        and tail.get("from_status") == "READY"
        and tail.get("to_status") == "IN_PROGRESS"
        and tail.get("event_sha256") == SOURCE_EVENT_SHA256
        and continuation.event_sha256(tail) == SOURCE_EVENT_SHA256,
        "source seq69 seal differs",
    )
    require(
        state.get("transition_history_anchor_sha256") == SOURCE_EVENT_SHA256
        and state.get("status_by_goal", {}).get(GOAL_ID) == "IN_PROGRESS"
        and state.get("focus_goal_id") == GOAL_ID
        and state.get("focus_goal_path") == GOAL_PATH
        and state.get("focus_work_item_id") == GOAL_ID
        and state.get("ready_frontier_goal_ids")
        == [GOAL_ID, PARENT_GOAL_ID, EPIC12_GOAL_ID],
        "source FP022 runtime differs",
    )
    roles = {
        row.get("role")
        for row in source.get("canonical_bindings", [])
        if type(row) is dict
    }
    require(COMPLETION_ROLE not in roles, "source already contains FP022 completion")
    require(
        state.get("pending_producer_completion_goal_id") in {None, ""},
        "source has an open producer transaction",
    )


def _document_id(value: Mapping[str, Any], label: str) -> str:
    result = value.get("document_id")
    if result is None and type(value.get("metadata")) is dict:
        metadata = value["metadata"]
        result = metadata.get("report_id") or metadata.get("backlog_id")
    require(type(result) is str and result, f"{label} document identity differs")
    return result


def _read_documents(root: Path) -> tuple[dict[Path, dict[str, Any]], dict[Path, str]]:
    documents: dict[Path, dict[str, Any]] = {}
    digests: dict[Path, str] = {}
    for relative in PRODUCTION_PATHS:
        raw = safe_file(root, relative).read_bytes()
        require(raw, f"empty production evidence: {relative}")
        digests[relative] = sha256_bytes(raw)
        if relative.suffix == ".json":
            documents[relative] = strict_json(raw, relative.as_posix())
    return documents, digests


def _final_managed_sha256_by_path(
    physical: Mapping[Path, str],
    final_paths: set[Path],
) -> dict[Path, str]:
    require(
        set(LANE_LOG_RELS).isdisjoint(final_paths),
        "ignored lane log entered the final managed source closure",
    )
    require(
        final_paths.issubset(physical),
        "final managed path is missing from the physical evidence cohort",
    )
    return {relative: physical[relative] for relative in final_paths}


def _product_manifest_paths(implementation: Mapping[str, Any]) -> set[Path]:
    manifest = implementation.get("final_content_manifest")
    rows = manifest.get("files") if type(manifest) is dict else None
    require(
        type(rows) is list
        and len(rows) == 22
        and all(type(row) is dict for row in rows),
        "FP022 final product manifest differs",
    )
    paths = [row.get("path") for row in rows]
    require(
        all(
            type(path) is str
            and bool(path)
            and not Path(path).is_absolute()
            and ".." not in Path(path).parts
            for path in paths
        )
        and len(set(paths)) == 22,
        "FP022 final product path set differs",
    )
    return {Path(path) for path in paths}


def load_transition_review_binding(root: Path) -> dict[str, dict[str, Any]]:
    """Validate and bind the separately authored completion-transition review."""
    try:
        review = importlib.import_module(
            "scripts.build_walksafe_fp022_completion_seq70_71_review_20260814"
        )
        binding = review.transition_review_binding(root)
    except (ImportError, OSError, RuntimeError, TypeError, ValueError) as exc:
        raise CompletionApplyError(
            "FP022 seq70/71 transition review is unavailable or invalid"
        ) from exc
    require(
        type(binding) is dict
        and set(binding) == {"assignment", "review_result", "independent_review"},
        "FP022 seq70/71 transition review binding differs",
    )
    expected_paths = {
        "assignment": TRANSITION_REVIEW_PATHS[0],
        "review_result": TRANSITION_REVIEW_PATHS[1],
        "independent_review": TRANSITION_REVIEW_PATHS[2],
    }
    for role, relative in expected_paths.items():
        row = binding[role]
        require(
            type(row) is dict
            and row.get("path") == relative.as_posix()
            and type(row.get("sha256")) is str
            and SHA256_RE.fullmatch(row["sha256"]) is not None
            and row.get("byte_length") == safe_file(root, relative).stat().st_size
            and row["sha256"] == sha256_bytes(safe_file(root, relative).read_bytes()),
            f"FP022 seq70/71 transition review physical binding differs: {role}",
        )
    return copy.deepcopy(binding)


def _validate_r028(gap: Mapping[str, Any], backlog: Mapping[str, Any]) -> None:
    require(_document_id(gap, "R028 Gap").endswith("028"), "R028 Gap ID differs")
    require(_document_id(backlog, "R028 backlog").endswith("028"), "R028 backlog ID differs")
    assessments = gap.get("assessments")
    rows = [
        row for row in assessments or []
        if type(row) is dict
        and row.get("source_policy_id") == "FP-022"
        and row.get("gap_id") == "GAP-031"
    ] if type(assessments) is list else []
    require(len(rows) == 1 and rows[0].get("status") == "PARTIAL", "R028 GAP-031 differs")
    action = backlog.get("next_single_action")
    require(type(action) is dict, "R028 next pointer is missing")
    require(
        action.get("epic_id") == "EPIC-04"
        and action.get("source_policy_id") == "FP-023"
        and action.get("gap_id") == "GAP-032"
        and action.get("priority_rank") == 25
        and action.get("status") == "PLANNED_NEXT"
        and type(action.get("action")) is str
        and action["action"],
        "R028 FP023/GAP-032 rank25 pointer differs",
    )


def _zero_credit_boundary(value: Any) -> bool:
    if type(value) is not dict:
        return False
    expected = {
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "external_tmap_status": "NOT_RUN",
        "external_review_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
        "formal_test_credit_delta": 0,
        "device_credit_delta": 0,
        "external_credit_delta": 0,
        "deployment_credit_delta": 0,
        "release_credit_delta": 0,
    }
    return all(value.get(key) == wanted for key, wanted in expected.items())


def _require_bound_path(
    root: Path,
    container: Mapping[str, Any],
    relative: Path,
    digest: str,
) -> None:
    candidates: list[Mapping[str, Any]] = []
    for key in (
        "result_evidence", "evidence_bindings", "reviewed_evidence",
        "output_evidence_manifest", "bound_artifacts", "inputs", "r028_bindings",
    ):
        rows = container.get(key)
        if type(rows) is list:
            candidates.extend(row for row in rows if type(row) is dict)
    matching = [row for row in candidates if row.get("path") == relative.as_posix()]
    require(len(matching) == 1, f"completion binding missing: {relative}")
    row = matching[0]
    observed = row.get("sha256") or row.get("file_sha256")
    require(observed == digest, f"completion binding SHA-256 differs: {relative}")
    require(sha256_bytes(safe_file(root, relative).read_bytes()) == digest, "physical binding differs")


def load_completion_evidence(root: Path, source: Mapping[str, Any]) -> CompletionEvidence:
    documents, digests = _read_documents(root)
    gap = documents[GAP_JSON_REL]
    backlog = documents[BACKLOG_JSON_REL]
    completion = documents[COMPLETION_REL]
    review = documents[INDEPENDENT_REVIEW_REL]
    _validate_r028(gap, backlog)
    exact_identities = {
        IMPLEMENTATION_REL: (
            "walksafe.fp022-navigation-implementation-record.v1",
            IMPLEMENTATION_DOCUMENT_ID,
        ),
        VERIFICATION_REL: (
            "walksafe.fp022-verification-result.v1",
            VERIFICATION_DOCUMENT_ID,
        ),
        SUCCESSOR_REL: (
            "walksafe.fp022-gap031-successor-trace.v1",
            SUCCESSOR_DOCUMENT_ID,
        ),
        INDEPENDENT_REVIEW_REL: (
            "walksafe.fp022-internal-independent-review.v1",
            INDEPENDENT_REVIEW_DOCUMENT_ID,
        ),
        COMPLETION_REL: (
            "walksafe.fp022-work-item-completion-receipt.v1",
            COMPLETION_DOCUMENT_ID,
        ),
    }
    for relative, (schema_version, document_id) in exact_identities.items():
        document = documents[relative]
        require(
            document.get("schema_version") == schema_version
            and document.get("document_id") == document_id,
            f"exact FP022 evidence identity differs: {relative}",
        )
        require(
            _zero_credit_boundary(document.get("completion_boundary")),
            f"FP022 evidence zero-credit boundary differs: {relative}",
        )
    require(
        documents[IMPLEMENTATION_REL].get("kind") == "IMPLEMENTATION_RECORD"
        and documents[IMPLEMENTATION_REL].get("status") == "PASS"
        and documents[IMPLEMENTATION_REL].get("credit_scope")
        == "REPOSITORY_INTERNAL_ONLY"
        and documents[VERIFICATION_REL].get("kind") == "VERIFICATION_RESULT"
        and documents[VERIFICATION_REL].get("status") == "PASS"
        and documents[VERIFICATION_REL].get("credit_scope")
        == "REPOSITORY_INTERNAL_ONLY"
        and documents[SUCCESSOR_REL].get("kind") == "SUCCESSOR_TRACE"
        and documents[SUCCESSOR_REL].get("gap031_status") == "PARTIAL",
        "FP022 result kind/status/scope differs",
    )
    require(
        completion.get("kind") == "WORK_ITEM_COMPLETION_RECEIPT"
        and completion.get("goal_id") == GOAL_ID
        and completion.get("source_policy_ids") == ["FP-022"]
        and completion.get("gap_ids") == ["GAP-031"]
        and completion.get("target_completion_level")
        == "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
        and completion.get("status") == "ACCEPTED"
        and completion.get("result") == "PASS"
        and completion.get("execution_session_event")
        == {
            "sequence": 69,
            "event_id": SOURCE_EVENT_ID,
            "event_type": "GOAL_STARTED",
            "event_sha256": SOURCE_EVENT_SHA256,
        },
        "FP022 completion receipt identity/status differs",
    )
    require(_zero_credit_boundary(completion.get("completion_boundary")), "completion zero-credit boundary differs")
    require(
        review.get("goal_id") == GOAL_ID
        and review.get("kind") == "INTERNAL_INDEPENDENT_REVIEW"
        and review.get("status") == "PASS"
        and review.get("decision") in {"APPROVED", "PASS"}
        and review.get("external_independence_claimed") is False,
        "FP022 independent internal review differs",
    )
    for relative in (IMPLEMENTATION_REL, VERIFICATION_REL, SUCCESSOR_REL, GAP_JSON_REL, BACKLOG_JSON_REL):
        _require_bound_path(root, completion, relative, digests[relative])
    review_binding = completion.get("independent_review_binding")
    require(
        type(review_binding) is dict
        and review_binding.get("path") == INDEPENDENT_REVIEW_REL.as_posix()
        and review_binding.get("sha256") == digests[INDEPENDENT_REVIEW_REL]
        and review_binding.get("byte_length")
        == safe_file(root, INDEPENDENT_REVIEW_REL).stat().st_size,
        "completion independent-review binding differs",
    )
    start_at = parse_time(source["goal_execution"]["transition_history"][-1]["occurred_at"], "seq69 occurred_at")
    generated_value = completion.get("generated_at") or completion.get("completed_at")
    generated_at = parse_time(generated_value, "completion generated_at")
    require(generated_at >= start_at, "completion predates seq69")
    update_at = max(start_at + timedelta(seconds=1), generated_at)
    completion_at = update_at + timedelta(seconds=1)
    bindings = {
        "IMPLEMENTATION_GAP": {
            "role": "IMPLEMENTATION_GAP",
            "document_id": _document_id(gap, "R028 Gap"),
            "path": GAP_JSON_REL.as_posix(),
            "file_sha256": digests[GAP_JSON_REL],
        },
        "IMPLEMENTATION_BACKLOG": {
            "role": "IMPLEMENTATION_BACKLOG",
            "document_id": _document_id(backlog, "R028 backlog"),
            "path": BACKLOG_JSON_REL.as_posix(),
            "file_sha256": digests[BACKLOG_JSON_REL],
        },
        COMPLETION_ROLE: {
            "role": COMPLETION_ROLE,
            "document_id": COMPLETION_DOCUMENT_ID,
            "path": COMPLETION_REL.as_posix(),
            "file_sha256": digests[COMPLETION_REL],
        },
    }
    snapshot_paths = source.get("working_tree_snapshot", {}).get("managed_changed_paths")
    require(type(snapshot_paths) is list, "source managed paths are missing")
    git_visible = npc_atomic._git_visible_managed_paths(root)
    final_paths = (
        {Path(path) for path in snapshot_paths}
        | git_visible
        | set(SEQUENCE_PATHS)
        | set(CATALOG_PATHS)
    )
    physical = dict(digests)
    transition_review = load_transition_review_binding(root)
    final_paths |= (
        set(TRANSITION_REVIEW_PATHS)
        | _product_manifest_paths(documents[IMPLEMENTATION_REL])
    )
    for relative in final_paths - set(digests):
        physical[relative] = sha256_bytes(safe_file(root, relative).read_bytes())
    final_managed = _final_managed_sha256_by_path(physical, final_paths)
    return CompletionEvidence(
        documents_by_path=documents,
        bindings_by_role=bindings,
        update_occurred_at=update_at.isoformat(),
        completion_occurred_at=completion_at.isoformat(),
        physical_sha256_by_path=physical,
        final_managed_sha256_by_path=final_managed,
        transition_review_binding=transition_review,
    )


def _canonical_bindings(source: Mapping[str, Any], evidence: CompletionEvidence) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for source_row in source.get("canonical_bindings", []):
        require(type(source_row) is dict, "canonical binding differs")
        role = source_row.get("role")
        require(type(role) is str and role not in seen, "canonical role differs")
        seen.add(role)
        if role not in {"IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"}:
            result.append(copy.deepcopy(source_row))
            continue
        row = copy.deepcopy(source_row)
        row.update(evidence.bindings_by_role[role])
        result.append(row)
    require(COMPLETION_ROLE not in seen, "FP022 completion role already exists")
    result.append({
        **evidence.bindings_by_role[COMPLETION_ROLE],
        "identity_json_path": "document_id",
        "mutable": False,
    })
    return result


def _gap_snapshot(gap: Mapping[str, Any], backlog: Mapping[str, Any]) -> dict[str, Any]:
    assessments = gap.get("assessments")
    epics = backlog.get("epics")
    require(type(assessments) is list and type(epics) is list, "R028 snapshot differs")
    epic_counts: dict[str, int] = {}
    for epic in epics:
        require(type(epic) is dict and type(epic.get("current_status")) is str, "R028 epic differs")
        status = epic["current_status"]
        epic_counts[status] = epic_counts.get(status, 0) + 1
    return {
        "report_id": _document_id(gap, "R028 Gap"),
        "report_version": gap.get("metadata", {}).get("version"),
        "assessment_count": len(assessments),
        "status_counts": copy.deepcopy(gap.get("summary", {}).get("status_counts")),
        "backlog_id": _document_id(backlog, "R028 backlog"),
        "epic_count": len(epics),
        "epic_status_counts": epic_counts,
        "implementation_snapshot": copy.deepcopy(gap.get("implementation_snapshot")),
    }


def _runtime(state: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "focus_goal_id": state["focus_goal_id"],
        "focus_goal_path": state["focus_goal_path"],
        "focus_work_item_id": state["focus_work_item_id"],
        "focus_source": state["focus_source"],
        "ready_frontier_goal_ids": copy.deepcopy(state["ready_frontier_goal_ids"]),
        "blocked_goal_ids": copy.deepcopy(state["blocked_goal_ids"]),
        "pending_questions": copy.deepcopy(state["pending_questions"]),
        "open_question_count": state["open_question_count"],
        "artifact_work_queue_sha256": continuation.canonical_json_sha256(state["artifact_work_queue"]),
        "completion_boundary_sha256": continuation.canonical_json_sha256(state["completion_boundary"]),
        "activation_status": state["activation_status"],
        "package_status": state["package_status"],
    }


def _snapshot_hashes(digests: Mapping[Path, str]) -> tuple[str, str]:
    rows = sorted((path.as_posix(), digest) for path, digest in digests.items())
    path_hash = hashlib.sha256(
        ("\n".join(path for path, _ in rows) + "\n").encode("utf-8")
    ).hexdigest()
    content = hashlib.sha256()
    for path, digest in rows:
        require(SHA256_RE.fullmatch(digest) is not None, f"snapshot digest differs: {path}")
        content.update(path.encode("utf-8"))
        content.update(b"\0")
        content.update(digest.encode("ascii"))
        content.update(b"\n")
    return path_hash, content.hexdigest()


def project_seq70_71(
    root: Path,
    source: Mapping[str, Any],
    evidence: CompletionEvidence,
    *,
    runtime_deriver: Callable[[Path, dict[str, Any], list[str]], tuple[dict[str, Any], dict[str, Any]]] = atomic.derive_runtime,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    projected = copy.deepcopy(source)
    projected["canonical_bindings"] = _canonical_bindings(source, evidence)
    projected["implementation_gap_snapshot"] = _gap_snapshot(
        evidence.documents_by_path[GAP_JSON_REL], evidence.documents_by_path[BACKLOG_JSON_REL]
    )
    state = projected["goal_execution"]
    snapshot = continuation.canonical_binding_snapshot(projected)
    queue70, boundary70 = runtime_deriver(root, projected, [GOAL_ID, PARENT_GOAL_ID, EPIC12_GOAL_ID])
    state["artifact_work_queue"] = queue70
    state["completion_boundary"] = boundary70
    completion_binding = copy.deepcopy(evidence.bindings_by_role[COMPLETION_ROLE])
    update = {
        "sequence": 70,
        "event_id": UPDATE_EVENT_ID,
        "event_type": "CANONICAL_BINDINGS_UPDATED",
        "occurred_on": datetime.fromisoformat(evidence.update_occurred_at).date().isoformat(),
        "occurred_at": evidence.update_occurred_at,
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": GOAL_ID,
        "focus_goal_content_sha256": GOAL_SHA256,
        "from_status": "IN_PROGRESS",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": _runtime(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [row["resolution_id"] for row in state["blocker_resolution_history"]],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": list(CHANGED_ROLES),
        "previous_event_sha256": SOURCE_EVENT_SHA256,
        "produced_by_goal_id": GOAL_ID,
        "produced_binding_roles": list(PRODUCED_ROLES),
        "producer_completion_receipt_binding": completion_binding,
        "changed_binding_roles": list(CHANGED_ROLES),
        "changed_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-022"],
            "IMPLEMENTATION_GAP": ["FP-022", "GAP-031"],
        },
        "producer_output_subject_ids_by_role": {
            "IMPLEMENTATION_BACKLOG": ["FP-022"],
            "IMPLEMENTATION_GAP": ["FP-022", "GAP-031"],
        },
        "impact_closure_goal_ids": [PARENT_GOAL_ID],
        "impact_disposition_by_goal": {
            PARENT_GOAL_ID: {"result": "REVALIDATION_REFRESH_REQUIRED", "target_status": "READY"}
        },
        "reopened_completion_event_sha256_by_goal": {},
        "canonical_binding_snapshot_after": copy.deepcopy(snapshot),
        "transition_control_review_binding": copy.deepcopy(
            evidence.transition_review_binding
        ),
    }
    update["event_sha256"] = continuation.event_sha256(update)
    require(set(update) == UPDATE_FIELDS, "seq70 field set differs")
    state["transition_history"].append(update)
    state["pending_producer_completion_goal_id"] = GOAL_ID

    state["status_by_goal"][GOAL_ID] = "COMPLETE_AT_TARGET"
    state["focus_goal_id"] = PARENT_GOAL_ID
    state["focus_goal_path"] = PARENT_GOAL_PATH
    state["focus_work_item_id"] = ""
    state["focus_source"] = "WORKSTREAM_GRAPH"
    state["ready_frontier_goal_ids"] = [PARENT_GOAL_ID, EPIC12_GOAL_ID]
    queue71, boundary71 = runtime_deriver(root, projected, [PARENT_GOAL_ID, EPIC12_GOAL_ID])
    state["artifact_work_queue"] = queue71
    state["completion_boundary"] = boundary71
    completed = copy.deepcopy(state["completion_evidence_by_goal"])
    completed[GOAL_ID] = [COMPLETION_ROLE]
    completion = {
        "sequence": 71,
        "event_id": COMPLETION_EVENT_ID,
        "event_type": "GOAL_COMPLETED",
        "occurred_on": datetime.fromisoformat(evidence.completion_occurred_at).date().isoformat(),
        "occurred_at": evidence.completion_occurred_at,
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": PARENT_GOAL_ID,
        "focus_goal_content_sha256": PARENT_GOAL_SHA256,
        "subject_goal_id": GOAL_ID,
        "from_status": "IN_PROGRESS",
        "to_status": "COMPLETE_AT_TARGET",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {GOAL_ID: "COMPLETE_AT_TARGET"},
        "runtime_after": _runtime(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [row["resolution_id"] for row in state["blocker_resolution_history"]],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [COMPLETION_ROLE],
        "previous_event_sha256": update["event_sha256"],
        "canonical_update_event_sha256": update["event_sha256"],
        "completion_receipt_binding": completion_binding,
        "completion_evidence_bindings": {COMPLETION_ROLE: completion_binding},
        "completion_evidence_by_goal_after": completed,
        "canonical_binding_snapshot_after": copy.deepcopy(snapshot),
    }
    completion["event_sha256"] = continuation.event_sha256(completion)
    require(set(completion) == COMPLETION_FIELDS, "seq71 field set differs")
    state["transition_history"].append(completion)
    state["transition_history_anchor_sha256"] = completion["event_sha256"]
    state["validation_cutoff_at"] = evidence.completion_occurred_at
    state["completion_evidence_by_goal"] = completed
    state["pending_producer_completion_goal_id"] = ""

    action = evidence.documents_by_path[BACKLOG_JSON_REL]["next_single_action"]["action"]
    current = projected["current_work"]
    current["work_item_id"] = ""
    current["last_completed_work_summary"] = (
        "FP-022/GAP-031 repository-internal TMAP destination, route, arrival and "
        "user-decision controls completed"
    )
    current["current_focus"] = (
        "EPIC-04 workstream READY; FP-023/GAP-032 rank25 is the semantic next pointer"
    )
    current["next_action"] = action
    current["release_completion_claimed"] = False
    handoff = projected["session_handoff"]
    handoff["current_epic"] = "EPIC-04"
    handoff["next_single_action"] = action
    handoff["last_updated_by_work_item"] = GOAL_ID
    handoff["last_verification_status"] = "PASS_INTERNAL_ONLY_FORMAL_EXTERNAL_RELEASE_NOT_RUN"
    paths = sorted(path.as_posix() for path in evidence.final_managed_sha256_by_path)
    path_hash, content_hash = _snapshot_hashes(evidence.final_managed_sha256_by_path)
    working = projected["working_tree_snapshot"]
    working["managed_changed_paths"] = paths
    working["managed_changed_path_count"] = len(paths)
    working["path_set_sha256"] = path_hash
    working["content_set_sha256"] = content_hash
    working["scope"] = FINAL_SCOPE
    source_snapshot = handoff["source_commit_or_snapshot"]
    source_snapshot["file_count"] = len(paths)
    source_snapshot["path_set_sha256"] = path_hash
    source_snapshot["content_set_sha256"] = content_hash
    handoff["changed_files"] = paths
    return projected, update, completion


def validate_projection(source: Mapping[str, Any], projected: Mapping[str, Any], evidence: CompletionEvidence) -> None:
    source_state = source["goal_execution"]
    state = projected["goal_execution"]
    history = state.get("transition_history")
    require(type(history) is list and len(history) == 71, "projection is not seq71")
    require(history[:-2] == source_state["transition_history"], "pre-seq70 history changed")
    update, completion = history[-2:]
    require(set(update) == UPDATE_FIELDS and set(completion) == COMPLETION_FIELDS, "suffix fields differ")
    require(update["event_sha256"] == continuation.event_sha256(update), "seq70 seal differs")
    require(completion["event_sha256"] == continuation.event_sha256(completion), "seq71 seal differs")
    require(completion["previous_event_sha256"] == update["event_sha256"], "seq70/71 are not adjacent")
    require(
        update.get("transition_control_review_binding")
        == evidence.transition_review_binding,
        "seq70 transition review binding differs",
    )
    require(state["status_by_goal"].get(GOAL_ID) == "COMPLETE_AT_TARGET", "FP022 final status differs")
    require(state["focus_goal_id"] == PARENT_GOAL_ID and state["focus_work_item_id"] == "", "final focus differs")
    require(state["ready_frontier_goal_ids"] == [PARENT_GOAL_ID, EPIC12_GOAL_ID], "final frontier differs")
    require(state["completion_evidence_by_goal"].get(GOAL_ID) == [COMPLETION_ROLE], "completion role differs")
    require(state.get("pending_producer_completion_goal_id") in {None, ""}, "producer transaction remains open")
    before = continuation.canonical_binding_snapshot(source)
    after = continuation.canonical_binding_snapshot(projected)
    changed = sorted(role for role in set(before) | set(after) if before.get(role) != after.get(role))
    require(changed == sorted(CHANGED_ROLES), "canonical role delta differs")
    require(update["canonical_binding_snapshot_after"] == after == completion["canonical_binding_snapshot_after"], "canonical snapshots differ")
    for role in CHANGED_ROLES:
        require(after[role] == evidence.bindings_by_role[role], f"canonical binding differs: {role}")
    for field in ("authority_boundary", "verification_boundary", "approved_state"):
        require(projected.get(field) == source.get(field), f"{field} credit changed")
    require(
        projected.get("approved_state", {}).get("release_status") == "NOT_ELIGIBLE"
        and projected.get("verification_boundary", {}).get("formal_test_pass_claimed") is False
        and projected.get("verification_boundary", {}).get("release_eligible") is False,
        "formal/release credit changed",
    )
    allowed_top = {"canonical_bindings", "current_work", "goal_execution", "implementation_gap_snapshot", "session_handoff", "working_tree_snapshot"}
    for key in set(source) | set(projected):
        if key not in allowed_top:
            require(source.get(key) == projected.get(key), f"unauthorized top-level mutation: {key}")


def run_continuation(root: Path, checkpoint: Path) -> list[str]:
    return continuation.validate(root, checkpoint, continuation.V23_ARCHIVE_RELATIVE, continuation.V24_MANIFEST_RELATIVE)


def run_goal_graph(root: Path, checkpoint: Path) -> list[str]:
    return goal_graph.validate(root, checkpoint, check_continuation=False, run_frozen_semantics=False)


def _validate_projected(root: Path, raw: bytes, continuation_checker: Callable[[Path, Path], list[str]], goal_checker: Callable[[Path, Path], list[str]]) -> None:
    descriptor, name = tempfile.mkstemp(dir=root / CHECKPOINT_REL.parent, prefix=".walksafe-fp022-seq70-71-preflight.", suffix=".json")
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        relative = temporary.relative_to(root)
        errors = continuation_checker(root, relative)
        require(not errors, "projected continuation failed: " + "; ".join(errors))
        errors = goal_checker(root, relative)
        require(not errors, "projected Goal graph failed: " + "; ".join(errors))
    finally:
        temporary.unlink(missing_ok=True)


def _with_catalogs(evidence: CompletionEvidence, candidates: Mapping[Path, bytes]) -> CompletionEvidence:
    require(set(candidates) == set(CATALOG_PATHS), "candidate catalog inventory differs")
    missing = set(CATALOG_PATHS) - set(evidence.final_managed_sha256_by_path)
    require(
        not missing,
        "candidate catalogs are outside the final managed closure: "
        + ", ".join(path.as_posix() for path in sorted(missing, key=Path.as_posix)),
    )
    final = dict(evidence.final_managed_sha256_by_path)
    physical = dict(evidence.physical_sha256_by_path)
    for path, raw in candidates.items():
        final[path] = sha256_bytes(raw)
        physical[path] = sha256_bytes(raw)
    return CompletionEvidence(
        documents_by_path=evidence.documents_by_path,
        bindings_by_role=evidence.bindings_by_role,
        update_occurred_at=evidence.update_occurred_at,
        completion_occurred_at=evidence.completion_occurred_at,
        physical_sha256_by_path=physical,
        final_managed_sha256_by_path=final,
        transition_review_binding=evidence.transition_review_binding,
    )


def _require_catalog_universe(
    root: Path,
    expected: tuple[str, ...],
    *,
    atomic_target: Path | None = None,
    allowed_stage_bytes: tuple[bytes, bytes] | None = None,
) -> None:
    observed = catalogs.discover_source_paths(root)
    if observed == expected:
        return
    require(
        atomic_target is not None and allowed_stage_bytes is not None,
        "catalog source universe changed",
    )
    extras = sorted(set(observed) - set(expected))
    require(not (set(expected) - set(observed)) and len(extras) == 1, "catalog source universe changed")
    relative = Path(extras[0])
    require(
        relative.parent == atomic_target.relative_to(root).parent
        and re.fullmatch(
            rf"\.{re.escape(atomic_target.name)}\.fp048-seq43-44\.[0-9a-f]{{24}}\.tmp",
            relative.name,
        ) is not None,
        "atomic catalog staging path differs",
    )
    raw = safe_file(root, relative).read_bytes()
    require(raw in allowed_stage_bytes, "atomic catalog staging bytes differ")


def prepare_projection(
    root: Path = ROOT,
    *,
    source_validator: Callable[[bytes, Mapping[str, Any]], None] = require_exact_source,
    evidence_loader: Callable[[Path, Mapping[str, Any]], CompletionEvidence] = load_completion_evidence,
    runtime_deriver: Callable[[Path, dict[str, Any], list[str]], tuple[dict[str, Any], dict[str, Any]]] = atomic.derive_runtime,
    catalog_source_loader: Callable[[Path], tuple[str, ...]] = catalogs.discover_source_paths,
    catalog_builder: Callable[..., Mapping[str, bytes]] = catalogs.build_catalog_bytes,
    continuation_checker: Callable[[Path, Path], list[str]] = run_continuation,
    goal_checker: Callable[[Path, Path], list[str]] = run_goal_graph,
    allow_stale_catalogs: bool = False,
) -> PreparedProjection:
    root = root.resolve(strict=True)
    checkpoint_path = safe_file(root, CHECKPOINT_REL)
    source_bytes = checkpoint_path.read_bytes()
    source = strict_json(source_bytes, CHECKPOINT_REL.as_posix())
    source_validator(source_bytes, source)
    source_universe = catalog_source_loader(root)
    evidence = evidence_loader(root, source)
    draft, _, _ = project_seq70_71(root, source, evidence, runtime_deriver=runtime_deriver)
    candidate_raw = catalog_builder(root, source_universe, checkpoint_override=draft)
    require(set(candidate_raw) == set(catalogs.OUTPUT_PATHS), "catalog inventory differs")
    candidates = {Path(path): raw for path, raw in candidate_raw.items()}
    evidence = _with_catalogs(evidence, candidates)
    projected, update, completion = project_seq70_71(root, source, evidence, runtime_deriver=runtime_deriver)
    require(
        {Path(path): raw for path, raw in catalog_builder(root, source_universe, checkpoint_override=projected).items()} == candidates,
        "catalog/checkpoint projection did not reach a fixed point",
    )
    require(
        catalog_source_loader(root) == source_universe,
        "catalog source universe changed during projection",
    )
    npc_atomic._require_git_visible_changes_are_managed(
        root,
        evidence.final_managed_sha256_by_path,
    )
    validate_projection(source, projected, evidence)
    projected_bytes = json_bytes(projected)
    if not allow_stale_catalogs:
        for path, raw in candidates.items():
            require(safe_file(root, path).read_bytes() == raw, f"catalog is stale: {path}")
        _validate_projected(root, projected_bytes, continuation_checker, goal_checker)
        for path, digest in evidence.physical_sha256_by_path.items():
            require(sha256_bytes(safe_file(root, path).read_bytes()) == digest, f"physical input changed: {path}")
        paths = sorted(path.as_posix() for path in evidence.final_managed_sha256_by_path)
        require(
            continuation.working_snapshot_hashes(root, paths)
            == _snapshot_hashes(evidence.final_managed_sha256_by_path),
            "live managed closure differs from projected snapshot",
        )
    require(checkpoint_path.read_bytes() == source_bytes, "source changed during preflight")
    return PreparedProjection(root, checkpoint_path, source_bytes, source, projected, projected_bytes, update, completion, evidence, source_universe, candidates, not allow_stale_catalogs)


def write_catalogs(prepared: PreparedProjection, *, atomic_writer: Callable[..., None] = atomic.atomic_write) -> None:
    require(not prepared.catalogs_verified, "catalog refresh requires stale-catalog projection")
    active: tuple[Path, bytes, bytes] | None = None
    def guard() -> None:
        require(
            prepared.checkpoint_path.read_bytes() == prepared.source_bytes,
            "source changed during catalog refresh",
        )
        _require_catalog_universe(
            prepared.root,
            prepared.source_universe,
            atomic_target=None if active is None else active[0],
            allowed_stage_bytes=None if active is None else (active[1], active[2]),
        )
        rebuilt = catalogs.build_catalog_bytes(
            prepared.root,
            prepared.source_universe,
            checkpoint_override=prepared.projected,
        )
        require(
            {Path(path): raw for path, raw in rebuilt.items()}
            == prepared.candidate_catalogs,
            "catalog fixed point changed during refresh",
        )

    guard()
    for relative in CATALOG_PATHS:
        target = safe_file(prepared.root, relative)
        source = target.read_bytes()
        wanted = prepared.candidate_catalogs[relative]
        if source != wanted:
            active = (target, source, wanted)
            atomic_writer(
                target,
                wanted,
                expected_source=source,
                commit_guard=guard,
            )
            active = None
        guard()


def _require_checkpoint_commit_phase(
    checkpoint_path: Path,
    source_bytes: bytes,
    projected_bytes: bytes,
) -> None:
    require(
        checkpoint_path.read_bytes() in {source_bytes, projected_bytes},
        "checkpoint left the source/projected commit phases",
    )


def write_projection(prepared: PreparedProjection, *, atomic_writer: Callable[..., None] = atomic.atomic_write) -> None:
    require(prepared.catalogs_verified, "stale catalogs cannot publish checkpoint")
    cohort = npc_atomic.retain_physical_pin_cohort(prepared.root, prepared.evidence.physical_sha256_by_path)
    primary: BaseException | None = None
    try:
        def guard() -> None:
            cohort.verify()
            _require_checkpoint_commit_phase(
                prepared.checkpoint_path,
                prepared.source_bytes,
                prepared.projected_bytes,
            )
            _require_catalog_universe(
                prepared.root,
                prepared.source_universe,
                atomic_target=prepared.checkpoint_path,
                allowed_stage_bytes=(prepared.source_bytes, prepared.projected_bytes),
            )
            rebuilt = catalogs.build_catalog_bytes(
                prepared.root,
                prepared.source_universe,
                checkpoint_override=prepared.projected,
            )
            require(
                {Path(path): raw for path, raw in rebuilt.items()}
                == prepared.candidate_catalogs,
                "catalog fixed point changed at commit",
            )
            for path, raw in prepared.candidate_catalogs.items():
                require(safe_file(prepared.root, path).read_bytes() == raw, f"catalog changed: {path}")
            npc_atomic._require_git_visible_changes_are_managed(
                prepared.root,
                prepared.evidence.final_managed_sha256_by_path,
            )
            managed = sorted(
                path.as_posix()
                for path in prepared.evidence.final_managed_sha256_by_path
            )
            require(
                continuation.working_snapshot_hashes(prepared.root, managed)
                == _snapshot_hashes(prepared.evidence.final_managed_sha256_by_path),
                "managed closure changed at commit",
            )
            validate_projection(prepared.source, prepared.projected, prepared.evidence)

        guard()
        atomic_writer(prepared.checkpoint_path, prepared.projected_bytes, expected_source=prepared.source_bytes, commit_guard=guard)
    except BaseException as exc:
        primary = exc
        raise
    finally:
        cohort.close(primary)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--refresh-catalogs", action="store_true")
    mode.add_argument("--write", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.refresh_catalogs:
            stale = prepare_projection(args.root, allow_stale_catalogs=True)
            write_catalogs(stale)
            prepared = prepare_projection(args.root)
            mode = "REFRESH_CATALOGS"
        else:
            prepared = prepare_projection(args.root)
            if args.write:
                write_projection(prepared)
            mode = "WRITE" if args.write else "PREFLIGHT"
    except (CompletionApplyError, atomic.CompletionApplyError, atomic.CompletionPostCommitError, OSError, ValueError, TypeError, KeyError) as exc:
        print(f"FP022 GOAL_COMPLETED seq70-71: FAIL: {exc}")
        return 1
    print(
        "FP022 GOAL_COMPLETED seq70-71: PASS "
        f"mode={mode} update_sha256={prepared.update_event['event_sha256']} "
        f"completion_sha256={prepared.completion_event['event_sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
