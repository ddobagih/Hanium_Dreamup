#!/usr/bin/env python3
"""Browser-level PWA lifecycle, server-v2 privacy-control, and offline-boundary smoke.

Run with the project virtualenv because the CDP transport uses ``websockets``.
The privacy probe is browser-local and deliberately does not claim real detector,
backend persistence, personal-data handling, or mobile-device evidence.
"""
from __future__ import annotations

import argparse
import asyncio
import errno
import json
import math
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

import websockets

from check_pwa_server_e2e import (
    CheckFailed,
    cdp_call as unbounded_cdp_call,
    chrome_binary,
    evaluate as unbounded_evaluate,
    http_json,
    start_process,
    stop_process,
    wait_for_http,
)


REPO_ROOT = Path(__file__).resolve().parents[1]
PRIVACY_WITHDRAWAL_ABORT_TIMEOUT_SECONDS = 1.0
CDP_RESPONSE_TIMEOUT_SECONDS = 30.0
LIFECYCLE_LOCK_NAME = "\0walksafe-pwa-browser-lifecycle-v1"
EXPECTED_ADVISORY_MESSAGE = (
    "카메라 기준 정면에 점자블록이 감지됐습니다. "
    "TMAP 길 안내를 기준으로 주변을 확인하세요."
)


def acquire_lifecycle_lock() -> socket.socket:
    if not hasattr(socket, "AF_UNIX"):
        raise CheckFailed("PWA browser lifecycle requires a Linux AF_UNIX run lock")
    lock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        lock.bind(LIFECYCLE_LOCK_NAME)
    except OSError as exc:
        lock.close()
        raise CheckFailed("another PWA browser lifecycle check is already running") from exc
    return lock


def new_lifecycle_credentials() -> tuple[str, str, str, str]:
    return (
        f"pwa-e2e-field-{secrets.token_hex(8)}",
        f"pwa-e2e-account-{secrets.token_hex(32)}",
        f"pwa-e2e-backend-{secrets.token_hex(32)}",
        f"pwa-e2e-session-{secrets.token_hex(32)}",
    )


def require_isolated_loopback_ports(ports: dict[str, int]) -> None:
    if len(set(ports.values())) != len(ports):
        raise CheckFailed("PWA web and Chromium debug ports must be different")
    for label, port in ports.items():
        if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
            raise CheckFailed(f"{label} loopback port must be an integer from 1 to 65535")
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
                probe.bind(("127.0.0.1", port))
        except OSError as exc:
            raise CheckFailed(
                f"{label} loopback port {port} is already in use before launch"
            ) from exc


def wait_for_loopback_port_release(port: int, *, timeout: float = 8.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(0.2)
            if probe.connect_ex(("127.0.0.1", port)) == errno.ECONNREFUSED:
                return
        time.sleep(0.05)
    raise CheckFailed(f"loopback port {port} remained open after owned process shutdown")


def stop_owned_process(
    process: subprocess.Popen[str],
    port: int,
    label: str,
) -> None:
    stop_process(process)
    if process.poll() is None:
        raise CheckFailed(f"Could not stop {label}")
    wait_for_loopback_port_release(port)


def select_launched_chrome_page(
    targets: Any,
    expected_url: str,
    expected_debug_port: int,
) -> dict[str, Any]:
    if not isinstance(targets, list):
        raise CheckFailed(f"Chromium target response is not a list: {targets!r}")
    matches = [
        target
        for target in targets
        if isinstance(target, dict)
        and target.get("type") == "page"
        and target.get("url") == expected_url
    ]
    if len(matches) != 1:
        raise CheckFailed(
            "CDP did not expose exactly one page owned by the launched Chromium instance"
        )
    target = matches[0]
    target_id = target.get("id")
    websocket_url = target.get("webSocketDebuggerUrl")
    if not isinstance(target_id, str) or not target_id or not isinstance(websocket_url, str):
        raise CheckFailed("launched Chromium target identity is incomplete")
    try:
        parsed_websocket_url = urlsplit(websocket_url)
        websocket_port = parsed_websocket_url.port
    except ValueError as exc:
        raise CheckFailed("launched Chromium target has an invalid WebSocket URL") from exc
    if (
        parsed_websocket_url.scheme != "ws"
        or parsed_websocket_url.hostname != "127.0.0.1"
        or websocket_port != expected_debug_port
        or parsed_websocket_url.username is not None
        or parsed_websocket_url.password is not None
        or parsed_websocket_url.path != f"/devtools/page/{target_id}"
        or parsed_websocket_url.query
        or parsed_websocket_url.fragment
    ):
        raise CheckFailed(
            "launched Chromium target WebSocket is outside the owned loopback endpoint"
        )
    return target


async def _bounded_cdp(
    awaitable: Any,
    operation: str,
    timeout_seconds: float | None = None,
) -> Any:
    timeout = CDP_RESPONSE_TIMEOUT_SECONDS
    if timeout_seconds is not None:
        timeout = min(timeout, timeout_seconds)
    try:
        return await asyncio.wait_for(awaitable, timeout=timeout)
    except TimeoutError as exc:
        raise CheckFailed(f"Timed out waiting for {operation} after {timeout:g}s") from exc


async def cdp_call(
    websocket: Any,
    method: str,
    params: dict[str, Any] | None = None,
    *,
    message_id: int,
    timeout_seconds: float | None = None,
) -> Any:
    return await _bounded_cdp(
        unbounded_cdp_call(websocket, method, params, message_id=message_id),
        f"CDP {method}",
        timeout_seconds,
    )


async def evaluate(
    websocket: Any,
    expression: str,
    *,
    message_id: int,
    timeout_seconds: float | None = None,
) -> Any:
    return await _bounded_cdp(
        unbounded_evaluate(websocket, expression, message_id=message_id),
        "CDP Runtime.evaluate",
        timeout_seconds,
    )


PRIVACY_PROBE_INIT_SCRIPT = r"""
(() => {
  const originalFetch = window.fetch.bind(window);
  const routeFixture = __WALKSAFE_ROUTE_FIXTURE__;
  const probe = {
    detectStarted: 0,
    detectSucceeded: 0,
    detectFailed: 0,
    detectWithGps: 0,
    detectAborted: 0,
    detectPending: 0,
    detectContextErrors: 0,
    reportStarted: 0,
    reportAborted: 0,
    reportPending: 0,
    blockedExternalFetches: 0,
    cameraAttempts: 0,
    cameraFailed: 0,
    failCamera: true,
    failDetect: false,
    stallDetect: false,
    releaseDetect: null,
    advisoryMode: false,
    recordAdvisoryFrames: false,
    advisoryCapturedAt: [],
    voiceStarted: 0,
    destinationSearchStarted: 0,
    routeStarted: 0,
    navigationContractErrors: 0,
    telemetryStarted: 0,
    telemetrySucceeded: 0,
    vibrateCalls: 0,
    vibrateInstallError: null
  };
  Object.defineProperty(window, '__walksafePrivacyProbe', {
    configurable: false,
    enumerable: false,
    value: probe
  });
  try {
    Object.defineProperty(navigator, 'vibrate', {
      configurable: true,
      value() {
        probe.vibrateCalls += 1;
        return true;
      }
    });
  } catch (error) {
    probe.vibrateInstallError = String(error);
  }
  const speechProbe = {
    speakCalls: 0,
    cancelCalls: 0,
    messages: [],
    installError: null
  };
  Object.defineProperty(window, '__walksafeSpeechProbe', {
    configurable: false,
    enumerable: false,
    value: speechProbe
  });
  try {
    Object.defineProperty(window.speechSynthesis, 'speak', {
      configurable: true,
      value(utterance) {
        speechProbe.speakCalls += 1;
        speechProbe.messages.push(String(utterance?.text ?? ''));
        window.setTimeout(() => {
          if (typeof utterance?.onerror === 'function') {
            utterance.onerror({ error: 'synthetic-synthesis-failed' });
          }
        }, 0);
      }
    });
    Object.defineProperty(window.speechSynthesis, 'cancel', {
      configurable: true,
      value() {
        speechProbe.cancelCalls += 1;
      }
    });
  } catch (error) {
    speechProbe.installError = String(error);
  }

  const pendingResponse = (kind, signal, response = undefined) => new Promise((resolve, reject) => {
    const pendingKey = `${kind}Pending`;
    const abortedKey = `${kind}Aborted`;
    let settled = false;
    probe[pendingKey] += 1;
    const finish = () => {
      if (settled) return false;
      settled = true;
      probe[pendingKey] -= 1;
      signal?.removeEventListener('abort', abort);
      if (kind === 'detect' && probe.releaseDetect === release) probe.releaseDetect = null;
      return true;
    };
    const abort = () => {
      if (!finish()) return;
      probe[abortedKey] += 1;
      reject(new DOMException('Synthetic privacy probe request aborted', 'AbortError'));
    };
    const release = () => {
      if (!finish()) return;
      resolve(response);
    };
    if (kind === 'detect') probe.releaseDetect = release;
    if (signal?.aborted) abort();
    else signal?.addEventListener('abort', abort, { once: true });
  });

  window.fetch = async (input, init = {}) => {
    const request = input instanceof Request ? input : null;
    const url = new URL(request ? request.url : String(input), window.location.href);
    const signal = init.signal || request?.signal;
    if (url.origin !== window.location.origin) {
      probe.blockedExternalFetches += 1;
      throw new TypeError(`Synthetic privacy probe blocked external fetch: ${url.origin}`);
    }
    if (url.origin === window.location.origin && url.pathname === '/api/detect/v2') {
      probe.detectStarted += 1;
      const formData = init.body instanceof FormData ? init.body : null;
      const rawContext = formData?.get('context');
      let context = null;
      try {
        context = typeof rawContext === 'string' ? JSON.parse(rawContext) : null;
      } catch (_error) {
        context = null;
      }
      if (!context || typeof context.captured_at !== 'string') {
        probe.detectContextErrors += 1;
        return Response.json({ code: 'synthetic_context_missing' }, { status: 400 });
      }
      if (context.gps) probe.detectWithGps += 1;
      if (probe.failDetect) {
        probe.detectFailed += 1;
        return Response.json(
          { code: 'synthetic_detector_unavailable', reason: 'Synthetic detector outage' },
          { status: 503, headers: { 'x-request-id': `privacy-probe-failed-${probe.detectStarted}` } }
        );
      }
      probe.detectSucceeded += 1;
      if (probe.advisoryMode && probe.recordAdvisoryFrames) {
        probe.advisoryCapturedAt.push(context.captured_at);
      }
      const response = Response.json({
        schema_version: 'detect.v2',
        detections: probe.advisoryMode && !probe.recordAdvisoryFrames ? [] : [{
          schema_version: 'detect.v2',
          model_key: 'unified_walksafe',
          source_model: 'walksafe-synthetic-browser-probe',
          model_class_id: probe.advisoryMode ? 7 : 8,
          class_name: probe.advisoryMode ? 'normal_tactile_block' : 'damaged_tactile_block',
          category: probe.advisoryMode ? 'tactile_normal' : 'tactile_damage',
          confidence: probe.advisoryMode ? 0.88 : 0.96,
          bbox: { x: 0.35, y: 0.45, width: 0.3, height: 0.3 },
          threshold_used: 0.5,
          captured_at: context.captured_at,
          gps: context.gps ?? null,
          heading: context.heading ?? null
        }]
      }, {
        status: 200,
        headers: { 'x-request-id': `privacy-probe-${probe.detectStarted}` }
      });
      return probe.stallDetect ? pendingResponse('detect', signal, response) : response;
    }
    if (probe.advisoryMode && url.pathname === '/api/speech/stt') {
      probe.voiceStarted += 1;
      const setDestination = probe.voiceStarted === 1;
      return Response.json({
        transcript: setDestination ? '테스트 목적지로 안내해줘' : '길안내 시작',
        normalized: setDestination ? '테스트 목적지로 안내해줘' : '길안내 시작',
        intent: setDestination ? 'set_destination' : 'start_navigation',
        confidence: 0.9,
        score: 0.9,
        acoustic_confidence: 0.9,
        avg_logprob: -0.1,
        no_speech_probability: 0.01,
        acoustic_execution_allowed: true,
        slots: setDestination ? { destination: '테스트 목적지' } : {},
        action: 'execute',
        should_execute: true,
        reason: null,
        prompt: null
      });
    }
    if (probe.advisoryMode && url.pathname === '/api/navigation/destinations/search') {
      probe.destinationSearchStarted += 1;
      const query = url.searchParams.get('query') ?? '';
      return Response.json({
        schema_version: 'walksafe.destination_search.v1',
        provider: 'tmap_poi',
        query,
        results: [{
          id: 'synthetic-destination-1',
          name: '테스트 목적지',
          point: { ...routeFixture.request.destination, name: '테스트 목적지' },
          result_type: 'poi',
          distance_m: routeFixture.response.summary.distance_m
        }]
      });
    }
    if (probe.advisoryMode && url.pathname === '/api/navigation/walking') {
      probe.routeStarted += 1;
      let body = null;
      try { body = JSON.parse(String(init.body ?? '')); } catch (_error) {}
      if (
        body?.origin?.latitude !== routeFixture.request.origin.latitude ||
        body?.origin?.longitude !== routeFixture.request.origin.longitude ||
        body?.destination?.latitude !== routeFixture.request.destination.latitude ||
        body?.destination?.longitude !== routeFixture.request.destination.longitude ||
        body?.priority !== routeFixture.request.priority
      ) probe.navigationContractErrors += 1;
      return Response.json(routeFixture.response);
    }
    if (url.origin === window.location.origin && url.pathname === '/api/reports/v2') {
      probe.reportStarted += 1;
      return pendingResponse('report', signal);
    }
    if (url.pathname === '/api/walksafe-field-log' && (init.method ?? request?.method) === 'POST') {
      probe.telemetryStarted += 1;
      const response = await originalFetch(input, init);
      if (response.ok) probe.telemetrySucceeded += 1;
      return response;
    }
    return originalFetch(input, init);
  };

  let gpsWatchId = 0;
  const gpsTimers = new Map();
  const position = () => ({
    coords: {
      latitude: routeFixture.request.origin.latitude,
      longitude: routeFixture.request.origin.longitude,
      accuracy: 5,
      speed: 0,
      heading: null,
      altitude: null,
      altitudeAccuracy: null
    },
    timestamp: Date.now()
  });
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: {
      clearWatch(id) {
        const timer = gpsTimers.get(id);
        if (timer !== undefined) window.clearInterval(timer);
        gpsTimers.delete(id);
      },
      getCurrentPosition(success) {
        window.setTimeout(() => success(position()), 0);
      },
      watchPosition(success) {
        const id = ++gpsWatchId;
        window.setTimeout(() => success(position()), 0);
        gpsTimers.set(id, window.setInterval(() => success(position()), 1000));
        return id;
      }
    }
  });

  const originalMediaDevices = navigator.mediaDevices || {};
  Object.defineProperty(navigator, 'mediaDevices', {
    configurable: true,
    value: {
      ...originalMediaDevices,
      async getUserMedia(constraints) {
        if (constraints?.audio && !constraints?.video) {
          const track = new EventTarget();
          track.stop = () => {};
          return {
            getTracks: () => [track],
            getAudioTracks: () => [track],
            getVideoTracks: () => []
          };
        }
        if (!constraints?.video) throw new DOMException('Video only', 'NotFoundError');
        probe.cameraAttempts += 1;
        if (probe.failCamera) {
          probe.cameraFailed += 1;
          throw new DOMException('Synthetic camera permission denied', 'NotAllowedError');
        }
        const canvas = document.createElement('canvas');
        canvas.width = 640;
        canvas.height = 480;
        const context = canvas.getContext('2d');
        if (!context || typeof canvas.captureStream !== 'function') {
          throw new DOMException('Synthetic camera unavailable', 'NotReadableError');
        }
        const draw = () => {
          const phase = Date.now() % 255;
          context.fillStyle = `rgb(${phase}, 48, 96)`;
          context.fillRect(0, 0, canvas.width, canvas.height);
        };
        draw();
        window.setInterval(draw, 100);
        const stream = canvas.captureStream(10);
        const track = stream.getVideoTracks()[0];
        const getSettings = track.getSettings.bind(track);
        Object.defineProperty(track, 'getSettings', {
          configurable: true,
          value: () => ({ ...getSettings(), facingMode: 'environment' })
        });
        return stream;
      }
    }
  });
  class SyntheticMediaRecorder {
    static isTypeSupported() { return true; }
    constructor(_stream, options = {}) {
      this.state = 'inactive';
      this.mimeType = options.mimeType || 'audio/webm';
      this.ondataavailable = null;
      this.onerror = null;
      this.onstop = null;
    }
    start() { this.state = 'recording'; }
    stop() {
      if (this.state === 'inactive') return;
      this.state = 'inactive';
      this.ondataavailable?.({ data: new Blob(['synthetic-audio'], { type: this.mimeType }) });
      queueMicrotask(() => this.onstop?.());
    }
  }
  Object.defineProperty(window, 'MediaRecorder', { configurable: true, value: SyntheticMediaRecorder });
})();
"""


def privacy_probe_init_script() -> str:
    route_fixture = json.loads(
        (REPO_ROOT / "contracts/fixtures/walking-route-v1.json").read_text(encoding="utf-8")
    )
    return PRIVACY_PROBE_INIT_SCRIPT.replace(
        "__WALKSAFE_ROUTE_FIXTURE__",
        json.dumps(route_fixture, ensure_ascii=False, separators=(",", ":")),
    )


PRIVACY_STATE_EXPRESSION = r"""
(() => {
  const text = document.body?.innerText ?? '';
  const buttons = [...document.querySelectorAll('button')];
  const button = (label) => buttons.find((item) => item.innerText.includes(label));
  const reportAllow = button('저장 항목 확인·자동 신고 동의');
  const video = document.querySelector('video');
  const probe = window.__walksafePrivacyProbe;
  const advisoryCard = [...document.querySelectorAll('.status-item')]
    .find((item) => item.querySelector('.status-label')?.textContent?.trim() === '카메라 보조');
  return {
    text,
    initialProcessing: text.includes('서버 탐지 처리: 동의 전 중지'),
    processingAllowed: text.includes('서버 탐지 처리: 프레임 전송 허용'),
    reportStopped: text.includes('신고 이미지 저장·자동 신고: 중지됨'),
    reportAllowed: text.includes('신고 이미지 저장·자동 신고: 동의됨'),
    reportAllowDisabled: reportAllow ? reportAllow.disabled : null,
    cameraReady: Boolean(video && video.videoWidth > 0 && video.videoHeight > 0 && video.readyState >= 2),
    telemetryAllowed: text.includes('현장 진단 기록: 수집 중'),
    navigationActive: text.includes('TMAP 경로 안내 중'),
    advisoryText: advisoryCard?.textContent?.replace(/\s+/g, ' ').trim() ?? null,
    riskDanger: Boolean(document.querySelector('.risk-pill.danger')),
    probe: probe ? {
      detectStarted: probe.detectStarted,
      detectSucceeded: probe.detectSucceeded,
      detectFailed: probe.detectFailed,
      detectWithGps: probe.detectWithGps,
      detectAborted: probe.detectAborted,
      detectPending: probe.detectPending,
      detectContextErrors: probe.detectContextErrors,
      reportStarted: probe.reportStarted,
      reportAborted: probe.reportAborted,
      reportPending: probe.reportPending,
      blockedExternalFetches: probe.blockedExternalFetches,
      cameraAttempts: probe.cameraAttempts,
      cameraFailed: probe.cameraFailed,
      failCamera: probe.failCamera,
      failDetect: probe.failDetect,
      stallDetect: probe.stallDetect,
      advisoryMode: probe.advisoryMode,
      advisoryCapturedAt: [...probe.advisoryCapturedAt],
      voiceStarted: probe.voiceStarted,
      destinationSearchStarted: probe.destinationSearchStarted,
      routeStarted: probe.routeStarted,
      navigationContractErrors: probe.navigationContractErrors,
      telemetryStarted: probe.telemetryStarted,
      telemetrySucceeded: probe.telemetrySucceeded,
      vibrateCalls: probe.vibrateCalls,
      vibrateInstallError: probe.vibrateInstallError
    } : null
  };
})()
"""


async def wait_for_pwa_ready(websocket: Any, message_id: int, timeout_seconds: float) -> tuple[dict[str, Any], int]:
    expression = """
(async () => {
  if (!('serviceWorker' in navigator)) {
    return { ready: false, reason: 'service_worker_unsupported' };
  }
  const registration = await navigator.serviceWorker.getRegistration('/');
  if (!registration) {
    return { ready: false, reason: 'service_worker_registration_missing' };
  }
  const worker = registration.active || registration.waiting || registration.installing;
  let version = null;
  if (worker) {
    version = await new Promise((resolve) => {
      const channel = new MessageChannel();
      const timer = setTimeout(() => resolve(null), 3000);
      channel.port1.onmessage = (event) => {
        clearTimeout(timer);
        resolve(typeof event.data?.version === 'string' ? event.data.version : null);
      };
      worker.postMessage({ type: 'GET_VERSION' }, [channel.port2]);
    });
  }
  const cacheNames = await caches.keys();
  const shellCached = Boolean(await caches.match('/'));
  return {
    ready: Boolean(worker && worker.state === 'activated'),
    scope: registration.scope,
    activeState: worker?.state ?? null,
    controlled: Boolean(navigator.serviceWorker.controller),
    version,
    cacheNames,
    shellCached,
    offlineShellReady: document.documentElement?.dataset.walksafeOfflineShellReady === 'true'
  };
})()
"""
    deadline = time.monotonic() + timeout_seconds
    last_state: dict[str, Any] | None = None
    while time.monotonic() < deadline:
        state = await evaluate(
            websocket,
            expression,
            message_id=message_id,
            timeout_seconds=deadline - time.monotonic(),
        )
        message_id += 1
        last_state = state if isinstance(state, dict) else None
        if (
            last_state
            and last_state.get("ready")
            and last_state.get("controlled")
            and last_state.get("shellCached")
            and last_state.get("offlineShellReady")
        ):
            return last_state, message_id
        await asyncio.sleep(0.5)
    raise CheckFailed(f"PWA service worker did not become ready/controlling/cached: {last_state}")


def privacy_probe(state: dict[str, Any] | None) -> dict[str, Any]:
    probe = state.get("probe") if isinstance(state, dict) else None
    return probe if isinstance(probe, dict) else {}


async def wait_for_privacy_state(
    websocket: Any,
    message_id: int,
    timeout_seconds: float,
    predicate: Callable[[dict[str, Any]], bool],
    description: str,
) -> tuple[dict[str, Any], int]:
    deadline = time.monotonic() + timeout_seconds
    last_state: dict[str, Any] | None = None
    while time.monotonic() < deadline:
        state = await evaluate(
            websocket,
            PRIVACY_STATE_EXPRESSION,
            message_id=message_id,
            timeout_seconds=deadline - time.monotonic(),
        )
        message_id += 1
        last_state = state if isinstance(state, dict) else None
        if last_state is not None and predicate(last_state):
            return last_state, message_id
        await asyncio.sleep(0.1)
    raise CheckFailed(f"Timed out waiting for {description}: {last_state}")


async def click_privacy_button(websocket: Any, message_id: int, label: str) -> int:
    clicked = await evaluate(
        websocket,
        f"""
(() => {{
  const button = [...document.querySelectorAll('button')]
    .find((item) => item.innerText.includes({json.dumps(label)}));
  if (!button || button.disabled) return false;
  button.click();
  return true;
}})()
""",
        message_id=message_id,
    )
    if clicked is not True:
        raise CheckFailed(f"Privacy control was missing or disabled: {label}")
    return message_id + 1


async def wait_for_tts_failure_fallback(
    websocket: Any,
    message_id: int,
    timeout_seconds: float,
    expected_fragment: str,
) -> tuple[dict[str, Any], int]:
    expression = r"""
(() => {
  const probe = window.__walksafeSpeechProbe;
  const live = document.querySelector('.sr-only[role="alert"][aria-live="assertive"]');
  return {
    installed: Boolean(probe) && probe.installError === null,
    installError: probe?.installError ?? null,
    speakCalls: probe?.speakCalls ?? 0,
    messages: probe?.messages ?? [],
    liveMessage: live?.textContent?.trim() ?? null
  };
})()
"""
    deadline = time.monotonic() + timeout_seconds
    last_state: dict[str, Any] | None = None
    while time.monotonic() < deadline:
        state = await evaluate(
            websocket,
            expression,
            message_id=message_id,
            timeout_seconds=deadline - time.monotonic(),
        )
        message_id += 1
        last_state = state if isinstance(state, dict) else None
        live_message = str(last_state.get("liveMessage", "")) if last_state else ""
        if (
            last_state
            and last_state.get("installed") is True
            and last_state.get("speakCalls", 0) >= 1
            and expected_fragment in live_message
            and "전방을 직접 확인해 주세요" in live_message
        ):
            return last_state, message_id
        await asyncio.sleep(0.1)
    raise CheckFailed(f"TTS failure did not reach the production assertive live fallback: {last_state}")


async def exercise_server_v2_privacy_controls(
    websocket: Any,
    message_id: int,
    timeout_seconds: float,
) -> tuple[dict[str, Any], int]:
    state, message_id = await wait_for_privacy_state(
        websocket,
        message_id,
        timeout_seconds,
        lambda item: item.get("initialProcessing") is True and item.get("reportStopped") is True,
        "the authenticated fail-closed server-v2 privacy UI",
    )
    if state.get("reportAllowDisabled") is not True:
        raise CheckFailed(f"Report storage consent was enabled before processing consent: {state}")
    text = str(state.get("text", ""))
    for disclosure in (
        "최대 960px 전체 프레임 JPEG",
        "/detect/v2 자체는 프레임을 파일이나 DB에 저장하지 않습니다",
        "보존 정책: 180일",
        "얼굴·차량번호를 모자이크하지 않은",
    ):
        if disclosure not in text:
            raise CheckFailed(f"Server-v2 privacy disclosure is missing: {disclosure}")

    message_id = await click_privacy_button(websocket, message_id, "카메라/GPS 권한 요청")
    state, message_id = await wait_for_privacy_state(
        websocket,
        message_id,
        timeout_seconds,
        lambda item: privacy_probe(item).get("cameraFailed", 0) >= 1,
        "the initial synthetic camera permission failure",
    )
    camera_failure_fallback, message_id = await wait_for_tts_failure_fallback(
        websocket,
        message_id,
        timeout_seconds,
        "실시간 장애물 탐지를 사용할 수 없습니다",
    )
    camera_recovery_armed = await evaluate(
        websocket,
        "window.__walksafePrivacyProbe.failCamera = false; true",
        message_id=message_id,
    )
    message_id += 1
    if camera_recovery_armed is not True:
        raise CheckFailed("Could not release the synthetic camera failure probe")
    message_id = await click_privacy_button(websocket, message_id, "카메라/GPS 권한 요청")
    state, message_id = await wait_for_privacy_state(
        websocket,
        message_id,
        timeout_seconds,
        lambda item: item.get("cameraReady") is True,
        "the synthetic rear camera",
    )
    await asyncio.sleep(0.7)
    state = await evaluate(websocket, PRIVACY_STATE_EXPRESSION, message_id=message_id)
    message_id += 1
    probe = privacy_probe(state if isinstance(state, dict) else None)
    if probe.get("detectStarted") != 0 or probe.get("reportStarted") != 0:
        raise CheckFailed(f"Server-v2 transferred data before explicit consent: {state}")

    armed = await evaluate(
        websocket,
        "window.__walksafePrivacyProbe.failDetect = true; true",
        message_id=message_id,
    )
    message_id += 1
    if armed is not True:
        raise CheckFailed("Could not arm the synthetic detector-outage probe")
    message_id = await click_privacy_button(websocket, message_id, "전송 항목 확인·서버 탐지 동의")
    state, message_id = await wait_for_privacy_state(
        websocket,
        message_id,
        timeout_seconds,
        lambda item: (
            item.get("processingAllowed") is True
            and item.get("reportStopped") is True
            and privacy_probe(item).get("detectWithGps", 0) >= 1
        ),
        "consented server-v2 detection with synthetic GPS",
    )
    probe = privacy_probe(state)
    if probe.get("reportStarted") != 0 or probe.get("detectContextErrors") != 0:
        raise CheckFailed(f"Processing-only consent crossed the report-storage boundary: {state}")
    if state.get("reportAllowDisabled") is not False:
        raise CheckFailed(f"Report consent did not become independently selectable: {state}")
    speech_fallback, message_id = await wait_for_tts_failure_fallback(
        websocket,
        message_id,
        timeout_seconds,
        "실시간 장애물 탐지 오류",
    )
    failed_detect_count = probe.get("detectFailed", 0)
    resumed = await evaluate(
        websocket,
        "window.__walksafePrivacyProbe.failDetect = false; true",
        message_id=message_id,
    )
    message_id += 1
    if resumed is not True:
        raise CheckFailed("Could not release the synthetic detector-outage probe")
    state, message_id = await wait_for_privacy_state(
        websocket,
        message_id,
        timeout_seconds,
        lambda item: (
            privacy_probe(item).get("detectFailed", 0) >= failed_detect_count
            and privacy_probe(item).get("detectSucceeded", 0) >= 1
        ),
        "server detection to recover after the synthetic detector outage",
    )

    message_id = await click_privacy_button(websocket, message_id, "저장 항목 확인·자동 신고 동의")
    state, message_id = await wait_for_privacy_state(
        websocket,
        message_id,
        timeout_seconds,
        lambda item: (
            item.get("processingAllowed") is True
            and item.get("reportAllowed") is True
            and privacy_probe(item).get("reportStarted", 0) >= 1
            and privacy_probe(item).get("reportPending", 0) >= 1
        ),
        "an in-flight synthetic automatic report",
    )
    updated = await evaluate(
        websocket,
        "window.__walksafePrivacyProbe.stallDetect = true; true",
        message_id=message_id,
    )
    message_id += 1
    if updated is not True:
        raise CheckFailed("Could not arm the report-only detect continuity probe")
    state, message_id = await wait_for_privacy_state(
        websocket,
        message_id,
        timeout_seconds,
        lambda item: privacy_probe(item).get("detectPending", 0) >= 1,
        "an in-flight detect request before report-only withdrawal",
    )
    report_detect_aborts = privacy_probe(state).get("detectAborted", 0)
    message_id = await click_privacy_button(websocket, message_id, "신고 저장 동의 철회·업로드 취소")
    state, message_id = await wait_for_privacy_state(
        websocket,
        message_id,
        min(timeout_seconds, PRIVACY_WITHDRAWAL_ABORT_TIMEOUT_SECONDS),
        lambda item: (
            item.get("processingAllowed") is True
            and item.get("reportStopped") is True
            and privacy_probe(item).get("reportAborted", 0) >= 1
            and privacy_probe(item).get("reportPending") == 0
            and privacy_probe(item).get("detectPending", 0) >= 1
        ),
        "report-only withdrawal to abort the report upload",
    )
    probe = privacy_probe(state)
    if probe.get("detectAborted", 0) != report_detect_aborts:
        raise CheckFailed(f"Report-only withdrawal interrupted server detection: {state}")
    released = await evaluate(
        websocket,
        """
(() => {
  const probe = window.__walksafePrivacyProbe;
  probe.stallDetect = false;
  if (typeof probe.releaseDetect !== 'function') return false;
  probe.releaseDetect();
  return true;
})()
""",
        message_id=message_id,
    )
    message_id += 1
    if released is not True:
        raise CheckFailed("Could not release the report-only detect continuity probe")
    detect_before_continuation = probe.get("detectStarted", 0)
    state, message_id = await wait_for_privacy_state(
        websocket,
        message_id,
        timeout_seconds,
        lambda item: (
            privacy_probe(item).get("detectPending") == 0
            and privacy_probe(item).get("detectStarted", 0) > detect_before_continuation
        ),
        "server detection to continue after report-only withdrawal",
    )

    message_id = await click_privacy_button(websocket, message_id, "저장 항목 확인·자동 신고 동의")
    state, message_id = await wait_for_privacy_state(
        websocket,
        message_id,
        timeout_seconds,
        lambda item: (
            item.get("reportAllowed") is True
            and privacy_probe(item).get("reportStarted", 0) >= 2
            and privacy_probe(item).get("reportPending", 0) >= 1
        ),
        "a second in-flight report before processing consent withdrawal",
    )
    baseline_detect_aborts = privacy_probe(state).get("detectAborted", 0)
    baseline_report_aborts = privacy_probe(state).get("reportAborted", 0)
    updated = await evaluate(
        websocket,
        "window.__walksafePrivacyProbe.stallDetect = true; true",
        message_id=message_id,
    )
    message_id += 1
    if updated is not True:
        raise CheckFailed("Could not arm the synthetic in-flight detect probe")
    state, message_id = await wait_for_privacy_state(
        websocket,
        message_id,
        timeout_seconds,
        lambda item: privacy_probe(item).get("detectPending", 0) >= 1,
        "an in-flight synthetic detect request",
    )
    message_id = await click_privacy_button(websocket, message_id, "처리 동의 철회·탐지/신고 전송 취소")
    state, message_id = await wait_for_privacy_state(
        websocket,
        message_id,
        min(timeout_seconds, PRIVACY_WITHDRAWAL_ABORT_TIMEOUT_SECONDS),
        lambda item: (
            item.get("initialProcessing") is True
            and item.get("reportStopped") is True
            and privacy_probe(item).get("detectAborted", 0) > baseline_detect_aborts
            and privacy_probe(item).get("detectPending") == 0
            and privacy_probe(item).get("reportAborted", 0) > baseline_report_aborts
            and privacy_probe(item).get("reportPending") == 0
        ),
        "processing withdrawal to abort detection and its dependent report upload",
    )
    stopped_detect_count = privacy_probe(state).get("detectStarted", 0)
    await asyncio.sleep(1.0)
    state = await evaluate(websocket, PRIVACY_STATE_EXPRESSION, message_id=message_id)
    message_id += 1
    if not isinstance(state, dict) or privacy_probe(state).get("detectStarted") != stopped_detect_count:
        raise CheckFailed(f"Detection scheduling continued after processing consent withdrawal: {state}")
    final_probe = privacy_probe(state)
    if final_probe.get("blockedExternalFetches") != 0:
        raise CheckFailed(f"Production privacy check attempted an external fetch: {state}")

    await cdp_call(websocket, "Page.reload", {}, message_id=message_id)
    message_id += 1
    reloaded, message_id = await wait_for_privacy_state(
        websocket,
        message_id,
        timeout_seconds,
        lambda item: (
            item.get("initialProcessing") is True
            and item.get("reportStopped") is True
            and item.get("reportAllowDisabled") is True
        ),
        "server-v2 privacy consent to reset after reload",
    )
    return {
        "initial_fail_closed": True,
        "initial_camera_failure_tts_assertive_live_fallback": camera_failure_fallback,
        "separate_processing_and_storage_consent": True,
        "processing_only_detected_without_reporting": True,
        "tts_failure_assertive_live_fallback": speech_fallback,
        "report_withdrawal_aborted_upload_only": True,
        "processing_withdrawal_aborted_detection": True,
        "processing_withdrawal_aborted_dependent_report": True,
        "reload_reset_consent": True,
        "external_fetch_attempts": 0,
        "synthetic_detect_started": stopped_detect_count,
        "synthetic_report_started": final_probe.get("reportStarted", 0),
        "reloaded_report_consent_disabled": reloaded.get("reportAllowDisabled") is True,
    }, message_id


def non_metric_advisory_log_evidence(root: Path) -> dict[str, Any] | None:
    for path in sorted(root.glob("*/*.jsonl")):
        for line in path.read_text(encoding="utf-8").splitlines():
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            payload = record.get("payload") if isinstance(record, dict) else None
            if not isinstance(payload, dict) or payload.get("non_metric_advisory_active") is not True:
                continue
            frames = payload.get("non_metric_advisory_consecutive_frames")
            stable_ms = payload.get("non_metric_advisory_stable_ms")
            if (
                payload.get("non_metric_advisory_tier") == "CAMERA_NON_METRIC_ADVISORY"
                and payload.get("non_metric_advisory_direction") == "front"
                and payload.get("non_metric_advisory_message") == EXPECTED_ADVISORY_MESSAGE
                and isinstance(frames, int) and not isinstance(frames, bool) and frames >= 3
                and isinstance(stable_ms, (int, float)) and not isinstance(stable_ms, bool) and stable_ms >= 700
                and payload.get("non_metric_advisory_metric") is False
                and payload.get("non_metric_advisory_tmap_authoritative") is True
                and payload.get("non_metric_advisory_reports_allowed") is False
                and payload.get("navigation_active") is True
                and payload.get("risk_active") is False
            ):
                return {
                    "path": str(path),
                    "event_type": record.get("event_type"),
                    "consecutive_frames": frames,
                    "stable_ms": stable_ms,
                    "metric": False,
                    "tmap_authoritative": True,
                    "reports_allowed": False,
                }
    return None


def advisory_frame_span_ms(state: dict[str, Any]) -> float | None:
    raw = privacy_probe(state).get("advisoryCapturedAt")
    if not isinstance(raw, list) or len(set(raw)) < 3 or not all(isinstance(item, str) for item in raw):
        return None
    try:
        times = [datetime.fromisoformat(item.replace("Z", "+00:00")).timestamp() * 1000 for item in raw]
    except ValueError:
        return None
    return max(times) - min(times)


async def exercise_non_metric_advisory(
    websocket: Any,
    message_id: int,
    timeout_seconds: float,
    field_log_dir: Path,
) -> tuple[dict[str, Any], int]:
    armed = await evaluate(
        websocket,
        """
(() => {
  const probe = window.__walksafePrivacyProbe;
  if (!probe) return false;
  probe.failCamera = false;
  probe.failDetect = false;
  probe.stallDetect = false;
  probe.advisoryMode = true;
  probe.recordAdvisoryFrames = false;
  probe.advisoryCapturedAt = [];
  return true;
})()
""",
        message_id=message_id,
    )
    message_id += 1
    if armed is not True:
        raise CheckFailed("Could not arm the synthetic non-metric advisory phase")

    message_id = await click_privacy_button(websocket, message_id, "고지 확인·수집 동의")
    _, message_id = await wait_for_privacy_state(
        websocket, message_id, timeout_seconds,
        lambda item: item.get("telemetryAllowed") is True,
        "independent field telemetry consent",
    )
    message_id = await click_privacy_button(websocket, message_id, "카메라/GPS 권한 요청")
    _, message_id = await wait_for_privacy_state(
        websocket, message_id, timeout_seconds,
        lambda item: item.get("cameraReady") is True,
        "the advisory synthetic rear camera",
    )
    message_id = await click_privacy_button(websocket, message_id, "전송 항목 확인·서버 탐지 동의")
    _, message_id = await wait_for_privacy_state(
        websocket, message_id, timeout_seconds,
        lambda item: (
            item.get("processingAllowed") is True
            and item.get("reportStopped") is True
            and privacy_probe(item).get("detectSucceeded", 0) >= 1
        ),
        "processing-only normal-tactile detection",
    )
    message_id = await click_privacy_button(websocket, message_id, "음성 켜짐")

    for command_index in (1, 2):
        message_id = await click_privacy_button(websocket, message_id, "음성 명령")
        _, message_id = await wait_for_privacy_state(
            websocket, message_id, timeout_seconds,
            lambda item: "녹음 종료" in str(item.get("text", "")),
            f"synthetic voice recording {command_index}",
        )
        message_id = await click_privacy_button(websocket, message_id, "녹음 종료")
        if command_index == 1:
            _, message_id = await wait_for_privacy_state(
                websocket, message_id, timeout_seconds,
                lambda item: (
                    privacy_probe(item).get("voiceStarted") == 1
                    and privacy_probe(item).get("destinationSearchStarted") == 1
                    and "길안내 시작이라고 말하면" in str(item.get("text", ""))
                ),
                "the fixture-backed synthetic destination",
            )

    state, message_id = await wait_for_privacy_state(
        websocket, message_id, timeout_seconds,
        lambda item: (
            item.get("navigationActive") is True
            and privacy_probe(item).get("voiceStarted") == 2
            and privacy_probe(item).get("routeStarted") == 1
            and privacy_probe(item).get("navigationContractErrors") == 0
        ),
        "fixture-backed TMAP navigation",
    )
    vibration_baseline = await evaluate(
        websocket,
        """
(() => {
  const probe = window.__walksafePrivacyProbe;
  if (!probe) return null;
  const vibrateCallsBefore = probe.vibrateCalls;
  probe.recordAdvisoryFrames = true;
  return { vibrateCallsBefore, installError: probe.vibrateInstallError };
})()
""",
        message_id=message_id,
    )
    message_id += 1
    if not isinstance(vibration_baseline, dict):
        raise CheckFailed("Could not arm advisory inference continuity and vibration measurement")
    vibrate_calls_before = vibration_baseline.get("vibrateCallsBefore")
    if isinstance(vibrate_calls_before, bool) or not isinstance(vibrate_calls_before, int):
        raise CheckFailed(f"Navigator vibration probe did not return a numeric baseline: {vibration_baseline}")
    if vibration_baseline.get("installError") is not None:
        raise CheckFailed(f"Could not install the navigator.vibrate probe: {vibration_baseline}")

    state, message_id = await wait_for_privacy_state(
        websocket, message_id, timeout_seconds,
        lambda item: (
            advisory_frame_span_ms(item) is not None
            and advisory_frame_span_ms(item) >= 700
            and EXPECTED_ADVISORY_MESSAGE in str(item.get("advisoryText", ""))
            and privacy_probe(item).get("telemetrySucceeded", 0) >= 1
        ),
        "three stable non-metric advisory frames and telemetry",
    )
    probe = privacy_probe(state)
    advisory_text = str(state.get("advisoryText", ""))
    forbidden = ("STOP", "즉시 정지", "미터", "걸음", "로컬 경로", "정렬", "경로 변경", "안전합니다", "고위험")
    forbidden_found = [item for item in forbidden if item.casefold() in advisory_text.casefold()]
    if forbidden_found or state.get("riskDanger") is True:
        raise CheckFailed(f"Non-metric advisory used forbidden metric/safety language: {forbidden_found}")
    if probe.get("reportStarted") != 0 or state.get("reportStopped") is not True:
        raise CheckFailed(f"Non-metric advisory crossed the report-storage boundary: {state}")

    deadline = time.monotonic() + timeout_seconds
    log_evidence = non_metric_advisory_log_evidence(field_log_dir)
    while log_evidence is None and time.monotonic() < deadline:
        await asyncio.sleep(0.1)
        log_evidence = non_metric_advisory_log_evidence(field_log_dir)
    if log_evidence is None:
        raise CheckFailed(f"Production field JSONL did not record the bounded advisory contract: {field_log_dir}")

    vibration_after = await evaluate(
        websocket,
        """
(() => {
  const probe = window.__walksafePrivacyProbe;
  return probe ? { vibrateCalls: probe.vibrateCalls, installError: probe.vibrateInstallError } : null;
})()
""",
        message_id=message_id,
    )
    message_id += 1
    if not isinstance(vibration_after, dict) or vibration_after.get("installError") is not None:
        raise CheckFailed(f"Could not read the navigator.vibrate probe after the advisory: {vibration_after}")
    vibrate_calls_after = vibration_after.get("vibrateCalls")
    if isinstance(vibrate_calls_after, bool) or not isinstance(vibrate_calls_after, int):
        raise CheckFailed(f"Navigator vibration probe did not return a numeric result: {vibration_after}")
    vibrate_delta = vibrate_calls_after - vibrate_calls_before
    if vibrate_delta != 0:
        raise CheckFailed(f"Low non-metric advisory called navigator.vibrate {vibrate_delta} time(s)")

    return {
        "synthetic_browser_local": True,
        "fixture": "contracts/fixtures/walking-route-v1.json",
        "tmap_navigation_active": True,
        "distinct_advisory_frames": len(set(probe.get("advisoryCapturedAt", []))),
        "advisory_frame_span_ms": advisory_frame_span_ms(state),
        "report_storage_consented": False,
        "report_requests": 0,
        "navigator_vibrate_calls_before_advisory": vibrate_calls_before,
        "navigator_vibrate_calls_after_advisory": vibrate_calls_after,
        "navigator_vibrate_delta": vibrate_delta,
        "ui_message": EXPECTED_ADVISORY_MESSAGE,
        "ui_forbidden_language": [],
        "field_log": log_evidence,
    }, message_id


async def exercise_update_ui(websocket: Any, message_id: int, timeout_seconds: float) -> tuple[dict[str, Any], int]:
    trigger = await evaluate(
        websocket,
        """
(async () => {
  const scriptUrl = `/sw.js?e2e_update=${Date.now()}`;
  const registration = await navigator.serviceWorker.register(scriptUrl, { scope: '/' });
  return { scriptUrl, scope: registration.scope };
})()
""",
        message_id=message_id,
    )
    message_id += 1
    if not isinstance(trigger, dict) or not isinstance(trigger.get("scriptUrl"), str):
        raise CheckFailed(f"Could not trigger a same-scope service-worker update: {trigger}")

    await evaluate(
        websocket,
        """
(() => {
  const expand = [...document.querySelectorAll('button')]
    .find((item) => item.innerText.trim() === '펼치기');
  if (expand && !expand.disabled) expand.click();
  return Boolean(expand);
})()
""",
        message_id=message_id,
    )
    message_id += 1

    deadline = time.monotonic() + timeout_seconds
    waiting_state: dict[str, Any] | None = None
    while time.monotonic() < deadline:
        state = await evaluate(
            websocket,
            """
(async () => {
  const registration = await navigator.serviceWorker.getRegistration('/');
  const button = [...document.querySelectorAll('button')]
    .find((item) => item.innerText.includes('오프라인 셸 업데이트 적용'));
  return {
    waiting: Boolean(registration?.waiting),
    buttonEnabled: Boolean(button && !button.disabled),
    text: document.body?.innerText ?? ''
  };
})()
""",
            message_id=message_id,
            timeout_seconds=deadline - time.monotonic(),
        )
        message_id += 1
        waiting_state = state if isinstance(state, dict) else None
        if waiting_state and waiting_state.get("waiting") and waiting_state.get("buttonEnabled"):
            break
        await asyncio.sleep(0.25)
    else:
        raise CheckFailed(f"Updated worker never reached the waiting/user-apply UI state: {waiting_state}")

    clicked = await evaluate(
        websocket,
        """
(() => {
  const button = [...document.querySelectorAll('button')]
    .find((item) => item.innerText.includes('오프라인 셸 업데이트 적용'));
  if (!button || button.disabled) return false;
  button.click();
  return true;
})()
""",
        message_id=message_id,
    )
    message_id += 1
    if clicked is not True:
        raise CheckFailed("PWA update apply button was not actionable")

    applied_state: dict[str, Any] | None = None
    while time.monotonic() < deadline:
        state = await evaluate(
            websocket,
            """
(() => ({
  controllerUrl: navigator.serviceWorker.controller?.scriptURL ?? null,
  offlineShellReady: document.documentElement?.dataset.walksafeOfflineShellReady === 'true',
  text: document.body?.innerText ?? ''
}))()
""",
            message_id=message_id,
            timeout_seconds=deadline - time.monotonic(),
        )
        message_id += 1
        applied_state = state if isinstance(state, dict) else None
        if (
            applied_state
            and "e2e_update=" in str(applied_state.get("controllerUrl"))
            and applied_state.get("offlineShellReady") is True
            and "새 오프라인 셸 적용 완료" in str(applied_state.get("text"))
        ):
            return {
                "evidence_scope": "synthetic_same_build_query_trigger_ui_wiring_only",
                "waiting_ui_observed": True,
                "apply_button_clicked": True,
                "controller_url": applied_state.get("controllerUrl"),
                "applied_ui_observed": True,
            }, message_id
        await asyncio.sleep(0.25)
    raise CheckFailed(f"Applied worker/controller UI state was not confirmed: {applied_state}")


async def run_browser_check(
    args: argparse.Namespace,
    web_url: str,
    web_process: subprocess.Popen[str],
    chrome_instance_url: str,
    field_actor_id: str,
    field_account_token: str,
    field_log_dir: Path,
) -> dict[str, Any]:
    targets = http_json(f"http://127.0.0.1:{args.chrome_debug_port}/json", timeout=5.0)
    page = select_launched_chrome_page(
        targets,
        chrome_instance_url,
        args.chrome_debug_port,
    )

    async with websockets.connect(page["webSocketDebuggerUrl"], max_size=8 * 1024 * 1024) as websocket:
        message_id = 1
        for method, params in (
            ("Page.enable", {}),
            ("Runtime.enable", {}),
            ("Network.enable", {}),
            ("Page.addScriptToEvaluateOnNewDocument", {"source": privacy_probe_init_script()}),
            ("Page.navigate", {"url": web_url}),
        ):
            await cdp_call(websocket, method, params, message_id=message_id)
            message_id += 1

        authentication = await evaluate(
            websocket,
            f"""
(async () => {{
  const response = await fetch('/api/field-session', {{
    method: 'POST',
    headers: {{ 'content-type': 'application/json', 'x-real-ip': '127.0.0.1' }},
    body: JSON.stringify({{
      actor_id: {json.dumps(field_actor_id)},
      token: {json.dumps(field_account_token)}
    }}),
    cache: 'no-store'
  }});
  if (!response.ok) return {{ ok: false, status: response.status }};
  window.location.reload();
  return {{ ok: true, status: response.status }};
}})()
""",
            message_id=message_id,
        )
        message_id += 1
        if not isinstance(authentication, dict) or authentication.get("ok") is not True:
            raise CheckFailed(f"Could not establish the isolated PWA field session: {authentication}")

        privacy_state, message_id = await exercise_server_v2_privacy_controls(
            websocket,
            message_id,
            args.browser_timeout,
        )
        pwa_state, message_id = await wait_for_pwa_ready(websocket, message_id, args.browser_timeout)
        advisory_state, message_id = await exercise_non_metric_advisory(
            websocket,
            message_id,
            args.browser_timeout,
            field_log_dir,
        )

        manifest = await cdp_call(websocket, "Page.getAppManifest", {}, message_id=message_id)
        message_id += 1
        manifest_errors = manifest.get("errors", []) if isinstance(manifest, dict) else []
        if manifest_errors:
            raise CheckFailed(f"Browser rejected the web app manifest: {manifest_errors}")
        manifest_data = manifest.get("data", "") if isinstance(manifest, dict) else ""
        try:
            parsed_manifest = json.loads(manifest_data)
        except (TypeError, json.JSONDecodeError) as exc:
            raise CheckFailed(f"Browser did not return a valid app manifest: {manifest!r}") from exc
        if parsed_manifest.get("display") != "standalone" or parsed_manifest.get("start_url") != "/":
            raise CheckFailed(f"Unexpected install manifest contract: {parsed_manifest}")

        installability = await cdp_call(websocket, "Page.getInstallabilityErrors", {}, message_id=message_id)
        message_id += 1
        installability_errors = installability.get("installabilityErrors", []) if isinstance(installability, dict) else []
        if installability_errors:
            raise CheckFailed(f"Chromium installability errors: {installability_errors}")

        update_state, message_id = await exercise_update_ui(websocket, message_id, args.browser_timeout)

        # CDP's page-target offline emulation does not necessarily block fetches
        # performed inside a service-worker target. Stop the isolated origin so
        # the reload and API checks prove a real network outage.
        stop_owned_process(
            web_process,
            args.web_port,
            "the isolated PWA origin before the offline check",
        )

        await cdp_call(
            websocket,
            "Network.emulateNetworkConditions",
            {"offline": True, "latency": 0, "downloadThroughput": 0, "uploadThroughput": 0},
            message_id=message_id,
        )
        message_id += 1
        offline_state = await evaluate(
            websocket,
            """
(async () => {
  const shell = await fetch('/').then((response) => ({ ok: response.ok, status: response.status })).catch(() => null);
  const api = await fetch('/api/health', { cache: 'no-store' })
    .then((response) => ({ rejected: false, ok: response.ok, status: response.status, type: response.type }))
    .catch(() => ({ rejected: true, ok: false, status: null, type: 'error' }));
  return { shell, api };
})()
""",
            message_id=message_id,
        )
        offline_shell = offline_state.get("shell") if isinstance(offline_state, dict) else None
        if not isinstance(offline_shell, dict) or offline_shell.get("ok") is not True:
            raise CheckFailed(f"Cached root shell was unavailable offline: {offline_state}")
        offline_api = offline_state.get("api")
        if not isinstance(offline_api, dict) or offline_api.get("ok") is not False:
            raise CheckFailed(f"Safety API unexpectedly returned successful data while offline: {offline_state}")

        offline_reload_url = f"{web_url}/?walksafe_offline_reload=1"
        await cdp_call(websocket, "Page.navigate", {"url": offline_reload_url}, message_id=message_id)
        message_id += 1
        deadline = time.monotonic() + args.browser_timeout
        offline_reload_state: dict[str, Any] | None = None
        while time.monotonic() < deadline:
            state = await evaluate(
                websocket,
                """
(() => ({
  offlineReloadCommitted: location.search.includes('walksafe_offline_reload=1'),
  readyState: document.readyState,
  text: document.body?.innerText ?? '',
  nextScripts: [...document.scripts].filter((item) => item.src.includes('/_next/static/')).length,
  nextStyles: [...document.styleSheets].filter((item) => item.href?.includes('/_next/static/')).length,
  hydrated: document.documentElement?.dataset.walksafeHydrated === 'true'
}))()
""",
                message_id=message_id,
                timeout_seconds=deadline - time.monotonic(),
            )
            message_id += 1
            offline_reload_state = state if isinstance(state, dict) else None
            if (
                offline_reload_state
                and offline_reload_state.get("offlineReloadCommitted") is True
                and offline_reload_state.get("readyState") == "complete"
                and offline_reload_state.get("nextScripts", 0) > 0
                and offline_reload_state.get("nextStyles", 0) > 0
                and offline_reload_state.get("hydrated") is True
                and "현장 테스트 인증" in str(offline_reload_state.get("text", ""))
            ):
                break
            await asyncio.sleep(0.25)
        else:
            raise CheckFailed(f"Offline reload did not restore the Next.js shell and chunks: {offline_reload_state}")

        return {
            "schema_version": "walksafe.pwa_browser_evidence.v2",
            "captured_at": datetime.now(UTC).isoformat(),
            "web_url": web_url,
            "manifest_installable": True,
            "service_worker_active": True,
            "service_worker_controls_page": bool(pwa_state.get("controlled")),
            "service_worker_version": pwa_state.get("version"),
            "synthetic_service_worker_update_ui_wiring": update_state,
            "server_v2_privacy_controls": privacy_state,
            "non_metric_advisory": advisory_state,
            "offline_shell_available": True,
            "offline_reload_hydrated": True,
            "offline_authentication_gate_only": True,
            "offline_safety_api_unavailable": True,
            "scope": pwa_state.get("scope"),
            "cache_names": pwa_state.get("cacheNames"),
            "claim_limits": [
                "headless Chromium browser lifecycle and server-v2 privacy-control wiring only",
                "camera, GPS, detect, and report behavior use synthetic browser-local probes",
                "speech failure and accessible live fallback use a synthetic browser-local speech probe",
                "navigation, voice, normal-tactile detections, and non-metric advisory use synthetic browser-local probes",
                "the walking-route response reuses the checked-in contract fixture but does not call live TMAP",
                "does not prove detector inference, backend persistence, or real personal-data handling",
                "does not prove real TMAP/STT, phone camera/GPS, WebXR, or mobile performance",
                "does not prove mobile installation or update UX",
                "the waiting/apply UI wiring uses a same-build query trigger and does not prove release-to-release update",
                "offline reload exposes only the authentication/static shell",
                "does not prove offline detection, report, navigation, or voice availability",
            ],
        }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--web-port", type=int, default=3100)
    parser.add_argument("--chrome-debug-port", type=int, default=9322)
    parser.add_argument("--chrome-bin", default=None)
    parser.add_argument("--browser-timeout", type=float, default=30.0)
    parser.add_argument("--evidence-out", type=Path, default=None)
    parser.add_argument(
        "--existing-build-dir",
        default=None,
        help="Reuse an existing .next or safe .next-* production build instead of rebuilding it.",
    )
    parser.add_argument(
        "--keep-open",
        action="store_true",
        help="Rejected because browser evidence must verify owned-process cleanup.",
    )
    return parser.parse_args()


def main() -> int:
    global CDP_RESPONSE_TIMEOUT_SECONDS
    args = parse_args()
    if not math.isfinite(args.browser_timeout) or args.browser_timeout <= 0:
        raise CheckFailed("--browser-timeout must be a positive finite number")
    if args.keep_open:
        raise CheckFailed("--keep-open is incompatible with browser lifecycle evidence")
    CDP_RESPONSE_TIMEOUT_SECONDS = args.browser_timeout
    lifecycle_lock = acquire_lifecycle_lock()
    try:
        require_isolated_loopback_ports(
            {
                "PWA web": args.web_port,
                "Chromium debug": args.chrome_debug_port,
            }
        )
        web_url = f"http://127.0.0.1:{args.web_port}"
        temp_dir = Path(tempfile.mkdtemp(prefix="walksafe-pwa-lifecycle-"))
        dist_dir_name = args.existing_build_dir or f".next-pwa-lifecycle-{os.getpid()}"
        if dist_dir_name != ".next" and re.fullmatch(r"\.next-[A-Za-z0-9._-]+", dist_dir_name) is None:
            raise CheckFailed("--existing-build-dir must be .next or a safe .next-* directory name")
        dist_dir = REPO_ROOT / "apps/web" / dist_dir_name
        generated_typescript_files = [
            REPO_ROOT / "apps/web/tsconfig.json",
            REPO_ROOT / "apps/web/next-env.d.ts",
        ]
        original_typescript_files = {
            path: path.read_bytes() for path in generated_typescript_files
        }
    except BaseException:
        lifecycle_lock.close()
        raise
    web_process: subprocess.Popen[str] | None = None
    chrome_process: subprocess.Popen[str] | None = None

    try:
        web_env = os.environ.copy()
        (
            field_actor_id,
            field_account_token,
            backend_field_token,
            session_secret,
        ) = new_lifecycle_credentials()
        rate_limit_dir = temp_dir / "gateway-rate-limits"
        rate_limit_dir.mkdir(mode=0o700)
        field_log_dir = temp_dir / "web-field-logs"
        web_env.update(
            {
                "NEXT_PUBLIC_WALKSAFE_PWA_ENABLED": "true",
                "NEXT_PUBLIC_DETECTOR_MODE": "server-v2",
                "BACKEND_API_BASE_URL": "http://127.0.0.1:8000",
                "VOICE_API_BASE_URL": "http://127.0.0.1:9001",
                "WALKSAFE_FIELD_TEST_TOKEN": backend_field_token,
                "WALKSAFE_FIELD_ACCOUNTS_JSON": json.dumps(
                    [{"actor_id": field_actor_id, "token": field_account_token}]
                ),
                "WALKSAFE_GATEWAY_SESSION_SECRET": session_secret,
                "WALKSAFE_ALLOW_INSECURE_LOCAL_DEV": "false",
                "WALKSAFE_GATEWAY_RATE_LIMIT_DIR": str(rate_limit_dir),
                "WALKSAFE_GATEWAY_TRUSTED_IP_HEADER": "x-real-ip",
                "WALKSAFE_ENVIRONMENT": "test",
                "WALKSAFE_WEB_REPLICAS": "1",
                "WALKSAFE_WEB_PROCESS_LOCK_PATH": str(temp_dir / "web-process.lock"),
                "WALKSAFE_FIELD_LOG_DIR": str(field_log_dir),
            }
        )
        if dist_dir_name != ".next":
            web_env["WALKSAFE_NEXT_DIST_DIR"] = dist_dir_name
        else:
            web_env.pop("WALKSAFE_NEXT_DIST_DIR", None)
        if args.existing_build_dir:
            if not (dist_dir / "BUILD_ID").is_file():
                raise CheckFailed(f"existing PWA build is missing BUILD_ID: {dist_dir}")
        else:
            with (temp_dir / "web-build.log").open("w", encoding="utf-8") as build_log:
                build = subprocess.run(
                    ["npm", "run", "build"],
                    cwd=REPO_ROOT / "apps/web",
                    env=web_env,
                    stdout=build_log,
                    stderr=subprocess.STDOUT,
                    text=True,
                    timeout=180,
                    check=False,
                )
            if build.returncode != 0:
                raise CheckFailed(f"PWA production build failed; see {temp_dir / 'web-build.log'}")
        web_process = start_process(
            ["npm", "run", "start", "--", "--hostname", "127.0.0.1", "--port", str(args.web_port)],
            cwd=REPO_ROOT / "apps/web",
            env=web_env,
            log_path=temp_dir / "web.log",
        )
        wait_for_http(web_url, timeout=60.0)
        if web_process.poll() is not None:
            raise CheckFailed(f"PWA web process exited early; port {args.web_port} may already be in use")

        profile_dir = temp_dir / "chrome-profile"
        chrome_instance_url = (
            f"data:text/html,walksafe-pwa-lifecycle-{secrets.token_hex(16)}"
        )
        chrome_process = start_process(
            [
                chrome_binary(args.chrome_bin),
                "--headless=new",
                "--remote-debugging-address=127.0.0.1",
                f"--remote-debugging-port={args.chrome_debug_port}",
                f"--user-data-dir={profile_dir}",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-networking",
                "--disable-gpu",
                chrome_instance_url,
            ],
            cwd=REPO_ROOT,
            env=os.environ.copy(),
            log_path=temp_dir / "chrome.log",
        )
        wait_for_http(f"http://127.0.0.1:{args.chrome_debug_port}/json", timeout=30.0)
        if chrome_process.poll() is not None:
            raise CheckFailed(f"Chromium exited early; debug port {args.chrome_debug_port} may already be in use")
        evidence = asyncio.run(
            run_browser_check(
                args,
                web_url,
                web_process,
                chrome_instance_url,
                field_actor_id,
                field_account_token,
                field_log_dir,
            )
        )

        if not args.keep_open:
            stop_owned_process(
                chrome_process,
                args.chrome_debug_port,
                "the Chromium evidence process",
            )
            chrome_process = None
            web_process = None

        if args.evidence_out:
            args.evidence_out.parent.mkdir(parents=True, exist_ok=True)
            args.evidence_out.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        print("PASS: PWA browser lifecycle, server-v2 privacy controls, and offline safety boundary")
        print(json.dumps(evidence, ensure_ascii=False, indent=2))
        print(f"logs={temp_dir}")
        return 0
    finally:
        if not args.keep_open:
            stop_process(chrome_process)
            stop_process(web_process)
            if not args.existing_build_dir:
                shutil.rmtree(dist_dir, ignore_errors=True)
        for path, content in original_typescript_files.items():
            if not path.exists() or path.read_bytes() != content:
                path.write_bytes(content)
        lifecycle_lock.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except CheckFailed as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1)
