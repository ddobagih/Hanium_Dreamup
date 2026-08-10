from __future__ import annotations

from copy import deepcopy
import json
import os
from pathlib import Path

import pytest

from scripts import (
    build_walksafe_fp046_artifact_trace_successor_20260810 as builder,
)
from scripts import (
    build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810 as trace,
)
from scripts import build_walksafe_fp046_gap_backlog_r025_20260810 as gap_builder


REPO_ROOT = Path(__file__).resolve().parents[1]


def _write(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)


def _source_groups(root: Path) -> tuple[trace.SourceGroup, ...]:
    rows = (
        (
            "ANDROID_USER_APP_PRIVACY",
            Path("apps/android/app/fp046-artifact-fixture.kt"),
            b"android-fp046\n",
        ),
        (
            "ANDROID_GATEWAY_PRIVACY",
            Path("apps/android-gateway/fp046-artifact-fixture.ts"),
            b"gateway-fp046\n",
        ),
        (
            "BACKEND_PRIVACY_LIFECYCLE",
            Path("backend/fp046-artifact-fixture.py"),
            b"backend-fp046\n",
        ),
        (
            "RETENTION_BACKUP_AND_TRACE_TOOLING",
            builder.BUILDER_REL,
            (REPO_ROOT / builder.BUILDER_REL).read_bytes(),
        ),
    )
    for _group_id, relative, raw in rows:
        _write(root, relative, raw)
    return tuple(
        trace.SourceGroup(group_id, (relative.as_posix(),))
        for group_id, relative, _raw in rows
    )


def _authority() -> dict[str, object]:
    return {
        "goal_binding": {
            "role": "FP046_GOAL",
            "path": trace.GOAL_REL.as_posix(),
            "byte_length": 1,
            "sha256": "a" * 64,
        },
        "start_gate_binding": {
            "role": "FP046_EXACT9_START_GATE",
            "path": trace.START_GATE_REL.as_posix(),
            "byte_length": 1,
            "sha256": "b" * 64,
            "repository_state_path": trace.START_GATE_REPOSITORY_STATE_REL.as_posix(),
            "repository_state_sha256": "c" * 64,
            "event_sequence": trace.EXPECTED_START_EVENT_SEQUENCE,
            "event_id": trace.EXPECTED_START_EVENT_ID,
        },
        "gate_ended_at": "2026-08-09T20:14:48+09:00",
    }


def _lane_raw_outputs() -> dict[str, bytes]:
    result: dict[str, bytes] = {}
    for index, lane in enumerate(trace.LANES, start=1):
        started_at = f"2026-08-10T01:0{index}:00+09:00"
        ended_at = f"2026-08-10T01:0{index}:30+09:00"
        runner_summary = {
            "ANDROID_CONSENT_DELETION": (
                "JUnit tests=981 failures=0 errors=0 skipped=0"
            ),
            "GATEWAY_PRIVACY_LEDGER": (
                "Node tests=88 pass=88 fail=0; typecheck=PASS; build=PASS"
            ),
            "BACKEND_PRIVACY_POSTGRES": "57 passed in 1.00s",
            "RETENTION_BACKUP_DELETION": "92 passed in 1.00s",
        }[lane.lane_id]
        result[lane.lane_id] = (
            f"WALKSAFE_FP046_STARTED_AT {started_at}\n"
            f"WALKSAFE_FP046_COMMAND {lane.expected_command}\n"
            f"{runner_summary}\n"
            "WALKSAFE_FP046_SUMMARY "
            f"passed={lane.expected_passed} failed=0 errors=0 skipped=0\n"
            "WALKSAFE_FP046_EXIT_CODE 0\n"
            f"WALKSAFE_FP046_ENDED_AT {ended_at}\n"
        ).encode("utf-8")
    return result


def _lane_observations() -> dict[str, dict[str, object]]:
    raw_outputs = _lane_raw_outputs()
    result: dict[str, dict[str, object]] = {}
    for index, lane in enumerate(trace.LANES, start=1):
        raw = raw_outputs[lane.lane_id]
        result[lane.lane_id] = {
            "schema_version": "walksafe.fp046-internal-lane-observation.v1",
            "lane_id": lane.lane_id,
            "status": "PASS",
            "command": lane.expected_command,
            "exit_code": 0,
            "started_at": f"2026-08-10T01:0{index}:00+09:00",
            "ended_at": f"2026-08-10T01:0{index}:30+09:00",
            "raw_output_sha256": trace.bytes_sha256(raw),
            "raw_output_byte_length": len(raw),
            "metrics": {
                "result_format": "INTERNAL_TEST_SUMMARY_V1",
                "passed": lane.expected_passed,
                "failed": 0,
                "errors": 0,
                "skipped": 0,
            },
            "evidence_boundary": trace.lane_evidence_boundary(),
        }
    return result


def _trace_outputs(root: Path) -> dict[Path, str]:
    return trace.build_pre_review_outputs(
        root=root,
        lane_observations=_lane_observations(),
        lane_raw_outputs=_lane_raw_outputs(),
        source_groups=_source_groups(root),
        authority=_authority(),
    )


def _r025_outputs(
    trace_outputs: dict[Path, str],
    root: Path,
    *,
    source_groups: tuple[trace.SourceGroup, ...] | None = None,
    authority: dict[str, object] | None = None,
) -> dict[Path, str]:
    predecessor_gap_raw = (REPO_ROOT / gap_builder.R024_GAP_REL).read_bytes()
    predecessor_backlog_raw = (
        REPO_ROOT / gap_builder.R024_BACKLOG_REL
    ).read_bytes()
    implementation_raw = trace_outputs[trace.IMPLEMENTATION_REL].encode("utf-8")
    verification_raw = trace_outputs[trace.VERIFICATION_REL].encode("utf-8")
    receipt_raw_by_id = {
        lane.lane_id: trace_outputs[lane.receipt_rel].encode("utf-8")
        for lane in trace.LANES
    }
    log_raw_by_id = {
        lane.lane_id: trace_outputs[lane.log_rel].encode("utf-8")
        for lane in trace.LANES
    }
    return gap_builder.build_documents(
        trace.strict_json_bytes(predecessor_gap_raw, "R024 gap"),
        trace.strict_json_bytes(predecessor_backlog_raw, "R024 backlog"),
        trace.strict_json_bytes(implementation_raw, "FP046 implementation"),
        trace.strict_json_bytes(verification_raw, "FP046 verification"),
        predecessor_gap_raw=predecessor_gap_raw,
        predecessor_backlog_raw=predecessor_backlog_raw,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        lane_receipt_raw_by_id=receipt_raw_by_id,
        lane_log_raw_by_id=log_raw_by_id,
        root=root,
        source_groups=source_groups or _source_groups(root),
        authority=authority or _authority(),
    )


def _predecessors() -> tuple[dict[Path, bytes], dict[Path, dict[str, object]]]:
    raw = {
        relative: (REPO_ROOT / relative).read_bytes()
        for relative in builder.OUTPUT_PATHS
    }
    documents = {
        relative: trace.strict_json_bytes(content, relative.as_posix())
        for relative, content in raw.items()
    }
    predecessor_flags = {
        relative: trace.bytes_sha256(raw[relative])
        == builder.EXPECTED_PREDECESSOR_SHA256_BY_PATH[relative]
        for relative in builder.OUTPUT_PATHS
    }
    if all(predecessor_flags.values()):
        builder.validate_predecessors(documents, raw)
        return raw, documents
    trace.require(
        not any(predecessor_flags.values()),
        "live six-artifact fixture mixes FP008 predecessor and FP046 successor",
    )
    recovered = builder.validate_successor_structure(documents, raw)
    recovered_raw = {
        relative: trace.json_text(recovered[relative]).encode("utf-8")
        for relative in builder.OUTPUT_PATHS
    }
    builder.validate_predecessors(recovered, recovered_raw)
    return recovered_raw, recovered


def _artifact_outputs(
    trace_outputs: dict[Path, str], r025_outputs: dict[Path, str]
) -> dict[Path, str]:
    predecessor_raw, predecessors = _predecessors()
    implementation_raw = trace_outputs[trace.IMPLEMENTATION_REL].encode("utf-8")
    verification_raw = trace_outputs[trace.VERIFICATION_REL].encode("utf-8")
    gap_raw = r025_outputs[gap_builder.R025_GAP_JSON_REL].encode("utf-8")
    return builder.build_documents(
        predecessors,
        predecessor_raw,
        trace.strict_json_bytes(implementation_raw, "FP046 implementation"),
        trace.strict_json_bytes(verification_raw, "FP046 verification"),
        trace.strict_json_bytes(gap_raw, "R025 gap"),
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
    )


def _fixture(
    root: Path,
) -> tuple[dict[Path, str], dict[Path, str], dict[Path, str]]:
    trace_outputs = _trace_outputs(root)
    r025_outputs = _r025_outputs(trace_outputs, root)
    artifact_outputs = _artifact_outputs(trace_outputs, r025_outputs)
    return trace_outputs, r025_outputs, artifact_outputs


def _materialize_live_inputs(root: Path) -> None:
    for relative in (
        *builder.AUTHORITY_PATHS,
        *builder.CANONICAL_SOURCE_PATHS,
        *trace.BACKEND_TRANSITIVE_INPUT_SHA256_BY_PATH,
        builder.BUILDER_REL,
    ):
        _write(root, relative, (REPO_ROOT / relative).read_bytes())
    authority = trace.validate_authority(root)
    trace_outputs = trace.build_pre_review_outputs(
        root=root,
        lane_observations=_lane_observations(),
        lane_raw_outputs=_lane_raw_outputs(),
        source_groups=trace.IMPLEMENTATION_SOURCE_GROUPS,
        authority=authority,
    )
    for relative, content in trace_outputs.items():
        _write(root, relative, content.encode("utf-8"))
    r025_outputs = _r025_outputs(
        trace_outputs,
        root,
        source_groups=trace.IMPLEMENTATION_SOURCE_GROUPS,
        authority=authority,
    )
    _write(
        root,
        gap_builder.R025_GAP_JSON_REL,
        r025_outputs[gap_builder.R025_GAP_JSON_REL].encode("utf-8"),
    )


def _materialize_writer_root(
    root: Path,
) -> dict[Path, bytes]:
    predecessor_raw, _ = _predecessors()
    for relative, raw in predecessor_raw.items():
        _write(root, relative, raw)
    _materialize_live_inputs(root)
    return predecessor_raw


def test_six_successors_are_deterministic_exact_and_zero_credit(
    tmp_path: Path,
) -> None:
    trace_outputs, r025_outputs, first = _fixture(tmp_path)
    second = _artifact_outputs(trace_outputs, r025_outputs)
    assert first == second
    assert tuple(first) == builder.OUTPUT_PATHS

    predecessor_raw, predecessors = _predecessors()
    successor_raw = {
        relative: content.encode("utf-8") for relative, content in first.items()
    }
    successors = {
        relative: trace.strict_json_bytes(raw, relative.as_posix())
        for relative, raw in successor_raw.items()
    }
    assert builder.recover_predecessors(successors) == predecessors
    assert set(builder.validate_successor_structure(successors, successor_raw)) == set(
        builder.OUTPUT_PATHS
    )

    for relative in builder.OUTPUT_PATHS:
        marker = successors[relative]["fp046_artifact_trace_successor"]
        assert marker["successor_id"] == builder.SUCCESSOR_ID
        assert marker["policy_id"] == "FP-046"
        assert marker["gap_id"] == "GAP-055"
        assert [row["name"] for row in marker["input_bindings"]] == [
            "fp046_implementation_result",
            "fp046_verification_result",
            "fp046_gap055_r025_successor",
        ]
        boundary = marker["trace_boundary"]
        assert boundary["formal_test_status"] == "NOT_RUN"
        assert boundary["actual_device_status"] == "NOT_RUN"
        assert boundary["external_verification_status"] == "NOT_RUN"
        assert boundary["production_deployment_status"] == "NOT_RUN"
        assert boundary["release_status"] == "NOT_ELIGIBLE"
        assert boundary["release_credit_count"] == 0

    rtm_before = {
        row["requirement_id"]: row for row in predecessors[builder.RTM_REL]["requirements"]
    }
    rtm_after = {
        row["requirement_id"]: row for row in successors[builder.RTM_REL]["requirements"]
    }
    assert {
        key: value for key, value in rtm_before.items() if key != "RQ-FP-046-001"
    } == {key: value for key, value in rtm_after.items() if key != "RQ-FP-046-001"}
    target = rtm_after["RQ-FP-046-001"]
    assert [row["planned_test_id"] for row in target["acceptance_conditions"]] == list(
        trace.FORMAL_TEST_IDS
    )
    assert {row["test_execution_status"] for row in target["acceptance_conditions"]} == {
        "NOT_RUN"
    }
    assert not any(row["pass_claimed"] for row in target["acceptance_conditions"])

    design_before = {
        row["design_id"]: row for row in predecessors[builder.DESIGN_REL]["records"]
    }
    design_after = {
        row["design_id"]: row for row in successors[builder.DESIGN_REL]["records"]
    }
    assert {key: value for key, value in design_before.items() if key != "DES-06"} == {
        key: value for key, value in design_after.items() if key != "DES-06"
    }
    assert "fp046_internal_conformance" in design_after["DES-06"]

    modules_before = {
        row["module_id"]: row
        for row in predecessors[builder.MODULE_REGISTER_REL]["modules"]
    }
    modules_after = {
        row["module_id"]: row
        for row in successors[builder.MODULE_REGISTER_REL]["modules"]
    }
    changed_modules = {
        "MOD-ANDROID-USER",
        "MOD-ANDROID-GATEWAY",
        "MOD-BACKEND",
    }
    assert {
        key: value for key, value in modules_before.items() if key not in changed_modules
    } == {
        key: value for key, value in modules_after.items() if key not in changed_modules
    }
    assert all(
        "fp046_internal_trace" in modules_after[module_id]
        for module_id in changed_modules
    )

    artifact_before = {
        row["display_code"]: row
        for row in predecessors[builder.DOC01_REL]["artifacts"]
    }
    artifact_after = {
        row["display_code"]: row
        for row in successors[builder.DOC01_REL]["artifacts"]
    }
    target_codes = set(builder.TARGET_ARTIFACT_PATHS)
    assert {key: value for key, value in artifact_before.items() if key not in target_codes} == {
        key: value for key, value in artifact_after.items() if key not in target_codes
    }
    assert all(
        "fp046_internal_rebinding" in artifact_after[code] for code in target_codes
    )

    tampered_raw = dict(predecessor_raw)
    tampered_raw[builder.DOC05_REL] += b" "
    with pytest.raises(builder.BuildError, match="pinned FP008 predecessor bytes"):
        builder.build_documents(
            predecessors,
            tampered_raw,
            json.loads(trace_outputs[trace.IMPLEMENTATION_REL]),
            json.loads(trace_outputs[trace.VERIFICATION_REL]),
            json.loads(r025_outputs[gap_builder.R025_GAP_JSON_REL]),
            implementation_raw=trace_outputs[trace.IMPLEMENTATION_REL].encode(),
            verification_raw=trace_outputs[trace.VERIFICATION_REL].encode(),
            gap_raw=r025_outputs[gap_builder.R025_GAP_JSON_REL].encode(),
        )


def test_writer_is_transactional_tmp_only_and_idempotent(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    rollback_root = tmp_path / "rollback"
    rollback_root.mkdir()
    predecessor_raw = _materialize_writer_root(rollback_root)
    expected = builder.build_outputs(rollback_root)
    original_exchange = builder.fp008_builder._rename_exchange_at
    calls = 0

    def fail_once(*args: object) -> None:
        nonlocal calls
        calls += 1
        if calls == 3:
            raise OSError("injected exchange failure")
        original_exchange(*args)

    monkeypatch.setattr(builder.fp008_builder, "_rename_exchange_at", fail_once)
    with pytest.raises(OSError, match="injected exchange failure"):
        builder.write_successor(rollback_root)
    assert {
        relative: (rollback_root / relative).read_bytes()
        for relative in builder.OUTPUT_PATHS
    } == predecessor_raw
    assert not (rollback_root / builder.TRANSACTION_JOURNAL_REL).exists()
    for relative in builder.OUTPUT_PATHS:
        assert not (rollback_root / builder._transaction_member(relative, "stage")).exists()
        assert not (rollback_root / builder._transaction_member(relative, "backup")).exists()

    monkeypatch.setattr(
        builder.fp008_builder, "_rename_exchange_at", original_exchange
    )
    assert builder.write_successor(rollback_root) == "PUBLISHED_SUCCESSOR"
    assert builder.check_successor(rollback_root) == expected
    assert builder.write_successor(rollback_root) == "ALREADY_CURRENT"


@pytest.mark.parametrize("race_timing", ("before_exchange", "after_exchange"))
def test_rollback_exchange_race_preserves_foreign_target_and_rolls_back_cohort(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, race_timing: str
) -> None:
    race_root = tmp_path / "rollback-race"
    race_root.mkdir()
    predecessor_raw = _materialize_writer_root(race_root)
    raced_relative = builder.OUTPUT_PATHS[0]
    foreign_raw = b'{"foreign_update":"rollback_exchange_race"}\n'
    original_exchange = builder.fp008_builder._rename_exchange_at
    calls = 0

    def replace_target_with_foreign() -> None:
        foreign_stage = (race_root / raced_relative).with_name(
            f".{raced_relative.name}.foreign-race"
        )
        foreign_stage.write_bytes(foreign_raw)
        os.replace(foreign_stage, race_root / raced_relative)

    def fail_then_race(*args: object) -> None:
        nonlocal calls
        calls += 1
        if calls == 3:
            raise OSError("injected publication exchange failure")
        if calls == 4 and race_timing == "before_exchange":
            replace_target_with_foreign()
        original_exchange(*args)
        if calls == 4 and race_timing == "after_exchange":
            replace_target_with_foreign()

    monkeypatch.setattr(
        builder.fp008_builder, "_rename_exchange_at", fail_then_race
    )
    with pytest.raises(builder.BuildError, match="rollback CAS changed") as error:
        builder.write_successor(race_root)
    assert isinstance(error.value.__cause__, OSError)
    assert "injected publication exchange failure" in str(error.value.__cause__)

    restored = {
        relative: (race_root / relative).read_bytes()
        for relative in builder.OUTPUT_PATHS
    }
    assert restored[raced_relative] == foreign_raw
    assert {
        relative: restored[relative]
        for relative in builder.OUTPUT_PATHS
        if relative != raced_relative
    } == {
        relative: predecessor_raw[relative]
        for relative in builder.OUTPUT_PATHS
        if relative != raced_relative
    }
    assert (race_root / builder.TRANSACTION_JOURNAL_REL).is_file()
    assert (
        race_root / builder._transaction_member(raced_relative, "backup")
    ).read_bytes() == predecessor_raw[raced_relative]
    assert calls == (6 if race_timing == "before_exchange" else 5)


def test_writer_rejects_mixed_predecessor_successor_cohort(tmp_path: Path) -> None:
    mixed_root = tmp_path / "mixed"
    mixed_root.mkdir()
    predecessor_raw = _materialize_writer_root(mixed_root)
    expected = builder.build_outputs(mixed_root)
    mixed_raw = dict(predecessor_raw)
    mixed_raw[builder.OUTPUT_PATHS[0]] = expected[builder.OUTPUT_PATHS[0]].encode(
        "utf-8"
    )
    _write(mixed_root, builder.OUTPUT_PATHS[0], mixed_raw[builder.OUTPUT_PATHS[0]])

    with pytest.raises(builder.BuildError, match="mixes FP008 predecessor"):
        builder.write_successor(mixed_root)
    assert {
        relative: (mixed_root / relative).read_bytes()
        for relative in builder.OUTPUT_PATHS
    } == mixed_raw
    assert not (mixed_root / builder.TRANSACTION_JOURNAL_REL).exists()


def test_writer_atomically_upgrades_prior_successor_and_rolls_back_to_it(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    old_fixture_root = tmp_path / "old-fixture"
    old_fixture_root.mkdir()
    _old_trace, _old_r025, old_outputs = _fixture(old_fixture_root)
    old_raw = {
        relative: old_outputs[relative].encode("utf-8")
        for relative in builder.OUTPUT_PATHS
    }

    upgrade_root = tmp_path / "upgrade"
    upgrade_root.mkdir()
    for relative, raw in old_raw.items():
        _write(upgrade_root, relative, raw)
    _materialize_live_inputs(upgrade_root)
    expected = builder.build_outputs(upgrade_root)
    assert any(
        old_raw[relative] != expected[relative].encode("utf-8")
        for relative in builder.OUTPUT_PATHS
    )

    original_exchange = builder.fp008_builder._rename_exchange_at
    calls = 0

    def fail_once(*args: object) -> None:
        nonlocal calls
        calls += 1
        if calls == 3:
            raise OSError("injected upgrade exchange failure")
        original_exchange(*args)

    monkeypatch.setattr(builder.fp008_builder, "_rename_exchange_at", fail_once)
    with pytest.raises(OSError, match="injected upgrade exchange failure"):
        builder.write_successor(upgrade_root)
    assert {
        relative: (upgrade_root / relative).read_bytes()
        for relative in builder.OUTPUT_PATHS
    } == old_raw
    assert not (upgrade_root / builder.TRANSACTION_JOURNAL_REL).exists()
    for relative in builder.OUTPUT_PATHS:
        assert not (upgrade_root / builder._transaction_member(relative, "stage")).exists()
        assert not (upgrade_root / builder._transaction_member(relative, "backup")).exists()

    monkeypatch.setattr(
        builder.fp008_builder, "_rename_exchange_at", original_exchange
    )
    assert builder.write_successor(upgrade_root) == "UPGRADED_SUCCESSOR"
    assert builder.check_successor(upgrade_root) == expected
    assert builder.write_successor(upgrade_root) == "ALREADY_CURRENT"


def test_resealed_successor_marker_tampering_is_rejected(tmp_path: Path) -> None:
    _trace_outputs_value, _r025_outputs_value, outputs = _fixture(tmp_path)
    successor_raw = {
        relative: content.encode("utf-8") for relative, content in outputs.items()
    }
    successors = {
        relative: trace.strict_json_bytes(raw, relative.as_posix())
        for relative, raw in successor_raw.items()
    }
    tampered = deepcopy(successors)
    marker = tampered[builder.DOC05_REL]["fp046_artifact_trace_successor"]
    marker["input_bindings"][2]["relation"] = "GAP055_R024_SUCCESSOR_RESULT"
    tampered[builder.DOC05_REL].pop("content_sha256")
    tampered[builder.DOC05_REL]["content_sha256"] = trace.object_sha256(
        tampered[builder.DOC05_REL]
    )
    tampered_raw = dict(successor_raw)
    tampered_raw[builder.DOC05_REL] = trace.json_text(
        tampered[builder.DOC05_REL]
    ).encode("utf-8")
    with pytest.raises(builder.BuildError, match="input binding differs"):
        builder.validate_successor_structure(tampered, tampered_raw)
