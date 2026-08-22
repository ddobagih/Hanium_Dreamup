#!/usr/bin/env python3
"""Build the add-only canonical R029 audit pair from its frozen candidate.

Only the Gap/Backlog pair is publishable.  The discovery pair remains
candidate-only and is retained here as exact source evidence.
"""

from __future__ import annotations

import argparse
import importlib
from pathlib import Path
import stat
import sys
from typing import Any, Mapping, Sequence


def _load_candidate() -> Any:
    try:
        from scripts import (
            build_walksafe_fp046_gap_backlog_r029_candidate_20260815 as module,
        )

        return module
    except ModuleNotFoundError:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module(
            "scripts.build_walksafe_fp046_gap_backlog_r029_candidate_20260815"
        )


candidate = _load_candidate()
io_base = candidate.io_base
BuildError = candidate.BuildError
require = candidate.require
bytes_sha256 = candidate.bytes_sha256
object_sha256 = candidate.object_sha256
strict_json_bytes = candidate.strict_json_bytes
verify_seal = candidate.verify_seal

ROOT = Path(__file__).resolve().parents[1]
CANONICAL_GAP_JSON_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260815-r029.json"
)
CANONICAL_GAP_MD_REL = CANONICAL_GAP_JSON_REL.with_suffix(".md")
CANONICAL_BACKLOG_JSON_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260815-r029.json"
)
CANONICAL_BACKLOG_MD_REL = CANONICAL_BACKLOG_JSON_REL.with_suffix(".md")
CANDIDATE_TO_CANONICAL = {
    candidate.R029_GAP_JSON_REL: CANONICAL_GAP_JSON_REL,
    candidate.R029_GAP_MD_REL: CANONICAL_GAP_MD_REL,
    candidate.R029_BACKLOG_JSON_REL: CANONICAL_BACKLOG_JSON_REL,
    candidate.R029_BACKLOG_MD_REL: CANONICAL_BACKLOG_MD_REL,
}
CANONICAL_OUTPUT_PATHS = tuple(CANDIDATE_TO_CANONICAL.values())
CANDIDATE_ONLY_PATHS = (candidate.DISCOVERY_JSON_REL, candidate.DISCOVERY_MD_REL)

EXPECTED_CANDIDATE_OUTPUT_BINDINGS = {
    candidate.R029_GAP_JSON_REL: {
        "sha256": "bf0ae2003d53ab310f2321ea3c3026fc9f255909837f6b874a9a4b3738ad3922",
        "byte_length": 533697,
    },
    candidate.R029_GAP_MD_REL: {
        "sha256": "d4ced7d5c298a9532e94a8bee11138de98724b54586a9037805a297898bae68f",
        "byte_length": 612,
    },
    candidate.R029_BACKLOG_JSON_REL: {
        "sha256": "8128560569c340ce3b60c24972ccaa6bb5a52e5d13035bd709a3f12a2392aeba",
        "byte_length": 65896,
    },
    candidate.R029_BACKLOG_MD_REL: {
        "sha256": "932602ef6b31d276aa4f8c75f37d708472469577d202decbb255b6aa6ac88b47",
        "byte_length": 363,
    },
    candidate.DISCOVERY_JSON_REL: {
        "sha256": "e75093a88bc318ee7f51619fa68fe573fdffe90652b3f65b3890d044dc7d5979",
        "byte_length": 7245,
    },
    candidate.DISCOVERY_MD_REL: {
        "sha256": "d7fd04e3ca6fa62d1e118f07a2f3cfdbeb16d2e49ffa80f3a7681f426728bce8",
        "byte_length": 527,
    },
}


def _root(root: Path) -> Path:
    root = Path(root)
    try:
        info = root.lstat()
    except OSError as exc:
        raise BuildError(f"bridge root cannot be inspected: {root}") from exc
    require(
        stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode),
        "bridge root must be a non-symlink directory",
    )
    return root.resolve(strict=True)


def _raw_binding(path: Path, raw: bytes, role: str) -> dict[str, Any]:
    return {
        "path": path.as_posix(),
        "role": role,
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
    }


def _rows(rows: Any, key: str, label: str) -> dict[str, dict[str, Any]]:
    require(type(rows) is list, f"{label} must be a list")
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        require(type(row) is dict and type(row.get(key)) is str, f"{label} row differs")
        require(row[key] not in result, f"duplicate {label}: {row[key]}")
        result[row[key]] = row
    return result


def _only_changed(
    before: Mapping[str, dict[str, Any]],
    after: Mapping[str, dict[str, Any]],
    expected: str,
    label: str,
) -> None:
    require(
        len(before) == len(after) and list(before) == list(after),
        f"{label} inventory differs",
    )
    require(
        [key for key in before if before[key] != after[key]] == [expected],
        f"{label} scope differs",
    )


def _validate_candidate_bytes(outputs: Mapping[Path, str]) -> None:
    require(
        tuple(outputs) == candidate.OUTPUT_PATHS
        and set(outputs) == set(EXPECTED_CANDIDATE_OUTPUT_BINDINGS),
        "candidate output inventory or ordering differs",
    )
    for path in candidate.OUTPUT_PATHS:
        text = outputs[path]
        require(type(text) is str, f"candidate output is not text: {path}")
        raw = text.encode("utf-8")
        pin = EXPECTED_CANDIDATE_OUTPUT_BINDINGS[path]
        require(
            bytes_sha256(raw) == pin["sha256"] and len(raw) == pin["byte_length"],
            f"candidate output bytes differ: {path}",
        )


def _pinned_inputs(
    root: Path,
) -> tuple[dict[str, Any], dict[str, Any], dict[Path, bytes], dict[Path, bytes]]:
    def read(paths: Sequence[Path], pins: Mapping[Path, Mapping[str, Any]], label: str) -> dict[Path, bytes]:
        require(set(paths) == set(pins), f"{label} pin inventory differs")
        raw_by_path = {path: io_base.read_bytes(root, path) for path in paths}
        for path, raw in raw_by_path.items():
            pin = pins[path]
            require(
                bytes_sha256(raw) == pin["sha256"] and len(raw) == pin["byte_length"],
                f"{label} pin differs: {path}",
            )
        return raw_by_path

    r028_raw = read(
        candidate.R028_INPUT_PATHS,
        candidate.EXPECTED_R028_BINDING_BY_PATH,
        "R028 predecessor",
    )
    current_raw = read(
        candidate.CURRENT_SOURCE_PATHS,
        candidate.EXPECTED_CURRENT_SOURCE_BINDING_BY_PATH,
        "regression source",
    )
    gap = strict_json_bytes(r028_raw[candidate.R028_GAP_JSON_REL], "R028 gap")
    backlog = strict_json_bytes(r028_raw[candidate.R028_BACKLOG_JSON_REL], "R028 backlog")
    verify_seal(gap, "report_content_sha256", "R028 gap")
    verify_seal(backlog, "backlog_content_sha256", "R028 backlog")
    require(
        gap.get("metadata", {}).get("report_id")
        == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260814-028"
        and backlog.get("metadata", {}).get("backlog_id")
        == "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260814-028",
        "R028 predecessor identity differs",
    )
    return gap, backlog, r028_raw, current_raw


def _candidate_json(outputs: Mapping[Path, str], path: Path, label: str) -> dict[str, Any]:
    return strict_json_bytes(outputs[path].encode("utf-8"), label)


def _validate_gap(before: Mapping[str, Any], after: Mapping[str, Any]) -> None:
    verify_seal(after, "report_content_sha256", "R029 canonical gap")
    require(
        after.get("metadata", {}).get("report_id")
        == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260815-029"
        and after.get("metadata", {}).get("predecessor_report_id")
        == before.get("metadata", {}).get("report_id"),
        "R029 gap identity or R028 binding differs",
    )
    before_rows = _rows(before.get("assessments"), "gap_id", "R028 assessments")
    after_rows = _rows(after.get("assessments"), "gap_id", "R029 assessments")
    require(len(before_rows) == len(after_rows) == 68, "R029 assessment count differs")
    _only_changed(before_rows, after_rows, candidate.GAP_ID, "R029 assessment")
    target = after_rows[candidate.GAP_ID]
    target_seal = target.get("assessment_sha256")
    target_projection = {key: value for key, value in target.items() if key != "assessment_sha256"}
    require(
        type(target_seal) is str and target_seal == object_sha256(target_projection),
        "R029 GAP-055 assessment seal differs",
    )
    require(
        target.get("status") == "PARTIAL"
        and target.get("formal_test_status") == "NOT_RUN"
        and target.get("planned_test_ids") == list(candidate.FORMAL_TEST_IDS)
        and target.get("waived") is False
        and target.get("regression_reopen") == candidate._reopen_record(),
        "R029 GAP-055 reopen or zero-credit boundary differs",
    )
    summary = after.get("summary", {})
    counts = {status: 0 for status in candidate.EXPECTED_STATUS_COUNTS}
    for row in after_rows.values():
        require(row.get("status") in counts, "R029 assessment status differs")
        counts[row["status"]] += 1
    require(
        counts == candidate.EXPECTED_STATUS_COUNTS
        and summary.get("status_counts") == counts
        and summary.get("implemented_and_formally_verified_count") == 0
        and summary.get("release_status") == "NOT_ELIGIBLE",
        "R029 gap global count or credit differs",
    )
    require(
        all(
            before.get(key) == after.get(key)
            for key in ("authorization_boundary", "current_status_model")
            if key in before or key in after
        )
        and {
            "candidate_status",
            "canonical_application_status",
            "approval_status",
            "operational_application_boundary",
        }.isdisjoint(after),
        "R029 gap global or candidate-only drift differs",
    )


def _validate_backlog(
    before: Mapping[str, Any], after: Mapping[str, Any], gap: Mapping[str, Any]
) -> None:
    verify_seal(after, "backlog_content_sha256", "R029 canonical backlog")
    require(
        after.get("metadata", {}).get("backlog_id")
        == "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260815-029"
        and after.get("metadata", {}).get("predecessor_backlog_id")
        == before.get("metadata", {}).get("backlog_id")
        and after.get("gap_report_content_sha256") == gap.get("report_content_sha256"),
        "R029 backlog identity or gap binding differs",
    )
    require(
        after.get("source_predecessor")
        == {
            "path": candidate.R028_BACKLOG_JSON_REL.as_posix(),
            "file_sha256": candidate.EXPECTED_R028_BINDING_BY_PATH[
                candidate.R028_BACKLOG_JSON_REL
            ]["sha256"],
            "preserved_unchanged": True,
        },
        "R029 backlog R028 pin differs",
    )
    before_actions = _rows(before.get("next_action_sequence"), "source_policy_id", "R028 actions")
    after_actions = _rows(after.get("next_action_sequence"), "source_policy_id", "R029 actions")
    require(len(before_actions) == len(after_actions) == 68, "R029 action count differs")
    _only_changed(before_actions, after_actions, candidate.POLICY_ID, "R029 action")
    require(
        after_actions[candidate.POLICY_ID].get("status") == "REOPEN_REQUIRED",
        "R029 FP-046 action differs",
    )
    before_epics = _rows(before.get("epics"), "epic_id", "R028 epics")
    after_epics = _rows(after.get("epics"), "epic_id", "R029 epics")
    require(len(before_epics) == len(after_epics) == 12, "R029 epic count differs")
    _only_changed(before_epics, after_epics, "EPIC-03", "R029 epic")
    require(
        after_epics["EPIC-03"].get("current_status") == "PLANNED"
        and all(
            before.get(key) == after.get(key)
            for key in ("authorization_boundary", "current_status_model", "next_single_action")
        )
        and {
            "candidate_status",
            "canonical_application_status",
            "approval_status",
            "operational_application_boundary",
        }.isdisjoint(after),
        "R029 backlog global or candidate-only drift differs",
    )


def _validate_discovery(
    discovery: Mapping[str, Any], r028_raw: Mapping[Path, bytes], current_raw: Mapping[Path, bytes]
) -> None:
    verify_seal(discovery, "discovery_content_sha256", "R029 discovery")
    require(
        discovery.get("document_id")
        == "WS-FP046-GAP055-REGRESSION-TRIGGER-DISCOVERY-20260815-R001"
        and discovery.get("candidate_status")
        == "DISCOVERED_NOT_CANONICALLY_APPLIED"
        and discovery.get("canonical_application_status") == "NOT_APPLIED"
        and discovery.get("approval_status") == "NOT_REQUESTED"
        and discovery.get("approval_claimed") is False
        and discovery.get("external_independence_claimed") is False
        and discovery.get("operational_application_boundary")
        == candidate.operational_application_boundary()
        and discovery.get("completion_boundary") == candidate.zero_credit_boundary()
        and discovery.get("r028_predecessor_bindings")
        == candidate._r028_predecessor_bindings(r028_raw)
        and discovery.get("source_bindings")
        == candidate._current_source_bindings(current_raw),
        "candidate-only discovery boundary or source evidence differs",
    )


def candidate_source_evidence(outputs: Mapping[Path, str]) -> dict[str, Any]:
    """The six immutable candidate bytes consumed by the bridge."""

    _validate_candidate_bytes(outputs)
    return {
        "schema_version": "walksafe.fp046-gap055-r029-canonical-bridge-source.v1",
        "candidate_output_bindings": [
            _raw_binding(path, outputs[path].encode("utf-8"), "CANDIDATE_ONLY" if path in CANDIDATE_ONLY_PATHS else "R029_CANDIDATE")
            for path in candidate.OUTPUT_PATHS
        ],
        "candidate_only_paths": [path.as_posix() for path in CANDIDATE_ONLY_PATHS],
        "canonical_mappings": [
            {
                "candidate_path": source.as_posix(),
                "canonical_path": destination.as_posix(),
                "sha256": bytes_sha256(outputs[source].encode("utf-8")),
                "byte_length": len(outputs[source].encode("utf-8")),
            }
            for source, destination in CANDIDATE_TO_CANONICAL.items()
        ],
        "discovery_boundary": {
            "canonical_application_status": "NOT_APPLIED",
            "publication_scope": "CANDIDATE_ONLY_SOURCE_EVIDENCE",
        },
    }


def validate_candidate_outputs(root: Path, outputs: Mapping[Path, str]) -> None:
    root = _root(root)
    _validate_candidate_bytes(outputs)
    before_gap, before_backlog, r028_raw, current_raw = _pinned_inputs(root)
    gap = _candidate_json(outputs, candidate.R029_GAP_JSON_REL, "R029 gap")
    backlog = _candidate_json(outputs, candidate.R029_BACKLOG_JSON_REL, "R029 backlog")
    discovery = _candidate_json(outputs, candidate.DISCOVERY_JSON_REL, "R029 discovery")
    _validate_gap(before_gap, gap)
    _validate_backlog(before_backlog, backlog, gap)
    _validate_discovery(discovery, r028_raw, current_raw)
    require(
        set(CANDIDATE_ONLY_PATHS).isdisjoint(CANDIDATE_TO_CANONICAL),
        "candidate-only discovery is mapped to canonical output",
    )


def build_outputs_and_source_evidence(
    root: Path = ROOT,
) -> tuple[dict[Path, str], dict[str, Any]]:
    root = _root(root)
    candidate_outputs = candidate.build_outputs(root)
    validate_candidate_outputs(root, candidate_outputs)
    outputs = {
        canonical: candidate_outputs[source]
        for source, canonical in CANDIDATE_TO_CANONICAL.items()
    }
    require(
        tuple(outputs) == CANONICAL_OUTPUT_PATHS
        and all(outputs[target] == candidate_outputs[source] for source, target in CANDIDATE_TO_CANONICAL.items()),
        "candidate-to-canonical bytes differ",
    )
    return outputs, candidate_source_evidence(candidate_outputs)


def build_outputs(root: Path = ROOT) -> dict[Path, str]:
    """Build only; no candidate or canonical file is created."""

    return build_outputs_and_source_evidence(root)[0]


def build_source_evidence(root: Path = ROOT) -> dict[str, Any]:
    return build_outputs_and_source_evidence(root)[1]


def _parent(root: Path, relative: Path, *, create: bool) -> Path | None:
    require(
        not relative.is_absolute()
        and bool(relative.parts)
        and all(part not in {"", ".", ".."} for part in relative.parts),
        f"unsafe output path: {relative}",
    )
    cursor = root
    for component in relative.parent.parts:
        child = cursor / component
        try:
            info = child.lstat()
        except FileNotFoundError:
            if not create:
                return None
            child.mkdir()
            info = child.lstat()
        require(
            stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode),
            f"unsafe output parent: {relative}",
        )
        cursor = child
    return cursor


def _exists(root: Path, relative: Path) -> bool:
    parent = _parent(root, relative, create=False)
    if parent is None:
        return False
    try:
        (parent / relative.name).lstat()
    except FileNotFoundError:
        return False
    return True


def write_or_check_outputs(
    root: Path, outputs: Mapping[Path, str], *, write: bool
) -> None:
    """Check exact files or publish them once; writes never overwrite."""

    root = _root(root)
    expected = build_outputs(root)
    require(
        tuple(outputs) == CANONICAL_OUTPUT_PATHS and dict(outputs) == expected,
        "bridge outputs differ from deterministic candidate mapping",
    )
    if not write:
        for path, text in expected.items():
            require(
                io_base.read_bytes(root, path) == text.encode("utf-8"),
                f"canonical output differs: {path}",
            )
        return
    for path in CANONICAL_OUTPUT_PATHS:
        require(not _exists(root, path), f"add-only canonical target already exists: {path}")
    for path, text in expected.items():
        parent = _parent(root, path, create=True)
        require(parent is not None, f"output parent missing: {path}")
        try:
            with (parent / path.name).open("xb") as output:
                output.write(text.encode("utf-8"))
        except FileExistsError as exc:
            raise BuildError(f"add-only canonical target already exists: {path}") from exc


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        outputs = build_outputs(args.root)
        write_or_check_outputs(args.root, outputs, write=args.write)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"FP-046/GAP-055 canonical R029 bridge: FAIL: {exc}")
        return 1
    print(
        "FP-046/GAP-055 canonical R029 bridge: "
        f"PASS outputs={len(outputs)} mode={'WRITE' if args.write else 'CHECK'}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
