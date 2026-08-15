from __future__ import annotations

import argparse
import asyncio
import base64
import errno
import gzip
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import stat
import subprocess
import sys
import tarfile
import time
from typing import Any
import zipfile
import zlib

import pytest


ROOT = Path(__file__).resolve().parents[1]


def load_script(name: str):
    path = ROOT / "scripts" / name
    spec = importlib.util.spec_from_file_location(path.stem, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


builder = load_script("build_walksafe_full_rc_20260713.py")
validator = load_script("validate_walksafe_full_rc_20260713.py")
signing_gate = load_script("verify_walksafe_signed_android_release_20260713.py")


def load_quality_runner():
    path = ROOT / "scripts/run_walksafe_product_quality_20260713.py"
    module_name = "walksafe_full_rc_quality_runner_under_test"
    spec = importlib.util.spec_from_file_location(module_name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


quality_runner = load_quality_runner()


def test_trusted_proxy_tls_success_context_is_ca_verified_and_hostname_checked(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    trusted_proxy = load_script("check_walksafe_trusted_proxy_20260716.py")
    certificate = tmp_path / "certificate.pem"
    certificate.write_text("test CA\n", encoding="ascii")
    expected_context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context_arguments: dict[str, object] = {}
    connection_arguments: dict[str, object] = {}

    def create_default_context(**kwargs):
        context_arguments.update(kwargs)
        return expected_context

    def https_connection(host, port, *, timeout, context):
        connection_arguments.update(
            {"host": host, "port": port, "timeout": timeout, "context": context}
        )
        return object()

    monkeypatch.setattr(trusted_proxy.ssl, "create_default_context", create_default_context)
    monkeypatch.setattr(trusted_proxy.http.client, "HTTPSConnection", https_connection)

    context = trusted_proxy._trusted_tls_context(certificate)
    connection = trusted_proxy._trusted_https_connection(
        edge_port=443,
        tls_context=context,
    )

    assert connection is not None
    assert context.verify_mode == ssl.CERT_REQUIRED
    assert context.check_hostname is True
    assert context_arguments == {
        "purpose": ssl.Purpose.SERVER_AUTH,
        "cafile": str(certificate),
    }
    assert connection_arguments == {
        "host": "127.0.0.1",
        "port": 443,
        "timeout": 5,
        "context": context,
    }


def test_trusted_proxy_tls_negative_context_is_rejected_before_network(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    trusted_proxy = load_script("check_walksafe_trusted_proxy_20260716.py")
    negative_context = trusted_proxy._negative_test_tls_context()
    connection_attempts = 0

    def unexpected_connection(*_args, **_kwargs):
        nonlocal connection_attempts
        connection_attempts += 1
        return object()

    monkeypatch.setattr(
        trusted_proxy.http.client,
        "HTTPSConnection",
        unexpected_connection,
    )

    assert negative_context.verify_mode == ssl.CERT_NONE
    assert negative_context.check_hostname is False
    with pytest.raises(
        trusted_proxy.ProxyCheckError,
        match="requires CA verification and hostname checking",
    ):
        trusted_proxy._trusted_https_connection(
            edge_port=443,
            tls_context=negative_context,
        )
    with pytest.raises(
        trusted_proxy.ProxyCheckError,
        match="requires CA verification and hostname checking",
    ):
        trusted_proxy._request_with_declared_body(
            edge_port=443,
            tls_context=negative_context,
            path="/negative-test",
            content_length=0,
        )
    assert connection_attempts == 0


def test_pwa_browser_lifecycle_bounds_unresponsive_cdp(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.syspath_prepend(str(ROOT / "scripts"))
    browser_lifecycle = load_script("check_pwa_browser_lifecycle_20260711.py")

    first_lock = browser_lifecycle.acquire_lifecycle_lock()
    try:
        with pytest.raises(
            browser_lifecycle.CheckFailed,
            match="another PWA browser lifecycle check is already running",
        ):
            browser_lifecycle.acquire_lifecycle_lock()
    finally:
        first_lock.close()
    browser_lifecycle.acquire_lifecycle_lock().close()

    first_credentials = browser_lifecycle.new_lifecycle_credentials()
    second_credentials = browser_lifecycle.new_lifecycle_credentials()
    assert len(set(first_credentials)) == 4
    assert all(first != second for first, second in zip(first_credentials, second_credentials))
    monkeypatch.setattr(browser_lifecycle, "CDP_RESPONSE_TIMEOUT_SECONDS", 0.01)

    class NeverResponds:
        receive_cancelled = False

        async def send(self, _payload: str) -> None:
            return None

        async def recv(self) -> str:
            try:
                await asyncio.Future()
            except asyncio.CancelledError:
                self.receive_cancelled = True
                raise
            raise AssertionError("unreachable")

    websocket = NeverResponds()
    with pytest.raises(browser_lifecycle.CheckFailed, match="CDP Runtime.evaluate.*0.01s"):
        asyncio.run(
            browser_lifecycle.cdp_call(
                websocket,
                "Runtime.evaluate",
                {"expression": "new Promise(() => {})", "awaitPromise": True},
                message_id=1,
            )
        )
    assert websocket.receive_cancelled is True


def test_pwa_privacy_poll_rejects_state_returned_after_deadline(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.syspath_prepend(str(ROOT / "scripts"))
    browser_lifecycle = load_script("check_pwa_browser_lifecycle_20260711.py")
    monkeypatch.setattr(browser_lifecycle, "CDP_RESPONSE_TIMEOUT_SECONDS", 1.0)

    class DelayedResponse:
        message_id = 0
        receive_cancelled = False

        async def send(self, payload: str) -> None:
            self.message_id = json.loads(payload)["id"]

        async def recv(self) -> str:
            try:
                await asyncio.sleep(0.05)
            except asyncio.CancelledError:
                self.receive_cancelled = True
                raise
            return json.dumps(
                {"id": self.message_id, "result": {"result": {"value": {"eventual": True}}}}
            )

    websocket = DelayedResponse()
    with pytest.raises(browser_lifecycle.CheckFailed, match="CDP Runtime.evaluate"):
        asyncio.run(
            browser_lifecycle.wait_for_privacy_state(
                websocket,
                message_id=1,
                timeout_seconds=0.01,
                predicate=lambda state: state.get("eventual") is True,
                description="a timely privacy withdrawal",
            )
        )
    assert websocket.receive_cancelled is True


def test_pwa_non_metric_advisory_log_requires_bounded_contract(tmp_path: Path) -> None:
    browser_lifecycle = load_script("check_pwa_browser_lifecycle_20260711.py")
    log_dir = tmp_path / "logs" / "2026-07-16"
    log_dir.mkdir(parents=True)
    payload = {
        "non_metric_advisory_active": True,
        "non_metric_advisory_tier": "CAMERA_NON_METRIC_ADVISORY",
        "non_metric_advisory_direction": "front",
        "non_metric_advisory_message": browser_lifecycle.EXPECTED_ADVISORY_MESSAGE,
        "non_metric_advisory_consecutive_frames": 3,
        "non_metric_advisory_stable_ms": 700,
        "non_metric_advisory_metric": False,
        "non_metric_advisory_tmap_authoritative": True,
        "non_metric_advisory_reports_allowed": False,
        "navigation_active": True,
        "risk_active": False,
    }
    path = log_dir / "session.jsonl"
    path.write_text(json.dumps({"event_type": "heartbeat", "payload": payload}) + "\n", encoding="utf-8")
    evidence = browser_lifecycle.non_metric_advisory_log_evidence(tmp_path / "logs")
    assert evidence is not None and evidence["consecutive_frames"] == 3

    payload["non_metric_advisory_reports_allowed"] = True
    path.write_text(json.dumps({"event_type": "heartbeat", "payload": payload}) + "\n", encoding="utf-8")
    assert browser_lifecycle.non_metric_advisory_log_evidence(tmp_path / "logs") is None


def test_pwa_browser_lifecycle_rejects_stale_listener_and_cdp_target(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monkeypatch.syspath_prepend(str(ROOT / "scripts"))
    browser_lifecycle = load_script("check_pwa_browser_lifecycle_20260711.py")
    real_run_browser_check = browser_lifecycle.run_browser_check

    fake_root = tmp_path / "source"
    fake_web_root = fake_root / "apps/web"
    (fake_web_root / ".next").mkdir(parents=True)
    (fake_web_root / ".next/BUILD_ID").write_text("fixture-build\n", encoding="utf-8")
    (fake_web_root / "tsconfig.json").write_text("{}\n", encoding="utf-8")
    (fake_web_root / "next-env.d.ts").write_text("fixture\n", encoding="utf-8")
    monkeypatch.setattr(browser_lifecycle, "REPO_ROOT", fake_root)

    class FakeProcess:
        next_pid = 50000

        def __init__(self, label: str) -> None:
            self.pid = FakeProcess.next_pid
            FakeProcess.next_pid += 1
            self.label = label
            self.returncode: int | None = None

        def poll(self) -> int | None:
            return self.returncode

    start_calls: list[dict[str, Any]] = []
    observed_credentials: list[tuple[str, str, str, str, str]] = []
    cleanup_events: list[tuple[str, str | int]] = []
    active_evidence_path: Path | None = None

    def fake_start_process(
        command: list[str],
        *,
        cwd: Path,
        env: dict[str, str],
        log_path: Path,
    ) -> FakeProcess:
        start_calls.append({"command": command, "cwd": cwd, "env": env, "log": log_path})
        label = "chrome" if command[0] == "/fake/chromium" else "web"
        return FakeProcess(label)

    def fake_stop_process(process: FakeProcess | None) -> None:
        if process is not None:
            cleanup_events.append(("stop", process.label))
            process.returncode = 0

    def fake_wait_for_loopback_port_release(port: int, **_kwargs: Any) -> None:
        cleanup_events.append(("release", port))
        if port in unused_ports and active_evidence_path is not None:
            assert not active_evidence_path.exists()

    async def fake_run_browser_check(
        args: argparse.Namespace,
        web_url: str,
        web_process: FakeProcess,
        chrome_instance_url: str,
        field_actor_id: str,
        field_account_token: str,
        field_log_dir: Path,
    ) -> dict[str, Any]:
        del args, web_url
        web_process.returncode = 0
        web_env = start_calls[-2]["env"]
        account = json.loads(web_env["WALKSAFE_FIELD_ACCOUNTS_JSON"])[0]
        observed_credentials.append(
            (
                account["actor_id"],
                account["token"],
                web_env["WALKSAFE_FIELD_TEST_TOKEN"],
                web_env["WALKSAFE_GATEWAY_SESSION_SECRET"],
                chrome_instance_url,
            )
        )
        assert field_actor_id == account["actor_id"]
        assert field_account_token == account["token"]
        assert web_env["WALKSAFE_FIELD_LOG_DIR"] == str(field_log_dir)
        return {"fixture": True}

    monkeypatch.setattr(browser_lifecycle, "start_process", fake_start_process)
    monkeypatch.setattr(browser_lifecycle, "stop_process", fake_stop_process)
    monkeypatch.setattr(browser_lifecycle, "wait_for_http", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(
        browser_lifecycle,
        "wait_for_loopback_port_release",
        fake_wait_for_loopback_port_release,
    )
    monkeypatch.setattr(browser_lifecycle, "chrome_binary", lambda _path: "/fake/chromium")
    monkeypatch.setattr(browser_lifecycle, "run_browser_check", fake_run_browser_check)

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        occupied_port = listener.getsockname()[1]

        monkeypatch.setattr(
            browser_lifecycle,
            "parse_args",
            lambda: argparse.Namespace(
                browser_timeout=1.0,
                web_port=occupied_port,
                chrome_debug_port=occupied_port + 1,
                chrome_bin=None,
                evidence_out=None,
                existing_build_dir=".next",
                keep_open=False,
            ),
        )
        with pytest.raises(
            browser_lifecycle.CheckFailed,
            match=rf"PWA web loopback port {occupied_port} is already in use before launch",
        ):
            browser_lifecycle.main()

        with pytest.raises(
            browser_lifecycle.CheckFailed,
            match=rf"Chromium debug loopback port {occupied_port} is already in use before launch",
        ):
            browser_lifecycle.require_isolated_loopback_ports(
                {"Chromium debug": occupied_port}
            )

    unused_ports: list[int] = []
    while len(unused_ports) < 2:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as reservation:
            reservation.bind(("127.0.0.1", 0))
            candidate = reservation.getsockname()[1]
        if candidate not in unused_ports:
            unused_ports.append(candidate)
    evidence_path = tmp_path / "forbidden-keep-open-evidence.json"
    monkeypatch.setattr(
        browser_lifecycle,
        "parse_args",
        lambda: argparse.Namespace(
            browser_timeout=1.0,
            web_port=unused_ports[0],
            chrome_debug_port=unused_ports[1],
            chrome_bin=None,
            evidence_out=evidence_path,
            existing_build_dir=".next",
            keep_open=True,
        ),
    )
    with pytest.raises(
        browser_lifecycle.CheckFailed,
        match="--keep-open is incompatible with browser lifecycle evidence",
    ):
        browser_lifecycle.main()
    assert not evidence_path.exists()

    success_evidence_paths = [
        tmp_path / "success-evidence-1.json",
        tmp_path / "success-evidence-2.json",
    ]

    def success_args() -> argparse.Namespace:
        nonlocal active_evidence_path
        active_evidence_path = success_evidence_paths[len(observed_credentials)]
        return argparse.Namespace(
            browser_timeout=1.0,
            web_port=unused_ports[0],
            chrome_debug_port=unused_ports[1],
            chrome_bin=None,
            evidence_out=active_evidence_path,
            existing_build_dir=".next",
            keep_open=False,
        )

    monkeypatch.setattr(browser_lifecycle, "parse_args", success_args)
    assert browser_lifecycle.main() == 0
    assert browser_lifecycle.main() == 0
    assert all(path.is_file() for path in success_evidence_paths)
    assert cleanup_events == [
        ("stop", "chrome"),
        ("release", unused_ports[1]),
        ("stop", "chrome"),
        ("release", unused_ports[1]),
    ]
    assert len(observed_credentials) == 2
    for credentials in observed_credentials:
        assert len(set(credentials[:4])) == 4
        assert credentials[4].startswith("data:text/html,walksafe-pwa-lifecycle-")
    assert all(
        first != second
        for first, second in zip(observed_credentials[0], observed_credentials[1])
    )

    expected_url = "data:text/html,walksafe-pwa-lifecycle-owned"
    stale_targets = [
        {
            "id": "stale",
            "type": "page",
            "url": "about:blank",
            "webSocketDebuggerUrl": "ws://127.0.0.1:9322/devtools/page/stale",
        }
    ]
    with pytest.raises(
        browser_lifecycle.CheckFailed,
        match="page owned by the launched Chromium instance",
    ):
        browser_lifecycle.select_launched_chrome_page(stale_targets, expected_url, 9322)

    launched_target = {
        "id": "launched",
        "type": "page",
        "url": expected_url,
        "webSocketDebuggerUrl": "ws://127.0.0.1:9322/devtools/page/launched",
    }
    assert (
        browser_lifecycle.select_launched_chrome_page(
            [*stale_targets, launched_target], expected_url, 9322
        )
        is launched_target
    )

    remote_target = {
        **launched_target,
        "webSocketDebuggerUrl": "ws://example.test:9322/devtools/page/launched",
    }
    with pytest.raises(
        browser_lifecycle.CheckFailed,
        match="outside the owned loopback endpoint",
    ):
        browser_lifecycle.select_launched_chrome_page(
            [remote_target], expected_url, 9322
        )

    class FakeWebSocketContext:
        async def __aenter__(self) -> object:
            return object()

        async def __aexit__(self, *_args: object) -> None:
            return None

    async def fake_cdp_call(
        _websocket: object,
        method: str,
        _params: dict[str, Any] | None = None,
        *,
        message_id: int,
        timeout_seconds: float | None = None,
    ) -> dict[str, Any]:
        del message_id, timeout_seconds
        if method == "Page.getAppManifest":
            return {
                "errors": [],
                "data": json.dumps({"display": "standalone", "start_url": "/"}),
            }
        if method == "Page.getInstallabilityErrors":
            return {"installabilityErrors": []}
        return {}

    evaluate_results = iter(
        [
            {"ok": True, "status": 200},
            {"shell": {"ok": True, "status": 200}, "api": {"ok": False}},
            {
                "offlineReloadCommitted": True,
                "readyState": "complete",
                "text": "현장 테스트 인증",
                "nextScripts": 1,
                "nextStyles": 1,
                "hydrated": True,
            },
        ]
    )

    async def fake_evaluate(*_args: Any, **_kwargs: Any) -> Any:
        return next(evaluate_results)

    async def fake_privacy_controls(
        _websocket: object,
        message_id: int,
        _timeout: float,
    ) -> tuple[dict[str, bool], int]:
        return {"fixture": True}, message_id

    async def fake_pwa_ready(
        _websocket: object,
        message_id: int,
        _timeout: float,
    ) -> tuple[dict[str, Any], int]:
        return {
            "controlled": True,
            "version": "fixture",
            "scope": "http://127.0.0.1:31123/",
            "cacheNames": ["fixture"],
        }, message_id

    async def fake_advisory(
        _websocket: object,
        message_id: int,
        _timeout: float,
        field_log_dir: Path,
    ) -> tuple[dict[str, bool], int]:
        assert field_log_dir.name == "field-logs"
        return {"fixture": True}, message_id

    async def fake_update_ui(
        _websocket: object,
        message_id: int,
        _timeout: float,
    ) -> tuple[dict[str, bool], int]:
        return {"fixture": True}, message_id

    owned_target = {
        "id": "owned",
        "type": "page",
        "url": expected_url,
        "webSocketDebuggerUrl": "ws://127.0.0.1:9322/devtools/page/owned",
    }
    cleanup_events.clear()
    monkeypatch.setattr(browser_lifecycle, "http_json", lambda *_args, **_kwargs: [owned_target])
    monkeypatch.setattr(
        browser_lifecycle.websockets,
        "connect",
        lambda *_args, **_kwargs: FakeWebSocketContext(),
    )
    monkeypatch.setattr(browser_lifecycle, "cdp_call", fake_cdp_call)
    monkeypatch.setattr(browser_lifecycle, "evaluate", fake_evaluate)
    monkeypatch.setattr(browser_lifecycle, "privacy_probe_init_script", lambda: "(() => {})();")
    monkeypatch.setattr(
        browser_lifecycle,
        "exercise_server_v2_privacy_controls",
        fake_privacy_controls,
    )
    monkeypatch.setattr(browser_lifecycle, "exercise_non_metric_advisory", fake_advisory)
    monkeypatch.setattr(browser_lifecycle, "wait_for_pwa_ready", fake_pwa_ready)
    monkeypatch.setattr(browser_lifecycle, "exercise_update_ui", fake_update_ui)
    browser_evidence = asyncio.run(
        real_run_browser_check(
            argparse.Namespace(
                browser_timeout=1.0,
                chrome_debug_port=9322,
                web_port=31123,
            ),
            "http://127.0.0.1:31123",
            FakeProcess("web"),
            expected_url,
            "fixture-actor",
            "fixture-token",
            tmp_path / "field-logs",
        )
    )
    assert browser_evidence["offline_shell_available"] is True
    assert browser_evidence["non_metric_advisory"] == {"fixture": True}
    assert browser_evidence["synthetic_service_worker_update_ui_wiring"] == {"fixture": True}
    assert "service_worker_update_waiting_and_apply" not in browser_evidence
    assert any(
        "does not prove release-to-release update" in limit
        for limit in browser_evidence["claim_limits"]
    )
    assert cleanup_events == [("stop", "web"), ("release", 31123)]


def test_stop_process_kills_child_group_after_launcher_exit(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monkeypatch.syspath_prepend(str(ROOT / "scripts"))
    server_e2e = load_script("check_pwa_server_e2e.py")
    browser_lifecycle = load_script("check_pwa_browser_lifecycle_20260711.py")

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]

    launcher = server_e2e.start_process(
        [
            sys.executable,
            "-c",
            (
                "import os, sys\n"
                "if os.fork():\n"
                "    os._exit(0)\n"
                f"os.execv({sys.executable!r}, "
                f"[{sys.executable!r}, '-m', 'http.server', {str(port)!r}, "
                "'--bind', '127.0.0.1'])\n"
            ),
        ],
        cwd=ROOT,
        env=os.environ.copy(),
        log_path=tmp_path / "orphan-listener.log",
    )
    try:
        launcher.wait(timeout=5)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
                probe.settimeout(0.2)
                if probe.connect_ex(("127.0.0.1", port)) == 0:
                    break
            time.sleep(0.05)
        else:
            pytest.fail("orphan listener did not start")

        browser_lifecycle.stop_owned_process(launcher, port, "the orphan listener")

        class BusyProbe:
            def __enter__(self) -> BusyProbe:
                return self

            def __exit__(self, *_args: object) -> None:
                return None

            def settimeout(self, _timeout: float) -> None:
                return None

            def connect_ex(self, _address: tuple[str, int]) -> int:
                return errno.EAGAIN

        with monkeypatch.context() as busy_probe_patch:
            busy_probe_patch.setattr(
                browser_lifecycle.socket,
                "socket",
                lambda *_args, **_kwargs: BusyProbe(),
            )
            with pytest.raises(
                browser_lifecycle.CheckFailed,
                match="remained open after owned process shutdown",
            ):
                browser_lifecycle.wait_for_loopback_port_release(port, timeout=0.01)
    finally:
        try:
            os.killpg(launcher.pid, 9)
        except ProcessLookupError:
            pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path: Path, data: str | bytes = "fixture\n") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(data, bytes):
        path.write_bytes(data)
    else:
        path.write_text(data, encoding="utf-8")


BUILD_CONFIG_DEX_TEMPLATE_COMMIT = b"2178f30f21dcabdbf1e77d4a726d4f4b9e1be98c"
BUILD_CONFIG_DEX_TEMPLATE_DESCRIPTOR = b"Lkr/co/hanium/dreamup/walksafe/BuildConfig;"
BUILD_CONFIG_DEX_TEMPLATE = base64.b64decode(
    "ZGV4CjAzOAAg143NGT3udy3Gj3XKgRczC2SrpoQnksZ0BAAAcAAAAHhWNBIAAAAAAAAAANQDAAAXAAAAcAAAAAYAAADMAAAA"
    "AQAAAOQAAAAIAAAA8AAAAAIAAAAwAQAAAQAAAEABAAAUAwAAYAEAAHwBAACDAQAArQEAALUBAADFAQAA0QEAAOMBAADqAQAA"
    "7QEAAAECAAAVAgAAQgIAAEUCAABTAgAAYQIAAHgCAACRAgAAqQIAAKwCAADOAgAA7QIAAPYCAAALAwAABwAAAAgAAAAJAAAA"
    "CgAAAAsAAAARAAAACwAAAAQAAAAAAAAAAwACAAMAAAADAAIABAAAAAMABQAGAAAAAwAAAAwAAAADAAIADQAAAAMAAgAOAAAA"
    "AwACAA8AAAADAAIAEAAAAAEAAAACAAAAAwAAAAIAAAADAAAAEQAAAAEAAAAAAAAABQAAAAAAAACqAwAAxAMAAAEAAQABAAAA"
    "eAEAAAQAAABwEAAAAAAOAAYADgAFMC4xLjAAKDIxNzhmMzBmMjFkY2FiZGJmMWU3N2Q0YTcyNmQ0ZjRiOWUxYmU5OGMABjxp"
    "bml0PgAOQVBQTElDQVRJT05fSUQACkJVSUxEX1RZUEUAEEJ1aWxkQ29uZmlnLmphdmEABURFQlVHAAFJABJMamF2YS9sYW5n"
    "L09iamVjdDsAEkxqYXZhL2xhbmcvU3RyaW5nOwArTGtyL2NvL2hhbml1bS9kcmVhbXVwL3dhbGtzYWZlL0J1aWxkQ29uZmln"
    "OwABVgAMVkVSU0lPTl9DT0RFAAxWRVJTSU9OX05BTUUAFVdBTEtTQUZFX0JVSUxEX01BUktFUgAXV0FMS1NBRkVfR0FURVdB"
    "WV9PUklHSU4AFldBTEtTQUZFX1NPVVJDRV9DT01NSVQAAVoAIGh0dHBzOi8vd2Fsa3NhZmUuZXhhbXBsZS5pbnZhbGlkAB1r"
    "ci5jby5oYW5pdW0uZHJlYW11cC53YWxrc2FmZQAHcmVsZWFzZQATd2Fsa3NhZmUtcmVsZWFzZS12MQCcAX5+RDh7ImJhY2tl"
    "bmQiOiJkZXgiLCJjb21waWxhdGlvbi1tb2RlIjoiZGVidWciLCJoYXMtY2hlY2tzdW1zIjpmYWxzZSwibWluLWFwaSI6MjYs"
    "InNoYS0xIjoiNzUwYTIxYjRmNDI4MWIxZjQ1M2I2NDllMGI4NGYxYmE5YzA0ZjRmYyIsInZlcnNpb24iOiI5LjAuMy1kZXYi"
    "fQAIAAEAABkBGQEZARkBGQEZARkBGQGBgATgAggXExcUHwQBFwAXFRcSFwENAAAAAAAAAAEAAAAAAAAAAQAAABcAAABwAAAA"
    "AgAAAAYAAADMAAAAAwAAAAEAAADkAAAABAAAAAgAAADwAAAABQAAAAIAAAAwAQAABgAAAAEAAABAAQAAASAAAAEAAABgAQAA"
    "AyAAAAEAAAB4AQAAAiAAABcAAAB8AQAAACAAAAEAAACqAwAABSAAAAEAAADEAwAAABAAAAEAAADUAwAA"
)


def build_config_dex(source_commit: str, *, owns_walksafe_descriptor: bool = True) -> bytes:
    commit = source_commit.encode("ascii")
    assert len(commit) == len(BUILD_CONFIG_DEX_TEMPLATE_COMMIT)
    payload = BUILD_CONFIG_DEX_TEMPLATE.replace(BUILD_CONFIG_DEX_TEMPLATE_COMMIT, commit)
    if not owns_walksafe_descriptor:
        payload = payload.replace(
            BUILD_CONFIG_DEX_TEMPLATE_DESCRIPTOR,
            b"Lkr/co/hanium/dreamup/walksafe/BuildConfjg;",
        )
    mutable = bytearray(payload)
    mutable[12:32] = hashlib.sha1(mutable[32:]).digest()
    mutable[8:12] = (zlib.adler32(mutable[12:]) & 0xFFFFFFFF).to_bytes(4, "little")
    return bytes(mutable)


def git(root: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(root), *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout.strip()


def create_unsigned_apk(path: Path, source_commit: str, source_root: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("classes.dex", build_config_dex(source_commit, owns_walksafe_descriptor=False))
        archive.writestr("classes2.dex", build_config_dex(source_commit))
        archive.writestr("AndroidManifest.xml", b"fixture-manifest")
        config_path = source_root / "apps/android/app/src/main/assets/model-config/two_model_runtime.json"
        config = json.loads(config_path.read_text(encoding="utf-8"))
        archive.write(config_path, "assets/model-config/two_model_runtime.json")
        for model in config["models"].values():
            asset = model["asset"]
            archive.write(source_root / "apps/android/app/src/main/assets" / asset, f"assets/{asset}")


def web_build_variant_files(web_root: str, *, fresh: bool) -> dict[str, bytes]:
    digits = ("4", "5", "6") if fresh else ("1", "2", "3")
    rsc_key = base64.b64encode((b"B" if fresh else b"A") * 32).decode("ascii")
    preview = {
        "version": 4,
        "routes": {},
        "dynamicRoutes": {},
        "notFoundRoutes": [],
        "preview": {
            "previewModeId": digits[0] * 32,
            "previewModeSigningKey": digits[1] * 64,
            "previewModeEncryptionKey": digits[2] * 64,
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
    rsc = {"node": {}, "edge": {}, "encryptionKey": rsc_key}
    server_config = {
        "outputFileTracingRoot": web_root,
        "turbopack": {"root": web_root},
    }
    compact = {"ensure_ascii": False, "separators": (",", ":")}
    return {
        ".next/prerender-manifest.json": json.dumps(preview, **compact).encode(),
        ".next/required-server-files.json": json.dumps(required, **compact).encode(),
        ".next/server/server-reference-manifest.json": json.dumps(rsc, **compact).encode(),
        ".next/server/server-reference-manifest.js": (
            "self.__RSC_SERVER_MANIFEST="
            + json.dumps(json.dumps(rsc, ensure_ascii=False, indent=2), **compact)
        ).encode(),
        "server.js": (
            "const nextConfig = " + json.dumps(server_config, **compact) + "\n"
        ).encode(),
    }


def create_web_release(root: Path, source_commit: str, source_root: Path) -> tuple[Path, Path]:
    root.mkdir(parents=True, exist_ok=True)
    archive_path = root / f"web-standalone-{source_commit}.tar.gz"
    raw = io.BytesIO()
    archive_files = web_build_variant_files(
        "/secure/build/web-artifact/apps/web", fresh=False
    )
    archive_files["BUILD_ID"] = source_commit.encode()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.PAX_FORMAT) as archive:
        for name, content in sorted(archive_files.items()):
            info = tarfile.TarInfo(name)
            info.size = len(content)
            info.mode = 0o644
            info.mtime = 0
            info.uid = info.gid = 0
            archive.addfile(info, io.BytesIO(content))
    with archive_path.open("wb") as destination:
        with gzip.GzipFile(filename="", mode="wb", fileobj=destination, mtime=0) as compressed:
            compressed.write(raw.getvalue())

    package_json = root / "web-provenance" / "package.json"
    package_lock = root / "web-provenance" / "package-lock.json"
    node_toolchain_lock = root / "web-provenance" / "walksafe_node_toolchain_lock_20260715.json"
    package_json.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source_root / "apps/web/package.json", package_json)
    shutil.copyfile(source_root / "apps/web/package-lock.json", package_lock)
    shutil.copyfile(
        source_root / "configs/walksafe_node_toolchain_lock_20260715.json",
        node_toolchain_lock,
    )
    node_lock = json.loads(node_toolchain_lock.read_text(encoding="utf-8"))
    quality_receipts = []
    for name in (
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
    ):
        receipt = root / "web-quality" / f"{name}.log"
        if name in {"node-toolchain", "node-toolchain-post"}:
            write(
                receipt,
                json.dumps(
                    {
                        "schema_version": "walksafe.node-toolchain-attestation.v2",
                        "lock_sha256": sha256(node_toolchain_lock),
                        "official_archive": node_lock["official_archive"],
                        "platform": node_lock["platform"],
                        "root": node_lock["root"],
                        "node": node_lock["node"],
                        "npm": node_lock["npm"],
                    },
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n",
            )
        else:
            write(receipt, f"{name} PASS\n")
        quality_receipts.append((name, receipt))
    artifact = lambda path: {
        "path": path.relative_to(root).as_posix(),
        "sha256": sha256(path),
        "bytes": path.stat().st_size,
    }
    manifest = {
        "schema_version": "walksafe.web-build-manifest.v4",
        "source_commit": source_commit,
        "build_id": source_commit,
        "toolchain": {
            "node": node_lock["node"]["version"],
            "npm": node_lock["npm"]["version"],
        },
        "build_environment": builder.WEB_BUILD_ENVIRONMENT,
        "inputs": {
            "package_json": artifact(package_json),
            "package_lock": artifact(package_lock),
            "node_toolchain_lock": artifact(node_toolchain_lock),
        },
        "quality_receipts": [{"name": name, **artifact(path)} for name, path in quality_receipts],
        "deployment_archive": {
            "name": archive_path.name,
            **artifact(archive_path),
        },
        "files": [
            {"path": path, "sha256": hashlib.sha256(content).hexdigest()}
            for path, content in sorted(archive_files.items())
        ],
    }
    manifest_path = root / "web-build-manifest.json"
    write(manifest_path, json.dumps(manifest, sort_keys=True))
    return manifest_path, archive_path


def real_apksigner() -> Path:
    candidates: list[Path] = []
    for environment_name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        sdk = os.environ.get(environment_name)
        if sdk:
            candidates.extend(sorted((Path(sdk) / "build-tools").glob("*/apksigner"), reverse=True))
    candidates.extend(
        sorted(Path("/home/ddobagi/Android/Sdk/build-tools").glob("*/apksigner"), reverse=True)
    )
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate.resolve()
    pytest.skip("real Android SDK apksigner is unavailable")


def validation_tool_args(apksigner: Path) -> tuple[Path, str, Path, str]:
    java_name = shutil.which("java")
    if java_name is None:
        pytest.skip("real Java executable is unavailable")
    java = Path(java_name).resolve()
    apksigner_jar = (apksigner.parent / "lib" / "apksigner.jar").resolve()
    if not apksigner_jar.is_file():
        pytest.skip("real Android SDK apksigner.jar is unavailable")
    return java, sha256(java), apksigner_jar, sha256(apksigner_jar)


def create_source_repo(root: Path) -> str:
    android_models = {
        "coco_yolo26n_float32.tflite": b"fixture-coco-model",
        "custom_tactile_yolo26s_float32.tflite": b"fixture-custom-model",
        "walksafe_unified_yolo26n_768_float32.tflite": b"fixture-unified-model",
    }
    android_model_config = {
        "models": {
            name: {
                "asset": f"models/{filename}",
                "artifact_sha256": hashlib.sha256(payload).hexdigest(),
            }
            for name, (filename, payload) in zip(
                ("coco_general", "custom_tactile", "unified_walksafe"),
                android_models.items(),
                strict=True,
            )
        }
    }
    def python_lock(*requirements: tuple[str, str]) -> str:
        return "\n".join(
            f"{name}=={version} --hash=sha256:{hashlib.sha256(f'{name}=={version}'.encode()).hexdigest()}"
            for name, version in requirements
        ) + "\n"

    verification_hash = "c" * 64
    verification_metadata = f"""<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
  <components>
    <component group="androidx.core" name="core-ktx" version="1.18.0">
      <artifact name="core-ktx-1.18.0.aar"><sha256 value="{verification_hash}"/></artifact>
    </component>
    <component group="com.android.application" name="com.android.application.gradle.plugin" version="9.1.0">
      <artifact name="com.android.application.gradle.plugin-9.1.0.pom"><sha256 value="{verification_hash}"/></artifact>
    </component>
  </components>
</verification-metadata>
"""
    files: dict[str, str | bytes] = {
        ".gitignore": "apps/android/app/build/\napps/web/.next/\n",
        "backend/__init__.py": "",
        "backend/app/__init__.py": "",
        "backend/app/main.py": "app = object()\n",
        "backend/alembic.ini": "[alembic]\nscript_location = alembic\n",
        "backend/alembic/env.py": "",
        "backend/alembic/versions/0001.py": "revision = '0001'\n",
        "backend/requirements.txt": "fastapi==1.2.3\nuvicorn[standard]==4.5.6\n",
        "backend/requirements.lock": python_lock(("fastapi", "1.2.3"), ("uvicorn", "4.5.6")),
        "backend/.env.example": (
            "DATABASE_URL=postgresql+psycopg://user:CHANGE_ME@db/walksafe"
            "?sslmode=verify-full&gssencmode=disable\n"
        ),
        "model/two_model_runtime.py": "DEFAULT_RUNTIME_CONFIG = {}\n",
        "configs/walksafe_unified_epoch270_field_20260711.json": "{}\n",
        "voice/__init__.py": "",
        "voice/server.py": "app = object()\n",
        "voice/requirements.txt": "fastapi==7.8.9\n",
        "voice/requirements.lock": python_lock(("fastapi", "7.8.9")),
        "voice/quality-requirements.txt": "pytest==8.4.2\n",
        "voice/quality-requirements.lock": python_lock(("pytest", "8.4.2")),
        "apps/web/package.json": '{"name":"walksafe-pwa","version":"0.1.0"}\n',
        "apps/web/package-lock.json": json.dumps(
            {
                "name": "walksafe-pwa",
                "version": "0.1.0",
                "lockfileVersion": 3,
                "packages": {
                    "": {"name": "walksafe-pwa", "version": "0.1.0"},
                    "node_modules/next": {
                        "version": "16.2.6",
                        "integrity": "sha512-" + base64.b64encode(b"n" * 64).decode("ascii"),
                    },
                },
            }
        ),
        "apps/web/.env.example": "BACKEND_API_BASE_URL=http://127.0.0.1:8000\n",
        "apps/android/app/build.gradle.kts": (
            "dependencyLocking {\n"
            "    lockMode.set(LockMode.STRICT)\n"
            "    lockAllConfigurations()\n"
            "}\n"
            'implementation("androidx.core:core-ktx:1.18.0")\n'
        ),
        "apps/android/app/gradle.lockfile": (
            "androidx.core:core-ktx:1.18.0=releaseRuntimeClasspath\n"
        ),
        "apps/android/build.gradle.kts": 'id("com.android.application") version "9.1.0" apply false\n',
        "apps/android/settings.gradle.kts": 'rootProject.name = "WalkSafeAndroid"\n',
        "apps/android/gradle/wrapper/gradle-wrapper.properties": (
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip\n"
            f"distributionSha256Sum={'d' * 64}\n"
        ),
        "apps/android/gradle/wrapper/gradle-wrapper.jar": b"gradle-wrapper",
        "apps/android/gradle/verification-metadata.xml": verification_metadata,
        "apps/android/gradlew": "#!/bin/sh\nexit 0\n",
        "apps/android/app/src/main/assets/model-config/two_model_runtime.json": json.dumps(
            android_model_config,
            sort_keys=True,
        ),
        "deploy/config/walksafe-backend.env.example": (
            ROOT / "deploy/config/walksafe-backend.env.example"
        ).read_bytes(),
        "deploy/config/walksafe-backend-migration.env.example": (
            ROOT / "deploy/config/walksafe-backend-migration.env.example"
        ).read_bytes(),
        "deploy/config/walksafe-report-retention.env.example": (
            ROOT / "deploy/config/walksafe-report-retention.env.example"
        ).read_bytes(),
        "deploy/config/walksafe-voice.env.example": (
            ROOT / "deploy/config/walksafe-voice.env.example"
        ).read_bytes(),
        "deploy/config/walksafe-web.env.example": (
            ROOT / "deploy/config/walksafe-web.env.example"
        ).read_bytes(),
        "deploy/nginx/walksafe-web.conf.example": (
            ROOT / "deploy/nginx/walksafe-web.conf.example"
        ).read_bytes(),
        "deploy/systemd/walksafe-backend.service": (
            ROOT / "deploy/systemd/walksafe-backend.service"
        ).read_bytes(),
        "deploy/systemd/walksafe-backend-migrate.service": (
            ROOT / "deploy/systemd/walksafe-backend-migrate.service"
        ).read_bytes(),
        "deploy/systemd/walksafe-admin-issuer-bind.service": (
            ROOT / "deploy/systemd/walksafe-admin-issuer-bind.service"
        ).read_bytes(),
        "deploy/sysusers.d/walksafe-backend.conf": (
            ROOT / "deploy/sysusers.d/walksafe-backend.conf"
        ).read_bytes(),
        "deploy/systemd/walksafe-report-retention.service": (
            ROOT / "deploy/systemd/walksafe-report-retention.service"
        ).read_bytes(),
        "deploy/systemd/walksafe-report-retention.timer": (
            ROOT / "deploy/systemd/walksafe-report-retention.timer"
        ).read_bytes(),
        "deploy/systemd/walksafe-voice.service": (
            ROOT / "deploy/systemd/walksafe-voice.service"
        ).read_bytes(),
        "deploy/systemd/walksafe-web.service": (
            ROOT / "deploy/systemd/walksafe-web.service"
        ).read_bytes(),
        "contracts/walksafe.openapi.json": "{}\n",
        "contracts/fixtures/walking-route-v1.json": "{}\n",
        "configs/walksafe_product_quality_policy_20260713.json": (
            ROOT / "configs/walksafe_product_quality_policy_20260713.json"
        ).read_bytes(),
        "configs/walksafe_node_toolchain_lock_20260715.json": (
            ROOT / "configs/walksafe_node_toolchain_lock_20260715.json"
        ).read_bytes(),
        "apps/web/quality-requirements.txt": (
            ROOT / "apps/web/quality-requirements.txt"
        ).read_bytes(),
        "apps/web/quality-requirements.lock": (
            ROOT / "apps/web/quality-requirements.lock"
        ).read_bytes(),
        "scripts/run_walksafe_product_quality_20260713.py": (
            ROOT / "scripts/run_walksafe_product_quality_20260713.py"
        ).read_bytes(),
        "scripts/run_walksafe_isolated_python_20260713.py": (
            ROOT / "scripts/run_walksafe_isolated_python_20260713.py"
        ).read_bytes(),
        "scripts/check_walksafe_node_toolchain_20260715.py": (
            ROOT / "scripts/check_walksafe_node_toolchain_20260715.py"
        ).read_bytes(),
        "scripts/check_walksafe_trusted_proxy_20260716.py": (
            ROOT / "scripts/check_walksafe_trusted_proxy_20260716.py"
        ).read_bytes(),
        "scripts/validate_walksafe_full_rc_20260713.py": (
            ROOT / "scripts/validate_walksafe_full_rc_20260713.py"
        ).read_bytes(),
        "scripts/walksafe_release_integrity.py": (
            ROOT / "scripts/walksafe_release_integrity.py"
        ).read_bytes(),
        "scripts/check_report_retention_dry_run.py": (
            ROOT / "scripts/check_report_retention_dry_run.py"
        ).read_bytes(),
        "scripts/run_walksafe_report_retention_20260717.sh": (
            ROOT / "scripts/run_walksafe_report_retention_20260717.sh"
        ).read_bytes(),
        "scripts/walksafe_backup_integrity.py": (
            ROOT / "scripts/walksafe_backup_integrity.py"
        ).read_bytes(),
        "scripts/walksafe_environment_identity.py": (
            ROOT / "scripts/walksafe_environment_identity.py"
        ).read_bytes(),
        "scripts/walksafe_external_check_receipt.py": (
            ROOT / "scripts/walksafe_external_check_receipt.py"
        ).read_bytes(),
    }
    for filename, payload in android_models.items():
        files[f"apps/android/app/src/main/assets/models/{filename}"] = payload
    for relative in builder.RELEASE_SOURCE_INPUTS:
        files.setdefault(relative, "fixture\n")
    for relative, content in files.items():
        write(root / relative, content)
    (root / "apps/android/gradlew").chmod(0o755)
    subprocess.run(["git", "init", "-q", str(root)], check=True)
    git(root, "config", "user.email", "test@example.invalid")
    git(root, "config", "user.name", "WalkSafe test")
    git(root, "add", ".")
    git(root, "commit", "-qm", "fixture")
    return git(root, "rev-parse", "HEAD")


def fixture_quality_receipts(source: Path) -> dict[str, Path]:
    return {
        product: source.parent / "quality" / f"walksafe-{product}-quality-receipt.json"
        for product in builder.QUALITY_PRODUCTS
    }


def fake_node_quality_options(source: Path) -> dict[str, object]:
    node_root = source.parent / "locked-node"
    node_bin = node_root / "bin"
    node_bin.mkdir(parents=True, exist_ok=True)
    for name in ("node", "npm"):
        executable = node_bin / name
        write(executable, "#!/bin/sh\nexit 0\n")
        executable.chmod(0o755)
    lock_path = source / "configs/walksafe_node_toolchain_lock_20260715.json"
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    attestation = {
        "schema_version": "walksafe.node-toolchain-attestation.v2",
        "lock_sha256": hashlib.sha256(lock_path.read_bytes()).hexdigest(),
        "official_archive": lock["official_archive"],
        "platform": lock["platform"],
        "root": lock["root"],
        "node": lock["node"],
        "npm": lock["npm"],
    }

    def attest(node_root_argument, lock_path_argument):
        assert node_root_argument == node_root
        assert lock_path_argument == lock_path
        return attestation

    return {"node_bin_dir": node_bin, "node_toolchain_attestor": attest}


def create_quality_receipts(source: Path, web_archive: Path) -> dict[str, Path]:
    output = source.parent / "quality"
    output.mkdir(parents=True, exist_ok=False)
    receipts = fixture_quality_receipts(source)
    ambient = {
        "PATH": os.environ["PATH"],
        "HOME": os.environ.get("HOME", "/tmp"),
        "LANG": "C.UTF-8",
        "WALKSAFE_TEST_DATABASE_URL": "postgresql+psycopg://fixture.invalid/walksafe",
    }

    source_commit = git(source, "rev-parse", "HEAD")

    def passing_executor(argv, _cwd, _environment):
        if "assembleRelease" in argv:
            create_unsigned_apk(
                source / "apps/android/app/build/outputs/apk/release/app-release-unsigned.apk",
                source_commit,
                source,
            )
        if tuple(argv[-2:]) == ("run", "build"):
            build_root = source / "apps/web/.next"
            (build_root / "standalone").mkdir(parents=True)
            (build_root / "static").mkdir()
            write(build_root / "BUILD_ID", source_commit)
            for relative, payload in web_build_variant_files(
                str((source / "apps" / "web").resolve()), fresh=True
            ).items():
                write(build_root / "standalone" / relative, payload)
        return quality_runner.ExecutionResult(returncode=0)

    installed_distributions = {
        "web": {"pip": "26.1.1", "websockets": "16.0"},
        "backend": {"fastapi": "1.2.3", "pip": "26.1.1", "uvicorn": "4.5.6"},
        "voice": {"fastapi": "7.8.9", "pip": "26.1.1", "pytest": "8.4.2"},
    }

    arguments = {
        "web": {"web_release_archive": str(web_archive)},
        "android": {"android_gateway_origin": "https://walksafe.invalid"},
        "backend": {},
        "voice": {},
    }
    for product in builder.QUALITY_PRODUCTS:
        quality_runner.run_product_quality(
            repo_root=source,
            product=product,
            receipt_path=receipts[product],
            arguments=arguments[product],
            executor=passing_executor,
            ambient_environment=ambient,
            policy_path=source / "configs/walksafe_product_quality_policy_20260713.json",
            expected_policy_sha256=builder.QUALITY_POLICY_SHA256,
            runner_path=source / "scripts/run_walksafe_product_quality_20260713.py",
            installed_distributions=installed_distributions.get(product),
            **(fake_node_quality_options(source) if product == "web" else {}),
        )
        if product == "android":
            staged_apk = source.parent / "quality-inputs" / "app-release-unsigned.apk"
            staged_apk.parent.mkdir()
            shutil.copyfile(
                source / "apps/android/app/build/outputs/apk/release/app-release-unsigned.apk",
                staged_apk,
            )
            shutil.rmtree(source / "apps/android/app/build")
        if product == "web":
            shutil.rmtree(source / "apps/web/.next")
    return receipts


def build_fixture(tmp_path: Path) -> tuple[Path, Path, Path, Path]:
    source = tmp_path / "source"
    source_commit = create_source_repo(source)
    web_root = tmp_path / "web"
    web_manifest, web_archive = create_web_release(web_root, source_commit, source)
    quality_receipts = create_quality_receipts(source, web_archive)
    apk = tmp_path / "quality-inputs" / "app-release-unsigned.apk"
    output = tmp_path / "rc"
    builder.build_full_rc(
        source_root=source,
        web_manifest_path=web_manifest,
        web_artifact_root=web_root,
        web_archive_path=web_archive,
        unsigned_apk_path=apk,
        quality_receipt_paths=quality_receipts,
        output_root=output,
    )
    return source, output, apk, real_apksigner()


def rewrite_manifest_artifact_record(manifest_path: Path, relative: str) -> None:
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    path = manifest_path.parent / relative
    sbom_record = payload.get("sbom")
    if isinstance(sbom_record, dict) and sbom_record.get("path") == relative:
        sbom_record["sha256"] = sha256(path)
        sbom_record["bytes"] = path.stat().st_size
    for component in payload["components"].values():
        records = [
            component.get("artifact"),
            component.get("build_manifest"),
            component.get("runtime_support"),
            *component.get("evidence_files", []),
        ]
        for record in records:
            if isinstance(record, dict) and record.get("path") == relative:
                record["sha256"] = sha256(path)
                record["bytes"] = path.stat().st_size
    for record in payload["files"]:
        if record.get("path") == relative:
            record["sha256"] = sha256(path)
            record["bytes"] = path.stat().st_size
    quality = payload.get("quality", {}).get("products", {})
    for product in quality.values():
        records = [product.get("receipt"), *product.get("evidence_files", [])]
        for record in records:
            if isinstance(record, dict) and record.get("path") == relative:
                record["sha256"] = sha256(path)
                record["bytes"] = path.stat().st_size
    canonical = "".join(
        f"{record['sha256']} {record['bytes']} {record['path']}\n"
        for record in sorted(payload["files"], key=lambda item: item["path"])
    ).encode()
    payload["closure"]["sha256"] = hashlib.sha256(canonical).hexdigest()
    manifest_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


def rewrite_apk(path: Path, replacements: dict[str, bytes], additions: dict[str, bytes] | None = None) -> None:
    temporary = path.with_suffix(".tmp.apk")
    with zipfile.ZipFile(path) as source, zipfile.ZipFile(
        temporary,
        "w",
        compression=zipfile.ZIP_STORED,
    ) as destination:
        for info in source.infolist():
            destination.writestr(info, replacements.get(info.filename, source.read(info)))
        for name, payload in (additions or {}).items():
            destination.writestr(name, payload)
    temporary.replace(path)


def make_tar(path: Path, members: list[tuple[str, bytes, str, int]]) -> None:
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.PAX_FORMAT) as archive:
        for name, content, kind, mtime in members:
            info = tarfile.TarInfo(name)
            info.mtime = mtime
            info.uid = info.gid = 0
            info.mode = 0o644
            if kind == "symlink":
                info.type = tarfile.SYMTYPE
                info.linkname = "target"
                archive.addfile(info)
            else:
                info.size = len(content)
                archive.addfile(info, io.BytesIO(content))
    with path.open("wb") as destination:
        with gzip.GzipFile(filename="", mode="wb", fileobj=destination, mtime=0) as compressed:
            compressed.write(raw.getvalue())


def test_full_rc_is_reproducible_and_independently_validated(tmp_path: Path) -> None:
    source, first, apk, apksigner = build_fixture(tmp_path / "one")
    source_commit = git(source, "rev-parse", "HEAD")
    web_root = tmp_path / "two" / "web"
    web_manifest, web_archive = create_web_release(web_root, source_commit, source)
    second_apk = tmp_path / "two" / "app-release-unsigned.apk"
    shutil.copyfile(apk, second_apk)
    second = tmp_path / "two" / "rc"
    builder.build_full_rc(
        source_root=source,
        web_manifest_path=web_manifest,
        web_artifact_root=web_root,
        web_archive_path=web_archive,
        unsigned_apk_path=second_apk,
        quality_receipt_paths=fixture_quality_receipts(source),
        output_root=second,
    )

    first_files = {path.relative_to(first).as_posix(): sha256(path) for path in first.rglob("*") if path.is_file()}
    second_files = {path.relative_to(second).as_posix(): sha256(path) for path in second.rglob("*") if path.is_file()}
    assert first_files == second_files
    result = validator.validate_full_rc(source, first, *validation_tool_args(apksigner))
    assert result["deployment_complete"] is False
    assert result["android_signing_status"] == "unsigned"


def test_build_refuses_dirty_source_tree(tmp_path: Path) -> None:
    source, output, apk, _apksigner = build_fixture(tmp_path)
    write(source / "untracked.txt")
    manifest = output / "web" / "web-build-manifest.json"
    archive_record = json.loads(manifest.read_text(encoding="utf-8"))["deployment_archive"]
    with pytest.raises(builder.ReleaseBuildError, match="dirty"):
        builder.build_full_rc(
            source_root=source,
            web_manifest_path=manifest,
            web_artifact_root=output / "web",
            web_archive_path=output / "web" / archive_record["path"],
            unsigned_apk_path=apk,
            quality_receipt_paths=fixture_quality_receipts(source),
            output_root=tmp_path / "second-rc",
        )


def test_build_rejects_web_dependency_evidence_that_differs_from_source(tmp_path: Path) -> None:
    source, output, apk, _apksigner = build_fixture(tmp_path)
    web_root = output / "web"
    web_manifest_path = web_root / "web-build-manifest.json"
    web_manifest = json.loads(web_manifest_path.read_text(encoding="utf-8"))
    lock_record = web_manifest["inputs"]["package_lock"]
    preserved_lock = web_root / lock_record["path"]
    write(preserved_lock, '{"lockfileVersion":3,"packages":{}}\n')
    lock_record["sha256"] = sha256(preserved_lock)
    lock_record["bytes"] = preserved_lock.stat().st_size
    write(web_manifest_path, json.dumps(web_manifest, sort_keys=True))

    with pytest.raises(builder.ReleaseBuildError, match="differs from the source HEAD"):
        builder.build_full_rc(
            source_root=source,
            web_manifest_path=web_manifest_path,
            web_artifact_root=web_root,
            web_archive_path=web_root / web_manifest["deployment_archive"]["path"],
            unsigned_apk_path=apk,
            quality_receipt_paths=fixture_quality_receipts(source),
            output_root=tmp_path / "second-rc",
        )


def test_builder_rejects_quality_receipt_with_stale_source_inventory(tmp_path: Path) -> None:
    source, output, apk, _apksigner = build_fixture(tmp_path)
    receipts = fixture_quality_receipts(source)
    receipt = json.loads(receipts["web"].read_text(encoding="utf-8"))
    receipt["inputs"]["tracked_source_after"]["sha256"] = "0" * 64
    write(receipts["web"], json.dumps(receipt, sort_keys=True))
    web_manifest_path = output / "web/web-build-manifest.json"
    web_manifest = json.loads(web_manifest_path.read_text(encoding="utf-8"))

    with pytest.raises(builder.ReleaseBuildError, match="inventory"):
        builder.build_full_rc(
            source_root=source,
            web_manifest_path=web_manifest_path,
            web_artifact_root=output / "web",
            web_archive_path=output / "web" / web_manifest["deployment_archive"]["path"],
            unsigned_apk_path=apk,
            quality_receipt_paths=receipts,
            output_root=tmp_path / "second-rc",
        )


@pytest.mark.parametrize("phase", ["before", "after"])
def test_builder_rejects_web_quality_node_attestation_drift(
    tmp_path: Path,
    phase: str,
) -> None:
    source, output, apk, _apksigner = build_fixture(tmp_path)
    receipts = fixture_quality_receipts(source)
    receipt = json.loads(receipts["web"].read_text(encoding="utf-8"))
    receipt["node_toolchain"][phase]["node"]["sha256"] = "0" * 64
    write(receipts["web"], json.dumps(receipt, sort_keys=True))
    web_manifest_path = output / "web/web-build-manifest.json"
    web_manifest = json.loads(web_manifest_path.read_text(encoding="utf-8"))

    with pytest.raises(builder.ReleaseBuildError, match="Node attestation"):
        builder.build_full_rc(
            source_root=source,
            web_manifest_path=web_manifest_path,
            web_artifact_root=output / "web",
            web_archive_path=output / "web" / web_manifest["deployment_archive"]["path"],
            unsigned_apk_path=apk,
            quality_receipt_paths=receipts,
            output_root=tmp_path / "second-rc",
        )


def test_builder_rejects_forged_installed_distribution_inventory(tmp_path: Path) -> None:
    source, output, apk, _apksigner = build_fixture(tmp_path)
    receipts = fixture_quality_receipts(source)
    receipt = json.loads(receipts["voice"].read_text(encoding="utf-8"))
    receipt["tested_environment"]["installed_distributions"]["sha256"] = "0" * 64
    write(receipts["voice"], json.dumps(receipt, sort_keys=True))
    web_manifest_path = output / "web/web-build-manifest.json"
    web_manifest = json.loads(web_manifest_path.read_text(encoding="utf-8"))

    with pytest.raises(builder.ReleaseBuildError, match="installed distribution identity"):
        builder.build_full_rc(
            source_root=source,
            web_manifest_path=web_manifest_path,
            web_artifact_root=output / "web",
            web_archive_path=output / "web" / web_manifest["deployment_archive"]["path"],
            unsigned_apk_path=apk,
            quality_receipt_paths=receipts,
            output_root=tmp_path / "second-rc",
        )


def test_builder_rejects_forged_site_directory_closure(tmp_path: Path) -> None:
    source, output, apk, _apksigner = build_fixture(tmp_path)
    receipts = fixture_quality_receipts(source)
    receipt = json.loads(receipts["voice"].read_text(encoding="utf-8"))
    site_packages = receipt["tested_environment"]["site_packages"]
    site_packages["closure"]["count"] = len(site_packages["roots"])
    write(receipts["voice"], json.dumps(receipt, sort_keys=True))
    web_manifest_path = output / "web/web-build-manifest.json"
    web_manifest = json.loads(web_manifest_path.read_text(encoding="utf-8"))

    with pytest.raises(builder.ReleaseBuildError, match="site-packages record"):
        builder.build_full_rc(
            source_root=source,
            web_manifest_path=web_manifest_path,
            web_artifact_root=output / "web",
            web_archive_path=output / "web" / web_manifest["deployment_archive"]["path"],
            unsigned_apk_path=apk,
            quality_receipt_paths=receipts,
            output_root=tmp_path / "second-rc",
        )


def test_builder_rejects_resigned_site_closure_outside_source_policy(tmp_path: Path) -> None:
    source, output, apk, _apksigner = build_fixture(tmp_path)
    receipts = fixture_quality_receipts(source)
    receipt = json.loads(receipts["voice"].read_text(encoding="utf-8"))
    receipt["tested_environment"]["site_packages"]["closure"]["sha256"] = "0" * 64
    write(receipts["voice"], json.dumps(receipt, sort_keys=True))
    web_manifest_path = output / "web/web-build-manifest.json"
    web_manifest = json.loads(web_manifest_path.read_text(encoding="utf-8"))

    with pytest.raises(builder.ReleaseBuildError, match="source-pinned policy"):
        builder.build_full_rc(
            source_root=source,
            web_manifest_path=web_manifest_path,
            web_artifact_root=output / "web",
            web_archive_path=output / "web" / web_manifest["deployment_archive"]["path"],
            unsigned_apk_path=apk,
            quality_receipt_paths=receipts,
            output_root=tmp_path / "second-rc",
        )


def test_android_builder_rejects_stale_dex_with_appended_current_commit(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source_commit = create_source_repo(source)
    apk = tmp_path / "app-release-unsigned.apk"
    create_unsigned_apk(apk, "0" * 40, source)
    rewrite_apk(apk, {}, {"assets/forged-source.txt": source_commit.encode()})

    with pytest.raises(builder.ReleaseBuildError, match="BuildConfig release binding"):
        builder._validate_unsigned_apk_payload(apk, source_commit, source)


def test_android_builder_accepts_build_config_in_secondary_dex(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source_commit = create_source_repo(source)
    apk = tmp_path / "app-release-unsigned.apk"
    create_unsigned_apk(apk, source_commit, source)

    builder._validate_unsigned_apk_payload(apk, source_commit, source)


def test_android_builder_rejects_duplicate_build_config_dex(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source_commit = create_source_repo(source)
    apk = tmp_path / "app-release-unsigned.apk"
    create_unsigned_apk(apk, source_commit, source)
    rewrite_apk(apk, {}, {"classes3.dex": build_config_dex(source_commit)})

    with pytest.raises(builder.ReleaseBuildError, match="BuildConfig release binding"):
        builder._validate_unsigned_apk_payload(apk, source_commit, source)


def test_android_builder_rejects_current_commit_appended_to_stale_build_config_dex(
    tmp_path: Path,
) -> None:
    source = tmp_path / "source"
    source_commit = create_source_repo(source)
    apk = tmp_path / "app-release-unsigned.apk"
    create_unsigned_apk(apk, source_commit, source)
    forged = bytearray(build_config_dex("0" * 40))
    forged.extend(source_commit.encode("ascii"))
    forged[32:36] = len(forged).to_bytes(4, "little")
    data_size = int.from_bytes(forged[104:108], "little") + len(source_commit)
    forged[104:108] = data_size.to_bytes(4, "little")
    forged[12:32] = hashlib.sha1(forged[32:]).digest()
    forged[8:12] = (zlib.adler32(forged[12:]) & 0xFFFFFFFF).to_bytes(4, "little")
    rewrite_apk(apk, {"classes2.dex": bytes(forged)})

    with pytest.raises(builder.ReleaseBuildError, match="BuildConfig release binding"):
        builder._validate_unsigned_apk_payload(apk, source_commit, source)


@pytest.mark.parametrize("version", [b"036", b"041"])
def test_android_builder_rejects_unreviewed_dex_header_version(
    tmp_path: Path,
    version: bytes,
) -> None:
    source = tmp_path / "source"
    source_commit = create_source_repo(source)
    apk = tmp_path / "app-release-unsigned.apk"
    create_unsigned_apk(apk, source_commit, source)
    unreviewed = bytearray(build_config_dex(source_commit))
    unreviewed[4:7] = version
    unreviewed[12:32] = hashlib.sha1(unreviewed[32:]).digest()
    unreviewed[8:12] = (zlib.adler32(unreviewed[12:]) & 0xFFFFFFFF).to_bytes(4, "little")
    rewrite_apk(apk, {"classes2.dex": bytes(unreviewed)})

    with pytest.raises(builder.ReleaseBuildError, match="BuildConfig release binding"):
        builder._validate_unsigned_apk_payload(apk, source_commit, source)


def test_android_builder_rejects_dex_string_with_false_utf16_size(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source_commit = create_source_repo(source)
    apk = tmp_path / "app-release-unsigned.apk"
    create_unsigned_apk(apk, source_commit, source)
    malformed = bytearray(build_config_dex(source_commit))
    commit_offset = malformed.index(source_commit.encode("ascii"))
    assert malformed[commit_offset - 1] == len(source_commit)
    malformed[commit_offset - 1] -= 1
    malformed[12:32] = hashlib.sha1(malformed[32:]).digest()
    malformed[8:12] = (zlib.adler32(malformed[12:]) & 0xFFFFFFFF).to_bytes(4, "little")
    rewrite_apk(apk, {"classes2.dex": bytes(malformed)})

    with pytest.raises(builder.ReleaseBuildError, match="BuildConfig release binding"):
        builder._validate_unsigned_apk_payload(apk, source_commit, source)


def test_android_builder_controls_oversized_dex_index_rejection(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source_commit = create_source_repo(source)
    apk = tmp_path / "app-release-unsigned.apk"
    create_unsigned_apk(apk, source_commit, source)
    oversized_name = f"classes{'9' * 10_000}.dex"
    rewrite_apk(apk, {}, {oversized_name: build_config_dex(source_commit)})

    with pytest.raises(builder.ReleaseBuildError, match="BuildConfig release binding"):
        builder._validate_unsigned_apk_payload(apk, source_commit, source)


def test_android_dex_binding_parser_is_a_pinned_provenance_input() -> None:
    relative = "scripts/walksafe_android_dex_binding.py"
    parser_sha256 = sha256(ROOT / relative)

    assert relative in builder.RELEASE_SOURCE_INPUTS
    assert relative in validator.REQUIRED_PROVENANCE_PATHS
    assert parser_sha256 == builder._DEX_BINDING_SHA256
    assert parser_sha256 == validator._DEX_BINDING_SHA256


def test_external_receipt_policy_is_full_release_provenance() -> None:
    relative = "scripts/walksafe_external_check_receipt.py"

    assert relative in builder.RELEASE_SOURCE_INPUTS
    assert relative in validator.REQUIRED_PROVENANCE_PATHS


def test_validator_rejects_mutated_external_receipt_policy_provenance(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    receipt_policy = next(
        record
        for record in manifest["source_inputs"]
        if record["path"] == "scripts/walksafe_external_check_receipt.py"
    )
    receipt_policy["sha256"] = "0" * 64
    write(manifest_path, json.dumps(manifest, sort_keys=True))

    with pytest.raises(validator.ValidationError, match="provenance"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_report_retention_scheduler_is_in_provenance_and_backend_runtime() -> None:
    runtime_files = {
        "deploy/config/walksafe-report-retention.env.example",
        "deploy/systemd/walksafe-report-retention.service",
        "deploy/systemd/walksafe-report-retention.timer",
        "scripts/check_report_retention_dry_run.py",
        "scripts/run_walksafe_report_retention_20260717.sh",
        "scripts/walksafe_backup_integrity.py",
        "scripts/walksafe_environment_identity.py",
        "scripts/walksafe_release_integrity.py",
    }

    assert runtime_files <= set(builder.RELEASE_SOURCE_INPUTS)
    assert runtime_files <= validator.REQUIRED_PROVENANCE_PATHS
    assert runtime_files <= set(builder._backend_runtime_files(ROOT))


def test_issuer_binding_cli_is_provenance_and_in_backend_archive(
    tmp_path: Path,
) -> None:
    relative = "scripts/bind_walksafe_admin_credential_issuer_key.py"
    assert relative in builder.RELEASE_SOURCE_INPUTS
    assert relative in validator.REQUIRED_PROVENANCE_PATHS
    assert relative in builder._backend_runtime_files(ROOT)

    source, output, _apk, _apksigner = build_fixture(tmp_path)
    manifest = json.loads(
        (output / "walksafe-full-rc-manifest.json").read_text(encoding="utf-8")
    )
    archive_path = output / manifest["components"]["backend"]["artifact"]["path"]
    member_name = f"walksafe-backend/{relative}"
    with tarfile.open(archive_path, mode="r:gz") as archive:
        member = archive.getmember(member_name)
        archived = archive.extractfile(member)
        assert member.isfile() and archived is not None
        assert archived.read() == (source / relative).read_bytes()


def test_android_builder_rejects_model_asset_that_differs_from_source(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source_commit = create_source_repo(source)
    apk = tmp_path / "app-release-unsigned.apk"
    create_unsigned_apk(apk, source_commit, source)
    rewrite_apk(apk, {"assets/models/coco_yolo26n_float32.tflite": b"tampered"})

    with pytest.raises(builder.ReleaseBuildError, match="model asset differs"):
        builder._validate_unsigned_apk_payload(apk, source_commit, source)


def test_runtime_contract_requires_backend_migration_before_service(tmp_path: Path) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    write(source / "deploy/systemd/walksafe-backend.service", "--workers 1\nStateDirectoryMode=0700\n")

    with pytest.raises(validator.ValidationError, match="backend runtime unit"):
        validator._validate_runtime_contracts(source)


def test_runtime_contract_requires_remote_database_to_disable_gss_encryption(tmp_path: Path) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    backend_config = source / "deploy/config/walksafe-backend.env.example"
    backend_config.write_text(
        backend_config.read_text(encoding="utf-8").replace(
            "&gssencmode=disable",
            "\n# gssencmode=disable",
        ),
        encoding="utf-8",
    )

    with pytest.raises(validator.ValidationError, match="gssencmode=disable"):
        validator._validate_runtime_contracts(source)


def test_runtime_contract_rejects_migration_database_url_in_api_environment(
    tmp_path: Path,
) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    runtime_environment = source / "deploy/config/walksafe-backend.env.example"
    write(
        runtime_environment,
        runtime_environment.read_text(encoding="utf-8")
        + "WALKSAFE_MIGRATION_DATABASE_URL="
        "postgresql+psycopg://walksafe_migrator:CHANGE_ME@db.example.invalid:5432/"
        "walksafe?sslmode=verify-full&gssencmode=disable\n",
    )

    with pytest.raises(validator.ValidationError, match="backend runtime example"):
        validator._validate_runtime_contracts(source)


@pytest.mark.parametrize(
    "assignment",
    [
        (
            "DATABASE_URL=postgresql+psycopg://walksafe_backend_app:CHANGE_ME@"
            "db.example.invalid:5432/walksafe?sslmode=verify-full&gssencmode=disable"
        ),
        "WALKSAFE_ADMIN_TOTP_SECRET=CHANGE_ME_CANONICAL_UNPADDED_BASE32_MIN_160_BITS",
    ],
    ids=("runtime-database-url", "admin-totp-secret"),
)
def test_runtime_contract_rejects_api_secret_in_migration_environment(
    tmp_path: Path,
    assignment: str,
) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    migration_environment = (
        source / "deploy/config/walksafe-backend-migration.env.example"
    )
    write(
        migration_environment,
        migration_environment.read_text(encoding="utf-8") + assignment + "\n",
    )

    with pytest.raises(validator.ValidationError, match="backend migration example"):
        validator._validate_runtime_contracts(source)


def test_runtime_contract_rejects_api_reading_migration_environment(
    tmp_path: Path,
) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    runtime_unit = source / "deploy/systemd/walksafe-backend.service"
    trusted = "EnvironmentFile=/etc/walksafe/backend-runtime.env"
    payload = runtime_unit.read_text(encoding="utf-8")
    assert trusted in payload
    write(
        runtime_unit,
        payload.replace(
            trusted,
            "EnvironmentFile=/etc/walksafe/backend-migration.env",
            1,
        ),
    )

    with pytest.raises(validator.ValidationError, match="backend runtime unit"):
        validator._validate_runtime_contracts(source)


def test_runtime_contract_rejects_migration_running_as_runtime_identity(
    tmp_path: Path,
) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    migration_unit = source / "deploy/systemd/walksafe-backend-migrate.service"
    payload = migration_unit.read_text(encoding="utf-8")
    for trusted, unsafe in (
        ("User=walksafe-maintenance", "User=walksafe-backend"),
        ("Group=walksafe-maintenance", "Group=walksafe-backend"),
        (
            "EnvironmentFile=/etc/walksafe/backend-migration.env",
            "EnvironmentFile=/etc/walksafe/backend-runtime.env",
        ),
    ):
        assert trusted in payload
        payload = payload.replace(trusted, unsafe, 1)
    write(migration_unit, payload)

    with pytest.raises(validator.ValidationError, match="backend migration unit"):
        validator._validate_runtime_contracts(source)


@pytest.mark.parametrize(
    ("trusted", "unsafe"),
    [
        (
            "User=walksafe-issuer-bind\nGroup=walksafe-issuer-bind",
            "User=root\nGroup=root",
        ),
        (
            "LoadCredential=admin-credential-issuer.key:"
            "/etc/walksafe/admin-credential-issuer.key\n",
            "",
        ),
    ],
    ids=("root-execution", "missing-load-credential"),
)
def test_runtime_contract_rejects_privileged_or_uncredentialed_issuer_binding(
    tmp_path: Path,
    trusted: str,
    unsafe: str,
) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    binding_unit = source / "deploy/systemd/walksafe-admin-issuer-bind.service"
    payload = binding_unit.read_text(encoding="utf-8")
    assert trusted in payload
    write(binding_unit, payload.replace(trusted, unsafe, 1))

    with pytest.raises(validator.ValidationError, match="backend issuer binding unit"):
        validator._validate_runtime_contracts(source)


@pytest.mark.parametrize(
    ("relative", "trusted", "unsafe", "message"),
    [
        (
            "deploy/config/walksafe-report-retention.env.example",
            "sslmode=verify-full",
            "sslmode=require",
            "report retention runtime example",
        ),
        (
            "deploy/systemd/walksafe-report-retention.service",
            "User=walksafe-backend",
            "User=root",
            "report retention runtime unit",
        ),
        (
            "deploy/systemd/walksafe-report-retention.service",
            "ProtectHome=true",
            "ProtectHome=false",
            "report retention runtime unit",
        ),
        (
            "deploy/systemd/walksafe-report-retention.service",
            "EnvironmentFile=/etc/walksafe/report-retention.env",
            "EnvironmentFile=/etc/walksafe/backend.env",
            "report retention runtime unit",
        ),
        (
            "deploy/systemd/walksafe-report-retention.service",
            "TimeoutStartSec=1h",
            "TimeoutStartSec=infinity",
            "report retention runtime unit",
        ),
        (
            "deploy/systemd/walksafe-report-retention.service",
            "TimeoutStopSec=130s",
            "TimeoutStopSec=infinity",
            "report retention runtime unit",
        ),
        (
            "deploy/systemd/walksafe-report-retention.service",
            "LimitCORE=0",
            "LimitCORE=infinity",
            "report retention runtime unit",
        ),
        (
            "deploy/systemd/walksafe-report-retention.timer",
            "Persistent=true",
            "Persistent=false",
            "report retention timer",
        ),
        (
            "scripts/run_walksafe_report_retention_20260717.sh",
            "--upload-dir \"${UPLOAD_DIR}\"",
            "--database-url \"${DATABASE_URL}\" --upload-dir \"${UPLOAD_DIR}\"",
            "report retention runner",
        ),
        (
            "scripts/run_walksafe_report_retention_20260717.sh",
            "metadata.st_uid != os.geteuid()",
            "False",
            "report retention runner",
        ),
        (
            "scripts/run_walksafe_report_retention_20260717.sh",
            "rename_noreplace(directory_fd, pending.name, final_name)",
            "os.replace(pending.name, final_name)",
            "report retention runner",
        ),
        (
            "scripts/run_walksafe_report_retention_20260717.sh",
            "/usr/bin/setsid --wait",
            "/usr/bin/setsid",
            "report retention runner",
        ),
        (
            "scripts/run_walksafe_report_retention_20260717.sh",
            'child_is_running "${pid}" || child_group_is_running "${pid}"',
            'child_is_running "${pid}"',
            "report retention runner",
        ),
    ],
)
def test_runtime_contract_rejects_unsafe_report_retention_override(
    tmp_path: Path,
    relative: str,
    trusted: str,
    unsafe: str,
    message: str,
) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    path = source / relative
    path.write_text(
        path.read_text(encoding="utf-8").replace(trusted, unsafe),
        encoding="utf-8",
    )

    with pytest.raises(validator.ValidationError, match=message):
        validator._validate_runtime_contracts(source)


@pytest.mark.parametrize(
    ("trusted", "unsafe"),
    [
        (
            "proxy_set_header CF-Connecting-IP $remote_addr;",
            "proxy_set_header CF-Connecting-IP $http_cf_connecting_ip;",
        ),
        (
            "proxy_set_header X-Forwarded-For $remote_addr;",
            "proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;",
        ),
        (
            'proxy_set_header Forwarded "";',
            "proxy_set_header Forwarded $http_forwarded;",
        ),
    ],
)
def test_runtime_contract_rejects_client_injected_forwarding_identity(
    tmp_path: Path,
    trusted: str,
    unsafe: str,
) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    proxy_config = source / "deploy/nginx/walksafe-web.conf.example"
    proxy_config.write_text(
        proxy_config.read_text(encoding="utf-8").replace(trusted, unsafe),
        encoding="utf-8",
    )

    with pytest.raises(validator.ValidationError, match="trusted proxy"):
        validator._validate_runtime_contracts(source)


@pytest.mark.parametrize("extra", ["listen 3001;", "include /etc/nginx/conf.d/extra.conf;"])
def test_runtime_contract_rejects_additional_proxy_listener_or_include(
    tmp_path: Path,
    extra: str,
) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    proxy_config = source / "deploy/nginx/walksafe-web.conf.example"
    proxy_config.write_text(
        proxy_config.read_text(encoding="utf-8").replace(
            "    listen 443 ssl;",
            f"    listen 443 ssl;\n    {extra}",
        ),
        encoding="utf-8",
    )

    with pytest.raises(validator.ValidationError, match="single-listener TLS"):
        validator._validate_runtime_contracts(source)


@pytest.mark.parametrize(
    ("trusted", "unsafe"),
    [
        ("client_max_body_size 64k;", "client_max_body_size 11m;"),
        ("client_max_body_size 9m;", "client_max_body_size 10m;"),
        ("client_max_body_size 11m;", "client_max_body_size 12m;"),
        ("proxy_request_buffering on;", "proxy_request_buffering off;"),
        ("proxy_request_buffering off;", "proxy_request_buffering on;"),
        ("location = /api/detect {", "location /api/detect {"),
        ("location = /api/detect/v2 {", "location /api/detect/v2 {"),
        ("location = /api/reports {", "location /api/reports {"),
        ("location = /api/reports/v2 {", "location /api/reports/v2 {"),
        ("location = /api/speech/stt {", "location /api/speech/stt {"),
        ("client_header_timeout 10s;", "client_header_timeout 60s;"),
        ("client_body_timeout 10s;", "client_body_timeout 60s;"),
        ("send_timeout 20s;", "send_timeout 60s;"),
        ("proxy_connect_timeout 2s;", "proxy_connect_timeout 60s;"),
        ("proxy_send_timeout 15s;", "proxy_send_timeout 60s;"),
        ("proxy_read_timeout 20s;", "proxy_read_timeout 60s;"),
    ],
)
def test_runtime_contract_rejects_relaxed_proxy_body_boundary(
    tmp_path: Path,
    trusted: str,
    unsafe: str,
) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    proxy_config = source / "deploy/nginx/walksafe-web.conf.example"
    contents = proxy_config.read_text(encoding="utf-8")
    assert trusted in contents
    proxy_config.write_text(contents.replace(trusted, unsafe, 1), encoding="utf-8")

    with pytest.raises(validator.ValidationError, match="single-listener TLS"):
        validator._validate_runtime_contracts(source)


@pytest.mark.parametrize(
    ("trusted", "unsafe"),
    [
        ("HOSTNAME=127.0.0.1", "HOSTNAME=0.0.0.0"),
        ("PORT=3000", "PORT=3001"),
        ("HOSTNAME=127.0.0.1", "HOSTNAME=127.0.0.1\nHOSTNAME=0.0.0.0"),
    ],
)
def test_runtime_contract_rejects_direct_web_proxy_bypass(
    tmp_path: Path,
    trusted: str,
    unsafe: str,
) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    web_environment = source / "deploy/config/walksafe-web.env.example"
    web_environment.write_text(
        web_environment.read_text(encoding="utf-8").replace(trusted, unsafe),
        encoding="utf-8",
    )

    with pytest.raises(validator.ValidationError, match="Web runtime example"):
        validator._validate_runtime_contracts(source)


@pytest.mark.parametrize(
    ("relative", "trusted", "unsafe"),
    [
        (
            "deploy/config/walksafe-backend.env.example",
            "WALKSAFE_ENVIRONMENT=production",
            "# WALKSAFE_ENVIRONMENT=production\nWALKSAFE_ENVIRONMENT=development",
        ),
        (
            "deploy/config/walksafe-backend.env.example",
            "WALKSAFE_ADMIN_SECURITY_ENABLED=true",
            "# WALKSAFE_ADMIN_SECURITY_ENABLED=true\n"
            "WALKSAFE_ADMIN_SECURITY_ENABLED=false",
        ),
        (
            "deploy/config/walksafe-backend.env.example",
            "WALKSAFE_ADMIN_TOTP_SECRET=CHANGE_ME_CANONICAL_UNPADDED_BASE32_MIN_160_BITS",
            "# WALKSAFE_ADMIN_TOTP_SECRET="
            "CHANGE_ME_CANONICAL_UNPADDED_BASE32_MIN_160_BITS\n"
            "WALKSAFE_ADMIN_TOTP_SECRET=",
        ),
        (
            "deploy/config/walksafe-voice.env.example",
            "VOICE_SERVICE_WORKERS=1",
            "# VOICE_SERVICE_WORKERS=1\nVOICE_SERVICE_WORKERS=8",
        ),
        (
            "deploy/config/walksafe-web.env.example",
            "WALKSAFE_GATEWAY_TRUSTED_IP_HEADER=cf-connecting-ip",
            "# WALKSAFE_GATEWAY_TRUSTED_IP_HEADER=cf-connecting-ip\n"
            "WALKSAFE_GATEWAY_TRUSTED_IP_HEADER=x-forwarded-for",
        ),
    ],
)
def test_runtime_contract_rejects_commented_safe_environment_shadow(
    tmp_path: Path,
    relative: str,
    trusted: str,
    unsafe: str,
) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    environment = source / relative
    environment.write_text(
        environment.read_text(encoding="utf-8").replace(trusted, unsafe),
        encoding="utf-8",
    )

    with pytest.raises(validator.ValidationError, match="runtime example"):
        validator._validate_runtime_contracts(source)


@pytest.mark.parametrize(
    ("trusted", "unsafe"),
    [
        ("EnvironmentFile=/etc/walksafe/web.env\n", ""),
        (
            "EnvironmentFile=/etc/walksafe/web.env\n",
            "EnvironmentFile=/etc/walksafe/web.env\n"
            "EnvironmentFile=-/etc/walksafe/web-override.env\n",
        ),
        (
            "EnvironmentFile=/etc/walksafe/web.env\n",
            "EnvironmentFile=/etc/walksafe/web.env\nEnvironment=HOSTNAME=0.0.0.0\n",
        ),
        (
            "EnvironmentFile=/etc/walksafe/web.env\n",
            "EnvironmentFile=/etc/walksafe/web.env\nUnsetEnvironment=HOSTNAME\n",
        ),
        (
            "ExecStart=/usr/bin/python3 /srv/walksafe/web/scripts/"
            "run_walksafe_web_single_instance_20260713.py -- /usr/bin/node "
            "/srv/walksafe/web/web/server.js\n",
            "# run_walksafe_web_single_instance_20260713.py -- /usr/bin/node\n"
            "ExecStart=/usr/bin/env HOSTNAME=0.0.0.0 PORT=3000 /usr/bin/node "
            "/srv/walksafe/web/web/server.js\n",
        ),
        (
            "ExecStart=/usr/bin/python3 /srv/walksafe/web/scripts/"
            "run_walksafe_web_single_instance_20260713.py -- /usr/bin/node "
            "/srv/walksafe/web/web/server.js\n",
            "ExecStart=/usr/bin/python3 /srv/walksafe/web/scripts/"
            "run_walksafe_web_single_instance_20260713.py -- /usr/bin/node "
            "/srv/walksafe/web/web/server.js\n"
            "ExecStartPost=/usr/bin/env HOSTNAME=0.0.0.0 PORT=3001 /usr/bin/node "
            "/srv/walksafe/web/web/server.js\n",
        ),
        ("User=walksafe-web\n", "User=walksafe-web\nUser=root\n"),
    ],
)
def test_runtime_contract_rejects_web_unit_environment_override(
    tmp_path: Path,
    trusted: str,
    unsafe: str,
) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    web_unit = source / "deploy/systemd/walksafe-web.service"
    web_unit.write_text(
        web_unit.read_text(encoding="utf-8").replace(trusted, unsafe),
        encoding="utf-8",
    )

    with pytest.raises(validator.ValidationError, match="Web runtime unit"):
        validator._validate_runtime_contracts(source)


@pytest.mark.parametrize(
    ("relative", "trusted", "unsafe", "message"),
    [
        (
            "deploy/systemd/walksafe-backend.service",
            "--host 127.0.0.1 --port 8000 --workers 1",
            "--host 0.0.0.0 --port 8000 --workers 8\n# --workers 1",
            "backend runtime unit",
        ),
        (
            "deploy/systemd/walksafe-backend.service",
            "LimitCORE=0",
            "LimitCORE=infinity",
            "backend runtime unit",
        ),
        (
            "deploy/systemd/walksafe-voice.service",
            "--host 127.0.0.1 --port 9001 --workers 1",
            "--host 0.0.0.0 --port 9001 --workers 8\n# --workers 1",
            "Voice runtime unit",
        ),
        (
            "deploy/systemd/walksafe-backend-migrate.service",
            "ExecStart=/srv/walksafe/backend/.venv/bin/python -m alembic "
            "-c backend/alembic.ini upgrade head",
            "ExecStart=/srv/walksafe/backend/.venv/bin/python -m alembic "
            "-c backend/alembic.ini upgrade head\nExecStartPost=/usr/bin/true",
            "backend migration unit",
        ),
        (
            "deploy/systemd/walksafe-backend-migrate.service",
            "LimitCORE=0",
            "LimitCORE=infinity",
            "backend migration unit",
        ),
    ],
)
def test_runtime_contract_rejects_backend_voice_or_migration_command_override(
    tmp_path: Path,
    relative: str,
    trusted: str,
    unsafe: str,
    message: str,
) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    unit = source / relative
    unit.write_text(
        unit.read_text(encoding="utf-8").replace(trusted, unsafe),
        encoding="utf-8",
    )

    with pytest.raises(validator.ValidationError, match=message):
        validator._validate_runtime_contracts(source)


@pytest.mark.parametrize(
    ("members", "message"),
    [
        ([('../escape', b'x', 'file', 0)], "unsafe"),
        ([('root/link', b'', 'symlink', 0)], "regular"),
        ([('root/file', b'x', 'file', 1)], "mtime"),
        (
            [('root/z', b'z', 'file', 0), ('root/a', b'a', 'file', 0)],
            "order",
        ),
    ],
)
def test_independent_archive_validator_rejects_unsafe_or_nondeterministic_members(
    tmp_path: Path,
    members: list[tuple[str, bytes, str, int]],
    message: str,
) -> None:
    archive = tmp_path / "bad.tar.gz"
    make_tar(archive, members)
    with pytest.raises(validator.ValidationError, match=message):
        validator.inspect_deterministic_archive(archive)


@pytest.mark.parametrize(
    "members",
    [
        [
            ("BUILD_ID", b"a" * 40, "file", 0o644),
            ("collision", b"", "dir", 0o755),
            ("collision", b"payload", "file", 0o644),
        ],
        [
            ("BUILD_ID", b"a" * 40, "file", 0o644),
            ("privileged", b"payload", "file", 0o4755),
        ],
    ],
)
def test_web_archive_rejects_path_collisions_and_special_modes(
    tmp_path: Path,
    members: list[tuple[str, bytes, str, int]],
) -> None:
    archive_path = tmp_path / "web.tar.gz"
    raw = io.BytesIO()
    files = []
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.PAX_FORMAT) as archive:
        for name, content, kind, mode in members:
            info = tarfile.TarInfo(name)
            info.mtime = 0
            info.uid = info.gid = 0
            info.mode = mode
            if kind == "dir":
                info.type = tarfile.DIRTYPE
                archive.addfile(info)
            else:
                info.size = len(content)
                archive.addfile(info, io.BytesIO(content))
                files.append({"path": name, "sha256": hashlib.sha256(content).hexdigest()})
    with archive_path.open("wb") as destination:
        with gzip.GzipFile(filename="", mode="wb", fileobj=destination, mtime=0) as compressed:
            compressed.write(raw.getvalue())
    manifest = {"source_commit": "a" * 40, "files": files}

    with pytest.raises(validator.ValidationError, match="duplicate path|non-canonical mode"):
        validator._inspect_web_archive(archive_path, manifest)


@pytest.mark.parametrize(
    ("members", "accepted"),
    [
        (
            [
                ("BUILD_ID", b"a" * 40, "file"),
                ("app", b"", "dir"),
                ("app/page", b"", "dir"),
                ("app/page/server-reference-manifest.json", b"nested", "file"),
                ("app/page.js", b"sibling", "file"),
            ],
            True,
        ),
        (
            [
                ("BUILD_ID", b"a" * 40, "file"),
                ("app", b"", "dir"),
                ("app/page", b"", "dir"),
                ("app/page.js", b"sibling", "file"),
                ("app/page/server-reference-manifest.json", b"nested", "file"),
            ],
            False,
        ),
    ],
)
def test_web_archive_uses_componentwise_canonical_member_order(
    tmp_path: Path,
    members: list[tuple[str, bytes, str]],
    accepted: bool,
) -> None:
    archive_path = tmp_path / "web.tar.gz"
    raw = io.BytesIO()
    files = []
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.PAX_FORMAT) as archive:
        for name, content, kind in members:
            info = tarfile.TarInfo(name)
            info.mtime = 0
            info.uid = info.gid = 0
            info.mode = 0o755 if kind == "dir" else 0o644
            if kind == "dir":
                info.type = tarfile.DIRTYPE
                archive.addfile(info)
            else:
                info.size = len(content)
                archive.addfile(info, io.BytesIO(content))
                files.append(
                    {"path": name, "sha256": hashlib.sha256(content).hexdigest()}
                )
    with archive_path.open("wb") as destination:
        with gzip.GzipFile(filename="", mode="wb", fileobj=destination, mtime=0) as compressed:
            compressed.write(raw.getvalue())
    manifest = {"source_commit": "a" * 40, "files": files}

    if accepted:
        validator._inspect_web_archive(archive_path, manifest)
    else:
        with pytest.raises(validator.ValidationError, match="order is not deterministic"):
            validator._inspect_web_archive(archive_path, manifest)


def test_web_archive_rejects_numeric_manifest_path_type(tmp_path: Path) -> None:
    archive_path = tmp_path / "web.tar.gz"
    source_commit = "a" * 40
    numeric_content = b"numeric-path"
    make_tar(
        archive_path,
        [
            ("0", numeric_content, "file", 0),
            ("BUILD_ID", source_commit.encode(), "file", 0),
        ],
    )
    manifest = {
        "source_commit": source_commit,
        "files": [
            {"path": 0, "sha256": hashlib.sha256(numeric_content).hexdigest()},
            {
                "path": "BUILD_ID",
                "sha256": hashlib.sha256(source_commit.encode()).hexdigest(),
            },
        ],
    }

    with pytest.raises(validator.ValidationError, match="file hash entry types"):
        validator._inspect_web_archive(archive_path, manifest)


def test_validator_binds_manifest_source_commit_to_actual_head(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["source"]["commit"] = "0" * 40
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(validator.ValidationError, match="HEAD"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_rejects_stale_file_closure_digest(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["closure"]["sha256"] = "0" * 64
    write(manifest_path, json.dumps(manifest, sort_keys=True))

    with pytest.raises(validator.ValidationError, match="closure digest"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_preserves_external_tls_identity_blocker(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    tls_identity = next(
        row
        for row in manifest["external_runtime_inputs"]
        if row["id"] == "trusted-edge-tls-identity"
    )
    tls_identity["required"] = "none"
    write(manifest_path, json.dumps(manifest, sort_keys=True))

    with pytest.raises(validator.ValidationError, match="external runtime blocker"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_rejects_manifest_semantic_contract_mutations(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    original = json.loads(manifest_path.read_text(encoding="utf-8"))

    def change_blocker_requirement(payload: dict[str, object]) -> None:
        blockers = payload["external_runtime_inputs"]
        assert isinstance(blockers, list)
        next(row for row in blockers if row["id"] == "operator-secrets")["required"] = "none"

    def duplicate_blocker_id(payload: dict[str, object]) -> None:
        blockers = payload["external_runtime_inputs"]
        assert isinstance(blockers, list)
        blockers[-1] = dict(blockers[0])

    def add_unknown_blocker_id(payload: dict[str, object]) -> None:
        blockers = payload["external_runtime_inputs"]
        assert isinstance(blockers, list)
        blockers[-1] = {
            "id": "all-blockers-cleared",
            "included": False,
            "required": "none",
        }

    mutations = (
        (
            "top-level field",
            lambda payload: payload.__setitem__("deployment_instructions", "deploy now"),
            "top-level fields",
        ),
        (
            "source field",
            lambda payload: payload["source"].__setitem__("unverified", True),
            "source commit",
        ),
        (
            "release claim",
            lambda payload: payload.__setitem__(
                "release_state",
                {
                    "kind": "production_release",
                    "deployment_complete": False,
                    "reason": "all operational blockers cleared",
                },
            ),
            "release state",
        ),
        ("blocker requirement", change_blocker_requirement, "external runtime blocker differs"),
        ("duplicate blocker", duplicate_blocker_id, "exact unique set"),
        ("unknown blocker", add_unknown_blocker_id, "exact unique set"),
        (
            "packaging claim",
            lambda payload: payload["packaging_policy"].__setitem__(
                "android_embedded_model_note", "signed and deployment-ready"
            ),
            "packaging policy",
        ),
    )
    for _label, mutate, expected_error in mutations:
        manifest = json.loads(json.dumps(original))
        mutate(manifest)
        write(manifest_path, json.dumps(manifest, sort_keys=True))
        with pytest.raises(validator.ValidationError, match=expected_error):
            validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_rejects_json_scalar_type_confusion(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    original_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    voice_receipt_relative = original_manifest["quality"]["products"]["voice"]["receipt"][
        "path"
    ]
    voice_receipt_path = output / voice_receipt_relative
    original_voice_receipt_bytes = voice_receipt_path.read_bytes()
    original_voice_receipt = json.loads(original_voice_receipt_bytes)

    manifest_mutations = (
        (
            "release bool as integer",
            lambda payload: payload["release_state"].__setitem__("deployment_complete", 0),
        ),
        (
            "authentication bool as integer",
            lambda payload: payload["authentication"].__setitem__("included", 0),
        ),
        (
            "authentication deployment bool as integer",
            lambda payload: payload["authentication"].__setitem__(
                "deployment_allowed", 0
            ),
        ),
        (
            "external-input bool as integer",
            lambda payload: payload["external_runtime_inputs"][0].__setitem__("included", 0),
        ),
        (
            "packaging bool as integer",
            lambda payload: payload["packaging_policy"].__setitem__("secrets_included", 0),
        ),
        (
            "packaging weight bool as integer",
            lambda payload: payload["packaging_policy"].__setitem__(
                "loose_server_or_voice_model_weights_included", 0
            ),
        ),
        (
            "source byte count as float",
            lambda payload: payload["source_inputs"][0].__setitem__(
                "bytes", float(payload["source_inputs"][0]["bytes"])
            ),
        ),
    )
    for _label, mutate in manifest_mutations:
        manifest = json.loads(json.dumps(original_manifest))
        mutate(manifest)
        write(manifest_path, json.dumps(manifest, sort_keys=True))
        with pytest.raises(
            validator.ValidationError,
            match="release state|authentication|blocker|packaging|provenance",
        ):
            validator.validate_full_rc(
                source, output, *validation_tool_args(apksigner)
            )

    receipt_mutations = (
        (
            "receipt exit code as boolean",
            lambda payload: payload.__setitem__("exit_code", False),
            "quality receipt",
        ),
        (
            "clean-source boolean as integer",
            lambda payload: payload["source"].__setitem__("tree_clean_before", 1),
            "quality receipt",
        ),
        (
            "step exit code as boolean",
            lambda payload: payload["steps"][0].__setitem__("exit_code", False),
            "quality step",
        ),
        (
            "environment presence as integer",
            lambda payload: payload["steps"][0]["environment"][
                "allowlisted_ambient_presence"
            ]["PATH"].__setitem__("present", 1),
            "ambient environment",
        ),
        (
            "provenance byte count as float",
            lambda payload: payload["provenance"]["runner"].__setitem__(
                "bytes", float(payload["provenance"]["runner"]["bytes"])
            ),
            "quality receipt",
        ),
        (
            "source inventory count as float",
            lambda payload: payload["inputs"]["tracked_source_before"].__setitem__(
                "file_count",
                float(payload["inputs"]["tracked_source_before"]["file_count"]),
            ),
            "quality receipt",
        ),
        (
            "tested distribution count as float",
            lambda payload: payload["tested_environment"][
                "installed_distributions"
            ].__setitem__(
                "count",
                float(
                    payload["tested_environment"]["installed_distributions"]["count"]
                ),
            ),
            "installed distribution identity",
        ),
    )
    for _label, mutate, expected_error in receipt_mutations:
        receipt = json.loads(json.dumps(original_voice_receipt))
        mutate(receipt)
        write(voice_receipt_path, json.dumps(receipt, sort_keys=True))
        write(manifest_path, json.dumps(original_manifest, sort_keys=True))
        rewrite_manifest_artifact_record(manifest_path, voice_receipt_relative)
        with pytest.raises(validator.ValidationError, match=expected_error):
            validator.validate_full_rc(
                source, output, *validation_tool_args(apksigner)
            )

    write(voice_receipt_path, original_voice_receipt_bytes)
    web_receipt_relative = original_manifest["quality"]["products"]["web"]["receipt"][
        "path"
    ]
    web_receipt_path = output / web_receipt_relative
    original_web_receipt_bytes = web_receipt_path.read_bytes()
    original_web_receipt = json.loads(original_web_receipt_bytes)
    web_receipt_mutations = (
        (
            lambda payload: payload["node_toolchain"]["checker"].__setitem__(
                "bytes",
                float(payload["node_toolchain"]["checker"]["bytes"]),
            ),
            "Node provenance",
        ),
        (
            lambda payload: payload["node_toolchain"]["before"]["root"][
                "closure"
            ].__setitem__(
                "bytes",
                float(
                    payload["node_toolchain"]["before"]["root"]["closure"][
                        "bytes"
                    ]
                ),
            ),
            "Node attestation",
        ),
        (
            lambda payload: payload["outputs"][0].__setitem__(
                "bytes", float(payload["outputs"][0]["bytes"])
            ),
            "quality output",
        ),
    )
    for mutate, expected_error in web_receipt_mutations:
        receipt = json.loads(json.dumps(original_web_receipt))
        mutate(receipt)
        write(web_receipt_path, json.dumps(receipt, sort_keys=True))
        write(manifest_path, json.dumps(original_manifest, sort_keys=True))
        rewrite_manifest_artifact_record(manifest_path, web_receipt_relative)
        with pytest.raises(validator.ValidationError, match=expected_error):
            validator.validate_full_rc(
                source, output, *validation_tool_args(apksigner)
            )

    write(web_receipt_path, original_web_receipt_bytes)
    manifest = json.loads(json.dumps(original_manifest))
    manifest["files"][0]["bytes"] = float(manifest["files"][0]["bytes"])
    canonical = "".join(
        f"{record['sha256']} {record['bytes']} {record['path']}\n"
        for record in sorted(manifest["files"], key=lambda item: item["path"])
    ).encode()
    manifest["closure"]["sha256"] = hashlib.sha256(canonical).hexdigest()
    write(manifest_path, json.dumps(manifest, sort_keys=True))
    with pytest.raises(validator.ValidationError, match="byte count"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_rejects_step_log_exit_code_type_confusion(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    receipt_relative = manifest["quality"]["products"]["voice"]["receipt"]["path"]
    receipt_path = output / receipt_relative
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    log_record = receipt["steps"][0]["log"]
    log_relative = f"quality/voice/{log_record['path']}"
    log_path = output / log_relative
    log = json.loads(log_path.read_text(encoding="utf-8"))
    log["exit_code"] = False
    write(log_path, json.dumps(log, sort_keys=True))
    log_record["bytes"] = log_path.stat().st_size
    log_record["sha256"] = sha256(log_path)
    write(receipt_path, json.dumps(receipt, sort_keys=True))
    rewrite_manifest_artifact_record(manifest_path, log_relative)
    rewrite_manifest_artifact_record(manifest_path, receipt_relative)

    with pytest.raises(validator.ValidationError, match="quality log"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_rejects_numeric_quality_executable_sha256(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    receipt_relative = manifest["quality"]["products"]["voice"]["receipt"]["path"]
    receipt_path = output / receipt_relative
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    numeric_sha256 = int("1" * 64)
    original_sha256 = receipt["tested_environment"]["python"]["sha256"]
    receipt["tested_environment"]["python"]["sha256"] = numeric_sha256
    log_relatives = []
    for step in receipt["steps"]:
        if step["executable"]["sha256"] != original_sha256:
            continue
        step["executable"]["sha256"] = numeric_sha256
        log_relative = f"quality/voice/{step['log']['path']}"
        log_path = output / log_relative
        log = json.loads(log_path.read_text(encoding="utf-8"))
        log["executable"]["sha256"] = numeric_sha256
        write(log_path, json.dumps(log, sort_keys=True))
        step["log"]["bytes"] = log_path.stat().st_size
        step["log"]["sha256"] = sha256(log_path)
        log_relatives.append(log_relative)
    assert log_relatives
    write(receipt_path, json.dumps(receipt, sort_keys=True))
    for relative in log_relatives:
        rewrite_manifest_artifact_record(manifest_path, relative)
    rewrite_manifest_artifact_record(manifest_path, receipt_relative)

    with pytest.raises(validator.ValidationError, match="quality step|tested Python"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_rejects_component_record_contract_mutations(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    original = json.loads(manifest_path.read_text(encoding="utf-8"))
    metadata_fields = (
        ("android", "deployment_blocker"),
        ("web", "runtime_unit"),
        ("web", "configuration_example"),
        ("backend", "runtime_unit"),
        ("backend", "migration_unit"),
        ("backend", "configuration_example"),
        ("backend", "migration_configuration_example"),
        ("backend", "issuer_binding_unit"),
        ("backend", "issuer_binding_cli"),
        ("voice", "runtime_unit"),
        ("voice", "configuration_example"),
    )
    for component, field in metadata_fields:
        manifest = json.loads(json.dumps(original))
        manifest["components"][component][field] = "none"
        write(manifest_path, json.dumps(manifest, sort_keys=True))
        with pytest.raises(validator.ValidationError, match="component metadata"):
            validator.validate_full_rc(source, output, *validation_tool_args(apksigner))

    manifest = json.loads(json.dumps(original))
    manifest["components"]["web"]["deployment_allowed"] = True
    write(manifest_path, json.dumps(manifest, sort_keys=True))
    with pytest.raises(validator.ValidationError, match="component record fields"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))

    manifest = json.loads(json.dumps(original))
    del manifest["components"]["backend"]["migration_unit"]
    write(manifest_path, json.dumps(manifest, sort_keys=True))
    with pytest.raises(validator.ValidationError, match="component record fields"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_rejects_full_rc_artifact_record_overclaims(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    original = json.loads(manifest_path.read_text(encoding="utf-8"))
    mutations = (
        lambda payload: payload["components"]["android"]["artifact"].__setitem__(
            "signed", True
        ),
        lambda payload: payload["sbom"].__setitem__("signed", True),
        lambda payload: payload["source_inputs"][0].__setitem__(
            "independently_verified", True
        ),
        lambda payload: payload["files"][0].__setitem__("safe_to_deploy", True),
        lambda payload: payload["quality"]["products"]["web"]["receipt"].__setitem__(
            "signed", True
        ),
        lambda payload: payload["quality"]["products"]["web"]["evidence_files"][0].__setitem__(
            "safe_to_deploy", True
        ),
        lambda payload: payload["components"]["web"]["evidence_files"][0].__setitem__(
            "deployment_ready", True
        ),
    )
    for mutate in mutations:
        manifest = json.loads(json.dumps(original))
        mutate(manifest)
        write(manifest_path, json.dumps(manifest, sort_keys=True))
        with pytest.raises(validator.ValidationError, match="exact record contract"):
            validator.validate_full_rc(source, output, *validation_tool_args(apksigner))

    manifest = json.loads(json.dumps(original))
    manifest["components"]["web"]["evidence_files"].append(
        dict(manifest["components"]["web"]["evidence_files"][0])
    )
    write(manifest_path, json.dumps(manifest, sort_keys=True))
    with pytest.raises(validator.ValidationError, match="evidence file set"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_rejects_web_manifest_record_overclaims(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    web_manifest_path = output / "web/web-build-manifest.json"
    outer_original = json.loads(manifest_path.read_text(encoding="utf-8"))
    web_original = json.loads(web_manifest_path.read_text(encoding="utf-8"))

    def swap_receipt_names(payload: dict[str, object]) -> None:
        receipts = payload["quality_receipts"]
        assert isinstance(receipts, list)
        npm_ci = next(row for row in receipts if row["name"] == "npm-ci")
        npm_audit = next(row for row in receipts if row["name"] == "npm-audit")
        npm_ci["name"], npm_audit["name"] = npm_audit["name"], npm_ci["name"]

    mutations = (
        (
            lambda payload: payload.__setitem__("deployment_complete", True),
            "fields or source identity",
        ),
        (
            lambda payload: payload["inputs"]["package_json"].__setitem__(
                "independently_verified", True
            ),
            "exact record contract",
        ),
        (
            lambda payload: payload["quality_receipts"][0].__setitem__("signed", True),
            "exact record contract",
        ),
        (
            lambda payload: payload["deployment_archive"].__setitem__(
                "deployment_ready", True
            ),
            "exact record contract",
        ),
        (
            lambda payload: payload["files"][0].__setitem__("safe_to_deploy", True),
            "exact record contract",
        ),
        (
            lambda payload: payload["deployment_archive"].__setitem__(
                "name", "signed-production-release.tar.gz"
            ),
            "name is not canonical",
        ),
        (
            lambda payload: payload["deployment_archive"].__setitem__(
                "bytes", float(payload["deployment_archive"]["bytes"])
            ),
            "record types",
        ),
        (swap_receipt_names, "receipt name does not match"),
    )
    for mutate, expected_error in mutations:
        web_manifest = json.loads(json.dumps(web_original))
        mutate(web_manifest)
        write(web_manifest_path, json.dumps(web_manifest, sort_keys=True))
        write(manifest_path, json.dumps(outer_original, sort_keys=True))
        rewrite_manifest_artifact_record(
            manifest_path,
            "web/web-build-manifest.json",
        )
        with pytest.raises(validator.ValidationError, match=expected_error):
            validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validation_receipt_binds_validator_manifest_closure_and_blockers(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    result = validator.validate_full_rc(source, output, *validation_tool_args(apksigner))
    receipt_path = tmp_path / "operator" / "validation-receipt.json"
    receipt_path.parent.mkdir(mode=0o700)
    validator.write_validation_receipt(
        receipt_path=receipt_path,
        source_root=source,
        rc_root=output,
        result=result,
    )
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))

    assert receipt["schema_version"] == "walksafe.full-rc-validation-receipt.v1"
    assert receipt["result"] == "passed"
    assert receipt["source"] == {
        "commit": git(source, "rev-parse", "HEAD"),
        "tree": git(source, "rev-parse", "HEAD^{tree}"),
    }
    assert receipt["manifest"]["sha256"] == sha256(output / receipt["manifest"]["path"])
    assert receipt["closure_sha256"] == result["closure_sha256"]
    assert receipt["blocker_ids"] == result["blocker_ids"]
    assert receipt["quality"] == result["quality"]
    for record in receipt["quality"]["products"].values():
        copied = receipt_path.parent / record["path"]
        assert copied.is_file()
        assert sha256(copied) == record["sha256"]
    assert receipt["deployment_complete"] is False


def test_validation_receipt_rejects_manifest_replaced_after_validation(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    result = validator.validate_full_rc(source, output, *validation_tool_args(apksigner))
    validated_manifest_sha256 = result["validated_rc"]["manifest"]["sha256"]
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["components"]["android"]["deployable"] = True
    replacement = manifest_path.with_name(".walksafe-full-rc-manifest.replacement.json")
    write(
        replacement,
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    )
    os.replace(replacement, manifest_path)
    receipt_path = tmp_path / "operator" / "validation-receipt.json"
    receipt_path.parent.mkdir(mode=0o700)

    assert sha256(manifest_path) != validated_manifest_sha256
    with pytest.raises(validator.ValidationError, match="changed after validation"):
        validator.write_validation_receipt(
            receipt_path=receipt_path,
            source_root=source,
            rc_root=output,
            result=result,
        )

    assert not receipt_path.exists()


def test_validator_rejects_quality_receipt_with_stale_source_inventory(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    relative = manifest["quality"]["products"]["voice"]["receipt"]["path"]
    receipt_path = output / relative
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    receipt["inputs"]["tracked_source_before"]["sha256"] = "0" * 64
    write(receipt_path, json.dumps(receipt, sort_keys=True))
    rewrite_manifest_artifact_record(manifest_path, relative)

    with pytest.raises(validator.ValidationError, match="clean source HEAD"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


@pytest.mark.parametrize("phase", ["before", "after"])
def test_validator_rejects_web_quality_node_attestation_drift(
    tmp_path: Path,
    phase: str,
) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    relative = manifest["quality"]["products"]["web"]["receipt"]["path"]
    receipt_path = output / relative
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    receipt["node_toolchain"][phase]["npm"]["version"] = "0.0.0"
    write(receipt_path, json.dumps(receipt, sort_keys=True))
    rewrite_manifest_artifact_record(manifest_path, relative)

    with pytest.raises(validator.ValidationError, match="Node attestation"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_rejects_forged_installed_distribution_inventory(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    relative = manifest["quality"]["products"]["voice"]["receipt"]["path"]
    receipt_path = output / relative
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    receipt["tested_environment"]["installed_distributions"]["count"] += 1
    write(receipt_path, json.dumps(receipt, sort_keys=True))
    rewrite_manifest_artifact_record(manifest_path, relative)

    with pytest.raises(validator.ValidationError, match="installed distribution identity"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_rejects_forged_site_directory_closure(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    relative = manifest["quality"]["products"]["voice"]["receipt"]["path"]
    receipt_path = output / relative
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    receipt["tested_environment"]["site_packages"]["format"] = "files-only"
    write(receipt_path, json.dumps(receipt, sort_keys=True))
    rewrite_manifest_artifact_record(manifest_path, relative)

    with pytest.raises(validator.ValidationError, match="site-packages record"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_rejects_resigned_site_closure_outside_source_policy(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    relative = manifest["quality"]["products"]["voice"]["receipt"]["path"]
    receipt_path = output / relative
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    receipt["tested_environment"]["site_packages"]["closure"]["sha256"] = "0" * 64
    write(receipt_path, json.dumps(receipt, sort_keys=True))
    rewrite_manifest_artifact_record(manifest_path, relative)

    with pytest.raises(validator.ValidationError, match="source-pinned policy"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_rejects_stale_android_dex_with_appended_current_commit(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    source_commit = git(source, "rev-parse", "HEAD")
    manifest_path = output / "walksafe-full-rc-manifest.json"
    relative = "android/app-release-unsigned.apk"
    android_apk = output / relative
    rewrite_apk(
        android_apk,
        {"classes2.dex": build_config_dex("0" * 40)},
        {"assets/forged-source.txt": source_commit.encode()},
    )
    rewrite_manifest_artifact_record(manifest_path, relative)

    with pytest.raises(validator.ValidationError, match="BuildConfig release binding"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_rejects_archive_whose_bytes_changed_even_if_manifest_hash_is_updated(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    relative = manifest["components"]["backend"]["artifact"]["path"]
    archive = output / relative
    make_tar(archive, [("walksafe-backend/backend/app/main.py", b"tampered", "file", 0)])
    rewrite_manifest_artifact_record(manifest_path, relative)
    with pytest.raises(validator.ValidationError, match="source|set"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_sbom_covers_python_web_and_android_dependency_inputs(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    validator.validate_full_rc(source, output, *validation_tool_args(apksigner))
    sbom = json.loads((output / "sbom/walksafe-full-rc.spdx.json").read_text(encoding="utf-8"))
    sources = {package.get("comment") for package in sbom["packages"]}
    assert "source=backend/requirements.lock#package/fastapi" in sources
    assert "source=backend/requirements.lock#package/uvicorn" in sources
    assert "source=voice/requirements.lock#package/fastapi" in sources
    assert "source=apps/web/package-lock.json#packages/node_modules/next" in sources
    next_package = next(
        package
        for package in sbom["packages"]
        if package.get("comment") == "source=apps/web/package-lock.json#packages/node_modules/next"
    )
    assert next_package["checksums"] == [
        {"algorithm": "SHA512", "checksumValue": (b"n" * 64).hex()}
    ]
    assert (
        "source=apps/android/app/gradle.lockfile#"
        "releaseRuntimeClasspath/androidx.core:core-ktx:1.18.0"
    ) in sources
    assert (
        "source=apps/android/app/src/main/assets/model-config/two_model_runtime.json#"
        "model/unified_walksafe"
    ) in sources


def test_validator_rejects_sbom_exact_contract_mutations(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    relative = "sbom/walksafe-full-rc.spdx.json"
    sbom_path = output / relative
    original_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    original_sbom = json.loads(sbom_path.read_text(encoding="utf-8"))

    def dependency(payload: dict[str, object]) -> dict[str, object]:
        packages = payload["packages"]
        assert isinstance(packages, list)
        return next(
            package
            for package in packages
            if isinstance(package, dict) and "externalRefs" in package
        )

    def append_unexpected_package(payload: dict[str, object]) -> None:
        packages = payload["packages"]
        assert isinstance(packages, list)
        forged = json.loads(json.dumps(packages[0]))
        forged["SPDXID"] = "SPDXRef-Forged-Deployment-Claim"
        forged["comment"] = "source=forged/deployment-claim.json"
        packages.append(forged)

    def duplicate_relationship(payload: dict[str, object]) -> None:
        relationships = payload["relationships"]
        assert isinstance(relationships, list)
        relationships.append(json.loads(json.dumps(relationships[0])))

    mutations = (
        (
            lambda payload: payload.__setitem__("deployment_ready", True),
            "top-level fields",
        ),
        (
            lambda payload: payload["creationInfo"].__setitem__("reviewed", True),
            "creation metadata",
        ),
        (
            lambda payload: payload["packages"][0].__setitem__(
                "independently_verified", True
            ),
            "package differs",
        ),
        (
            lambda payload: payload["packages"][0]["checksums"][0].__setitem__(
                "trusted", True
            ),
            "package differs",
        ),
        (
            lambda payload: dependency(payload)["externalRefs"][0].__setitem__(
                "verified", True
            ),
            "package differs",
        ),
        (
            lambda payload: dependency(payload)["externalRefs"][0].__setitem__(
                "referenceLocator", "pkg:generic/forged@1"
            ),
            "package differs",
        ),
        (
            lambda payload: payload["relationships"][0].__setitem__(
                "approved", True
            ),
            "relationship fields",
        ),
        (
            lambda payload: payload["relationships"][0].__setitem__(
                "relationshipType", "DEPLOYMENT_APPROVED"
            ),
            "relationship multiset",
        ),
        (append_unexpected_package, "package set"),
        (duplicate_relationship, "relationship multiset"),
        (
            lambda payload: payload["packages"][0].__setitem__(
                "name", "production-ready"
            ),
            "package differs",
        ),
        (
            lambda payload: payload["packages"][0].pop("checksums"),
            "package differs",
        ),
        (
            lambda payload: payload["packages"][0].__setitem__("filesAnalyzed", 0),
            "package differs",
        ),
    )
    for mutate, expected_error in mutations:
        sbom = json.loads(json.dumps(original_sbom))
        mutate(sbom)
        write(sbom_path, json.dumps(sbom, sort_keys=True))
        write(manifest_path, json.dumps(original_manifest, sort_keys=True))
        rewrite_manifest_artifact_record(manifest_path, relative)
        with pytest.raises(validator.ValidationError, match=expected_error):
            validator.validate_full_rc(
                source, output, *validation_tool_args(apksigner)
            )


def test_python_lock_must_preserve_every_direct_requirement_with_hashes(tmp_path: Path) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    write(
        source / "backend/requirements.lock",
        f"fastapi==1.2.3 --hash=sha256:{'a' * 64}\n",
    )

    direct = builder._parse_python_requirements(
        source / "backend/requirements.txt",
        "backend/requirements.txt",
    )
    with pytest.raises(builder.ReleaseBuildError, match="uvicorn"):
        builder._parse_python_lock(
            source / "backend/requirements.lock",
            "backend/requirements.lock",
            direct,
        )


def test_web_lock_dependency_requires_sha512_integrity(tmp_path: Path) -> None:
    lock = tmp_path / "package-lock.json"
    write(
        lock,
        json.dumps(
            {
                "lockfileVersion": 3,
                "packages": {"node_modules/next": {"version": "16.2.6"}},
            }
        ),
    )

    with pytest.raises(builder.ReleaseBuildError, match="SHA-512 integrity"):
        builder._parse_web_lock(lock)


def test_android_release_lock_requires_verification_hashes(tmp_path: Path) -> None:
    source = tmp_path / "source"
    create_source_repo(source)
    write(
        source / "apps/android/app/gradle.lockfile",
        "example:missing:1.0.0=releaseRuntimeClasspath\n",
    )

    with pytest.raises(builder.ReleaseBuildError, match="unverified"):
        builder._parse_android_dependencies(source)


def test_signing_gate_rejects_user_owned_java_runtime_and_compares_payload_bytes(tmp_path: Path) -> None:
    _source, output, unsigned_apk, apksigner = build_fixture(tmp_path)
    signed_apk = tmp_path / "signed.apk"
    shutil.copyfile(unsigned_apk, signed_apk)
    java, _java_sha256, _jar, _jar_sha256 = validation_tool_args(apksigner)
    fake_java_home = tmp_path / "user-jre"
    fake_java = fake_java_home / "bin" / "java"
    fake_java.parent.mkdir(parents=True)
    shutil.copyfile(java, fake_java)
    fake_java.chmod(0o755)

    with pytest.raises(signing_gate.ReleaseIntegrityError, match="root-owned"):
        signing_gate.root_owned_system_trust(
            fake_java,
            context="Java runtime",
            tree_root=fake_java_home,
        )
    assert signing_gate._payload_entries(signed_apk) == signing_gate._payload_entries(
        output / "android/app-release-unsigned.apk"
    )
    rewrite_apk(signed_apk, {"classes.dex": b"changed-after-validation"})
    assert signing_gate._payload_entries(signed_apk) != signing_gate._payload_entries(
        output / "android/app-release-unsigned.apk"
    )


@pytest.mark.parametrize(
    ("protected", "message"),
    [
        ("source", "validated source"),
        ("rc", "full RC"),
        ("bundle", "validation bundle"),
    ],
)
def test_signing_receipt_must_not_publish_inside_verified_inputs(
    tmp_path: Path,
    protected: str,
    message: str,
) -> None:
    roots = {name: tmp_path / name for name in ("source", "rc", "bundle")}
    for root in roots.values():
        root.mkdir()
    receipt = roots[protected] / "signing-receipt.json"

    with pytest.raises(signing_gate.SigningGateError, match=message):
        signing_gate._validated_receipt_output(
            receipt,
            source_root=roots["source"],
            manifest_path=roots["rc"] / "walksafe-full-rc-manifest.json",
            validation_receipt_path=roots["bundle"] / "validation-receipt.json",
        )

    assert not receipt.exists()


@pytest.mark.parametrize(
    "payload",
    [
        b'{"outer":{"value":1,"value":2}}',
        b'{"value":NaN}',
        b'{"value":1e400}',
        b'{"value":-1e400}',
    ],
)
def test_strict_release_json_rejects_duplicates_and_nonfinite_numbers(payload: bytes) -> None:
    with pytest.raises(validator.ReleaseIntegrityError, match="unambiguous UTF-8 JSON"):
        validator.strict_json_bytes(payload, context="attack JSON")


def test_validator_parses_manifest_and_quality_receipt_from_strict_snapshot_bytes(
    tmp_path: Path,
) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    manifest_path = output / "walksafe-full-rc-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    relative = manifest["quality"]["products"]["voice"]["receipt"]["path"]
    receipt_path = output / relative
    original = receipt_path.read_text(encoding="utf-8")
    receipt_path.write_text(
        original.replace('"result": "passed"', '"result": "failed", "result": "passed"', 1),
        encoding="utf-8",
    )
    rewrite_manifest_artifact_record(manifest_path, relative)

    with pytest.raises(validator.ValidationError, match="unambiguous UTF-8 JSON"):
        validator.validate_full_rc(source, output, *validation_tool_args(apksigner))


def test_validator_rejects_apksigner_jar_inode_swap_even_when_original_bytes_return(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    java, java_sha256, real_jar, _real_jar_sha256 = validation_tool_args(apksigner)
    jar = tmp_path / "tool" / "apksigner.jar"
    jar.parent.mkdir()
    shutil.copyfile(real_jar, jar)
    jar_sha256 = sha256(jar)
    original = jar.read_bytes()

    def swap_jar(*_args, **_kwargs):
        forged = jar.with_name(".apksigner-forged.jar")
        forged.write_bytes(b"forged apksigner jar")
        os.replace(forged, jar)
        restored = jar.with_name(".apksigner-restored.jar")
        restored.write_bytes(original)
        restored.chmod(0o664)
        os.replace(restored, jar)

    monkeypatch.setattr(validator, "_validate_unsigned_apk", swap_jar)
    with pytest.raises(validator.ValidationError, match="changed during unsigned APK"):
        validator.validate_full_rc(
            source,
            output,
            java,
            java_sha256,
            jar,
            jar_sha256,
        )


def test_validation_receipt_rechecks_every_published_quality_copy(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    result = validator.validate_full_rc(source, output, *validation_tool_args(apksigner))
    bundle = tmp_path / "validation-bundle"
    bundle.mkdir(mode=0o700)
    receipt_path = bundle / "validation-receipt.json"
    real_publish = validator.publish_snapshot
    corrupted = False

    def corrupt_after_publish(path, snapshot, *, mode=0o644):
        nonlocal corrupted
        real_publish(path, snapshot, mode=mode)
        if not corrupted:
            path.write_bytes(b"corrupted after copy\n")
            corrupted = True

    monkeypatch.setattr(validator, "publish_snapshot", corrupt_after_publish)
    with pytest.raises(validator.ValidationError, match="quality receipt changed"):
        validator.write_validation_receipt(
            receipt_path=receipt_path,
            source_root=source,
            rc_root=output,
            result=result,
        )
    assert not receipt_path.exists()


def test_validation_receipt_legacy_tmp_symlink_cannot_overwrite_victim(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    result = validator.validate_full_rc(source, output, *validation_tool_args(apksigner))
    bundle = tmp_path / "validation-bundle"
    bundle.mkdir(mode=0o700)
    victim = tmp_path / "victim.txt"
    victim.write_text("keep me\n", encoding="utf-8")
    (bundle / ".validation-receipt.json.tmp").symlink_to(victim)

    with pytest.raises(validator.ValidationError, match="must be empty"):
        validator.write_validation_receipt(
            receipt_path=bundle / "validation-receipt.json",
            source_root=source,
            rc_root=output,
            result=result,
        )
    assert victim.read_text(encoding="utf-8") == "keep me\n"


def test_validation_receipt_must_not_publish_inside_source(tmp_path: Path) -> None:
    source, output, _apk, apksigner = build_fixture(tmp_path)
    result = validator.validate_full_rc(source, output, *validation_tool_args(apksigner))
    bundle = source / "validation-output"
    bundle.mkdir()
    receipt = bundle / "validation-receipt.json"

    with pytest.raises(validator.ValidationError, match="outside the validated source"):
        validator.write_validation_receipt(
            receipt_path=receipt,
            source_root=source,
            rc_root=output,
            result=result,
        )

    assert not receipt.exists()
    assert not any(bundle.iterdir())


def test_directory_no_replace_publication_preserves_competitor(tmp_path: Path) -> None:
    prepared = tmp_path / ".prepared"
    prepared.mkdir()
    write(prepared / "artifact.txt", "ours\n")
    competitor = tmp_path / "release"
    competitor.mkdir()
    competitor_inode = competitor.stat().st_ino

    with pytest.raises(builder.ReleaseIntegrityError, match="created concurrently"):
        builder.exclusive_directory_publish(prepared, competitor)

    assert competitor.stat().st_ino == competitor_inode
    assert prepared.is_dir()


def test_directory_snapshot_rejects_line_break_in_filename(tmp_path: Path) -> None:
    tree = tmp_path / "tree"
    tree.mkdir()
    write(tree / "ambiguous\nrecord.txt")

    with pytest.raises(validator.ReleaseIntegrityError, match="path is not canonical"):
        validator.DirectorySnapshot.capture(tree, context="attack tree")


def test_runtime_archive_never_follows_existing_output_symlink(tmp_path: Path) -> None:
    source = tmp_path / "source"
    write(source / "payload.txt", "archive payload\n")
    victim = tmp_path / "victim.bin"
    victim.write_bytes(b"keep me")
    output = tmp_path / "runtime.tar.gz"
    output.symlink_to(victim)

    with pytest.raises(builder.ReleaseBuildError, match="already exists"):
        builder.create_deterministic_tar_gz(
            output,
            source_root=source,
            archive_root_name="runtime",
            relative_files=["payload.txt"],
        )

    assert victim.read_bytes() == b"keep me"


def test_builder_json_never_follows_existing_output_symlink(tmp_path: Path) -> None:
    victim = tmp_path / "victim.json"
    victim.write_text("keep me\n", encoding="utf-8")
    output = tmp_path / "manifest.json"
    output.symlink_to(victim)

    with pytest.raises(builder.ReleaseBuildError, match="already exists"):
        builder._write_json(output, {"verified": True})

    assert victim.read_text(encoding="utf-8") == "keep me\n"
