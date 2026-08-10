import json
from pathlib import Path
import subprocess
import unittest

from scripts import build_walksafe_fp018_walk_state_recovery_trace_20260724 as builder
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


class WalkSafeFp018TraceTest(unittest.TestCase):
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
        self.assertIn("outputs=11", completed.stdout)

    def test_predecessors_remain_byte_exact(self) -> None:
        for path, expected in builder.EXPECTED_PREDECESSOR_SHA256.items():
            self.assertEqual(builder.sha256_file(path), expected)

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
        self.assertTrue(implementation["changed_artifacts"])
        self.assertTrue(verification["checks"])
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

    def test_gap_reassesses_only_gap027_without_formal_overclaim(self) -> None:
        before = self.load(builder.GAP_R008_JSON)
        after = self.load(builder.GAP_R009_JSON)
        before_rows = {row["gap_id"]: row for row in before["assessments"]}
        after_rows = {row["gap_id"]: row for row in after["assessments"]}
        changed = {
            gap_id
            for gap_id in before_rows
            if before_rows[gap_id] != after_rows[gap_id]
        }
        self.assertEqual(changed, {"GAP-027"})
        self.assertEqual(after_rows["GAP-027"]["status"], "PARTIAL")
        self.assertEqual(after_rows["GAP-027"]["formal_test_status"], "NOT_RUN")
        self.assertEqual(after["summary"]["release_status"], "NOT_ELIGIBLE")
        self.assertTrue(
            graph.continuation.object_seal_is_valid(
                after_rows["GAP-027"],
                "assessment_sha256",
            )
        )
        self.assertTrue(
            graph.continuation.object_seal_is_valid(
                after,
                "report_content_sha256",
            )
        )

    def test_canonical_successor_scope_is_exact(self) -> None:
        before = {
            "IMPLEMENTATION_GAP": {
                "role": "IMPLEMENTATION_GAP",
                "path": builder.relative(builder.GAP_R008_JSON),
                "document_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-008",
                "identity_json_path": "metadata.report_id",
                "file_sha256": builder.sha256_file(builder.GAP_R008_JSON),
                "mutable": False,
            },
            "IMPLEMENTATION_BACKLOG": {
                "role": "IMPLEMENTATION_BACKLOG",
                "path": builder.relative(builder.BACKLOG_R008_JSON),
                "document_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-008",
                "identity_json_path": "metadata.backlog_id",
                "file_sha256": builder.sha256_file(builder.BACKLOG_R008_JSON),
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
                "IMPLEMENTATION_BACKLOG": ["FP-018"],
                "IMPLEMENTATION_GAP": ["FP-018", "GAP-027"],
            },
        )
        self.assertEqual(successor["changed_subject_ids_by_role"], subjects)

    def test_review_and_receipt_bind_exact_results(self) -> None:
        receipt = self.load(builder.RECEIPT_JSON)
        review = self.load(builder.REVIEW_JSON)
        result_hashes = {
            item["kind"]: item["sha256"]
            for item in receipt["result_evidence"]
        }
        self.assertEqual(review["reviewed_result_sha256_by_kind"], result_hashes)
        self.assertEqual(receipt["execution_start_event_sha256"], builder.EXPECTED_START_EVENT_SHA256)
        self.assertNotEqual(receipt["executor"]["id"], receipt["reviewer"]["id"])
        self.assertEqual(receipt["reviewer"]["decision"], "APPROVED")
        self.assertEqual(
            receipt["reviewer_provenance"]["sha256"],
            builder.sha256_file(builder.REVIEW_JSON),
        )


if __name__ == "__main__":
    unittest.main()
