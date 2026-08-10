from __future__ import annotations

import csv
import hashlib
from pathlib import Path

import pytest

from scripts.walksafe_dataset_integrity import DatasetIntegrityError, verify_content_hashed_manifest


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_fixture(root: Path) -> tuple[Path, Path]:
    image = root / "image.bin"
    label = root / "label.txt"
    manifest = root / "manifest.csv"
    image.write_bytes(b"image-bytes")
    label.write_text("0 0.5 0.5 0.2 0.2\n", encoding="utf-8")
    with manifest.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[
                "split",
                "image_ref",
                "target_image",
                "target_label",
                "image_sha256",
                "label_sha256",
            ],
        )
        writer.writeheader()
        writer.writerow(
            {
                "split": "val",
                "image_ref": "fixture",
                "target_image": image.name,
                "target_label": label.name,
                "image_sha256": digest(image),
                "label_sha256": digest(label),
            }
        )
    return manifest, image


def test_manifest_rehashes_every_bound_artifact(tmp_path: Path) -> None:
    manifest, image = write_fixture(tmp_path)
    result = verify_content_hashed_manifest(manifest, repository_root=tmp_path)
    assert result["rows"] == 1

    image.write_bytes(b"tampered")
    with pytest.raises(DatasetIntegrityError, match="image SHA-256 mismatch"):
        verify_content_hashed_manifest(manifest, repository_root=tmp_path)


def test_manifest_rejects_path_escape(tmp_path: Path) -> None:
    manifest, _ = write_fixture(tmp_path)
    text = manifest.read_text(encoding="utf-8").replace("image.bin", "../image.bin")
    manifest.write_text(text, encoding="utf-8")
    with pytest.raises(DatasetIntegrityError, match="repository-relative"):
        verify_content_hashed_manifest(manifest, repository_root=tmp_path)


def test_manifest_rejects_symlinked_artifact_parent(tmp_path: Path) -> None:
    real = tmp_path / "real"
    real.mkdir()
    manifest, _ = write_fixture(real)
    linked = tmp_path / "linked"
    linked.symlink_to(real, target_is_directory=True)
    manifest_text = manifest.read_text(encoding="utf-8").replace("image.bin", "linked/image.bin").replace(
        "label.txt", "linked/label.txt"
    )
    manifest.write_text(manifest_text, encoding="utf-8")

    with pytest.raises(DatasetIntegrityError, match="symlink path components"):
        verify_content_hashed_manifest(manifest, repository_root=tmp_path)


def test_manifest_path_itself_must_not_use_a_symlinked_parent(tmp_path: Path) -> None:
    real = tmp_path / "real"
    real.mkdir()
    manifest, _ = write_fixture(real)
    linked = tmp_path / "linked"
    linked.symlink_to(real, target_is_directory=True)

    with pytest.raises(DatasetIntegrityError, match="symlink path components"):
        verify_content_hashed_manifest(linked / manifest.name, repository_root=tmp_path)


def test_manifest_rejects_unknown_or_empty_split(tmp_path: Path) -> None:
    manifest, _ = write_fixture(tmp_path)
    manifest.write_text(manifest.read_text(encoding="utf-8").replace("val,", "holdout,"), encoding="utf-8")

    with pytest.raises(DatasetIntegrityError, match="invalid split"):
        verify_content_hashed_manifest(manifest, repository_root=tmp_path)


def test_manifest_rejects_duplicate_image_ref(tmp_path: Path) -> None:
    manifest, _ = write_fixture(tmp_path)
    rows = manifest.read_text(encoding="utf-8").splitlines()
    manifest.write_text("\n".join([*rows, rows[-1]]) + "\n", encoding="utf-8")

    with pytest.raises(DatasetIntegrityError, match="duplicate image_ref"):
        verify_content_hashed_manifest(manifest, repository_root=tmp_path)


def test_manifest_rejects_identical_image_content_across_splits(tmp_path: Path) -> None:
    manifest, image = write_fixture(tmp_path)
    second_image = tmp_path / "same-image.bin"
    second_label = tmp_path / "second-label.txt"
    second_image.write_bytes(image.read_bytes())
    second_label.write_text("0 0.4 0.4 0.1 0.1\n", encoding="utf-8")
    with manifest.open("a", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "train",
                "second-fixture",
                second_image.name,
                second_label.name,
                digest(second_image),
                digest(second_label),
            ]
        )

    with pytest.raises(DatasetIntegrityError, match="crosses splits"):
        verify_content_hashed_manifest(manifest, repository_root=tmp_path)
