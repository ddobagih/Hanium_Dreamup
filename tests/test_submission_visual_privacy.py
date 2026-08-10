from __future__ import annotations

from pathlib import Path
import sys

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import scripts.audit_submission_visual_privacy_20260711 as privacy  # noqa: E402


def office_render_stub(_root: Path, *_args: object) -> dict[str, object]:
    return {
        "report": {"name": "개발보고서 양식.docx", "sha256": "c" * 64, "pdf_pages": 1},
        "slides": {
            "name": "제작설계서_일반.pptx",
            "sha256": "d" * 64,
            "pdf_pages": 34,
            "slides": 34,
            "aspect_ratio": "4:3",
        },
    }


def test_assistant_review_cannot_mark_release_ready(tmp_path: Path, monkeypatch) -> None:
    for name in privacy.EXPECTED_ASSETS:
        Image.new("RGB", (80, 80), "white").save(tmp_path / name)
    monkeypatch.setattr(privacy, "audit_assets", lambda _root: {"metadata_violations": [], "face_candidates": [], "assets": []})
    monkeypatch.setattr(privacy, "audit_documents", lambda _root: [{"name": "doc.docx", "sha256": "a" * 64}] * 8)
    monkeypatch.setattr(privacy, "audit_final_documents", lambda _root: [{"name": "final.docx", "sha256": "b" * 64}] * 2)
    monkeypatch.setattr(privacy, "audit_office_render", office_render_stub)
    monkeypatch.setattr(privacy, "submission_toolchain_attestation", lambda _root: {})
    monkeypatch.setattr(
        privacy,
        "require_clean_source_revision",
        lambda _root, _excluded: {"source_commit": "a" * 40, "source_dirty": False},
    )

    receipt = privacy.build_receipt(
        tmp_path,
        tmp_path,
        tmp_path,
        reviewer="codex.visual",
        reviewer_kind="assistant",
        no_faces=True,
        no_license_plates=True,
        no_personal_addresses=True,
        office_render_reviewed=True,
    )

    assert receipt["release_ready"] is False
    assert receipt["source_commit"] == "a" * 40


def test_human_review_requires_every_manual_assertion(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(privacy, "audit_assets", lambda _root: {"metadata_violations": [], "face_candidates": [], "assets": []})
    monkeypatch.setattr(privacy, "audit_documents", lambda _root: [{"name": "doc.docx", "sha256": "a" * 64}] * 8)
    monkeypatch.setattr(privacy, "audit_final_documents", lambda _root: [{"name": "final.docx", "sha256": "b" * 64}] * 2)
    monkeypatch.setattr(privacy, "audit_office_render", office_render_stub)
    monkeypatch.setattr(privacy, "submission_toolchain_attestation", lambda _root: {})
    monkeypatch.setattr(
        privacy,
        "require_clean_source_revision",
        lambda _root, _excluded: {"source_commit": "a" * 40, "source_dirty": False},
    )
    receipt = privacy.build_receipt(
        tmp_path,
        tmp_path,
        tmp_path,
        reviewer="reviewer.kim",
        reviewer_kind="human",
        no_faces=True,
        no_license_plates=False,
        no_personal_addresses=True,
        office_render_reviewed=True,
    )
    assert receipt["release_ready"] is False
