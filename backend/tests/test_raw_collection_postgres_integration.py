from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import timedelta
import hashlib
import importlib
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from types import SimpleNamespace
import uuid

from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from fastapi import FastAPI, Request
import httpx
import pytest
from sqlalchemy import create_engine, func, inspect, select, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import NullPool

import backend.app.main as main_app
import backend.app.services.raw_collection_storage as raw_storage
from backend.app.api.health import _exact_actor_rate_limit_group_expression
from backend.app.api.raw_collections import create_router
from backend.app.database import get_db
from backend.app.field_test_security import (
    FieldTestAccess,
    RawCollectionOperation,
    VerifiedActorAssertion,
    VerifiedRawCollectionRequestProof,
)
from backend.app.models import (
    Base,
    RawCollection,
    RawCollectionChunk,
    RawCollectionObject,
)
from backend.app.schemas import (
    AccountDeletionRequestV2,
    RawCollectionCommitV1,
    RawCollectionManifestV1,
    raw_collection_manifest_sha256,
    raw_collection_receipt_sha256,
    raw_collection_receipt_v2_sha256,
)
from backend.app.services.privacy_lifecycle import (
    PRIVACY_CONSENT_ITEM_VERSIONS,
    PRIVACY_CONSENT_POLICY_VERSION,
    PrivacyLifecycleError,
    accept_account_deletion,
    lock_privacy_subject_exclusive,
    privacy_subject_hmac,
    record_consent_event,
)
from backend.app.services.capacity_state import (
    CapacityLevel,
    CapacityStateUnavailable,
)
from backend.app.services.raw_collection_crypto import ENVELOPE_MAGIC
from backend.app.services.raw_collection_ingest import (
    RawCollectionAdmission,
    RawCollectionStorageError,
    authorize_raw_collection_write,
)
from backend.app.services.raw_collection_storage import RawCollectionStorage


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)

PRIVACY_SECRET = "walksafe-pytest-privacy-hmac-secret-boundary-v2"
MASTER_KEY = bytes(range(32))


class _StaticKeyManager:
    def synchronize(self, _db) -> None:
        return None

    def encryption_slot(self) -> SimpleNamespace:
        return SimpleNamespace(key_id="pytest-raw-master-v1", material=MASTER_KEY)

    def decryption_key(self, key_id: str) -> bytes:
        if key_id != "pytest-raw-master-v1":
            raise AssertionError("unexpected raw key id")
        return MASTER_KEY


def _session_factory():
    engine = create_engine(
        os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(),
        pool_pre_ping=True,
    )
    return engine, sessionmaker(bind=engine, expire_on_commit=False)


def _truncate_raw_tables(engine) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                "TRUNCATE TABLE raw_collection_chunks, "
                "raw_collection_objects, raw_collections"
            )
        )


@contextmanager
def _runtime_session():
    engine = create_engine(
        os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(),
        poolclass=NullPool,
    )
    try:
        with engine.connect() as connection:
            connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            connection.commit()
            with Session(bind=connection, expire_on_commit=False) as db:
                yield db
    finally:
        engine.dispose()


def _record_admission(
    SessionFactory,
    *,
    actor_id: str,
    installation_id: str | None = None,
) -> RawCollectionAdmission:
    with SessionFactory() as db:
        recording = record_consent_event(
            db,
            actor_id=actor_id,
            account_generation=1,
            installation_id=installation_id or f"install_{uuid.uuid4().hex}",
            request_id=f"raw_consent_{uuid.uuid4().hex}",
            client_revision=1,
            policy_version=PRIVACY_CONSENT_POLICY_VERSION,
            item_versions=PRIVACY_CONSENT_ITEM_VERSIONS,
            raw_source_collection=True,
            automatic_reporting=True,
            mobile_network_transfer=False,
            training_reuse=False,
            secret=PRIVACY_SECRET,
        )
    return RawCollectionAdmission(
        actor_id=actor_id,
        account_generation=1,
        privacy_subject_hmac=privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET),
        purpose="GENERAL_RAW",
        consent_receipt_sha256=recording.event.receipt_sha256,
    )


def _manifest(
    *,
    admission: RawCollectionAdmission,
    content: bytes,
    collection_id: uuid.UUID | None = None,
    object_id: uuid.UUID | None = None,
    captured_ended_at: str = "2026-08-29T00:00:05Z",
) -> RawCollectionManifestV1:
    collection_id = collection_id or uuid.uuid4()
    object_id = object_id or uuid.uuid4()
    digest = hashlib.sha256(content).hexdigest()
    value: dict[str, object] = {
        "schema_version": "walksafe.raw-collection-manifest.v1",
        "collection_id": str(collection_id),
        "walk_id": str(uuid.uuid4()),
        "segment_id": str(uuid.uuid4()),
        "purpose": admission.purpose,
        "captured_started_at": "2026-08-29T00:00:00Z",
        "captured_ended_at": captured_ended_at,
        "consent_receipt_sha256": admission.consent_receipt_sha256,
        "object_count": 1,
        "chunk_count": 1,
        "total_bytes": len(content),
        "objects": [
            {
                "object_id": str(object_id),
                "kind": "SENSOR",
                "content_type": "application/octet-stream",
                "size_bytes": len(content),
                "sha256": digest,
                "chunks": [
                    {
                        "index": 0,
                        "size_bytes": len(content),
                        "sha256": digest,
                    }
                ],
            }
        ],
    }
    value["manifest_sha256"] = raw_collection_manifest_sha256(value)
    return RawCollectionManifestV1.model_validate(value)


def _unsupported_manifest(
    *,
    admission: RawCollectionAdmission,
) -> RawCollectionManifestV1:
    object_ids = sorted((uuid.uuid4(), uuid.uuid4()), key=str)
    contents = (b"first", b"second")
    objects: list[dict[str, object]] = []
    for object_id, content in zip(object_ids, contents, strict=True):
        digest = hashlib.sha256(content).hexdigest()
        objects.append(
            {
                "object_id": str(object_id),
                "kind": "SENSOR",
                "content_type": "application/octet-stream",
                "size_bytes": len(content),
                "sha256": digest,
                "chunks": [
                    {"index": 0, "size_bytes": len(content), "sha256": digest}
                ],
            }
        )
    value: dict[str, object] = {
        "schema_version": "walksafe.raw-collection-manifest.v1",
        "collection_id": str(uuid.uuid4()),
        "walk_id": str(uuid.uuid4()),
        "segment_id": str(uuid.uuid4()),
        "purpose": admission.purpose,
        "captured_started_at": "2026-08-29T00:00:00Z",
        "captured_ended_at": "2026-08-29T00:00:05Z",
        "consent_receipt_sha256": admission.consent_receipt_sha256,
        "object_count": 2,
        "chunk_count": 2,
        "total_bytes": sum(len(content) for content in contents),
        "objects": objects,
    }
    value["manifest_sha256"] = raw_collection_manifest_sha256(value)
    return RawCollectionManifestV1.model_validate(value)


class _MutableCapacityState:
    def __init__(self, level: CapacityLevel | None = CapacityLevel.NORMAL) -> None:
        self.level = level

    def current(self) -> SimpleNamespace:
        if self.level is None:
            raise CapacityStateUnavailable("capacity state is unavailable")
        return SimpleNamespace(level=self.level)


def _storage(
    root: Path,
    *,
    capacity_state: _MutableCapacityState | None = None,
) -> RawCollectionStorage:
    root.mkdir(mode=0o700)
    return RawCollectionStorage(
        raw_object_dir=root,
        privacy_hmac_secret=PRIVACY_SECRET,
        key_manager=_StaticKeyManager(),  # type: ignore[arg-type]
        capacity_state=capacity_state or _MutableCapacityState(),  # type: ignore[arg-type]
    )


def _authorize_raw_write(
    db: Session,
    admission: RawCollectionAdmission,
) -> RawCollectionAdmission:
    return authorize_raw_collection_write(
        db,
        actor_id=admission.actor_id,
        account_generation=admission.account_generation,
        purpose=admission.purpose,  # type: ignore[arg-type]
        consent_receipt_sha256=admission.consent_receipt_sha256,
        settings=SimpleNamespace(
            privacy_hmac_secret=PRIVACY_SECRET,
            privacy_hmac_key_version=1,
        ),
    )


def _store_raw_write(
    db: Session,
    storage: RawCollectionStorage,
    admission: RawCollectionAdmission,
    manifest: RawCollectionManifestV1,
    content: bytes,
    write_kind: str,
):
    if write_kind == "manifest":
        return storage.put_manifest(db, admission, manifest)
    assert write_kind == "chunk"
    return storage.put_chunk(
        db,
        admission,
        collection_id=manifest.collection_id,
        object_id=manifest.objects[0].object_id,
        index=0,
        walk_id=manifest.walk_id,
        manifest_sha256=manifest.manifest_sha256,
        content=content,
        content_sha256=hashlib.sha256(content).hexdigest(),
    )


def _prepare_ready_raw_collection(
    SessionFactory,
    *,
    storage: RawCollectionStorage,
    admission: RawCollectionAdmission,
    manifest: RawCollectionManifestV1,
    content: bytes,
) -> RawCollectionCommitV1:
    with SessionFactory() as db:
        storage.put_manifest(db, _authorize_raw_write(db, admission), manifest)
    with SessionFactory() as db:
        storage.put_chunk(
            db,
            _authorize_raw_write(db, admission),
            collection_id=manifest.collection_id,
            object_id=manifest.objects[0].object_id,
            index=0,
            walk_id=manifest.walk_id,
            manifest_sha256=manifest.manifest_sha256,
            content=content,
            content_sha256=hashlib.sha256(content).hexdigest(),
        )
    return RawCollectionCommitV1.model_validate(
        {
            "schema_version": "walksafe.raw-collection-commit.v1",
            "collection_id": manifest.collection_id,
            "manifest_sha256": manifest.manifest_sha256,
            "object_count": 1,
            "chunk_count": 1,
            "total_bytes": len(content),
        }
    )


def _apply_privacy_action(
    db: Session,
    *,
    action: str,
    actor_id: str,
    installation_id: str,
    suffix: str,
) -> str:
    if action == "withdraw":
        recording = record_consent_event(
            db,
            actor_id=actor_id,
            account_generation=1,
            installation_id=installation_id,
            request_id=f"raw_withdraw_{suffix}",
            client_revision=2,
            policy_version=PRIVACY_CONSENT_POLICY_VERSION,
            item_versions=PRIVACY_CONSENT_ITEM_VERSIONS,
            raw_source_collection=False,
            automatic_reporting=False,
            mobile_network_transfer=False,
            training_reuse=False,
            secret=PRIVACY_SECRET,
        )
        assert recording.created is True
        return "withdrawn"
    assert action == "delete"
    accepted = accept_account_deletion(
        db,
        payload=AccountDeletionRequestV2(
            schema_version="walksafe.account-deletion-request.v2",
            request_id=f"raw_delete_{suffix}",
            client_revision=1,
            confirmation="DELETE_MY_ACCOUNT",
        ),
        actor_id=actor_id,
        account_generation=1,
        access_pre_digest="a" * 64,
        tombstone_id=None,
        secret=PRIVACY_SECRET,
    )
    assert accepted.created is True
    return "deleted"


def _wait_for_advisory_waiter(SessionFactory, backend_pid: int) -> None:
    deadline = time.monotonic() + 5
    with SessionFactory() as db:
        while time.monotonic() < deadline:
            waiting = db.scalar(
                text(
                    "SELECT EXISTS (SELECT 1 FROM pg_locks "
                    "WHERE pid = :pid AND locktype = 'advisory' AND NOT granted)"
                ),
                {"pid": backend_pid},
            )
            if waiting:
                return
            time.sleep(0.01)
    raise AssertionError("privacy fence contender did not wait for an advisory lock")


def _wait_for_database_lock_waiter(SessionFactory, backend_pid: int) -> None:
    deadline = time.monotonic() + 5
    with SessionFactory() as db:
        while time.monotonic() < deadline:
            waiting = db.scalar(
                text(
                    "SELECT EXISTS (SELECT 1 FROM pg_locks "
                    "WHERE pid = :pid AND NOT granted)"
                ),
                {"pid": backend_pid},
            )
            if waiting:
                return
            time.sleep(0.01)
    raise AssertionError("database contender did not wait for a lock")


def _assert_raw_write_absent(
    SessionFactory,
    *,
    manifest: RawCollectionManifestV1,
    root: Path,
    write_kind: str,
) -> None:
    with SessionFactory() as db:
        collection = db.get(RawCollection, uuid.UUID(manifest.collection_id))
        if write_kind == "manifest":
            assert collection is None
        else:
            assert collection is not None
            assert collection.state == "MANIFEST_ACCEPTED"
            chunk = db.get(
                RawCollectionChunk,
                (
                    uuid.UUID(manifest.collection_id),
                    uuid.UUID(manifest.objects[0].object_id),
                    0,
                ),
            )
            assert chunk is not None
            assert chunk.storage_name is None
            assert chunk.persisted_at is None
    assert list(root.iterdir()) == []


def test_raw_b1b_one_step_downgrade_drops_ephemeral_rate_rows(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    database_url = os.environ["WALKSAFE_TEST_DATABASE_URL"].strip()
    engine = create_engine(database_url, pool_pre_ping=True)
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    "TRUNCATE TABLE raw_collection_chunks, "
                    "raw_collection_objects, raw_collections"
                )
            )
            connection.execute(
                text(
                    "INSERT INTO actor_rate_limit_events "
                    "(actor_digest, rate_group, observed_at) "
                    "VALUES (:actor_digest, 'raw_collection', clock_timestamp())"
                ),
                {"actor_digest": "d" * 64},
            )
    finally:
        engine.dispose()

    monkeypatch.setenv("DATABASE_URL", database_url)
    monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", database_url)
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    try:
        command.downgrade(config, "202608250002")
        downgraded_engine = create_engine(database_url, pool_pre_ping=True)
        try:
            with downgraded_engine.connect() as connection:
                assert connection.execute(
                    text("SELECT version_num FROM alembic_version")
                ).scalar_one() == "202608250002"
                assert connection.execute(
                    text(
                        "SELECT count(*) FROM actor_rate_limit_events "
                        "WHERE rate_group = 'raw_collection'"
                    )
                ).scalar_one() == 0
        finally:
            downgraded_engine.dispose()
    finally:
        command.upgrade(config, "head")

    restored_engine = create_engine(database_url, pool_pre_ping=True)
    try:
        with restored_engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == "202608290011"
    finally:
        restored_engine.dispose()


def test_raw_b1e_upgrade_backfills_retention_and_refuses_data_loss(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    database_url = os.environ["WALKSAFE_TEST_DATABASE_URL"].strip()
    engine, SessionFactory = _session_factory()
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    monkeypatch.setenv("DATABASE_URL", database_url)
    monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", database_url)
    collection_id = uuid.uuid4()
    try:
        _truncate_raw_tables(engine)
        command.downgrade(config, "202608290002")
        admission = _record_admission(
            SessionFactory,
            actor_id=f"raw.retention-migration.{uuid.uuid4().hex}",
        )
        manifest = _manifest(
            admission=admission,
            content=b"raw retention migration fixture",
            collection_id=collection_id,
        )
        with engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO raw_collections (collection_id, "
                    "privacy_subject_hmac, account_generation, walk_id, segment_id, "
                    "purpose, consent_receipt_sha256, manifest_sha256, "
                    "captured_started_at, captured_ended_at, object_count, "
                    "chunk_count, total_bytes, state) VALUES ("
                    ":collection_id, :subject, 1, :walk_id, :segment_id, "
                    "'GENERAL_RAW', :consent, :manifest, :started_at, :ended_at, "
                    "1, 1, :total_bytes, 'MANIFEST_ACCEPTED')"
                ),
                {
                    "collection_id": collection_id,
                    "subject": admission.privacy_subject_hmac,
                    "walk_id": uuid.UUID(manifest.walk_id),
                    "segment_id": uuid.UUID(manifest.segment_id),
                    "consent": admission.consent_receipt_sha256,
                    "manifest": manifest.manifest_sha256,
                    "started_at": manifest.captured_started_at,
                    "ended_at": manifest.captured_ended_at,
                    "total_bytes": manifest.total_bytes,
                },
            )

        command.upgrade(config, "head")
        with engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == "202608290011"
            assert connection.execute(
                text(
                    "SELECT retention_class FROM raw_collections "
                    "WHERE collection_id = :collection_id"
                ),
                {"collection_id": collection_id},
            ).scalar_one() == "RAW_ORIGINAL_180D"

        with pytest.raises(SQLAlchemyError) as rejected:
            command.downgrade(config, "202608290002")
        assert getattr(rejected.value.orig, "sqlstate", None) == "55000"
        with engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == "202608290011"
    finally:
        command.upgrade(config, "head")
        with engine.begin() as connection:
            connection.execute(
                text("DELETE FROM raw_collections WHERE collection_id = :collection_id"),
                {"collection_id": collection_id},
            )
        engine.dispose()


def test_raw_b1b_schema_acl_rate_group_and_state_guards(tmp_path: Path) -> None:
    engine, SessionFactory = _session_factory()
    actor_id = f"raw.schema.{uuid.uuid4().hex}"
    admission = _record_admission(SessionFactory, actor_id=actor_id)
    content = b"raw schema trigger fixture"
    manifest = _manifest(admission=admission, content=content)
    root = tmp_path / "raw-schema"
    storage = _storage(root)
    try:
        with _runtime_session() as db:
            status, created = storage.put_manifest(db, admission, manifest)
        assert created is True
        assert status.state == "MANIFEST_ACCEPTED"

        raw_tables = {
            "privacy_consent_events",
            "raw_collections",
            "raw_collection_objects",
            "raw_collection_chunks",
        }

        def include_raw(obj, _name, type_, _reflected, _compare_to):
            if type_ == "table":
                return obj.name in raw_tables
            table = getattr(obj, "table", None)
            return table is not None and table.name in raw_tables

        with engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == "202608290011"
            assert compare_metadata(
                MigrationContext.configure(
                    connection,
                    opts={"include_object": include_raw},
                ),
                Base.metadata,
            ) == []
            assert set(inspect(connection).get_table_names()).issuperset(
                raw_tables - {"privacy_consent_events"}
            )
            triggers = set(
                connection.execute(
                    text(
                        "SELECT tgname FROM pg_trigger WHERE NOT tgisinternal "
                        "AND tgrelid IN ('raw_collections'::regclass, "
                        "'raw_collection_chunks'::regclass)"
                    )
                ).scalars()
            )
            assert {
                "raw_collections_state_guard",
                "raw_collection_chunks_persistence_immutable",
            }.issubset(triggers)
            rate_expression = connection.execute(
                text(
                    "SELECT pg_get_expr(conbin, conrelid) FROM pg_constraint "
                    "WHERE conrelid = 'actor_rate_limit_events'::regclass "
                    "AND conname = 'ck_actor_rate_limit_events_group'"
                )
            ).scalar_one()
            assert _exact_actor_rate_limit_group_expression(rate_expression)

            for table_name in (
                "raw_collections",
                "raw_collection_objects",
                "raw_collection_chunks",
            ):
                for privilege in ("SELECT", "INSERT"):
                    assert connection.execute(
                        text(
                            "SELECT has_table_privilege("
                            ":role, :table, :privilege)"
                        ),
                        {
                            "role": "walksafe_backend_runtime",
                            "table": table_name,
                            "privilege": privilege,
                        },
                    ).scalar_one() is True
                for privilege in ("DELETE", "TRUNCATE"):
                    assert connection.execute(
                        text(
                            "SELECT has_table_privilege("
                            ":role, :table, :privilege)"
                        ),
                        {
                            "role": "walksafe_backend_runtime",
                            "table": table_name,
                            "privilege": privilege,
                        },
                    ).scalar_one() is False
                for privilege in ("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE"):
                    expected = privilege == "SELECT" or (
                        table_name == "raw_collections" and privilege == "DELETE"
                    )
                    assert connection.execute(
                        text(
                            "SELECT has_table_privilege("
                            ":role, :table, :privilege)"
                        ),
                        {
                            "role": "walksafe_account_deletion_worker",
                            "table": table_name,
                            "privilege": privilege,
                        },
                    ).scalar_one() is expected
            assert connection.execute(
                text(
                    "SELECT has_column_privilege('walksafe_backend_runtime', "
                    "'raw_collections', 'state', 'UPDATE')"
                )
            ).scalar_one() is True
            for column_name in (
                "committed_at",
                "retention_expires_at",
                "receipt_sha256",
            ):
                assert connection.execute(
                    text(
                        "SELECT has_column_privilege('walksafe_backend_runtime', "
                        "'raw_collections', :column_name, 'UPDATE')"
                    ),
                    {"column_name": column_name},
                ).scalar_one() is True
            assert connection.execute(
                text(
                    "SELECT has_column_privilege('walksafe_backend_runtime', "
                    "'raw_collections', 'manifest_sha256', 'UPDATE')"
                )
            ).scalar_one() is False
            assert connection.execute(
                text(
                    "SELECT has_column_privilege('walksafe_backend_runtime', "
                    "'raw_collection_chunks', 'storage_name', 'UPDATE')"
                )
            ).scalar_one() is True
            assert connection.execute(
                text(
                    "SELECT has_column_privilege('walksafe_backend_runtime', "
                    "'raw_collection_chunks', 'declared_sha256', 'UPDATE')"
                )
            ).scalar_one() is False

            connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            try:
                invalid_insert = connection.begin_nested()
                try:
                    with pytest.raises(SQLAlchemyError) as injected_state:
                        connection.execute(
                            text(
                                "INSERT INTO raw_collections (collection_id, "
                                "privacy_subject_hmac, account_generation, walk_id, "
                                "segment_id, purpose, retention_class, "
                                "consent_receipt_sha256, "
                                "manifest_sha256, captured_started_at, captured_ended_at, "
                                "object_count, chunk_count, total_bytes, state) VALUES ("
                                ":collection_id, :subject, 1, :walk_id, :segment_id, "
                                "'GENERAL_RAW', 'RAW_ORIGINAL_180D', :receipt, :manifest, "
                                "clock_timestamp(), "
                                "clock_timestamp(), 1, 1, 1, 'COMMITTED')"
                            ),
                            {
                                "collection_id": uuid.uuid4(),
                                "subject": admission.privacy_subject_hmac,
                                "walk_id": uuid.uuid4(),
                                "segment_id": uuid.uuid4(),
                                "receipt": admission.consent_receipt_sha256,
                                "manifest": "f" * 64,
                            },
                        )
                    assert getattr(injected_state.value.orig, "sqlstate", None) == "23514"
                finally:
                    invalid_insert.rollback()

                invalid_transition = connection.begin_nested()
                try:
                    with pytest.raises(SQLAlchemyError) as skipped_state:
                        connection.execute(
                            text(
                                "UPDATE raw_collections SET state = 'COMMITTED' "
                                "WHERE collection_id = :collection_id"
                            ),
                            {"collection_id": uuid.UUID(manifest.collection_id)},
                        )
                    assert getattr(skipped_state.value.orig, "sqlstate", None) == "23514"
                finally:
                    invalid_transition.rollback()

                object_id = uuid.UUID(manifest.objects[0].object_id)
                persisted_insert = connection.begin_nested()
                try:
                    with pytest.raises(SQLAlchemyError) as injected_envelope:
                        connection.execute(
                            text(
                                "INSERT INTO raw_collection_chunks (collection_id, "
                                "object_id, chunk_index, manifest_sha256, "
                                "consent_receipt_sha256, declared_size_bytes, "
                                "declared_sha256, storage_name, envelope_version, "
                                "algorithm, aad_version, key_id, nonce, envelope_sha256, "
                                "envelope_size, persisted_at) VALUES ("
                                ":collection_id, :object_id, 1, :manifest, :receipt, "
                                "1, :declared, :storage_name, 1, 'AES-256-GCM', 1, "
                                "'pytest-raw-master-v1', :nonce, :envelope_sha, 32, "
                                "clock_timestamp())"
                            ),
                            {
                                "collection_id": uuid.UUID(manifest.collection_id),
                                "object_id": object_id,
                                "manifest": manifest.manifest_sha256,
                                "receipt": admission.consent_receipt_sha256,
                                "declared": "d" * 64,
                                "storage_name": (
                                    f"{manifest.collection_id}.{object_id}.1.wsrc"
                                ),
                                "nonce": b"0" * 12,
                                "envelope_sha": "e" * 64,
                            },
                        )
                    assert getattr(injected_envelope.value.orig, "sqlstate", None) == "23514"
                finally:
                    persisted_insert.rollback()
            finally:
                connection.execute(text("RESET SESSION AUTHORIZATION"))
    finally:
        engine.dispose()


def test_raw_b1b_manifest_chunk_replay_hiding_and_encrypted_file(
    tmp_path: Path,
) -> None:
    engine, SessionFactory = _session_factory()
    actor_id = f"raw.flow.{uuid.uuid4().hex}"
    admission = _record_admission(SessionFactory, actor_id=actor_id)
    other = RawCollectionAdmission(
        actor_id="other.raw.actor",
        account_generation=1,
        privacy_subject_hmac=privacy_subject_hmac(
            "other.raw.actor",
            1,
            PRIVACY_SECRET,
        ),
        purpose=admission.purpose,
        consent_receipt_sha256=admission.consent_receipt_sha256,
    )
    content = b"synthetic raw collection chunk\x00without personal data"
    manifest = _manifest(admission=admission, content=content)
    root = tmp_path / "raw-objects"
    storage = _storage(root)
    object_id = manifest.objects[0].object_id
    try:
        with SessionFactory() as db:
            accepted, created = storage.put_manifest(db, admission, manifest)
        assert created is True
        assert accepted.state == "MANIFEST_ACCEPTED"

        with _runtime_session() as db:
            replay, replay_created = storage.put_manifest(db, admission, manifest)
        assert replay_created is False
        assert replay == accepted

        conflict = _manifest(
            admission=admission,
            content=content,
            collection_id=uuid.UUID(manifest.collection_id),
            object_id=uuid.UUID(object_id),
            captured_ended_at="2026-08-29T00:00:06Z",
        )
        with SessionFactory() as db, pytest.raises(
            RawCollectionStorageError,
            match="another manifest",
        ) as manifest_conflict:
            storage.put_manifest(db, admission, conflict)
        assert manifest_conflict.value.status_code == 409

        with SessionFactory() as db, pytest.raises(
            RawCollectionStorageError
        ) as hidden_manifest:
            storage.put_manifest(db, other, manifest)
        assert hidden_manifest.value.status_code == 404

        unsupported = _unsupported_manifest(admission=admission)
        with SessionFactory() as db, pytest.raises(
            RawCollectionStorageError,
            match="exactly one raw object",
        ) as unsupported_shape:
            storage.put_manifest(db, admission, unsupported)
        assert unsupported_shape.value.status_code == 503
        with SessionFactory() as db:
            assert db.scalar(
                select(func.count()).select_from(RawCollection).where(
                    RawCollection.collection_id
                    == uuid.UUID(unsupported.collection_id)
                )
            ) == 0
        assert list(root.iterdir()) == []

        with SessionFactory() as db, pytest.raises(
            RawCollectionStorageError,
            match="manifest declaration",
        ) as mismatch:
            storage.put_chunk(
                db,
                admission,
                collection_id=manifest.collection_id,
                object_id=object_id,
                index=0,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
                content=b"x" * len(content),
                content_sha256=hashlib.sha256(b"x" * len(content)).hexdigest(),
            )
        assert mismatch.value.status_code == 409
        assert list(root.iterdir()) == []

        with _runtime_session() as db:
            ack, chunk_created = storage.put_chunk(
                db,
                admission,
                collection_id=manifest.collection_id,
                object_id=object_id,
                index=0,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
                content=content,
                content_sha256=hashlib.sha256(content).hexdigest(),
            )
        assert chunk_created is True
        assert ack.state == "READY_TO_COMMIT"
        [encrypted_path] = list(root.iterdir())
        before = encrypted_path.stat(follow_symlinks=False)
        envelope = encrypted_path.read_bytes()
        assert envelope.startswith(ENVELOPE_MAGIC)
        assert content not in envelope

        with SessionFactory() as db:
            stored_chunk = db.get(
                RawCollectionChunk,
                (
                    uuid.UUID(manifest.collection_id),
                    uuid.UUID(object_id),
                    0,
                ),
            )
            assert stored_chunk is not None
            assert stored_chunk.envelope_sha256 == hashlib.sha256(envelope).hexdigest()
            assert stored_chunk.envelope_size == len(envelope)
            persisted_at = stored_chunk.persisted_at

        with SessionFactory() as db, pytest.raises(
            RawCollectionStorageError
        ) as hidden_chunk:
            storage.put_chunk(
                db,
                other,
                collection_id=manifest.collection_id,
                object_id=object_id,
                index=0,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
                content=content,
                content_sha256=hashlib.sha256(content).hexdigest(),
            )
        assert hidden_chunk.value.status_code == 404

        different = bytes([content[0] ^ 1]) + content[1:]
        with SessionFactory() as db, pytest.raises(
            RawCollectionStorageError,
            match="manifest declaration",
        ) as stored_conflict:
            storage.put_chunk(
                db,
                admission,
                collection_id=manifest.collection_id,
                object_id=object_id,
                index=0,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
                content=different,
                content_sha256=hashlib.sha256(different).hexdigest(),
            )
        assert stored_conflict.value.status_code == 409
        unchanged = encrypted_path.stat(follow_symlinks=False)
        assert (unchanged.st_dev, unchanged.st_ino) == (
            before.st_dev,
            before.st_ino,
        )
        assert encrypted_path.read_bytes() == envelope
        with SessionFactory() as db:
            unchanged_chunk = db.get(
                RawCollectionChunk,
                (
                    uuid.UUID(manifest.collection_id),
                    uuid.UUID(object_id),
                    0,
                ),
            )
            assert unchanged_chunk is not None
            assert unchanged_chunk.persisted_at == persisted_at

        with _runtime_session() as db:
            replay_ack, replay_chunk_created = storage.put_chunk(
                db,
                admission,
                collection_id=manifest.collection_id,
                object_id=object_id,
                index=0,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
                content=content,
                content_sha256=hashlib.sha256(content).hexdigest(),
            )
        after = encrypted_path.stat(follow_symlinks=False)
        assert replay_chunk_created is False
        assert replay_ack == ack
        assert (after.st_dev, after.st_ino) == (before.st_dev, before.st_ino)
        assert encrypted_path.read_bytes() == envelope
        with SessionFactory() as db:
            assert db.get(
                RawCollectionChunk,
                (
                    uuid.UUID(manifest.collection_id),
                    uuid.UUID(object_id),
                    0,
                ),
            ).persisted_at == persisted_at

        with engine.connect() as connection:
            connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            try:
                reversed_state = connection.begin_nested()
                try:
                    with pytest.raises(SQLAlchemyError) as rejected_reverse:
                        connection.execute(
                            text(
                                "UPDATE raw_collections "
                                "SET state = 'MANIFEST_ACCEPTED' "
                                "WHERE collection_id = :collection_id"
                            ),
                            {"collection_id": uuid.UUID(manifest.collection_id)},
                        )
                    assert (
                        getattr(rejected_reverse.value.orig, "sqlstate", None)
                        == "23514"
                    )
                finally:
                    reversed_state.rollback()
            finally:
                connection.execute(text("RESET SESSION AUTHORIZATION"))

        with SessionFactory() as db:
            status = storage.get_status(
                db,
                actor_id=actor_id,
                account_generation=1,
                collection_id=manifest.collection_id,
                purpose=manifest.purpose,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
            )
        assert status.state == "READY_TO_COMMIT"
        assert status.received_chunk_count == 1

        with SessionFactory() as db, pytest.raises(
            RawCollectionStorageError
        ) as hidden_status:
            storage.get_status(
                db,
                actor_id="other.raw.actor",
                account_generation=1,
                collection_id=manifest.collection_id,
                purpose=manifest.purpose,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
            )
        assert hidden_status.value.status_code == 404

        with engine.connect() as connection:
            connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            try:
                fractional_receipt = connection.begin_nested()
                try:
                    with pytest.raises(SQLAlchemyError) as rejected_fractional:
                        connection.execute(
                            text(
                                "UPDATE raw_collections SET state = 'COMMITTED', "
                                "committed_at = TIMESTAMPTZ "
                                "'2026-08-29 00:00:00.500000+00', "
                                "retention_expires_at = TIMESTAMPTZ "
                                "'2026-08-29 00:00:00.500000+00' "
                                "+ INTERVAL '180 days', receipt_sha256 = :receipt "
                                "WHERE collection_id = :collection_id"
                            ),
                            {
                                "receipt": "e" * 64,
                                "collection_id": uuid.UUID(manifest.collection_id),
                            },
                        )
                    assert (
                        getattr(rejected_fractional.value.orig, "sqlstate", None)
                        == "23514"
                    )
                finally:
                    fractional_receipt.rollback()
            finally:
                connection.execute(text("RESET SESSION AUTHORIZATION"))

        commit = RawCollectionCommitV1.model_validate(
            {
                "schema_version": "walksafe.raw-collection-commit.v1",
                "collection_id": manifest.collection_id,
                "manifest_sha256": manifest.manifest_sha256,
                "object_count": 1,
                "chunk_count": 1,
                "total_bytes": len(content),
            }
        )
        with _runtime_session() as db:
            receipt = storage.commit(
                db,
                admission,
                commit,
                walk_id=manifest.walk_id,
            )
        assert receipt.collection_id == manifest.collection_id
        assert receipt.manifest_sha256 == manifest.manifest_sha256
        assert receipt.objects[0].object_id == object_id
        assert receipt.objects[0].sha256 == hashlib.sha256(content).hexdigest()
        assert receipt.persistence_marker == "DATABASE_AND_ENCRYPTED_CHUNK_STORE"
        assert receipt.retention_class == "RAW_QUARANTINE_14D"
        assert receipt.receipt_sha256 == raw_collection_receipt_v2_sha256(receipt)
        assert receipt.quarantine_expires_at - receipt.committed_at == timedelta(days=14)

        with _runtime_session() as db:
            replayed_receipt = storage.commit(
                db,
                admission,
                commit,
                walk_id=manifest.walk_id,
            )
        assert replayed_receipt == receipt

        with SessionFactory() as db:
            committed = db.scalar(
                select(RawCollection).where(
                    RawCollection.collection_id
                    == uuid.UUID(manifest.collection_id)
                )
            )
            assert committed is not None
            assert committed.state == "QUARANTINED"
            assert committed.receipt_sha256 == receipt.receipt_sha256
            assert committed.committed_at == receipt.committed_at
            assert committed.quarantine_expires_at == receipt.quarantine_expires_at
            committed_status = storage.get_status(
                db,
                actor_id=actor_id,
                account_generation=1,
                collection_id=manifest.collection_id,
                purpose=manifest.purpose,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
            )
        assert committed_status.state == "QUARANTINED"
        assert committed_status.receipt == receipt

        with _runtime_session() as db:
            committed_replay, committed_replay_created = storage.put_manifest(
                db,
                admission,
                manifest,
            )
        assert committed_replay_created is False
        assert committed_replay.receipt == receipt

        with engine.connect() as connection:
            connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            try:
                mutate_receipt = connection.begin_nested()
                try:
                    with pytest.raises(SQLAlchemyError) as rejected_mutation:
                        connection.execute(
                            text(
                                "UPDATE raw_collections SET receipt_sha256 = :receipt "
                                "WHERE collection_id = :collection_id"
                            ),
                            {
                                "receipt": "f" * 64,
                                "collection_id": uuid.UUID(manifest.collection_id),
                            },
                        )
                    assert (
                        getattr(rejected_mutation.value.orig, "sqlstate", None)
                        == "23514"
                    )
                finally:
                    mutate_receipt.rollback()
            finally:
                connection.execute(text("RESET SESSION AUTHORIZATION"))

        tampered_envelope = bytearray(envelope)
        tampered_envelope[-1] ^= 1
        encrypted_path.write_bytes(tampered_envelope)
        with _runtime_session() as db, pytest.raises(
            RawCollectionStorageError,
        ) as drifted_status:
            storage.get_status(
                db,
                actor_id=actor_id,
                account_generation=1,
                collection_id=manifest.collection_id,
                purpose=manifest.purpose,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
            )
        assert drifted_status.value.code == "raw_chunk_persistence_ambiguous"
        assert drifted_status.value.status_code == 503

        with _runtime_session() as db, pytest.raises(
            RawCollectionStorageError,
        ) as drifted_manifest_replay:
            storage.put_manifest(db, admission, manifest)
        assert (
            drifted_manifest_replay.value.code
            == "raw_chunk_persistence_ambiguous"
        )
        assert drifted_manifest_replay.value.status_code == 503

        with _runtime_session() as db, pytest.raises(
            RawCollectionStorageError,
        ) as drifted_commit_replay:
            storage.commit(db, admission, commit, walk_id=manifest.walk_id)
        assert drifted_commit_replay.value.code == "raw_chunk_persistence_ambiguous"
        assert drifted_commit_replay.value.status_code == 503
        with SessionFactory() as db:
            unchanged_receipt = db.get(
                RawCollection,
                uuid.UUID(manifest.collection_id),
            )
            assert unchanged_receipt is not None
            assert unchanged_receipt.state == "QUARANTINED"
            assert unchanged_receipt.receipt_sha256 == receipt.receipt_sha256
            assert unchanged_receipt.committed_at == receipt.committed_at
            assert unchanged_receipt.quarantine_expires_at == receipt.quarantine_expires_at
    finally:
        engine.dispose()


def test_raw_b1c_incomplete_and_mismatched_commit_do_not_advance_state(
    tmp_path: Path,
) -> None:
    engine, SessionFactory = _session_factory()
    actor_id = f"raw.commit-reject.{uuid.uuid4().hex}"
    admission = _record_admission(SessionFactory, actor_id=actor_id)
    content = b"raw incomplete commit fixture"
    manifest = _manifest(admission=admission, content=content)
    root = tmp_path / "raw-commit-reject"
    storage = _storage(root)
    commit = RawCollectionCommitV1.model_validate(
        {
            "schema_version": "walksafe.raw-collection-commit.v1",
            "collection_id": manifest.collection_id,
            "manifest_sha256": manifest.manifest_sha256,
            "object_count": 1,
            "chunk_count": 1,
            "total_bytes": len(content),
        }
    )
    try:
        with _runtime_session() as db:
            storage.put_manifest(db, admission, manifest)
        with _runtime_session() as db, pytest.raises(
            RawCollectionStorageError,
        ) as incomplete:
            storage.commit(db, admission, commit, walk_id=manifest.walk_id)
        assert incomplete.value.code == "raw_collection_incomplete"
        assert incomplete.value.status_code == 409

        with _runtime_session() as db:
            storage.put_chunk(
                db,
                admission,
                collection_id=manifest.collection_id,
                object_id=manifest.objects[0].object_id,
                index=0,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
                content=content,
                content_sha256=hashlib.sha256(content).hexdigest(),
            )
        mismatch = RawCollectionCommitV1.model_validate(
            {
                **commit.model_dump(mode="json"),
                "object_count": 2,
            }
        )
        with _runtime_session() as db, pytest.raises(
            RawCollectionStorageError,
        ) as inventory_mismatch:
            storage.commit(db, admission, mismatch, walk_id=manifest.walk_id)
        assert inventory_mismatch.value.code == "raw_commit_inventory_mismatch"
        assert inventory_mismatch.value.status_code == 409

        with SessionFactory() as db:
            collection = db.get(
                RawCollection,
                uuid.UUID(manifest.collection_id),
            )
            assert collection is not None
            assert collection.state == "READY_TO_COMMIT"
            assert collection.committed_at is None
            assert collection.retention_expires_at is None
            assert collection.receipt_sha256 is None
    finally:
        engine.dispose()


def test_raw_b1c_concurrent_commit_returns_one_immutable_receipt(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, SessionFactory = _session_factory()
    actor_id = f"raw.concurrent-commit.{uuid.uuid4().hex}"
    admission = _record_admission(SessionFactory, actor_id=actor_id)
    content = b"raw concurrent commit fixture"
    manifest = _manifest(admission=admission, content=content)
    root = tmp_path / "raw-concurrent-commit"
    storage = _storage(root)
    with _runtime_session() as db:
        storage.put_manifest(db, admission, manifest)
    with _runtime_session() as db:
        storage.put_chunk(
            db,
            admission,
            collection_id=manifest.collection_id,
            object_id=manifest.objects[0].object_id,
            index=0,
            walk_id=manifest.walk_id,
            manifest_sha256=manifest.manifest_sha256,
            content=content,
            content_sha256=hashlib.sha256(content).hexdigest(),
        )
    commit = RawCollectionCommitV1.model_validate(
        {
            "schema_version": "walksafe.raw-collection-commit.v1",
            "collection_id": manifest.collection_id,
            "manifest_sha256": manifest.manifest_sha256,
            "object_count": 1,
            "chunk_count": 1,
            "total_bytes": len(content),
        }
    )

    first_verifying = threading.Event()
    release_first = threading.Event()
    second_started = threading.Event()
    verification_lock = threading.Lock()
    verification_count = 0
    backend_pids: dict[int, int] = {}
    verify_persisted_chunk = storage._verify_persisted_chunk

    def controlled_verification(collection, item, chunk) -> None:
        nonlocal verification_count
        with verification_lock:
            verification_count += 1
            current = verification_count
        if current == 1:
            first_verifying.set()
            assert release_first.wait(timeout=5)
        verify_persisted_chunk(collection, item, chunk)

    monkeypatch.setattr(storage, "_verify_persisted_chunk", controlled_verification)

    def commit_once(index: int):
        with _runtime_session() as db:
            backend_pids[index] = db.scalar(text("SELECT pg_backend_pid()"))
            if index == 1:
                second_started.set()
            return storage.commit(
                db,
                admission,
                commit,
                walk_id=manifest.walk_id,
            )

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            first = executor.submit(commit_once, 0)
            try:
                assert first_verifying.wait(timeout=5)
                second = executor.submit(commit_once, 1)
                assert second_started.wait(timeout=5)
                _wait_for_database_lock_waiter(SessionFactory, backend_pids[1])
                assert not second.done()
            finally:
                release_first.set()
            receipts = [first.result(timeout=5), second.result(timeout=5)]
        assert receipts[0] == receipts[1]
        with SessionFactory() as db:
            collection = db.get(
                RawCollection,
                uuid.UUID(manifest.collection_id),
            )
            assert collection is not None
            assert collection.state == "QUARANTINED"
            assert collection.receipt_sha256 == receipts[0].receipt_sha256
            assert db.scalar(
                select(func.count()).select_from(RawCollection).where(
                    RawCollection.receipt_sha256 == receipts[0].receipt_sha256
                )
            ) == 1
        migration = importlib.import_module(
            "backend.alembic.versions.202608290002_raw_collection_commit_receipt"
        )
        with engine.connect() as connection:
            transaction = connection.begin()
            with pytest.raises(SQLAlchemyError) as unsafe_downgrade:
                connection.execute(text(migration._UNSAFE_DOWNGRADE_SQL))
            assert getattr(unsafe_downgrade.value.orig, "sqlstate", None) == "55000"
            transaction.rollback()
    finally:
        release_first.set()
        engine.dispose()


@pytest.mark.parametrize("privacy_action", ("withdraw", "delete"))
def test_raw_b1c_commit_fence_serializes_privacy_action_after_receipt(
    tmp_path: Path,
    privacy_action: str,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex
    actor_id = f"raw.commit-writer-first.{suffix}"
    installation_id = f"install_{suffix}"
    admission = _record_admission(
        SessionFactory,
        actor_id=actor_id,
        installation_id=installation_id,
    )
    content = b"raw commit privacy writer-first fixture"
    manifest = _manifest(admission=admission, content=content)
    storage = _storage(tmp_path / "raw-commit-writer-first")
    commit = _prepare_ready_raw_collection(
        SessionFactory,
        storage=storage,
        admission=admission,
        manifest=manifest,
        content=content,
    )
    writer_locked = threading.Event()
    release_writer = threading.Event()
    privacy_started = threading.Event()
    backend_pids: dict[str, int] = {}

    def commit_collection():
        with SessionFactory() as db:
            backend_pids["writer"] = db.scalar(text("SELECT pg_backend_pid()"))
            authorized = _authorize_raw_write(db, admission)
            writer_locked.set()
            assert release_writer.wait(timeout=5)
            return storage.commit(
                db,
                authorized,
                commit,
                walk_id=manifest.walk_id,
            )

    def change_privacy() -> str:
        with SessionFactory() as db:
            backend_pids["privacy"] = db.scalar(text("SELECT pg_backend_pid()"))
            privacy_started.set()
            return _apply_privacy_action(
                db,
                action=privacy_action,
                actor_id=actor_id,
                installation_id=installation_id,
                suffix=suffix,
            )

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            writer = executor.submit(commit_collection)
            try:
                assert writer_locked.wait(timeout=5)
                privacy = executor.submit(change_privacy)
                assert privacy_started.wait(timeout=5)
                _wait_for_advisory_waiter(SessionFactory, backend_pids["privacy"])
                assert not privacy.done()
            finally:
                release_writer.set()
            receipt = writer.result(timeout=5)
            privacy_result = privacy.result(timeout=5)

        assert privacy_result == (
            "withdrawn" if privacy_action == "withdraw" else "deleted"
        )
        with SessionFactory() as db:
            collection = db.get(RawCollection, uuid.UUID(manifest.collection_id))
            assert collection is not None
            assert collection.state == "QUARANTINED"
            assert collection.receipt_sha256 == receipt.receipt_sha256
    finally:
        release_writer.set()
        engine.dispose()


@pytest.mark.parametrize(
    ("privacy_action", "expected_code"),
    (
        ("withdraw", "raw_source_collection_consent_interrupted"),
        ("delete", "account_generation_tombstoned"),
    ),
)
def test_raw_b1c_privacy_fence_rejects_commit_before_receipt(
    tmp_path: Path,
    privacy_action: str,
    expected_code: str,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex
    actor_id = f"raw.commit-privacy-first.{suffix}"
    installation_id = f"install_{suffix}"
    admission = _record_admission(
        SessionFactory,
        actor_id=actor_id,
        installation_id=installation_id,
    )
    content = b"raw commit privacy privacy-first fixture"
    manifest = _manifest(admission=admission, content=content)
    storage = _storage(tmp_path / "raw-commit-privacy-first")
    commit = _prepare_ready_raw_collection(
        SessionFactory,
        storage=storage,
        admission=admission,
        manifest=manifest,
        content=content,
    )
    privacy_locked = threading.Event()
    release_privacy = threading.Event()
    writer_started = threading.Event()
    backend_pids: dict[str, int] = {}

    def change_privacy() -> str:
        with SessionFactory() as db:
            backend_pids["privacy"] = db.scalar(text("SELECT pg_backend_pid()"))
            lock_privacy_subject_exclusive(
                db,
                admission.privacy_subject_hmac,
                admission.account_generation,
            )
            privacy_locked.set()
            assert release_privacy.wait(timeout=5)
            return _apply_privacy_action(
                db,
                action=privacy_action,
                actor_id=actor_id,
                installation_id=installation_id,
                suffix=suffix,
            )

    def commit_collection() -> str:
        with SessionFactory() as db:
            backend_pids["writer"] = db.scalar(text("SELECT pg_backend_pid()"))
            writer_started.set()
            try:
                authorized = _authorize_raw_write(db, admission)
            except PrivacyLifecycleError as exc:
                return exc.code
            storage.commit(db, authorized, commit, walk_id=manifest.walk_id)
            return "committed"

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            privacy = executor.submit(change_privacy)
            try:
                assert privacy_locked.wait(timeout=5)
                writer = executor.submit(commit_collection)
                assert writer_started.wait(timeout=5)
                _wait_for_advisory_waiter(SessionFactory, backend_pids["writer"])
                assert not writer.done()
                with SessionFactory() as db:
                    collection = db.get(
                        RawCollection,
                        uuid.UUID(manifest.collection_id),
                    )
                    assert collection is not None
                    assert collection.state == "READY_TO_COMMIT"
                    assert collection.receipt_sha256 is None
            finally:
                release_privacy.set()
            privacy_result = privacy.result(timeout=5)
            writer_result = writer.result(timeout=5)

        assert privacy_result == (
            "withdrawn" if privacy_action == "withdraw" else "deleted"
        )
        assert writer_result == expected_code
        with SessionFactory() as db:
            collection = db.get(RawCollection, uuid.UUID(manifest.collection_id))
            assert collection is not None
            assert collection.state == "READY_TO_COMMIT"
            assert collection.receipt_sha256 is None
    finally:
        release_privacy.set()
        engine.dispose()


def test_raw_b1d_startup_removes_file_when_database_commit_failed(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, SessionFactory = _session_factory()
    _truncate_raw_tables(engine)
    actor_id = f"raw.crash-before-db.{uuid.uuid4().hex}"
    admission = _record_admission(SessionFactory, actor_id=actor_id)
    content = b"raw crash before database commit fixture"
    manifest = _manifest(admission=admission, content=content)
    root = tmp_path / "raw-crash-before-db"
    storage = _storage(root)
    key_manager = _StaticKeyManager()
    try:
        with SessionFactory() as db:
            storage.put_manifest(db, _authorize_raw_write(db, admission), manifest)

        with SessionFactory() as db:
            authorized = _authorize_raw_write(db, admission)

            def fail_commit() -> None:
                raise SQLAlchemyError("injected raw chunk commit failure")

            monkeypatch.setattr(db, "commit", fail_commit)
            with pytest.raises(RawCollectionStorageError) as ambiguous:
                storage.put_chunk(
                    db,
                    authorized,
                    collection_id=manifest.collection_id,
                    object_id=manifest.objects[0].object_id,
                    index=0,
                    walk_id=manifest.walk_id,
                    manifest_sha256=manifest.manifest_sha256,
                    content=content,
                    content_sha256=hashlib.sha256(content).hexdigest(),
                )
        assert ambiguous.value.code == "raw_chunk_persistence_ambiguous"
        assert len(list(root.glob("*.wsrc"))) == 1
        assert len(list((root / ".raw-write-journal").glob("*.json"))) == 1

        monkeypatch.setattr(main_app, "SessionLocal", SessionFactory)
        monkeypatch.setattr(main_app.settings, "raw_object_dir", root)
        monkeypatch.setattr(main_app, "report_image_key_manager", key_manager)
        main_app.reconcile_raw_collection_storage()

        assert list(root.iterdir()) == []
        with SessionFactory() as db:
            collection = db.get(RawCollection, uuid.UUID(manifest.collection_id))
            chunk = db.get(
                RawCollectionChunk,
                (
                    uuid.UUID(manifest.collection_id),
                    uuid.UUID(manifest.objects[0].object_id),
                    0,
                ),
            )
            assert collection is not None
            assert collection.state == "MANIFEST_ACCEPTED"
            assert chunk is not None
            assert chunk.storage_name is None
    finally:
        engine.dispose()


def test_raw_b1d_startup_keeps_database_committed_file_and_clears_journal(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, SessionFactory = _session_factory()
    _truncate_raw_tables(engine)
    actor_id = f"raw.crash-after-db.{uuid.uuid4().hex}"
    admission = _record_admission(SessionFactory, actor_id=actor_id)
    content = b"raw crash after database commit fixture"
    manifest = _manifest(admission=admission, content=content)
    root = tmp_path / "raw-crash-after-db"
    storage = _storage(root)
    key_manager = _StaticKeyManager()
    complete_write = raw_storage.complete_raw_chunk_write

    def fail_cleanup(_pending) -> None:
        raise OSError("injected post-commit journal cleanup failure")

    try:
        with SessionFactory() as db:
            storage.put_manifest(db, _authorize_raw_write(db, admission), manifest)
        monkeypatch.setattr(raw_storage, "complete_raw_chunk_write", fail_cleanup)
        with SessionFactory() as db, pytest.raises(
            RawCollectionStorageError,
        ) as ambiguous:
            storage.put_chunk(
                db,
                _authorize_raw_write(db, admission),
                collection_id=manifest.collection_id,
                object_id=manifest.objects[0].object_id,
                index=0,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
                content=content,
                content_sha256=hashlib.sha256(content).hexdigest(),
            )
        assert ambiguous.value.code == "raw_chunk_persistence_ambiguous"
        [encrypted_path] = list(root.glob("*.wsrc"))
        assert len(list((root / ".raw-write-journal").glob("*.json"))) == 1

        monkeypatch.setattr(raw_storage, "complete_raw_chunk_write", complete_write)
        monkeypatch.setattr(main_app, "SessionLocal", SessionFactory)
        monkeypatch.setattr(main_app.settings, "raw_object_dir", root)
        monkeypatch.setattr(main_app, "report_image_key_manager", key_manager)
        main_app.reconcile_raw_collection_storage()

        assert encrypted_path.exists()
        assert not (root / ".raw-write-journal").exists()
        with SessionFactory() as db:
            status = storage.get_status(
                db,
                actor_id=actor_id,
                account_generation=1,
                collection_id=manifest.collection_id,
                purpose=manifest.purpose,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
            )
        assert status.state == "READY_TO_COMMIT"
        assert status.received_chunk_count == 1
    finally:
        engine.dispose()


@pytest.mark.parametrize(
    ("phase", "return_code", "expected_state", "expected_file_count"),
    (
        ("before_database_commit", 77, "MANIFEST_ACCEPTED", 0),
        ("after_database_commit", 78, "READY_TO_COMMIT", 1),
    ),
)
def test_raw_b1d_process_death_reconciles_actual_postgres_boundary(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    phase: str,
    return_code: int,
    expected_state: str,
    expected_file_count: int,
) -> None:
    engine, SessionFactory = _session_factory()
    _truncate_raw_tables(engine)
    actor_id = f"raw.process-death.{phase}.{uuid.uuid4().hex}"
    root = tmp_path / phase
    code = """
import os
from pathlib import Path
import sys
from backend.tests import test_raw_collection_postgres_integration as helpers
import backend.app.services.raw_collection_storage as raw_storage

phase, actor_id, root_text = sys.argv[1:]
engine, SessionFactory = helpers._session_factory()
admission = helpers._record_admission(SessionFactory, actor_id=actor_id)
content = b'raw actual postgres process death fixture'
manifest = helpers._manifest(admission=admission, content=content)
storage = helpers._storage(Path(root_text))
with SessionFactory() as db:
    storage.put_manifest(db, helpers._authorize_raw_write(db, admission), manifest)
if phase == 'before_database_commit':
    persist = raw_storage.persist_raw_chunk_envelope
    def crash_after_publish(*args, **kwargs):
        result = persist(*args, **kwargs)
        os._exit(77)
    raw_storage.persist_raw_chunk_envelope = crash_after_publish
else:
    raw_storage.complete_raw_chunk_write = lambda _pending: os._exit(78)
with SessionFactory() as db:
    storage.put_chunk(
        db,
        helpers._authorize_raw_write(db, admission),
        collection_id=manifest.collection_id,
        object_id=manifest.objects[0].object_id,
        index=0,
        walk_id=manifest.walk_id,
        manifest_sha256=manifest.manifest_sha256,
        content=content,
        content_sha256=helpers.hashlib.sha256(content).hexdigest(),
    )
"""
    environment = dict(os.environ)
    environment["PYTHONPATH"] = str(Path(__file__).resolve().parents[2])
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    try:
        crashed = subprocess.run(
            [sys.executable, "-c", code, phase, actor_id, str(root)],
            env=environment,
            check=False,
            timeout=20,
        )
        assert crashed.returncode == return_code
        assert len(list(root.glob("*.wsrc"))) == 1
        assert len(list((root / ".raw-write-journal").glob("*.json"))) == 1

        monkeypatch.setattr(main_app, "SessionLocal", SessionFactory)
        monkeypatch.setattr(main_app.settings, "raw_object_dir", root)
        monkeypatch.setattr(
            main_app,
            "report_image_key_manager",
            _StaticKeyManager(),
        )
        main_app.reconcile_raw_collection_storage()

        assert len(list(root.glob("*.wsrc"))) == expected_file_count
        assert not (root / ".raw-write-journal").exists()
        with SessionFactory() as db:
            collection = db.scalar(
                select(RawCollection).where(
                    RawCollection.privacy_subject_hmac
                    == privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
                )
            )
            assert collection is not None
            assert collection.state == expected_state
    finally:
        engine.dispose()


def test_raw_b1d_reconciler_waits_for_inflight_chunk_database_commit(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, SessionFactory = _session_factory()
    _truncate_raw_tables(engine)
    actor_id = f"raw.reconcile-race.{uuid.uuid4().hex}"
    admission = _record_admission(SessionFactory, actor_id=actor_id)
    content = b"raw reconciliation lock fixture"
    manifest = _manifest(admission=admission, content=content)
    root = tmp_path / "raw-reconcile-race"
    storage = _storage(root)
    writer_staged = threading.Event()
    release_writer = threading.Event()
    reconciler_started = threading.Event()
    backend_pids: dict[str, int] = {}
    persist_envelope = raw_storage.persist_raw_chunk_envelope

    def controlled_persist(*args, **kwargs):
        writer_staged.set()
        assert release_writer.wait(timeout=5)
        return persist_envelope(*args, **kwargs)

    monkeypatch.setattr(
        raw_storage,
        "persist_raw_chunk_envelope",
        controlled_persist,
    )
    with SessionFactory() as db:
        storage.put_manifest(db, _authorize_raw_write(db, admission), manifest)

    def write_chunk():
        with SessionFactory() as db:
            return storage.put_chunk(
                db,
                _authorize_raw_write(db, admission),
                collection_id=manifest.collection_id,
                object_id=manifest.objects[0].object_id,
                index=0,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
                content=content,
                content_sha256=hashlib.sha256(content).hexdigest(),
            )

    def reconcile_lock() -> str:
        with SessionFactory() as db:
            backend_pids["reconciler"] = db.scalar(text("SELECT pg_backend_pid()"))
            reconciler_started.set()
            raw_storage.lock_raw_storage_reconciliation_transaction(db)
            return "locked"

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            writer = executor.submit(write_chunk)
            try:
                assert writer_staged.wait(timeout=5)
                reconciler = executor.submit(reconcile_lock)
                assert reconciler_started.wait(timeout=5)
                _wait_for_database_lock_waiter(
                    SessionFactory,
                    backend_pids["reconciler"],
                )
                assert not reconciler.done()
            finally:
                release_writer.set()
            write_result = writer.result(timeout=5)
            assert reconciler.result(timeout=5) == "locked"

        assert write_result[1] is True
        assert len(list(root.glob("*.wsrc"))) == 1
        assert not (root / ".raw-write-journal").exists()
    finally:
        release_writer.set()
        engine.dispose()


def test_raw_b1d_reconciler_waits_for_inflight_manifest_database_commit(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, SessionFactory = _session_factory()
    _truncate_raw_tables(engine)
    actor_id = f"raw.manifest-reconcile-race.{uuid.uuid4().hex}"
    admission = _record_admission(SessionFactory, actor_id=actor_id)
    content = b"raw manifest reconciliation lock fixture"
    manifest = _manifest(admission=admission, content=content)
    root = tmp_path / "raw-manifest-reconcile-race"
    storage = _storage(root)
    writer_locked = threading.Event()
    release_writer = threading.Event()
    reconciler_started = threading.Event()
    backend_pids: dict[str, int] = {}
    rows_for_collection = storage._rows_for_collection

    def controlled_rows(db, collection_id, *, for_update):
        writer_locked.set()
        assert release_writer.wait(timeout=5)
        return rows_for_collection(db, collection_id, for_update=for_update)

    monkeypatch.setattr(storage, "_rows_for_collection", controlled_rows)

    def write_manifest():
        with SessionFactory() as db:
            return storage.put_manifest(
                db,
                _authorize_raw_write(db, admission),
                manifest,
            )

    def reconcile_lock() -> str:
        with SessionFactory() as db:
            backend_pids["reconciler"] = db.scalar(text("SELECT pg_backend_pid()"))
            reconciler_started.set()
            raw_storage.lock_raw_storage_reconciliation_transaction(db)
            return "locked"

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            writer = executor.submit(write_manifest)
            try:
                assert writer_locked.wait(timeout=5)
                reconciler = executor.submit(reconcile_lock)
                assert reconciler_started.wait(timeout=5)
                _wait_for_database_lock_waiter(
                    SessionFactory,
                    backend_pids["reconciler"],
                )
                assert not reconciler.done()
            finally:
                release_writer.set()
            write_result = writer.result(timeout=5)
            assert reconciler.result(timeout=5) == "locked"

        assert write_result[1] is True
        with SessionFactory() as db:
            assert db.get(RawCollection, uuid.UUID(manifest.collection_id)) is not None
    finally:
        release_writer.set()
        engine.dispose()


@pytest.mark.parametrize(
    "level",
    (
        None,
        CapacityLevel.HOLD_NEW_RAW_COLLECTION_SESSIONS,
        CapacityLevel.HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES,
    ),
)
def test_raw_b1e_capacity_blocks_only_new_manifest_sessions(
    tmp_path: Path,
    level: CapacityLevel | None,
) -> None:
    engine, SessionFactory = _session_factory()
    _truncate_raw_tables(engine)
    capacity = _MutableCapacityState()
    actor_id = f"raw.capacity.{uuid.uuid4().hex}"
    admission = _record_admission(SessionFactory, actor_id=actor_id)
    content = b"raw capacity continuation fixture"
    manifest = _manifest(admission=admission, content=content)
    storage = _storage(tmp_path / f"capacity-{level}", capacity_state=capacity)
    try:
        with SessionFactory() as db:
            created_status, created = storage.put_manifest(
                db,
                _authorize_raw_write(db, admission),
                manifest,
            )
        assert created is True
        capacity.level = level

        with SessionFactory() as db:
            replay_status, replay_created = storage.put_manifest(
                db,
                _authorize_raw_write(db, admission),
                manifest,
            )
        assert replay_created is False
        assert replay_status == created_status

        another = _manifest(admission=admission, content=b"another raw session")
        with SessionFactory() as db, pytest.raises(
            RawCollectionStorageError,
        ) as blocked:
            storage.put_manifest(
                db,
                _authorize_raw_write(db, admission),
                another,
            )
        assert blocked.value.code == (
            "raw_capacity_state_unavailable"
            if level is None
            else "raw_capacity_hold"
        )

        with SessionFactory() as db:
            storage.put_chunk(
                db,
                _authorize_raw_write(db, admission),
                collection_id=manifest.collection_id,
                object_id=manifest.objects[0].object_id,
                index=0,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
                content=content,
                content_sha256=hashlib.sha256(content).hexdigest(),
            )
        commit = RawCollectionCommitV1.model_validate(
            {
                "schema_version": "walksafe.raw-collection-commit.v1",
                "collection_id": manifest.collection_id,
                "manifest_sha256": manifest.manifest_sha256,
                "object_count": 1,
                "chunk_count": 1,
                "total_bytes": len(content),
            }
        )
        with SessionFactory() as db:
            receipt = storage.commit(
                db,
                _authorize_raw_write(db, admission),
                commit,
                walk_id=manifest.walk_id,
            )
        assert receipt.retention_class == "RAW_QUARANTINE_14D"
        with SessionFactory() as db:
            persisted = db.get(RawCollection, uuid.UUID(manifest.collection_id))
            assert persisted is not None
            assert persisted.retention_class == "RAW_QUARANTINE_14D"
    finally:
        engine.dispose()


def test_raw_b1e_capacity_reservation_serializes_concurrent_manifests(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, SessionFactory = _session_factory()
    _truncate_raw_tables(engine)
    first_admission = _record_admission(
        SessionFactory,
        actor_id=f"raw.capacity.first.{uuid.uuid4().hex}",
    )
    second_admission = _record_admission(
        SessionFactory,
        actor_id=f"raw.capacity.second.{uuid.uuid4().hex}",
    )
    content = b"one exactly reserved raw collection"
    first_manifest = _manifest(admission=first_admission, content=content)
    second_manifest = _manifest(admission=second_admission, content=content)
    storage = _storage(tmp_path / "capacity-reservation")
    block_size = 4096
    reservation_bytes = raw_storage._raw_capacity_reservation_bytes(
        len(content),
        1,
        block_size,
    )
    monkeypatch.setattr(
        raw_storage,
        "_raw_filesystem_capacity",
        lambda _root: (reservation_bytes, block_size, 100),
    )

    def create_manifest(admission, manifest):
        with SessionFactory() as db:
            try:
                _status, created = storage.put_manifest(
                    db,
                    _authorize_raw_write(db, admission),
                    manifest,
                )
                return "created" if created else "replayed"
            except RawCollectionStorageError as exc:
                return exc.code

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(
                executor.map(
                    lambda values: create_manifest(*values),
                    (
                        (first_admission, first_manifest),
                        (second_admission, second_manifest),
                    ),
                )
            )
        assert sorted(results) == ["created", "raw_capacity_reservation_unavailable"]
        with SessionFactory() as db:
            assert db.scalar(select(func.count(RawCollection.collection_id))) == 1
    finally:
        engine.dispose()


def test_raw_b1e_capacity_reservation_rejects_inode_exhaustion(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, SessionFactory = _session_factory()
    _truncate_raw_tables(engine)
    admission = _record_admission(
        SessionFactory,
        actor_id=f"raw.capacity.inodes.{uuid.uuid4().hex}",
    )
    manifest = _manifest(admission=admission, content=b"inode capacity")
    storage = _storage(tmp_path / "capacity-inodes")
    monkeypatch.setattr(
        raw_storage,
        "_raw_filesystem_capacity",
        lambda _root: (1 << 40, 4096, 2),
    )

    try:
        with SessionFactory() as db:
            with pytest.raises(RawCollectionStorageError) as exc_info:
                storage.put_manifest(
                    db,
                    _authorize_raw_write(db, admission),
                    manifest,
                )
        assert exc_info.value.code == "raw_capacity_reservation_unavailable"
        with SessionFactory() as db:
            assert db.scalar(select(func.count(RawCollection.collection_id))) == 0
    finally:
        engine.dispose()


def test_raw_b1b_concurrent_same_chunk_publishes_once(tmp_path: Path) -> None:
    engine, SessionFactory = _session_factory()
    actor_id = f"raw.concurrent.{uuid.uuid4().hex}"
    admission = _record_admission(SessionFactory, actor_id=actor_id)
    content = b"synthetic concurrent raw chunk"
    manifest = _manifest(admission=admission, content=content)
    root = tmp_path / "raw-objects"
    storage = _storage(root)
    with SessionFactory() as db:
        storage.put_manifest(db, admission, manifest)

    def upload(_index: int):
        with SessionFactory() as db:
            return storage.put_chunk(
                db,
                admission,
                collection_id=manifest.collection_id,
                object_id=manifest.objects[0].object_id,
                index=0,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
                content=content,
                content_sha256=hashlib.sha256(content).hexdigest(),
            )

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(upload, range(2)))
        assert sorted(created for _ack, created in results) == [False, True]
        assert results[0][0] == results[1][0]
        assert len(list(root.iterdir())) == 1
        with SessionFactory() as db:
            assert db.scalar(
                select(func.count()).select_from(RawCollectionChunk).where(
                    RawCollectionChunk.collection_id
                    == uuid.UUID(manifest.collection_id),
                    RawCollectionChunk.storage_name.is_not(None),
                )
            ) == 1
            assert db.scalar(
                select(func.count()).select_from(RawCollectionObject).where(
                    RawCollectionObject.collection_id
                    == uuid.UUID(manifest.collection_id)
                )
            ) == 1
    finally:
        engine.dispose()


@pytest.mark.parametrize(
    ("privacy_action", "write_kind"),
    (("withdraw", "manifest"), ("delete", "chunk")),
)
def test_raw_b1b_writer_fence_serializes_privacy_action_after_storage(
    tmp_path: Path,
    privacy_action: str,
    write_kind: str,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex
    actor_id = f"raw.writer-first.{suffix}"
    installation_id = f"install_{suffix}"
    admission = _record_admission(
        SessionFactory,
        actor_id=actor_id,
        installation_id=installation_id,
    )
    content = b"raw privacy writer-first fixture"
    manifest = _manifest(admission=admission, content=content)
    root = tmp_path / "raw-writer-first"
    storage = _storage(root)
    if write_kind == "chunk":
        with SessionFactory() as db:
            storage.put_manifest(db, _authorize_raw_write(db, admission), manifest)

    writer_locked = threading.Event()
    release_writer = threading.Event()
    privacy_started = threading.Event()
    backend_pids: dict[str, int] = {}

    def write():
        with SessionFactory() as db:
            backend_pids["writer"] = db.scalar(text("SELECT pg_backend_pid()"))
            authorized = _authorize_raw_write(db, admission)
            writer_locked.set()
            assert release_writer.wait(timeout=5)
            result = _store_raw_write(
                db,
                storage,
                authorized,
                manifest,
                content,
                write_kind,
            )
            return result

    def change_privacy() -> str:
        with SessionFactory() as db:
            backend_pids["privacy"] = db.scalar(text("SELECT pg_backend_pid()"))
            privacy_started.set()
            result = _apply_privacy_action(
                db,
                action=privacy_action,
                actor_id=actor_id,
                installation_id=installation_id,
                suffix=suffix,
            )
            return result

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            writer = executor.submit(write)
            try:
                assert writer_locked.wait(timeout=5)
                privacy = executor.submit(change_privacy)
                assert privacy_started.wait(timeout=5)
                _wait_for_advisory_waiter(
                    SessionFactory,
                    backend_pids["privacy"],
                )
                assert not privacy.done()
            finally:
                release_writer.set()
            write_result = writer.result(timeout=5)
            privacy_result = privacy.result(timeout=5)

        assert write_result[1] is True
        assert privacy_result == (
            "withdrawn" if privacy_action == "withdraw" else "deleted"
        )
        with SessionFactory() as db:
            collection = db.get(RawCollection, uuid.UUID(manifest.collection_id))
            assert collection is not None
            if write_kind == "chunk":
                chunk = db.get(
                    RawCollectionChunk,
                    (
                        uuid.UUID(manifest.collection_id),
                        uuid.UUID(manifest.objects[0].object_id),
                        0,
                    ),
                )
                assert chunk is not None
                assert chunk.storage_name is not None
                assert len(list(root.iterdir())) == 1
            else:
                assert list(root.iterdir()) == []
    finally:
        release_writer.set()
        engine.dispose()


@pytest.mark.parametrize(
    ("privacy_action", "write_kind", "expected_code"),
    (
        ("withdraw", "chunk", "raw_source_collection_consent_interrupted"),
        ("delete", "manifest", "account_generation_tombstoned"),
    ),
)
def test_raw_b1b_privacy_fence_rejects_write_before_storage(
    tmp_path: Path,
    privacy_action: str,
    write_kind: str,
    expected_code: str,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex
    actor_id = f"raw.privacy-first.{suffix}"
    installation_id = f"install_{suffix}"
    admission = _record_admission(
        SessionFactory,
        actor_id=actor_id,
        installation_id=installation_id,
    )
    content = b"raw privacy privacy-first fixture"
    manifest = _manifest(admission=admission, content=content)
    root = tmp_path / "raw-privacy-first"
    storage = _storage(root)
    if write_kind == "chunk":
        with SessionFactory() as db:
            storage.put_manifest(db, _authorize_raw_write(db, admission), manifest)

    privacy_locked = threading.Event()
    release_privacy = threading.Event()
    writer_started = threading.Event()
    backend_pids: dict[str, int] = {}

    def change_privacy() -> str:
        with SessionFactory() as db:
            backend_pids["privacy"] = db.scalar(text("SELECT pg_backend_pid()"))
            lock_privacy_subject_exclusive(
                db,
                admission.privacy_subject_hmac,
                admission.account_generation,
            )
            privacy_locked.set()
            assert release_privacy.wait(timeout=5)
            return _apply_privacy_action(
                db,
                action=privacy_action,
                actor_id=actor_id,
                installation_id=installation_id,
                suffix=suffix,
            )

    def write() -> str:
        with SessionFactory() as db:
            backend_pids["writer"] = db.scalar(text("SELECT pg_backend_pid()"))
            writer_started.set()
            try:
                authorized = _authorize_raw_write(db, admission)
            except PrivacyLifecycleError as exc:
                return exc.code
            _store_raw_write(
                db,
                storage,
                authorized,
                manifest,
                content,
                write_kind,
            )
            return "stored"

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            privacy = executor.submit(change_privacy)
            try:
                assert privacy_locked.wait(timeout=5)
                writer = executor.submit(write)
                assert writer_started.wait(timeout=5)
                _wait_for_advisory_waiter(SessionFactory, backend_pids["writer"])
                assert not writer.done()
                _assert_raw_write_absent(
                    SessionFactory,
                    manifest=manifest,
                    root=root,
                    write_kind=write_kind,
                )
            finally:
                release_privacy.set()
            privacy_result = privacy.result(timeout=5)
            writer_result = writer.result(timeout=5)

        assert privacy_result == (
            "withdrawn" if privacy_action == "withdraw" else "deleted"
        )
        assert writer_result == expected_code
        _assert_raw_write_absent(
            SessionFactory,
            manifest=manifest,
            root=root,
            write_kind=write_kind,
        )
    finally:
        release_privacy.set()
        engine.dispose()


def test_raw_b1b_chunk_body_completes_before_admission_lock(
    tmp_path: Path,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex
    actor_id = f"raw.slow-body.{suffix}"
    admission = _record_admission(SessionFactory, actor_id=actor_id)
    content = b"raw body split across the admission boundary"
    content_sha256 = hashlib.sha256(content).hexdigest()
    manifest = _manifest(admission=admission, content=content)
    root = tmp_path / "raw-slow-body"
    storage = _storage(root)
    with SessionFactory() as db:
        storage.put_manifest(db, _authorize_raw_write(db, admission), manifest)

    gate_called = threading.Event()
    request_backend_pid: dict[str, int] = {}

    def observed_gate(db: Session, **kwargs) -> RawCollectionAdmission:
        gate_called.set()
        return authorize_raw_collection_write(db, **kwargs)

    def request_database():
        with SessionFactory() as db:
            request_backend_pid["value"] = db.scalar(
                text("SELECT pg_backend_pid()")
            )
            yield db

    settings = SimpleNamespace(
        raw_ingest_enabled=True,
        privacy_hmac_secret=PRIVACY_SECRET,
        privacy_hmac_key_version=1,
        maintenance_lock_path=None,
        maintenance_lock_group_gid=None,
    )
    app = FastAPI()
    app.include_router(
        create_router(
            settings,  # type: ignore[arg-type]
            admission_gate=observed_gate,
            storage_handler=storage,
        )
    )
    app.dependency_overrides[get_db] = request_database

    @app.middleware("http")
    async def bind_verified_gateway_actor(request: Request, call_next):
        request.state.verified_actor_assertion = VerifiedActorAssertion(
            actor_id=actor_id,
            account_generation=1,
            access=FieldTestAccess.FIELD,
        )
        request.state.verified_raw_collection_request_proof = (
            VerifiedRawCollectionRequestProof(
                actor_id=actor_id,
                account_generation=1,
                operation=RawCollectionOperation.PUT_CHUNK,
                method="PUT",
                path=request.url.path,
                purpose=admission.purpose,
                walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256,
                consent_receipt_sha256=admission.consent_receipt_sha256,
                chunk_sha256=content_sha256,
                commit_sha256=None,
            )
        )
        return await call_next(request)

    path = (
        f"/raw-collections/{manifest.collection_id}/objects/"
        f"{manifest.objects[0].object_id}/chunks/0"
    )
    headers = {
        "x-walksafe-actor-id": actor_id,
        "x-walksafe-account-generation": "1",
        "x-walksafe-raw-purpose": admission.purpose,
        "x-walksafe-raw-walk-id": manifest.walk_id,
        "x-walksafe-raw-manifest-sha256": manifest.manifest_sha256,
        "x-walksafe-consent-receipt-sha256": admission.consent_receipt_sha256,
        "x-walksafe-chunk-sha256": content_sha256,
        "content-type": "application/octet-stream",
        "content-length": str(len(content)),
    }

    async def send_slow_body() -> httpx.Response:
        first_part_consumed = asyncio.Event()
        release_body = asyncio.Event()

        async def body():
            yield content[:1]
            first_part_consumed.set()
            await release_body.wait()
            yield content[1:]

        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as client:
            request = asyncio.create_task(
                client.put(path, headers=headers, content=body())
            )
            await asyncio.wait_for(first_part_consumed.wait(), timeout=5)
            try:
                assert gate_called.is_set() is False
                assert "value" in request_backend_pid

                def advisory_lock_count() -> int:
                    with SessionFactory() as db:
                        return db.scalar(
                            text(
                                "SELECT count(*) FROM pg_locks "
                                "WHERE pid = :pid AND locktype = 'advisory'"
                            ),
                            {"pid": request_backend_pid["value"]},
                        )

                assert await asyncio.to_thread(advisory_lock_count) == 0
            finally:
                release_body.set()
            return await asyncio.wait_for(request, timeout=5)

    try:
        response = asyncio.run(send_slow_body())
        assert response.status_code == 201
        assert gate_called.is_set() is True
        assert response.json()["state"] == "READY_TO_COMMIT"
        assert len(list(root.iterdir())) == 1
    finally:
        engine.dispose()
