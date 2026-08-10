#!/usr/bin/env python3
"""Validate the 2026-07-08 WalkSafe classification inventory."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
INVENTORY_DIR = REPO_ROOT / "docs" / "inventory"
PROJECT_MANIFEST = INVENTORY_DIR / "project_classification_manifest_20260708.jsonl"
DOWNLOADS_MANIFEST = INVENTORY_DIR / "downloads_walksafe_manifest_20260708.jsonl"
PRE_SNAPSHOT = INVENTORY_DIR / "downloads_snapshot_pre_20260708.jsonl"
POST_SNAPSHOT = INVENTORY_DIR / "downloads_snapshot_post_20260708.jsonl"
RESULT_JSON = INVENTORY_DIR / "validation_results_20260708.json"
RESULT_MD = INVENTORY_DIR / "validation_results_20260708.md"

REQUIRED_FIELDS = {
    "schema_version",
    "audit_id",
    "source_root",
    "source_path",
    "normalized_path",
    "path_type",
    "exists",
    "size_bytes",
    "sha256",
    "mtime_utc",
    "scope",
    "included_in_audit",
    "include_reason",
    "exclude_reason",
    "current_state",
    "class",
    "topic_key",
    "canonical_group",
    "supersedes",
    "superseded_by",
    "related_manifest_ids",
    "import_decision",
    "import_destination",
    "git_tracking_policy",
    "git_tracking_actual",
    "evidence",
    "review_status",
    "reviewer",
    "reviewed_at",
    "notes",
}

PATH_TYPES = {"file", "directory", "symlink"}
SCOPES = {"repo", "downloads"}
CURRENT_STATES = {"CURRENT", "NON_CURRENT", "LOCAL_ONLY", "EXCLUDED", "N/A"}
CLASSES = {
    "CANONICAL_SPEC",
    "CURRENT_STATUS",
    "IMPLEMENTATION_DOC",
    "EVIDENCE",
    "PLAN_ACTIVE",
    "PLAN_HISTORY",
    "SUPERSEDED",
    "ARCHIVE_CANDIDATE",
    "EXTERNAL_REFERENCE",
    "DATASET_LOCAL_ONLY",
    "MODEL_ARTIFACT_LOCAL_ONLY",
    "DOWNLOADS_IMPORTED",
    "DO_NOT_IMPORT",
    "DECISION_NEEDED",
    "EXCLUDED",
    "SOURCE_BASELINE",
    "SUBMISSION_CANDIDATE",
    "SUBMISSION_DERIVED",
    "DRAFT",
}
IMPORT_DECISIONS = {"IMPORTED", "NOT_IMPORTED", "LOCAL_ONLY", "DO_NOT_IMPORT", "DECISION_NEEDED", "N/A"}
TRACKING_POLICY = {"ALLOWED", "FORBIDDEN", "REQUIRED"}
TRACKING_ACTUAL = {"TRACKED", "UNTRACKED", "IGNORED", "OUTSIDE_REPO"}
REVIEW_STATUS = {"VERIFIED", "DECISION_NEEDED", "FAILED"}
CURRENT_CLASSES = {
    "CANONICAL_SPEC",
    "CURRENT_STATUS",
    "IMPLEMENTATION_DOC",
    "EVIDENCE",
    "PLAN_ACTIVE",
    "SOURCE_BASELINE",
}
CANONICAL_GROUPS = {
    "project_overview",
    "docs_index",
    "team_current_status",
    "implementation_status",
    "android_runtime",
    "report_operations",
    "policy_decisions",
    "implementation_confidence",
    "final_evidence_index",
    "model_runtime",
    "classification_inventory",
    "project_manifest",
    "downloads_manifest",
    "source_requirements",
    "code_documentation",
    "submission_index",
}
FORBIDDEN_RE = re.compile(
    r"(\.env$|\.env\.|secret|token|\.pem$|\.key$|\.p12$|\.kdbx$|\.sqlite$|\.db$|\.pt$|\.onnx$|\.tflite$|\.engine$|\.zip$)",
    re.IGNORECASE,
)
ALLOWLIST_RE = re.compile(r"(^|/)\.env\.example$")
BAD_MARKERS = ("UNKNOWN", "TODO_CLASSIFY", "임시", "나중에 확인")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def active_runtime_artifact_allowlist() -> dict[str, str]:
    registry = json.loads(
        (REPO_ROOT / "model/registry/walksafe-model-registry.json").read_text(encoding="utf-8")
    )
    deployment = json.loads(
        (REPO_ROOT / "model/deployments/local-deployment.json").read_text(encoding="utf-8")
    )
    active_ids = {
        target.get("active_model_id")
        for target in deployment.get("targets", {}).values()
        if isinstance(target, dict)
    }
    models = [model for model in registry.get("models", []) if model.get("model_id") in active_ids]
    if len(models) != 1:
        raise ValueError(f"expected one shared active runtime model, found {len(models)}")
    model = models[0]
    artifact = model.get("artifact", {})
    android_export = model.get("exports", {}).get("android_tflite", {})
    allowed = {
        str(artifact.get("path")): str(artifact.get("sha256")),
        str(android_export.get("path")): str(android_export.get("sha256")),
    }
    if any(path in {"", "None"} or re.fullmatch(r"[0-9a-f]{64}", digest) is None for path, digest in allowed.items()):
        raise ValueError("active runtime registry contains an invalid path or SHA-256")
    return allowed


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    with path.open(encoding="utf-8") as handle:
        for lineno, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise AssertionError(f"{path}:{lineno}: invalid json: {exc}") from exc
    return rows


def run(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=REPO_ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def add_result(results: list[dict], name: str, ok: bool, details: str = "") -> None:
    results.append({"name": name, "ok": ok, "details": details})


def validate_schema(rows: list[dict], results: list[dict]) -> None:
    errors = []
    normalized = Counter()
    for index, row in enumerate(rows, 1):
        missing = REQUIRED_FIELDS - set(row)
        if missing:
            errors.append(f"row {index} missing {sorted(missing)}")
        for field, allowed in [
            ("path_type", PATH_TYPES),
            ("scope", SCOPES),
            ("current_state", CURRENT_STATES),
            ("class", CLASSES),
            ("import_decision", IMPORT_DECISIONS),
            ("git_tracking_policy", TRACKING_POLICY),
            ("git_tracking_actual", TRACKING_ACTUAL),
            ("review_status", REVIEW_STATUS),
        ]:
            if row.get(field) not in allowed:
                errors.append(f"row {index} invalid {field}={row.get(field)}")
        key = (row.get("scope"), row.get("normalized_path"))
        normalized[key] += 1
        path = Path(str(row.get("source_path", "")))
        if row.get("exists") and not (path.exists() or path.is_symlink()):
            errors.append(f"row {index} path missing: {path}")
        if any(marker in json.dumps(row, ensure_ascii=False) for marker in BAD_MARKERS):
            errors.append(f"row {index} contains forbidden marker")
        if row.get("current_state") == "CURRENT" and row.get("class") not in CURRENT_CLASSES:
            errors.append(f"row {index} CURRENT with non-current class {row.get('class')}")
        if row.get("class") in {"SUPERSEDED", "PLAN_HISTORY"} and row.get("current_state") != "NON_CURRENT":
            errors.append(f"row {index} {row.get('class')} must be NON_CURRENT")
    duplicates = [key for key, count in normalized.items() if count > 1]
    if duplicates:
        errors.append(f"duplicate normalized paths: {duplicates[:10]}")
    row_by_path = {row.get("normalized_path"): row for row in rows if row.get("scope") == "repo"}
    expected_submission_classes = {
        "templates/[서식1] 2026 한이음 드림업 개발보고서 양식.docx": "EXTERNAL_REFERENCE",
        "templates/[서식2] 2026년_제작설계서_일반(개인정보 기재x).pptx": "EXTERNAL_REFERENCE",
        "templates/개발보고서 양식.docx": "SUBMISSION_CANDIDATE",
        "templates/제작설계서_일반.pptx": "SUBMISSION_CANDIDATE",
        "templates/README.md": "IMPLEMENTATION_DOC",
        "docs/submission/form_materials/README.md": "SUBMISSION_DERIVED",
        "docs/submission/final/개발보고서 양식.docx": "SUBMISSION_CANDIDATE",
        "docs/submission/final/제작설계서_일반.pptx": "SUBMISSION_CANDIDATE",
    }
    for path, expected_class in expected_submission_classes.items():
        row = row_by_path.get(path)
        if row is None:
            errors.append(f"submission classification row missing: {path}")
        elif row.get("class") != expected_class:
            errors.append(f"{path} class={row.get('class')} expected={expected_class}")
    add_result(results, "schema_enum_path_duplicate_checks", not errors, "; ".join(errors[:20]))


def validate_canonical(rows: list[dict], results: list[dict]) -> None:
    counts = defaultdict(list)
    for row in rows:
        group = row.get("canonical_group")
        if group in CANONICAL_GROUPS and row.get("current_state") == "CURRENT":
            counts[group].append(row["normalized_path"])
    errors = []
    for group in sorted(CANONICAL_GROUPS):
        if len(counts[group]) != 1:
            errors.append(f"{group} has {len(counts[group])}: {counts[group]}")
    add_result(results, "canonical_group_exactly_one_current", not errors, "; ".join(errors))


def validate_git_forbidden(results: list[dict]) -> None:
    tracked = run(["git", "ls-files"]).stdout.splitlines()
    status = run(["git", "status", "--porcelain", "--untracked-files=all"]).stdout.splitlines()
    offenders = []
    try:
        runtime_allowlist = active_runtime_artifact_allowlist()
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        add_result(results, "forbidden_tracked_staged_untracked_files", False, str(exc))
        return
    for item in tracked + [line[3:] for line in status if len(line) > 3]:
        if ALLOWLIST_RE.search(item):
            continue
        expected_hash = runtime_allowlist.get(item)
        if expected_hash is not None:
            artifact = REPO_ROOT / item
            if artifact.is_file() and sha256_file(artifact) == expected_hash:
                continue
        if FORBIDDEN_RE.search(item):
            offenders.append(item)
    add_result(results, "forbidden_tracked_staged_untracked_files", not offenders, "\n".join(offenders[:50]))


def validate_download_snapshot(results: list[dict]) -> None:
    pre = load_jsonl(PRE_SNAPSHOT)
    post = load_jsonl(POST_SNAPSHOT)
    pre_map = {row["normalized_path"]: row for row in pre}
    post_map = {row["normalized_path"]: row for row in post}
    errors = []
    for key, pre_row in pre_map.items():
        post_row = post_map.get(key)
        if not post_row:
            errors.append(f"missing post snapshot row {key}")
            continue
        for field in ("exists", "size_bytes", "sha256"):
            if pre_row.get(field) != post_row.get(field):
                errors.append(f"{key} changed {field}: {pre_row.get(field)} -> {post_row.get(field)}")
    add_result(results, "downloads_pre_post_snapshot_unchanged", not errors, "; ".join(errors[:20]))


def validate_current_index(results: list[dict]) -> None:
    readme = (REPO_ROOT / "docs" / "README.md").read_text(encoding="utf-8")
    required = [
        "implementation_audit_20260710.md",
        "document_inventory_20260707.md",
        "legacy/superseded",
        "Android native",
        "Web/PWA",
        "SOURCE_BASELINE",
    ]
    missing = [item for item in required if item not in readme]
    add_result(results, "docs_readme_current_non_current_separation", not missing, ", ".join(missing))


def validate_inventory_mentions(results: list[dict]) -> None:
    required_files = [
        PROJECT_MANIFEST,
        DOWNLOADS_MANIFEST,
        INVENTORY_DIR / "classification_audit_summary_20260708.md",
        INVENTORY_DIR / "decision_needed_20260708.md",
        INVENTORY_DIR / "do_not_import_20260708.md",
    ]
    missing = [path.as_posix() for path in required_files if not path.exists() or path.stat().st_size == 0]
    add_result(results, "inventory_outputs_exist", not missing, "\n".join(missing))


def write_results(results: list[dict]) -> int:
    ok = all(item["ok"] for item in results)
    payload = {
        "ok": ok,
        "validated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "results": results,
    }
    RESULT_JSON.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    rows = ["# Classification validation - refreshed 2026-07-13", "", f"Overall: {'PASS' if ok else 'FAIL'}", ""]
    rows.append("| check | result | details |")
    rows.append("| --- | --- | --- |")
    for item in results:
        details = str(item["details"]).replace("\n", "<br>")
        rows.append(f"| {item['name']} | {'PASS' if item['ok'] else 'FAIL'} | {details} |")
    RESULT_MD.write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    return 0 if ok else 1


def main() -> int:
    results: list[dict] = []
    validate_inventory_mentions(results)
    project_rows = load_jsonl(PROJECT_MANIFEST)
    download_rows = load_jsonl(DOWNLOADS_MANIFEST)
    all_rows = project_rows + download_rows
    validate_schema(all_rows, results)
    validate_canonical(project_rows, results)
    validate_git_forbidden(results)
    validate_download_snapshot(results)
    validate_current_index(results)
    return write_results(results)


if __name__ == "__main__":
    raise SystemExit(main())
