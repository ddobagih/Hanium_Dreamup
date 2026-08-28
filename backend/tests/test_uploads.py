from __future__ import annotations

import io
import os
from pathlib import Path
import stat
import sys

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("UPLOAD_DIR", str(ROOT / "backend" / "uploads" / "test"))

from backend.app.config import get_settings  # noqa: E402
from backend.app.main import app  # noqa: E402
from backend.app.uploads import (  # noqa: E402
    MAX_IMAGE_DIMENSION,
    ImageDimensionsTooLarge,
    strip_image_metadata,
    write_image_file,
)
from asgi_client import ASGITestClient  # noqa: E402


def test_legacy_plaintext_upload_is_never_served_without_database_admin_grant(tmp_path: Path) -> None:
    settings = get_settings()
    previous_upload_dir = settings.upload_dir
    settings.upload_dir = tmp_path
    (tmp_path / "sample.jpg").write_bytes(b"\xff\xd8\xff\xe0" + (b"0" * 16))

    try:
        response = ASGITestClient(app).get("/uploads/sample.jpg")
    finally:
        settings.upload_dir = previous_upload_dir

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "admin_security_required"
    assert not response.content.startswith(b"\xff\xd8\xff")


def test_upload_route_rejects_path_traversal(tmp_path: Path) -> None:
    settings = get_settings()
    previous_upload_dir = settings.upload_dir
    settings.upload_dir = tmp_path

    try:
        response = ASGITestClient(app).get("/uploads/../secret.jpg")
    finally:
        settings.upload_dir = previous_upload_dir

    assert response.status_code == 404


def test_upload_route_never_falls_back_to_a_plaintext_symlink(tmp_path: Path) -> None:
    settings = get_settings()
    previous_upload_dir = settings.upload_dir
    outside = tmp_path / "outside.jpg"
    outside.write_bytes(b"private")
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir()
    (upload_dir / "linked.jpg").symlink_to(outside)
    settings.upload_dir = upload_dir

    try:
        response = ASGITestClient(app).get("/uploads/linked.jpg")
    finally:
        settings.upload_dir = previous_upload_dir

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "admin_security_required"
    assert response.content != b"private"


def test_strip_image_metadata_removes_jpeg_exif_when_decodable() -> None:
    from PIL import Image

    source = io.BytesIO()
    image = Image.new("RGB", (2, 2), color=(255, 0, 0))
    image.save(source, format="JPEG", exif=b"Exif\x00\x00TEST-EXIF")

    sanitized = strip_image_metadata(source.getvalue(), "image/jpeg")

    assert sanitized.startswith(b"\xff\xd8\xff")
    assert b"TEST-EXIF" not in sanitized


def test_strip_image_metadata_rejects_signature_only_malformed_image() -> None:
    import pytest

    with pytest.raises(ValueError, match="decoded and sanitized"):
        strip_image_metadata(b"\xff\xd8\xff\xe0" + (b"0" * 16), "image/jpeg")


def test_strip_image_metadata_rejects_oversized_dimensions_before_decode(monkeypatch) -> None:
    import pytest
    from PIL import Image

    class OversizedImage:
        size = (MAX_IMAGE_DIMENSION + 1, 1)

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

        def load(self) -> None:
            raise AssertionError("oversized image must be rejected before pixel decode")

    monkeypatch.setattr(Image, "open", lambda _source: OversizedImage())

    with pytest.raises(ImageDimensionsTooLarge, match="dimensions"):
        strip_image_metadata(b"\xff\xd8\xff\xe0", "image/jpeg")


def test_write_image_file_enforces_private_directory_and_file_modes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "development")
    upload_dir = tmp_path / "uploads"
    destination = upload_dir / "report.jpg"

    write_image_file(destination, b"private-image")

    assert upload_dir.stat().st_mode & 0o777 == 0o700
    assert destination.stat().st_mode & 0o777 == 0o600


@pytest.mark.parametrize("environment", ["field", "staging", "production"])
def test_write_image_file_rejects_private_upload_root_in_deployment(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    environment: str,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", environment)
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    destination = upload_dir / "report.wse"

    with pytest.raises(ValueError, match="backup-reader group"):
        write_image_file(destination, b"encrypted-envelope")

    assert not destination.exists()


def test_write_image_file_preserves_read_only_backup_group_contract(
    tmp_path: Path,
    monkeypatch,
) -> None:
    from backend.app import uploads

    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o750)
    upload_dir.chmod(0o2750)
    destination = upload_dir / "report.wse"
    monkeypatch.setattr(
        uploads.grp,
        "getgrnam",
        lambda _name: type("Group", (), {"gr_gid": upload_dir.stat().st_gid})(),
    )

    uploads.write_image_file(destination, b"encrypted-envelope")

    assert upload_dir.stat().st_mode & 0o7777 == 0o2750
    assert destination.stat().st_mode & 0o777 == 0o640
    assert destination.stat().st_gid == upload_dir.stat().st_gid


def test_write_image_file_rejects_upload_root_or_inherited_file_acl(
    tmp_path: Path,
    monkeypatch,
) -> None:
    from backend.app import uploads

    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    destination = upload_dir / "report.wse"
    upload_identity = (upload_dir.stat().st_dev, upload_dir.stat().st_ino)

    def root_acl(descriptor: int) -> list[bytes]:
        metadata = os.fstat(descriptor)
        return (
            [b"system.posix_acl_default"]
            if (metadata.st_dev, metadata.st_ino) == upload_identity
            else []
        )

    monkeypatch.setattr(uploads.os, "listxattr", root_acl)
    with pytest.raises(OSError, match="ACL"):
        uploads.write_image_file(destination, b"encrypted-envelope")
    assert not destination.exists()

    monkeypatch.setattr(
        uploads.os,
        "listxattr",
        lambda descriptor: (
            [b"system.posix_acl_access"]
            if stat.S_ISREG(os.fstat(descriptor).st_mode)
            else []
        ),
    )
    with pytest.raises(OSError, match="ACL"):
        uploads.write_image_file(destination, b"encrypted-envelope")
    assert not destination.exists()
    assert not list(upload_dir.glob(".*.tmp"))

    file_acl_checks = 0

    def acl_added_during_write(descriptor: int) -> list[bytes]:
        nonlocal file_acl_checks
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            return []
        file_acl_checks += 1
        return [] if file_acl_checks == 1 else [b"system.posix_acl_access"]

    monkeypatch.setattr(uploads.os, "listxattr", acl_added_during_write)
    with pytest.raises(OSError, match="metadata changed"):
        uploads.write_image_file(destination, b"encrypted-envelope")
    assert file_acl_checks == 2
    assert not destination.exists()
    assert not list(upload_dir.glob(".*.tmp"))


@pytest.mark.parametrize("drift_target", ["file", "directory"])
def test_write_image_file_rejects_metadata_drift_before_publish(
    tmp_path: Path,
    monkeypatch,
    drift_target: str,
) -> None:
    from backend.app import uploads

    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    destination = upload_dir / "report.wse"
    real_fsync = uploads.os.fsync
    drift_injected = False

    def inject_drift(descriptor: int) -> None:
        nonlocal drift_injected
        real_fsync(descriptor)
        if drift_injected or not stat.S_ISREG(os.fstat(descriptor).st_mode):
            return
        drift_injected = True
        if drift_target == "file":
            os.fchmod(descriptor, 0o644)
        else:
            upload_dir.chmod(0o755)

    monkeypatch.setattr(uploads.os, "fsync", inject_drift)
    with pytest.raises(OSError, match="metadata changed"):
        uploads.write_image_file(destination, b"encrypted-envelope")
    assert drift_injected is True
    assert not destination.exists()
    assert not list(upload_dir.glob(".*.tmp"))


def test_write_image_file_rejects_wrong_backup_reader_gid_and_closes_fd_on_cleanup_error(
    tmp_path: Path,
    monkeypatch,
) -> None:
    from backend.app import uploads

    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    upload_dir.chmod(0o2750)
    destination = upload_dir / "report.wse"
    monkeypatch.setattr(
        uploads.grp,
        "getgrnam",
        lambda _name: type(
            "Group",
            (),
            {"gr_gid": upload_dir.stat().st_gid + 1},
        )(),
    )
    with pytest.raises(ValueError, match="backup-reader group differs"):
        uploads.write_image_file(destination, b"encrypted-envelope")
    assert not destination.exists()

    monkeypatch.setattr(
        uploads.grp,
        "getgrnam",
        lambda _name: type("Group", (), {"gr_gid": upload_dir.stat().st_gid})(),
    )
    real_open = uploads.os.open
    directory_fd = -1

    def recording_open(path, flags, *args, **kwargs):
        nonlocal directory_fd
        descriptor = real_open(path, flags, *args, **kwargs)
        if flags & getattr(os, "O_DIRECTORY", 0):
            directory_fd = descriptor
        return descriptor

    monkeypatch.setattr(uploads.os, "open", recording_open)
    monkeypatch.setattr(
        uploads.os,
        "listxattr",
        lambda descriptor: (
            [b"system.posix_acl_access"]
            if stat.S_ISREG(os.fstat(descriptor).st_mode)
            else []
        ),
    )
    monkeypatch.setattr(
        uploads.os,
        "unlink",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            PermissionError("injected cleanup failure")
        ),
    )
    with pytest.raises(RuntimeError, match="cleanup failed") as captured:
        uploads.write_image_file(destination, b"encrypted-envelope")
    assert isinstance(captured.value.__cause__, OSError)
    with pytest.raises(OSError):
        os.fstat(directory_fd)


def test_write_image_file_fsyncs_content_and_parent_directory(tmp_path: Path, monkeypatch) -> None:
    from backend.app import uploads

    fsync_kinds: list[str] = []
    real_fsync = os.fsync

    def recording_fsync(descriptor: int) -> None:
        metadata = os.fstat(descriptor)
        fsync_kinds.append("directory" if stat.S_ISDIR(metadata.st_mode) else "file")
        real_fsync(descriptor)

    monkeypatch.setattr(uploads.os, "fsync", recording_fsync)

    uploads.write_image_file(tmp_path / "uploads" / "report.jpg", b"durable-image")

    assert fsync_kinds == ["file", "directory"]
