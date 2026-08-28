from __future__ import annotations

from pathlib import Path

import pytest

from scripts import apply_walksafe_fp046_r002_goal_started_seq78_20260823 as subject


ROOT = Path(__file__).resolve().parents[1]


def _seq77_source() -> dict:
    _, source = subject.reanchor.load_frozen_source_checkpoint(ROOT)
    paths = {
        Path(path)
        for path in source["working_tree_snapshot"]["managed_changed_paths"]
    }
    paths.update(subject.reanchor.REQUIRED_CONTROL_PATHS)
    final = {path: "0" * 64 for path in paths}
    successor = {
        "schema_version": "1.1",
        "document_id": subject.reanchor.R002_DOCUMENT_ID,
        "path": subject.reanchor.R002_CONTRACT_PATH.as_posix(),
        "file_sha256": "1" * 64,
        "contract_id": subject.reanchor.R002_CONTRACT_ID,
        "contract_version": subject.reanchor.R002_CONTRACT_VERSION,
        "canonical_contract_sha256": "2" * 64,
    }
    authorization = {
        "path": subject.reanchor.AUTHORIZATION_REL.as_posix(),
        "sha256": "3" * 64,
        "byte_length": 100,
    }
    runner = {
        "path": subject.reanchor.START_GATE_RUNNER_PATH.as_posix(),
        "sha256": "4" * 64,
        "byte_length": 100,
    }
    review = {
        role: {"path": path.as_posix(), "sha256": str(index) * 64, "byte_length": 100}
        for index, (role, path) in enumerate(
            zip(
                ("assignment", "review_result", "independent_review"),
                subject.reanchor.REVIEW_PATHS,
                strict=True,
            ),
            start=5,
        )
    }
    projected, _ = subject.reanchor.project(
        source,
        final,
        event_occurred_at="2026-08-23T00:00:01+09:00",
        successor_contract=successor,
        authorization=authorization,
        runner_binding=runner,
        transition_review=review,
    )
    return projected


def _evidence() -> subject.GateEvidence:
    return subject.GateEvidence(
        receipt={"repository_snapshot": {"sealed": True}},
        receipt_bytes=b"{}\n",
        receipt_binding={
            "document_id": subject.gate._document_id(subject.EVENT_ID),
            "path": (
                subject.gate.GATE_ROOT_RELATIVE
                / subject.EVENT_ID
                / subject.gate.RECEIPT_NAME
            ).as_posix(),
            "file_sha256": "9" * 64,
        },
        repository_payload={"sealed": True},
        event_occurred_at="2026-08-23T00:00:03+09:00",
    )


def _final_hashes(source: dict) -> dict[Path, str]:
    paths = {
        Path(path)
        for path in source["working_tree_snapshot"]["managed_changed_paths"]
    }
    paths.update({subject.SCRIPT_RELATIVE, subject.TEST_RELATIVE})
    return {path: "a" * 64 for path in paths}


def test_seq78_projection_starts_only_fp046_r002(monkeypatch) -> None:
    source = _seq77_source()
    monkeypatch.setattr(subject, "require_exact_source", lambda *_args, **_kwargs: None)
    projected, event = subject.project_seq78(
        ROOT,
        source,
        _evidence(),
        event_id=subject.EVENT_ID,
        final_sha256_by_path=_final_hashes(source),
    )
    assert set(event) == subject.EVENT_FIELDS
    assert event["event_id"] == subject.EVENT_ID
    assert event["from_status"] == "READY"
    assert event["to_status"] == "IN_PROGRESS"
    assert event["status_changes"] == {subject.gate.TARGET_GOAL_ID: "IN_PROGRESS"}
    assert projected["goal_execution"]["status_by_goal"][
        subject.gate.TARGET_GOAL_ID
    ] == "IN_PROGRESS"
    assert list(projected["goal_execution"]["status_by_goal"].values()).count(
        "IN_PROGRESS"
    ) == 1
    assert projected["approved_state"] == source["approved_state"]
    assert projected["verification_boundary"] == source["verification_boundary"]


def test_seq78_zero_credit_projection_distinguishes_boolean_from_integer(
    monkeypatch,
) -> None:
    source = _seq77_source()
    monkeypatch.setattr(subject, "require_exact_source", lambda *_args, **_kwargs: None)
    projected, _ = subject.project_seq78(
        ROOT,
        source,
        _evidence(),
        event_id=subject.EVENT_ID,
        final_sha256_by_path=_final_hashes(source),
    )
    assert type(source["goal_execution"]["open_question_count"]) is int
    projected["goal_execution"]["open_question_count"] = False
    with pytest.raises(
        subject.StartApplyError,
        match="zero-credit state: open_question_count",
    ):
        subject._assert_zero_credit_projection(source, projected)


def test_seq78_rejects_wrong_event_id(monkeypatch) -> None:
    source = _seq77_source()
    monkeypatch.setattr(subject, "require_exact_source", lambda *_args, **_kwargs: None)
    with pytest.raises(subject.StartApplyError, match="event ID differs"):
        subject.project_seq78(
            ROOT,
            source,
            _evidence(),
            event_id="wrong",
            final_sha256_by_path=_final_hashes(source),
        )


def test_main_reports_post_commit_uncertainty_without_retry(monkeypatch) -> None:
    prepared = type(
        "Prepared",
        (),
        {"event": {"event_sha256": "a" * 64, "occurred_at": "2026-08-23T00:00:03+09:00"}},
    )()
    calls = []
    monkeypatch.setattr(subject, "prepare_projection", lambda *_args, **_kwargs: prepared)

    def fail_once(value) -> None:
        calls.append(value)
        raise subject.atomic.CompletionPostCommitError("publication uncertain")

    monkeypatch.setattr(subject, "write_projection", fail_once)
    diagnostics = []
    monkeypatch.setattr(subject, "_write_postcommit_diagnostic", diagnostics.append)
    assert subject.main(
        ["--root", str(ROOT), "--event-id", subject.EVENT_ID, "--write"]
    ) == 2
    assert calls == [prepared]
    assert diagnostics == [
        "FP046-R002 GOAL_STARTED seq78: "
        "POSTCOMMIT-UNCERTAIN: publication uncertain"
    ]


def test_terminal_guard_failure_after_physical_seq78_write_is_postcommit(
    tmp_path: Path,
) -> None:
    checkpoint = tmp_path / "checkpoint.json"
    projected = b"published\n"
    checkpoint.write_bytes(projected)
    checkpoint.chmod(0o600)
    prepared = type(
        "Prepared",
        (),
        {
            "checkpoint_path": checkpoint,
            "projected_checkpoint_bytes": projected,
        },
    )()

    def final_guard() -> None:
        raise subject.StartApplyError("final guard failed")

    with pytest.raises(
        subject.atomic.CompletionPostCommitError,
        match="terminal validation failed",
    ):
        subject._verify_published_projection(prepared, final_guard)


def test_transport_cleanup_failure_after_seq78_publication_is_postcommit(
    tmp_path: Path, monkeypatch
) -> None:
    checkpoint = tmp_path / "checkpoint.json"
    source = b"source\n"
    projected = b"projected\n"
    checkpoint.write_bytes(source)
    checkpoint.chmod(0o600)
    flock_module = subject.atomic.atomic_write.__globals__["fcntl"]
    real_flock = flock_module.flock

    def fail_unlock(descriptor: int, operation: int) -> None:
        if operation == flock_module.LOCK_UN:
            raise OSError("unlock cleanup failed")
        real_flock(descriptor, operation)

    monkeypatch.setattr(flock_module, "flock", fail_unlock)
    with pytest.raises(
        subject.atomic.CompletionPostCommitError,
        match="unclassified failure",
    ):
        subject._write_projection_transport(
            subject.atomic.atomic_write,
            checkpoint,
            projected,
            expected_source=source,
            commit_guard=lambda: None,
        )
    assert checkpoint.read_bytes() == projected


def test_transport_failure_before_seq78_exchange_is_retry_safe(tmp_path: Path) -> None:
    checkpoint = tmp_path / "checkpoint.json"
    source = b"source\n"
    checkpoint.write_bytes(source)
    checkpoint.chmod(0o600)

    def fail_before_exchange(*_args, **_kwargs) -> None:
        raise OSError("exchange was not attempted")

    with pytest.raises(
        subject.atomic.CompletionApplyError,
        match="before checkpoint replacement",
    ):
        subject._write_projection_transport(
            fail_before_exchange,
            checkpoint,
            b"projected\n",
            expected_source=source,
            commit_guard=lambda: None,
        )
    assert checkpoint.read_bytes() == source


def test_transport_failure_after_seq78_exchange_is_postcommit(tmp_path: Path) -> None:
    checkpoint = tmp_path / "checkpoint.json"
    source = b"source\n"
    projected = b"projected\n"
    checkpoint.write_bytes(source)
    checkpoint.chmod(0o600)

    def fail_after_exchange(path: Path, content: bytes, **_kwargs) -> None:
        path.write_bytes(content)
        raise OSError("exchange completed")

    with pytest.raises(
        subject.atomic.CompletionPostCommitError,
        match="published state is uncertain",
    ):
        subject._write_projection_transport(
            fail_after_exchange,
            checkpoint,
            projected,
            expected_source=source,
            commit_guard=lambda: None,
        )
    assert checkpoint.read_bytes() == projected


def _minimal_write_projection_fixture(
    tmp_path: Path,
    monkeypatch,
) -> tuple[subject.PreparedProjection, Path, bytes, bytes]:
    checkpoint = tmp_path / "checkpoint.json"
    artifact = tmp_path / "artifact.txt"
    source = b"source\n"
    projected = b"projected\n"
    good = b"GOOD\n"
    checkpoint.write_bytes(source)
    checkpoint.chmod(0o600)
    artifact.write_bytes(good)
    digest = subject.sha256_bytes(good)
    path_sha256, content_sha256 = subject.npc._snapshot_hashes_from_digests(
        {Path("artifact.txt"): digest}
    )
    evidence = subject.GateEvidence(
        receipt={},
        receipt_bytes=b"",
        receipt_binding={},
        repository_payload={},
        event_occurred_at="",
    )
    prepared = subject.PreparedProjection(
        root=tmp_path,
        checkpoint_path=checkpoint,
        event_id=subject.EVENT_ID,
        source_checkpoint_bytes=source,
        source_checkpoint={},
        projected_checkpoint={
            "working_tree_snapshot": {
                "managed_changed_paths": ["artifact.txt"],
                "path_set_sha256": path_sha256,
                "content_set_sha256": content_sha256,
            }
        },
        projected_checkpoint_bytes=projected,
        event={},
        context=object(),
        evidence=evidence,
        final_sha256_by_path={Path("artifact.txt"): digest},
        physical_sha256_by_path={Path("artifact.txt"): digest},
        source_universe=(),
        candidate_catalogs={},
        catalogs_verified=True,
    )

    def snapshot_hashes(_root: Path, paths: list[str]) -> tuple[str, str]:
        assert paths == ["artifact.txt"]
        return subject.npc._snapshot_hashes_from_digests(
            {Path("artifact.txt"): subject.sha256_bytes(artifact.read_bytes())}
        )

    monkeypatch.setattr(subject.contract, "working_snapshot_hashes", snapshot_hashes)
    monkeypatch.setattr(subject, "_require_static_context", lambda *_args: None)
    monkeypatch.setattr(
        subject,
        "validate_gate_evidence",
        lambda *_args, **_kwargs: evidence,
    )
    monkeypatch.setattr(
        subject,
        "_require_live_repository_recapture",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        subject,
        "validate_projected_checkpoint",
        lambda *_args, **_kwargs: [],
    )
    return prepared, artifact, source, projected


def test_seq78_rejects_transient_managed_change_during_checkpoint_exchange(
    tmp_path: Path,
    monkeypatch,
) -> None:
    prepared, artifact, source, _ = _minimal_write_projection_fixture(
        tmp_path,
        monkeypatch,
    )
    good = artifact.read_bytes()

    def injected_writer(
        path: Path,
        content: bytes,
        *,
        expected_source: bytes,
        commit_guard,
    ) -> None:
        def exchange(parent_fd: int, source_name: str, destination_name: str) -> None:
            artifact.write_bytes(b"DIFFERENT\n")
            subject.atomic._rename_exchange_at(
                parent_fd,
                source_name,
                destination_name,
            )
            artifact.write_bytes(good)

        subject.atomic.atomic_write(
            path,
            content,
            expected_source=expected_source,
            commit_guard=commit_guard,
            exchanger=exchange,
        )

    with pytest.raises(
        subject.atomic.CompletionApplyError,
        match="retained physical evidence .* differs",
    ):
        subject.write_projection(prepared, atomic_writer=injected_writer)
    assert prepared.checkpoint_path.read_bytes() == source
    assert artifact.read_bytes() == good


def test_seq78_cohort_cleanup_failure_after_publication_is_postcommit(
    tmp_path: Path,
    monkeypatch,
) -> None:
    prepared, _, _, projected = _minimal_write_projection_fixture(
        tmp_path,
        monkeypatch,
    )

    class FailingCohort:
        def verify(self) -> None:
            return None

        def close(self, primary) -> None:
            assert primary is None
            raise OSError("cohort cleanup failed")

    monkeypatch.setattr(
        subject.npc,
        "retain_physical_pin_cohort",
        lambda *_args, **_kwargs: FailingCohort(),
    )

    def publish(
        path: Path,
        content: bytes,
        *,
        expected_source: bytes,
        commit_guard,
    ) -> None:
        assert path.read_bytes() == expected_source
        commit_guard()
        path.write_bytes(content)
        path.chmod(0o600)
        commit_guard()

    with pytest.raises(
        subject.atomic.CompletionPostCommitError,
        match="retained cohort cleanup failed",
    ):
        subject.write_projection(prepared, atomic_writer=publish)
    assert prepared.checkpoint_path.read_bytes() == projected


def test_seq78_preserves_prepublish_primary_when_cohort_cleanup_also_fails(
    tmp_path: Path,
    monkeypatch,
) -> None:
    prepared, _, source, _ = _minimal_write_projection_fixture(
        tmp_path,
        monkeypatch,
    )

    class FailingCohort:
        def verify(self) -> None:
            raise subject.StartApplyError("prepublication validation failed")

        def close(self, primary) -> None:
            assert isinstance(primary, subject.StartApplyError)
            raise OSError("cohort cleanup failed")

    monkeypatch.setattr(
        subject.npc,
        "retain_physical_pin_cohort",
        lambda *_args, **_kwargs: FailingCohort(),
    )
    with pytest.raises(
        subject.StartApplyError,
        match="prepublication validation failed",
    ) as raised:
        subject.write_projection(prepared)
    assert prepared.checkpoint_path.read_bytes() == source
    assert any(
        "retained cohort cleanup also failed" in note
        for note in getattr(raised.value, "__notes__", ())
    )


def test_main_classifies_pass_output_failure_after_seq78_publication(
    monkeypatch,
) -> None:
    prepared = type(
        "Prepared",
        (),
        {"event": {"event_sha256": "a" * 64, "occurred_at": "2026-08-23T00:00:03+09:00"}},
    )()
    writes = []
    monkeypatch.setattr(subject, "prepare_projection", lambda *_args, **_kwargs: prepared)
    monkeypatch.setattr(subject, "write_projection", lambda value: writes.append(value))
    diagnostics = []

    def output(stream, content: bytes) -> None:
        del stream
        if b": PASS " in content:
            raise BrokenPipeError("closed")
        diagnostics.append(content)

    monkeypatch.setattr(subject.gate._impl, "_write_raw_exact", output)
    assert subject.main(
        ["--root", str(ROOT), "--event-id", subject.EVENT_ID, "--write"]
    ) == 2
    assert writes == [prepared]
    assert len(diagnostics) == 1
    assert b"POSTCOMMIT-UNCERTAIN" in diagnostics[0]


def test_event_ids_are_exact() -> None:
    assert subject.reanchor.EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
        "FP046-R002-20260823-001"
    )
    assert subject.EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-001"
    )


def test_seq77_is_activation_trust_anchor_and_seq76_remains_ready_anchor() -> None:
    source = _seq77_source()
    control = source["goal_execution"]["transition_history"][-1]
    assert subject._source_activation(source, control["event_sha256"]) == control
    assert control["previous_event_sha256"] == subject.gate.SOURCE_READY_EVENT_SHA256
    control["event_type"] = "PACKAGE_ACTIVATED"
    with pytest.raises(subject.StartApplyError, match="seq77 trust anchor differs"):
        subject._source_activation(source, control["event_sha256"])


def test_seq78_consumes_active_r006_and_preserves_r005_session_bindings() -> None:
    review = subject.reanchor._review_module()
    assert subject.reanchor.REVIEW_PATHS == review.REVIEW_PATHS
    assert subject.reanchor.R005_REVIEW_PATHS == review.R005_REVIEW_PATHS
    assert subject.reanchor.SESSION_ARTIFACT_PATHS == review.SESSION_ARTIFACT_PATHS
    assert review.ASSIGNMENT_DOCUMENT_ID.endswith("-R006")
    assert review.RESULT_DOCUMENT_ID.endswith("-R006")
    assert review.INDEPENDENT_DOCUMENT_ID.endswith("-R006")
    control = _seq77_source()["goal_execution"]["transition_history"][-1]
    binding = control["transition_control_review_binding"]
    assert tuple(binding[role]["path"] for role in (
        "assignment",
        "review_result",
        "independent_review",
    )) == tuple(path.as_posix() for path in review.REVIEW_PATHS)
    assert all("/R006/" in row["path"] for row in binding.values())
    context = review.prepare_review_context(ROOT, require_exact_source=False)
    assert tuple(row["path"] for row in context.approved_r005_review_bindings) == tuple(
        path.as_posix() for path in review.R005_REVIEW_PATHS
    )
    assert tuple(row["path"] for row in context.session_artifact_bindings) == tuple(
        path.as_posix() for path in review.SESSION_ARTIFACT_PATHS
    )


def _private_gate_fixture(
    tmp_path: Path, monkeypatch
) -> dict[str, object]:
    source = _seq77_source()
    checks = tuple(
        (check_id, f"command-{index}")
        for index, check_id in enumerate(subject.gate.EXPECTED_CHECK_IDS, start=1)
    )
    event_dir = tmp_path / subject.gate.GATE_ROOT_RELATIVE / subject.EVENT_ID
    event_dir.mkdir(parents=True)
    event_dir.chmod(0o700)
    runs = []
    control_after = source["goal_execution"]["transition_history"][-1][
        "repository_context_reanchor"
    ]["after"]
    repository_snapshot = {
        "checkpoint_managed_path_count": control_after[
            "managed_changed_path_count"
        ],
        "checkpoint_path_set_sha256": control_after["path_set_sha256"],
        "checkpoint_content_set_sha256": control_after["content_set_sha256"],
    }
    reconstructed_source = b"exact seq77 checkpoint\n"
    for index, (check_id, command) in enumerate(checks, start=1):
        output_relative = (
            subject.gate.GATE_ROOT_RELATIVE
            / subject.EVENT_ID
            / f"{index:02d}-{check_id}.log"
        )
        output = (
            subject.contract.canonical_json_bytes({"repository": "sealed"}) + b"\n"
            if check_id == "REPOSITORY_STATE"
            else f"{check_id}: PASS\n".encode()
        )
        output_path = tmp_path / output_relative
        output_path.write_bytes(output)
        output_path.chmod(0o600)
        runs.append(
            {
                "check_id": check_id,
                "command": command,
                "executed_at": f"2026-08-23T00:00:02.{index}+09:00",
                "exit_code": 0,
                "output_path": output_relative.as_posix(),
                "output_sha256": subject.sha256_bytes(output),
            }
        )
    contract_binding = {
        "schema_version": "1.1",
        "document_id": subject.gate.CONTRACT_DOCUMENT_ID,
        "path": subject.gate.CONTRACT_RELATIVE.as_posix(),
        "file_sha256": subject.gate.CONTRACT_FILE_SHA256,
        "contract_id": subject.gate.CONTRACT_ID,
        "contract_version": subject.gate.CONTRACT_VERSION,
        "canonical_contract_sha256": subject.gate.CONTRACT_CANONICAL_SHA256,
    }
    receipt = {
        "schema_version": "1.1",
        "document_id": subject.gate._document_id(subject.EVENT_ID),
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": "INITIAL_START",
        "status": "PASS",
        "package_id": subject.gate.PACKAGE_ID,
        "target_transition_event_id": subject.EVENT_ID,
        "target_goal_id": subject.gate.TARGET_GOAL_ID,
        "target_goal_content_sha256": subject.gate.TARGET_GOAL_SHA256,
        "static_plan_manifest_sha256": subject.gate.MANIFEST_SHA256,
        "source_activation_event_sha256": source["goal_execution"][
            "transition_history"
        ][-1]["event_sha256"],
        "source_checkpoint_sha256": subject.sha256_bytes(reconstructed_source),
        "source_ready_event_sha256": subject.gate.SOURCE_READY_EVENT_SHA256,
        "check_command_contract_version": subject.gate.CONTRACT_VERSION,
        "check_command_contract_sha256": subject.gate.CONTRACT_CANONICAL_SHA256,
        "implementation_start_gate_contract_binding": contract_binding,
        "runtime_bindings": [],
        "execution_window": {
            "started_at": "2026-08-23T00:00:02+09:00",
            "ended_at": "2026-08-23T00:00:03+09:00",
        },
        "check_runs": runs,
        "repository_snapshot": repository_snapshot,
        "generated_at": "2026-08-23T00:00:04+09:00",
    }
    assert set(receipt) == subject.gate.RECEIPT_FIELDS
    receipt_bytes = subject.json_bytes(receipt)
    receipt_relative = (
        subject.gate.GATE_ROOT_RELATIVE
        / subject.EVENT_ID
        / subject.gate.RECEIPT_NAME
    )
    receipt_path = tmp_path / receipt_relative
    receipt_path.write_bytes(receipt_bytes)
    receipt_path.chmod(0o600)
    evidence = subject.GateEvidence(
        receipt=receipt,
        receipt_bytes=receipt_bytes,
        receipt_binding={
            "document_id": receipt["document_id"],
            "path": receipt_relative.as_posix(),
            "file_sha256": subject.sha256_bytes(receipt_bytes),
        },
        repository_payload={"repository": "sealed"},
        event_occurred_at="2026-08-23T00:00:05+09:00",
    )
    monkeypatch.setattr(subject, "require_exact_source", lambda *_args, **_kwargs: None)
    projected, _ = subject.project_seq78(
        ROOT,
        source,
        evidence,
        event_id=subject.EVENT_ID,
        final_sha256_by_path=_final_hashes(source),
    )
    monkeypatch.setattr(subject.reanchor, "validate_history_suffix", lambda *_: [])
    monkeypatch.setattr(
        subject.reanchor,
        "reconstructed_seq77_checkpoint_bytes",
        lambda *_: reconstructed_source,
    )
    monkeypatch.setattr(subject.gate, "_load_gate_contract", lambda *_: (checks, {}))
    monkeypatch.setattr(
        subject.gate, "expected_contract_binding", lambda: contract_binding
    )
    monkeypatch.setattr(
        subject.gate,
        "repository_snapshot_from_payload",
        lambda *_args, **_kwargs: repository_snapshot,
    )
    return {
        "source": source,
        "checks": checks,
        "event_dir": event_dir,
        "receipt": receipt,
        "receipt_bytes": receipt_bytes,
        "receipt_path": receipt_path,
        "projected": projected,
        "reconstructed_source": reconstructed_source,
        "contract_binding": contract_binding,
        "repository_snapshot": repository_snapshot,
        "repository_payload": {"repository": "sealed"},
    }


def _reseal_private_gate_receipt(fixture: dict[str, object]) -> None:
    receipt = fixture["receipt"]
    receipt_path = fixture["receipt_path"]
    projected = fixture["projected"]
    assert isinstance(receipt, dict)
    assert isinstance(receipt_path, Path)
    assert isinstance(projected, dict)
    receipt_bytes = subject.json_bytes(receipt)
    receipt_path.write_bytes(receipt_bytes)
    receipt_path.chmod(0o600)
    event = projected["goal_execution"]["transition_history"][-1]
    event["implementation_start_gate_binding"] = {
        **event["implementation_start_gate_binding"],
        "file_sha256": subject.sha256_bytes(receipt_bytes),
    }
    event["event_sha256"] = subject.contract.event_sha256(event)


def _reseal_empty_first_gate_log(fixture: dict[str, object]) -> None:
    event_dir = fixture["event_dir"]
    receipt = fixture["receipt"]
    assert isinstance(event_dir, Path)
    assert isinstance(receipt, dict)
    first_log = event_dir / "01-CONTINUATION.log"
    first_log.write_bytes(b"")
    first_log.chmod(0o600)
    receipt["check_runs"][0]["output_sha256"] = subject.sha256_bytes(b"")
    _reseal_private_gate_receipt(fixture)


def _validate_private_gate_fixture(
    root: Path,
    fixture: dict[str, object],
) -> subject.GateEvidence:
    source = fixture["source"]
    checks = fixture["checks"]
    reconstructed = fixture["reconstructed_source"]
    contract_binding = fixture["contract_binding"]
    repository_payload = fixture["repository_payload"]
    assert isinstance(source, dict)
    assert isinstance(checks, tuple)
    assert isinstance(reconstructed, bytes)
    assert isinstance(contract_binding, dict)
    assert isinstance(repository_payload, dict)
    context = subject.gate.GateContext(
        checks=checks,
        checkpoint_sha256=subject.sha256_bytes(reconstructed),
        target_goal_sha256=subject.gate.TARGET_GOAL_SHA256,
        source_activation_event_sha256=source["goal_execution"][
            "transition_history"
        ][-1]["event_sha256"],
        source_ready_event_sha256=subject.gate.SOURCE_READY_EVENT_SHA256,
        source_ready_occurred_at=subject.datetime.fromisoformat(
            "2026-08-23T00:00:00+09:00"
        ),
        contract_binding=contract_binding,
        runtime_bindings=(),
    )
    return subject.validate_gate_evidence(
        root,
        source,
        reconstructed,
        context,
        event_id=subject.EVENT_ID,
        capture_repository_state=(lambda *_args, **_kwargs: repository_payload),
    )


def test_history_suffix_validates_all_five_private_logs(
    tmp_path: Path, monkeypatch
) -> None:
    fixture = _private_gate_fixture(tmp_path, monkeypatch)
    projected = fixture["projected"]
    receipt = fixture["receipt"]
    receipt_bytes = fixture["receipt_bytes"]
    receipt_path = fixture["receipt_path"]
    event_dir = fixture["event_dir"]
    assert isinstance(projected, dict)
    assert isinstance(receipt, dict)
    assert isinstance(receipt_bytes, bytes)
    assert isinstance(receipt_path, Path)
    assert isinstance(event_dir, Path)
    assert subject.validate_history_suffix(tmp_path, projected) == []
    original_binding = projected["goal_execution"]["transition_history"][-1][
        "implementation_start_gate_binding"
    ]

    one_sided_receipt = dict(receipt)
    one_sided_receipt["document_id"] = "WS-FP046-R002-WRONG-RECEIPT"
    one_sided_raw = subject.json_bytes(one_sided_receipt)
    receipt_path.write_bytes(one_sided_raw)
    receipt_path.chmod(0o600)
    event = projected["goal_execution"]["transition_history"][-1]
    event["implementation_start_gate_binding"] = {
        **original_binding,
        "file_sha256": subject.sha256_bytes(one_sided_raw),
    }
    event["event_sha256"] = subject.contract.event_sha256(event)
    assert "authority differs" in subject.validate_history_suffix(
        tmp_path, projected
    )[0]

    resealed_binding = {
        **original_binding,
        "document_id": one_sided_receipt["document_id"],
        "file_sha256": subject.sha256_bytes(one_sided_raw),
    }
    event["implementation_start_gate_binding"] = resealed_binding
    event["event_sha256"] = subject.contract.event_sha256(event)
    assert "authority differs" in subject.validate_history_suffix(
        tmp_path, projected
    )[0]

    receipt_path.write_bytes(receipt_bytes)
    receipt_path.chmod(0o600)
    event["implementation_start_gate_binding"] = original_binding
    event["event_sha256"] = subject.contract.event_sha256(event)
    tampered_receipt = dict(receipt)
    tampered_receipt["source_checkpoint_sha256"] = "c" * 64
    tampered_raw = subject.json_bytes(tampered_receipt)
    receipt_path.write_bytes(tampered_raw)
    receipt_path.chmod(0o600)
    event["implementation_start_gate_binding"] = {
        **original_binding,
        "file_sha256": subject.sha256_bytes(tampered_raw),
    }
    event["event_sha256"] = subject.contract.event_sha256(event)
    assert "authority differs" in subject.validate_history_suffix(
        tmp_path, projected
    )[0]
    receipt_path.write_bytes(receipt_bytes)
    receipt_path.chmod(0o600)
    event["implementation_start_gate_binding"] = original_binding
    event["event_sha256"] = subject.contract.event_sha256(event)
    first_log = event_dir / "01-CONTINUATION.log"
    first_log.write_bytes(b"tampered\n")
    first_log.chmod(0o600)
    assert "authority differs" in subject.validate_history_suffix(
        tmp_path, projected
    )[0]


def test_history_suffix_rejects_resealed_empty_gate_log(
    tmp_path: Path, monkeypatch
) -> None:
    fixture = _private_gate_fixture(tmp_path, monkeypatch)
    projected = fixture["projected"]
    assert isinstance(projected, dict)
    assert subject.validate_history_suffix(tmp_path, projected) == []
    _reseal_empty_first_gate_log(fixture)
    assert subject.validate_history_suffix(tmp_path, projected)


def test_validate_gate_evidence_rejects_resealed_empty_gate_log(
    tmp_path: Path, monkeypatch
) -> None:
    fixture = _private_gate_fixture(tmp_path, monkeypatch)
    _validate_private_gate_fixture(tmp_path, fixture)
    _reseal_empty_first_gate_log(fixture)
    with pytest.raises((subject.StartApplyError, subject.gate.GateError)):
        _validate_private_gate_fixture(tmp_path, fixture)


@pytest.mark.parametrize("name", tuple(sorted(subject._gate_evidence_names())))
def test_retained_gate_guard_rejects_any_empty_evidence_file(
    tmp_path: Path, monkeypatch, name: str
) -> None:
    fixture = _private_gate_fixture(tmp_path, monkeypatch)
    event_dir = fixture["event_dir"]
    assert isinstance(event_dir, Path)
    evidence_path = event_dir / name
    evidence_path.write_bytes(b"")
    evidence_path.chmod(0o600)
    with pytest.raises(subject.StartApplyError) as raised:
        subject._capture_gate_evidence_guard(tmp_path, subject.EVENT_ID)
    assert name in str(raised.value)


def test_history_suffix_rejects_resealed_boolean_exit_code(
    tmp_path: Path, monkeypatch
) -> None:
    fixture = _private_gate_fixture(tmp_path, monkeypatch)
    projected = fixture["projected"]
    receipt = fixture["receipt"]
    assert isinstance(projected, dict)
    assert isinstance(receipt, dict)
    receipt["check_runs"][0]["exit_code"] = False
    _reseal_private_gate_receipt(fixture)
    assert subject.validate_history_suffix(tmp_path, projected)


def test_history_suffix_runtime_distinguishes_boolean_from_integer(
    tmp_path: Path,
    monkeypatch,
) -> None:
    fixture = _private_gate_fixture(tmp_path, monkeypatch)
    projected = fixture["projected"]
    assert isinstance(projected, dict)
    event = projected["goal_execution"]["transition_history"][-1]
    assert type(event["runtime_after"]["open_question_count"]) is int
    event["runtime_after"]["open_question_count"] = False
    event["event_sha256"] = subject.contract.event_sha256(event)
    errors = subject.validate_history_suffix(tmp_path, projected)
    assert errors and "seq78 event authority differs" in errors[0]


def test_validate_gate_evidence_rejects_resealed_boolean_exit_code(
    tmp_path: Path, monkeypatch
) -> None:
    fixture = _private_gate_fixture(tmp_path, monkeypatch)
    receipt = fixture["receipt"]
    assert isinstance(receipt, dict)
    receipt["check_runs"][0]["exit_code"] = False
    _reseal_private_gate_receipt(fixture)
    with pytest.raises((subject.StartApplyError, subject.gate.GateError)):
        _validate_private_gate_fixture(tmp_path, fixture)


def _install_gate_evidence_race(
    monkeypatch,
    event_dir: Path,
    race: str,
) -> None:
    fired = False

    def mutate() -> None:
        nonlocal fired
        if fired:
            return
        fired = True
        if race == "add-file":
            (event_dir / "unexpected.log").write_bytes(b"unexpected\n")
            return
        first = event_dir / "01-CONTINUATION.log"
        first.write_bytes(b"replaced after initial read\n")
        first.chmod(0o600)

    if hasattr(subject, "_capture_gate_evidence_guard"):
        guard_type = subject.gate._impl._RetainedEventEvidenceGuard
        original_content = guard_type.content

        def retained_content(guard, name: str) -> bytes:
            if name == "02-GOAL_GRAPH.log":
                mutate()
            return original_content(guard, name)

        monkeypatch.setattr(guard_type, "content", retained_content)
        return

    original_reader = subject.gate._private_file_bytes

    def path_reader(path: Path, **kwargs) -> bytes:
        if path.name == "02-GOAL_GRAPH.log":
            mutate()
        return original_reader(path, **kwargs)

    monkeypatch.setattr(subject.gate, "_private_file_bytes", path_reader)


@pytest.mark.parametrize("race", ("add-file", "replace-read-log"))
def test_history_suffix_rejects_gate_evidence_inventory_race(
    tmp_path: Path, monkeypatch, race: str
) -> None:
    fixture = _private_gate_fixture(tmp_path, monkeypatch)
    event_dir = fixture["event_dir"]
    projected = fixture["projected"]
    assert isinstance(event_dir, Path)
    assert isinstance(projected, dict)
    _install_gate_evidence_race(monkeypatch, event_dir, race)
    assert subject.validate_history_suffix(tmp_path, projected)


@pytest.mark.parametrize("race", ("add-file", "replace-read-log"))
def test_validate_gate_evidence_rejects_inventory_race(
    tmp_path: Path, monkeypatch, race: str
) -> None:
    fixture = _private_gate_fixture(tmp_path, monkeypatch)
    event_dir = fixture["event_dir"]
    assert isinstance(event_dir, Path)
    _install_gate_evidence_race(monkeypatch, event_dir, race)
    with pytest.raises((subject.StartApplyError, subject.gate.GateError)):
        _validate_private_gate_fixture(tmp_path, fixture)
