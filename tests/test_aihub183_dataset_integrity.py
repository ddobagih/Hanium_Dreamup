from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "data_sources/scripts/append_aihub183_escooter_to_reviewed_dataset.py"
SPEC = importlib.util.spec_from_file_location("append_aihub183", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def image(member: str, split: str):
    return MODULE.ApprovedImage(Path("source.zip"), member, split, 1920, 1080)


def test_capture_sequence_key_removes_only_terminal_frame_identity() -> None:
    assert MODULE.capture_sequence_key("20210916_210816_맑음_야간_1688.png") == "20210916_210816_맑음_야간"
    assert MODULE.capture_sequence_key("20210916_200357_흐림_야간_95_w0013.png") == "20210916_200357_흐림_야간"


def test_cross_split_capture_sequence_is_rejected() -> None:
    rows = [
        image("20210916_210816_맑음_야간_1688.png", "train"),
        image("20210916_210816_맑음_야간_1709.png", "val"),
    ]
    with pytest.raises(RuntimeError, match="leaks capture sequences"):
        MODULE.reject_cross_split_sequences(rows)


def test_append_rejects_base_manifest_without_content_hashes(tmp_path: Path) -> None:
    base = tmp_path / "base"
    target = tmp_path / "target"
    base.mkdir()
    target.mkdir()
    (base / "materialized_manifest.csv").write_text(
        "split,source,image_ref,target_image,target_label,boxes\n",
        encoding="utf-8",
    )

    with pytest.raises(RuntimeError, match="predates per-file content hashes"):
        MODULE.rewrite_manifest(base, target, [])


def test_default_absolute_target_is_recorded_repo_relative() -> None:
    target = MODULE.builder.DEFAULT_TARGET / "images" / "train" / "fixture.jpg"
    value = MODULE.builder.repo_relative_artifact_path(target)
    assert value == "datasets/walksafe_unified_coco_aihub_13cls_20260619/images/train/fixture.jpg"


def test_target_outside_repository_is_rejected(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="must stay inside repository"):
        MODULE.builder.repo_relative_artifact_path(tmp_path / "dataset")


def test_manifest_artifact_resolution_does_not_depend_on_cwd(tmp_path: Path, monkeypatch) -> None:
    expected = ROOT / "datasets" / "fixture" / "images" / "train" / "one.jpg"
    monkeypatch.chdir(tmp_path)

    resolved = MODULE.builder.resolve_repo_artifact_path(
        "datasets/fixture/images/train/one.jpg"
    )

    assert resolved == expected


def test_validate_new_outputs_resolves_repo_relative_manifest_paths(
    tmp_path: Path, monkeypatch
) -> None:
    dataset = ROOT / "datasets" / "walksafe_manifest_path_test"
    image_path = dataset / "images" / "train" / "one.jpg"
    label_path = dataset / "labels" / "train" / "one.txt"
    image_path.parent.mkdir(parents=True, exist_ok=True)
    label_path.parent.mkdir(parents=True, exist_ok=True)
    image_path.write_bytes(b"fixture")
    label_path.write_text("12 0.5 0.5 0.2 0.2\n", encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    try:
        result = MODULE.validate_new_outputs(
            [
                {
                    "target_image": image_path.relative_to(ROOT).as_posix(),
                    "target_label": label_path.relative_to(ROOT).as_posix(),
                }
            ]
        )
        assert result["ok"] is True
    finally:
        label_path.unlink(missing_ok=True)
        image_path.unlink(missing_ok=True)
        label_path.parent.rmdir()
        image_path.parent.rmdir()
        (dataset / "labels").rmdir()
        (dataset / "images").rmdir()
        dataset.rmdir()
