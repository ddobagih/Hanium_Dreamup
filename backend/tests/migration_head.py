"""Resolve the repository's current Alembic head.

Tests that guard "the database is migrated up to date" must pin that
relationship, not the revision string of the day.  A literal goes stale the
moment a migration is added, and the failure then points at the test instead
of at the schema it was meant to protect.
"""

from __future__ import annotations

from functools import cache
from pathlib import Path

from alembic.config import Config
from alembic.script import ScriptDirectory

_BACKEND_ROOT = Path(__file__).resolve().parents[1]


@cache
def current_migration_head() -> str:
    """Return the single Alembic head declared by the repository.

    Raises AssertionError when the migration graph has branched, because every
    caller asserts against one head and a silent pick would hide the branch.
    """
    config = Config(str(_BACKEND_ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(_BACKEND_ROOT / "alembic"))
    heads = ScriptDirectory.from_config(config).get_heads()
    assert len(heads) == 1, f"expected exactly one Alembic head, found {heads}"
    return heads[0]
