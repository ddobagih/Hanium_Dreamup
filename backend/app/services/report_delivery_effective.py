"""Effective institution-delivery state: corrected events carry no state (S18-40).

Every consumer that derives a delivery status (administrator detail, user
status projection, package proof, next-append transition base) filters with
the same clause, so a correction takes effect everywhere at once.
"""

from __future__ import annotations

from sqlalchemy import exists, select

from backend.app.models import (
    ReportInstitutionDeliveryCorrection,
    ReportInstitutionDeliveryEvent,
)


def uncorrected_delivery_event_clause(event=ReportInstitutionDeliveryEvent):
    """SQL clause that is true when no correction names ``event``."""

    return ~exists(
        select(ReportInstitutionDeliveryCorrection.id).where(
            ReportInstitutionDeliveryCorrection.corrects_event_id == event.id
        )
    )


__all__ = ["uncorrected_delivery_event_clause"]
