"""S20-8: duplicate-candidate locks are partitioned by space without missing neighbours."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
import math
import os
import random

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session

from backend.app.services import duplicates
from backend.app.services.duplicates import (
    DUPLICATE_RADIUS_M,
    duplicate_candidate_lock_cells,
    lock_duplicate_candidate_window,
)

SEOUL = (37.5665, 126.978)
BUSAN = (35.1796, 129.0756)
NOW = datetime(2026, 9, 26, 3, 0, 30, tzinfo=UTC)
_EARTH_M = 6_371_008.8


def _haversine_m(a: tuple[float, float], b: tuple[float, float]) -> float:
    lat1, lng1, lat2, lng2 = map(math.radians, (*a, *b))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 2 * _EARTH_M * math.asin(min(1.0, math.sqrt(h)))


def _offset(point: tuple[float, float], distance_m: float, bearing: float) -> tuple[float, float]:
    lat1, lng1 = map(math.radians, point)
    angular = distance_m / _EARTH_M
    lat2 = math.asin(
        math.sin(lat1) * math.cos(angular) + math.cos(lat1) * math.sin(angular) * math.cos(bearing)
    )
    lng2 = lng1 + math.atan2(
        math.sin(bearing) * math.sin(angular) * math.cos(lat1),
        math.cos(angular) - math.sin(lat1) * math.sin(lat2),
    )
    lng = (math.degrees(lng2) + 540.0) % 360.0 - 180.0
    return max(-90.0, min(90.0, math.degrees(lat2))), lng


def _shares_cell(a: tuple[float, float], b: tuple[float, float]) -> bool:
    return bool(set(duplicate_candidate_lock_cells(*a)) & set(duplicate_candidate_lock_cells(*b)))


EDGE_PAIRS = {
    "same_spot": (SEOUL, SEOUL),
    "cell_corner_7m": ((37.56649, 126.97799), (37.56654, 126.97804)),
    "east_west_9m": ((37.5665, 126.97795), (37.5665, 126.97805)),
    "equator_6m": ((-0.00003, 10.0), (0.00003, 10.0)),
    "antimeridian_6m": ((0.0, 179.99997), (0.0, -179.99997)),
    "antimeridian_exact": ((12.0, 180.0), (12.0, -180.0)),
    "north_pole_9m": ((89.99996, 0.0), (89.99996, 180.0)),
    "south_pole_9m": ((-89.99996, 45.0), (-89.99996, -135.0)),
    "polar_band_edge": ((84.99995, 20.0), (85.00003, 20.0)),
    "polar_band_edge_south": ((-84.99990, -70.0), (-84.99998, -70.0)),
    "high_latitude_84": ((84.5, 100.0), (84.5, 100.0009)),
}


@pytest.mark.parametrize("name", sorted(EDGE_PAIRS))
def test_neighbours_within_radius_share_a_lock_cell(name: str) -> None:
    a, b = EDGE_PAIRS[name]
    assert _haversine_m(a, b) <= DUPLICATE_RADIUS_M
    assert _shares_cell(a, b)


def test_random_neighbours_within_radius_always_share_a_lock_cell() -> None:
    rng = random.Random(20260926)
    checked = 0
    for _ in range(20000):
        # Bias anchors onto cell corners and the hard latitudes as well as uniform points.
        choice = rng.random()
        if choice < 0.4:
            step = 1 / duplicates.DUPLICATE_LOCK_CELLS_PER_DEGREE
            lat = round(rng.uniform(-84.0, 84.0) / step) * step + rng.uniform(-2e-5, 2e-5)
            lng = round(rng.uniform(-180.0, 180.0) / step) * step + rng.uniform(-2e-5, 2e-5)
        elif choice < 0.6:
            lat = rng.choice([1.0, -1.0]) * rng.uniform(84.99, 90.0)
            lng = rng.uniform(-180.0, 180.0)
        elif choice < 0.7:
            lat = rng.uniform(-60.0, 60.0)
            lng = rng.choice([180.0, -180.0]) + rng.uniform(-3e-4, 3e-4)
            lng = (lng + 540.0) % 360.0 - 180.0
        else:
            lat, lng = rng.uniform(-90.0, 90.0), rng.uniform(-180.0, 180.0)
        a = (max(-90.0, min(90.0, lat)), lng)
        b = _offset(a, rng.uniform(0.0, DUPLICATE_RADIUS_M * 1.02), rng.uniform(0.0, 2 * math.pi))
        if _haversine_m(a, b) > DUPLICATE_RADIUS_M * 1.02:
            continue
        checked += 1
        assert _shares_cell(a, b), (a, b, _haversine_m(a, b))
    assert checked > 19000


@pytest.mark.parametrize(
    "a, b",
    [
        (SEOUL, BUSAN),
        (SEOUL, (SEOUL[0] + 0.009, SEOUL[1])),  # about 1 km north
        (SEOUL, (SEOUL[0], SEOUL[1] + 0.0015)),  # about 130 m east
        ((0.0, 179.99), (0.0, -179.99)),  # about 2.2 km across the antimeridian
        ((89.99996, 0.0), (-89.99996, 0.0)),  # opposite poles
    ],
)
def test_distant_reports_use_disjoint_lock_cells(a, b) -> None:
    assert _haversine_m(a, b) > 100.0
    assert not _shares_cell(a, b)


def test_lock_cell_count_stays_small_in_service_area() -> None:
    rng = random.Random(7)
    counts = [
        len(duplicate_candidate_lock_cells(rng.uniform(33.0, 38.7), rng.uniform(124.5, 131.0)))
        for _ in range(5000)
    ]
    assert 1 <= min(counts) and max(counts) <= 4


class _RecordingSession:
    def __init__(self) -> None:
        self.keys: list[str] = []

    def execute(self, statement, parameters) -> None:
        assert str(statement) == "SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"
        self.keys.append(parameters["lock_key"])


def test_lock_keys_cover_minutes_times_cells_in_one_global_order() -> None:
    session = _RecordingSession()
    lock_duplicate_candidate_window(
        session, class_name="damaged_tactile_block", captured_at=NOW, is_fake=False,
        latitude=37.56649, longitude=126.97799,
    )
    cells = duplicate_candidate_lock_cells(37.56649, 126.97799)
    assert len(cells) == 4
    assert session.keys == sorted(session.keys)
    assert len(session.keys) == len(set(session.keys)) == 3 * len(cells)
    minutes = {key.split("\n")[3] for key in session.keys}
    assert minutes == {
        (NOW.replace(second=0) + timedelta(minutes=offset)).isoformat() for offset in (-1, 0, 1)
    }
    assert {key.split("\n")[4] for key in session.keys} == set(cells)
    assert all(key.startswith("report-duplicate\ndamaged_tactile_block\n0\n") for key in session.keys)


def test_report_without_location_takes_no_duplicate_lock() -> None:
    session = _RecordingSession()
    lock_duplicate_candidate_window(
        session, class_name="damaged_tactile_block", captured_at=NOW, is_fake=False,
        latitude=None, longitude=None,
    )
    assert session.keys == []


# ---------------------------------------------------------------------------
# Real PostgreSQL: lock conflicts and the PostGIS duplicate oracle
# ---------------------------------------------------------------------------
requires_database = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


@pytest.fixture
def engine():
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"])
    with engine.connect() as connection:
        assert "test" in connection.scalar(text("SELECT current_database()"))
    try:
        yield engine
    finally:
        engine.dispose()


def _second_writer_blocked(engine, a, b, *, b_seconds: int = 0, b_fake: bool = False) -> bool:
    with Session(engine) as first, Session(engine) as second:
        try:
            lock_duplicate_candidate_window(
                first, class_name="damaged_tactile_block", captured_at=NOW, is_fake=False,
                latitude=a[0], longitude=a[1],
            )
            second.execute(text("SET LOCAL lock_timeout = '200ms'"))
            try:
                lock_duplicate_candidate_window(
                    second, class_name="damaged_tactile_block",
                    captured_at=NOW + timedelta(seconds=b_seconds), is_fake=b_fake,
                    latitude=b[0], longitude=b[1],
                )
            except OperationalError as exc:
                assert type(exc.orig).__name__ == "LockNotAvailable"
                return True
            return False
        finally:
            second.rollback()
            first.rollback()


@requires_database
@pytest.mark.parametrize("name", ["same_spot", "cell_corner_7m", "antimeridian_6m", "north_pole_9m", "polar_band_edge"])
def test_postgres_neighbour_writers_still_serialize(engine, name: str) -> None:
    a, b = EDGE_PAIRS[name]
    assert _second_writer_blocked(engine, a, b)
    assert _second_writer_blocked(engine, a, b, b_seconds=59)


@requires_database
def test_postgres_distant_writers_do_not_wait_for_each_other(engine) -> None:
    assert not _second_writer_blocked(engine, SEOUL, BUSAN)
    assert not _second_writer_blocked(engine, SEOUL, (SEOUL[0] + 0.009, SEOUL[1]))
    # Existing non-spatial partitions are preserved.
    assert not _second_writer_blocked(engine, SEOUL, SEOUL, b_seconds=600)
    assert not _second_writer_blocked(engine, SEOUL, SEOUL, b_fake=True)


@requires_database
def test_postgis_duplicates_always_share_a_lock_cell(engine) -> None:
    """Every pair PostGIS accepts as a duplicate (ST_DWithin 10 m) shares a lock cell."""

    rng = random.Random(4242)
    step = 1 / duplicates.DUPLICATE_LOCK_CELLS_PER_DEGREE
    anchors = []
    for index in range(4000):
        if index % 4 == 0:
            lat = rng.choice([1.0, -1.0]) * rng.uniform(84.99, 89.9999)
            lng = rng.uniform(-180.0, 180.0)
        elif index % 4 == 1:
            lat, lng = rng.uniform(-60.0, 60.0), rng.choice([179.99995, -179.99995])
        else:
            lat = round(rng.uniform(33.0, 38.7) / step) * step + rng.uniform(-1e-5, 1e-5)
            lng = round(rng.uniform(124.5, 131.0) / step) * step + rng.uniform(-1e-5, 1e-5)
        anchors.append((lat, lng))
    pairs = [
        (a, _offset(a, rng.uniform(5.0, 14.0), rng.uniform(0.0, 2 * math.pi)))
        for a in anchors
    ]
    with engine.connect() as connection:
        within = connection.execute(
            text("""
                SELECT ST_DWithin(
                    ST_SetSRID(ST_MakePoint(p.alng, p.alat), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(p.blng, p.blat), 4326)::geography,
                    :radius_m
                )
                FROM unnest(
                    CAST(:alat AS double precision[]), CAST(:alng AS double precision[]),
                    CAST(:blat AS double precision[]), CAST(:blng AS double precision[])
                ) WITH ORDINALITY AS p(alat, alng, blat, blng, n)
                ORDER BY p.n
            """),
            {
                "radius_m": DUPLICATE_RADIUS_M,
                "alat": [a[0] for a, _ in pairs], "alng": [a[1] for a, _ in pairs],
                "blat": [b[0] for _, b in pairs], "blng": [b[1] for _, b in pairs],
            },
        ).scalars().all()
    duplicates_seen = 0
    for (a, b), is_duplicate in zip(pairs, within, strict=True):
        if is_duplicate:
            duplicates_seen += 1
            assert _shares_cell(a, b), (a, b)
    assert duplicates_seen > 1000
