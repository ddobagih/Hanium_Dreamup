"""Signup document version rules shared by account creation and consent bootstrap.

S01-3 / FP-013-DETAIL-02: every signup document has its own version. A version is
current when it equals the configured version or is listed as a non-substantive
equivalent of it. Nothing else is accepted as current.

GWBE-04: an optional document version that is not current is accepted (with its grant
recorded as not given) only when the server lists it as a prior published version of
that document; any other version fails closed.
"""

from __future__ import annotations

from collections.abc import Collection, Mapping
from typing import Any

SIGNUP_REQUIRED_DOCUMENT_KEYS = (
    "terms_of_service",
    "privacy_notice",
    "location_terms",
)
SIGNUP_OPTIONAL_DOCUMENT_KEYS = (
    "raw_original",
    "automatic_reporting",
    "training_reuse",
)


def signup_document_keys_are_exact(versions: Mapping[str, str]) -> bool:
    """GWBE-06: the configured documents are exactly the required + optional keys."""
    expected = SIGNUP_REQUIRED_DOCUMENT_KEYS + SIGNUP_OPTIONAL_DOCUMENT_KEYS
    return len(versions) == len(expected) and set(versions) == set(expected)


def signup_equivalent_document_versions(settings: Any) -> Mapping[str, Collection[str]]:
    value = getattr(settings, "account_signup_equivalent_document_versions", None)
    return value if isinstance(value, Mapping) else {}


def signup_prior_document_versions(settings: Any) -> Mapping[str, Collection[str]]:
    value = getattr(settings, "account_signup_prior_document_versions", None)
    return value if isinstance(value, Mapping) else {}


def signup_document_version_matches(
    current_versions: Mapping[str, str],
    equivalent_versions: Mapping[str, Collection[str]],
    key: str,
    version: str,
) -> bool:
    return version == current_versions[key] or version in equivalent_versions.get(key, ())


def signup_document_version_is_current(settings: Any, key: str, version: str) -> bool:
    return signup_document_version_matches(
        settings.account_signup_document_versions,
        signup_equivalent_document_versions(settings),
        key,
        version,
    )


def signup_document_version_is_known_prior(settings: Any, key: str, version: str) -> bool:
    return (
        key in SIGNUP_OPTIONAL_DOCUMENT_KEYS
        and version in signup_prior_document_versions(settings).get(key, ())
    )


__all__ = [
    "SIGNUP_OPTIONAL_DOCUMENT_KEYS",
    "SIGNUP_REQUIRED_DOCUMENT_KEYS",
    "signup_document_keys_are_exact",
    "signup_document_version_is_current",
    "signup_document_version_is_known_prior",
    "signup_document_version_matches",
    "signup_equivalent_document_versions",
    "signup_prior_document_versions",
]
