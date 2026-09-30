"""HTTP regressions for deadline-bound IPC on the default direct-spawn path."""

from __future__ import annotations

import asyncio
import os
import selectors
import signal
import sys
import time

from fastapi import FastAPI
import httpx
import pytest

from backend.app.api import detect as detect_api
from backend.app.services import inference_process as ipc


# The child speaks the real framed socket protocol. Its only replacement is the
# model-side behavior; parent spawn, socket I/O, selector and cleanup stay real.
_FAULT_CHILD = """
import os
import pickle
import socket
import sys
import time

channel = socket.socket(fileno=int(sys.argv[2]))
channel.settimeout(3)
mode = sys.argv[1]
marker_fd = int(sys.argv[3])
channel.sendall(b"R")
os.write(marker_fd, b"R")

def exact(size):
    data = bytearray()
    while len(data) < size:
        part = channel.recv(size - len(data))
        if not part:
            raise EOFError("parent closed the socket")
        data.extend(part)
    return bytes(data)

if mode == "stalled_send":
    os.write(marker_fd, b"S")
else:
    size = int.from_bytes(exact(8), "big")
    request = pickle.loads(exact(size))
    result = (request[0], "ok", {
        "model_status": "ready", "model_version": "ipc-http-test", "detections": [],
    })
    body = pickle.dumps(result, protocol=pickle.DEFAULT_PROTOCOL)
    header = len(body).to_bytes(8, "big")
    if mode == "partial_header":
        channel.sendall(header[:3])
        os.write(marker_fd, b"H")
    elif mode == "partial_body":
        channel.sendall(header + body[:3])
        os.write(marker_fd, b"B")
    elif mode == "healthy":
        channel.sendall(header + body)
        os.write(marker_fd, b"K")
    else:
        raise AssertionError("unknown test mode")

# Safety exit is not the product deadline. The parent must finish much sooner.
time.sleep(4)
"""


@pytest.mark.skipif(
    os.name != "posix" or not hasattr(os, "posix_spawn"),
    reason="the production direct-spawn path is POSIX-only",
)
@pytest.mark.parametrize(
    ("mode", "marker", "wait_event"),
    [
        ("stalled_send", b"S", selectors.EVENT_WRITE),
        ("partial_header", b"H", selectors.EVENT_READ),
        ("partial_body", b"B", selectors.EVENT_READ),
    ],
)
def test_http_ipc_fault_finishes_by_direct_spawn_deadline_and_recovers(
    monkeypatch: pytest.MonkeyPatch, mode: str, marker: bytes, wait_event: int,
) -> None:
    runner = ipc.InferenceProcessRunner(
        timeout_seconds=0.75,
        startup_timeout_seconds=0.75,
        queue_timeout_seconds=2.0,
    )
    assert runner._context is runner._direct_context

    spawned: list[tuple[int, int, float]] = []
    wait_events: list[int] = []
    real_spawn = os.posix_spawn
    real_wait = ipc._FramedSocket._wait
    image_bytes = b"x" * (8 * 1024 * 1024) if mode == "stalled_send" else b"frame"

    def record_wait(self: ipc._FramedSocket, event: int, deadline: float) -> None:
        wait_events.append(event)
        real_wait(self, event, deadline)

    def spawn_fault_child(path, argv, environment, **kwargs):
        assert path == sys.executable
        assert len(argv) == 3 and "_inference_worker" in argv[2]
        assert kwargs["setpgroup"] == 0
        socket_actions = [
            action for action in kwargs["file_actions"]
            if action[0] == os.POSIX_SPAWN_DUP2
        ]
        assert len(socket_actions) == 1
        socket_target_fd = socket_actions[0][2]
        assert socket_actions[0][1] != socket_target_fd
        read_fd, write_fd = os.pipe()
        try:
            if write_fd == socket_target_fd:
                replacement_fd = os.dup(write_fd)
                os.close(write_fd)
                write_fd = replacement_fd
            marker_fd = next(
                fd for fd in (101, 102, 103) if fd not in (socket_target_fd, write_fd)
            )
            child_mode = mode if not spawned else "healthy"
            pid = real_spawn(
                path,
                (path, "-c", _FAULT_CHILD, child_mode, str(socket_target_fd), str(marker_fd)),
                environment,
                file_actions=(*kwargs["file_actions"],
                              (os.POSIX_SPAWN_DUP2, write_fd, marker_fd)),
                setpgroup=0,
            )
        except BaseException:
            os.close(read_fd)
            raise
        finally:
            os.close(write_fd)
        spawned.append((pid, read_fd, time.monotonic()))
        assert os.getpgid(pid) == pid
        return pid

    async def sanitized(_image, _settings):
        return image_bytes, "image/png"

    monkeypatch.setattr(ipc.os, "posix_spawn", spawn_fault_child)
    monkeypatch.setattr(ipc._FramedSocket, "_wait", record_wait)
    monkeypatch.setattr(detect_api, "_read_detection_image_upload", sanitized)

    app = FastAPI()
    app.include_router(detect_api.create_router(object(), inference_runner=runner))
    request = {
        "data": {"context": "{}"},
        "files": {"image": ("frame.png", b"http-upload", "image/png")},
    }
    completed = False

    async def exercise() -> None:
        nonlocal image_bytes, completed
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            started = time.monotonic()
            fault = await asyncio.wait_for(client.post("/detect", **request), timeout=2.0)
            elapsed = time.monotonic() - started
            assert fault.status_code == 503
            assert fault.json()["detail"]["code"] == "detect_inference_deadline_exceeded"
            # 0.75 s IPC budget plus ample CI scheduling margin, still below
            # the route's 2.75 s queue+startup fallback and the child's 4 s exit.
            assert elapsed < 1.75, f"{mode} HTTP response took {elapsed:.3f}s"
            assert wait_event in wait_events, f"{mode} did not reach the blocked IPC wait"
            assert len(spawned) == 1
            assert marker in os.read(spawned[0][1], 16), f"{mode} child did not reach the fault"
            assert runner._process is None
            assert runner._connection is None
            assert runner._child_connection is None
            with pytest.raises(ChildProcessError):
                os.waitpid(spawned[0][0], os.WNOHANG)

            # Admission is deliberately closed for five seconds after creation.
            # A fresh HTTP call must be promptly rejected, not inherit stale IPC.
            started = time.monotonic()
            limited = await asyncio.wait_for(client.post("/detect", **request), timeout=1.0)
            assert time.monotonic() - started < 0.75
            assert limited.status_code == 503
            assert limited.json()["detail"]["code"] == "model_unavailable"
            assert len(spawned) == 1

            await asyncio.sleep(max(0.0, 5.1 - (time.monotonic() - spawned[0][2])))
            image_bytes = b"next-frame"
            started = time.monotonic()
            recovered = await asyncio.wait_for(client.post("/detect", **request), timeout=2.0)
            assert time.monotonic() - started < 1.75
            assert recovered.status_code == 200
            assert recovered.json()["model_version"] == "ipc-http-test"
            assert len(spawned) == 2
            assert b"K" in os.read(spawned[1][1], 16)
            completed = True

    try:
        asyncio.run(exercise())
    finally:
        if not completed:
            # Emergency cleanup is only for a failing assertion or hung request.
            # It cannot make a faulty product response count as success.
            for pid, _fd, _started in spawned:
                try:
                    os.killpg(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
        runner.close()
        for _pid, read_fd, _started in spawned:
            os.close(read_fd)


@pytest.mark.skipif(
    os.name != "posix" or not hasattr(os, "posix_spawn"),
    reason="the production direct-spawn path is POSIX-only",
)
@pytest.mark.parametrize(("source_fd", "child_fd"), [(100, 101), (101, 100)])
def test_direct_spawn_never_duplicates_a_cloexec_socket_onto_itself(
    monkeypatch: pytest.MonkeyPatch, source_fd: int, child_fd: int,
) -> None:
    observed: list[tuple[tuple[str, ...], tuple[tuple[int, int, int], ...], int]] = []

    def record_spawn(path, argv, environment, **kwargs):
        assert path == sys.executable
        observed.append((argv, kwargs["file_actions"], kwargs["setpgroup"]))
        return 123456

    class SocketAtFd:
        def fileno(self) -> int:
            return source_fd

    monkeypatch.setattr(ipc.os, "posix_spawn", record_spawn)
    ipc._spawn_posix_worker(SocketAtFd())
    assert len(observed) == 1
    argv, actions, process_group = observed[0]
    assert f"fileno={child_fd}" in argv[2]
    assert actions == ((os.POSIX_SPAWN_DUP2, source_fd, child_fd),)
    assert source_fd != child_fd
    assert process_group == 0
