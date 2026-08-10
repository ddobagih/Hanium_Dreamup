#!/usr/bin/env python3
"""Atomically promote reviewed Office files and bind the final submission manifest."""

from __future__ import annotations

import sys

if __name__ == "__main__":
    _verified_submission_commit = getattr(
        sys.modules.get("__main__"), "_walksafe_submission_source_commit", None
    )
    _startup_flags = (
        sys.flags.isolated,
        sys.flags.no_site,
        sys.flags.ignore_environment,
        int(getattr(sys.flags, "safe_path", False)),
        sys.flags.dont_write_bytecode,
    )
    if (
        any(value != 1 for value in _startup_flags)
        or "site" in sys.modules
        or not isinstance(_verified_submission_commit, str)
        or len(_verified_submission_commit) != 40
        or any(character not in "0123456789abcdef" for character in _verified_submission_commit)
    ):
        raise SystemExit(
            "WalkSafe submission CLI requires Python -I -S -B through the submission runner"
        )

import argparse
import ctypes
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
from datetime import datetime
from pathlib import Path

from PIL import Image

from submission_build_io import (
    atomic_output_path,
    canonical_android_device_evidence,
    submission_build_lock,
)
from submission_manifest_policy import (
    ALL_SUBMISSION_GENERATED_PATHS,
    ASSET_DIRECTORY_FILES,
    DESIGN_DOCUMENT_NAMES,
    FINAL_OUTPUT_NAMES,
    FINAL_SECTION_PATHS,
    file_record,
    record_bundle_sha256,
    require_clean_source_revision,
    require_exact_file_set,
    require_review_identity,
    sha256,
    submission_toolchain_attestation,
)


REPO_ROOT = Path(__file__).resolve().parents[1]
FINAL_DIR = REPO_ROOT / "docs/submission/final"
README_PATH = FINAL_DIR / "README.md"
MANIFEST_PATH = FINAL_DIR / "BUILD_MANIFEST.json"
FACTS_PATH = REPO_ROOT / "docs/submission/form_materials/09_제출_사실_기준.json"
DESIGN_MANIFEST_PATH = REPO_ROOT / "docs/submission/design_documents/BUILD_MANIFEST.json"
FORM_MANIFEST_PATH = REPO_ROOT / "templates/SUBMISSION_BUILD_MANIFEST.json"
WORKING_REPORT = REPO_ROOT / "templates/개발보고서 양식.docx"
WORKING_SLIDES = REPO_ROOT / "templates/제작설계서_일반.pptx"
ASSET_DIR = REPO_ROOT / "docs/submission/form_materials/assets"
DESIGN_DIR = REPO_ROOT / "docs/submission/design_documents"
ASSET_PNG_NAMES = frozenset(name for name in ASSET_DIRECTORY_FILES if name.endswith(".png"))
STAGING_DIR = REPO_ROOT / "docs/submission/.final-promotion-staging"
BACKUP_DIR = REPO_ROOT / "docs/submission/.final-promotion-backup"
SUBMISSION_RUNNER = REPO_ROOT / "scripts/run_walksafe_submission_python_20260714.py"
APPROVED_SOURCE_FREEZE_POLICY = (
    "사용자가 승인한 source-freeze commit의 자동검증 snapshot이며 최종 source-freeze "
    "근거로 채택했다. Field 또는 Release 증거로 확대하지 않고 PASS와 FAIL을 함께 "
    "기록하며 서로 다른 계층 수를 합산하지 않는다. current source-freeze Android "
    "device run은 NOT_RUN_CURRENT_SOURCE_FREEZE로 기록하고, 2026-07-13 SM-G981N "
    "2/2는 별도 historical evidence로만 보존한다."
)


def android_device_evidence(facts: dict[str, object]) -> tuple[str, str]:
    _, _, current_label, historical_label = canonical_android_device_evidence(
        facts.get("verification_snapshot")
    )
    return current_label, historical_label


def stable_stat_identity(value: os.stat_result) -> tuple[int, int, int, int, int, int]:
    return (
        value.st_mode,
        value.st_dev,
        value.st_ino,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


def read_stable_regular_file(path: Path, label: str) -> tuple[bytes, os.stat_result]:
    nofollow = getattr(os, "O_NOFOLLOW", None)
    if nofollow is None:
        raise RuntimeError("O_NOFOLLOW is required to read promotion files safely")
    flags = os.O_RDONLY | nofollow | getattr(os, "O_CLOEXEC", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise ValueError(f"{label} must be a regular non-symlink file: {path}") from exc
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            raise ValueError(f"{label} must be a regular non-symlink file: {path}")
        chunks: list[bytes] = []
        while chunk := os.read(descriptor, 1024 * 1024):
            chunks.append(chunk)
        after = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    try:
        current = os.lstat(path)
    except OSError as exc:
        raise ValueError(f"{label} changed while it was read: {path}") from exc

    if (
        not stat.S_ISREG(current.st_mode)
        or stable_stat_identity(before) != stable_stat_identity(after)
        or stable_stat_identity(after) != stable_stat_identity(current)
    ):
        raise ValueError(f"{label} changed while it was read: {path}")
    return b"".join(chunks), after


def load_json_snapshot(path: Path) -> tuple[dict[str, object], bytes, str]:
    def reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON key in {path}: {key}")
            result[key] = value
        return result

    raw, _identity = read_stable_regular_file(path, "JSON input")
    payload = json.loads(
        raw.decode("utf-8"),
        object_pairs_hook=reject_duplicate_keys,
    )
    if not isinstance(payload, dict):
        raise ValueError(f"expected a JSON object: {path}")
    return payload, raw, hashlib.sha256(raw).hexdigest()


def load_json(path: Path) -> dict[str, object]:
    return load_json_snapshot(path)[0]


def validate_promotable_verification_snapshot(facts: object) -> None:
    """Reject non-final verification states before publishing submission files."""

    if not isinstance(facts, dict):
        raise ValueError("canonical facts must be a JSON object")
    snapshot = facts.get("verification_snapshot")
    if not isinstance(snapshot, dict):
        raise ValueError("canonical facts must contain a verification snapshot")
    status = snapshot.get("status")
    executed_at = snapshot.get("executed_at")
    policy = snapshot.get("policy")
    try:
        if (
            not isinstance(executed_at, str)
            or re.fullmatch(r"\d{4}-\d{2}-\d{2}", executed_at) is None
        ):
            raise ValueError
        datetime.strptime(executed_at, "%Y-%m-%d")
    except ValueError as exc:
        raise ValueError("final promotion requires an approved source-freeze snapshot") from exc
    if status != f"SOURCE_FREEZE_APPROVED_{executed_at}":
        raise ValueError("final promotion requires an approved source-freeze snapshot")
    if policy != APPROVED_SOURCE_FREEZE_POLICY:
        raise ValueError("approved source-freeze snapshot policy is not the exact approval contract")
    try:
        canonical_android_device_evidence(snapshot)
    except ValueError as exc:
        raise ValueError(
            "approved source-freeze snapshot device evidence is not canonical"
        ) from exc


def validate_clean_upstream_manifests() -> str:
    validate_promotable_verification_snapshot(load_json(FACTS_PATH))
    revision = require_clean_source_revision(REPO_ROOT, ALL_SUBMISSION_GENERATED_PATHS)
    manifests = {
        "asset": (
            REPO_ROOT / "docs/submission/form_materials/assets/BUILD_MANIFEST.json",
            "walksafe.submission-assets-build.v1",
        ),
        "design": (DESIGN_MANIFEST_PATH, "walksafe.design-documents.v2"),
        "form": (FORM_MANIFEST_PATH, "walksafe.submission-forms-build.v1"),
    }
    for label, (path, schema) in manifests.items():
        manifest = load_json(path)
        if manifest.get("schema_version") != schema:
            raise ValueError(f"unexpected {label} manifest schema")
        if manifest.get("source_dirty") is not False:
            raise ValueError(f"{label} manifest records dirty submission source")
        for key in (
            "source_commit",
            "source_dirty",
            "source_dirty_excluded_generated_paths",
        ):
            if manifest.get(key) != revision[key]:
                raise ValueError(f"{label} manifest source revision differs from current clean source")
    return str(revision["source_commit"])


def copy_atomic(source: Path, target: Path) -> None:
    with atomic_output_path(target) as temporary:
        temporary.write_bytes(source.read_bytes())


def write_json_atomic(path: Path, payload: dict[str, object]) -> None:
    with atomic_output_path(path) as temporary:
        temporary.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )


def exact_hash_records(
    records: object,
    expected_paths: dict[str, Path],
    label: str,
    expected_keys: set[str],
) -> None:
    if not isinstance(records, list) or any(not isinstance(item, dict) for item in records):
        raise ValueError(f"privacy receipt {label} hash records are invalid")
    if any(set(item) != expected_keys for item in records):
        raise ValueError(f"privacy receipt {label} hash record shape is invalid")
    names = [str(item.get("name")) for item in records]
    if len(names) != len(set(names)) or set(names) != set(expected_paths):
        raise ValueError(f"privacy receipt {label} file set does not match current artifacts")
    for path in expected_paths.values():
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"privacy review target is missing or unsafe: {path}")
    recorded = {str(item.get("name")): str(item.get("sha256")) for item in records}
    actual = {name: sha256(path) for name, path in expected_paths.items()}
    if recorded != actual:
        raise ValueError(f"privacy receipt {label} hashes do not match current artifacts")


def validate_current_asset_metadata(paths: dict[str, Path]) -> None:
    violations: list[str] = []
    for name, path in paths.items():
        try:
            with Image.open(path) as image:
                image.verify()
            with Image.open(path) as image:
                if image.getexif().get(34853):
                    violations.append(f"{name}:gps_exif")
                suspicious = sorted(
                    key
                    for key in image.info
                    if key.casefold() in {"author", "comment", "description", "location"}
                )
                if suspicious:
                    violations.append(f"{name}:metadata:{','.join(suspicious)}")
        except OSError as exc:
            raise ValueError(f"privacy review asset is invalid: {name}: {exc}") from exc
    if violations:
        raise ValueError(f"current submission assets contain metadata violations: {violations}")


def validate_privacy_receipt(receipt: dict[str, object], *, source_commit: str) -> tuple[int, int]:
    if set(receipt) != {
        "schema_version",
        "source_commit",
        "reviewed_at",
        "reviewer",
        "reviewer_kind",
        "automatic",
        "manual_assertions",
        "release_ready",
    }:
        raise ValueError("privacy receipt fields differ from the exact contract")
    if receipt.get("schema_version") != "walksafe.submission-visual-privacy.v2":
        raise ValueError("unexpected privacy receipt schema")
    if receipt.get("source_commit") != source_commit:
        raise ValueError("privacy receipt source commit differs from current clean source")
    require_review_identity(receipt.get("reviewer"), receipt.get("reviewed_at"))
    if receipt.get("reviewer_kind") != "human":
        raise ValueError("privacy receipt requires a named human reviewer")
    if receipt.get("release_ready") is not True:
        raise ValueError("privacy receipt requires release_ready=true")
    automatic = receipt.get("automatic")
    assertions = receipt.get("manual_assertions")
    if not isinstance(automatic, dict) or not isinstance(assertions, dict):
        raise ValueError("privacy receipt is missing audit sections")
    if set(automatic) != {
        "assets",
        "metadata_violations",
        "face_candidates",
        "automatic_face_detection_limit",
        "documents",
        "final_documents",
        "office_render",
    }:
        raise ValueError("privacy receipt automatic evidence fields differ from the exact contract")
    if (
        not isinstance(automatic.get("face_candidates"), list)
        or automatic.get("automatic_face_detection_limit")
        != "Haar 후보 검사이며 얼굴·차량번호·OCR 부재를 단독 보장하지 않음"
    ):
        raise ValueError("privacy receipt automatic detector disclosure is invalid")
    if automatic.get("metadata_violations") != []:
        raise ValueError("privacy receipt contains metadata violations")
    required_assertions = (
        "no_visible_faces",
        "no_visible_license_plates",
        "no_personal_addresses_or_accounts",
        "office_pdf_render_reviewed",
    )
    if set(assertions) != set(required_assertions) or not all(
        assertions.get(name) is True for name in required_assertions
    ):
        raise ValueError("privacy and Office review assertions must all be true")
    asset_paths = {name: ASSET_DIR / name for name in ASSET_PNG_NAMES}
    exact_hash_records(
        automatic.get("assets"),
        asset_paths,
        "asset",
        {"name", "sha256", "width", "height"},
    )
    asset_dimensions = {
        str(item["name"]): (item["width"], item["height"])
        for item in automatic["assets"]
    }
    for name, path in asset_paths.items():
        with Image.open(path) as image:
            if asset_dimensions.get(name) != image.size:
                raise ValueError(f"privacy receipt asset dimensions differ: {name}")
    validate_current_asset_metadata(asset_paths)
    exact_hash_records(
        automatic.get("documents"),
        {name: DESIGN_DIR / name for name in DESIGN_DOCUMENT_NAMES},
        "design document",
        {"name", "sha256"},
    )
    exact_hash_records(
        automatic.get("final_documents"),
        {WORKING_REPORT.name: WORKING_REPORT, WORKING_SLIDES.name: WORKING_SLIDES},
        "review Office",
        {"name", "sha256"},
    )
    office_render = automatic.get("office_render")
    if not isinstance(office_render, dict) or set(office_render) != {"report", "slides"}:
        raise ValueError("privacy receipt requires exact Office render evidence")
    report_render = office_render.get("report")
    slides_render = office_render.get("slides")
    if not isinstance(report_render, dict) or set(report_render) != {
        "name",
        "sha256",
        "pdf_pages",
    }:
        raise ValueError("privacy receipt report render evidence is invalid")
    if not isinstance(slides_render, dict) or set(slides_render) != {
        "name",
        "sha256",
        "pdf_pages",
        "slides",
        "aspect_ratio",
    }:
        raise ValueError("privacy receipt slide render evidence is invalid")
    report_pages = report_render.get("pdf_pages")
    slides = slides_render.get("slides")
    if (
        report_render.get("name") != WORKING_REPORT.name
        or report_render.get("sha256") != sha256(WORKING_REPORT)
        or isinstance(report_pages, bool)
        or not isinstance(report_pages, int)
        or report_pages < 1
        or slides_render.get("name") != WORKING_SLIDES.name
        or slides_render.get("sha256") != sha256(WORKING_SLIDES)
        or isinstance(slides, bool)
        or not isinstance(slides, int)
        or slides != 34
        or slides_render.get("pdf_pages") != slides
        or slides_render.get("aspect_ratio") != "4:3"
    ):
        raise ValueError("privacy receipt Office render evidence differs from current artifacts")
    return report_pages, slides


def update_readme(
    report_hash: str,
    slides_hash: str,
    report_pages: int,
    slides: int,
    facts: dict[str, object],
    output_path: Path = README_PATH,
) -> None:
    project = facts["project"]
    counts = facts["verification_snapshot"]["counts"]
    current_device_label, historical_device_label = android_device_evidence(facts)
    navigation = facts["runtime"]["navigation"]
    release_gate_lines = "\n".join(
        f"- `{gate['id']}`: {gate['evidence_required']}"
        for gate in facts["release_gates"]
    )
    text = f"""# WalkSafe Assist 최종 제출 후보

이 폴더의 Office 파일은 {facts['as_of_date']} canonical facts와 사람이 검토·승인한 exact visual/privacy receipt를 기준으로 승격했습니다. receipt의 human identity는 암호학적 서명이 아니라 외부 신뢰 경계입니다. 제품 상태는 `{project['overall_status']}`이며 Field·Release 미검증 범위를 완료로 표현하지 않습니다.

| 파일 | 페이지/슬라이드 | 상태 | SHA-256 |
|---|---:|---|---|
| `개발보고서 양식.docx` | {report_pages}쪽 | 자동검사·사람 렌더/개인정보 검수 통과 | `{report_hash}` |
| `제작설계서_일반.pptx` | {slides}장, 4:3 | 자동검사·사람 렌더/개인정보 검수 통과 | `{slides_hash}` |

## Canonical 범위

- 주 사용자 앱은 `{project['primary_app']}`이고 Android는 `{project['android_role']}`입니다.
- 전역 보행 provider는 TMAP-only입니다. Web은 IMU 선택 `CAMERA_NON_METRIC_ADVISORY`, ARCore 미지원 Android는 fresh IMU 필수 `CAMERA_IMU_NON_METRIC`·reports=false이며 실패하면 `TMAP_ONLY`입니다. 두 tier는 low 좌/중앙/우·3 distinct frame+700ms만 허용하고 거리·N보·STOP/high·local steering·안전 경로·경로 변경·자동 신고를 금지합니다. 기존 Web alertable risk와 명시 동의 damaged-report는 유지하고, 동일 심각도 3A bounded sequential handoff는 전달 직전 recheck합니다. Web 실폰·ARCore 미지원 Android Field는 `UNVERIFIED_OPEN`입니다. Android ARCore metric supplier는 `{navigation['android_tactile_local_steering']['status']}`와 strict TMAP fallback을 유지합니다.
- WebXR depth는 중앙 거리 표시 전용이며 객체 위험 판단에 연결되지 않았습니다.
- 손상 점자블록 신고는 named admin 검수·agency export 후 사람이 외부 채널로 제출하며 기관 자동 API 접수를 주장하지 않습니다.

## Canonical 검증 snapshot

- Unit Python {counts['unit_python']}, Functional Python/PostGIS {counts['functional_python']}, Integration Python {counts['integration_python']}, Backend full {counts['backend_full']}
- Voice {counts['voice']}, Android JVM {counts['android_jvm']}/{counts['android_jvm']}
- {current_device_label}
- {historical_device_label}
- Web NFT {counts['web_trace_count']} traces/{counts['web_trace_files']} unique files
- 계층 수치는 합산하지 않고 Unit·격리 DB·build 근거를 Field 또는 Release로 확대하지 않습니다.

## 남은 Release gate

{release_gate_lines}

재현 절차는 `docs/submission/CLEAN_ROOM_REPRODUCTION_20260713.md`, 상세 hash·source/toolchain 결합은 `BUILD_MANIFEST.json`에 기록합니다.
"""
    with atomic_output_path(output_path) as temporary:
        temporary.write_text(text, encoding="utf-8")


def logical_file_record(path: Path, logical_path: str) -> dict[str, object]:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"final staging input is missing or unsafe: {path}")
    return {"path": logical_path, "sha256": sha256(path), "bytes": path.stat().st_size}


def build_manifest(
    report_pages: int,
    slides: int,
    final_dir: Path = FINAL_DIR,
) -> dict[str, object]:
    facts = load_json(FACTS_PATH)
    design_manifest = load_json(DESIGN_MANIFEST_PATH)
    form_manifest = load_json(FORM_MANIFEST_PATH)
    if design_manifest.get("schema_version") != "walksafe.design-documents.v2":
        raise ValueError("unexpected design manifest schema")
    if form_manifest.get("schema_version") != "walksafe.submission-forms-build.v1":
        raise ValueError("unexpected form manifest schema")

    canonical = file_record(REPO_ROOT, FACTS_PATH)
    canonical["schema_version"] = facts.get("schema_version")
    sections = {
        name: [file_record(REPO_ROOT, REPO_ROOT / relative) for relative in sorted(paths)]
        for name, paths in FINAL_SECTION_PATHS.items()
        if name not in {"artifacts", "supporting_files"}
    }
    report_record = logical_file_record(
        final_dir / WORKING_REPORT.name,
        f"docs/submission/final/{WORKING_REPORT.name}",
    )
    report_record["pages"] = report_pages
    slides_record = logical_file_record(
        final_dir / WORKING_SLIDES.name,
        f"docs/submission/final/{WORKING_SLIDES.name}",
    )
    slides_record.update({"slides": slides, "aspect_ratio": "4:3"})
    supporting_files = [
        logical_file_record(final_dir / Path(relative).name, relative)
        for relative in sorted(FINAL_SECTION_PATHS["supporting_files"])
    ]
    source_records = [canonical, *sections["critical_sources"], *sections["build_sources"]]
    source_count = len(source_records)
    algorithm = (
        "SHA-256 of sorted sha256sum lines for canonical facts, "
        f"{len(FINAL_SECTION_PATHS['critical_sources'])} critical sources, and "
        f"{len(FINAL_SECTION_PATHS['build_sources'])} build/validation sources"
    )
    counts = facts["verification_snapshot"]["counts"]
    current_device_label, historical_device_label = android_device_evidence(facts)
    return {
        "schema_version": "walksafe.submission-final.v1",
        "as_of_date": facts["as_of_date"],
        "product_status": facts["project"]["overall_status"],
        "candidate_status": "AUTOMATED_AND_HUMAN_VISUAL_QA_PASS",
        "product_scope": {
            "primary_app": facts["project"]["primary_app"],
            "android_role": facts["project"]["android_role"],
            "institution_submission": "named admin review, agency file preparation, manual external-channel submission",
        },
        "repository": {
            "commit": form_manifest.get("source_commit"),
            "dirty": form_manifest.get("source_dirty"),
            "source_bundle_sha256": record_bundle_sha256(source_records),
            "source_bundle_algorithm": algorithm,
            "source_bundle_entry_count": source_count,
        },
        "canonical_facts": canonical,
        "official_templates": sections["official_templates"],
        "critical_sources": sections["critical_sources"],
        "build_sources": sections["build_sources"],
        "design_pack": {
            "document_count": len(DESIGN_DOCUMENT_NAMES),
            "manifest_path": DESIGN_MANIFEST_PATH.relative_to(REPO_ROOT).as_posix(),
            "manifest_sha256": sha256(DESIGN_MANIFEST_PATH),
        },
        "form_build": {
            "schema_version": form_manifest.get("schema_version"),
            "manifest_path": FORM_MANIFEST_PATH.relative_to(REPO_ROOT).as_posix(),
            "manifest_sha256": sha256(FORM_MANIFEST_PATH),
        },
        "artifacts": [report_record, slides_record],
        "supporting_files": supporting_files,
        "validation": {
            "test_layers": (
                f"PASS: Unit Python {counts['unit_python']}; "
                f"Functional Python/PostGIS {counts['functional_python']}; "
                f"Integration Python {counts['integration_python']}"
            ),
            "backend_voice_android": (
                f"PASS: Backend {counts['backend_full']}; Voice {counts['voice']}; "
                f"Android JVM {counts['android_jvm']}/{counts['android_jvm']}"
            ),
            "android_device": (
                f"{current_device_label}; {historical_device_label}"
            ),
            "web": (
                "PASS: test/typecheck/lint/build, PWA lifecycle, "
                f"NFT {counts['web_trace_count']} traces/{counts['web_trace_files']} unique files"
            ),
            "submission_materials": (
                f"PASS: canonical facts and generated design/forms, {len(ASSET_PNG_NAMES)} assets, "
                f"{slides} slides, REQ-001..{len(facts['requirements']):03d}, "
                f"UC-01..{len(facts['use_cases']):02d}"
            ),
            "office_render": (
                f"PASS_HUMAN: report {report_pages} pages, design {slides} slides; "
                "exact visual/privacy receipt bound"
            ),
        },
        "known_release_blockers": facts["release_gates"],
    }


def submission_python_argv(script: str, *args: str) -> list[str]:
    expected_commit = os.environ.get("WALKSAFE_SUBMISSION_SOURCE_COMMIT", "").lower()
    if len(expected_commit) != 40 or any(character not in "0123456789abcdef" for character in expected_commit):
        raise ValueError("WALKSAFE_SUBMISSION_SOURCE_COMMIT must be the runner-verified full commit")
    return [
        sys.executable,
        "-I",
        "-S",
        "-B",
        str(SUBMISSION_RUNNER),
        "--repo-root",
        str(REPO_ROOT),
        "--expected-commit",
        expected_commit,
        "--",
        f"scripts/{script}",
        *args,
    ]


def run_validator(*args: str) -> None:
    subprocess.run(
        submission_python_argv("validate_submission_forms_20260710.py", *args),
        cwd=REPO_ROOT,
        check=True,
    )


def run_upstream_validators() -> None:
    for script, arguments in (
        ("validate_submission_materials_20260710.py", ()),
        ("build_design_documents_20260710.py", ("--validate-only",)),
        ("validate_submission_forms_20260710.py", ("--working-only",)),
    ):
        subprocess.run(
            submission_python_argv(script, *arguments),
            cwd=REPO_ROOT,
            check=True,
        )


def atomic_exchange_directories(left: Path, right: Path) -> None:
    """Atomically exchange two existing directories; never use a non-atomic fallback."""
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    if renameat2 is None:
        raise RuntimeError("renameat2(RENAME_EXCHANGE) is required for final publication")
    renameat2.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    ]
    renameat2.restype = ctypes.c_int
    at_fdcwd = -100
    rename_exchange = 2
    result = renameat2(
        at_fdcwd,
        os.fsencode(left),
        at_fdcwd,
        os.fsencode(right),
        rename_exchange,
    )
    if result != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error), f"{left} <-> {right}")


def fsync_parent_directories(*paths: Path) -> None:
    """Persist directory-entry changes for every distinct parent directory."""
    directory_flag = getattr(os, "O_DIRECTORY", None)
    if directory_flag is None:
        raise RuntimeError("O_DIRECTORY is required for durable final publication")
    for parent in sorted({path.parent.resolve() for path in paths}, key=os.fspath):
        descriptor = os.open(
            parent,
            os.O_RDONLY | directory_flag | getattr(os, "O_CLOEXEC", 0),
        )
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def final_tree_snapshot(directory: Path) -> dict[str, dict[str, object]]:
    """Bind every required final path to its stable type, mode, bytes, hash, and inode."""
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError(f"final snapshot directory is missing or unsafe: {directory}")
    require_exact_file_set(directory, FINAL_OUTPUT_NAMES)
    snapshot: dict[str, dict[str, object]] = {}
    for name in sorted(FINAL_OUTPUT_NAMES):
        raw, identity = read_stable_regular_file(directory / name, "final snapshot input")
        snapshot[name] = {
            "path": name,
            "type": "regular",
            "mode": stat.S_IMODE(identity.st_mode),
            "size": identity.st_size,
            "sha256": hashlib.sha256(raw).hexdigest(),
            "device": identity.st_dev,
            "inode": identity.st_ino,
        }
    return snapshot


def require_published_snapshot(
    expected: dict[str, dict[str, object]],
    had_final: bool,
) -> None:
    try:
        actual = final_tree_snapshot(FINAL_DIR)
    except BaseException as exc:
        mismatch: BaseException = exc
    else:
        if actual == expected:
            return
        mismatch = ValueError("published final identity/hash snapshot differs from validated staging")
    try:
        if had_final:
            atomic_exchange_directories(FINAL_DIR, STAGING_DIR)
        else:
            os.replace(FINAL_DIR, STAGING_DIR)
        fsync_parent_directories(FINAL_DIR, STAGING_DIR)
    except BaseException as rollback_error:
        raise RuntimeError(
            "published final snapshot mismatch and atomic restore/quarantine failed"
        ) from rollback_error
    raise ValueError(
        f"published final snapshot mismatch; candidate quarantined at {STAGING_DIR}"
    ) from mismatch


def committed_with_warning(message: str) -> str:
    print(f"COMMITTED_WITH_WARNING: {message}", file=sys.stderr)
    return "COMMITTED_WITH_WARNING"


def cleanup_unpublished_staging() -> None:
    if not STAGING_DIR.is_dir():
        return
    try:
        shutil.rmtree(STAGING_DIR)
    except OSError as exc:
        print(
            f"warning: unpublished candidate retained for manual cleanup at {STAGING_DIR}: {exc}",
            file=sys.stderr,
        )


def prepare_staging_final(
    receipt_bytes: bytes,
    receipt_sha256: str,
    report_pages: int,
    slides: int,
) -> None:
    if STAGING_DIR.exists() or BACKUP_DIR.exists():
        raise ValueError("stale final promotion staging/backup directory requires manual recovery")
    STAGING_DIR.mkdir()
    copy_atomic(WORKING_REPORT, STAGING_DIR / WORKING_REPORT.name)
    copy_atomic(WORKING_SLIDES, STAGING_DIR / WORKING_SLIDES.name)
    receipt_target = STAGING_DIR / "ASSISTANT_VISUAL_PRIVACY_REVIEW.json"
    with atomic_output_path(receipt_target) as temporary:
        temporary.write_bytes(receipt_bytes)
    if sha256(receipt_target) != receipt_sha256:
        raise ValueError("staged privacy receipt bytes differ from locked receipt")
    update_readme(
        sha256(STAGING_DIR / WORKING_REPORT.name),
        sha256(STAGING_DIR / WORKING_SLIDES.name),
        report_pages,
        slides,
        load_json(FACTS_PATH),
        STAGING_DIR / "README.md",
    )
    write_json_atomic(
        STAGING_DIR / "BUILD_MANIFEST.json",
        build_manifest(report_pages, slides, STAGING_DIR),
    )
    require_exact_file_set(STAGING_DIR, FINAL_OUTPUT_NAMES)


def swap_final_with_rollback() -> str:
    if STAGING_DIR.is_symlink() or not STAGING_DIR.is_dir():
        raise ValueError("final promotion staging path is missing or unsafe")
    if FINAL_DIR.is_symlink() or BACKUP_DIR.is_symlink():
        raise ValueError("final promotion path cannot be a symlink")
    if BACKUP_DIR.exists():
        raise ValueError("final promotion backup path already exists")
    had_final = FINAL_DIR.is_dir()
    if FINAL_DIR.exists() and not had_final:
        raise ValueError("final submission path is not a directory")
    try:
        fsync_parent_directories(FINAL_DIR, STAGING_DIR, BACKUP_DIR)
        before_validation = final_tree_snapshot(STAGING_DIR)
        run_validator("--final-dir", str(STAGING_DIR))
        validated_snapshot = final_tree_snapshot(STAGING_DIR)
        if validated_snapshot != before_validation:
            raise ValueError("final staging tree changed during full validation")
    except BaseException:
        cleanup_unpublished_staging()
        raise

    if not had_final:
        try:
            os.replace(STAGING_DIR, FINAL_DIR)
        except BaseException:
            cleanup_unpublished_staging()
            raise
        try:
            fsync_parent_directories(FINAL_DIR)
        except Exception as exc:
            require_published_snapshot(validated_snapshot, had_final=False)
            return committed_with_warning(
                f"validated final is live but parent directory sync failed: {exc}"
            )
        require_published_snapshot(validated_snapshot, had_final=False)
        return "COMMITTED"

    try:
        atomic_exchange_directories(FINAL_DIR, STAGING_DIR)
    except BaseException:
        cleanup_unpublished_staging()
        raise
    try:
        fsync_parent_directories(FINAL_DIR, STAGING_DIR)
    except Exception as exc:
        require_published_snapshot(validated_snapshot, had_final=True)
        return committed_with_warning(
            f"validated final is live but parent directory sync failed; "
            f"previous final retained at {STAGING_DIR}: {exc}"
        )
    require_published_snapshot(validated_snapshot, had_final=True)

    try:
        os.replace(STAGING_DIR, BACKUP_DIR)
    except OSError as exc:
        return committed_with_warning(
            f"validated final is live; previous final retained at {STAGING_DIR}: {exc}"
        )
    try:
        fsync_parent_directories(BACKUP_DIR)
    except Exception as exc:
        return committed_with_warning(
            f"validated final is live but backup directory sync failed; "
            f"previous final retained at {BACKUP_DIR}: {exc}"
        )
    return committed_with_warning(
        f"validated final is live; previous final retained at {BACKUP_DIR} "
        "for explicit manual cleanup"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--privacy-receipt", type=Path, required=True)
    args = parser.parse_args()
    submission_toolchain_attestation(REPO_ROOT)
    receipt_path = Path(os.path.abspath(args.privacy_receipt))
    receipt, receipt_bytes, receipt_hash = load_json_snapshot(receipt_path)
    source_commit = validate_clean_upstream_manifests()
    report_pages, slides = validate_privacy_receipt(receipt, source_commit=source_commit)
    run_upstream_validators()

    with submission_build_lock(REPO_ROOT):
        locked_source_commit = validate_clean_upstream_manifests()
        locked_receipt, locked_receipt_bytes, locked_receipt_hash = load_json_snapshot(receipt_path)
        locked_pages, locked_slides = validate_privacy_receipt(
            locked_receipt,
            source_commit=locked_source_commit,
        )
        if (locked_pages, locked_slides) != (report_pages, slides):
            raise ValueError("privacy receipt Office evidence changed during promotion")
        if (
            locked_receipt != receipt
            or locked_receipt_hash != receipt_hash
            or locked_receipt_bytes != receipt_bytes
        ):
            raise ValueError("privacy receipt exact bytes changed before promotion lock")
        try:
            prepare_staging_final(
                locked_receipt_bytes,
                locked_receipt_hash,
                report_pages,
                slides,
            )
        except BaseException:
            cleanup_unpublished_staging()
            raise
        publication_status = swap_final_with_rollback()
    print(
        f"promoted {WORKING_REPORT.name}, {WORKING_SLIDES.name}, "
        f"and {MANIFEST_PATH.relative_to(REPO_ROOT)}; status={publication_status}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
