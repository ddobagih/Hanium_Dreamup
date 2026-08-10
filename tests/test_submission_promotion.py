from __future__ import annotations

import copy
import hashlib
import json
import sys
import subprocess
from pathlib import Path

import pytest
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import promote_submission_final_20260713 as promotion  # noqa: E402
import validate_submission_forms_20260710 as form_validator  # noqa: E402
from submission_build_io import candidate_source_freeze_policy  # noqa: E402
from submission_manifest_policy import source_revision  # noqa: E402


APPROVED_SOURCE_FREEZE_POLICY = (
    "사용자가 승인한 source-freeze commit의 자동검증 snapshot이며 최종 source-freeze "
    "근거로 채택했다. Field 또는 Release 증거로 확대하지 않고 PASS와 FAIL을 함께 "
    "기록하며 서로 다른 계층 수를 합산하지 않는다. current source-freeze Android "
    "device run은 NOT_RUN_CURRENT_SOURCE_FREEZE로 기록하고, 2026-07-13 SM-G981N "
    "2/2는 별도 historical evidence로만 보존한다."
)


def test_final_promotion_rejects_source_freeze_candidate(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    facts = json.loads(promotion.FACTS_PATH.read_text(encoding="utf-8"))
    facts["verification_snapshot"]["status"] = "SOURCE_FREEZE_CANDIDATE_2026-07-16"
    facts["verification_snapshot"]["policy"] = candidate_source_freeze_policy("2026-07-16")

    with pytest.raises(ValueError, match="approved source-freeze snapshot"):
        promotion.validate_promotable_verification_snapshot(facts)

    candidate_path = tmp_path / "candidate-facts.json"
    candidate_path.write_text(json.dumps(facts), encoding="utf-8")
    monkeypatch.setattr(promotion, "FACTS_PATH", candidate_path)
    with pytest.raises(ValueError, match="approved source-freeze snapshot"):
        promotion.validate_clean_upstream_manifests()

    facts["verification_snapshot"]["status"] = "SOURCE_FREEZE_APPROVED_2026-07-16"
    with pytest.raises(ValueError, match="policy"):
        promotion.validate_promotable_verification_snapshot(facts)


def test_final_promotion_accepts_explicitly_approved_source_freeze() -> None:
    facts = json.loads(promotion.FACTS_PATH.read_text(encoding="utf-8"))

    promotion.validate_promotable_verification_snapshot(facts)


def test_final_promotion_rejects_impossible_snapshot_date() -> None:
    facts = json.loads(promotion.FACTS_PATH.read_text(encoding="utf-8"))
    facts["verification_snapshot"]["executed_at"] = "2026-02-30"
    facts["verification_snapshot"]["status"] = "SOURCE_FREEZE_APPROVED_2026-02-30"
    facts["verification_snapshot"]["policy"] = APPROVED_SOURCE_FREEZE_POLICY

    with pytest.raises(ValueError, match="approved source-freeze snapshot"):
        promotion.validate_promotable_verification_snapshot(facts)


def test_final_promotion_rejects_snapshot_before_historical_device_evidence() -> None:
    facts = json.loads(promotion.FACTS_PATH.read_text(encoding="utf-8"))
    facts["verification_snapshot"]["executed_at"] = "2026-07-12"
    facts["verification_snapshot"]["status"] = "SOURCE_FREEZE_APPROVED_2026-07-12"
    facts["verification_snapshot"]["policy"] = APPROVED_SOURCE_FREEZE_POLICY

    with pytest.raises(ValueError, match="device evidence is not canonical"):
        promotion.validate_promotable_verification_snapshot(facts)


def test_promotion_child_validators_use_isolated_submission_runner(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    commit = "a" * 40
    monkeypatch.setenv("WALKSAFE_SUBMISSION_SOURCE_COMMIT", commit)

    argv = promotion.submission_python_argv(
        "validate_submission_forms_20260710.py",
        "--working-only",
    )

    assert argv[:4] == [sys.executable, "-I", "-S", "-B"]
    assert argv[4] == str(promotion.SUBMISSION_RUNNER)
    assert argv[5:9] == [
        "--repo-root",
        str(promotion.REPO_ROOT),
        "--expected-commit",
        commit,
    ]
    assert argv[9:] == [
        "--",
        "scripts/validate_submission_forms_20260710.py",
        "--working-only",
    ]


def test_promotion_executes_every_child_validator_through_submission_runner(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    commit = "b" * 40
    calls: list[tuple[list[str], Path, bool]] = []

    def record_call(argv: list[str], *, cwd: Path, check: bool) -> None:
        calls.append((argv, cwd, check))

    monkeypatch.setenv("WALKSAFE_SUBMISSION_SOURCE_COMMIT", commit)
    monkeypatch.setattr(promotion.subprocess, "run", record_call)

    promotion.run_upstream_validators()
    promotion.run_validator("--final-dir", "candidate")

    assert len(calls) == 4
    assert [call[0][10] for call in calls] == [
        "scripts/validate_submission_materials_20260710.py",
        "scripts/build_design_documents_20260710.py",
        "scripts/validate_submission_forms_20260710.py",
        "scripts/validate_submission_forms_20260710.py",
    ]
    for argv, cwd, check in calls:
        assert argv[:4] == [sys.executable, "-I", "-S", "-B"]
        assert argv[4] == str(promotion.SUBMISSION_RUNNER)
        assert argv[5:9] == [
            "--repo-root",
            str(promotion.REPO_ROOT),
            "--expected-commit",
            commit,
        ]
        assert argv[9] == "--"
        assert cwd == promotion.REPO_ROOT
        assert check is True


def snapshot(directory: Path) -> dict[str, bytes]:
    return {
        path.relative_to(directory).as_posix(): path.read_bytes()
        for path in sorted(directory.rglob("*"))
        if path.is_file()
    }


def receipt_with_identity(reviewer: object, reviewed_at: object) -> dict[str, object]:
    return {
        "schema_version": "walksafe.submission-visual-privacy.v2",
        "source_commit": "a" * 40,
        "reviewed_at": reviewed_at,
        "reviewer": reviewer,
        "reviewer_kind": "human",
        "automatic": {
            "assets": [],
            "metadata_violations": [],
            "face_candidates": [],
            "automatic_face_detection_limit": "Haar 후보 검사이며 얼굴·차량번호·OCR 부재를 단독 보장하지 않음",
            "documents": [],
            "final_documents": [],
            "office_render": {},
        },
        "manual_assertions": {
            "no_visible_faces": True,
            "no_visible_license_plates": True,
            "no_personal_addresses_or_accounts": True,
            "office_pdf_render_reviewed": True,
        },
        "release_ready": True,
    }


def valid_exact_receipt(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> dict[str, object]:
    asset_dir = tmp_path / "assets"
    design_dir = tmp_path / "design"
    office_dir = tmp_path / "office"
    asset_dir.mkdir()
    design_dir.mkdir()
    office_dir.mkdir()
    for name in promotion.ASSET_PNG_NAMES:
        Image.new("RGB", (16, 9), "white").save(asset_dir / name)
    for name in promotion.DESIGN_DOCUMENT_NAMES:
        (design_dir / name).write_bytes(f"design:{name}".encode())
    report = office_dir / "개발보고서 양식.docx"
    slides = office_dir / "제작설계서_일반.pptx"
    report.write_bytes(b"report")
    slides.write_bytes(b"slides")
    monkeypatch.setattr(promotion, "ASSET_DIR", asset_dir)
    monkeypatch.setattr(promotion, "DESIGN_DIR", design_dir)
    monkeypatch.setattr(promotion, "WORKING_REPORT", report)
    monkeypatch.setattr(promotion, "WORKING_SLIDES", slides)
    return {
        "schema_version": "walksafe.submission-visual-privacy.v2",
        "source_commit": "a" * 40,
        "reviewed_at": "2026-07-13T12:00:00+00:00",
        "reviewer": "reviewer.kim",
        "reviewer_kind": "human",
        "automatic": {
            "assets": [
                {
                    "name": name,
                    "sha256": promotion.sha256(asset_dir / name),
                    "width": 16,
                    "height": 9,
                }
                for name in sorted(promotion.ASSET_PNG_NAMES)
            ],
            "metadata_violations": [],
            "face_candidates": [],
            "automatic_face_detection_limit": "Haar 후보 검사이며 얼굴·차량번호·OCR 부재를 단독 보장하지 않음",
            "documents": [
                {"name": name, "sha256": promotion.sha256(design_dir / name)}
                for name in sorted(promotion.DESIGN_DOCUMENT_NAMES)
            ],
            "final_documents": [
                {"name": report.name, "sha256": promotion.sha256(report)},
                {"name": slides.name, "sha256": promotion.sha256(slides)},
            ],
            "office_render": {
                "report": {"name": report.name, "sha256": promotion.sha256(report), "pdf_pages": 7},
                "slides": {
                    "name": slides.name,
                    "sha256": promotion.sha256(slides),
                    "pdf_pages": 34,
                    "slides": 34,
                    "aspect_ratio": "4:3",
                },
            },
        },
        "manual_assertions": {
            "no_visible_faces": True,
            "no_visible_license_plates": True,
            "no_personal_addresses_or_accounts": True,
            "office_pdf_render_reviewed": True,
        },
        "release_ready": True,
    }


def test_final_directory_swap_restores_old_tree_on_validation_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    final = tmp_path / "final"
    staging = tmp_path / "staging"
    backup = tmp_path / "backup"
    final.mkdir()
    staging.mkdir()
    for name in promotion.FINAL_OUTPUT_NAMES:
        (final / name).write_bytes(f"old:{name}".encode())
        (staging / name).write_bytes(f"new:{name}".encode())
    old_snapshot = snapshot(final)

    monkeypatch.setattr(promotion, "FINAL_DIR", final)
    monkeypatch.setattr(promotion, "STAGING_DIR", staging)
    monkeypatch.setattr(promotion, "BACKUP_DIR", backup)

    def fail_validation(*_args: str) -> None:
        raise RuntimeError("injected validator failure")

    monkeypatch.setattr(promotion, "run_validator", fail_validation)
    with pytest.raises(RuntimeError, match="injected validator failure"):
        promotion.swap_final_with_rollback()

    assert snapshot(final) == old_snapshot
    assert not staging.exists()
    assert not backup.exists()


def test_json_loader_rejects_duplicate_keys_and_symlink(tmp_path: Path) -> None:
    duplicate = tmp_path / "duplicate.json"
    duplicate.write_text('{"reviewer":"one","reviewer":"two"}', encoding="utf-8")
    with pytest.raises(ValueError, match="duplicate JSON key"):
        promotion.load_json(duplicate)

    link = tmp_path / "receipt-link.json"
    link.symlink_to(duplicate)
    with pytest.raises(ValueError, match="non-symlink"):
        promotion.load_json(link)


def test_json_snapshot_binds_exact_bytes_not_only_parsed_value(tmp_path: Path) -> None:
    compact = tmp_path / "compact.json"
    formatted = tmp_path / "formatted.json"
    compact.write_bytes(b'{"reviewer":"reviewer.kim"}\n')
    formatted.write_bytes(b'{\n  "reviewer": "reviewer.kim"\n}\n')

    compact_value, compact_bytes, compact_hash = promotion.load_json_snapshot(compact)
    formatted_value, formatted_bytes, formatted_hash = promotion.load_json_snapshot(formatted)

    assert compact_value == formatted_value
    assert compact_bytes != formatted_bytes
    assert compact_hash != formatted_hash


def test_promoted_readme_separates_current_not_run_from_historical_device_evidence(
    tmp_path: Path,
) -> None:
    facts = promotion.load_json(promotion.FACTS_PATH)
    output = tmp_path / "README.md"

    promotion.update_readme(
        "a" * 64,
        "b" * 64,
        13,
        34,
        facts,
        output_path=output,
    )

    text = output.read_text(encoding="utf-8")
    current_label, historical_label = promotion.android_device_evidence(facts)
    assert current_label in text
    assert historical_label in text
    assert "Android instrumentation 2/2" not in text


def test_json_snapshot_rejects_path_replacement_during_read(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    receipt = tmp_path / "receipt.json"
    replacement = tmp_path / "replacement.json"
    receipt.write_bytes(b'{"reviewer":"first"}\n')
    replacement.write_bytes(b'{"reviewer":"second"}\n')
    real_read = promotion.os.read
    replaced = False

    def replace_after_first_read(descriptor: int, size: int) -> bytes:
        nonlocal replaced
        chunk = real_read(descriptor, size)
        if not replaced:
            replacement.replace(receipt)
            replaced = True
        return chunk

    monkeypatch.setattr(promotion.os, "read", replace_after_first_read)
    with pytest.raises(ValueError, match="changed while it was read"):
        promotion.load_json_snapshot(receipt)


def test_staging_copies_locked_receipt_bytes_exactly(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    staging = tmp_path / "staging"
    backup = tmp_path / "backup"
    report = tmp_path / "report.docx"
    slides = tmp_path / "slides.pptx"
    report.write_bytes(b"report")
    slides.write_bytes(b"slides")
    receipt_bytes = b'{\n  "reviewer": "reviewer.kim"\n}\n'

    monkeypatch.setattr(promotion, "STAGING_DIR", staging)
    monkeypatch.setattr(promotion, "BACKUP_DIR", backup)
    monkeypatch.setattr(promotion, "WORKING_REPORT", report)
    monkeypatch.setattr(promotion, "WORKING_SLIDES", slides)
    monkeypatch.setattr(
        promotion,
        "update_readme",
        lambda *_args: (staging / "README.md").write_text("readme", encoding="utf-8"),
    )
    monkeypatch.setattr(promotion, "build_manifest", lambda *_args: {})
    monkeypatch.setattr(
        promotion,
        "FINAL_OUTPUT_NAMES",
        {
            "ASSISTANT_VISUAL_PRIVACY_REVIEW.json",
            "BUILD_MANIFEST.json",
            "README.md",
            report.name,
            slides.name,
        },
    )

    promotion.prepare_staging_final(
        receipt_bytes,
        hashlib.sha256(receipt_bytes).hexdigest(),
        1,
        34,
    )

    assert (staging / "ASSISTANT_VISUAL_PRIVACY_REVIEW.json").read_bytes() == receipt_bytes


def test_final_tree_snapshot_has_exact_five_file_identity_records(tmp_path: Path) -> None:
    final = tmp_path / "final"
    final.mkdir()
    for name in promotion.FINAL_OUTPUT_NAMES:
        (final / name).write_bytes(f"candidate:{name}".encode())

    records = promotion.final_tree_snapshot(final)

    assert set(records) == promotion.FINAL_OUTPUT_NAMES
    assert all(
        set(record) == {"path", "type", "mode", "size", "sha256", "device", "inode"}
        and record["path"] == name
        and record["type"] == "regular"
        for name, record in records.items()
    )


def test_valid_privacy_receipt_binds_exact_18_8_2_sets(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    receipt = valid_exact_receipt(tmp_path, monkeypatch)

    assert len(receipt["automatic"]["assets"]) == 18
    assert len(receipt["automatic"]["documents"]) == 8
    assert len(receipt["automatic"]["final_documents"]) == 2
    assert promotion.validate_privacy_receipt(receipt, source_commit="a" * 40) == (7, 34)


def test_privacy_receipt_rejects_different_clean_source_commit(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    receipt = valid_exact_receipt(tmp_path, monkeypatch)

    with pytest.raises(ValueError, match="source commit"):
        promotion.validate_privacy_receipt(receipt, source_commit="b" * 40)


@pytest.mark.parametrize(
    "mutation",
    ["missing_asset", "duplicate_design", "office_hash_drift", "slide_count_drift"],
)
def test_privacy_receipt_rejects_exact_set_and_office_evidence_mutations(
    mutation: str,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    receipt = copy.deepcopy(valid_exact_receipt(tmp_path, monkeypatch))
    automatic = receipt["automatic"]
    if mutation == "missing_asset":
        automatic["assets"].pop()
    elif mutation == "duplicate_design":
        automatic["documents"].append(copy.deepcopy(automatic["documents"][0]))
    elif mutation == "office_hash_drift":
        automatic["final_documents"][0]["sha256"] = "0" * 64
    else:
        automatic["office_render"]["slides"]["slides"] = 33

    with pytest.raises(ValueError, match="file set|hashes|Office render"):
        promotion.validate_privacy_receipt(receipt, source_commit="a" * 40)


@pytest.mark.parametrize(
    ("reviewer", "reviewed_at"),
    [
        ([], "2026-07-13T12:00:00+00:00"),
        ({}, "2026-07-13T12:00:00+00:00"),
        ("reviewer.kim", "2026-07-13T12:00:00"),
        ("reviewer.kim", "2026-07-13T21:00:00+09:00"),
    ],
)
def test_promotion_rejects_non_string_reviewer_and_non_utc_timestamp(
    reviewer: object,
    reviewed_at: object,
) -> None:
    with pytest.raises(ValueError, match="reviewer|reviewed_at"):
        promotion.validate_privacy_receipt(
            receipt_with_identity(reviewer, reviewed_at),
            source_commit="a" * 40,
        )


def test_successful_swap_keeps_repository_source_clean_while_backup_exists(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "config", "user.name", "Test"], check=True)
    subprocess.run(
        ["git", "-C", str(tmp_path), "config", "user.email", "test@example.invalid"],
        check=True,
    )
    (tmp_path / "source.md").write_text("source", encoding="utf-8")
    final = tmp_path / "docs/submission/final"
    staging = tmp_path / "docs/submission/.final-promotion-staging"
    backup = tmp_path / "docs/submission/.final-promotion-backup"
    final.mkdir(parents=True)
    for name in promotion.FINAL_OUTPUT_NAMES:
        (final / name).write_bytes(f"old:{name}".encode())
    subprocess.run(["git", "-C", str(tmp_path), "add", "."], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "commit", "-qm", "fixture"], check=True)

    staging.mkdir()
    for name in promotion.FINAL_OUTPUT_NAMES:
        (staging / name).write_bytes(f"new:{name}".encode())
    new_snapshot = snapshot(staging)
    old_snapshot = snapshot(final)

    monkeypatch.setattr(promotion, "FINAL_DIR", final)
    monkeypatch.setattr(promotion, "STAGING_DIR", staging)
    monkeypatch.setattr(promotion, "BACKUP_DIR", backup)

    def validate_during_swap(*args: str) -> None:
        revision = source_revision(tmp_path, promotion.ALL_SUBMISSION_GENERATED_PATHS)
        assert revision["source_dirty"] is False
        assert args == ("--final-dir", str(staging))
        assert staging.is_dir()
        assert not backup.exists()
        assert snapshot(final) == old_snapshot

    monkeypatch.setattr(promotion, "run_validator", validate_during_swap)
    result = promotion.swap_final_with_rollback()

    assert result == "COMMITTED_WITH_WARNING"
    assert snapshot(final) == new_snapshot
    assert not staging.exists()
    assert snapshot(backup) == old_snapshot


def test_exchange_failure_keeps_old_tree_and_removes_candidate(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    final = tmp_path / "final"
    staging = tmp_path / "staging"
    backup = tmp_path / "backup"
    final.mkdir()
    staging.mkdir()
    for name in promotion.FINAL_OUTPUT_NAMES:
        (final / name).write_bytes(f"old:{name}".encode())
        (staging / name).write_bytes(f"new:{name}".encode())
    old_snapshot = snapshot(final)

    monkeypatch.setattr(promotion, "FINAL_DIR", final)
    monkeypatch.setattr(promotion, "STAGING_DIR", staging)
    monkeypatch.setattr(promotion, "BACKUP_DIR", backup)
    monkeypatch.setattr(promotion, "run_validator", lambda *_args: None)

    def fail_exchange(_left: Path, _right: Path) -> None:
        raise OSError("injected exchange failure")

    monkeypatch.setattr(promotion, "atomic_exchange_directories", fail_exchange)
    with pytest.raises(OSError, match="injected exchange failure"):
        promotion.swap_final_with_rollback()

    assert snapshot(final) == old_snapshot
    assert not staging.exists()
    assert not backup.exists()


def test_prepublication_directory_sync_failure_preserves_old_live_and_cleans_candidate(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    final = tmp_path / "final"
    staging = tmp_path / "staging"
    backup = tmp_path / "backup"
    final.mkdir()
    staging.mkdir()
    for name in promotion.FINAL_OUTPUT_NAMES:
        (final / name).write_bytes(f"old:{name}".encode())
        (staging / name).write_bytes(f"new:{name}".encode())
    old_snapshot = snapshot(final)
    monkeypatch.setattr(promotion, "FINAL_DIR", final)
    monkeypatch.setattr(promotion, "STAGING_DIR", staging)
    monkeypatch.setattr(promotion, "BACKUP_DIR", backup)
    monkeypatch.setattr(
        promotion,
        "fsync_parent_directories",
        lambda *_paths: (_ for _ in ()).throw(OSError("injected prepublication sync failure")),
    )

    with pytest.raises(OSError, match="prepublication sync failure"):
        promotion.swap_final_with_rollback()

    assert snapshot(final) == old_snapshot
    assert not staging.exists()
    assert not backup.exists()


def test_postpublication_directory_sync_failure_is_committed_with_old_recoverable(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    final = tmp_path / "final"
    staging = tmp_path / "staging"
    backup = tmp_path / "backup"
    final.mkdir()
    staging.mkdir()
    for name in promotion.FINAL_OUTPUT_NAMES:
        (final / name).write_bytes(f"old:{name}".encode())
        (staging / name).write_bytes(f"new:{name}".encode())
    old_snapshot = snapshot(final)
    new_snapshot = snapshot(staging)
    monkeypatch.setattr(promotion, "FINAL_DIR", final)
    monkeypatch.setattr(promotion, "STAGING_DIR", staging)
    monkeypatch.setattr(promotion, "BACKUP_DIR", backup)
    monkeypatch.setattr(promotion, "run_validator", lambda *_args: None)
    real_fsync = promotion.fsync_parent_directories
    calls = 0

    def fail_second_sync(*paths: Path) -> None:
        nonlocal calls
        calls += 1
        if calls == 2:
            raise OSError("injected postpublication sync failure")
        real_fsync(*paths)

    monkeypatch.setattr(promotion, "fsync_parent_directories", fail_second_sync)
    result = promotion.swap_final_with_rollback()

    assert result == "COMMITTED_WITH_WARNING"
    assert snapshot(final) == new_snapshot
    assert snapshot(staging) == old_snapshot
    assert not backup.exists()
    assert "COMMITTED_WITH_WARNING" in capsys.readouterr().err


@pytest.mark.parametrize(
    ("failure_point", "recovery_name"),
    [("backup_rename", "staging"), ("backup_sync", "backup")],
)
def test_postpublication_backup_failures_are_committed_with_old_recoverable(
    failure_point: str,
    recovery_name: str,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    final = tmp_path / "final"
    staging = tmp_path / "staging"
    backup = tmp_path / "backup"
    final.mkdir()
    staging.mkdir()
    for name in promotion.FINAL_OUTPUT_NAMES:
        (final / name).write_bytes(f"old:{name}".encode())
        (staging / name).write_bytes(f"new:{name}".encode())
    old_snapshot = snapshot(final)
    new_snapshot = snapshot(staging)
    monkeypatch.setattr(promotion, "FINAL_DIR", final)
    monkeypatch.setattr(promotion, "STAGING_DIR", staging)
    monkeypatch.setattr(promotion, "BACKUP_DIR", backup)
    monkeypatch.setattr(promotion, "run_validator", lambda *_args: None)

    if failure_point == "backup_rename":
        real_replace = promotion.os.replace

        def fail_backup_rename(source: Path, target: Path) -> None:
            if Path(source) == staging and Path(target) == backup:
                raise OSError("injected backup rename failure")
            real_replace(source, target)

        monkeypatch.setattr(promotion.os, "replace", fail_backup_rename)
    else:
        real_fsync = promotion.fsync_parent_directories
        calls = 0

        def fail_backup_sync(*paths: Path) -> None:
            nonlocal calls
            calls += 1
            if calls == 3:
                raise OSError("injected backup sync failure")
            real_fsync(*paths)

        monkeypatch.setattr(promotion, "fsync_parent_directories", fail_backup_sync)

    result = promotion.swap_final_with_rollback()

    recovery = {"staging": staging, "backup": backup}[recovery_name]
    assert result == "COMMITTED_WITH_WARNING"
    assert snapshot(final) == new_snapshot
    assert snapshot(recovery) == old_snapshot
    assert "COMMITTED_WITH_WARNING" in capsys.readouterr().err


@pytest.mark.parametrize("mutation", ["bytes", "inode", "mode"])
def test_staging_mutation_after_validation_snapshot_restores_old_final(
    mutation: str,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    final = tmp_path / "final"
    staging = tmp_path / "staging"
    backup = tmp_path / "backup"
    final.mkdir()
    staging.mkdir()
    for name in promotion.FINAL_OUTPUT_NAMES:
        (final / name).write_bytes(f"old:{name}".encode())
        (staging / name).write_bytes(f"new:{name}".encode())
    old_snapshot = snapshot(final)
    target_name = sorted(promotion.FINAL_OUTPUT_NAMES)[0]
    target = staging / target_name
    candidate_bytes = target.read_bytes()
    candidate_inode = target.stat().st_ino
    candidate_mode = target.stat().st_mode & 0o777
    monkeypatch.setattr(promotion, "FINAL_DIR", final)
    monkeypatch.setattr(promotion, "STAGING_DIR", staging)
    monkeypatch.setattr(promotion, "BACKUP_DIR", backup)
    monkeypatch.setattr(promotion, "run_validator", lambda *_args: None)
    real_tree_snapshot = promotion.final_tree_snapshot
    staging_snapshots = 0

    def mutate_after_validated_snapshot(directory: Path) -> dict[str, dict[str, object]]:
        nonlocal staging_snapshots
        result = real_tree_snapshot(directory)
        if directory == staging:
            staging_snapshots += 1
            if staging_snapshots == 2:
                if mutation == "bytes":
                    target.write_bytes(b"tampered-after-validation")
                elif mutation == "inode":
                    replacement = tmp_path / "same-bytes-replacement"
                    replacement.write_bytes(candidate_bytes)
                    replacement.chmod(candidate_mode)
                    replacement.replace(target)
                else:
                    target.chmod(candidate_mode ^ 0o100)
        return result

    monkeypatch.setattr(promotion, "final_tree_snapshot", mutate_after_validated_snapshot)
    with pytest.raises(ValueError, match="candidate quarantined"):
        promotion.swap_final_with_rollback()

    assert snapshot(final) == old_snapshot
    if mutation == "bytes":
        assert target.read_bytes() == b"tampered-after-validation"
    elif mutation == "inode":
        assert target.read_bytes() == candidate_bytes
        assert target.stat().st_ino != candidate_inode
    else:
        assert target.stat().st_mode & 0o777 != candidate_mode
    assert not backup.exists()


def test_staging_mutation_without_prior_final_is_quarantined(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    final = tmp_path / "final"
    staging = tmp_path / "staging"
    backup = tmp_path / "backup"
    staging.mkdir()
    for name in promotion.FINAL_OUTPUT_NAMES:
        (staging / name).write_bytes(f"new:{name}".encode())
    target_name = sorted(promotion.FINAL_OUTPUT_NAMES)[0]
    monkeypatch.setattr(promotion, "FINAL_DIR", final)
    monkeypatch.setattr(promotion, "STAGING_DIR", staging)
    monkeypatch.setattr(promotion, "BACKUP_DIR", backup)
    monkeypatch.setattr(promotion, "run_validator", lambda *_args: None)
    real_tree_snapshot = promotion.final_tree_snapshot
    staging_snapshots = 0

    def mutate_after_validated_snapshot(directory: Path) -> dict[str, dict[str, object]]:
        nonlocal staging_snapshots
        result = real_tree_snapshot(directory)
        if directory == staging:
            staging_snapshots += 1
            if staging_snapshots == 2:
                (staging / target_name).write_bytes(b"tampered-after-validation")
        return result

    monkeypatch.setattr(promotion, "final_tree_snapshot", mutate_after_validated_snapshot)
    with pytest.raises(ValueError, match="candidate quarantined"):
        promotion.swap_final_with_rollback()

    assert not final.exists()
    assert (staging / target_name).read_bytes() == b"tampered-after-validation"
    assert not backup.exists()


def test_final_dir_mapping_redirects_only_logical_final_paths(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    repository = tmp_path / "repository"
    staging = repository / "docs/submission/.final-promotion-staging"
    staging.mkdir(parents=True)
    for attribute in ("FINAL_OUTPUT_DIR", "FINAL_MANIFEST_PATH", "FINAL_README_PATH", "OUTPUT_PAIRS"):
        monkeypatch.setattr(form_validator, attribute, getattr(form_validator, attribute))
    monkeypatch.setattr(form_validator, "REPO_ROOT", repository)

    form_validator.configure_final_output_dir(staging)

    assert form_validator.FINAL_OUTPUT_DIR == staging
    assert form_validator.FINAL_MANIFEST_PATH == staging / "BUILD_MANIFEST.json"
    assert form_validator.FINAL_README_PATH == staging / "README.md"
    assert form_validator.OUTPUT_PAIRS["docx"][1] == staging / "개발보고서 양식.docx"
    assert form_validator.OUTPUT_PAIRS["pptx"][1] == staging / "제작설계서_일반.pptx"
    for name in promotion.FINAL_OUTPUT_NAMES:
        assert form_validator.final_manifest_target(f"docs/submission/final/{name}") == staging / name
    source_path = "docs/submission/form_materials/09_제출_사실_기준.json"
    assert form_validator.final_manifest_target(source_path) == repository / source_path
