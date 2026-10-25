from __future__ import annotations

from collections.abc import AsyncGenerator
from contextlib import contextmanager
from contextvars import ContextVar
from threading import Lock
from urllib.parse import urlsplit

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker
from starlette.concurrency import run_in_threadpool

from backend.app.config import Settings, get_settings


class DatabaseResources:
    """An application's lazy engine and session factory; construction performs no I/O."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self._engine = None
        self._session_factory = None
        self._lock = Lock()

    @property
    def session_factory(self):
        with self._lock:
            if self._session_factory is None:
                settings = self.settings
                scheme = urlsplit(settings.database_url).scheme.split("+", 1)[0].lower()
                connect_args = ({
                    "connect_timeout": settings.database_connect_timeout_seconds,
                    "options": f"-c statement_timeout={settings.database_statement_timeout_ms}",
                } if scheme in {"postgres", "postgresql"} else {})
                self._engine = create_engine(
                    settings.database_url, pool_pre_ping=True,
                    pool_timeout=settings.database_connect_timeout_seconds,
                    hide_parameters=True, connect_args=connect_args,
                )
                self._session_factory = sessionmaker(
                    bind=self._engine, autoflush=False, autocommit=False,
                )
            return self._session_factory

    @property
    def engine(self):
        return self.session_factory.kw["bind"]

    def close(self):
        with self._lock:
            if self._engine is not None:
                self._engine.dispose()
                self._engine = None
                self._session_factory = None


_active_database: ContextVar[DatabaseResources | None] = ContextVar("walksafe_database", default=None)
_default_database = None
_default_lock = Lock()


def current_database():
    current = _active_database.get()
    if current is not None:
        return current
    global _default_database
    with _default_lock:
        if _default_database is None:
            _default_database = DatabaseResources(get_settings())
        return _default_database


@contextmanager
def use_database(resources):
    token = _active_database.set(resources)
    try:
        yield
    finally:
        _active_database.reset(token)


class _SessionFactory:
    """Compatibility entry point for CLI and existing services, scoped by the app."""
    def __call__(self, *args, **kwargs):
        return current_database().session_factory(*args, **kwargs)

    def begin(self):
        return current_database().session_factory.begin()

    @property
    def kw(self):
        return current_database().session_factory.kw


SessionLocal = _SessionFactory()


def __getattr__(name):
    if name == "engine":
        return current_database().engine
    raise AttributeError(name)


async def get_db() -> AsyncGenerator[Session, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        await run_in_threadpool(db.close)
