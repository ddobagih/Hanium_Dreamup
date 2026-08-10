from __future__ import annotations

import hashlib
import io
import os
from pathlib import Path
import tarfile
from typing import Any
import uuid

import pytest

import scripts.check_walksafe_backup_source_20260713 as backup_source
from backend.app.services.report_image_crypto import encrypt_report_image
from scripts.check_walksafe_backup_source_20260713 import (
    ReportImage,
    report_image_filename,
    validate_snapshot_consistency,
    write_validated_upload_archive,
)


def _private_root(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    path.chmod(0o700)


def _report_image(
    upload_dir: Path,
    report_id: uuid.UUID,
    *,
    plaintext: bytes = b"image",
    key_id: str = "report-key-2026-08",
    logical_suffix: str = ".jpg",
    write: bool = True,
) -> tuple[ReportImage, bytes]:
    _private_root(upload_dir)
    encrypted = encrypt_report_image(
        plaintext,
        report_id=report_id,
        content_type="image/jpeg",
        key_id=key_id,
        key=b"k" * 32,
        nonce=b"n" * 12,
    )
    storage_name = f"{report_id}.wse"
    if write:
        path = upload_dir / storage_name
        path.write_bytes(encrypted.envelope)
        path.chmod(0o600)
    return (
        ReportImage(
            report_id=str(report_id),
            image_path=f"/uploads/{report_id}{logical_suffix}",
            image_object_report_id=str(report_id),
            storage_name=storage_name,
            envelope_sha256=encrypted.envelope_sha256,
            envelope_size=len(encrypted.envelope),
            key_id=encrypted.key_id,
        ),
        encrypted.envelope,
    )


def test_backup_source_uses_encrypted_objects_not_logical_image_paths(tmp_path: Path) -> None:
    first_id = uuid.UUID("11111111-1111-4111-8111-111111111111")
    second_id = uuid.UUID("22222222-2222-4222-8222-222222222222")
    first, first_envelope = _report_image(tmp_path, first_id, plaintext=b"a")
    second, second_envelope = _report_image(tmp_path, second_id, plaintext=b"b")

    result = validate_snapshot_consistency([first, second], tmp_path)

    snapshot_digest = hashlib.sha256()
    for name, envelope in sorted(
        (
            (f"{first_id}.wse", first_envelope),
            (f"{second_id}.wse", second_envelope),
        )
    ):
        encoded_name = name.encode("utf-8")
        snapshot_digest.update(len(encoded_name).to_bytes(4, "big"))
        snapshot_digest.update(encoded_name)
        snapshot_digest.update(hashlib.sha256(envelope).digest())
    assert result == {
        "report_image_count": 2,
        "upload_file_count": 2,
        "missing_count": 0,
        "orphan_count": 0,
        "missing_image_hash_count": 0,
        "image_hash_mismatch_count": 0,
        "snapshot_content_sha256": snapshot_digest.hexdigest(),
    }


def test_backup_archive_contains_only_authoritative_wse_bytes(tmp_path: Path) -> None:
    report_id = uuid.UUID("11111111-1111-4111-8111-111111111111")
    report_image, envelope = _report_image(tmp_path, report_id, plaintext=b"archive-content")
    output = io.BytesIO()

    result = write_validated_upload_archive([report_image], tmp_path, output)

    output.seek(0)
    with tarfile.open(fileobj=output, mode="r:gz") as archive:
        assert archive.getnames() == [f"{report_id}.wse"]
        stream = archive.extractfile(f"{report_id}.wse")
        assert stream is not None
        assert stream.read() == envelope
    assert result["image_hash_mismatch_count"] == 0


def test_backup_archive_rejects_same_inode_content_aba(tmp_path: Path) -> None:
    report_id = uuid.UUID("11111111-1111-4111-8111-111111111111")
    report_image, envelope = _report_image(tmp_path, report_id, plaintext=b"archive-content")
    image = tmp_path / f"{report_id}.wse"

    class MutatingOutput(io.BytesIO):
        mutated = False

        def write(self, chunk: bytes) -> int:
            if not self.mutated:
                self.mutated = True
                image.write_bytes(b"changed-content")
                image.write_bytes(envelope)
            return super().write(chunk)

    with pytest.raises(ValueError, match="upload file changed while reading"):
        write_validated_upload_archive([report_image], tmp_path, MutatingOutput())


@pytest.mark.parametrize(
    "image_path",
    [
        "relative.jpg",
        "/private/11111111-1111-4111-8111-111111111111.jpg",
        "/uploads/../11111111-1111-4111-8111-111111111111.jpg",
        "/uploads/nested/11111111-1111-4111-8111-111111111111.jpg",
        "/uploads/11111111-1111-4111-8111-111111111111.jpeg",
        "/uploads/11111111-1111-4111-8111-111111111111.jpg\t",
        "/uploads/22222222-2222-4222-8222-222222222222.jpg",
    ],
)
def test_backup_source_rejects_unsafe_or_unbound_logical_report_paths(image_path: str) -> None:
    with pytest.raises(ValueError, match="unsafe"):
        report_image_filename(
            image_path,
            report_id=uuid.UUID("11111111-1111-4111-8111-111111111111"),
        )


def test_backup_source_rejects_missing_and_orphan_encrypted_objects(tmp_path: Path) -> None:
    missing, _envelope = _report_image(
        tmp_path,
        uuid.UUID("11111111-1111-4111-8111-111111111111"),
        write=False,
    )
    orphan, _orphan_envelope = _report_image(
        tmp_path,
        uuid.UUID("22222222-2222-4222-8222-222222222222"),
    )

    with pytest.raises(ValueError, match="inconsistent"):
        validate_snapshot_consistency([missing], tmp_path)

    assert orphan.storage_name is not None


def test_backup_source_rejects_nested_and_symlink_entries(tmp_path: Path) -> None:
    _private_root(tmp_path)
    nested = tmp_path / "nested"
    nested.mkdir()
    with pytest.raises(ValueError, match="legacy, plaintext, orphan, or unknown"):
        validate_snapshot_consistency([], tmp_path)

    nested.rmdir()
    outside = tmp_path.parent / "outside.wse"
    outside.write_bytes(b"outside")
    (tmp_path / "11111111-1111-4111-8111-111111111111.wse").symlink_to(outside)
    with pytest.raises(ValueError, match="unsafe"):
        validate_snapshot_consistency([], tmp_path)


def test_backup_source_rejects_duplicate_database_object_bindings(tmp_path: Path) -> None:
    report_id = uuid.UUID("11111111-1111-4111-8111-111111111111")
    report_image, _envelope = _report_image(tmp_path, report_id)

    with pytest.raises(ValueError, match="multiple database rows"):
        validate_snapshot_consistency([report_image, report_image], tmp_path)


@pytest.mark.parametrize("field", ["envelope_sha256", "envelope_size", "key_id"])
def test_backup_source_rejects_database_envelope_metadata_drift(
    tmp_path: Path,
    field: str,
) -> None:
    report_image, _envelope = _report_image(
        tmp_path,
        uuid.UUID("11111111-1111-4111-8111-111111111111"),
    )
    values = vars(report_image).copy()
    values[field] = {
        "envelope_sha256": "0" * 64,
        "envelope_size": report_image.envelope_size + 1,  # type: ignore[operator]
        "key_id": "different-key",
    }[field]

    with pytest.raises(ValueError, match="metadata mismatch"):
        validate_snapshot_consistency([ReportImage(**values)], tmp_path)


@pytest.mark.parametrize("digest", [None, "", "0" * 63, "g" * 64, "A" * 64])
def test_backup_source_rejects_missing_or_invalid_envelope_hash(
    tmp_path: Path,
    digest: str | None,
) -> None:
    report_image, _envelope = _report_image(
        tmp_path,
        uuid.UUID("11111111-1111-4111-8111-111111111111"),
    )
    values = vars(report_image).copy()
    values["envelope_sha256"] = digest

    with pytest.raises(ValueError, match="hash is missing or invalid"):
        validate_snapshot_consistency([ReportImage(**values)], tmp_path)


def test_backup_source_rejects_hardlinked_encrypted_object(tmp_path: Path) -> None:
    first_id = uuid.UUID("11111111-1111-4111-8111-111111111111")
    second_id = uuid.UUID("22222222-2222-4222-8222-222222222222")
    first, _envelope = _report_image(tmp_path, first_id)
    os.link(tmp_path / f"{first_id}.wse", tmp_path / f"{second_id}.wse")

    with pytest.raises(ValueError, match="unsafe"):
        validate_snapshot_consistency([first], tmp_path)


def test_backup_source_rejects_plaintext_unknown_and_mode_drift(tmp_path: Path) -> None:
    _private_root(tmp_path)
    plaintext = tmp_path / "legacy.jpg"
    plaintext.write_bytes(b"plaintext")
    plaintext.chmod(0o600)
    with pytest.raises(ValueError, match="legacy, plaintext, orphan, or unknown"):
        validate_snapshot_consistency([], tmp_path)

    plaintext.unlink()
    report_id = uuid.UUID("11111111-1111-4111-8111-111111111111")
    report_image, _envelope = _report_image(tmp_path, report_id)
    encrypted_path = tmp_path / f"{report_id}.wse"
    encrypted_path.chmod(0o640)
    with pytest.raises(ValueError, match="unsafe"):
        validate_snapshot_consistency([report_image], tmp_path)

    encrypted_path.chmod(0o600)
    tmp_path.chmod(0o750)
    with pytest.raises(ValueError, match="service-owned private"):
        validate_snapshot_consistency([report_image], tmp_path)


def test_backup_source_rejects_plaintext_renamed_to_wse(tmp_path: Path) -> None:
    report_id = uuid.UUID("11111111-1111-4111-8111-111111111111")
    report_image, _envelope = _report_image(tmp_path, report_id)
    path = tmp_path / f"{report_id}.wse"
    path.write_bytes(b"plaintext disguised as encrypted storage")
    path.chmod(0o600)
    values = vars(report_image).copy()
    values["envelope_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    values["envelope_size"] = path.stat().st_size

    with pytest.raises(ValueError, match="not an encrypted report envelope"):
        validate_snapshot_consistency([ReportImage(**values)], tmp_path)


@pytest.mark.parametrize("directory_name", sorted(backup_source.OPERATIONAL_DIRECTORIES))
def test_backup_source_allows_only_empty_private_operational_directories(
    tmp_path: Path,
    directory_name: str,
) -> None:
    report_id = uuid.UUID("11111111-1111-4111-8111-111111111111")
    report_image, _envelope = _report_image(tmp_path, report_id)
    operational = tmp_path / directory_name
    _private_root(operational)

    result = validate_snapshot_consistency([report_image], tmp_path)
    assert result["upload_file_count"] == 1

    operational.chmod(0o750)
    with pytest.raises(ValueError, match="operational directory is unsafe"):
        validate_snapshot_consistency([report_image], tmp_path)
    operational.chmod(0o700)

    evidence = operational / "pending.json"
    evidence.write_text("{}", encoding="utf-8")
    evidence.chmod(0o600)
    with pytest.raises(ValueError, match="nonempty or nonterminal"):
        validate_snapshot_consistency([report_image], tmp_path)
    assert evidence.exists()


@pytest.mark.parametrize("directory_name", sorted(backup_source.OPERATIONAL_DIRECTORIES))
def test_backup_source_rejects_operational_directory_symlinks(
    tmp_path: Path,
    directory_name: str,
) -> None:
    _private_root(tmp_path)
    outside = tmp_path.parent / f"{directory_name}-outside"
    _private_root(outside)
    (tmp_path / directory_name).symlink_to(outside, target_is_directory=True)

    with pytest.raises(ValueError, match="operational directory is unsafe"):
        validate_snapshot_consistency([], tmp_path)


@pytest.mark.parametrize("missing_side", ["report", "image_object"])
def test_full_join_missing_side_fails_closed(tmp_path: Path, missing_side: str) -> None:
    report_image, _envelope = _report_image(
        tmp_path,
        uuid.UUID("11111111-1111-4111-8111-111111111111"),
    )
    values = vars(report_image).copy()
    values["report_id" if missing_side == "report" else "image_object_report_id"] = None

    with pytest.raises(ValueError, match="FULL JOIN"):
        validate_snapshot_consistency([ReportImage(**values)], tmp_path)


def test_backup_source_directory_fd_is_not_redirected_by_path_swap(tmp_path: Path) -> None:
    upload = tmp_path / "uploads"
    displaced = tmp_path / "displaced"
    replacement = tmp_path / "replacement"
    report_id = uuid.UUID("11111111-1111-4111-8111-111111111111")
    report_image, _envelope = _report_image(upload, report_id, plaintext=b"original")
    replacement_row, _replacement_envelope = _report_image(
        replacement,
        report_id,
        plaintext=b"replacement",
    )
    descriptor = os.open(upload, os.O_RDONLY | os.O_DIRECTORY)
    try:
        upload.rename(displaced)
        replacement.rename(upload)
        result = validate_snapshot_consistency([report_image], descriptor)
        assert result["image_hash_mismatch_count"] == 0
        with pytest.raises(ValueError, match="metadata mismatch"):
            validate_snapshot_consistency([report_image], upload)
        assert replacement_row.storage_name == report_image.storage_name
    finally:
        os.close(descriptor)


def test_load_report_images_uses_full_outer_join_and_encrypted_metadata(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    report_id = "11111111-1111-4111-8111-111111111111"
    statements: list[str] = []

    class Cursor:
        def __enter__(self) -> Cursor:
            return self

        def __exit__(self, *_args: Any) -> None:
            return None

        def execute(self, statement: str) -> None:
            statements.append(statement)

        def fetchall(self) -> list[tuple[Any, ...]]:
            return [
                (
                    report_id,
                    f"/uploads/{report_id}.jpg",
                    report_id,
                    f"{report_id}.wse",
                    "a" * 64,
                    512,
                    "key-1",
                )
            ]

    class Connection:
        def __enter__(self) -> Connection:
            return self

        def __exit__(self, *_args: Any) -> None:
            return None

        def cursor(self) -> Cursor:
            return Cursor()

    monkeypatch.setattr(backup_source, "explicit_backup_database_url", lambda value: value)
    monkeypatch.setattr(
        backup_source.psycopg,
        "connect",
        lambda *_args, **_kwargs: Connection(),
    )

    rows = backup_source.load_report_images("postgresql://backup")

    query = "\n".join(statements)
    assert "FULL OUTER JOIN report_image_objects" in query
    assert "report_image_objects.envelope_sha256" in query
    assert "metadata->>'image_sha256'" not in query
    assert rows[0].storage_name == f"{report_id}.wse"
