import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import time

import pytest


RUNNER = Path(__file__).parents[1] / "scripts" / "run_cloudflare_field_test_services_20260711.sh"
REMOTE_BROWSER = Path(__file__).parents[1] / "scripts" / "check_walksafe_remote_field_browser_20260711.py"
WEB_LOCK_RUNNER = Path(__file__).parents[1] / "scripts" / "run_walksafe_web_single_instance_20260713.py"

LOCK_HELPER = """
import importlib.util
import os
from pathlib import Path
import sys

runner = Path(sys.argv[1])
command = sys.argv[2:]
spec = importlib.util.spec_from_file_location("walksafe_web_lock", runner)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.acquire_process_lock()
os.execv(command[0], command)
"""


def web_lock_command(*command: str) -> list[str]:
    return [sys.executable, "-c", LOCK_HELPER, str(WEB_LOCK_RUNNER), *command]


def _runner_embedded_python(containing: str) -> str:
    script = RUNNER.read_text(encoding="utf-8")
    for chunk in script.split("<<'PY'\n")[1:]:
        code, separator, _remainder = chunk.partition("\nPY\n")
        if separator and containing in code:
            return code
    raise AssertionError(f"embedded runner Python was not found: {containing}")


def test_secured_backend_readiness_checks_use_field_service_token() -> None:
    source = RUNNER.read_text(encoding="utf-8")

    assert 'export WALKSAFE_ACTOR_RATE_LIMIT_STORE=postgresql' in source
    assert "WALKSAFE_BACKEND_PROCESS_LOCK_PATH" not in source
    assert (
        'wait_http "http://127.0.0.1:8000/ready" 90 \\\n'
        '  "x-walksafe-field-test-token: ${WALKSAFE_FIELD_TEST_TOKEN}"'
    ) in source
    assert (
        '--header "x-walksafe-field-test-token: ${WALKSAFE_FIELD_TEST_TOKEN}" \\\n'
        '  "http://127.0.0.1:8000/ready" >"${RUN_DIR}/backend-readiness.json"'
    ) in source
    assert 'curl --silent --show-error --fail "http://127.0.0.1:8000/ready"' not in source


def test_voice_runner_uses_dedicated_token_and_single_process_limits() -> None:
    source = RUNNER.read_text(encoding="utf-8")

    assert "require_value VOICE_SERVICE_TOKEN" in source
    assert 'export VOICE_SERVICE_WORKERS=1' in source
    assert 'export VOICE_SERVICE_REPLICAS=1' in source
    assert 'export VOICE_SERVICE_PROCESS_LOCK_PATH="${RUN_DIR}/voice-process.lock"' in source
    assert '--workers "${VOICE_SERVICE_WORKERS}"' in source
    assert "command -v ffprobe >/dev/null" in source
    assert (
        'wait_http "http://127.0.0.1:9001/ready" 3 \\\n'
        '  "x-walksafe-voice-service-token: ${VOICE_SERVICE_TOKEN}" 300'
    ) in source
    assert 'wait_http "http://127.0.0.1:9001/ready" 3 ""' not in source


def test_web_runner_uses_single_process_lock_and_trusted_edge_ip() -> None:
    source = RUNNER.read_text(encoding="utf-8")

    assert 'export WALKSAFE_WEB_REPLICAS=1' in source
    assert 'export WALKSAFE_WEB_PROCESS_LOCK_PATH="${RUN_DIR}/web-process.lock"' in source
    assert '--hostname 127.0.0.1 --port 3000' in source
    assert '--header "${WALKSAFE_GATEWAY_TRUSTED_IP_HEADER}: 127.0.0.1"' in source


def test_field_launcher_is_closed_before_service_configuration(tmp_path: Path) -> None:
    environment = {
        **os.environ,
        "WEB_ENV_FILE": str(tmp_path / "missing-web.env"),
        "BACKEND_ENV_FILE": str(tmp_path / "missing-backend.env"),
    }

    completed = subprocess.run(
        ["bash", str(RUNNER)],
        env=environment,
        capture_output=True,
        text=True,
        timeout=5,
        check=False,
    )

    assert completed.returncode == 78
    assert "LEGACY_REFERENCE_ONLY" in completed.stderr
    assert "Starting PostGIS" not in completed.stdout


def test_runner_uses_a_trusted_runtime_maintenance_lock_parent() -> None:
    source = RUNNER.read_text(encoding="utf-8")

    assert 'MAINTENANCE_LOCK_PATH="${WALKSAFE_MAINTENANCE_LOCK_PATH:-}"' in source
    assert 'EXPECTED_XDG_RUNTIME_DIR="/run/user/$(id -u)"' in source
    assert 'MAINTENANCE_LOCK_PATH="${XDG_RUNTIME_DIR}/walksafe.lock"' in source
    assert '[[ "$(id -u)" != "0" ]]' in source
    assert '"${lock_parent_uid}" == "$(id -u)" && "${lock_parent_mode}" == "700"' in source
    assert 'authority_fds.append(os.open("/", flags))' in source
    assert 'for component in parent.parent.relative_to("/").parts:' in source
    assert "opened_authority.st_uid != 0" in source
    assert "stat.S_IMODE(opened_authority.st_mode) & 0o022" in source
    assert 'os.access(\n                ".",' in source
    assert "dir_fd=authority_fd" in source
    assert 'exec {MAINTENANCE_LOCK_PARENT_FD}<"${MAINTENANCE_LOCK_DIR}"' in source
    assert 'parent_fd = int(sys.argv[2])' in source
    assert 'MAINTENANCE_LOCK_PARENT_ANCHOR="/proc/self/fd/${MAINTENANCE_LOCK_PARENT_FD}"' in source
    assert ': > "${MAINTENANCE_LOCK_ANCHORED_PATH}"' in source
    assert 'stat -c \'%d:%i\' "${MAINTENANCE_LOCK_ANCHORED_PATH}"' in source
    assert '"${RUN_DIR}/gateway-rate-limits" "${MAINTENANCE_LOCK_DIR}"' not in source


def test_runner_lock_authority_rejects_user_owned_higher_ancestor(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service_directory = tmp_path / "service"
    trusted_immediate_ancestor = service_directory / "trusted"
    lock_parent = trusted_immediate_ancestor / "maintenance"
    lock_parent.mkdir(parents=True, mode=0o700)
    service_directory.chmod(0o700)
    trusted_immediate_ancestor.chmod(0o755)
    higher_ancestor_identity = (service_directory.stat().st_dev, service_directory.stat().st_ino)
    authority_paths = [Path("/")]
    for component in trusted_immediate_ancestor.relative_to("/").parts:
        authority_paths.append(authority_paths[-1] / component)
    simulated_safe_paths = set(authority_paths) - {service_directory}
    simulated_safe_identities = {
        (path.stat().st_dev, path.stat().st_ino) for path in simulated_safe_paths
    }
    visited_identities: list[tuple[int, int]] = []
    real_fstat = os.fstat
    real_access = os.access

    def only_higher_ancestor_remains_user_owned(descriptor: int) -> os.stat_result:
        metadata = real_fstat(descriptor)
        identity = (metadata.st_dev, metadata.st_ino)
        visited_identities.append(identity)
        if identity not in simulated_safe_identities:
            return metadata
        fields = list(metadata)
        fields[0] = (fields[0] & ~0o7777) | 0o755
        fields[4] = 0
        return os.stat_result(fields)

    def simulated_safe_ancestors_appear_non_writable(
        path: str,
        mode: int,
        *,
        dir_fd: int | None = None,
        effective_ids: bool = False,
        follow_symlinks: bool = True,
    ) -> bool:
        if dir_fd is not None:
            metadata = real_fstat(dir_fd)
            if (metadata.st_dev, metadata.st_ino) in simulated_safe_identities:
                return False
        if dir_fd is None and Path(path) in simulated_safe_paths:
            return False
        return real_access(
            path,
            mode,
            dir_fd=dir_fd,
            effective_ids=effective_ids,
            follow_symlinks=follow_symlinks,
        )

    parent_fd = os.open(lock_parent, os.O_RDONLY | os.O_DIRECTORY)
    monkeypatch.setattr(os, "fstat", only_higher_ancestor_remains_user_owned)
    monkeypatch.setattr(os, "access", simulated_safe_ancestors_appear_non_writable)
    monkeypatch.setattr(sys, "argv", ["embedded-runner", str(lock_parent), str(parent_fd)])
    try:
        with pytest.raises(SystemExit, match="authority ancestors"):
            exec(
                compile(_runner_embedded_python("authority_fds = []"), "<runner>", "exec"),
                {},
            )
        assert higher_ancestor_identity in visited_identities
    finally:
        os.close(parent_fd)


def test_runner_lock_authority_accepts_standard_user_runtime(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime_parent = Path(f"/run/user/{os.geteuid()}")
    if (
        os.geteuid() == 0
        or not runtime_parent.is_dir()
        or runtime_parent.stat().st_uid != os.geteuid()
        or runtime_parent.stat().st_mode & 0o777 != 0o700
    ):
        pytest.skip("requires the standard non-root /run/user/$UID runtime directory")
    parent_fd = os.open(runtime_parent, os.O_RDONLY | os.O_DIRECTORY)
    monkeypatch.setattr(sys, "argv", ["embedded-runner", str(runtime_parent), str(parent_fd)])
    try:
        exec(compile(_runner_embedded_python("authority_fds = []"), "<runner>", "exec"), {})
    finally:
        os.close(parent_fd)


def test_runner_lock_authority_rejects_mismatched_parent_descriptor(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime_parent = Path(f"/run/user/{os.geteuid()}")
    if (
        os.geteuid() == 0
        or not runtime_parent.is_dir()
        or runtime_parent.stat().st_uid != os.geteuid()
        or runtime_parent.stat().st_mode & 0o777 != 0o700
    ):
        pytest.skip("requires the standard non-root /run/user/$UID runtime directory")
    other_parent = runtime_parent / f"walksafe-runner-parent-test-{os.getpid()}"
    if other_parent.exists() or other_parent.is_symlink():
        pytest.skip("runtime parent test path is already occupied")
    other_parent.mkdir(mode=0o700)
    parent_fd = os.open(other_parent, os.O_RDONLY | os.O_DIRECTORY)
    monkeypatch.setattr(sys, "argv", ["embedded-runner", str(runtime_parent), str(parent_fd)])
    try:
        with pytest.raises(SystemExit, match="ancestry changed"):
            exec(
                compile(_runner_embedded_python("authority_fds = []"), "<runner>", "exec"),
                {},
            )
    finally:
        os.close(parent_fd)
        other_parent.rmdir()


def test_web_process_lock_rejects_a_real_second_process(tmp_path: Path) -> None:
    private_runtime = tmp_path / "web-runtime"
    private_runtime.mkdir(mode=0o700)
    lock_path = private_runtime / "web.lock"
    ready_path = private_runtime / "ready"
    environment = {
        **os.environ,
        "WALKSAFE_ENVIRONMENT": "production",
        "WALKSAFE_WEB_REPLICAS": "1",
        "WALKSAFE_WEB_PROCESS_LOCK_PATH": str(lock_path.resolve()),
    }
    hold_command = [
        sys.executable,
        "-c",
        "from pathlib import Path; import sys, time; Path(sys.argv[1]).write_text('ready'); time.sleep(30)",
        str(ready_path),
    ]
    first = subprocess.Popen(
        web_lock_command(*hold_command),
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        deadline = time.monotonic() + 5
        while not ready_path.exists() and first.poll() is None and time.monotonic() < deadline:
            time.sleep(0.02)
        assert ready_path.exists(), first.stderr.read() if first.poll() is not None and first.stderr else "lock holder did not start"

        second = subprocess.run(
            web_lock_command(sys.executable, "-c", "raise SystemExit(0)"),
            env=environment,
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        assert second.returncode == 2
        assert "already holds" in second.stderr
    finally:
        first.terminate()
        first.wait(timeout=5)

    restarted = subprocess.run(
        web_lock_command(sys.executable, "-c", "raise SystemExit(0)"),
        env=environment,
        capture_output=True,
        text=True,
        timeout=5,
        check=False,
    )
    assert restarted.returncode == 0, restarted.stderr


def test_web_process_lock_rejects_replaced_lock_file(tmp_path: Path) -> None:
    private_runtime = tmp_path / "web-runtime"
    private_runtime.mkdir(mode=0o700)
    lock_path = private_runtime / "web.lock"
    ready_path = private_runtime / "ready"
    environment = {
        **os.environ,
        "WALKSAFE_ENVIRONMENT": "production",
        "WALKSAFE_WEB_REPLICAS": "1",
        "WALKSAFE_WEB_PROCESS_LOCK_PATH": str(lock_path.resolve()),
    }
    first = subprocess.Popen(
        web_lock_command(
            sys.executable,
            "-c",
            "from pathlib import Path; import sys, time; Path(sys.argv[1]).write_text('ready'); time.sleep(30)",
            str(ready_path),
        ),
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        deadline = time.monotonic() + 5
        while not ready_path.exists() and first.poll() is None and time.monotonic() < deadline:
            time.sleep(0.02)
        assert ready_path.exists(), first.stderr.read() if first.poll() is not None and first.stderr else "lock holder did not start"
        lock_path.rename(private_runtime / "displaced.lock")

        second = subprocess.run(
            web_lock_command(sys.executable, "-c", "raise SystemExit(0)"),
            env=environment,
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        assert second.returncode == 2
        assert "already holds" in second.stderr
    finally:
        first.terminate()
        first.wait(timeout=5)


def test_npm_start_rejects_argument_override_before_the_production_lock_gate() -> None:
    environment = os.environ.copy()
    for name in (
        "NODE_ENV",
        "WALKSAFE_ENVIRONMENT",
        "WALKSAFE_WEB_REPLICAS",
        "WALKSAFE_WEB_PROCESS_LOCK_PATH",
    ):
        environment.pop(name, None)
    completed = subprocess.run(
        ["npm", "run", "start", "--", "--help"],
        cwd=Path(__file__).parents[1] / "apps" / "web",
        env=environment,
        capture_output=True,
        text=True,
        timeout=10,
        check=False,
    )

    assert completed.returncode == 2
    assert "exact loopback" in completed.stderr


def test_web_runner_rejects_arbitrary_nonproduction_command() -> None:
    environment = os.environ.copy()
    environment["WALKSAFE_ENVIRONMENT"] = "test"
    for name in ("NODE_ENV", "WALKSAFE_WEB_START_MODE", "WALKSAFE_WEB_REPLICAS", "WALKSAFE_WEB_PROCESS_LOCK_PATH"):
        environment.pop(name, None)
    completed = subprocess.run(
        [sys.executable, str(WEB_LOCK_RUNNER), "--", sys.executable, "-c", "raise SystemExit(0)"],
        env=environment,
        capture_output=True,
        text=True,
        timeout=5,
        check=False,
    )

    assert completed.returncode == 2
    assert "exact loopback" in completed.stderr


@pytest.mark.parametrize("replicas", ["", "2"])
def test_web_process_lock_rejects_unbounded_replica_configuration(tmp_path: Path, replicas: str) -> None:
    private_runtime = tmp_path / "web-runtime"
    private_runtime.mkdir(mode=0o700)
    environment = {
        **os.environ,
        "WALKSAFE_ENVIRONMENT": "staging",
        "WALKSAFE_WEB_REPLICAS": replicas,
        "WALKSAFE_WEB_PROCESS_LOCK_PATH": str((private_runtime / "web.lock").resolve()),
    }
    completed = subprocess.run(
        web_lock_command(sys.executable, "-c", "raise SystemExit(0)"),
        env=environment,
        capture_output=True,
        text=True,
        timeout=5,
        check=False,
    )

    assert completed.returncode == 2
    assert "must be exactly 1" in completed.stderr


@pytest.mark.parametrize("unsafe_kind", ["relative", "public-parent"])
def test_web_process_lock_rejects_unsafe_paths(tmp_path: Path, unsafe_kind: str) -> None:
    runtime = tmp_path / "runtime"
    runtime.mkdir(mode=0o700)
    lock_path = Path("relative-web.lock")
    if unsafe_kind == "public-parent":
        runtime.chmod(0o755)
        lock_path = runtime / "web.lock"
    environment = {
        **os.environ,
        "WALKSAFE_ENVIRONMENT": "field",
        "WALKSAFE_WEB_REPLICAS": "1",
        "WALKSAFE_WEB_PROCESS_LOCK_PATH": str(lock_path),
    }
    completed = subprocess.run(
        web_lock_command(sys.executable, "-c", "raise SystemExit(0)"),
        env=environment,
        capture_output=True,
        text=True,
        timeout=5,
        check=False,
    )

    assert completed.returncode == 2
    assert "must be" in completed.stderr


def test_remote_browser_uses_named_account_not_backend_service_token() -> None:
    source = REMOTE_BROWSER.read_text(encoding="utf-8")

    assert 'os.environ.get("WALKSAFE_FIELD_TEST_TOKEN")' not in source
    assert 'read_dotenv_value(dotenv, "WALKSAFE_FIELD_TEST_TOKEN")' not in source
    assert 'actor_id: {json.dumps(field_actor_id)}' in source
    assert 'token: {json.dumps(field_account_token)}' in source


def test_remote_browser_consents_before_camera_and_reads_versioned_session_json() -> None:
    source = REMOTE_BROWSER.read_text(encoding="utf-8")

    assert source.index("processing_consent = await evaluate(") < source.index("click_result = await evaluate(")
    assert "전송 항목 확인·서버 탐지 동의" in source
    assert "telemetrySession: sessionStorage.getItem('walksafe-field-session-id')" in source
    assert 'state.pop("telemetrySession", None)' in source


@pytest.mark.parametrize(
    ("stored", "expected_actor", "message"),
    [
        ({"actor_id": "tester.lee", "session_id": "web-2026-test"}, "tester.kim", "does not match"),
        ({"actor_id": "tester.kim", "session_id": "../unsafe"}, "tester.kim", "session_id is invalid"),
        ("web-legacy-session", "tester.kim", "must be an object"),
    ],
)
def test_remote_browser_rejects_unbound_or_legacy_telemetry_sessions(
    monkeypatch: pytest.MonkeyPatch,
    stored: object,
    expected_actor: str,
    message: str,
) -> None:
    monkeypatch.syspath_prepend(str(REMOTE_BROWSER.parent))
    namespace = runpy.run_path(str(REMOTE_BROWSER), run_name="walksafe_remote_browser_policy")
    parser = namespace["parse_telemetry_session"]
    check_failed = namespace["CheckFailed"]

    with pytest.raises(check_failed, match=message):
        parser(json.dumps(stored), expected_actor)


def test_remote_browser_extracts_actor_bound_telemetry_session(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.syspath_prepend(str(REMOTE_BROWSER.parent))
    namespace = runpy.run_path(str(REMOTE_BROWSER), run_name="walksafe_remote_browser_policy")
    parser = namespace["parse_telemetry_session"]

    assert parser(
        json.dumps({"actor_id": "tester.kim", "session_id": "web-2026-07-16-session_1"}),
        "tester.kim",
    ) == ("tester.kim", "web-2026-07-16-session_1")
