from fastapi import FastAPI
from asgi_client import ASGITestClient
from backend.app.api.accounts import create_router
from backend.app.services.accounts import UnavailableOtpSender
from backend.app.field_test_security import required_field_test_access, requires_actor_identity, FieldTestAccess
from test_accounts import _settings


def test_policy_is_public_metadata_without_database_or_email_delivery():
    settings = _settings(account_signup_equivalent_document_versions={"terms_of_service": ["terms.v0"]},
                         account_signup_prior_document_versions={"training_reuse": ["training.v0"]})
    app = FastAPI()
    app.include_router(create_router(settings, sender=UnavailableOtpSender(), rate_limiter=object()))
    response = ASGITestClient(app).get('/account-signup-policy')
    assert response.status_code == 200
    assert response.headers['cache-control'] == 'no-store'
    body = response.json()
    assert set(body) == {'schema_version', 'documents'}
    assert set(body['documents']) == set(settings.account_signup_document_versions)
    assert body['documents']['terms_of_service']['equivalent_versions'] == ['terms.v0']
    assert body['documents']['training_reuse']['prior_versions'] == ['training.v0']
    assert body['documents']['training_reuse']['required'] is False
    assert required_field_test_access('/account-signup-policy', 'GET') is FieldTestAccess.FIELD
    assert not requires_actor_identity('/account-signup-policy', 'GET')


def test_missing_document_configuration_is_not_an_approvable_policy():
    settings = _settings(account_signup_document_versions={})
    app = FastAPI()
    app.include_router(create_router(settings, sender=UnavailableOtpSender(), rate_limiter=object()))
    response = ASGITestClient(app).get('/account-signup-policy')
    assert response.status_code == 503
    assert response.headers['cache-control'] == 'no-store'
