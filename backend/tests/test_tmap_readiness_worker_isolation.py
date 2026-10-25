"""S20-50: TMAP readiness success evidence is process-local (not shared by workers).

Each uvicorn/gunicorn worker is a separate interpreter. A success recorded by one
worker must not make another worker ready, and a worker with the live probe
disabled and no recent success of its own stays not ready.
"""
from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace

import pytest

import backend.app.services.health_readiness as health_api
from backend.app.services.tmap_pedestrian import (
    _record_tmap_success,
    reset_tmap_success_cache,
    tmap_dependency_readiness,
)

_WORKER_SCRIPT = r"""
import json
import socket
import sys

def deny_network(*args, **kwargs):
    raise AssertionError("network access forbidden in readiness worker child")

socket.socket.connect = deny_network
socket.socket.connect_ex = deny_network
socket.create_connection = deny_network
from backend.app.services.tmap_pedestrian import (
    _record_tmap_success, tmap_dependency_readiness,
)
if sys.argv[1] == "record":
    _record_tmap_success("route")
    _record_tmap_success("poi")
print(json.dumps(tmap_dependency_readiness(300.0), sort_keys=True))
"""


def _run_worker(phase: str) -> dict[str, object]:
    environment = dict(os.environ)
    environment.pop("PYTHONOPTIMIZE", None)
    completed = subprocess.run(
        [sys.executable, "-B", "-c", _WORKER_SCRIPT, phase],
        cwd=Path(__file__).resolve().parents[2], env=environment,
        capture_output=True, text=True, timeout=30,
    )
    assert completed.returncode == 0, completed.stderr[-2000:]
    return json.loads(completed.stdout)


@pytest.fixture(autouse=True)
def isolated_success_cache():
    reset_tmap_success_cache()
    yield
    reset_tmap_success_cache()


def test_s20_50_success_record_is_not_shared_between_worker_processes() -> None:
    # Worker B records its own success; worker A (this process) stays not ready.
    other_worker = _run_worker("record")
    assert other_worker["ready"] is True
    assert other_worker["evidence"] == "recent_success"
    this_worker = tmap_dependency_readiness(300.0)
    assert this_worker["ready"] is False
    assert this_worker["evidence"] == "none"
    assert (this_worker["route_recent"], this_worker["poi_recent"]) == (False, False)

    # Worker A records success; a fresh worker B still has no evidence.
    _record_tmap_success("route")
    _record_tmap_success("poi")
    assert tmap_dependency_readiness(300.0)["ready"] is True
    fresh_worker = _run_worker("observe")
    assert fresh_worker["ready"] is False
    assert fresh_worker["evidence"] == "none"


def _navigation_settings(*, live_probe_enabled: bool) -> SimpleNamespace:
    return SimpleNamespace(
        walking_route_provider="tmap_pedestrian",
        tmap_poi_provider="live",
        tmap_app_key="fixture-key",
        tmap_readiness_success_max_age_seconds=300.0,
        tmap_readiness_live_probe_enabled=live_probe_enabled,
        tmap_readiness_probe_timeout_seconds=1.0,
    )


def test_s20_50_disabled_live_probe_without_own_record_is_not_ready(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    probes: list[object] = []

    def forbidden_probe(*args, **kwargs):
        probes.append((args, kwargs))
        raise AssertionError("live probe must not run while disabled")

    monkeypatch.setattr(health_api, "probe_tmap_dependencies", forbidden_probe)
    settings = _navigation_settings(live_probe_enabled=False)

    result = asyncio.run(health_api._navigation_readiness(settings))
    assert result["ready"] is False
    assert result["reason"] == "tmap_recent_success_unavailable"
    assert result["live_probe_enabled"] is False
    assert result["evidence"] == "none"

    # Only one dependency succeeded in this worker: still not ready.
    _record_tmap_success("route")
    partial = asyncio.run(health_api._navigation_readiness(settings))
    assert partial["ready"] is False
    assert (partial["route_recent"], partial["poi_recent"]) == (True, False)

    # This worker's own fresh route+POI success is the only path to ready.
    _record_tmap_success("poi")
    ready = asyncio.run(health_api._navigation_readiness(settings))
    assert ready["ready"] is True
    assert ready["evidence"] == "recent_success"
    assert probes == []
