#!/usr/bin/env python3
"""Build the add-only, non-effective WalkSafe v2.5 control candidate.

The physical candidate contains the immutable PACKAGE_PREPARED (seq1) prefix.
PACKAGE_ACTIVATED and BULK_REBASELINE_APPLIED are generated and validated only
in the authorized in-memory projection; this builder never creates a delegation
receipt, changes active v2.4/r021 state, or writes a canonical r022 file.
"""

from __future__ import annotations

import argparse
import copy
import ctypes
import errno
import os
from pathlib import Path
import secrets
import stat
import sys
from typing import Any, Mapping


REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts import walksafe_v2_5_candidate_validation as validation  # noqa: E402


PREPARED_AT = validation.PREPARED_AT
PREPARED_ON = validation.PREPARED_ON
PUBLICATION_RESULT_PUBLISHED_NEW = "PUBLISHED_NEW"
PUBLICATION_RESULT_EXISTING_TARGET_TERMINAL = (
    "NEW_REVISION_REQUIRED_EXISTING_R002_TARGET_AUTHORITY_ZERO"
)


def _build_static(source: dict[str, Any]) -> dict[str, Any]:
    predecessor = source["static"]
    checkpoint = source["checkpoint"]
    result = copy.deepcopy(predecessor)
    result["schema_version"] = "2.3-candidate"
    result["manifest_id"] = validation.MANIFEST_ID
    result["package_id"] = validation.PACKAGE_ID
    result["plan_version"] = "2.5.0"
    result["created_on"] = PREPARED_ON
    result["candidate_state"] = "NON_EFFECTIVE_NOT_APPROVED_NOT_APPLIED"
    result["supersedes"] = {
        "package_id": predecessor["package_id"],
        "plan_version": predecessor["plan_version"],
        "activation_status": "ACTIVE_PREDECESSOR_UNCHANGED",
        "manifest_path": validation.ACTIVE_STATIC_REL,
        "manifest_sha256": validation.TRUSTED_SOURCE_PINS[
            validation.ACTIVE_STATIC_REL
        ][0],
        "checkpoint_path": validation.ACTIVE_CHECKPOINT_REL,
        "checkpoint_sha256": validation.TRUSTED_SOURCE_PINS[
            validation.ACTIVE_CHECKPOINT_REL
        ][0],
        "checkpoint_bytes": validation.TRUSTED_SOURCE_PINS[
            validation.ACTIVE_CHECKPOINT_REL
        ][1],
        "transition_event_count": len(
            checkpoint["goal_execution"]["transition_history"]
        ),
        "transition_history_anchor_sha256": validation.EXPECTED_PREDECESSOR_TAIL,
        "predecessor_bytes_modified": False,
    }
    result["goal_graph"]["initial_focus_goal_id"] = checkpoint["goal_execution"][
        "focus_goal_id"
    ]
    result["goal_graph"]["initial_ready_frontier_goal_ids"] = copy.deepcopy(
        checkpoint["goal_execution"]["ready_frontier_goal_ids"]
    )
    transition = result["transition_contract"]
    transition["check_command_contract_version"] = (
        validation.V25_CHECK_COMMAND_CONTRACT_VERSION
    )
    transition["quick_activation_check_ids"] = [
        check_id for check_id, _ in validation.V25_QUICK_CHECKS
    ]
    transition["quick_activation_check_contract_sha256"] = (
        validation.v25_quick_contract_sha256()
    )
    transition["bulk_rebaseline_event"] = "BULK_REBASELINE_APPLIED"
    transition["bulk_rebaseline_is_candidate_specific"] = True
    transition["bulk_rebaseline_requires_external_core_review"] = True
    transition["bulk_rebaseline_requires_current_session_delegation_record"] = True
    transition["bulk_rebaseline_requires_fresh_v2_5_quick_gate"] = True
    result["successor_control_contract"] = {
        "candidate_builder_path": (
            "scripts/build_walksafe_v2_5_control_candidate_20260730.py"
        ),
        "shared_validation_core_path": (
            "scripts/walksafe_v2_5_candidate_validation.py"
        ),
        "active_validation_core_final_path": validation.V25_CORE_FINAL_REL,
        "candidate_continuation_checker_path": (
            "scripts/check_walksafe_project_continuation_v2_5_candidate.py"
        ),
        "candidate_goal_graph_checker_path": (
            "scripts/check_walksafe_goal_graph_v2_5_candidate.py"
        ),
        "active_continuation_checker_final_path": (
            validation.V25_CONTINUATION_FINAL_REL
        ),
        "active_goal_graph_checker_final_path": validation.V25_GOAL_FINAL_REL,
        "candidate_wrappers_are_byte_exact_dual_mode_sources": True,
        "candidate_test_path": (
            "tests/test_walksafe_v2_5_control_candidate_20260730.py"
        ),
        "active_discovery_registered": False,
        "candidate_directory": validation.BUNDLE_REL,
        "application_requires_current_session_delegation_receipt": True,
        "current_session_delegation_status": (
            validation.AUTHORIZATION_REQUEST_STATUS
        ),
        "application_requires_fresh_quick_gate_after_delegation_receipt": True,
        "product_implementation_authorized": False,
    }
    result["bulk_rebaseline_contract"] = {
        "event_type": "BULK_REBASELINE_APPLIED",
        "event_chain": [
            "PACKAGE_PREPARED",
            "PACKAGE_ACTIVATED",
            "BULK_REBASELINE_APPLIED",
        ],
        "physical_candidate_event_count": 1,
        "physical_candidate_tail": "PACKAGE_PREPARED",
        "authorized_final_event_count": 3,
        "package_activation_is_no_op": True,
        "candidate_checkpoint_is_seq1_only": True,
        "seq2_and_seq3_are_sealed_deterministic_transform_outputs": True,
        "r002_pair_fingerprint_sha256": validation.EXPECTED_PAIR_FINGERPRINT,
        "mapping_sha256": validation.EXPECTED_MAPPING_SHA256,
        "hard_dependency_sha256": validation.EXPECTED_DEPENDENCY_SHA256,
        "goal_topology_delta": 0,
        "goal_status_delta": 0,
        "runtime_queue_delta": 0,
        "goal_materialization_credit_delta": 0,
        "goal_start_credit_delta": 0,
        "goal_completion_credit_delta": 0,
        "artifact_completion_credit_delta": 0,
        "approval_credit_delta": 0,
        "formal_test_credit_delta": 0,
        "actual_device_credit_delta": 0,
        "actual_event_credit_delta": 0,
        "release_gate_credit_delta": 0,
        "product_code_delta": 0,
        "release_status_after": "NOT_ELIGIBLE",
        "produced_by_goal_id_required": False,
        "post_commit_receipt_bound_by_event": False,
        "checkpoint_is_last_commit_point": True,
        "active_tail_package_activated_is_forbidden": True,
    }
    return validation.seal_object(result, "manifest_content_sha256")


def _build_plan(
    root: Path,
    source: dict[str, Any],
    static_raw: bytes,
) -> dict[str, Any]:
    pair = source["r002_pair"]
    predecessor = source["checkpoint"]
    result = {
        "schema_version": "walksafe.v2.5-application-transaction-plan.candidate.v1",
        "metadata": {
            "plan_id": validation.TRANSACTION_PLAN_ID,
            "prepared_at": PREPARED_AT,
            "status": "PRECOMMIT_CANDIDATE_NOT_AUTHORIZED_NOT_APPLIED",
        },
        "authority_boundary": {
            "effective": False,
            "approved": False,
            "applied": False,
            "canonical_write_authorized": False,
            "checkpoint_write_authorized": False,
            "goal_materialization_authorized": False,
            "product_code_change_authorized": False,
        },
        "source_cas": validation.source_bindings_from_state(source),
        "static_candidate_binding": {
            "path": validation.STATIC_REL,
            "sha256": validation.sha256_bytes(static_raw),
            "bytes": len(static_raw),
        },
        "r002_review_binding": {
            "path": validation.R002_REVIEW_REL,
            "sha256": validation.TRUSTED_SOURCE_PINS[
                validation.R002_REVIEW_REL
            ][0],
            "bytes": validation.TRUSTED_SOURCE_PINS[
                validation.R002_REVIEW_REL
            ][1],
            "findings": {"BLOCKING": 0, "MAJOR": 0, "MINOR": 0},
        },
        "reviewed_design_binding": {
            "path": validation.DESIGN_REL,
            "sha256": validation.TRUSTED_SOURCE_PINS[validation.DESIGN_REL][0],
            "bytes": validation.TRUSTED_SOURCE_PINS[validation.DESIGN_REL][1],
            "independent_review_path": validation.DESIGN_REVIEW_REL,
            "independent_review_sha256": validation.TRUSTED_SOURCE_PINS[
                validation.DESIGN_REVIEW_REL
            ][0],
            "independent_review_bytes": validation.TRUSTED_SOURCE_PINS[
                validation.DESIGN_REVIEW_REL
            ][1],
            "findings": {"BLOCKING": 0, "MAJOR": 0, "MINOR": 0},
        },
        "control_core_review_gate": {
            "required": True,
            "status": "REQUIRED_NOT_PRESENT_BLOCKS_APPLY",
            "receipt_path": None,
            "receipt_sha256": None,
            "findings_required": {"BLOCKING": 0, "MAJOR": 0, "MINOR": 0},
        },
        "delegation_gate": {
            "requirement_id": validation.DELEGATION_REQUIREMENT_ID,
            "required": True,
            "status": validation.AUTHORIZATION_REQUEST_STATUS,
            "authority_kind": validation.AUTHORITY_KIND,
            "authority_claim_boundary": validation.AUTHORITY_CLAIM_BOUNDARY,
            "normalized_execution_scope": (
                validation.normalized_execution_scope()
            ),
            "transaction_nonce_contract": {
                "created_before_request_serialization": True,
                "copied_unchanged_to_receipt_single_use": True,
                "derived_from_request_self_hash": False,
            },
            "receipt_path": None,
            "receipt_sha256": None,
            "local_receipt_recorded": False,
            "authorized": False,
        },
        "fresh_quick_gate": {
            "requirement_id": validation.QUICK_GATE_REQUIREMENT_ID,
            "required": True,
            "status": "MISSING_BLOCKS_APPLY",
            "receipt_path": None,
            "receipt_sha256": None,
            "required_check_ids": [
                "CONTINUATION_QUICK_V2_5",
                "GOAL_GRAPH_QUICK_V2_5",
            ],
            "must_run_after_delegation_record": True,
            "source_cas_must_match_at_checkpoint_switch": True,
            "wall_clock_only_is_sufficient": False,
        },
        "before_r021_pair": {
            "gap_id": predecessor["implementation_gap_snapshot"]["report_id"],
            "gap_path": validation.R021_GAP_REL,
            "gap_sha256": validation.TRUSTED_SOURCE_PINS[
                validation.R021_GAP_REL
            ][0],
            "backlog_id": predecessor["implementation_gap_snapshot"][
                "backlog_id"
            ],
            "backlog_path": validation.R021_BACKLOG_REL,
            "backlog_sha256": validation.TRUSTED_SOURCE_PINS[
                validation.R021_BACKLOG_REL
            ][0],
        },
        "target_r022_pair": {
            "pair_manifest_path": validation.R002_PAIR_REL,
            "pair_manifest_sha256": validation.TRUSTED_SOURCE_PINS[
                validation.R002_PAIR_REL
            ][0],
            "pair_fingerprint_sha256": validation.EXPECTED_PAIR_FINGERPRINT,
            "gap_candidate_path": validation.R002_GAP_REL,
            "gap_final_path": validation.R022_GAP_FINAL_REL,
            "gap_sha256": validation.TRUSTED_SOURCE_PINS[
                validation.R002_GAP_REL
            ][0],
            "backlog_candidate_path": validation.R002_BACKLOG_REL,
            "backlog_final_path": validation.R022_BACKLOG_FINAL_REL,
            "backlog_sha256": validation.TRUSTED_SOURCE_PINS[
                validation.R002_BACKLOG_REL
            ][0],
        },
        "changed_subject_ids_by_role": pair["activation_impact_boundary"][
            "changed_subject_ids_by_role"
        ],
        "checkpoint_projection_rows": validation.checkpoint_projection_rows(source),
        "activation_envelope_projection_contract": (
            validation.activation_envelope_projection_contract(source)
        ),
        "final_history_projection_contract": (
            validation.final_history_projection_contract()
        ),
        "working_snapshot_transform_contract": (
            validation.working_snapshot_transform_contract(root, source)
        ),
        "normalized_execution_scope": validation.normalized_execution_scope(),
        "checkpoint_invariants": {
            "approved_state_sha256": validation.sha256_bytes(
                validation.canonical_bytes(predecessor["approved_state"])
            ),
            "verification_boundary_sha256": validation.sha256_bytes(
                validation.canonical_bytes(predecessor["verification_boundary"])
            ),
            "repository_sha256": validation.sha256_bytes(
                validation.canonical_bytes(predecessor["repository"])
            ),
            "predecessor_working_tree_snapshot_sha256": validation.sha256_bytes(
                validation.canonical_bytes(predecessor["working_tree_snapshot"])
            ),
            "goal_status_sha256": validation.sha256_bytes(
                validation.canonical_bytes(
                    predecessor["goal_execution"]["status_by_goal"]
                )
            ),
            "goal_runtime_queue_sha256": validation.sha256_bytes(
                validation.canonical_bytes(
                    predecessor["goal_execution"]["artifact_work_queue"]
                )
            ),
            "goal_topology_sha256": validation.sha256_bytes(
                validation.canonical_bytes(
                    source["static"]["goal_graph"]["static_nodes"]
                )
            ),
            "artifact_complete_count": 126,
            "artifact_open_count": 131,
            "formal_pass_count": 0,
            "actual_device_execution_count": 0,
            "closed_release_gate_count": 0,
            "release_status": "NOT_ELIGIBLE",
        },
        "goal_topology_delta": 0,
        "goal_status_delta": 0,
        "runtime_queue_delta": 0,
        "goal_materialization_credit_delta": 0,
        "goal_start_credit_delta": 0,
        "goal_completion_credit_delta": 0,
        "artifact_completion_credit_delta": 0,
        "approval_credit_delta": 0,
        "formal_test_credit_delta": 0,
        "actual_device_credit_delta": 0,
        "actual_event_credit_delta": 0,
        "release_gate_credit_delta": 0,
        "product_code_delta": 0,
        "release_status_after": "NOT_ELIGIBLE",
        "commit_protocol": {
            "all_final_bytes_validated_before_first_final_write": True,
            "single_lock_held_through_checkpoint_commit": True,
            "single_lock_held_through_postcheck_and_receipt_fsync": True,
            "source_cas_revalidated_immediately_before_checkpoint_switch": True,
            "checkpoint_is_last_commit_point": True,
            "promotion_modes": {
                "BYTE_EXACT_COPY": {
                    "rewrite_allowed": False,
                    "candidate_hash_and_bytes_must_match": True,
                    "write_sequence": [
                        "TEMP_O_EXCL",
                        "FILE_FSYNC",
                        "RENAME_NOREPLACE",
                        "PARENT_FSYNC",
                    ],
                },
                "SEALED_DETERMINISTIC_TRANSFORM": {
                    "transform_id": validation.FINAL_TRANSFORM_ID,
                    "all_inputs_sealed": True,
                    "all_outputs_built_and_validated_before_final_write": True,
                    "allowed_delta_contracts": [
                        "checkpoint_projection_rows",
                        "activation_envelope_projection_contract",
                        "final_history_projection_contract",
                        "working_snapshot_transform_contract",
                    ],
                    "delta_outside_allowed_contracts_allowed": False,
                },
            },
            "source_cas_checkpoints": [
                "AFTER_LOCK_ACQUIRED",
                "IMMEDIATELY_BEFORE_CHECKPOINT_SWITCH",
            ],
            "monotonic_freshness": validation.monotonic_freshness_contract(),
            "partial_crash_recovery": validation.pre_c1_partial_contract(),
            "pre_checkpoint_failure": {
                "before_any_receipt_or_final_member": (
                    "PRE_CHECKPOINT_FAILURE_NOT_ACTIVE"
                ),
                "after_any_receipt_or_final_member": (
                    "NEW_REVISION_REQUIRED_UNCOMMITTED_AUTHORITY_ZERO"
                ),
                "v2_4_r021_remains_active": True,
                "activation_claim_allowed": False,
                "automatic_resume_allowed": False,
            },
            "post_checkpoint_states": {
                "postcheck_not_run": "COMMITTED_POSTCHECK_PENDING",
                "postcheck_failed": "COMMITTED_POSTCHECK_FAILED",
                "postcheck_passed_receipt_missing": "COMMITTED_RECEIPT_PENDING",
                "rollback_to_v2_4_allowed": False,
            },
            "post_commit_receipt": {
                "created_by_candidate": False,
                "created_only_after_postcheck_pass": True,
                "binds_final_event_and_checkpoint_one_way": True,
            },
        },
        "binding_dag": {
            "edge_direction": "UPSTREAM_TO_DEPENDENT",
            "edges": [
                ["PREDECESSOR_V2_4_R021", "STATIC_CANDIDATE"],
                ["PREDECESSOR_V2_4_R021", "TRANSACTION_PLAN"],
                ["R002_PAIR_AND_REVIEW", "TRANSACTION_PLAN"],
                ["STATIC_CANDIDATE", "TRANSACTION_PLAN"],
                ["TRANSACTION_PLAN", "PACKAGE_PREPARED_SEQ1"],
                ["PACKAGE_PREPARED_SEQ1", "SEQ1_CANDIDATE_CHECKPOINT"],
                ["SEQ1_CANDIDATE_CHECKPOINT", "CANDIDATE_PACKAGE_MANIFEST"],
                ["CANDIDATE_PACKAGE_MANIFEST", "AUTHORIZATION_REQUEST"],
                ["CONTROL_CORE_REVIEW", "AUTHORIZATION_REQUEST"],
                [
                    "CURRENT_LIVE_SESSION_USER_DELEGATION",
                    "AUTHORIZATION_RECEIPT",
                ],
                ["AUTHORIZATION_REQUEST", "AUTHORIZATION_RECEIPT"],
                ["AUTHORIZATION_RECEIPT", "FRESH_QUICK_GATE"],
                ["FRESH_QUICK_GATE", "PACKAGE_ACTIVATED_SEQ2"],
                ["AUTHORIZATION_RECEIPT", "PACKAGE_ACTIVATED_SEQ2"],
                ["PACKAGE_ACTIVATED_SEQ2", "BULK_REBASELINE_SEQ3"],
                ["TRANSACTION_PLAN", "BULK_REBASELINE_SEQ3"],
                ["R002_PAIR_AND_REVIEW", "BULK_REBASELINE_SEQ3"],
                ["BULK_REBASELINE_SEQ3", "FINAL_CHECKPOINT"],
                ["FINAL_CHECKPOINT", "POSTCOMMIT_RECEIPT"],
            ],
        },
        "hash_cycle_boundary": {
            "plan_binds_final_checkpoint_hash": False,
            "plan_binds_final_history_hash": False,
            "plan_binds_package_manifest_hash": False,
            "event_binds_post_commit_receipt": False,
            "post_commit_receipt_is_one_way_dependent": True,
        },
    }
    return validation.seal_object(result, "plan_content_sha256")


def _build_history(
    source: dict[str, Any],
    static_raw: bytes,
    plan_raw: bytes,
) -> dict[str, Any]:
    checkpoint = source["checkpoint"]
    seq1 = {
        "sequence": 1,
        "event_id": validation.PREPARED_EVENT_ID,
        "event_type": "PACKAGE_PREPARED",
        "occurred_at": PREPARED_AT,
        "previous_event_sha256": validation.EXPECTED_PREDECESSOR_TAIL,
        "commit_state": "PREPARED_NOT_ACTIVE",
        "transaction_plan_binding": {
            "path": validation.PLAN_REL,
            "sha256": validation.sha256_bytes(plan_raw),
            "bytes": len(plan_raw),
        },
        "static_manifest_binding": {
            "path": validation.STATIC_REL,
            "sha256": validation.sha256_bytes(static_raw),
            "bytes": len(static_raw),
        },
        "predecessor_checkpoint_binding": {
            "path": validation.ACTIVE_CHECKPOINT_REL,
            "sha256": validation.TRUSTED_SOURCE_PINS[
                validation.ACTIVE_CHECKPOINT_REL
            ][0],
            "bytes": validation.TRUSTED_SOURCE_PINS[
                validation.ACTIVE_CHECKPOINT_REL
            ][1],
            "tail_event_sha256": validation.EXPECTED_PREDECESSOR_TAIL,
        },
        "imported_goal_runtime_sha256": validation.sha256_bytes(
            validation.canonical_bytes(
                {
                    key: value
                    for key, value in checkpoint["goal_execution"].items()
                    if key not in validation.GOAL_EXECUTION_ENVELOPE_KEYS
                }
            )
        ),
        "goal_status_changes": {},
        "canonical_binding_changes": {},
        "r022_applied": False,
        "delegation_authorization_receipt_present": False,
        "fresh_quick_gate_present": False,
    }
    seq1["event_sha256"] = validation.event_sha256(seq1)
    result = {
        "schema_version": "walksafe.v2.5-transition-history.candidate.v1",
        "history_id": validation.HISTORY_ID,
        "candidate_state": "SEQ1_PREFIX_NOT_EFFECTIVE",
        "predecessor_anchor": {
            "package_id": checkpoint["goal_execution"]["package_id"],
            "checkpoint_path": validation.ACTIVE_CHECKPOINT_REL,
            "checkpoint_sha256": validation.TRUSTED_SOURCE_PINS[
                validation.ACTIVE_CHECKPOINT_REL
            ][0],
            "checkpoint_bytes": validation.TRUSTED_SOURCE_PINS[
                validation.ACTIVE_CHECKPOINT_REL
            ][1],
            "tail_event_sha256": validation.EXPECTED_PREDECESSOR_TAIL,
            "event_count": len(
                checkpoint["goal_execution"]["transition_history"]
            ),
        },
        "required_authorized_event_chain": [
            "PACKAGE_PREPARED",
            "PACKAGE_ACTIVATED",
            "BULK_REBASELINE_APPLIED",
        ],
        "future_events_materialized": False,
        "events": [seq1],
        "tail_event_sha256": seq1["event_sha256"],
        "post_commit_receipt_created": False,
    }
    return validation.seal_object(result, "history_content_sha256")


def _build_checkpoint(
    source: dict[str, Any],
    static_raw: bytes,
    plan_raw: bytes,
    history: dict[str, Any],
    history_raw: bytes,
) -> dict[str, Any]:
    result = copy.deepcopy(source["checkpoint"])
    result["metadata"] = copy.deepcopy(result["metadata"])
    result["metadata"].update(
        {
            "checkpoint_id": validation.PREPARED_CHECKPOINT_ID,
            "version": "1.25.0-candidate",
            "as_of": PREPARED_ON,
            "status": "PACKAGE_PREPARED_SEQ1_NOT_ACTIVE_NOT_EFFECTIVE",
            "purpose": (
                "v2.5 successor package의 seq1-only 비효력 후보와 current-session "
                "위임 receipt 로컬 기록 후 결정론적 r022 projection 입력을 봉인한다."
            ),
        }
    )
    goal = result["goal_execution"]
    goal["schema_version"] = "2.3-candidate"
    goal["package_id"] = validation.PACKAGE_ID
    goal["package_status"] = "PREPARED_NOT_ACTIVE"
    goal["activation_status"] = "NOT_ACTIVE"
    goal["static_plan_version"] = "2.5.0"
    goal["static_plan_manifest_path"] = validation.V25_STATIC_FINAL_REL
    goal["static_plan_manifest_sha256"] = validation.sha256_bytes(static_raw)
    goal["transition_history"] = copy.deepcopy(history["events"])
    goal["transition_history_anchor_sha256"] = history["tail_event_sha256"]
    goal["transition_history_assurance"] = (
        "PREDECESSOR_ANCHORED_SEQ1_PREFIX_NOT_ACTIVE"
    )
    goal["validation_cutoff_at"] = PREPARED_AT
    goal["verification_evidence_refs"] = list(
        dict.fromkeys(
            [
                *goal["verification_evidence_refs"],
                "R002_REVIEWED_NON_EFFECTIVE_PAIR",
                "V25_SEQ1_CANDIDATE_PREPARATION_ONLY",
            ]
        )
    )
    result["candidate_control_projection"] = {
        "candidate_id": validation.PACKAGE_CANDIDATE_ID,
        "state": "PACKAGE_PREPARED_SEQ1_ONLY",
        "effective": False,
        "approved": False,
        "applied": False,
        "post_commit_receipt_created": False,
        "static_manifest_binding": {
            "candidate_path": validation.STATIC_REL,
            "final_path": validation.V25_STATIC_FINAL_REL,
            "sha256": validation.sha256_bytes(static_raw),
            "bytes": len(static_raw),
        },
        "transaction_plan_binding": {
            "candidate_path": validation.PLAN_REL,
            "final_path": validation.V25_PLAN_FINAL_REL,
            "sha256": validation.sha256_bytes(plan_raw),
            "bytes": len(plan_raw),
        },
        "history_binding": {
            "candidate_path": validation.HISTORY_REL,
            "final_path": validation.V25_HISTORY_PREFIX_FINAL_REL,
            "sha256": validation.sha256_bytes(history_raw),
            "bytes": len(history_raw),
            "tail_event_sha256": history["tail_event_sha256"],
        },
        "authorized_final_transform": {
            "transform_id": validation.FINAL_TRANSFORM_ID,
            "promotion_mode": "SEALED_DETERMINISTIC_TRANSFORM",
            "delegation_requirement_id": validation.DELEGATION_REQUIREMENT_ID,
            "fresh_quick_gate_requirement_id": (
                validation.QUICK_GATE_REQUIREMENT_ID
            ),
            "final_history_path": validation.V25_HISTORY_FINAL_REL,
            "final_checkpoint_path": validation.ACTIVE_CHECKPOINT_REL,
            "target_gap_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260726-022",
            "target_backlog_id": (
                "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-022"
            ),
            "next_work_item_id": "EPIC-03-FP008-ADMIN-REVIEW-DELIVERY",
        },
    }
    return validation.seal_object(result, "checkpoint_content_sha256")


def _byte_exact_promotions(
    root: Path,
    static_raw: bytes,
    plan_raw: bytes,
    history_raw: bytes,
) -> list[dict[str, Any]]:
    rows = [
        (
            validation.R002_GAP_REL,
            validation.R022_GAP_FINAL_REL,
            validation.TRUSTED_SOURCE_PINS[validation.R002_GAP_REL][0],
            validation.TRUSTED_SOURCE_PINS[validation.R002_GAP_REL][1],
        ),
        (
            validation.R002_BACKLOG_REL,
            validation.R022_BACKLOG_FINAL_REL,
            validation.TRUSTED_SOURCE_PINS[validation.R002_BACKLOG_REL][0],
            validation.TRUSTED_SOURCE_PINS[validation.R002_BACKLOG_REL][1],
        ),
        (
            validation.ACTIVE_CHECKPOINT_REL,
            validation.V24_CHECKPOINT_ARCHIVE_FINAL_REL,
            validation.TRUSTED_SOURCE_PINS[
                validation.ACTIVE_CHECKPOINT_REL
            ][0],
            validation.TRUSTED_SOURCE_PINS[
                validation.ACTIVE_CHECKPOINT_REL
            ][1],
        ),
        (
            validation.STATIC_REL,
            validation.V25_STATIC_FINAL_REL,
            validation.sha256_bytes(static_raw),
            len(static_raw),
        ),
        (
            validation.PLAN_REL,
            validation.V25_PLAN_FINAL_REL,
            validation.sha256_bytes(plan_raw),
            len(plan_raw),
        ),
        (
            validation.HISTORY_REL,
            validation.V25_HISTORY_PREFIX_FINAL_REL,
            validation.sha256_bytes(history_raw),
            len(history_raw),
        ),
    ]
    document_rows = [
        {
            "candidate_path": candidate_path,
            "final_path": final_path,
            "sha256": digest,
            "bytes": size,
            "promotion_mode": "BYTE_EXACT_COPY",
        }
        for candidate_path, final_path, digest, size in rows
    ]
    return [*document_rows, *validation.code_byte_exact_promotions(root)]


def _build_package(
    source: dict[str, Any],
    static: dict[str, Any],
    static_raw: bytes,
    plan: dict[str, Any],
    plan_raw: bytes,
    history: dict[str, Any],
    history_raw: bytes,
    checkpoint: dict[str, Any],
    checkpoint_raw: bytes,
    root: Path,
) -> dict[str, Any]:
    outputs = [
        {
            "role": "STATIC_PLAN",
            "path": validation.STATIC_REL,
            "sha256": validation.sha256_bytes(static_raw),
            "bytes": len(static_raw),
            "content_sha256": static["manifest_content_sha256"],
        },
        {
            "role": "APPLICATION_TRANSACTION_PLAN",
            "path": validation.PLAN_REL,
            "sha256": validation.sha256_bytes(plan_raw),
            "bytes": len(plan_raw),
            "content_sha256": plan["plan_content_sha256"],
        },
        {
            "role": "TRANSITION_HISTORY",
            "path": validation.HISTORY_REL,
            "sha256": validation.sha256_bytes(history_raw),
            "bytes": len(history_raw),
            "content_sha256": history["history_content_sha256"],
        },
        {
            "role": "CHECKPOINT_AFTER_PROJECTION",
            "path": validation.CHECKPOINT_REL,
            "sha256": validation.sha256_bytes(checkpoint_raw),
            "bytes": len(checkpoint_raw),
            "content_sha256": checkpoint["checkpoint_content_sha256"],
        },
    ]
    byte_exact = _byte_exact_promotions(root, static_raw, plan_raw, history_raw)
    transform_inputs = {
        validation.STATIC_NAME: static_raw,
        validation.PLAN_NAME: plan_raw,
        validation.HISTORY_NAME: history_raw,
        validation.CHECKPOINT_NAME: checkpoint_raw,
        validation.PACKAGE_NAME: b"",
    }
    transforms = [
        validation.deterministic_transform_entry(root, transform_inputs, source)
    ]
    result = {
        "schema_version": "walksafe.v2.5-control-package-manifest.candidate.v1",
        "metadata": {
            "candidate_id": validation.PACKAGE_CANDIDATE_ID,
            "prepared_at": PREPARED_AT,
            "status": "NON_EFFECTIVE_NOT_APPROVED_NOT_APPLIED",
        },
        "effective": False,
        "approved": False,
        "applied": False,
        "post_commit_receipt_created": False,
        "active_discovery_registered": False,
        "source_bindings": validation.source_bindings_from_state(source),
        "generator_bindings": validation.generator_bindings(root),
        "outputs": outputs,
        "package_manifest_self_binding": {
            "path": validation.PACKAGE_REL,
            "hash_kind": "LOGICAL_SELF_SEAL_ONLY",
            "excluded_from_physical_output_bindings": True,
        },
        "byte_exact_promotion_mapping": byte_exact,
        "sealed_deterministic_transforms": transforms,
        "candidate_specific_delegation_subject": {
            "requirement_id": validation.DELEGATION_REQUIREMENT_ID,
            "candidate_id": validation.PACKAGE_CANDIDATE_ID,
            "transaction_plan_id": validation.TRANSACTION_PLAN_ID,
            "status": validation.AUTHORIZATION_REQUEST_STATUS,
            "authority_kind": validation.AUTHORITY_KIND,
            "authority_claim_boundary": validation.AUTHORITY_CLAIM_BOUNDARY,
            "delegation_observed": True,
            "local_receipt_recorded": False,
            "authorized": False,
            "promotion_mapping_sha256": validation.sha256_bytes(
                validation.canonical_bytes(
                    {
                        "byte_exact": byte_exact,
                        "transforms": transforms,
                    }
                )
            ),
            "transaction_plan_sha256": validation.sha256_bytes(plan_raw),
            "transaction_plan_content_sha256": plan["plan_content_sha256"],
            "prepared_event_sha256": history["events"][0]["event_sha256"],
            "normalized_execution_scope": validation.normalized_execution_scope(),
        },
        "future_activation_attempt_contract": (
            validation.future_activation_attempt_contract()
        ),
        "publication_contract": {
            "candidate_directory_add_only": True,
            "all_output_bytes_built_and_validated_before_publication": True,
            "single_atomic_directory_publish": True,
            "rename_noreplace_required": True,
            "partial_candidate_directory_allowed": False,
            "canonical_paths_written": False,
        },
        "resolved_output_manifest_contract": {
            "created_by_candidate": False,
            "created_after_authorization_and_fresh_quick_gate": True,
            "created_before_any_final_write": True,
            "path": (
                "docs/control/execution/goal-gates/"
                "WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-002/"
                "resolved-output-manifest.json"
            ),
            "publication": [
                "TEMP_O_EXCL",
                "FILE_FSYNC",
                "RENAME_NOREPLACE",
                "PARENT_FSYNC",
            ],
            "binds": [
                "TRANSACTION_ID",
                "AUTHORIZATION_RECEIPT_SHA256",
                "FRESH_QUICK_GATE_RECEIPT_SHA256",
                "ALL_FINAL_PATH_SHA256_BYTES",
            ],
            "exact_member_paths": [
                validation.R022_GAP_FINAL_REL,
                validation.R022_BACKLOG_FINAL_REL,
                validation.V25_STATIC_FINAL_REL,
                validation.V25_PLAN_FINAL_REL,
                validation.V25_HISTORY_PREFIX_FINAL_REL,
                validation.V25_PACKAGE_FINAL_REL,
                validation.V24_CHECKPOINT_ARCHIVE_FINAL_REL,
                validation.V25_CORE_FINAL_REL,
                validation.V25_CONTINUATION_FINAL_REL,
                validation.V25_GOAL_FINAL_REL,
                validation.V25_HISTORY_FINAL_REL,
                validation.ACTIVE_CHECKPOINT_REL,
            ],
            "excluded_member_kinds": [
                "RESOLVED_MANIFEST_SELF",
                "TEMPORARY_FILE",
                "FAILURE_RECEIPT",
                "INCIDENT_RECORD",
                "POSTCHECK_OUTPUT",
                "POST_COMMIT_RECEIPT",
            ],
            "preexisting_final_allowed": False,
            "partial_resume_allowed": False,
            "overwrite_allowed": False,
            "pre_c1_partial_terminal": (
                "NEW_REVISION_REQUIRED_UNCOMMITTED_AUTHORITY_ZERO"
            ),
            "referenced_by_events": False,
            "bound_one_way_by_post_commit_receipt": True,
        },
        "hash_cycle_boundary": {
            "package_manifest_self_reference": "LOGICAL_SELF_SEAL_ONLY",
            "package_manifest_excluded_from_physical_output_bindings": True,
            "transaction_plan_binds_final_checkpoint_hash": False,
            "events_bind_post_commit_receipt": False,
            "post_commit_receipt_present": False,
        },
    }
    return validation.seal_object(result, "manifest_content_sha256")


def _build_output_manifest(
    static: dict[str, Any],
    static_raw: bytes,
    plan: dict[str, Any],
    plan_raw: bytes,
    history: dict[str, Any],
    history_raw: bytes,
    checkpoint: dict[str, Any],
    checkpoint_raw: bytes,
    package: dict[str, Any],
    package_raw: bytes,
    root: Path,
) -> dict[str, Any]:
    physical_rows = [
        (
            "STATIC_PLAN",
            validation.STATIC_REL,
            static_raw,
            static["manifest_content_sha256"],
        ),
        (
            "APPLICATION_TRANSACTION_PLAN",
            validation.PLAN_REL,
            plan_raw,
            plan["plan_content_sha256"],
        ),
        (
            "TRANSITION_HISTORY_SEQ1_PREFIX",
            validation.HISTORY_REL,
            history_raw,
            history["history_content_sha256"],
        ),
        (
            "CHECKPOINT_SEQ1_PROJECTION_INPUT",
            validation.CHECKPOINT_REL,
            checkpoint_raw,
            checkpoint["checkpoint_content_sha256"],
        ),
        (
            "CONTROL_PACKAGE_MANIFEST",
            validation.PACKAGE_REL,
            package_raw,
            package["manifest_content_sha256"],
        ),
    ]
    physical_outputs = [
        {
            "role": role,
            "candidate_path": candidate_path,
            "sha256": validation.sha256_bytes(raw),
            "bytes": len(raw),
            "content_sha256": content_sha256,
        }
        for role, candidate_path, raw, content_sha256 in physical_rows
    ]
    byte_exact = [
        *copy.deepcopy(package["byte_exact_promotion_mapping"]),
        {
            "candidate_path": validation.PACKAGE_REL,
            "final_path": validation.V25_PACKAGE_FINAL_REL,
            "sha256": validation.sha256_bytes(package_raw),
            "bytes": len(package_raw),
            "promotion_mode": "BYTE_EXACT_COPY",
        },
    ]
    result = {
        "schema_version": "walksafe.v2.5-candidate-output-manifest.v1",
        "metadata": {
            "manifest_id": validation.OUTPUT_MANIFEST_ID,
            "candidate_id": validation.PACKAGE_CANDIDATE_ID,
            "prepared_at": PREPARED_AT,
            "status": "NON_EFFECTIVE_NOT_APPROVED_NOT_APPLIED",
        },
        "effective": False,
        "approved": False,
        "applied": False,
        "post_commit_receipt_created": False,
        "active_discovery_registered": False,
        "physical_outputs": physical_outputs,
        "self_binding": {
            "path": validation.OUTPUT_MANIFEST_REL,
            "hash_kind": "LOGICAL_SELF_SEAL_ONLY",
            "excluded_from_physical_output_bindings": True,
        },
        "byte_exact_promotion_mapping": byte_exact,
        "sealed_deterministic_transforms": copy.deepcopy(
            package["sealed_deterministic_transforms"]
        ),
        "future_activation_attempt_contract": (
            validation.future_activation_attempt_contract()
        ),
        "dynamic_input_slots": [
            {
                "role": "CONTROL_CORE_INDEPENDENT_REVIEW_RECEIPT",
                "intended_path": validation.CORE_REVIEW_RECEIPT_REL,
                "physical_sha256_and_bytes_required": True,
                "required_before": "AUTHORIZATION_REQUEST_CREATION",
                "candidate_bundle_member": False,
                "status": "MISSING_BLOCKS_AUTHORIZATION_REQUEST",
            },
            {
                "role": "CONTROL_TRANSITION_AUTHORIZATION_REQUEST",
                "intended_path": validation.AUTHORIZATION_REQUEST_REL,
                "physical_sha256_and_bytes_required": True,
                "required_before": "DELEGATION_RECEIPT_LOCAL_RECORD",
                "candidate_bundle_member": False,
                "status": "CREATED_ONLY_AFTER_CORE_REVIEW",
            },
            {
                "role": "CONTROL_TRANSITION_AUTHORIZATION_RECEIPT",
                "intended_path": validation.AUTHORIZATION_RECEIPT_REL,
                "physical_sha256_and_bytes_required": True,
                "required_before": "FRESH_QUICK_GATE",
                "candidate_bundle_member": False,
                "status": "PENDING_LOCAL_RECORD_BLOCKS_APPLY",
            },
            {
                "role": "FRESH_QUICK_GATE_RECEIPT",
                "intended_path": validation.QUICK_GATE_RECEIPT_REL,
                "physical_sha256_and_bytes_required": True,
                "required_before": "SEALED_DETERMINISTIC_TRANSFORM",
                "candidate_bundle_member": False,
                "status": "MISSING_BLOCKS_APPLY",
            },
        ],
        "hash_cycle_boundary": {
            "self_physical_sha256_and_bytes_excluded": True,
            "package_manifest_does_not_reference_output_manifest": True,
            "external_core_review_binds_output_manifest_physical_sha256_bytes": True,
            "authorization_request_created_only_after_external_core_review": True,
            "post_commit_receipt_present": False,
        },
    }
    return validation.seal_object(result, "output_manifest_content_sha256")


def construct_outputs_unvalidated(
    root: Path = REPO_ROOT,
) -> dict[str, bytes]:
    root = root.resolve(strict=True)
    source = validation.load_source_state(root)
    static = _build_static(source)
    static_raw = validation.json_bytes(static)
    plan = _build_plan(root, source, static_raw)
    plan_raw = validation.json_bytes(plan)
    history = _build_history(source, static_raw, plan_raw)
    history_raw = validation.json_bytes(history)
    checkpoint = _build_checkpoint(
        source,
        static_raw,
        plan_raw,
        history,
        history_raw,
    )
    checkpoint_raw = validation.json_bytes(checkpoint)
    package = _build_package(
        source,
        static,
        static_raw,
        plan,
        plan_raw,
        history,
        history_raw,
        checkpoint,
        checkpoint_raw,
        root,
    )
    package_raw = validation.json_bytes(package)
    output_manifest = _build_output_manifest(
        static,
        static_raw,
        plan,
        plan_raw,
        history,
        history_raw,
        checkpoint,
        checkpoint_raw,
        package,
        package_raw,
        root,
    )
    outputs = {
        validation.STATIC_NAME: static_raw,
        validation.PLAN_NAME: plan_raw,
        validation.HISTORY_NAME: history_raw,
        validation.CHECKPOINT_NAME: checkpoint_raw,
        validation.PACKAGE_NAME: package_raw,
        validation.OUTPUT_MANIFEST_NAME: validation.json_bytes(output_manifest),
    }
    return outputs


def build_outputs(root: Path = REPO_ROOT) -> dict[str, bytes]:
    root = root.resolve(strict=True)
    validation.validate_failed_r001_bundle(root)
    outputs = construct_outputs_unvalidated(root)
    validation.validate_failed_r001_bundle(root)
    # Semantic validation happens before the first final publication write.
    validation.validate_bundle_bytes(root, outputs, mode="CANDIDATE")
    return outputs


def _rename_noreplace(parent_fd: int, source: str, target: str) -> None:
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    if renameat2 is None:
        raise validation.ValidationError(
            "atomic renameat2(RENAME_NOREPLACE) is unavailable"
        )
    renameat2.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    ]
    renameat2.restype = ctypes.c_int
    result = renameat2(
        parent_fd,
        os.fsencode(source),
        parent_fd,
        os.fsencode(target),
        1,  # RENAME_NOREPLACE
    )
    if result != 0:
        error = ctypes.get_errno()
        if error == errno.EEXIST:
            raise FileExistsError(f"add-only target exists: {target}")
        raise OSError(error, os.strerror(error), target)


def _read_staging_outputs(stage_fd: int) -> dict[str, bytes]:
    stage_before = os.fstat(stage_fd)
    validation.require(
        stat.S_ISDIR(stage_before.st_mode),
        "candidate staging target is not a directory",
    )
    validation.require(
        stat.S_IMODE(stage_before.st_mode) == 0o700,
        "candidate staging directory mode is not 0700",
    )
    validation.require(
        (stage_before.st_uid, stage_before.st_gid) == (os.getuid(), os.getgid()),
        "candidate staging directory owner mismatch",
    )
    names = tuple(sorted(os.listdir(stage_fd), key=os.fsencode))
    validation.require(
        names == tuple(sorted(validation.OUTPUT_NAMES, key=os.fsencode)),
        "candidate staging entry set mismatch",
    )
    observed: dict[str, bytes] = {}
    for name in validation.OUTPUT_NAMES:
        try:
            member_before = os.stat(
                name,
                dir_fd=stage_fd,
                follow_symlinks=False,
            )
        except OSError as exc:
            raise validation.ValidationError(
                f"candidate staging member cannot be inspected: {name}: {exc}"
            ) from exc
        validation.require(
            stat.S_ISREG(member_before.st_mode),
            f"candidate staging member is not regular: {name}",
        )
        validation.require(
            member_before.st_nlink == 1,
            f"candidate staging member link count is not one: {name}",
        )
        validation.require(
            (member_before.st_uid, member_before.st_gid)
            == (stage_before.st_uid, stage_before.st_gid),
            f"candidate staging member owner mismatch: {name}",
        )
        validation.require(
            not member_before.st_mode & stat.S_IWOTH,
            f"candidate staging member is world-writable: {name}",
        )
        try:
            descriptor = os.open(
                name,
                os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0),
                dir_fd=stage_fd,
            )
        except OSError as exc:
            raise validation.ValidationError(
                f"candidate staging member cannot be opened: {name}: {exc}"
            ) from exc
        try:
            opened = os.fstat(descriptor)
            validation.require(
                validation._physical_stat_fingerprint(opened)
                == validation._physical_stat_fingerprint(member_before),
                f"candidate staging member identity changed before read: {name}",
            )
            chunks: list[bytes] = []
            while True:
                chunk = os.read(descriptor, 1024 * 1024)
                if not chunk:
                    break
                chunks.append(chunk)
            member_after = os.fstat(descriptor)
        finally:
            os.close(descriptor)
        member_closed = os.stat(
            name,
            dir_fd=stage_fd,
            follow_symlinks=False,
        )
        validation.require(
            validation._physical_stat_fingerprint(member_before)
            == validation._physical_stat_fingerprint(member_after)
            == validation._physical_stat_fingerprint(member_closed),
            f"candidate staging member changed during read: {name}",
        )
        content = b"".join(chunks)
        validation.require(
            len(content) == member_before.st_size,
            f"candidate staging member size changed during read: {name}",
        )
        observed[name] = content
    validation.require(
        tuple(sorted(os.listdir(stage_fd), key=os.fsencode)) == names
        and validation._physical_stat_fingerprint(os.fstat(stage_fd))
        == validation._physical_stat_fingerprint(stage_before),
        "candidate staging directory changed during read",
    )
    return observed


def _cleanup_staging(
    parent_fd: int,
    stage_name: str,
    created: list[str],
) -> None:
    cleanup_fd: int | None = None
    try:
        cleanup_fd = os.open(
            stage_name,
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=parent_fd,
        )
        for name in reversed(created):
            try:
                os.unlink(name, dir_fd=cleanup_fd)
            except FileNotFoundError:
                pass
        os.close(cleanup_fd)
        cleanup_fd = None
        os.rmdir(stage_name, dir_fd=parent_fd)
    except OSError:
        pass
    finally:
        if cleanup_fd is not None:
            os.close(cleanup_fd)


def write_add_only(
    root: Path,
    outputs: Mapping[str, bytes],
) -> str:
    root = root.resolve(strict=True)
    outputs = dict(outputs)
    validation.validate_failed_r001_bundle(root)
    validation.require(
        set(outputs) == set(validation.OUTPUT_NAMES),
        "candidate publication output set",
    )
    validation.require(
        all(type(outputs[name]) is bytes for name in validation.OUTPUT_NAMES),
        "candidate publication outputs must be exact bytes",
    )
    validation.validate_bundle_bytes(root, outputs, mode="CANDIDATE")
    parent_rel = validation.PLAN_ROOT_REL
    parent = root / parent_rel
    validation.require(parent.is_dir(), "candidate publication parent missing")
    validation.require(not parent.is_symlink(), "publication parent symlink")
    parent_fd = os.open(
        parent,
        os.O_RDONLY
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    stage_name = (
        f".{Path(validation.BUNDLE_REL).name}.staging-"
        f"{os.getpid()}-{secrets.token_hex(8)}"
    )
    stage_fd: int | None = None
    created: list[str] = []
    published = False
    staging_exists = False
    try:
        target_name = Path(validation.BUNDLE_REL).name
        try:
            os.stat(target_name, dir_fd=parent_fd, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            raise validation.ValidationError(PUBLICATION_RESULT_EXISTING_TARGET_TERMINAL)
        os.mkdir(stage_name, 0o700, dir_fd=parent_fd)
        staging_exists = True
        stage_fd = os.open(
            stage_name,
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=parent_fd,
        )
        for name in validation.OUTPUT_NAMES:
            content = outputs[name]
            descriptor = os.open(
                name,
                os.O_WRONLY
                | os.O_CREAT
                | os.O_EXCL
                | getattr(os, "O_NOFOLLOW", 0),
                0o644,
                dir_fd=stage_fd,
            )
            created.append(name)
            try:
                remaining = memoryview(content)
                while remaining:
                    written = os.write(descriptor, remaining)
                    validation.require(written > 0, f"zero-byte write: {name}")
                    remaining = remaining[written:]
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        os.fsync(stage_fd)
        staged_outputs = _read_staging_outputs(stage_fd)
        validation.require(
            staged_outputs == outputs,
            "candidate staging bytes differ from deterministic outputs",
        )
        validation.validate_bundle_bytes(
            root,
            staged_outputs,
            mode="CANDIDATE",
        )
        os.close(stage_fd)
        stage_fd = None
        validation.validate_failed_r001_bundle(root)
        try:
            _rename_noreplace(parent_fd, stage_name, target_name)
        except FileExistsError:
            _cleanup_staging(parent_fd, stage_name, created)
            staging_exists = False
            raise validation.ValidationError(PUBLICATION_RESULT_EXISTING_TARGET_TERMINAL)
        published = True
        staging_exists = False
        os.fsync(parent_fd)
        validation.validate_failed_r001_bundle(root)
        observed = validation.read_candidate_bundle(root)
        validation.require(
            observed == outputs,
            "published candidate differs from deterministic outputs",
        )
        validation.validate_bundle_bytes(root, observed, mode="CANDIDATE")
        return PUBLICATION_RESULT_PUBLISHED_NEW
    except BaseException:
        if stage_fd is not None:
            os.close(stage_fd)
        if not published and staging_exists:
            _cleanup_staging(parent_fd, stage_name, created)
        raise
    finally:
        os.close(parent_fd)


def check_outputs(root: Path, expected: Mapping[str, bytes]) -> None:
    root = root.resolve(strict=True)
    validation.validate_failed_r001_bundle(root)
    observed = validation.read_candidate_bundle(root)
    validation.require(
        observed == dict(expected),
        "candidate outputs differ from deterministic rebuild",
    )
    validation.validate_bundle_bytes(root, observed, mode="CANDIDATE")
    validation.validate_failed_r001_bundle(root)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    args = parser.parse_args()
    try:
        root = args.root.resolve(strict=True)
        outputs = build_outputs(root)
        if args.check:
            check_outputs(root, outputs)
            mode = "CHECK"
            publication_result = "NOT_APPLICABLE"
        else:
            publication_result = write_add_only(root, outputs)
            mode = "WRITE"
    except (
        FileExistsError,
        OSError,
        TypeError,
        validation.ValidationError,
        ValueError,
    ) as exc:
        print(f"WalkSafe v2.5 control candidate: FAIL: {exc}", file=sys.stderr)
        return 1
    package_sha = validation.sha256_bytes(outputs[validation.PACKAGE_NAME])
    print(
        "WalkSafe v2.5 control candidate: PASS "
        f"mode={mode} candidate_id={validation.PACKAGE_CANDIDATE_ID} "
        f"package_sha256={package_sha} publication_result={publication_result}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
