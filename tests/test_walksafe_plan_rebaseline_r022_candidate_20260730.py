from __future__ import annotations

import copy
import json
from pathlib import Path
import subprocess
import sys
import unittest

from scripts import build_walksafe_plan_rebaseline_r022_candidate_20260730 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = (
    REPO_ROOT
    / "scripts/build_walksafe_plan_rebaseline_r022_candidate_20260730.py"
)


class WalkSafePlanRebaselineR022CandidateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.outputs = {
            path: path.read_bytes()
            for path in (
                builder.LEDGER_PATH,
                builder.GAP_CANDIDATE_PATH,
                builder.BACKLOG_CANDIDATE_PATH,
                builder.PAIR_MANIFEST_PATH,
            )
        }
        cls.ledger = builder.load_strict_json(builder.LEDGER_PATH)
        cls.gap = builder.load_strict_json(builder.GAP_CANDIDATE_PATH)
        cls.backlog = builder.load_strict_json(builder.BACKLOG_CANDIDATE_PATH)
        cls.manifest = builder.load_strict_json(builder.PAIR_MANIFEST_PATH)
        cls.source_gap = builder.load_strict_json(builder.R021_GAP_PATH)
        cls.source_backlog = builder.load_strict_json(builder.R021_BACKLOG_PATH)

    def test_generated_outputs_are_current_without_writing(self) -> None:
        before = {
            path: (path.stat().st_mtime_ns, path.read_bytes())
            for path in self.outputs
        }
        completed = subprocess.run(
            [sys.executable, "-B", str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertIn("reviewed=68 changed=31 carry=37 status_changed=8", completed.stdout)
        self.assertIn("canonical=NOT_APPLIED release=NOT_ELIGIBLE", completed.stdout)
        after = {
            path: (path.stat().st_mtime_ns, path.read_bytes())
            for path in self.outputs
        }
        self.assertEqual(after, before)

    def test_builder_is_deterministic(self) -> None:
        first = builder.build_outputs()
        second = builder.build_outputs()
        self.assertEqual(first, second)
        self.assertEqual(first, self.outputs)

    def test_exact68_changed31_and_byte_exact_carry37(self) -> None:
        self.assertEqual(len(self.ledger["records"]), 68)
        changed = set(self.ledger["summary"]["changed_gap_ids"])
        carried = set(self.ledger["summary"]["carried_forward_gap_ids"])
        self.assertEqual(len(changed), 31)
        self.assertEqual(len(carried), 37)
        self.assertFalse(changed & carried)
        before = {row["gap_id"]: row for row in self.source_gap["assessments"]}
        after = {row["gap_id"]: row for row in self.gap["assessments"]}
        for gap_id in carried:
            self.assertEqual(after[gap_id], before[gap_id])
        for gap_id in changed:
            self.assertNotEqual(after[gap_id], before[gap_id])

    def test_status_counts_and_gap055_protocol_conflict_are_conservative(self) -> None:
        self.assertEqual(
            self.gap["summary"]["status_counts"],
            {
                "BLOCKED": 5,
                "CONFLICTING": 14,
                "EVIDENCE_MISSING": 4,
                "MISSING": 6,
                "PARTIAL": 39,
                "IMPLEMENTED": 0,
            },
        )
        rows = {row["gap_id"]: row for row in self.gap["assessments"]}
        self.assertEqual(rows["GAP-055"]["status"], "CONFLICTING")
        self.assertIn("secret header", rows["GAP-055"]["current_implementation_in_plain_language"])
        self.assertEqual(
            {
                row["gap_id"]
                for row in self.ledger["records"]
                if row["decision_disposition"]
                in {"REASSESS_UP", "REASSESS_DOWN"}
            },
            set(builder.STATUS_CHANGES),
        )

    def test_exact68_review_scope_and_plan_dispositions_are_explicit(self) -> None:
        scope = self.gap["reassessment_scope"]
        changed_gap_ids = set(self.ledger["summary"]["changed_gap_ids"])
        carried_gap_ids = set(self.ledger["summary"]["carried_forward_gap_ids"])
        self.assertEqual(set(scope["directly_reassessed_gap_ids"]), changed_gap_ids)
        self.assertEqual(set(scope["reviewed_gap_ids"]), changed_gap_ids)
        self.assertEqual(
            set(scope["assessment_delta_gap_ids"]),
            changed_gap_ids,
        )
        self.assertEqual(
            set(scope["byte_exact_carry_forward_gap_ids"]),
            carried_gap_ids,
        )
        self.assertEqual(set(scope["carried_forward_gap_ids"]), carried_gap_ids)
        self.assertEqual(self.ledger["review_contract"]["reviewed_gap_count"], 68)
        self.assertEqual(
            self.ledger["summary"]["decision_disposition_counts"],
            {
                "KEEP": 33,
                "NEEDS_EVIDENCE": 4,
                "REASSESS_DOWN": 1,
                "REASSESS_UP": 7,
                "TEXT_FIX": 23,
            },
        )
        records = {row["gap_id"]: row for row in self.ledger["records"]}
        for gap_id in {"GAP-011", "GAP-033", "GAP-058", "GAP-059"}:
            self.assertEqual(records[gap_id]["decision_disposition"], "NEEDS_EVIDENCE")
        self.assertEqual(records["GAP-055"]["decision_disposition"], "REASSESS_DOWN")

    def test_json_pointer_evidence_locators_are_resolved_fail_closed(self) -> None:
        receipt = (
            REPO_ROOT
            / "docs/control/execution/goal-results/"
            "WS-GOAL-EPIC-03-FP-047-R001/completion-receipt.json"
        )
        self.assertEqual(
            builder._validate_locator_text(receipt, "/completion_boundary"),
            "JSON_POINTER_RESOLVED",
        )
        with self.assertRaises(builder.R022CandidateError):
            builder._validate_locator_text(receipt, "/definitely/not/a/real/pointer")

    def test_specialized_backlog_actions_do_not_regress_to_gap_remediation(self) -> None:
        source_gap_by_policy = {
            row["source_policy_id"]: row
            for row in self.source_gap["assessments"]
        }
        source_sequence = {
            row["source_policy_id"]: row
            for row in self.source_backlog["next_action_sequence"]
        }
        candidate_sequence = {
            row["source_policy_id"]: row
            for row in self.backlog["next_action_sequence"]
        }
        specialized = {
            policy_id
            for policy_id, row in source_sequence.items()
            if row["action"] != source_gap_by_policy[policy_id]["remediation"]
        }
        self.assertIn("FP-047", specialized)
        for policy_id in specialized:
            self.assertEqual(
                candidate_sequence[policy_id]["action"],
                source_sequence[policy_id]["action"],
            )

    def test_activation_impact_uses_each_role_subject_namespace(self) -> None:
        impact = self.manifest["activation_impact_boundary"]
        by_role = impact["changed_subject_ids_by_role"]
        changed_gap_ids = set(self.ledger["summary"]["changed_gap_ids"])
        gap_rows = {
            row["gap_id"]: row
            for row in self.gap["assessments"]
        }
        self.assertEqual(
            set(by_role["IMPLEMENTATION_GAP"]),
            {
                "*",
                *changed_gap_ids,
                *(gap_rows[gap_id]["source_policy_id"] for gap_id in changed_gap_ids),
            },
        )
        source_sequence = {
            row["source_policy_id"]: row
            for row in self.source_backlog["next_action_sequence"]
        }
        candidate_sequence = {
            row["source_policy_id"]: row
            for row in self.backlog["next_action_sequence"]
        }
        changed_policies = {
            policy_id
            for policy_id in source_sequence
            if source_sequence[policy_id] != candidate_sequence[policy_id]
        }
        self.assertEqual(
            set(by_role["IMPLEMENTATION_BACKLOG"]),
            {"*", *changed_policies},
        )
        self.assertFalse(
            any(
                subject.startswith("GAP-")
                for subject in by_role["IMPLEMENTATION_BACKLOG"]
            )
        )
        self.assertEqual(impact["backlog_changed_policy_count"], len(changed_policies))
        self.assertEqual(
            impact["v2_4_scope_compatibility"],
            "CONTENT_SCOPE_COMPATIBLE_CHANGED31_CARRY37",
        )

    def test_changed_claims_bind_their_material_current_evidence(self) -> None:
        records = {row["gap_id"]: row for row in self.ledger["records"]}
        expected_paths = {
            "GAP-002": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "session/PrivacyDeletionPolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "network/AndroidPrivacyDeletionClient.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "network/AndroidIntegratedConsentClient.kt",
                "apps/android-gateway/src/privacy-rights.ts",
                "apps/android-gateway/src/routes.ts",
            },
            "GAP-004": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "network/AndroidNetworkTransferPolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "report/AndroidPendingReportStore.kt",
            },
            "GAP-005": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "report/AndroidPendingReportStore.kt",
            },
            "GAP-006": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "session/PermissionSessionPolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
            },
            "GAP-008": {
                "apps/android/adminapp/build.gradle.kts",
                "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "admin/security/AdminSecurityController.java",
                "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "admin/security/AdminHighRiskActionGate.java",
                "backend/app/services/admin_security.py",
                "configs/walksafe_product_boundary_20260722.json",
            },
            "GAP-016": {
                "apps/android/settings.gradle.kts",
                "apps/android/app/build.gradle.kts",
                "apps/android/adminapp/build.gradle.kts",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "device/WalkSafeStartupCapability.kt",
            },
            "GAP-017": {
                "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "admin/AdminBoundaryActivity.java",
                "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "admin/security/AdminSecurityController.java",
                "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "admin/security/AdminHighRiskActionGate.java",
            },
            "GAP-021": {
                "apps/android-gateway/src/field-walk-ledger.ts",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "network/GatewayWalkSession.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
            },
            "GAP-022": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "session/IntegratedConsentPolicy.kt",
                "apps/android-gateway/src/integrated-consent.ts",
            },
            "GAP-023": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "session/PermissionSessionPolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
            },
            "GAP-030": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "device/CameraFrameQualityPolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
                "apps/android/app/src/main/assets/model-config/two_model_runtime.json",
                "docs/deliverables/08-ai-ml-data/registers/model-register.json",
            },
            "GAP-025": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
                "MainActivityFp016StaticTest.kt",
            },
            "GAP-028": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "inference/TfliteAndroidFrameDetector.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
                "apps/android/app/src/main/assets/model-config/two_model_runtime.json",
                "docs/deliverables/08-ai-ml-data/registers/model-register.json",
            },
            "GAP-034": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "device/AndroidStartupCapabilityProbe.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
            },
            "GAP-036": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "feedback/AndroidFeedbackActuator.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "feedback/WalkSafeFeedbackPolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
            },
            "GAP-041": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "report/AndroidPendingReportStore.kt",
                "backend/app/api/reports.py",
                "backend/app/services/report_storage.py",
            },
            "GAP-037": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
                "MainActivityAccessibilityStaticTest.kt",
            },
            "GAP-039": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
                "PriorityUserOnboardingStaticTest.kt",
            },
            "GAP-042": {
                "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "admin/AdminBoundaryActivity.java",
                "backend/app/api/reports.py",
            },
            "GAP-040": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "report/AndroidReportCandidatePolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "report/AndroidPendingReportStore.kt",
            },
            "GAP-045": {
                "backend/app/api/reports.py",
                "backend/app/services/report_storage.py",
                "scripts/check_report_retention_dry_run.py",
            },
            "GAP-044": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "network/AndroidNetworkTransferPolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
                "apps/android/app/src/release/java/kr/co/hanium/dreamup/walksafe/"
                "debuglog/DebugFrameCaptureUploaderFactory.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "report/AndroidPendingReportStore.kt",
            },
            "GAP-053": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "network/AndroidNetworkTransferPolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "report/AndroidPendingReportStore.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
            },
            "GAP-054": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "device/AndroidWalkSessionResourceProbe.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
            },
            "GAP-049": {
                "apps/android-gateway/src/routes.ts",
                "backend/app/services/admin_security.py",
                "backend/app/api/reports.py",
            },
            "GAP-052": {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "device/AndroidWalkSessionResourceProbe.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "feedback/AndroidFeedbackActuator.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
            },
            "GAP-061": {
                "apps/android-gateway/src/telemetry.ts",
                "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "admin/security/AdminSecurityTelemetry.java",
                "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "admin/security/AdminSecurityController.java",
                "docs/deliverables/10-operations/operator-guide.md",
            },
            "GAP-062": {
                "scripts/backup_walksafe_data_20260711.sh",
                "scripts/restore_walksafe_backup_drill_20260711.sh",
                "scripts/prune_walksafe_backups_20260711.py",
                "docs/deliverables/10-operations/registers/operations-registers.json",
                "docs/deliverables/10-operations/recovery-plan.md",
            },
            "GAP-063": {
                "backend/app/api/reports.py",
                "scripts/backup_walksafe_data_20260711.sh",
                "docs/deliverables/12-closure/decommissioning-plan.md",
                "docs/control/decision-interview/"
                "walksafe-feature-policy-effective-candidate.json",
            },
        }
        for gap_id, paths in expected_paths.items():
            self.assertTrue(
                paths.issubset(
                    {locator["path"] for locator in records[gap_id]["evidence_locators"]}
                )
            )

    def test_content_classification_is_separate_from_application_route(self) -> None:
        self.assertEqual(
            self.manifest["content_classification"],
            "V2_4_CONTENT_SCOPE_COMPATIBLE_CHANGED31_CARRY37",
        )
        self.assertEqual(
            self.manifest["application_route"],
            "VERSIONED_SUCCESSOR_CONTROL_REQUIRED_FOR_OPERATIONAL_DELTA",
        )
        self.assertEqual(
            self.manifest["classification"],
            "STAGED_R022_CANDIDATE_NOT_APPLIED",
        )

    def test_all_seals_pair_binding_and_physical_manifest_are_valid(self) -> None:
        self.assertTrue(builder._seal_is_valid(self.ledger, "ledger_content_sha256"))
        self.assertTrue(builder._seal_is_valid(self.gap, "report_content_sha256"))
        self.assertTrue(builder._seal_is_valid(self.backlog, "backlog_content_sha256"))
        self.assertTrue(builder._seal_is_valid(self.manifest, "manifest_content_sha256"))
        self.assertEqual(
            self.backlog["gap_report_content_sha256"],
            self.gap["report_content_sha256"],
        )
        for document in self.manifest["documents"]:
            path = REPO_ROOT / document["candidate_path"]
            payload = path.read_bytes()
            self.assertEqual(document["file_sha256"], builder._sha256_bytes(payload))
            self.assertEqual(document["bytes"], len(payload))

    def test_next_leaf_is_prepared_but_not_materialized(self) -> None:
        self.assertEqual(
            self.backlog["next_single_action"],
            {
                "epic_id": "EPIC-03",
                "work_item_id": "EPIC-03-FP008-ADMIN-REVIEW-DELIVERY",
                "source_policy_id": "FP-008",
                "gap_id": "GAP-017",
                "status": "PLANNED_NEXT_NOT_MATERIALIZED",
                "action": next(
                    row["remediation"]
                    for row in self.gap["assessments"]
                    if row["gap_id"] == "GAP-017"
                ),
            },
        )
        boundary = self.manifest["authorization_boundary"]
        self.assertFalse(boundary["canonical_files_written"])
        self.assertFalse(boundary["checkpoint_modified"])
        self.assertFalse(boundary["goal_event_appended"])
        self.assertFalse(boundary["next_work_item_materialized"])
        self.assertTrue(
            boundary["separate_candidate_specific_activation_approval_required"]
        )

    def test_static_mapping_and_dependencies_remain_v24_exact(self) -> None:
        self.assertEqual(
            self.ledger["mapping_sha256"],
            builder.EXPECTED_MAPPING_SHA256,
        )
        self.assertEqual(
            self.ledger["hard_dependency_sha256"],
            builder.EXPECTED_HARD_DEPENDENCY_SHA256,
        )
        self.assertFalse(self.manifest["static_fingerprints"]["mapping_changed"])
        self.assertFalse(self.manifest["static_fingerprints"]["hard_dependency_changed"])
        self.assertFalse(self.manifest["static_fingerprints"]["static_schema_changed"])

    def test_resealed_missing_record_and_implemented_claim_are_rejected(self) -> None:
        missing = copy.deepcopy(self.outputs)
        ledger = json.loads(missing[builder.LEDGER_PATH])
        ledger["records"].pop()
        ledger = builder._sealed_object(ledger, "ledger_content_sha256")
        missing[builder.LEDGER_PATH] = builder._pretty_bytes(ledger)
        with self.assertRaises(builder.R022CandidateError):
            builder.validate_outputs(missing)

        implemented = copy.deepcopy(self.outputs)
        gap = json.loads(implemented[builder.GAP_CANDIDATE_PATH])
        row = next(item for item in gap["assessments"] if item["gap_id"] == "GAP-001")
        row["status"] = "IMPLEMENTED"
        resealed = builder._sealed_object(row, "assessment_sha256")
        row.clear()
        row.update(resealed)
        gap = builder._sealed_object(gap, "report_content_sha256")
        implemented[builder.GAP_CANDIDATE_PATH] = builder._pretty_bytes(gap)
        with self.assertRaises(builder.R022CandidateError):
            builder.validate_outputs(implemented)

    def test_default_write_is_add_only_and_does_not_overwrite(self) -> None:
        before = {path: path.read_bytes() for path in self.outputs}
        completed = subprocess.run(
            [sys.executable, "-B", str(SCRIPT_PATH)],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("output directory already exists", completed.stderr)
        self.assertEqual(
            {path: path.read_bytes() for path in self.outputs},
            before,
        )


if __name__ == "__main__":
    unittest.main()
