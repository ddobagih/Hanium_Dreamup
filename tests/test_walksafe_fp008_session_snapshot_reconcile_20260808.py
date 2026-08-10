from __future__ import annotations

import contextlib
import copy
import io
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


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import reconcile_walksafe_fp008_session_snapshot_seq47_20260808 as reconcile
from scripts import (
    reconcile_walksafe_fp008_isolated_snapshot_fix_seq47a_20260809 as corrective,
)
from scripts import apply_walksafe_fp008_work_session_resumed_seq48_20260809 as resumed


class WalkSafeFp008SessionSnapshotReconcileTest(unittest.TestCase):
    LIVE_IMMUTABILITY_RELATIVES = (
        Path(".github/workflows/quality.yml"),
        Path("docs/control/README.md"),
        reconcile.CHECKPOINT,
        Path("scripts/reconcile_walksafe_fp008_session_snapshot_seq47_20260808.py"),
        Path("scripts/run_walksafe_fp008_session_resume_gate_20260809.py"),
        Path("scripts/apply_walksafe_fp008_work_session_resumed_seq48_20260809.py"),
    )

    @staticmethod
    def _effective_authorized_existing_deltas() -> dict[str, dict[str, str]]:
        effective = copy.deepcopy(reconcile.AUTHORIZED_EXISTING_DELTAS)
        for relative, correction in (
            corrective.engine.AUTHORIZED_EXISTING_DELTAS.items()
        ):
            if relative in reconcile.ADDED_MANAGED_PATHS:
                continue
            previous = effective.get(relative)
            if previous is None:
                effective[relative] = copy.deepcopy(correction)
                continue
            if previous["candidate_sha256"] != correction["source_sha256"]:
                raise AssertionError(
                    f"corrective reconcile delta chain differs: {relative}"
                )
            effective[relative] = {
                "source_sha256": previous["source_sha256"],
                "candidate_sha256": correction["candidate_sha256"],
            }
        return effective

    @staticmethod
    def _observe_live_file(path: Path) -> tuple[bytes, tuple[int, ...]]:
        descriptor = os.open(
            path,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
            | getattr(os, "O_NOATIME", 0),
        )
        try:
            metadata = os.fstat(descriptor)
            chunks: list[bytes] = []
            offset = 0
            while offset < metadata.st_size:
                chunk = os.pread(
                    descriptor,
                    min(1024 * 1024, metadata.st_size - offset),
                    offset,
                )
                if not chunk:
                    break
                chunks.append(chunk)
                offset += len(chunk)
            content = b"".join(chunks)
            after = os.fstat(descriptor)
            named = path.lstat()
        finally:
            os.close(descriptor)
        fields = (
            "st_mode",
            "st_ino",
            "st_dev",
            "st_nlink",
            "st_uid",
            "st_gid",
            "st_size",
            "st_atime_ns",
            "st_mtime_ns",
            "st_ctime_ns",
            "st_rdev",
            "st_blksize",
            "st_blocks",
        )
        before_values = tuple(getattr(metadata, field) for field in fields)
        if (
            len(content) != metadata.st_size
            or tuple(getattr(after, field) for field in fields) != before_values
            or tuple(getattr(named, field) for field in fields) != before_values
        ):
            raise AssertionError(f"live authority changed while observing {path}")
        return content, before_values

    @staticmethod
    def _observe_live_root() -> tuple[tuple[str, ...], tuple[int, ...]]:
        descriptor = os.open(
            ROOT,
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
            | getattr(os, "O_NOATIME", 0),
        )
        try:
            metadata = os.fstat(descriptor)
            inventory = tuple(sorted(os.listdir(descriptor)))
            after = os.fstat(descriptor)
            named = ROOT.lstat()
        finally:
            os.close(descriptor)
        fields = (
            "st_mode",
            "st_ino",
            "st_dev",
            "st_nlink",
            "st_uid",
            "st_gid",
            "st_size",
            "st_atime_ns",
            "st_mtime_ns",
            "st_ctime_ns",
            "st_rdev",
            "st_blksize",
            "st_blocks",
        )
        values = tuple(getattr(metadata, field) for field in fields)
        if (
            tuple(getattr(after, field) for field in fields) != values
            or tuple(getattr(named, field) for field in fields) != values
        ):
            raise AssertionError("live repository root changed while observing it")
        return inventory, values

    @staticmethod
    def _raw_output_failure_fixture(
        failure: str,
    ) -> tuple[mock.Mock, mock.Mock, list[tuple[int, bytes]], object]:
        stdout_fd = 101
        stderr_fd = 102
        stdout = mock.Mock()
        stdout.fileno.return_value = stdout_fd
        stderr = mock.Mock()
        stderr.fileno.return_value = stderr_fd
        calls: list[tuple[int, bytes]] = []
        stdout_calls = 0

        def raw_write(descriptor: int, content: bytes) -> int:
            nonlocal stdout_calls
            calls.append((descriptor, content))
            if descriptor == stderr_fd:
                if failure == "diagnostic-baseexception":
                    raise KeyboardInterrupt("injected raw stderr failure")
                return len(content)
            if descriptor != stdout_fd:
                raise AssertionError("unexpected raw output descriptor")
            stdout_calls += 1
            if failure in {"error", "diagnostic-baseexception"}:
                raise OSError("injected raw stdout failure")
            if failure == "baseexception":
                raise KeyboardInterrupt("injected raw stdout interrupt")
            if failure == "zero":
                return 0
            if failure == "invalid":
                return True
            if failure == "partial-baseexception":
                if stdout_calls == 1:
                    return max(1, len(content) // 2)
                raise KeyboardInterrupt("injected partial raw stdout interrupt")
            raise AssertionError("unexpected raw output failure fixture")

        return stdout, stderr, calls, raw_write

    @staticmethod
    def _live_tree_files(relative_root: Path) -> set[str]:
        found: set[str] = set()

        def walk(relative: Path) -> None:
            descriptor = os.open(
                ROOT / relative,
                os.O_RDONLY
                | getattr(os, "O_DIRECTORY", 0)
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0)
                | getattr(os, "O_NOATIME", 0),
            )
            try:
                for name in sorted(os.listdir(descriptor)):
                    metadata = os.stat(
                        name,
                        dir_fd=descriptor,
                        follow_symlinks=False,
                    )
                    child = relative / name
                    if stat.S_ISDIR(metadata.st_mode):
                        walk(child)
                    elif stat.S_ISREG(metadata.st_mode):
                        found.add(child.as_posix())
            finally:
                os.close(descriptor)

        walk(relative_root)
        return found

    @classmethod
    def setUpClass(cls) -> None:
        cls.live_root_observation = cls._observe_live_root()
        cls.live_observations = {
            relative: cls._observe_live_file(ROOT / relative)
            for relative in cls.LIVE_IMMUTABILITY_RELATIVES
        }
        cls.temporary = tempfile.TemporaryDirectory(
            prefix=".fp008-reconcile-test-",
        )
        cls.root = Path(cls.temporary.name).resolve()
        live = json.loads(cls.live_observations[reconcile.CHECKPOINT][0])
        history = live.get("goal_execution", {}).get("transition_history", [])
        if history and history[-1].get("event_type") == "WORK_SESSION_RESUMED":
            live, _live_bytes = resumed._source_from_resumed_checkpoint(live)
        paths = live["working_tree_snapshot"]["managed_changed_paths"]
        if set(reconcile.ADDED_MANAGED_PATHS).issubset(paths):
            source = reconcile._source_from_candidate(live)
        else:
            source = live
        source_bytes = reconcile.json_bytes(source)
        if reconcile.sha256_bytes(source_bytes) != reconcile.SOURCE_CHECKPOINT_SHA256:
            raise AssertionError("could not reconstruct the exact seq47 reconcile source")
        cls.source_bytes = source_bytes
        cls.reconcile_delta_patcher = mock.patch.object(
            reconcile,
            "AUTHORIZED_EXISTING_DELTAS",
            cls._effective_authorized_existing_deltas(),
        )
        cls.reconcile_delta_patcher.start()
        cls.addClassCleanup(cls.reconcile_delta_patcher.stop)
        copied = {str(value) for value in paths}
        copied.update(reconcile.ADDED_MANAGED_PATHS)
        copied.update(
            relative.as_posix() for relative in cls.LIVE_IMMUTABILITY_RELATIVES
        )
        copied.update(cls._live_tree_files(Path("docs/control")))

        def referenced_paths(value: object) -> set[str]:
            found: set[str] = set()
            if isinstance(value, dict):
                for child in value.values():
                    found.update(referenced_paths(child))
            elif isinstance(value, list):
                for child in value:
                    found.update(referenced_paths(child))
            elif isinstance(value, str):
                relative = Path(value)
                if not relative.is_absolute() and ".." not in relative.parts:
                    try:
                        is_file = (ROOT / relative).is_file()
                    except OSError:
                        is_file = False
                    if is_file:
                        found.add(relative.as_posix())
            return found

        copied.update(referenced_paths(source))
        inspected_json: set[str] = set()
        while True:
            pending = sorted(
                relative
                for relative in copied - inspected_json
                if relative.endswith(".json")
            )
            if not pending:
                break
            for relative in pending:
                inspected_json.add(relative)
                try:
                    value = json.loads(cls._observe_live_file(ROOT / relative)[0])
                except (OSError, UnicodeDecodeError, json.JSONDecodeError):
                    continue
                copied.update(referenced_paths(value))
        copied.discard(reconcile.CHECKPOINT.as_posix())
        for relative in sorted(copied):
            source_path = ROOT / relative
            if not source_path.is_file() or source_path.is_symlink():
                continue
            target = cls.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            content, metadata = cls._observe_live_file(source_path)
            target.write_bytes(content)
            os.chmod(target, stat.S_IMODE(metadata[0]))
        for directory in sorted(
            (path for path in cls.root.rglob("*") if path.is_dir()),
            key=lambda path: len(path.parts),
        ):
            source_directory = ROOT / directory.relative_to(cls.root)
            if source_directory.is_dir() and not source_directory.is_symlink():
                os.chmod(directory, stat.S_IMODE(source_directory.stat().st_mode))
        checkpoint = cls.root / reconcile.CHECKPOINT
        checkpoint.parent.mkdir(parents=True, exist_ok=True)
        checkpoint.write_bytes(source_bytes)
        os.chmod(checkpoint, 0o600)
        cls.authority_temporary = tempfile.TemporaryDirectory(
            prefix="fp008-independent-authority-",
        )
        authority_directory = Path(cls.authority_temporary.name).resolve()
        os.chmod(authority_directory, 0o700)
        cls.authority_path = authority_directory / "approved-added-paths.json"
        candidate_paths = sorted(set(paths) | set(reconcile.ADDED_MANAGED_PATHS))
        cohort = reconcile.ReconcileCohort.capture(cls.root, candidate_paths)
        try:
            projected = reconcile._project_from_retained(cls.root, source, cohort)
            projected_bytes = reconcile.json_bytes(projected)
            signed_rows = reconcile.signed_authority_rows(cohort)
            authority = {
                "schema_version": reconcile.ADDED_AUTHORITY_SCHEMA,
                "operation_version": reconcile.ADDED_AUTHORITY_OPERATION,
                "repository": {
                    "uuid": reconcile.REPOSITORY_UUID,
                    "canonical_root": str(cls.root),
                },
                "approval_id": "cd" * 32,
                "generation_nonce": "ab" * 32,
                "reviewer": {
                    "key_fingerprint_sha256": (
                        reconcile.REVIEWER_PUBLIC_KEY_SPKI_SHA256
                    ),
                    "signature_algorithm": (
                        reconcile.ADDED_AUTHORITY_SIGNATURE_ALGORITHM
                    ),
                    "signature_domain": (
                        reconcile.ADDED_AUTHORITY_SIGNATURE_DOMAIN.decode("ascii")
                    ),
                },
                "base_checkpoint": {
                    "sha256": reconcile.SOURCE_CHECKPOINT_SHA256,
                    "size": reconcile.SOURCE_CHECKPOINT_BYTE_COUNT,
                },
                "added_managed_paths": signed_rows["added_managed_paths"],
                "authorized_existing_deltas": signed_rows[
                    "authorized_existing_deltas"
                ],
                "candidate_snapshot": {
                    "managed_path_count": len(candidate_paths),
                    "path_set_sha256": projected["working_tree_snapshot"]
                    ["path_set_sha256"],
                    "content_set_sha256": cohort.content_set_sha256(),
                },
                "candidate_checkpoint": {
                    "sha256": reconcile.sha256_bytes(projected_bytes),
                    "size": len(projected_bytes),
                },
            }
            cls.authority_bytes = reconcile.authority_json_bytes(authority)
        finally:
            cohort.close()
        cls.authority_path.write_bytes(cls.authority_bytes)
        os.chmod(cls.authority_path, 0o600)
        cls.authority_sha256 = reconcile.sha256_bytes(cls.authority_bytes)
        cls.signature_path = authority_directory / "approved-added-paths.sig"
        cls.signature_bytes = b"\0" * reconcile.ED25519_SIGNATURE_BYTE_COUNT
        cls.signature_path.write_bytes(cls.signature_bytes)
        os.chmod(cls.signature_path, 0o600)
        cls.signature_sha256 = reconcile.sha256_bytes(cls.signature_bytes)
        cls.real_verify_reviewer_signature = staticmethod(
            reconcile._verify_reviewer_signature
        )
        cls.signature_verifier_patcher = mock.patch.object(
            reconcile,
            "_verify_reviewer_signature",
            autospec=True,
        )
        cls.signature_verifier_patcher.start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.signature_verifier_patcher.stop()
        cls.temporary.cleanup()
        cls.authority_temporary.cleanup()
        after = {
            relative: cls._observe_live_file(ROOT / relative)
            for relative in cls.LIVE_IMMUTABILITY_RELATIVES
        }
        if after != cls.live_observations:
            raise AssertionError("A fixture changed live repository metadata or bytes")
        if cls._observe_live_root() != cls.live_root_observation:
            raise AssertionError(
                "A fixture changed live repository root metadata or inventory"
            )

    @classmethod
    def capture_authority(cls) -> reconcile.AddedAuthorityManifestGuard:
        return reconcile.AddedAuthorityManifestGuard.capture(
            cls.root,
            cls.authority_path,
            cls.authority_sha256,
            len(cls.authority_bytes),
            cls.signature_path,
            cls.signature_sha256,
            len(cls.signature_bytes),
        )

    @classmethod
    def prepare_candidate(cls) -> reconcile.PreparedReconcile:
        authority = cls.capture_authority()
        try:
            return reconcile.prepare(cls.root, authority)
        except BaseException as exc:
            authority.close(exc)
            raise

    @staticmethod
    def close_prepared(prepared: reconcile.PreparedReconcile) -> None:
        primary: BaseException | None = None
        try:
            if prepared.cohort._pins:
                prepared.cohort.close()
        except BaseException as exc:
            primary = exc
            raise
        finally:
            if prepared.added_authority.descriptor is not None:
                prepared.added_authority.close(primary)

    def project_source(self, source: dict[str, object]) -> dict[str, object]:
        exact = json.loads((self.root / reconcile.CHECKPOINT).read_bytes())
        paths = sorted(
            set(exact["working_tree_snapshot"]["managed_changed_paths"])
            | set(reconcile.ADDED_MANAGED_PATHS)
        )
        cohort = reconcile.ReconcileCohort.capture(self.root, paths)
        authority = self.capture_authority()
        primary: BaseException | None = None
        try:
            return reconcile.project(self.root, source, cohort, authority)
        except BaseException as exc:
            primary = exc
            raise
        finally:
            cohort.close(primary)
            authority.close(primary)

    def test_fixture_uses_independent_mode_preserving_byte_copies(self) -> None:
        for relative in self.LIVE_IMMUTABILITY_RELATIVES:
            with self.subTest(relative=relative):
                live = ROOT / relative
                copied = self.root / relative
                expected = (
                    self.source_bytes
                    if relative == reconcile.CHECKPOINT
                    else self._observe_live_file(live)[0]
                )
                self.assertEqual(
                    copied.read_bytes(),
                    expected,
                )
                self.assertEqual(
                    stat.S_IMODE(copied.stat().st_mode),
                    stat.S_IMODE(live.stat().st_mode),
                )
                self.assertNotEqual(
                    (copied.stat().st_dev, copied.stat().st_ino),
                    (live.stat().st_dev, live.stat().st_ino),
                )
                self.assertEqual(copied.stat().st_nlink, 1)

    def test_pinned_reviewer_key_rejects_an_invalid_detached_signature(self) -> None:
        self.assertEqual(
            reconcile.sha256_bytes(
                reconcile.base64.b64decode(
                    reconcile.REVIEWER_PUBLIC_KEY_SPKI_DER_BASE64,
                    validate=True,
                )
            ),
            reconcile.REVIEWER_PUBLIC_KEY_SPKI_SHA256,
        )
        envelope = reconcile.authority_signature_envelope(self.authority_bytes)
        self.assertTrue(
            envelope.startswith(reconcile.ADDED_AUTHORITY_SIGNATURE_DOMAIN + b"\0")
        )
        with self.assertRaisesRegex(reconcile.ReconcileError, "signature is invalid"):
            self.real_verify_reviewer_signature(
                self.authority_bytes,
                self.signature_bytes,
            )

    def test_unsigned_template_is_comparison_only_and_exactly_physical(self) -> None:
        checkpoint = self.root / reconcile.CHECKPOINT
        before = checkpoint.read_bytes()
        template = reconcile.unsigned_authority_template(self.root)
        self.assertEqual(template["status"], "UNAUTHORIZED_TEMPLATE")
        self.assertTrue(template["comparison_only"])
        self.assertFalse(template["may_prepare_or_write"])
        manifest = template["manifest_template"]
        self.assertTrue(manifest["approval_id"].startswith("REPLACE_WITH_"))
        self.assertTrue(manifest["generation_nonce"].startswith("REPLACE_WITH_"))
        self.assertEqual(len(manifest["added_managed_paths"]), 6)
        self.assertEqual(
            len(manifest["authorized_existing_deltas"]),
            len(reconcile.AUTHORIZED_EXISTING_DELTAS),
        )
        for row in (
            *manifest["added_managed_paths"],
            *manifest["authorized_existing_deltas"],
        ):
            self.assertTrue(
                {
                    "path",
                    "bytes",
                    "file_type",
                    "mode",
                    "uid",
                    "gid",
                    "nlink",
                    "repository_inode_aliases",
                    "external_inode_aliases",
                }.issubset(row)
            )
        self.assertEqual(checkpoint.read_bytes(), before)

    def test_authority_requires_external_exact_canonical_private_bytes(self) -> None:
        with self.assertRaisesRegex(reconcile.ReconcileError, "absolute"):
            reconcile.AddedAuthorityManifestGuard.capture(
                self.root,
                Path("relative-authority.json"),
                self.authority_sha256,
                len(self.authority_bytes),
                self.signature_path,
                self.signature_sha256,
                len(self.signature_bytes),
            )
        inside = self.root / "inside-authority.json"
        inside.write_bytes(self.authority_bytes)
        os.chmod(inside, 0o600)
        with self.assertRaisesRegex(reconcile.ReconcileError, "outside"):
            reconcile.AddedAuthorityManifestGuard.capture(
                self.root,
                inside,
                self.authority_sha256,
                len(self.authority_bytes),
                self.signature_path,
                self.signature_sha256,
                len(self.signature_bytes),
            )
        with self.assertRaisesRegex(reconcile.ReconcileError, "SHA-256 differs"):
            reconcile.AddedAuthorityManifestGuard.capture(
                self.root,
                self.authority_path,
                "0" * 64,
                len(self.authority_bytes),
                self.signature_path,
                self.signature_sha256,
                len(self.signature_bytes),
            )
        duplicate = self.authority_bytes.replace(
            b'{"added_managed_paths"',
            b'{"schema_version":"duplicate","added_managed_paths"',
            1,
        )
        with tempfile.TemporaryDirectory(
            prefix="fp008-invalid-authority-"
        ) as temporary:
            directory = Path(temporary).resolve()
            os.chmod(directory, 0o700)
            path = directory / "duplicate.json"
            path.write_bytes(duplicate)
            os.chmod(path, 0o600)
            with self.assertRaisesRegex(reconcile.ReconcileError, "duplicate key"):
                reconcile.AddedAuthorityManifestGuard.capture(
                    self.root,
                    path,
                    reconcile.sha256_bytes(duplicate),
                    len(duplicate),
                    self.signature_path,
                    self.signature_sha256,
                    len(self.signature_bytes),
                )
            noncanonical = (
                json.dumps(json.loads(self.authority_bytes), indent=2) + "\n"
            ).encode("utf-8")
            path.write_bytes(noncanonical)
            os.chmod(path, 0o600)
            with self.assertRaisesRegex(reconcile.ReconcileError, "canonical"):
                reconcile.AddedAuthorityManifestGuard.capture(
                    self.root,
                    path,
                    reconcile.sha256_bytes(noncanonical),
                    len(noncanonical),
                    self.signature_path,
                    self.signature_sha256,
                    len(self.signature_bytes),
                )
            path.write_bytes(self.authority_bytes)
            os.chmod(path, 0o640)
            with self.assertRaisesRegex(reconcile.ReconcileError, "0600"):
                reconcile.AddedAuthorityManifestGuard.capture(
                    self.root,
                    path,
                    self.authority_sha256,
                    len(self.authority_bytes),
                    self.signature_path,
                    self.signature_sha256,
                    len(self.signature_bytes),
                )
            os.chmod(path, 0o600)
            sibling = directory / "hardlink.json"
            os.link(path, sibling)
            with self.assertRaisesRegex(reconcile.ReconcileError, "singly linked"):
                reconcile.AddedAuthorityManifestGuard.capture(
                    self.root,
                    path,
                    self.authority_sha256,
                    len(self.authority_bytes),
                    self.signature_path,
                    self.signature_sha256,
                    len(self.signature_bytes),
                )

    def test_authority_exact_schema_rejects_added_digest_and_numeric_near_miss(
        self,
    ) -> None:
        original = json.loads(self.authority_bytes)
        for label, mutate in (
            (
                "added digest",
                lambda value: value["added_managed_paths"][0].__setitem__(
                    "sha256", "0" * 64
                ),
            ),
            (
                "numeric near miss",
                lambda value: value["candidate_checkpoint"].__setitem__(
                    "size", float(value["candidate_checkpoint"]["size"])
                ),
            ),
            (
                "path alias",
                lambda value: value["added_managed_paths"][0].__setitem__(
                    "path", "./" + value["added_managed_paths"][0]["path"]
                ),
            ),
            (
                "repository binding",
                lambda value: value["repository"].__setitem__(
                    "uuid", "00000000-0000-0000-0000-000000000000"
                ),
            ),
            (
                "signed mode",
                lambda value: value["added_managed_paths"][0].__setitem__(
                    "mode", "0666"
                ),
            ),
            (
                "signed link count",
                lambda value: value["authorized_existing_deltas"][0].__setitem__(
                    "nlink", value["authorized_existing_deltas"][0]["nlink"] + 1
                ),
            ),
        ):
            with self.subTest(label=label), tempfile.TemporaryDirectory(
                prefix="fp008-schema-authority-"
            ) as temporary:
                document = copy.deepcopy(original)
                mutate(document)
                raw = reconcile.authority_json_bytes(document)
                directory = Path(temporary).resolve()
                os.chmod(directory, 0o700)
                path = directory / "approved.json"
                path.write_bytes(raw)
                os.chmod(path, 0o600)
                authority = reconcile.AddedAuthorityManifestGuard.capture(
                    self.root,
                    path,
                    reconcile.sha256_bytes(raw),
                    len(raw),
                    self.signature_path,
                    self.signature_sha256,
                    len(self.signature_bytes),
                )
                try:
                    with self.assertRaisesRegex(
                        reconcile.ReconcileError,
                        "authority",
                    ):
                        reconcile.prepare(self.root, authority)
                finally:
                    authority.close()

    def test_authority_guard_rejects_same_byte_inode_replacement(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="fp008-swap-authority-"
        ) as temporary:
            directory = Path(temporary).resolve()
            os.chmod(directory, 0o700)
            path = directory / "approved.json"
            path.write_bytes(self.authority_bytes)
            os.chmod(path, 0o600)
            guard = reconcile.AddedAuthorityManifestGuard.capture(
                self.root,
                path,
                self.authority_sha256,
                len(self.authority_bytes),
                self.signature_path,
                self.signature_sha256,
                len(self.signature_bytes),
            )
            replacement = directory / "replacement.json"
            replacement.write_bytes(self.authority_bytes)
            os.chmod(replacement, 0o600)
            os.replace(replacement, path)
            try:
                with self.assertRaisesRegex(reconcile.ReconcileError, "identity"):
                    guard.verify()
            finally:
                guard.close()

    def test_signature_guard_rejects_same_byte_inode_replacement(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="fp008-swap-signature-"
        ) as temporary:
            directory = Path(temporary).resolve()
            os.chmod(directory, 0o700)
            manifest_path = directory / "approved.json"
            manifest_path.write_bytes(self.authority_bytes)
            os.chmod(manifest_path, 0o600)
            signature_path = directory / "approved.sig"
            signature_path.write_bytes(self.signature_bytes)
            os.chmod(signature_path, 0o600)
            guard = reconcile.AddedAuthorityManifestGuard.capture(
                self.root,
                manifest_path,
                self.authority_sha256,
                len(self.authority_bytes),
                signature_path,
                self.signature_sha256,
                len(self.signature_bytes),
            )
            replacement = directory / "replacement.sig"
            replacement.write_bytes(self.signature_bytes)
            os.chmod(replacement, 0o600)
            os.replace(replacement, signature_path)
            try:
                with self.assertRaisesRegex(
                    reconcile.ReconcileError,
                    "signature identity changed",
                ):
                    guard.verify()
            finally:
                guard.close()

    def test_preflight_projects_only_authorized_snapshot_membership_fields_without_writing(self) -> None:
        checkpoint = self.root / reconcile.CHECKPOINT
        before = checkpoint.read_bytes()
        prepared = self.prepare_candidate()
        try:
            self.assertEqual(
                reconcile._changed_fields(prepared.source, prepared.projected),
                set(reconcile.ALLOWED_MUTATIONS),
            )
            self.assertEqual(
                prepared.projected["working_tree_snapshot"]["content_set_sha256"],
                prepared.cohort.content_set_sha256(),
            )
            self.assertEqual(
                prepared.projected["session_handoff"]["source_commit_or_snapshot"]
                ["content_set_sha256"],
                prepared.cohort.content_set_sha256(),
            )
            self.assertEqual(
                prepared.projected["goal_execution"]["transition_history"],
                prepared.source["goal_execution"]["transition_history"],
            )
            self.assertEqual(
                prepared.projected["goal_execution"]["status_by_goal"],
                prepared.source["goal_execution"]["status_by_goal"],
            )
            self.assertEqual(
                prepared.projected["canonical_bindings"],
                prepared.source["canonical_bindings"],
            )
            self.assertEqual(
                reconcile._source_from_candidate(prepared.projected),
                prepared.source,
            )
        finally:
            self.close_prepared(prepared)
        self.assertEqual(checkpoint.read_bytes(), before)

    def test_cli_preflight_reports_candidate_and_does_not_write(self) -> None:
        checkpoint = self.root / reconcile.CHECKPOINT
        before = checkpoint.read_bytes()
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = reconcile.main(
                [
                    "--preflight",
                    "--root",
                    str(self.root),
                    "--added-authority-manifest",
                    str(self.authority_path),
                    "--added-authority-sha256",
                    self.authority_sha256,
                    "--added-authority-bytes",
                    str(len(self.authority_bytes)),
                    "--added-authority-signature",
                    str(self.signature_path),
                    "--added-authority-signature-sha256",
                    self.signature_sha256,
                    "--added-authority-signature-bytes",
                    str(len(self.signature_bytes)),
                ]
            )
        self.assertEqual(result, 0, stderr.getvalue())
        payload = json.loads(stdout.getvalue())
        self.assertEqual(payload["mode"], "PREFLIGHT")
        self.assertFalse(payload["wrote_checkpoint"])
        self.assertEqual(
            payload["managed_path_count"],
            reconcile.MANAGED_PATH_COUNT + len(reconcile.ADDED_MANAGED_PATHS),
        )
        self.assertEqual(
            payload["added_managed_paths"],
            list(reconcile.ADDED_MANAGED_PATHS),
        )
        self.assertEqual(
            payload["allowed_checkpoint_mutations"],
            list(reconcile.ALLOWED_MUTATIONS),
        )
        signed = payload["independent_added_authority"]
        self.assertEqual(signed["signature_sha256"], self.signature_sha256)
        self.assertEqual(
            signed["reviewer_key_fingerprint_sha256"],
            reconcile.REVIEWER_PUBLIC_KEY_SPKI_SHA256,
        )
        self.assertEqual(
            len(signed["signed_candidate_files"]["added_managed_paths"]),
            6,
        )
        self.assertEqual(checkpoint.read_bytes(), before)

    def test_writer_cli_raw_pass_failures_are_rc2_for_fresh_and_recovery(
        self,
    ) -> None:
        failures = (
            "error",
            "baseexception",
            "zero",
            "invalid",
            "partial-baseexception",
            "diagnostic-baseexception",
        )
        writer = reconcile.secure_writer
        for route in ("fresh", "recovery"):
            for failure in failures:
                with self.subTest(route=route, failure=failure):
                    stdout, stderr, calls, raw_write = (
                        self._raw_output_failure_fixture(failure)
                    )
                    args = mock.Mock(root=Path("/unused"), write=True)
                    existing = (
                        {
                            "goal_execution": {
                                "transition_history": [
                                    {"event_sha256": "e" * 64}
                                ]
                            }
                        }
                        if route == "recovery"
                        else None
                    )
                    prepared = mock.Mock()
                    prepared.event = {"event_sha256": "f" * 64}
                    with (
                        mock.patch.object(writer, "parse_args", return_value=args),
                        mock.patch.object(
                            writer,
                            "inspect_or_recover_started_checkpoint",
                            return_value=existing,
                        ) as recover,
                        mock.patch.object(
                            writer,
                            "prepare",
                            return_value=prepared,
                        ) as prepare,
                        mock.patch.object(writer, "publish") as publish,
                        mock.patch.object(
                            writer.os,
                            "write",
                            side_effect=raw_write,
                        ),
                        mock.patch.object(writer.sys, "stdout", stdout),
                        mock.patch.object(writer.sys, "stderr", stderr),
                    ):
                        result = writer.main(["--write"])
                    self.assertEqual(result, 2)
                    recover.assert_called_once_with(Path("/unused"), write=True)
                    stdout_calls = [call for call in calls if call[0] == 101]
                    stderr_calls = [call for call in calls if call[0] == 102]
                    self.assertEqual(len(stderr_calls), 1)
                    self.assertIn(b"POSTCOMMIT-UNCERTAIN", stderr_calls[0][1])
                    self.assertEqual(
                        len(stdout_calls),
                        2 if failure == "partial-baseexception" else 1,
                    )
                    expected_mode = (
                        b"mode=WRITE-RECOVERED"
                        if route == "recovery"
                        else b"mode=WRITE"
                    )
                    self.assertIn(expected_mode, stdout_calls[0][1])
                    if route == "fresh":
                        prepare.assert_called_once_with(Path("/unused"))
                        publish.assert_called_once_with(prepared)
                    else:
                        prepare.assert_not_called()
                        publish.assert_not_called()

    def test_reconcile_cli_raw_report_failures_are_rc2_for_fresh_and_recovery(
        self,
    ) -> None:
        failures = (
            "error",
            "baseexception",
            "zero",
            "invalid",
            "partial-baseexception",
            "diagnostic-baseexception",
        )
        for route in ("fresh", "recovery"):
            for failure in failures:
                with self.subTest(route=route, failure=failure):
                    stdout, stderr, calls, raw_write = (
                        self._raw_output_failure_fixture(failure)
                    )
                    args = mock.Mock(
                        root=Path("/unused"),
                        write=True,
                        print_unsigned_authority_template=False,
                        added_authority_manifest=Path("/outside/manifest.json"),
                        added_authority_sha256="a" * 64,
                        added_authority_bytes=1,
                        added_authority_signature=Path("/outside/manifest.sig"),
                        added_authority_signature_sha256="b" * 64,
                        added_authority_signature_bytes=64,
                    )
                    authority = mock.Mock()
                    existing = {"recognized": True} if route == "recovery" else None
                    prepared = mock.Mock()
                    payload = {"mode": "WRITE-RECOVERED" if existing else "WRITE"}
                    with (
                        mock.patch.object(
                            reconcile,
                            "parse_args",
                            return_value=args,
                        ),
                        mock.patch.object(
                            reconcile.AddedAuthorityManifestGuard,
                            "capture",
                            return_value=authority,
                        ),
                        mock.patch.object(
                            reconcile,
                            "inspect_or_recover_reconciled_checkpoint",
                            return_value=existing,
                        ) as recover,
                        mock.patch.object(
                            reconcile,
                            "_report_candidate",
                            return_value=payload,
                        ) as recovered_report,
                        mock.patch.object(
                            reconcile,
                            "prepare",
                            return_value=prepared,
                        ) as prepare,
                        mock.patch.object(reconcile, "publish") as publish,
                        mock.patch.object(
                            reconcile,
                            "report",
                            return_value=payload,
                        ) as fresh_report,
                        mock.patch.object(
                            reconcile.os,
                            "write",
                            side_effect=raw_write,
                        ),
                        mock.patch.object(reconcile.sys, "stdout", stdout),
                        mock.patch.object(reconcile.sys, "stderr", stderr),
                    ):
                        result = reconcile.main(["--write"])
                    self.assertEqual(result, 2)
                    recover.assert_called_once_with(
                        Path("/unused"),
                        write=True,
                        added_authority=authority,
                    )
                    stdout_calls = [call for call in calls if call[0] == 101]
                    stderr_calls = [call for call in calls if call[0] == 102]
                    self.assertEqual(len(stderr_calls), 1)
                    self.assertIn(b"POSTCOMMIT-UNCERTAIN", stderr_calls[0][1])
                    self.assertEqual(
                        len(stdout_calls),
                        2 if failure == "partial-baseexception" else 1,
                    )
                    self.assertIn(payload["mode"].encode("utf-8"), stdout_calls[0][1])
                    if route == "fresh":
                        prepare.assert_called_once_with(Path("/unused"), authority)
                        publish.assert_called_once_with(prepared)
                        fresh_report.assert_called_once_with(
                            prepared,
                            mode="WRITE",
                            wrote=True,
                        )
                        recovered_report.assert_not_called()
                    else:
                        prepare.assert_not_called()
                        publish.assert_not_called()
                        fresh_report.assert_not_called()
                        recovered_report.assert_called_once()
                        authority.verify.assert_called_once_with()
                        authority.close.assert_called_once_with()

    def test_projection_rejects_any_runtime_or_membership_change(self) -> None:
        source = json.loads((self.root / reconcile.CHECKPOINT).read_bytes())
        changed_status = copy.deepcopy(source)
        changed_status["goal_execution"]["status_by_goal"][
            reconcile.FOCUS_GOAL_ID
        ] = "READY"
        with self.assertRaisesRegex(reconcile.ReconcileError, "runtime status"):
            self.project_source(changed_status)

        changed_paths = copy.deepcopy(source)
        changed_paths["working_tree_snapshot"]["managed_changed_paths"].pop()
        with self.assertRaisesRegex(reconcile.ReconcileError, "managed snapshot"):
            self.project_source(changed_paths)

    def test_projection_requires_full_transition_replay(self) -> None:
        source = json.loads((self.root / reconcile.CHECKPOINT).read_bytes())
        with mock.patch.object(
            reconcile.continuation,
            "validate_transition_replay",
            return_value=["synthetic replay failure"],
        ):
            with self.assertRaisesRegex(
                reconcile.ReconcileError,
                "synthetic replay failure",
            ):
                self.project_source(source)

    def test_publish_delegates_to_seq47_secure_atomic_writer(self) -> None:
        prepared = self.prepare_candidate()
        captured: dict[str, object] = {}

        def fake_writer(path: Path, content: bytes, **kwargs: object) -> object:
            captured["path"] = path
            captured["content"] = content
            captured["expected_source"] = kwargs["expected_source"]
            kwargs["precommit_guard"]()
            kwargs["commit_guard"]()
            return prepared.source_authority

        def fake_finalizer(*args: object, **kwargs: object) -> None:
            self.assertTrue(kwargs["write"])
            kwargs["terminal_guard"]()

        try:
            with mock.patch.object(
                reconcile.secure_writer,
                "atomic_write_seq47",
                side_effect=fake_writer,
            ), mock.patch.object(
                reconcile.secure_writer,
                "_capture_checkpoint_authority",
                return_value=(
                    prepared.projected_bytes,
                    prepared.source_authority,
                ),
            ), mock.patch.object(
                reconcile.secure_writer,
                "_finalize_projected_publication",
                side_effect=fake_finalizer,
            ):
                reconcile.publish(prepared)
        finally:
            if prepared.cohort._pins:
                self.close_prepared(prepared)
        self.assertEqual(captured["path"], self.root / reconcile.CHECKPOINT)
        self.assertEqual(captured["content"], prepared.projected_bytes)
        self.assertEqual(captured["expected_source"], prepared.source_bytes)

    def test_published_candidate_is_idempotently_inspected_for_recovery(self) -> None:
        prepared = self.prepare_candidate()
        authority = self.capture_authority()
        try:
            with mock.patch.object(
                reconcile.secure_writer,
                "_capture_checkpoint_authority",
                return_value=(
                    prepared.projected_bytes,
                    prepared.source_authority,
                ),
            ), mock.patch.object(
                reconcile.secure_writer,
                "_finalize_projected_publication",
            ) as finalizer:
                recovered = reconcile.inspect_or_recover_reconciled_checkpoint(
                    self.root,
                    write=True,
                    added_authority=authority,
                )
            self.assertEqual(recovered, prepared.projected)
            finalizer.assert_called_once()
            self.assertTrue(finalizer.call_args.kwargs["write"])
        finally:
            authority.close()
            self.close_prepared(prepared)

    def test_recovery_rejects_same_byte_checkpoint_authority_swap(self) -> None:
        prepared = self.prepare_candidate()
        authority = self.capture_authority()
        try:
            with (
                mock.patch.object(
                    reconcile.secure_writer,
                    "_capture_checkpoint_authority",
                    return_value=(
                        prepared.projected_bytes,
                        prepared.source_authority,
                    ),
                ),
                mock.patch.object(
                    reconcile.secure_writer,
                    "_finalize_projected_publication",
                    side_effect=reconcile.secure_writer.StartPostCommitUncertain(
                        "same-byte projected authority swap"
                    ),
                ),
                self.assertRaises(reconcile.ReconcilePostCommitUncertain),
            ):
                reconcile.inspect_or_recover_reconciled_checkpoint(
                    self.root,
                    write=True,
                    added_authority=authority,
                )
        finally:
            authority.close()
            self.close_prepared(prepared)

    def test_recognized_reconcile_maps_generic_finalizer_failure_to_uncertain(
        self,
    ) -> None:
        prepared = self.prepare_candidate()
        authority = self.capture_authority()
        try:
            with (
                mock.patch.object(
                    reconcile.secure_writer,
                    "_capture_checkpoint_authority",
                    return_value=(
                        prepared.projected_bytes,
                        prepared.source_authority,
                    ),
                ),
                mock.patch.object(
                    reconcile.secure_writer,
                    "_finalize_projected_publication",
                    side_effect=reconcile.ReconcileError(
                        "recognized reconcile terminal revalidation drift"
                    ),
                ),
                self.assertRaisesRegex(
                    reconcile.ReconcilePostCommitUncertain,
                    "recognized reconcile terminal revalidation drift",
                ),
            ):
                reconcile.inspect_or_recover_reconciled_checkpoint(
                    self.root,
                    write=True,
                    added_authority=authority,
                )
        finally:
            authority.close()
            self.close_prepared(prepared)

    def test_recovery_rejects_numeric_near_miss_before_mutation(self) -> None:
        prepared = self.prepare_candidate()
        authority = self.capture_authority()
        try:
            near_miss = copy.deepcopy(prepared.projected)
            near_miss["working_tree_snapshot"][
                "managed_changed_path_count"
            ] = float(
                near_miss["working_tree_snapshot"][
                    "managed_changed_path_count"
                ]
            )
            near_miss_bytes = reconcile.json_bytes(near_miss)
            with (
                mock.patch.object(
                    reconcile.secure_writer,
                    "_capture_checkpoint_authority",
                    return_value=(near_miss_bytes, prepared.source_authority),
                ),
                mock.patch.object(
                    reconcile.secure_writer,
                    "_finalize_projected_publication",
                ) as finalizer,
                self.assertRaisesRegex(
                    reconcile.ReconcileError,
                    "byte-matches",
                ),
            ):
                reconcile.inspect_or_recover_reconciled_checkpoint(
                    self.root,
                    write=True,
                    added_authority=authority,
                )
            finalizer.assert_not_called()
        finally:
            authority.close()
            self.close_prepared(prepared)

    def test_atomic_postcommit_failure_is_classified_as_uncertain(self) -> None:
        prepared = self.prepare_candidate()
        try:
            with mock.patch.object(
                reconcile.secure_writer,
                "atomic_write_seq47",
                side_effect=reconcile.secure_writer.StartPostCommitUncertain(
                    "synthetic postcommit"
                ),
            ), mock.patch.object(
                reconcile.secure_writer,
                "_capture_checkpoint_authority",
                return_value=(
                    prepared.projected_bytes,
                    prepared.source_authority,
                ),
            ):
                with self.assertRaises(reconcile.ReconcilePostCommitUncertain):
                    reconcile.publish(prepared)
        finally:
            if prepared.cohort._pins:
                self.close_prepared(prepared)

    def test_managed_cohort_rejects_ancestor_identity_swap(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            managed = root / "managed/file.txt"
            managed.parent.mkdir()
            managed.write_bytes(b"same")
            with mock.patch.object(
                reconcile,
                "ADDED_MANAGED_PATHS",
                (),
            ), mock.patch.object(
                reconcile,
                "AUTHORIZED_EXISTING_DELTAS",
                (),
            ):
                cohort = reconcile.ReconcileCohort.capture(
                    root,
                    ["managed/file.txt"],
                )
            try:
                original = root / "managed-original"
                os.rename(managed.parent, original)
                managed.parent.mkdir()
                managed.write_bytes(b"same")
                with self.assertRaisesRegex(
                    reconcile.ReconcileError,
                    "managed ancestor authority changed",
                ):
                    cohort.verify()
            finally:
                cohort.close()

    def test_signed_external_hardlink_topology_is_retained_and_exact(self) -> None:
        required = sorted(
            set(reconcile.ADDED_MANAGED_PATHS)
            | set(reconcile.AUTHORIZED_EXISTING_DELTAS)
        )
        target_relative = "scripts/check_walksafe_project_continuation_v2_4.py"
        with tempfile.TemporaryDirectory() as repository_temporary, tempfile.TemporaryDirectory(
            prefix="fp008-external-aliases-"
        ) as alias_temporary:
            root = Path(repository_temporary).resolve()
            for relative in required:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(relative.encode("utf-8"))
            target = root / target_relative
            alias_root = Path(alias_temporary).resolve()
            alias_paths = (
                alias_root / "one/check.py",
                alias_root / "two/check.py",
            )
            for alias in alias_paths:
                alias.parent.mkdir(parents=True)
                os.link(target, alias)
            with mock.patch.object(reconcile, "ROOT", root), mock.patch.dict(
                reconcile.SIGNED_EXTERNAL_ALIAS_PATHS,
                {target_relative: tuple(str(path) for path in alias_paths)},
                clear=True,
            ):
                cohort = reconcile.ReconcileCohort.capture(root, required)
                try:
                    metadata = cohort.signed_file_metadata(target_relative)
                    self.assertEqual(metadata["nlink"], 3)
                    self.assertEqual(len(metadata["repository_inode_aliases"]), 1)
                    self.assertEqual(len(metadata["external_inode_aliases"]), 2)
                    os.unlink(alias_paths[0])
                    alias_paths[0].write_bytes(target.read_bytes())
                    with self.assertRaisesRegex(
                        reconcile.ReconcileError,
                        "external inode alias authority changed",
                    ):
                        cohort.verify()
                finally:
                    cohort.close()

    def test_sigkill_restart_recovers_inherited_atomic_stage_states(self) -> None:
        child = r'''from pathlib import Path
import os
import signal
import sys
from scripts import apply_walksafe_fp008_goal_started_seq47_20260803 as writer
root = Path(sys.argv[1]).resolve()
hook_name = sys.argv[2]
path = root / writer.CHECKPOINT
source, authority = writer._capture_checkpoint_authority(root)
def hook(label: str) -> None:
    if label == hook_name:
        os.kill(os.getpid(), signal.SIGKILL)
writer.atomic_write_seq47(
    path,
    b"projected",
    expected_source=source,
    expected_source_authority=authority,
    commit_guard=lambda: None,
    hook=hook,
)
'''
        for hook_name in ("stage_linked", "after_exchange"):
            with self.subTest(hook=hook_name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                checkpoint = root / reconcile.CHECKPOINT
                checkpoint.parent.mkdir(parents=True)
                checkpoint.write_bytes(b"source")
                os.chmod(checkpoint, 0o600)
                killed = subprocess.run(
                    [sys.executable, "-B", "-c", child, str(root), hook_name],
                    cwd=ROOT,
                    env={**os.environ, "PYTHONPATH": str(ROOT)},
                    check=False,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(killed.returncode, -signal.SIGKILL, killed.stderr)
                if hook_name == "stage_linked":
                    source, authority = (
                        reconcile.secure_writer._capture_checkpoint_authority(root)
                    )
                    reconcile.secure_writer._verify_source_recovery_state(
                        root,
                        source,
                        b"projected",
                        authority,
                    )
                    projected_authority = reconcile.secure_writer.atomic_write_seq47(
                        checkpoint,
                        b"projected",
                        expected_source=source,
                        expected_source_authority=authority,
                        commit_guard=lambda: None,
                    )
                else:
                    _, projected_authority = (
                        reconcile.secure_writer._capture_checkpoint_authority(
                            root,
                            b"projected",
                        )
                    )
                    reconcile.secure_writer._finalize_projected_publication(
                        root,
                        b"projected",
                        write=True,
                        expected_projected_authority=projected_authority,
                        expected_source_sha256=(
                            reconcile.sha256_bytes(b"source")
                        ),
                        expected_source_byte_count=len(b"source"),
                    )
                self.assertEqual(checkpoint.read_bytes(), b"projected")
                self.assertFalse(
                    (checkpoint.parent / reconcile.secure_writer.STAGE_NAME).exists()
                )
                reconcile.secure_writer._finalize_projected_publication(
                    root,
                    b"projected",
                    write=True,
                    expected_projected_authority=projected_authority,
                    expected_source_sha256=reconcile.sha256_bytes(b"source"),
                    expected_source_byte_count=len(b"source"),
                )
                self.assertEqual(checkpoint.read_bytes(), b"projected")

    def test_source_preflight_accepts_only_absent_or_exact_stage(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / reconcile.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(b"source")
            os.chmod(checkpoint, 0o600)
            source, authority = reconcile.secure_writer._capture_checkpoint_authority(
                root,
                b"source",
            )
            reconcile.secure_writer._verify_source_recovery_state(
                root,
                source,
                b"projected",
                authority,
            )
            stage = checkpoint.parent / reconcile.secure_writer.STAGE_NAME
            stage.write_bytes(b"foreign")
            os.chmod(stage, 0o600)
            with self.assertRaisesRegex(
                reconcile.secure_writer.StartApplyError,
                "checkpoint byte",
            ):
                reconcile.secure_writer._verify_source_recovery_state(
                    root,
                    source,
                    b"projected",
                    authority,
                )

    def test_source_preflight_rejoins_path_after_terminal_byte_check(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / reconcile.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(b"source")
            os.chmod(checkpoint, 0o600)
            source, authority = reconcile.secure_writer._capture_checkpoint_authority(
                root,
                b"source",
            )
            real_verify = reconcile.secure_writer._verify_open_file
            source_verifications = 0

            def swap_after_terminal_bytes(
                descriptor: int,
                identity: tuple[int, ...],
                content: bytes,
            ) -> None:
                nonlocal source_verifications
                real_verify(descriptor, identity, content)
                if content != source:
                    return
                source_verifications += 1
                if source_verifications == 2:
                    replacement = checkpoint.with_name("source-terminal-swap")
                    replacement.write_bytes(source)
                    os.chmod(replacement, 0o600)
                    os.replace(replacement, checkpoint)

            with mock.patch.object(
                reconcile.secure_writer,
                "_verify_open_file",
                side_effect=swap_after_terminal_bytes,
            ), self.assertRaisesRegex(
                reconcile.secure_writer.StartApplyError,
                "after terminal byte verification",
            ):
                reconcile.secure_writer._verify_source_recovery_state(
                    root,
                    source,
                    b"projected",
                    authority,
                )
            self.assertEqual(source_verifications, 2)

    def test_projected_read_only_recovery_is_durability_uncertain(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / reconcile.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(b"projected")
            os.chmod(checkpoint, 0o600)
            _, authority = reconcile.secure_writer._capture_checkpoint_authority(
                root,
                b"projected",
            )
            with self.assertRaisesRegex(
                reconcile.secure_writer.StartPostCommitUncertain,
                "explicit write recovery",
            ):
                reconcile.secure_writer._finalize_projected_publication(
                    root,
                    b"projected",
                    write=False,
                    expected_projected_authority=authority,
                    expected_source_sha256=reconcile.sha256_bytes(b"source"),
                    expected_source_byte_count=len(b"source"),
                )

    def test_recovery_terminal_guard_runs_before_source_stage_unlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / reconcile.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(b"projected")
            os.chmod(checkpoint, 0o600)
            stage = checkpoint.parent / reconcile.secure_writer.STAGE_NAME
            stage.write_bytes(b"source")
            os.chmod(stage, 0o600)
            _, authority = reconcile.secure_writer._capture_checkpoint_authority(
                root,
                b"projected",
            )

            def reject_terminal() -> None:
                raise reconcile.ReconcileError("synthetic terminal authority drift")

            with self.assertRaises(
                reconcile.secure_writer.StartPostCommitUncertain
            ):
                reconcile.secure_writer._finalize_projected_publication(
                    root,
                    b"projected",
                    write=True,
                    expected_projected_authority=authority,
                    expected_source_sha256=reconcile.sha256_bytes(b"source"),
                    expected_source_byte_count=len(b"source"),
                    terminal_guard=reject_terminal,
                )
            self.assertEqual(stage.read_bytes(), b"source")

    def test_recovery_rejoins_path_after_terminal_byte_check(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / reconcile.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(b"projected")
            os.chmod(checkpoint, 0o600)
            _, authority = reconcile.secure_writer._capture_checkpoint_authority(
                root,
                b"projected",
            )
            real_verify = reconcile.secure_writer._verify_open_file
            projected_verifications = 0

            def swap_after_terminal_bytes(
                descriptor: int,
                identity: tuple[int, ...],
                content: bytes,
            ) -> None:
                nonlocal projected_verifications
                real_verify(descriptor, identity, content)
                if content != b"projected":
                    return
                projected_verifications += 1
                if projected_verifications == 4:
                    replacement = checkpoint.with_name("projected-terminal-swap")
                    replacement.write_bytes(content)
                    os.chmod(replacement, 0o600)
                    os.replace(replacement, checkpoint)

            with mock.patch.object(
                reconcile.secure_writer,
                "_verify_open_file",
                side_effect=swap_after_terminal_bytes,
            ), self.assertRaises(
                reconcile.secure_writer.StartPostCommitUncertain
            ) as raised:
                reconcile.secure_writer._finalize_projected_publication(
                    root,
                    b"projected",
                    write=True,
                    expected_projected_authority=authority,
                    expected_source_sha256=reconcile.sha256_bytes(b"source"),
                    expected_source_byte_count=len(b"source"),
                )
            self.assertEqual(projected_verifications, 4)
            self.assertIn(
                "after terminal byte verification",
                str(raised.exception.__cause__),
            )

    def test_standalone_read_only_inspection_cannot_report_published(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / reconcile.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(reconcile.secure_writer.json_bytes({"candidate": True}))
            os.chmod(checkpoint, 0o600)
            with mock.patch.object(
                reconcile.secure_writer,
                "require_started_checkpoint",
            ), mock.patch.object(
                reconcile.secure_writer,
                "require_failed_attempt_is_fail_stop",
            ), self.assertRaisesRegex(
                reconcile.secure_writer.StartPostCommitUncertain,
                "explicit write recovery",
            ):
                reconcile.secure_writer.inspect_or_recover_started_checkpoint(
                    root,
                    write=False,
                )

    def test_recognized_started_checkpoint_failure_is_postcommit_uncertain(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / reconcile.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(reconcile.secure_writer.json_bytes({"candidate": True}))
            os.chmod(checkpoint, 0o600)
            with mock.patch.object(
                reconcile.secure_writer,
                "require_started_checkpoint",
            ), mock.patch.object(
                reconcile.secure_writer,
                "require_failed_attempt_is_fail_stop",
                side_effect=reconcile.secure_writer.StartApplyError(
                    "recognized candidate validation drift"
                ),
            ), self.assertRaisesRegex(
                reconcile.secure_writer.StartPostCommitUncertain,
                "recognized seq47 checkpoint",
            ):
                reconcile.secure_writer.inspect_or_recover_started_checkpoint(
                    root,
                    write=True,
                )

    def test_recognized_started_refresh_failure_is_postcommit_uncertain(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / reconcile.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            candidate_bytes = reconcile.secure_writer.json_bytes({"candidate": True})
            checkpoint.write_bytes(candidate_bytes)
            os.chmod(checkpoint, 0o600)
            _, authority = reconcile.secure_writer._capture_checkpoint_authority(
                root,
                candidate_bytes,
            )
            with mock.patch.object(
                reconcile.secure_writer,
                "_capture_checkpoint_authority",
                side_effect=[
                    (candidate_bytes, authority),
                    OSError("recognized checkpoint refresh failed"),
                ],
            ), mock.patch.object(
                reconcile.secure_writer,
                "require_started_checkpoint",
            ), mock.patch.object(
                reconcile.secure_writer,
                "require_failed_attempt_is_fail_stop",
            ), mock.patch.object(
                reconcile.secure_writer,
                "_finalize_projected_publication",
            ), self.assertRaisesRegex(
                reconcile.secure_writer.StartPostCommitUncertain,
                "recognized seq47 checkpoint",
            ):
                reconcile.secure_writer.inspect_or_recover_started_checkpoint(
                    root,
                    write=True,
                )

    def test_writer_rejects_same_byte_final_swap_after_parent_fsync(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / reconcile.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(b"source")
            os.chmod(checkpoint, 0o600)
            source, authority = reconcile.secure_writer._capture_checkpoint_authority(
                root,
                b"source",
            )

            def swap_final(label: str) -> None:
                if label != "after_parent_fsync":
                    return
                replacement = checkpoint.with_name("replacement")
                replacement.write_bytes(b"projected")
                os.chmod(replacement, 0o600)
                os.replace(replacement, checkpoint)

            with self.assertRaises(
                reconcile.secure_writer.StartPostCommitUncertain
            ):
                reconcile.secure_writer.atomic_write_seq47(
                    checkpoint,
                    b"projected",
                    expected_source=source,
                    expected_source_authority=authority,
                    commit_guard=lambda: None,
                    hook=swap_final,
                )

    def test_writer_rechecks_source_after_final_precommit_callback(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / reconcile.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(b"source")
            os.chmod(checkpoint, 0o600)
            source, authority = reconcile.secure_writer._capture_checkpoint_authority(
                root,
                b"source",
            )
            guard_calls = 0

            def swap_on_final_guard() -> None:
                nonlocal guard_calls
                guard_calls += 1
                if guard_calls != 2:
                    return
                replacement = checkpoint.with_name("source-replacement")
                replacement.write_bytes(source)
                os.chmod(replacement, 0o600)
                os.replace(replacement, checkpoint)

            with self.assertRaisesRegex(
                reconcile.secure_writer.StartApplyError,
                "source checkpoint changed at exchange boundary",
            ):
                reconcile.secure_writer.atomic_write_seq47(
                    checkpoint,
                    b"projected",
                    expected_source=source,
                    expected_source_authority=authority,
                    commit_guard=lambda: None,
                    precommit_guard=swap_on_final_guard,
                )
            self.assertEqual(guard_calls, 2)
            self.assertEqual(checkpoint.read_bytes(), source)

    def test_writer_rejoins_path_after_terminal_byte_check(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / reconcile.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(b"source")
            os.chmod(checkpoint, 0o600)
            source, authority = reconcile.secure_writer._capture_checkpoint_authority(
                root,
                b"source",
            )
            stage_path = checkpoint.parent / reconcile.secure_writer.STAGE_NAME
            real_verify = reconcile.secure_writer._verify_open_file
            swapped = False

            def swap_after_terminal_bytes(
                descriptor: int,
                identity: tuple[int, ...],
                content: bytes,
            ) -> None:
                nonlocal swapped
                real_verify(descriptor, identity, content)
                if (
                    swapped
                    or content != b"projected"
                    or stage_path.exists()
                    or checkpoint.read_bytes() != b"projected"
                ):
                    return
                replacement = checkpoint.with_name("published-terminal-swap")
                replacement.write_bytes(content)
                os.chmod(replacement, 0o600)
                os.replace(replacement, checkpoint)
                swapped = True

            with mock.patch.object(
                reconcile.secure_writer,
                "_verify_open_file",
                side_effect=swap_after_terminal_bytes,
            ), self.assertRaises(
                reconcile.secure_writer.StartPostCommitUncertain
            ) as raised:
                reconcile.secure_writer.atomic_write_seq47(
                    checkpoint,
                    b"projected",
                    expected_source=source,
                    expected_source_authority=authority,
                    commit_guard=lambda: None,
                )
            self.assertTrue(swapped)
            self.assertIn(
                "after terminal byte verification",
                str(raised.exception.__cause__),
            )

    def test_writer_rejects_relinked_source_inode_at_terminal_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            checkpoint = root / reconcile.CHECKPOINT
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_bytes(b"source")
            os.chmod(checkpoint, 0o600)
            source, authority = reconcile.secure_writer._capture_checkpoint_authority(
                root,
                b"source",
            )

            def relink_source(label: str) -> None:
                if label != "after_parent_fsync":
                    return
                parent_fd = os.open(
                    checkpoint.parent,
                    os.O_RDONLY | getattr(os, "O_DIRECTORY", 0),
                )
                try:
                    for entry in os.listdir("/proc/self/fd"):
                        try:
                            descriptor = int(entry)
                            metadata = os.fstat(descriptor)
                        except (OSError, ValueError):
                            continue
                        if metadata.st_nlink != 0 or metadata.st_size != len(source):
                            continue
                        try:
                            content = os.pread(descriptor, len(source), 0)
                        except OSError:
                            continue
                        if content == source:
                            reconcile.secure_writer._link_fd_at_empty(
                                descriptor,
                                parent_fd,
                                "relinked-source",
                            )
                            return
                    self.fail("retained unlinked source descriptor was not found")
                finally:
                    os.close(parent_fd)

            with self.assertRaisesRegex(
                reconcile.secure_writer.StartPostCommitUncertain,
                "committed",
            ):
                reconcile.secure_writer.atomic_write_seq47(
                    checkpoint,
                    b"projected",
                    expected_source=source,
                    expected_source_authority=authority,
                    commit_guard=lambda: None,
                    hook=relink_source,
                )


if __name__ == "__main__":
    unittest.main()
