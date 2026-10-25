"""Pure report-status decisions shared by legacy and administrator writes."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Literal


_REPORT_STATUS_TRANSITIONS: dict[str, tuple[str, ...]] = {
    "new": ("reviewed", "resolved"),
    "reviewed": ("new", "resolved"),
    "resolved": ("reviewed",),
}


def allowed_next_statuses(status: str) -> tuple[str, ...]:
    try:
        return _REPORT_STATUS_TRANSITIONS[status]
    except KeyError as exc:
        raise ValueError("unknown report status") from exc


@dataclass(frozen=True)
class ReportStatusTransition:
    previous_status: str
    status: str
    previous_version: int
    status_version: int
    outcome: Literal["allowed", "unchanged", "invalid"]


def decide_report_status_transition(
    current_status: str, current_version: int, next_status: str,
) -> ReportStatusTransition:
    if next_status == current_status:
        outcome = "unchanged"
    elif next_status in _REPORT_STATUS_TRANSITIONS.get(current_status, ()):
        outcome = "allowed"
    else:
        outcome = "invalid"
    return ReportStatusTransition(
        previous_status=current_status,
        status=next_status,
        previous_version=current_version,
        status_version=current_version + (1 if outcome == "allowed" else 0),
        outcome=outcome,
    )
