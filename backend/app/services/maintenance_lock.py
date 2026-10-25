"""Shared maintenance file lock; ordering and exit-time integrity are unchanged."""
from __future__ import annotations

from contextlib import contextmanager
import errno
import fcntl
import os
import stat
from pathlib import Path
from fastapi import HTTPException


class _ReportWriteLockExitError(HTTPException):
    """The acquired write lock failed its exit-time integrity check."""

    def __init__(self) -> None:
        super().__init__(status_code=503, detail={"code": "maintenance_lock_unavailable"})


def _descriptor_acl_is_absent(descriptor: int) -> bool:
    try:
        return not any(
            "acl" in os.fsdecode(name).casefold()
            for name in os.listxattr(descriptor)
        )
    except (AttributeError, OSError):
        return False


def _trusted_maintenance_lock_parent(parent: Path, parent_descriptor: int) -> bool:
    if os.geteuid() == 0 or not parent.is_absolute():
        return False
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | os.O_DIRECTORY
        | os.O_NOFOLLOW
    )
    authority_paths = [Path("/")]
    authority_descriptors: list[int] = []
    try:
        if parent.resolve(strict=True) != parent:
            return False
        authority_descriptors.append(os.open("/", flags))
        for component in parent.parent.relative_to("/").parts:
            authority_descriptors.append(
                os.open(component, flags, dir_fd=authority_descriptors[-1])
            )
            authority_paths.append(authority_paths[-1] / component)
        for index, (authority_path, descriptor) in enumerate(
            zip(authority_paths, authority_descriptors, strict=True)
        ):
            opened_authority = os.fstat(descriptor)
            path_authority = authority_path.stat(follow_symlinks=False)
            if (
                not stat.S_ISDIR(opened_authority.st_mode)
                or opened_authority.st_uid != 0
                or stat.S_IMODE(opened_authority.st_mode) & 0o022
                or os.access(
                    ".",
                    os.W_OK,
                    dir_fd=descriptor,
                    effective_ids=True,
                    follow_symlinks=False,
                )
                or (path_authority.st_dev, path_authority.st_ino)
                != (opened_authority.st_dev, opened_authority.st_ino)
            ):
                return False
            if index:
                anchored_authority = os.stat(
                    authority_path.name,
                    dir_fd=authority_descriptors[index - 1],
                    follow_symlinks=False,
                )
                if (anchored_authority.st_dev, anchored_authority.st_ino) != (
                    opened_authority.st_dev,
                    opened_authority.st_ino,
                ):
                    return False
        anchored_parent = os.stat(
            parent.name,
            dir_fd=authority_descriptors[-1],
            follow_symlinks=False,
        )
        opened_parent = os.fstat(parent_descriptor)
        path_parent = parent.stat(follow_symlinks=False)
        return (
            (path_parent.st_dev, path_parent.st_ino)
            == (opened_parent.st_dev, opened_parent.st_ino)
            == (anchored_parent.st_dev, anchored_parent.st_ino)
        )
    except (OSError, RuntimeError):
        return False
    finally:
        for descriptor in reversed(authority_descriptors):
            os.close(descriptor)


@contextmanager
def _shared_report_write_lock(
    path: Path | None,
    *,
    expected_group_gid: int | None = None,
):
    if path is None:
        yield
        return

    parent = path.parent
    parent_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    try:
        parent_descriptor = os.open(parent, parent_flags)
    except OSError as exc:
        raise HTTPException(
            status_code=503,
            detail={"code": "maintenance_lock_unavailable"},
        ) from exc
    try:
        parent_stat = os.fstat(parent_descriptor)
        path_parent_stat = parent.stat(follow_symlinks=False)
        expected_parent_owner = 0 if expected_group_gid is not None else os.geteuid()
        expected_parent_mode = 0o750 if expected_group_gid is not None else 0o700
        if (
            not stat.S_ISDIR(parent_stat.st_mode)
            or parent_stat.st_uid != expected_parent_owner
            or (
                expected_group_gid is not None
                and parent_stat.st_gid != expected_group_gid
            )
            or stat.S_IMODE(parent_stat.st_mode) != expected_parent_mode
            or not _descriptor_acl_is_absent(parent_descriptor)
            or (
                expected_group_gid is not None
                and os.access(
                    ".",
                    os.W_OK,
                    dir_fd=parent_descriptor,
                    effective_ids=True,
                    follow_symlinks=False,
                )
            )
            or (path_parent_stat.st_dev, path_parent_stat.st_ino)
            != (parent_stat.st_dev, parent_stat.st_ino)
            or not _trusted_maintenance_lock_parent(parent, parent_descriptor)
        ):
            raise HTTPException(
                status_code=503,
                detail={"code": "maintenance_lock_unavailable"},
            )
        parent_state = (
            parent_stat.st_dev,
            parent_stat.st_ino,
            parent_stat.st_mode,
            parent_stat.st_uid,
            parent_stat.st_gid,
            parent_stat.st_nlink,
            parent_stat.st_ctime_ns,
        )
        try:
            flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
            try:
                descriptor = os.open(path.name, flags, dir_fd=parent_descriptor)
            except OSError as exc:
                raise HTTPException(
                    status_code=503,
                    detail={"code": "maintenance_lock_unavailable"},
                ) from exc
            try:
                expected_file_owner = 0 if expected_group_gid is not None else os.geteuid()
                expected_file_mode = 0o440 if expected_group_gid is not None else 0o600

                def verified_file_state() -> tuple[int, ...]:
                    try:
                        current_parent = os.fstat(parent_descriptor)
                        current_path_parent = parent.stat(follow_symlinks=False)
                        current_file = os.fstat(descriptor)
                        current_entry = os.stat(
                            path.name,
                            dir_fd=parent_descriptor,
                            follow_symlinks=False,
                        )
                        current_path_file = path.stat(follow_symlinks=False)
                    except OSError as exc:
                        raise HTTPException(
                            status_code=503,
                            detail={"code": "maintenance_lock_unavailable"},
                        ) from exc
                    current_parent_state = (
                        current_parent.st_dev,
                        current_parent.st_ino,
                        current_parent.st_mode,
                        current_parent.st_uid,
                        current_parent.st_gid,
                        current_parent.st_nlink,
                        current_parent.st_ctime_ns,
                    )
                    current_file_state = (
                        current_file.st_dev,
                        current_file.st_ino,
                        current_file.st_mode,
                        current_file.st_uid,
                        current_file.st_gid,
                        current_file.st_nlink,
                        current_file.st_ctime_ns,
                    )
                    if (
                        current_parent_state != parent_state
                        or (current_path_parent.st_dev, current_path_parent.st_ino)
                        != (current_parent.st_dev, current_parent.st_ino)
                        or not _trusted_maintenance_lock_parent(parent, parent_descriptor)
                        or not _descriptor_acl_is_absent(parent_descriptor)
                        or not _descriptor_acl_is_absent(descriptor)
                        or (
                            expected_group_gid is not None
                            and os.access(
                                ".",
                                os.W_OK,
                                dir_fd=parent_descriptor,
                                effective_ids=True,
                                follow_symlinks=False,
                            )
                        )
                        or not stat.S_ISREG(current_file.st_mode)
                        or current_file.st_uid != expected_file_owner
                        or (
                            expected_group_gid is not None
                            and current_file.st_gid != expected_group_gid
                        )
                        or stat.S_IMODE(current_file.st_mode) != expected_file_mode
                        or current_file.st_nlink != 1
                        or (current_entry.st_dev, current_entry.st_ino)
                        != (current_file.st_dev, current_file.st_ino)
                        or (current_path_file.st_dev, current_path_file.st_ino)
                        != (current_file.st_dev, current_file.st_ino)
                    ):
                        raise HTTPException(
                            status_code=503,
                            detail={"code": "maintenance_lock_unavailable"},
                        )
                    return current_file_state

                file_state = verified_file_state()
                try:
                    fcntl.flock(descriptor, fcntl.LOCK_SH | fcntl.LOCK_NB)
                except OSError as exc:
                    if exc.errno not in {errno.EACCES, errno.EAGAIN}:
                        raise
                    raise HTTPException(
                        status_code=503,
                        detail={
                            "code": "maintenance_in_progress",
                            "message": "report writes are temporarily paused for a consistent backup",
                        },
                        headers={"Retry-After": "5"},
                    ) from exc
                try:
                    if verified_file_state() != file_state:
                        raise HTTPException(
                            status_code=503,
                            detail={"code": "maintenance_lock_unavailable"},
                        )
                    yield
                finally:
                    try:
                        try:
                            exit_state_matches = verified_file_state() == file_state
                        except HTTPException as exc:
                            raise _ReportWriteLockExitError() from exc
                        if not exit_state_matches:
                            raise _ReportWriteLockExitError()
                    finally:
                        fcntl.flock(descriptor, fcntl.LOCK_UN)
            finally:
                os.close(descriptor)
        except HTTPException:
            raise
    finally:
        os.close(parent_descriptor)


shared_maintenance_write_lock = _shared_report_write_lock

