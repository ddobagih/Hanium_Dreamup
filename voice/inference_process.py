"""Reusable process isolation for local speech models with hard deadlines."""

from __future__ import annotations

import asyncio
from concurrent.futures import ProcessPoolExecutor
from concurrent.futures.process import BrokenProcessPool
import multiprocessing
from pathlib import Path
from threading import Lock
from typing import Any


class VoiceInferenceTimeout(RuntimeError):
    pass


def _execute(kind: str, action: str, arguments: tuple[Any, ...]) -> Any:
    # Import inside spawned workers so CUDA/model initialization never occurs
    # in the ASGI parent process.
    from voice.server import get_stt_engine, get_tts_engine

    if kind == "stt":
        engine = get_stt_engine()
        if action == "ready":
            engine.load()
            return {"ready": True, "model": engine.model_size}
        if action == "infer":
            return engine.transcribe_file(Path(arguments[0]))
    elif kind == "tts":
        engine = get_tts_engine()
        if action == "ready":
            engine.load()
            return {"ready": True, "model": engine.model_id}
        if action == "infer":
            return engine.synthesize(str(arguments[0]), use_cache=bool(arguments[1]))
    raise ValueError("unsupported voice inference action")


class IsolatedInferencePool:
    """Keep models warm in spawned workers and terminate the pool on timeout."""

    def __init__(self, kind: str, max_workers: int) -> None:
        self.kind = kind
        self.max_workers = max_workers
        self._executor: ProcessPoolExecutor | None = None
        self._lock = Lock()
        self._ready = False

    def _get_executor(self) -> ProcessPoolExecutor:
        with self._lock:
            if self._executor is None:
                self._executor = ProcessPoolExecutor(
                    max_workers=self.max_workers,
                    mp_context=multiprocessing.get_context("spawn"),
                )
            return self._executor

    async def run(self, action: str, *arguments: Any, timeout: float) -> Any:
        executor = self._get_executor()
        try:
            future = executor.submit(_execute, self.kind, action, arguments)
            result = await asyncio.wait_for(asyncio.wrap_future(future), timeout=timeout)
            self._ready = True
            return result
        except TimeoutError as exc:
            self.abort()
            raise VoiceInferenceTimeout(f"{self.kind} inference exceeded its hard deadline") from exc
        except asyncio.CancelledError:
            self.abort()
            raise
        except BrokenProcessPool:
            self.abort()
            raise

    @property
    def ready(self) -> bool:
        with self._lock:
            executor = self._executor
            if not self._ready or executor is None:
                return False
            processes = list(getattr(executor, "_processes", {}).values())
            return bool(processes) and all(process.is_alive() for process in processes)

    def abort(self) -> None:
        with self._lock:
            executor = self._executor
            self._executor = None
            self._ready = False
        if executor is None:
            return
        processes = list(getattr(executor, "_processes", {}).values())
        executor.shutdown(wait=False, cancel_futures=True)
        for process in processes:
            if process.is_alive():
                process.terminate()
        for process in processes:
            process.join(timeout=1)
            if process.is_alive():
                process.kill()
                process.join(timeout=1)

    def close(self) -> None:
        self.abort()
