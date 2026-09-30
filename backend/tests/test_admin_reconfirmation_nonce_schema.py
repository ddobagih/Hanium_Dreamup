from __future__ import annotations

import pytest
from pydantic import TypeAdapter, ValidationError

from backend.app.api.admin_security import ReconfirmationNonce


_NONCE = TypeAdapter(ReconfirmationNonce)


@pytest.mark.parametrize("last_character", ["A", "Q", "g", "w"])
def test_reconfirmation_nonce_accepts_canonical_sixteen_byte_base64url(last_character):
    value = "A" * 21 + last_character
    assert _NONCE.validate_python(value) == value


@pytest.mark.parametrize(
    "value",
    [
        "A" * 21,
        "A" * 23,
        "A" * 127 + "Q",
        "A" * 21 + "B",
        "A" * 21 + "R",
        "A" * 21 + "h",
        "A" * 21 + "x",
        "A" * 21 + "+",
        "A" * 21 + "/",
        "A" * 21 + "=",
    ],
)
def test_reconfirmation_nonce_rejects_noncanonical_encoding(value):
    with pytest.raises(ValidationError):
        _NONCE.validate_python(value)


def test_reconfirmation_nonce_openapi_schema_matches_service_boundary():
    schema = _NONCE.json_schema()
    assert schema["minLength"] == 22
    assert schema["maxLength"] == 22
    assert schema["pattern"] == r"^[A-Za-z0-9_-]{21}[AQgw]$"
