from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts import build_walksafe_feature_policy_resolution as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_feature_policy_resolution.py"
EXPECTED_ANSWER_SHA256 = "ef840c33215767d210fc930a8087f09e5eda7b99af695ded5cd9d226d8ab5150"
EXPECTED_REVISED_FEATURE_IDS = {
    "FP-003",
    "FP-013",
    "FP-014",
    "FP-015",
    "FP-018",
    "FP-019",
    "FP-020",
    "FP-021",
    "FP-022",
    "FP-023",
    "FP-025",
    "FP-026",
    "FP-031",
    "FP-032",
    "FP-034",
    "FP-035",
    "FP-044",
    "FP-046",
    "FP-047",
}
EXPECTED_ACCEPTED_DATED_AMENDMENTS = {
    "SP-09",
    "FP-011",
    "FP-017",
    "FP-024",
    "FP-033",
    "FP-036",
    "FP-038",
    "FP-041",
    "FP-042",
    "FP-043",
    "FP-045",
    "FP-048",
    "FP-053",
}


class WalkSafeFeaturePolicyResolutionTests(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.result = builder.build_resolution()
        cls.records = {
            item["review_id"]: item for item in cls.result["review_records"]
        }
        cls.features = {
            item["feature_id"]: item for item in cls.result["feature_resolutions"]
        }
        cls.constants = {
            item["id"]: item for item in cls.result["normalized_policy_constants"]
        }

    def test_controlled_answer_is_hash_bound_and_build_inputs_are_relative(self) -> None:
        controlled_hash = hashlib.sha256(builder.CONTROLLED_ANSWERS_PATH.read_bytes()).hexdigest()
        self.assertEqual(controlled_hash, EXPECTED_ANSWER_SHA256)
        self.assertEqual(self.result["source_bindings"][0]["sha256"], EXPECTED_ANSWER_SHA256)
        for binding in self.result["source_bindings"]:
            with self.subTest(source=binding["id"]):
                self.assertFalse(Path(binding["path"]).is_absolute())
                source_path = REPO_ROOT / binding["path"]
                self.assertTrue(source_path.is_file())
                self.assertEqual(
                    hashlib.sha256(source_path.read_bytes()).hexdigest(),
                    binding["sha256"],
                )
        intake = builder.load_strict_json(builder.INTAKE_PATH)
        self.assertEqual(
            intake["answer_intake"]["external_path_usage"],
            "PROVENANCE_ONLY",
        )

    def test_all_76_reviews_and_all_54_features_are_covered_once(self) -> None:
        summary = self.result["summary"]
        self.assertEqual(summary["reviewed"], 76)
        self.assertEqual(summary["accepted"], 54)
        self.assertEqual(summary["revision_requested"], 22)
        self.assertEqual(summary["held"], 0)
        self.assertEqual(summary["feature_count"], 54)
        self.assertEqual(summary["non_empty_note_count"], 37)
        self.assertEqual(summary["dated_correction_count"], 35)
        self.assertEqual(summary["direct_changed_feature_count"], 31)
        self.assertEqual(len(self.records), 76)
        self.assertEqual(len(self.features), 54)
        self.assertEqual(
            list(self.features),
            [f"FP-{index:03d}" for index in range(1, 55)],
        )
        coverage = self.result["coverage"]
        for field in (
            "missing_review_ids",
            "duplicate_review_ids",
            "missing_feature_ids",
            "duplicate_feature_ids",
            "orphan_cascade_source_ids",
            "orphan_cascade_feature_ids",
        ):
            self.assertEqual(coverage[field], [], field)

    def test_revision_and_accepted_amendment_sets_are_not_conflated(self) -> None:
        revised_features = {
            review_id
            for review_id, record in self.records.items()
            if record["scope"] == "FEATURE" and record["decision"] == "revise"
        }
        self.assertEqual(revised_features, EXPECTED_REVISED_FEATURE_IDS)
        revised_globals = {
            review_id
            for review_id, record in self.records.items()
            if record["scope"] == "GLOBAL" and record["decision"] == "revise"
        }
        revised_shared = {
            review_id
            for review_id, record in self.records.items()
            if record["scope"] == "SHARED" and record["decision"] == "revise"
        }
        self.assertEqual(revised_globals, {"GP-04"})
        self.assertEqual(revised_shared, {"SP-11", "SP-13"})

        accepted_dated = {
            review_id
            for review_id, record in self.records.items()
            if record["decision"] == "accept" and record["is_dated_correction"]
        }
        self.assertEqual(accepted_dated, EXPECTED_ACCEPTED_DATED_AMENDMENTS)
        for review_id in EXPECTED_ACCEPTED_DATED_AMENDMENTS:
            with self.subTest(review_id=review_id):
                self.assertEqual(
                    self.records[review_id]["merge_action"],
                    "KEEP_PROPOSAL_AND_APPLY_NOTE",
                )
                self.assertTrue(self.records[review_id]["reviewer_note"])

    def test_server_and_phone_capacity_policies_are_separate_and_arithmetic_is_exact(self) -> None:
        server = self.constants["NPC-SERVER-STORAGE-CAPACITY"]
        phone = self.constants["NPC-PHONE-QUEUE-CAPACITY"]
        self.assertEqual(server["primary_limit_gib"], 300)
        self.assertEqual(server["backup_limit_gib"], 300)
        self.assertEqual(server["physical_total_limit_gib"], 600)
        self.assertEqual(
            server["thresholds"],
            [
                {"percent": 70, "gib": 210, "action": "ADMIN_ONLY_WARNING"},
                {
                    "percent": 85,
                    "gib": 255,
                    "action": "STOP_ADDING_NEW_FIELD_TEST_PARTICIPANTS",
                },
                {
                    "percent": 95,
                    "gib": 285,
                    "action": "CLEAN_EXPIRED_DATA_THEN_HOLD_NEW_RAW_COLLECTION_SESSIONS",
                },
                {
                    "percent": 100,
                    "gib": 300,
                    "action": "SILENTLY_HOLD_NEW_TRAINING_DATA_AND_AUTO_REPORT_CANDIDATES",
                },
            ],
        )
        self.assertEqual(
            server["pricing_assumption"]["calculated_storage_only_krw"],
            17_550,
        )
        self.assertLessEqual(
            server["pricing_assumption"]["calculated_storage_only_krw"],
            server["monthly_storage_budget_krw"],
        )
        self.assertFalse(phone["server_gib_or_percentage_thresholds_apply"])
        self.assertEqual(
            phone["byte_limit_status"],
            "MEASUREMENT_REQUIRED_PER_SUPPORTED_DEVICE",
        )
        self.assertEqual(
            phone["delete_or_hold_order"],
            [
                "RECREATABLE_TEMP_CACHE",
                "SERVER_RECEIPT_CONFIRMED_LOCAL_COPY",
                "EXPIRED_OPTIONAL_TRAINING_DATA",
                "EXPIRED_LOW_CONFIDENCE_UNSENT_REPORT_CANDIDATE",
                "HOLD_NEW_TRAINING_DATA_AND_AUTO_REPORT_CANDIDATE_CREATION",
            ],
        )
        self.assertFalse(phone["user_notification_for_data_pipeline_hold"])

    def test_retention_auto_report_and_route_normalizations_are_explicit(self) -> None:
        lifecycle = self.constants["NPC-DATA-LIFECYCLE"]
        retention = lifecycle["retention"]
        self.assertEqual(retention["receipt_confirmed_local_copy_hours"], 24)
        self.assertEqual(retention["unsent_local_original_days"], 30)
        self.assertEqual(retention["server_intake_and_quarantine_days"], 14)
        self.assertEqual(retention["server_general_and_auto_report_original_days"], 180)
        self.assertEqual(
            retention["approved_training_original_label_and_fixed_validation_calendar_years"],
            3,
        )
        self.assertEqual(retention["rotating_backup_days"], 35)
        self.assertIn("모든 미전송 로컬 원본의 절대 상한", lifecycle["expiry_normalization"])

        auto_report = self.constants["NPC-AUTO-REPORT"]
        self.assertEqual(auto_report["candidate_notification"], "NONE")
        self.assertFalse(auto_report["per_candidate_cancel"])
        self.assertTrue(auto_report["settings_master_off"])
        self.assertEqual(
            auto_report["off_behavior"],
            [
                "새 후보 생성 즉시 중단",
                "미전송 후보의 전송 중단 및 24시간 안 삭제",
                "전송 중인 자료의 서버 처리 여부 확인",
                "서버 원본을 삭제요청 상태로 바꾸고 7일 안 삭제",
                "처리 결과를 내부 감사기록에 남김",
            ],
        )
        self.assertEqual(auto_report["mobile_network_transfer"], "OPT_IN_ONLY")
        self.assertEqual(
            auto_report["wifi_unavailable_behavior"],
            "암호화 대기열에 보관하고 다음 Wi-Fi 연결 때 전송",
        )
        self.assertFalse(auto_report["long_unsent_user_notification"])
        self.assertTrue(auto_report["upload_after_walk_session_ends"])
        self.assertTrue(auto_report["stable_idempotency_key_required"])
        self.assertIn("commit", auto_report["completion_condition"])
        self.assertIn("hash", auto_report["completion_condition"])

        route = self.constants["NPC-NAVIGATION-ROUTE-DIRECTION"]
        self.assertEqual(len(route["deviation_flow"]), 10)
        self.assertEqual(
            route["direction_responsibility"]["actual_movement_direction"],
            "TRUSTED_GPS",
        )
        self.assertEqual(
            route["direction_responsibility"]["desired_direction_and_turn"],
            "STORED_TMAP_ROUTE",
        )
        self.assertEqual(
            route["direction_responsibility"]["camera_view_direction"],
            "ROTATION_SENSOR_AND_ARCORE",
        )
        self.assertEqual(
            route["direction_responsibility"]["stride_for_direction"],
            "PROHIBITED",
        )
        self.assertFalse(route["reroute_on_network_recovery"])

    def test_raw_collection_and_single_admin_recovery_values_are_exact(self) -> None:
        raw_original = self.constants["NPC-RAW-ORIGINAL-COLLECTION"]
        self.assertEqual(
            raw_original["bystander_face_plate_voice_masking_at_collection"],
            "NONE",
        )

        admin = self.constants["NPC-SINGLE-ADMIN-RECOVERY"]
        self.assertEqual(admin["authentication"], "MFA_OR_PASSKEY")
        self.assertEqual(
            admin["recovery_material_location"],
            "관리자 휴대전화 밖의 복구코드 또는 보안키",
        )
        self.assertTrue(admin["remote_device_session_revocation_required"])
        self.assertEqual(
            admin["access_loss_behavior"],
            "복구할 때까지 출시·권한 변경·데이터 삭제 등 고위험 작업 동결",
        )
        self.assertEqual(admin["pre_field_test_or_deployment_recovery_drill_count"], 1)

    def test_confirmed_core_policy_mutations_fail_semantic_validation(self) -> None:
        cases = [
            ("NPC-RAW-ORIGINAL-COLLECTION", "bystander_face_plate_voice_masking_at_collection", "MASKED"),
            ("NPC-AUTO-REPORT", "mobile_network_transfer", "ALWAYS"),
            ("NPC-AUTO-REPORT", "wifi_unavailable_behavior", "즉시 폐기"),
            ("NPC-SINGLE-ADMIN-RECOVERY", "authentication", "PASSWORD_ONLY"),
            ("NPC-SINGLE-ADMIN-RECOVERY", "recovery_material_location", "관리자 휴대전화 내부"),
            ("NPC-SINGLE-ADMIN-RECOVERY", "remote_device_session_revocation_required", False),
            ("NPC-SINGLE-ADMIN-RECOVERY", "access_loss_behavior", "우회 운영"),
            ("NPC-SINGLE-ADMIN-RECOVERY", "pre_field_test_or_deployment_recovery_drill_count", 0),
        ]
        for constant_id, field, invalid_value in cases:
            with self.subTest(constant_id=constant_id, field=field):
                rules = builder.load_strict_json(builder.RULES_PATH)
                constant = next(
                    item
                    for item in rules["normalized_policy_constants"]
                    if item["id"] == constant_id
                )
                constant[field] = invalid_value
                with self.assertRaises(builder.PolicyResolutionValidationError):
                    builder._validate_normalized_constants(rules)

        list_cases = [
            ("NPC-SERVER-STORAGE-CAPACITY", "thresholds"),
            ("NPC-PHONE-QUEUE-CAPACITY", "delete_or_hold_order"),
            ("NPC-AUTO-REPORT", "off_behavior"),
        ]
        for constant_id, field in list_cases:
            with self.subTest(constant_id=constant_id, field=field):
                rules = builder.load_strict_json(builder.RULES_PATH)
                constant = next(
                    item
                    for item in rules["normalized_policy_constants"]
                    if item["id"] == constant_id
                )
                constant[field] = list(reversed(constant[field]))
                with self.assertRaises(builder.PolicyResolutionValidationError):
                    builder._validate_normalized_constants(rules)

    def test_cascade_links_and_fp053_resolution_keep_measurement_gates(self) -> None:
        fp022 = self.features["FP-022"]
        self.assertIn("CAS-ROUTE-STRIDE-DIRECTION", fp022["cascade_group_ids"])
        self.assertIn(
            "NPC-NAVIGATION-ROUTE-DIRECTION",
            fp022["normalized_constant_ids"],
        )
        self.assertEqual(fp022["applicable_review_ids"], ["FP-022"])
        self.assertIn("FP-023", fp022["cascade_evidence_review_ids"])
        self.assertNotIn("FP-023", fp022["review_note_ids"])
        fp031 = self.features["FP-031"]
        self.assertIn("CAS-AUTO-REPORT", fp031["cascade_group_ids"])
        self.assertIn("CAS-SERVER-AND-PHONE-CAPACITY", fp031["cascade_group_ids"])
        fp047 = self.features["FP-047"]
        self.assertIn("CAS-SINGLE-ADMIN-RECOVERY", fp047["cascade_group_ids"])

        fp053 = self.features["FP-053"]
        self.assertEqual(
            fp053["policy_conflict_after_review"],
            "POLICY_CONFLICT_RESOLVED_MEASUREMENT_PENDING",
        )
        self.assertIn("GATE-CLOUD-COST-MEASUREMENT", fp053["remaining_gate_ids"])
        self.assertEqual(
            self.result["summary"]["remaining_policy_conflict_feature_ids"],
            [],
        )
        self.assertGreater(
            self.result["summary"]["implementation_revalidation_feature_count"],
            self.result["summary"]["direct_changed_feature_count"],
        )

    def test_accepted_change_proposals_also_require_implementation_revalidation(self) -> None:
        previously_missed = {
            "FP-001",
            "FP-002",
            "FP-004",
            "FP-005",
            "FP-006",
            "FP-012",
            "FP-016",
            "FP-028",
            "FP-029",
            "FP-030",
            "FP-049",
            "FP-050",
        }
        for feature_id in previously_missed:
            with self.subTest(feature_id=feature_id):
                feature = self.features[feature_id]
                self.assertEqual(
                    feature["implementation_alignment_status"],
                    "REVALIDATION_REQUIRED",
                )
                self.assertIn(
                    "ACCEPTED_PROPOSAL_CHANGES_CURRENT_POLICY",
                    feature["policy_change_reasons"],
                )
                self.assertTrue(feature["accepted_change_proposal_refs"])

    def test_review_completion_never_claims_baseline_release_or_implementation_approval(self) -> None:
        self.assertEqual(self.result["lifecycle_status"], "IN_REVIEW")
        boundary = self.result["approval_boundary"]
        self.assertEqual(boundary["baseline_status"], "NOT_APPROVED")
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")
        self.assertIsNone(boundary["approved_by"])
        self.assertIsNone(boundary["approved_at"])
        self.assertFalse(boundary["baseline_approval_recorded"])
        generation = self.result["generation_boundary"]
        self.assertFalse(generation["formal_deliverables_generated"])
        self.assertFalse(generation["html_report_generated"])
        for feature in self.features.values():
            self.assertNotEqual(feature["implementation_alignment_status"], "VERIFIED")

    def test_strict_json_rejects_duplicate_keys_and_nonstandard_numbers(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            duplicate_path = Path(temp_dir) / "duplicate.json"
            duplicate_path.write_text('{"value": 1, "value": 2}\n', encoding="utf-8")
            with self.assertRaises(builder.PolicyResolutionValidationError):
                builder.load_strict_json(duplicate_path)

            nan_path = Path(temp_dir) / "nan.json"
            nan_path.write_text('{"value": NaN}\n', encoding="utf-8")
            with self.assertRaises(builder.PolicyResolutionValidationError):
                builder.load_strict_json(nan_path)

    def test_missing_revision_reason_fails_validation(self) -> None:
        answers = builder.load_strict_json(builder.CONTROLLED_ANSWERS_PATH)
        proposals = builder.load_strict_json(builder.PROPOSALS_PATH)
        intake = builder.load_strict_json(builder.INTAKE_PATH)
        rules = builder.load_strict_json(builder.RULES_PATH)
        broken = copy.deepcopy(answers)
        broken["feature_reviews"]["FP-003"]["note"] = ""
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder._validate_answers_and_proposals(broken, proposals, intake, rules)

        stale_normalization = copy.deepcopy(answers)
        stale_normalization["feature_reviews"]["FP-011"]["note"] += " 변경"
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder._validate_answers_and_proposals(
                stale_normalization,
                proposals,
                intake,
                rules,
            )

    def test_intake_rejects_missing_ambient_and_weak_control_metadata(self) -> None:
        answers = builder.load_strict_json(builder.CONTROLLED_ANSWERS_PATH)
        intake = builder.load_strict_json(builder.INTAKE_PATH)

        missing_sources = copy.deepcopy(intake)
        missing_sources["review_source_bindings"] = []
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder._validate_intake(missing_sources, answers)

        absolute_source = copy.deepcopy(intake)
        absolute_source["review_source_bindings"][0]["path"] = str(builder.PROPOSALS_PATH)
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder._validate_intake(absolute_source, answers)

        weak_trust = copy.deepcopy(intake)
        weak_trust["answer_intake"]["adoption_trust"] = "A"
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder._validate_intake(weak_trust, answers)

        invalid_time = copy.deepcopy(intake)
        invalid_time["answer_intake"]["captured_at"] = "not-a-date"
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder._validate_intake(invalid_time, answers)

        invalid_id = copy.deepcopy(intake)
        invalid_id["intake_id"] = ""
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder._validate_intake(invalid_id, answers)

        invalid_status = copy.deepcopy(intake)
        invalid_status["approval_boundary"]["review_status"] = "DRAFT"
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder._validate_intake(invalid_status, answers)

    def test_resolution_validator_rejects_false_approval_mutations(self) -> None:
        approved = copy.deepcopy(self.result)
        approved["approval_boundary"]["baseline_approval_recorded"] = True
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder.validate_resolution(approved)

        wrong_status = copy.deepcopy(self.result)
        wrong_status["approval_boundary"]["owner_policy_review_status"] = "BASELINE_APPROVED"
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder.validate_resolution(wrong_status)

        tampered_constant = copy.deepcopy(self.result)
        next(
            item
            for item in tampered_constant["normalized_policy_constants"]
            if item["id"] == "NPC-RAW-ORIGINAL-COLLECTION"
        )["items"] = []
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder.validate_resolution(tampered_constant)

        forged_digest = copy.deepcopy(tampered_constant)
        forged_digest.pop("resolution_content_sha256")
        forged_digest["resolution_content_sha256"] = builder.object_sha256(forged_digest)
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder.validate_resolution(forged_digest)

        false_purpose = copy.deepcopy(self.result)
        false_purpose["purpose"] = "승인된 기준선이며 즉시 출시 가능"
        false_purpose.pop("resolution_content_sha256")
        false_purpose["resolution_content_sha256"] = builder.object_sha256(false_purpose)
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder.validate_resolution(false_purpose)

    def test_rule_semantics_reject_unreviewed_constant_cascade_and_conflict_mutations(self) -> None:
        answers = builder.load_strict_json(builder.CONTROLLED_ANSWERS_PATH)
        rules = builder.load_strict_json(builder.RULES_PATH)

        empty_raw_items = copy.deepcopy(rules)
        next(
            item
            for item in empty_raw_items["normalized_policy_constants"]
            if item["id"] == "NPC-RAW-ORIGINAL-COLLECTION"
        )["items"] = []
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder._validate_rule_references(empty_raw_items, answers)

        missing_cascade_feature = copy.deepcopy(rules)
        next(
            item
            for item in missing_cascade_feature["cascade_groups"]
            if item["id"] == "CAS-RAW-COLLECTION-RETENTION-DELETION"
        )["affected_feature_ids"].remove("FP-019")
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder._validate_rule_references(missing_cascade_feature, answers)

        wrong_conflict_source = copy.deepcopy(rules)
        conflict = next(
            item
            for item in wrong_conflict_source["known_conflict_replacements"]
            if item["id"] == "KCR-RAW-MINIMUM-AND-MASKING"
        )
        conflict["superseding_review_ids"] = ["GP-01"]
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder._validate_rule_references(wrong_conflict_source, answers)

    def test_base_candidates_reject_duplicate_feature_and_decision_ids(self) -> None:
        proposals = builder.load_strict_json(builder.PROPOSALS_PATH)
        base_policy = builder.load_strict_json(builder.BASE_FEATURE_POLICY_PATH)
        base_register = builder.load_strict_json(builder.BASE_REGISTER_PATH)

        duplicate_proposal = copy.deepcopy(proposals)
        duplicate_proposal["features"].append(
            copy.deepcopy(duplicate_proposal["features"][0])
        )
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder._validate_base_candidates(
                base_policy,
                base_register,
                duplicate_proposal,
            )

        duplicate_base = copy.deepcopy(base_policy)
        duplicate_base["features"].append(copy.deepcopy(duplicate_base["features"][0]))
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder._validate_base_candidates(
                duplicate_base,
                base_register,
                proposals,
            )

        duplicate_register = copy.deepcopy(base_register)
        duplicate_register["decisions"] = [
            copy.deepcopy(duplicate_register["decisions"][0])
            for _ in range(135)
        ]
        with self.assertRaises(builder.PolicyResolutionValidationError):
            builder._validate_base_candidates(
                base_policy,
                duplicate_register,
                proposals,
            )

    def test_checked_in_resolution_is_deterministic_and_current(self) -> None:
        first = builder.build_resolution()
        second = builder.build_resolution()
        self.assertEqual(first, second)
        self.assertEqual(
            builder.OUTPUT_PATH.read_text(encoding="utf-8"),
            builder.json_text(first),
        )
        completed = subprocess.run(
            [sys.executable, "-B", str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            check=False,
            text=True,
            capture_output=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("76 reviews", completed.stdout)
        self.assertIn("54 features", completed.stdout)


if __name__ == "__main__":
    unittest.main()
