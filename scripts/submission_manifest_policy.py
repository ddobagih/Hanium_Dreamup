from __future__ import annotations

import base64
import hashlib
import importlib.metadata
import json
import os
import platform
import re
import shutil
import stat
import subprocess
import sys
from datetime import datetime, timedelta
from pathlib import Path, PurePosixPath
from typing import Iterable


SUBMISSION_TOOLCHAIN_LOCK_PATH = "configs/submission_toolchain_lock_20260713.json"
SUBMISSION_LIBRARY_BUNDLE_FORMAT = "walksafe.semantic-record-bundle.v1"
SUBMISSION_INSTALLER_REQUIREMENTS_PATH = "configs/submission_installer_requirements.lock"
SUBMISSION_EXACT8_REQUIREMENTS_PATH = "configs/submission_exact8_requirements.lock"
SUBMISSION_INSTALLER_VERSION = "26.1.1"
SUBMISSION_INSTALL_FLAGS = ["--require-hashes", "--no-deps", "--no-compile"]
SUBMISSION_REPRODUCTION_RUNBOOK_PATH = "docs/submission/CLEAN_ROOM_REPRODUCTION_20260713.md"
SUBMISSION_RUNNER_PATH = "scripts/run_walksafe_submission_python_20260714.py"
ISOLATED_PYTHON_BOOTSTRAP_PATH = "scripts/run_walksafe_isolated_python_20260713.py"
SUBMISSION_PYTHON_TRUST_SOURCES = frozenset(
    {
        SUBMISSION_RUNNER_PATH,
        ISOLATED_PYTHON_BOOTSTRAP_PATH,
        SUBMISSION_INSTALLER_REQUIREMENTS_PATH,
        SUBMISSION_EXACT8_REQUIREMENTS_PATH,
    }
)
REVIEWER_IDENTIFIER = re.compile(r"^[A-Za-z0-9._@-]{1,64}$")

DESIGN_DOCUMENT_NAMES = frozenset(
    {
        "01_요구사항_정의서.docx",
        "02_유스케이스_정의서.docx",
        "03_요구사항_기능_추적표.docx",
        "04_서비스_구성도_및_흐름도.docx",
        "05_화면설계서_UIUX_정의서.docx",
        "06_엔티티관계도_테이블정의서.docx",
        "07_기능처리도_알고리즘명세서.docx",
        "08_프로그램목록_핵심소스코드_개발환경.docx",
    }
)
DESIGN_OUTPUT_NAMES = DESIGN_DOCUMENT_NAMES | {"BUILD_MANIFEST.json", "README.md"}
DESIGN_GENERATED_PATHS = frozenset(
    {f"docs/submission/design_documents/{name}" for name in DESIGN_DOCUMENT_NAMES}
    | {"docs/submission/design_documents/BUILD_MANIFEST.json"}
)
DESIGN_BUILD_INPUT_PATHS = frozenset(
    {
        SUBMISSION_TOOLCHAIN_LOCK_PATH,
        "docs/submission/form_materials/assets/BUILD_MANIFEST.json",
        "scripts/build_design_documents_20260710.py",
        "scripts/build_submission_assets_20260710.py",
        "scripts/submission_build_io.py",
        "scripts/submission_manifest_policy.py",
    }
) | SUBMISSION_PYTHON_TRUST_SOURCES
GENERATED_ASSET_NAMES = frozenset(
    {
        "adoption_roadmap.png",
        "evidence_results.png",
        "model_quality_gate.png",
        "navigation_state_flow.png",
        "problem_solution_map.png",
        "report_csv_flow.png",
        "reports_erd.png",
        "risk_processing_flow.png",
        "risk_timeline.png",
        "scope_change_map.png",
        "system_architecture.png",
        "ui_screen_storyboard.png",
        "ui_state_map.png",
        "use_case_swimlane.png",
        "value_flow.png",
    }
)
GENERATED_ASSET_SIDECAR_NAMES = frozenset({"evidence_results.semantic.json"})
GENERATED_ASSET_ARTIFACT_NAMES = GENERATED_ASSET_NAMES | GENERATED_ASSET_SIDECAR_NAMES
ASSET_GENERATED_PATHS = frozenset(
    {
        f"docs/submission/form_materials/assets/{name}"
        for name in GENERATED_ASSET_ARTIFACT_NAMES
    }
    | {"docs/submission/form_materials/assets/BUILD_MANIFEST.json"}
)
ASSET_DIRECTORY_FILES = GENERATED_ASSET_ARTIFACT_NAMES | {
    "README.md",
    "BUILD_MANIFEST.json",
    "admin_desktop.png",
    "web_main_desktop.png",
    "web_main_mobile.png",
}
ASSET_REPOSITORY_INPUT_PATHS = frozenset(
    {
        SUBMISSION_TOOLCHAIN_LOCK_PATH,
        "docs/submission/form_materials/09_제출_사실_기준.json",
        "docs/submission/form_materials/assets/admin_desktop.png",
        "docs/submission/form_materials/assets/web_main_desktop.png",
        "docs/submission/form_materials/assets/web_main_mobile.png",
        "scripts/build_submission_assets_20260710.py",
        "scripts/submission_build_io.py",
        "scripts/submission_manifest_policy.py",
    }
) | SUBMISSION_PYTHON_TRUST_SOURCES
ASSET_SYSTEM_INPUT_PATHS = frozenset(
    {
        "/usr/share/fonts/truetype/nanum/NanumSquareB.ttf",
        "/usr/share/fonts/truetype/nanum/NanumSquareR.ttf",
    }
)
ASSET_ARTIFACT_PATHS = frozenset(
    f"docs/submission/form_materials/assets/{name}"
    for name in GENERATED_ASSET_ARTIFACT_NAMES
)
DESIGN_ASSET_PATHS = frozenset(
    f"docs/submission/form_materials/assets/{name}"
    for name in (
        "admin_desktop.png",
        "evidence_results.png",
        "navigation_state_flow.png",
        "problem_solution_map.png",
        "report_csv_flow.png",
        "reports_erd.png",
        "risk_processing_flow.png",
        "risk_timeline.png",
        "scope_change_map.png",
        "system_architecture.png",
        "ui_screen_storyboard.png",
        "ui_state_map.png",
        "use_case_swimlane.png",
        "web_main_desktop.png",
        "web_main_mobile.png",
    )
)
FINAL_OUTPUT_NAMES = frozenset(
    {
        "ASSISTANT_VISUAL_PRIVACY_REVIEW.json",
        "BUILD_MANIFEST.json",
        "README.md",
        "개발보고서 양식.docx",
        "제작설계서_일반.pptx",
    }
)

FINAL_SECTION_PATHS = {
    "official_templates": frozenset(
        {
            "templates/[서식1] 2026 한이음 드림업 개발보고서 양식.docx",
            "templates/[서식2] 2026년_제작설계서_일반(개인정보 기재x).pptx",
        }
    ),
    "critical_sources": frozenset(
        {
            "docs/submission/form_materials/04_개발보고서_본문원고.md",
            "docs/submission/form_materials/05_제작설계서_34장_원고.md",
            "docs/submission/deliverables/요구사항_정의서.md",
            "docs/submission/deliverables/유스케이스_정의서.md",
            "docs/submission/drafts/요구사항_추적표.md",
        }
    ),
    "build_sources": frozenset(
        {
            SUBMISSION_REPRODUCTION_RUNBOOK_PATH,
            SUBMISSION_TOOLCHAIN_LOCK_PATH,
            "scripts/audit_submission_visual_privacy_20260711.py",
            "scripts/build_submission_assets_20260710.py",
            "scripts/build_design_documents_20260710.py",
            "scripts/build_submission_forms_20260710.py",
            "scripts/promote_submission_final_20260713.py",
            SUBMISSION_RUNNER_PATH,
            ISOLATED_PYTHON_BOOTSTRAP_PATH,
            SUBMISSION_INSTALLER_REQUIREMENTS_PATH,
            SUBMISSION_EXACT8_REQUIREMENTS_PATH,
            "scripts/submission_build_io.py",
            "scripts/submission_manifest_policy.py",
            "scripts/validate_submission_materials_20260710.py",
            "scripts/validate_submission_forms_20260710.py",
        }
    ),
    "artifacts": frozenset(
        {
            "docs/submission/final/개발보고서 양식.docx",
            "docs/submission/final/제작설계서_일반.pptx",
        }
    ),
    "supporting_files": frozenset(
        {
            "docs/submission/final/ASSISTANT_VISUAL_PRIVACY_REVIEW.json",
            "docs/submission/final/README.md",
        }
    ),
}

FORM_TEMPLATE_FILES = frozenset(
    {
        "README.md",
        "[서식1] 2026 한이음 드림업 개발보고서 양식.docx",
        "[서식2] 2026년_제작설계서_일반(개인정보 기재x).pptx",
        "개발보고서 양식.docx",
        "제작설계서_일반.pptx",
        "SUBMISSION_BUILD_MANIFEST.json",
    }
)
FORM_MATERIAL_PATHS = frozenset(
    f"docs/submission/form_materials/{name}"
    for name in (
        "00_작성기준_및_가정.md",
        "01_주장_근거_검증_매트릭스.md",
        "02_기능_진척도_산정표.md",
        "03_수행일정_계획대비실적.md",
        "04_개발보고서_본문원고.md",
        "05_제작설계서_34장_원고.md",
        "06_외부근거_출처표.md",
        "07_화면_시각자료_목록.md",
        "08_도식_정의_및_검증.md",
        "09_제출_사실_기준.json",
    )
)
FORM_ASSET_PATHS = frozenset(
    f"docs/submission/form_materials/assets/{name}"
    for name in (
        "admin_desktop.png",
        "adoption_roadmap.png",
        "evidence_results.png",
        "model_quality_gate.png",
        "navigation_state_flow.png",
        "problem_solution_map.png",
        "report_csv_flow.png",
        "reports_erd.png",
        "risk_processing_flow.png",
        "risk_timeline.png",
        "scope_change_map.png",
        "system_architecture.png",
        "ui_state_map.png",
        "use_case_swimlane.png",
        "value_flow.png",
        "web_main_desktop.png",
        "web_main_mobile.png",
    )
) | {"docs/submission/form_materials/assets/evidence_results.semantic.json"}
FORM_BUILD_INPUT_PATHS = (
    FINAL_SECTION_PATHS["official_templates"]
    | FORM_MATERIAL_PATHS
    | FORM_ASSET_PATHS
    | {
        SUBMISSION_TOOLCHAIN_LOCK_PATH,
        "docs/submission/form_materials/assets/BUILD_MANIFEST.json",
        "templates/README.md",
        "scripts/build_submission_forms_20260710.py",
        "scripts/submission_build_io.py",
        "scripts/submission_manifest_policy.py",
    }
    | SUBMISSION_PYTHON_TRUST_SOURCES
)
FORM_ARTIFACT_PATHS = frozenset(
    {"templates/개발보고서 양식.docx", "templates/제작설계서_일반.pptx"}
)
FORM_GENERATED_PATHS = FORM_ARTIFACT_PATHS | {"templates/SUBMISSION_BUILD_MANIFEST.json"}
FINAL_GENERATED_PATHS = frozenset(
    f"docs/submission/final/{name}" for name in FINAL_OUTPUT_NAMES
)
PROMOTION_TRANSIENT_PATHS = frozenset(
    {
        "docs/submission/.final-promotion-staging",
        "docs/submission/.final-promotion-backup",
    }
)
ALL_SUBMISSION_GENERATED_PATHS = frozenset(
    ASSET_GENERATED_PATHS
    | DESIGN_GENERATED_PATHS
    | FORM_GENERATED_PATHS
    | FINAL_GENERATED_PATHS
    | PROMOTION_TRANSIENT_PATHS
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def file_record(repository_root: Path, path: Path) -> dict[str, object]:
    resolved_root = repository_root.resolve()
    resolved = path.resolve()
    if path.is_symlink() or not path.is_file():
        raise FileNotFoundError(f"manifest input must be a regular file: {path}")
    try:
        relative = resolved.relative_to(resolved_root).as_posix()
    except ValueError as exc:
        raise ValueError(f"manifest input is outside repository: {path}") from exc
    return {"path": relative, "sha256": sha256(path), "bytes": path.stat().st_size}


def record_bundle_sha256(records: Iterable[dict[str, object]]) -> str:
    lines = []
    seen_paths: set[str] = set()
    for record in records:
        path = record.get("path")
        digest = record.get("sha256")
        if not isinstance(path, str) or re.fullmatch(r"[0-9a-f]{64}", str(digest)) is None:
            raise ValueError(f"invalid manifest bundle record: {record!r}")
        if path in seen_paths:
            raise ValueError(f"duplicate manifest bundle path: {path}")
        seen_paths.add(path)
        lines.append(f"{digest}  {path}\n")
    return hashlib.sha256("".join(sorted(lines)).encode("utf-8")).hexdigest()


def source_revision(
    repository_root: Path,
    excluded_generated_paths: Iterable[str] = (),
) -> dict[str, object]:
    root = repository_root.resolve()
    excluded = sorted(set(excluded_generated_paths))
    for relative in excluded:
        relative_path = Path(relative)
        if (
            relative in {"", "."}
            or relative_path.is_absolute()
            or relative_path.as_posix() != relative
            or any(part in {"", ".", ".."} for part in relative_path.parts)
        ):
            raise ValueError(f"generated output exclusion must be a normalized relative path: {relative}")
        candidate = (root / relative).resolve()
        try:
            candidate.relative_to(root)
        except ValueError as exc:
            raise ValueError(f"generated output exclusion is outside repository: {relative}") from exc
    main_module = sys.modules.get("__main__")
    runner_commit = getattr(main_module, "_walksafe_submission_source_commit", None)
    runner_root = getattr(main_module, "_walksafe_submission_repo_root", None)
    runner_generated = getattr(main_module, "_walksafe_submission_generated_paths", None)
    environment_commit = os.environ.get("WALKSAFE_SUBMISSION_SOURCE_COMMIT")
    if (
        environment_commit is not None
        and runner_commit is None
        and runner_root is None
        and runner_generated is None
    ):
        raise ValueError("submission runner source verification marker is missing")
    if any(value is not None for value in (runner_commit, runner_root, runner_generated)):
        expected_generated = tuple(sorted(ALL_SUBMISSION_GENERATED_PATHS))
        if (
            not isinstance(runner_commit, str)
            or re.fullmatch(r"[0-9a-f]{40}", runner_commit) is None
            or environment_commit != runner_commit
            or runner_root != str(root)
            or runner_generated != expected_generated
            or tuple(excluded) != expected_generated
        ):
            raise ValueError("submission runner source verification marker is invalid")
        return {
            "source_commit": runner_commit,
            "source_dirty": False,
            "source_dirty_excluded_generated_paths": excluded,
        }
    commit = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip().lower()
    status_command = [
        "git",
        "-C",
        str(root),
        "status",
        "--porcelain=v1",
        "--untracked-files=all",
        "--",
        ".",
        *(f":(top,exclude,literal){relative}" for relative in excluded),
    ]
    status = subprocess.run(
        status_command,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    if re.fullmatch(r"[0-9a-f]{40}", commit) is None:
        raise ValueError("submission source commit must be a full Git SHA")
    return {
        "source_commit": commit,
        "source_dirty": bool(status.strip()),
        "source_dirty_excluded_generated_paths": excluded,
    }


def require_clean_source_revision(
    repository_root: Path,
    excluded_generated_paths: Iterable[str] = (),
) -> dict[str, object]:
    revision = source_revision(repository_root, excluded_generated_paths)
    if revision["source_dirty"] is not False:
        raise ValueError("submission source must be clean outside declared generated outputs")
    return revision


def build_tool_provenance(distributions: Iterable[str]) -> dict[str, object]:
    libraries = []
    for name in sorted(set(distributions), key=str.casefold):
        libraries.append({"distribution": name, "version": importlib.metadata.version(name)})
    return {
        "python": {
            "implementation": platform.python_implementation(),
            "version": platform.python_version(),
        },
        "platform": {"system": platform.system(), "machine": platform.machine()},
        "libraries": libraries,
    }


def require_review_identity(reviewer: object, reviewed_at: object) -> None:
    if not isinstance(reviewer, str) or REVIEWER_IDENTIFIER.fullmatch(reviewer) is None:
        raise ValueError("reviewer must be a 1..64 character identifier string")
    if not isinstance(reviewed_at, str):
        raise ValueError("reviewed_at must be a UTC ISO-8601 string")
    try:
        parsed = datetime.fromisoformat(reviewed_at)
    except ValueError as exc:
        raise ValueError("reviewed_at must be a UTC ISO-8601 string") from exc
    canonical = parsed.isoformat()
    accepted = {canonical, canonical.removesuffix("+00:00") + "Z"}
    if parsed.tzinfo is None or parsed.utcoffset() != timedelta(0) or reviewed_at not in accepted:
        raise ValueError("reviewed_at must be a canonical UTC ISO-8601 string")


def _matches_locked_regular_file(
    path: Path,
    expected_hash: object,
    expected_bytes: object,
) -> bool:
    if (
        not path.is_absolute()
        or path.resolve() != path
        or path.is_symlink()
        or not path.is_file()
        or re.fullmatch(r"[0-9a-f]{64}", str(expected_hash)) is None
        or isinstance(expected_bytes, bool)
        or not isinstance(expected_bytes, int)
        or expected_bytes < 1
    ):
        return False
    try:
        return path.stat().st_size == expected_bytes and sha256(path) == expected_hash
    except OSError:
        return False


def _runtime_chain_paths(name: str, invocation: Path) -> tuple[Path, ...]:
    if name == "libreoffice":
        return invocation.with_name("oosplash"), invocation.with_name("soffice.bin")
    return ()


def _locked_runtime_chain(
    name: str,
    invocation: Path,
    raw: object,
) -> tuple[tuple[Path, object, object], ...] | None:
    if not isinstance(raw, list) or any(
        not isinstance(item, dict) or set(item) != {"resolved_path", "sha256", "bytes"}
        for item in raw
    ):
        return None
    records = tuple(
        (Path(str(item["resolved_path"])), item["sha256"], item["bytes"])
        for item in raw
    )
    if tuple(path for path, _digest, _bytes in records) != _runtime_chain_paths(
        name, invocation
    ):
        return None
    return records


def _locked_runtime_chain_matches(
    records: tuple[tuple[Path, object, object], ...],
) -> bool:
    return all(
        _matches_locked_regular_file(path, digest, byte_count)
        for path, digest, byte_count in records
    )


def run_attested_submission_tool(
    attestation: dict[str, object],
    name: str,
    arguments: Iterable[str],
    **run_kwargs: object,
) -> subprocess.CompletedProcess[str]:
    verified = attestation.get("verified")
    tools = verified.get("tools") if isinstance(verified, dict) else None
    matches = [
        item
        for item in tools
        if isinstance(item, dict) and item.get("name") == name
    ] if isinstance(tools, list) else []
    if len(matches) != 1:
        raise ValueError(f"attested submission tool is missing or duplicated: {name}")
    record = matches[0]
    executable = Path(str(record.get("resolved_path")))
    expected_hash = record.get("sha256")
    expected_bytes = record.get("bytes")
    runtime_chain = _locked_runtime_chain(name, executable, record.get("runtime_chain"))
    if (
        runtime_chain is None
        or not _matches_locked_regular_file(executable, expected_hash, expected_bytes)
        or not _locked_runtime_chain_matches(runtime_chain)
    ):
        raise ValueError(f"attested submission tool changed before execution: {name}")
    try:
        completed = subprocess.run(
            [str(executable), *arguments],
            **run_kwargs,
        )
    finally:
        if (
            not _matches_locked_regular_file(executable, expected_hash, expected_bytes)
            or not _locked_runtime_chain_matches(runtime_chain)
        ):
            raise ValueError(f"attested submission tool changed during execution: {name}")
    return completed


def _canonical_distribution_path(raw: str) -> str:
    parts = raw.split("/")
    seen_name = False
    if (
        not raw
        or "\\" in raw
        or PurePosixPath(raw).is_absolute()
        or PurePosixPath(raw).as_posix() != raw
        or any(part in {"", "."} for part in parts)
        or any(ord(character) < 32 or ord(character) == 127 for character in raw)
    ):
        raise ValueError("submission distribution file path is not canonical")
    for part in parts:
        if part == "..":
            if seen_name:
                raise ValueError("submission distribution file path is not canonical")
        else:
            seen_name = True
    if not seen_name:
        raise ValueError("submission distribution file path is not canonical")
    return raw


def _stable_distribution_bytes(path: Path, context: str) -> bytes:
    candidate = path.absolute()
    if candidate.resolve() != candidate or candidate.is_symlink():
        raise ValueError(f"{context} must be a real file")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(candidate, flags)
        with os.fdopen(descriptor, "rb") as source:
            before = os.fstat(source.fileno())
            payload = source.read()
            after = os.fstat(source.fileno())
        current = os.stat(candidate, follow_symlinks=False)
    except OSError as exc:
        raise ValueError(f"{context} cannot be read safely") from exc
    identities = {
        (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
        for item in (before, after, current)
    }
    if not stat.S_ISREG(current.st_mode) or len(identities) != 1:
        raise ValueError(f"{context} changed while it was read")
    return payload


def _semantic_distribution_bundle(
    distribution: importlib.metadata.Distribution,
    name: str,
    prefix: Path,
    installed_claims: dict[Path, str],
) -> tuple[int, str]:
    package_files = distribution.files
    if package_files is None:
        raise ValueError(f"submission distribution has no installed-file inventory: {name}")
    distribution_root = Path(os.path.abspath(distribution.locate_file("")))
    claims: list[dict[str, object]] = []
    seen_relatives: set[str] = set()
    for package_file in sorted(package_files, key=lambda item: item.as_posix()):
        relative = _canonical_distribution_path(package_file.as_posix())
        if relative in seen_relatives:
            raise ValueError("submission distribution RECORD contains a duplicate path")
        seen_relatives.add(relative)
        installed = Path(os.path.abspath(distribution.locate_file(package_file)))
        try:
            installed.relative_to(prefix)
        except ValueError as exc:
            raise ValueError("submission distribution escaped its virtualenv") from exc
        hash_mode = package_file.hash.mode if package_file.hash is not None else ""
        hash_value = package_file.hash.value if package_file.hash is not None else ""
        if installed.suffix.lower() in {".pyc", ".pyo"}:
            if installed.exists() or hash_mode or package_file.size is not None:
                raise ValueError(
                    f"submission distribution contains executable bytecode: {name}:{relative}"
                )
            claims.append(
                {
                    "relative": relative,
                    "present": False,
                    "hash_mode": "",
                    "hash_value": "",
                    "recorded_size": None,
                }
            )
            continue
        previous_claim = installed_claims.get(installed)
        if previous_claim is not None:
            raise ValueError(
                "submission virtualenv path is claimed by multiple distributions: "
                f"{previous_claim}, {name}"
            )
        installed_claims[installed] = name
        payload = _stable_distribution_bytes(
            installed,
            f"submission distribution file {name}:{relative}",
        )
        digest = hashlib.sha256(payload).digest()
        if package_file.size is not None and package_file.size != len(payload):
            raise ValueError("submission file size differs from distribution RECORD")
        if not hash_mode:
            if not relative.endswith(".dist-info/RECORD"):
                raise ValueError("submission file is not hashed by distribution RECORD")
        elif hash_mode != "sha256":
            raise ValueError("submission distribution RECORD uses a non-SHA-256 hash")
        elif base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii") != hash_value:
            raise ValueError("submission file differs from distribution RECORD")
        external = not installed.is_relative_to(distribution_root)
        launcher_shebang = b"#!" + os.fsencode(prefix / "bin/python") + b"\n"
        normalized = payload
        if external and installed.parent == prefix / "bin":
            if not payload.startswith(launcher_shebang):
                raise ValueError(
                    "submission virtualenv bin claim lacks the exact Python launcher shebang"
                )
            normalized = (
                b"#!/__walksafe_venv__/bin/python\n" + payload[len(launcher_shebang) :]
            )
        claims.append(
            {
                "relative": relative,
                "present": True,
                "payload": normalized,
                "hash_mode": hash_mode,
                "hash_value": hash_value,
                "recorded_size": package_file.size,
                "external": external,
            }
        )

    record_claims = [
        claim
        for claim in claims
        if not claim["hash_mode"] and str(claim["relative"]).endswith(".dist-info/RECORD")
    ]
    if len(record_claims) != 1:
        raise ValueError("submission distribution has no unique unhashed RECORD file")
    record_relative = str(record_claims[0]["relative"])
    requested_relative = f"{record_relative.removesuffix('/RECORD')}/REQUESTED"
    semantic_rows: list[str] = []
    for claim in claims:
        relative = str(claim["relative"])
        if relative == requested_relative:
            if claim.get("payload") != b"" or claim.get("hash_mode") != "sha256" or claim.get(
                "recorded_size"
            ) != 0:
                raise ValueError("submission installer REQUESTED marker is invalid")
            continue
        hash_mode = str(claim["hash_mode"])
        hash_value = str(claim["hash_value"])
        recorded_size = claim["recorded_size"]
        if claim.get("external"):
            normalized = claim["payload"]
            assert isinstance(normalized, bytes)
            hash_value = base64.urlsafe_b64encode(hashlib.sha256(normalized).digest()).rstrip(
                b"="
            ).decode("ascii")
            recorded_size = len(normalized)
        semantic_rows.append(
            f"claim\0{relative}\0{hash_mode}\0{hash_value}\0"
            f"{'' if recorded_size is None else recorded_size}\n"
        )
    semantic_record = "".join(sorted(semantic_rows)).encode("utf-8")
    closure: list[tuple[str, int, str]] = []
    for claim in claims:
        if not claim["present"]:
            continue
        relative = str(claim["relative"])
        if relative == requested_relative:
            continue
        payload = semantic_record if relative == record_relative else claim["payload"]
        assert isinstance(payload, bytes)
        closure.append((relative, len(payload), hashlib.sha256(payload).hexdigest()))
    canonical = "".join(
        f"{digest}  {size}  {relative}\n" for relative, size, digest in closure
    ).encode("utf-8")
    return len(closure), hashlib.sha256(canonical).hexdigest()


def _require_python_installation_lock(repository_root: Path, raw: object) -> None:
    if not isinstance(raw, dict) or set(raw) != {
        "installer_lock",
        "exact8_lock",
        "pip_version",
        "install_flags",
    }:
        raise ValueError("submission Python installation lock has an invalid shape")
    if (
        raw.get("pip_version") != SUBMISSION_INSTALLER_VERSION
        or raw.get("install_flags") != SUBMISSION_INSTALL_FLAGS
    ):
        raise ValueError("submission Python installation recipe differs from the contract")
    expected_paths = {
        "installer_lock": SUBMISSION_INSTALLER_REQUIREMENTS_PATH,
        "exact8_lock": SUBMISSION_EXACT8_REQUIREMENTS_PATH,
    }
    for key, expected_relative in expected_paths.items():
        record = raw.get(key)
        if not isinstance(record, dict) or set(record) != {"path", "sha256", "bytes"}:
            raise ValueError("submission Python requirements lock has an invalid shape")
        expected_sha256 = record.get("sha256")
        expected_bytes = record.get("bytes")
        if (
            record.get("path") != expected_relative
            or not isinstance(expected_sha256, str)
            or re.fullmatch(r"[0-9a-f]{64}", expected_sha256) is None
            or isinstance(expected_bytes, bool)
            or not isinstance(expected_bytes, int)
            or expected_bytes < 1
        ):
            raise ValueError("submission Python requirements lock record is invalid")
        requirements = _stable_distribution_bytes(
            repository_root / expected_relative,
            f"submission Python requirements lock {expected_relative}",
        )
        if (
            len(requirements) != expected_bytes
            or hashlib.sha256(requirements).hexdigest() != expected_sha256
        ):
            raise ValueError(
                f"submission Python requirements lock differs from toolchain lock: {expected_relative}"
            )


def submission_toolchain_attestation(
    repository_root: Path,
    lock_path: Path | None = None,
) -> dict[str, object]:
    """Verify the pinned submission toolchain and return its exact attestation."""
    path = lock_path or repository_root / SUBMISSION_TOOLCHAIN_LOCK_PATH
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"submission toolchain lock must be a regular file: {path}")
    def reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate submission toolchain lock key: {key}")
            result[key] = value
        return result

    try:
        payload = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=reject_duplicate_keys,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"invalid submission toolchain lock: {exc}") from exc
    if not isinstance(payload, dict) or payload.get("schema_version") != "walksafe.submission-toolchain.v4":
        raise ValueError("unexpected submission toolchain lock schema")
    if set(payload) != {
        "schema_version",
        "library_bundle_format",
        "python_installation",
        "python",
        "libraries",
        "tools",
        "fonts",
    }:
        raise ValueError("submission toolchain lock fields differ from the exact contract")
    if payload.get("library_bundle_format") != SUBMISSION_LIBRARY_BUNDLE_FORMAT:
        raise ValueError("submission library bundle format is unsupported")
    _require_python_installation_lock(repository_root, payload.get("python_installation"))

    expected_python = payload.get("python")
    python_executable = Path(sys.executable).resolve()
    actual_python = {
        "implementation": platform.python_implementation(),
        "version": platform.python_version(),
        "platform": {"system": platform.system(), "machine": platform.machine()},
        "executable": {
            "resolved_path": str(python_executable),
            "sha256": sha256(python_executable),
            "bytes": python_executable.stat().st_size,
        },
    }
    if expected_python != actual_python:
        raise ValueError(
            f"submission Python differs from lock: actual={actual_python!r}, expected={expected_python!r}"
        )

    libraries = payload.get("libraries")
    if not isinstance(libraries, list) or any(
        not isinstance(item, dict)
        or set(item) != {"distribution", "version", "file_count", "file_bundle_sha256"}
        for item in libraries
    ):
        raise ValueError("submission library lock has an invalid shape")
    expected_library_names = {
        "Pillow",
        "python-docx",
        "python-pptx",
        "lxml",
        "opencv-python",
        "numpy",
        "typing-extensions",
        "XlsxWriter",
    }
    names = [str(item["distribution"]) for item in libraries]
    if len(names) != len(set(names)) or set(names) != expected_library_names:
        raise ValueError("submission library lock must contain the exact pipeline distributions")
    actual_libraries = []
    installed_claims: dict[Path, str] = {}
    prefix = Path(sys.prefix).absolute()
    for name in names:
        distribution = importlib.metadata.distribution(name)
        file_count, file_bundle_sha256 = _semantic_distribution_bundle(
            distribution,
            name,
            prefix,
            installed_claims,
        )
        actual_libraries.append(
            {
                "distribution": name,
                "version": distribution.version,
                "file_count": file_count,
                "file_bundle_sha256": file_bundle_sha256,
            }
        )
    if actual_libraries != libraries:
        raise ValueError(
            f"submission libraries differ from lock: actual={actual_libraries!r}, expected={libraries!r}"
        )

    tools = payload.get("tools")
    if not isinstance(tools, list) or len(tools) != 3 or {
        item.get("name") for item in tools if isinstance(item, dict)
    } != {
        "libreoffice",
        "pdfinfo",
        "pdftoppm",
    }:
        raise ValueError("submission tool lock must contain libreoffice, pdfinfo, and pdftoppm")
    for tool in tools:
        if not isinstance(tool, dict) or set(tool) != {
            "name",
            "command",
            "resolved_path",
            "bytes",
            "version_args",
            "version_output",
            "sha256",
            "runtime_chain",
        }:
            raise ValueError("submission tool lock entry has an invalid shape")
        command = tool["command"]
        version_args = tool["version_args"]
        if not isinstance(command, str) or not isinstance(version_args, list) or any(
            not isinstance(argument, str) for argument in version_args
        ):
            raise ValueError("submission tool command/version arguments are invalid")
        executable = shutil.which(command)
        if executable is None:
            raise ValueError(f"submission tool is unavailable: {command}")
        resolved = Path(executable).resolve()
        runtime_chain = _locked_runtime_chain(
            str(tool["name"]), resolved, tool["runtime_chain"]
        )
        if (
            str(resolved) != tool["resolved_path"]
            or runtime_chain is None
            or not _matches_locked_regular_file(resolved, tool["sha256"], tool["bytes"])
            or not _locked_runtime_chain_matches(runtime_chain)
        ):
            raise ValueError(f"submission tool execution chain differs from lock: {command}")
        try:
            completed = subprocess.run(
                [str(resolved), *version_args],
                check=True,
                capture_output=True,
                text=True,
            )
        finally:
            if (
                not _matches_locked_regular_file(resolved, tool["sha256"], tool["bytes"])
                or not _locked_runtime_chain_matches(runtime_chain)
            ):
                raise ValueError(
                    f"submission tool execution chain changed during attestation: {command}"
                )
        version_output = "\n".join(
            line.strip()
            for line in (completed.stdout + "\n" + completed.stderr).splitlines()
            if line.strip()
        )
        if version_output != tool["version_output"]:
            raise ValueError(f"submission tool version differs from lock: {command}")

    fonts = payload.get("fonts")
    if not isinstance(fonts, list) or len(fonts) != 2:
        raise ValueError("submission font lock must contain the exact two fonts")
    font_paths: set[str] = set()
    for font in fonts:
        if not isinstance(font, dict) or set(font) != {"path", "sha256", "bytes"}:
            raise ValueError("submission font lock entry has an invalid shape")
        font_path = Path(str(font["path"]))
        if str(font_path) in font_paths or font_path.is_symlink() or not font_path.is_file():
            raise ValueError(f"submission font is missing, duplicate, or unsafe: {font_path}")
        font_paths.add(str(font_path))
        if sha256(font_path) != font["sha256"] or font_path.stat().st_size != font["bytes"]:
            raise ValueError(f"submission font differs from lock: {font_path}")

    return {
        "lock": {
            "path": path.relative_to(repository_root).as_posix(),
            "sha256": sha256(path),
            "bytes": path.stat().st_size,
            "schema_version": payload["schema_version"],
        },
        "verified": payload,
    }


def directory_file_names(directory: Path) -> set[str]:
    files, directories = directory_entries(directory)
    if directories:
        raise ValueError(f"output directory contains non-files: {sorted(directories)}")
    return files


def directory_entries(directory: Path) -> tuple[set[str], set[str]]:
    if not directory.is_dir():
        raise FileNotFoundError(f"missing output directory: {directory}")
    symlinks = sorted(path.name for path in directory.iterdir() if path.is_symlink())
    if symlinks:
        raise ValueError(f"output directory contains symlinks: {symlinks}")
    unsupported = sorted(
        path.name for path in directory.iterdir() if not path.is_file() and not path.is_dir()
    )
    if unsupported:
        raise ValueError(f"output directory contains unsupported entries: {unsupported}")
    return (
        {path.name for path in directory.iterdir() if path.is_file()},
        {path.name for path in directory.iterdir() if path.is_dir()},
    )


def require_exact_file_set(directory: Path, expected_names: Iterable[str]) -> None:
    expected = set(expected_names)
    actual = directory_file_names(directory)
    if actual != expected:
        raise ValueError(
            f"output file set mismatch for {directory}: "
            f"missing={sorted(expected - actual)}, unexpected={sorted(actual - expected)}"
        )


def require_exact_directory_entries(
    directory: Path,
    expected_files: Iterable[str],
    expected_directories: Iterable[str],
) -> None:
    actual_files, actual_directories = directory_entries(directory)
    expected_file_set = set(expected_files)
    expected_directory_set = set(expected_directories)
    if actual_files != expected_file_set or actual_directories != expected_directory_set:
        raise ValueError(
            f"directory entry set mismatch for {directory}: "
            f"missing_files={sorted(expected_file_set - actual_files)}, "
            f"unexpected_files={sorted(actual_files - expected_file_set)}, "
            f"missing_directories={sorted(expected_directory_set - actual_directories)}, "
            f"unexpected_directories={sorted(actual_directories - expected_directory_set)}"
        )


def require_no_unexpected_files(directory: Path, allowed_names: Iterable[str]) -> None:
    if not directory.exists():
        return
    allowed = set(allowed_names)
    actual = directory_file_names(directory)
    if not actual <= allowed:
        raise ValueError(f"unexpected output files in {directory}: {sorted(actual - allowed)}")


def require_exact_manifest_paths(
    section: str, entries: object, expected_paths: Iterable[str]
) -> list[dict[str, object]]:
    if not isinstance(entries, list):
        raise ValueError(f"manifest {section} must be a list")
    typed = [entry for entry in entries if isinstance(entry, dict)]
    paths = [entry.get("path") for entry in typed]
    expected = set(expected_paths)
    if len(typed) != len(entries) or len(paths) != len(set(paths)) or set(paths) != expected:
        raise ValueError(
            f"manifest {section} path set mismatch: "
            f"expected={sorted(expected)}, actual={sorted(str(path) for path in paths)}"
        )
    return typed
