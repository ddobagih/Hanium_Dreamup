from __future__ import annotations

from datetime import UTC, datetime
import uuid

import pytest

from scripts.purge_account_enrollments import (
    AccountEnrollmentPurgeError,
    _candidate_digest,
    _parse_before,
    _validated_database_url,
)


def test_account_enrollment_purge_cutoff_and_digest_are_canonical() -> None:
    cutoff = _parse_before("2026-08-29T00:00:00Z")
    assert cutoff == datetime(2026, 8, 29, tzinfo=UTC)
    candidates = [
        uuid.UUID("123e4567-e89b-42d3-a456-426614174000"),
        uuid.UUID("123e4567-e89b-42d3-a456-426614174001"),
    ]
    assert _candidate_digest(candidates) == _candidate_digest(list(candidates))
    assert _candidate_digest(candidates) != _candidate_digest(list(reversed(candidates)))
    with pytest.raises(AccountEnrollmentPurgeError):
        _parse_before("2026-08-29 00:00:00+00:00")


def test_account_enrollment_purge_database_transport_is_mode_bound() -> None:
    local = "postgresql+psycopg://purger:secret@127.0.0.1/walksafe_test"
    assert _validated_database_url(local, deployment=False) == local
    with pytest.raises(AccountEnrollmentPurgeError, match="loopback"):
        _validated_database_url(
            "postgresql+psycopg://purger:secret@db.example.invalid/walksafe_test",
            deployment=False,
        )
    with pytest.raises(AccountEnrollmentPurgeError, match="transport"):
        _validated_database_url(
            "postgresql+psycopg://purger:secret@db.example.invalid/walksafe",
            deployment=True,
        )
