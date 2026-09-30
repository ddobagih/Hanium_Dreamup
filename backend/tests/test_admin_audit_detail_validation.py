from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
from types import SimpleNamespace

import pytest

from backend.app.services import admin_security


def _wrapped_details(value, shape):
    if shape == "root":
        return value
    if shape == "list":
        return {"events": [value]}
    if shape == "nested_list":
        return {"events": [[value]]}
    if shape == "dict_list":
        return {"summary": {"events": [value]}}
    if shape == "mixed":
        return {"events": [0, False, None, {"children": [[value]]}]}
    raise AssertionError("unknown fixture shape")


@pytest.mark.parametrize("key", ["password", "PASSWORD", "user_password"])
@pytest.mark.parametrize("shape", ["root", "list", "nested_list", "dict_list", "mixed"])
def test_audit_details_reject_forbidden_keys_at_every_container_boundary(key, shape):
    details = _wrapped_details({key: "fixture-not-a-secret"}, shape)
    original = deepcopy(details)

    with pytest.raises(ValueError, match="secret-bearing audit key is forbidden") as rejected:
        admin_security._safe_audit_details(details)

    assert "fixture-not-a-secret" not in str(rejected.value)
    assert details == original


@pytest.mark.parametrize("value", [b"fixture", ("fixture",), {"fixture"}, object()])
@pytest.mark.parametrize("shape", ["list", "nested_list", "mixed"])
def test_audit_details_reject_unsupported_values_inside_lists(value, shape):
    with pytest.raises(ValueError, match="unsupported audit detail type"):
        admin_security._safe_audit_details(_wrapped_details(value, shape))


@pytest.mark.parametrize("value", [b"fixture", ("fixture",), {"fixture"}, object()])
def test_audit_details_keep_existing_direct_value_rejections(value):
    with pytest.raises(ValueError, match="unsupported audit detail type for event"):
        admin_security._safe_audit_details({"event": value})


def test_audit_details_preserve_valid_nested_metadata_without_mutation():
    events = [
        {"action": "review", "count": 2, "success": True},
        ["text", -2, 0, 1.25, False, None, [], {}],
    ]
    details = {"events": events, "summary": {"items": [events, None]}}
    original = deepcopy(details)

    result = admin_security._safe_audit_details(details)

    assert result is details
    assert result["events"] is events
    assert result["summary"]["items"][0] is events
    assert result == original
    assert admin_security._safe_audit_details({}) == {}


@pytest.mark.parametrize("shape", ["list", "nested_list", "dict_list", "mixed"])
@pytest.mark.parametrize("forbidden_key", [True, False])
def test_audit_append_rejects_invalid_nested_metadata_before_any_database_access(
    shape, forbidden_key,
):
    value = {"password": "fixture-not-a-secret"} if forbidden_key else object()
    details = _wrapped_details(value, shape)
    touched = []

    class NoDatabaseAccess:
        def __getattr__(self, name):
            touched.append(name)
            raise AssertionError("invalid audit metadata must be rejected before database access")

    with pytest.raises(ValueError):
        admin_security._append_admin_security_audit(
            NoDatabaseAccess(),
            action="fixture.audit",
            outcome="DENIED",
            details=details,
        )

    assert touched == []


def test_audit_append_preserves_valid_nested_metadata_in_the_staged_record():
    details = {"events": [{"action": "review", "count": 2}, [True, None, "text"]]}
    original = deepcopy(details)
    observed_at = datetime(2026, 9, 20, tzinfo=timezone.utc)
    calls = []
    staged = []

    class FakeSession:
        def get_bind(self):
            calls.append("get_bind")
            return None

        def execute(self, statement):
            calls.append("execute")
            return SimpleNamespace(scalar_one_or_none=lambda: None)

        def add(self, record):
            calls.append("add")
            staged.append(record)

        def flush(self):
            calls.append("flush")

    result = admin_security._append_admin_security_audit(
        FakeSession(),
        action="fixture.audit",
        outcome="SUCCESS",
        details=details,
        created_at=observed_at,
    )

    assert calls == ["get_bind", "execute", "add", "flush"]
    assert staged == [result]
    assert result.details == original
    assert details == original
    assert result.sequence == 1
    assert result.previous_entry_sha256 is None
    assert result.created_at == observed_at
