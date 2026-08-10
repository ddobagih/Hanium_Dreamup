#!/usr/bin/env python3
"""Run the add-only 9-check FP008 work-session resume gate."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import date, datetime, timedelta
import json
import os
from pathlib import Path
import re
import stat
import sys
import threading
from typing import Any, Iterator, Mapping, Sequence
from zoneinfo import ZoneInfo


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import run_walksafe_fp008_goal_start_gate_20260803 as start_gate


EVENT_ID = "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-FP008-20260809-001"
EVENT_ID_RE = re.compile(
    r"^WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-FP008-(\d{8})-(\d{3})$"
)
DOCUMENT_ID = (
    "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-RESUME-GATE-FP008-20260809-001"
)
RECEIPT_NAME = "implementation-resume-gate-receipt.json"
RECEIPT_STAGE_NAME = ".implementation-resume-gate-receipt.json.staging"
GATE_PURPOSE = "SESSION_RESUME"
SOURCE_SEQUENCE = 47
SOURCE_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-002"
SOURCE_EVENT_SHA256 = (
    "82ad77e33eaa55530f53f5ee315807ef66e21fbfc8be2511b004abe99505db90"
)
SOURCE_READY_EVENT_SHA256 = start_gate.SOURCE_READY_EVENT_SHA256
TARGET_GOAL_ID = start_gate.TARGET_GOAL_ID
TARGET_GOAL_SHA256 = start_gate.TARGET_GOAL_SHA256
READY_FRONTIER = start_gate.READY_FRONTIER
TARGET_KST_DATE = date(2026, 8, 9)
_ADAPTER_LOCK = threading.Lock()
_ORIGINAL_LOAD_CONTRACT = start_gate._load_gate_contract


GateError = start_gate.GateError
GateCheckFailed = start_gate.GateCheckFailed
GatePostCommitUncertain = start_gate.GatePostCommitUncertain


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise GateError(message)


def _load_resume_contract(
    root: Path,
    *,
    retained_content: bytes | None = None,
) -> tuple[tuple[tuple[str, str], ...], dict[str, Any]]:
    previous = start_gate.GATE_PURPOSE
    start_gate.GATE_PURPOSE = "INITIAL_START"
    try:
        return _ORIGINAL_LOAD_CONTRACT(
            root,
            retained_content=retained_content,
        )
    finally:
        start_gate.GATE_PURPOSE = previous


def _event_date(event_id: str) -> date:
    match = EVENT_ID_RE.fullmatch(event_id)
    if match is None or int(match.group(2)) < 1:
        raise GateError(
            "event ID must be an unused dated FP008 resume ID"
        )
    try:
        parsed = datetime.strptime(match.group(1), "%Y%m%d").date()
    except ValueError as exc:
        raise GateError("resume event ID date is invalid") from exc
    _require(
        parsed >= TARGET_KST_DATE,
        "resume event ID date precedes the reserved 20260809 boundary",
    )
    return parsed


def _document_id(event_id: str) -> str:
    event_date = _event_date(event_id)
    match = EVENT_ID_RE.fullmatch(event_id)
    assert match is not None
    return (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-RESUME-GATE-FP008-"
        f"{event_date.strftime('%Y%m%d')}-{match.group(2)}"
    )


def _validate_resume_source(
    checkpoint: dict[str, Any],
    *,
    contract_binding: dict[str, Any],
) -> tuple[str, Any]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(
        isinstance(state, dict)
        and isinstance(history, list)
        and len(history) == SOURCE_SEQUENCE,
        "FP008 resume gate requires the exact seq47 source",
    )
    ready = history[-2] if len(history) >= 2 else {}
    started = history[-1] if history else {}
    _require(
        isinstance(ready, dict)
        and ready.get("sequence") == 46
        and ready.get("event_id") == start_gate.READY_EVENT_ID
        and ready.get("event_sha256") == SOURCE_READY_EVENT_SHA256
        and ready.get("event_sha256") == start_gate.event_sha256(ready)
        and ready.get("implementation_start_gate_contract_binding")
        == contract_binding,
        "FP008 seq46 READY contract lineage differs",
    )
    _require(
        isinstance(started, dict)
        and started.get("sequence") == SOURCE_SEQUENCE
        and started.get("event_id") == SOURCE_EVENT_ID
        and started.get("event_type") == "GOAL_STARTED"
        and started.get("subject_goal_id") == TARGET_GOAL_ID
        and started.get("from_status") == "READY"
        and started.get("to_status") == "IN_PROGRESS"
        and started.get("status_changes") == {TARGET_GOAL_ID: "IN_PROGRESS"}
        and started.get("previous_event_sha256") == SOURCE_READY_EVENT_SHA256
        and started.get("event_sha256") == SOURCE_EVENT_SHA256
        and started.get("event_sha256") == start_gate.event_sha256(started)
        and state.get("transition_history_anchor_sha256") == SOURCE_EVENT_SHA256,
        "FP008 seq47 execution-session event differs",
    )
    statuses = state.get("status_by_goal")
    _require(
        state.get("package_id") == start_gate.PACKAGE_ID
        and state.get("package_status") == "ACTIVE"
        and state.get("activation_status") == "ACTIVE"
        and state.get("focus_goal_id") == TARGET_GOAL_ID
        and state.get("focus_goal_path") == start_gate.GOAL_RELATIVE.as_posix()
        and state.get("focus_work_item_id") == start_gate.WORK_ITEM_ID
        and state.get("focus_source") == "IMPLEMENTATION_BACKLOG"
        and state.get("ready_frontier_goal_ids") == list(READY_FRONTIER)
        and isinstance(statuses, dict)
        and statuses.get(TARGET_GOAL_ID) == "IN_PROGRESS"
        and [goal_id for goal_id, status in statuses.items() if status == "IN_PROGRESS"]
        == [TARGET_GOAL_ID],
        "FP008 seq47 active runtime differs",
    )
    blockers = state.get("blockers_by_goal")
    _require(
        state.get("blocked_goal_ids") == []
        and state.get("pending_questions") == []
        and state.get("open_question_count") == 0
        and (not isinstance(blockers, dict) or not blockers.get(TARGET_GOAL_ID)),
        "FP008 seq47 has an unresolved blocker or question",
    )
    current = checkpoint.get("current_work")
    _require(
        isinstance(current, dict)
        and current.get("work_item_id") == start_gate.WORK_ITEM_ID
        and current.get("status") == "IN_PROGRESS"
        and current.get("release_completion_claimed") is False,
        "FP008 seq47 current-work pointer differs",
    )
    return SOURCE_READY_EVENT_SHA256, start_gate._parse_timestamp(
        started.get("occurred_at"),
        label="FP008 seq47 occurred_at",
    )


def load_gate_context(
    root: Path,
    retained_contents: Mapping[Path, bytes] | None = None,
) -> start_gate.GateContext:
    root = root.resolve(strict=True)
    if retained_contents is not None:
        _require(
            set(retained_contents) == set(start_gate.SOURCE_GUARD_RELATIVES),
            "retained resume source membership differs",
        )
    checks, contract_value = _load_resume_contract(
        root,
        retained_content=(
            retained_contents[start_gate.CONTRACT_RELATIVE]
            if retained_contents is not None
            else None
        ),
    )
    contract_binding = start_gate.expected_contract_binding()
    _require(
        contract_binding["canonical_contract_sha256"]
        == start_gate.canonical_sha256(contract_value),
        "FP008 resume contract canonical SHA-256 differs",
    )
    checkpoint_bytes, checkpoint = start_gate._load_checkpoint(
        root,
        retained_content=(
            retained_contents[start_gate.CHECKPOINT_RELATIVE]
            if retained_contents is not None
            else None
        ),
    )
    ready_sha256, source_occurred_at = _validate_resume_source(
        checkpoint,
        contract_binding=contract_binding,
    )
    if retained_contents is None:
        continuation_errors = continuation.validate(root)
        _require(
            not continuation_errors,
            "FP008 resume source continuation failed: " + "; ".join(continuation_errors),
        )
    _require(
        start_gate.sha256_bytes(
            retained_contents[start_gate.MANIFEST_RELATIVE]
            if retained_contents is not None
            else start_gate.repo_file(root, start_gate.MANIFEST_RELATIVE).read_bytes()
        )
        == start_gate.MANIFEST_SHA256,
        "v2.4 manifest SHA-256 differs",
    )
    _require(
        start_gate.sha256_bytes(
            retained_contents[start_gate.GOAL_RELATIVE]
            if retained_contents is not None
            else start_gate.repo_file(root, start_gate.GOAL_RELATIVE).read_bytes()
        )
        == TARGET_GOAL_SHA256,
        "FP008 Goal SHA-256 differs",
    )
    history = checkpoint["goal_execution"]["transition_history"]
    activations = [
        event
        for event in history
        if isinstance(event, dict)
        and event.get("event_type") == "PACKAGE_ACTIVATED"
        and event.get("static_plan_manifest_sha256") == start_gate.MANIFEST_SHA256
    ]
    _require(len(activations) == 1, "v2.4 activation event is missing or ambiguous")
    activation_sha256 = activations[0].get("event_sha256")
    _require(
        isinstance(activation_sha256, str)
        and start_gate.SHA256_RE.fullmatch(activation_sha256) is not None,
        "v2.4 activation event SHA-256 is invalid",
    )
    runtime_bindings = tuple(
        (
            relative.as_posix(),
            start_gate.sha256_bytes(
                retained_contents[relative]
                if retained_contents is not None
                else start_gate.repo_file(root, relative).read_bytes()
            ),
        )
        for relative in start_gate.RUNTIME_BINDING_RELATIVES
    )
    return start_gate.GateContext(
        checks=checks,
        checkpoint_sha256=start_gate.sha256_bytes(checkpoint_bytes),
        target_goal_sha256=TARGET_GOAL_SHA256,
        source_activation_event_sha256=activation_sha256,
        source_ready_event_sha256=ready_sha256,
        source_ready_occurred_at=source_occurred_at,
        contract_binding=contract_binding,
        runtime_bindings=runtime_bindings,
    )


@contextmanager
def _resume_adapter() -> Iterator[None]:
    replacements = {
        "GATE_PURPOSE": GATE_PURPOSE,
        "RECEIPT_NAME": RECEIPT_NAME,
        "RECEIPT_STAGE_NAME": RECEIPT_STAGE_NAME,
        "load_gate_context": load_gate_context,
        "_load_gate_contract": _load_resume_contract,
        "_document_id": _document_id,
    }
    with _ADAPTER_LOCK:
        originals = {name: getattr(start_gate, name) for name in replacements}
        try:
            for name, value in replacements.items():
                setattr(start_gate, name, value)
            yield
        finally:
            for name, value in originals.items():
                setattr(start_gate, name, value)


def _expected_log_names() -> set[str]:
    return {
        f"{index:02d}-{check_id}.log"
        for index, check_id in enumerate(start_gate.EXPECTED_CHECK_IDS, start=1)
    }


def _require_current_repository_exact(
    root: Path,
    event_id: str,
    recorded_payload: dict[str, Any],
    repository_guard: start_gate.RetainedRepositoryAuthorityGuard,
) -> None:
    try:
        current_payload = repository_guard.capture_state(
            root / start_gate.CHECKPOINT_RELATIVE,
            event_id,
            capture=start_gate.capture_repository_state,
        )
    except BaseException as exc:
        raise GatePostCommitUncertain(
            "published resume current repository capture is uncertain"
        ) from exc
    _require(
        start_gate.canonical_json_bytes(current_payload)
        == start_gate.canonical_json_bytes(recorded_payload),
        "published resume repository state differs from the current full repository",
    )


def _fsync_completed_evidence(
    guard: start_gate._RetainedEventEvidenceGuard,
) -> None:
    try:
        guard.fsync_event_and_parent()
    except BaseException as exc:
        raise GatePostCommitUncertain(
            "published resume evidence durability is uncertain"
        ) from exc


def _validate_completed_attempt_retained(
    root: Path,
    event_dir: Path,
    event_id: str,
    guard: start_gate._RetainedEventEvidenceGuard,
    source_guard: start_gate._RetainedSourceGuard,
    repository_guard: start_gate.RetainedRepositoryAuthorityGuard,
) -> Path:
    try:
        source_guard.verify()
        guard.verify()
        repository_guard.verify()
    except BaseException as exc:
        raise GatePostCommitUncertain(
            "published resume retained boundary verification is uncertain"
        ) from exc
    receipt_bytes = guard.content(RECEIPT_NAME)
    try:
        receipt = json.loads(receipt_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("published resume receipt is not valid JSON") from exc
    _require(
        isinstance(receipt, dict)
        and set(receipt) == start_gate.RECEIPT_FIELDS
        and receipt.get("schema_version") == "1.1"
        and receipt.get("document_id") == _document_id(event_id)
        and receipt.get("evidence_type")
        == "IMPLEMENTATION_START_OR_RESUME_GATE"
        and receipt.get("gate_purpose") == GATE_PURPOSE
        and receipt.get("status") == "PASS"
        and receipt.get("package_id") == start_gate.PACKAGE_ID
        and receipt.get("target_transition_event_id") == event_id
        and receipt.get("target_goal_id") == TARGET_GOAL_ID,
        "published resume receipt identity differs",
    )
    context = load_gate_context(root, source_guard.contents)
    _require(
        receipt.get("target_goal_content_sha256")
        == context.target_goal_sha256
        and receipt.get("static_plan_manifest_sha256")
        == start_gate.MANIFEST_SHA256
        and receipt.get("source_activation_event_sha256")
        == context.source_activation_event_sha256
        and receipt.get("source_checkpoint_sha256") == context.checkpoint_sha256
        and receipt.get("source_ready_event_sha256")
        == context.source_ready_event_sha256
        and receipt.get("check_command_contract_version")
        == context.contract_binding["contract_version"]
        and receipt.get("check_command_contract_sha256")
        == context.contract_binding["canonical_contract_sha256"]
        and receipt.get("implementation_start_gate_contract_binding")
        == context.contract_binding
        and receipt.get("runtime_bindings")
        == [
            {"path": path, "file_sha256": digest}
            for path, digest in context.runtime_bindings
        ],
        "published resume receipt source binding differs",
    )
    expected_checks = [
        {"check_id": check_id, "command": command}
        for check_id, command in context.checks
    ]
    event_relative = start_gate.GATE_ROOT_RELATIVE / event_id
    retained_contents = {
        (event_relative / name).as_posix(): guard.content(name)
        for name in guard.expected_names
    }
    errors, repository_payload = continuation._validate_check_runs(
        root,
        label="v2.4 session_resume gate recovery",
        event_id=event_id,
        receipt=receipt,
        expected_checks=expected_checks,
        fp008_private_evidence=True,
        fp008_event_directory_identity=guard.event_identity[:2],
        fp008_receipt_name=RECEIPT_NAME,
        fp008_retained_contents=retained_contents,
    )
    _require(not errors, "published resume evidence differs: " + "; ".join(errors))
    _require(
        isinstance(repository_payload, dict),
        "published resume repository evidence is missing",
    )
    repository_run = receipt["check_runs"][-1]
    expected_snapshot = start_gate.repository_snapshot_from_payload(
        repository_payload,
        event_id=event_id,
        output_sha256=repository_run["output_sha256"],
    )
    _require(
        receipt.get("repository_snapshot") == expected_snapshot,
        "published resume receipt repository snapshot differs",
    )
    checkpoint = json.loads(
        source_guard.content(start_gate.CHECKPOINT_RELATIVE)
    )
    snapshot = checkpoint.get("working_tree_snapshot")
    repository = checkpoint.get("repository")
    controlled = repository_payload.get(
        "checkpoint_controlled_working_snapshot"
    )
    _require(
        isinstance(snapshot, dict)
        and isinstance(repository, dict)
        and isinstance(controlled, dict)
        and controlled.get("base_head") == repository.get("snapshot_base_head")
        and controlled.get("managed_changed_path_count")
        == snapshot.get("managed_changed_path_count")
        and controlled.get("path_set_sha256")
        == snapshot.get("path_set_sha256")
        and controlled.get("content_set_sha256")
        == snapshot.get("content_set_sha256"),
        "published resume repository state is not bound to the retained checkpoint",
    )
    try:
        source_guard.verify()
        guard.verify()
        repository_guard.verify()
    except BaseException as exc:
        raise GatePostCommitUncertain(
            "published resume pre-durability verification is uncertain"
        ) from exc
    _require_current_repository_exact(
        root,
        event_id,
        repository_payload,
        repository_guard,
    )
    try:
        source_guard.verify()
        guard.verify()
        repository_guard.verify()
    except BaseException as exc:
        raise GatePostCommitUncertain(
            "published resume terminal verification is uncertain"
        ) from exc
    _fsync_completed_evidence(guard)
    return event_dir / RECEIPT_NAME


def _validate_completed_attempt(
    root: Path,
    event_dir: Path,
    event_id: str,
) -> Path:
    event_relative = start_gate.GATE_ROOT_RELATIVE / event_id
    expected_names = _expected_log_names() | {RECEIPT_NAME}
    evidence_guard: start_gate._RetainedEventEvidenceGuard | None = None
    source_guard: start_gate._RetainedSourceGuard | None = None
    repository_guard: (
        start_gate.RetainedRepositoryAuthorityGuard | None
    ) = None
    recovered: Path | None = None
    terminal = False
    try:
        with start_gate._checkpoint_parent_shared_lock(root) as checkpoint_lock:
            primary: BaseException | None = None
            close_error: BaseException | None = None
            try:
                repository_guard = (
                    start_gate.RetainedRepositoryAuthorityGuard.capture(root)
                )
                source_guard = start_gate._RetainedSourceGuard.capture(
                    root,
                    start_gate.SOURCE_GUARD_RELATIVES,
                    checkpoint_lock=checkpoint_lock,
                )
                evidence_guard = start_gate._RetainedEventEvidenceGuard.capture(
                    root,
                    event_relative,
                    expected_names,
                )
                recovered = _validate_completed_attempt_retained(
                    root,
                    event_dir,
                    event_id,
                    evidence_guard,
                    source_guard,
                    repository_guard,
                )
                terminal = True
                source_guard.verify()
                evidence_guard.verify()
                repository_guard.verify()
            except BaseException as exc:
                primary = exc
                raise
            finally:
                if evidence_guard is not None:
                    try:
                        evidence_guard.close(primary)
                    except BaseException as exc:
                        close_error = exc
                if source_guard is not None:
                    try:
                        if primary is None and close_error is None:
                            source_guard.close()
                        else:
                            source_guard.close(primary or close_error)
                    except BaseException as exc:
                        if close_error is None:
                            close_error = exc
                if repository_guard is not None:
                    try:
                        if primary is None and close_error is None:
                            repository_guard.close()
                        else:
                            repository_guard.close(primary or close_error)
                    except BaseException as exc:
                        if close_error is None:
                            close_error = exc
                if primary is None and close_error is not None:
                    raise GatePostCommitUncertain(
                        "published resume recovery guard cleanup failed"
                    ) from close_error
    except GatePostCommitUncertain:
        raise
    except BaseException as exc:
        if terminal:
            raise GatePostCommitUncertain(
                "published resume recovery terminal lock cleanup failed"
            ) from exc
        raise
    _require(recovered is not None, "published resume recovery result is missing")
    return recovered


def _recover_existing_attempt(
    root: Path,
    event_id: str,
) -> Path | None:
    gate_root = start_gate.repo_directory(root, start_gate.GATE_ROOT_RELATIVE)
    event_dir = gate_root / event_id
    if not os.path.lexists(event_dir):
        return None
    identity = start_gate._directory_identity(event_dir)
    metadata = event_dir.lstat()
    _require(
        stat.S_IMODE(metadata.st_mode) == 0o700
        and metadata.st_uid == os.geteuid(),
        "existing resume attempt directory authority differs",
    )
    names = set(os.listdir(event_dir))
    if RECEIPT_NAME in names:
        _require(
            names == _expected_log_names() | {RECEIPT_NAME},
            "published resume attempt inventory differs",
        )
        return _validate_completed_attempt(root, event_dir, event_id)
    allowed = _expected_log_names() | {RECEIPT_STAGE_NAME}
    _require(
        names.issubset(allowed),
        "incomplete resume attempt inventory differs",
    )
    for name in names:
        entry = event_dir / name
        entry_metadata = entry.lstat()
        _require(
            stat.S_ISREG(entry_metadata.st_mode)
            and stat.S_IMODE(entry_metadata.st_mode) == 0o600
            and entry_metadata.st_uid == os.geteuid()
            and entry_metadata.st_nlink == 1
            and not entry.is_symlink(),
            f"incomplete resume attempt entry authority differs: {name}",
        )
    start_gate._require_directory_identity(event_dir, identity)
    _require(
        set(os.listdir(event_dir)) == names,
        "incomplete resume attempt changed during validation",
    )
    raise GateError(
        "incomplete resume attempt is add-only preserved; use an unused "
        "subsequent FP008 resume event ID"
    )


def run_gate(root: Path, event_id: str, **kwargs: Any) -> Path:
    _document_id(event_id)
    event_date = _event_date(event_id)
    root = root.resolve(strict=True)
    with _resume_adapter():
        recovered = _recover_existing_attempt(root, event_id)
        if recovered is not None:
            return recovered
    clock = kwargs.pop(
        "clock",
        lambda: datetime.now(ZoneInfo("Asia/Seoul")),
    )
    first_clock_value = clock()
    _require(
        isinstance(first_clock_value, datetime)
        and first_clock_value.tzinfo is not None
        and first_clock_value.utcoffset() is not None,
        "resume gate clock must return a timezone-aware datetime",
    )
    _require(
        first_clock_value.astimezone(ZoneInfo("Asia/Seoul")).date()
        == event_date,
        "resume gate KST execution date must match the event ID date",
    )
    first_pending = True
    clock_call_count = 0
    previous_effective_value: datetime | None = None
    second_precision_calls = {
        1,
        len(start_gate.EXPECTED_CHECK_IDS) + 2,
        len(start_gate.EXPECTED_CHECK_IDS) + 3,
    }

    def retained_clock() -> datetime:
        nonlocal first_pending, clock_call_count, previous_effective_value
        if first_pending:
            first_pending = False
            value = first_clock_value
        else:
            value = clock()
        _require(
            isinstance(value, datetime)
            and value.tzinfo is not None
            and value.utcoffset() is not None
            and value.astimezone(ZoneInfo("Asia/Seoul")).date()
            == event_date,
            "resume gate clock crossed the event ID KST date boundary",
        )
        clock_call_count += 1
        if clock_call_count in second_precision_calls:
            effective_value = value.replace(microsecond=0)
            if (
                previous_effective_value is not None
                and effective_value <= previous_effective_value
            ):
                effective_value = previous_effective_value.replace(microsecond=0)
                if effective_value <= previous_effective_value:
                    effective_value += timedelta(seconds=1)
        else:
            effective_value = value
            if (
                previous_effective_value is not None
                and effective_value <= previous_effective_value
            ):
                effective_value = previous_effective_value + timedelta(
                    microseconds=1
                )
        _require(
            effective_value.astimezone(ZoneInfo("Asia/Seoul")).date()
            == event_date,
            "resume gate timestamp adjustment crossed the event ID KST date",
        )
        previous_effective_value = effective_value
        return value

    with _resume_adapter():
        return start_gate.run_gate(
            root,
            event_id,
            clock=retained_clock,
            **kwargs,
        )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-id", default=EVENT_ID)
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def _write_raw_exact(stream: Any, content: bytes) -> None:
    descriptor = stream.fileno()
    if (
        isinstance(descriptor, bool)
        or not isinstance(descriptor, int)
        or descriptor < 0
    ):
        raise OSError("output stream descriptor is invalid")
    offset = 0
    while offset < len(content):
        written = os.write(descriptor, content[offset:])
        if (
            isinstance(written, bool)
            or not isinstance(written, int)
            or written <= 0
            or written > len(content) - offset
        ):
            raise OSError("raw output write made invalid progress")
        offset += written


def _write_pass_result(receipt_path: Path) -> None:
    try:
        output = f"FP-008 session-resume gate: PASS: {receipt_path}\n".encode(
            "utf-8"
        )
        _write_raw_exact(sys.stdout, output)
    except BaseException as exc:
        raise GatePostCommitUncertain(
            "published or recovered receipt PASS output delivery is uncertain"
        ) from exc


def _write_postcommit_diagnostic(message: str) -> None:
    try:
        _write_raw_exact(sys.stderr, f"{message}\n".encode("utf-8"))
    except BaseException:
        pass


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        receipt_path = run_gate(args.root, args.event_id)
        _write_pass_result(receipt_path)
    except GateCheckFailed as exc:
        print(f"FP-008 session-resume gate: FAIL: {exc}", file=sys.stderr)
        return exc.exit_code if exc.exit_code > 0 else 1
    except GatePostCommitUncertain as exc:
        _write_postcommit_diagnostic(
            f"FP-008 session-resume gate: POSTCOMMIT-UNCERTAIN: {exc}"
        )
        return 2
    except (GateError, OSError, ValueError) as exc:
        print(f"FP-008 session-resume gate: ERROR: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
