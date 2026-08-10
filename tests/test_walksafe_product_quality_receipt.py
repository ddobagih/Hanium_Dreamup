from __future__ import annotations

import base64
import hashlib
import gzip
import io
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile

import pytest


REPO_ROOT = Path(__file__).resolve().parents[1]
RUNNER_PATH = REPO_ROOT / "scripts" / "run_walksafe_product_quality_20260713.py"
POLICY_PATH = REPO_ROOT / "configs" / "walksafe_product_quality_policy_20260713.json"


def load_runner():
    module_name = "walksafe_product_quality_runner_under_test"
    spec = importlib.util.spec_from_file_location(module_name, RUNNER_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


runner = load_runner()


def git(repo: Path, *arguments: str) -> None:
    subprocess.run(
        ["git", "-C", str(repo), *arguments],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def create_repository(tmp_path: Path) -> Path:
    repo = tmp_path / "source"
    for directory in (
        repo / "apps" / "web",
        repo / "apps" / "android",
        repo / "backend",
        repo / "configs",
        repo / "contracts" / "fixtures",
        repo / "scripts",
        repo / "voice",
    ):
        directory.mkdir(parents=True, exist_ok=True)
    (repo / "README.md").write_text("clean source\n", encoding="utf-8")
    (repo / ".gitignore").write_text(
        ".env.*\napps/web/.next/\napps/android/app/build/\n",
        encoding="utf-8",
    )
    (repo / "apps" / "web" / ".keep").write_text("web\n", encoding="utf-8")
    (repo / "apps/web/quality-requirements.txt").write_text(
        "websockets==16.0\n", encoding="utf-8"
    )
    (repo / "apps/web/quality-requirements.lock").write_text(
        f"websockets==16.0 --hash=sha256:{'c' * 64}\n", encoding="utf-8"
    )
    (repo / "apps" / "android" / "gradlew").write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    (repo / "apps" / "android" / "gradlew").chmod(0o755)
    (repo / "scripts" / ".keep").write_text("scripts\n", encoding="utf-8")
    (repo / "scripts/run_walksafe_isolated_python_20260713.py").write_bytes(
        (REPO_ROOT / "scripts/run_walksafe_isolated_python_20260713.py").read_bytes()
    )
    (repo / "scripts/check_walksafe_node_toolchain_20260715.py").write_bytes(
        (REPO_ROOT / "scripts/check_walksafe_node_toolchain_20260715.py").read_bytes()
    )
    (repo / "configs/walksafe_node_toolchain_lock_20260715.json").write_bytes(
        (REPO_ROOT / "configs/walksafe_node_toolchain_lock_20260715.json").read_bytes()
    )
    for component in ("backend", "voice"):
        (repo / component / "requirements.txt").write_text("fixture==1\n", encoding="utf-8")
        (repo / component / "requirements.lock").write_text(
            f"fixture==1 --hash=sha256:{'a' * 64}\n", encoding="utf-8"
        )
    (repo / "voice/quality-requirements.txt").write_text("testtool==2\n", encoding="utf-8")
    (repo / "voice/quality-requirements.lock").write_text(
        f"testtool==2 --hash=sha256:{'b' * 64}\n", encoding="utf-8"
    )
    (repo / "contracts" / "walksafe.openapi.json").write_text("{}\n", encoding="utf-8")
    (repo / "contracts" / "fixtures" / "walking-route-v1.json").write_text("{}\n", encoding="utf-8")
    git(repo, "init", "-q")
    git(repo, "config", "user.name", "Quality Test")
    git(repo, "config", "user.email", "quality@example.invalid")
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "fixture")
    return repo


def ambient(*, database_url: str | None = None) -> dict[str, str]:
    values = {
        "PATH": os.environ["PATH"],
        "HOME": os.environ.get("HOME", "/tmp"),
        "LANG": "C.UTF-8",
    }
    if database_url is not None:
        values["WALKSAFE_TEST_DATABASE_URL"] = database_url
    return values


def passing_executor(calls: list[tuple[tuple[str, ...], Path, dict[str, str]]]):
    def execute(argv, cwd, environment):
        calls.append((tuple(argv), cwd, dict(environment)))
        return runner.ExecutionResult(returncode=0, stdout=b"ignored command output")

    return execute


def locked_environment(product: str = "voice") -> dict[str, str]:
    if product == "web":
        return {"pip": "26.1.1", "websockets": "16.0"}
    installed = {"fixture": "1", "pip": "26.1.1"}
    if product == "voice":
        installed["testtool"] = "2"
    return installed


def expected_node_attestation(repo: Path) -> dict[str, object]:
    lock_path = repo / "configs/walksafe_node_toolchain_lock_20260715.json"
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    return {
        "schema_version": "walksafe.node-toolchain-attestation.v2",
        "lock_sha256": hashlib.sha256(lock_path.read_bytes()).hexdigest(),
        "official_archive": lock["official_archive"],
        "platform": lock["platform"],
        "root": lock["root"],
        "node": lock["node"],
        "npm": lock["npm"],
    }


def web_node_options(repo: Path, *, attestor=None) -> dict[str, object]:
    node_root = repo.parent / "locked-node"
    node_bin = node_root / "bin"
    node_bin.mkdir(parents=True, exist_ok=True)
    for name in ("node", "npm"):
        executable = node_bin / name
        executable.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        executable.chmod(0o755)
    if attestor is None:
        expected = expected_node_attestation(repo)

        def attestor(node_root_argument, lock_path_argument):
            assert node_root_argument == node_root
            assert lock_path_argument == (
                repo / "configs/walksafe_node_toolchain_lock_20260715.json"
            )
            return expected

    return {
        "node_bin_dir": node_bin,
        "node_toolchain_attestor": attestor,
    }


def web_variant_files(
    web_root: str,
    *,
    preview_values: tuple[str, str, str],
    rsc_key_bytes: bytes,
) -> dict[str, bytes]:
    preview = {
        "version": 4,
        "routes": {},
        "dynamicRoutes": {},
        "notFoundRoutes": [],
        "preview": {
            "previewModeId": preview_values[0],
            "previewModeSigningKey": preview_values[1],
            "previewModeEncryptionKey": preview_values[2],
        },
    }
    required = {
        "version": 1,
        "appDir": web_root,
        "config": {
            "outputFileTracingRoot": web_root,
            "turbopack": {"root": web_root},
        },
        "files": [],
    }
    rsc = {
        "node": {},
        "edge": {},
        "encryptionKey": base64.b64encode(rsc_key_bytes).decode("ascii"),
    }
    rsc_inner = json.dumps(rsc, ensure_ascii=False, indent=2)
    server_config = {
        "outputFileTracingRoot": web_root,
        "turbopack": {"root": web_root},
    }
    compact = {"ensure_ascii": False, "separators": (",", ":")}
    return {
        ".next/prerender-manifest.json": json.dumps(preview, **compact).encode("utf-8"),
        ".next/required-server-files.json": json.dumps(required, **compact).encode("utf-8"),
        ".next/server/server-reference-manifest.json": json.dumps(
            rsc, **compact
        ).encode("utf-8"),
        ".next/server/server-reference-manifest.js": (
            "self.__RSC_SERVER_MANIFEST=" + json.dumps(rsc_inner, **compact)
        ).encode("utf-8"),
        "server.js": (
            "const nextConfig = " + json.dumps(server_config, **compact) + "\n"
        ).encode("utf-8"),
    }


def write_web_fixture(
    archive: Path,
    repo: Path,
    *,
    server: bytes | None = None,
    overrides: dict[str, bytes] | None = None,
) -> None:
    source_commit = subprocess.run(
        ["git", "-C", str(repo), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    files = web_variant_files(
        "/secure/build/web-artifact/apps/web",
        preview_values=("1" * 32, "2" * 64, "3" * 64),
        rsc_key_bytes=b"A" * 32,
    )
    if server is not None:
        files["server.js"] = server
    files.update(overrides or {})
    files["BUILD_ID"] = source_commit.encode()
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w") as output:
        for name, content in sorted(files.items()):
            info = tarfile.TarInfo(name)
            info.size = len(content)
            info.mode = 0o644
            output.addfile(info, io.BytesIO(content))
    with archive.open("wb") as destination:
        with gzip.GzipFile(filename="", mode="wb", fileobj=destination, mtime=0) as compressed:
            compressed.write(raw.getvalue())


def materialize_fresh_web_build(repo: Path) -> None:
    source_commit = subprocess.run(
        ["git", "-C", str(repo), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    build = repo / "apps/web/.next"
    (build / "standalone").mkdir(parents=True)
    (build / "static").mkdir()
    (build / "BUILD_ID").write_text(source_commit, encoding="utf-8")
    files = web_variant_files(
        str((repo / "apps" / "web").resolve()),
        preview_values=("4" * 32, "5" * 64, "6" * 64),
        rsc_key_bytes=b"B" * 32,
    )
    for relative, payload in files.items():
        destination = build / "standalone" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(payload)


def test_policy_has_the_exact_product_step_sets() -> None:
    policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))

    assert set(policy["products"]) == {"web", "android", "backend", "voice"}
    assert [step["id"] for step in policy["products"]["web"]["steps"]] == [
        "npm-ci",
        "npm-audit",
        "npm-lint",
        "npm-typecheck",
        "npm-test",
        "npm-build",
        "runtime-trace",
        "browser-lifecycle",
    ]
    assert policy["products"]["web"]["steps"][7]["fixed_environment"][
        "WALKSAFE_SOURCE_COMMIT"
    ] == "{source_commit}"
    assert [step["id"] for step in policy["products"]["android"]["steps"]] == [
        "unit-test",
        "lint-release",
        "assemble-release",
        "apk-model-asset",
    ]
    assert [step["id"] for step in policy["products"]["backend"]["steps"]] == [
        "database-preflight",
        "migrate-test-database",
        "backend-pytest",
        "openapi-check",
        "pip-check",
    ]
    backend_pytest = policy["products"]["backend"]["steps"][2]
    assert backend_pytest["fixed_environment"]["PYTEST_DISABLE_PLUGIN_AUTOLOAD"] == "1"
    assert backend_pytest["argv"][1:10] == [
        "-I",
        "-S",
        "-B",
        "scripts/run_walksafe_isolated_python_20260713.py",
        "--repo-root",
        ".",
        "--product",
        "backend",
        "--",
    ]
    assert backend_pytest["argv"][12:14] == ["-p", "no:cacheprovider"]
    voice_steps = policy["products"]["voice"]["steps"]
    assert [step["id"] for step in voice_steps] == ["voice-pytest", "pip-check"]
    assert voice_steps[0]["fixed_environment"]["PYTEST_DISABLE_PLUGIN_AUTOLOAD"] == "1"
    assert voice_steps[0]["argv"][1:10] == [
        "-I",
        "-S",
        "-B",
        "scripts/run_walksafe_isolated_python_20260713.py",
        "--repo-root",
        ".",
        "--product",
        "voice",
        "--",
    ]
    assert voice_steps[0]["argv"][12:14] == ["-p", "no:cacheprovider"]
    assert voice_steps[0]["argv"][15:] == [
        path.relative_to(REPO_ROOT).as_posix()
        for path in sorted((REPO_ROOT / "tests").glob("test_voice*.py"))
    ]
    assert policy["products"]["web"]["tested_tool_lock"] == (
        "apps/web/quality-requirements.lock"
    )
    assert policy["products"]["web"]["trusted_tool_distributions"] == {
        "pip": "26.1.1"
    }
    assert policy["products"]["android"]["trusted_tool_distributions"] == {}
    assert policy["products"]["backend"]["trusted_tool_distributions"] == {
        "pip": "26.1.1"
    }
    assert policy["products"]["voice"]["trusted_tool_distributions"] == {
        "pip": "26.1.1"
    }


def test_web_receipt_binds_fixed_steps_logs_inputs_and_release_archive(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    archive = tmp_path / "walksafe-web-release.tar.gz"
    write_web_fixture(archive, repo)
    receipt_path = tmp_path / "web-quality.json"
    calls: list[tuple[tuple[str, ...], Path, dict[str, str]]] = []

    def execute(argv, cwd, environment):
        calls.append((tuple(argv), cwd, dict(environment)))
        if len(calls) == 6:
            materialize_fresh_web_build(repo)
        return runner.ExecutionResult(returncode=0)

    receipt = runner.run_product_quality(
        repo_root=repo,
        product="web",
        receipt_path=receipt_path,
        arguments={"web_release_archive": str(archive)},
        executor=execute,
        ambient_environment=ambient(),
        installed_distributions=locked_environment("web"),
        **web_node_options(repo),
    )

    assert receipt["schema_version"] == "walksafe.product-quality-receipt.v3"
    assert receipt["exit_code"] == 0
    assert receipt["result"] == "passed"
    assert receipt["source"]["tree_clean_before"] is True
    assert receipt["source"]["tree_clean_after"] is True
    assert receipt["inputs"]["tracked_source_before"] == receipt["inputs"]["tracked_source_after"]
    assert [step["id"] for step in receipt["steps"]] == [
        "npm-ci",
        "npm-audit",
        "npm-lint",
        "npm-typecheck",
        "npm-test",
        "npm-build",
        "runtime-trace",
        "browser-lifecycle",
    ]
    assert len(calls) == 8
    build_environment = calls[5][2]
    assert build_environment["NEXT_PUBLIC_DETECTOR_MODE"] == "server-v2"
    assert build_environment["NEXT_PUBLIC_WALKSAFE_PWA_ENABLED"] == "true"
    assert build_environment["NEXT_PUBLIC_WALKSAFE_SUPERVISED_TACTILE_LOCAL_STEERING"] == "false"
    assert build_environment["NEXT_PUBLIC_WALKSAFE_TEST_CAPTURE_PANEL"] == "false"
    assert build_environment["WALKSAFE_SOURCE_COMMIT"] == receipt["source"]["commit"]
    assert calls[7][2]["WALKSAFE_SOURCE_COMMIT"] == receipt["source"]["commit"]
    expected_path = f"{repo.parent / 'locked-node/bin'}:/usr/bin:/bin"
    assert all(call_environment["PATH"] == expected_path for _, _, call_environment in calls)
    assert all(
        step["environment"]["fixed_public"]["PATH"]
        == "{locked_node_bin}:/usr/bin:/bin"
        for step in receipt["steps"]
    )
    expected_attestation = expected_node_attestation(repo)
    assert receipt["node_toolchain"]["before"] == expected_attestation
    assert receipt["node_toolchain"]["after"] == expected_attestation
    assert receipt["node_toolchain"]["lock"]["sha256"] == expected_attestation["lock_sha256"]
    assert receipt["outputs"] == [
        {
            "name": "web_release_archive",
            "verification_step": "npm-build",
            "verification": "fresh-next-standalone-content-with-validated-build-variance-v2",
            "path": "operator-artifact:walksafe-web-release.tar.gz",
            "bytes": archive.stat().st_size,
            "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
        }
    ]
    assert receipt_path.is_file()
    for step in receipt["steps"]:
        log_path = receipt_path.parent / step["log"]["path"]
        assert log_path.is_file()
        assert hashlib.sha256(log_path.read_bytes()).hexdigest() == step["log"]["sha256"]


@pytest.mark.parametrize(
    "mutation",
    [
        "preview-extra-field",
        "preview-duplicate-field",
        "preview-nonfinite",
        "preview-length",
        "preview-uppercase",
        "preview-zero",
        "preview-not-distinct",
        "preview-reused-across-builds",
        "rsc-extra-field",
        "rsc-invalid-base64",
        "rsc-decoded-length",
        "rsc-json-js-mismatch",
        "rsc-reused-across-builds",
        "required-root-disagreement",
        "required-relative-root",
        "cross-file-root-mismatch",
        "server-root-occurrence-count",
        "unapproved-byte-difference",
    ],
)
def test_web_archive_build_variance_rejects_unapproved_mutations(
    tmp_path: Path, mutation: str
) -> None:
    repo = create_repository(tmp_path)
    artifact_root = "/secure/build/web-artifact/apps/web"
    files = web_variant_files(
        artifact_root,
        preview_values=("1" * 32, "2" * 64, "3" * 64),
        rsc_key_bytes=b"A" * 32,
    )
    preview_path = ".next/prerender-manifest.json"
    rsc_json_path = ".next/server/server-reference-manifest.json"
    rsc_js_path = ".next/server/server-reference-manifest.js"
    required_path = ".next/required-server-files.json"

    if mutation.startswith("preview-"):
        if mutation == "preview-duplicate-field":
            files[preview_path] = files[preview_path].replace(
                b'"previewModeId":',
                b'"previewModeId":"' + b"7" * 32 + b'","previewModeId":',
                1,
            )
        elif mutation == "preview-nonfinite":
            files[preview_path] = files[preview_path][:-1] + b',"unexpected":NaN}'
        else:
            preview = json.loads(files[preview_path])
            values = preview["preview"]
            if mutation == "preview-extra-field":
                values["unexpected"] = "7" * 32
            elif mutation == "preview-length":
                values["previewModeId"] = "7" * 31
            elif mutation == "preview-uppercase":
                values["previewModeId"] = "A" * 32
            elif mutation == "preview-zero":
                values["previewModeId"] = "0" * 32
            elif mutation == "preview-not-distinct":
                values["previewModeSigningKey"] = values["previewModeEncryptionKey"]
            elif mutation == "preview-reused-across-builds":
                values.update(
                    previewModeId="4" * 32,
                    previewModeSigningKey="5" * 64,
                    previewModeEncryptionKey="6" * 64,
                )
            files[preview_path] = json.dumps(
                preview, ensure_ascii=False, separators=(",", ":")
            ).encode("utf-8")
    elif mutation.startswith("rsc-"):
        rsc = json.loads(files[rsc_json_path])
        previous_key = rsc["encryptionKey"]
        if mutation == "rsc-extra-field":
            rsc["unexpected"] = True
        elif mutation == "rsc-invalid-base64":
            rsc["encryptionKey"] = "!" * 44
        elif mutation == "rsc-decoded-length":
            rsc["encryptionKey"] = base64.b64encode(b"C" * 31).decode("ascii")
        elif mutation == "rsc-json-js-mismatch":
            rsc["encryptionKey"] = base64.b64encode(b"C" * 32).decode("ascii")
        elif mutation == "rsc-reused-across-builds":
            rsc["encryptionKey"] = base64.b64encode(b"B" * 32).decode("ascii")
            files[rsc_js_path] = files[rsc_js_path].replace(
                previous_key.encode("ascii"), rsc["encryptionKey"].encode("ascii")
            )
        files[rsc_json_path] = json.dumps(
            rsc, ensure_ascii=False, separators=(",", ":")
        ).encode("utf-8")
    elif mutation in {
        "required-root-disagreement",
        "required-relative-root",
        "cross-file-root-mismatch",
    }:
        required = json.loads(files[required_path])
        if mutation == "required-root-disagreement":
            required["config"]["outputFileTracingRoot"] = "/secure/other/apps/web"
        else:
            replacement = (
                "relative/apps/web"
                if mutation == "required-relative-root"
                else "/secure/build/other/apps/web"
            )
            required["appDir"] = replacement
            required["config"]["outputFileTracingRoot"] = replacement
            required["config"]["turbopack"]["root"] = replacement
        files[required_path] = json.dumps(
            required, ensure_ascii=False, separators=(",", ":")
        ).encode("utf-8")
    elif mutation == "server-root-occurrence-count":
        files["server.js"] += b"// " + artifact_root.encode("utf-8") + b"\n"
    elif mutation == "unapproved-byte-difference":
        preview = json.loads(files[preview_path])
        preview["version"] = 5
        files[preview_path] = json.dumps(
            preview, ensure_ascii=False, separators=(",", ":")
        ).encode("utf-8")

    archive = tmp_path / "walksafe-web-release.tar.gz"
    write_web_fixture(archive, repo, overrides=files)
    materialize_fresh_web_build(repo)
    source_commit = subprocess.run(
        ["git", "-C", str(repo), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    with pytest.raises(runner.QualityRunnerError):
        runner._verify_web_archive_from_fresh_build(
            repo_root=repo,
            archive_path=archive,
            source_commit=source_commit,
        )


def test_web_quality_requires_locked_node_before_executor(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    archive = tmp_path / "walksafe-web-release.tar.gz"
    write_web_fixture(archive, repo)
    calls = 0

    def execute(*_):
        nonlocal calls
        calls += 1
        return runner.ExecutionResult(0)

    with pytest.raises(runner.QualityRunnerError, match="node_bin_dir is required"):
        runner.run_product_quality(
            repo_root=repo,
            product="web",
            receipt_path=tmp_path / "quality.json",
            arguments={"web_release_archive": str(archive)},
            executor=execute,
            ambient_environment=ambient(),
            installed_distributions=locked_environment("web"),
        )

    assert calls == 0


def test_web_quality_rejects_node_attestation_before_executor(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    archive = tmp_path / "walksafe-web-release.tar.gz"
    write_web_fixture(archive, repo)
    calls = 0

    def execute(*_):
        nonlocal calls
        calls += 1
        return runner.ExecutionResult(0)

    def reject_node(*_):
        raise ValueError("fixture Node differs from lock")

    with pytest.raises(runner.QualityRunnerError, match="Node toolchain verification failed"):
        runner.run_product_quality(
            repo_root=repo,
            product="web",
            receipt_path=tmp_path / "quality.json",
            arguments={"web_release_archive": str(archive)},
            executor=execute,
            ambient_environment=ambient(),
            installed_distributions=locked_environment("web"),
            **web_node_options(repo, attestor=reject_node),
        )

    assert calls == 0


def test_web_quality_rechecks_node_after_all_steps(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    archive = tmp_path / "walksafe-web-release.tar.gz"
    write_web_fixture(archive, repo)
    executor_calls = 0
    attestor_calls = 0

    def execute(*_):
        nonlocal executor_calls
        executor_calls += 1
        if executor_calls == 6:
            materialize_fresh_web_build(repo)
        return runner.ExecutionResult(0)

    def change_after_steps(*_):
        nonlocal attestor_calls
        attestor_calls += 1
        if attestor_calls == 2:
            raise ValueError("Node tree changed")
        return expected_node_attestation(repo)

    with pytest.raises(runner.QualityRunnerError, match="Node toolchain verification failed"):
        runner.run_product_quality(
            repo_root=repo,
            product="web",
            receipt_path=tmp_path / "quality.json",
            arguments={"web_release_archive": str(archive)},
            executor=execute,
            ambient_environment=ambient(),
            installed_distributions=locked_environment("web"),
            **web_node_options(repo, attestor=change_after_steps),
        )

    assert executor_calls == 8
    assert attestor_calls == 2
    assert not (tmp_path / "quality.json").exists()


def test_backend_secret_is_only_recorded_as_presence(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    receipt_path = tmp_path / "backend-quality.json"
    secret_url = "postgresql+psycopg://quality:DO-NOT-RECORD@127.0.0.1:5432/walksafe_test"
    calls: list[tuple[tuple[str, ...], Path, dict[str, str]]] = []

    receipt = runner.run_product_quality(
        repo_root=repo,
        product="backend",
        receipt_path=receipt_path,
        arguments={},
        executor=passing_executor(calls),
        ambient_environment=ambient(database_url=secret_url),
        installed_distributions=locked_environment("backend"),
    )

    assert calls[0][2]["WALKSAFE_TEST_DATABASE_URL"] == secret_url
    assert "DATABASE_URL" not in calls[0][2]
    assert calls[1][2]["DATABASE_URL"] == secret_url
    assert "WALKSAFE_TEST_DATABASE_URL" not in calls[1][2]
    serialized = receipt_path.read_text(encoding="utf-8")
    serialized += "".join(
        (receipt_path.parent / step["log"]["path"]).read_text(encoding="utf-8")
        for step in receipt["steps"]
    )
    assert secret_url not in serialized
    assert "DO-NOT-RECORD" not in serialized
    assert receipt["steps"][0]["environment"]["required_ambient_presence"] == {
        "WALKSAFE_TEST_DATABASE_URL": {"present": True}
    }
    assert receipt["steps"][1]["environment"]["derived_secret_presence"] == {
        "DATABASE_URL": {"source": "WALKSAFE_TEST_DATABASE_URL", "present": True}
    }


def test_tested_environment_records_the_exact_installed_distribution_set(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)

    receipt = runner.run_product_quality(
        repo_root=repo,
        product="voice",
        receipt_path=tmp_path / "voice-quality.json",
        arguments={},
        executor=passing_executor([]),
        ambient_environment=ambient(),
        installed_distributions=locked_environment(),
    )

    locked_canonical = b"fixture==1\ntesttool==2\n"
    installed_canonical = b"fixture==1\npip==26.1.1\ntesttool==2\n"
    assert receipt["tested_environment"]["locked_distributions"] == {
        "count": 2,
        "sha256": hashlib.sha256(locked_canonical).hexdigest(),
    }
    assert receipt["tested_environment"]["installed_distributions"] == {
        "count": 3,
        "sha256": hashlib.sha256(installed_canonical).hexdigest(),
    }
    site_packages = receipt["tested_environment"]["site_packages"]
    assert site_packages["format"] == "walksafe.record-claimed-site-closure.v2"
    assert site_packages["closure"]["count"] > len(site_packages["roots"])


def test_tested_environment_rejects_missing_and_unexpected_distributions(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    receipt_path = tmp_path / "voice-quality.json"

    with pytest.raises(
        runner.QualityRunnerError,
        match=r"missing=\['testtool'\], unexpected=\['attacker-shadow'\]",
    ):
        runner.run_product_quality(
            repo_root=repo,
            product="voice",
            receipt_path=receipt_path,
            arguments={},
            executor=passing_executor([]),
            ambient_environment=ambient(),
            installed_distributions={
                "attacker-shadow": "9",
                "fixture": "1",
                "pip": "26.1.1",
            },
        )

    assert not receipt_path.exists()


def test_tested_environment_rejects_duplicate_normalized_distribution_names(
    tmp_path: Path,
) -> None:
    repo = create_repository(tmp_path)

    with pytest.raises(runner.QualityRunnerError, match="duplicate normalized distribution"):
        runner.run_product_quality(
            repo_root=repo,
            product="voice",
            receipt_path=tmp_path / "voice-quality.json",
            arguments={},
            executor=passing_executor([]),
            ambient_environment=ambient(),
            installed_distributions={
                "Fixture": "1",
                "fixture": "1",
                "pip": "26.1.1",
                "testtool": "2",
            },
        )


def test_tested_environment_rejects_trusted_tool_version_mismatch(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    installed = locked_environment()
    installed["pip"] = "0"

    with pytest.raises(runner.QualityRunnerError, match=r"version_mismatch=\['pip'\]"):
        runner.run_product_quality(
            repo_root=repo,
            product="voice",
            receipt_path=tmp_path / "voice-quality.json",
            arguments={},
            executor=passing_executor([]),
            ambient_environment=ambient(),
            installed_distributions=installed,
        )


def test_trusted_tool_distribution_must_not_collide_with_a_lock(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)

    with pytest.raises(runner.QualityRunnerError, match="collide with fixed locks"):
        runner._tested_environment_record(
            repo_root=repo,
            lock_relatives=["voice/requirements.lock"],
            expected_site_packages=json.loads(POLICY_PATH.read_text(encoding="utf-8"))[
                "products"
            ]["voice"]["tested_site_packages"],
            trusted_tool_distributions={"fixture": "1"},
            installed_distributions={"fixture": "1"},
        )


def test_quality_steps_hide_ambient_home_configuration(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    attacker_home = tmp_path / "attacker-home"
    (attacker_home / ".gradle").mkdir(parents=True)
    (attacker_home / ".gradle/init.gradle").write_text("throw new Error('injected')\n")
    (attacker_home / ".npmrc").write_text("ignore-scripts=false\n")
    environment = ambient()
    environment["HOME"] = str(attacker_home)
    calls: list[tuple[tuple[str, ...], Path, dict[str, str]]] = []

    receipt = runner.run_product_quality(
        repo_root=repo,
        product="voice",
        receipt_path=tmp_path / "voice-quality.json",
        arguments={},
        executor=passing_executor(calls),
        ambient_environment=environment,
        installed_distributions=locked_environment(),
    )

    private_homes = {call_environment["HOME"] for _, _, call_environment in calls}
    assert len(private_homes) == 1
    private_home = Path(private_homes.pop())
    assert private_home != attacker_home
    assert not private_home.exists()
    for _, _, call_environment in calls:
        assert call_environment["GRADLE_USER_HOME"].startswith(str(private_home))
        assert call_environment["NPM_CONFIG_USERCONFIG"] == str(private_home / ".npmrc")
        assert call_environment["PYTHONNOUSERSITE"] == "1"
    for step in receipt["steps"]:
        assert "HOME" not in step["environment"]["allowlisted_ambient_presence"]
        assert step["environment"]["fixed_public"] | {
            "HOME": "{private_quality_home}",
            "GRADLE_USER_HOME": "{private_quality_home}/.gradle",
            "NPM_CONFIG_USERCONFIG": "{private_quality_home}/.npmrc",
            "PYTHONNOUSERSITE": "1",
        } == step["environment"]["fixed_public"]


def test_web_receipt_rejects_archive_not_built_from_fresh_npm_output(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    archive = tmp_path / "walksafe-web-release.tar.gz"
    write_web_fixture(archive, repo, server=b"not-the-fresh-build")
    calls = 0

    def execute(*_):
        nonlocal calls
        calls += 1
        if calls == 6:
            materialize_fresh_web_build(repo)
        return runner.ExecutionResult(0)

    with pytest.raises(runner.QualityRunnerError, match="archive"):
        runner.run_product_quality(
            repo_root=repo,
            product="web",
            receipt_path=tmp_path / "quality.json",
            arguments={"web_release_archive": str(archive)},
            executor=execute,
            ambient_environment=ambient(),
            installed_distributions=locked_environment("web"),
            **web_node_options(repo),
        )


def test_android_receipt_rejects_stale_ignored_output_before_execution(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    stale = repo / "apps/android/app/build/outputs/apk/release/app-release-unsigned.apk"
    stale.parent.mkdir(parents=True)
    stale.write_bytes(b"stale fixture")

    with pytest.raises(runner.QualityRunnerError, match="undeclared ignored path"):
        runner.run_product_quality(
            repo_root=repo,
            product="android",
            receipt_path=tmp_path / "quality.json",
            arguments={"android_gateway_origin": "https://walksafe.invalid"},
            executor=lambda *_: runner.ExecutionResult(0),
            ambient_environment=ambient(),
        )
    assert stale.read_bytes() == b"stale fixture"


def test_quality_step_rejects_executable_changed_during_execution(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)

    def replace_gradle(*_):
        gradle = repo / "apps/android/gradlew"
        gradle.write_text("#!/bin/sh\nexit 9\n", encoding="utf-8")
        gradle.chmod(0o755)
        return runner.ExecutionResult(0)

    with pytest.raises(runner.QualityRunnerError, match="executable changed during step"):
        runner.run_product_quality(
            repo_root=repo,
            product="android",
            receipt_path=tmp_path / "quality.json",
            arguments={"android_gateway_origin": "https://walksafe.invalid"},
            executor=replace_gradle,
            ambient_environment=ambient(),
        )


def test_resolved_executable_target_survives_path_symlink_retarget(tmp_path: Path) -> None:
    bin_directory = tmp_path / "bin"
    bin_directory.mkdir()
    verified_target = bin_directory / "verified-tool"
    replacement_target = bin_directory / "replacement-tool"
    verified_target.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    replacement_target.write_text("#!/bin/sh\nexit 9\n", encoding="utf-8")
    verified_target.chmod(0o755)
    replacement_target.chmod(0o755)
    path_entry = bin_directory / "quality-tool"
    path_entry.symlink_to(verified_target)

    argv, executable = runner._resolve_executable(
        ["quality-tool"], tmp_path, {"PATH": str(bin_directory)}
    )
    path_entry.unlink()
    path_entry.symlink_to(replacement_target)

    result = runner._default_executor(argv, tmp_path, {"PATH": str(bin_directory)})

    assert result.returncode == 0
    assert argv[0] == str(verified_target.resolve())
    assert executable["path"] == str(verified_target.resolve())


def test_default_python_executor_preserves_virtual_environment(tmp_path: Path) -> None:
    private_home = tmp_path / "home"
    private_home.mkdir()
    result = runner._default_executor(
        [
            str(Path(sys.executable).absolute()),
            "-c",
            "import pytest; raise SystemExit(0 if pytest.__version__ else 1)",
        ],
        tmp_path,
        {
            "PATH": os.environ["PATH"],
            "HOME": str(private_home),
            "PYTHONNOUSERSITE": "1",
            "PYTHONDONTWRITEBYTECODE": "1",
        },
    )

    assert result.returncode == 0


def test_quality_receipt_must_not_publish_inside_source(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    receipt = repo / "quality.json"

    with pytest.raises(runner.QualityRunnerError, match="outside the quality source"):
        runner.run_product_quality(
            repo_root=repo,
            product="voice",
            receipt_path=receipt,
            arguments={},
            executor=lambda *_: pytest.fail("source-internal receipt must fail before execution"),
            ambient_environment=ambient(),
            installed_distributions=locked_environment(),
        )

    assert not receipt.exists()
    assert not (repo / "quality.logs").exists()


def test_web_receipt_rejects_stale_ignored_output_before_cleanup(
    tmp_path: Path,
) -> None:
    repo = create_repository(tmp_path)
    archive = tmp_path / "walksafe-web-release.tar.gz"
    write_web_fixture(archive, repo)
    materialize_fresh_web_build(repo)
    build_root = repo / "apps/web/.next"
    (build_root / "standalone/evil.js").write_bytes(b"stale")
    with pytest.raises(runner.QualityRunnerError, match="undeclared ignored path"):
        runner.run_product_quality(
            repo_root=repo,
            product="web",
            receipt_path=tmp_path / "quality.json",
            arguments={"web_release_archive": str(archive)},
            executor=lambda *_: pytest.fail("stale ignored output must fail before execution"),
            ambient_environment=ambient(),
            installed_distributions=locked_environment("web"),
            **web_node_options(repo),
        )

    assert (build_root / "standalone/evil.js").read_bytes() == b"stale"


def test_web_receipt_rejects_archive_replaced_on_tar_close(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    repo = create_repository(tmp_path)
    archive = tmp_path / "walksafe-web-release.tar.gz"
    replacement = tmp_path / "replacement-web-release.tar.gz"
    write_web_fixture(archive, repo)
    write_web_fixture(replacement, repo, server=b"replacement")
    calls = 0

    def execute(*_):
        nonlocal calls
        calls += 1
        if calls == 6:
            materialize_fresh_web_build(repo)
        return runner.ExecutionResult(0)

    real_tar_open = runner.tarfile.open

    class ReplaceOnExit:
        def __init__(self, opened):
            self.opened = opened

        def __enter__(self):
            return self.opened.__enter__()

        def __exit__(self, *arguments):
            try:
                return self.opened.__exit__(*arguments)
            finally:
                os.replace(replacement, archive)

    def open_then_replace(*arguments, **kwargs):
        return ReplaceOnExit(real_tar_open(*arguments, **kwargs))

    monkeypatch.setattr(runner.tarfile, "open", open_then_replace)
    with pytest.raises(runner.QualityRunnerError, match="changed during fresh build verification"):
        runner.run_product_quality(
            repo_root=repo,
            product="web",
            receipt_path=tmp_path / "quality.json",
            arguments={"web_release_archive": str(archive)},
            executor=execute,
            ambient_environment=ambient(),
            installed_distributions=locked_environment("web"),
            **web_node_options(repo),
        )


def test_web_receipt_rejects_archive_replaced_after_fresh_build_comparison(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    repo = create_repository(tmp_path)
    archive = tmp_path / "walksafe-web-release.tar.gz"
    write_web_fixture(archive, repo)
    calls = 0

    def execute(*_):
        nonlocal calls
        calls += 1
        if calls == 6:
            materialize_fresh_web_build(repo)
        return runner.ExecutionResult(0)

    collect_outputs = runner._collect_outputs

    def replace_then_collect(**arguments):
        write_web_fixture(archive, repo, server=b"replaced-after-comparison")
        return collect_outputs(**arguments)

    monkeypatch.setattr(runner, "_collect_outputs", replace_then_collect)
    with pytest.raises(runner.QualityRunnerError, match="bound step verification"):
        runner.run_product_quality(
            repo_root=repo,
            product="web",
            receipt_path=tmp_path / "quality.json",
            arguments={"web_release_archive": str(archive)},
            executor=execute,
            ambient_environment=ambient(),
            installed_distributions=locked_environment("web"),
            **web_node_options(repo),
        )


@pytest.mark.parametrize("mutation", ["argv", "step"])
def test_runner_rejects_argv_or_step_policy_changes(tmp_path: Path, mutation: str) -> None:
    repo = create_repository(tmp_path)
    mutated_policy = tmp_path / "mutated-policy.json"
    payload = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
    if mutation == "argv":
        payload["products"]["web"]["steps"][0]["argv"] = ["npm", "install"]
    else:
        payload["products"]["web"]["steps"].append(
            {"id": "forged-pass", "argv": ["true"], "cwd": "."}
        )
    mutated_policy.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(runner.QualityRunnerError, match="pinned policy"):
        runner.run_product_quality(
            repo_root=repo,
            product="voice",
            receipt_path=tmp_path / "quality.json",
            arguments={},
            executor=lambda *_: runner.ExecutionResult(0),
            ambient_environment=ambient(),
            policy_path=mutated_policy,
        )


def test_forged_pass_output_cannot_override_failure_exit(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    receipt_path = tmp_path / "quality.json"
    (tmp_path / "forged.log").write_text("PASS\n", encoding="utf-8")

    def forged_pass_executor(*_):
        return runner.ExecutionResult(returncode=17, stdout=b"PASS\n", stderr=b"all checks PASS\n")

    with pytest.raises(runner.QualityRunnerError, match="exit code 17"):
        runner.run_product_quality(
            repo_root=repo,
            product="voice",
            receipt_path=receipt_path,
            arguments={},
            executor=forged_pass_executor,
            ambient_environment=ambient(),
            installed_distributions=locked_environment(),
        )
    assert not receipt_path.exists()
    assert not (tmp_path / "quality.logs").exists()


def test_any_nonzero_step_exit_prevents_receipt(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    receipt_path = tmp_path / "quality.json"
    call_count = 0

    def fail_second_step(*_):
        nonlocal call_count
        call_count += 1
        return runner.ExecutionResult(returncode=3 if call_count == 2 else 0)

    with pytest.raises(runner.QualityRunnerError, match="exit code 3"):
        runner.run_product_quality(
            repo_root=repo,
            product="voice",
            receipt_path=receipt_path,
            arguments={},
            executor=fail_second_step,
            ambient_environment=ambient(),
            installed_distributions=locked_environment(),
        )
    assert call_count == 2
    assert not receipt_path.exists()


def test_dirty_tree_before_execution_is_rejected(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    (repo / "untracked.txt").write_text("dirty\n", encoding="utf-8")

    with pytest.raises(runner.QualityRunnerError, match="dirty before"):
        runner.run_product_quality(
            repo_root=repo,
            product="voice",
            receipt_path=tmp_path / "quality.json",
            arguments={},
            executor=lambda *_: pytest.fail("dirty source must not execute commands"),
            ambient_environment=ambient(),
            installed_distributions=locked_environment(),
        )


def test_dirty_tree_after_execution_prevents_receipt(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    receipt_path = tmp_path / "quality.json"
    modified = False

    def dirty_executor(*_):
        nonlocal modified
        if not modified:
            (repo / "README.md").write_text("modified during execution\n", encoding="utf-8")
            modified = True
        return runner.ExecutionResult(0)

    with pytest.raises(runner.QualityRunnerError, match="dirty after"):
        runner.run_product_quality(
            repo_root=repo,
            product="voice",
            receipt_path=receipt_path,
            arguments={},
            executor=dirty_executor,
            ambient_environment=ambient(),
            installed_distributions=locked_environment(),
        )
    assert not receipt_path.exists()
    assert not (tmp_path / "quality.logs").exists()


def test_assume_unchanged_cannot_hide_tracked_bytes_from_exact_git_verification(
    tmp_path: Path,
) -> None:
    repo = create_repository(tmp_path)
    git(repo, "update-index", "--assume-unchanged", "README.md")
    (repo / "README.md").write_text("hidden replacement\n", encoding="utf-8")

    with pytest.raises(runner.QualityRunnerError, match="bytes differ from HEAD"):
        runner.run_product_quality(
            repo_root=repo,
            product="voice",
            receipt_path=tmp_path / "quality.json",
            arguments={},
            executor=lambda *_: pytest.fail("hidden source change must fail before execution"),
            ambient_environment=ambient(),
            installed_distributions=locked_environment(),
        )


def test_ignored_environment_file_is_rejected_before_quality_execution(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    (repo / ".env.local").write_text("INJECTED=true\n", encoding="utf-8")

    with pytest.raises(runner.QualityRunnerError, match="undeclared ignored path"):
        runner.run_product_quality(
            repo_root=repo,
            product="voice",
            receipt_path=tmp_path / "quality.json",
            arguments={},
            executor=lambda *_: pytest.fail("ignored input injection must fail before execution"),
            ambient_environment=ambient(),
            installed_distributions=locked_environment(),
        )


def test_same_bytes_inode_replacement_is_detected_during_exact_git_scan(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    repo = create_repository(tmp_path)
    target = repo / "README.md"
    original_read = runner.FileSnapshot.read_bytes
    replaced = False

    def replace_after_read(snapshot, *args, **kwargs):
        nonlocal replaced
        payload = original_read(snapshot, *args, **kwargs)
        if snapshot.display_path == "README.md" and not replaced:
            replacement = target.with_name(".README.replacement")
            replacement.write_bytes(payload)
            replacement.chmod(target.stat().st_mode & 0o777)
            os.replace(replacement, target)
            replaced = True
        return payload

    monkeypatch.setattr(runner.FileSnapshot, "read_bytes", replace_after_read)
    with pytest.raises(runner.ReleaseIntegrityError, match="tracked file changed"):
        runner.verify_exact_git_source(repo, context="test source")


def test_git_replace_ref_is_rejected_even_with_original_worktree(tmp_path: Path) -> None:
    repo = create_repository(tmp_path)
    original_commit = subprocess.run(
        ["git", "-C", str(repo), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    (repo / "README.md").write_text("replacement source\n", encoding="utf-8")
    git(repo, "add", "README.md")
    git(repo, "commit", "-qm", "replacement")
    replacement_commit = subprocess.run(
        ["git", "-C", str(repo), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    git(repo, "reset", "--hard", original_commit)
    git(repo, "replace", original_commit, replacement_commit)

    with pytest.raises(runner.ReleaseIntegrityError, match="replace refs"):
        runner.verify_exact_git_source(repo, context="test source")


def test_quality_log_directory_publication_never_replaces_concurrent_directory(
    tmp_path: Path,
) -> None:
    repo = create_repository(tmp_path)
    competitor = tmp_path / "quality.logs"
    competitor_inode: int | None = None

    def create_competitor(*_):
        nonlocal competitor_inode
        if competitor_inode is None:
            competitor.mkdir()
            competitor_inode = competitor.stat().st_ino
        return runner.ExecutionResult(0)

    with pytest.raises(runner.QualityRunnerError, match="created concurrently"):
        runner.run_product_quality(
            repo_root=repo,
            product="voice",
            receipt_path=tmp_path / "quality.json",
            arguments={},
            executor=create_competitor,
            ambient_environment=ambient(),
            installed_distributions=locked_environment(),
        )

    assert competitor.is_dir()
    assert competitor.stat().st_ino == competitor_inode
    assert not (tmp_path / "quality.json").exists()
