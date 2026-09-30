from __future__ import annotations

import json
import re
from pathlib import Path

import pytest
from pydantic import TypeAdapter, ValidationError

from backend.app.api.admin_security import ReconfirmationNonce
from backend.app.services.admin_security import valid_reconfirmation_nonce


CONTRACT_PATH = Path(__file__).resolve().parents[2] / "contracts" / "walksafe.openapi.json"
CONTRACT = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
NONCE_ADAPTER = TypeAdapter(ReconfirmationNonce)
EXPECTED_HEADER_OPERATIONS = {
    ("/admin/incidents/{incident_id}/status", "patch"),
    ("/admin/raw-collections/{collection_id}/decisions", "post"),
    ("/admin/raw-collections/{collection_id}/legal-holds", "post"),
    ("/admin/report-requests/{request_id}/status", "patch"),
    ("/admin/reports/{report_id}/delivery-packages", "post"),
    ("/admin/reports/{report_id}/status", "patch"),
    ("/reports/export", "get"),
    ("/reports/{report_id}/original-access-grants", "post"),
    # S18-40: HIGH-risk append-only delivery correction.
    ("/reports/{report_id}/delivery-corrections", "post"),
    ("/reports/{report_id}/status", "patch"),
}
HEADER_SCHEMAS = {
    (path, method): parameter["schema"]
    for path, path_item in CONTRACT["paths"].items()
    for method, operation in path_item.items()
    if isinstance(operation, dict)
    for parameter in operation.get("parameters", [])
    if parameter.get("in") == "header"
    and parameter.get("name") == "X-WalkSafe-Reconfirm-Nonce"
}
NONCE_SCHEMAS = {
    "ReauthenticateRequest.nonce": CONTRACT["components"]["schemas"]
    ["ReauthenticateRequest"]["properties"]["nonce"],
    **{f"{method.upper()} {path}": schema for (path, method), schema in HEADER_SCHEMAS.items()},
}


def _contract_accepts(schema: dict, value: str) -> bool:
    return (
        schema["type"] == "string"
        and schema["minLength"] <= len(value) <= schema["maxLength"]
        and re.fullmatch(schema["pattern"], value) is not None
    )


def test_reconfirmation_nonce_contract_covers_request_and_all_high_risk_headers() -> None:
    assert set(HEADER_SCHEMAS) == EXPECTED_HEADER_OPERATIONS
    for schema in NONCE_SCHEMAS.values():
        assert schema["type"] == "string"
        assert schema["minLength"] == schema["maxLength"] == 22
        assert schema["pattern"] == r"^[A-Za-z0-9_-]{21}[AQgw]$"


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("A" * 21 + "A", True),
        ("A" * 21 + "Q", True),
        ("A" * 21 + "g", True),
        ("A" * 21 + "w", True),
        ("-_" * 10 + "A" + "w", True),
        ("A" * 21, False),
        ("A" * 23, False),
        ("A" * 128, False),
        ("A" * 21 + "B", False),
        ("A" * 21 + "R", False),
        ("A" * 21 + "h", False),
        ("A" * 21 + "x", False),
        ("A" * 21 + "=", False),
        ("A" * 21 + "+", False),
        ("A" * 21 + "/", False),
    ],
)
def test_reconfirmation_nonce_contract_matches_python_validators(value: str, expected: bool) -> None:
    assert valid_reconfirmation_nonce(value) is expected
    try:
        NONCE_ADAPTER.validate_python(value)
        api_accepts = True
    except ValidationError:
        api_accepts = False
    assert api_accepts is expected
    for location, schema in NONCE_SCHEMAS.items():
        assert _contract_accepts(schema, value) is expected, location
