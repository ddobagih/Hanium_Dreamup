from __future__ import annotations

from collections.abc import AsyncGenerator

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from backend.app.config import get_settings


settings = get_settings()
engine = create_engine(settings.database_url, pool_pre_ping=True)
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)


async def get_db() -> AsyncGenerator[Session, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
