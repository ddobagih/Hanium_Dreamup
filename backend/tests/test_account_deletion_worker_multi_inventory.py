from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest
from sqlalchemy import delete, select

from backend.tests import test_fp046_postgres_integration as helpers
from backend.tests.test_raw_collection_postgres_integration import _StaticKeyManager
from scripts.account_deletion_worker import AccountDeletionWorkerError


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def _stored_collection(
    SessionFactory, raw_root: Path, actor_id: str, *,
    received_chunks: int = 2, commit_collection: bool = True,
):
    consent = helpers._record_consent(SessionFactory, actor_id=actor_id)
    subject = helpers.privacy_subject_hmac(actor_id, 1, helpers.PRIVACY_SECRET)
    admission = helpers.RawCollectionAdmission(
        actor_id=actor_id, account_generation=1, privacy_subject_hmac=subject,
        purpose="GENERAL_RAW", consent_receipt_sha256=consent.event.receipt_sha256,
    )
    content_by_id: dict[str, bytes] = {}
    objects = []
    for kind in ("DETECTION", "PERFORMANCE"):
        object_id = str(uuid.uuid4())
        content = ('{"kind":"' + kind + '","fixture":true}').encode("ascii")
        digest = hashlib.sha256(content).hexdigest()
        content_by_id[object_id] = content
        objects.append({
            "object_id": object_id, "kind": kind, "content_type": "application/json",
            "size_bytes": len(content), "sha256": digest,
            "chunks": [{"index": 0, "size_bytes": len(content), "sha256": digest}],
        })
    value = {
        "schema_version": "walksafe.raw-collection-manifest.v1",
        "collection_id": str(uuid.uuid4()), "walk_id": str(uuid.uuid4()),
        "segment_id": str(uuid.uuid4()), "purpose": "GENERAL_RAW",
        "captured_started_at": "2026-09-06T00:00:00Z",
        "captured_ended_at": "2026-09-06T00:00:05Z",
        "consent_receipt_sha256": consent.event.receipt_sha256,
        "object_count": 2, "chunk_count": 2,
        "total_bytes": sum(map(len, content_by_id.values())),
        "objects": sorted(objects, key=lambda item: item["object_id"]),
    }
    value["manifest_sha256"] = helpers.raw_collection_manifest_sha256(value)
    manifest = helpers.RawCollectionManifestV1.model_validate(value)
    collection_id = uuid.UUID(manifest.collection_id)
    storage = helpers.RawCollectionStorage(
        raw_object_dir=raw_root, privacy_hmac_secret=helpers.PRIVACY_SECRET,
        key_manager=_StaticKeyManager(),  # type: ignore[arg-type]
        capacity_state=SimpleNamespace(
            current=lambda: SimpleNamespace(level=helpers.CapacityLevel.NORMAL)
        ),
    )
    with SessionFactory() as db:
        storage.put_manifest(db, admission, manifest)
    for item in manifest.objects[:received_chunks]:
        content = content_by_id[item.object_id]
        with SessionFactory() as db:
            storage.put_chunk(
                db, admission, collection_id=manifest.collection_id,
                object_id=item.object_id, index=0, walk_id=manifest.walk_id,
                manifest_sha256=manifest.manifest_sha256, content=content,
                content_sha256=hashlib.sha256(content).hexdigest(),
            )
    commit = helpers.RawCollectionCommitV1.model_validate({
        "schema_version": "walksafe.raw-collection-commit.v1",
        "collection_id": manifest.collection_id,
        "manifest_sha256": manifest.manifest_sha256,
        "object_count": 2, "chunk_count": 2, "total_bytes": manifest.total_bytes,
    })
    receipt = None
    if commit_collection:
        with SessionFactory() as db:
            receipt = storage.commit(db, admission, commit, walk_id=manifest.walk_id)
        assert receipt.object_count == receipt.chunk_count == 2
        assert receipt.total_bytes == sum(map(len, content_by_id.values()))
    else:
        with SessionFactory() as db:
            status = storage.get_status(
                db, actor_id=actor_id, account_generation=1,
                collection_id=manifest.collection_id, purpose=manifest.purpose,
                walk_id=manifest.walk_id, manifest_sha256=manifest.manifest_sha256,
            )
        assert status.state == ("MANIFEST_ACCEPTED" if received_chunks == 0 else "RECEIVING")
        assert status.received_chunk_count == received_chunks
    with SessionFactory() as db:
        collection = db.get(helpers.RawCollection, collection_id)
        assert collection is not None
        assert collection.privacy_subject_hmac == subject
        assert collection.account_generation == 1
        assert collection.receipt_sha256 == (receipt.receipt_sha256 if receipt else None)
        chunks = db.scalars(select(helpers.RawCollectionChunk).where(
            helpers.RawCollectionChunk.collection_id == collection_id,
        )).all()
        assert len(chunks) == 2
        paths = [raw_root / chunk.storage_name for chunk in chunks if chunk.storage_name is not None]
    assert len(paths) == received_chunks
    assert all(path.is_file() for path in paths)
    return collection_id, paths, receipt


@pytest.mark.parametrize("phase", [None, "before_database_commit", "after_database_commit_before_ack"])
def test_account_deletion_removes_committed_detection_and_performance_objects(
    tmp_path: Path, phase: str | None,
) -> None:
    engine, SessionFactory = helpers._session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"worker.multi.{suffix}"
    request_id = f"delete_multi_{suffix}"
    raw_root, upload_root = tmp_path / "raw", tmp_path / "uploads"
    for directory in (raw_root, upload_root):
        directory.mkdir(mode=0o700)
    try:
        collection_id, paths, receipt = _stored_collection(SessionFactory, raw_root, actor_id)
        other_id, other_paths, other_receipt = _stored_collection(
            SessionFactory, raw_root, f"worker.other.{suffix}",
        )
        helpers._accept(SessionFactory, actor_id=actor_id, request_id=request_id)
        worker_root = tmp_path / "worker"
        helpers.prepare_account_deletion_worker_roots(worker_root.resolve())

        class SimulatedCrash(Exception):
            pass

        def fault(point: str) -> None:
            if point == phase:
                raise SimulatedCrash

        with helpers._worker_session_factory(engine, suffix) as WorkerSessionFactory:
            arguments = dict(
                request_id=request_id, upload_dir=upload_root.resolve(),
                raw_object_dir=raw_root.resolve(), root=worker_root.resolve(),
            )
            if phase is None:
                helpers.process_account_deletion_request(WorkerSessionFactory, **arguments)
            else:
                with pytest.raises(SimulatedCrash):
                    helpers.process_account_deletion_request(WorkerSessionFactory, **arguments, fault=fault)
                if phase == "before_database_commit":
                    assert all(path.is_file() for path in paths)
                    with SessionFactory() as db:
                        assert db.get(helpers.RawCollection, collection_id) is not None
                    helpers.process_account_deletion_request(WorkerSessionFactory, **arguments)
                else:
                    with SessionFactory() as db:
                        assert db.get(helpers.RawCollection, collection_id) is None
                    [journal_path] = list((worker_root / "journals").glob("*.json"))
                    journal = json.loads(journal_path.read_text())
                    assert journal["raw_collection_count"] == 1
                    assert len(journal["raw_collections"]) == len(journal["raw_objects"]) == 2
                    assert {row["receipt_sha256"] for row in journal["raw_collections"]} == {receipt.receipt_sha256}
                    quarantined = [Path(row["quarantine_path"]) for row in journal["raw_objects"]]
                    for corruption in ("missing", "duplicate"):
                        invalid = json.loads(json.dumps(journal))
                        if corruption == "missing":
                            invalid["raw_objects"].pop()
                        else:
                            invalid["raw_collections"].append(dict(invalid["raw_collections"][0]))
                        helpers.write_account_deletion_worker_journal(journal_path, invalid)
                        with pytest.raises(AccountDeletionWorkerError, match="raw journal inventory is invalid"):
                            helpers.reconcile_account_deletion_journal(WorkerSessionFactory, worker_root.resolve(), journal_path)
                        assert all(path.is_file() for path in quarantined)
                    helpers.write_account_deletion_worker_journal(journal_path, journal)
                    helpers.reconcile_account_deletion_journal(WorkerSessionFactory, worker_root.resolve(), journal_path)
        assert all(not path.exists() for path in paths)
        assert all(path.is_file() for path in other_paths)
        with SessionFactory() as db:
            assert db.get(helpers.RawCollection, collection_id) is None
            assert db.get(helpers.AccountDeletionRequest, request_id) is not None
            other = db.get(helpers.RawCollection, other_id)
            assert other is not None and other.receipt_sha256 == other_receipt.receipt_sha256
        manifest = json.loads((worker_root / "manifests" / f"{request_id}.json").read_text())
        assert manifest["raw_collection_count"] == 1
        assert len(manifest["raw_collections"]) == 2
        assert {row["collection_id"] for row in manifest["raw_collections"]} == {str(collection_id)}
    finally:
        engine.dispose()


def test_account_deletion_rejects_missing_committed_chunk_before_mutation(tmp_path: Path) -> None:
    engine, SessionFactory = helpers._session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"worker.missing.{suffix}"
    request_id = f"delete_missing_{suffix}"
    raw_root, upload_root = tmp_path / "raw", tmp_path / "uploads"
    for directory in (raw_root, upload_root):
        directory.mkdir(mode=0o700)
    try:
        collection_id, paths, _receipt = _stored_collection(SessionFactory, raw_root, actor_id)
        with SessionFactory.begin() as db:
            chunk = db.scalar(select(helpers.RawCollectionChunk).where(
                helpers.RawCollectionChunk.collection_id == collection_id,
            ).order_by(helpers.RawCollectionChunk.object_id))
            assert chunk is not None
            db.execute(delete(helpers.RawCollectionChunk).where(
                helpers.RawCollectionChunk.collection_id == collection_id,
                helpers.RawCollectionChunk.object_id == chunk.object_id,
            ))
        helpers._accept(SessionFactory, actor_id=actor_id, request_id=request_id)
        worker_root = tmp_path / "worker"
        helpers.prepare_account_deletion_worker_roots(worker_root.resolve())
        with helpers._worker_session_factory(engine, suffix) as WorkerSessionFactory:
            with pytest.raises(AccountDeletionWorkerError, match="raw collection inventory is incomplete"):
                helpers.process_account_deletion_request(
                    WorkerSessionFactory, request_id=request_id, upload_dir=upload_root.resolve(),
                    raw_object_dir=raw_root.resolve(), root=worker_root.resolve(),
                )
        assert all(path.is_file() for path in paths)
        with SessionFactory() as db:
            assert db.get(helpers.RawCollection, collection_id) is not None
        assert list((worker_root / "journals").glob("*.json")) == []
    finally:
        engine.dispose()


@pytest.mark.parametrize("received_chunks", [0, 1], ids=["manifest-only", "one-of-two-chunks"])
def test_account_deletion_retries_normal_partial_inventory_without_touching_other_actor(
    tmp_path: Path, received_chunks: int,
) -> None:
    engine, SessionFactory = helpers._session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"worker.partial.{suffix}"
    request_id = f"delete_partial_{suffix}"
    raw_root, upload_root = tmp_path / "raw", tmp_path / "uploads"
    for directory in (raw_root, upload_root):
        directory.mkdir(mode=0o700)
    try:
        collection_id, paths, receipt = _stored_collection(
            SessionFactory, raw_root, actor_id,
            received_chunks=received_chunks, commit_collection=False,
        )
        assert receipt is None
        other_id, other_paths, other_receipt = _stored_collection(
            SessionFactory, raw_root, f"worker.preserved.{suffix}",
        )
        other_digests = {
            path: hashlib.sha256(path.read_bytes()).hexdigest() for path in other_paths
        }

        def assert_other_actor_unchanged() -> None:
            assert {
                path: hashlib.sha256(path.read_bytes()).hexdigest() for path in other_paths
            } == other_digests
            with SessionFactory() as db:
                other = db.get(helpers.RawCollection, other_id)
                assert other is not None
                assert other.receipt_sha256 == other_receipt.receipt_sha256

        assert_other_actor_unchanged()
        helpers._accept(SessionFactory, actor_id=actor_id, request_id=request_id)
        worker_root = tmp_path / "worker"
        helpers.prepare_account_deletion_worker_roots(worker_root.resolve())

        class SimulatedCrash(Exception):
            pass

        def fault(point: str) -> None:
            if point == "before_database_commit":
                raise SimulatedCrash

        with helpers._worker_session_factory(engine, suffix) as WorkerSessionFactory:
            arguments = dict(
                request_id=request_id, upload_dir=upload_root.resolve(),
                raw_object_dir=raw_root.resolve(), root=worker_root.resolve(),
            )
            with pytest.raises(SimulatedCrash):
                helpers.process_account_deletion_request(
                    WorkerSessionFactory, **arguments, fault=fault,
                )
            assert all(path.is_file() for path in paths)
            with SessionFactory() as db:
                collection = db.get(helpers.RawCollection, collection_id)
                assert collection is not None and collection.receipt_sha256 is None
                chunks = db.scalars(select(helpers.RawCollectionChunk).where(
                    helpers.RawCollectionChunk.collection_id == collection_id,
                )).all()
                assert len(chunks) == 2
                assert sum(chunk.storage_name is not None for chunk in chunks) == received_chunks
            assert list((worker_root / "journals").glob("*.json")) == []
            assert_other_actor_unchanged()
            helpers.process_account_deletion_request(WorkerSessionFactory, **arguments)
        assert all(not path.exists() for path in paths)
        with SessionFactory() as db:
            assert db.get(helpers.RawCollection, collection_id) is None
            assert db.get(helpers.AccountDeletionRequest, request_id) is not None
        assert_other_actor_unchanged()
        manifest = json.loads((worker_root / "manifests" / f"{request_id}.json").read_text())
        assert manifest["raw_collection_count"] == 1
        assert len(manifest["raw_collections"]) == 2
        assert {row["collection_id"] for row in manifest["raw_collections"]} == {str(collection_id)}
        assert all(row["receipt_sha256"] is None for row in manifest["raw_collections"])
        assert all(row["committed_at"] is None for row in manifest["raw_collections"])
        assert sum(row["storage_name"] is not None for row in manifest["raw_collections"]) == received_chunks
    finally:
        engine.dispose()
