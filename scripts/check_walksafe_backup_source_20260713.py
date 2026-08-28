#!/usr/bin/env python3
"""Fail closed unless report rows and the flat upload tree form one snapshot."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from dataclasses import dataclass
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tarfile
from typing import Any, BinaryIO, Iterable, Iterator
import uuid

import psycopg

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.walksafe_environment_identity import explicit_backup_database_url
from backend.app.services.report_image_crypto import (
    MAX_ENVELOPE_BYTES,
    ReportImageCryptoError,
    parse_report_image_envelope,
)


SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
KEY_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
LOGICAL_IMAGE_NAME_PATTERN = re.compile(
    r"(?P<report_id>[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\.(?:jpg|png|webp)"
)
STORAGE_NAME_PATTERN = re.compile(
    r"(?P<report_id>[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\.wse"
)
OPERATIONAL_DIRECTORIES = frozenset({".report-write-journal", ".retention-quarantine"})
UploadEntry = tuple[int, os.stat_result]


@dataclass(frozen=True)
class ReportImage:
    report_id: str | None
    image_path: str | None
    image_object_report_id: str | None
    storage_name: str | None
    envelope_sha256: str | None
    envelope_size: int | None
    key_id: str | None


@dataclass(frozen=True)
class ExpectedUpload:
    report_id: uuid.UUID
    storage_name: str
    envelope_sha256: str
    envelope_size: int
    key_id: str


@dataclass(frozen=True)
class ActualUpload:
    envelope_sha256: str
    envelope_size: int
    key_id: str


def report_image_filename(image_path: str, *, report_id: uuid.UUID | None = None) -> str:
    prefix = "/uploads/"
    if not isinstance(image_path, str) or not image_path.startswith(prefix):
        raise ValueError(f"unsafe report image path: {image_path!r}")
    filename = image_path.removeprefix(prefix)
    match = LOGICAL_IMAGE_NAME_PATTERN.fullmatch(filename)
    if (
        not filename
        or Path(filename).name != filename
        or filename in {".", ".."}
        or any(ord(character) < 0x20 or ord(character) == 0x7F for character in filename)
        or match is None
        or (report_id is not None and match.group("report_id") != str(report_id))
    ):
        raise ValueError(f"unsafe report image path: {image_path!r}")
    return filename


def _stat_identity(metadata: os.stat_result) -> tuple[int, ...]:
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


def _descriptor_acl_is_absent(descriptor: int) -> bool:
    try:
        return not any(
            "acl" in os.fsdecode(name).casefold()
            for name in os.listxattr(descriptor)
        )
    except (AttributeError, OSError):
        return False


@contextmanager
def opened_upload_snapshot(
    upload_dir: Path | int,
    *,
    expected_reader_gid: int | None = None,
) -> Iterator[tuple[int, dict[str, UploadEntry]]]:
    if isinstance(upload_dir, int):
        directory_fd = os.dup(upload_dir)
    else:
        directory_fd = os.open(
            upload_dir,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
    files: dict[str, UploadEntry] = {}
    try:
        root_before = os.fstat(directory_fd)
        if expected_reader_gid is None:
            if (
                not stat.S_ISDIR(root_before.st_mode)
                or root_before.st_uid != os.geteuid()
                or stat.S_IMODE(root_before.st_mode) != 0o700
                or not _descriptor_acl_is_absent(directory_fd)
            ):
                raise ValueError("upload root must be a service-owned private real directory")
        else:
            effective_groups = {os.getegid(), *os.getgroups()}
            if expected_reader_gid <= 0 or expected_reader_gid not in effective_groups:
                raise ValueError("backup process is not a member of the upload reader group")
            if (
                not stat.S_ISDIR(root_before.st_mode)
                or root_before.st_uid in {0, os.geteuid()}
                or root_before.st_gid != expected_reader_gid
                or stat.S_IMODE(root_before.st_mode) != 0o2750
                or not _descriptor_acl_is_absent(directory_fd)
                or os.access(
                    ".",
                    os.W_OK,
                    dir_fd=directory_fd,
                    effective_ids=True,
                    follow_symlinks=False,
                )
            ):
                raise ValueError(
                    "operational upload root must be backend-owned, reader-group 2750, and not writable by backup"
                )
        for filename in sorted(os.listdir(directory_fd)):
            entry = os.stat(filename, dir_fd=directory_fd, follow_symlinks=False)
            if filename in OPERATIONAL_DIRECTORIES:
                raise ValueError(f"backup operational directory remains in upload root: {filename}")
            if STORAGE_NAME_PATTERN.fullmatch(filename) is None:
                raise ValueError(
                    f"upload snapshot contains a legacy, plaintext, orphan, or unknown entry: {filename}"
                )
            expected_uid = os.geteuid() if expected_reader_gid is None else root_before.st_uid
            expected_mode = 0o600 if expected_reader_gid is None else 0o640
            if (
                not stat.S_ISREG(entry.st_mode)
                or entry.st_uid != expected_uid
                or (
                    expected_reader_gid is not None
                    and entry.st_gid != expected_reader_gid
                )
                or stat.S_IMODE(entry.st_mode) != expected_mode
                or entry.st_nlink != 1
                or not 0 < entry.st_size <= MAX_ENVELOPE_BYTES
            ):
                raise ValueError(
                    f"upload snapshot contains a non-flat or unsafe entry: {filename}"
                )
            descriptor = os.open(
                filename,
                os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
                dir_fd=directory_fd,
            )
            opened_before = os.fstat(descriptor)
            if (
                _stat_identity(opened_before) != _stat_identity(entry)
                or not _descriptor_acl_is_absent(descriptor)
            ):
                os.close(descriptor)
                raise ValueError(f"upload file changed or has an ACL while opening: {filename}")
            files[filename] = (descriptor, opened_before)
        yield directory_fd, files
        if (
            _stat_identity(os.fstat(directory_fd)) != _stat_identity(root_before)
            or not _descriptor_acl_is_absent(directory_fd)
        ):
            raise ValueError("upload root changed while reading the snapshot")
        for filename, (descriptor, opened_before) in files.items():
            if (
                _stat_identity(os.fstat(descriptor)) != _stat_identity(opened_before)
                or not _descriptor_acl_is_absent(descriptor)
            ):
                raise ValueError(f"upload file changed while reading: {filename}")
    finally:
        for descriptor, _metadata in files.values():
            os.close(descriptor)
        os.close(directory_fd)


def _read_actual_upload(
    descriptor: int,
    metadata: os.stat_result,
    storage_name: str,
) -> ActualUpload:
    match = STORAGE_NAME_PATTERN.fullmatch(storage_name)
    if match is None:
        raise ValueError(f"encrypted report storage name is invalid: {storage_name}")
    expected_report_id = uuid.UUID(match.group("report_id"))
    os.lseek(descriptor, 0, os.SEEK_SET)
    chunks: list[bytes] = []
    remaining = metadata.st_size
    while remaining:
        chunk = os.read(descriptor, min(1024 * 1024, remaining))
        if not chunk:
            raise ValueError(f"encrypted report object is truncated: {storage_name}")
        chunks.append(chunk)
        remaining -= len(chunk)
    if os.read(descriptor, 1):
        raise ValueError(f"encrypted report object size changed: {storage_name}")
    final = os.fstat(descriptor)
    if _stat_identity(final) != _stat_identity(metadata):
        raise ValueError(f"upload file changed while reading: {storage_name}")
    envelope = b"".join(chunks)
    try:
        parsed = parse_report_image_envelope(
            envelope,
            expected_report_id=expected_report_id,
        )
    except ReportImageCryptoError as exc:
        raise ValueError(f"upload file is not an encrypted report envelope: {storage_name}") from exc
    os.lseek(descriptor, 0, os.SEEK_SET)
    return ActualUpload(
        envelope_sha256=hashlib.sha256(envelope).hexdigest(),
        envelope_size=len(envelope),
        key_id=parsed.key_id,
    )


def _actual_uploads(files: dict[str, UploadEntry]) -> dict[str, ActualUpload]:
    return {
        filename: _read_actual_upload(descriptor, metadata, filename)
        for filename, (descriptor, metadata) in files.items()
    }


def upload_hashes(
    upload_dir: Path | int,
    *,
    expected_reader_gid: int | None = None,
) -> dict[str, str]:
    with opened_upload_snapshot(
        upload_dir,
        expected_reader_gid=expected_reader_gid,
    ) as (_directory_fd, files):
        return {
            filename: actual.envelope_sha256
            for filename, actual in _actual_uploads(files).items()
        }


def expected_uploads(report_images: Iterable[ReportImage]) -> dict[str, ExpectedUpload]:
    referenced: dict[str, ExpectedUpload] = {}
    report_ids: set[uuid.UUID] = set()
    for row in report_images:
        if not isinstance(row, ReportImage):
            raise ValueError("backup report/image row shape is invalid")
        try:
            report_id = uuid.UUID(row.report_id) if isinstance(row.report_id, str) else None
            image_object_report_id = (
                uuid.UUID(row.image_object_report_id)
                if isinstance(row.image_object_report_id, str)
                else None
            )
        except ValueError as exc:
            raise ValueError("backup report/image UUID is invalid") from exc
        if (
            report_id is None
            or image_object_report_id is None
            or str(report_id) != row.report_id
            or str(image_object_report_id) != row.image_object_report_id
            or report_id.version != 4
            or image_object_report_id != report_id
        ):
            raise ValueError(
                "FULL JOIN found a report without exact encrypted image metadata or an orphan image object"
            )
        report_image_filename(row.image_path, report_id=report_id)  # routing shape only
        expected_storage_name = f"{report_id}.wse"
        if (
            row.storage_name != expected_storage_name
            or STORAGE_NAME_PATTERN.fullmatch(row.storage_name) is None
        ):
            raise ValueError(f"encrypted report storage name is not report-bound: {row.storage_name!r}")
        if (
            not isinstance(row.envelope_sha256, str)
            or SHA256_PATTERN.fullmatch(row.envelope_sha256) is None
        ):
            raise ValueError(f"report envelope hash is missing or invalid: {row.storage_name}")
        if (
            not isinstance(row.envelope_size, int)
            or isinstance(row.envelope_size, bool)
            or not 0 < row.envelope_size <= MAX_ENVELOPE_BYTES
        ):
            raise ValueError(f"report envelope size is missing or invalid: {row.storage_name}")
        if not isinstance(row.key_id, str) or KEY_ID_PATTERN.fullmatch(row.key_id) is None:
            raise ValueError(f"report envelope key id is missing or invalid: {row.storage_name}")
        if report_id in report_ids or row.storage_name in referenced:
            raise ValueError("multiple database rows reference the same encrypted report object")
        report_ids.add(report_id)
        referenced[row.storage_name] = ExpectedUpload(
            report_id=report_id,
            storage_name=row.storage_name,
            envelope_sha256=row.envelope_sha256,
            envelope_size=row.envelope_size,
            key_id=row.key_id,
        )
    return referenced


def expected_upload_hashes(report_images: Iterable[ReportImage]) -> dict[str, str]:
    return {
        storage_name: expected.envelope_sha256
        for storage_name, expected in expected_uploads(report_images).items()
    }


def snapshot_consistency_result(
    referenced: dict[str, ExpectedUpload],
    actual: dict[str, ActualUpload],
) -> dict[str, int | str]:
    referenced_set = set(referenced)
    missing = sorted(referenced_set - actual.keys())
    orphan = sorted(actual.keys() - referenced_set)
    if missing or orphan:
        raise ValueError(
            f"report/upload snapshot is inconsistent: missing={missing[:5]}, orphan={orphan[:5]}"
        )
    mismatched = sorted(
        filename
        for filename, expected in referenced.items()
        if actual[filename].envelope_sha256 != expected.envelope_sha256
        or actual[filename].envelope_size != expected.envelope_size
        or actual[filename].key_id != expected.key_id
    )
    if mismatched:
        raise ValueError(f"report/upload encrypted object metadata mismatch: {mismatched[:5]}")
    content_digest = hashlib.sha256()
    for filename in sorted(actual):
        encoded_name = filename.encode("utf-8")
        content_digest.update(len(encoded_name).to_bytes(4, "big"))
        content_digest.update(encoded_name)
        content_digest.update(bytes.fromhex(actual[filename].envelope_sha256))
    return {
        "report_image_count": len(referenced),
        "upload_file_count": len(actual),
        "missing_count": 0,
        "orphan_count": 0,
        "missing_image_hash_count": 0,
        "image_hash_mismatch_count": 0,
        "snapshot_content_sha256": content_digest.hexdigest(),
    }


def validate_snapshot_consistency(
    report_images: Iterable[ReportImage],
    upload_dir: Path | int,
    *,
    expected_reader_gid: int | None = None,
) -> dict[str, int | str]:
    referenced = expected_uploads(report_images)
    with opened_upload_snapshot(
        upload_dir,
        expected_reader_gid=expected_reader_gid,
    ) as (_directory_fd, files):
        return snapshot_consistency_result(referenced, _actual_uploads(files))


class _DigestingReader:
    def __init__(self, stream: BinaryIO) -> None:
        self.stream = stream
        self.digest = hashlib.sha256()

    def read(self, size: int = -1) -> bytes:
        chunk = self.stream.read(size)
        self.digest.update(chunk)
        return chunk


def write_validated_upload_archive(
    report_images: Iterable[ReportImage],
    upload_dir: Path | int,
    output: BinaryIO,
    *,
    expected_reader_gid: int | None = None,
) -> dict[str, int | str]:
    referenced = expected_uploads(report_images)
    with opened_upload_snapshot(
        upload_dir,
        expected_reader_gid=expected_reader_gid,
    ) as (_directory_fd, files):
        file_names = set(files)
        missing = sorted(set(referenced) - file_names)
        orphan = sorted(file_names - set(referenced))
        if missing or orphan:
            raise ValueError(
                f"report/upload snapshot is inconsistent: missing={missing[:5]}, orphan={orphan[:5]}"
            )
        inspected = _actual_uploads(files)
        snapshot_consistency_result(referenced, inspected)
        actual: dict[str, ActualUpload] = {}
        with gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w|", format=tarfile.PAX_FORMAT) as archive:
                for filename, (descriptor, metadata) in files.items():
                    info = tarfile.TarInfo(filename)
                    info.size = metadata.st_size
                    info.mode = 0o600
                    info.mtime = int(metadata.st_mtime)
                    info.uid = 0
                    info.gid = 0
                    with os.fdopen(os.dup(descriptor), "rb") as stream:
                        reader = _DigestingReader(stream)
                        archive.addfile(info, reader)  # type: ignore[arg-type]
                        actual[filename] = ActualUpload(
                            envelope_sha256=reader.digest.hexdigest(),
                            envelope_size=metadata.st_size,
                            key_id=inspected[filename].key_id,
                        )
        return snapshot_consistency_result(referenced, actual)


def load_report_images(database_url: str) -> list[ReportImage]:
    dsn = explicit_backup_database_url(database_url)
    with psycopg.connect(dsn, connect_timeout=5) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SET statement_timeout = 10000")
            cursor.execute(
                """
                SELECT
                    reports.id::text,
                    reports.image_path,
                    report_image_objects.report_id::text,
                    report_image_objects.storage_name,
                    report_image_objects.envelope_sha256,
                    report_image_objects.envelope_size,
                    report_image_objects.key_id
                FROM reports
                FULL OUTER JOIN report_image_objects
                  ON report_image_objects.report_id = reports.id
                ORDER BY COALESCE(reports.id, report_image_objects.report_id)
                """
            )
            return [
                ReportImage(
                    report_id=str(row[0]) if row[0] is not None else None,
                    image_path=str(row[1]) if row[1] is not None else None,
                    image_object_report_id=str(row[2]) if row[2] is not None else None,
                    storage_name=str(row[3]) if row[3] is not None else None,
                    envelope_sha256=str(row[4]) if row[4] is not None else None,
                    envelope_size=int(row[5]) if row[5] is not None else None,
                    key_id=str(row[6]) if row[6] is not None else None,
                )
                for row in cursor.fetchall()
            ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    upload_source = parser.add_mutually_exclusive_group(required=True)
    upload_source.add_argument("--upload-dir", type=Path)
    upload_source.add_argument("--upload-dir-fd", type=int)
    parser.add_argument("--upload-reader-gid", type=int)
    parser.add_argument("--archive-output", action="store_true")
    parser.add_argument("--result-fd", type=int)
    args = parser.parse_args()
    database_url = os.environ.get("DATABASE_URL", "").strip()
    if not database_url:
        parser.error("DATABASE_URL is required")
    try:
        upload_dir = (
            args.upload_dir_fd
            if args.upload_dir_fd is not None
            else args.upload_dir.expanduser().absolute()
        )
        report_images = load_report_images(database_url)
        if args.archive_output:
            if args.result_fd is None:
                raise ValueError("--archive-output requires --result-fd")
            result = write_validated_upload_archive(
                report_images,
                upload_dir,
                sys.stdout.buffer,
                expected_reader_gid=args.upload_reader_gid,
            )
            result_metadata = os.fstat(args.result_fd)
            if (
                not stat.S_ISREG(result_metadata.st_mode)
                or result_metadata.st_uid != os.getuid()
                or result_metadata.st_nlink != 0
            ):
                raise ValueError("archive result descriptor must be a private unlinked file")
            encoded_result = json.dumps(
                {"ready": True, **result}, ensure_ascii=False
            ).encode("utf-8")
            os.ftruncate(args.result_fd, 0)
            os.pwrite(args.result_fd, encoded_result, 0)
            os.fsync(args.result_fd)
            return 0
        if args.result_fd is not None:
            raise ValueError("--result-fd requires --archive-output")
        result = validate_snapshot_consistency(
            report_images,
            upload_dir,
            expected_reader_gid=args.upload_reader_gid,
        )
    except (OSError, ValueError, psycopg.Error) as exc:
        print(
            json.dumps({"ready": False, "error": str(exc)}, ensure_ascii=False),
            file=sys.stderr,
        )
        return 2
    print(json.dumps({"ready": True, **result}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
