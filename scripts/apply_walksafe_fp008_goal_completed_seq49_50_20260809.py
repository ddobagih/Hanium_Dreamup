#!/usr/bin/env python3
"""Prepare or atomically apply the exact FP-008 seq49-50 completion pair."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp048_goal_completed_seq43_44_20260802 as cas_transport
from scripts import build_walksafe_fp008_admin_review_delivery_trace_20260803 as fp008_trace
from scripts import build_walksafe_fp008_artifact_trace_successor_20260803 as fp008_artifacts
from scripts import build_walksafe_fp008_gap_backlog_r024_20260803 as fp008_r024
from scripts import build_walksafe_fp008_strict_review_gate_20260803 as fp008_review
from scripts import build_walksafe_phase1_exact257_successor_r013_20260803 as fp008_r013
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as contract
from scripts import materialize_walksafe_fp048_goal_20260802 as runtime_model


# The FP-048 completion publisher is reused only as the audited, generic
# rename-exchange transport.  FP-008 authority, evidence, projection, and pin
# closure are defined and checked independently below.
CompletionApplyError = cas_transport.CompletionApplyError
CompletionPostCommitError = cas_transport.CompletionPostCommitError
atomic_write = cas_transport.atomic_write

CHECKPOINT_RELATIVE = Path(
    "docs/control/walksafe-project-continuation-checkpoint.json"
)
SOURCE_CHECKPOINT_RAW_SHA256 = (
    "94053f5fbc408a1ac1f3479715b84be5e7edefe809328a76a5ea035e7900399a"
)
SOURCE_CHECKPOINT_BYTE_COUNT = 1_536_201
SOURCE_START_EVENT_SHA256 = (
    "82ad77e33eaa55530f53f5ee315807ef66e21fbfc8be2511b004abe99505db90"
)
SOURCE_START_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-002"
)
SOURCE_RESUME_EVENT_SHA256 = (
    "7674013bfab4cbdac572a0c809507aed0af36fa4f4fef91e0a7081426e62d7b0"
)
SOURCE_RESUME_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-FP008-20260809-005"
)
START_GATE_BINDING = {
    "document_id": (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP008-20260803-002"
    ),
    "file_sha256": (
        "c60c0da7311e86d3b3ee0c8282719468474d5356c267d283e049c911f550b47a"
    ),
    "path": (
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-002/"
        "implementation-start-gate-receipt.json"
    ),
}
START_GATE_REPOSITORY_STATE_PATH = fp008_trace.INITIAL_GATE_REPOSITORY_STATE_REL
START_GATE_REPOSITORY_STATE_SHA256 = (
    fp008_trace.EXPECTED_INITIAL_GATE_REPOSITORY_STATE_SHA256
)
START_RECEIPT_GATE_BINDING = {
    **START_GATE_BINDING,
    "repository_state_path": START_GATE_REPOSITORY_STATE_PATH.as_posix(),
    "repository_state_sha256": START_GATE_REPOSITORY_STATE_SHA256,
}
RESUME_GATE_BINDING = {
    "document_id": (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-RESUME-GATE-FP008-20260809-005"
    ),
    "file_sha256": (
        "394c9775c8ed3b2fbdac1b3cf8354ff839006b91998adc8cbbbb7c5f13dd8c5e"
    ),
    "path": (
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-FP008-20260809-005/"
        "implementation-resume-gate-receipt.json"
    ),
}
RESUME_GATE_REPOSITORY_STATE_PATH = fp008_trace.RESUME_GATE_REPOSITORY_STATE_REL
RESUME_GATE_REPOSITORY_STATE_SHA256 = (
    fp008_trace.EXPECTED_RESUME_GATE_REPOSITORY_STATE_SHA256
)
RESUME_RECEIPT_GATE_BINDING = {
    **RESUME_GATE_BINDING,
    "repository_state_path": RESUME_GATE_REPOSITORY_STATE_PATH.as_posix(),
    "repository_state_sha256": RESUME_GATE_REPOSITORY_STATE_SHA256,
}
CANONICAL_UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP008-20260809-001"
)
COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP008-20260809-001"
)
MANIFEST_SHA256 = (
    "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
)

GOAL_ID = "WS-GOAL-EPIC-03-FP-008-R001"
GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-fp008-admin-review-delivery-r001.md"
)
GOAL_SHA256 = (
    "2654fe5f595aefaecf974a4de70bad7946c2385cba9716200f2d2f49d849dd8e"
)
WORK_ITEM_ID = "EPIC-03-FP008-ADMIN-REVIEW-DELIVERY"
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
PARENT_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-03-account-admin-security.md"
)
PARENT_GOAL_SHA256 = (
    "7d2dfb7fe89bd30fc1f6113acb0b533a9fd91a6106cc2ab4798091763beae832"
)
EPIC12_GOAL_ID = "WS-GOAL-EPIC-12"

COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
COMPLETION_DOCUMENT_ID = (
    "WS-FP008-ADMIN-REVIEW-DELIVERY-WORK-ITEM-COMPLETION-20260809-001"
)
RESULT_ROOT = Path("docs/control/execution/goal-results") / GOAL_ID
COMPLETION_PATH = RESULT_ROOT / "completion-receipt.json"
IMPLEMENTATION_RESULT_PATH = RESULT_ROOT / "implementation-record.json"
VERIFICATION_RESULT_PATH = RESULT_ROOT / "verification-result.json"
SUCCESSOR_TRACE_PATH = RESULT_ROOT / "successor-trace.json"
POLICY_CONTRACT_PATH = RESULT_ROOT / "policy-contract.json"
REVIEW_SUBJECT_PATH = RESULT_ROOT / "review-subject.json"
REVIEW_ATTESTATION_PATH = RESULT_ROOT / "review-attestation.json"
INDEPENDENT_REVIEW_PATH = RESULT_ROOT / "independent-review.json"
GAP_PATH = Path(
    "docs/control/audits/"
    "walksafe-implementation-gap-analysis-20260809-r024.json"
)
BACKLOG_PATH = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260809-r024.json"
)
GAP_MARKDOWN_PATH = GAP_PATH.with_suffix(".md")
BACKLOG_MARKDOWN_PATH = BACKLOG_PATH.with_suffix(".md")
IMPLEMENTATION_MANIFEST_PATH = fp008_artifacts.IMPLEMENTATION_MANIFEST_REL
R013_LEDGER_PATH = fp008_r013.R013_LEDGER_REL
R013_EVIDENCE_PATH = fp008_r013.R013_EVIDENCE_REL
R013_RECEIPT_PATH = fp008_r013.R013_RECEIPT_REL
TRANSITIVE_INPUT_SHA256_BY_PATH = dict(
    fp008_r013.EXPECTED_R012_SHA256_BY_PATH
)

LANE_RECEIPT_PATHS = tuple(lane.receipt_rel for lane in fp008_trace.LANES)
LANE_LOG_PATHS = tuple(lane.log_rel for lane in fp008_trace.LANES)
GATE_LOG_ENTRY_NAMES = (
    "01-CONTINUATION.log",
    "02-V24_ARTIFACT_WORK_QUEUE.log",
    "03-TEST_LAYER_REGISTRY_VALIDATE.log",
    "04-BACKEND_TEST_DATABASE_PREFLIGHT.log",
    "05-BACKEND_ADMIN_REPORTS_OPENAPI_POSTGRES.log",
    "06-ANDROID_USER_INTERNAL.log",
    "07-ANDROID_ADMIN_INTERNAL.log",
    "08-ROOT_FP008_CONTROL_REGRESSION.log",
    "09-REPOSITORY_STATE.log",
)
SEALED_INVENTORY_BY_DIRECTORY = {
    RESULT_ROOT: tuple(
        sorted(
            {
                POLICY_CONTRACT_PATH.name,
                IMPLEMENTATION_RESULT_PATH.name,
                VERIFICATION_RESULT_PATH.name,
                SUCCESSOR_TRACE_PATH.name,
                REVIEW_SUBJECT_PATH.name,
                REVIEW_ATTESTATION_PATH.name,
                INDEPENDENT_REVIEW_PATH.name,
                COMPLETION_PATH.name,
                LANE_RECEIPT_PATHS[0].parent.name,
                LANE_LOG_PATHS[0].parent.name,
            }
        )
    ),
    LANE_RECEIPT_PATHS[0].parent: tuple(
        sorted(relative.name for relative in LANE_RECEIPT_PATHS)
    ),
    LANE_LOG_PATHS[0].parent: tuple(
        sorted(relative.name for relative in LANE_LOG_PATHS)
    ),
    Path(START_GATE_BINDING["path"]).parent: tuple(
        sorted(
            {
                Path(START_GATE_BINDING["path"]).name,
                *GATE_LOG_ENTRY_NAMES,
            }
        )
    ),
    Path(RESUME_GATE_BINDING["path"]).parent: tuple(
        sorted(
            {
                Path(RESUME_GATE_BINDING["path"]).name,
                *GATE_LOG_ENTRY_NAMES,
            }
        )
    ),
}
RECEIPT_MANIFEST_PATHS = (
    POLICY_CONTRACT_PATH,
    *LANE_RECEIPT_PATHS,
    IMPLEMENTATION_RESULT_PATH,
    VERIFICATION_RESULT_PATH,
    SUCCESSOR_TRACE_PATH,
    REVIEW_SUBJECT_PATH,
    *LANE_LOG_PATHS,
    INDEPENDENT_REVIEW_PATH,
)

CONSUMER_PATH_BY_ROLE = {
    "GAP_R024": GAP_PATH,
    "BACKLOG_R024": BACKLOG_PATH,
    "ARTIFACT_CHANGE_LOG": Path(
        "docs/deliverables/00-control/artifact-change-log.json"
    ),
    "ARTIFACT_REGISTER": Path(
        "docs/deliverables/00-control/artifact-register.json"
    ),
    "REQUIREMENTS_TRACEABILITY": Path(
        "docs/deliverables/03-requirements/rtm.json"
    ),
    "DESIGN_TRACEABILITY": Path(
        "docs/deliverables/04-design/design-traceability-register.json"
    ),
    "IMPLEMENTATION_MANIFEST": IMPLEMENTATION_MANIFEST_PATH,
    "MODULE_REGISTER": Path(
        "docs/deliverables/05-implementation/module-register.json"
    ),
    "EXACT257_R013_LEDGER": R013_LEDGER_PATH,
    "EXACT257_R013_EVIDENCE": R013_EVIDENCE_PATH,
    "EXACT257_R013_CHECK_RECEIPT": R013_RECEIPT_PATH,
}
CONSUMER_ROLE_ORDER = tuple(CONSUMER_PATH_BY_ROLE)

NEXT_POLICY_ID = "FP-046"
NEXT_GAP_ID = "GAP-055"
NEXT_WORK_ITEM_ID = "EPIC-03-FP046-CONSENT-WITHDRAWAL-DELETION"
NEXT_ACTION = (
    "동의·철회·전체삭제 요청/서버 연결 기능과 저장소별 삭제 연계 처리, "
    "부분실패 재시도, 백업 복원 뒤 삭제 완료 표식 재적용, 3년 삭제영수증을 "
    "구현한다."
)

FINAL_CURRENT_FOCUS = (
    "FP008/GAP-017 COMPLETE_AT_TARGET; FP046/GAP-055 PLANNED_NEXT"
)
FINAL_SCOPE = (
    "Graph v2.4 through the atomic FP008 canonical producer seq49 and "
    "completion seq50 transaction; FP046/GAP-055 is a pointer only and no "
    "formal, external, device, deployment, or release credit is granted."
)
FINAL_HANDOFF_EPIC = "EPIC-03 / FP046/GAP-055 PLANNED_NEXT"

SCRIPT_RELATIVE = Path(
    "scripts/apply_walksafe_fp008_goal_completed_seq49_50_20260809.py"
)
TEST_RELATIVE = Path(
    "tests/test_walksafe_fp008_goal_completed_seq49_50_20260809.py"
)
SEQUENCE_AUTHORITY_PATHS = (SCRIPT_RELATIVE, TEST_RELATIVE)
CONTROLLED_CHECKER_DELTA_SHA256_BY_PATH = {
    Path("scripts/check_walksafe_goal_graph_v2_4.py"): (
        "ed9ec31a87c58441a96b7acb614f6df4fe78ecd6b1e537f424154ef53c8ab09d"
    ),
    Path("tests/test_walksafe_goal_graph_v2_4.py"): (
        "4b711f519dd428379598e5884320b14c4314facbdb28b7450ea56d2ff180b8c5"
    ),
}

ADDITIONAL_OUTPUT_PIN_PATHS = (
    GAP_MARKDOWN_PATH,
    BACKLOG_MARKDOWN_PATH,
    IMPLEMENTATION_MANIFEST_PATH,
    R013_LEDGER_PATH,
    R013_EVIDENCE_PATH,
    R013_RECEIPT_PATH,
    *RECEIPT_MANIFEST_PATHS,
    REVIEW_ATTESTATION_PATH,
)

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
PENDING_FINAL_ARTIFACT_SHA256 = "PENDING_FINAL_ARTIFACT_SHA256"


@dataclass(frozen=True)
class ArtifactSpec:
    role: str
    document_id: str
    path: Path
    identity_json_path: str
    mutable: bool
    consumer_role: str


ARTIFACT_SPECS = (
    ArtifactSpec(
        "ARTIFACT_CHANGE_LOG",
        "ART-DOC-05-001",
        Path("docs/deliverables/00-control/artifact-change-log.json"),
        "metadata.register_id",
        True,
        "ARTIFACT_CHANGE_LOG",
    ),
    ArtifactSpec(
        "ARTIFACT_REGISTER",
        "ART-DOC-01-001",
        Path("docs/deliverables/00-control/artifact-register.json"),
        "metadata.register_id",
        True,
        "ARTIFACT_REGISTER",
    ),
    ArtifactSpec(
        "DESIGN_TRACEABILITY",
        "WS-DESIGN-TRACEABILITY-20260721-001",
        Path("docs/deliverables/04-design/design-traceability-register.json"),
        "metadata.register_id",
        True,
        "DESIGN_TRACEABILITY",
    ),
    ArtifactSpec(
        "IMPLEMENTATION_BACKLOG",
        "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260809-024",
        BACKLOG_PATH,
        "metadata.backlog_id",
        False,
        "BACKLOG_R024",
    ),
    ArtifactSpec(
        "IMPLEMENTATION_GAP",
        "WS-IMPLEMENTATION-GAP-ANALYSIS-20260809-024",
        GAP_PATH,
        "metadata.report_id",
        False,
        "GAP_R024",
    ),
    ArtifactSpec(
        "MODULE_REGISTER",
        "DEV-18",
        Path("docs/deliverables/05-implementation/module-register.json"),
        "metadata.artifact_type_ids",
        True,
        "MODULE_REGISTER",
    ),
    ArtifactSpec(
        "REQUIREMENTS_TRACEABILITY",
        "WS-REQ-RTM-DRAFT-20260721-R001",
        Path("docs/deliverables/03-requirements/rtm.json"),
        "metadata.document_id",
        True,
        "REQUIREMENTS_TRACEABILITY",
    ),
    ArtifactSpec(
        COMPLETION_ROLE,
        COMPLETION_DOCUMENT_ID,
        COMPLETION_PATH,
        "document_id",
        False,
        COMPLETION_ROLE,
    ),
)
SPEC_BY_ROLE = {spec.role: spec for spec in ARTIFACT_SPECS}

# These pins bind the independently reviewed FP-008 publication closure.  The
# overrides remain an in-process test seam and are not exposed through the CLI.
PINNED_PRODUCTION_SHA256_BY_ROLE: dict[str, str] = {
    "ARTIFACT_CHANGE_LOG": (
        "ba4d4cc686471b068e052955112ce7a056eb66bb464d51713aadb8138668cf45"
    ),
    "ARTIFACT_REGISTER": (
        "7a5131837e135b55f9a8e510007d48f46a7b740583ffe6e51d00b3220be43a40"
    ),
    "DESIGN_TRACEABILITY": (
        "08df990c3c1b694cb49a1acf19317403d4e5952fc880e0493fa226b859931c6f"
    ),
    "IMPLEMENTATION_BACKLOG": (
        "f2c860d8d5199bdd18da62fd44bf0e161a7f6b9933bde7eebed86afb9fb8b55d"
    ),
    "IMPLEMENTATION_GAP": (
        "e9e95998875660663ad7d573cf193ad7b80de12fc64a1d5ecda1285ff71b38e0"
    ),
    "MODULE_REGISTER": (
        "bdbb190c64665f07afedbddcb3d31d41a5b140ebb9e466288b17445e3d4f3e08"
    ),
    "REQUIREMENTS_TRACEABILITY": (
        "eff6b14788626add6b774080674f18fc53cce81944330665a841db240a25ccf9"
    ),
    COMPLETION_ROLE: (
        "d5592127c5b2bb30c0d0927554c91b17b1eb1c4b31f470c7aeef07030f43c608"
    ),
}
PINNED_PRODUCTION_SHA256_BY_PATH: dict[Path, str] = {
    GAP_MARKDOWN_PATH: (
        "acbf3c5206fc46071d8f1ef7ce8f0edcdb28b0c17cf8aa820c766be310885367"
    ),
    BACKLOG_MARKDOWN_PATH: (
        "1cc9aa6fb6b73c82fb80d16a19396fc44ee6af85ac385cad182b37c74b0f1770"
    ),
    IMPLEMENTATION_MANIFEST_PATH: (
        "6d85730c82e1ee088cf73ad7bfe9f35e9cc4cda7fc5bc9555dd9db24875e9f64"
    ),
    R013_LEDGER_PATH: (
        "52fc3313e0704f7b2e3ec17d64d703ad0c1dc9a69755818583c0f13b1ad8719e"
    ),
    R013_EVIDENCE_PATH: (
        "22559ee3299672f2997798999782438a72ebe2932d969ff7c7e6e4e9ece51649"
    ),
    R013_RECEIPT_PATH: (
        "c6049e385beebf4034d000f83735744f661831302f5b34228569e4f571e528d6"
    ),
    POLICY_CONTRACT_PATH: (
        "59f4ce8d160a4736b3f3820d3b60f0a805ef7cd887f6451ca5424917412b8a16"
    ),
    LANE_RECEIPT_PATHS[0]: (
        "58c5daf496a3e52dcf85b6cdd3fff5bf863ab0f6abbdf28a6e49ef5c3a34d64e"
    ),
    LANE_RECEIPT_PATHS[1]: (
        "f20925d11e0a8402672efc78d2846eef4e603305ea0d68c7acf692a825074302"
    ),
    LANE_RECEIPT_PATHS[2]: (
        "0cd29621afed690a5ff6afbec8199f5acc3e2410b94eff703e0c89646e29a304"
    ),
    LANE_RECEIPT_PATHS[3]: (
        "9c13ace8f23132ba7cfd2f25f9a6fdd88fd9f19d20bd868ce597c721f04f2888"
    ),
    IMPLEMENTATION_RESULT_PATH: (
        "d5d7eca6873b5b5744c6ad8a0f55b41f0b4a94cb0aa109381a9b05c6b64aee9b"
    ),
    VERIFICATION_RESULT_PATH: (
        "916e79d6b7a63e9c1648125a09e2a26cb6ffd7f6779887e10ad7c7ddd1b26fad"
    ),
    SUCCESSOR_TRACE_PATH: (
        "da19ccb8d3f872965d8d09bc7ec7922ebcb25e1c3f4e4a72c0fcaf53fb476faf"
    ),
    REVIEW_SUBJECT_PATH: (
        "5d4bc66152185c46d4044519ad4f0e92aa990eb7a351d471e2f885621514755b"
    ),
    LANE_LOG_PATHS[0]: (
        "d36674ae31a2b5b6560fa964a99f2c2a92bace0ad2b3ab2a296a58825ee0f898"
    ),
    LANE_LOG_PATHS[1]: (
        "6ef0753e2e178ee49edadd4273f439c039e13d765f4db054ad486de86d040649"
    ),
    LANE_LOG_PATHS[2]: (
        "626b9e94120bdf8f58f27f7842fe062406c743ebb5a8c282c0f1a780616905e6"
    ),
    LANE_LOG_PATHS[3]: (
        "eeeba797f2b746b51c81c3b5edac0167c1742dc5084e7f6a355d3f98d27ede6e"
    ),
    INDEPENDENT_REVIEW_PATH: (
        "81afcd50b29ffbce66ca7611624e77feb5f1ed49633433887d1335a3483275bb"
    ),
    REVIEW_ATTESTATION_PATH: (
        "3a8a51876a01b4bf04ce2d5c3cd134f4a6ce0d23d65e597d4eec8f7f7ef89973"
    ),
}

CHANGED_ROLES = sorted(SPEC_BY_ROLE)
PRODUCED_ROLES = ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"]
TRACE_ROLES = {
    "ARTIFACT_CHANGE_LOG",
    "ARTIFACT_REGISTER",
    "DESIGN_TRACEABILITY",
    "MODULE_REGISTER",
    "REQUIREMENTS_TRACEABILITY",
}
FORBIDDEN_CHANGED_ROLES = {
    "IMPLEMENTATION_MANIFEST",
    "PLANNED_TEST_CASES",
    "PHASE1_EXACT257_SUCCESSOR_R012_LEDGER",
    "PHASE1_EXACT257_SUCCESSOR_R012_EVIDENCE",
    "PHASE1_EXACT257_SUCCESSOR_R012_CHECK_RECEIPT",
}
ARTIFACT_SUBJECT_IDS = [
    "DLV-DES-06",
    "DLV-DEV-01",
    "DLV-DEV-18",
    "DLV-DOC-01",
    "DLV-DOC-05",
    "DLV-REQ-16",
]
CHANGED_SUBJECT_IDS_BY_ROLE = {
    "ARTIFACT_CHANGE_LOG": ARTIFACT_SUBJECT_IDS,
    "ARTIFACT_REGISTER": ARTIFACT_SUBJECT_IDS,
    "DESIGN_TRACEABILITY": ["FP-008"],
    "IMPLEMENTATION_BACKLOG": ["FP-008"],
    "IMPLEMENTATION_GAP": ["FP-008", "GAP-017"],
    "MODULE_REGISTER": ["FP-008"],
    "REQUIREMENTS_TRACEABILITY": ["FP-008"],
}
PRODUCER_OUTPUT_SUBJECT_IDS_BY_ROLE = {
    role: CHANGED_SUBJECT_IDS_BY_ROLE[role] for role in PRODUCED_ROLES
}

UPDATE_EVENT_FIELDS = {
    "sequence",
    "event_id",
    "event_type",
    "occurred_on",
    "occurred_at",
    "previous_focus_goal_id",
    "previous_focus_content_sha256",
    "focus_goal_id",
    "focus_goal_content_sha256",
    "from_status",
    "to_status",
    "static_plan_manifest_sha256",
    "status_changes",
    "runtime_after",
    "blockers_after",
    "blocker_resolution_ids_after",
    "source_checkpoint_version",
    "evidence_refs",
    "previous_event_sha256",
    "produced_by_goal_id",
    "produced_binding_roles",
    "producer_completion_receipt_binding",
    "changed_binding_roles",
    "changed_subject_ids_by_role",
    "producer_output_subject_ids_by_role",
    "impact_closure_goal_ids",
    "impact_disposition_by_goal",
    "reopened_completion_event_sha256_by_goal",
    "canonical_binding_snapshot_after",
    "event_sha256",
}
COMPLETION_EVENT_FIELDS = {
    "sequence",
    "event_id",
    "event_type",
    "occurred_on",
    "occurred_at",
    "previous_focus_goal_id",
    "previous_focus_content_sha256",
    "focus_goal_id",
    "focus_goal_content_sha256",
    "subject_goal_id",
    "from_status",
    "to_status",
    "static_plan_manifest_sha256",
    "status_changes",
    "runtime_after",
    "blockers_after",
    "blocker_resolution_ids_after",
    "source_checkpoint_version",
    "evidence_refs",
    "previous_event_sha256",
    "canonical_update_event_sha256",
    "completion_receipt_binding",
    "completion_evidence_bindings",
    "completion_evidence_by_goal_after",
    "canonical_binding_snapshot_after",
    "event_sha256",
}

EXPECTED_COMPLETION_BOUNDARY = {
    "scope": (
        "REPOSITORY_INTERNAL_FP008_IMPLEMENTATION_AND_"
        "AUTOMATED_VERIFICATION_ONLY"
    ),
    "planned_test_ids": [f"TC-FP-008-{number:02d}" for number in range(1, 5)],
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
class CompletionEvidence:
    documents_by_role: dict[str, dict[str, Any]]
    bindings_by_role: dict[str, dict[str, str]]
    receipt: dict[str, Any]
    update_occurred_at: str
    completion_occurred_at: str
    physical_sha256_by_path: dict[Path, str]
    final_managed_sha256_by_path: dict[Path, str]
    authorized_delta_by_path: dict[Path, dict[str, str]]
    added_managed_paths: tuple[Path, ...]


@dataclass(frozen=True)
class PreparedProjection:
    root: Path
    checkpoint_path: Path
    source_checkpoint_bytes: bytes
    projected_checkpoint: dict[str, Any]
    projected_checkpoint_bytes: bytes
    update_event: dict[str, Any]
    completion_event: dict[str, Any]
    evidence: CompletionEvidence
    artifact_sha256_by_role: dict[str, str]
    output_sha256_by_path: dict[Path, str]
    sequence_sha256_by_path: dict[Path, str]
    test_only_pin_override_used: bool


@dataclass(frozen=True)
class RetainedPhysicalPin:
    label: str
    relative: Path
    descriptor: int
    identity: tuple[int, ...]
    expected_size: int
    expected_sha256: str


@dataclass(frozen=True)
class RetainedDirectoryPin:
    label: str
    relative: Path
    descriptor: int
    identity: tuple[int, ...]
    expected_entries: tuple[str, ...]


class PhysicalPinCohort:
    def __init__(
        self,
        root: Path,
        pins: list[RetainedPhysicalPin],
        directory_pins: list[RetainedDirectoryPin] | None = None,
    ) -> None:
        self.root = root
        self.pins = pins
        self.directory_pins = [] if directory_pins is None else directory_pins

    def verify(self) -> None:
        for pin in self.pins:
            _verify_retained_physical_pin(self.root, pin)
        for pin in self.directory_pins:
            _verify_retained_directory_pin(self.root, pin)

    def close(self, primary_error: BaseException | None = None) -> None:
        first_error: OSError | None = None
        descriptors = [
            *(pin.descriptor for pin in self.pins),
            *(pin.descriptor for pin in self.directory_pins),
        ]
        self.pins.clear()
        self.directory_pins.clear()
        while descriptors:
            try:
                os.close(descriptors.pop())
            except OSError as exc:
                if first_error is None:
                    first_error = exc
        if first_error is None:
            return
        if primary_error is not None:
            primary_error.add_note(
                "physical pin descriptor cleanup also failed: "
                f"{type(first_error).__name__}: {first_error}"
            )
            return
        raise first_error


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise CompletionApplyError(message)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode(
        "utf-8"
    )


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        _require(key not in value, f"duplicate JSON member: {key}")
        value[key] = item
    return value


def _parse_json_bytes(content: bytes, *, label: str) -> dict[str, Any]:
    _require(not content.startswith(b"\xef\xbb\xbf"), f"{label} has a BOM")
    try:
        value = json.loads(
            content.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(
                CompletionApplyError(f"{label} has non-finite JSON: {token}")
            ),
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise CompletionApplyError(
            f"{label} is not valid UTF-8 JSON"
        ) from exc
    _require(isinstance(value, dict), f"{label} root is not an object")
    return value


def _safe_file(root: Path, relative: Path) -> Path:
    _require(
        not relative.is_absolute()
        and relative.parts
        and ".." not in relative.parts
        and "." not in relative.parts,
        f"unsafe repository path: {relative}",
    )
    resolved_root = root.resolve(strict=True)
    candidate = resolved_root
    for part in relative.parts:
        candidate /= part
        _require(not candidate.is_symlink(), f"symlink is forbidden: {relative}")
    try:
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(resolved_root)
    except (OSError, RuntimeError, ValueError) as exc:
        raise CompletionApplyError(
            f"repository file is missing or unsafe: {relative}"
        ) from exc
    _require(resolved.is_file(), f"repository path is not a file: {relative}")
    return resolved


def _safe_directory(root: Path, relative: Path) -> Path:
    _require(
        not relative.is_absolute()
        and relative.parts
        and ".." not in relative.parts
        and "." not in relative.parts,
        f"unsafe repository directory: {relative}",
    )
    resolved_root = root.resolve(strict=True)
    candidate = resolved_root
    for part in relative.parts:
        candidate /= part
        _require(not candidate.is_symlink(), f"symlink is forbidden: {relative}")
    try:
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(resolved_root)
    except (OSError, RuntimeError, ValueError) as exc:
        raise CompletionApplyError(
            f"repository directory is missing or unsafe: {relative}"
        ) from exc
    _require(resolved.is_dir(), f"repository path is not a directory: {relative}")
    return resolved


def _parse_time(
    value: Any,
    *,
    label: str,
    allow_fractional_seconds: bool = False,
) -> datetime:
    _require(isinstance(value, str), f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise CompletionApplyError(f"{label} is not ISO-8601") from exc
    _require(
        parsed.tzinfo is not None and parsed.utcoffset() is not None,
        f"{label} lacks a timezone",
    )
    _require(parsed.isoformat() == value, f"{label} is not canonical ISO-8601")
    if not allow_fractional_seconds:
        _require(parsed.microsecond == 0, f"{label} must use second precision")
    return parsed


def _json_path(value: dict[str, Any], dotted: str) -> Any:
    current: Any = value
    for component in dotted.split("."):
        if not isinstance(current, dict) or component not in current:
            return None
        current = current[component]
    return current


def _resolve_sha256_pins(
    override: Mapping[str, str] | None,
) -> dict[str, str]:
    pins = dict(PINNED_PRODUCTION_SHA256_BY_ROLE if override is None else override)
    _require(set(pins) == set(SPEC_BY_ROLE), "artifact SHA-256 role set differs")
    pending = sorted(
        role
        for role, digest in pins.items()
        if not isinstance(digest, str) or SHA256_RE.fullmatch(digest) is None
    )
    _require(
        not pending,
        "production artifact SHA-256 is PENDING or invalid: "
        + ", ".join(pending),
    )
    return pins


def _resolve_path_sha256_pins(
    override: Mapping[Path | str, str] | None,
    *,
    production: Mapping[Path, str],
    expected_paths: Sequence[Path],
    label: str,
) -> dict[Path, str]:
    raw = production if override is None else override
    pins: dict[Path, str] = {}
    for raw_path, digest in raw.items():
        relative = Path(raw_path)
        _require(relative not in pins, f"duplicate {label} path: {relative}")
        pins[relative] = digest
    _require(set(pins) == set(expected_paths), f"{label} path set differs")
    pending = sorted(
        relative.as_posix()
        for relative, digest in pins.items()
        if not isinstance(digest, str) or SHA256_RE.fullmatch(digest) is None
    )
    _require(
        not pending,
        f"production {label} SHA-256 is PENDING or invalid: "
        + ", ".join(pending),
    )
    return pins


def _merge_sha256_binding(
    target: dict[Path, str],
    relative: Path,
    digest: str,
    *,
    label: str,
) -> None:
    existing = target.get(relative)
    _require(
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
        _require(
            isinstance(digest, str) and SHA256_RE.fullmatch(digest) is not None,
            f"snapshot digest differs: {relative_text}",
        )
        content.update(relative_text.encode("utf-8"))
        content.update(b"\0")
        content.update(digest.encode("ascii"))
        content.update(b"\n")
    return path_hash, content.hexdigest()


def require_exact_source(content: bytes, source: dict[str, Any]) -> None:
    _require(len(content) == SOURCE_CHECKPOINT_BYTE_COUNT, "source byte count differs")
    _require(
        sha256_bytes(content) == SOURCE_CHECKPOINT_RAW_SHA256,
        "source checkpoint raw SHA-256 differs",
    )
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(source.get("schema_version") == "1.25.0", "source schema differs")
    _require(isinstance(state, dict), "source goal execution is missing")
    _require(isinstance(history, list) and len(history) == 48, "source is not seq48")
    start = history[-2]
    resume = history[-1]
    _require(
        isinstance(start, dict)
        and start.get("sequence") == 47
        and start.get("event_id") == SOURCE_START_EVENT_ID
        and start.get("event_type") == "GOAL_STARTED"
        and start.get("subject_goal_id") == GOAL_ID
        and start.get("from_status") == "READY"
        and start.get("to_status") == "IN_PROGRESS"
        and start.get("implementation_start_gate_binding") == START_GATE_BINDING
        and start.get("event_sha256") == SOURCE_START_EVENT_SHA256
        and contract.event_sha256(start) == SOURCE_START_EVENT_SHA256,
        "source seq47 start seal or gate differs",
    )
    _require(
        isinstance(resume, dict)
        and resume.get("sequence") == 48
        and resume.get("event_id") == SOURCE_RESUME_EVENT_ID
        and resume.get("event_type") == "WORK_SESSION_RESUMED"
        and resume.get("subject_goal_id") == GOAL_ID
        and resume.get("from_status") == "IN_PROGRESS"
        and resume.get("to_status") == "IN_PROGRESS"
        and resume.get("status_changes") == {}
        and resume.get("previous_event_sha256") == SOURCE_START_EVENT_SHA256
        and resume.get("previous_execution_session_event_sha256")
        == SOURCE_START_EVENT_SHA256
        and resume.get("implementation_start_gate_binding")
        == RESUME_GATE_BINDING
        and resume.get("event_sha256") == SOURCE_RESUME_EVENT_SHA256
        and contract.event_sha256(resume) == SOURCE_RESUME_EVENT_SHA256,
        "source seq48 resume seal, lineage, or gate differs",
    )
    _require(
        state.get("transition_history_anchor_sha256")
        == SOURCE_RESUME_EVENT_SHA256,
        "source history anchor differs",
    )
    _require(
        state.get("status_by_goal", {}).get(GOAL_ID) == "IN_PROGRESS"
        and state.get("focus_goal_id") == GOAL_ID
        and state.get("focus_goal_path") == GOAL_PATH
        and state.get("focus_work_item_id") == WORK_ITEM_ID
        and state.get("focus_source") == "IMPLEMENTATION_BACKLOG"
        and state.get("ready_frontier_goal_ids")
        == [GOAL_ID, PARENT_GOAL_ID, EPIC12_GOAL_ID]
        and state.get("activation_status") == "ACTIVE"
        and state.get("package_status") == "ACTIVE",
        "source FP008 IN_PROGRESS projection differs",
    )
    _require(
        state.get("pending_producer_completion_goal_id") in {None, ""},
        "source already has a pending producer transaction",
    )
    bindings = source.get("canonical_bindings")
    _require(
        isinstance(bindings, list) and len(bindings) == 39,
        "source canonical count differs",
    )
    _require(
        COMPLETION_ROLE
        not in {row.get("role") for row in bindings if isinstance(row, dict)},
        "source already contains the FP008 completion role",
    )
    errors = contract.validate_generic_event_order(history)
    _require(not errors, "source history differs: " + "; ".join(errors))


def _load_evidence_documents(
    root: Path,
    pins: Mapping[str, str],
) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, str]]]:
    documents: dict[str, dict[str, Any]] = {}
    bindings: dict[str, dict[str, str]] = {}
    for spec in ARTIFACT_SPECS:
        content = _safe_file(root, spec.path).read_bytes()
        _require(
            sha256_bytes(content) == pins[spec.role],
            f"sealed physical artifact SHA-256 differs: {spec.role}",
        )
        value = _parse_json_bytes(content, label=spec.role)
        identity = _json_path(value, spec.identity_json_path)
        matches = (
            spec.document_id in identity
            if isinstance(identity, list)
            else identity == spec.document_id
        )
        _require(matches, f"physical artifact identity differs: {spec.role}")
        documents[spec.role] = value
        bindings[spec.role] = {
            "role": spec.role,
            "document_id": spec.document_id,
            "path": spec.path.as_posix(),
            "file_sha256": pins[spec.role],
        }
    return documents, bindings


def _physical_sha256_binding(
    root: Path,
    value: Any,
    *,
    label: str,
    expected_path: Path | None = None,
) -> tuple[Path, str]:
    _require(isinstance(value, dict), f"{label} binding is missing")
    relative_value = value.get("path")
    digest = value.get("sha256", value.get("file_sha256"))
    _require(
        isinstance(relative_value, str)
        and isinstance(digest, str)
        and SHA256_RE.fullmatch(digest) is not None,
        f"{label} binding is malformed",
    )
    relative = Path(relative_value)
    _require(expected_path is None or relative == expected_path, f"{label} path differs")
    content = _safe_file(root, relative).read_bytes()
    _require(sha256_bytes(content) == digest, f"{label} physical SHA-256 differs")
    return relative, digest


def _source_snapshot_authority(
    root: Path,
    source: dict[str, Any],
) -> tuple[dict[Path, str], dict[Path, str]]:
    log_raw = _safe_file(root, RESUME_GATE_REPOSITORY_STATE_PATH).read_bytes()
    _require(
        sha256_bytes(log_raw) == RESUME_GATE_REPOSITORY_STATE_SHA256,
        "seq48 repository-state log SHA-256 differs",
    )
    log = _parse_json_bytes(log_raw, label="seq48 repository-state log")
    _require(
        log.get("schema_version") == "1.0.0"
        and log.get("evidence_type") == "GATE_REPOSITORY_STATE"
        and log.get("gate_event_id") == SOURCE_RESUME_EVENT_ID,
        "seq48 repository-state identity differs",
    )
    source_snapshot = source.get("working_tree_snapshot")
    controlled = log.get("checkpoint_controlled_working_snapshot")
    _require(
        isinstance(source_snapshot, dict) and isinstance(controlled, dict),
        "seq48 controlled snapshot authority is missing",
    )
    for source_key, log_key in (
        ("managed_changed_path_count", "managed_changed_path_count"),
        ("path_set_sha256", "path_set_sha256"),
        ("content_set_sha256", "content_set_sha256"),
    ):
        _require(
            source_snapshot.get(source_key) == controlled.get(log_key),
            f"seq48 controlled snapshot {source_key} differs",
        )

    resume = source["goal_execution"]["transition_history"][-1]
    repository_snapshot = resume.get("repository_snapshot_before")
    _require(
        isinstance(repository_snapshot, dict)
        and repository_snapshot.get("gate_repository_state_output_sha256")
        == RESUME_GATE_REPOSITORY_STATE_SHA256
        and repository_snapshot.get("checkpoint_managed_path_count")
        == source_snapshot.get("managed_changed_path_count")
        and repository_snapshot.get("checkpoint_path_set_sha256")
        == source_snapshot.get("path_set_sha256")
        and repository_snapshot.get("checkpoint_content_set_sha256")
        == source_snapshot.get("content_set_sha256"),
        "seq48 event repository-state projection differs",
    )

    dirty = log.get("dirty_snapshot")
    rows = dirty.get("paths") if isinstance(dirty, dict) else None
    _require(
        isinstance(rows, list)
        and dirty.get("dirty_path_count") == len(rows),
        "seq48 dirty snapshot inventory differs",
    )
    current_by_path: dict[str, dict[str, Any]] = {}
    for row in rows:
        _require(isinstance(row, dict), "seq48 dirty snapshot row is malformed")
        if row.get("path_role") != "CURRENT":
            continue
        path_text = row.get("path")
        _require(
            isinstance(path_text, str) and path_text not in current_by_path,
            "seq48 current path is invalid or duplicated",
        )
        current_by_path[path_text] = row

    source_paths = source_snapshot.get("managed_changed_paths")
    _require(
        isinstance(source_paths, list)
        and source_paths == sorted(set(source_paths))
        and len(source_paths) == source_snapshot.get("managed_changed_path_count"),
        "seq48 managed path inventory differs",
    )
    source_sha256_by_path: dict[Path, str] = {}
    for path_text in source_paths:
        row = current_by_path.get(path_text)
        if row is None:
            # The repository-state inventory is based on Git porcelain and
            # therefore omits clean tracked and ignored controlled files.  For
            # those exact checkpoint paths, the physical bytes complete the
            # old per-path map; the checkpoint's sealed aggregate below proves
            # that the resulting 631-path reconstruction is the seq48 map.
            digest = sha256_bytes(_safe_file(root, Path(path_text)).read_bytes())
        else:
            worktree = row.get("worktree")
            digest = worktree.get("sha256") if isinstance(worktree, dict) else None
            _require(
                isinstance(worktree, dict)
                and worktree.get("state") == "PRESENT"
                and worktree.get("type") == "REGULAR_FILE"
                and isinstance(worktree.get("byte_count"), int)
                and worktree["byte_count"] >= 0
                and isinstance(digest, str)
                and SHA256_RE.fullmatch(digest) is not None,
                f"seq48 managed source bytes are not bound: {path_text}",
            )
        source_sha256_by_path[Path(path_text)] = digest
    _require(
        _snapshot_hashes_from_digests(source_sha256_by_path)
        == (
            source_snapshot.get("path_set_sha256"),
            source_snapshot.get("content_set_sha256"),
        ),
        "seq48 managed source aggregate cannot be reconstructed",
    )
    return source_sha256_by_path, {
        RESUME_GATE_REPOSITORY_STATE_PATH: RESUME_GATE_REPOSITORY_STATE_SHA256
    }


def _collect_gate_closure(
    root: Path,
    binding: Mapping[str, str],
    *,
    repository_state_path: Path,
    repository_state_sha256: str,
    label: str,
) -> dict[Path, str]:
    receipt_path = Path(binding["path"])
    receipt_raw = _safe_file(root, receipt_path).read_bytes()
    _require(
        sha256_bytes(receipt_raw) == binding["file_sha256"],
        f"{label} receipt SHA-256 differs",
    )
    receipt = _parse_json_bytes(receipt_raw, label=f"{label} receipt")
    _require(
        receipt.get("document_id") == binding["document_id"]
        and receipt.get("status") == "PASS"
        and receipt.get("target_goal_id") == GOAL_ID,
        f"{label} receipt identity differs",
    )
    closure = {receipt_path: binding["file_sha256"]}

    contract_binding = receipt.get("implementation_start_gate_contract_binding")
    relative, digest = _physical_sha256_binding(
        root,
        contract_binding,
        label=f"{label} contract",
    )
    _merge_sha256_binding(closure, relative, digest, label=f"{label} contract")

    runtime_bindings = receipt.get("runtime_bindings")
    _require(
        isinstance(runtime_bindings, list) and len(runtime_bindings) == 5,
        f"{label} runtime binding set differs",
    )
    for index, runtime_binding in enumerate(runtime_bindings, start=1):
        relative, digest = _physical_sha256_binding(
            root,
            runtime_binding,
            label=f"{label} runtime {index}",
        )
        _merge_sha256_binding(
            closure,
            relative,
            digest,
            label=f"{label} runtime {index}",
        )

    runs = receipt.get("check_runs")
    _require(
        isinstance(runs, list) and len(runs) == 9,
        f"{label} check-run set differs",
    )
    for index, run in enumerate(runs, start=1):
        _require(
            isinstance(run, dict)
            and run.get("exit_code") == 0
            and isinstance(run.get("check_id"), str),
            f"{label} check run {index} differs",
        )
        relative, digest = _physical_sha256_binding(
            root,
            {
                "path": run.get("output_path"),
                "sha256": run.get("output_sha256"),
            },
            label=f"{label} check output {index}",
        )
        _merge_sha256_binding(
            closure,
            relative,
            digest,
            label=f"{label} check output {index}",
        )
    _require(
        closure.get(repository_state_path) == repository_state_sha256,
        f"{label} repository-state output binding differs",
    )
    repository_snapshot = receipt.get("repository_snapshot")
    _require(
        isinstance(repository_snapshot, dict)
        and repository_snapshot.get("gate_repository_state_output_sha256")
        == repository_state_sha256,
        f"{label} repository-state receipt projection differs",
    )
    return closure


def _validate_sealed_document(
    value: dict[str, Any],
    field: str,
    *,
    label: str,
) -> None:
    digest = value.get(field)
    projection = copy.deepcopy(value)
    projection.pop(field, None)
    _require(
        isinstance(digest, str)
        and SHA256_RE.fullmatch(digest) is not None
        and contract.canonical_json_sha256(projection) == digest,
        f"{label} self-seal differs",
    )


def _validate_path_manifest(
    root: Path,
    rows: Any,
    *,
    expected_role: str,
    expected_paths: Sequence[str],
    label: str,
) -> dict[Path, str]:
    _require(isinstance(rows, list), f"{label} path manifest is missing")
    _require(
        tuple(row.get("path") for row in rows if isinstance(row, dict))
        == tuple(expected_paths),
        f"{label} exact ordered path scope differs",
    )
    result: dict[Path, str] = {}
    for row in rows:
        _require(
            isinstance(row, dict)
            and set(row) == {"role", "path", "byte_length", "sha256"}
            and row.get("role") == expected_role
            and isinstance(row.get("path"), str)
            and isinstance(row.get("byte_length"), int)
            and row["byte_length"] > 0
            and isinstance(row.get("sha256"), str)
            and SHA256_RE.fullmatch(row["sha256"]) is not None,
            f"{label} path row differs",
        )
        relative = Path(row["path"])
        _require(relative not in result, f"{label} path is duplicated: {relative}")
        content = _safe_file(root, relative).read_bytes()
        _require(
            len(content) == row["byte_length"]
            and sha256_bytes(content) == row["sha256"],
            f"{label} physical path binding differs: {relative}",
        )
        result[relative] = row["sha256"]
    return result


def _validate_result_path_authority(
    root: Path,
) -> dict[Path, str]:
    implementation = _parse_json_bytes(
        _safe_file(root, IMPLEMENTATION_RESULT_PATH).read_bytes(),
        label="FP008 implementation record",
    )
    verification = _parse_json_bytes(
        _safe_file(root, VERIFICATION_RESULT_PATH).read_bytes(),
        label="FP008 verification result",
    )
    _require(
        implementation.get("schema_version")
        == "walksafe.fp008-implementation-record.v1"
        and implementation.get("goal_id") == GOAL_ID
        and implementation.get("kind") == "IMPLEMENTATION_RECORD"
        and implementation.get("status") == "PASS"
        and implementation.get("scope_kind")
        == "EXACT_ORDERED_FP008_IMPLEMENTATION_PATH_SET"
        and implementation.get("completion_boundary")
        == EXPECTED_COMPLETION_BOUNDARY,
        "FP008 implementation record boundary differs",
    )
    _validate_sealed_document(
        implementation,
        "implementation_record_content_sha256",
        label="FP008 implementation record",
    )
    implementation_rows = implementation.get("changed_artifacts")
    implementation_map = _validate_path_manifest(
        root,
        implementation_rows,
        expected_role="FP008_IMPLEMENTATION_SOURCE",
        expected_paths=fp008_trace.IMPLEMENTATION_PATHS,
        label="FP008 implementation",
    )
    _require(
        implementation.get("exact_path_count") == len(implementation_map)
        and contract.canonical_json_sha256(implementation_rows)
        == implementation.get("implementation_content_set_sha256"),
        "FP008 implementation manifest aggregate differs",
    )

    _require(
        verification.get("schema_version")
        == "walksafe.fp008-verification-result.v1"
        and verification.get("goal_id") == GOAL_ID
        and verification.get("kind") == "VERIFICATION_RESULT"
        and verification.get("status") == "PASS"
        and verification.get("implementation_content_set_sha256")
        == implementation.get("implementation_content_set_sha256")
        and verification.get("completion_boundary")
        == EXPECTED_COMPLETION_BOUNDARY,
        "FP008 verification result boundary differs",
    )
    _validate_sealed_document(
        verification,
        "verification_result_content_sha256",
        label="FP008 verification result",
    )
    verification_rows = verification.get("verification_input_manifest")
    verification_map = _validate_path_manifest(
        root,
        verification_rows,
        expected_role="FP008_VERIFICATION_INPUT",
        expected_paths=fp008_trace.VERIFICATION_INPUT_PATHS,
        label="FP008 verification input",
    )
    _require(
        contract.canonical_json_sha256(verification_rows)
        == verification.get("verification_input_content_set_sha256"),
        "FP008 verification-input aggregate differs",
    )
    for relative, digest in verification_map.items():
        _merge_sha256_binding(
            implementation_map,
            relative,
            digest,
            label="implementation/verification manifest",
        )
    return implementation_map


def _validate_builder_closure(root: Path) -> None:
    try:
        context = fp008_review.prepare_review_context(root)
        attestation_raw = _safe_file(root, REVIEW_ATTESTATION_PATH).read_bytes()
        attestation = _parse_json_bytes(
            attestation_raw,
            label="FP008 review attestation",
        )
        fp008_review.validate_attestation(attestation, context)
        expected = fp008_review.build_post_review_outputs(
            context,
            attestation,
            attestation_raw,
        )
    except (OSError, RuntimeError, TypeError, ValueError) as exc:
        raise CompletionApplyError(
            f"FP008 producer/review closure differs: {exc}"
        ) from exc
    for relative, content in expected.items():
        _require(
            _safe_file(root, relative).read_bytes() == content.encode("utf-8"),
            f"FP008 post-review output rebuild differs: {relative}",
        )


def _consumer_binding_map(receipt: dict[str, Any]) -> dict[str, dict[str, Any]]:
    raw = receipt.get("downstream_consumer_bindings")
    _require(isinstance(raw, list), "completion consumer bindings are missing")
    result: dict[str, dict[str, Any]] = {}
    for row in raw:
        _require(isinstance(row, dict), "completion consumer binding is malformed")
        role = row.get("role")
        _require(
            isinstance(role, str) and role not in result,
            "completion consumer role differs",
        )
        result[role] = row
    return result


def _validate_receipt(
    root: Path,
    source: dict[str, Any],
    receipt: dict[str, Any],
    bindings: Mapping[str, dict[str, str]],
    output_pins: Mapping[Path, str],
) -> tuple[str, str, dict[Path, str]]:
    expected = {
        "schema_version": "1.0",
        "document_id": COMPLETION_DOCUMENT_ID,
        "evidence_type": "WORK_ITEM_EXECUTION_RECEIPT",
        "status": "ACCEPTED",
        "result": "PASS",
        "target_goal_id": GOAL_ID,
        "target_goal_content_sha256": GOAL_SHA256,
        "work_item_id": WORK_ITEM_ID,
        "source_policy_ids": ["FP-008"],
        "gap_ids": ["GAP-017"],
        "target_completion_level": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
        "execution_start_event_sha256": SOURCE_START_EVENT_SHA256,
    }
    for field, wanted in expected.items():
        _require(receipt.get(field) == wanted, f"completion receipt {field} differs")

    history = source["goal_execution"]["transition_history"]
    start, resume = history[-2:]
    _require(
        receipt.get("implementation_start_gate_binding")
        == START_RECEIPT_GATE_BINDING
        and start.get("implementation_start_gate_binding") == START_GATE_BINDING,
        "completion receipt start-gate binding differs",
    )
    _require(
        receipt.get("execution_session_gate_binding")
        == RESUME_RECEIPT_GATE_BINDING
        and resume.get("implementation_start_gate_binding")
        == RESUME_GATE_BINDING,
        "completion receipt resume-gate binding differs",
    )
    _require(
        receipt.get("execution_start_event")
        == {
            "sequence": 47,
            "event_id": SOURCE_START_EVENT_ID,
            "event_type": "GOAL_STARTED",
            "event_sha256": SOURCE_START_EVENT_SHA256,
        },
        "completion execution-start event differs",
    )
    _require(
        receipt.get("execution_session_event")
        == {
            "sequence": 48,
            "event_id": SOURCE_RESUME_EVENT_ID,
            "event_type": "WORK_SESSION_RESUMED",
            "event_sha256": SOURCE_RESUME_EVENT_SHA256,
        },
        "completion execution-session event differs",
    )

    physical: dict[Path, str] = {}
    for label, gate_binding in (
        ("implementation start gate", START_RECEIPT_GATE_BINDING),
        ("implementation resume gate", RESUME_RECEIPT_GATE_BINDING),
    ):
        relative, digest = _physical_sha256_binding(
            root,
            gate_binding,
            label=label,
            expected_path=Path(gate_binding["path"]),
        )
        physical[relative] = digest

    executor = receipt.get("executor")
    reviewer = receipt.get("reviewer")
    _require(
        isinstance(executor, dict) and isinstance(reviewer, dict),
        "completion actors are missing",
    )
    _require(
        isinstance(executor.get("id"), str)
        and bool(executor["id"])
        and isinstance(reviewer.get("id"), str)
        and bool(reviewer["id"])
        and executor.get("id") != reviewer.get("id")
        and executor.get("task") != reviewer.get("task"),
        "completion executor/reviewer separation differs",
    )
    _require(
        reviewer.get("separate_internal_review_pass") is True
        and reviewer.get("external_independence_claimed") is False
        and reviewer.get("decision") == "APPROVED"
        and reviewer.get("authority") == "INTERNAL_REPOSITORY_CONTROL",
        "completion internal review boundary differs",
    )
    relative, digest = _physical_sha256_binding(
        root,
        receipt.get("reviewer_provenance"),
        label="completion reviewer provenance",
        expected_path=INDEPENDENT_REVIEW_PATH,
    )
    physical[relative] = digest
    review_document = _parse_json_bytes(
        _safe_file(root, INDEPENDENT_REVIEW_PATH).read_bytes(),
        label="FP008 independent review",
    )
    relative, digest = _physical_sha256_binding(
        root,
        review_document.get("attestation_provenance"),
        label="completion review attestation provenance",
        expected_path=REVIEW_ATTESTATION_PATH,
    )
    physical[relative] = digest

    result_evidence = receipt.get("result_evidence")
    _require(
        isinstance(result_evidence, list) and len(result_evidence) == 3,
        "completion result evidence set differs",
    )
    expected_result_paths = {
        "IMPLEMENTATION_RECORD": IMPLEMENTATION_RESULT_PATH,
        "VERIFICATION_RESULT": VERIFICATION_RESULT_PATH,
        "SUCCESSOR_TRACE": SUCCESSOR_TRACE_PATH,
    }
    result_by_kind = {
        row.get("kind"): row
        for row in result_evidence
        if isinstance(row, dict) and isinstance(row.get("kind"), str)
    }
    _require(
        set(result_by_kind) == set(expected_result_paths),
        "completion result evidence kinds differ",
    )
    for kind, expected_path in expected_result_paths.items():
        relative, digest = _physical_sha256_binding(
            root,
            result_by_kind[kind],
            label=f"completion {kind}",
            expected_path=expected_path,
        )
        physical[relative] = digest

    _require(
        receipt.get("completion_boundary") == EXPECTED_COMPLETION_BOUNDARY,
        "completion formal/external/release boundary differs",
    )

    window = receipt.get("execution_window")
    _require(isinstance(window, dict), "completion execution window is missing")
    start_event_at = _parse_time(start.get("occurred_at"), label="seq47 occurred_at")
    resume_event_at = _parse_time(resume.get("occurred_at"), label="seq48 occurred_at")
    started_at = _parse_time(
        window.get("started_at"),
        label="execution started_at",
        allow_fractional_seconds=True,
    )
    ended_at = _parse_time(
        window.get("ended_at"),
        label="execution ended_at",
        allow_fractional_seconds=True,
    )
    completed_at = _parse_time(receipt.get("completed_at"), label="completion completed_at")
    reviewed_at = _parse_time(reviewer.get("decided_at"), label="reviewer decided_at")
    generated_at = _parse_time(receipt.get("generated_at"), label="completion generated_at")
    _require(
        start_event_at <= resume_event_at <= started_at <= ended_at
        <= completed_at <= reviewed_at <= generated_at,
        "completion receipt chronology differs",
    )

    manifest = receipt.get("output_evidence_manifest")
    _require(isinstance(manifest, list), "completion evidence manifest is missing")
    _require(
        contract.canonical_json_sha256(manifest)
        == receipt.get("output_evidence_manifest_sha256"),
        "completion evidence manifest seal differs",
    )
    manifest_paths: set[Path] = set()
    for row in manifest:
        relative, digest = _physical_sha256_binding(
            root,
            row,
            label="completion output evidence",
        )
        _require(relative not in manifest_paths, "completion evidence path is duplicated")
        manifest_paths.add(relative)
        physical[relative] = digest
        _require(
            output_pins.get(relative) == digest,
            f"completion output pin differs: {relative}",
        )
    _require(
        tuple(Path(row["path"]) for row in manifest)
        == RECEIPT_MANIFEST_PATHS
        and manifest_paths == set(RECEIPT_MANIFEST_PATHS),
        "completion output evidence manifest path set or order differs",
    )

    consumers = _consumer_binding_map(receipt)
    raw_consumers = receipt.get("downstream_consumer_bindings")
    _require(
        isinstance(raw_consumers, list)
        and tuple(row.get("role") for row in raw_consumers if isinstance(row, dict))
        == CONSUMER_ROLE_ORDER
        and set(consumers) == set(CONSUMER_ROLE_ORDER),
        "completion consumer role set or order differs",
    )
    for spec in ARTIFACT_SPECS:
        if spec.role == COMPLETION_ROLE:
            continue
        consumer = consumers[spec.consumer_role]
        _require(
            consumer.get("path") == spec.path.as_posix()
            and consumer.get("sha256") == bindings[spec.role]["file_sha256"],
            f"completion consumer binding differs: {spec.role}",
        )
    for role in (
        "IMPLEMENTATION_MANIFEST",
        "EXACT257_R013_LEDGER",
        "EXACT257_R013_EVIDENCE",
        "EXACT257_R013_CHECK_RECEIPT",
    ):
        _require(
            consumers[role].get("path") == CONSUMER_PATH_BY_ROLE[role].as_posix(),
            f"completion noncanonical consumer path differs: {role}",
        )
        _require(
            consumers[role].get("sha256")
            == output_pins[CONSUMER_PATH_BY_ROLE[role]],
            f"completion noncanonical consumer SHA-256 differs: {role}",
        )

    update_time = max(resume_event_at + timedelta(seconds=1), generated_at)
    _require(
        update_time - generated_at <= timedelta(hours=1),
        "completion evidence is stale for seq49",
    )
    return (
        update_time.isoformat(),
        (update_time + timedelta(seconds=1)).isoformat(),
        physical,
    )


def _validate_next_pointer(gap: dict[str, Any], backlog: dict[str, Any]) -> None:
    _require(
        backlog.get("next_single_action")
        == {
            "epic_id": "EPIC-03",
            "source_policy_id": NEXT_POLICY_ID,
            "gap_id": NEXT_GAP_ID,
            "status": "PLANNED_NEXT",
            "action": NEXT_ACTION,
        },
        "r024 FP046/GAP055 next_single_action differs",
    )
    adjacent = gap.get("reassessment_scope", {}).get("next_adjacent_gap")
    _require(
        isinstance(adjacent, dict)
        and adjacent.get("source_policy_id") == NEXT_POLICY_ID
        and adjacent.get("gap_id") == NEXT_GAP_ID,
        "r024 FP046/GAP055 next-adjacent pointer differs",
    )
    assessments = gap.get("assessments")
    gap017 = (
        [
            row
            for row in assessments
            if isinstance(row, dict) and row.get("gap_id") == "GAP-017"
        ]
        if isinstance(assessments, list)
        else []
    )
    _require(
        len(gap017) == 1
        and gap017[0].get("source_policy_id") == "FP-008"
        and gap017[0].get("status") == "PARTIAL",
        "r024 GAP-017 reassessment differs",
    )


def load_completion_evidence(
    root: Path,
    source: dict[str, Any],
    pins: Mapping[str, str],
    output_pins: Mapping[Path, str],
) -> CompletionEvidence:
    _validate_builder_closure(root)
    documents, bindings = _load_evidence_documents(root, pins)
    _validate_next_pointer(
        documents["IMPLEMENTATION_GAP"],
        documents["IMPLEMENTATION_BACKLOG"],
    )
    update_at, completion_at, physical = _validate_receipt(
        root,
        source,
        documents[COMPLETION_ROLE],
        bindings,
        output_pins,
    )

    source_sha256_by_path, source_physical = _source_snapshot_authority(
        root,
        source,
    )
    implementation_sha256_by_path = _validate_result_path_authority(root)
    approved_sha256_by_path = dict(implementation_sha256_by_path)
    for spec in ARTIFACT_SPECS:
        _merge_sha256_binding(
            approved_sha256_by_path,
            spec.path,
            pins[spec.role],
            label=f"canonical output {spec.role}",
        )
    for relative, digest in output_pins.items():
        _merge_sha256_binding(
            approved_sha256_by_path,
            relative,
            digest,
            label="separately pinned FP008 output",
        )
    for relative in SEQUENCE_AUTHORITY_PATHS:
        content = _safe_file(root, relative).read_bytes()
        _merge_sha256_binding(
            approved_sha256_by_path,
            relative,
            sha256_bytes(content),
            label="completion sequence implementation",
        )
    for relative, digest in CONTROLLED_CHECKER_DELTA_SHA256_BY_PATH.items():
        _merge_sha256_binding(
            approved_sha256_by_path,
            relative,
            digest,
            label="sealed FP008 completion checker delta",
        )

    for relative, digest in approved_sha256_by_path.items():
        _require(
            sha256_bytes(_safe_file(root, relative).read_bytes()) == digest,
            f"approved final path SHA-256 differs: {relative}",
        )
    final_managed_sha256_by_path = dict(source_sha256_by_path)
    final_managed_sha256_by_path.update(approved_sha256_by_path)
    for relative, digest in final_managed_sha256_by_path.items():
        _require(
            sha256_bytes(_safe_file(root, relative).read_bytes()) == digest,
            f"unapproved or stale final managed path differs: {relative}",
        )

    authorized_delta_by_path = {
        relative: {
            "source_sha256": source_sha256_by_path[relative],
            "final_sha256": digest,
        }
        for relative, digest in approved_sha256_by_path.items()
        if relative in source_sha256_by_path
        and source_sha256_by_path[relative] != digest
    }
    added_managed_paths = tuple(
        sorted(
            (
                relative
                for relative in approved_sha256_by_path
                if relative not in source_sha256_by_path
            ),
            key=Path.as_posix,
        )
    )

    for relative, digest in source_physical.items():
        _merge_sha256_binding(
            physical,
            relative,
            digest,
            label="seq48 source authority",
        )
    for relative, digest in TRANSITIVE_INPUT_SHA256_BY_PATH.items():
        _require(
            sha256_bytes(_safe_file(root, relative).read_bytes()) == digest,
            f"FP008 transitive predecessor SHA-256 differs: {relative}",
        )
        _merge_sha256_binding(
            physical,
            relative,
            digest,
            label="FP008 transitive predecessor",
        )
    for binding, state_path, state_sha, label in (
        (
            START_GATE_BINDING,
            START_GATE_REPOSITORY_STATE_PATH,
            START_GATE_REPOSITORY_STATE_SHA256,
            "FP008 initial gate",
        ),
        (
            RESUME_GATE_BINDING,
            RESUME_GATE_REPOSITORY_STATE_PATH,
            RESUME_GATE_REPOSITORY_STATE_SHA256,
            "FP008 resume gate",
        ),
    ):
        gate_closure = _collect_gate_closure(
            root,
            binding,
            repository_state_path=state_path,
            repository_state_sha256=state_sha,
            label=label,
        )
        for relative, digest in gate_closure.items():
            _merge_sha256_binding(
                physical,
                relative,
                digest,
                label=label,
            )
    for relative, digest in final_managed_sha256_by_path.items():
        _merge_sha256_binding(
            physical,
            relative,
            digest,
            label="final managed closure",
        )
    return CompletionEvidence(
        documents_by_role=documents,
        bindings_by_role=bindings,
        receipt=documents[COMPLETION_ROLE],
        update_occurred_at=update_at,
        completion_occurred_at=completion_at,
        physical_sha256_by_path=physical,
        final_managed_sha256_by_path=final_managed_sha256_by_path,
        authorized_delta_by_path=authorized_delta_by_path,
        added_managed_paths=added_managed_paths,
    )


def _project_canonical_bindings(
    source: dict[str, Any],
    evidence: CompletionEvidence,
) -> list[dict[str, Any]]:
    raw = source.get("canonical_bindings")
    _require(isinstance(raw, list), "source canonical bindings are missing")
    projected: list[dict[str, Any]] = []
    seen: set[str] = set()
    for source_binding in raw:
        _require(isinstance(source_binding, dict), "source canonical binding is malformed")
        role = source_binding.get("role")
        _require(isinstance(role, str) and role not in seen, "source canonical role differs")
        seen.add(role)
        if role not in SPEC_BY_ROLE or role == COMPLETION_ROLE:
            projected.append(copy.deepcopy(source_binding))
            continue
        spec = SPEC_BY_ROLE[role]
        binding = copy.deepcopy(source_binding)
        binding.update(evidence.bindings_by_role[role])
        binding["identity_json_path"] = spec.identity_json_path
        binding["mutable"] = spec.mutable
        projected.append(binding)
    _require(COMPLETION_ROLE not in seen, "FP008 completion role already exists")
    completion = SPEC_BY_ROLE[COMPLETION_ROLE]
    projected.append(
        {
            **evidence.bindings_by_role[COMPLETION_ROLE],
            "identity_json_path": completion.identity_json_path,
            "mutable": completion.mutable,
        }
    )
    _require(len(projected) == 40, "projected canonical binding count differs")
    return projected


def _project_gap_snapshot(gap: dict[str, Any], backlog: dict[str, Any]) -> dict[str, Any]:
    gap_metadata = gap.get("metadata")
    backlog_metadata = backlog.get("metadata")
    assessments = gap.get("assessments")
    epics = backlog.get("epics")
    status_counts = gap.get("summary", {}).get("status_counts")
    implementation_snapshot = gap.get("implementation_snapshot")
    _require(
        isinstance(gap_metadata, dict)
        and isinstance(backlog_metadata, dict)
        and isinstance(assessments, list)
        and isinstance(epics, list)
        and isinstance(status_counts, dict)
        and isinstance(implementation_snapshot, dict),
        "r024 checkpoint Gap snapshot source differs",
    )
    epic_status_counts: dict[str, int] = {}
    for epic in epics:
        _require(isinstance(epic, dict), "r024 backlog epic is malformed")
        status_value = epic.get("current_status")
        _require(isinstance(status_value, str) and status_value, "r024 epic status differs")
        epic_status_counts[status_value] = epic_status_counts.get(status_value, 0) + 1
    return {
        "report_id": gap_metadata.get("report_id"),
        "report_version": gap_metadata.get("version"),
        "assessment_count": len(assessments),
        "status_counts": copy.deepcopy(status_counts),
        "backlog_id": backlog_metadata.get("backlog_id"),
        "epic_count": len(epics),
        "epic_status_counts": epic_status_counts,
        "implementation_snapshot": copy.deepcopy(implementation_snapshot),
    }


def _runtime_projection(
    state: dict[str, Any],
    *,
    artifact_queue: dict[str, Any],
    completion_boundary: dict[str, Any],
) -> dict[str, Any]:
    return {
        "focus_goal_id": state["focus_goal_id"],
        "focus_goal_path": state["focus_goal_path"],
        "focus_work_item_id": state["focus_work_item_id"],
        "focus_source": state["focus_source"],
        "ready_frontier_goal_ids": copy.deepcopy(state["ready_frontier_goal_ids"]),
        "blocked_goal_ids": copy.deepcopy(state["blocked_goal_ids"]),
        "pending_questions": copy.deepcopy(state["pending_questions"]),
        "open_question_count": state["open_question_count"],
        "artifact_work_queue_sha256": contract.canonical_json_sha256(artifact_queue),
        "completion_boundary_sha256": contract.canonical_json_sha256(completion_boundary),
        "activation_status": state["activation_status"],
        "package_status": state["package_status"],
    }


def derive_runtime(
    root: Path,
    checkpoint: dict[str, Any],
    ready_frontier_goal_ids: list[str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    return runtime_model.derive_queue_and_boundary(
        root,
        checkpoint,
        ready_frontier_goal_ids=ready_frontier_goal_ids,
    )


def _update_working_snapshot(
    root: Path,
    checkpoint: dict[str, Any],
    evidence: CompletionEvidence,
    snapshot_hasher: Callable[[Path, list[str]], tuple[str, str]],
) -> None:
    snapshot = checkpoint.get("working_tree_snapshot")
    _require(isinstance(snapshot, dict), "working snapshot is missing")
    paths = sorted(
        relative.as_posix()
        for relative in evidence.final_managed_sha256_by_path
    )
    expected_hashes = _snapshot_hashes_from_digests(
        evidence.final_managed_sha256_by_path
    )
    observed_hashes = snapshot_hasher(root, paths)
    _require(
        observed_hashes == expected_hashes,
        "final managed path/content aggregate differs from approved closure",
    )
    path_hash, content_hash = expected_hashes
    snapshot["managed_changed_paths"] = paths
    snapshot["managed_changed_path_count"] = len(paths)
    snapshot["path_set_sha256"] = path_hash
    snapshot["content_set_sha256"] = content_hash
    snapshot["scope"] = FINAL_SCOPE

    handoff = checkpoint.get("session_handoff")
    _require(isinstance(handoff, dict), "session handoff is missing")
    source_mirror = handoff.get("source_commit_or_snapshot")
    _require(isinstance(source_mirror, dict), "session source snapshot is missing")
    handoff["changed_files"] = copy.deepcopy(paths)
    source_mirror["file_count"] = len(paths)
    source_mirror["path_set_sha256"] = path_hash
    source_mirror["content_set_sha256"] = content_hash


def project_seq49_50(
    root: Path,
    source: dict[str, Any],
    evidence: CompletionEvidence,
    *,
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]],
        tuple[dict[str, Any], dict[str, Any]],
    ] = derive_runtime,
    snapshot_hasher: Callable[[Path, list[str]], tuple[str, str]] = (
        contract.working_snapshot_hashes
    ),
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    checkpoint = copy.deepcopy(source)
    checkpoint["canonical_bindings"] = _project_canonical_bindings(source, evidence)
    checkpoint["implementation_gap_snapshot"] = _project_gap_snapshot(
        evidence.documents_by_role["IMPLEMENTATION_GAP"],
        evidence.documents_by_role["IMPLEMENTATION_BACKLOG"],
    )
    state = checkpoint["goal_execution"]
    canonical_snapshot = contract.canonical_binding_snapshot(checkpoint)
    _require(len(canonical_snapshot) == 40, "canonical snapshot role count differs")

    queue49, boundary49 = runtime_deriver(
        root,
        checkpoint,
        [GOAL_ID, PARENT_GOAL_ID, EPIC12_GOAL_ID],
    )
    state["artifact_work_queue"] = queue49
    state["completion_boundary"] = boundary49
    completion_binding = evidence.bindings_by_role[COMPLETION_ROLE]
    update_at = evidence.update_occurred_at
    update_event: dict[str, Any] = {
        "sequence": 49,
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
        "runtime_after": _runtime_projection(
            state,
            artifact_queue=queue49,
            completion_boundary=boundary49,
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": list(CHANGED_ROLES),
        "previous_event_sha256": SOURCE_RESUME_EVENT_SHA256,
        "produced_by_goal_id": GOAL_ID,
        "produced_binding_roles": list(PRODUCED_ROLES),
        "producer_completion_receipt_binding": copy.deepcopy(completion_binding),
        "changed_binding_roles": list(CHANGED_ROLES),
        "changed_subject_ids_by_role": copy.deepcopy(CHANGED_SUBJECT_IDS_BY_ROLE),
        "producer_output_subject_ids_by_role": copy.deepcopy(
            PRODUCER_OUTPUT_SUBJECT_IDS_BY_ROLE
        ),
        "impact_closure_goal_ids": [PARENT_GOAL_ID],
        "impact_disposition_by_goal": {
            PARENT_GOAL_ID: {
                "result": "REVALIDATION_REFRESH_REQUIRED",
                "target_status": "READY",
            }
        },
        "reopened_completion_event_sha256_by_goal": {},
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_snapshot),
    }
    update_event["event_sha256"] = contract.event_sha256(update_event)
    _require(set(update_event) == UPDATE_EVENT_FIELDS, "seq49 event field set differs")
    state["transition_history"].append(update_event)
    state["pending_producer_completion_goal_id"] = GOAL_ID

    state["status_by_goal"][GOAL_ID] = "COMPLETE_AT_TARGET"
    state["focus_goal_id"] = PARENT_GOAL_ID
    state["focus_goal_path"] = PARENT_GOAL_PATH
    state["focus_work_item_id"] = ""
    state["focus_source"] = "WORKSTREAM_GRAPH"
    state["ready_frontier_goal_ids"] = [PARENT_GOAL_ID, EPIC12_GOAL_ID]
    queue50, boundary50 = runtime_deriver(
        root,
        checkpoint,
        [PARENT_GOAL_ID, EPIC12_GOAL_ID],
    )
    state["artifact_work_queue"] = queue50
    state["completion_boundary"] = boundary50
    completion_evidence = copy.deepcopy(state["completion_evidence_by_goal"])
    completion_evidence[GOAL_ID] = [COMPLETION_ROLE]
    completion_at = evidence.completion_occurred_at
    completion_event: dict[str, Any] = {
        "sequence": 50,
        "event_id": COMPLETION_EVENT_ID,
        "event_type": "GOAL_COMPLETED",
        "occurred_on": datetime.fromisoformat(completion_at).date().isoformat(),
        "occurred_at": completion_at,
        "previous_focus_goal_id": GOAL_ID,
        "previous_focus_content_sha256": GOAL_SHA256,
        "focus_goal_id": PARENT_GOAL_ID,
        "focus_goal_content_sha256": PARENT_GOAL_SHA256,
        "subject_goal_id": GOAL_ID,
        "from_status": "IN_PROGRESS",
        "to_status": "COMPLETE_AT_TARGET",
        "static_plan_manifest_sha256": MANIFEST_SHA256,
        "status_changes": {GOAL_ID: "COMPLETE_AT_TARGET"},
        "runtime_after": _runtime_projection(
            state,
            artifact_queue=queue50,
            completion_boundary=boundary50,
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            row["resolution_id"] for row in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [COMPLETION_ROLE],
        "previous_event_sha256": update_event["event_sha256"],
        "canonical_update_event_sha256": update_event["event_sha256"],
        "completion_receipt_binding": copy.deepcopy(completion_binding),
        "completion_evidence_bindings": {
            COMPLETION_ROLE: copy.deepcopy(completion_binding)
        },
        "completion_evidence_by_goal_after": completion_evidence,
        "canonical_binding_snapshot_after": copy.deepcopy(canonical_snapshot),
    }
    completion_event["event_sha256"] = contract.event_sha256(completion_event)
    _require(
        set(completion_event) == COMPLETION_EVENT_FIELDS,
        "seq50 event field set differs",
    )
    state["transition_history"].append(completion_event)
    state["transition_history_anchor_sha256"] = completion_event["event_sha256"]
    state["validation_cutoff_at"] = completion_at
    state["completion_evidence_by_goal"] = completion_evidence
    state["pending_producer_completion_goal_id"] = ""

    current = checkpoint["current_work"]
    current["work_item_id"] = NEXT_WORK_ITEM_ID
    current["last_completed_work_summary"] = (
        "FP008/GAP-017 Android administrator review and manual-delivery "
        "repository-internal implementation, regression, security review, and "
        "successor evidence completed"
    )
    current["current_focus"] = FINAL_CURRENT_FOCUS
    current["next_action"] = NEXT_ACTION
    current["release_completion_claimed"] = False

    handoff = checkpoint["session_handoff"]
    handoff["current_epic"] = FINAL_HANDOFF_EPIC
    handoff["next_single_action"] = NEXT_ACTION
    handoff["last_updated_by_work_item"] = WORK_ITEM_ID
    handoff["last_verification_status"] = (
        "PASS_INTERNAL_ONLY_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
    )
    _update_working_snapshot(root, checkpoint, evidence, snapshot_hasher)
    return checkpoint, update_event, completion_event


def _changed_canonical_roles(
    source: dict[str, Any],
    projected: dict[str, Any],
) -> list[str]:
    before = contract.canonical_binding_snapshot(source)
    after = contract.canonical_binding_snapshot(projected)
    return sorted(
        role
        for role in set(before) | set(after)
        if before.get(role) != after.get(role)
    )


def validate_exact_projection(
    source: dict[str, Any],
    projected: dict[str, Any],
    evidence: CompletionEvidence,
) -> None:
    source_state = source["goal_execution"]
    state = projected["goal_execution"]
    history = state.get("transition_history")
    _require(isinstance(history, list) and len(history) == 50, "projection is not seq50")
    _require(history[:-2] == source_state["transition_history"], "pre-seq49 history changed")
    update, completion = history[-2:]
    _require(
        update.get("sequence") == 49 and completion.get("sequence") == 50,
        "seq49/50 adjacency differs",
    )
    _require(update.get("event_id") == CANONICAL_UPDATE_EVENT_ID, "seq49 id differs")
    _require(completion.get("event_id") == COMPLETION_EVENT_ID, "seq50 id differs")
    _require(update.get("event_type") == "CANONICAL_BINDINGS_UPDATED", "seq49 type differs")
    _require(completion.get("event_type") == "GOAL_COMPLETED", "seq50 type differs")
    _require(update.get("previous_event_sha256") == SOURCE_RESUME_EVENT_SHA256, "seq49 lineage differs")
    _require(update.get("event_sha256") == contract.event_sha256(update), "seq49 seal differs")
    _require(completion.get("event_sha256") == contract.event_sha256(completion), "seq50 seal differs")
    _require(
        completion.get("previous_event_sha256") == update.get("event_sha256")
        and completion.get("canonical_update_event_sha256") == update.get("event_sha256"),
        "event inserted between seq49/50",
    )
    _require(set(update) == UPDATE_EVENT_FIELDS, "seq49 exact field set differs")
    _require(set(completion) == COMPLETION_EVENT_FIELDS, "seq50 exact field set differs")
    _require(update.get("status_changes") == {}, "seq49 changed goal status")
    _require(update.get("changed_binding_roles") == CHANGED_ROLES, "seq49 changed roles differ")
    _require(update.get("evidence_refs") == CHANGED_ROLES, "seq49 evidence roles differ")
    _require(update.get("produced_binding_roles") == PRODUCED_ROLES, "seq49 producer authority differs")
    _require(
        update.get("changed_subject_ids_by_role") == CHANGED_SUBJECT_IDS_BY_ROLE,
        "seq49 changed subjects differ",
    )
    _require(
        update.get("producer_output_subject_ids_by_role")
        == PRODUCER_OUTPUT_SUBJECT_IDS_BY_ROLE,
        "seq49 producer subjects differ",
    )
    _require(not (set(CHANGED_ROLES) & FORBIDDEN_CHANGED_ROLES), "forbidden role changed")
    _require(
        _changed_canonical_roles(source, projected) == CHANGED_ROLES,
        "physical canonical role delta differs",
    )
    _require(len(projected.get("canonical_bindings", [])) == 40, "final canonical count differs")
    snapshot = contract.canonical_binding_snapshot(projected)
    _require(len(snapshot) == 40, "final canonical snapshot count differs")
    _require(update.get("canonical_binding_snapshot_after") == snapshot, "seq49 snapshot differs")
    _require(completion.get("canonical_binding_snapshot_after") == snapshot, "seq50 snapshot differs")
    for role in CHANGED_ROLES:
        _require(
            snapshot.get(role) == evidence.bindings_by_role[role],
            f"canonical evidence binding differs: {role}",
        )
    _require(
        update.get("producer_completion_receipt_binding")
        == evidence.bindings_by_role[COMPLETION_ROLE],
        "seq49 completion receipt binding differs",
    )
    _require(
        completion.get("completion_receipt_binding")
        == evidence.bindings_by_role[COMPLETION_ROLE],
        "seq50 completion receipt binding differs",
    )
    _require(
        state.get("status_by_goal", {}).get(GOAL_ID) == "COMPLETE_AT_TARGET",
        "FP008 final status differs",
    )
    _require(state.get("focus_goal_id") == PARENT_GOAL_ID, "final focus differs")
    _require(
        state.get("ready_frontier_goal_ids") == [PARENT_GOAL_ID, EPIC12_GOAL_ID],
        "final ready frontier differs",
    )
    _require(
        state.get("pending_producer_completion_goal_id") in {None, ""},
        "producer transaction remains open",
    )
    _require(
        state.get("completion_evidence_by_goal", {}).get(GOAL_ID)
        == [COMPLETION_ROLE]
        and completion.get("completion_evidence_by_goal_after")
        == state.get("completion_evidence_by_goal"),
        "FP008 completion evidence differs",
    )
    _require(
        projected["current_work"].get("work_item_id") == NEXT_WORK_ITEM_ID
        and projected["current_work"].get("next_action") == NEXT_ACTION
        and projected["session_handoff"].get("next_single_action") == NEXT_ACTION,
        "FP046/GAP055 pointer differs",
    )
    _require(
        projected.get("implementation_gap_snapshot")
        == _project_gap_snapshot(
            evidence.documents_by_role["IMPLEMENTATION_GAP"],
            evidence.documents_by_role["IMPLEMENTATION_BACKLOG"],
        ),
        "r024 checkpoint Gap snapshot differs",
    )

    working = projected.get("working_tree_snapshot", {})
    handoff = projected.get("session_handoff", {})
    mirror = handoff.get("source_commit_or_snapshot", {})
    expected_managed_paths = sorted(
        relative.as_posix()
        for relative in evidence.final_managed_sha256_by_path
    )
    expected_path_hash, expected_content_hash = _snapshot_hashes_from_digests(
        evidence.final_managed_sha256_by_path
    )
    _require(
        working.get("managed_changed_paths") == expected_managed_paths
        and working.get("managed_changed_path_count") == len(expected_managed_paths)
        and working.get("path_set_sha256") == expected_path_hash
        and working.get("content_set_sha256") == expected_content_hash
        and handoff.get("changed_files") == working.get("managed_changed_paths")
        and mirror.get("file_count") == working.get("managed_changed_path_count")
        and mirror.get("path_set_sha256") == working.get("path_set_sha256")
        and mirror.get("content_set_sha256") == working.get("content_set_sha256"),
        "working snapshot and handoff mirror differ",
    )

    for field in ("authority_boundary", "verification_boundary", "approved_state"):
        _require(projected.get(field) == source.get(field), f"{field} credit changed")
    _require(
        state.get("standing_execution_authority")
        == source_state.get("standing_execution_authority"),
        "generic POLICY_GAP_WORK authority expanded",
    )
    _require(
        projected.get("approved_state", {}).get("release_status") == "NOT_ELIGIBLE"
        and projected.get("verification_boundary", {}).get("formal_test_pass_claimed") is False
        and projected.get("verification_boundary", {}).get("release_eligible") is False
        and evidence.receipt.get("completion_boundary") == EXPECTED_COMPLETION_BOUNDARY,
        "formal/external/device/release credit was promoted",
    )

    allowed_top = {
        "canonical_bindings",
        "current_work",
        "goal_execution",
        "implementation_gap_snapshot",
        "session_handoff",
        "working_tree_snapshot",
    }
    for key in set(source) | set(projected):
        if key not in allowed_top:
            _require(source.get(key) == projected.get(key), f"unauthorized top-level mutation: {key}")
    allowed_state = {
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
    }
    for key in set(source_state) | set(state):
        if key not in allowed_state:
            _require(source_state.get(key) == state.get(key), f"unauthorized runtime mutation: {key}")
    errors = contract.validate_generic_event_order(history)
    _require(not errors, "projected generic history differs: " + "; ".join(errors))


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
    checkpoint_dir = root / CHECKPOINT_RELATIVE.parent
    descriptor, temporary_name = tempfile.mkstemp(
        dir=checkpoint_dir,
        prefix=".walksafe-fp008-seq49-50-preflight.",
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
        _require(
            not continuation_errors,
            "projected continuation v2.4 check failed: "
            + "; ".join(continuation_errors),
        )
        graph_errors = goal_graph_checker(root, relative)
        _require(
            not graph_errors,
            "projected goal-graph v2.4 check failed: " + "; ".join(graph_errors),
        )
    finally:
        temporary.unlink(missing_ok=True)


def prepare_projection(
    root: Path,
    *,
    artifact_sha256_by_role: Mapping[str, str] | None = None,
    output_sha256_by_path: Mapping[Path | str, str] | None = None,
    source_validator: Callable[[bytes, dict[str, Any]], None] = require_exact_source,
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]],
        tuple[dict[str, Any], dict[str, Any]],
    ] = derive_runtime,
    snapshot_hasher: Callable[[Path, list[str]], tuple[str, str]] = (
        contract.working_snapshot_hashes
    ),
    continuation_checker: Callable[[Path, Path], list[str]] = run_continuation_checker,
    goal_graph_checker: Callable[[Path, Path], list[str]] = run_goal_graph_checker,
) -> PreparedProjection:
    root = root.resolve(strict=True)
    pins = _resolve_sha256_pins(artifact_sha256_by_role)
    output_pins = _resolve_path_sha256_pins(
        output_sha256_by_path,
        production=PINNED_PRODUCTION_SHA256_BY_PATH,
        expected_paths=ADDITIONAL_OUTPUT_PIN_PATHS,
        label="FP008 output",
    )
    checkpoint_path = _safe_file(root, CHECKPOINT_RELATIVE)
    source_bytes = checkpoint_path.read_bytes()
    source = _parse_json_bytes(source_bytes, label="source checkpoint")
    source_validator(source_bytes, source)
    _require(checkpoint_path.read_bytes() == source_bytes, "source changed during validation")
    evidence = load_completion_evidence(root, source, pins, output_pins)
    _require(checkpoint_path.read_bytes() == source_bytes, "source changed during evidence validation")
    projected, update, completion = project_seq49_50(
        root,
        source,
        evidence,
        runtime_deriver=runtime_deriver,
        snapshot_hasher=snapshot_hasher,
    )
    validate_exact_projection(source, projected, evidence)
    projected_bytes = json_bytes(projected)
    validate_projected_with_both_checkers(
        root,
        projected_bytes,
        continuation_checker=continuation_checker,
        goal_graph_checker=goal_graph_checker,
    )
    _require(checkpoint_path.read_bytes() == source_bytes, "source changed during checker preflight")
    return PreparedProjection(
        root=root,
        checkpoint_path=checkpoint_path,
        source_checkpoint_bytes=source_bytes,
        projected_checkpoint=projected,
        projected_checkpoint_bytes=projected_bytes,
        update_event=update,
        completion_event=completion,
        evidence=evidence,
        artifact_sha256_by_role=pins,
        output_sha256_by_path=output_pins,
        sequence_sha256_by_path={
            relative: evidence.final_managed_sha256_by_path[relative]
            for relative in SEQUENCE_AUTHORITY_PATHS
        },
        test_only_pin_override_used=(
            artifact_sha256_by_role is not None
            or output_sha256_by_path is not None
        ),
    )


def _file_identity(info: os.stat_result) -> tuple[int, ...]:
    return cas_transport._file_identity(info)


def _directory_identity(info: os.stat_result) -> tuple[int, ...]:
    return cas_transport._directory_identity(info)


def _read_descriptor_exact(descriptor: int, maximum_bytes: int) -> bytes:
    return cas_transport._read_descriptor_exact(descriptor, maximum_bytes)


def _verify_retained_physical_pin(root: Path, pin: RetainedPhysicalPin) -> None:
    canonical = _safe_file(root, pin.relative)
    path_before = os.stat(canonical, follow_symlinks=False)
    descriptor_before = os.fstat(pin.descriptor)
    _require(
        _file_identity(path_before) == pin.identity == _file_identity(descriptor_before),
        f"retained physical evidence identity differs: {pin.label}",
    )
    observed = _read_descriptor_exact(pin.descriptor, pin.expected_size)
    path_after = os.stat(canonical, follow_symlinks=False)
    descriptor_after = os.fstat(pin.descriptor)
    _require(
        _file_identity(path_after) == pin.identity == _file_identity(descriptor_after),
        f"retained physical evidence changed while read: {pin.label}",
    )
    _require(
        len(observed) == pin.expected_size
        and sha256_bytes(observed) == pin.expected_sha256,
        f"retained physical evidence SHA-256 differs: {pin.label}",
    )


def _verify_retained_directory_pin(
    root: Path,
    pin: RetainedDirectoryPin,
) -> None:
    canonical = _safe_directory(root, pin.relative)
    path_before = os.stat(canonical, follow_symlinks=False)
    descriptor_before = os.fstat(pin.descriptor)
    _require(
        _directory_identity(path_before)
        == pin.identity
        == _directory_identity(descriptor_before),
        f"retained evidence directory identity differs: {pin.label}",
    )
    observed_entries = tuple(sorted(os.listdir(pin.descriptor)))
    path_after = os.stat(canonical, follow_symlinks=False)
    descriptor_after = os.fstat(pin.descriptor)
    _require(
        _directory_identity(path_after)
        == pin.identity
        == _directory_identity(descriptor_after),
        f"retained evidence directory changed while read: {pin.label}",
    )
    _require(
        observed_entries == pin.expected_entries,
        f"retained evidence directory inventory differs: {pin.label}",
    )


def retain_physical_pin_cohort(
    root: Path,
    physical_sha256_by_path: Mapping[Path, str],
) -> PhysicalPinCohort:
    retained: list[RetainedPhysicalPin] = []
    retained_directories: list[RetainedDirectoryPin] = []
    try:
        for relative, digest in sorted(
            physical_sha256_by_path.items(), key=lambda row: row[0].as_posix()
        ):
            canonical = _safe_file(root, relative)
            descriptor = os.open(
                canonical,
                os.O_RDONLY
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
            )
            try:
                info = os.fstat(descriptor)
                path_info = os.stat(canonical, follow_symlinks=False)
                _require(
                    _file_identity(info) == _file_identity(path_info)
                    and stat.S_ISREG(info.st_mode)
                    and info.st_uid == os.geteuid()
                    and info.st_nlink == 1
                    and not (info.st_mode & (stat.S_ISUID | stat.S_ISGID)),
                    f"physical evidence authority differs: {relative}",
                )
                observed = _read_descriptor_exact(descriptor, info.st_size)
                _require(
                    sha256_bytes(observed) == digest,
                    f"sealed physical evidence SHA-256 differs: {relative}",
                )
                retained.append(
                    RetainedPhysicalPin(
                        label=relative.as_posix(),
                        relative=relative,
                        descriptor=descriptor,
                        identity=_file_identity(info),
                        expected_size=info.st_size,
                        expected_sha256=digest,
                    )
                )
            except BaseException:
                os.close(descriptor)
                raise
        for relative, expected_entries in SEALED_INVENTORY_BY_DIRECTORY.items():
            canonical = _safe_directory(root, relative)
            descriptor = os.open(
                canonical,
                os.O_RDONLY
                | getattr(os, "O_DIRECTORY", 0)
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
            )
            try:
                info = os.fstat(descriptor)
                path_info = os.stat(canonical, follow_symlinks=False)
                _require(
                    _directory_identity(info) == _directory_identity(path_info)
                    and stat.S_ISDIR(info.st_mode)
                    and info.st_uid == os.geteuid()
                    and not (info.st_mode & (stat.S_ISUID | stat.S_ISGID)),
                    f"evidence directory authority differs: {relative}",
                )
                retained_directories.append(
                    RetainedDirectoryPin(
                        label=relative.as_posix(),
                        relative=relative,
                        descriptor=descriptor,
                        identity=_directory_identity(info),
                        expected_entries=expected_entries,
                    )
                )
            except BaseException:
                os.close(descriptor)
                raise
        cohort = PhysicalPinCohort(root, retained, retained_directories)
        cohort.verify()
        return cohort
    except BaseException as exc:
        PhysicalPinCohort(root, retained, retained_directories).close(exc)
        raise


def write_projection(
    prepared: PreparedProjection,
    *,
    source_validator: Callable[[bytes, dict[str, Any]], None] = require_exact_source,
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]],
        tuple[dict[str, Any], dict[str, Any]],
    ] = derive_runtime,
    snapshot_hasher: Callable[[Path, list[str]], tuple[str, str]] = (
        contract.working_snapshot_hashes
    ),
    continuation_checker: Callable[[Path, Path], list[str]] = run_continuation_checker,
    goal_graph_checker: Callable[[Path, Path], list[str]] = run_goal_graph_checker,
    atomic_writer: Callable[..., None] = atomic_write,
) -> None:
    _require(
        not prepared.test_only_pin_override_used or atomic_writer is not atomic_write,
        "test-only SHA-256 overrides cannot use the production atomic writer",
    )
    _require(
        prepared.checkpoint_path.read_bytes() == prepared.source_checkpoint_bytes,
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
            artifact_sha256_by_role=prepared.artifact_sha256_by_role,
            output_sha256_by_path=prepared.output_sha256_by_path,
            source_validator=source_validator,
            runtime_deriver=runtime_deriver,
            snapshot_hasher=snapshot_hasher,
            continuation_checker=continuation_checker,
            goal_graph_checker=goal_graph_checker,
        )
        cohort.verify()
        _require(refreshed.evidence == prepared.evidence, "completion evidence changed before write")
        _require(
            refreshed.projected_checkpoint_bytes == prepared.projected_checkpoint_bytes,
            "projected checkpoint changed before write",
        )
        _require(
            refreshed.sequence_sha256_by_path == prepared.sequence_sha256_by_path,
            "completion sequence authority changed before write",
        )
        managed_paths = sorted(
            relative.as_posix()
            for relative in prepared.evidence.final_managed_sha256_by_path
        )
        expected_snapshot_hashes = _snapshot_hashes_from_digests(
            prepared.evidence.final_managed_sha256_by_path
        )

        def commit_guard() -> None:
            cohort.verify()
            _require(
                snapshot_hasher(prepared.root, managed_paths)
                == expected_snapshot_hashes,
                "final managed closure changed at commit",
            )
            validate_projected_with_both_checkers(
                prepared.root,
                prepared.projected_checkpoint_bytes,
                continuation_checker=continuation_checker,
                goal_graph_checker=goal_graph_checker,
            )

        atomic_writer(
            prepared.checkpoint_path,
            prepared.projected_checkpoint_bytes,
            expected_source=prepared.source_checkpoint_bytes,
            commit_guard=commit_guard,
        )
    except BaseException as exc:
        primary_error = exc
        raise
    finally:
        cohort.close(primary_error)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        prepared = prepare_projection(args.root)
        if args.write:
            write_projection(prepared)
            mode = "WRITE"
        else:
            mode = "PREFLIGHT"
    except CompletionPostCommitError as exc:
        print(f"FP-008 GOAL_COMPLETED seq49-50: POSTCOMMIT_UNCERTAIN: {exc}")
        return 1
    except (CompletionApplyError, KeyError, OSError, TypeError, ValueError) as exc:
        print(f"FP-008 GOAL_COMPLETED seq49-50: FAIL: {exc}")
        return 1
    print(
        "FP-008 GOAL_COMPLETED seq49-50: PASS "
        f"mode={mode} update_sha256={prepared.update_event['event_sha256']} "
        f"completion_sha256={prepared.completion_event['event_sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
