#!/usr/bin/env python3
"""Audit fixed submission images and record explicit visual/Office review assertions."""

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
from datetime import UTC, datetime
import hashlib
import json
from pathlib import Path
import re
import tempfile
import uuid

import cv2
from PIL import Image
from pptx import Presentation

if __package__:
    from scripts.submission_manifest_policy import (
        ALL_SUBMISSION_GENERATED_PATHS,
        require_clean_source_revision,
        require_review_identity,
        run_attested_submission_tool,
        submission_toolchain_attestation,
    )
else:
    from submission_manifest_policy import (
        ALL_SUBMISSION_GENERATED_PATHS,
        require_clean_source_revision,
        require_review_identity,
        run_attested_submission_tool,
        submission_toolchain_attestation,
    )


EXPECTED_ASSETS = {
    "admin_desktop.png",
    "report_csv_flow.png",
    "reports_erd.png",
    "risk_processing_flow.png",
    "system_architecture.png",
    "model_quality_gate.png",
    "value_flow.png",
    "web_main_desktop.png",
    "web_main_mobile.png",
    "problem_solution_map.png",
    "use_case_swimlane.png",
    "ui_state_map.png",
    "ui_screen_storyboard.png",
    "navigation_state_flow.png",
    "risk_timeline.png",
    "evidence_results.png",
    "scope_change_map.png",
    "adoption_roadmap.png",
}
EXPECTED_DOCUMENTS = {
    "01_요구사항_정의서.docx",
    "02_유스케이스_정의서.docx",
    "03_요구사항_기능_추적표.docx",
    "04_서비스_구성도_및_흐름도.docx",
    "05_화면설계서_UIUX_정의서.docx",
    "06_엔티티관계도_테이블정의서.docx",
    "07_기능처리도_알고리즘명세서.docx",
    "08_프로그램목록_핵심소스코드_개발환경.docx",
}
EXPECTED_FINAL_DOCUMENTS = {"개발보고서 양식.docx", "제작설계서_일반.pptx"}
REPO_ROOT = Path(__file__).resolve().parents[1]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def audit_assets(asset_dir: Path) -> dict[str, object]:
    candidates = sorted(asset_dir.glob("*.png"))
    symlinks = [path.name for path in candidates if path.is_symlink()]
    if symlinks:
        raise ValueError(f"submission asset set contains symlinks: {symlinks}")
    paths = [path for path in candidates if path.is_file()]
    names = {path.name for path in paths}
    if names != EXPECTED_ASSETS:
        raise ValueError(f"submission asset set differs: missing={sorted(EXPECTED_ASSETS - names)}, extra={sorted(names - EXPECTED_ASSETS)}")
    cascade_path = Path(cv2.data.haarcascades) / "haarcascade_frontalface_default.xml"
    classifier = cv2.CascadeClassifier(str(cascade_path))
    if classifier.empty():
        raise RuntimeError("OpenCV frontal-face classifier is unavailable")
    face_candidates: list[dict[str, object]] = []
    metadata_violations: list[str] = []
    assets: list[dict[str, object]] = []
    for path in paths:
        with Image.open(path) as image:
            image.verify()
        with Image.open(path) as image:
            exif = image.getexif()
            if exif.get(34853):
                metadata_violations.append(f"{path.name}:gps_exif")
            suspicious_info = sorted(
                key for key in image.info if key.casefold() in {"author", "comment", "description", "location"}
            )
            if suspicious_info:
                metadata_violations.append(f"{path.name}:metadata:{','.join(suspicious_info)}")
            width, height = image.size
        pixels = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
        if pixels is None:
            raise ValueError(f"cannot decode {path}")
        detections = classifier.detectMultiScale(pixels, scaleFactor=1.1, minNeighbors=6, minSize=(40, 40))
        for x, y, width_px, height_px in detections:
            face_candidates.append(
                {"asset": path.name, "x": int(x), "y": int(y), "width": int(width_px), "height": int(height_px)}
            )
        assets.append({"name": path.name, "sha256": sha256(path), "width": width, "height": height})
    return {
        "assets": assets,
        "metadata_violations": metadata_violations,
        "face_candidates": face_candidates,
        "automatic_face_detection_limit": "Haar 후보 검사이며 얼굴·차량번호·OCR 부재를 단독 보장하지 않음",
    }


def audit_documents(document_dir: Path) -> list[dict[str, str]]:
    candidates = sorted(document_dir.glob("*.docx"))
    symlinks = [path.name for path in candidates if path.is_symlink()]
    if symlinks:
        raise ValueError(f"design document set contains symlinks: {symlinks}")
    paths = [path for path in candidates if path.is_file()]
    names = {path.name for path in paths}
    if names != EXPECTED_DOCUMENTS:
        raise ValueError(f"design document set differs: missing={sorted(EXPECTED_DOCUMENTS - names)}, extra={sorted(names - EXPECTED_DOCUMENTS)}")
    return [{"name": path.name, "sha256": sha256(path)} for path in paths]


def audit_final_documents(final_document_dir: Path) -> list[dict[str, str]]:
    paths = [final_document_dir / name for name in sorted(EXPECTED_FINAL_DOCUMENTS)]
    missing = [path.name for path in paths if path.is_symlink() or not path.is_file()]
    if missing:
        raise ValueError(
            f"review Office document set differs: missing={missing}"
        )
    return [{"name": path.name, "sha256": sha256(path)} for path in paths]


def audit_office_render(
    final_document_dir: Path,
    toolchain: dict[str, object],
) -> dict[str, object]:
    report = final_document_dir / "개발보고서 양식.docx"
    slides_path = final_document_dir / "제작설계서_일반.pptx"
    for path in (report, slides_path):
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"review Office file is missing or unsafe: {path}")
    presentation = Presentation(slides_path)
    slides = len(presentation.slides)
    if slides != 34 or presentation.slide_width * 3 != presentation.slide_height * 4:
        raise ValueError("review PPTX must contain exactly 34 slides in 4:3")

    with tempfile.TemporaryDirectory(prefix="walksafe-submission-render-") as temp:
        root = Path(temp)
        output_dir = root / "pdf"
        profile_dir = root / "lo-profile"
        output_dir.mkdir()
        profile_dir.mkdir()
        run_attested_submission_tool(
            toolchain,
            "libreoffice",
            [
                "--headless",
                f"-env:UserInstallation={profile_dir.resolve().as_uri()}",
                "--convert-to",
                "pdf",
                "--outdir",
                str(output_dir),
                str(report),
                str(slides_path),
            ],
            check=True,
            capture_output=True,
            text=True,
        )

        def pdf_pages(source: Path) -> int:
            pdf = output_dir / f"{source.stem}.pdf"
            if pdf.is_symlink() or not pdf.is_file():
                raise ValueError(f"LibreOffice did not render review file: {source.name}")
            completed = run_attested_submission_tool(
                toolchain,
                "pdfinfo",
                [str(pdf)],
                check=True,
                capture_output=True,
                text=True,
            )
            match = re.search(r"^Pages:\s*(\d+)\s*$", completed.stdout, re.MULTILINE)
            if match is None or int(match.group(1)) < 1:
                raise ValueError(f"cannot determine rendered page count: {source.name}")
            return int(match.group(1))

        report_pages = pdf_pages(report)
        slide_pages = pdf_pages(slides_path)
    if slide_pages != slides:
        raise ValueError("rendered PPTX page count differs from actual slide count")
    return {
        "report": {"name": report.name, "sha256": sha256(report), "pdf_pages": report_pages},
        "slides": {
            "name": slides_path.name,
            "sha256": sha256(slides_path),
            "pdf_pages": slide_pages,
            "slides": slides,
            "aspect_ratio": "4:3",
        },
    }


def build_receipt(
    asset_dir: Path,
    document_dir: Path,
    final_document_dir: Path,
    *,
    reviewer: str,
    reviewer_kind: str,
    no_faces: bool,
    no_license_plates: bool,
    no_personal_addresses: bool,
    office_render_reviewed: bool,
) -> dict[str, object]:
    reviewed_at = datetime.now(UTC).isoformat()
    require_review_identity(reviewer, reviewed_at)
    if reviewer_kind not in {"human", "assistant"}:
        raise ValueError("reviewer_kind must be human or assistant")
    revision = require_clean_source_revision(REPO_ROOT, ALL_SUBMISSION_GENERATED_PATHS)
    toolchain = submission_toolchain_attestation(REPO_ROOT)
    automated = audit_assets(asset_dir)
    automated["documents"] = audit_documents(document_dir)
    automated["final_documents"] = audit_final_documents(final_document_dir)
    automated["office_render"] = audit_office_render(final_document_dir, toolchain)
    return {
        "schema_version": "walksafe.submission-visual-privacy.v2",
        "source_commit": revision["source_commit"],
        "reviewed_at": reviewed_at,
        "reviewer": reviewer,
        "reviewer_kind": reviewer_kind,
        "automatic": automated,
        "manual_assertions": {
            "no_visible_faces": no_faces,
            "no_visible_license_plates": no_license_plates,
            "no_personal_addresses_or_accounts": no_personal_addresses,
            "office_pdf_render_reviewed": office_render_reviewed,
        },
        "release_ready": bool(
            reviewer_kind == "human"
            and no_faces
            and no_license_plates
            and no_personal_addresses
            and office_render_reviewed
            and not automated["metadata_violations"]
            and len(automated["documents"]) == len(EXPECTED_DOCUMENTS)
            and len(automated["final_documents"]) == len(EXPECTED_FINAL_DOCUMENTS)
        ),
    }


def write_json_atomic(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--asset-dir",
        type=Path,
        default=REPO_ROOT / "docs/submission/form_materials/assets",
    )
    parser.add_argument(
        "--document-dir",
        type=Path,
        default=REPO_ROOT / "docs/submission/design_documents",
    )
    parser.add_argument(
        "--final-document-dir",
        type=Path,
        default=REPO_ROOT / "templates",
        help="directory containing the generated working DOCX/PPTX reviewed before promotion",
    )
    parser.add_argument("--reviewer", required=True)
    parser.add_argument("--reviewer-kind", choices=["human", "assistant"], required=True)
    parser.add_argument("--confirm-no-faces", action="store_true")
    parser.add_argument("--confirm-no-license-plates", action="store_true")
    parser.add_argument("--confirm-no-personal-addresses", action="store_true")
    parser.add_argument("--confirm-office-render-reviewed", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    receipt = build_receipt(
        args.asset_dir,
        args.document_dir,
        args.final_document_dir,
        reviewer=args.reviewer,
        reviewer_kind=args.reviewer_kind,
        no_faces=args.confirm_no_faces,
        no_license_plates=args.confirm_no_license_plates,
        no_personal_addresses=args.confirm_no_personal_addresses,
        office_render_reviewed=args.confirm_office_render_reviewed,
    )
    write_json_atomic(args.output, receipt)
    print(json.dumps({"output": str(args.output), "release_ready": receipt["release_ready"]}))
    return 0 if receipt["release_ready"] else 3


if __name__ == "__main__":
    raise SystemExit(main())
