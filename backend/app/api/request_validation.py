"""Project parser failures onto a small, input-free public error contract."""

from __future__ import annotations

import json

from pydantic import BaseModel, ValidationError


_PUBLIC_KINDS = {
    "json_invalid": "json_invalid",
    "missing": "missing",
    "extra_forbidden": "unexpected_field",
}


def public_request_validation_detail(
    error: ValidationError | json.JSONDecodeError,
    *,
    model: type[BaseModel],
    field: str,
    code: str,
) -> dict[str, object]:
    """Use caller-owned identifiers and declared top-level schema fields only.

    Nested locations can contain input-controlled mapping keys. Coarsen those
    paths, and never copy messages, input values, context, titles, or custom
    validator error names into the public response.
    """
    failures: set[tuple[tuple[str, ...], str]] = set()
    if isinstance(error, json.JSONDecodeError):
        failures.add(((field,), "json_invalid"))
    else:
        declared_fields = frozenset(model.model_fields)
        for item in error.errors(
            include_url=False,
            include_context=False,
            include_input=False,
        ):
            location = item.get("loc", ())
            first = location[0] if location else None
            path = (
                (field, first)
                if isinstance(first, str) and first in declared_fields
                else (field,)
            )
            failures.add((path, _PUBLIC_KINDS.get(item["type"], "invalid_value")))
    if not failures:
        failures.add(((field,), "invalid_value"))
    return {
        "code": code,
        "errors": [
            {"field": list(path), "type": kind}
            for path, kind in sorted(failures)
        ],
    }
