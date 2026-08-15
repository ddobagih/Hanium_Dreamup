from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

import pytest

from scripts import build_walksafe_npc_single_admin_recovery_artifact_trace_successor_20260812 as subject


ROOT = Path(__file__).resolve().parents[1]


def _predecessor_raw() -> dict[Path, bytes]:
    return {
        path: subprocess.check_output(
            ["git", "show", f"HEAD:{path.as_posix()}"], cwd=ROOT
        )
        for path in subject.OUTPUT_PATHS
    }


def _write(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)


def _producer_and_gap() -> tuple[dict, bytes, dict, bytes, dict, bytes]:
    files = [
        {
            "path": "apps/android/adminapp/src/main/Fake.java",
            "sha256": "a" * 64,
            "byte_count": 10,
        },
        {
            "path": "backend/app/services/admin_security.py",
            "sha256": "b" * 64,
            "byte_count": 20,
        },
    ]
    manifest = {
        "files": files,
        "file_count": len(files),
        "path_set_sha256": "c" * 64,
        "content_set_sha256": "d" * 64,
    }
    manifest["manifest_content_sha256"] = subject.trace.object_sha256(manifest)
    implementation = subject.trace._seal(
        {
            "schema_version": "1.0",
            "document_id": "TEST-NPC-IMPLEMENTATION",
            "kind": "IMPLEMENTATION_RECORD",
            "goal_id": subject.trace.GOAL_ID,
            "policy_id": subject.trace.POLICY_ID,
            "gap_id": subject.trace.GAP_ID,
            "status": "PASS_INTERNAL",
            "observed_at": "2026-08-12T22:00:00+09:00",
            "scope_kind": "EXACT_ORDERED_NPC_SINGLE_ADMIN_RECOVERY_FINAL_CONTENT_MANIFEST",
            "final_content_manifest": manifest,
            "completion_boundary": subject.trace.completion_boundary(),
        },
        "implementation_record_content_sha256",
    )
    implementation_raw = subject.trace.json_text(implementation).encode()
    verification = subject.trace._seal(
        {
            "schema_version": "1.0",
            "document_id": "TEST-NPC-VERIFICATION",
            "kind": "VERIFICATION_RESULT",
            "goal_id": subject.trace.GOAL_ID,
            "status": "PASS_INTERNAL",
            "observed_at": "2026-08-12T22:00:00+09:00",
            "implementation_record_sha256": subject.trace.bytes_sha256(implementation_raw),
            "completion_boundary": subject.trace.completion_boundary(),
        },
        "verification_result_content_sha256",
    )
    verification_raw = subject.trace.json_text(verification).encode()
    r025_gap_raw = (ROOT / subject.gap_builder.R025_GAP_REL).read_bytes()
    r025_backlog_raw = (ROOT / subject.gap_builder.R025_BACKLOG_REL).read_bytes()
    gap_outputs = subject.gap_builder.build_documents(
        subject.trace.strict_json_bytes(r025_gap_raw, "r025 gap"),
        subject.trace.strict_json_bytes(r025_backlog_raw, "r025 backlog"),
        implementation,
        verification,
        predecessor_gap_raw=r025_gap_raw,
        predecessor_backlog_raw=r025_backlog_raw,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
    )
    gap_raw = gap_outputs[subject.gap_builder.R026_GAP_JSON_REL].encode()
    gap = subject.trace.strict_json_bytes(gap_raw, "r026 gap")
    return implementation, implementation_raw, verification, verification_raw, gap, gap_raw


def _materialize_writer_root(root: Path) -> dict[Path, bytes]:
    root.mkdir()
    predecessor_raw = _predecessor_raw()
    for relative, raw in predecessor_raw.items():
        _write(root, relative, raw)
    _, implementation_raw, _, verification_raw, _, gap_raw = _producer_and_gap()
    for relative, raw in (
        (subject.trace.IMPLEMENTATION_REL, implementation_raw),
        (subject.trace.VERIFICATION_REL, verification_raw),
        (subject.gap_builder.R026_GAP_JSON_REL, gap_raw),
        (subject.BUILDER_REL, (ROOT / subject.BUILDER_REL).read_bytes()),
    ):
        _write(root, relative, raw)
    return predecessor_raw


def test_exact_six_successors_preserve_planned_test_and_credit_boundaries() -> None:
    predecessor_raw = _predecessor_raw()
    predecessors = {
        path: subject.trace.strict_json_bytes(raw, path.as_posix())
        for path, raw in predecessor_raw.items()
    }
    implementation, implementation_raw, verification, verification_raw, gap, gap_raw = _producer_and_gap()
    before_requirement = next(
        row
        for row in predecessors[subject.RTM_REL]["requirements"]
        if row["requirement_id"] == "RQ-NPC-SINGLE-ADMIN-RECOVERY-001"
    )

    outputs = subject.build_documents(
        predecessors,
        predecessor_raw,
        implementation,
        verification,
        gap,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
        generator_sha256="e" * 64,
    )
    successors = {path: json.loads(raw) for path, raw in outputs.items()}
    assert set(successors) == set(subject.OUTPUT_PATHS)
    subject.validate_successor_documents(
        successors,
        {path: raw.encode("utf-8") for path, raw in outputs.items()},
        implementation,
        verification,
        gap,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
    )
    for path, document in successors.items():
        marker = document["npc_single_admin_recovery_artifact_trace_successor"]
        assert marker["predecessor_sha256"] == subject.EXPECTED_PREDECESSOR_SHA256_BY_PATH[path]
        assert marker["trace_boundary"]["formal_test_status"] == "NOT_RUN"
        assert marker["trace_boundary"]["actual_recovery_drill_status"] == "NOT_RUN"
        assert marker["trace_boundary"]["release_status"] == "NOT_ELIGIBLE"
        subject.verify_projection_seal(document, subject.SEAL_FIELD_BY_PATH[path], str(path))

    after_requirement = next(
        row
        for row in successors[subject.RTM_REL]["requirements"]
        if row["requirement_id"] == "RQ-NPC-SINGLE-ADMIN-RECOVERY-001"
    )
    assert after_requirement["acceptance_conditions"] == before_requirement["acceptance_conditions"]
    assert all(row["test_execution_status"] == "NOT_RUN" for row in after_requirement["acceptance_conditions"])
    assert successors[subject.DOC05_REL]["changes"][-1]["change_id"] == subject.CHANGE_ID
    doc01_rows = {
        row["display_code"]: row for row in successors[subject.DOC01_REL]["artifacts"]
    }
    assert doc01_rows["DOC-01"]["npc_single_admin_recovery_internal_rebinding"]["self_physical_sha256_excluded"] is True


def test_check_fails_cleanly_when_producer_results_are_unresolved(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert subject.main(["--root", str(tmp_path), "--check"]) == 1
    assert "output parent missing" in capsys.readouterr().out


def test_writer_rejects_symlink_target_without_touching_referent(tmp_path: Path) -> None:
    root = tmp_path / "symlink-target"
    _materialize_writer_root(root)
    relative = subject.DOC05_REL
    target = root / relative
    victim = root / "victim.json"
    victim_raw = b'{"outside":"must-remain"}\n'
    victim.write_bytes(victim_raw)
    target.unlink()
    target.symlink_to(victim)

    with pytest.raises(subject.BuildError, match="cannot safely open|file authority"):
        subject.write_successor(root)

    assert target.is_symlink()
    assert victim.read_bytes() == victim_raw
    assert not (root / subject.TRANSACTION_JOURNAL_REL).exists()


def test_live_producer_mutation_invalidates_all_six_successor_bindings(
    tmp_path: Path,
) -> None:
    root = tmp_path / "producer-mutation"
    _materialize_writer_root(root)
    subject.write_successor(root)
    implementation_path = root / subject.trace.IMPLEMENTATION_REL
    implementation = json.loads(implementation_path.read_bytes())
    implementation["observed_at"] = "2026-08-12T22:00:01+09:00"
    implementation.pop("implementation_record_content_sha256")
    implementation = subject.trace._seal(
        implementation, "implementation_record_content_sha256"
    )
    implementation_path.write_bytes(
        subject.trace.json_text(implementation).encode("utf-8")
    )

    with pytest.raises(subject.BuildError, match="live producer input bindings differ"):
        subject.check_successor(root)
    with pytest.raises(subject.BuildError, match="live producer input bindings differ"):
        subject.write_successor(root)


@pytest.mark.parametrize("race_timing", ("before_exchange", "after_exchange"))
def test_exchange_cas_preserves_foreign_target_and_retains_journal(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, race_timing: str
) -> None:
    root = tmp_path / "target-race"
    predecessor_raw = _materialize_writer_root(root)
    raced = subject.RTM_REL
    foreign_raw = b'{"foreign":"target-race"}\n'
    original_exchange = subject.fp008_builder._rename_exchange_at
    injected = False

    def exchange_after_target_race(
        source_descriptor: int,
        source_name: str,
        target_descriptor: int,
        target_name: str,
    ) -> None:
        nonlocal injected
        should_inject = not injected and target_name == raced.name
        if should_inject and race_timing == "before_exchange":
            injected = True
            foreign_stage = (root / raced).with_name(f".{raced.name}.foreign")
            foreign_stage.write_bytes(foreign_raw)
            os.replace(foreign_stage, root / raced)
        original_exchange(
            source_descriptor,
            source_name,
            target_descriptor,
            target_name,
        )
        if should_inject and race_timing == "after_exchange":
            injected = True
            foreign_stage = (root / raced).with_name(f".{raced.name}.foreign")
            foreign_stage.write_bytes(foreign_raw)
            os.replace(foreign_stage, root / raced)

    monkeypatch.setattr(
        subject.fp008_builder, "_rename_exchange_at", exchange_after_target_race
    )
    with pytest.raises(subject.BuildError, match="rollback retained|CAS"):
        subject.write_successor(root)

    assert injected
    assert (root / raced).read_bytes() == foreign_raw
    assert {
        relative: (root / relative).read_bytes()
        for relative in subject.OUTPUT_PATHS
        if relative != raced
    } == {
        relative: predecessor_raw[relative]
        for relative in subject.OUTPUT_PATHS
        if relative != raced
    }
    assert (root / subject.TRANSACTION_JOURNAL_REL).is_file()


def test_rerun_recovers_interrupted_partial_commit_then_publishes_all_successors(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = tmp_path / "interrupted"
    predecessor_raw = _materialize_writer_root(root)
    expected = subject.build_outputs(root)
    original_exchange = subject.fp008_builder._rename_exchange_at
    original_recover = subject._recover_pending_transaction_locked
    calls = 0

    def fail_third_exchange(*args: object) -> None:
        nonlocal calls
        calls += 1
        if calls == 3:
            raise OSError("simulated process interruption")
        original_exchange(*args)

    def abandon_immediate_rollback(*args: object, **kwargs: object) -> str:
        if kwargs.get("force_rollback") is True:
            raise RuntimeError("simulated process stopped before recovery")
        return original_recover(*args, **kwargs)

    with monkeypatch.context() as patch:
        patch.setattr(
            subject.fp008_builder, "_rename_exchange_at", fail_third_exchange
        )
        patch.setattr(
            subject, "_recover_pending_transaction_locked", abandon_immediate_rollback
        )
        with pytest.raises(RuntimeError, match="stopped before recovery") as raised:
            subject.write_successor(root)
    assert isinstance(raised.value.__cause__, OSError)
    assert (root / subject.TRANSACTION_JOURNAL_REL).is_file()
    interrupted_raw = {
        relative: (root / relative).read_bytes() for relative in subject.OUTPUT_PATHS
    }
    assert sum(
        interrupted_raw[relative] == expected[relative].encode("utf-8")
        for relative in subject.OUTPUT_PATHS
    ) == 2
    assert sum(
        interrupted_raw[relative] == predecessor_raw[relative]
        for relative in subject.OUTPUT_PATHS
    ) == 4

    root_descriptor = subject.fp008_builder._open_locked_root(root)
    descriptors: list[int] = []
    try:
        held, descriptors = subject._open_held_outputs(root_descriptor)
        assert (
            subject._recover_pending_transaction_locked(
                root, root_descriptor, held
            )
            == "ROLLED_BACK"
        )
        recovered_raw, _ = subject._read_held_outputs(held)
        assert recovered_raw == predecessor_raw
    finally:
        subject._close_locked_context(root_descriptor, descriptors)
    assert not (root / subject.TRANSACTION_JOURNAL_REL).exists()

    subject.write_successor(root)

    assert subject.check_successor(root) == expected
    assert not (root / subject.TRANSACTION_JOURNAL_REL).exists()
    for relative in subject.OUTPUT_PATHS:
        assert not (root / subject._transaction_member(relative, "stage")).exists()
        assert not (root / subject._transaction_member(relative, "backup")).exists()
