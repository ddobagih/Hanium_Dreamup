from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts import apply_walksafe_npc_single_admin_recovery_goal_seq56_57_20260810 as subject
from scripts import materialize_walksafe_npc_single_admin_recovery_goal_seq56_57_20260810 as materialize

_FIXTURE_PATH = Path(__file__).with_name(
    "test_walksafe_npc_single_admin_recovery_goal_seq56_57_20260810.py"
)
_FIXTURE_SPEC = importlib.util.spec_from_file_location(
    "walksafe_npc_single_admin_recovery_goal_fixture", _FIXTURE_PATH
)
if _FIXTURE_SPEC is None or _FIXTURE_SPEC.loader is None:
    raise ImportError(f"cannot load fixture module: {_FIXTURE_PATH}")
_FIXTURE_MODULE = importlib.util.module_from_spec(_FIXTURE_SPEC)
_FIXTURE_SPEC.loader.exec_module(_FIXTURE_MODULE)
make_source = _FIXTURE_MODULE.make_source
make_source_checkpoint = _FIXTURE_MODULE.make_source_checkpoint
project_fixture = _FIXTURE_MODULE.project_fixture


def _sha(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _content_set(relative: str, raw: bytes) -> str:
    digest = hashlib.sha256()
    digest.update(relative.encode("utf-8"))
    digest.update(b"\0")
    digest.update(_sha(raw).encode("ascii"))
    digest.update(b"\n")
    return digest.hexdigest()


def make_publication_fixture(root: Path) -> tuple[dict[str, object], dict[str, object]]:
    source = make_source()
    checkpoint = root / materialize.CHECKPOINT
    checkpoint.parent.mkdir(parents=True, exist_ok=True)
    checkpoint.write_bytes(
        (json.dumps(source, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    )
    checkpoint.chmod(0o600)
    _, projected, _, _, _ = project_fixture(root)
    relative = "fixture/managed.txt"
    managed_raw = b"sealed managed input\n"
    managed = root / relative
    managed.parent.mkdir(parents=True, exist_ok=True)
    managed.write_bytes(managed_raw)
    projected["working_tree_snapshot"]["managed_changed_paths"] = [relative]
    projected["working_tree_snapshot"]["managed_changed_path_count"] = 1
    projected["working_tree_snapshot"]["content_set_sha256"] = _content_set(
        relative, managed_raw
    )
    return source, projected


class NpcSingleAdminRecoveryApplyTests(unittest.TestCase):
    def test_prepare_is_read_only_and_retains_the_tmp_fixture(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, projected = make_publication_fixture(root)
            checkpoint = root / materialize.CHECKPOINT
            before = checkpoint.read_bytes()
            identity = checkpoint.stat().st_ino
            with (
                mock.patch.object(
                    materialize,
                    "project",
                    return_value=(projected, {}, {}),
                ),
                mock.patch.object(materialize, "require_ready_checkpoint"),
            ):
                prepared = subject.prepare(root)
            try:
                self.assertEqual(checkpoint.read_bytes(), before)
                self.assertEqual(checkpoint.stat().st_ino, identity)
                prepared.cohort.verify()
                self.assertEqual(prepared.projected, projected)
            finally:
                prepared.cohort.close()

    def test_prepare_rejects_seq55_raw_drift_between_two_reads(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, projected = make_publication_fixture(root)
            first = make_source_checkpoint(make_source())
            second_document = make_source()
            second_document["current_work"]["status"] = "DRIFT"
            second = make_source_checkpoint(second_document)
            with (
                mock.patch.object(
                    materialize,
                    "load_exact_source",
                    side_effect=[first, second],
                ),
                mock.patch.object(
                    materialize,
                    "project",
                    return_value=(projected, {}, {}),
                ),
                mock.patch.object(materialize, "require_ready_checkpoint"),
            ):
                with self.assertRaisesRegex(subject.PublicationError, "seq55 checkpoint changed"):
                    subject.prepare(root)

    def test_retained_cohort_rejects_a_same_byte_inode_swap(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            relative = "fixture/managed.txt"
            path = root / relative
            path.parent.mkdir(parents=True)
            path.write_bytes(b"same bytes\n")
            cohort = subject.PinnedCohort.capture(root, [relative])
            replacement = root / "fixture/replacement.txt"
            replacement.write_bytes(b"same bytes\n")
            os.replace(replacement, path)
            try:
                with self.assertRaisesRegex(
                    subject.PublicationError,
                    "metadata changed|identity changed",
                ):
                    cohort.verify()
            finally:
                cohort.close()

    def test_retained_cohort_rejects_a_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "target.txt"
            target.write_text("target\n")
            link = root / "link.txt"
            link.symlink_to(target)
            with self.assertRaisesRegex(subject.PublicationError, "symlink"):
                subject.PinnedCohort.capture(root, ["link.txt"])

    def test_publish_uses_one_cas_and_commits_only_seq57_in_tmp(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, projected = make_publication_fixture(root)
            calls: list[tuple[bytes, bytes]] = []
            validated_lengths: list[int] = []

            def writer(
                path: Path,
                payload: bytes,
                *,
                expected_source: bytes,
                commit_guard,
            ) -> None:
                self.assertEqual(path.read_bytes(), expected_source)
                commit_guard()
                calls.append((expected_source, payload))
                path.write_bytes(payload)
                path.chmod(0o600)

            def require_ready(_root: Path, checkpoint: dict[str, object]) -> None:
                length = len(checkpoint["goal_execution"]["transition_history"])
                validated_lengths.append(length)
                self.assertEqual(length, 57)
                self.assertFalse(
                    any(event.get("sequence") == 58 for event in checkpoint["goal_execution"]["transition_history"])
                )

            with (
                mock.patch.object(
                    materialize,
                    "project",
                    return_value=(projected, {}, {}),
                ),
                mock.patch.object(materialize, "require_ready_checkpoint"),
            ):
                prepared = subject.prepare(root)
            with mock.patch.object(materialize, "require_ready_checkpoint", side_effect=require_ready):
                subject.publish(prepared, atomic_writer=writer)
            self.assertEqual(len(calls), 1)
            self.assertEqual(validated_lengths, [57])
            self.assertEqual(
                json.loads((root / materialize.CHECKPOINT).read_text()),
                projected,
            )

    def test_publish_fails_before_cas_when_managed_input_drifts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, projected = make_publication_fixture(root)
            with (
                mock.patch.object(
                    materialize,
                    "project",
                    return_value=(projected, {}, {}),
                ),
                mock.patch.object(materialize, "require_ready_checkpoint"),
            ):
                prepared = subject.prepare(root)
            (root / "fixture/managed.txt").write_text("changed\n")
            writer = mock.Mock()
            with self.assertRaises(subject.PublicationError):
                subject.publish(prepared, atomic_writer=writer)
            writer.assert_not_called()

    def test_cli_requires_an_exact_explicit_mode(self) -> None:
        with self.assertRaises(SystemExit):
            subject.parse_args([])
        with self.assertRaises(SystemExit):
            subject.parse_args(["--pre", "--root", "/tmp"])
        with self.assertRaises(SystemExit):
            subject.parse_args(["--preflight", "--write"])


if __name__ == "__main__":
    unittest.main()
