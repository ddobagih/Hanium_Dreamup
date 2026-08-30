from __future__ import annotations

import hashlib
import json
import struct
import uuid

import pytest

from backend.app.services.raw_collection_crypto import (
    AAD_DOMAIN,
    ENVELOPE_MAGIC,
    RawCollectionChunkBinding,
    RawCollectionChunkAuthenticationError,
    RawCollectionChunkFormatError,
    decrypt_raw_collection_chunk,
    derive_raw_collection_chunk_key,
    encrypt_raw_collection_chunk,
    parse_raw_collection_chunk_envelope,
)
from backend.app.services.report_image_crypto import ENVELOPE_MAGIC as REPORT_MAGIC


MASTER_KEY = bytes(range(32))
NONCE = bytes(range(12))
COLLECTION_ID = uuid.UUID("123e4567-e89b-42d3-a456-426614174000")
OBJECT_ID = uuid.UUID("123e4567-e89b-42d3-a456-426614174001")
WALK_ID = uuid.UUID("123e4567-e89b-42d3-a456-426614174002")
PLAINTEXT = b"walksafe raw crypto golden\n"


def _binding(**overrides: object) -> RawCollectionChunkBinding:
    values: dict[str, object] = {
        "privacy_subject_hmac": "a" * 64,
        "account_generation": 7,
        "collection_id": COLLECTION_ID,
        "object_id": OBJECT_ID,
        "chunk_index": 0,
        "manifest_sha256": "b" * 64,
        "purpose": "GENERAL_RAW",
        "walk_id": WALK_ID,
        "content_type": "application/octet-stream",
        "plaintext_length": len(PLAINTEXT),
        "plaintext_sha256": hashlib.sha256(PLAINTEXT).hexdigest(),
    }
    values.update(overrides)
    return RawCollectionChunkBinding(**values)  # type: ignore[arg-type]


def _replace_envelope_header(envelope: bytes, header: bytes) -> bytes:
    previous_length = struct.unpack(">I", envelope[8:12])[0]
    ciphertext_and_tag = envelope[12 + previous_length :]
    return (
        ENVELOPE_MAGIC
        + struct.pack(">I", len(header))
        + header
        + ciphertext_and_tag
    )


def test_raw_chunk_hkdf_has_a_literal_domain_separated_golden() -> None:
    derived = derive_raw_collection_chunk_key(MASTER_KEY)

    assert AAD_DOMAIN == b"walksafe/raw-collection-chunk/aad/v1\0"
    assert derived.hex() == (
        "20a61293461956a7352bcfeacd5b7169b99cc0b9265007e9b56d8402cc9dcd03"
    )
    assert derived != MASTER_KEY


def test_raw_chunk_envelope_has_literal_golden_and_round_trips() -> None:
    binding = _binding()
    encrypted = encrypt_raw_collection_chunk(
        PLAINTEXT,
        binding=binding,
        key_id="raw-golden-key-v1",
        master_key=MASTER_KEY,
        nonce=NONCE,
    )

    assert ENVELOPE_MAGIC == b"WSRC\x00\x00\x00\x01"
    assert ENVELOPE_MAGIC != REPORT_MAGIC
    assert encrypted.envelope_sha256 == (
        "72cdaec7763c7ef59a96a10a47f58d09850a012534582e02794158b9e06af89b"
    )
    assert hashlib.sha256(encrypted.header_bytes).hexdigest() == (
        "08ed7c074b2eeef54a267d07a89297d7dd5b028228c91500ae527d6c7b859db4"
    )

    parsed = parse_raw_collection_chunk_envelope(
        encrypted.envelope,
        expected_binding=binding,
    )
    assert parsed.key_id == "raw-golden-key-v1"
    assert parsed.nonce == NONCE
    assert parsed.binding == binding
    assert decrypt_raw_collection_chunk(
        encrypted.envelope,
        expected_binding=binding,
        master_key=MASTER_KEY,
    ).content == PLAINTEXT


def test_raw_chunk_parser_rejects_plaintext_and_cross_domain_envelopes() -> None:
    binding = _binding()
    encrypted = encrypt_raw_collection_chunk(
        PLAINTEXT,
        binding=binding,
        key_id="raw-golden-key-v1",
        master_key=MASTER_KEY,
        nonce=NONCE,
    )

    with pytest.raises(RawCollectionChunkFormatError):
        parse_raw_collection_chunk_envelope(PLAINTEXT)
    with pytest.raises(RawCollectionChunkFormatError):
        parse_raw_collection_chunk_envelope(REPORT_MAGIC + encrypted.envelope[8:])
    with pytest.raises(RawCollectionChunkFormatError, match="another binding"):
        parse_raw_collection_chunk_envelope(
            encrypted.envelope,
            expected_binding=_binding(account_generation=8),
        )


def test_raw_chunk_header_and_ciphertext_tampering_never_returns_plaintext() -> None:
    binding = _binding()
    encrypted = encrypt_raw_collection_chunk(
        PLAINTEXT,
        binding=binding,
        key_id="raw-golden-key-v1",
        master_key=MASTER_KEY,
        nonce=NONCE,
    )
    header_length = struct.unpack(">I", encrypted.envelope[8:12])[0]
    header_start = 12
    header_end = header_start + header_length

    noncanonical_header = bytearray(encrypted.envelope)
    noncanonical_header[header_start:header_end] = encrypted.header_bytes[::-1]
    with pytest.raises(RawCollectionChunkFormatError):
        parse_raw_collection_chunk_envelope(bytes(noncanonical_header))

    boolean_version = json.loads(encrypted.header_bytes)
    boolean_version["aad_version"] = True
    boolean_header = json.dumps(
        boolean_version,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    boolean_envelope = (
        ENVELOPE_MAGIC
        + struct.pack(">I", len(boolean_header))
        + boolean_header
        + encrypted.envelope[header_end:]
    )
    with pytest.raises(RawCollectionChunkFormatError, match="version"):
        parse_raw_collection_chunk_envelope(boolean_envelope)

    tampered_ciphertext = bytearray(encrypted.envelope)
    tampered_ciphertext[-1] ^= 1
    with pytest.raises(RawCollectionChunkAuthenticationError):
        decrypt_raw_collection_chunk(
            bytes(tampered_ciphertext),
            expected_binding=binding,
            master_key=MASTER_KEY,
        )

    with pytest.raises(RawCollectionChunkAuthenticationError):
        decrypt_raw_collection_chunk(
            encrypted.envelope,
            expected_binding=binding,
            master_key=bytes(reversed(MASTER_KEY)),
        )


def test_raw_chunk_parser_normalizes_nonfinite_and_surrogate_headers() -> None:
    encrypted = encrypt_raw_collection_chunk(
        PLAINTEXT,
        binding=_binding(),
        key_id="raw-golden-key-v1",
        master_key=MASTER_KEY,
        nonce=NONCE,
    )
    header = json.loads(encrypted.header_bytes)

    nonfinite_bytes = json.dumps(
        {**header, "account_generation": float("nan")},
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    surrogate_bytes = json.dumps(
        {**header, "content_type": "application/\ud800"},
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")

    for invalid_header in (nonfinite_bytes, surrogate_bytes):
        with pytest.raises(RawCollectionChunkFormatError):
            parse_raw_collection_chunk_envelope(
                _replace_envelope_header(encrypted.envelope, invalid_header)
            )
