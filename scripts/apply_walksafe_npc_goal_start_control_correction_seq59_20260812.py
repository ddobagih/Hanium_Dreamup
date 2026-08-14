#!/usr/bin/env python3
"""Prepare or CAS-publish the zero-credit NPC start-control correction seq59."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp048_goal_completed_seq43_44_20260802 as atomic
from scripts import apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812 as seq58
from scripts import apply_walksafe_npc_single_admin_recovery_goal_seq56_57_20260810 as retained
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as contract


CHECKPOINT_RELATIVE = Path("docs/control/walksafe-project-continuation-checkpoint.json")
TARGET_GOAL_ID = seq58.TARGET_GOAL_ID
GOAL_ID = TARGET_GOAL_ID
TARGET_GOAL_SHA256 = seq58.TARGET_GOAL_SHA256
TARGET_GOAL_RELATIVE = seq58.TARGET_GOAL_RELATIVE
SOURCE_READY_SEQUENCE = seq58.SOURCE_READY_SEQUENCE
SOURCE_READY_EVENT_ID = seq58.SOURCE_READY_EVENT_ID
SOURCE_READY_EVENT_SHA256 = seq58.SOURCE_READY_EVENT_SHA256
SOURCE_CONTROL_EVENT_SHA256 = (
    "929ff8b16ec13ad9bd697148f6cee600e4491e627339fdf4d2aa325b6a64c38b"
)
CONTROL_CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "NPC-CORRECTION-20260812-001"
)
CONTROL_CORRECTION_SEQUENCE = 59
CONTROL_CORRECTION_OCCURRED_AT = "2026-08-12T23:18:02+09:00"
SOURCE_CHECKPOINT_FILE_SHA256 = (
    "55b2a209679ddb9573abfeb97b0b24112151884e756133a4faef34879257d2d4"
)
SOURCE_CHECKPOINT_BYTE_COUNT = 1_770_409
SOURCE_CHECKPOINT_CTIME_NS = 1_786_543_540_886_601_199
SOURCE_CHECKPOINT_SCHEMA_VERSION = "1.25.0"
STATIC_PLAN_MANIFEST_SHA256 = seq58.STATIC_PLAN_MANIFEST_SHA256
AUTHORIZED_BRANCH = seq58.AUTHORIZED_BRANCH
AUTHORIZED_BASE_COMMIT = seq58.AUTHORIZED_BASE_COMMIT
AUTHORIZED_HEAD_COMMIT = seq58.AUTHORIZED_HEAD_COMMIT
GIT_AUTHORITY_PATHS = seq58.GIT_AUTHORITY_PATHS

AUTHORITY_ROOT_RELATIVE = Path(
    "docs/control/execution/goal-start-control-reanchors"
) / CONTROL_CORRECTION_EVENT_ID
AUTHORIZATION_RELATIVE = AUTHORITY_ROOT_RELATIVE / "authorization.md"
AUTHORIZATION_DOCUMENT_ID = (
    "WS-GOAL-GRAPH-V2-4-NPC-START-CONTROL-CORRECTION-"
    "AUTHORIZATION-20260812-001"
)
AUTHORIZATION_FILE_SHA256 = (
    "d12ce6bc2b741cb583ea3ddefae2ea3b09852d215cf49f488c4e4550fc0deb8f"
)
AUTHORIZATION_BYTE_COUNT = 5_273
AUTHORIZATION_RECORDED_AT = "2026-08-12T23:15:00+09:00"
INDEPENDENT_REVIEW_RELATIVE = AUTHORITY_ROOT_RELATIVE / "independent-review.md"
INDEPENDENT_REVIEW_DOCUMENT_ID = (
    "WS-GOAL-GRAPH-V2-4-NPC-START-CONTROL-CORRECTION-"
    "REVIEW-20260812-001"
)
INDEPENDENT_REVIEW_FILE_SHA256 = (
    "8d2ce430e4b002ca8dc38969858a4d3adf4789268157af969ce7f737e91ccaeb"
)
INDEPENDENT_REVIEW_BYTE_COUNT = 3_304
INDEPENDENT_REVIEWED_AT = "2026-08-12T23:18:01+09:00"

FAILED_GATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-SINGLE-ADMIN-RECOVERY-"
    "20260812-001"
)
FAILED_GATE_LOG_RELATIVE = (
    Path("docs/control/execution/goal-gates")
    / FAILED_GATE_EVENT_ID
    / "07-ROOT_NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REGRESSION.log"
)
FAILED_GATE_LOG_SHA256 = (
    "d6a2657c3848d292eea440adeb3afc68f210ae3bcc0a60c0a0eb1d1b888bb05b"
)
FAILED_GATE_LOG_BYTE_COUNT = 2_324

CHECKER_RELATIVE = Path("scripts/check_walksafe_project_continuation_v2_4.py")
CHECKER_BEFORE_SHA256 = (
    "a928e38a23f8e67c4ccb3c54521d2cb610e4afc70a91057acdb27d48cbc88600"
)
CHECKER_AFTER_SHA256 = (
    "50f9592718edb47b2242cbcaa4cdb89b41f369b6d6b16468a81882ef7c80bca9"
)

# Every path outside the source seq58 snapshot must be named here. Existing
# snapshot members may change only when their final digest is named below.
ADD_ONLY_PATHS = (
    AUTHORIZATION_RELATIVE.as_posix(),
    INDEPENDENT_REVIEW_RELATIVE.as_posix(),
    "scripts/apply_walksafe_npc_goal_start_control_correction_seq59_20260812.py",
    "scripts/apply_walksafe_npc_single_admin_recovery_goal_started_seq60_20260812.py",
    "tests/test_apply_walksafe_npc_goal_start_control_correction_seq59_20260812.py",
    "tests/test_apply_walksafe_npc_single_admin_recovery_goal_started_seq60_20260812.py",
)
SOURCE_PATHS = tuple(sorted(ADD_ONLY_PATHS))

ADD_ONLY_FINAL_SHA256 = {
    "scripts/apply_walksafe_npc_single_admin_recovery_goal_started_seq60_20260812.py": (
        "974538366bda49b087423f805edf182bfe3588d6dc2e1609e99efb5c4b951c4e"
    ),
    "tests/test_apply_walksafe_npc_goal_start_control_correction_seq59_20260812.py": (
        "553d5dfc73329469ee6754e2e2558c0293139e1ed546275fca73d8ba56395a2a"
    ),
    "tests/test_apply_walksafe_npc_single_admin_recovery_goal_started_seq60_20260812.py": (
        "6a67190bb90f23e5130d6103b2084043fd910836c05bb142373f5b7269143147"
    ),
}

MODIFIED_EXISTING_FINAL_SHA256 = {
    CHECKER_RELATIVE.as_posix(): CHECKER_AFTER_SHA256,
    "scripts/generate_repository_catalogs.py": (
        "ed08e07baefd6a014fb4bb529642e371614dc5f7472ffee0a848f0091c66971e"
    ),
    "scripts/run_walksafe_test_layers_current.sh": (
        "f1a19f459ae5cd2cfeacd08bb5c64e64218f746f4ced8940ce3a2e8454f542f7"
    ),
    "tests/test_repository_catalogs.py": (
        "f2f49c4849df32b26d955c611c989e67573c0904bec5bac26c519f6cc6ef1d28"
    ),
    "docs/catalogs/repository-paths.json": (
        "45a5cb576f209af5e5123513712d19bae14436e4bb50e71320cfe7c53d446f76"
    ),
    "docs/catalogs/scripts.json": (
        "22137678e8edc2d1dfdb4cc8a3ed01f203c4aebd84dd1ff010e3d5b8ab0c4985"
    ),
    "docs/catalogs/tests.json": (
        "96a6fa4526706119997d8ae556bf0f2c13ab8b2cfa8bcf3a5d7a997ed3912668"
    ),
    "scripts/run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py": (
        "6253f8ca5d54556ff5a0106c2243f0eb2b5a76ef8fc6a65f85491e439e70079d"
    ),
    "tests/test_run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py": (
        "a371a08fc6936cac855f89e3aebbc82c37805931ed4c5f1ebc89bce349e3ad7f"
    ),
}

EVENT_FIELDS = seq58.EVENT_FIELDS
CLAIM_BOUNDARY = copy.deepcopy(seq58.CLAIM_BOUNDARY)
SOURCE_UNCHANGED_CONTROL_SHA256 = copy.deepcopy(
    seq58.SOURCE_UNCHANGED_CONTROL_SHA256
)
EVIDENCE_REFS = [
    "FAILED_START_GATE_001_CORRECTION",
    "GOAL_START_CONTROL_CORRECTION_AUTHORIZATION",
    "GOAL_START_CONTROL_CORRECTION_INDEPENDENT_REVIEW",
    "INITIAL_START_GATE_CONTRACT_SUCCESSOR",
]


class ControlCorrectionError(RuntimeError):
    """The exact correction cannot be proven or published."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ControlCorrectionError(message)


def _json_bytes(value: Mapping[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _safe_file(root: Path, relative: Path | str, *, mode: int | None = None) -> Path:
    value = Path(relative)
    _require(
        not value.is_absolute()
        and value.parts
        and all(part not in {"", ".", ".."} for part in value.parts),
        f"unsafe path: {value}",
    )
    current = root.resolve(strict=True)
    for part in value.parts:
        current /= part
        _require(not current.is_symlink(), f"symlink path: {value}")
    info = current.lstat()
    _require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, f"unsafe file: {value}")
    if mode is not None:
        _require(stat.S_IMODE(info.st_mode) == mode, f"file mode differs: {value}")
    return current


def _binding(
    root: Path,
    relative: Path,
    document_id: str,
    digest: str,
    size: int,
    timestamp_key: str,
    timestamp: str,
) -> dict[str, Any]:
    raw = _safe_file(root, relative).read_bytes()
    _require(len(raw) == size and _sha256_bytes(raw) == digest, f"binding differs: {relative}")
    return {
        "document_id": document_id,
        "path": relative.as_posix(),
        "file_sha256": digest,
        "byte_count": size,
        timestamp_key: timestamp,
    }


def _authorization_binding(root: Path = ROOT) -> dict[str, Any]:
    return _binding(
        root, AUTHORIZATION_RELATIVE, AUTHORIZATION_DOCUMENT_ID,
        AUTHORIZATION_FILE_SHA256, AUTHORIZATION_BYTE_COUNT,
        "recorded_at", AUTHORIZATION_RECORDED_AT,
    )


def _independent_review_binding(root: Path = ROOT) -> dict[str, Any]:
    return _binding(
        root, INDEPENDENT_REVIEW_RELATIVE, INDEPENDENT_REVIEW_DOCUMENT_ID,
        INDEPENDENT_REVIEW_FILE_SHA256, INDEPENDENT_REVIEW_BYTE_COUNT,
        "reviewed_at", INDEPENDENT_REVIEWED_AT,
    )


def _source_ready_event_binding() -> dict[str, Any]:
    return seq58._source_ready_event_binding()


def _contract_supersession() -> dict[str, Any]:
    return {
        "previous_contract_binding": seq58._previous_contract_binding(),
        "replacement_contract_binding": seq58._replacement_contract_binding(),
        "reason_code": "CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED",
    }


def _source_repository_context_binding() -> dict[str, Any]:
    return {
        "checkpoint_path": CHECKPOINT_RELATIVE.as_posix(),
        "checkpoint_file_sha256": SOURCE_CHECKPOINT_FILE_SHA256,
        "checkpoint_byte_count": SOURCE_CHECKPOINT_BYTE_COUNT,
        "branch": AUTHORIZED_BRANCH,
        "base_commit": AUTHORIZED_BASE_COMMIT,
        "current_head": AUTHORIZED_HEAD_COMMIT,
        "managed_changed_path_count": 639,
        "path_set_sha256": "fcf3627f3beb8675930c990fa9ac336f48012e95b19bf9c78dbf6f26bf4add10",
        "content_set_sha256": "279899fa496301f3c5339fa2f61e43983e25d224e964348ef67c5b76b995c5a3",
    }


def _after_context(paths: list[str], path_digest: str, content_digest: str) -> dict[str, Any]:
    return {
        "branch": AUTHORIZED_BRANCH,
        "base_commit": AUTHORIZED_BASE_COMMIT,
        "current_head": AUTHORIZED_HEAD_COMMIT,
        "managed_changed_path_count": len(paths),
        "path_set_sha256": path_digest,
        "content_set_sha256": content_digest,
    }


def _runtime_projection(state: Mapping[str, Any]) -> dict[str, Any]:
    return seq58._runtime_projection(state)


def _control_projection_hashes(checkpoint: Mapping[str, Any]) -> dict[str, str]:
    return seq58._control_projection_hashes(checkpoint)


def _require_exact_source(checkpoint: Mapping[str, Any]) -> None:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(checkpoint.get("schema_version") == SOURCE_CHECKPOINT_SCHEMA_VERSION, "source schema differs")
    _require(isinstance(history, list) and len(history) == 58, "source is not seq58")
    seq58._validate_projected_structure(checkpoint)
    _require(history[-1].get("event_sha256") == SOURCE_CONTROL_EVENT_SHA256, "source seq58 seal differs")
    _require(state.get("status_by_goal", {}).get(TARGET_GOAL_ID) == "READY", "source target is not READY")
    _require("IN_PROGRESS" not in state.get("status_by_goal", {}).values(), "source has IN_PROGRESS Goal")
    _require(_control_projection_hashes(checkpoint) == SOURCE_UNCHANGED_CONTROL_SHA256, "source control projection differs")


def project_seq59(
    source: Mapping[str, Any],
    *,
    managed_paths: list[str],
    path_set_sha256: str,
    content_set_sha256: str,
    authorization_binding: Mapping[str, Any],
    independent_review_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    _require_exact_source(source)
    _require(managed_paths == sorted(set(managed_paths)), "managed paths differ")
    checkpoint = copy.deepcopy(source)
    state = checkpoint["goal_execution"]
    event = {
        "sequence": CONTROL_CORRECTION_SEQUENCE,
        "event_id": CONTROL_CORRECTION_EVENT_ID,
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "occurred_on": datetime.fromisoformat(CONTROL_CORRECTION_OCCURRED_AT).date().isoformat(),
        "occurred_at": CONTROL_CORRECTION_OCCURRED_AT,
        "previous_focus_goal_id": TARGET_GOAL_ID,
        "previous_focus_content_sha256": TARGET_GOAL_SHA256,
        "focus_goal_id": TARGET_GOAL_ID,
        "focus_goal_content_sha256": TARGET_GOAL_SHA256,
        "subject_goal_id": TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "static_plan_manifest_sha256": STATIC_PLAN_MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": _runtime_projection(state),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": copy.deepcopy(EVIDENCE_REFS),
        "source_ready_event_binding": _source_ready_event_binding(),
        "contract_supersession": _contract_supersession(),
        "repository_context_reanchor": {
            "before": _source_repository_context_binding(),
            "after": _after_context(managed_paths, path_set_sha256, content_set_sha256),
        },
        "authorization_binding": copy.deepcopy(dict(authorization_binding)),
        "independent_review_binding": copy.deepcopy(dict(independent_review_binding)),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "unchanged_control_projection": {
            name: {"before_sha256": digest, "after_sha256": digest}
            for name, digest in sorted(SOURCE_UNCHANGED_CONTROL_SHA256.items())
        },
        "previous_event_sha256": SOURCE_CONTROL_EVENT_SHA256,
    }
    event["event_sha256"] = contract.event_sha256(event)
    _require(set(event) == EVENT_FIELDS, "event field set differs")
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = CONTROL_CORRECTION_OCCURRED_AT
    snapshot = checkpoint["working_tree_snapshot"]
    snapshot.update({
        "base_head": AUTHORIZED_BASE_COMMIT,
        "managed_changed_paths": managed_paths,
        "managed_changed_path_count": len(managed_paths),
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": content_set_sha256,
        "scope": "NPC start-control correction seq59; READY, zero credit, R002 retained.",
    })
    handoff = checkpoint["session_handoff"]
    handoff["branch"] = AUTHORIZED_BRANCH
    handoff["changed_files"] = copy.deepcopy(managed_paths)
    handoff["source_commit_or_snapshot"].update({
        "base_commit": AUTHORIZED_BASE_COMMIT,
        "current_head": AUTHORIZED_HEAD_COMMIT,
        "file_count": len(managed_paths),
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": content_set_sha256,
    })
    _require(_control_projection_hashes(checkpoint) == SOURCE_UNCHANGED_CONTROL_SHA256, "immutable control projection changed")
    return checkpoint, event


def _validate_structure(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(isinstance(history, list) and len(history) == 59, "checkpoint is not seq59")
    _require_exact_source({**checkpoint, "goal_execution": {**state, "transition_history": history[:-1], "transition_history_anchor_sha256": SOURCE_CONTROL_EVENT_SHA256, "validation_cutoff_at": seq58.CONTROL_REANCHOR_OCCURRED_AT}})
    event = history[-1]
    _require(isinstance(event, dict) and set(event) == EVENT_FIELDS, "event fields differ")
    _require(
        event.get("sequence") == 59
        and event.get("event_id") == CONTROL_CORRECTION_EVENT_ID
        and event.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and event.get("occurred_at") == CONTROL_CORRECTION_OCCURRED_AT
        and event.get("from_status") == event.get("to_status") == "READY"
        and event.get("status_changes") == {}
        and event.get("previous_event_sha256") == SOURCE_CONTROL_EVENT_SHA256
        and event.get("source_ready_event_binding") == _source_ready_event_binding()
        and event.get("contract_supersession") == _contract_supersession()
        and event.get("evidence_refs") == EVIDENCE_REFS
        and event.get("claim_boundary") == CLAIM_BOUNDARY
        and event.get("event_sha256") == contract.event_sha256(event),
        "event invariant differs",
    )
    _require(
        state.get("transition_history_anchor_sha256") == event["event_sha256"]
        and state.get("validation_cutoff_at") == CONTROL_CORRECTION_OCCURRED_AT
        and state.get("status_by_goal", {}).get(TARGET_GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values(),
        "runtime boundary differs",
    )
    _require(_control_projection_hashes(checkpoint) == SOURCE_UNCHANGED_CONTROL_SHA256, "control projection differs")
    return event


def _git(root: Path, *args: str) -> str:
    result = subprocess.run(["git", *args], cwd=root, text=True, capture_output=True)
    _require(result.returncode == 0, f"Git failed: {' '.join(args)}")
    return result.stdout.strip()


def _live_paths(root: Path) -> list[str]:
    raw = subprocess.run(
        ["git", "diff", "--name-only", "-z", AUTHORIZED_BASE_COMMIT, "--"],
        cwd=root, check=True, capture_output=True,
    ).stdout
    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard", "-z"],
        cwd=root, check=True, capture_output=True,
    ).stdout
    paths = {part.decode() for part in (raw + untracked).split(b"\0") if part}
    paths.discard(CHECKPOINT_RELATIVE.as_posix())
    return sorted(path for path in paths if not path.startswith("docs/control/execution/goal-gates/"))


def _validate_inputs(root: Path) -> None:
    _require(_git(root, "branch", "--show-current") == AUTHORIZED_BRANCH, "branch differs")
    _require(_git(root, "rev-parse", "HEAD^{commit}") == AUTHORIZED_HEAD_COMMIT, "HEAD differs")
    source_checkpoint = _safe_file(root, CHECKPOINT_RELATIVE, mode=0o600)
    source_checkpoint_raw = source_checkpoint.read_bytes()
    if (
        len(source_checkpoint_raw) == SOURCE_CHECKPOINT_BYTE_COUNT
        and _sha256_bytes(source_checkpoint_raw) == SOURCE_CHECKPOINT_FILE_SHA256
    ):
        source_checkpoint_cutoff_ns = source_checkpoint.stat().st_ctime_ns
        _require(
            source_checkpoint_cutoff_ns == SOURCE_CHECKPOINT_CTIME_NS,
            "source checkpoint physical identity changed",
        )
        try:
            source_document = json.loads(source_checkpoint_raw)
            source_managed_paths = source_document["working_tree_snapshot"]["managed_changed_paths"]
        except (KeyError, TypeError, json.JSONDecodeError) as exc:
            raise ControlCorrectionError("source managed path authority is malformed") from exc
        _require(
            isinstance(source_managed_paths, list)
            and source_managed_paths == sorted(set(source_managed_paths)),
            "source managed path authority differs",
        )
        allowed_changed_paths = set(MODIFIED_EXISTING_FINAL_SHA256)
        for path in source_managed_paths:
            _require(isinstance(path, str), "source managed path is not a string")
            metadata = _safe_file(root, path).stat()
            _require(
                metadata.st_ctime_ns <= source_checkpoint_cutoff_ns
                or path in allowed_changed_paths,
                f"unlisted source path changed after seq58: {path}",
            )
    for path, digest in MODIFIED_EXISTING_FINAL_SHA256.items():
        _require(_sha256_bytes(_safe_file(root, path).read_bytes()) == digest, f"modified path differs: {path}")
    for path, digest in ADD_ONLY_FINAL_SHA256.items():
        _require(_sha256_bytes(_safe_file(root, path).read_bytes()) == digest, f"add-only path differs: {path}")
    log = _safe_file(root, FAILED_GATE_LOG_RELATIVE)
    raw = log.read_bytes()
    _require(len(raw) == FAILED_GATE_LOG_BYTE_COUNT and _sha256_bytes(raw) == FAILED_GATE_LOG_SHA256, "failed gate evidence differs")
    _authorization_binding(root)
    _independent_review_binding(root)


def require_control_corrected_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    run_external_validators: bool = True,
    ignored_live_paths: Sequence[str] = (),
) -> None:
    root = root.resolve(strict=True)
    _validate_inputs(root)
    event = _validate_structure(checkpoint)
    _require(event["authorization_binding"] == _authorization_binding(root), "authorization binding differs")
    _require(event["independent_review_binding"] == _independent_review_binding(root), "review binding differs")
    snapshot = checkpoint["working_tree_snapshot"]
    paths = snapshot["managed_changed_paths"]
    _require(paths == sorted(set(paths)), "snapshot paths differ")
    ignored = set(ignored_live_paths)
    live = set(_live_paths(root)) - ignored
    _require(live.issubset(paths) and set(SOURCE_PATHS).issubset(paths), "live path escaped snapshot")
    path_digest, content_digest = contract.working_snapshot_hashes(root, paths)
    expected_after = _after_context(paths, path_digest, content_digest)
    _require(event["repository_context_reanchor"] == {"before": _source_repository_context_binding(), "after": expected_after}, "repository context differs")
    source_snapshot = checkpoint["session_handoff"]["source_commit_or_snapshot"]
    _require(
        snapshot["path_set_sha256"] == path_digest
        and snapshot["content_set_sha256"] == content_digest
        and checkpoint["session_handoff"]["changed_files"] == paths
        and source_snapshot["path_set_sha256"] == path_digest
        and source_snapshot["content_set_sha256"] == content_digest,
        "snapshot parity differs",
    )
    if run_external_validators:
        errors = contract.validate(root, CHECKPOINT_RELATIVE, contract.V23_ARCHIVE_RELATIVE, contract.V24_MANIFEST_RELATIVE)
        errors.extend(goal_graph.validate(root, CHECKPOINT_RELATIVE, contract.V23_ARCHIVE_RELATIVE, contract.V24_MANIFEST_RELATIVE, check_continuation=False))
        _require(not errors, "public validation failed: " + "; ".join(errors))


@dataclass
class PreparedProjection:
    root: Path
    checkpoint_path: Path
    source_raw: bytes
    projected_checkpoint: dict[str, Any]
    projected_checkpoint_bytes: bytes
    event: dict[str, Any]


def prepare_projection(root: Path, *, run_external_validators: bool = True) -> PreparedProjection:
    root = root.resolve(strict=True)
    _validate_inputs(root)
    checkpoint_path = _safe_file(root, CHECKPOINT_RELATIVE, mode=0o600)
    raw = checkpoint_path.read_bytes()
    _require(len(raw) == SOURCE_CHECKPOINT_BYTE_COUNT and _sha256_bytes(raw) == SOURCE_CHECKPOINT_FILE_SHA256, "source checkpoint bytes differ")
    source = json.loads(raw)
    _require_exact_source(source)
    source_paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    additions = set(_live_paths(root)) - source_paths
    _require(additions == set(SOURCE_PATHS), "add-only path set differs")
    required = set(contract.EXPECTED_CONTROLLED_PATHS) | set(contract.expected_goal_paths(source["goal_execution"]))
    paths = sorted(required | set(_live_paths(root)))
    path_digest, content_digest = contract.working_snapshot_hashes(root, paths)
    projected, event = project_seq59(
        source, managed_paths=paths, path_set_sha256=path_digest,
        content_set_sha256=content_digest,
        authorization_binding=_authorization_binding(root),
        independent_review_binding=_independent_review_binding(root),
    )
    # Candidate public validation uses a private temporary path and never the live source.
    if run_external_validators:
        parent = root / "docs/control/execution/goal-gates"
        with tempfile.TemporaryDirectory(prefix=".seq59-candidate-", dir=parent) as directory:
            candidate = Path(directory) / "checkpoint.json"
            candidate.write_bytes(_json_bytes(projected))
            candidate.chmod(0o600)
            relative = candidate.relative_to(root)
            errors = contract.validate(root, relative, contract.V23_ARCHIVE_RELATIVE, contract.V24_MANIFEST_RELATIVE)
            errors.extend(goal_graph.validate(root, relative, contract.V23_ARCHIVE_RELATIVE, contract.V24_MANIFEST_RELATIVE, check_continuation=False))
            _require(not errors, "candidate validation failed: " + "; ".join(errors))
    _require(checkpoint_path.read_bytes() == raw, "source changed during preparation")
    return PreparedProjection(root, checkpoint_path, raw, projected, _json_bytes(projected), event)


def write_projection(
    prepared: PreparedProjection,
    *,
    atomic_writer: Callable[..., None] = atomic.atomic_write,
) -> None:
    def guard() -> None:
        current = prepared.checkpoint_path.read_bytes()
        _require(current in {prepared.source_raw, prepared.projected_checkpoint_bytes}, "checkpoint changed at CAS boundary")
        _validate_inputs(prepared.root)
        _require(_json_bytes(prepared.projected_checkpoint) == prepared.projected_checkpoint_bytes, "candidate bytes diverged")

    guard()
    atomic_writer(
        prepared.checkpoint_path,
        prepared.projected_checkpoint_bytes,
        expected_source=prepared.source_raw,
        commit_guard=guard,
    )
    _require(prepared.checkpoint_path.read_bytes() == prepared.projected_checkpoint_bytes, "publication differs")
    _safe_file(prepared.root, CHECKPOINT_RELATIVE, mode=0o600)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        prepared = prepare_projection(args.root)
        if args.write:
            write_projection(prepared)
    except (ControlCorrectionError, OSError, ValueError, TypeError, KeyError) as exc:
        print(f"NPC start-control correction seq59: FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "NPC start-control correction seq59: PASS "
        f"mode={'WRITE' if args.write else 'CHECK'} "
        f"event_sha256={prepared.event['event_sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
