from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import stat

import pytest

from scripts import build_walksafe_phase1_exact257_successor_r015_20260812 as subject


ROOT = Path(__file__).resolve().parents[1]


def test_r015_preserves_251_rows_and_adds_progress_only_to_exact_six() -> None:
    raw_by_path = {path: (ROOT / path).read_bytes() for path in subject.PREDECESSOR_PATHS}
    documents = {
        path: subject.trace.strict_json_bytes(raw, path.as_posix())
        for path, raw in raw_by_path.items()
    }
    subject.validate_predecessor_packet(documents, raw_by_path)
    predecessor = documents[subject.R014_LEDGER_REL]
    predecessor_bindings = [
        {"binding_id": f"R015-PRE-{index:03d}"}
        for index in range(1, 4)
    ]
    source_bindings = [
        {"binding_id": "R015-SRC-001", "subject_role": "NPC_IMPLEMENTATION_RESULT"},
        {"binding_id": "R015-SRC-002", "subject_role": "NPC_VERIFICATION_RESULT"},
        {"binding_id": "R015-SRC-003", "subject_role": "GAP008_R026_SUCCESSOR"},
    ]
    for index, (artifact_id, path) in enumerate(subject.TARGET_ARTIFACT_PATHS, start=4):
        source_bindings.append(
            {
                "binding_id": f"R015-SRC-{index:03d}",
                "subject_role": f"NPC_{artifact_id}_PHYSICAL_SUCCESSOR",
                "path": path.as_posix(),
                "sha256": str(index) * 64,
                "byte_length": index,
            }
        )

    ledger = subject.build_ledger(predecessor, predecessor_bindings, source_bindings)
    before = {row["artifact_type_code"]: row for row in predecessor["records"]}
    after = {row["artifact_type_code"]: row for row in ledger["records"]}
    changed = [artifact_id for artifact_id in before if before[artifact_id] != after[artifact_id]]
    assert set(changed) == set(subject.TARGET_ARTIFACT_IDS)
    assert len(before) - len(changed) == 251
    assert ledger["summaries"] == predecessor["summaries"]
    assert ledger["authorization_boundary"] == predecessor["authorization_boundary"]
    for artifact_id in subject.TARGET_ARTIFACT_IDS:
        assert after[artifact_id]["queue_route"] == before[artifact_id]["queue_route"]
        assert after[artifact_id]["artifact_closure"] == before[artifact_id]["artifact_closure"]
        assert after[artifact_id]["claim_boundary"] == before[artifact_id]["claim_boundary"]
        observation = after[artifact_id]["progress_axes"]["internal_validation"]["observations"][-1]
        assert observation["credit_count"] == 0
        assert observation["actual_recovery_drill_status"] == "NOT_RUN"
        assert observation["release_status"] == "NOT_ELIGIBLE"

    sealed = subject.r014_builder.seal_json(ledger, subject.R015_LEDGER_REL)
    subject.r014_builder.verify_nonself(
        subject.trace.strict_json_bytes(sealed, "R015 ledger"), subject.R015_LEDGER_REL
    )


def test_check_reports_missing_unresolved_inputs(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert subject.main(["--root", str(tmp_path), "--check"]) == 1
    assert "required source missing" in capsys.readouterr().out


def _output_bytes(value: int) -> bytes:
    return subject.trace.json_text({"value": value}).encode("utf-8")


def test_r015_writer_rejects_non_utf8_or_noncanonical_bytes(tmp_path: Path) -> None:
    target = subject.R015_LEDGER_REL
    with pytest.raises(subject.BuildError, match="strict UTF-8"):
        subject.write_or_check_outputs(tmp_path, {target: b"\xff"}, write=True)
    with pytest.raises(subject.BuildError, match="canonical JSON text"):
        subject.write_or_check_outputs(
            tmp_path,
            {target: b'{"value":1}\n'},
            write=True,
        )
    assert not (tmp_path / target).exists()


@pytest.mark.parametrize(
    "target",
    (
        Path("/tmp/walksafe-r015-path-escape.json"),
        Path("../walksafe-r015-path-escape.json"),
    ),
)
@pytest.mark.parametrize("write", (False, True))
def test_r015_writer_rejects_absolute_or_parent_escape_paths(
    tmp_path: Path,
    target: Path,
    write: bool,
) -> None:
    with pytest.raises(subject.BuildError, match="absolute|unsafe path"):
        subject.write_or_check_outputs(
            tmp_path,
            {target: _output_bytes(1)},
            write=write,
        )


def test_r015_writer_rejects_symlink_and_existing_different_bytes(
    tmp_path: Path,
) -> None:
    symlink_target = subject.R015_LEDGER_REL
    victim = tmp_path / "victim.json"
    victim.write_bytes(b"victim\n")
    (tmp_path / symlink_target).parent.mkdir(parents=True)
    (tmp_path / symlink_target).symlink_to(victim)

    with pytest.raises(subject.BuildError, match="safely open|symlink"):
        subject.write_or_check_outputs(
            tmp_path,
            {symlink_target: _output_bytes(1)},
            write=True,
        )
    assert victim.read_bytes() == b"victim\n"

    (tmp_path / symlink_target).unlink()
    subject.write_or_check_outputs(
        tmp_path,
        {symlink_target: _output_bytes(1)},
        write=True,
    )
    before = (tmp_path / symlink_target).stat()
    with pytest.raises(subject.BuildError, match="existing output differs"):
        subject.write_or_check_outputs(
            tmp_path,
            {symlink_target: _output_bytes(2)},
            write=True,
        )
    after = (tmp_path / symlink_target).stat()
    assert (after.st_dev, after.st_ino) == (before.st_dev, before.st_ino)
    assert (tmp_path / symlink_target).read_bytes() == _output_bytes(1)


def test_r015_check_rejects_target_entry_swap_after_descriptor_open(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    target = subject.R015_LEDGER_REL
    raw = _output_bytes(1)
    subject.write_or_check_outputs(tmp_path, {target: raw}, write=True)
    held = tmp_path / "held-r015-output.json"
    real_stat = subject.trace.io_base.os.stat
    swapped = False

    def swap_before_entry_identity_check(
        path: object,
        *args: object,
        **kwargs: object,
    ) -> os.stat_result:
        nonlocal swapped
        if (
            not swapped
            and path == target.name
            and kwargs.get("dir_fd") is not None
            and kwargs.get("follow_symlinks") is False
        ):
            live = tmp_path / target
            live.rename(held)
            live.write_bytes(raw)
            live.chmod(0o600)
            swapped = True
        return real_stat(path, *args, **kwargs)

    with monkeypatch.context() as patcher:
        patcher.setattr(subject.trace.io_base.os, "stat", swap_before_entry_identity_check)
        with pytest.raises(subject.BuildError, match="identity changed"):
            subject.write_or_check_outputs(tmp_path, {target: raw}, write=False)
    assert swapped
    assert held.read_bytes() == raw


def test_r015_writer_recovers_partial_preexisting_packet_add_only(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    outputs = {
        subject.R015_LEDGER_REL: _output_bytes(1),
        subject.R015_EVIDENCE_REL: _output_bytes(2),
        subject.R015_RECEIPT_REL: _output_bytes(3),
    }
    first, second, third = outputs
    subject.write_or_check_outputs(tmp_path, {first: outputs[first]}, write=True)
    first_identity = (tmp_path / first).stat()

    real_link = subject.trace.io_base.os.link
    failed = False

    def fail_third_commit(
        source: str,
        destination: str,
        **kwargs: object,
    ) -> None:
        nonlocal failed
        if destination == third.name and not failed:
            failed = True
            raise OSError("injected R015 partial commit failure")
        real_link(source, destination, **kwargs)

    with monkeypatch.context() as patcher:
        patcher.setattr(subject.trace.io_base.os, "link", fail_third_commit)
        with pytest.raises(OSError, match="partial commit failure"):
            subject.write_or_check_outputs(tmp_path, outputs, write=True)

    assert failed
    assert (tmp_path / first).read_bytes() == outputs[first]
    assert (tmp_path / second).read_bytes() == outputs[second]
    assert not (tmp_path / third).exists()

    subject.write_or_check_outputs(tmp_path, outputs, write=True)
    identities = {
        relative: (tmp_path / relative).stat()
        for relative in outputs
    }
    subject.write_or_check_outputs(tmp_path, outputs, write=True)
    subject.write_or_check_outputs(tmp_path, outputs, write=False)

    assert (first_identity.st_dev, first_identity.st_ino) == (
        identities[first].st_dev,
        identities[first].st_ino,
    )
    for relative, raw in outputs.items():
        current = (tmp_path / relative).stat()
        assert (current.st_dev, current.st_ino) == (
            identities[relative].st_dev,
            identities[relative].st_ino,
        )
        assert stat.S_IMODE(current.st_mode) == 0o600
        assert current.st_nlink == 1
        assert (tmp_path / relative).read_bytes() == raw
    assert not list(tmp_path.glob(".walksafe-fp008-add-only-*.stage"))
