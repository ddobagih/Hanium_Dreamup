#!/usr/bin/env python3
"""Small offline regression fixtures for S21 infrastructure; no product suites."""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

from check_android_apk_model_asset_20260713 import verify_apk
from check_android_device_results import verify_results
from check_walksafe_active_docs import _check_android_model_summary, FIXED_ACTIVE_DOCS
from summarize_walksafe_test_reports import summarize


ROOT = Path(__file__).resolve().parents[1]


class InfraTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    QUALITY_NODE_CHECKS = (
        ("web_lint", "Verify closed Legacy Web lint", "npm --prefix apps/web run lint"),
        ("web_typecheck", "Verify closed Legacy Web types", "npm --prefix apps/web run typecheck"),
        ("web_build", "Build closed Legacy Web", "npm --prefix apps/web run build"),
        ("gateway_typecheck", "Verify independent Android Gateway types", "npm --prefix apps/android-gateway run typecheck"),
        ("gateway_test", "Test independent Android Gateway", "npm --prefix apps/android-gateway test"),
    )
    QUALITY_NODE_CONDITION = "${{ !cancelled() && steps.node_dependencies.outcome == 'success' }}"

    @classmethod
    def quality_workflow(cls):
        if not hasattr(cls, "_quality_workflow_cache"):
            import yaml

            # BaseLoader keeps the GitHub Actions 'on' key and scalar values as strings.
            cls._quality_workflow_cache = yaml.load(
                (ROOT / ".github/workflows/quality.yml").read_text(),
                Loader=yaml.BaseLoader,
            )
        return copy.deepcopy(cls._quality_workflow_cache)

    def assert_quality_node_contract(self, workflow):
        job = workflow["jobs"]["acceptance"]
        steps = job["steps"]
        ids = [step["id"] for step in steps if "id" in step]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertNotIn("continue-on-error", job)
        for step in steps:
            self.assertNotIn("continue-on-error", step)
        setup_index = next(
            index for index, step in enumerate(steps)
            if step.get("name") == "Install Node regression dependencies"
        )
        setup = steps[setup_index]
        self.assertEqual(setup["id"], "node_dependencies")
        # Setup retains implicit success(), including the existing protective gates.
        self.assertNotIn("if", setup)
        checks = steps[setup_index + 1:setup_index + 6]
        self.assertEqual(len(checks), len(self.QUALITY_NODE_CHECKS))
        for step, (step_id, name, command) in zip(checks, self.QUALITY_NODE_CHECKS):
            self.assertEqual(step["id"], step_id)
            self.assertEqual(step["name"], name)
            self.assertEqual(step["if"], self.QUALITY_NODE_CONDITION)
            self.assertEqual(step["shell"], "bash")
            self.assertEqual(step["run"].splitlines(), ["set -euo pipefail", command])
        self.assertNotIn(
            "Verify closed Legacy Web and independent Android Gateway",
            [step.get("name") for step in steps],
        )
        return setup, checks

    def quality_node_is_runnable(self, step, outcomes, cancelled):
        # A local model of this documented condition, not a GitHub runner emulator.
        match = re.fullmatch(
            r"\$\{\{\s*!cancelled\(\)\s*&&\s*steps\.([A-Za-z_][A-Za-z0-9_]*)"
            r"\.outcome\s*==\s*'success'\s*\}\}",
            step["if"],
        )
        self.assertIsNotNone(match, step["if"])
        return not cancelled and outcomes.get(match.group(1)) == "success"

    def test_quality_node_contract_is_explicit(self):
        self.assert_quality_node_contract(self.quality_workflow())

    def test_quality_node_failures_do_not_suppress_other_checks(self):
        _, checks = self.assert_quality_node_contract(self.quality_workflow())
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        npm = bin_dir / "npm"
        npm.write_text(
            f"#!{sys.executable}\n"
            "import os, sys\n"
            "args = ' '.join(sys.argv[1:])\n"
            "with open(os.environ['S21_NPM_LOG'], 'a') as stream:\n"
            "    stream.write(args + '\\n')\n"
            "sys.exit(7 if args == os.environ['S21_FAIL_NPM_ARGS'] else 0)\n"
        )
        npm.chmod(0o755)
        commands = {step_id: command for step_id, _, command in self.QUALITY_NODE_CHECKS}
        expected_args = [command.removeprefix("npm ") for command in commands.values()]
        for failed_id in (None, *commands):
            with self.subTest(failed_id=failed_id):
                log = self.root / f"{failed_id or 'success'}.log"
                outcomes = {"node_dependencies": "success"}
                returncodes = {}
                environment = {
                    "PATH": f"{bin_dir}:/usr/bin:/bin",
                    "S21_NPM_LOG": str(log),
                    "S21_FAIL_NPM_ARGS": commands[failed_id].removeprefix("npm ") if failed_id else "",
                }
                for step in checks:
                    if self.quality_node_is_runnable(step, outcomes, cancelled=False):
                        result = subprocess.run(
                            ["/bin/bash", "--noprofile", "--norc", "-c", step["run"]],
                            cwd=self.root, env=environment, capture_output=True, text=True, timeout=5,
                        )
                        returncodes[step["id"]] = result.returncode
                        outcomes[step["id"]] = "success" if result.returncode == 0 else "failure"
                self.assertEqual(log.read_text().splitlines(), expected_args)
                self.assertEqual(returncodes, {
                    step_id: 7 if step_id == failed_id else 0 for step_id in commands
                })
                self.assertEqual(any(code != 0 for code in returncodes.values()), failed_id is not None)

    def test_quality_node_setup_failure_and_cancellation_block_checks(self):
        _, checks = self.assert_quality_node_contract(self.quality_workflow())
        for setup_outcome in ("success", "failure", "cancelled", "skipped", None):
            for cancelled in (False, True):
                with self.subTest(setup_outcome=setup_outcome, cancelled=cancelled):
                    outcomes = {} if setup_outcome is None else {"node_dependencies": setup_outcome}
                    selected = [
                        step["id"] for step in checks
                        if self.quality_node_is_runnable(step, outcomes, cancelled)
                    ]
                    expected = [step_id for step_id, _, _ in self.QUALITY_NODE_CHECKS]
                    self.assertEqual(selected, expected if setup_outcome == "success" and not cancelled else [])

    def test_quality_rejects_implicit_success_or_failure_masking(self):
        mutations = (
            ("check", "if", "${{ steps.node_dependencies.outcome == 'success' }}"),
            ("check", "if", "${{ success() && steps.node_dependencies.outcome == 'success' }}"),
            ("check", "if", "${{ !cancelled() }}"),
            ("check", "if", "${{ !cancelled() && steps.other.outcome == 'success' }}"),
            ("check", "if", "${{ !cancelled() && steps.node_dependencies.conclusion == 'success' }}"),
            ("check", "continue-on-error", "true"),
            ("check", "run", "set -euo pipefail\nnpm --prefix apps/web run lint || true\n"),
            ("setup", "if", "always()"),
            ("job", "continue-on-error", "true"),
        )
        for target, key, value in mutations:
            with self.subTest(target=target, key=key, value=value):
                workflow = self.quality_workflow()
                setup, checks = self.assert_quality_node_contract(workflow)
                item = {"setup": setup, "check": checks[0], "job": workflow["jobs"]["acceptance"]}[target]
                item[key] = value
                with self.assertRaises(AssertionError):
                    self.assert_quality_node_contract(workflow)

    def test_quality_preserves_artifact_gates(self):
        steps = self.quality_workflow()["jobs"]["acceptance"]["steps"]
        uploads = [step for step in steps if step.get("uses", "").startswith("actions/upload-artifact@")]
        self.assertEqual(len(uploads), 3)
        for step in uploads:
            self.assertEqual(step["uses"], "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02")
        actual = [
            (step["if"], step["with"]["name"], step["with"]["path"].splitlines(),
             step["with"]["if-no-files-found"])
            for step in uploads
        ]
        self.assertEqual(actual, [
            ("always()", "walksafe-validation-reports-${{ github.sha }}", [
                "apps/android/app/build/reports/",
                "apps/android/app/build/test-results/",
                "apps/android/adminapp/build/reports/",
                "apps/android/adminapp/build/test-results/",
                "artifacts/test-results/",
            ], "warn"),
            ("success()", "walksafe-quality-${{ github.sha }}", [
                "apps/android/app/build/outputs/apk/",
            ], "error"),
            ("success()", "walksafe-admin-quality-${{ github.sha }}", [
                "apps/android/adminapp/build/outputs/apk/",
            ], "error"),
        ])

    @classmethod
    def locked_node_fixture_source(cls):
        if not hasattr(cls, "_locked_node_fixture_cache"):
            source = (ROOT / "scripts/run_walksafe_test_layers_current.sh").read_text()
            definitions = []
            for name in (
                "attest_locked_node", "activate_locked_node_path",
                "run_locked_npm_in", "run_locked_gateway_npm",
            ):
                matches = re.findall(
                    r"^" + name + r"\(\) \{\n.*?^\}", source, re.MULTILINE | re.DOTALL,
                )
                if len(matches) != 1:
                    raise AssertionError(f"Expected one runner function: {name}")
                definitions.append(matches[0])
            caller = (
                '  : > "${REPORT_DIR}/node-gateway.started"\n'
                '  gateway_status=0\n'
                '  run_locked_gateway_npm test || gateway_status=$?\n'
                "  printf '%s\\n' \"${gateway_status}\" > \"${REPORT_DIR}/node-gateway.exit\"\n"
                '  if (( gateway_status != 0 )); then exit "${gateway_status}"; fi\n'
            )
            if source.count(caller) != 1:
                raise AssertionError("Expected the existing Gateway reporting/OR-list caller")
            start = source.index(caller)
            cls._locked_node_fixture_cache = (
                "\n\n".join(definitions), source[start:start + len(caller)],
            )
        return cls._locked_node_fixture_cache

    def run_locked_node_guard_fixture(
        self, name, *, attest_statuses=(0, 0, 0), npm_status=0,
        expected_status=0, expected_events=None, fault="",
        missing_node=False, missing_package=False,
    ):
        functions, caller = self.locked_node_fixture_source()
        fixture = self.root / name
        node_bin = fixture / "node/bin"
        node_bin.mkdir(parents=True)
        repo = fixture / "repo"
        repo.mkdir()
        if not missing_package:
            (repo / "apps/android-gateway").mkdir(parents=True)
        reports = fixture / "reports"
        reports.mkdir()
        private_home = fixture / "home"
        private_home.mkdir()
        trace = fixture / "trace.log"
        counter = fixture / "attest-count"
        interpreter = fixture / "attestation-stub"
        prefix = f"#!{sys.executable}\nfrom pathlib import Path\n"
        interpreter.write_text(
            prefix
            + f"trace = Path({str(trace)!r})\ncounter = Path({str(counter)!r})\n"
            + "call = int(counter.read_text()) + 1 if counter.exists() else 1\n"
            + "counter.write_text(str(call))\n"
            + "with trace.open('a') as stream:\n"
            + "    stream.write(f'attest:{call}\\n')\n"
            + f"statuses = {list(attest_statuses)!r}\n"
            + "raise SystemExit(statuses[call - 1] if call <= len(statuses) else 97)\n"
        )
        interpreter.chmod(0o755)
        npm = node_bin / "npm"
        npm.write_text(
            prefix
            + "import sys\n"
            + f"with Path({str(trace)!r}).open('a') as stream:\n"
            + "    stream.write('npm:' + ' '.join(sys.argv[1:]) + '\\n')\n"
            + f"raise SystemExit({npm_status})\n"
        )
        npm.chmod(0o755)
        environment = {
            "PATH": "/usr/bin:/bin",
            "PYTHONDONTWRITEBYTECODE": "1",
            "PYTHON_BIN": str(interpreter),
            "NODE_TOOLCHAIN_CHECKER": str(fixture / "never-executed-checker.py"),
            "NODE_TOOLCHAIN_LOCK": str(fixture / "never-read-lock.json"),
            "LOCKED_NODE_BIN_DIR": "",
            "LOCKED_NODE_ROOT": "",
            "LOCKED_NODE_PRIVATE_HOME": str(private_home),
            "WALKSAFE_NODE_BIN_DIR": str(fixture / "missing-node" if missing_node else node_bin),
            "S21_NODE_ROOT": str(node_bin.parent),
            "REPO_ROOT": str(repo),
            "REPORT_DIR": str(reports),
        }
        # Valid-looking output plus failure forces guards to honor command status.
        overrides = {
            "": "",
            "cd": r"""cd() { if [[ "$1" == "${WALKSAFE_NODE_BIN_DIR}" ]]; then printf '%s\n' "${WALKSAFE_NODE_BIN_DIR}"; return 31; fi; builtin cd "$@"; }""",
            "pwd": r"""pwd() { printf '%s\n' "${WALKSAFE_NODE_BIN_DIR}"; return 32; }""",
            "dirname": r"""dirname() { printf '%s\n' "${S21_NODE_ROOT}"; return 33; }""",
        }
        result = subprocess.run(
            ["/bin/bash", "--noprofile", "--norc"],
            input="set -euo pipefail\n" + functions + "\n" + overrides[fault] + "\n" + caller,
            cwd=fixture, env=environment, capture_output=True, text=True, timeout=5,
        )
        self.assertEqual(result.returncode, expected_status, result.stdout + result.stderr)
        self.assertTrue((reports / "node-gateway.started").is_file())
        self.assertEqual((reports / "node-gateway.exit").read_text(), f"{expected_status}\n")
        events = trace.read_text().splitlines() if trace.exists() else []
        if expected_events is None:
            expected_events = ("attest:1", "attest:2", "npm:test", "attest:3")
        self.assertEqual(events, list(expected_events), result.stdout + result.stderr)

    def test_locked_node_activation_failures_stop_before_npm(self):
        cases = (
            ("missing-node", "", (), 2, ()),
            ("activation-cd", "cd", (), 31, ()),
            ("activation-pwd", "pwd", (), 32, ()),
            ("activation-root", "dirname", (), 33, ()),
            ("activation-attestation", "", (11,), 11, ("attest:1",)),
        )
        for name, fault, statuses, code, events in cases:
            with self.subTest(stage=name):
                self.run_locked_node_guard_fixture(
                    name, fault=fault, attest_statuses=statuses, expected_status=code,
                    expected_events=events, missing_node=name == "missing-node",
                )

    def test_locked_node_pre_attestation_failure_stops_before_npm(self):
        self.run_locked_node_guard_fixture(
            "pre-attestation", attest_statuses=(0, 13), expected_status=13,
            expected_events=("attest:1", "attest:2"),
        )

    def test_locked_node_post_attestation_and_npm_exit_priority(self):
        cases = (
            ("success", (0, 0, 0), 0, 0, False),
            ("post-attestation", (0, 0, 17), 0, 17, False),
            ("npm-failure", (0, 0, 0), 7, 7, False),
            ("both-fail", (0, 0, 17), 7, 7, False),
            ("missing-package", (0, 0, 0), 0, 1, True),
        )
        for name, statuses, npm_status, code, missing_package in cases:
            with self.subTest(stage=name):
                self.run_locked_node_guard_fixture(
                    name, attest_statuses=statuses, npm_status=npm_status, expected_status=code,
                    missing_package=missing_package,
                    expected_events=("attest:1", "attest:2", "attest:3") if missing_package else None,
                )

    def make_apk(self, digest="valid", payload=b"model"):
        model = {"asset": "models/current.tflite"}
        if digest != "missing":
            model["artifact_sha256"] = hashlib.sha256(b"model").hexdigest() if digest == "valid" else digest
        config = {"primary_model": "current", "models": {"current": model}}
        config_path = self.root / "config.json"
        config_path.write_text(json.dumps(config))
        apk = self.root / "fixture.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("assets/model-config/two_model_runtime.json", config_path.read_bytes())
            archive.writestr("assets/models/current.tflite", payload)
        return apk, config_path

    def test_apk_hash_positive(self):
        self.assertEqual(verify_apk(*self.make_apk())[0], 1)

    def test_apk_missing_wrong_hash_and_changed_byte(self):
        for digest, payload in [("missing", b"model"), ("0" * 64, b"model"), ("valid", b"modeL"), ("bad", b"model")]:
            with self.subTest(digest=digest, payload=payload), self.assertRaises(ValueError):
                verify_apk(*self.make_apk(digest, payload))

    def make_apk_with_companion(self, companion_payload=b"sam", declared=b"sam", packaged=True):
        apk, config_path = self.make_apk()
        contract = {"asset": "models/companion.tflite", "sha256": hashlib.sha256(declared).hexdigest()}
        contract_path = self.root / "companion_contract.json"
        contract_path.write_text(json.dumps(contract))
        with zipfile.ZipFile(apk, "a") as archive:
            archive.writestr("assets/model-config/companion_contract.json", contract_path.read_bytes())
            if packaged:
                archive.writestr("assets/models/companion.tflite", companion_payload)
        return apk, config_path, contract_path

    def test_apk_companion_model_contract_is_hash_checked(self):
        apk, config_path, contract_path = self.make_apk_with_companion()
        # Without its contract a packaged companion model stays an unexpected payload.
        with self.assertRaisesRegex(ValueError, "unexpected=.*companion"):
            verify_apk(apk, config_path)
        self.assertEqual(verify_apk(apk, config_path, (contract_path,))[0], 2)
        for kwargs in ({"companion_payload": b"saM"}, {"packaged": False}):
            with self.subTest(**kwargs), self.assertRaises(ValueError):
                apk, config_path, contract_path = self.make_apk_with_companion(**kwargs)
                verify_apk(apk, config_path, (contract_path,))

    def tflite_contract_run(self, mutate_config=None, class_map_text=None):
        import argparse
        from unittest import mock
        import check_android_tflite_contract_20260531 as contract

        config = json.loads(contract.ANDROID_CONFIG.read_text(encoding="utf-8"))
        if mutate_config:
            mutate_config(config["models"]["unified_walksafe"])
        config_path = self.root / "two_model_runtime.json"
        config_path.write_text(json.dumps(config))
        class_map = contract.ANDROID_CLASS_MAP
        if class_map_text is not None:
            class_map = self.root / "TwoModelClassMap.kt"
            class_map.write_text(class_map_text, encoding="utf-8")
        args = argparse.Namespace(android_config=config_path, backend_config=None, strict_backend_config=False)
        with mock.patch.object(contract, "ANDROID_CLASS_MAP", class_map):
            return contract.run_check(args)

    def test_tflite_static_contract_accepts_current_tree_without_function_name_markers(self):
        result = self.tflite_contract_run()
        self.assertTrue(result.ok, result.errors)

    def test_tflite_static_contract_rejects_deleted_class(self):
        def delete_last(model):
            model["classes"].pop()
            model["allowlist"].pop()
        errors = self.tflite_contract_run(delete_last).errors
        self.assertTrue(any("classes mismatch" in error for error in errors), errors)

    def test_tflite_static_contract_checks_class_order_against_artifact_metadata(self):
        import check_android_tflite_contract_20260531 as contract

        first, second = "linear_tactile_paving", "damaged_linear_tactile_paving"
        source = contract.ANDROID_CLASS_MAP.read_text(encoding="utf-8")
        start = source.index("unifiedWalksafeClasses")
        end = source.index(")", start)
        block = source[start:end].replace(f'"{first}"', '"__first__"').replace(f'"{second}"', f'"{first}"')
        swapped_source = source[:start] + block.replace('"__first__"', f'"{second}"') + source[end:]

        def swap(model):
            for key in ("classes", "allowlist"):
                values = model[key]
                a, b = values.index(first), values.index(second)
                values[a], values[b] = values[b], values[a]
        # Kotlin and JSON agree with each other, but not with the exported model's own names.
        errors = self.tflite_contract_run(swap, swapped_source).errors
        self.assertTrue(any("artifact metadata class order" in error for error in errors), errors)

    def tflite_threshold_run(self, *, mutate_android=None, mutate_allowlist=None, strict=False, allowlist=True):
        # S21-19: Android/backend threshold differences are errors unless declared.
        import argparse
        import check_android_tflite_contract_20260531 as contract

        config = json.loads(contract.ANDROID_CONFIG.read_text(encoding="utf-8"))
        if mutate_android:
            mutate_android(config["models"]["unified_walksafe"]["thresholds"])
        config_path = self.root / "two_model_runtime.json"
        config_path.write_text(json.dumps(config))
        allowlist_path = self.root / "threshold-allowlist.json"
        allowlist_path.unlink(missing_ok=True)
        if allowlist:
            declared = json.loads(contract.THRESHOLD_ALLOWLIST.read_text(encoding="utf-8"))
            if mutate_allowlist:
                mutate_allowlist(declared)
            allowlist_path.write_text(json.dumps(declared))
        args = argparse.Namespace(
            android_config=config_path, backend_config=contract.BACKEND_CONFIG,
            strict_backend_config=strict, threshold_allowlist=allowlist_path,
        )
        return contract.run_check(args)

    def test_tflite_declared_threshold_differences_pass_and_are_reported(self):
        result = self.tflite_threshold_run()
        self.assertTrue(result.ok, result.errors)
        self.assertEqual(len(result.backend_threshold_differences), 21)
        self.assertEqual(result.unapproved_threshold_differences, [])
        self.assertEqual(result.stale_threshold_allowlist_entries, [])

    def test_tflite_threshold_change_outside_allowlist_fails(self):
        def change(thresholds):
            thresholds["person"] = 0.35
        errors = self.tflite_threshold_run(mutate_android=change).errors
        self.assertTrue(any("unapproved Android/backend threshold difference: unified_walksafe/person" in e for e in errors), errors)
        self.assertTrue(any("stale threshold allowlist entry: unified_walksafe/person" in e for e in errors), errors)

    def test_tflite_threshold_aligned_class_leaves_stale_allowlist_entry(self):
        def align(thresholds):
            thresholds["crosswalk"] = 0.3
        result = self.tflite_threshold_run(mutate_android=align)
        self.assertEqual(result.unapproved_threshold_differences, [])
        self.assertTrue(any("stale threshold allowlist entry: unified_walksafe/crosswalk" in e for e in result.errors), result.errors)

    def test_tflite_threshold_allowlist_rejects_malformed_or_missing_declarations(self):
        def drop_reason(declared):
            declared["entries"][0]["reason"] = ""
        self.assertTrue(any("threshold allowlist entry" in e for e in self.tflite_threshold_run(mutate_allowlist=drop_reason).errors))
        def duplicate(declared):
            declared["entries"].append(dict(declared["entries"][0]))
        self.assertTrue(any("duplicate" in e for e in self.tflite_threshold_run(mutate_allowlist=duplicate).errors))
        missing = self.tflite_threshold_run(allowlist=False).errors
        self.assertTrue(any("threshold allowlist not found" in e for e in missing), missing)
        self.assertTrue(any("unapproved Android/backend threshold difference" in e for e in missing), missing)

    def test_tflite_strict_backend_config_ignores_allowlist(self):
        errors = self.tflite_threshold_run(strict=True).errors
        self.assertTrue(any("thresholds differ from the active backend runtime config" in e for e in errors), errors)

    def test_tflite_static_contract_derives_anchor_count_from_input_size(self):
        def drift(model):
            model["export"]["output_shape"] = [1, 25, 12095]
        errors = self.tflite_contract_run(drift).errors
        self.assertTrue(any("anchors" in error for error in errors), errors)

    def write_xml(self, names=("first", "second"), child="", extra_attributes=""):
        folder = self.root / "nested"
        folder.mkdir(exist_ok=True)
        (folder / "TEST-result.xml").write_text(
            f'<testsuites><testsuite errors="0"\n {extra_attributes} name="approved" tests="{len(names)}">'
            + "".join(f'<testcase classname="approved" name="{name}">{child}</testcase>' for name in names)
            + '</testsuite></testsuites>'
        )

    def test_device_xml_format_nested_and_extended_approval(self):
        self.write_xml()
        self.assertEqual(verify_results(self.root, "approved", ["first", "second"]), 2)
        self.write_xml(("first", "second", "third"))
        self.assertEqual(verify_results(self.root, "approved", ["first", "second", "third"]), 3)

    def test_device_xml_missing_duplicate_failed_skipped_and_malformed(self):
        for names, child, attributes in [(("first",), "", ""), (("first", "first"), "", ""), (("first", "second"), "<failure/>", ""), (("first", "second"), "<skipped/>", ""), (("first", "second"), "", 'failures="1"')]:
            with self.subTest(names=names, child=child, attributes=attributes):
                self.write_xml(names, child, attributes)
                with self.assertRaises(ValueError):
                    verify_results(self.root, "approved", ["first", "second"])
        (self.root / "nested/TEST-result.xml").write_text("<invalid")
        with self.assertRaises(ET.ParseError):
            verify_results(self.root, "approved", ["first", "second"])

    def test_device_xml_suite_claiming_tests_without_testcases_is_rejected(self):
        self.write_xml()
        (self.root / "nested/TEST-other.xml").write_text(
            '<testsuite name="unapproved" tests="3" failures="0" errors="0" skipped="0"></testsuite>'
        )
        with self.assertRaisesRegex(ValueError, "count mismatch"):
            verify_results(self.root, "approved", ["first", "second"])

    def test_report_status_does_not_invent_execution(self):
        (self.root / "scope.tsv").write_text("ok\tselected\ta.py\nfailed\tselected\tb.py\nmissing\tselected\tc.py\nold\texcluded\td.py\n")
        for group, code in [("ok", 0), ("failed", 1)]:
            (self.root / f"{group}.started").touch()
            (self.root / f"{group}.exit").write_text(str(code))
            (self.root / f"{group}.xml").write_text('<testsuite><testcase name="one"/></testsuite>')
        (self.root / "missing.xml").write_text('<testsuite><testcase name="stale"/></testsuite>')
        self.assertEqual([row["status"] for row in summarize(self.root)], ["PASS", "FAIL", "NOT_RUN", "EXCLUDED"])

    def test_report_all_skipped_is_not_pass_and_failures_keep_precedence(self):
        (self.root / "scope.tsv").write_text("group\tselected\tcase.py\n")
        (self.root / "group.started").touch()
        cases = [
            ('<testcase><skipped/></testcase>', 0, "SKIPPED"),
            ('<testcase><skipped/></testcase><testcase/>', 0, "PASS"),
            ('<testcase><skipped/></testcase><testcase><failure/></testcase>', 0, "FAIL"),
            ('<testcase><skipped/><error/></testcase>', 0, "FAIL"),
            ('<testcase><skipped/></testcase>', 1, "FAIL"),
            ('', 0, "FAIL"),
        ]
        for body, exit_code, expected in cases:
            with self.subTest(body=body, exit_code=exit_code):
                (self.root / "group.exit").write_text(str(exit_code))
                (self.root / "group.xml").write_text(f"<testsuite>{body}</testsuite>")
                row = summarize(self.root)[0]
                self.assertEqual(row["status"], expected)
                self.assertEqual(row["skipped"], body.count("<skipped/>"))

    def test_skipped_report_without_exit_stays_incomplete(self):
        (self.root / "scope.tsv").write_text("group\tselected\tcase.py\n")
        (self.root / "group.started").touch()
        (self.root / "group.xml").write_text('<testsuite><testcase><skipped/></testcase></testsuite>')
        self.assertEqual("INCOMPLETE", summarize(self.root)[0]["status"])

    def test_node_recursive_discovery_and_junit(self):
        self.run_node_fixture("import {test} from 'node:test'; test('nested test', () => {});", expected=0)
        inventory = json.loads((self.root / "reports/node-gateway-inventory.json").read_text())
        self.assertEqual(len(inventory["files"]), 1)
        self.assertIn("deep", inventory["files"][0])
        self.assertTrue(list(ET.parse(self.root / "reports/node-gateway.xml").iter("testcase")))

    def test_node_all_skipped_keeps_json_and_markdown_status(self):
        self.run_node_fixture("import {test} from 'node:test'; test('unavailable', {skip: true}, () => {});", expected=0)
        reports = self.root / "reports"
        (reports / "scope.tsv").write_text("node-gateway\tselected\tnested.test.js\n")
        result = subprocess.run([sys.executable, "-B", str(ROOT / "scripts/summarize_walksafe_test_reports.py"),
                                 "--report-dir", str(reports), "--exit-code", "0"],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        row = json.loads((reports / "summary.json").read_text())["groups"][0]
        self.assertEqual("SKIPPED", row["status"])
        self.assertEqual(row["tests"], row["skipped"])
        self.assertIn("| node-gateway | SKIPPED |", (reports / "summary.md").read_text())

    def test_node_timeout_is_failure_with_report(self):
        self.run_node_fixture("import {test} from 'node:test'; test('never ends', async () => { await new Promise(() => { setInterval(() => {}, 1000); }); });", expected=1)
        self.assertIn("failure", (self.root / "reports/node-gateway.xml").read_text())

    def run_node_fixture(self, source, expected):
        folder = self.root / "tests/deep"
        folder.mkdir(parents=True)
        (self.root / "tests/package.json").write_text('{"type":"module"}')
        (folder / "nested.test.js").write_text(source)
        environment = {**os.environ, "WALKSAFE_NODE_TEST_TIMEOUT_MS": "200", "WALKSAFE_TEST_REPORT_DIR": str(self.root / "reports")}
        result = subprocess.run(["node", str(ROOT / "scripts/run_walksafe_node_tests.mjs"), str(self.root / "tests")], env=environment, cwd=self.root, capture_output=True, text=True, timeout=8)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)

    def test_readme_contract_and_drift(self):
        self.assertIn(Path("apps/android/README.md"), FIXED_ACTIVE_DOCS)
        self.assertEqual(_check_android_model_summary(ROOT), [])
        config_path = self.root / "apps/android/app/src/main/assets/model-config/two_model_runtime.json"
        config_path.parent.mkdir(parents=True)
        config = json.loads((ROOT / config_path.relative_to(self.root)).read_text())
        config_path.write_text(json.dumps(config))
        doc = self.root / "apps/android/README.md"
        doc.write_text((ROOT / "apps/android/README.md").read_text().replace('| class_count | `21` |', '| class_count | `13` |'))
        self.assertTrue(any("class_count" in error for error in _check_android_model_summary(self.root)))

    def test_active_docs_selector_tables_ignore_unrelated_backticked_tables(self):
        import check_walksafe_active_docs as docs

        text = (
            "| 선택자·분류 | 실제 범위 |\n| --- | --- |\n| `unit` | a |\n| `all` | b |\n\n"
            "| Build type | 용도 |\n| --- | --- |\n| `debug` | c |\n\n"
            "| model_key | asset |\n|---|---|\n| `unified_walksafe` | d |\n"
        )
        self.assertEqual(docs._documented_table_selectors(text), {"unit", "all"})
        result = docs.check_active_docs(ROOT)
        self.assertFalse([e for e in result.errors if "documented selector is missing" in e], result.errors)
        # An unknown selector in a selector table is still a contract error.
        errors, _ = docs._runner_contract(ROOT, set(result.runner_selectors) | {"bogus"})
        self.assertIn("bogus: documented selector is missing from runner case", errors)

    def test_android_debug_matrix_executes_both_expected_flag_combinations(self):
        source = (ROOT / "scripts/run_walksafe_test_layers_current.sh").read_text()
        matrix = source.split("  # Generated BuildConfig combinations:", 1)[1]
        matrix = matrix[matrix.index("  local enabled"):matrix.index('  (cd "${REPO_ROOT}/apps/android" && ./gradlew')]
        android = self.root / "apps/android"
        android.mkdir(parents=True)
        log = self.root / "gradle-calls.jsonl"
        stub = android / "gradlew"
        stub.write_text(f'#!{sys.executable}\nimport json, sys\nwith open({str(log)!r}, "a") as output:\n output.write(json.dumps(sys.argv[1:]) + "\\n")\n')
        stub.chmod(0o755)
        result = subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c",
            'set -euo pipefail\nREPO_ROOT="$1"\nrun_matrix() {\n' + matrix + '\n}\nrun_matrix\n',
            "matrix-fixture", str(self.root)], capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        calls = [json.loads(line) for line in log.read_text().splitlines()]
        self.assertEqual(2, len(calls))
        for enabled, call in zip(("false", "true"), calls):
            self.assertIn(":app:testDebugUnitTest", call)
            self.assertIn(":adminapp:testDebugUnitTest", call)
            self.assertIn("*BuildVariantIsolationTest", call)
            self.assertIn("*AdminBuildVariantIsolationTest", call)
            for flag in ("developmentReportQueue", "developmentQuickStart", "walksafe.guidanceStartBypass",
                         "WALKSAFE_ADMIN_OPERATIONAL_DEBUG", "walksafe.expectedDevelopmentQuickStart",
                         "walksafe.expectedReportQueue", "walksafe.expectedGuidanceStartBypass"):
                self.assertIn(f"-P{flag}={enabled}", call)
            self.assertIn("-PWALKSAFE_REPORT_QUEUE_ENABLED=false", call)
            self.assertFalse(any("positioneval" in option for option in call))
        for module in ("app", "adminapp"):
            self.assertIn(f":{module}:verifyReleaseBuildConfigIsolation :{module}:lintRelease :{module}:assembleRelease", source)
        self.assertIn("-PWALKSAFE_ADMIN_OPERATIONAL_DEBUG=true", source)
        self.assertIn("-Pwalksafe.expectedDevelopmentQuickStart=false -Pwalksafe.expectedReportQueue=false", source)

    def test_runner_cwd_and_failure_reporting_with_command_stubs(self):
        self.check_runner_report_directory(relative=False)

    def test_runner_relative_report_base_reaches_gateway_cwd(self):
        self.check_runner_report_directory(relative=True)

    def test_runner_pre_fix_relative_report_base_is_detected(self):
        with self.assertRaisesRegex(AssertionError, "report directory must be absolute"):
            self.check_runner_report_directory(relative=True, pre_fix=True)

    def check_runner_report_directory(self, relative, pre_fix=False):
        source = (ROOT / "scripts/run_walksafe_test_layers_current.sh").read_text()
        if pre_fix:
            source = source.replace('REPORT_BASE="$(cd "${REPORT_BASE}" && pwd)"\n', '')
        runner = self.root / "repo/scripts/run_walksafe_test_layers_current.sh"
        runner.parent.mkdir(parents=True)
        runner.write_text(source)
        repo = runner.parents[1]
        for path in set(re.findall(r"(?:backend/tests|tests|model)/[A-Za-z0-9_./-]+\.py", source)):
            target = repo / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.touch()
        for path in ("backend/tests", "tests", "model", "apps/android", "apps/web", "apps/android-gateway"):
            (repo / path).mkdir(parents=True, exist_ok=True)
        shutil.copy(ROOT / "scripts/summarize_walksafe_test_reports.py", runner.parent)
        log = self.root / "calls.jsonl"
        interpreter = self.root / "python-stub"
        interpreter.write_text(f'#!{sys.executable}\nimport json, os, pathlib, sys\nwith open({str(log)!r}, "a") as f: f.write(json.dumps({{"cwd": os.getcwd(), "args": sys.argv[1:], "report_dir": os.environ.get("WALKSAFE_TEST_REPORT_DIR")}}) + "\\n")\nif "summarize_walksafe_test_reports.py" in sys.argv[2] if len(sys.argv)>2 else False:\n os.execv({sys.executable!r}, [{sys.executable!r}, *sys.argv[1:]])\nif "pytest" in sys.argv:\n p=next(x.split("=",1)[1] for x in sys.argv if x.startswith("--junitxml="))\n pathlib.Path(p).write_text(\'<testsuite><testcase name="fixture"><failure/></testcase></testsuite>\')\n sys.exit(7)\n')
        interpreter.chmod(0o755)
        node_bin = self.root / "node/bin"
        node_bin.mkdir(parents=True)
        report_base = repo / "reports" if relative else self.root / "reports"
        environment = {**os.environ, "PYTHON_BIN": str(interpreter), "WALKSAFE_NODE_BIN_DIR": str(node_bin), "WALKSAFE_TEST_REPORT_BASE": "reports" if relative else str(report_base)}
        result = subprocess.run(["bash", str(runner), "unit"], cwd=self.root, env=environment, capture_output=True, text=True, timeout=8)
        self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
        calls = [json.loads(line) for line in log.read_text().splitlines()]
        pytest_call = next(call for call in calls if "pytest" in call["args"])
        self.assertEqual(pytest_call["cwd"], str(repo))
        report_dir = Path(pytest_call["report_dir"])
        self.assertTrue(report_dir.is_absolute(), "report directory must be absolute")
        self.assertEqual(report_dir.parent, report_base)
        if relative:
            child = subprocess.run(
                ["node", "-e", "require('node:fs').writeFileSync(require('node:path').join(process.env.WALKSAFE_TEST_REPORT_DIR, 'gateway-cwd-fixture.txt'), 'written')"],
                cwd=repo / "apps/android-gateway",
                env={**os.environ, "WALKSAFE_TEST_REPORT_DIR": str(report_dir)},
                capture_output=True, text=True, timeout=8,
            )
            self.assertEqual(child.returncode, 0, child.stderr)
            self.assertEqual((report_dir / "gateway-cwd-fixture.txt").read_text(), "written")
        report = json.loads(next(report_base.glob("*/summary.json")).read_text())
        groups = {row["group"]: row for row in report["groups"]}
        self.assertEqual(groups["python-unit"]["status"], "FAIL")
        self.assertEqual(groups["node-gateway"]["status"], "NOT_RUN")
        for module in ("app", "adminapp"):
            self.assertIn(f":{module}:verifyReleaseBuildConfigIsolation :{module}:lintRelease :{module}:assembleRelease", source)
        self.assertIn(":app:testDebugSdkAdapterUnitTest --no-daemon --rerun-tasks", source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
