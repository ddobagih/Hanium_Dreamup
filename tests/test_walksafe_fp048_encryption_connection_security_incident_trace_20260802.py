from __future__ import annotations

import base64
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import stat
import subprocess

import pytest

from scripts import build_walksafe_fp048_encryption_connection_security_incident_trace_20260802 as builder


START = datetime(2026, 8, 2, 22, 0, tzinfo=timezone(timedelta(hours=9)))


def authority() -> dict:
    return {
        "event": {
            "sequence": 42,
            "event_id": builder.EXPECTED_EXECUTION_EVENT_ID,
            "event_type": "GOAL_STARTED",
            "event_sha256": builder.EXPECTED_EXECUTION_EVENT_SHA256,
            "occurred_at": builder.EXPECTED_EXECUTION_STARTED_AT,
            "implementation_start_gate_binding": {
                "document_id": builder.EXPECTED_START_GATE_RECEIPT_ID,
                "path": builder.START_GATE_RECEIPT_REL.as_posix(),
                "file_sha256": builder.EXPECTED_START_GATE_RECEIPT_SHA256,
            },
        },
        "gate_ended_at": "2026-08-02T21:42:18+09:00",
        "repository_state": {"dirty_snapshot": {"paths": []}},
        "start_gate_binding": {
            "document_id": builder.EXPECTED_START_GATE_RECEIPT_ID,
            "path": builder.START_GATE_RECEIPT_REL.as_posix(),
            "sha256": builder.EXPECTED_START_GATE_RECEIPT_SHA256,
        },
        "repository_state_binding": {
            "path": builder.START_GATE_REPOSITORY_STATE_REL.as_posix(),
            "sha256": builder.EXPECTED_START_GATE_REPOSITORY_STATE_SHA256,
            "pinned_head": builder.EXPECTED_PINNED_HEAD,
        },
    }


def rows() -> list[dict]:
    return [
        {"path": "fixture/a.kt", "before_sha256": "1" * 64, "after_sha256": "2" * 64, "before_source": "SEQ42_START_GATE_DIRTY_SNAPSHOT", "change_kind": "MODIFIED"},
        {"path": "fixture/b.py", "before_sha256": None, "after_sha256": "3" * 64, "before_source": "SEQ42_PINNED_HEAD_ABSENT", "change_kind": "ADDED"},
    ]


def verification_rows() -> list[dict]:
    relative = builder.VERIFICATION_INPUT_PATHS[0]
    return [{
        "path": relative.as_posix(),
        "byte_count": builder.EXPECTED_VERIFICATION_INPUT_BYTE_COUNT[relative],
        "sha256": builder.EXPECTED_VERIFICATION_INPUT_SHA256[relative],
    }]


def android_junit_lines(*, failures: int = 0) -> list[str]:
    counts = [8] * 14 + [7] * 5
    lines = []
    for index, (class_name, count) in enumerate(zip(builder.EXPECTED_ANDROID_JUNIT_CLASSES, counts, strict=True)):
        children = []
        for case in range(count):
            child = f'<testcase classname="{class_name}" name="case{case}">'
            if failures and index == 0 and case == 0:
                child += "<failure>boom</failure>"
            child += "</testcase>"
            children.append(child)
        declared_failures = 1 if failures and index == 0 else 0
        raw = (
            f'<testsuite name="{class_name}" tests="{count}" failures="{declared_failures}" errors="0" skipped="0">'
            + "".join(children)
            + "</testsuite>"
        ).encode()
        path = f"apps/android/app/build/test-results/testDebugUnitTest/TEST-{class_name}.xml"
        lines.append(
            f"ANDROID_JUNIT_XML_BYTES path={path} bytes={len(raw)} "
            f"sha256={builder.bytes_sha256(raw)} base64={base64.b64encode(raw).decode()}"
        )
    return lines


def raw_output(spec: builder.LaneSpec) -> list[str]:
    if spec.lane_id == "ANDROID_PROTECTED_STORAGE":
        host = dict(spec.exact_markers)["WALKSAFE_ANDROID_HOST_PYTEST_PASSED"]
        return [
            "> Task :app:testDebugUnitTest",
            "BUILD SUCCESSFUL in 1s",
            *android_junit_lines(),
            f"{host} passed in 1.00s",
            "> Task :app:compileDebugAndroidTestKotlin",
            "> Task :app:assembleDebug",
            "> Task :app:lintDebug",
            "BUILD SUCCESSFUL in 1s",
        ]
    if spec.lane_id == "HTTPS_NO_DOWNGRADE":
        return ["> Task :app:testDebugUnitTest", "BUILD SUCCESSFUL in 1s", "1..77", "# tests 77", "# suites 0", "# pass 77", "# fail 0", "# cancelled 0", "# skipped 0", "# todo 0"]
    if spec.lane_id == "SERVER_ORIGINAL_DB_BACKUP_BOUNDARY":
        return ["441 passed, 11 skipped in 1.00s", "38 passed in 1.00s", "64 passed in 1.00s"]
    if spec.lane_id == "KEY_LIFECYCLE_ORIGINAL_ACCESS":
        return ["254 passed, 11 skipped in 1.00s"]
    return ["64 passed in 1.00s"]


def log_bytes(spec: builder.LaneSpec, content_set: str, verification_set: str, index: int) -> bytes:
    started, ended = START + timedelta(minutes=index * 2), START + timedelta(minutes=index * 2 + 1)
    lines = [
        "WALKSAFE_EXECUTION_EVENT_SEQUENCE=42",
        f"WALKSAFE_EXECUTION_EVENT_ID={builder.EXPECTED_EXECUTION_EVENT_ID}",
        f"WALKSAFE_EXECUTION_EVENT_SHA256={builder.EXPECTED_EXECUTION_EVENT_SHA256}",
        f"WALKSAFE_IMPLEMENTATION_CONTENT_SET_SHA256={content_set}",
        f"WALKSAFE_VERIFICATION_INPUT_CONTENT_SET_SHA256={verification_set}",
        f"WALKSAFE_RUN_ID=fp048-fixture-{index:02d}",
        f"WALKSAFE_COMMAND_SHA256={builder.bytes_sha256(spec.command.encode())}",
        f"WALKSAFE_COMMAND_STARTED_AT={started.isoformat()}",
        f"WALKSAFE_LANE_ID={spec.lane_id}",
        "WALKSAFE_LANE_STATUS=PASS",
        builder.RAW_OUTPUT_BEGIN,
        *raw_output(spec),
        builder.RAW_OUTPUT_END,
        *(f"{name}={value}" for name, value in spec.exact_markers),
        f"WALKSAFE_COMMAND_ENDED_AT={ended.isoformat()}",
        "WALKSAFE_COMMAND_EXIT_CODE=0",
    ]
    return ("\n".join(lines) + "\n").encode()


def inputs() -> dict:
    fixture_rows = rows()
    content_set = builder.implementation_content_set(fixture_rows)
    verification_input_rows = verification_rows()
    verification_set = builder.object_sha256(verification_input_rows)
    return {
        "implementation_rows": fixture_rows,
        "authority": authority(),
        "test_mode": True,
        "verification_input_rows": verification_input_rows,
        "log_snapshots": {
            spec.lane_id: log_bytes(spec, content_set, verification_set, index)
            for index, spec in enumerate(builder.LANES)
        },
    }


def parse(outputs: dict[Path, str], relative: Path) -> dict:
    return json.loads(outputs[relative])


def write_json(root: Path, relative: Path, value: dict) -> bytes:
    raw = builder.json_text(value).encode()
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)
    return raw


def seal(value: dict, field: str) -> dict:
    value.pop(field, None)
    value[field] = builder.object_sha256(value)
    return value


def bypass_full_consumer_validators(monkeypatch: pytest.MonkeyPatch) -> list[tuple[str, dict[Path, str]]]:
    calls: list[tuple[str, dict[Path, str]]] = []

    def r023(_root: Path, snapshots) -> None:
        assert set(snapshots) == {builder.R023_GAP_REL, builder.R023_BACKLOG_REL}
        calls.append(("r023", {}))

    def artifact(_root: Path, snapshots, pins) -> None:
        assert len(snapshots) == 6
        assert all(builder.bytes_sha256(raw) == builder.bytes_sha256(raw) for raw, _value in snapshots.values())
        calls.append(("artifact", dict(pins)))

    def r012(_root: Path, snapshots, pins) -> None:
        assert len(snapshots) == 3
        calls.append(("r012", dict(pins)))

    monkeypatch.setattr(builder, "_run_full_r023_validation", r023)
    monkeypatch.setattr(builder, "_run_full_artifact_successor_validation", artifact)
    monkeypatch.setattr(builder, "_run_full_r012_validation", r012)
    return calls


def materialize_consumers(root: Path, outputs: dict[Path, str]) -> list[dict]:
    implementation_raw = outputs[builder.IMPLEMENTATION_REL].encode()
    verification_raw = outputs[builder.VERIFICATION_REL].encode()
    implementation_sha = builder.bytes_sha256(implementation_raw)
    verification_sha = builder.bytes_sha256(verification_raw)
    source_bindings = [
        {"name": "fp048_implementation", "path": builder.IMPLEMENTATION_REL.as_posix(), "bytes": len(implementation_raw), "sha256": implementation_sha},
        {"name": "fp048_verification", "path": builder.VERIFICATION_REL.as_posix(), "bytes": len(verification_raw), "sha256": verification_sha},
    ]
    gap = {
        "schema_version": "walksafe.implementation-gap-analysis.v1",
        "metadata": {"report_id": builder.EXPECTED_R023_REPORT_ID, "version": "0.23.0"},
        "source_bindings": source_bindings,
        "source_binding_sha256": builder.object_sha256(source_bindings),
        "evidence_catalog": [{
            "evidence_id": "EVD-FP048-INTERNAL-ENCRYPTION-SECURITY-20260802",
            "result_evidence_sha256": {"IMPLEMENTATION_RECORD": implementation_sha, "VERIFICATION_RESULT": verification_sha},
        }],
    }
    gap_raw = write_json(root, builder.R023_GAP_REL, seal(gap, "report_content_sha256"))
    backlog = {
        "schema_version": "walksafe.implementation-remediation-backlog.v1",
        "metadata": {"backlog_id": builder.EXPECTED_R023_BACKLOG_ID, "version": "0.23.0"},
        "gap_report_content_sha256": gap["report_content_sha256"],
        "next_single_action": {
            "epic_id": "EPIC-03",
            "source_policy_id": "FP-008",
            "gap_id": "GAP-017",
            "status": "PLANNED_NEXT",
            "action": "separate Android administrator flow",
        },
    }
    write_json(root, builder.R023_BACKLOG_REL, seal(backlog, "backlog_content_sha256"))

    producer_sources = {
        builder.IMPLEMENTATION_REL: implementation_raw,
        builder.VERIFICATION_REL: verification_raw,
        builder.R023_GAP_REL: gap_raw,
    }
    artifact_raw: dict[Path, bytes] = {}
    for _role, relative, schema in builder.FP048_ARTIFACT_CONSUMERS:
        artifact = {
            "schema_version": schema,
            "fp048_artifact_trace_successor": {
                "successor_id": builder.FP048_ARTIFACT_SUCCESSOR_ID,
                "input_bindings": [
                    {"path": source.as_posix(), "sha256": builder.bytes_sha256(raw), "byte_length": len(raw)}
                    for source, raw in producer_sources.items()
                ],
            },
        }
        artifact_raw[relative] = write_json(root, relative, artifact)

    source_contents = {**producer_sources, **artifact_raw}
    source_rows = [
        {
            "binding_id": f"R012-SRC-{index:03d}",
            "path": relative.as_posix(),
            "byte_length": len(raw),
            "sha256": builder.bytes_sha256(raw),
        }
        for index, (relative, raw) in enumerate(source_contents.items(), start=1)
    ]
    ledger_rel = builder.R012_DIR_REL / "phase1-exact257-successor-ledger-r012.json"
    evidence_rel = builder.R012_DIR_REL / "evidence.json"
    receipt_rel = builder.R012_DIR_REL / "phase1-exact257-successor-check-receipt-r012.json"
    ledger = {
        "schema_version": "walksafe.phase1-exact257-successor-ledger.v12",
        "r012_fp048_artifact_progress_application": {
            "record_count": 257,
            "unchanged_record_count": 251,
            "progress_binding_record_count": 6,
            "forbidden_new_source_count": 0,
            "zero_credits": deepcopy(builder.R012_ZERO_CREDITS),
        },
        "r012_source_bindings": deepcopy(source_rows),
    }
    ledger_raw = write_json(root, ledger_rel, ledger)
    ledger_binding = builder._r012_bytes_binding(ledger_rel, ledger_raw, "R012-OUT-001", "R012_FULL_EXACT257_LEDGER")
    evidence = {
        "schema_version": "walksafe.phase1-exact257-successor-evidence.v12",
        "row_delta": {"record_count": 257, "unchanged_record_count": 251, "progress_binding_record_count": 6, "other_record_delta_count": 0},
        "preserved_invariants": {"zero_credits": deepcopy(builder.R012_ZERO_CREDITS)},
        "source_bindings": deepcopy(source_rows),
        "subject_chain": {"r012_ledger": ledger_binding},
    }
    evidence_raw = write_json(root, evidence_rel, evidence)
    evidence_binding = builder._r012_bytes_binding(evidence_rel, evidence_raw, "R012-OUT-002", "R012_FP048_EXACT6_PROGRESS_EVIDENCE")
    receipt = {
        "schema_version": "walksafe.phase1-exact257-successor-check-receipt.v12",
        "status": "PASS",
        "summary": {"record_count": 257, "unchanged_record_count": 251, "progress_binding_record_count": 6, "zero_credits": deepcopy(builder.R012_ZERO_CREDITS)},
        "source_bindings": deepcopy(source_rows),
        "output_bindings": [ledger_binding, evidence_binding],
    }
    write_json(root, receipt_rel, receipt)
    return builder.validate_consumer_bindings(root, implementation_sha, verification_sha)


def materialize_attestation(root: Path, outputs: dict[Path, str], consumers: list[dict], *, reviewer_id: str = builder.EXPECTED_REVIEWER_ID) -> dict:
    hashes = {
        "IMPLEMENTATION_RECORD": builder.bytes_sha256(outputs[builder.IMPLEMENTATION_REL].encode()),
        "VERIFICATION_RESULT": builder.bytes_sha256(outputs[builder.VERIFICATION_REL].encode()),
        "SUCCESSOR_TRACE": builder.bytes_sha256(outputs[builder.SUCCESSOR_REL].encode()),
    }
    value = {
        "schema_version": "1.0",
        "evidence_type": "INTERNAL_REVIEW_ATTESTATION",
        "goal_id": builder.GOAL_ID,
        "reviewer_id": reviewer_id,
        "reviewer_task": builder.EXPECTED_REVIEWER_TASK,
        "reviewed_at": "2026-08-02T23:00:00+09:00",
        "review_subject_sha256": builder.bytes_sha256(outputs[builder.REVIEW_SUBJECT_REL].encode()),
        "reviewed_result_sha256_by_kind": hashes,
        "reviewed_consumer_bindings": consumers,
        "decision": "APPROVED",
        "findings": {"blocking": 0, "major_open": 0, "minor_open": 0},
        "review_boundary": builder.expected_review_boundary(),
    }
    write_json(root, builder.REVIEW_ATTESTATION_REL, value)
    (root / builder.REVIEW_ATTESTATION_REL).chmod(0o600)
    return value


def prepare_post(root: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[dict, dict[Path, str], list[dict]]:
    bypass_full_consumer_validators(monkeypatch)
    fixture = inputs()
    outputs = builder.build_pre_review_outputs(root=root, **fixture)
    builder.write_or_check_outputs(root, outputs, write=True)
    consumers = materialize_consumers(root, outputs)
    materialize_attestation(root, outputs, consumers)
    return fixture, outputs, consumers


def test_exact_scope_is_106_paths_and_excludes_unchanged_or_review_consumers() -> None:
    assert len(builder.IMPLEMENTATION_PATHS) == len(set(builder.IMPLEMENTATION_PATHS)) == 106
    assert builder.VERIFICATION_INPUT_PATHS == (Path("tests/test_report_retention_operational_safety.py"),)
    assert builder.VERIFICATION_INPUT_PATHS[0].as_posix() not in builder.IMPLEMENTATION_PATHS
    for expected in (
        "deploy/config/walksafe-backend.env.example",
        "deploy/systemd/walksafe-backend.service",
        "scripts/check_walksafe_backup_source_20260713.py",
        "backend/tests/test_backup_source.py",
        "scripts/check_report_retention_dry_run.py",
        "backend/tests/test_report_retention.py",
        "tests/test_report_retention_encrypted_objects.py",
        "tests/test_release_evidence_gate.py",
    ):
        assert expected in builder.IMPLEMENTATION_PATHS
    serialized = "\n".join(builder.IMPLEMENTATION_PATHS)
    for forbidden in ("review-subject", "review-attestation", "independent-review", "completion-receipt", "r023", "successor-r012"):
        assert forbidden not in serialized


def test_pre_review_outputs_are_deterministic_and_bind_verification_input() -> None:
    fixture = inputs()
    first = builder.build_pre_review_outputs(**fixture)
    second = builder.build_pre_review_outputs(**fixture)
    assert first == second and tuple(first) == builder.PRE_REVIEW_OUTPUTS and len(first) == 9
    verification = parse(first, builder.VERIFICATION_REL)
    subject = parse(first, builder.REVIEW_SUBJECT_REL)
    expected_set = builder.object_sha256(verification_rows())
    assert verification["verification_input_manifest"] == verification_rows()
    assert verification["verification_input_content_set_sha256"] == expected_set
    assert subject["verification_input_content_set_sha256"] == expected_set


def test_five_receipts_bind_logs_commands_seq42_scope_inputs_and_seals() -> None:
    outputs = builder.build_pre_review_outputs(**inputs())
    verification = parse(outputs, builder.VERIFICATION_REL)
    assert verification["internal_lane_count"] == 5
    for spec, check in zip(builder.LANES, verification["checks"], strict=True):
        receipt = parse(outputs, spec.receipt_rel)
        seal_value = receipt.pop("receipt_content_sha256")
        assert seal_value == builder.object_sha256(receipt)
        assert check["command"] == spec.command and check["exit_code"] == 0
        assert check["execution_event_sequence"] == 42
        assert receipt["verification_input_manifest"] == verification_rows()
        assert check["verification_input_content_set_sha256"] == builder.object_sha256(verification_rows())


@pytest.mark.parametrize("old,new,match", [
    (b"WALKSAFE_COMMAND_EXIT_CODE=0", b"WALKSAFE_COMMAND_EXIT_CODE=1", "EXIT_CODE"),
    (b"WALKSAFE_EXECUTION_EVENT_SEQUENCE=42", b"WALKSAFE_EXECUTION_EVENT_SEQUENCE=41", "EVENT_SEQUENCE"),
    (b"WALKSAFE_LANE_STATUS=PASS", b"WALKSAFE_LANE_STATUS=FAIL", "LANE_STATUS"),
    (b"WALKSAFE_VERIFICATION_INPUT_CONTENT_SET_SHA256=", b"WALKSAFE_VERIFICATION_INPUT_CONTENT_SET_SHA256=f", "VERIFICATION_INPUT"),
])
def test_log_tamper_fails_closed(old: bytes, new: bytes, match: str) -> None:
    fixture, spec = inputs(), builder.LANES[0]
    fixture["log_snapshots"][spec.lane_id] = fixture["log_snapshots"][spec.lane_id].replace(old, new, 1)
    with pytest.raises(builder.BuildError, match=match):
        builder.build_pre_review_outputs(**fixture)


@pytest.mark.parametrize("lane_index,bad_line", [
    (2, "1 failed, 441 passed in 1.00s"),
    (2, "ERROR collecting tests/test_spoof.py"),
    (2, "1 xfailed in 1.00s"),
    (1, "not ok 77 - spoof"),
    (1, "  not ok 77 - indented spoof"),
    (1, "# fail 1"),
    (1, "# cancelled 1"),
    (1, "1..76"),
    (0, "BUILD FAILED in 1s"),
    (0, "BUILD SUCCESSFULISH"),
    (0, "> Task :app:testDebugUnitTest UP-TO-DATE"),
    (0, "> Task :app:testDebugUnitTest FROM-CACHE"),
    (2, "BUILD SUCCESSFUL in 1s"),
])
def test_raw_failure_or_stale_evidence_is_rejected_even_with_success(lane_index: int, bad_line: str) -> None:
    spec = builder.LANES[lane_index]
    output = "\n".join([*raw_output(spec), bad_line]) + "\n"
    with pytest.raises(builder.BuildError, match="failure evidence|stale or cached|terminal summar|TAP plan"):
        builder.observed_lane_markers(spec, output, Path("/unused"))


def test_gradle_noop_lifecycle_task_may_be_up_to_date_while_evidence_tasks_are_fresh() -> None:
    spec = builder.LANES[0]
    lifecycle_lines = [
        f"> Task {task} UP-TO-DATE"
        for task in sorted(builder.GRADLE_UP_TO_DATE_NOOP_TASKS)
    ]
    output = "\n".join([*lifecycle_lines, *raw_output(spec)]) + "\n"
    assert builder.observed_lane_markers(spec, output, Path("/unused")) == dict(spec.exact_markers)


@pytest.mark.parametrize(
    "task",
    (
        ":app:compileDebugKotlin",
        ":app:compileDebugUnitTestKotlin",
        ":app:processDebugResources",
    ),
)
def test_non_allowlisted_gradle_dependency_cannot_be_up_to_date(task: str) -> None:
    spec = builder.LANES[0]
    output = "\n".join([f"> Task {task} UP-TO-DATE", *raw_output(spec)]) + "\n"
    with pytest.raises(builder.BuildError, match="stale or cached Gradle evidence"):
        builder.observed_lane_markers(spec, output, Path("/unused"))


def test_extra_pytest_success_summary_is_rejected() -> None:
    spec = builder.LANES[2]
    output = "\n".join([*raw_output(spec), "1 passed in 1.00s"]) + "\n"
    with pytest.raises(builder.BuildError, match="terminal summaries"):
        builder.observed_lane_markers(spec, output, Path("/unused"))


def test_android_raw_xml_sha_bytes_and_structure_are_reparsed() -> None:
    spec = builder.LANES[0]
    output = "\n".join(raw_output(spec)) + "\n"
    observed = builder.observed_lane_markers(spec, output, Path("/unused"))
    assert observed["WALKSAFE_ANDROID_JUNIT_TESTS"] == "147"
    tampered = output.replace("sha256=", "sha256=" + "0" * 64 + "\nIGNORED=", 1)
    with pytest.raises(builder.BuildError, match="XML bytes missing|SHA-256|failure evidence|count differs|malformed Android"):
        builder.observed_lane_markers(spec, tampered, Path("/unused"))
    host = dict(spec.exact_markers)["WALKSAFE_ANDROID_HOST_PYTEST_PASSED"]
    failing = "\n".join(
        [
            "> Task :app:testDebugUnitTest",
            "BUILD SUCCESSFUL",
            *android_junit_lines(failures=1),
            f"{host} passed in 1.00s",
            "> Task :app:compileDebugAndroidTestKotlin",
            "> Task :app:assembleDebug",
            "> Task :app:lintDebug",
            "BUILD SUCCESSFUL",
        ]
    ) + "\n"
    with pytest.raises(builder.BuildError, match="count differs"):
        builder.observed_lane_markers(spec, failing, Path("/unused"))


def test_android_command_forces_fresh_gradle_and_emits_xml_bytes() -> None:
    assert builder.ANDROID_COMMAND.count("--rerun-tasks") == 2
    assert "--emit-android-junit-xml" in builder.ANDROID_COMMAND
    assert ".venv/bin/pytest" not in builder.ANDROID_COMMAND
    assert (
        f"{builder.LOCKED_PYTHON} -B -m pytest -p no:cacheprovider -q "
        "tests/test_android_field_session_summary.py tests/test_release_evidence_gate.py"
    ) in builder.ANDROID_COMMAND
    assert len(builder.EXPECTED_ANDROID_JUNIT_XML_RELATIVES) == 19


def test_marker_only_log_without_raw_command_evidence_fails_closed() -> None:
    fixture, spec = inputs(), builder.LANES[2]
    content = fixture["log_snapshots"][spec.lane_id]
    start = content.index((builder.RAW_OUTPUT_BEGIN + "\n").encode())
    end = content.index((builder.RAW_OUTPUT_END + "\n").encode()) + len(builder.RAW_OUTPUT_END) + 1
    fixture["log_snapshots"][spec.lane_id] = content[:start] + content[end:]
    with pytest.raises(builder.BuildError, match="raw output boundary"):
        builder.build_pre_review_outputs(**fixture)


def test_verification_input_live_pin_and_drift_rejection() -> None:
    manifest, digest = builder.verification_input_manifest(builder.ROOT)
    assert manifest == verification_rows() and digest == builder.object_sha256(manifest)
    fixture = inputs()
    fixture["verification_input_rows"][0]["sha256"] = "0" * 64
    with pytest.raises(builder.BuildError, match="verification input identity"):
        builder.build_pre_review_outputs(**fixture)


def test_not_run_and_not_eligible_boundaries_are_exact() -> None:
    boundary = parse(builder.build_pre_review_outputs(**inputs()), builder.VERIFICATION_REL)["evidence_boundary"]
    for key in ("formal_test_status", "formal_279_status", "actual_device_status", "external_tls_status", "external_kms_status", "external_cloud_status", "external_backup_restore_status", "external_security_review_status", "external_legal_review_status", "external_privacy_review_status", "production_deployment_status", "release_gate_status"):
        assert boundary[key] == "NOT_RUN"
    assert boundary["release_status"] == "NOT_ELIGIBLE" and boundary["release_gates_waived"] is False


def test_write_check_round_trip_enforces_mode_uid_and_single_link(tmp_path: Path) -> None:
    outputs = builder.build_pre_review_outputs(**inputs())
    builder.write_or_check_outputs(tmp_path, outputs, write=True)
    builder.write_or_check_outputs(tmp_path, outputs, write=False)
    first = tmp_path / next(iter(outputs))
    assert stat.S_IMODE(first.stat().st_mode) == 0o600 and first.stat().st_uid == os.getuid()
    first.chmod(0o644)
    with pytest.raises(builder.BuildError, match="mode"):
        builder.write_or_check_outputs(tmp_path, outputs, write=False)

    hardlink_root = tmp_path / "hardlink"
    hardlink_root.mkdir()
    builder.write_or_check_outputs(hardlink_root, outputs, write=True)
    target = hardlink_root / next(iter(outputs))
    os.link(target, hardlink_root / "alias")
    with pytest.raises(builder.BuildError, match="hard-linked"):
        builder.write_or_check_outputs(hardlink_root, outputs, write=False)


def test_transaction_manifest_hardlink_and_directory_authority_are_rejected(tmp_path: Path) -> None:
    outputs = builder.build_pre_review_outputs(**inputs())

    def stop(index: int, _relative: Path) -> None:
        if index == 0:
            raise RuntimeError("stop")

    with pytest.raises(RuntimeError):
        builder.write_or_check_outputs(tmp_path, outputs, write=True, publish_hook=stop)
    transaction = tmp_path / builder.RESULT_DIR_REL / ".pre-review-transaction"
    manifest = transaction / "manifest.json"
    os.link(manifest, tmp_path / "manifest-alias")
    with pytest.raises(builder.BuildError, match="hard-linked"):
        builder.write_or_check_outputs(tmp_path, outputs, write=True)

    authority_root = tmp_path / "authority"
    authority_root.mkdir()
    with pytest.raises(RuntimeError):
        builder.write_or_check_outputs(authority_root, outputs, write=True, publish_hook=stop)
    transaction = authority_root / builder.RESULT_DIR_REL / ".pre-review-transaction"
    transaction.chmod(0o755)
    with pytest.raises(builder.BuildError, match="transaction authority"):
        builder.write_or_check_outputs(authority_root, outputs, write=True)


def test_output_transaction_recovers_forward_after_partial_publish(tmp_path: Path) -> None:
    outputs = builder.build_pre_review_outputs(**inputs())

    def stop_after_third(index: int, _relative: Path) -> None:
        if index == 2:
            raise RuntimeError("simulated process stop")

    with pytest.raises(RuntimeError, match="simulated"):
        builder.write_or_check_outputs(tmp_path, outputs, write=True, publish_hook=stop_after_third)
    assert [relative for relative in outputs if (tmp_path / relative).exists()] == list(outputs)[:3]
    builder.write_or_check_outputs(tmp_path, outputs, write=True)
    builder.write_or_check_outputs(tmp_path, outputs, write=False)
    assert not (tmp_path / builder.RESULT_DIR_REL / ".pre-review-transaction").exists()


def test_snapshot_json_rejects_duplicate_member_symlink_ancestor_and_hardlink(tmp_path: Path) -> None:
    duplicate = tmp_path / "duplicate.json"
    duplicate.write_text('{"x":1,"x":2}\n')
    with pytest.raises(builder.BuildError, match="duplicate JSON"):
        builder.snapshot_json(tmp_path, Path("duplicate.json"))

    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "value.json").write_text("{}\n")
    (tmp_path / "link").symlink_to(outside, target_is_directory=True)
    with pytest.raises(builder.BuildError, match="safely snapshot"):
        builder.snapshot_json(tmp_path, Path("link/value.json"))

    original = tmp_path / "original.json"
    original.write_text("{}\n")
    os.link(original, tmp_path / "second.json")
    with pytest.raises(builder.BuildError, match="hard-linked"):
        builder.snapshot_json(tmp_path, Path("original.json"), require_single_link=True)


def test_partial_and_marker_only_consumers_fail_closed(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    path = tmp_path / builder.R023_GAP_REL
    path.parent.mkdir(parents=True)
    path.write_text("{}\n")
    with pytest.raises(builder.BuildError, match="partial"):
        builder.validate_consumer_bindings(tmp_path, "1" * 64, "2" * 64)

    root = tmp_path / "marker-only"
    root.mkdir()
    fixture = inputs()
    outputs = builder.build_pre_review_outputs(root=root, **fixture)
    builder.write_or_check_outputs(root, outputs, write=True)
    bypass_full_consumer_validators(monkeypatch)
    materialize_consumers(root, outputs)
    artifact_path = root / builder.FP048_ARTIFACT_CONSUMERS[0][1]
    artifact_path.write_text(builder.json_text({
        "schema_version": builder.FP048_ARTIFACT_CONSUMERS[0][2],
        "fp048_artifact_trace_successor": {"successor_id": builder.FP048_ARTIFACT_SUCCESSOR_ID},
    }))
    with pytest.raises(builder.BuildError, match="input binding count"):
        builder.validate_consumer_bindings(root, builder.bytes_sha256(outputs[builder.IMPLEMENTATION_REL].encode()), builder.bytes_sha256(outputs[builder.VERIFICATION_REL].encode()))


def test_all_11_consumers_bind_exact_sources_and_call_full_validators(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    calls = bypass_full_consumer_validators(monkeypatch)
    outputs = builder.build_pre_review_outputs(root=tmp_path, **inputs())
    builder.write_or_check_outputs(tmp_path, outputs, write=True)
    consumers = materialize_consumers(tmp_path, outputs)
    assert len(consumers) == 11 and [row["role"] for row in consumers] == [row[0] for row in builder.CONSUMER_CONTRACTS]
    assert [name for name, _pins in calls] == ["r023", "artifact", "r012"]
    assert calls[1][1][builder.IMPLEMENTATION_REL] == builder.bytes_sha256(outputs[builder.IMPLEMENTATION_REL].encode())
    assert set(calls[2][1]) == {builder.IMPLEMENTATION_REL, builder.VERIFICATION_REL, builder.R023_GAP_REL, *(relative for _, relative, _ in builder.FP048_ARTIFACT_CONSUMERS)}


def test_r023_seals_planned_next_and_producer_hashes_reject_spoofs(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    bypass_full_consumer_validators(monkeypatch)
    outputs = builder.build_pre_review_outputs(root=tmp_path, **inputs())
    builder.write_or_check_outputs(tmp_path, outputs, write=True)
    materialize_consumers(tmp_path, outputs)
    backlog_path = tmp_path / builder.R023_BACKLOG_REL
    backlog = json.loads(backlog_path.read_text())
    backlog["next_single_action"]["status"] = "MISSING"
    write_json(tmp_path, builder.R023_BACKLOG_REL, seal(backlog, "backlog_content_sha256"))
    with pytest.raises(builder.BuildError, match="PLANNED_NEXT"):
        builder.validate_consumer_bindings(tmp_path, builder.bytes_sha256(outputs[builder.IMPLEMENTATION_REL].encode()), builder.bytes_sha256(outputs[builder.VERIFICATION_REL].encode()))


def test_r012_marker_only_and_output_binding_spoofs_are_rejected(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    bypass_full_consumer_validators(monkeypatch)
    outputs = builder.build_pre_review_outputs(root=tmp_path, **inputs())
    builder.write_or_check_outputs(tmp_path, outputs, write=True)
    materialize_consumers(tmp_path, outputs)
    ledger_rel = builder.R012_DIR_REL / "phase1-exact257-successor-ledger-r012.json"
    write_json(tmp_path, ledger_rel, {"schema_version": "walksafe.phase1-exact257-successor-ledger.v12"})
    with pytest.raises(builder.BuildError, match="progress application"):
        builder.validate_consumer_bindings(tmp_path, builder.bytes_sha256(outputs[builder.IMPLEMENTATION_REL].encode()), builder.bytes_sha256(outputs[builder.VERIFICATION_REL].encode()))

    other = tmp_path / "binding"
    other.mkdir()
    outputs = builder.build_pre_review_outputs(root=other, **inputs())
    builder.write_or_check_outputs(other, outputs, write=True)
    materialize_consumers(other, outputs)
    receipt_rel = builder.R012_DIR_REL / "phase1-exact257-successor-check-receipt-r012.json"
    receipt = json.loads((other / receipt_rel).read_text())
    receipt["output_bindings"][0]["sha256"] = "0" * 64
    write_json(other, receipt_rel, receipt)
    with pytest.raises(builder.BuildError, match="output bindings"):
        builder.validate_consumer_bindings(other, builder.bytes_sha256(outputs[builder.IMPLEMENTATION_REL].encode()), builder.bytes_sha256(outputs[builder.VERIFICATION_REL].encode()))


def test_post_review_receipt_satisfies_completion_applier_contract(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    fixture, outputs, consumers = prepare_post(tmp_path, monkeypatch)
    post = builder.build_post_review_outputs(root=tmp_path, pre_review_kwargs=fixture)
    review = parse(post, builder.INDEPENDENT_REVIEW_REL)
    receipt = parse(post, builder.COMPLETION_RECEIPT_REL)
    assert review["reviewed_consumer_bindings"] == consumers
    assert receipt["target_goal_content_sha256"] == builder.EXPECTED_GOAL_SHA256
    assert receipt["work_item_id"] == builder.EXPECTED_WORK_ITEM_ID
    assert receipt["source_policy_ids"] == ["FP-048"] and receipt["gap_ids"] == ["GAP-057"]
    assert receipt["execution_start_event_sha256"] == builder.EXPECTED_EXECUTION_EVENT_SHA256
    assert receipt["implementation_start_gate_binding"] == authority()["event"]["implementation_start_gate_binding"]
    assert receipt["reviewer"]["authority"] == "INTERNAL_REPOSITORY_CONTROL"
    assert receipt["reviewer"]["decision"] == "APPROVED"
    assert receipt["reviewer"]["decided_at"] == receipt["generated_at"] == receipt["completed_at"]
    assert receipt["completion_boundary"] == builder.completion_boundary()
    assert receipt["result_evidence"] == [
        {"kind": kind, "path": relative.as_posix(), "sha256": builder.bytes_sha256(outputs[relative].encode())}
        for kind, relative in (
            ("IMPLEMENTATION_RECORD", builder.IMPLEMENTATION_REL),
            ("VERIFICATION_RESULT", builder.VERIFICATION_REL),
            ("SUCCESSOR_TRACE", builder.SUCCESSOR_REL),
        )
    ]
    canonical = {"ARTIFACT_CHANGE_LOG", "ARTIFACT_REGISTER", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "REQUIREMENTS_TRACEABILITY", "GAP_R023", "BACKLOG_R023"}
    by_role = {row["role"]: row for row in receipt["downstream_consumer_bindings"]}
    assert canonical <= set(by_role)
    for role in canonical:
        assert builder.SHA256_RE.fullmatch(by_role[role]["sha256"])
    paths = [item["path"] for item in receipt["output_evidence_manifest"]]
    assert paths == [relative.as_posix() for relative in (*builder.PRE_REVIEW_OUTPUTS, builder.INDEPENDENT_REVIEW_REL)]
    assert builder.COMPLETION_RECEIPT_REL.as_posix() not in paths


def test_stale_subject_and_executor_as_reviewer_are_rejected(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    fixture = inputs()
    stale_root = tmp_path / "stale"
    stale_root.mkdir()
    outputs = builder.build_pre_review_outputs(root=stale_root, **fixture)
    builder.write_or_check_outputs(stale_root, outputs, write=True)
    path = stale_root / builder.IMPLEMENTATION_REL
    path.chmod(0o600)
    path.write_text(outputs[builder.IMPLEMENTATION_REL] + " ")
    path.chmod(0o600)
    with pytest.raises(builder.BuildError, match="output differs"):
        builder.build_post_review_outputs(root=stale_root, pre_review_kwargs=fixture)

    reviewer_root = tmp_path / "reviewer"
    reviewer_root.mkdir()
    bypass_full_consumer_validators(monkeypatch)
    outputs = builder.build_pre_review_outputs(root=reviewer_root, **fixture)
    builder.write_or_check_outputs(reviewer_root, outputs, write=True)
    consumers = materialize_consumers(reviewer_root, outputs)
    materialize_attestation(reviewer_root, outputs, consumers, reviewer_id=builder.EXECUTOR_ID)
    with pytest.raises(builder.BuildError, match="reviewer"):
        builder.build_post_review_outputs(root=reviewer_root, pre_review_kwargs=fixture)


def test_capture_server_lane_parses_exact_counts_and_publishes_atomic_log(tmp_path: Path) -> None:
    times = iter((START, START + timedelta(seconds=1)))
    output = ("\n".join(raw_output(builder.LANES[2])) + "\n").encode()
    calls = []

    def runner(command: str, **kwargs):
        calls.append((command, kwargs))
        return subprocess.CompletedProcess(command, 0, stdout=output)

    path = builder.capture_lane(
        "SERVER_ORIGINAL_DB_BACKUP_BOUNDARY",
        root=tmp_path,
        runner=runner,
        clock=lambda: next(times),
        authority=authority(),
        implementation_rows=rows(),
        verification_input_rows=verification_rows(),
        test_mode=True,
    )
    assert calls[0][0] == builder.SERVER_COMMAND
    assert stat.S_IMODE(path.stat().st_mode) == 0o600 and path.stat().st_nlink == 1
    content = path.read_text()
    assert "WALKSAFE_RETENTION_INTERNAL_PASSED=38" in content
    assert "WALKSAFE_BACKUP_INTERNAL_PASSED=64" in content


def test_capture_android_keeps_command_emitted_xml_and_never_synthesizes_summary(tmp_path: Path) -> None:
    times = iter((START, START + timedelta(seconds=1)))
    output = ("\n".join(raw_output(builder.LANES[0])) + "\n").encode()

    def runner(command: str, **_kwargs):
        return subprocess.CompletedProcess(command, 0, stdout=output)

    path = builder.capture_lane(
        "ANDROID_PROTECTED_STORAGE",
        root=tmp_path,
        runner=runner,
        clock=lambda: next(times),
        authority=authority(),
        implementation_rows=rows(),
        verification_input_rows=verification_rows(),
        test_mode=True,
    )
    content = path.read_text()
    assert content.count("ANDROID_JUNIT_XML_BYTES") == 19
    assert "ANDROID_JUNIT_XML_SUMMARY" not in content
    with pytest.raises(builder.BuildError, match="injected Android JUnit"):
        builder.capture_lane(
            "ANDROID_PROTECTED_STORAGE",
            root=tmp_path / "other",
            authority=authority(),
            implementation_rows=rows(),
            verification_input_rows=verification_rows(),
            junit_summary={"suites": 19},
            test_mode=True,
        )


@pytest.mark.parametrize("returncode,output,match", [
    (1, b"441 passed, 11 skipped in 1.00s\n38 passed in 1.00s\n64 passed in 1.00s\n", "exited"),
    (0, b"441 passed, 11 skipped in 1.00s\n37 passed in 1.00s\n64 passed in 1.00s\n", "summaries"),
])
def test_capture_failure_or_count_drift_never_publishes(tmp_path: Path, returncode: int, output: bytes, match: str) -> None:
    times = iter((START, START + timedelta(seconds=1)))

    def runner(command: str, **kwargs):
        return subprocess.CompletedProcess(command, returncode, stdout=output)

    with pytest.raises(builder.BuildError, match=match):
        builder.capture_lane(
            "SERVER_ORIGINAL_DB_BACKUP_BOUNDARY",
            root=tmp_path,
            runner=runner,
            clock=lambda: next(times),
            authority=authority(),
            implementation_rows=rows(),
            verification_input_rows=verification_rows(),
            test_mode=True,
        )
    assert not (tmp_path / builder.LANES[2].log_rel).exists()


def test_dirty_snapshot_has_priority(monkeypatch: pytest.MonkeyPatch) -> None:
    state = {"dirty_snapshot": {"paths": [{"path": "x", "path_role": "CURRENT", "worktree": {"state": "PRESENT", "sha256": "a" * 64}}]}}
    monkeypatch.setattr(builder, "pinned_head_blob", lambda *_: pytest.fail("must not read HEAD"))
    assert builder.before_state(Path("/unused"), "x", state) == (True, "a" * 64, "SEQ42_START_GATE_DIRTY_SNAPSHOT")
