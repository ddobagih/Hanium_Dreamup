#!/usr/bin/env python3
"""Stage and atomically publish the reviewed FP-046/NPC seq72--76 transition.

The companion module prepares the canonical R029/R002 review subject and its
three-file review chain.  This module owns the one add-only publication
transaction after that review chain is present.  It deliberately stops before
seq77: the resulting FP-046 R002 start-gate contract is NOT_AUTHORIZED and
NOT_RUN, so this transaction does not start product work or claim any release
credit.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import importlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
from typing import Any, Mapping, Sequence

sys.dont_write_bytecode = True


def _load_module(name: str) -> Any:
    try:
        return importlib.import_module(name)
    except ModuleNotFoundError:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module(name)


review = _load_module("scripts.apply_walksafe_fp046_npc_r002_reopen_20260815")
catalogs = _load_module("scripts.generate_repository_catalogs")
continuation = _load_module("scripts.check_walksafe_project_continuation_v2_4")

BuildError = review.BuildError
require = review.require
bytes_sha256 = review.bytes_sha256
json_text = review.json_text

ROOT = Path(__file__).resolve().parents[1]
CHECKPOINT_REL = review.CHECKPOINT_REL
CATALOG_RELATIVES = tuple(Path(path) for path in catalogs.OUTPUT_PATHS)
MUTABLE_TARGETS = (CHECKPOINT_REL, *CATALOG_RELATIVES)
SOURCE_CONTROL_PATHS = (
    Path("scripts/build_walksafe_fp046_gap_backlog_r029_candidate_20260815.py"),
    Path("tests/test_build_walksafe_fp046_gap_backlog_r029_candidate_20260815.py"),
    Path("scripts/build_walksafe_fp046_gap_backlog_r029_20260815.py"),
    Path("tests/test_build_walksafe_fp046_gap_backlog_r029_20260815.py"),
    Path("scripts/apply_walksafe_fp046_npc_r002_reopen_20260815.py"),
    Path("tests/test_apply_walksafe_fp046_npc_r002_reopen_20260815.py"),
    Path("scripts/apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py"),
    Path("tests/test_apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py"),
)
TRANSACTION_STATUS = "READY_R011_CONTROL_R004_TRANSITION_REVIEW_BOUND"


def _control_successor_module() -> Any:
    """Load current control-successor definitions without publishing anything."""

    return _load_module(
        "scripts.build_walksafe_fp022_completion_seq70_71_review_20260814"
    )


def _expected_r011_control_cohort_paths() -> tuple[Path, ...]:
    module = _control_successor_module()
    paths = tuple(Path(path) for path in module.CONTROL_SUCCESSOR_R011_COHORT_PATHS)
    require(
        len(paths) == 23
        and len(set(paths)) == len(paths)
        and set(SOURCE_CONTROL_PATHS).issubset(paths),
        "R011 control cohort definition differs",
    )
    return paths


def _normalize_r011_control_cohort_paths(
    paths: Sequence[Path | str] | None,
) -> tuple[Path, ...]:
    expected = _expected_r011_control_cohort_paths()
    if paths is None:
        candidate = expected
    else:
        require(
            all(isinstance(path, (Path, str)) for path in paths),
            "R011 control cohort path type differs",
        )
        candidate = tuple(Path(path) for path in paths)
    require(
        candidate == expected,
        "R011 control cohort path order differs",
    )
    return candidate


def _cohort_from_r011_context(context: Any) -> tuple[dict[str, Any], ...]:
    cohort = context.current.control_code_cohort
    require(
        isinstance(cohort, tuple)
        and all(isinstance(row, dict) for row in cohort),
        "R011 control cohort is malformed",
    )
    paths = _normalize_r011_control_cohort_paths(
        [row.get("path") for row in cohort]
    )
    require(
        all(
            set(row) == {"path", "sha256", "byte_length"}
            and row["path"] == path.as_posix()
            and isinstance(row["sha256"], str)
            and isinstance(row["byte_length"], int)
            for row, path in zip(cohort, paths, strict=True)
        ),
        "R011 control cohort binding differs",
    )
    return tuple(deepcopy(row) for row in cohort)


def _prepared_r011_control_cohort(root: Path) -> tuple[dict[str, Any], ...]:
    """Read the live 23-path R011 cohort without claiming control approval."""

    module = _control_successor_module()
    return _cohort_from_r011_context(
        module.prepare_control_successor_r011_context(root)
    )


def _validated_r011_control_cohort(root: Path) -> tuple[dict[str, Any], ...]:
    """Require the independent R011 control review before publication."""

    module = _control_successor_module()
    return _cohort_from_r011_context(
        module.validated_control_successor_r011_context(root)
    )


def _r009_control_review_paths() -> tuple[Path, ...]:
    module = _control_successor_module()
    paths = tuple(Path(path) for path in module.CONTROL_SUCCESSOR_R009_PATHS)
    require(len(paths) == 3 and len(set(paths)) == 3, "R009 control review paths differ")
    return paths


def _r009_control_review_path_by_role() -> dict[str, Path]:
    module = _control_successor_module()
    paths = {
        "assignment": Path(module.CONTROL_SUCCESSOR_R009_ASSIGNMENT_REL),
        "review_result": Path(module.CONTROL_SUCCESSOR_R009_RESULT_REL),
        "independent_review": Path(module.CONTROL_SUCCESSOR_R009_INDEPENDENT_REL),
    }
    require(
        tuple(paths.values()) == _r009_control_review_paths(),
        "R009 control review role paths differ",
    )
    return paths


def _r010_control_review_paths() -> tuple[Path, ...]:
    module = _control_successor_module()
    paths = tuple(Path(path) for path in module.CONTROL_SUCCESSOR_R010_PATHS)
    require(len(paths) == 3 and len(set(paths)) == 3, "R010 control review paths differ")
    return paths


def _r010_control_review_path_by_role() -> dict[str, Path]:
    module = _control_successor_module()
    paths = {
        "assignment": Path(module.CONTROL_SUCCESSOR_R010_ASSIGNMENT_REL),
        "review_result": Path(module.CONTROL_SUCCESSOR_R010_RESULT_REL),
        "independent_review": Path(module.CONTROL_SUCCESSOR_R010_INDEPENDENT_REL),
    }
    require(
        tuple(paths.values()) == _r010_control_review_paths(),
        "R010 control review role paths differ",
    )
    return paths


def _r011_control_review_paths() -> tuple[Path, ...]:
    module = _control_successor_module()
    paths = tuple(Path(path) for path in module.CONTROL_SUCCESSOR_R011_PATHS)
    require(len(paths) == 3 and len(set(paths)) == 3, "R011 control review paths differ")
    return paths


def _r011_control_review_path_by_role() -> dict[str, Path]:
    module = _control_successor_module()
    paths = {
        "assignment": Path(module.CONTROL_SUCCESSOR_R011_ASSIGNMENT_REL),
        "review_result": Path(module.CONTROL_SUCCESSOR_R011_RESULT_REL),
        "independent_review": Path(module.CONTROL_SUCCESSOR_R011_INDEPENDENT_REL),
    }
    require(
        tuple(paths.values()) == _r011_control_review_paths(),
        "R011 control review role paths differ",
    )
    return paths


def _review_closure_paths() -> tuple[Path, ...]:
    paths = (
        *review.TRANSITION_R001_PATHS,
        *review.TRANSITION_R002_PATHS,
        *review.TRANSITION_R003_PATHS,
        *review.TRANSITION_R004_PATHS,
        *_r009_control_review_paths(),
        *_r010_control_review_paths(),
        *_r011_control_review_paths(),
    )
    require(
        len(paths) == len(set(paths)) == 21,
        "R001/R002/R003/R004/R009/R010/R011 review closure differs",
    )
    return paths


def _validated_root(root: Path) -> Path:
    root = Path(root)
    try:
        info = root.lstat()
    except OSError as exc:
        raise BuildError(f"transaction root cannot be inspected: {root}") from exc
    require(
        stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode),
        "transaction root must be a non-symlink directory",
    )
    return root.resolve(strict=True)


def _validate_relative(relative: Path, label: str) -> None:
    require(
        not relative.is_absolute()
        and bool(relative.parts)
        and all(part not in {"", ".", ".."} for part in relative.parts),
        f"unsafe {label} path: {relative}",
    )


def _walk_existing(root: Path, relative: Path, label: str) -> Path:
    _validate_relative(relative, label)
    cursor = root
    for part in relative.parts:
        cursor = cursor / part
        try:
            info = cursor.lstat()
        except OSError as exc:
            raise BuildError(f"required {label} is missing: {relative}") from exc
        require(not stat.S_ISLNK(info.st_mode), f"{label} uses a symlink: {relative}")
    require(
        stat.S_ISREG(info.st_mode) and info.st_nlink == 1,
        f"{label} must be a single-link regular file: {relative}",
    )
    return cursor


def _walk_regular_output(
    root: Path,
    relative: Path,
    label: str,
    *,
    missing_ok: bool = False,
) -> Path | None:
    """Resolve a rollback target without assuming its private stage link is gone."""

    _validate_relative(relative, label)
    cursor = root
    for index, part in enumerate(relative.parts):
        cursor = cursor / part
        try:
            info = cursor.lstat()
        except FileNotFoundError as exc:
            if missing_ok:
                return None
            raise BuildError(f"required {label} is missing: {relative}") from exc
        except OSError as exc:
            raise BuildError(f"required {label} is missing: {relative}") from exc
        require(not stat.S_ISLNK(info.st_mode), f"{label} uses a symlink: {relative}")
        if index < len(relative.parts) - 1:
            require(stat.S_ISDIR(info.st_mode), f"unsafe {label} parent: {relative}")
    require(stat.S_ISREG(info.st_mode), f"{label} must be a regular file: {relative}")
    return cursor


def _read_regular(root: Path, relative: Path, label: str) -> bytes:
    target = _walk_existing(root, relative, label)
    try:
        return target.read_bytes()
    except OSError as exc:
        raise BuildError(f"required {label} cannot be read: {relative}") from exc


def _single_link_signature(root: Path, relative: Path, label: str) -> tuple[int, ...]:
    target = _walk_existing(root, relative, label)
    try:
        info = target.stat(follow_symlinks=False)
    except OSError as exc:
        raise BuildError(f"required {label} cannot be statted: {relative}") from exc
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
        info.st_nlink,
        info.st_size,
        info.st_mtime_ns,
        info.st_ctime_ns,
    )


def _require_missing_target(root: Path, relative: Path) -> None:
    _validate_relative(relative, "output")
    cursor = root
    for part in relative.parent.parts:
        cursor = cursor / part
        try:
            info = cursor.lstat()
        except FileNotFoundError:
            return
        require(
            stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode),
            f"unsafe output parent: {relative}",
        )
    target = cursor / relative.name
    try:
        info = target.lstat()
    except FileNotFoundError:
        return
    raise BuildError(
        f"add-only output conflict ({'symlink' if stat.S_ISLNK(info.st_mode) else 'existing'}): {relative}"
    )


def _ensure_safe_parent(root: Path, relative: Path, created: list[Path]) -> Path:
    _validate_relative(relative, "output")
    cursor = root
    for part in relative.parent.parts:
        cursor = cursor / part
        try:
            info = cursor.lstat()
        except FileNotFoundError:
            try:
                cursor.mkdir()
            except FileExistsError:
                pass
            try:
                info = cursor.lstat()
            except OSError as exc:
                raise BuildError(f"output parent cannot be inspected: {relative}") from exc
            created.append(cursor)
        require(
            stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode),
            f"unsafe output parent: {relative}",
        )
    return cursor


def _binding(path: Path, raw: bytes) -> dict[str, Any]:
    return {
        "path": path.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
    }


def _parse_canonical(raw: bytes, label: str) -> dict[str, Any]:
    value = review.strict_json_bytes(raw, label)
    require(raw == json_text(value).encode("utf-8"), f"{label} is noncanonical")
    return value


def _transition_review_binding(
    overlay: Mapping[Path, bytes],
) -> dict[str, dict[str, Any]]:
    return {
        "assignment": _binding(
            review.TRANSITION_ASSIGNMENT_REL,
            overlay[review.TRANSITION_ASSIGNMENT_REL],
        ),
        "review_result": _binding(
            review.TRANSITION_RESULT_REL,
            overlay[review.TRANSITION_RESULT_REL],
        ),
        "independent_review": _binding(
            review.TRANSITION_INDEPENDENT_REL,
            overlay[review.TRANSITION_INDEPENDENT_REL],
        ),
    }


def _transition_review_overlay(root: Path) -> dict[Path, bytes]:
    return {
        path: _read_regular(root, path, "transition review")
        for path in (
            review.TRANSITION_ASSIGNMENT_REL,
            review.TRANSITION_RESULT_REL,
            review.TRANSITION_INDEPENDENT_REL,
        )
    }


def _historical_transition_r001_review_binding(
    overlay: Mapping[Path, bytes],
) -> dict[str, dict[str, Any]]:
    return {
        role: _binding(relative, overlay[relative])
        for role, relative in zip(
            ("assignment", "review_result", "independent_review"),
            review.TRANSITION_R001_PATHS,
            strict=True,
        )
    }


def _historical_transition_r001_review_overlay(root: Path) -> dict[Path, bytes]:
    return {
        path: _read_regular(root, path, "frozen transition R001 review")
        for path in review.TRANSITION_R001_PATHS
    }


def _historical_transition_r002_review_binding(
    overlay: Mapping[Path, bytes],
) -> dict[str, dict[str, Any]]:
    return {
        role: _binding(relative, overlay[relative])
        for role, relative in zip(
            ("assignment", "review_result", "independent_review"),
            review.TRANSITION_R002_PATHS,
            strict=True,
        )
    }


def _historical_transition_r002_review_overlay(root: Path) -> dict[Path, bytes]:
    return {
        path: _read_regular(root, path, "frozen transition R002 review")
        for path in review.TRANSITION_R002_PATHS
    }


def _predecessor_transition_review_binding(
    overlay: Mapping[Path, bytes],
) -> dict[str, dict[str, Any]]:
    return {
        role: _binding(relative, overlay[relative])
        for role, relative in zip(
            ("assignment", "review_result", "independent_review"),
            review.TRANSITION_R003_PATHS,
            strict=True,
        )
    }


def _predecessor_transition_review_overlay(root: Path) -> dict[Path, bytes]:
    return {
        path: _read_regular(root, path, "frozen transition R003 review")
        for path in review.TRANSITION_R003_PATHS
    }


def _r009_control_review_binding(
    overlay: Mapping[Path, bytes],
) -> dict[str, dict[str, Any]]:
    return {
        role: _binding(relative, overlay[relative])
        for role, relative in _r009_control_review_path_by_role().items()
    }


def _r009_control_review_overlay(root: Path) -> dict[Path, bytes]:
    return {
        path: _read_regular(root, path, "R009 control review")
        for path in _r009_control_review_paths()
    }


def _r010_control_review_binding(
    overlay: Mapping[Path, bytes],
) -> dict[str, dict[str, Any]]:
    return {
        role: _binding(relative, overlay[relative])
        for role, relative in _r010_control_review_path_by_role().items()
    }


def _r010_control_review_overlay(root: Path) -> dict[Path, bytes]:
    return {
        path: _read_regular(root, path, "R010 control review")
        for path in _r010_control_review_paths()
    }


def _r011_control_review_binding(
    overlay: Mapping[Path, bytes],
) -> dict[str, dict[str, Any]]:
    return {
        role: _binding(relative, overlay[relative])
        for role, relative in _r011_control_review_path_by_role().items()
    }


def _r011_control_review_overlay(root: Path) -> dict[Path, bytes]:
    return {
        path: _read_regular(root, path, "R011 control review")
        for path in _r011_control_review_paths()
    }


def _capture_frozen_r009_control_material(
    root: Path,
) -> tuple[dict[Path, bytes], dict[str, dict[str, Any]]]:
    """Capture the exact frozen R009 review without live-current reinterpretation."""

    expected_binding, _raw_by_path = review.load_frozen_control_successor_r009(root)
    overlay = _r009_control_review_overlay(root)
    binding = _r009_control_review_binding(overlay)
    require(binding == expected_binding, "frozen R009 control review binding differs")
    return overlay, binding


def _capture_frozen_r010_control_material(
    root: Path,
) -> tuple[dict[Path, bytes], dict[str, dict[str, Any]]]:
    """Capture the exact frozen R010 review without live-current reinterpretation."""

    expected_binding, _raw_by_path = review.load_frozen_control_successor_r010(root)
    overlay = _r010_control_review_overlay(root)
    binding = _r010_control_review_binding(overlay)
    require(binding == expected_binding, "frozen R010 control review binding differs")
    return overlay, binding


def _capture_r011_control_material(
    root: Path,
) -> tuple[
    tuple[dict[str, Any], ...],
    dict[Path, bytes],
    dict[str, dict[str, Any]],
]:
    """Capture one validated current R011 cohort/review snapshot."""

    module = _control_successor_module()
    context = module.validated_control_successor_r011_context(root)
    overlay = _r011_control_review_overlay(root)
    paths = _r011_control_review_path_by_role()
    assignment_raw = overlay[paths["assignment"]]
    result_raw = overlay[paths["review_result"]]
    independent_raw = overlay[paths["independent_review"]]
    assignment = module.strict_json_bytes(
        assignment_raw, paths["assignment"].as_posix()
    )
    result = module.strict_json_bytes(result_raw, paths["review_result"].as_posix())
    module.validate_control_successor_r011_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    require(
        independent_raw
        == module.build_control_successor_r011_independent_review(
            context,
            assignment,
            assignment_raw,
            result,
            result_raw,
        ).encode("utf-8"),
        "control successor R011 independent review differs",
    )
    return (
        _cohort_from_r011_context(context),
        overlay,
        _r011_control_review_binding(overlay),
    )


def _capture_transition_material(
    root: Path,
) -> tuple[
    dict[str, Any],
    dict[str, Any],
    dict[Path, bytes],
    dict[Path, bytes],
    dict[Path, bytes],
    dict[str, dict[str, Any]],
    dict[str, dict[str, Any]],
    dict[str, Any],
]:
    """Capture one coherent source/triad snapshot for the staged transaction."""

    package = review.build_transition_package(root)
    historical_overlay = {
        **_historical_transition_r001_review_overlay(root),
        **_historical_transition_r002_review_overlay(root),
    }
    historical_r001_binding = _historical_transition_r001_review_binding(
        historical_overlay
    )
    frozen_r001_binding, _frozen_r001_raw = review.load_frozen_transition_r001(root)
    require(
        historical_r001_binding == frozen_r001_binding,
        "historical transition R001 binding differs",
    )
    historical_r002_binding = _historical_transition_r002_review_binding(
        historical_overlay
    )
    frozen_r002_binding, _frozen_r002_raw = review.load_frozen_transition_r002(root)
    require(
        historical_r002_binding == frozen_r002_binding,
        "historical transition R002 binding differs",
    )
    predecessor_overlay = _predecessor_transition_review_overlay(root)
    predecessor_binding = _predecessor_transition_review_binding(
        predecessor_overlay
    )
    require(
        predecessor_binding == package["predecessor_transition_review_bindings"],
        "transition R003 predecessor binding differs",
    )
    overlay = _transition_review_overlay(root)
    assignment_raw = overlay[review.TRANSITION_ASSIGNMENT_REL]
    result_raw = overlay[review.TRANSITION_RESULT_REL]
    independent_raw = overlay[review.TRANSITION_INDEPENDENT_REL]
    assignment = review.strict_json_bytes(assignment_raw, "transition assignment")
    review.validate_transition_assignment_document(assignment, assignment_raw, package)
    result = review.strict_json_bytes(result_raw, "transition review result")
    review._validate_transition_result_document(
        result,
        result_raw,
        assignment,
        assignment_raw,
    )
    expected_independent = review.build_transition_independent_review(
        assignment,
        assignment_raw,
        result,
        result_raw,
    ).encode("utf-8")
    require(
        independent_raw == expected_independent,
        "transition independent review differs",
    )
    source = review._load_source(root)
    require(
        package.get("source_checkpoint")
        == review._binding(review.CHECKPOINT_REL, source["checkpoint_raw"]),
        "transition package source checkpoint changed while capturing review",
    )
    return (
        package,
        source,
        historical_overlay,
        predecessor_overlay,
        overlay,
        predecessor_binding,
        _transition_review_binding(overlay),
        deepcopy(package["approval_neutral_plan_core_binding"]),
    )


def _reseal(event: Mapping[str, Any]) -> dict[str, Any]:
    sealed = deepcopy(dict(event))
    sealed.pop("event_sha256", None)
    sealed["event_sha256"] = review.object_sha256(sealed)
    return sealed


def _bind_review_to_preflight(
    plan: Mapping[str, Any],
    review_binding: Mapping[str, Any],
    r009_control_review_binding: Mapping[str, Any] | None = None,
    r010_control_review_binding: Mapping[str, Any] | None = None,
    r011_control_review_binding: Mapping[str, Any] | None = None,
    *,
    predecessor_review_binding: Mapping[str, Any] | None = None,
    review_subject_binding: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Bind completed review material to seq72 and replay dependent hashes."""

    return review.bind_transition_review_evidence(
        plan,
        predecessor_transition_review_binding=predecessor_review_binding,
        transition_review_binding=review_binding,
        transition_review_subject_binding=review_subject_binding,
        r009_control_review_binding=r009_control_review_binding,
        r010_control_review_binding=r010_control_review_binding,
        r011_control_review_binding=r011_control_review_binding,
    )


def _approval_neutral_plan_core(plan: Mapping[str, Any]) -> dict[str, Any]:
    return review.approval_neutral_plan_core(plan)


def _overlay_hashes(
    root: Path,
    paths: Sequence[str],
    overlay: Mapping[Path, bytes],
) -> tuple[str, str]:
    normalized = sorted(set(paths))
    require(normalized == list(paths), "managed path list is not sorted and unique")
    path_digest = hashlib.sha256(("\n".join(normalized) + "\n").encode("utf-8")).hexdigest()
    content_digest = hashlib.sha256()
    for value in normalized:
        relative = Path(value)
        _validate_relative(relative, "managed")
        raw = overlay.get(relative)
        if raw is None:
            raw = _read_regular(root, relative, "managed source")
        content_digest.update(value.encode("utf-8"))
        content_digest.update(b"\0")
        content_digest.update(bytes_sha256(raw).encode("ascii"))
        content_digest.update(b"\n")
    return path_digest, content_digest.hexdigest()


def _update_canonical_bindings(
    checkpoint: dict[str, Any], snapshot: Mapping[str, Any]
) -> None:
    bindings = checkpoint.get("canonical_bindings")
    require(isinstance(bindings, list), "checkpoint canonical bindings are missing")
    by_role = {
        item.get("role"): item
        for item in bindings
        if isinstance(item, dict) and isinstance(item.get("role"), str)
    }
    require(len(by_role) == len(bindings), "checkpoint canonical binding role inventory differs")
    for role in ("IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"):
        source = snapshot.get(role)
        target = by_role.get(role)
        require(isinstance(source, dict) and isinstance(target, dict), f"canonical {role} binding is missing")
        for field in ("document_id", "path", "file_sha256"):
            target[field] = source[field]


def _update_current_handoff(checkpoint: dict[str, Any]) -> None:
    current = checkpoint.get("current_work")
    handoff = checkpoint.get("session_handoff")
    require(isinstance(current, dict) and isinstance(handoff, dict), "checkpoint handoff is missing")
    current.update(
        {
            "current_focus": "EPIC-03 FP-046/GAP-055 R002 READY; seq77 initial start gate NOT_AUTHORIZED/NOT_RUN",
            "epic_id": review.EPIC03,
            "gap_ids": [review.FP046_GAP_ID],
            "gap_ids_semantics": "FOCUS_GOAL_EXACT_SCOPE",
            "last_completed_work_summary": "EPIC-03/FP-046/NPC prior completion evidence archived by the seq72-76 canonical-change reopen",
            "next_action": "FP-046 R002 초기 시작 gate의 권한과 검사 기록을 별도 확인한다.",
            "scope_kind": "GOAL_FOCUS",
            "source_policy_ids": [review.FP046_POLICY_ID],
            "source_policy_ids_semantics": "FOCUS_GOAL_EXACT_SCOPE",
            "status": "READY",
            "status_scope": "GOAL_STATUS",
            "title": "동의 철회와 계정 삭제 재개 검증",
            "work_item_id": review.FP046_R002,
            "work_item_id_semantics": "FOCUS_GOAL_ID",
        }
    )
    handoff.update(
        {
            "current_epic": "EPIC-03 / FP-046/GAP-055 R002 READY",
            "last_updated_by_work_item": review.FP046_R002,
            "last_verification_status": "INTERNAL_CONTROL_TRANSITION_REVIEWED_SEQ77_NOT_AUTHORIZED_NOT_RUN",
            "next_single_action": current["next_action"],
        }
    )


def _future_managed_paths(
    checkpoint: Mapping[str, Any],
    output_paths: Sequence[Path],
    r011_control_cohort_paths: Sequence[Path],
    historical_transition_review_paths: Sequence[Path],
    predecessor_transition_review_paths: Sequence[Path],
    transition_review_paths: Sequence[Path],
    r009_control_review_paths: Sequence[Path],
    r010_control_review_paths: Sequence[Path],
    r011_control_review_paths: Sequence[Path],
) -> list[str]:
    snapshot = checkpoint.get("working_tree_snapshot")
    require(isinstance(snapshot, dict), "checkpoint working snapshot is missing")
    base = snapshot.get("managed_changed_paths")
    require(isinstance(base, list) and all(isinstance(value, str) for value in base), "checkpoint managed paths are missing")
    additions = {
        path.as_posix()
        for path in (
            *output_paths,
            *r011_control_cohort_paths,
            *historical_transition_review_paths,
            *predecessor_transition_review_paths,
            *transition_review_paths,
            *r009_control_review_paths,
            *r010_control_review_paths,
            *r011_control_review_paths,
        )
        if path != CHECKPOINT_REL
    }
    return sorted(set(base) | additions)


def _project_checkpoint(
    root: Path,
    source: Mapping[str, Any],
    plan: Mapping[str, Any],
    output_bytes: Mapping[Path, bytes],
    r011_control_cohort_paths: Sequence[Path],
    historical_transition_review_overlay: Mapping[Path, bytes],
    predecessor_transition_review_overlay: Mapping[Path, bytes],
    transition_review_overlay: Mapping[Path, bytes],
    r009_control_review_overlay: Mapping[Path, bytes],
    r010_control_review_overlay: Mapping[Path, bytes],
    r011_control_review_overlay: Mapping[Path, bytes],
) -> dict[str, Any]:
    checkpoint = deepcopy(source["checkpoint"])
    state = checkpoint.get("goal_execution")
    final = plan.get("final_state")
    events = plan.get("events")
    require(isinstance(state, dict) and isinstance(final, dict) and isinstance(events, list), "transition projection is malformed")
    require(len(events) == 5 and all(isinstance(event, dict) for event in events), "transition history projection differs")
    history = state.get("transition_history")
    require(isinstance(history, list) and len(history) == review.SOURCE_SEQUENCE, "source checkpoint is not seq71")
    _update_canonical_bindings(checkpoint, events[0]["canonical_binding_snapshot_after"])
    for field in (
        "status_by_goal",
        "completion_evidence_by_goal",
        "archived_completion_evidence_by_goal",
        "dynamic_goal_inventory",
        "materialized_child_goal_ids_by_parent",
        "ready_frontier_goal_ids",
        "focus_goal_id",
        "artifact_work_queue",
        "completion_boundary",
    ):
        state[field] = deepcopy(final[field])
    history.extend(deepcopy(events))
    state["transition_history_anchor_sha256"] = events[-1]["event_sha256"]
    state["validation_cutoff_at"] = events[-1]["occurred_at"]
    final_runtime = events[-1]["runtime_after"]
    require(isinstance(final_runtime, dict), "final transition runtime is missing")
    for field in (
        "focus_goal_id",
        "focus_goal_path",
        "focus_source",
        "focus_work_item_id",
        "ready_frontier_goal_ids",
        "blocked_goal_ids",
        "pending_questions",
        "open_question_count",
        "activation_status",
        "package_status",
    ):
        state[field] = deepcopy(final_runtime[field])
    state["goal_status"] = "READY"
    state["pending_reopen_goal_ids"] = []
    state["pending_producer_completion_goal_id"] = None
    goal_paths = list(continuation.expected_goal_paths(state))
    managed_goal_paths = sorted(set(continuation.V24_NATIVE_PATHS) | set(goal_paths))
    state["goal_document_paths"] = goal_paths
    state["goal_document_count"] = len(goal_paths)
    state["managed_goal_paths"] = managed_goal_paths
    state["managed_goal_path_count"] = len(managed_goal_paths)
    hash_overlay = {
        **output_bytes,
        **historical_transition_review_overlay,
        **predecessor_transition_review_overlay,
        **transition_review_overlay,
        **r009_control_review_overlay,
        **r010_control_review_overlay,
        **r011_control_review_overlay,
    }
    package_path_hash, package_content_hash = _overlay_hashes(
        root, managed_goal_paths, hash_overlay
    )
    state["path_set_sha256"] = package_path_hash
    state["content_set_sha256"] = package_content_hash
    _update_current_handoff(checkpoint)
    mutable_outputs = [path for path in output_bytes if path != CHECKPOINT_REL]
    managed_paths = _future_managed_paths(
        checkpoint,
        mutable_outputs,
        r011_control_cohort_paths,
        tuple(historical_transition_review_overlay),
        tuple(predecessor_transition_review_overlay),
        tuple(transition_review_overlay),
        tuple(r009_control_review_overlay),
        tuple(r010_control_review_overlay),
        tuple(r011_control_review_overlay),
    )
    snapshot = checkpoint["working_tree_snapshot"]
    snapshot["managed_changed_paths"] = managed_paths
    snapshot["managed_changed_path_count"] = len(managed_paths)
    path_hash, content_hash = _overlay_hashes(root, managed_paths, hash_overlay)
    snapshot["path_set_sha256"] = path_hash
    snapshot["content_set_sha256"] = content_hash
    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = managed_paths
    source_snapshot = handoff.get("source_commit_or_snapshot")
    require(isinstance(source_snapshot, dict), "checkpoint source snapshot is missing")
    source_snapshot["file_count"] = len(managed_paths)
    source_snapshot["path_set_sha256"] = path_hash
    source_snapshot["content_set_sha256"] = content_hash
    return checkpoint


def _catalog_outputs(
    root: Path,
    checkpoint: Mapping[str, Any],
    future_paths: Sequence[Path],
) -> dict[Path, bytes]:
    try:
        current_paths = catalogs.discover_source_paths(root)
        path_universe = tuple(
            sorted(set(current_paths) | {path.as_posix() for path in future_paths})
        )
        raw = catalogs.build_catalog_bytes(
            root,
            path_universe,
            checkpoint_override=checkpoint,
        )
    except (OSError, ValueError, TypeError, catalogs.CatalogError) as exc:
        raise BuildError(f"catalog refresh projection failed: {exc}") from exc
    result = {Path(path): value for path, value in raw.items()}
    require(set(result) == set(CATALOG_RELATIVES), "catalog output inventory differs")
    return result


def _source_binding_paths(
    r011_control_cohort_paths: Sequence[Path],
) -> tuple[Path, ...]:
    candidate = review.r029_candidate
    paths = [
        CHECKPOINT_REL,
        *review.R028_PATHS,
        review.FP046_R001_REL,
        review.NPC_R001_REL,
        review.FP022_R001_REL,
        review.EPIC03_REL,
        *candidate.R028_INPUT_PATHS,
        *candidate.CURRENT_SOURCE_PATHS,
        *review.R007_REVIEW_PINS,
        *_review_closure_paths(),
        *CATALOG_RELATIVES,
        *r011_control_cohort_paths,
    ]
    return tuple(sorted(set(paths)))


def _source_bindings(
    root: Path,
    r011_control_cohort_paths: Sequence[Path],
) -> list[dict[str, Any]]:
    return [
        _binding(path, _read_regular(root, path, "transaction source"))
        for path in _source_binding_paths(
            r011_control_cohort_paths,
        )
    ]


def _validate_control_cohort_source_bindings(
    cohort: Sequence[Mapping[str, Any]],
    source_bindings: Sequence[Mapping[str, Any]],
) -> None:
    by_path = {
        binding.get("path"): binding
        for binding in source_bindings
        if isinstance(binding, Mapping) and isinstance(binding.get("path"), str)
    }
    require(len(by_path) == len(source_bindings), "transaction source binding paths differ")
    for row in cohort:
        require(isinstance(row, Mapping), "R011 control cohort row is malformed")
        path = row.get("path")
        require(
            isinstance(path, str)
            and by_path.get(path)
            == {
                "path": path,
                "sha256": row.get("sha256"),
                "byte_length": row.get("byte_length"),
            },
            f"R011 control cohort source binding differs: {path}",
        )


def _validate_review_source_bindings(
    review_binding: Mapping[str, Mapping[str, Any]],
    source_bindings: Sequence[Mapping[str, Any]],
    label: str,
) -> None:
    by_path = {
        binding.get("path"): binding
        for binding in source_bindings
        if isinstance(binding, Mapping) and isinstance(binding.get("path"), str)
    }
    for binding in review_binding.values():
        path = binding.get("path")
        require(
            isinstance(path, str) and by_path.get(path) == binding,
            f"{label} source binding differs: {path}",
        )


def _projected_goal_nodes(
    root: Path,
    state: Mapping[str, Any],
    output_bytes: Mapping[Path, bytes],
) -> dict[str, dict[str, Any]]:
    goal_graph = _load_module("scripts.check_walksafe_goal_graph_v2_4")
    imported = goal_graph.frozen_goal.imported_goal_binding_map(dict(state))
    inventory = state.get("dynamic_goal_inventory")
    require(isinstance(inventory, Mapping), "projected dynamic Goal inventory is missing")
    relative_paths = {
        record.get("path")
        for record in (*imported.values(), *inventory.values())
        if isinstance(record, Mapping)
    }
    require(
        all(isinstance(relative, str) for relative in relative_paths),
        "projected Goal path inventory is malformed",
    )
    nodes: dict[str, dict[str, Any]] = {}
    for relative in sorted(relative_paths):
        relative_path = Path(relative)
        raw = output_bytes.get(relative_path)
        if raw is not None:
            node, _body = review._goal_parts(raw, f"projected Goal {relative}")
        else:
            path = continuation.resolve_repo_file(root, relative)
            require(
                path is not None,
                f"projected Goal path is missing or unsafe: {relative}",
            )
            try:
                node, _body = goal_graph.frozen_goal.parse_goal(path)
            except (OSError, ValueError) as exc:
                raise BuildError(f"projected Goal cannot be parsed: {relative}: {exc}") from exc
        goal_id = node.get("goal_id")
        require(
            isinstance(goal_id, str) and goal_id not in nodes,
            f"projected Goal identity differs: {relative}",
        )
        nodes[goal_id] = node
    return nodes


def _runtime_projection_replay(
    root: Path,
    checkpoint: Mapping[str, Any],
    plan: Mapping[str, Any],
    output_bytes: Mapping[Path, bytes],
) -> list[tuple[dict[str, Any], dict[str, Any]]]:
    state = checkpoint.get("goal_execution")
    final = plan.get("final_state")
    events = plan.get("events")
    require(
        isinstance(state, dict)
        and isinstance(final, Mapping)
        and isinstance(events, list)
        and len(events) == 5
        and all(isinstance(event, Mapping) for event in events),
        "projected runtime replay input differs",
    )
    statuses = deepcopy(final.get("status_by_goal"))
    require(isinstance(statuses, dict), "projected runtime status map is missing")
    for event in reversed(events):
        subject_goal_id = event.get("subject_goal_id")
        from_status = event.get("from_status")
        require(
            isinstance(subject_goal_id, str) and isinstance(from_status, str),
            "projected runtime source status differs",
        )
        statuses[subject_goal_id] = from_status
        materialized_goal_id = event.get("materialized_goal_id")
        if isinstance(materialized_goal_id, str):
            statuses.pop(materialized_goal_id, None)

    goal_graph = _load_module("scripts.check_walksafe_goal_graph_v2_4")
    nodes = _projected_goal_nodes(root, state, output_bytes)
    bindings = goal_graph.frozen_goal.canonical_binding_map(dict(checkpoint))
    register_binding = bindings.get("ARTIFACT_REGISTER")
    require(
        isinstance(register_binding, dict),
        "projected runtime ARTIFACT_REGISTER binding is missing",
    )
    register_path = continuation.resolve_repo_file(root, register_binding.get("path"))
    require(
        register_path is not None,
        "projected runtime ARTIFACT_REGISTER path is missing or unsafe",
    )
    register = continuation.load_json(register_path)
    replay: list[tuple[dict[str, Any], dict[str, Any]]] = []
    for event in events:
        status_changes = event.get("status_changes")
        runtime = event.get("runtime_after")
        require(
            isinstance(status_changes, Mapping) and isinstance(runtime, Mapping),
            "projected runtime event is malformed",
        )
        statuses.update(status_changes)
        queue_errors, queue = goal_graph.derive_v24_artifact_work_queue_from_register(
            register_binding,
            register,
            nodes,
            statuses,
        )
        require(
            not queue_errors,
            "projected artifact queue replay failed: " + "; ".join(queue_errors),
        )
        boundary_errors, boundary = goal_graph.frozen_goal.derive_completion_boundary(
            nodes,
            statuses,
            runtime.get("ready_frontier_goal_ids"),
            state["blockers_by_goal"],
            queue,
            package_status=state["package_status"],
        )
        require(
            not boundary_errors,
            "projected completion boundary replay failed: "
            + "; ".join(boundary_errors),
        )
        replay.append((queue, boundary))
    return replay


def _validate_projected_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    plan: Mapping[str, Any],
    output_bytes: Mapping[Path, bytes],
    r011_control_cohort_paths: Sequence[Path],
    historical_transition_review_overlay: Mapping[Path, bytes],
    predecessor_transition_review_overlay: Mapping[Path, bytes],
    transition_review_overlay: Mapping[Path, bytes],
    r009_control_review_overlay: Mapping[Path, bytes],
    r010_control_review_overlay: Mapping[Path, bytes],
    r011_control_review_overlay: Mapping[Path, bytes],
) -> None:
    state = checkpoint.get("goal_execution")
    require(isinstance(state, dict), "projected checkpoint goal execution is missing")
    history = state.get("transition_history")
    require(isinstance(history, list) and len(history) == 76, "projected checkpoint history length differs")
    require(history[-5:] == plan["events"], "projected checkpoint transition tail differs")
    require(
        state.get("status_by_goal") == plan["final_state"]["status_by_goal"]
        and state.get("completion_evidence_by_goal") == plan["final_state"]["completion_evidence_by_goal"]
        and state.get("archived_completion_evidence_by_goal") == plan["final_state"]["archived_completion_evidence_by_goal"]
        and state.get("dynamic_goal_inventory") == plan["final_state"]["dynamic_goal_inventory"]
        and state.get("materialized_child_goal_ids_by_parent") == plan["final_state"]["materialized_child_goal_ids_by_parent"],
        "projected checkpoint final state differs",
    )
    final_runtime = plan["events"][-1].get("runtime_after")
    require(isinstance(final_runtime, dict), "projected final runtime is missing")
    require(
        state.get("focus_goal_id") == plan["final_state"]["focus_goal_id"]
        and state.get("ready_frontier_goal_ids")
        == plan["final_state"]["ready_frontier_goal_ids"]
        and all(
            state.get(field) == final_runtime.get(field)
            for field in (
                "focus_goal_path",
                "focus_source",
                "focus_work_item_id",
                "blocked_goal_ids",
                "pending_questions",
                "open_question_count",
                "activation_status",
                "package_status",
            )
        ),
        "projected focus or ready frontier differs",
    )
    bindings = checkpoint.get("canonical_bindings")
    canonical = plan["events"][0].get("canonical_binding_snapshot_after")
    require(
        isinstance(bindings, list) and isinstance(canonical, dict),
        "projected canonical bindings are missing",
    )
    by_role = {
        row.get("role"): row
        for row in bindings
        if isinstance(row, dict) and isinstance(row.get("role"), str)
    }
    require(
        all(
            isinstance(canonical.get(role), dict)
            and isinstance(by_role.get(role), dict)
            and {
                field: by_role[role].get(field)
                for field in ("role", "document_id", "path", "file_sha256")
            }
            == canonical[role]
            for role in ("IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG")
        ),
        "projected canonical R029 bindings differ",
    )
    require(
        state["status_by_goal"].get(review.EPIC03) == "READY"
        and state["status_by_goal"].get(review.FP046_R001) == "SUPERSEDED"
        and state["status_by_goal"].get(review.FP046_R002) == "READY"
        and state["status_by_goal"].get(review.NPC_R001) == "SUPERSEDED"
        and state["status_by_goal"].get(review.NPC_R002) == "PLANNED",
        "projected reopen status differs",
    )
    runtime_replay = _runtime_projection_replay(
        root,
        checkpoint,
        plan,
        output_bytes,
    )
    for event, (queue, boundary) in zip(history[-5:], runtime_replay, strict=True):
        runtime = event.get("runtime_after")
        sequence = event.get("sequence")
        require(isinstance(runtime, dict), f"projected seq{sequence} runtime is missing")
        require(
            runtime.get("artifact_work_queue_sha256")
            == continuation.canonical_json_sha256(queue),
            f"projected seq{sequence} artifact queue hash differs from independent replay",
        )
        require(
            runtime.get("completion_boundary_sha256")
            == continuation.canonical_json_sha256(boundary),
            f"projected seq{sequence} completion boundary hash differs from independent replay",
        )
    expected_queue, expected_boundary = runtime_replay[-1]
    require(
        plan["final_state"].get("artifact_work_queue") == expected_queue
        and state.get("artifact_work_queue") == expected_queue,
        "projected artifact work queue differs from independent replay",
    )
    require(
        plan["final_state"].get("completion_boundary") == expected_boundary
        and state.get("completion_boundary") == expected_boundary,
        "projected completion boundary differs from independent replay",
    )
    for goal_id in (review.EPIC03, review.FP046_R001, review.NPC_R001):
        require(
            goal_id not in state["completion_evidence_by_goal"]
            and goal_id in state["archived_completion_evidence_by_goal"],
            f"projected completion archive differs: {goal_id}",
        )
    expected_goal_paths = list(continuation.expected_goal_paths(state))
    require(
        state.get("goal_document_paths") == expected_goal_paths
        and state.get("goal_document_count") == len(expected_goal_paths)
        and state.get("managed_goal_paths")
        == sorted(set(continuation.V24_NATIVE_PATHS) | set(expected_goal_paths)),
        "projected Goal path inventory differs",
    )
    require(
        state.get("managed_goal_path_count") == len(state["managed_goal_paths"])
        and isinstance(state.get("path_set_sha256"), str)
        and isinstance(state.get("content_set_sha256"), str),
        "projected Goal path count or digest differs",
    )
    managed = checkpoint.get("working_tree_snapshot", {}).get("managed_changed_paths")
    require(isinstance(managed, list), "projected working path inventory is missing")
    require(
        set(path.as_posix() for path in r011_control_cohort_paths).issubset(managed),
        "projected working paths omit the R011 control cohort",
    )
    review_paths = (
        *historical_transition_review_overlay,
        *predecessor_transition_review_overlay,
        *transition_review_overlay,
        *r009_control_review_overlay,
        *r010_control_review_overlay,
        *r011_control_review_overlay,
    )
    require(
        len(review_paths) == len(set(review_paths)) == 21
        and set(path.as_posix() for path in review_paths).issubset(managed),
        "projected working paths omit the R001/R002/R003/R004/R009/R010/R011 review closure",
    )
    path_hash, content_hash = _overlay_hashes(
        root,
        managed,
        {
            **output_bytes,
            **historical_transition_review_overlay,
            **predecessor_transition_review_overlay,
            **transition_review_overlay,
            **r009_control_review_overlay,
            **r010_control_review_overlay,
            **r011_control_review_overlay,
        },
    )
    require(
        checkpoint["working_tree_snapshot"].get("path_set_sha256") == path_hash
        and checkpoint["working_tree_snapshot"].get("content_set_sha256") == content_hash,
        "projected working snapshot hashes differ",
    )
    handoff = checkpoint.get("session_handoff")
    mirror = handoff.get("source_commit_or_snapshot") if isinstance(handoff, dict) else None
    require(
        isinstance(handoff, dict)
        and handoff.get("changed_files") == managed
        and isinstance(mirror, dict)
        and mirror.get("file_count") == len(managed)
        and mirror.get("path_set_sha256") == path_hash
        and mirror.get("content_set_sha256") == content_hash,
        "projected handoff snapshot mirror differs",
    )
    expected_outputs = build_staged_outputs(root, plan)
    require(
        all(output_bytes.get(path) == raw for path, raw in expected_outputs.items()),
        "projected authorization, R029, or R002 output differs",
    )


def build_staged_outputs(
    root: Path,
    preflight: Mapping[str, Any],
) -> dict[Path, bytes]:
    """Build the eight add-only subjects in memory, without any review I/O."""

    root = _validated_root(root)
    documents = preflight.get("documents")
    require(isinstance(documents, Mapping), "preflight documents are missing")
    canonical_outputs, _evidence = review.r029_bridge.build_outputs_and_source_evidence(root)
    outputs: dict[Path, bytes] = {
        **{path: text.encode("utf-8") for path, text in canonical_outputs.items()},
        **{
            Path(path): text.encode("utf-8")
            for path, text in documents.items()
            if isinstance(path, str) and isinstance(text, str)
        },
        review.AUTHORIZATION_REL: json_text(
            review._authorization_document()
        ).encode("utf-8"),
        review.INITIAL_START_GATE_CONTRACT_REL: json_text(
            review._initial_start_gate_contract(preflight)
        ).encode("utf-8"),
    }
    expected = {
        *review.r029_bridge.CANONICAL_OUTPUT_PATHS,
        review.FP046_R002_REL,
        review.NPC_R002_REL,
        review.AUTHORIZATION_REL,
        review.INITIAL_START_GATE_CONTRACT_REL,
    }
    require(
        set(outputs) == expected,
        "transaction add-only output inventory differs",
    )
    return outputs


def _catalog_overlay(
    catalog_overlay: Mapping[Path | str, bytes] | None,
) -> dict[Path, bytes] | None:
    if catalog_overlay is None:
        return None
    result = {Path(path): raw for path, raw in catalog_overlay.items()}
    require(
        set(result) == set(CATALOG_RELATIVES)
        and all(isinstance(raw, bytes) for raw in result.values()),
        "catalog overlay inventory differs",
    )
    return result


def _review_overlay(
    transition_review_overlay: Mapping[Path | str, bytes] | None,
    plan: Mapping[str, Any],
) -> dict[Path, bytes]:
    events = plan.get("events")
    require(
        isinstance(events, list) and events and isinstance(events[0], dict),
        "transition review overlay preflight differs",
    )
    binding = events[0].get("transition_review_binding")
    if transition_review_overlay is None:
        require(
            binding is None,
            "transition review overlay is required for a bound seq72 plan",
        )
        return {}
    result = {Path(path): raw for path, raw in transition_review_overlay.items()}
    expected = {
        review.TRANSITION_ASSIGNMENT_REL,
        review.TRANSITION_RESULT_REL,
        review.TRANSITION_INDEPENDENT_REL,
    }
    require(
        set(result) == expected and all(isinstance(raw, bytes) for raw in result.values()),
        "transition review overlay inventory differs",
    )
    require(
        binding == _transition_review_binding(result),
        "transition review overlay binding differs",
    )
    return result


def _historical_review_overlay(
    transition_review_overlay: Mapping[Path | str, bytes] | None,
) -> dict[Path, bytes]:
    if transition_review_overlay is None:
        return {}
    result = {Path(path): raw for path, raw in transition_review_overlay.items()}
    require(
        set(result)
        == set((*review.TRANSITION_R001_PATHS, *review.TRANSITION_R002_PATHS))
        and all(isinstance(raw, bytes) for raw in result.values()),
        "historical transition R001/R002 overlay inventory differs",
    )
    return result


def _predecessor_review_overlay(
    transition_review_overlay: Mapping[Path | str, bytes] | None,
    plan: Mapping[str, Any],
) -> dict[Path, bytes]:
    events = plan.get("events")
    require(
        isinstance(events, list) and events and isinstance(events[0], dict),
        "transition R003 predecessor overlay preflight differs",
    )
    binding = events[0].get("predecessor_transition_review_binding")
    if transition_review_overlay is None:
        require(
            binding is None,
            "transition R003 predecessor overlay is required for a bound seq72 plan",
        )
        return {}
    result = {Path(path): raw for path, raw in transition_review_overlay.items()}
    require(
        set(result) == set(review.TRANSITION_R003_PATHS)
        and all(isinstance(raw, bytes) for raw in result.values()),
        "transition R003 predecessor overlay inventory differs",
    )
    require(
        binding == _predecessor_transition_review_binding(result),
        "transition R003 predecessor overlay binding differs",
    )
    return result


def _r009_review_overlay(
    r009_control_review_overlay: Mapping[Path | str, bytes] | None,
    plan: Mapping[str, Any],
) -> dict[Path, bytes]:
    events = plan.get("events")
    require(
        isinstance(events, list) and events and isinstance(events[0], dict),
        "R009 control review overlay preflight differs",
    )
    binding = events[0].get("r009_control_review_binding")
    if r009_control_review_overlay is None:
        require(
            binding is None,
            "R009 control review overlay is required for a bound seq72 plan",
        )
        return {}
    result = {
        Path(path): raw for path, raw in r009_control_review_overlay.items()
    }
    require(
        set(result) == set(_r009_control_review_paths())
        and all(isinstance(raw, bytes) for raw in result.values()),
        "R009 control review overlay inventory differs",
    )
    require(
        binding == _r009_control_review_binding(result),
        "R009 control review overlay binding differs",
    )
    return result


def _r010_review_overlay(
    r010_control_review_overlay: Mapping[Path | str, bytes] | None,
    plan: Mapping[str, Any],
) -> dict[Path, bytes]:
    events = plan.get("events")
    require(
        isinstance(events, list) and events and isinstance(events[0], dict),
        "R010 control review overlay preflight differs",
    )
    binding = events[0].get("r010_control_review_binding")
    if r010_control_review_overlay is None:
        require(
            binding is None,
            "R010 control review overlay is required for a bound seq72 plan",
        )
        return {}
    result = {
        Path(path): raw for path, raw in r010_control_review_overlay.items()
    }
    require(
        set(result) == set(_r010_control_review_paths())
        and all(isinstance(raw, bytes) for raw in result.values()),
        "R010 control review overlay inventory differs",
    )
    require(
        binding == _r010_control_review_binding(result),
        "R010 control review overlay binding differs",
    )
    return result


def _r011_review_overlay(
    r011_control_review_overlay: Mapping[Path | str, bytes] | None,
    plan: Mapping[str, Any],
) -> dict[Path, bytes]:
    events = plan.get("events")
    require(
        isinstance(events, list) and events and isinstance(events[0], dict),
        "R011 control review overlay preflight differs",
    )
    binding = events[0].get("r011_control_review_binding")
    if r011_control_review_overlay is None:
        require(
            binding is None,
            "R011 control review overlay is required for a bound seq72 plan",
        )
        return {}
    result = {
        Path(path): raw for path, raw in r011_control_review_overlay.items()
    }
    require(
        set(result) == set(_r011_control_review_paths())
        and all(isinstance(raw, bytes) for raw in result.values()),
        "R011 control review overlay inventory differs",
    )
    require(
        binding == _r011_control_review_binding(result),
        "R011 control review overlay binding differs",
    )
    return result


def project_transaction(
    root: Path,
    *,
    preflight: Mapping[str, Any],
    staged_outputs: Mapping[Path, bytes],
    source_checkpoint: Mapping[str, Any] | None = None,
    catalog_overlay: Mapping[Path | str, bytes] | None = None,
    historical_transition_review_overlay: Mapping[Path | str, bytes] | None = None,
    predecessor_transition_review_overlay: Mapping[Path | str, bytes] | None = None,
    transition_review_overlay: Mapping[Path | str, bytes] | None = None,
    r009_control_review_overlay: Mapping[Path | str, bytes] | None = None,
    r010_control_review_overlay: Mapping[Path | str, bytes] | None = None,
    r011_control_review_overlay: Mapping[Path | str, bytes] | None = None,
    r011_control_cohort_paths: Sequence[Path | str] | None = None,
) -> dict[str, Any]:
    """Pure seq72--76 checkpoint/output projection with no review-file reads.

    ``preflight`` and the eight staged outputs are explicit inputs so a
    reviewer can reproduce the exact projected checkpoint before the external
    transition review triad is written.  This function does not write files or
    validate that triad; ``build_transaction`` owns those authorization gates.
    """

    root = _validated_root(root)
    source = review._load_source(root)
    if source_checkpoint is not None:
        require(
            dict(source_checkpoint) == source["checkpoint"],
            "projection source checkpoint differs",
        )
    require(isinstance(preflight, Mapping), "projection preflight is missing")
    plan = deepcopy(dict(preflight))
    review._validate_replay(plan, source)
    cohort_paths = _normalize_r011_control_cohort_paths(r011_control_cohort_paths)
    historical_review_overlay = _historical_review_overlay(
        historical_transition_review_overlay
    )
    predecessor_review_overlay = _predecessor_review_overlay(
        predecessor_transition_review_overlay, plan
    )
    review_overlay = _review_overlay(transition_review_overlay, plan)
    frozen_control_review_overlay = _r009_review_overlay(
        r009_control_review_overlay, plan
    )
    frozen_r010_control_review_overlay = _r010_review_overlay(
        r010_control_review_overlay, plan
    )
    current_control_review_overlay = _r011_review_overlay(
        r011_control_review_overlay, plan
    )
    expected_staged = build_staged_outputs(root, plan)
    outputs = {Path(path): raw for path, raw in staged_outputs.items()}
    require(
        outputs == expected_staged,
        "staged transaction outputs differ",
    )
    provisional = _project_checkpoint(
        root,
        source,
        plan,
        outputs,
        cohort_paths,
        historical_review_overlay,
        predecessor_review_overlay,
        review_overlay,
        frozen_control_review_overlay,
        frozen_r010_control_review_overlay,
        current_control_review_overlay,
    )
    future_catalog_paths = [
        *outputs,
        *historical_review_overlay,
        *predecessor_review_overlay,
        *review_overlay,
        *frozen_control_review_overlay,
        *frozen_r010_control_review_overlay,
        *current_control_review_overlay,
        *cohort_paths,
    ]
    catalog_bytes = _catalog_overlay(catalog_overlay)
    if catalog_bytes is None:
        catalog_bytes = _catalog_outputs(root, provisional, future_catalog_paths)
    outputs.update(catalog_bytes)
    checkpoint = _project_checkpoint(
        root,
        source,
        plan,
        outputs,
        cohort_paths,
        historical_review_overlay,
        predecessor_review_overlay,
        review_overlay,
        frozen_control_review_overlay,
        frozen_r010_control_review_overlay,
        current_control_review_overlay,
    )
    outputs[CHECKPOINT_REL] = json_text(checkpoint).encode("utf-8")
    _validate_projected_checkpoint(
        root,
        checkpoint,
        plan,
        outputs,
        cohort_paths,
        historical_review_overlay,
        predecessor_review_overlay,
        review_overlay,
        frozen_control_review_overlay,
        frozen_r010_control_review_overlay,
        current_control_review_overlay,
    )
    return {
        "checkpoint": checkpoint,
        "output_bytes": outputs,
        "r011_control_cohort_paths": [path.as_posix() for path in cohort_paths],
        "r009_control_review_binding": (
            _r009_control_review_binding(frozen_control_review_overlay)
            if frozen_control_review_overlay
            else None
        ),
        "r010_control_review_binding": (
            _r010_control_review_binding(frozen_r010_control_review_overlay)
            if frozen_r010_control_review_overlay
            else None
        ),
        "r011_control_review_binding": (
            _r011_control_review_binding(current_control_review_overlay)
            if current_control_review_overlay
            else None
        ),
    }


def build_transaction(root: Path = ROOT) -> dict[str, Any]:
    """Build the staged transaction entirely in memory; never write output."""

    root = _validated_root(root)
    (
        package,
        source,
        historical_transition_review_overlay,
        predecessor_transition_review_overlay,
        transition_review_overlay,
        predecessor_review_binding,
        review_binding,
        review_subject_binding,
    ) = _capture_transition_material(root)
    r009_control_review_overlay, r009_control_review_binding = (
        _capture_frozen_r009_control_material(root)
    )
    r010_control_review_overlay, r010_control_review_binding = (
        _capture_frozen_r010_control_material(root)
    )
    (
        r011_control_cohort,
        r011_control_review_overlay,
        r011_control_review_binding,
    ) = _capture_r011_control_material(root)
    require(
        r009_control_review_binding
        == package["r009_control_successor_review_bindings"],
        "R009 control review differs from the reviewed transition core",
    )
    require(
        r010_control_review_binding
        == package["r010_control_successor_review_bindings"],
        "R010 control review differs from the reviewed transition core",
    )
    require(
        r011_control_review_binding
        == package["r011_control_successor_review_bindings"],
        "R011 control review differs from the reviewed transition core",
    )
    plan = _bind_review_to_preflight(
        package["preflight"],
        review_binding,
        r009_control_review_binding,
        r010_control_review_binding,
        r011_control_review_binding,
        predecessor_review_binding=predecessor_review_binding,
        review_subject_binding=review_subject_binding,
    )
    review.validate_reviewed_transition_plan(
        plan,
        package,
        review_binding,
    )
    review._validate_replay(plan, source)
    r011_control_cohort_paths = _normalize_r011_control_cohort_paths(
        [row["path"] for row in r011_control_cohort]
    )
    projection = project_transaction(
        root,
        preflight=plan,
        staged_outputs=build_staged_outputs(root, plan),
        source_checkpoint=source["checkpoint"],
        historical_transition_review_overlay=historical_transition_review_overlay,
        predecessor_transition_review_overlay=predecessor_transition_review_overlay,
        transition_review_overlay=transition_review_overlay,
        r009_control_review_overlay=r009_control_review_overlay,
        r010_control_review_overlay=r010_control_review_overlay,
        r011_control_review_overlay=r011_control_review_overlay,
        r011_control_cohort_paths=r011_control_cohort_paths,
    )
    output_bytes = projection["output_bytes"]
    checkpoint = projection["checkpoint"]
    source_bindings = _source_bindings(
        root,
        r011_control_cohort_paths,
    )
    _validate_control_cohort_source_bindings(r011_control_cohort, source_bindings)
    mutable_bindings = [
        _binding(path, _read_regular(root, path, "mutable transaction target"))
        for path in MUTABLE_TARGETS
    ]
    transaction = {
        "schema_version": "walksafe.fp046-npc-r002-reopen-seq72-76-transaction.v4",
        "transaction_status": TRANSACTION_STATUS,
        "source_checkpoint": deepcopy(package["source_checkpoint"]),
        "predecessor_transition_review_binding": predecessor_review_binding,
        "transition_review_binding": review_binding,
        "transition_review_subject_binding": review_subject_binding,
        "r009_control_review_binding": r009_control_review_binding,
        "r010_control_review_binding": r010_control_review_binding,
        "r011_control_review_binding": r011_control_review_binding,
        "r011_control_cohort": list(r011_control_cohort),
        "source_bindings": source_bindings,
        "mutable_target_bindings": mutable_bindings,
        "output_bindings": [
            _binding(path, output_bytes[path]) for path in sorted(output_bytes)
        ],
        "preflight": plan,
        "checkpoint": checkpoint,
        "output_bytes": output_bytes,
    }
    validate_transaction(root, transaction)
    return transaction


def validate_transaction(root: Path, transaction: Mapping[str, Any]) -> None:
    root = _validated_root(root)
    outputs = transaction.get("output_bytes")
    checkpoint = transaction.get("checkpoint")
    plan = transaction.get("preflight")
    cohort = transaction.get("r011_control_cohort")
    require(isinstance(outputs, dict), "transaction output bytes are missing")
    require(
        all(isinstance(path, Path) and isinstance(raw, bytes) for path, raw in outputs.items()),
        "transaction output byte types differ",
    )
    require(
        isinstance(checkpoint, dict)
        and isinstance(plan, dict)
        and isinstance(cohort, list)
        and all(isinstance(row, dict) for row in cohort)
        and isinstance(transaction.get("source_bindings"), list),
        "transaction projection is missing",
    )
    cohort_paths = _normalize_r011_control_cohort_paths(
        [row.get("path") for row in cohort]
    )
    source_bindings = transaction.get("source_bindings", [])
    require(
        transaction.get("mutable_target_bindings")
        == [
            _binding(path, _read_regular(root, path, "mutable transaction target"))
            for path in MUTABLE_TARGETS
        ],
        "mutable target bindings differ from live source",
    )
    live_r009_overlay, live_r009_binding = _capture_frozen_r009_control_material(root)
    live_r010_overlay, live_r010_binding = _capture_frozen_r010_control_material(root)
    live_cohort, live_r011_overlay, live_r011_binding = (
        _capture_r011_control_material(root)
    )
    (
        package,
        _source,
        live_historical_review_overlay,
        live_predecessor_review_overlay,
        live_transition_review_overlay,
        live_predecessor_review_binding,
        live_transition_review_binding,
        live_review_subject_binding,
    ) = _capture_transition_material(root)
    events = plan.get("events")
    seq72 = events[0] if isinstance(events, list) and events else None
    require(
        transaction.get("schema_version")
        == "walksafe.fp046-npc-r002-reopen-seq72-76-transaction.v4"
        and transaction.get("transaction_status") == TRANSACTION_STATUS
        and transaction.get("output_bindings")
        == [_binding(path, outputs[path]) for path in sorted(outputs)],
        "transaction output bindings differ",
    )
    require(
        list(live_cohort) == cohort,
        "R011 approved control cohort differs from the staged transaction",
    )
    expected_review_fields = {
        "predecessor_transition_review_binding",
        "transition_review_binding",
        "transition_review_subject_binding",
        "r009_control_review_binding",
        "r010_control_review_binding",
        "r011_control_review_binding",
    }
    require(
        isinstance(seq72, dict)
        and {
            key
            for key in seq72
            if "review" in key and key.endswith("binding")
        }
        == expected_review_fields
        and transaction.get("r009_control_review_binding")
        == live_r009_binding
        and seq72.get("r009_control_review_binding")
        == live_r009_binding
        and transaction.get("r010_control_review_binding")
        == live_r010_binding
        and seq72.get("r010_control_review_binding")
        == live_r010_binding
        and transaction.get("r011_control_review_binding")
        == live_r011_binding
        and seq72.get("r011_control_review_binding")
        == live_r011_binding,
        "R009/R010/R011 control review transaction binding differs",
    )
    require(
        transaction.get("transition_review_binding")
        == live_transition_review_binding
        == seq72.get("transition_review_binding")
        and transaction.get("predecessor_transition_review_binding")
        == live_predecessor_review_binding
        == seq72.get("predecessor_transition_review_binding")
        and transaction.get("transition_review_subject_binding")
        == live_review_subject_binding
        == seq72.get("transition_review_subject_binding"),
        "R003/R004 transition review transaction binding differs",
    )
    require(
        live_r009_binding
        == package.get("r009_control_successor_review_bindings")
        and live_r010_binding
        == package.get("r010_control_successor_review_bindings")
        and live_r011_binding
        == package.get("r011_control_successor_review_bindings")
        and transaction.get("source_checkpoint") == package.get("source_checkpoint"),
        "reviewed transition source binding differs",
    )
    review.validate_reviewed_transition_plan(
        plan,
        package,
        live_transition_review_binding,
    )
    staged_outputs = build_staged_outputs(root, plan)
    require(
        [_binding(path, staged_outputs[path]) for path in sorted(staged_outputs)]
        == package.get("staged_subject_bindings")
        and len(staged_outputs) == 8,
        "reviewed add-only output bindings differ",
    )
    require(
        [
            row.get("path") if isinstance(row, Mapping) else None
            for row in source_bindings
        ]
        == [path.as_posix() for path in _source_binding_paths(cohort_paths)],
        "transaction source binding path inventory differs",
    )
    live_historical_binding = _historical_transition_r001_review_binding(
        live_historical_review_overlay
    )
    live_historical_r002_binding = _historical_transition_r002_review_binding(
        live_historical_review_overlay
    )
    _validate_review_source_bindings(
        live_historical_binding,
        source_bindings,
        "historical transition R001 review",
    )
    _validate_review_source_bindings(
        live_historical_r002_binding,
        source_bindings,
        "historical transition R002 review",
    )
    _validate_review_source_bindings(
        live_r009_binding,
        source_bindings,
        "frozen R009 control review",
    )
    _validate_review_source_bindings(
        live_r010_binding,
        source_bindings,
        "frozen R010 control review",
    )
    _validate_review_source_bindings(
        live_r011_binding,
        source_bindings,
        "current R011 control review",
    )
    _validate_review_source_bindings(
        live_predecessor_review_binding,
        source_bindings,
        "transition R003 predecessor review",
    )
    _validate_review_source_bindings(
        live_transition_review_binding,
        source_bindings,
        "transition R004 current review",
    )
    _validate_control_cohort_source_bindings(
        cohort,
        source_bindings,
    )
    require(
        source_bindings == _source_bindings(root, cohort_paths),
        "transaction source bindings differ from live source",
    )
    _validate_projected_checkpoint(
        root,
        checkpoint,
        plan,
        outputs,
        cohort_paths,
        _historical_review_overlay(live_historical_review_overlay),
        _predecessor_review_overlay(live_predecessor_review_overlay, plan),
        _review_overlay(live_transition_review_overlay, plan),
        _r009_review_overlay(live_r009_overlay, plan),
        _r010_review_overlay(live_r010_overlay, plan),
        _r011_review_overlay(live_r011_overlay, plan),
    )
    require(
        outputs.get(CHECKPOINT_REL) == json_text(checkpoint).encode("utf-8"),
        "transaction checkpoint bytes differ",
    )


def _verify_bindings(root: Path, bindings: Sequence[Mapping[str, Any]], label: str) -> dict[Path, tuple[int, ...]]:
    signatures: dict[Path, tuple[int, ...]] = {}
    for binding in bindings:
        require(isinstance(binding, dict), f"{label} binding is malformed")
        path_value = binding.get("path")
        digest = binding.get("sha256")
        length = binding.get("byte_length")
        require(
            isinstance(path_value, str) and isinstance(digest, str) and isinstance(length, int),
            f"{label} binding fields differ",
        )
        path = Path(path_value)
        raw = _read_regular(root, path, label)
        require(
            bytes_sha256(raw) == digest and len(raw) == length,
            f"{label} compare-exchange differs: {path}",
        )
        signatures[path] = _single_link_signature(root, path, label)
    return signatures


def _stage_files(root: Path, outputs: Mapping[Path, bytes]) -> tuple[Path, dict[Path, Path]]:
    try:
        stage = Path(tempfile.mkdtemp(prefix=".walksafe-r011-stage-", dir=root))
    except OSError as exc:
        raise BuildError("transaction staging directory cannot be created") from exc
    files: dict[Path, Path] = {}
    try:
        for index, path in enumerate(sorted(outputs)):
            raw = outputs[path]
            staged = stage / f"{index:04d}.payload"
            descriptor = os.open(staged, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                with os.fdopen(descriptor, "wb", closefd=True) as handle:
                    handle.write(raw)
                    handle.flush()
                    os.fsync(handle.fileno())
            except BaseException:
                try:
                    os.close(descriptor)
                except OSError:
                    pass
                raise
            files[path] = staged
    except BaseException:
        for staged in files.values():
            try:
                staged.unlink()
            except OSError:
                pass
        try:
            stage.rmdir()
        except OSError:
            pass
        raise
    return stage, files


def _cleanup_stage(stage: Path) -> bool:
    try:
        for item in stage.iterdir():
            item.unlink()
        stage.rmdir()
    except OSError:
        return False
    return True


def _rollback(
    root: Path,
    replaced: list[tuple[Path, Path]],
    created: list[tuple[Path, tuple[int, int]]],
    created_parents: list[Path],
) -> None:
    rollback_errors: list[str] = []
    for relative, backup in reversed(replaced):
        try:
            parent = _ensure_safe_parent(root, relative, [])
            os.replace(backup, parent / relative.name)
        except (OSError, BuildError) as exc:
            rollback_errors.append(f"cannot restore {relative}: {exc}")
    for relative, identity in reversed(created):
        try:
            target = _walk_regular_output(
                root,
                relative,
                "created transaction output",
                missing_ok=True,
            )
            if target is None:
                continue
            info = target.stat(follow_symlinks=False)
            if (info.st_dev, info.st_ino) != identity:
                rollback_errors.append(f"created output identity changed: {relative}")
                continue
            target.unlink()
        except (OSError, BuildError) as exc:
            rollback_errors.append(f"cannot remove {relative}: {exc}")
    for directory in reversed(created_parents):
        try:
            directory.rmdir()
        except OSError:
            pass
    if rollback_errors:
        raise BuildError("transaction rollback failed: " + "; ".join(rollback_errors))


def apply_transaction(root: Path = ROOT, transaction: Mapping[str, Any] | None = None) -> None:
    """Compare-exchange and publish all seq72--76 outputs as one guarded unit."""

    root = _validated_root(root)
    expected = build_transaction(root)
    if transaction is not None:
        validate_transaction(root, transaction)
        require(transaction == expected, "transaction source changed before apply")
    transaction = expected
    _r009_overlay_bytes, approved_r009_binding = (
        _capture_frozen_r009_control_material(root)
    )
    _r010_overlay_bytes, approved_r010_binding = (
        _capture_frozen_r010_control_material(root)
    )
    (
        approved_r011_cohort,
        _r011_overlay_bytes,
        approved_r011_binding,
    ) = _capture_r011_control_material(root)
    require(
        list(approved_r011_cohort) == transaction["r011_control_cohort"],
        "R011 approved control cohort differs from the staged transaction",
    )
    require(
        approved_r009_binding == transaction["r009_control_review_binding"]
        and approved_r010_binding == transaction["r010_control_review_binding"]
        and approved_r011_binding == transaction["r011_control_review_binding"],
        "R009/R010/R011 control review transaction binding differs",
    )
    control_review_bindings = [
        *approved_r009_binding.values(),
        *approved_r010_binding.values(),
        *approved_r011_binding.values(),
    ]
    control_review_signatures = _verify_bindings(
        root,
        control_review_bindings,
        "R009/R010/R011 control review",
    )
    outputs = transaction["output_bytes"]
    source_signatures = _verify_bindings(
        root,
        transaction["source_bindings"],
        "transaction source",
    )
    target_signatures = _verify_bindings(
        root, transaction["mutable_target_bindings"], "mutable transaction target"
    )
    new_outputs = [path for path in outputs if path not in MUTABLE_TARGETS]
    for path in new_outputs:
        _require_missing_target(root, path)
    stage, staged = _stage_files(root, outputs)
    replaced: list[tuple[Path, Path]] = []
    created: list[tuple[Path, tuple[int, int]]] = []
    created_parents: list[Path] = []
    try:
        require(
            _verify_bindings(
                root,
                transaction["source_bindings"],
                "transaction source",
            )
            == source_signatures,
            "transaction source changed while staging",
        )
        require(
            _verify_bindings(
                root,
                transaction["mutable_target_bindings"],
                "mutable transaction target",
            )
            == target_signatures,
            "mutable transaction target changed while staging",
        )
        require(
            _verify_bindings(
                root,
                control_review_bindings,
                "R009/R010/R011 control review",
            )
            == control_review_signatures,
            "R009/R010/R011 control review changed while staging",
        )
        _staged_r009_overlay, staged_r009_binding = (
            _capture_frozen_r009_control_material(root)
        )
        _staged_r010_overlay, staged_r010_binding = (
            _capture_frozen_r010_control_material(root)
        )
        staged_cohort, _staged_r011_overlay, staged_r011_binding = (
            _capture_r011_control_material(root)
        )
        require(
            list(staged_cohort) == transaction["r011_control_cohort"]
            and staged_r009_binding == transaction["r009_control_review_binding"]
            and staged_r010_binding == transaction["r010_control_review_binding"]
            and staged_r011_binding == transaction["r011_control_review_binding"],
            "R009/R010/R011 approved control material changed while staging",
        )
        for path in sorted(outputs):
            parent = _ensure_safe_parent(root, path, created_parents)
            target = parent / path.name
            if path in MUTABLE_TARGETS:
                current = _single_link_signature(root, path, "mutable transaction target")
                require(
                    current == target_signatures[path],
                    f"mutable transaction target changed before replace: {path}",
                )
                backup = stage / f"backup-{len(replaced):04d}.payload"
                with backup.open("xb") as handle:
                    handle.write(_read_regular(root, path, "mutable transaction target"))
                    handle.flush()
                    os.fsync(handle.fileno())
                replaced.append((path, backup))
                os.chmod(staged[path], stat.S_IMODE(current[2]))
                os.replace(staged[path], target)
            else:
                _require_missing_target(root, path)
                staged_info = staged[path].stat(follow_symlinks=False)
                stage_identity = (staged_info.st_dev, staged_info.st_ino)
                os.chmod(staged[path], 0o644)
                created.append((path, stage_identity))
                os.link(staged[path], target, follow_symlinks=False)
                info = target.stat(follow_symlinks=False)
                require(
                    stat.S_ISREG(info.st_mode) and info.st_nlink == 2,
                    f"add-only output link authority differs: {path}",
                )
                require(
                    (info.st_dev, info.st_ino) == stage_identity,
                    f"add-only output identity differs: {path}",
                )
        for path in new_outputs:
            staged[path].unlink()
        _validate_published_transaction(root, transaction)
    except BaseException as primary_error:
        try:
            _rollback(root, replaced, created, created_parents)
        except BuildError as rollback_error:
            raise BuildError(
                "transaction publish failed: "
                f"{primary_error}; rollback failed: {rollback_error}; "
                f"recovery staging retained: {stage}"
            ) from primary_error
        if not _cleanup_stage(stage):
            raise BuildError(
                "transaction publish failed: "
                f"{primary_error}; rollback succeeded but staging cleanup "
                f"failed: {stage}"
            ) from primary_error
        raise
    if not _cleanup_stage(stage):
        raise BuildError(
            f"transaction applied but staging cleanup failed: {stage}"
        )


def validate_applied_transaction(root: Path, transaction: Mapping[str, Any]) -> None:
    """Verify published bytes without attempting to rerun or overwrite a transaction."""

    root = _validated_root(root)
    output_bindings = transaction.get("output_bindings")
    outputs = transaction.get("output_bytes")
    require(
        transaction.get("schema_version")
        == "walksafe.fp046-npc-r002-reopen-seq72-76-transaction.v4"
        and transaction.get("transaction_status") == TRANSACTION_STATUS
        and isinstance(output_bindings, list)
        and isinstance(outputs, dict)
        and output_bindings
        == [_binding(path, outputs[path]) for path in sorted(outputs)],
        "published transaction envelope differs",
    )
    for binding in output_bindings:
        path = Path(binding["path"])
        raw = _read_regular(root, path, "published transaction output")
        require(
            bytes_sha256(raw) == binding["sha256"] and len(raw) == binding["byte_length"],
            f"published transaction output differs: {path}",
        )
    checkpoint = _parse_canonical(
        _read_regular(root, CHECKPOINT_REL, "published checkpoint"), "published checkpoint"
    )
    require(checkpoint == transaction["checkpoint"], "published checkpoint projection differs")
    r004_binding, r004_raw = review.load_validated_transition_r004(root)
    r004_assignment = review.strict_json_bytes(
        r004_raw[review.TRANSITION_R004_ASSIGNMENT_REL],
        "published transition R004 assignment",
    )
    predecessor_binding, _predecessor_raw = review.load_frozen_transition_r003(root)
    review.load_frozen_transition_r002(root)
    review.load_frozen_transition_r001(root)
    _r009_overlay, r009_binding = _capture_frozen_r009_control_material(root)
    _r010_overlay, r010_binding = _capture_frozen_r010_control_material(root)
    cohort, _r011_overlay, r011_binding = _capture_r011_control_material(root)
    events = transaction.get("preflight", {}).get("events")
    seq72 = events[0] if isinstance(events, list) and events else None
    expected_review_fields = {
        "predecessor_transition_review_binding",
        "transition_review_binding",
        "transition_review_subject_binding",
        "r009_control_review_binding",
        "r010_control_review_binding",
        "r011_control_review_binding",
    }
    require(
        isinstance(seq72, dict)
        and {
            key
            for key in seq72
            if "review" in key and key.endswith("binding")
        }
        == expected_review_fields
        and transaction.get("predecessor_transition_review_binding")
        == predecessor_binding
        == seq72.get("predecessor_transition_review_binding")
        and transaction.get("transition_review_binding")
        == r004_binding
        == seq72.get("transition_review_binding")
        and transaction.get("transition_review_subject_binding")
        == seq72.get("transition_review_subject_binding")
        and transaction.get("r009_control_review_binding")
        == r009_binding
        == seq72.get("r009_control_review_binding")
        and transaction.get("r010_control_review_binding")
        == r010_binding
        == seq72.get("r010_control_review_binding")
        and transaction.get("r011_control_review_binding")
        == r011_binding
        == seq72.get("r011_control_review_binding")
        and transaction.get("r011_control_cohort") == list(cohort),
        "published review bindings differ",
    )
    review.validate_reviewed_transition_plan(
        transaction["preflight"],
        r004_assignment["review_scope"],
        r004_binding,
    )
    closure = {path.as_posix() for path in _review_closure_paths()}
    managed = checkpoint.get("working_tree_snapshot", {}).get(
        "managed_changed_paths"
    )
    handoff = checkpoint.get("session_handoff", {}).get("changed_files")
    require(
        len(closure) == 21
        and isinstance(managed, list)
        and closure.issubset(managed)
        and handoff == managed,
        "published review closure differs",
    )


def _validate_published_transaction(
    root: Path,
    transaction: Mapping[str, Any],
) -> None:
    """Run the exact bytes and official control checks before commit cleanup."""

    validate_applied_transaction(root, transaction)
    continuation_errors = continuation.validate(root, CHECKPOINT_REL)
    require(
        isinstance(continuation_errors, list) and not continuation_errors,
        "published continuation validation differs: "
        + "; ".join(str(error) for error in continuation_errors),
    )
    goal_graph = _load_module("scripts.check_walksafe_goal_graph_v2_4")
    goal_graph_errors = goal_graph.validate(
        root,
        CHECKPOINT_REL,
        check_continuation=False,
        run_frozen_semantics=False,
    )
    require(
        isinstance(goal_graph_errors, list) and not goal_graph_errors,
        "published Goal graph validation differs: "
        + "; ".join(str(error) for error in goal_graph_errors),
    )


def _git_output(
    root: Path,
    arguments: Sequence[str],
    label: str,
    *,
    allowed_returncodes: tuple[int, ...] = (0,),
) -> bytes:
    try:
        completed = subprocess.run(
            ["git", "--no-optional-locks", "-C", str(root), *arguments],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError as exc:
        raise BuildError(f"cannot inspect {label}") from exc
    require(
        completed.returncode in allowed_returncodes,
        f"cannot inspect {label}: {completed.stderr.decode('utf-8', 'replace').strip()}",
    )
    return completed.stdout


def _absolute_regular_signature(path: Path, label: str) -> tuple[Any, ...]:
    try:
        info = path.lstat()
    except OSError as exc:
        raise BuildError(f"required {label} cannot be inspected: {path}") from exc
    require(
        stat.S_ISREG(info.st_mode)
        and not stat.S_ISLNK(info.st_mode)
        and info.st_nlink == 1,
        f"{label} must be a single-link regular file: {path}",
    )
    try:
        raw = path.read_bytes()
    except OSError as exc:
        raise BuildError(f"required {label} cannot be read: {path}") from exc
    return (
        bytes_sha256(raw),
        len(raw),
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
        info.st_nlink,
        info.st_size,
        info.st_mtime_ns,
        info.st_ctime_ns,
    )


def _git_state(root: Path) -> tuple[Path, dict[str, Any]]:
    raw_git_dir = _git_output(
        root,
        ("rev-parse", "--absolute-git-dir"),
        "absolute git directory",
    )
    try:
        git_dir_text = raw_git_dir.decode("utf-8").strip()
    except UnicodeDecodeError as exc:
        raise BuildError("absolute git directory is not UTF-8") from exc
    require(
        bool(git_dir_text) and "\x00" not in git_dir_text,
        "absolute git directory differs",
    )
    unresolved_git_dir = Path(git_dir_text)
    require(unresolved_git_dir.is_absolute(), "absolute git directory differs")
    try:
        git_dir = unresolved_git_dir.resolve(strict=True)
        git_dir_info = git_dir.lstat()
    except OSError as exc:
        raise BuildError("absolute git directory cannot be resolved") from exc
    require(
        stat.S_ISDIR(git_dir_info.st_mode) and not stat.S_ISLNK(git_dir_info.st_mode),
        "absolute git directory must resolve to a directory",
    )
    state = {
        "index": _absolute_regular_signature(git_dir / "index", "git index"),
        "head_file": _absolute_regular_signature(git_dir / "HEAD", "git HEAD"),
        "head": _git_output(root, ("rev-parse", "--verify", "HEAD"), "git HEAD"),
        "refs": _git_output(
            root,
            ("show-ref", "--head", "--dereference"),
            "git refs",
            allowed_returncodes=(0, 1),
        ),
        "status": _git_output(
            root,
            ("status", "--porcelain=v2", "-z", "--untracked-files=all"),
            "git status",
        ),
    }
    return git_dir, state


def _shadow_copy_manifest(
    root: Path,
    paths: Sequence[Path],
    label: str,
) -> dict[Path, tuple[str, int, int, int, int]]:
    manifest: dict[Path, tuple[str, int, int, int, int]] = {}
    for path in paths:
        raw = _read_regular(root, path, label)
        info = (root / path).lstat()
        manifest[path] = (
            bytes_sha256(raw),
            len(raw),
            info.st_mode,
            info.st_nlink,
            info.st_mtime_ns,
        )
    return manifest


def _copy_shadow_paths(root: Path, shadow: Path, paths: Sequence[Path]) -> None:
    operands = bytearray()
    for path in paths:
        _validate_relative(path, "shadow source")
        encoded = os.fsencode("./" + path.as_posix())
        require(b"\x00" not in encoded, f"unsafe shadow source path: {path}")
        operands.extend(encoded)
        operands.append(0)
    try:
        completed = subprocess.run(
            [
                "xargs",
                "-0",
                "-r",
                "cp",
                "--parents",
                "--preserve=mode,timestamps",
                "--reflink=auto",
                f"--target-directory={shadow}",
                "--",
            ],
            cwd=root,
            input=bytes(operands),
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError as exc:
        raise BuildError("shadow repository copy cannot be executed") from exc
    require(
        completed.returncode == 0,
        "shadow repository copy failed: "
        + completed.stderr.decode("utf-8", "replace").strip(),
    )


def _validate_check_in_shadow(
    root: Path,
    transaction: Mapping[str, Any],
    *,
    temporary_directory_factory: Any | None = None,
) -> None:
    """Apply and officially postvalidate a check-only transaction in isolation."""

    root = _validated_root(root)
    catalog_paths = tuple(
        sorted(Path(path) for path in catalogs.discover_source_paths(root))
    )
    require(
        len(catalog_paths) == len(set(catalog_paths)),
        "source catalog universe contains duplicate paths",
    )
    private_paths = tuple(
        Path(path) for path in _control_successor_module().UNMANAGED_EVIDENCE_PATHS
    )
    copy_paths = tuple(sorted(set(catalog_paths).union(private_paths)))
    copy_manifest = _shadow_copy_manifest(root, copy_paths, "shadow source")
    source_checkpoint = _binding(
        CHECKPOINT_REL,
        _read_regular(root, CHECKPOINT_REL, "source checkpoint"),
    )
    require(
        transaction.get("source_checkpoint") == source_checkpoint,
        "shadow source checkpoint binding differs",
    )
    source_signatures = _verify_bindings(
        root,
        transaction.get("source_bindings", []),
        "shadow transaction source",
    )
    git_dir, git_state = _git_state(root)
    factory = temporary_directory_factory or tempfile.TemporaryDirectory
    try:
        with factory(prefix=".walksafe-r011-check-") as temporary:
            shadow = Path(temporary)
            shadow_info = shadow.lstat()
            require(
                stat.S_ISDIR(shadow_info.st_mode)
                and not stat.S_ISLNK(shadow_info.st_mode),
                "shadow root must be a non-symlink directory",
            )
            os.chmod(shadow, 0o700)
            _copy_shadow_paths(root, shadow, copy_paths)
            os.chmod(shadow, 0o700)
            require(
                _shadow_copy_manifest(shadow, copy_paths, "copied shadow source")
                == copy_manifest,
                "shadow repository copy differs",
            )
            git_link = shadow / ".git"
            os.symlink(str(git_dir), git_link, target_is_directory=True)
            require(
                stat.S_ISLNK(git_link.lstat().st_mode)
                and os.readlink(git_link) == str(git_dir),
                "shadow git directory link differs",
            )
            shadow_catalog_paths = tuple(
                sorted(Path(path) for path in catalogs.discover_source_paths(shadow))
            )
            require(
                shadow_catalog_paths == catalog_paths,
                "shadow catalog universe differs from source",
            )
            apply_transaction(shadow, transaction)
    finally:
        require(
            _binding(
                CHECKPOINT_REL,
                _read_regular(root, CHECKPOINT_REL, "source checkpoint"),
            )
            == source_checkpoint,
            "source checkpoint changed during shadow validation",
        )
        require(
            _verify_bindings(
                root,
                transaction.get("source_bindings", []),
                "shadow transaction source",
            )
            == source_signatures,
            "transaction source changed during shadow validation",
        )
        require(
            _shadow_copy_manifest(root, copy_paths, "shadow source")
            == copy_manifest,
            "source repository files changed during shadow validation",
        )
        current_git_dir, current_git_state = _git_state(root)
        require(
            current_git_dir == git_dir and current_git_state == git_state,
            "source git index, HEAD, refs, or status changed during shadow validation",
        )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--print-plan", action="store_true")
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    try:
        transaction = build_transaction(args.root)
        if args.apply:
            apply_transaction(args.root, transaction)
            print("WalkSafe FP046/NPC R002 seq72-76 transaction: APPLIED")
        elif args.check:
            validate_transaction(args.root, transaction)
            _r009_overlay, approved_r009_binding = (
                _capture_frozen_r009_control_material(args.root)
            )
            _r010_overlay, approved_r010_binding = (
                _capture_frozen_r010_control_material(args.root)
            )
            approved, _r011_overlay, approved_r011_binding = (
                _capture_r011_control_material(args.root)
            )
            require(
                list(approved) == transaction["r011_control_cohort"]
                and approved_r009_binding
                == transaction["r009_control_review_binding"]
                and approved_r010_binding
                == transaction["r010_control_review_binding"]
                and approved_r011_binding
                == transaction["r011_control_review_binding"],
                "R009/R010/R011 approved control material differs from the staged transaction",
            )
            _validate_check_in_shadow(args.root, transaction)
            print("WalkSafe FP046/NPC R002 seq72-76 transaction: READY")
        else:
            printable = {
                key: value
                for key, value in transaction.items()
                if key not in {"output_bytes", "checkpoint"}
            }
            print(json_text(printable))
    except (BuildError, OSError, ValueError, TypeError, KeyError) as exc:
        print(f"WalkSafe FP046/NPC R002 seq72-76 transaction: FAIL: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
