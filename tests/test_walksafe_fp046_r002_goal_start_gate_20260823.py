from __future__ import annotations

from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess

import pytest

from scripts import (
    apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824
    as correction,
)
from scripts import (
    build_walksafe_fp046_r002_seq78_79_recovery_review_20260824 as review,
)
from scripts import run_walksafe_fp046_r002_goal_start_gate_20260823 as gate


ROOT = Path(__file__).resolve().parents[1]


def _seq84_source() -> dict[str, object]:
    live = json.loads((ROOT / correction.CHECKPOINT_RELATIVE).read_bytes())
    source = json.loads(
        correction.reconstructed_seq83_checkpoint_bytes(
            ROOT,
            live["goal_execution"]["transition_history"][82],
        )
    )
    assert len(source["goal_execution"]["transition_history"]) == 83
    paths = correction.exact_seq84_managed_paths(source)
    assert len(paths) == 1020
    projected, _ = correction.project_seq84(
        source,
        managed_paths=paths,
        path_set_sha256=hashlib.sha256(
            ("\n".join(paths) + "\n").encode()
        ).hexdigest(),
        content_set_sha256="a" * 64,
        authorization_binding={
            "path": correction.AUTHORIZATION_RELATIVE.as_posix(),
            "sha256": correction.AUTHORIZATION_SHA256,
            "byte_length": correction.AUTHORIZATION_BYTE_COUNT,
        },
        transition_review_binding={},
        event_occurred_at=(
            datetime.fromisoformat(
                source["goal_execution"]["transition_history"][-1][
                    "occurred_at"
                ]
            )
            + timedelta(seconds=1)
        ).isoformat(),
        runner_binding={},
        failed_gate_binding=review.passed_gate_attempt_004_binding(ROOT),
    )
    return projected


def _write_burned_recovery_namespaces(root: Path) -> None:
    empty = root / gate.GATE_ROOT_RELATIVE / gate.FAILED_RECOVERY_STARTED_EVENT_ID
    empty.mkdir(parents=True, exist_ok=True)
    empty.chmod(0o700)
    logged = root / review.FAILED_GATE_003_DIR
    logged.mkdir(exist_ok=True)
    logged.chmod(0o700)
    log = root / review.FAILED_GATE_003_LOG_REL
    log.write_bytes((ROOT / review.FAILED_GATE_003_LOG_REL).read_bytes())
    log.chmod(0o600)


def _write_passed_gate_004_namespace(root: Path) -> None:
    directory = root / review.PASSED_GATE_004_DIR
    if directory.exists():
        assert review.passed_gate_attempt_004_binding(root) == (
            review.STORED_PASSED_GATE_ATTEMPT_004
        )
        return
    directory.mkdir(parents=True, exist_ok=False)
    directory.chmod(0o700)
    for relative, (sha256, byte_count) in review.PASSED_GATE_004_FILE_PINS.items():
        raw = (ROOT / relative).read_bytes()
        assert len(raw) == byte_count
        assert hashlib.sha256(raw).hexdigest() == sha256
        path = root / relative
        path.write_bytes(raw)
        path.chmod(0o600)


def _write_fp022_private_logs(root: Path) -> None:
    directory = root / gate.FP022_PRIVATE_LOG_RELATIVES[0].parent
    directory.mkdir(mode=0o700)
    directory.chmod(0o700)
    for relative, (byte_count, sha256) in gate.FP022_PRIVATE_LOG_PINS.items():
        raw = (ROOT / relative).read_bytes()
        assert len(raw) == byte_count
        assert hashlib.sha256(raw).hexdigest() == sha256
        path = root / relative
        path.write_bytes(raw)
        path.chmod(0o600)


def _copy_live_git_fixture(destination: Path) -> None:
    subprocess.run(
        [
            "git",
            "clone",
            "--shared",
            "--no-checkout",
            "--quiet",
            "--",
            str(ROOT),
            str(destination),
        ],
        check=True,
    )
    live_index = Path(
        subprocess.run(
            ["git", "rev-parse", "--path-format=absolute", "--git-path", "index"],
            cwd=ROOT,
            check=True,
            stdout=subprocess.PIPE,
            text=True,
        ).stdout.strip()
    )
    fixture_index = destination / ".git" / "index"
    subprocess.run(
        [
            "cp",
            "--reflink=auto",
            "--preserve=mode,timestamps",
            "--",
            str(live_index),
            str(fixture_index),
        ],
        check=True,
    )
    raw_paths = subprocess.run(
        [
            "git",
            "ls-files",
            "--cached",
            "--others",
            "--exclude-standard",
            "-z",
        ],
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
    ).stdout
    relatives = []
    for raw in raw_paths.rstrip(b"\0").split(b"\0") if raw_paths else ():
        relative = Path(os.fsdecode(raw))
        try:
            (ROOT / relative).lstat()
        except FileNotFoundError:
            continue
        relatives.append(relative.as_posix())
    for offset in range(0, len(relatives), 400):
        subprocess.run(
            [
                "cp",
                "-P",
                "--reflink=auto",
                "--preserve=mode,timestamps",
                "--parents",
                "--",
                *relatives[offset : offset + 400],
                str(destination),
            ],
            cwd=ROOT,
            check=True,
        )


def _publish_seq84_correction(repository: Path) -> Path:
    checkpoint = repository / gate.CHECKPOINT_RELATIVE
    copied_checkpoint = json.loads(checkpoint.read_bytes())
    copied_history = copied_checkpoint["goal_execution"]["transition_history"]
    if len(copied_history) >= correction.CONTROL_CORRECTION_SEQUENCE:
        seq83_bytes = correction.reconstructed_seq83_checkpoint_bytes(
            repository,
            copied_history[correction.SEQ83_CORRECTION_SEQUENCE - 1],
        )
        checkpoint.unlink()
        checkpoint.write_bytes(seq83_bytes)
        checkpoint.chmod(0o600)

    review_presence = tuple(
        (repository / relative).is_file() for relative in review.REVIEW_PATHS
    )
    assert review_presence in {
        (False, False, False),
        (True, False, False),
        (True, True, False),
        (True, True, True),
    }, "copied active recovery review artifacts violate add-only ordering"
    assigned_at = "2026-08-25T02:20:00+09:00"
    if not review_presence[0]:
        review.write_assignment(repository, assigned_at=assigned_at)
    assignment_raw = (repository / review.ASSIGNMENT_REL).read_bytes()
    assignment = json.loads(assignment_raw)
    context = review.prepare_review_context(repository)
    review.validate_assignment(assignment, assignment_raw, context)
    if not review_presence[1]:
        result_raw = review.build_review_result(
            assignment_raw,
            reviewed_at=assignment["assigned_at"],
            decision="APPROVED",
            findings={"blocking": [], "major_open": [], "minor_open": []},
        ).encode()
        review.publish_review_result_candidate(
            result_raw,
            repository,
            actor_id=review.REVIEWER_ID,
            actor_task=review.REVIEWER_TASK,
        )
    else:
        result_raw = (repository / review.RESULT_REL).read_bytes()
        review.validate_review_result_candidate(
            json.loads(result_raw),
            result_raw,
            assignment,
            assignment_raw,
            context,
        )
    if not review_presence[2]:
        independent_raw = review.build_independent_review(
            assignment_raw,
            result_raw,
        ).encode()
        review.publish_independent_review_candidate(
            independent_raw,
            repository,
            actor_id=review.REVIEWER_ID,
            actor_task=review.REVIEWER_TASK,
        )
    review.validate_post_review(repository)
    prepared = correction.prepare_projection(
        repository,
        run_external_validators=False,
    )
    correction.write_projection(prepared)
    assert prepared.event["sequence"] == 84
    return checkpoint


def test_recovery_gate_identity_and_burned_id_are_exact() -> None:
    assert gate.SOURCE_SEQUENCE == gate.CONTROL_CORRECTION_SEQUENCE == 84
    assert gate.SEQ78_CORRECTION_SEQUENCE == 78
    assert gate.SEQ79_CORRECTION_SEQUENCE == 79
    assert gate.SEQ80_CORRECTION_SEQUENCE == 80
    assert gate.SEQ81_CORRECTION_SEQUENCE == 81
    assert gate.SEQ82_CORRECTION_SEQUENCE == 82
    assert gate.SEQ83_CORRECTION_SEQUENCE == 83
    assert gate.SEQ78_CORRECTION_EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
        "FP046-R002-CORRECTION-20260824-001"
    )
    assert gate.CONTROL_CORRECTION_EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
        "FP046-R002-CORRECTION-20260824-007"
    )
    assert gate.STARTED_EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-001"
    )
    assert gate.FAILED_RECOVERY_STARTED_EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-002"
    )
    assert gate.FAILED_SECOND_RECOVERY_STARTED_EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-003"
    )
    assert gate.PASSED_RECOVERY_STARTED_EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-004"
    )
    assert gate.RECOVERY_STARTED_EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-005"
    )
    assert gate.BURNED_EVENT_IDS == frozenset(
        {
            gate.STARTED_EVENT_ID,
            gate.FAILED_RECOVERY_STARTED_EVENT_ID,
            gate.FAILED_SECOND_RECOVERY_STARTED_EVENT_ID,
            gate.PASSED_RECOVERY_STARTED_EVENT_ID,
        }
    )
    assert gate._impl.STARTED_EVENT_ID == gate.RECOVERY_STARTED_EVENT_ID


def test_active_gate_rejects_burned_and_accepts_only_recovery_id() -> None:
    assert gate._document_id(gate.STARTED_EVENT_ID).endswith("20260823-001")
    assert gate._document_id(gate.FAILED_RECOVERY_STARTED_EVENT_ID).endswith(
        "20260823-002"
    )
    assert gate._document_id(
        gate.FAILED_SECOND_RECOVERY_STARTED_EVENT_ID
    ).endswith("20260823-003")
    assert gate._document_id(gate.PASSED_RECOVERY_STARTED_EVENT_ID).endswith(
        "20260823-004"
    )
    assert gate._recovery_document_id(gate.RECOVERY_STARTED_EVENT_ID).endswith(
        "20260823-005"
    )
    for burned in gate.BURNED_EVENT_IDS:
        with pytest.raises(gate.GateError, match="immutable prior gate attempt"):
            gate._recovery_document_id(burned)
    for invalid in (
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-006",
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-2",
        "wrong",
    ):
        with pytest.raises(gate.GateError):
            gate._recovery_document_id(invalid)


def test_contract_remains_byte_exact_with_ordered_five_checks() -> None:
    raw = (ROOT / gate.CONTRACT_RELATIVE).read_bytes()
    checks, contract = gate._load_gate_contract(ROOT)
    assert hashlib.sha256(raw).hexdigest() == gate.CONTRACT_FILE_SHA256
    assert gate.canonical_sha256(contract) == gate.CONTRACT_CANONICAL_SHA256
    assert tuple(check_id for check_id, _ in checks) == (
        "CONTINUATION",
        "GOAL_GRAPH",
        "TEST_LAYER_REGISTRY_VALIDATE",
        "ROOT_FP046_R002_CONTROL_REGRESSION",
        "REPOSITORY_STATE",
    )
    regression = dict(checks)["ROOT_FP046_R002_CONTROL_REGRESSION"]
    assert gate.ROOT_CONTROL_TEST_RELATIVE.as_posix() in regression
    assert "tests/test_apply_walksafe_fp046_r002_goal_started_seq78_20260823.py" in regression
    assert " && " in regression and " -k " not in regression
    assert contract["claim_boundary"] == gate.CONTRACT_CLAIM_BOUNDARY


def test_source_guard_uses_recovery_review_without_live_session_artifacts() -> None:
    assert gate.CONTROL_CORRECTION_RELATIVE in gate.SOURCE_GUARD_RELATIVES
    assert gate.CONTROL_CORRECTION_TEST_RELATIVE in gate.SOURCE_GUARD_RELATIVES
    assert gate.START_APPLY_RELATIVE in gate.SOURCE_GUARD_RELATIVES
    assert gate.START_APPLY_TEST_RELATIVE in gate.SOURCE_GUARD_RELATIVES
    assert gate.REVIEW_DIRECTORY_RELATIVE == review.REVIEW_DIR
    assert gate.AUTHORIZATION_RELATIVE == review.AUTHORIZATION_REL
    assert gate.REVIEW_RECORD_RELATIVES == review.REVIEW_PATHS
    assert gate.PRESERVED_REVIEW_RECORD_RELATIVES == review.PRESERVED_REVIEW_PATHS
    assert gate.SESSION_ARTIFACT_RELATIVES == ()
    assert set(gate.FP022_PRIVATE_LOG_RELATIVES).issubset(
        gate.SOURCE_GUARD_RELATIVES
    )
    for relative, (byte_count, sha256) in gate.FP022_PRIVATE_LOG_PINS.items():
        raw = (ROOT / relative).read_bytes()
        assert len(raw) == byte_count
        assert hashlib.sha256(raw).hexdigest() == sha256
        assert stat.S_IMODE((ROOT / relative).stat().st_mode) == 0o600
    assert set(review.PRESERVED_SESSION_ARTIFACT_PATHS).isdisjoint(
        gate.SOURCE_GUARD_RELATIVES
    )
    assert len(set(gate.SOURCE_GUARD_RELATIVES)) == len(
        gate.SOURCE_GUARD_RELATIVES
    )


def test_checkpoint_evidence_scanner_accepts_exact_failed_attempt_witness() -> None:
    live = json.loads((ROOT / gate.CHECKPOINT_RELATIVE).read_bytes())
    raw = correction.reconstructed_seq79_checkpoint_bytes(
        ROOT,
        live["goal_execution"]["transition_history"][78],
    )
    checkpoint = json.loads(raw)
    assert len(checkpoint["goal_execution"]["transition_history"]) == 79
    event_directories, bound_paths = (
        gate._checkpoint_bound_gate_event_directories(raw)
    )
    assert review.FAILED_GATE_DIR in event_directories
    assert review.FAILED_GATE_LOG_REL in bound_paths
    assert (
        gate.GATE_ROOT_RELATIVE / gate.FAILED_SECOND_RECOVERY_STARTED_EVENT_ID
        not in event_directories
    )


def test_burned_empty_gate_namespace_is_exact_and_cannot_be_reused(
    tmp_path: Path,
) -> None:
    relative = (
        gate.GATE_ROOT_RELATIVE / gate.FAILED_RECOVERY_STARTED_EVENT_ID
    )
    directory = tmp_path / relative
    directory.mkdir(parents=True)
    directory.chmod(0o700)
    gate._require_burned_empty_gate_namespace(tmp_path)

    (directory / "unexpected.log").write_bytes(b"not empty\n")
    with pytest.raises(
        gate.GateError,
        match="burned -002 gate namespace authority differs",
    ):
        gate._require_burned_empty_gate_namespace(tmp_path)


def test_burned_logged_gate_namespace_is_exact_and_cannot_be_reused(
    tmp_path: Path,
) -> None:
    _write_burned_recovery_namespaces(tmp_path)
    gate._require_burned_logged_gate_namespace(tmp_path)
    log = tmp_path / review.FAILED_GATE_003_LOG_REL
    log.write_bytes(b"tampered\n")
    with pytest.raises(
        gate.GateError,
        match="burned -003 gate namespace authority differs",
    ):
        gate._require_burned_logged_gate_namespace(tmp_path)


def test_seq84_scanner_retains_prior_gate_evidence_and_omits_directories(
    tmp_path: Path,
) -> None:
    source = _seq84_source()
    _write_burned_recovery_namespaces(tmp_path)
    _write_passed_gate_004_namespace(tmp_path)
    raw = json.dumps(source, ensure_ascii=False, separators=(",", ":")).encode()
    token = gate._GATE_RUN_ROOT.set(tmp_path)
    try:
        event_directories, bound_paths = (
            gate._checkpoint_bound_gate_event_directories(raw)
        )
    finally:
        gate._GATE_RUN_ROOT.reset(token)
    assert review.FAILED_GATE_DIR in event_directories
    assert review.FAILED_GATE_LOG_REL in bound_paths
    assert (
        gate.GATE_ROOT_RELATIVE / gate.FAILED_RECOVERY_STARTED_EVENT_ID
        not in event_directories
    )
    assert review.FAILED_GATE_003_DIR in event_directories
    assert review.FAILED_GATE_003_LOG_REL in bound_paths
    assert review.PASSED_GATE_004_DIR in event_directories
    assert set(review.PASSED_GATE_004_FILE_PINS).issubset(bound_paths)


@pytest.mark.parametrize("mutation", ("directory", "log_parent", "log_sha256"))
def test_seq84_scanner_rejects_nonexact_logged_003_binding(
    tmp_path: Path,
    mutation: str,
) -> None:
    source = _seq84_source()
    _write_burned_recovery_namespaces(tmp_path)
    _write_passed_gate_004_namespace(tmp_path)
    failed = source["goal_execution"]["transition_history"][82][
        "source_checkpoint_binding"
    ]["failed_gate_attempt"]
    tampered = gate.GATE_ROOT_RELATIVE / "TAMPERED-003"
    if mutation == "directory":
        failed["directory"] = tampered.as_posix()
    elif mutation == "log_parent":
        failed["only_log_binding"]["path"] = (
            tampered / "01-CONTINUATION.log"
        ).as_posix()
    else:
        failed["only_log_binding"]["sha256"] = "0" * 64
    raw = json.dumps(source, ensure_ascii=False, separators=(",", ":")).encode()
    token = gate._GATE_RUN_ROOT.set(tmp_path)
    try:
        with pytest.raises(
            gate.GateError,
            match="checkpoint gate evidence reference is malformed",
        ):
            gate._checkpoint_bound_gate_event_directories(raw)
    finally:
        gate._GATE_RUN_ROOT.reset(token)


@pytest.mark.parametrize("mutation", ("directory", "file_parent", "file_sha256"))
def test_seq84_scanner_rejects_nonexact_passed_004_binding(
    tmp_path: Path,
    mutation: str,
) -> None:
    source = _seq84_source()
    _write_burned_recovery_namespaces(tmp_path)
    passed = source["goal_execution"]["transition_history"][-1][
        "source_checkpoint_binding"
    ]["passed_gate_attempt_004"]
    tampered = gate.GATE_ROOT_RELATIVE / "TAMPERED-004"
    if mutation == "directory":
        passed["directory"] = tampered.as_posix()
    elif mutation == "file_parent":
        passed["files"][0]["path"] = (
            tampered / "01-CONTINUATION.log"
        ).as_posix()
    else:
        passed["files"][0]["sha256"] = "0" * 64
    raw = json.dumps(source, ensure_ascii=False, separators=(",", ":")).encode()
    token = gate._GATE_RUN_ROOT.set(tmp_path)
    try:
        with pytest.raises(
            gate.GateError,
            match="checkpoint gate evidence reference is malformed",
        ):
            gate._checkpoint_bound_gate_event_directories(raw)
    finally:
        gate._GATE_RUN_ROOT.reset(token)


@pytest.mark.parametrize(
    "mutation",
    ("directory", "log_parent", "arbitrary_key"),
)
def test_checkpoint_evidence_scanner_rejects_nonexact_directory_witness(
    mutation: str,
) -> None:
    live = json.loads((ROOT / gate.CHECKPOINT_RELATIVE).read_bytes())
    checkpoint = json.loads(
        correction.reconstructed_seq79_checkpoint_bytes(
            ROOT,
            live["goal_execution"]["transition_history"][78],
        )
    )
    failed = checkpoint["goal_execution"]["transition_history"][78][
        "source_checkpoint_binding"
    ]["failed_gate_attempt"]
    tampered_directory = (
        gate.GATE_ROOT_RELATIVE
        / "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-TAMPERED"
    )
    if mutation == "directory":
        failed["directory"] = tampered_directory.as_posix()
    elif mutation == "log_parent":
        failed["only_log_binding"]["path"] = (
            tampered_directory / "01-CONTINUATION.log"
        ).as_posix()
    else:
        checkpoint["unexpected_gate_reference"] = (
            review.FAILED_GATE_LOG_REL.as_posix()
        )
    raw = json.dumps(checkpoint, ensure_ascii=False, separators=(",", ":")).encode()
    with pytest.raises(
        gate.GateError,
        match="checkpoint gate evidence reference is malformed",
    ):
        gate._checkpoint_bound_gate_event_directories(raw)


def test_seq84_source_accepts_only_exact_correction_evidence_refs() -> None:
    checkpoint = _seq84_source()
    correction_event = checkpoint["goal_execution"]["transition_history"][83]
    assert correction.validate_history_suffix(
        ROOT,
        checkpoint,
        require_live_snapshot=False,
    ) == correction_event
    gate._validate_ready_source(
        checkpoint,
        contract_binding=gate.expected_contract_binding(),
    )
    correction_event["evidence_refs"].append("UNSEALED_REFERENCE")
    with pytest.raises(
        correction.ControlCorrectionError,
        match="seq84 correction event differs",
    ):
        correction.validate_history_suffix(
            ROOT,
            checkpoint,
            require_live_snapshot=False,
        )


def test_exact_seq84_correction_is_gate_source() -> None:
    source = _seq84_source()
    correction_event = source["goal_execution"]["transition_history"][-1]
    ready_sha256, occurred_at = gate._validate_ready_source(
        source,
        contract_binding=gate.expected_contract_binding(),
    )
    assert ready_sha256 == gate.SOURCE_READY_EVENT_SHA256
    assert occurred_at.isoformat() == correction_event["occurred_at"]
    assert gate._SOURCE_CORRECTION_SHA256.get() == correction_event["event_sha256"]


@pytest.mark.parametrize(
    ("mutator", "message"),
    (
        (
            lambda source: source["goal_execution"]["transition_history"][75].update(
                {"event_sha256": "0" * 64}
            ),
            "seq76 READY event differs",
        ),
        (
            lambda source: source["goal_execution"]["transition_history"][-1][
                "contract_supersession"
            ].update({"reason_code": "UNBOUND"}),
            "seq84 start-control correction differs",
        ),
        (
            lambda source: source["goal_execution"].update(
                {"transition_history_anchor_sha256": "0" * 64}
            ),
            "seq84 history anchor differs",
        ),
    ),
)
def test_seq84_source_tampering_is_rejected(mutator: object, message: str) -> None:
    source = _seq84_source()
    assert callable(mutator)
    mutator(source)
    with pytest.raises(gate.GateError, match=message):
        gate._validate_ready_source(
            source,
            contract_binding=gate.expected_contract_binding(),
        )


def test_gate_context_binds_correction_but_retains_ready_anchor(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _seq84_source()
    correction_sha256 = source["goal_execution"]["transition_history"][-1][
        "event_sha256"
    ]
    base = gate.GateContext(
        checks=(),
        checkpoint_sha256="b" * 64,
        target_goal_sha256=gate.TARGET_GOAL_SHA256,
        source_activation_event_sha256="c" * 64,
        source_ready_event_sha256=gate.SOURCE_READY_EVENT_SHA256,
        source_ready_occurred_at=datetime.fromisoformat(
            "2026-08-24T00:00:01+09:00"
        ),
        contract_binding={},
        runtime_bindings=(),
    )

    def fake_load(_root: Path, _retained: object = None) -> gate.GateContext:
        gate._SOURCE_CORRECTION_SHA256.set(correction_sha256)
        return base

    monkeypatch.setattr(gate, "_base_load_gate_context", fake_load)
    context = gate.load_gate_context(tmp_path, require_live_snapshot=False)
    assert context.source_activation_event_sha256 == correction_sha256
    assert context.source_ready_event_sha256 == gate.SOURCE_READY_EVENT_SHA256


def test_checkpoint_loader_requires_burned_namespaces_only_on_live_root(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    checkpoint: dict[str, object] = {}
    monkeypatch.setattr(
        gate,
        "_base_load_checkpoint",
        lambda *_args, **_kwargs: (b"{}", checkpoint),
    )
    monkeypatch.setattr(
        correction,
        "require_control_corrected_checkpoint",
        lambda *_args, **_kwargs: None,
    )
    calls: list[str] = []
    monkeypatch.setattr(
        gate,
        "_require_burned_empty_gate_namespace",
        lambda _root: calls.append("-002"),
    )
    monkeypatch.setattr(
        gate,
        "_require_burned_logged_gate_namespace",
        lambda _root: calls.append("-003"),
    )
    token = gate._REQUIRE_LIVE_SNAPSHOT.set(False)
    try:
        assert gate._load_checkpoint(tmp_path) == (b"{}", checkpoint)
    finally:
        gate._REQUIRE_LIVE_SNAPSHOT.reset(token)
    assert calls == []
    gate._load_checkpoint(tmp_path)
    assert calls == ["-002", "-003"]


def test_preflight_runs_five_isolated_checks_without_creating_namespace(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    payload = {"repository": "exact"}
    repository_output = gate._impl.canonical_json_bytes(payload) + b"\n"
    workspace = tmp_path / "authority"
    (workspace / "runtime" / "tmp").mkdir(parents=True)
    (workspace / "commands").mkdir()
    (workspace / "home").mkdir()
    root_descriptor = os.open(tmp_path, os.O_RDONLY | os.O_DIRECTORY)
    calls: list[tuple[str, Path]] = []
    closed: list[bool] = []

    class RepositoryGuard:
        def __init__(self) -> None:
            self.workspace = workspace

        def capture_state(self, *_args: object, **_kwargs: object) -> dict:
            return payload

    class SourceGuard:
        contents = {}

    class Snapshot:
        def __init__(self) -> None:
            self.root_descriptor = root_descriptor
            self.repository_verified = False

        def verify(self) -> None:
            pass

        def verify_repository(self) -> None:
            self.repository_verified = True

    snapshot = Snapshot()

    class Resources:
        def __init__(self) -> None:
            self.repository_guard = RepositoryGuard()
            self.source_guard = SourceGuard()
            self.repository_authority = None
            self.snapshot = None

        def verify_source(self) -> None:
            pass

        def verify_all(self) -> None:
            if self.snapshot is not None:
                self.snapshot.verify_repository()

        def close(self, primary: BaseException | None = None) -> None:
            del primary
            os.close(root_descriptor)
            closed.append(True)

    resources = Resources()

    class Authority:
        cli_output = repository_output

        def require_exact(self, value: object, *, label: str) -> None:
            del label
            assert value == payload

    authority = Authority()
    checks = tuple((check_id, f"command-{check_id}") for check_id in gate.EXPECTED_CHECK_IDS)
    context = gate.GateContext(
        checks=checks,
        checkpoint_sha256="a" * 64,
        target_goal_sha256=gate.TARGET_GOAL_SHA256,
        source_activation_event_sha256="b" * 64,
        source_ready_event_sha256=gate.SOURCE_READY_EVENT_SHA256,
        source_ready_occurred_at=datetime.fromisoformat(
            "2026-08-24T00:00:01+09:00"
        ),
        contract_binding={},
        runtime_bindings=(),
    )
    monkeypatch.setattr(
        gate._impl._GateRunResources,
        "capture",
        lambda _root: resources,
    )
    monkeypatch.setattr(
        gate._impl._RepositoryStateAuthority,
        "capture",
        lambda *_args, **_kwargs: authority,
    )
    monkeypatch.setattr(gate, "load_gate_context", lambda *_args, **_kwargs: context)

    def runner(command: str, **kwargs: object) -> subprocess.CompletedProcess[str]:
        output = kwargs["stdout"]
        assert hasattr(output, "write")
        check_id = command.removeprefix("command-")
        content = (
            repository_output
            if check_id == "REPOSITORY_STATE"
            else f"{check_id}: PASS\n".encode()
        )
        output.write(content)
        calls.append((check_id, kwargs["cwd"]))
        return subprocess.CompletedProcess(command, 0)

    results = gate.preflight_gate(
        tmp_path,
        gate.RECOVERY_STARTED_EVENT_ID,
        process_runner=runner,
        repository_state_guard=lambda *_args: payload,
        isolated_snapshot_factory=lambda *_args, **_kwargs: snapshot,
    )
    assert tuple(result.check_id for result in results) == gate.EXPECTED_CHECK_IDS
    assert tuple(check_id for check_id, _ in calls) == gate.EXPECTED_CHECK_IDS
    assert all(str(cwd).startswith("/proc/self/fd/") for _, cwd in calls)
    assert snapshot.repository_verified
    assert closed == [True]
    assert not (
        tmp_path / gate.GATE_ROOT_RELATIVE / gate.RECOVERY_STARTED_EVENT_ID
    ).exists()


def test_production_retained_snapshot_runs_real_continuation_for_seq84(
    tmp_path: Path,
) -> None:
    if os.environ.get("WALKSAFE_GATE_EVENT_ID") == gate.RECOVERY_STARTED_EVENT_ID:
        pytest.skip("outer FP046 exact-five snapshot regression owns this check")
    repository = tmp_path / "repository"
    _copy_live_git_fixture(repository)
    _write_burned_recovery_namespaces(repository)
    _write_passed_gate_004_namespace(repository)
    _write_fp022_private_logs(repository)
    assert not (
        repository / gate.GATE_ROOT_RELATIVE / gate.RECOVERY_STARTED_EVENT_ID
    ).exists()

    checkpoint = _publish_seq84_correction(repository)
    expected_repository_output = (
        gate._impl.canonical_json_bytes(
            gate.capture_repository_state(
                repository,
                checkpoint,
                gate.RECOVERY_STARTED_EVENT_ID,
            )
        )
        + b"\n"
    )
    commands = dict(gate._load_gate_contract(repository)[0])
    check_id_by_command = {command: check_id for check_id, command in commands.items()}
    snapshot_checked: list[bool] = []
    repository_outputs: list[bytes] = []

    def runner(command: str, **kwargs: object) -> subprocess.CompletedProcess[bytes]:
        snapshot_root = Path(os.fspath(kwargs["cwd"]))
        if not snapshot_checked:
            assert stat.S_IMODE(snapshot_root.stat().st_mode) == 0o700
            assert stat.S_IMODE((snapshot_root / ".git").stat().st_mode) == 0o700
            empty_relative = (
                gate.GATE_ROOT_RELATIVE / gate.FAILED_RECOVERY_STARTED_EVENT_ID
            )
            assert (repository / empty_relative).is_dir()
            assert not (snapshot_root / empty_relative).exists()
            assert (
                snapshot_root / review.FAILED_GATE_003_LOG_REL
            ).read_bytes() == (ROOT / review.FAILED_GATE_003_LOG_REL).read_bytes()
            for relative, (sha256, byte_count) in (
                review.PASSED_GATE_004_FILE_PINS.items()
            ):
                raw = (snapshot_root / relative).read_bytes()
                assert len(raw) == byte_count
                assert hashlib.sha256(raw).hexdigest() == sha256
                assert stat.S_IMODE((snapshot_root / relative).stat().st_mode) == 0o600
            for relative, (byte_count, sha256) in gate.FP022_PRIVATE_LOG_PINS.items():
                raw = (snapshot_root / relative).read_bytes()
                assert len(raw) == byte_count
                assert hashlib.sha256(raw).hexdigest() == sha256
                assert stat.S_IMODE((snapshot_root / relative).stat().st_mode) == 0o600
            for current_text, names, _files in os.walk(snapshot_root):
                current = Path(current_text)
                names[:] = [name for name in names if name != ".git"]
                if current != snapshot_root:
                    relative = current.relative_to(snapshot_root)
                    assert stat.S_IMODE(current.stat().st_mode) == stat.S_IMODE(
                        (repository / relative).stat().st_mode
                    )
            assert not (
                snapshot_root
                / gate.GATE_ROOT_RELATIVE
                / gate.RECOVERY_STARTED_EVENT_ID
            ).exists()
            snapshot_checked.append(True)
        completed = subprocess.run(command, **kwargs)
        if completed.returncode != 0:
            output = kwargs["stdout"]
            assert hasattr(output, "fileno") and hasattr(output, "tell")
            captured = os.pread(output.fileno(), output.tell(), 0)
            pytest.fail(
                f"{check_id_by_command[command]} failed:\n"
                + captured[-12_000:].decode(errors="replace")
            )
        if check_id_by_command[command] == "REPOSITORY_STATE":
            output = kwargs["stdout"]
            assert hasattr(output, "fileno") and hasattr(output, "tell")
            repository_outputs.append(
                os.pread(output.fileno(), output.tell(), 0)
            )
        return completed

    results = gate.preflight_gate(
        repository,
        gate.RECOVERY_STARTED_EVENT_ID,
        process_runner=runner,
    )
    assert tuple(result.check_id for result in results) == gate.EXPECTED_CHECK_IDS
    assert snapshot_checked == [True]
    assert repository_outputs == [expected_repository_output]
    assert not (
        repository
        / gate.GATE_ROOT_RELATIVE
        / gate.RECOVERY_STARTED_EVENT_ID
    ).exists()


def test_disposable_seq84_gate_and_seq85_start_chain_passes_full_consumers(
    tmp_path: Path,
) -> None:
    if os.environ.get("WALKSAFE_GATE_EVENT_ID") == gate.RECOVERY_STARTED_EVENT_ID:
        pytest.skip("outer disposable FP046 start chain owns this check")
    from scripts import (
        apply_walksafe_fp046_r002_goal_started_seq79_20260824 as started,
    )

    repository = tmp_path / "repository"
    _copy_live_git_fixture(repository)
    _write_burned_recovery_namespaces(repository)
    _write_passed_gate_004_namespace(repository)
    _write_fp022_private_logs(repository)
    live_gate = ROOT / gate.GATE_ROOT_RELATIVE / gate.RECOVERY_STARTED_EVENT_ID
    temp_gate = repository / gate.GATE_ROOT_RELATIVE / gate.RECOVERY_STARTED_EVENT_ID
    assert not live_gate.exists()
    assert not temp_gate.exists()

    checkpoint = _publish_seq84_correction(repository)
    corrected = json.loads(checkpoint.read_bytes())
    assert len(corrected["goal_execution"]["transition_history"]) == 84
    assert corrected["goal_execution"]["transition_history"][-1][
        "event_id"
    ] == correction.CONTROL_CORRECTION_EVENT_ID

    receipt = gate.run_gate(repository, gate.RECOVERY_STARTED_EVENT_ID)
    assert receipt == temp_gate / gate.RECEIPT_NAME
    assert receipt.is_file()
    assert stat.S_IMODE(temp_gate.stat().st_mode) == 0o700
    assert {path.name for path in temp_gate.iterdir()} == {
        gate.RECEIPT_NAME,
        *(
            f"{index:02d}-{check_id}.log"
            for index, check_id in enumerate(gate.EXPECTED_CHECK_IDS, start=1)
        ),
    }

    stale = started.prepare_projection(
        repository,
        event_id=gate.RECOVERY_STARTED_EVENT_ID,
        allow_stale_catalogs=True,
    )
    started.write_catalogs(stale)
    prepared = started.prepare_projection(
        repository,
        event_id=gate.RECOVERY_STARTED_EVENT_ID,
    )
    assert prepared.catalogs_verified is True
    assert prepared.event["sequence"] == 85
    assert prepared.event["event_id"] == gate.RECOVERY_STARTED_EVENT_ID
    assert started.validate_projected_checkpoint(
        repository,
        prepared.projected_checkpoint,
    ) == []
    assert not live_gate.exists()


def test_main_classifies_pass_output_failure_as_postcommit(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    receipt = tmp_path / gate.RECEIPT_NAME
    monkeypatch.setattr(gate, "run_gate", lambda *_args, **_kwargs: receipt)
    diagnostics: list[bytes] = []

    def output(stream: object, content: bytes) -> None:
        del stream
        if b": PASS:" in content:
            raise BrokenPipeError("closed")
        diagnostics.append(content)

    monkeypatch.setattr(gate._impl, "_write_raw_exact", output)
    assert gate.main(
        ["--root", str(ROOT), "--event-id", gate.RECOVERY_STARTED_EVENT_ID]
    ) == 2
    assert len(diagnostics) == 1
    assert b"POSTCOMMIT-UNCERTAIN" in diagnostics[0]
