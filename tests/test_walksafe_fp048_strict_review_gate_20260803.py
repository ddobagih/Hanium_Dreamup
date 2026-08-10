from __future__ import annotations

import copy
from copy import deepcopy
from datetime import datetime, timedelta
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import sys

import pytest

from scripts import build_walksafe_fp048_strict_review_gate_20260803 as gate


TRACE_FIXTURE_PATH = Path(__file__).with_name(
    "test_walksafe_fp048_encryption_connection_security_incident_trace_20260802.py"
)
TRACE_FIXTURE_SPEC = importlib.util.spec_from_file_location(
    "_walksafe_fp048_trace_fixture_support",
    TRACE_FIXTURE_PATH,
)
assert TRACE_FIXTURE_SPEC is not None and TRACE_FIXTURE_SPEC.loader is not None
trace_fixture = importlib.util.module_from_spec(TRACE_FIXTURE_SPEC)
sys.modules[TRACE_FIXTURE_SPEC.name] = trace_fixture
TRACE_FIXTURE_SPEC.loader.exec_module(trace_fixture)

trace = gate.trace
NOW = datetime(2026, 8, 3, 4, 0, 0, tzinfo=gate.KST)


def prepare_root(
    root: Path,
    monkeypatch: pytest.MonkeyPatch,
    *,
    with_attestation: bool,
) -> tuple[dict, dict[Path, str], list[dict]]:
    if with_attestation:
        fixture, outputs, consumers = trace_fixture.prepare_post(root, monkeypatch)
    else:
        trace_fixture.bypass_full_consumer_validators(monkeypatch)
        fixture = trace_fixture.inputs()
        outputs = trace.build_pre_review_outputs(root=root, **fixture)
        trace.write_or_check_outputs(root, outputs, write=True)
        consumers = trace_fixture.materialize_consumers(root, outputs)
    monkeypatch.setattr(
        gate,
        "_run_full_artifact_successor_validation_compatible",
        trace._run_full_artifact_successor_validation,
    )
    monkeypatch.setattr(
        gate,
        "_run_full_r012_validation_compatible",
        trace._run_full_r012_validation,
    )
    use_fixture_log_auxiliary_contract(monkeypatch, fixture)
    materialize_known_auxiliary(
        root,
        log_snapshots=fixture["log_snapshots"],
    )
    return fixture, outputs, consumers


def attestation_path(root: Path) -> Path:
    return root / trace.REVIEW_ATTESTATION_REL


def read_attestation(root: Path) -> dict:
    return json.loads(attestation_path(root).read_text(encoding="utf-8"))


def write_attestation_value(root: Path, value: dict) -> bytes:
    raw = trace.json_text(value).encode()
    attestation_path(root).write_bytes(raw)
    attestation_path(root).chmod(0o600)
    return raw


def assert_no_post_outputs(root: Path) -> None:
    assert not any(
        os.path.lexists(root / relative) for relative in trace.POST_REVIEW_OUTPUTS
    )
    assert not os.path.lexists(
        root / trace.RESULT_DIR_REL / ".post-review-transaction"
    )


def assert_exact_result_inventory(root: Path, *, include_post: bool) -> None:
    result = root / trace.RESULT_DIR_REL
    expected_relatives = [
        *(relative.relative_to(trace.RESULT_DIR_REL) for relative in trace.PRE_REVIEW_OUTPUTS),
        trace.REVIEW_ATTESTATION_REL.relative_to(trace.RESULT_DIR_REL),
    ]
    if include_post:
        expected_relatives.extend(
            relative.relative_to(trace.RESULT_DIR_REL)
            for relative in trace.POST_REVIEW_OUTPUTS
        )
    expected_relatives.extend(
        relative.relative_to(trace.RESULT_DIR_REL)
        for relative in known_auxiliary_file_relatives()
    )
    expected_files = {relative.as_posix() for relative in expected_relatives}
    expected_directories = {
        parent.as_posix()
        for relative in expected_relatives
        for parent in relative.parents
        if parent != Path(".")
    }
    actual_files = {
        path.relative_to(result).as_posix()
        for path in result.rglob("*")
        if path.is_file() or path.is_symlink()
    }
    actual_directories = {
        path.relative_to(result).as_posix()
        for path in result.rglob("*")
        if path.is_dir() and not path.is_symlink()
    }
    assert actual_files == expected_files
    assert actual_directories == expected_directories


def known_auxiliary_file_relatives() -> list[Path]:
    manifest = json.loads(
        (gate.ROOT / gate.RETIREMENT_MANIFEST_REL).read_text(encoding="utf-8")
    )
    return [
        *gate.KNOWN_LOG_SHA256_BY_REL,
        gate.RETIREMENT_MANIFEST_REL,
        *(
            gate.RETIREMENT_DIR_REL / row["archive_path"]
            for field in ("archived_producer_sources", "archived_outputs")
            for row in manifest[field]
        ),
    ]


def materialize_known_auxiliary(
    root: Path,
    *,
    log_snapshots: dict[str, bytes] | None = None,
) -> list[Path]:
    relatives = known_auxiliary_file_relatives()
    result = root / trace.RESULT_DIR_REL
    for relative in relatives:
        destination = root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        for parent in (destination.parent, *destination.parent.parents):
            if parent == result.parent:
                break
            parent.chmod(0o700)
        lane = next(
            (candidate for candidate in trace.LANES if candidate.log_rel == relative),
            None,
        )
        content = (
            log_snapshots[lane.lane_id]
            if lane is not None and log_snapshots is not None
            else (gate.ROOT / relative).read_bytes()
        )
        destination.write_bytes(content)
        destination.chmod(0o600)
    return relatives


def remove_test_tree(path: Path) -> None:
    for child in sorted(path.rglob("*"), key=lambda item: len(item.parts), reverse=True):
        if child.is_dir() and not child.is_symlink():
            child.rmdir()
        else:
            child.unlink()
    path.rmdir()


def use_fixture_log_auxiliary_contract(
    monkeypatch: pytest.MonkeyPatch,
    fixture: dict,
) -> None:
    snapshots = fixture["log_snapshots"]
    monkeypatch.setattr(
        gate,
        "KNOWN_LOG_SHA256_BY_REL",
        {
            lane.log_rel: trace.bytes_sha256(snapshots[lane.lane_id])
            for lane in trace.LANES
        },
    )


def test_gate_stays_outside_the_exact106_source_binding() -> None:
    assert gate.__file__ is not None
    assert Path(gate.__file__).resolve().relative_to(gate.ROOT).as_posix() not in trace.IMPLEMENTATION_PATHS
    assert Path(__file__).resolve().relative_to(gate.ROOT).as_posix() not in trace.IMPLEMENTATION_PATHS
    assert trace.file_sha256(gate.ROOT / trace.TOOLING_PATHS[0]) == "c7ac40d4d63ce7fbc0596511bc1c68f796ebc07b489d88540462b54ede91a44a"
    assert trace.file_sha256(gate.ROOT / trace.TOOLING_PATHS[1]) == "1f9fc9c2a3f85c3fe85f386f9682a4963c49cd598a4f6e90088921731182d09c"


def test_known_auxiliary_contract_matches_production_bindings() -> None:
    result = gate.ROOT / trace.RESULT_DIR_REL
    inventory = {
        path.relative_to(result).as_posix()
        for path in result.rglob("*")
    }
    auxiliary = gate._validate_known_auxiliary_inventory(gate.ROOT, inventory)
    assert len(auxiliary) == 16
    for relative_text in auxiliary:
        path = result / relative_text
        info = path.lstat()
        assert not path.is_symlink()
        if path.is_dir():
            assert stat.S_IMODE(info.st_mode) == 0o700
        else:
            assert stat.S_IMODE(info.st_mode) == 0o600
            assert info.st_nlink == 1

    verification = json.loads(
        (gate.ROOT / trace.VERIFICATION_REL).read_text(encoding="utf-8")
    )
    checks = verification["checks"]
    assert len(checks) == len(trace.LANES) == len(gate.KNOWN_LOG_SHA256_BY_REL)
    for check, lane in zip(checks, trace.LANES, strict=True):
        raw = (gate.ROOT / lane.log_rel).read_bytes()
        digest = trace.bytes_sha256(raw)
        assert digest == gate.KNOWN_LOG_SHA256_BY_REL[lane.log_rel]
        assert check["lane_id"] == lane.lane_id
        assert check["output_path"] == lane.log_rel.as_posix()
        assert check["output_sha256"] == digest

    manifest_raw = (gate.ROOT / gate.RETIREMENT_MANIFEST_REL).read_bytes()
    assert trace.bytes_sha256(manifest_raw) == gate.RETIREMENT_MANIFEST_SHA256
    manifest = json.loads(manifest_raw)
    rows = [
        row
        for field in ("archived_producer_sources", "archived_outputs")
        for row in manifest[field]
    ]
    assert len(rows) == 6
    for row in rows:
        archived = gate.ROOT / gate.RETIREMENT_DIR_REL / row["archive_path"]
        raw = archived.read_bytes()
        info = archived.stat()
        assert len(raw) == row["byte_count"]
        assert trace.bytes_sha256(raw) == row["sha256"]
        assert stat.S_IMODE(info.st_mode) == 0o600
        assert info.st_nlink == 1


def test_production_prepare_review_context_runs_real_consumer11_end_to_end() -> None:
    original_validator = trace._run_full_artifact_successor_validation
    context = gate.prepare_review_context(gate.ROOT)
    expected = [
        (role, relative.as_posix(), schema)
        for role, relative, schema in trace.CONSUMER_CONTRACTS
    ]
    actual = [
        (row["role"], row["path"], row["schema_version"])
        for row in context.consumer_bindings
    ]
    assert actual == expected
    assert sum(
        row["role"].startswith("EXACT257_R012_")
        for row in context.consumer_bindings
    ) == 3
    for row in context.consumer_bindings:
        assert row["sha256"] == trace.file_sha256(gate.ROOT / row["path"])
    assert trace._run_full_artifact_successor_validation is original_validator


def test_production_post_review_derivation_runs_real_consumer11_without_write() -> None:
    original_builder = trace.build_post_review_outputs
    original_validator = trace.validate_consumer_bindings
    before = {
        relative: (
            (gate.ROOT / relative).read_bytes()
            if os.path.lexists(gate.ROOT / relative)
            else None
        )
        for relative in trace.POST_REVIEW_OUTPUTS
    }
    transaction_before = os.path.lexists(gate.ROOT / gate.POST_TRANSACTION_REL)

    outputs, attestation = gate.build_strict_post_review_outputs(gate.ROOT)

    assert tuple(outputs) == trace.POST_REVIEW_OUTPUTS
    assert attestation.value["decision"] == "APPROVED"
    assert attestation.value["findings"] == {
        "blocking": 0,
        "major_open": 0,
        "minor_open": 0,
    }
    assert {
        relative: (
            (gate.ROOT / relative).read_bytes()
            if os.path.lexists(gate.ROOT / relative)
            else None
        )
        for relative in trace.POST_REVIEW_OUTPUTS
    } == before
    assert os.path.lexists(gate.ROOT / gate.POST_TRANSACTION_REL) is transaction_before
    assert trace.build_post_review_outputs is original_builder
    assert trace.validate_consumer_bindings is original_validator


@pytest.mark.parametrize("module_mode", [False, True])
def test_direct_and_module_cli_help(module_mode: bool) -> None:
    target = "scripts.build_walksafe_fp048_strict_review_gate_20260803"
    command = [sys.executable, "-B"]
    command.extend(
        ["-m", target]
        if module_mode
        else [str(Path(gate.__file__).resolve())]
    )
    completed = subprocess.run(
        [*command, "--help"],
        cwd=gate.ROOT,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    assert completed.returncode == 0, completed.stderr
    assert "--write-attestation" in completed.stdout
    assert "--check-post-review" in completed.stdout


@pytest.mark.parametrize("group", ["logs", "retirement"])
def test_write_attestation_requires_complete_known_auxiliary_before_link(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    group: str,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=False,
    )
    target = (
        tmp_path / trace.RESULT_DIR_REL / "logs"
        if group == "logs"
        else tmp_path / gate.RETIREMENT_DIR_REL
    )
    remove_test_tree(target)
    before = snapshot_tree(tmp_path)

    with pytest.raises(gate.GateError, match="auxiliary"):
        gate.write_attestation(
            tmp_path,
            pre_review_kwargs=fixture,
            clock=lambda: NOW,
        )

    assert snapshot_tree(tmp_path) == before
    assert not os.path.lexists(attestation_path(tmp_path))


@pytest.mark.parametrize("group", ["logs", "retirement"])
def test_post_review_requires_complete_known_auxiliary_without_mutation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    group: str,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    target = (
        tmp_path / trace.RESULT_DIR_REL / "logs"
        if group == "logs"
        else tmp_path / gate.RETIREMENT_DIR_REL
    )
    remove_test_tree(target)
    before = snapshot_tree(tmp_path)

    with pytest.raises(gate.GateError, match="auxiliary"):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )

    assert snapshot_tree(tmp_path) == before
    assert_no_post_outputs(tmp_path)


@pytest.mark.parametrize("entry_kind", ["file", "directory"])
def test_write_attestation_rejects_foreign_inventory_before_link(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    entry_kind: str,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=False,
    )
    foreign = tmp_path / trace.RESULT_DIR_REL / (
        "foreign-before-attestation.txt"
        if entry_kind == "file"
        else "foreign-before-attestation"
    )
    if entry_kind == "file":
        foreign.write_bytes(b"foreign\n")
        foreign.chmod(0o600)
    else:
        foreign.mkdir(mode=0o700)
    before = snapshot_tree(tmp_path)

    with pytest.raises(gate.GateError, match="unexpected review result inventory"):
        gate.write_attestation(
            tmp_path,
            pre_review_kwargs=fixture,
            clock=lambda: NOW,
        )

    assert snapshot_tree(tmp_path) == before
    assert not os.path.lexists(attestation_path(tmp_path))


def test_write_attestation_rejects_nonexact_post_link_inventory_transition(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=False,
    )
    foreign = tmp_path / trace.RESULT_DIR_REL / "foreign-after-attestation.txt"

    def add_foreign_entry() -> None:
        foreign.write_bytes(b"foreign\n")
        foreign.chmod(0o600)

    with pytest.raises(gate.GateError, match="unexpected review result inventory"):
        gate.write_attestation(
            tmp_path,
            pre_review_kwargs=fixture,
            clock=lambda: NOW,
            publish_hook=add_foreign_entry,
        )

    assert attestation_path(tmp_path).is_file()
    assert foreign.read_bytes() == b"foreign\n"
    assert_no_post_outputs(tmp_path)


@pytest.mark.parametrize("operation", ["load", "write-attestation", "write-post"])
def test_unjournaled_partial_post_is_rejected_without_mutation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    operation: str,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    outputs, _attestation = gate.build_strict_post_review_outputs(
        tmp_path,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    first_relative = trace.POST_REVIEW_OUTPUTS[0]
    first = tmp_path / first_relative
    first.write_text(outputs[first_relative], encoding="utf-8")
    first.chmod(0o600)
    before = snapshot_tree(tmp_path)

    with pytest.raises(gate.GateError, match="partial post-review"):
        if operation == "load":
            gate.load_strict_attestation(
                tmp_path,
                pre_review_kwargs=fixture,
                now=NOW,
            )
        elif operation == "write-attestation":
            gate.write_attestation(
                tmp_path,
                pre_review_kwargs=fixture,
                clock=lambda: NOW,
            )
        elif operation == "write-post":
            gate.write_or_check_post_review(
                tmp_path,
                write=True,
                pre_review_kwargs=fixture,
                now=NOW,
            )
        else:
            raise AssertionError(operation)

    assert snapshot_tree(tmp_path) == before
    assert not os.path.lexists(tmp_path / gate.POST_TRANSACTION_REL)
    assert not os.path.lexists(tmp_path / trace.POST_REVIEW_OUTPUTS[1])


def test_partial_post_manifest_without_remaining_stage_is_not_laundered(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    outputs, _attestation = gate.build_strict_post_review_outputs(
        tmp_path,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    first_relative = trace.POST_REVIEW_OUTPUTS[0]
    first = tmp_path / first_relative
    first.write_text(outputs[first_relative], encoding="utf-8")
    first.chmod(0o600)
    transaction = tmp_path / gate.POST_TRANSACTION_REL
    transaction.mkdir(mode=0o700)
    _manifest, manifest_raw = trace._forward_transaction_manifest(
        outputs,
        "post-review",
    )
    manifest_path = transaction / "manifest.json"
    manifest_path.write_bytes(manifest_raw)
    manifest_path.chmod(0o600)
    before = snapshot_tree(tmp_path)

    with pytest.raises(gate.GateError, match="durable transaction evidence"):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )

    assert snapshot_tree(tmp_path) == before
    assert not os.path.lexists(transaction / "01.stage")
    assert not os.path.lexists(tmp_path / trace.POST_REVIEW_OUTPUTS[1])


def test_valid_exact_attestation_and_post_round_trip_same_process(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    original = gate._build_post_review_outputs_compatible
    process_ids: list[int] = []

    def observed_build(*args, **kwargs):
        process_ids.append(os.getpid())
        return original(*args, **kwargs)

    monkeypatch.setattr(gate, "_build_post_review_outputs_compatible", observed_build)
    strict = gate.load_strict_attestation(
        tmp_path,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    outputs = gate.write_or_check_post_review(
        tmp_path,
        write=True,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    gate.write_or_check_post_review(
        tmp_path,
        write=False,
        pre_review_kwargs=fixture,
        now=NOW,
    )

    assert process_ids and set(process_ids) == {os.getpid()}
    assert tuple(outputs) == trace.POST_REVIEW_OUTPUTS
    review = json.loads(outputs[trace.INDEPENDENT_REVIEW_REL])
    assert review["reviewed_consumer_bindings"] == consumers
    assert review["attestation_provenance"] == {
        "path": trace.REVIEW_ATTESTATION_REL.as_posix(),
        "sha256": strict.sha256,
    }
    for relative in trace.POST_REVIEW_OUTPUTS:
        info = (tmp_path / relative).stat()
        assert stat.S_IMODE(info.st_mode) == 0o600
        assert info.st_uid == os.getuid() and info.st_nlink == 1
    assert not os.path.lexists(tmp_path / gate.POST_TRANSACTION_REL)
    assert_exact_result_inventory(tmp_path, include_post=True)


@pytest.mark.parametrize("field", gate.FINDING_FIELDS)
@pytest.mark.parametrize("bad", [False, 0.0, "0", 1])
def test_findings_require_exact_int_zero(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    field: str,
    bad: object,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    value = read_attestation(tmp_path)
    value["findings"][field] = bad
    write_attestation_value(tmp_path, value)
    with pytest.raises(gate.GateError, match="findings|finding"):
        gate.load_strict_attestation(
            tmp_path,
            pre_review_kwargs=fixture,
            now=NOW,
        )


def mutate_shape(value: dict, case: str) -> None:
    if case == "top-status-fail":
        value["status"] = "FAIL"
    elif case == "top-external-claim":
        value["external_independence_claimed"] = True
    elif case == "top-missing-decision":
        value.pop("decision")
    elif case == "result-extra":
        value["reviewed_result_sha256_by_kind"]["EXTRA"] = "0" * 64
    elif case == "result-missing":
        value["reviewed_result_sha256_by_kind"].pop("SUCCESSOR_TRACE")
    elif case == "result-drift":
        value["reviewed_result_sha256_by_kind"]["SUCCESSOR_TRACE"] = "0" * 64
    elif case == "consumer-missing":
        value["reviewed_consumer_bindings"].pop()
    elif case == "consumer-extra":
        value["reviewed_consumer_bindings"].append(
            deepcopy(value["reviewed_consumer_bindings"][-1])
        )
    elif case == "consumer-reordered":
        value["reviewed_consumer_bindings"].reverse()
    elif case == "consumer-row-extra":
        value["reviewed_consumer_bindings"][0]["extra"] = False
    elif case == "consumer-sha-drift":
        value["reviewed_consumer_bindings"][0]["sha256"] = "0" * 64
    elif case == "reviewer-id":
        value["reviewer_id"] = trace.EXECUTOR_ID
    elif case == "reviewer-task":
        value["reviewer_task"] = trace.EXECUTOR_TASK
    elif case == "subject-sha":
        value["review_subject_sha256"] = "0" * 64
    elif case == "findings-missing":
        value["findings"].pop("minor_open")
    elif case == "findings-extra":
        value["findings"]["informational"] = 0
    else:
        raise AssertionError(case)


@pytest.mark.parametrize(
    "case",
    [
        "top-status-fail",
        "top-external-claim",
        "top-missing-decision",
        "result-extra",
        "result-missing",
        "result-drift",
        "consumer-missing",
        "consumer-extra",
        "consumer-reordered",
        "consumer-row-extra",
        "consumer-sha-drift",
        "reviewer-id",
        "reviewer-task",
        "subject-sha",
        "findings-missing",
        "findings-extra",
    ],
)
def test_attestation_exact_shape_and_bindings_fail_closed(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    case: str,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    value = read_attestation(tmp_path)
    mutate_shape(value, case)
    write_attestation_value(tmp_path, value)
    with pytest.raises(gate.GateError):
        gate.load_strict_attestation(
            tmp_path,
            pre_review_kwargs=fixture,
            now=NOW,
        )


@pytest.mark.parametrize(
    "field,bad",
    [
        ("release_gates_waived", 0),
        ("external_independence_claimed", 0),
        ("separate_internal_review_pass", 1),
    ],
)
def test_review_boundary_requires_exact_boolean_types(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    field: str,
    bad: int,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    value = read_attestation(tmp_path)
    value["review_boundary"][field] = bad
    write_attestation_value(tmp_path, value)
    with pytest.raises(gate.GateError, match="type differs"):
        gate.load_strict_attestation(
            tmp_path,
            pre_review_kwargs=fixture,
            now=NOW,
        )


@pytest.mark.parametrize(
    "case",
    ["bom", "duplicate", "nan", "minified", "extra-whitespace", "no-newline"],
)
def test_attestation_raw_bytes_are_unique_canonical(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    case: str,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    value = read_attestation(tmp_path)
    canonical = trace.json_text(value).encode()
    if case == "bom":
        raw = b"\xef\xbb\xbf" + canonical
    elif case == "duplicate":
        raw = canonical.replace(
            b'  "schema_version": "1.0",\n',
            b'  "schema_version": "1.0",\n  "schema_version": "1.0",\n',
            1,
        )
    elif case == "nan":
        raw = canonical.replace(b'"blocking": 0', b'"blocking": NaN', 1)
    elif case == "minified":
        raw = (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
    elif case == "extra-whitespace":
        raw = canonical + b"\n"
    elif case == "no-newline":
        raw = canonical.rstrip(b"\n")
    else:
        raise AssertionError(case)
    attestation_path(tmp_path).write_bytes(raw)
    with pytest.raises((gate.GateError, trace.BuildError)):
        gate.load_strict_attestation(
            tmp_path,
            pre_review_kwargs=fixture,
            now=NOW,
        )


@pytest.mark.parametrize(
    "bad",
    [
        "2026-08-02T23:00:00",
        "2026-08-02T14:00:00+00:00",
        "2026-08-02T21:00:00+09:00",
        "2026-08-03T04:00:01+09:00",
        "2026-08-02T23:00:00.000001+09:00",
        0,
    ],
)
def test_reviewed_at_is_canonical_kst_and_bounded(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    bad: object,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    value = read_attestation(tmp_path)
    value["reviewed_at"] = bad
    write_attestation_value(tmp_path, value)
    with pytest.raises(gate.GateError, match="reviewed_at|predates|future"):
        gate.load_strict_attestation(
            tmp_path,
            pre_review_kwargs=fixture,
            now=NOW,
        )


@pytest.mark.parametrize("case", ["mode", "hardlink", "symlink", "directory"])
def test_attestation_file_authority_is_strict(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    case: str,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    path = attestation_path(tmp_path)
    if case == "mode":
        path.chmod(0o644)
    elif case == "hardlink":
        os.link(path, path.with_name("review-attestation-alias.json"))
    elif case == "symlink":
        target = path.with_name("review-attestation-target.json")
        path.replace(target)
        path.symlink_to(target.name)
    elif case == "directory":
        path.unlink()
        path.mkdir()
    else:
        raise AssertionError(case)
    with pytest.raises((gate.GateError, trace.BuildError, OSError)):
        gate.load_strict_attestation(
            tmp_path,
            pre_review_kwargs=fixture,
            now=NOW,
        )


def test_attestation_add_only_idempotence_has_zero_helper_residue(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=False,
    )
    first = gate.write_attestation(
        tmp_path,
        pre_review_kwargs=fixture,
        clock=lambda: NOW,
    )
    before = attestation_path(tmp_path).stat()
    second = gate.write_attestation(
        tmp_path,
        pre_review_kwargs=fixture,
        clock=lambda: NOW + timedelta(minutes=1),
    )
    after = attestation_path(tmp_path).stat()
    assert first.raw == second.raw
    assert (before.st_dev, before.st_ino) == (after.st_dev, after.st_ino)
    assert_exact_result_inventory(tmp_path, include_post=False)


def test_attestation_collision_fails_without_moving_or_replacing_canonical(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=False,
    )
    first = gate.write_attestation(
        tmp_path,
        pre_review_kwargs=fixture,
        clock=lambda: NOW,
    )

    value = deepcopy(first.value)
    value["status"] = "FAIL"
    differing = write_attestation_value(tmp_path, value)
    before = attestation_path(tmp_path).stat()
    with pytest.raises(gate.GateError):
        gate.write_attestation(
            tmp_path,
            pre_review_kwargs=fixture,
            clock=lambda: NOW + timedelta(minutes=2),
        )
    after = attestation_path(tmp_path).stat()
    assert trace._file_identity(after) == trace._file_identity(before)
    assert attestation_path(tmp_path).read_bytes() == differing
    assert_no_post_outputs(tmp_path)


def test_attestation_post_link_swap_is_detected_without_automatic_restore(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=False,
    )
    replacement: bytes | None = None

    def swap_canonical() -> None:
        nonlocal replacement
        value = read_attestation(tmp_path)
        value["status"] = "FAIL"
        replacement = write_attestation_value(tmp_path, value)

    with pytest.raises((gate.GateError, trace.BuildError)):
        gate.write_attestation(
            tmp_path,
            pre_review_kwargs=fixture,
            clock=lambda: NOW,
            publish_hook=swap_canonical,
        )
    assert replacement is not None
    assert attestation_path(tmp_path).read_bytes() == replacement
    assert_no_post_outputs(tmp_path)


def test_attestation_same_bytes_inode_swap_fails_published_identity_cas(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=False,
    )
    moved = tmp_path / "moved-original-attestation.json"
    published_inode: int | None = None

    def swap_same_bytes() -> None:
        nonlocal published_inode
        path = attestation_path(tmp_path)
        raw = path.read_bytes()
        published_inode = path.stat().st_ino
        path.replace(moved)
        path.write_bytes(raw)
        path.chmod(0o600)

    with pytest.raises(gate.GateError, match="published identity changed"):
        gate.write_attestation(
            tmp_path,
            pre_review_kwargs=fixture,
            clock=lambda: NOW,
            publish_hook=swap_same_bytes,
        )
    assert published_inode is not None
    assert attestation_path(tmp_path).read_bytes() == moved.read_bytes()
    assert attestation_path(tmp_path).stat().st_ino != published_inode
    assert moved.stat().st_ino == published_inode
    assert_no_post_outputs(tmp_path)


def test_attestation_directory_fsync_failure_retries_durably_without_rewrite(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=False,
    )
    original_fsync = os.fsync
    failed = False

    def fail_first_directory_fsync(descriptor: int) -> None:
        nonlocal failed
        if not failed and stat.S_ISDIR(os.fstat(descriptor).st_mode):
            failed = True
            raise OSError("injected result directory fsync failure")
        original_fsync(descriptor)

    monkeypatch.setattr(os, "fsync", fail_first_directory_fsync)
    with pytest.raises(OSError, match="directory fsync failure"):
        gate.write_attestation(
            tmp_path,
            pre_review_kwargs=fixture,
            clock=lambda: NOW,
        )
    assert failed and attestation_path(tmp_path).is_file()
    before = attestation_path(tmp_path).stat()

    monkeypatch.setattr(os, "fsync", original_fsync)
    strict = gate.write_attestation(
        tmp_path,
        pre_review_kwargs=fixture,
        clock=lambda: NOW + timedelta(minutes=1),
    )
    after = attestation_path(tmp_path).stat()
    assert strict.raw == attestation_path(tmp_path).read_bytes()
    assert (before.st_dev, before.st_ino) == (after.st_dev, after.st_ino)
    assert_exact_result_inventory(tmp_path, include_post=False)


@pytest.mark.parametrize(
    "phase",
    ["mid-unnamed-write", "after-unnamed-fsync", "after-link"],
)
def test_attestation_unnamed_atomic_publish_recovers_without_helper_residue(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    phase: str,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=False,
    )
    child = os.fork()
    if child == 0:
        if phase in {"mid-unnamed-write", "after-unnamed-fsync"}:
            original_write = gate._write_descriptor

            def abrupt_write(descriptor: int, raw: bytes, *, label: str) -> None:
                if label == "review attestation":
                    if phase == "mid-unnamed-write":
                        os.write(descriptor, raw[: max(1, len(raw) // 2)])
                        os.fsync(descriptor)
                    else:
                        original_write(descriptor, raw, label=label)
                    os._exit(97)
                original_write(descriptor, raw, label=label)

            gate._write_descriptor = abrupt_write
        else:
            original_link = gate._link_unnamed_noreplace

            def abrupt_link(descriptor: int, directory_fd: int, name: str) -> bool:
                linked = original_link(descriptor, directory_fd, name)
                if name == trace.REVIEW_ATTESTATION_REL.name:
                    os._exit(97)
                return linked

            gate._link_unnamed_noreplace = abrupt_link
        try:
            gate.write_attestation(
                tmp_path,
                pre_review_kwargs=fixture,
                clock=lambda: NOW,
            )
        except BaseException:
            os._exit(98)
        os._exit(99)
    _pid, status = os.waitpid(child, 0)
    assert os.WIFEXITED(status) and os.WEXITSTATUS(status) == 97

    strict = gate.write_attestation(
        tmp_path,
        pre_review_kwargs=fixture,
        clock=lambda: NOW + timedelta(minutes=1),
    )
    assert strict.raw == attestation_path(tmp_path).read_bytes()
    assert stat.S_IMODE(attestation_path(tmp_path).stat().st_mode) == 0o600
    assert attestation_path(tmp_path).stat().st_nlink == 1
    assert_exact_result_inventory(tmp_path, include_post=False)


def test_attestation_publish_revalidates_pre_review_snapshot(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=False,
    )
    producer = tmp_path / trace.IMPLEMENTATION_REL

    def mutate_producer() -> None:
        producer.write_bytes(producer.read_bytes() + b" ")

    with pytest.raises(trace.BuildError):
        gate.write_attestation(
            tmp_path,
            pre_review_kwargs=fixture,
            clock=lambda: NOW,
            publish_hook=mutate_producer,
        )
    assert_no_post_outputs(tmp_path)


def test_post_write_revalidates_pre_review_snapshot_before_transaction_or_outputs(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    original = gate._build_post_review_outputs_compatible
    producer = tmp_path / trace.IMPLEMENTATION_REL

    def mutate_after_build(*args, **kwargs):
        outputs = original(*args, **kwargs)
        producer.write_bytes(producer.read_bytes() + b" ")
        return outputs

    monkeypatch.setattr(
        gate,
        "_build_post_review_outputs_compatible",
        mutate_after_build,
    )
    with pytest.raises(trace.BuildError):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert_no_post_outputs(tmp_path)


@pytest.mark.parametrize("phase", ["before-build", "after-build", "provenance"])
def test_same_process_snapshot_binding_rejects_swap_without_partial_post(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    phase: str,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    original = gate._build_post_review_outputs_compatible
    first = read_attestation(tmp_path)
    replacement = deepcopy(first)
    replacement["reviewed_at"] = "2026-08-02T23:00:01+09:00"

    def racing_build(*args, **kwargs):
        if phase == "before-build":
            write_attestation_value(tmp_path, replacement)
        outputs = original(*args, **kwargs)
        if phase == "after-build":
            write_attestation_value(tmp_path, replacement)
        elif phase == "provenance":
            review = json.loads(outputs[trace.INDEPENDENT_REVIEW_REL])
            review["attestation_provenance"]["sha256"] = "0" * 64
            outputs[trace.INDEPENDENT_REVIEW_REL] = trace.json_text(review)
        return outputs

    monkeypatch.setattr(gate, "_build_post_review_outputs_compatible", racing_build)
    with pytest.raises(gate.GateError, match="outputs|changed|leased"):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert_no_post_outputs(tmp_path)


def test_attestation_swap_at_writer_entry_is_rejected_before_any_post_mutation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    original_writer = trace.write_or_check_outputs
    replacement = read_attestation(tmp_path)
    replacement["reviewed_at"] = "2026-08-02T23:00:01+09:00"
    replacement_raw = trace.json_text(replacement).encode()

    def racing_writer(root: Path, outputs, *, write: bool, **kwargs) -> None:
        if write:
            write_attestation_value(tmp_path, replacement)
        original_writer(root, outputs, write=write, **kwargs)

    monkeypatch.setattr(trace, "write_or_check_outputs", racing_writer)
    with pytest.raises(gate.GateError, match="changed|leased"):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert not any(
        os.path.lexists(tmp_path / relative) for relative in trace.POST_REVIEW_OUTPUTS
    )
    assert attestation_path(tmp_path).read_bytes() == replacement_raw
    transaction = tmp_path / gate.POST_TRANSACTION_REL
    assert {path.name for path in transaction.iterdir()} == {
        "manifest.json",
        "00.stage",
        "01.stage",
    }


def test_transaction_path_swap_after_materialization_cannot_return_success(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    original_writer = trace.write_or_check_outputs
    transaction = tmp_path / gate.POST_TRANSACTION_REL
    retained = transaction.with_name(".retained-post-review-transaction")

    def swapping_writer(root: Path, outputs, *, write: bool, **_kwargs) -> None:
        materialized = dict(outputs)
        if write:
            transaction.rename(retained)
            transaction.mkdir(mode=0o700)
        original_writer(root, materialized, write=write)

    monkeypatch.setattr(trace, "write_or_check_outputs", swapping_writer)
    with pytest.raises(gate.GateError, match="remains linked|inventory"):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert retained.is_dir()
    assert {path.name for path in retained.iterdir()} == {
        "manifest.json",
        "00.stage",
        "01.stage",
    }
    assert not os.path.lexists(transaction)
    assert all(
        os.path.lexists(tmp_path / relative) for relative in trace.POST_REVIEW_OUTPUTS
    )


def test_preexisting_hidden_result_entry_is_rejected_before_post_mutation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    hidden = tmp_path / trace.RESULT_DIR_REL / ".foreign-helper"
    hidden.write_bytes(b"foreign\n")
    hidden.chmod(0o600)
    before = snapshot_tree(tmp_path)
    with pytest.raises(gate.GateError, match="unexpected hidden"):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert snapshot_tree(tmp_path) == before
    assert not any(
        os.path.lexists(tmp_path / relative) for relative in trace.POST_REVIEW_OUTPUTS
    )


@pytest.mark.parametrize("entry_kind", ["file", "directory"])
def test_preexisting_noncanonical_result_entry_is_rejected_before_post_mutation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    entry_kind: str,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    foreign = tmp_path / trace.RESULT_DIR_REL / (
        "foreign.txt" if entry_kind == "file" else "renamed-helper"
    )
    if entry_kind == "file":
        foreign.write_bytes(b"foreign\n")
        foreign.chmod(0o600)
    else:
        foreign.mkdir(mode=0o700)
    before = snapshot_tree(tmp_path)
    with pytest.raises(gate.GateError, match="unexpected review result inventory"):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert snapshot_tree(tmp_path) == before
    assert not any(
        os.path.lexists(tmp_path / relative) for relative in trace.POST_REVIEW_OUTPUTS
    )


def test_fixed_known_auxiliary_inventory_is_preserved_through_post_publication(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    auxiliary_relatives = known_auxiliary_file_relatives()
    auxiliary_before = {
        relative: (tmp_path / relative).read_bytes()
        for relative in auxiliary_relatives
    }
    _inventory, auxiliary_inventory = gate._result_inventory(
        tmp_path,
        allow_transaction=True,
        require_attestation=True,
        allow_post=True,
    )
    assert len(auxiliary_inventory) == 16

    gate.write_or_check_post_review(
        tmp_path,
        write=True,
        pre_review_kwargs=fixture,
        now=NOW,
    )

    assert {
        relative: (tmp_path / relative).read_bytes()
        for relative in auxiliary_relatives
    } == auxiliary_before
    inventory, observed_auxiliary = gate._result_inventory(
        tmp_path,
        allow_transaction=False,
        require_attestation=True,
        allow_post=True,
    )
    assert observed_auxiliary == auxiliary_inventory
    assert inventory == gate._expected_final_result_inventory(auxiliary_inventory)


@pytest.mark.parametrize("case", ["bytes", "path", "mode", "hardlink"])
def test_known_auxiliary_mutation_is_rejected_without_post_mutation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    case: str,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    log = tmp_path / next(iter(gate.KNOWN_LOG_SHA256_BY_REL))
    if case == "bytes":
        log.write_bytes(log.read_bytes() + b"changed\n")
    elif case == "path":
        log.rename(log.with_name("renamed-helper.log"))
    elif case == "mode":
        archive = (
            tmp_path
            / gate.RETIREMENT_DIR_REL
            / "canonical-outputs/walksafe-implementation-gap-analysis-20260802-r023.md"
        )
        archive.chmod(0o640)
    elif case == "hardlink":
        os.link(log, tmp_path / "known-log-alias")
    else:
        raise AssertionError(case)
    before = snapshot_tree(tmp_path)

    with pytest.raises(
        (gate.GateError, trace.BuildError),
        match="auxiliary|authority|inventory|SHA-256",
    ):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )

    assert snapshot_tree(tmp_path) == before
    assert not any(
        os.path.lexists(tmp_path / relative) for relative in trace.POST_REVIEW_OUTPUTS
    )


def test_post_publish_crash_with_attestation_conflict_fails_unchanged_until_manual_restore(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    original_attestation = attestation_path(tmp_path).read_bytes()
    replacement = read_attestation(tmp_path)
    replacement["reviewed_at"] = "2026-08-02T23:00:01+09:00"
    child = os.fork()
    if child == 0:
        original_rename = trace._rename_noreplace

        def abrupt_rename(source: Path, destination: Path) -> None:
            if destination == tmp_path / trace.INDEPENDENT_REVIEW_REL:
                write_attestation_value(tmp_path, replacement)
                original_rename(source, destination)
                os._exit(97)
            original_rename(source, destination)

        trace._rename_noreplace = abrupt_rename
        try:
            gate.write_or_check_post_review(
                tmp_path,
                write=True,
                pre_review_kwargs=fixture,
                now=NOW,
            )
        except BaseException:
            os._exit(98)
        os._exit(99)
    _pid, status = os.waitpid(child, 0)
    assert os.WIFEXITED(status) and os.WEXITSTATUS(status) == 97
    assert (tmp_path / trace.INDEPENDENT_REVIEW_REL).exists()
    assert not (tmp_path / trace.COMPLETION_RECEIPT_REL).exists()
    replacement_raw = trace.json_text(replacement).encode()
    conflict_before = attestation_path(tmp_path).stat()
    partial_before = (tmp_path / trace.INDEPENDENT_REVIEW_REL).read_bytes()
    with pytest.raises((gate.GateError, trace.BuildError)):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert attestation_path(tmp_path).read_bytes() == replacement_raw
    assert trace._file_identity(attestation_path(tmp_path).stat()) == trace._file_identity(
        conflict_before
    )
    assert (tmp_path / trace.INDEPENDENT_REVIEW_REL).read_bytes() == partial_before
    assert os.path.lexists(tmp_path / gate.POST_TRANSACTION_REL)

    attestation_path(tmp_path).write_bytes(original_attestation)
    attestation_path(tmp_path).chmod(0o600)
    outputs = gate.write_or_check_post_review(
        tmp_path,
        write=True,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    assert tuple(outputs) == trace.POST_REVIEW_OUTPUTS
    assert attestation_path(tmp_path).read_bytes() == original_attestation
    trace.write_or_check_outputs(tmp_path, outputs, write=False)
    assert_exact_result_inventory(tmp_path, include_post=True)


def test_materialized_writer_bypass_is_detected_without_canonical_restore(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    original_attestation = attestation_path(tmp_path).read_bytes()
    replacement = read_attestation(tmp_path)
    replacement["reviewed_at"] = "2026-08-02T23:00:01+09:00"
    replacement_raw = trace.json_text(replacement).encode()
    original_writer = trace.write_or_check_outputs

    def bypassing_writer(root: Path, outputs, *, write: bool, **_kwargs) -> None:
        materialized = dict(outputs)
        if write:
            write_attestation_value(tmp_path, replacement)
        original_writer(root, materialized, write=write)

    monkeypatch.setattr(trace, "write_or_check_outputs", bypassing_writer)
    with pytest.raises(gate.GateError, match="changed|leased"):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert attestation_path(tmp_path).read_bytes() == replacement_raw
    for relative in trace.POST_REVIEW_OUTPUTS:
        assert (tmp_path / relative).exists()
    assert not os.path.lexists(tmp_path / gate.POST_TRANSACTION_REL)

    attestation_path(tmp_path).write_bytes(original_attestation)
    attestation_path(tmp_path).chmod(0o600)
    gate.write_or_check_post_review(
        tmp_path,
        write=False,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    assert_exact_result_inventory(tmp_path, include_post=True)


@pytest.mark.parametrize(
    "phase",
    [
        "after-mkdir",
        "mid-manifest",
        "after-manifest",
        "mid-stage-00",
        "after-stage-00",
        "after-stage-01",
        "after-first-output",
        "before-cleanup",
        "after-manifest-unlink",
        "after-rmdir",
    ],
)
def test_post_transaction_crash_retries_to_exact_outputs_and_zero_residue(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    phase: str,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    original_attestation = attestation_path(tmp_path).read_bytes()
    child = os.fork()
    if child == 0:
        if phase == "after-mkdir":
            original_open = gate._open_or_create_post_transaction

            def abrupt_open(directory_fd: int):
                opened = original_open(directory_fd)
                os._exit(97)
                return opened

            gate._open_or_create_post_transaction = abrupt_open
        elif phase in {"mid-manifest", "mid-stage-00"}:
            original_write = gate._write_descriptor
            target = "manifest.json" if phase == "mid-manifest" else "00.stage"

            def abrupt_write(descriptor: int, raw: bytes, *, label: str) -> None:
                if label.endswith(target):
                    os.write(descriptor, raw[: max(1, len(raw) // 2)])
                    os.fsync(descriptor)
                    os._exit(97)
                original_write(descriptor, raw, label=label)

            gate._write_descriptor = abrupt_write
        elif phase in {"after-manifest", "after-stage-00", "after-stage-01"}:
            original_publish = gate._publish_unnamed_add_only
            target = {
                "after-manifest": "manifest.json",
                "after-stage-00": "00.stage",
                "after-stage-01": "01.stage",
            }[phase]

            def abrupt_publish(
                directory_fd: int,
                name: str,
                raw: bytes,
                *,
                label: str,
            ) -> None:
                original_publish(directory_fd, name, raw, label=label)
                if label.endswith(target):
                    os._exit(97)

            gate._publish_unnamed_add_only = abrupt_publish
        elif phase == "after-first-output":
            original_rename = trace._rename_noreplace

            def abrupt_rename(source: Path, destination: Path) -> None:
                original_rename(source, destination)
                if destination == tmp_path / trace.INDEPENDENT_REVIEW_REL:
                    os._exit(97)

            trace._rename_noreplace = abrupt_rename
        elif phase == "before-cleanup":
            def abrupt_cleanup(*_args, **_kwargs) -> None:
                os._exit(97)

            trace._cleanup_forward_transaction = abrupt_cleanup
        elif phase == "after-manifest-unlink":
            original_unlink = Path.unlink

            def abrupt_unlink(path: Path, *args, **kwargs) -> None:
                original_unlink(path, *args, **kwargs)
                if path == tmp_path / gate.POST_TRANSACTION_REL / "manifest.json":
                    os._exit(97)

            Path.unlink = abrupt_unlink
        elif phase == "after-rmdir":
            original_rmdir = Path.rmdir

            def abrupt_rmdir(path: Path) -> None:
                original_rmdir(path)
                if path == tmp_path / gate.POST_TRANSACTION_REL:
                    os._exit(97)

            Path.rmdir = abrupt_rmdir
        else:
            raise AssertionError(phase)
        try:
            gate.write_or_check_post_review(
                tmp_path,
                write=True,
                pre_review_kwargs=fixture,
                now=NOW,
            )
        except BaseException:
            os._exit(98)
        os._exit(99)
    _pid, status = os.waitpid(child, 0)
    assert os.WIFEXITED(status) and os.WEXITSTATUS(status) == 97

    result_identity = gate._directory_identity(
        (tmp_path / trace.RESULT_DIR_REL).stat()
    )
    result_fsyncs: list[int] = []
    original_fsync = os.fsync

    def observe_result_fsync(descriptor: int) -> None:
        if gate._directory_identity(os.fstat(descriptor)) == result_identity:
            result_fsyncs.append(descriptor)
        original_fsync(descriptor)

    monkeypatch.setattr(os, "fsync", observe_result_fsync)
    outputs = gate.write_or_check_post_review(
        tmp_path,
        write=True,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    assert tuple(outputs) == trace.POST_REVIEW_OUTPUTS
    if phase == "after-rmdir":
        assert result_fsyncs
    assert attestation_path(tmp_path).read_bytes() == original_attestation
    assert_exact_result_inventory(tmp_path, include_post=True)


def snapshot_tree(root: Path) -> dict[str, tuple[tuple[int, ...], bytes | None]]:
    snapshot: dict[str, tuple[tuple[int, ...], bytes | None]] = {}
    for path in sorted(root.rglob("*")):
        info = path.lstat()
        raw = path.read_bytes() if stat.S_ISREG(info.st_mode) else None
        snapshot[path.relative_to(root).as_posix()] = (
            trace._file_identity(info),
            raw,
        )
    return snapshot


@pytest.mark.parametrize("relative", trace.POST_REVIEW_OUTPUTS)
def test_post_destination_conflict_fails_before_transaction_mutation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    relative: Path,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    conflict = tmp_path / relative
    conflict.write_bytes(b"conflicting canonical output\n")
    conflict.chmod(0o600)
    before = snapshot_tree(tmp_path)
    with pytest.raises(
        gate.GateError,
        match="partial post-review|existing post-review output differs",
    ):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert snapshot_tree(tmp_path) == before
    assert not os.path.lexists(tmp_path / gate.POST_TRANSACTION_REL)


def test_orphan_post_stage_without_durable_manifest_is_not_laundered(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    outputs, _attestation = gate.build_strict_post_review_outputs(
        tmp_path,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    transaction = tmp_path / gate.POST_TRANSACTION_REL
    transaction.mkdir(mode=0o700)
    stage = transaction / "00.stage"
    stage.write_bytes(outputs[trace.INDEPENDENT_REVIEW_REL].encode())
    stage.chmod(0o600)
    before = snapshot_tree(tmp_path)
    with pytest.raises(gate.GateError, match="lacks durable manifest"):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert snapshot_tree(tmp_path) == before
    assert not any(
        os.path.lexists(tmp_path / relative) for relative in trace.POST_REVIEW_OUTPUTS
    )


def test_second_exact_post_write_is_full_tree_read_only(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    first = gate.write_or_check_post_review(
        tmp_path,
        write=True,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    before = snapshot_tree(tmp_path)
    result_identity = gate._directory_identity(
        (tmp_path / trace.RESULT_DIR_REL).stat()
    )
    result_fsyncs: list[int] = []
    original_fsync = os.fsync

    def observe_result_fsync(descriptor: int) -> None:
        if gate._directory_identity(os.fstat(descriptor)) == result_identity:
            result_fsyncs.append(descriptor)
        original_fsync(descriptor)

    monkeypatch.setattr(os, "fsync", observe_result_fsync)
    second = gate.write_or_check_post_review(
        tmp_path,
        write=True,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    assert second == first
    assert result_fsyncs
    assert snapshot_tree(tmp_path) == before
    assert_exact_result_inventory(tmp_path, include_post=True)


def test_exact_post_retry_directory_fsync_failure_is_fail_closed_and_retryable(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    expected = gate.write_or_check_post_review(
        tmp_path,
        write=True,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    before = snapshot_tree(tmp_path)
    result_identity = gate._directory_identity(
        (tmp_path / trace.RESULT_DIR_REL).stat()
    )
    original_fsync = os.fsync

    def fail_result_fsync(descriptor: int) -> None:
        if gate._directory_identity(os.fstat(descriptor)) == result_identity:
            raise OSError("injected exact retry directory fsync failure")
        original_fsync(descriptor)

    monkeypatch.setattr(os, "fsync", fail_result_fsync)
    with pytest.raises(OSError, match="exact retry directory fsync failure"):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert snapshot_tree(tmp_path) == before

    monkeypatch.setattr(os, "fsync", original_fsync)
    observed = gate.write_or_check_post_review(
        tmp_path,
        write=True,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    assert observed == expected
    assert snapshot_tree(tmp_path) == before


@pytest.mark.parametrize("case", ["attestation", "producer", "inventory"])
def test_exact_post_retry_revalidates_every_boundary_after_directory_fsync(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    case: str,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    gate.write_or_check_post_review(
        tmp_path,
        write=True,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    result_identity = gate._directory_identity(
        (tmp_path / trace.RESULT_DIR_REL).stat()
    )
    original_fsync = os.fsync
    mutated = False

    def mutate_after_result_fsync(descriptor: int) -> None:
        nonlocal mutated
        original_fsync(descriptor)
        if (
            not mutated
            and gate._directory_identity(os.fstat(descriptor)) == result_identity
        ):
            mutated = True
            if case == "attestation":
                path = attestation_path(tmp_path)
                raw = path.read_bytes()
                path.replace(tmp_path / "moved-attestation-after-fsync.json")
                path.write_bytes(raw)
                path.chmod(0o600)
            elif case == "producer":
                producer = tmp_path / trace.IMPLEMENTATION_REL
                producer.write_bytes(producer.read_bytes() + b" ")
            elif case == "inventory":
                foreign = tmp_path / trace.RESULT_DIR_REL / "foreign-after-fsync"
                foreign.write_bytes(b"foreign\n")
                foreign.chmod(0o600)
            else:
                raise AssertionError(case)

    monkeypatch.setattr(os, "fsync", mutate_after_result_fsync)
    with pytest.raises(
        (gate.GateError, trace.BuildError),
        match="leased|changed|output differs|inventory",
    ):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert mutated
    assert not os.path.lexists(tmp_path / gate.POST_TRANSACTION_REL)


def test_post_publication_revalidates_current_producer_context(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    original_writer = trace.write_or_check_outputs
    producer = tmp_path / trace.IMPLEMENTATION_REL

    def mutate_after_write(root: Path, outputs, *, write: bool, **kwargs) -> None:
        original_writer(root, outputs, write=write, **kwargs)
        if write:
            producer.write_bytes(producer.read_bytes() + b" ")

    monkeypatch.setattr(trace, "write_or_check_outputs", mutate_after_write)
    with pytest.raises(trace.BuildError):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert not os.path.lexists(tmp_path / gate.POST_TRANSACTION_REL)
    assert all(
        os.path.lexists(tmp_path / relative) for relative in trace.POST_REVIEW_OUTPUTS
    )


@pytest.mark.parametrize(
    "case",
    ["symlink", "mode", "foreign", "manifest-drift", "stage-hardlink"],
)
def test_malformed_post_transaction_fails_without_mutating_existing_state(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    case: str,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    transaction = tmp_path / gate.POST_TRANSACTION_REL
    if case == "symlink":
        target = tmp_path / "transaction-target"
        target.mkdir(mode=0o700)
        transaction.symlink_to(target, target_is_directory=True)
    else:
        transaction.mkdir(mode=0o700)
        if case == "mode":
            transaction.chmod(0o755)
        elif case == "foreign":
            foreign = transaction / "foreign"
            foreign.write_bytes(b"foreign")
            foreign.chmod(0o600)
        elif case == "manifest-drift":
            manifest = transaction / "manifest.json"
            manifest.write_bytes(b"{}\n")
            manifest.chmod(0o600)
        elif case == "stage-hardlink":
            stage = transaction / "00.stage"
            stage.write_bytes(b"bad")
            stage.chmod(0o600)
            os.link(stage, tmp_path / "stage-alias")
        else:
            raise AssertionError(case)
    before = snapshot_tree(tmp_path)
    with pytest.raises((gate.GateError, trace.BuildError, OSError)):
        gate.write_or_check_post_review(
            tmp_path,
            write=True,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert snapshot_tree(tmp_path) == before
    assert not any(
        os.path.lexists(tmp_path / relative) for relative in trace.POST_REVIEW_OUTPUTS
    )


def test_check_post_review_is_read_only_and_rejects_transaction_residue(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    gate.write_or_check_post_review(
        tmp_path,
        write=True,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    before = snapshot_tree(tmp_path)
    gate.write_or_check_post_review(
        tmp_path,
        write=False,
        pre_review_kwargs=fixture,
        now=NOW,
    )
    assert snapshot_tree(tmp_path) == before

    transaction = tmp_path / gate.POST_TRANSACTION_REL
    transaction.mkdir(mode=0o700)
    with pytest.raises(gate.GateError, match="residue"):
        gate.write_or_check_post_review(
            tmp_path,
            write=False,
            pre_review_kwargs=fixture,
            now=NOW,
        )
    assert transaction.is_dir() and not any(transaction.iterdir())


def test_attestation_lease_is_single_owner_and_close_is_idempotent(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    with gate._result_lock(tmp_path) as (directory_fd, directory_identity):
        context = gate.prepare_review_context(
            tmp_path,
            pre_review_kwargs=fixture,
        )
        lease = gate._open_attestation_lease(
            tmp_path,
            directory_fd,
            directory_identity,
            context,
            now=NOW,
        )
        with pytest.raises(TypeError, match="cannot be copied"):
            copy.copy(lease)
        with pytest.raises(TypeError, match="cannot be copied"):
            copy.deepcopy(lease)
        lease.check()
        lease.close()
        lease.close()
        with pytest.raises(gate.GateError, match="closed"):
            lease.check()


def test_load_attestation_rejects_caller_supplied_cached_context(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    context = gate.prepare_review_context(tmp_path, pre_review_kwargs=fixture)
    with pytest.raises(TypeError, match="unexpected keyword argument 'context'"):
        gate.load_strict_attestation(
            tmp_path,
            context=context,
            pre_review_kwargs=fixture,
            now=NOW,
        )


def test_transaction_open_fstat_failure_closes_new_descriptor_once(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepare_root(tmp_path, monkeypatch, with_attestation=True)
    captured: list[int] = []
    closed: list[int] = []
    with gate._result_lock(tmp_path) as (directory_fd, _directory_identity):
        original_open = os.open
        original_fstat = os.fstat
        original_close = os.close

        def capture_open(path, flags, mode=0o777, *, dir_fd=None):
            descriptor = original_open(path, flags, mode, dir_fd=dir_fd)
            if path == gate.POST_TRANSACTION_REL.name and dir_fd == directory_fd:
                captured.append(descriptor)
            return descriptor

        def fail_target_fstat(descriptor: int):
            if captured and descriptor == captured[-1]:
                raise OSError("injected transaction fstat failure")
            return original_fstat(descriptor)

        def observe_close(descriptor: int) -> None:
            closed.append(descriptor)
            original_close(descriptor)

        monkeypatch.setattr(os, "open", capture_open)
        monkeypatch.setattr(os, "fstat", fail_target_fstat)
        monkeypatch.setattr(os, "close", observe_close)
        with pytest.raises(OSError, match="transaction fstat failure"):
            gate._open_or_create_post_transaction(directory_fd)
        assert len(captured) == 1
        assert closed.count(captured[0]) == 1


def test_attestation_lease_close_preserves_primary_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture, _pre, _consumers = prepare_root(
        tmp_path,
        monkeypatch,
        with_attestation=True,
    )
    with gate._result_lock(tmp_path) as (directory_fd, directory_identity):
        context = gate.prepare_review_context(
            tmp_path,
            pre_review_kwargs=fixture,
        )
        lease = gate._open_attestation_lease(
            tmp_path,
            directory_fd,
            directory_identity,
            context,
            now=NOW,
        )
        lease_fd = lease.descriptor
        assert lease_fd is not None
        original_close = os.close

        def fail_after_close(descriptor: int) -> None:
            original_close(descriptor)
            if descriptor == lease_fd:
                raise OSError("injected lease close failure")

        monkeypatch.setattr(os, "close", fail_after_close)
        with pytest.raises(gate.GateError, match="primary failure"):
            with lease:
                raise gate.GateError("primary failure")
        assert lease.descriptor is None
