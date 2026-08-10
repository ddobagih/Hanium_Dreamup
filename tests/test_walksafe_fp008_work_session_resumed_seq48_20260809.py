from __future__ import annotations

from collections.abc import Callable
import copy
from dataclasses import replace
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import signal
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from contextlib import nullcontext
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp008_work_session_resumed_seq48_20260809 as publisher


class WalkSafeFp008WorkSessionResumedSeq48Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        live_bytes = (ROOT / publisher.CHECKPOINT).read_bytes()
        live = json.loads(live_bytes)
        history = live.get("goal_execution", {}).get("transition_history", [])
        if history and history[-1].get("event_type") == "WORK_SESSION_RESUMED":
            cls.source, cls.source_bytes = publisher._source_from_resumed_checkpoint(
                live
            )
        else:
            cls.source_bytes = live_bytes
            cls.source = live

    def _evidence(
        self,
        event_id: str = publisher.EVENT_ID,
    ) -> publisher.GateEvidence:
        document_id = publisher.gate._document_id(event_id)
        event_relative = (
            publisher.gate.start_gate.GATE_ROOT_RELATIVE / event_id
        )
        receipt = {
            "document_id": document_id,
            "repository_snapshot": {
                "checkpoint_managed_path_count": self.source["working_tree_snapshot"]
                ["managed_changed_path_count"],
                "checkpoint_path_set_sha256": self.source["working_tree_snapshot"]
                ["path_set_sha256"],
                "checkpoint_content_set_sha256": self.source["working_tree_snapshot"]
                ["content_set_sha256"],
            },
        }
        return publisher.GateEvidence(
            event_id=event_id,
            receipt=receipt,
            receipt_bytes=b"synthetic receipt\n",
            receipt_binding={
                "document_id": document_id,
                "path": (event_relative / publisher.RECEIPT_NAME).as_posix(),
                "file_sha256": "a" * 64,
            },
            event_occurred_at="2026-08-09T00:30:00+09:00",
            evidence_paths=(),
            event_dir_identity=(),
            evidence_identity_by_path={},
            evidence_sha256_by_path={},
            repository_payload={"synthetic": "projection"},
        )

    def test_projection_carries_a_fresh_same_day_resume_id_end_to_end(self) -> None:
        event_id = publisher.EVENT_ID[:-3] + "002"
        projected, event = publisher.project(
            self.source,
            self.source_bytes,
            self._evidence(event_id),
        )
        self.assertEqual(event["event_id"], event_id)
        self.assertEqual(event["occurred_on"], "2026-08-09")
        self.assertEqual(
            event["implementation_start_gate_binding"]["document_id"],
            publisher.gate._document_id(event_id),
        )
        self.assertEqual(
            projected["goal_execution"]["transition_history"][-1],
            event,
        )

    def _guard_fixture(
        self,
        root: Path,
    ) -> tuple[publisher.GateEvidence, list[str], str, Path]:
        event_dir = (
            root
            / publisher.gate.start_gate.GATE_ROOT_RELATIVE
            / publisher.EVENT_ID
        )
        event_dir.mkdir(parents=True)
        os.chmod(event_dir, 0o700)
        evidence_paths: list[str] = []
        identity_by_path: dict[str, tuple[int, ...]] = {}
        digest_by_path: dict[str, str] = {}
        for name, value in (("01-check.log", b"PASS\n"), (publisher.RECEIPT_NAME, b"{}\n")):
            path = event_dir / name
            path.write_bytes(value)
            os.chmod(path, 0o600)
            relative = path.relative_to(root).as_posix()
            evidence_paths.append(relative)
            identity_by_path[relative] = publisher.seq47._file_identity(path.lstat())
            digest_by_path[relative] = hashlib.sha256(value).hexdigest()
        managed = root / "managed.txt"
        managed.write_bytes(b"managed\n")
        managed_relative = managed.relative_to(root).as_posix()
        managed_digest = publisher._content_set_sha256(
            [managed_relative],
            {managed_relative: hashlib.sha256(managed.read_bytes()).hexdigest()},
        )
        evidence = publisher.GateEvidence(
            event_id=publisher.EVENT_ID,
            receipt={},
            receipt_bytes=b"{}\n",
            receipt_binding={},
            event_occurred_at="2026-08-09T00:30:00+09:00",
            evidence_paths=tuple(evidence_paths),
            event_dir_identity=publisher._directory_identity(event_dir.lstat()),
            evidence_identity_by_path=identity_by_path,
            evidence_sha256_by_path=digest_by_path,
            repository_payload={"synthetic": "gate"},
        )
        return evidence, [managed_relative], managed_digest, event_dir

    def _capture_synthetic_guard(
        self,
        root: Path,
        managed_paths: list[str],
        evidence: publisher.GateEvidence,
        managed_digest: str,
        source_bytes: bytes = b"source",
        projected_bytes: bytes = b"projected",
    ) -> publisher.ResumePublicationGuard:
        authority = mock.Mock()
        authority.command_environment.side_effect = lambda: nullcontext()
        authority.capture_state.side_effect = (
            lambda checkpoint, event_id, *, capture: capture(
                root,
                checkpoint,
                event_id,
            )
        )
        with mock.patch.object(
            publisher.gate.start_gate.RetainedRepositoryAuthorityGuard,
            "capture",
            return_value=authority,
        ):
            return publisher.ResumePublicationGuard.capture(
                root,
                managed_paths,
                evidence,
                managed_digest,
                source_bytes,
                projected_bytes,
            )

    def _run_git(
        self,
        root: Path,
        *arguments: str,
        input_bytes: bytes | None = None,
    ) -> bytes:
        completed = subprocess.run(
            ["git", *arguments],
            cwd=root,
            input=input_bytes,
            capture_output=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr.decode())
        return completed.stdout

    @staticmethod
    def _raw_output_failure_fixture(
        failure: str,
    ) -> tuple[mock.Mock, mock.Mock, list[tuple[int, bytes]], object]:
        stdout_fd = 101
        stderr_fd = 102
        stdout = mock.Mock()
        stdout.fileno.return_value = -1 if failure == "invalid-fd" else stdout_fd
        stderr = mock.Mock()
        stderr.fileno.return_value = stderr_fd
        calls: list[tuple[int, bytes]] = []
        stdout_calls = 0

        def raw_write(descriptor: int, content: bytes) -> int:
            nonlocal stdout_calls
            calls.append((descriptor, content))
            if descriptor == stderr_fd:
                if failure == "diagnostic-error":
                    raise OSError("injected stderr diagnostic failure")
                return len(content)
            if descriptor != stdout_fd:
                raise AssertionError("unexpected raw output descriptor")
            stdout_calls += 1
            if failure in {"immediate-error", "diagnostic-error"}:
                raise OSError("injected stdout write failure")
            if failure == "zero":
                return 0
            if failure == "partial-error":
                if stdout_calls == 1:
                    return max(1, len(content) // 2)
                raise OSError("injected stdout continuation failure")
            if failure == "base-exception":
                raise KeyboardInterrupt("injected raw output interruption")
            raise AssertionError("unexpected raw output failure fixture")

        return stdout, stderr, calls, raw_write

    def _real_git_guard_fixture(
        self,
        root: Path,
        *,
        reused_stage: bool,
        authority_setup: Callable[[Path], None] | None = None,
    ) -> tuple[
        publisher.ResumePublicationGuard,
        Path,
        bytes,
        bytes,
        publisher.seq47.SourcePublicationAuthority,
        publisher.GateEvidence,
    ]:
        self._run_git(root, "init", "-q")
        self._run_git(root, "config", "user.name", "WalkSafe Test")
        self._run_git(root, "config", "user.email", "walksafe@example.invalid")
        evidence, managed_paths, managed_digest, _event_dir = self._guard_fixture(
            root
        )
        self._run_git(root, "add", "managed.txt")
        self._run_git(root, "commit", "-q", "-m", "fixture")
        head = self._run_git(root, "rev-parse", "HEAD").strip().decode("ascii")
        path_hash, content_hash = (
            publisher.continuation._v23_utility.working_snapshot_hashes(
                root,
                managed_paths,
            )
        )
        source = {
            "session_handoff": {
                "source_commit_or_snapshot": {"current_head": head}
            },
            "working_tree_snapshot": {
                "base_head": head,
                "content_set_sha256": content_hash,
                "managed_changed_path_count": len(managed_paths),
                "managed_changed_paths": managed_paths,
                "path_set_sha256": path_hash,
            },
        }
        source_bytes = publisher.seq47.json_bytes(source)
        projected = copy.deepcopy(source)
        projected["seq48_test_projection"] = True
        projected_bytes = publisher.seq47.json_bytes(projected)
        checkpoint = root / publisher.CHECKPOINT
        checkpoint.write_bytes(source_bytes)
        os.chmod(checkpoint, 0o600)
        if authority_setup is not None:
            authority_setup(root)
        authority = (
            publisher.gate.start_gate.RetainedRepositoryAuthorityGuard.capture(root)
        )
        try:
            with authority.command_environment():
                repository_payload = (
                    publisher.gate.start_gate.capture_repository_state(
                        root,
                        checkpoint,
                        publisher.EVENT_ID,
                    )
                )
            evidence = replace(evidence, repository_payload=repository_payload)
            if reused_stage:
                stage = checkpoint.parent / publisher.seq47.STAGE_NAME
                stage.write_bytes(projected_bytes)
                os.chmod(stage, 0o600)
            with mock.patch.object(
                publisher.gate.start_gate.RetainedRepositoryAuthorityGuard,
                "capture",
                return_value=authority,
            ):
                guard = publisher.ResumePublicationGuard.capture(
                    root,
                    managed_paths,
                    evidence,
                    managed_digest,
                    source_bytes,
                    projected_bytes,
                )
        except BaseException as exc:
            authority.close(exc)
            raise
        _captured, source_authority = (
            publisher.seq47._capture_checkpoint_authority(root, source_bytes)
        )
        return (
            guard,
            checkpoint,
            source_bytes,
            projected_bytes,
            source_authority,
            evidence,
        )

    def _assert_real_git_stage_publication(self, *, reused_stage: bool) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (
                guard,
                checkpoint,
                source_bytes,
                projected_bytes,
                source_authority,
                _evidence,
            ) = self._real_git_guard_fixture(root, reused_stage=reused_stage)
            exchanger = mock.Mock(wraps=publisher.seq47._rename_exchange)
            try:
                publisher.seq47.atomic_write_seq47(
                    checkpoint,
                    projected_bytes,
                    expected_source=source_bytes,
                    expected_source_authority=source_authority,
                    commit_guard=guard.verify,
                    precommit_guard=guard.verify_precommit,
                    exchanger=exchanger,
                )
                guard.verify_postcommit()
            finally:
                guard.close()
            exchanger.assert_called_once()
            self.assertEqual(checkpoint.read_bytes(), projected_bytes)
            self.assertFalse(
                (checkpoint.parent / publisher.seq47.STAGE_NAME).exists()
            )

    def test_load_source_rejects_noncanonical_json_before_contract_validation(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / publisher.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(b"{}\n\n")
            os.chmod(checkpoint, 0o600)
            with (
                mock.patch.object(publisher.gate, "_load_resume_contract") as contract,
                self.assertRaisesRegex(
                    publisher.ResumeApplyError,
                    "source checkpoint canonical JSON bytes differ",
                ),
            ):
                publisher.load_source(root)
            contract.assert_not_called()

    def test_load_source_rejects_reordered_top_level_keys(self) -> None:
        reordered = {"z": 1, "a": {"b": 2, "c": 3}}
        source_bytes = (
            json.dumps(reordered, ensure_ascii=False, indent=2) + "\n"
        ).encode("utf-8")
        with (
            mock.patch.object(
                publisher.seq47,
                "_capture_checkpoint_authority",
                return_value=(source_bytes, mock.sentinel.authority),
            ),
            mock.patch.object(publisher.gate, "_load_resume_contract") as contract,
            self.assertRaisesRegex(
                publisher.ResumeApplyError,
                "source checkpoint canonical JSON bytes differ",
            ),
        ):
            publisher.load_source(ROOT)
        contract.assert_not_called()

    def test_load_source_rejects_reordered_nested_keys(self) -> None:
        reordered = {"a": {"z": 1, "b": 2}, "c": 3}
        source_bytes = (
            json.dumps(reordered, ensure_ascii=False, indent=2) + "\n"
        ).encode("utf-8")
        with (
            mock.patch.object(
                publisher.seq47,
                "_capture_checkpoint_authority",
                return_value=(source_bytes, mock.sentinel.authority),
            ),
            mock.patch.object(publisher.gate, "_load_resume_contract") as contract,
            self.assertRaisesRegex(
                publisher.ResumeApplyError,
                "source checkpoint canonical JSON bytes differ",
            ),
        ):
            publisher.load_source(ROOT)
        contract.assert_not_called()

    def test_seq48_is_status_neutral_and_preserves_seq1_through_47(self) -> None:
        projected, event = publisher.project(
            self.source,
            self.source_bytes,
            self._evidence(),
        )
        self.assertEqual(
            projected["goal_execution"]["transition_history"][:47],
            self.source["goal_execution"]["transition_history"],
        )
        self.assertEqual(event["sequence"], 48)
        self.assertEqual(event["event_type"], "WORK_SESSION_RESUMED")
        self.assertEqual(event["from_status"], "IN_PROGRESS")
        self.assertEqual(event["to_status"], "IN_PROGRESS")
        self.assertEqual(event["status_changes"], {})
        self.assertEqual(event["previous_event_sha256"], publisher.SOURCE_EVENT_SHA256)
        self.assertEqual(
            event["previous_execution_session_event_sha256"],
            publisher.SOURCE_EVENT_SHA256,
        )
        self.assertEqual(
            publisher.continuation.event_sha256(event),
            event["event_sha256"],
        )
        self.assertEqual(
            projected["goal_execution"]["status_by_goal"],
            self.source["goal_execution"]["status_by_goal"],
        )
        self.assertEqual(
            projected["goal_execution"]["status_by_goal"][publisher.TARGET_GOAL_ID],
            "IN_PROGRESS",
        )
        self.assertEqual(
            projected["goal_execution"]["status_by_goal"]["WS-GOAL-EPIC-03"],
            "READY",
        )

    def test_seq48_grants_no_completion_or_release_credit(self) -> None:
        projected, _ = publisher.project(
            self.source,
            self.source_bytes,
            self._evidence(),
        )
        self.assertEqual(projected["canonical_bindings"], self.source["canonical_bindings"])
        self.assertEqual(
            projected["goal_execution"]["completion_evidence_by_goal"],
            self.source["goal_execution"]["completion_evidence_by_goal"],
        )
        self.assertEqual(projected["approved_state"], self.source["approved_state"])
        self.assertEqual(
            projected["verification_boundary"], self.source["verification_boundary"]
        )
        self.assertFalse(projected["verification_boundary"]["release_eligible"])
        self.assertEqual(
            projected["verification_boundary"]["formal_test_not_run_count"], 279
        )

    def test_checker_selects_fp008_private_9_check_contract_for_resume(self) -> None:
        projected, event = publisher.project(
            self.source,
            self.source_bytes,
            self._evidence(),
        )
        errors, checks, binding, ready = publisher.continuation._fp008_start_gate_contract(
            ROOT,
            event=event,
            checkpoint=projected,
        )
        self.assertEqual(errors, [])
        self.assertEqual(
            [item["check_id"] for item in checks],
            publisher.continuation.FP008_START_GATE_CHECK_IDS,
        )
        self.assertEqual(binding, ready["implementation_start_gate_contract_binding"])

    def test_private_inventory_accepts_resume_receipt_name_only(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            event_dir = root / publisher.continuation.FP008_GATE_ROOT_RELATIVE / publisher.EVENT_ID
            event_dir.mkdir(parents=True)
            os.chmod(event_dir, 0o700)
            checks = [
                {"check_id": check_id, "command": "true"}
                for check_id in publisher.continuation.FP008_START_GATE_CHECK_IDS
            ]
            for index, check_id in enumerate(
                publisher.continuation.FP008_START_GATE_CHECK_IDS,
                start=1,
            ):
                path = event_dir / f"{index:02d}-{check_id}.log"
                path.write_text("PASS\n", encoding="utf-8")
                os.chmod(path, 0o600)
            receipt = event_dir / publisher.RECEIPT_NAME
            receipt.write_text("{}\n", encoding="utf-8")
            os.chmod(receipt, 0o600)
            identity = event_dir.lstat()
            errors = publisher.continuation._validate_fp008_gate_event_inventory(
                root,
                event_id=publisher.EVENT_ID,
                expected_checks=checks,
                expected_directory_identity=(identity.st_dev, identity.st_ino),
                label="synthetic resume",
                receipt_name=publisher.RECEIPT_NAME,
            )
            self.assertEqual(errors, [])
            receipt.rename(event_dir / "implementation-start-gate-receipt.json")
            errors = publisher.continuation._validate_fp008_gate_event_inventory(
                root,
                event_id=publisher.EVENT_ID,
                expected_checks=checks,
                expected_directory_identity=(identity.st_dev, identity.st_ino),
                label="synthetic resume",
                receipt_name=publisher.RECEIPT_NAME,
            )
            self.assertTrue(any("only the 9" in error for error in errors), errors)

    def test_retained_guard_rejects_extra_entry_and_event_ancestor_swap(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence, managed_paths, managed_digest, event_dir = self._guard_fixture(root)
            guard = self._capture_synthetic_guard(
                root,
                managed_paths,
                evidence,
                managed_digest,
            )
            try:
                extra = event_dir / "unexpected.tmp"
                extra.write_bytes(b"unexpected\n")
                with self.assertRaisesRegex(
                    publisher.ResumeApplyError,
                    "directory inventory changed",
                ):
                    guard.verify()
                extra.unlink()

                ancestor = event_dir.parent
                detached = ancestor.with_name(ancestor.name + "-detached")
                ancestor.rename(detached)
                ancestor.mkdir()
                replacement = ancestor / event_dir.name
                replacement.mkdir()
                os.chmod(replacement, 0o700)
                for old in (detached / event_dir.name).iterdir():
                    new = replacement / old.name
                    new.write_bytes(old.read_bytes())
                    os.chmod(new, stat.S_IMODE(old.stat().st_mode))
                with self.assertRaisesRegex(
                    publisher.seq47.StartApplyError,
                    "directory chain changed",
                ):
                    guard.verify()
            finally:
                guard.close()

    def test_precommit_rejects_repository_state_changed_after_gate(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            evidence, managed_paths, managed_digest, _event_dir = self._guard_fixture(
                root
            )
            guard = self._capture_synthetic_guard(
                root,
                managed_paths,
                evidence,
                managed_digest,
            )
            try:
                with (
                    mock.patch.object(
                        publisher.gate.start_gate,
                        "capture_repository_state",
                        return_value={"synthetic": "changed"},
                    ) as recapture,
                    self.assertRaisesRegex(
                        publisher.ResumeApplyError,
                        "repository changed after resume gate before seq48 commit",
                    ),
                ):
                    guard.verify_precommit()
                recapture.assert_called_once_with(
                    root,
                    root / publisher.CHECKPOINT,
                    publisher.EVENT_ID,
                )
            finally:
                guard.close()

    def test_real_git_precommit_allows_owned_fresh_exact_stage(self) -> None:
        self._assert_real_git_stage_publication(reused_stage=False)

    def test_real_git_precommit_allows_owned_reused_exact_stage(self) -> None:
        self._assert_real_git_stage_publication(reused_stage=True)

    def test_real_git_recovery_normalizes_source_stage_before_cleanup(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (
                guard,
                checkpoint,
                source_bytes,
                projected_bytes,
                _source_authority,
                _evidence,
            ) = self._real_git_guard_fixture(root, reused_stage=False)
            stage = checkpoint.parent / publisher.seq47.STAGE_NAME
            stage.write_bytes(projected_bytes)
            os.chmod(stage, 0o600)
            parent_fd = os.open(
                checkpoint.parent,
                os.O_RDONLY
                | getattr(os, "O_DIRECTORY", 0)
                | getattr(os, "O_CLOEXEC", 0),
            )
            try:
                publisher.seq47._rename_exchange(
                    parent_fd,
                    publisher.seq47.STAGE_NAME,
                    checkpoint.name,
                )
            finally:
                os.close(parent_fd)
            _captured, projected_authority = (
                publisher.seq47._capture_checkpoint_authority(
                    root,
                    projected_bytes,
                )
            )
            try:
                guard.verify_recovery_boundary()
                publisher._finalize_resumed_publication(
                    root,
                    projected_bytes,
                    source_bytes,
                    projected_authority,
                    write=True,
                    terminal_guard=guard.verify_recovery_boundary,
                )
                guard.verify_postcommit()
            finally:
                guard.close()
            self.assertEqual(checkpoint.read_bytes(), projected_bytes)
            self.assertFalse(stage.exists())

    def test_real_git_recovery_drift_preserves_source_stage(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (
                guard,
                checkpoint,
                source_bytes,
                projected_bytes,
                _source_authority,
                _evidence,
            ) = self._real_git_guard_fixture(root, reused_stage=False)
            stage = checkpoint.parent / publisher.seq47.STAGE_NAME
            stage.write_bytes(projected_bytes)
            os.chmod(stage, 0o600)
            parent_fd = os.open(
                checkpoint.parent,
                os.O_RDONLY
                | getattr(os, "O_DIRECTORY", 0)
                | getattr(os, "O_CLOEXEC", 0),
            )
            try:
                publisher.seq47._rename_exchange(
                    parent_fd,
                    publisher.seq47.STAGE_NAME,
                    checkpoint.name,
                )
            finally:
                os.close(parent_fd)
            _captured, projected_authority = (
                publisher.seq47._capture_checkpoint_authority(
                    root,
                    projected_bytes,
                )
            )
            (root / "unrelated.txt").write_bytes(b"drift\n")
            primary: BaseException = publisher.ResumeApplyError(
                "expected recovery drift failure"
            )
            try:
                with self.assertRaises(publisher.ResumePostCommitUncertain) as raised:
                    publisher._finalize_resumed_publication(
                        root,
                        projected_bytes,
                        source_bytes,
                        projected_authority,
                        write=True,
                        terminal_guard=guard.verify_recovery_boundary,
                    )
                primary = raised.exception
            except BaseException as exc:
                primary = exc
                raise
            finally:
                guard.close(primary)
            self.assertEqual(checkpoint.read_bytes(), projected_bytes)
            self.assertEqual(stage.read_bytes(), source_bytes)

    def test_real_git_precommit_rejects_unrelated_change_before_exchange(
        self,
    ) -> None:
        for mutation in ("untracked", "index", "head"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                (
                    guard,
                    checkpoint,
                    source_bytes,
                    projected_bytes,
                    source_authority,
                    _evidence,
                ) = self._real_git_guard_fixture(root, reused_stage=False)
                exchanger = mock.Mock(
                    side_effect=AssertionError("exchange must not run")
                )

                def mutate(label: str) -> None:
                    if label != "before_exchange":
                        return
                    if mutation == "untracked":
                        (root / "unrelated.txt").write_bytes(b"drift\n")
                    elif mutation == "index":
                        object_id = self._run_git(
                            root,
                            "hash-object",
                            "-w",
                            "--stdin",
                            input_bytes=b"index drift\n",
                        ).strip().decode("ascii")
                        self._run_git(
                            root,
                            "update-index",
                            "--cacheinfo",
                            "100644",
                            object_id,
                            "managed.txt",
                        )
                    else:
                        self._run_git(
                            root,
                            "commit",
                            "-q",
                            "--allow-empty",
                            "-m",
                            "head drift",
                        )

                cleanup_primary: BaseException | None = None
                try:
                    with self.assertRaises(
                        publisher.gate.start_gate.GateError
                    ) as raised:
                        publisher.seq47.atomic_write_seq47(
                            checkpoint,
                            projected_bytes,
                            expected_source=source_bytes,
                            expected_source_authority=source_authority,
                            commit_guard=guard.verify,
                            precommit_guard=guard.verify_precommit,
                            exchanger=exchanger,
                            hook=mutate,
                        )
                    cleanup_primary = raised.exception
                except BaseException as exc:
                    cleanup_primary = exc
                    raise
                finally:
                    guard.close(cleanup_primary)
                exchanger.assert_not_called()
                self.assertEqual(checkpoint.read_bytes(), source_bytes)

    def test_real_git_precommit_rejects_transient_git_namespace_aba(self) -> None:
        for mutation in ("index", "head", "ref", "exclude", "config", "git_dir"):
            with (
                self.subTest(mutation=mutation),
                tempfile.TemporaryDirectory() as temporary,
                tempfile.TemporaryDirectory() as authority_temporary,
            ):
                root = Path(temporary).resolve()
                authority_root = Path(authority_temporary).resolve()
                spoof_git = authority_root / "spoof.git"

                def prepare_authority(repository_root: Path) -> None:
                    if mutation == "git_dir":
                        shutil.copytree(repository_root / ".git", spoof_git)

                (
                    guard,
                    checkpoint,
                    source_bytes,
                    projected_bytes,
                    source_authority,
                    _evidence,
                ) = self._real_git_guard_fixture(
                    root,
                    reused_stage=False,
                    authority_setup=prepare_authority,
                )
                original_capture = (
                    publisher.gate.start_gate.capture_repository_state
                )
                exchanger = mock.Mock(
                    side_effect=AssertionError("exchange must not run")
                )
                injected = False

                def transient_capture(
                    capture_root: Path,
                    capture_checkpoint: Path,
                    event_id: str,
                ) -> dict[str, object]:
                    nonlocal injected
                    stage = checkpoint.parent / publisher.seq47.STAGE_NAME
                    if injected or not stage.exists():
                        return original_capture(
                            capture_root,
                            capture_checkpoint,
                            event_id,
                        )
                    injected = True
                    git_dir = root / ".git"
                    if mutation == "git_dir":
                        held = authority_root / "held.git"
                        git_dir.rename(held)
                        spoof_git.rename(git_dir)
                        try:
                            return original_capture(
                                capture_root,
                                capture_checkpoint,
                                event_id,
                            )
                        finally:
                            git_dir.rename(spoof_git)
                            held.rename(git_dir)
                    if mutation == "index":
                        target = git_dir / "index"
                    elif mutation == "head":
                        target = git_dir / "HEAD"
                    elif mutation == "ref":
                        ref_name = self._run_git(
                            root,
                            "symbolic-ref",
                            "HEAD",
                        ).strip().decode("ascii")
                        target = git_dir / ref_name
                    elif mutation == "exclude":
                        target = git_dir / "info/exclude"
                    else:
                        target = git_dir / "config"
                    content = target.read_bytes()
                    mode = stat.S_IMODE(target.stat().st_mode)
                    held = authority_root / f"held-{mutation}"
                    target.rename(held)
                    target.write_bytes(content)
                    os.chmod(target, mode)
                    try:
                        return original_capture(
                            capture_root,
                            capture_checkpoint,
                            event_id,
                        )
                    finally:
                        target.unlink()
                        held.rename(target)

                cleanup_primary: BaseException = publisher.ResumeApplyError(
                    "expected transient repository authority failure"
                )
                try:
                    with (
                        mock.patch.object(
                            publisher.gate.start_gate,
                            "capture_repository_state",
                            side_effect=transient_capture,
                        ),
                        self.assertRaises(publisher.ResumeApplyError),
                    ):
                        publisher.seq47.atomic_write_seq47(
                            checkpoint,
                            projected_bytes,
                            expected_source=source_bytes,
                            expected_source_authority=source_authority,
                            commit_guard=guard.verify,
                            precommit_guard=guard.verify_precommit,
                            exchanger=exchanger,
                        )
                except BaseException as exc:
                    cleanup_primary = exc
                    raise
                finally:
                    guard.close(cleanup_primary)
                self.assertTrue(injected)
                exchanger.assert_not_called()
                self.assertEqual(checkpoint.read_bytes(), source_bytes)

    def test_real_git_drift_after_final_guard_is_postcommit_uncertain(self) -> None:
        for mutation in ("untracked", "index", "head", "other_event"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                (
                    guard,
                    checkpoint,
                    source_bytes,
                    projected_bytes,
                    source_authority,
                    evidence,
                ) = self._real_git_guard_fixture(root, reused_stage=False)

                def mutate_after_final_guard() -> None:
                    if mutation == "untracked":
                        (root / "unrelated.txt").write_bytes(b"drift\n")
                    elif mutation == "index":
                        object_id = self._run_git(
                            root,
                            "hash-object",
                            "-w",
                            "--stdin",
                            input_bytes=b"index drift\n",
                        ).strip().decode("ascii")
                        self._run_git(
                            root,
                            "update-index",
                            "--cacheinfo",
                            "100644",
                            object_id,
                            "managed.txt",
                        )
                    elif mutation == "head":
                        self._run_git(
                            root,
                            "commit",
                            "-q",
                            "--allow-empty",
                            "-m",
                            "head drift",
                        )
                    else:
                        other = (
                            root
                            / publisher.gate.start_gate.GATE_ROOT_RELATIVE
                            / "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260809-999"
                        )
                        other.mkdir()
                        (other / "unrelated.log").write_bytes(b"drift\n")

                prepared = publisher.PreparedProjection(
                    root=root,
                    source_bytes=source_bytes,
                    source={},
                    projected={},
                    projected_bytes=projected_bytes,
                    event={},
                    evidence=evidence,
                    source_authority=source_authority,
                    guard=guard,
                )
                original_atomic_write = publisher.seq47.atomic_write_seq47

                def mutating_atomic_write(*args, **kwargs):
                    def mutate_then_exchange(
                        parent_fd: int,
                        left: str,
                        right: str,
                    ) -> None:
                        mutate_after_final_guard()
                        publisher.seq47._rename_exchange(
                            parent_fd,
                            left,
                            right,
                        )

                    kwargs["exchanger"] = mutate_then_exchange
                    return original_atomic_write(*args, **kwargs)

                with (
                    mock.patch.object(
                        publisher.seq47,
                        "atomic_write_seq47",
                        side_effect=mutating_atomic_write,
                    ),
                    self.assertRaises(publisher.ResumePostCommitUncertain),
                ):
                    publisher.publish(prepared)
                self.assertEqual(checkpoint.read_bytes(), projected_bytes)

    def test_postcommit_rejects_same_snapshot_checkpoint_inode_swap(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (
                guard,
                checkpoint,
                source_bytes,
                projected_bytes,
                source_authority,
                evidence,
            ) = self._real_git_guard_fixture(root, reused_stage=False)
            publisher.seq47.atomic_write_seq47(
                checkpoint,
                projected_bytes,
                expected_source=source_bytes,
                expected_source_authority=source_authority,
                commit_guard=guard.verify,
                precommit_guard=guard.verify_precommit,
            )
            replacement = checkpoint.with_name("different-event.tmp")
            different = json.loads(projected_bytes)
            different["seq48_test_projection"] = "different-event"

            def swap_during_repository_capture(
                _root: Path,
                _checkpoint: Path,
                _event_id: str,
            ) -> dict[str, object]:
                replacement.write_bytes(publisher.seq47.json_bytes(different))
                os.chmod(replacement, 0o600)
                replacement.replace(checkpoint)
                return evidence.repository_payload

            try:
                with (
                    mock.patch.object(
                        publisher.gate.start_gate,
                        "capture_repository_state",
                        side_effect=swap_during_repository_capture,
                    ),
                    self.assertRaisesRegex(
                        publisher.ResumeApplyError,
                        "retained seq48 projected checkpoint identity differs",
                    ),
                ):
                    guard.verify_postcommit()
            finally:
                guard.close()

    def test_retention_rejects_same_byte_evidence_inode_replacement(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence, managed_paths, managed_digest, _event_dir = self._guard_fixture(root)
            relative = evidence.evidence_paths[0]
            path = root / relative
            replacement = path.with_name("replacement.tmp")
            replacement.write_bytes(path.read_bytes())
            os.chmod(replacement, 0o600)
            replacement.replace(path)
            with self.assertRaisesRegex(
                publisher.ResumeApplyError,
                "changed before retention",
            ):
                with mock.patch.object(
                    publisher.gate.start_gate.RetainedRepositoryAuthorityGuard,
                    "capture",
                    return_value=mock.Mock(),
                ):
                    publisher.ResumePublicationGuard.capture(
                        root,
                        managed_paths,
                        evidence,
                        managed_digest,
                        b"source",
                        b"projected",
                    )

    def test_retained_managed_parent_is_verified_via_held_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence, _managed_paths, _managed_digest, _event_dir = (
                self._guard_fixture(root)
            )
            managed = root / "nested/managed.txt"
            managed.parent.mkdir()
            managed.write_bytes(b"managed\n")
            relative = managed.relative_to(root).as_posix()
            digest = publisher._content_set_sha256(
                [relative],
                {relative: hashlib.sha256(managed.read_bytes()).hexdigest()},
            )
            guard = self._capture_synthetic_guard(
                root,
                [relative],
                evidence,
                digest,
            )
            try:
                detached = root.parent / f"{root.name}-nested-detached"
                managed.parent.rename(detached)
                managed.parent.mkdir()
                managed.write_bytes(b"managed\n")
                with self.assertRaisesRegex(
                    publisher.ResumeApplyError,
                    "managed resume ancestor changed",
                ):
                    guard.verify()
            finally:
                guard.close()
                managed.unlink()
                managed.parent.rmdir()
                detached.rename(root / "nested")

    def test_guard_allows_checkpoint_atomic_write_in_shared_parent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            evidence, _managed_paths, _managed_digest, _event_dir = (
                self._guard_fixture(root)
            )
            checkpoint = root / publisher.CHECKPOINT
            checkpoint.parent.mkdir(parents=True, exist_ok=True)
            checkpoint.write_bytes(b"source")
            os.chmod(checkpoint, 0o600)
            managed = checkpoint.parent / "managed.txt"
            managed.write_bytes(b"managed\n")
            relative = managed.relative_to(root).as_posix()
            digest = publisher._content_set_sha256(
                [relative],
                {relative: hashlib.sha256(managed.read_bytes()).hexdigest()},
            )
            guard = self._capture_synthetic_guard(
                root,
                [relative],
                evidence,
                digest,
            )
            try:
                _source, authority = publisher.seq47._capture_checkpoint_authority(
                    root,
                    b"source",
                )
                projected_authority = publisher.seq47.atomic_write_seq47(
                    checkpoint,
                    b"projected",
                    expected_source=b"source",
                    expected_source_authority=authority,
                    commit_guard=guard.verify,
                )
                guard.verify()
                self.assertEqual(checkpoint.read_bytes(), b"projected")
                self.assertEqual(
                    publisher.seq47._capture_checkpoint_authority(
                        root,
                        b"projected",
                    )[1],
                    projected_authority,
                )
            finally:
                guard.close()

    def test_recovery_rejects_checkpoint_not_reproducible_from_exact_inputs(self) -> None:
        evidence = self._evidence()
        projected, _event = publisher.project(
            self.source,
            self.source_bytes,
            evidence,
        )
        projected["goal_execution"]["transition_history"][-1][
            "occurred_at"
        ] = "2026-08-09T00:30:01+09:00"
        checkpoint_bytes = publisher._checkpoint_json_bytes(projected)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / publisher.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(checkpoint_bytes)
            os.chmod(checkpoint, 0o600)
            guard = mock.Mock()
            with (
                mock.patch.object(
                    publisher,
                    "require_resumed_checkpoint",
                    return_value=self.source_bytes,
                ),
                mock.patch.object(
                    publisher,
                    "load_gate_evidence",
                    return_value=evidence,
                ),
                mock.patch.object(
                    publisher.ResumePublicationGuard,
                    "capture",
                    return_value=guard,
                ),
                mock.patch.object(
                    publisher,
                    "_finalize_resumed_publication",
                ) as finalizer,
                self.assertRaisesRegex(
                    publisher.ResumePostCommitUncertain,
                    "recovery verification failed",
                ),
            ):
                publisher.inspect_or_recover_resumed_checkpoint(
                    root,
                    write=True,
                )
            finalizer.assert_not_called()
            guard.close.assert_not_called()

    def test_recovery_rejects_noncanonical_seq48_checkpoint_before_lineage(self) -> None:
        projected, _event = publisher.project(
            self.source,
            self.source_bytes,
            self._evidence(),
        )
        noncanonical = publisher._checkpoint_json_bytes(projected) + b"\n"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / publisher.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(noncanonical)
            os.chmod(checkpoint, 0o600)
            with (
                mock.patch.object(
                    publisher,
                    "require_resumed_checkpoint",
                ) as lineage,
                self.assertRaisesRegex(
                    publisher.ResumeApplyError,
                    "live seq48 checkpoint canonical JSON bytes differ",
                ),
            ):
                publisher.inspect_or_recover_resumed_checkpoint(
                    root,
                    write=True,
                )
            lineage.assert_not_called()

    def test_read_only_published_recovery_requires_explicit_write(self) -> None:
        evidence = self._evidence()
        projected, _event = publisher.project(
            self.source,
            self.source_bytes,
            evidence,
        )
        checkpoint_bytes = publisher._checkpoint_json_bytes(projected)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / publisher.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(checkpoint_bytes)
            os.chmod(checkpoint, 0o600)
            guard = mock.Mock()
            with (
                mock.patch.object(
                    publisher,
                    "require_resumed_checkpoint",
                    return_value=self.source_bytes,
                ),
                mock.patch.object(
                    publisher,
                    "load_gate_evidence",
                    return_value=evidence,
                ),
                mock.patch.object(
                    publisher.ResumePublicationGuard,
                    "capture",
                    return_value=guard,
                ),
                self.assertRaisesRegex(
                    publisher.ResumePostCommitUncertain,
                    "explicit write recovery is required",
                ),
            ):
                publisher.inspect_or_recover_resumed_checkpoint(
                    root,
                    write=False,
                )
            guard.verify.assert_called()
            guard.close.assert_called_once()

    def test_recovery_wrapper_forwards_terminal_guard_and_clean_requirement(
        self,
    ) -> None:
        terminal_guard = mock.Mock()
        projected_authority = mock.sentinel.projected_authority
        with mock.patch.object(
            publisher.seq47,
            "_finalize_projected_publication",
        ) as finalizer:
            publisher._finalize_resumed_publication(
                ROOT,
                b"projected",
                b"source",
                projected_authority,
                write=True,
                terminal_guard=terminal_guard,
                require_clean=True,
            )
        finalizer.assert_called_once_with(
            ROOT,
            b"projected",
            write=True,
            expected_projected_authority=projected_authority,
            expected_source_sha256=hashlib.sha256(b"source").hexdigest(),
            expected_source_byte_count=len(b"source"),
            terminal_guard=terminal_guard,
            require_clean=True,
        )

    def test_secure_writer_postcommit_uncertainty_maps_to_resume_rc2(self) -> None:
        prepared = mock.Mock()
        prepared.root = ROOT
        prepared.projected_bytes = b"projected"
        prepared.source_bytes = b"source"
        prepared.source_authority = mock.Mock()
        prepared.guard = mock.Mock()
        with mock.patch.object(
            publisher.seq47,
            "atomic_write_seq47",
            side_effect=publisher.seq47.StartPostCommitUncertain("uncertain"),
        ):
            with self.assertRaisesRegex(
                publisher.ResumePostCommitUncertain,
                "secure writer",
            ):
                publisher.publish(prepared)
        prepared.guard.close.assert_called_once()

        diagnostic = mock.Mock()
        with mock.patch.object(
            publisher,
            "inspect_or_recover_resumed_checkpoint",
            return_value=None,
        ), mock.patch.object(
            publisher,
            "prepare",
            return_value=prepared,
        ), mock.patch.object(
            publisher,
            "publish",
            side_effect=publisher.ResumePostCommitUncertain("uncertain"),
        ), mock.patch.object(
            publisher,
            "_write_postcommit_diagnostic",
            diagnostic,
        ):
            return_code = publisher.main(["--write", "--root", str(ROOT)])
        self.assertEqual(return_code, 2)
        diagnostic.assert_called_once()
        self.assertIn("POSTCOMMIT-UNCERTAIN", diagnostic.call_args.args[0])

    def test_write_cli_raw_pass_failures_are_rc2_for_fresh_and_recovery(
        self,
    ) -> None:
        event = {
            "event_sha256": "a" * 64,
            "source_checkpoint_sha256": "b" * 64,
        }
        existing = {
            "goal_execution": {
                "transition_history": [event],
            }
        }
        for route in ("fresh", "recovery"):
            for failure in (
                "invalid-fd",
                "immediate-error",
                "zero",
                "partial-error",
                "base-exception",
                "diagnostic-error",
            ):
                with (
                    self.subTest(route=route, failure=failure),
                    tempfile.TemporaryDirectory() as temporary,
                ):
                    root = Path(temporary).resolve()
                    prepared = mock.Mock()
                    prepared.event = event
                    stdout, stderr, calls, raw_write = (
                        self._raw_output_failure_fixture(failure)
                    )
                    with mock.patch.object(
                        publisher,
                        "inspect_or_recover_resumed_checkpoint",
                        return_value=existing if route == "recovery" else None,
                    ) as recover, mock.patch.object(
                        publisher,
                        "prepare",
                        return_value=prepared,
                    ) as prepare, mock.patch.object(
                        publisher,
                        "publish",
                    ) as publish, mock.patch.object(
                        publisher.os,
                        "write",
                        side_effect=raw_write,
                    ), mock.patch.object(
                        publisher.sys,
                        "stdout",
                        stdout,
                    ), mock.patch.object(
                        publisher.sys,
                        "stderr",
                        stderr,
                    ):
                        return_code = publisher.main(
                            ["--write", "--root", os.fspath(root)]
                        )
                    self.assertEqual(return_code, 2)
                    self.assertTrue(any(fd == 102 for fd, _ in calls))
                    self.assertTrue(
                        any(
                            b"POSTCOMMIT-UNCERTAIN" in content
                            for fd, content in calls
                            if fd == 102
                        )
                    )
                    stdout.write.assert_not_called()
                    stderr.write.assert_not_called()
                    if failure == "partial-error":
                        self.assertEqual(sum(fd == 101 for fd, _ in calls), 2)
                    if failure == "invalid-fd":
                        self.assertFalse(any(fd == 101 for fd, _ in calls))
                    else:
                        expected_mode = (
                            b"mode=WRITE-RECOVERED"
                            if route == "recovery"
                            else b"mode=WRITE"
                        )
                        self.assertIn(
                            expected_mode,
                            next(content for fd, content in calls if fd == 101),
                        )
                    recover.assert_called_once_with(
                        root,
                        write=True,
                        expected_event_id=publisher.EVENT_ID,
                    )
                    if route == "fresh":
                        prepare.assert_called_once_with(root, publisher.EVENT_ID)
                        publish.assert_called_once_with(prepared)
                    else:
                        prepare.assert_not_called()
                        publish.assert_not_called()

    def test_preflight_pass_output_failure_remains_ordinary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            prepared = mock.Mock()
            prepared.event = {
                "event_sha256": "a" * 64,
                "source_checkpoint_sha256": "b" * 64,
            }
            with mock.patch.object(
                publisher,
                "inspect_or_recover_resumed_checkpoint",
                return_value=None,
            ), mock.patch.object(
                publisher,
                "prepare",
                return_value=prepared,
            ), mock.patch(
                "builtins.print",
                side_effect=OSError("injected preflight output failure"),
            ), self.assertRaisesRegex(
                OSError,
                "injected preflight output failure",
            ):
                publisher.main(["--preflight", "--root", os.fspath(root)])
            prepared.guard.close.assert_called_once()

    def test_sigkill_restart_recovers_inherited_seq47_stage_states(self) -> None:
        kill_program = r"""
import os
from pathlib import Path
import signal
import sys
from scripts import apply_walksafe_fp008_goal_started_seq47_20260803 as secure_writer
root = Path(sys.argv[1])
kill_label = sys.argv[2]
path = root / secure_writer.CHECKPOINT
_, authority = secure_writer._capture_checkpoint_authority(root, b"source")
def kill_at_boundary(label: str) -> None:
    if label == kill_label:
        os.kill(os.getpid(), signal.SIGKILL)
secure_writer.atomic_write_seq47(
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
from scripts import apply_walksafe_fp008_goal_started_seq47_20260803 as secure_writer
root = Path(sys.argv[1])
path = root / secure_writer.CHECKPOINT
_, authority = secure_writer._capture_checkpoint_authority(root, b"source")
secure_writer.atomic_write_seq47(
    path,
    b"projected",
    expected_source=b"source",
    expected_source_authority=authority,
    commit_guard=lambda: None,
)
"""
        resume_projected_program = r"""
from pathlib import Path
import sys
from scripts import apply_walksafe_fp008_work_session_resumed_seq48_20260809 as publisher
root = Path(sys.argv[1])
_, authority = publisher.seq47._capture_checkpoint_authority(root, b"projected")
publisher._finalize_resumed_publication(
    root,
    b"projected",
    b"source",
    authority,
    write=True,
)
"""
        for kill_label in ("stage_linked", "after_exchange", "after_source_unlink"):
            with self.subTest(kill_label=kill_label), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                parent = root / publisher.CHECKPOINT.parent
                parent.mkdir(parents=True)
                path = root / publisher.CHECKPOINT
                path.write_bytes(b"source")
                os.chmod(path, 0o600)
                killed = subprocess.run(
                    [sys.executable, "-B", "-c", kill_program, str(root), kill_label],
                    cwd=ROOT,
                    capture_output=True,
                    text=True,
                    check=False,
                )
                self.assertEqual(killed.returncode, -signal.SIGKILL, killed.stderr)
                if kill_label == "stage_linked":
                    resumed = subprocess.run(
                        [sys.executable, "-B", "-c", resume_source_program, str(root)],
                        cwd=ROOT,
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    self.assertEqual(resumed.returncode, 0, resumed.stderr)
                else:
                    for _attempt in range(2):
                        resumed = subprocess.run(
                            [
                                sys.executable,
                                "-B",
                                "-c",
                                resume_projected_program,
                                str(root),
                            ],
                            cwd=ROOT,
                            capture_output=True,
                            text=True,
                            check=False,
                        )
                        self.assertEqual(resumed.returncode, 0, resumed.stderr)
                self.assertEqual(path.read_bytes(), b"projected")
                self.assertEqual([entry.name for entry in parent.iterdir()], [path.name])


if __name__ == "__main__":
    unittest.main()
