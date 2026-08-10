from __future__ import annotations

import base64
from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import errno
import fcntl
import hashlib
from io import StringIO
import json
import os
from pathlib import Path
import shutil
import signal
import tempfile
import types
import unittest
from unittest import mock

from scripts import build_walksafe_fp048_gap_backlog_r023_20260802 as builder


ROOT = Path(__file__).resolve().parents[1]
TRACE = builder.fp048_trace
START = datetime(2026, 8, 2, 22, 0, tzinfo=timezone(timedelta(hours=9)))


class WalkSafeFp048GapBacklogR023Test(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for relative in (
            builder.R021_GAP_REL,
            builder.R021_BACKLOG_REL,
            builder.GOAL_REL,
            builder.START_GATE_RECEIPT_REL,
            builder.START_GATE_REPOSITORY_STATE_REL,
            builder.CHECKPOINT_REL,
            builder.BUILDER_REL,
            builder.BUILDER_TEST_REL,
            *TRACE.VERIFICATION_INPUT_PATHS,
        ):
            source = ROOT / relative
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        self._write_result_fixture()

    @staticmethod
    def _write_json(path: Path, value: dict) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    @staticmethod
    def _sha256(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def _write_result_fixture(self) -> None:
        canonical_implementation = json.loads(
            (ROOT / builder.IMPLEMENTATION_REL).read_text(encoding="utf-8")
        )
        canonical_before = {
            item["path"]: item
            for item in canonical_implementation["changed_artifacts"]
        }
        self.implementation_rows = []
        for index, relative_text in enumerate(TRACE.IMPLEMENTATION_PATHS):
            relative = Path(relative_text)
            product = self.root / relative
            product.parent.mkdir(parents=True, exist_ok=True)
            if relative in {
                builder.TRACE_BUILDER_REL,
                builder.TRACE_BUILDER_TEST_REL,
            }:
                product.write_bytes((ROOT / relative).read_bytes())
            else:
                product.write_bytes(
                    f"fp048-fixture-{index:03d}:{relative_text}\n".encode()
                )
            self.implementation_rows.append(
                {
                    "path": relative_text,
                    "before_sha256": canonical_before[relative_text][
                        "before_sha256"
                    ],
                    "after_sha256": self._sha256(product),
                    "before_source": canonical_before[relative_text][
                        "before_source"
                    ],
                    "change_kind": canonical_before[relative_text]["change_kind"],
                }
            )
        self.product_relative = Path(TRACE.IMPLEMENTATION_PATHS[0])
        content_set = TRACE.implementation_content_set(self.implementation_rows)
        verification_rows = []
        for relative in TRACE.VERIFICATION_INPUT_PATHS:
            content = (self.root / relative).read_bytes()
            verification_rows.append(
                {
                    "path": relative.as_posix(),
                    "byte_count": len(content),
                    "sha256": hashlib.sha256(content).hexdigest(),
                }
            )
        verification_set = TRACE.object_sha256(verification_rows)
        for index, spec in enumerate(TRACE.LANES):
            content = self._trace_log_bytes(
                spec,
                content_set,
                verification_set,
                index,
            )
            log = self.root / spec.log_rel
            log.parent.mkdir(parents=True, exist_ok=True)
            log.write_bytes(content)
            log.chmod(0o600)

        trace_outputs = TRACE.build_pre_review_outputs(
            root=self.root,
            implementation_rows=deepcopy(self.implementation_rows),
        )
        for relative in (*TRACE.RECEIPT_OUTPUTS, TRACE.IMPLEMENTATION_REL, TRACE.VERIFICATION_REL):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(trace_outputs[relative], encoding="utf-8")
            if relative in (
                *TRACE.RECEIPT_OUTPUTS,
                TRACE.IMPLEMENTATION_REL,
                TRACE.VERIFICATION_REL,
            ):
                target.chmod(0o600)

    @staticmethod
    def _android_junit_lines() -> list[str]:
        markers = dict(TRACE.LANES[0].exact_markers)
        total = int(markers["WALKSAFE_ANDROID_JUNIT_TESTS"])
        base, remainder = divmod(total, len(TRACE.EXPECTED_ANDROID_JUNIT_CLASSES))
        counts = [base + 1] * remainder + [base] * (len(TRACE.EXPECTED_ANDROID_JUNIT_CLASSES) - remainder)
        lines = []
        for class_name, count in zip(TRACE.EXPECTED_ANDROID_JUNIT_CLASSES, counts, strict=True):
            cases = "".join(
                f'<testcase classname="{class_name}" name="case{case}" />'
                for case in range(count)
            )
            raw = (
                f'<testsuite name="{class_name}" tests="{count}" failures="0" errors="0" skipped="0">'
                f"{cases}</testsuite>"
            ).encode()
            relative = TRACE.ANDROID_JUNIT_RESULT_DIR_REL / f"TEST-{class_name}.xml"
            lines.append(
                f"ANDROID_JUNIT_XML_BYTES path={relative.as_posix()} bytes={len(raw)} "
                f"sha256={TRACE.bytes_sha256(raw)} base64={base64.b64encode(raw).decode()}"
            )
        return lines

    @classmethod
    def _raw_trace_output(cls, spec: TRACE.LaneSpec) -> list[str]:
        markers = dict(spec.exact_markers)
        if spec.lane_id == "ANDROID_PROTECTED_STORAGE":
            host = markers["WALKSAFE_ANDROID_HOST_PYTEST_PASSED"]
            return [
                "> Task :app:testDebugUnitTest",
                "BUILD SUCCESSFUL in 1s",
                *cls._android_junit_lines(),
                f"{host} passed in 1.00s",
                "> Task :app:compileDebugAndroidTestKotlin",
                "> Task :app:assembleDebug",
                "> Task :app:lintDebug",
                "BUILD SUCCESSFUL in 1s",
            ]
        if spec.lane_id == "HTTPS_NO_DOWNGRADE":
            tests = markers["WALKSAFE_GATEWAY_TESTS"]
            passed = markers["WALKSAFE_GATEWAY_PASS"]
            failed = markers["WALKSAFE_GATEWAY_FAIL"]
            return ["> Task :app:testDebugUnitTest", "BUILD SUCCESSFUL in 1s", f"1..{tests}", f"# tests {tests}", "# suites 0", f"# pass {passed}", f"# fail {failed}", "# cancelled 0", "# skipped 0", "# todo 0"]
        if spec.lane_id == "SERVER_ORIGINAL_DB_BACKUP_BOUNDARY":
            return [
                f'{markers["WALKSAFE_BACKEND_INTERNAL_PASSED"]} passed, {markers["WALKSAFE_BACKEND_INTERNAL_SKIPPED"]} skipped in 1.00s',
                f'{markers["WALKSAFE_RETENTION_INTERNAL_PASSED"]} passed in 1.00s',
                f'{markers["WALKSAFE_BACKUP_INTERNAL_PASSED"]} passed in 1.00s',
            ]
        if spec.lane_id == "KEY_LIFECYCLE_ORIGINAL_ACCESS":
            return [f'{markers["WALKSAFE_BACKEND_FOCUSED_PASSED"]} passed, {markers["WALKSAFE_BACKEND_FOCUSED_SKIPPED"]} skipped in 1.00s']
        return [f'{markers["WALKSAFE_INCIDENT_TEST_UNIVERSE_PASSED"]} passed in 1.00s']

    @classmethod
    def _trace_log_bytes(
        cls,
        spec: TRACE.LaneSpec,
        content_set: str,
        verification_set: str,
        index: int,
    ) -> bytes:
        started = START + timedelta(minutes=index * 2)
        ended = started + timedelta(minutes=1)
        lines = [
            f"WALKSAFE_EXECUTION_EVENT_SEQUENCE={TRACE.EXPECTED_EXECUTION_EVENT_SEQUENCE}",
            f"WALKSAFE_EXECUTION_EVENT_ID={TRACE.EXPECTED_EXECUTION_EVENT_ID}",
            f"WALKSAFE_EXECUTION_EVENT_SHA256={TRACE.EXPECTED_EXECUTION_EVENT_SHA256}",
            f"WALKSAFE_IMPLEMENTATION_CONTENT_SET_SHA256={content_set}",
            f"WALKSAFE_VERIFICATION_INPUT_CONTENT_SET_SHA256={verification_set}",
            f"WALKSAFE_RUN_ID=fp048-r023-fixture-{index:02d}",
            f"WALKSAFE_COMMAND_SHA256={TRACE.bytes_sha256(spec.command.encode())}",
            f"WALKSAFE_COMMAND_STARTED_AT={started.isoformat()}",
            f"WALKSAFE_LANE_ID={spec.lane_id}",
            "WALKSAFE_LANE_STATUS=PASS",
            TRACE.RAW_OUTPUT_BEGIN,
            *cls._raw_trace_output(spec),
            TRACE.RAW_OUTPUT_END,
            "WALKSAFE_COMMAND_EXIT_CODE=0",
            f"WALKSAFE_COMMAND_ENDED_AT={ended.isoformat()}",
            *(f"{key}={value}" for key, value in spec.exact_markers),
        ]
        return ("\n".join(lines) + "\n").encode()

    def _build(self) -> tuple[dict[Path, str], dict, dict]:
        outputs = builder.build_outputs(self.root)
        gap = json.loads(outputs[builder.R023_GAP_JSON_REL])
        backlog = json.loads(outputs[builder.R023_BACKLOG_JSON_REL])
        return outputs, gap, backlog

    def _source_gap(self) -> dict:
        return json.loads((self.root / builder.R021_GAP_REL).read_text())

    def test_build_is_deterministic_and_has_exact_four_outputs(self) -> None:
        first = builder.build_outputs(self.root)
        second = builder.build_outputs(self.root)
        self.assertEqual(first, second)
        self.assertEqual(tuple(first), builder.OUTPUT_PATHS)

    def test_metadata_uses_canonical_r023_and_records_skipped_revision(self) -> None:
        _, gap, backlog = self._build()
        self.assertEqual(gap["metadata"]["version"], "0.23.0")
        self.assertEqual(
            gap["metadata"]["report_id"],
            "WS-IMPLEMENTATION-GAP-ANALYSIS-20260802-023",
        )
        self.assertEqual(backlog["metadata"]["version"], "0.23.0")
        self.assertEqual(
            backlog["metadata"]["backlog_id"],
            "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260802-023",
        )
        self.assertEqual(
            gap["metadata"]["revision_022_disposition"],
            builder.REVISION_022_DISPOSITION,
        )
        self.assertEqual(
            backlog["metadata"]["revision_022_disposition"],
            builder.REVISION_022_DISPOSITION,
        )

    def test_r021_source_bindings_are_exact_prefix_then_nine_fixed_bindings(self) -> None:
        _, gap, _ = self._build()
        predecessor = self._source_gap()
        prefix_length = len(predecessor["source_bindings"])
        self.assertEqual(prefix_length, 85)
        self.assertEqual(
            gap["source_bindings"][:prefix_length],
            predecessor["source_bindings"],
        )
        appended = gap["source_bindings"][prefix_length:]
        self.assertEqual(
            tuple(item["name"] for item in appended),
            builder.APPENDED_SOURCE_BINDING_NAMES,
        )
        self.assertEqual(len(appended), 9)
        self.assertEqual(
            gap["source_binding_sha256"],
            builder.object_sha256(gap["source_bindings"]),
        )

    def test_source_bindings_exclude_legacy_review_and_completion_inputs(self) -> None:
        _, gap, _ = self._build()
        appended_paths = [item["path"] for item in gap["source_bindings"][85:]]
        joined = "\n".join(appended_paths)
        self.assertNotIn("r022-candidate", joined)
        self.assertNotIn("independent-review", joined)
        self.assertNotIn("review-attestation", joined)
        self.assertNotIn("review-subject", joined)
        self.assertNotIn("completion-receipt", joined)
        self.assertIn(builder.BUILDER_REL.as_posix(), appended_paths)
        self.assertIn(builder.BUILDER_TEST_REL.as_posix(), appended_paths)

    def test_only_gap057_changes_and_other_67_are_deep_equal(self) -> None:
        _, gap, _ = self._build()
        predecessor = self._source_gap()
        old = {item["gap_id"]: item for item in predecessor["assessments"]}
        new = {item["gap_id"]: item for item in gap["assessments"]}
        self.assertEqual(set(new), set(old))
        changed = [gap_id for gap_id in old if old[gap_id] != new[gap_id]]
        self.assertEqual(changed, [builder.GAP_ID])
        self.assertEqual(
            sum(old[gap_id] == new[gap_id] for gap_id in old if gap_id != builder.GAP_ID),
            67,
        )
        self.assertEqual(old[builder.GAP_ID]["status"], "PARTIAL")
        self.assertEqual(new[builder.GAP_ID]["status"], "PARTIAL")

    def test_gap_seal_counts_and_boundary_remain_conservative(self) -> None:
        _, gap, _ = self._build()
        predecessor = self._source_gap()
        payload = deepcopy(gap)
        claimed = payload.pop("report_content_sha256")
        self.assertEqual(claimed, builder.object_sha256(payload))
        self.assertEqual(
            gap["summary"]["status_counts"],
            predecessor["summary"]["status_counts"],
        )
        self.assertEqual(gap["summary"]["release_status"], "NOT_ELIGIBLE")
        assessment = next(item for item in gap["assessments"] if item["gap_id"] == builder.GAP_ID)
        self.assertEqual(assessment["formal_test_status"], "NOT_RUN")
        for key, expected in builder.EXPECTED_VERIFICATION_BOUNDARY.items():
            self.assertEqual(assessment["fp048_reassessment"][key], expected)

    def test_reassessment_scope_is_exact_one_plus_sixty_seven(self) -> None:
        _, gap, _ = self._build()
        scope = gap["reassessment_scope"]
        self.assertEqual(scope["directly_reassessed_gap_ids"], [builder.GAP_ID])
        self.assertEqual(scope["reviewed_gap_ids"], [builder.GAP_ID])
        self.assertEqual(scope["carried_forward_gap_count"], 67)
        self.assertNotIn(builder.GAP_ID, scope["carried_forward_gap_ids"])
        self.assertEqual(scope["predecessor"]["path"], builder.R021_GAP_REL.as_posix())
        self.assertEqual(scope["next_adjacent_gap"]["gap_id"], builder.NEXT_GAP_ID)
        self.assertEqual(
            scope["next_adjacent_gap"]["source_policy_id"],
            builder.NEXT_POLICY_ID,
        )
        self.assertEqual(
            scope["predecessor"]["backlog_path"],
            builder.R021_BACKLOG_REL.as_posix(),
        )

    def test_backlog_is_sealed_and_advances_to_fp008_after_fp048_activation(self) -> None:
        _, gap, backlog = self._build()
        payload = deepcopy(backlog)
        claimed = payload.pop("backlog_content_sha256")
        self.assertEqual(claimed, builder.object_sha256(payload))
        self.assertEqual(backlog["gap_report_content_sha256"], gap["report_content_sha256"])
        self.assertEqual(backlog["next_single_action"]["source_policy_id"], builder.NEXT_POLICY_ID)
        self.assertEqual(backlog["next_single_action"]["gap_id"], builder.NEXT_GAP_ID)
        self.assertEqual(
            backlog["next_single_action"]["status"],
            "PLANNED_NEXT",
        )
        predecessor = json.loads((self.root / builder.R021_BACKLOG_REL).read_text())
        expected_action = next(
            item["action"]
            for item in predecessor["next_action_sequence"]
            if item["source_policy_id"] == builder.NEXT_POLICY_ID
        )
        self.assertEqual(backlog["next_single_action"]["action"], expected_action)
        self.assertEqual(
            backlog["fp048_verification_boundary"],
            {
                "formal_test_ids": builder.EXPECTED_FORMAL_TEST_IDS,
                **builder.EXPECTED_VERIFICATION_BOUNDARY,
            },
        )
        row = next(
            item
            for item in backlog["next_action_sequence"]
            if item["source_policy_id"] == builder.POLICY_ID
        )
        self.assertEqual(row["status"], "PARTIAL")

    def test_markdown_states_r023_partial_and_not_run_boundary(self) -> None:
        outputs, _, _ = self._build()
        gap_markdown = outputs[builder.R023_GAP_MD_REL]
        backlog_markdown = outputs[builder.R023_BACKLOG_MD_REL]
        self.assertTrue(gap_markdown.startswith("# WalkSafe 구현 Gap 분석 r023\n"))
        self.assertIn("`PARTIAL → PARTIAL`", gap_markdown)
        self.assertIn("67개 r021 deep-equal carry-forward", gap_markdown)
        self.assertIn("`NOT_RUN`", gap_markdown)
        self.assertTrue(backlog_markdown.startswith("# WalkSafe 구현 보완 Backlog r023\n"))
        self.assertIn("`NOT_ELIGIBLE`", backlog_markdown)

    def test_build_does_not_modify_any_source_bytes(self) -> None:
        relatives = (
            builder.R021_GAP_REL,
            builder.R021_BACKLOG_REL,
            builder.GOAL_REL,
            builder.START_GATE_RECEIPT_REL,
            builder.START_GATE_REPOSITORY_STATE_REL,
            builder.IMPLEMENTATION_REL,
            builder.VERIFICATION_REL,
            *builder.TRACE_RECEIPT_RELS,
            *(spec.log_rel for spec in TRACE.LANES),
            *TRACE.VERIFICATION_INPUT_PATHS,
            builder.BUILDER_REL,
            builder.BUILDER_TEST_REL,
        )
        before = {relative: (self.root / relative).read_bytes() for relative in relatives}
        builder.build_outputs(self.root)
        after = {relative: (self.root / relative).read_bytes() for relative in relatives}
        self.assertEqual(after, before)

    def test_write_then_check_round_trip(self) -> None:
        outputs = builder.build_outputs(self.root)
        builder.write_or_check(self.root, outputs, write=True)
        builder.write_or_check(self.root, builder.build_outputs(self.root), write=False)
        self.assertTrue(all((self.root / relative).is_file() for relative in builder.OUTPUT_PATHS))

    def test_strong_umask_still_publishes_private_outputs(self) -> None:
        outputs = builder.build_outputs(self.root)
        previous_umask: int | None = None

        def enable_strong_umask(index: int, _relative: Path) -> None:
            nonlocal previous_umask
            if index == 0:
                previous_umask = os.umask(0o777)

        try:
            self.assertEqual(
                builder.write_or_check(
                    self.root,
                    outputs,
                    write=True,
                    stage_write_hook=enable_strong_umask,
                ),
                outputs,
            )
        finally:
            if previous_umask is not None:
                os.umask(previous_umask)
        for relative in builder.OUTPUT_PATHS:
            self.assertEqual((self.root / relative).stat().st_mode & 0o777, 0o600)
        builder.write_or_check(self.root, outputs, write=False)

    def test_strong_umask_sigkill_stage_recovers_without_manual_cleanup(self) -> None:
        outputs = builder.build_outputs(self.root)
        child = os.fork()
        if child == 0:
            def enable_umask_then_kill(index: int, _relative: Path) -> None:
                if index == 0:
                    os.umask(0o777)
                elif index == 1:
                    os.kill(os.getpid(), signal.SIGKILL)

            try:
                builder.write_or_check(
                    self.root,
                    outputs,
                    write=True,
                    stage_write_hook=enable_umask_then_kill,
                )
            except BaseException:
                os._exit(98)
            os._exit(99)
        _pid, status = os.waitpid(child, 0)
        self.assertTrue(os.WIFSIGNALED(status))
        self.assertEqual(os.WTERMSIG(status), signal.SIGKILL)
        first_stage = self.root / builder._output_stage_relative(
            builder.OUTPUT_PATHS[1]
        )
        self.assertTrue(first_stage.is_file())
        self.assertEqual(first_stage.stat().st_mode & 0o777, 0o600)

        self.assertEqual(
            builder.write_or_check(self.root, outputs, write=True),
            outputs,
        )
        builder.write_or_check(self.root, outputs, write=False)
        residue = (
            builder.TRANSACTION_JOURNAL_REL,
            builder.TRANSACTION_JOURNAL_STAGE_REL,
            *(builder._output_stage_relative(relative) for relative in builder.OUTPUT_PATHS),
        )
        self.assertFalse(any((self.root / relative).exists() for relative in residue))

    def test_partial_publish_recovers_forward_without_overwrite(self) -> None:
        outputs = builder.build_outputs(self.root)

        def stop_after_second(index: int, _relative: Path) -> None:
            if index == 1:
                raise RuntimeError("simulated process stop")

        with self.assertRaisesRegex(RuntimeError, "simulated"):
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                publish_hook=stop_after_second,
            )
        self.assertEqual(
            [relative for relative in builder.OUTPUT_PATHS if (self.root / relative).exists()],
            list(builder.OUTPUT_PATHS[:2]),
        )
        builder.write_or_check(self.root, outputs, write=True)
        builder.write_or_check(self.root, outputs, write=False)
        self.assertFalse(
            any(
                path.name.endswith(".r023-stage")
                for path in (self.root / builder.R023_GAP_JSON_REL.parent).iterdir()
            )
        )

    def test_main_write_and_check_report_pass(self) -> None:
        stdout = StringIO()
        with redirect_stdout(stdout):
            self.assertEqual(builder.main(["--root", str(self.root), "--write"]), 0)
        self.assertIn("mode=WRITE", stdout.getvalue())
        stdout = StringIO()
        with redirect_stdout(stdout):
            self.assertEqual(builder.main(["--root", str(self.root), "--check"]), 0)
        self.assertIn("mode=CHECK", stdout.getvalue())

    def test_rejects_r021_byte_drift(self) -> None:
        path = self.root / builder.R021_GAP_REL
        path.write_bytes(path.read_bytes() + b"\n")
        with self.assertRaisesRegex(builder.BuildError, "canonical r021 file differs"):
            builder.build_outputs(self.root)

    def test_rejects_start_goal_drift(self) -> None:
        path = self.root / builder.GOAL_REL
        path.write_bytes(path.read_bytes() + b"drift\n")
        with self.assertRaisesRegex(builder.BuildError, "Goal hash differs"):
            builder.build_outputs(self.root)

    def test_rejects_r023_runtime_builder_source_mirror_drift(self) -> None:
        path = self.root / builder.BUILDER_REL
        path.write_text("this is not valid Python\n", encoding="utf-8")
        with self.assertRaisesRegex(
            builder.BuildError,
            "runtime tooling source mirror differs",
        ):
            builder.build_outputs(self.root)

    def test_rejects_trace_runtime_builder_source_mirror_drift(self) -> None:
        path = self.root / builder.TRACE_BUILDER_REL
        path.write_text("this is not valid Python\n", encoding="utf-8")
        with self.assertRaisesRegex(
            builder.BuildError,
            "runtime tooling source mirror differs",
        ):
            builder.build_outputs(self.root)

    def test_rejects_runtime_r023_function_code_replacement(self) -> None:
        with mock.patch.object(builder, "build_gap", return_value={}):
            with self.assertRaisesRegex(
                builder.BuildError,
                "runtime r023 builder code differs",
            ):
                builder.build_outputs(self.root)

    def test_rejects_runtime_r023_configuration_replacement(self) -> None:
        with mock.patch.object(
            builder,
            "REVISION_022_DISPOSITION",
            "INJECTED_RUNTIME_GLOBAL",
        ):
            with self.assertRaisesRegex(
                builder.BuildError,
                "runtime r023 configuration differs",
            ):
                builder.build_outputs(self.root)

    def test_rejects_runtime_r023_named_function_binding_replacement(self) -> None:
        original = builder.build_gap

        def replacement(*args: object, **kwargs: object) -> dict:
            return original(*args, **kwargs)

        replacement.__wrapped__ = original  # type: ignore[attr-defined]
        with mock.patch.object(builder, "saved_build_gap", original, create=True):
            with mock.patch.object(builder, "build_gap", replacement):
                with self.assertRaisesRegex(
                    builder.BuildError,
                    "runtime r023 function bindings differ",
                ):
                    builder.build_outputs(self.root)

    def test_rejects_runtime_r023_function_default_replacement(self) -> None:
        with mock.patch.object(builder.build_outputs, "__defaults__", (self.root,)):
            with self.assertRaisesRegex(
                builder.BuildError,
                "runtime r023 function bindings differ",
            ):
                builder.build_outputs()

    def test_rejects_runtime_r023_function_globals_replacement(self) -> None:
        original = builder.build_gap
        injected_globals = dict(original.__globals__)
        injected_globals["REVISION_022_DISPOSITION"] = "INJECTED_FUNCTION_GLOBALS"
        replacement = types.FunctionType(
            original.__code__,
            injected_globals,
            original.__name__,
            original.__defaults__,
            original.__closure__,
        )
        replacement.__kwdefaults__ = original.__kwdefaults__
        replacement.__annotations__ = original.__annotations__
        replacement.__qualname__ = original.__qualname__

        with mock.patch.object(builder, "build_gap", replacement):
            with self.assertRaisesRegex(
                builder.BuildError,
                "runtime function globals differ",
            ):
                builder.build_outputs(self.root)

    def test_rejects_runtime_r023_dependency_binding_replacement(self) -> None:
        original = builder.deepcopy

        def replacement(value: object) -> object:
            return original(value)

        with mock.patch.object(builder, "deepcopy", replacement):
            with self.assertRaisesRegex(
                builder.BuildError,
                "runtime r023 dependency bindings differ",
            ):
                builder.build_outputs(self.root)

    def test_rejects_used_runtime_module_attribute_rebinding(self) -> None:
        cases = (
            (builder.json, "dumps"),
            (builder.hashlib, "sha256"),
            (builder.os, "open"),
        )
        for module, name in cases:
            with self.subTest(binding=f"{module.__name__}.{name}"):
                original = getattr(module, name)

                def replacement(*args: object, _original: object = original, **kwargs: object) -> object:
                    return _original(*args, **kwargs)  # type: ignore[operator]

                with mock.patch.object(module, name, replacement):
                    with self.assertRaisesRegex(
                        builder.BuildError,
                        "runtime dependency attribute bindings differ",
                    ):
                        builder.build_outputs(self.root)
        self.assertFalse(any((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))

    def test_runtime_code_maps_ignore_python314_annotate_thunks(self) -> None:
        origin = str(self.root / "annotated_fixture.py")
        source = b"from __future__ import annotations\ndef value(item: Missing) -> Missing:\n    return item\n"
        module_code = compile(source, origin, "exec", dont_inherit=True)
        module = types.ModuleType("annotated_fixture")
        module.__file__ = origin
        exec(module_code, module.__dict__)
        expected = builder._function_code_map_from_code(module_code, origin)
        actual = builder._runtime_function_code_map(module, origin)
        self.assertEqual(actual, expected)
        self.assertTrue(all(key[2] != "__annotate__" for key in expected))

    def test_serialized_outputs_reject_semantic_and_derivation_tamper(self) -> None:
        outputs = builder.build_outputs(self.root)

        gap_tamper = {relative: text.encode() for relative, text in outputs.items()}
        gap = json.loads(gap_tamper[builder.R023_GAP_JSON_REL])
        gap["summary"]["release_status"] = "TAMPERED"
        gap_tamper[builder.R023_GAP_JSON_REL] = builder.json_text(gap).encode()
        with self.assertRaisesRegex(builder.BuildError, "serialized r023 Gap seal differs"):
            builder._validate_serialized_output_bytes(gap_tamper)

        cross_tamper = {relative: text.encode() for relative, text in outputs.items()}
        backlog = json.loads(cross_tamper[builder.R023_BACKLOG_JSON_REL])
        backlog["gap_report_content_sha256"] = "0" * 64
        backlog.pop("backlog_content_sha256")
        backlog["backlog_content_sha256"] = builder.object_sha256(backlog)
        cross_tamper[builder.R023_BACKLOG_JSON_REL] = builder.json_text(backlog).encode()
        with self.assertRaisesRegex(builder.BuildError, "Gap/Backlog hash binding differs"):
            builder._validate_serialized_output_bytes(cross_tamper)

        markdown_tamper = {relative: text.encode() for relative, text in outputs.items()}
        markdown_tamper[builder.R023_GAP_MD_REL] += b"unexpected\n"
        with self.assertRaisesRegex(builder.BuildError, "output derivation differs"):
            builder._validate_serialized_output_bytes(markdown_tamper)

        noncanonical = {relative: text.encode() for relative, text in outputs.items()}
        noncanonical[builder.R023_GAP_JSON_REL] = b" \n" + noncanonical[builder.R023_GAP_JSON_REL]
        with self.assertRaisesRegex(builder.BuildError, "output derivation differs"):
            builder._validate_serialized_output_bytes(noncanonical)

    def test_trace_rebuild_does_not_execute_external_git(self) -> None:
        def reject_external_git(*_args: object, **_kwargs: object) -> object:
            raise AssertionError("external git must not execute")

        with mock.patch.object(
            TRACE.subprocess,
            "run",
            new=reject_external_git,
        ):
            with self.assertRaisesRegex(
                builder.BuildError,
                "runtime dependency attribute bindings differ",
            ):
                builder.build_outputs(self.root)
        outputs = builder.build_outputs(self.root)
        self.assertEqual(tuple(outputs), builder.OUTPUT_PATHS)

    def test_production_root_forbids_test_rename_hook(self) -> None:
        with self.assertRaisesRegex(
            builder.BuildError,
            "test rename hook is forbidden for the production root",
        ):
            builder.write_or_check(
                builder.ROOT,
                None,
                write=False,
                test_rename_noreplace_hook=lambda _fd, _source, _destination: None,
            )

    def test_publication_requires_cooperative_exclusive_parent_lease(self) -> None:
        outputs = builder.build_outputs(self.root)
        parent = self.root / builder.OUTPUT_PATHS[0].parent
        parent.mkdir(parents=True, exist_ok=True)
        ready_read, ready_write = os.pipe()
        release_read, release_write = os.pipe()
        child = os.fork()
        if child == 0:
            os.close(ready_read)
            os.close(release_write)
            descriptor = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX)
                os.write(ready_write, b"1")
                os.read(release_read, 1)
            finally:
                os.close(descriptor)
                os.close(ready_write)
                os.close(release_read)
            os._exit(0)

        os.close(ready_write)
        os.close(release_read)
        try:
            self.assertEqual(os.read(ready_read, 1), b"1")
            with self.assertRaisesRegex(
                builder.BuildError,
                "another cooperative r023 publisher holds the lease",
            ):
                builder.write_or_check(self.root, outputs, write=True)
        finally:
            os.write(release_write, b"1")
            os.close(ready_read)
            os.close(release_write)
            _pid, status = os.waitpid(child, 0)
        self.assertTrue(os.WIFEXITED(status))
        self.assertEqual(os.WEXITSTATUS(status), 0)
        self.assertFalse(any((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))

    def test_rejects_formal_or_external_status_promotion(self) -> None:
        path = self.root / builder.VERIFICATION_REL
        value = json.loads(path.read_text())
        value["evidence_boundary"]["formal_test_status"] = "PASS"
        self._write_json(path, value)
        with self.assertRaisesRegex(builder.BuildError, "formal_test_status"):
            builder.build_outputs(self.root)

    def test_rejects_review_or_completion_as_result_evidence(self) -> None:
        path = self.root / builder.VERIFICATION_REL
        value = json.loads(path.read_text())
        value["checks"][0]["output_path"] = (
            builder.RESULT_DIR_REL / "independent-review.json"
        ).as_posix()
        self._write_json(path, value)
        with self.assertRaisesRegex(builder.BuildError, "forbidden verification source"):
            builder.build_outputs(self.root)

    def test_rejects_web_legacy_implementation_source(self) -> None:
        legacy_relative = Path("apps/web/legacy_candidate.ts")
        legacy = self.root / legacy_relative
        legacy.parent.mkdir(parents=True, exist_ok=True)
        legacy.write_text("export const legacy = true;\n", encoding="utf-8")
        implementation_path = self.root / builder.IMPLEMENTATION_REL
        implementation = json.loads(implementation_path.read_text())
        legacy_hash = self._sha256(legacy)
        implementation["changed_artifacts"] = [
            {
                "path": legacy_relative.as_posix(),
                "before_sha256": None,
                "after_sha256": legacy_hash,
                "before_source": "GATE_PINNED_HEAD_ABSENT",
                "change_kind": "ADDED",
            }
        ]
        implementation["implementation_content_set_sha256"] = builder.object_sha256(
            [{"path": legacy_relative.as_posix(), "sha256": legacy_hash}]
        )
        self._write_json(implementation_path, implementation)
        with self.assertRaisesRegex(builder.BuildError, "forbidden implementation source"):
            builder.build_outputs(self.root)

    def test_rejects_skeletal_noncanonical_trace_fixture(self) -> None:
        implementation_path = self.root / builder.IMPLEMENTATION_REL
        implementation = json.loads(implementation_path.read_text())
        implementation["changed_artifacts"] = implementation["changed_artifacts"][:1]
        implementation["exact_path_count"] = 1
        implementation["implementation_content_set_sha256"] = builder.object_sha256(
            [
                {
                    "path": implementation["changed_artifacts"][0]["path"],
                    "sha256": implementation["changed_artifacts"][0]["after_sha256"],
                }
            ]
        )
        self._write_json(implementation_path, implementation)
        verification_path = self.root / builder.VERIFICATION_REL
        verification = json.loads(verification_path.read_text())
        verification["implementation_content_set_sha256"] = implementation[
            "implementation_content_set_sha256"
        ]
        for check in verification["checks"]:
            check["implementation_content_set_sha256"] = implementation[
                "implementation_content_set_sha256"
            ]
        self._write_json(verification_path, verification)
        with self.assertRaisesRegex(builder.BuildError, "implementation path order differs"):
            builder.build_outputs(self.root)

    def test_rejects_lane_command_and_receipt_spoof(self) -> None:
        verification_path = self.root / builder.VERIFICATION_REL
        verification = json.loads(verification_path.read_text())
        verification["checks"][0]["command"] += " --spoof"
        self._write_json(verification_path, verification)
        with self.assertRaisesRegex(builder.BuildError, "lane binding differs"):
            builder.build_outputs(self.root)

        trace_outputs = TRACE.build_pre_review_outputs(
            root=self.root,
            implementation_rows=deepcopy(self.implementation_rows),
        )
        verification_path.write_text(trace_outputs[TRACE.VERIFICATION_REL], encoding="utf-8")
        receipt_path = self.root / TRACE.RECEIPT_OUTPUTS[0]
        receipt = json.loads(receipt_path.read_text())
        receipt["command_execution"]["run_id"] = "fp048-spoofed-receipt"
        receipt.pop("receipt_content_sha256")
        receipt["receipt_content_sha256"] = builder.object_sha256(receipt)
        self._write_json(receipt_path, receipt)
        receipt_path.chmod(0o600)
        with self.assertRaisesRegex(builder.BuildError, "receipt differs from exact producer"):
            builder.build_outputs(self.root)

    def test_rejects_input_hardlink_and_symlink_swap(self) -> None:
        product = self.root / self.product_relative
        alias = self.root / "product-hardlink"
        os.link(product, alias)
        with self.assertRaisesRegex(builder.BuildError, "hard-linked file"):
            builder.build_outputs(self.root)
        alias.unlink()

        outside = self.root / "outside-product"
        outside.write_bytes(product.read_bytes())
        original_open = builder.os.open
        swapped = False

        def swap_before_final_open(path: object, flags: int, *args: object, **kwargs: object) -> int:
            nonlocal swapped
            if (
                not swapped
                and path == product.name
                and kwargs.get("dir_fd") is not None
                and flags & os.O_DIRECTORY == 0
            ):
                swapped = True
                product.unlink()
                product.symlink_to(outside)
            return original_open(path, flags, *args, **kwargs)

        with mock.patch.object(builder, "_OS_OPEN", side_effect=swap_before_final_open):
            with self.assertRaises(OSError):
                builder.build_outputs(self.root)
        self.assertTrue(swapped)

    def test_check_rejects_hardlink_and_world_writable_output(self) -> None:
        outputs = builder.build_outputs(self.root)
        builder.write_or_check(self.root, outputs, write=True)
        target = self.root / builder.R023_GAP_JSON_REL
        alias = self.root / "output-hardlink"
        os.link(target, alias)
        with self.assertRaisesRegex(builder.BuildError, "hard-linked file"):
            builder.write_or_check(self.root, outputs, write=False)
        alias.unlink()
        target.chmod(0o666)
        with self.assertRaisesRegex(builder.BuildError, "unsafe output file|mode differs"):
            builder.write_or_check(self.root, outputs, write=False)

    def test_rename_collision_never_overwrites(self) -> None:
        outputs = builder.build_outputs(self.root)
        original = builder._rename_noreplace_at

        def collide(parent_fd: int, source: str, destination: str) -> None:
            if destination == builder.TRANSACTION_JOURNAL_REL.name:
                original(parent_fd, source, destination)
                return
            descriptor = os.open(
                destination,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                0o600,
                dir_fd=parent_fd,
            )
            try:
                os.write(descriptor, b"collision")
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
            original(parent_fd, source, destination)

        with self.assertRaisesRegex(builder.BuildError, "output (?:differs|byte length differs)"):
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                test_rename_noreplace_hook=collide,
            )
        self.assertEqual((self.root / builder.R023_GAP_JSON_REL).read_bytes(), b"collision")

    def test_existing_exact_outputs_keep_their_inodes(self) -> None:
        outputs = builder.build_outputs(self.root)
        builder.write_or_check(self.root, outputs, write=True)
        before = {
            relative: (self.root / relative).stat().st_ino
            for relative in builder.OUTPUT_PATHS
        }
        builder.write_or_check(self.root, outputs, write=True)
        self.assertEqual(
            {
                relative: (self.root / relative).stat().st_ino
                for relative in builder.OUTPUT_PATHS
            },
            before,
        )

    def test_exact_byte_rename_collision_converges_without_overwrite(self) -> None:
        outputs = builder.build_outputs(self.root)
        original = builder._rename_noreplace_at
        collided = False

        def collide_exact(parent_fd: int, source: str, destination: str) -> None:
            nonlocal collided
            if destination == builder.TRANSACTION_JOURNAL_REL.name:
                original(parent_fd, source, destination)
                return
            if not collided:
                collided = True
                descriptor = os.open(
                    destination,
                    os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                    0o600,
                    dir_fd=parent_fd,
                )
                try:
                    os.write(descriptor, outputs[builder.OUTPUT_PATHS[0]].encode())
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
            original(parent_fd, source, destination)

        builder.write_or_check(
            self.root,
            outputs,
            write=True,
            test_rename_noreplace_hook=collide_exact,
        )
        self.assertTrue(collided)
        builder.write_or_check(self.root, outputs, write=False)
        self.assertFalse(any((self.root / relative.with_name(f".{relative.name}.r023-stage")).exists() for relative in builder.OUTPUT_PATHS))

    def test_owned_malformed_stage_is_rebuilt_before_publish(self) -> None:
        outputs = builder.build_outputs(self.root)
        first = builder.OUTPUT_PATHS[0]
        staged = first.with_name(f".{first.name}.r023-stage")
        staged_path = self.root / staged
        staged_path.parent.mkdir(parents=True, exist_ok=True)
        staged_path.write_bytes(b"x" * len(outputs[first].encode()))
        staged_path.chmod(0o600)
        builder.write_or_check(self.root, outputs, write=True)
        builder.write_or_check(self.root, outputs, write=False)
        self.assertFalse(staged_path.exists())

    def test_rename_non_eexist_error_preserves_stage_and_no_final(self) -> None:
        outputs = builder.build_outputs(self.root)
        first = builder.OUTPUT_PATHS[0]
        staged = first.with_name(f".{first.name}.r023-stage")
        def fail_rename(_parent_fd: int, _source: str, _destination: str) -> None:
            raise OSError(errno.EIO, "simulated rename failure")

        with self.assertRaises(OSError):
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                test_rename_noreplace_hook=fail_rename,
            )
        self.assertFalse((self.root / first).exists())
        self.assertEqual((self.root / staged).read_bytes(), outputs[first].encode())

    def test_pre_publish_stage_mode_tamper_never_reaches_final(self) -> None:
        outputs = builder.build_outputs(self.root)
        tampered = False

        def tamper_before_commit() -> None:
            nonlocal tampered
            tampered = True
            first = builder._output_stage_relative(builder.OUTPUT_PATHS[0])
            (self.root / first).chmod(0o666)

        with self.assertRaisesRegex(
            builder.BuildError,
            "unsafe stage file|stage descriptor identity changed",
        ):
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                before_journal_commit_hook=tamper_before_commit,
            )
        self.assertTrue(tampered)
        self.assertFalse(any((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))

    def test_same_length_stage_overwrite_after_commit_is_preserved_and_fails_closed(self) -> None:
        outputs = builder.build_outputs(self.root)
        original = builder._rename_noreplace_at
        tampered = False

        def overwrite_then_rename(parent_fd: int, source: str, destination: str) -> None:
            nonlocal tampered
            if destination == builder.TRANSACTION_JOURNAL_REL.name:
                original(parent_fd, source, destination)
                return
            if not tampered:
                tampered = True
                descriptor = os.open(
                    source,
                    os.O_WRONLY | os.O_NOFOLLOW,
                    dir_fd=parent_fd,
                )
                try:
                    before = os.fstat(descriptor)
                    replacement = b"x" * before.st_size
                    position = 0
                    while position < len(replacement):
                        position += os.write(descriptor, replacement[position:])
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
                os.utime(
                    source,
                    ns=(before.st_atime_ns, before.st_mtime_ns),
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
            original(parent_fd, source, destination)

        with self.assertRaisesRegex(builder.BuildError, "published stage"):
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                test_rename_noreplace_hook=overwrite_then_rename,
            )
        self.assertTrue(tampered)
        self.assertTrue((self.root / builder.OUTPUT_PATHS[0]).exists())
        self.assertTrue((self.root / builder.TRANSACTION_JOURNAL_REL).exists())
        with self.assertRaisesRegex(builder.BuildError, "output differs"):
            builder.write_or_check(self.root, outputs, write=True)

    def test_final_cohort_revalidation_rejects_late_mutation_of_earlier_output(self) -> None:
        outputs = builder.build_outputs(self.root)
        first = self.root / builder.OUTPUT_PATHS[0]
        tampered = False

        def tamper_after_later_publish(index: int, _relative: Path) -> None:
            nonlocal tampered
            if index != 1:
                return
            tampered = True
            replacement = b"x" * first.stat().st_size
            descriptor = os.open(first, os.O_WRONLY | os.O_NOFOLLOW)
            try:
                position = 0
                while position < len(replacement):
                    position += os.write(descriptor, replacement[position:])
                os.fsync(descriptor)
            finally:
                os.close(descriptor)

        with self.assertRaisesRegex(builder.BuildError, "output"):
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                publish_hook=tamper_after_later_publish,
            )
        self.assertTrue(tampered)

    def test_sigkill_mid_publish_recovers_forward(self) -> None:
        outputs = builder.build_outputs(self.root)
        child = os.fork()
        if child == 0:
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                publish_hook=lambda index, _relative: (
                    os.kill(os.getpid(), signal.SIGKILL) if index == 1 else None
                ),
            )
            os._exit(99)
        _pid, status = os.waitpid(child, 0)
        self.assertTrue(os.WIFSIGNALED(status))
        self.assertEqual(os.WTERMSIG(status), signal.SIGKILL)
        builder.write_or_check(self.root, outputs, write=True)
        builder.write_or_check(self.root, outputs, write=False)

    def test_one_byte_partial_stage_recovers_forward(self) -> None:
        outputs = builder.build_outputs(self.root)
        first = builder.OUTPUT_PATHS[0]
        staged = first.with_name(f".{first.name}.r023-stage")
        staged_path = self.root / staged
        staged_path.parent.mkdir(parents=True, exist_ok=True)
        staged_path.write_bytes(b"{")
        staged_path.chmod(0o600)
        self.assertEqual((self.root / staged).stat().st_size, 1)
        self.assertFalse(any((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))
        builder.write_or_check(self.root, outputs, write=True)
        builder.write_or_check(self.root, outputs, write=False)
        self.assertFalse(any((self.root / relative.with_name(f".{relative.name}.r023-stage")).exists() for relative in builder.OUTPUT_PATHS))

    def test_committed_journal_recovery_requires_current_source_anchor(self) -> None:
        outputs = builder.build_outputs(self.root)
        child = os.fork()
        if child == 0:
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                journal_commit_hook=lambda: os.kill(os.getpid(), signal.SIGKILL),
            )
            os._exit(99)
        _pid, status = os.waitpid(child, 0)
        self.assertTrue(os.WIFSIGNALED(status))
        self.assertEqual(os.WTERMSIG(status), signal.SIGKILL)
        self.assertTrue((self.root / builder.TRANSACTION_JOURNAL_REL).exists())
        self.assertFalse(any((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))

        source = self.root / self.product_relative
        original = source.read_bytes()
        source.write_bytes(b"drifted-after-commit\n")
        with self.assertRaisesRegex(builder.BuildError, "implementation file differs"):
            builder.write_or_check(self.root, outputs, write=True)
        self.assertTrue((self.root / builder.TRANSACTION_JOURNAL_REL).exists())
        self.assertFalse(any((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))
        source.write_bytes(original)
        recovered = builder.write_or_check(self.root, outputs, write=True)
        self.assertEqual(recovered, outputs)
        self.assertTrue(all((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))
        self.assertFalse((self.root / builder.TRANSACTION_JOURNAL_REL).exists())

    def test_committed_journal_recovery_rejects_root_path_replacement(self) -> None:
        outputs = builder.build_outputs(self.root)
        child = os.fork()
        if child == 0:
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                journal_commit_hook=lambda: os.kill(os.getpid(), signal.SIGKILL),
            )
            os._exit(99)
        _pid, status = os.waitpid(child, 0)
        self.assertTrue(os.WIFSIGNALED(status))

        replacement = self.root.with_name(f"{self.root.name}-replacement")
        parked = self.root.with_name(f"{self.root.name}-parked")
        shutil.copytree(self.root, replacement)
        replaced = False

        def replace_root_then_rename(
            parent_fd: int,
            source: str,
            destination: str,
        ) -> None:
            nonlocal replaced
            if not replaced:
                replaced = True
                os.rename(self.root, parked)
                os.rename(replacement, self.root)
            builder._rename_noreplace_at(parent_fd, source, destination)

        try:
            with self.assertRaisesRegex(
                builder.BuildError,
                "repository root path changed",
            ):
                builder.write_or_check(
                    self.root,
                    outputs,
                    write=True,
                    test_rename_noreplace_hook=replace_root_then_rename,
                )
            self.assertTrue(replaced)
            self.assertFalse(
                any((self.root / relative).exists() for relative in builder.OUTPUT_PATHS)
            )
        finally:
            if parked.exists():
                restored_replacement = replacement
                if self.root.exists():
                    os.rename(self.root, restored_replacement)
                os.rename(parked, self.root)
                shutil.rmtree(restored_replacement)

    def test_recovery_rejects_self_sealed_forged_transaction(self) -> None:
        expected = builder.build_outputs(self.root)
        forged = dict(expected)
        forged[builder.R023_GAP_MD_REL] += "forged\n"
        with builder.RepositorySnapshot(self.root) as snapshot:
            builder._capture_input_cohort(snapshot)
            source_snapshot_sha256 = snapshot.content_set_sha256()
            snapshot.verify()

        for relative, text in forged.items():
            staged = self.root / builder._output_stage_relative(relative)
            staged.parent.mkdir(parents=True, exist_ok=True)
            staged.write_bytes(text.encode())
            staged.chmod(0o600)
        journal = self.root / builder.TRANSACTION_JOURNAL_REL
        journal.write_bytes(
            builder._transaction_journal_bytes(
                forged,
                source_snapshot_sha256=source_snapshot_sha256,
            )
        )
        journal.chmod(0o600)

        with self.assertRaisesRegex(
            builder.BuildError,
            "transaction output binding differs",
        ):
            builder.write_or_check(self.root, None, write=True)
        self.assertTrue(journal.exists())
        self.assertFalse(any((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))
        self.assertTrue(
            all(
                (self.root / builder._output_stage_relative(relative)).exists()
                for relative in builder.OUTPUT_PATHS
            )
        )

    def test_recovery_collision_preserves_correct_stage_and_journal(self) -> None:
        outputs = builder.build_outputs(self.root)
        child = os.fork()
        if child == 0:
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                journal_commit_hook=lambda: os.kill(os.getpid(), signal.SIGKILL),
            )
            os._exit(99)
        _pid, status = os.waitpid(child, 0)
        self.assertTrue(os.WIFSIGNALED(status))

        first = builder.OUTPUT_PATHS[0]
        first_stage = self.root / builder._output_stage_relative(first)
        expected_stage = first_stage.read_bytes()
        collided = False

        def collide(parent_fd: int, source: str, destination: str) -> None:
            nonlocal collided
            if not collided and destination == first.name:
                collided = True
                descriptor = os.open(
                    destination,
                    os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                    0o600,
                    dir_fd=parent_fd,
                )
                try:
                    os.write(descriptor, b"foreign")
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
            builder._rename_noreplace_at(parent_fd, source, destination)

        with self.assertRaisesRegex(builder.BuildError, "output byte length differs"):
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                test_rename_noreplace_hook=collide,
            )
        self.assertTrue(collided)
        self.assertEqual(first_stage.read_bytes(), expected_stage)
        self.assertTrue((self.root / builder.TRANSACTION_JOURNAL_REL).exists())
        self.assertEqual((self.root / first).read_bytes(), b"foreign")
        (self.root / first).unlink()
        self.assertEqual(
            builder.write_or_check(self.root, outputs, write=True),
            outputs,
        )
        builder.write_or_check(self.root, outputs, write=False)

    def test_journal_unlink_is_final_commit_boundary(self) -> None:
        outputs = builder.build_outputs(self.root)
        source = self.root / self.product_relative
        original_source = source.read_bytes()
        original_unlink = builder._unlink_retained_output_at
        patcher: mock._patch | None = None

        def unlink_then_drift(*args: object, **kwargs: object) -> None:
            original_unlink(*args, **kwargs)
            source.write_bytes(b"drifted-after-final-commit\n")

        def install_unlink_probe() -> None:
            nonlocal patcher
            patcher = mock.patch.object(
                builder,
                "_unlink_retained_output_at",
                side_effect=unlink_then_drift,
            )
            patcher.start()

        try:
            self.assertEqual(
                builder.write_or_check(
                    self.root,
                    outputs,
                    write=True,
                    journal_commit_hook=install_unlink_probe,
                ),
                outputs,
            )
        finally:
            if patcher is not None:
                patcher.stop()
            source.write_bytes(original_source)
        self.assertFalse((self.root / builder.TRANSACTION_JOURNAL_REL).exists())
        self.assertTrue(all((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))
        builder.write_or_check(self.root, outputs, write=False)

    def test_post_unlink_exception_does_not_reverse_commit(self) -> None:
        outputs = builder.build_outputs(self.root)
        original_unlink = builder._unlink_retained_output_at
        patcher: mock._patch | None = None

        def unlink_then_raise(*args: object, **kwargs: object) -> None:
            original_unlink(*args, **kwargs)
            raise OSError(errno.EIO, "simulated exception after journal unlink")

        def install_unlink_probe() -> None:
            nonlocal patcher
            patcher = mock.patch.object(
                builder,
                "_unlink_retained_output_at",
                side_effect=unlink_then_raise,
            )
            patcher.start()

        try:
            self.assertEqual(
                builder.write_or_check(
                    self.root,
                    outputs,
                    write=True,
                    journal_commit_hook=install_unlink_probe,
                ),
                outputs,
            )
        finally:
            if patcher is not None:
                patcher.stop()
        self.assertFalse((self.root / builder.TRANSACTION_JOURNAL_REL).exists())
        self.assertTrue(all((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))
        builder.write_or_check(self.root, outputs, write=False)

    def test_post_commit_close_errors_do_not_reverse_commit_or_skip_closes(self) -> None:
        outputs = builder.build_outputs(self.root)
        original_close = builder._OS_CLOSE
        closed: list[int] = []
        patcher: mock._patch | None = None

        def close_then_raise(descriptor: int) -> None:
            original_close(descriptor)
            closed.append(descriptor)
            raise OSError(errno.EIO, "simulated close error after durable commit")

        def install_close_probe() -> None:
            nonlocal patcher
            patcher = mock.patch.object(
                builder,
                "_OS_CLOSE",
                side_effect=close_then_raise,
            )
            patcher.start()

        try:
            self.assertEqual(
                builder.write_or_check(
                    self.root,
                    outputs,
                    write=True,
                    journal_commit_hook=install_close_probe,
                ),
                outputs,
            )
        finally:
            if patcher is not None:
                patcher.stop()
        self.assertGreaterEqual(len(closed), len(builder.OUTPUT_PATHS) + 1)
        self.assertFalse((self.root / builder.TRANSACTION_JOURNAL_REL).exists())
        self.assertTrue(all((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))
        builder.write_or_check(self.root, outputs, write=False)

    def test_recovery_journal_unlink_is_final_commit_boundary(self) -> None:
        outputs = builder.build_outputs(self.root)
        child = os.fork()
        if child == 0:
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                journal_commit_hook=lambda: os.kill(os.getpid(), signal.SIGKILL),
            )
            os._exit(99)
        _pid, status = os.waitpid(child, 0)
        self.assertTrue(os.WIFSIGNALED(status))

        source = self.root / self.product_relative
        original_source = source.read_bytes()
        original_unlink = builder._unlink_retained_output_at
        original_rename = builder._rename_noreplace_at
        patcher: mock._patch | None = None

        def unlink_then_drift(*args: object, **kwargs: object) -> None:
            original_unlink(*args, **kwargs)
            source.write_bytes(b"drifted-after-recovery-commit\n")

        def rename_and_install(parent_fd: int, staged: str, final: str) -> None:
            nonlocal patcher
            original_rename(parent_fd, staged, final)
            if patcher is None:
                patcher = mock.patch.object(
                    builder,
                    "_unlink_retained_output_at",
                    side_effect=unlink_then_drift,
                )
                patcher.start()

        try:
            self.assertEqual(
                builder.write_or_check(
                    self.root,
                    outputs,
                    write=True,
                    test_rename_noreplace_hook=rename_and_install,
                ),
                outputs,
            )
        finally:
            if patcher is not None:
                patcher.stop()
            source.write_bytes(original_source)
        self.assertFalse((self.root / builder.TRANSACTION_JOURNAL_REL).exists())
        self.assertTrue(all((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))
        builder.write_or_check(self.root, outputs, write=False)

    def test_check_rejects_orphan_output_stage_without_mutating_it(self) -> None:
        outputs = builder.build_outputs(self.root)
        builder.write_or_check(self.root, outputs, write=True)
        first = builder.OUTPUT_PATHS[0]
        staged = self.root / builder._output_stage_relative(first)
        content = outputs[first].encode()
        staged.write_bytes(content)
        staged.chmod(0o600)

        with self.assertRaisesRegex(
            builder.BuildError,
            "unfinished r023 publication transaction exists",
        ):
            builder.write_or_check(self.root, outputs, write=False)
        self.assertEqual(staged.read_bytes(), content)

    def test_existing_outputs_write_cleans_owned_partial_journal_stage(self) -> None:
        outputs = builder.build_outputs(self.root)
        builder.write_or_check(self.root, outputs, write=True)
        journal_stage = self.root / builder.TRANSACTION_JOURNAL_STAGE_REL
        journal_stage.write_bytes(b"partial-uncommitted-journal")
        journal_stage.chmod(0o600)

        self.assertEqual(
            builder.write_or_check(self.root, outputs, write=True),
            outputs,
        )
        self.assertFalse(journal_stage.exists())
        builder.write_or_check(self.root, outputs, write=False)

    def test_committed_recovery_removes_exact_journal_stage_residue(self) -> None:
        outputs = builder.build_outputs(self.root)
        child = os.fork()
        if child == 0:
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                journal_commit_hook=lambda: os.kill(os.getpid(), signal.SIGKILL),
            )
            os._exit(99)
        _pid, status = os.waitpid(child, 0)
        self.assertTrue(os.WIFSIGNALED(status))

        journal = self.root / builder.TRANSACTION_JOURNAL_REL
        journal_stage = self.root / builder.TRANSACTION_JOURNAL_STAGE_REL
        journal_stage.write_bytes(journal.read_bytes())
        journal_stage.chmod(0o600)

        self.assertEqual(
            builder.write_or_check(self.root, outputs, write=True),
            outputs,
        )
        self.assertFalse(journal.exists())
        self.assertFalse(journal_stage.exists())
        self.assertFalse(
            any(
                (self.root / builder._output_stage_relative(relative)).exists()
                for relative in builder.OUTPUT_PATHS
            )
        )
        builder.write_or_check(self.root, outputs, write=False)

    def test_stage_mutate_restore_before_journal_is_rejected_by_identity_cas(self) -> None:
        outputs = builder.build_outputs(self.root)
        first = builder._output_stage_relative(builder.OUTPUT_PATHS[0])

        def mutate_restore_stage() -> None:
            path = self.root / first
            original = path.read_bytes()
            before = path.stat()
            descriptor = os.open(path, os.O_WRONLY | os.O_NOFOLLOW)
            try:
                os.write(descriptor, b"x" * len(original))
                os.lseek(descriptor, 0, os.SEEK_SET)
                os.write(descriptor, original)
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
            os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))

        with self.assertRaisesRegex(builder.BuildError, "stage descriptor identity changed"):
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                before_journal_commit_hook=mutate_restore_stage,
            )
        self.assertFalse((self.root / builder.TRANSACTION_JOURNAL_REL).exists())
        self.assertFalse(any((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))

    def test_same_byte_source_inode_swap_before_journal_is_rejected(self) -> None:
        outputs = builder.build_outputs(self.root)
        source = self.root / self.product_relative

        def replace_source_inode() -> None:
            replacement = source.with_name(f".{source.name}.replacement")
            replacement.write_bytes(source.read_bytes())
            replacement.chmod(source.stat().st_mode & 0o777)
            os.replace(replacement, source)

        with self.assertRaisesRegex(builder.BuildError, "source changed after snapshot"):
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                before_journal_commit_hook=replace_source_inode,
            )
        self.assertFalse((self.root / builder.TRANSACTION_JOURNAL_REL).exists())
        self.assertFalse(any((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))

    def test_root_replacement_before_journal_never_publishes_to_replacement(self) -> None:
        outputs = builder.build_outputs(self.root)
        replacement = self.root.with_name(f"{self.root.name}-replacement")
        parked = self.root.with_name(f"{self.root.name}-parked")
        shutil.copytree(self.root, replacement)

        def replace_root() -> None:
            os.rename(self.root, parked)
            os.rename(replacement, self.root)

        try:
            with self.assertRaisesRegex(builder.BuildError, "repository root path changed"):
                builder.write_or_check(
                    self.root,
                    outputs,
                    write=True,
                    before_journal_commit_hook=replace_root,
                )
            self.assertFalse((self.root / builder.TRANSACTION_JOURNAL_REL).exists())
            self.assertFalse(any((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))
        finally:
            if parked.exists():
                restored_replacement = replacement
                if self.root.exists():
                    os.rename(self.root, restored_replacement)
                os.rename(parked, self.root)
                shutil.rmtree(restored_replacement)

    def test_same_byte_final_inode_replacement_is_rejected_by_final_cohort(self) -> None:
        outputs = builder.build_outputs(self.root)
        first = self.root / builder.OUTPUT_PATHS[0]

        def replace_first_after_later_publish(index: int, _relative: Path) -> None:
            if index != 1:
                return
            replacement = first.with_name(f".{first.name}.replacement")
            replacement.write_bytes(outputs[builder.OUTPUT_PATHS[0]].encode())
            replacement.chmod(0o600)
            os.replace(replacement, first)

        with self.assertRaisesRegex(builder.BuildError, "file path identity differs|output"):
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                publish_hook=replace_first_after_later_publish,
            )
        self.assertTrue((self.root / builder.TRANSACTION_JOURNAL_REL).exists())

    def test_source_and_output_reads_are_bounded_before_allocation(self) -> None:
        source = self.root / builder.R021_GAP_REL
        with source.open("r+b") as stream:
            stream.truncate(builder.MAXIMUM_SOURCE_BYTES + 1)
        with self.assertRaisesRegex(builder.BuildError, "file exceeds byte limit"):
            builder.build_outputs(self.root)

    def test_snapshot_open_identity_failure_does_not_leak_descriptor(self) -> None:
        before = len(list(Path("/proc/self/fd").iterdir()))
        real_identity = builder._directory_identity
        calls = 0

        def mismatch_second(info: os.stat_result) -> tuple[int, ...]:
            nonlocal calls
            calls += 1
            identity = real_identity(info)
            if calls == 2:
                return (*identity[:-1], identity[-1] + 1)
            return identity

        with mock.patch.object(
            builder,
            "_directory_identity",
            side_effect=mismatch_second,
        ):
            with self.assertRaisesRegex(builder.BuildError, "ancestor changed"):
                builder.RepositorySnapshot(self.root)
        self.assertEqual(len(list(Path("/proc/self/fd").iterdir())), before)

    def test_snapshot_root_fstat_failure_does_not_leak_descriptors(self) -> None:
        before = len(list(Path("/proc/self/fd").iterdir()))
        real_fstat = builder._OS_FSTAT
        calls = 0
        final_root_call = len(self.root.parts)

        def fail_final_root(descriptor: int) -> os.stat_result:
            nonlocal calls
            calls += 1
            if calls == final_root_call:
                raise OSError(errno.EIO, "simulated root fstat failure")
            return real_fstat(descriptor)

        with mock.patch.object(builder, "_OS_FSTAT", side_effect=fail_final_root):
            with self.assertRaisesRegex(OSError, "simulated root fstat failure"):
                builder.RepositorySnapshot(self.root)
        self.assertEqual(calls, final_root_call)
        self.assertEqual(len(list(Path("/proc/self/fd").iterdir())), before)

    def test_snapshot_directory_fstat_failure_does_not_leak_descriptor(self) -> None:
        with builder.RepositorySnapshot(self.root) as snapshot:
            before = len(list(Path("/proc/self/fd").iterdir()))
            with mock.patch.object(
                builder,
                "_OS_FSTAT",
                side_effect=OSError(errno.EIO, "simulated directory fstat failure"),
            ):
                with self.assertRaisesRegex(
                    OSError,
                    "simulated directory fstat failure",
                ):
                    snapshot.directory_fd(Path("docs"))
            self.assertEqual(len(list(Path("/proc/self/fd").iterdir())), before)

    def test_snapshot_short_read_failure_does_not_leak_descriptor(self) -> None:
        with builder.RepositorySnapshot(self.root) as snapshot:
            snapshot.directory_fd(builder.R021_GAP_REL.parent)
            before = len(list(Path("/proc/self/fd").iterdir()))
            with mock.patch.object(builder, "_OS_READ", return_value=b""):
                with self.assertRaisesRegex(
                    builder.BuildError,
                    "source byte length differs",
                ):
                    snapshot.read(builder.R021_GAP_REL)
            self.assertEqual(len(list(Path("/proc/self/fd").iterdir())), before)

    def test_snapshot_file_registration_failure_does_not_leak_descriptor(self) -> None:
        class FailingDict(dict):
            def __setitem__(self, key: object, value: object) -> None:
                raise MemoryError("simulated file registration failure")

        with builder.RepositorySnapshot(self.root) as snapshot:
            snapshot.directory_fd(builder.R021_GAP_REL.parent)
            snapshot._files = FailingDict()
            before = len(list(Path("/proc/self/fd").iterdir()))
            with self.assertRaisesRegex(MemoryError, "file registration failure"):
                snapshot.read(builder.R021_GAP_REL)
            self.assertEqual(len(list(Path("/proc/self/fd").iterdir())), before)

    def test_snapshot_entry_construction_failure_does_not_leak_descriptor(self) -> None:
        with builder.RepositorySnapshot(self.root) as snapshot:
            snapshot.directory_fd(builder.R021_GAP_REL.parent)
            before = len(list(Path("/proc/self/fd").iterdir()))
            original_identity = builder._identity
            calls = 0

            def fail_retained_entry(info: os.stat_result) -> tuple[int, ...]:
                nonlocal calls
                calls += 1
                if calls == 7:
                    raise MemoryError("simulated retained entry construction failure")
                return original_identity(info)

            with mock.patch.object(
                builder,
                "_identity",
                side_effect=fail_retained_entry,
            ):
                with self.assertRaisesRegex(
                    MemoryError,
                    "retained entry construction failure",
                ):
                    snapshot.read(builder.R021_GAP_REL)
            self.assertEqual(calls, 7)
            self.assertNotIn(builder.R021_GAP_REL, snapshot._files)
            self.assertEqual(len(list(Path("/proc/self/fd").iterdir())), before)

    def test_snapshot_directory_registration_failure_does_not_leak_descriptor(self) -> None:
        class FailingDict(dict):
            def __setitem__(self, key: object, value: object) -> None:
                raise MemoryError("simulated directory registration failure")

        with builder.RepositorySnapshot(self.root) as snapshot:
            snapshot._directories = FailingDict(snapshot._directories)
            before = len(list(Path("/proc/self/fd").iterdir()))
            with self.assertRaisesRegex(MemoryError, "directory registration failure"):
                snapshot.directory_fd(Path("docs"))
            self.assertNotIn(("docs",), snapshot._directory_links)
            self.assertEqual(len(list(Path("/proc/self/fd").iterdir())), before)

    def test_owned_descriptor_append_failure_closes_descriptor(self) -> None:
        class FailingList(list):
            def append(self, value: object) -> None:
                raise MemoryError("simulated descriptor append failure")

        descriptor = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
        with self.assertRaisesRegex(MemoryError, "descriptor append failure"):
            builder._append_owned_descriptor(FailingList(), descriptor, descriptor)
        with self.assertRaises(OSError):
            os.fstat(descriptor)

    def test_opened_directory_identity_failure_does_not_leak_descriptor(self) -> None:
        before = len(list(Path("/proc/self/fd").iterdir()))
        real_identity = builder._directory_identity
        calls = 0

        def mismatch_second(info: os.stat_result) -> tuple[int, ...]:
            nonlocal calls
            calls += 1
            identity = real_identity(info)
            if calls == 2:
                return (*identity[:-1], identity[-1] + 1)
            return identity

        with mock.patch.object(
            builder,
            "_directory_identity",
            side_effect=mismatch_second,
        ):
            with self.assertRaisesRegex(builder.BuildError, "directory changed"):
                with builder._opened_directory(self.root, Path("docs")):
                    pass
        self.assertEqual(len(list(Path("/proc/self/fd").iterdir())), before)

    def test_unsafe_stage_mode_is_rejected_before_publish(self) -> None:
        outputs = builder.build_outputs(self.root)

        def tamper_first_stage(index: int, relative: Path) -> None:
            if index == 0:
                (self.root / relative).chmod(0o666)

        with self.assertRaisesRegex(builder.BuildError, "unsafe stage file"):
            builder.write_or_check(
                self.root,
                outputs,
                write=True,
                stage_write_hook=tamper_first_stage,
            )
        self.assertFalse(any((self.root / relative).exists() for relative in builder.OUTPUT_PATHS))

    def test_rejects_implementation_content_drift(self) -> None:
        path = self.root / self.product_relative
        path.write_text("FP048_INTERNAL_CONTROL = False\n", encoding="utf-8")
        with self.assertRaisesRegex(builder.BuildError, "implementation file differs"):
            builder.build_outputs(self.root)

    def test_rejects_duplicate_json_members(self) -> None:
        path = self.root / builder.VERIFICATION_REL
        text = path.read_text()
        path.write_text(
            text.replace(
                '"status": "PASS",',
                '"status": "PASS",\n  "status": "PASS",',
                1,
            )
        )
        stderr = StringIO()
        with redirect_stderr(stderr):
            result = builder.main(["--root", str(self.root), "--check"])
        self.assertEqual(result, 1)
        self.assertIn("duplicate JSON member", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
