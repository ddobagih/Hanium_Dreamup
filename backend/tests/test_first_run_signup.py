"""FP-010 stage 4~8 email signup substitute.

Email stands in for phone verification until release; see product/decisions.md,
2026-08-30. These tests pin the boundaries that must survive the switch back to
phone verification.
"""

from __future__ import annotations

import re

import pytest

from backend.app.services.first_run_signup import (
    FirstRunSignupError,
    email_pseudonym,
    normalized_email,
)

SECRET = "test-privacy-hmac-secret-value-at-least-32-bytes"


def test_addresses_are_rejected_before_any_storage_when_malformed() -> None:
    for candidate in ["", "   ", "nodomain", "a@b", "@example.org", "a@ example.org"]:
        with pytest.raises(FirstRunSignupError) as raised:
            normalized_email(candidate)
        assert raised.value.code == "first_run_email_invalid"


def test_only_the_domain_is_case_folded() -> None:
    # local part 의 대소문자는 공급자마다 의미가 다르므로 보존한다.
    assert normalized_email("  Tester@Example.ORG ") == "Tester@example.org"
    assert normalized_email("a.b+tag@Sub.Example.com") == "a.b+tag@sub.example.com"


def test_the_pseudonym_hides_the_address_and_is_stable() -> None:
    address = normalized_email("tester@example.org")
    first = email_pseudonym(SECRET, address)
    assert re.fullmatch(r"[0-9a-f]{64}", first)
    assert first == email_pseudonym(SECRET, address)
    # 주소나 그 조각이 결과에 남지 않는다.
    assert "tester" not in first and "example" not in first
    # 다른 키는 다른 가명을 만든다.
    assert first != email_pseudonym(SECRET + "x", address)
    # 도메인 정규화 뒤에는 대소문자 차이가 같은 가명이 된다.
    assert first == email_pseudonym(SECRET, normalized_email("tester@EXAMPLE.org"))


def test_a_missing_secret_fails_closed_rather_than_hashing_without_a_key() -> None:
    with pytest.raises(FirstRunSignupError) as raised:
        email_pseudonym("", "tester@example.org")
    assert raised.value.status_code == 503


def test_opaque_handles_are_random_and_never_derived_from_the_address() -> None:
    from backend.app.services.first_run_signup import _opaque

    address_handles = {_opaque("onb") for _ in range(64)}
    assert len(address_handles) == 64
    for handle in address_handles:
        assert re.fullmatch(r"onb_[0-9a-f]{32}", handle)
    assert re.fullmatch(r"actor_[0-9a-f]{32}", _opaque("actor"))


def test_receipts_are_sha256_and_not_reused() -> None:
    from backend.app.services.first_run_signup import _receipt

    receipts = {_receipt() for _ in range(64)}
    assert len(receipts) == 64
    for receipt in receipts:
        assert re.fullmatch(r"[0-9a-f]{64}", receipt)
