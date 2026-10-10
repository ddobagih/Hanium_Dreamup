import base64
from types import SimpleNamespace
from fastapi import FastAPI
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from asgi_client import ASGITestClient
from backend.app.api import privacy
from backend.app.database import get_db
from backend.app.field_test_security import requires_actor_identity, requires_account_generation
from backend.app.services.privacy_lifecycle import PrivacyLifecycleError


def test_registration_binds_authenticated_actor_generation_and_installation(monkeypatch):
    calls = []
    monkeypatch.setattr(privacy, 'register_installation_evidence_key', lambda db, **kw: calls.append(kw))
    app = FastAPI()
    app.include_router(privacy.create_router(SimpleNamespace(privacy_hmac_secret='synthetic', privacy_hmac_key_version=1)))
    class DB:
        def rollback(self): pass
    app.dependency_overrides[get_db] = lambda: DB()
    raw = ec.generate_private_key(ec.SECP256R1()).public_key().public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)
    payload = {'installation_id': 'installation_test_01', 'public_key': base64.urlsafe_b64encode(raw).decode().rstrip('=')}
    client = ASGITestClient(app)
    response = client.post('/privacy/installation-evidence-key', json=payload,
        headers={'x-walksafe-actor-id':'actor.test', 'x-walksafe-account-generation':'3'})
    assert response.status_code == 204
    assert response.headers['cache-control'] == 'no-store'
    assert calls[0]['actor_id'] == 'actor.test' and calls[0]['account_generation'] == 3
    assert calls[0]['installation_id'] == payload['installation_id'] and calls[0]['public_key_der'] == raw
    assert requires_actor_identity('/privacy/installation-evidence-key', 'POST')
    assert requires_account_generation('/privacy/installation-evidence-key', 'POST')
    assert client.post('/privacy/installation-evidence-key', json=payload).status_code == 422
    def conflict(*args, **kwargs):
        raise PrivacyLifecycleError('installation_evidence_key_conflict', 'conflict', status_code=409)
    monkeypatch.setattr(privacy, 'register_installation_evidence_key', conflict)
    assert client.post('/privacy/installation-evidence-key', json=payload,
        headers={'x-walksafe-actor-id':'actor.test', 'x-walksafe-account-generation':'3'}).status_code == 409
