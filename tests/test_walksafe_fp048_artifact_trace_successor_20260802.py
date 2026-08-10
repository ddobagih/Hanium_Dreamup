from __future__ import annotations

from copy import deepcopy
import fcntl
import hashlib
import json
import mmap
import os
from pathlib import Path
import select
import shutil
import signal
import stat
import subprocess
import sys
from unittest import mock

import pytest

from scripts import build_walksafe_fp048_artifact_trace_successor_20260802 as builder


def _write(root: Path, relative: Path, raw: bytes, mode: int = 0o600) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)
    path.chmod(mode)


def _write_json(root: Path, relative: Path, value: dict) -> bytes:
    raw = builder.json_bytes(value)
    _write(root, relative, raw)
    return raw


def _sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _fixture(tmp_path: Path) -> tuple[Path, dict[Path, str], dict[Path, str], dict[Path, list[Path]]]:
    root = tmp_path / "repository"
    root.mkdir()
    aliases: dict[Path, list[Path]] = {}
    live_outputs = {
        relative: (builder.ROOT / relative).read_bytes()
        for relative in builder.OUTPUT_PATHS
    }
    if all(
        _sha(live_outputs[relative]) == builder.PINNED_PREDECESSOR_SHA256[relative]
        for relative in builder.OUTPUT_PATHS
    ):
        predecessor_outputs = live_outputs
    else:
        recovered = builder._recover_predecessors(
            {
                relative: builder._json_from_bytes(raw, relative)
                for relative, raw in live_outputs.items()
            }
        )
        predecessor_outputs = {
            relative: builder.json_bytes(recovered[relative])
            for relative in builder.OUTPUT_PATHS
        }
    hard_linked = {
        builder.RTM_REL,
        builder.DESIGN_REL,
        builder.IMPLEMENTATION_MANIFEST_REL,
        builder.MODULE_REGISTER_REL,
    }
    for relative in builder.OUTPUT_PATHS:
        mode = 0o664 if relative in hard_linked else 0o600
        raw = predecessor_outputs[relative]
        assert _sha(raw) == builder.PINNED_PREDECESSOR_SHA256[relative]
        _write(root, relative, raw, mode)
        aliases[relative] = []
        if relative in hard_linked:
            for index in range(3):
                alias = root / ".fixture-predecessor-links" / f"{relative.name}.{index}"
                alias.parent.mkdir(parents=True, exist_ok=True)
                os.link(root / relative, alias)
                aliases[relative].append(alias)

    _write(root, builder.BUILDER_REL, (builder.ROOT / builder.BUILDER_REL).read_bytes())
    _write(root, builder.R021_GAP_REL, (builder.ROOT / builder.R021_GAP_REL).read_bytes())
    implementation_source = Path("apps/android/app/src/main/java/fp048/Fixture.kt")
    implementation_raw = b"package fp048\ninternal const val ENCRYPTED = true\n"
    _write(root, implementation_source, implementation_raw)
    changed_artifacts = [
        {
            "path": implementation_source.as_posix(),
            "before_sha256": "1" * 64,
            "after_sha256": _sha(implementation_raw),
            "before_source": "SEQ42_START_GATE_DIRTY_SNAPSHOT",
            "change_kind": "MODIFIED",
        }
    ]
    content_set = builder.object_sha256(
        [{"path": implementation_source.as_posix(), "sha256": _sha(implementation_raw)}]
    )
    implementation = {
        "schema_version": "1.0",
        "document_id": "WS-FP048-IMPLEMENTATION-FIXTURE",
        "goal_id": builder.GOAL_ID,
        "kind": "IMPLEMENTATION_RECORD",
        "status": "PASS",
        "changed_artifacts": changed_artifacts,
        "implementation_content_set_sha256": content_set,
    }
    implementation_bytes = _write_json(root, builder.IMPLEMENTATION_REL, implementation)

    verification_output = Path(
        "docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-048-R001/logs/fixture.log"
    )
    verification_output_bytes = b"81 passed\n"
    _write(root, verification_output, verification_output_bytes)
    verification = {
        "schema_version": "1.0",
        "document_id": "WS-FP048-VERIFICATION-FIXTURE",
        "goal_id": builder.GOAL_ID,
        "kind": "VERIFICATION_RESULT",
        "status": "PASS",
        "checks": [
            {
                "name": "FIXTURE",
                "exit_code": 0,
                "implementation_content_set_sha256": content_set,
                "output_path": verification_output.as_posix(),
                "output_sha256": _sha(verification_output_bytes),
            }
        ],
        "evidence_boundary": {
            "formal_test_ids": deepcopy(builder.EXPECTED_FORMAL_TEST_IDS),
            **deepcopy(builder.EXPECTED_BOUNDARY),
        },
    }
    verification_bytes = _write_json(root, builder.VERIFICATION_REL, verification)

    input_pins = {
        builder.IMPLEMENTATION_REL: _sha(implementation_bytes),
        builder.VERIFICATION_REL: _sha(verification_bytes),
        builder.GAP_REL: "0" * 64,
    }
    reassessment = {
        "implementation_record_sha256": input_pins[builder.IMPLEMENTATION_REL],
        "verification_result_sha256": input_pins[builder.VERIFICATION_REL],
        "internal_implementation_status": "PASS",
        "internal_verification_status": "PASS",
        **deepcopy(builder.EXPECTED_BOUNDARY),
    }
    r021_gap = json.loads((root / builder.R021_GAP_REL).read_text())
    current_source_bindings: list[dict] = []
    for index, name in enumerate(builder.EXPECTED_R023_CURRENT_SOURCE_BINDING_NAMES):
        source = Path(f"docs/control/evidence/fp048-current-source-{index:02d}.txt")
        source_raw = f"{name}\n".encode()
        _write(root, source, source_raw)
        current_source_bindings.append(
            {
                "name": name,
                "path": source.as_posix(),
                "bytes": len(source_raw),
                "sha256": _sha(source_raw),
            }
        )
    source_bindings = deepcopy(r021_gap["source_bindings"]) + current_source_bindings
    gap = {
        "schema_version": "walksafe.implementation-gap-analysis.v1",
        "metadata": {"report_id": builder.GAP_REPORT_ID},
        "source_bindings": source_bindings,
        "source_binding_sha256": builder.object_sha256(source_bindings),
        "assessments": [
            {"gap_id": "GAP-057", "status": "PARTIAL", "fp048_reassessment": reassessment}
        ],
    }
    gap["report_content_sha256"] = builder.object_sha256(gap)
    gap_bytes = _write_json(root, builder.GAP_REL, gap)
    input_pins[builder.GAP_REL] = _sha(gap_bytes)
    document_ids = {
        builder.IMPLEMENTATION_REL: implementation["document_id"],
        builder.VERIFICATION_REL: verification["document_id"],
    }
    return root, input_pins, document_ids, aliases


def _build(root: Path, pins: dict[Path, str], ids: dict[Path, str]) -> dict[Path, bytes]:
    return builder.build_outputs(
        root,
        expected_input_sha256=pins,
        expected_document_ids=ids,
    )


def _write_successor(root: Path, pins: dict[Path, str], ids: dict[Path, str], **kwargs: object) -> str:
    return builder.write_successor(
        root,
        expected_input_sha256=pins,
        expected_document_ids=ids,
        **kwargs,
    )


def _replace_implementation_rows(
    root: Path,
    pins: dict[Path, str],
    rows: list[dict],
) -> None:
    implementation = json.loads((root / builder.IMPLEMENTATION_REL).read_text())
    implementation["changed_artifacts"] = rows
    implementation["implementation_content_set_sha256"] = builder.object_sha256(
        [{"path": row["path"], "sha256": row["after_sha256"]} for row in rows]
    )
    raw = _write_json(root, builder.IMPLEMENTATION_REL, implementation)
    pins[builder.IMPLEMENTATION_REL] = _sha(raw)
    verification = json.loads((root / builder.VERIFICATION_REL).read_text())
    verification["checks"][0]["implementation_content_set_sha256"] = implementation[
        "implementation_content_set_sha256"
    ]
    raw = _write_json(root, builder.VERIFICATION_REL, verification)
    pins[builder.VERIFICATION_REL] = _sha(raw)
    gap = json.loads((root / builder.GAP_REL).read_text())
    reassessment = gap["assessments"][0]["fp048_reassessment"]
    reassessment["implementation_record_sha256"] = pins[builder.IMPLEMENTATION_REL]
    reassessment["verification_result_sha256"] = pins[builder.VERIFICATION_REL]
    gap.pop("report_content_sha256")
    gap["report_content_sha256"] = builder.object_sha256(gap)
    pins[builder.GAP_REL] = _sha(_write_json(root, builder.GAP_REL, gap))


def test_build_accepts_pinned_hardlinked_predecessors_and_is_deterministic(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    for relative in builder.OUTPUT_PATHS:
        assert _sha((root / relative).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[relative]
    first = _build(root, pins, ids)
    second = _build(root, pins, ids)
    assert first == second
    assert tuple(first) == builder.OUTPUT_PATHS


def test_all_successors_bind_cooperative_single_publisher_contract(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    outputs = _build(root, pins, ids)
    for raw in outputs.values():
        value = json.loads(raw)
        assert value["fp048_artifact_trace_successor"][
            "publication_concurrency_contract"
        ] == builder.PUBLICATION_CONCURRENCY_CONTRACT
    manifest, _raw = builder._transaction_manifest(outputs, pins)
    assert manifest["publication_concurrency_contract"] == (
        builder.PUBLICATION_CONCURRENCY_CONTRACT
    )


def test_publication_lock_follows_repository_inode_and_path_swap_fails_closed(
    tmp_path: Path,
) -> None:
    root = tmp_path / "lock-root"
    alias = tmp_path / "renamed-lock-root"
    root.mkdir()
    read_fd, write_fd = os.pipe()
    child: subprocess.Popen[bytes] | None = None
    try:
        with pytest.raises(
            builder.BuildError,
            match="publication lock FD or root path changed",
        ):
            with builder._publication_lock(root) as lease:
                root.rename(alias)
                child = subprocess.Popen(
                    [
                        sys.executable,
                        "-c",
                        (
                            "import os,sys; from pathlib import Path; "
                            "from scripts import "
                            "build_walksafe_fp048_artifact_trace_successor_20260802 as b; "
                            "\nwith b._publication_lock(Path(sys.argv[1])): "
                            "os.write(int(sys.argv[2]), b'x')"
                        ),
                        str(alias),
                        str(write_fd),
                    ],
                    pass_fds=(write_fd,),
                    cwd=builder.ROOT,
                )
                assert select.select([read_fd], [], [], 0.4)[0] == []
                lease.verify()
        assert select.select([read_fd], [], [], 5.0)[0] == [read_fd]
        assert os.read(read_fd, 1) == b"x"
        assert child.wait(timeout=5) == 0
    finally:
        os.close(read_fd)
        os.close(write_fd)
        if child is not None and child.poll() is None:
            child.terminate()
            child.wait(timeout=5)


def test_private_publication_uses_read_only_anonymous_link_descriptors(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    original = builder._link_fd_noreplace_at
    observed_targets: set[str] = set()

    def assert_read_only_then_link(
        descriptor: int,
        parent_fd: int,
        destination: str,
    ) -> None:
        if destination in {
            builder.OUTPUT_PATHS[0].name,
            builder._transaction_member_relative(
                builder.OUTPUT_PATHS[0],
                "backup",
            ).name,
        }:
            assert fcntl.fcntl(descriptor, fcntl.F_GETFL) & os.O_ACCMODE == os.O_RDONLY
            assert stat.S_IMODE(os.fstat(descriptor).st_mode) == 0o400
            with pytest.raises(PermissionError):
                os.open(
                    f"/proc/self/fd/{descriptor}",
                    os.O_RDWR | os.O_CLOEXEC,
                )
            observed_targets.add(destination)
        original(descriptor, parent_fd, destination)

    with mock.patch.object(
        builder,
        "_link_fd_noreplace_at",
        side_effect=assert_read_only_then_link,
    ):
        assert _write_successor(root, pins, ids) == "PUBLISHED_SUCCESSOR"
    assert observed_targets == {
        builder.OUTPUT_PATHS[0].name,
        builder._transaction_member_relative(builder.OUTPUT_PATHS[0], "backup").name,
    }
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_publish_breaks_predecessor_links_and_sets_private_single_link_outputs(tmp_path: Path) -> None:
    root, pins, ids, aliases = _fixture(tmp_path)
    assert _write_successor(root, pins, ids) == "PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)
    for relative in builder.OUTPUT_PATHS:
        info = (root / relative).stat()
        assert stat.S_IMODE(info.st_mode) == 0o600
        assert info.st_nlink == 1
        for alias in aliases[relative]:
            assert _sha(alias.read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[relative]
        for kind in ("stage", "backup", "authority", "quarantine"):
            assert not (root / builder._transaction_member_relative(relative, kind)).exists()
    assert not (root / builder.TRANSACTION_JOURNAL_REL).exists()
    assert _write_successor(root, pins, ids) == "ALREADY_CURRENT"


def test_r022_legacy_and_submission_sources_are_rejected(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    forbidden = Path("docs/control/audits/walksafe-plan-rebaseline-20260730-r022.json")
    forbidden_raw = b"{}\n"
    _write(root, forbidden, forbidden_raw)
    _replace_implementation_rows(root, pins, [
        {
            "path": forbidden.as_posix(),
            "before_sha256": None,
            "after_sha256": _sha(forbidden_raw),
            "before_source": "SEQ42_PINNED_HEAD_ABSENT",
            "change_kind": "ADDED",
        }
    ])
    with pytest.raises(builder.BuildError, match="forbidden implementation scope"):
        _build(root, pins, ids)


def test_r022_before_source_provenance_is_rejected(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    implementation = json.loads((root / builder.IMPLEMENTATION_REL).read_text())
    row = deepcopy(implementation["changed_artifacts"][0])
    row["before_source"] = "docs/control/audits/walksafe-plan-rebaseline-20260730-r022.json"
    _replace_implementation_rows(root, pins, [row])
    with pytest.raises(builder.BuildError, match="forbidden implementation provenance"):
        _build(root, pins, ids)


def test_noncanonical_web_before_source_provenance_is_rejected(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    implementation = json.loads((root / builder.IMPLEMENTATION_REL).read_text())
    row = deepcopy(implementation["changed_artifacts"][0])
    row["before_source"] = "apps//web/src/Forbidden.ts"
    _replace_implementation_rows(root, pins, [row])
    with pytest.raises(builder.BuildError, match="forbidden implementation provenance"):
        _build(root, pins, ids)


def test_noncanonical_web_path_cannot_bypass_scope_filter(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    forbidden = root / "apps/web/src/Forbidden.ts"
    forbidden.parent.mkdir(parents=True)
    forbidden.write_bytes(b"export const forbidden = true;\n")
    forbidden.chmod(0o600)
    _replace_implementation_rows(root, pins, [
        {
            "path": "apps//web/src/Forbidden.ts",
            "before_sha256": None,
            "after_sha256": _sha(forbidden.read_bytes()),
            "before_source": "SEQ42_PINNED_HEAD_ABSENT",
            "change_kind": "ADDED",
        }
    ])
    with pytest.raises(builder.BuildError, match="noncanonical"):
        _build(root, pins, ids)


def test_gap_source_bindings_must_be_rows_not_mapping_keys(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    gap = json.loads((root / builder.GAP_REL).read_text())
    gap["source_bindings"] = {
        "forbidden": {"path": "apps/web/src/legacy/submission-candidate-r022.json"}
    }
    gap.pop("report_content_sha256")
    gap["report_content_sha256"] = builder.object_sha256(gap)
    pins[builder.GAP_REL] = _sha(_write_json(root, builder.GAP_REL, gap))
    with pytest.raises(builder.BuildError, match="source bindings must be a list"):
        _build(root, pins, ids)


@pytest.mark.parametrize("mutation", ["missing", "wrong_sha", "wrong_bytes"])
def test_gap_source_bindings_require_held_physical_sha(
    tmp_path: Path,
    mutation: str,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    source = Path("docs/control/evidence/fp048-source-binding.json")
    source_raw = b'{"source":"fixture"}\n'
    if mutation != "missing":
        _write(root, source, source_raw)
    gap = json.loads((root / builder.GAP_REL).read_text())
    gap["source_bindings"][-1] = {
        "name": builder.EXPECTED_R023_CURRENT_SOURCE_BINDING_NAMES[-1],
        "path": source.as_posix(),
        "bytes": len(source_raw) + (1 if mutation == "wrong_bytes" else 0),
        "sha256": (
            "f" * 64
            if mutation == "missing"
            else "e" * 64 if mutation == "wrong_sha" else _sha(source_raw)
        ),
    }
    gap["source_binding_sha256"] = builder.object_sha256(gap["source_bindings"])
    gap.pop("report_content_sha256")
    gap["report_content_sha256"] = builder.object_sha256(gap)
    pins[builder.GAP_REL] = _sha(_write_json(root, builder.GAP_REL, gap))
    with pytest.raises(
        builder.BuildError,
        match=(
            "required (file|directory) is missing|r023 source SHA differs|"
            "r023 source byte length differs"
        ),
    ):
        _build(root, pins, ids)


def test_gap_projection_binding_cannot_bypass_raw_sha_and_canonical_bytes(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    source = Path("docs/control/evidence/fp048-source-binding.json")
    source_value = {"source": "fixture"}
    content_sha256 = builder.object_sha256(source_value)
    source_value["record_content_sha256"] = content_sha256
    source_raw = json.dumps(source_value, separators=(",", ":")).encode() + b"\n"
    _write(root, source, source_raw)
    gap = json.loads((root / builder.GAP_REL).read_text())
    gap["source_bindings"][-1] = {
        "name": builder.EXPECTED_R023_CURRENT_SOURCE_BINDING_NAMES[-1],
        "path": source.as_posix(),
        "binding_kind": "CANONICAL_JSON_OBJECT_EXCLUDING_OWN_HASH_FIELD",
        "content_sha256": content_sha256,
        "bytes": 1,
        "sha256": "0" * 64,
    }
    gap["source_binding_sha256"] = builder.object_sha256(gap["source_bindings"])
    gap.pop("report_content_sha256")
    gap["report_content_sha256"] = builder.object_sha256(gap)
    pins[builder.GAP_REL] = _sha(_write_json(root, builder.GAP_REL, gap))
    with pytest.raises(builder.BuildError, match="r023 source SHA differs"):
        _build(root, pins, ids)


def test_ancestor_symlink_swap_during_snapshot_fails_closed(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    target_dir = root / builder.RTM_REL.parent
    original_dir = target_dir.with_name(target_dir.name + ".original")
    outside = tmp_path / "outside-requirements"
    outside.mkdir()
    (outside / builder.RTM_REL.name).write_bytes((target_dir / builder.RTM_REL.name).read_bytes())
    swapped = False
    real_open = builder.os.open

    def swap(path: object, flags: int, *args: object, **kwargs: object) -> int:
        nonlocal swapped
        is_target = path == target_dir.name or Path(os.fspath(path)) == target_dir / builder.RTM_REL.name
        if not swapped and is_target:
            swapped = True
            target_dir.rename(original_dir)
            target_dir.symlink_to(outside, target_is_directory=True)
        return real_open(path, flags, *args, **kwargs)

    with mock.patch.object(builder.os, "open", side_effect=swap):
        with pytest.raises((builder.BuildError, OSError)):
            _build(root, pins, ids)
    assert swapped


def test_link_collision_never_overwrites_foreign_target(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    original = builder._link_fd_noreplace_at
    collided = False

    def collide(descriptor: int, parent_fd: int, destination: str) -> None:
        nonlocal collided
        if not collided and destination == builder.OUTPUT_PATHS[0].name:
            foreign_descriptor = os.open(
                destination,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                0o600,
                dir_fd=parent_fd,
            )
            try:
                os.write(foreign_descriptor, b"foreign-collision")
                os.fsync(foreign_descriptor)
            finally:
                os.close(foreign_descriptor)
            collided = True
        original(descriptor, parent_fd, destination)

    with mock.patch.object(builder, "_link_fd_noreplace_at", side_effect=collide):
        with pytest.raises(builder.BuildError, match="already exists"):
            _write_successor(root, pins, ids)
    assert collided
    first = root / builder.OUTPUT_PATHS[0]
    assert first.read_bytes() == b"foreign-collision"


def test_real_child_sigkill_after_empty_stage_creation_recovers_forward(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    child = os.fork()
    if child == 0:
        try:
            _write_successor(
                root,
                pins,
                ids,
                stage_created_hook=lambda index, _relative: (
                    os.kill(os.getpid(), signal.SIGKILL) if index == 0 else None
                ),
            )
        finally:
            os._exit(95)
    _pid, status = os.waitpid(child, 0)
    assert os.WIFSIGNALED(status)
    assert os.WTERMSIG(status) == signal.SIGKILL
    relative = builder.OUTPUT_PATHS[0]
    stage = root / builder._transaction_member_relative(relative, "stage")
    sidecar = root / builder._transaction_member_relative(relative, "authority")
    assert (root / builder.TRANSACTION_JOURNAL_REL).exists()
    assert stage.exists() and stage.read_bytes() == b""
    assert not sidecar.exists()
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)
    quarantine = root / builder._transaction_member_relative(relative, "quarantine")
    assert quarantine.exists() and quarantine.read_bytes() == b""


def test_strong_umask_fresh_publish_forces_private_modes(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    previous_umask = os.umask(0o777)
    try:
        assert _write_successor(root, pins, ids) == "PUBLISHED_SUCCESSOR"
    finally:
        os.umask(previous_umask)
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)
    for relative in builder.OUTPUT_PATHS:
        info = (root / relative).stat()
        assert stat.S_IMODE(info.st_mode) == 0o600
        assert info.st_nlink == 1
        for kind in ("stage", "backup", "authority", "quarantine"):
            assert not (root / builder._transaction_member_relative(relative, kind)).exists()
    assert not (root / builder.TRANSACTION_JOURNAL_REL).exists()


def test_sigkill_between_named_stage_open_and_fchmod_recovers_forward(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    child = os.fork()
    if child == 0:
        previous_umask = os.umask(0o777)
        original_fchmod = builder.os.fchmod
        killed = False

        def kill_before_stage_fchmod(descriptor: int, mode: int) -> None:
            nonlocal killed
            info = os.fstat(descriptor)
            if (
                not killed
                and mode == 0o600
                and info.st_nlink == 1
                and info.st_size == 0
            ):
                killed = True
                os.kill(os.getpid(), signal.SIGKILL)
            original_fchmod(descriptor, mode)

        try:
            with mock.patch.object(
                builder.os,
                "fchmod",
                side_effect=kill_before_stage_fchmod,
            ):
                _write_successor(root, pins, ids)
        finally:
            os.umask(previous_umask)
            os._exit(87)
    _pid, status = os.waitpid(child, 0)
    assert os.WIFSIGNALED(status)
    assert os.WTERMSIG(status) == signal.SIGKILL
    relative = builder.OUTPUT_PATHS[0]
    stage = root / builder._transaction_member_relative(relative, "stage")
    sidecar = root / builder._transaction_member_relative(relative, "authority")
    assert (root / builder.TRANSACTION_JOURNAL_REL).exists()
    assert stage.exists() and stage.stat().st_size == 0
    assert stat.S_IMODE(stage.stat().st_mode) == 0
    assert not sidecar.exists()
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)
    quarantine = root / builder._transaction_member_relative(relative, "quarantine")
    assert quarantine.exists() and quarantine.read_bytes() == b""
    assert stat.S_IMODE(quarantine.stat().st_mode) == 0o600


def test_repeated_sigkill_between_stage_creation_and_sidecar_recovers_forward(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    for exit_code in (89, 88):
        child = os.fork()
        if child == 0:
            try:
                _write_successor(
                    root,
                    pins,
                    ids,
                    stage_created_hook=lambda index, _relative: (
                        os.kill(os.getpid(), signal.SIGKILL) if index == 0 else None
                    ),
                )
            finally:
                os._exit(exit_code)
        _pid, status = os.waitpid(child, 0)
        assert os.WIFSIGNALED(status)
        assert os.WTERMSIG(status) == signal.SIGKILL

    relative = builder.OUTPUT_PATHS[0]
    stage = root / builder._transaction_member_relative(relative, "stage")
    sidecar = root / builder._transaction_member_relative(relative, "authority")
    primary = root / builder._transaction_member_relative(relative, "quarantine")
    retry = primary.with_name(f"{primary.name}.retry-000001")
    assert stage.exists() and stage.read_bytes() == b""
    assert primary.exists() and primary.read_bytes() == b""
    assert not sidecar.exists()
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)
    assert primary.exists() and primary.read_bytes() == b""
    assert retry.exists() and retry.read_bytes() == b""


def test_real_child_sigkill_before_anonymous_journal_write_leaves_no_partial_name(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    child = os.fork()
    if child == 0:
        original = builder.os.write

        def kill_before_anonymous_write(descriptor: int, raw: bytes) -> int:
            if os.fstat(descriptor).st_nlink == 0:
                os.kill(os.getpid(), signal.SIGKILL)
            return original(descriptor, raw)

        try:
            with mock.patch.object(
                builder.os,
                "write",
                side_effect=kill_before_anonymous_write,
            ):
                _write_successor(root, pins, ids)
        finally:
            os._exit(91)
    _pid, status = os.waitpid(child, 0)
    assert os.WIFSIGNALED(status)
    assert os.WTERMSIG(status) == signal.SIGKILL
    assert not (root / builder.TRANSACTION_JOURNAL_REL).exists()
    for output in builder.OUTPUT_PATHS:
        assert _sha((root / output).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[output]
    assert _write_successor(root, pins, ids) == "PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_sigkill_after_read_only_journal_capability_link_recovers(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    capability_name = builder._anonymous_capability_name(
        builder.TRANSACTION_JOURNAL_REL.name
    )
    capability = root / capability_name
    child = os.fork()
    if child == 0:
        original = builder._link_fd_at_raw
        killed = False

        def capability_link_then_kill(
            descriptor: int,
            parent_fd: int,
            destination: str,
        ) -> None:
            nonlocal killed
            original(descriptor, parent_fd, destination)
            if not killed and destination == capability_name:
                killed = True
                os.kill(os.getpid(), signal.SIGKILL)

        try:
            with mock.patch.object(
                builder,
                "_link_fd_at_raw",
                side_effect=capability_link_then_kill,
            ):
                _write_successor(root, pins, ids)
        finally:
            os._exit(89)
    _pid, status = os.waitpid(child, 0)
    assert os.WIFSIGNALED(status)
    assert os.WTERMSIG(status) == signal.SIGKILL
    assert not (root / builder.TRANSACTION_JOURNAL_REL).exists()
    assert capability.exists()
    assert stat.S_IMODE(capability.stat().st_mode) == 0o400
    assert capability.stat().st_nlink == 1
    assert _write_successor(root, pins, ids) == "PUBLISHED_SUCCESSOR"
    assert not capability.exists()
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_real_child_sigkill_before_anonymous_sidecar_write_recovers_forward(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    child = os.fork()
    if child == 0:
        original = builder.os.write
        anonymous_writes = 0

        def kill_before_second_anonymous_write(descriptor: int, raw: bytes) -> int:
            nonlocal anonymous_writes
            if os.fstat(descriptor).st_nlink == 0:
                anonymous_writes += 1
                if anonymous_writes == 2:
                    os.kill(os.getpid(), signal.SIGKILL)
            return original(descriptor, raw)

        try:
            with mock.patch.object(
                builder.os,
                "write",
                side_effect=kill_before_second_anonymous_write,
            ):
                _write_successor(root, pins, ids)
        finally:
            os._exit(90)
    _pid, status = os.waitpid(child, 0)
    assert os.WIFSIGNALED(status)
    assert os.WTERMSIG(status) == signal.SIGKILL
    relative = builder.OUTPUT_PATHS[0]
    stage = root / builder._transaction_member_relative(relative, "stage")
    sidecar = root / builder._transaction_member_relative(relative, "authority")
    assert (root / builder.TRANSACTION_JOURNAL_REL).exists()
    assert stage.exists() and stage.read_bytes() == b""
    assert not sidecar.exists()
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_real_child_sigkill_after_private_backup_link_before_unlink_recovers(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.OUTPUT_PATHS[0]
    backup = root / builder._transaction_member_relative(relative, "backup")
    child = os.fork()
    if child == 0:
        original = builder._link_fd_noreplace_at
        killed = False

        def link_then_kill(descriptor: int, parent_fd: int, destination: str) -> None:
            nonlocal killed
            original(descriptor, parent_fd, destination)
            if not killed and destination == backup.name:
                killed = True
                os.kill(os.getpid(), signal.SIGKILL)

        try:
            with mock.patch.object(
                builder,
                "_link_fd_noreplace_at",
                side_effect=link_then_kill,
            ):
                _write_successor(root, pins, ids)
        finally:
            os._exit(91)
    _pid, status = os.waitpid(child, 0)
    assert os.WIFSIGNALED(status)
    assert os.WTERMSIG(status) == signal.SIGKILL
    assert backup.exists()
    assert _sha((root / relative).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[relative]
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_stage_inode_swap_during_held_fd_link_is_quarantined_and_recovers(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.OUTPUT_PATHS[0]
    stage = root / builder._transaction_member_relative(relative, "stage")
    quarantine = root / builder._transaction_member_relative(relative, "quarantine")
    original = builder._link_fd_noreplace_at
    foreign = b"foreign-stage-inode-swap"
    swapped = False

    def swap_then_link(descriptor: int, parent_fd: int, destination: str) -> None:
        nonlocal swapped
        if not swapped and destination == relative.name:
            replacement = root / "replacement-stage-race"
            replacement.write_bytes(foreign)
            replacement.chmod(0o600)
            os.replace(replacement, stage)
            swapped = True
        original(descriptor, parent_fd, destination)

    with mock.patch.object(builder, "_link_fd_noreplace_at", side_effect=swap_then_link):
        with pytest.raises((builder.BuildError, OSError)):
            _write_successor(root, pins, ids)
    assert swapped
    assert quarantine.read_bytes() == foreign
    for output in builder.OUTPUT_PATHS:
        assert _sha((root / output).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[output]
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_stage_hidden_rename_during_held_fd_link_rolls_back_and_recovers(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.OUTPUT_PATHS[0]
    stage = root / builder._transaction_member_relative(relative, "stage")
    quarantine = root / builder._transaction_member_relative(relative, "quarantine")
    hidden = stage.with_name(stage.name + ".attacker-hidden")
    original = builder._link_fd_noreplace_at
    foreign = b"foreign-stage-after-hidden-rename"
    moved = False

    def hide_then_link(descriptor: int, parent_fd: int, destination: str) -> None:
        nonlocal moved
        if not moved and destination == relative.name:
            stage.rename(hidden)
            stage.write_bytes(foreign)
            stage.chmod(0o600)
            moved = True
        original(descriptor, parent_fd, destination)

    with mock.patch.object(builder, "_link_fd_noreplace_at", side_effect=hide_then_link):
        with pytest.raises(builder.BuildError, match="named stage changed|hard-linked transaction file"):
            _write_successor(root, pins, ids)
    assert moved
    assert quarantine.read_bytes() == foreign
    assert hidden.exists()
    for output in builder.OUTPUT_PATHS:
        assert _sha((root / output).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[output]
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_same_inode_same_length_restored_mtime_mutation_after_backup_move_is_rejected(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.OUTPUT_PATHS[0]
    target = root / relative
    backup = root / builder._transaction_member_relative(relative, "backup")
    before = target.stat()
    original_raw = target.read_bytes()
    original = builder._read_descriptor
    mutated = False

    def mutate_and_restore(descriptor: int) -> bytes:
        nonlocal mutated
        raw = original(descriptor)
        if (
            not mutated
            and backup.exists()
            and os.fstat(descriptor).st_ino == before.st_ino
        ):
            mutated = True
            changed = bytes([original_raw[0] ^ 1]) + original_raw[1:]
            with backup.open("r+b") as handle:
                handle.write(changed)
                handle.flush()
                os.fsync(handle.fileno())
                handle.seek(0)
                handle.write(original_raw)
                handle.flush()
                os.fsync(handle.fileno())
            os.utime(
                backup,
                ns=(before.st_atime_ns, before.st_mtime_ns),
                follow_symlinks=False,
            )
        return raw

    with mock.patch.object(builder, "_read_descriptor", side_effect=mutate_and_restore):
        with pytest.raises(builder.BuildError, match="predecessor changed after backup move"):
            _write_successor(root, pins, ids)
    assert mutated
    after = target.stat()
    assert target.read_bytes() == original_raw
    assert (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) == (
        before.st_dev,
        before.st_ino,
        before.st_size,
        before.st_mtime_ns,
    )
    for output in builder.OUTPUT_PATHS:
        assert _sha((root / output).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[output]
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_restored_mtime_mutation_after_backup_path_stat_is_rejected(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.OUTPUT_PATHS[0]
    target = root / relative
    backup = root / builder._transaction_member_relative(relative, "backup")
    original_raw = target.read_bytes()
    real_stat = builder.os.stat
    mutated = False

    def stat_then_mutate(path: object, *args: object, **kwargs: object) -> os.stat_result:
        nonlocal mutated
        info = real_stat(path, *args, **kwargs)
        if (
            not mutated
            and path == backup.name
            and kwargs.get("dir_fd") is not None
        ):
            mutated = True
            changed = bytes([original_raw[0] ^ 1]) + original_raw[1:]
            descriptor = os.open(backup, os.O_RDWR | os.O_NOFOLLOW)
            try:
                os.pwrite(descriptor, changed, 0)
                os.fsync(descriptor)
                os.pwrite(descriptor, original_raw, 0)
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
            os.utime(
                backup,
                ns=(info.st_atime_ns, info.st_mtime_ns),
                follow_symlinks=False,
            )
        return info

    with mock.patch.object(builder.os, "stat", side_effect=stat_then_mutate):
        with pytest.raises(
            builder.BuildError,
            match=(
                "held predecessor backup changed|predecessor changed after backup move|"
                "anonymous capability target changed"
            ),
        ):
            _write_successor(root, pins, ids)
    assert mutated
    assert target.read_bytes() == original_raw
    for output in builder.OUTPUT_PATHS:
        assert _sha((root / output).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[output]
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_shared_mmap_mutation_during_private_predecessor_backup_is_rejected(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.OUTPUT_PATHS[0]
    target = root / relative
    backup = root / builder._transaction_member_relative(relative, "backup")
    original_raw = (root / relative).read_bytes()
    original = builder._link_fd_noreplace_at
    before = target.stat()
    writable = os.open(target, os.O_RDWR | os.O_NOFOLLOW)
    mapping = mmap.mmap(writable, len(original_raw), access=mmap.ACCESS_WRITE)
    mutated = False

    def link_then_mutate(descriptor: int, parent_fd: int, destination: str) -> None:
        nonlocal mutated
        original(descriptor, parent_fd, destination)
        if not mutated and destination == backup.name:
            changed = bytes([original_raw[0] ^ 1]) + original_raw[1:]
            mapping.seek(0)
            mapping.write(changed)
            mapping.flush()
            mapping.seek(0)
            mapping.write(original_raw)
            mapping.flush()
            os.utime(
                target,
                ns=(before.st_atime_ns, before.st_mtime_ns),
                follow_symlinks=False,
            )
            mutated = True

    try:
        with mock.patch.object(builder, "_link_fd_noreplace_at", side_effect=link_then_mutate):
            with pytest.raises(
                builder.BuildError,
                match="predecessor changed during private backup copy",
            ):
                _write_successor(root, pins, ids)
    finally:
        mapping.close()
        os.close(writable)
    assert mutated
    for output in builder.OUTPUT_PATHS:
        assert _sha((root / output).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[output]
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_shared_mmap_mutation_inside_stage_publication_is_rejected(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.OUTPUT_PATHS[0]
    stage = root / builder._transaction_member_relative(relative, "stage")
    original = builder._link_fd_noreplace_at
    held_resources: list[tuple[mmap.mmap, int]] = []
    mutated = False

    def link_then_mutate(descriptor: int, parent_fd: int, destination: str) -> None:
        nonlocal mutated
        if not mutated and destination == relative.name:
            before = stage.stat()
            original_raw = stage.read_bytes()
            changed = bytes([original_raw[0] ^ 1]) + original_raw[1:]
            writable = os.open(stage, os.O_RDWR | os.O_NOFOLLOW)
            mapping = mmap.mmap(writable, len(original_raw), access=mmap.ACCESS_WRITE)
            held_resources.append((mapping, writable))
            original(descriptor, parent_fd, destination)
            mapping.seek(0)
            mapping.write(changed)
            mapping.flush()
            mapping.seek(0)
            mapping.write(original_raw)
            mapping.flush()
            os.utime(
                stage,
                ns=(before.st_atime_ns, before.st_mtime_ns),
                follow_symlinks=False,
            )
            mutated = True
            return
        original(descriptor, parent_fd, destination)

    try:
        with mock.patch.object(builder, "_link_fd_noreplace_at", side_effect=link_then_mutate):
            with pytest.raises(
                builder.BuildError,
                match="named stage changed during private publication",
            ):
                _write_successor(root, pins, ids)
    finally:
        for mapping, writable in held_resources:
            mapping.close()
            os.close(writable)
    assert mutated
    for output in builder.OUTPUT_PATHS:
        assert _sha((root / output).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[output]
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_damaged_named_stage_after_private_target_link_rolls_back_and_recovers(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.OUTPUT_PATHS[0]
    stage = root / builder._transaction_member_relative(relative, "stage")
    quarantine = root / builder._transaction_member_relative(relative, "quarantine")
    original = builder._link_fd_noreplace_at
    damaged = False

    def link_then_damage(descriptor: int, parent_fd: int, destination: str) -> None:
        nonlocal damaged
        original(descriptor, parent_fd, destination)
        if not damaged and destination == relative.name:
            stage_raw = stage.read_bytes()
            writable = os.open(stage, os.O_RDWR | os.O_NOFOLLOW)
            try:
                os.pwrite(writable, bytes([stage_raw[0] ^ 1]), 0)
                os.fsync(writable)
            finally:
                os.close(writable)
            damaged = True

    with mock.patch.object(builder, "_link_fd_noreplace_at", side_effect=link_then_damage):
        with pytest.raises(builder.BuildError, match="named stage changed"):
            _write_successor(root, pins, ids)
    assert damaged
    assert quarantine.exists()
    for output in builder.OUTPUT_PATHS:
        assert _sha((root / output).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[output]
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_sigkill_after_rollback_sidecar_quarantine_recovers_forward(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.OUTPUT_PATHS[0]
    stage = root / builder._transaction_member_relative(relative, "stage")
    sidecar = builder._transaction_member_relative(relative, "authority")
    expected = _build(root, pins, ids)[relative]
    child = os.fork()
    if child == 0:
        original_link = builder._link_fd_noreplace_at
        original_move = builder._move_to_available_quarantine
        damaged = False

        def link_then_damage(descriptor: int, parent_fd: int, destination: str) -> None:
            nonlocal damaged
            original_link(descriptor, parent_fd, destination)
            if not damaged and destination == relative.name:
                raw = stage.read_bytes()
                writable = os.open(stage, os.O_RDWR | os.O_NOFOLLOW)
                try:
                    os.pwrite(writable, bytes([raw[0] ^ 1]), 0)
                    os.fsync(writable)
                finally:
                    os.close(writable)
                damaged = True

        def move_then_kill(
            authority: builder.RepositorySnapshot,
            row: dict,
            source: Path,
            source_info: os.stat_result,
            label: str,
        ) -> Path:
            quarantine = original_move(authority, row, source, source_info, label)
            if source.name == sidecar.name and label == "obsolete rollback stage authority":
                os.kill(os.getpid(), signal.SIGKILL)
            return quarantine

        try:
            with (
                mock.patch.object(
                    builder,
                    "_link_fd_noreplace_at",
                    side_effect=link_then_damage,
                ),
                mock.patch.object(
                    builder,
                    "_move_to_available_quarantine",
                    side_effect=move_then_kill,
                ),
            ):
                _write_successor(root, pins, ids)
        finally:
            os._exit(90)
    _pid, status = os.waitpid(child, 0)
    assert os.WIFSIGNALED(status)
    assert os.WTERMSIG(status) == signal.SIGKILL
    target = root / relative
    assert target.read_bytes() == expected
    assert target.stat().st_ino == stage.stat().st_ino
    assert target.stat().st_nlink == 2
    assert not (root / sidecar).exists()
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_backup_restore_rejects_replacement_inode_and_restores_held_bytes(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.OUTPUT_PATHS[0]
    stage = root / builder._transaction_member_relative(relative, "stage")
    backup = root / builder._transaction_member_relative(relative, "backup")
    hidden = backup.with_name(backup.name + ".held-hidden")
    quarantine = root / builder._transaction_member_relative(relative, "quarantine")
    predecessor_raw = (root / relative).read_bytes()
    original_link = builder._link_fd_noreplace_at
    restored_held_inode: int | None = None
    replacement_inode: int | None = None
    damaged = False
    target_links = 0

    def link_then_damage(descriptor: int, parent_fd: int, destination: str) -> None:
        nonlocal damaged, restored_held_inode, replacement_inode, target_links
        if destination == relative.name:
            target_links += 1
        if target_links == 2 and destination == relative.name:
            os.rename(
                backup.name,
                hidden.name,
                src_dir_fd=parent_fd,
                dst_dir_fd=parent_fd,
            )
            restored_held_inode = os.stat(
                hidden.name,
                dir_fd=parent_fd,
                follow_symlinks=False,
            ).st_ino
            replacement = os.open(
                backup.name,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                0o600,
                dir_fd=parent_fd,
            )
            try:
                os.write(replacement, predecessor_raw)
                os.fsync(replacement)
                replacement_inode = os.fstat(replacement).st_ino
            finally:
                os.close(replacement)
        original_link(descriptor, parent_fd, destination)
        if not damaged and target_links == 1 and destination == relative.name:
            raw = stage.read_bytes()
            writable = os.open(stage, os.O_RDWR | os.O_NOFOLLOW)
            try:
                os.pwrite(writable, bytes([raw[0] ^ 1]), 0)
                os.fsync(writable)
            finally:
                os.close(writable)
            damaged = True

    with mock.patch.object(
        builder,
        "_link_fd_noreplace_at",
        side_effect=link_then_damage,
    ):
        with pytest.raises(builder.BuildError, match="named stage changed"):
            _write_successor(root, pins, ids)
    assert damaged and restored_held_inode is not None and replacement_inode is not None
    assert (root / relative).stat().st_ino != restored_held_inode
    assert (root / relative).stat().st_ino != replacement_inode
    quarantines = list(quarantine.parent.glob(quarantine.name + "*"))
    assert quarantine.exists()
    assert replacement_inode in {path.stat().st_ino for path in quarantines}
    for output in builder.OUTPUT_PATHS:
        assert _sha((root / output).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[output]
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_retained_unlink_rejects_basename_swap_without_deleting_foreign_inode(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.OUTPUT_PATHS[0]
    backup = builder._transaction_member_relative(relative, "backup")
    hidden = root / backup.with_name(backup.name + ".held-before-unlink")
    capture = root / backup.with_name(builder._unlink_capture_name(backup.name))
    foreign = b"foreign-cleanup-backup"
    original_rename = builder._rename_noreplace_at
    swapped = False

    def swap_inside_retained_unlink_capture(
        parent_fd: int,
        source: str,
        destination: str,
    ) -> None:
        nonlocal swapped
        if (
            not swapped
            and source == backup.name
            and destination == capture.name
        ):
            os.rename(
                source,
                hidden.name,
                src_dir_fd=parent_fd,
                dst_dir_fd=parent_fd,
            )
            replacement = os.open(
                source,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                0o600,
                dir_fd=parent_fd,
            )
            try:
                os.write(replacement, foreign)
                os.fsync(replacement)
            finally:
                os.close(replacement)
            swapped = True
        original_rename(parent_fd, source, destination)

    with mock.patch.object(
        builder,
        "_rename_noreplace_at",
        side_effect=swap_inside_retained_unlink_capture,
    ):
        with pytest.raises(builder.BuildError, match="unlink capture authority changed"):
            _write_successor(root, pins, ids)
    assert swapped
    assert capture.read_bytes() == foreign
    assert _sha(hidden.read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[relative]


def test_sigkill_after_unlink_capture_rename_restores_and_recovers(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.OUTPUT_PATHS[0]
    backup = builder._transaction_member_relative(relative, "backup")
    capture = root / backup.with_name(builder._unlink_capture_name(backup.name))
    child = os.fork()
    if child == 0:
        original = builder._rename_noreplace_at
        killed = False

        def capture_then_kill(
            parent_fd: int,
            source: str,
            destination: str,
        ) -> None:
            nonlocal killed
            original(parent_fd, source, destination)
            if (
                not killed
                and source == backup.name
                and destination == capture.name
            ):
                killed = True
                os.kill(os.getpid(), signal.SIGKILL)

        try:
            with mock.patch.object(
                builder,
                "_rename_noreplace_at",
                side_effect=capture_then_kill,
            ):
                _write_successor(root, pins, ids)
        finally:
            os._exit(88)
    _pid, status = os.waitpid(child, 0)
    assert os.WIFSIGNALED(status)
    assert os.WTERMSIG(status) == signal.SIGKILL
    assert capture.exists()
    assert not (root / backup).exists()
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    assert not capture.exists()
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_publication_and_source_snapshot_directory_swap_is_rejected(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    target_dir = root / builder.RTM_REL.parent
    displaced_dir = target_dir.with_name(target_dir.name + ".displaced")
    original_raw = (root / builder.RTM_REL).read_bytes()
    real_prepare = builder._prepare_predecessor_build
    swapped = False

    def swap_before_source_snapshot(*args: object, **kwargs: object):
        nonlocal swapped
        target_dir.rename(displaced_dir)
        target_dir.mkdir(mode=0o700)
        _write(root, builder.RTM_REL, original_raw, 0o664)
        swapped = True
        return real_prepare(*args, **kwargs)

    with mock.patch.object(
        builder,
        "_prepare_predecessor_build",
        side_effect=swap_before_source_snapshot,
    ):
        with pytest.raises(builder.BuildError, match="publication/source directory authority differs"):
            _write_successor(root, pins, ids)
    assert swapped
    assert not (root / builder.TRANSACTION_JOURNAL_REL).exists()


def test_output_parent_swap_after_precommit_is_rejected_before_any_publication(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    target_dir = root / builder.RTM_REL.parent
    displaced_dir = target_dir.with_name(target_dir.name + ".post-precommit-displaced")
    predecessor_raw = (root / builder.RTM_REL).read_bytes()
    swapped = False

    def swap_after_precommit() -> None:
        nonlocal swapped
        target_dir.rename(displaced_dir)
        target_dir.mkdir(mode=0o700)
        _write(root, builder.RTM_REL, predecessor_raw, 0o664)
        swapped = True

    with pytest.raises(builder.BuildError, match="directory path changed"):
        _write_successor(root, pins, ids, before_publish_hook=swap_after_precommit)
    assert swapped
    for output in builder.OUTPUT_PATHS:
        assert _sha((root / output).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[output]


def test_output_parent_swap_inside_backup_link_rolls_back_all_and_recovers(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.RTM_REL
    live_parent = root / relative.parent
    displaced_parent = live_parent.with_name(live_parent.name + ".link-displaced")
    predecessor_raw = (root / relative).read_bytes()
    backup = builder._transaction_member_relative(relative, "backup")
    original = builder._link_fd_noreplace_at
    swapped = False

    def swap_parent_then_link(descriptor: int, parent_fd: int, destination: str) -> None:
        nonlocal swapped
        if not swapped and destination == backup.name:
            live_parent.rename(displaced_parent)
            live_parent.mkdir(mode=0o700)
            _write(root, relative, predecessor_raw, 0o664)
            swapped = True
        original(descriptor, parent_fd, destination)

    with mock.patch.object(builder, "_link_fd_noreplace_at", side_effect=swap_parent_then_link):
        with pytest.raises(builder.BuildError, match="directory path changed"):
            _write_successor(root, pins, ids)
    assert swapped
    for output in builder.OUTPUT_PATHS:
        assert _sha((root / output).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[output]
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_repository_root_swap_preserves_old_transaction_for_explicit_recovery(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    replacement = tmp_path / "replacement-successor"
    displaced = tmp_path / "displaced-predecessor"
    shutil.copytree(root, replacement)
    assert _write_successor(replacement, pins, ids) == "PUBLISHED_SUCCESSOR"
    swapped = False

    def swap_root() -> None:
        nonlocal swapped
        root.rename(displaced)
        replacement.rename(root)
        swapped = True

    with pytest.raises(
        builder.BuildError,
        match="repository root path changed|publication lock FD or root path changed",
    ):
        _write_successor(root, pins, ids, before_publish_hook=swap_root)
    assert swapped
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)
    for output in builder.OUTPUT_PATHS:
        assert _sha((displaced / output).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[output]
        assert (displaced / builder._transaction_member_relative(output, "stage")).exists()
        assert (displaced / builder._transaction_member_relative(output, "authority")).exists()
        assert not (displaced / builder._transaction_member_relative(output, "backup")).exists()
    assert (displaced / builder.TRANSACTION_JOURNAL_REL).exists()
    assert _write_successor(displaced, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(
        displaced,
        expected_input_sha256=pins,
        expected_document_ids=ids,
    )
    assert _write_successor(root, pins, ids) == "ALREADY_CURRENT"


def test_repository_root_swap_inside_cleanup_cannot_return_success(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    replacement = tmp_path / "cleanup-replacement-successor"
    displaced = tmp_path / "cleanup-displaced-successor"
    shutil.copytree(root, replacement)
    assert _write_successor(replacement, pins, ids) == "PUBLISHED_SUCCESSOR"
    original_unlink = builder._unlink_exact_at
    swapped = False

    def swap_root_then_unlink(
        parent_fd: int,
        name: str,
        expected_info: os.stat_result,
        label: str,
    ) -> os.stat_result:
        nonlocal swapped
        if (
            not swapped
            and name == builder.TRANSACTION_JOURNAL_REL.name
        ):
            root.rename(displaced)
            replacement.rename(root)
            swapped = True
        return original_unlink(parent_fd, name, expected_info, label)

    with mock.patch.object(
        builder,
        "_unlink_exact_at",
        side_effect=swap_root_then_unlink,
    ):
        with pytest.raises(
            builder.BuildError,
            match="repository root path changed|publication lock FD or root path changed",
        ):
            _write_successor(root, pins, ids)
    assert swapped
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)
    assert (displaced / builder.TRANSACTION_JOURNAL_REL).exists()
    assert _write_successor(root, pins, ids) == "ALREADY_CURRENT"


def test_real_child_sigkill_midpublish_recovers_forward(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    child = os.fork()
    if child == 0:
        try:
            _write_successor(
                root,
                pins,
                ids,
                publish_hook=lambda index, _relative: (
                    os.kill(os.getpid(), signal.SIGKILL) if index == 1 else None
                ),
            )
        finally:
            os._exit(97)
    _pid, status = os.waitpid(child, 0)
    assert os.WIFSIGNALED(status)
    assert os.WTERMSIG(status) == signal.SIGKILL
    assert (root / builder.TRANSACTION_JOURNAL_REL).exists()
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)
    assert not (root / builder.TRANSACTION_JOURNAL_REL).exists()


def test_real_child_sigkill_after_fd_link_before_stage_unlink_recovers_forward(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    child = os.fork()
    if child == 0:
        original = builder._link_fd_noreplace_at
        killed = False

        def link_then_kill(descriptor: int, parent_fd: int, destination: str) -> None:
            nonlocal killed
            original(descriptor, parent_fd, destination)
            if not killed and destination == builder.OUTPUT_PATHS[0].name:
                killed = True
                os.kill(os.getpid(), signal.SIGKILL)

        try:
            with mock.patch.object(
                builder,
                "_link_fd_noreplace_at",
                side_effect=link_then_kill,
            ):
                _write_successor(root, pins, ids)
        finally:
            os._exit(94)
    _pid, status = os.waitpid(child, 0)
    assert os.WIFSIGNALED(status)
    assert os.WTERMSIG(status) == signal.SIGKILL
    relative = builder.OUTPUT_PATHS[0]
    target = root / relative
    stage = root / builder._transaction_member_relative(relative, "stage")
    capability = target.with_name(builder._anonymous_capability_name(target.name))
    target_info = target.stat()
    stage_info = stage.stat()
    capability_info = capability.stat()
    assert target_info.st_nlink == capability_info.st_nlink == 2
    assert stat.S_IMODE(target_info.st_mode) == 0o400
    assert (target_info.st_dev, target_info.st_ino) == (
        capability_info.st_dev,
        capability_info.st_ino,
    )
    assert stage_info.st_nlink == 1
    assert target_info.st_ino != stage_info.st_ino
    assert target.read_bytes() == stage.read_bytes()
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)
    assert not (root / builder.TRANSACTION_JOURNAL_REL).exists()


def test_link_window_sigkill_recovers_with_preserved_empty_stage_quarantine(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    first_child = os.fork()
    if first_child == 0:
        try:
            _write_successor(
                root,
                pins,
                ids,
                stage_created_hook=lambda index, _relative: (
                    os.kill(os.getpid(), signal.SIGKILL) if index == 0 else None
                ),
            )
        finally:
            os._exit(93)
    _pid, first_status = os.waitpid(first_child, 0)
    assert os.WIFSIGNALED(first_status)
    assert os.WTERMSIG(first_status) == signal.SIGKILL

    second_child = os.fork()
    if second_child == 0:
        original = builder._link_fd_noreplace_at
        killed = False

        def link_then_kill(descriptor: int, parent_fd: int, destination: str) -> None:
            nonlocal killed
            original(descriptor, parent_fd, destination)
            if not killed and destination == builder.OUTPUT_PATHS[0].name:
                killed = True
                os.kill(os.getpid(), signal.SIGKILL)

        try:
            with mock.patch.object(
                builder,
                "_link_fd_noreplace_at",
                side_effect=link_then_kill,
            ):
                _write_successor(root, pins, ids)
        finally:
            os._exit(92)
    _pid, second_status = os.waitpid(second_child, 0)
    assert os.WIFSIGNALED(second_status)
    assert os.WTERMSIG(second_status) == signal.SIGKILL

    relative = builder.OUTPUT_PATHS[0]
    quarantine = root / builder._transaction_member_relative(relative, "quarantine")
    target = root / relative
    stage = root / builder._transaction_member_relative(relative, "stage")
    capability = target.with_name(builder._anonymous_capability_name(target.name))
    assert quarantine.exists() and quarantine.read_bytes() == b""
    assert target.stat().st_ino != stage.stat().st_ino
    assert target.stat().st_nlink == capability.stat().st_nlink == 2
    assert stat.S_IMODE(target.stat().st_mode) == 0o400
    assert stage.stat().st_nlink == 1
    assert target.read_bytes() == stage.read_bytes()
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)
    assert quarantine.exists() and quarantine.read_bytes() == b""


def test_foreign_stage_collision_is_not_removed_or_overwritten(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder._transaction_member_relative(builder.OUTPUT_PATHS[0], "stage")
    collision = root / relative
    collision.write_bytes(b"foreign-stage")
    collision.chmod(0o600)
    with pytest.raises(builder.BuildError, match="transaction member collision"):
        _write_successor(root, pins, ids)
    assert collision.read_bytes() == b"foreign-stage"


def test_source_mutation_during_held_fd_read_is_rejected(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    source = root / "apps/android/app/src/main/java/fp048/Fixture.kt"
    expected_inode = source.stat().st_ino
    real_pread = builder.os.pread
    mutated = False

    def mutate_during_read(descriptor: int, length: int, offset: int) -> bytes:
        nonlocal mutated
        raw = real_pread(descriptor, length, offset)
        if not mutated and os.fstat(descriptor).st_ino == expected_inode:
            mutated = True
            with source.open("ab") as handle:
                handle.write(b"// concurrent mutation\n")
                handle.flush()
                os.fsync(handle.fileno())
        return raw

    with mock.patch.object(builder.os, "pread", side_effect=mutate_during_read):
        with pytest.raises(builder.BuildError, match="changed while reading"):
            _build(root, pins, ids)
    assert mutated


def test_source_cas_drift_before_commit_leaves_all_predecessors(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    source = root / "apps/android/app/src/main/java/fp048/Fixture.kt"

    def drift() -> None:
        source.write_bytes(source.read_bytes() + b"// drift before commit\n")

    with pytest.raises(builder.BuildError, match="held source changed|held source bytes changed|source path changed"):
        _write_successor(root, pins, ids, before_commit_hook=drift)
    for relative in builder.OUTPUT_PATHS:
        assert _sha((root / relative).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[relative]
    assert (root / builder.TRANSACTION_JOURNAL_REL).exists()


def test_real_child_sigkill_during_stage_write_resumes_exact_prefix(tmp_path: Path) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    child = os.fork()
    if child == 0:
        try:
            _write_successor(
                root,
                pins,
                ids,
                stage_write_hook=lambda index, _relative, written, total: (
                    os.kill(os.getpid(), signal.SIGKILL)
                    if index == 0 and 0 < written < total
                    else None
                ),
            )
        finally:
            os._exit(96)
    _pid, status = os.waitpid(child, 0)
    assert os.WIFSIGNALED(status)
    assert os.WTERMSIG(status) == signal.SIGKILL
    stage = root / builder._transaction_member_relative(builder.OUTPUT_PATHS[0], "stage")
    expected = _build(root, pins, ids)[builder.OUTPUT_PATHS[0]]
    staged = stage.read_bytes()
    assert 0 < len(staged) < len(expected)
    assert staged == expected[: len(staged)]
    assert (root / builder.TRANSACTION_JOURNAL_REL).exists()
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


def test_restored_stage_mutation_before_publish_is_rejected_by_full_identity(
    tmp_path: Path,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.OUTPUT_PATHS[0]
    stage = root / builder._transaction_member_relative(relative, "stage")
    mutated = False

    def mutate_and_restore_stage() -> None:
        nonlocal mutated
        before = stage.stat()
        original_raw = stage.read_bytes()
        changed = bytes([original_raw[0] ^ 1]) + original_raw[1:]
        with stage.open("r+b") as handle:
            handle.write(changed)
            handle.flush()
            os.fsync(handle.fileno())
            handle.seek(0)
            handle.write(original_raw)
            handle.flush()
            os.fsync(handle.fileno())
        os.utime(
            stage,
            ns=(before.st_atime_ns, before.st_mtime_ns),
            follow_symlinks=False,
        )
        mutated = True

    with pytest.raises(builder.BuildError, match="staged successor changed after publication baseline"):
        _write_successor(root, pins, ids, before_publish_hook=mutate_and_restore_stage)
    assert mutated
    for output in builder.OUTPUT_PATHS:
        assert _sha((root / output).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[output]
    assert _write_successor(root, pins, ids) == "RECOVERED_AND_PUBLISHED_SUCCESSOR"
    builder.check_successor(root, expected_input_sha256=pins, expected_document_ids=ids)


@pytest.mark.parametrize("mutation", ["mode", "hardlink", "bytes", "identity"])
def test_staged_successor_mutation_before_publish_is_rejected(
    tmp_path: Path,
    mutation: str,
) -> None:
    root, pins, ids, _aliases = _fixture(tmp_path)
    relative = builder.OUTPUT_PATHS[0]
    stage = root / builder._transaction_member_relative(relative, "stage")

    def mutate_stage() -> None:
        if mutation == "mode":
            stage.chmod(0o640)
        elif mutation == "hardlink":
            os.link(stage, root / "stage-hardlink-alias")
        elif mutation == "bytes":
            with stage.open("r+b") as handle:
                handle.seek(0)
                handle.write(b"X")
                handle.flush()
                os.fsync(handle.fileno())
        else:
            replacement = root / "replacement-stage"
            replacement.write_bytes(stage.read_bytes())
            replacement.chmod(0o600)
            os.replace(replacement, stage)

    with pytest.raises(builder.BuildError, match="transaction mode differs|hard-linked transaction|staged successor differs|staged successor identity differs"):
        _write_successor(root, pins, ids, before_publish_hook=mutate_stage)
    for output in builder.OUTPUT_PATHS:
        assert _sha((root / output).read_bytes()) == builder.PINNED_PREDECESSOR_SHA256[output]
