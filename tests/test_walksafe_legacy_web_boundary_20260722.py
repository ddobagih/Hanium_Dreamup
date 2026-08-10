from __future__ import annotations

import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
CHECKER_PATH = ROOT / "scripts/check_walksafe_legacy_web_boundary_20260722.py"
SPEC = importlib.util.spec_from_file_location("walksafe_legacy_web_boundary", CHECKER_PATH)
assert SPEC is not None and SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class LegacyWebBoundaryTest(unittest.TestCase):
    def _copy_contract(self, destination: Path) -> None:
        for relative in CHECKER.REQUIRED_PATHS:
            source = ROOT / relative
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

    def test_current_repository_passes(self) -> None:
        CHECKER.check_boundary(ROOT)

    def test_workflow_cannot_restore_web_release_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            workflow = root / ".github/workflows/quality.yml"
            workflow.write_text(
                workflow.read_text(encoding="utf-8")
                + "\n# build_walksafe_web_release_20260711.sh\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "Web release artifact"):
                CHECKER.check_boundary(root)

    def test_remote_launcher_must_exit_before_tunnel_setup(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            launcher = root / "scripts/run_walksafe_remote_field_stack_20260711.sh"
            text = launcher.read_text(encoding="utf-8")
            launcher.write_text(
                text.replace("exit 78", "# exit guard removed", 1),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "exact fail-closed guard"):
                CHECKER.check_boundary(root)

    def test_commented_exit_cannot_satisfy_the_fail_closed_guard(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            launcher = root / "scripts/run_walksafe_remote_field_stack_20260711.sh"
            text = launcher.read_text(encoding="utf-8")
            launcher.write_text(
                text.replace("exit 78", "# exit 78", 1),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "exact fail-closed guard"):
                CHECKER.check_boundary(root)

    def test_blocked_launcher_has_no_side_effect(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            launcher = root / "scripts/run_walksafe_remote_field_stack_20260711.sh"
            launcher.parent.mkdir(parents=True)
            shutil.copy2(
                ROOT / "scripts/run_walksafe_remote_field_stack_20260711.sh",
                launcher,
            )
            completed = subprocess.run(
                [str(launcher)],
                cwd=root,
                env={"HOME": str(root / "home"), "PATH": "/usr/bin:/bin"},
                capture_output=True,
                text=True,
                check=False,
                timeout=5,
            )
            self.assertEqual(78, completed.returncode)
            self.assertIn("LEGACY_REFERENCE_ONLY", completed.stderr)
            self.assertFalse((root / "artifacts/cloudflare-field-test").exists())

    def test_all_historical_cli_paths_fail_closed_before_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            commands = (
                [str(ROOT / "scripts/build_walksafe_web_release_20260711.sh")],
                [str(ROOT / "scripts/run_cloudflare_field_test_services_20260711.sh")],
                [sys.executable, "-I", "-S", "-B", str(ROOT / "scripts/build_walksafe_full_rc_20260713.py")],
                [sys.executable, "-I", "-S", "-B", str(ROOT / "scripts/validate_walksafe_full_rc_20260713.py")],
                [
                    sys.executable,
                    "-I",
                    "-S",
                    "-B",
                    str(ROOT / "scripts/run_walksafe_product_quality_20260713.py"),
                    "--product",
                    "web",
                    "--receipt",
                    str(root / "web-quality.json"),
                ],
            )
            for command in commands:
                completed = subprocess.run(
                    command,
                    cwd=root,
                    env={"HOME": str(root / "home"), "PATH": "/usr/bin:/bin"},
                    capture_output=True,
                    text=True,
                    check=False,
                    timeout=5,
                )
                with self.subTest(command=command):
                    self.assertEqual(78, completed.returncode, completed.stderr)
                    self.assertIn("LEGACY_REFERENCE_ONLY", completed.stderr)
            self.assertEqual([], list(root.iterdir()))

    def test_deployment_stub_cannot_restore_active_directive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            unit = root / "deploy/systemd/walksafe-web.service"
            unit.write_text(
                unit.read_text(encoding="utf-8") + "\n[Service]\nExecStart=/usr/bin/true\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "active configuration"):
                CHECKER.check_boundary(root)

    def test_android_bff_allowlist_cannot_expand_to_legacy_api(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            policy = root / "apps/web/legacy-runtime-boundary.ts"
            policy.write_text(
                policy.read_text(encoding="utf-8").replace(
                    '  "/api/reports/v2"',
                    '  "/api/reports/v2",\n  "/api/admin-session"',
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "allowlist implementation is not exact"):
                CHECKER.check_boundary(root)

    def test_android_bff_allowlist_predicate_cannot_be_bypassed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            policy = root / "apps/web/legacy-runtime-boundary.ts"
            policy.write_text(
                policy.read_text(encoding="utf-8").replace(
                    "  return transitionalAndroidApiPaths.has(pathname);",
                    "  return true || transitionalAndroidApiPaths.has(pathname);",
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "allowlist implementation is not exact"):
                CHECKER.check_boundary(root)

    def test_runtime_proxy_must_keep_gone_response(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            proxy = root / "apps/web/proxy.ts"
            proxy.write_text(
                proxy.read_text(encoding="utf-8").replace("status: 410", "status: 200"),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "runtime request boundary"):
                CHECKER.check_boundary(root)

    def test_runtime_proxy_cannot_return_before_the_gone_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            proxy = root / "apps/web/proxy.ts"
            proxy.write_text(
                proxy.read_text(encoding="utf-8").replace(
                    "export function proxy(request: NextRequest): Response | undefined {",
                    "export function proxy(request: NextRequest): Response | undefined {\n  if (true) return undefined;",
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "runtime request boundary"):
                CHECKER.check_boundary(root)

    def test_package_loopback_comment_cannot_hide_a_public_bind(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            package = root / "apps/web/package.json"
            package.write_text(
                package.read_text(encoding="utf-8").replace(
                    "--hostname 127.0.0.1 --port 3000\",",
                    "--hostname 0.0.0.0 --port 3000 # 127.0.0.1\",",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "exact loopback contract"):
                CHECKER.check_boundary(root)

    def test_shell_guard_rejects_an_unlisted_command_before_exit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            launcher = root / "scripts/build_walksafe_web_release_20260711.sh"
            launcher.write_text(
                launcher.read_text(encoding="utf-8").replace(
                    "set -euo pipefail\n",
                    "set -euo pipefail\ntouch pre-exit-side-effect\n",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "exact fail-closed guard"):
                CHECKER.check_boundary(root)

    def test_product_boundary_cannot_claim_release_eligibility(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            boundary = root / "configs/walksafe_product_boundary_20260722.json"
            boundary.write_text(
                boundary.read_text(encoding="utf-8").replace(
                    '"release_eligibility": "NOT_ELIGIBLE"',
                    '"release_eligibility": "ELIGIBLE"',
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "release gates"):
                CHECKER.check_boundary(root)

    def test_release_evidence_rejects_repeated_profile_bypass(self) -> None:
        completed = subprocess.run(
            [
                sys.executable,
                "-I",
                "-S",
                "-B",
                str(ROOT / "scripts/check_walksafe_release_evidence_20260711.py"),
                "--profile",
                "android-research",
                "--profile",
                "web-release",
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
            timeout=5,
        )
        self.assertEqual(78, completed.returncode)
        self.assertIn("LEGACY_REFERENCE_ONLY", completed.stderr)

    def test_release_evidence_imported_main_guard_cannot_be_reordered(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            gate = root / "scripts/check_walksafe_release_evidence_20260711.py"
            gate.write_text(
                gate.read_text(encoding="utf-8").replace(
                    "    if args.profile in {\"web-release\", \"full\"}:",
                    "    args.profile = \"android-research\"\n    if args.profile in {\"web-release\", \"full\"}:",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "function prefix"):
                CHECKER.check_boundary(root)

    def test_product_quality_web_guard_cannot_be_reordered(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            runner = root / "scripts/run_walksafe_product_quality_20260713.py"
            runner.write_text(
                runner.read_text(encoding="utf-8").replace(
                    "    if args.product == \"web\":",
                    "    args.product = \"android\"\n    if args.product == \"web\":",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "function prefix"):
                CHECKER.check_boundary(root)

    def test_full_rc_guard_must_precede_other_top_level_work(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            builder = root / "scripts/build_walksafe_full_rc_20260713.py"
            builder.write_text(
                builder.read_text(encoding="utf-8").replace(
                    "import sys\n\nif __name__ == \"__main__\":",
                    "import sys\nprint('pre-guard work')\n\nif __name__ == \"__main__\":",
                    1,
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "pre-import exit 78 guard"):
                CHECKER.check_boundary(root)

    def test_official_next_runner_rejects_public_bind_override(self) -> None:
        runner = ROOT / "scripts/run_walksafe_web_single_instance_20260713.py"
        completed = subprocess.run(
            [
                sys.executable,
                "-B",
                str(runner),
                "--",
                "./node_modules/.bin/next",
                "start",
                "--hostname",
                "127.0.0.1",
                "--port",
                "3000",
                "--hostname",
                "0.0.0.0",
            ],
            cwd=ROOT / "apps/web",
            capture_output=True,
            text=True,
            check=False,
            timeout=5,
        )
        self.assertEqual(2, completed.returncode)
        self.assertIn("exact loopback", completed.stderr)

    def test_cli_reports_pass(self) -> None:
        completed = subprocess.run(
            [sys.executable, "-B", str(CHECKER_PATH), "--root", str(ROOT)],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("PASS", completed.stdout)


if __name__ == "__main__":
    unittest.main()
