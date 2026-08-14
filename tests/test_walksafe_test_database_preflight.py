from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess
import sys

import pytest

from scripts.check_walksafe_test_database_20260713 import (
    database_name,
    validate_test_database_url,
)
from scripts.run_walksafe_isolated_python_20260713 import (
    _locked_versions,
    _normalize_distribution_name,
)


CPU_TORCH_URL = (
    "https://download.pytorch.org/whl/cpu/"
    "torch-2.11.0%2Bcpu-cp312-cp312-manylinux_2_28_x86_64.whl"
    "#sha256=f82e2ae20c1545bb03997d1cc3143d94e14b800038669ee1aca45808a9acc338"
)
CPU_TORCHVISION_URL = (
    "https://download.pytorch.org/whl/cpu/"
    "torchvision-0.26.0%2Bcpu-cp312-cp312-manylinux_2_28_x86_64.whl"
    "#sha256=cf547dc0975eb40bc3249be4ccbeb736597d2c3ece305b1c4e5b7a5dd7363567"
)


def _logical_requirements(path: Path) -> list[str]:
    logical: list[str] = []
    pending = ""
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.endswith("\\"):
            pending += line[:-1].strip() + " "
            continue
        logical.append((pending + line).strip())
        pending = ""
    assert not pending
    return logical


def _source_versions(*paths: Path) -> dict[str, str]:
    pattern = re.compile(
        r"^([A-Za-z0-9_.-]+)(?:\[[A-Za-z0-9_,.-]+\])?==([^\s;]+)$"
    )
    versions: dict[str, str] = {}
    for path in paths:
        for line in _logical_requirements(path):
            matched = pattern.fullmatch(line)
            assert matched is not None
            name = _normalize_distribution_name(matched.group(1))
            assert name not in versions
            versions[name] = matched.group(2)
    return versions


def _cpu_lock_versions(path: Path) -> tuple[dict[str, str], dict[str, str]]:
    pinned = re.compile(
        r"^([A-Za-z0-9_.-]+)(?:\[[A-Za-z0-9_,.-]+\])?==([^\s;]+)"
        r"((?:\s+--hash=sha256:[0-9a-f]{64})+)$"
    )
    direct = re.compile(
        r"^([A-Za-z0-9_.-]+) @ (https://\S+#sha256=([0-9a-f]{64}))"
        r"((?:\s+--hash=sha256:[0-9a-f]{64})+)$"
    )
    expected_direct = {
        "torch": ("2.11.0+cpu", CPU_TORCH_URL),
        "torchvision": ("0.26.0+cpu", CPU_TORCHVISION_URL),
    }
    versions: dict[str, str] = {}
    direct_urls: dict[str, str] = {}
    for line in _logical_requirements(path):
        matched = pinned.fullmatch(line)
        if matched is not None:
            name = _normalize_distribution_name(matched.group(1))
            version = matched.group(2)
        else:
            matched = direct.fullmatch(line)
            assert matched is not None
            name = _normalize_distribution_name(matched.group(1))
            assert name in expected_direct
            version, expected_url = expected_direct[name]
            assert matched.group(2) == expected_url
            assert re.findall(r"--hash=sha256:([0-9a-f]{64})", matched.group(4)) == [
                matched.group(3)
            ]
            direct_urls[name] = matched.group(2)
        assert name not in versions
        versions[name] = version
    return versions, direct_urls


def test_accepts_explicit_postgresql_test_database() -> None:
    url = "postgresql+psycopg://tester:secret@127.0.0.1:5432/walksafe_test"
    assert database_name(url) == "walksafe_test"
    assert validate_test_database_url(url) == "walksafe_test"


@pytest.mark.parametrize(
    "url",
    [
        "",
        "sqlite:///walksafe_test.sqlite",
        "postgresql://tester@localhost/walksafe_test",
        "postgresql://tester@localhost/postgres",
        "postgresql://tester@localhost/walksafe",
        "postgresql://tester@localhost/walksafe_dev",
    ],
)
def test_rejects_missing_non_postgresql_or_non_test_database(url: str) -> None:
    with pytest.raises(ValueError):
        validate_test_database_url(url)


def test_rejects_same_database_name_as_operator_even_when_hosts_differ() -> None:
    with pytest.raises(ValueError, match="must differ"):
        validate_test_database_url(
            "postgresql+psycopg://tester@localhost/walksafe_test",
            "postgresql://operator@db.internal/walksafe_test",
        )


def test_root_test_dependency_lock_matches_its_source() -> None:
    source_versions = _source_versions(Path("tests/requirements.txt"))
    test_versions = _locked_versions(Path("tests/requirements.lock"))
    assert source_versions.items() <= test_versions.items()


def test_hosted_cpu_lock_matches_sources_and_production_pins() -> None:
    cpu_input = _logical_requirements(
        Path("tests/general-quality-cp312-linux-x86_64-cpu.in")
    )
    assert cpu_input == [
        "-c ../backend/requirements.lock",
        "-r ../backend/requirements.txt",
        "-r requirements.txt",
        f"torch @ {CPU_TORCH_URL}",
        f"torchvision @ {CPU_TORCHVISION_URL}",
    ]

    source_versions = _source_versions(
        Path("backend/requirements.txt"),
        Path("tests/requirements.txt"),
    )
    backend_versions = _locked_versions(Path("backend/requirements.lock"))
    cpu_versions, direct_urls = _cpu_lock_versions(
        Path("tests/general-quality-cp312-linux-x86_64-cpu.lock")
    )
    assert source_versions.items() <= cpu_versions.items()
    assert direct_urls == {
        "torch": CPU_TORCH_URL,
        "torchvision": CPU_TORCHVISION_URL,
    }
    for name in backend_versions.keys() & cpu_versions.keys():
        expected_version = backend_versions[name]
        if name in direct_urls:
            expected_version += "+cpu"
        assert cpu_versions[name] == expected_version
    assert not {
        name
        for name in cpu_versions
        if name.startswith(("cuda", "nvidia")) or "triton" in name
    }


def test_backup_integrity_cp314_lock_is_minimal_and_hash_locked() -> None:
    assert _logical_requirements(Path("tests/backup-integrity-cp314.in")) == [
        "-c ../backend/requirements.lock",
        "pytest==8.4.2",
    ]
    assert _locked_versions(Path("tests/backup-integrity-cp314.lock")) == {
        "iniconfig": "2.3.0",
        "packaging": "26.2",
        "pluggy": "1.6.0",
        "pygments": "2.20.0",
        "pytest": "8.4.2",
    }
    assert Path("tests/backup-integrity-cp314.lock").read_text(encoding="utf-8").count(
        "--hash=sha256:"
    ) == 5


def test_layer_runner_migrates_the_verified_test_database_before_tests() -> None:
    runner = Path("scripts/run_walksafe_test_layers_current.sh").read_text(encoding="utf-8")
    preflight = runner.index("check_walksafe_test_database_20260713.py")
    migration = runner.index('DATABASE_URL="${WALKSAFE_TEST_DATABASE_URL}"', preflight)
    test_entrypoint = runner.index("run_functional()", migration)

    assert migration < test_entrypoint
    assert 'backend/alembic.ini" upgrade head' in runner[migration:test_entrypoint]
    assert 'next_env_backup="$(mktemp)"' in runner
    assert 'cp -- "${next_env_backup}" "${next_env}"' in runner
    assert 'rev-parse --absolute-git-dir' in runner
    assert 'exec 9>"${web_lock_path}"' in runner
    assert 'rm -rf -- "${web_dist_path}"' in runner
    web_lock = runner.index("flock -x 9")
    backup = runner.index('next_env_backup="$(mktemp)"', web_lock)
    snapshot = runner.index('cp -- "${next_env}" "${next_env_backup}"', backup)
    restore_trap = runner.index('cp -- "${next_env_backup}" "${next_env}"', snapshot)
    web_build = runner.index("run_locked_npm run build", restore_trap)
    assert web_lock < backup < snapshot < restore_trap < web_build


def test_current_runner_omits_unpreserved_v25_candidate_test() -> None:
    candidate_test = "tests/test_walksafe_v2_5_control_candidate_20260730.py"
    ignored_paths = Path(".gitignore").read_text(encoding="utf-8").splitlines()
    current_runner = Path("scripts/run_walksafe_test_layers_current.sh").read_text(
        encoding="utf-8"
    )
    frozen_runner = Path("scripts/run_walksafe_test_layers_20260711.sh").read_text(
        encoding="utf-8"
    )

    assert f"/{candidate_test}" in ignored_paths
    assert candidate_test not in current_runner
    assert candidate_test in frozen_runner


def test_quality_workflow_uses_checksum_pinned_current_tree_secret_scan() -> None:
    workflow = Path(".github/workflows/quality.yml").read_text(encoding="utf-8")

    checkout = workflow.index("actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5")
    setup_python = workflow.index(
        "actions/setup-python@a26af69be951a213d495a4c3e4e4022e16d87065"
    )
    restore_modes = workflow.index("Restore private evidence modes")
    secret_scan = workflow.index("Verify current tree contains no new secrets")
    dependency_install = workflow.index("Install Node regression dependencies")
    assert checkout < setup_python < restore_modes < secret_scan < dependency_install
    assert workflow.count("actions/setup-python@") == 2
    assert 'python-version: "3.14.6"' in workflow
    assert "update-environment: false" in workflow
    assert "steps.backup_python.outputs.python-path" in workflow
    assert "steps.general_base_python.outputs.python-path" in workflow
    assert (
        '"${GENERAL_BASE_PYTHON}" -I -B scripts/restore_walksafe_private_evidence_modes.py'
        in workflow
    )
    assert 'GITLEAKS_VERSION: "8.30.1"' in workflow
    assert (
        'GITLEAKS_ARCHIVE_SHA256: "551f6fc83ea457d62a0d98237cbad105a'
        'f8d557003051f41f3e7ca7b3f2470eb"'
    ) in workflow
    assert "sha256sum --check --strict" in workflow
    assert '"${binary}" dir' in workflow
    assert '--report-format json --report-path "${report}"' in workflow
    assert "--exit-code 1 . || scan_status=$?" in workflow
    assert "scripts/check_walksafe_gitleaks_report.py" in workflow
    assert "configs/walksafe_gitleaks_reviewed_findings.json" in workflow
    assert "--baseline-path" not in workflow
    assert "--max-target-megabytes" not in workflow
    assert "--redact" not in workflow
    assert 'rm -f -- "${report}"' in workflow
    assert '"pip==26.1.1"' not in workflow


def test_layer_runner_assigns_model_runtime_pytest_to_unit(tmp_path: Path) -> None:
    runner = Path("scripts/run_walksafe_test_layers_current.sh").read_text(encoding="utf-8")

    assert "model/test_two_model_runtime.py" in runner
    assert runner.count("tests/test_report_retention_scheduler.py") == 1
    assert runner.count("tests/test_walksafe_backup_integrity.py") == 1
    assert "BACKUP_INTEGRITY_PYTHON_TESTS=(" in runner
    assert 'BACKUP_PYTHON_BIN="${WALKSAFE_BACKUP_PYTHON_BIN:-}"' in runner
    assert '"${runtime_identity}" == "cpython 3 14 6"' in runner
    assert 'pytest.__version__ == "8.4.2"' in runner
    assert "find backend/tests tests model -type f -name 'test_*.py'" in runner
    assert "export PYTHONDONTWRITEBYTECODE=1" in runner
    assert "export PYTEST_DISABLE_PLUGIN_AUTOLOAD=1" in runner
    for function_name in (
        "run_unit",
        "run_active_session_control",
        "run_model_audit",
        "run_functional",
        "run_integration",
    ):
        function = re.search(
            rf"(?ms)^{function_name}\(\).*?(?=^[a-z_]+\(\)|^validate_layer_configuration$)",
            runner,
        )
        assert function is not None
        assert "-p no:cacheprovider" in function.group()
    assert "check_walksafe_node_toolchain_20260715.py" in runner
    assert 'export PATH="${LOCKED_NODE_BIN_DIR}:/usr/bin:/bin"' in runner
    assert '/usr/bin/env -i "${npm_environment[@]}" "${LOCKED_NODE_BIN_DIR}/npm" "$@"' in runner
    assert len(re.findall(r"(?m)^\s*run_locked_npm(?:\(\)|\s)", runner)) == 5
    assert len(re.findall(r"(?m)^\s*run_locked_gateway_npm(?:\(\)|\s)", runner)) == 3
    assert len(re.findall(r"(?m)^\s*run_locked_npm_in(?:\(\)|\s)", runner)) == 3
    all_start = runner.index("  all)\n")
    all_dispatch = runner[all_start : runner.index("\n    ;;", all_start)]
    assert all_dispatch == (
        "  all)\n"
        "    run_unit\n"
        "    run_functional\n"
        "    run_integration"
    )
    for historical_test in (
        "tests/test_release_evidence_gate.py",
        "tests/test_submission_toolchain_host_lock_20260713_history.py",
        "tests/test_walksafe_operator_attestation.py",
        "tests/test_walksafe_product_quality_receipt.py",
    ):
        assert historical_test not in runner[
            runner.index("UNIT_PYTHON_TESTS=(") : runner.index(
                "HISTORICAL_CONTROL_PYTHON_TESTS=("
            )
        ]
    assert "./gradlew testDebugUnitTest --no-daemon --rerun-tasks" in runner
    assert (
        'WALKSAFE_ADMIN_API_ORIGIN="${WALKSAFE_RELEASE_TEST_ADMIN_API_ORIGIN:-'
        'https://admin.walksafe.invalid}"'
    ) in runner
    integration_function = re.search(
        r"(?ms)^run_integration\(\).*?(?=^validate_layer_configuration$)", runner
    )
    assert integration_function is not None
    release_block = integration_function.group()
    release_command = (
        '      WALKSAFE_SOURCE_COMMIT="${source_commit}" \\\n'
        '      WALKSAFE_GATEWAY_ORIGIN="${WALKSAFE_RELEASE_TEST_GATEWAY_ORIGIN:-'
        'https://walksafe.invalid}" \\\n'
        '      WALKSAFE_ADMIN_API_ORIGIN="${WALKSAFE_RELEASE_TEST_ADMIN_API_ORIGIN:-'
        'https://admin.walksafe.invalid}" \\\n'
        "      ./gradlew lintRelease assembleRelease --no-daemon"
    )
    assert release_block.count(release_command) == 1

    capture = tmp_path / "pytest-argv.txt"
    environment_capture = tmp_path / "pytest-environment.txt"
    fake_python = tmp_path / "python"
    fake_python.write_text(
        "#!/bin/sh\n"
        "case \" $* \" in\n"
        "  *check_walksafe_node_toolchain_20260715.py*) exit 0 ;;\n"
        "esac\n"
        "printf '%s\\n' \"$@\" > \"$WALKSAFE_TEST_ARGV_CAPTURE\"\n"
        "printf 'PYTEST_ADDOPTS=%s\\nPYTEST_PLUGINS=%s\\n' "
        "\"${PYTEST_ADDOPTS+present}\" \"${PYTEST_PLUGINS+present}\" "
        "> \"$WALKSAFE_TEST_ENV_CAPTURE\"\n"
        "printf 'WALKSAFE_TEST_DATABASE_URL=%s\\nDATABASE_URL=%s\\n' "
        "\"${WALKSAFE_TEST_DATABASE_URL+present}\" \"${DATABASE_URL+present}\" "
        ">> \"$WALKSAFE_TEST_ENV_CAPTURE\"\n"
        "printf 'PATH=%s\\n' \"$PATH\" >> \"$WALKSAFE_TEST_ENV_CAPTURE\"\n"
        "exit 23\n",
        encoding="utf-8",
    )
    fake_python.chmod(0o755)
    fake_node_bin = tmp_path / "locked-node/bin"
    fake_node_bin.mkdir(parents=True)
    completed = subprocess.run(
        ["bash", "scripts/run_walksafe_test_layers_current.sh", "unit"],
        cwd=Path(__file__).resolve().parents[1],
        env={
            **os.environ,
            "PYTHON_BIN": str(fake_python),
            "WALKSAFE_NODE_BIN_DIR": str(fake_node_bin),
            "PYTEST_ADDOPTS": "--deselect=model/test_two_model_runtime.py",
            "PYTEST_PLUGINS": "malicious_pytest_plugin",
            "WALKSAFE_TEST_DATABASE_URL": (
                "postgresql+psycopg://walksafe_test:unsafe@127.0.0.1/walksafe_test"
            ),
            "DATABASE_URL": "postgresql+psycopg://walksafe:unsafe@db/walksafe",
            "WALKSAFE_TEST_ARGV_CAPTURE": str(capture),
            "WALKSAFE_TEST_ENV_CAPTURE": str(environment_capture),
        },
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 23
    argv = capture.read_text(encoding="utf-8").splitlines()
    assert argv[:2] == ["-m", "pytest"]
    assert "model/test_two_model_runtime.py" in argv
    selection_options = {
        "-k",
        "-m",
        "--deselect",
        "--ignore",
        "--ignore-glob",
        "--collect-only",
        "--co",
        "--lf",
        "--last-failed",
    }
    assert not any(argument.split("=", 1)[0] in selection_options for argument in argv[2:])
    assert environment_capture.read_text(encoding="utf-8") == (
        "PYTEST_ADDOPTS=\nPYTEST_PLUGINS=\n"
        "WALKSAFE_TEST_DATABASE_URL=\nDATABASE_URL=\n"
        f"PATH={fake_node_bin}:/usr/bin:/bin\n"
    )


def test_integration_runner_does_not_fall_back_to_general_python_for_backup(
    tmp_path: Path,
) -> None:
    fake_general_python = tmp_path / "general-python"
    fake_general_python.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    fake_general_python.chmod(0o755)
    environment = {key: value for key, value in os.environ.items() if key != "WALKSAFE_BACKUP_PYTHON_BIN"}
    environment.update(
        {
            "PYTHON_BIN": str(fake_general_python),
            "WALKSAFE_TEST_DATABASE_URL": (
                "postgresql+psycopg://walksafe_test:test@127.0.0.1/walksafe_test"
            ),
        }
    )

    completed = subprocess.run(
        ["bash", "scripts/run_walksafe_test_layers_current.sh", "integration"],
        cwd=Path(__file__).resolve().parents[1],
        env=environment,
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 2
    assert "WALKSAFE_BACKUP_PYTHON_BIN is required" in completed.stderr


def test_integration_runner_executes_only_backup_test_with_dedicated_python(
    tmp_path: Path,
) -> None:
    fake_general_python = tmp_path / "general-python"
    fake_general_python.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    fake_general_python.chmod(0o755)
    capture = tmp_path / "backup-python-argv.txt"
    fake_backup_python = tmp_path / "backup-python"
    fake_backup_python.write_text(
        "#!/bin/sh\n"
        "case \" $* \" in\n"
        "  *sys.implementation.name*) printf 'cpython 3 14 6\\n'; exit 0 ;;\n"
        "  *--runtime-capability-preflight*) exit 0 ;;\n"
        "  *pytest.__version__*) exit 0 ;;\n"
        "esac\n"
        "printf '%s\\n' \"$@\" > \"$WALKSAFE_BACKUP_ARGV_CAPTURE\"\n"
        "exit 23\n",
        encoding="utf-8",
    )
    fake_backup_python.chmod(0o755)
    completed = subprocess.run(
        ["bash", "scripts/run_walksafe_test_layers_current.sh", "integration"],
        cwd=Path(__file__).resolve().parents[1],
        env={
            **os.environ,
            "PYTHON_BIN": str(fake_general_python),
            "WALKSAFE_BACKUP_PYTHON_BIN": str(fake_backup_python),
            "WALKSAFE_BACKUP_ARGV_CAPTURE": str(capture),
            "WALKSAFE_TEST_DATABASE_URL": (
                "postgresql+psycopg://walksafe_test:test@127.0.0.1/walksafe_test"
            ),
        },
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 23
    argv = capture.read_text(encoding="utf-8").splitlines()
    assert argv[:2] == ["-m", "pytest"]
    assert argv.count("tests/test_walksafe_backup_integrity.py") == 1
    assert "tests/test_runtime_model_integration.py" not in argv


def test_integration_runner_rejects_nonexact_backup_python(tmp_path: Path) -> None:
    fake_general_python = tmp_path / "general-python"
    fake_general_python.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    fake_general_python.chmod(0o755)
    fake_backup_python = tmp_path / "backup-python"
    fake_backup_python.write_text(
        "#!/bin/sh\n"
        "case \" $* \" in\n"
        "  *sys.implementation.name*) printf 'cpython 3 14 5\\n'; exit 0 ;;\n"
        "esac\n"
        "exit 0\n",
        encoding="utf-8",
    )
    fake_backup_python.chmod(0o755)

    completed = subprocess.run(
        ["bash", "scripts/run_walksafe_test_layers_current.sh", "integration"],
        cwd=Path(__file__).resolve().parents[1],
        env={
            **os.environ,
            "PYTHON_BIN": str(fake_general_python),
            "WALKSAFE_BACKUP_PYTHON_BIN": str(fake_backup_python),
            "WALKSAFE_TEST_DATABASE_URL": (
                "postgresql+psycopg://walksafe_test:test@127.0.0.1/walksafe_test"
            ),
        },
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 2
    assert "must be exact CPython 3.14.6" in completed.stderr


def test_layer_runner_removes_ambient_node_execution_options(tmp_path: Path) -> None:
    source_root = Path(__file__).resolve().parents[1]
    fake_python = tmp_path / "python"
    fake_python.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    fake_python.chmod(0o755)
    fake_node_bin = tmp_path / "locked-node/bin"
    fake_node_bin.mkdir(parents=True)
    npm_environment_capture = tmp_path / "npm-environment.txt"
    fake_npm = fake_node_bin / "npm"
    fake_npm.write_text(
        "#!/bin/sh\n"
        f"{{\n"
        "  printf 'NODE_OPTIONS=%s\\n' \"${NODE_OPTIONS+present}\"\n"
        "  printf 'NODE_PATH=%s\\n' \"${NODE_PATH+present}\"\n"
        "  printf 'NPM_CONFIG_NODE_OPTIONS=%s\\n' \"${NPM_CONFIG_NODE_OPTIONS+present}\"\n"
        "  printf 'npm_config_node_options=%s\\n' \"${npm_config_node_options+present}\"\n"
        "  printf 'PATH=%s\\nHOME=%s\\n' \"$PATH\" \"$HOME\"\n"
        "  printf 'USERCONFIG=%s\\nGLOBALCONFIG=%s\\n' \"$NPM_CONFIG_USERCONFIG\" \"$NPM_CONFIG_GLOBALCONFIG\"\n"
        f"}} > '{npm_environment_capture}'\n"
        "exit 23\n",
        encoding="utf-8",
    )
    fake_npm.chmod(0o755)

    completed = subprocess.run(
        ["bash", "scripts/run_walksafe_test_layers_current.sh", "unit"],
        cwd=source_root,
        env={
            **os.environ,
            "PYTHON_BIN": str(fake_python),
            "WALKSAFE_NODE_BIN_DIR": str(fake_node_bin),
            "NODE_OPTIONS": "--require=/tmp/untrusted-walksafe-hook.cjs",
            "NODE_PATH": "/tmp/untrusted-walksafe-modules",
            "NPM_CONFIG_NODE_OPTIONS": "--require=/tmp/untrusted-walksafe-npm-hook.cjs",
            "npm_config_node_options": "--require=/tmp/untrusted-walksafe-lower-hook.cjs",
        },
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 23
    lines = npm_environment_capture.read_text(encoding="utf-8").splitlines()
    assert lines[:4] == [
        "NODE_OPTIONS=",
        "NODE_PATH=",
        "NPM_CONFIG_NODE_OPTIONS=",
        "npm_config_node_options=",
    ]
    assert lines[4] == f"PATH={fake_node_bin}:/usr/bin:/bin"
    private_home = Path(lines[5].removeprefix("HOME="))
    assert private_home.name.startswith("walksafe-test-node.")
    assert lines[6] == f"USERCONFIG={private_home}/npmrc"
    assert lines[7] == "GLOBALCONFIG=/dev/null"
    assert not private_home.exists()


@pytest.mark.parametrize("layer", ["functional", "integration"])
def test_ambient_database_ready_flag_cannot_bypass_preflight(layer: str) -> None:
    environment = {
        **os.environ,
        "PYTHON_BIN": sys.executable,
        "TEST_DATABASE_READY": "true",
    }
    environment.pop("WALKSAFE_TEST_DATABASE_URL", None)

    completed = subprocess.run(
        ["bash", "scripts/run_walksafe_test_layers_current.sh", layer],
        cwd=Path(__file__).resolve().parents[1],
        env=environment,
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 2
    assert "WALKSAFE_TEST_DATABASE_URL is required" in completed.stderr
