from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess

import pytest

from scripts.check_web_runtime_trace_scope_20260713 import audit_trace_scope


def write_trace(trace: Path, entries: list[str]) -> None:
    trace.parent.mkdir(parents=True, exist_ok=True)
    trace.write_text(json.dumps({"version": 1, "files": entries}), encoding="utf-8")


def test_accepts_build_node_modules_and_package_manifest(tmp_path: Path) -> None:
    web = tmp_path / "apps/web"
    build = web / ".next"
    trace = build / "server/app/page.js.nft.json"
    build_file = build / "server/app/page.js"
    dependency = web / "node_modules/example/index.js"
    package = web / "package.json"
    for path in (build_file, dependency, package):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("fixture", encoding="utf-8")
    write_trace(
        trace,
        [
            os.path.relpath(build_file, trace.parent),
            os.path.relpath(dependency, trace.parent),
            os.path.relpath(package, trace.parent),
        ],
    )

    assert audit_trace_scope(web, build) == (1, 3)


def test_next_config_excludes_quality_only_python_files_from_runtime_trace() -> None:
    root = Path(__file__).resolve().parents[1]
    environment = {
        key: value
        for key, value in os.environ.items()
        if key not in {"WALKSAFE_ALLOWED_DEV_ORIGINS", "WALKSAFE_NEXT_DIST_DIR", "WALKSAFE_SOURCE_COMMIT"}
    }
    completed = subprocess.run(
        [
            "node",
            "--input-type=module",
            "--eval",
            "import config from './apps/web/next.config.mjs'; "
            "process.stdout.write(JSON.stringify(config.outputFileTracingExcludes['/*']));",
        ],
        cwd=root,
        env=environment,
        check=True,
        capture_output=True,
        text=True,
    )

    excludes = set(json.loads(completed.stdout))
    assert {
        "./quality-requirements.lock",
        "./quality-requirements.txt",
    } <= excludes


@pytest.mark.parametrize(
    "relative_path",
    ["tests/policy.test.ts", ".env.local", "logs/field.jsonl", "runtime-not-reviewed.txt"],
)
def test_rejects_project_source_secret_or_log_capture(tmp_path: Path, relative_path: str) -> None:
    web = tmp_path / "apps/web"
    build = web / ".next"
    trace = build / "server/app/page.js.nft.json"
    captured = web / relative_path
    captured.parent.mkdir(parents=True, exist_ok=True)
    captured.write_text("fixture", encoding="utf-8")
    write_trace(trace, [os.path.relpath(captured, trace.parent)])

    with pytest.raises(ValueError, match="unreviewed files"):
        audit_trace_scope(web, build)


def test_rejects_build_directory_outside_web_project(tmp_path: Path) -> None:
    web = tmp_path / "apps/web"
    with pytest.raises(ValueError, match="must stay inside"):
        audit_trace_scope(web, tmp_path / "other-build")


def test_rejects_sensitive_payload_copied_inside_build(tmp_path: Path) -> None:
    web = tmp_path / "apps/web"
    build = web / ".next"
    trace = build / "server/app/page.js.nft.json"
    copied_secret = build / "server/.env.local"
    copied_secret.parent.mkdir(parents=True, exist_ok=True)
    copied_secret.write_text("fixture", encoding="utf-8")
    write_trace(trace, [os.path.relpath(copied_secret, trace.parent)])

    with pytest.raises(ValueError, match="unreviewed files"):
        audit_trace_scope(web, build)


def test_rejects_missing_runtime_traces(tmp_path: Path) -> None:
    web = tmp_path / "apps/web"
    build = web / ".next"
    build.mkdir(parents=True)
    with pytest.raises(ValueError, match="no Next runtime trace"):
        audit_trace_scope(web, build)
