#!/usr/bin/env python3
"""Download and atomically prepare the pinned Vosk Korean small model for Android.

The model artifact and its Apache-2.0 designation are published by the official
Vosk model catalog at https://alphacephei.com/vosk/models.  The catalog does not
publish a checksum, so the SHA-256 below pins the bytes served by the official
artifact URL.  The license text is pinned independently to the Apache Software
Foundation's canonical HTTPS copy.
"""

from __future__ import annotations

import argparse
import hashlib
from http.client import HTTPException
import os
from pathlib import Path, PurePosixPath
import stat
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import zipfile


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
MODEL_ID = "vosk-model-small-ko-0.22"
MODEL_URL = f"https://alphacephei.com/vosk/models/{MODEL_ID}.zip"
MODEL_ARCHIVE_SHA256 = "eea36124087fed26c59996a4761519458e3bd185e8ea9d9865ad8760c4a1d989"
MODEL_ARCHIVE_BYTES = 86_914_329
MODEL_ARCHIVE_ENTRY_COUNT = 21
MODEL_ARCHIVE_FILE_COUNT = 15
MODEL_UNPACKED_FILE_BYTES = 264_364_726
MODEL_COMPRESSED_ENTRY_BYTES = 86_909_979

LICENSE_ID = "Apache-2.0"
LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0.txt"
LICENSE_SHA256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
LICENSE_BYTES = 11_358
LICENSE_FILENAME = "LICENSE.Apache-2.0.txt"

MANIFEST_FILENAME = "MODEL_FILES.sha256"
METADATA_FILENAME = "MODEL_METADATA.properties"
METADATA_BYTES = (
    f"model.id={MODEL_ID}\n"
    f"model.license={LICENSE_ID}\n"
    f"source.archive.sha256={MODEL_ARCHIVE_SHA256}\n"
).encode("utf-8")
EXPECTED_MANIFEST_SHA256 = "8396458f1990e174b88c531f7db14587a4b52273443a1b39dfd6b75552e135c3"
EXPECTED_MANIFEST_BYTES = 1_446
EXPECTED_PAYLOAD_FILE_COUNT = MODEL_ARCHIVE_FILE_COUNT + 2
EXPECTED_INSTALLED_FILE_COUNT = EXPECTED_PAYLOAD_FILE_COUNT + 1
EXPECTED_INSTALLED_FILE_BYTES = (
    MODEL_UNPACKED_FILE_BYTES + LICENSE_BYTES + len(METADATA_BYTES) + EXPECTED_MANIFEST_BYTES
)
EXPECTED_MODEL_DIRECTORIES = frozenset(
    {
        PurePosixPath("."),
        PurePosixPath("am"),
        PurePosixPath("conf"),
        PurePosixPath("graph"),
        PurePosixPath("graph/phones"),
        PurePosixPath("ivector"),
    }
)
DEFAULT_DESTINATION = (
    REPOSITORY_ROOT
    / "apps/android/app/src/main/assets/voice-models"
    / MODEL_ID
)
COPY_BUFFER_BYTES = 1024 * 1024
DOWNLOAD_TIMEOUT_SECONDS = 60


class PreparationError(Exception):
    """A fail-closed model preparation or verification error."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(COPY_BUFFER_BYTES), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_exclusive(path: Path, payload: bytes) -> None:
    with path.open("xb") as handle:
        handle.write(payload)
        handle.flush()
        os.fsync(handle.fileno())
    path.chmod(0o644)


def download_verified(url: str, expected_bytes: int, expected_sha256: str, target: Path) -> None:
    request = Request(url, headers={"User-Agent": "WalkSafe-Vosk-Model-Preparer/1"})
    try:
        response = urlopen(request, timeout=DOWNLOAD_TIMEOUT_SECONDS)
    except (HTTPError, URLError, TimeoutError) as error:
        raise PreparationError(f"download failed for {url}: {error}") from error

    digest = hashlib.sha256()
    downloaded_bytes = 0
    try:
        with response:
            if response.geturl() != url:
                raise PreparationError(
                    f"unexpected download redirect: expected {url}, got {response.geturl()}"
                )
            content_length = response.headers.get("Content-Length")
            if content_length is not None:
                try:
                    declared_bytes = int(content_length)
                except ValueError as error:
                    raise PreparationError(f"invalid Content-Length for {url}") from error
                if declared_bytes != expected_bytes:
                    raise PreparationError(
                        f"download size declaration mismatch for {url}: "
                        f"expected {expected_bytes}, got {declared_bytes}"
                    )

            with target.open("xb") as output:
                while True:
                    chunk = response.read(COPY_BUFFER_BYTES)
                    if not chunk:
                        break
                    downloaded_bytes += len(chunk)
                    if downloaded_bytes > expected_bytes:
                        raise PreparationError(f"download exceeds pinned size for {url}")
                    digest.update(chunk)
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
    except (HTTPException, OSError) as error:
        raise PreparationError(f"download write failed for {url}: {error}") from error

    if downloaded_bytes != expected_bytes:
        raise PreparationError(
            f"download size mismatch for {url}: expected {expected_bytes}, got {downloaded_bytes}"
        )
    actual_sha256 = digest.hexdigest()
    if actual_sha256 != expected_sha256:
        raise PreparationError(
            f"download SHA-256 mismatch for {url}: expected {expected_sha256}, got {actual_sha256}"
        )


def safe_member_relative_path(info: zipfile.ZipInfo) -> PurePosixPath | None:
    name = info.filename
    if not name or "\x00" in name or "\\" in name:
        raise PreparationError(f"unsafe ZIP member name: {name!r}")
    if any(ord(character) < 32 or ord(character) == 127 for character in name):
        raise PreparationError(f"control character in ZIP member name: {name!r}")
    if name.startswith("/"):
        raise PreparationError(f"absolute ZIP member path: {name!r}")

    is_directory = info.is_dir()
    raw_path = name[:-1] if is_directory else name
    parts = raw_path.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise PreparationError(f"unsafe ZIP member path component: {name!r}")
    if ":" in parts[0]:
        raise PreparationError(f"drive-qualified ZIP member path: {name!r}")
    if parts[0] != MODEL_ID:
        raise PreparationError(f"ZIP member is outside the pinned model root: {name!r}")

    mode = (info.external_attr >> 16) & 0xFFFF
    file_type = stat.S_IFMT(mode)
    if file_type not in (0, stat.S_IFREG, stat.S_IFDIR):
        raise PreparationError(f"ZIP member is a link or special file: {name!r}")
    if is_directory and file_type not in (0, stat.S_IFDIR):
        raise PreparationError(f"ZIP directory has a non-directory mode: {name!r}")
    if not is_directory and file_type == stat.S_IFDIR:
        raise PreparationError(f"ZIP file has a directory mode: {name!r}")
    if info.flag_bits & 0x1:
        raise PreparationError(f"encrypted ZIP member is not allowed: {name!r}")
    if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
        raise PreparationError(f"unsupported ZIP compression for member: {name!r}")

    if len(parts) == 1:
        if not is_directory:
            raise PreparationError("the pinned ZIP root must be a directory")
        return None
    return PurePosixPath(*parts[1:])


def validated_archive_files(archive: zipfile.ZipFile) -> list[tuple[zipfile.ZipInfo, PurePosixPath]]:
    infos = archive.infolist()
    if len(infos) != MODEL_ARCHIVE_ENTRY_COUNT:
        raise PreparationError(
            f"ZIP entry count mismatch: expected {MODEL_ARCHIVE_ENTRY_COUNT}, got {len(infos)}"
        )

    seen_paths: set[str] = set()
    files: list[tuple[zipfile.ZipInfo, PurePosixPath]] = []
    unpacked_bytes = 0
    compressed_bytes = 0
    for info in infos:
        relative_path = safe_member_relative_path(info)
        normalized = "." if relative_path is None else relative_path.as_posix()
        if normalized in seen_paths:
            raise PreparationError(f"duplicate normalized ZIP member: {info.filename!r}")
        seen_paths.add(normalized)
        if info.is_dir():
            continue
        if relative_path is None:
            raise PreparationError(f"invalid root file in ZIP: {info.filename!r}")
        files.append((info, relative_path))
        unpacked_bytes += info.file_size
        compressed_bytes += info.compress_size

    if len(files) != MODEL_ARCHIVE_FILE_COUNT:
        raise PreparationError(
            f"ZIP file count mismatch: expected {MODEL_ARCHIVE_FILE_COUNT}, got {len(files)}"
        )
    if unpacked_bytes != MODEL_UNPACKED_FILE_BYTES:
        raise PreparationError(
            f"ZIP unpacked size mismatch: expected {MODEL_UNPACKED_FILE_BYTES}, got {unpacked_bytes}"
        )
    if compressed_bytes != MODEL_COMPRESSED_ENTRY_BYTES:
        raise PreparationError(
            f"ZIP compressed entry size mismatch: expected {MODEL_COMPRESSED_ENTRY_BYTES}, "
            f"got {compressed_bytes}"
        )
    return sorted(files, key=lambda item: item[1].as_posix())


def extract_verified_archive(archive_path: Path, destination: Path) -> None:
    destination.mkdir(mode=0o755)
    for directory in sorted(EXPECTED_MODEL_DIRECTORIES, key=lambda item: item.as_posix()):
        if directory == PurePosixPath("."):
            continue
        (destination / directory).mkdir(parents=True, exist_ok=True, mode=0o755)

    try:
        with zipfile.ZipFile(archive_path) as archive:
            members = validated_archive_files(archive)
            extracted_bytes = 0
            for info, relative_path in members:
                target = destination.joinpath(*relative_path.parts)
                target.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
                member_bytes = 0
                with archive.open(info, "r") as source, target.open("xb") as output:
                    while True:
                        chunk = source.read(COPY_BUFFER_BYTES)
                        if not chunk:
                            break
                        member_bytes += len(chunk)
                        extracted_bytes += len(chunk)
                        if member_bytes > info.file_size or extracted_bytes > MODEL_UNPACKED_FILE_BYTES:
                            raise PreparationError(
                                f"ZIP member expanded beyond its pinned size: {info.filename!r}"
                            )
                        output.write(chunk)
                    output.flush()
                    os.fsync(output.fileno())
                if member_bytes != info.file_size:
                    raise PreparationError(
                        f"ZIP member size mismatch after extraction: {info.filename!r}"
                    )
                target.chmod(0o644)
            if extracted_bytes != MODEL_UNPACKED_FILE_BYTES:
                raise PreparationError(
                    f"extracted size mismatch: expected {MODEL_UNPACKED_FILE_BYTES}, "
                    f"got {extracted_bytes}"
                )
    except (zipfile.BadZipFile, NotImplementedError, RuntimeError) as error:
        raise PreparationError(f"invalid model ZIP: {error}") from error


def model_tree_files(root: Path) -> tuple[list[Path], set[PurePosixPath]]:
    files: list[Path] = []
    directories = {PurePosixPath(".")}
    for path in root.rglob("*"):
        relative_path = PurePosixPath(path.relative_to(root).as_posix())
        if path.is_symlink():
            raise PreparationError(f"installed model contains a symbolic link: {relative_path}")
        if path.is_dir():
            directories.add(relative_path)
        elif path.is_file():
            files.append(path)
        else:
            raise PreparationError(f"installed model contains a special file: {relative_path}")
    return files, directories


def build_manifest(root: Path) -> bytes:
    files, directories = model_tree_files(root)
    if directories != EXPECTED_MODEL_DIRECTORIES:
        raise PreparationError(
            "installed model directory set mismatch: "
            f"expected {sorted(map(str, EXPECTED_MODEL_DIRECTORIES))}, "
            f"got {sorted(map(str, directories))}"
        )

    payload_files = [
        path for path in files if path.relative_to(root).as_posix() != MANIFEST_FILENAME
    ]
    if len(payload_files) != EXPECTED_PAYLOAD_FILE_COUNT:
        raise PreparationError(
            f"installed payload count mismatch: expected {EXPECTED_PAYLOAD_FILE_COUNT}, "
            f"got {len(payload_files)}"
        )
    lines = []
    for path in sorted(payload_files, key=lambda item: item.relative_to(root).as_posix()):
        relative_path = path.relative_to(root).as_posix()
        if "\n" in relative_path or "\r" in relative_path:
            raise PreparationError(f"unsafe installed model path: {relative_path!r}")
        lines.append(f"{sha256_file(path)}  {relative_path}\n")
    manifest = "".join(lines).encode("utf-8")
    if len(manifest) != EXPECTED_MANIFEST_BYTES:
        raise PreparationError(
            f"generated manifest size mismatch: expected {EXPECTED_MANIFEST_BYTES}, got {len(manifest)}"
        )
    manifest_sha256 = hashlib.sha256(manifest).hexdigest()
    if manifest_sha256 != EXPECTED_MANIFEST_SHA256:
        raise PreparationError(
            "generated manifest does not match the pinned official model contents: "
            f"expected {EXPECTED_MANIFEST_SHA256}, got {manifest_sha256}"
        )
    return manifest


def verify_installation(root: Path) -> tuple[int, int]:
    if root.is_symlink() or not root.is_dir():
        raise PreparationError(f"model destination is not a regular directory: {root}")

    metadata_path = root / METADATA_FILENAME
    if metadata_path.is_symlink() or not metadata_path.is_file():
        raise PreparationError(f"missing model metadata: {metadata_path}")
    if metadata_path.read_bytes() != METADATA_BYTES:
        raise PreparationError(f"model metadata mismatch: {metadata_path}")

    license_path = root / LICENSE_FILENAME
    if license_path.is_symlink() or not license_path.is_file():
        raise PreparationError(f"missing model license: {license_path}")
    if license_path.stat().st_size != LICENSE_BYTES or sha256_file(license_path) != LICENSE_SHA256:
        raise PreparationError(f"model license mismatch: {license_path}")

    expected_manifest = build_manifest(root)
    manifest_path = root / MANIFEST_FILENAME
    if manifest_path.is_symlink() or not manifest_path.is_file():
        raise PreparationError(f"missing model manifest: {manifest_path}")
    if manifest_path.read_bytes() != expected_manifest:
        raise PreparationError(f"model manifest mismatch: {manifest_path}")

    files, _ = model_tree_files(root)
    installed_bytes = sum(path.stat().st_size for path in files)
    if len(files) != EXPECTED_INSTALLED_FILE_COUNT:
        raise PreparationError(
            f"installed file count mismatch: expected {EXPECTED_INSTALLED_FILE_COUNT}, got {len(files)}"
        )
    if installed_bytes != EXPECTED_INSTALLED_FILE_BYTES:
        raise PreparationError(
            f"installed byte count mismatch: expected {EXPECTED_INSTALLED_FILE_BYTES}, "
            f"got {installed_bytes}"
        )
    return len(files), installed_bytes


def prepare(destination: Path) -> tuple[bool, int, int]:
    if os.path.lexists(destination):
        file_count, installed_bytes = verify_installation(destination)
        return False, file_count, installed_bytes

    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix=f".{MODEL_ID}.prepare-", dir=destination.parent
    ) as temporary_directory:
        workspace = Path(temporary_directory)
        archive_path = workspace / f"{MODEL_ID}.zip"
        license_download_path = workspace / LICENSE_FILENAME
        staged_model = workspace / "install"

        download_verified(
            MODEL_URL,
            MODEL_ARCHIVE_BYTES,
            MODEL_ARCHIVE_SHA256,
            archive_path,
        )
        if archive_path.stat().st_size != MODEL_ARCHIVE_BYTES:
            raise PreparationError("model archive changed after download verification")
        extract_verified_archive(archive_path, staged_model)

        download_verified(LICENSE_URL, LICENSE_BYTES, LICENSE_SHA256, license_download_path)
        license_target = staged_model / LICENSE_FILENAME
        license_download_path.replace(license_target)
        license_target.chmod(0o644)
        write_exclusive(staged_model / METADATA_FILENAME, METADATA_BYTES)

        manifest = build_manifest(staged_model)
        write_exclusive(staged_model / MANIFEST_FILENAME, manifest)
        file_count, installed_bytes = verify_installation(staged_model)

        if os.path.lexists(destination):
            raise PreparationError(f"model destination appeared during preparation: {destination}")
        staged_model.rename(destination)

    verified_file_count, verified_bytes = verify_installation(destination)
    if (verified_file_count, verified_bytes) != (file_count, installed_bytes):
        raise PreparationError("installed model changed during atomic publication")
    return True, verified_file_count, verified_bytes


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--destination",
        type=Path,
        default=DEFAULT_DESTINATION,
        help=f"prepared model root (default: {DEFAULT_DESTINATION})",
    )
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="verify an existing prepared model without network access",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    destination = args.destination.expanduser().resolve(strict=False)
    try:
        if args.verify_only:
            file_count, installed_bytes = verify_installation(destination)
            print(
                f"Vosk model verification PASS: {destination} "
                f"({file_count} files, {installed_bytes} bytes)"
            )
            return 0

        installed, file_count, installed_bytes = prepare(destination)
    except (OSError, PreparationError) as error:
        print(f"Vosk model preparation FAIL: {error}", file=sys.stderr)
        return 1

    action = "prepared" if installed else "already prepared and verified"
    print(
        f"Vosk model {action}: {destination} "
        f"({file_count} files, {installed_bytes} bytes)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
