from __future__ import annotations

import copy
import json
import os
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import walksafe_fp008_builder_test_support as support
from scripts import build_walksafe_fp008_admin_review_delivery_trace_20260803 as builder


def test_seq48_authority_binds_initial_and_resume_exact9_repository_state() -> None:
    authority = builder.validate_authority(builder.ROOT)
    assert authority["initial_gate_binding"]["repository_state_path"].endswith("/09-REPOSITORY_STATE.log")
    assert authority["initial_gate_binding"]["repository_state_sha256"] == builder.EXPECTED_INITIAL_GATE_REPOSITORY_STATE_SHA256
    assert authority["resume_gate_binding"]["event_sequence"] == 48
    assert authority["resume_gate_binding"]["event_id"] == builder.EXPECTED_RESUME_EVENT_ID
    workflow_lane = builder.LANE_BY_ID["BACKEND_ADMIN_REVIEW_DELIVERY_POSTGRES"]
    assert workflow_lane.expected_command.endswith(
        " -m pytest -p no:cacheprovider -q backend/tests/test_admin_report_workflow.py "
        "backend/tests/test_fp008_postgres_integration.py::"
        "test_fp008_postgres_review_delivery_authority_and_append_only_history"
    )
    assert workflow_lane.expected_metrics == {
        "result_format": "PYTEST_TERMINAL_SUMMARY_V1",
        "working_directory": ".",
        "passed": 70,
        "failed": 0,
        "errors": 0,
        "skipped": 0,
    }


def test_pre_review_build_is_deterministic_internal_only_and_policy_bound(tmp_path: Path) -> None:
    before = set((builder.ROOT / builder.RESULT_DIR_REL).glob("**/*"))
    first = support.build_trace(tmp_path)
    second = support.build_trace(tmp_path)
    assert first == second
    assert tuple(first) == tuple(lane.log_rel for lane in builder.LANES) + tuple(lane.receipt_rel for lane in builder.LANES) + (
        builder.IMPLEMENTATION_REL,
        builder.VERIFICATION_REL,
        builder.SUCCESSOR_REL,
        builder.REVIEW_SUBJECT_REL,
    )
    implementation = json.loads(first[builder.IMPLEMENTATION_REL])
    subject = json.loads(first[builder.REVIEW_SUBJECT_REL])
    assert implementation["completion_boundary"] == builder.completion_boundary()
    assert implementation["completion_boundary"]["planned_test_ids"] == list(builder.FORMAL_TEST_IDS)
    assert implementation["completion_boundary"]["formal_test_status"] == "NOT_RUN"
    assert subject["policy_contract_binding"]["path"] == builder.POLICY_CONTRACT_REL.as_posix()
    assert subject["policy_contract_binding"]["sha256"] == builder.bytes_sha256(
        (tmp_path / builder.POLICY_CONTRACT_REL).read_bytes()
    )
    assert set((builder.ROOT / builder.RESULT_DIR_REL).glob("**/*")) == before


def test_lane_or_forbidden_source_tamper_fails_closed(tmp_path: Path) -> None:
    observations = support.lane_observations()
    observations[builder.LANES[0].lane_id]["evidence_boundary"]["formal_test_status"] = "PASS"
    source = Path("product/source.txt")
    verification = Path("product/test.txt")
    support.write(source, b"source", tmp_path)
    support.write(verification, b"test", tmp_path)
    with pytest.raises(builder.BuildError, match="evidence boundary"):
        builder.build_pre_review_outputs(
            root=tmp_path,
            lane_observations=observations,
            lane_raw_outputs=support.lane_raw_outputs(),
            implementation_paths=(source.as_posix(),),
            verification_input_paths=(verification.as_posix(),),
            authority=support.authority(),
        )
    observations = support.lane_observations()
    observations[builder.LANES[0].lane_id]["command"] = "python -m pytest -q"
    with pytest.raises(builder.BuildError, match="command differs"):
        builder.build_pre_review_outputs(
            root=tmp_path,
            lane_observations=observations,
            lane_raw_outputs=support.lane_raw_outputs(),
            implementation_paths=(source.as_posix(),),
            verification_input_paths=(verification.as_posix(),),
            authority=support.authority(),
        )

    pytest_lane = builder.LANES[0]
    exact_observation = support.lane_observations()[pytest_lane.lane_id]
    float_metric = copy.deepcopy(exact_observation)
    float_metric["metrics"]["passed"] = float(
        pytest_lane.expected_metrics["passed"]
    )
    with pytest.raises(builder.BuildError, match="metric differs"):
        builder._validate_lane_result(
            pytest_lane,
            float_metric,
            support.lane_raw_outputs()[pytest_lane.lane_id],
        )

    exact_raw = support.lane_raw_outputs()[pytest_lane.lane_id]
    for noncanonical_time in (
        "2026-08-09T12:01:00.123456789+09:00",
        "2026-08-09T12:01:00,123456+09:00",
        "2026-08-09T03:01:00Z",
    ):
        noncanonical = copy.deepcopy(exact_observation)
        noncanonical["started_at"] = noncanonical_time
        with pytest.raises(builder.BuildError, match="canonical ISO-8601"):
            builder._validate_lane_observation(
                pytest_lane,
                noncanonical,
                support.authority()["gate_ended_at"],
                exact_raw,
            )
    long_duration_raw = exact_raw.replace(
        b"34 passed in 1.00s",
        b"34 passed in 68.67s (0:01:08)",
    )
    assert long_duration_raw != exact_raw
    builder._validate_lane_result(
        pytest_lane,
        exact_observation,
        long_duration_raw,
    )
    for malformed_duration in (b"(0:99:08)", b"(0:01:99)"):
        with pytest.raises(builder.BuildError, match="terminal summary differs"):
            builder._validate_lane_result(
                pytest_lane,
                exact_observation,
                long_duration_raw.replace(b"(0:01:08)", malformed_duration),
            )
    with pytest.raises(builder.BuildError, match="command marker differs"):
        builder._validate_lane_result(
            pytest_lane,
            exact_observation,
            exact_raw + b"WALKSAFE_FP008_COMMAND python -m pytest -q\n",
        )
    with pytest.raises(builder.BuildError, match="terminal summary differs"):
        builder._validate_lane_result(
            pytest_lane,
            exact_observation,
            exact_raw + b"1 failed in 0.01s\n",
        )

    android_lane = next(
        lane for lane in builder.LANES if lane.result_kind == "ANDROID_GRADLE"
    )
    with pytest.raises(builder.BuildError, match="Gradle result differs"):
        builder._validate_lane_result(
            android_lane,
            support.lane_observations()[android_lane.lane_id],
            support.lane_raw_outputs()[android_lane.lane_id]
            + b"BUILD FAILED in 1s\n",
        )
    assert builder._path_forbidden("legacy1/admin.py")
    assert builder._path_forbidden("legacy-2/admin.py")
    assert builder._path_forbidden("legacy_3/admin.py")
    assert builder._path_forbidden("roadmap/R034.json")
    assert builder._path_forbidden("roadmap/R-035.json")
    assert builder._path_forbidden("apps/web/admin.ts")
    assert builder._path_forbidden("web/admin.ts")
    assert builder._path_forbidden("docs/submission/old.json")
    assert builder._path_forbidden("results/review-attestation.json")
    assert builder._path_forbidden("results/completion-receipt.json")


def test_add_only_writer_stages_recovers_and_cleans_failed_write(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    first = Path("docs/control/audits/fp008-add-only-a.json")
    second = Path("docs/control/audits/fp008-add-only-b.json")
    outputs = {first: "{\"a\":1}\n", second: "{\"b\":2}\n"}
    staged = builder._add_only_stage_relative(first)
    builder._write_add_only_stage(tmp_path / staged, outputs[first].encode())
    builder.write_or_check_outputs(tmp_path, outputs, write=True)
    builder.write_or_check_outputs(tmp_path, outputs, write=False)
    assert not (tmp_path / staged).exists()

    failed = Path("docs/control/audits/fp008-add-only-failed.json")
    failed_stage = builder._add_only_stage_relative(failed)

    with monkeypatch.context() as patcher:
        def fail_write(_descriptor: int, _raw: bytes) -> int:
            raise OSError("injected write failure")

        patcher.setattr(builder.os, "write", fail_write)
        with pytest.raises(OSError, match="injected write failure"):
            builder.write_or_check_outputs(
                tmp_path, {failed: "failed\n"}, write=True
            )
    assert not (tmp_path / failed).exists()
    assert not (tmp_path / failed_stage).exists()

    # The resolved root inode is captured before open.  Returning an FD for a
    # different safe directory cannot redirect any publication.
    root_race_target = Path("root-race/target.json")
    replacement_root = tmp_path / "replacement-root"
    replacement_root.mkdir()
    resolved_root = os.fspath(tmp_path.resolve())
    real_open = builder.os.open
    wrong_root_opened = False

    def open_wrong_root(
        path: object,
        flags: int,
        mode: int = 0o777,
        *,
        dir_fd: int | None = None,
    ) -> int:
        nonlocal wrong_root_opened
        if (
            not wrong_root_opened
            and dir_fd is None
            and os.fspath(path) == resolved_root
        ):
            wrong_root_opened = True
            return real_open(replacement_root, flags, mode)
        return real_open(path, flags, mode, dir_fd=dir_fd)

    with monkeypatch.context() as patcher:
        patcher.setattr(builder.os, "open", open_wrong_root)
        with pytest.raises(builder.BuildError, match="root changed while opening"):
            builder.write_or_check_outputs(
                tmp_path, {root_race_target: "root-race\n"}, write=True
            )
    assert wrong_root_opened
    assert not (tmp_path / root_race_target).exists()
    assert not (replacement_root / root_race_target).exists()

    # A transient first fstat failure on the anonymous stage leaves no named
    # residue and permits an ordinary retry without intervention.
    fstat_target = Path("fstat/failure.json")
    fstat_raw = "{\"fstat\":true}\n"
    fstat_stage = builder._add_only_stage_relative(fstat_target)
    real_fstat = builder.os.fstat
    fstat_failed = False

    def fail_initial_stage_fstat(descriptor: int) -> os.stat_result:
        nonlocal fstat_failed
        descriptor_path = os.readlink(f"/proc/self/fd/{descriptor}")
        if (
            not fstat_failed
            and "/#" in descriptor_path
            and descriptor_path.endswith(" (deleted)")
        ):
            fstat_failed = True
            raise OSError("injected initial stage fstat failure")
        return real_fstat(descriptor)

    with monkeypatch.context() as patcher:
        patcher.setattr(builder.os, "fstat", fail_initial_stage_fstat)
        with pytest.raises(OSError, match="initial stage fstat failure"):
            builder.write_or_check_outputs(
                tmp_path, {fstat_target: fstat_raw}, write=True
            )
    assert fstat_failed
    assert not (tmp_path / fstat_target).exists()
    assert not (tmp_path / fstat_stage).exists()
    builder.write_or_check_outputs(
        tmp_path, {fstat_target: fstat_raw}, write=True
    )

    # The same residue guarantee holds if the procfd identity probe, rather
    # than fstat, is the first metadata operation to fail.
    procstat_target = Path("procstat/failure.json")
    procstat_raw = "{\"procstat\":true}\n"
    procstat_stage = builder._add_only_stage_relative(procstat_target)
    real_stat = builder.os.stat
    procstat_failed = False

    def fail_initial_procstat(
        path: object, *args: object, **kwargs: object
    ) -> os.stat_result:
        nonlocal procstat_failed
        path_text = os.fspath(path)
        if (
            not procstat_failed
            and isinstance(path_text, str)
            and path_text.startswith("/proc/self/fd/")
            and "/#" in os.readlink(path_text)
        ):
            procstat_failed = True
            raise OSError("injected initial procfd stat failure")
        return real_stat(path, *args, **kwargs)

    with monkeypatch.context() as patcher:
        patcher.setattr(builder.os, "stat", fail_initial_procstat)
        with pytest.raises(OSError, match="initial procfd stat failure"):
            builder.write_or_check_outputs(
                tmp_path, {procstat_target: procstat_raw}, write=True
            )
    assert procstat_failed
    assert not (tmp_path / procstat_target).exists()
    assert not (tmp_path / procstat_stage).exists()
    builder.write_or_check_outputs(
        tmp_path, {procstat_target: procstat_raw}, write=True
    )

    def retry_requires_root_sync_before_final_link(
        relative: Path, content: str
    ) -> None:
        root_identity = os.stat(tmp_path)
        root_synced = False
        final_link_seen = False
        real_retry_fsync = builder.os.fsync
        real_retry_link = builder.os.link

        def track_root_sync(descriptor: int) -> None:
            nonlocal root_synced
            current = os.fstat(descriptor)
            if (current.st_dev, current.st_ino) == (
                root_identity.st_dev,
                root_identity.st_ino,
            ):
                root_synced = True
            real_retry_fsync(descriptor)

        def require_sync_before_link(
            source: str, destination: str, **kwargs: object
        ) -> None:
            nonlocal final_link_seen
            if destination == relative.name:
                assert root_synced
                final_link_seen = True
            real_retry_link(source, destination, **kwargs)

        with monkeypatch.context() as patcher:
            patcher.setattr(builder.os, "fsync", track_root_sync)
            patcher.setattr(builder.os, "link", require_sync_before_link)
            builder.write_or_check_outputs(
                tmp_path, {relative: content}, write=True
            )
        assert root_synced
        assert final_link_seen

    # A failure of the immediate named-stage directory fsync leaves a complete
    # recovery name; retry must re-sync that name before the final target link.
    link_fsync_target = Path("durability/link-fsync.json")
    link_fsync_raw = "{\"link_fsync\":true}\n"
    link_fsync_stage = builder._add_only_stage_relative(link_fsync_target)
    link_fsync_failed = False
    root_identity = os.stat(tmp_path)
    real_fsync = builder.os.fsync

    def fail_named_stage_fsync(descriptor: int) -> None:
        nonlocal link_fsync_failed
        current = os.fstat(descriptor)
        if (
            not link_fsync_failed
            and (current.st_dev, current.st_ino)
            == (root_identity.st_dev, root_identity.st_ino)
            and (tmp_path / link_fsync_stage).exists()
            and not (tmp_path / link_fsync_target).exists()
        ):
            link_fsync_failed = True
            raise OSError("injected named-stage fsync failure")
        real_fsync(descriptor)

    with monkeypatch.context() as patcher:
        patcher.setattr(builder.os, "fsync", fail_named_stage_fsync)
        with pytest.raises(OSError, match="named-stage fsync failure"):
            builder.write_or_check_outputs(
                tmp_path, {link_fsync_target: link_fsync_raw}, write=True
            )
    assert link_fsync_failed
    assert not (tmp_path / link_fsync_target).exists()
    assert (tmp_path / link_fsync_stage).read_text() == link_fsync_raw
    retry_requires_root_sync_before_final_link(
        link_fsync_target, link_fsync_raw
    )

    # Failure of post-link stage validation happens only after the stage parent
    # has been fsynced; its retry still re-syncs before final publication.
    postlink_target = Path("durability/postlink-fstat.json")
    postlink_raw = "{\"postlink\":true}\n"
    postlink_stage = builder._add_only_stage_relative(postlink_target)
    anonymous_fstat_count = 0
    postlink_failed = False
    real_fstat = builder.os.fstat

    def fail_postlink_fstat(descriptor: int) -> os.stat_result:
        nonlocal anonymous_fstat_count, postlink_failed
        descriptor_path = os.readlink(f"/proc/self/fd/{descriptor}")
        if "/#" in descriptor_path and descriptor_path.endswith(" (deleted)"):
            anonymous_fstat_count += 1
            if anonymous_fstat_count == 4:
                postlink_failed = True
                raise OSError("injected post-link fstat failure")
        return real_fstat(descriptor)

    with monkeypatch.context() as patcher:
        patcher.setattr(builder.os, "fstat", fail_postlink_fstat)
        with pytest.raises(OSError, match="post-link fstat failure"):
            builder.write_or_check_outputs(
                tmp_path, {postlink_target: postlink_raw}, write=True
            )
    assert postlink_failed
    assert not (tmp_path / postlink_target).exists()
    assert (tmp_path / postlink_stage).read_text() == postlink_raw
    retry_requires_root_sync_before_final_link(postlink_target, postlink_raw)

    # If the hard link exists but its directory fsync fails, retry must fsync
    # that target directory before discarding the already-durable root stage.
    durable = Path("durable/target/fsync-recovery.json")
    durable_raw = "{\"durable\":true}\n"
    durable_parent = tmp_path / durable.parent
    durable_parent.mkdir(parents=True)
    durable_stage = builder._add_only_stage_relative(durable)
    builder._write_add_only_stage(
        tmp_path / durable_stage, durable_raw.encode()
    )
    parent_identity = os.stat(durable_parent)
    real_fsync = builder.os.fsync
    fsync_failed = False

    def fail_first_commit_fsync(descriptor: int) -> None:
        nonlocal fsync_failed
        current = os.fstat(descriptor)
        if (
            not fsync_failed
            and (current.st_dev, current.st_ino)
            == (parent_identity.st_dev, parent_identity.st_ino)
            and (tmp_path / durable).exists()
            and (tmp_path / durable_stage).exists()
        ):
            fsync_failed = True
            raise OSError("injected target-directory fsync failure")
        real_fsync(descriptor)

    with monkeypatch.context() as patcher:
        patcher.setattr(builder.os, "fsync", fail_first_commit_fsync)
        with pytest.raises(OSError, match="target-directory fsync failure"):
            builder.write_or_check_outputs(
                tmp_path, {durable: durable_raw}, write=True
            )
    assert fsync_failed
    assert (tmp_path / durable).stat().st_ino == (tmp_path / durable_stage).stat().st_ino
    builder.write_or_check_outputs(tmp_path, {durable: durable_raw}, write=True)
    assert not (tmp_path / durable_stage).exists()

    # Replacing a checked destination parent cannot redirect publication: the
    # link uses the already-open safe directory descriptor and the ancestry
    # change is detected before stage cleanup.
    parent_race = Path("race/parent/parent-race.json")
    parent_raw = "{\"parent\":true}\n"
    parent_stage = builder._add_only_stage_relative(parent_race)
    held_parent = tmp_path / "held-parent"
    outside_parent = tmp_path / "outside-parent"
    outside_parent.mkdir()
    real_link = builder.os.link
    parent_swapped = False

    def swap_parent_before_link(source: str, destination: str, **kwargs: object) -> None:
        nonlocal parent_swapped
        if destination == parent_race.name:
            live_parent = tmp_path / parent_race.parent
            live_parent.rename(held_parent)
            live_parent.symlink_to(outside_parent, target_is_directory=True)
            parent_swapped = True
        real_link(source, destination, **kwargs)

    with monkeypatch.context() as patcher:
        patcher.setattr(builder.os, "link", swap_parent_before_link)
        with pytest.raises(builder.BuildError, match="parent changed"):
            builder.write_or_check_outputs(
                tmp_path, {parent_race: parent_raw}, write=True
            )
    assert parent_swapped
    assert not (outside_parent / parent_race.name).exists()
    assert not (held_parent / parent_race.name).exists()
    assert (tmp_path / parent_stage).read_text() == parent_raw
    (tmp_path / parent_race.parent).unlink()
    held_parent.rename(tmp_path / parent_race.parent)
    builder.write_or_check_outputs(tmp_path, {parent_race: parent_raw}, write=True)
    assert not (tmp_path / parent_stage).exists()

    # If the parent is displaced only while the durable root stage is being
    # removed, recovery authority is relinked and fsynced first; the target in
    # the moved directory is then rolled back through its held parent FD.
    late_parent_race = Path("race/late-parent/late-parent-race.json")
    late_parent_raw = "{\"late_parent\":true}\n"
    late_parent_stage = builder._add_only_stage_relative(late_parent_race)
    held_late_parent = tmp_path / "held-late-parent"
    late_parent_moved = False
    real_unlink = builder.os.unlink

    def move_parent_during_stage_cleanup(
        name: str, *, dir_fd: int | None = None
    ) -> None:
        nonlocal late_parent_moved
        if name == late_parent_stage.name and not late_parent_moved:
            (tmp_path / late_parent_race.parent).rename(held_late_parent)
            late_parent_moved = True
        real_unlink(name, dir_fd=dir_fd)

    with monkeypatch.context() as patcher:
        patcher.setattr(builder.os, "unlink", move_parent_during_stage_cleanup)
        with pytest.raises(builder.BuildError, match="parent changed"):
            builder.write_or_check_outputs(
                tmp_path, {late_parent_race: late_parent_raw}, write=True
            )
    assert late_parent_moved
    assert not (held_late_parent / late_parent_race.name).exists()
    assert (tmp_path / late_parent_stage).read_text() == late_parent_raw
    assert (tmp_path / late_parent_stage).stat().st_nlink == 1
    held_late_parent.rename(tmp_path / late_parent_race.parent)
    builder.write_or_check_outputs(
        tmp_path, {late_parent_race: late_parent_raw}, write=True
    )

    # A swap of the just-restored deterministic stage is detected before the
    # held target is rolled back, so canonical bytes retain two known links and
    # can be restored without data loss.
    restore_swap_race = Path("race/restore-swap/restore-swap-race.json")
    restore_swap_raw = "{\"restore_swap\":true}\n"
    restore_swap_stage = builder._add_only_stage_relative(restore_swap_race)
    restore_swap_path = tmp_path / restore_swap_stage
    saved_restored_stage = tmp_path / "saved-restored-stage"
    held_restore_parent = tmp_path / "held-restore-parent"
    restore_parent_moved = False
    restored_stage_swapped = False
    real_unlink = builder.os.unlink
    real_link = builder.os.link

    def move_restore_parent_during_cleanup(
        name: str, *, dir_fd: int | None = None
    ) -> None:
        nonlocal restore_parent_moved
        if name == restore_swap_stage.name and not restore_parent_moved:
            (tmp_path / restore_swap_race.parent).rename(held_restore_parent)
            restore_parent_moved = True
        real_unlink(name, dir_fd=dir_fd)

    def swap_just_restored_stage(
        source: str, destination: str, **kwargs: object
    ) -> None:
        nonlocal restored_stage_swapped
        real_link(source, destination, **kwargs)
        if (
            destination == restore_swap_stage.name
            and restore_parent_moved
            and not restored_stage_swapped
        ):
            restore_swap_path.rename(saved_restored_stage)
            restore_swap_path.write_bytes(b"X" * len(restore_swap_raw.encode()))
            restore_swap_path.chmod(0o600)
            restored_stage_swapped = True

    with monkeypatch.context() as patcher:
        patcher.setattr(builder.os, "unlink", move_restore_parent_during_cleanup)
        patcher.setattr(builder.os, "link", swap_just_restored_stage)
        with pytest.raises(builder.BuildError, match="existing output differs"):
            builder.write_or_check_outputs(
                tmp_path, {restore_swap_race: restore_swap_raw}, write=True
            )
    assert restore_parent_moved and restored_stage_swapped
    assert (held_restore_parent / restore_swap_race.name).read_text() == restore_swap_raw
    assert saved_restored_stage.read_text() == restore_swap_raw
    assert (
        (held_restore_parent / restore_swap_race.name).stat().st_ino
        == saved_restored_stage.stat().st_ino
    )
    assert restore_swap_path.read_bytes() != restore_swap_raw.encode()
    restore_swap_path.unlink()
    saved_restored_stage.rename(restore_swap_path)
    held_restore_parent.rename(tmp_path / restore_swap_race.parent)
    builder.write_or_check_outputs(
        tmp_path, {restore_swap_race: restore_swap_raw}, write=True
    )

    # If the restored stage is swapped only at rollback entry, the subsequent
    # stage check fails and compensation relinks/fsyncs the held target before
    # propagating that failure.
    rollback_swap_race = Path("race/rollback-swap/rollback-swap-race.json")
    rollback_swap_raw = "{\"rollback_swap\":true}\n"
    rollback_swap_stage = builder._add_only_stage_relative(rollback_swap_race)
    rollback_swap_path = tmp_path / rollback_swap_stage
    saved_rollback_stage = tmp_path / "saved-rollback-stage"
    held_rollback_parent = tmp_path / "held-rollback-parent"
    rollback_parent_moved = False
    rollback_stage_swapped = False
    real_unlink = builder.os.unlink
    real_rollback = builder._rollback_linked_target

    def move_rollback_parent_during_cleanup(
        name: str, *, dir_fd: int | None = None
    ) -> None:
        nonlocal rollback_parent_moved
        if name == rollback_swap_stage.name and not rollback_parent_moved:
            (tmp_path / rollback_swap_race.parent).rename(held_rollback_parent)
            rollback_parent_moved = True
        real_unlink(name, dir_fd=dir_fd)

    def swap_stage_at_rollback_entry(
        parent_descriptor: int,
        target_name: str,
        stage_descriptor: int,
    ) -> None:
        nonlocal rollback_stage_swapped
        if (
            target_name == rollback_swap_race.name
            and rollback_parent_moved
            and not rollback_stage_swapped
        ):
            rollback_swap_path.rename(saved_rollback_stage)
            rollback_swap_path.write_bytes(
                b"Y" * len(rollback_swap_raw.encode())
            )
            rollback_swap_path.chmod(0o600)
            rollback_stage_swapped = True
        real_rollback(parent_descriptor, target_name, stage_descriptor)

    with monkeypatch.context() as patcher:
        patcher.setattr(builder.os, "unlink", move_rollback_parent_during_cleanup)
        patcher.setattr(
            builder, "_rollback_linked_target", swap_stage_at_rollback_entry
        )
        with pytest.raises(builder.BuildError, match="existing output differs"):
            builder.write_or_check_outputs(
                tmp_path, {rollback_swap_race: rollback_swap_raw}, write=True
            )
    assert rollback_parent_moved and rollback_stage_swapped
    held_rollback_target = held_rollback_parent / rollback_swap_race.name
    assert held_rollback_target.read_text() == rollback_swap_raw
    assert saved_rollback_stage.read_text() == rollback_swap_raw
    assert held_rollback_target.stat().st_ino == saved_rollback_stage.stat().st_ino
    assert rollback_swap_path.read_bytes() != rollback_swap_raw.encode()
    rollback_swap_path.unlink()
    saved_rollback_stage.rename(rollback_swap_path)
    held_rollback_parent.rename(tmp_path / rollback_swap_race.parent)
    builder.write_or_check_outputs(
        tmp_path, {rollback_swap_race: rollback_swap_raw}, write=True
    )

    # Replacing the root stage after it has been opened cannot change linked
    # bytes.  The FD-backed source publishes the validated inode, then the
    # changed stage pathname is detected and retained for explicit recovery.
    stage_race = Path("race/stage/stage-race.json")
    stage_raw = "{\"stage\":true}\n"
    stage_relative = builder._add_only_stage_relative(stage_race)
    stage_path = tmp_path / stage_relative
    saved_stage = tmp_path / f"{stage_relative.name}.saved"
    stage_swapped = False

    def swap_stage_before_link(source: str, destination: str, **kwargs: object) -> None:
        nonlocal stage_swapped
        if destination == stage_race.name:
            stage_path.rename(saved_stage)
            stage_path.write_bytes(b"wrong-stage-bytes\n")
            stage_swapped = True
        real_link(source, destination, **kwargs)

    with monkeypatch.context() as patcher:
        patcher.setattr(builder.os, "link", swap_stage_before_link)
        with pytest.raises(builder.BuildError, match="stage changed during commit"):
            builder.write_or_check_outputs(
                tmp_path, {stage_race: stage_raw}, write=True
            )
    assert stage_swapped
    assert not (tmp_path / stage_race).exists()
    assert stage_path.read_bytes() == b"wrong-stage-bytes\n"
    assert saved_stage.read_text() == stage_raw
    stage_path.unlink()
    saved_stage.rename(stage_path)
    builder.write_or_check_outputs(tmp_path, {stage_race: stage_raw}, write=True)
    assert not stage_path.exists()

    # A target basename replaced exactly while the canonical stage is removed
    # cannot be accepted through the still-open old target FD.  Post-cleanup
    # validation reopens the requested basename and leaves foreign bytes alone.
    target_race = Path("race/target/target-race.json")
    target_raw = "{\"target\":true}\n"
    target_stage = builder._add_only_stage_relative(target_race)
    saved_target = tmp_path / "saved-target-entry"
    target_swapped = False
    real_unlink = builder.os.unlink

    def swap_target_during_stage_cleanup(
        name: str, *, dir_fd: int | None = None
    ) -> None:
        nonlocal target_swapped
        if name == target_stage.name and not target_swapped:
            target_path = tmp_path / target_race
            target_path.rename(saved_target)
            target_path.write_bytes(b"foreign-target-bytes\n")
            target_swapped = True
        real_unlink(name, dir_fd=dir_fd)

    with monkeypatch.context() as patcher:
        patcher.setattr(builder.os, "unlink", swap_target_during_stage_cleanup)
        with pytest.raises(builder.BuildError, match="foreign target blocks"):
            builder.write_or_check_outputs(
                tmp_path, {target_race: target_raw}, write=True
            )
    assert target_swapped
    assert (tmp_path / target_race).read_bytes() == b"foreign-target-bytes\n"
    assert saved_target.read_text() == target_raw
    assert (tmp_path / target_stage).read_text() == target_raw
    assert saved_target.stat().st_ino == (tmp_path / target_stage).stat().st_ino
    (tmp_path / target_race).unlink()
    saved_target.unlink()
    builder.write_or_check_outputs(tmp_path, {target_race: target_raw}, write=True)

    # A canonical-stage alias moved elsewhere must not turn into an accepted
    # mutable alias on retry when the canonical stage pathname is absent.
    alias_target = Path("race/alias/alias-target.json")
    alias_raw = "{\"alias\":true}\n"
    alias_path = tmp_path / "unknown-hardlink-alias"
    (tmp_path / alias_target.parent).mkdir(parents=True)
    (tmp_path / alias_target).write_text(alias_raw)
    (tmp_path / alias_target).chmod(0o600)
    os.link(tmp_path / alias_target, alias_path)
    with pytest.raises(builder.BuildError, match="unknown hard-link alias"):
        builder.write_or_check_outputs(
            tmp_path, {alias_target: alias_raw}, write=True
        )
    alias_path.unlink()
    builder.write_or_check_outputs(tmp_path, {alias_target: alias_raw}, write=True)

    # Exact bytes do not authorize a preexisting recovery stage or target with
    # broader permissions; both must be current-UID regular 0600 files.
    mode_stage_target = Path("race/mode/stage-target.json")
    mode_stage_raw = "{\"mode_stage\":true}\n"
    mode_stage = builder._add_only_stage_relative(mode_stage_target)
    (tmp_path / mode_stage).write_text(mode_stage_raw)
    (tmp_path / mode_stage).chmod(0o777)
    with pytest.raises(builder.BuildError, match="file authority differs"):
        builder.write_or_check_outputs(
            tmp_path, {mode_stage_target: mode_stage_raw}, write=True
        )
    assert not (tmp_path / mode_stage_target).exists()
    (tmp_path / mode_stage).chmod(0o600)
    builder.write_or_check_outputs(
        tmp_path, {mode_stage_target: mode_stage_raw}, write=True
    )

    mode_target = Path("race/mode/existing-target.json")
    mode_target_raw = "{\"mode_target\":true}\n"
    (tmp_path / mode_target).write_text(mode_target_raw)
    (tmp_path / mode_target).chmod(0o777)
    with pytest.raises(builder.BuildError, match="file authority differs"):
        builder.write_or_check_outputs(
            tmp_path, {mode_target: mode_target_raw}, write=True
        )
    (tmp_path / mode_target).chmod(0o600)
    builder.write_or_check_outputs(
        tmp_path, {mode_target: mode_target_raw}, write=True
    )

    # O_NONBLOCK ensures a FIFO target is rejected as non-regular without
    # blocking while the repository lock is held.
    fifo_target = Path("race/fifo/existing-target.json")
    fifo_raw = "{\"fifo\":true}\n"
    (tmp_path / fifo_target.parent).mkdir(parents=True)
    os.mkfifo(tmp_path / fifo_target, 0o600)
    with pytest.raises(builder.BuildError, match="file authority differs"):
        builder.write_or_check_outputs(
            tmp_path, {fifo_target: fifo_raw}, write=True
        )
    (tmp_path / fifo_target).unlink()
    builder.write_or_check_outputs(tmp_path, {fifo_target: fifo_raw}, write=True)

    # No requested output may occupy this transaction's deterministic internal
    # stage namespace, including another requested output's exact stage name.
    collision_target = Path("race/collision/value.json")
    collision_stage = builder._add_only_stage_relative(collision_target)
    with pytest.raises(builder.BuildError, match="internal stage namespace"):
        builder.write_or_check_outputs(
            tmp_path,
            {
                collision_target: "value\n",
                collision_stage: "collision\n",
            },
            write=True,
        )
    assert not (tmp_path / collision_target).exists()
    assert not (tmp_path / collision_stage).exists()

    # Opening an exact target before discovering a corrupt recovery stage must
    # close every target/stage/parent descriptor on every failed retry.
    leak_target = Path("race/fd/leak-target.json")
    leak_raw = "{\"fd\":true}\n"
    leak_stage = builder._add_only_stage_relative(leak_target)
    (tmp_path / leak_target.parent).mkdir(parents=True)
    (tmp_path / leak_target).write_text(leak_raw)
    (tmp_path / leak_target).chmod(0o600)
    (tmp_path / leak_stage).write_bytes(b"wrong-recovery-stage\n")
    (tmp_path / leak_stage).chmod(0o600)
    descriptor_count = len(os.listdir("/proc/self/fd"))
    for _ in range(5):
        with pytest.raises(builder.BuildError, match="file authority differs"):
            builder.write_or_check_outputs(
                tmp_path, {leak_target: leak_raw}, write=True
            )
        assert len(os.listdir("/proc/self/fd")) == descriptor_count
    (tmp_path / leak_stage).unlink()
    builder.write_or_check_outputs(tmp_path, {leak_target: leak_raw}, write=True)
