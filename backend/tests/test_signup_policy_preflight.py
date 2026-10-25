"""Read-only signup metadata retains the existing account consent rules."""
from copy import deepcopy

from fastapi import FastAPI
from pydantic import ValidationError
import pytest

from asgi_client import ASGITestClient
from backend.app.account_schemas import AccountSignupPolicyV1
from backend.app.api.accounts import create_router
from backend.app.services.accounts import UnavailableOtpSender
from test_accounts import _settings


def _client(settings):
    app = FastAPI()
    # Any DB acquisition or mail use would fail: policy has no dependencies.
    app.include_router(create_router(settings, service=object(), sender=UnavailableOtpSender(), rate_limiter=object()))
    return ASGITestClient(app)


def test_policy_reads_current_required_equivalent_prior_without_database_or_mail():
    settings = _settings(
        account_signup_equivalent_document_versions={"terms_of_service": ["terms.v0", "terms.v00"]},
        account_signup_prior_document_versions={"training_reuse": ["training.v0"]},
    )
    response = _client(settings).get("/account-signup-policy")
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"
    body = response.json()
    assert set(body) == {"schema_version", "documents"}
    assert body["schema_version"] == "walksafe.account-signup-policy.v1"
    assert set(body["documents"]) == set(settings.account_signup_document_versions)
    for key, document in body["documents"].items():
        assert set(document) == {"version", "required", "equivalent_versions", "prior_versions"}
        assert document["version"] == settings.account_signup_document_versions[key]
        assert document["required"] == (key in {"terms_of_service", "privacy_notice", "location_terms"})
    assert body["documents"]["terms_of_service"]["equivalent_versions"] == ["terms.v0", "terms.v00"]
    assert body["documents"]["training_reuse"]["prior_versions"] == ["training.v0"]


@pytest.mark.parametrize("setting,value", [
    ("account_signup_document_versions", {}),
    ("account_signup_document_versions", None),
    ("account_signup_equivalent_document_versions", {"unknown": ["v1"]}),
    ("account_signup_equivalent_document_versions", {"terms_of_service": "v0"}),
    ("account_signup_equivalent_document_versions", {"terms_of_service": ["v0", "v0"]}),
    ("account_signup_equivalent_document_versions", {"terms_of_service": ["v" + str(n) for n in range(9)]}),
    ("account_signup_equivalent_document_versions", {"terms_of_service": ["v" * 65]}),
    ("account_signup_equivalent_document_versions", {"terms_of_service": [True]}),
    ("account_signup_prior_document_versions", {"terms_of_service": ["v0"]}),
    ("account_signup_prior_document_versions", None),
])
def test_malformed_configuration_fails_closed_without_caching(setting, value):
    response = _client(_settings(**{setting: value})).get("/account-signup-policy")
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "account_enrollment_unavailable"
    assert response.headers["cache-control"] == "no-store"


@pytest.mark.parametrize("kind", ["current_equivalent", "current_prior", "equivalent_prior"])
def test_overlapping_version_configuration_fails_closed(kind):
    settings = _settings()
    current = settings.account_signup_document_versions["training_reuse"]
    settings.account_signup_equivalent_document_versions = {"training_reuse": [current if kind == "current_equivalent" else "old.v0"]}
    settings.account_signup_prior_document_versions = {"training_reuse": [current if kind == "current_prior" else "old.v0"]} if kind != "current_equivalent" else {}
    assert _client(settings).get("/account-signup-policy").status_code == 503


@pytest.mark.parametrize("change", ["extra_document", "extra_field", "missing_document", "wrong_required", "string_boolean"])
def test_policy_response_schema_is_strict(change):
    body = deepcopy(_client(_settings()).get("/account-signup-policy").json())
    document = body["documents"]["terms_of_service"]
    if change == "extra_document":
        body["documents"]["unknown"] = document
    elif change == "extra_field":
        document["unknown"] = "v1"
    elif change == "missing_document":
        body["documents"].pop("training_reuse")
    elif change == "wrong_required":
        document["required"] = False
    else:
        document["required"] = "true"
    with pytest.raises(ValidationError):
        AccountSignupPolicyV1.model_validate(body)



def test_real_app_security_allows_service_token_without_actor_and_never_opens_db(monkeypatch):
    from sqlalchemy.engine import Engine
    from backend.app.main import app, settings
    from backend.app.field_test_security import (
        FieldTestAccess, required_field_test_access, requires_actor_identity,
    )
    def unexpected_database(*args, **kwargs):
        raise AssertionError("policy preflight opened a database connection")
    monkeypatch.setattr(Engine, "connect", unexpected_database)
    monkeypatch.setattr(settings, "field_test_security_enabled", True)
    monkeypatch.setattr(settings, "field_test_token", "signup-policy-service-token-test-only")
    monkeypatch.setattr(settings, "walksafe_environment", "development")
    monkeypatch.setattr(settings, "allow_insecure_local_dev", True)
    client = ASGITestClient(app)
    assert client.get("/account-signup-policy").status_code == 401
    accepted = client.get("/account-signup-policy", headers={
        "x-walksafe-field-test-token": settings.field_test_token,
    })
    assert accepted.status_code == 200
    assert accepted.headers["cache-control"] == "no-store"
    assert set(accepted.json()["documents"]) == set(settings.account_signup_document_versions)
    assert required_field_test_access("/account-signup-policy", "GET") is FieldTestAccess.FIELD
    assert not requires_actor_identity("/account-signup-policy", "GET")
