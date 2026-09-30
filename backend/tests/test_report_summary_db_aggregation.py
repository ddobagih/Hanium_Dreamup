"""Actual SQL builder, decoder and route wiring; no PostgreSQL execution.

SQL structure/binds and Python reference vectors are not database numerical
execution proof. Endpoint cases use explicit aggregate-result effect doubles.
"""
from __future__ import annotations

import itertools
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
from types import SimpleNamespace

from sqlalchemy import select, text
from sqlalchemy.dialects import postgresql

from backend.app.models import Report
from backend.app.services.report_serialization import review_flags
from backend.app.services.report_summary_aggregation import (
    _SOURCE_MODEL_FAKE_PATTERN,
    build_report_summary_statement,
)


ROOT = Path(__file__).resolve().parents[2]


def _compiled(statement):
    compiled = statement.compile(dialect=postgresql.dialect())
    return str(compiled), compiled.params


def _fake_reference(payload, *, source="server"):
    report = SimpleNamespace(
        payload=payload, source=source, latitude=None, longitude=None,
        accuracy_m=None, confidence=1.0, heading=0.0,
    )
    return "fake_source" in review_flags(report)


def test_builder_preserves_filter_relation_and_narrow_projection():
    filtered = (
        select(Report)
        .where(Report.status == "reviewed")
        .where(Report.class_name == "damaged_tactile_block")
        .where(Report.payload.contains({"model_key": "owned-model"}))
        .where(text(
            "location IS NOT NULL AND ST_DWithin(location::geography, "
            "ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius_m)"
        ))
        .params(lat=37.0, lng=127.0, radius_m=20.0)
    )
    sql, parameters = _compiled(build_report_summary_statement(filtered))
    assert "s39_filtered AS" in sql
    assert "FROM reports" in sql
    assert "reports.status =" in sql
    assert "reports.class_name =" in sql
    assert "ST_DWithin(location::geography" in sql
    assert parameters["lat"] == 37.0
    assert parameters["lng"] == 127.0
    assert parameters["radius_m"] == 20.0
    assert "reviewed" in parameters.values()
    assert "damaged_tactile_block" in parameters.values()
    assert {"model_key": "owned-model"} in parameters.values()
    assert "reports.image_path" not in sql
    assert "reports.bbox_x" not in sql
    assert "reports.id" not in sql
    assert "10001" not in sql and "10000" not in sql
    assert ";" not in sql


def test_builder_keeps_exact_strict_fake_predicates():
    sql, parameters = _compiled(build_report_summary_statement(select(Report)))
    assert "jsonb_typeof(payload) = 'object'" in sql
    for name in ("fake_source", "demo", "is_demo", "is_fake"):
        assert f"metadata -> '{name}' = 'true'::jsonb" in sql
    assert """metadata -> 'data_origin' = '"demo"'::jsonb""" in sql
    assert "jsonb_typeof(metadata -> 'review_flags') = 'array'" in sql
    assert """flag.value = '"fake_source"'::jsonb""" in sql
    assert "jsonb_typeof(metadata -> 'source_model') = 'string'" in sql
    assert 'source COLLATE "C" = \'fake\'' in sql
    assert 'COLLATE "C" ~' in sql
    assert "lower(" not in sql.lower() and "ilike" not in sql.lower()
    assert parameters["s39_fake_pattern"] == _SOURCE_MODEL_FAKE_PATTERN
    assert "metadata -> 'metadata'" not in sql


def test_builder_keeps_grid_fast_path_and_integer_fallback():
    sql, parameters = _compiled(build_report_summary_statement(select(Report)))
    assert "float8send(coordinate.value)" in sql
    assert "get_byte(bits, 0)" in sql
    assert "get_byte(fast_bits, 0)" in sql
    assert "floor(quotient)" in sql
    assert "lower_bound < upper_bound" in sql
    assert "value < lower_bound OR value >= upper_bound" in sql
    assert "div(exact_numerator, exact_denominator)" in sql
    assert "mod(exact_numerator, exact_denominator) <> 0" in sql
    assert "exact_numerator < 0" in sql
    assert "CAST(trunc(c.latitude_cell, 0) AS text)" in sql
    assert 'ORDER BY count DESC, key COLLATE "C" ASC' in sql
    assert "sum(CAST(latitude AS double precision))" in sql
    assert "t.latitude_sum / CAST(t.count AS double precision)" in sql
    assert "avg(" not in sql.lower()
    assert "min(ordinal) AS first_ordinal" in sql
    assert "r.ordinal = t.first_ordinal" in sql
    assert "representative_latitude_bits" in sql
    assert int(parameters["s39_overflow_midpoint"]) == (1 << 1024) - (1 << 970)


def test_builder_binds_exact_grid_ratios_and_existing_top_limits():
    grids = (
        1.0, 0.001, math.nextafter(0.001, 0.0),
        math.nextafter(0.001, math.inf), math.ldexp(1.0, -60),
        math.ldexp(1.0, -1022), math.ldexp(1.0, -1074),
    )
    for grid in grids:
        for top_limit in (0, 5, 50):
            sql, parameters = _compiled(build_report_summary_statement(
                select(Report), grid_size_degrees=grid, top_limit=top_limit,
            ))
            numerator, denominator = grid.as_integer_ratio()
            assert int(parameters["s39_grid_numerator"]) == numerator
            assert int(parameters["s39_grid_denominator"]) == denominator
            assert parameters["s39_grid"] == grid
            assert parameters["s39_top_limit"] == top_limit
            assert "LIMIT" in sql
    # These loop rows are bind/structure checks, not PostgreSQL numeric cases.


def test_unicode_inverse_matches_python_lower_and_live_review_flags():
    letters = set("fakedmo")
    inverses = {letter: set() for letter in letters}
    for ordinal in range(0x110000):
        if 0xD800 <= ordinal <= 0xDFFF:
            continue  # Surrogates are not PostgreSQL UTF-8 scalar strings.
        character = chr(ordinal)
        lowered = character.lower()
        assert lowered
        if lowered in inverses:
            inverses[lowered].add(character)
        if len(lowered) != 1:
            # No default full-lower expansion can supply a target ASCII letter.
            assert not letters.intersection(lowered)
    expected = {letter: {letter, letter.upper()} for letter in letters}
    expected["k"].add("\u212a")
    assert inverses == expected
    pattern = re.compile(_SOURCE_MODEL_FAKE_PATTERN)
    for word in ("fake", "demo"):
        for spelling in itertools.product(*(inverses[letter] for letter in word)):
            model = "prefix-" + "".join(spelling) + "-suffix"
            assert pattern.search(model) is not None
            assert _fake_reference({"source_model": model})
    for model in (
        "", "genuine", "f a k e", "fa\u0301ke", "de\u0301mo",
        "\u03a6\u0391\u039a\u0395", "\uff26\uff21\uff2b\uff25",
        "\u0130FAKE", "\u039f\u03a3-demo", "FAKE-\u0391\u03a3\u0391",
        "fa\u03a3ke", "de\u0130mo", "fa\u212ae",
    ):
        expected_fake = "fake" in model.lower() or "demo" in model.lower()
        assert bool(pattern.search(model)) == expected_fake
        assert _fake_reference({"source_model": model}) == expected_fake


def test_historical_json_reference_vectors_match_strict_summary_contract():
    vectors = (
        ({}, False),
        (None, False),
        (["fake_source"], False),
        ("fake_source", False),
        ({"fake_source": True}, True),
        ({"fake_source": 1}, False),
        ({"fake_source": "true"}, False),
        ({"fake_source": False}, False),
        ({"fake_source": None}, False),
        ({"demo": True}, True),
        ({"is_demo": True}, True),
        ({"is_fake": True}, True),
        ({"demo": "true", "is_demo": 1, "is_fake": "true"}, False),
        ({"data_origin": "demo"}, True),
        ({"data_origin": "Demo"}, False),
        ({"review_flags": ["fake_source", "unrelated"]}, True),
        ({"review_flags": "fake_source"}, False),
        ({"review_flags": [["fake_source"]]}, False),
        ({"metadata": {"fake_source": True, "review_flags": ["fake_source"]}}, False),
        ({"source_model": ["fake"]}, False),
        ({"source_model": "owned-fa\u212ae-model"}, True),
    )
    for payload, expected in vectors:
        assert _fake_reference(payload) == expected
    assert _fake_reference({}, source="fake") is True
    # The real serializer is the independent Python oracle. SQL execution is
    # deliberately not claimed by these reference vectors and builder checks.


_SUMMARY_CHILD = r"""
from datetime import UTC, datetime
from fractions import Fraction
import inspect
import json
import math
import socket
import struct
import sys
from types import SimpleNamespace

case = sys.argv[1]
phase = "guards"
attempts = {"database": 0, "network": 0}

def deny_database(*args, **kwargs):
    attempts["database"] += 1
    raise AssertionError("summary test forbids database connections")

def deny_network(*args, **kwargs):
    attempts["network"] += 1
    raise AssertionError("summary test forbids network connections")

socket.socket.connect = deny_network
socket.socket.connect_ex = deny_network
socket.create_connection = deny_network
socket.getaddrinfo = deny_network
from sqlalchemy.engine import Engine
Engine.connect = deny_database
Engine.raw_connection = deny_database

try:
    phase = "imports"
    from backend.app import config
    config.get_settings = lambda: SimpleNamespace(
        database_url="postgresql+psycopg://walksafe_test:disabled@127.0.0.1:1/walksafe_pytest_unconfigured",
        database_connect_timeout_seconds=1,
        database_statement_timeout_ms=1000,
    )
    from fastapi import HTTPException
    from fastapi.params import Param
    from sqlalchemy.dialects import postgresql
    from backend.app.api import reports
    from backend.app.services.report_summary_aggregation import aggregate_report_summary

    phase = "fixture"
    settings = SimpleNamespace(
        admin_security_enabled=False, field_test_security_enabled=False,
    )
    router = reports.create_router(settings, object())
    endpoint = next(
        route.endpoint for route in router.routes
        if getattr(route.endpoint, "__name__", "") == "summarize_reports"
    )
    settings.field_test_security_enabled = True
    arguments = {
        name: parameter.default.default if isinstance(parameter.default, Param) else parameter.default
        for name, parameter in inspect.signature(endpoint).parameters.items()
    }
    events = []
    identities = []
    audits = []
    statements = []
    grid_calls = []
    actual_grid = reports._report_grid_cell
    audit_error = HTTPException(status_code=503, detail={"code": "owned_read_audit_failure"})

    def bits(value):
        return struct.pack(">d", float(value)).hex()

    def wire_cluster(key, latitude, longitude, count=100, fake=0, center=None):
        center_latitude, center_longitude = center or (latitude, longitude)
        return {
            "key": key, "count": count, "fake": fake,
            "representative_latitude_bits": bits(latitude),
            "representative_longitude_bits": bits(longitude),
            "center_latitude_bits": bits(center_latitude),
            "center_longitude_bits": bits(center_longitude),
            "status_counts": {"new": count},
            "source_counts": {"server": count},
        }

    # Deliberately supplied DB aggregate results, NOT an SQL evaluator.
    row = {
        "total": 45000, "fake": 700, "located": 40000,
        "min_latitude_bits": bits(37.0), "max_latitude_bits": bits(38.0),
        "min_longitude_bits": bits(127.0), "max_longitude_bits": bits(128.0),
        "status_counts": {"new": 40000, "reviewed": 3000, "resolved": 2000},
        "source_counts": {"fake": 700, "onnx": 10000, "server": 34000, "android": 300},
        "top_clusters": [
            wire_cluster("37125:127125", 37.125, 127.125, fake=3, center=(37.12525, 127.12575)),
        ],
    }

    def small_result(clusters):
        row["total"] = sum(cluster["count"] for cluster in clusters)
        row["fake"] = sum(cluster["fake"] for cluster in clusters)
        row["located"] = row["total"]
        row["status_counts"] = {"new": row["total"]}
        row["source_counts"] = {"server": row["total"]}
        row["top_clusters"] = clusters

    if case == "empty":
        small_result([])
        for name in ("min_latitude_bits", "max_latitude_bits", "min_longitude_bits", "max_longitude_bits"):
            row[name] = None
    elif case == "top-zero":
        arguments["top_limit"] = 0
        row["top_clusters"] = []
    elif case == "tie":
        arguments["grid_size_degrees"] = 1.0
        small_result([
            wire_cluster("-1:0", -1.0, 0.0, count=5, center=(-0.75, 0.125)),
            wire_cluster("10:0", 10.0, 0.0, count=5, center=(10.25, 0.25)),
            wire_cluster("2:0", 2.0, 0.0, count=5, center=(2.25, 0.25)),
        ])
    elif case == "top-fifty":
        arguments.update(grid_size_degrees=1.0, top_limit=50)
        row["top_clusters"] = [
            wire_cluster(f"{index}:0", float(index), 0.0)
            for index in sorted(range(50), key=lambda value: f"{value}:0")
        ]
    elif case == "tiny-grid":
        grid = math.ldexp(1.0, -1074)
        arguments["grid_size_degrees"] = grid
        key = f"{math.floor(Fraction(37.0) / Fraction(grid))}:{math.floor(Fraction(127.0) / Fraction(grid))}"
        small_result([wire_cluster(key, 37.0, 127.0, count=1)])
        row.update(
            min_latitude_bits=bits(37.0), max_latitude_bits=bits(37.0),
            min_longitude_bits=bits(127.0), max_longitude_bits=bits(127.0),
        )
    elif case == "boundary":
        small_result([
            wire_cluster("-2:0", math.nextafter(-0.001, -math.inf), 0.0, count=4),
            wire_cluster("-1:0", -0.001, 0.0, count=3),
            wire_cluster("0:0", math.nextafter(0.001, 0.0), 0.0, count=2),
            wire_cluster("1:0", math.nextafter(0.001, math.inf), 0.0, count=1),
        ])
    elif case == "filters":
        arguments.update(
            status="reviewed", class_name="damaged_tactile_block",
            source="android", demo_filter="exclude_fake",
            model_key="owned-model", trigger="auto", auto_reported=True,
            performance_excluded=True,
            created_from=datetime(2026, 1, 1, tzinfo=UTC),
            created_to=datetime(2026, 1, 2, tzinfo=UTC),
            lat=37.0, lng=127.0, radius_m=20.0,
        )

    class MappingResult:
        def mappings(self):
            events.append("mappings")
            return self

        def one(self):
            events.append("one")
            return row

        def scalars(self):
            raise AssertionError("aggregate result must not hydrate ORM scalars")

    class Database:
        def execute(self, statement):
            events.append("execute")
            statements.append(statement)
            return MappingResult()

        def scalars(self, *args, **kwargs):
            raise AssertionError("summary must not select ORM Report objects")

    database = Database()

    def identity(*args, **kwargs):
        events.append("identity")
        identities.append((args, kwargs))
        return "owned-actor", "owned-purpose"

    def audit(*args, **kwargs):
        events.append("audit")
        audits.append((args, kwargs))
        if case == "audit-failure":
            raise audit_error

    def grid_cell(coordinate, grid):
        grid_calls.append((coordinate, grid))
        return actual_grid(coordinate, grid)

    def forbidden(*args, **kwargs):
        raise AssertionError("summary must not call legacy ORM/DTO summary helpers")

    reports.resolve_read_audit_identity = identity
    reports.persist_report_read_audit = audit
    reports._report_grid_cell = grid_cell
    reports._reports_summary = forbidden
    reports._bounded_summary_reports = forbidden
    reports.report_to_response = forbidden
    arguments.update(
        db=database, x_walksafe_actor_id="owned-actor",
        x_walksafe_read_purpose="owned-purpose",
    )

    phase = "operation"
    result = None
    caught = None
    try:
        if case == "aggregate":
            filtered = reports._filtered_reports_statement(
                status=None, class_name=None, source=None, demo_filter="all",
                model_key=None, trigger=None, auto_reported=None,
                performance_excluded=None, created_from=None, created_to=None,
                lat=None, lng=None, radius_m=None,
            )
            result = aggregate_report_summary(
                database, filtered, grid_size_degrees=0.001,
                top_limit=5, grid_cell=grid_cell,
            )
        else:
            result = endpoint(**arguments)
    except BaseException as error:
        caught = error

    phase = "assertions"
    assert len(statements) == 1
    assert events.count("mappings") == 1 and events.count("one") == 1
    compiled = statements[0].compile(dialect=postgresql.dialect())
    sql, parameters = str(compiled), compiled.params
    assert "s39_filtered AS" in sql and "s39_global AS" in sql
    assert 'ORDER BY count DESC, key COLLATE "C" ASC' in sql
    assert "10001" not in sql and "10000" not in sql
    assert "reports.image_path" not in sql and "reports.bbox_x" not in sql
    assert parameters["s39_top_limit"] == arguments["top_limit"]
    assert len(grid_calls) == 2 * len(row["top_clusters"])
    assert len(row["top_clusters"]) <= arguments["top_limit"]
    if case == "aggregate":
        assert identities == [] and audits == []
    else:
        assert identities == [
            (("owned-actor", "owned-purpose"), {"required": True}),
        ]
        assert len(audits) == 1
        assert audits[0][0] == (database,)
        assert audits[0][1] == {
            "actor_id": "owned-actor", "purpose": "owned-purpose",
            "resource_type": "report_list", "resource_id": "reports/summary",
            "details": {
                "result_count": row["total"], "located_count": row["located"],
                "exact_location_filter_applied": case == "filters",
                "grid_size_degrees": arguments["grid_size_degrees"],
                "top_limit": arguments["top_limit"],
            },
        }
        assert events.index("identity") < events.index("execute") < events.index("audit")
    if case == "audit-failure":
        assert caught is audit_error
        assert result is None
    else:
        assert caught is None, repr(caught)
        assert result["total"] == row["total"]
        assert result["fake"] == row["fake"]
        assert result["non_fake"] == row["total"] - row["fake"]
        assert result["located"] == row["located"]
        assert result["missing_location"] == row["total"] - row["located"]
        assert result["note"] == (
            "Summary is computed from the current filtered report query, "
            "not from external map SDK data."
        )
        assert len(result["top_clusters"]) == len(row["top_clusters"])
        if case in {"aggregate", "large", "filters", "top-zero", "top-fifty"}:
            assert result["total"] == 45000
            assert result["located"] == 40000
            assert result["missing_location"] == 5000
            assert result["status_counts"] == {"new": 40000, "reviewed": 3000, "resolved": 2000}
        if case == "empty":
            assert result["bounds"] is None and result["top_clusters"] == []
            assert result["status_counts"] == {"new": 0, "reviewed": 0, "resolved": 0}
            assert result["source_counts"] == {"fake": 0, "onnx": 0, "server": 0, "android": 0}
        elif case == "top-zero":
            assert result["top_clusters"] == [] and grid_calls == []
            assert result["bounds"] == {
                "min_latitude": 37.0, "max_latitude": 38.0,
                "min_longitude": 127.0, "max_longitude": 128.0,
            }
        elif case == "tie":
            assert [cluster["key"] for cluster in result["top_clusters"]] == ["-1:0", "10:0", "2:0"]
            assert [cluster["center_latitude"] for cluster in result["top_clusters"]] == [-0.75, 10.25, 2.25]
        elif case == "top-fifty":
            assert len(result["top_clusters"]) == 50 and len(grid_calls) == 100
            assert [cluster["key"] for cluster in result["top_clusters"]] == sorted(f"{index}:0" for index in range(50))
        elif case == "tiny-grid":
            cluster = result["top_clusters"][0]
            assert cluster["key"] == key and len(key) > 100
            assert cluster["bounds"] == {
                "min_latitude": 37.0, "max_latitude": 37.0,
                "min_longitude": 127.0, "max_longitude": 127.0,
            }
            assert int(parameters["s39_grid_denominator"]) == 1 << 1074
        elif case == "boundary":
            assert [cluster["key"] for cluster in result["top_clusters"]] == ["-2:0", "-1:0", "0:0", "1:0"]
            assert [
                (cluster["bounds"]["min_latitude"], cluster["bounds"]["max_latitude"])
                for cluster in result["top_clusters"]
            ] == [(-0.002, -0.001), (-0.001, 0.0), (0.0, 0.001), (0.001, 0.002)]
        else:
            cluster = result["top_clusters"][0]
            assert cluster["key"] == "37125:127125"
            assert cluster["count"] == 100 and cluster["fake"] == 3 and cluster["non_fake"] == 97
            assert cluster["center_latitude"] == 37.12525
            assert cluster["center_longitude"] == 127.12575
            assert cluster["status_counts"] == {"new": 100, "reviewed": 0, "resolved": 0}
            assert cluster["source_counts"] == {"fake": 0, "onnx": 0, "server": 100, "android": 0}
            assert grid_calls == [(37.125, 0.001), (127.125, 0.001)]
    if case == "filters":
        assert "ST_DWithin(location::geography" in sql
        assert parameters["lat"] == 37.0 and parameters["lng"] == 127.0
        assert parameters["radius_m"] == 20.0
        for value in ("reviewed", "damaged_tactile_block", "android"):
            assert value in parameters.values()
        for value in (
            {"model_key": "owned-model"}, {"trigger": "auto"},
            {"auto_reported": True}, {"performance_excluded": True},
        ):
            assert value in parameters.values()
        # The filter keeps its existing fake-string/nested conventions.
        assert {"fake_source": "true"} in parameters.values()
        assert {"metadata": {"fake_source": True}} in parameters.values()
        assert datetime(2026, 1, 1, tzinfo=UTC) in parameters.values()
        assert datetime(2026, 1, 2, tzinfo=UTC) in parameters.values()
    assert attempts == {"database": 0, "network": 0}
    print(json.dumps({"ok": True, "case": case, "attempts": attempts}))
except BaseException as error:
    print(json.dumps({
        "ok": False, "case": case, "phase": phase,
        "error_type": type(error).__name__, "error": str(error),
        "attempts": attempts,
    }))
    raise SystemExit(1)
"""


def _run_summary_case(case: str) -> None:
    completed = subprocess.run(
        [sys.executable, "-B", "-c", _SUMMARY_CHILD, case],
        cwd=ROOT,
        env={
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "PYTHONPATH": str(ROOT),
            "PYTHONDONTWRITEBYTECODE": "1",
        },
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        timeout=20,
    )
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    assert json.loads(completed.stdout) == {
        "ok": True, "case": case, "attempts": {"database": 0, "network": 0},
    }


def test_aggregate_requests_one_mapping_and_only_selected_grid_bounds():
    _run_summary_case("aggregate")


def test_endpoint_reports_total_above_10000_without_orm_hydration():
    _run_summary_case("large")


def test_endpoint_empty_result_and_zero_seed_maps():
    _run_summary_case("empty")


def test_endpoint_top_zero_keeps_all_totals():
    _run_summary_case("top-zero")


def test_endpoint_retains_filters_identity_and_audit():
    _run_summary_case("filters")


def test_endpoint_withholds_result_on_audit_failure():
    _run_summary_case("audit-failure")


def test_endpoint_preserves_string_key_order_and_database_center_values():
    _run_summary_case("tie")


def test_endpoint_reconstructs_tiny_grid_bounds_with_actual_helper():
    _run_summary_case("tiny-grid")


def test_endpoint_bounds_top_fifty_to_selected_representatives():
    _run_summary_case("top-fifty")


def test_endpoint_preserves_negative_and_adjacent_float_boundary_bounds():
    _run_summary_case("boundary")
