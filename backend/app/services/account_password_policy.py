"""New-password screening for account creation (FP-010-DETAIL-02).

Policy: new passwords have at least 12 characters and common or leaked values
are refused. No composition rules (upper/digit/symbol) are imposed, so long
passphrases, password managers and pasting keep working.

Length is counted in Unicode code points, the same unit the Gateway and the
Android client use. Screening compares an NFKC + casefold form so case and
full-width variants of a listed value are refused as well.

The bundled list is a deliberately small, offline stand-in (no network
download). Replacing it with a reviewed leaked-password corpus is part of the
FP-010-DETAIL-02 security review; the version string identifies the list.
"""

from __future__ import annotations

import unicodedata

NEW_ACCOUNT_PASSWORD_MIN_CODE_POINTS = 12
NEW_ACCOUNT_PASSWORD_MAX_CODE_POINTS = 128
COMMON_PASSWORD_LIST_VERSION = "walksafe-common-passwords.v1"

# Well-known weak values of 12+ characters (compact, casefolded form).
_COMMON_PASSWORDS = frozenset({
    "password1234", "password12345", "password123456", "password123!",
    "passwordpassword", "qwerty123456", "qwertyqwerty", "qwertyuiop12",
    "qwertyuiop123", "qwertyuiopasdf", "1q2w3e4r5t6y", "q1w2e3r4t5y6",
    "1q2w3e4r1q2w3e4r", "1q2w3e4r!@#$", "1qaz2wsx3edc", "!qaz@wsx#edc",
    "zaq12wsxcde3", "zaq1xsw2cde3", "qwer1234!@#$", "qwer1234qwer",
    "qwer1234asdf", "1234qwerasdf", "qwerasdfzxcv", "asdfghjkl123",
    "asdfasdfasdf", "zxcvbnm12345", "123456789012", "123456789123",
    "123123123123", "111111111111", "000000000000", "121212121212",
    "112233445566", "abc123456789", "abcdefghijkl", "abcd12345678",
    "a1b2c3d4e5f6", "iloveyou1234", "iloveyouiloveyou", "welcome12345",
    "letmein12345", "admin1234567", "administrator", "football1234",
    "baseball1234", "sunshine1234", "princess1234", "superman1234",
    "dragon123456", "monkey123456", "master123456", "changeme1234",
    "dkssudgktpdy", "dkssudgktpdy1", "tkfkdgo12345", "saranghae123",
    "saranghae1234", "walksafe1234", "walksafe12345", "hanium123456",
    "dreamup12345",
})

# A listed base word plus only digits/punctuation (e.g. "Password2026!").
_BASE_WORDS = (
    "password", "passw0rd", "qwertyuiop", "qwerty", "asdfghjkl", "asdfgh",
    "zxcvbnm", "iloveyou", "welcome", "letmein", "admin", "football",
    "baseball", "sunshine", "princess", "superman", "dragon", "monkey",
    "master", "changeme", "saranghae", "dkssudgktpdy", "walksafe", "hanium",
    "dreamup", "1q2w3e4r", "q1w2e3r4", "1qaz2wsx", "zaq12wsx", "qazwsx",
)
_KEYBOARD_ROWS = ("`1234567890-=", "qwertyuiop[]\\", "asdfghjkl;'", "zxcvbnm,./")
_IGNORED_SEPARATORS = frozenset(" \t-_.")


def new_account_password_length_is_valid(password: str) -> bool:
    return (
        NEW_ACCOUNT_PASSWORD_MIN_CODE_POINTS
        <= len(password)
        <= NEW_ACCOUNT_PASSWORD_MAX_CODE_POINTS
        and "\x00" not in password
    )


def _compact(password: str) -> str:
    folded = unicodedata.normalize("NFKC", password).casefold()
    return "".join(character for character in folded if character not in _IGNORED_SEPARATORS)


def _is_repeated_unit(value: str) -> bool:
    for unit in range(1, min(4, len(value) // 2) + 1):
        if len(value) % unit == 0 and value[:unit] * (len(value) // unit) == value:
            return True
    return False


def _is_straight_sequence(value: str) -> bool:
    if len(value) < 2:
        return False
    for step in (1, -1):
        if all(
            ord(later) - ord(earlier) == step
            or (earlier, later) in (("9", "0"), ("0", "9"))
            for earlier, later in zip(value, value[1:])
        ):
            return True
    return any(value in row or value in row[::-1] for row in _KEYBOARD_ROWS)


def _is_base_word_variant(value: str) -> bool:
    for word in _BASE_WORDS:
        for remainder in (
            value[len(word):] if value.startswith(word) else None,
            value[:-len(word)] if value.endswith(word) else None,
        ):
            if remainder is not None and not any(character.isalpha() for character in remainder):
                return True
    return False


def is_common_or_trivial_password(password: str) -> bool:
    compact = _compact(password)
    return (
        compact in _COMMON_PASSWORDS
        or len(set(compact)) <= 1
        or _is_repeated_unit(compact)
        or _is_straight_sequence(compact)
        or _is_base_word_variant(compact)
    )


__all__ = [
    "COMMON_PASSWORD_LIST_VERSION",
    "NEW_ACCOUNT_PASSWORD_MAX_CODE_POINTS",
    "NEW_ACCOUNT_PASSWORD_MIN_CODE_POINTS",
    "is_common_or_trivial_password",
    "new_account_password_length_is_valid",
]
