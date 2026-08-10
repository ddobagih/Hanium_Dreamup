from __future__ import annotations

import base64
import copy
import gc
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
CANONICAL_NAME = "scripts.reconcile_walksafe_fp008_session_snapshot_seq47_20260808"
SCRIPT = (
    ROOT
    / "scripts"
    / "reconcile_walksafe_fp008_isolated_snapshot_fix_seq47b_20260809.py"
)


def run_cli(arguments: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["/usr/bin/python3.14", "-I", "-S", "-B", str(SCRIPT), *arguments],
        check=False,
        cwd="/",
        env={
            "LANG": "C.UTF-8",
            "LC_ALL": "C.UTF-8",
            "PATH": "/usr/bin:/bin",
            "PYTHONDONTWRITEBYTECODE": "1",
            "TZ": "UTC",
        },
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )

from scripts import reconcile_walksafe_fp008_session_snapshot_seq47_20260808 as canonical

CANONICAL_BOUNDARY_BEFORE = {
    name: copy.deepcopy(getattr(canonical, name))
    for name in (
        "ADDED_AUTHORITY_SCHEMA",
        "ADDED_AUTHORITY_OPERATION",
        "ADDED_AUTHORITY_SIGNATURE_DOMAIN",
        "REVIEWER_PUBLIC_KEY_SPKI_DER_BASE64",
        "REVIEWER_PUBLIC_KEY_SPKI_SHA256",
        "SOURCE_CHECKPOINT_SHA256",
        "SOURCE_CHECKPOINT_BYTE_COUNT",
        "SOURCE_CONTENT_SET_SHA256",
        "MANAGED_PATH_COUNT",
        "PATH_SET_SHA256",
        "AUTHORIZED_EXISTING_DELTAS",
        "ADDED_MANAGED_PATHS",
        "SIGNED_EXTERNAL_ALIAS_PATHS",
    )
}

from scripts import reconcile_walksafe_fp008_isolated_snapshot_fix_seq47b_20260809 as reconcile


class WalkSafeFp008IsolatedSnapshotFixSeq47bReconcileTest(unittest.TestCase):
    def test_exact_configuration_and_canonical_a_isolation(self) -> None:
        engine = reconcile.engine
        self.assertIsNot(engine, canonical)
        self.assertIs(sys.modules[CANONICAL_NAME], canonical)
        self.assertNotIn(engine.__name__, sys.modules)
        self.assertEqual(
            {
                name: getattr(canonical, name)
                for name in CANONICAL_BOUNDARY_BEFORE
            },
            CANONICAL_BOUNDARY_BEFORE,
        )
        self.assertEqual(
            engine.ADDED_AUTHORITY_SCHEMA,
            "walksafe.fp008-isolated-snapshot-fix-added-authority.v1",
        )
        self.assertEqual(
            engine.ADDED_AUTHORITY_OPERATION,
            "walksafe.fp008.seq47b-isolated-snapshot-fix-reconcile.v1",
        )
        self.assertEqual(
            engine.ADDED_AUTHORITY_SIGNATURE_DOMAIN,
            b"walksafe.fp008-isolated-snapshot-fix-added-authority.ed25519.v1",
        )
        self.assertEqual(
            (
                engine.SOURCE_CHECKPOINT_SHA256,
                engine.SOURCE_CHECKPOINT_BYTE_COUNT,
                engine.MANAGED_PATH_COUNT,
                engine.PATH_SET_SHA256,
                engine.SOURCE_CONTENT_SET_SHA256,
                engine.SOURCE_SEQUENCE,
                engine.SOURCE_EVENT_SHA256,
            ),
            (
                "3c6518ba09853987a0b76050e1727e932e7b6338826e2dfd4a44e03f4c65e2d4",
                1_531_420,
                627,
                "6b803fcf23e250bc28415c75dc8793ee22c5d1976d801f968cf55c9ea51627ae",
                "b2549167757da634dd2a688c566f54d742d7fcfd83d3b5cfd59e7e7bb49d8195",
                47,
                "82ad77e33eaa55530f53f5ee315807ef66e21fbfc8be2511b004abe99505db90",
            ),
        )
        self.assertEqual(
            engine.AUTHORIZED_EXISTING_DELTAS,
            {
                "scripts/run_walksafe_fp008_goal_start_gate_20260803.py": {
                    "source_sha256": (
                        "713c4214acd150a1d2a115a978f3771001f78481a2457a0237cc767ac2d77e42"
                    ),
                    "candidate_sha256": (
                        "8d48b7685e0d8809c2982007218189a019745b3f0d7e614dee78f08c44805430"
                    ),
                },
                "scripts/run_walksafe_test_layers_20260711.sh": {
                    "source_sha256": (
                        "78747667b148504a70b5a1b793249fb47f75abfa5cdadf02aaba7cbdd58412b9"
                    ),
                    "candidate_sha256": (
                        "8bed9597e078b78b6701413618b4fe48015cd8998a0ca2bdb451a031de9edc9e"
                    ),
                },
                "tests/test_walksafe_fp008_goal_start_gate_20260803.py": {
                    "source_sha256": (
                        "f3a7de1c6f8b8bc01691effc6bae922cc88555ba399d365d8ce9f051f181feeb"
                    ),
                    "candidate_sha256": (
                        "c84914444ae5bbe34ed794f5da48089b3f3332fa30fdcdf2b9e33c18d8fdcb75"
                    ),
                },
            },
        )
        self.assertEqual(
            engine.ADDED_MANAGED_PATHS,
            (
                "scripts/reconcile_walksafe_fp008_isolated_snapshot_fix_seq47b_20260809.py",
                "tests/test_walksafe_fp008_isolated_snapshot_fix_reconcile_seq47b_20260809.py",
            ),
        )
        self.assertEqual(
            engine.SIGNED_EXTERNAL_ALIAS_PATHS,
            CANONICAL_BOUNDARY_BEFORE["SIGNED_EXTERNAL_ALIAS_PATHS"],
        )
        public_der = base64.b64decode(
            engine.REVIEWER_PUBLIC_KEY_SPKI_DER_BASE64,
            validate=True,
        )
        self.assertEqual(len(public_der), 44)
        self.assertEqual(
            hashlib.sha256(public_der).hexdigest(),
            "d05359635aeb69ed3e7c975856f9f88ffd90cacb38fe1dbc6782d1f22042c89f",
        )
        self.assertEqual(engine.ED25519_SIGNATURE_BYTE_COUNT, 64)

    def test_in_process_engine_is_facts_only_and_never_loadable(self) -> None:
        with self.assertRaisesRegex(reconcile.EngineLoadError, "disabled"):
            reconcile._load_engine()
        for retired in (
            "_EngineProxy",
            "_FrozenFinder",
            "_FrozenLoader",
            "_FrozenSourceCapture",
            "_PrivateModuleGraph",
            "_ScriptsPackageLoader",
            "_archive_bytes",
            "_build_read_only_engine_facts",
            "_capture_frozen_sources",
            "_configure_engine",
            "_run_isolated_child",
            "main",
            "_ENGINE_CONFIGURATION",
        ):
            self.assertFalse(hasattr(reconcile, retired))
        with self.assertRaises(AttributeError):
            getattr(reconcile.engine, "_FUNCTIONS")
        self.assertFalse(hasattr(type(reconcile.engine), "_FUNCTIONS"))
        retired_types = {
            "_EngineProxy",
            "_FrozenFinder",
            "_FrozenLoader",
            "_FrozenSourceCapture",
            "_PrivateModuleGraph",
            "_ScriptsPackageLoader",
        }
        self.assertFalse(
            any(
                type(value) is type
                and value.__module__ == reconcile.__name__
                and value.__name__ in retired_types
                for value in gc.get_objects()
            )
        )
        self.assertTrue(
            reconcile.engine.__name__.startswith(reconcile.ENGINE_PRIVATE_MODULE_PREFIX)
        )
        self.assertNotIn(reconcile.engine.__name__, sys.modules)
        self.assertIs(sys.modules[CANONICAL_NAME], canonical)
        self.assertEqual(
            canonical.SOURCE_CHECKPOINT_SHA256,
            CANONICAL_BOUNDARY_BEFORE["SOURCE_CHECKPOINT_SHA256"],
        )
        for prohibited in (
            "AddedAuthorityManifestGuard",
            "ReconcileCohort",
            "_verify_reviewer_signature",
            "main",
            "pinned",
            "prepare",
            "publish",
            "secure_writer",
        ):
            with self.assertRaises(AttributeError):
                getattr(reconcile.engine, prohibited)
        with self.assertRaises(AttributeError):
            reconcile.engine.SOURCE_SEQUENCE = 0

    def test_exact_a_bytes_are_verified_before_private_module_creation(self) -> None:
        source = reconcile.ENGINE_PATH.read_bytes()
        self.assertEqual(len(source), reconcile.ENGINE_SOURCE_BYTE_COUNT)
        self.assertEqual(
            hashlib.sha256(source).hexdigest(),
            reconcile.ENGINE_SOURCE_SHA256,
        )
        with tempfile.TemporaryDirectory() as directory:
            changed = Path(directory) / reconcile.ENGINE_PATH.name
            changed.write_bytes(source[:-1] + bytes([source[-1] ^ 1]))
            directory_fd = os.open(
                directory,
                os.O_RDONLY | getattr(os, "O_DIRECTORY", 0),
            )
            with mock.patch.object(
                importlib.util,
                "module_from_spec",
                wraps=importlib.util.module_from_spec,
            ) as module_from_spec:
                try:
                    with self.assertRaisesRegex(
                        reconcile.EngineLoadError,
                        "SHA-256 differs",
                    ):
                        reconcile._read_retained_file(
                            directory_fd,
                            changed.name,
                            reconcile.ENGINE_SOURCE_SHA256,
                            reconcile.ENGINE_SOURCE_BYTE_COUNT,
                            require_single_link=True,
                        )
                    module_from_spec.assert_not_called()
                finally:
                    os.close(directory_fd)

    def test_ambient_repository_modules_are_not_reused_or_leaked(self) -> None:
        names = (
            "scripts.apply_walksafe_fp008_goal_seq45_46_20260803",
            "scripts.apply_walksafe_fp008_goal_started_seq47_20260803",
            "scripts.check_walksafe_project_continuation_v2_4",
        )
        originals = {name: sys.modules.get(name) for name in names}
        poisoned = {name: type(sys)(name) for name in names}
        try:
            sys.modules.update(poisoned)
            self.assertEqual(reconcile.engine.SOURCE_SEQUENCE, 47)
            for name in names:
                self.assertIs(sys.modules[name], poisoned[name])
        finally:
            for name, original in originals.items():
                if original is None:
                    sys.modules.pop(name, None)
                else:
                    sys.modules[name] = original

    def test_frozen_graph_memfd_is_fully_sealed(self) -> None:
        import fcntl
        content = b"walksafe-fp008-frozen-graph-seal-regression"
        descriptor = reconcile._sealed_memfd(content)
        try:
            self.assertEqual(
                fcntl.fcntl(descriptor, reconcile._F_GET_SEALS),
                reconcile._F_SEAL_WRITE
                | reconcile._F_SEAL_GROW
                | reconcile._F_SEAL_SHRINK
                | reconcile._F_SEAL_SEAL,
            )
            with self.assertRaises(OSError):
                os.pwrite(descriptor, b"x", 0)
        finally:
            os.close(descriptor)

    def test_actual_checkpoint_reverses_from_source_or_current_candidate(self) -> None:
        checkpoint_path = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"
        checkpoint_bytes = checkpoint_path.read_bytes()
        checkpoint_sha256 = hashlib.sha256(checkpoint_bytes).hexdigest()
        engine = reconcile.engine
        if checkpoint_sha256 == engine.SOURCE_CHECKPOINT_SHA256:
            source = json.loads(checkpoint_bytes)
            engine._require_exact_seq47(source)
            source_paths = source["working_tree_snapshot"]["managed_changed_paths"]
            candidate_paths = sorted(set(source_paths) | set(engine.ADDED_MANAGED_PATHS))
            digests = {
                relative: hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()
                for relative in candidate_paths
            }
            for relative, expected in engine.AUTHORIZED_EXISTING_DELTAS.items():
                self.assertEqual(digests[relative], expected["candidate_sha256"])
            reconstructed = {relative: digests[relative] for relative in source_paths}
            for relative, expected in engine.AUTHORIZED_EXISTING_DELTAS.items():
                reconstructed[relative] = expected["source_sha256"]
            self.assertEqual(
                engine._content_set_sha256_from_digests(source_paths, reconstructed),
                engine.SOURCE_CONTENT_SET_SHA256,
            )
            candidate = copy.deepcopy(source)
            path_hash = hashlib.sha256(
                ("\n".join(candidate_paths) + "\n").encode("utf-8")
            ).hexdigest()
            content_hash = engine._content_set_sha256_from_digests(
                candidate_paths,
                digests,
            )
            snapshot = candidate["working_tree_snapshot"]
            snapshot.update(
                managed_changed_paths=candidate_paths,
                managed_changed_path_count=len(candidate_paths),
                path_set_sha256=path_hash,
                content_set_sha256=content_hash,
            )
            handoff = candidate["session_handoff"]
            handoff["changed_files"] = copy.deepcopy(candidate_paths)
            handoff["source_commit_or_snapshot"].update(
                file_count=len(candidate_paths),
                path_set_sha256=path_hash,
                content_set_sha256=content_hash,
            )
        else:
            candidate = json.loads(checkpoint_bytes)
        reversed_source = engine._source_from_candidate(candidate)
        reversed_bytes = engine.json_bytes(reversed_source)
        if checkpoint_sha256 == engine.SOURCE_CHECKPOINT_SHA256:
            self.assertEqual(reversed_bytes, checkpoint_bytes)
        self.assertEqual(len(reversed_bytes), engine.SOURCE_CHECKPOINT_BYTE_COUNT)
        self.assertEqual(
            hashlib.sha256(reversed_bytes).hexdigest(),
            engine.SOURCE_CHECKPOINT_SHA256,
        )

    def test_cli_only_entry_and_missing_authority_fail_closed(self) -> None:
        self.assertFalse(hasattr(reconcile, "main"))
        completed = run_cli(["--preflight", "--root", str(ROOT)])
        self.assertEqual(completed.returncode, 1)
        self.assertIn("require all manifest", completed.stderr)

    def test_system_runtime_is_retained_and_child_environment_is_closed(self) -> None:
        authority = reconcile._SystemRuntimeAuthority.capture()
        try:
            authority.verify()
            self.assertEqual(
                os.fstat(authority.interpreter_fd).st_size,
                reconcile.SYSTEM_PYTHON_BYTE_COUNT,
            )
        finally:
            authority.close()
        completed = run_cli(["--help"])
        self.assertEqual(completed.returncode, 0, completed.stderr)

    def test_mutated_imported_configuration_cannot_affect_clean_cli(self) -> None:
        self.assertFalse(hasattr(reconcile, "_ENGINE_CONFIGURATION"))
        try:
            reconcile._ENGINE_CONFIGURATION = {"SOURCE_SEQUENCE": 999}
            self.assertEqual(reconcile.engine.SOURCE_SEQUENCE, 47)
            completed = run_cli(["--help"])
            self.assertEqual(completed.returncode, 0, completed.stderr)
        finally:
            del reconcile._ENGINE_CONFIGURATION

    def test_optional_import_miss_never_falls_through_to_repository(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            isolated_root = Path(directory)
            (isolated_root / "scripts").mkdir()
            for relative in reconcile.FROZEN_SCRIPT_SOURCES:
                source = ROOT / relative
                target = isolated_root / relative
                shutil.copyfile(source, target)
            marker = isolated_root / "shadow-imported"
            (isolated_root / "msvcrt.py").write_text(
                "from pathlib import Path\n"
                f"Path({str(marker)!r}).write_text('unsafe')\n",
                encoding="utf-8",
            )
            completed = run_cli(["--root", str(isolated_root), "--help"])
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertFalse(marker.exists())

    def test_outer_terminal_fault_preserves_postcommit_uncertain_rc2(self) -> None:
        self.assertEqual(
            reconcile._normalize_isolated_result(
                write_requested=True,
                child_started=True,
                result=0,
                terminal_error=reconcile.EngineLoadError("injected terminal drift"),
            ),
            2,
        )

    def test_write_abbreviation_is_rejected_and_signal_exit_becomes_rc2(self) -> None:
        for abbreviation in ("--w", "--wr", "--wri", "--writ"):
            completed = run_cli([abbreviation])
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("non-canonical CLI option", completed.stderr)
        self.assertEqual(
            reconcile._normalize_isolated_result(
                write_requested=True,
                child_started=True,
                result=-9,
                terminal_error=None,
            ),
            2,
        )


if __name__ == "__main__":
    unittest.main()
