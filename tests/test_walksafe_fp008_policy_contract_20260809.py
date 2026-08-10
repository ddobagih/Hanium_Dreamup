from __future__ import annotations

import base64
import copy
import hashlib
import json
from pathlib import Path
import re
import unittest
from urllib.parse import quote


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = (
    ROOT
    / "docs/control/execution/goal-results"
    / "WS-GOAL-EPIC-03-FP-008-R001/policy-contract.json"
)

CHALLENGE_EXACT13 = (
    "action",
    "admin_id",
    "body_sha256",
    "correlation_id",
    "device_id",
    "device_key_marker",
    "device_key_version",
    "method",
    "path",
    "purpose",
    "query_sha256",
    "read_purpose",
    "session_id",
)
SIGNED_EXACT18 = (
    "action",
    "admin_id",
    "body_sha256",
    "challenge_id",
    "correlation_id",
    "device_id",
    "device_key_marker",
    "device_key_version",
    "expires_at_epoch_ms",
    "issued_at_epoch_ms",
    "method",
    "nonce",
    "path",
    "purpose",
    "query_sha256",
    "read_purpose",
    "schema_version",
    "session_id",
)
DECISION_EXACT6 = (
    "decision",
    "reason",
    "duplicate_of_report_id",
    "location_reviewed",
    "photo_reviewed",
    "privacy_reviewed",
)
DELIVERY_EXACT10 = (
    "institution",
    "channel",
    "recipient",
    "status",
    "external_receipt_id",
    "reason",
    "evidence_sha256",
    "observed_at",
    "expected_revision",
    "idempotency_key",
)


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _canonical_json(value: object) -> str:
    return json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )


def _canonical_query(pairs: list[list[str]]) -> str:
    encoded = sorted(
        (
            quote(name, safe="-._~", encoding="utf-8", errors="strict"),
            quote(value, safe="-._~", encoding="utf-8", errors="strict"),
        )
        for name, value in pairs
    )
    return "&".join(f"{name}={value}" for name, value in encoded)


class WalkSafeFp008PolicyContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.raw = CONTRACT.read_bytes()
        cls.contract = json.loads(
            cls.raw,
            object_pairs_hook=_reject_duplicate_keys,
        )

    def test_identity_source_binding_and_self_seal(self) -> None:
        contract = self.contract
        self.assertEqual(contract["schema_version"], "1.0")
        self.assertEqual(contract["goal_id"], "WS-GOAL-EPIC-03-FP-008-R001")
        self.assertEqual(contract["policy_id"], "FP-008")
        self.assertEqual(contract["gap_id"], "GAP-017")
        self.assertEqual(contract["status"], "CONTRACT_DEFINED")

        source = contract["source_binding"]
        source_path = ROOT / source["path"]
        self.assertTrue(source_path.is_file())
        self.assertEqual(
            hashlib.sha256(source_path.read_bytes()).hexdigest(),
            source["sha256"],
        )

        canonical_subject = copy.deepcopy(contract)
        expected = canonical_subject["integrity"].pop("content_sha256")
        observed = hashlib.sha256(
            _canonical_json(canonical_subject).encode("utf-8")
        ).hexdigest()
        self.assertEqual(observed, expected)
        self.assertRegex(expected, r"^[0-9a-f]{64}$")

    def test_android_only_separate_admin_boundary_has_no_external_send(self) -> None:
        boundary = self.contract["product_boundary"]
        self.assertEqual(
            boundary["supported_surface"],
            "ANDROID_ADMIN_APP_AND_BACKEND",
        )
        self.assertEqual(
            boundary["separate_from_user_app"],
            {
                "application_id": True,
                "signing_boundary": True,
                "session_namespace": True,
            },
        )
        self.assertTrue(boundary["registered_admin_device_required"])
        self.assertTrue(boundary["additional_admin_authentication_required"])
        self.assertEqual(
            boundary["institution_delivery_mode"],
            "MANUAL_RECORD_ONLY",
        )
        self.assertFalse(boundary["outbound_institution_dispatch"])

        text = self.raw.decode("utf-8")
        for excluded in ("R034", "R035", "apps/web", "PWA"):
            with self.subTest(excluded=excluded):
                self.assertNotIn(excluded, text)

    def test_proof_exact18_and_response_exact19_are_closed(self) -> None:
        proof = self.contract["admin_device_proof"]
        self.assertEqual(
            proof["proof_schema_version"],
            "walksafe.admin-device-proof.v2",
        )
        self.assertEqual(
            tuple(proof["challenge_purposes"]),
            ("LOGIN", "ACTION", "RECOVERY_COMPLETE"),
        )
        challenge = proof["challenge_request"]
        self.assertEqual(challenge["method"], "POST")
        self.assertEqual(
            challenge["path"],
            "/admin/security/device-proof/challenges",
        )
        self.assertEqual(tuple(challenge["exact_fields"]), CHALLENGE_EXACT13)
        self.assertEqual(len(set(challenge["exact_fields"])), 13)
        self.assertTrue(challenge["all_exact_fields_present"])
        self.assertTrue(challenge["unknown_fields_rejected"])
        self.assertEqual(
            set(challenge["nullable_fields"]),
            {"action", "read_purpose", "session_id"},
        )
        self.assertTrue(challenge["purpose_is_challenge_type"])
        self.assertTrue(challenge["separate_challenge_type_field_forbidden"])
        self.assertNotIn("challenge_type", challenge["exact_fields"])
        self.assertEqual(tuple(proof["signed_payload_exact_fields"]), SIGNED_EXACT18)
        self.assertEqual(
            tuple(proof["challenge_response_exact_fields"]),
            SIGNED_EXACT18 + ("signing_payload",),
        )
        self.assertEqual(len(set(proof["signed_payload_exact_fields"])), 18)
        self.assertEqual(len(set(proof["challenge_response_exact_fields"])), 19)
        self.assertTrue(proof["physical_key_rule"]["all_exact_fields_present"])
        self.assertTrue(proof["physical_key_rule"]["unknown_fields_rejected"])
        self.assertEqual(
            set(proof["physical_key_rule"]["nullable_fields"]),
            {"action", "read_purpose", "session_id"},
        )

    def test_proof_operation_literals_are_unambiguous(self) -> None:
        rule = self.contract["admin_device_proof"]["physical_key_rule"]
        operations = rule["action_and_read_purpose_by_operation"]
        self.assertEqual(
            set(operations),
            {
                "LOGIN",
                "RECOVERY_COMPLETE",
                "REPORT_DECISION_POST",
                "DELIVERY_EVENT_POST",
                "DECISION_HISTORY_GET",
                "DELIVERY_HISTORY_GET",
            },
        )
        self.assertEqual(
            operations["LOGIN"],
            {"action": None, "read_purpose": None},
        )
        self.assertEqual(
            operations["RECOVERY_COMPLETE"],
            {"action": None, "read_purpose": None},
        )
        self.assertEqual(
            operations["REPORT_DECISION_POST"],
            {
                "purpose": "ACTION",
                "method": "POST",
                "action": "report.review.decide",
                "read_purpose": None,
            },
        )
        self.assertEqual(
            operations["DELIVERY_EVENT_POST"],
            {
                "purpose": "ACTION",
                "method": "POST",
                "action": "report.delivery.create",
                "read_purpose": None,
            },
        )
        self.assertEqual(
            operations["DECISION_HISTORY_GET"],
            {
                "purpose": "ACTION",
                "method": "GET",
                "action": None,
                "read_purpose": "report.review_decisions",
            },
        )
        self.assertEqual(
            operations["DELIVERY_HISTORY_GET"],
            {
                "purpose": "ACTION",
                "method": "GET",
                "action": None,
                "read_purpose": "report.delivery_events",
            },
        )

    def test_canonical_query_vectors_preserve_exact_semantics(self) -> None:
        query = self.contract["admin_device_proof"]["canonical_query_v1"]
        self.assertEqual(query["plus_character"], "LITERAL_PLUS_NOT_SPACE")
        self.assertEqual(query["space_encoding"], "%20")
        self.assertEqual(query["duplicate_pairs"], "PRESERVE")
        self.assertEqual(query["blank_names_and_values"], "PRESERVE")
        for vector in query["vectors"]:
            with self.subTest(vector=vector["name"]):
                canonical = _canonical_query(vector["pairs"])
                self.assertEqual(canonical, vector["canonical"])
                self.assertEqual(
                    hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
                    vector["sha256"],
                )

        absent = query["vectors"][0]
        self.assertEqual(absent["canonical"].encode("utf-8"), b"")
        self.assertEqual(
            absent["sha256"],
            hashlib.sha256(b"").hexdigest(),
        )

    def test_signing_payload_vector_and_freshness_are_exact(self) -> None:
        proof = self.contract["admin_device_proof"]
        vector = proof["proof_vector"]
        exact18 = vector["exact18"]
        self.assertEqual(tuple(exact18), SIGNED_EXACT18)
        signing_payload = _canonical_json(exact18)
        self.assertEqual(
            hashlib.sha256(signing_payload.encode("utf-8")).hexdigest(),
            vector["signing_payload_sha256"],
        )
        response = {**exact18, "signing_payload": signing_payload}
        self.assertEqual(tuple(response), SIGNED_EXACT18 + ("signing_payload",))
        self.assertEqual(response["signing_payload"], signing_payload)
        canonical = proof["canonical_json"]
        self.assertEqual(canonical["encoding"], "UTF-8")
        self.assertTrue(canonical["sort_keys"])
        self.assertEqual(canonical["separators"], [",", ":"])
        self.assertFalse(canonical["ensure_ascii"])
        self.assertEqual(
            canonical["signing_payload_representation"],
            "JSON_STRING_NOT_BASE64",
        )
        self.assertEqual(
            canonical["signed_bytes"],
            "UTF8_BYTES_OF_SIGNING_PAYLOAD",
        )

        freshness = proof["freshness_and_replay"]
        self.assertEqual(freshness["ttl_ms"], 120000)
        self.assertEqual(
            exact18["expires_at_epoch_ms"] - exact18["issued_at_epoch_ms"],
            freshness["ttl_ms"],
        )
        nonce = exact18["nonce"]
        self.assertEqual(len(nonce), 43)
        self.assertIsNone(re.search(r"[^A-Za-z0-9_-]", nonce))
        decoded_nonce = base64.urlsafe_b64decode(nonce + "=")
        self.assertEqual(len(decoded_nonce), freshness["nonce_random_bytes"])
        self.assertNotIn("=", nonce)

    def test_body_query_key_and_signature_encodings_are_closed(self) -> None:
        proof = self.contract["admin_device_proof"]
        binding = proof["request_binding"]
        empty_sha = hashlib.sha256(b"").hexdigest()
        self.assertEqual(binding["body_sha256"]["input"], "EXACT_TRANSMITTED_BODY_BYTES")
        self.assertEqual(binding["body_sha256"]["output"], "LOWERCASE_HEX_64")
        self.assertEqual(binding["query_sha256"]["absent_query_input"], "EMPTY_BYTES")
        self.assertEqual(proof["proof_vector"]["exact18"]["body_sha256"], empty_sha)
        self.assertEqual(proof["proof_vector"]["exact18"]["query_sha256"], empty_sha)

        signature = proof["device_key_and_signature"]
        self.assertEqual(signature["algorithm"], "ECDSA_P256_SHA256")
        self.assertEqual(signature["public_key_format"], "SPKI_DER")
        self.assertEqual(
            signature["device_key_marker"],
            "SHA256_SPKI_DER_LOWERCASE_HEX_64",
        )
        self.assertEqual(signature["signature_format"], "ASN1_DER")
        self.assertEqual(signature["signature_encoding"], "BASE64URL_NO_PADDING")

    def test_decision_exact6_and_server_derived_audit_are_closed(self) -> None:
        decision = self.contract["report_decision"]
        self.assertEqual(tuple(decision["request_exact_fields"]), DECISION_EXACT6)
        self.assertTrue(decision["unknown_fields_rejected"])
        self.assertEqual(
            tuple(decision["decisions"]),
            ("APPROVED", "REJECTED", "DUPLICATE"),
        )
        self.assertEqual(decision["reason"], "NON_BLANK_REQUIRED")
        self.assertEqual(
            decision["review_flags"],
            {
                "fields": [
                    "location_reviewed",
                    "photo_reviewed",
                    "privacy_reviewed",
                ],
                "type": "BOOLEAN",
                "approved_requires_all_true": True,
            },
        )
        duplicate = decision["duplicate_target"]
        self.assertEqual(duplicate["required_only_for"], "DUPLICATE")
        self.assertTrue(duplicate["null_for_other_decisions"])
        self.assertTrue(duplicate["must_exist"])
        self.assertTrue(duplicate["must_not_reference_self"])
        self.assertTrue(set(decision["server_derived_fields"]).isdisjoint(DECISION_EXACT6))
        self.assertEqual(decision["history"], "APPEND_ONLY")
        self.assertEqual(decision["effective_decision"], "LATEST_REVISION")

    def test_delivery_exact10_transition_and_idempotency_are_closed(self) -> None:
        delivery = self.contract["manual_delivery"]
        self.assertEqual(tuple(delivery["request_exact_fields"]), DELIVERY_EXACT10)
        self.assertTrue(delivery["unknown_fields_rejected"])
        self.assertEqual(
            tuple(delivery["allowed_statuses"]),
            ("SUBMITTED", "ACKNOWLEDGED", "RESOLVED", "FAILED"),
        )
        self.assertEqual(delivery["initial_transitions"], ["SUBMITTED", "FAILED"])
        self.assertEqual(
            delivery["transitions"],
            {
                "FAILED": ["FAILED", "SUBMITTED"],
                "SUBMITTED": ["ACKNOWLEDGED", "FAILED"],
                "ACKNOWLEDGED": ["RESOLVED"],
                "RESOLVED": [],
            },
        )
        self.assertEqual(
            delivery["report_eligibility"],
            {
                "latest_decision_must_equal": "APPROVED",
                "latest_location_reviewed_must_equal": True,
                "latest_photo_reviewed_must_equal": True,
                "latest_privacy_reviewed_must_equal": True,
                "recheck_before_every_delivery_append": True,
            },
        )
        self.assertEqual(delivery["history"], "APPEND_ONLY")
        self.assertEqual(delivery["external_receipt_id"], "NULL_OR_NON_BLANK_STRING")
        self.assertEqual(delivery["evidence_sha256"], "NULL_OR_LOWERCASE_HEX_64")
        self.assertEqual(
            delivery["expected_revision"],
            "INTEGER_GREATER_THAN_OR_EQUAL_TO_ZERO",
        )
        self.assertEqual(delivery["idempotency_key"], "UUID")

        idempotency = delivery["idempotency"]
        self.assertEqual(idempotency["same_key_same_request"], "RETURN_ORIGINAL_RESULT")
        self.assertEqual(idempotency["same_key_different_request"], "REJECT_CONFLICT")
        self.assertTrue(idempotency["retry_check_precedes_revision_check"])
        self.assertTrue(delivery["side_effect"]["record_only"])
        self.assertFalse(delivery["side_effect"]["external_send_performed"])
        self.assertFalse(
            delivery["side_effect"]["external_receipt_generation_performed"]
        )

    def test_admin_failure_isolated_and_claim_boundary_remains_open(self) -> None:
        isolation = self.contract["audit_and_failure_isolation"]
        self.assertTrue(isolation["protected_read_audit_required"])
        self.assertTrue(isolation["decision_audit_required"])
        self.assertTrue(isolation["delivery_audit_required"])
        self.assertTrue(isolation["audit_actor_from_verified_session"])
        self.assertTrue(isolation["admin_failure"]["admin_alert_required"])
        self.assertTrue(isolation["admin_failure"]["internal_audit_required"])
        self.assertTrue(
            isolation["admin_failure"]["normally_operating_user_walk_continues"]
        )
        self.assertTrue(
            isolation["shared_realtime_safety_impact"][
                "affected_feature_only_safe_stop"
            ]
        )
        self.assertTrue(
            isolation["shared_realtime_safety_impact"][
                "user_reason_notice_required"
            ]
        )

        boundary = self.contract["acceptance_boundary"]
        self.assertEqual(
            [test["id"] for test in boundary["planned_tests"]],
            [f"TC-FP-008-0{number}" for number in range(1, 5)],
        )
        self.assertEqual(
            {test["formal_status"] for test in boundary["planned_tests"]},
            {"NOT_RUN"},
        )
        status_fields = (
            "actual_device_status",
            "external_institution_status",
            "external_authentication_status",
            "operational_database_status",
            "deployment_status",
            "independent_external_security_review_status",
            "independent_external_privacy_review_status",
            "formal_test_status",
        )
        for field in status_fields:
            with self.subTest(field=field):
                self.assertEqual(boundary[field], "NOT_RUN")
        self.assertEqual(boundary["gap_before_internal_reassessment"], "MISSING")
        self.assertEqual(boundary["maximum_gap_after_internal_reassessment"], "PARTIAL")
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")


if __name__ == "__main__":
    unittest.main()
