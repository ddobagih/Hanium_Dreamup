"""Offline ordering checks for raw chunk writes and keyring generations."""

import hashlib
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest

from backend.app.services import raw_collection_storage as raw_storage
from backend.app.services.raw_collection_ingest import RawCollectionStorageError
from backend.app.services.report_image_keys import ReportImageKeyError


class _KeyManager:
    def __init__(self, events: list[str], *, stale: bool) -> None:
        self._events = events
        self._stale = stale
        self.synchronized = False

    def synchronize(self, _db: object) -> None:
        self._events.append("sync")
        if self._stale:
            raise ReportImageKeyError("stored keyring generation is newer")
        self.synchronized = True

    def encryption_slot(self) -> SimpleNamespace:
        assert self.synchronized
        self._events.append("slot")
        return SimpleNamespace(key_id="active-key", material=b"key material")


def _chunk_write_case(
    monkeypatch: pytest.MonkeyPatch,
    root: Path,
    *,
    stale: bool,
) -> tuple[SimpleNamespace, SimpleNamespace, SimpleNamespace, dict[str, object], list[str]]:
    events: list[str] = []
    content = b"raw chunk"
    digest = hashlib.sha256(content).hexdigest()
    collection_id = uuid.uuid4()
    object_id = uuid.uuid4()
    walk_id = uuid.uuid4()
    collection = SimpleNamespace(
        collection_id=collection_id,
        purpose="training",
        walk_id=walk_id,
        manifest_sha256="manifest",
        consent_receipt_sha256="receipt",
        chunk_count=1,
        state="MANIFEST_ACCEPTED",
    )
    item = SimpleNamespace(object_id=object_id)
    chunk = SimpleNamespace(
        chunk_index=0,
        declared_size_bytes=len(content),
        declared_sha256=digest,
        storage_name=None,
    )
    inventory = [(item, [chunk])]
    manager = _KeyManager(events, stale=stale)
    storage = SimpleNamespace(
        _root=root,
        _key_manager=manager,
        _rows_for_collection=lambda _db, _id, *, for_update: (collection, inventory),
        _owned=lambda _collection, **_kwargs: True,
        _assert_digest_not_rejected=lambda _collection: None,
        _verify_completed_object_with_candidate=lambda *_args: True,
        _binding=lambda *_args: object(),
        _keyring_identity=lambda: (1, "keyring-manifest"),
        _persisted_chunk_metadata=lambda *_args: object(),
        _remember_verified_chunk_file=lambda *_args, **_kwargs: None,
    )
    db = SimpleNamespace(commit=lambda: events.append("commit"))
    admission = SimpleNamespace(
        privacy_subject_hmac="subject",
        account_generation=1,
        purpose="training",
        consent_receipt_sha256="receipt",
    )
    monkeypatch.setattr(
        raw_storage,
        "lock_raw_storage_write_transaction",
        lambda _db: events.append("raw_lock"),
    )

    def encrypt(_content: bytes, *, binding: object, key_id: str, master_key: bytes) -> SimpleNamespace:
        assert manager.synchronized
        assert key_id == "active-key" and master_key == b"key material"
        return SimpleNamespace(key_id=key_id, nonce=b"0" * 12, envelope=b"encrypted")

    def stage(_root: Path, *, storage_name: str, envelope: bytes, **_kwargs: object) -> SimpleNamespace:
        events.append("stage")
        return SimpleNamespace(
            metadata=SimpleNamespace(
                storage_name=storage_name,
                envelope_sha256=hashlib.sha256(envelope).hexdigest(),
                envelope_size=len(envelope),
            )
        )

    def persist(_root: Path, storage_name: str, envelope: bytes) -> SimpleNamespace:
        events.append("persist")
        return SimpleNamespace(
            storage_name=storage_name,
            envelope_sha256=hashlib.sha256(envelope).hexdigest(),
            envelope_size=len(envelope),
            fingerprint=(1, 2, 3, 4, 5),
        )

    monkeypatch.setattr(raw_storage, "encrypt_raw_collection_chunk", encrypt)
    monkeypatch.setattr(raw_storage, "stage_raw_chunk_write", stage)
    monkeypatch.setattr(raw_storage, "persist_raw_chunk_envelope", persist)
    monkeypatch.setattr(
        raw_storage,
        "complete_raw_chunk_write",
        lambda _pending: events.append("complete"),
    )
    request = {
        "collection_id": str(collection_id),
        "object_id": str(object_id),
        "index": 0,
        "walk_id": str(walk_id),
        "manifest_sha256": "manifest",
        "content": content,
        "content_sha256": digest,
    }
    return storage, db, admission, request, events


def test_raw_chunk_synchronizes_keyring_before_slot_and_holds_transaction_to_commit(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    storage, db, admission, request, events = _chunk_write_case(
        monkeypatch, tmp_path, stale=False
    )

    ack, created = raw_storage.RawCollectionStorage.put_chunk(
        storage, db, admission, **request
    )

    assert created is True
    assert ack.state == "READY_TO_COMMIT"
    assert events == [
        "raw_lock", "sync", "slot", "stage", "persist", "commit", "complete"
    ]


def test_raw_chunk_stale_keyring_fails_before_file_or_database_write(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    storage, db, admission, request, events = _chunk_write_case(
        monkeypatch, tmp_path, stale=True
    )

    with pytest.raises(RawCollectionStorageError) as rejected:
        raw_storage.RawCollectionStorage.put_chunk(storage, db, admission, **request)

    assert rejected.value.code == "raw_chunk_encryption_unavailable"
    assert rejected.value.status_code == 503
    assert events == ["raw_lock", "sync"]
    assert not list(tmp_path.iterdir())
