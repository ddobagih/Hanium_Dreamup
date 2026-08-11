import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile

import pytest

from scripts.check_walksafe_node_toolchain_20260715 import (
    ROOT_CLOSURE_ALGORITHM,
    _directory_closure,
    _regular_file_record,
    _symlink_record,
)
from scripts import create_walksafe_web_build_manifest_20260711 as manifest_creator


create_manifest = manifest_creator.create_manifest


SOURCE_COMMIT = "a" * 40


def _fake_node_toolchain(tmp_path: Path, lock_path: Path) -> Path:
    root = tmp_path / "node-root"
    npm_bin = root / "lib/node_modules/npm/bin"
    npm_bin.mkdir(parents=True)
    node = root / "bin/node"
    node.parent.mkdir(parents=True)
    node.write_text(
        """#!/bin/sh
if [ "$#" -eq 1 ] && [ "$1" = "--version" ]; then
  printf 'v22.23.1\\n'
elif [ "$#" -eq 2 ] && [ "$1" = "-p" ]; then
  printf '["linux","x64"]\\n'
elif [ "$#" -eq 2 ] && [ "$2" = "--version" ]; then
  printf '10.9.8\\n'
else
  exit 64
fi
""",
        encoding="utf-8",
    )
    node.chmod(0o755)
    npm_cli = npm_bin / "npm-cli.js"
    npm_cli.write_text(
        "#!/bin/sh\n"
        "if [ -n \"$NODE_OPTIONS\" ] || [ -n \"$NODE_PATH\" ] || "
        "[ -n \"$NPM_CONFIG_NODE_OPTIONS\" ] || [ -n \"$npm_config_node_options\" ]; then\n"
        f"  : > '{tmp_path / 'ambient-node-options-executed'}'\n"
        "fi\n"
        "printf 'fake npm ci reached\\n'\n"
        "exit 23\n",
        encoding="utf-8",
    )
    npm_cli.chmod(0o755)
    npm_root = root / "lib/node_modules/npm"
    (npm_root / "package.json").write_text(
        json.dumps({"name": "npm", "version": "10.9.8"}) + "\n",
        encoding="utf-8",
    )
    (npm_root / "README.md").write_text("npm fixture\n", encoding="utf-8")
    (root / "bin/npm").symlink_to("../lib/node_modules/npm/bin/npm-cli.js")
    launcher, resolved = _symlink_record(root / "bin/npm", "bin/npm", root)
    lock = {
        "schema_version": "walksafe.node-toolchain.v2",
        "official_archive": {
            "name": "node-v22.23.1-linux-x64.tar.xz",
            "url": "https://nodejs.org/dist/v22.23.1/node-v22.23.1-linux-x64.tar.xz",
            "sha256": "9749e988f437343b7fa832c69ded82a312e41a03116d766797ac14f6f9eee578",
            "shasums_url": "https://nodejs.org/dist/v22.23.1/SHASUMS256.txt",
        },
        "platform": {
            "system": "Linux",
            "machine": "x86_64",
            "node_platform": "linux",
            "node_arch": "x64",
        },
        "root": {
            "path": ".",
            "closure": _directory_closure(root, algorithm=ROOT_CLOSURE_ALGORITHM),
        },
        "node": {"version": "v22.23.1", **_regular_file_record(node, "bin/node")},
        "npm": {
            "version": "10.9.8",
            "launcher": {**launcher, "resolved_path": resolved.relative_to(root).as_posix()},
            "package": {
                "path": "lib/node_modules/npm",
                "closure": _directory_closure(npm_root),
            },
        },
    }
    lock_path.parent.mkdir(parents=True)
    lock_path.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    return root


def test_web_release_builder_requires_absolute_node_tool_directory(tmp_path: Path) -> None:
    completed = subprocess.run(
        ["bash", "scripts/build_walksafe_web_release_20260711.sh", str(tmp_path / "manifest.json")],
        cwd=Path(__file__).resolve().parents[1],
        env={
            **os.environ,
            "PYTHON_BIN": sys.executable,
            "WALKSAFE_NODE_BIN_DIR": "relative-node-bin",
        },
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 78
    assert "LEGACY_REFERENCE_ONLY" in completed.stderr
    assert not (tmp_path / "manifest.json").exists()


def test_web_release_builder_rejects_missing_node_tool_directory(tmp_path: Path) -> None:
    environment = {key: value for key, value in os.environ.items() if key != "WALKSAFE_NODE_BIN_DIR"}
    environment["PYTHON_BIN"] = sys.executable
    completed = subprocess.run(
        ["bash", "scripts/build_walksafe_web_release_20260711.sh", str(tmp_path / "manifest.json")],
        cwd=Path(__file__).resolve().parents[1],
        env=environment,
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 78
    assert "LEGACY_REFERENCE_ONLY" in completed.stderr
    assert not (tmp_path / "manifest.json").exists()


def test_web_release_builder_writes_relative_output_receipt_from_web_directory(
    tmp_path: Path,
) -> None:
    source_root = Path(__file__).resolve().parents[1]
    repo = tmp_path / "repo"
    scripts = repo / "scripts"
    web = repo / "apps/web"
    scripts.mkdir(parents=True)
    web.mkdir(parents=True)
    shutil.copy2(
        source_root / "scripts/build_walksafe_web_release_20260711.sh",
        scripts / "build_walksafe_web_release_20260711.sh",
    )
    shutil.copy2(
        source_root / "scripts/run_walksafe_isolated_python_20260713.py",
        scripts / "run_walksafe_isolated_python_20260713.py",
    )
    shutil.copy2(
        source_root / "scripts/check_walksafe_node_toolchain_20260715.py",
        scripts / "check_walksafe_node_toolchain_20260715.py",
    )
    (web / "package.json").write_text("{}\n", encoding="utf-8")
    (web / "package-lock.json").write_text("{}\n", encoding="utf-8")
    node_root = _fake_node_toolchain(
        tmp_path,
        repo / "configs/walksafe_node_toolchain_lock_20260715.json",
    )
    subprocess.run(["/usr/bin/git", "init", "--quiet", str(repo)], check=True)
    subprocess.run(
        ["/usr/bin/git", "-C", str(repo), "config", "user.name", "WalkSafe test"],
        check=True,
    )
    subprocess.run(
        [
            "/usr/bin/git",
            "-C",
            str(repo),
            "config",
            "user.email",
            "test@example.invalid",
        ],
        check=True,
    )
    subprocess.run(["/usr/bin/git", "-C", str(repo), "add", "."], check=True)
    subprocess.run(
        ["/usr/bin/git", "-C", str(repo), "commit", "--quiet", "-m", "fixture"],
        check=True,
    )

    output = Path("artifacts/ci/web-build-manifest.json")
    environment = {
        key: value
        for key, value in os.environ.items()
        if not key.startswith("NEXT_PUBLIC_")
    }
    environment.update(
        {
            "PYTHON_BIN": sys.executable,
            "WALKSAFE_NODE_BIN_DIR": str(node_root / "bin"),
            "NODE_OPTIONS": "--require=/tmp/untrusted-walksafe-hook.cjs",
            "NODE_PATH": "/tmp/untrusted-walksafe-modules",
            "NPM_CONFIG_NODE_OPTIONS": "--require=/tmp/untrusted-walksafe-npm-hook.cjs",
            "npm_config_node_options": "--require=/tmp/untrusted-walksafe-lower-hook.cjs",
        }
    )
    completed = subprocess.run(
        [str(scripts / "build_walksafe_web_release_20260711.sh"), str(output)],
        cwd=repo,
        env=environment,
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 78
    assert "LEGACY_REFERENCE_ONLY" in completed.stderr
    assert not (repo / output).exists()
    assert not (repo / output.parent).exists()
    assert not (tmp_path / "ambient-node-options-executed").exists()


def test_quality_workflow_keeps_web_regression_but_not_a_web_release_artifact() -> None:
    workflow = Path(".github/workflows/quality.yml").read_text(encoding="utf-8")
    current_runner = Path("scripts/run_walksafe_test_layers_current.sh").read_text(
        encoding="utf-8"
    )
    setup_python = workflow.index(
        "actions/setup-python@a26af69be951a213d495a4c3e4e4022e16d87065"
    )
    regression_install = workflow.index("Install Node regression dependencies")
    general_install = workflow.index(
        "Install hash-locked general test environment", regression_install
    )
    backup_setup = workflow.index("actions/setup-python@", setup_python + 1)
    backup_install = workflow.index(
        "Install hash-locked backup integrity environment", backup_setup
    )
    openapi = workflow.index("Verify canonical OpenAPI contract", backup_install)
    continuation = workflow.index("Verify current continuation boundary", openapi)
    checkpoint_control = workflow.index(
        "Run current checkpoint control tests", continuation
    )
    catalogs = workflow.index("Verify repository catalogs", checkpoint_control)

    assert setup_python < regression_install < general_install < backup_setup < backup_install < openapi
    assert openapi < continuation < checkpoint_control < catalogs
    assert (
        "scripts/run_walksafe_test_layers_current.sh active-session-control"
        in workflow[checkpoint_control:catalogs]
    )
    active_control = current_runner[
        current_runner.index("run_active_session_control()") : current_runner.index(
            "run_model_audit()"
        )
    ]
    assert active_control.index(
        "check_walksafe_project_continuation_v2_4.py"
    ) < active_control.index("-m pytest")
    assert "Build source-bound Web release bundle" not in workflow
    assert "Verify Web release artifact binding" not in workflow
    assert "build_walksafe_web_release_20260711.sh" not in workflow
    assert "--profile web-release" not in workflow
    assert "web-standalone-" not in workflow
    assert "WEB_QUALITY_PYTHON" not in workflow
    assert "WALKSAFE_RUN_BROWSER_E2E" not in workflow
    assert "artifacts/ci/" not in workflow
    regression_steps = workflow[regression_install:general_install]
    node_lock = json.loads(
        Path("configs/walksafe_node_toolchain_lock_20260715.json").read_text(
            encoding="utf-8"
        )
    )
    archive = node_lock["official_archive"]
    assert f'WALKSAFE_NODE_ARCHIVE_URL: {archive["url"]}' in regression_steps
    assert f'WALKSAFE_NODE_ARCHIVE_SHA256: {archive["sha256"]}' in regression_steps
    assert "${RUNNER_TEMP:?}" in regression_steps
    assert "--proto '=https' --tlsv1.2" in regression_steps
    assert "sha256sum --check --strict" in regression_steps
    assert "tar --extract --xz --same-permissions" in regression_steps
    assert "--strip-components=1" in regression_steps
    assert "scripts/check_walksafe_node_toolchain_20260715.py" in regression_steps
    assert '--node-root "${node_root}"' in regression_steps
    assert '"${node_bin_dir}/npm" --prefix apps/web ci' in regression_steps
    assert '"${node_bin_dir}/npm" --prefix apps/android-gateway ci' in regression_steps
    assert "npm --prefix apps/android-gateway test" in regression_steps
    assert 'echo "${node_bin_dir}" >> "${GITHUB_PATH}"' in regression_steps
    assert 'export PATH="${node_bin_dir}:/usr/bin:/bin"' in regression_steps
    assert regression_steps.index('export PATH="${node_bin_dir}:/usr/bin:/bin"') < (
        regression_steps.index('"${node_bin_dir}/npm" --prefix apps/web ci')
    )
    download = regression_steps.index("curl --fail --location")
    checksum = regression_steps.index("sha256sum --check --strict")
    create_root = regression_steps.index('mkdir --mode=0755 "${node_root}"')
    extract = regression_steps.index("tar --extract --xz --same-permissions")
    checker = regression_steps.index("scripts/check_walksafe_node_toolchain_20260715.py")
    export_environment = regression_steps.index(
        'echo "WALKSAFE_NODE_BIN_DIR=${node_bin_dir}" >> "${GITHUB_ENV}"'
    )
    export_path = regression_steps.index('echo "${node_bin_dir}" >> "${GITHUB_PATH}"')
    current_path = regression_steps.index('export PATH="${node_bin_dir}:/usr/bin:/bin"')
    web_install = regression_steps.index('"${node_bin_dir}/npm" --prefix apps/web ci')
    gateway_install = regression_steps.index(
        '"${node_bin_dir}/npm" --prefix apps/android-gateway ci'
    )
    assert (
        download
        < checksum
        < create_root
        < extract
        < checker
        < export_environment
        < export_path
        < current_path
        < web_install
        < gateway_install
    )
    assert 'node_bin_dir="$(dirname "$(command -v node)")"' not in regression_steps
    assert 'echo "WALKSAFE_NODE_BIN_DIR=${node_bin_dir}" >> "${GITHUB_ENV}"' in workflow[
        regression_install:general_install
    ]
    assert 'python-version: "3.14.4"' not in workflow
    assert 'python-version: "3.14.6"' in workflow[backup_setup:backup_install]
    assert "update-environment: false" in workflow[backup_setup:backup_install]
    assert 'node-version: "22.23.1"' in workflow[:regression_install]
    assert "tests/general-quality-cp312-linux-x86_64-cpu.lock" in workflow[
        setup_python:regression_install
    ]
    assert 'python-version: "3.12.13"' in workflow[setup_python:regression_install]
    general_step = workflow[general_install:backup_setup]
    assert '"pip==26.1.1"' not in general_step
    assert "--require-hashes --only-binary=:all: --no-compile" in general_step
    assert "-r tests/general-quality-cp312-linux-x86_64-cpu.lock" in general_step
    assert "-r backend/requirements.lock" not in general_step
    assert "-r tests/requirements.lock" not in general_step
    assert "-r backend/requirements.txt" not in general_step
    assert "-r tests/requirements.txt" not in general_step
    assert "python -m pip check" in general_step
    assert 'torch.__version__ == "2.11.0+cpu"' in general_step
    assert "torch.version.cuda is None" in general_step
    assert 'torchvision.__version__ == "0.26.0+cpu"' in general_step
    backup_step = workflow[backup_install:openapi]
    assert "steps.backup_python.outputs.python-path" in backup_step
    assert "--require-hashes --only-binary=:all: --no-compile" in backup_step
    assert "-r tests/backup-integrity-cp314.lock" in backup_step
    assert "WALKSAFE_BACKUP_PYTHON_BIN" in backup_step
    assert backup_step.count('= "3.12.13"') == 2
    all_step_start = workflow.index("Run commit-stable test layers")
    all_step_end = workflow.index("actions/upload-artifact@", all_step_start)
    all_step = workflow[all_step_start:all_step_end]
    assert all_step.count('= "3.12.13"') == 1
    assert 'WALKSAFE_RUN_ANDROID_DEVICE_TESTS: "false"' in all_step
    assert 'WALKSAFE_TFLITE_SMOKE_IMAGE: ""' in all_step
    assert workflow.count("WALKSAFE_RUN_ANDROID_DEVICE_TESTS:") == 1
    assert workflow.count("WALKSAFE_TFLITE_SMOKE_IMAGE:") == 1
    assert "PYTHON_BIN=python" not in workflow
    assert "working-directory: apps/web" not in workflow[:regression_install]


def write_archive(path: Path, build_root: Path) -> Path:
    with tarfile.open(path, "w:gz") as archive:
        archive.add(build_root, arcname=".")
    return path


def provenance(tmp_path: Path, build_root: Path) -> dict[str, object]:
    package_json = tmp_path / "package.json"
    package_lock = tmp_path / "package-lock.json"
    node_toolchain_lock = tmp_path / "walksafe_node_toolchain_lock_20260715.json"
    package_json.write_text("{}\n", encoding="utf-8")
    package_lock.write_text('{"lockfileVersion": 3}\n', encoding="utf-8")
    shutil.copyfile(
        Path("configs/walksafe_node_toolchain_lock_20260715.json"),
        node_toolchain_lock,
    )
    node_lock = json.loads(node_toolchain_lock.read_text(encoding="utf-8"))
    receipt_names = (
        "node-toolchain",
        "node-toolchain-post",
        "npm-ci",
        "npm-audit",
        "npm-lint",
        "npm-typecheck",
        "npm-test",
        "npm-build",
        "runtime-trace",
        "browser-lifecycle",
    )
    receipts = {}
    for name in receipt_names:
        receipt = tmp_path / f"{name}.log"
        if name in {"node-toolchain", "node-toolchain-post"}:
            attestation = {
                "schema_version": "walksafe.node-toolchain-attestation.v2",
                "lock_sha256": hashlib.sha256(node_toolchain_lock.read_bytes()).hexdigest(),
                "official_archive": node_lock["official_archive"],
                "platform": node_lock["platform"],
                "root": node_lock["root"],
                "node": node_lock["node"],
                "npm": node_lock["npm"],
            }
            receipt.write_text(
                json.dumps(attestation, sort_keys=True, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
        else:
            receipt.write_text(f"{name} PASS\n", encoding="utf-8")
        receipts[name] = receipt
    deployment_archive = tmp_path / "walksafe-web.tar.gz"
    write_archive(deployment_archive, build_root)
    return {
        "package_json": package_json,
        "package_lock": package_lock,
        "node_toolchain_lock": node_toolchain_lock,
        "node_version": "v22.23.1",
        "npm_version": "10.9.8",
        "build_environment": {
            "NEXT_PUBLIC_DETECTOR_MODE": "server-v2",
            "NEXT_PUBLIC_WALKSAFE_PWA_ENABLED": "true",
            "NEXT_PUBLIC_WALKSAFE_SUPERVISED_TACTILE_LOCAL_STEERING": "false",
            "NEXT_PUBLIC_WALKSAFE_TEST_CAPTURE_PANEL": "false",
        },
        "quality_receipts": receipts,
        "deployment_archive": deployment_archive,
        "artifact_root": tmp_path,
    }


def test_web_build_manifest_rejects_stale_development_output(tmp_path: Path) -> None:
    build_root = tmp_path / "build"
    build_root.mkdir()
    (build_root / "BUILD_ID").write_text(f"{SOURCE_COMMIT}\n", encoding="utf-8")
    (build_root / "server").mkdir()
    (build_root / "server" / "app.js").write_text("production", encoding="utf-8")
    (build_root / "dev").mkdir()
    (build_root / "dev" / "stale-secret.txt").write_text("stale", encoding="utf-8")

    with pytest.raises(ValueError, match="development-only"):
        create_manifest(build_root, SOURCE_COMMIT, **provenance(tmp_path, build_root))


def test_web_build_manifest_accepts_fresh_source_bound_output(tmp_path: Path) -> None:
    build_root = tmp_path / "build"
    build_root.mkdir()
    (build_root / "BUILD_ID").write_text(f"{SOURCE_COMMIT}\n", encoding="utf-8")
    (build_root / "server").mkdir()
    (build_root / "server" / "app.js").write_text("production", encoding="utf-8")

    manifest = create_manifest(build_root, SOURCE_COMMIT, **provenance(tmp_path, build_root))

    assert manifest["source_commit"] == SOURCE_COMMIT
    assert {entry["path"] for entry in manifest["files"]} == {"BUILD_ID", "server/app.js"}


def test_web_build_manifest_allows_runtime_dependency_named_dev(tmp_path: Path) -> None:
    build_root = tmp_path / "build"
    runtime_file = build_root / "node_modules/next/dist/client/dev/index.js"
    runtime_file.parent.mkdir(parents=True)
    (build_root / "BUILD_ID").write_text(f"{SOURCE_COMMIT}\n", encoding="utf-8")
    runtime_file.write_text("production runtime dependency", encoding="utf-8")

    manifest = create_manifest(build_root, SOURCE_COMMIT, **provenance(tmp_path, build_root))

    assert "node_modules/next/dist/client/dev/index.js" in {
        entry["path"] for entry in manifest["files"]
    }


def test_web_build_manifest_records_complete_release_provenance(tmp_path: Path) -> None:
    build_root = tmp_path / "build"
    build_root.mkdir()
    (build_root / "BUILD_ID").write_text(f"{SOURCE_COMMIT}\n", encoding="utf-8")
    manifest = create_manifest(
        build_root,
        SOURCE_COMMIT,
        **provenance(tmp_path, build_root),
    )

    assert manifest["schema_version"] == "walksafe.web-build-manifest.v4"
    assert manifest["toolchain"] == {"node": "v22.23.1", "npm": "10.9.8"}
    assert {receipt["name"] for receipt in manifest["quality_receipts"]} == {
        "npm-ci",
        "npm-audit",
        "npm-lint",
        "npm-typecheck",
        "npm-test",
        "npm-build",
        "runtime-trace",
        "browser-lifecycle",
        "node-toolchain",
        "node-toolchain-post",
    }
    assert set(manifest["inputs"]) == {
        "package_json",
        "package_lock",
        "node_toolchain_lock",
    }
    assert manifest["inputs"]["package_json"]["path"] == "package.json"
    assert all("path" in receipt for receipt in manifest["quality_receipts"])
    assert manifest["deployment_archive"]["name"] == "walksafe-web.tar.gz"


def test_web_build_manifest_rejects_empty_toolchain_version(tmp_path: Path) -> None:
    build_root = tmp_path / "build"
    build_root.mkdir()
    (build_root / "BUILD_ID").write_text(f"{SOURCE_COMMIT}\n", encoding="utf-8")
    inputs = provenance(tmp_path, build_root)
    inputs["node_version"] = ""

    with pytest.raises(ValueError, match="versions"):
        create_manifest(build_root, SOURCE_COMMIT, **inputs)


@pytest.mark.parametrize("receipt_name", ["node-toolchain", "node-toolchain-post"])
def test_web_build_manifest_rejects_toolchain_attestation_drift(
    tmp_path: Path,
    receipt_name: str,
) -> None:
    build_root = tmp_path / "build"
    build_root.mkdir()
    (build_root / "BUILD_ID").write_text(f"{SOURCE_COMMIT}\n", encoding="utf-8")
    inputs = provenance(tmp_path, build_root)
    receipt = inputs["quality_receipts"][receipt_name]
    receipt.write_text('{"schema_version":"walksafe.node-toolchain-attestation.v2"}\n')

    with pytest.raises(ValueError, match="differs from the locked toolchain"):
        create_manifest(build_root, SOURCE_COMMIT, **inputs)


@pytest.mark.parametrize("encoding", ["utf-16", "utf-32", "utf-8-sig"])
def test_web_build_manifest_rejects_noncanonical_json_encoding(
    tmp_path: Path,
    encoding: str,
) -> None:
    build_root = tmp_path / "build"
    build_root.mkdir()
    (build_root / "BUILD_ID").write_text(f"{SOURCE_COMMIT}\n", encoding="utf-8")
    inputs = provenance(tmp_path, build_root)
    lock = inputs["node_toolchain_lock"]
    payload = json.loads(lock.read_text(encoding="utf-8"))
    lock.write_bytes(json.dumps(payload).encode(encoding))

    with pytest.raises(ValueError, match="not strict JSON"):
        create_manifest(build_root, SOURCE_COMMIT, **inputs)


@pytest.mark.parametrize(
    ("payload", "message"),
    [
        (b'{"schema_version":"x","schema_version":"y"}\n', "duplicate JSON key"),
        (b'{"schema_version":NaN}\n', "non-finite JSON value"),
    ],
)
def test_web_build_manifest_rejects_non_strict_toolchain_json(
    tmp_path: Path,
    payload: bytes,
    message: str,
) -> None:
    build_root = tmp_path / "build"
    build_root.mkdir()
    (build_root / "BUILD_ID").write_text(f"{SOURCE_COMMIT}\n", encoding="utf-8")
    inputs = provenance(tmp_path, build_root)
    inputs["node_toolchain_lock"].write_bytes(payload)

    with pytest.raises(ValueError, match=message):
        create_manifest(build_root, SOURCE_COMMIT, **inputs)


def test_web_build_manifest_rejects_oversized_toolchain_json(tmp_path: Path) -> None:
    build_root = tmp_path / "build"
    build_root.mkdir()
    (build_root / "BUILD_ID").write_text(f"{SOURCE_COMMIT}\n", encoding="utf-8")
    inputs = provenance(tmp_path, build_root)
    inputs["node_toolchain_lock"].write_bytes(b" " * (64 * 1024 + 1))

    with pytest.raises(ValueError, match="bounded regular"):
        create_manifest(build_root, SOURCE_COMMIT, **inputs)


def test_web_build_manifest_rejects_toolchain_json_symlink(tmp_path: Path) -> None:
    build_root = tmp_path / "build"
    build_root.mkdir()
    (build_root / "BUILD_ID").write_text(f"{SOURCE_COMMIT}\n", encoding="utf-8")
    inputs = provenance(tmp_path, build_root)
    lock = inputs["node_toolchain_lock"]
    replacement = lock.with_name("replacement-lock.json")
    replacement.write_bytes(lock.read_bytes())
    lock.unlink()
    lock.symlink_to(replacement)

    with pytest.raises(ValueError, match="dependency input is missing"):
        create_manifest(build_root, SOURCE_COMMIT, **inputs)


def test_web_build_manifest_rejects_toolchain_json_replaced_during_read(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    build_root = tmp_path / "build"
    build_root.mkdir()
    (build_root / "BUILD_ID").write_text(f"{SOURCE_COMMIT}\n", encoding="utf-8")
    inputs = provenance(tmp_path, build_root)
    lock = inputs["node_toolchain_lock"]
    replacement = lock.with_name("replacement-lock.json")
    replacement.write_bytes(lock.read_bytes())
    original_read = manifest_creator.os.read
    replaced = False

    def replace_after_read(descriptor: int, size: int) -> bytes:
        nonlocal replaced
        chunk = original_read(descriptor, size)
        if chunk and not replaced:
            os.replace(replacement, lock)
            replaced = True
        return chunk

    monkeypatch.setattr(manifest_creator.os, "read", replace_after_read)
    with pytest.raises(ValueError, match="changed while being read"):
        create_manifest(build_root, SOURCE_COMMIT, **inputs)


def test_web_build_manifest_rejects_archive_with_different_bytes(tmp_path: Path) -> None:
    build_root = tmp_path / "build"
    build_root.mkdir()
    (build_root / "BUILD_ID").write_text(f"{SOURCE_COMMIT}\n", encoding="utf-8")
    (build_root / "server.js").write_text("reviewed", encoding="utf-8")
    inputs = provenance(tmp_path, build_root)
    other_root = tmp_path / "other"
    other_root.mkdir()
    (other_root / "BUILD_ID").write_text(f"{SOURCE_COMMIT}\n", encoding="utf-8")
    (other_root / "server.js").write_text("changed!", encoding="utf-8")
    write_archive(inputs["deployment_archive"], other_root)

    with pytest.raises(ValueError, match="bytes differ"):
        create_manifest(build_root, SOURCE_COMMIT, **inputs)


def test_web_build_manifest_rejects_archive_link_entry(tmp_path: Path) -> None:
    build_root = tmp_path / "build"
    build_root.mkdir()
    (build_root / "BUILD_ID").write_text(f"{SOURCE_COMMIT}\n", encoding="utf-8")
    inputs = provenance(tmp_path, build_root)
    with tarfile.open(inputs["deployment_archive"], "w:gz") as archive:
        archive.add(build_root / "BUILD_ID", arcname="BUILD_ID")
        link = tarfile.TarInfo("server.js")
        link.type = tarfile.SYMTYPE
        link.linkname = "/etc/passwd"
        archive.addfile(link)

    with pytest.raises(ValueError, match="non-regular"):
        create_manifest(build_root, SOURCE_COMMIT, **inputs)
