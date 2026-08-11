from __future__ import annotations

import base64
import hashlib
import importlib.metadata
import importlib.util
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import types

import pytest


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "scripts/run_walksafe_submission_python_20260714.py"
RUNBOOK = ROOT / "docs/submission/CLEAN_ROOM_REPRODUCTION_20260713.md"
SUBMISSION_ENTRYPOINTS = (
    "audit_submission_visual_privacy_20260711.py",
    "build_design_documents_20260710.py",
    "build_submission_assets_20260710.py",
    "build_submission_forms_20260710.py",
    "promote_submission_final_20260713.py",
    "validate_submission_forms_20260710.py",
    "validate_submission_materials_20260710.py",
)


def load_runner():
    spec = importlib.util.spec_from_file_location("walksafe_submission_runner_under_test", RUNNER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def install_distribution(
    site_root: Path,
    name: str,
    version: str,
    files: dict[str, bytes | str],
) -> None:
    distribution = site_root / f"{name}-{version}.dist-info"
    payloads = {
        **files,
        f"{distribution.name}/METADATA": (
            f"Metadata-Version: 2.4\nName: {name}\nVersion: {version}\n"
        ),
    }
    rows: list[str] = []
    for relative, payload in sorted(payloads.items()):
        raw = payload.encode("utf-8") if isinstance(payload, str) else payload
        path = site_root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
        digest = base64.urlsafe_b64encode(hashlib.sha256(raw).digest()).rstrip(b"=").decode()
        rows.append(f"{relative},sha256={digest},{len(raw)}")
    record_relative = f"{distribution.name}/RECORD"
    rows.append(f"{record_relative},,")
    (site_root / record_relative).write_text("\n".join(rows) + "\n", encoding="utf-8")


def installed_bundle(site_root: Path, name: str, version: str) -> tuple[int, str]:
    runner = load_runner()
    distribution = importlib.metadata.PathDistribution(site_root / f"{name}-{version}.dist-info")
    prefix = site_root.parents[2]
    previous_prefix = runner.sys.prefix
    runner.sys.prefix = str(prefix)
    try:
        bundle, _identities, _installed_paths = runner._semantic_distribution_bundle(
            distribution,
            runner._normalize_distribution_name(name),
            (site_root,),
        )
    finally:
        runner.sys.prefix = previous_prefix
    return bundle


def append_record_claim(
    site_root: Path,
    name: str,
    version: str,
    relative: str,
    hash_field: str = "",
    size_field: str = "",
) -> None:
    record = site_root / f"{name}-{version}.dist-info/RECORD"
    rows = record.read_text(encoding="utf-8").splitlines()
    record_row = rows.pop()
    rows.extend((f"{relative},{hash_field},{size_field}", record_row))
    record.write_text("\n".join(rows) + "\n", encoding="utf-8")


def write_recorded_claim(
    site_root: Path,
    name: str,
    version: str,
    relative: str,
    installed: Path,
    payload: bytes,
) -> None:
    installed.parent.mkdir(parents=True, exist_ok=True)
    installed.write_bytes(payload)
    digest = base64.urlsafe_b64encode(hashlib.sha256(payload).digest()).rstrip(b"=").decode()
    append_record_claim(
        site_root,
        name,
        version,
        relative,
        f"sha256={digest}",
        str(len(payload)),
    )


def test_submission_runner_blocks_ambient_python_startup_hooks(tmp_path: Path) -> None:
    marker = tmp_path / "ambient-sitecustomize-executed"
    ambient = tmp_path / "ambient"
    ambient.mkdir()
    (ambient / "sitecustomize.py").write_text(
        f"from pathlib import Path\nPath({str(marker)!r}).write_text('executed')\n",
        encoding="utf-8",
    )

    completed = subprocess.run(
        [sys.executable, "-I", "-S", "-B", str(RUNNER), "--help"],
        cwd=ROOT,
        env={**os.environ, "PYTHONPATH": str(ambient)},
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 0, completed.stderr
    assert "submission" in completed.stdout.lower()
    assert not marker.exists()


@pytest.mark.parametrize("startup_flags", [(), ("-I", "-S", "-B")])
@pytest.mark.parametrize("script", SUBMISSION_ENTRYPOINTS)
def test_submission_entrypoints_reject_direct_execution(
    script: str,
    startup_flags: tuple[str, ...],
) -> None:
    completed = subprocess.run(
        [sys.executable, *startup_flags, str(ROOT / "scripts" / script), "--help"],
        cwd=ROOT,
        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        capture_output=True,
        text=True,
    )

    assert completed.returncode != 0
    assert "requires Python -I -S -B" in completed.stderr


def test_submission_runner_pins_match_current_trust_sources() -> None:
    runner = load_runner()

    for path, expected in (
        (ROOT / "scripts/run_walksafe_isolated_python_20260713.py", runner._BASE_BOOTSTRAP_SHA256),
        (ROOT / "scripts/submission_manifest_policy.py", runner._SUBMISSION_POLICY_SHA256),
        (ROOT / runner._TOOLCHAIN_LOCK_RELATIVE, runner._TOOLCHAIN_LOCK_SHA256),
    ):
        assert hashlib.sha256(path.read_bytes()).hexdigest() == expected


def test_submission_runner_rejects_cross_checkout_repo_root(tmp_path: Path) -> None:
    runner = load_runner()

    runner._require_runner_checkout(ROOT)
    with pytest.raises(runner.SubmissionRunnerError, match="same checkout"):
        runner._require_runner_checkout(tmp_path)


def test_submission_runner_allows_only_canonical_entrypoints() -> None:
    runner = load_runner()
    expected = {f"scripts/{script}" for script in SUBMISSION_ENTRYPOINTS}

    assert runner._CANONICAL_ENTRYPOINTS == expected
    for script in SUBMISSION_ENTRYPOINTS:
        runner._require_canonical_command((f"scripts/{script}", "--help"))
    for command in (
        ("-m", "scripts.promote_submission_final_20260713"),
        ("docs/submission/.final-promotion-staging/evil.py",),
        (str(ROOT / "scripts/promote_submission_final_20260713.py"),),
    ):
        with pytest.raises(runner.SubmissionRunnerError, match="canonical entrypoint"):
            runner._require_canonical_command(command)


def test_submission_runner_verify_only_attests_without_dispatch(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    runner = load_runner()
    expected_commit = "a" * 40
    site_root = tmp_path / "site-packages"
    site_root.mkdir()
    attested_roots: list[Path] = []

    class HelperError(RuntimeError):
        pass

    def inspect_site_environment(_expected: object) -> tuple[dict[str, object], tuple[Path, ...], object]:
        return {"site": "locked"}, (site_root,), ("site-identity",)

    def reject_dispatch(*_args: object) -> None:
        raise AssertionError("verify-only must not dispatch a submission entrypoint")

    helper = types.SimpleNamespace(
        IsolatedPythonError=HelperError,
        _repository_root=lambda _raw: ROOT,
        inspect_site_environment=inspect_site_environment,
        _dispatch=reject_dispatch,
    )

    def attest(repo_root: Path) -> dict[str, object]:
        assert repo_root == ROOT
        assert str(site_root) in sys.path
        attested_roots.append(repo_root)
        return {"verified": {"libraries": [{"distribution": "Pillow"}]}}

    policy = types.SimpleNamespace(
        ALL_SUBMISSION_GENERATED_PATHS=frozenset(),
        submission_toolchain_attestation=attest,
    )

    def load_pinned(_name: str, path: Path, _digest: str) -> object:
        return helper if path == runner._BASE_BOOTSTRAP_PATH else policy

    monkeypatch.setattr(
        runner,
        "_parse_args",
        lambda: types.SimpleNamespace(
            repo_root=ROOT,
            expected_commit=expected_commit,
            verify_only=True,
            command=[],
        ),
    )
    monkeypatch.setattr(runner, "_configure_process_environment", lambda *_args: {})
    monkeypatch.setattr(runner, "_load_pinned_source", load_pinned)
    monkeypatch.setattr(runner, "_require_runner_checkout", lambda *_args: None)
    monkeypatch.setattr(runner, "_verify_source", lambda *_args: {"source_commit": expected_commit})
    monkeypatch.setattr(runner, "_locked_environment", lambda *_args: ({}, {}))
    monkeypatch.setattr(
        runner,
        "_locked_library_bundles",
        lambda *_args: ({}, ("bundle-identity",)),
    )

    original_path = list(sys.path)
    assert runner.main() == 0

    assert attested_roots == [ROOT]
    assert sys.path == original_path
    assert '"verified"' in capsys.readouterr().out

    def reject_attestation(_repo_root: Path) -> dict[str, object]:
        raise ValueError("invalid native runtime")

    policy.submission_toolchain_attestation = reject_attestation
    with pytest.raises(runner.SubmissionRunnerError, match="invalid native runtime"):
        runner.main()
    assert sys.path == original_path
    assert capsys.readouterr().out == ""


def test_submission_source_verification_ignores_ambient_git_environment(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = load_runner()
    repo = tmp_path / "repo"
    repo.mkdir()
    tracked = repo / "tracked.py"
    tracked.write_text("VALUE = 1\n", encoding="utf-8")
    (repo / ".gitattributes").write_text("*.py filter=walksafe-test\n", encoding="utf-8")
    setup_environment = {
        "LC_ALL": "C",
        "LANG": "C",
        "PATH": "/usr/bin:/bin",
        "HOME": str(tmp_path / "setup-home"),
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_SYSTEM": "/dev/null",
        "GIT_CONFIG_GLOBAL": "/dev/null",
    }
    Path(setup_environment["HOME"]).mkdir()
    subprocess.run(["/usr/bin/git", "init", "--quiet", str(repo)], check=True, env=setup_environment)
    subprocess.run(
        ["/usr/bin/git", "-C", str(repo), "add", "tracked.py", ".gitattributes"],
        check=True,
        env=setup_environment,
    )
    subprocess.run(
        [
            "/usr/bin/git",
            "-c",
            "user.name=WalkSafe Test",
            "-c",
            "user.email=walksafe@example.invalid",
            "-C",
            str(repo),
            "commit",
            "--quiet",
            "-m",
            "fixture",
        ],
        check=True,
        env=setup_environment,
    )
    commit = subprocess.run(
        ["/usr/bin/git", "-C", str(repo), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
        env=setup_environment,
    ).stdout.strip()
    filter_marker = tmp_path / "git-clean-filter-executed"
    filter_script = tmp_path / "git-clean-filter.sh"
    filter_script.write_text(
        f"#!/bin/sh\n: > {shlex.quote(str(filter_marker))}\ncat\n",
        encoding="utf-8",
    )
    filter_script.chmod(0o755)
    subprocess.run(
        [
            "/usr/bin/git",
            "-C",
            str(repo),
            "config",
            "filter.walksafe-test.clean",
            str(filter_script),
        ],
        check=True,
        env=setup_environment,
    )
    subprocess.run(
        [
            "/usr/bin/git",
            "-C",
            str(repo),
            "config",
            "filter.walksafe-test.required",
            "true",
        ],
        check=True,
        env=setup_environment,
    )
    private_home = tmp_path / "private-home"
    (private_home / "xdg").mkdir(parents=True)
    policy = types.SimpleNamespace(ALL_SUBMISSION_GENERATED_PATHS=frozenset())

    monkeypatch.setenv("GIT_DIR", str(tmp_path / "invalid-git-dir"))
    monkeypatch.setenv("GIT_WORK_TREE", str(tmp_path / "invalid-worktree"))
    monkeypatch.setenv("GIT_INDEX_FILE", str(tmp_path / "invalid-index"))
    monkeypatch.setenv("GIT_CONFIG_COUNT", "1")
    monkeypatch.setenv("GIT_CONFIG_KEY_0", "core.fsmonitor")
    monkeypatch.setenv("GIT_CONFIG_VALUE_0", "true")

    revision = runner._verify_source(
        policy,
        repo,
        commit,
        runner._git_environment(private_home),
    )
    assert revision["source_commit"] == commit
    assert not filter_marker.exists()

    alternate_index = tmp_path / "alternate-index"
    shutil.copyfile(repo / ".git/index", alternate_index)
    alternate_environment = {**setup_environment, "GIT_INDEX_FILE": str(alternate_index)}
    subprocess.run(
        ["/usr/bin/git", "-C", str(repo), "update-index", "--assume-unchanged", "tracked.py"],
        check=True,
        env=alternate_environment,
    )
    filter_marker.unlink(missing_ok=True)
    tracked.write_text("VALUE = 2\n", encoding="utf-8")
    with pytest.raises(runner.SubmissionRunnerError, match="bytes differ"):
        runner._verify_source(
            policy,
            repo,
            commit,
            runner._git_environment(private_home),
        )
    assert not filter_marker.exists()


@pytest.mark.skipif(
    not RUNBOOK.is_file(),
    reason="legacy local-only submission runbook is not present in the current repository",
)
def test_submission_runbook_routes_every_python_entrypoint_through_runner() -> None:
    runbook = RUNBOOK.read_text(encoding="utf-8")

    assert 'SUBMISSION_RUNNER="$clone/scripts/run_walksafe_submission_python_20260714.py"' in runbook
    assert '"$SUBMISSION_PYTHON" -I -S -B "$SUBMISSION_RUNNER"' in runbook
    assert runbook.index("--verify-only") < runbook.index(
        "run_submission_python scripts/build_submission_assets_20260710.py"
    )
    for script in SUBMISSION_ENTRYPOINTS:
        assert f"scripts/{script}" in runbook


def test_tracked_markdown_has_no_plain_submission_python_invocation() -> None:
    markdown_paths = subprocess.run(
        ["/usr/bin/git", "-C", str(ROOT), "ls-files", "-z", "--", "*.md"],
        check=True,
        capture_output=True,
    ).stdout.split(b"\0")
    entrypoints = "|".join(re.escape(f"scripts/{script}") for script in SUBMISSION_ENTRYPOINTS)
    direct_invocation = re.compile(
        rf'(?:\bpython(?:3(?:\.\d+)?)?|\S*/bin/python(?:3(?:\.\d+)?)?'
        rf'|"?\$(?:\{{)?(?:SUBMISSION_PYTHON|PYTHON_BIN)(?:\}})?"?)'
        rf"\s+(?P<arguments>[^\n]*?(?:{entrypoints}))"
    )

    for raw_path in markdown_paths:
        if not raw_path:
            continue
        relative = raw_path.decode("utf-8")
        text = (ROOT / relative).read_text(encoding="utf-8")
        logical_lines = re.sub(r"\\\r?\n\s*", " ", text)
        for match in direct_invocation.finditer(logical_lines):
            assert "SUBMISSION_RUNNER" in match.group(0), relative


def test_submission_runner_rejects_extra_distribution_before_dispatch(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = load_runner()
    helper = runner._load_pinned_source(
        "_walksafe_submission_test_bootstrap_extra",
        ROOT / "scripts/run_walksafe_isolated_python_20260713.py",
        runner._BASE_BOOTSTRAP_SHA256,
    )
    site_root = tmp_path / "lib/python3.14/site-packages"
    install_distribution(site_root, "Pillow", "12.2.0", {"PIL/__init__.py": "VALUE = 1\n"})
    install_distribution(site_root, "evil", "1.0", {"evil/__init__.py": "ATTACK = True\n"})
    count, bundle = installed_bundle(site_root, "Pillow", "12.2.0")
    monkeypatch.setattr(runner.sys, "prefix", str(tmp_path))

    with pytest.raises(runner.SubmissionRunnerError, match="exact fixed distribution set"):
        runner._locked_library_bundles(
            helper,
            (site_root,),
            {"pillow": "12.2.0"},
            {"pillow": {"file_count": count, "file_bundle_sha256": bundle}},
        )


def test_submission_runner_rejects_self_consistent_unlocked_library_bytes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = load_runner()
    helper = runner._load_pinned_source(
        "_walksafe_submission_test_bootstrap_bundle",
        ROOT / "scripts/run_walksafe_isolated_python_20260713.py",
        runner._BASE_BOOTSTRAP_SHA256,
    )
    site_root = tmp_path / "lib/python3.14/site-packages"
    install_distribution(site_root, "Pillow", "12.2.0", {"PIL/__init__.py": "TAMPERED = True\n"})
    count, _bundle = installed_bundle(site_root, "Pillow", "12.2.0")
    monkeypatch.setattr(runner.sys, "prefix", str(tmp_path))

    with pytest.raises(runner.SubmissionRunnerError, match="differs from lock"):
        runner._locked_library_bundles(
            helper,
            (site_root,),
            {"pillow": "12.2.0"},
            {"pillow": {"file_count": count, "file_bundle_sha256": "0" * 64}},
        )


def test_submission_bundle_is_prefix_and_requested_marker_independent(tmp_path: Path) -> None:
    bundles: list[tuple[int, str]] = []
    record_payloads: list[bytes] = []
    for index, prefix in enumerate(
        (tmp_path / "short", tmp_path / "a-much-longer-submission-prefix")
    ):
        site_root = prefix / "lib/python3.14/site-packages"
        install_distribution(site_root, "numpy", "2.4.4", {"numpy/__init__.py": "VALUE = 1\n"})
        script = f"#!{prefix}/bin/python\nprint('stable')\n".encode("utf-8")
        write_recorded_claim(
            site_root,
            "numpy",
            "2.4.4",
            "../../../bin/f2py",
            prefix / "bin/f2py",
            script,
        )
        if index == 0:
            write_recorded_claim(
                site_root,
                "numpy",
                "2.4.4",
                "numpy-2.4.4.dist-info/REQUESTED",
                site_root / "numpy-2.4.4.dist-info/REQUESTED",
                b"",
            )
        record_payloads.append((site_root / "numpy-2.4.4.dist-info/RECORD").read_bytes())
        bundles.append(installed_bundle(site_root, "numpy", "2.4.4"))

    assert record_payloads[0] != record_payloads[1]
    assert bundles[0] == bundles[1]


def test_submission_bundle_does_not_erase_prefix_bytes_from_console_body(
    tmp_path: Path,
) -> None:
    prefix = tmp_path / "submission-venv"
    site_root = prefix / "lib/python3.14/site-packages"
    install_distribution(site_root, "numpy", "2.4.4", {"numpy/__init__.py": "VALUE = 1\n"})
    write_recorded_claim(
        site_root,
        "numpy",
        "2.4.4",
        "../../../bin/f2py",
        prefix / "bin/f2py",
        f"#!{prefix}/bin/python\nVALUE = '/__walksafe_venv__'\n".encode("utf-8"),
    )
    before = installed_bundle(site_root, "numpy", "2.4.4")

    record = site_root / "numpy-2.4.4.dist-info/RECORD"
    retained = [
        line
        for line in record.read_text(encoding="utf-8").splitlines()
        if not line.startswith("../../../bin/f2py,")
    ]
    record.write_text("\n".join(retained) + "\n", encoding="utf-8")
    write_recorded_claim(
        site_root,
        "numpy",
        "2.4.4",
        "../../../bin/f2py",
        prefix / "bin/f2py",
        f"#!{prefix}/bin/python\nVALUE = {str(prefix)!r}\n".encode("utf-8"),
    )

    assert installed_bundle(site_root, "numpy", "2.4.4") != before


def test_submission_bundle_rejects_literal_console_normalization_marker(
    tmp_path: Path,
) -> None:
    prefix = tmp_path / "submission-venv"
    site_root = prefix / "lib/python3.14/site-packages"
    install_distribution(site_root, "numpy", "2.4.4", {"numpy/__init__.py": "VALUE = 1\n"})
    write_recorded_claim(
        site_root,
        "numpy",
        "2.4.4",
        "../../../bin/f2py",
        prefix / "bin/f2py",
        b"#!/__walksafe_venv__/bin/python\nprint('stable')\n",
    )

    with pytest.raises(RuntimeError, match="exact Python launcher shebang"):
        installed_bundle(site_root, "numpy", "2.4.4")

    spec = importlib.util.spec_from_file_location(
        "submission_policy_marker_test",
        ROOT / "scripts/submission_manifest_policy.py",
    )
    assert spec is not None and spec.loader is not None
    policy = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = policy
    spec.loader.exec_module(policy)
    distribution = importlib.metadata.PathDistribution(site_root / "numpy-2.4.4.dist-info")
    with pytest.raises(ValueError, match="exact Python launcher shebang"):
        policy._semantic_distribution_bundle(distribution, "numpy", prefix, {})


def test_submission_runner_allows_only_absent_unhashed_record_bytecode(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = load_runner()
    helper = runner._load_pinned_source(
        "_walksafe_submission_test_bootstrap_absent_pyc",
        ROOT / "scripts/run_walksafe_isolated_python_20260713.py",
        runner._BASE_BOOTSTRAP_SHA256,
    )
    site_root = tmp_path / "lib/python3.14/site-packages"
    install_distribution(site_root, "Pillow", "12.2.0", {"PIL/__init__.py": "VALUE = 1\n"})
    append_record_claim(site_root, "Pillow", "12.2.0", "PIL/__pycache__/missing.pyc")
    count, bundle = installed_bundle(site_root, "Pillow", "12.2.0")
    monkeypatch.setattr(runner.sys, "prefix", str(tmp_path))

    records, _identities = runner._locked_library_bundles(
        helper,
        (site_root,),
        {"pillow": "12.2.0"},
        {"pillow": {"file_count": count, "file_bundle_sha256": bundle}},
    )
    assert records["pillow"] == (count, bundle)


def test_submission_runner_rejects_actual_record_bytecode(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = load_runner()
    helper = runner._load_pinned_source(
        "_walksafe_submission_test_bootstrap_actual_pyc",
        ROOT / "scripts/run_walksafe_isolated_python_20260713.py",
        runner._BASE_BOOTSTRAP_SHA256,
    )
    site_root = tmp_path / "lib/python3.14/site-packages"
    install_distribution(site_root, "Pillow", "12.2.0", {"PIL/__init__.py": "VALUE = 1\n"})
    bytecode = site_root / "PIL/__pycache__/actual.pyc"
    bytecode.parent.mkdir(parents=True)
    bytecode.write_bytes(b"executable bytecode")
    append_record_claim(site_root, "Pillow", "12.2.0", "PIL/__pycache__/actual.pyc")
    monkeypatch.setattr(runner.sys, "prefix", str(tmp_path))

    with pytest.raises(runner.SubmissionRunnerError, match="executable bytecode"):
        runner._locked_library_bundles(
            helper,
            (site_root,),
            {"pillow": "12.2.0"},
            {"pillow": {"file_count": 1, "file_bundle_sha256": "0" * 64}},
        )


def test_submission_runner_verifies_record_hash_outside_site_root(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = load_runner()
    helper = runner._load_pinned_source(
        "_walksafe_submission_test_bootstrap_outside_record",
        ROOT / "scripts/run_walksafe_isolated_python_20260713.py",
        runner._BASE_BOOTSTRAP_SHA256,
    )
    site_root = tmp_path / "lib/python3.14/site-packages"
    install_distribution(site_root, "XlsxWriter", "3.2.9", {"xlsxwriter/__init__.py": "VALUE = 1\n"})
    tool = tmp_path / "bin/vba_extract.py"
    tool.parent.mkdir()
    tool.write_bytes(b"trusted tool bytes\n")
    bad_digest = base64.urlsafe_b64encode(b"\0" * 32).rstrip(b"=").decode()
    append_record_claim(
        site_root,
        "XlsxWriter",
        "3.2.9",
        "../../../bin/vba_extract.py",
        f"sha256={bad_digest}",
        str(tool.stat().st_size),
    )
    monkeypatch.setattr(runner.sys, "prefix", str(tmp_path))

    with pytest.raises(runner.SubmissionRunnerError, match="differs from distribution RECORD"):
        runner._locked_library_bundles(
            helper,
            (site_root,),
            {"xlsxwriter": "3.2.9"},
            {"xlsxwriter": {"file_count": 1, "file_bundle_sha256": "0" * 64}},
        )


def test_submission_runner_rejects_outside_site_duplicate_ownership(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = load_runner()
    helper = runner._load_pinned_source(
        "_walksafe_submission_test_bootstrap_duplicate_owner",
        ROOT / "scripts/run_walksafe_isolated_python_20260713.py",
        runner._BASE_BOOTSTRAP_SHA256,
    )
    site_root = tmp_path / "lib/python3.14/site-packages"
    install_distribution(site_root, "Pillow", "12.2.0", {"PIL/__init__.py": "VALUE = 1\n"})
    install_distribution(
        site_root,
        "XlsxWriter",
        "3.2.9",
        {"xlsxwriter/__init__.py": "VALUE = 1\n"},
    )
    tool = tmp_path / "bin/shared-tool.py"
    tool.parent.mkdir()
    tool.write_bytes(f"#!{tmp_path}/bin/python\nshared tool bytes\n".encode("utf-8"))
    digest = base64.urlsafe_b64encode(hashlib.sha256(tool.read_bytes()).digest()).rstrip(b"=").decode()
    for name, version in (("Pillow", "12.2.0"), ("XlsxWriter", "3.2.9")):
        append_record_claim(
            site_root,
            name,
            version,
            "../../../bin/shared-tool.py",
            f"sha256={digest}",
            str(tool.stat().st_size),
        )
    pillow_bundle = installed_bundle(site_root, "Pillow", "12.2.0")
    xlsx_bundle = installed_bundle(site_root, "XlsxWriter", "3.2.9")
    monkeypatch.setattr(runner.sys, "prefix", str(tmp_path))

    with pytest.raises(runner.SubmissionRunnerError, match="claimed by multiple distributions"):
        runner._locked_library_bundles(
            helper,
            (site_root,),
            {"pillow": "12.2.0", "xlsxwriter": "3.2.9"},
            {
                "pillow": {
                    "file_count": pillow_bundle[0],
                    "file_bundle_sha256": pillow_bundle[1],
                },
                "xlsxwriter": {
                    "file_count": xlsx_bundle[0],
                    "file_bundle_sha256": xlsx_bundle[1],
                },
            },
        )
