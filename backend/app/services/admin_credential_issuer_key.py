"""Load the administrator credential-issuance key from a private file."""

from __future__ import annotations

import base64
import binascii
import os
from pathlib import Path
import stat


_MAX_ENCODED_KEY_BYTES = 44
_PRIVATE_FILE_MODES = frozenset({0o400, 0o600})
_ROOT_SERVICE_FILE_MODES = frozenset({0o440})


class AdminCredentialIssuerKeyError(RuntimeError):
    """The issuer-key file is unavailable, unsafe, or malformed."""


def _identity(metadata: os.stat_result) -> tuple[int, int]:
    return metadata.st_dev, metadata.st_ino


def _stable_metadata(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_nlink,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _validate_directory(
    metadata: os.stat_result,
    *,
    require_root_authority: bool,
    final_parent: bool,
) -> None:
    if not stat.S_ISDIR(metadata.st_mode):
        raise AdminCredentialIssuerKeyError("issuer-key authority is invalid")
    mode = stat.S_IMODE(metadata.st_mode)
    if require_root_authority:
        if metadata.st_uid != 0 or mode & 0o022:
            raise AdminCredentialIssuerKeyError("issuer-key authority is invalid")
    elif final_parent:
        if metadata.st_uid == os.geteuid():
            if mode & 0o077:
                raise AdminCredentialIssuerKeyError("issuer-key authority is invalid")
        elif metadata.st_uid != 0 or mode & 0o022:
            raise AdminCredentialIssuerKeyError("issuer-key authority is invalid")


def _parse_key(raw: bytes) -> str:
    encoded = raw[:-1] if raw.endswith(b"\n") else raw
    if len(encoded) != 43 or b"\n" in encoded or b"\r" in encoded:
        raise AdminCredentialIssuerKeyError("issuer key is invalid")
    try:
        decoded = base64.b64decode(encoded + b"=", altchars=b"-_", validate=True)
    except (binascii.Error, ValueError):
        raise AdminCredentialIssuerKeyError("issuer key is invalid") from None
    canonical = base64.urlsafe_b64encode(decoded).rstrip(b"=")
    if len(decoded) != 32 or canonical != encoded:
        raise AdminCredentialIssuerKeyError("issuer key is invalid")
    return encoded.decode("ascii")


def load_admin_credential_issuer_key(
    path: Path,
    *,
    require_root_authority: bool,
    expected_service_gid: int | None = None,
) -> str:
    """Return a canonical 256-bit key without exposing its path on failure."""

    if (
        not path.is_absolute()
        or Path(os.path.abspath(path)) != path
        or path.name in {"", ".", ".."}
    ):
        raise AdminCredentialIssuerKeyError("issuer-key configuration is invalid")

    directory_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    file_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_NONBLOCK", 0)
    )
    directories: list[tuple[int, int | None, str, tuple[int, int]]] = []
    descriptor: int | None = None
    try:
        root_descriptor = os.open("/", directory_flags)
        try:
            root_metadata = os.fstat(root_descriptor)
        except BaseException:
            os.close(root_descriptor)
            raise
        directories.append((root_descriptor, None, "/", _identity(root_metadata)))
        _validate_directory(
            root_metadata,
            require_root_authority=require_root_authority,
            final_parent=path.parent == Path("/"),
        )

        for index, component in enumerate(path.parts[1:-1]):
            parent_descriptor = directories[-1][0]
            child_descriptor = os.open(component, directory_flags, dir_fd=parent_descriptor)
            try:
                child_metadata = os.fstat(child_descriptor)
            except BaseException:
                os.close(child_descriptor)
                raise
            directories.append(
                (
                    child_descriptor,
                    parent_descriptor,
                    component,
                    _identity(child_metadata),
                )
            )
            _validate_directory(
                child_metadata,
                require_root_authority=require_root_authority,
                final_parent=index == len(path.parts[1:-1]) - 1,
            )

        parent_descriptor = directories[-1][0]
        descriptor = os.open(path.name, file_flags, dir_fd=parent_descriptor)
        before = os.fstat(descriptor)
        expected_gid = os.getegid() if expected_service_gid is None else expected_service_gid
        allowed_modes = (
            _ROOT_SERVICE_FILE_MODES if require_root_authority else _PRIVATE_FILE_MODES
        )
        allowed_uids = {0} if require_root_authority else {0, os.geteuid()}
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_nlink != 1
            or before.st_uid not in allowed_uids
            or stat.S_IMODE(before.st_mode) not in allowed_modes
            or (require_root_authority and before.st_gid != expected_gid)
        ):
            raise AdminCredentialIssuerKeyError("issuer-key file authority is invalid")

        if before.st_size > _MAX_ENCODED_KEY_BYTES:
            raise AdminCredentialIssuerKeyError("issuer key is invalid")

        raw = os.read(descriptor, _MAX_ENCODED_KEY_BYTES + 1)
        if len(raw) > _MAX_ENCODED_KEY_BYTES or os.read(descriptor, 1):
            raise AdminCredentialIssuerKeyError("issuer key is invalid")
        after = os.fstat(descriptor)
        named = os.stat(path.name, dir_fd=parent_descriptor, follow_symlinks=False)
        if _stable_metadata(after) != _stable_metadata(before) or _identity(named) != _identity(after):
            raise AdminCredentialIssuerKeyError("issuer-key file identity changed")

        for index, (opened, parent, basename, identity) in enumerate(directories):
            opened_metadata = os.fstat(opened)
            named_metadata = (
                os.stat("/", follow_symlinks=False)
                if parent is None
                else os.stat(basename, dir_fd=parent, follow_symlinks=False)
            )
            if _identity(opened_metadata) != identity or _identity(named_metadata) != identity:
                raise AdminCredentialIssuerKeyError("issuer-key authority changed")
            _validate_directory(
                opened_metadata,
                require_root_authority=require_root_authority,
                final_parent=index == len(directories) - 1,
            )
        return _parse_key(raw)
    except AdminCredentialIssuerKeyError:
        raise
    except OSError:
        raise AdminCredentialIssuerKeyError("issuer key is unavailable") from None
    finally:
        if descriptor is not None:
            os.close(descriptor)
        for opened, _parent, _basename, _identity_value in reversed(directories):
            os.close(opened)


__all__ = [
    "AdminCredentialIssuerKeyError",
    "load_admin_credential_issuer_key",
]
