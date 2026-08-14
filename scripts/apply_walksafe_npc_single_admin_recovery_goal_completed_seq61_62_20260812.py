#!/usr/bin/env python3
"""Project NPC recovery canonical update seq61 and Goal completion seq62."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import os
from pathlib import Path
import re
import stat
import tempfile
from typing import Any, Callable, Mapping, Sequence

from scripts import build_walksafe_npc_single_admin_recovery_trace_20260812 as trace
from scripts import build_walksafe_npc_single_admin_recovery_gap_backlog_r027_20260813 as gap_builder
from scripts import build_walksafe_npc_single_admin_recovery_artifact_trace_correction_v2_20260813 as artifact_builder
from scripts import build_walksafe_phase1_exact257_successor_r016_20260813 as r016_builder
from scripts import build_walksafe_npc_single_admin_recovery_strict_review_gate_20260812 as review_builder
from scripts import build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813 as r004_review
from scripts import build_walksafe_npc_single_admin_recovery_r005_followup_review_20260813 as r005_review
from scripts import build_walksafe_npc_single_admin_recovery_r006_followup_review_20260813 as r006_review
from scripts import build_walksafe_npc_single_admin_recovery_r007_followup_review_20260813 as r007_review
from scripts import build_walksafe_npc_single_admin_recovery_r008_followup_review_20260813 as r008_review
from scripts import build_walksafe_npc_single_admin_recovery_r009_followup_review_20260813 as r009_review
from scripts import build_walksafe_npc_single_admin_recovery_r010_followup_review_20260813 as r010_review
from scripts import build_walksafe_npc_single_admin_recovery_r011_followup_review_20260813 as r011_review
from scripts import apply_walksafe_fp046_goal_completed_seq54_55_20260810 as fp046_apply
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as contract
from scripts import check_walksafe_project_continuation_v2_3 as git_utility
from scripts import generate_repository_catalogs as repository_catalogs


ROOT = Path(__file__).resolve().parents[1]
CHECKPOINT_REL = Path("docs/control/walksafe-project-continuation-checkpoint.json")
CATALOG_PATHS = tuple(Path(path) for path in repository_catalogs.OUTPUT_PATHS)
SOURCE_CHECKPOINT_RAW_SHA256 = "d5d4a5ae00442c54b3509f5b6996297aad6751733ca4328f543f25d563c7e779"
SOURCE_CHECKPOINT_BYTE_COUNT = 1_784_762
SOURCE_START_EVENT_SHA256 = trace.EXPECTED_START_EVENT_SHA256
MANIFEST_SHA256 = "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
GOAL_ID = trace.GOAL_ID
GOAL_SHA256 = trace.EXPECTED_GOAL_SHA256
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
PARENT_GOAL_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-03-account-admin-security.md"
)
PARENT_GOAL_SHA256 = "7d2dfb7fe89bd30fc1f6113acb0b533a9fd91a6106cc2ab4798091763beae832"
FOCUS_GOAL_ID = "WS-GOAL-EPIC-02"
FOCUS_GOAL_PATH = Path(
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-02-safe-walk-state-and-permissions.md"
)
FOCUS_GOAL_SHA256 = "40fafdf86acf23c8c7243bebc3e7c4c0566a56123b5c4c815f29d5fc3eb15665"
EPIC12_GOAL_ID = "WS-GOAL-EPIC-12"
COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
CANONICAL_UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-NPC-SINGLE-ADMIN-RECOVERY-20260812-001"
)
COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-NPC-SINGLE-ADMIN-RECOVERY-20260812-001"
)
FINAL_SCOPE = (
    "Graph v2.4 through the atomic NPC single-admin recovery canonical "
    "producer seq61 and completion seq62 transaction; all repository-internal "
    "product, exact-six correction v2, R027, R016, result, registry, guide, test, and script "
    "bytes are bound; no formal, external, device, deployment, or release "
    "credit is granted."
)

SCRIPT_RELATIVE = Path(
    "scripts/apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py"
)
TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py"
)
SEQUENCE_AUTHORITY_PATHS = (
    Path("scripts/build_walksafe_npc_single_admin_recovery_trace_20260812.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_trace_20260812.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_gap_backlog_r026_20260812.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_gap_backlog_r026_20260812.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_artifact_trace_successor_20260812.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_artifact_trace_successor_20260812.py"),
    Path("scripts/build_walksafe_phase1_exact257_successor_r015_20260812.py"),
    Path("tests/test_build_walksafe_phase1_exact257_successor_r015_20260812.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_gap_backlog_r027_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_gap_backlog_r027_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_artifact_trace_correction_v2_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_artifact_trace_correction_v2_20260813.py"),
    Path("scripts/build_walksafe_phase1_exact257_successor_r016_20260813.py"),
    Path("tests/test_build_walksafe_phase1_exact257_successor_r016_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_strict_review_gate_20260812.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_strict_review_gate_20260812.py"),
    Path("scripts/run_walksafe_npc_single_admin_recovery_verification_20260813.py"),
    Path("tests/test_run_walksafe_npc_single_admin_recovery_verification_20260813.py"),
    Path("scripts/check_walksafe_goal_graph_v2_4.py"),
    Path("tests/test_walksafe_goal_graph_v2_4.py"),
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    Path("scripts/generate_repository_catalogs.py"),
    Path("tests/test_repository_catalogs.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r005_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r005_followup_review_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r006_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r006_followup_review_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r007_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r007_followup_review_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r008_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r008_followup_review_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r009_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r009_followup_review_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r010_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r010_followup_review_20260813.py"),
    Path("scripts/build_walksafe_npc_single_admin_recovery_r011_followup_review_20260813.py"),
    Path("tests/test_build_walksafe_npc_single_admin_recovery_r011_followup_review_20260813.py"),
    SCRIPT_RELATIVE,
    TEST_RELATIVE,
)
AUXILIARY_FINAL_PATHS = {
    Path("README.md"),
    Path("docs/catalogs/README.md"),
    Path("docs/catalogs/repository-paths.json"),
    Path("docs/catalogs/scripts.json"),
    Path("docs/catalogs/tests.json"),
    Path("docs/README.md"),
    Path("docs/guides/project-guide.md"),
    Path("docs/guides/team-workflow.md"),
    Path("docs/planning/walksafe_feature_implementation_catalog.html"),
    Path("scripts/README.md"),
    Path("scripts/generate_repository_catalogs.py"),
    Path("scripts/run_walksafe_test_layers_current.sh"),
    Path("tests/README.md"),
    Path("tests/test_repository_catalogs.py"),
}
DAYLOG_PATH_RE = re.compile(r"^daylog/\d{4}-\d{2}-\d{2}\.md$")
CHECKPOINT_TRANSPORT_STAGE_NAME_RE = re.compile(
    rf"^\.{re.escape(CHECKPOINT_REL.name)}\.fp048-seq43-44\."
    r"[0-9a-f]{24}\.tmp$"
)

CANONICAL_PATH_BY_ROLE = {
    "ARTIFACT_CHANGE_LOG": artifact_builder.DOC05_REL,
    "ARTIFACT_REGISTER": artifact_builder.DOC01_REL,
    "REQUIREMENTS_TRACEABILITY": artifact_builder.RTM_REL,
    "DESIGN_TRACEABILITY": artifact_builder.DESIGN_REL,
    "MODULE_REGISTER": artifact_builder.MODULE_REGISTER_REL,
    "IMPLEMENTATION_GAP": gap_builder.R027_GAP_JSON_REL,
    "IMPLEMENTATION_BACKLOG": gap_builder.R027_BACKLOG_JSON_REL,
    COMPLETION_ROLE: review_builder.COMPLETION_RECEIPT_REL,
}
CHANGED_ROLES = sorted(CANONICAL_PATH_BY_ROLE)
PRODUCED_ROLES = ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"]
FORBIDDEN_CHANGED_ROLES = {
    "POLICY_BASELINE",
    "ARTIFACT_APPLICATION_RECEIPT",
    "PLANNED_TEST_CASES",
}
PHYSICAL_NONCANONICAL_PATHS = (
    artifact_builder.IMPLEMENTATION_MANIFEST_REL,
    r016_builder.R016_LEDGER_REL,
    r016_builder.R016_EVIDENCE_REL,
    r016_builder.R016_RECEIPT_REL,
)
LANE_LOG_PATHS = tuple(
    trace.CORRECTION_DIR_REL / "logs" / f"{lane.lane_id.lower()}.log"
    for lane in trace.LANES
)
NON_JSON_PRODUCTION_PATHS = {
    *LANE_LOG_PATHS,
    gap_builder.R027_GAP_MD_REL,
    gap_builder.R027_BACKLOG_MD_REL,
    *(
        path
        for path in review_builder.IMMUTABLE_PREDECESSOR_HISTORY_PATHS
        if path.suffix in {".log", ".md"}
    ),
}
PINNED_PRODUCTION_PATHS = (
    trace.V2_OBSERVATION_MANIFEST_REL,
    *LANE_LOG_PATHS,
    *(lane.receipt_rel for lane in trace.LANES),
    trace.V2_IMPLEMENTATION_REL,
    trace.V2_VERIFICATION_REL,
    trace.V2_SUCCESSOR_REL,
    trace.V2_REVIEW_SUBJECT_REL,
    review_builder.R001_REVIEW_RESULT_REL,
    *review_builder.R002_REVIEW_HISTORY_PATHS,
    *review_builder.IMMUTABLE_PREDECESSOR_HISTORY_PATHS,
    gap_builder.R027_GAP_JSON_REL,
    gap_builder.R027_GAP_MD_REL,
    gap_builder.R027_BACKLOG_JSON_REL,
    gap_builder.R027_BACKLOG_MD_REL,
    *artifact_builder.OUTPUT_PATHS,
    *r016_builder.OUTPUT_PATHS,
)
REVIEW_DYNAMIC_PATHS = (
    review_builder.REVIEW_ASSIGNMENT_REL,
    review_builder.REVIEW_RESULT_REL,
    review_builder.INDEPENDENT_REVIEW_REL,
    review_builder.COMPLETION_RECEIPT_REL,
    r004_review.REVIEW_ASSIGNMENT_REL,
    r005_review.REVIEW_ASSIGNMENT_REL,
    r006_review.REVIEW_ASSIGNMENT_REL,
    r007_review.REVIEW_ASSIGNMENT_REL,
    *r008_review.REVIEW_PATHS,
    *r009_review.REVIEW_PATHS,
    *r010_review.REVIEW_PATHS,
    *r011_review.REVIEW_PATHS,
)
FINAL_READY_FRONTIER = [FOCUS_GOAL_ID, PARENT_GOAL_ID, EPIC12_GOAL_ID]
NPC_SUBJECT_ID = "NPC-SINGLE-ADMIN-RECOVERY"
DLV_SUBJECT_IDS = sorted(
    f"DLV-{code}" for code in artifact_builder.TARGET_ARTIFACT_PATHS
)
CHANGED_SUBJECT_IDS_BY_ROLE = {
    "ARTIFACT_CHANGE_LOG": DLV_SUBJECT_IDS,
    "ARTIFACT_REGISTER": DLV_SUBJECT_IDS,
    "REQUIREMENTS_TRACEABILITY": [NPC_SUBJECT_ID],
    "DESIGN_TRACEABILITY": [NPC_SUBJECT_ID],
    "MODULE_REGISTER": [NPC_SUBJECT_ID],
    "IMPLEMENTATION_GAP": ["GAP-008", NPC_SUBJECT_ID],
    "IMPLEMENTATION_BACKLOG": [NPC_SUBJECT_ID],
}
PRODUCER_SUBJECT_IDS_BY_ROLE = {
    "IMPLEMENTATION_BACKLOG": [NPC_SUBJECT_ID],
    "IMPLEMENTATION_GAP": ["GAP-008", NPC_SUBJECT_ID],
}

BuildError = trace.BuildError
require = trace.require
bytes_sha256 = trace.bytes_sha256
CompletionApplyError = fp046_apply.CompletionApplyError
CompletionPostCommitError = fp046_apply.CompletionPostCommitError
atomic_write = fp046_apply.atomic_write
_safe_file = fp046_apply._safe_file


@dataclass(frozen=True)
class CompletionEvidence:
    documents_by_path: Mapping[Path, Mapping[str, Any]]
    bindings_by_role: Mapping[str, Mapping[str, Any]]
    update_occurred_at: str
    completion_occurred_at: str
    physical_sha256_by_path: Mapping[Path, str]
    final_managed_sha256_by_path: Mapping[Path, str]
    production_sha256_by_path: Mapping[Path, str] | None = None


@dataclass(frozen=True)
class PreparedProjection:
    root: Path
    checkpoint_path: Path
    source_bytes: bytes
    projected: dict[str, Any]
    projected_bytes: bytes
    update_event: dict[str, Any]
    completion_event: dict[str, Any]
    evidence: CompletionEvidence
    production_sha256_by_path: Mapping[Path, str]
    sequence_sha256_by_path: Mapping[Path, str]
    catalog_source_universe: tuple[str, ...]
    candidate_catalog_bytes_by_path: Mapping[Path, bytes]
    catalog_physical_verified: bool
    test_only_pin_override_used: bool


RetainedPhysicalPin = fp046_apply.RetainedPhysicalPin
PhysicalPinCohort = fp046_apply.PhysicalPinCohort


def require_resolved_production_pins(pins: Mapping[Path, str]) -> None:
    require(set(pins) == set(PINNED_PRODUCTION_PATHS), "production output pin inventory differs")
    for path, digest in pins.items():
        require(
            type(digest) is str
            and trace.SHA256_RE.fullmatch(digest) is not None,
            f"invalid production pin: {path}",
        )


def _production_pins_from_review_scope(
    review_result: Mapping[str, Any],
) -> dict[Path, str]:
    scope = review_result.get("review_scope")
    require(type(scope) is dict, "reviewed production pin scope is missing")
    evidence = scope.get("reviewed_evidence_manifest")
    controls = scope.get("reviewed_control_code_cohort")
    require(
        type(evidence) is list
        and type(controls) is list
        and scope.get("reviewed_evidence_manifest_sha256")
        == trace.object_sha256(evidence)
        and scope.get("reviewed_control_code_cohort_sha256")
        == trace.object_sha256(controls),
        "reviewed production pin authority seal differs",
    )
    wanted = set(PINNED_PRODUCTION_PATHS)
    selected: dict[Path, str] = {}
    occurrence_count: dict[Path, int] = {}
    for rows, exact_fields, label in (
        (evidence, {"path", "sha256"}, "reviewed evidence manifest"),
        (
            controls,
            {"path", "sha256", "byte_length"},
            "reviewed control-code cohort",
        ),
    ):
        for row in rows:
            require(
                type(row) is dict and set(row) == exact_fields,
                f"{label} row fields differ",
            )
            path_text = row.get("path")
            digest = row.get("sha256")
            require(
                type(path_text) is str
                and Path(path_text).as_posix() == path_text
                and not Path(path_text).is_absolute()
                and "." not in Path(path_text).parts
                and ".." not in Path(path_text).parts
                and type(digest) is str
                and trace.SHA256_RE.fullmatch(digest) is not None,
                f"{label} row content differs",
            )
            relative = Path(path_text)
            if relative not in wanted:
                continue
            occurrence_count[relative] = occurrence_count.get(relative, 0) + 1
            require(
                relative not in selected,
                f"reviewed production pin is duplicated: {relative}",
            )
            selected[relative] = digest
    require(
        set(selected) == wanted
        and all(occurrence_count.get(path) == 1 for path in wanted),
        "reviewed production pin inventory differs",
    )
    require_resolved_production_pins(selected)
    return selected


def _require_pinned_production_hashes(
    raw_by_path: Mapping[Path, bytes],
    pins: Mapping[Path, str],
) -> None:
    require(
        set(PINNED_PRODUCTION_PATHS) <= set(raw_by_path),
        "pinned production bytes are missing",
    )
    for path in PINNED_PRODUCTION_PATHS:
        require(
            bytes_sha256(raw_by_path[path]) == pins[path],
            f"production output SHA-256 differs: {path}",
        )


def _read_safe_bytes(root: Path, relative: Path) -> bytes:
    return _safe_file(root, relative).read_bytes()


def _merge_sha256_binding(
    target: dict[Path, str],
    relative: Path,
    digest: str,
    *,
    label: str,
) -> None:
    existing = target.get(relative)
    require(
        existing in {None, digest},
        f"physical SHA-256 authority conflicts: {label}: {relative}",
    )
    target[relative] = digest


def _snapshot_hashes_from_digests(
    sha256_by_path: Mapping[Path, str],
) -> tuple[str, str]:
    normalized = sorted(relative.as_posix() for relative in sha256_by_path)
    path_hash = hashlib.sha256(
        ("\n".join(normalized) + "\n").encode("utf-8")
    ).hexdigest()
    content = hashlib.sha256()
    for relative_text in normalized:
        digest = sha256_by_path[Path(relative_text)]
        require(
            type(digest) is str
            and trace.SHA256_RE.fullmatch(digest) is not None,
            f"snapshot digest differs: {relative_text}",
        )
        content.update(relative_text.encode("utf-8"))
        content.update(b"\0")
        content.update(digest.encode("ascii"))
        content.update(b"\n")
    return path_hash, content.hexdigest()


def require_exact_source(content: bytes, source: Mapping[str, Any]) -> None:
    require(len(content) == SOURCE_CHECKPOINT_BYTE_COUNT, "source checkpoint byte count differs")
    require(
        bytes_sha256(content) == SOURCE_CHECKPOINT_RAW_SHA256,
        "source checkpoint bytes differ",
    )
    state = source.get("goal_execution")
    history = state.get("transition_history") if type(state) is dict else None
    require(source.get("schema_version") == "1.25.0", "source schema differs")
    require(type(history) is list and len(history) == 60, "source is not exact seq60")
    start = history[-1]
    require(
        type(start) is dict
        and start.get("sequence") == trace.EXPECTED_START_EVENT_SEQUENCE
        and start.get("event_id") == trace.EXPECTED_START_EVENT_ID
        and start.get("event_type") == "GOAL_STARTED"
        and start.get("subject_goal_id") == GOAL_ID
        and start.get("from_status") == "READY"
        and start.get("to_status") == "IN_PROGRESS"
        and start.get("event_sha256") == SOURCE_START_EVENT_SHA256
        and contract.event_sha256(start) == SOURCE_START_EVENT_SHA256,
        "source seq60 start authority differs",
    )
    require(
        state.get("transition_history_anchor_sha256") == SOURCE_START_EVENT_SHA256
        and state.get("status_by_goal", {}).get(GOAL_ID) == "IN_PROGRESS"
        and state.get("focus_goal_id") == GOAL_ID,
        "source seq60 runtime authority differs",
    )
    require(
        state.get("pending_producer_completion_goal_id") in {None, ""},
        "source already has a pending producer transaction",
    )
    errors = contract.validate_generic_event_order(history)
    require(not errors, "source history differs: " + "; ".join(errors))


def _source_snapshot_authority(
    root: Path,
    source: Mapping[str, Any],
) -> tuple[dict[Path, str], dict[Path, str]]:
    state_raw = _read_safe_bytes(root, trace.START_GATE_REPOSITORY_STATE_REL)
    require(
        bytes_sha256(state_raw) == trace.EXPECTED_START_GATE_REPOSITORY_STATE_SHA256,
        "seq60 repository-state log SHA-256 differs",
    )
    repository_state = trace.strict_json_bytes(
        state_raw,
        trace.START_GATE_REPOSITORY_STATE_REL.as_posix(),
    )
    require(
        repository_state.get("evidence_type") == "GATE_REPOSITORY_STATE"
        and repository_state.get("gate_event_id") == trace.EXPECTED_START_EVENT_ID,
        "seq60 repository-state identity differs",
    )
    repository = repository_state.get("repository")
    gate_head = repository.get("head_commit") if type(repository) is dict else None
    require(
        type(gate_head) is str
        and re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", gate_head) is not None
        and contract.current_head(root) == gate_head,
        "seq60 repository HEAD authority differs",
    )
    snapshot = source.get("working_tree_snapshot")
    controlled = repository_state.get("checkpoint_controlled_working_snapshot")
    require(
        type(snapshot) is dict and type(controlled) is dict,
        "seq60 controlled snapshot authority is missing",
    )
    for field in (
        "managed_changed_path_count",
        "path_set_sha256",
        "content_set_sha256",
    ):
        require(
            snapshot.get(field) == controlled.get(field),
            f"seq60 controlled snapshot {field} differs",
        )
    start = source["goal_execution"]["transition_history"][-1]
    event_snapshot = start.get("repository_snapshot_before")
    require(
        type(event_snapshot) is dict
        and event_snapshot.get("gate_event_id") == trace.EXPECTED_START_EVENT_ID
        and event_snapshot.get("gate_repository_state_output_sha256")
        == trace.EXPECTED_START_GATE_REPOSITORY_STATE_SHA256
        and event_snapshot.get("checkpoint_managed_path_count")
        == snapshot.get("managed_changed_path_count")
        and event_snapshot.get("checkpoint_path_set_sha256")
        == snapshot.get("path_set_sha256")
        and event_snapshot.get("checkpoint_content_set_sha256")
        == snapshot.get("content_set_sha256"),
        "seq60 event repository-state projection differs",
    )

    dirty = repository_state.get("dirty_snapshot")
    rows = dirty.get("paths") if type(dirty) is dict else None
    require(
        type(rows) is list and dirty.get("dirty_path_count") == len(rows),
        "seq60 dirty snapshot inventory differs",
    )
    current_by_path: dict[str, Mapping[str, Any]] = {}
    for row in rows:
        require(type(row) is dict, "seq60 dirty snapshot row is malformed")
        if row.get("path_role") != "CURRENT":
            continue
        path_text = row.get("path")
        require(
            type(path_text) is str and path_text not in current_by_path,
            "seq60 dirty current path is invalid or duplicated",
        )
        current_by_path[path_text] = row

    source_paths = snapshot.get("managed_changed_paths")
    require(
        type(source_paths) is list
        and source_paths == sorted(set(source_paths))
        and len(source_paths) == snapshot.get("managed_changed_path_count"),
        "seq60 managed path inventory differs",
    )
    source_sha256_by_path: dict[Path, str] = {}
    for path_text in source_paths:
        require(type(path_text) is str, "seq60 managed path is malformed")
        relative = Path(path_text)
        require(
            not relative.is_absolute()
            and relative.parts
            and "." not in relative.parts
            and ".." not in relative.parts,
            f"seq60 managed path is unsafe: {path_text}",
        )
        row = current_by_path.get(path_text)
        if row is None:
            historical = git_utility._run_git_bytes(
                root,
                ["show", f"{gate_head}:{path_text}"],
                accepted_returncodes=(0, 128),
            )
            digest = (
                bytes_sha256(historical.stdout)
                if historical.returncode == 0
                else bytes_sha256(_read_safe_bytes(root, relative))
            )
        else:
            worktree = row.get("worktree")
            digest = worktree.get("sha256") if type(worktree) is dict else None
            require(
                type(worktree) is dict
                and worktree.get("state") == "PRESENT"
                and worktree.get("type") == "REGULAR_FILE"
                and type(digest) is str
                and trace.SHA256_RE.fullmatch(digest) is not None,
                f"seq60 managed source bytes are not bound: {path_text}",
            )
        source_sha256_by_path[relative] = digest
    require(
        _snapshot_hashes_from_digests(source_sha256_by_path)
        == (snapshot.get("path_set_sha256"), snapshot.get("content_set_sha256")),
        "seq60 managed source aggregate cannot be reconstructed",
    )
    return source_sha256_by_path, {
        trace.GOAL_REL: GOAL_SHA256,
        trace.START_GATE_RECEIPT_REL: trace.EXPECTED_START_GATE_RECEIPT_SHA256,
        trace.START_GATE_REPOSITORY_STATE_REL: (
            trace.EXPECTED_START_GATE_REPOSITORY_STATE_SHA256
        ),
    }


def _implementation_source_authority(
    root: Path,
    implementation: Mapping[str, Any],
) -> dict[Path, str]:
    manifest = implementation.get("final_content_manifest")
    rows = manifest.get("files") if type(manifest) is dict else None
    require(type(rows) is list and bool(rows), "implementation final content manifest missing")
    result: dict[Path, str] = {}
    normalized_rows: list[dict[str, str]] = []
    for row in rows:
        require(type(row) is dict, "implementation final content row is malformed")
        path_text = row.get("path")
        digest = row.get("sha256")
        byte_count = row.get("byte_count")
        require(
            type(path_text) is str
            and type(digest) is str
            and trace.SHA256_RE.fullmatch(digest) is not None
            and type(byte_count) is int
            and byte_count >= 0,
            "implementation final content binding differs",
        )
        relative = Path(path_text)
        require(relative not in result, f"implementation source path duplicated: {relative}")
        raw = _read_safe_bytes(root, relative)
        require(
            len(raw) == byte_count and bytes_sha256(raw) == digest,
            f"implementation source bytes differ: {relative}",
        )
        result[relative] = digest
        normalized_rows.append({"path": path_text, "sha256": digest})
    ordered_paths = [relative.as_posix() for relative in result]
    require(ordered_paths == sorted(ordered_paths), "implementation source paths are not sorted")
    require(
        manifest.get("file_count") == len(rows)
        and manifest.get("path_set_sha256") == trace.object_sha256(ordered_paths)
        and manifest.get("content_set_sha256") == trace.object_sha256(normalized_rows),
        "implementation final content aggregate differs",
    )
    sealed = copy.deepcopy(dict(manifest))
    observed_seal = sealed.pop("manifest_content_sha256", None)
    require(
        observed_seal == trace.object_sha256(sealed),
        "implementation final content manifest seal differs",
    )
    return result


def _git_visible_managed_paths(root: Path) -> set[Path]:
    raw = git_utility._run_git_bytes(
        root,
        list(git_utility.GIT_STATUS_PORCELAIN_V2_COMMAND[1:]),
        config_overrides=git_utility.GIT_STATUS_CONFIG_OVERRIDES,
    ).stdout
    records = git_utility.parse_git_status_porcelain_v2(raw)
    result: set[Path] = set()
    gate_prefix = "docs/control/execution/goal-gates/"
    staging_prefix = f"docs/control/.{CHECKPOINT_REL.name}."
    for record in records:
        for entry in record["paths"]:
            path_text = entry["path"]
            if (
                path_text == CHECKPOINT_REL.as_posix()
                or path_text.startswith(gate_prefix)
                or (
                    path_text.startswith(staging_prefix)
                    and path_text.endswith(".tmp")
                )
            ):
                continue
            relative = Path(path_text)
            _safe_file(root, relative)
            result.add(relative)
    return result


def _require_git_visible_changes_are_managed(
    root: Path,
    final_managed_sha256_by_path: Mapping[Path, str],
    *,
    changed_path_loader: Callable[[Path], set[Path]] = _git_visible_managed_paths,
) -> None:
    unmanaged = sorted(
        changed_path_loader(root) - set(final_managed_sha256_by_path),
        key=Path.as_posix,
    )
    require(
        not unmanaged,
        "Git-visible path is outside the final managed closure: "
        + ", ".join(path.as_posix() for path in unmanaged[:4]),
    )


def _auxiliary_final_paths(
    root: Path,
    *,
    changed_path_loader: Callable[[Path], set[Path]] = _git_visible_managed_paths,
) -> set[Path]:
    changed = changed_path_loader(root)
    selected = {
        path
        for path in changed
        if path in AUXILIARY_FINAL_PATHS
        or DAYLOG_PATH_RE.fullmatch(path.as_posix()) is not None
    }
    for path in selected:
        _safe_file(root, path)
    return selected


def _validate_r016_relation(
    root: Path,
    raw_by_path: Mapping[Path, bytes],
    documents: Mapping[Path, Mapping[str, Any]],
) -> None:
    del root
    required_documents = {
        *r016_builder.PREDECESSOR_PATHS,
        *r016_builder.OUTPUT_PATHS,
    }
    required_raw = {
        *required_documents,
        trace.V2_IMPLEMENTATION_REL,
        trace.V2_VERIFICATION_REL,
        gap_builder.R027_GAP_JSON_REL,
        *(path for _, path in r016_builder.TARGET_ARTIFACT_PATHS),
    }
    require(
        required_raw <= set(raw_by_path)
        and required_documents <= set(documents),
        "R016 relation inventory differs",
    )
    predecessor_documents = {
        path: documents[path] for path in r016_builder.PREDECESSOR_PATHS
    }
    predecessor_raw = {
        path: raw_by_path[path] for path in r016_builder.PREDECESSOR_PATHS
    }
    r016_builder.validate_predecessor_packet(
        predecessor_documents,
        predecessor_raw,
    )
    for path in r016_builder.OUTPUT_PATHS:
        require(
            raw_by_path[path] == trace.json_text(documents[path]).encode("utf-8"),
            f"R016 output JSON is noncanonical: {path}",
        )
        r016_builder.r015_builder.r014_builder.verify_nonself(documents[path], path)
    expected_sources = r016_builder._source_bindings(
        raw_by_path[trace.V2_IMPLEMENTATION_REL],
        raw_by_path[trace.V2_VERIFICATION_REL],
        raw_by_path[gap_builder.R027_GAP_JSON_REL],
        {path: raw_by_path[path] for _, path in r016_builder.TARGET_ARTIFACT_PATHS},
    )
    for path in r016_builder.OUTPUT_PATHS:
        require(
            documents[path].get("r016_source_bindings", documents[path].get("source_bindings"))
            == expected_sources,
            f"R016 exact-six source relation differs: {path}",
        )
    expected_predecessors = [
        r016_builder._binding(
            f"R016-PRE-{index:03d}",
            path,
            raw_by_path[path],
            f"R015_{role}",
        )
        for index, (path, role) in enumerate(
            zip(
                r016_builder.PREDECESSOR_PATHS,
                ("LEDGER", "EVIDENCE", "RECEIPT"),
                strict=True,
            ),
            start=1,
        )
    ]
    for path in r016_builder.OUTPUT_PATHS:
        require(
            documents[path].get(
                "r016_predecessor_packet_bindings",
                documents[path].get("predecessor_packet_bindings"),
            )
            == expected_predecessors,
            f"R016 predecessor relation differs: {path}",
        )

    ledger = documents[r016_builder.R016_LEDGER_REL]
    evidence = documents[r016_builder.R016_EVIDENCE_REL]
    receipt = documents[r016_builder.R016_RECEIPT_REL]
    application = ledger.get(
        "r016_npc_single_admin_recovery_gap008_r027_exact6_v2_correction_application"
    )
    require(
        ledger.get("schema_version")
        == "walksafe.phase1-exact257-successor-ledger.v16"
        and ledger.get("ledger_id")
        == "WS-PHASE1-EXACT257-SUCCESSOR-LEDGER-20260813-R016"
        and ledger.get("prepared_on") == r016_builder.PREPARED_ON
        and type(application) is dict
        and application.get("verdict") == r016_builder.VERDICT
        and application.get("record_count") == 257
        and application.get("record_order_preserved") is True
        and application.get("unchanged_record_count") == 251
        and application.get("progress_binding_record_count") == 6
        and application.get("target_artifact_ids")
        == list(r016_builder.TARGET_ARTIFACT_IDS)
        and application.get("summary_delta_count") == 0
        and application.get("queue_route_delta_count") == 0
        and application.get("authorization_delta_count") == 0
        and application.get("status_delta_count") == 0
        and application.get("credit_delta_count") == 0
        and application.get("formal_test_status") == "NOT_RUN"
        and application.get("actual_device_status") == "NOT_RUN"
        and application.get("actual_recovery_drill_status") == "NOT_RUN"
        and application.get("external_evidence_status") == "NOT_RUN"
        and application.get("production_deployment_status") == "NOT_RUN"
        and application.get("release_status") == "NOT_ELIGIBLE"
        and application.get("zero_credits") == r016_builder.ZERO_CREDITS,
        "R016 ledger progress-only relation differs",
    )
    r016_builder._verify_record_delta(
        predecessor_documents[r016_builder.R015_LEDGER_REL],
        ledger,
        expected_sources,
    )
    require(
        evidence.get("schema_version")
        == "walksafe.phase1-exact257-successor-evidence.v16"
        and evidence.get("packet_id")
        == "WS-PHASE1-EXACT257-SUCCESSOR-EVIDENCE-20260813-R016"
        and evidence.get("prepared_on") == r016_builder.PREPARED_ON
        and evidence.get("verdict") == r016_builder.VERDICT
        and evidence.get("row_delta")
        == {
            "record_count": 257,
            "record_order_preserved": True,
            "unchanged_record_count": 251,
            "progress_binding_record_count": 6,
            "target_artifact_ids": list(r016_builder.TARGET_ARTIFACT_IDS),
            "other_record_delta_count": 0,
            "status_delta_count": 0,
            "credit_delta_count": 0,
        }
        and evidence.get("preserved_invariants")
        == {
            "summaries_deep_equal": True,
            "authorization_boundary_deep_equal": True,
            "all_queue_routes_deep_equal": True,
            "zero_credits": r016_builder.ZERO_CREDITS,
        }
        and evidence.get("formal_device_external_release_boundary")
        == {
            "formal_test_ids": ["TC-NPC-SINGLE-ADMIN-RECOVERY-01"],
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "actual_recovery_drill_status": "NOT_RUN",
            "external_evidence_status": "NOT_RUN",
            "production_deployment_status": "NOT_RUN",
            "release_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        }
        and evidence.get("ledger_binding")
        == r016_builder._binding(
            "R016-OUT-001",
            r016_builder.R016_LEDGER_REL,
            raw_by_path[r016_builder.R016_LEDGER_REL],
            "R016_FULL_EXACT257_LEDGER",
        ),
        "R016 evidence relation differs",
    )
    summary = receipt.get("summary")
    require(
        receipt.get("schema_version")
        == "walksafe.phase1-exact257-successor-check-receipt.v16"
        and receipt.get("receipt_id")
        == "WS-PHASE1-EXACT257-SUCCESSOR-CHECK-RECEIPT-20260813-R016"
        and receipt.get("prepared_on") == r016_builder.PREPARED_ON
        and receipt.get("status") == "PASS"
        and receipt.get("verdict") == r016_builder.VERDICT
        and type(summary) is dict
        and summary.get("check_count") == 7
        and summary.get("pass_count") == 7
        and summary.get("fail_count") == 0
        and summary.get("record_count") == 257
        and summary.get("unchanged_record_count") == 251
        and summary.get("progress_binding_record_count") == 6
        and summary.get("status_delta_count") == 0
        and summary.get("credit_delta_count") == 0
        and summary.get("release_status") == "NOT_ELIGIBLE",
        "R016 exact251+6 zero-credit relation differs",
    )
    require(
        receipt.get("output_bindings")
        == [
            r016_builder._binding(
                "R016-OUT-001",
                r016_builder.R016_LEDGER_REL,
                raw_by_path[r016_builder.R016_LEDGER_REL],
                "R016_FULL_EXACT257_LEDGER",
            ),
            r016_builder._binding(
                "R016-OUT-002",
                r016_builder.R016_EVIDENCE_REL,
                raw_by_path[r016_builder.R016_EVIDENCE_REL],
                "R016_NPC_GAP008_R027_EXACT6_V2_CORRECTION_EVIDENCE",
            ),
        ]
        and receipt.get("physical_output_contract")
        == {
            "paths": [path.as_posix() for path in r016_builder.OUTPUT_PATHS],
            "add_only": True,
            "overwrite_allowed": False,
            "checkpoint_or_daylog_publication": False,
        },
        "R016 output binding relation differs",
    )


def _replay_strict_review(
    root: Path,
    raw_by_path: Mapping[Path, bytes],
    *,
    context_preparer: Callable[[Path], Any] = r011_review.prepare_frozen_r003_context,
    review_input_loader: Callable[
        [Path, Any],
        tuple[dict[str, Any], bytes, dict[str, Any], bytes],
    ] = (
        review_builder.load_review_inputs
    ),
    post_review_builder: Callable[
        [Any, Mapping[str, Any], bytes, Mapping[str, Any], bytes],
        Mapping[Path, str],
    ] = (
        review_builder.build_post_review_outputs
    ),
) -> Any:
    context = context_preparer(root)
    assignment, assignment_raw, result, result_raw = review_input_loader(root, context)
    require(
        assignment_raw == raw_by_path[review_builder.REVIEW_ASSIGNMENT_REL],
        "live review assignment bytes changed during replay",
    )
    require(
        result_raw == raw_by_path[review_builder.REVIEW_RESULT_REL],
        "live review result bytes changed during replay",
    )
    rebuilt = post_review_builder(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    require(
        set(rebuilt) == set(review_builder.POST_REVIEW_OUTPUT_PATHS),
        "post-review output inventory differs",
    )
    for path in review_builder.POST_REVIEW_OUTPUT_PATHS:
        require(
            rebuilt[path].encode("utf-8") == raw_by_path[path],
            f"strict post-review rebuild differs: {path}",
        )
    return context


def load_reviewed_production_pins(root: Path) -> dict[Path, str]:
    context = r011_review.prepare_frozen_r003_context(root)
    _assignment, _assignment_raw, result, _result_raw = (
        review_builder.load_review_inputs(root, context)
    )
    require(
        result.get("decision") == "APPROVED",
        "review result does not authorize production pins",
    )
    return _production_pins_from_review_scope(result)


def _binding_for(
    role: str,
    path: Path,
    document: Mapping[str, Any],
    raw: bytes,
) -> dict[str, Any]:
    return {
        "role": role,
        "document_id": trace._document_id(document, path),
        "path": path.as_posix(),
        "file_sha256": bytes_sha256(raw),
    }


def _validate_changed_subject_authority(
    documents: Mapping[Path, Mapping[str, Any]],
) -> None:
    for path in artifact_builder.OUTPUT_PATHS:
        marker = documents[path].get(artifact_builder.MARKER_FIELD)
        require(
            type(marker) is dict
            and marker.get("successor_id") == artifact_builder.SUCCESSOR_ID
            and marker.get("goal_id") == GOAL_ID
            and marker.get("physical_path") == path.as_posix(),
            f"NPC corrected artifact subject authority differs: {path}",
        )
    require(
        DLV_SUBJECT_IDS
        == sorted(f"DLV-{code}" for code in artifact_builder.TARGET_ARTIFACT_PATHS),
        "NPC deliverable subject inventory differs",
    )
    gap = documents[gap_builder.R027_GAP_JSON_REL]
    targets = [
        row
        for row in gap.get("assessments", [])
        if type(row) is dict and row.get("gap_id") == "GAP-008"
    ]
    require(
        len(targets) == 1
        and targets[0].get("source_policy_id") == NPC_SUBJECT_ID
        and type(
            targets[0].get("npc_single_admin_recovery_evidence_correction")
        )
        is dict,
        "NPC Gap subject authority differs",
    )
    backlog = documents[gap_builder.R027_BACKLOG_JSON_REL]
    require(
        type(backlog.get("npc_single_admin_recovery_evidence_correction"))
        is dict,
        "NPC Backlog subject authority differs",
    )


def load_completion_evidence(
    root: Path,
    source: Mapping[str, Any],
    *,
    pins: Mapping[Path, str] | None = None,
    start_authority_validator: Callable[[Path], None] = trace.validate_start_authority,
    source_snapshot_loader: Callable[
        [Path, Mapping[str, Any]], tuple[dict[Path, str], dict[Path, str]]
    ] = _source_snapshot_authority,
    review_context_preparer: Callable[[Path], Any] = r011_review.prepare_frozen_r003_context,
    review_input_loader: Callable[
        [Path, Any],
        tuple[dict[str, Any], bytes, dict[str, Any], bytes],
    ] = (
        review_builder.load_review_inputs
    ),
    post_review_builder: Callable[
        [Any, Mapping[str, Any], bytes, Mapping[str, Any], bytes],
        Mapping[Path, str],
    ] = (
        review_builder.build_post_review_outputs
    ),
    auxiliary_path_loader: Callable[[Path], set[Path]] = _auxiliary_final_paths,
) -> CompletionEvidence:
    if pins is not None:
        require_resolved_production_pins(pins)
    start_authority_validator(root)
    immutable_history_sha256_by_path = (
        review_builder.load_immutable_predecessor_history_sha256_by_path(root)
    )
    raw_by_path = {
        path: _read_safe_bytes(root, path) for path in PINNED_PRODUCTION_PATHS
    }
    raw_by_path.update(
        {path: _read_safe_bytes(root, path) for path in REVIEW_DYNAMIC_PATHS}
    )
    documents = {
        path: trace.strict_json_bytes(raw_by_path[path], path.as_posix())
        for path in (*PINNED_PRODUCTION_PATHS, *REVIEW_DYNAMIC_PATHS)
        if path != trace.V2_OBSERVATION_MANIFEST_REL
        and path not in NON_JSON_PRODUCTION_PATHS
    }
    implementation = documents[trace.V2_IMPLEMENTATION_REL]
    verification = documents[trace.V2_VERIFICATION_REL]
    successor = documents[trace.V2_SUCCESSOR_REL]
    subject = documents[trace.V2_REVIEW_SUBJECT_REL]
    review = documents[review_builder.INDEPENDENT_REVIEW_REL]
    completion = documents[review_builder.COMPLETION_RECEIPT_REL]
    assignment = documents[review_builder.REVIEW_ASSIGNMENT_REL]
    review_result = documents[review_builder.REVIEW_RESULT_REL]
    context = _replay_strict_review(
        root,
        raw_by_path,
        context_preparer=review_context_preparer,
        review_input_loader=review_input_loader,
        post_review_builder=post_review_builder,
    )
    r011_review.validate_followup_authority(root)
    reviewed_pins = _production_pins_from_review_scope(review_result)
    if pins is not None:
        require(
            dict(pins) == reviewed_pins,
            "test-only production pins differ from reviewed authority",
        )
    effective_pins = reviewed_pins if pins is None else dict(pins)
    _require_pinned_production_hashes(raw_by_path, effective_pins)
    require(
        all(
            effective_pins[path] == digest
            for path, digest in immutable_history_sha256_by_path.items()
        ),
        "reviewed immutable predecessor history pins differ",
    )
    _validate_changed_subject_authority(documents)
    for document, field, label in (
        (implementation, "implementation_record_content_sha256", "implementation"),
        (verification, "verification_result_content_sha256", "verification"),
        (successor, "successor_trace_content_sha256", "successor"),
        (subject, "review_subject_content_sha256", "review subject"),
    ):
        trace.verify_seal(document, field, label)
    for lane, log_path in zip(trace.LANES, LANE_LOG_PATHS, strict=True):
        receipt = documents[lane.receipt_rel]
        require(
            receipt.get("observation", {}).get("log_path") == log_path.as_posix()
            and receipt.get("raw_output_binding")
            == {
                "path": log_path.as_posix(),
                "byte_length": len(raw_by_path[log_path]),
                "sha256": bytes_sha256(raw_by_path[log_path]),
            },
            f"pinned lane log binding differs: {lane.lane_id}",
        )
    require(review.get("status") == "PASS", "independent internal review did not pass")
    require(
        completion.get("status") == "ACCEPTED"
        and completion.get("result") == "PASS"
        and completion.get("target_goal_id") == GOAL_ID
        and completion.get("target_goal_content_sha256") == GOAL_SHA256
        and completion.get("completion_boundary") == trace.completion_boundary(),
        "completion receipt identity/boundary differs",
    )
    expected_assignment_provenance = {
        "path": review_builder.REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": bytes_sha256(raw_by_path[review_builder.REVIEW_ASSIGNMENT_REL]),
    }
    expected_result_provenance = {
        "path": review_builder.REVIEW_RESULT_REL.as_posix(),
        "sha256": bytes_sha256(raw_by_path[review_builder.REVIEW_RESULT_REL]),
    }
    require(
        review_result.get("decision") == "APPROVED"
        and review.get("assignment_provenance") == expected_assignment_provenance
        and review.get("review_result_provenance") == expected_result_provenance
        and completion.get("review_assignment_provenance")
        == expected_assignment_provenance
        and completion.get("review_result_provenance") == expected_result_provenance,
        "completion review assignment/result provenance differs",
    )
    require(
        completion.get("reviewed_control_code_cohort")
        == list(context.control_code_cohort)
        and completion.get("reviewed_control_code_cohort_sha256")
        == context.control_code_cohort_sha256,
        "completion reviewed control-code cohort differs",
    )
    expected_consumers = [
        {
            "role": role,
            "path": path.as_posix(),
            "document_id": trace._document_id(documents[path], path),
            "sha256": bytes_sha256(raw_by_path[path]),
            "byte_length": len(raw_by_path[path]),
        }
        for role, path in trace.CONSUMER_SPECS
    ]
    require(
        completion.get("downstream_consumer_bindings") == expected_consumers
        and len(expected_consumers) == len(trace.CONSUMER_SPECS),
        "completion exact consumer binding differs",
    )
    require(
        list(context.consumer_bindings) == expected_consumers
        and successor.get("downstream_consumer_bindings") == expected_consumers
        and subject.get("reviewed_consumer_bindings") == expected_consumers
        and assignment.get("review_scope", {}).get("reviewed_consumer_bindings")
        == expected_consumers
        and review_result.get("review_scope", {}).get("reviewed_consumer_bindings")
        == expected_consumers
        and review.get("review_scope", {}).get("reviewed_consumer_bindings")
        == expected_consumers,
        "strict review exact 11-consumer relation differs",
    )
    for path, raw in context.result_raw.items():
        require(raw_by_path[path] == raw, f"strict review live result bytes differ: {path}")
    _validate_r016_relation(
        root,
        raw_by_path,
        documents,
    )
    require(
        completion.get("execution_start_event_sha256") == SOURCE_START_EVENT_SHA256
        and completion.get("execution_start_event")
        == {
            "sequence": trace.EXPECTED_START_EVENT_SEQUENCE,
            "event_id": trace.EXPECTED_START_EVENT_ID,
            "event_sha256": SOURCE_START_EVENT_SHA256,
        }
        and completion.get("implementation_start_gate_binding")
        == {
            "path": trace.START_GATE_RECEIPT_REL.as_posix(),
            "file_sha256": trace.EXPECTED_START_GATE_RECEIPT_SHA256,
        },
        "completion start authority differs",
    )
    generated = trace._parse_time(completion.get("generated_at"), "completion generated_at")
    start_at = trace._parse_time(
        completion.get("execution_window", {}).get("started_at"),
        "completion started_at",
    )
    ended_at = trace._parse_time(
        completion.get("execution_window", {}).get("ended_at"),
        "completion ended_at",
    )
    require(start_at <= ended_at <= generated, "completion chronology differs")
    update_time = max(
        trace._parse_time(
            documents[trace.V2_IMPLEMENTATION_REL].get("observed_at"),
            "implementation observed_at",
        )
        + timedelta(seconds=1),
        generated,
    )
    bindings = {
        role: _binding_for(role, path, documents[path], raw_by_path[path])
        for role, path in CANONICAL_PATH_BY_ROLE.items()
    }
    source_sha256_by_path, start_physical = source_snapshot_loader(root, source)
    implementation_sha256_by_path = _implementation_source_authority(
        root,
        implementation,
    )
    approved_sha256_by_path = dict(implementation_sha256_by_path)
    for path, digest in effective_pins.items():
        _merge_sha256_binding(
            approved_sha256_by_path,
            path,
            digest,
            label="pinned NPC completion output",
        )
    for path in REVIEW_DYNAMIC_PATHS:
        _merge_sha256_binding(
            approved_sha256_by_path,
            path,
            bytes_sha256(raw_by_path[path]),
            label="strictly replayed dynamic review output",
        )
    for path in SEQUENCE_AUTHORITY_PATHS:
        _merge_sha256_binding(
            approved_sha256_by_path,
            path,
            bytes_sha256(_read_safe_bytes(root, path)),
            label="NPC completion sequence authority",
        )
    for path in auxiliary_path_loader(root):
        _merge_sha256_binding(
            approved_sha256_by_path,
            path,
            bytes_sha256(_read_safe_bytes(root, path)),
            label="NPC final catalog, runner, or daylog cohort",
        )
    final_managed_sha256_by_path = dict(source_sha256_by_path)
    final_managed_sha256_by_path.update(approved_sha256_by_path)
    for path, digest in final_managed_sha256_by_path.items():
        require(
            bytes_sha256(_read_safe_bytes(root, path)) == digest,
            f"final managed path differs from approved closure: {path}",
        )
    physical_sha256_by_path = dict(final_managed_sha256_by_path)
    for path, digest in start_physical.items():
        _merge_sha256_binding(
            physical_sha256_by_path,
            path,
            digest,
            label="NPC start authority",
        )
    start_authority_validator(root)
    return CompletionEvidence(
        documents_by_path=documents,
        bindings_by_role=bindings,
        update_occurred_at=update_time.isoformat(),
        completion_occurred_at=(update_time + timedelta(seconds=1)).isoformat(),
        physical_sha256_by_path=physical_sha256_by_path,
        final_managed_sha256_by_path=final_managed_sha256_by_path,
        production_sha256_by_path=effective_pins,
    )


def _project_canonical_bindings(
    source: Mapping[str, Any],
    bindings_by_role: Mapping[str, Mapping[str, Any]],
) -> list[dict[str, Any]]:
    raw = source.get("canonical_bindings")
    require(type(raw) is list, "source canonical bindings missing")
    projected: list[dict[str, Any]] = []
    seen: set[str] = set()
    for source_binding in raw:
        require(type(source_binding) is dict and type(source_binding.get("role")) is str, "source canonical binding malformed")
        role = source_binding["role"]
        require(role not in seen, f"duplicate source canonical role: {role}")
        seen.add(role)
        if role in bindings_by_role:
            value = copy.deepcopy(source_binding)
            value.update(bindings_by_role[role])
            projected.append(value)
        else:
            projected.append(copy.deepcopy(source_binding))
    require(COMPLETION_ROLE not in seen, "NPC completion canonical role already exists")
    completion = dict(bindings_by_role[COMPLETION_ROLE])
    completion.update({"identity_json_path": "/document_id", "mutable": False})
    projected.append(completion)
    require(len(projected) == len(raw) + 1, "canonical binding count delta differs")
    return projected


def _project_gap_snapshot(gap: Mapping[str, Any], backlog: Mapping[str, Any]) -> dict[str, Any]:
    assessments = gap.get("assessments")
    epics = backlog.get("epics")
    require(type(assessments) is list and type(epics) is list, "R027 gap/backlog snapshot source differs")
    epic_status_counts: dict[str, int] = {}
    for epic in epics:
        status = epic.get("current_status")
        epic_status_counts[status] = epic_status_counts.get(status, 0) + 1
    return {
        "report_id": gap["metadata"]["report_id"],
        "report_version": gap["metadata"]["version"],
        "assessment_count": len(assessments),
        "status_counts": copy.deepcopy(gap["summary"]["status_counts"]),
        "backlog_id": backlog["metadata"]["backlog_id"],
        "epic_count": len(epics),
        "epic_status_counts": epic_status_counts,
        "implementation_snapshot": copy.deepcopy(gap["implementation_snapshot"]),
    }


def _project_current_work_from_backlog(
    current_work: dict[str, Any],
    backlog: Mapping[str, Any],
) -> tuple[str, str]:
    action = backlog.get("next_single_action")
    epics = backlog.get("epics")
    require(
        type(action) is dict
        and type(epics) is list
        and action.get("status") == "PLANNED_NEXT"
        and type(action.get("epic_id")) is str
        and type(action.get("source_policy_id")) is str
        and type(action.get("gap_id")) is str
        and type(action.get("work_item_id")) is str
        and type(action.get("action")) is str,
        "R027 next-single-action pointer differs",
    )
    selected = [
        row
        for row in epics
        if type(row) is dict and row.get("epic_id") == action["epic_id"]
    ]
    require(len(selected) == 1, "R027 next-action EPIC aggregate differs")
    epic = selected[0]
    for field in (
        "current_status",
        "deferred_release_gate_ids",
        "gap_ids",
        "source_policy_ids",
        "target_completion_level",
        "title",
    ):
        require(field in epic, f"R027 next-action EPIC field missing: {field}")
    require(
        action["source_policy_id"] in epic["source_policy_ids"]
        and action["gap_id"] in epic["gap_ids"],
        "R027 next action is outside its EPIC aggregate",
    )
    current_focus = (
        f"{action['source_policy_id']}/{action['gap_id']} PLANNED_NEXT; "
        f"{action['epic_id']} canonical Backlog aggregate"
    )
    current_work.update(
        {
            "current_focus": current_focus,
            "deferred_release_gate_ids": copy.deepcopy(
                epic["deferred_release_gate_ids"]
            ),
            "epic_id": f"WS-GOAL-{epic['epic_id']}",
            "gap_ids": copy.deepcopy(epic["gap_ids"]),
            "gap_ids_semantics": (
                "BACKLOG_EPIC_AGGREGATE_NOT_FOCUS_GOAL_COMPLETION_SCOPE"
            ),
            "next_action": action["action"],
            "release_completion_claimed": False,
            "scope_kind": "BACKLOG_EPIC_AGGREGATE",
            "source_policy_ids": copy.deepcopy(epic["source_policy_ids"]),
            "source_policy_ids_semantics": (
                "BACKLOG_EPIC_AGGREGATE_NOT_FOCUS_GOAL_COMPLETION_SCOPE"
            ),
            "status": epic["current_status"],
            "status_scope": "IMPLEMENTATION_BACKLOG_EPIC_STATUS_NOT_GOAL_STATUS",
            "target_completion_level": epic["target_completion_level"],
            "title": epic["title"],
            "work_item_id": action["work_item_id"],
            "work_item_id_semantics": "NEXT_ACTION_POINTER_ONLY",
        }
    )
    return action["action"], current_focus


def _runtime_projection(state: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "focus_goal_id": state["focus_goal_id"],
        "focus_goal_path": state["focus_goal_path"],
        "focus_work_item_id": state["focus_work_item_id"],
        "focus_source": state["focus_source"],
        "ready_frontier_goal_ids": copy.deepcopy(state["ready_frontier_goal_ids"]),
        "blocked_goal_ids": copy.deepcopy(state["blocked_goal_ids"]),
        "pending_questions": copy.deepcopy(state["pending_questions"]),
        "open_question_count": state["open_question_count"],
        "artifact_work_queue_sha256": contract.canonical_json_sha256(state["artifact_work_queue"]),
        "completion_boundary_sha256": contract.canonical_json_sha256(state["completion_boundary"]),
        "activation_status": state["activation_status"],
        "package_status": state["package_status"],
    }


def _update_working_snapshot(
    checkpoint: dict[str, Any],
    evidence: CompletionEvidence,
) -> None:
    snapshot = checkpoint.get("working_tree_snapshot")
    handoff = checkpoint.get("session_handoff")
    require(type(snapshot) is dict, "working snapshot is missing")
    require(type(handoff) is dict, "session handoff is missing")
    mirror = handoff.get("source_commit_or_snapshot")
    require(type(mirror) is dict, "session source snapshot is missing")
    paths = sorted(
        relative.as_posix()
        for relative in evidence.final_managed_sha256_by_path
    )
    path_hash, content_hash = _snapshot_hashes_from_digests(
        evidence.final_managed_sha256_by_path
    )
    snapshot["managed_changed_paths"] = paths
    snapshot["managed_changed_path_count"] = len(paths)
    snapshot["path_set_sha256"] = path_hash
    snapshot["content_set_sha256"] = content_hash
    snapshot["scope"] = FINAL_SCOPE
    handoff["changed_files"] = copy.deepcopy(paths)
    mirror["file_count"] = len(paths)
    mirror["path_set_sha256"] = path_hash
    mirror["content_set_sha256"] = content_hash


def project_seq61_62(
    source: Mapping[str, Any],
    evidence: CompletionEvidence,
    *,
    runtime_deriver: Callable[[dict[str, Any], list[str]], tuple[dict[str, Any], dict[str, Any]]] | None = None,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    checkpoint = copy.deepcopy(dict(source))
    source_state = source["goal_execution"]
    tail = source_state["transition_history"][-1]
    require(
        tail.get("sequence") == 60
        and tail.get("event_id") == trace.EXPECTED_START_EVENT_ID
        and tail.get("event_sha256") == SOURCE_START_EVENT_SHA256
        and source_state.get("status_by_goal", {}).get(GOAL_ID) == "IN_PROGRESS",
        "source is not exact NPC GOAL_STARTED seq60",
    )
    checkpoint["canonical_bindings"] = _project_canonical_bindings(source, evidence.bindings_by_role)
    gap = evidence.documents_by_path[gap_builder.R027_GAP_JSON_REL]
    backlog = evidence.documents_by_path[gap_builder.R027_BACKLOG_JSON_REL]
    checkpoint["implementation_gap_snapshot"] = _project_gap_snapshot(gap, backlog)
    state = checkpoint["goal_execution"]
    if runtime_deriver is not None:
        queue, boundary = runtime_deriver(checkpoint, [GOAL_ID, PARENT_GOAL_ID, EPIC12_GOAL_ID])
        state["artifact_work_queue"] = queue
        state["completion_boundary"] = boundary
    canonical_snapshot = contract.canonical_binding_snapshot(checkpoint)
    completion_binding = dict(evidence.bindings_by_role[COMPLETION_ROLE])
    update_at = evidence.update_occurred_at
    update_event = {
        "sequence": 61,
        "event_id": CANONICAL_UPDATE_EVENT_ID,
        "event_type": "CANONICAL_BINDINGS_UPDATED",
        "occurred_on": datetime.fromisoformat(update_at).date().isoformat(),
        "occurred_at": update_at,
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": GOAL_ID,
        "focus_goal_content_sha256": GOAL_SHA256,
        "from_status": "IN_PROGRESS",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": _runtime_projection(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [row["resolution_id"] for row in state["blocker_resolution_history"]],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": list(CHANGED_ROLES),
        "previous_event_sha256": SOURCE_START_EVENT_SHA256,
        "produced_by_goal_id": GOAL_ID,
        "produced_binding_roles": list(PRODUCED_ROLES),
        "producer_completion_receipt_binding": completion_binding,
        "changed_binding_roles": list(CHANGED_ROLES),
        "changed_subject_ids_by_role": copy.deepcopy(
            CHANGED_SUBJECT_IDS_BY_ROLE
        ),
        "producer_output_subject_ids_by_role": copy.deepcopy(
            PRODUCER_SUBJECT_IDS_BY_ROLE
        ),
        "impact_closure_goal_ids": [PARENT_GOAL_ID],
        "impact_disposition_by_goal": {
            PARENT_GOAL_ID: {"result": "REVALIDATION_REFRESH_REQUIRED", "target_status": "READY"}
        },
        "reopened_completion_event_sha256_by_goal": {},
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_snapshot),
    }
    update_event["event_sha256"] = contract.event_sha256(update_event)
    state["transition_history"].append(update_event)
    state["pending_producer_completion_goal_id"] = GOAL_ID

    state["status_by_goal"][GOAL_ID] = "COMPLETE_AT_TARGET"
    state["focus_goal_id"] = FOCUS_GOAL_ID
    state["focus_goal_path"] = FOCUS_GOAL_PATH.as_posix()
    state["focus_work_item_id"] = ""
    state["focus_source"] = "WORKSTREAM_GRAPH"
    state["ready_frontier_goal_ids"] = copy.deepcopy(FINAL_READY_FRONTIER)
    if runtime_deriver is not None:
        queue, boundary = runtime_deriver(checkpoint, state["ready_frontier_goal_ids"])
        state["artifact_work_queue"] = queue
        state["completion_boundary"] = boundary
    completion_evidence = copy.deepcopy(state["completion_evidence_by_goal"])
    completion_evidence[GOAL_ID] = [COMPLETION_ROLE]
    completion_at = evidence.completion_occurred_at
    completion_event = {
        "sequence": 62,
        "event_id": COMPLETION_EVENT_ID,
        "event_type": "GOAL_COMPLETED",
        "occurred_on": datetime.fromisoformat(completion_at).date().isoformat(),
        "occurred_at": completion_at,
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": FOCUS_GOAL_ID,
        "focus_goal_content_sha256": FOCUS_GOAL_SHA256,
        "subject_goal_id": GOAL_ID,
        "from_status": "IN_PROGRESS",
        "to_status": "COMPLETE_AT_TARGET",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {GOAL_ID: "COMPLETE_AT_TARGET"},
        "runtime_after": _runtime_projection(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [row["resolution_id"] for row in state["blocker_resolution_history"]],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [COMPLETION_ROLE],
        "previous_event_sha256": update_event["event_sha256"],
        "canonical_update_event_sha256": update_event["event_sha256"],
        "completion_receipt_binding": completion_binding,
        "completion_evidence_bindings": {COMPLETION_ROLE: completion_binding},
        "completion_evidence_by_goal_after": completion_evidence,
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_snapshot),
    }
    completion_event["event_sha256"] = contract.event_sha256(completion_event)
    state["transition_history"].append(completion_event)
    state["transition_history_anchor_sha256"] = completion_event["event_sha256"]
    state["validation_cutoff_at"] = completion_at
    state["completion_evidence_by_goal"] = completion_evidence
    state["pending_producer_completion_goal_id"] = ""
    checkpoint["current_work"]["last_completed_work_summary"] = (
        "NPC-SINGLE-ADMIN-RECOVERY/GAP-008 repository-internal implementation, regression, review and successor evidence completed"
    )
    next_action, _current_focus = _project_current_work_from_backlog(
        checkpoint["current_work"],
        backlog,
    )
    checkpoint["session_handoff"]["current_epic"] = (
        "EPIC-04 / FP-022/GAP-031 PLANNED_NEXT"
    )
    checkpoint["session_handoff"]["next_single_action"] = next_action
    checkpoint["session_handoff"]["last_updated_by_work_item"] = trace.WORK_ITEM_ID
    checkpoint["session_handoff"]["last_verification_status"] = (
        "PASS_INTERNAL_ONLY_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
    )
    _update_working_snapshot(checkpoint, evidence)
    return checkpoint, update_event, completion_event


def _require_only_allowed_changes(
    before: Mapping[str, Any],
    after: Mapping[str, Any],
    allowed: set[str],
    *,
    label: str,
) -> None:
    for key in set(before) | set(after):
        if key not in allowed:
            require(before.get(key) == after.get(key), f"unauthorized {label} mutation: {key}")


def validate_projection(
    source: Mapping[str, Any],
    projected: Mapping[str, Any],
    evidence: CompletionEvidence,
) -> None:
    before_state = source["goal_execution"]
    state = projected["goal_execution"]
    history = state["transition_history"]
    require(
        len(history) == len(before_state["transition_history"]) + 2
        and history[:-2] == before_state["transition_history"],
        "history before seq61 changed",
    )
    update, completion = history[-2:]
    require(update["sequence"] == 61 and completion["sequence"] == 62, "seq61/62 adjacency differs")
    require(update["event_sha256"] == contract.event_sha256(update), "seq61 seal differs")
    require(completion["event_sha256"] == contract.event_sha256(completion), "seq62 seal differs")
    require(
        completion["previous_event_sha256"] == update["event_sha256"]
        and completion["canonical_update_event_sha256"] == update["event_sha256"],
        "event inserted between seq61/62",
    )
    require(update["changed_binding_roles"] == CHANGED_ROLES, "seq61 changed roles differ")
    require(update["evidence_refs"] == CHANGED_ROLES, "seq61 evidence roles differ")
    require(update["produced_binding_roles"] == PRODUCED_ROLES, "seq61 producer roles differ")
    require(
        update.get("changed_subject_ids_by_role")
        == CHANGED_SUBJECT_IDS_BY_ROLE,
        "seq61 changed subject IDs differ from canonical delta",
    )
    require(
        update.get("producer_output_subject_ids_by_role")
        == PRODUCER_SUBJECT_IDS_BY_ROLE,
        "seq61 producer subject IDs differ",
    )
    require(not (set(CHANGED_ROLES) & FORBIDDEN_CHANGED_ROLES), "forbidden canonical role changed")
    before_bindings = contract.canonical_binding_snapshot(source)
    after_bindings = contract.canonical_binding_snapshot(projected)
    changed = sorted(role for role in set(before_bindings) | set(after_bindings) if before_bindings.get(role) != after_bindings.get(role))
    require(changed == CHANGED_ROLES, "physical canonical role delta differs")
    require(
        after_bindings["PLANNED_TEST_CASES"] == before_bindings["PLANNED_TEST_CASES"],
        "PLANNED_TEST_CASES changed",
    )
    require(
        artifact_builder.IMPLEMENTATION_MANIFEST_REL.as_posix()
        not in {binding["path"] for binding in projected["canonical_bindings"]},
        "DEV-01 was promoted to a checkpoint canonical role",
    )
    expected_statuses = copy.deepcopy(before_state["status_by_goal"])
    expected_statuses[GOAL_ID] = "COMPLETE_AT_TARGET"
    require(state["status_by_goal"] == expected_statuses, "NPC final status map differs")
    require(
        state["focus_goal_id"] == FOCUS_GOAL_ID
        and state["focus_goal_path"] == FOCUS_GOAL_PATH.as_posix()
        and state["focus_work_item_id"] == ""
        and state["focus_source"] == "WORKSTREAM_GRAPH"
        and state["ready_frontier_goal_ids"] == FINAL_READY_FRONTIER
        and state.get("pending_producer_completion_goal_id") in {None, ""},
        "final focus/frontier/producer state differs",
    )
    current_work = projected.get("current_work")
    expected_current_work = copy.deepcopy(source["current_work"])
    expected_current_work["last_completed_work_summary"] = (
        "NPC-SINGLE-ADMIN-RECOVERY/GAP-008 repository-internal implementation, regression, review and successor evidence completed"
    )
    expected_next_action, _ = _project_current_work_from_backlog(
        expected_current_work,
        evidence.documents_by_path[gap_builder.R027_BACKLOG_JSON_REL],
    )
    require(
        type(current_work) is dict
        and current_work == expected_current_work
        and projected.get("session_handoff", {}).get("next_single_action")
        == expected_next_action,
        "final canonical Backlog current-work aggregate differs",
    )
    require(
        state.get("completion_evidence_by_goal", {}).get(GOAL_ID)
        == [COMPLETION_ROLE]
        and completion.get("completion_evidence_by_goal_after")
        == state.get("completion_evidence_by_goal"),
        "NPC completion evidence projection differs",
    )
    expected_paths = sorted(
        path.as_posix() for path in evidence.final_managed_sha256_by_path
    )
    expected_path_hash, expected_content_hash = _snapshot_hashes_from_digests(
        evidence.final_managed_sha256_by_path
    )
    working = projected.get("working_tree_snapshot")
    handoff = projected.get("session_handoff")
    mirror = handoff.get("source_commit_or_snapshot") if type(handoff) is dict else None
    require(
        type(working) is dict
        and type(handoff) is dict
        and type(mirror) is dict
        and working.get("managed_changed_paths") == expected_paths
        and working.get("managed_changed_path_count") == len(expected_paths)
        and working.get("path_set_sha256") == expected_path_hash
        and working.get("content_set_sha256") == expected_content_hash
        and working.get("scope") == FINAL_SCOPE
        and handoff.get("changed_files") == expected_paths
        and mirror.get("file_count") == len(expected_paths)
        and mirror.get("path_set_sha256") == expected_path_hash
        and mirror.get("content_set_sha256") == expected_content_hash,
        "final working snapshot/handoff binding differs",
    )

    _require_only_allowed_changes(
        source,
        projected,
        {
            "canonical_bindings",
            "current_work",
            "goal_execution",
            "implementation_gap_snapshot",
            "session_handoff",
            "working_tree_snapshot",
        },
        label="top-level checkpoint",
    )
    _require_only_allowed_changes(
        before_state,
        state,
        {
            "artifact_work_queue",
            "completion_boundary",
            "completion_evidence_by_goal",
            "focus_goal_id",
            "focus_goal_path",
            "focus_source",
            "focus_work_item_id",
            "pending_producer_completion_goal_id",
            "ready_frontier_goal_ids",
            "status_by_goal",
            "transition_history",
            "transition_history_anchor_sha256",
            "validation_cutoff_at",
        },
        label="goal-execution",
    )
    _require_only_allowed_changes(
        source["current_work"],
        projected["current_work"],
        {
            "deferred_release_gate_ids",
            "epic_id",
            "gap_ids",
            "gap_ids_semantics",
            "last_completed_work_summary",
            "current_focus",
            "next_action",
            "release_completion_claimed",
            "scope_kind",
            "source_policy_ids",
            "source_policy_ids_semantics",
            "status",
            "status_scope",
            "title",
            "work_item_id",
            "work_item_id_semantics",
        },
        label="current-work",
    )
    _require_only_allowed_changes(
        source["session_handoff"],
        projected["session_handoff"],
        {
            "changed_files",
            "current_epic",
            "last_updated_by_work_item",
            "last_verification_status",
            "next_single_action",
            "source_commit_or_snapshot",
        },
        label="session-handoff",
    )
    _require_only_allowed_changes(
        source["session_handoff"]["source_commit_or_snapshot"],
        projected["session_handoff"]["source_commit_or_snapshot"],
        {"file_count", "path_set_sha256", "content_set_sha256"},
        label="session snapshot mirror",
    )
    _require_only_allowed_changes(
        source["working_tree_snapshot"],
        projected["working_tree_snapshot"],
        {
            "managed_changed_paths",
            "managed_changed_path_count",
            "path_set_sha256",
            "content_set_sha256",
            "scope",
        },
        label="working snapshot",
    )
    for field in (
        "authority_boundary",
        "verification_boundary",
        "approved_state",
    ):
        require(projected.get(field) == source.get(field), f"{field} credit changed")
    require(
        state.get("standing_execution_authority")
        == before_state.get("standing_execution_authority"),
        "generic POLICY_GAP_WORK authority expanded",
    )
    require(
        projected.get("approved_state", {}).get("release_status") == "NOT_ELIGIBLE"
        and projected.get("verification_boundary", {}).get("formal_test_pass_claimed") is False
        and projected.get("verification_boundary", {}).get("release_eligible") is False,
        "formal/external/device/release credit was promoted",
    )
    errors = contract.validate_generic_event_order(history)
    require(not errors, "generic event order differs: " + "; ".join(errors))


def run_continuation_checker(root: Path, checkpoint_path: Path) -> list[str]:
    return contract.validate(
        root,
        checkpoint_path,
        contract.V23_ARCHIVE_RELATIVE,
        contract.V24_MANIFEST_RELATIVE,
    )


def run_goal_graph_checker(root: Path, checkpoint_path: Path) -> list[str]:
    return goal_graph.validate(
        root,
        checkpoint_path,
        contract.V23_ARCHIVE_RELATIVE,
        contract.V24_MANIFEST_RELATIVE,
        check_continuation=False,
    )


def validate_projected_with_both_checkers(
    root: Path,
    projected_bytes: bytes,
    *,
    continuation_checker: Callable[[Path, Path], list[str]] = run_continuation_checker,
    goal_graph_checker: Callable[[Path, Path], list[str]] = run_goal_graph_checker,
) -> None:
    checkpoint_dir = _safe_file(root, CHECKPOINT_REL).parent
    descriptor, temporary_name = tempfile.mkstemp(
        dir=checkpoint_dir,
        prefix=".walksafe-npc-seq61-62-preflight.",
        suffix=".json",
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(projected_bytes)
            stream.flush()
            os.fsync(stream.fileno())
        relative = temporary.relative_to(root)
        continuation_errors = continuation_checker(root, relative)
        require(
            not continuation_errors,
            "projected continuation v2.4 check failed: "
            + "; ".join(continuation_errors),
        )
        graph_errors = goal_graph_checker(root, relative)
        require(
            not graph_errors,
            "projected goal-graph v2.4 check failed: "
            + "; ".join(graph_errors),
        )
    finally:
        temporary.unlink(missing_ok=True)


def _require_physical_matches(
    root: Path,
    sha256_by_path: Mapping[Path, str],
) -> None:
    for path, digest in sha256_by_path.items():
        require(
            bytes_sha256(_read_safe_bytes(root, path)) == digest,
            f"physical evidence changed: {path}",
        )


def _build_candidate_catalog_bytes(
    root: Path,
    source_universe: tuple[str, ...],
    checkpoint: Mapping[str, Any],
    *,
    catalog_builder: Callable[..., Mapping[str, bytes]] = (
        repository_catalogs.build_catalog_bytes
    ),
) -> dict[Path, bytes]:
    built = catalog_builder(
        root,
        source_universe,
        checkpoint_override=checkpoint,
    )
    require(
        set(built) == set(repository_catalogs.OUTPUT_PATHS),
        "candidate catalog inventory differs",
    )
    result: dict[Path, bytes] = {}
    for path_text in repository_catalogs.OUTPUT_PATHS:
        raw = built[path_text]
        require(type(raw) is bytes, f"candidate catalog bytes differ: {path_text}")
        result[Path(path_text)] = raw
    return result


def _with_candidate_catalogs(
    evidence: CompletionEvidence,
    candidate_catalog_bytes_by_path: Mapping[Path, bytes],
) -> CompletionEvidence:
    require(
        set(candidate_catalog_bytes_by_path) == set(CATALOG_PATHS),
        "candidate catalog path inventory differs",
    )
    missing = set(CATALOG_PATHS) - set(evidence.final_managed_sha256_by_path)
    require(
        not missing,
        "candidate catalogs are outside the final managed closure: "
        + ", ".join(path.as_posix() for path in sorted(missing, key=Path.as_posix)),
    )
    final_managed = dict(evidence.final_managed_sha256_by_path)
    physical = dict(evidence.physical_sha256_by_path)
    for path, raw in candidate_catalog_bytes_by_path.items():
        digest = bytes_sha256(raw)
        final_managed[path] = digest
        physical[path] = digest
    return CompletionEvidence(
        documents_by_path=evidence.documents_by_path,
        bindings_by_role=evidence.bindings_by_role,
        update_occurred_at=evidence.update_occurred_at,
        completion_occurred_at=evidence.completion_occurred_at,
        physical_sha256_by_path=physical,
        final_managed_sha256_by_path=final_managed,
        production_sha256_by_path=evidence.production_sha256_by_path,
    )


def _require_candidate_catalogs_exact(
    root: Path,
    source_universe: tuple[str, ...],
    candidate_catalog_bytes_by_path: Mapping[Path, bytes],
    *,
    checkpoint: Mapping[str, Any],
    catalog_source_loader: Callable[[Path], tuple[str, ...]] = (
        repository_catalogs.discover_source_paths
    ),
    catalog_builder: Callable[..., Mapping[str, bytes]] = (
        repository_catalogs.build_catalog_bytes
    ),
) -> None:
    require(
        catalog_source_loader(root) == source_universe,
        "candidate catalog source universe changed",
    )
    require(
        _build_candidate_catalog_bytes(
            root,
            source_universe,
            checkpoint,
            catalog_builder=catalog_builder,
        )
        == candidate_catalog_bytes_by_path,
        "candidate catalog semantics changed",
    )
    for path in CATALOG_PATHS:
        require(
            _read_safe_bytes(root, path) == candidate_catalog_bytes_by_path[path],
            f"candidate catalog is stale: {path}",
        )


def prepare_projection(
    root: Path = ROOT,
    *,
    production_sha256_by_path: Mapping[Path, str] | None = None,
    source_validator: Callable[[bytes, Mapping[str, Any]], None] = require_exact_source,
    evidence_loader: Callable[..., CompletionEvidence] = load_completion_evidence,
    evidence_loader_kwargs: Mapping[str, Any] | None = None,
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]],
        tuple[dict[str, Any], dict[str, Any]],
    ] = fp046_apply.derive_runtime,
    snapshot_hasher: Callable[[Path, list[str]], tuple[str, str]] = (
        contract.working_snapshot_hashes
    ),
    changed_path_loader: Callable[[Path], set[Path]] = _git_visible_managed_paths,
    catalog_source_loader: Callable[[Path], tuple[str, ...]] = (
        repository_catalogs.discover_source_paths
    ),
    catalog_builder: Callable[..., Mapping[str, bytes]] = (
        repository_catalogs.build_catalog_bytes
    ),
    continuation_checker: Callable[[Path, Path], list[str]] = run_continuation_checker,
    goal_graph_checker: Callable[[Path, Path], list[str]] = run_goal_graph_checker,
    _allow_stale_catalogs_for_refresh: bool = False,
) -> PreparedProjection:
    root = root.resolve(strict=True)
    test_only_pins = (
        None
        if production_sha256_by_path is None
        else dict(production_sha256_by_path)
    )
    if test_only_pins is not None:
        require_resolved_production_pins(test_only_pins)
    checkpoint_path = _safe_file(root, CHECKPOINT_REL)
    source_bytes = checkpoint_path.read_bytes()
    source = trace.strict_json_bytes(source_bytes, CHECKPOINT_REL.as_posix())
    source_validator(source_bytes, source)
    require(checkpoint_path.read_bytes() == source_bytes, "source changed during validation")
    source_universe = catalog_source_loader(root)
    evidence = evidence_loader(
        root,
        source,
        pins=test_only_pins,
        **({} if evidence_loader_kwargs is None else dict(evidence_loader_kwargs)),
    )
    if test_only_pins is None:
        require(
            evidence.production_sha256_by_path is not None,
            "production review pin authority was not returned",
        )
        pins = dict(evidence.production_sha256_by_path)
        require_resolved_production_pins(pins)
    else:
        pins = test_only_pins
        require(
            evidence.production_sha256_by_path is None
            or dict(evidence.production_sha256_by_path) == pins,
            "completion evidence production pin authority differs",
        )
    require(
        checkpoint_path.read_bytes() == source_bytes,
        "source changed during evidence validation",
    )
    require(
        catalog_source_loader(root) == source_universe,
        "candidate catalog source universe changed during evidence validation",
    )

    def derive(
        checkpoint: dict[str, Any],
        ready: list[str],
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        return runtime_deriver(root, checkpoint, ready)

    draft_projected, _, _ = project_seq61_62(
        source,
        evidence,
        runtime_deriver=derive,
    )
    candidate_catalogs = _build_candidate_catalog_bytes(
        root,
        source_universe,
        draft_projected,
        catalog_builder=catalog_builder,
    )
    require(
        catalog_source_loader(root) == source_universe,
        "candidate catalog source universe changed while catalogs were built",
    )
    evidence = _with_candidate_catalogs(evidence, candidate_catalogs)
    projected, update, completion = project_seq61_62(
        source,
        evidence,
        runtime_deriver=derive,
    )
    fixed_point_catalogs = _build_candidate_catalog_bytes(
        root,
        source_universe,
        projected,
        catalog_builder=catalog_builder,
    )
    require(
        fixed_point_catalogs == candidate_catalogs,
        "candidate catalog/checkpoint projection did not reach a fixed point",
    )
    require(
        catalog_source_loader(root) == source_universe,
        "candidate catalog source universe changed during fixed-point projection",
    )
    if not _allow_stale_catalogs_for_refresh:
        _require_candidate_catalogs_exact(
            root,
            source_universe,
            candidate_catalogs,
            checkpoint=projected,
            catalog_source_loader=catalog_source_loader,
            catalog_builder=catalog_builder,
        )
    _require_git_visible_changes_are_managed(
        root,
        evidence.final_managed_sha256_by_path,
        changed_path_loader=changed_path_loader,
    )
    expected_snapshot = _snapshot_hashes_from_digests(
        evidence.final_managed_sha256_by_path
    )
    managed_paths = sorted(
        path.as_posix() for path in evidence.final_managed_sha256_by_path
    )
    if not _allow_stale_catalogs_for_refresh:
        require(
            snapshot_hasher(root, managed_paths) == expected_snapshot,
            "live final managed closure differs from projected snapshot",
        )
    validate_projection(source, projected, evidence)
    projected_bytes = trace.json_text(projected).encode()
    if not _allow_stale_catalogs_for_refresh:
        validate_projected_with_both_checkers(
            root,
            projected_bytes,
            continuation_checker=continuation_checker,
            goal_graph_checker=goal_graph_checker,
        )
        _require_candidate_catalogs_exact(
            root,
            source_universe,
            candidate_catalogs,
            checkpoint=projected,
            catalog_source_loader=catalog_source_loader,
            catalog_builder=catalog_builder,
        )
        _require_physical_matches(root, evidence.physical_sha256_by_path)
    require(
        checkpoint_path.read_bytes() == source_bytes,
        "source checkpoint changed during checker preflight",
    )
    return PreparedProjection(
        root=root,
        checkpoint_path=checkpoint_path,
        source_bytes=source_bytes,
        projected=projected,
        projected_bytes=projected_bytes,
        update_event=update,
        completion_event=completion,
        evidence=evidence,
        production_sha256_by_path=pins,
        sequence_sha256_by_path={
            path: evidence.final_managed_sha256_by_path[path]
            for path in SEQUENCE_AUTHORITY_PATHS
            if path in evidence.final_managed_sha256_by_path
        },
        catalog_source_universe=source_universe,
        candidate_catalog_bytes_by_path=candidate_catalogs,
        catalog_physical_verified=not _allow_stale_catalogs_for_refresh,
        test_only_pin_override_used=production_sha256_by_path is not None,
    )


def retain_physical_pin_cohort(
    root: Path,
    physical_sha256_by_path: Mapping[Path, str],
) -> PhysicalPinCohort:
    nofollow = getattr(os, "O_NOFOLLOW", None)
    require(type(nofollow) is int, "O_NOFOLLOW support is required")
    retained: list[RetainedPhysicalPin] = []
    try:
        for relative, digest in sorted(
            physical_sha256_by_path.items(),
            key=lambda row: row[0].as_posix(),
        ):
            canonical = _safe_file(root, relative)
            descriptor = os.open(
                canonical,
                os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | nofollow,
            )
            try:
                info = os.fstat(descriptor)
                path_info = os.stat(canonical, follow_symlinks=False)
                require(
                    fp046_apply._file_identity(info)
                    == fp046_apply._file_identity(path_info)
                    and stat.S_ISREG(info.st_mode)
                    and info.st_uid == os.geteuid()
                    and info.st_nlink == 1
                    and not (info.st_mode & (stat.S_ISUID | stat.S_ISGID)),
                    f"physical evidence authority differs: {relative}",
                )
                observed = fp046_apply._read_descriptor_exact(
                    descriptor,
                    info.st_size,
                )
                require(
                    bytes_sha256(observed) == digest,
                    f"sealed physical evidence SHA-256 differs: {relative}",
                )
                retained.append(
                    RetainedPhysicalPin(
                        label=relative.as_posix(),
                        relative=relative,
                        descriptor=descriptor,
                        identity=fp046_apply._file_identity(info),
                        expected_size=info.st_size,
                        expected_sha256=digest,
                    )
                )
            except BaseException:
                os.close(descriptor)
                raise
        cohort = PhysicalPinCohort(root, retained, [])
        cohort.verify()
        return cohort
    except BaseException as exc:
        PhysicalPinCohort(root, retained, []).close(exc)
        raise


def _require_candidate_catalog_projection_stable(
    prepared: PreparedProjection,
    *,
    catalog_source_loader: Callable[[Path], tuple[str, ...]] = (
        repository_catalogs.discover_source_paths
    ),
    catalog_builder: Callable[..., Mapping[str, bytes]] = (
        repository_catalogs.build_catalog_bytes
    ),
) -> None:
    require(
        _read_safe_bytes(prepared.root, CHECKPOINT_REL) == prepared.source_bytes,
        "source changed during candidate catalog refresh",
    )
    require(
        catalog_source_loader(prepared.root) == prepared.catalog_source_universe,
        "candidate catalog source universe changed during refresh",
    )
    rebuilt = _build_candidate_catalog_bytes(
        prepared.root,
        prepared.catalog_source_universe,
        prepared.projected,
        catalog_builder=catalog_builder,
    )
    require(
        rebuilt == prepared.candidate_catalog_bytes_by_path,
        "candidate catalog fixed point changed during refresh",
    )


def _validated_checkpoint_transport_stage(
    root: Path,
    source_bytes: bytes,
    projected_bytes: bytes,
) -> Path:
    parent = _safe_file(root, CHECKPOINT_REL).parent
    matches = tuple(
        entry
        for entry in os.scandir(parent)
        if CHECKPOINT_TRANSPORT_STAGE_NAME_RE.fullmatch(entry.name)
    )
    require(len(matches) == 1, "checkpoint transport stage inventory differs")
    entry = matches[0]
    info = entry.stat(follow_symlinks=False)
    relative = Path(entry.path).relative_to(root)
    require(
        not entry.is_symlink()
        and stat.S_ISREG(info.st_mode)
        and info.st_uid == os.geteuid()
        and info.st_nlink == 1
        and stat.S_IMODE(info.st_mode) == 0o600,
        "checkpoint transport stage authority differs",
    )
    raw = _read_safe_bytes(root, relative)
    require(
        raw == source_bytes or raw == projected_bytes,
        "checkpoint transport stage bytes differ",
    )
    return relative


def _catalog_source_universe_at_checkpoint_commit(
    root: Path,
    source_bytes: bytes,
    projected_bytes: bytes,
    *,
    loader: Callable[[Path], tuple[str, ...]],
) -> tuple[str, ...]:
    stage = _validated_checkpoint_transport_stage(
        root,
        source_bytes,
        projected_bytes,
    ).as_posix()
    universe = loader(root)
    require(stage in universe, "checkpoint transport stage is outside the catalog source universe")
    return tuple(path for path in universe if path != stage)


def _git_visible_paths_at_checkpoint_commit(
    root: Path,
    source_bytes: bytes,
    projected_bytes: bytes,
    *,
    loader: Callable[[Path], set[Path]],
) -> set[Path]:
    stage = _validated_checkpoint_transport_stage(
        root,
        source_bytes,
        projected_bytes,
    )
    visible = loader(root)
    require(
        stage not in visible,
        "checkpoint transport stage leaked into Git-visible managed paths",
    )
    return visible


def write_candidate_catalogs(
    prepared: PreparedProjection,
    *,
    catalog_source_loader: Callable[[Path], tuple[str, ...]] = (
        repository_catalogs.discover_source_paths
    ),
    catalog_builder: Callable[..., Mapping[str, bytes]] = (
        repository_catalogs.build_catalog_bytes
    ),
    atomic_writer: Callable[..., None] = atomic_write,
) -> None:
    require(
        not prepared.catalog_physical_verified,
        "candidate catalog refresh requires a refresh projection",
    )
    require(
        not prepared.test_only_pin_override_used or atomic_writer is not atomic_write,
        "test-only SHA-256 overrides cannot use the production catalog writer",
    )

    def guard() -> None:
        _require_candidate_catalog_projection_stable(
            prepared,
            catalog_source_loader=catalog_source_loader,
            catalog_builder=catalog_builder,
        )

    guard()
    for relative in CATALOG_PATHS:
        target = _safe_file(prepared.root, relative)
        expected_source = target.read_bytes()
        wanted = prepared.candidate_catalog_bytes_by_path[relative]
        if expected_source == wanted:
            continue
        atomic_writer(
            target,
            wanted,
            expected_source=expected_source,
            commit_guard=guard,
        )
    guard()
    _require_candidate_catalogs_exact(
        prepared.root,
        prepared.catalog_source_universe,
        prepared.candidate_catalog_bytes_by_path,
        checkpoint=prepared.projected,
        catalog_source_loader=catalog_source_loader,
        catalog_builder=catalog_builder,
    )


def write_projection(
    prepared: PreparedProjection,
    *,
    source_validator: Callable[[bytes, Mapping[str, Any]], None] = require_exact_source,
    evidence_loader: Callable[..., CompletionEvidence] = load_completion_evidence,
    evidence_loader_kwargs: Mapping[str, Any] | None = None,
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]],
        tuple[dict[str, Any], dict[str, Any]],
    ] = fp046_apply.derive_runtime,
    snapshot_hasher: Callable[[Path, list[str]], tuple[str, str]] = (
        contract.working_snapshot_hashes
    ),
    changed_path_loader: Callable[[Path], set[Path]] = _git_visible_managed_paths,
    catalog_source_loader: Callable[[Path], tuple[str, ...]] = (
        repository_catalogs.discover_source_paths
    ),
    catalog_builder: Callable[..., Mapping[str, bytes]] = (
        repository_catalogs.build_catalog_bytes
    ),
    continuation_checker: Callable[[Path, Path], list[str]] = run_continuation_checker,
    goal_graph_checker: Callable[[Path, Path], list[str]] = run_goal_graph_checker,
    review_pin_loader: Callable[[Path], Mapping[Path, str]] = (
        load_reviewed_production_pins
    ),
    atomic_writer: Callable[..., None] = atomic_write,
) -> None:
    require(
        prepared.catalog_physical_verified,
        "stale candidate catalogs cannot publish the checkpoint",
    )
    require(
        not prepared.test_only_pin_override_used or atomic_writer is not atomic_write,
        "test-only SHA-256 overrides cannot use the production atomic writer",
    )
    require(
        _read_safe_bytes(prepared.root, CHECKPOINT_REL) == prepared.source_bytes,
        "source changed before write revalidation",
    )
    cohort = retain_physical_pin_cohort(
        prepared.root,
        prepared.evidence.physical_sha256_by_path,
    )
    primary_error: BaseException | None = None
    try:
        refreshed = prepare_projection(
            prepared.root,
            production_sha256_by_path=(
                prepared.production_sha256_by_path
                if prepared.test_only_pin_override_used
                else None
            ),
            source_validator=source_validator,
            evidence_loader=evidence_loader,
            evidence_loader_kwargs=evidence_loader_kwargs,
            runtime_deriver=runtime_deriver,
            snapshot_hasher=snapshot_hasher,
            changed_path_loader=changed_path_loader,
            catalog_source_loader=catalog_source_loader,
            catalog_builder=catalog_builder,
            continuation_checker=continuation_checker,
            goal_graph_checker=goal_graph_checker,
        )
        cohort.verify()
        require(
            refreshed.production_sha256_by_path
            == prepared.production_sha256_by_path,
            "reviewed production pin authority changed before write",
        )
        require(refreshed.evidence == prepared.evidence, "completion evidence changed before write")
        require(
            refreshed.projected_bytes == prepared.projected_bytes,
            "projected checkpoint changed before write",
        )
        require(
            refreshed.sequence_sha256_by_path == prepared.sequence_sha256_by_path,
            "completion sequence authority changed before write",
        )
        require(
            refreshed.catalog_source_universe == prepared.catalog_source_universe
            and refreshed.candidate_catalog_bytes_by_path
            == prepared.candidate_catalog_bytes_by_path,
            "candidate catalog fixed point changed before write",
        )
        managed_paths = sorted(
            path.as_posix()
            for path in prepared.evidence.final_managed_sha256_by_path
        )
        expected_snapshot = _snapshot_hashes_from_digests(
            prepared.evidence.final_managed_sha256_by_path
        )
        commit_catalog_source_loader = catalog_source_loader
        commit_changed_path_loader = changed_path_loader
        if atomic_writer is atomic_write:
            commit_catalog_source_loader = lambda root: (
                _catalog_source_universe_at_checkpoint_commit(
                    root,
                    prepared.source_bytes,
                    prepared.projected_bytes,
                    loader=catalog_source_loader,
                )
            )
            commit_changed_path_loader = lambda root: (
                _git_visible_paths_at_checkpoint_commit(
                    root,
                    prepared.source_bytes,
                    prepared.projected_bytes,
                    loader=changed_path_loader,
                )
            )

        def commit_guard() -> None:
            cohort.verify()
            if not prepared.test_only_pin_override_used:
                require(
                    dict(review_pin_loader(prepared.root))
                    == dict(prepared.production_sha256_by_path),
                    "reviewed production pin authority changed at commit",
                )
            _require_candidate_catalogs_exact(
                prepared.root,
                prepared.catalog_source_universe,
                prepared.candidate_catalog_bytes_by_path,
                checkpoint=prepared.projected,
                catalog_source_loader=commit_catalog_source_loader,
                catalog_builder=catalog_builder,
            )
            _require_physical_matches(
                prepared.root,
                prepared.evidence.physical_sha256_by_path,
            )
            _require_git_visible_changes_are_managed(
                prepared.root,
                prepared.evidence.final_managed_sha256_by_path,
                changed_path_loader=commit_changed_path_loader,
            )
            require(
                snapshot_hasher(prepared.root, managed_paths) == expected_snapshot,
                "final managed closure changed at commit",
            )
            validate_projected_with_both_checkers(
                prepared.root,
                prepared.projected_bytes,
                continuation_checker=continuation_checker,
                goal_graph_checker=goal_graph_checker,
            )
            _require_candidate_catalogs_exact(
                prepared.root,
                prepared.catalog_source_universe,
                prepared.candidate_catalog_bytes_by_path,
                checkpoint=prepared.projected,
                catalog_source_loader=commit_catalog_source_loader,
                catalog_builder=catalog_builder,
            )

        atomic_writer(
            prepared.checkpoint_path,
            prepared.projected_bytes,
            expected_source=prepared.source_bytes,
            commit_guard=commit_guard,
        )
    except BaseException as exc:
        primary_error = exc
        raise
    finally:
        cohort.close(primary_error)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--refresh-catalogs", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.refresh_catalogs:
            refresh = prepare_projection(
                args.root,
                _allow_stale_catalogs_for_refresh=True,
            )
            write_candidate_catalogs(refresh)
            prepare_projection(args.root)
        else:
            prepared = prepare_projection(args.root)
        if args.write:
            write_projection(prepared)
    except CompletionPostCommitError as exc:
        print(f"NPC single-admin recovery seq61/62: POSTCOMMIT_UNCERTAIN: {exc}")
        return 1
    except (
        BuildError,
        CompletionApplyError,
        KeyError,
        OSError,
        ValueError,
        TypeError,
        repository_catalogs.CatalogError,
    ) as exc:
        print(f"NPC single-admin recovery seq61/62: FAIL: {exc}")
        return 1
    print("NPC single-admin recovery seq61/62: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
