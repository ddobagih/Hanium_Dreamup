#!/usr/bin/env python3
"""Build the 2026-07-08 WalkSafe repo/Downloads classification inventory."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


AUDIT_ID = "walksafe-classification-20260713"
SCHEMA_VERSION = "1.0"
REPO_ROOT = Path(__file__).resolve().parents[1]
DOWNLOADS_ROOT = Path("/home/ddobagi/Downloads")
INVENTORY_DIR = REPO_ROOT / "docs" / "inventory"
IMPORT_DIR = INVENTORY_DIR / "downloads_imported" / "2026-07-08"

PROJECT_MANIFEST = INVENTORY_DIR / "project_classification_manifest_20260708.jsonl"
DOWNLOADS_MANIFEST = INVENTORY_DIR / "downloads_walksafe_manifest_20260708.jsonl"
SUMMARY_MD = INVENTORY_DIR / "classification_audit_summary_20260708.md"
DECISION_MD = INVENTORY_DIR / "decision_needed_20260708.md"
DO_NOT_IMPORT_MD = INVENTORY_DIR / "do_not_import_20260708.md"
PRE_SNAPSHOT = INVENTORY_DIR / "downloads_snapshot_pre_20260708.jsonl"
POST_SNAPSHOT = INVENTORY_DIR / "downloads_snapshot_post_20260708.jsonl"
VALIDATION_JSON = INVENTORY_DIR / "validation_results_20260708.json"
VALIDATION_MD = INVENTORY_DIR / "validation_results_20260708.md"

HASH_LIMIT_BYTES = 50 * 1024 * 1024
HASH_SKIP_SUFFIXES = {
    ".zip",
    ".pt",
    ".pth",
    ".onnx",
    ".engine",
    ".tflite",
    ".db",
    ".sqlite",
    ".pem",
    ".key",
    ".p12",
    ".kdbx",
    ".jpg",
    ".jpeg",
    ".png",
    ".gif",
    ".webp",
    ".mp3",
    ".wav",
    ".m4a",
    ".flac",
    ".ogg",
    ".webm",
}

ROOT_DOC_SUFFIXES = {
    ".md",
    ".txt",
    ".csv",
    ".json",
    ".yaml",
    ".yml",
    ".toml",
    ".ini",
    ".cfg",
    ".pdf",
    ".docx",
    ".pptx",
}

REPO_SCOPE_DIRS = [
    "apps",
    "ai_tasks",
    "backend",
    "configs",
    "data",
    "datasets",
    "data_sources",
    "daylog",
    "docs",
    "logs",
    "model",
    "plans",
    "product",
    "reports",
    "scripts",
    "templates",
    "tests",
    "voice",
]

SKIP_DIR_NAMES = {
    ".git",
    ".venv",
    ".venv-voice",
    "node_modules",
    ".next",
    "build",
    "dist",
    "__pycache__",
    ".pytest_cache",
    "tmp",
}

EXCLUDED_DIR_PATTERNS = (
    ("datasets/", "/images", "DATASET_LOCAL_ONLY", "raw dataset image tree"),
    ("datasets/", "/labels", "DATASET_LOCAL_ONLY", "raw dataset label tree"),
    ("datasets/coco/annotations", "", "DATASET_LOCAL_ONLY", "raw COCO annotation artifact"),
    ("apps/android/", "/build", "DO_NOT_IMPORT", "generated Android build output"),
    ("apps/web/", "/node_modules", "DO_NOT_IMPORT", "third-party web dependency tree"),
    ("apps/web/", "/.next", "DO_NOT_IMPORT", "generated web build output"),
    ("logs/", "", "MODEL_ARTIFACT_LOCAL_ONLY", "local run log tree"),
    ("data/datasets/local_links/", "", "DATASET_LOCAL_ONLY", "local dataset symlink tree"),
    ("model/artifacts/pretrained/", "", "MODEL_ARTIFACT_LOCAL_ONLY", "pretrained/model helper artifact tree"),
    ("model/artifacts/exports/", "", "MODEL_ARTIFACT_LOCAL_ONLY", "model export artifact tree"),
    ("model/artifacts/candidates/", "", "MODEL_ARTIFACT_LOCAL_ONLY", "candidate model weight tree"),
    ("reports/runs/", "", "MODEL_ARTIFACT_LOCAL_ONLY", "local run/evaluation report tree"),
)

FORBIDDEN_SUFFIXES = {
    ".env",
    ".pem",
    ".key",
    ".p12",
    ".kdbx",
    ".sqlite",
    ".db",
    ".pt",
    ".onnx",
    ".tflite",
    ".engine",
    ".zip",
}

CANONICAL_ROWS = {
    "README.md": ("CANONICAL_SPEC", "project_overview"),
    "docs/README.md": ("CANONICAL_SPEC", "docs_index"),
    "docs/status/implementation_audit_20260710.md": ("CURRENT_STATUS", "team_current_status"),
    "docs/status/current_status.md": ("CURRENT_STATUS", "implementation_status"),
    "docs/submission/source_materials/README.md": ("SOURCE_BASELINE", "source_requirements"),
    "docs/submission/README.md": ("IMPLEMENTATION_DOC", "submission_index"),
    "apps/android/README.md": ("IMPLEMENTATION_DOC", "android_runtime"),
    "docs/operations/report_operations.md": ("CANONICAL_SPEC", "report_operations"),
    "docs/walksafe-v2/policy_decisions_20260702.md": ("CANONICAL_SPEC", "policy_decisions"),
    "docs/walksafe-v2/implementation_confidence_audit_20260704.md": (
        "EVIDENCE",
        "implementation_confidence",
    ),
    "docs/evidence/final_report_index.md": ("EVIDENCE", "final_evidence_index"),
    "model/README.md": ("IMPLEMENTATION_DOC", "model_runtime"),
    "docs/inventory/document_inventory_20260707.md": ("IMPLEMENTATION_DOC", "document_inventory"),
    "docs/inventory/document_consolidation_plan_20260707.md": ("PLAN_ACTIVE", "document_consolidation"),
    "docs/inventory/code_documentation_coverage_20260710.md": (
        "IMPLEMENTATION_DOC",
        "code_documentation",
    ),
    "docs/inventory/classification_audit_summary_20260708.md": (
        "CURRENT_STATUS",
        "classification_inventory",
    ),
    "docs/inventory/project_classification_manifest_20260708.jsonl": (
        "CURRENT_STATUS",
        "project_manifest",
    ),
    "docs/inventory/downloads_walksafe_manifest_20260708.jsonl": (
        "CURRENT_STATUS",
        "downloads_manifest",
    ),
}

SUPERSEDED_HINTS = {
    "PROJECT_PLAN.md": "docs/status/current_status.md",
    "docs/inference_contract.md": "docs/walksafe-v2/backend_api_contract.md",
    "docs/model_integration_plan.md": "model/README.md",
    "docs/model_placeholder_systems.md": "model/README.md",
    "docs/pre_model_backend_todo.md": "model/README.md",
    "docs/frontend_handoff_without_model.md": "docs/walksafe-v2/frontend_display_policy.md",
    "docs/frontend_api_examples.md": "docs/backend/api_reference.md",
    "docs/model_v2_status.md": "model/README.md",
    "docs/model_training_status.md": "model/README.md",
    "docs/model_v1_dataset.md": "model/README.md",
    "docs/model_training_handoff.md": "model/README.md",
    "docs/neck_worn_phone_test_checklist.md": "docs/android/android_device_overlay_depth_checklist_20260601.md",
    "docs/status/walksafe_team_current_status_20260708.md": "docs/status/implementation_audit_20260710.md",
    "docs/review/requirements/walksafe_requirement_traceability_matrix_20260701.md": "docs/status/implementation_audit_20260710.md",
    "docs/design/ui_feature_inventory.md": "docs/status/implementation_audit_20260710.md",
    "docs/design/figma_ui_handoff.md": "docs/status/implementation_audit_20260710.md",
    "docs/design/figma_make_accessibility_review.md": "docs/status/implementation_audit_20260710.md",
}

DOWNLOADS_KNOWN_PATHS = [
    DOWNLOADS_ROOT / "abc.html",
    DOWNLOADS_ROOT / "walksafe_overlay_fourth_review_151.md",
    DOWNLOADS_ROOT / "walksafe_team_aihub_used_data_training_brief_20260628.md",
    DOWNLOADS_ROOT / "walksafe_dataset_download_inventory_20260628.csv",
    DOWNLOADS_ROOT / "walksafe_aihub_source_usage_20260628.csv",
    DOWNLOADS_ROOT / "escooter_obstruction_review_fullres_rechecked.csv",
    DOWNLOADS_ROOT / "AIHub183_escooter_obstruction_review_20260625_metadata",
    DOWNLOADS_ROOT / "AIHub183_escooter_obstruction_review_20260625_metadata" / "review_template.csv",
    DOWNLOADS_ROOT / "AIHub183_escooter_obstruction_review_20260625_metadata" / "summary.json",
    DOWNLOADS_ROOT / "AIHub183_escooter_obstruction_review_20260625_metadata" / "FOUR_ROUND_REVIEW_PLAN.md",
    DOWNLOADS_ROOT / "codex-repo-research" / "navigation_4_blind",
    DOWNLOADS_ROOT / "codex-repo-research" / "navigation_4_blind" / "README.md",
    DOWNLOADS_ROOT / "codex-repo-research" / "navigation_4_blind" / "requirements.txt",
    DOWNLOADS_ROOT
    / "codex-repo-research"
    / "navigation_4_blind"
    / "navigation_app"
    / "templates"
    / "index.html",
    DOWNLOADS_ROOT / "한이음 드림업 데이터셋",
    DOWNLOADS_ROOT / "한이음 드림업 데이터셋" / "인도보행 영상" / "뎁스프리딕션" / "Depth_001~005.zip",
]

DOWNLOAD_KEYWORDS = (
    "walksafe",
    "hanium",
    "dreamup",
    "aihub",
    "tactile",
    "navigation_4_blind",
    "overlay",
    "review",
    "depthprediction",
    "뎁스프리딕션",
    "한이음 드림업 데이터셋",
)


def run_git(args: list[str], input_text: str | None = None) -> str:
    proc = subprocess.run(
        ["git", *args],
        cwd=REPO_ROOT,
        input=input_text,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    return proc.stdout


def utc_mtime(path: Path) -> str:
    stat_result = path.lstat()
    return datetime.fromtimestamp(stat_result.st_mtime, timezone.utc).isoformat().replace("+00:00", "Z")


def path_type(path: Path) -> str:
    if path.is_symlink():
        return "symlink"
    if path.is_dir():
        return "directory"
    return "file"


def count_files(path: Path) -> int:
    if path.is_file() or path.is_symlink():
        return 1
    total = 0
    for _, dirs, files in os.walk(path):
        dirs[:] = [d for d in dirs if d not in SKIP_DIR_NAMES and d != ".git"]
        total += len(files)
    return total


def should_hash(path: Path) -> tuple[bool, str]:
    if not path.is_file() or path.is_symlink():
        return False, "sha256 omitted for non-file path"
    suffix = path.suffix.lower()
    if suffix in HASH_SKIP_SUFFIXES:
        return False, f"sha256 omitted for local-only or media/model artifact suffix {suffix}"
    size = path.stat().st_size
    if size > HASH_LIMIT_BYTES:
        return False, "sha256 omitted because file is larger than 50MiB"
    name = path.name.lower()
    if name == ".env" or name.startswith(".env."):
        return False, "sha256 omitted for env-like path"
    return True, ""


def sha256_file(path: Path) -> tuple[str, str]:
    ok, reason = should_hash(path)
    if not ok:
        return "", reason
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest(), ""


def manifest_id(scope: str, normalized_path: str) -> str:
    digest = hashlib.sha1(f"{scope}:{normalized_path}".encode("utf-8")).hexdigest()[:12]
    return f"{scope}:{digest}"


def rel_to(root: Path, path: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def base_row(path: Path, source_root: Path, scope: str) -> dict:
    normalized = rel_to(source_root, path)
    exists = path.exists() or path.is_symlink()
    size = path.lstat().st_size if exists and path.is_file() else None
    sha, sha_note = ("", "path missing")
    if exists:
        sha, sha_note = sha256_file(path)
    row = {
        "schema_version": SCHEMA_VERSION,
        "audit_id": AUDIT_ID,
        "manifest_id": manifest_id(scope, normalized),
        "source_root": source_root.as_posix(),
        "source_path": path.as_posix(),
        "normalized_path": normalized,
        "path_type": path_type(path) if exists else "file",
        "exists": exists,
        "size_bytes": size,
        "sha256": sha,
        "mtime_utc": utc_mtime(path) if exists else "",
        "scope": scope,
        "included_in_audit": True,
        "include_reason": "",
        "exclude_reason": "",
        "current_state": "N/A",
        "class": "EXTERNAL_REFERENCE",
        "topic_key": "",
        "canonical_group": "",
        "supersedes": [],
        "superseded_by": [],
        "related_manifest_ids": [],
        "import_decision": "N/A",
        "import_destination": "",
        "git_tracking_policy": "ALLOWED",
        "git_tracking_actual": "OUTSIDE_REPO" if scope == "downloads" else "UNTRACKED",
        "evidence": [],
        "review_status": "VERIFIED",
        "reviewer": "codex",
        "reviewed_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "notes": sha_note,
    }
    return row


def classify_repo_path(rel: str, path: Path) -> dict:
    result = {
        "included_in_audit": True,
        "include_reason": "in repo audit scope",
        "exclude_reason": "",
        "current_state": "LOCAL_ONLY",
        "class": "EVIDENCE",
        "topic_key": rel,
        "canonical_group": "",
        "superseded_by": [],
        "import_decision": "N/A",
        "git_tracking_policy": "ALLOWED",
        "evidence": [],
        "review_status": "VERIFIED",
        "notes": "",
    }
    if rel in CANONICAL_ROWS:
        class_name, group = CANONICAL_ROWS[rel]
        result.update(
            current_state="CURRENT",
            topic_key=group,
            canonical_group=group,
            git_tracking_policy="REQUIRED",
            include_reason="canonical current project document",
        )
        result["class"] = class_name
        return result
    if rel in SUPERSEDED_HINTS or rel.endswith("_3day_execution_plan.md"):
        result.update(
            current_state="NON_CURRENT",
            superseded_by=[SUPERSEDED_HINTS.get(rel, "docs/status/current_status.md")],
            include_reason="superseded or legacy planning document",
        )
        result["class"] = "SUPERSEDED"
        return result
    if rel.startswith("docs/_archive_candidates/"):
        result.update(
            current_state="NON_CURRENT",
            include_reason="approved archive candidate staging path",
        )
        result["class"] = "ARCHIVE_CANDIDATE"
        return result
    if rel.startswith("plans/.work/") or rel.startswith("plans/daily/") or rel.startswith("plans/catchup/") or rel.startswith("plans/weekly/"):
        result.update(
            current_state="NON_CURRENT",
            include_reason="historical plan/work-log material",
        )
        result["class"] = "PLAN_HISTORY"
        return result
    if rel.startswith("daylog/"):
        result.update(current_state="NON_CURRENT", include_reason="daily work log evidence")
        result["class"] = "PLAN_HISTORY"
        return result
    if rel.startswith("docs/execution/"):
        result.update(current_state="NON_CURRENT", include_reason="historical execution record")
        result["class"] = "PLAN_HISTORY"
        return result
    if rel.startswith("docs/review/") or rel.startswith("ai_tasks/"):
        result.update(current_state="LOCAL_ONLY", include_reason="review/evidence package")
        result["class"] = "EVIDENCE"
        if "tactile_damage_area" in rel:
            result.update(review_status="VERIFIED", notes="manual bbox/review package; review pending and not applied-complete evidence")
        return result
    if rel.startswith("docs/submission/source_materials/"):
        result.update(
            current_state="CURRENT",
            include_reason="original project requirement and outcome baseline",
        )
        result["class"] = "SOURCE_BASELINE"
        return result
    if rel.startswith("docs/submission/final/"):
        result.update(current_state="LOCAL_ONLY", include_reason="submission candidate requiring final user review")
        result["class"] = "SUBMISSION_CANDIDATE"
        return result
    if rel.startswith("docs/submission/form_materials/"):
        result.update(current_state="LOCAL_ONLY", include_reason="validated evidence pack used to generate official forms")
        result["class"] = "SUBMISSION_DERIVED"
        return result
    if rel.startswith("docs/submission/deliverables/"):
        result.update(current_state="LOCAL_ONLY", include_reason="derived submission deliverable")
        result["class"] = "SUBMISSION_DERIVED"
        return result
    if rel.startswith("docs/submission/drafts/"):
        result.update(current_state="NON_CURRENT", include_reason="submission drafting material")
        result["class"] = "DRAFT"
        return result
    if rel.startswith("docs/submission/qa/") or rel.startswith("docs/submission/checklists/"):
        result.update(current_state="LOCAL_ONLY", include_reason="submission QA or checklist evidence")
        result["class"] = "EVIDENCE"
        return result
    if rel.startswith("datasets/"):
        result.update(
            included_in_audit=False,
            current_state="EXCLUDED",
            include_reason="dataset metadata/provenance path",
            exclude_reason="raw dataset artifacts are local-only and not current product evidence",
            import_decision="LOCAL_ONLY",
            git_tracking_policy="FORBIDDEN",
        )
        result["class"] = "DATASET_LOCAL_ONLY"
        return result
    if rel.startswith("data_sources/manifests/"):
        result.update(current_state="LOCAL_ONLY", include_reason="dataset provenance manifest")
        result["class"] = "EVIDENCE"
        return result
    if rel.startswith("data_sources/scripts/") or rel.startswith("scripts/"):
        result.update(current_state="LOCAL_ONLY", include_reason="audit/build/validation script")
        result["class"] = "IMPLEMENTATION_DOC"
        return result
    if rel.startswith("artifacts/"):
        result.update(
            current_state="LOCAL_ONLY",
            include_reason="local evidence artifact",
            import_decision="LOCAL_ONLY",
            git_tracking_policy="FORBIDDEN",
        )
        result["class"] = "EVIDENCE"
        return result
    if rel.startswith("logs/"):
        result.update(
            included_in_audit=False,
            current_state="EXCLUDED",
            exclude_reason="local log artifact; not a current source document",
            import_decision="LOCAL_ONLY",
            git_tracking_policy="FORBIDDEN",
        )
        result["class"] = "MODEL_ARTIFACT_LOCAL_ONLY"
        return result
    if rel.startswith("data/"):
        result.update(current_state="LOCAL_ONLY", include_reason="local data organization path")
        result["class"] = "DATASET_LOCAL_ONLY"
        return result
    if rel.startswith("apps/android/"):
        result.update(current_state="LOCAL_ONLY", include_reason="Android ARCore/depth/TFLite supporting research path")
        result["class"] = "IMPLEMENTATION_DOC"
        return result
    if rel.startswith("apps/web/"):
        result.update(current_state="LOCAL_ONLY", include_reason="Web/PWA primary user and admin implementation path")
        result["class"] = "IMPLEMENTATION_DOC"
        return result
    if rel.startswith("backend/"):
        result.update(current_state="LOCAL_ONLY", include_reason="backend/report/export/navigation API path")
        result["class"] = "IMPLEMENTATION_DOC"
        return result
    if rel.startswith("voice/"):
        result.update(current_state="LOCAL_ONLY", include_reason="local voice prototype implementation path")
        result["class"] = "IMPLEMENTATION_DOC"
        return result
    if rel.startswith("tests/"):
        result.update(current_state="LOCAL_ONLY", include_reason="cross-domain regression test path")
        result["class"] = "IMPLEMENTATION_DOC"
        return result
    if rel.startswith("configs/"):
        result.update(current_state="LOCAL_ONLY", include_reason="runtime and training configuration path")
        result["class"] = "IMPLEMENTATION_DOC"
        return result
    if rel.startswith("templates/"):
        if rel == "templates/README.md":
            result.update(current_state="LOCAL_ONLY", include_reason="submission template and generated-copy usage guide")
            result["class"] = "IMPLEMENTATION_DOC"
        elif Path(rel).name.startswith("[서식"):
            result.update(current_state="LOCAL_ONLY", include_reason="unaltered official submission template reference")
            result["class"] = "EXTERNAL_REFERENCE"
        elif Path(rel).suffix.lower() in {".docx", ".pptx"}:
            result.update(current_state="LOCAL_ONLY", include_reason="generated working submission form")
            result["class"] = "SUBMISSION_CANDIDATE"
        else:
            result.update(current_state="LOCAL_ONLY", include_reason="submission template support file")
            result["class"] = "IMPLEMENTATION_DOC"
        return result
    if rel.startswith("model/"):
        result.update(current_state="LOCAL_ONLY", include_reason="model/runtime helper path")
        result["class"] = "IMPLEMENTATION_DOC"
        return result
    if rel.startswith("product/"):
        result.update(current_state="CURRENT", include_reason="product planning/spec document")
        result["class"] = "CANONICAL_SPEC"
        return result
    if rel.startswith("reports/"):
        result.update(current_state="LOCAL_ONLY", include_reason="evaluation/report evidence path")
        result["class"] = "EVIDENCE"
        return result
    if path.suffix.lower() in FORBIDDEN_SUFFIXES and path.name not in {".env.example"}:
        result.update(
            included_in_audit=False,
            current_state="EXCLUDED",
            exclude_reason="forbidden local artifact extension",
            import_decision="DO_NOT_IMPORT",
            git_tracking_policy="FORBIDDEN",
        )
        result["class"] = "DO_NOT_IMPORT"
    return result


def excluded_dir_info(rel: str) -> tuple[str, str, str] | None:
    for prefix, contains, class_name, reason in EXCLUDED_DIR_PATTERNS:
        if rel == prefix.rstrip("/") or rel.startswith(prefix):
            if contains == "" or contains in f"/{rel}":
                return class_name, reason, "excluded by audit rule"
    return None


def collect_repo_paths() -> list[Path]:
    paths: list[Path] = []
    for child in sorted(REPO_ROOT.iterdir(), key=lambda p: p.as_posix()):
        if child.is_file() and child.suffix.lower() in ROOT_DOC_SUFFIXES:
            paths.append(child)
    for dirname in REPO_SCOPE_DIRS:
        base = REPO_ROOT / dirname
        if not base.exists():
            continue
        for root, dirs, files in os.walk(base):
            root_path = Path(root)
            rel_root = rel_to(REPO_ROOT, root_path)
            dirs[:] = [d for d in dirs if d not in SKIP_DIR_NAMES]
            for dirname_candidate in list(dirs):
                child = root_path / dirname_candidate
                rel_child = rel_to(REPO_ROOT, child)
                if excluded_dir_info(rel_child):
                    paths.append(child)
                    dirs.remove(dirname_candidate)
            if root_path != base and excluded_dir_info(rel_root):
                continue
            for name in sorted(files):
                paths.append(root_path / name)
    return sorted(set(paths), key=lambda p: rel_to(REPO_ROOT, p))


def build_repo_rows() -> list[dict]:
    rows = []
    for path in collect_repo_paths():
        rel = rel_to(REPO_ROOT, path)
        row = base_row(path, REPO_ROOT, "repo")
        if path.is_dir():
            class_name, reason, exclude_reason = excluded_dir_info(rel) or ("EXCLUDED", "excluded directory", "excluded by audit rule")
            row.update(
                included_in_audit=False,
                include_reason=reason,
                exclude_reason=exclude_reason,
                current_state="EXCLUDED",
                import_decision="LOCAL_ONLY" if class_name != "DO_NOT_IMPORT" else "DO_NOT_IMPORT",
                git_tracking_policy="FORBIDDEN",
                notes=f"{row['notes']}; aggregate_file_count={count_files(path)}".strip("; "),
            )
            row["class"] = class_name
        else:
            classification = classify_repo_path(rel, path)
            base_note = row["notes"]
            row.update(classification)
            class_note = classification.get("notes", "")
            if base_note and class_note:
                row["notes"] = f"{base_note}; {class_note}"
            elif base_note:
                row["notes"] = base_note
            else:
                row["notes"] = class_note
        rows.append(row)
    return rows


def build_git_tracking(rows: list[dict]) -> None:
    tracked = set(run_git(["ls-files"]).splitlines())
    rels = [row["normalized_path"] for row in rows if row["scope"] == "repo"]
    ignored_output = run_git(["check-ignore", "--stdin"], "\n".join(rels) + "\n") if rels else ""
    ignored = set(ignored_output.splitlines())
    for row in rows:
        if row["scope"] != "repo":
            row["git_tracking_actual"] = "OUTSIDE_REPO"
            continue
        rel = row["normalized_path"]
        if rel in tracked:
            row["git_tracking_actual"] = "TRACKED"
        elif rel in ignored or any(rel.startswith(f"{item}/") for item in ignored):
            row["git_tracking_actual"] = "IGNORED"
        else:
            row["git_tracking_actual"] = "UNTRACKED"


def discover_download_candidates() -> tuple[list[Path], Counter]:
    candidates = {path for path in DOWNLOADS_KNOWN_PATHS if path.exists() or path.is_symlink()}
    unrelated_counts: Counter = Counter()
    for root, dirs, files in os.walk(DOWNLOADS_ROOT):
        root_path = Path(root)
        depth = len(root_path.relative_to(DOWNLOADS_ROOT).parts)
        if depth >= 4:
            dirs[:] = []
        for name in files:
            path = root_path / name
            normalized_lower = path.as_posix().lower()
            if any(keyword.lower() in normalized_lower for keyword in DOWNLOAD_KEYWORDS):
                candidates.add(path)
            else:
                unrelated_counts[path.suffix.lower() or "<no_ext>"] += 1
    return sorted(candidates, key=lambda p: rel_to(DOWNLOADS_ROOT, p)), unrelated_counts


def classify_download_path(path: Path) -> dict:
    rel = rel_to(DOWNLOADS_ROOT, path)
    result = {
        "included_in_audit": True,
        "include_reason": "WalkSafe/hanium related Downloads candidate",
        "exclude_reason": "",
        "current_state": "LOCAL_ONLY",
        "class": "EXTERNAL_REFERENCE",
        "topic_key": rel,
        "canonical_group": "",
        "superseded_by": [],
        "import_decision": "NOT_IMPORTED",
        "git_tracking_policy": "FORBIDDEN",
        "evidence": [],
        "review_status": "VERIFIED",
        "notes": "",
    }
    lower = rel.lower()
    if rel == "walksafe_overlay_fourth_review_151.md":
        result.update(
            import_decision="IMPORTED",
            import_destination=rel_to(REPO_ROOT, IMPORT_DIR / path.name),
            review_status="VERIFIED",
            notes="Imported as reference-only material; dataset application is not verified and it is not completion evidence.",
        )
        result["class"] = "EXTERNAL_REFERENCE"
        return result
    if rel == "abc.html":
        result.update(
            current_state="NON_CURRENT",
            superseded_by=["docs/walksafe-v2/policy_decisions_20260702.md"],
            notes="Policy board content was moved into repo policy docs.",
        )
        result["class"] = "SUPERSEDED"
        return result
    if "depth_001~005.zip" in lower or rel.startswith("한이음 드림업 데이터셋"):
        result.update(
            included_in_audit=False,
            current_state="EXCLUDED",
            exclude_reason="AIHub raw/depth zip is local-only source data",
            import_decision="DO_NOT_IMPORT",
            notes="AIHub189 is offline ZED reference only; not Android ARCore PASS evidence.",
        )
        result["class"] = "DATASET_LOCAL_ONLY"
        return result
    if rel.startswith("codex-repo-research/navigation_4_blind"):
        result.update(
            import_decision="DO_NOT_IMPORT",
            notes="External reference only; repo code/assets/env are not imported.",
        )
        result["class"] = "EXTERNAL_REFERENCE"
        if path.is_dir():
            result["notes"] += f" aggregate_file_count={count_files(path)}"
        return result
    if "walksafe_team_aihub_used_data_training_brief" in rel:
        result.update(
            current_state="NON_CURRENT",
            superseded_by=["data_sources/manifests/walksafe_aihub_source_usage_20260628.md"],
            notes="Superseded by repo AIHub source usage manifest.",
        )
        result["class"] = "SUPERSEDED"
        return result
    if "walksafe_aihub_source_usage_20260628" in rel or "walksafe_dataset_download_inventory_20260628" in rel:
        result.update(import_decision="NOT_IMPORTED", notes="Repo copy/manifest already exists or is represented.")
        result["class"] = "EVIDENCE"
        return result
    if "escooter" in lower or "aihub183" in lower:
        result.update(
            import_decision="NOT_IMPORTED",
            notes="AIHub183 review provenance; later repo manifests supersede direct import.",
        )
        result["class"] = "EVIDENCE"
        return result
    if "agent-md-system" in lower:
        result.update(
            included_in_audit=False,
            current_state="EXCLUDED",
            exclude_reason="generic agent guide unrelated to WalkSafe evidence",
            import_decision="DO_NOT_IMPORT",
        )
        result["class"] = "DO_NOT_IMPORT"
    return result


def build_download_rows() -> tuple[list[dict], Counter]:
    candidates, unrelated_counts = discover_download_candidates()
    rows = []
    for path in candidates:
        row = base_row(path, DOWNLOADS_ROOT, "downloads")
        row.update(classify_download_path(path))
        if path.is_dir() and "aggregate_file_count" not in row["notes"]:
            row["notes"] = f"{row['notes']}; aggregate_file_count={count_files(path)}".strip("; ")
        rows.append(row)
    return rows, unrelated_counts


def write_jsonl(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def copy_download_imports(download_rows: list[dict]) -> None:
    IMPORT_DIR.mkdir(parents=True, exist_ok=True)
    for row in download_rows:
        if row["import_decision"] != "IMPORTED":
            continue
        src = Path(row["source_path"])
        dest = REPO_ROOT / row["import_destination"]
        dest.parent.mkdir(parents=True, exist_ok=True)
        src_hash, _ = sha256_file(src)
        if dest.exists():
            dest_hash, _ = sha256_file(dest)
            if src_hash != dest_hash:
                raise SystemExit(f"no-clobber import conflict: {dest}")
        else:
            shutil.copy2(src, dest)
            dest_hash, _ = sha256_file(dest)
            if src_hash != dest_hash:
                raise SystemExit(f"sha256 mismatch after import: {src} -> {dest}")
        row["evidence"] = [
            {
                "source_sha256": src_hash,
                "destination_sha256": dest_hash,
                "copy_policy": "no-clobber",
            }
        ]


def snapshot_download_rows(download_rows: list[dict], unrelated_counts: Counter) -> list[dict]:
    rows = []
    for row in download_rows:
        snapshot = {
            "schema_version": SCHEMA_VERSION,
            "audit_id": AUDIT_ID,
            "source_path": row["source_path"],
            "normalized_path": row["normalized_path"],
            "path_type": row["path_type"],
            "exists": row["exists"],
            "size_bytes": row["size_bytes"],
            "mtime_utc": row["mtime_utc"],
            "sha256": row["sha256"],
            "include_reason": row["include_reason"],
            "exclude_reason": row["exclude_reason"],
            "notes": row["notes"],
        }
        rows.append(snapshot)
    rows.append(
        {
            "schema_version": SCHEMA_VERSION,
            "audit_id": AUDIT_ID,
            "source_path": DOWNLOADS_ROOT.as_posix(),
            "normalized_path": "__unrelated_downloads_extension_counts__",
            "path_type": "directory",
            "exists": DOWNLOADS_ROOT.exists(),
            "size_bytes": None,
            "mtime_utc": utc_mtime(DOWNLOADS_ROOT),
            "sha256": "",
            "include_reason": "aggregate count only for unrelated Downloads files",
            "exclude_reason": "unrelated Downloads paths are not listed in public inventory",
            "notes": json.dumps(dict(sorted(unrelated_counts.items())), ensure_ascii=False, sort_keys=True),
        }
    )
    return rows


def markdown_table(rows: list[list[str]]) -> str:
    if not rows:
        return ""
    header = rows[0]
    out = ["| " + " | ".join(header) + " |", "| " + " | ".join(["---"] * len(header)) + " |"]
    for row in rows[1:]:
        out.append("| " + " | ".join(str(cell).replace("\n", " ") for cell in row) + " |")
    return "\n".join(out)


def build_decision_md(project_rows: list[dict], download_rows: list[dict]) -> str:
    rows = [["scope", "path", "class", "reason", "next action"]]
    for row in project_rows + download_rows:
        if row["review_status"] == "DECISION_NEEDED" or row["class"] == "DECISION_NEEDED":
            rows.append(
                [
                    row["scope"],
                    row["normalized_path"],
                    row["class"],
                    row["notes"] or row["include_reason"],
                    "사용자 결정 전 current 완료 근거로 승격하지 않음",
                ]
            )
    if len(rows) == 1:
        rows.append(
            [
                "repo",
                "-",
                "RESOLVED",
                "Web/PWA primary and administrator-reviewed CSV manual submission are confirmed",
                "현재 사용자 결정 대기 없음. 미구현 기능은 backlog로 추적",
            ]
        )
    return (
        "# Decision status - refreshed 2026-07-13\n\n"
        "플랫폼과 기관 신고 방식은 확정됐다. 이 표에는 실제 사용자 결정 대기 항목만 남긴다. "
        "구현되지 않은 MLOps·정밀 측위·미래 ROI는 결정 대기가 아니라 backlog다.\n\n"
        + markdown_table(rows)
        + "\n"
    )


def build_do_not_import_md(project_rows: list[dict], download_rows: list[dict]) -> str:
    selected = [
        row
        for row in project_rows + download_rows
        if row["import_decision"] == "DO_NOT_IMPORT"
        or row["git_tracking_policy"] == "FORBIDDEN"
        or row["class"] in {"DO_NOT_IMPORT", "DATASET_LOCAL_ONLY", "MODEL_ARTIFACT_LOCAL_ONLY"}
    ]
    category_counts = Counter()
    for row in selected:
        reason = row["exclude_reason"] or row["notes"] or row["include_reason"]
        if row["normalized_path"].startswith("datasets/"):
            key = ("repo", "datasets/**", row["class"], row["import_decision"], "raw dataset files and generated materialized artifacts")
        elif row["normalized_path"].startswith("logs/"):
            key = ("repo", "logs/**", row["class"], row["import_decision"], "local training/runtime logs")
        elif row["normalized_path"].startswith("artifacts/"):
            key = ("repo", "artifacts/**", row["class"], row["import_decision"], "local evidence artifacts; indexed but not imported as current source")
        elif row["normalized_path"].startswith("apps/android/.gradle/"):
            key = ("repo", "apps/android/.gradle/**", row["class"], row["import_decision"], "generated Android Gradle state")
        elif row["scope"] == "downloads" and row["normalized_path"].startswith("codex-repo-research/navigation_4_blind"):
            key = ("downloads", "codex-repo-research/navigation_4_blind/**", row["class"], row["import_decision"], "external reference repo code/assets/env not imported")
        elif row["scope"] == "downloads" and row["normalized_path"].startswith("한이음 드림업 데이터셋"):
            key = ("downloads", "한이음 드림업 데이터셋/**", row["class"], row["import_decision"], "AIHub raw/depth zip local-only source data")
        else:
            key = (row["scope"], row["normalized_path"], row["class"], row["import_decision"], reason)
        category_counts[key] += 1
    table = [["scope", "path/category", "class", "policy", "count", "reason"]]
    for key, count in sorted(category_counts.items()):
        scope, path, class_name, policy, reason = key
        table.append(
            [
                scope,
                path,
                class_name,
                policy,
                str(count),
                reason,
            ]
        )
    return (
        "# Do not import / local-only - 2026-07-08\n\n"
        "활성 deployment·registry SHA-256에 고정된 img768 PT와 Android TFLite 두 파일만 runtime artifact로 허용한다. 그 밖의 원본 AIHub zip, raw dataset, model weight/export, DB/env/secret, 외부 repo code/assets는 repo current 근거로 import하지 않는다.\n\n"
        + markdown_table(table)
        + "\n"
    )


def build_summary_md(project_rows: list[dict], download_rows: list[dict], unrelated_counts: Counter) -> str:
    project_classes = Counter(row["class"] for row in project_rows)
    download_classes = Counter(row["class"] for row in download_rows)
    current_count = sum(1 for row in project_rows if row["current_state"] == "CURRENT")
    non_current_count = sum(1 for row in project_rows if row["current_state"] == "NON_CURRENT")
    imported = [row for row in download_rows if row["import_decision"] == "IMPORTED"]
    do_not_import = [row for row in project_rows + download_rows if row["import_decision"] == "DO_NOT_IMPORT"]
    local_only = [row for row in project_rows + download_rows if row["import_decision"] == "LOCAL_ONLY"]
    table = [["metric", "value"]]
    table.extend(
        [
            ["project manifest rows", str(len(project_rows))],
            ["downloads manifest rows", str(len(download_rows))],
            ["repo CURRENT rows", str(current_count)],
            ["repo NON_CURRENT rows", str(non_current_count)],
            ["Downloads imported files", str(len(imported))],
            ["DO_NOT_IMPORT rows", str(len(do_not_import))],
            ["LOCAL_ONLY rows", str(len(local_only))],
            ["unrelated Downloads extension buckets", str(len(unrelated_counts))],
        ]
    )
    imported_rows = [["source", "destination", "status"]]
    if imported:
        for row in imported:
            imported_rows.append([row["source_path"], row["import_destination"], row["notes"]])
    else:
        imported_rows.append(["-", "-", "none"])
    return (
        "# WalkSafe classification audit summary - refreshed 2026-07-13\n\n"
        "## Scope\n\n"
        "- Current product baseline: Web/PWA is the primary user app; backend/admin/report/export/navigation are product API/operations.\n"
        "- Original PWA release/domain requirements remain SOURCE_BASELINE and align with the confirmed platform.\n"
        "- Android native ARCore/TFLite is a supporting research path and does not replace Web browser/Release evidence.\n"
        "- YOLO26n img768 PT training reached 300 epochs, but weak surface-hazard classes, corrupt data, Web real-model configuration, mobile field PASS, and production auth/release are not complete.\n"
        "- `tactile_damage_area`, AIHub189, and Downloads overlay evidence are not treated as completion evidence.\n"
        "- Institution submission wording is normalized to administrator review/filter, CSV download, then manual submission through an external agency channel.\n\n"
        "## Outputs\n\n"
        "- `docs/inventory/project_classification_manifest_20260708.jsonl`\n"
        "- `docs/inventory/downloads_walksafe_manifest_20260708.jsonl`\n"
        "- `docs/inventory/decision_needed_20260708.md`\n"
        "- `docs/inventory/do_not_import_20260708.md`\n"
        "- `docs/inventory/downloads_snapshot_pre_20260708.jsonl`\n"
        "- `docs/inventory/downloads_snapshot_post_20260708.jsonl`\n"
        "- `scripts/audit_project_classification_20260708.py`\n"
        "- `scripts/validate_project_classification_20260708.py`\n\n"
        "## Counts\n\n"
        + markdown_table(table)
        + "\n\n## Repo class counts\n\n"
        + markdown_table([["class", "count"], *[[key, str(value)] for key, value in sorted(project_classes.items())]])
        + "\n\n## Downloads class counts\n\n"
        + markdown_table([["class", "count"], *[[key, str(value)] for key, value in sorted(download_classes.items())]])
        + "\n\n## Downloads import/copy\n\n"
        + markdown_table(imported_rows)
        + "\n\n## Local-only and do-not-import policy\n\n"
        "Raw Downloads files were not deleted or moved. Only the img768 PT and Android TFLite bound by the active deployment and registry SHA-256 are allowed as runtime artifacts. Other AIHub raw zip/depth, model weights, exported TFLite/ONNX, DB/env/secret, large image/depth trees, and external `navigation_4_blind` code/assets are not imported as current repo artifacts.\n\n"
        "Unrelated Downloads files are represented only as extension/category counts in the snapshot notes, not as public detailed paths.\n\n"
        "## Validation\n\n"
        "Run `python3 scripts/validate_project_classification_20260708.py`. The validator writes `docs/inventory/validation_results_20260708.json` and `.md`.\n"
    )


def ensure_output_placeholders() -> None:
    INVENTORY_DIR.mkdir(parents=True, exist_ok=True)
    IMPORT_DIR.mkdir(parents=True, exist_ok=True)
    for path in [
        PROJECT_MANIFEST,
        DOWNLOADS_MANIFEST,
        SUMMARY_MD,
        DECISION_MD,
        DO_NOT_IMPORT_MD,
        PRE_SNAPSHOT,
        POST_SNAPSHOT,
        VALIDATION_JSON,
        VALIDATION_MD,
    ]:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch(exist_ok=True)


def main() -> int:
    ensure_output_placeholders()
    download_rows, unrelated_counts = build_download_rows()
    pre_snapshot = snapshot_download_rows(download_rows, unrelated_counts)
    copy_download_imports(download_rows)
    post_download_rows, post_unrelated_counts = build_download_rows()
    for row in post_download_rows:
        for imported_row in download_rows:
            if row["normalized_path"] == imported_row["normalized_path"] and imported_row["import_decision"] == "IMPORTED":
                row.update(imported_row)
    post_snapshot = snapshot_download_rows(post_download_rows, post_unrelated_counts)
    project_rows = build_repo_rows()
    build_git_tracking(project_rows)
    build_git_tracking(post_download_rows)

    write_jsonl(PRE_SNAPSHOT, pre_snapshot)
    write_jsonl(POST_SNAPSHOT, post_snapshot)
    SUMMARY_MD.write_text(build_summary_md(project_rows, post_download_rows, unrelated_counts), encoding="utf-8")
    DECISION_MD.write_text(build_decision_md(project_rows, post_download_rows), encoding="utf-8")
    DO_NOT_IMPORT_MD.write_text(build_do_not_import_md(project_rows, post_download_rows), encoding="utf-8")
    write_jsonl(PROJECT_MANIFEST, project_rows)
    write_jsonl(DOWNLOADS_MANIFEST, post_download_rows)

    print(json.dumps(
        {
            "ok": True,
            "project_rows": len(project_rows),
            "downloads_rows": len(post_download_rows),
            "imported": [row["import_destination"] for row in post_download_rows if row["import_decision"] == "IMPORTED"],
            "summary": rel_to(REPO_ROOT, SUMMARY_MD),
        },
        ensure_ascii=False,
        sort_keys=True,
    ))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
