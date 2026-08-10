import json
import shutil
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path

from scripts import check_walksafe_goal_package as goals


ROOT = Path(__file__).resolve().parents[1]
CHECKPOINT_RELATIVE = Path("docs/control/walksafe-project-continuation-checkpoint.json")


@unittest.skip(
    "superseded, never-activated A-D package; v2 graph tests are authoritative"
)
class WalkSafeGoalPackageTest(unittest.TestCase):
    def test_current_goal_package_is_valid(self) -> None:
        self.assertEqual(goals.validate(ROOT), [])

    def test_all_68_policy_and_gap_units_are_covered_once(self) -> None:
        checkpoint = goals.load_json(ROOT / CHECKPOINT_RELATIVE)
        goal_paths = checkpoint["goal_execution"]["goal_document_paths"]
        nodes = {}
        for relative in goal_paths:
            metadata, _ = goals.parse_goal(ROOT / relative)
            nodes[metadata["goal_id"]] = metadata
        binding = goals.canonical_binding_map(checkpoint)["IMPLEMENTATION_BACKLOG"]
        backlog = goals.load_json(ROOT / binding["path"])
        mapping_errors, policy_gap_mapping = goals.load_policy_gap_mapping(
            ROOT,
            goals.canonical_binding_map(checkpoint),
        )

        self.assertEqual(mapping_errors, [])
        self.assertEqual(
            goals.validate_epics(nodes, backlog, policy_gap_mapping),
            [],
        )
        policies = [
            policy
            for node in nodes.values()
            if node["goal_kind"] == "EPIC"
            for policy in node["source_policy_ids"]
        ]
        gaps = [
            gap_id
            for node in nodes.values()
            if node["goal_kind"] == "EPIC"
            for gap_id in node["gap_ids"]
        ]
        self.assertEqual(len(policies), 68)
        self.assertEqual(len(set(policies)), 68)
        self.assertEqual(len(gaps), 68)
        self.assertEqual(len(set(gaps)), 68)

    def test_missing_goal_is_rejected_even_after_manifest_rehash(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            missing = (
                "docs/control/goals/walksafe-completion-v1/"
                "epics/epic-05-object-detection-safety.md"
            )
            (root / missing).unlink()
            state = checkpoint["goal_execution"]
            state["managed_goal_paths"].remove(missing)
            state["goal_document_paths"].remove(missing)
            state["managed_goal_path_count"] -= 1
            state["goal_document_count"] -= 1
            state["status_by_goal"].pop("WS-GOAL-A-EPIC-05")
            self.rehash(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertTrue(any("expected EPIC goal is missing" in error for error in errors))
        self.assertTrue(any("target is missing" in error for error in errors))

    def test_policy_duplication_is_rejected_after_rehash(self) -> None:
        with self.fixture_root() as root:
            relative = (
                "docs/control/goals/walksafe-completion-v1/"
                "epics/epic-03-account-admin-security.md"
            )
            path = root / relative
            text = path.read_text(encoding="utf-8")
            text = text.replace(
                'source_policy_ids = ["FP-047",',
                'source_policy_ids = ["FP-018",',
                1,
            )
            path.write_text(text, encoding="utf-8")
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.rehash(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertTrue(any("ordered policies differ" in error for error in errors))
        self.assertTrue(any("not covered exactly once" in error for error in errors))

    def test_dependency_cycle_is_rejected_after_rehash(self) -> None:
        with self.fixture_root() as root:
            relative = (
                "docs/control/goals/walksafe-completion-v1/"
                "10-phase-a-implementation-readiness.md"
            )
            path = root / relative
            text = path.read_text(encoding="utf-8")
            text = text.replace(
                "dependencies = []",
                'dependencies = ["WS-GOAL-PHASE-D"]',
                1,
            )
            path.write_text(text, encoding="utf-8")
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.rehash(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertTrue(any("dependency graph contains a cycle" in error for error in errors))
        self.assertTrue(any("phase A: dependencies drifted" in error for error in errors))

    def test_current_leaf_and_backlog_drift_are_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            state["current_leaf_goal_id"] = "WS-GOAL-A-EPIC-03"
            state["current_work_item_id"] = "EPIC-03-FAKE"
            checkpoint["current_work"]["work_item_id"] = "EPIC-03-FAKE"
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertTrue(any("current leaf must be a materialized Work Item" in error for error in errors))
        self.assertTrue(any("current leaf Goal ID/path mapping" in error for error in errors))
        self.assertTrue(any("goal current work item differs" in error for error in errors))

    def test_completion_without_evidence_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            state["status_by_goal"]["WS-GOAL-A-EPIC-02"] = "COMPLETE_AT_TARGET"
            state["completion_evidence_by_goal"]["WS-GOAL-A-EPIC-02"] = []
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertTrue(any("completed parent has an incomplete child" in error for error in errors))
        self.assertTrue(any("latest backlog does not prove target completion" in error for error in errors))
        self.assertTrue(any("completion evidence references are missing" in error for error in errors))

    def test_formal_test_gate_and_release_promotion_are_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            checkpoint["approved_state"]["formal_test_not_run_count"] = 0
            checkpoint["approved_state"]["remaining_gate_count"] = 0
            checkpoint["approved_state"]["remaining_gates_waived"] = True
            checkpoint["approved_state"]["release_status"] = "ELIGIBLE"
            checkpoint["verification_boundary"]["actual_device_test_status"] = "PASS"
            checkpoint["verification_boundary"]["release_eligible"] = True
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertTrue(any("Phase A cannot promote formal test" in error for error in errors))
        self.assertTrue(any("remaining gates must not be waived" in error for error in errors))
        self.assertTrue(any("Phase A cannot close" in error for error in errors))
        self.assertTrue(any("Phase A cannot promote actual-device" in error for error in errors))
        self.assertTrue(any("Phase A cannot promote release eligibility" in error for error in errors))

    def test_active_transition_keeps_one_leaf_and_its_ancestors_active(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            state["activation_status"] = "ACTIVE"
            state["package_status"] = "ACTIVE"
            state["goal_status"] = "IN_PROGRESS"
            for goal_id in (
                "WS-GOAL-WALKSAFE-COMPLETION-V1",
                "WS-GOAL-PHASE-A",
                "WS-GOAL-A-EPIC-02",
                "WS-GOAL-A-EPIC-02-FP-018-R001",
            ):
                state["status_by_goal"][goal_id] = "IN_PROGRESS"
            self.append_event(
                checkpoint,
                root=root,
                event_type="PACKAGE_ACTIVATED",
                current_leaf_goal_id=state["current_leaf_goal_id"],
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_waiting_user_state_with_structured_question_is_valid(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            self.activate_current_leaf(checkpoint, root)
            state["status_by_goal"][state["current_leaf_goal_id"]] = "AWAITING_USER"
            state["goal_status"] = "AWAITING_USER"
            blocker = self.make_blocker(
                blocker_id="TEST-BLOCKER-001",
                owner="USER",
                condition_code="AUTHORITY_EXPANSION_REQUIRED",
                blocks_goal_id=state["current_leaf_goal_id"],
                return_leaf_goal_id=state["current_leaf_goal_id"],
                prompt="실제 외부 계정 사용 권한이 필요합니다.",
            )
            state["blockers_by_goal"] = {
                state["current_leaf_goal_id"]: [blocker]
            }
            state["pending_questions"] = [
                blocker
            ]
            state["open_question_count"] = 1
            self.append_event(
                checkpoint,
                root=root,
                event_type="BLOCKER_RECORDED",
                current_leaf_goal_id=state["current_leaf_goal_id"],
                from_status="IN_PROGRESS",
                to_status="AWAITING_USER",
                extra={"blocker_ids": ["TEST-BLOCKER-001"]},
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_authority_and_external_boundaries_cannot_be_widened(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            state["standing_execution_authority"] = ["ANYTHING"]
            state["external_action_required_for"] = []
            state["package_status"] = "COMPLETE_AT_TARGET"
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertTrue(any("standing execution authority drifted" in error for error in errors))
        self.assertTrue(any("external action boundary drifted" in error for error in errors))
        self.assertTrue(any("package status is inconsistent" in error for error in errors))

    def test_fake_completion_evidence_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            state["completion_evidence_by_goal"]["WS-GOAL-A-EPIC-01"] = [
                "FAKE-EVIDENCE"
            ]
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertTrue(any("unresolved evidence reference: FAKE-EVIDENCE" in error for error in errors))

    def test_support_documents_are_required_even_after_manifest_rehash(self) -> None:
        for missing in sorted(goals.SUPPORT_PATHS):
            with self.subTest(missing=missing), self.fixture_root() as root:
                checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
                (root / missing).unlink()
                state = checkpoint["goal_execution"]
                state["managed_goal_paths"].remove(missing)
                state["managed_goal_path_count"] -= 1
                self.rehash(checkpoint, root)
                self.write_checkpoint(checkpoint, root)

                errors = goals.validate(root, check_continuation=False)

            self.assertTrue(
                any("required support document is missing" in error for error in errors)
            )

    def test_invalid_dependency_and_history_types_fail_without_crashing(self) -> None:
        with self.fixture_root() as root:
            relative = (
                "docs/control/goals/walksafe-completion-v1/"
                "10-phase-a-implementation-readiness.md"
            )
            path = root / relative
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "dependencies = []",
                    "dependencies = 1",
                    1,
                ),
                encoding="utf-8",
            )
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            checkpoint["goal_execution"]["transition_history"] = [1]
            self.rehash(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertTrue(any("dependencies must be a string list" in error for error in errors))
        self.assertTrue(any("history entries must be objects" in error for error in errors))

    def test_epic_creation_status_is_not_forced_to_latest_backlog_status(self) -> None:
        checkpoint = goals.load_json(ROOT / CHECKPOINT_RELATIVE)
        nodes = {}
        for relative in checkpoint["goal_execution"]["goal_document_paths"]:
            metadata, _ = goals.parse_goal(ROOT / relative)
            nodes[metadata["goal_id"]] = metadata
        binding = goals.canonical_binding_map(checkpoint)["IMPLEMENTATION_BACKLOG"]
        backlog = goals.load_json(ROOT / binding["path"])
        for epic in backlog["epics"]:
            if epic["epic_id"] == "EPIC-02":
                epic["current_status"] = "FUTURE_SUCCESSOR_STATUS"
        mapping_errors, policy_gap_mapping = goals.load_policy_gap_mapping(
            ROOT,
            goals.canonical_binding_map(checkpoint),
        )

        errors = [
            *mapping_errors,
            *goals.validate_epics(
                nodes,
                backlog,
                policy_gap_mapping,
            ),
        ]

        self.assertEqual(errors, [])

    def test_successor_work_item_transition_is_dynamic(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            old_goal_id = state["current_leaf_goal_id"]
            old_goal_path = state["current_leaf_goal_path"]
            old_source_path = goals.canonical_binding_map(checkpoint)[
                "IMPLEMENTATION_BACKLOG"
            ]["path"]

            backlog_path = (
                "docs/control/audits/"
                "walksafe-implementation-remediation-backlog-20260723-r009.json"
            )
            backlog_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-009"
            backlog = goals.load_json(root / old_source_path)
            backlog["metadata"]["backlog_id"] = backlog_id
            backlog["metadata"]["version"] = "0.9.0"
            backlog["metadata"]["predecessor_backlog_id"] = (
                "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-008"
            )
            backlog["next_single_action"] = {
                "epic_id": "EPIC-02",
                "work_item_id": "EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE",
                "source_policy_id": "NPC-PERMISSION-SESSION-LIFECYCLE",
                "gap_id": "GAP-006",
                "status": "PLANNED_NEXT_WITHIN_EPIC",
                "action": "권한·로그인·동의 상태를 분리한다.",
            }
            self.write_json(root / backlog_path, backlog)
            self.update_binding(
                checkpoint,
                root=root,
                role="IMPLEMENTATION_BACKLOG",
                path=backlog_path,
                document_id=backlog_id,
            )

            new_goal_id = (
                "WS-GOAL-A-EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE-R001"
            )
            new_goal_path = self.materialize_work_item(
                root,
                goal_id=new_goal_id,
                relative_path=(
                    "docs/control/goals/walksafe-completion-v1/work-items/a/"
                    "epic-02-npc-permission-session-lifecycle-r001.md"
                ),
                phase_id="A",
                parent_goal_id="WS-GOAL-A-EPIC-02",
                sequence=8,
                work_item_id="EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE",
                source_policy_ids=["NPC-PERMISSION-SESSION-LIFECYCLE"],
                gap_ids=["GAP-006"],
                dependencies=["WS-GOAL-A-EPIC-01"],
                materialized_from_role="IMPLEMENTATION_BACKLOG",
                materialized_from_path=backlog_path,
                materialized_from_document_id=backlog_id,
                predecessor_goal_id=old_goal_id,
                predecessor_goal_path=old_goal_path,
            )

            self.activate_current_leaf(checkpoint, root)
            completion_ref = self.create_work_item_completion_receipt(
                checkpoint,
                root,
                goal_id=old_goal_id,
            )
            state["status_by_goal"][old_goal_id] = "COMPLETE_AT_TARGET"
            state["completion_evidence_by_goal"][old_goal_id] = [
                completion_ref
            ]
            state["status_by_goal"][new_goal_id] = "PLANNED"
            state["materialized_child_goal_ids_by_parent"][
                "WS-GOAL-A-EPIC-02"
            ].append(new_goal_id)
            state["goal_status"] = "COMPLETE_AT_TARGET"
            self.append_event(
                checkpoint,
                root=root,
                event_type="GOAL_COMPLETED",
                current_leaf_goal_id=old_goal_id,
                from_status="IN_PROGRESS",
                to_status="COMPLETE_AT_TARGET",
                evidence_refs=[completion_ref],
                extra={
                    "completion_receipt_binding": (
                        self.completion_binding_snapshot(
                            checkpoint,
                            completion_ref,
                        )
                    )
                },
            )

            state["status_by_goal"][new_goal_id] = "IN_PROGRESS"
            state["current_leaf_goal_id"] = new_goal_id
            state["current_leaf_goal_path"] = new_goal_path
            state["current_work_item_id"] = (
                "EPIC-02-NPC-PERMISSION-SESSION-LIFECYCLE"
            )
            state["goal_status"] = "IN_PROGRESS"
            checkpoint["current_work"]["work_item_id"] = state["current_work_item_id"]
            self.append_event(
                checkpoint,
                root=root,
                event_type="GOAL_TRANSITION",
                current_leaf_goal_id=new_goal_id,
                from_status="COMPLETE_AT_TARGET",
                to_status="IN_PROGRESS",
            )
            self.refresh_goal_manifest(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_work_item_source_plan_alone_cannot_complete_work(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            self.activate_current_leaf(checkpoint, root)
            leaf_id = state["current_leaf_goal_id"]
            source_ref = f"WORK_ITEM_SOURCE::{leaf_id}"
            state["status_by_goal"][leaf_id] = "COMPLETE_AT_TARGET"
            state["completion_evidence_by_goal"][leaf_id] = [source_ref]
            state["goal_status"] = "COMPLETE_AT_TARGET"
            self.append_event(
                checkpoint,
                root=root,
                event_type="GOAL_COMPLETED",
                current_leaf_goal_id=leaf_id,
                from_status="IN_PROGRESS",
                to_status="COMPLETE_AT_TARGET",
                evidence_refs=[source_ref],
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "source plan alone", "completion")

    def test_work_item_policy_gap_pair_must_follow_gap_assessment(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            nodes = {
                goal_id: goals.parse_goal(root / relative)[0]
                for goal_id, relative in self.goal_path_by_id(root).items()
            }
            goal_id = checkpoint["goal_execution"]["current_leaf_goal_id"]
            nodes[goal_id]["gap_ids"] = ["GAP-013"]
            mapping_errors, policy_gap_mapping = (
                goals.load_policy_gap_mapping(
                    root,
                    goals.canonical_binding_map(checkpoint),
                )
            )
            self.assertEqual(mapping_errors, [])

            errors = goals.validate_work_item_lineage(
                root,
                nodes,
                self.goal_path_by_id(root),
                goals.canonical_binding_map(checkpoint),
                policy_gap_mapping,
            )

        self.assert_has_error(
            errors,
            "policy/Gap pair",
            "IMPLEMENTATION_GAP",
        )

    def test_work_item_cannot_claim_phase_level_completion_target(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            goal_paths = self.goal_path_by_id(root)
            nodes = {
                goal_id: goals.parse_goal(root / relative)[0]
                for goal_id, relative in goal_paths.items()
            }
            goal_id = checkpoint["goal_execution"]["current_leaf_goal_id"]
            nodes[goal_id]["target_completion_level"] = (
                "PROJECT_ACCEPTED_AND_HANDOVER_OR_CLOSURE_COMPLETE"
            )
            mapping_errors, policy_gap_mapping = (
                goals.load_policy_gap_mapping(
                    root,
                    goals.canonical_binding_map(checkpoint),
                )
            )
            self.assertEqual(mapping_errors, [])

            errors = goals.validate_work_item_lineage(
                root,
                nodes,
                goal_paths,
                goals.canonical_binding_map(checkpoint),
                policy_gap_mapping,
            )

        self.assert_has_error(errors, "target completion level", "phase")

    def test_work_item_receipt_must_bind_latest_execution_start(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.activate_current_leaf(checkpoint, root)
            self.complete_active_work_item(checkpoint, root)
            goal_id = checkpoint["goal_execution"]["current_leaf_goal_id"]
            role = f"WORK_ITEM_COMPLETION::{goal_id}"
            binding = goals.canonical_binding_map(checkpoint)[role]
            receipt = goals.load_json(root / binding["path"])
            receipt["execution_start_event_sha256"] = "0" * 64
            self.write_json(root / binding["path"], receipt)
            binding["file_sha256"] = goals.sha256_file(
                root / binding["path"]
            )
            completion_event = checkpoint["goal_execution"][
                "transition_history"
            ][-1]
            completion_event["completion_receipt_binding"] = (
                self.completion_binding_snapshot(checkpoint, role)
            )
            self.rehash_event_chain(checkpoint)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "latest execution start event")

    def test_work_item_execution_window_cannot_end_after_review(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.activate_current_leaf(checkpoint, root)
            self.complete_active_work_item(checkpoint, root)
            goal_id = checkpoint["goal_execution"]["current_leaf_goal_id"]
            role = f"WORK_ITEM_COMPLETION::{goal_id}"
            binding = goals.canonical_binding_map(checkpoint)[role]
            receipt = goals.load_json(root / binding["path"])
            receipt["execution_window"]["ended_at"] = (
                "2030-07-23T12:00:00+09:00"
            )
            self.write_json(root / binding["path"], receipt)
            binding["file_sha256"] = goals.sha256_file(
                root / binding["path"]
            )
            completion_event = checkpoint["goal_execution"][
                "transition_history"
            ][-1]
            completion_event["completion_receipt_binding"] = (
                self.completion_binding_snapshot(checkpoint, role)
            )
            self.rehash_event_chain(checkpoint)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "execution receipt completion/review/generation chronology",
        )

    def test_goal_completed_event_must_snapshot_completion_receipt(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.activate_current_leaf(checkpoint, root)
            self.complete_active_work_item(checkpoint, root)
            completion_event = checkpoint["goal_execution"][
                "transition_history"
            ][-1]
            completion_event.pop("completion_receipt_binding")
            self.rehash_event_chain(checkpoint)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "completion receipt binding snapshot",
        )

    def test_transition_event_cannot_exceed_reviewed_validation_cutoff(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            event = checkpoint["goal_execution"]["transition_history"][0]
            event["occurred_on"] = "2026-07-24"
            event["occurred_at"] = "2026-07-24T00:00:00+09:00"
            self.rehash_event_chain(checkpoint)
            goals.EXPECTED_INITIAL_TRANSITION_EVENT_SHA256 = event[
                "event_sha256"
            ]
            goals.EXPECTED_TRANSITION_HISTORY_HEAD_SHA256 = event[
                "event_sha256"
            ]
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "exceeds the reviewed validation cutoff",
        )

    def test_validation_cutoff_must_match_checker_trust_anchor(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            checkpoint["goal_execution"]["validation_cutoff_at"] = (
                "2026-07-24T23:59:59+09:00"
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "validation cutoff differs",
            "checker trust anchor",
        )

    def test_work_item_result_evidence_payload_must_match_goal(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.activate_current_leaf(checkpoint, root)
            self.complete_active_work_item(checkpoint, root)
            goal_id = checkpoint["goal_execution"]["current_leaf_goal_id"]
            role = f"WORK_ITEM_COMPLETION::{goal_id}"
            binding = goals.canonical_binding_map(checkpoint)[role]
            receipt = goals.load_json(root / binding["path"])
            result_item = receipt["result_evidence"][0]
            payload = goals.load_json(root / result_item["path"])
            payload["goal_id"] = "WS-GOAL-UNRELATED"
            self.write_json(root / result_item["path"], payload)
            result_item["sha256"] = goals.sha256_file(
                root / result_item["path"]
            )
            self.write_json(root / binding["path"], receipt)
            binding["file_sha256"] = goals.sha256_file(
                root / binding["path"]
            )
            completion_event = checkpoint["goal_execution"][
                "transition_history"
            ][-1]
            completion_event["completion_receipt_binding"] = (
                self.completion_binding_snapshot(checkpoint, role)
            )
            self.rehash_event_chain(checkpoint)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "typed payload differs")

    def test_phase_a_to_b_transition_is_valid(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)

            self.transition_to_phase_b(checkpoint, root)
            self.write_checkpoint(checkpoint, root)
            errors = goals.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_phase_b_to_c_transition_with_verified_evidence_is_valid(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)

            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            self.write_checkpoint(checkpoint, root)
            errors = goals.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_phase_b_completion_without_release_receipt_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            checkpoint["canonical_bindings"] = [
                binding
                for binding in checkpoint["canonical_bindings"]
                if binding["role"] != "RELEASE_ELIGIBILITY_APPROVAL"
            ]
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertTrue(
            any(
                "required canonical evidence binding is missing: "
                "RELEASE_ELIGIBILITY_APPROVAL"
                in error
                for error in errors
            )
        )

    def test_phase_b_completion_event_must_bind_formal_receipts(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            state = checkpoint["goal_execution"]
            completion_event = next(
                event
                for event in reversed(state["transition_history"])
                if event["event_type"] == "GOAL_COMPLETED"
                and event["status_changes"].get(goals.EXPECTED_PHASE_IDS["B"])
                == "COMPLETE_AT_TARGET"
            )
            leaf_id = completion_event["current_leaf_goal_id"]
            work_item_role = f"WORK_ITEM_COMPLETION::{leaf_id}"
            state["completion_evidence_by_goal"][
                goals.EXPECTED_PHASE_IDS["B"]
            ] = ["IMPLEMENTATION_BACKLOG"]
            state["completion_evidence_by_goal"][
                goals.EXPECTED_EPIC_GOAL_IDS["EPIC-12"]
            ] = ["IMPLEMENTATION_BACKLOG"]
            state["completion_evidence_by_goal"][leaf_id] = [
                work_item_role,
                "IMPLEMENTATION_BACKLOG",
            ]
            completion_event["evidence_refs"] = [
                work_item_role,
                "IMPLEMENTATION_BACKLOG",
            ]
            self.rehash_event_chain(checkpoint)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "completed Phase B completion evidence",
            "formal receipt roles",
        )
        self.assert_has_error(
            errors,
            "completed EPIC-12 completion evidence",
            "formal receipt roles",
        )

    def test_phase_b_and_epic12_reject_extra_completion_role(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            state = checkpoint["goal_execution"]
            completion_event = next(
                event
                for event in reversed(state["transition_history"])
                if event["event_type"] == "GOAL_COMPLETED"
                and event["status_changes"].get(
                    goals.EXPECTED_PHASE_IDS["B"]
                )
                == "COMPLETE_AT_TARGET"
            )
            for goal_id in (
                goals.EXPECTED_PHASE_IDS["B"],
                goals.EXPECTED_EPIC_GOAL_IDS["EPIC-12"],
            ):
                state["completion_evidence_by_goal"][goal_id].append(
                    "PLANNED_TEST_CASES"
                )
            completion_event["evidence_refs"].append(
                "PLANNED_TEST_CASES"
            )
            completion_event["evidence_refs"].sort()
            self.rehash_event_chain(checkpoint)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "completed Phase B completion evidence",
            "exact formal receipt roles",
        )
        self.assert_has_error(
            errors,
            "completed EPIC-12 completion evidence",
            "exact formal receipt roles",
        )

    def test_minimal_three_field_phase_b_receipts_are_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(
                checkpoint,
                root,
                structured_receipts=False,
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "Phase B", "evidence")

    def test_structured_phase_b_real_evidence_receipts_are_valid(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_authority_roster_must_predate_receipt_authority_use(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            bindings = goals.canonical_binding_map(checkpoint)
            roster_binding = bindings["AUTHORITY_ROSTER"]
            roster = goals.load_json(root / roster_binding["path"])
            roster["approved_at"] = "2026-07-24T09:00:00+09:00"
            self.write_json(root / roster_binding["path"], roster)
            roster_binding["file_sha256"] = goals.sha256_file(
                root / roster_binding["path"]
            )
            receipt_binding = bindings["FORMAL_TEST_REPORT"]
            receipt = goals.load_json(root / receipt_binding["path"])
            receipt["authority_roster_sha256"] = roster_binding[
                "file_sha256"
            ]

            errors = goals.validate_receipt_authority_roster_binding(
                root,
                receipt,
                bindings,
            )

        self.assert_has_error(
            errors,
            "roster approval",
            "postdates",
            "authority use",
        )

    def test_phase_b_receipt_without_external_attestation_anchor_is_rejected(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            role = next(iter(goals.REQUIRED_PHASE_B_EVIDENCE_STATUS))
            goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE.pop(role)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "external authority attestation trust anchor",
            role,
        )

    def test_phase_b_receipt_bytes_must_match_external_attestation_anchor(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            role = next(iter(goals.REQUIRED_PHASE_B_EVIDENCE_STATUS))
            binding = goals.canonical_binding_map(checkpoint)[role]
            receipt = goals.load_json(root / binding["path"])
            receipt["unattested_extension"] = True
            self.write_json(root / binding["path"], receipt)
            binding["file_sha256"] = goals.sha256_file(root / binding["path"])
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "differs from external authority attestation anchor",
            role,
        )

    def test_phase_b_receipt_must_be_final_before_goal_completion(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            role = "RELEASE_ELIGIBILITY_APPROVAL"
            binding = goals.canonical_binding_map(checkpoint)[role]
            receipt = goals.load_json(root / binding["path"])
            receipt["generated_at"] = "2026-07-24T12:20:00+09:00"
            self.write_json(root / binding["path"], receipt)
            binding["file_sha256"] = goals.sha256_file(
                root / binding["path"]
            )
            goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE[
                role
            ] = binding["file_sha256"]
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            role,
            "generated_at",
            "postdates GOAL_COMPLETED",
        )

    def test_formal_report_binds_the_executed_test_plan_snapshot(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            binding = goals.canonical_binding_map(checkpoint)[
                "PLANNED_TEST_CASES"
            ]
            planned = goals.load_json(root / binding["path"])
            planned["metadata"]["version"] = "0.1.1"
            self.write_json(root / binding["path"], planned)
            binding["file_sha256"] = goals.sha256_file(
                root / binding["path"]
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "formal report",
            "planned test case binding differs",
        )

    def test_formal_execution_requires_preexisting_attested_plan_approval(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            bindings = goals.canonical_binding_map(checkpoint)

            planned_binding = bindings["PLANNED_TEST_CASES"]
            planned = goals.load_json(root / planned_binding["path"])
            planned["metadata"]["approved_at"] = (
                "2026-07-23T10:30:00+09:00"
            )
            self.write_json(root / planned_binding["path"], planned)
            planned_binding["file_sha256"] = goals.sha256_file(
                root / planned_binding["path"]
            )

            snapshot = {
                "role": "PLANNED_TEST_CASES",
                "document_id": planned_binding["document_id"],
                "path": planned_binding["path"],
                "file_sha256": planned_binding["file_sha256"],
                "version": planned["metadata"]["version"],
                "test_case_id_set_sha256": goals.string_set_sha256(
                    [
                        row["test_case_id"]
                        for row in planned["test_cases"]
                    ]
                ),
            }
            plan_role = "TEST_PLAN_APPROVAL_RECEIPT"
            plan_binding = bindings[plan_role]
            plan_receipt = goals.load_json(root / plan_binding["path"])
            plan_receipt["planned_test_cases_binding"] = snapshot
            plan_receipt["execution_window"] = {
                "started_at": "2026-07-23T10:10:00+09:00",
                "ended_at": "2026-07-23T10:20:00+09:00",
            }
            plan_receipt["raw_evidence"][0]["collected_at"] = (
                "2026-07-23T10:15:00+09:00"
            )
            plan_receipt["reviewer"]["decided_at"] = (
                "2026-07-23T10:25:00+09:00"
            )
            plan_receipt["approver"]["decided_at"] = (
                "2026-07-23T10:30:00+09:00"
            )
            plan_receipt["plan_approved_at"] = (
                "2026-07-23T10:30:00+09:00"
            )
            plan_receipt["generated_at"] = (
                "2026-07-23T10:40:00+09:00"
            )
            self.write_json(root / plan_binding["path"], plan_receipt)
            plan_binding["file_sha256"] = goals.sha256_file(
                root / plan_binding["path"]
            )
            goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE[
                plan_role
            ] = plan_binding["file_sha256"]

            formal_role = "FORMAL_TEST_REPORT"
            formal_binding = bindings[formal_role]
            formal = goals.load_json(root / formal_binding["path"])
            formal["planned_test_cases_binding"] = snapshot
            self.write_json(root / formal_binding["path"], formal)
            formal_binding["file_sha256"] = goals.sha256_file(
                root / formal_binding["path"]
            )
            goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE[
                formal_role
            ] = formal_binding["file_sha256"]
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "execution predates immutable test plan approval",
            "FORMAL_TEST_REPORT",
        )

    def test_phase_b_boundary_cannot_advance_before_receipts_complete(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            approved = checkpoint["approved_state"]
            boundary = checkpoint["verification_boundary"]
            approved["formal_test_not_run_count"] = 0
            approved["remaining_gate_count"] = 0
            boundary["formal_test_not_run_count"] = 0
            boundary["remaining_gate_ids"] = []
            boundary["all_remaining_gate_status"] = "CLOSED"
            boundary["actual_device_test_status"] = "PASS"
            boundary["approved_production_profile_count"] = 1
            boundary["formal_test_pass_claimed"] = True
            checkpoint["goal_execution"]["verification_evidence_refs"] = [
                "PLANNED_TEST_CASES"
            ]
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "formal test boundary",
            "before Phase B completes",
        )
        self.assert_has_error(
            errors,
            "actual-device status",
            "before Phase B completes",
        )

    def test_not_applicable_decision_cannot_postdate_formal_report(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            role = "FORMAL_TEST_REPORT"
            binding = goals.canonical_binding_map(checkpoint)[role]
            receipt = goals.load_json(root / binding["path"])
            case = receipt["test_cases"][0]
            case["applicability"] = "NOT_APPLICABLE"
            case["status"] = "NOT_APPLICABLE"
            case["applicability_reason"] = "시험 대상 기능이 없음"
            case["applicability_decision"] = {
                "decision": "APPROVED_NOT_APPLICABLE",
                "approver_id": receipt["approver"]["id"],
                "decided_at": "2026-07-24T12:00:00+09:00",
            }
            receipt["test_summary"]["passed"] = 278
            receipt["test_summary"]["not_applicable"] = 1
            self.write_json(root / binding["path"], receipt)
            binding["file_sha256"] = goals.sha256_file(
                root / binding["path"]
            )
            goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE[
                role
            ] = binding["file_sha256"]
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "applicability decision chronology differs",
        )

    def test_phase_c_to_d_with_bound_delivery_receipt_is_valid(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            self.transition_to_phase_d(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_phase_c_receipt_cannot_start_before_phase_c_event(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            self.transition_to_phase_d(checkpoint, root)
            role = "PHASE_C_TECHNICAL_DELIVERY_RECEIPT"
            binding = goals.canonical_binding_map(checkpoint)[role]
            receipt = goals.load_json(root / binding["path"])
            receipt["execution_window"]["started_at"] = (
                "2026-07-23T12:00:00+09:00"
            )
            self.write_json(root / binding["path"], receipt)
            binding["file_sha256"] = goals.sha256_file(
                root / binding["path"]
            )
            goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE[
                role
            ] = binding["file_sha256"]
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            role,
            "execution predates its Phase start event",
        )

    def test_phase_c_canary_must_precede_smoke_and_delivery(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            self.transition_to_phase_d(checkpoint, root)
            role = "PHASE_C_TECHNICAL_DELIVERY_RECEIPT"
            binding = goals.canonical_binding_map(checkpoint)[role]
            receipt = goals.load_json(root / binding["path"])
            receipt["required_checks"]["canary"]["executed_at"] = (
                "2026-07-23T13:30:00+09:00"
            )
            self.write_json(root / binding["path"], receipt)
            binding["file_sha256"] = goals.sha256_file(
                root / binding["path"]
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "start, canary", "smoke")

    def test_phase_c_completion_without_delivery_receipt_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            self.transition_to_phase_d(checkpoint, root)
            checkpoint["canonical_bindings"] = [
                binding
                for binding in checkpoint["canonical_bindings"]
                if binding["role"] != "PHASE_C_TECHNICAL_DELIVERY_RECEIPT"
            ]
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "Phase C", "receipt")

    def test_phase_c_unrelated_file_cannot_substitute_for_delivery_receipt(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            self.transition_to_phase_d(checkpoint, root)
            role = "PHASE_C_TECHNICAL_DELIVERY_RECEIPT"
            unrelated = (
                "docs/control/goals/walksafe-completion-v1/README.md"
            )
            for binding in checkpoint["canonical_bindings"]:
                if binding["role"] == role:
                    binding["path"] = unrelated
                    binding["document_id"] = "UNRELATED-README"
                    binding["file_sha256"] = goals.sha256_file(root / unrelated)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "Phase C", "receipt")

    def test_phase_d_to_terminal_with_handover_and_closure_is_valid(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            self.transition_to_phase_d(checkpoint, root)
            self.transition_to_terminal(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_phase_d_closure_cannot_start_before_handover_acceptance(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            self.transition_to_phase_d(checkpoint, root)
            self.transition_to_terminal(checkpoint, root)
            role = "PHASE_D_PROJECT_CLOSURE_RECEIPT"
            binding = goals.canonical_binding_map(checkpoint)[role]
            receipt = goals.load_json(root / binding["path"])
            receipt["execution_window"]["started_at"] = (
                "2026-07-23T13:50:00+09:00"
            )
            self.write_json(root / binding["path"], receipt)
            binding["file_sha256"] = goals.sha256_file(
                root / binding["path"]
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "closure execution",
            "before handover acceptance",
        )

    def test_phase_d_handover_decisions_must_precede_acceptance(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            self.transition_to_phase_d(checkpoint, root)
            self.transition_to_terminal(checkpoint, root)
            handover_role = "PHASE_D_OPERATION_HANDOVER_RECEIPT"
            handover_binding = goals.canonical_binding_map(checkpoint)[
                handover_role
            ]
            handover = goals.load_json(root / handover_binding["path"])
            handover["transferor"]["decided_at"] = (
                "2026-07-23T14:55:00+09:00"
            )
            self.write_json(root / handover_binding["path"], handover)
            handover_binding["file_sha256"] = goals.sha256_file(
                root / handover_binding["path"]
            )
            closure_role = "PHASE_D_PROJECT_CLOSURE_RECEIPT"
            closure_binding = goals.canonical_binding_map(checkpoint)[
                closure_role
            ]
            closure = goals.load_json(root / closure_binding["path"])
            closure["operation_handover_receipt_sha256"] = (
                handover_binding["file_sha256"]
            )
            self.write_json(root / closure_binding["path"], closure)
            closure_binding["file_sha256"] = goals.sha256_file(
                root / closure_binding["path"]
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "handover chronology",
            "before acceptance",
        )

    def test_terminal_without_project_closure_receipt_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            self.transition_to_phase_d(checkpoint, root)
            self.transition_to_terminal(checkpoint, root)
            checkpoint["canonical_bindings"] = [
                binding
                for binding in checkpoint["canonical_bindings"]
                if binding["role"] != "PHASE_D_PROJECT_CLOSURE_RECEIPT"
            ]
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "Phase D", "receipt")

    def test_terminal_with_stale_goal_status_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            self.transition_to_phase_d(checkpoint, root)
            self.transition_to_terminal(checkpoint, root)
            checkpoint["goal_execution"]["goal_status"] = "READY"
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "goal_status")

    def test_phase_plan_source_must_be_its_parent_goal_document_and_id(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            state = checkpoint["goal_execution"]
            path = root / state["current_leaf_goal_path"]
            text = path.read_text(encoding="utf-8")
            wrong_path = (
                "docs/control/goals/walksafe-completion-v1/README.md"
            )
            text = text.replace(
                'materialized_from_path = "docs/control/goals/'
                'walksafe-completion-v1/epics/'
                'epic-12-formal-verification-gates.md"',
                f'materialized_from_path = "{wrong_path}"',
            )
            text = text.replace(
                'materialized_from_document_id = "WS-GOAL-B-EPIC-12"',
                'materialized_from_document_id = "WS-GOAL-UNRELATED"',
            )
            current_hash = goals.sha256_file(root / wrong_path)
            text = self.replace_front_matter_value(
                text,
                "materialized_from_sha256",
                current_hash,
            )
            path.write_text(text, encoding="utf-8")
            self.refresh_goal_manifest(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "PHASE_PLAN")

    def test_non_initial_work_item_without_predecessor_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            state = checkpoint["goal_execution"]
            path = root / state["current_leaf_goal_path"]
            text = path.read_text(encoding="utf-8")
            text = self.replace_front_matter_value(text, "predecessor_goal_id", "")
            text = self.replace_front_matter_value(
                text,
                "predecessor_goal_content_sha256",
                "",
            )
            path.write_text(text, encoding="utf-8")
            self.refresh_goal_manifest(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "predecessor")

    def test_transition_from_unfinished_previous_leaf_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            state = checkpoint["goal_execution"]
            current, _ = goals.parse_goal(
                root / state["current_leaf_goal_path"]
            )
            predecessor_id = current["predecessor_goal_id"]
            state["status_by_goal"][predecessor_id] = "READY"
            state["completion_evidence_by_goal"].pop(predecessor_id)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "predecessor", "closed")

    def test_orphan_superseded_work_item_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            state = checkpoint["goal_execution"]
            current, _ = goals.parse_goal(
                root / state["current_leaf_goal_path"]
            )
            predecessor_id = current["predecessor_goal_id"]
            refs = state["completion_evidence_by_goal"].pop(predecessor_id)
            state["archived_completion_evidence_by_goal"][
                predecessor_id
            ] = refs
            state["status_by_goal"][predecessor_id] = "SUPERSEDED"
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "SUPERSEDED")

    def test_duplicate_work_item_without_explicit_reopen_contract_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.repeat_current_work_item(
                checkpoint,
                root,
                with_reopen_contract=False,
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "R002", "must supersede", "R001")

    def test_duplicate_work_item_with_explicit_reopen_contract_is_valid(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.repeat_current_work_item(
                checkpoint,
                root,
                with_reopen_contract=True,
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_reopen_trigger_requires_external_attestation_anchor(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            target_goal_id = checkpoint["goal_execution"][
                "current_leaf_goal_id"
            ]
            self.repeat_current_work_item(
                checkpoint,
                root,
                with_reopen_contract=True,
            )
            role = f"REOPEN_TRIGGER::{target_goal_id}"
            goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE.pop(
                role
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "external authority attestation trust anchor",
            role,
        )

    def test_reopen_trigger_cannot_predate_target_completion(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            target_goal_id = checkpoint["goal_execution"][
                "current_leaf_goal_id"
            ]
            self.repeat_current_work_item(
                checkpoint,
                root,
                with_reopen_contract=True,
            )
            role = f"REOPEN_TRIGGER::{target_goal_id}"
            binding = goals.canonical_binding_map(checkpoint)[role]
            receipt = goals.load_json(root / binding["path"])
            receipt["decided_at"] = "2026-07-22T12:00:00+09:00"
            receipt["approver"]["decided_at"] = (
                "2026-07-22T12:00:00+09:00"
            )
            self.write_json(root / binding["path"], receipt)
            binding["file_sha256"] = goals.sha256_file(
                root / binding["path"]
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "reopen trigger", "predates")

    def test_historical_completed_work_item_can_be_reopened_later(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            state = checkpoint["goal_execution"]
            first_goal_id = state["current_leaf_goal_id"]
            first_goal_path = state["current_leaf_goal_path"]
            first, _ = goals.parse_goal(root / first_goal_path)
            parent_goal_id = first["parent_goal_id"]
            phase_plan_path = first["materialized_from_path"]

            self.complete_active_work_item(checkpoint, root)
            later_goal_id = (
                "WS-GOAL-B-EPIC-12-GATE-CLOUD-COST-MEASUREMENT-R001"
            )
            self.start_materialized_successor(
                checkpoint,
                root,
                goal_id=later_goal_id,
                relative_path=(
                    "docs/control/goals/walksafe-completion-v1/work-items/b/"
                    "epic-12-cloud-cost-measurement-r001.md"
                ),
                phase_id="B",
                epic_key="EPIC-12",
                parent_goal_id=parent_goal_id,
                sequence=2,
                work_item_id="EPIC-12-CLOUD-COST-MEASUREMENT",
                policy_id="GATE-CLOUD-COST-MEASUREMENT",
                gap_id="GAP-067",
                dependencies=first["dependencies"],
                materialized_from_role="PHASE_PLAN",
                materialized_from_path=phase_plan_path,
                materialized_from_document_id=parent_goal_id,
            )
            self.complete_active_work_item(checkpoint, root)

            reopen_ref = self.create_reopen_trigger(
                checkpoint,
                root,
                target_goal_id=first_goal_id,
                work_item_id=first["work_item_id"],
            )
            reopened_goal_id = (
                "WS-GOAL-B-EPIC-12-"
                "GATE-SINGLE-ADMIN-RECOVERY-DRILL-R002"
            )
            reopened_goal_path = self.materialize_work_item(
                root,
                goal_id=reopened_goal_id,
                relative_path=(
                    "docs/control/goals/walksafe-completion-v1/work-items/b/"
                    "epic-12-single-admin-recovery-drill-r002.md"
                ),
                phase_id="B",
                parent_goal_id=parent_goal_id,
                sequence=3,
                work_item_id=first["work_item_id"],
                source_policy_ids=first["source_policy_ids"],
                gap_ids=first["gap_ids"],
                dependencies=first["dependencies"],
                materialized_from_role="PHASE_PLAN",
                materialized_from_path=phase_plan_path,
                materialized_from_document_id=parent_goal_id,
                predecessor_goal_id=later_goal_id,
                predecessor_goal_path=state["current_leaf_goal_path"],
                supersedes_goal_id=first_goal_id,
                supersedes_goal_path=first_goal_path,
                reopen_reason="뒤늦게 확인된 회귀 결함을 수정한다.",
                reopen_evidence_refs=[reopen_ref],
            )
            first_completion_refs = state[
                "completion_evidence_by_goal"
            ].pop(first_goal_id)
            state["archived_completion_evidence_by_goal"][
                first_goal_id
            ] = first_completion_refs
            state["status_by_goal"][first_goal_id] = "SUPERSEDED"
            state["status_by_goal"][reopened_goal_id] = "PLANNED"
            state["materialized_child_goal_ids_by_parent"][
                parent_goal_id
            ].append(reopened_goal_id)
            self.append_event(
                checkpoint,
                root=root,
                event_type="GOAL_SUPERSEDED",
                current_leaf_goal_id=later_goal_id,
                from_status="COMPLETE_AT_TARGET",
                to_status="COMPLETE_AT_TARGET",
                evidence_refs=[reopen_ref],
                extra={
                    "superseded_goal_id": first_goal_id,
                    "successor_goal_id": reopened_goal_id,
                    "reopen_evidence_refs": [reopen_ref],
                },
            )

            state["status_by_goal"][reopened_goal_id] = "IN_PROGRESS"
            state["current_leaf_goal_id"] = reopened_goal_id
            state["current_leaf_goal_path"] = reopened_goal_path
            state["current_work_item_id"] = first["work_item_id"]
            state["goal_status"] = "IN_PROGRESS"
            checkpoint["current_work"]["work_item_id"] = first[
                "work_item_id"
            ]
            self.append_event(
                checkpoint,
                root=root,
                event_type="GOAL_TRANSITION",
                current_leaf_goal_id=reopened_goal_id,
                from_status="COMPLETE_AT_TARGET",
                to_status="IN_PROGRESS",
                evidence_refs=[reopen_ref],
            )
            self.refresh_goal_manifest(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_awaiting_user_can_resume_only_after_recorded_resolution(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            leaf_id = state["current_leaf_goal_id"]
            self.activate_current_leaf(checkpoint, root)
            state["status_by_goal"][leaf_id] = "AWAITING_USER"
            state["goal_status"] = "AWAITING_USER"
            blocker = self.make_blocker(
                blocker_id="TEST-BLOCKER-RESUME-001",
                condition_code="POLICY_CHANGE_REQUIRED",
                owner="USER",
                blocks_goal_id=leaf_id,
                return_leaf_goal_id=leaf_id,
                prompt="정책 변경 승인이 필요합니다.",
            )
            state["blockers_by_goal"] = {leaf_id: [blocker]}
            state["pending_questions"] = [blocker]
            state["open_question_count"] = 1
            self.append_event(
                checkpoint,
                root=root,
                event_type="BLOCKER_RECORDED",
                current_leaf_goal_id=leaf_id,
                from_status="IN_PROGRESS",
                to_status="AWAITING_USER",
                extra={"blocker_ids": ["TEST-BLOCKER-RESUME-001"]},
            )
            resolution = self.create_blocker_resolution(
                checkpoint,
                root,
                blocker=blocker,
                blocker_event_sha256=state["transition_history"][-1][
                    "event_sha256"
                ],
            )
            state["blockers_by_goal"] = {}
            state["pending_questions"] = []
            state["open_question_count"] = 0
            state["blocker_resolution_history"] = [resolution]
            state["status_by_goal"][leaf_id] = "READY"
            state["goal_status"] = "READY"
            self.append_event(
                checkpoint,
                root=root,
                event_type="BLOCKER_RESOLVED",
                current_leaf_goal_id=leaf_id,
                from_status="AWAITING_USER",
                to_status="READY",
                evidence_refs=[resolution["resolution_receipt_ref"]],
                extra={
                    "resolved_blocker_ids": [
                        "TEST-BLOCKER-RESUME-001"
                    ]
                },
            )
            state["status_by_goal"][leaf_id] = "IN_PROGRESS"
            state["goal_status"] = "IN_PROGRESS"
            self.append_event(
                checkpoint,
                root=root,
                event_type="GOAL_RESUMED",
                current_leaf_goal_id=leaf_id,
                from_status="READY",
                to_status="IN_PROGRESS",
                extra={"resolved_blocker_ids": ["TEST-BLOCKER-RESUME-001"]},
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_dependency_ready_bypass_preserves_blocker_and_return_path(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.bypass_waiting_epic_to_epic03(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_dependency_ready_bypass_cannot_drop_original_blocker(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.bypass_waiting_epic_to_epic03(checkpoint, root)
            state = checkpoint["goal_execution"]
            state["blockers_by_goal"].pop(
                goals.EXPECTED_EPIC_GOAL_IDS["EPIC-02"]
            )
            state["open_question_count"] -= 1
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "bypass")
        self.assert_has_error(errors, "blocker-map")

    def test_dependency_ready_bypass_returns_after_all_blockers_resolve(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.bypass_waiting_epic_to_epic03(checkpoint, root)
            self.return_from_dependency_ready_bypass(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assertEqual(errors, [])

    def test_blocker_resolution_requires_external_attestation_anchor(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.bypass_waiting_epic_to_epic03(checkpoint, root)
            self.return_from_dependency_ready_bypass(checkpoint, root)
            role = checkpoint["goal_execution"][
                "blocker_resolution_history"
            ][0]["resolution_receipt_ref"]
            goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE.pop(
                role
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "external authority attestation trust anchor",
            role,
        )

    def test_blocker_resolution_cannot_predate_blocker_creation(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.bypass_waiting_epic_to_epic03(checkpoint, root)
            self.return_from_dependency_ready_bypass(checkpoint, root)
            resolution = checkpoint["goal_execution"][
                "blocker_resolution_history"
            ][0]
            role = resolution["resolution_receipt_ref"]
            binding = goals.canonical_binding_map(checkpoint)[role]
            receipt = goals.load_json(root / binding["path"])
            receipt["decided_at"] = "2026-07-23T09:00:00+09:00"
            receipt["resolver"]["decided_at"] = (
                "2026-07-23T09:00:00+09:00"
            )
            self.write_json(root / binding["path"], receipt)
            binding["file_sha256"] = goals.sha256_file(
                root / binding["path"]
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "resolution", "predates", "creation")

    def test_blocker_resolution_cannot_predate_recording_event(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.bypass_waiting_epic_to_epic03(checkpoint, root)
            self.return_from_dependency_ready_bypass(checkpoint, root)
            resolution = checkpoint["goal_execution"][
                "blocker_resolution_history"
            ][0]
            role = resolution["resolution_receipt_ref"]
            binding = goals.canonical_binding_map(checkpoint)[role]
            receipt = goals.load_json(root / binding["path"])
            receipt["decided_at"] = "2026-07-23T10:00:30+09:00"
            receipt["resolver"]["decided_at"] = (
                "2026-07-23T10:00:30+09:00"
            )
            self.write_json(root / binding["path"], receipt)
            binding["file_sha256"] = goals.sha256_file(
                root / binding["path"]
            )
            goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE[
                role
            ] = binding["file_sha256"]
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "resolution",
            "predates",
            "BLOCKER_RECORDED",
        )

    def test_active_blocker_creation_cannot_postdate_recording_event(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            self.activate_current_leaf(checkpoint, root)
            leaf_id = state["current_leaf_goal_id"]
            state["status_by_goal"][leaf_id] = "AWAITING_USER"
            state["goal_status"] = "AWAITING_USER"
            blocker = self.make_blocker(
                blocker_id="TEST-BLOCKER-FUTURE",
                owner="USER",
                condition_code="AUTHORITY_EXPANSION_REQUIRED",
                blocks_goal_id=leaf_id,
                return_leaf_goal_id=leaf_id,
                prompt="기록 시각보다 늦은 blocker를 거부합니다.",
            )
            state["blockers_by_goal"] = {leaf_id: [blocker]}
            state["pending_questions"] = [blocker]
            state["open_question_count"] = 1
            self.append_event(
                checkpoint,
                root=root,
                event_type="BLOCKER_RECORDED",
                current_leaf_goal_id=leaf_id,
                from_status="IN_PROGRESS",
                to_status="AWAITING_USER",
                extra={"blocker_ids": [blocker["blocker_id"]]},
            )
            blocker["created_at"] = "2026-07-24T10:00:00+09:00"
            blocker["blocker_snapshot_sha256"] = (
                goals.blocker_snapshot_sha256(blocker)
            )
            recorded_event = state["transition_history"][-1]
            recorded_event["blockers_after"][leaf_id] = [
                json.loads(json.dumps(blocker))
            ]
            self.rehash_event_chain(checkpoint)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "blocker creation",
            "postdates",
            "BLOCKER_RECORDED",
        )

    def test_blocker_resolved_event_must_bind_resolution_receipts(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.bypass_waiting_epic_to_epic03(checkpoint, root)
            self.return_from_dependency_ready_bypass(checkpoint, root)
            resolved_event = next(
                event
                for event in checkpoint["goal_execution"][
                    "transition_history"
                ]
                if event["event_type"] == "BLOCKER_RESOLVED"
            )
            resolved_event["evidence_refs"] = ["IMPLEMENTATION_BACKLOG"]
            self.rehash_event_chain(checkpoint)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "invalid BLOCKER_RESOLVED")

    def test_bulk_sibling_epic_completion_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            self.activate_current_leaf(checkpoint, root)
            leaf_id = state["current_leaf_goal_id"]
            sibling_id = goals.EXPECTED_EPIC_GOAL_IDS["EPIC-03"]
            leaf_ref = f"WORK_ITEM_SOURCE::{leaf_id}"
            state["status_by_goal"][leaf_id] = "COMPLETE_AT_TARGET"
            state["completion_evidence_by_goal"][leaf_id] = [leaf_ref]
            state["status_by_goal"][sibling_id] = "COMPLETE_AT_TARGET"
            state["completion_evidence_by_goal"][sibling_id] = [
                "IMPLEMENTATION_BACKLOG"
            ]
            state["goal_status"] = "COMPLETE_AT_TARGET"
            self.append_event(
                checkpoint,
                root=root,
                event_type="GOAL_COMPLETED",
                current_leaf_goal_id=leaf_id,
                from_status="IN_PROGRESS",
                to_status="COMPLETE_AT_TARGET",
                evidence_refs=[leaf_ref, "IMPLEMENTATION_BACKLOG"],
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "completion", "current leaf", "ancestors")

    def test_completed_epic_with_missing_policy_gap_work_items_is_rejected(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.activate_current_leaf(checkpoint, root)
            self.complete_active_work_item(
                checkpoint,
                root,
                completed_ancestor_ids=[
                    goals.EXPECTED_EPIC_GOAL_IDS["EPIC-02"]
                ],
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "policy/Gap", "exactly once")

    def test_non_blocker_event_cannot_modify_blocker_snapshot(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.bypass_waiting_epic_to_epic03(checkpoint, root)
            state = checkpoint["goal_execution"]
            blocker = next(
                blocker
                for rows in state["blockers_by_goal"].values()
                for blocker in rows
            )
            blocker["prompt"] = "비차단 이벤트에서 변조한 질문"
            blocker["blocker_snapshot_sha256"] = (
                goals.blocker_snapshot_sha256(blocker)
            )
            leaf_id = state["current_leaf_goal_id"]
            leaf_ref = f"WORK_ITEM_SOURCE::{leaf_id}"
            state["status_by_goal"][leaf_id] = "COMPLETE_AT_TARGET"
            state["completion_evidence_by_goal"][leaf_id] = [leaf_ref]
            state["goal_status"] = "COMPLETE_AT_TARGET"
            self.append_event(
                checkpoint,
                root=root,
                event_type="GOAL_COMPLETED",
                current_leaf_goal_id=leaf_id,
                from_status="IN_PROGRESS",
                to_status="COMPLETE_AT_TARGET",
                evidence_refs=[leaf_ref],
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "blocker snapshot", "outside")

    def test_consumed_blocker_resolution_cannot_resume_twice(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.bypass_waiting_epic_to_epic03(checkpoint, root)
            self.return_from_dependency_ready_bypass(checkpoint, root)
            state = checkpoint["goal_execution"]
            resolved_ids = [
                row["blocker_id"]
                for row in state["blocker_resolution_history"]
            ]
            self.append_event(
                checkpoint,
                root=root,
                event_type="GOAL_RESUMED",
                current_leaf_goal_id=state["current_leaf_goal_id"],
                from_status="IN_PROGRESS",
                to_status="IN_PROGRESS",
                evidence_refs=[
                    row["resolution_receipt_ref"]
                    for row in state["blocker_resolution_history"]
                ],
                extra={"resolved_blocker_ids": resolved_ids},
            )
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "invalid same-leaf GOAL_RESUMED")

    def test_historical_bypass_snapshot_tampering_is_rejected_after_return(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.bypass_waiting_epic_to_epic03(checkpoint, root)
            self.return_from_dependency_ready_bypass(checkpoint, root)
            state = checkpoint["goal_execution"]
            bypass_event = next(
                event
                for event in state["transition_history"]
                if event["event_type"] == "GOAL_BYPASSED"
            )
            bypass_event["bypass_after"]["return_leaf_goal_id"] = (
                bypass_event["current_leaf_goal_id"]
            )
            self.rehash_event_chain(checkpoint)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "historical bypass snapshot", "differs")

    def test_unregistered_reopen_approver_and_blocker_resolver_are_rejected(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.repeat_current_work_item(
                checkpoint,
                root,
                with_reopen_contract=True,
            )
            self.remove_authority_actor(
                checkpoint,
                root,
                actor_id="TEST-APPROVER",
            )
            self.write_checkpoint(checkpoint, root)

            reopen_errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            reopen_errors,
            "REGRESSION_DEFECT_RECEIPT",
            "approver",
            "not in the authority roster",
        )

        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.bypass_waiting_epic_to_epic03(checkpoint, root)
            self.return_from_dependency_ready_bypass(checkpoint, root)
            self.remove_authority_actor(
                checkpoint,
                root,
                actor_id="TEST-BLOCKER-RESOLVER",
            )
            self.write_checkpoint(checkpoint, root)

            resolution_errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            resolution_errors,
            "DECISION_RECEIPT",
            "resolver",
            "not in the authority roster",
        )

    def test_awaiting_user_and_external_condition_mismatch_is_rejected(self) -> None:
        cases = [
            ("AWAITING_USER", "REAL_DEVICE_OR_PARTICIPANT_REQUIRED"),
            ("AWAITING_EXTERNAL", "POLICY_CHANGE_REQUIRED"),
        ]
        for waiting_status, condition_code in cases:
            with self.subTest(
                waiting_status=waiting_status,
                condition_code=condition_code,
            ), self.fixture_root() as root:
                checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
                state = checkpoint["goal_execution"]
                leaf_id = state["current_leaf_goal_id"]
                self.activate_current_leaf(checkpoint, root)
                state["status_by_goal"][leaf_id] = waiting_status
                state["goal_status"] = waiting_status
                blocker = self.make_blocker(
                    blocker_id="TEST-BLOCKER-TYPE-001",
                    condition_code=condition_code,
                    owner=(
                        "USER"
                        if waiting_status == "AWAITING_USER"
                        else "EXTERNAL"
                    ),
                    blocks_goal_id=leaf_id,
                    return_leaf_goal_id=leaf_id,
                    prompt="대기 유형 조합을 검사합니다.",
                )
                state["blockers_by_goal"] = {leaf_id: [blocker]}
                state["pending_questions"] = [blocker]
                state["open_question_count"] = 1
                self.append_event(
                    checkpoint,
                    root=root,
                    event_type="BLOCKER_RECORDED",
                    current_leaf_goal_id=leaf_id,
                    from_status="IN_PROGRESS",
                    to_status=waiting_status,
                    extra={"blocker_ids": ["TEST-BLOCKER-TYPE-001"]},
                )
                self.write_checkpoint(checkpoint, root)

                errors = goals.validate(root, check_continuation=False)

            self.assert_has_error(errors, "owner differs from condition code")

    def test_malformed_parent_and_current_leaf_fail_cleanly(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            path = root / state["current_leaf_goal_path"]
            text = path.read_text(encoding="utf-8").replace(
                'parent_goal_id = "WS-GOAL-A-EPIC-02"',
                "parent_goal_id = 7",
                1,
            )
            path.write_text(text, encoding="utf-8")
            self.refresh_goal_manifest(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "parent")

        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            checkpoint["goal_execution"]["current_leaf_goal_id"] = []
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "current leaf")

    def test_static_plan_change_is_rejected_even_after_package_rehash(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            path = (
                root
                / "docs/control/goals/walksafe-completion-v1/"
                "30-phase-c-release-delivery.md"
            )
            path.write_text(
                path.read_text(encoding="utf-8") + "\n변조된 정적 계획\n",
                encoding="utf-8",
            )
            self.rehash(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "static Goal plan", "protected content changed")

    def test_historical_transition_rewrite_is_rejected_after_chain_rehash(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            state = checkpoint["goal_execution"]
            history = state["transition_history"]
            history[0]["event_id"] = "REWRITTEN-HISTORY-EVENT"
            previous_hash = ""
            for event in history:
                event["previous_event_sha256"] = previous_hash
                event["event_sha256"] = goals.event_sha256(event)
                previous_hash = event["event_sha256"]
            state["transition_history_anchor_sha256"] = previous_hash
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "initial transition", "anchor")

    def test_latest_transition_rewrite_is_rejected_after_chain_rehash(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            state = checkpoint["goal_execution"]
            state["transition_history"][-1]["event_id"] = (
                "REWRITTEN-LATEST-EVENT"
            )
            self.rehash_event_chain(checkpoint)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(
            errors,
            "reviewed",
            "checker head trust anchor",
        )

    def test_transition_history_leaf_discontinuity_is_rejected_after_rehash(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            state = checkpoint["goal_execution"]
            event = state["transition_history"][-1]
            event["previous_leaf_goal_id"] = event["current_leaf_goal_id"]
            event["previous_leaf_content_sha256"] = event[
                "current_leaf_content_sha256"
            ]
            self.rehash_event_chain(checkpoint)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "previous leaf", "continuity")

    def test_reverse_revision_supersedes_target_is_rejected(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.repeat_current_work_item(
                checkpoint,
                root,
                with_reopen_contract=True,
            )
            state = checkpoint["goal_execution"]
            current_id = state["current_leaf_goal_id"]
            current_path = state["current_leaf_goal_path"]
            current, _ = goals.parse_goal(root / current_path)
            first_revision_id = current["supersedes_goal_id"]
            first_revision_path = self.goal_path_by_id(root)[first_revision_id]
            reopen_ref = self.create_reopen_trigger(
                checkpoint,
                root,
                target_goal_id=first_revision_id,
                work_item_id=current["work_item_id"],
            )
            reverse_id = (
                "WS-GOAL-B-EPIC-12-"
                "GATE-SINGLE-ADMIN-RECOVERY-DRILL-R003"
            )
            reverse_path = self.materialize_work_item(
                root,
                goal_id=reverse_id,
                relative_path=(
                    "docs/control/goals/walksafe-completion-v1/work-items/b/"
                    "epic-12-single-admin-recovery-drill-r003.md"
                ),
                phase_id="B",
                parent_goal_id=current["parent_goal_id"],
                sequence=3,
                work_item_id=current["work_item_id"],
                source_policy_ids=current["source_policy_ids"],
                gap_ids=current["gap_ids"],
                dependencies=current["dependencies"],
                materialized_from_role="PHASE_PLAN",
                materialized_from_path=current["materialized_from_path"],
                materialized_from_document_id=current[
                    "materialized_from_document_id"
                ],
                predecessor_goal_id=current_id,
                predecessor_goal_path=current_path,
                supersedes_goal_id=first_revision_id,
                supersedes_goal_path=first_revision_path,
                reopen_reason="이전보다 더 오래된 revision으로 역행한다.",
                reopen_evidence_refs=[reopen_ref],
            )
            state["status_by_goal"][reverse_id] = "PLANNED"
            state["materialized_child_goal_ids_by_parent"][
                current["parent_goal_id"]
            ].append(reverse_id)
            self.refresh_goal_manifest(checkpoint, root)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "R003", "must supersede", "R002")

    def test_intermediate_symlink_cannot_escape_raw_evidence_root(self) -> None:
        with self.fixture_root() as root, tempfile.TemporaryDirectory() as outside:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            self.transition_to_phase_b(checkpoint, root)
            self.transition_to_phase_c(checkpoint, root)
            formal = goals.canonical_binding_map(checkpoint)[
                "FORMAL_TEST_REPORT"
            ]
            receipt = goals.load_json(root / formal["path"])
            raw_relative = receipt["raw_evidence"][0]["path"]
            raw_path = root / raw_relative
            raw_payload = goals.load_json(raw_path)
            raw_parent = raw_path.parent
            shutil.rmtree(raw_parent)
            outside_path = Path(outside) / raw_path.name
            self.write_json(outside_path, raw_payload)
            raw_parent.symlink_to(Path(outside), target_is_directory=True)
            self.write_checkpoint(checkpoint, root)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "raw_evidence", "unsafe")

    def test_checkpoint_symlink_cannot_escape_repository(self) -> None:
        with self.fixture_root() as root, tempfile.TemporaryDirectory() as outside:
            checkpoint_path = root / CHECKPOINT_RELATIVE
            outside_path = Path(outside) / "checkpoint.json"
            shutil.copy2(checkpoint_path, outside_path)
            checkpoint_path.unlink()
            checkpoint_path.symlink_to(outside_path)

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "checkpoint path", "unsafe")

    def test_malformed_static_manifest_protected_file_list_fails_cleanly(
        self,
    ) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            manifest_path = root / goals.STATIC_PLAN_MANIFEST_RELATIVE
            manifest = goals.load_json(manifest_path)
            manifest["protected_files"] = {"not": "a list"}
            self.write_json(manifest_path, manifest)
            manifest_hash = goals.sha256_file(manifest_path)
            state = checkpoint["goal_execution"]
            state["static_plan_manifest_sha256"] = manifest_hash
            initial_event = state["transition_history"][0]
            initial_event["static_plan_manifest_sha256"] = manifest_hash
            initial_event["event_sha256"] = goals.event_sha256(initial_event)
            state["transition_history_anchor_sha256"] = initial_event[
                "event_sha256"
            ]
            self.rehash(checkpoint, root)
            self.write_checkpoint(checkpoint, root)
            goals.EXPECTED_STATIC_PLAN_MANIFEST_SHA256 = manifest_hash
            goals.EXPECTED_INITIAL_TRANSITION_EVENT_SHA256 = initial_event[
                "event_sha256"
            ]

            errors = goals.validate(root, check_continuation=False)

        self.assert_has_error(errors, "protected_files", "list")

    def test_static_manifest_nul_path_fails_cleanly(self) -> None:
        with self.fixture_root() as root:
            checkpoint = goals.load_json(root / CHECKPOINT_RELATIVE)
            manifest_path = root / goals.STATIC_PLAN_MANIFEST_RELATIVE
            manifest = goals.load_json(manifest_path)
            manifest["protected_files"][0]["path"] = "bad\u0000path.md"
            self.write_json(manifest_path, manifest)
            manifest_hash = goals.sha256_file(manifest_path)
            state = checkpoint["goal_execution"]
            state["static_plan_manifest_sha256"] = manifest_hash
            initial_event = state["transition_history"][0]
            initial_event["static_plan_manifest_sha256"] = manifest_hash
            initial_event["event_sha256"] = goals.event_sha256(initial_event)
            state["transition_history_anchor_sha256"] = initial_event[
                "event_sha256"
            ]
            self.rehash(checkpoint, root)
            self.write_checkpoint(checkpoint, root)
            goals.EXPECTED_STATIC_PLAN_MANIFEST_SHA256 = manifest_hash
            goals.EXPECTED_INITIAL_TRANSITION_EVENT_SHA256 = initial_event[
                "event_sha256"
            ]

            errors = goals.validate(root, check_continuation=False)

        self.assertIsInstance(errors, list)
        self.assert_has_error(errors, "unsafe")

    def assert_has_error(self, errors: list[str], *fragments: str) -> None:
        lowered = [error.lower() for error in errors]
        expected = [fragment.lower() for fragment in fragments]
        self.assertTrue(
            any(all(fragment in error for fragment in expected) for error in lowered),
            f"expected error containing {fragments!r}, got: {errors!r}",
        )

    def make_blocker(
        self,
        *,
        blocker_id: str,
        condition_code: str,
        owner: str,
        blocks_goal_id: str,
        return_leaf_goal_id: str,
        prompt: str,
    ) -> dict:
        blocker = {
            "blocker_id": blocker_id,
            "condition_code": condition_code,
            "owner": owner,
            "blocks_goal_id": blocks_goal_id,
            "return_leaf_goal_id": return_leaf_goal_id,
            "created_at": "2026-07-23T10:00:00+09:00",
            "prompt": prompt,
        }
        blocker["blocker_snapshot_sha256"] = goals.blocker_snapshot_sha256(
            blocker
        )
        return blocker

    def create_blocker_resolution(
        self,
        checkpoint: dict,
        root: Path,
        *,
        blocker: dict,
        blocker_event_sha256: str,
    ) -> dict:
        blocker_id = blocker["blocker_id"]
        role = f"BLOCKER_RESOLUTION::{blocker_id}"
        raw_path = (
            "docs/control/test-evidence/raw/"
            f"{blocker_id.lower()}-resolution.json"
        )
        self.write_json(
            root / raw_path,
            {
                "blocker_id": blocker_id,
                "resolution": "APPROVED",
            },
        )
        receipt = {
            "schema_version": "1.0",
            "document_id": f"TEST-RESOLUTION-{blocker_id}",
            "evidence_type": {
                "USER": "DECISION_RECEIPT",
                "EXTERNAL": "EXTERNAL_EVIDENCE_RECEIPT",
                "BLOCKED": "BLOCKER_REMEDIATION_RECEIPT",
            }[blocker["owner"]],
            "status": "RESOLVED",
            "blocker_id": blocker_id,
            "goal_id": blocker["blocks_goal_id"],
            "condition_code": blocker["condition_code"],
            "owner": blocker["owner"],
            "blocker_event_sha256": blocker_event_sha256,
            "blocker_snapshot_sha256": blocker[
                "blocker_snapshot_sha256"
            ],
            **self.authority_roster_receipt_fields(root),
            "decided_at": "2026-07-23T12:00:00+09:00",
            "resolver": {
                "id": "TEST-BLOCKER-RESOLVER",
                "role": "BLOCKER_RESOLVER",
                "authority": "RESOLVE",
                "authority_evidence_ref": "AUTHORITY_ROSTER",
                "decision": "APPROVED",
                "decided_at": "2026-07-23T12:00:00+09:00",
            },
            "raw_evidence": [
                {
                    "path": raw_path,
                    "sha256": goals.sha256_file(root / raw_path),
                }
            ],
        }
        receipt_path = (
            "docs/control/test-evidence/"
            f"{blocker_id.lower()}-resolution-receipt.json"
        )
        self.write_json(root / receipt_path, receipt)
        receipt_sha256 = goals.sha256_file(root / receipt_path)
        goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE[
            role
        ] = receipt_sha256
        checkpoint["canonical_bindings"].append(
            {
                "role": role,
                "path": receipt_path,
                "document_id": receipt["document_id"],
                "file_sha256": receipt_sha256,
            }
        )
        return {
            "blocker_id": blocker_id,
            "goal_id": blocker["blocks_goal_id"],
            "condition_code": blocker["condition_code"],
            "owner": blocker["owner"],
            "blocker_event_sha256": blocker_event_sha256,
            "blocker_snapshot_sha256": blocker[
                "blocker_snapshot_sha256"
            ],
            "resolution_receipt_ref": role,
        }

    def replace_front_matter_value(
        self,
        text: str,
        field: str,
        value: str,
    ) -> str:
        prefix = f'{field} = "'
        lines = text.splitlines()
        for index, line in enumerate(lines):
            if line.startswith(prefix):
                lines[index] = f'{field} = "{value}"'
                return "\n".join(lines) + ("\n" if text.endswith("\n") else "")
        self.fail(f"front matter field not found: {field}")

    def activate_current_leaf(self, checkpoint: dict, root: Path) -> None:
        state = checkpoint["goal_execution"]
        state["activation_status"] = "ACTIVE"
        state["package_status"] = "ACTIVE"
        state["goal_status"] = "IN_PROGRESS"
        for goal_id in (
            goals.EXPECTED_MASTER_ID,
            state["current_phase_goal_id"],
            state["current_epic_goal_id"],
            state["current_leaf_goal_id"],
        ):
            if goal_id:
                state["status_by_goal"][goal_id] = "IN_PROGRESS"
        self.append_event(
            checkpoint,
            root=root,
            event_type="PACKAGE_ACTIVATED",
            current_leaf_goal_id=state["current_leaf_goal_id"],
            from_status="READY",
            to_status="IN_PROGRESS",
        )

    def repeat_current_work_item(
        self,
        checkpoint: dict,
        root: Path,
        *,
        with_reopen_contract: bool,
    ) -> None:
        state = checkpoint["goal_execution"]
        old_goal_id = state["current_leaf_goal_id"]
        old_goal_path = state["current_leaf_goal_path"]
        old_metadata, _ = goals.parse_goal(root / old_goal_path)
        parent_goal_id = old_metadata["parent_goal_id"]
        source_path = old_metadata["materialized_from_path"]
        self.complete_active_work_item(checkpoint, root)
        completion_refs = state["completion_evidence_by_goal"][old_goal_id]
        reopen_evidence_refs = (
            [
                self.create_reopen_trigger(
                    checkpoint,
                    root,
                    target_goal_id=old_goal_id,
                    work_item_id=old_metadata["work_item_id"],
                )
            ]
            if with_reopen_contract
            else []
        )
        new_goal_id = (
            "WS-GOAL-B-EPIC-12-GATE-SINGLE-ADMIN-RECOVERY-DRILL-R002"
        )
        new_goal_path = self.materialize_work_item(
            root,
            goal_id=new_goal_id,
            relative_path=(
                "docs/control/goals/walksafe-completion-v1/work-items/b/"
                "epic-12-single-admin-recovery-drill-r002.md"
            ),
            phase_id="B",
            parent_goal_id=parent_goal_id,
            sequence=2,
            work_item_id=old_metadata["work_item_id"],
            source_policy_ids=old_metadata["source_policy_ids"],
            gap_ids=old_metadata["gap_ids"],
            dependencies=old_metadata["dependencies"],
            materialized_from_role="PHASE_PLAN",
            materialized_from_path=source_path,
            materialized_from_document_id=old_metadata[
                "materialized_from_document_id"
            ],
            predecessor_goal_id=old_goal_id,
            predecessor_goal_path=old_goal_path,
            supersedes_goal_id=old_goal_id if with_reopen_contract else "",
            supersedes_goal_path=old_goal_path if with_reopen_contract else "",
            reopen_reason=(
                "검증 실패를 수정한 동일 작업 재개"
                if with_reopen_contract
                else ""
            ),
            reopen_evidence_refs=reopen_evidence_refs,
        )
        if with_reopen_contract:
            state["status_by_goal"][new_goal_id] = "PLANNED"
            state["materialized_child_goal_ids_by_parent"][
                parent_goal_id
            ].append(new_goal_id)
            state["completion_evidence_by_goal"].pop(old_goal_id)
            state["archived_completion_evidence_by_goal"][
                old_goal_id
            ] = completion_refs
            state["status_by_goal"][old_goal_id] = "SUPERSEDED"
            state["goal_status"] = "SUPERSEDED"
            self.append_event(
                checkpoint,
                root=root,
                event_type="GOAL_SUPERSEDED",
                current_leaf_goal_id=old_goal_id,
                from_status="COMPLETE_AT_TARGET",
                to_status="SUPERSEDED",
                evidence_refs=reopen_evidence_refs,
                extra={
                    "superseded_goal_id": old_goal_id,
                    "successor_goal_id": new_goal_id,
                    "reopen_evidence_refs": reopen_evidence_refs,
                },
            )
        else:
            state["materialized_child_goal_ids_by_parent"][
                parent_goal_id
            ].append(new_goal_id)

        state["status_by_goal"][new_goal_id] = "IN_PROGRESS"
        state["current_leaf_goal_id"] = new_goal_id
        state["current_leaf_goal_path"] = new_goal_path
        state["current_work_item_id"] = old_metadata["work_item_id"]
        state["goal_status"] = "IN_PROGRESS"
        checkpoint["current_work"]["work_item_id"] = old_metadata["work_item_id"]
        self.append_event(
            checkpoint,
            root=root,
            event_type="GOAL_TRANSITION",
            current_leaf_goal_id=new_goal_id,
            from_status=(
                "SUPERSEDED" if with_reopen_contract else "COMPLETE_AT_TARGET"
            ),
            to_status="IN_PROGRESS",
            evidence_refs=(
                reopen_evidence_refs
                if with_reopen_contract
                else ["IMPLEMENTATION_BACKLOG"]
            ),
            extra=(
                {
                    "superseded_goal_id": old_goal_id,
                    "successor_goal_id": new_goal_id,
                    "reopen_evidence_refs": reopen_evidence_refs,
                }
                if with_reopen_contract
                else None
            ),
        )
        self.refresh_goal_manifest(checkpoint, root)

    def create_reopen_trigger(
        self,
        checkpoint: dict,
        root: Path,
        *,
        target_goal_id: str,
        work_item_id: str,
    ) -> str:
        role = f"REOPEN_TRIGGER::{target_goal_id}"
        completion_event = next(
            (
                event
                for event in reversed(
                    checkpoint["goal_execution"]["transition_history"]
                )
                if event.get("event_type") == "GOAL_COMPLETED"
                and event.get("status_changes", {}).get(target_goal_id)
                == "COMPLETE_AT_TARGET"
            ),
            None,
        )
        self.assertIsNotNone(completion_event)
        receipt = {
            "schema_version": "1.0",
            "document_id": f"TEST-REOPEN-{target_goal_id}",
            "evidence_type": "REGRESSION_DEFECT_RECEIPT",
            "status": "CONFIRMED",
            "target_goal_id": target_goal_id,
            "work_item_id": work_item_id,
            "target_completion_event_sha256": completion_event[
                "event_sha256"
            ],
            **self.authority_roster_receipt_fields(root),
            "decided_at": "2026-07-23T12:00:00+09:00",
            "approver": self.receipt_actor(
                "approver",
                require_decision=True,
            ),
        }
        relative = (
            "docs/control/test-evidence/"
            f"{target_goal_id.lower()}-reopen-trigger.json"
        )
        self.write_json(root / relative, receipt)
        receipt_sha256 = goals.sha256_file(root / relative)
        goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE[
            role
        ] = receipt_sha256
        checkpoint["canonical_bindings"].append(
            {
                "role": role,
                "path": relative,
                "document_id": receipt["document_id"],
                "file_sha256": receipt_sha256,
            }
        )
        return role

    def bypass_waiting_epic_to_epic03(
        self,
        checkpoint: dict,
        root: Path,
    ) -> None:
        state = checkpoint["goal_execution"]
        old_leaf_id = state["current_leaf_goal_id"]
        old_leaf_path = state["current_leaf_goal_path"]
        epic_blocker_id = "TEST-BLOCKER-BYPASS-EPIC02"
        leaf_blocker_id = "TEST-BLOCKER-BYPASS-LEAF"
        self.activate_current_leaf(checkpoint, root)
        state["status_by_goal"][old_leaf_id] = "AWAITING_USER"
        state["status_by_goal"][goals.EXPECTED_EPIC_GOAL_IDS["EPIC-02"]] = (
            "AWAITING_USER"
        )
        state["goal_status"] = "AWAITING_USER"
        epic_blocker = self.make_blocker(
            blocker_id=epic_blocker_id,
            owner="USER",
            condition_code="POLICY_CHANGE_REQUIRED",
            blocks_goal_id=goals.EXPECTED_EPIC_GOAL_IDS["EPIC-02"],
            return_leaf_goal_id=old_leaf_id,
            prompt="EPIC-02 정책 변경 승인이 필요합니다.",
        )
        leaf_blocker = self.make_blocker(
            blocker_id=leaf_blocker_id,
            owner="USER",
            condition_code="POLICY_CHANGE_REQUIRED",
            blocks_goal_id=old_leaf_id,
            return_leaf_goal_id=old_leaf_id,
            prompt="현재 Work Item의 정책 변경 승인이 필요합니다.",
        )
        state["blockers_by_goal"] = {
            goals.EXPECTED_EPIC_GOAL_IDS["EPIC-02"]: [epic_blocker],
            old_leaf_id: [leaf_blocker],
        }
        state["pending_questions"] = [
            {
                "blocker_id": leaf_blocker_id,
                "condition_code": "POLICY_CHANGE_REQUIRED",
                "owner": "USER",
                "blocks_goal_id": old_leaf_id,
                "prompt": "현재 Work Item의 정책 변경 승인이 필요합니다.",
            }
        ]
        state["open_question_count"] = 2
        self.append_event(
            checkpoint,
            root=root,
            event_type="BLOCKER_RECORDED",
            current_leaf_goal_id=old_leaf_id,
            from_status="IN_PROGRESS",
            to_status="AWAITING_USER",
            extra={"blocker_ids": [epic_blocker_id, leaf_blocker_id]},
        )

        old_backlog_path = goals.canonical_binding_map(checkpoint)[
            "IMPLEMENTATION_BACKLOG"
        ]["path"]
        backlog_path = "docs/control/audits/test-epic03-bypass-backlog.json"
        backlog_id = "TEST-EPIC03-BYPASS-BACKLOG"
        backlog = goals.load_json(root / old_backlog_path)
        predecessor_backlog_id = backlog["metadata"].get("backlog_id")
        backlog["metadata"]["backlog_id"] = backlog_id
        backlog["metadata"]["version"] = "0.8.1-test"
        backlog["metadata"]["predecessor_backlog_id"] = predecessor_backlog_id
        backlog["next_single_action"] = {
            "epic_id": "EPIC-03",
            "work_item_id": "EPIC-03-FP047-ACCOUNT-SESSION",
            "source_policy_id": "FP-047",
            "gap_id": "GAP-056",
            "status": "DEPENDENCY_READY_BYPASS",
            "action": "독립적으로 준비 가능한 계정 세션 작업을 진행한다.",
        }
        self.write_json(root / backlog_path, backlog)
        self.update_binding(
            checkpoint,
            root=root,
            role="IMPLEMENTATION_BACKLOG",
            path=backlog_path,
            document_id=backlog_id,
        )

        new_goal_id = "WS-GOAL-A-EPIC-03-FP-047-R001"
        new_goal_path = self.materialize_work_item(
            root,
            goal_id=new_goal_id,
            relative_path=(
                "docs/control/goals/walksafe-completion-v1/work-items/a/"
                "epic-03-fp047-account-session-r001.md"
            ),
            phase_id="A",
            parent_goal_id=goals.EXPECTED_EPIC_GOAL_IDS["EPIC-03"],
            sequence=1,
            work_item_id="EPIC-03-FP047-ACCOUNT-SESSION",
            source_policy_ids=["FP-047"],
            gap_ids=["GAP-056"],
            dependencies=[goals.EXPECTED_EPIC_GOAL_IDS["EPIC-01"]],
            materialized_from_role="IMPLEMENTATION_BACKLOG",
            materialized_from_path=backlog_path,
            materialized_from_document_id=backlog_id,
            predecessor_goal_id=old_leaf_id,
            predecessor_goal_path=old_leaf_path,
        )
        state["status_by_goal"][goals.EXPECTED_EPIC_GOAL_IDS["EPIC-03"]] = (
            "IN_PROGRESS"
        )
        state["status_by_goal"][new_goal_id] = "IN_PROGRESS"
        state["materialized_child_goal_ids_by_parent"][
            goals.EXPECTED_EPIC_GOAL_IDS["EPIC-03"]
        ] = [new_goal_id]
        state["current_epic_goal_id"] = goals.EXPECTED_EPIC_GOAL_IDS["EPIC-03"]
        state["current_leaf_goal_id"] = new_goal_id
        state["current_leaf_goal_path"] = new_goal_path
        state["current_work_item_id"] = "EPIC-03-FP047-ACCOUNT-SESSION"
        state["current_leaf_source"] = "IMPLEMENTATION_BACKLOG"
        state["goal_status"] = "IN_PROGRESS"
        state["temporary_dependency_ready_bypass"] = {
            "current_goal_id": goals.EXPECTED_EPIC_GOAL_IDS["EPIC-03"],
            "return_goal_ids": [goals.EXPECTED_EPIC_GOAL_IDS["EPIC-02"]],
            "blocked_leaf_goal_ids": [old_leaf_id],
            "return_leaf_goal_id": old_leaf_id,
            "blocker_ids": [epic_blocker_id, leaf_blocker_id],
            "resolution_required": True,
        }
        state["pending_questions"] = []
        checkpoint["current_work"]["epic_id"] = "EPIC-03"
        checkpoint["current_work"]["work_item_id"] = state["current_work_item_id"]
        self.append_event(
            checkpoint,
            root=root,
            event_type="GOAL_BYPASSED",
            current_leaf_goal_id=new_goal_id,
            from_status="AWAITING_USER",
            to_status="IN_PROGRESS",
            evidence_refs=["IMPLEMENTATION_BACKLOG"],
            extra={
                "blocked_leaf_goal_ids": [old_leaf_id],
                "blocker_ids": [epic_blocker_id, leaf_blocker_id],
                "return_goal_ids": [
                    goals.EXPECTED_EPIC_GOAL_IDS["EPIC-02"]
                ],
            },
        )
        self.refresh_goal_manifest(checkpoint, root)

    def return_from_dependency_ready_bypass(
        self,
        checkpoint: dict,
        root: Path,
    ) -> None:
        state = checkpoint["goal_execution"]
        bypass = state["temporary_dependency_ready_bypass"]
        bypass_leaf_id = state["current_leaf_goal_id"]
        return_leaf_id = bypass["return_leaf_goal_id"]
        return_leaf_path = self.goal_path_by_id(root)[return_leaf_id]
        return_metadata, _ = goals.parse_goal(root / return_leaf_path)
        blocker_event_sha256 = next(
            event["event_sha256"]
            for event in state["transition_history"]
            if event["event_type"] == "BLOCKER_RECORDED"
        )
        blockers = [
            blocker
            for rows in state["blockers_by_goal"].values()
            for blocker in rows
        ]

        completion_ref = self.create_work_item_completion_receipt(
            checkpoint,
            root,
            goal_id=bypass_leaf_id,
        )
        state["status_by_goal"][bypass_leaf_id] = "COMPLETE_AT_TARGET"
        state["completion_evidence_by_goal"][bypass_leaf_id] = [
            completion_ref
        ]
        state["goal_status"] = "COMPLETE_AT_TARGET"
        self.append_event(
            checkpoint,
            root=root,
            event_type="GOAL_COMPLETED",
            current_leaf_goal_id=bypass_leaf_id,
            from_status="IN_PROGRESS",
            to_status="COMPLETE_AT_TARGET",
            evidence_refs=[completion_ref],
            extra={
                "completion_receipt_binding": (
                    self.completion_binding_snapshot(
                        checkpoint,
                        completion_ref,
                    )
                )
            },
        )

        resolutions = [
            self.create_blocker_resolution(
                checkpoint,
                root,
                blocker=blocker,
                blocker_event_sha256=blocker_event_sha256,
            )
            for blocker in blockers
        ]
        resolution_refs = [
            resolution["resolution_receipt_ref"]
            for resolution in resolutions
        ]
        resolved_ids = [
            resolution["blocker_id"] for resolution in resolutions
        ]
        state["blockers_by_goal"] = {}
        state["pending_questions"] = []
        state["open_question_count"] = 0
        state["blocker_resolution_history"] = resolutions
        state["status_by_goal"][
            goals.EXPECTED_EPIC_GOAL_IDS["EPIC-02"]
        ] = "READY"
        state["status_by_goal"][return_leaf_id] = "READY"
        self.append_event(
            checkpoint,
            root=root,
            event_type="BLOCKER_RESOLVED",
            current_leaf_goal_id=bypass_leaf_id,
            from_status="COMPLETE_AT_TARGET",
            to_status="COMPLETE_AT_TARGET",
            evidence_refs=resolution_refs,
            extra={"resolved_blocker_ids": resolved_ids},
        )

        source_path = return_metadata["materialized_from_path"]
        source = goals.load_json(root / source_path)
        self.update_binding(
            checkpoint,
            root=root,
            role="IMPLEMENTATION_BACKLOG",
            path=source_path,
            document_id=source["metadata"]["backlog_id"],
        )
        state["status_by_goal"][
            goals.EXPECTED_EPIC_GOAL_IDS["EPIC-03"]
        ] = "PLANNED"
        state["status_by_goal"][
            goals.EXPECTED_EPIC_GOAL_IDS["EPIC-02"]
        ] = "IN_PROGRESS"
        state["status_by_goal"][return_leaf_id] = "IN_PROGRESS"
        state["current_epic_goal_id"] = goals.EXPECTED_EPIC_GOAL_IDS[
            "EPIC-02"
        ]
        state["current_leaf_goal_id"] = return_leaf_id
        state["current_leaf_goal_path"] = return_leaf_path
        state["current_work_item_id"] = return_metadata["work_item_id"]
        state["current_leaf_source"] = "IMPLEMENTATION_BACKLOG"
        state["goal_status"] = "IN_PROGRESS"
        state["temporary_dependency_ready_bypass"] = None
        checkpoint["current_work"]["epic_id"] = "EPIC-02"
        checkpoint["current_work"]["work_item_id"] = return_metadata[
            "work_item_id"
        ]
        self.append_event(
            checkpoint,
            root=root,
            event_type="GOAL_RESUMED",
            current_leaf_goal_id=return_leaf_id,
            from_status="COMPLETE_AT_TARGET",
            to_status="IN_PROGRESS",
            evidence_refs=resolution_refs,
            extra={
                "return_from_bypass": True,
                "resolved_blocker_ids": resolved_ids,
            },
        )

    def fixture_root(self):
        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        package_source = ROOT / goals.PACKAGE_RELATIVE
        package_target = root / goals.PACKAGE_RELATIVE
        package_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(package_source, package_target)

        checkpoint_source = ROOT / CHECKPOINT_RELATIVE
        checkpoint_target = root / CHECKPOINT_RELATIVE
        checkpoint_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(checkpoint_source, checkpoint_target)

        checkpoint = goals.load_json(checkpoint_source)
        static_manifest_relative = getattr(
            goals,
            "STATIC_PLAN_MANIFEST_RELATIVE",
            None,
        )
        if static_manifest_relative is not None:
            static_manifest_source = ROOT / static_manifest_relative
            static_manifest_target = root / static_manifest_relative
            if (
                static_manifest_source.is_file()
                and not static_manifest_target.is_file()
            ):
                static_manifest_target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(static_manifest_source, static_manifest_target)
        for binding in goals.canonical_binding_map(checkpoint).values():
            source = ROOT / binding["path"]
            target = root / binding["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

        authority_hash = self.install_authority_roster(checkpoint, root)
        manifest_hash, initial_event_hash = self.rebase_fixture_trust_anchors(
            checkpoint,
            root,
        )
        self.write_checkpoint(checkpoint, root)
        previous_manifest_hash = goals.EXPECTED_STATIC_PLAN_MANIFEST_SHA256
        previous_initial_event_hash = goals.EXPECTED_INITIAL_TRANSITION_EVENT_SHA256
        previous_history_head_hash = (
            goals.EXPECTED_TRANSITION_HISTORY_HEAD_SHA256
        )
        previous_authority_hash = goals.EXPECTED_AUTHORITY_ROSTER_SHA256
        previous_receipt_anchors = dict(
            goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE
        )

        class FixtureContext:
            def __enter__(self_nonlocal):
                goals.EXPECTED_STATIC_PLAN_MANIFEST_SHA256 = manifest_hash
                goals.EXPECTED_INITIAL_TRANSITION_EVENT_SHA256 = initial_event_hash
                goals.EXPECTED_TRANSITION_HISTORY_HEAD_SHA256 = (
                    initial_event_hash
                )
                goals.EXPECTED_AUTHORITY_ROSTER_SHA256 = authority_hash
                goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE = {}
                return root

            def __exit__(self_nonlocal, exc_type, exc_value, traceback):
                goals.EXPECTED_STATIC_PLAN_MANIFEST_SHA256 = previous_manifest_hash
                goals.EXPECTED_INITIAL_TRANSITION_EVENT_SHA256 = (
                    previous_initial_event_hash
                )
                goals.EXPECTED_TRANSITION_HISTORY_HEAD_SHA256 = (
                    previous_history_head_hash
                )
                goals.EXPECTED_AUTHORITY_ROSTER_SHA256 = previous_authority_hash
                goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE = (
                    previous_receipt_anchors
                )
                temp.cleanup()

        return FixtureContext()

    def install_authority_roster(self, checkpoint: dict, root: Path) -> str:
        evidence_types = [
            *goals.REQUIRED_PHASE_B_EVIDENCE_STATUS,
            *goals.REQUIRED_PHASE_C_EVIDENCE_STATUS,
            *goals.REQUIRED_PHASE_D_EVIDENCE_STATUS,
            *goals.REOPEN_TRIGGER_EVIDENCE_TYPES,
            *goals.BLOCKER_RESOLUTION_EVIDENCE_TYPES,
        ]
        actors = []
        for actor_id, actor_role, authority, group in (
            ("TEST-EXECUTOR", "TEST_OPERATOR", "EXECUTE", "GROUP-EXECUTOR"),
            ("TEST-REVIEWER", "TEST_REVIEWER", "REVIEW", "GROUP-REVIEWER"),
            ("TEST-APPROVER", "TEST_APPROVER", "APPROVE", "GROUP-APPROVER"),
            ("TEST-RECIPIENT", "DELIVERY_RECIPIENT", "ACCEPT", "GROUP-RECIPIENT"),
            ("TEST-TRANSFEROR", "SERVICE_TRANSFEROR", "TRANSFER", "GROUP-TRANSFEROR"),
            ("TEST-TRANSFEREE", "SERVICE_OWNER", "ACCEPT", "GROUP-TRANSFEREE"),
            ("TEST-PROJECT-OWNER", "PROJECT_OWNER", "CLOSE", "GROUP-PROJECT"),
            ("TEST-ACCEPTING-OWNER", "ACCEPTING_OWNER", "ACCEPT", "GROUP-ACCEPTING"),
            (
                "TEST-BLOCKER-RESOLVER",
                "BLOCKER_RESOLVER",
                "RESOLVE",
                "GROUP-BLOCKER-RESOLVER",
            ),
        ):
            actors.append(
                {
                    "id": actor_id,
                    "allowed_roles": [actor_role],
                    "allowed_authorities": [authority],
                    "permitted_evidence_types": evidence_types,
                    "independence_group": group,
                }
            )
        roster = {
            "schema_version": "1.0",
            "document_id": "TEST-AUTHORITY-ROSTER",
            "status": "APPROVED",
            "approved_at": "2026-07-23T09:00:00+09:00",
            "actors": actors,
        }
        relative = "docs/control/test-evidence/authority-roster.json"
        self.write_json(root / relative, roster)
        roster_hash = goals.sha256_file(root / relative)
        checkpoint["canonical_bindings"].append(
            {
                "role": "AUTHORITY_ROSTER",
                "path": relative,
                "document_id": roster["document_id"],
                "file_sha256": roster_hash,
            }
        )
        return roster_hash

    def remove_authority_actor(
        self,
        checkpoint: dict,
        root: Path,
        *,
        actor_id: str,
    ) -> None:
        binding = goals.canonical_binding_map(checkpoint)[
            "AUTHORITY_ROSTER"
        ]
        roster = goals.load_json(root / binding["path"])
        roster["actors"] = [
            actor for actor in roster["actors"] if actor["id"] != actor_id
        ]
        self.write_json(root / binding["path"], roster)
        new_hash = goals.sha256_file(root / binding["path"])
        for item in checkpoint["canonical_bindings"]:
            if item["role"] == "AUTHORITY_ROSTER":
                item["file_sha256"] = new_hash
        goals.EXPECTED_AUTHORITY_ROSTER_SHA256 = new_hash

    def rebase_fixture_trust_anchors(
        self,
        checkpoint: dict,
        root: Path,
    ) -> tuple[str, str]:
        manifest_path = root / goals.STATIC_PLAN_MANIFEST_RELATIVE
        manifest = goals.load_json(manifest_path)
        for item in manifest["protected_files"]:
            item["sha256"] = goals.sha256_file(root / item["path"])
        self.write_json(manifest_path, manifest)
        manifest_hash = goals.sha256_file(manifest_path)

        state = checkpoint["goal_execution"]
        state["static_plan_manifest_sha256"] = manifest_hash
        event = state["transition_history"][0]
        event["static_plan_manifest_sha256"] = manifest_hash
        event["current_leaf_content_sha256"] = goals.sha256_file(
            root / state["current_leaf_goal_path"]
        )
        event["status_changes"] = dict(state["status_by_goal"])
        event["pointers_after"] = {
            field: state.get(field, "")
            for field in goals.TRANSITION_POINTER_FIELDS
        }
        event["blockers_after"] = {}
        event["blocker_resolution_ids_after"] = []
        event["bypass_after"] = None
        event["previous_event_sha256"] = ""
        event["event_sha256"] = goals.event_sha256(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        self.rehash(checkpoint, root)
        return manifest_hash, event["event_sha256"]

    def rehash(self, checkpoint: dict, root: Path) -> None:
        state = checkpoint["goal_execution"]
        path_hash, content_hash = goals.path_and_content_hashes(
            root,
            state["managed_goal_paths"],
        )
        state["path_set_sha256"] = path_hash
        state["content_set_sha256"] = content_hash

    def rehash_event_chain(self, checkpoint: dict) -> None:
        state = checkpoint["goal_execution"]
        previous_hash = ""
        for event in state["transition_history"]:
            event["previous_event_sha256"] = previous_hash
            event["event_sha256"] = goals.event_sha256(event)
            previous_hash = event["event_sha256"]
        state["transition_history_anchor_sha256"] = previous_hash

    def append_event(
        self,
        checkpoint: dict,
        *,
        root: Path,
        event_type: str,
        current_leaf_goal_id: str,
        from_status: str | None = None,
        to_status: str | None = None,
        evidence_refs: list[str] | None = None,
        extra: dict | None = None,
    ) -> None:
        state = checkpoint["goal_execution"]
        history = state["transition_history"]
        previous_leaf_goal_id = history[-1].get("current_leaf_goal_id", "")
        goal_paths = self.goal_path_by_id(root)

        def goal_hash(goal_id: str) -> str:
            relative = goal_paths.get(goal_id)
            return goals.sha256_file(root / relative) if relative else ""

        replay_statuses: dict[str, str] = {}
        for prior_event in history:
            changes = prior_event.get("status_changes", {})
            if isinstance(changes, dict):
                replay_statuses.update(changes)
        status_changes = {
            goal_id: status
            for goal_id, status in state.get("status_by_goal", {}).items()
            if replay_statuses.get(goal_id) != status
        }
        previous_occurred_at = goals.parse_iso_datetime(
            history[-1].get("occurred_at")
        )
        self.assertIsNotNone(previous_occurred_at)
        event_time_floor = previous_occurred_at + timedelta(minutes=1)
        targets = goals.evidence_reference_targets(
            goals.canonical_binding_map(checkpoint)
        )
        evidence_times = []
        for reference in evidence_refs or []:
            binding = targets.get(reference, {})
            evidence_path = goals.resolve_safe_repo_file(
                root,
                binding.get("path"),
            )
            if evidence_path is None:
                continue
            try:
                payload = goals.load_json(evidence_path)
            except (OSError, ValueError, json.JSONDecodeError):
                continue
            for field in (
                "generated_at",
                "decided_at",
                "completed_at",
                "accepted_at",
                "closed_at",
            ):
                timestamp = goals.parse_iso_datetime(payload.get(field))
                if timestamp is not None:
                    evidence_times.append(timestamp)
        for blocker_rows in state.get("blockers_by_goal", {}).values():
            if not isinstance(blocker_rows, list):
                continue
            for blocker in blocker_rows:
                if isinstance(blocker, dict):
                    timestamp = goals.parse_iso_datetime(
                        blocker.get("created_at")
                    )
                    if timestamp is not None:
                        evidence_times.append(timestamp)
        if evidence_times:
            event_time_floor = max(
                event_time_floor,
                max(evidence_times) + timedelta(minutes=1),
            )
        occurred_at = event_time_floor.isoformat(timespec="seconds")
        event = {
            "sequence": len(history) + 1,
            "event_id": f"TEST-{event_type}-{len(history) + 1:03d}",
            "event_type": event_type,
            "occurred_on": event_time_floor.date().isoformat(),
            "occurred_at": occurred_at,
            "previous_leaf_goal_id": previous_leaf_goal_id,
            "previous_leaf_content_sha256": goal_hash(previous_leaf_goal_id),
            "current_leaf_goal_id": current_leaf_goal_id,
            "current_leaf_content_sha256": goal_hash(current_leaf_goal_id),
            "from_status": (
                from_status
                if from_status is not None
                else history[-1].get("to_status", "READY")
            ),
            "to_status": (
                to_status
                if to_status is not None
                else state.get("goal_status", "READY")
            ),
            "static_plan_manifest_sha256": state.get(
                "static_plan_manifest_sha256", ""
            ),
            "status_changes": status_changes,
            "pointers_after": {
                field: state.get(field, "")
                for field in goals.TRANSITION_POINTER_FIELDS
            },
            "blockers_after": json.loads(
                json.dumps(
                    state.get("blockers_by_goal", {}),
                    ensure_ascii=False,
                )
            ),
            "blocker_resolution_ids_after": [
                item["blocker_id"]
                for item in state.get("blocker_resolution_history", [])
                if isinstance(item, dict)
                and isinstance(item.get("blocker_id"), str)
            ],
            "bypass_after": json.loads(
                json.dumps(
                    state.get("temporary_dependency_ready_bypass"),
                    ensure_ascii=False,
                )
            ),
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": evidence_refs or ["IMPLEMENTATION_BACKLOG"],
            "previous_event_sha256": history[-1]["event_sha256"],
        }
        if extra:
            event.update(extra)
        event["event_sha256"] = goals.event_sha256(event)
        history.append(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        goals.EXPECTED_TRANSITION_HISTORY_HEAD_SHA256 = event["event_sha256"]

    def materialize_work_item(
        self,
        root: Path,
        *,
        goal_id: str,
        relative_path: str,
        phase_id: str,
        parent_goal_id: str,
        sequence: int,
        work_item_id: str,
        source_policy_ids: list[str],
        gap_ids: list[str],
        dependencies: list[str],
        materialized_from_role: str,
        materialized_from_path: str,
        materialized_from_document_id: str,
        predecessor_goal_id: str = "",
        predecessor_goal_path: str = "",
        supersedes_goal_id: str = "",
        supersedes_goal_path: str = "",
        reopen_reason: str = "",
        reopen_evidence_refs: list[str] | None = None,
    ) -> str:
        predecessor_hash = (
            goals.sha256_file(root / predecessor_goal_path)
            if predecessor_goal_path
            else ""
        )
        supersedes_hash = (
            goals.sha256_file(root / supersedes_goal_path)
            if supersedes_goal_path
            else ""
        )
        source_hash = goals.sha256_file(root / materialized_from_path)
        target_completion_level = {
            "A": "INTERNAL_POLICY_CONFORMANCE_REASSESSED",
            "B": "FORMAL_VERIFICATION_ACTION_COMPLETE",
            "C": "RELEASE_DELIVERY_ACTION_COMPLETE",
            "D": "HANDOVER_CLOSURE_ACTION_COMPLETE",
        }[phase_id]
        roles = [
            "POLICY_BASELINE",
            "ARTIFACT_APPLICATION_RECEIPT",
            "ARTIFACT_REGISTER",
            "ARTIFACT_CHANGE_LOG",
            "REQUIREMENTS_TRACEABILITY",
            "DESIGN_TRACEABILITY",
            "MODULE_REGISTER",
            "PLANNED_TEST_CASES",
            "IMPLEMENTATION_GAP",
            "IMPLEMENTATION_BACKLOG",
        ]
        text = f"""+++
schema_version = "1.0"
goal_id = "{goal_id}"
goal_kind = "WORK_ITEM"
document_version = "1.0.0"
phase_id = "{phase_id}"
parent_goal_id = "{parent_goal_id}"
sequence = {sequence}
initial_status = "READY"
target_completion_level = "{target_completion_level}"
next_goal_id = ""
return_goal_id = "{parent_goal_id}"
work_item_id = "{work_item_id}"
dependencies = {json.dumps(dependencies)}
child_goal_ids = []
source_policy_ids = {json.dumps(source_policy_ids)}
gap_ids = {json.dumps(gap_ids)}
canonical_input_roles = {json.dumps(roles)}
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "{materialized_from_role}"
materialized_from_path = "{materialized_from_path}"
materialized_from_document_id = "{materialized_from_document_id}"
materialized_from_sha256 = "{source_hash}"
predecessor_goal_id = "{predecessor_goal_id}"
predecessor_goal_content_sha256 = "{predecessor_hash}"
supersedes_goal_id = "{supersedes_goal_id}"
supersedes_goal_content_sha256 = "{supersedes_hash}"
reopen_reason = "{reopen_reason}"
reopen_evidence_refs = {json.dumps(reopen_evidence_refs or [])}
+++

# Test Work Item

## 목표

동적 successor 전환을 검증한다.

## 정본 입력

source 문서와 hash를 사용한다.

## 범위와 제외

저장소 내부 검증만 포함한다.

## 단계별 실행

1. 준비하고 검증한다.

## 검증

정책·Gap·부모·source를 확인한다.

## 완료 기준

내부 목표수준을 충족한다.

## 질문·중단 조건

Master 정책을 상속한다.

## 완료 후 인계

원자적으로 다음 Goal로 전환한다.
"""
        path = root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return relative_path

    def goal_path_by_id(self, root: Path) -> dict[str, str]:
        _, goal_paths = goals.discover_package_paths(root)
        result: dict[str, str] = {}
        for relative in goal_paths:
            metadata, _ = goals.parse_goal(root / relative)
            goal_id = metadata.get("goal_id")
            if isinstance(goal_id, str):
                result[goal_id] = relative
        return result

    def refresh_goal_manifest(self, checkpoint: dict, root: Path) -> None:
        all_paths, goal_paths = goals.discover_package_paths(root)
        state = checkpoint["goal_execution"]
        state["managed_goal_paths"] = all_paths
        state["goal_document_paths"] = goal_paths
        state["managed_goal_path_count"] = len(all_paths)
        state["goal_document_count"] = len(goal_paths)
        self.rehash(checkpoint, root)

    def update_binding(
        self,
        checkpoint: dict,
        *,
        root: Path,
        role: str,
        path: str,
        document_id: str,
    ) -> None:
        for binding in checkpoint["canonical_bindings"]:
            if binding["role"] == role:
                binding["path"] = path
                binding["document_id"] = document_id
                binding["file_sha256"] = goals.sha256_file(root / path)
                return
        self.fail(f"missing binding: {role}")

    def create_work_item_completion_receipt(
        self,
        checkpoint: dict,
        root: Path,
        *,
        goal_id: str,
    ) -> str:
        state = checkpoint["goal_execution"]
        goal_path = self.goal_path_by_id(root)[goal_id]
        metadata, _ = goals.parse_goal(root / goal_path)
        start_event = next(
            (
                event
                for event in reversed(state["transition_history"])
                if event.get("status_changes", {}).get(goal_id)
                == "IN_PROGRESS"
            ),
            None,
        )
        self.assertIsNotNone(start_event)
        start_time = goals.parse_iso_datetime(start_event["occurred_at"])
        self.assertIsNotNone(start_time)
        execution_started_at = start_time + timedelta(seconds=5)
        observed_at = start_time + timedelta(seconds=20)
        completed_at = start_time + timedelta(seconds=30)
        reviewed_at = start_time + timedelta(seconds=40)
        generated_at = start_time + timedelta(seconds=50)

        def timestamp(value) -> str:
            return value.isoformat(timespec="seconds")

        token = goal_id.lower()
        result_evidence = []
        for kind in (
            "IMPLEMENTATION_RECORD",
            "VERIFICATION_RESULT",
            "SUCCESSOR_TRACE",
        ):
            relative = (
                "docs/control/test-evidence/work-items/"
                f"{token}-{kind.lower().replace('_', '-')}.json"
            )
            self.write_json(
                root / relative,
                {
                    "goal_id": goal_id,
                    "kind": kind,
                    "status": "PASS",
                    "observed_at": timestamp(observed_at),
                },
            )
            result_evidence.append(
                {
                    "kind": kind,
                    "path": relative,
                    "sha256": goals.sha256_file(root / relative),
                }
            )
        role = f"WORK_ITEM_COMPLETION::{goal_id}"
        receipt = {
            "schema_version": "1.0",
            "document_id": f"TEST-WORK-ITEM-COMPLETION-{goal_id}",
            "evidence_type": goals.WORK_ITEM_EXECUTION_EVIDENCE_TYPE,
            "status": "ACCEPTED",
            "result": "PASS",
            "target_goal_id": goal_id,
            "target_goal_content_sha256": goals.sha256_file(
                root / goal_path
            ),
            "work_item_id": metadata["work_item_id"],
            "source_policy_ids": metadata["source_policy_ids"],
            "gap_ids": metadata["gap_ids"],
            "target_completion_level": metadata[
                "target_completion_level"
            ],
            "execution_start_event_sha256": start_event["event_sha256"],
            "execution_window": {
                "started_at": timestamp(execution_started_at),
                "ended_at": timestamp(completed_at),
            },
            "completed_at": timestamp(completed_at),
            "generated_at": timestamp(generated_at),
            "executor": self.receipt_actor("executor"),
            "reviewer": self.receipt_actor(
                "reviewer",
                require_decision=True,
                decided_at=timestamp(reviewed_at),
            ),
            "result_evidence": result_evidence,
        }
        relative = (
            "docs/control/test-evidence/work-items/"
            f"{token}-completion-receipt.json"
        )
        self.write_json(root / relative, receipt)
        checkpoint["canonical_bindings"].append(
            {
                "role": role,
                "path": relative,
                "document_id": receipt["document_id"],
                "identity_json_path": "document_id",
                "file_sha256": goals.sha256_file(root / relative),
                "mutable": False,
            }
        )
        return role

    def completion_binding_snapshot(
        self,
        checkpoint: dict,
        role: str,
    ) -> dict:
        binding = goals.canonical_binding_map(checkpoint)[role]
        return {
            field: binding[field]
            for field in ("role", "document_id", "path", "file_sha256")
        }

    def complete_active_work_item(
        self,
        checkpoint: dict,
        root: Path,
        *,
        completed_ancestor_ids: list[str] | None = None,
        ancestor_evidence_refs: list[str] | None = None,
    ) -> None:
        state = checkpoint["goal_execution"]
        leaf_id = state["current_leaf_goal_id"]
        leaf_ref = self.create_work_item_completion_receipt(
            checkpoint,
            root,
            goal_id=leaf_id,
        )
        state["status_by_goal"][leaf_id] = "COMPLETE_AT_TARGET"
        state["completion_evidence_by_goal"][leaf_id] = [leaf_ref]
        event_refs = {leaf_ref}
        for goal_id in completed_ancestor_ids or []:
            refs = list(ancestor_evidence_refs or ["IMPLEMENTATION_BACKLOG"])
            state["status_by_goal"][goal_id] = "COMPLETE_AT_TARGET"
            state["completion_evidence_by_goal"][goal_id] = refs
            event_refs.update(refs)
        state["goal_status"] = "COMPLETE_AT_TARGET"
        self.append_event(
            checkpoint,
            root=root,
            event_type="GOAL_COMPLETED",
            current_leaf_goal_id=leaf_id,
            from_status="IN_PROGRESS",
            to_status="COMPLETE_AT_TARGET",
            evidence_refs=sorted(event_refs),
            extra={
                "completion_receipt_binding": (
                    self.completion_binding_snapshot(
                        checkpoint,
                        leaf_ref,
                    )
                )
            },
        )

    def start_materialized_successor(
        self,
        checkpoint: dict,
        root: Path,
        *,
        goal_id: str,
        relative_path: str,
        phase_id: str,
        epic_key: str,
        parent_goal_id: str,
        sequence: int,
        work_item_id: str,
        policy_id: str,
        gap_id: str,
        dependencies: list[str],
        materialized_from_role: str,
        materialized_from_path: str,
        materialized_from_document_id: str,
    ) -> None:
        state = checkpoint["goal_execution"]
        previous_goal_id = state["current_leaf_goal_id"]
        previous_goal_path = state["current_leaf_goal_path"]
        new_goal_path = self.materialize_work_item(
            root,
            goal_id=goal_id,
            relative_path=relative_path,
            phase_id=phase_id,
            parent_goal_id=parent_goal_id,
            sequence=sequence,
            work_item_id=work_item_id,
            source_policy_ids=[policy_id],
            gap_ids=[gap_id],
            dependencies=dependencies,
            materialized_from_role=materialized_from_role,
            materialized_from_path=materialized_from_path,
            materialized_from_document_id=materialized_from_document_id,
            predecessor_goal_id=previous_goal_id,
            predecessor_goal_path=previous_goal_path,
        )
        state["status_by_goal"][goal_id] = "IN_PROGRESS"
        state["materialized_child_goal_ids_by_parent"].setdefault(
            parent_goal_id,
            [],
        ).append(goal_id)
        phase_goal_id = goals.EXPECTED_PHASE_IDS[phase_id]
        state["status_by_goal"][goals.EXPECTED_MASTER_ID] = "IN_PROGRESS"
        state["status_by_goal"][phase_goal_id] = "IN_PROGRESS"
        state["status_by_goal"][parent_goal_id] = "IN_PROGRESS"
        state["current_phase_goal_id"] = phase_goal_id
        state["current_epic_goal_id"] = parent_goal_id
        state["current_leaf_goal_id"] = goal_id
        state["current_leaf_goal_path"] = new_goal_path
        state["current_work_item_id"] = work_item_id
        state["current_leaf_source"] = materialized_from_role
        state["goal_status"] = "IN_PROGRESS"
        checkpoint["current_work"]["epic_id"] = epic_key
        checkpoint["current_work"]["work_item_id"] = work_item_id
        self.append_event(
            checkpoint,
            root=root,
            event_type="GOAL_TRANSITION",
            current_leaf_goal_id=goal_id,
            from_status="COMPLETE_AT_TARGET",
            to_status="IN_PROGRESS",
            evidence_refs=["IMPLEMENTATION_BACKLOG"],
        )

    def transition_to_phase_b(self, checkpoint: dict, root: Path) -> None:
        state = checkpoint["goal_execution"]
        self.activate_current_leaf(checkpoint, root)
        old_source_path = goals.canonical_binding_map(checkpoint)[
            "IMPLEMENTATION_BACKLOG"
        ]["path"]
        backlog_path = "docs/control/audits/test-phase-b-backlog.json"
        backlog_id = "TEST-PHASE-B-BACKLOG"
        backlog = goals.load_json(root / old_source_path)
        backlog["metadata"]["backlog_id"] = backlog_id
        for epic in backlog["epics"]:
            if epic["epic_id"] != "EPIC-12":
                epic["current_status"] = epic["target_completion_level"]
        self.write_json(root / backlog_path, backlog)
        self.update_binding(
            checkpoint,
            root=root,
            role="IMPLEMENTATION_BACKLOG",
            path=backlog_path,
            document_id=backlog_id,
        )

        nodes = {
            goal_id: goals.parse_goal(root / relative)[0]
            for goal_id, relative in self.goal_path_by_id(root).items()
        }
        mapping_errors, policy_gap_mapping = goals.load_policy_gap_mapping(
            root,
            goals.canonical_binding_map(checkpoint),
        )
        self.assertEqual(mapping_errors, [])
        current, _ = goals.parse_goal(root / state["current_leaf_goal_path"])
        epic02 = nodes[goals.EXPECTED_EPIC_GOAL_IDS["EPIC-02"]]
        expected_epic02_pairs = [
            (policy_id, policy_gap_mapping[policy_id])
            for policy_id in epic02["source_policy_ids"]
        ]
        corrected_pair = next(
            pair
            for pair in expected_epic02_pairs
            if pair[0] == current["source_policy_ids"][0]
        )
        if (
            current["source_policy_ids"][0],
            current["gap_ids"][0],
        ) != corrected_pair:
            old_goal_id = state["current_leaf_goal_id"]
            old_goal_path = state["current_leaf_goal_path"]
            self.complete_active_work_item(checkpoint, root)
            reopen_ref = self.create_reopen_trigger(
                checkpoint,
                root,
                target_goal_id=old_goal_id,
                work_item_id=current["work_item_id"],
            )
            completion_refs = state["completion_evidence_by_goal"].pop(
                old_goal_id
            )
            state["archived_completion_evidence_by_goal"][
                old_goal_id
            ] = completion_refs
            state["status_by_goal"][old_goal_id] = "SUPERSEDED"
            state["goal_status"] = "SUPERSEDED"
            self.append_event(
                checkpoint,
                root=root,
                event_type="GOAL_SUPERSEDED",
                current_leaf_goal_id=old_goal_id,
                from_status="COMPLETE_AT_TARGET",
                to_status="SUPERSEDED",
                evidence_refs=[reopen_ref],
                extra={
                    "supersedes_goal_id": old_goal_id,
                    "reopen_evidence_refs": [reopen_ref],
                },
            )
            corrected_goal_id = (
                "WS-GOAL-A-EPIC-02-FP-018-R002"
            )
            corrected_path = self.materialize_work_item(
                root,
                goal_id=corrected_goal_id,
                relative_path=(
                    "docs/control/goals/walksafe-completion-v1/work-items/a/"
                    "epic-02-fp018-walk-state-recovery-r002.md"
                ),
                phase_id="A",
                parent_goal_id=goals.EXPECTED_EPIC_GOAL_IDS["EPIC-02"],
                sequence=current["sequence"] + 1,
                work_item_id=current["work_item_id"],
                source_policy_ids=[corrected_pair[0]],
                gap_ids=[corrected_pair[1]],
                dependencies=current["dependencies"],
                materialized_from_role="IMPLEMENTATION_BACKLOG",
                materialized_from_path=backlog_path,
                materialized_from_document_id=backlog_id,
                predecessor_goal_id=old_goal_id,
                predecessor_goal_path=old_goal_path,
                supersedes_goal_id=old_goal_id,
                supersedes_goal_path=old_goal_path,
                reopen_reason="EPIC 정책·Gap 정본 pair로 초기 Goal을 교정한다.",
                reopen_evidence_refs=[reopen_ref],
            )
            state["status_by_goal"][corrected_goal_id] = "IN_PROGRESS"
            state["materialized_child_goal_ids_by_parent"][
                goals.EXPECTED_EPIC_GOAL_IDS["EPIC-02"]
            ].append(corrected_goal_id)
            state["current_leaf_goal_id"] = corrected_goal_id
            state["current_leaf_goal_path"] = corrected_path
            state["current_work_item_id"] = current["work_item_id"]
            state["goal_status"] = "IN_PROGRESS"
            checkpoint["current_work"]["work_item_id"] = current[
                "work_item_id"
            ]
            self.append_event(
                checkpoint,
                root=root,
                event_type="GOAL_TRANSITION",
                current_leaf_goal_id=corrected_goal_id,
                from_status="SUPERSEDED",
                to_status="IN_PROGRESS",
                evidence_refs=[reopen_ref],
                extra={
                    "supersedes_goal_id": old_goal_id,
                    "reopen_evidence_refs": [reopen_ref],
                },
            )
            current_sequence = current["sequence"] + 1
        else:
            current_sequence = current["sequence"]
        current_pair = corrected_pair
        ordered_specs: list[tuple[str, str, str]] = []
        phase_a_epic_keys = [
            epic_key
            for epic_key in goals.EXPECTED_EXECUTION_ORDER
            if epic_key not in {"EPIC-01", "EPIC-12"}
        ]
        for epic_key in phase_a_epic_keys:
            epic = nodes[goals.EXPECTED_EPIC_GOAL_IDS[epic_key]]
            pairs = [
                (policy_id, policy_gap_mapping[policy_id])
                for policy_id in epic["source_policy_ids"]
            ]
            if epic_key == "EPIC-02":
                pairs = [
                    current_pair,
                    *[pair for pair in pairs if pair != current_pair],
                ]
            ordered_specs.extend(
                (epic_key, policy_id, gap_id)
                for policy_id, gap_id in pairs
            )

        sequence_by_epic = {"EPIC-02": current_sequence + 1}
        for index, (epic_key, policy_id, gap_id) in enumerate(ordered_specs):
            self.assertEqual(
                state["current_epic_goal_id"],
                goals.EXPECTED_EPIC_GOAL_IDS[epic_key],
            )
            next_spec = (
                ordered_specs[index + 1]
                if index + 1 < len(ordered_specs)
                else None
            )
            completed_ancestors: list[str] = []
            if next_spec is None or next_spec[0] != epic_key:
                completed_ancestors.append(
                    goals.EXPECTED_EPIC_GOAL_IDS[epic_key]
                )
            if next_spec is None:
                completed_ancestors.append(goals.EXPECTED_PHASE_IDS["A"])
            self.complete_active_work_item(
                checkpoint,
                root,
                completed_ancestor_ids=completed_ancestors,
            )
            if next_spec is None:
                break
            next_epic_key, next_policy_id, next_gap_id = next_spec
            next_epic_id = goals.EXPECTED_EPIC_GOAL_IDS[next_epic_key]
            next_epic = nodes[next_epic_id]
            sequence = sequence_by_epic.get(next_epic_key, 1)
            sequence_by_epic[next_epic_key] = sequence + 1
            token = next_policy_id.replace("_", "-")
            self.start_materialized_successor(
                checkpoint,
                root,
                goal_id=f"{next_epic_id}-{token}-R001",
                relative_path=(
                    "docs/control/goals/walksafe-completion-v1/work-items/a/"
                    f"{next_epic_key.lower()}-{token.lower()}-r001.md"
                ),
                phase_id="A",
                epic_key=next_epic_key,
                parent_goal_id=next_epic_id,
                sequence=sequence,
                work_item_id=f"{next_epic_key}-{token}",
                policy_id=next_policy_id,
                gap_id=next_gap_id,
                dependencies=next_epic["dependencies"],
                materialized_from_role="IMPLEMENTATION_BACKLOG",
                materialized_from_path=backlog_path,
                materialized_from_document_id=backlog_id,
            )

        phase_b_epic_id = goals.EXPECTED_EPIC_GOAL_IDS["EPIC-12"]
        phase_b_epic = nodes[phase_b_epic_id]
        self.start_materialized_successor(
            checkpoint,
            root,
            goal_id=(
                "WS-GOAL-B-EPIC-12-"
                "GATE-SINGLE-ADMIN-RECOVERY-DRILL-R001"
            ),
            relative_path=(
                "docs/control/goals/walksafe-completion-v1/work-items/b/"
                "epic-12-single-admin-recovery-drill-r001.md"
            ),
            phase_id="B",
            epic_key="EPIC-12",
            parent_goal_id=phase_b_epic_id,
            sequence=1,
            work_item_id="EPIC-12-SINGLE-ADMIN-RECOVERY-DRILL",
            policy_id="GATE-SINGLE-ADMIN-RECOVERY-DRILL",
            gap_id="GAP-068",
            dependencies=phase_b_epic["dependencies"],
            materialized_from_role="PHASE_PLAN",
            materialized_from_path=(
                "docs/control/goals/walksafe-completion-v1/"
                "epics/epic-12-formal-verification-gates.md"
            ),
            materialized_from_document_id=phase_b_epic_id,
        )
        self.refresh_goal_manifest(checkpoint, root)

    def transition_to_phase_c(
        self,
        checkpoint: dict,
        root: Path,
        *,
        structured_receipts: bool = True,
    ) -> None:
        state = checkpoint["goal_execution"]
        old_goal_id = state["current_leaf_goal_id"]
        old_goal_path = state["current_leaf_goal_path"]

        previous_backlog_path = goals.canonical_binding_map(checkpoint)[
            "IMPLEMENTATION_BACKLOG"
        ]["path"]
        backlog = goals.load_json(root / previous_backlog_path)
        backlog_path = "docs/control/audits/test-phase-c-backlog.json"
        previous_backlog_id = backlog["metadata"]["backlog_id"]
        backlog["metadata"]["backlog_id"] = "TEST-PHASE-C-BACKLOG"
        backlog["metadata"]["predecessor_backlog_id"] = previous_backlog_id
        for epic in backlog["epics"]:
            if epic["epic_id"] == "EPIC-12":
                epic["current_status"] = epic["target_completion_level"]
        self.write_json(root / backlog_path, backlog)
        self.update_binding(
            checkpoint,
            root=root,
            role="IMPLEMENTATION_BACKLOG",
            path=backlog_path,
            document_id=backlog["metadata"]["backlog_id"],
        )

        epic_id = goals.EXPECTED_EPIC_GOAL_IDS["EPIC-12"]
        epic_path = self.goal_path_by_id(root)[epic_id]
        epic, _ = goals.parse_goal(root / epic_path)
        mapping_errors, policy_gap_mapping = goals.load_policy_gap_mapping(
            root,
            goals.canonical_binding_map(checkpoint),
        )
        self.assertEqual(mapping_errors, [])
        current, _ = goals.parse_goal(root / state["current_leaf_goal_path"])
        current_pair = (
            current["source_policy_ids"][0],
            current["gap_ids"][0],
        )
        remaining_pairs = [
            (policy_id, policy_gap_mapping[policy_id])
            for policy_id in epic["source_policy_ids"]
            if (policy_id, policy_gap_mapping[policy_id]) != current_pair
        ]
        phase_plan_path = (
            "docs/control/goals/walksafe-completion-v1/"
            "epics/epic-12-formal-verification-gates.md"
        )
        for sequence, (policy_id, gap_id) in enumerate(
            remaining_pairs,
            start=2,
        ):
            self.complete_active_work_item(checkpoint, root)
            token = policy_id.replace("_", "-")
            self.start_materialized_successor(
                checkpoint,
                root,
                goal_id=f"{epic_id}-{token}-R001",
                relative_path=(
                    "docs/control/goals/walksafe-completion-v1/work-items/b/"
                    f"epic-12-{token.lower()}-r001.md"
                ),
                phase_id="B",
                epic_key="EPIC-12",
                parent_goal_id=epic_id,
                sequence=sequence,
                work_item_id=f"EPIC-12-{token}",
                policy_id=policy_id,
                gap_id=gap_id,
                dependencies=epic["dependencies"],
                materialized_from_role="PHASE_PLAN",
                materialized_from_path=phase_plan_path,
                materialized_from_document_id=epic_id,
            )
        old_goal_id = state["current_leaf_goal_id"]
        old_goal_path = state["current_leaf_goal_path"]

        planned_binding = goals.canonical_binding_map(checkpoint)[
            "PLANNED_TEST_CASES"
        ]
        planned = goals.load_json(root / planned_binding["path"])
        planned["metadata"]["approval_status"] = "APPROVED"
        planned["metadata"]["approved_at"] = (
            "2026-07-23T09:40:00+09:00"
        )
        for row in planned["test_cases"]:
            if not row.get("eligible_environment_ids"):
                row["eligible_environment_ids"] = ["TEST-ENV-ANDROID"]
        self.write_json(root / planned_binding["path"], planned)
        for binding in checkpoint["canonical_bindings"]:
            if binding["role"] == "PLANNED_TEST_CASES":
                binding["file_sha256"] = goals.sha256_file(
                    root / binding["path"]
                )

        _, candidate_sha256, _ = self.candidate_fixture(root)
        evidence_roles = list(goals.REQUIRED_PHASE_B_EVIDENCE_STATUS)
        receipt_hashes: dict[str, str] = {}
        for role, status in goals.REQUIRED_PHASE_B_EVIDENCE_STATUS.items():
            evidence_path = (
                f"docs/control/test-evidence/{role.lower().replace('_', '-')}.json"
            )
            receipt = (
                self.phase_b_receipt(
                    root,
                    role=role,
                    status=status,
                    candidate_sha256=candidate_sha256,
                    prerequisite_receipt_sha256=receipt_hashes,
                )
                if structured_receipts
                else {
                    "evidence_type": role,
                    "status": status,
                    "candidate_sha256": candidate_sha256,
                }
            )
            self.write_json(root / evidence_path, receipt)
            receipt_hashes[role] = goals.sha256_file(root / evidence_path)
            goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE[
                role
            ] = receipt_hashes[role]
            checkpoint["canonical_bindings"].append(
                {
                    "role": role,
                    "path": evidence_path,
                    "document_id": f"TEST-{role}",
                    "file_sha256": receipt_hashes[role],
                }
            )

        new_goal_id = "WS-GOAL-C-RELEASE-MANIFEST-R001"
        new_goal_path = self.materialize_work_item(
            root,
            goal_id=new_goal_id,
            relative_path=(
                "docs/control/goals/walksafe-completion-v1/work-items/c/"
                "release-manifest-r001.md"
            ),
            phase_id="C",
            parent_goal_id=goals.EXPECTED_PHASE_IDS["C"],
            sequence=1,
            work_item_id="PHASE-C-RELEASE-MANIFEST",
            source_policy_ids=[],
            gap_ids=[],
            dependencies=[goals.EXPECTED_PHASE_IDS["B"]],
            materialized_from_role="PHASE_PLAN",
            materialized_from_path=(
                "docs/control/goals/walksafe-completion-v1/"
                "30-phase-c-release-delivery.md"
            ),
            materialized_from_document_id="WS-GOAL-PHASE-C",
            predecessor_goal_id=old_goal_id,
            predecessor_goal_path=old_goal_path,
        )

        state["status_by_goal"][new_goal_id] = "PLANNED"
        state["materialized_child_goal_ids_by_parent"][
            goals.EXPECTED_PHASE_IDS["C"]
        ] = [new_goal_id]
        work_item_completion_ref = (
            self.create_work_item_completion_receipt(
                checkpoint,
                root,
                goal_id=old_goal_id,
            )
        )
        for goal_id in (
            goals.EXPECTED_EPIC_GOAL_IDS["EPIC-12"],
            goals.EXPECTED_PHASE_IDS["B"],
        ):
            state["status_by_goal"][goal_id] = "COMPLETE_AT_TARGET"
            state["completion_evidence_by_goal"][goal_id] = evidence_roles
        state["status_by_goal"][old_goal_id] = "COMPLETE_AT_TARGET"
        state["completion_evidence_by_goal"][old_goal_id] = [
            work_item_completion_ref,
            *evidence_roles,
        ]
        state["goal_status"] = "COMPLETE_AT_TARGET"
        self.append_event(
            checkpoint,
            root=root,
            event_type="GOAL_COMPLETED",
            current_leaf_goal_id=old_goal_id,
            from_status="IN_PROGRESS",
            to_status="COMPLETE_AT_TARGET",
            evidence_refs=[work_item_completion_ref, *evidence_roles],
            extra={
                "completion_receipt_binding": (
                    self.completion_binding_snapshot(
                        checkpoint,
                        work_item_completion_ref,
                    )
                )
            },
        )

        for goal_id in (
            goals.EXPECTED_MASTER_ID,
            goals.EXPECTED_PHASE_IDS["C"],
            new_goal_id,
        ):
            state["status_by_goal"][goal_id] = "IN_PROGRESS"
        state["current_phase_goal_id"] = goals.EXPECTED_PHASE_IDS["C"]
        state["current_epic_goal_id"] = ""
        state["current_leaf_goal_id"] = new_goal_id
        state["current_leaf_goal_path"] = new_goal_path
        state["current_work_item_id"] = "PHASE-C-RELEASE-MANIFEST"
        state["current_leaf_source"] = "PHASE_PLAN"
        state["goal_status"] = "IN_PROGRESS"
        state["verification_evidence_refs"] = evidence_roles
        checkpoint["current_work"]["epic_id"] = ""
        checkpoint["current_work"]["work_item_id"] = state["current_work_item_id"]

        approved = checkpoint["approved_state"]
        boundary = checkpoint["verification_boundary"]
        approved["formal_test_not_run_count"] = 0
        approved["remaining_gate_count"] = 0
        approved["release_status"] = "ELIGIBLE"
        boundary["formal_test_not_run_count"] = 0
        boundary["remaining_gate_ids"] = []
        boundary["all_remaining_gate_status"] = "CLOSED"
        boundary["actual_device_test_status"] = "PASS"
        boundary["approved_production_profile_count"] = 1
        boundary["formal_test_pass_claimed"] = True
        boundary["release_eligible"] = True

        self.append_event(
            checkpoint,
            root=root,
            event_type="GOAL_TRANSITION",
            current_leaf_goal_id=new_goal_id,
            from_status="COMPLETE_AT_TARGET",
            to_status="IN_PROGRESS",
            evidence_refs=evidence_roles,
        )
        self.refresh_goal_manifest(checkpoint, root)

    def phase_b_receipt(
        self,
        root: Path,
        *,
        role: str,
        status: str,
        candidate_sha256: str,
        prerequisite_receipt_sha256: dict[str, str],
    ) -> dict:
        receipt = self.common_receipt(
            root,
            evidence_type=role,
            status=status,
            candidate_sha256=candidate_sha256,
            source_goal_id=goals.EXPECTED_PHASE_IDS["B"],
            source_goal_path=(
                "docs/control/goals/walksafe-completion-v1/"
                "20-phase-b-formal-verification.md"
            ),
            raw_record_count=(
                279
                if role == "FORMAL_TEST_REPORT"
                else len(goals.EXPECTED_RELEASE_GATE_IDS)
                if role == "RELEASE_GATE_CLOSURE_RECEIPT"
                else 1
            ),
        )
        raw_path = receipt["raw_evidence"][0]["path"]
        if role == "FORMAL_TEST_REPORT":
            planned_binding_path = (
                "docs/deliverables/06-testing/registers/test-cases.json"
            )
            planned = goals.load_json(root / planned_binding_path)
            receipt["planned_test_cases_binding"] = {
                "role": "PLANNED_TEST_CASES",
                "document_id": "TST-05",
                "path": planned_binding_path,
                "file_sha256": goals.sha256_file(
                    root / planned_binding_path
                ),
                "version": planned["metadata"]["version"],
                "test_case_id_set_sha256": goals.string_set_sha256(
                    [
                        row["test_case_id"]
                        for row in planned["test_cases"]
                    ]
                ),
            }
            receipt["test_summary"] = {
                "total": 279,
                "passed": 279,
                "failed": 0,
                "not_run": 0,
                "not_applicable": 0,
                "p0_open": 0,
                "p1_open": 0,
            }
            receipt["test_cases"] = [
                {
                    "test_case_id": row["test_case_id"],
                    "applicability": "APPLICABLE",
                    "status": "PASS",
                    "candidate_sha256": candidate_sha256,
                    "environment_id": row["eligible_environment_ids"][0],
                    "evidence_types": row["required_evidence_types"],
                    "executor_id": receipt["executor"]["id"],
                    "raw_evidence_refs": [raw_path],
                }
                for row in planned["test_cases"]
            ]
        elif role == "TEST_PLAN_APPROVAL_RECEIPT":
            planned_binding_path = (
                "docs/deliverables/06-testing/registers/test-cases.json"
            )
            planned = goals.load_json(root / planned_binding_path)
            receipt["planned_test_cases_binding"] = {
                "role": "PLANNED_TEST_CASES",
                "document_id": "TST-05",
                "path": planned_binding_path,
                "file_sha256": goals.sha256_file(
                    root / planned_binding_path
                ),
                "version": planned["metadata"]["version"],
                "test_case_id_set_sha256": goals.string_set_sha256(
                    [
                        row["test_case_id"]
                        for row in planned["test_cases"]
                    ]
                ),
            }
            receipt["plan_approved_at"] = planned["metadata"][
                "approved_at"
            ]
        elif role == "ACTUAL_DEVICE_TEST_REPORT":
            receipt["approved_production_profile_count"] = 1
            receipt["tested_candidate_sha256"] = candidate_sha256
            receipt["approved_production_profiles"] = [
                {
                    "profile_id": "TEST-ANDROID-PROFILE-001",
                    "device_model": "Test Android Device",
                    "os_version": "Android Test",
                    "status": "PASS",
                    "raw_evidence_refs": [raw_path],
                }
            ]
        elif role == "RELEASE_GATE_CLOSURE_RECEIPT":
            receipt["gates"] = [
                {
                    "gate_id": gate_id,
                    "status": "CLOSED",
                    "waived": False,
                    "completed_at": "2026-07-23T10:45:00+09:00",
                    "executor_id": receipt["executor"]["id"],
                    "reviewer_id": receipt["reviewer"]["id"],
                    "approver_id": receipt["approver"]["id"],
                    "evidence_refs": [raw_path],
                    **(
                        {
                            "cost_measurement": {
                                "storage_krw": 1000,
                                "request_krw": 1000,
                                "restore_krw": 1000,
                                "vat_krw": 300,
                                "vat_included": True,
                                "currency": "KRW",
                                "monthly_total_krw": 3300,
                            }
                        }
                        if gate_id == "GATE-CLOUD-COST-MEASUREMENT"
                        else {}
                    ),
                }
                for gate_id in sorted(goals.EXPECTED_RELEASE_GATE_IDS)
            ]
        elif role == "RELEASE_ELIGIBILITY_APPROVAL":
            receipt["release_candidate_sha256"] = candidate_sha256
            receipt["eligibility_approved_at"] = (
                "2026-07-23T12:10:00+09:00"
            )
            receipt["prerequisite_receipt_sha256"] = dict(
                prerequisite_receipt_sha256
            )
        return receipt

    def candidate_fixture(
        self,
        root: Path,
    ) -> tuple[str, str, dict[str, str]]:
        component_paths = {
            "android-app": "docs/control/test-evidence/candidate/android-app.json",
            "server": "docs/control/test-evidence/candidate/server.json",
            "model": "docs/control/test-evidence/candidate/model.json",
        }
        component_hashes: dict[str, str] = {}
        components: list[dict[str, str]] = []
        for name, relative in component_paths.items():
            self.write_json(
                root / relative,
                {
                    "component": name,
                    "version": "1.0.0-test",
                    "build_id": "TEST-RELEASE-CANDIDATE-001",
                },
            )
            component_hash = goals.sha256_file(root / relative)
            component_hashes[name] = component_hash
            components.append(
                {
                    "name": name,
                    "path": relative,
                    "sha256": component_hash,
                }
            )
        manifest_path = (
            "docs/control/test-evidence/candidate/release-candidate-manifest.json"
        )
        self.write_json(
            root / manifest_path,
            {
                "schema_version": "1.0",
                "candidate_id": "TEST-RELEASE-CANDIDATE-001",
                "components": components,
            },
        )
        return (
            manifest_path,
            goals.sha256_file(root / manifest_path),
            component_hashes,
        )

    def receipt_actor(
        self,
        actor_name: str,
        *,
        require_decision: bool = False,
        decided_at: str = "2026-07-23T12:00:00+09:00",
    ) -> dict:
        actors = {
            "executor": ("TEST-EXECUTOR", "TEST_OPERATOR", "EXECUTE"),
            "reviewer": ("TEST-REVIEWER", "TEST_REVIEWER", "REVIEW"),
            "approver": ("TEST-APPROVER", "TEST_APPROVER", "APPROVE"),
            "recipient": ("TEST-RECIPIENT", "DELIVERY_RECIPIENT", "ACCEPT"),
            "transferor": ("TEST-TRANSFEROR", "SERVICE_TRANSFEROR", "TRANSFER"),
            "transferee": ("TEST-TRANSFEREE", "SERVICE_OWNER", "ACCEPT"),
            "project_owner": ("TEST-PROJECT-OWNER", "PROJECT_OWNER", "CLOSE"),
            "accepting_owner": (
                "TEST-ACCEPTING-OWNER",
                "ACCEPTING_OWNER",
                "ACCEPT",
            ),
        }
        actor_id, role, authority = actors[actor_name]
        actor = {
            "id": actor_id,
            "role": role,
            "authority": authority,
            "authority_evidence_ref": "AUTHORITY_ROSTER",
        }
        if require_decision:
            actor.update(
                {
                    "decision": "APPROVED",
                    "decided_at": decided_at,
                }
            )
        return actor

    def authority_roster_receipt_fields(self, root: Path) -> dict:
        relative = "docs/control/test-evidence/authority-roster.json"
        roster = goals.load_json(root / relative)
        return {
            "authority_roster_ref": "AUTHORITY_ROSTER",
            "authority_roster_document_id": roster["document_id"],
            "authority_roster_sha256": goals.sha256_file(root / relative),
        }

    def required_check_map(
        self,
        keys: set[str],
        *,
        raw_path: str,
        executed_at: str,
    ) -> tuple[dict[str, dict], dict[str, int]]:
        checks = {
            key: {
                "status": "PASS",
                "executed_at": executed_at,
                "executor_id": "TEST-EXECUTOR",
                "reviewer_id": "TEST-REVIEWER",
                "approver_id": "TEST-APPROVER",
                "evidence_refs": [raw_path],
            }
            for key in sorted(keys)
        }
        return (
            checks,
            {
                "total": len(keys),
                "passed": len(keys),
                "failed": 0,
                "not_run": 0,
            },
        )

    def common_receipt(
        self,
        root: Path,
        *,
        evidence_type: str,
        status: str,
        candidate_sha256: str,
        source_goal_id: str,
        source_goal_path: str,
        required_checks: set[str] | None = None,
        raw_record_count: int = 1,
    ) -> dict:
        manifest_path, actual_candidate_sha256, component_hashes = (
            self.candidate_fixture(root)
        )
        self.assertEqual(
            candidate_sha256,
            actual_candidate_sha256,
            "fixture receipts must reference the actual candidate manifest",
        )
        time_profiles = {
            "TEST_PLAN_APPROVAL_RECEIPT": {
                "started_at": "2026-07-23T09:05:00+09:00",
                "ended_at": "2026-07-23T09:25:00+09:00",
                "collected_at": "2026-07-23T09:15:00+09:00",
                "check_at": "2026-07-23T09:20:00+09:00",
                "reviewed_at": "2026-07-23T09:30:00+09:00",
                "approved_at": "2026-07-23T09:40:00+09:00",
                "generated_at": "2026-07-23T09:50:00+09:00",
            },
            "RELEASE_ELIGIBILITY_APPROVAL": {
                "started_at": "2026-07-23T11:31:00+09:00",
                "ended_at": "2026-07-23T12:00:00+09:00",
                "collected_at": "2026-07-23T11:45:00+09:00",
                "check_at": "2026-07-23T11:50:00+09:00",
                "reviewed_at": "2026-07-23T12:05:00+09:00",
                "approved_at": "2026-07-23T12:10:00+09:00",
                "generated_at": "2026-07-23T12:20:00+09:00",
            },
            "PHASE_C_TECHNICAL_DELIVERY_RECEIPT": {
                "started_at": "2026-07-23T13:00:00+09:00",
                "ended_at": "2026-07-23T14:00:00+09:00",
                "collected_at": "2026-07-23T13:15:00+09:00",
                "check_at": "2026-07-23T13:30:00+09:00",
                "reviewed_at": "2026-07-23T14:10:00+09:00",
                "approved_at": "2026-07-23T14:20:00+09:00",
                "generated_at": "2026-07-23T14:30:00+09:00",
            },
            "PHASE_D_OPERATION_HANDOVER_RECEIPT": {
                "started_at": "2026-07-23T14:40:00+09:00",
                "ended_at": "2026-07-23T15:00:00+09:00",
                "collected_at": "2026-07-23T14:45:00+09:00",
                "check_at": "2026-07-23T14:50:00+09:00",
                "reviewed_at": "2026-07-23T15:10:00+09:00",
                "approved_at": "2026-07-23T15:20:00+09:00",
                "generated_at": "2026-07-23T15:30:00+09:00",
            },
            "PHASE_D_PROJECT_CLOSURE_RECEIPT": {
                "started_at": "2026-07-23T15:40:00+09:00",
                "ended_at": "2026-07-23T16:00:00+09:00",
                "collected_at": "2026-07-23T15:45:00+09:00",
                "check_at": "2026-07-23T15:50:00+09:00",
                "reviewed_at": "2026-07-23T16:10:00+09:00",
                "approved_at": "2026-07-23T16:20:00+09:00",
                "generated_at": "2026-07-23T16:30:00+09:00",
            },
        }
        timeline = time_profiles.get(
            evidence_type,
            {
                "started_at": "2026-07-23T10:00:00+09:00",
                "ended_at": "2026-07-23T11:00:00+09:00",
                "collected_at": "2026-07-23T10:30:00+09:00",
                "check_at": "2026-07-23T10:45:00+09:00",
                "reviewed_at": "2026-07-23T11:10:00+09:00",
                "approved_at": "2026-07-23T11:20:00+09:00",
                "generated_at": "2026-07-23T11:30:00+09:00",
            },
        )
        raw_path = (
            "docs/control/test-evidence/raw/"
            f"{evidence_type.lower().replace('_', '-')}.json"
        )
        self.write_json(
            root / raw_path,
            {
                "evidence_type": evidence_type,
                "candidate_sha256": candidate_sha256,
                "observed_at": "2026-07-23T12:00:00+09:00",
                "records": [
                    {"record_id": index + 1, "result": "PASS"}
                    for index in range(raw_record_count)
                ],
            },
        )
        receipt = {
            "schema_version": "1.0",
            "document_id": f"TEST-{evidence_type}",
            "evidence_type": evidence_type,
            "status": status,
            "candidate_sha256": candidate_sha256,
            **self.authority_roster_receipt_fields(root),
            "generated_at": timeline["generated_at"],
            "executor": self.receipt_actor("executor"),
            "reviewer": self.receipt_actor(
                "reviewer",
                require_decision=True,
                decided_at=timeline["reviewed_at"],
            ),
            "approver": self.receipt_actor(
                "approver",
                require_decision=True,
                decided_at=timeline["approved_at"],
            ),
            "execution_window": {
                "started_at": timeline["started_at"],
                "ended_at": timeline["ended_at"],
            },
            "environment_ids": ["TEST-ENV-ANDROID"],
            "tool_versions": {"test-runner": "1.0.0"},
            "defect_refs": [],
            "residual_risk_refs": [],
            "candidate_manifest": {
                "path": manifest_path,
                "sha256": actual_candidate_sha256,
                "component_hashes": component_hashes,
            },
            "source_goal_id": source_goal_id,
            "source_goal_sha256": goals.sha256_file(root / source_goal_path),
            "raw_evidence": [
                {
                    "path": raw_path,
                    "sha256": goals.sha256_file(root / raw_path),
                    "record_count": raw_record_count,
                    "media_type": "application/json",
                    "collected_at": timeline["collected_at"],
                    "collector": self.receipt_actor("executor"),
                }
            ],
        }
        if required_checks is not None:
            checks, summary = self.required_check_map(
                required_checks,
                raw_path=raw_path,
                executed_at=timeline["check_at"],
            )
            receipt["required_checks"] = checks
            receipt["required_check_summary"] = summary
        return receipt

    def add_receipt_binding(
        self,
        checkpoint: dict,
        root: Path,
        *,
        role: str,
        receipt: dict,
    ) -> str:
        relative = (
            "docs/control/test-evidence/"
            f"{role.lower().replace('_', '-')}.json"
        )
        self.write_json(root / relative, receipt)
        file_sha256 = goals.sha256_file(root / relative)
        externally_attested_roles = (
            set(goals.REQUIRED_PHASE_B_EVIDENCE_STATUS)
            | set(goals.REQUIRED_PHASE_C_EVIDENCE_STATUS)
            | set(goals.REQUIRED_PHASE_D_EVIDENCE_STATUS)
        )
        if role in externally_attested_roles:
            goals.EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE[
                role
            ] = file_sha256
        checkpoint["canonical_bindings"].append(
            {
                "role": role,
                "path": relative,
                "document_id": receipt["document_id"],
                "file_sha256": file_sha256,
            }
        )
        return file_sha256

    def transition_to_phase_d(self, checkpoint: dict, root: Path) -> None:
        state = checkpoint["goal_execution"]
        old_goal_id = state["current_leaf_goal_id"]
        old_goal_path = state["current_leaf_goal_path"]
        candidate_manifest_path, candidate_sha256, _ = self.candidate_fixture(
            root
        )
        role = "PHASE_C_TECHNICAL_DELIVERY_RECEIPT"
        receipt = self.common_receipt(
            root,
            evidence_type=role,
            status="DELIVERED",
            candidate_sha256=candidate_sha256,
            source_goal_id=goals.EXPECTED_PHASE_IDS["C"],
            source_goal_path=(
                "docs/control/goals/walksafe-completion-v1/"
                "30-phase-c-release-delivery.md"
            ),
            required_checks={
                "candidate_identity": "PASS",
                "artifact_signature": "PASS",
                "sbom_provenance_license": "PASS",
                "backup_migration_rollback": "PASS",
                "canary": "PASS",
                "smoke": "PASS",
                "delivery_documents": "PASS",
                "acceptance_record": "PASS",
            },
            raw_record_count=len(goals.PHASE_C_REQUIRED_CHECKS),
        )
        receipt["release_eligibility_receipt_sha256"] = (
            goals.canonical_binding_map(checkpoint)[
                "RELEASE_ELIGIBILITY_APPROVAL"
            ]["file_sha256"]
        )
        receipt["approved_candidate_sha256"] = candidate_sha256
        receipt["deployed_candidate_sha256"] = candidate_sha256
        receipt["required_checks"]["canary"]["executed_at"] = (
            "2026-07-23T13:10:00+09:00"
        )
        receipt["required_checks"]["smoke"]["executed_at"] = (
            "2026-07-23T13:20:00+09:00"
        )
        receipt["deployment"] = {
            "environment_id": "TEST-PRODUCTION",
            "channel": "controlled-test",
            "scope": "test release candidate",
            "started_at": "2026-07-23T13:00:00+09:00",
            "ended_at": "2026-07-23T13:40:00+09:00",
            "executor_id": receipt["executor"]["id"],
        }
        receipt["technical_delivery"] = {
            "recipient": self.receipt_actor("recipient"),
            "accepted_at": "2026-07-23T13:50:00+09:00",
            "artifacts": [
                {
                    "path": candidate_manifest_path,
                    "sha256": candidate_sha256,
                }
            ],
        }
        self.add_receipt_binding(
            checkpoint,
            root,
            role=role,
            receipt=receipt,
        )

        new_goal_id = "WS-GOAL-D-OPERATION-HANDOVER-R001"
        phase_d_path = (
            "docs/control/goals/walksafe-completion-v1/"
            "40-phase-d-operation-handover-closure.md"
        )
        new_goal_path = self.materialize_work_item(
            root,
            goal_id=new_goal_id,
            relative_path=(
                "docs/control/goals/walksafe-completion-v1/work-items/d/"
                "operation-handover-r001.md"
            ),
            phase_id="D",
            parent_goal_id=goals.EXPECTED_PHASE_IDS["D"],
            sequence=1,
            work_item_id="PHASE-D-OPERATION-HANDOVER",
            source_policy_ids=[],
            gap_ids=[],
            dependencies=[goals.EXPECTED_PHASE_IDS["C"]],
            materialized_from_role="PHASE_PLAN",
            materialized_from_path=phase_d_path,
            materialized_from_document_id=goals.EXPECTED_PHASE_IDS["D"],
            predecessor_goal_id=old_goal_id,
            predecessor_goal_path=old_goal_path,
        )

        state["status_by_goal"][new_goal_id] = "PLANNED"
        state["materialized_child_goal_ids_by_parent"][
            goals.EXPECTED_PHASE_IDS["D"]
        ] = [new_goal_id]
        work_item_completion_ref = (
            self.create_work_item_completion_receipt(
                checkpoint,
                root,
                goal_id=old_goal_id,
            )
        )
        state["status_by_goal"][old_goal_id] = "COMPLETE_AT_TARGET"
        state["completion_evidence_by_goal"][old_goal_id] = [
            work_item_completion_ref,
            role,
        ]
        state["status_by_goal"][
            goals.EXPECTED_PHASE_IDS["C"]
        ] = "COMPLETE_AT_TARGET"
        state["completion_evidence_by_goal"][
            goals.EXPECTED_PHASE_IDS["C"]
        ] = [role]
        state["goal_status"] = "COMPLETE_AT_TARGET"
        self.append_event(
            checkpoint,
            root=root,
            event_type="GOAL_COMPLETED",
            current_leaf_goal_id=old_goal_id,
            from_status="IN_PROGRESS",
            to_status="COMPLETE_AT_TARGET",
            evidence_refs=[work_item_completion_ref, role],
            extra={
                "completion_receipt_binding": (
                    self.completion_binding_snapshot(
                        checkpoint,
                        work_item_completion_ref,
                    )
                )
            },
        )

        for goal_id in (
            goals.EXPECTED_MASTER_ID,
            goals.EXPECTED_PHASE_IDS["D"],
            new_goal_id,
        ):
            state["status_by_goal"][goal_id] = "IN_PROGRESS"
        state["current_phase_goal_id"] = goals.EXPECTED_PHASE_IDS["D"]
        state["current_epic_goal_id"] = ""
        state["current_leaf_goal_id"] = new_goal_id
        state["current_leaf_goal_path"] = new_goal_path
        state["current_work_item_id"] = "PHASE-D-OPERATION-HANDOVER"
        state["current_leaf_source"] = "PHASE_PLAN"
        state["goal_status"] = "IN_PROGRESS"
        checkpoint["current_work"]["epic_id"] = ""
        checkpoint["current_work"]["work_item_id"] = state["current_work_item_id"]
        self.append_event(
            checkpoint,
            root=root,
            event_type="GOAL_TRANSITION",
            current_leaf_goal_id=new_goal_id,
            from_status="COMPLETE_AT_TARGET",
            to_status="IN_PROGRESS",
            evidence_refs=[role],
        )
        self.refresh_goal_manifest(checkpoint, root)

    def transition_to_terminal(self, checkpoint: dict, root: Path) -> None:
        state = checkpoint["goal_execution"]
        old_goal_id = state["current_leaf_goal_id"]
        candidate_manifest_path, candidate_sha256, _ = self.candidate_fixture(
            root
        )
        phase_c_role = "PHASE_C_TECHNICAL_DELIVERY_RECEIPT"
        handover_role = "PHASE_D_OPERATION_HANDOVER_RECEIPT"
        handover = self.common_receipt(
            root,
            evidence_type=handover_role,
            status="HANDED_OVER",
            candidate_sha256=candidate_sha256,
            source_goal_id=goals.EXPECTED_PHASE_IDS["D"],
            source_goal_path=(
                "docs/control/goals/walksafe-completion-v1/"
                "40-phase-d-operation-handover-closure.md"
            ),
            required_checks={
                "service_owner": "PASS",
                "access_transfer": "PASS",
                "runbook": "PASS",
                "monitoring": "PASS",
                "backup_restore": "PASS",
                "support_escalation": "PASS",
            },
            raw_record_count=len(goals.PHASE_D_HANDOVER_REQUIRED_CHECKS),
        )
        handover["phase_c_technical_delivery_receipt_sha256"] = (
            goals.canonical_binding_map(checkpoint)[phase_c_role][
                "file_sha256"
            ]
        )
        handover["deployed_candidate_sha256"] = candidate_sha256
        handover["transferor"] = self.receipt_actor(
            "transferor",
            require_decision=True,
            decided_at="2026-07-23T14:45:00+09:00",
        )
        handover["transferee"] = self.receipt_actor(
            "transferee",
            require_decision=True,
            decided_at="2026-07-23T14:45:00+09:00",
        )
        handover["accepted_at"] = "2026-07-23T14:50:00+09:00"
        handover["operational_responsibility"] = {
            "service_owner_id": handover["transferee"]["id"],
            "support_contact": "test-support@example.invalid",
            "cost_owner_id": handover["transferee"]["id"],
            "effective_at": "2026-07-23T14:50:00+09:00",
        }
        handover_sha256 = self.add_receipt_binding(
            checkpoint,
            root,
            role=handover_role,
            receipt=handover,
        )
        closure_role = "PHASE_D_PROJECT_CLOSURE_RECEIPT"
        closure = self.common_receipt(
            root,
            evidence_type=closure_role,
            status="CLOSED",
            candidate_sha256=candidate_sha256,
            source_goal_id=goals.EXPECTED_PHASE_IDS["D"],
            source_goal_path=(
                "docs/control/goals/walksafe-completion-v1/"
                "40-phase-d-operation-handover-closure.md"
            ),
            required_checks={
                "final_acceptance": "PASS",
                "remaining_defects": "PASS",
                "remaining_risks": "PASS",
                "technical_debt": "PASS",
                "data_disposition": "PASS",
                "account_key_cleanup": "PASS",
                "lessons_learned": "PASS",
            },
            raw_record_count=len(goals.PHASE_D_CLOSURE_REQUIRED_CHECKS),
        )
        closure["operation_handover_receipt_sha256"] = handover_sha256
        closure["deployed_candidate_sha256"] = candidate_sha256
        closure["closure_type"] = "CONTINUED_OPERATION"
        archive_path = (
            "docs/control/test-evidence/final-project-archive.json"
        )
        self.write_json(
            root / archive_path,
            {
                "archive_id": "TEST-FINAL-ARCHIVE-001",
                "candidate_manifest_path": candidate_manifest_path,
                "candidate_sha256": candidate_sha256,
            },
        )
        closure["final_archive"] = {
            "path": archive_path,
            "sha256": goals.sha256_file(root / archive_path),
        }
        closure["kpi_result_refs"] = ["KPI-RESULT-001"]
        closure["remaining_item_registers"] = {
            "defects": [],
            "risks": [],
            "technical_debt": [],
        }
        closure["data_disposition_status"] = "TRANSFERRED"
        closure["account_key_cleanup_status"] = "TRANSFERRED"
        closure["project_owner"] = self.receipt_actor(
            "project_owner",
            require_decision=True,
            decided_at="2026-07-23T15:45:00+09:00",
        )
        closure["accepting_owner"] = self.receipt_actor(
            "accepting_owner",
            require_decision=True,
            decided_at="2026-07-23T15:45:00+09:00",
        )
        closure["closed_at"] = "2026-07-23T15:50:00+09:00"
        self.add_receipt_binding(
            checkpoint,
            root,
            role=closure_role,
            receipt=closure,
        )

        work_item_completion_ref = (
            self.create_work_item_completion_receipt(
                checkpoint,
                root,
                goal_id=old_goal_id,
            )
        )
        for goal_id in (
            goals.EXPECTED_PHASE_IDS["D"],
            goals.EXPECTED_MASTER_ID,
        ):
            state["status_by_goal"][goal_id] = "COMPLETE_AT_TARGET"
            state["completion_evidence_by_goal"][goal_id] = [
                handover_role,
                closure_role,
            ]
        state["status_by_goal"][old_goal_id] = "COMPLETE_AT_TARGET"
        state["completion_evidence_by_goal"][old_goal_id] = [
            work_item_completion_ref,
            handover_role,
            closure_role,
        ]
        state["goal_status"] = "COMPLETE_AT_TARGET"
        self.append_event(
            checkpoint,
            root=root,
            event_type="GOAL_COMPLETED",
            current_leaf_goal_id=old_goal_id,
            from_status="IN_PROGRESS",
            to_status="COMPLETE_AT_TARGET",
            evidence_refs=[
                work_item_completion_ref,
                handover_role,
                closure_role,
            ],
            extra={
                "completion_receipt_binding": (
                    self.completion_binding_snapshot(
                        checkpoint,
                        work_item_completion_ref,
                    )
                )
            },
        )

        state["activation_status"] = "COMPLETE"
        state["package_status"] = "COMPLETE_AT_TARGET"
        state["goal_status"] = "COMPLETE_AT_TARGET"
        state["current_phase_goal_id"] = ""
        state["current_epic_goal_id"] = ""
        state["current_leaf_goal_id"] = ""
        state["current_leaf_goal_path"] = ""
        state["current_work_item_id"] = ""
        state["pending_questions"] = []
        state["open_question_count"] = 0
        checkpoint["current_work"]["epic_id"] = ""
        checkpoint["current_work"]["work_item_id"] = ""
        self.append_event(
            checkpoint,
            root=root,
            event_type="PACKAGE_COMPLETED",
            current_leaf_goal_id="",
            from_status="COMPLETE_AT_TARGET",
            to_status="COMPLETE_AT_TARGET",
            evidence_refs=[handover_role, closure_role],
        )
        self.refresh_goal_manifest(checkpoint, root)

    def write_json(self, path: Path, value: dict) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    def write_checkpoint(self, checkpoint: dict, root: Path) -> None:
        (root / CHECKPOINT_RELATIVE).write_text(
            json.dumps(checkpoint, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
