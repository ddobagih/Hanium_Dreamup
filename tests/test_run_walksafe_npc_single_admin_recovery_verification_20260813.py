from __future__ import annotations

from datetime import datetime, timedelta
import inspect
import json
import os
from pathlib import Path
import subprocess
import sys
from typing import Callable

from alembic.config import Config
from alembic.script import ScriptDirectory
import pytest

from scripts import (
    run_walksafe_npc_single_admin_recovery_verification_20260813 as runner,
)


RUN_ID = "NPC-RECOVERY-" + "a" * 32
TEST_DATABASE_URL = (
    "postgresql+psycopg://verification-user:verification-password@"
    "db.invalid/walksafe_test"
)


def test_database_post_migration_head_remains_in_repository_revision_graph() -> None:
    root = Path(__file__).parents[1]
    config = Config(str(root / "backend/alembic.ini"))
    config.set_main_option("script_location", str(root / "backend/alembic"))
    revisions = ScriptDirectory.from_config(config)

    assert len(revisions.get_heads()) == 1
    frozen_revision = revisions.get_revision(runner.DATABASE_POST_MIGRATION_HEAD)
    assert frozen_revision is not None
    assert frozen_revision.revision == runner.DATABASE_POST_MIGRATION_HEAD


@pytest.fixture(autouse=True)
def isolated_gradle_user_home(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> Path:
    gradle_home = tmp_path / "gradle-user-home"
    distribution_parent = (
        gradle_home
        / "wrapper/dists/gradle-9.3.1-bin/fixture-cache-key"
    )
    distribution = distribution_parent / "gradle-9.3.1"
    (distribution / "bin").mkdir(parents=True)
    (distribution / "bin/gradle").write_text(
        "#!/bin/sh\nexit 99\n",
        encoding="utf-8",
    )
    (distribution_parent / "gradle-9.3.1-bin.zip.ok").write_bytes(b"")
    module_root = gradle_home / "caches/modules-2"
    for name in ("files-2.1", "metadata-2.107"):
        directory = module_root / name
        directory.mkdir(parents=True)
        (directory / "fixture-entry").write_text(
            f"isolated {name}\n",
            encoding="utf-8",
        )
    monkeypatch.setenv("GRADLE_USER_HOME", str(gradle_home))
    return gradle_home


def database_state(phase: str, *, post: bool) -> dict[str, object]:
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


SUCCESS_OUTPUTS = (
    (b"> Task :adminapp:testDebugUnitTest\nBUILD SUCCESSFUL in 1s\n", b""),
    (
        b"> Task :adminapp:assembleDebug\n"
        b"> Task :adminapp:lintDebug\nBUILD SUCCESSFUL in 1s\n",
        b"",
    ),
    (b"test database preflight PASS: walksafe_test\n", b""),
    (
        (
            runner.DATABASE_STATE_MARKER
            + json.dumps(database_state("PRE_MIGRATION", post=False), sort_keys=True, separators=(",", ":"))
            + "\n"
        ).encode(),
        b"",
    ),
    (b"", b"INFO alembic.runtime.migration upgrade complete\n"),
    (
        (
            runner.DATABASE_STATE_MARKER
            + json.dumps(database_state("AFTER_MIGRATION", post=True), sort_keys=True, separators=(",", ":"))
            + "\n"
        ).encode(),
        b"",
    ),
    (b"................. [100%]\n17 passed in 0.10s\n", b""),
    (
        (
            runner.DATABASE_STATE_MARKER
            + json.dumps(database_state("AFTER_TEST", post=True), sort_keys=True, separators=(",", ":"))
            + "\n"
        ).encode(),
        b"",
    ),
    (b"..... [100%]\n5 passed in 0.05s\n", b""),
)


class AdvancingClock:
    def __init__(self) -> None:
        self.value = datetime(2026, 8, 13, 9, 0, tzinfo=runner.TIMEZONE)

    def __call__(self) -> datetime:
        value = self.value
        self.value += timedelta(microseconds=1)
        return value


class FakeRun:
    def __init__(
        self,
        *,
        on_call: Callable[[int, Path], None] | None = None,
        appended_output: bytes = b"",
    ) -> None:
        self.calls: list[tuple[list[str], dict[str, object]]] = []
        self.on_call = on_call
        self.appended_output = appended_output

    def __call__(
        self, argv: list[str], **kwargs: object
    ) -> subprocess.CompletedProcess[bytes]:
        index = len(self.calls)
        cwd = Path(str(kwargs["cwd"]))
        resolved_cwd = Path(os.readlink(cwd))
        snapshot = resolved_cwd.parents[1] if index < 2 else resolved_cwd
        self.calls.append((list(argv), dict(kwargs)))
        if self.on_call is not None:
            self.on_call(index, snapshot)
        stdout, stderr = SUCCESS_OUTPUTS[index]
        if self.appended_output:
            stdout += self.appended_output + b"\n"
        return subprocess.CompletedProcess(
            argv, 0, stdout=stdout, stderr=stderr
        )


def make_repository(tmp_path: Path) -> Path:
    root = tmp_path / "repo"
    root.mkdir()
    for index, relative in enumerate(runner.NPC_PRODUCT_SOURCE_PATHS, start=1):
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"source {index}: {relative}\n", encoding="utf-8")
    runner_path = root / runner.RUNNER_REL
    runner_path.parent.mkdir(parents=True, exist_ok=True)
    runner_path.write_bytes(Path(runner.__file__).read_bytes())
    gradlew = root / "apps/android/gradlew"
    gradlew.write_text("#!/bin/sh\nexit 99\n", encoding="utf-8")
    gradlew.chmod(0o755)
    for relative in (
        Path("backend/requirements.lock"),
        Path("apps/android/gradle/wrapper/gradle-wrapper.properties"),
    ):
        destination = root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes((runner.ROOT / relative).read_bytes())
    (root / "tracked-source.txt").write_text("tracked\n", encoding="utf-8")
    (root / ".gitignore").write_text(
        ".env\nbuild/\n.gradle/\n", encoding="utf-8"
    )
    subprocess.run(
        [runner.GIT_EXECUTABLE, "init", "-q"], cwd=root, check=True
    )
    subprocess.run(
        [runner.GIT_EXECUTABLE, "add", "--", "."], cwd=root, check=True
    )
    v1 = root / runner.V1_OBSERVATION_MANIFEST_REL
    v1.parent.mkdir(parents=True, exist_ok=True)
    v1.write_bytes((runner.ROOT / runner.V1_OBSERVATION_MANIFEST_REL).read_bytes())
    v1.chmod(0o600)
    (root / ".env").write_text("IGNORED_SECRET=value\n")
    ignored = root / "build/ignored-secret.txt"
    ignored.parent.mkdir()
    ignored.write_text("ignored\n")
    return root


def environment() -> dict[str, str]:
    java_home = os.environ["JAVA_HOME"]
    return {
        "JAVA_HOME": java_home,
        "GRADLE_USER_HOME": os.environ.get(
            "GRADLE_USER_HOME", str(Path.home() / ".gradle")
        ),
        "PATH": f"{java_home}/bin:/usr/bin:/bin",
        "WALKSAFE_TEST_DATABASE_URL": TEST_DATABASE_URL,
        "PYTHONNOUSERSITE": "1",
    }


def build_in_memory(
    root: Path, fake: FakeRun | None = None
) -> tuple[dict[str, object], dict[Path, str]]:
    return runner._capture_observation_for_test(
        root=root,
        environment=environment(),
        python_executable=sys.executable,
        run=fake or FakeRun(),
        clock=AdvancingClock(),
        run_id_factory=lambda: RUN_ID,
    )


def test_production_publish_api_has_no_execution_injection() -> None:
    assert tuple(inspect.signature(runner.capture_and_publish).parameters) == (
        "root",
        "source_path_file",
    )
    forbidden = {
        "clock",
        "environment",
        "python_executable",
        "run",
        "run_id_factory",
    }
    assert forbidden.isdisjoint(
        inspect.signature(runner.capture_and_publish).parameters
    )


def test_backend_lane_executes_each_current_recovery_test_once() -> None:
    backend = runner.LANE_SPEC_BY_ID["BACKEND_RECOVERY_PYTEST"]
    pytest_argv = backend.commands[4].argv

    assert pytest_argv.count("backend/tests/test_admin_device_proof.py") == 1
    assert pytest_argv.count("backend/tests/test_admin_runtime_acl_hardening.py") == 1


def test_gradle_offline_seed_matches_fresh_gradle_9_cache(tmp_path: Path) -> None:
    module_root = tmp_path / "modules-2"
    files = module_root / "files-2.1"
    metadata = module_root / "metadata-2.107"
    files.mkdir(parents=True)
    metadata.mkdir()

    assert runner._gradle_offline_module_seed_directories(module_root) == {
        "files-2.1": files,
        "metadata-2.107": metadata,
    }


@pytest.mark.parametrize("missing", ["files-2.1", "metadata-2.107"])
def test_gradle_offline_seed_rejects_missing_required_cache_namespace(
    tmp_path: Path,
    missing: str,
) -> None:
    module_root = tmp_path / "modules-2"
    for name in {"files-2.1", "metadata-2.107"} - {missing}:
        (module_root / name).mkdir(parents=True)

    with pytest.raises(
        runner.VerificationError,
        match="Gradle offline module seed is incomplete",
    ):
        runner._gradle_offline_module_seed_directories(module_root)


def test_fake_execution_builds_v2_in_memory_without_publication(
    tmp_path: Path,
) -> None:
    root = make_repository(tmp_path)
    manifest, outputs = build_in_memory(root)

    assert manifest["schema_version"] == runner.OBSERVATION_SCHEMA
    assert manifest["correction"]["promotion_authority"] == "V2_ONLY"
    assert manifest["correction"]["superseded_evidence_binding"]["sha256"] == (
        runner.sha256_bytes((root / runner.V1_OBSERVATION_MANIFEST_REL).read_bytes())
    )
    assert tuple(outputs) == tuple(
        spec.log_relative for spec in runner.LANE_SPECS
    ) + (runner.OBSERVATION_MANIFEST_REL,)
    receipt = manifest["runner_toolchain_receipt"]
    assert receipt["python"]["invocation_path"] == sys.executable
    assert all(
        command["execution_argv"][0] == sys.executable
        for lane in receipt["lane_contract"]
        for command in lane["commands"]
        if command["logical_command"].startswith("python3 ")
    )
    assert receipt["python_requirements_lock"]["path"] == "backend/requirements.lock"
    assert receipt["python_installed_distributions"]["distribution_count"] > 0
    assert receipt["gradle_distribution"]["file_count"] > 0
    assert Path(receipt["gradle_distribution"]["resolved_path"]).is_relative_to(
        tmp_path
    )
    assert receipt["gradle_offline_module_seed"]["files-2.1"]["file_count"] > 0
    assert set(receipt["gradle_offline_module_seed"]) == {
        "files-2.1",
        "metadata-2.107",
    }
    assert receipt["jdk"]["modules"]["byte_count"] > 0
    assert receipt["android_sdk"]["platform_android_36"]["file_count"] > 0
    assert receipt["android_sdk"]["build_tools"]["file_count"] > 0
    assert not any((root / relative).exists() for relative in outputs)


def test_fake_execution_binds_optional_gradle_resources_cache(
    tmp_path: Path,
    isolated_gradle_user_home: Path,
) -> None:
    resources = (
        isolated_gradle_user_home
        / "caches/modules-2/resources-2.1"
    )
    resources.mkdir()
    (resources / "fixture-entry").write_text(
        "isolated resources-2.1\n",
        encoding="utf-8",
    )
    root = make_repository(tmp_path)

    manifest, _ = build_in_memory(root)

    receipt = manifest["runner_toolchain_receipt"]
    assert set(receipt["gradle_offline_module_seed"]) == {
        "files-2.1",
        "metadata-2.107",
        "resources-2.1",
    }
    assert receipt["gradle_offline_module_seed"]["resources-2.1"][
        "file_count"
    ] == 1


def test_v2_closure_binds_every_nonexcluded_git_visible_input(
    tmp_path: Path,
) -> None:
    root = make_repository(tmp_path)
    manifest, _ = build_in_memory(root)
    closure = manifest["execution_input_closure"]
    rows = closure["files"]
    paths = [row["path"] for row in rows]

    assert paths == list(runner._git_visible_paths(root))
    assert runner.RUNNER_REL.as_posix() in paths
    assert "tracked-source.txt" in paths
    assert not any(path.startswith("docs/control/") for path in paths)
    assert not any(path.startswith("build/") for path in paths)
    gradlew = next(row for row in rows if row["path"] == "apps/android/gradlew")
    assert gradlew["mode"] == 0o755
    assert gradlew["byte_count"] == (root / "apps/android/gradlew").stat().st_size
    assert closure["content_set_sha256"] == runner.sha256_bytes(
        runner.canonical_json_bytes(rows)
    )


def test_generated_catalogs_only_are_excluded_from_execution_closure(
    tmp_path: Path,
) -> None:
    root = make_repository(tmp_path)
    generated = root / "docs/catalogs/scripts.json"
    generated.parent.mkdir(parents=True)
    generated.write_text("{}\n")
    catalog_readme = root / "docs/catalogs/README.md"
    catalog_readme.write_text("catalog guide\n")
    generator = root / "scripts/generate_repository_catalogs.py"
    generator.write_text("print('generate')\n")
    generator_test = root / "tests/test_repository_catalogs.py"
    generator_test.parent.mkdir(parents=True, exist_ok=True)
    generator_test.write_text("def test_catalog(): pass\n")
    completion_apply = root / (
        "scripts/apply_walksafe_npc_single_admin_recovery_goal_completed_"
        "seq61_62_20260812.py"
    )
    completion_apply.write_text("print('completion')\n")
    subprocess.run(
        [runner.GIT_EXECUTABLE, "add", "--", "."], cwd=root, check=True
    )

    paths = set(runner._git_visible_paths(root))

    assert "docs/catalogs/scripts.json" not in paths
    assert "docs/catalogs/README.md" in paths
    assert "scripts/generate_repository_catalogs.py" in paths
    assert "tests/test_repository_catalogs.py" in paths
    assert completion_apply.relative_to(root).as_posix() in paths


def test_only_declared_legacy_prefixes_are_excluded(tmp_path: Path) -> None:
    root = make_repository(tmp_path)
    excluded = (
        "legacy/old.txt",
        "legacy1/old.txt",
        "legacy2/old.txt",
        "legacy3/old.txt",
    )
    retained = (
        "docs/execution/current.txt",
        "docs/evidence/current.txt",
        "docs/review/current.txt",
    )
    for relative in (*excluded, *retained):
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(relative + "\n")
    subprocess.run(
        [runner.GIT_EXECUTABLE, "add", "--", "."], cwd=root, check=True
    )

    paths = set(runner._git_visible_paths(root))

    assert not paths.intersection(excluded)
    assert set(retained).issubset(paths)


def test_successor_backend_migration_is_discovered_without_fixed_hash(
    tmp_path: Path,
) -> None:
    root = make_repository(tmp_path)
    migration = (
        root
        / "backend/alembic/versions/202608130001_admin_runtime_acl_hardening.py"
    )
    migration.write_text("revision = '202608130001'\n", encoding="utf-8")
    manifest, _ = build_in_memory(root)

    product_paths = [row["path"] for row in manifest["source_files"]]
    assert migration.relative_to(root).as_posix() in product_paths
    closure_row = next(
        row
        for row in manifest["execution_input_closure"]["files"]
        if row["path"] == migration.relative_to(root).as_posix()
    )
    assert closure_row["sha256"] == runner.sha256_bytes(migration.read_bytes())


def test_actual_broken_gradlew_fails_and_publishes_no_v2(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = make_repository(tmp_path)
    monkeypatch.setenv("WALKSAFE_TEST_DATABASE_URL", TEST_DATABASE_URL)

    with pytest.raises(runner.VerificationError, match="failed closed"):
        runner.capture_and_publish(root=root)

    assert not (root / runner.CORRECTION_DIR_REL).exists()
    assert (root / runner.V1_OBSERVATION_MANIFEST_REL).exists()


def test_public_capture_rejects_a_monkeypatched_module_subprocess_executor(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = make_repository(tmp_path)
    monkeypatch.setenv("WALKSAFE_TEST_DATABASE_URL", TEST_DATABASE_URL)
    monkeypatch.setattr(runner.subprocess, "run", FakeRun())

    with pytest.raises(runner.VerificationError, match="executor was replaced"):
        runner.capture_and_publish(root=root)

    assert not (root / runner.CORRECTION_DIR_REL).exists()


def test_public_capture_rejects_both_mutable_executor_aliases_replaced(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = make_repository(tmp_path)
    fake = FakeRun()
    monkeypatch.setenv("WALKSAFE_TEST_DATABASE_URL", TEST_DATABASE_URL)
    monkeypatch.setattr(runner.subprocess, "run", fake)
    monkeypatch.setattr(runner, "_SYSTEM_SUBPROCESS_RUN", fake)

    with pytest.raises(runner.VerificationError, match="executor was replaced"):
        runner.capture_and_publish(root=root)

    assert fake.calls == []
    assert not (root / runner.CORRECTION_DIR_REL).exists()


def test_public_capture_ignores_a_replaced_resolver_global(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: list[str] = []

    def injected() -> FakeRun:
        calls.append("injected")
        return FakeRun()

    monkeypatch.setattr(runner, "_resolve_production_executor", injected)

    with pytest.raises(runner.VerificationError, match="repository root is unavailable"):
        runner.capture_and_publish(root=tmp_path / "missing")

    assert calls == []


def test_production_environment_removes_ambient_test_and_gradle_injection(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_TEST_DATABASE_URL", TEST_DATABASE_URL)
    monkeypatch.setenv("PYTEST_ADDOPTS", "--pwned")
    monkeypatch.setenv("PYTEST_PLUGINS", "attacker")
    monkeypatch.setenv("GRADLE_OPTS", "-Dunsafe=true")
    monkeypatch.setenv("JAVA_TOOL_OPTIONS", "-javaagent:unsafe.jar")
    monkeypatch.setenv("PYTHONPATH", "/unsafe")

    safe = runner._production_environment()

    assert "WALKSAFE_TEST_DATABASE_URL" in safe
    assert not {
        "PYTEST_ADDOPTS",
        "PYTEST_PLUGINS",
        "GRADLE_OPTS",
        "JAVA_TOOL_OPTIONS",
        "PYTHONPATH",
    }.intersection(safe)


@pytest.mark.parametrize("mutation", ("content", "add", "delete", "mode"))
def test_repository_closure_drift_publishes_nothing(
    tmp_path: Path, mutation: str
) -> None:
    root = make_repository(tmp_path)

    def mutate(index: int, _snapshot: Path) -> None:
        if index != 5:
            return
        target = root / "tracked-source.txt"
        if mutation == "content":
            target.write_text("changed\n")
        elif mutation == "add":
            (root / "new-visible.txt").write_text("new\n")
        elif mutation == "delete":
            target.unlink()
        else:
            target.chmod(0o755)

    with pytest.raises(runner.VerificationError, match="Git-visible source"):
        build_in_memory(root, FakeRun(on_call=mutate))

    assert not (root / runner.CORRECTION_DIR_REL).exists()


def test_lane_created_runtime_input_fails_closed(
    tmp_path: Path,
) -> None:
    root = make_repository(tmp_path)

    def create_input(index: int, snapshot: Path) -> None:
        if index == 0:
            (snapshot / "runtime-created-input.txt").write_text("unbound\n")

    with pytest.raises(runner.VerificationError, match="non-allowlisted runtime input"):
        build_in_memory(root, FakeRun(on_call=create_input))


def test_generated_build_output_cannot_feed_the_next_lane(
    tmp_path: Path,
) -> None:
    root = make_repository(tmp_path)

    def inspect_lanes(index: int, snapshot: Path) -> None:
        token = snapshot / "apps/android/adminapp/build/prior-lane-token"
        if index == 0:
            token.parent.mkdir(parents=True)
            token.write_text("generated\n")
        elif index == 1:
            assert not token.exists()

    build_in_memory(root, FakeRun(on_call=inspect_lanes))


def test_private_test_capture_has_no_publication_primitive(
    tmp_path: Path,
) -> None:
    root = make_repository(tmp_path)
    _, outputs = build_in_memory(root)

    assert not hasattr(runner, "_authorize_production_capture")
    assert not hasattr(runner, "_publish_outputs_transaction")
    assert not any((root / relative).exists() for relative in outputs)


@pytest.mark.parametrize(
    "capture_target",
    (
        "_capture_observation_for_test",
        "_capture_observation_core",
        "_capture_production_observation",
    ),
)
def test_production_rejects_each_module_level_fake_capture_path(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capture_target: str,
) -> None:
    root = make_repository(tmp_path)
    monkeypatch.setenv("WALKSAFE_TEST_DATABASE_URL", TEST_DATABASE_URL)
    calls: list[str] = []

    def forged(**_: object) -> tuple[dict[str, object], dict[Path, str]]:
        calls.append(capture_target)
        return {}, {runner.OBSERVATION_MANIFEST_REL: "{}\n"}

    monkeypatch.setattr(runner, capture_target, forged, raising=False)
    with pytest.raises(runner.VerificationError, match="failed closed"):
        runner.capture_and_publish(root=root)

    assert calls == []
    assert not (root / runner.CORRECTION_DIR_REL).exists()


def test_database_runtime_identity_must_remain_stable() -> None:
    states = (
        database_state("PRE_MIGRATION", post=False),
        database_state("AFTER_MIGRATION", post=True),
        {
            **database_state("AFTER_TEST", post=True),
            "server_identity_sha256": "4" * 64,
        },
    )
    spec = runner.LANE_SPEC_BY_ID["BACKEND_RECOVERY_PYTEST"]
    commands = list(
        runner.CommandResult(command.logical_command(), b"", b"")
        for command in spec.commands
    )
    for index, state in zip((1, 3, 5), states, strict=True):
        commands[index] = runner.CommandResult(
            spec.commands[index].logical_command(),
            (
                runner.DATABASE_STATE_MARKER
                + json.dumps(state, sort_keys=True, separators=(",", ":"))
                + "\n"
            ).encode(),
            b"",
        )
    lane = runner.LaneResult(
        spec,
        "2026-08-13T09:00:00+09:00",
        "2026-08-13T09:00:01+09:00",
        tuple(commands),
        b"",
    )

    with pytest.raises(runner.VerificationError, match="continuity differs"):
        runner._database_runtime_receipt((lane,))


def test_database_function_catalog_excludes_postgis_aggregates() -> None:
    source = inspect.getsource(runner._capture_database_state_receipt)
    function_query = source.split('"functions": (', 1)[1].split(
        '"rls_policies": (', 1
    )[0]

    assert "pg_get_functiondef(p.oid)" in function_query
    assert "WHERE n.nspname = 'public' AND p.prokind = 'f'" in function_query


def test_database_privilege_catalog_query_contract() -> None:
    source = inspect.getsource(runner._capture_database_state_receipt)
    columns_query = source.split('"columns": (', 1)[1].split(
        '"constraints": (', 1
    )[0]
    effective_acl_query = source.split('"effective_acl_privileges": (', 1)[1].split(
        '"walksafe_roles": (', 1
    )[0]
    memberships_query = source.split('"walksafe_role_memberships": (', 1)[1].split(
        "},", 1
    )[0]

    for existing_column_field in (
        "ordinal_position::text",
        "data_type",
        "udt_name",
        "is_nullable",
        "column_default",
    ):
        assert existing_column_field in columns_query
    assert "attacl" not in columns_query
    assert "pg_catalog.pg_attribute AS attribute" in effective_acl_query
    assert "COALESCE(attribute.attacl, pg_catalog.acldefault('c'" in effective_acl_query
    assert "pg_catalog.aclexplode(default_acl.defaclacl)" in effective_acl_query
    assert "COALESCE(default_acl.defaclacl" not in effective_acl_query
    assert "'CURRENT_DATABASE'" in effective_acl_query
    assert '"1,2,3,4,5,6,7,8,9"' in effective_acl_query
    assert "grantor.rolname" in memberships_query
    assert "membership.set_option::text" in memberships_query
    assert "membership.inherit_option::text" in memberships_query


@pytest.mark.parametrize(
    ("component_name", "before_rows", "after_rows"),
    (
        (
            "effective_acl_privileges",
            [[
                "column",
                "public",
                "admin_device_proof_challenges",
                "consumed_at",
                "walksafe_test",
                "walksafe_test",
                "walksafe_backend_runtime",
                "SELECT",
                "false",
            ]],
            [[
                "column",
                "public",
                "admin_device_proof_challenges",
                "consumed_at",
                "walksafe_test",
                "walksafe_test",
                "walksafe_backend_runtime",
                "UPDATE",
                "false",
            ]],
        ),
        (
            "walksafe_role_memberships",
            [["walksafe_owner", "walksafe_runtime", "walksafe_test", "false", "true", "false"]],
            [["walksafe_owner", "walksafe_runtime", "walksafe_test", "false", "true", "true"]],
        ),
        (
            "walksafe_role_memberships",
            [["walksafe_owner", "walksafe_runtime", "walksafe_test", "false", "true", "false"]],
            [["walksafe_owner", "walksafe_runtime", "walksafe_test", "false", "false", "false"]],
        ),
    ),
    ids=("column-attacl", "membership-set-option", "membership-inherit-option"),
)
def test_database_privilege_catalog_mutation_changes_seal_and_fails_continuity(
    component_name: str,
    before_rows: list[list[str]],
    after_rows: list[list[str]],
) -> None:
    before_seal = runner._database_component_seal(before_rows)
    after_seal = runner._database_component_seal(after_rows)
    assert before_seal["row_count"] == after_seal["row_count"]
    assert before_seal["sha256"] != after_seal["sha256"]

    def state_with_seal(phase: str, seal: dict[str, object]) -> dict[str, object]:
        state = database_state(phase, post=phase != "PRE_MIGRATION")
        components = dict(state["catalog_component_seals"])
        components[component_name] = seal
        state["catalog_component_seals"] = components
        state["relevant_state_sha256"] = runner.sha256_bytes(
            runner.canonical_json_bytes(
                {
                    "migration_heads": state["schema_migration_heads"],
                    "components": components,
                }
            )
        )
        return state

    states = (
        database_state("PRE_MIGRATION", post=False),
        state_with_seal("AFTER_MIGRATION", before_seal),
        state_with_seal("AFTER_TEST", after_seal),
    )
    spec = runner.LANE_SPEC_BY_ID["BACKEND_RECOVERY_PYTEST"]
    commands = [
        runner.CommandResult(command.logical_command(), b"", b"")
        for command in spec.commands
    ]
    for index, state in zip((1, 3, 5), states, strict=True):
        commands[index] = runner.CommandResult(
            spec.commands[index].logical_command(),
            (
                runner.DATABASE_STATE_MARKER
                + json.dumps(state, sort_keys=True, separators=(",", ":"))
                + "\n"
            ).encode(),
            b"",
        )
    lane = runner.LaneResult(
        spec,
        "2026-08-13T09:00:00+09:00",
        "2026-08-13T09:00:01+09:00",
        tuple(commands),
        b"",
    )

    with pytest.raises(runner.VerificationError, match="continuity differs"):
        runner._database_runtime_receipt((lane,))


def test_external_tool_binding_rejects_hardlink_and_replacement_race(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    original = tmp_path / "tool"
    original.write_text("#!/bin/sh\nexit 0\n")
    original.chmod(0o755)
    hardlink = tmp_path / "hardlink"
    os.link(original, hardlink)
    with pytest.raises(runner.VerificationError, match="authority differs"):
        runner._external_tool_binding(str(original), "TEST", allow_cached=False)

    hardlink.unlink()
    real_fstat = runner.os.fstat
    calls = 0

    def replace_after_open(descriptor: int) -> os.stat_result:
        nonlocal calls
        value = real_fstat(descriptor)
        calls += 1
        if calls == 1:
            replacement = tmp_path / "replacement"
            replacement.write_text("#!/bin/sh\nexit 1\n")
            replacement.chmod(0o755)
            os.replace(replacement, original)
        return value

    monkeypatch.setattr(runner.os, "fstat", replace_after_open)
    with pytest.raises(runner.VerificationError, match="changed while"):
        runner._external_tool_binding(str(original), "TEST", allow_cached=False)


def test_python_and_jdk_runtime_closures_are_content_bound(tmp_path: Path) -> None:
    runtime = tmp_path / "runtime"
    runtime.mkdir()
    member = runtime / "member.bin"
    member.write_bytes(b"before")
    first = runner._directory_content_binding(runtime, "TEST_RUNTIME", allow_cached=False)
    member.write_bytes(b"after")
    second = runner._directory_content_binding(runtime, "TEST_RUNTIME", allow_cached=False)

    assert first["content_set_sha256"] != second["content_set_sha256"]


def test_python_receipt_replay_preserves_venv_invocation_semantics(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    base_target = Path(sys.executable).resolve(strict=True)
    venv = tmp_path / "venv"
    launcher = venv / "bin/python"
    launcher.parent.mkdir(parents=True)
    intermediate = launcher.with_name("python3")
    launcher.symlink_to(intermediate.name)
    intermediate.symlink_to(base_target)
    version = f"{sys.version_info.major}.{sys.version_info.minor}"
    (venv / "pyvenv.cfg").write_text(
        f"home = {base_target.parent}\n"
        "include-system-site-packages = false\n"
        f"version = {sys.version.split()[0]}\n",
        encoding="utf-8",
    )
    site_packages = venv / f"lib/python{version}/site-packages"
    site_packages.mkdir(parents=True)
    (site_packages / "venv_only_sentinel.py").write_text(
        "VALUE = 'venv-only'\n", encoding="utf-8"
    )
    clean_environment = {"PYTHONNOUSERSITE": "1"}

    venv_result = subprocess.run(
        [str(launcher), "-c", "import venv_only_sentinel"],
        env=clean_environment,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    target_result = subprocess.run(
        [str(base_target), "-c", "import venv_only_sentinel"],
        env=clean_environment,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert venv_result.returncode == 0
    assert target_result.returncode != 0

    monkeypatch.setattr(runner.sys, "executable", str(launcher))
    assert runner._python_invocation_path(str(base_target)) == str(launcher)
    binding = runner._python_executor_binding(
        str(base_target), allow_cached=False
    )
    assert binding["invocation_path"] == str(launcher)
    assert binding["resolved_path"] == str(base_target)
    assert [row["path"] for row in binding["invocation_chain"]] == [
        str(launcher),
        str(intermediate),
        str(base_target),
    ]
    assert [row["kind"] for row in binding["invocation_chain"]] == [
        "SYMLINK",
        "SYMLINK",
        "REGULAR_FILE",
    ]
    monkeypatch.setattr(runner.sys, "prefix", str(venv))
    monkeypatch.setattr(runner.sys, "base_prefix", str(base_target.parent.parent))
    monkeypatch.setattr(runner.sys, "exec_prefix", str(venv))
    monkeypatch.setattr(
        runner.sys, "base_exec_prefix", str(base_target.parent.parent)
    )
    monkeypatch.setattr(
        runner.sysconfig,
        "get_paths",
        lambda: {
            "stdlib": str(base_target.parent.parent / f"lib/python{version}"),
            "platstdlib": str(base_target.parent.parent / f"lib/python{version}"),
            "purelib": str(site_packages),
            "platlib": str(site_packages),
            "include": str(base_target.parent.parent / f"include/python{version}"),
            "platinclude": str(
                base_target.parent.parent / f"include/python{version}"
            ),
            "scripts": str(venv / "bin"),
            "data": str(venv),
        },
    )
    runtime = runner._python_runtime_identity(binding, allow_cached=False)
    assert runtime["executable_invocation_path"] == str(launcher)
    assert runtime["prefixes"] == {
        "prefix": str(venv),
        "exec_prefix": str(venv),
        "base_prefix": str(base_target.parent.parent),
        "base_exec_prefix": str(base_target.parent.parent),
    }
    assert runtime["base_executable"]["resolved_path"] == str(base_target)
    assert {"stdlib", "platstdlib", "purelib", "platlib", "scripts", "data"}.issubset(
        runtime["sysconfig_paths"]
    )
    assert runtime["pyvenv_cfg"]["sha256"] == runner.sha256_bytes(
        (venv / "pyvenv.cfg").read_bytes()
    )
    assert runner.CommandSpec(("python3", "-V")).execution_argv(
        runner._python_invocation_path(str(base_target))
    )[0] == str(launcher)


def test_python_binding_rejects_intermediate_symlink_replacement_race(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    base_target = Path(sys.executable).resolve(strict=True)
    launcher = tmp_path / "venv/bin/python"
    intermediate = launcher.with_name("python3")
    launcher.parent.mkdir(parents=True)
    launcher.symlink_to(intermediate.name)
    intermediate.symlink_to(base_target)
    monkeypatch.setattr(runner.sys, "executable", str(launcher))
    real_binding = runner._external_tool_binding

    def replace_intermediate(*args: object, **kwargs: object) -> dict[str, object]:
        result = real_binding(*args, **kwargs)
        intermediate.unlink()
        intermediate.symlink_to(base_target)
        return result

    monkeypatch.setattr(runner, "_external_tool_binding", replace_intermediate)
    with pytest.raises(runner.VerificationError, match="changed while"):
        runner._python_executor_binding(str(launcher), allow_cached=False)


def test_toolchain_rejects_project_gradle_configuration_injection(
    tmp_path: Path,
) -> None:
    root = make_repository(tmp_path)
    injected = root / "apps/android/gradle.properties"
    injected.write_text("org.gradle.java.home=/tmp/untrusted\n")

    with pytest.raises(runner.VerificationError, match="Gradle configuration injection"):
        build_in_memory(root)


def test_toolchain_rejects_java_home_that_differs_from_path(
    tmp_path: Path,
) -> None:
    root = make_repository(tmp_path)
    visible = runner._capture_visible_inventory(root)
    closure = runner.execution_input_closure(visible)
    alternative_java_home = tmp_path / "alternative-jdk"
    alternative_bin = alternative_java_home / "bin"
    alternative_bin.mkdir(parents=True)
    for executable in ("java", "javac"):
        stub = alternative_bin / executable
        stub.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        stub.chmod(0o755)
    (alternative_java_home / "release").write_text(
        'JAVA_VERSION="21"\n', encoding="utf-8"
    )
    mismatched = environment()
    mismatched["JAVA_HOME"] = str(alternative_java_home)

    with pytest.raises(runner.VerificationError, match="JAVA_HOME and PATH-selected"):
        runner._toolchain_receipt(
            root,
            closure,
            sys.executable,
            environment=mismatched,
            allow_cached=False,
        )


def test_forged_legacy_journal_is_never_promoted(
    tmp_path: Path,
) -> None:
    root = make_repository(tmp_path)
    _, outputs = build_in_memory(root)
    journal = root / runner.TRANSACTION_JOURNAL_REL
    journal.parent.mkdir(parents=True)
    journal.write_text("{}\n")
    journal.chmod(0o600)

    with pytest.raises(runner.VerificationError, match="manual quarantine"):
        runner.recover_publication_transaction(root)
    assert not (root / runner.OBSERVATION_MANIFEST_REL).exists()


def test_published_v2_is_private_secret_free_and_v1_is_unchanged(
    tmp_path: Path,
) -> None:
    root = make_repository(tmp_path)
    v1_before = (root / runner.V1_OBSERVATION_MANIFEST_REL).read_bytes()
    fake = FakeRun(appended_output=TEST_DATABASE_URL.encode("utf-8"))
    _, outputs = build_in_memory(root, fake)

    combined = b"\n".join(content.encode() for content in outputs.values())
    assert TEST_DATABASE_URL.encode("utf-8") not in combined
    assert b"verification-password" not in combined
    assert b"[REDACTED_SECRET" in combined
    assert (root / runner.V1_OBSERVATION_MANIFEST_REL).read_bytes() == v1_before
    assert not any((root / relative).exists() for relative in outputs)
    manifest = json.loads(outputs[runner.OBSERVATION_MANIFEST_REL])
    assert manifest["lane_environment_receipt"]["database_url"]["value_recorded"] is False
    assert TEST_DATABASE_URL not in json.dumps(manifest)


def test_hardlinked_publication_and_staging_members_are_rejected(
    tmp_path: Path,
) -> None:
    root = make_repository(tmp_path)
    stage = root / runner.PUBLICATION_STAGE_REL
    stage.mkdir(parents=True, mode=0o700)
    source = root / "stage-hardlink-source"
    source.write_text("forged\n")
    source.chmod(0o600)
    os.link(source, stage / "member")
    with pytest.raises(runner.VerificationError, match="staging member authority differs"):
        runner.recover_publication_transaction(root)


def _make_staged_outputs(
    root: Path,
) -> tuple[int, int, dict[Path, str], tuple[int, ...]]:
    result = root / runner.RESULT_DIR_REL
    result_descriptor = os.open(result, os.O_RDONLY | os.O_DIRECTORY)
    os.mkdir(
        runner.PUBLICATION_STAGE_REL.name,
        runner.SNAPSHOT_DIRECTORY_MODE,
        dir_fd=result_descriptor,
    )
    stage_descriptor = os.open(
        runner.PUBLICATION_STAGE_REL.name,
        os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
        dir_fd=result_descriptor,
    )
    empty_identity = runner._stable_identity(os.fstat(stage_descriptor))
    outputs = {
        runner.CORRECTION_DIR_REL / "logs/test.log": "sealed log\n",
        runner.CORRECTION_DIR_REL / "test.json": "{}\n",
    }
    for relative, content in outputs.items():
        runner._write_private_exclusive_at(
            stage_descriptor,
            relative.relative_to(runner.CORRECTION_DIR_REL),
            content.encode(),
        )
    return result_descriptor, stage_descriptor, outputs, empty_identity


def test_normal_staged_transaction_reaches_descriptor_rename(tmp_path: Path) -> None:
    root = make_repository(tmp_path)
    result_descriptor, stage_descriptor, outputs, empty_identity = (
        _make_staged_outputs(root)
    )
    try:
        first = runner._staged_outputs_seal_at(
            result_descriptor,
            stage_descriptor,
            runner.PUBLICATION_STAGE_REL.name,
            outputs,
        )
        second = runner._staged_outputs_seal_at(
            result_descriptor,
            stage_descriptor,
            runner.PUBLICATION_STAGE_REL.name,
            outputs,
        )
        assert first == second
        assert empty_identity != runner._stable_identity(os.fstat(stage_descriptor))
        os.rename(
            runner.PUBLICATION_STAGE_REL.name,
            runner.CORRECTION_DIR_REL.name,
            src_dir_fd=result_descriptor,
            dst_dir_fd=result_descriptor,
        )
    finally:
        os.close(stage_descriptor)
        os.close(result_descriptor)

    assert (root / runner.CORRECTION_DIR_REL / "logs/test.log").read_text() == (
        "sealed log\n"
    )


@pytest.mark.parametrize(
    "mutation", ("overwrite", "add", "delete", "replace", "mode", "hardlink", "symlink")
)
def test_recursive_stage_seal_rejects_child_mutation_before_rename(
    tmp_path: Path, mutation: str
) -> None:
    root = make_repository(tmp_path)
    result_descriptor, stage_descriptor, outputs, _ = _make_staged_outputs(root)
    stage = root / runner.PUBLICATION_STAGE_REL
    target = stage / "logs/test.log"
    runner._staged_outputs_seal_at(
        result_descriptor,
        stage_descriptor,
        runner.PUBLICATION_STAGE_REL.name,
        outputs,
    )
    if mutation == "overwrite":
        target.write_text("changed\n")
    elif mutation == "add":
        extra = stage / "extra"
        extra.write_text("extra\n")
        extra.chmod(0o600)
    elif mutation == "delete":
        target.unlink()
    elif mutation == "replace":
        replacement = stage / "replacement"
        replacement.write_text("changed\n")
        replacement.chmod(0o600)
        os.replace(replacement, target)
    elif mutation == "mode":
        target.chmod(0o644)
    elif mutation == "hardlink":
        target.unlink()
        source = root / "hardlink-source"
        source.write_text("sealed log\n")
        source.chmod(0o600)
        os.link(source, target)
    else:
        target.unlink()
        target.symlink_to(root / "tracked-source.txt")
    try:
        with pytest.raises(runner.VerificationError):
            runner._staged_outputs_seal_at(
                result_descriptor,
                stage_descriptor,
                runner.PUBLICATION_STAGE_REL.name,
                outputs,
            )
        assert not (root / runner.CORRECTION_DIR_REL).exists()
    finally:
        os.close(stage_descriptor)
        os.close(result_descriptor)
