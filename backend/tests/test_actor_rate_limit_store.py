from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import hashlib

from sqlalchemy import text

from backend.app.database import SessionLocal
from backend.app.field_test_security import ACTOR_RATE_LIMITS
from backend.app.services.actor_rate_limit import PostgresActorRateLimiter


def _clear_rate_limit_events() -> None:
    with SessionLocal.begin() as db:
        db.execute(text("TRUNCATE TABLE actor_rate_limit_events"))


def test_postgres_rate_limit_is_shared_by_independent_instances() -> None:
    _clear_rate_limit_events()
    first = PostgresActorRateLimiter(ACTOR_RATE_LIMITS)
    second = PostgresActorRateLimiter(ACTOR_RATE_LIMITS)
    actor_id = "shared-limit@example.com"

    for index in range(ACTOR_RATE_LIMITS["export"]):
        limiter = first if index % 2 == 0 else second
        assert limiter.check(actor_id, "export") is None

    assert PostgresActorRateLimiter(ACTOR_RATE_LIMITS).check(actor_id, "export") is not None


def test_postgres_rate_limit_prunes_only_the_locked_actor_bucket() -> None:
    _clear_rate_limit_events()
    current_actor = "current@example.com"
    current_digest = hashlib.sha256(current_actor.encode("utf-8")).hexdigest()
    with SessionLocal.begin() as db:
        db.execute(
            text(
                "INSERT INTO actor_rate_limit_events "
                "(actor_digest, rate_group, observed_at) "
                "VALUES ('expired-other-actor', 'report', "
                "clock_timestamp() - interval '2 minutes'), "
                "(:current_digest, 'report', "
                "clock_timestamp() - interval '2 minutes')"
            ),
            {"current_digest": current_digest},
        )

    assert PostgresActorRateLimiter(ACTOR_RATE_LIMITS).check(current_actor, "report") is None

    with SessionLocal.begin() as db:
        remaining_other = db.execute(
            text(
                "SELECT count(*) FROM actor_rate_limit_events "
                "WHERE actor_digest = 'expired-other-actor'"
            )
        ).scalar_one()
        current_bucket = db.execute(
            text(
                "SELECT count(*) FROM actor_rate_limit_events "
                "WHERE actor_digest = :current_digest AND rate_group = 'report'"
            ),
            {"current_digest": current_digest},
        ).scalar_one()
    assert remaining_other == 1
    assert current_bucket == 1


def test_postgres_rate_limit_admission_is_atomic_across_workers() -> None:
    _clear_rate_limit_events()
    actor_id = "concurrent-limit@example.com"
    attempt_count = ACTOR_RATE_LIMITS["report"] + 8

    with ThreadPoolExecutor(max_workers=attempt_count) as executor:
        results = list(
            executor.map(
                lambda _index: PostgresActorRateLimiter(ACTOR_RATE_LIMITS).check(
                    actor_id,
                    "report",
                ),
                range(attempt_count),
            )
        )

    assert sum(result is None for result in results) == ACTOR_RATE_LIMITS["report"]
    assert sum(result is not None for result in results) == 8
