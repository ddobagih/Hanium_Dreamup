"""Real PostgreSQL ownership checks after verified Gateway identity binding.

Signature/authentication rejection is tested separately in test_field_test_security.
These tests exercise real routes, SQL ownership filters and absence of foreign mutations.
"""

from datetime import UTC, datetime
import copy
import hashlib
import os
from types import SimpleNamespace
import uuid

import pytest
from fastapi import FastAPI, Request
from sqlalchemy import select, func

from asgi_client import ASGITestClient
from backend.app.api import raw_collections, privacy, report_user_requests, reports
from backend.app.database import get_db
from backend.app.config import get_settings
from backend.app.field_test_security import (
    FieldTestAccess,
    RawCollectionOperation,
    VerifiedActorAssertion,
    VerifiedRawCollectionRequestProof,
    VerifiedPrivacyDeletionAssertion,
)
from backend.app.models import (
    Report,
    ReportUserRequest,
    ReportContentRevision,
    RawCollection,
)
from backend.app.schemas import DeviceDeletionEvidenceV2, raw_collection_commit_sha256
from backend.app.services.privacy_lifecycle import (
    privacy_subject_hmac,
    deletion_evidence_sha256,
)
from test_raw_collection_postgres_integration import (
    _session_factory,
    _record_admission,
    _manifest,
    _storage,
    _prepare_ready_raw_collection,
    PRIVACY_SECRET,
)
from test_fp046_postgres_integration import _accept

pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="isolated PostgreSQL required",
)


def _client(factory, routers):
    app = FastAPI()
    for router in routers:
        app.include_router(router)

    def database():
        with factory() as db:
            yield db

    app.dependency_overrides[get_db] = database

    @app.middleware("http")
    async def verified_gateway(request: Request, call_next):
        h = request.headers
        actor = h["x-walksafe-actor-id"]
        generation = int(h["x-walksafe-account-generation"])
        request.state.verified_actor_assertion = VerifiedActorAssertion(
            actor, generation, FieldTestAccess.FIELD
        )
        if request.url.path.startswith("/raw-collections/"):
            operation = (
                RawCollectionOperation.GET_STATUS
                if request.method == "GET"
                else (
                    RawCollectionOperation.PUT_MANIFEST
                    if request.url.path.endswith("/manifest")
                    else (
                        RawCollectionOperation.COMMIT
                        if request.url.path.endswith("/commit")
                        else RawCollectionOperation.PUT_CHUNK
                    )
                )
            )
            request.state.verified_raw_collection_request_proof = (
                VerifiedRawCollectionRequestProof(
                    actor,
                    generation,
                    operation,
                    request.method,
                    request.url.path,
                    h["x-walksafe-raw-purpose"],
                    h["x-walksafe-raw-walk-id"],
                    h["x-walksafe-raw-manifest-sha256"],
                    (
                        None
                        if request.method == "GET"
                        else h.get("x-walksafe-consent-receipt-sha256")
                    ),
                    h.get("x-walksafe-chunk-sha256"),
                    h.get("x-walksafe-raw-commit-sha256"),
                )
            )
        if request.url.path.startswith("/privacy/account-deletions/"):
            request.state.privacy_deletion_assertion = VerifiedPrivacyDeletionAssertion(
                actor,
                generation,
                request.url.path.split("/")[3],
                h["x-walksafe-deletion-tombstone-id"],
                h["x-walksafe-deletion-access-pre-digest"],
                request.method,
                request.url.path,
                "0" * 64,
            )
        return await call_next(request)

    return ASGITestClient(app)


def test_foreign_raw_routes_hide_resources_and_preflight_has_no_existence_oracle(
    tmp_path,
):
    engine, factory = _session_factory()
    owner = _record_admission(factory, actor_id="owner." + uuid.uuid4().hex)
    foreign = _record_admission(factory, actor_id="other." + uuid.uuid4().hex)
    content = b"synthetic raw bytes"
    manifest = _manifest(admission=owner, content=content)
    storage = _storage(tmp_path / "raw")
    commit = _prepare_ready_raw_collection(
        factory, storage=storage, admission=owner, manifest=manifest, content=content
    )
    settings = SimpleNamespace(
        raw_ingest_enabled=True,
        privacy_hmac_secret=PRIVACY_SECRET,
        privacy_hmac_key_version=1,
        maintenance_lock_path=None,
        maintenance_lock_group_gid=None,
    )
    client = _client(
        factory, [raw_collections.create_router(settings, storage_handler=storage)]
    )
    root = "/raw-collections/" + manifest.collection_id
    chunk_path = "/objects/" + manifest.objects[0].object_id + "/chunks/0"

    def headers(admission):
        return {
            "x-walksafe-actor-id": admission.actor_id,
            "x-walksafe-account-generation": "1",
            "x-walksafe-raw-purpose": manifest.purpose,
            "x-walksafe-raw-walk-id": manifest.walk_id,
            "x-walksafe-raw-manifest-sha256": manifest.manifest_sha256,
            "x-walksafe-consent-receipt-sha256": admission.consent_receipt_sha256,
        }

    try:
        assert client.get(root, headers=headers(owner)).status_code == 200
        before_files = {p.name: p.read_bytes() for p in (tmp_path / "raw").iterdir()}
        for method, suffix, payload in [
            ("GET", "", {}),
            ("PUT", chunk_path, {"content": content}),
            ("POST", "/commit", {"json": commit.model_dump(mode="json")}),
        ]:
            h = headers(foreign)
            if suffix == chunk_path:
                h["x-walksafe-chunk-sha256"] = hashlib.sha256(content).hexdigest()
                h["content-type"] = "application/octet-stream"
            if suffix == "/commit":
                h["x-walksafe-raw-commit-sha256"] = raw_collection_commit_sha256(commit)
            response = client.request(method, root + suffix, headers=h, **payload)
            assert response.status_code == 404, (method, suffix, response.text)
        # A foreign actor supplies its own valid receipt and manifest, targeting the occupied ID.
        foreign_manifest = _manifest(
            admission=foreign,
            content=content,
            collection_id=uuid.UUID(manifest.collection_id),
        )
        h = headers(foreign)
        h["x-walksafe-raw-walk-id"] = foreign_manifest.walk_id
        h["x-walksafe-raw-manifest-sha256"] = foreign_manifest.manifest_sha256
        response = client.request(
            "PUT",
            root + "/manifest",
            headers=h,
            json=foreign_manifest.model_dump(mode="json"),
        )
        assert response.status_code == 404, response.text
        # HEAD deliberately checks only upload admission, never the target resource's existence.
        for suffix in ["/manifest", chunk_path, "/commit"]:
            h = headers(foreign)
            if suffix == chunk_path:
                h["x-walksafe-chunk-sha256"] = hashlib.sha256(content).hexdigest()
            if suffix == "/commit":
                h["x-walksafe-raw-commit-sha256"] = raw_collection_commit_sha256(commit)
            for target in [root, "/raw-collections/" + str(uuid.uuid4())]:
                response = client.request("HEAD", target + suffix, headers=h)
                assert response.status_code == 204, (suffix, response.status_code)
                assert response.content == b""
        assert before_files == {
            p.name: p.read_bytes() for p in (tmp_path / "raw").iterdir()
        }
        with factory() as db:
            row = db.get(RawCollection, uuid.UUID(manifest.collection_id))
            assert row.state == "READY_TO_COMMIT"
            assert row.privacy_subject_hmac == owner.privacy_subject_hmac
        assert client.get(root, headers=headers(owner)).status_code == 200
    finally:
        engine.dispose()


@pytest.mark.parametrize(
    "suffix,method,body",
    [
        (
            "/requests",
            "POST",
            {
                "client_request_id": str(uuid.uuid4()),
                "request_type": "CORRECTION",
                "request_text": "synthetic correction",
            },
        ),
        (
            "/corrections",
            "POST",
            {
                "expected_revision": 0,
                "idempotency_key": str(uuid.uuid4()),
                "user_description": "synthetic description",
            },
        ),
        ("/status", "GET", None),
    ],
)
def test_foreign_report_routes_conceal_and_do_not_mutate(suffix, method, body):
    engine, factory = _session_factory()
    settings = copy.copy(get_settings())
    settings.field_test_security_enabled = True
    settings.privacy_hmac_secret = PRIVACY_SECRET
    owner = "report.owner." + uuid.uuid4().hex
    foreign = "report.other." + uuid.uuid4().hex
    report_id = uuid.uuid4()
    with factory() as db:
        now = datetime.now(UTC)
        db.add(
            Report(
                id=report_id,
                status="new",
                status_version=1,
                class_id=1,
                class_name="damaged_tactile_block",
                confidence=0.9,
                bbox_x=0.1,
                bbox_y=0.1,
                bbox_width=0.5,
                bbox_height=0.5,
                captured_at=now,
                source="android",
                image_path=f"owner-test-{report_id}.jpg.enc",
                image_content_type="image/jpeg",
                payload={"schema_version": "detect.v2"},
                privacy_subject_hmac=privacy_subject_hmac(owner, 7, PRIVACY_SECRET),
                account_generation=7,
                created_at=now,
                updated_at=now,
                client_payload_sha256="a" * 64,
                client_payload_bytes=128,
                persistence_marker=uuid.uuid4(),
            )
        )
        db.commit()
    client = _client(
        factory,
        [
            report_user_requests.create_router(settings),
            reports.create_router(settings, None),
        ],
    )
    path = (
        f"/reports/v2/{report_id}"
        if suffix == "/status"
        else f"/reports/mine/{report_id}"
    ) + suffix
    h = {"x-walksafe-actor-id": foreign, "x-walksafe-account-generation": "7"}
    try:
        response = client.request(
            method, path, headers=h, **({"json": body} if body is not None else {})
        )
        assert response.status_code == 404, response.text
        stale_headers = {
            "x-walksafe-actor-id": owner,
            "x-walksafe-account-generation": "8",
        }
        stale = client.request(
            method,
            path,
            headers=stale_headers,
            **({"json": body} if body is not None else {}),
        )
        assert stale.status_code == 404, stale.text
        with factory() as db:
            assert (
                db.scalar(
                    select(func.count())
                    .select_from(ReportUserRequest)
                    .where(ReportUserRequest.report_id == report_id)
                )
                == 0
            )
            assert (
                db.scalar(
                    select(func.count())
                    .select_from(ReportContentRevision)
                    .where(ReportContentRevision.report_id == report_id)
                )
                == 0
            )
            assert db.get(
                Report, report_id
            ).privacy_subject_hmac == privacy_subject_hmac(owner, 7, PRIVACY_SECRET)
        h["x-walksafe-actor-id"] = owner
        own = client.request(
            method, path, headers=h, **({"json": body} if body is not None else {})
        )
        assert own.status_code == (200 if method == "GET" else 201), own.text
    finally:
        engine.dispose()


@pytest.mark.parametrize("evidence_request", [False, True])
def test_foreign_deletion_routes_conceal_even_with_the_owners_capability(
    evidence_request,
):
    engine, factory = _session_factory()
    owner = "delete.owner." + uuid.uuid4().hex
    request_id = "delete_" + uuid.uuid4().hex
    pre = "a" * 64
    accepted = _accept(
        factory, actor_id=owner, request_id=request_id, pre_digest=pre
    ).status
    settings = SimpleNamespace(
        field_test_security_enabled=True,
        privacy_hmac_secret=PRIVACY_SECRET,
        privacy_hmac_key_version=1,
    )
    client = _client(factory, [privacy.create_router(settings)])
    evidence = DeviceDeletionEvidenceV2(
        schema_version="walksafe.device-deletion-evidence.v2",
        request_id=request_id,
        tombstone_id=str(accepted.tombstone_id),
        request_receipt_sha256=accepted.request_receipt_sha256,
        installation_id="install_foreign_test",
        evidence_id="evidence_" + uuid.uuid4().hex,
        client_revision=1,
        expected_status_revision=accepted.revision,
        item="device_untransmitted_data",
        result="DELETED",
        completed_at=datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        evidence_sha256="0" * 64,
    )
    evidence = evidence.model_copy(
        update={"evidence_sha256": deletion_evidence_sha256(evidence)}
    )
    h = {
        "x-walksafe-actor-id": "foreign." + uuid.uuid4().hex,
        "x-walksafe-account-generation": "1",
        "x-walksafe-deletion-tombstone-id": str(accepted.tombstone_id),
        "x-walksafe-deletion-access-pre-digest": pre,
    }
    root = f"/privacy/account-deletions/{request_id}"
    try:
        response = client.request(
            "POST" if evidence_request else "GET",
            root + ("/device-evidence" if evidence_request else "/status"),
            headers=h,
            **({"json": evidence.model_dump(mode="json")} if evidence_request else {}),
        )
        assert response.status_code == 404, response.text
        h["x-walksafe-actor-id"] = owner
        current = client.get(root + "/status", headers=h)
        assert current.status_code == 200, current.text
        assert current.json()["revision"] == accepted.revision
    finally:
        engine.dispose()
