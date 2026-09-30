"""One-statement report summary aggregation, without Report ORM hydration.

The database keeps its existing statement timeout. This module adds no row,
time, memory, concurrency, or grid-size policy. Float8 SUM/count centers may
differ in their last bits from the old unordered Python accumulation.
"""
from __future__ import annotations

from collections.abc import Callable
import math
import struct
from typing import Any

from sqlalchemy import BigInteger, Float, Integer, String, Text, bindparam, select, text
from sqlalchemy.dialects.postgresql import JSONB

from backend.app.models import Report


# Default Unicode lower maps the ASCII letters below to their lower-case
# partners; Kelvin sign is the additional inverse for "k". Default contextual
# sigma lowercasing cannot create any letter of "fake"/"demo". Do not replace
# this binary, case-sensitive pattern with database LOWER or ILIKE. The tests
# audit Unicode scalar inverses and expansions against the running Python.
_SOURCE_MODEL_FAKE_PATTERN = "[Ff][Aa][Kk\u212a][Ee]|[Dd][Ee][Mm][Oo]"
_FLOAT64_OVERFLOW_MIDPOINT = (1 << 1024) - (1 << 970)
# S20-38: practical public minimum (about 11 m of latitude). The HTTP query
# rejects smaller sizes with 422; the internal builder keeps the full finite
# (0, 1] domain so its overflow/rational fallback stays regression-tested.
# At this minimum and |coordinate| <= 180 the float cell bounds never collapse.
REPORT_SUMMARY_MIN_GRID_SIZE_DEGREES = 0.0001
REPORT_SUMMARY_MAX_GRID_SIZE_DEGREES = 1
_STATUS_ZERO = {"new": 0, "reviewed": 0, "resolved": 0}
_SOURCE_ZERO = {"fake": 0, "onnx": 0, "server": 0, "android": 0}
_SUMMARY_NOTE = (
    "Summary is computed from the current filtered report query, "
    "not from external map SDK data."
)


def _fraction_expression(bits: str) -> str:
    terms = [f"CAST((get_byte({bits}, 1) & 15) AS numeric) * 281474976710656"]
    for index in range(2, 8):
        terms.append(f"CAST(get_byte({bits}, {index}) AS numeric) * {1 << (8 * (7 - index))}")
    return " + ".join(terms)


# float8send is used instead of a decimal rendering of a double. Integer
# NUMERIC operations preserve large cells, including subnormal grid sizes.
# No database function, extension, schema object, or DDL is installed.
_AGGREGATE_SQL = """
WITH
s39_normalized AS MATERIALIZED (
    SELECT row_number() OVER () AS ordinal, status, source, latitude, longitude,
           CASE WHEN jsonb_typeof(payload) = 'object'
                THEN payload ELSE '{}'::jsonb END AS metadata
    FROM s39_filtered
),
s39_input AS MATERIALIZED (
    SELECT ordinal, status, source, latitude, longitude,
           COALESCE(
               source COLLATE "C" = 'fake'
               OR metadata -> 'fake_source' = 'true'::jsonb
               OR metadata -> 'demo' = 'true'::jsonb
               OR metadata -> 'is_demo' = 'true'::jsonb
               OR metadata -> 'is_fake' = 'true'::jsonb
               OR metadata -> 'data_origin' = '"demo"'::jsonb
               OR EXISTS (
                   SELECT 1
                   FROM jsonb_array_elements(
                       CASE WHEN jsonb_typeof(metadata -> 'review_flags') = 'array'
                            THEN metadata -> 'review_flags' ELSE '[]'::jsonb END
                   ) AS flag(value)
                   WHERE flag.value = '"fake_source"'::jsonb
               )
               OR CASE WHEN jsonb_typeof(metadata -> 'source_model') = 'string'
                       THEN (metadata ->> 'source_model') COLLATE "C" ~ :s39_fake_pattern
                       ELSE FALSE END,
               FALSE
           ) AS is_fake
    FROM s39_normalized
),
s39_global AS (
    SELECT count(*) AS total,
           count(*) FILTER (WHERE is_fake) AS fake,
           count(*) FILTER (
               WHERE latitude IS NOT NULL AND longitude IS NOT NULL
           ) AS located,
           min(latitude) FILTER (
               WHERE latitude IS NOT NULL AND longitude IS NOT NULL
           ) AS min_latitude,
           max(latitude) FILTER (
               WHERE latitude IS NOT NULL AND longitude IS NOT NULL
           ) AS max_latitude,
           min(longitude) FILTER (
               WHERE latitude IS NOT NULL AND longitude IS NOT NULL
           ) AS min_longitude,
           max(longitude) FILTER (
               WHERE latitude IS NOT NULL AND longitude IS NOT NULL
           ) AS max_longitude
    FROM s39_input
),
s39_status_counts AS (
    SELECT COALESCE(jsonb_object_agg(status, count), '{}'::jsonb) AS counts
    FROM (SELECT status, count(*) AS count FROM s39_input GROUP BY status COLLATE "C", status) AS grouped
),
s39_source_counts AS (
    SELECT COALESCE(jsonb_object_agg(source, count), '{}'::jsonb) AS counts
    FROM (SELECT source, count(*) AS count FROM s39_input GROUP BY source COLLATE "C", source) AS grouped
),
s39_coordinates AS (
    SELECT i.ordinal, coordinate.axis, coordinate.value,
           float8send(coordinate.value) AS bits
    FROM s39_input AS i
    CROSS JOIN LATERAL (
        VALUES (0, i.latitude), (1, i.longitude)
    ) AS coordinate(axis, value)
    WHERE i.latitude IS NOT NULL AND i.longitude IS NOT NULL
      AND :s39_top_limit > 0
),
s39_parts AS (
    SELECT ordinal, axis, value,
           CASE WHEN (get_byte(bits, 0) & 128) = 0 THEN 1 ELSE -1 END AS sign,
           ((get_byte(bits, 0) & 127) * 16 + (get_byte(bits, 1) >> 4)) AS exponent,
           (__COORDINATE_FRACTION__) AS fraction
    FROM s39_coordinates
),
s39_ratios AS (
    SELECT ordinal, axis, value,
           CASE WHEN exponent = 0 THEN sign * fraction
                WHEN exponent >= 1075
                THEN sign * (4503599627370496 + fraction) * power(2::numeric, exponent - 1075)
                ELSE sign * (4503599627370496 + fraction)
           END AS numerator,
           CASE WHEN exponent = 0 THEN power(2::numeric, 1074)
                WHEN exponent < 1075 THEN power(2::numeric, 1075 - exponent)
                ELSE 1::numeric
           END AS denominator
    FROM s39_parts
),
s39_quotients AS (
    SELECT *,
           CASE WHEN abs(numerator) * CAST(:s39_grid_denominator AS numeric)
                     < CAST(:s39_overflow_midpoint AS numeric)
                       * denominator * CAST(:s39_grid_numerator AS numeric)
                THEN value / CAST(:s39_grid AS double precision)
                ELSE NULL::double precision
           END AS quotient
    FROM s39_ratios
),
s39_fast_cells AS (
    SELECT *, floor(quotient) AS fast_cell
    FROM s39_quotients
),
s39_fast_bounds AS (
    SELECT *,
           fast_cell * CAST(:s39_grid AS double precision) AS lower_bound,
           (fast_cell + CAST(1 AS double precision))
               * CAST(:s39_grid AS double precision) AS upper_bound,
           float8send(fast_cell) AS fast_bits
    FROM s39_fast_cells
),
s39_fast_parts AS (
    SELECT *,
           CASE WHEN (get_byte(fast_bits, 0) & 128) = 0 THEN 1 ELSE -1 END AS fast_sign,
           ((get_byte(fast_bits, 0) & 127) * 16 + (get_byte(fast_bits, 1) >> 4)) AS fast_exponent,
           (__FAST_FRACTION__) AS fast_fraction,
           numerator * CAST(:s39_grid_denominator AS numeric) AS exact_numerator,
           denominator * CAST(:s39_grid_numerator AS numeric) AS exact_denominator
    FROM s39_fast_bounds
),
s39_cell_values AS (
    SELECT ordinal, axis,
           CASE WHEN quotient IS NOT NULL
                     AND lower_bound < upper_bound
                     AND NOT (value < lower_bound OR value >= upper_bound)
                THEN CASE WHEN fast_exponent = 0 THEN 0::numeric
                          WHEN fast_exponent >= 1075
                          THEN fast_sign * (4503599627370496 + fast_fraction)
                               * power(2::numeric, fast_exponent - 1075)
                          ELSE div(
                              fast_sign * (4503599627370496 + fast_fraction),
                              power(2::numeric, 1075 - fast_exponent)
                          )
                     END
                ELSE div(exact_numerator, exact_denominator)
                     - CASE WHEN exact_numerator < 0
                                 AND mod(exact_numerator, exact_denominator) <> 0
                            THEN 1 ELSE 0 END
           END AS cell
    FROM s39_fast_parts
),
s39_cell_pairs AS (
    SELECT ordinal,
           max(cell) FILTER (WHERE axis = 0) AS latitude_cell,
           max(cell) FILTER (WHERE axis = 1) AS longitude_cell
    FROM s39_cell_values
    GROUP BY ordinal
),
s39_located AS MATERIALIZED (
    SELECT i.*,
           CAST(trunc(c.latitude_cell, 0) AS text) || ':' ||
           CAST(trunc(c.longitude_cell, 0) AS text) AS key
    FROM s39_input AS i
    JOIN s39_cell_pairs AS c ON c.ordinal = i.ordinal
),
s39_cluster_counts AS (
    SELECT key, count(*) AS count,
           count(*) FILTER (WHERE is_fake) AS fake,
           sum(CAST(latitude AS double precision)) AS latitude_sum,
           sum(CAST(longitude AS double precision)) AS longitude_sum,
           min(ordinal) AS first_ordinal
    FROM s39_located
    GROUP BY key COLLATE "C", key
),
s39_top AS MATERIALIZED (
    SELECT *
    FROM s39_cluster_counts
    ORDER BY count DESC, key COLLATE "C" ASC
    LIMIT :s39_top_limit
),
s39_top_status AS (
    SELECT key, jsonb_object_agg(status, count) AS counts
    FROM (
        SELECT l.key, l.status, count(*) AS count
        FROM s39_located AS l
        JOIN s39_top AS t ON t.key COLLATE "C" = l.key COLLATE "C"
        GROUP BY l.key COLLATE "C", l.key, l.status COLLATE "C", l.status
    ) AS grouped
    GROUP BY key COLLATE "C", key
),
s39_top_source AS (
    SELECT key, jsonb_object_agg(source, count) AS counts
    FROM (
        SELECT l.key, l.source, count(*) AS count
        FROM s39_located AS l
        JOIN s39_top AS t ON t.key COLLATE "C" = l.key COLLATE "C"
        GROUP BY l.key COLLATE "C", l.key, l.source COLLATE "C", l.source
    ) AS grouped
    GROUP BY key COLLATE "C", key
),
s39_selected AS (
    SELECT t.key, t.count, t.fake,
           encode(float8send(
               t.latitude_sum / CAST(t.count AS double precision)
           ), 'hex') AS center_latitude_bits,
           encode(float8send(
               t.longitude_sum / CAST(t.count AS double precision)
           ), 'hex') AS center_longitude_bits,
           encode(float8send(r.latitude), 'hex') AS representative_latitude_bits,
           encode(float8send(r.longitude), 'hex') AS representative_longitude_bits,
           sc.counts AS status_counts, so.counts AS source_counts
    FROM s39_top AS t
    JOIN s39_located AS r ON r.ordinal = t.first_ordinal
    JOIN s39_top_status AS sc ON sc.key COLLATE "C" = t.key COLLATE "C"
    JOIN s39_top_source AS so ON so.key COLLATE "C" = t.key COLLATE "C"
)
SELECT g.total, g.fake, g.located,
       encode(float8send(g.min_latitude), 'hex') AS min_latitude_bits,
       encode(float8send(g.max_latitude), 'hex') AS max_latitude_bits,
       encode(float8send(g.min_longitude), 'hex') AS min_longitude_bits,
       encode(float8send(g.max_longitude), 'hex') AS max_longitude_bits,
       (SELECT counts FROM s39_status_counts) AS status_counts,
       (SELECT counts FROM s39_source_counts) AS source_counts,
       COALESCE((
           SELECT jsonb_agg(
               jsonb_build_object(
                   'key', key, 'count', count, 'fake', fake,
                   'center_latitude_bits', center_latitude_bits,
                   'center_longitude_bits', center_longitude_bits,
                   'representative_latitude_bits', representative_latitude_bits,
                   'representative_longitude_bits', representative_longitude_bits,
                   'status_counts', status_counts, 'source_counts', source_counts
               )
               ORDER BY count DESC, key COLLATE "C" ASC
           )
           FROM s39_selected
       ), '[]'::jsonb) AS top_clusters
FROM s39_global AS g
""".replace(
    "__COORDINATE_FRACTION__", _fraction_expression("bits"),
).replace(
    "__FAST_FRACTION__", _fraction_expression("fast_bits"),
)


def build_report_summary_statement(
    filtered_statement: Any,
    *,
    grid_size_degrees: float = 0.001,
    top_limit: int = 5,
):
    """Reuse the existing WHERE/binds; return one aggregate mapping."""
    if not math.isfinite(grid_size_degrees) or not 0 < grid_size_degrees <= 1:
        raise ValueError("grid_size_degrees must be finite, positive, and at most one")
    if not isinstance(top_limit, int) or not 0 <= top_limit <= 50:
        raise ValueError("top_limit must be between zero and fifty")
    grid_numerator, grid_denominator = grid_size_degrees.as_integer_ratio()
    filtered = filtered_statement.with_only_columns(
        Report.status.label("status"),
        Report.source.label("source"),
        Report.latitude.label("latitude"),
        Report.longitude.label("longitude"),
        Report.payload.label("payload"),
        maintain_column_froms=True,
    ).cte("s39_filtered")
    aggregate = text(_AGGREGATE_SQL).bindparams(
        bindparam("s39_grid", grid_size_degrees, type_=Float()),
        bindparam("s39_grid_numerator", str(grid_numerator), type_=String()),
        bindparam("s39_grid_denominator", str(grid_denominator), type_=String()),
        bindparam("s39_overflow_midpoint", str(_FLOAT64_OVERFLOW_MIDPOINT), type_=String()),
        bindparam("s39_fake_pattern", _SOURCE_MODEL_FAKE_PATTERN, type_=String()),
        bindparam("s39_top_limit", top_limit, type_=Integer()),
    ).columns(
        total=BigInteger(),
        fake=BigInteger(),
        located=BigInteger(),
        min_latitude_bits=Text(),
        max_latitude_bits=Text(),
        min_longitude_bits=Text(),
        max_longitude_bits=Text(),
        status_counts=JSONB(),
        source_counts=JSONB(),
        top_clusters=JSONB(),
    ).subquery("s39_aggregate")
    return select(aggregate).add_cte(filtered)


def _float64_from_bits(value: str) -> float:
    return struct.unpack(">d", bytes.fromhex(value))[0]


def aggregate_report_summary(
    db: Any,
    filtered_statement: Any,
    *,
    grid_size_degrees: float = 0.001,
    top_limit: int = 5,
    grid_cell: Callable[[float, float], tuple[int, float, float]],
) -> dict[str, object]:
    """Decode bounded aggregate results, not ORM rows or all grid cells.

    Unlike the old summary, this intentionally does not construct/validate a
    full ReportResponse for every stored row. Fake classification is done by
    the local review_flags conventions encoded in the SQL above.
    """
    row = db.execute(build_report_summary_statement(
        filtered_statement,
        grid_size_degrees=grid_size_degrees,
        top_limit=top_limit,
    )).mappings().one()
    total, fake, located = row["total"], row["fake"], row["located"]
    bounds = None
    if located:
        bounds = {
            "min_latitude": _float64_from_bits(row["min_latitude_bits"]),
            "max_latitude": _float64_from_bits(row["max_latitude_bits"]),
            "min_longitude": _float64_from_bits(row["min_longitude_bits"]),
            "max_longitude": _float64_from_bits(row["max_longitude_bits"]),
        }
    clusters = []
    for cluster in row["top_clusters"]:
        representative_latitude = _float64_from_bits(cluster["representative_latitude_bits"])
        representative_longitude = _float64_from_bits(cluster["representative_longitude_bits"])
        _, min_latitude, max_latitude = grid_cell(representative_latitude, grid_size_degrees)
        _, min_longitude, max_longitude = grid_cell(representative_longitude, grid_size_degrees)
        clusters.append({
            "key": cluster["key"],
            "count": cluster["count"],
            "fake": cluster["fake"],
            "non_fake": cluster["count"] - cluster["fake"],
            "center_latitude": _float64_from_bits(cluster["center_latitude_bits"]),
            "center_longitude": _float64_from_bits(cluster["center_longitude_bits"]),
            "bounds": {
                "min_latitude": min_latitude,
                "max_latitude": max_latitude,
                "min_longitude": min_longitude,
                "max_longitude": max_longitude,
            },
            "status_counts": {**_STATUS_ZERO, **cluster["status_counts"]},
            "source_counts": {**_SOURCE_ZERO, **cluster["source_counts"]},
        })
    return {
        "total": total,
        "fake": fake,
        "non_fake": total - fake,
        "located": located,
        "missing_location": total - located,
        "bounds": bounds,
        "status_counts": {**_STATUS_ZERO, **row["status_counts"]},
        "source_counts": {**_SOURCE_ZERO, **row["source_counts"]},
        "grid_size_degrees": grid_size_degrees,
        "top_clusters": clusters,
        "note": _SUMMARY_NOTE,
    }
