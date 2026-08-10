from __future__ import annotations

import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


REPO_ROOT = Path(__file__).resolve().parents[1]
CONFIG_PATH = REPO_ROOT / "configs/walksafe_product_boundary_20260722.json"
SETTINGS_PATH = REPO_ROOT / "apps/android/settings.gradle.kts"
USER_BUILD_PATH = REPO_ROOT / "apps/android/app/build.gradle.kts"
ADMIN_ROOT = REPO_ROOT / "apps/android/adminapp"
ADMIN_BUILD_PATH = ADMIN_ROOT / "build.gradle.kts"
ADMIN_MANIFEST_PATH = ADMIN_ROOT / "src/main/AndroidManifest.xml"
ADMIN_ACTIVITY_PATH = ADMIN_ROOT / "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def load_strict_json(path: Path) -> dict:
    def reject_duplicates(pairs: list[tuple[str, object]]) -> dict:
        result: dict = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON key: {key}")
            result[key] = value
        return result

    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicates)


class WalkSafeAndroidProductBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.config = load_strict_json(CONFIG_PATH)
        cls.user = cls.config["products"]["user_android_app"]
        cls.admin = cls.config["products"]["admin_android_app"]

    def test_policy_scope_and_non_claims_are_explicit(self) -> None:
        baseline = self.config["effective_policy_baseline"]
        self.assertEqual(baseline["version"], "1.0.1")
        self.assertEqual(
            baseline["policy_ids"],
            ["FP-003", "FP-002", "FP-007", "FP-009", "FP-001"],
        )
        self.assertTrue((REPO_ROOT / baseline["manifest"]).is_file())
        self.assertEqual(
            self.config["implementation_scope"]["contract_status"],
            "IMPLEMENTED_PHASE_E_ANDROID_GATEWAY_EXTRACTION_INTERNAL",
        )
        non_claims = set(self.config["implementation_scope"]["does_not_claim"])
        self.assertIn("production_signing_configured", non_claims)
        self.assertIn("administrator_security_production_provisioning_completed", non_claims)
        self.assertIn("administrator_authentication_formally_verified", non_claims)
        self.assertIn("administrator_recovery_drill_completed", non_claims)
        self.assertIn("legacy_web_source_archived", non_claims)
        self.assertIn("historical_external_web_endpoint_decommissioned", non_claims)
        self.assertIn("historical_cached_pwa_clients_deactivated", non_claims)
        self.assertIn("android_gateway_deployed", non_claims)
        self.assertIn("android_gateway_actual_device_verified", non_claims)
        self.assertIn("device_profile_field_verified", non_claims)
        self.assertIn("runtime_metric_distance_verified", non_claims)
        self.assertIn("formal_test_completed", non_claims)
        self.assertIn("release_eligible", non_claims)

    def test_user_and_admin_have_distinct_machine_boundaries(self) -> None:
        self.assertEqual(self.user["application_id"], "kr.co.hanium.dreamup.walksafe")
        self.assertEqual(self.user["display_name"]["canonical"], "WalkSafe(워크세이프)")
        self.assertEqual(self.user["display_name"]["default"], "WalkSafe")
        self.assertEqual(self.user["display_name"]["ko"], "워크세이프")
        self.assertEqual(self.admin["application_id"], "kr.co.hanium.dreamup.walksafe.admin")
        self.assertNotEqual(self.user["application_id"], self.admin["application_id"])
        self.assertNotEqual(self.user["signing"]["contract_id"], self.admin["signing"]["contract_id"])
        self.assertNotEqual(self.user["session"]["contract_id"], self.admin["session"]["contract_id"])
        self.assertNotEqual(self.user["session"]["server_audience"], self.admin["session"]["server_audience"])
        self.assertNotEqual(
            self.user["distribution"]["target_channel"],
            self.admin["distribution"]["target_channel"],
        )
        self.assertEqual(self.user["signing"]["configuration_state"], "NOT_CONFIGURED")
        self.assertEqual(self.admin["signing"]["configuration_state"], "NOT_CONFIGURED")

    def test_lifecycle_stage_and_distribution_cohort_are_separate_and_closed(self) -> None:
        control = self.config["release_control"]
        self.assertEqual(control["current_lifecycle_stage"], "PRE_DEMO_DEVELOPMENT")
        self.assertEqual(control["current_distribution_cohort"], "NO_EXTERNAL_DISTRIBUTION")
        self.assertIn("PUBLIC_RELEASE", control["lifecycle_stage_values"])
        self.assertIn("PUBLIC", control["distribution_cohort_values"])
        self.assertNotEqual(control["current_lifecycle_stage"], control["current_distribution_cohort"])
        self.assertEqual(control["release_eligibility"], "NOT_ELIGIBLE")
        self.assertFalse(control["public_distribution_allowed"])
        self.assertEqual(len(control["remaining_gates"]), 5)
        self.assertTrue(all(gate["execution_status"] == "NOT_RUN" for gate in control["remaining_gates"]))
        self.assertTrue(all(gate["waived"] is False for gate in control["remaining_gates"]))
        public_requirements = control["promotion_rules"]["public_release_requires"]
        self.assertEqual(public_requirements["release_eligibility"], "ELIGIBLE")
        self.assertEqual(public_requirements["all_remaining_gates_execution_status"], "PASSED")
        self.assertTrue(public_requirements["approved_release_manifest"])
        self.assertEqual(public_requirements["approved_distribution_cohort"], "PUBLIC")

    def test_gradle_declares_two_distinct_product_modules_and_roles(self) -> None:
        settings = SETTINGS_PATH.read_text(encoding="utf-8")
        self.assertRegex(settings, r'include\(\s*":app"\s*\)')
        self.assertRegex(settings, r'include\(\s*":adminapp"\s*\)')
        user_build = USER_BUILD_PATH.read_text(encoding="utf-8")
        admin_build = ADMIN_BUILD_PATH.read_text(encoding="utf-8")
        self.assertIn('applicationId = "kr.co.hanium.dreamup.walksafe"', user_build)
        self.assertIn('"WALKSAFE_PRODUCT_ROLE", "\\"USER\\""', user_build)
        self.assertIn('applicationId = "kr.co.hanium.dreamup.walksafe.admin"', admin_build)
        self.assertIn('"WALKSAFE_PRODUCT_ROLE", "\\"ADMIN\\""', admin_build)
        self.assertIn('"ADMIN_SECURITY_WORKFLOWS_ENABLED", "true"', admin_build)
        self.assertIn('"ADMIN_OPERATIONAL_WORKFLOWS_ENABLED", "false"', admin_build)
        self.assertIn('"ADMIN_AUTHENTICATION_MODE", "\\"PASSWORD_TOTP\\""', admin_build)
        self.assertIn("Release builds require WALKSAFE_ADMIN_API_ORIGIN", admin_build)
        self.assertNotIn("signingConfig =", user_build)
        self.assertNotIn("signingConfig =", admin_build)

    def test_admin_security_channel_has_only_internet_and_operational_workflows_stay_locked(self) -> None:
        root = ET.parse(ADMIN_MANIFEST_PATH).getroot()
        permissions = {
            node.attrib[f"{ANDROID_NS}name"]
            for node in root.findall("uses-permission")
        }
        self.assertEqual(permissions, {"android.permission.INTERNET"})
        application = root.find("application")
        self.assertIsNotNone(application)
        self.assertEqual(application.attrib[f"{ANDROID_NS}allowBackup"], "false")
        self.assertEqual(application.attrib[f"{ANDROID_NS}usesCleartextTraffic"], "false")
        activities = application.findall("activity")
        self.assertEqual(len(activities), 1)
        self.assertEqual(activities[0].attrib[f"{ANDROID_NS}name"], ".AdminBoundaryActivity")
        all_sources = "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted((ADMIN_ROOT / "src/main/java").rglob("*.java"))
        )
        for forbidden_permission in (
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
        ):
            self.assertNotIn(forbidden_permission, all_sources)
        self.assertNotRegex(all_sources, r"(?i)(password|totp|recovery|access)[_-]?(?:token|code)?\s*=\s*\"[^\"]+\"")
        self.assertTrue(self.admin["fail_closed"]["security_control_workflows_enabled"])
        self.assertFalse(self.admin["fail_closed"]["operational_workflows_enabled"])
        self.assertTrue(self.admin["fail_closed"]["network_access_enabled"])
        self.assertEqual(
            self.admin["fail_closed"]["manifest_permissions"],
            ["android.permission.INTERNET"],
        )
        self.assertEqual(self.admin["fail_closed"]["runtime_permissions"], [])
        self.assertEqual(self.admin["fail_closed"]["unlock_requires_epic"], "EPIC-01")
        self.assertEqual(self.admin["authentication"]["mode"], "PASSWORD_TOTP")
        self.assertFalse(self.admin["authentication"]["public_or_shared_password_allowed"])
        self.assertFalse(self.admin["authentication"]["hidden_bypass_password_allowed"])
        self.assertFalse(self.admin["recovery"]["server_stores_plaintext_recovery_code"])
        self.assertEqual(
            self.admin["recovery"]["code_consumption"],
            "ONLY_AFTER_SUCCESSFUL_RECOVERY_COMPLETION",
        )
        self.assertIn(
            "SAME_UNUSED_CODE_AND_SAME_DEVICE",
            self.admin["recovery"]["response_loss_recovery"],
        )
        self.assertEqual(self.admin["recovery"]["formal_phone_loss_drill_status"], "NOT_RUN")
        activity = ADMIN_ACTIVITY_PATH.read_text(encoding="utf-8")
        self.assertIn("BuildConfig.ADMIN_SECURITY_WORKFLOWS_ENABLED", activity)
        self.assertIn("BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED", activity)
        self.assertIn("운영 업무", activity)

    def test_purpose_device_and_legacy_web_boundaries_are_explicit(self) -> None:
        purpose = self.config["product_purpose"]
        self.assertIn("가까운 위험과 이동 방향", purpose["canonical_statement"])
        self.assertIn("손상 점자블록 신고", purpose["canonical_statement"])
        self.assertIn("보행 안전을 보장하지 않으며", purpose["safety_limitation"])
        self.assertIn("흰지팡이·안내견·보호자", purpose["safety_limitation"])
        self.assertEqual(len(purpose["required_surfaces"]), 4)

        support = self.config["device_support"]
        self.assertEqual(support["install_min_sdk"], 26)
        self.assertEqual(support["official_support_candidate_min_sdk"], 31)
        self.assertEqual(
            support["official_support_approval_status"],
            "PENDING_DESIGNATED_DEVICE_VERIFICATION",
        )
        self.assertEqual(support["capability_tiers"], ["FULL", "LIMITED", "BLOCKED"])
        self.assertEqual(
            support["phase_a_full_tier_status"],
            "BLOCKED_PENDING_RUNTIME_METRIC_VERIFICATION",
        )
        self.assertIn("STABLE_METRIC_DEPTH_FRAMES", support["full_tier_requires"])
        self.assertIn("APPROVED_DESIGNATED_DEVICE_PROFILE", support["full_tier_requires"])
        self.assertFalse(support["approved_designated_device_profile"])
        self.assertIsNone(support["approved_designated_device_profile_version"])
        self.assertIn(
            "non-empty approved profile version",
            support["future_full_tier_input_contract"],
        )
        self.assertEqual(
            support["limited_notice"],
            "이 휴대폰은 거리를 잴 수 없어 물체 종류만 알려드립니다. 거리와 안전 여부는 판단하지 않습니다",
        )
        self.assertEqual(support["unknown_capability_behavior"], "BLOCKED")

        legacy = self.config["products"]["legacy_web"]
        gateway = self.config["products"]["android_api_gateway"]
        self.assertEqual(gateway["product_role"], "ANDROID_API_GATEWAY")
        self.assertEqual(gateway["source_path"], "apps/android-gateway")
        self.assertEqual(gateway["official_local_bind"], "127.0.0.1:8081")
        self.assertEqual(gateway["backend_origin"], "http://127.0.0.1:8000")
        self.assertEqual(
            gateway["public_routes"],
            [
                "/api/field-session",
                "/api/navigation/walking",
                "/api/navigation/destinations/search",
                "/api/reports/v2",
                "/api/field-walk",
            ],
        )
        self.assertEqual(gateway["legacy_next_fallback"], "PROHIBITED")
        self.assertEqual(gateway["deployment_status"], "NOT_RUN")
        self.assertEqual(gateway["actual_device_connectivity_status"], "NOT_RUN")
        self.assertEqual(legacy["product_role"], "LEGACY_REFERENCE_ONLY")
        self.assertFalse(legacy["external_user_runtime_allowed"])
        self.assertFalse(legacy["formal_release_component_allowed"])
        self.assertEqual(
            legacy["technical_closure_status"],
            "COMPLETE_WITHOUT_RUNTIME_ALLOWLIST_INTERNAL",
        )
        self.assertEqual(legacy["remaining_executable_historical_inputs"], [])
        self.assertEqual(legacy["local_execution_exception"]["legacy_ui_response_status"], 410)
        self.assertEqual(
            legacy["local_execution_exception"]["official_npm_runtime_bind"],
            "127.0.0.1:3000",
        )
        self.assertEqual(
            legacy["transitional_android_api_routes"]["extraction_status"],
            "INTERNAL_IMPLEMENTATION_VERIFIED_NOT_DEPLOYED",
        )
        self.assertEqual(
            legacy["transitional_android_api_routes"]["runtime_allowlist"],
            [],
        )

        release = self.config["release_control"]
        self.assertIn("ANDROID_USER_APP", release["immutable_bundle_components"])
        self.assertIn("ANDROID_API_GATEWAY", release["immutable_bundle_components"])
        self.assertEqual(
            release["approval_invalidation_rule"],
            "ANY_COMPONENT_OR_CONFIGURATION_CHANGE_INVALIDATES_THE_PREVIOUS_STAGE_APPROVAL",
        )


if __name__ == "__main__":
    unittest.main()
