from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import materialize_walksafe_fp046_goal_seq51_52_20260809 as materialize
from scripts import run_walksafe_fp046_goal_start_gate_20260809 as gate


ROOT = Path(__file__).resolve().parents[1]
EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-001"


def _ready_source() -> dict[str, object]:
    raw = (ROOT / materialize.CHECKPOINT).read_bytes()
    live = json.loads(raw)
    if hashlib.sha256(raw).hexdigest() == materialize.SOURCE_CHECKPOINT_SHA256:
        _, source = materialize.load_exact_source(ROOT)
        projected, _, _ = materialize.project(ROOT, source)
        return projected

    candidate = copy.deepcopy(live)
    state = candidate["goal_execution"]
    history = state["transition_history"]
    if len(history) < 52:
        raise AssertionError("live checkpoint is neither seq50 nor an FP046 successor")
    del history[52:]
    ready = history[-1]
    state["transition_history_anchor_sha256"] = ready["event_sha256"]
    state["validation_cutoff_at"] = ready["occurred_at"]
    state["status_by_goal"][materialize.GOAL_ID] = "READY"
    state["focus_goal_id"] = materialize.GOAL_ID
    state["focus_goal_path"] = materialize.GOAL_PATH.as_posix()
    state["focus_work_item_id"] = materialize.WORK_ITEM_ID
    state["focus_source"] = "IMPLEMENTATION_BACKLOG"
    state["ready_frontier_goal_ids"] = copy.deepcopy(materialize.READY_FRONTIER)
    candidate["current_work"]["work_item_id"] = materialize.WORK_ITEM_ID
    candidate["current_work"]["current_focus"] = (
        "FP046/GAP-055 Goal READY; active internal start gate not run"
    )
    candidate["current_work"]["release_completion_claimed"] = False
    return candidate


class WalkSafeFp046GoalStartGateTest(unittest.TestCase):
    def test_external_contract_has_the_exact_internal_nine_check_order(self) -> None:
        checks, contract = gate._load_gate_contract(ROOT)
        self.assertEqual(
            hashlib.sha256((ROOT / gate.CONTRACT_RELATIVE).read_bytes()).hexdigest(),
            gate.CONTRACT_FILE_SHA256,
        )
        self.assertEqual(gate.canonical_sha256(contract), gate.CONTRACT_CANONICAL_SHA256)
        self.assertEqual(
            tuple(check_id for check_id, _ in checks),
            gate.EXPECTED_CHECK_IDS,
        )
        commands = dict(checks)
        self.assertIn(
            "WALKSAFE_TEST_DATABASE_URL:?required dedicated test database",
            commands["BACKEND_TEST_DATABASE_PREFLIGHT"],
        )
        self.assertIn(
            "backend/tests/test_report_retention.py",
            commands["BACKEND_REPORT_STORAGE_RETENTION_POSTGRES"],
        )
        self.assertIn(":app:testDebugUnitTest", commands["ANDROID_USER_INTERNAL"])
        self.assertIn(
            "apps/android-gateway/dist/test/privacy-rights.test.js",
            commands["ANDROID_GATEWAY_PRIVACY_INTERNAL"],
        )
        for _, command in checks:
            for fragment in gate.FORBIDDEN_COMMAND_FRAGMENTS:
                self.assertNotIn(fragment, command.lower())

    def test_exact_seq52_ready_source_is_accepted(self) -> None:
        source = _ready_source()
        ready_sha256, occurred_at = gate._validate_ready_source(
            source,
            contract_binding=gate.expected_contract_binding(),
        )
        self.assertEqual(
            ready_sha256,
            source["goal_execution"]["transition_history"][-1]["event_sha256"],
        )
        self.assertEqual(ready_sha256, materialize.EXPECTED_READY_EVENT_SHA256)
        self.assertEqual(occurred_at.isoformat(), materialize.READY_AT)

    def test_ready_source_rejects_contract_or_lineage_tampering(self) -> None:
        source = _ready_source()
        wrong_binding = copy.deepcopy(gate.expected_contract_binding())
        wrong_binding["canonical_contract_sha256"] = "0" * 64
        with self.assertRaisesRegex(
            gate.GateError,
            "implementation-start contract binding differs",
        ):
            gate._validate_ready_source(source, contract_binding=wrong_binding)

        tampered = copy.deepcopy(source)
        tampered["goal_execution"]["transition_history"][-1][
            "previous_event_sha256"
        ] = "0" * 64
        with self.assertRaisesRegex(gate.GateError, "seq52 readiness event differs"):
            gate._validate_ready_source(
                tampered,
                contract_binding=gate.expected_contract_binding(),
            )

    def test_event_and_receipt_identity_are_goal_scoped(self) -> None:
        self.assertEqual(
            gate._document_id(EVENT_ID),
            "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP046-20260809-001",
        )
        for invalid in (
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260809-001",
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-1",
            "FP046-20260809-001",
        ):
            with self.subTest(event_id=invalid), self.assertRaises(gate.GateError):
                gate._document_id(invalid)

    def test_wrapper_uses_private_schema_and_exact_runtime_bindings(self) -> None:
        self.assertEqual(gate.RECEIPT_FIELDS, gate._impl.RECEIPT_FIELDS)
        self.assertIn("source_ready_event_sha256", gate.RECEIPT_FIELDS)
        self.assertIn("source_checkpoint_sha256", gate.RECEIPT_FIELDS)
        self.assertEqual(
            tuple(gate.RUNTIME_BINDING_RELATIVES),
            (
                Path("apps/android/gradle/wrapper/gradle-wrapper.properties"),
                Path("apps/android/gradle/wrapper/gradle-wrapper.jar"),
                Path("apps/android/gradle/verification-metadata.xml"),
                Path("apps/android/app/gradle.lockfile"),
                Path("apps/android-gateway/package-lock.json"),
                Path("configs/walksafe_node_toolchain_lock_20260715.json"),
            ),
        )

    def test_gate_scratch_home_allows_npm_state_without_weakening_its_identity(self) -> None:
        guard = gate._impl.RetainedRepositoryAuthorityGuard.capture(ROOT)
        try:
            home = guard.workspace / "home"
            entries = [entry for entry in guard.entries if entry.path == home]
            self.assertEqual(len(entries), 1)
            self.assertFalse(entries[0].seal_directory_inventory)
            (home / ".npm").mkdir()
            (home / ".npm" / "anonymous-state").write_text(
                "isolated\n",
                encoding="utf-8",
            )
            guard.verify()
            self.assertTrue(home.is_dir())
            self.assertFalse(home.is_symlink())
        finally:
            guard.close()

    def test_continuation_private_inventory_accepts_only_the_fp046_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            event_id = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-999"
            event_dir = root / continuation.FP008_GATE_ROOT_RELATIVE / event_id
            event_dir.mkdir(parents=True, mode=0o700)
            event_dir.chmod(0o700)
            for index, check_id in enumerate(gate.EXPECTED_CHECK_IDS, start=1):
                path = event_dir / f"{index:02d}-{check_id}.log"
                path.write_text("PASS\n", encoding="utf-8")
                path.chmod(0o600)
            receipt = event_dir / gate.RECEIPT_NAME
            receipt.write_text("{}\n", encoding="utf-8")
            receipt.chmod(0o600)
            metadata = event_dir.stat()
            checks = [
                {"check_id": check_id, "command": "true"}
                for check_id in gate.EXPECTED_CHECK_IDS
            ]
            self.assertEqual(
                continuation._validate_fp008_gate_event_inventory(
                    root,
                    event_id=event_id,
                    expected_checks=checks,
                    expected_directory_identity=(metadata.st_dev, metadata.st_ino),
                    label="FP046 private inventory regression",
                ),
                [],
            )
            checks[-1]["check_id"] = "UNDECLARED"
            self.assertIn(
                "ordered log inventory contract differs",
                continuation._validate_fp008_gate_event_inventory(
                    root,
                    event_id=event_id,
                    expected_checks=checks,
                    expected_directory_identity=(metadata.st_dev, metadata.st_ino),
                    label="FP046 private inventory regression",
                )[0],
            )


if __name__ == "__main__":
    unittest.main()
