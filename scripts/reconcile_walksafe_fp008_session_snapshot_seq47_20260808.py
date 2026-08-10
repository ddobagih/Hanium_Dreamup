#!/usr/bin/env python3
"""Safely project or publish the administrative FP008 seq47 snapshot reconcile."""

from __future__ import annotations

import argparse
import base64
import copy
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
from types import MappingProxyType
import stat
import sys
from typing import Any, Mapping, Sequence

from cryptography.exceptions import InvalidSignature, UnsupportedAlgorithm
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp008_goal_seq45_46_20260803 as pinned
from scripts import apply_walksafe_fp008_goal_started_seq47_20260803 as secure_writer
from scripts import check_walksafe_project_continuation_v2_4 as continuation


CHECKPOINT = Path("docs/control/walksafe-project-continuation-checkpoint.json")
ADDED_AUTHORITY_SCHEMA = "walksafe.fp008-reconcile-added-authority.v1"
ADDED_AUTHORITY_OPERATION = "walksafe.fp008.seq47-snapshot-reconcile.v1"
ADDED_AUTHORITY_SIGNATURE_ALGORITHM = "Ed25519"
ADDED_AUTHORITY_SIGNATURE_DOMAIN = (
    b"walksafe.fp008-reconcile-added-authority.ed25519.v1"
)
REVIEWER_PUBLIC_KEY_SPKI_DER_BASE64 = (
    "MCowBQYDK2VwAyEAwGejDsoTqPfsjmT2fj/71Q6hy9wsCTOZJurVM1HvVu0="
)
REVIEWER_PUBLIC_KEY_SPKI_SHA256 = (
    "fc45ca556087ab65e7af93aa65fa528c63bbb9cd9c87c4ae868bbd2b24deedba"
)
REVIEWER_PUBLIC_KEY_SPKI_BYTE_COUNT = 44
ED25519_SIGNATURE_BYTE_COUNT = 64
REPOSITORY_UUID = "b7ac18d0-7198-5ef3-a59a-3b3a07118c47"
SOURCE_CHECKPOINT_SHA256 = (
    "95e54f48567e2ca542676b6b9f9443c4ef027864ff8be9440de4a49fd995b119"
)
SOURCE_CHECKPOINT_BYTE_COUNT = 1_530_222
SOURCE_SEQUENCE = 47
SOURCE_EVENT_SHA256 = (
    "82ad77e33eaa55530f53f5ee315807ef66e21fbfc8be2511b004abe99505db90"
)
SOURCE_CONTENT_SET_SHA256 = (
    "ce248fbded6eb44d66b9c6ecd7efaaca2b4c41362f8b1bd5aff22bb862da5455"
)
MANAGED_PATH_COUNT = 619
PATH_SET_SHA256 = (
    "df3eb284c438e99447c78cd9060cfd4fb72c627ac683198df64fd33b59cd3735"
)
AUTHORIZED_EXISTING_DELTAS = {
    "scripts/apply_walksafe_fp008_goal_seq45_46_20260803.py": {
        "source_sha256": "7016d10d754eccbb8faab21a06b745a8507705aca91abe683c22aa3269ffeadd",
        "candidate_sha256": "ac7a9a1344f1bfcbf1a6f60a8f1675e91046a9e6eec4ffa7839c7d50108a7fd8",
    },
    "scripts/apply_walksafe_fp008_goal_started_seq47_20260803.py": {
        "source_sha256": "90455181f2351a7d3c517601d8f1cfe5519e065f031a77e728f1b13dba74a7f6",
        "candidate_sha256": "a36e624c11b044c2035cea5befc011a20c709a9a100eb1a9f3e8fdd4fb0a0a3a",
    },
    "scripts/materialize_walksafe_fp008_goal_seq45_46_20260803.py": {
        "source_sha256": "fe993fad5c9a710951ec99880b0e8a887c1d6633003bc741f19a8deb4304964a",
        "candidate_sha256": "0678de478586d23d04b5ecd5858b58d88c2150e174515b53c748a70f70e9a72f",
    },
    "scripts/run_walksafe_fp008_goal_start_gate_20260803.py": {
        "source_sha256": "13b581e74543fe2d1e4a6f6bc4b0964f09c6e992e70a549276ae9b280366fa42",
        "candidate_sha256": "a735b5f99d0144dc5cdb07ab9da5f1d317e2221863b4a1d835cecee9accdfc85",
    },
    "scripts/check_walksafe_project_continuation_v2_4.py": {
        "source_sha256": "8df411669773e614a6b66b38ba59b5649614931a14849d791321768d238e2364",
        "candidate_sha256": "09dcc2e7a16ab964838e5fe259f56f0be5a94f52391959a1945692aa101caa4f",
    },
    "scripts/check_walksafe_goal_graph_v2_4.py": {
        "source_sha256": "ebfb7b905834f13246935905c58fa527d29bdc0011c85a88e1c9acc67b71fff9",
        "candidate_sha256": "da543bb7e5503f0bc82dccd2b1b422ed3584eaffe8e38cff3e504c9712a78ed7",
    },
    "scripts/run_walksafe_test_layers_20260711.sh": {
        "source_sha256": "993fd8edb96c69aa2e7f35cc0989ec2664d7a5ce1a3b3f7dc41a33558a263108",
        "candidate_sha256": "78747667b148504a70b5a1b793249fb47f75abfa5cdadf02aaba7cbdd58412b9",
    },
    "tests/test_walksafe_project_continuation_v2_4.py": {
        "source_sha256": "92f9a6fea8a57e3e1ae846ae1fa1ef7966b255f88e88fbde06fa46c8d42ca234",
        "candidate_sha256": "659debe13ab754d4fc2015a1227e7292ea67cbda434608889a0cdd8e81a64563",
    },
    "tests/test_walksafe_goal_graph_v2_4.py": {
        "source_sha256": "0b35f8a2933daa7fb7dc039f203408893add39fe13333f9a1f0bcbea19e3e6af",
        "candidate_sha256": "eb7f816fbe1b02e082055ba09d6eda6532423590dc1dc08f2e406b058d83e759",
    },
    "tests/test_walksafe_fp008_goal_seq45_46_20260803.py": {
        "source_sha256": "ee97bb82552f4fc509cec641a7f07adb81ea62d838b0a1379c5b32faf12e3707",
        "candidate_sha256": "41b4fd8e455e1ee9d973afb157b35d41e236fb5122206d4afb5e37244fd04367",
    },
    "tests/test_walksafe_fp008_goal_started_seq47_20260803.py": {
        "source_sha256": "488715062b71f63615b85609c01f069c4c1136ac0eb7d8e5fc805e1ea02417f9",
        "candidate_sha256": "8fa88a9fbc1d4261bd6f7f567e61e6a57133cb0dd1aaf3a4fd48f8407391638a",
    },
}
ADDED_MANAGED_PATHS = (
    "scripts/apply_walksafe_fp008_work_session_resumed_seq48_20260809.py",
    "scripts/reconcile_walksafe_fp008_session_snapshot_seq47_20260808.py",
    "scripts/run_walksafe_fp008_session_resume_gate_20260809.py",
    "tests/test_walksafe_fp008_session_resume_gate_20260809.py",
    "tests/test_walksafe_fp008_session_snapshot_reconcile_20260808.py",
    "tests/test_walksafe_fp008_work_session_resumed_seq48_20260809.py",
)
SIGNED_EXTERNAL_ALIAS_PATHS = {
    # Exact frozen topology: the retained repo entry plus these two distinct
    # directory entries equals st_nlink=3, proving that no fourth link exists.
    "scripts/check_walksafe_project_continuation_v2_4.py": (
        "/home/ddobagi/Code/walksafe-v24-tests-stage-j22XpI/scripts/check_walksafe_project_continuation_v2_4.py",
        "/home/ddobagi/.cache/walksafe-fp011-seq7-v24-g8rctyj2/scripts/check_walksafe_project_continuation_v2_4.py",
    ),
}
PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4"
FOCUS_GOAL_ID = "WS-GOAL-EPIC-03-FP-008-R001"
EPIC03_GOAL_ID = "WS-GOAL-EPIC-03"
EPIC12_GOAL_ID = "WS-GOAL-EPIC-12"
ALLOWED_MUTATIONS = (
    "session_handoff.changed_files",
    "session_handoff.source_commit_or_snapshot.content_set_sha256",
    "session_handoff.source_commit_or_snapshot.file_count",
    "session_handoff.source_commit_or_snapshot.path_set_sha256",
    "working_tree_snapshot.content_set_sha256",
    "working_tree_snapshot.managed_changed_path_count",
    "working_tree_snapshot.managed_changed_paths",
    "working_tree_snapshot.path_set_sha256",
)


class ReconcileError(RuntimeError):
    """The administrative snapshot reconcile is not safe to project or publish."""


class ReconcilePostCommitUncertain(ReconcileError):
    """The reconcile may have committed and requires exact-state recovery."""


@dataclass
class PreparedReconcile:
    root: Path
    source_bytes: bytes
    source: dict[str, Any]
    projected: dict[str, Any]
    projected_bytes: bytes
    source_authority: secure_writer.SourcePublicationAuthority
    cohort: "ReconcileCohort"
    added_authority: "AddedAuthorityManifestGuard"


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ReconcileError(message)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _directory_authority(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
    )


def _file_authority(metadata: os.stat_result) -> tuple[int, ...]:
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


def _authority_directory_identity(metadata: os.stat_result) -> tuple[int, ...]:
    """Stable physical identity for an out-of-repository ancestor directory."""
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
    )


def _read_fd_bytes(descriptor: int, size: int) -> bytes:
    chunks: list[bytes] = []
    offset = 0
    while offset < size:
        chunk = os.pread(descriptor, min(1024 * 1024, size - offset), offset)
        _require(bool(chunk), "authority manifest became short while reading")
        chunks.append(chunk)
        offset += len(chunk)
    _require(not os.pread(descriptor, 1, size), "authority manifest grew while reading")
    return b"".join(chunks)


def _reject_duplicate_json_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, child in pairs:
        if key in value:
            raise ReconcileError(f"authority manifest has duplicate key: {key}")
        value[key] = child
    return value


def authority_json_bytes(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def authority_signature_envelope(raw_manifest: bytes) -> bytes:
    _require(
        len(raw_manifest) < 1 << 64,
        "added authority manifest is too large for the signature envelope",
    )
    return (
        ADDED_AUTHORITY_SIGNATURE_DOMAIN
        + b"\0"
        + len(raw_manifest).to_bytes(8, "big")
        + raw_manifest
    )


def _reviewer_public_key() -> Ed25519PublicKey:
    try:
        encoded = base64.b64decode(
            REVIEWER_PUBLIC_KEY_SPKI_DER_BASE64,
            validate=True,
        )
    except (ValueError, TypeError) as exc:
        raise ReconcileError("reviewer public key base64 is malformed") from exc
    _require(
        len(encoded) == REVIEWER_PUBLIC_KEY_SPKI_BYTE_COUNT
        and sha256_bytes(encoded) == REVIEWER_PUBLIC_KEY_SPKI_SHA256,
        "reviewer public key SPKI authority differs",
    )
    try:
        public_key = serialization.load_der_public_key(encoded)
    except (ValueError, UnsupportedAlgorithm) as exc:
        raise ReconcileError("reviewer public key SPKI is invalid") from exc
    _require(
        isinstance(public_key, Ed25519PublicKey),
        "reviewer public key is not Ed25519",
    )
    canonical = public_key.public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    _require(canonical == encoded, "reviewer public key SPKI is not canonical")
    return public_key


def _verify_reviewer_signature(raw_manifest: bytes, signature: bytes) -> None:
    _require(
        len(signature) == ED25519_SIGNATURE_BYTE_COUNT,
        "added authority detached signature byte count differs",
    )
    try:
        _reviewer_public_key().verify(
            signature,
            authority_signature_envelope(raw_manifest),
        )
    except InvalidSignature as exc:
        raise ReconcileError(
            "added authority detached reviewer signature is invalid"
        ) from exc


def _freeze_json(value: Any) -> Any:
    if isinstance(value, dict):
        return MappingProxyType({key: _freeze_json(child) for key, child in value.items()})
    if isinstance(value, list):
        return tuple(_freeze_json(child) for child in value)
    return value


def _thaw_json(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {key: _thaw_json(child) for key, child in value.items()}
    if isinstance(value, tuple):
        return [_thaw_json(child) for child in value]
    return value


class DetachedSignatureGuard:
    """Retain the exact external Ed25519 signature and its pathname authority."""

    def __init__(
        self,
        *,
        root: Path,
        path: Path,
        directory_descriptors: list[int],
        directory_names: tuple[str, ...],
        directory_identities: tuple[tuple[int, ...], ...],
        descriptor: int,
        file_identity: tuple[int, ...],
        raw_bytes: bytes,
        sha256: str,
    ) -> None:
        self.root = root
        self.path = path
        self.directory_descriptors = directory_descriptors
        self.directory_names = directory_names
        self.directory_identities = directory_identities
        self.descriptor: int | None = descriptor
        self.file_identity = file_identity
        self.raw_bytes = raw_bytes
        self.sha256 = sha256

    @classmethod
    def capture(
        cls,
        root: Path,
        path: Path,
        expected_sha256: str,
        expected_byte_count: int,
    ) -> "DetachedSignatureGuard":
        root = root.resolve(strict=True)
        _require(path.is_absolute(), "authority signature path must be absolute")
        _require(".." not in path.parts, "authority signature path contains '..'")
        _require(
            len(expected_sha256) == 64
            and expected_sha256 == expected_sha256.lower()
            and all(character in "0123456789abcdef" for character in expected_sha256),
            "authority signature SHA-256 literal is malformed",
        )
        _require(
            type(expected_byte_count) is int
            and expected_byte_count == ED25519_SIGNATURE_BYTE_COUNT,
            "authority signature byte-count literal differs",
        )
        parts = path.parts
        _require(
            len(parts) >= 3 and parts[0] == os.sep,
            "authority signature must name a file below a private directory",
        )
        directory_names = tuple(parts[1:-1])
        leaf_name = parts[-1]
        _require(
            leaf_name not in ("", ".", ".."),
            "authority signature leaf is unsafe",
        )
        directory_flags = (
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        file_flags = (
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
            | getattr(os, "O_NOATIME", 0)
        )
        directories: list[int] = []
        identities: list[tuple[int, ...]] = []
        descriptor: int | None = None
        try:
            directories.append(os.open(os.sep, directory_flags))
            identities.append(
                _authority_directory_identity(os.fstat(directories[-1]))
            )
            for name in directory_names:
                _require(
                    name not in ("", ".", ".."),
                    "authority signature directory component is unsafe",
                )
                directories.append(
                    os.open(name, directory_flags, dir_fd=directories[-1])
                )
                identities.append(
                    _authority_directory_identity(os.fstat(directories[-1]))
                )
            root_identity = _authority_directory_identity(root.lstat())[:2]
            _require(
                all(identity[:2] != root_identity for identity in identities),
                "authority signature must be outside the repository",
            )
            parent = os.fstat(directories[-1])
            _require(
                stat.S_ISDIR(parent.st_mode)
                and stat.S_IMODE(parent.st_mode) == 0o700
                and parent.st_uid == os.geteuid()
                and parent.st_gid == os.getegid(),
                "authority signature directory must be private 0700 and caller-owned",
            )
            descriptor = os.open(leaf_name, file_flags, dir_fd=directories[-1])
            metadata = os.fstat(descriptor)
            identity = _file_authority(metadata)
            named = os.stat(
                leaf_name,
                dir_fd=directories[-1],
                follow_symlinks=False,
            )
            _require(
                stat.S_ISREG(metadata.st_mode)
                and stat.S_IMODE(metadata.st_mode) == 0o600
                and metadata.st_uid == os.geteuid()
                and metadata.st_gid == os.getegid()
                and metadata.st_nlink == 1
                and _file_authority(named) == identity,
                "authority signature must be private 0600, singly linked, and caller-owned",
            )
            _require(
                metadata.st_size == expected_byte_count,
                "authority signature byte count differs",
            )
            raw_bytes = _read_fd_bytes(descriptor, metadata.st_size)
            _require(
                sha256_bytes(raw_bytes) == expected_sha256,
                "authority signature SHA-256 differs",
            )
            guard = cls(
                root=root,
                path=path,
                directory_descriptors=directories,
                directory_names=directory_names,
                directory_identities=tuple(identities),
                descriptor=descriptor,
                file_identity=identity,
                raw_bytes=raw_bytes,
                sha256=expected_sha256,
            )
            guard.verify()
            return guard
        except BaseException:
            if descriptor is not None:
                os.close(descriptor)
            for directory_descriptor in reversed(directories):
                os.close(directory_descriptor)
            raise

    def _verify_directories(self) -> None:
        root_named = os.stat(os.sep, follow_symlinks=False)
        _require(
            _authority_directory_identity(root_named) == self.directory_identities[0]
            and _authority_directory_identity(os.fstat(self.directory_descriptors[0]))
            == self.directory_identities[0],
            "authority signature root ancestor changed",
        )
        for index, name in enumerate(self.directory_names, start=1):
            named = os.stat(
                name,
                dir_fd=self.directory_descriptors[index - 1],
                follow_symlinks=False,
            )
            expected = self.directory_identities[index]
            _require(
                stat.S_ISDIR(named.st_mode)
                and _authority_directory_identity(named) == expected
                and _authority_directory_identity(os.fstat(self.directory_descriptors[index]))
                == expected,
                "authority signature ancestor identity changed",
            )
        parent = os.fstat(self.directory_descriptors[-1])
        _require(
            stat.S_IMODE(parent.st_mode) == 0o700
            and parent.st_uid == os.geteuid()
            and parent.st_gid == os.getegid(),
            "authority signature directory authority changed",
        )

    def verify(self) -> None:
        _require(self.descriptor is not None, "authority signature guard is closed")
        self._verify_directories()
        named = os.stat(
            self.path.name,
            dir_fd=self.directory_descriptors[-1],
            follow_symlinks=False,
        )
        opened = os.fstat(self.descriptor)
        _require(
            _file_authority(named) == self.file_identity
            and _file_authority(opened) == self.file_identity,
            "authority signature identity changed",
        )
        _require(
            _read_fd_bytes(self.descriptor, self.file_identity[6]) == self.raw_bytes,
            "authority signature bytes changed",
        )
        named_after = os.stat(
            self.path.name,
            dir_fd=self.directory_descriptors[-1],
            follow_symlinks=False,
        )
        _require(
            _file_authority(named_after) == self.file_identity
            and _file_authority(os.fstat(self.descriptor)) == self.file_identity,
            "authority signature terminal identity changed",
        )
        self._verify_directories()
        _require(
            sha256_bytes(self.raw_bytes) == self.sha256,
            "retained authority signature digest differs",
        )

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        descriptor, self.descriptor = self.descriptor, None
        if descriptor is not None:
            try:
                os.close(descriptor)
            except BaseException as exc:
                first = exc
        while self.directory_descriptors:
            directory_descriptor = self.directory_descriptors.pop()
            try:
                os.close(directory_descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc
        if primary is None and first is not None:
            raise first


class AddedAuthorityManifestGuard:
    """Retain the independent review authority from prepare through terminal state."""

    def __init__(
        self,
        *,
        root: Path,
        path: Path,
        directory_descriptors: list[int],
        directory_names: tuple[str, ...],
        directory_identities: tuple[tuple[int, ...], ...],
        descriptor: int,
        file_identity: tuple[int, ...],
        raw_bytes: bytes,
        document: Mapping[str, Any],
        sha256: str,
        signature: DetachedSignatureGuard,
    ) -> None:
        self.root = root
        self.path = path
        self.directory_descriptors = directory_descriptors
        self.directory_names = directory_names
        self.directory_identities = directory_identities
        self.descriptor: int | None = descriptor
        self.file_identity = file_identity
        self.raw_bytes = raw_bytes
        self.document = document
        self.sha256 = sha256
        self.signature = signature

    @classmethod
    def capture(
        cls,
        root: Path,
        path: Path,
        expected_sha256: str,
        expected_byte_count: int,
        signature_path: Path,
        expected_signature_sha256: str,
        expected_signature_byte_count: int,
    ) -> "AddedAuthorityManifestGuard":
        root = root.resolve(strict=True)
        _require(path.is_absolute(), "added authority manifest path must be absolute")
        _require(".." not in path.parts, "added authority manifest path contains '..'")
        _require(
            len(expected_sha256) == 64
            and expected_sha256 == expected_sha256.lower()
            and all(character in "0123456789abcdef" for character in expected_sha256),
            "added authority SHA-256 literal is malformed",
        )
        _require(
            type(expected_byte_count) is int and expected_byte_count > 0,
            "added authority byte-count literal is malformed",
        )
        parts = path.parts
        _require(len(parts) >= 3 and parts[0] == os.sep, "authority path must name a file below an authority directory")
        directory_names = tuple(parts[1:-1])
        leaf_name = parts[-1]
        _require(leaf_name not in ("", ".", ".."), "authority manifest leaf is unsafe")
        directory_flags = (
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        file_flags = (
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
            | getattr(os, "O_NOATIME", 0)
        )
        directories: list[int] = []
        identities: list[tuple[int, ...]] = []
        descriptor: int | None = None
        signature: DetachedSignatureGuard | None = None
        try:
            directories.append(os.open(os.sep, directory_flags))
            identities.append(_authority_directory_identity(os.fstat(directories[-1])))
            for name in directory_names:
                _require(name not in ("", ".", ".."), "authority directory component is unsafe")
                directories.append(os.open(name, directory_flags, dir_fd=directories[-1]))
                identities.append(_authority_directory_identity(os.fstat(directories[-1])))
            root_identity = _authority_directory_identity(root.lstat())[:2]
            _require(
                all(identity[:2] != root_identity for identity in identities),
                "added authority manifest must be outside the repository",
            )
            parent = os.fstat(directories[-1])
            _require(
                stat.S_ISDIR(parent.st_mode)
                and stat.S_IMODE(parent.st_mode) == 0o700
                and parent.st_uid == os.geteuid()
                and parent.st_gid == os.getegid(),
                "added authority directory must be private 0700 and owned by the caller",
            )
            descriptor = os.open(leaf_name, file_flags, dir_fd=directories[-1])
            metadata = os.fstat(descriptor)
            identity = _file_authority(metadata)
            named = os.stat(leaf_name, dir_fd=directories[-1], follow_symlinks=False)
            _require(
                stat.S_ISREG(metadata.st_mode)
                and stat.S_IMODE(metadata.st_mode) == 0o600
                and metadata.st_uid == os.geteuid()
                and metadata.st_gid == os.getegid()
                and metadata.st_nlink == 1
                and _file_authority(named) == identity,
                "added authority manifest must be private 0600, singly linked, and caller-owned",
            )
            _require(metadata.st_size == expected_byte_count, "added authority byte count differs")
            raw_bytes = _read_fd_bytes(descriptor, metadata.st_size)
            _require(sha256_bytes(raw_bytes) == expected_sha256, "added authority SHA-256 differs")
            try:
                parsed = json.loads(raw_bytes, object_pairs_hook=_reject_duplicate_json_pairs)
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise ReconcileError("added authority manifest is not valid JSON") from exc
            _require(isinstance(parsed, dict), "added authority manifest root is not an object")
            _require(
                authority_json_bytes(parsed) == raw_bytes,
                "added authority manifest is not strict canonical JSON",
            )
            signature = DetachedSignatureGuard.capture(
                root,
                signature_path,
                expected_signature_sha256,
                expected_signature_byte_count,
            )
            _require(
                path != signature_path
                and identity != signature.file_identity,
                "manifest and detached signature authorities must be distinct",
            )
            _verify_reviewer_signature(raw_bytes, signature.raw_bytes)
            guard = cls(
                root=root,
                path=path,
                directory_descriptors=directories,
                directory_names=directory_names,
                directory_identities=tuple(identities),
                descriptor=descriptor,
                file_identity=identity,
                raw_bytes=raw_bytes,
                document=_freeze_json(parsed),
                sha256=expected_sha256,
                signature=signature,
            )
            guard.verify()
            return guard
        except BaseException:
            if signature is not None:
                signature.close()
            if descriptor is not None:
                os.close(descriptor)
            for directory_descriptor in reversed(directories):
                os.close(directory_descriptor)
            raise

    def _verify_directories(self) -> None:
        root_named = os.stat(os.sep, follow_symlinks=False)
        _require(
            _authority_directory_identity(root_named) == self.directory_identities[0]
            and _authority_directory_identity(os.fstat(self.directory_descriptors[0]))
            == self.directory_identities[0],
            "added authority root ancestor changed",
        )
        for index, name in enumerate(self.directory_names, start=1):
            named = os.stat(
                name,
                dir_fd=self.directory_descriptors[index - 1],
                follow_symlinks=False,
            )
            expected = self.directory_identities[index]
            _require(
                stat.S_ISDIR(named.st_mode)
                and _authority_directory_identity(named) == expected
                and _authority_directory_identity(os.fstat(self.directory_descriptors[index]))
                == expected,
                "added authority ancestor identity changed",
            )
        parent = os.fstat(self.directory_descriptors[-1])
        _require(
            stat.S_IMODE(parent.st_mode) == 0o700
            and parent.st_uid == os.geteuid()
            and parent.st_gid == os.getegid(),
            "added authority directory authority changed",
        )

    def verify(self) -> None:
        _require(self.descriptor is not None, "added authority manifest guard is closed")
        self._verify_directories()
        named = os.stat(
            self.path.name,
            dir_fd=self.directory_descriptors[-1],
            follow_symlinks=False,
        )
        opened = os.fstat(self.descriptor)
        _require(
            _file_authority(named) == self.file_identity
            and _file_authority(opened) == self.file_identity,
            "added authority manifest identity changed",
        )
        _require(
            _read_fd_bytes(self.descriptor, self.file_identity[6]) == self.raw_bytes,
            "added authority manifest bytes changed",
        )
        named_after = os.stat(
            self.path.name,
            dir_fd=self.directory_descriptors[-1],
            follow_symlinks=False,
        )
        _require(
            _file_authority(named_after) == self.file_identity
            and _file_authority(os.fstat(self.descriptor)) == self.file_identity,
            "added authority manifest terminal identity changed",
        )
        self.signature.verify()
        _verify_reviewer_signature(self.raw_bytes, self.signature.raw_bytes)
        named_terminal = os.stat(
            self.path.name,
            dir_fd=self.directory_descriptors[-1],
            follow_symlinks=False,
        )
        _require(
            _file_authority(named_terminal) == self.file_identity
            and _file_authority(os.fstat(self.descriptor)) == self.file_identity,
            "added authority manifest changed during signature verification",
        )
        self._verify_directories()
        _require(sha256_bytes(self.raw_bytes) == self.sha256, "retained authority digest differs")

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        try:
            self.signature.close(primary)
        except BaseException as exc:
            first = exc
        descriptor, self.descriptor = self.descriptor, None
        if descriptor is not None:
            try:
                os.close(descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc
        while self.directory_descriptors:
            directory_descriptor = self.directory_descriptors.pop()
            try:
                os.close(directory_descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc
        if primary is None and first is not None:
            raise first


class _RetainedDirectoryTree:
    def __init__(
        self,
        root: Path,
        descriptors: dict[Path, int],
        authorities: dict[Path, tuple[int, ...]],
    ) -> None:
        self.root = root
        self.descriptors = descriptors
        self.authorities = authorities

    @classmethod
    def capture(
        cls,
        root: Path,
        paths: list[str],
    ) -> "_RetainedDirectoryTree":
        relatives = {Path(".")}
        for relative in paths:
            value = Path(relative)
            _require(
                not value.is_absolute() and ".." not in value.parts,
                f"managed ancestor path is unsafe: {relative}",
            )
            current = Path(".")
            for part in value.parts[:-1]:
                current /= part
                relatives.add(current)
        flags = (
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        descriptors: dict[Path, int] = {}
        authorities: dict[Path, tuple[int, ...]] = {}
        try:
            for relative in sorted(
                relatives,
                key=lambda value: (len(value.parts), value.as_posix()),
            ):
                descriptor = (
                    os.open(root, flags)
                    if relative == Path(".")
                    else os.open(
                        relative.name,
                        flags,
                        dir_fd=descriptors[relative.parent],
                    )
                )
                descriptors[relative] = descriptor
                metadata = os.fstat(descriptor)
                _require(
                    stat.S_ISDIR(metadata.st_mode)
                    and metadata.st_uid == os.geteuid(),
                    f"managed ancestor authority differs: {relative}",
                )
                authorities[relative] = _directory_authority(metadata)
            tree = cls(root, descriptors, authorities)
            tree.verify()
            return tree
        except BaseException:
            for descriptor in reversed(tuple(descriptors.values())):
                os.close(descriptor)
            raise

    def verify(self) -> None:
        root_relative = Path(".")
        root_named = self.root.lstat()
        _require(
            stat.S_ISDIR(root_named.st_mode)
            and not self.root.is_symlink()
            and _directory_authority(root_named)
            == self.authorities[root_relative]
            and _directory_authority(os.fstat(self.descriptors[root_relative]))
            == self.authorities[root_relative],
            "managed ancestor authority changed: .",
        )
        for relative in sorted(
            (value for value in self.descriptors if value != root_relative),
            key=lambda value: (len(value.parts), value.as_posix()),
        ):
            named = os.stat(
                relative.name,
                dir_fd=self.descriptors[relative.parent],
                follow_symlinks=False,
            )
            opened = os.fstat(self.descriptors[relative])
            expected = self.authorities[relative]
            _require(
                stat.S_ISDIR(named.st_mode)
                and _directory_authority(named) == expected
                and _directory_authority(opened) == expected,
                f"managed ancestor authority changed: {relative}",
            )

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        relatives = sorted(
            self.descriptors,
            key=lambda value: (len(value.parts), value.as_posix()),
            reverse=True,
        )
        for relative in relatives:
            descriptor = self.descriptors.pop(relative)
            try:
                os.close(descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc
        if primary is None and first is not None:
            raise first


class _ExternalAliasPin:
    """Retain one explicitly authorized external hardlink and every ancestor."""

    def __init__(
        self,
        *,
        path: Path,
        directory_names: tuple[str, ...],
        directory_descriptors: list[int],
        directory_authorities: tuple[tuple[int, ...], ...],
        descriptor: int,
        file_authority: tuple[int, ...],
        sha256: str,
    ) -> None:
        self.path = path
        self.directory_names = directory_names
        self.directory_descriptors = directory_descriptors
        self.directory_authorities = directory_authorities
        self.descriptor: int | None = descriptor
        self.file_authority = file_authority
        self.sha256 = sha256

    @property
    def entry_key(self) -> tuple[int, int, str]:
        parent = self.directory_authorities[-1]
        return (parent[0], parent[1], self.path.name)

    @classmethod
    def capture(
        cls,
        path: Path,
        expected_file_authority: tuple[int, ...],
        expected_sha256: str,
    ) -> "_ExternalAliasPin":
        _require(path.is_absolute(), "external inode alias path must be absolute")
        _require(".." not in path.parts, "external inode alias path contains '..'")
        directory_names = tuple(path.parts[1:-1])
        leaf = path.name
        _require(leaf not in ("", ".", ".."), "external inode alias leaf is unsafe")
        directory_flags = (
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        file_flags = (
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
            | getattr(os, "O_NOATIME", 0)
        )
        directories: list[int] = []
        authorities: list[tuple[int, ...]] = []
        descriptor: int | None = None
        try:
            directories.append(os.open(os.sep, directory_flags))
            authorities.append(_directory_authority(os.fstat(directories[-1])))
            for name in directory_names:
                _require(
                    name not in ("", ".", ".."),
                    "external inode alias ancestor is unsafe",
                )
                directories.append(
                    os.open(name, directory_flags, dir_fd=directories[-1])
                )
                metadata = os.fstat(directories[-1])
                _require(
                    stat.S_ISDIR(metadata.st_mode),
                    "external inode alias ancestor is not a directory",
                )
                authorities.append(_directory_authority(metadata))
            descriptor = os.open(leaf, file_flags, dir_fd=directories[-1])
            named = os.stat(leaf, dir_fd=directories[-1], follow_symlinks=False)
            opened = os.fstat(descriptor)
            _require(
                stat.S_ISREG(opened.st_mode)
                and _file_authority(named) == expected_file_authority
                and _file_authority(opened) == expected_file_authority,
                f"external inode alias authority differs: {path}",
            )
            _require(
                pinned._digest_fd(descriptor) == expected_sha256,
                f"external inode alias bytes differ: {path}",
            )
            pin = cls(
                path=path,
                directory_names=directory_names,
                directory_descriptors=directories,
                directory_authorities=tuple(authorities),
                descriptor=descriptor,
                file_authority=expected_file_authority,
                sha256=expected_sha256,
            )
            pin.verify()
            return pin
        except BaseException:
            if descriptor is not None:
                os.close(descriptor)
            for directory_descriptor in reversed(directories):
                os.close(directory_descriptor)
            raise

    def _verify_directories(self) -> None:
        root_named = os.stat(os.sep, follow_symlinks=False)
        _require(
            _directory_authority(root_named) == self.directory_authorities[0]
            and _directory_authority(os.fstat(self.directory_descriptors[0]))
            == self.directory_authorities[0],
            "external inode alias root ancestor changed",
        )
        for index, name in enumerate(self.directory_names, start=1):
            named = os.stat(
                name,
                dir_fd=self.directory_descriptors[index - 1],
                follow_symlinks=False,
            )
            expected = self.directory_authorities[index]
            _require(
                stat.S_ISDIR(named.st_mode)
                and _directory_authority(named) == expected
                and _directory_authority(os.fstat(self.directory_descriptors[index]))
                == expected,
                f"external inode alias ancestor changed: {self.path}",
            )

    def verify(self) -> None:
        _require(self.descriptor is not None, "external inode alias pin is closed")
        self._verify_directories()
        named = os.stat(
            self.path.name,
            dir_fd=self.directory_descriptors[-1],
            follow_symlinks=False,
        )
        _require(
            _file_authority(named) == self.file_authority
            and _file_authority(os.fstat(self.descriptor)) == self.file_authority,
            f"external inode alias authority changed: {self.path}",
        )
        _require(
            pinned._digest_fd(self.descriptor) == self.sha256,
            f"external inode alias bytes changed: {self.path}",
        )
        named_after = os.stat(
            self.path.name,
            dir_fd=self.directory_descriptors[-1],
            follow_symlinks=False,
        )
        _require(
            _file_authority(named_after) == self.file_authority
            and _file_authority(os.fstat(self.descriptor)) == self.file_authority,
            f"external inode alias terminal authority changed: {self.path}",
        )
        self._verify_directories()

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        descriptor, self.descriptor = self.descriptor, None
        if descriptor is not None:
            try:
                os.close(descriptor)
            except BaseException as exc:
                first = exc
        while self.directory_descriptors:
            descriptor = self.directory_descriptors.pop()
            try:
                os.close(descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc
        if primary is None and first is not None:
            raise first


class ReconcileCohort:
    def __init__(
        self,
        root: Path,
        managed: pinned.PinnedCohort,
        directory_tree: _RetainedDirectoryTree,
        file_authorities: tuple[tuple[Any, tuple[int, ...]], ...],
        signed_topologies: dict[
            str,
            tuple[tuple[str, ...], tuple[_ExternalAliasPin, ...]],
        ],
    ) -> None:
        self.root = root
        self.managed = managed
        self.directory_tree = directory_tree
        self.file_authorities = file_authorities
        self.signed_topologies = signed_topologies

    @property
    def _pins(self) -> list[Any]:
        return self.managed._pins

    @property
    def paths(self) -> list[str]:
        return [pin.relative for pin in self._pins]

    @property
    def digests(self) -> dict[str, str]:
        return {pin.relative: pin.sha256 for pin in self._pins}

    @property
    def sizes(self) -> dict[str, int]:
        return {pin.relative: pin.identity[-1] for pin in self._pins}

    def signed_file_metadata(self, relative: str) -> dict[str, Any]:
        for pin, authority in self.file_authorities:
            if pin.relative == relative:
                _require(
                    stat.S_ISREG(authority[2]),
                    f"signed candidate file type differs: {relative}",
                )
                managed_aliases, external_aliases = self.signed_topologies[relative]
                return {
                    "bytes": authority[6],
                    "file_type": "regular",
                    "mode": f"{stat.S_IMODE(authority[2]):04o}",
                    "uid": authority[3],
                    "gid": authority[4],
                    "nlink": authority[5],
                    "repository_inode_aliases": list(managed_aliases),
                    "external_inode_aliases": [
                        {
                            "path": str(alias.path),
                            "sha256": alias.sha256,
                            "bytes": alias.file_authority[6],
                            "file_type": "regular",
                            "mode": f"{stat.S_IMODE(alias.file_authority[2]):04o}",
                            "uid": alias.file_authority[3],
                            "gid": alias.file_authority[4],
                            "nlink": alias.file_authority[5],
                            "device": alias.file_authority[0],
                            "inode": alias.file_authority[1],
                            "parent_device": alias.entry_key[0],
                            "parent_inode": alias.entry_key[1],
                            "leaf": alias.entry_key[2],
                        }
                        for alias in external_aliases
                    ],
                }
        raise ReconcileError(f"signed candidate path is not retained: {relative}")

    @classmethod
    def capture(cls, root: Path, paths: list[str]) -> "ReconcileCohort":
        directory_tree = _RetainedDirectoryTree.capture(root, paths)
        managed: pinned.PinnedCohort | None = None
        try:
            managed = pinned.PinnedCohort.capture(root, paths)
            file_authorities: list[tuple[Any, tuple[int, ...]]] = []
            for pin in managed._pins:
                metadata = os.fstat(pin.descriptor)
                _require(
                    stat.S_ISREG(metadata.st_mode)
                    and metadata.st_uid == os.geteuid(),
                    f"managed file authority differs: {pin.relative}",
                )
                file_authorities.append((pin, _file_authority(metadata)))
            authority_by_relative = {
                pin.relative: authority for pin, authority in file_authorities
            }
            signed_topologies: dict[
                str,
                tuple[tuple[str, ...], tuple[_ExternalAliasPin, ...]],
            ] = {}
            required = sorted(
                set(ADDED_MANAGED_PATHS) | set(AUTHORIZED_EXISTING_DELTAS)
            )
            for relative in required:
                target = authority_by_relative[relative]
                managed_aliases = tuple(
                    sorted(
                        candidate
                        for candidate, authority in authority_by_relative.items()
                        if authority[:2] == target[:2]
                    )
                )
                configured = tuple(
                    Path(value)
                    for value in SIGNED_EXTERNAL_ALIAS_PATHS.get(relative, ())
                )
                external_aliases: tuple[_ExternalAliasPin, ...]
                if configured and root == ROOT.resolve(strict=True):
                    _require(
                        len(set(configured)) == len(configured)
                        and target[5] == len(managed_aliases) + len(configured),
                        f"frozen external inode alias topology differs: {relative}",
                    )
                    captured_aliases: list[_ExternalAliasPin] = []
                    try:
                        for alias_path in configured:
                            captured_aliases.append(
                                _ExternalAliasPin.capture(
                                    alias_path,
                                    target,
                                    next(
                                        pin.sha256
                                        for pin, authority in file_authorities
                                        if pin.relative == relative
                                        and authority == target
                                    ),
                                )
                            )
                    except BaseException as exc:
                        for alias in reversed(captured_aliases):
                            alias.close(exc)
                        raise
                    external_aliases = tuple(captured_aliases)
                elif target[5] == len(managed_aliases):
                    external_aliases = ()
                else:
                    _require(
                        bool(configured)
                        and target[5] == len(managed_aliases) + len(configured),
                        f"signed inode alias topology is unresolved: {relative}",
                    )
                    captured_aliases: list[_ExternalAliasPin] = []
                    try:
                        for alias_path in configured:
                            captured_aliases.append(
                                _ExternalAliasPin.capture(
                                    alias_path,
                                    target,
                                    next(
                                        pin.sha256
                                        for pin, authority in file_authorities
                                        if pin.relative == relative
                                        and authority == target
                                    ),
                                )
                            )
                    except BaseException as exc:
                        for alias in reversed(captured_aliases):
                            alias.close(exc)
                        raise
                    external_aliases = tuple(captured_aliases)
                managed_entry_keys = {
                    (
                        directory_tree.authorities[Path(candidate).parent][0],
                        directory_tree.authorities[Path(candidate).parent][1],
                        Path(candidate).name,
                    )
                    for candidate in managed_aliases
                }
                external_entry_keys = {
                    alias.entry_key for alias in external_aliases
                }
                _require(
                    len(managed_entry_keys) == len(managed_aliases)
                    and len(external_entry_keys) == len(external_aliases)
                    and managed_entry_keys.isdisjoint(external_entry_keys)
                    and target[5]
                    == len(managed_entry_keys) + len(external_entry_keys),
                    f"signed inode link count differs from retained aliases: {relative}",
                )
                signed_topologies[relative] = (
                    managed_aliases,
                    external_aliases,
                )
            cohort = cls(
                root,
                managed,
                directory_tree,
                tuple(file_authorities),
                signed_topologies,
            )
            cohort.verify()
            return cohort
        except BaseException as exc:
            if "signed_topologies" in locals():
                for _, external_aliases in signed_topologies.values():
                    for alias in reversed(external_aliases):
                        alias.close(exc)
            if managed is not None:
                managed.close(exc)
            directory_tree.close(exc)
            raise

    def content_set_sha256(self) -> str:
        return self.managed.content_set_sha256()

    def verify(self) -> None:
        self.directory_tree.verify()
        for _, external_aliases in self.signed_topologies.values():
            for alias in external_aliases:
                alias.verify()
        for pin, expected in self.file_authorities:
            relative = Path(pin.relative)
            named = os.stat(
                relative.name,
                dir_fd=self.directory_tree.descriptors[relative.parent],
                follow_symlinks=False,
            )
            _require(
                _file_authority(os.fstat(pin.descriptor)) == expected
                and _file_authority(named) == expected,
                f"managed file authority changed: {pin.relative}",
            )
            _require(
                pinned._digest_fd(pin.descriptor) == pin.sha256,
                f"managed file bytes changed: {pin.relative}",
            )
        for pin, expected in self.file_authorities:
            relative = Path(pin.relative)
            named = os.stat(
                relative.name,
                dir_fd=self.directory_tree.descriptors[relative.parent],
                follow_symlinks=False,
            )
            _require(
                _file_authority(os.fstat(pin.descriptor)) == expected
                and _file_authority(named) == expected,
                f"managed file authority changed: {pin.relative}",
            )
        for relative, (managed_aliases, external_aliases) in self.signed_topologies.items():
            expected = next(
                authority
                for pin, authority in self.file_authorities
                if pin.relative == relative
            )
            _require(
                expected[5] == len(managed_aliases) + len(external_aliases),
                f"signed inode topology count changed: {relative}",
            )
            for alias in external_aliases:
                alias.verify()
        self.directory_tree.verify()

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        for _, external_aliases in reversed(tuple(self.signed_topologies.values())):
            for alias in reversed(external_aliases):
                try:
                    alias.close(primary or first)
                except BaseException as exc:
                    if first is None:
                        first = exc
        try:
            self.managed.close(primary or first)
        except BaseException as exc:
            if first is None:
                first = exc
        try:
            self.directory_tree.close(primary or first)
        except BaseException as exc:
            if first is None:
                first = exc
        if primary is None and first is not None:
            raise first


def signed_authority_rows(cohort: ReconcileCohort) -> dict[str, list[dict[str, Any]]]:
    """Return the exact candidate rows an independent reviewer must sign."""
    cohort.verify()
    required = set(ADDED_MANAGED_PATHS) | set(AUTHORIZED_EXISTING_DELTAS)
    _require(
        required.issubset(cohort.paths),
        "signed authority paths are not all retained",
    )
    added_rows = [
        {
            "path": relative,
            "sha256": cohort.digests[relative],
            **cohort.signed_file_metadata(relative),
        }
        for relative in ADDED_MANAGED_PATHS
    ]
    delta_rows = [
        {
            "path": relative,
            **hashes,
            "sha256": cohort.digests[relative],
            **cohort.signed_file_metadata(relative),
        }
        for relative, hashes in sorted(AUTHORIZED_EXISTING_DELTAS.items())
    ]
    cohort.verify()
    return {
        "added_managed_paths": added_rows,
        "authorized_existing_deltas": delta_rows,
    }


def _content_set_sha256_from_digests(
    paths: list[str],
    digests: dict[str, str],
) -> str:
    value = hashlib.sha256()
    for relative in sorted(paths):
        value.update(relative.encode("utf-8"))
        value.update(b"\0")
        value.update(digests[relative].encode("ascii"))
        value.update(b"\n")
    return value.hexdigest()


def _changed_fields(before: Any, after: Any, prefix: str = "") -> set[str]:
    if isinstance(before, dict) and isinstance(after, dict):
        if set(before) != set(after):
            return {prefix or "<root>"}
        changed: set[str] = set()
        for key in before:
            child = f"{prefix}.{key}" if prefix else key
            changed.update(_changed_fields(before[key], after[key], child))
        return changed
    if isinstance(before, list) and isinstance(after, list):
        return set() if before == after else {prefix}
    return set() if before == after else {prefix}


def _validate_projected_checkpoint(
    root: Path,
    projected: dict[str, Any],
    cohort: ReconcileCohort,
) -> list[str]:
    errors, archive = continuation.validate_frozen_v23_boundary(
        root,
        continuation.V23_ARCHIVE_RELATIVE,
    )
    errors.extend(
        continuation.validate_seq39_canonical_binding_authorization_request(
            root,
            projected,
        )
    )
    errors.extend(
        continuation.validate_seq39_canonical_binding_update(root, projected)
    )
    if archive:
        errors.extend(
            continuation.validate_prepared_checkpoint_projection(
                projected,
                archive,
            )
        )
        errors.extend(
            continuation.validate_transition_replay(
                root,
                projected,
                archive,
                continuation.V24_MANIFEST_RELATIVE,
                expected_prepared_sha256=(
                    continuation.EXPECTED_V24_PREPARED_EVENT_SHA256
                ),
                expected_authorization_sha256=(
                    continuation.EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256
                ),
            )
        )
    snapshot = projected.get("working_tree_snapshot")
    state = projected.get("goal_execution")
    if not isinstance(snapshot, dict):
        errors.append("v2.4 working_tree_snapshot is missing")
    elif not isinstance(state, dict):
        errors.append("v2.4 goal_execution is missing")
    else:
        paths = snapshot.get("managed_changed_paths")
        if (
            not isinstance(paths, list)
            or not all(isinstance(path, str) for path in paths)
            or paths != sorted(set(paths))
        ):
            errors.append("v2.4 managed changed path list is malformed")
        else:
            required = set(continuation.EXPECTED_CONTROLLED_PATHS) | set(
                continuation.expected_goal_paths(state)
            )
            if not required.issubset(paths):
                errors.append(
                    "v2.4 managed changed paths omit activation or dynamic Goal paths"
                )
            if continuation.V24_CHECKPOINT_RELATIVE.as_posix() in paths:
                errors.append("v2.4 working snapshot includes its checkpoint")
            if any(
                path.startswith("docs/control/execution/goal-gates/")
                for path in paths
            ):
                errors.append("v2.4 working snapshot includes direct gate evidence")
            if snapshot.get("managed_changed_path_count") != len(paths):
                errors.append("v2.4 managed changed path count differs")
            retained_path_hash = hashlib.sha256(
                ("\n".join(sorted(paths)) + "\n").encode("utf-8")
            ).hexdigest()
            if snapshot.get("path_set_sha256") != retained_path_hash:
                errors.append("v2.4 working snapshot path-set SHA-256 differs")
            if snapshot.get("content_set_sha256") != cohort.content_set_sha256():
                errors.append("v2.4 working snapshot content-set SHA-256 differs")
    return errors


def _require_exact_seq47(source: dict[str, Any]) -> list[str]:
    state = source.get("goal_execution")
    snapshot = source.get("working_tree_snapshot")
    handoff = source.get("session_handoff")
    _require(isinstance(state, dict), "seq47 goal_execution is missing")
    _require(isinstance(snapshot, dict), "seq47 working_tree_snapshot is missing")
    _require(isinstance(handoff, dict), "seq47 session_handoff is missing")
    history = state.get("transition_history")
    statuses = state.get("status_by_goal")
    _require(
        isinstance(history, list)
        and len(history) == SOURCE_SEQUENCE
        and isinstance(history[-1], dict)
        and history[-1].get("sequence") == SOURCE_SEQUENCE
        and history[-1].get("event_type") == "GOAL_STARTED"
        and history[-1].get("event_sha256") == SOURCE_EVENT_SHA256
        and state.get("transition_history_anchor_sha256") == SOURCE_EVENT_SHA256,
        "source is not the exact seq47 history tail",
    )
    _require(
        state.get("package_id") == PACKAGE_ID
        and state.get("package_status") == "ACTIVE"
        and state.get("activation_status") == "ACTIVE"
        and state.get("focus_goal_id") == FOCUS_GOAL_ID
        and isinstance(statuses, dict)
        and statuses.get(FOCUS_GOAL_ID) == "IN_PROGRESS"
        and statuses.get(EPIC03_GOAL_ID) == "READY"
        and statuses.get(EPIC12_GOAL_ID) == "READY",
        "seq47 runtime status boundary differs",
    )
    paths = snapshot.get("managed_changed_paths")
    changed_files = handoff.get("changed_files")
    source_snapshot = handoff.get("source_commit_or_snapshot")
    _require(
        isinstance(paths, list)
        and paths == sorted(set(paths))
        and len(paths) == MANAGED_PATH_COUNT
        and snapshot.get("managed_changed_path_count") == MANAGED_PATH_COUNT
        and snapshot.get("path_set_sha256") == PATH_SET_SHA256
        and snapshot.get("content_set_sha256") == SOURCE_CONTENT_SET_SHA256,
        "seq47 managed snapshot boundary differs",
    )
    _require(
        changed_files == paths
        and isinstance(source_snapshot, dict)
        and source_snapshot.get("file_count") == MANAGED_PATH_COUNT
        and source_snapshot.get("path_set_sha256") == PATH_SET_SHA256
        and source_snapshot.get("content_set_sha256") == SOURCE_CONTENT_SET_SHA256,
        "seq47 handoff snapshot mirror differs",
    )
    return paths


def _normalized_manifest_path(value: Any, label: str) -> str:
    _require(type(value) is str and bool(value), f"{label} is not a path string")
    path = Path(value)
    _require(
        not path.is_absolute()
        and ".." not in path.parts
        and "." not in path.parts
        and "\\" not in value
        and path.as_posix() == value,
        f"{label} is not a normalized repository-relative path",
    )
    return value


def _require_exact_json(actual: Any, expected: Any, label: str) -> None:
    if isinstance(expected, dict):
        _require(isinstance(actual, Mapping), f"{label} is not an object")
        _require(set(actual) == set(expected), f"{label} field set differs")
        for key, expected_child in expected.items():
            _require_exact_json(actual[key], expected_child, f"{label}.{key}")
        return
    if isinstance(expected, list):
        _require(isinstance(actual, (list, tuple)), f"{label} is not an array")
        _require(len(actual) == len(expected), f"{label} length differs")
        for index, (actual_child, expected_child) in enumerate(zip(actual, expected)):
            _require_exact_json(actual_child, expected_child, f"{label}[{index}]")
        return
    _require(
        type(actual) is type(expected) and actual == expected,
        f"{label} differs",
    )


def _project_from_retained(
    root: Path,
    source: dict[str, Any],
    cohort: ReconcileCohort,
) -> dict[str, Any]:
    source_paths = _require_exact_seq47(source)
    _require(
        set(AUTHORIZED_EXISTING_DELTAS).issubset(source_paths)
        and not set(ADDED_MANAGED_PATHS).intersection(source_paths),
        "authorized reconcile membership boundary differs",
    )
    candidate_paths = sorted(set(source_paths) | set(ADDED_MANAGED_PATHS))
    cohort.verify()
    _require(
        cohort.paths == candidate_paths,
        "retained candidate membership differs",
    )
    candidate_digests = cohort.digests
    for relative, expected in AUTHORIZED_EXISTING_DELTAS.items():
        _require(
            candidate_digests[relative] == expected["candidate_sha256"],
            f"authorized candidate SHA-256 differs: {relative}",
        )
    reconstructed_source_digests = {
        relative: candidate_digests[relative] for relative in source_paths
    }
    for relative, expected in AUTHORIZED_EXISTING_DELTAS.items():
        reconstructed_source_digests[relative] = expected["source_sha256"]
    _require(
        _content_set_sha256_from_digests(
            source_paths,
            reconstructed_source_digests,
        )
        == SOURCE_CONTENT_SET_SHA256,
        "an existing managed path changed outside the authorized delta set",
    )
    path_hash = hashlib.sha256(
        ("\n".join(candidate_paths) + "\n").encode("utf-8")
    ).hexdigest()
    content_hash = _content_set_sha256_from_digests(
        candidate_paths,
        candidate_digests,
    )
    _require(
        content_hash == cohort.content_set_sha256(),
        "candidate retained content set differs",
    )
    projected = copy.deepcopy(source)
    snapshot = projected["working_tree_snapshot"]
    snapshot["managed_changed_paths"] = candidate_paths
    snapshot["managed_changed_path_count"] = len(candidate_paths)
    snapshot["path_set_sha256"] = path_hash
    snapshot["content_set_sha256"] = content_hash
    handoff = projected["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(candidate_paths)
    handoff_source = handoff["source_commit_or_snapshot"]
    handoff_source["file_count"] = len(candidate_paths)
    handoff_source["path_set_sha256"] = path_hash
    handoff_source["content_set_sha256"] = content_hash
    _require(
        _changed_fields(source, projected) == set(ALLOWED_MUTATIONS),
        "reconcile projection changes fields outside the administrative hash mirrors",
    )
    cohort.verify()
    errors = _validate_projected_checkpoint(root, projected, cohort)
    _require(not errors, "reconciled checkpoint is invalid: " + "; ".join(errors))
    cohort.verify()
    return projected


def authority_document(
    root: Path,
    projected: dict[str, Any],
    projected_bytes: bytes,
    cohort: ReconcileCohort,
    *,
    approval_id: str,
    generation_nonce: str,
) -> dict[str, Any]:
    """Build the exact canonical document shape that the reviewer must sign."""
    signed_rows = signed_authority_rows(cohort)
    return {
        "schema_version": ADDED_AUTHORITY_SCHEMA,
        "operation_version": ADDED_AUTHORITY_OPERATION,
        "repository": {
            "uuid": REPOSITORY_UUID,
            "canonical_root": str(root),
        },
        "approval_id": approval_id,
        "generation_nonce": generation_nonce,
        "reviewer": {
            "key_fingerprint_sha256": REVIEWER_PUBLIC_KEY_SPKI_SHA256,
            "signature_algorithm": ADDED_AUTHORITY_SIGNATURE_ALGORITHM,
            "signature_domain": ADDED_AUTHORITY_SIGNATURE_DOMAIN.decode("ascii"),
        },
        "base_checkpoint": {
            "sha256": SOURCE_CHECKPOINT_SHA256,
            "size": SOURCE_CHECKPOINT_BYTE_COUNT,
        },
        "added_managed_paths": signed_rows["added_managed_paths"],
        "authorized_existing_deltas": signed_rows[
            "authorized_existing_deltas"
        ],
        "candidate_snapshot": {
            "managed_path_count": len(cohort.paths),
            "path_set_sha256": projected["working_tree_snapshot"][
                "path_set_sha256"
            ],
            "content_set_sha256": cohort.content_set_sha256(),
        },
        "candidate_checkpoint": {
            "sha256": sha256_bytes(projected_bytes),
            "size": len(projected_bytes),
        },
    }


def _validate_added_authority(
    root: Path,
    source: dict[str, Any],
    projected: dict[str, Any],
    projected_bytes: bytes,
    cohort: ReconcileCohort,
    authority: AddedAuthorityManifestGuard,
) -> None:
    authority.verify()
    _require(authority.root == root, "added authority repository binding differs")
    document = authority.document
    approval_id = document.get("approval_id")
    generation_nonce = document.get("generation_nonce")
    _require(
        type(approval_id) is str
        and len(approval_id) == 64
        and approval_id == approval_id.lower()
        and all(character in "0123456789abcdef" for character in approval_id),
        "added authority approval id is malformed",
    )
    _require(
        type(generation_nonce) is str
        and len(generation_nonce) == 64
        and generation_nonce == generation_nonce.lower()
        and all(character in "0123456789abcdef" for character in generation_nonce),
        "added authority generation nonce is malformed",
    )
    _require(
        approval_id != generation_nonce,
        "added authority approval id and generation nonce must be distinct",
    )
    added = document.get("added_managed_paths")
    deltas = document.get("authorized_existing_deltas")
    _require(isinstance(added, (list, tuple)), "added authority managed paths are missing")
    _require(isinstance(deltas, (list, tuple)), "added authority deltas are missing")
    normalized_paths: list[str] = []
    for index, entry in enumerate((*added, *deltas)):
        _require(isinstance(entry, Mapping), "added authority path entry is not an object")
        normalized_paths.append(
            _normalized_manifest_path(entry.get("path"), f"authority path[{index}]")
        )
    _require(
        len({value.casefold() for value in normalized_paths}) == len(normalized_paths),
        "added authority paths contain a case-fold alias",
    )
    expected = authority_document(
        root,
        projected,
        projected_bytes,
        cohort,
        approval_id=approval_id,
        generation_nonce=generation_nonce,
    )
    _require_exact_json(document, expected, "added authority manifest")
    _require(
        _changed_fields(source, projected) == set(ALLOWED_MUTATIONS),
        "authority candidate mutation boundary differs",
    )
    cohort.verify()
    authority.verify()


def project(
    root: Path,
    source: dict[str, Any],
    cohort: ReconcileCohort,
    authority: AddedAuthorityManifestGuard,
) -> dict[str, Any]:
    root = root.resolve(strict=True)
    projected = _project_from_retained(root, source, cohort)
    projected_bytes = json_bytes(projected)
    _validate_added_authority(
        root,
        source,
        projected,
        projected_bytes,
        cohort,
        authority,
    )
    return projected


def unsigned_authority_template(root: Path) -> dict[str, Any]:
    """Build reviewer input only; this never authorizes, prepares, or writes."""
    root = root.resolve(strict=True)
    source_bytes, source_authority = secure_writer._capture_checkpoint_authority(root)
    _require(
        len(source_bytes) == SOURCE_CHECKPOINT_BYTE_COUNT
        and sha256_bytes(source_bytes) == SOURCE_CHECKPOINT_SHA256,
        "unsigned template requires the exact seq47 source checkpoint",
    )
    try:
        source = json.loads(source_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ReconcileError("unsigned template source is not valid JSON") from exc
    _require(isinstance(source, dict), "unsigned template source root differs")
    source_paths = _require_exact_seq47(source)
    cohort = ReconcileCohort.capture(
        root,
        sorted(set(source_paths) | set(ADDED_MANAGED_PATHS)),
    )
    primary: BaseException | None = None
    try:
        projected = _project_from_retained(root, source, cohort)
        projected_bytes = json_bytes(projected)
        manifest = authority_document(
            root,
            projected,
            projected_bytes,
            cohort,
            approval_id="REPLACE_WITH_64_LOWERCASE_HEX_APPROVAL_ID",
            generation_nonce="REPLACE_WITH_64_LOWERCASE_HEX_GENERATION_NONCE",
        )
        manifest_bytes = authority_json_bytes(manifest)
        secure_writer._verify_source_recovery_state(
            root,
            source_bytes,
            projected_bytes,
            source_authority,
            terminal_guard=cohort.verify,
        )
        return {
            "status": "UNAUTHORIZED_TEMPLATE",
            "comparison_only": True,
            "independent_recomputation_required": True,
            "may_prepare_or_write": False,
            "instructions": (
                "Comparison aid only: do not sign this candidate-authored output. "
                "An independent reviewer must separately recompute every field, "
                "author a distinct manifest with fresh 64-character lowercase "
                "hexadecimal approval and generation values, exact-diff it against "
                "the candidate facts, canonicalize it, and only then sign its "
                "domain-separated exact bytes."
            ),
            "manifest_template": manifest,
            "manifest_template_sha256": sha256_bytes(manifest_bytes),
            "manifest_template_byte_count": len(manifest_bytes),
            "signature_algorithm": ADDED_AUTHORITY_SIGNATURE_ALGORITHM,
            "signature_domain": ADDED_AUTHORITY_SIGNATURE_DOMAIN.decode("ascii"),
            "reviewer_key_fingerprint_sha256": REVIEWER_PUBLIC_KEY_SPKI_SHA256,
        }
    except BaseException as exc:
        primary = exc
        raise
    finally:
        cohort.close(primary)


def prepare(
    root: Path,
    added_authority: AddedAuthorityManifestGuard,
) -> PreparedReconcile:
    root = root.resolve(strict=True)
    added_authority.verify()
    _require(added_authority.root == root, "added authority repository binding differs")
    source_bytes, source_authority = secure_writer._capture_checkpoint_authority(root)
    _require(
        len(source_bytes) == SOURCE_CHECKPOINT_BYTE_COUNT,
        "source checkpoint byte count differs",
    )
    _require(
        sha256_bytes(source_bytes) == SOURCE_CHECKPOINT_SHA256,
        "source checkpoint SHA-256 differs",
    )
    try:
        source = json.loads(source_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ReconcileError("source checkpoint is not valid JSON") from exc
    _require(isinstance(source, dict), "source checkpoint root is not an object")
    source_paths = _require_exact_seq47(source)
    paths = sorted(set(source_paths) | set(ADDED_MANAGED_PATHS))
    cohort = ReconcileCohort.capture(root, paths)

    def verify_inputs() -> None:
        added_authority.verify()
        cohort.verify()
        added_authority.verify()

    try:
        projected = project(root, source, cohort, added_authority)
        verify_inputs()
        projected_bytes = json_bytes(projected)
        _require(
            json.loads(projected_bytes) == projected,
            "projected checkpoint serialization differs",
        )
        secure_writer._verify_source_recovery_state(
            root,
            source_bytes,
            projected_bytes,
            source_authority,
            terminal_guard=verify_inputs,
        )
        return PreparedReconcile(
            root=root,
            source_bytes=source_bytes,
            source=source,
            projected=projected,
            projected_bytes=projected_bytes,
            source_authority=source_authority,
            cohort=cohort,
            added_authority=added_authority,
        )
    except BaseException as exc:
        cohort.close(exc)
        raise


def _source_from_candidate(candidate: dict[str, Any]) -> dict[str, Any]:
    snapshot = candidate.get("working_tree_snapshot")
    candidate_paths = (
        snapshot.get("managed_changed_paths")
        if isinstance(snapshot, dict)
        else None
    )
    _require(
        isinstance(candidate_paths, list)
        and candidate_paths == sorted(set(candidate_paths))
        and set(ADDED_MANAGED_PATHS).issubset(candidate_paths)
        and len(candidate_paths) == MANAGED_PATH_COUNT + len(ADDED_MANAGED_PATHS),
        "reconciled candidate membership differs",
    )
    source = copy.deepcopy(candidate)
    source_paths = sorted(set(candidate_paths) - set(ADDED_MANAGED_PATHS))
    snapshot = source["working_tree_snapshot"]
    snapshot["managed_changed_paths"] = source_paths
    snapshot["managed_changed_path_count"] = MANAGED_PATH_COUNT
    snapshot["path_set_sha256"] = PATH_SET_SHA256
    snapshot["content_set_sha256"] = SOURCE_CONTENT_SET_SHA256
    handoff = source["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(source_paths)
    handoff_source = handoff["source_commit_or_snapshot"]
    handoff_source["file_count"] = MANAGED_PATH_COUNT
    handoff_source["path_set_sha256"] = PATH_SET_SHA256
    handoff_source["content_set_sha256"] = SOURCE_CONTENT_SET_SHA256
    source_bytes = json_bytes(source)
    _require(
        len(source_bytes) == SOURCE_CHECKPOINT_BYTE_COUNT
        and sha256_bytes(source_bytes) == SOURCE_CHECKPOINT_SHA256,
        "reconciled candidate does not reverse to the exact seq47 source",
    )
    _require_exact_seq47(source)
    return source


def inspect_or_recover_reconciled_checkpoint(
    root: Path,
    *,
    write: bool,
    added_authority: AddedAuthorityManifestGuard,
) -> dict[str, Any] | None:
    root = root.resolve(strict=True)
    added_authority.verify()
    _require(added_authority.root == root, "added authority repository binding differs")
    checkpoint_bytes, checkpoint_authority = (
        secure_writer._capture_checkpoint_authority(root)
    )
    if (
        len(checkpoint_bytes) == SOURCE_CHECKPOINT_BYTE_COUNT
        and sha256_bytes(checkpoint_bytes) == SOURCE_CHECKPOINT_SHA256
    ):
        try:
            source = json.loads(checkpoint_bytes)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ReconcileError("exact source checkpoint is not valid JSON") from exc
        _require(isinstance(source, dict), "exact source checkpoint root differs")
        source_paths = _require_exact_seq47(source)
        cohort = ReconcileCohort.capture(
            root,
            sorted(set(source_paths) | set(ADDED_MANAGED_PATHS)),
        )

        def verify_source_inputs() -> None:
            added_authority.verify()
            cohort.verify()
            added_authority.verify()

        primary: BaseException | None = None
        try:
            projected_bytes = json_bytes(
                project(root, source, cohort, added_authority)
            )
            verify_source_inputs()
            secure_writer._verify_source_recovery_state(
                root,
                checkpoint_bytes,
                projected_bytes,
                checkpoint_authority,
                terminal_guard=verify_source_inputs,
            )
        except BaseException as exc:
            primary = exc
            raise
        finally:
            cohort.close(primary)
        return None
    try:
        candidate = json.loads(checkpoint_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ReconcileError(
            "live checkpoint is neither exact source nor reconciled JSON"
        ) from exc
    _require(isinstance(candidate, dict), "reconciled checkpoint root differs")
    _require(
        json_bytes(candidate) == checkpoint_bytes,
        "reconciled checkpoint canonical JSON bytes differ",
    )
    source = _source_from_candidate(candidate)
    cohort = ReconcileCohort.capture(
        root,
        candidate["working_tree_snapshot"]["managed_changed_paths"],
    )

    def verify_recovery_inputs() -> None:
        added_authority.verify()
        cohort.verify()
        added_authority.verify()

    primary: BaseException | None = None
    recognized_candidate = False
    finalized = False
    try:
        cohort.verify()
        _require(
            cohort.content_set_sha256()
            == candidate["working_tree_snapshot"]["content_set_sha256"],
            "reconciled candidate differs from the retained content set",
        )
        regenerated_bytes = json_bytes(
            project(root, source, cohort, added_authority)
        )
        _require(
            regenerated_bytes == checkpoint_bytes,
            "reconciled checkpoint no longer byte-matches retained controlled inputs",
        )
        recognized_candidate = True
        verify_recovery_inputs()
        secure_writer._finalize_projected_publication(
            root,
            checkpoint_bytes,
            write=write,
            expected_projected_authority=checkpoint_authority,
            expected_source_sha256=SOURCE_CHECKPOINT_SHA256,
            expected_source_byte_count=SOURCE_CHECKPOINT_BYTE_COUNT,
            terminal_guard=verify_recovery_inputs,
        )
        finalized = True
    except BaseException as exc:
        primary = exc
        if (
            recognized_candidate
            or finalized
            or isinstance(exc, secure_writer.StartPostCommitUncertain)
        ):
            raise ReconcilePostCommitUncertain(str(exc)) from exc
        raise
    finally:
        try:
            cohort.close(primary)
        except BaseException as exc:
            if primary is None:
                raise ReconcilePostCommitUncertain(
                    "reconciled recovery cohort cleanup failed"
                ) from exc
    return candidate


def publish(prepared: PreparedReconcile) -> None:
    primary: BaseException | None = None
    committed = False

    def verify_inputs() -> None:
        prepared.added_authority.verify()
        prepared.cohort.verify()
        prepared.added_authority.verify()

    try:
        verify_inputs()
        _require(
            json_bytes(
                _project_from_retained(
                    prepared.root,
                    prepared.source,
                    prepared.cohort,
                )
            )
            == prepared.projected_bytes,
            "reconcile candidate changed before write",
        )
        _validate_added_authority(
            prepared.root,
            prepared.source,
            prepared.projected,
            prepared.projected_bytes,
            prepared.cohort,
            prepared.added_authority,
        )
        verify_inputs()
        try:
            projected_authority = secure_writer.atomic_write_seq47(
                prepared.root / CHECKPOINT,
                prepared.projected_bytes,
                expected_source=prepared.source_bytes,
                expected_source_authority=prepared.source_authority,
                commit_guard=verify_inputs,
                precommit_guard=verify_inputs,
            )
        except BaseException as exc:
            try:
                observed, observed_authority = secure_writer._capture_checkpoint_authority(
                    prepared.root
                )
            except BaseException as inspect_exc:
                committed = True
                raise ReconcilePostCommitUncertain(
                    "reconcile atomic writer failed and final state is unreadable"
                ) from inspect_exc
            if (
                observed == prepared.source_bytes
                and observed_authority == prepared.source_authority
            ):
                raise
            committed = True
            if observed == prepared.projected_bytes:
                raise ReconcilePostCommitUncertain(
                    "reconcile atomic writer failed after the projected state became live"
                ) from exc
            raise ReconcilePostCommitUncertain(
                "reconcile atomic writer failed with an unrecognized final state"
            ) from exc
        committed = True
        verify_inputs()
        published_bytes, published_authority = (
            secure_writer._capture_checkpoint_authority(
                prepared.root,
                prepared.projected_bytes,
            )
        )
        _require(
            published_bytes == prepared.projected_bytes
            and published_authority == projected_authority,
            "published reconcile bytes or projected authority differ",
        )
        published = json.loads(prepared.projected_bytes)
        _require(
            _changed_fields(prepared.source, published) == set(ALLOWED_MUTATIONS),
            "published reconcile mutation boundary differs",
        )
        errors = _validate_projected_checkpoint(
            prepared.root,
            published,
            prepared.cohort,
        )
        _require(not errors, "published reconcile is invalid: " + "; ".join(errors))
        secure_writer._finalize_projected_publication(
            prepared.root,
            prepared.projected_bytes,
            write=True,
            expected_projected_authority=projected_authority,
            expected_source_sha256=SOURCE_CHECKPOINT_SHA256,
            expected_source_byte_count=SOURCE_CHECKPOINT_BYTE_COUNT,
            terminal_guard=verify_inputs,
            require_clean=True,
        )
    except BaseException as exc:
        primary = exc
        if committed and not isinstance(exc, ReconcilePostCommitUncertain):
            raise ReconcilePostCommitUncertain(
                "administrative reconcile committed but post-write verification failed"
            ) from exc
        raise
    finally:
        cleanup_error: BaseException | None = None
        try:
            prepared.cohort.close(primary)
        except BaseException as exc:
            cleanup_error = exc
        try:
            prepared.added_authority.close(primary or cleanup_error)
        except BaseException as exc:
            if cleanup_error is None:
                cleanup_error = exc
        if primary is None and cleanup_error is not None:
            if committed:
                raise ReconcilePostCommitUncertain(
                    "administrative reconcile committed but retained authority cleanup failed"
                ) from cleanup_error
            raise cleanup_error


def _report_candidate(
    projected: dict[str, Any],
    projected_bytes: bytes,
    added_authority: AddedAuthorityManifestGuard,
    *,
    mode: str,
    wrote: bool,
) -> dict[str, Any]:
    return {
        "status": "PASS",
        "mode": mode,
        "administrative_only": True,
        "wrote_checkpoint": wrote,
        "source_sequence": SOURCE_SEQUENCE,
        "source_event_sha256": SOURCE_EVENT_SHA256,
        "source_checkpoint_sha256": SOURCE_CHECKPOINT_SHA256,
        "source_checkpoint_byte_count": SOURCE_CHECKPOINT_BYTE_COUNT,
        "candidate_checkpoint_sha256": sha256_bytes(projected_bytes),
        "candidate_checkpoint_byte_count": len(projected_bytes),
        "managed_path_count": projected["working_tree_snapshot"]
        ["managed_changed_path_count"],
        "path_set_sha256": projected["working_tree_snapshot"]["path_set_sha256"],
        "source_content_set_sha256": SOURCE_CONTENT_SET_SHA256,
        "candidate_content_set_sha256": projected["working_tree_snapshot"]
        ["content_set_sha256"],
        "independent_added_authority": {
            "approval_id": added_authority.document["approval_id"],
            "generation_nonce": added_authority.document["generation_nonce"],
            "manifest_sha256": added_authority.sha256,
            "manifest_byte_count": len(added_authority.raw_bytes),
            "signature_sha256": added_authority.signature.sha256,
            "signature_byte_count": len(added_authority.signature.raw_bytes),
            "reviewer_key_fingerprint_sha256": REVIEWER_PUBLIC_KEY_SPKI_SHA256,
            "signature_algorithm": ADDED_AUTHORITY_SIGNATURE_ALGORITHM,
            "signature_domain": ADDED_AUTHORITY_SIGNATURE_DOMAIN.decode("ascii"),
            "signed_candidate_files": {
                "added_managed_paths": _thaw_json(
                    added_authority.document["added_managed_paths"]
                ),
                "authorized_existing_deltas": _thaw_json(
                    added_authority.document["authorized_existing_deltas"]
                ),
            },
        },
        "authorized_existing_deltas": [
            {"path": path, **hashes}
            for path, hashes in sorted(AUTHORIZED_EXISTING_DELTAS.items())
        ],
        "added_managed_paths": list(ADDED_MANAGED_PATHS),
        "allowed_checkpoint_mutations": list(ALLOWED_MUTATIONS),
        "transition_history_unchanged": True,
        "goal_statuses_unchanged": True,
        "canonical_bindings_unchanged": True,
        "completion_credit_granted": False,
        "release_credit_granted": False,
    }


def report(prepared: PreparedReconcile, *, mode: str, wrote: bool) -> dict[str, Any]:
    return _report_candidate(
        prepared.projected,
        prepared.projected_bytes,
        prepared.added_authority,
        mode=mode,
        wrote=wrote,
    )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--print-unsigned-authority-template", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument(
        "--added-authority-manifest",
        type=Path,
        help="absolute manifest approved by an independent out-of-band reviewer",
    )
    parser.add_argument(
        "--added-authority-sha256",
        help="independently supplied exact manifest SHA-256; never auto-derived",
    )
    parser.add_argument(
        "--added-authority-bytes",
        type=int,
        help="independently supplied exact manifest byte count",
    )
    parser.add_argument(
        "--added-authority-signature",
        type=Path,
        help="absolute path to the independent reviewer's detached Ed25519 signature",
    )
    parser.add_argument(
        "--added-authority-signature-sha256",
        help="independently supplied exact detached-signature SHA-256",
    )
    parser.add_argument(
        "--added-authority-signature-bytes",
        type=int,
        help="exact detached-signature byte count (64 for Ed25519)",
    )
    return parser.parse_args(argv)


def _write_raw_exact(stream: Any, content: bytes) -> None:
    descriptor = stream.fileno()
    if (
        isinstance(descriptor, bool)
        or not isinstance(descriptor, int)
        or descriptor < 0
    ):
        raise OSError("output stream descriptor is invalid")
    offset = 0
    while offset < len(content):
        written = os.write(descriptor, content[offset:])
        if (
            isinstance(written, bool)
            or not isinstance(written, int)
            or written <= 0
            or written > len(content) - offset
        ):
            raise OSError("raw output write made invalid progress")
        offset += written


def _write_committed_report(payload: dict[str, Any], *, recovered: bool) -> None:
    try:
        content = (
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
        ).encode("utf-8")
        _write_raw_exact(sys.stdout, content)
    except BaseException as exc:
        state = "recovered" if recovered else "committed"
        raise ReconcilePostCommitUncertain(
            f"{state} reconcile public report delivery is uncertain"
        ) from exc


def _write_postcommit_diagnostic(message: str) -> None:
    try:
        _write_raw_exact(sys.stderr, f"{message}\n".encode("utf-8"))
    except BaseException:
        pass


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    prepared: PreparedReconcile | None = None
    added_authority: AddedAuthorityManifestGuard | None = None
    try:
        authority_arguments = (
            args.added_authority_manifest,
            args.added_authority_sha256,
            args.added_authority_bytes,
            args.added_authority_signature,
            args.added_authority_signature_sha256,
            args.added_authority_signature_bytes,
        )
        if args.print_unsigned_authority_template:
            _require(
                all(value is None for value in authority_arguments),
                "unsigned template mode does not accept authority inputs",
            )
            template_bytes = authority_json_bytes(unsigned_authority_template(args.root))
            sys.stdout.write(template_bytes.decode("utf-8"))
            sys.stdout.flush()
            return 0
        _require(
            all(value is not None for value in authority_arguments),
            "preflight and write require all manifest and detached-signature inputs",
        )
        added_authority = AddedAuthorityManifestGuard.capture(
            args.root,
            args.added_authority_manifest,
            args.added_authority_sha256,
            args.added_authority_bytes,
            args.added_authority_signature,
            args.added_authority_signature_sha256,
            args.added_authority_signature_bytes,
        )
        existing = inspect_or_recover_reconciled_checkpoint(
            args.root,
            write=args.write,
            added_authority=added_authority,
        )
        if existing is not None:
            payload = _report_candidate(
                existing,
                json_bytes(existing),
                added_authority,
                mode="WRITE-RECOVERED",
                wrote=False,
            )
            added_authority.verify()
            try:
                added_authority.close()
            except BaseException as exc:
                raise ReconcilePostCommitUncertain(
                    "published reconcile authority cleanup failed"
                ) from exc
            added_authority = None
            if args.write:
                _write_committed_report(payload, recovered=True)
            else:
                try:
                    print(json.dumps(payload, ensure_ascii=False, indent=2))
                    sys.stdout.flush()
                except OSError as exc:
                    raise ReconcileError(
                        "published preflight report emission failed"
                    ) from exc
            return 0
        prepared = prepare(args.root, added_authority)
        added_authority = None
        if args.write:
            publish(prepared)
        else:
            cleanup_error: BaseException | None = None
            try:
                prepared.cohort.close()
            except BaseException as exc:
                cleanup_error = exc
            try:
                prepared.added_authority.close(cleanup_error)
            except BaseException as exc:
                if cleanup_error is None:
                    cleanup_error = exc
            if cleanup_error is not None:
                raise cleanup_error
        payload = report(
            prepared,
            mode="WRITE" if args.write else "PREFLIGHT",
            wrote=args.write,
        )
        if args.write:
            _write_committed_report(payload, recovered=False)
        else:
            try:
                print(json.dumps(payload, ensure_ascii=False, indent=2))
                sys.stdout.flush()
            except OSError as exc:
                raise ReconcileError("preflight report emission failed") from exc
    except (ReconcilePostCommitUncertain, secure_writer.StartPostCommitUncertain) as exc:
        _write_postcommit_diagnostic(
            "WalkSafe FP008 snapshot reconcile: "
            f"POSTCOMMIT-UNCERTAIN: {exc}"
        )
        return 2
    except (OSError, ValueError, ReconcileError, pinned.PublicationError) as exc:
        print(f"WalkSafe FP008 snapshot reconcile: FAIL: {exc}", file=sys.stderr)
        return 1
    finally:
        if added_authority is not None:
            try:
                added_authority.close()
            except BaseException:
                pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
