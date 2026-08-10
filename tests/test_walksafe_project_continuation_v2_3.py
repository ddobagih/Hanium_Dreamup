import copy
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts import check_walksafe_project_continuation_v2_3 as continuation


ROOT = Path(__file__).resolve().parents[1]
CHECKPOINT = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"


def binding_by_role(checkpoint: dict, role: str) -> dict:
    return next(
        binding
        for binding in checkpoint["canonical_bindings"]
        if binding["role"] == role
    )


class WalkSafeProjectContinuationV23Test(unittest.TestCase):
    def test_current_checkpoint_is_valid(self) -> None:
        self.assertEqual(continuation.validate(CHECKPOINT, ROOT), [])

    def test_binding_hash_tampering_is_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        binding_by_role(tampered, "POLICY_BASELINE")["file_sha256"] = "0" * 64

        with tempfile.TemporaryDirectory() as temp_dir:
            tampered_path = Path(temp_dir) / "checkpoint.json"
            tampered_path.write_text(
                json.dumps(tampered, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            errors = continuation.validate(tampered_path, ROOT)

        self.assertTrue(any("POLICY_BASELINE file SHA-256" in error for error in errors))

    def test_malformed_checkpoint_metadata_fails_closed_without_crashing(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        tampered["metadata"] = []

        errors = self._validate_temporary(tampered)

        self.assertIn("checkpoint metadata must be an object", errors)

    def test_non_object_canonical_binding_fails_closed_without_crashing(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        tampered["canonical_bindings"][0] = 1

        errors = self._validate_temporary(tampered)

        self.assertIn("canonical binding 0 must be an object", errors)
        self.assertTrue(any("canonical bindings missing roles" in error for error in errors))

    def test_invalid_core_id_types_fail_closed_without_crashing(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        tampered["metadata"]["runbook_path"] = ["not", "a", "path"]
        tampered["current_work"]["work_item_id"] = ["not", "an", "id"]
        tampered["goal_execution"]["focus_goal_id"] = [
            "not",
            "a",
            "goal",
        ]

        errors = self._validate_temporary(tampered)

        self.assertIn("checkpoint metadata runbook_path must be a string", errors)
        self.assertIn("current_work work_item_id must be a string", errors)
        self.assertIn("goal_execution focus_goal_id must be a string", errors)

    def test_duplicate_canonical_binding_role_remains_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        tampered["canonical_bindings"].append(
            copy.deepcopy(tampered["canonical_bindings"][0])
        )

        errors = self._validate_temporary(tampered)

        duplicate_role = tampered["canonical_bindings"][0]["role"]
        self.assertIn(f"duplicate canonical binding role: {duplicate_role}", errors)

    def test_absolute_parent_and_nul_binding_paths_fail_closed(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        unsafe_values = (
            "/etc/passwd",
            "../outside.json",
            "docs/control/\0outside.json",
        )

        for unsafe_value in unsafe_values:
            with self.subTest(path=repr(unsafe_value)):
                tampered = copy.deepcopy(checkpoint)
                binding = tampered["canonical_bindings"][0]
                role = binding["role"]
                binding["path"] = unsafe_value

                errors = self._validate_temporary(tampered)

                self.assertTrue(
                    any(
                        "canonical binding path is unsafe or missing: "
                        f"{role}" in error
                        for error in errors
                    )
                )

    def test_intermediate_symlink_repo_path_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            actual = root / "actual"
            actual.mkdir()
            (actual / "document.json").write_text("{}\n", encoding="utf-8")
            (root / "linked").symlink_to(actual, target_is_directory=True)

            resolved = continuation.resolve_safe_repo_file(
                root,
                "linked/document.json",
            )
            with self.assertRaises(ValueError):
                continuation.working_snapshot_hashes(
                    root,
                    ["linked/document.json"],
                )

        self.assertIsNone(resolved)

    def test_release_and_gate_boundary_are_fail_closed(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        self.assertEqual(
            checkpoint["schema_version"],
            continuation.EXPECTED_CHECKPOINT_SCHEMA_VERSION,
        )
        self.assertEqual(checkpoint["approved_state"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(checkpoint["approved_state"]["remaining_gates_waived"])
        self.assertEqual(checkpoint["verification_boundary"]["all_remaining_gate_status"], "NOT_RUN")
        self.assertEqual(checkpoint["verification_boundary"]["actual_device_test_status"], "NOT_RUN")
        self.assertEqual(checkpoint["verification_boundary"]["approved_production_profile_count"], 0)
        self.assertFalse(checkpoint["verification_boundary"]["release_eligible"])
        self.assertEqual(
            checkpoint["approved_state"]["artifact_state_counts"],
            continuation.EXPECTED_ARTIFACT_STATE_COUNTS,
        )

    def test_unsafe_work_state_and_release_tampering_are_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        tampered["current_work"]["status"] = "COMPLETE"
        tampered["current_work"]["source_policy_ids"].reverse()
        tampered["current_work"]["next_action"] = "git clean -fdx"
        tampered["verification_boundary"]["remaining_gate_ids"] = ["FAKE-GATE"]
        tampered["verification_boundary"]["release_eligible"] = True

        errors = self._validate_temporary(tampered)

        self.assertTrue(any("current work status" in error for error in errors))
        self.assertTrue(any("current work ordered policies" in error for error in errors))
        self.assertTrue(any("unsafe fragments" in error for error in errors))
        self.assertTrue(any("verification gate IDs" in error for error in errors))
        self.assertTrue(any("release eligible boundary" in error for error in errors))

    def test_backlog_epic_scope_cannot_be_misread_as_goal_leaf_scope(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        tampered["current_work"]["status_scope"] = "GOAL_STATUS"
        tampered["current_work"]["scope_kind"] = "FOCUS_LEAF"
        tampered["current_work"]["work_item_id_semantics"] = "BULK_COMPLETION_SCOPE"

        errors = self._validate_temporary(tampered)

        self.assertTrue(any("current work status scope" in error for error in errors))
        self.assertTrue(any("current work scope kind" in error for error in errors))
        self.assertTrue(any("current work item ID semantics" in error for error in errors))

    def test_repository_binding_and_handoff_tampering_are_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        tampered["repository"]["root"] = "/tmp/not-walksafe"
        tampered["repository"]["snapshot_base_head"] = "0" * 40
        binding_by_role(tampered, "REQUIREMENTS_TRACEABILITY")[
            "document_id"
        ] = "REQ-16"
        tampered["session_handoff"]["changed_files"] = []
        tampered["session_handoff"]["last_verification_status"] = "PASS"

        errors = self._validate_temporary(tampered)

        self.assertTrue(any("repository root" in error for error in errors))
        self.assertTrue(any("snapshot base HEAD" in error for error in errors))
        self.assertTrue(any("REQUIREMENTS_TRACEABILITY document ID" in error for error in errors))
        self.assertTrue(any("session handoff field is empty: changed_files" in error for error in errors))
        self.assertTrue(any("handoff verification status" in error for error in errors))

    def test_working_snapshot_tampering_is_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        tampered["working_tree_snapshot"]["content_set_sha256"] = "0" * 64

        errors = self._validate_temporary(tampered)

        self.assertTrue(any("working snapshot content-set SHA-256" in error for error in errors))

    def test_rehashed_snapshot_omission_is_rejected_by_exact_manifest(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        paths = tampered["working_tree_snapshot"]["managed_changed_paths"]
        paths.remove("apps/android-gateway/server.ts")
        path_hash, content_hash = continuation.working_snapshot_hashes(ROOT, paths)
        tampered["working_tree_snapshot"]["managed_changed_path_count"] = len(paths)
        tampered["working_tree_snapshot"]["path_set_sha256"] = path_hash
        tampered["working_tree_snapshot"]["content_set_sha256"] = content_hash
        tampered["session_handoff"]["changed_files"] = paths
        source = tampered["session_handoff"]["source_commit_or_snapshot"]
        source["file_count"] = len(paths)
        source["path_set_sha256"] = path_hash
        source["content_set_sha256"] = content_hash

        errors = self._validate_temporary(tampered)

        self.assertTrue(any("exact controlled path manifest" in error for error in errors))

    def test_transaction_and_formal_test_count_tampering_are_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        tampered["approved_state"]["transaction_status"] = "ABORTED"
        tampered["approved_state"]["artifact_state_counts"]["APPROVED_BASELINED"] = 101
        tampered["approved_state"]["formal_test_not_run_count"] = 0
        tampered["verification_boundary"]["formal_test_not_run_count"] = 0

        errors = self._validate_temporary(tampered)

        self.assertTrue(any("approved transaction" in error for error in errors))
        self.assertTrue(any("approved artifact state counts" in error for error in errors))
        self.assertTrue(any("formal test NOT_RUN count" in error for error in errors))
        self.assertTrue(any("verification formal test NOT_RUN" in error for error in errors))

    def test_fake_verification_and_missing_work_blockers_are_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        tampered["session_handoff"]["verification_commands_and_results"] = [
            {
                "id": "CONTINUATION_INTEGRITY",
                "command": "true",
                "status": "PASS",
                "result": "279 formal tests passed; release ready",
            },
        ]
        tampered["session_handoff"]["remaining_blockers_and_gates"] = [
            item
            for item in tampered["session_handoff"]["remaining_blockers_and_gates"]
            if item.get("kind") == "RELEASE_GATE"
        ]

        errors = self._validate_temporary(tampered)

        self.assertTrue(any("handoff verification IDs" in error for error in errors))
        self.assertTrue(any("CONTINUATION_INTEGRITY command" in error for error in errors))
        self.assertTrue(any("forbidden completion claim" in error for error in errors))
        self.assertTrue(any("omits implementation blockers" in error for error in errors))

    def test_false_current_focus_completion_claim_is_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        tampered["current_work"]["last_completed_work_summary"] = "EPIC-01 완료, 정식 시험 PASS"
        tampered["current_work"]["current_focus"] = "출시 가능"

        errors = self._validate_temporary(tampered)

        self.assertTrue(any("current work last completed summary" in error for error in errors))
        self.assertTrue(any("current work current focus" in error for error in errors))

    def test_completed_gateway_extraction_is_not_left_as_an_open_blocker(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        blocker_ids = {
            item["id"]
            for item in checkpoint["session_handoff"]["remaining_blockers_and_gates"]
        }

        self.assertNotIn("EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE", blocker_ids)
        self.assertNotIn("EPIC-01-NEXT-BFF-EXTRACTION", blocker_ids)
        self.assertIn("PHASE-E-GATEWAY-DEPLOYMENT-NOT-RUN", blocker_ids)
        self.assertIn("PHASE-E-ACTUAL-DEVICE-CONNECTIVITY-NOT-RUN", blocker_ids)
        self.assertNotIn("EPIC-01-PURPOSE-SURFACES", blocker_ids)
        self.assertNotIn("EPIC-01-NO-DESTINATION-HAZARD-CONFORMANCE", blocker_ids)
        self.assertNotIn("EPIC-02-FP017-WALK-SESSION-LIFECYCLE", blocker_ids)
        self.assertIn("EPIC-02-FP017-ACTUAL-DEVICE-LIFECYCLE", blocker_ids)
        self.assertIn("EPIC-02-FP017-FORMAL", blocker_ids)
        self.assertNotIn("EPIC-02-FP018-WALK-STATE-RECOVERY", blocker_ids)
        self.assertIn("EPIC-02-FP018-ACTUAL-DEVICE-LIFECYCLE", blocker_ids)
        self.assertIn("EPIC-02-FP018-FORMAL", blocker_ids)
        self.assertNotIn("EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE", blocker_ids)
        self.assertIn("EPIC-02-NPC-ACTUAL-DEVICE-LIFECYCLE", blocker_ids)
        self.assertIn("EPIC-02-NPC-FORMAL", blocker_ids)
        self.assertIn("EPIC-02-NPC-EXTERNAL-RIGHTS-OPERATION", blocker_ids)
        self.assertNotIn("EPIC-02-FP004-PRIORITY-USER", blocker_ids)
        self.assertIn("EPIC-02-FP004-FORMAL", blocker_ids)
        self.assertIn("EPIC-02-FP004-PRIORITY-USER-TEST", blocker_ids)
        self.assertIn("EPIC-02-FP004-ACTUAL-DEVICE", blocker_ids)
        self.assertIn(
            "EPIC-02-FP004-GUARDIAN-VERIFICATION-PROVIDER",
            blocker_ids,
        )
        self.assertNotIn(
            "EPIC-02-FP005-OFFICIAL-ENVIRONMENT-CROSSWALK",
            blocker_ids,
        )
        for blocker_id in (
            "EPIC-02-FP005-FORMAL",
            "EPIC-02-FP005-ENVIRONMENT-FIELD",
            "EPIC-02-FP005-ACTUAL-USER",
            "EPIC-02-FP005-ACTUAL-DEVICE",
            "EPIC-02-FP005-PRODUCTION-PROFILE",
        ):
            self.assertIn(blocker_id, blocker_ids)
        self.assertNotIn(
            "EPIC-02-FP006-SOLO-WALK-PHONE-MOUNTING",
            blocker_ids,
        )

    def test_phase_d_open_evidence_limitations_cannot_be_omitted_or_promoted(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        items = tampered["session_handoff"]["remaining_blockers_and_gates"]
        manual = next(
            item for item in items if item["id"] == "PHASE-D-ARBITRARY-MANUAL-NEXT-BYPASS"
        )
        manual["status"] = "COMPLETE"
        items[:] = [
            item
            for item in items
            if item["id"] != "PHASE-D-HISTORICAL-EXTERNAL-URL-DECOMMISSION"
        ]

        errors = self._validate_temporary(tampered)

        self.assertTrue(
            any(
                "handoff blocker PHASE-D-ARBITRARY-MANUAL-NEXT-BYPASS status" in error
                for error in errors
            )
        )
        self.assertTrue(any("omits implementation blockers" in error for error in errors))

    def test_phase_e_not_run_boundaries_and_removed_work_item_are_fail_closed(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        items = tampered["session_handoff"]["remaining_blockers_and_gates"]
        deployment = next(
            item for item in items if item["id"] == "PHASE-E-GATEWAY-DEPLOYMENT-NOT-RUN"
        )
        deployment["status"] = "PASS"
        items[:] = [
            item
            for item in items
            if item["id"] != "PHASE-E-ACTUAL-DEVICE-CONNECTIVITY-NOT-RUN"
        ]
        items.append(
            {
                "id": "EPIC-01-NEXT-BFF-EXTRACTION",
                "kind": "IMPLEMENTATION_WORK",
                "status": "OPEN",
            }
        )

        errors = self._validate_temporary(tampered)

        self.assertTrue(
            any(
                "handoff blocker PHASE-E-GATEWAY-DEPLOYMENT-NOT-RUN status" in error
                for error in errors
            )
        )
        self.assertTrue(any("omits implementation blockers" in error for error in errors))
        self.assertTrue(any("stale or unexpected implementation blockers" in error for error in errors))

    def test_unexecuted_result_claim_is_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        tampered["session_handoff"]["verification_commands_and_results"][0][
            "result"
        ] = "실제로 실행하지 않았지만 PASS로 기록"

        errors = self._validate_temporary(tampered)

        self.assertTrue(any("ADMIN_ANDROID_PHASE_B result" in error for error in errors))

    def test_phase_b_live_regeneration_is_explicitly_expected_stale(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        verifications = {
            item["id"]: item
            for item in checkpoint["session_handoff"]["verification_commands_and_results"]
        }

        self.assertEqual(verifications["PHASE_B_TRACE"]["status"], "EXPECTED_STALE")
        self.assertIn("Phase C 이후", verifications["PHASE_B_TRACE"]["result"])
        self.assertIn(
            "--deselect=tests/test_walksafe_epic01_phase_b_trace_20260722.py::"
            "WalkSafeEpic01PhaseBTraceTest::test_generated_files_are_current_and_deterministic",
            verifications["CONTINUATION_INTEGRITY"]["command"],
        )

    def test_release_ready_result_alias_is_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        tampered["session_handoff"]["verification_commands_and_results"][1][
            "result"
        ] = "모든 검증이 끝났고 배포 준비 완료"

        errors = self._validate_temporary(tampered)

        self.assertTrue(any("BACKEND_PHASE_B result" in error for error in errors))

    def test_r013_gap_and_backlog_counts_are_current_without_release_claim(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)

        self.assertEqual(
            checkpoint["implementation_gap_snapshot"]["status_counts"],
            {
                "CONFLICTING": 18,
                "MISSING": 16,
                "PARTIAL": 25,
                "EVIDENCE_MISSING": 4,
                "BLOCKED": 5,
                "IMPLEMENTED": 0,
            },
        )
        self.assertEqual(
            checkpoint["implementation_gap_snapshot"]["report_id"],
            continuation.EXPECTED_R013_GAP_DOCUMENT_ID,
        )
        self.assertEqual(
            checkpoint["implementation_gap_snapshot"]["backlog_id"],
            continuation.EXPECTED_R013_BACKLOG_DOCUMENT_ID,
        )
        self.assertEqual(
            checkpoint["implementation_gap_snapshot"]["epic_status_counts"],
            {"IMPLEMENTATION_READY": 1, "IN_PROGRESS": 1, "PLANNED": 10},
        )
        self.assertEqual(checkpoint["current_work"]["status"], "IN_PROGRESS")
        self.assertEqual(
            checkpoint["current_work"]["status_scope"],
            "IMPLEMENTATION_BACKLOG_EPIC_STATUS_NOT_GOAL_STATUS",
        )
        self.assertEqual(
            checkpoint["current_work"]["scope_kind"],
            "BACKLOG_EPIC_AGGREGATE",
        )
        self.assertEqual(
            checkpoint["current_work"]["next_action"],
            continuation.EXPECTED_FP010_NEXT_ACTION,
        )
        gap = continuation.load_json(
            ROOT / binding_by_role(checkpoint, "IMPLEMENTATION_GAP")["path"]
        )
        assessments = {item["gap_id"]: item for item in gap["assessments"]}
        expected = {
            "GAP-006": "PARTIAL",
            "GAP-013": "PARTIAL",
            "GAP-014": "PARTIAL",
            "GAP-015": "PARTIAL",
            "GAP-019": "MISSING",
            "GAP-026": "PARTIAL",
            "GAP-027": "PARTIAL",
        }
        self.assertEqual(
            {gap_id: assessments[gap_id]["status"] for gap_id in expected},
            expected,
        )
        self.assertTrue(
            all(assessments[gap_id]["formal_test_status"] == "NOT_RUN" for gap_id in expected)
        )

    def test_phase_b_binding_identity_tampering_is_rejected_by_role(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        roles = (
            "EPIC01_PHASE_B_RECORD",
            "SINGLE_ADMIN_RECOVERY_DRILL_PROTOCOL",
            "EPIC01_PHASE_B_ACTIVE_OVERLAY",
        )
        for role in roles:
            binding_by_role(tampered, role)["document_id"] = f"TAMPERED-{role}"

        errors = self._validate_temporary(tampered)

        for role in roles:
            self.assertTrue(
                any(f"{role} document ID" in error for error in errors),
                role,
            )

    def test_epic02_phase_a_canonical_bindings_point_to_latest_successors(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        expected_bindings = copy.deepcopy(
            continuation.EXPECTED_SUCCESSOR_CANONICAL_BINDINGS
        )
        expected_bindings.update(
            {
                "IMPLEMENTATION_GAP": {
                    "path": continuation.EXPECTED_R013_GAP_PATH,
                    "document_id": continuation.EXPECTED_R013_GAP_DOCUMENT_ID,
                    "identity_json_path": "metadata.report_id",
                    "mutable": False,
                },
                "IMPLEMENTATION_BACKLOG": {
                    "path": continuation.EXPECTED_R013_BACKLOG_PATH,
                    "document_id": continuation.EXPECTED_R013_BACKLOG_DOCUMENT_ID,
                    "identity_json_path": "metadata.backlog_id",
                    "mutable": False,
                },
                continuation.EXPECTED_FP006_COMPLETION_ROLE: {
                    "path": continuation.EXPECTED_FP006_COMPLETION_RECEIPT_PATH,
                    "document_id": (
                        continuation.EXPECTED_FP006_COMPLETION_RECEIPT_ID
                    ),
                    "identity_json_path": "document_id",
                    "mutable": False,
                },
            }
        )
        for role in (
            "IMPLEMENTATION_GAP",
            "IMPLEMENTATION_BACKLOG",
            "EPIC01_PHASE_C_RECORD",
            "EPIC01_PHASE_C_ACTIVE_OVERLAY",
            "EPIC01_PHASE_D_RECORD",
            "EPIC01_PHASE_D_ACTIVE_OVERLAY",
            "EPIC01_PHASE_E_RECORD",
            "EPIC01_PHASE_E_ACTIVE_OVERLAY",
            "EPIC01_PHASE_F_RECORD",
            "EPIC01_PHASE_F_ACTIVE_OVERLAY",
            "EPIC01_PHASE_G_RECORD",
            "EPIC01_PHASE_G_ACTIVE_OVERLAY",
            "EPIC02_PHASE_A_RECORD",
            "EPIC02_PHASE_A_ACTIVE_OVERLAY",
            "WORK_ITEM_COMPLETION::WS-GOAL-EPIC-02-FP-004-R001",
            continuation.EXPECTED_FP005_COMPLETION_ROLE,
            continuation.EXPECTED_FP006_COMPLETION_ROLE,
        ):
            binding = binding_by_role(checkpoint, role)
            expected = expected_bindings[role]
            for field, value in expected.items():
                self.assertEqual(binding[field], value, f"{role}.{field}")
        phase_d = continuation.load_json(
            ROOT / binding_by_role(checkpoint, "EPIC01_PHASE_D_RECORD")["path"]
        )
        self.assertEqual(
            phase_d["next_single_action"]["work_item_id"],
            continuation.EXPECTED_PHASE_D_NEXT_WORK_ITEM_ID,
        )
        self.assertEqual(
            phase_d["next_single_action"]["action"],
            continuation.EXPECTED_PHASE_D_NEXT_ACTION,
        )

    def test_phase_c_binding_path_tampering_is_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        binding_by_role(tampered, "IMPLEMENTATION_GAP")["path"] = (
            "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r002.json"
        )

        errors = self._validate_temporary(tampered)

        self.assertTrue(
            any("IMPLEMENTATION_GAP canonical binding path" in error for error in errors)
        )

    def test_phase_f_binding_identity_and_predecessor_fallback_are_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        tampered = copy.deepcopy(checkpoint)
        binding_by_role(tampered, "EPIC01_PHASE_F_RECORD")["document_id"] = (
            "WS-EPIC-01-PHASE-E-ANDROID-GATEWAY-IMPLEMENTATION-20260723-001"
        )
        binding_by_role(tampered, "IMPLEMENTATION_BACKLOG")["path"] = (
            "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r005.json"
        )

        errors = self._validate_temporary(tampered)

        self.assertTrue(any("EPIC01_PHASE_F_RECORD document ID" in error for error in errors))
        self.assertTrue(
            any("IMPLEMENTATION_BACKLOG canonical binding path" in error for error in errors)
        )

    def test_immutable_predecessor_outputs_are_byte_exact(self) -> None:
        self.assertTrue(
            set(continuation.EXPECTED_PHASE_E_TRACE_PATHS).issubset(
                continuation.EXPECTED_IMMUTABLE_PREDECESSOR_SHA256
            )
        )
        self.assertTrue(
            set(continuation.EXPECTED_PHASE_F_TRACE_PATHS).issubset(
                continuation.EXPECTED_IMMUTABLE_PREDECESSOR_SHA256
            )
        )
        self.assertTrue(
            set(continuation.EXPECTED_PHASE_G_TRACE_PATHS).issubset(
                continuation.EXPECTED_IMMUTABLE_PREDECESSOR_SHA256
            )
        )
        self.assertTrue(
            set(continuation.EXPECTED_EPIC02_PHASE_A_TRACE_PATHS).issubset(
                continuation.EXPECTED_IMMUTABLE_PREDECESSOR_SHA256
            )
        )
        for relative, expected in continuation.EXPECTED_IMMUTABLE_PREDECESSOR_SHA256.items():
            self.assertEqual(continuation.sha256_file(ROOT / relative), expected, relative)

    def test_active_goal_package_is_v23_and_v22_v21_v20_anchors_are_preserved(self) -> None:
        checkpoint = self._prepared_checkpoint_fixture()
        goal_execution = checkpoint["goal_execution"]
        prepared = goal_execution["transition_history"][0]
        prepared_runtime = prepared["runtime_after"]

        self.assertEqual(checkpoint["schema_version"], "1.14.0")
        self.assertEqual(checkpoint["metadata"]["version"], "1.14.0")
        self.assertEqual(goal_execution["schema_version"], "2.1")
        self.assertEqual(
            goal_execution["package_id"],
            continuation.EXPECTED_ACTIVE_GOAL_PACKAGE_ID,
        )
        self.assertEqual(
            prepared_runtime["package_status"],
            "PREPARED_NOT_ACTIVATED",
        )
        self.assertEqual(
            prepared_runtime["activation_status"],
            "READY_NOT_ACTIVATED",
        )
        self.assertEqual(
            goal_execution["static_plan_manifest_path"],
            continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_PATH,
        )
        self.assertEqual(
            prepared_runtime["focus_goal_path"],
            continuation.EXPECTED_ACTIVE_GOAL_FOCUS_PATH,
        )
        self.assertEqual(len(goal_execution["transition_history"]), 1)
        self.assertEqual(prepared["event_type"], "PACKAGE_PREPARED")
        self.assertEqual(
            prepared["status_changes"]["WS-GOAL-EPIC-02-FP-005-R001"],
            "READY",
        )
        self.assertEqual(
            goal_execution["static_plan_version"],
            continuation.EXPECTED_ACTIVE_GOAL_PLAN_VERSION,
        )
        self.assertEqual(
            continuation.sha256_file(
                ROOT / continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_PATH
            ),
            continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256,
        )
        self.assertEqual(
            continuation.sha256_file(
                ROOT / continuation.EXPECTED_V22_GOAL_MANIFEST_PATH
            ),
            continuation.EXPECTED_V22_GOAL_MANIFEST_SHA256,
        )
        self.assertEqual(
            continuation.sha256_file(
                ROOT / continuation.EXPECTED_V21_GOAL_MANIFEST_PATH
            ),
            continuation.EXPECTED_V21_GOAL_MANIFEST_SHA256,
        )
        self.assertEqual(
            continuation.sha256_file(
                ROOT / continuation.EXPECTED_V2_GOAL_MANIFEST_PATH
            ),
            continuation.EXPECTED_V2_GOAL_MANIFEST_SHA256,
        )

    def test_v23_trust_anchors_are_finalized_sha256_values(self) -> None:
        self.assertEqual(
            continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256,
            "dfa615686b0223826497fae424c1f3c41538b271102879a9497a33b81f66329c",
        )
        self.assertEqual(
            continuation.EXPECTED_GOAL_INITIAL_EVENT_SHA256,
            "3e2a585a8224527c5c732c3ac39cfc13fb35ed85613a1f97a0295339c71170ef",
        )

    def test_v23_historical_partial_replays_remain_valid_in_replay_layer(self) -> None:
        fixtures = []
        prepared = self._prepared_checkpoint_fixture()
        fixtures.append(("prepared", prepared))
        activated = self._prepared_checkpoint_fixture()
        self._append_v23_activation(activated)
        fixtures.append(("activated", activated))
        started = self._prepared_checkpoint_fixture()
        self._append_v23_activation(started)
        self._append_v23_start(started)
        fixtures.append(("started", started))

        for label, checkpoint in fixtures:
            with self.subTest(lifecycle=label):
                errors: list[str] = []
                continuation.validate_v23_transition_replay(
                    errors,
                    checkpoint["goal_execution"],
                    ROOT,
                    continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256,
                )
                self.assertEqual(errors, [])
                if label == "started":
                    state = checkpoint["goal_execution"]
                    self.assertEqual(
                        state["status_by_goal"][
                            continuation.EXPECTED_ACTIVE_GOAL_FOCUS_ID
                        ],
                        "IN_PROGRESS",
                    )
                    self.assertEqual(
                        state["status_by_goal"][
                            continuation.EXPECTED_ACTIVE_GOAL_MASTER_ID
                        ],
                        "READY",
                    )
                    self.assertEqual(state["goal_status"], "READY")

    def test_v23_active_lifecycle_without_activation_event_is_rejected(self) -> None:
        checkpoint = self._prepared_checkpoint_fixture()
        self._append_v23_activation(checkpoint)
        self._append_v23_start(checkpoint)
        state = checkpoint["goal_execution"]
        start = state["transition_history"].pop(2)
        state["transition_history"].pop(1)
        start["sequence"] = 2
        start["previous_event_sha256"] = state["transition_history"][0][
            "event_sha256"
        ]
        self._reseal_event(start)
        state["transition_history"].append(start)
        state["transition_history_anchor_sha256"] = start["event_sha256"]

        errors: list[str] = []
        continuation.validate_v23_transition_replay(
            errors,
            state,
            ROOT,
            continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256,
        )

        self.assertTrue(
            any(
                "lacks PACKAGE_ACTIVATED immediately after preparation" in error
                for error in errors
            )
        )
        self.assertTrue(
            any("GOAL_STARTED precedes package activation" in error for error in errors)
        )

    def test_v23_started_status_must_match_event_replay(self) -> None:
        checkpoint = self._prepared_checkpoint_fixture()
        self._append_v23_activation(checkpoint)
        self._append_v23_start(checkpoint)
        state = checkpoint["goal_execution"]
        goal_id = continuation.EXPECTED_ACTIVE_GOAL_FOCUS_ID

        with self.subTest(mismatch="runtime_status"):
            tampered = copy.deepcopy(state)
            tampered["status_by_goal"][goal_id] = "READY"
            errors: list[str] = []
            continuation.validate_v23_transition_replay(
                errors,
                tampered,
                ROOT,
                continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256,
            )
            self.assertTrue(
                any("active Goal replayed statuses" in error for error in errors)
            )

        with self.subTest(mismatch="event_boundary"):
            tampered = copy.deepcopy(state)
            start = tampered["transition_history"][-1]
            start["from_status"] = "PLANNED"
            self._reseal_event(start)
            tampered["transition_history_anchor_sha256"] = start["event_sha256"]
            errors = []
            continuation.validate_v23_transition_replay(
                errors,
                tampered,
                ROOT,
                continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256,
            )
            self.assertTrue(
                any("GOAL_STARTED from status" in error for error in errors)
            )

    def test_v23_goal_status_must_summarize_replayed_master_status(self) -> None:
        checkpoint = self._prepared_checkpoint_fixture()
        self._append_v23_activation(checkpoint)
        self._append_v23_start(checkpoint)
        state = checkpoint["goal_execution"]
        state["goal_status"] = "IN_PROGRESS"

        errors: list[str] = []
        continuation.validate_v23_transition_replay(
            errors,
            state,
            ROOT,
            continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256,
        )

        self.assertTrue(
            any("active Goal replayed master status" in error for error in errors)
        )

    def test_v23_started_focus_must_remain_replay_deterministic(self) -> None:
        checkpoint = self._prepared_checkpoint_fixture()
        self._append_v23_activation(checkpoint)
        self._append_v23_start(checkpoint)
        state = checkpoint["goal_execution"]
        start = state["transition_history"][-1]
        runtime = start["runtime_after"]
        alternate_goal_id = "WS-GOAL-EPIC-03"
        alternate = state["imported_predecessor_goal_bindings"][
            alternate_goal_id
        ]
        runtime.update(
            {
                "focus_goal_id": alternate_goal_id,
                "focus_goal_path": alternate["path"],
                "focus_work_item_id": "",
                "focus_source": "WORKSTREAM_GRAPH",
            }
        )
        start["focus_goal_id"] = alternate_goal_id
        start["focus_goal_content_sha256"] = continuation.sha256_file(
            ROOT / alternate["path"]
        )
        self._reseal_event(start)
        state["transition_history_anchor_sha256"] = start["event_sha256"]
        self._sync_v23_runtime_from_tail(state)

        errors: list[str] = []
        continuation.validate_v23_transition_replay(
            errors,
            state,
            ROOT,
            continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256,
        )

        self.assertTrue(
            any("runtime focus is not replay-deterministic" in error for error in errors)
        )
        self.assertTrue(
            any("started/runtime focus ID" in error for error in errors)
        )

    def test_v23_mixed_ownership_inventory_is_exact(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = checkpoint["goal_execution"]
        imported = state["imported_predecessor_goal_bindings"]
        manifest = continuation.load_json(
            ROOT / continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_PATH
        )
        fp011_materialized = len(state["transition_history"]) >= 16
        expected_successor_paths = (
            continuation.EXPECTED_V23_SUCCESSOR_DYNAMIC_GOAL_PATHS
            if fp011_materialized
            else continuation.EXPECTED_SEQ13_V23_SUCCESSOR_DYNAMIC_GOAL_PATHS
        )
        expected_package_paths = (
            continuation.EXPECTED_GOAL_PACKAGE_PATHS
            if fp011_materialized
            else continuation.EXPECTED_SEQ13_GOAL_PACKAGE_PATHS
        )

        self.assertEqual(manifest["schema_version"], "2.1")
        self.assertEqual(len(imported), 17)
        self.assertEqual(
            sorted(binding["path"] for binding in imported.values()),
            sorted(continuation.EXPECTED_V23_IMPORTED_GOAL_PATHS),
        )
        self.assertTrue(
            all(
                binding["source_package_id"]
                == continuation.EXPECTED_V22_GOAL_PACKAGE_ID
                for binding in imported.values()
            )
        )
        self.assertEqual(
            state["managed_goal_paths"],
            sorted(expected_package_paths),
        )
        self.assertEqual(
            state["goal_document_paths"],
            sorted(
                set(continuation.EXPECTED_V23_IMPORTED_GOAL_PATHS)
                | set(expected_successor_paths)
            ),
        )
        self.assertEqual(
            state["support_paths"],
            sorted(continuation.EXPECTED_V23_NATIVE_PACKAGE_PATHS),
        )
        self.assertEqual(
            state["managed_goal_path_count"],
            len(expected_package_paths),
        )
        self.assertEqual(
            state["goal_document_count"],
            len(continuation.EXPECTED_V23_IMPORTED_GOAL_PATHS)
            + len(expected_successor_paths),
        )
        self.assertEqual(
            continuation.package_regular_file_paths(
                ROOT,
                "docs/control/goals/walksafe-completion-graph-v2-3",
            ),
            tuple(
                sorted(
                    set(continuation.EXPECTED_V23_NATIVE_PACKAGE_PATHS)
                    | set(expected_successor_paths)
                )
            ),
        )
        self.assertEqual(len(manifest["protected_files"]), 5)
        self.assertEqual(
            {record["path"] for record in manifest["protected_files"]},
            set(continuation.EXPECTED_V23_NATIVE_PACKAGE_PATHS)
            - {continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_PATH},
        )
        self.assertEqual(
            manifest["imported_predecessor_goal_contract"][
                "runtime_binding_field"
            ],
            "imported_predecessor_goal_bindings",
        )
        self.assertEqual(
            manifest["imported_predecessor_goal_contract"][
                "expected_goal_count"
            ],
            17,
        )

    def test_frozen_v22_package_and_checkpoint_archive_are_byte_exact(self) -> None:
        self.assertEqual(
            continuation.sha256_file(
                ROOT
                / continuation.EXPECTED_FROZEN_V22_CONTINUATION_CHECKER_PATH
            ),
            continuation.EXPECTED_FROZEN_V22_CONTINUATION_CHECKER_SHA256,
        )
        self.assertEqual(
            continuation.sha256_file(
                ROOT / continuation.EXPECTED_FROZEN_V22_CONTINUATION_TEST_PATH
            ),
            continuation.EXPECTED_FROZEN_V22_CONTINUATION_TEST_SHA256,
        )
        self.assertEqual(
            continuation.package_regular_file_paths(
                ROOT,
                "docs/control/goals/walksafe-completion-graph-v2-2",
            ),
            tuple(sorted(continuation.EXPECTED_FROZEN_V22_GOAL_PACKAGE_PATHS)),
        )
        path_hash, content_hash = continuation.working_snapshot_hashes(
            ROOT,
            list(continuation.EXPECTED_FROZEN_V22_GOAL_PACKAGE_PATHS),
        )
        self.assertEqual(
            path_hash,
            continuation.EXPECTED_V22_PACKAGE_PATH_SET_SHA256,
        )
        self.assertEqual(
            content_hash,
            continuation.EXPECTED_V22_PACKAGE_CONTENT_SET_SHA256,
        )
        self.assertEqual(
            continuation.sha256_file(
                ROOT / continuation.EXPECTED_V22_ARCHIVED_CHECKPOINT_PATH
            ),
            continuation.EXPECTED_V22_ARCHIVED_CHECKPOINT_SHA256,
        )

    def test_frozen_v21_package_is_byte_exact(self) -> None:
        self.assertEqual(
            len(continuation.EXPECTED_FROZEN_V21_GOAL_PACKAGE_PATHS),
            continuation.EXPECTED_V21_PACKAGE_MANAGED_PATH_COUNT,
        )
        self.assertEqual(
            continuation.package_regular_file_paths(
                ROOT,
                "docs/control/goals/walksafe-completion-graph-v2-1",
            ),
            tuple(sorted(continuation.EXPECTED_FROZEN_V21_GOAL_PACKAGE_PATHS)),
        )
        path_hash, content_hash = continuation.working_snapshot_hashes(
            ROOT,
            list(continuation.EXPECTED_FROZEN_V21_GOAL_PACKAGE_PATHS),
        )
        self.assertEqual(
            path_hash,
            continuation.EXPECTED_V21_PACKAGE_PATH_SET_SHA256,
        )
        self.assertEqual(
            content_hash,
            continuation.EXPECTED_V21_PACKAGE_CONTENT_SET_SHA256,
        )

    def test_frozen_v21_manifest_hash_tampering_is_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        target = (ROOT / continuation.EXPECTED_V21_GOAL_MANIFEST_PATH).resolve()
        original_sha256_file = continuation.sha256_file

        def tampered_hash(path: Path) -> str:
            if Path(path).resolve() == target:
                return "0" * 64
            return original_sha256_file(path)

        errors: list[str] = []
        with mock.patch.object(
            continuation,
            "sha256_file",
            side_effect=tampered_hash,
        ):
            continuation.validate_goal_package_binding(
                errors,
                checkpoint["goal_execution"],
                ROOT,
            )

        self.assertTrue(
            any(
                "superseded v2.1 Goal manifest SHA-256" in error
                for error in errors
            )
        )
        self.assertTrue(
            any(
                "superseded v2.1 package content-set SHA-256" in error
                for error in errors
            )
        )

    def test_frozen_v21_protected_file_tampering_is_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        relative = (
            "docs/control/goals/walksafe-completion-graph-v2-1/00-master-goal.md"
        )
        target = (ROOT / relative).resolve()
        original_sha256_file = continuation.sha256_file

        def tampered_hash(path: Path) -> str:
            if Path(path).resolve() == target:
                return "0" * 64
            return original_sha256_file(path)

        errors: list[str] = []
        with mock.patch.object(
            continuation,
            "sha256_file",
            side_effect=tampered_hash,
        ):
            continuation.validate_goal_package_binding(
                errors,
                checkpoint["goal_execution"],
                ROOT,
            )

        self.assertTrue(
            any(
                f"superseded v2.1 protected file SHA-256 {relative}" in error
                for error in errors
            )
        )

    def test_frozen_v21_extra_file_is_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        original_inventory = continuation.package_regular_file_paths

        def inventory_with_extra(root: Path, package_relative: str) -> tuple[str, ...]:
            inventory = original_inventory(root, package_relative)
            if package_relative == (
                "docs/control/goals/walksafe-completion-graph-v2-1"
            ):
                return tuple(sorted((*inventory, f"{package_relative}/extra.md")))
            return inventory

        errors: list[str] = []
        with mock.patch.object(
            continuation,
            "package_regular_file_paths",
            side_effect=inventory_with_extra,
        ):
            continuation.validate_goal_package_binding(
                errors,
                checkpoint["goal_execution"],
                ROOT,
            )

        self.assertTrue(
            any("superseded v2.1 package path inventory" in error for error in errors)
        )

    def test_archived_v21_prepared_event_tampering_is_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        target = (ROOT / continuation.EXPECTED_V21_PREPARED_EVENT_PATH).resolve()
        original_load_json = continuation.load_json

        def tampered_load(path: Path) -> dict:
            document = original_load_json(path)
            if Path(path).resolve() == target:
                document = copy.deepcopy(document)
                document["event_sha256"] = "0" * 64
            return document

        errors: list[str] = []
        with mock.patch.object(
            continuation,
            "load_json",
            side_effect=tampered_load,
        ):
            continuation.validate_goal_package_binding(
                errors,
                checkpoint["goal_execution"],
                ROOT,
            )

        self.assertTrue(
            any(
                "superseded v2.1 prepared event seal is invalid" in error
                for error in errors
            )
        )
        self.assertTrue(
            any(
                "superseded v2.1 prepared event SHA-256" in error
                for error in errors
            )
        )

    def test_unfinalized_active_initial_event_anchor_fails_closed(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        errors: list[str] = []
        with mock.patch.object(
            continuation,
            "EXPECTED_GOAL_INITIAL_EVENT_SHA256",
            "0" * 64,
        ):
            continuation.validate_goal_package_binding(
                errors,
                checkpoint["goal_execution"],
                ROOT,
            )

        self.assertIn(
            "active Goal initial event SHA-256 trust anchor is not finalized",
            errors,
        )

    def test_superseded_v20_manifest_hash_tampering_is_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        target = (ROOT / continuation.EXPECTED_V2_GOAL_MANIFEST_PATH).resolve()
        original_sha256_file = continuation.sha256_file

        def tampered_hash(path: Path) -> str:
            if Path(path).resolve() == target:
                return "0" * 64
            return original_sha256_file(path)

        errors: list[str] = []
        with mock.patch.object(
            continuation,
            "sha256_file",
            side_effect=tampered_hash,
        ):
            continuation.validate_goal_package_binding(
                errors,
                checkpoint["goal_execution"],
                ROOT,
            )

        self.assertTrue(
            any(
                "superseded v2.0 Goal manifest SHA-256" in error
                for error in errors
            )
        )

    def test_v20_to_v11_manifest_chain_tampering_is_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        target = (ROOT / continuation.EXPECTED_V2_GOAL_MANIFEST_PATH).resolve()
        original_load_json = continuation.load_json

        def tampered_load(path: Path) -> dict:
            document = original_load_json(path)
            if Path(path).resolve() == target:
                document = copy.deepcopy(document)
                document["supersedes"]["package_id"] = "WRONG-V1-PACKAGE"
            return document

        errors: list[str] = []
        with mock.patch.object(
            continuation,
            "load_json",
            side_effect=tampered_load,
        ):
            continuation.validate_goal_package_binding(
                errors,
                checkpoint["goal_execution"],
                ROOT,
            )

        self.assertTrue(
            any(
                "superseded v2.0 Goal manifest superseded v1.1 package ID"
                in error
                for error in errors
            )
        )

    def test_phase_a_historical_snapshot_does_not_require_live_source_hashes(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        target = (
            ROOT
            / "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
            "session/WalkSessionLifecycle.kt"
        ).resolve()
        original_sha256_file = continuation.sha256_file

        def changed_live_source_hash(path: Path) -> str:
            if Path(path).resolve() == target:
                return "0" * 64
            return original_sha256_file(path)

        with mock.patch.object(
            continuation,
            "sha256_file",
            side_effect=changed_live_source_hash,
        ):
            errors = continuation.validate(CHECKPOINT, ROOT)

        self.assertFalse(
            any(
                "EPIC-02 Phase A implementation snapshot hashes differ" in error
                for error in errors
            )
        )
        self.assertTrue(
            any("working snapshot content-set SHA-256" in error for error in errors)
        )

    def test_phase_c_controlled_manifest_contains_11_implementation_and_10_trace_paths(self) -> None:
        self.assertEqual(len(continuation.EXPECTED_PHASE_C_IMPLEMENTATION_PATHS), 11)
        self.assertEqual(len(continuation.EXPECTED_PHASE_C_TRACE_PATHS), 10)
        phase_c_paths = (
            set(continuation.EXPECTED_PHASE_C_IMPLEMENTATION_PATHS)
            | set(continuation.EXPECTED_PHASE_C_TRACE_PATHS)
        )
        self.assertTrue(phase_c_paths.issubset(continuation.EXPECTED_CONTROLLED_PATHS))

    def test_phase_d_controlled_manifest_contains_34_implementation_and_10_trace_paths(self) -> None:
        self.assertEqual(len(continuation.EXPECTED_PHASE_D_IMPLEMENTATION_PATHS), 34)
        self.assertEqual(len(continuation.EXPECTED_PHASE_D_TRACE_PATHS), 10)
        phase_d_paths = (
            set(continuation.EXPECTED_PHASE_D_IMPLEMENTATION_PATHS)
            | set(continuation.EXPECTED_PHASE_D_TRACE_PATHS)
        )
        self.assertTrue(phase_d_paths.issubset(continuation.EXPECTED_CONTROLLED_PATHS))

    def test_phase_e_controlled_manifest_contains_41_implementation_and_10_trace_paths(self) -> None:
        self.assertEqual(len(continuation.EXPECTED_PHASE_E_IMPLEMENTATION_PATHS), 41)
        self.assertEqual(len(continuation.EXPECTED_PHASE_E_TRACE_PATHS), 10)
        phase_e_paths = (
            set(continuation.EXPECTED_PHASE_E_IMPLEMENTATION_PATHS)
            | set(continuation.EXPECTED_PHASE_E_TRACE_PATHS)
        )
        self.assertTrue(phase_e_paths.issubset(continuation.EXPECTED_CONTROLLED_PATHS))
        self.assertTrue(
            all(
                not (ROOT / relative).exists()
                for relative in continuation.EXPECTED_PHASE_E_REMOVED_NEXT_ROUTE_PATHS
            )
        )

    def test_phase_f_controlled_manifest_contains_9_implementation_and_10_trace_paths(self) -> None:
        self.assertEqual(len(continuation.EXPECTED_PHASE_F_IMPLEMENTATION_PATHS), 9)
        self.assertEqual(len(continuation.EXPECTED_PHASE_F_TRACE_PATHS), 10)
        phase_f_paths = (
            set(continuation.EXPECTED_PHASE_F_IMPLEMENTATION_PATHS)
            | set(continuation.EXPECTED_PHASE_F_TRACE_PATHS)
        )
        self.assertTrue(phase_f_paths.issubset(continuation.EXPECTED_CONTROLLED_PATHS))

    def test_phase_g_controlled_manifest_contains_15_implementation_and_10_trace_paths(self) -> None:
        self.assertEqual(len(continuation.EXPECTED_PHASE_G_IMPLEMENTATION_PATHS), 15)
        self.assertEqual(len(continuation.EXPECTED_PHASE_G_TRACE_PATHS), 10)
        phase_g_paths = (
            set(continuation.EXPECTED_PHASE_G_IMPLEMENTATION_PATHS)
            | set(continuation.EXPECTED_PHASE_G_TRACE_PATHS)
        )
        self.assertTrue(phase_g_paths.issubset(continuation.EXPECTED_CONTROLLED_PATHS))

    def test_epic02_phase_a_manifest_contains_13_implementation_and_10_trace_paths(self) -> None:
        self.assertEqual(len(continuation.EXPECTED_EPIC02_PHASE_A_IMPLEMENTATION_PATHS), 13)
        self.assertEqual(len(continuation.EXPECTED_EPIC02_PHASE_A_TRACE_PATHS), 10)
        self.assertEqual(
            len(continuation.EXPECTED_FP018_IN_PROGRESS_IMPLEMENTATION_PATHS),
            9,
        )
        self.assertEqual(len(continuation.EXPECTED_FP018_TRACE_PATHS), 16)
        self.assertEqual(
            len(continuation.EXPECTED_FP004_IN_PROGRESS_IMPLEMENTATION_PATHS),
            9,
        )
        self.assertEqual(
            len(continuation.EXPECTED_FP004_INTERRUPTED_TRACE_PATHS),
            2,
        )
        self.assertEqual(len(continuation.EXPECTED_FP004_TRACE_PATHS), 15)
        self.assertEqual(len(continuation.EXPECTED_FP005_IMPLEMENTATION_PATHS), 20)
        self.assertEqual(len(continuation.EXPECTED_FP005_TRACE_PATHS), 16)
        self.assertEqual(
            len(continuation.EXPECTED_NPC_PERMISSION_SESSION_IMPLEMENTATION_PATHS),
            29,
        )
        self.assertEqual(
            len(continuation.EXPECTED_NPC_PERMISSION_SESSION_TRACE_PATHS),
            17,
        )
        self.assertEqual(len(continuation.EXPECTED_SUPERSEDED_GOAL_PACKAGE_PATHS), 23)
        self.assertEqual(len(continuation.EXPECTED_SUPERSEDED_V2_GOAL_PACKAGE_PATHS), 19)
        self.assertEqual(len(continuation.EXPECTED_FROZEN_V21_GOAL_PACKAGE_PATHS), 21)
        self.assertEqual(len(continuation.EXPECTED_FROZEN_V22_GOAL_PACKAGE_PATHS), 23)
        self.assertEqual(len(continuation.EXPECTED_V23_IMPORTED_GOAL_PATHS), 17)
        self.assertEqual(len(continuation.EXPECTED_V23_NATIVE_PACKAGE_PATHS), 6)
        self.assertEqual(
            len(continuation.EXPECTED_SEQ8_V23_SUCCESSOR_DYNAMIC_GOAL_PATHS),
            1,
        )
        self.assertEqual(
            len(continuation.EXPECTED_V23_SUCCESSOR_DYNAMIC_GOAL_PATHS),
            3,
        )
        self.assertEqual(len(continuation.EXPECTED_SEQ8_GOAL_PACKAGE_PATHS), 24)
        self.assertEqual(len(continuation.EXPECTED_SEQ13_GOAL_PACKAGE_PATHS), 25)
        self.assertEqual(len(continuation.EXPECTED_GOAL_PACKAGE_PATHS), 26)
        self.assertEqual(len(continuation.EXPECTED_GOAL_TOOL_PATHS), 10)
        self.assertEqual(len(continuation.EXPECTED_CURRENT_DYNAMIC_GOAL_PATHS), 3)
        self.assertEqual(
            len(continuation.EXPECTED_CONTROLLED_PATHS),
            continuation.EXPECTED_CONTROLLED_PATH_COUNT,
        )
        self.assertEqual(
            len(continuation.EXPECTED_SEQ8_CONTROLLED_PATHS),
            continuation.EXPECTED_SEQ8_CONTROLLED_PATH_COUNT,
        )
        self.assertEqual(continuation.EXPECTED_SEQ8_CONTROLLED_PATH_COUNT, 444)
        self.assertEqual(continuation.EXPECTED_SEQ13_CONTROLLED_PATH_COUNT, 465)
        self.assertEqual(
            continuation.EXPECTED_FP010_COMPLETION_CONTROLLED_PATH_COUNT,
            484,
        )
        self.assertEqual(continuation.EXPECTED_CONTROLLED_PATH_COUNT, 485)
        self.assertEqual(
            hashlib.sha256(
                (
                    "\n".join(sorted(continuation.EXPECTED_CONTROLLED_PATHS))
                    + "\n"
                ).encode("utf-8")
            ).hexdigest(),
            continuation.EXPECTED_CONTROLLED_PATH_SET_SHA256,
        )
        self.assertEqual(
            continuation.path_set_sha256(
                continuation.EXPECTED_SEQ8_CONTROLLED_PATHS
            ),
            continuation.EXPECTED_SEQ8_CONTROLLED_PATH_SET_SHA256,
        )
        self.assertEqual(
            len(continuation.EXPECTED_FP006_COMPLETION_OUTPUT_PATHS),
            11,
        )
        self.assertEqual(
            len(continuation.EXPECTED_FP006_VERIFICATION_LOG_PATHS),
            3,
        )
        for paths in (
            continuation.EXPECTED_FP006_IMPLEMENTATION_PATHS,
            continuation.EXPECTED_FP006_COMPLETION_OUTPUT_PATHS,
            continuation.EXPECTED_FP006_VERIFICATION_LOG_PATHS,
            continuation.EXPECTED_FP006_TRACE_TOOL_PATHS,
        ):
            self.assertTrue(
                set(paths).issubset(continuation.EXPECTED_CONTROLLED_PATHS)
            )
        self.assertEqual(len(continuation.EXPECTED_FP010_IMPLEMENTATION_PATHS), 6)
        self.assertEqual(
            len(continuation.EXPECTED_FP010_COMPLETION_OUTPUT_PATHS),
            11,
        )
        self.assertEqual(
            len(continuation.EXPECTED_FP010_VERIFICATION_LOG_PATHS),
            3,
        )
        self.assertEqual(len(continuation.EXPECTED_FP010_TRACE_TOOL_PATHS), 2)
        for paths in (
            continuation.EXPECTED_FP010_IMPLEMENTATION_PATHS,
            continuation.EXPECTED_FP010_COMPLETION_OUTPUT_PATHS,
            continuation.EXPECTED_FP010_VERIFICATION_LOG_PATHS,
            continuation.EXPECTED_FP010_TRACE_TOOL_PATHS,
        ):
            self.assertTrue(
                set(paths).issubset(continuation.EXPECTED_CONTROLLED_PATHS)
            )
        self.assertIn(
            continuation.EXPECTED_FP011_GOAL_PATH,
            continuation.EXPECTED_CONTROLLED_PATHS,
        )
        self.assertEqual(
            continuation.expected_controlled_paths_for_goal_execution(
                {"transition_history": [{}] * 13}
            ),
            continuation.EXPECTED_SEQ13_CONTROLLED_PATHS,
        )
        self.assertEqual(
            continuation.expected_controlled_paths_for_goal_execution(
                {"transition_history": [{}] * 14}
            ),
            continuation.EXPECTED_FP010_COMPLETION_CONTROLLED_PATHS,
        )
        self.assertEqual(
            continuation.expected_controlled_paths_for_goal_execution(
                {"transition_history": [{}] * 16}
            ),
            continuation.EXPECTED_CONTROLLED_PATHS,
        )
        phase_a_paths = (
            set(continuation.EXPECTED_EPIC02_PHASE_A_IMPLEMENTATION_PATHS)
            | set(continuation.EXPECTED_EPIC02_PHASE_A_TRACE_PATHS)
        )
        self.assertTrue(phase_a_paths.issubset(continuation.EXPECTED_CONTROLLED_PATHS))
        self.assertTrue(
            set(
                continuation.EXPECTED_FP018_IN_PROGRESS_IMPLEMENTATION_PATHS
            ).issubset(continuation.EXPECTED_CONTROLLED_PATHS)
        )
        self.assertTrue(
            set(continuation.EXPECTED_FP018_TRACE_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )
        self.assertTrue(
            set(
                continuation.EXPECTED_FP004_IN_PROGRESS_IMPLEMENTATION_PATHS
            ).issubset(continuation.EXPECTED_CONTROLLED_PATHS)
        )
        self.assertTrue(
            set(
                continuation.EXPECTED_FP004_INTERRUPTED_TRACE_PATHS
            ).issubset(continuation.EXPECTED_CONTROLLED_PATHS)
        )
        self.assertTrue(
            set(continuation.EXPECTED_FP004_TRACE_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )
        self.assertTrue(
            set(continuation.EXPECTED_FP005_IMPLEMENTATION_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )
        self.assertTrue(
            set(continuation.EXPECTED_FP005_TRACE_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )
        self.assertTrue(
            set(
                continuation.EXPECTED_NPC_PERMISSION_SESSION_IMPLEMENTATION_PATHS
            ).issubset(continuation.EXPECTED_CONTROLLED_PATHS)
        )
        self.assertTrue(
            set(continuation.EXPECTED_NPC_PERMISSION_SESSION_TRACE_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )
        self.assertTrue(
            set(continuation.EXPECTED_FROZEN_V22_GOAL_PACKAGE_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )
        self.assertTrue(
            set(continuation.EXPECTED_GOAL_PACKAGE_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )
        self.assertTrue(
            set(
                continuation.EXPECTED_V23_SUCCESSOR_DYNAMIC_GOAL_PATHS
            ).issubset(continuation.EXPECTED_CONTROLLED_PATHS)
        )
        self.assertTrue(
            set(continuation.EXPECTED_GOAL_TOOL_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )
        self.assertTrue(
            set(continuation.EXPECTED_CURRENT_DYNAMIC_GOAL_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )
        self.assertTrue(
            set(continuation.EXPECTED_SUPERSEDED_GOAL_PACKAGE_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )
        self.assertTrue(
            set(continuation.EXPECTED_SUPERSEDED_V2_GOAL_PACKAGE_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )
        self.assertTrue(
            set(continuation.EXPECTED_FROZEN_V21_GOAL_PACKAGE_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )

    def test_goal_runtime_has_no_fixed_stage_fields(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        goal_execution = checkpoint["goal_execution"]
        replayed_statuses: dict[str, str] = {}
        for event in goal_execution["transition_history"]:
            replayed_statuses.update(event["status_changes"])
        final_runtime = goal_execution["transition_history"][-1]["runtime_after"]

        self.assertEqual(goal_execution["graph_model"], "DEPENDENCY_DAG_READY_FRONTIER")
        self.assertNotIn("phase_order", goal_execution)
        self.assertNotIn("current_phase_goal_id", goal_execution)
        self.assertEqual(goal_execution["status_by_goal"], replayed_statuses)
        for field in continuation.V23_REPLAYED_RUNTIME_FIELDS:
            self.assertEqual(goal_execution[field], final_runtime[field], field)
        self.assertEqual(
            goal_execution["goal_status"],
            replayed_statuses[continuation.EXPECTED_ACTIVE_GOAL_MASTER_ID],
        )

    def test_v23_current_tail_preserves_fp006_history_and_advances_fp010(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = checkpoint["goal_execution"]
        event_types = tuple(
            event["event_type"] for event in state["transition_history"]
        )

        fp006_history = continuation.EXPECTED_V23_LEGAL_TAIL_EVENT_TYPES + (
            "GOAL_STARTED",
        )
        self.assertEqual(event_types[:8], fp006_history)
        fp010_started = (
            continuation.EXPECTED_V23_FP010_TRANSITION_EVENT_TYPES
            + ("GOAL_STARTED",)
        )
        allowed_successor_tails = {
            continuation.EXPECTED_V23_FP010_TRANSITION_EVENT_TYPES,
            fp010_started,
        }
        for suffix_length in range(
            1,
            len(continuation.EXPECTED_V23_FP011_TRANSITION_EVENT_TYPES) + 1,
        ):
            allowed_successor_tails.add(
                fp010_started
                + continuation.EXPECTED_V23_FP011_TRANSITION_EVENT_TYPES[
                    :suffix_length
                ]
            )
        self.assertIn(
            event_types[8:],
            allowed_successor_tails,
        )
        errors: list[str] = []
        continuation.validate_v23_fp006_legal_tail(
            errors,
            goal_execution=state,
            root=ROOT,
        )
        self.assertEqual(errors, [])

    def test_fp010_seq12_candidate_tail_is_valid(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixture_root = Path(temp_dir)
            goal_file = fixture_root / "fp010-goal.md"
            goal_file.write_text("FP010 goal candidate\n", encoding="utf-8")
            checkpoint = self._fp010_candidate_checkpoint(goal_file)
            state = checkpoint["goal_execution"]

            with mock.patch.object(
                continuation,
                "resolve_safe_repo_file",
                side_effect=self._fp010_candidate_resolver(goal_file),
            ):
                replay_errors: list[str] = []
                continuation.validate_v23_transition_replay(
                    replay_errors,
                    state,
                    ROOT,
                    continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256,
                )
                legal_tail_errors: list[str] = []
                continuation.validate_v23_fp006_legal_tail(
                    legal_tail_errors,
                    goal_execution=state,
                    root=ROOT,
                )

        self.assertEqual(replay_errors, [])
        self.assertEqual(legal_tail_errors, [])
        self.assertEqual(len(state["transition_history"]), 12)
        self.assertEqual(
            state["status_by_goal"][continuation.EXPECTED_CURRENT_GOAL_ID],
            "COMPLETE_AT_TARGET",
        )
        self.assertEqual(
            state["status_by_goal"][continuation.EXPECTED_FP010_GOAL_ID],
            "READY",
        )
        self.assertEqual(
            state["goal_status"],
            state["status_by_goal"][continuation.EXPECTED_ACTIVE_GOAL_MASTER_ID],
        )

    def test_fp010_seq13_candidate_tail_is_valid_and_keeps_master_status(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixture_root = Path(temp_dir)
            goal_file = fixture_root / "fp010-goal.md"
            goal_file.write_text("FP010 goal candidate\n", encoding="utf-8")
            start_gate = self._fp010_start_gate_fixture(
                fixture_root,
                goal_file,
            )
            checkpoint = self._fp010_candidate_checkpoint(
                goal_file,
                start_gate=start_gate,
            )
            state = checkpoint["goal_execution"]

            with mock.patch.object(
                continuation,
                "resolve_safe_repo_file",
                side_effect=self._fp010_candidate_resolver(
                    goal_file,
                    fixture_root,
                ),
            ):
                replay_errors: list[str] = []
                continuation.validate_v23_transition_replay(
                    replay_errors,
                    state,
                    ROOT,
                    continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256,
                )
                legal_tail_errors: list[str] = []
                continuation.validate_v23_fp006_legal_tail(
                    legal_tail_errors,
                    goal_execution=state,
                    root=ROOT,
                )

        self.assertEqual(replay_errors, [])
        self.assertEqual(legal_tail_errors, [])
        self.assertEqual(len(state["transition_history"]), 13)
        self.assertEqual(
            state["status_by_goal"][continuation.EXPECTED_FP010_GOAL_ID],
            "IN_PROGRESS",
        )
        self.assertEqual(state["goal_status"], "READY")
        self.assertNotEqual(
            state["goal_status"],
            state["status_by_goal"][continuation.EXPECTED_FP010_GOAL_ID],
        )

    def test_fp011_seq14_to_seq17_candidate_prefixes_are_valid(self) -> None:
        for suffix_length in range(1, 5):
            with self.subTest(suffix_length=suffix_length):
                with tempfile.TemporaryDirectory() as temp_dir:
                    fixture_root = Path(temp_dir)
                    checkpoint = self._fp011_candidate_checkpoint(
                        fixture_root,
                        suffix_length=suffix_length,
                    )
                    state = checkpoint["goal_execution"]
                    with mock.patch.object(
                        continuation,
                        "resolve_safe_repo_file",
                        side_effect=self._fp011_candidate_resolver(
                            fixture_root
                        ),
                    ):
                        replay_errors: list[str] = []
                        continuation.validate_v23_transition_replay(
                            replay_errors,
                            state,
                            ROOT,
                            continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256,
                        )
                        legal_tail_errors: list[str] = []
                        continuation.validate_v23_fp006_legal_tail(
                            legal_tail_errors,
                            goal_execution=state,
                            root=ROOT,
                        )

                self.assertEqual(replay_errors, [])
                self.assertEqual(legal_tail_errors, [])
                self.assertEqual(
                    len(state["transition_history"]),
                    13 + suffix_length,
                )
                self.assertEqual(
                    state["status_by_goal"][
                        continuation.EXPECTED_FP010_GOAL_ID
                    ],
                    (
                        "COMPLETE_AT_TARGET"
                        if suffix_length >= 2
                        else "IN_PROGRESS"
                    ),
                )
                if suffix_length >= 3:
                    self.assertEqual(
                        state["status_by_goal"][
                            continuation.EXPECTED_FP011_GOAL_ID
                        ],
                        "READY" if suffix_length == 4 else "PLANNED",
                    )

    def test_fp011_candidate_rejects_r014_binding_hash_tampering(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixture_root = Path(temp_dir)
            checkpoint = self._fp011_candidate_checkpoint(fixture_root)
            state = checkpoint["goal_execution"]
            state["transition_history"][13][
                "canonical_binding_snapshot_after"
            ]["IMPLEMENTATION_BACKLOG"]["file_sha256"] = "0" * 64
            with mock.patch.object(
                continuation,
                "resolve_safe_repo_file",
                side_effect=self._fp011_candidate_resolver(fixture_root),
            ):
                errors: list[str] = []
                continuation.validate_v23_fp006_legal_tail(
                    errors,
                    goal_execution=state,
                    root=ROOT,
                )

        self.assertTrue(
            any("FP010 update Backlog binding" in error for error in errors)
        )

    def test_fp011_candidate_rejects_out_of_order_completion(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixture_root = Path(temp_dir)
            checkpoint = self._fp011_candidate_checkpoint(fixture_root)
            state = checkpoint["goal_execution"]
            state["transition_history"][13]["event_type"] = "GOAL_COMPLETED"

            errors: list[str] = []
            continuation.validate_v23_fp006_legal_tail(
                errors,
                goal_execution=state,
                root=ROOT,
            )

        self.assertTrue(
            any("ordered FP006→FP010→FP011" in error for error in errors)
        )

    def test_fp011_candidate_rejects_broken_event_hash_chain(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixture_root = Path(temp_dir)
            checkpoint = self._fp011_candidate_checkpoint(fixture_root)
            state = checkpoint["goal_execution"]
            state["transition_history"][14]["previous_event_sha256"] = "0" * 64
            with mock.patch.object(
                continuation,
                "resolve_safe_repo_file",
                side_effect=self._fp011_candidate_resolver(fixture_root),
            ):
                errors: list[str] = []
                continuation.validate_v23_transition_replay(
                    errors,
                    state,
                    ROOT,
                    continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256,
                )

        self.assertTrue(
            any("event 15 previous event SHA-256" in error for error in errors)
        )

    def test_fp011_candidate_rejects_wrong_ready_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixture_root = Path(temp_dir)
            checkpoint = self._fp011_candidate_checkpoint(fixture_root)
            state = checkpoint["goal_execution"]
            state["transition_history"][16]["readiness_basis"][
                "dependency_completion_events"
            ][0]["event_sha256"] = "0" * 64
            with mock.patch.object(
                continuation,
                "resolve_safe_repo_file",
                side_effect=self._fp011_candidate_resolver(fixture_root),
            ):
                errors: list[str] = []
                continuation.validate_v23_fp006_legal_tail(
                    errors,
                    goal_execution=state,
                    root=ROOT,
                )

        self.assertTrue(
            any("FP011 readiness lineage" in error for error in errors)
        )

    def test_fp010_candidate_rejects_non_r013_canonical_binding(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            goal_file = Path(temp_dir) / "fp010-goal.md"
            goal_file.write_text("FP010 goal candidate\n", encoding="utf-8")
            checkpoint = self._fp010_candidate_checkpoint(goal_file)
            state = checkpoint["goal_execution"]
            state["transition_history"][8]["canonical_binding_snapshot_after"][
                "IMPLEMENTATION_GAP"
            ]["path"] = continuation.EXPECTED_R012_GAP_PATH

            with mock.patch.object(
                continuation,
                "resolve_safe_repo_file",
                side_effect=self._fp010_candidate_resolver(goal_file),
            ):
                errors: list[str] = []
                continuation.validate_v23_fp006_legal_tail(
                    errors,
                    goal_execution=state,
                    root=ROOT,
                )

        self.assertTrue(
            any("FP006 update Gap binding" in error for error in errors)
        )

    def test_fp010_ready_tail_rejects_premature_gate_event_binding(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = copy.deepcopy(checkpoint["goal_execution"])
        state["transition_history"] = state["transition_history"][:12]
        ready = state["transition_history"][-1]
        ready["implementation_start_gate_binding"] = {}

        errors: list[str] = []
        continuation.validate_v23_fp006_legal_tail(
            errors,
            goal_execution=state,
            root=ROOT,
        )

        self.assertIn(
            "active Goal FP010 READY event must not contain "
            "implementation_start_gate_binding",
            errors,
        )

    def test_fp010_started_tail_requires_gate_receipt_and_snapshot(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = copy.deepcopy(checkpoint["goal_execution"])
        state["transition_history"] = state["transition_history"][:12]
        ready = state["transition_history"][-1]
        state["status_by_goal"][continuation.EXPECTED_FP010_GOAL_ID] = (
            "IN_PROGRESS"
        )
        started = {
            "event_type": "GOAL_STARTED",
            "event_id": "WS-GOAL-GRAPH-V2-3-GOAL-STARTED-FP010-TEST-001",
            "subject_goal_id": continuation.EXPECTED_FP010_GOAL_ID,
            "status_changes": {
                continuation.EXPECTED_FP010_GOAL_ID: "IN_PROGRESS"
            },
            "runtime_after": copy.deepcopy(ready["runtime_after"]),
        }
        state["transition_history"].append(started)

        errors: list[str] = []
        continuation.validate_v23_fp006_legal_tail(
            errors,
            goal_execution=state,
            root=ROOT,
        )

        self.assertIn(
            "active Goal FP010 start gate binding must be an object",
            errors,
        )

    def test_fp006_start_gate_binds_receipt_and_repository_snapshot(self) -> None:
        event_id = "WS-GOAL-GRAPH-V2-3-GOAL-STARTED-FP006-TEST-001"
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            goal_path = root / continuation.EXPECTED_CURRENT_GOAL_PATH
            goal_path.parent.mkdir(parents=True)
            goal_path.write_text("FP006 goal fixture\n", encoding="utf-8")
            gate_dir = (
                root
                / "docs/control/execution/goal-gates"
                / event_id
            )
            gate_dir.mkdir(parents=True)
            output_path = gate_dir / "19-REPOSITORY_STATE.log"
            controlled_content_sha256 = "1" * 64
            repository_payload = {
                "evidence_type": (
                    continuation.GATE_REPOSITORY_STATE_EVIDENCE_TYPE
                ),
                "gate_event_id": event_id,
                "checkpoint_controlled_working_snapshot": {
                    "managed_changed_path_count": (
                        continuation.EXPECTED_SEQ8_CONTROLLED_PATH_COUNT
                    ),
                    "path_set_sha256": (
                        continuation.EXPECTED_SEQ8_CONTROLLED_PATH_SET_SHA256
                    ),
                    "content_set_sha256": controlled_content_sha256,
                },
            }
            output_path.write_text(
                json.dumps(
                    repository_payload,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n",
                encoding="utf-8",
            )
            output_sha256 = continuation.sha256_file(output_path)
            repository_snapshot = {
                "gate_event_id": event_id,
                "checkpoint_managed_path_count": (
                    continuation.EXPECTED_SEQ8_CONTROLLED_PATH_COUNT
                ),
                "checkpoint_path_set_sha256": (
                    continuation.EXPECTED_SEQ8_CONTROLLED_PATH_SET_SHA256
                ),
                "checkpoint_content_set_sha256": controlled_content_sha256,
                "gate_repository_state_output_sha256": output_sha256,
            }
            receipt_path = gate_dir / "implementation-start-gate-receipt.json"
            relative_output = output_path.relative_to(root).as_posix()
            receipt = {
                "schema_version": "1.0",
                "document_id": (
                    "WS-GOAL-GRAPH-V2-3-IMPLEMENTATION-START-GATE-"
                    "FP006-TEST-001"
                ),
                "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
                "gate_purpose": "INITIAL_START",
                "status": "PASS",
                "package_id": continuation.EXPECTED_ACTIVE_GOAL_PACKAGE_ID,
                "target_transition_event_id": event_id,
                "target_goal_id": continuation.EXPECTED_CURRENT_GOAL_ID,
                "target_goal_content_sha256": continuation.sha256_file(
                    goal_path
                ),
                "static_plan_manifest_sha256": (
                    continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256
                ),
                "source_activation_event_sha256": (
                    continuation.EXPECTED_V23_ACTIVATION_EVENT_SHA256
                ),
                "check_command_contract_version": (
                    continuation.EXPECTED_V23_START_GATE_CONTRACT_VERSION
                ),
                "check_command_contract_sha256": (
                    continuation.EXPECTED_V23_START_GATE_CONTRACT_SHA256
                ),
                "repository_snapshot": repository_snapshot,
                "check_runs": [{} for _ in range(18)]
                + [
                    {
                        "check_id": "REPOSITORY_STATE",
                        "output_path": relative_output,
                        "output_sha256": output_sha256,
                    }
                ],
            }
            receipt_path.write_text(
                json.dumps(receipt, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            event = {
                "event_id": event_id,
                "implementation_start_gate_binding": {
                    "document_id": receipt["document_id"],
                    "path": receipt_path.relative_to(root).as_posix(),
                    "file_sha256": continuation.sha256_file(receipt_path),
                },
                "repository_snapshot_before": repository_snapshot,
            }

            errors: list[str] = []
            continuation.validate_fp006_start_gate_binding(
                errors,
                event=event,
                root=root,
            )

        self.assertEqual(errors, [])

    def test_package_prepared_anchor_keeps_fp005_r011_lineage(self) -> None:
        checkpoint = self._prepared_checkpoint_fixture()
        state = checkpoint["goal_execution"]
        prepared = state["transition_history"][0]
        backlog_binding = prepared["canonical_binding_snapshot_after"][
            "IMPLEMENTATION_BACKLOG"
        ]
        backlog = continuation.load_json(ROOT / backlog_binding["path"])
        next_action = backlog["next_single_action"]

        self.assertEqual(
            prepared["focus_goal_id"],
            continuation.EXPECTED_ACTIVE_GOAL_FOCUS_ID,
        )
        self.assertEqual(
            prepared["status_changes"]["WS-GOAL-EPIC-02-FP-004-R001"],
            "COMPLETE_AT_TARGET",
        )
        self.assertEqual(
            prepared["status_changes"]["WS-GOAL-EPIC-02-FP-005-R001"],
            "READY",
        )
        self.assertEqual(
            backlog_binding["path"],
            "docs/control/audits/"
            "walksafe-implementation-remediation-backlog-20260724-r011.json",
        )
        self.assertEqual(
            (
                next_action["work_item_id"],
                next_action["source_policy_id"],
                next_action["gap_id"],
            ),
            (
                "EPIC-02-FP005-OFFICIAL-ENVIRONMENT-CROSSWALK",
                "FP-005",
                "GAP-014",
            ),
        )

    def test_fp006_history_is_the_r012_gap015_successor_of_completed_fp005(
        self,
    ) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = checkpoint["goal_execution"]
        events = {
            event["sequence"]: event for event in state["transition_history"]
        }
        backlog_binding = events[4]["canonical_binding_snapshot_after"][
            "IMPLEMENTATION_BACKLOG"
        ]
        backlog = continuation.load_json(ROOT / backlog_binding["path"])
        next_action = backlog["next_single_action"]

        self.assertEqual(
            backlog_binding["path"],
            continuation.EXPECTED_R012_BACKLOG_PATH,
        )
        self.assertEqual(
            (
                next_action["work_item_id"],
                next_action["source_policy_id"],
                next_action["gap_id"],
            ),
            (
                continuation.EXPECTED_CURRENT_GOAL_WORK_ITEM_ID,
                "FP-006",
                "GAP-015",
            ),
        )
        self.assertEqual(
            events[5]["status_changes"],
            {continuation.EXPECTED_FP005_GOAL_ID: "COMPLETE_AT_TARGET"},
        )
        self.assertEqual(
            events[7]["status_changes"],
            {continuation.EXPECTED_CURRENT_GOAL_ID: "READY"},
        )
        self.assertEqual(
            events[8]["status_changes"],
            {continuation.EXPECTED_CURRENT_GOAL_ID: "IN_PROGRESS"},
        )

    def test_fp010_focus_is_the_r013_gap019_successor_of_completed_fp006(
        self,
    ) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = checkpoint["goal_execution"]
        backlog_binding = binding_by_role(checkpoint, "IMPLEMENTATION_BACKLOG")
        backlog = continuation.load_json(ROOT / backlog_binding["path"])
        next_action = backlog["next_single_action"]
        fp011_successor_active = len(state["transition_history"]) >= 14

        self.assertEqual(
            backlog_binding["path"],
            (
                continuation.EXPECTED_R014_BACKLOG_PATH
                if fp011_successor_active
                else continuation.EXPECTED_R013_BACKLOG_PATH
            ),
        )
        self.assertEqual(
            (
                next_action["work_item_id"],
                next_action["source_policy_id"],
                next_action["gap_id"],
            ),
            (
                (
                    continuation.EXPECTED_FP011_GOAL_WORK_ITEM_ID
                    if fp011_successor_active
                    else continuation.EXPECTED_FP010_GOAL_WORK_ITEM_ID
                ),
                "FP-011" if fp011_successor_active else "FP-010",
                "GAP-020" if fp011_successor_active else "GAP-019",
            ),
        )
        self.assertEqual(
            state["status_by_goal"][continuation.EXPECTED_CURRENT_GOAL_ID],
            "COMPLETE_AT_TARGET",
        )
        if len(state["transition_history"]) >= 15:
            self.assertEqual(
                state["status_by_goal"][continuation.EXPECTED_FP010_GOAL_ID],
                "COMPLETE_AT_TARGET",
            )
        else:
            self.assertIn(
                state["status_by_goal"][continuation.EXPECTED_FP010_GOAL_ID],
                {"READY", "IN_PROGRESS"},
            )
        if len(state["transition_history"]) >= 17:
            self.assertEqual(
                state["focus_goal_id"],
                continuation.EXPECTED_FP011_GOAL_ID,
            )
        elif len(state["transition_history"]) <= 14:
            self.assertEqual(
                state["focus_goal_id"],
                continuation.EXPECTED_FP010_GOAL_ID,
            )
        self.assertEqual(
            checkpoint["current_work"]["work_item_id"],
            next_action["work_item_id"],
        )

    def test_restart_npm_commands_are_bound_to_attested_locked_node(self) -> None:
        for relative_path in (
            "docs/control/README.md",
            "docs/control/walksafe-project-resumption-runbook.md",
        ):
            text = (ROOT / relative_path).read_text(encoding="utf-8")
            self.assertIn("scripts/check_walksafe_node_toolchain_20260715.py", text)
            self.assertIn('"${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway', text)
            self.assertIn('"${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web', text)
            self.assertNotIn("\nnpm --prefix ", text)

        for verification_id in ("ANDROID_GATEWAY_PHASE_E", "LEGACY_WEB_PHASE_E"):
            command = continuation.EXPECTED_VERIFICATION_COMMANDS[verification_id]["command"]
            self.assertIn("check_walksafe_node_toolchain_20260715.py", command)
            self.assertIn('"${WALKSAFE_NODE_BIN_DIR}/npm" --prefix', command)
            self.assertNotIn("&& npm --prefix", command)

    def test_phase_c_verification_commands_are_exact(self) -> None:
        trace = continuation.EXPECTED_VERIFICATION_COMMANDS["PHASE_C_TRACE"]
        self.assertIn("build_walksafe_epic01_phase_c_trace_20260722.py --check", trace["command"])
        self.assertIn("test_walksafe_epic01_phase_c_trace_20260722.py", trace["command"])
        self.assertEqual(trace["status"], "EXPECTED_STALE")
        self.assertIn("Phase F", trace["result"])

    def test_phase_d_verification_commands_are_exact(self) -> None:
        boundary = continuation.EXPECTED_VERIFICATION_COMMANDS["LEGACY_WEB_PHASE_D"]
        trace = continuation.EXPECTED_VERIFICATION_COMMANDS["PHASE_D_TRACE"]
        self.assertIn("check_walksafe_legacy_web_boundary_20260722.py", boundary["command"])
        self.assertEqual(boundary["status"], "EXPECTED_STALE")
        self.assertIn("Phase E", boundary["result"])
        self.assertIn(
            "build_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py --check",
            trace["command"],
        )
        self.assertEqual(trace["status"], "EXPECTED_STALE")
        self.assertIn("불변 SHA-256", trace["result"])
        self.assertIn(
            "test_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py",
            trace["command"],
        )

    def test_phase_e_verification_commands_are_exact_and_fail_closed(self) -> None:
        gateway = continuation.EXPECTED_VERIFICATION_COMMANDS["ANDROID_GATEWAY_PHASE_E"]
        web = continuation.EXPECTED_VERIFICATION_COMMANDS["LEGACY_WEB_PHASE_E"]
        trace = continuation.EXPECTED_VERIFICATION_COMMANDS["PHASE_E_TRACE"]
        self.assertIn("apps/android-gateway run typecheck", gateway["command"])
        self.assertIn("check_walksafe_android_gateway_boundary_20260723.py", gateway["command"])
        self.assertIn("apps/web run build", web["command"])
        self.assertIn("build_walksafe_epic01_phase_e_android_gateway_trace_20260723.py", trace["command"])
        self.assertEqual(trace["status"], "EXPECTED_STALE")
        self.assertIn("Phase F", trace["result"])

    def test_phase_f_verification_commands_are_exact_and_fail_closed(self) -> None:
        android = continuation.EXPECTED_VERIFICATION_COMMANDS["USER_ANDROID_PHASE_F"]
        trace = continuation.EXPECTED_VERIFICATION_COMMANDS["PHASE_F_TRACE"]
        self.assertIn(":app:testDebugUnitTest :app:assembleDebug :app:lintDebug", android["command"])
        self.assertIn("379/379", android["result"])
        self.assertIn("build_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py", trace["command"])
        self.assertEqual(trace["status"], "EXPECTED_STALE")
        self.assertIn("Phase G", trace["result"])
        self.assertIn(
            "--deselect=tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py::"
            "WalkSafeEpic01PhaseFTraceTest::test_generated_files_are_current_and_deterministic",
            continuation.EXPECTED_VERIFICATION_COMMANDS["CONTINUATION_INTEGRITY"]["command"],
        )

    def test_phase_g_verification_commands_are_exact_and_fail_closed(self) -> None:
        android = continuation.EXPECTED_VERIFICATION_COMMANDS["USER_ANDROID_PHASE_G"]
        trace = continuation.EXPECTED_VERIFICATION_COMMANDS["PHASE_G_TRACE"]
        integrity = continuation.EXPECTED_VERIFICATION_COMMANDS["CONTINUATION_INTEGRITY"]
        self.assertIn(":app:testDebugUnitTest :app:assembleDebug :app:lintDebug", android["command"])
        self.assertIn(
            "build_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py",
            trace["command"],
        )
        self.assertEqual(trace["status"], "EXPECTED_STALE")
        self.assertIn("EPIC-02 Phase A", trace["result"])
        self.assertIn("불변 SHA-256", trace["result"])
        self.assertIn(
            "tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py",
            integrity["command"],
        )
        self.assertIn(
            "--deselect=tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py::"
            "WalkSafeEpic01PhaseGTraceTest::test_generated_files_are_current_and_deterministic",
            integrity["command"],
        )

    def test_epic02_phase_a_verification_is_historical_and_live_currentness_is_deselected(self) -> None:
        android = continuation.EXPECTED_VERIFICATION_COMMANDS[
            "USER_ANDROID_EPIC02_PHASE_A"
        ]
        trace = continuation.EXPECTED_VERIFICATION_COMMANDS["EPIC02_PHASE_A_TRACE"]
        integrity = continuation.EXPECTED_VERIFICATION_COMMANDS["CONTINUATION_INTEGRITY"]
        self.assertIn(":app:testDebugUnitTest :app:assembleDebug :app:lintDebug", android["command"])
        self.assertIn("407/407", android["result"])
        self.assertIn("lint 경고 31개·오류 0", android["result"])
        self.assertEqual(trace["status"], "PASS")
        self.assertIn(
            "build_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py",
            trace["command"],
        )
        self.assertIn("GAP-026=PARTIAL", trace["result_required_fragments"])
        self.assertIn("EPIC-02=IN_PROGRESS", trace["result_required_fragments"])
        self.assertIn(
            "tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py",
            integrity["command"],
        )
        self.assertIn(
            "--deselect=tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py::"
            "WalkSafeEpic02PhaseATraceTest::test_generated_files_are_current_and_deterministic",
            integrity["command"],
        )
        self.assertTrue(
            set(continuation.EXPECTED_EPIC02_PHASE_A_TRACE_PATHS).issubset(
                continuation.EXPECTED_IMMUTABLE_PREDECESSOR_SHA256
            )
        )

    def test_fp018_trace_verification_is_current_and_bound_to_trace_paths(self) -> None:
        trace = continuation.EXPECTED_VERIFICATION_COMMANDS["FP018_TRACE"]

        self.assertEqual(trace["status"], "PASS")
        self.assertIn(
            "build_walksafe_fp018_walk_state_recovery_trace_20260724.py",
            trace["command"],
        )
        self.assertIn(
            "tests/test_walksafe_fp018_walk_state_recovery_trace_20260724.py",
            trace["command"],
        )
        self.assertIn("GAP-027=PARTIAL", trace["result_required_fragments"])
        self.assertIn(
            "NPC-PERMISSION-SESSION-LIFECYCLE",
            trace["result_required_fragments"],
        )
        self.assertTrue(
            set(continuation.EXPECTED_FP018_TRACE_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )

    def test_fp004_trace_is_historical_after_fp005_successor(self) -> None:
        trace = continuation.EXPECTED_VERIFICATION_COMMANDS["FP004_TRACE"]
        integrity = continuation.EXPECTED_VERIFICATION_COMMANDS[
            "CONTINUATION_INTEGRITY"
        ]

        self.assertEqual(trace["status"], "EXPECTED_STALE")
        self.assertIn(
            "build_walksafe_fp004_priority_user_trace_20260724.py --check",
            trace["command"],
        )
        self.assertIn(
            "tests/test_walksafe_fp004_priority_user_trace_20260724.py",
            trace["command"],
        )
        self.assertIn("GAP-013=PARTIAL", trace["result_required_fragments"])
        self.assertIn("FP-005", trace["result_required_fragments"])
        self.assertNotIn(
            "tests/test_walksafe_fp004_priority_user_trace_20260724.py",
            integrity["command"],
        )
        self.assertIn(
            "tests/test_walksafe_epic02_trace_v2_2_history.py",
            integrity["command"],
        )
        self.assertTrue(
            set(continuation.EXPECTED_FP004_TRACE_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )

    def test_fp005_trace_verification_is_current_and_bound_to_trace_paths(self) -> None:
        trace = continuation.EXPECTED_VERIFICATION_COMMANDS["FP005_TRACE"]

        self.assertEqual(trace["status"], "PASS")
        self.assertIn(
            "build_walksafe_fp005_official_environment_trace_20260724.py --check",
            trace["command"],
        )
        self.assertIn(
            "tests/test_walksafe_fp005_official_environment_trace_20260724.py",
            trace["command"],
        )
        self.assertIn("10/10", trace["result_required_fragments"])
        self.assertIn("GAP-014=PARTIAL", trace["result_required_fragments"])
        self.assertIn("FP-006/GAP-015", trace["result_required_fragments"])
        self.assertTrue(
            set(continuation.EXPECTED_FP005_TRACE_PATHS).issubset(
                continuation.EXPECTED_CONTROLLED_PATHS
            )
        )

    def test_continuation_integrity_uses_v22_epic02_history_adapter(self) -> None:
        verification = continuation.EXPECTED_VERIFICATION_COMMANDS[
            "CONTINUATION_INTEGRITY"
        ]
        command = verification["command"]
        adapter = "tests/test_walksafe_epic02_trace_v2_2_history.py"

        self.assertIn(adapter, command)
        self.assertNotIn(
            "tests/test_walksafe_npc_permission_session_trace_20260724.py",
            command,
        )
        self.assertNotIn(
            "tests/test_walksafe_fp004_priority_user_trace_20260724.py",
            command,
        )
        ordered_paths = (
            "tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py",
            "tests/test_walksafe_android_gateway_boundary_20260723.py",
            adapter,
            "tests/test_walksafe_project_continuation_v2_3.py",
            "tests/test_walksafe_artifact_baseline_materialization_20260722.py",
        )
        self.assertEqual(
            sorted(command.index(path) for path in ordered_paths),
            [command.index(path) for path in ordered_paths],
        )
        self.assertIn(
            "v2.2 EPIC-02 FP018/NPC/FP004 trace history 보존",
            verification["result_required_fragments"],
        )
        self.assertIn(adapter, continuation.EXPECTED_GOAL_TOOL_PATHS)
        self.assertIn(adapter, continuation.EXPECTED_CONTROLLED_PATHS)

    def test_v23_goal_control_verification_uses_successor_and_history_tests(self) -> None:
        verification = continuation.EXPECTED_VERIFICATION_COMMANDS[
            "GOAL_PACKAGE"
        ]
        command = verification["command"]

        self.assertEqual(verification["status"], "PASS")
        self.assertIn("scripts/check_walksafe_goal_graph_v2_3.py", command)
        self.assertIn("tests/test_walksafe_goal_graph_v2_3.py", command)
        self.assertIn(
            "tests/test_walksafe_project_continuation_v2_3.py",
            command,
        )
        self.assertIn("tests/test_walksafe_goal_graph_v2_2_history.py", command)
        self.assertNotIn("tests/test_walksafe_goal_graph.py", command)
        self.assertNotIn("tests/test_walksafe_project_continuation.py", command)
        self.assertNotIn("--deselect", command)

    def test_phase_c_record_boundary_tampering_is_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "EPIC01_PHASE_C_RECORD",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                document["production_device_profile_registry"]["approved_profile_count"] = 1
                document["internal_verification"]["actual_device_execution"] = "PASS"
                document["release_boundary"]["formal_tests_not_run"] = 0
                document["release_boundary"]["release_status"] = "ELIGIBLE"
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("Phase C approved production profile count" in error for error in errors))
        self.assertTrue(any("Phase C actual device status" in error for error in errors))
        self.assertTrue(any("Phase C formal tests NOT_RUN" in error for error in errors))
        self.assertTrue(any("Phase C release status" in error for error in errors))

    def test_gap018_promotion_beyond_partial_is_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "IMPLEMENTATION_GAP",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                gap018 = next(
                    item for item in document["assessments"] if item["gap_id"] == "GAP-018"
                )
                gap018["status"] = "IMPLEMENTED"
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("GAP-018 status" in error for error in errors))

    def test_gap016_promotion_beyond_partial_is_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "IMPLEMENTATION_GAP",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                gap016 = next(
                    item for item in document["assessments"] if item["gap_id"] == "GAP-016"
                )
                gap016["status"] = "IMPLEMENTED"
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("GAP-016 status" in error for error in errors))

    def test_phase_d_record_completion_overclaim_is_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "EPIC01_PHASE_D_RECORD",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                document["authority_boundary"]["claims_bff_extraction_complete"] = True
                document["authority_boundary"][
                    "claims_historical_external_urls_decommissioned"
                ] = True
                document["internal_verification"]["actual_device_execution"] = "PASS"
                document["release_boundary"]["formal_tests_not_run"] = 0
                document["release_boundary"]["release_status"] = "ELIGIBLE"
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("Phase D BFF completion claim" in error for error in errors))
        self.assertTrue(any("Phase D external URL claim" in error for error in errors))
        self.assertTrue(any("Phase D actual device status" in error for error in errors))
        self.assertTrue(any("Phase D formal tests NOT_RUN" in error for error in errors))
        self.assertTrue(any("Phase D release status" in error for error in errors))

    def test_phase_d_overlay_promotion_is_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "EPIC01_PHASE_D_ACTIVE_OVERLAY",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                document["authority_boundary"]["changes_lifecycle_or_approval_state"] = True
                document["open_evidence_boundaries"][
                    "historical_external_url_decommission_status"
                ] = "PASS"
                document["formal_boundary"]["remaining_gates_waived"] = True
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("Phase D overlay lifecycle promotion" in error for error in errors))
        self.assertTrue(any("Phase D overlay external URL status" in error for error in errors))
        self.assertTrue(any("Phase D overlay gates waived" in error for error in errors))

    def test_phase_e_record_deployment_device_and_release_overclaim_is_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "EPIC01_PHASE_E_RECORD",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                document["authority_boundary"]["claims_gateway_deployed"] = True
                document["authority_boundary"]["claims_actual_device_connectivity_pass"] = True
                document["internal_verification"]["actual_device_execution"] = "PASS"
                document["release_boundary"]["formal_tests_not_run"] = 0
                document["release_boundary"]["release_status"] = "ELIGIBLE"
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("Phase E deployment claim" in error for error in errors))
        self.assertTrue(any("Phase E actual-device claim" in error for error in errors))
        self.assertTrue(any("Phase E actual-device execution" in error for error in errors))
        self.assertTrue(any("Phase E formal tests NOT_RUN" in error for error in errors))
        self.assertTrue(any("Phase E release status" in error for error in errors))

    def test_phase_e_record_implementation_hash_tampering_is_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "EPIC01_PHASE_E_RECORD",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                document["implementation_snapshot"]["files"][0]["sha256"] = "0" * 64
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(
            any("Phase E implementation snapshot seal is invalid" in error for error in errors)
        )

    def test_phase_e_overlay_promotion_is_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "EPIC01_PHASE_E_ACTIVE_OVERLAY",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                document["authority_boundary"]["changes_lifecycle_or_approval_state"] = True
                document["authority_boundary"]["deployment_completion_claimed"] = True
                document["open_evidence_boundaries"]["android_gateway_deployment_status"] = "PASS"
                document["formal_boundary"]["remaining_gates_waived"] = True
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("Phase E overlay lifecycle promotion" in error for error in errors))
        self.assertTrue(any("Phase E overlay deployment claim" in error for error in errors))
        self.assertTrue(any("Phase E overlay Gateway deployment" in error for error in errors))
        self.assertTrue(any("Phase E overlay gates waived" in error for error in errors))

    def test_phase_f_record_scope_and_release_overclaim_is_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "EPIC01_PHASE_F_RECORD",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                document["purpose_surface_contract"]["report_scope"] = "ALL_HAZARDS"
                document["authority_boundary"]["claims_rel17_approved"] = True
                document["release_boundary"]["formal_tests_not_run"] = 0
                document["release_boundary"]["release_status"] = "ELIGIBLE"
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("Phase F report scope" in error for error in errors))
        self.assertTrue(any("Phase F REL-17 approval claim" in error for error in errors))
        self.assertTrue(any("Phase F formal tests NOT_RUN" in error for error in errors))
        self.assertTrue(any("Phase F release status" in error for error in errors))

    def test_phase_f_overlay_promotion_is_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "EPIC01_PHASE_F_ACTIVE_OVERLAY",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                document["authority_boundary"]["changes_lifecycle_or_approval_state"] = True
                document["events"][0]["lifecycle_status_after"] = "APPROVED_BASELINED"
                document["formal_boundary"]["remaining_gates_waived"] = True
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("Phase F overlay lifecycle promotion" in error for error in errors))
        self.assertTrue(any("Phase F overlay promotes an Active artifact" in error for error in errors))
        self.assertTrue(any("Phase F overlay gates waived" in error for error in errors))

    def test_phase_g_record_route_coupling_and_release_overclaim_are_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "EPIC01_PHASE_G_RECORD",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                contract = document["no_destination_hazard_contract"]
                contract["camera_non_metric_constraints"]["required_gates"].append(
                    "tmap_route_active"
                )
                contract["behavior_matrix"][0]["tactile_local_guidance"] = "ALLOWED"
                document["authority_boundary"]["claims_epic_complete"] = True
                document["release_boundary"]["formal_tests_not_run"] = 0
                document["release_boundary"]["release_status"] = "ELIGIBLE"
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("Phase G no-destination hazard contract seal" in error for error in errors))
        self.assertTrue(any("Phase G CameraX required gates" in error for error in errors))
        self.assertTrue(any("Phase G no-destination ARCore tactile guidance" in error for error in errors))
        self.assertTrue(any("Phase G complete claim" in error for error in errors))
        self.assertTrue(any("Phase G formal tests NOT_RUN" in error for error in errors))
        self.assertTrue(any("Phase G release status" in error for error in errors))

    def test_phase_g_overlay_promotion_is_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "EPIC01_PHASE_G_ACTIVE_OVERLAY",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                document["authority_boundary"]["changes_lifecycle_or_approval_state"] = True
                document["authority_boundary"]["epic_complete_claimed"] = True
                document["events"][0]["lifecycle_status_after"] = "APPROVED_BASELINED"
                document["formal_boundary"]["remaining_gates_waived"] = True
                document["formal_boundary"]["release_status"] = "ELIGIBLE"
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("Phase G overlay lifecycle promotion" in error for error in errors))
        self.assertTrue(any("Phase G overlay complete claim" in error for error in errors))
        self.assertTrue(any("Phase G overlay promotes an Active artifact" in error for error in errors))
        self.assertTrue(any("Phase G overlay gates waived" in error for error in errors))
        self.assertTrue(any("Phase G overlay formal release status" in error for error in errors))

    def test_fp018_gap_and_epic_transition_overclaim_are_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        gap_target = ROOT / binding_by_role(checkpoint, "IMPLEMENTATION_GAP")["path"]
        backlog_target = ROOT / binding_by_role(checkpoint, "IMPLEMENTATION_BACKLOG")["path"]

        def mutate(path: Path, document: dict) -> dict:
            resolved = Path(path).resolve()
            if resolved == gap_target.resolve():
                document = copy.deepcopy(document)
                gap027 = next(
                    item for item in document["assessments"] if item["gap_id"] == "GAP-027"
                )
                gap027["status"] = "IMPLEMENTED"
            elif resolved == backlog_target.resolve():
                document = copy.deepcopy(document)
                epic02 = next(item for item in document["epics"] if item["epic_id"] == "EPIC-02")
                epic02["current_status"] = "IMPLEMENTATION_READY"
                document["next_single_action"]["source_policy_id"] = "FP-019"
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("GAP-027 status" in error for error in errors))
        self.assertTrue(any("current EPIC status" in error for error in errors))
        self.assertTrue(any("backlog next source policy" in error for error in errors))

    def test_phase_b_record_completion_tampering_is_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "EPIC01_PHASE_B_RECORD",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                document["metadata"]["status"] = "COMPLETE"
                document["authority_boundary"]["claims_release_eligible"] = True
                document["release_boundary"]["release_status"] = "ELIGIBLE"
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("Phase B record status" in error for error in errors))
        self.assertTrue(any("Phase B release claim" in error for error in errors))
        self.assertTrue(any("Phase B release status" in error for error in errors))

    def test_unexecuted_recovery_drill_tampering_is_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "SINGLE_ADMIN_RECOVERY_DRILL_PROTOCOL",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                document["metadata"]["status"] = "PASSED"
                document["execution"]["status"] = "PASS"
                document["execution"]["result"] = "PASS"
                document["authority_boundary"]["gate_closed"] = True
                document["release_boundary"]["release_status"] = "ELIGIBLE"
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("recovery drill protocol status" in error for error in errors))
        self.assertTrue(any("recovery drill execution status" in error for error in errors))
        self.assertTrue(any("recovery drill gate closed" in error for error in errors))
        self.assertTrue(any("recovery drill release status" in error for error in errors))

    def test_active_overlay_promotion_tampering_is_rejected(self) -> None:
        target = ROOT / binding_by_role(
            continuation.load_json(CHECKPOINT),
            "EPIC01_PHASE_B_ACTIVE_OVERLAY",
        )["path"]

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                document["authority_boundary"]["changes_lifecycle_or_approval_state"] = True
                document["formal_boundary"]["formal_tests_not_run"] = 0
                document["formal_boundary"]["release_status"] = "ELIGIBLE"
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("overlay lifecycle promotion" in error for error in errors))
        self.assertTrue(any("overlay formal tests NOT_RUN" in error for error in errors))
        self.assertTrue(any("overlay release status" in error for error in errors))

    def test_product_contract_auth_and_fail_closed_tampering_is_rejected(self) -> None:
        target = ROOT / "configs/walksafe_product_boundary_20260722.json"

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                admin = document["products"]["admin_android_app"]
                admin["authentication"]["mode"] = "SHARED_PASSWORD"
                admin["fail_closed"]["operational_workflows_enabled"] = True
                admin["recovery"]["formal_phone_loss_drill_status"] = "PASS"
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("admin authentication" in error for error in errors))
        self.assertTrue(any("admin operational workflows" in error for error in errors))
        self.assertTrue(any("admin recovery drill" in error for error in errors))

    def test_product_contract_phase_e_boundary_overclaim_is_rejected(self) -> None:
        target = ROOT / "configs/walksafe_product_boundary_20260722.json"

        def mutate(path: Path, document: dict) -> dict:
            if Path(path).resolve() == target.resolve():
                document = copy.deepcopy(document)
                legacy = document["products"]["legacy_web"]
                legacy["technical_closure_status"] = "FULLY_REMOVED"
                legacy["remaining_executable_historical_inputs"] = ["legacy-launcher"]
                legacy["transitional_android_api_routes"]["extraction_status"] = "COMPLETED"
                legacy["transitional_android_api_routes"]["runtime_allowlist"] = [
                    "/api/field-session"
                ]
                gateway = document["products"]["android_api_gateway"]
                gateway["official_local_bind"] = "0.0.0.0:8081"
                gateway["deployment_status"] = "PASS"
                document["release_control"]["release_eligibility"] = "ELIGIBLE"
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("Legacy Web technical closure" in error for error in errors))
        self.assertTrue(any("Legacy Web remaining executable inputs" in error for error in errors))
        self.assertTrue(any("gateway extraction" in error for error in errors))
        self.assertTrue(any("Legacy Web runtime allowlist" in error for error in errors))
        self.assertTrue(any("Gateway bind" in error for error in errors))
        self.assertTrue(any("Gateway deployment status" in error for error in errors))
        self.assertTrue(any("product contract release status" in error for error in errors))

    def test_epic02_phase_a_record_and_overlay_overclaim_are_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        record_target = ROOT / binding_by_role(checkpoint, "EPIC02_PHASE_A_RECORD")["path"]
        overlay_target = (
            ROOT / binding_by_role(checkpoint, "EPIC02_PHASE_A_ACTIVE_OVERLAY")["path"]
        )

        def mutate(path: Path, document: dict) -> dict:
            resolved = Path(path).resolve()
            if resolved == record_target.resolve():
                document = copy.deepcopy(document)
                document["authority_boundary"]["claims_epic_complete"] = True
                document["release_boundary"]["formal_tests_not_run"] = 0
                document["release_boundary"]["release_status"] = "ELIGIBLE"
            elif resolved == overlay_target.resolve():
                document = copy.deepcopy(document)
                document["authority_boundary"]["changes_lifecycle_or_approval_state"] = True
                document["events"][0]["lifecycle_status_after"] = "APPROVED_BASELINED"
                document["formal_boundary"]["remaining_gates_waived"] = True
            return document

        errors = self._validate_with_loader_mutation(mutate)

        self.assertTrue(any("EPIC-02 Phase A record seal" in error for error in errors))
        self.assertTrue(any("EPIC-02 Phase A complete claim" in error for error in errors))
        self.assertTrue(any("EPIC-02 Phase A formal tests NOT_RUN" in error for error in errors))
        self.assertTrue(any("EPIC-02 Phase A release status" in error for error in errors))
        self.assertTrue(any("EPIC-02 Phase A overlay seal" in error for error in errors))
        self.assertTrue(any("EPIC-02 Phase A overlay lifecycle promotion" in error for error in errors))
        self.assertTrue(any("EPIC-02 Phase A overlay promotes an Active artifact" in error for error in errors))
        self.assertTrue(any("EPIC-02 Phase A overlay gates waived" in error for error in errors))

    def _fp011_candidate_checkpoint(
        self,
        fixture_root: Path,
        *,
        suffix_length: int = 4,
    ) -> dict:
        if suffix_length not in range(1, 5):
            raise ValueError("suffix_length must be between 1 and 4")
        checkpoint = copy.deepcopy(continuation.load_json(CHECKPOINT))
        state = checkpoint["goal_execution"]

        fixture_documents = {
            continuation.EXPECTED_R014_GAP_PATH: (
                '{"metadata":{"report_id":"'
                + continuation.EXPECTED_R014_GAP_DOCUMENT_ID
                + '"}}\n'
            ),
            continuation.EXPECTED_R014_BACKLOG_PATH: (
                '{"metadata":{"backlog_id":"'
                + continuation.EXPECTED_R014_BACKLOG_DOCUMENT_ID
                + '"}}\n'
            ),
            continuation.EXPECTED_FP010_COMPLETION_RECEIPT_PATH: (
                '{"document_id":"'
                + continuation.EXPECTED_FP010_COMPLETION_RECEIPT_ID
                + '"}\n'
            ),
            continuation.EXPECTED_FP011_GOAL_PATH: "FP011 Goal fixture\n",
        }
        for relative, content in fixture_documents.items():
            target = fixture_root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        def fixture_path(relative: str) -> Path:
            candidate = fixture_root / relative
            return candidate if candidate.is_file() else ROOT / relative

        def goal_sha256(relative: str) -> str:
            return continuation.sha256_file(fixture_path(relative))

        def event_binding(
            role: str,
            document_id: str,
            relative: str,
        ) -> dict:
            return {
                "role": role,
                "document_id": document_id,
                "path": relative,
                "file_sha256": continuation.sha256_file(
                    fixture_path(relative)
                ),
            }

        gap_binding = event_binding(
            "IMPLEMENTATION_GAP",
            continuation.EXPECTED_R014_GAP_DOCUMENT_ID,
            continuation.EXPECTED_R014_GAP_PATH,
        )
        backlog_binding = event_binding(
            "IMPLEMENTATION_BACKLOG",
            continuation.EXPECTED_R014_BACKLOG_DOCUMENT_ID,
            continuation.EXPECTED_R014_BACKLOG_PATH,
        )
        completion_binding = event_binding(
            continuation.EXPECTED_FP010_COMPLETION_ROLE,
            continuation.EXPECTED_FP010_COMPLETION_RECEIPT_ID,
            continuation.EXPECTED_FP010_COMPLETION_RECEIPT_PATH,
        )

        def runtime(
            *,
            focus_goal_id: str,
            focus_goal_path: str,
            focus_work_item_id: str,
            focus_source: str,
            ready_frontier_goal_ids: list[str],
        ) -> dict:
            value = copy.deepcopy(
                state["transition_history"][-1]["runtime_after"]
            )
            value.update(
                {
                    "focus_goal_id": focus_goal_id,
                    "focus_goal_path": focus_goal_path,
                    "focus_work_item_id": focus_work_item_id,
                    "focus_source": focus_source,
                    "ready_frontier_goal_ids": ready_frontier_goal_ids,
                }
            )
            return value

        def append_event(
            *,
            event_id: str,
            event_type: str,
            occurred_at: str,
            focus_goal_id: str,
            focus_goal_path: str,
            from_status: str,
            to_status: str,
            status_changes: dict[str, str],
            runtime_after: dict,
            evidence_refs: list[str],
            extra: dict | None = None,
        ) -> dict:
            previous = state["transition_history"][-1]
            previous_runtime = previous["runtime_after"]
            event = {
                "sequence": len(state["transition_history"]) + 1,
                "event_id": event_id,
                "event_type": event_type,
                "occurred_on": "2026-07-25",
                "occurred_at": occurred_at,
                "previous_focus_goal_id": previous_runtime["focus_goal_id"],
                "previous_focus_content_sha256": goal_sha256(
                    previous_runtime["focus_goal_path"]
                ),
                "focus_goal_id": focus_goal_id,
                "focus_goal_content_sha256": goal_sha256(focus_goal_path),
                "from_status": from_status,
                "to_status": to_status,
                "static_plan_manifest_sha256": (
                    continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256
                ),
                "status_changes": status_changes,
                "runtime_after": runtime_after,
                "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
                "blocker_resolution_ids_after": [],
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": evidence_refs,
                "previous_event_sha256": previous["event_sha256"],
            }
            if extra:
                event.update(extra)
            self._reseal_event(event)
            state["transition_history"].append(event)
            state["transition_history_anchor_sha256"] = event["event_sha256"]
            state["status_by_goal"].update(status_changes)
            self._sync_v23_runtime_from_tail(state)
            return event

        canonical_snapshot = copy.deepcopy(
            state["transition_history"][8][
                "canonical_binding_snapshot_after"
            ]
        )
        canonical_snapshot.update(
            {
                "IMPLEMENTATION_GAP": gap_binding,
                "IMPLEMENTATION_BACKLOG": backlog_binding,
                continuation.EXPECTED_FP010_COMPLETION_ROLE: (
                    completion_binding
                ),
            }
        )
        updated = append_event(
            event_id=(
                "WS-GOAL-GRAPH-V2-3-CANONICAL-BINDINGS-UPDATED-"
                "FP010-TEST-001"
            ),
            event_type="CANONICAL_BINDINGS_UPDATED",
            occurred_at="2026-07-25T02:10:00+09:00",
            focus_goal_id=continuation.EXPECTED_FP010_GOAL_ID,
            focus_goal_path=continuation.EXPECTED_FP010_GOAL_PATH,
            from_status="IN_PROGRESS",
            to_status="IN_PROGRESS",
            status_changes={},
            runtime_after=runtime(
                focus_goal_id=continuation.EXPECTED_FP010_GOAL_ID,
                focus_goal_path=continuation.EXPECTED_FP010_GOAL_PATH,
                focus_work_item_id=(
                    continuation.EXPECTED_FP010_GOAL_WORK_ITEM_ID
                ),
                focus_source="IMPLEMENTATION_BACKLOG",
                ready_frontier_goal_ids=list(
                    continuation.EXPECTED_FP010_GOAL_READY_FRONTIER
                ),
            ),
            evidence_refs=[
                "IMPLEMENTATION_BACKLOG",
                "IMPLEMENTATION_GAP",
                continuation.EXPECTED_FP010_COMPLETION_ROLE,
            ],
            extra={
                "changed_binding_roles": [
                    "IMPLEMENTATION_BACKLOG",
                    "IMPLEMENTATION_GAP",
                    continuation.EXPECTED_FP010_COMPLETION_ROLE,
                ],
                "changed_subject_ids_by_role": {
                    "IMPLEMENTATION_BACKLOG": ["FP-010"],
                    "IMPLEMENTATION_GAP": ["FP-010", "GAP-019"],
                },
                "impact_closure_goal_ids": ["WS-GOAL-EPIC-02"],
                "impact_disposition_by_goal": {
                    "WS-GOAL-EPIC-02": {
                        "result": "REVALIDATION_REFRESH_REQUIRED",
                        "target_status": "READY",
                    }
                },
                "reopened_completion_event_sha256_by_goal": {},
                "canonical_binding_snapshot_after": canonical_snapshot,
                "produced_by_goal_id": continuation.EXPECTED_FP010_GOAL_ID,
                "produced_binding_roles": [
                    "IMPLEMENTATION_BACKLOG",
                    "IMPLEMENTATION_GAP",
                ],
                "producer_output_subject_ids_by_role": {
                    "IMPLEMENTATION_BACKLOG": ["FP-010"],
                    "IMPLEMENTATION_GAP": ["FP-010", "GAP-019"],
                },
                "producer_completion_receipt_binding": completion_binding,
            },
        )
        if suffix_length == 1:
            return checkpoint

        epic02 = state["imported_predecessor_goal_bindings"][
            "WS-GOAL-EPIC-02"
        ]
        completed = append_event(
            event_id="WS-GOAL-GRAPH-V2-3-GOAL-COMPLETED-FP010-TEST-001",
            event_type="GOAL_COMPLETED",
            occurred_at="2026-07-25T02:10:01+09:00",
            focus_goal_id="WS-GOAL-EPIC-02",
            focus_goal_path=epic02["path"],
            from_status="IN_PROGRESS",
            to_status="COMPLETE_AT_TARGET",
            status_changes={
                continuation.EXPECTED_FP010_GOAL_ID: "COMPLETE_AT_TARGET"
            },
            runtime_after=runtime(
                focus_goal_id="WS-GOAL-EPIC-02",
                focus_goal_path=epic02["path"],
                focus_work_item_id="",
                focus_source="WORKSTREAM_GRAPH",
                ready_frontier_goal_ids=[
                    "WS-GOAL-EPIC-02",
                    "WS-GOAL-EPIC-03",
                    "WS-GOAL-EPIC-12",
                ],
            ),
            evidence_refs=[continuation.EXPECTED_FP010_COMPLETION_ROLE],
            extra={
                "subject_goal_id": continuation.EXPECTED_FP010_GOAL_ID,
                "completion_evidence_bindings": {
                    continuation.EXPECTED_FP010_COMPLETION_ROLE: (
                        completion_binding
                    )
                },
                "completion_receipt_binding": completion_binding,
                "canonical_update_event_sha256": updated["event_sha256"],
            },
        )
        state["completion_evidence_by_goal"][
            continuation.EXPECTED_FP010_GOAL_ID
        ] = [continuation.EXPECTED_FP010_COMPLETION_ROLE]
        if suffix_length == 2:
            return checkpoint

        fp010_goal_sha256 = goal_sha256(
            continuation.EXPECTED_FP010_GOAL_PATH
        )
        fp011_goal_sha256 = goal_sha256(
            continuation.EXPECTED_FP011_GOAL_PATH
        )
        epic03 = state["imported_predecessor_goal_bindings"][
            "WS-GOAL-EPIC-03"
        ]
        materialized = append_event(
            event_id=(
                "WS-GOAL-GRAPH-V2-3-GOAL-MATERIALIZED-FP011-TEST-001"
            ),
            event_type="GOAL_MATERIALIZED",
            occurred_at="2026-07-25T02:10:02+09:00",
            focus_goal_id="WS-GOAL-EPIC-03",
            focus_goal_path=epic03["path"],
            from_status="",
            to_status="PLANNED",
            status_changes={continuation.EXPECTED_FP011_GOAL_ID: "PLANNED"},
            runtime_after=runtime(
                focus_goal_id="WS-GOAL-EPIC-03",
                focus_goal_path=epic03["path"],
                focus_work_item_id="",
                focus_source="WORKSTREAM_GRAPH",
                ready_frontier_goal_ids=[
                    "WS-GOAL-EPIC-03",
                    "WS-GOAL-EPIC-12",
                ],
            ),
            evidence_refs=["IMPLEMENTATION_BACKLOG"],
            extra={
                "materialized_goal_id": continuation.EXPECTED_FP011_GOAL_ID,
                "materialized_goal_path": continuation.EXPECTED_FP011_GOAL_PATH,
                "materialized_goal_content_sha256": fp011_goal_sha256,
                "materialized_from_role": "IMPLEMENTATION_BACKLOG",
                "materialized_from_path": (
                    continuation.EXPECTED_R014_BACKLOG_PATH
                ),
                "materialized_from_document_id": (
                    continuation.EXPECTED_R014_BACKLOG_DOCUMENT_ID
                ),
                "materialized_from_sha256": backlog_binding["file_sha256"],
                "predecessor_goal_id": (
                    continuation.EXPECTED_FP010_GOAL_ID
                ),
                "predecessor_goal_content_sha256": fp010_goal_sha256,
                "supersedes_goal_id": "",
                "supersedes_goal_content_sha256": "",
                "artifact_work_reason": "",
                "artifact_trigger_evidence_refs": [],
                "artifact_trigger_evidence_bindings": {},
            },
        )
        state["dynamic_goal_inventory"][
            continuation.EXPECTED_FP011_GOAL_ID
        ] = {
            "goal_id": continuation.EXPECTED_FP011_GOAL_ID,
            "path": continuation.EXPECTED_FP011_GOAL_PATH,
            "sha256": fp011_goal_sha256,
            "goal_kind": "WORK_ITEM",
            "work_item_type": "POLICY_GAP_WORK",
            "parent_goal_id": "WS-GOAL-EPIC-02",
            "materialized_event_sha256": materialized["event_sha256"],
            "materialized_from_role": "IMPLEMENTATION_BACKLOG",
            "materialized_from_path": continuation.EXPECTED_R014_BACKLOG_PATH,
            "materialized_from_document_id": (
                continuation.EXPECTED_R014_BACKLOG_DOCUMENT_ID
            ),
            "materialized_from_sha256": backlog_binding["file_sha256"],
            "predecessor_goal_id": continuation.EXPECTED_FP010_GOAL_ID,
            "predecessor_goal_content_sha256": fp010_goal_sha256,
            "supersedes_goal_id": "",
            "supersedes_goal_content_sha256": "",
            "artifact_work_reason": "",
            "artifact_trigger_evidence_refs": [],
        }
        state["materialized_child_goal_ids_by_parent"][
            "WS-GOAL-EPIC-02"
        ].append(continuation.EXPECTED_FP011_GOAL_ID)
        if suffix_length == 3:
            return checkpoint

        append_event(
            event_id="WS-GOAL-GRAPH-V2-3-GOAL-READY-FP011-TEST-001",
            event_type="GOAL_READY",
            occurred_at="2026-07-25T02:10:03+09:00",
            focus_goal_id=continuation.EXPECTED_FP011_GOAL_ID,
            focus_goal_path=continuation.EXPECTED_FP011_GOAL_PATH,
            from_status="PLANNED",
            to_status="READY",
            status_changes={continuation.EXPECTED_FP011_GOAL_ID: "READY"},
            runtime_after=runtime(
                focus_goal_id=continuation.EXPECTED_FP011_GOAL_ID,
                focus_goal_path=continuation.EXPECTED_FP011_GOAL_PATH,
                focus_work_item_id=(
                    continuation.EXPECTED_FP011_GOAL_WORK_ITEM_ID
                ),
                focus_source="IMPLEMENTATION_BACKLOG",
                ready_frontier_goal_ids=list(
                    continuation.EXPECTED_FP011_GOAL_READY_FRONTIER
                ),
            ),
            evidence_refs=[],
            extra={
                "subject_goal_id": continuation.EXPECTED_FP011_GOAL_ID,
                "readiness_basis": {
                    "dependency_completion_events": [
                        {
                            "goal_id": continuation.EXPECTED_FP010_GOAL_ID,
                            "event_sha256": completed["event_sha256"],
                        }
                    ]
                },
                "start_evidence_bindings": {},
                "start_evidence_provenance": {},
            },
        )
        return checkpoint

    @staticmethod
    def _fp011_candidate_resolver(fixture_root: Path):
        original = continuation.resolve_safe_repo_file

        def resolve(root: Path, relative) -> Path | None:
            if isinstance(relative, str):
                candidate = fixture_root / relative
                if candidate.is_file():
                    return candidate
            return original(root, relative)

        return resolve

    def _fp010_candidate_checkpoint(
        self,
        fp010_goal_file: Path,
        *,
        start_gate: dict | None = None,
    ) -> dict:
        checkpoint = copy.deepcopy(continuation.load_json(CHECKPOINT))
        state = checkpoint["goal_execution"]
        state["transition_history"] = state["transition_history"][:8]
        state["transition_history_anchor_sha256"] = state["transition_history"][
            -1
        ]["event_sha256"]
        replayed_statuses: dict[str, str] = {}
        for event in state["transition_history"]:
            replayed_statuses.update(event["status_changes"])
        state["status_by_goal"] = replayed_statuses
        state["dynamic_goal_inventory"].pop(
            continuation.EXPECTED_FP010_GOAL_ID,
            None,
        )
        state["completion_evidence_by_goal"].pop(
            continuation.EXPECTED_CURRENT_GOAL_ID,
            None,
        )
        state["materialized_child_goal_ids_by_parent"][
            "WS-GOAL-EPIC-02"
        ] = [
            goal_id
            for goal_id in state["materialized_child_goal_ids_by_parent"][
                "WS-GOAL-EPIC-02"
            ]
            if goal_id != continuation.EXPECTED_FP010_GOAL_ID
        ]
        self._sync_v23_runtime_from_tail(state)

        fp006_goal_sha256 = continuation.sha256_file(
            ROOT / continuation.EXPECTED_CURRENT_GOAL_PATH
        )
        fp010_goal_sha256 = continuation.sha256_file(fp010_goal_file)
        manifest_sha256 = continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256

        def goal_sha256(relative: str) -> str:
            if relative == continuation.EXPECTED_FP010_GOAL_PATH:
                return fp010_goal_sha256
            return continuation.sha256_file(ROOT / relative)

        def event_binding(
            role: str,
            document_id: str,
            relative: str,
        ) -> dict:
            return {
                "role": role,
                "document_id": document_id,
                "path": relative,
                "file_sha256": continuation.sha256_file(ROOT / relative),
            }

        gap_binding = event_binding(
            "IMPLEMENTATION_GAP",
            continuation.EXPECTED_R013_GAP_DOCUMENT_ID,
            continuation.EXPECTED_R013_GAP_PATH,
        )
        backlog_binding = event_binding(
            "IMPLEMENTATION_BACKLOG",
            continuation.EXPECTED_R013_BACKLOG_DOCUMENT_ID,
            continuation.EXPECTED_R013_BACKLOG_PATH,
        )
        completion_binding = event_binding(
            continuation.EXPECTED_FP006_COMPLETION_ROLE,
            continuation.EXPECTED_FP006_COMPLETION_RECEIPT_ID,
            continuation.EXPECTED_FP006_COMPLETION_RECEIPT_PATH,
        )

        def runtime(
            *,
            focus_goal_id: str,
            focus_goal_path: str,
            focus_work_item_id: str,
            focus_source: str,
            ready_frontier_goal_ids: list[str],
        ) -> dict:
            value = copy.deepcopy(
                state["transition_history"][-1]["runtime_after"]
            )
            value.update(
                {
                    "focus_goal_id": focus_goal_id,
                    "focus_goal_path": focus_goal_path,
                    "focus_work_item_id": focus_work_item_id,
                    "focus_source": focus_source,
                    "ready_frontier_goal_ids": ready_frontier_goal_ids,
                }
            )
            return value

        def append_event(
            *,
            event_id: str,
            event_type: str,
            occurred_at: str,
            focus_goal_id: str,
            focus_goal_path: str,
            from_status: str,
            to_status: str,
            status_changes: dict[str, str],
            runtime_after: dict,
            evidence_refs: list[str],
            extra: dict | None = None,
        ) -> dict:
            previous = state["transition_history"][-1]
            previous_runtime = previous["runtime_after"]
            event = {
                "sequence": len(state["transition_history"]) + 1,
                "event_id": event_id,
                "event_type": event_type,
                "occurred_on": "2026-07-24",
                "occurred_at": occurred_at,
                "previous_focus_goal_id": previous_runtime["focus_goal_id"],
                "previous_focus_content_sha256": goal_sha256(
                    previous_runtime["focus_goal_path"]
                ),
                "focus_goal_id": focus_goal_id,
                "focus_goal_content_sha256": goal_sha256(focus_goal_path),
                "from_status": from_status,
                "to_status": to_status,
                "static_plan_manifest_sha256": manifest_sha256,
                "status_changes": status_changes,
                "runtime_after": runtime_after,
                "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
                "blocker_resolution_ids_after": [],
                "source_checkpoint_version": checkpoint["schema_version"],
                "evidence_refs": evidence_refs,
                "previous_event_sha256": previous["event_sha256"],
            }
            if extra:
                event.update(extra)
            self._reseal_event(event)
            state["transition_history"].append(event)
            state["transition_history_anchor_sha256"] = event["event_sha256"]
            state["status_by_goal"].update(status_changes)
            self._sync_v23_runtime_from_tail(state)
            return event

        fp006_runtime = runtime(
            focus_goal_id=continuation.EXPECTED_CURRENT_GOAL_ID,
            focus_goal_path=continuation.EXPECTED_CURRENT_GOAL_PATH,
            focus_work_item_id=continuation.EXPECTED_CURRENT_GOAL_WORK_ITEM_ID,
            focus_source="IMPLEMENTATION_BACKLOG",
            ready_frontier_goal_ids=list(
                continuation.EXPECTED_CURRENT_GOAL_READY_FRONTIER
            ),
        )
        canonical_snapshot = copy.deepcopy(
            state["transition_history"][3]["canonical_binding_snapshot_after"]
        )
        canonical_snapshot.update(
            {
                "IMPLEMENTATION_GAP": gap_binding,
                "IMPLEMENTATION_BACKLOG": backlog_binding,
                continuation.EXPECTED_FP006_COMPLETION_ROLE: completion_binding,
            }
        )
        updated = append_event(
            event_id=(
                "WS-GOAL-GRAPH-V2-3-CANONICAL-BINDINGS-UPDATED-"
                "FP006-TEST-001"
            ),
            event_type="CANONICAL_BINDINGS_UPDATED",
            occurred_at="2026-07-24T23:31:00+09:00",
            focus_goal_id=continuation.EXPECTED_CURRENT_GOAL_ID,
            focus_goal_path=continuation.EXPECTED_CURRENT_GOAL_PATH,
            from_status="IN_PROGRESS",
            to_status="IN_PROGRESS",
            status_changes={},
            runtime_after=fp006_runtime,
            evidence_refs=[
                "IMPLEMENTATION_BACKLOG",
                "IMPLEMENTATION_GAP",
                continuation.EXPECTED_FP006_COMPLETION_ROLE,
            ],
            extra={
                "changed_binding_roles": [
                    "IMPLEMENTATION_BACKLOG",
                    "IMPLEMENTATION_GAP",
                    continuation.EXPECTED_FP006_COMPLETION_ROLE,
                ],
                "changed_subject_ids_by_role": {
                    "IMPLEMENTATION_BACKLOG": ["FP-006"],
                    "IMPLEMENTATION_GAP": ["FP-006", "GAP-015"],
                },
                "impact_closure_goal_ids": ["WS-GOAL-EPIC-02"],
                "impact_disposition_by_goal": {
                    "WS-GOAL-EPIC-02": {
                        "result": "REVALIDATION_REFRESH_REQUIRED",
                        "target_status": "READY",
                    }
                },
                "reopened_completion_event_sha256_by_goal": {},
                "canonical_binding_snapshot_after": canonical_snapshot,
                "produced_by_goal_id": continuation.EXPECTED_CURRENT_GOAL_ID,
                "produced_binding_roles": [
                    "IMPLEMENTATION_BACKLOG",
                    "IMPLEMENTATION_GAP",
                ],
                "producer_output_subject_ids_by_role": {
                    "IMPLEMENTATION_BACKLOG": ["FP-006"],
                    "IMPLEMENTATION_GAP": ["FP-006", "GAP-015"],
                },
                "producer_completion_receipt_binding": completion_binding,
            },
        )

        epic02 = state["imported_predecessor_goal_bindings"][
            "WS-GOAL-EPIC-02"
        ]
        completed = append_event(
            event_id="WS-GOAL-GRAPH-V2-3-GOAL-COMPLETED-FP006-TEST-001",
            event_type="GOAL_COMPLETED",
            occurred_at="2026-07-24T23:31:01+09:00",
            focus_goal_id="WS-GOAL-EPIC-02",
            focus_goal_path=epic02["path"],
            from_status="IN_PROGRESS",
            to_status="COMPLETE_AT_TARGET",
            status_changes={
                continuation.EXPECTED_CURRENT_GOAL_ID: "COMPLETE_AT_TARGET"
            },
            runtime_after=runtime(
                focus_goal_id="WS-GOAL-EPIC-02",
                focus_goal_path=epic02["path"],
                focus_work_item_id="",
                focus_source="WORKSTREAM_GRAPH",
                ready_frontier_goal_ids=[
                    "WS-GOAL-EPIC-02",
                    "WS-GOAL-EPIC-03",
                    "WS-GOAL-EPIC-12",
                ],
            ),
            evidence_refs=[continuation.EXPECTED_FP006_COMPLETION_ROLE],
            extra={
                "subject_goal_id": continuation.EXPECTED_CURRENT_GOAL_ID,
                "completion_evidence_bindings": {
                    continuation.EXPECTED_FP006_COMPLETION_ROLE: (
                        completion_binding
                    )
                },
                "completion_receipt_binding": completion_binding,
                "canonical_update_event_sha256": updated["event_sha256"],
            },
        )
        state["completion_evidence_by_goal"][
            continuation.EXPECTED_CURRENT_GOAL_ID
        ] = [continuation.EXPECTED_FP006_COMPLETION_ROLE]

        epic03 = state["imported_predecessor_goal_bindings"][
            "WS-GOAL-EPIC-03"
        ]
        materialized = append_event(
            event_id="WS-GOAL-GRAPH-V2-3-GOAL-MATERIALIZED-FP010-TEST-001",
            event_type="GOAL_MATERIALIZED",
            occurred_at="2026-07-24T23:31:02+09:00",
            focus_goal_id="WS-GOAL-EPIC-03",
            focus_goal_path=epic03["path"],
            from_status="",
            to_status="PLANNED",
            status_changes={continuation.EXPECTED_FP010_GOAL_ID: "PLANNED"},
            runtime_after=runtime(
                focus_goal_id="WS-GOAL-EPIC-03",
                focus_goal_path=epic03["path"],
                focus_work_item_id="",
                focus_source="WORKSTREAM_GRAPH",
                ready_frontier_goal_ids=[
                    "WS-GOAL-EPIC-03",
                    "WS-GOAL-EPIC-12",
                ],
            ),
            evidence_refs=["IMPLEMENTATION_BACKLOG"],
            extra={
                "materialized_goal_id": continuation.EXPECTED_FP010_GOAL_ID,
                "materialized_goal_path": continuation.EXPECTED_FP010_GOAL_PATH,
                "materialized_goal_content_sha256": fp010_goal_sha256,
                "materialized_from_role": "IMPLEMENTATION_BACKLOG",
                "materialized_from_path": continuation.EXPECTED_R013_BACKLOG_PATH,
                "materialized_from_document_id": (
                    continuation.EXPECTED_R013_BACKLOG_DOCUMENT_ID
                ),
                "materialized_from_sha256": backlog_binding["file_sha256"],
                "predecessor_goal_id": continuation.EXPECTED_CURRENT_GOAL_ID,
                "predecessor_goal_content_sha256": fp006_goal_sha256,
                "supersedes_goal_id": "",
                "supersedes_goal_content_sha256": "",
                "artifact_work_reason": "",
                "artifact_trigger_evidence_refs": [],
                "artifact_trigger_evidence_bindings": {},
            },
        )
        state["dynamic_goal_inventory"][
            continuation.EXPECTED_FP010_GOAL_ID
        ] = {
            "goal_id": continuation.EXPECTED_FP010_GOAL_ID,
            "path": continuation.EXPECTED_FP010_GOAL_PATH,
            "sha256": fp010_goal_sha256,
            "goal_kind": "WORK_ITEM",
            "work_item_type": "POLICY_GAP_WORK",
            "parent_goal_id": "WS-GOAL-EPIC-02",
            "materialized_event_sha256": materialized["event_sha256"],
            "materialized_from_role": "IMPLEMENTATION_BACKLOG",
            "materialized_from_path": continuation.EXPECTED_R013_BACKLOG_PATH,
            "materialized_from_document_id": (
                continuation.EXPECTED_R013_BACKLOG_DOCUMENT_ID
            ),
            "materialized_from_sha256": backlog_binding["file_sha256"],
            "predecessor_goal_id": continuation.EXPECTED_CURRENT_GOAL_ID,
            "predecessor_goal_content_sha256": fp006_goal_sha256,
            "supersedes_goal_id": "",
            "supersedes_goal_content_sha256": "",
            "artifact_work_reason": "",
            "artifact_trigger_evidence_refs": [],
        }
        state["materialized_child_goal_ids_by_parent"][
            "WS-GOAL-EPIC-02"
        ].append(continuation.EXPECTED_FP010_GOAL_ID)

        ready = append_event(
            event_id="WS-GOAL-GRAPH-V2-3-GOAL-READY-FP010-TEST-001",
            event_type="GOAL_READY",
            occurred_at="2026-07-24T23:31:03+09:00",
            focus_goal_id=continuation.EXPECTED_FP010_GOAL_ID,
            focus_goal_path=continuation.EXPECTED_FP010_GOAL_PATH,
            from_status="PLANNED",
            to_status="READY",
            status_changes={continuation.EXPECTED_FP010_GOAL_ID: "READY"},
            runtime_after=runtime(
                focus_goal_id=continuation.EXPECTED_FP010_GOAL_ID,
                focus_goal_path=continuation.EXPECTED_FP010_GOAL_PATH,
                focus_work_item_id=(
                    continuation.EXPECTED_FP010_GOAL_WORK_ITEM_ID
                ),
                focus_source="IMPLEMENTATION_BACKLOG",
                ready_frontier_goal_ids=list(
                    continuation.EXPECTED_FP010_GOAL_READY_FRONTIER
                ),
            ),
            evidence_refs=[],
            extra={
                "subject_goal_id": continuation.EXPECTED_FP010_GOAL_ID,
                "readiness_basis": {
                    "dependency_completion_events": [
                        {
                            "goal_id": continuation.EXPECTED_CURRENT_GOAL_ID,
                            "event_sha256": completed["event_sha256"],
                        }
                    ]
                },
                "start_evidence_bindings": {},
                "start_evidence_provenance": {},
            },
        )

        if start_gate is not None:
            append_event(
                event_id=start_gate["event_id"],
                event_type="GOAL_STARTED",
                occurred_at="2026-07-24T23:31:04+09:00",
                focus_goal_id=continuation.EXPECTED_FP010_GOAL_ID,
                focus_goal_path=continuation.EXPECTED_FP010_GOAL_PATH,
                from_status="READY",
                to_status="IN_PROGRESS",
                status_changes={
                    continuation.EXPECTED_FP010_GOAL_ID: "IN_PROGRESS"
                },
                runtime_after=copy.deepcopy(ready["runtime_after"]),
                evidence_refs=[],
                extra={
                    "subject_goal_id": continuation.EXPECTED_FP010_GOAL_ID,
                    "implementation_start_gate_binding": start_gate["binding"],
                    "repository_snapshot_before": start_gate[
                        "repository_snapshot"
                    ],
                },
            )
        return checkpoint

    @staticmethod
    def _fp010_candidate_resolver(
        goal_file: Path,
        fixture_root: Path | None = None,
    ):
        original = continuation.resolve_safe_repo_file

        def resolve(root: Path, relative) -> Path | None:
            if relative == continuation.EXPECTED_FP010_GOAL_PATH:
                return goal_file
            if (
                fixture_root is not None
                and isinstance(relative, str)
                and relative.startswith("docs/control/execution/goal-gates/")
            ):
                candidate = fixture_root / relative
                if candidate.is_file():
                    return candidate
            return original(root, relative)

        return resolve

    def _fp010_start_gate_fixture(
        self,
        fixture_root: Path,
        goal_file: Path,
    ) -> dict:
        event_id = "WS-GOAL-GRAPH-V2-3-GOAL-STARTED-FP010-TEST-001"
        gate_dir = (
            fixture_root / "docs/control/execution/goal-gates" / event_id
        )
        gate_dir.mkdir(parents=True)
        output_path = gate_dir / "19-REPOSITORY_STATE.log"
        controlled_content_sha256 = "2" * 64
        repository_payload = {
            "evidence_type": continuation.GATE_REPOSITORY_STATE_EVIDENCE_TYPE,
            "gate_event_id": event_id,
            "checkpoint_controlled_working_snapshot": {
                "managed_changed_path_count": (
                    continuation.EXPECTED_SEQ13_CONTROLLED_PATH_COUNT
                ),
                "path_set_sha256": (
                    continuation.EXPECTED_SEQ13_CONTROLLED_PATH_SET_SHA256
                ),
                "content_set_sha256": controlled_content_sha256,
            },
        }
        output_path.write_text(
            json.dumps(
                repository_payload,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n",
            encoding="utf-8",
        )
        output_sha256 = continuation.sha256_file(output_path)
        repository_snapshot = {
            "gate_event_id": event_id,
            "checkpoint_managed_path_count": (
                continuation.EXPECTED_SEQ13_CONTROLLED_PATH_COUNT
            ),
            "checkpoint_path_set_sha256": (
                continuation.EXPECTED_SEQ13_CONTROLLED_PATH_SET_SHA256
            ),
            "checkpoint_content_set_sha256": controlled_content_sha256,
            "gate_repository_state_output_sha256": output_sha256,
        }
        receipt_path = gate_dir / "implementation-start-gate-receipt.json"
        receipt = {
            "schema_version": "1.0",
            "document_id": (
                "WS-GOAL-GRAPH-V2-3-IMPLEMENTATION-START-GATE-"
                "FP010-TEST-001"
            ),
            "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
            "gate_purpose": "INITIAL_START",
            "status": "PASS",
            "package_id": continuation.EXPECTED_ACTIVE_GOAL_PACKAGE_ID,
            "target_transition_event_id": event_id,
            "target_goal_id": continuation.EXPECTED_FP010_GOAL_ID,
            "target_goal_content_sha256": continuation.sha256_file(goal_file),
            "static_plan_manifest_sha256": (
                continuation.EXPECTED_ACTIVE_GOAL_MANIFEST_SHA256
            ),
            "source_activation_event_sha256": (
                continuation.EXPECTED_V23_ACTIVATION_EVENT_SHA256
            ),
            "check_command_contract_version": (
                continuation.EXPECTED_V23_START_GATE_CONTRACT_VERSION
            ),
            "check_command_contract_sha256": (
                continuation.EXPECTED_V23_START_GATE_CONTRACT_SHA256
            ),
            "repository_snapshot": repository_snapshot,
            "check_runs": [{} for _ in range(18)]
            + [
                {
                    "check_id": "REPOSITORY_STATE",
                    "output_path": output_path.relative_to(
                        fixture_root
                    ).as_posix(),
                    "output_sha256": output_sha256,
                }
            ],
        }
        receipt_path.write_text(
            json.dumps(receipt, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return {
            "event_id": event_id,
            "binding": {
                "document_id": receipt["document_id"],
                "path": receipt_path.relative_to(fixture_root).as_posix(),
                "file_sha256": continuation.sha256_file(receipt_path),
            },
            "repository_snapshot": repository_snapshot,
        }

    def _prepared_checkpoint_fixture(self) -> dict:
        checkpoint = copy.deepcopy(continuation.load_json(CHECKPOINT))
        state = checkpoint["goal_execution"]
        prepared = copy.deepcopy(state["transition_history"][0])
        state["transition_history"] = [prepared]
        state["transition_history_anchor_sha256"] = prepared["event_sha256"]
        state["status_by_goal"] = copy.deepcopy(prepared["status_changes"])
        self._sync_v23_runtime_from_tail(state)
        return checkpoint

    def _append_v23_activation(self, checkpoint: dict) -> None:
        state = checkpoint["goal_execution"]
        previous_runtime = state["transition_history"][-1]["runtime_after"]
        runtime = copy.deepcopy(previous_runtime)
        runtime["activation_status"] = "ACTIVE"
        runtime["package_status"] = "ACTIVE"
        focus_id = runtime["focus_goal_id"]
        focus_path = runtime["focus_goal_path"]
        focus_status = state["status_by_goal"][focus_id]
        event = {
            "sequence": len(state["transition_history"]) + 1,
            "event_id": "WS-GOAL-GRAPH-V2-3-PACKAGE-ACTIVATED-TEST-001",
            "event_type": "PACKAGE_ACTIVATED",
            "occurred_on": "2026-07-24",
            "occurred_at": "2026-07-24T18:00:00+09:00",
            "previous_focus_goal_id": previous_runtime["focus_goal_id"],
            "previous_focus_content_sha256": continuation.sha256_file(
                ROOT / previous_runtime["focus_goal_path"]
            ),
            "focus_goal_id": focus_id,
            "focus_goal_content_sha256": continuation.sha256_file(
                ROOT / focus_path
            ),
            "from_status": focus_status,
            "to_status": focus_status,
            "static_plan_manifest_sha256": state[
                "static_plan_manifest_sha256"
            ],
            "status_changes": {},
            "runtime_after": runtime,
            "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": [],
            "previous_event_sha256": state["transition_history"][-1][
                "event_sha256"
            ],
        }
        self._reseal_event(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        self._sync_v23_runtime_from_tail(state)

    def _append_v23_start(self, checkpoint: dict) -> None:
        state = checkpoint["goal_execution"]
        previous_runtime = state["transition_history"][-1]["runtime_after"]
        goal_id = previous_runtime["focus_goal_id"]
        previous_status = state["status_by_goal"][goal_id]
        state["status_by_goal"][goal_id] = "IN_PROGRESS"
        runtime = copy.deepcopy(previous_runtime)
        event = {
            "sequence": len(state["transition_history"]) + 1,
            "event_id": "WS-GOAL-GRAPH-V2-3-GOAL-STARTED-TEST-001",
            "event_type": "GOAL_STARTED",
            "subject_goal_id": goal_id,
            "occurred_on": "2026-07-24",
            "occurred_at": "2026-07-24T18:01:00+09:00",
            "previous_focus_goal_id": previous_runtime["focus_goal_id"],
            "previous_focus_content_sha256": continuation.sha256_file(
                ROOT / previous_runtime["focus_goal_path"]
            ),
            "focus_goal_id": goal_id,
            "focus_goal_content_sha256": continuation.sha256_file(
                ROOT / runtime["focus_goal_path"]
            ),
            "from_status": previous_status,
            "to_status": "IN_PROGRESS",
            "static_plan_manifest_sha256": state[
                "static_plan_manifest_sha256"
            ],
            "status_changes": {goal_id: "IN_PROGRESS"},
            "runtime_after": runtime,
            "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
            "blocker_resolution_ids_after": [],
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": [],
            "previous_event_sha256": state["transition_history"][-1][
                "event_sha256"
            ],
        }
        self._reseal_event(event)
        state["transition_history"].append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        self._sync_v23_runtime_from_tail(state)

    @staticmethod
    def _reseal_event(event: dict) -> None:
        event.pop("event_sha256", None)
        event["event_sha256"] = continuation.canonical_json_sha256(event)

    @staticmethod
    def _sync_v23_runtime_from_tail(state: dict) -> None:
        runtime = state["transition_history"][-1]["runtime_after"]
        for field in continuation.V23_REPLAYED_RUNTIME_FIELDS:
            state[field] = copy.deepcopy(runtime[field])
        state["goal_status"] = state["status_by_goal"][
            continuation.EXPECTED_ACTIVE_GOAL_MASTER_ID
        ]

    def _validate_with_loader_mutation(self, mutator) -> list[str]:
        original = continuation.load_json

        def load_and_mutate(path: Path) -> dict:
            return mutator(path, original(path))

        with mock.patch.object(
            continuation,
            "load_json",
            side_effect=load_and_mutate,
        ):
            return continuation.validate(CHECKPOINT, ROOT)

    def _validate_temporary(self, checkpoint: dict) -> list[str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            tampered_path = Path(temp_dir) / "checkpoint.json"
            tampered_path.write_text(
                json.dumps(checkpoint, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            return continuation.validate(tampered_path, ROOT)


class GateRepositoryStateCaptureTest(unittest.TestCase):
    EVENT_ID = "GOAL-STARTED-TEST-001"

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self._git("init", "-b", "main")
        self._git("config", "user.name", "WalkSafe Test")
        self._git("config", "user.email", "walksafe-test@example.invalid")
        self._write("modified.txt", "before-one\n")
        self._write("deleted.txt", "delete-me\n")
        self._write("renamed-old.txt", "rename-me\n")
        self._write("staged.txt", "before-stage\n")
        self._write("unchanged.txt", "unchanged\n")
        self._write("controlled.txt", "controlled snapshot\n")
        self._git("add", ".")
        self._git("commit", "-m", "fixture baseline")

        self._write("modified.txt", "after-one!\n")
        (self.root / "deleted.txt").unlink()
        self._git("mv", "renamed-old.txt", "renamed-new.txt")
        self._write("staged.txt", "staged-version\n")
        self._git("add", "staged.txt")
        self._write("staged.txt", "worktree-version\n")
        self._write("untracked.txt", "SUPER-SECRET-RAW-CONTENT\n")
        self.checkpoint = (
            self.root / continuation.GATE_REPOSITORY_CHECKPOINT_PATH
        )
        self.checkpoint.parent.mkdir(parents=True, exist_ok=True)
        managed_paths = ["controlled.txt"]
        path_set_sha256 = hashlib.sha256(
            ("\n".join(managed_paths) + "\n").encode("utf-8")
        ).hexdigest()
        _, content_set_sha256 = continuation.working_snapshot_hashes(
            self.root,
            managed_paths,
        )
        fixture_head = self._git("rev-parse", "HEAD").stdout.strip()
        checkpoint_document = {
            "working_tree_snapshot": {
                "base_head": fixture_head,
                "managed_changed_paths": managed_paths,
                "managed_changed_path_count": len(managed_paths),
                "path_set_sha256": path_set_sha256,
                "content_set_sha256": content_set_sha256,
                "checkpoint_self_exclusion": (
                    "EXCLUDED_TO_AVOID_CIRCULAR_CONTENT_HASH; "
                    "VALIDATED_SEMANTICALLY"
                ),
            },
            "session_handoff": {
                "source_commit_or_snapshot": {
                    "base_commit": fixture_head,
                    "current_head": fixture_head,
                }
            },
        }
        self.checkpoint.write_text(
            json.dumps(checkpoint_document, sort_keys=True) + "\n",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_capture_detects_tracked_delete_rename_staged_index_and_untracked(self) -> None:
        state = self._capture()
        entries = {
            item["path"]: item
            for item in state["dirty_snapshot"]["paths"]
        }

        self.assertEqual(
            set(entries),
            {
                "deleted.txt",
                "modified.txt",
                "renamed-new.txt",
                "renamed-old.txt",
                "staged.txt",
                "untracked.txt",
            },
        )
        self.assertEqual(
            state["dirty_snapshot"]["dirty_path_count"],
            len(entries),
        )
        self.assertEqual(entries["modified.txt"]["status"]["xy"], ".M")
        self.assertEqual(
            entries["deleted.txt"]["worktree"]["deletion_marker"],
            "WORKTREE_PATH_ABSENT",
        )
        self.assertEqual(
            entries["renamed-new.txt"]["path_role"],
            "DESTINATION",
        )
        self.assertEqual(
            entries["renamed-new.txt"]["counterpart_path"],
            "renamed-old.txt",
        )
        self.assertEqual(
            entries["renamed-old.txt"]["path_role"],
            "RENAME_SOURCE",
        )
        self.assertEqual(
            entries["renamed-old.txt"]["worktree"]["type"],
            "DELETION_MARKER",
        )
        self.assertEqual(entries["staged.txt"]["status"]["xy"], "MM")
        self.assertEqual(
            entries["staged.txt"]["index_entries"][0]["stage"],
            0,
        )
        self.assertRegex(
            entries["staged.txt"]["index_entries"][0]["object_id"],
            r"\A[0-9a-f]{40}\Z",
        )
        self.assertEqual(entries["untracked.txt"]["status"]["kind"], "UNTRACKED")
        self.assertEqual(entries["untracked.txt"]["index_entries"], [])
        self.assertEqual(
            state["git_status_raw"]["record_count"],
            5,
        )
        self.assertEqual(
            state["git_status_raw"]["scope"],
            "AFTER_EXACT_TRANSACTION_EXCLUSIONS",
        )

    def test_same_status_byte_change_updates_only_content_digest(self) -> None:
        before = self._capture()
        self._write("modified.txt", "after-two!\n")
        after = self._capture()

        self.assertEqual(
            before["git_status_raw"]["sha256"],
            after["git_status_raw"]["sha256"],
        )
        self.assertEqual(
            before["dirty_snapshot"]["path_set_sha256"],
            after["dirty_snapshot"]["path_set_sha256"],
        )
        self.assertEqual(
            before["dirty_snapshot"]["index_state_sha256"],
            after["dirty_snapshot"]["index_state_sha256"],
        )
        self.assertNotEqual(
            before["dirty_snapshot"]["content_set_sha256"],
            after["dirty_snapshot"]["content_set_sha256"],
        )

    def test_same_xy_worktree_with_changed_staged_blob_updates_index_digest(self) -> None:
        before = self._capture()
        self._write("staged.txt", "second-stage\n")
        self._git("add", "staged.txt")
        self._write("staged.txt", "worktree-version\n")
        after = self._capture()

        self.assertEqual(
            before["dirty_snapshot"]["path_set_sha256"],
            after["dirty_snapshot"]["path_set_sha256"],
        )
        self.assertEqual(
            before["dirty_snapshot"]["content_set_sha256"],
            after["dirty_snapshot"]["content_set_sha256"],
        )
        self.assertNotEqual(
            before["dirty_snapshot"]["index_state_sha256"],
            after["dirty_snapshot"]["index_state_sha256"],
        )
        before_entry = next(
            item
            for item in before["dirty_snapshot"]["paths"]
            if item["path"] == "staged.txt"
        )
        after_entry = next(
            item
            for item in after["dirty_snapshot"]["paths"]
            if item["path"] == "staged.txt"
        )
        self.assertEqual(before_entry["status"]["xy"], "MM")
        self.assertEqual(after_entry["status"]["xy"], "MM")
        self.assertNotEqual(
            before_entry["index_entries"],
            after_entry["index_entries"],
        )

    def test_capture_rejects_worktree_change_between_hash_passes(self) -> None:
        original_identity = continuation._worktree_identity
        mutated = False

        def mutate_after_first_hash(root: Path, relative: str) -> dict:
            nonlocal mutated
            identity = original_identity(root, relative)
            if relative == "modified.txt" and not mutated:
                mutated = True
                self._write("modified.txt", "after-two!\n")
            return identity

        with mock.patch.object(
            continuation,
            "_worktree_identity",
            side_effect=mutate_after_first_hash,
        ):
            with self.assertRaisesRegex(ValueError, "content changed"):
                self._capture()

    def test_checkpoint_declared_content_hash_must_match_live_controlled_bytes(self) -> None:
        checkpoint_document = json.loads(
            self.checkpoint.read_text(encoding="utf-8")
        )
        checkpoint_document["working_tree_snapshot"]["content_set_sha256"] = (
            "0" * 64
        )
        self.checkpoint.write_text(
            json.dumps(checkpoint_document, sort_keys=True) + "\n",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ValueError, "does not match live bytes"):
            self._capture()

    def test_checkpoint_base_head_may_be_an_ancestor_of_current_head(self) -> None:
        checkpoint_document = json.loads(
            self.checkpoint.read_text(encoding="utf-8")
        )
        base_head = checkpoint_document["working_tree_snapshot"]["base_head"]
        self._git("commit", "--allow-empty", "-m", "fixture descendant")
        descendant_head = self._git("rev-parse", "HEAD").stdout.strip()
        checkpoint_document["session_handoff"]["source_commit_or_snapshot"][
            "current_head"
        ] = descendant_head
        self.checkpoint.write_text(
            json.dumps(checkpoint_document, sort_keys=True) + "\n",
            encoding="utf-8",
        )

        state = self._capture()

        self.assertNotEqual(base_head, descendant_head)
        self.assertEqual(
            state["checkpoint_controlled_working_snapshot"]["base_head"],
            base_head,
        )
        self.assertEqual(
            state["repository"]["head_commit"],
            descendant_head,
        )

    def test_checkpoint_non_ancestor_base_head_is_rejected(self) -> None:
        checkpoint_document = json.loads(
            self.checkpoint.read_text(encoding="utf-8")
        )
        tree = self._git("rev-parse", "HEAD^{tree}").stdout.strip()
        unrelated_head = self._git(
            "commit-tree",
            tree,
            "-m",
            "unrelated fixture commit",
        ).stdout.strip()
        checkpoint_document["working_tree_snapshot"]["base_head"] = (
            unrelated_head
        )
        checkpoint_document["session_handoff"]["source_commit_or_snapshot"][
            "base_commit"
        ] = unrelated_head
        self.checkpoint.write_text(
            json.dumps(checkpoint_document, sort_keys=True) + "\n",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ValueError, "not an ancestor"):
            self._capture()

    def test_checkpoint_handoff_current_head_mismatch_is_rejected(self) -> None:
        checkpoint_document = json.loads(
            self.checkpoint.read_text(encoding="utf-8")
        )
        checkpoint_document["session_handoff"]["source_commit_or_snapshot"][
            "current_head"
        ] = "0" * 40
        self.checkpoint.write_text(
            json.dumps(checkpoint_document, sort_keys=True) + "\n",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ValueError, "handoff current HEAD"):
            self._capture()

    def test_event_transaction_exclusions_are_exact_and_recorded(self) -> None:
        exact_gate_path = (
            self.root
            / "docs/control/execution/goal-gates"
            / self.EVENT_ID
            / "repository-state.json"
        )
        exact_gate_path.parent.mkdir(parents=True, exist_ok=True)
        exact_gate_path.write_text("transaction output\n", encoding="utf-8")
        sibling_event = (
            self.root
            / "docs/control/execution/goal-gates"
            / f"{self.EVENT_ID}-SIBLING"
            / "repository-state.json"
        )
        sibling_event.parent.mkdir(parents=True, exist_ok=True)
        sibling_event.write_text("must remain in scope\n", encoding="utf-8")

        state = self._capture()
        exclusions = state["transaction_exclusions"]
        included_paths = {
            item["path"]
            for item in state["dirty_snapshot"]["paths"]
        }

        self.assertEqual(exclusions["allowed_rule_count"], 2)
        self.assertEqual(
            exclusions["checkpoint_exact_path"],
            continuation.GATE_REPOSITORY_CHECKPOINT_PATH,
        )
        self.assertEqual(
            exclusions["gate_event_exact_prefix"],
            f"docs/control/execution/goal-gates/{self.EVENT_ID}/",
        )
        self.assertIn(
            (
                "docs/control/execution/goal-gates/"
                f"{self.EVENT_ID}-SIBLING/repository-state.json"
            ),
            included_paths,
        )

    def test_checkpoint_and_event_transaction_writes_do_not_change_capture(self) -> None:
        before = self._capture()
        checkpoint_document = json.loads(
            self.checkpoint.read_text(encoding="utf-8")
        )
        checkpoint_document["event_log"] = [
            {"event_type": "GOAL_STARTED", "sequence": 3}
        ]
        self.checkpoint.write_text(
            json.dumps(checkpoint_document, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        gate_directory = (
            self.root
            / "docs/control/execution/goal-gates"
            / self.EVENT_ID
        )
        gate_directory.mkdir(parents=True, exist_ok=True)
        (gate_directory / "repository-state.json").write_text(
            "capture output is transaction-local\n",
            encoding="utf-8",
        )
        (gate_directory / "receipt.json").write_text(
            "receipt is transaction-local\n",
            encoding="utf-8",
        )

        after = self._capture()

        self.assertEqual(before, after)

    def test_gate_event_id_rejects_unsafe_or_broad_values(self) -> None:
        for event_id in (
            "",
            ".",
            "..",
            "../escape",
            "nested/event",
            "-leading-hyphen",
            "contains space",
            "x" * 129,
        ):
            with self.subTest(event_id=event_id):
                with self.assertRaises(ValueError):
                    continuation.validate_gate_event_id(event_id)

    def test_rename_cannot_cross_transaction_exclusion_boundary(self) -> None:
        gate_directory = (
            self.root
            / "docs/control/execution/goal-gates"
            / self.EVENT_ID
        )
        gate_directory.mkdir(parents=True, exist_ok=True)
        self._git(
            "mv",
            "unchanged.txt",
            str(
                gate_directory.relative_to(self.root)
                / "cross-boundary.txt"
            ),
        )

        with self.assertRaisesRegex(ValueError, "crosses"):
            self._capture()

    def test_status_copy_detection_config_is_overridden_deterministically(self) -> None:
        self._git("config", "status.renames", "copies")
        self._write("copy.txt", "after-one!\n")
        self._git("add", "copy.txt")

        state = self._capture()
        entries = {
            item["path"]: item
            for item in state["dirty_snapshot"]["paths"]
        }

        self.assertEqual(
            state["git_status_raw"]["config_overrides"],
            {"status.renames": "true"},
        )
        self.assertIn("copy.txt", entries)
        self.assertIn("modified.txt", entries)

    def test_hidden_index_flags_and_alternate_index_environment_fail_closed(self) -> None:
        self._git("update-index", "--assume-unchanged", "modified.txt")
        with self.assertRaisesRegex(ValueError, "assume-unchanged"):
            self._capture()

        self._git("update-index", "--no-assume-unchanged", "modified.txt")
        self._git("update-index", "--skip-worktree", "modified.txt")
        with self.assertRaisesRegex(ValueError, "skip-worktree"):
            self._capture()

        self._git("update-index", "--no-skip-worktree", "modified.txt")
        with mock.patch.dict(
            os.environ,
            {"GIT_INDEX_FILE": str(self.root / "alternate-index")},
        ):
            with self.assertRaisesRegex(ValueError, "environment overrides"):
                self._capture()

    def test_capture_and_cli_output_are_deterministic_canonical_json(self) -> None:
        first = self._capture()
        second = self._capture()
        self.assertEqual(first, second)
        self.assertEqual(
            continuation.canonical_json_bytes(first),
            continuation.canonical_json_bytes(second),
        )

        command = [
            sys.executable,
            str(ROOT / "scripts/check_walksafe_project_continuation_v2_3.py"),
            "--root",
            str(self.root),
            "--checkpoint",
            str(self.checkpoint),
            "--print-gate-repository-state",
            "--gate-event-id",
            self.EVENT_ID,
        ]
        completed = subprocess.run(
            command,
            check=False,
            capture_output=True,
        )

        self.assertEqual(completed.returncode, 0, completed.stderr.decode())
        self.assertEqual(completed.stderr, b"")
        self.assertEqual(completed.stdout.count(b"\n"), 1)
        self.assertEqual(
            completed.stdout,
            continuation.canonical_json_bytes(first) + b"\n",
        )

    def test_output_contains_hashes_but_no_file_or_symlink_raw_content(self) -> None:
        secret_target = "SUPER-SECRET-SYMLINK-TARGET"
        os.symlink(secret_target, self.root / "untracked-link")
        state = self._capture()
        encoded = continuation.canonical_json_bytes(state)
        entries = {
            item["path"]: item
            for item in state["dirty_snapshot"]["paths"]
        }

        self.assertNotIn(b"SUPER-SECRET-RAW-CONTENT", encoded)
        self.assertNotIn(secret_target.encode("utf-8"), encoded)
        self.assertEqual(entries["untracked-link"]["worktree"]["type"], "SYMLINK")
        self.assertEqual(
            entries["untracked-link"]["worktree"]["symlink_target_sha256"],
            hashlib.sha256(secret_target.encode("utf-8")).hexdigest(),
        )

    def test_dirfd_traversal_rejects_symlink_parent_and_cannot_escape_on_swap(self) -> None:
        with tempfile.TemporaryDirectory() as outside_directory:
            outside = Path(outside_directory)
            (outside / "payload.txt").write_text(
                "OUTSIDE-SECRET\n",
                encoding="utf-8",
            )
            (self.root / "linked-parent").symlink_to(
                outside,
                target_is_directory=True,
            )
            with self.assertRaisesRegex(ValueError, "unsafe parent"):
                continuation._worktree_identity(
                    self.root,
                    "linked-parent/payload.txt",
                )

            original_parent = self.root / "race-parent"
            moved_parent = self.root / "race-parent-original"
            original_parent.mkdir()
            (original_parent / "payload.txt").write_text(
                "INSIDE-CONTENT\n",
                encoding="utf-8",
            )
            original_open = os.open
            swapped = False

            def swap_parent_before_final_open(path, flags, *args, **kwargs):
                nonlocal swapped
                if (
                    path == "payload.txt"
                    and kwargs.get("dir_fd") is not None
                    and not swapped
                ):
                    swapped = True
                    original_parent.rename(moved_parent)
                    original_parent.symlink_to(
                        outside,
                        target_is_directory=True,
                    )
                return original_open(path, flags, *args, **kwargs)

            try:
                with mock.patch.object(
                    continuation.os,
                    "open",
                    side_effect=swap_parent_before_final_open,
                ):
                    identity = continuation._worktree_identity(
                        self.root,
                        "race-parent/payload.txt",
                    )
            finally:
                if original_parent.is_symlink():
                    original_parent.unlink()
                if moved_parent.exists():
                    moved_parent.rename(original_parent)

        self.assertTrue(swapped)
        self.assertEqual(
            identity["sha256"],
            hashlib.sha256(b"INSIDE-CONTENT\n").hexdigest(),
        )
        self.assertNotEqual(
            identity["sha256"],
            hashlib.sha256(b"OUTSIDE-SECRET\n").hexdigest(),
        )

    def test_unsafe_submodule_and_special_file_fail_closed(self) -> None:
        with self.assertRaises(ValueError):
            continuation.parse_git_status_porcelain_v2(b"? ../escape\0")
        submodule_record = (
            b"1 .M S... 160000 160000 160000 "
            + (b"0" * 40)
            + b" "
            + (b"0" * 40)
            + b" submodule\0"
        )
        with self.assertRaises(ValueError):
            continuation.parse_git_status_porcelain_v2(submodule_record)

        special_path = self.root / "untracked-fifo"
        os.mkfifo(special_path)
        try:
            with self.assertRaisesRegex(ValueError, "special file"):
                self._capture()
        finally:
            special_path.unlink()

    def _capture(self) -> dict:
        return continuation.capture_gate_repository_state(
            self.root,
            self.checkpoint,
            self.EVENT_ID,
        )

    def _write(self, relative: str, content: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def _git(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        completed = subprocess.run(
            ["git", *arguments],
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
        )
        if completed.returncode != 0:
            self.fail(
                f"git {' '.join(arguments)} failed: "
                f"{completed.stderr.strip()}"
            )
        return completed


if __name__ == "__main__":
    unittest.main()
