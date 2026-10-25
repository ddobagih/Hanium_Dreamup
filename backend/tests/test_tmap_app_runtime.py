"""App-owned TMAP lifecycle and health call boundaries; no live provider work."""

from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
from types import SimpleNamespace

import pytest


ROOT = Path(__file__).resolve().parents[2]
APP_CHILD_SAFETY_SECONDS = 12.0
APP_CHILD_CLEANUP_SECONDS = 1.0


class OwnedAppCheckFailed(AssertionError):
    def __init__(self, check_code):
        self.check_code = check_code
        super().__init__(check_code)


class OwnedRuntimeStartFailure(RuntimeError):
    pass


class OwnedRuntimeCloseFailure(RuntimeError):
    pass


class OwnedHealthStopFailure(RuntimeError):
    pass


def _expect(condition, code, checks):
    if not condition:
        raise OwnedAppCheckFailed(code)
    checks.append(code)


def _exercise_owned_app(scenario, root, guards, progress):
    """Execute production main import and lifespan inside an isolated child."""
    from sqlalchemy.engine import Engine

    # The bootstrap installs these before importing this module or main.
    _expect(
        Engine.connect is progress["database_guard"],
        "child_database_guard_installed", [],
    )
    events, checks = [], []
    imports = {}
    runtime_instances = []
    router_calls = {"health": [], "navigation": []}
    observed = {}
    holder = {}
    root = Path(root)

    with pytest.MonkeyPatch.context() as patch:
        progress["phase"] = "test_settings"
        from backend.app import config

        settings = config.get_settings()
        _expect(
            settings.walksafe_environment in {"development", "test"},
            "settings_are_not_deployment", checks,
        )
        # Existing guarded pytest settings supply validation/key configuration.
        # Do not invent key material or serialize Settings into another process.
        owned_upload = root / "uploads"
        owned_raw = root / "raw"
        for name, value in (
            ("upload_dir", owned_upload),
            ("upload_backup_reader_group_gid", None),
            ("raw_object_dir", owned_raw),
            ("capacity_measurement_interval_seconds", 60.0),
            ("capacity_state_ttl_seconds", 60.0),
            ("capacity_version_state_path", root / "capacity-version.json"),
        ):
            patch.setattr(settings, name, value)
        patch.setattr(config, "get_settings", lambda: settings)

        progress["phase"] = "import_factory_setup"
        from backend.app.api import detect, health, navigation
        from backend.app.services import capacity_state as capacity_service
        from backend.app.services import detect_v2
        from backend.app.services import raw_collection_storage as raw_storage
        from backend.app.services import report_image_keys
        from backend.app.services import tmap_runtime
        from backend.app.services import yolo_inference_adapter

        def forbidden_model_load(*args, **kwargs):
            guards["model_load"] += 1
            raise RuntimeError("owned app test forbids model loading")

        patch.setattr(detect_v2, "load_yolo_model", forbidden_model_load)
        patch.setattr(yolo_inference_adapter, "load_yolo_model", forbidden_model_load)

        class KeyManager:
            def __init__(self):
                self.keyring = SimpleNamespace(slots=())

            def readiness(self):
                return {"ready": True}

        key_manager = KeyManager()

        def make_keys(received):
            imports.setdefault("keys", []).append(received)
            return key_manager

        def prepare_raw(received):
            imports.setdefault("prepare_raw", []).append(received)

        class RawStorage:
            def __init__(
                self, *, raw_object_dir, privacy_hmac_secret, key_manager,
                capacity_state,
            ):
                imports.setdefault("raw_storage", []).append({
                    "root": raw_object_dir,
                    "key_manager": key_manager,
                    "capacity_state": capacity_state,
                })

        class InferenceCleanup:
            def close(self):
                events.append("inference.close")

        inference = InferenceCleanup()

        def make_inference(received):
            imports.setdefault("inference", []).append(received)
            return inference

        real_capacity_state = capacity_service.capacity_state_from_environment

        def make_capacity_state(environment, *, live_monitor_enabled):
            state = real_capacity_state(
                environment, live_monitor_enabled=live_monitor_enabled,
            )
            imports.setdefault("capacity_state", []).append(
                (environment, live_monitor_enabled, state),
            )
            return state

        def same_filesystem(upload_root, raw_root):
            imports.setdefault("same_filesystem", []).append((upload_root, raw_root))

        class CapacityMonitor:
            def __init__(
                self, *, state, upload_dir, version_state_path,
                interval_seconds, ttl_seconds,
            ):
                self.interval_seconds = interval_seconds
                imports.setdefault("capacity_monitor", []).append({
                    "state": state, "upload_dir": upload_dir,
                    "version_state_path": version_state_path,
                    "interval_seconds": interval_seconds, "ttl_seconds": ttl_seconds,
                })

            def measure_once(self):
                events.append("capacity.measure")

        class Runtime:
            def __init__(self, *, timeout_seconds):
                self.timeout_seconds = timeout_seconds
                self.acquired = False
                self.closed = False
                runtime_instances.append(self)
                events.append("runtime.construct")

            async def start(self):
                events.append("runtime.start")
                observed["state_during_start"] = holder["main"].app.state.tmap_runtime
                self.acquired = True
                await asyncio.sleep(0)
                if scenario == "partial_start_failure":
                    raise OwnedRuntimeStartFailure()

            def stop_admission(self):
                events.append("runtime.stop_admission")
                observed["state_during_stop"] = holder["main"].app.state.tmap_runtime

            async def aclose(self):
                events.append("runtime.aclose")
                await asyncio.sleep(0)
                if scenario == "runtime_close_failure":
                    raise OwnedRuntimeCloseFailure()
                self.closed = True
                self.acquired = False

        patch.setattr(report_image_keys, "create_report_image_key_manager", make_keys)
        patch.setattr(raw_storage, "prepare_raw_object_directory", prepare_raw)
        patch.setattr(raw_storage, "RawCollectionStorage", RawStorage)
        patch.setattr(detect, "create_inference_runner", make_inference)
        patch.setattr(capacity_service, "capacity_state_from_environment", make_capacity_state)
        patch.setattr(capacity_service, "require_same_capacity_filesystem", same_filesystem)
        patch.setattr(capacity_service, "FilesystemCapacityMonitor", CapacityMonitor)
        patch.setattr(tmap_runtime, "TmapRuntime", Runtime)

        real_health_factory = health.create_router
        real_navigation_factory = navigation.create_router

        def make_health_router(*args, **kwargs):
            router = real_health_factory(*args, **kwargs)
            router_calls["health"].append((args, kwargs.get("runtime_provider"), router))
            return router

        def make_navigation_router(*args, **kwargs):
            router = real_navigation_factory(*args, **kwargs)
            router_calls["navigation"].append((args, kwargs.get("runtime_provider"), router))
            return router

        patch.setattr(health, "create_router", make_health_router)
        patch.setattr(navigation, "create_router", make_navigation_router)

        progress["phase"] = "production_main_import"
        _expect("backend.app.main" not in sys.modules, "main_import_is_fresh", checks)
        from backend.app import main
        from fastapi.routing import APIRoute

        holder["main"] = main
        from backend.app import application_runtime as runtime_module
        owner = main.app.state.runtime
        _expect(not owned_upload.exists(), "factory_has_no_upload_io", checks)
        owner.initialize_resources()
        patch.setattr(owner, "initialize_resources", lambda: None)
        _expect(main.settings is settings, "main_uses_owned_test_settings", checks)
        _expect(main.settings.upload_dir == owned_upload, "owned_upload_path", checks)
        _expect(owned_upload.is_dir(), "production_import_created_owned_upload", checks)
        _expect(owner.raw_collection_storage is not None, "raw_factory_result_installed", checks)
        _expect(owner.inference_runner is inference, "owned_inference_cleanup_installed", checks)
        _expect(len(imports.get("keys", [])) == 1, "key_factory_called_once", checks)
        _expect(len(imports.get("prepare_raw", [])) == 1, "raw_prepare_called_once", checks)
        _expect(len(imports.get("raw_storage", [])) == 1, "raw_factory_called_once", checks)
        _expect(len(imports.get("inference", [])) == 1, "inference_factory_called_once", checks)
        _expect(len(imports.get("capacity_state", [])) == 1, "capacity_state_factory_called_once", checks)
        _expect(len(imports.get("capacity_monitor", [])) == 1, "capacity_monitor_factory_called_once", checks)
        _expect(
            imports.get("same_filesystem") == [(owned_upload, owned_raw)],
            "same_filesystem_arguments_are_owned", checks,
        )
        _expect(
            imports["raw_storage"][0]["root"] == owned_raw
            and imports["raw_storage"][0]["key_manager"] is key_manager
            and imports["raw_storage"][0]["capacity_state"] is owner.capacity_state,
            "raw_factory_dependencies_match", checks,
        )
        _expect(
            imports["capacity_monitor"][0]["upload_dir"] == owned_upload
            and imports["capacity_monitor"][0]["version_state_path"] == root / "capacity-version.json",
            "capacity_monitor_paths_are_owned", checks,
        )

        providers = {}
        application_endpoints = {
            route.endpoint for route in main.app.routes if isinstance(route, APIRoute)
        }
        for name in ("health", "navigation"):
            _expect(len(router_calls[name]) == 1, name + "_real_factory_called_once", checks)
            args, provider, router = router_calls[name][0]
            _expect(args[0] is settings, name + "_factory_settings_match", checks)
            _expect(callable(provider), name + "_provider_was_supplied", checks)
            providers[name] = provider
            endpoints = {
                route.endpoint for route in router.routes if isinstance(route, APIRoute)
            }
            _expect(bool(endpoints), name + "_real_router_is_not_empty", checks)
            _expect(
                endpoints <= application_endpoints,
                name + "_real_router_endpoints_in_main", checks,
            )
            _expect(provider() is None, name + "_owner_absent_before_start", checks)

        def sync_step(name):
            def invoke():
                events.append(name)
            return invoke

        for attribute, event in (
            ("validate_admin_credential_issuer_binding", "credential"),
            ("bind_privacy_hmac_key", "privacy"),
            ("bind_account_crypto_keys", "account"),
            ("reconcile_report_storage", "report"),
            ("reconcile_raw_collection_storage", "raw"),
        ):
            patch.setattr(owner, attribute, sync_step(event))

        def start_health(received):
            events.append("health.start")
            observed["health_settings"] = received
            observed["state_during_health_start"] = main.app.state.tmap_runtime

        def stop_health():
            events.append("health.stop")
            observed["state_during_health_stop"] = main.app.state.tmap_runtime
            if scenario == "health_stop_failure":
                raise OwnedHealthStopFailure()

        patch.setattr(runtime_module, "start_detect_v2_health", start_health)
        patch.setattr(runtime_module, "stop_detect_v2_health", stop_health)

        class StorageMonitor:
            # S20-1: the lifespan owns a report-storage monitor task after the
            # startup reconcile; its real batches read the database, so the
            # child owns it with a spy instead of letting it reach the guard.
            async def run(self, stop_requested):
                events.append("storage.task.start")
                observed["storage_task"] = asyncio.current_task()
                try:
                    await stop_requested.wait()
                finally:
                    observed["storage_stop_requested"] = stop_requested.is_set()
                    events.append("storage.task.stop")

            def stop(self):
                events.append("storage.stop")

        patch.setattr(owner, "report_storage_monitor", StorageMonitor())
        real_capacity_monitor = owner.run_capacity_monitor

        async def observe_capacity_monitor(stop_requested):
            events.append("capacity.task.start")
            observed["capacity_task"] = asyncio.current_task()
            try:
                await real_capacity_monitor(stop_requested)
            finally:
                observed["capacity_stop_requested"] = stop_requested.is_set()
                events.append("capacity.task.stop")

        patch.setattr(owner, "run_capacity_monitor", observe_capacity_monitor)

        async def exercise_lifespan():
            entered = False
            caught = None
            try:
                async with owner.lifespan(main.app):
                    entered = True
                    events.append("lifespan.body")
                    _expect(len(runtime_instances) == 1, "one_runtime_constructed", checks)
                    runtime = runtime_instances[0]
                    _expect(main.app.state.tmap_runtime is runtime, "owner_registered_after_start", checks)
                    _expect(
                        providers["health"]() is runtime
                        and providers["navigation"]() is runtime,
                        "both_real_factories_follow_same_app_owner", checks,
                    )
            except (OwnedRuntimeStartFailure, OwnedRuntimeCloseFailure, OwnedHealthStopFailure) as exc:
                caught = type(exc).__name__
            expected_error = {
                "normal": None,
                "partial_start_failure": "OwnedRuntimeStartFailure",
                "runtime_close_failure": "OwnedRuntimeCloseFailure",
                "health_stop_failure": "OwnedHealthStopFailure",
            }[scenario]
            _expect(caught == expected_error, "injected_failure_contract", checks)
            _expect(
                entered is (scenario != "partial_start_failure"),
                "partial_start_does_not_enter_application_body", checks,
            )
            _expect(main.app.state.tmap_runtime is None, "owner_cleared_in_finally", checks)
            _expect(
                all(provider() is None for provider in providers.values()),
                "both_providers_observe_cleared_owner", checks,
            )
            _expect(
                events[:5] == ["credential", "privacy", "account", "report", "raw"],
                "existing_startup_order_preserved", checks,
            )
            for name in (
                "credential", "privacy", "account", "report", "raw",
                "capacity.measure", "capacity.task.start", "capacity.task.stop",
                "runtime.construct", "runtime.start", "runtime.stop_admission",
                "runtime.aclose", "health.stop", "inference.close",
                "storage.task.start", "storage.stop", "storage.task.stop",
            ):
                _expect(events.count(name) == 1, name + "_once", checks)
            _expect(
                events.index("raw") < events.index("storage.task.start")
                < events.index("runtime.start"),
                "storage_monitor_starts_after_startup_reconcile", checks,
            )
            _expect(
                events.index("storage.stop") < events.index("storage.task.stop")
                < events.index("runtime.stop_admission"),
                "storage_monitor_stops_before_runtime_cleanup", checks,
            )
            _expect(
                observed["storage_stop_requested"] is True
                and observed["storage_task"].done(),
                "actual_storage_task_received_stop_and_exited", checks,
            )
            _expect(
                events.count("health.start") == (0 if scenario == "partial_start_failure" else 1),
                "health_start_after_runtime_start_only", checks,
            )
            _expect(
                events.index("capacity.measure") < events.index("runtime.start"),
                "capacity_initial_measurement_precedes_runtime_start", checks,
            )
            _expect(
                events.index("runtime.stop_admission") < events.index("runtime.aclose")
                < events.index("health.stop") < events.index("capacity.task.stop")
                < events.index("inference.close"),
                "cleanup_order_survives_injected_failure", checks,
            )
            runtime = runtime_instances[0]
            _expect(runtime.timeout_seconds == settings.tmap_timeout_seconds, "dedicated_runtime_timeout_argument", checks)
            _expect(observed["state_during_start"] is None, "no_owner_published_during_partial_start", checks)
            _expect(observed["state_during_health_stop"] is None, "owner_cleared_before_health_stop", checks)
            if scenario != "partial_start_failure":
                _expect(
                    observed["health_settings"] is settings
                    and observed["state_during_health_start"] is runtime,
                    "health_start_observes_registered_owner", checks,
                )
            _expect(
                observed["capacity_stop_requested"] is True
                and observed["capacity_task"].done(),
                "actual_capacity_task_received_stop_and_exited", checks,
            )
            _expect(
                runtime.closed is (scenario != "runtime_close_failure"),
                "spy_close_failure_not_reported_as_success", checks,
            )

        progress["phase"] = "production_lifespan"
        asyncio.run(exercise_lifespan())
        _expect(
            guards == {"database": 0, "network": 0, "model_load": 0},
            "no_guarded_external_or_model_attempts", checks,
        )
        progress["phase"] = "completed"
        return {
            "ok": True, "scenario": scenario, "checks": checks,
            "events": events, "guards": dict(guards),
            "main_import": "fresh production module",
            "router_factories": "real health/navigation factories delegated",
            "lifecycle": "production owner.lifespan; dependency operations are owned spies",
            "runtime_internal_resource_shutdown_proven": False,
            "http_or_middleware_success_claimed": False,
        }


_CHILD_BOOTSTRAP = r"""
import json
import os
import socket
import sys
import pytest
from sqlalchemy.engine import Engine

report_fd = os.dup(sys.stdout.fileno())
os.dup2(sys.stderr.fileno(), sys.stdout.fileno())
guards = {"database": 0, "network": 0, "model_load": 0}
progress = {"phase": "bootstrap"}
cases = None
def deny_database(*args, **kwargs):
    guards["database"] += 1
    raise RuntimeError("owned app child forbids database connections")
def deny_network(*args, **kwargs):
    guards["network"] += 1
    raise RuntimeError("owned app child forbids external connections")
try:
    with pytest.MonkeyPatch.context() as guard:
        guard.setattr(Engine, "connect", deny_database)
        guard.setattr(Engine, "raw_connection", deny_database)
        guard.setattr(socket.socket, "connect", deny_network)
        guard.setattr(socket.socket, "connect_ex", deny_network)
        guard.setattr(socket, "create_connection", deny_network)
        guard.setattr(socket, "getaddrinfo", deny_network)
        progress["database_guard"] = deny_database
        from backend.tests import test_tmap_app_runtime as cases
        receipt = cases._exercise_owned_app(sys.argv[1], sys.argv[2], guards, progress)
except BaseException as exc:
    receipt = {
        "ok": False,
        "phase": progress["phase"],
        "error_type": type(exc).__name__,
        "check": (
            exc.check_code
            if cases is not None and isinstance(exc, cases.OwnedAppCheckFailed)
            else None
        ),
        "guards": dict(guards),
    }
finally:
    payload = json.dumps(receipt, sort_keys=True).encode("ascii")
    try:
        view = memoryview(payload)
        while view:
            written = os.write(report_fd, view)
            if written <= 0:
                raise RuntimeError("owned receipt channel closed")
            view = view[written:]
    finally:
        os.close(report_fd)
raise SystemExit(0 if receipt["ok"] else 1)
"""


def _run_owned_app_child(scenario, tmp_path):
    environment = dict(os.environ)
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    environment["UPLOAD_DIR"] = str(tmp_path / "uploads")
    process = subprocess.Popen(
        [sys.executable, "-c", _CHILD_BOOTSTRAP, scenario, str(tmp_path)],
        cwd=ROOT, env=environment, stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        close_fds=True,
    )
    output = b""
    timed_out = False
    cleanup_actions = []
    try:
        try:
            output, _ = process.communicate(timeout=APP_CHILD_SAFETY_SECONDS)
        except subprocess.TimeoutExpired:
            timed_out = True
            process.terminate()
            cleanup_actions.append("fixture_terminate")
            try:
                output, _ = process.communicate(timeout=APP_CHILD_CLEANUP_SECONDS)
            except subprocess.TimeoutExpired:
                process.kill()
                cleanup_actions.append("fixture_kill")
                output, _ = process.communicate(timeout=APP_CHILD_CLEANUP_SECONDS)
    finally:
        # This is harness-owned child cleanup, not proof that TmapRuntime
        # internals closed: the production lifespan uses a runtime spy here.
        before_finally = {
            "returncode": process.returncode,
            "stdout_closed": process.stdout is None or process.stdout.closed,
        }
        if process.returncode is None:
            process.kill()
            cleanup_actions.append("fixture_finally_kill")
            process.wait(timeout=APP_CHILD_CLEANUP_SECONDS)
        for stream in (process.stdin, process.stdout, process.stderr):
            if stream is not None and not stream.closed:
                stream.close()
                cleanup_actions.append("fixture_channel_close")
        resources = {
            "pid": process.pid, "returncode": process.returncode,
            "timed_out": timed_out,
            "before_finally_cleanup": before_finally,
            "cleanup_actions": cleanup_actions,
            "all_channels_closed": all(
                stream is None or stream.closed
                for stream in (process.stdin, process.stdout, process.stderr)
            ),
            "cleanup_owner": "test_child_harness",
            "runtime_internal_resource_shutdown_proven": False,
        }
        print("TMAP_APP_OWNED_CHILD " + json.dumps(resources, sort_keys=True))
    try:
        receipt = json.loads(output.decode("ascii"))
    except (ValueError, UnicodeError):
        receipt = {"ok": False, "error_type": "InvalidOwnedChildReceipt"}
    return receipt, resources


@pytest.mark.parametrize("scenario", [
    "normal",
    "partial_start_failure",
    "runtime_close_failure",
    "health_stop_failure",
])
def test_production_main_lifespan_and_router_owner_contract(tmp_path, scenario):
    receipt, resources = _run_owned_app_child(scenario, tmp_path)
    assert resources["timed_out"] is False, resources
    assert resources["returncode"] == 0, receipt
    assert resources["all_channels_closed"] is True
    assert receipt["ok"] is True, receipt
    assert receipt["scenario"] == scenario
    assert receipt["guards"] == {"database": 0, "network": 0, "model_load": 0}
    assert receipt["runtime_internal_resource_shutdown_proven"] is False
    print("TMAP_APP_CASE_RECEIPT " + json.dumps(receipt, sort_keys=True))


@pytest.fixture
def isolated_health(monkeypatch):
    from sqlalchemy.engine import Engine

    guards = {"database": 0, "network": 0}

    def deny_database(*args, **kwargs):
        guards["database"] += 1
        raise RuntimeError("owned health test forbids database connections")

    def deny_network(*args, **kwargs):
        guards["network"] += 1
        raise RuntimeError("owned health test forbids external connections")

    monkeypatch.setattr(Engine, "connect", deny_database)
    monkeypatch.setattr(Engine, "raw_connection", deny_database)
    monkeypatch.setattr(socket.socket, "connect", deny_network)
    monkeypatch.setattr(socket.socket, "connect_ex", deny_network)
    monkeypatch.setattr(socket, "create_connection", deny_network)
    monkeypatch.setattr(socket, "getaddrinfo", deny_network)
    from backend.app.services import health_readiness as health

    yield health
    assert guards == {"database": 0, "network": 0}


def _health_settings():
    return SimpleNamespace(
        walking_route_provider="tmap_pedestrian",
        tmap_poi_provider="live",
        tmap_app_key="owned-synthetic-tmap-key",
        tmap_readiness_success_max_age_seconds=60.0,
        tmap_readiness_live_probe_enabled=True,
        tmap_readiness_probe_timeout_seconds=1.0,
    )


@pytest.mark.parametrize("mode", ["app_owner", "app_missing", "standalone"])
def test_actual_navigation_readiness_owner_and_standalone_boundary(
    isolated_health, monkeypatch, mode,
):
    health = isolated_health
    settings = _health_settings()
    owner = object()
    cached = {"ready": False, "reason": "owned_cold_cache", "provider": "tmap_pedestrian"}
    cache_calls, provider_calls, probe_calls, factory_calls = [], [], [], []

    def cache(max_age):
        cache_calls.append(max_age)
        return dict(cached)

    def provider():
        provider_calls.append(1)
        return None if mode == "app_missing" else owner

    def forbidden_factory(*args, **kwargs):
        factory_calls.append(1)
        raise AssertionError("the health adapter must not construct a runtime")

    if mode == "standalone":
        # Exact old signature: an added runtime keyword would prevent entry.
        async def probe(received):
            probe_calls.append((received, None))
            await asyncio.sleep(0)
            return {"ready": True, "provider": "tmap_pedestrian"}
    else:
        async def probe(received, *, runtime):
            probe_calls.append((received, runtime))
            await asyncio.sleep(0)
            return {"ready": True, "provider": "tmap_pedestrian"}

    monkeypatch.setattr(health, "tmap_dependency_readiness", cache)
    monkeypatch.setattr(health, "probe_tmap_dependencies", probe)
    monkeypatch.setattr(health, "TmapRuntime", forbidden_factory)
    if mode == "standalone":
        result = asyncio.run(health._navigation_readiness(settings))
    else:
        result = asyncio.run(health._navigation_readiness(
            settings, runtime_provider=provider,
        ))

    assert cache_calls == [settings.tmap_readiness_success_max_age_seconds]
    assert factory_calls == []
    assert result["live_probe_enabled"] is True
    if mode == "app_missing":
        assert provider_calls == [1]
        assert probe_calls == []
        assert result["ready"] is False
        assert result["reason"] == "tmap_live_probe_failed"
        assert result["error_type"] == "RuntimeError"
    else:
        assert len(probe_calls) == 1
        settings_matched = probe_calls[0][0] is settings
        owner_matched = probe_calls[0][1] is (None if mode == "standalone" else owner)
        assert settings_matched and owner_matched
        assert provider_calls == ([] if mode == "standalone" else [1])
        assert result.get("reason") != "tmap_live_probe_failed"
        # Do not invent a new successful-probe response envelope here.
        # Actual scoped standalone runtime ownership has separate service tests.


@pytest.mark.parametrize("branch", [
    "route_config",
    "poi_config",
    "key_config",
    "cached_ready",
    "live_disabled",
])
def test_actual_navigation_readiness_preserves_pre_owner_short_circuits(
    isolated_health, monkeypatch, branch,
):
    health = isolated_health
    settings = _health_settings()
    expected_reason = {
        "route_config": "walking_provider_invalid",
        "poi_config": "tmap_poi_provider_not_live",
        "key_config": "tmap_app_key_missing",
        "live_disabled": "tmap_recent_success_unavailable",
    }
    if branch == "route_config":
        settings.walking_route_provider = "owned-invalid-provider"
    elif branch == "poi_config":
        settings.tmap_poi_provider = "owned-invalid-poi-provider"
    elif branch == "key_config":
        settings.tmap_app_key = ""
    elif branch == "live_disabled":
        settings.tmap_readiness_live_probe_enabled = False
    cached = {
        "ready": branch == "cached_ready",
        "reason": None if branch == "cached_ready" else "owned_cold_cache",
        "provider": "tmap_pedestrian",
    }
    cache_calls, provider_calls, probe_calls, factory_calls = [], [], [], []

    def cache(max_age):
        cache_calls.append(max_age)
        return dict(cached)

    def provider():
        provider_calls.append(1)
        return None

    async def probe(*args, **kwargs):
        probe_calls.append(1)
        raise AssertionError("a preceding readiness branch must not probe")

    def forbidden_factory(*args, **kwargs):
        factory_calls.append(1)
        raise AssertionError("a preceding readiness branch must not create an owner")

    monkeypatch.setattr(health, "tmap_dependency_readiness", cache)
    monkeypatch.setattr(health, "probe_tmap_dependencies", probe)
    monkeypatch.setattr(health, "TmapRuntime", forbidden_factory)
    result = asyncio.run(health._navigation_readiness(
        settings, runtime_provider=provider,
    ))
    assert provider_calls == probe_calls == factory_calls == []
    if branch in {"route_config", "poi_config", "key_config"}:
        assert cache_calls == []
        assert result["ready"] is False
        assert result["reason"] == expected_reason[branch]
    else:
        assert cache_calls == [settings.tmap_readiness_success_max_age_seconds]
        if branch == "cached_ready":
            assert result == cached
        else:
            assert result["ready"] is False
            assert result["reason"] == expected_reason[branch]
            assert result["live_probe_enabled"] is False
