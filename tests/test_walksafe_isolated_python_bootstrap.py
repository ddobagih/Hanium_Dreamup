from __future__ import annotations

import base64
import hashlib
import importlib.util
import json
import marshal
import os
from pathlib import Path
import shutil
import shlex
import struct
import subprocess
import sys

import pytest


ROOT = Path(__file__).resolve().parents[1]
BOOTSTRAP_PATH = ROOT / "scripts/run_walksafe_isolated_python_20260713.py"
RUNBOOK_PATH = ROOT / "docs/release/walksafe_full_rc_20260713.md"


def required_quality_cpython_314() -> Path:
    configured = os.environ.get("WEB_QUALITY_PYTHON")
    if not configured:
        pytest.fail("WEB_QUALITY_PYTHON must provide the locked CPython 3.14.4 for quality behavior tests")
    candidate = Path(configured)
    completed = subprocess.run(
        [
            str(candidate),
            "-I",
            "-S",
            "-B",
            "-c",
            "import sys; print(sys.implementation.name, *sys.version_info[:3])",
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0 or completed.stdout.strip() != "cpython 3 14 4":
        pytest.fail("WEB_QUALITY_PYTHON must be the executable locked CPython 3.14.4 interpreter")
    return candidate


def load_bootstrap():
    spec = importlib.util.spec_from_file_location(
        "walksafe_isolated_python_bootstrap_under_test", BOOTSTRAP_PATH
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


bootstrap = load_bootstrap()


def write(path: Path, payload: bytes | str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(payload, str):
        path.write_text(payload, encoding="utf-8")
    else:
        path.write_bytes(payload)


def write_matching_timestamp_pyc(source: Path, payload: str) -> None:
    metadata = source.stat()
    code = compile(payload, str(source), "exec")
    header = importlib.util.MAGIC_NUMBER + struct.pack(
        "<III",
        0,
        int(metadata.st_mtime) & 0xFFFFFFFF,
        metadata.st_size & 0xFFFFFFFF,
    )
    write(Path(importlib.util.cache_from_source(str(source))), header + marshal.dumps(code))


def copy_release_scripts(root: Path, names: list[str]) -> Path:
    scripts = root / "scripts"
    scripts.mkdir(parents=True)
    for name in names:
        shutil.copy2(ROOT / "scripts" / name, scripts / name)
    return scripts


def install_distribution(
    site_root: Path,
    name: str,
    version: str,
    files: dict[str, bytes | str],
) -> None:
    distribution = site_root / f"{name}-{version}.dist-info"
    metadata_relative = f"{distribution.name}/METADATA"
    payloads = {
        **files,
        metadata_relative: (
            f"Metadata-Version: 2.4\nName: {name}\nVersion: {version}\n"
        ),
    }
    record_lines: list[str] = []
    for relative, payload in sorted(payloads.items()):
        raw = payload.encode("utf-8") if isinstance(payload, str) else payload
        write(site_root / relative, raw)
        encoded = base64.urlsafe_b64encode(hashlib.sha256(raw).digest()).rstrip(b"=").decode()
        record_lines.append(f"{relative},sha256={encoded},{len(raw)}")
    record_relative = f"{distribution.name}/RECORD"
    record_lines.append(f"{record_relative},,")
    write(site_root / record_relative, "\n".join(record_lines) + "\n")


def create_web_site(site_root: Path, *, hooks: dict[str, str] | None = None) -> None:
    site_root.mkdir(parents=True, exist_ok=True)
    install_distribution(
        site_root,
        "pip",
        "26.1.1",
        {"pip/__init__.py": "__version__ = '26.1.1'\n", **(hooks or {})},
    )
    install_distribution(
        site_root,
        "websockets",
        "16.0",
        {"websockets/__init__.py": "__version__ = '16.0'\n"},
    )


def add_websockets_console_script_claim(prefix: Path, site_root: Path, body: str) -> None:
    script = f"#!{prefix}/bin/python\n{body}".encode("utf-8")
    write(prefix / "bin/websockets", script)
    encoded = base64.urlsafe_b64encode(hashlib.sha256(script).digest()).rstrip(b"=").decode()
    record = site_root / "websockets-16.0.dist-info/RECORD"
    record.write_text(
        f"../../../bin/websockets,sha256={encoded},{len(script)}\n"
        + record.read_text(encoding="utf-8"),
        encoding="utf-8",
    )


def web_expected() -> dict[str, str]:
    return {"pip": "26.1.1", "websockets": "16.0"}


def test_site_closure_digest_is_inode_independent(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    first = tmp_path / "first/site-packages"
    second = tmp_path / "second/site-packages"
    create_web_site(first)
    create_web_site(second)

    monkeypatch.setattr(bootstrap, "_site_roots", lambda: (first,))
    first_record, _, first_identity = bootstrap.inspect_site_environment(web_expected())
    monkeypatch.setattr(bootstrap, "_site_roots", lambda: (second,))
    second_record, _, second_identity = bootstrap.inspect_site_environment(web_expected())

    assert first_record["installed_distributions"] == second_record["installed_distributions"]
    assert first_record["site_packages"]["closure"] == second_record["site_packages"]["closure"]
    assert first_identity != second_identity


def test_site_closure_digest_normalizes_relocated_record_console_scripts(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prefixes = (tmp_path / "short", tmp_path / "a-much-longer-virtualenv-prefix")
    records: list[dict[str, object]] = []
    record_payloads: list[bytes] = []
    for prefix in prefixes:
        site_root = prefix / "lib/python3.14/site-packages"
        create_web_site(site_root)
        add_websockets_console_script_claim(prefix, site_root, "print('stable')\n")
        record_payloads.append(
            (site_root / "websockets-16.0.dist-info/RECORD").read_bytes()
        )
        monkeypatch.setattr(sys, "prefix", str(prefix))
        monkeypatch.setattr(sys, "executable", str(prefix / "bin/python"))
        monkeypatch.setattr(bootstrap, "_site_roots", lambda root=site_root: (root,))
        environment, _, _ = bootstrap.inspect_site_environment(web_expected())
        records.append(environment["site_packages"]["closure"])

    assert record_payloads[0] != record_payloads[1]
    assert records[0] == records[1]


def test_source_pinned_site_closure_rejects_resigned_external_console_script(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prefix = tmp_path / "web-quality-venv"
    site_root = prefix / "lib/python3.14/site-packages"
    create_web_site(site_root)
    add_websockets_console_script_claim(prefix, site_root, "print('stable')\n")
    monkeypatch.setattr(sys, "prefix", str(prefix))
    monkeypatch.setattr(sys, "executable", str(prefix / "bin/python"))
    monkeypatch.setattr(bootstrap, "_site_roots", lambda: (site_root,))
    before, _, _ = bootstrap.inspect_site_environment(web_expected())
    expected = {
        "format": before["site_packages"]["format"],
        "closure": before["site_packages"]["closure"],
    }

    record = site_root / "websockets-16.0.dist-info/RECORD"
    retained = [
        line
        for line in record.read_text(encoding="utf-8").splitlines()
        if not line.startswith("../../../bin/websockets,")
    ]
    tampered = f"#!{prefix}/bin/python\nprint('tampered')\n".encode("utf-8")
    write(prefix / "bin/websockets", tampered)
    encoded = base64.urlsafe_b64encode(hashlib.sha256(tampered).digest()).rstrip(b"=").decode()
    record.write_text(
        f"../../../bin/websockets,sha256={encoded},{len(tampered)}\n"
        + "\n".join(retained)
        + "\n",
        encoding="utf-8",
    )

    resigned, _, _ = bootstrap.inspect_site_environment(web_expected())
    with pytest.raises(bootstrap.IsolatedPythonError, match="source-pinned policy"):
        bootstrap.require_expected_site_packages(resigned, expected)


def test_external_console_normalization_does_not_erase_prefix_bytes_from_body(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prefix = tmp_path / "web-quality-venv"
    site_root = prefix / "lib/python3.14/site-packages"
    create_web_site(site_root)
    add_websockets_console_script_claim(
        prefix,
        site_root,
        "VALUE = '/__walksafe_venv__'\n",
    )
    monkeypatch.setattr(sys, "prefix", str(prefix))
    monkeypatch.setattr(sys, "executable", str(prefix / "bin/python"))
    monkeypatch.setattr(bootstrap, "_site_roots", lambda: (site_root,))
    before, _, _ = bootstrap.inspect_site_environment(web_expected())

    record = site_root / "websockets-16.0.dist-info/RECORD"
    retained = [
        line
        for line in record.read_text(encoding="utf-8").splitlines()
        if not line.startswith("../../../bin/websockets,")
    ]
    tampered = f"#!{prefix}/bin/python\nVALUE = {str(prefix)!r}\n".encode("utf-8")
    write(prefix / "bin/websockets", tampered)
    encoded = base64.urlsafe_b64encode(hashlib.sha256(tampered).digest()).rstrip(b"=").decode()
    record.write_text(
        f"../../../bin/websockets,sha256={encoded},{len(tampered)}\n"
        + "\n".join(retained)
        + "\n",
        encoding="utf-8",
    )

    resigned, _, _ = bootstrap.inspect_site_environment(web_expected())
    assert resigned["site_packages"]["closure"] != before["site_packages"]["closure"]


def test_external_console_claim_rejects_literal_normalization_marker(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prefix = tmp_path / "web-quality-venv"
    site_root = prefix / "lib/python3.14/site-packages"
    create_web_site(site_root)
    script = b"#!/__walksafe_venv__/bin/python\nprint('stable')\n"
    write(prefix / "bin/websockets", script)
    encoded = base64.urlsafe_b64encode(hashlib.sha256(script).digest()).rstrip(b"=").decode()
    record = site_root / "websockets-16.0.dist-info/RECORD"
    record.write_text(
        f"../../../bin/websockets,sha256={encoded},{len(script)}\n"
        + record.read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    monkeypatch.setattr(sys, "prefix", str(prefix))
    monkeypatch.setattr(sys, "executable", str(prefix / "bin/python"))
    monkeypatch.setattr(bootstrap, "_site_roots", lambda: (site_root,))

    with pytest.raises(bootstrap.IsolatedPythonError, match="exact Python launcher shebang"):
        bootstrap.inspect_site_environment(web_expected())


@pytest.mark.parametrize("directory_name", ["attacker_namespace", "__pycache__"])
def test_site_closure_rejects_unowned_empty_directory(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    directory_name: str,
) -> None:
    site_root = tmp_path / "site-packages"
    create_web_site(site_root)
    (site_root / directory_name).mkdir()
    monkeypatch.setattr(bootstrap, "_site_roots", lambda: (site_root,))

    with pytest.raises(bootstrap.IsolatedPythonError, match="unowned directory"):
        bootstrap.inspect_site_environment(web_expected())


@pytest.mark.parametrize("relative", [None, "websockets"])
def test_site_closure_digest_binds_root_and_directory_modes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    relative: str | None,
) -> None:
    site_root = tmp_path / "site-packages"
    create_web_site(site_root)
    monkeypatch.setattr(bootstrap, "_site_roots", lambda: (site_root,))
    before, _, before_identity = bootstrap.inspect_site_environment(web_expected())

    target = site_root if relative is None else site_root / relative
    target.chmod(0o700)
    after, _, after_identity = bootstrap.inspect_site_environment(web_expected())

    assert before["site_packages"]["closure"] != after["site_packages"]["closure"]
    assert before_identity == after_identity


@pytest.mark.parametrize("attack", ["loose", "bytecode", "symlink", "record-tamper"])
def test_site_closure_rejects_unowned_special_or_record_tampered_files(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    attack: str,
) -> None:
    site_root = tmp_path / "site-packages"
    create_web_site(site_root)
    if attack == "loose":
        write(site_root / "websockets_shadow.py", "ATTACK = True\n")
    elif attack == "bytecode":
        write(site_root / "websockets/evil.pyc", b"executable-bytecode")
    elif attack == "symlink":
        (site_root / "websockets/link.py").symlink_to(site_root / "websockets/__init__.py")
    else:
        write(site_root / "websockets/__init__.py", "TAMPERED = True\n")
    monkeypatch.setattr(bootstrap, "_site_roots", lambda: (site_root,))

    with pytest.raises(
        bootstrap.IsolatedPythonError,
        match="unowned|bytecode|symlink|differs from distribution RECORD",
    ):
        bootstrap.inspect_site_environment(web_expected())


def test_source_pinned_site_closure_rejects_resigned_record(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    site_root = tmp_path / "site-packages"
    create_web_site(site_root)
    monkeypatch.setattr(bootstrap, "_site_roots", lambda: (site_root,))
    before, _, _ = bootstrap.inspect_site_environment(web_expected())
    expected = {
        "format": before["site_packages"]["format"],
        "closure": before["site_packages"]["closure"],
    }

    module = site_root / "websockets/__init__.py"
    tampered = b"TAMPERED = True\n"
    module.write_bytes(tampered)
    encoded = base64.urlsafe_b64encode(hashlib.sha256(tampered).digest()).rstrip(b"=").decode()
    record = site_root / "websockets-16.0.dist-info/RECORD"
    lines = record.read_text(encoding="utf-8").splitlines()
    lines = [
        f"websockets/__init__.py,sha256={encoded},{len(tampered)}"
        if line.startswith("websockets/__init__.py,")
        else line
        for line in lines
    ]
    record.write_text("\n".join(lines) + "\n", encoding="utf-8")

    resigned, _, _ = bootstrap.inspect_site_environment(web_expected())
    with pytest.raises(bootstrap.IsolatedPythonError, match="source-pinned policy"):
        bootstrap.require_expected_site_packages(resigned, expected)


def test_site_closure_allows_absent_unhashed_generated_bytecode_record_entry(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    site_root = tmp_path / "site-packages"
    create_web_site(site_root)
    record = site_root / "pip-26.1.1.dist-info/RECORD"
    record.write_text(
        "pip/__pycache__/__init__.cpython-314.pyc,,\n"
        + record.read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    monkeypatch.setattr(bootstrap, "_site_roots", lambda: (site_root,))

    environment, _, _ = bootstrap.inspect_site_environment(web_expected())

    assert environment["site_packages"]["format"] == "walksafe.record-claimed-site-closure.v2"


def test_isolated_bootstrap_blocks_startup_hooks_and_imports_locked_tool(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    quality_python = required_quality_cpython_314()
    virtualenv = tmp_path / "web-quality-venv"
    subprocess.run(
        [str(quality_python), "-m", "venv", "--without-pip", str(virtualenv)],
        check=True,
        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
    )
    python = virtualenv / "bin/python"
    site_root = Path(
        subprocess.run(
            [str(python), "-I", "-S", "-B", "-c", "import sysconfig; print(sysconfig.get_path('purelib'))"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    )
    pth_marker = tmp_path / "pth-executed"
    venv_site_marker = tmp_path / "venv-sitecustomize-executed"
    ambient_marker = tmp_path / "ambient-sitecustomize-executed"
    hooks = {
        "startup-hook.pth": (
            f"import pathlib; pathlib.Path({str(pth_marker)!r}).write_text('executed')\n"
        ),
        "sitecustomize.py": (
            f"from pathlib import Path\nPath({str(venv_site_marker)!r}).write_text('executed')\n"
        ),
    }
    create_web_site(site_root, hooks=hooks)
    monkeypatch.setattr(bootstrap, "_site_roots", lambda: (site_root,))
    site_environment, _, _ = bootstrap.inspect_site_environment(web_expected())
    tested_site_packages = {
        "format": site_environment["site_packages"]["format"],
        "closure": site_environment["site_packages"]["closure"],
    }
    repository = tmp_path / "source"
    (repository / ".git").mkdir(parents=True)
    policy = json.loads(
        (ROOT / "configs/walksafe_product_quality_policy_20260713.json").read_text(
            encoding="utf-8"
        )
    )
    policy["products"]["web"]["tested_site_packages"] = tested_site_packages
    policy_bytes = (json.dumps(policy, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    write(repository / "configs/walksafe_product_quality_policy_20260713.json", policy_bytes)
    write(
        repository / "apps/web/quality-requirements.lock",
        (ROOT / "apps/web/quality-requirements.lock").read_bytes(),
    )
    bootstrap_source = BOOTSTRAP_PATH.read_text(encoding="utf-8").replace(
        bootstrap.POLICY_SHA256,
        hashlib.sha256(policy_bytes).hexdigest(),
        1,
    )
    write(repository / "scripts/run_walksafe_isolated_python_20260713.py", bootstrap_source)
    for name in ("check_pwa_browser_lifecycle_20260711.py", "check_pwa_server_e2e.py"):
        write(repository / "scripts" / name, (ROOT / "scripts" / name).read_bytes())
    ambient = tmp_path / "ambient-pythonpath"
    write(
        ambient / "sitecustomize.py",
        f"from pathlib import Path\nPath({str(ambient_marker)!r}).write_text('executed')\n",
    )

    completed = subprocess.run(
        [
            str(python),
            "-I",
            "-S",
            "-B",
            str(repository / "scripts/run_walksafe_isolated_python_20260713.py"),
            "--repo-root",
            str(repository),
            "--product",
            "web",
            "--",
            "scripts/check_pwa_browser_lifecycle_20260711.py",
            "--help",
        ],
        cwd=ROOT,
        env={**os.environ, "PYTHONPATH": str(ambient), "PYTHONDONTWRITEBYTECODE": "1"},
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 0, completed.stderr
    assert "Browser-level PWA" in completed.stdout
    assert not pth_marker.exists()
    assert not venv_site_marker.exists()
    assert not ambient_marker.exists()


@pytest.mark.parametrize(
    "cached_source",
    ["walksafe_release_integrity.py", "run_walksafe_isolated_python_20260713.py"],
)
def test_quality_runner_ignores_matching_header_local_bytecode(
    tmp_path: Path,
    cached_source: str,
) -> None:
    scripts = copy_release_scripts(
        tmp_path,
        [
            "run_walksafe_product_quality_20260713.py",
            "run_walksafe_isolated_python_20260713.py",
            "check_walksafe_node_toolchain_20260715.py",
            "walksafe_release_integrity.py",
        ],
    )
    marker = tmp_path / "cached-module-executed"
    payload = f"open({str(marker)!r}, 'w').write('executed')\n"
    if cached_source == "walksafe_release_integrity.py":
        payload += """
FileSnapshot = object
ReleaseIntegrityError = RuntimeError
exclusive_atomic_publish = lambda *args, **kwargs: None
exclusive_directory_publish = lambda *args, **kwargs: None
exact_directory_record = lambda *args, **kwargs: None
require_isolated_python = lambda *args, **kwargs: None
strict_json_snapshot = lambda *args, **kwargs: None
verify_exact_git_source = lambda *args, **kwargs: None
"""
    write_matching_timestamp_pyc(scripts / cached_source, payload)

    completed = subprocess.run(
        [
            sys.executable,
            "-I",
            "-S",
            "-B",
            str(scripts / "run_walksafe_product_quality_20260713.py"),
            "--help",
        ],
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 0, completed.stderr
    assert not marker.exists()


@pytest.mark.parametrize(
    ("entrypoint", "dependencies"),
    [
        (
            "run_walksafe_product_quality_20260713.py",
            [
                "run_walksafe_isolated_python_20260713.py",
                "check_walksafe_node_toolchain_20260715.py",
            ],
        ),
        ("walksafe_backup_integrity.py", []),
        (
            "check_walksafe_release_evidence_20260711.py",
            [
                "verify_walksafe_signed_android_release_20260713.py",
                "verify_walksafe_operator_attestation_20260713.py",
            ],
        ),
        ("build_walksafe_full_rc_20260713.py", ["walksafe_android_dex_binding.py"]),
        ("validate_walksafe_full_rc_20260713.py", ["walksafe_android_dex_binding.py"]),
        ("verify_walksafe_operator_attestation_20260713.py", []),
        (
            "verify_walksafe_signed_android_release_20260713.py",
            ["verify_walksafe_operator_attestation_20260713.py"],
        ),
    ],
)
def test_release_entrypoint_rejects_dirty_helper_source_before_exec(
    tmp_path: Path,
    entrypoint: str,
    dependencies: list[str],
) -> None:
    scripts = copy_release_scripts(
        tmp_path,
        [entrypoint, "walksafe_release_integrity.py", *dependencies],
    )
    marker = tmp_path / "dirty-helper-executed"
    helper = scripts / "walksafe_release_integrity.py"
    helper.write_text(
        helper.read_text(encoding="utf-8")
        + f"\nopen({str(marker)!r}, 'w').write('source-executed')\n",
        encoding="utf-8",
    )
    write_matching_timestamp_pyc(
        helper,
        f"open({str(marker)!r}, 'w').write('pyc-executed')\n",
    )

    completed = subprocess.run(
        [sys.executable, "-I", "-S", "-B", str(scripts / entrypoint), "--help"],
        capture_output=True,
        text=True,
    )

    if entrypoint in {
        "build_walksafe_full_rc_20260713.py",
        "validate_walksafe_full_rc_20260713.py",
    }:
        assert completed.returncode == 78
        assert "LEGACY_REFERENCE_ONLY" in completed.stderr
    else:
        assert completed.returncode != 0
        assert "hash differs from its pin" in completed.stderr
    assert not marker.exists()


@pytest.mark.parametrize(
    "entrypoint",
    ["build_walksafe_full_rc_20260713.py", "validate_walksafe_full_rc_20260713.py"],
)
def test_full_rc_entrypoint_rejects_dirty_dex_binding_source_before_exec(
    tmp_path: Path,
    entrypoint: str,
) -> None:
    scripts = copy_release_scripts(
        tmp_path,
        [entrypoint, "walksafe_release_integrity.py", "walksafe_android_dex_binding.py"],
    )
    marker = tmp_path / "dirty-dex-binding-executed"
    helper = scripts / "walksafe_android_dex_binding.py"
    helper.write_text(
        helper.read_text(encoding="utf-8")
        + f"\nopen({str(marker)!r}, 'w').write('source-executed')\n",
        encoding="utf-8",
    )
    write_matching_timestamp_pyc(
        helper,
        f"open({str(marker)!r}, 'w').write('pyc-executed')\n",
    )

    completed = subprocess.run(
        [sys.executable, "-I", "-S", "-B", str(scripts / entrypoint), "--help"],
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 78
    assert "LEGACY_REFERENCE_ONLY" in completed.stderr
    assert not marker.exists()


def test_quality_runner_rejects_dirty_bootstrap_source_before_exec(tmp_path: Path) -> None:
    scripts = copy_release_scripts(
        tmp_path,
        [
            "run_walksafe_product_quality_20260713.py",
            "run_walksafe_isolated_python_20260713.py",
            "check_walksafe_node_toolchain_20260715.py",
            "walksafe_release_integrity.py",
        ],
    )
    marker = tmp_path / "dirty-bootstrap-executed"
    target = scripts / "run_walksafe_isolated_python_20260713.py"
    target.write_text(
        target.read_text(encoding="utf-8")
        + f"\nopen({str(marker)!r}, 'w').write('executed')\n",
        encoding="utf-8",
    )

    completed = subprocess.run(
        [
            sys.executable,
            "-I",
            "-S",
            "-B",
            str(scripts / "run_walksafe_product_quality_20260713.py"),
            "--help",
        ],
        capture_output=True,
        text=True,
    )

    assert completed.returncode != 0
    assert "hash differs from its pin" in completed.stderr
    assert not marker.exists()


def test_signed_gate_rejects_dirty_operator_source_before_exec(tmp_path: Path) -> None:
    scripts = copy_release_scripts(
        tmp_path,
        [
            "verify_walksafe_signed_android_release_20260713.py",
            "verify_walksafe_operator_attestation_20260713.py",
            "walksafe_release_integrity.py",
        ],
    )
    marker = tmp_path / "dirty-operator-executed"
    target = scripts / "verify_walksafe_operator_attestation_20260713.py"
    target.write_text(
        target.read_text(encoding="utf-8")
        + f"\nopen({str(marker)!r}, 'w').write('executed')\n",
        encoding="utf-8",
    )

    completed = subprocess.run(
        [
            sys.executable,
            "-I",
            "-S",
            "-B",
            str(scripts / "verify_walksafe_signed_android_release_20260713.py"),
            "--help",
        ],
        capture_output=True,
        text=True,
    )

    assert completed.returncode != 0
    assert "hash differs from its pin" in completed.stderr
    assert not marker.exists()


def test_release_evidence_rejects_dirty_signed_gate_source_before_exec(tmp_path: Path) -> None:
    scripts = copy_release_scripts(
        tmp_path,
        [
            "check_walksafe_release_evidence_20260711.py",
            "verify_walksafe_signed_android_release_20260713.py",
            "verify_walksafe_operator_attestation_20260713.py",
            "walksafe_release_integrity.py",
        ],
    )
    marker = tmp_path / "dirty-signed-gate-executed"
    target = scripts / "verify_walksafe_signed_android_release_20260713.py"
    target.write_text(
        target.read_text(encoding="utf-8")
        + f"\nopen({str(marker)!r}, 'w').write('executed')\n",
        encoding="utf-8",
    )

    completed = subprocess.run(
        [
            sys.executable,
            "-I",
            "-S",
            "-B",
            str(scripts / "check_walksafe_release_evidence_20260711.py"),
            "--help",
        ],
        capture_output=True,
        text=True,
    )

    assert completed.returncode != 0
    assert "hash differs from its pin" in completed.stderr
    assert not marker.exists()


def test_signed_gate_ignores_matching_header_operator_bytecode(tmp_path: Path) -> None:
    scripts = copy_release_scripts(
        tmp_path,
        [
            "verify_walksafe_signed_android_release_20260713.py",
            "verify_walksafe_operator_attestation_20260713.py",
            "walksafe_release_integrity.py",
        ],
    )
    marker = tmp_path / "cached-operator-executed"
    write_matching_timestamp_pyc(
        scripts / "verify_walksafe_operator_attestation_20260713.py",
        f"open({str(marker)!r}, 'w').write('executed')\n"
        "class AttestationVerificationError(RuntimeError):\n    pass\n"
        "verify_operator_attestation = lambda *args, **kwargs: None\n",
    )

    completed = subprocess.run(
        [
            sys.executable,
            "-I",
            "-S",
            "-B",
            str(scripts / "verify_walksafe_signed_android_release_20260713.py"),
            "--help",
        ],
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 0, completed.stderr
    assert not marker.exists()


def test_release_helper_import_cannot_be_shadowed_by_loose_stdlib_source(
    tmp_path: Path,
) -> None:
    scripts = copy_release_scripts(
        tmp_path,
        [
            "run_walksafe_product_quality_20260713.py",
            "run_walksafe_isolated_python_20260713.py",
            "check_walksafe_node_toolchain_20260715.py",
            "walksafe_release_integrity.py",
        ],
    )
    marker = tmp_path / "loose-ctypes-executed"
    write(
        scripts / "ctypes.py",
        f"open({str(marker)!r}, 'w').write('executed')\n",
    )

    completed = subprocess.run(
        [
            sys.executable,
            "-I",
            "-S",
            "-B",
            str(scripts / "run_walksafe_product_quality_20260713.py"),
            "--help",
        ],
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 0, completed.stderr
    assert not marker.exists()


def test_bootstrap_executes_direct_script_as_source_not_pyc_payload(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    repo = tmp_path / "repo"
    target = repo / "scripts/target.py"
    marker = tmp_path / "direct-pyc-executed"
    write(target, "pass\n")
    write_matching_timestamp_pyc(
        target,
        f"open({str(marker)!r}, 'w').write('executed')\n",
    )
    cached = Path(importlib.util.cache_from_source(str(target)))
    target.write_bytes(cached.read_bytes())
    shutil.rmtree(cached.parent)
    monkeypatch.setattr(sys, "path", list(sys.path))

    with pytest.raises(SyntaxError):
        bootstrap._dispatch(repo, [str(target)], ())

    assert not marker.exists()


def test_bootstrap_rejects_repository_bytecode_before_dispatch(tmp_path: Path) -> None:
    repo = tmp_path / "repo"
    (repo / ".git").mkdir(parents=True)
    write(repo / "scripts/__pycache__/injected.pyc", b"bytecode")

    with pytest.raises(bootstrap.IsolatedPythonError, match="executable Python bytecode"):
        bootstrap._repository_root(repo)


def test_web_builder_ignores_bash_env_and_rejects_ignored_paths(tmp_path: Path) -> None:
    repo = tmp_path / "repo"
    scripts = copy_release_scripts(
        repo,
        [
            "build_walksafe_web_release_20260711.sh",
            "run_walksafe_isolated_python_20260713.py",
            "check_walksafe_node_toolchain_20260715.py",
        ],
    )
    write(
        repo / "configs/walksafe_node_toolchain_lock_20260715.json",
        (ROOT / "configs/walksafe_node_toolchain_lock_20260715.json").read_bytes(),
    )
    node_bin = tmp_path / "node/bin"
    for name in ("node", "npm"):
        write(node_bin / name, "#!/bin/sh\nexit 0\n")
        (node_bin / name).chmod(0o755)
    write(repo / ".gitignore", "apps/web/injected.cache\n")
    write(repo / "apps/web/injected.cache", "ignored\n")
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.name", "WalkSafe test"], check=True)
    subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
    subprocess.run(["git", "-C", str(repo), "commit", "-qm", "fixture"], check=True)
    marker = tmp_path / "bash-env-executed"
    bash_env = tmp_path / "bash-env"
    write(bash_env, f"printf executed > {marker}\n")
    attacker_bin = tmp_path / "attacker-bin"
    write(
        attacker_bin / "dirname",
        f"#!/bin/sh\nprintf executed > {marker}\nexec /usr/bin/dirname \"$@\"\n",
    )
    (attacker_bin / "dirname").chmod(0o755)

    completed = subprocess.run(
        [str(scripts / "build_walksafe_web_release_20260711.sh"), str(tmp_path / "output.json")],
        cwd=repo,
        env={
            **os.environ,
            "BASH_ENV": str(bash_env),
            "GIT_DIR": str(tmp_path / "wrong.git"),
            "PATH": str(attacker_bin),
            "PYTHON_BIN": sys.executable,
            "WALKSAFE_NODE_BIN_DIR": str(node_bin),
        },
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 78, completed.stderr
    assert "LEGACY_REFERENCE_ONLY" in completed.stderr
    assert not marker.exists()


def test_web_builder_rejects_dirty_bootstrap_before_exec(tmp_path: Path) -> None:
    scripts = copy_release_scripts(
        tmp_path,
        ["build_walksafe_web_release_20260711.sh", "run_walksafe_isolated_python_20260713.py"],
    )
    marker = tmp_path / "web-dirty-bootstrap-executed"
    bootstrap_source = scripts / "run_walksafe_isolated_python_20260713.py"
    bootstrap_source.write_text(
        bootstrap_source.read_text(encoding="utf-8")
        + f"\nopen({str(marker)!r}, 'w').write('executed')\n",
        encoding="utf-8",
    )
    node_bin = tmp_path / "node/bin"
    for name in ("node", "npm"):
        write(node_bin / name, "#!/bin/sh\nexit 0\n")
        (node_bin / name).chmod(0o755)

    completed = subprocess.run(
        [str(scripts / "build_walksafe_web_release_20260711.sh"), str(tmp_path / "output.json")],
        env={
            **os.environ,
            "PYTHON_BIN": sys.executable,
            "WALKSAFE_NODE_BIN_DIR": str(node_bin),
        },
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 78, completed.stderr
    assert "LEGACY_REFERENCE_ONLY" in completed.stderr
    assert not marker.exists()


def test_runbook_quality_guard_rejects_system_python_and_cleans_fresh_venv(
    tmp_path: Path,
) -> None:
    quality_python = required_quality_cpython_314()
    runbook = RUNBOOK_PATH.read_text(encoding="utf-8")
    start = runbook.index("quality_python_guard() {")
    end = runbook.index("\n}\n\nquality_pythons=", start) + len("\n}\n")
    guard = "set -euo pipefail\n" + runbook[start:end]
    base_python = Path(
        subprocess.run(
            [
                str(quality_python),
                "-I",
                "-S",
                "-B",
                "-c",
                "import sys; print(sys._base_executable)",
            ],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    )

    rejected = subprocess.run(
        [
            "/bin/bash",
            "-c",
            guard + f"\nquality_python_guard {shlex.quote(str(base_python))} check\n",
        ],
        capture_output=True,
        text=True,
    )

    assert rejected.returncode != 0
    assert "non-venv" in rejected.stderr

    virtualenv = tmp_path / "quality-venv"
    subprocess.run(
        [str(quality_python), "-m", "venv", "--without-pip", str(virtualenv)],
        check=True,
        env={
            key: value
            for key, value in os.environ.items()
            if key != "PYTHONDONTWRITEBYTECODE"
        },
    )
    python = virtualenv / "bin/python"
    site_root = Path(
        subprocess.run(
            [
                str(python),
                "-I",
                "-S",
                "-B",
                "-c",
                "import sysconfig; print(sysconfig.get_path('purelib'))",
            ],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    )
    cache = site_root / "__pycache__"
    write(cache / "generated.pyc", b"generated")
    command = (
        guard
        + f"\nquality_python_guard {shlex.quote(str(python))} check\n"
        + f"quality_python_guard {shlex.quote(str(python))} clean "
        + f"{shlex.quote(str(virtualenv))}\n"
    )

    cleaned = subprocess.run(
        ["/bin/bash", "-c", command],
        capture_output=True,
        text=True,
    )

    assert cleaned.returncode == 0, cleaned.stderr
    assert not cache.exists()
    assert not list(site_root.rglob("__pycache__"))
    assert not list(site_root.rglob("*.py[co]"))
    assert "quality venv prefix is reused" in runbook
    assert 'chmod -R go-rwx -- "${quality_prefix}"' in runbook

    inspect_code = f"""
import importlib.metadata
import sys
import sysconfig
import types
from pathlib import Path
path = Path({str(BOOTSTRAP_PATH)!r})
module = types.ModuleType("runbook_bootstrap_check")
module.__file__ = str(path)
sys.modules[module.__name__] = module
exec(compile(path.read_bytes(), str(path), "exec", dont_inherit=True), module.__dict__)
roots = list(dict.fromkeys(sysconfig.get_path(name) for name in ("purelib", "platlib")))
expected = {{
    module._normalize_distribution_name(item.metadata.get("Name")): item.version
    for item in importlib.metadata.distributions(path=roots)
}}
record, _, _ = module.inspect_site_environment(expected)
print(record["site_packages"]["format"])
"""
    inspected = subprocess.run(
        [str(python), "-I", "-S", "-B", "-c", inspect_code],
        capture_output=True,
        text=True,
    )

    assert inspected.returncode == 0, inspected.stderr
    assert inspected.stdout.strip() == "walksafe.record-claimed-site-closure.v2"


@pytest.mark.parametrize(
    "script",
    [
        "run_walksafe_isolated_python_20260713.py",
        "run_walksafe_product_quality_20260713.py",
        "walksafe_backup_integrity.py",
        "check_walksafe_release_evidence_20260711.py",
        "build_walksafe_full_rc_20260713.py",
        "validate_walksafe_full_rc_20260713.py",
        "verify_walksafe_operator_attestation_20260713.py",
        "verify_walksafe_signed_android_release_20260713.py",
    ],
)
def test_release_cli_requires_isolated_site_disabled_python(script: str) -> None:
    path = ROOT / "scripts" / script
    unsafe = subprocess.run(
        [sys.executable, str(path), "--help"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
    )
    isolated = subprocess.run(
        [sys.executable, "-I", "-S", "-B", str(path), "--help"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )

    assert unsafe.returncode != 0
    assert "requires Python -I -S -B" in unsafe.stderr
    if script in {
        "build_walksafe_full_rc_20260713.py",
        "validate_walksafe_full_rc_20260713.py",
    }:
        assert isolated.returncode == 78
        assert "LEGACY_REFERENCE_ONLY" in isolated.stderr
    else:
        assert isolated.returncode == 0, isolated.stderr


def test_release_evidence_rejects_dirty_dependency_before_import(tmp_path: Path) -> None:
    repository = tmp_path / "source"
    scripts = copy_release_scripts(
        repository,
        [
            "check_walksafe_release_evidence_20260711.py",
            "verify_walksafe_signed_android_release_20260713.py",
            "verify_walksafe_operator_attestation_20260713.py",
            "walksafe_release_integrity.py",
            "walksafe_android_dex_binding.py",
        ],
    )
    dependency = scripts / "walksafe_environment_identity.py"
    dependency.write_text("VALUE = 'clean'\n", encoding="utf-8")
    subprocess.run(["/usr/bin/git", "init", "-q", str(repository)], check=True)
    subprocess.run(
        ["/usr/bin/git", "-C", str(repository), "config", "user.name", "Release Test"],
        check=True,
    )
    subprocess.run(
        ["/usr/bin/git", "-C", str(repository), "config", "user.email", "release@example.invalid"],
        check=True,
    )
    subprocess.run(["/usr/bin/git", "-C", str(repository), "add", "."], check=True)
    subprocess.run(
        ["/usr/bin/git", "-C", str(repository), "commit", "-qm", "fixture"],
        check=True,
    )
    commit = subprocess.run(
        ["/usr/bin/git", "-C", str(repository), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    evidence = tmp_path / "release-evidence.json"
    evidence.write_text(
        json.dumps(
            {
                "schema_version": "walksafe.release_evidence.v1",
                "release_id": "release-test",
                "operator": "human-reviewer",
                "source_commit": commit,
                "checks": {},
            }
        ),
        encoding="utf-8",
    )
    marker = tmp_path / "dirty-dependency-executed"
    dependency.write_text(
        f"from pathlib import Path\nPath({str(marker)!r}).write_text('executed')\n",
        encoding="utf-8",
    )

    completed = subprocess.run(
        [
            sys.executable,
            "-I",
            "-S",
            "-B",
            str(scripts / "check_walksafe_release_evidence_20260711.py"),
            "--evidence",
            str(evidence),
        ],
        cwd=repository,
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 78
    assert "LEGACY_REFERENCE_ONLY" in completed.stderr
    assert not marker.exists()


def test_web_artifact_builder_uses_the_isolated_bootstrap_for_all_python_steps() -> None:
    script = (ROOT / "scripts/build_walksafe_web_release_20260711.sh").read_text(
        encoding="utf-8"
    )

    assert '"${PYTHON_BIN}" -I -S -B "${PYTHON_BOOTSTRAP}"' in script
    assert "run_receipt runtime-trace run_web_python" in script
    assert "run_receipt browser-lifecycle run_web_python" in script
    assert 'run_web_python "${SCRIPT_DIR}/create_walksafe_web_build_manifest_20260711.py"' in script
