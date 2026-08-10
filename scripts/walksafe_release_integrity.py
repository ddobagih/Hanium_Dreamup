#!/usr/bin/env python3
"""Shared fail-closed integrity primitives for WalkSafe release tooling."""

from __future__ import annotations

from dataclasses import dataclass
import ctypes
import errno
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
from typing import Any, BinaryIO


class ReleaseIntegrityError(RuntimeError):
    pass


def require_isolated_python(context: str) -> None:
    flags = (
        sys.flags.isolated,
        sys.flags.no_site,
        sys.flags.ignore_environment,
        int(getattr(sys.flags, "safe_path", False)),
        sys.flags.dont_write_bytecode,
    )
    if any(value != 1 for value in flags) or "site" in sys.modules:
        raise ReleaseIntegrityError(
            f"{context} requires Python -I -S -B before any release code runs"
        )


def _safe_relative(raw: str, context: str) -> PurePosixPath:
    if (
        not isinstance(raw, str)
        or not raw
        or "\\" in raw
        or any(ord(character) < 0x20 or ord(character) == 0x7F for character in raw)
    ):
        raise ReleaseIntegrityError(f"{context} path is not canonical")
    relative = PurePosixPath(raw)
    if relative.is_absolute() or ".." in relative.parts or "." in relative.parts:
        raise ReleaseIntegrityError(f"{context} path is not canonical")
    return relative


def _write_all(descriptor: int, payload: bytes) -> None:
    view = memoryview(payload)
    while view:
        written = os.write(descriptor, view)
        if written <= 0:
            raise ReleaseIntegrityError("exclusive file write did not make progress")
        view = view[written:]


@dataclass
class FileSnapshot:
    """An unlinked immutable-by-path copy of one regular file."""

    source_path: Path
    display_path: str
    size: int
    sha256: str
    mode: int
    source_identity: tuple[int, int, int, int, int]
    _stream: BinaryIO

    @classmethod
    def capture(
        cls,
        path: Path,
        *,
        context: str,
        display_path: str | None = None,
        nonempty: bool = True,
        max_bytes: int | None = None,
        private_mode: int = 0o400,
    ) -> "FileSnapshot":
        candidate = path.expanduser().absolute()
        flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
        temporary: BinaryIO | None = None
        try:
            descriptor = os.open(candidate, flags)
            with os.fdopen(descriptor, "rb") as source:
                before = os.fstat(source.fileno())
                if not stat.S_ISREG(before.st_mode):
                    raise ReleaseIntegrityError(f"{context} is not a regular file")
                if nonempty and before.st_size <= 0:
                    raise ReleaseIntegrityError(f"{context} is empty")
                if max_bytes is not None and before.st_size > max_bytes:
                    raise ReleaseIntegrityError(f"{context} exceeds its size limit")
                temporary = tempfile.TemporaryFile(prefix="walksafe-release-snapshot-")
                digest = hashlib.sha256()
                copied = 0
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
                    temporary.write(chunk)
                    copied += len(chunk)
                temporary.flush()
                os.fsync(temporary.fileno())
                temporary.seek(0)
                after = os.fstat(source.fileno())
                current = os.stat(candidate, follow_symlinks=False)
        except OSError as exc:
            if temporary is not None:
                temporary.close()
            raise ReleaseIntegrityError(f"{context} cannot be snapshotted") from exc
        identities = {
            (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
            for item in (before, after, current)
        }
        if not stat.S_ISREG(current.st_mode) or len(identities) != 1 or copied != before.st_size:
            temporary.close()
            raise ReleaseIntegrityError(f"{context} changed while being snapshotted")
        os.fchmod(temporary.fileno(), private_mode)
        try:
            readonly_descriptor = os.open(
                f"/proc/self/fd/{temporary.fileno()}",
                os.O_RDONLY | getattr(os, "O_CLOEXEC", 0),
            )
        except OSError as exc:
            temporary.close()
            raise ReleaseIntegrityError(f"{context} immutable snapshot cannot be opened") from exc
        readonly = os.fdopen(readonly_descriptor, "rb")
        temporary.close()
        temporary = readonly
        return cls(
            source_path=candidate,
            display_path=display_path or candidate.name,
            size=copied,
            sha256=digest.hexdigest(),
            mode=stat.S_IMODE(before.st_mode),
            source_identity=(
                before.st_dev,
                before.st_ino,
                before.st_size,
                before.st_mtime_ns,
                before.st_ctime_ns,
            ),
            _stream=temporary,
        )

    @property
    def fd(self) -> int:
        return self._stream.fileno()

    @property
    def proc_path(self) -> str:
        return f"/proc/self/fd/{self.fd}"

    def open_reader(self) -> BinaryIO:
        descriptor = os.dup(self.fd)
        reader = os.fdopen(descriptor, "rb")
        reader.seek(0)
        return reader

    def read_bytes(self, *, max_bytes: int | None = None) -> bytes:
        if max_bytes is not None and self.size > max_bytes:
            raise ReleaseIntegrityError(f"{self.display_path} exceeds its size limit")
        with self.open_reader() as source:
            return source.read()

    def record(self, *, path: str | None = None) -> dict[str, Any]:
        return {
            "path": self.display_path if path is None else path,
            "bytes": self.size,
            "sha256": self.sha256,
        }

    def matches_path(self, path: Path | None = None) -> bool:
        candidate = self.source_path if path is None else path.expanduser().absolute()
        try:
            current = FileSnapshot.capture(
                candidate,
                context=self.display_path,
                display_path=self.display_path,
                nonempty=self.size > 0,
            )
        except ReleaseIntegrityError:
            return False
        try:
            return (current.size, current.sha256, current.mode, current.source_identity) == (
                self.size,
                self.sha256,
                self.mode,
                self.source_identity,
            )
        finally:
            current.close()

    def close(self) -> None:
        self._stream.close()

    def __enter__(self) -> "FileSnapshot":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> None:
    raise ValueError(f"non-finite JSON number: {value}")


def _strict_json_float(value: str) -> float:
    parsed = float(value)
    if not math.isfinite(parsed):
        raise ValueError(f"non-finite JSON number: {value}")
    return parsed


def strict_json_bytes(payload: bytes, *, context: str) -> Any:
    try:
        return json.loads(
            payload.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_json_constant,
            parse_float=_strict_json_float,
        )
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise ReleaseIntegrityError(f"{context} is not unambiguous UTF-8 JSON") from exc


def strict_json_snapshot(snapshot: FileSnapshot, *, context: str) -> Any:
    return strict_json_bytes(snapshot.read_bytes(), context=context)


def exclusive_atomic_publish(path: Path, payload: bytes, *, mode: int = 0o644) -> None:
    output = path.expanduser().absolute()
    parent = output.parent
    try:
        parent_metadata = parent.lstat()
        resolved_parent = parent.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise ReleaseIntegrityError("output parent is unavailable") from exc
    if (
        not stat.S_ISDIR(parent_metadata.st_mode)
        or stat.S_ISLNK(parent_metadata.st_mode)
        or resolved_parent != parent
    ):
        raise ReleaseIntegrityError("output parent must be an existing real directory")
    descriptor = -1
    directory_fd = -1
    published_fd = -1
    link_created = False
    publication_complete = False
    previous_signal_mask: set[signal.Signals] | None = None
    payload_sha256 = hashlib.sha256(payload).hexdigest()

    def descriptor_sha256(open_descriptor: int, size: int) -> str:
        digest = hashlib.sha256()
        offset = 0
        while offset < size:
            chunk = os.pread(open_descriptor, min(1024 * 1024, size - offset), offset)
            if not chunk:
                raise ReleaseIntegrityError("published output ended before its expected size")
            digest.update(chunk)
            offset += len(chunk)
        if os.pread(open_descriptor, 1, size):
            raise ReleaseIntegrityError("published output exceeds its expected size")
        return digest.hexdigest()

    try:
        directory_fd = os.open(
            parent,
            os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0),
        )
        opened_parent = os.fstat(directory_fd)
        if (
            not stat.S_ISDIR(opened_parent.st_mode)
            or (opened_parent.st_dev, opened_parent.st_ino)
            != (parent_metadata.st_dev, parent_metadata.st_ino)
        ):
            raise ReleaseIntegrityError("output parent identity changed before publication")
        try:
            os.stat(output.name, dir_fd=directory_fd, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            raise ReleaseIntegrityError("output path already exists")
        temporary_flag = getattr(os, "O_TMPFILE", 0)
        if not temporary_flag:
            raise ReleaseIntegrityError("anonymous temporary publication is unavailable")
        descriptor = os.open(
            ".",
            os.O_RDWR | temporary_flag | getattr(os, "O_CLOEXEC", 0),
            0o600,
            dir_fd=directory_fd,
        )
        os.fchmod(descriptor, mode)
        _write_all(descriptor, payload)
        os.fsync(descriptor)
        before = os.fstat(descriptor)
        if (
            not stat.S_ISREG(before.st_mode)
            or stat.S_IMODE(before.st_mode) != mode
            or before.st_size != len(payload)
            or before.st_nlink != 0
            or descriptor_sha256(descriptor, before.st_size) != payload_sha256
        ):
            raise ReleaseIntegrityError("temporary output differs from the requested payload")
        if not hasattr(signal, "pthread_sigmask"):
            raise ReleaseIntegrityError("signal masking is required for atomic publication")
        previous_signal_mask = signal.pthread_sigmask(
            signal.SIG_BLOCK,
            {signal.SIGHUP, signal.SIGINT, signal.SIGTERM},
        )
        try:
            os.link(
                f"/proc/self/fd/{descriptor}",
                output.name,
                dst_dir_fd=directory_fd,
                follow_symlinks=True,
            )
        except FileExistsError as exc:
            raise ReleaseIntegrityError("output path was created concurrently") from exc
        link_created = True
        os.fsync(directory_fd)
        current = os.stat(output.name, dir_fd=directory_fd, follow_symlinks=False)
        if (
            not stat.S_ISREG(current.st_mode)
            or (current.st_dev, current.st_ino) != (before.st_dev, before.st_ino)
            or stat.S_IMODE(current.st_mode) != mode
            or current.st_size != len(payload)
            or current.st_nlink != 1
            or descriptor_sha256(descriptor, before.st_size) != payload_sha256
        ):
            raise ReleaseIntegrityError("published output is not the exact written regular file")
        published_fd = os.open(
            output.name,
            os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=directory_fd,
        )
        final_metadata = os.fstat(published_fd)
        final_path_metadata = os.stat(output.name, dir_fd=directory_fd, follow_symlinks=False)
        final_parent_metadata = os.stat(parent, follow_symlinks=False)
        if (
            (final_metadata.st_dev, final_metadata.st_ino) != (before.st_dev, before.st_ino)
            or (final_path_metadata.st_dev, final_path_metadata.st_ino)
            != (before.st_dev, before.st_ino)
            or stat.S_IMODE(final_metadata.st_mode) != mode
            or final_metadata.st_size != len(payload)
            or final_metadata.st_nlink != 1
            or descriptor_sha256(published_fd, final_metadata.st_size) != payload_sha256
            or (final_parent_metadata.st_dev, final_parent_metadata.st_ino)
            != (opened_parent.st_dev, opened_parent.st_ino)
        ):
            raise ReleaseIntegrityError("final published output failed exact verification")
        publication_complete = True
    except ReleaseIntegrityError:
        raise
    except BaseException as exc:
        raise ReleaseIntegrityError("exclusive atomic publication failed") from exc
    finally:
        rollback_error: BaseException | None = None
        if link_created and not publication_complete and directory_fd >= 0:
            try:
                current = os.stat(output.name, dir_fd=directory_fd, follow_symlinks=False)
                if descriptor >= 0 and (current.st_dev, current.st_ino) == (
                    os.fstat(descriptor).st_dev,
                    os.fstat(descriptor).st_ino,
                ):
                    os.unlink(output.name, dir_fd=directory_fd)
                    os.fsync(directory_fd)
            except FileNotFoundError:
                pass
            except BaseException as exc:
                rollback_error = exc
        if published_fd >= 0:
            os.close(published_fd)
        if descriptor >= 0:
            os.close(descriptor)
        if directory_fd >= 0:
            os.close(directory_fd)
        if previous_signal_mask is not None:
            signal.pthread_sigmask(signal.SIG_SETMASK, previous_signal_mask)
        if rollback_error is not None:
            raise ReleaseIntegrityError("exclusive publication rollback failed") from rollback_error


def publish_snapshot(path: Path, snapshot: FileSnapshot, *, mode: int = 0o644) -> None:
    exclusive_atomic_publish(path, snapshot.read_bytes(), mode=mode)
    published = FileSnapshot.capture(path, context=f"published {path.name}", display_path=path.name)
    try:
        if (published.size, published.sha256) != (snapshot.size, snapshot.sha256):
            raise ReleaseIntegrityError("published snapshot differs from its verified input")
    finally:
        published.close()


def exclusive_directory_publish(source: Path, destination: Path) -> None:
    """Atomically publish a prepared directory without replacing any existing name."""
    prepared = source.expanduser().absolute()
    output = destination.expanduser().absolute()
    if prepared.parent != output.parent:
        raise ReleaseIntegrityError("directory publication must stay within one parent")
    parent = output.parent
    if parent.is_symlink() or not parent.is_dir() or parent.resolve() != parent:
        raise ReleaseIntegrityError("directory output parent must be an existing real directory")
    before = os.stat(prepared, follow_symlinks=False)
    if not stat.S_ISDIR(before.st_mode):
        raise ReleaseIntegrityError("prepared publication source is not a directory")
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    if renameat2 is None:
        raise ReleaseIntegrityError("renameat2 no-replace publication is unavailable")
    renameat2.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    renameat2.restype = ctypes.c_int
    result = renameat2(
        -100,
        os.fsencode(prepared),
        -100,
        os.fsencode(output),
        1,
    )
    if result != 0:
        error = ctypes.get_errno()
        if error in {errno.EEXIST, errno.ENOTEMPTY}:
            raise ReleaseIntegrityError("directory output was created concurrently")
        raise ReleaseIntegrityError("directory no-replace publication failed") from OSError(error, os.strerror(error))
    current = os.stat(output, follow_symlinks=False)
    if not stat.S_ISDIR(current.st_mode) or (current.st_dev, current.st_ino) != (
        before.st_dev,
        before.st_ino,
    ):
        raise ReleaseIntegrityError("published directory identity changed")
    directory_fd = os.open(parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)


def ensure_real_subdirectory(root: Path, parts: tuple[str, ...], *, mode: int = 0o755) -> Path:
    boundary = root.expanduser().absolute()
    if boundary.is_symlink() or not boundary.is_dir() or boundary.resolve() != boundary:
        raise ReleaseIntegrityError("subdirectory root must be an existing real directory")
    current = boundary
    for part in parts:
        if not part or part in {".", ".."} or "/" in part or "\\" in part:
            raise ReleaseIntegrityError("subdirectory component is unsafe")
        current = current / part
        try:
            os.mkdir(current, mode)
        except FileExistsError:
            pass
        metadata = os.lstat(current)
        if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode) or current.resolve() != current:
            raise ReleaseIntegrityError("subdirectory path contains a non-directory or symlink")
    return current


@dataclass
class DirectorySnapshot:
    source_root: Path
    root: Path
    files: list[dict[str, Any]]
    sha256: str
    _temporary: tempfile.TemporaryDirectory[str]

    @classmethod
    def capture(cls, root: Path, *, context: str) -> "DirectorySnapshot":
        source_root = root.expanduser().absolute()
        if source_root.is_symlink() or not source_root.is_dir() or source_root.resolve() != source_root:
            raise ReleaseIntegrityError(f"{context} root must be a real directory")
        temporary = tempfile.TemporaryDirectory(prefix="walksafe-release-tree-")
        snapshot_root = Path(temporary.name)
        records: list[dict[str, Any]] = []
        try:
            items = sorted(
                source_root.rglob("*"), key=lambda item: item.relative_to(source_root).as_posix()
            )
            for item in items:
                relative_text = item.relative_to(source_root).as_posix()
                relative = _safe_relative(relative_text, context)
                mode = item.lstat().st_mode
                if stat.S_ISLNK(mode):
                    raise ReleaseIntegrityError(f"{context} contains a symlink: {relative_text}")
                if stat.S_ISDIR(mode):
                    continue
                if not stat.S_ISREG(mode):
                    raise ReleaseIntegrityError(f"{context} contains a non-regular entry: {relative_text}")
                snapshot = FileSnapshot.capture(
                    item,
                    context=f"{context} file {relative_text}",
                    display_path=relative_text,
                )
                try:
                    destination = snapshot_root.joinpath(*relative.parts)
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    publish_snapshot(destination, snapshot, mode=0o400)
                    records.append(snapshot.record(path=relative_text))
                finally:
                    snapshot.close()
        except Exception:
            temporary.cleanup()
            raise
        canonical = "".join(
            f"{record['sha256']} {record['bytes']} {record['path']}\n" for record in records
        ).encode("utf-8")
        return cls(
            source_root=source_root,
            root=snapshot_root,
            files=records,
            sha256=hashlib.sha256(canonical).hexdigest(),
            _temporary=temporary,
        )

    def record(self, *, manifest_name: str | None = None) -> dict[str, Any]:
        result: dict[str, Any] = {
            "algorithm": "walksafe-path-size-sha256-lines.v1",
            "sha256": self.sha256,
            "files": self.files,
        }
        if manifest_name is not None:
            manifest = next(
                (record for record in self.files if record["path"] == manifest_name), None
            )
            if manifest is None:
                raise ReleaseIntegrityError(f"directory snapshot is missing {manifest_name}")
            result["manifest"] = manifest
        return result

    def close(self) -> None:
        self._temporary.cleanup()

    def __enter__(self) -> "DirectorySnapshot":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()


def exact_directory_record(root: Path, *, context: str, manifest_name: str | None = None) -> dict[str, Any]:
    with DirectorySnapshot.capture(root, context=context) as snapshot:
        return snapshot.record(manifest_name=manifest_name)


def root_owned_system_trust(
    path: Path,
    *,
    context: str,
    tree_root: Path | None = None,
) -> dict[str, Any]:
    """Record a root-owned, group/world-non-writable system trust boundary."""
    candidate = path.expanduser().absolute()
    if candidate.resolve() != candidate:
        raise ReleaseIntegrityError(f"{context} path must not contain symlinks")

    def require_trusted(item: Path, *, follow_symlinks: bool = False) -> os.stat_result:
        try:
            metadata = item.stat() if follow_symlinks else item.lstat()
        except OSError as exc:
            raise ReleaseIntegrityError(f"{context} trust path is unavailable: {item}") from exc
        if metadata.st_uid != 0 or (
            not stat.S_ISLNK(metadata.st_mode) and metadata.st_mode & 0o022
        ):
            raise ReleaseIntegrityError(
                f"{context} must be root-owned and group/world non-writable: {item}"
            )
        return metadata

    def require_chain(item: Path) -> None:
        current = item
        while True:
            require_trusted(current)
            if current == current.parent:
                break
            current = current.parent

    require_chain(candidate)

    root = candidate if tree_root is None else tree_root.expanduser().absolute()
    if root.resolve() != root or root not in candidate.parents and root != candidate:
        raise ReleaseIntegrityError(f"{context} runtime root is not canonical")
    rows: list[str] = []
    items = [root]
    if tree_root is not None:
        try:
            items.extend(sorted(root.rglob("*"), key=lambda item: item.relative_to(root).as_posix()))
        except OSError as exc:
            raise ReleaseIntegrityError(f"{context} runtime tree cannot be enumerated") from exc
    for item in items:
        metadata = require_trusted(item)
        relative = "." if item == root else item.relative_to(root).as_posix()
        kind = "symlink" if stat.S_ISLNK(metadata.st_mode) else (
            "dir" if stat.S_ISDIR(metadata.st_mode) else "file" if stat.S_ISREG(metadata.st_mode) else "other"
        )
        if kind == "other":
            raise ReleaseIntegrityError(f"{context} runtime tree contains a non-file entry: {relative}")
        target = ""
        if kind == "symlink":
            try:
                resolved = item.resolve(strict=True)
            except OSError:
                target = f"unresolved:{os.readlink(item)}"
            else:
                require_chain(resolved)
                target = str(resolved)
        rows.append(
            f"{relative}\0{kind}\0{metadata.st_uid}\0{stat.S_IMODE(metadata.st_mode):04o}\0{target}\n"
        )
    return {
        "kind": "root-owned-group-world-non-writable.v1",
        "root": str(root),
        "entries": len(rows),
        "metadata_sha256": hashlib.sha256("".join(rows).encode("utf-8")).hexdigest(),
    }


@dataclass(frozen=True)
class GitSourceIdentity:
    root: Path
    commit: str
    tree: str
    inventory: dict[str, Any]
    git: dict[str, Any]


def _git_environment() -> dict[str, str]:
    return {
        "LC_ALL": "C",
        "LANG": "C",
        "PATH": "/usr/bin:/bin",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_SYSTEM": "/dev/null",
        "GIT_CONFIG_GLOBAL": "/dev/null",
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_NO_REPLACE_OBJECTS": "1",
        "HOME": "/nonexistent",
    }


def _git(git: FileSnapshot, root: Path, *arguments: str) -> bytes:
    try:
        completed = subprocess.run(
            [
                git.proc_path,
                "-c",
                "core.fsmonitor=false",
                "-c",
                "core.untrackedCache=false",
                "-C",
                str(root),
                *arguments,
            ],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=_git_environment(),
            pass_fds=(git.fd,),
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise ReleaseIntegrityError(f"Git command failed: {' '.join(arguments[:2])}") from exc
    return completed.stdout


def _parse_tree(raw: bytes) -> dict[bytes, tuple[bytes, bytes]]:
    result: dict[bytes, tuple[bytes, bytes]] = {}
    for row in raw.split(b"\0"):
        if not row:
            continue
        try:
            metadata, path = row.split(b"\t", 1)
            mode, kind, object_id = metadata.split(b" ", 2)
        except ValueError as exc:
            raise ReleaseIntegrityError("Git HEAD tree inventory is malformed") from exc
        if kind != b"blob" or mode not in {b"100644", b"100755"} or path in result:
            raise ReleaseIntegrityError("Git HEAD tree must contain unique regular files")
        result[path] = (mode, object_id)
    if not result:
        raise ReleaseIntegrityError("Git HEAD tree is empty")
    return result


def _parse_index(raw: bytes) -> dict[bytes, tuple[bytes, bytes]]:
    result: dict[bytes, tuple[bytes, bytes]] = {}
    for row in raw.split(b"\0"):
        if not row:
            continue
        try:
            metadata, path = row.split(b"\t", 1)
            mode, object_id, stage = metadata.split(b" ", 2)
        except ValueError as exc:
            raise ReleaseIntegrityError("Git index inventory is malformed") from exc
        if stage != b"0" or mode not in {b"100644", b"100755"} or path in result:
            raise ReleaseIntegrityError("Git index must contain unique regular stage-0 files")
        result[path] = (mode, object_id)
    return result


def verify_exact_git_source(
    root: Path,
    *,
    context: str,
    expected_commit: str | None = None,
    expected_tree: str | None = None,
    allowed_ignored_roots: tuple[str, ...] = (),
) -> GitSourceIdentity:
    candidate = root.expanduser().absolute()
    if candidate.is_symlink() or not candidate.is_dir() or candidate.resolve() != candidate:
        raise ReleaseIntegrityError(f"{context} root must be a real directory")
    git_path = shutil.which("git", path="/usr/bin:/bin")
    if git_path is None:
        raise ReleaseIntegrityError("trusted Git executable is unavailable")
    git_snapshot = FileSnapshot.capture(
        Path(git_path).resolve(),
        context="Git executable",
        display_path=str(Path(git_path).resolve()),
        private_mode=0o500,
    )
    try:
        git = git_snapshot.source_path
        top = Path(_git(git_snapshot, candidate, "rev-parse", "--show-toplevel").decode("utf-8").strip())
        if top.resolve() != candidate:
            raise ReleaseIntegrityError(f"{context} root is not the Git worktree root")
        commit = _git(git_snapshot, candidate, "rev-parse", "--verify", "HEAD").decode("ascii").strip().lower()
        tree = _git(git_snapshot, candidate, "rev-parse", "--verify", "HEAD^{tree}").decode("ascii").strip().lower()
        object_format = _git(git_snapshot, candidate, "rev-parse", "--show-object-format").decode("ascii").strip()
        if object_format not in {"sha1", "sha256"}:
            raise ReleaseIntegrityError("Git object format is unsupported")
        expected_length = 40 if object_format == "sha1" else 64
        if len(commit) != expected_length or len(tree) != expected_length:
            raise ReleaseIntegrityError("Git source identity is not a full object id")
        if expected_commit is not None and commit != expected_commit:
            raise ReleaseIntegrityError(f"{context} HEAD changed")
        if expected_tree is not None and tree != expected_tree:
            raise ReleaseIntegrityError(f"{context} tree changed")
        head = _parse_tree(_git(git_snapshot, candidate, "ls-tree", "-r", "-z", "--full-tree", "HEAD"))
        index = _parse_index(_git(git_snapshot, candidate, "ls-files", "--stage", "-z"))
        if index != head:
            raise ReleaseIntegrityError(f"{context} index differs from the HEAD tree")
        status = _git(git_snapshot, candidate, "status", "--porcelain=v1", "-z", "--untracked-files=all")
        if status:
            raise ReleaseIntegrityError(
                f"{context} worktree is dirty (tracked or untracked changes)"
            )
        replace_refs = _git(
            git_snapshot,
            candidate,
            "for-each-ref",
            "--format=%(refname)",
            "refs/replace",
        )
        if replace_refs:
            raise ReleaseIntegrityError(f"{context} contains Git replace refs")
        allowed_ignored = tuple(
            _safe_relative(raw, f"{context} allowed ignored root").as_posix().rstrip("/")
            for raw in allowed_ignored_roots
        )
        ignored = tuple(
            sorted(
                row
                for row in _git(
                    git_snapshot,
                    candidate,
                    "ls-files",
                    "--others",
                    "--ignored",
                    "--exclude-standard",
                    "-z",
                ).split(b"\0")
                if row
            )
        )
        for path_bytes in ignored:
            relative_text = os.fsdecode(path_bytes)
            relative = _safe_relative(relative_text, context).as_posix()
            if not any(relative == root or relative.startswith(f"{root}/") for root in allowed_ignored):
                raise ReleaseIntegrityError(
                    f"{context} contains an undeclared ignored path: {relative_text}"
                )

        inventory_digest = hashlib.sha256()
        for path_bytes in sorted(head):
            mode, object_id = head[path_bytes]
            relative_text = os.fsdecode(path_bytes)
            relative = _safe_relative(relative_text, context)
            path = candidate.joinpath(*relative.parts)
            snapshot = FileSnapshot.capture(
                path,
                context=f"{context} tracked file {relative_text}",
                display_path=relative_text,
                nonempty=False,
            )
            try:
                executable_bits = snapshot.mode & 0o111
                if (mode == b"100755" and not snapshot.mode & 0o100) or (
                    mode == b"100644" and executable_bits
                ):
                    raise ReleaseIntegrityError(
                        f"{context} executable mode differs from HEAD: {relative_text}"
                    )
                payload = snapshot.read_bytes()
                git_digest = hashlib.new(object_format)
                git_digest.update(f"blob {len(payload)}\0".encode("ascii"))
                git_digest.update(payload)
                if git_digest.hexdigest().encode("ascii") != object_id:
                    raise ReleaseIntegrityError(f"{context} file bytes differ from HEAD: {relative_text}")
                inventory_digest.update(mode)
                inventory_digest.update(b"\0")
                inventory_digest.update(path_bytes)
                inventory_digest.update(b"\0")
                inventory_digest.update(str(len(payload)).encode("ascii"))
                inventory_digest.update(b"\0")
                inventory_digest.update(hashlib.sha256(payload).digest())
                if not snapshot.matches_path(path):
                    raise ReleaseIntegrityError(
                        f"{context} tracked file changed during verification: {relative_text}"
                    )
            finally:
                snapshot.close()
        final_commit = _git(git_snapshot, candidate, "rev-parse", "--verify", "HEAD").decode("ascii").strip().lower()
        final_tree = _git(git_snapshot, candidate, "rev-parse", "--verify", "HEAD^{tree}").decode("ascii").strip().lower()
        final_head = _parse_tree(
            _git(git_snapshot, candidate, "ls-tree", "-r", "-z", "--full-tree", "HEAD")
        )
        final_index = _parse_index(_git(git_snapshot, candidate, "ls-files", "--stage", "-z"))
        final_status = _git(
            git_snapshot,
            candidate,
            "status",
            "--porcelain=v1",
            "-z",
            "--untracked-files=all",
        )
        final_replace_refs = _git(
            git_snapshot,
            candidate,
            "for-each-ref",
            "--format=%(refname)",
            "refs/replace",
        )
        final_ignored = tuple(
            sorted(
                row
                for row in _git(
                    git_snapshot,
                    candidate,
                    "ls-files",
                    "--others",
                    "--ignored",
                    "--exclude-standard",
                    "-z",
                ).split(b"\0")
                if row
            )
        )
        if (
            final_commit != commit
            or final_tree != tree
            or final_head != head
            or final_index != index
            or final_status
            or final_replace_refs != replace_refs
            or final_ignored != ignored
        ):
            raise ReleaseIntegrityError(f"{context} changed during exact source verification")
        if not git_snapshot.matches_path(git):
            raise ReleaseIntegrityError("Git executable changed during source verification")
        return GitSourceIdentity(
            root=candidate,
            commit=commit,
            tree=tree,
            inventory={
                "algorithm": "sha256",
                "file_count": len(head),
                "sha256": inventory_digest.hexdigest(),
            },
            git=git_snapshot.record(path=str(git)),
        )
    finally:
        git_snapshot.close()
