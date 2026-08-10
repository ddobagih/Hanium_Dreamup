#!/usr/bin/env python3
"""Run the signed FP008 seq47b isolated-snapshot corrective reconcile."""

from __future__ import annotations

import sys
import os

_DIRECT_CLI_ENVIRONMENT = {
    "LANG": "C.UTF-8",
    "LC_ALL": "C.UTF-8",
    "PATH": "/usr/bin:/bin",
    "PYTHONDONTWRITEBYTECODE": "1",
    "TZ": "UTC",
}


def _early_executable_identity(
    path: str,
    *,
    follow_symlinks: bool,
) -> tuple[int, ...]:
    metadata = os.stat(path, follow_symlinks=follow_symlinks)
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
        metadata.st_size,
    )

# The write-capable CLI is valid only when Python has already excluded the
# repository and user site from its startup import path.  Imports below this
# guard are therefore reached by production only through the exact isolated
# system-runtime command.
if __name__ == "__main__" and (
    sys.flags.isolated != 1
    or sys.flags.no_site != 1
    or not sys.flags.safe_path
    or sys.executable != "/usr/bin/python3.14"
    or dict(os.environ) != _DIRECT_CLI_ENVIRONMENT
    or _early_executable_identity("/proc/self/exe", follow_symlinks=True)
    != _early_executable_identity(
        "/usr/bin/python3.14",
        follow_symlinks=False,
    )
):
    raise SystemExit(
        "run with the exact clean environment and "
        "/usr/bin/python3.14 -I -S -B script path"
    )

import argparse
from contextlib import contextmanager
import copy
import ctypes
import fcntl
import hashlib
import importlib.abc
import importlib.util
import io
import json
from pathlib import Path
import stat
import subprocess
from types import FunctionType, ModuleType
from typing import Any, Iterator, Sequence
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SYSTEM_PYTHON = Path("/usr/bin/python3.14")
SYSTEM_PYTHON_SHA256 = (
    "b8d8288faefdd300201f43fcf00f6f539a27218eeed3a3dff5ab10b9c4c99700"
)
SYSTEM_PYTHON_BYTE_COUNT = 7_481_192
SYSTEM_CRYPTOGRAPHY_SITE = Path("/usr/lib/python3/dist-packages")
SYSTEM_RUNTIME_FILES = {
    "/usr/lib/python3/dist-packages/_cffi_backend.cpython-314-x86_64-linux-gnu.so": (
        "ab27f4c263ba3de633a90ba97f3a94a996c4152b59853a8c29468636c86fccb3",
        219_192,
    ),
    "/usr/lib/python3/dist-packages/bcrypt/_bcrypt.cpython-314-x86_64-linux-gnu.so": (
        "b9e5f29ceac3be6e6127b97d2ba034b01950776fdf3983cfaac08bd10ad0bfa3",
        513_120,
    ),
    "/usr/lib/python3/dist-packages/cryptography/hazmat/bindings/"
    "_rust.abi3-x86_64-linux-gnu.so": (
        "684274b43072d1862b835d66444840bdeecaaa1e1116bce317b8478294c78354",
        3_899_216,
    ),
    "/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2": (
        "c5e80a563850d6ab5c2f2482e4202d9c1b71fbf44854b8c399e63527202c64e1",
        254_864,
    ),
    "/usr/lib/x86_64-linux-gnu/libc.so.6": (
        "a3947513a02831ec692ebf13053c07614882ab54a2101fb91a1b15724062ed0c",
        2_186_512,
    ),
    "/usr/lib/x86_64-linux-gnu/libcrypto.so.3": (
        "0cd331307536a397ab9c83c6dbeeb3474d0a5114f397ce03d1762adb96d3c781",
        6_386_528,
    ),
    "/usr/lib/x86_64-linux-gnu/libexpat.so.1.11.2": (
        "093e973baf0b0c786d32fd89d1f6904b794af28e489d20198b9fe0729fd39765",
        182_608,
    ),
    "/usr/lib/x86_64-linux-gnu/libffi.so.8.2.0": (
        "1a0dc86f787f73e025a6e521056360afcbe70f2a82cd808132fefc2b4ee95daa",
        64_184,
    ),
    "/usr/lib/x86_64-linux-gnu/libgcc_s.so.1": (
        "9d339ecb409578d6a5d587e6c537a8f9589b8a13fefba30d167433a4b5758bee",
        187_120,
    ),
    "/usr/lib/x86_64-linux-gnu/libm.so.6": (
        "beea4eeacfcfa2cd96011b959a826c97cf4a774017e214f6a34d7eea3d49cd88",
        1_198_376,
    ),
    "/usr/lib/x86_64-linux-gnu/libssl.so.3": (
        "ce215a320bc1b56462c93fdf95975bcfbee071c74629ca9135d4b3b0beace258",
        1_106_088,
    ),
    "/usr/lib/x86_64-linux-gnu/libz.so.1.3.1": (
        "fbf56b0e59287033b6579bbbeae2f9de2fe86ad5bf2bd44d44aad67a15109318",
        121_272,
    ),
    "/usr/lib/x86_64-linux-gnu/libzstd.so.1.5.7": (
        "060eeb79531d435306665fd78329d8a9dd579f95876c5960bb67937825362a80",
        796_824,
    ),
    "/usr/lib/x86_64-linux-gnu/ossl-modules/legacy.so": (
        "d402fc154136d33dad9eef026ce6e6ea8ed5d97ad595a3372a6cbfeb3877eb9d",
        141_464,
    ),
}
_F_ADD_SEALS = 1033
_F_GET_SEALS = 1034
_F_SEAL_SEAL = 0x0001
_F_SEAL_SHRINK = 0x0002
_F_SEAL_GROW = 0x0004
_F_SEAL_WRITE = 0x0008
_MFD_CLOEXEC = 0x0001
_MFD_ALLOW_SEALING = 0x0002
ENGINE_RELATIVE = "scripts/reconcile_walksafe_fp008_session_snapshot_seq47_20260808.py"
ENGINE_PATH = ROOT / ENGINE_RELATIVE
ENGINE_SOURCE_SHA256 = (
    "1d78d0db8760a19b3365346975ef1c4db8d8f0174735d7254b1eee1738a29d01"
)
ENGINE_SOURCE_BYTE_COUNT = 95_897
ENGINE_PRIVATE_MODULE_PREFIX = "_walksafe_fp008_seq47b_reconcile_engine_"
ENGINE_CANONICAL_MODULE_NAME = (
    "scripts.reconcile_walksafe_fp008_session_snapshot_seq47_20260808"
)

# This is the complete repository-local Python import closure used by frozen A.
# Every source is retained from the repository scripts directory, checked by
# size and SHA-256, and then executed only from the retained bytes.
FROZEN_SCRIPT_SOURCES = {
    "scripts/apply_walksafe_fp008_goal_seq45_46_20260803.py": (
        "ac7a9a1344f1bfcbf1a6f60a8f1675e91046a9e6eec4ffa7839c7d50108a7fd8",
        8_913,
    ),
    "scripts/apply_walksafe_fp008_goal_started_seq47_20260803.py": (
        "a36e624c11b044c2035cea5befc011a20c709a9a100eb1a9f3e8fdd4fb0a0a3a",
        71_464,
    ),
    "scripts/apply_walksafe_fp048_goal_completed_seq43_44_20260802.py": (
        "273e8f62472a326b07a4af52da5bce310b188604e6bf944814f6ca8d24d82752",
        72_052,
    ),
    "scripts/check_walksafe_goal_graph_v2_3.py": (
        "27dde08c2f7828fc138b9f502a31d604fde60c3957e33e87882ee9f05dbb87ac",
        633_119,
    ),
    "scripts/check_walksafe_goal_graph_v2_4.py": (
        "da543bb7e5503f0bc82dccd2b1b422ed3584eaffe8e38cff3e504c9712a78ed7",
        368_699,
    ),
    "scripts/check_walksafe_goal_package.py": (
        "0298c9f10edd757e2123632b5657776f1ec503d92d467214bdcde87620481640",
        271_352,
    ),
    "scripts/check_walksafe_project_continuation.py": (
        "c980ea32e08809a299e0b75d6110f81a8996ca4fa523dba7639f8b9136238499",
        265_641,
    ),
    "scripts/check_walksafe_project_continuation_v2_3.py": (
        "1785a97c5fd0cc0182cb1a9f95616328c344aca777f2e86839980afbe7bdab3f",
        382_342,
    ),
    "scripts/check_walksafe_project_continuation_v2_4.py": (
        "09dcc2e7a16ab964838e5fe259f56f0be5a94f52391959a1945692aa101caa4f",
        193_466,
    ),
    "scripts/materialize_walksafe_fp008_goal_seq45_46_20260803.py": (
        "0678de478586d23d04b5ecd5858b58d88c2150e174515b53c748a70f70e9a72f",
        55_568,
    ),
    "scripts/materialize_walksafe_fp048_goal_20260802.py": (
        "0ac01107e3c40e1a4032f3364b91cefbf77dd9432d73113aca344c1793a65ec7",
        21_323,
    ),
    ENGINE_RELATIVE: (ENGINE_SOURCE_SHA256, ENGINE_SOURCE_BYTE_COUNT),
    "scripts/run_walksafe_fp008_goal_start_gate_20260803.py": (
        "8d48b7685e0d8809c2982007218189a019745b3f0d7e614dee78f08c44805430",
        163_291,
    ),
}

_ENGINE_CONFIGURATION = {
    "ADDED_AUTHORITY_SCHEMA": (
        "walksafe.fp008-isolated-snapshot-fix-added-authority.v1"
    ),
    "ADDED_AUTHORITY_OPERATION": (
        "walksafe.fp008.seq47b-isolated-snapshot-fix-reconcile.v1"
    ),
    "ADDED_AUTHORITY_SIGNATURE_DOMAIN": (
        b"walksafe.fp008-isolated-snapshot-fix-added-authority.ed25519.v1"
    ),
    "REVIEWER_PUBLIC_KEY_SPKI_DER_BASE64": (
        "MCowBQYDK2VwAyEApGDArmnB3iHEftaMBVq27uAtQYiPcNuXldGj2C+sFjc="
    ),
    "REVIEWER_PUBLIC_KEY_SPKI_SHA256": (
        "d05359635aeb69ed3e7c975856f9f88ffd90cacb38fe1dbc6782d1f22042c89f"
    ),
    "REVIEWER_PUBLIC_KEY_SPKI_BYTE_COUNT": 44,
    "ED25519_SIGNATURE_BYTE_COUNT": 64,
    "SOURCE_CHECKPOINT_SHA256": (
        "3c6518ba09853987a0b76050e1727e932e7b6338826e2dfd4a44e03f4c65e2d4"
    ),
    "SOURCE_CHECKPOINT_BYTE_COUNT": 1_531_420,
    "SOURCE_SEQUENCE": 47,
    "SOURCE_EVENT_SHA256": (
        "82ad77e33eaa55530f53f5ee315807ef66e21fbfc8be2511b004abe99505db90"
    ),
    "SOURCE_CONTENT_SET_SHA256": (
        "b2549167757da634dd2a688c566f54d742d7fcfd83d3b5cfd59e7e7bb49d8195"
    ),
    "MANAGED_PATH_COUNT": 627,
    "PATH_SET_SHA256": (
        "6b803fcf23e250bc28415c75dc8793ee22c5d1976d801f968cf55c9ea51627ae"
    ),
    "AUTHORIZED_EXISTING_DELTAS": {
        "scripts/run_walksafe_fp008_goal_start_gate_20260803.py": {
            "source_sha256": (
                "713c4214acd150a1d2a115a978f3771001f78481a2457a0237cc767ac2d77e42"
            ),
            "candidate_sha256": (
                "8d48b7685e0d8809c2982007218189a019745b3f0d7e614dee78f08c44805430"
            ),
        },
        "scripts/run_walksafe_test_layers_20260711.sh": {
            "source_sha256": (
                "78747667b148504a70b5a1b793249fb47f75abfa5cdadf02aaba7cbdd58412b9"
            ),
            "candidate_sha256": (
                "8bed9597e078b78b6701413618b4fe48015cd8998a0ca2bdb451a031de9edc9e"
            ),
        },
        "tests/test_walksafe_fp008_goal_start_gate_20260803.py": {
            "source_sha256": (
                "f3a7de1c6f8b8bc01691effc6bae922cc88555ba399d365d8ce9f051f181feeb"
            ),
            "candidate_sha256": (
                "c84914444ae5bbe34ed794f5da48089b3f3332fa30fdcdf2b9e33c18d8fdcb75"
            ),
        },
    },
    "ADDED_MANAGED_PATHS": (
        "scripts/reconcile_walksafe_fp008_isolated_snapshot_fix_seq47b_20260809.py",
        "tests/test_walksafe_fp008_isolated_snapshot_fix_reconcile_seq47b_20260809.py",
    ),
}


class EngineLoadError(RuntimeError):
    """The exact frozen reconcile engine graph could not be loaded safely."""


def _identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _read_retained_file(
    directory_fd: int,
    leaf: str,
    expected_sha256: str,
    expected_size: int,
    *,
    require_single_link: bool = False,
) -> tuple[int, tuple[int, ...], bytes]:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
    nofollow = getattr(os, "O_NOFOLLOW", None)
    if not isinstance(nofollow, int):
        raise EngineLoadError("O_NOFOLLOW is required for frozen source capture")
    flags |= nofollow
    try:
        descriptor = os.open(leaf, flags, dir_fd=directory_fd)
    except OSError as exc:
        raise EngineLoadError(f"frozen source open failed: scripts/{leaf}") from exc
    try:
        before = os.fstat(descriptor)
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_uid != os.geteuid()
            or before.st_size != expected_size
            or (require_single_link and before.st_nlink != 1)
        ):
            raise EngineLoadError(f"frozen source metadata differs: scripts/{leaf}")
        chunks: list[bytes] = []
        offset = 0
        while offset < expected_size:
            chunk = os.pread(descriptor, min(1024 * 1024, expected_size - offset), offset)
            if not chunk:
                raise EngineLoadError(f"frozen source became short: scripts/{leaf}")
            chunks.append(chunk)
            offset += len(chunk)
        if os.pread(descriptor, 1, expected_size):
            raise EngineLoadError(f"frozen source grew while reading: scripts/{leaf}")
        source = b"".join(chunks)
        after = os.fstat(descriptor)
        if _identity(after) != _identity(before):
            raise EngineLoadError(f"frozen source changed while reading: scripts/{leaf}")
        if hashlib.sha256(source).hexdigest() != expected_sha256:
            raise EngineLoadError(f"frozen source SHA-256 differs: scripts/{leaf}")
        return descriptor, _identity(before), source
    except BaseException:
        os.close(descriptor)
        raise


class _FrozenSourceCapture:
    """Retain the physical repository source authority through child exit."""

    def __init__(
        self,
        canonical_root: Path,
        root_fd: int,
        root_authority: tuple[int, ...],
        scripts_fd: int,
        scripts_authority: tuple[int, ...],
        retained: list[tuple[str, int, tuple[int, ...], bytes]],
    ) -> None:
        self.canonical_root = canonical_root
        self.root_fd: int | None = root_fd
        self.root_authority = root_authority
        self.scripts_fd: int | None = scripts_fd
        self.scripts_authority = scripts_authority
        self.retained = retained
        self.sources = {
            f"scripts/{leaf}": source
            for leaf, _, _, source in retained
        }

    @property
    def descriptors(self) -> tuple[int, ...]:
        if self.root_fd is None or self.scripts_fd is None:
            raise EngineLoadError("frozen source capture is already closed")
        return (
            self.root_fd,
            self.scripts_fd,
            *(descriptor for _, descriptor, _, _ in self.retained),
        )

    def verify(self) -> None:
        if self.root_fd is None or self.scripts_fd is None:
            raise EngineLoadError("frozen source capture is already closed")
        named_root = os.stat(self.canonical_root, follow_symlinks=False)
        named_scripts = os.stat(
            "scripts",
            dir_fd=self.root_fd,
            follow_symlinks=False,
        )
        if (
            not stat.S_ISDIR(named_root.st_mode)
            or _identity(named_root) != self.root_authority
            or _identity(os.fstat(self.root_fd)) != self.root_authority
            or not stat.S_ISDIR(named_scripts.st_mode)
            or _identity(named_scripts) != self.scripts_authority
            or _identity(os.fstat(self.scripts_fd)) != self.scripts_authority
        ):
            raise EngineLoadError("frozen source directory authority changed")
        for leaf, descriptor, authority, source in self.retained:
            named = os.stat(leaf, dir_fd=self.scripts_fd, follow_symlinks=False)
            if (
                _identity(named) != authority
                or _identity(os.fstat(descriptor)) != authority
                or _read_fd_exact(descriptor, len(source)) != source
            ):
                raise EngineLoadError(
                    f"frozen source authority changed: scripts/{leaf}"
                )

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        while self.retained:
            _, descriptor, _, _ = self.retained.pop()
            try:
                os.close(descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc
        for attribute in ("scripts_fd", "root_fd"):
            descriptor = getattr(self, attribute)
            setattr(self, attribute, None)
            if descriptor is not None:
                try:
                    os.close(descriptor)
                except BaseException as exc:
                    if first is None:
                        first = exc
        if primary is None and first is not None:
            raise first


def _read_fd_exact(descriptor: int, expected_size: int) -> bytes:
    chunks: list[bytes] = []
    offset = 0
    while offset < expected_size:
        chunk = os.pread(
            descriptor,
            min(1024 * 1024, expected_size - offset),
            offset,
        )
        if not chunk:
            raise EngineLoadError("retained file became short")
        chunks.append(chunk)
        offset += len(chunk)
    if os.pread(descriptor, 1, expected_size):
        raise EngineLoadError("retained file grew")
    return b"".join(chunks)


def _capture_frozen_sources(root: Path = ROOT) -> _FrozenSourceCapture:
    canonical_root = root.resolve(strict=True)
    directory_flags = (
        os.O_RDONLY
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    root_fd = os.open(canonical_root, directory_flags)
    scripts_fd: int | None = None
    retained: list[tuple[str, int, tuple[int, ...], bytes]] = []
    success = False
    try:
        root_authority = _identity(os.fstat(root_fd))
        named_root = os.stat(canonical_root, follow_symlinks=False)
        if not stat.S_ISDIR(named_root.st_mode) or _identity(named_root) != root_authority:
            raise EngineLoadError("repository root authority differs")
        scripts_fd = os.open("scripts", directory_flags, dir_fd=root_fd)
        scripts_authority = _identity(os.fstat(scripts_fd))
        named_scripts = os.stat("scripts", dir_fd=root_fd, follow_symlinks=False)
        if not stat.S_ISDIR(named_scripts.st_mode) or _identity(named_scripts) != scripts_authority:
            raise EngineLoadError("repository scripts authority differs")
        sources: dict[str, bytes] = {}
        for relative, (expected_sha256, expected_size) in sorted(
            FROZEN_SCRIPT_SOURCES.items()
        ):
            path = Path(relative)
            if path.parent.as_posix() != "scripts" or path.name in ("", ".", ".."):
                raise EngineLoadError(f"unsafe frozen source path: {relative}")
            descriptor, authority, source = _read_retained_file(
                scripts_fd,
                path.name,
                expected_sha256,
                expected_size,
                require_single_link=relative == ENGINE_RELATIVE,
            )
            retained.append((path.name, descriptor, authority, source))
            sources[relative] = source
        for leaf, descriptor, authority, _ in retained:
            named = os.stat(leaf, dir_fd=scripts_fd, follow_symlinks=False)
            if _identity(os.fstat(descriptor)) != authority or _identity(named) != authority:
                raise EngineLoadError(f"frozen source authority changed: scripts/{leaf}")
        if (
            _identity(os.fstat(scripts_fd)) != scripts_authority
            or _identity(os.stat("scripts", dir_fd=root_fd, follow_symlinks=False))
            != scripts_authority
            or _identity(os.fstat(root_fd)) != root_authority
            or _identity(os.stat(canonical_root, follow_symlinks=False)) != root_authority
        ):
            raise EngineLoadError("frozen source directory authority changed")
        capture = _FrozenSourceCapture(
            canonical_root,
            root_fd,
            root_authority,
            scripts_fd,
            scripts_authority,
            retained,
        )
        capture.verify()
        success = True
        return capture
    finally:
        if not success:
            for _, descriptor, _, _ in reversed(retained):
                os.close(descriptor)
            if scripts_fd is not None:
                os.close(scripts_fd)
            os.close(root_fd)


class _FrozenLoader(importlib.abc.Loader):
    def __init__(self, graph: "_PrivateModuleGraph", relative: str, origin: str):
        self.graph = graph
        self.relative = relative
        self.origin = origin

    def create_module(self, spec: Any) -> None:
        return None

    def exec_module(self, module: ModuleType) -> None:
        module.__file__ = self.origin
        source = self.graph.sources[self.relative]
        code = compile(source, self.origin, "exec", dont_inherit=True)
        exec(code, module.__dict__)


class _ScriptsPackageLoader(importlib.abc.Loader):
    def create_module(self, spec: Any) -> None:
        return None

    def exec_module(self, module: ModuleType) -> None:
        module.__path__ = []


class _FrozenFinder(importlib.abc.MetaPathFinder):
    def __init__(self, graph: "_PrivateModuleGraph"):
        self.graph = graph

    def find_spec(
        self,
        fullname: str,
        path: Any = None,
        target: Any = None,
    ) -> Any:
        if fullname == "scripts":
            return importlib.util.spec_from_loader(
                fullname,
                _ScriptsPackageLoader(),
                origin=str(self.graph.root / "scripts/__init__.py"),
                is_package=True,
            )
        if not fullname.startswith("scripts."):
            return None
        relative = "scripts/" + fullname.removeprefix("scripts.").replace(".", "/") + ".py"
        if relative not in self.graph.sources:
            raise ModuleNotFoundError(f"uncontrolled repository module import: {fullname}")
        origin = str(self.graph.root / relative)
        return importlib.util.spec_from_loader(
            fullname,
            _FrozenLoader(self.graph, relative, origin),
            origin=origin,
        )


class _PrivateModuleGraph:
    def __init__(self, root: Path, sources: dict[str, bytes]):
        self.root = root
        self.sources = dict(sources)
        self.finder = _FrozenFinder(self)
        self.modules: dict[str, ModuleType] = {}
        self._original_spec_from_file_location = importlib.util.spec_from_file_location

    def _spec_from_file_location(
        self,
        name: str,
        location: Any,
        *args: Any,
        **kwargs: Any,
    ) -> Any:
        candidate = Path(os.path.abspath(os.fspath(location)))
        try:
            relative = candidate.relative_to(self.root).as_posix()
        except ValueError:
            return self._original_spec_from_file_location(name, location, *args, **kwargs)
        if relative in self.sources:
            return importlib.util.spec_from_loader(
                name,
                _FrozenLoader(self, relative, str(candidate)),
                origin=str(candidate),
            )
        if candidate.suffix == ".py":
            raise ImportError(f"uncontrolled repository file import: {relative}")
        return self._original_spec_from_file_location(name, location, *args, **kwargs)

    @contextmanager
    def activate(self) -> Iterator[None]:
        existing = {
            name: module
            for name, module in sys.modules.items()
            if name == "scripts" or name.startswith("scripts.")
        }
        for name in existing:
            sys.modules.pop(name, None)
        sys.modules.update(self.modules)
        path_before = list(sys.path)
        meta_before = list(sys.meta_path)
        spec_before = importlib.util.spec_from_file_location
        sys.meta_path.insert(0, self.finder)
        importlib.util.spec_from_file_location = self._spec_from_file_location
        try:
            yield
            self.modules = {
                name: module
                for name, module in sys.modules.items()
                if name == "scripts" or name.startswith("scripts.")
            }
        finally:
            importlib.util.spec_from_file_location = spec_before
            sys.meta_path[:] = meta_before
            sys.path[:] = path_before
            for name in tuple(sys.modules):
                if name == "scripts" or name.startswith("scripts."):
                    sys.modules.pop(name, None)
            sys.modules.update(existing)


def _private_module_name() -> str:
    while True:
        name = ENGINE_PRIVATE_MODULE_PREFIX + uuid.uuid4().hex
        if name not in sys.modules:
            return name


def _configure_engine(module: ModuleType) -> None:
    for name, value in _ENGINE_CONFIGURATION.items():
        setattr(module, name, copy.deepcopy(value))


class _EngineProxy:
    __slots__ = ("__graph", "__module", "__signature_verifier")

    _ALLOWED = frozenset(
        {
            "ADDED_AUTHORITY_OPERATION",
            "ADDED_AUTHORITY_SCHEMA",
            "ADDED_AUTHORITY_SIGNATURE_DOMAIN",
            "ADDED_MANAGED_PATHS",
            "AUTHORIZED_EXISTING_DELTAS",
            "AddedAuthorityManifestGuard",
            "CHECKPOINT",
            "ED25519_SIGNATURE_BYTE_COUNT",
            "MANAGED_PATH_COUNT",
            "PATH_SET_SHA256",
            "ReconcileCohort",
            "REVIEWER_PUBLIC_KEY_SPKI_DER_BASE64",
            "SIGNED_EXTERNAL_ALIAS_PATHS",
            "SOURCE_CHECKPOINT_BYTE_COUNT",
            "SOURCE_CHECKPOINT_SHA256",
            "SOURCE_CONTENT_SET_SHA256",
            "SOURCE_EVENT_SHA256",
            "SOURCE_SEQUENCE",
            "__name__",
            "_content_set_sha256_from_digests",
            "_project_from_retained",
            "_require_exact_seq47",
            "_source_from_candidate",
            "_verify_reviewer_signature",
            "authority_document",
            "authority_json_bytes",
            "json_bytes",
            "prepare",
            "sha256_bytes",
        }
    )

    def __init__(self, graph: _PrivateModuleGraph, module: ModuleType):
        object.__setattr__(self, "_EngineProxy__graph", graph)
        object.__setattr__(self, "_EngineProxy__module", module)
        object.__setattr__(
            self,
            "_EngineProxy__signature_verifier",
            getattr(module, "_verify_reviewer_signature"),
        )

    def __getattr__(self, name: str) -> Any:
        if name not in self._ALLOWED:
            raise AttributeError(f"test-only engine capability is not exposed: {name}")
        graph = object.__getattribute__(self, "_EngineProxy__graph")
        module = object.__getattribute__(self, "_EngineProxy__module")
        with graph.activate():
            value = getattr(module, name)
        if isinstance(value, FunctionType):
            def isolated(*args: Any, **kwargs: Any) -> Any:
                with graph.activate():
                    return value(*args, **kwargs)

            return isolated
        return copy.deepcopy(value)

    def __setattr__(self, name: str, value: Any) -> None:
        if name == "_verify_reviewer_signature":
            graph = object.__getattribute__(self, "_EngineProxy__graph")
            module = object.__getattribute__(self, "_EngineProxy__module")
            with graph.activate():
                setattr(module, name, value)
            return
        raise AttributeError("test-only engine capability is read-only")

    def __delattr__(self, name: str) -> None:
        if name == "_verify_reviewer_signature":
            graph = object.__getattribute__(self, "_EngineProxy__graph")
            module = object.__getattribute__(self, "_EngineProxy__module")
            original = object.__getattribute__(
                self,
                "_EngineProxy__signature_verifier",
            )
            with graph.activate():
                setattr(module, name, original)
            return
        raise AttributeError("test-only engine capability is read-only")


def _load_engine(root: Path = ROOT) -> None:
    del root
    raise EngineLoadError("in-process reconcile engine loading is disabled")


def _build_read_only_engine_facts(configuration: dict[str, Any]) -> Any:
    """Detach immutable compatibility facts from every CLI authority object."""

    def freeze(value: Any) -> tuple[str, Any]:
        if isinstance(value, dict):
            return (
                "dict",
                tuple((key, freeze(item)) for key, item in sorted(value.items())),
            )
        if isinstance(value, list):
            return "list", tuple(freeze(item) for item in value)
        if isinstance(value, tuple):
            return "tuple", tuple(freeze(item) for item in value)
        if isinstance(value, bytes):
            return "bytes", value
        if value is None or isinstance(value, (bool, int, str)):
            return "scalar", value
        raise TypeError(f"unsupported read-only fact type: {type(value).__name__}")

    def thaw(frozen: tuple[str, Any]) -> Any:
        kind, value = frozen
        if kind == "dict":
            return {key: thaw(item) for key, item in value}
        if kind == "list":
            return [thaw(item) for item in value]
        if kind == "tuple":
            return tuple(thaw(item) for item in value)
        if kind in {"bytes", "scalar"}:
            return value
        raise TypeError(f"unsupported frozen fact type: {kind}")

    frozen_configuration = tuple(
        (name, freeze(value)) for name, value in sorted(configuration.items())
    )
    frozen_aliases = freeze(
        {
            "scripts/check_walksafe_project_continuation_v2_4.py": (
                "/home/ddobagi/Code/walksafe-v24-tests-stage-j22XpI/"
                "scripts/check_walksafe_project_continuation_v2_4.py",
                "/home/ddobagi/.cache/walksafe-fp011-seq7-v24-g8rctyj2/"
                "scripts/check_walksafe_project_continuation_v2_4.py",
            )
        }
    )
    source_byte_count = configuration["SOURCE_CHECKPOINT_BYTE_COUNT"]
    source_sha256 = configuration["SOURCE_CHECKPOINT_SHA256"]
    added_paths = frozenset(configuration["ADDED_MANAGED_PATHS"])
    managed_path_count = configuration["MANAGED_PATH_COUNT"]
    path_set_sha256 = configuration["PATH_SET_SHA256"]
    content_set_sha256 = configuration["SOURCE_CONTENT_SET_SHA256"]
    dumps = json.dumps
    sha256 = hashlib.sha256

    def json_bytes(value: dict[str, Any]) -> bytes:
        return (dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    def content_hash(paths: list[str], digests: dict[str, str]) -> str:
        value = sha256()
        for relative in sorted(paths):
            value.update(relative.encode("utf-8"))
            value.update(b"\0")
            value.update(digests[relative].encode("ascii"))
            value.update(b"\n")
        return value.hexdigest()

    def require_exact_seq47(source: dict[str, Any]) -> list[str]:
        raw = json_bytes(source)
        if len(raw) != source_byte_count or sha256(raw).hexdigest() != source_sha256:
            raise EngineLoadError("source is not the exact seq47 checkpoint")
        paths = source["working_tree_snapshot"]["managed_changed_paths"]
        if not isinstance(paths, list) or paths != sorted(set(paths)):
            raise EngineLoadError("seq47 managed path inventory differs")
        return paths

    def source_from_candidate(candidate: dict[str, Any]) -> dict[str, Any]:
        snapshot = candidate.get("working_tree_snapshot")
        paths = (
            snapshot.get("managed_changed_paths")
            if isinstance(snapshot, dict)
            else None
        )
        if (
            not isinstance(paths, list)
            or paths != sorted(set(paths))
            or not added_paths.issubset(paths)
        ):
            raise EngineLoadError("reconciled candidate membership differs")
        source = thaw(freeze(candidate))
        source_paths = sorted(set(paths) - added_paths)
        source_snapshot = source["working_tree_snapshot"]
        source_snapshot.update(
            managed_changed_paths=source_paths,
            managed_changed_path_count=managed_path_count,
            path_set_sha256=path_set_sha256,
            content_set_sha256=content_set_sha256,
        )
        handoff = source["session_handoff"]
        handoff["changed_files"] = list(source_paths)
        handoff["source_commit_or_snapshot"].update(
            file_count=managed_path_count,
            path_set_sha256=path_set_sha256,
            content_set_sha256=content_set_sha256,
        )
        require_exact_seq47(source)
        return source

    function_pairs = (
        ("_content_set_sha256_from_digests", content_hash),
        ("_require_exact_seq47", require_exact_seq47),
        ("_source_from_candidate", source_from_candidate),
        ("json_bytes", json_bytes),
    )
    private_name = ENGINE_PRIVATE_MODULE_PREFIX + "read_only_facts"

    class ReadOnlyFacts:
        __slots__ = ()

        def __getattr__(self, name: str) -> Any:
            if name == "__name__":
                return private_name
            if name == "SIGNED_EXTERNAL_ALIAS_PATHS":
                return thaw(frozen_aliases)
            for function_name, function in function_pairs:
                if name == function_name:
                    return function
            for fact_name, frozen_value in frozen_configuration:
                if name == fact_name:
                    return thaw(frozen_value)
            raise AttributeError(
                f"reconcile engine capability is not exposed: {name}"
            )

        def __setattr__(self, name: str, value: Any) -> None:
            raise AttributeError("reconcile engine facts are read-only")

        def __delattr__(self, name: str) -> None:
            raise AttributeError("reconcile engine facts are read-only")

    return ReadOnlyFacts()


def _archive_bytes(sources: dict[str, bytes]) -> bytes:
    configuration = copy.deepcopy(_ENGINE_CONFIGURATION)
    configuration["ADDED_AUTHORITY_SIGNATURE_DOMAIN"] = configuration[
        "ADDED_AUTHORITY_SIGNATURE_DOMAIN"
    ].decode("ascii")
    configuration["ADDED_MANAGED_PATHS"] = list(
        configuration["ADDED_MANAGED_PATHS"]
    )
    manifest = {
        "schema_version": "walksafe.fp008-frozen-python-graph.v1",
        "engine_relative": ENGINE_RELATIVE,
        "engine_sha256": ENGINE_SOURCE_SHA256,
        "engine_bytes": ENGINE_SOURCE_BYTE_COUNT,
        "sources": {
            relative: {
                "sha256": hashlib.sha256(source).hexdigest(),
                "bytes": len(source),
            }
            for relative, source in sorted(sources.items())
        },
        "configuration": configuration,
    }
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for relative, source in sorted(sources.items()):
            info = zipfile.ZipInfo(f"sources/{relative}", (2026, 8, 9, 0, 0, 0))
            info.external_attr = 0o600 << 16
            archive.writestr(info, source)
        info = zipfile.ZipInfo("manifest.json", (2026, 8, 9, 0, 0, 0))
        info.external_attr = 0o600 << 16
        archive.writestr(
            info,
            json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            .encode("utf-8"),
        )
    return output.getvalue()


def _sealed_memfd(content: bytes) -> int:
    flags = _MFD_CLOEXEC | _MFD_ALLOW_SEALING
    if hasattr(os, "memfd_create"):
        descriptor = os.memfd_create("walksafe-fp008-frozen-graph", flags)
    else:
        libc = ctypes.CDLL(None, use_errno=True)
        create = getattr(libc, "memfd_create", None)
        if create is None:
            raise EngineLoadError("sealed memfd support is required")
        create.argtypes = (ctypes.c_char_p, ctypes.c_uint)
        create.restype = ctypes.c_int
        descriptor = create(b"walksafe-fp008-frozen-graph", flags)
        if descriptor < 0:
            error = ctypes.get_errno()
            raise EngineLoadError("sealed memfd creation failed") from OSError(
                error,
                os.strerror(error),
            )
    try:
        offset = 0
        while offset < len(content):
            written = os.write(descriptor, content[offset:])
            if written <= 0:
                raise EngineLoadError("frozen graph memfd write made no progress")
            offset += written
        seals = _F_SEAL_WRITE | _F_SEAL_GROW | _F_SEAL_SHRINK | _F_SEAL_SEAL
        fcntl.fcntl(descriptor, _F_ADD_SEALS, seals)
        if fcntl.fcntl(descriptor, _F_GET_SEALS) != seals:
            raise EngineLoadError("frozen graph memfd seals differ")
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


_CHILD_BOOTSTRAP = r'''
import copy, fcntl, hashlib, importlib, importlib.abc, importlib.machinery
import importlib.util, io, json, os, pathlib, stat, sys, zipfile

fd, expected_size, expected_sha, root, crypto_site = (
    int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], pathlib.Path(sys.argv[4]), sys.argv[5]
)
source_fds = [int(value) for value in sys.argv[6].split(",") if value]
runtime_fds = [int(value) for value in sys.argv[7].split(",") if value]
argv = sys.argv[8:]
if len(source_fds) != 15 or len(runtime_fds) != 18:
    raise SystemExit("retained authority descriptor inventory differs")
for retained_fd in (fd, *source_fds, *runtime_fds):
    fcntl.fcntl(
        retained_fd,
        fcntl.F_SETFD,
        fcntl.fcntl(retained_fd, fcntl.F_GETFD) | fcntl.FD_CLOEXEC,
    )
required = 0x0008 | 0x0004 | 0x0002 | 0x0001
if fcntl.fcntl(fd, 1034) != required:
    raise SystemExit("frozen graph is not fully sealed")
raw = os.pread(fd, expected_size, 0)
if len(raw) != expected_size or os.pread(fd, 1, expected_size):
    raise SystemExit("frozen graph byte count differs")
if hashlib.sha256(raw).hexdigest() != expected_sha:
    raise SystemExit("frozen graph SHA-256 differs")
if any(name == "scripts" or name.startswith("scripts.") for name in sys.modules):
    raise SystemExit("ambient repository module preload is forbidden")
sys.path.append(crypto_site)
with zipfile.ZipFile(io.BytesIO(raw), "r") as archive:
    names = archive.namelist()
    if len(names) != len(set(names)) or "manifest.json" not in names:
        raise SystemExit("frozen graph archive inventory differs")
    manifest_raw = archive.read("manifest.json")
    if (
        len(manifest_raw) != 4077
        or hashlib.sha256(manifest_raw).hexdigest()
        != "5a3e840ed3bd9667eed5ac40f4ecbc79b09a88b3388b216acb398f38cd0cacf8"
    ):
        raise SystemExit("frozen graph hardcoded manifest authority differs")
    manifest = json.loads(manifest_raw)
    expected_names = {"manifest.json"} | {
        "sources/" + relative for relative in manifest["sources"]
    }
    if set(names) != expected_names:
        raise SystemExit("frozen graph archive path set differs")
    sources = {}
    for relative, metadata in manifest["sources"].items():
        path = pathlib.PurePosixPath(relative)
        if path.is_absolute() or ".." in path.parts or path.as_posix() != relative:
            raise SystemExit("unsafe frozen graph path")
        value = archive.read("sources/" + relative)
        if (
            len(value) != metadata["bytes"]
            or hashlib.sha256(value).hexdigest() != metadata["sha256"]
        ):
            raise SystemExit("frozen graph member differs")
        sources[relative] = value
if (
    manifest.get("schema_version") != "walksafe.fp008-frozen-python-graph.v1"
    or manifest.get("engine_relative") not in sources
    or len(sources[manifest["engine_relative"]]) != manifest.get("engine_bytes")
    or hashlib.sha256(sources[manifest["engine_relative"]]).hexdigest()
    != manifest.get("engine_sha256")
):
    raise SystemExit("frozen graph manifest differs")

def identity(metadata):
    return (
        metadata.st_dev, metadata.st_ino, metadata.st_mode, metadata.st_uid,
        metadata.st_gid, metadata.st_nlink, metadata.st_size,
        metadata.st_mtime_ns, metadata.st_ctime_ns,
    )

def read_exact(retained_fd, expected_bytes):
    chunks, offset = [], 0
    while offset < expected_bytes:
        chunk = os.pread(retained_fd, min(1024 * 1024, expected_bytes - offset), offset)
        if not chunk:
            raise SystemExit("retained authority became short")
        chunks.append(chunk)
        offset += len(chunk)
    if os.pread(retained_fd, 1, expected_bytes):
        raise SystemExit("retained authority grew")
    return b"".join(chunks)

source_root_fd, source_scripts_fd, *source_file_fds = source_fds
source_initial = [identity(os.fstat(value)) for value in source_fds]

def verify_source_authority():
    named_root = os.stat(root, follow_symlinks=False)
    named_scripts = os.stat(
        "scripts", dir_fd=source_root_fd, follow_symlinks=False
    )
    if (
        identity(named_root) != source_initial[0]
        or identity(os.fstat(source_root_fd)) != source_initial[0]
        or identity(named_scripts) != source_initial[1]
        or identity(os.fstat(source_scripts_fd)) != source_initial[1]
    ):
        raise SystemExit("retained repository directory authority changed")
    expected_paths = sorted(manifest["sources"])
    for index, (relative, retained_fd) in enumerate(
        zip(expected_paths, source_file_fds, strict=True), start=2
    ):
        leaf = pathlib.PurePosixPath(relative).name
        named = os.stat(leaf, dir_fd=source_scripts_fd, follow_symlinks=False)
        raw_source = sources[relative]
        if (
            identity(named) != source_initial[index]
            or identity(os.fstat(retained_fd)) != source_initial[index]
            or read_exact(retained_fd, len(raw_source)) != raw_source
        ):
            raise SystemExit("retained repository source authority changed: " + relative)

runtime_directory_paths = (
    pathlib.Path("/usr/lib/python3/dist-packages"),
    pathlib.Path("/usr/lib/python3/dist-packages/cryptography"),
    pathlib.Path("/usr/lib/python3/dist-packages/bcrypt"),
)
runtime_file_paths = (
    pathlib.Path("/usr/bin/python3.14"),
    pathlib.Path(
        "/usr/lib/python3/dist-packages/"
        "_cffi_backend.cpython-314-x86_64-linux-gnu.so"
    ),
    pathlib.Path(
        "/usr/lib/python3/dist-packages/bcrypt/"
        "_bcrypt.cpython-314-x86_64-linux-gnu.so"
    ),
    pathlib.Path(
        "/usr/lib/python3/dist-packages/cryptography/hazmat/bindings/"
        "_rust.abi3-x86_64-linux-gnu.so"
    ),
    pathlib.Path("/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2"),
    pathlib.Path("/usr/lib/x86_64-linux-gnu/libc.so.6"),
    pathlib.Path("/usr/lib/x86_64-linux-gnu/libcrypto.so.3"),
    pathlib.Path("/usr/lib/x86_64-linux-gnu/libexpat.so.1.11.2"),
    pathlib.Path("/usr/lib/x86_64-linux-gnu/libffi.so.8.2.0"),
    pathlib.Path("/usr/lib/x86_64-linux-gnu/libgcc_s.so.1"),
    pathlib.Path("/usr/lib/x86_64-linux-gnu/libm.so.6"),
    pathlib.Path("/usr/lib/x86_64-linux-gnu/libssl.so.3"),
    pathlib.Path("/usr/lib/x86_64-linux-gnu/libz.so.1.3.1"),
    pathlib.Path("/usr/lib/x86_64-linux-gnu/libzstd.so.1.5.7"),
    pathlib.Path("/usr/lib/x86_64-linux-gnu/ossl-modules/legacy.so"),
)
runtime_initial = [identity(os.fstat(value)) for value in runtime_fds]

def verify_runtime_authority():
    observed = {}
    for index, (path, retained_fd) in enumerate(
        zip(runtime_directory_paths, runtime_fds[:3], strict=True)
    ):
        named = os.stat(path, follow_symlinks=False)
        if (
            identity(named) != runtime_initial[index]
            or identity(os.fstat(retained_fd)) != runtime_initial[index]
            or not stat.S_ISDIR(named.st_mode)
            or named.st_uid != 0
            or named.st_mode & 0o022
        ):
            raise SystemExit("root-owned runtime directory authority changed")
    for offset, (path, retained_fd) in enumerate(
        zip(runtime_file_paths, runtime_fds[3:], strict=True), start=3
    ):
        named = os.stat(path, follow_symlinks=False)
        opened = os.fstat(retained_fd)
        if (
            identity(named) != runtime_initial[offset]
            or identity(opened) != runtime_initial[offset]
            or not stat.S_ISREG(named.st_mode)
            or named.st_uid != 0
            or named.st_mode & 0o022
        ):
            raise SystemExit("root-owned runtime file authority changed")
        raw_runtime = read_exact(retained_fd, opened.st_size)
        observed[str(path)] = {
            "bytes": len(raw_runtime),
            "sha256": hashlib.sha256(raw_runtime).hexdigest(),
        }
    observed_raw = json.dumps(
        observed, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    if (
        len(observed_raw) != 2154
        or hashlib.sha256(observed_raw).hexdigest()
        != "6a9fb3bd555de9cf6ecfacebedcda3f63d185115d8960a6358441caeda038621"
    ):
        raise SystemExit("root-owned runtime hardcoded authority differs")
    if identity(os.stat("/proc/self/exe")) != runtime_initial[3]:
        raise SystemExit("running interpreter differs from retained authority")

verify_source_authority()
verify_runtime_authority()

class Loader(importlib.abc.Loader):
    def __init__(self, relative, origin): self.relative, self.origin = relative, origin
    def create_module(self, spec): return None
    def exec_module(self, module):
        module.__file__ = self.origin
        code = compile(
            sources[self.relative], self.origin, "exec", dont_inherit=True
        )
        exec(code, module.__dict__)

class PackageLoader(importlib.abc.Loader):
    def create_module(self, spec): return None
    def exec_module(self, module): module.__path__ = []

class Finder(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname == "scripts":
            return importlib.util.spec_from_loader(fullname, PackageLoader(), is_package=True)
        if fullname.startswith("scripts."):
            relative = (
                "scripts/"
                + fullname.removeprefix("scripts.").replace(".", "/")
                + ".py"
            )
            if relative not in sources:
                raise ModuleNotFoundError(
                    "uncontrolled repository module import: " + fullname
                )
            origin = str(root / relative)
            return importlib.util.spec_from_loader(
                fullname, Loader(relative, origin), origin=origin
            )
        if (
            importlib.machinery.BuiltinImporter.find_spec(fullname) is not None
            or importlib.machinery.FrozenImporter.find_spec(fullname) is not None
        ):
            return None
        search = trusted_search_path(path)
        spec = importlib.machinery.PathFinder.find_spec(fullname, search, target)
        if spec is None:
            raise ModuleNotFoundError(
                "module is absent from the root-owned runtime: " + fullname
            )
        require_trusted_spec(fullname, spec)
        return spec

TRUSTED_ROOTS = (
    pathlib.Path("/usr/lib/python3.14"),
    pathlib.Path(crypto_site),
)
TRUSTED_TOP_LEVEL_SITE = {"_cffi_backend", "bcrypt", "cryptography"}

def trusted_path(value, *, require_directory=False):
    candidate = pathlib.Path(os.path.abspath(os.fspath(value)))
    base = next(
        (item for item in TRUSTED_ROOTS if candidate == item or item in candidate.parents),
        None,
    )
    if base is None:
        raise ImportError("module origin is outside the root-owned runtime: " + str(candidate))
    current = pathlib.Path("/")
    for part in candidate.parts[1:]:
        current /= part
        metadata = os.stat(current, follow_symlinks=False)
        if (
            stat.S_ISLNK(metadata.st_mode)
            or metadata.st_uid != 0
            or metadata.st_mode & 0o022
        ):
            raise ImportError("module origin authority differs: " + str(current))
    metadata = os.stat(candidate, follow_symlinks=False)
    if require_directory and not stat.S_ISDIR(metadata.st_mode):
        raise ImportError("module search path is not a directory: " + str(candidate))
    if not require_directory and not stat.S_ISREG(metadata.st_mode):
        raise ImportError("module origin is not a regular file: " + str(candidate))
    return candidate

def trusted_search_path(path):
    if path is None:
        return [
            "/usr/lib/python3.14",
            "/usr/lib/python3.14/lib-dynload",
            crypto_site,
        ]
    return [str(trusted_path(value, require_directory=True)) for value in path]

def require_trusted_spec(fullname, spec):
    if spec.origin not in (None, "built-in", "frozen"):
        origin = trusted_path(spec.origin)
        site = pathlib.Path(crypto_site)
        if site in origin.parents and fullname.partition(".")[0] not in TRUSTED_TOP_LEVEL_SITE:
            raise ImportError("unapproved system-site module import: " + fullname)
    locations = spec.submodule_search_locations
    if locations is not None:
        for value in locations:
            trusted_path(value, require_directory=True)

finder = Finder()
sys.meta_path.insert(0, finder)
original_spec = importlib.util.spec_from_file_location
def frozen_spec(name, location, *args, **kwargs):
    candidate = pathlib.Path(os.path.abspath(os.fspath(location)))
    try: relative = candidate.relative_to(root).as_posix()
    except ValueError:
        spec = original_spec(name, location, *args, **kwargs)
        if spec is not None:
            require_trusted_spec(name, spec)
        return spec
    if relative in sources:
        return importlib.util.spec_from_loader(
            name, Loader(relative, str(candidate)), origin=str(candidate)
        )
    if candidate.suffix == ".py":
        raise ImportError("uncontrolled repository file import: " + relative)
    spec = original_spec(name, location, *args, **kwargs)
    if spec is not None:
        require_trusted_spec(name, spec)
    return spec
importlib.util.spec_from_file_location = frozen_spec
engine_name = "scripts." + pathlib.PurePosixPath(manifest["engine_relative"]).stem
engine = importlib.import_module(engine_name)
configuration = manifest["configuration"]
configuration["ADDED_AUTHORITY_SIGNATURE_DOMAIN"] = configuration[
    "ADDED_AUTHORITY_SIGNATURE_DOMAIN"
].encode("ascii")
configuration["ADDED_MANAGED_PATHS"] = tuple(configuration["ADDED_MANAGED_PATHS"])
for name, value in configuration.items(): setattr(engine, name, copy.deepcopy(value))

# Every checkpoint read/write chain must open the same physical repository
# root inherited from the parent capture.  The exact engine then retains that
# chain and applies its existing terminal path/FD guards around publication.
writer = engine.secure_writer
original_chain_capture = writer._DirectoryChain.capture.__func__
def root_bound_chain_capture(cls, requested_root, relative):
    chain = original_chain_capture(cls, requested_root, relative)
    try:
        if identity(os.fstat(chain.descriptors[0])) != source_initial[0]:
            raise writer.StartApplyError(
                "repository root differs from inherited source authority"
            )
        return chain
    except BaseException as exc:
        chain.close(exc)
        raise
writer._DirectoryChain.capture = classmethod(root_bound_chain_capture)

child_result = None
child_error = None
try:
    child_result = engine.main(argv)
except BaseException as exc:
    child_error = exc
try:
    verify_source_authority()
    verify_runtime_authority()
except BaseException:
    if "--write" in argv:
        raise SystemExit(2)
    if child_error is not None:
        raise child_error
    raise
if child_error is not None:
    raise child_error
raise SystemExit(child_result)
'''


def _require_root_owned_runtime_path(
    path: Path,
    *,
    directory: bool,
) -> os.stat_result:
    if not path.is_absolute() or ".." in path.parts:
        raise EngineLoadError(f"system runtime path is unsafe: {path}")
    current = Path("/")
    metadata: os.stat_result | None = None
    for part in path.parts[1:]:
        current /= part
        metadata = os.stat(current, follow_symlinks=False)
        if (
            stat.S_ISLNK(metadata.st_mode)
            or metadata.st_uid != 0
            or metadata.st_mode & 0o022
        ):
            raise EngineLoadError(f"system runtime authority differs: {current}")
    if metadata is None:
        raise EngineLoadError("system runtime path cannot be the filesystem root")
    expected_type = stat.S_ISDIR if directory else stat.S_ISREG
    if not expected_type(metadata.st_mode):
        raise EngineLoadError(f"system runtime type differs: {path}")
    return metadata


def _open_pinned_runtime_file(
    path: Path,
    expected_sha256: str,
    expected_size: int,
) -> tuple[int, tuple[int, ...]]:
    named = _require_root_owned_runtime_path(path, directory=False)
    descriptor = os.open(
        path,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        opened = os.fstat(descriptor)
        raw = _read_fd_exact(descriptor, expected_size)
        if (
            _identity(opened) != _identity(named)
            or opened.st_nlink != 1
            or opened.st_size != expected_size
            or hashlib.sha256(raw).hexdigest() != expected_sha256
            or _identity(os.fstat(descriptor)) != _identity(opened)
        ):
            raise EngineLoadError(f"system runtime file differs: {path}")
        return descriptor, _identity(opened)
    except BaseException:
        os.close(descriptor)
        raise


def _verify_root_owned_runtime_tree(path: Path) -> None:
    _require_root_owned_runtime_path(path, directory=True)
    for current, directories, files in os.walk(path, followlinks=False):
        current_path = Path(current)
        _require_root_owned_runtime_path(current_path, directory=True)
        for name in (*directories, *files):
            candidate = current_path / name
            metadata = os.stat(candidate, follow_symlinks=False)
            if stat.S_ISLNK(metadata.st_mode):
                raise EngineLoadError(f"system runtime symlink is forbidden: {candidate}")
            _require_root_owned_runtime_path(
                candidate,
                directory=stat.S_ISDIR(metadata.st_mode),
            )


class _SystemRuntimeAuthority:
    """Pin the root-owned interpreter, crypto closure, and loader dependencies."""

    def __init__(
        self,
        directory_entries: list[tuple[Path, int, tuple[int, ...]]],
        file_entries: list[
            tuple[Path, int, tuple[int, ...], str, int]
        ],
    ) -> None:
        self.directory_entries = directory_entries
        self.file_entries = file_entries

    @classmethod
    def capture(cls) -> "_SystemRuntimeAuthority":
        directory_entries: list[tuple[Path, int, tuple[int, ...]]] = []
        file_entries: list[tuple[Path, int, tuple[int, ...], str, int]] = []
        try:
            for path in (
                SYSTEM_CRYPTOGRAPHY_SITE,
                SYSTEM_CRYPTOGRAPHY_SITE / "cryptography",
                SYSTEM_CRYPTOGRAPHY_SITE / "bcrypt",
            ):
                named = _require_root_owned_runtime_path(path, directory=True)
                descriptor = os.open(
                    path,
                    os.O_RDONLY
                    | getattr(os, "O_DIRECTORY", 0)
                    | getattr(os, "O_CLOEXEC", 0)
                    | getattr(os, "O_NOFOLLOW", 0),
                )
                opened = os.fstat(descriptor)
                if _identity(opened) != _identity(named):
                    os.close(descriptor)
                    raise EngineLoadError(
                        f"system runtime directory differs: {path}"
                    )
                directory_entries.append((path, descriptor, _identity(opened)))
            _verify_root_owned_runtime_tree(
                SYSTEM_CRYPTOGRAPHY_SITE / "cryptography"
            )
            _verify_root_owned_runtime_tree(SYSTEM_CRYPTOGRAPHY_SITE / "bcrypt")
            interpreter_fd, interpreter_identity = _open_pinned_runtime_file(
                SYSTEM_PYTHON,
                SYSTEM_PYTHON_SHA256,
                SYSTEM_PYTHON_BYTE_COUNT,
            )
            file_entries.append(
                (
                    SYSTEM_PYTHON,
                    interpreter_fd,
                    interpreter_identity,
                    SYSTEM_PYTHON_SHA256,
                    SYSTEM_PYTHON_BYTE_COUNT,
                )
            )
            for value, (expected_sha256, expected_size) in sorted(
                SYSTEM_RUNTIME_FILES.items()
            ):
                path = Path(value)
                descriptor, identity = _open_pinned_runtime_file(
                    path,
                    expected_sha256,
                    expected_size,
                )
                file_entries.append(
                    (path, descriptor, identity, expected_sha256, expected_size)
                )
            authority = cls(directory_entries, file_entries)
            authority.verify()
            return authority
        except BaseException as exc:
            authority = cls(directory_entries, file_entries)
            authority.close(exc)
            raise

    @property
    def interpreter_fd(self) -> int:
        if not self.file_entries or self.file_entries[0][0] != SYSTEM_PYTHON:
            raise EngineLoadError("system interpreter authority is unavailable")
        return self.file_entries[0][1]

    @property
    def descriptors(self) -> tuple[int, ...]:
        return (
            *(descriptor for _, descriptor, _ in self.directory_entries),
            *(descriptor for _, descriptor, _, _, _ in self.file_entries),
        )

    def verify(self) -> None:
        for path, descriptor, identity in self.directory_entries:
            named = _require_root_owned_runtime_path(path, directory=True)
            if (
                _identity(named) != identity
                or _identity(os.fstat(descriptor)) != identity
            ):
                raise EngineLoadError(f"system runtime directory changed: {path}")
        for path, descriptor, identity, expected_sha256, expected_size in self.file_entries:
            named = _require_root_owned_runtime_path(path, directory=False)
            if (
                _identity(named) != identity
                or _identity(os.fstat(descriptor)) != identity
                or hashlib.sha256(
                    _read_fd_exact(descriptor, expected_size)
                ).hexdigest()
                != expected_sha256
            ):
                raise EngineLoadError(f"system runtime file changed: {path}")
        _verify_root_owned_runtime_tree(SYSTEM_CRYPTOGRAPHY_SITE / "cryptography")
        _verify_root_owned_runtime_tree(SYSTEM_CRYPTOGRAPHY_SITE / "bcrypt")

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        while self.file_entries:
            _, descriptor, _, _, _ = self.file_entries.pop()
            try:
                os.close(descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc
        while self.directory_entries:
            _, descriptor, _ = self.directory_entries.pop()
            try:
                os.close(descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc
        if primary is None and first is not None:
            raise first


def _canonicalize_root_argument(
    argv: Sequence[str],
    canonical_root: Path,
) -> list[str]:
    result: list[str] = []
    seen = False
    index = 0
    while index < len(argv):
        value = argv[index]
        if value == "--root":
            if seen or index + 1 >= len(argv):
                raise EngineLoadError("root argument is duplicated or missing")
            seen = True
            index += 2
            continue
        if value.startswith("--root="):
            if seen:
                raise EngineLoadError("root argument is duplicated")
            seen = True
            index += 1
            continue
        result.append(value)
        index += 1
    return ["--root", str(canonical_root), *result]


_CLI_FLAG_OPTIONS = frozenset(
    {
        "--preflight",
        "--print-unsigned-authority-template",
        "--write",
        "--help",
        "-h",
    }
)
_CLI_VALUE_OPTIONS = frozenset(
    {
        "--root",
        "--added-authority-manifest",
        "--added-authority-sha256",
        "--added-authority-bytes",
        "--added-authority-signature",
        "--added-authority-signature-sha256",
        "--added-authority-signature-bytes",
    }
)


def _validate_cli_argv(argv: Sequence[str]) -> bool:
    index = 0
    write_requested = False
    while index < len(argv):
        token = argv[index]
        if token in _CLI_FLAG_OPTIONS:
            write_requested = write_requested or token == "--write"
            index += 1
            continue
        option, separator, _ = token.partition("=")
        if separator and option in _CLI_VALUE_OPTIONS:
            index += 1
            continue
        if token in _CLI_VALUE_OPTIONS:
            if index + 1 >= len(argv):
                raise EngineLoadError(f"CLI option value is missing: {token}")
            index += 2
            continue
        raise EngineLoadError(f"non-canonical CLI option is forbidden: {token}")
    return write_requested


def _requested_root(argv: Sequence[str]) -> Path:
    parser = argparse.ArgumentParser(add_help=False, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    known, _ = parser.parse_known_args(argv)
    return known.root


def _normalize_isolated_result(
    *,
    write_requested: bool,
    child_started: bool,
    result: int | None,
    terminal_error: BaseException | None,
) -> int:
    if terminal_error is not None:
        if child_started and write_requested:
            return 2
        raise terminal_error
    if result is None:
        raise EngineLoadError("isolated reconcile child did not return a status")
    if write_requested and result < 0:
        return 2
    return result


def _run_isolated_child(argv: Sequence[str]) -> int:
    write_requested = _validate_cli_argv(argv)
    requested = _requested_root(argv)
    capture = _capture_frozen_sources(requested)
    runtime: _SystemRuntimeAuthority | None = None
    descriptor: int | None = None
    result: int | None = None
    terminal_error: BaseException | None = None
    child_started = False
    try:
        runtime = _SystemRuntimeAuthority.capture()
        archive = _archive_bytes(capture.sources)
        descriptor = _sealed_memfd(archive)
        source_descriptors = capture.descriptors
        runtime_descriptors = runtime.descriptors
        interpreter_fd = runtime.interpreter_fd
        command = [
            f"/proc/self/fd/{interpreter_fd}",
            "-I",
            "-S",
            "-B",
            "-c",
            _CHILD_BOOTSTRAP,
            str(descriptor),
            str(len(archive)),
            hashlib.sha256(archive).hexdigest(),
            str(capture.canonical_root),
            str(SYSTEM_CRYPTOGRAPHY_SITE),
            ",".join(str(value) for value in source_descriptors),
            ",".join(str(value) for value in runtime_descriptors),
            *_canonicalize_root_argument(argv, capture.canonical_root),
        ]
        child_started = True
        completed = subprocess.run(
            command,
            check=False,
            close_fds=True,
            pass_fds=(descriptor, *source_descriptors, *runtime_descriptors),
            cwd="/",
            env={
                "LANG": "C.UTF-8",
                "LC_ALL": "C.UTF-8",
                "PATH": "/usr/bin:/bin",
                "PYTHONDONTWRITEBYTECODE": "1",
                "TZ": "UTC",
            },
        )
        result = completed.returncode
        capture.verify()
        runtime.verify()
    except BaseException as exc:
        terminal_error = exc
    finally:
        first = terminal_error
        if descriptor is not None:
            try:
                os.close(descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc
        if runtime is not None:
            try:
                runtime.close(first)
            except BaseException as exc:
                if first is None:
                    first = exc
        try:
            capture.close(first)
        except BaseException as exc:
            if first is None:
                first = exc
        terminal_error = first
    return _normalize_isolated_result(
        write_requested=write_requested,
        child_started=child_started,
        result=result,
        terminal_error=terminal_error,
    )


def main(argv: Sequence[str] | None = None) -> int:
    """Execute the corrective reconcile only in a fresh isolated child."""
    if __name__ != "__main__":
        raise EngineLoadError("write-capable reconcile entry point is CLI-only")
    try:
        return _run_isolated_child(list(argv) if argv is not None else sys.argv[1:])
    except (EngineLoadError, OSError, ValueError) as exc:
        print(f"WalkSafe FP008 snapshot reconcile: FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ != "__main__":
    engine = _build_read_only_engine_facts(_ENGINE_CONFIGURATION)
    import gc as _gc

    _retired_class_names = frozenset(
        {
            "_EngineProxy",
            "_FrozenFinder",
            "_FrozenLoader",
            "_FrozenSourceCapture",
            "_PrivateModuleGraph",
            "_ScriptsPackageLoader",
        }
    )
    _retired_function_names = frozenset(
        {
            "_archive_bytes",
            "_build_read_only_engine_facts",
            "_capture_frozen_sources",
            "_configure_engine",
            "_private_module_name",
            "_run_isolated_child",
            "main",
        }
    )
    for _retired_name in (
        *_retired_class_names,
        *_retired_function_names,
        "_ENGINE_CONFIGURATION",
    ):
        globals().pop(_retired_name, None)
    _gc.collect()
    for _retired_object in _gc.get_objects():
        if (
            type(_retired_object) is type
            and _retired_object.__module__ == __name__
            and _retired_object.__name__ in _retired_class_names
        ):
            raise EngineLoadError("retired loader class remains reachable")
        if (
            type(_retired_object) is FunctionType
            and _retired_object.__module__ == __name__
            and _retired_object.__name__ in _retired_function_names
        ):
            raise EngineLoadError("retired loader function remains reachable")
    del _retired_object
    del _retired_name
    del _retired_class_names
    del _retired_function_names
    del _gc


if __name__ == "__main__":
    raise SystemExit(main())
