from __future__ import annotations

import base64
import copy
from contextlib import ExitStack, redirect_stderr, redirect_stdout
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
import os
import pickle
from pathlib import Path
import shutil
import signal
import stat
import tempfile
import threading
import unittest
from unittest.mock import patch

from scripts import build_walksafe_fp048_artifact_trace_successor_20260802 as artifact_builder
from scripts import build_walksafe_fp048_encryption_connection_security_incident_trace_20260802 as trace_builder
from scripts import build_walksafe_fp048_gap_backlog_r023_20260802 as r023_builder
from scripts import build_walksafe_phase1_exact257_successor_r012_20260802 as builder


def _json_bytes(value: object) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            indent=2,
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def _json(value: bytes) -> dict:
    loaded = json.loads(value)
    assert isinstance(loaded, dict)
    return loaded


def _write(root: Path, relative: Path, content: bytes) -> Path:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return path


def _write_json(root: Path, relative: Path, value: object) -> bytes:
    content = _json_bytes(value)
    _write(root, relative, content)
    return content


def _copy_r011(root: Path) -> None:
    for relative in builder.R011_PINNED_SHA256_BY_RELATIVE_PATH:
        source = builder.REPO_ROOT / relative
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)


def _copy_repository_file(root: Path, relative: Path) -> None:
    source = builder.REPO_ROOT / relative
    assert source.is_file(), relative
    target = _write(root, relative, source.read_bytes())
    target.chmod(stat.S_IMODE(source.stat().st_mode))


def _authority_documents() -> dict[str, object]:
    repository_state_bytes = (
        builder.REPO_ROOT / trace_builder.START_GATE_REPOSITORY_STATE_REL
    ).read_bytes()
    repository_state = json.loads(repository_state_bytes)
    assert isinstance(repository_state, dict)
    repository_state_sha256 = hashlib.sha256(repository_state_bytes).hexdigest()
    start_receipt_bytes = (
        builder.REPO_ROOT / trace_builder.START_GATE_RECEIPT_REL
    ).read_bytes()
    start_receipt = json.loads(start_receipt_bytes)
    assert isinstance(start_receipt, dict)
    start_receipt_sha256 = hashlib.sha256(start_receipt_bytes).hexdigest()
    checkpoint = json.loads(
        (builder.REPO_ROOT / trace_builder.CHECKPOINT_REL).read_text(encoding="utf-8")
    )
    history = checkpoint["goal_execution"]["transition_history"]
    matches = [
        item
        for item in history
        if item.get("sequence") == trace_builder.EXPECTED_EXECUTION_EVENT_SEQUENCE
        or item.get("event_id") == trace_builder.EXPECTED_EXECUTION_EVENT_ID
    ]
    assert len(matches) == 1
    event = matches[0]
    return {
        "repository_state": repository_state,
        "repository_state_bytes": repository_state_bytes,
        "repository_state_sha256": repository_state_sha256,
        "start_receipt": start_receipt,
        "start_receipt_bytes": start_receipt_bytes,
        "start_receipt_sha256": start_receipt_sha256,
        "event": event,
        "checkpoint": checkpoint,
    }


TRACE_AUTHORITY = _authority_documents()
TRACE_START = datetime(2026, 8, 2, 22, 0, tzinfo=timezone(timedelta(hours=9)))


def _android_junit_lines() -> list[str]:
    counts = [8] * 14 + [7] * 5
    lines: list[str] = []
    for class_name, count in zip(
        trace_builder.EXPECTED_ANDROID_JUNIT_CLASSES,
        counts,
        strict=True,
    ):
        children = "".join(
            f'<testcase classname="{class_name}" name="case{case}"></testcase>'
            for case in range(count)
        )
        raw = (
            f'<testsuite name="{class_name}" tests="{count}" failures="0" '
            f'errors="0" skipped="0">{children}</testsuite>'
        ).encode()
        relative = (
            "apps/android/app/build/test-results/testDebugUnitTest/"
            f"TEST-{class_name}.xml"
        )
        lines.append(
            f"ANDROID_JUNIT_XML_BYTES path={relative} bytes={len(raw)} "
            f"sha256={trace_builder.bytes_sha256(raw)} "
            f"base64={base64.b64encode(raw).decode()}"
        )
    return lines


def _raw_lane_output(spec: trace_builder.LaneSpec) -> list[str]:
    if spec.lane_id == "ANDROID_PROTECTED_STORAGE":
        host_passed = dict(spec.exact_markers)[
            "WALKSAFE_ANDROID_HOST_PYTEST_PASSED"
        ]
        return [
            "> Task :app:testDebugUnitTest",
            "BUILD SUCCESSFUL in 1s",
            *_android_junit_lines(),
            f"{host_passed} passed in 1.00s",
            "> Task :app:compileDebugAndroidTestKotlin",
            "> Task :app:assembleDebug",
            "> Task :app:lintDebug",
            "BUILD SUCCESSFUL in 1s",
        ]
    if spec.lane_id == "HTTPS_NO_DOWNGRADE":
        return [
            "> Task :app:testDebugUnitTest",
            "BUILD SUCCESSFUL in 1s",
            "1..77",
            "# tests 77",
            "# suites 0",
            "# pass 77",
            "# fail 0",
            "# cancelled 0",
            "# skipped 0",
            "# todo 0",
        ]
    if spec.lane_id == "SERVER_ORIGINAL_DB_BACKUP_BOUNDARY":
        return [
            "441 passed, 11 skipped in 1.00s",
            "38 passed in 1.00s",
            "64 passed in 1.00s",
        ]
    if spec.lane_id == "KEY_LIFECYCLE_ORIGINAL_ACCESS":
        return ["254 passed, 11 skipped in 1.00s"]
    return ["64 passed in 1.00s"]


def _lane_log_bytes(
    spec: trace_builder.LaneSpec,
    content_set_sha256: str,
    verification_input_content_set_sha256: str,
    index: int,
    authority: dict[str, object],
) -> bytes:
    started = TRACE_START + timedelta(minutes=index * 2)
    ended = started + timedelta(minutes=1)
    event = authority["event"]
    assert isinstance(event, dict)
    lines = [
        f"WALKSAFE_EXECUTION_EVENT_SEQUENCE={event['sequence']}",
        f"WALKSAFE_EXECUTION_EVENT_ID={event['event_id']}",
        f"WALKSAFE_EXECUTION_EVENT_SHA256={event['event_sha256']}",
        f"WALKSAFE_IMPLEMENTATION_CONTENT_SET_SHA256={content_set_sha256}",
        "WALKSAFE_VERIFICATION_INPUT_CONTENT_SET_SHA256="
        f"{verification_input_content_set_sha256}",
        f"WALKSAFE_RUN_ID=fp048-r012-fixture-{index:02d}",
        f"WALKSAFE_COMMAND_SHA256={trace_builder.bytes_sha256(spec.command.encode())}",
        f"WALKSAFE_COMMAND_STARTED_AT={started.isoformat()}",
        f"WALKSAFE_LANE_ID={spec.lane_id}",
        "WALKSAFE_LANE_STATUS=PASS",
        trace_builder.RAW_OUTPUT_BEGIN,
        *_raw_lane_output(spec),
        trace_builder.RAW_OUTPUT_END,
        *(f"{name}={value}" for name, value in spec.exact_markers),
        f"WALKSAFE_COMMAND_ENDED_AT={ended.isoformat()}",
        "WALKSAFE_COMMAND_EXIT_CODE=0",
    ]
    return ("\n".join(lines) + "\n").encode()


def _make_fixture(
    root: Path,
    *,
    authority: dict[str, object] = TRACE_AUTHORITY,
) -> dict[Path, str]:
    _copy_r011(root)
    copy_paths = (
        trace_builder.GOAL_REL,
        *map(Path, trace_builder.IMPLEMENTATION_PATHS),
        *trace_builder.VERIFICATION_INPUT_PATHS,
        r023_builder.R021_GAP_REL,
        r023_builder.R021_BACKLOG_REL,
        r023_builder.BUILDER_REL,
        r023_builder.BUILDER_TEST_REL,
        artifact_builder.BUILDER_REL,
        *artifact_builder.OUTPUT_PATHS,
    )
    for relative in dict.fromkeys(copy_paths):
        _copy_repository_file(root, relative)

    _write(
        root,
        trace_builder.START_GATE_REPOSITORY_STATE_REL,
        authority["repository_state_bytes"],
    )
    _write(
        root,
        trace_builder.START_GATE_RECEIPT_REL,
        authority["start_receipt_bytes"],
    )
    _write_json(root, trace_builder.CHECKPOINT_REL, authority["checkpoint"])

    production_implementation = json.loads(
        (builder.REPO_ROOT / trace_builder.IMPLEMENTATION_REL).read_text(
            encoding="utf-8"
        )
    )
    implementation_rows = deepcopy(production_implementation["changed_artifacts"])
    assert len(implementation_rows) == 106
    assert [row["path"] for row in implementation_rows] == list(
        trace_builder.IMPLEMENTATION_PATHS
    )
    assert r023_builder.object_sha256(
        [
            {
                "path": row["path"],
                "before_sha256": row["before_sha256"],
                "before_source": row["before_source"],
                "change_kind": row["change_kind"],
            }
            for row in implementation_rows
        ]
    ) == r023_builder.EXPECTED_IMPLEMENTATION_BEFORE_PROJECTION_SHA256
    for row in implementation_rows:
        assert hashlib.sha256((root / row["path"]).read_bytes()).hexdigest() == row[
            "after_sha256"
        ]
    content_set_sha256 = trace_builder.implementation_content_set(implementation_rows)
    _, verification_input_content_set_sha256 = trace_builder.verification_input_manifest(root)
    log_snapshots: dict[str, bytes] = {}
    for index, spec in enumerate(trace_builder.LANES):
        log_bytes = _lane_log_bytes(
            spec,
            content_set_sha256,
            verification_input_content_set_sha256,
            index,
            authority,
        )
        path = _write(
            root,
            spec.log_rel,
            log_bytes,
        )
        path.chmod(0o600)
        log_snapshots[spec.lane_id] = log_bytes

    trace_outputs = trace_builder.build_pre_review_outputs(
        root=root,
        authority=trace_builder.validate_authority(root),
        implementation_rows=implementation_rows,
        log_snapshots=log_snapshots,
    )
    trace_builder.write_or_check_outputs(root, trace_outputs, write=True)

    r023_outputs = r023_builder.build_outputs(root)
    r023_builder.write_or_check(root, r023_outputs, write=True)

    # The production six-document cohort binds the final exact R023/trace
    # inputs. Overlay that retained 127-file cohort before replaying its
    # already-published six successors.
    r023_cohort = tuple(
        dict.fromkeys(
            (
                *r023_builder._direct_source_relatives(),
                r023_builder.CHECKPOINT_REL,
                *map(Path, trace_builder.IMPLEMENTATION_PATHS),
                *trace_builder.VERIFICATION_INPUT_PATHS,
                *(spec.log_rel for spec in trace_builder.LANES),
                *r023_builder.OUTPUT_PATHS,
            )
        )
    )
    for relative in r023_cohort:
        _copy_repository_file(root, relative)

    r023_gap = json.loads((root / r023_builder.R023_GAP_JSON_REL).read_text())
    for binding in r023_gap["source_bindings"]:
        relative = Path(binding["path"])
        if not (root / relative).exists():
            _copy_repository_file(root, relative)

    artifact_input_pins = {
        artifact_builder.IMPLEMENTATION_REL: hashlib.sha256(
            (root / artifact_builder.IMPLEMENTATION_REL).read_bytes()
        ).hexdigest(),
        artifact_builder.VERIFICATION_REL: hashlib.sha256(
            (root / artifact_builder.VERIFICATION_REL).read_bytes()
        ).hexdigest(),
        artifact_builder.GAP_REL: hashlib.sha256(
            (root / artifact_builder.GAP_REL).read_bytes()
        ).hexdigest(),
    }
    artifact_builder.write_successor(
        root,
        expected_input_sha256=artifact_input_pins,
        expected_document_ids={
            artifact_builder.IMPLEMENTATION_REL: (
                "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-"
                "IMPLEMENTATION-20260802-001"
            ),
            artifact_builder.VERIFICATION_REL: (
                "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-"
                "VERIFICATION-20260802-001"
            ),
        },
    )

    pins = {
        builder.IMPLEMENTATION_REL: hashlib.sha256(
            (root / builder.IMPLEMENTATION_REL).read_bytes()
        ).hexdigest(),
        builder.VERIFICATION_REL: hashlib.sha256(
            (root / builder.VERIFICATION_REL).read_bytes()
        ).hexdigest(),
        builder.R023_GAP_REL: hashlib.sha256(
            (root / builder.R023_GAP_REL).read_bytes()
        ).hexdigest(),
    }
    for _, relative in builder.TARGET_ARTIFACT_PATHS:
        pins[relative] = hashlib.sha256((root / relative).read_bytes()).hexdigest()
    return pins


def _make_skeletal_fixture(
    root: Path,
    *,
    implementation_path: str = "apps/android/app/src/main/java/Fp048.kt",
) -> dict[Path, str]:
    _copy_r011(root)

    implementation_rows = [
        {
            "path": implementation_path,
            "after_sha256": "1" * 64,
            "before_sha256": "2" * 64,
            "before_source": "TEST_FIXTURE",
            "change_kind": "MODIFIED",
        }
    ]
    implementation_content_set = builder._object_sha(
        [
            {
                "path": row["path"],
                "sha256": row["after_sha256"],
            }
            for row in implementation_rows
        ]
    )
    implementation = {
        "schema_version": "1.0",
        "document_id": (
            "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-"
            "IMPLEMENTATION-20260802-001"
        ),
        "goal_id": builder.GOAL_ID,
        "kind": "IMPLEMENTATION_RECORD",
        "status": "PASS",
        "implementation_content_set_sha256": implementation_content_set,
        "changed_artifacts": implementation_rows,
        "evidence_boundary": deepcopy(builder.EXPECTED_RESULT_BOUNDARY),
    }
    implementation_bytes = _write_json(
        root,
        builder.IMPLEMENTATION_REL,
        implementation,
    )
    implementation_sha = hashlib.sha256(implementation_bytes).hexdigest()

    verification = {
        "schema_version": "1.0",
        "document_id": (
            "WS-FP048-ENCRYPTION-CONNECTION-SECURITY-"
            "VERIFICATION-20260802-001"
        ),
        "goal_id": builder.GOAL_ID,
        "kind": "VERIFICATION_RESULT",
        "status": "PASS",
        "implementation_content_set_sha256": implementation_content_set,
        "checks": [
            {
                "name": "fixture-internal-check",
                "exit_code": 0,
                "implementation_content_set_sha256": implementation_content_set,
                "output_path": "evidence/fp048/internal-check.log",
                "output_sha256": "3" * 64,
            }
        ],
        "evidence_boundary": deepcopy(builder.EXPECTED_RESULT_BOUNDARY),
    }
    verification_bytes = _write_json(
        root,
        builder.VERIFICATION_REL,
        verification,
    )
    verification_sha = hashlib.sha256(verification_bytes).hexdigest()

    reassessment = {
        "goal_id": builder.GOAL_ID,
        "implementation_record_sha256": implementation_sha,
        "verification_result_sha256": verification_sha,
        "internal_implementation_status": "PASS",
        "internal_verification_status": "PASS",
        **deepcopy(builder.EXPECTED_R023_BOUNDARY),
    }
    gap = {
        "metadata": {"report_id": builder.R023_REPORT_ID},
        "source_bindings": [
            {"path": builder.IMPLEMENTATION_REL.as_posix()},
            {"path": builder.VERIFICATION_REL.as_posix()},
        ],
        "assessments": [
            {
                "gap_id": "GAP-057",
                "source_policy_id": "FP-048",
                "status": "PARTIAL",
                "formal_test_status": "NOT_RUN",
                "fp048_reassessment": reassessment,
            }
        ],
        "evidence_catalog": [
            {
                "evidence_id": builder.R023_EVIDENCE_ID,
                "producer_goal_id": builder.GOAL_ID,
                "result_evidence_sha256": {
                    "IMPLEMENTATION_RECORD": implementation_sha,
                    "VERIFICATION_RESULT": verification_sha,
                },
                "formal_test_status": "NOT_RUN",
                "actual_device_status": "NOT_RUN",
                "external_verification_status": "NOT_RUN",
                "release_status": "NOT_ELIGIBLE",
            }
        ],
        "summary": {"release_status": "NOT_ELIGIBLE"},
        "authorization_boundary": {
            "formal_test_completion_claimed": False,
            "artifact_approval_claimed": False,
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
    }
    gap["report_content_sha256"] = builder._object_sha(gap)
    gap_bytes = _write_json(root, builder.R023_GAP_REL, gap)
    gap_sha = hashlib.sha256(gap_bytes).hexdigest()

    producer_bindings = [
        {
            "name": "fp048_implementation_result",
            "path": builder.IMPLEMENTATION_REL.as_posix(),
            "sha256": implementation_sha,
            "byte_length": len(implementation_bytes),
            "relation": "INTERNAL_IMPLEMENTATION_RESULT",
        },
        {
            "name": "fp048_verification_result",
            "path": builder.VERIFICATION_REL.as_posix(),
            "sha256": verification_sha,
            "byte_length": len(verification_bytes),
            "relation": "INTERNAL_VERIFICATION_RESULT",
        },
        {
            "name": "fp048_gap057_r023_successor",
            "path": builder.R023_GAP_REL.as_posix(),
            "sha256": gap_sha,
            "byte_length": len(gap_bytes),
            "relation": "GAP057_R023_SUCCESSOR",
        },
    ]
    seal_keys = {
        builder.TARGET_ARTIFACT_PATHS[0][1]: "content_sha256",
        builder.TARGET_ARTIFACT_PATHS[1][1]: "content_sha256",
        builder.TARGET_ARTIFACT_PATHS[2][1]: "document_content_sha256",
        builder.TARGET_ARTIFACT_PATHS[3][1]: "register_content_sha256",
        builder.TARGET_ARTIFACT_PATHS[4][1]: "fp048_successor_content_sha256",
        builder.TARGET_ARTIFACT_PATHS[5][1]: "fp048_successor_content_sha256",
    }
    pins = {
        builder.IMPLEMENTATION_REL: implementation_sha,
        builder.VERIFICATION_REL: verification_sha,
        builder.R023_GAP_REL: gap_sha,
    }
    for artifact_id, relative in builder.TARGET_ARTIFACT_PATHS:
        document = {
            "schema_version": "test.walksafe.fp048-artifact-successor.v1",
            "artifact_type_code": artifact_id,
            "fp048_artifact_trace_successor": {
                "successor_id": builder.FP048_SUCCESSOR_ID,
                "prepared_on": builder.PREPARED_ON,
                "predecessor": {
                    "path": relative.as_posix(),
                    "sha256": builder.TARGET_PREDECESSOR_SHA256[relative],
                },
                "input_bindings": deepcopy(producer_bindings),
                "claim_boundary": deepcopy(
                    builder.EXPECTED_DOCUMENT_TRACE_BOUNDARY
                ),
            },
        }
        seal_key = seal_keys[relative]
        document[seal_key] = builder._object_sha(document)
        content = _write_json(root, relative, document)
        pins[relative] = hashlib.sha256(content).hexdigest()
    return pins


def _clone_fixture(source: Path, target: Path) -> None:
    shutil.copytree(source, target, dirs_exist_ok=True)


def _private_transaction_case(
    root: Path,
) -> tuple[Path, Path, Path, dict[Path, bytes]]:
    parent = root / "packets"
    parent.mkdir()
    packet = parent / "r012"
    transaction = parent / ".r012.r012-transaction"
    outputs = {
        packet / "ledger.json": b"ledger\n",
        packet / "evidence.json": b"evidence\n",
        packet / "receipt.json": b"receipt\n",
    }
    return parent, packet, transaction, outputs


def _fork_sigkill_write(
    testcase: unittest.TestCase,
    root: Path,
    outputs: dict[Path, bytes],
    patchers,
    transaction_hook=None,
) -> None:
    child = os.fork()
    if child == 0:
        try:
            with ExitStack() as stack:
                for patcher in patchers:
                    stack.enter_context(patcher)
                builder.write_add_only(
                    outputs,
                    allowed_root=root,
                    _transaction_hook=transaction_hook,
                )
        except BaseException:
            os._exit(86)
        os._exit(87)
    _pid, status = os.waitpid(child, 0)
    testcase.assertTrue(os.WIFSIGNALED(status), status)
    testcase.assertEqual(os.WTERMSIG(status), signal.SIGKILL)


def _capture_tree_state(root: Path) -> dict[str, tuple[object, ...]]:
    state: dict[str, tuple[object, ...]] = {}
    for path in (root, *sorted(root.rglob("*"))):
        relative = "." if path == root else path.relative_to(root).as_posix()
        info = path.lstat()
        if stat.S_ISDIR(info.st_mode):
            state[relative] = (
                "directory",
                info.st_dev,
                info.st_ino,
                info.st_mode,
                info.st_uid,
                info.st_gid,
            )
        elif stat.S_ISREG(info.st_mode):
            state[relative] = (
                "file",
                info.st_dev,
                info.st_ino,
                info.st_mode,
                info.st_nlink,
                path.read_bytes(),
            )
        else:
            state[relative] = ("other", info.st_dev, info.st_ino, info.st_mode)
    return state


def _owned_snapshot_fds(snapshot: builder._RetainedSnapshot) -> tuple[int, ...]:
    return tuple(
        dict.fromkeys(
            (
                *snapshot._directories.values(),
                *snapshot._outer_descriptors,
            )
        )
    )


def _leading_slash_alias(path: Path, count: int = 2) -> Path:
    return Path("/" * count + str(path).lstrip("/"))


class WalkSafePhase1Exact257SuccessorR012Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        cls.pins = _make_fixture(cls.root)
        cls.outputs = builder.build_outputs(
            cls.root,
            expected_source_sha256=cls.pins,
        )
        cls.predecessor = json.loads(
            (cls.root / builder.R011_LEDGER_REL).read_text(encoding="utf-8")
        )
        cls.ledger = _json(cls.outputs[cls.root / builder.R012_LEDGER_REL])
        cls.evidence = _json(cls.outputs[cls.root / builder.R012_EVIDENCE_REL])
        cls.receipt = _json(cls.outputs[cls.root / builder.R012_RECEIPT_REL])

    @classmethod
    def tearDownClass(cls) -> None:
        if isinstance(cls.outputs, builder._SourceBoundOutputs):
            cls.outputs.close()
        cls.temporary.cleanup()

    def test_build_is_deterministic_sealed_and_exact_three(self) -> None:
        expected_paths = (
            self.root / builder.R012_LEDGER_REL,
            self.root / builder.R012_EVIDENCE_REL,
            self.root / builder.R012_RECEIPT_REL,
        )
        self.assertEqual(tuple(self.outputs), expected_paths)
        rebuilt = builder.build_outputs(
            self.root,
            expected_source_sha256=self.pins,
        )
        try:
            self.assertEqual(self.outputs, rebuilt)
        finally:
            assert isinstance(rebuilt, builder._SourceBoundOutputs)
            rebuilt.close()
        for path, content in self.outputs.items():
            self.assertTrue(content.endswith(b"\n"), path)
            self.assertFalse(content.endswith(b"\n\n"), path)
            builder._validate_nonself(_json(content), path, root=self.root)

    def test_fixture_uses_full_trace_r023_and_six_document_producers(self) -> None:
        implementation = json.loads(
            (self.root / builder.IMPLEMENTATION_REL).read_text(encoding="utf-8")
        )
        verification = json.loads(
            (self.root / builder.VERIFICATION_REL).read_text(encoding="utf-8")
        )
        self.assertEqual(
            [row["path"] for row in implementation["changed_artifacts"]],
            list(trace_builder.IMPLEMENTATION_PATHS),
        )
        self.assertEqual(
            implementation["exact_path_count"],
            len(trace_builder.IMPLEMENTATION_PATHS),
        )
        self.assertEqual(
            [row["lane_id"] for row in verification["checks"]],
            [spec.lane_id for spec in trace_builder.LANES],
        )
        self.assertEqual(verification["internal_lane_count"], 5)
        manifest_by_lane = {
            row["lane_id"]: row for row in verification["lane_receipts"]
        }
        for spec, check in zip(
            trace_builder.LANES,
            verification["checks"],
            strict=True,
        ):
            self.assertEqual(check["command"], spec.command)
            log_path = self.root / spec.log_rel
            log_bytes = log_path.read_bytes()
            self.assertEqual(check["output_path"], spec.log_rel.as_posix())
            self.assertEqual(
                check["output_sha256"],
                hashlib.sha256(log_bytes).hexdigest(),
            )
            self.assertIn(trace_builder.RAW_OUTPUT_BEGIN.encode(), log_bytes)
            self.assertIn(trace_builder.RAW_OUTPUT_END.encode(), log_bytes)
            self.assertEqual(stat.S_IMODE(log_path.stat().st_mode), 0o600)

            receipt_path = self.root / spec.receipt_rel
            receipt_bytes = receipt_path.read_bytes()
            receipt = json.loads(receipt_bytes)
            receipt_seal = receipt.pop("receipt_content_sha256")
            self.assertEqual(receipt_seal, trace_builder.object_sha256(receipt))
            self.assertEqual(receipt["command_execution"], check)
            self.assertEqual(stat.S_IMODE(receipt_path.stat().st_mode), 0o600)
            self.assertEqual(
                manifest_by_lane[spec.lane_id]["sha256"],
                hashlib.sha256(receipt_bytes).hexdigest(),
            )

        event = TRACE_AUTHORITY["event"]
        self.assertIsInstance(event, dict)
        assert isinstance(event, dict)
        self.assertEqual(
            implementation["execution_session_event"]["event_sha256"],
            event["event_sha256"],
        )
        self.assertEqual(
            trace_builder.object_sha256(
                {key: value for key, value in event.items() if key != "event_sha256"}
            ),
            event["event_sha256"],
        )

        expected_r023 = r023_builder.build_outputs(self.root)
        self.assertEqual(tuple(expected_r023), r023_builder.OUTPUT_PATHS)
        self.assertEqual(len(expected_r023), 4)
        for relative, expected in expected_r023.items():
            self.assertEqual((self.root / relative).read_bytes(), expected.encode())

        artifact_input_pins = {
            artifact_builder.IMPLEMENTATION_REL: self.pins[builder.IMPLEMENTATION_REL],
            artifact_builder.VERIFICATION_REL: self.pins[builder.VERIFICATION_REL],
            artifact_builder.GAP_REL: self.pins[builder.R023_GAP_REL],
        }
        actual_six = artifact_builder.check_successor(
            self.root,
            expected_input_sha256=artifact_input_pins,
            expected_document_ids={
                artifact_builder.IMPLEMENTATION_REL: implementation["document_id"],
                artifact_builder.VERIFICATION_REL: verification["document_id"],
            },
        )
        self.assertEqual(tuple(actual_six), artifact_builder.OUTPUT_PATHS)
        self.assertEqual(len(actual_six), 6)
        for relative, raw in actual_six.items():
            self.assertEqual(raw, (self.root / relative).read_bytes())

        before_projection = [
            {
                "path": row["path"],
                "before_sha256": row["before_sha256"],
                "before_source": row["before_source"],
                "change_kind": row["change_kind"],
            }
            for row in implementation["changed_artifacts"]
        ]
        self.assertEqual(len(before_projection), 106)
        self.assertEqual(
            r023_builder.object_sha256(before_projection),
            r023_builder.EXPECTED_IMPLEMENTATION_BEFORE_PROJECTION_SHA256,
        )
        r023_cohort = tuple(
            dict.fromkeys(
                (
                    *r023_builder._direct_source_relatives(),
                    r023_builder.CHECKPOINT_REL,
                    *map(Path, trace_builder.IMPLEMENTATION_PATHS),
                    *trace_builder.VERIFICATION_INPUT_PATHS,
                    *(spec.log_rel for spec in trace_builder.LANES),
                )
            )
        )
        self.assertEqual(len(r023_cohort), 127)
        self.assertTrue(all((self.root / relative).is_file() for relative in r023_cohort))
        self.assertFalse((self.root / ".git").exists())

        with tempfile.TemporaryDirectory() as temporary:
            production_root = Path(temporary)
            production_pins = _make_fixture(production_root)
            self.assertFalse((production_root / ".git").exists())
            production_outputs = builder.build_outputs(
                production_root,
                expected_source_sha256=production_pins,
            )
            self.assertEqual(len(production_outputs), 3)
            assert isinstance(production_outputs, builder._SourceBoundOutputs)
            production_outputs.close()

    def test_skeletal_self_consistent_sources_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            pins = _make_skeletal_fixture(root)
            with self.assertRaisesRegex(
                builder.ValidationError,
                "exact implementation row count differs",
            ):
                builder.build_outputs(root, expected_source_sha256=pins)

    def test_only_exact_six_records_gain_three_progress_bindings(self) -> None:
        before = self.predecessor["records"]
        after = self.ledger["records"]
        self.assertEqual(len(before), 257)
        self.assertEqual(len(after), 257)
        self.assertEqual(
            [row["artifact_type_code"] for row in before],
            [row["artifact_type_code"] for row in after],
        )
        changed: list[str] = []
        for predecessor_row, successor_row in zip(before, after, strict=True):
            artifact_id = predecessor_row["artifact_type_code"]
            if artifact_id not in builder.TARGET_ARTIFACT_ID_SET:
                self.assertEqual(successor_row, predecessor_row, artifact_id)
                continue
            changed.append(artifact_id)
            stripped = deepcopy(successor_row)
            content = stripped["progress_axes"]["content_authored"][
                "observations"
            ].pop()
            materialization = stripped["progress_axes"][
                "packet_materialization"
            ].pop()
            validation = stripped["progress_axes"]["internal_validation"][
                "observations"
            ].pop()
            self.assertEqual(stripped, predecessor_row, artifact_id)
            self.assertFalse(content["artifact_content_accepted"])
            self.assertFalse(content["owner_approved"])
            self.assertFalse(content["completion_claimed"])
            self.assertFalse(content["state_promotion"])
            self.assertFalse(materialization["state_promotion"])
            self.assertFalse(materialization["completion_claimed"])
            self.assertFalse(validation["state_promotion"])
            self.assertFalse(validation["completion_claimed"])
            self.assertEqual(validation["credit_count"], 0)
        self.assertEqual(set(changed), builder.TARGET_ARTIFACT_ID_SET)
        self.assertEqual(
            changed,
            [
                row["artifact_type_code"]
                for row in before
                if row["artifact_type_code"] in builder.TARGET_ARTIFACT_ID_SET
            ],
        )

    def test_queue_summary_authorization_and_zero_credit_are_preserved(self) -> None:
        self.assertEqual(
            self.ledger["summaries"],
            self.predecessor["summaries"],
        )
        self.assertEqual(
            self.ledger["authorization_boundary"],
            self.predecessor["authorization_boundary"],
        )
        for before, after in zip(
            self.predecessor["records"],
            self.ledger["records"],
            strict=True,
        ):
            self.assertEqual(after["queue_route"], before["queue_route"])
            self.assertEqual(
                after["release_eligibility"],
                before["release_eligibility"],
            )
        self.assertEqual(
            self.ledger["r012_fp048_artifact_progress_application"][
                "zero_credits"
            ],
            builder.ZERO_CREDITS,
        )
        self.assertEqual(
            self.evidence["formal_device_external_release_boundary"],
            {
                "formal_test_status": "NOT_RUN",
                "formal279_pass_count": 0,
                "actual_device_status": "NOT_RUN",
                "actual_device_event_count": 0,
                "external_evidence_status": "NOT_RUN",
                "verified_rights_or_external_fact_count": 0,
                "release_gate_status": "NOT_RUN",
                "release_gates_waived": False,
                "release_status": "NOT_ELIGIBLE",
                "release_eligible_count": 0,
            },
        )
        self.assertEqual(self.receipt["summary"]["zero_credits"], builder.ZERO_CREDITS)

    def test_exact_nine_source_allowlist_and_output_chain(self) -> None:
        expected_source_paths = {
            builder.IMPLEMENTATION_REL.as_posix(),
            builder.VERIFICATION_REL.as_posix(),
            builder.R023_GAP_REL.as_posix(),
            *(path.as_posix() for _, path in builder.TARGET_ARTIFACT_PATHS),
        }
        bindings = self.evidence["source_bindings"]
        self.assertEqual(len(bindings), 9)
        self.assertEqual({item["path"] for item in bindings}, expected_source_paths)
        self.assertEqual(self.ledger["r012_source_bindings"], bindings)
        self.assertEqual(
            self.ledger["r012_predecessor_packet_bindings"],
            self.evidence["predecessor_packet_bindings"],
        )
        self.assertEqual(
            self.evidence["source_allowlist"],
            {
                "new_source_count": 9,
                "implementation_result_count": 1,
                "verification_result_count": 1,
                "r023_gap_count": 1,
                "physical_document_count": 6,
                "review_subject_count": 0,
                "review_attestation_count": 0,
                "independent_review_count": 0,
                "completion_receipt_count": 0,
                "legacy_web_pwa_docx_pptx_count": 0,
            },
        )
        for item in bindings:
            relative = Path(item["path"])
            content = (self.root / relative).read_bytes()
            self.assertEqual(item["sha256"], self.pins[relative])
            self.assertEqual(item["byte_length"], len(content))
        ledger_bytes = self.outputs[self.root / builder.R012_LEDGER_REL]
        evidence_bytes = self.outputs[self.root / builder.R012_EVIDENCE_REL]
        self.assertEqual(
            self.evidence["subject_chain"]["r012_ledger"]["sha256"],
            hashlib.sha256(ledger_bytes).hexdigest(),
        )
        by_path = {item["path"]: item for item in self.receipt["output_bindings"]}
        self.assertEqual(
            by_path[builder.R012_LEDGER_REL.as_posix()]["sha256"],
            hashlib.sha256(ledger_bytes).hexdigest(),
        )
        self.assertEqual(
            by_path[builder.R012_EVIDENCE_REL.as_posix()]["sha256"],
            hashlib.sha256(evidence_bytes).hexdigest(),
        )
        self.assertEqual(self.receipt["status"], "PASS")
        self.assertEqual(len(self.receipt["checks"]), 8)
        self.assertTrue(all(row["status"] == "PASS" for row in self.receipt["checks"]))

    def test_production_pins_build_read_only_without_materializing_r012(self) -> None:
        self.assertFalse(builder.R012_PACKET_DIR.exists())
        self.assertEqual(
            builder._effective_source_pins(builder.REPO_ROOT),
            builder.PINNED_SOURCE_SHA256_BY_RELATIVE_PATH,
        )
        for relative, expected in builder.PINNED_SOURCE_SHA256_BY_RELATIVE_PATH.items():
            self.assertEqual(
                hashlib.sha256((builder.REPO_ROOT / relative).read_bytes()).hexdigest(),
                expected,
            )
        with self.assertRaisesRegex(builder.ValidationError, "test-only"):
            builder._effective_source_pins(builder.REPO_ROOT, self.pins)
        with self.assertRaisesRegex(builder.ValidationError, "test-only"):
            builder.build_outputs(
                builder.REPO_ROOT,
                expected_source_sha256=self.pins,
            )
        production_outputs = builder.build_outputs(builder.REPO_ROOT)
        try:
            self.assertEqual(
                tuple(production_outputs),
                (
                    builder.R012_LEDGER_PATH,
                    builder.R012_EVIDENCE_PATH,
                    builder.R012_RECEIPT_PATH,
                ),
            )
            self.assertFalse(builder.R012_PACKET_DIR.exists())
        finally:
            assert isinstance(production_outputs, builder._SourceBoundOutputs)
            production_outputs.close()
        with redirect_stderr(io.StringIO()):
            self.assertEqual(builder.main(["--check"]), 1)
        self.assertFalse(builder.R012_PACKET_DIR.exists())
        self.assertFalse(
            (
                builder.R012_PACKET_DIR.parent
                / f".{builder.R012_PACKET_DIR.name}.r012-transaction"
            ).exists()
        )

    def test_forbidden_implementation_scope_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pins = _make_skeletal_fixture(
                root,
                implementation_path="apps/web/fp048.ts",
            )
            with self.assertRaises(builder.ValidationError):
                builder.build_outputs(root, expected_source_sha256=pins)

        for path in (
            "pwa/fp048.ts",
            "review/completion.json",
            "evidence/review_subject.json",
            "evidence/review-attestation.json",
            "evidence/independent_review.json",
            "evidence/completion-receipt.json",
            "legacy/fp048.json",
            "submission/fp048.docx",
            "slides/fp048.PPTX",
        ):
            with self.subTest(path=path):
                self.assertTrue(builder._forbidden_new_source_path(path))

    def test_stale_pin_projection_drift_hardlink_and_symlink_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            pins = dict(self.pins)
            stale = dict(pins)
            stale[builder.IMPLEMENTATION_REL] = "0" * 64
            with self.assertRaises(builder.ValidationError):
                builder.build_outputs(root, expected_source_sha256=stale)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            pins = dict(self.pins)
            relative = builder.TARGET_ARTIFACT_PATHS[0][1]
            document = json.loads((root / relative).read_text(encoding="utf-8"))
            document["unsealed_mutation"] = True
            content = _write_json(root, relative, document)
            pins[relative] = hashlib.sha256(content).hexdigest()
            with self.assertRaises(builder.ValidationError):
                builder.build_outputs(root, expected_source_sha256=pins)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            pins = dict(self.pins)
            implementation_source = root / trace_builder.IMPLEMENTATION_PATHS[0]
            os.link(implementation_source, root / "implementation-hardlink.kt")
            with self.assertRaisesRegex(builder.ValidationError, "hard-linked"):
                builder.build_outputs(root, expected_source_sha256=pins)

    def test_snapshot_requires_nofollow_authority_and_final_source_cas(self) -> None:
        for flag in ("O_NOFOLLOW", "O_DIRECTORY"):
            with self.subTest(flag=flag), patch.object(builder.os, flag, 0):
                with self.assertRaisesRegex(builder.ValidationError, "required OS open flag"):
                    builder._RetainedSnapshot(self.root)

        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            real_root = base / "real-root"
            real_root.mkdir()
            linked_root = base / "linked-root"
            linked_root.symlink_to(real_root, target_is_directory=True)
            with self.assertRaises(builder.ValidationError):
                builder._RetainedSnapshot(linked_root)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            pins = dict(self.pins)
            source = root / builder.R011_LEDGER_REL
            source.chmod(0o666)
            with self.assertRaisesRegex(builder.ValidationError, "world-writable"):
                builder.build_outputs(root, expected_source_sha256=pins)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            pins = dict(self.pins)
            current_uid = os.geteuid()
            with patch.object(builder.os, "geteuid", return_value=current_uid + 1):
                with self.assertRaisesRegex(builder.ValidationError, "owner differs"):
                    builder.build_outputs(root, expected_source_sha256=pins)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            pins = dict(self.pins)
            source = root / builder.R011_LEDGER_REL
            original = builder._validate_trace_authenticity

            def mutate_after_capture(candidate_root: Path, captured) -> None:
                original(candidate_root, captured)
                source.chmod(0o600)

            with patch.object(
                builder,
                "_validate_trace_authenticity",
                side_effect=mutate_after_capture,
            ):
                with self.assertRaisesRegex(builder.ValidationError, "changed after snapshot"):
                    builder.build_outputs(root, expected_source_sha256=pins)

        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            candidate_root = base / "candidate"
            clean_root = base / "clean"
            _clone_fixture(self.root, candidate_root)
            _clone_fixture(self.root, clean_root)
            pins = dict(self.pins)
            source = candidate_root / trace_builder.IMPLEMENTATION_PATHS[0]
            original_content = source.read_bytes()
            source.write_bytes(
                bytes([original_content[0] ^ 1]) + original_content[1:]
            )
            original = builder._validate_trace_authenticity
            swapped = False

            def swap_original_root_during_full_builder(
                producer_root: Path,
                captured,
            ) -> None:
                nonlocal swapped
                if swapped:
                    return original(producer_root, captured)
                swapped = True
                self.assertNotEqual(producer_root, candidate_root)
                retained_root = base / "candidate-retained"
                candidate_root.rename(retained_root)
                clean_root.rename(candidate_root)
                try:
                    return original(producer_root, captured)
                finally:
                    candidate_root.rename(clean_root)
                    retained_root.rename(candidate_root)

            with patch.object(
                builder,
                "_validate_trace_authenticity",
                side_effect=swap_original_root_during_full_builder,
            ):
                with self.assertRaisesRegex(
                    builder.ValidationError,
                    "r023 full-builder validation failed: implementation file differs",
                ):
                    builder.build_outputs(
                        candidate_root,
                        expected_source_sha256=pins,
                    )
            self.assertTrue(swapped)

        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            candidate_root = base / "candidate"
            replacement_root = base / "replacement"
            _clone_fixture(self.root, candidate_root)
            _clone_fixture(self.root, replacement_root)
            pins = dict(self.pins)
            original = builder._validate_trace_authenticity
            swapped = False

            def swap_clean_root_and_restore(
                producer_root: Path,
                captured,
            ) -> None:
                nonlocal swapped
                if swapped:
                    return original(producer_root, captured)
                swapped = True
                self.assertNotEqual(producer_root, candidate_root)
                retained_root = base / "candidate-retained"
                candidate_root.rename(retained_root)
                replacement_root.rename(candidate_root)
                try:
                    return original(producer_root, captured)
                finally:
                    candidate_root.rename(replacement_root)
                    retained_root.rename(candidate_root)

            with patch.object(
                builder,
                "_validate_trace_authenticity",
                side_effect=swap_clean_root_and_restore,
            ):
                with self.assertRaisesRegex(
                    builder.ValidationError,
                    "snapshot root changed|root ancestor changed",
                ):
                    builder.build_outputs(
                        candidate_root,
                        expected_source_sha256=pins,
                    )
            self.assertTrue(swapped)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            pins = dict(self.pins)
            source = root / builder.R011_LEDGER_REL
            original_content = source.read_bytes()
            original_info = source.stat()
            original = builder._validate_six_document_builder_semantics

            def mutate_same_inode_and_restore_size_mtime(
                candidate_root: Path,
                captured,
                expected_source_sha256,
            ) -> None:
                original(candidate_root, captured, expected_source_sha256)
                replacement = bytes([original_content[0] ^ 1]) + original_content[1:]
                source.write_bytes(replacement)
                os.utime(
                    source,
                    ns=(original_info.st_atime_ns, original_info.st_mtime_ns),
                )
                mutated_info = source.stat()
                self.assertEqual(mutated_info.st_ino, original_info.st_ino)
                self.assertEqual(mutated_info.st_size, original_info.st_size)
                self.assertEqual(mutated_info.st_mtime_ns, original_info.st_mtime_ns)

            with patch.object(
                builder,
                "_validate_six_document_builder_semantics",
                side_effect=mutate_same_inode_and_restore_size_mtime,
            ):
                with self.assertRaisesRegex(builder.ValidationError, "changed after snapshot"):
                    builder.build_outputs(root, expected_source_sha256=pins)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            pins = dict(self.pins)
            relative = builder.TARGET_ARTIFACT_PATHS[0][1]
            document = json.loads((root / relative).read_text(encoding="utf-8"))
            document.pop("content_sha256")
            document["review_subject"] = {
                "path": "reviews/review-subject.json",
                "status": "PASS",
            }
            document["content_sha256"] = builder._object_sha(document)
            content = _write_json(root, relative, document)
            pins[relative] = hashlib.sha256(content).hexdigest()
            with self.assertRaises(builder.ValidationError):
                builder.build_outputs(root, expected_source_sha256=pins)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            pins = dict(self.pins)
            source = root / builder.TARGET_ARTIFACT_PATHS[1][1]
            os.link(source, root / "hardlink-alias.json")
            with self.assertRaises(builder.ValidationError):
                builder.build_outputs(root, expected_source_sha256=pins)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            pins = dict(self.pins)
            source = root / builder.IMPLEMENTATION_REL
            alias_target = root / "implementation-source.json"
            source.rename(alias_target)
            source.symlink_to(alias_target)
            with self.assertRaises(builder.ValidationError):
                builder.build_outputs(root, expected_source_sha256=pins)

    def test_unrelated_outer_ancestor_churn_does_not_break_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "candidate"
            _clone_fixture(self.root, root)
            pins = dict(self.pins)
            begin_churn = threading.Event()
            changed = threading.Event()
            stop_churn = threading.Event()
            failures: list[BaseException] = []
            sibling = base / "unrelated-sibling-churn"

            def churn_outer_ancestor() -> None:
                try:
                    if not begin_churn.wait(timeout=10):
                        raise AssertionError("snapshot churn was never requested")
                    while not stop_churn.is_set():
                        sibling.mkdir()
                        sibling.rmdir()
                        changed.set()
                        stop_churn.wait(0.001)
                except BaseException as exc:
                    failures.append(exc)

            original = builder._validate_trace_authenticity

            def validate_while_ancestor_churns(
                candidate_root: Path,
                captured,
            ) -> None:
                begin_churn.set()
                self.assertTrue(changed.wait(timeout=10))
                original(candidate_root, captured)

            worker = threading.Thread(target=churn_outer_ancestor, daemon=True)
            worker.start()
            try:
                with patch.object(
                    builder,
                    "_validate_trace_authenticity",
                    side_effect=validate_while_ancestor_churns,
                ):
                    outputs = builder.build_outputs(
                        root,
                        expected_source_sha256=pins,
                    )
            finally:
                stop_churn.set()
                begin_churn.set()
                worker.join(timeout=10)
            self.assertFalse(worker.is_alive())
            self.assertEqual(failures, [])
            self.assertEqual(
                tuple(outputs),
                (
                    root / builder.R012_LEDGER_REL,
                    root / builder.R012_EVIDENCE_REL,
                    root / builder.R012_RECEIPT_REL,
                ),
            )
            if isinstance(outputs, builder._SourceBoundOutputs):
                outputs.close()

    def test_build_source_and_root_authority_remain_bound_through_write(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            pins = dict(self.pins)
            outputs = builder.build_outputs(root, expected_source_sha256=pins)
            source = root / builder.TARGET_ARTIFACT_PATHS[0][1]
            source.write_bytes(source.read_bytes() + b" ")
            try:
                with self.assertRaisesRegex(
                    builder.ValidationError,
                    "changed after snapshot",
                ):
                    builder.write_add_only(outputs, allowed_root=root)
                self.assertFalse((root / builder.R012_PACKET_DIR_REL).exists())
                self.assertFalse(
                    (
                        root
                        / builder.R012_PACKET_DIR_REL.parent
                        / f".{builder.R012_PACKET_DIR_REL.name}.r012-transaction"
                    ).exists()
                )
            finally:
                assert isinstance(outputs, builder._SourceBoundOutputs)
                outputs.close()

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            pins = dict(self.pins)
            outputs = builder.build_outputs(root, expected_source_sha256=pins)
            first_path = next(iter(outputs))
            original = outputs[first_path]

            def mutate_bound_mapping(phase: str) -> None:
                if phase == "journal_durable":
                    outputs[first_path] = b"forged\n"

            try:
                with self.assertRaisesRegex(
                    builder.ValidationError,
                    "bound output mapping changed",
                ):
                    builder.write_add_only(
                        outputs,
                        allowed_root=root,
                        _transaction_hook=mutate_bound_mapping,
                    )
                self.assertFalse((root / builder.R012_PACKET_DIR_REL).exists())
                transaction = (
                    root
                    / builder.R012_PACKET_DIR_REL.parent
                    / f".{builder.R012_PACKET_DIR_REL.name}.r012-transaction"
                )
                self.assertTrue(transaction.is_dir())
                self.assertEqual(list(transaction.iterdir()), [])
                outputs[first_path] = original
                builder.write_add_only(outputs, allowed_root=root)
                builder.check_outputs(outputs, allowed_root=root)
            finally:
                outputs[first_path] = original
                assert isinstance(outputs, builder._SourceBoundOutputs)
                outputs.close()

        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "candidate"
            replacement = base / "replacement"
            retained = base / "candidate-retained"
            _clone_fixture(self.root, root)
            _clone_fixture(self.root, replacement)
            pins = dict(self.pins)
            outputs = builder.build_outputs(root, expected_source_sha256=pins)
            root.rename(retained)
            replacement.rename(root)
            try:
                with self.assertRaisesRegex(
                    builder.ValidationError,
                    "snapshot root changed|root ancestor changed",
                ):
                    builder.write_add_only(outputs, allowed_root=root)
                self.assertFalse((root / builder.R012_PACKET_DIR_REL).exists())
            finally:
                assert isinstance(outputs, builder._SourceBoundOutputs)
                outputs.close()
                root.rename(replacement)
                retained.rename(root)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            pins = dict(self.pins)
            outputs = builder.build_outputs(root, expected_source_sha256=pins)
            try:
                builder.write_add_only(outputs, allowed_root=root)
                builder.check_outputs(outputs, allowed_root=root)
                self.assertEqual(
                    set((root / builder.R012_PACKET_DIR_REL).iterdir()),
                    set(outputs),
                )
            finally:
                assert isinstance(outputs, builder._SourceBoundOutputs)
                outputs.close()

    def test_all_snapshot_reads_are_bounded(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first"
            second = root / "second"
            first.write_bytes(b"1234")
            second.write_bytes(b"5678")
            with builder._RetainedSnapshot(
                root,
                maximum_file_bytes=4,
                maximum_total_bytes=6,
            ) as snapshot:
                self.assertEqual(snapshot.read(Path("first")), b"1234")
                with self.assertRaisesRegex(builder.ValidationError, "total byte cap"):
                    snapshot.read(Path("second"))

            oversized = root / "oversized"
            oversized.write_bytes(b"12345")
            with builder._RetainedSnapshot(
                root,
                maximum_file_bytes=4,
                maximum_total_bytes=8,
            ) as snapshot:
                with self.assertRaisesRegex(builder.ValidationError, "file exceeds byte cap"):
                    snapshot.read(Path("oversized"))

            descriptor = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
            try:
                oversized.chmod(0o600)
                with self.assertRaisesRegex(builder.ValidationError, "file exceeds byte cap"):
                    builder._read_regular_at(
                        descriptor,
                        oversized.name,
                        "oversized transaction file",
                        maximum_bytes=4,
                    )
                first.chmod(0o600)
                second.chmod(0o600)
                budget = builder._ReadBudget(6)
                self.assertEqual(
                    builder._read_regular_at(
                        descriptor,
                        first.name,
                        "first bounded transaction file",
                        maximum_bytes=4,
                        budget=budget,
                    ),
                    b"1234",
                )
                with self.assertRaisesRegex(
                    builder.ValidationError,
                    "aggregate read byte cap",
                ):
                    builder._read_regular_at(
                        descriptor,
                        second.name,
                        "second bounded transaction file",
                        maximum_bytes=4,
                        budget=budget,
                    )
            finally:
                os.close(descriptor)

            retained_directory = root / "retained-directory"
            retained_directory.mkdir()
            retained_source = retained_directory / "retained-source"
            retained_source.write_bytes(b"1234")
            retained_source.chmod(0o600)
            descriptor = os.open(
                retained_directory,
                os.O_RDONLY | os.O_DIRECTORY,
            )
            retained_outputs = {retained_source: b"1234"}
            try:
                with (
                    builder._DescriptorOwner() as owner,
                    patch.object(builder.os, "read", return_value=b"12345"),
                ):
                    with self.assertRaisesRegex(
                        builder.ValidationError,
                        "file exceeds byte cap",
                    ):
                        builder._retain_output_files(
                            descriptor,
                            retained_outputs,
                            owner=owner,
                        )

                with builder._DescriptorOwner() as owner:
                    retained = builder._retain_output_files(
                        descriptor,
                        retained_outputs,
                        owner=owner,
                    )
                    with patch.object(builder.os, "read", return_value=b"12345"):
                        with self.assertRaisesRegex(
                            builder.ValidationError,
                            "retained R012 output verify exceeds byte cap",
                        ):
                            builder._verify_retained_output_files(
                                descriptor,
                                retained,
                            )

                exhausted = builder._ReadBudget(3)
                with patch.object(builder.os, "read") as bounded_read:
                    with self.assertRaisesRegex(
                        builder.ValidationError,
                        "aggregate read byte cap",
                    ):
                        builder._read_regular_at(
                            descriptor,
                            retained_source.name,
                            "pre-read aggregate cap",
                            maximum_bytes=4,
                            budget=exhausted,
                        )
                    bounded_read.assert_not_called()
            finally:
                os.close(descriptor)

    def test_descriptor_failure_paths_close_every_opened_fd(self) -> None:
        def fd_count() -> int:
            return len(os.listdir("/proc/self/fd"))

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            before = fd_count()
            with patch.object(
                builder.os,
                "fstat",
                side_effect=OSError("injected snapshot fstat failure"),
            ):
                with self.assertRaises(builder.ValidationError):
                    builder._RetainedSnapshot(root)
            self.assertEqual(fd_count(), before)

            child = root / "child"
            child.mkdir(mode=0o700)
            snapshot = builder._RetainedSnapshot(root)
            try:
                before = fd_count()
                with patch.object(
                    builder.os,
                    "fstat",
                    side_effect=OSError("injected child fstat failure"),
                ):
                    with self.assertRaises(OSError):
                        snapshot.directory_fd(child)
                self.assertEqual(fd_count(), before)
            finally:
                snapshot.close()

            short_file = root / "short-file"
            short_file.write_bytes(b"not-empty")
            short_file.chmod(0o600)
            snapshot = builder._RetainedSnapshot(root)
            try:
                before = fd_count()
                with patch.object(builder.os, "read", return_value=b""):
                    with self.assertRaisesRegex(
                        builder.ValidationError,
                        "short snapshot read",
                    ):
                        snapshot.read(short_file, required_mode=0o600)
                self.assertEqual(fd_count(), before)
            finally:
                snapshot.close()

            parent_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
            try:
                before = fd_count()
                with patch.object(
                    builder.os,
                    "fstat",
                    side_effect=OSError("injected open-directory fstat failure"),
                ):
                    with self.assertRaises(OSError):
                        builder._open_directory_at(
                            parent_fd,
                            child.name,
                            "injected child",
                            required_mode=0o700,
                        )
                self.assertEqual(fd_count(), before)

                before = fd_count()
                with patch.object(builder.os, "read", return_value=b""):
                    with self.assertRaisesRegex(
                        builder.ValidationError,
                        "short regular-file read",
                    ):
                        builder._read_regular_at(
                            parent_fd,
                            short_file.name,
                            "injected short file",
                        )
                self.assertEqual(fd_count(), before)
            finally:
                os.close(parent_fd)

    def test_resealed_overclaim_output_is_rejected(self) -> None:
        state = builder._load_source_state(self.root, self.pins)
        mutated = dict(self.outputs)
        ledger_path = self.root / builder.R012_LEDGER_REL
        ledger = _json(mutated[ledger_path])
        target = next(
            row
            for row in ledger["records"]
            if row["artifact_type_code"] == builder.TARGET_ARTIFACT_IDS[0]
        )
        target["release_eligibility"]["eligible"] = True
        mutated[ledger_path] = builder._seal_json(
            ledger,
            ledger_path,
            root=self.root,
        )
        with self.assertRaisesRegex(
            builder.ValidationError,
            "non-progress delta",
        ):
            builder._validate_generated_outputs(mutated, state)

    def test_atomic_add_only_write_check_and_idempotent_republication(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            parent = root / "packets"
            parent.mkdir()
            packet = parent / "r012"
            outputs = {
                packet / "ledger.json": b"ledger\n",
                packet / "evidence.json": b"evidence\n",
                packet / "receipt.json": b"receipt\n",
            }
            builder.write_add_only(outputs, allowed_root=root)
            before = {}
            for path, expected in outputs.items():
                info = path.stat()
                self.assertEqual(path.read_bytes(), expected)
                self.assertEqual(stat.S_IMODE(info.st_mode), 0o600)
                self.assertEqual(info.st_nlink, 1)
                before[path] = (info.st_mtime_ns, info.st_size)
            builder.check_outputs(outputs, allowed_root=root)
            self.assertEqual(
                before,
                {
                    path: (path.stat().st_mtime_ns, path.stat().st_size)
                    for path in outputs
                },
            )
            mode_attacked = packet / "evidence.json"
            mode_attacked.chmod(0o666)
            with self.assertRaisesRegex(
                builder.ValidationError,
                "world-writable|file mode differs",
            ):
                builder.check_outputs(outputs, allowed_root=root)
            mode_attacked.chmod(0o600)
            (packet / "unexpected-review.json").write_bytes(b"unexpected\n")
            with self.assertRaises(builder.ValidationError):
                builder.check_outputs(outputs, allowed_root=root)
            (packet / "unexpected-review.json").unlink()
            builder.write_add_only(outputs, allowed_root=root)
            self.assertEqual(
                before,
                {
                    path: (path.stat().st_mtime_ns, path.stat().st_size)
                    for path in outputs
                },
            )
            self.assertFalse((parent / ".r012.r012-transaction").exists())

    def test_aliases_and_production_root_rebasing_fail_before_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            official = root / builder.R012_PACKET_DIR_REL
            official.parent.mkdir(parents=True)
            canonical = {
                root / builder.R012_LEDGER_REL: b"ledger\n",
                root / builder.R012_EVIDENCE_REL: b"evidence\n",
                root / builder.R012_RECEIPT_REL: b"receipt\n",
            }
            alias_directory = official / ".." / official.name
            aliases = {
                alias_directory / path.name: content
                for path, content in canonical.items()
            }
            mixed = dict(canonical)
            mixed.pop(next(iter(mixed)))
            mixed[alias_directory / next(iter(canonical)).name] = b"ledger\n"
            duplicate = {
                next(iter(canonical)): b"ledger\n",
                alias_directory / next(iter(canonical)).name: b"ledger\n",
            }
            relative = {
                builder.R012_LEDGER_REL: b"ledger\n",
                builder.R012_EVIDENCE_REL: b"evidence\n",
                builder.R012_RECEIPT_REL: b"receipt\n",
            }
            hook_calls: list[str] = []
            for label, candidate in (
                ("dotdot", aliases),
                ("mixed", mixed),
                ("duplicate", duplicate),
                ("relative", relative),
            ):
                with self.subTest(label=label), self.assertRaisesRegex(
                    builder.ValidationError,
                    "canonical absolute",
                ):
                    builder.write_add_only(
                        candidate,
                        allowed_root=root,
                        _transaction_hook=hook_calls.append,
                    )
            private_packet = root / "packets/private"
            private_outputs = {private_packet / "ledger.json": b"ledger\n"}
            reserved_alias = private_packet / ".." / "reserved"
            with self.assertRaisesRegex(
                builder.ValidationError,
                "reserved path must be canonical absolute",
            ):
                builder.write_add_only(
                    private_outputs,
                    allowed_root=root,
                    reserved_absent_paths=(reserved_alias,),
                    _transaction_hook=hook_calls.append,
                )
            for label, descendant_outputs in (
                (
                    "one-level",
                    {official / "nested" / "forbidden.json": b"forbidden\n"},
                ),
                (
                    "two-level",
                    {
                        official / "nested/deeper/forbidden.json": b"forbidden\n"
                    },
                ),
                (
                    "mixed-direct-descendant",
                    {
                        next(iter(canonical)): b"ledger\n",
                        official / "nested/forbidden.json": b"forbidden\n",
                    },
                ),
            ):
                with self.subTest(descendant=label), self.assertRaisesRegex(
                    builder.ValidationError,
                    "official R012 publication requires",
                ):
                    builder.write_add_only(
                        descendant_outputs,
                        allowed_root=root,
                        _transaction_hook=hook_calls.append,
                    )
            self.assertEqual(hook_calls, [])
            self.assertFalse(official.exists())
            self.assertFalse(
                (official.parent / f".{official.name}.r012-transaction").exists()
            )

        production_paths = (
            builder.R012_LEDGER_PATH,
            builder.R012_EVIDENCE_PATH,
            builder.R012_RECEIPT_PATH,
        )
        production_outputs = {
            path: f"forbidden-{index}\n".encode()
            for index, path in enumerate(production_paths)
        }
        nested_production_outputs = {
            builder.R012_PACKET_DIR / "nested" / "forbidden.json": b"forbidden\n"
        }
        deeper_production_outputs = {
            builder.R012_PACKET_DIR
            / "nested/deeper/forbidden.json": b"forbidden\n"
        }
        mixed_production_outputs = {
            production_paths[0]: b"forbidden-ledger\n",
            builder.R012_PACKET_DIR / "nested/forbidden.json": b"forbidden\n",
        }
        production_transaction = (
            builder.R012_PACKET_DIR.parent
            / f".{builder.R012_PACKET_DIR.name}.r012-transaction"
        )
        self.assertFalse(builder.R012_PACKET_DIR.exists())
        self.assertFalse(production_transaction.exists())
        hook_calls = []
        for writer in (
            lambda: builder.write_add_only(
                production_outputs,
                allowed_root=builder.REPO_ROOT.parent,
                _transaction_hook=hook_calls.append,
            ),
            lambda: builder._write_add_only_transaction(
                production_outputs,
                allowed_root=builder.REPO_ROOT.parent,
                _transaction_hook=hook_calls.append,
            ),
            lambda: builder.write_add_only(
                nested_production_outputs,
                allowed_root=builder.REPO_ROOT.parent,
                _transaction_hook=hook_calls.append,
            ),
            lambda: builder._write_add_only_transaction(
                nested_production_outputs,
                allowed_root=builder.REPO_ROOT.parent,
                _transaction_hook=hook_calls.append,
            ),
            lambda: builder.write_add_only(
                deeper_production_outputs,
                allowed_root=builder.REPO_ROOT.parent,
                _transaction_hook=hook_calls.append,
            ),
            lambda: builder._write_add_only_transaction(
                mixed_production_outputs,
                allowed_root=builder.REPO_ROOT.parent,
                _transaction_hook=hook_calls.append,
            ),
        ):
            with self.assertRaisesRegex(
                builder.ValidationError,
                "production R012 publication requires",
            ):
                writer()
        self.assertEqual(hook_calls, [])
        self.assertFalse(builder.R012_PACKET_DIR.exists())
        self.assertFalse(production_transaction.exists())

    def test_embedded_nul_paths_fail_before_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            initial = _capture_tree_state(root)
            hook_calls: list[str] = []
            nul_root = Path(f"{root}\x00suffix")
            elided_nul_root = root / "gone\x00component" / ".."
            cases = (
                (
                    "root",
                    {nul_root / "packet/output.json": b"output\n"},
                    nul_root,
                    (),
                    "allowed root",
                ),
                (
                    "root-dotdot-elision",
                    {elided_nul_root / "packet/output.json": b"output\n"},
                    elided_nul_root,
                    (),
                    "allowed root",
                ),
                (
                    "output",
                    {root / "packet/bad\x00suffix": b"output\n"},
                    root,
                    (),
                    "output path",
                ),
                (
                    "output-dotdot-elision",
                    {
                        root
                        / "packet/gone\x00component/../output.json": b"output\n"
                    },
                    root,
                    (),
                    "output path",
                ),
                (
                    "reserved",
                    {root / "packet/output.json": b"output\n"},
                    root,
                    (root / "reserved\x00suffix",),
                    "reserved path",
                ),
                (
                    "reserved-dotdot-elision",
                    {root / "packet/output.json": b"output\n"},
                    root,
                    (root / "gone\x00component/../reserved",),
                    "reserved path",
                ),
            )
            for label, outputs, allowed_root, reserved, error in cases:
                with self.subTest(label=label), self.assertRaisesRegex(
                    builder.ValidationError,
                    rf"{error} contains an embedded NUL path component",
                ):
                    builder.write_add_only(
                        outputs,
                        allowed_root=allowed_root,
                        reserved_absent_paths=reserved,
                        _transaction_hook=hook_calls.append,
                    )
                self.assertEqual(_capture_tree_state(root), initial)
            self.assertEqual(hook_calls, [])

    def test_hidden_directory_state_a_fresh_exact_publication(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, packet, transaction, outputs = _private_transaction_case(root)
            phases: list[str] = []
            transaction_inode: tuple[int, int] | None = None

            def observe(phase: str) -> None:
                nonlocal transaction_inode
                phases.append(phase)
                if phase == "before_rename":
                    info = transaction.stat()
                    transaction_inode = (info.st_dev, info.st_ino)

            builder.write_add_only(outputs, allowed_root=root, _transaction_hook=observe)
            self.assertEqual(
                phases,
                [
                    "journal_durable",
                    "staged_output_0",
                    "staged_output_1",
                    "staged_output_2",
                    "before_rename",
                    "after_rename",
                    "after_parent_fsync",
                    "before_journal_cleanup",
                ],
            )
            self.assertFalse(transaction.exists())
            self.assertEqual(stat.S_IMODE(packet.stat().st_mode), 0o700)
            self.assertEqual(
                (packet.stat().st_dev, packet.stat().st_ino),
                transaction_inode,
            )
            for path, expected in outputs.items():
                self.assertEqual(path.read_bytes(), expected)
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
                self.assertEqual(path.stat().st_nlink, 1)

    def test_hidden_directory_state_b_recovers_every_exact_subset(self) -> None:
        for mask in range(8):
            with self.subTest(mask=mask), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                _parent, packet, transaction, outputs = _private_transaction_case(root)
                transaction.mkdir(mode=0o700)
                retained_inodes: dict[Path, tuple[int, int]] = {}
                for index, (path, content) in enumerate(outputs.items()):
                    if mask & (1 << index):
                        staged = transaction / path.name
                        staged.write_bytes(content)
                        staged.chmod(0o600)
                        info = staged.stat()
                        retained_inodes[path] = (info.st_dev, info.st_ino)
                builder.write_add_only(outputs, allowed_root=root)
                builder.check_outputs(outputs, allowed_root=root)
                self.assertFalse(transaction.exists())
                for path, identity in retained_inodes.items():
                    self.assertEqual((path.stat().st_dev, path.stat().st_ino), identity)

    def test_hidden_directory_state_c_whole_directory_rollback_and_retry(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, packet, transaction, outputs = _private_transaction_case(root)
            transaction.mkdir(mode=0o700)
            for path, content in outputs.items():
                staged = transaction / path.name
                staged.write_bytes(content)
                staged.chmod(0o600)
            transaction_identity = (transaction.stat().st_dev, transaction.stat().st_ino)

            def fail_after_rename(phase: str) -> None:
                if phase == "after_rename":
                    raise RuntimeError("publication primary")

            with self.assertRaisesRegex(RuntimeError, "publication primary"):
                builder.write_add_only(
                    outputs,
                    allowed_root=root,
                    _transaction_hook=fail_after_rename,
                )
            self.assertFalse(packet.exists())
            self.assertEqual(
                (transaction.stat().st_dev, transaction.stat().st_ino),
                transaction_identity,
            )
            builder.write_add_only(outputs, allowed_root=root)
            self.assertEqual(
                (packet.stat().st_dev, packet.stat().st_ino),
                transaction_identity,
            )

    def test_hidden_directory_state_d_reverifies_exact_final(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, packet, transaction, outputs = _private_transaction_case(root)
            builder.write_add_only(outputs, allowed_root=root)
            before = {
                path: (path.stat().st_dev, path.stat().st_ino, path.stat().st_mtime_ns)
                for path in outputs
            }
            phases: list[str] = []
            builder.write_add_only(
                outputs,
                allowed_root=root,
                _transaction_hook=phases.append,
            )
            self.assertEqual(phases, [])
            self.assertEqual(
                before,
                {
                    path: (path.stat().st_dev, path.stat().st_ino, path.stat().st_mtime_ns)
                    for path in outputs
                },
            )
            self.assertTrue(packet.is_dir())
            self.assertFalse(transaction.exists())

    def test_hidden_directory_state_e_rejects_invalid_states_unchanged(self) -> None:
        variants = ("coexist", "foreign", "bytes", "mode", "hardlink", "symlink")
        for variant in variants:
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                _parent, packet, transaction, outputs = _private_transaction_case(root)
                transaction.mkdir(mode=0o700)
                first_path, first_content = next(iter(outputs.items()))
                staged = transaction / first_path.name
                if variant == "coexist":
                    packet.mkdir(mode=0o700)
                elif variant == "foreign":
                    foreign = transaction / "foreign"
                    foreign.write_bytes(b"foreign\n")
                    foreign.chmod(0o600)
                elif variant == "bytes":
                    staged.write_bytes(b"wrong\n")
                    staged.chmod(0o600)
                elif variant == "mode":
                    staged.write_bytes(first_content)
                    staged.chmod(0o640)
                elif variant == "hardlink":
                    source = root / "hardlink-source"
                    source.write_bytes(first_content)
                    source.chmod(0o600)
                    os.link(source, staged)
                else:
                    target = root / "symlink-target"
                    target.write_bytes(first_content)
                    os.symlink(target, staged)
                before = _capture_tree_state(root)
                with self.assertRaises((builder.ValidationError, OSError)):
                    builder.write_add_only(outputs, allowed_root=root)
                self.assertEqual(_capture_tree_state(root), before)

        for topology in (
            "transaction-mode",
            "transaction-file",
            "transaction-symlink",
            "final-mode",
            "final-foreign",
        ):
            with self.subTest(topology=topology), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                _parent, packet, transaction, outputs = _private_transaction_case(root)
                if topology == "transaction-mode":
                    transaction.mkdir(mode=0o750)
                elif topology == "transaction-file":
                    transaction.write_bytes(b"not-a-directory\n")
                elif topology == "transaction-symlink":
                    target = root / "transaction-target"
                    target.mkdir(mode=0o700)
                    os.symlink(target, transaction)
                else:
                    packet.mkdir(mode=0o700)
                    for path, content in outputs.items():
                        path.write_bytes(content)
                        path.chmod(0o600)
                    if topology == "final-mode":
                        packet.chmod(0o750)
                    else:
                        foreign = packet / "foreign"
                        foreign.write_bytes(b"foreign\n")
                        foreign.chmod(0o600)
                before = _capture_tree_state(root)
                with self.assertRaises((builder.ValidationError, OSError)):
                    builder.write_add_only(outputs, allowed_root=root)
                self.assertEqual(_capture_tree_state(root), before)

    def test_crash_retry_covers_every_hook_and_atomic_move_boundary(self) -> None:
        phases = (
            "journal_durable",
            "staged_output_0",
            "staged_output_1",
            "staged_output_2",
            "before_rename",
            "after_rename",
            "after_parent_fsync",
            "before_journal_cleanup",
        )
        for phase in phases:
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                _parent, _packet, _transaction, outputs = _private_transaction_case(root)

                def kill_at_phase(observed: str) -> None:
                    if observed == phase:
                        os.kill(os.getpid(), signal.SIGKILL)

                _fork_sigkill_write(self, root, outputs, (), kill_at_phase)
                builder.write_add_only(outputs, allowed_root=root)
                builder.check_outputs(outputs, allowed_root=root)

        for boundary in ("mkdir", "link", "rename"):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                _parent, _packet, _transaction, outputs = _private_transaction_case(root)
                if boundary == "mkdir":
                    original = builder.os.mkdir

                    def kill_after(*args, **kwargs):
                        result = original(*args, **kwargs)
                        os.kill(os.getpid(), signal.SIGKILL)
                        return result

                    patchers = (patch.object(builder.os, "mkdir", new=kill_after),)
                elif boundary == "link":
                    original = builder._link_fd_noreplace

                    def kill_after(*args, **kwargs):
                        result = original(*args, **kwargs)
                        os.kill(os.getpid(), signal.SIGKILL)
                        return result

                    patchers = (
                        patch.object(builder, "_link_fd_noreplace", new=kill_after),
                    )
                else:
                    original = builder._rename_noreplace

                    def kill_after(*args, **kwargs):
                        result = original(*args, **kwargs)
                        os.kill(os.getpid(), signal.SIGKILL)
                        return result

                    patchers = (
                        patch.object(builder, "_rename_noreplace", new=kill_after),
                    )
                _fork_sigkill_write(self, root, outputs, patchers)
                builder.write_add_only(outputs, allowed_root=root)
                builder.check_outputs(outputs, allowed_root=root)

        for fsync_index in range(1, 11):
            with self.subTest(fsync_index=fsync_index), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                _parent, _packet, _transaction, outputs = _private_transaction_case(root)
                original_fsync = builder.os.fsync
                calls = 0

                def kill_after_index(descriptor: int) -> None:
                    nonlocal calls
                    original_fsync(descriptor)
                    calls += 1
                    if calls == fsync_index:
                        os.kill(os.getpid(), signal.SIGKILL)

                _fork_sigkill_write(
                    self,
                    root,
                    outputs,
                    (patch.object(builder.os, "fsync", new=kill_after_index),),
                )
                builder.write_add_only(outputs, allowed_root=root)
                builder.check_outputs(outputs, allowed_root=root)

    def test_hooks_and_reserved_paths_remain_bound(self) -> None:
        phases = (
            "journal_durable",
            "staged_output_0",
            "staged_output_1",
            "staged_output_2",
            "before_rename",
            "after_rename",
            "after_parent_fsync",
            "before_journal_cleanup",
        )
        for phase in phases:
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                _parent, packet, transaction, outputs = _private_transaction_case(root)
                reserved = root / "must-remain-absent"

                def create_reserved(observed: str) -> None:
                    if observed == phase:
                        reserved.write_bytes(b"foreign\n")

                with self.assertRaises((builder.ValidationError, FileExistsError)):
                    builder.write_add_only(
                        outputs,
                        allowed_root=root,
                        reserved_absent_paths=(reserved,),
                        _transaction_hook=create_reserved,
                    )
                self.assertTrue(reserved.is_file())
                self.assertTrue(packet.exists() ^ transaction.exists())

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, _packet, transaction, outputs = _private_transaction_case(root)
            left = root / "reserved"
            right = left / "child"
            with self.assertRaisesRegex(builder.ValidationError, "overlap each other"):
                builder.write_add_only(
                    outputs,
                    allowed_root=root,
                    reserved_absent_paths=(left, right),
                )
            self.assertFalse(transaction.exists())

    def test_otmpfile_flags_and_empty_path_link_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, _packet, _transaction, outputs = _private_transaction_case(root)
            real_open = builder.os.open
            real_link = builder._link_fd_noreplace
            temporary_flags: list[int] = []
            linked_names: list[str] = []

            def observe_open(path, flags, *args, **kwargs):
                descriptor = real_open(path, flags, *args, **kwargs)
                if flags & builder.os.O_TMPFILE == builder.os.O_TMPFILE:
                    temporary_flags.append(flags)
                    self.assertEqual(os.fstat(descriptor).st_nlink, 0)
                return descriptor

            def observe_link(source_fd: int, directory_fd: int, name: str) -> None:
                self.assertEqual(os.fstat(source_fd).st_nlink, 0)
                with self.assertRaises(FileNotFoundError):
                    os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
                real_link(source_fd, directory_fd, name)
                self.assertEqual(os.fstat(source_fd).st_nlink, 1)
                linked_names.append(name)

            with (
                patch.object(builder.os, "open", new=observe_open),
                patch.object(builder, "_link_fd_noreplace", new=observe_link),
            ):
                builder.write_add_only(outputs, allowed_root=root)
            self.assertEqual(len(temporary_flags), 3)
            self.assertTrue(
                all(
                    flags & builder.os.O_CLOEXEC
                    and flags & builder.os.O_NOFOLLOW
                    and flags & builder.os.O_TMPFILE
                    for flags in temporary_flags
                )
            )
            self.assertEqual(set(linked_names), {path.name for path in outputs})

    def test_every_hook_rejects_exact_clone_and_source_authority_drift(self) -> None:
        phases = (
            "journal_durable",
            "staged_output_0",
            "staged_output_1",
            "staged_output_2",
            "before_rename",
            "after_rename",
            "after_parent_fsync",
            "before_journal_cleanup",
        )
        post_rename = {
            "after_rename",
            "after_parent_fsync",
            "before_journal_cleanup",
        }
        for phase in phases:
            with self.subTest(clone_phase=phase), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                parent, packet, transaction, outputs = _private_transaction_case(root)
                held = parent / "held-cohort"

                def swap_with_exact_clone(observed: str) -> None:
                    if observed != phase:
                        return
                    active = packet if phase in post_rename else transaction
                    active.rename(held)
                    active.mkdir(mode=0o700)
                    for source in held.iterdir():
                        clone = active / source.name
                        clone.write_bytes(source.read_bytes())
                        clone.chmod(0o600)

                with self.assertRaisesRegex(
                    builder.ValidationError,
                    "retained directory changed|external inventory changed",
                ):
                    builder.write_add_only(
                        outputs,
                        allowed_root=root,
                        _transaction_hook=swap_with_exact_clone,
                    )
                self.assertTrue(held.is_dir())

            with self.subTest(source_phase=phase), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                _parent, _packet, _transaction, outputs = _private_transaction_case(root)
                source_valid = True

                def verify_source() -> None:
                    if not source_valid:
                        raise builder.ValidationError("source authority drift")

                def invalidate_source(observed: str) -> None:
                    nonlocal source_valid
                    if observed == phase:
                        source_valid = False

                with self.assertRaisesRegex(
                    builder.ValidationError,
                    "source authority drift",
                ):
                    builder._write_add_only_transaction(
                        outputs,
                        allowed_root=root,
                        _transaction_hook=invalidate_source,
                        _source_authority_verifier=verify_source,
                    )

    def test_descriptor_owner_drains_and_preserves_primary(self) -> None:
        owner = builder._DescriptorOwner()
        for operation in (
            lambda: copy.copy(owner),
            lambda: copy.deepcopy(owner),
            lambda: pickle.dumps(owner),
        ):
            with self.assertRaises((builder.ValidationError, TypeError)):
                operation()

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
            second = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
            owner.adopt(first)
            owner.adopt(second)
            real_close = builder._OS_CLOSE
            closed: list[int] = []

            def close_then_fail(descriptor: int) -> None:
                real_close(descriptor)
                closed.append(descriptor)
                if len(closed) == 1:
                    raise OSError("first close failure")

            with patch.object(builder, "_OS_CLOSE", new=close_then_fail):
                with self.assertRaisesRegex(OSError, "first close failure"):
                    owner.close()
            self.assertEqual(closed, [second, first])
            for descriptor in (first, second):
                with self.assertRaises(OSError):
                    os.fstat(descriptor)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, _packet, transaction, outputs = _private_transaction_case(root)
            transaction.mkdir(mode=0o700)
            for path, content in list(outputs.items())[:2]:
                staged = transaction / path.name
                staged.write_bytes(content)
                staged.chmod(0o600)
            before = len(os.listdir("/proc/self/fd"))
            original_retain = builder._retain_regular_at
            calls = 0

            def fail_second(*args, **kwargs):
                nonlocal calls
                calls += 1
                if calls == 2:
                    raise RuntimeError("retention primary")
                return original_retain(*args, **kwargs)

            with patch.object(builder, "_retain_regular_at", new=fail_second):
                with self.assertRaisesRegex(RuntimeError, "retention primary"):
                    builder.write_add_only(outputs, allowed_root=root)
            self.assertEqual(len(os.listdir("/proc/self/fd")), before)

    def test_transaction_budget_is_prechecked_and_accepts_legal_maximum(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, _packet, _transaction, paths = _private_transaction_case(root)
            contents = (b"a", b"b", b"c")
            outputs = {
                path: byte * builder.MAXIMUM_REGULAR_FILE_BYTES
                for (path, _old), byte in zip(paths.items(), contents, strict=True)
            }
            builder.write_add_only(outputs, allowed_root=root)
            builder.check_outputs(outputs, allowed_root=root)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, _packet, transaction, paths = _private_transaction_case(root)
            contents = (b"a", b"b", b"c")
            outputs = {
                path: byte * builder.MAXIMUM_REGULAR_FILE_BYTES
                for (path, _old), byte in zip(paths.items(), contents, strict=True)
            }
            transaction.mkdir(mode=0o700)
            first_path, first_content = next(iter(outputs.items()))
            retained = transaction / first_path.name
            retained.write_bytes(first_content)
            retained.chmod(0o600)
            retained_identity = (retained.stat().st_dev, retained.stat().st_ino)
            builder.write_add_only(outputs, allowed_root=root)
            builder.check_outputs(outputs, allowed_root=root)
            self.assertEqual(
                (first_path.stat().st_dev, first_path.stat().st_ino),
                retained_identity,
            )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.write_bytes(b"1234")
            source.chmod(0o600)
            directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
            try:
                budget = builder._ReadBudget(3)
                with patch.object(builder.os, "read") as read:
                    with self.assertRaisesRegex(
                        builder.ValidationError,
                        "aggregate read byte cap",
                    ):
                        builder._retain_regular_at(
                            directory_fd,
                            source.name,
                            "pre-read budget",
                            maximum_bytes=4,
                            budget=budget,
                            owner=builder._DescriptorOwner(),
                        )
                    read.assert_not_called()
            finally:
                os.close(directory_fd)

    def test_late_snapshot_and_final_acquisition_failures_do_not_leak(self) -> None:
        def fd_count() -> int:
            return len(os.listdir("/proc/self/fd"))

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            child = root / "child"
            child.mkdir(mode=0o700)
            before = fd_count()
            original_flag = builder._required_open_flag
            calls = 0

            def fail_file_flag(name: str) -> int:
                nonlocal calls
                calls += 1
                if calls == 4:
                    raise builder.ValidationError("late file flag failure")
                return original_flag(name)

            with patch.object(builder, "_required_open_flag", new=fail_file_flag):
                with self.assertRaisesRegex(
                    builder.ValidationError,
                    "late file flag failure",
                ):
                    builder._RetainedSnapshot(root)
            self.assertEqual(fd_count(), before)

            snapshot = builder._RetainedSnapshot(root)

            class RejectingLinks(dict):
                def __setitem__(self, key, value) -> None:
                    raise RuntimeError("second cache insertion failure")

            try:
                before = fd_count()
                snapshot._directory_links = RejectingLinks()
                with self.assertRaisesRegex(
                    RuntimeError,
                    "second cache insertion failure",
                ):
                    snapshot.directory_fd(child)
                self.assertEqual(fd_count(), before)
            finally:
                snapshot.close()

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, packet, transaction, outputs = _private_transaction_case(root)
            packet.mkdir(mode=0o700)
            for path, content in outputs.items():
                path.write_bytes(content)
                path.chmod(0o600)
            before_tree = _capture_tree_state(root)
            before_fds = fd_count()
            original_retain = builder._retain_regular_at
            calls = 0

            def fail_second(*args, **kwargs):
                nonlocal calls
                calls += 1
                if calls == 2:
                    raise RuntimeError("final acquisition failure")
                return original_retain(*args, **kwargs)

            with patch.object(builder, "_retain_regular_at", new=fail_second):
                with self.assertRaisesRegex(RuntimeError, "final acquisition failure"):
                    builder.write_add_only(outputs, allowed_root=root)
            self.assertEqual(fd_count(), before_fds)
            self.assertEqual(_capture_tree_state(root), before_tree)
            self.assertFalse(transaction.exists())

    def test_mount_id_and_growth_reads_are_bounded_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
            try:
                before = len(os.listdir("/proc/self/fd"))
                with patch.object(builder.os, "pread", return_value=b"x" * 4097):
                    with self.assertRaisesRegex(
                        builder.ValidationError,
                        "fdinfo byte cap exceeded",
                    ):
                        builder._fd_mount_id(directory_fd, "oversized fdinfo")
                self.assertEqual(len(os.listdir("/proc/self/fd")), before)
                with patch.object(builder, "_fd_mount_id", side_effect=(41, 42)):
                    with self.assertRaisesRegex(
                        builder.ValidationError,
                        "cross-mount path is forbidden",
                    ):
                        builder._require_same_mount(
                            directory_fd,
                            directory_fd,
                            "injected mount drift",
                        )

                source = root / "growing"
                initial = b"a" * (2 * 1024 * 1024)
                source.write_bytes(initial)
                source.chmod(0o600)
                owner = builder._DescriptorOwner()
                real_read = builder.os.read
                returned_bytes = 0
                calls = 0

                def grow_after_first_read(descriptor: int, byte_count: int) -> bytes:
                    nonlocal returned_bytes, calls
                    observed = real_read(descriptor, byte_count)
                    returned_bytes += len(observed)
                    calls += 1
                    if calls == 1:
                        with source.open("ab") as stream:
                            stream.write(b"growth")
                    return observed

                try:
                    with patch.object(builder.os, "read", new=grow_after_first_read):
                        with self.assertRaisesRegex(
                            builder.ValidationError,
                            "changed while retaining",
                        ):
                            builder._retain_regular_at(
                                directory_fd,
                                source.name,
                                "growing retained file",
                                maximum_bytes=len(initial),
                                owner=owner,
                            )
                    self.assertEqual(returned_bytes, len(initial))
                finally:
                    owner.close(suppress_error=True)
            finally:
                os.close(directory_fd)

    def test_directory_inventory_is_streaming_bounded_and_closes(self) -> None:
        class FakeEntry:
            def __init__(self, name: str):
                self.name = name

        class FakeScandir:
            def __init__(
                self,
                names: list[str],
                *,
                failure_at: int | None = None,
                close_error: BaseException | None = None,
            ) -> None:
                self._names = names
                self._index = 0
                self._failure_at = failure_at
                self._close_error = close_error
                self.pulls = 0
                self.closed = False

            def __iter__(self):
                return self

            def __next__(self):
                self.pulls += 1
                if self._failure_at == self.pulls:
                    raise RuntimeError("scan primary")
                if self._index >= len(self._names):
                    raise StopIteration
                name = self._names[self._index]
                self._index += 1
                return FakeEntry(name)

            def close(self) -> None:
                self.closed = True
                if self._close_error is not None:
                    raise self._close_error

        entry_limited = FakeScandir(["one", "two", "three", "four", "five"])
        with patch.object(builder.os, "scandir", return_value=entry_limited):
            with self.assertRaisesRegex(
                builder.ValidationError,
                "directory inventory entry cap exceeded",
            ):
                builder._bounded_directory_inventory(
                    123,
                    "entry limited",
                    maximum_entries=3,
                    maximum_name_bytes=100,
                )
        self.assertEqual(entry_limited.pulls, 4)
        self.assertTrue(entry_limited.closed)

        byte_limited = FakeScandir(["aa", "bb", "never-read"])
        with patch.object(builder.os, "scandir", return_value=byte_limited):
            with self.assertRaisesRegex(
                builder.ValidationError,
                "directory inventory name-byte cap exceeded",
            ):
                builder._bounded_directory_inventory(
                    123,
                    "byte limited",
                    maximum_entries=3,
                    maximum_name_bytes=3,
                )
        self.assertEqual(byte_limited.pulls, 2)
        self.assertTrue(byte_limited.closed)

        scan_failed = FakeScandir(
            ["one"],
            failure_at=2,
            close_error=OSError("close secondary"),
        )
        with patch.object(builder.os, "scandir", return_value=scan_failed):
            with self.assertRaisesRegex(RuntimeError, "scan primary"):
                builder._bounded_directory_inventory(
                    123,
                    "scan failed",
                    maximum_entries=3,
                    maximum_name_bytes=100,
                )
        self.assertEqual(scan_failed.pulls, 2)
        self.assertTrue(scan_failed.closed)

        close_failed = FakeScandir(
            ["one"],
            close_error=OSError("close primary"),
        )
        with patch.object(builder.os, "scandir", return_value=close_failed):
            with self.assertRaisesRegex(OSError, "close primary"):
                builder._bounded_directory_inventory(
                    123,
                    "close failed",
                    maximum_entries=3,
                    maximum_name_bytes=100,
                )
        self.assertTrue(close_failed.closed)

        exact = FakeScandir(["one", "two"])
        with patch.object(builder.os, "scandir", return_value=exact):
            observed = builder._bounded_directory_inventory(
                123,
                "exact",
                maximum_entries=3,
                maximum_name_bytes=100,
            )
        self.assertIs(type(observed), frozenset)
        self.assertEqual(observed, frozenset({"one", "two"}))
        self.assertTrue(exact.closed)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            parent, packet, transaction, outputs = _private_transaction_case(root)
            for index in range(
                builder.MAXIMUM_PUBLICATION_PARENT_EXTERNAL_ENTRY_COUNT + 1
            ):
                foreign = parent / f"foreign-{index:03d}"
                foreign.write_bytes(b"foreign\n")
            hook_calls: list[str] = []
            with self.assertRaisesRegex(
                builder.ValidationError,
                "directory inventory entry cap exceeded",
            ):
                builder.write_add_only(
                    outputs,
                    allowed_root=root,
                    _transaction_hook=hook_calls.append,
                )
            self.assertEqual(hook_calls, [])
            self.assertFalse(packet.exists())
            self.assertFalse(transaction.exists())
            self.assertEqual(
                len(tuple(parent.glob("foreign-*"))),
                builder.MAXIMUM_PUBLICATION_PARENT_EXTERNAL_ENTRY_COUNT + 1,
            )

    def test_owner_masking_umask_and_proc_parse_fail_before_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, packet, transaction, outputs = _private_transaction_case(root)
            before = _capture_tree_state(root)
            hook_calls: list[str] = []
            previous_umask = os.umask(0o777)
            try:
                with (
                    patch.object(
                        builder.os,
                        "umask",
                        side_effect=AssertionError("writer changed process umask"),
                    ),
                    patch.object(
                        builder.os,
                        "mkdir",
                        side_effect=AssertionError("mkdir reached"),
                    ),
                ):
                    with self.assertRaisesRegex(
                        builder.ValidationError,
                        "process umask masks required owner permissions",
                    ):
                        builder.write_add_only(
                            outputs,
                            allowed_root=root,
                            _transaction_hook=hook_calls.append,
                        )
            finally:
                os.umask(previous_umask)
            self.assertEqual(hook_calls, [])
            self.assertEqual(_capture_tree_state(root), before)
            self.assertFalse(packet.exists())
            self.assertFalse(transaction.exists())

        before_fds = len(os.listdir("/proc/self/fd"))
        with patch.object(
            builder.os,
            "pread",
            return_value=b"x" * (builder.MAXIMUM_PROC_STATUS_BYTES + 1),
        ):
            with self.assertRaisesRegex(
                builder.ValidationError,
                "process status byte cap exceeded",
            ):
                builder._read_process_umask()
        self.assertEqual(len(os.listdir("/proc/self/fd")), before_fds)
        for label, content in (
            ("missing", b"Name:\tpython\n"),
            ("duplicate", b"Umask:\t0022\nUmask:\t0022\n"),
            ("malformed", b"Umask: 0022\n"),
            ("non-octal", b"Umask:\t0088\n"),
        ):
            with self.subTest(status=label), patch.object(
                builder.os,
                "pread",
                return_value=content,
            ):
                with self.assertRaisesRegex(
                    builder.ValidationError,
                    "process umask status differs",
                ):
                    builder._read_process_umask()
            self.assertEqual(len(os.listdir("/proc/self/fd")), before_fds)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, packet, transaction, outputs = _private_transaction_case(root)
            before = _capture_tree_state(root)
            with (
                patch.object(
                    builder,
                    "_read_process_umask",
                    side_effect=builder.ValidationError("process umask unavailable"),
                ),
                patch.object(
                    builder.os,
                    "mkdir",
                    side_effect=AssertionError("mkdir reached"),
                ),
            ):
                with self.assertRaisesRegex(
                    builder.ValidationError,
                    "process umask unavailable",
                ):
                    builder.write_add_only(outputs, allowed_root=root)
            self.assertEqual(_capture_tree_state(root), before)
            self.assertFalse(packet.exists())
            self.assertFalse(transaction.exists())

    def test_official_source_authority_is_checked_inside_commit_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _clone_fixture(self.root, root)
            outputs = builder.build_outputs(root, expected_source_sha256=self.pins)
            source = root / builder.IMPLEMENTATION_REL
            original_verify = builder._verify_retained_output_files
            mutated = False

            def mutate_after_final_bytes(*args, **kwargs) -> None:
                nonlocal mutated
                original_verify(*args, **kwargs)
                if not mutated:
                    mutated = True
                    source.write_bytes(source.read_bytes() + b" ")

            try:
                with patch.object(
                    builder,
                    "_verify_retained_output_files",
                    new=mutate_after_final_bytes,
                ):
                    with self.assertRaisesRegex(
                        builder.ValidationError,
                        "changed after snapshot",
                    ):
                        builder.write_add_only(outputs, allowed_root=root)
                self.assertTrue(mutated)
                packet = root / builder.R012_PACKET_DIR_REL
                transaction = packet.parent / f".{packet.name}.r012-transaction"
                self.assertFalse(packet.exists())
                self.assertTrue(transaction.is_dir())
            finally:
                outputs.close()

    def test_link_rename_final_cas_reentry_and_umask(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, packet, transaction, outputs = _private_transaction_case(root)
            original_link = builder._link_fd_noreplace
            first_name = next(iter(outputs)).name

            def collide(source_fd: int, directory_fd: int, name: str) -> None:
                if name == first_name:
                    descriptor = os.open(
                        name,
                        os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                        0o600,
                        dir_fd=directory_fd,
                    )
                    try:
                        os.write(descriptor, b"foreign\n")
                    finally:
                        os.close(descriptor)
                original_link(source_fd, directory_fd, name)

            with patch.object(builder, "_link_fd_noreplace", new=collide):
                with self.assertRaises(FileExistsError):
                    builder.write_add_only(outputs, allowed_root=root)
            self.assertFalse(packet.exists())
            self.assertEqual((transaction / first_name).read_bytes(), b"foreign\n")

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            parent, packet, transaction, outputs = _private_transaction_case(root)
            original_rename = builder._rename_noreplace

            def collide(*args, **kwargs) -> None:
                packet.mkdir(mode=0o700)
                foreign = packet / "foreign"
                foreign.write_bytes(b"foreign\n")
                foreign.chmod(0o600)
                original_rename(*args, **kwargs)

            with patch.object(builder, "_rename_noreplace", new=collide):
                with self.assertRaises(FileExistsError):
                    builder.write_add_only(outputs, allowed_root=root)
            self.assertEqual((packet / "foreign").read_bytes(), b"foreign\n")
            self.assertTrue(transaction.is_dir())
            self.assertEqual(set(path.name for path in transaction.iterdir()), {p.name for p in outputs})

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, packet, transaction, outputs = _private_transaction_case(root)
            nested_errors: list[BaseException] = []

            def reenter(phase: str) -> None:
                if phase == "journal_durable":
                    try:
                        builder.write_add_only(outputs, allowed_root=root)
                    except BaseException as exc:
                        nested_errors.append(exc)

            previous_umask = os.umask(0o077)
            try:
                builder.write_add_only(
                    outputs,
                    allowed_root=root,
                    _transaction_hook=reenter,
                )
            finally:
                os.umask(previous_umask)
            self.assertEqual(len(nested_errors), 1)
            self.assertIsInstance(nested_errors[0], builder.ValidationError)
            self.assertEqual(stat.S_IMODE(packet.stat().st_mode), 0o700)
            self.assertFalse(transaction.exists())

    def test_final_only_inode_swap_and_directory_mode_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            parent, packet, transaction, outputs = _private_transaction_case(root)
            builder.write_add_only(outputs, allowed_root=root)
            held = parent / "held-original-final"
            original_identity = (packet.stat().st_dev, packet.stat().st_ino)
            real_fsync = builder.os.fsync
            attacked = False

            def swap_after_parent_fsync(descriptor: int) -> None:
                nonlocal attacked
                real_fsync(descriptor)
                try:
                    target = Path(os.readlink(f"/proc/self/fd/{descriptor}"))
                except OSError:
                    return
                if not attacked and target == parent:
                    attacked = True
                    packet.rename(held)
                    packet.mkdir(mode=0o700)
                    for path, content in outputs.items():
                        clone = packet / path.name
                        clone.write_bytes(content)
                        clone.chmod(0o600)

            with patch.object(builder.os, "fsync", new=swap_after_parent_fsync):
                with self.assertRaisesRegex(
                    builder.ValidationError,
                    "retained directory changed",
                ):
                    builder.write_add_only(outputs, allowed_root=root)
            self.assertTrue(attacked)
            self.assertEqual(
                (held.stat().st_dev, held.stat().st_ino),
                original_identity,
            )
            self.assertFalse(transaction.exists())

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, packet, _transaction, outputs = _private_transaction_case(root)
            builder.write_add_only(outputs, allowed_root=root)
            packet.chmod(0o750)
            with self.assertRaisesRegex(
                builder.ValidationError,
                "packet directory mode differs",
            ):
                builder.check_outputs(outputs, allowed_root=root)

    def test_close_and_rollback_errors_never_mask_transaction_primary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, packet, transaction, outputs = _private_transaction_case(root)
            real_close = builder._OS_CLOSE

            def close_then_fail(descriptor: int) -> None:
                target = os.readlink(f"/proc/self/fd/{descriptor}")
                real_close(descriptor)
                if "/fdinfo/" not in target and not (
                    target.startswith("/proc/") and target.endswith("/status")
                ):
                    raise OSError("injected close failure")

            def primary(phase: str) -> None:
                if phase == "journal_durable":
                    raise RuntimeError("hook primary")

            with patch.object(builder, "_OS_CLOSE", new=close_then_fail):
                with self.assertRaisesRegex(RuntimeError, "hook primary"):
                    builder.write_add_only(
                        outputs,
                        allowed_root=root,
                        _transaction_hook=primary,
                    )
            self.assertFalse(packet.exists())
            self.assertTrue(transaction.is_dir())

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, packet, transaction, outputs = _private_transaction_case(root)
            real_close = builder._OS_CLOSE

            def close_after_commit(descriptor: int) -> None:
                target = os.readlink(f"/proc/self/fd/{descriptor}")
                real_close(descriptor)
                if "/fdinfo/" not in target and not (
                    target.startswith("/proc/") and target.endswith("/status")
                ):
                    raise OSError("postcommit close failure")

            with patch.object(builder, "_OS_CLOSE", new=close_after_commit):
                builder.write_add_only(outputs, allowed_root=root)
            self.assertTrue(packet.is_dir())
            self.assertFalse(transaction.exists())

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _parent, packet, transaction, outputs = _private_transaction_case(root)
            real_rename = builder._rename_noreplace
            rename_calls = 0

            def fail_rollback(*args, **kwargs) -> None:
                nonlocal rename_calls
                rename_calls += 1
                if rename_calls == 2:
                    raise OSError("rollback secondary")
                real_rename(*args, **kwargs)

            def fail_after_rename(phase: str) -> None:
                if phase == "after_rename":
                    raise RuntimeError("publication primary")

            with patch.object(builder, "_rename_noreplace", new=fail_rollback):
                with self.assertRaisesRegex(RuntimeError, "publication primary") as raised:
                    builder.write_add_only(
                        outputs,
                        allowed_root=root,
                        _transaction_hook=fail_after_rename,
                    )
            self.assertTrue(packet.is_dir())
            self.assertFalse(transaction.exists())
            self.assertTrue(
                any("rollback also failed" in note for note in raised.exception.__notes__)
            )


if __name__ == "__main__":
    unittest.main()
