from __future__ import annotations

from datetime import datetime, timedelta
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest

from scripts import build_walksafe_npc_single_admin_recovery_trace_20260812 as subject
from scripts import run_walksafe_npc_single_admin_recovery_verification_20260813 as runner


def _database_state(phase: str, *, post: bool) -> dict[str, object]:
    components = {
        name: {"row_count": 1, "sha256": "3" * 64}
        for name in (
            "relations",
            "columns",
            "constraints",
            "indexes",
            "triggers",
            "functions",
            "rls_policies",
            "schema_database_default_acl",
            "effective_acl_privileges",
            "walksafe_roles",
            "walksafe_role_memberships",
            "safe_control_rows",
        )
    }
    return {
        "schema": "walksafe.npc-database-runtime-state.v2",
        "phase": phase,
        "current_database_sha256": "1" * 64,
        "server_identity_sha256": "2" * 64,
        "schema_migration_heads": [
            runner.DATABASE_POST_MIGRATION_HEAD
            if post
            else runner.DATABASE_PRE_MIGRATION_HEAD
        ],
        "catalog_component_seals": components,
        "relevant_state_sha256": ("4" if post else "5") * 64,
        "application_rows_recorded": False,
        "database_url_recorded": False,
    }


class _AdvancingClock:
    def __init__(self, value: datetime | None = None) -> None:
        self.value = value or datetime(2026, 8, 13, 9, 0, tzinfo=runner.TIMEZONE)

    def __call__(self) -> datetime:
        value = self.value
        self.value += timedelta(microseconds=1)
        return value


_SUCCESS_OUTPUTS = (
    (b"> Task :adminapp:testDebugUnitTest\nBUILD SUCCESSFUL in 1s\n", b""),
    (
        b"> Task :adminapp:assembleDebug\n"
        b"> Task :adminapp:lintDebug\n"
        b"BUILD SUCCESSFUL in 1s\n",
        b"",
    ),
    (b"test database preflight PASS: walksafe_test\n", b""),
    (
        (
            runner.DATABASE_STATE_MARKER
            + json.dumps(
                _database_state("PRE_MIGRATION", post=False),
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n"
        ).encode(),
        b"",
    ),
    (b"", b"INFO alembic.runtime.migration upgrade complete\n"),
    (
        (
            runner.DATABASE_STATE_MARKER
            + json.dumps(
                _database_state("AFTER_MIGRATION", post=True),
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n"
        ).encode(),
        b"",
    ),
    (b"................. [100%]\n17 passed in 0.10s\n", b""),
    (
        (
            runner.DATABASE_STATE_MARKER
            + json.dumps(
                _database_state("AFTER_TEST", post=True),
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n"
        ).encode(),
        b"",
    ),
    (b"..... [100%]\n5 passed in 0.05s\n", b""),
)


class _FakeRun:
    def __init__(self) -> None:
        self.index = 0

    def __call__(self, argv: list[str], **_: object) -> subprocess.CompletedProcess[bytes]:
        stdout, stderr = _SUCCESS_OUTPUTS[self.index]
        self.index += 1
        return subprocess.CompletedProcess(argv, 0, stdout=stdout, stderr=stderr)


def _fixture(
    tmp_path: Path,
    *,
    clock: _AdvancingClock | None = None,
) -> tuple[dict, bytes]:
    for relative in (
        subject.GOAL_REL,
        subject.START_GATE_RECEIPT_REL,
        subject.START_GATE_REPOSITORY_STATE_REL,
        subject.CHECKPOINT_REL,
    ):
        raw = (subject.ROOT / relative).read_bytes()
        path = tmp_path / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)

    for index, relative in enumerate(runner.NPC_PRODUCT_SOURCE_PATHS):
        raw = f"source-{index}\n".encode()
        path = tmp_path / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)

    runner_path = tmp_path / runner.RUNNER_REL
    runner_path.parent.mkdir(parents=True, exist_ok=True)
    runner_path.write_bytes(Path(runner.__file__).read_bytes())
    gradlew = tmp_path / "apps/android/gradlew"
    gradlew.write_text("#!/bin/sh\nexit 99\n", encoding="utf-8")
    gradlew.chmod(0o755)
    for relative in (
        Path("backend/requirements.lock"),
        Path("apps/android/gradle/wrapper/gradle-wrapper.properties"),
    ):
        destination = tmp_path / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes((subject.ROOT / relative).read_bytes())

    subprocess.run(
        ["/usr/bin/git", "init", "-q"],
        cwd=tmp_path,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    subprocess.run(
        ["/usr/bin/git", "add", "--", "."],
        cwd=tmp_path,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )

    v1 = tmp_path / runner.V1_OBSERVATION_MANIFEST_REL
    v1.parent.mkdir(parents=True, exist_ok=True)
    v1.write_bytes((subject.ROOT / runner.V1_OBSERVATION_MANIFEST_REL).read_bytes())
    v1.chmod(0o600)

    observation, observation_outputs = runner._capture_observation_for_test(
        root=tmp_path,
        environment={
            "JAVA_HOME": os.environ["JAVA_HOME"],
            "GRADLE_USER_HOME": os.environ.get(
                "GRADLE_USER_HOME", str(Path.home() / ".gradle")
            ),
            "PATH": f"{os.environ['JAVA_HOME']}/bin:/usr/bin:/bin",
            "WALKSAFE_TEST_DATABASE_URL": "postgresql://fixture.invalid/walksafe_test",
        },
        python_executable=str(Path(sys.executable).resolve()),
        run=_FakeRun(),
        clock=clock or _AdvancingClock(),
        run_id_factory=lambda: "NPC-RECOVERY-" + "a" * 32,
    )
    for relative, content in observation_outputs.items():
        path = tmp_path / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        path.chmod(0o600)
    raw = (tmp_path / runner.OBSERVATION_MANIFEST_REL).read_bytes()
    return observation, raw


def test_pending_product_pin_fails_before_reading_inputs() -> None:
    with pytest.raises(subject.BuildError, match="unresolved product verification input"):
        subject.require_resolved_observation_pin(
            subject.PENDING_OBSERVATION_MANIFEST_SHA256
        )


def test_v1_observation_is_explicitly_rejected_for_promotion(tmp_path: Path) -> None:
    _fixture(tmp_path)
    raw = (tmp_path / runner.V1_OBSERVATION_MANIFEST_REL).read_bytes()
    observation = subject.strict_json_bytes(raw, "v1 observation")

    with pytest.raises(subject.BuildError, match="v1 verification evidence is superseded"):
        subject.validate_observations(
            observation,
            raw,
            root=tmp_path,
            expected_sha256=subject.bytes_sha256(raw),
        )


def test_fixed_v1_predecessor_rejects_any_byte_change(tmp_path: Path) -> None:
    observation, raw = _fixture(tmp_path)
    v1 = tmp_path / runner.V1_OBSERVATION_MANIFEST_REL
    v1.write_bytes(v1.read_bytes() + b"\n")

    with pytest.raises(subject.BuildError, match="fixed v1 predecessor byte binding"):
        subject.validate_observations(observation, raw, root=tmp_path)


def test_results_bind_exact_sources_lanes_and_zero_credit(tmp_path: Path) -> None:
    observation, raw = _fixture(tmp_path)
    outputs = subject.build_results_outputs(
        observation,
        raw,
        root=tmp_path,
    )

    assert set(outputs) == {
        *(lane.receipt_rel for lane in subject.LANES),
        subject.V2_IMPLEMENTATION_REL,
        subject.V2_VERIFICATION_REL,
    }
    implementation = json.loads(outputs[subject.V2_IMPLEMENTATION_REL])
    verification = json.loads(outputs[subject.V2_VERIFICATION_REL])
    subject.verify_seal(
        implementation, "implementation_record_content_sha256", "implementation"
    )
    subject.verify_seal(
        verification, "verification_result_content_sha256", "verification"
    )
    assert implementation["status"] == "PASS_INTERNAL"
    assert verification["internal_lane_count"] == 4
    assert verification["completion_boundary"]["formal_test_status"] == "NOT_RUN"
    assert verification["completion_boundary"]["actual_recovery_drill_status"] == "NOT_RUN"
    assert verification["completion_boundary"]["release_status"] == "NOT_ELIGIBLE"
    assert implementation["execution_source_boundary"] == (
        runner.execution_source_boundary()
    )

    with pytest.raises(subject.BuildError, match="manifest SHA-256 differs"):
        subject.build_results_outputs(
            observation,
            raw,
            root=tmp_path,
            expected_sha256="0" * 64,
        )


def test_trace_replay_rejects_resolved_python_target_as_invocation_path(
    tmp_path: Path,
) -> None:
    observation, _ = _fixture(tmp_path)
    python_binding = observation["runner_toolchain_receipt"]["python"]
    assert python_binding["invocation_path"] != python_binding["resolved_path"]
    python_binding["invocation_path"] = python_binding["resolved_path"]
    raw = subject.json_text(observation).encode()

    with pytest.raises(subject.BuildError, match="runner/toolchain binding differs"):
        subject.build_results_outputs(
            observation,
            raw,
            root=tmp_path,
            expected_sha256=subject.bytes_sha256(raw),
        )


@pytest.mark.parametrize("boundary_mutation", ("missing", "atomic_true"))
def test_results_reject_a_false_live_tree_atomicity_claim(
    tmp_path: Path, boundary_mutation: str
) -> None:
    observation, _ = _fixture(tmp_path)
    if boundary_mutation == "missing":
        observation.pop("execution_source_boundary")
    else:
        observation["execution_source_boundary"][
            "live_tree_atomic_binding_claimed"
        ] = True
    raw = subject.json_text(observation).encode()

    with pytest.raises(subject.BuildError, match="execution source boundary|fields"):
        subject.build_results_outputs(observation, raw, root=tmp_path)


def test_results_write_guard_rejects_source_drift_and_writes_nothing(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _fixture(tmp_path)
    original = subject.build_results_outputs
    calls = 0

    def mutate_after_initial_build(*args: object, **kwargs: object) -> dict[Path, str]:
        nonlocal calls
        outputs = original(*args, **kwargs)
        calls += 1
        if calls == 1:
            target = tmp_path / "tracked-after-build.txt"
            target.write_text("late execution input\n")
            subprocess.run(
                [runner.GIT_EXECUTABLE, "add", "--", target.name],
                cwd=tmp_path,
                check=True,
            )
        return outputs

    monkeypatch.setattr(subject, "build_results_outputs", mutate_after_initial_build)
    assert subject.main(["--root", str(tmp_path), "--write-results"]) == 1
    assert not any(
        (tmp_path / relative).exists()
        for relative in (*[lane.receipt_rel for lane in subject.LANES], subject.V2_IMPLEMENTATION_REL, subject.V2_VERIFICATION_REL)
    )


def test_results_reject_tampered_start_authority(tmp_path: Path) -> None:
    observation, raw = _fixture(tmp_path)
    receipt = tmp_path / subject.START_GATE_RECEIPT_REL
    receipt.write_bytes(receipt.read_bytes() + b"\n")

    with pytest.raises(subject.BuildError, match="start gate receipt SHA-256 differs"):
        subject.build_results_outputs(
            observation,
            raw,
            root=tmp_path,
            expected_sha256=subject.bytes_sha256(raw),
        )


@pytest.mark.parametrize("mutation", ("content", "add", "delete", "mode"))
def test_downstream_rejects_execution_closure_drift(
    tmp_path: Path, mutation: str
) -> None:
    observation, raw = _fixture(tmp_path)
    target = tmp_path / runner.RUNNER_REL
    if mutation == "content":
        target.write_bytes(target.read_bytes() + b"\n# tampered\n")
    elif mutation == "add":
        (tmp_path / "new-execution-input.txt").write_text("new\n")
    elif mutation == "delete":
        target.unlink()
    else:
        target.chmod(0o755)

    with pytest.raises(subject.BuildError, match="execution input closure differs"):
        subject.build_results_outputs(
            observation,
            raw,
            root=tmp_path,
            expected_sha256=subject.bytes_sha256(raw),
        )


def test_start_authority_remains_valid_after_goal_completion(
    tmp_path: Path,
) -> None:
    _fixture(tmp_path)
    checkpoint_path = tmp_path / subject.CHECKPOINT_REL
    checkpoint = json.loads(checkpoint_path.read_text())
    checkpoint["goal_execution"]["status_by_goal"][subject.GOAL_ID] = (
        "COMPLETE_AT_TARGET"
    )
    checkpoint_path.write_text(subject.json_text(checkpoint))

    subject.validate_start_authority(tmp_path)


def test_results_reject_zero_exit_lane_with_noncanonical_command(tmp_path: Path) -> None:
    observation, _ = _fixture(tmp_path)
    observation["lanes"][0]["command"] = ["true"]
    raw = subject.json_text(observation).encode()

    with pytest.raises(subject.BuildError, match="lane commands differ"):
        subject.build_results_outputs(
            observation,
            raw,
            root=tmp_path,
            expected_sha256=subject.bytes_sha256(raw),
        )


def test_results_reject_lane_timeline_overlap(tmp_path: Path) -> None:
    observation, _ = _fixture(tmp_path)
    first = observation["lanes"][0]
    second = observation["lanes"][1]
    log_path = tmp_path / second["log_path"]
    log_raw = log_path.read_bytes()
    log_raw = log_raw.replace(
        second["started_at"].encode(), first["started_at"].encode()
    ).replace(second["ended_at"].encode(), first["ended_at"].encode())
    log_path.write_bytes(log_raw)
    second["started_at"] = first["started_at"]
    second["ended_at"] = first["ended_at"]
    second["log_sha256"] = subject.bytes_sha256(log_raw)
    second["log_byte_count"] = len(log_raw)
    raw = subject.json_text(observation).encode()

    with pytest.raises(subject.BuildError, match="timeline overlaps"):
        subject.build_results_outputs(
            observation,
            raw,
            root=tmp_path,
            expected_sha256=subject.bytes_sha256(raw),
        )


def test_results_reject_verification_before_start_event(tmp_path: Path) -> None:
    observation, raw = _fixture(
        tmp_path,
        clock=_AdvancingClock(
            datetime(2026, 8, 12, 22, 0, tzinfo=runner.TIMEZONE)
        ),
    )

    with pytest.raises(subject.BuildError, match="predates its authority"):
        subject.build_results_outputs(
            observation,
            raw,
            root=tmp_path,
            expected_sha256=subject.bytes_sha256(raw),
        )


def test_successor_requires_exact_v2_consumers(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    observation, raw = _fixture(tmp_path)
    outputs = subject.build_results_outputs(
        observation,
        raw,
        root=tmp_path,
        expected_sha256=subject.bytes_sha256(raw),
    )
    implementation_raw = outputs[subject.V2_IMPLEMENTATION_REL].encode()
    verification_raw = outputs[subject.V2_VERIFICATION_REL].encode()
    implementation = subject.strict_json_bytes(implementation_raw, "implementation")
    verification = subject.strict_json_bytes(verification_raw, "verification")
    consumers = {
        path: subject.json_text(
            {
                "schema_version": "1.0",
                "document_id": role,
                **(
                    {"verdict": "REJECTED"}
                    if role == "REJECTED_REVIEW_R001"
                    else {}
                ),
            }
        ).encode()
        for role, path in subject.CONSUMER_SPECS
    }
    monkeypatch.setattr(subject, "_deep_validate_consumers", lambda *_: None)

    successor_outputs = subject.build_successor_outputs(
        implementation,
        verification,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        consumer_raw_by_path=consumers,
        observed_at=observation["observed_at"],
        root=tmp_path,
    )
    successor = json.loads(successor_outputs[subject.V2_SUCCESSOR_REL])
    review_subject = json.loads(successor_outputs[subject.V2_REVIEW_SUBJECT_REL])
    assert len(successor["downstream_consumer_bindings"]) == len(
        subject.CONSUMER_SPECS
    )
    assert len(review_subject["reviewed_consumer_bindings"]) == len(
        subject.CONSUMER_SPECS
    )
    assert review_subject["reviewer_must_be_independent_of_executor"] is True

    before_producer = (
        datetime.fromisoformat(observation["observed_at"]) - timedelta(seconds=1)
    ).isoformat()
    with pytest.raises(subject.BuildError, match="predates producer"):
        subject.build_successor_outputs(
            implementation,
            verification,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
            consumer_raw_by_path=consumers,
            observed_at=before_producer,
            root=tmp_path,
        )

    with pytest.raises(subject.BuildError, match="inventory"):
        subject.build_successor_outputs(
            implementation,
            verification,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
            consumer_raw_by_path=dict(list(consumers.items())[:-1]),
            observed_at=observation["observed_at"],
            root=tmp_path,
        )
