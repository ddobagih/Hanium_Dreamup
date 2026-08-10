from __future__ import annotations

import copy
import json
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import walksafe_fp008_builder_test_support as support
from scripts import build_walksafe_fp008_admin_review_delivery_trace_20260803 as trace
from scripts import build_walksafe_fp008_gap_backlog_r024_20260803 as gap_builder
from scripts import build_walksafe_fp008_artifact_trace_successor_20260803 as builder


def _materialize_stale_live_successor(
    root: Path, monkeypatch: pytest.MonkeyPatch
) -> dict[Path, str]:
    trace_outputs = support.build_trace(root)
    r024_outputs = support.build_r024(trace_outputs)
    expected = support.build_artifacts(trace_outputs, r024_outputs)
    for relative, content in trace_outputs.items():
        support.write(relative, content.encode(), root)
    for relative, content in r024_outputs.items():
        support.write(relative, content.encode(), root)
    source_raw = {
        relative: (support.REPO_ROOT / relative).read_bytes()
        for relative in builder.OUTPUT_PATHS
    }
    monkeypatch.setattr(
        builder,
        "EXPECTED_CORRECTION_REBASE_SOURCE_BY_PATH",
        {
            relative: {
                "sha256": trace.bytes_sha256(raw),
                "byte_length": len(raw),
            }
            for relative, raw in source_raw.items()
        },
    )
    for relative, raw in source_raw.items():
        support.write(relative, raw, root)
    return expected


def _stage_rebase_transaction(
    root: Path, expected: dict[Path, str], replace_count: int
) -> dict[Path, dict[str, object]]:
    source_raw = {
        relative: (root / relative).read_bytes()
        for relative in builder.OUTPUT_PATHS
    }
    manifest, journal_raw = builder._transaction_manifest(
        source_raw, expected, builder.SOURCE_FP008_CORRECTION_SUCCESSOR
    )
    rows = builder._validate_transaction_manifest(manifest, journal_raw)
    builder._write_private_stage(
        root / builder.TRANSACTION_JOURNAL_REL, journal_raw, 0o600
    )
    for relative in builder.OUTPUT_PATHS:
        mode = (root / relative).stat().st_mode & 0o777
        builder._write_private_stage(
            root / Path(rows[relative]["backup_path"]),
            source_raw[relative],
            mode,
        )
        builder._write_private_stage(
            root / Path(rows[relative]["stage_path"]),
            expected[relative].encode(),
            mode,
        )
    for relative in builder.OUTPUT_PATHS[:replace_count]:
        (root / Path(rows[relative]["stage_path"])).replace(root / relative)
    return rows


def test_six_successors_bind_fp008_without_formal_credit_and_fix_adminapp(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    live_raw = {
        relative: (support.REPO_ROOT / relative).read_bytes()
        for relative in builder.OUTPUT_PATHS
    }
    live_documents = {
        relative: trace.strict_json_bytes(raw, relative.as_posix())
        for relative, raw in live_raw.items()
    }
    current_pins = {
        relative: {
            "sha256": trace.bytes_sha256(raw),
            "byte_length": len(raw),
        }
        for relative, raw in live_raw.items()
    }
    split = len(builder.OUTPUT_PATHS) // 2
    with monkeypatch.context() as patch:
        patch.setattr(
            builder,
            "EXPECTED_ONE_TIME_REBASE_SOURCE_BY_PATH",
            {
                relative: (
                    current_pins[relative]
                    if index < split
                    else {"sha256": "0" * 64, "byte_length": len(live_raw[relative])}
                )
                for index, relative in enumerate(builder.OUTPUT_PATHS)
            },
        )
        patch.setattr(
            builder,
            "EXPECTED_CORRECTION_REBASE_SOURCE_BY_PATH",
            {
                relative: (
                    {"sha256": "f" * 64, "byte_length": len(live_raw[relative])}
                    if index < split
                    else current_pins[relative]
                )
                for index, relative in enumerate(builder.OUTPUT_PATHS)
            },
        )
        with pytest.raises(trace.BuildError, match="pinned one-time"):
            builder._classify_source_snapshot(live_raw)
    live_input_raw = {
        relative: (support.REPO_ROOT / relative).read_bytes()
        for relative in builder.INPUT_PATHS
    }
    live_inputs = {
        relative: trace.strict_json_bytes(raw, relative.as_posix())
        for relative, raw in live_input_raw.items()
    }
    builder.validate_successor_documents(
        live_documents,
        live_raw,
        live_inputs[trace.IMPLEMENTATION_REL],
        live_inputs[trace.VERIFICATION_REL],
        live_inputs[gap_builder.R024_GAP_JSON_REL],
        implementation_raw=live_input_raw[trace.IMPLEMENTATION_REL],
        verification_raw=live_input_raw[trace.VERIFICATION_REL],
        gap_raw=live_input_raw[gap_builder.R024_GAP_JSON_REL],
    )
    assert set(builder.validate_successor_structure(live_documents, live_raw)) == set(
        builder.OUTPUT_PATHS
    )
    tampered = copy.deepcopy(live_documents)
    marker = tampered[builder.DOC05_REL]["fp008_artifact_trace_successor"]
    marker["input_bindings"][0]["sha256"] = "0" * 64
    tampered[builder.DOC05_REL].pop("content_sha256")
    tampered[builder.DOC05_REL]["content_sha256"] = trace.object_sha256(
        tampered[builder.DOC05_REL]
    )
    tampered_raw = dict(live_raw)
    tampered_raw[builder.DOC05_REL] = trace.json_text(
        tampered[builder.DOC05_REL]
    ).encode()
    with pytest.raises(trace.BuildError, match="input bindings disagree"):
        builder.validate_successor_structure(tampered, tampered_raw)

    coherent = copy.deepcopy(live_documents)
    target_codes = set(builder.TARGET_ARTIFACT_PATHS)
    for row in coherent[builder.DOC01_REL]["artifacts"]:
        if row.get("display_code") in target_codes:
            row["integrity"]["generator_sha256"] = "f" * 64
    coherent[builder.DOC01_REL].pop("content_sha256")
    coherent[builder.DOC01_REL]["content_sha256"] = trace.object_sha256(
        coherent[builder.DOC01_REL]
    )
    coherent_raw = dict(live_raw)
    coherent_raw[builder.DOC01_REL] = trace.json_text(
        coherent[builder.DOC01_REL]
    ).encode()
    assert set(builder.validate_successor_structure(coherent, coherent_raw)) == set(
        builder.OUTPUT_PATHS
    )
    with pytest.raises(trace.BuildError):
        builder.validate_successor_documents(
            coherent,
            coherent_raw,
            live_inputs[trace.IMPLEMENTATION_REL],
            live_inputs[trace.VERIFICATION_REL],
            live_inputs[gap_builder.R024_GAP_JSON_REL],
            implementation_raw=live_input_raw[trace.IMPLEMENTATION_REL],
            verification_raw=live_input_raw[trace.VERIFICATION_REL],
            gap_raw=live_input_raw[gap_builder.R024_GAP_JSON_REL],
        )
    with pytest.raises(trace.BuildError, match="pinned one-time"):
        builder._classify_source_snapshot(coherent_raw)
    trace_outputs = support.build_trace(tmp_path)
    r024_outputs = support.build_r024(trace_outputs)
    first = support.build_artifacts(trace_outputs, r024_outputs)
    second = support.build_artifacts(trace_outputs, r024_outputs)
    assert first == second
    assert tuple(first) == builder.OUTPUT_PATHS
    for relative, raw in first.items():
        document = json.loads(raw)
        marker = document["fp008_artifact_trace_successor"]
        assert marker["successor_id"] == builder.SUCCESSOR_ID
        assert marker["trace_boundary"] == builder.boundary()
        assert marker["trace_boundary"]["formal_test_status"] == "NOT_RUN"
        assert marker["trace_boundary"]["release_credit_count"] == 0
    module = json.loads(first[builder.MODULE_REGISTER_REL])
    admin = next(row for row in module["modules"] if row["module_id"] == "MOD-ANDROID-ADMIN")
    assert admin["paths"] == ["apps/android/adminapp"]
    assert "apps/android/admin" not in admin["paths"]
    rtm = json.loads(first[builder.RTM_REL])
    requirement = next(row for row in rtm["requirements"] if row["requirement_id"] == "RQ-FP-008-001")
    assert [row["planned_test_id"] for row in requirement["acceptance_conditions"]] == list(trace.FORMAL_TEST_IDS)
    assert {row["test_execution_status"] for row in requirement["acceptance_conditions"]} == {"NOT_RUN"}
    assert not any(row["pass_claimed"] for row in requirement["acceptance_conditions"])


def test_forbidden_implementation_source_is_rejected(tmp_path: Path) -> None:
    trace_outputs = support.build_trace(tmp_path)
    r024_outputs = support.build_r024(trace_outputs)
    implementation = json.loads(trace_outputs[trace.IMPLEMENTATION_REL])
    implementation["changed_artifacts"][0]["path"] = "apps/web/admin.ts"
    implementation.pop("implementation_record_content_sha256")
    implementation = trace.sealed(implementation, "implementation_record_content_sha256")
    with pytest.raises(trace.BuildError, match="forbidden"):
        builder._implementation_rows(implementation)


def test_six_document_writer_rebases_valid_successor_and_is_idempotent(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    normal_root = tmp_path / "normal"
    normal_root.mkdir()
    expected = _materialize_stale_live_successor(normal_root, monkeypatch)
    foreign = normal_root / "foreign" / "keep.txt"
    support.write(Path("foreign/keep.txt"), b"unchanged\n", normal_root)
    old_marker = json.loads(
        (normal_root / builder.DOC05_REL).read_text(encoding="utf-8")
    )["fp008_artifact_trace_successor"]
    assert old_marker["input_bindings"][0]["sha256"] != trace.bytes_sha256(
        (normal_root / trace.IMPLEMENTATION_REL).read_bytes()
    )

    assert builder.write_successor(normal_root) == "REBASED_SUCCESSOR"
    assert builder.check_successor(normal_root) == expected
    assert builder.write_successor(normal_root) == "ALREADY_CURRENT"
    assert foreign.read_bytes() == b"unchanged\n"
    assert {
        relative: (normal_root / relative).read_text(encoding="utf-8")
        for relative in builder.OUTPUT_PATHS
    } == expected

    swap_root = tmp_path / "swap-root"
    swap_root.mkdir()
    _materialize_stale_live_successor(swap_root, monkeypatch)
    support.write(Path("sentinel.txt"), b"original-root\n", swap_root)
    replacement = tmp_path / "replacement-root"
    replacement.mkdir()
    displaced = tmp_path / "displaced-root"
    real_open = builder.os.open
    swapped = False

    def swap_before_root_open(path: object, flags: int, *args: object, **kwargs: object) -> int:
        nonlocal swapped
        if not swapped and Path(path) == swap_root and kwargs.get("dir_fd") is None:
            swapped = True
            swap_root.rename(displaced)
            replacement.rename(swap_root)
        return real_open(path, flags, *args, **kwargs)

    with monkeypatch.context() as patch:
        patch.setattr(builder.os, "open", swap_before_root_open)
        with pytest.raises(trace.BuildError, match="root changed while opening"):
            builder.write_successor(swap_root)
    assert swapped
    assert (displaced / "sentinel.txt").read_bytes() == b"original-root\n"
    assert all(
        trace.bytes_sha256((displaced / relative).read_bytes())
        == builder.EXPECTED_CORRECTION_REBASE_SOURCE_BY_PATH[relative]["sha256"]
        for relative in builder.OUTPUT_PATHS
    )

    parent_swap_root = tmp_path / "parent-swap-root"
    parent_swap_root.mkdir()
    _materialize_stale_live_successor(parent_swap_root, monkeypatch)
    support.write(
        Path("docs/deliverables/parent-sentinel.txt"),
        b"original-parent\n",
        parent_swap_root,
    )
    replacement_deliverables = parent_swap_root / "replacement-deliverables"
    replacement_deliverables.mkdir()
    displaced_deliverables = parent_swap_root / "docs" / "deliverables-displaced"
    real_exchange = builder._rename_exchange_at
    parent_swapped = False

    def swap_parent_during_commit(
        source_directory_descriptor: int,
        source_name: str,
        target_directory_descriptor: int,
        target_name: str,
    ) -> None:
        nonlocal parent_swapped
        if not parent_swapped:
            parent_swapped = True
            (parent_swap_root / "docs" / "deliverables").rename(
                displaced_deliverables
            )
            replacement_deliverables.rename(
                parent_swap_root / "docs" / "deliverables"
            )
        real_exchange(
            source_directory_descriptor,
            source_name,
            target_directory_descriptor,
            target_name,
        )

    with monkeypatch.context() as patch:
        patch.setattr(builder, "_rename_exchange_at", swap_parent_during_commit)
        with pytest.raises(trace.BuildError, match="transaction parent changed"):
            builder.write_successor(parent_swap_root)
    assert parent_swapped
    assert (
        displaced_deliverables / "parent-sentinel.txt"
    ).read_bytes() == b"original-parent\n"
    for relative in builder.OUTPUT_PATHS:
        suffix = relative.relative_to("docs/deliverables")
        assert trace.bytes_sha256((displaced_deliverables / suffix).read_bytes()) == (
            builder.EXPECTED_CORRECTION_REBASE_SOURCE_BY_PATH[relative]["sha256"]
        )

    lost_update_root = tmp_path / "lost-update"
    lost_update_root.mkdir()
    _materialize_stale_live_successor(lost_update_root, monkeypatch)
    concurrent_relative = builder.OUTPUT_PATHS[1]
    concurrent_raw = b"concurrent foreign update\n"
    real_exchange = builder._rename_exchange_at
    injected = False

    def mutate_later_target_before_first_exchange(
        source_directory_descriptor: int,
        source_name: str,
        target_directory_descriptor: int,
        target_name: str,
    ) -> None:
        nonlocal injected
        if not injected:
            injected = True
            support.write(concurrent_relative, concurrent_raw, lost_update_root)
        real_exchange(
            source_directory_descriptor,
            source_name,
            target_directory_descriptor,
            target_name,
        )

    with monkeypatch.context() as patch:
        patch.setattr(
            builder,
            "_rename_exchange_at",
            mutate_later_target_before_first_exchange,
        )
        with pytest.raises(trace.BuildError, match="source CAS changed before exchange"):
            builder.write_successor(lost_update_root)
    assert injected
    assert (lost_update_root / concurrent_relative).read_bytes() == concurrent_raw
    for relative in builder.OUTPUT_PATHS:
        if relative != concurrent_relative:
            assert trace.bytes_sha256((lost_update_root / relative).read_bytes()) == (
                builder.EXPECTED_CORRECTION_REBASE_SOURCE_BY_PATH[relative]["sha256"]
            )
        assert not (lost_update_root / builder._transaction_member(relative, "stage")).exists()
        assert not (lost_update_root / builder._transaction_member(relative, "backup")).exists()
    assert not (lost_update_root / builder.TRANSACTION_JOURNAL_REL).exists()


def test_interrupted_rebase_is_rolled_back_then_reapplied(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    mixed_root = tmp_path / "mixed"
    mixed_root.mkdir()
    expected = _materialize_stale_live_successor(mixed_root, monkeypatch)
    rows = _stage_rebase_transaction(mixed_root, expected, 1)

    assert builder.write_successor(mixed_root) == "RECOVERED_AND_REBASED_SUCCESSOR"
    assert builder.check_successor(mixed_root) == expected
    assert not (mixed_root / builder.TRANSACTION_JOURNAL_REL).exists()
    for relative in builder.OUTPUT_PATHS:
        assert not (mixed_root / Path(rows[relative]["stage_path"])).exists()
        assert not (mixed_root / Path(rows[relative]["backup_path"])).exists()

    complete_root = tmp_path / "complete"
    complete_root.mkdir()
    committed = _materialize_stale_live_successor(complete_root, monkeypatch)
    committed_rows = _stage_rebase_transaction(
        complete_root, committed, len(builder.OUTPUT_PATHS)
    )
    implementation = json.loads(
        (complete_root / trace.IMPLEMENTATION_REL).read_text(encoding="utf-8")
    )
    implementation["prepared_at"] = "2026-08-09T23:59:59+09:00"
    implementation.pop("implementation_record_content_sha256")
    implementation = trace.sealed(
        implementation, "implementation_record_content_sha256"
    )
    support.write(
        trace.IMPLEMENTATION_REL,
        trace.json_text(implementation).encode(),
        complete_root,
    )
    assert builder.write_successor(complete_root) == "RECOVERED_CURRENT_SUCCESSOR"
    assert not (complete_root / builder.TRANSACTION_JOURNAL_REL).exists()
    assert {
        relative: (complete_root / relative).read_text(encoding="utf-8")
        for relative in builder.OUTPUT_PATHS
    } == committed
    for relative in builder.OUTPUT_PATHS:
        assert not (
            complete_root / Path(committed_rows[relative]["backup_path"])
        ).exists()

    partial_root = tmp_path / "partial-stage"
    partial_root.mkdir()
    partial_expected = _materialize_stale_live_successor(partial_root, monkeypatch)
    partial_source_raw = {
        relative: (partial_root / relative).read_bytes()
        for relative in builder.OUTPUT_PATHS
    }
    partial_manifest, partial_journal_raw = builder._transaction_manifest(
        partial_source_raw,
        partial_expected,
        builder.SOURCE_FP008_CORRECTION_SUCCESSOR,
    )
    partial_rows = builder._validate_transaction_manifest(
        partial_manifest, partial_journal_raw
    )
    builder._write_private_stage(
        partial_root / builder.TRANSACTION_JOURNAL_REL,
        partial_journal_raw,
        0o600,
    )
    first_relative = builder.OUTPUT_PATHS[0]
    first_stage = Path(partial_rows[first_relative]["stage_path"])
    builder._write_private_stage(
        partial_root / first_stage,
        partial_expected[first_relative].encode()[:31],
        0o600,
    )
    assert builder.write_successor(partial_root) == "RECOVERED_AND_REBASED_SUCCESSOR"
    assert builder.check_successor(partial_root) == partial_expected
    assert not (partial_root / builder.TRANSACTION_JOURNAL_REL).exists()
    assert not (partial_root / first_stage).exists()

    identical_root = tmp_path / "identical-journal"
    identical_root.mkdir()
    identical_expected = _materialize_stale_live_successor(
        identical_root, monkeypatch
    )
    identical_source_raw = {
        relative: (identical_root / relative).read_bytes()
        for relative in builder.OUTPUT_PATHS
    }
    identical_manifest, _ = builder._transaction_manifest(
        identical_source_raw,
        identical_expected,
        builder.SOURCE_FP008_CORRECTION_SUCCESSOR,
    )
    for row in identical_manifest["outputs"]:
        row["successor_sha256"] = row["source_sha256"]
        row["successor_byte_length"] = row["source_byte_length"]
    identical_manifest["intended_successor"] = builder._intended_successor_binding(
        identical_source_raw
    )
    identical_manifest.pop("transaction_content_sha256")
    identical_manifest = trace.sealed(
        identical_manifest, "transaction_content_sha256"
    )
    identical_journal_raw = trace.json_text(identical_manifest).encode()
    before_identical = dict(identical_source_raw)
    builder._write_private_stage(
        identical_root / builder.TRANSACTION_JOURNAL_REL,
        identical_journal_raw,
        0o600,
    )
    with pytest.raises(trace.BuildError, match="transaction output binding differs"):
        builder.write_successor(identical_root)
    assert {
        relative: (identical_root / relative).read_bytes()
        for relative in builder.OUTPUT_PATHS
    } == before_identical
    assert (
        identical_root / builder.TRANSACTION_JOURNAL_REL
    ).read_bytes() == identical_journal_raw


def test_private_transaction_stage_is_removed_on_write_failure(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    target = tmp_path / "transaction-stage.json"

    def fail_write(_descriptor: int, _raw: bytes) -> int:
        raise OSError("injected transaction stage failure")

    with monkeypatch.context() as patch:
        patch.setattr(builder.os, "write", fail_write)
        with pytest.raises(OSError, match="injected transaction stage failure"):
            builder._write_private_stage(target, b"payload", 0o600)
    assert not target.exists()

    partial_journal_root = tmp_path / "partial-anonymous-journal"
    partial_journal_root.mkdir()
    _materialize_stale_live_successor(partial_journal_root, monkeypatch)
    real_write = builder.os.write
    prefix_written = False

    def fail_after_journal_prefix(descriptor: int, raw: bytes) -> int:
        nonlocal prefix_written
        if not prefix_written:
            prefix_written = True
            assert real_write(descriptor, raw[:31]) == 31
            raise OSError("injected anonymous journal interruption")
        return real_write(descriptor, raw)

    with monkeypatch.context() as patch:
        patch.setattr(builder.os, "write", fail_after_journal_prefix)
        with pytest.raises(OSError, match="anonymous journal interruption"):
            builder.write_successor(partial_journal_root)
    assert prefix_written
    assert not (
        partial_journal_root / builder.TRANSACTION_JOURNAL_REL
    ).exists()
    assert all(
        trace.bytes_sha256((partial_journal_root / relative).read_bytes())
        == builder.EXPECTED_CORRECTION_REBASE_SOURCE_BY_PATH[relative]["sha256"]
        for relative in builder.OUTPUT_PATHS
    )
    assert builder.write_successor(partial_journal_root) == "REBASED_SUCCESSOR"

    uncertain_root = tmp_path / "uncertain"
    uncertain_root.mkdir()
    _materialize_stale_live_successor(uncertain_root, monkeypatch)
    real_recover = builder._recover_pending_transaction_locked

    def fail_commit_exchange(*_args: object, **_kwargs: object) -> None:
        raise OSError("injected publication failure")

    def fail_rollback(*args: object, **kwargs: object) -> str:
        if kwargs.get("force_rollback") is True:
            raise trace.BuildError("injected rollback uncertainty")
        return real_recover(*args, **kwargs)

    with monkeypatch.context() as patch:
        patch.setattr(builder, "_rename_exchange_at", fail_commit_exchange)
        patch.setattr(builder, "_recover_pending_transaction_locked", fail_rollback)
        with pytest.raises(trace.BuildError, match="rollback uncertainty") as raised:
            builder.write_successor(uncertain_root)
    assert isinstance(raised.value.__cause__, OSError)
    assert "publication failure" in str(raised.value.__cause__)
    assert (uncertain_root / builder.TRANSACTION_JOURNAL_REL).exists()
