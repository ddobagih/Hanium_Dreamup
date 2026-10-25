from __future__ import annotations

import asyncio
from concurrent.futures import Future
from concurrent.futures.process import BrokenProcessPool

import pytest

import voice.inference_process as inference


class _Process:
    def __init__(self):
        self.alive = True

    def is_alive(self):
        return self.alive

    def terminate(self):
        self.alive = False

    def join(self, **_kwargs):
        pass


class _Executor:
    def __init__(self):
        self._processes = {1: _Process()}
        self.submitted = asyncio.Queue()
        self.shutdown_count = 0

    def submit(self, *_args):
        future = Future()
        # A real worker may already be running when its awaiting task is cancelled.
        future.set_running_or_notify_cancel()
        self.submitted.put_nowait(future)
        return future

    def shutdown(self, **_kwargs):
        self.shutdown_count += 1


@pytest.mark.parametrize("terminal", ["broken", "cancel", "timeout"])
def test_old_request_failure_does_not_abort_replacement_pool(monkeypatch, terminal):
    async def scenario():
        old, replacement = _Executor(), _Executor()
        executors = iter([old, replacement])
        monkeypatch.setattr(inference, "ProcessPoolExecutor", lambda **_kwargs: next(executors))
        pool = inference.IsolatedInferencePool("stt", 1)
        first = asyncio.create_task(pool.run("ready", timeout=10))
        await old.submitted.get()
        stale = asyncio.create_task(pool.run("infer", "old.wav", timeout=0.2 if terminal == "timeout" else 10))
        stale_future = await old.submitted.get()

        first.cancel()
        with pytest.raises(asyncio.CancelledError):
            await first
        assert old.shutdown_count == 1

        fresh = asyncio.create_task(pool.run("ready", timeout=10))
        fresh_future = await replacement.submitted.get()
        fresh_future.set_result({"ready": True})
        assert await fresh == {"ready": True}
        assert pool.ready

        if terminal == "broken":
            stale_future.set_exception(BrokenProcessPool("old worker exited"))
            expected = BrokenProcessPool
        elif terminal == "cancel":
            stale.cancel()
            expected = asyncio.CancelledError
        else:
            expected = inference.VoiceInferenceTimeout
        with pytest.raises(expected):
            await stale

        assert replacement.shutdown_count == 0
        assert pool.ready
        pool.close()
        assert replacement.shutdown_count == 1
        assert not pool.ready

    asyncio.run(scenario())


def test_old_completion_does_not_mark_new_unloaded_pool_ready(monkeypatch):
    async def scenario():
        old, replacement = _Executor(), _Executor()
        executors = iter([old, replacement])
        monkeypatch.setattr(inference, "ProcessPoolExecutor", lambda **_kwargs: next(executors))
        pool = inference.IsolatedInferencePool("tts", 1)
        stale = asyncio.create_task(pool.run("ready", timeout=10))
        stale_future = await old.submitted.get()
        pool.abort()

        fresh = asyncio.create_task(pool.run("ready", timeout=10))
        fresh_future = await replacement.submitted.get()
        assert not pool.ready
        stale_future.set_result({"ready": True})
        await stale
        assert not pool.ready
        fresh_future.set_result({"ready": True})
        await fresh
        assert pool.ready
        pool.close()

    asyncio.run(scenario())
