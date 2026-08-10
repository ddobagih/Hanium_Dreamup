from __future__ import annotations

from collections.abc import AsyncGenerator
from urllib.parse import urlsplit

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from backend.app.config import get_settings


settings = get_settings()
database_scheme = urlsplit(settings.database_url).scheme.split("+", 1)[0].lower()
connect_args = (
    {
        "connect_timeout": settings.database_connect_timeout_seconds,
        "options": f"-c statement_timeout={settings.database_statement_timeout_ms}",
    }
    if database_scheme in {"postgres", "postgresql"}
    else {}
)
engine = create_engine(
    settings.database_url,
    pool_pre_ping=True,
    pool_timeout=settings.database_connect_timeout_seconds,
    connect_args=connect_args,
)
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)


async def get_db() -> AsyncGenerator[Session, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
