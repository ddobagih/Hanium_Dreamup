from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import apply_walksafe_fp008_goal_started_seq47_20260803 as started
from scripts import check_walksafe_project_continuation_v2_4 as contract
from scripts import materialize_walksafe_fp008_goal_seq45_46_20260803 as materialize


ROOT = Path(__file__).resolve().parents[1]


class WalkSafeFp008GoalStartedSeq47Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.path = ROOT / started.CHECKPOINT
        cls.before_bytes = cls.path.read_bytes()
        cls.before_stat = cls.path.stat()
        if hashlib.sha256(cls.before_bytes).hexdigest() == started.SOURCE_CHECKPOINT_SHA256:
            cls.source_mode = True
            prepared = started.prepare(ROOT)
            cls.source = prepared.source
            cls.projected = prepared.projected
            cls.event = prepared.event
            cls.evidence = prepared.evidence
            prepared.guard.close()
        else:
            cls.source_mode = False
            cls.source = None
            successor = json.loads(cls.before_bytes)
            history = successor.get("goal_execution", {}).get(
                "transition_history",
                [],
            )
            if history and history[-1].get("event_type") == "WORK_SESSION_RESUMED":
                from scripts import (
                    apply_walksafe_fp008_work_session_resumed_seq48_20260809
                    as resumed,
                )

                successor, _source_bytes = (
                    resumed._source_from_resumed_checkpoint(successor)
                )
            from scripts import (
                reconcile_walksafe_fp008_session_snapshot_seq47_20260808
                as reconcile,
            )
            from scripts import (
                reconcile_walksafe_fp008_isolated_snapshot_fix_seq47a_20260809
                as corrective,
            )

            successor_overrides = {}
            frozen_overrides = {}
            for relative, expected in materialize.READY_CONTROL_SUCCESSOR_SHA256.items():
                observed = contract.sha256_file(ROOT / relative)
                if observed == expected:
                    continue
                delta = reconcile.AUTHORIZED_EXISTING_DELTAS.get(relative)
                correction = corrective.engine.AUTHORIZED_EXISTING_DELTAS.get(
                    relative
                )
                base_authorized = (
                    delta is not None
                    and delta["source_sha256"]
                    == materialize.READY_FROZEN_SOURCE_SHA256[relative]
                )
                if not (
                    base_authorized
                    and (
                        observed == delta["candidate_sha256"]
                        or (
                            correction is not None
                            and correction["source_sha256"]
                            == delta["candidate_sha256"]
                            and observed == correction["candidate_sha256"]
                        )
                    )
                ):
                    raise AssertionError(
                        f"current frozen-ready source is not an authorized delta: {relative}"
                )
                successor_overrides[relative] = observed
            for relative, expected in materialize.READY_FROZEN_SOURCE_SHA256.items():
                if relative in materialize.READY_CONTROL_SUCCESSOR_SHA256:
                    continue
                observed = contract.sha256_file(ROOT / relative)
                if observed == expected:
                    continue
                correction = corrective.engine.AUTHORIZED_EXISTING_DELTAS.get(
                    relative
                )
                if (
                    correction is None
                    or correction["source_sha256"] != expected
                    or correction["candidate_sha256"] != observed
                ):
                    raise AssertionError(
                        f"current frozen-ready source is not an authorized correction: {relative}"
                    )
                frozen_overrides[relative] = observed
            with mock.patch.dict(
                materialize.READY_CONTROL_SUCCESSOR_SHA256,
                successor_overrides,
            ), mock.patch.dict(
                materialize.READY_FROZEN_SOURCE_SHA256,
                frozen_overrides,
            ):
                ready = materialize.ready_checkpoint_from_exact_seq47_successor(
                    ROOT,
                    successor,
                )
            cls.evidence = started.load_gate_evidence(ROOT, ready)
            cls.projected, cls.event = started.project(
                ROOT,
                ready,
                cls.evidence,
            )
            started.require_started_checkpoint(ROOT, cls.projected)

    @classmethod
    def tearDownClass(cls) -> None:
        after = cls.path.stat()
        if cls.path.read_bytes() != cls.before_bytes:
            raise AssertionError("test changed live checkpoint bytes")
        if (after.st_dev, after.st_ino) != (
            cls.before_stat.st_dev,
            cls.before_stat.st_ino,
        ):
            raise AssertionError("test changed live checkpoint inode")

    def test_exact_seq46_source_and_success_receipt_are_bound(self) -> None:
        if self.source_mode:
            self.assertEqual(len(self.before_bytes), started.SOURCE_CHECKPOINT_BYTE_COUNT)
            self.assertEqual(
                hashlib.sha256(self.before_bytes).hexdigest(),
                started.SOURCE_CHECKPOINT_SHA256,
            )
            self.assertEqual(
                hashlib.sha256(self.evidence.receipt_bytes).hexdigest(),
                started.RECEIPT_SHA256,
            )
        self.assertEqual(stat.S_IMODE(self.before_stat.st_mode), 0o600)
        self.assertEqual(self.before_stat.st_nlink, 1)

    def test_seq47_is_the_exact_ready_to_in_progress_transition(self) -> None:
        event = self.event
        self.assertEqual(set(event), started.EXPECTED_EVENT_FIELDS)
        self.assertEqual(event["sequence"], 47)
        self.assertEqual(event["event_id"], started.EVENT_ID)
        self.assertEqual(event["event_type"], "GOAL_STARTED")
        self.assertEqual(event["subject_goal_id"], materialize.GOAL_ID)
        self.assertEqual(event["from_status"], "READY")
        self.assertEqual(event["to_status"], "IN_PROGRESS")
        self.assertEqual(event["previous_event_sha256"], started.SOURCE_READY_EVENT_SHA256)
        self.assertEqual(event["event_sha256"], contract.event_sha256(event))

    def test_source_checkpoint_receipt_and_event_have_one_digest(self) -> None:
        binding = self.event["implementation_start_gate_binding"]
        self.assertEqual(binding["path"], started.RECEIPT_RELATIVE.as_posix())
        self.assertEqual(binding["file_sha256"], started.RECEIPT_SHA256)
        receipt = json.loads((ROOT / started.RECEIPT_RELATIVE).read_bytes())
        self.assertEqual(
            receipt["source_checkpoint_sha256"],
            started.SOURCE_CHECKPOINT_SHA256,
        )
        self.assertEqual(
            self.event["source_checkpoint_sha256"],
            started.SOURCE_CHECKPOINT_SHA256,
        )
        self.assertEqual(
            receipt["source_ready_event_sha256"],
            started.SOURCE_READY_EVENT_SHA256,
        )

    def test_history_is_add_only_and_runtime_focus_is_fp008(self) -> None:
        state = self.projected["goal_execution"]
        history = state["transition_history"]
        self.assertEqual(len(history), 47)
        self.assertEqual(history[-1], self.event)
        if self.source_mode:
            self.assertEqual(history[:46], self.source["goal_execution"]["transition_history"])
        self.assertEqual(state["transition_history_anchor_sha256"], self.event["event_sha256"])
        self.assertEqual(state["status_by_goal"][materialize.GOAL_ID], "IN_PROGRESS")
        self.assertEqual(state["status_by_goal"][materialize.PARENT_GOAL_ID], "READY")
        self.assertEqual(state["focus_goal_id"], materialize.GOAL_ID)
        self.assertEqual(
            [value for value in state["status_by_goal"].values() if value == "IN_PROGRESS"],
            ["IN_PROGRESS"],
        )

    def test_working_snapshot_adds_only_the_seq47_control_pair(self) -> None:
        paths = self.projected["working_tree_snapshot"]["managed_changed_paths"]
        self.assertIn(started.SCRIPT_RELATIVE.as_posix(), paths)
        self.assertIn(started.TEST_RELATIVE.as_posix(), paths)
        if self.source_mode:
            before = set(self.source["working_tree_snapshot"]["managed_changed_paths"])
            self.assertEqual(
                set(paths) - before,
                {started.SCRIPT_RELATIVE.as_posix(), started.TEST_RELATIVE.as_posix()},
            )
        path_hash, content_hash = contract.working_snapshot_hashes(ROOT, paths)
        self.assertEqual(self.projected["working_tree_snapshot"]["path_set_sha256"], path_hash)
        self.assertEqual(self.projected["working_tree_snapshot"]["content_set_sha256"], content_hash)

    def test_no_external_device_formal_or_release_credit_is_added(self) -> None:
        current = self.projected["current_work"]
        scope = self.projected["working_tree_snapshot"]["scope"]
        self.assertFalse(current["release_completion_claimed"])
        self.assertIn("external", scope)
        self.assertIn("device", scope)
        self.assertIn("formal-test", scope)
        self.assertIn("release credit remain unclaimed", scope)
        if self.source_mode:
            self.assertEqual(
                self.projected["verification_boundary"],
                self.source["verification_boundary"],
            )
            self.assertEqual(
                self.projected["goal_execution"]["completion_evidence_by_goal"],
                self.source["goal_execution"]["completion_evidence_by_goal"],
            )

    def test_projected_checkpoint_passes_full_v24_replay(self) -> None:
        self.assertEqual(started.validate_projection(ROOT, self.projected), [])
        started.require_started_checkpoint(ROOT, self.projected)

    def test_full_semantic_pin_rejects_broad_non_event_mutations(self) -> None:
        mutations = (
            (("current_work", "current_focus"), "TAMPERED"),
            (("current_work", "next_action"), "TAMPERED"),
            (("session_handoff", "current_epic"), "TAMPERED"),
            (("session_handoff", "last_verification_status"), "TAMPERED"),
            (("repository", "branch"), "tampered"),
            (("approved_state", "release_status"), "ELIGIBLE"),
        )
        for key_path, value in mutations:
            with self.subTest(path=".".join(key_path)):
                candidate = copy.deepcopy(self.projected)
                target = candidate
                for key in key_path[:-1]:
                    target = target[key]
                target[key_path[-1]] = value
                with self.assertRaisesRegex(
                    started.StartApplyError,
                    "semantic SHA-256 differs",
                ):
                    started.require_started_checkpoint(ROOT, candidate)

    def test_normalized_content_hash_copies_must_remain_equal(self) -> None:
        candidate = copy.deepcopy(self.projected)
        candidate["session_handoff"]["source_commit_or_snapshot"][
            "content_set_sha256"
        ] = "0" * 64
        self.assertEqual(
            started.started_checkpoint_semantic_sha256(candidate),
            started.EXPECTED_STARTED_SEMANTIC_SHA256,
        )
        with self.assertRaisesRegex(
            started.StartApplyError,
            "content hash copies differ",
        ):
            started.require_started_checkpoint(ROOT, candidate)

    def test_preflight_never_changes_the_live_checkpoint(self) -> None:
        if not self.source_mode:
            self.skipTest("seq47 is already add-only published")
        prepared = started.prepare(ROOT)
        try:
            self.assertEqual(prepared.projected, self.projected)
            prepared.guard.verify()
        finally:
            prepared.guard.close()
        self.assertEqual(self.path.read_bytes(), self.before_bytes)
        after = self.path.stat()
        self.assertEqual((after.st_dev, after.st_ino), (self.before_stat.st_dev, self.before_stat.st_ino))

    def test_receipt_byte_tampering_is_rejected_before_projection(self) -> None:
        if not self.source_mode:
            self.skipTest("seq47 is already add-only published")
        real_reader = started._read_private_file

        def tampered(root: Path, relative: Path) -> bytes:
            value = real_reader(root, relative)
            if relative == started.RECEIPT_RELATIVE:
                return value + b" "
            return value

        with mock.patch.object(started, "_read_private_file", side_effect=tampered):
            with self.assertRaisesRegex(started.StartApplyError, "receipt SHA-256 differs"):
                started.load_gate_evidence(ROOT, self.source)

    def test_event_source_digest_tampering_is_rejected_by_checker(self) -> None:
        candidate = copy.deepcopy(self.projected)
        event = candidate["goal_execution"]["transition_history"][-1]
        event["source_checkpoint_sha256"] = "0" * 64
        event["event_sha256"] = contract.event_sha256(event)
        candidate["goal_execution"]["transition_history_anchor_sha256"] = event["event_sha256"]
        errors = started.validate_projection(ROOT, candidate)
        self.assertTrue(
            any("receipt/event source checkpoint SHA-256" in error for error in errors),
            errors,
        )

    def test_failed_gate_attempt_is_not_used_as_completion_evidence(self) -> None:
        failed = ROOT / started.gate.GATE_ROOT_RELATIVE / started.FAILED_EVENT_ID
        self.assertTrue(failed.is_dir())
        self.assertEqual(len(list(failed.iterdir())), 9)
        self.assertFalse((failed / started.RECEIPT_NAME).exists())
        self.assertNotIn(started.FAILED_EVENT_ID, json.dumps(self.event, sort_keys=True))

    def test_atomic_error_is_classified_by_observed_final_state(self) -> None:
        if not self.source_mode:
            self.skipTest("seq47 is already add-only published")
        prepared = started.prepare(ROOT)

        def fail_before(*args, **kwargs) -> None:
            raise RuntimeError("precommit")

        with mock.patch.object(started, "atomic_write_seq47", side_effect=fail_before):
            with self.assertRaisesRegex(RuntimeError, "precommit"):
                started.publish(prepared)
        self.assertEqual(self.path.read_bytes(), self.before_bytes)

        prepared = started.prepare(ROOT)
        with mock.patch.object(
            started,
            "atomic_write_seq47",
            side_effect=RuntimeError("after"),
        ), mock.patch.object(
            started,
            "_publication_state",
            return_value=started.PUBLICATION_PROJECTED_EXACT,
        ):
            with self.assertRaisesRegex(
                started.StartPostCommitUncertain,
                "PROJECTED_EXACT",
            ):
                started.publish(prepared)
        self.assertEqual(self.path.read_bytes(), self.before_bytes)

    def test_publisher_classifies_detached_foreign_final_as_postcommit_uncertain(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            parent = root / started.CHECKPOINT.parent
            parent.mkdir(parents=True)
            path = root / started.CHECKPOINT
            path.write_bytes(b"source")
            path.chmod(0o600)
            _, authority = started._capture_checkpoint_authority(root, b"source")
            guard = mock.Mock()
            prepared = started.PreparedProjection(
                root=root,
                source_bytes=b"source",
                source={},
                projected={},
                projected_bytes=b"projected",
                event={},
                evidence=mock.Mock(),
                source_authority=authority,
                guard=guard,
            )
            self.assertEqual(
                started._publication_state(prepared),
                started.PUBLICATION_SOURCE_EXACT,
            )

            detached = parent.with_name("control-detached")

            def detach_then_fail(*_args, **_kwargs) -> None:
                parent.rename(detached)
                parent.mkdir()
                replacement = parent / path.name
                replacement.write_bytes(b"foreign")
                replacement.chmod(0o600)
                raise RuntimeError("detached")

            with mock.patch.object(
                started,
                "atomic_write_seq47",
                side_effect=detach_then_fail,
            ):
                with self.assertRaisesRegex(
                    started.StartPostCommitUncertain,
                    "OTHER",
                ):
                    started.publish(prepared)
            guard.close.assert_called_once()
            self.assertEqual((parent / path.name).read_bytes(), b"foreign")
            self.assertEqual((detached / path.name).read_bytes(), b"source")

    def test_retained_guard_rejects_content_and_inventory_drift(self) -> None:
        if not self.source_mode:
            self.skipTest("seq47 is already add-only published")
        prepared = started.prepare(ROOT)
        try:
            with mock.patch.object(
                prepared.guard.managed,
                "content_set_sha256",
                return_value="0" * 64,
            ):
                with self.assertRaisesRegex(
                    started.StartApplyError,
                    "managed content authority differs",
                ):
                    prepared.guard.verify()
            real_listdir = os.listdir

            def extra_entry(value):
                entries = real_listdir(value)
                if value == prepared.guard.event_dir_fd:
                    return [*entries, "unexpected.tmp"]
                return entries

            with mock.patch.object(started.os, "listdir", side_effect=extra_entry):
                with self.assertRaisesRegex(
                    started.StartApplyError,
                    "directory inventory changed",
                ):
                    prepared.guard.verify()
        finally:
            prepared.guard.close()

    def test_internal_writer_success_failure_and_postcommit_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            parent = root / started.CHECKPOINT.parent
            parent.mkdir(parents=True)
            path = root / started.CHECKPOINT
            path.write_bytes(b"source")
            path.chmod(0o600)
            _, authority = started._capture_checkpoint_authority(root, b"source")
            calls: list[str] = []
            started.atomic_write_seq47(
                path,
                b"projected",
                expected_source=b"source",
                expected_source_authority=authority,
                commit_guard=lambda: calls.append("guard"),
            )
            self.assertEqual(path.read_bytes(), b"projected")
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertGreaterEqual(len(calls), 4)
            self.assertEqual(
                [entry.name for entry in parent.iterdir()],
                [started.CHECKPOINT.name],
            )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            parent = root / started.CHECKPOINT.parent
            parent.mkdir(parents=True)
            path = root / started.CHECKPOINT
            path.write_bytes(b"source")
            path.chmod(0o600)
            _, authority = started._capture_checkpoint_authority(root, b"source")
            with self.assertRaisesRegex(OSError, "exchange failed"):
                started.atomic_write_seq47(
                    path,
                    b"projected",
                    expected_source=b"source",
                    expected_source_authority=authority,
                    commit_guard=lambda: None,
                    exchanger=lambda *_args: (_ for _ in ()).throw(OSError("exchange failed")),
                )
            self.assertEqual(path.read_bytes(), b"source")
            self.assertEqual((parent / started.STAGE_NAME).read_bytes(), b"projected")

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            parent = root / started.CHECKPOINT.parent
            parent.mkdir(parents=True)
            path = root / started.CHECKPOINT
            path.write_bytes(b"source")
            path.chmod(0o600)
            _, authority = started._capture_checkpoint_authority(root, b"source")
            sync_count = 0

            def fail_second_sync(descriptor: int) -> None:
                nonlocal sync_count
                sync_count += 1
                if sync_count == 2:
                    raise OSError("fsync failed")
                os.fsync(descriptor)

            with self.assertRaisesRegex(
                started.StartPostCommitUncertain,
                "committed",
            ):
                started.atomic_write_seq47(
                    path,
                    b"projected",
                    expected_source=b"source",
                    expected_source_authority=authority,
                    commit_guard=lambda: None,
                    directory_syncer=fail_second_sync,
                )
            self.assertEqual(path.read_bytes(), b"projected")

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            parent = root / started.CHECKPOINT.parent
            parent.mkdir(parents=True)
            path = root / started.CHECKPOINT
            path.write_bytes(b"source")
            path.chmod(0o600)
            _, authority = started._capture_checkpoint_authority(root, b"source")

            def fail_after_exchange(label: str) -> None:
                if label == "after_exchange":
                    raise RuntimeError("after exchange")

            with self.assertRaisesRegex(
                started.StartPostCommitUncertain,
                "committed",
            ):
                started.atomic_write_seq47(
                    path,
                    b"projected",
                    expected_source=b"source",
                    expected_source_authority=authority,
                    commit_guard=lambda: None,
                    hook=fail_after_exchange,
                )
            self.assertEqual(path.read_bytes(), b"projected")
            retained = [entry for entry in parent.iterdir() if entry.name != path.name]
            self.assertEqual(len(retained), 1)
            self.assertEqual(retained[0].read_bytes(), b"source")

    def test_internal_writer_rejects_parent_detach_before_exchange(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            parent = root / started.CHECKPOINT.parent
            parent.mkdir(parents=True)
            path = root / started.CHECKPOINT
            path.write_bytes(b"source")
            path.chmod(0o600)
            _, authority = started._capture_checkpoint_authority(root, b"source")
            detached = parent.with_name("control-detached")

            def detach(label: str) -> None:
                if label != "before_exchange":
                    return
                parent.rename(detached)
                parent.mkdir()
                replacement = parent / path.name
                replacement.write_bytes(b"foreign")
                replacement.chmod(0o600)

            with self.assertRaisesRegex(
                started.StartApplyError,
                "directory chain changed",
            ):
                started.atomic_write_seq47(
                    path,
                    b"projected",
                    expected_source=b"source",
                    expected_source_authority=authority,
                    commit_guard=lambda: None,
                    hook=detach,
                )
            self.assertEqual((parent / path.name).read_bytes(), b"foreign")
            self.assertEqual((detached / path.name).read_bytes(), b"source")
            self.assertEqual(
                (detached / started.STAGE_NAME).read_bytes(),
                b"projected",
            )

    def test_internal_writer_rejects_same_byte_source_inode_replacement(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            parent = root / started.CHECKPOINT.parent
            parent.mkdir(parents=True)
            path = root / started.CHECKPOINT
            path.write_bytes(b"source")
            path.chmod(0o600)
            _, authority = started._capture_checkpoint_authority(root, b"source")
            replacement = parent / "replacement"
            replacement.write_bytes(b"source")
            replacement.chmod(0o600)
            replacement.replace(path)
            with self.assertRaisesRegex(
                started.StartApplyError,
                "inode changed",
            ):
                started.atomic_write_seq47(
                    path,
                    b"projected",
                    expected_source=b"source",
                    expected_source_authority=authority,
                    commit_guard=lambda: None,
                )
            self.assertEqual(path.read_bytes(), b"source")
            self.assertFalse((parent / started.STAGE_NAME).exists())

    def test_sigkill_restart_recovers_pre_and_post_exchange_states(self) -> None:
        kill_program = r"""
import os
from pathlib import Path
import signal
import sys
from scripts import apply_walksafe_fp008_goal_started_seq47_20260803 as started
root = Path(sys.argv[1])
kill_label = sys.argv[2]
path = root / started.CHECKPOINT
_, authority = started._capture_checkpoint_authority(root, b"source")
def kill_at_boundary(label: str) -> None:
    if label == kill_label:
        os.kill(os.getpid(), signal.SIGKILL)
started.atomic_write_seq47(
    path,
    b"projected",
    expected_source=b"source",
    expected_source_authority=authority,
    commit_guard=lambda: None,
    hook=kill_at_boundary,
)
"""
        resume_source_program = r"""
from pathlib import Path
import sys
from scripts import apply_walksafe_fp008_goal_started_seq47_20260803 as started
root = Path(sys.argv[1])
path = root / started.CHECKPOINT
_, authority = started._capture_checkpoint_authority(root, b"source")
started.atomic_write_seq47(
    path,
    b"projected",
    expected_source=b"source",
    expected_source_authority=authority,
    commit_guard=lambda: None,
)
"""
        resume_projected_program = r"""
import hashlib
from pathlib import Path
import sys
from scripts import apply_walksafe_fp008_goal_started_seq47_20260803 as started
root = Path(sys.argv[1])
_, authority = started._capture_checkpoint_authority(root, b"projected")
started._finalize_projected_publication(
    root,
    b"projected",
    write=True,
    expected_projected_authority=authority,
    expected_source_sha256=hashlib.sha256(b"source").hexdigest(),
    expected_source_byte_count=len(b"source"),
)
"""
        for kill_label in ("stage_linked", "after_exchange", "after_source_unlink"):
            with self.subTest(kill_label=kill_label), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                parent = root / started.CHECKPOINT.parent
                parent.mkdir(parents=True)
                path = root / started.CHECKPOINT
                path.write_bytes(b"source")
                path.chmod(0o600)
                source_inode = path.stat().st_ino
                killed = subprocess.run(
                    [sys.executable, "-B", "-c", kill_program, str(root), kill_label],
                    cwd=ROOT,
                    capture_output=True,
                    text=True,
                    check=False,
                )
                self.assertEqual(killed.returncode, -signal.SIGKILL, killed.stderr)
                if kill_label == "stage_linked":
                    self.assertTrue((parent / started.STAGE_NAME).is_file())
                    self.assertEqual(path.read_bytes(), b"source")
                    self.assertEqual(
                        (parent / started.STAGE_NAME).read_bytes(),
                        b"projected",
                    )
                    resumed = subprocess.run(
                        [sys.executable, "-B", "-c", resume_source_program, str(root)],
                        cwd=ROOT,
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    self.assertEqual(resumed.returncode, 0, resumed.stderr)
                else:
                    self.assertEqual(path.read_bytes(), b"projected")
                    projected_inode = path.stat().st_ino
                    if kill_label == "after_exchange":
                        self.assertEqual(
                            (parent / started.STAGE_NAME).read_bytes(),
                            b"source",
                        )
                        self.assertEqual(
                            (parent / started.STAGE_NAME).stat().st_ino,
                            source_inode,
                        )
                    else:
                        self.assertFalse((parent / started.STAGE_NAME).exists())
                    resumed = subprocess.run(
                        [sys.executable, "-B", "-c", resume_projected_program, str(root)],
                        cwd=ROOT,
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    self.assertEqual(resumed.returncode, 0, resumed.stderr)
                    resumed_again = subprocess.run(
                        [sys.executable, "-B", "-c", resume_projected_program, str(root)],
                        cwd=ROOT,
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    self.assertEqual(resumed_again.returncode, 0, resumed_again.stderr)
                    self.assertEqual(path.stat().st_ino, projected_inode)
                self.assertEqual(path.read_bytes(), b"projected")
                self.assertEqual([entry.name for entry in parent.iterdir()], [path.name])


if __name__ == "__main__":
    unittest.main()
