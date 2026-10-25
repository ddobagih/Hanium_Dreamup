"""Shared, I/O-free contracts and resource guards for TMAP normalization."""

from __future__ import annotations

from time import monotonic
from typing import Any


class TmapPoiSearchError(RuntimeError):
    def __init__(
        self,
        *,
        code: str,
        message: str,
        http_status: int = 502,
        provider_status: int | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.http_status = http_status
        self.provider_status = provider_status
        self.provider_result_code = None


class TmapPedestrianRouteError(RuntimeError):
    def __init__(
        self,
        *,
        code: str,
        message: str,
        http_status: int = 502,
        provider_status: int | None = None,
        provider_result_code: int | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.http_status = http_status
        self.provider_status = provider_status
        self.provider_result_code = provider_result_code


TMAP_MAX_STRING_LENGTH = 2048


def _ensure_tmap_deadline(deadline_at: float | None) -> None:
    if deadline_at is not None and monotonic() >= deadline_at:
        raise TimeoutError("TMAP request exceeded its wall-clock deadline")


def _json_has_oversized_string(
    payload: Any,
    *,
    deadline_at: float | None = None,
) -> bool:
    pending = [payload]
    inspected = 0
    while pending:
        value = pending.pop()
        inspected += 1
        if inspected % 256 == 0:
            _ensure_tmap_deadline(deadline_at)
        if isinstance(value, str):
            if len(value) > TMAP_MAX_STRING_LENGTH:
                return True
        elif isinstance(value, dict):
            if any(
                isinstance(key, str) and len(key) > TMAP_MAX_STRING_LENGTH
                for key in value
            ):
                return True
            pending.extend(value.values())
        elif isinstance(value, list):
            pending.extend(value)
    _ensure_tmap_deadline(deadline_at)
    return False


def _haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> int:
    from math import atan2, cos, radians, sin, sqrt

    earth_radius_m = 6371000
    d_lat = radians(lat2 - lat1)
    d_lng = radians(lng2 - lng1)
    a = sin(d_lat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(d_lng / 2) ** 2
    return int(round(earth_radius_m * 2 * atan2(sqrt(a), sqrt(1 - a))))

