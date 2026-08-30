from __future__ import annotations

import hashlib
import os
from pathlib import Path
import uuid

import pytest
from sqlalchemy import func, select

from backend.app.models import RawCollection, RawCollectionChunk, RawCollectionObject
from backend.tests import test_raw_collection_postgres_integration as raw_fixture
import scripts.manage_raw_collection_retention as retention


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def _committed_collection(tmp_path: Path):
    engine, SessionFactory = raw_fixture._session_factory()
    raw_fixture._truncate_raw_tables(engine)
    content = b"expired encrypted raw collection"
    admission = raw_fixture._record_admission(
        SessionFactory, actor_id=f"raw.retention.{uuid.uuid4().hex}"
    )
    manifest = raw_fixture._manifest(admission=admission, content=content)
    raw_root = tmp_path / "raw"
    storage = raw_fixture._storage(raw_root)
    commit = raw_fixture._prepare_ready_raw_collection(
        SessionFactory,
        storage=storage,
        admission=admission,
        manifest=manifest,
        content=content,
    )
    with raw_fixture._runtime_session() as db:
        storage.commit(db, admission, commit, walk_id=manifest.walk_id)
    return engine, SessionFactory, storage, raw_root, uuid.UUID(manifest.collection_id)


def test_exact_expiry_deletes_parent_cascade_and_bound_file(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    engine, SessionFactory, storage, raw_root, collection_id = _committed_collection(tmp_path)
    operation_root = tmp_path / "operations"
    operation_id = uuid.uuid4()
    try:
        [encrypted_path] = list(raw_root.glob("*.wsrc"))
        before_bytes = encrypted_path.read_bytes()
        before_stat = encrypted_path.stat()
        with SessionFactory() as db:
            expires_at = db.scalar(
                select(RawCollection.retention_expires_at).where(
                    RawCollection.collection_id == collection_id
                )
            )
            preview = retention.preview_raw_collection_retention(
                db, as_of=expires_at, limit=1
            )
            db.rollback()
        assert preview["candidate_count"] == 1
        with SessionFactory() as db:
            assert db.get(RawCollection, collection_id) is not None
        assert encrypted_path.read_bytes() == before_bytes
        after_stat = encrypted_path.stat()
        assert (after_stat.st_dev, after_stat.st_ino) == (
            before_stat.st_dev,
            before_stat.st_ino,
        )
        assert not operation_root.exists()

        with SessionFactory() as db:
            receipt = retention.apply_raw_collection_retention(
                db,
                raw_root=raw_root,
                operation_root=operation_root,
                as_of=expires_at,
                limit=1,
                operation_id=operation_id,
                expected_candidate_digest=preview["candidate_digest"],
                create_operation_root=True,
            )
        assert receipt["result"] == "DELETED"
        with SessionFactory() as db:
            assert db.scalar(select(func.count()).select_from(RawCollection)) == 0
            assert db.scalar(select(func.count()).select_from(RawCollectionObject)) == 0
            assert db.scalar(select(func.count()).select_from(RawCollectionChunk)) == 0
        assert not list(raw_root.glob("*.wsrc"))
        receipt_path = operation_root / "receipts" / f"{operation_id}.json"
        immutable_receipt = receipt_path.read_bytes()
        with SessionFactory() as db:
            replay = retention.apply_raw_collection_retention(
                db,
                raw_root=raw_root,
                operation_root=operation_root,
                as_of=expires_at,
                limit=1,
                operation_id=operation_id,
                expected_candidate_digest=preview["candidate_digest"],
                create_operation_root=False,
            )
        assert replay == receipt
        assert receipt_path.read_bytes() == immutable_receipt
        with SessionFactory() as db:
            with pytest.raises(retention.RawRetentionError, match="different"):
                retention.apply_raw_collection_retention(
                    db,
                    raw_root=raw_root,
                    operation_root=operation_root,
                    as_of=expires_at,
                    limit=1,
                    operation_id=operation_id,
                    expected_candidate_digest="f" * 64,
                    create_operation_root=False,
                )
    finally:
        raw_fixture._truncate_raw_tables(engine)
        engine.dispose()


def test_precommit_crash_reconcile_restores_exact_file_and_parent(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    engine, SessionFactory, storage, raw_root, collection_id = _committed_collection(tmp_path)
    operation_root = tmp_path / "operations"
    operation_id = uuid.uuid4()
    try:
        with SessionFactory() as db:
            expires_at = db.scalar(
                select(RawCollection.retention_expires_at).where(
                    RawCollection.collection_id == collection_id
                )
            )
            preview = retention.preview_raw_collection_retention(
                db, as_of=expires_at, limit=1
            )
            db.rollback()
        with SessionFactory() as db:
            monkeypatch.setattr(db, "commit", lambda: (_ for _ in ()).throw(RuntimeError("crash")))
            with pytest.raises(RuntimeError, match="crash"):
                retention.apply_raw_collection_retention(
                    db,
                    raw_root=raw_root,
                    operation_root=operation_root,
                    as_of=expires_at,
                    limit=1,
                    operation_id=operation_id,
                    expected_candidate_digest=preview["candidate_digest"],
                    create_operation_root=True,
                )
        with SessionFactory() as db:
            receipt = retention.reconcile_raw_collection_retention(
                db,
                raw_root=raw_root,
                operation_root=operation_root,
                operation_id=operation_id,
            )
        assert receipt["result"] == "RECONCILED_PRECOMMIT_ABORTED"
        assert list(raw_root.glob("*.wsrc"))
        with SessionFactory() as db:
            assert db.get(RawCollection, collection_id) is not None
    finally:
        raw_fixture._truncate_raw_tables(engine)
        engine.dispose()


def test_postcommit_crash_reconcile_finishes_cleanup_and_receipt(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    engine, SessionFactory, storage, raw_root, collection_id = _committed_collection(tmp_path)
    operation_root = tmp_path / "operations"
    operation_id = uuid.uuid4()
    reconcile = retention.reconcile_raw_collection_retention
    try:
        with SessionFactory() as db:
            expires_at = db.scalar(
                select(RawCollection.retention_expires_at).where(
                    RawCollection.collection_id == collection_id
                )
            )
            preview = retention.preview_raw_collection_retention(
                db, as_of=expires_at, limit=1
            )
            db.rollback()
        monkeypatch.setattr(
            retention,
            "reconcile_raw_collection_retention",
            lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("crash")),
        )
        with SessionFactory() as db:
            with pytest.raises(RuntimeError, match="crash"):
                retention.apply_raw_collection_retention(
                    db,
                    raw_root=raw_root,
                    operation_root=operation_root,
                    as_of=expires_at,
                    limit=1,
                    operation_id=operation_id,
                    expected_candidate_digest=preview["candidate_digest"],
                    create_operation_root=True,
                )
        monkeypatch.setattr(retention, "reconcile_raw_collection_retention", reconcile)
        with SessionFactory() as db:
            receipt = reconcile(
                db,
                raw_root=raw_root,
                operation_root=operation_root,
                operation_id=operation_id,
            )
        assert receipt["result"] == "DELETED"
        assert not list(raw_root.glob("*.wsrc"))
        with SessionFactory() as db:
            assert db.get(RawCollection, collection_id) is None
    finally:
        raw_fixture._truncate_raw_tables(engine)
        engine.dispose()


def test_retention_worker_role_has_only_raw_select_and_parent_delete() -> None:
    engine, SessionFactory = raw_fixture._session_factory()
    try:
        with SessionFactory() as db:
            role = db.execute(
                select(
                    func.has_table_privilege(
                        "walksafe_raw_retention_worker",
                        "public.raw_collections",
                        "SELECT,DELETE",
                    ),
                    func.has_table_privilege(
                        "walksafe_raw_retention_worker",
                        "public.raw_collection_objects",
                        "SELECT",
                    ),
                    func.has_table_privilege(
                        "walksafe_raw_retention_worker",
                        "public.raw_collection_chunks",
                        "SELECT",
                    ),
                    func.has_table_privilege(
                        "walksafe_raw_retention_worker",
                        "public.raw_collection_objects",
                        "DELETE,INSERT,UPDATE,TRUNCATE",
                    ),
                    func.has_table_privilege(
                        "walksafe_raw_retention_worker",
                        "public.reports",
                        "SELECT,INSERT,UPDATE,DELETE,TRUNCATE",
                    ),
                )
            ).one()
        assert tuple(role) == (True, True, True, False, False)
    finally:
        engine.dispose()
