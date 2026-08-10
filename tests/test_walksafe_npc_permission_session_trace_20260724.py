import json
from pathlib import Path
import subprocess
import unittest

from scripts import build_walksafe_npc_permission_session_trace_20260724 as builder
from scripts import check_walksafe_goal_graph as graph


ROOT = Path(__file__).resolve().parents[1]


def completed_successor_reaches_live(
    goal_id: str,
    relative_path: str,
    historical_sha256: str,
) -> bool:
    checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
    manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
    paths = [row["path"] for row in manifest["goal_graph"]["static_nodes"]]
    paths.extend(
        path
        for path in graph.discover_package_paths(ROOT)
        if "/work-items/" in path and path.endswith(".md")
    )
    nodes = {}
    for relative in paths:
        metadata, _ = graph.parse_goal(ROOT / relative)
        nodes[metadata["goal_id"]] = metadata
    completion_events = {
        event["subject_goal_id"]: event
        for event in checkpoint["goal_execution"]["transition_history"]
        if event.get("event_type") == "GOAL_COMPLETED"
    }
    completion_bindings = {
        goal: event.get("completion_evidence_bindings", {})
        for goal, event in completion_events.items()
    }
    return graph.completed_successor_artifact_chain_reaches_live(
        ROOT,
        goal_id=goal_id,
        relative_path=relative_path,
        historical_sha256=historical_sha256,
        nodes=nodes,
        completion_bindings_by_goal=completion_bindings,
        completion_event_by_goal=completion_events,
    )


class WalkSafeNpcPermissionSessionTraceTest(unittest.TestCase):
    def load(self, path: Path) -> dict:
        value = json.loads(path.read_text(encoding="utf-8"))
        self.assertIsInstance(value, dict)
        return value

    def test_builder_outputs_are_current(self) -> None:
        completed = subprocess.run(
            ["python3", str(builder.BUILDER)],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("outputs=12", completed.stdout)

    def test_predecessors_remain_byte_exact(self) -> None:
        for path, expected in builder.EXPECTED_PREDECESSOR_SHA256.items():
            self.assertEqual(builder.base.sha256_file(path), expected)

    def test_state_matrix_is_sealed_and_independent(self) -> None:
        matrix = self.load(builder.MATRIX_JSON)
        self.assertTrue(
            graph.continuation.object_seal_is_valid(matrix, "matrix_sha256")
        )
        states = {row["state_id"]: row for row in matrix["states"]}
        self.assertEqual(len(states), len(matrix["states"]))
        self.assertTrue(
            {
                "OS_RUNTIME_PERMISSIONS",
                "ACCOUNT_LOGIN_SESSION",
                "RAW_COLLECTION_CONSENT",
                "AUTOMATIC_REPORT_CONSENT",
                "MOBILE_NETWORK_CHOICE",
                "WALK_AND_ROUTE_RUNTIME",
                "SERVER_DATA_RIGHTS_ENTRY",
            }.issubset(states)
        )
        self.assertIn(
            "ANDROID_KEYSTORE_AES_GCM",
            states["ACCOUNT_LOGIN_SESSION"]["storage"],
        )
        self.assertEqual(
            states["SERVER_DATA_RIGHTS_ENTRY"]["lifetime"],
            "INDEPENDENT_OF_APP_INSTALL_AND_LOGIN",
        )
        self.assertEqual(
            matrix["boundary"]["external_rights_operation_status"],
            "NOT_RUN",
        )

    def test_result_set_is_typed_and_hash_bound(self) -> None:
        implementation = self.load(builder.IMPLEMENTATION_JSON)
        verification = self.load(builder.VERIFICATION_JSON)
        successor = self.load(builder.SUCCESSOR_JSON)
        self.assertEqual(
            {
                implementation["kind"],
                verification["kind"],
                successor["kind"],
            },
            {"IMPLEMENTATION_RECORD", "VERIFICATION_RESULT", "SUCCESSOR_TRACE"},
        )
        self.assertEqual(
            implementation["state_ownership_matrix"]["sha256"],
            graph.sha256_file(builder.MATRIX_JSON),
        )
        for record in implementation["changed_artifacts"]:
            live_sha256 = graph.sha256_file(ROOT / record["path"])
            self.assertTrue(
                live_sha256 == record["after_sha256"]
                or completed_successor_reaches_live(
                    builder.GOAL_ID,
                    record["path"],
                    record["after_sha256"],
                ),
                record["path"],
            )
        for check in verification["checks"]:
            self.assertEqual(check["exit_code"], 0)
            self.assertEqual(
                graph.sha256_file(ROOT / check["output_path"]),
                check["output_sha256"],
            )
        self.assertEqual(verification["android_test_summary"]["tests"], 452)
        self.assertEqual(verification["gateway_test_summary"]["contract_tests"], 16)
        self.assertEqual(verification["gateway_test_summary"]["boundary_tests"], 18)

    def test_gap_reassesses_only_gap006_without_external_overclaim(self) -> None:
        before = self.load(builder.GAP_R009_JSON)
        after = self.load(builder.GAP_R010_JSON)
        before_rows = {row["gap_id"]: row for row in before["assessments"]}
        after_rows = {row["gap_id"]: row for row in after["assessments"]}
        changed = {
            gap_id
            for gap_id in before_rows
            if before_rows[gap_id] != after_rows[gap_id]
        }
        self.assertEqual(changed, {"GAP-006"})
        row = after_rows["GAP-006"]
        self.assertEqual(row["status"], "PARTIAL")
        self.assertEqual(row["formal_test_status"], "NOT_RUN")
        self.assertEqual(
            row["permission_session_reassessment"]["actual_device_status"],
            "NOT_RUN",
        )
        self.assertEqual(after["summary"]["release_status"], "NOT_ELIGIBLE")
        self.assertTrue(
            graph.continuation.object_seal_is_valid(row, "assessment_sha256")
        )
        self.assertTrue(
            graph.continuation.object_seal_is_valid(after, "report_content_sha256")
        )

    def test_canonical_successor_scope_is_exact(self) -> None:
        before = {
            "IMPLEMENTATION_GAP": {
                "role": "IMPLEMENTATION_GAP",
                "path": builder.relative(builder.GAP_R009_JSON),
                "document_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260724-009",
                "identity_json_path": "metadata.report_id",
                "file_sha256": builder.base.sha256_file(builder.GAP_R009_JSON),
                "mutable": False,
            },
            "IMPLEMENTATION_BACKLOG": {
                "role": "IMPLEMENTATION_BACKLOG",
                "path": builder.relative(builder.BACKLOG_R009_JSON),
                "document_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260724-009",
                "identity_json_path": "metadata.backlog_id",
                "file_sha256": builder.base.sha256_file(builder.BACKLOG_R009_JSON),
                "mutable": False,
            },
        }
        successor = self.load(builder.SUCCESSOR_JSON)
        after = successor["resulting_canonical_bindings"]
        errors, subjects = graph.canonical_changed_subject_ids_by_role(
            ROOT,
            changed_roles=["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"],
            bindings_before=before,
            bindings_after=after,
        )
        self.assertEqual(errors, [])
        self.assertEqual(
            subjects,
            {
                "IMPLEMENTATION_BACKLOG": [
                    "NPC-PERMISSION-SESSION-LIFECYCLE"
                ],
                "IMPLEMENTATION_GAP": [
                    "GAP-006",
                    "NPC-PERMISSION-SESSION-LIFECYCLE",
                ],
            },
        )
        self.assertEqual(successor["changed_subject_ids_by_role"], subjects)
        self.assertEqual(
            successor["next_policy_gap_pair"],
            {"source_policy_id": "FP-004", "gap_id": "GAP-013"},
        )

    def test_review_and_receipt_bind_exact_results(self) -> None:
        receipt = self.load(builder.RECEIPT_JSON)
        review = self.load(builder.REVIEW_JSON)
        result_hashes = {
            item["kind"]: item["sha256"] for item in receipt["result_evidence"]
        }
        self.assertEqual(review["reviewed_result_sha256_by_kind"], result_hashes)
        self.assertEqual(
            receipt["execution_start_event_sha256"],
            builder.EXPECTED_START_EVENT_SHA256,
        )
        self.assertNotEqual(receipt["executor"]["id"], receipt["reviewer"]["id"])
        self.assertFalse(review["review_boundary"]["external_independence_claimed"])
        self.assertEqual(receipt["reviewer"]["decision"], "APPROVED")
        self.assertEqual(
            receipt["reviewer_provenance"]["sha256"],
            builder.base.sha256_file(builder.REVIEW_JSON),
        )


if __name__ == "__main__":
    unittest.main()
