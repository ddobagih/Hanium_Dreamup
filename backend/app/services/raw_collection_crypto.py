"""Authenticated raw-collection chunk envelope with report-key separation."""

from __future__ import annotations

import base64
import binascii
from dataclasses import dataclass
import hashlib
import hmac
import json
import os
import re
import struct
from typing import Any
import uuid

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


ENVELOPE_MAGIC = b"WSRC\x00\x00\x00\x01"
ENVELOPE_SCHEMA = "walksafe.raw-collection-chunk.envelope.v1"
ENVELOPE_ALGORITHM = "A256GCM"
ENVELOPE_VERSION = 1
AAD_VERSION = 1
KEY_DERIVATION_DOMAIN = b"walksafe/raw-collection-chunk-key/v1\0"
AAD_DOMAIN = b"walksafe/raw-collection-chunk/aad/v1\0"
NONCE_BYTES = 12
TAG_BYTES = 16
MAX_HEADER_BYTES = 4096
MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
MAX_ENVELOPE_BYTES = (
    MAX_PLAINTEXT_BYTES
    + MAX_HEADER_BYTES
    + len(ENVELOPE_MAGIC)
    + 4
    + TAG_BYTES
)
_KEY_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_CONTENT_TYPE_PATTERN = re.compile(
    r"^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/"
    r"[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$"
)
_HEADER_KEYS = {
    "aad_version",
    "account_generation",
    "alg",
    "chunk_index",
    "collection_id",
    "content_type",
    "key_id",
    "manifest_sha256",
    "nonce_b64url",
    "object_id",
    "plaintext_length",
    "plaintext_sha256",
    "privacy_subject_hmac",
    "purpose",
    "schema",
    "walk_id",
}


class RawCollectionChunkCryptoError(RuntimeError):
    """Base class for raw chunk envelope failures."""


class RawCollectionChunkFormatError(RawCollectionChunkCryptoError):
    """The value is not one exact supported WSRC envelope."""


class RawCollectionChunkAuthenticationError(RawCollectionChunkCryptoError):
    """The ciphertext or its bound metadata did not authenticate."""


def _canonical_uuid(value: uuid.UUID | str, name: str) -> uuid.UUID:
    try:
        parsed = value if isinstance(value, uuid.UUID) else uuid.UUID(value)
    except (AttributeError, TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be a canonical UUID") from exc
    if not isinstance(value, (str, uuid.UUID)) or (
        isinstance(value, str) and str(parsed) != value
    ):
        raise ValueError(f"{name} must be a canonical UUID")
    return parsed


@dataclass(frozen=True, slots=True)
class RawCollectionChunkBinding:
    privacy_subject_hmac: str
    account_generation: int
    collection_id: uuid.UUID
    object_id: uuid.UUID
    chunk_index: int
    manifest_sha256: str
    purpose: str
    walk_id: uuid.UUID
    content_type: str
    plaintext_length: int
    plaintext_sha256: str

    def __post_init__(self) -> None:
        object.__setattr__(
            self,
            "collection_id",
            _canonical_uuid(self.collection_id, "collection_id"),
        )
        object.__setattr__(
            self,
            "object_id",
            _canonical_uuid(self.object_id, "object_id"),
        )
        object.__setattr__(
            self,
            "walk_id",
            _canonical_uuid(self.walk_id, "walk_id"),
        )
        if (
            type(self.privacy_subject_hmac) is not str
            or _SHA256_PATTERN.fullmatch(self.privacy_subject_hmac) is None
        ):
            raise ValueError("privacy_subject_hmac must be lowercase SHA-256")
        if type(self.account_generation) is not int or self.account_generation < 1:
            raise ValueError("account_generation must be positive")
        if type(self.chunk_index) is not int or not 0 <= self.chunk_index < 2_048:
            raise ValueError("chunk_index is outside the raw contract")
        if (
            type(self.manifest_sha256) is not str
            or _SHA256_PATTERN.fullmatch(self.manifest_sha256) is None
        ):
            raise ValueError("manifest_sha256 must be lowercase SHA-256")
        if self.purpose not in {"GENERAL_RAW", "AUTO_REPORT"}:
            raise ValueError("purpose is outside the raw contract")
        if (
            type(self.content_type) is not str
            or _CONTENT_TYPE_PATTERN.fullmatch(self.content_type) is None
        ):
            raise ValueError("content_type is outside the raw contract")
        if (
            type(self.plaintext_length) is not int
            or not 0 < self.plaintext_length <= MAX_PLAINTEXT_BYTES
        ):
            raise ValueError("plaintext_length is outside the raw contract")
        if (
            type(self.plaintext_sha256) is not str
            or _SHA256_PATTERN.fullmatch(self.plaintext_sha256) is None
        ):
            raise ValueError("plaintext_sha256 must be lowercase SHA-256")


@dataclass(frozen=True, slots=True)
class ParsedRawCollectionChunkEnvelope:
    binding: RawCollectionChunkBinding
    key_id: str
    nonce: bytes
    header_bytes: bytes
    ciphertext_and_tag: bytes


@dataclass(frozen=True, slots=True)
class EncryptedRawCollectionChunk:
    binding: RawCollectionChunkBinding
    key_id: str
    nonce: bytes
    header_bytes: bytes
    envelope: bytes
    envelope_sha256: str


@dataclass(frozen=True, slots=True)
class DecryptedRawCollectionChunk:
    binding: RawCollectionChunkBinding
    content: bytes
    key_id: str
    plaintext_sha256: str


def _canonical_json(value: dict[str, Any]) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def _encode_base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _decode_nonce(value: object) -> bytes:
    if not isinstance(value, str) or "=" in value:
        raise RawCollectionChunkFormatError(
            "raw chunk envelope nonce is not canonical base64url"
        )
    try:
        decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (binascii.Error, ValueError) as exc:
        raise RawCollectionChunkFormatError(
            "raw chunk envelope nonce is invalid"
        ) from exc
    if len(decoded) != NONCE_BYTES or _encode_base64url(decoded) != value:
        raise RawCollectionChunkFormatError(
            "raw chunk envelope nonce is not canonical base64url"
        )
    return decoded


def _reject_duplicate_object_pairs(
    pairs: list[tuple[str, Any]],
) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _reject_nonfinite_json_constant(value: str) -> None:
    raise ValueError(f"non-finite JSON constant is not allowed: {value}")


def derive_raw_collection_chunk_key(master_key: bytes) -> bytes:
    if not isinstance(master_key, bytes) or len(master_key) != 32:
        raise ValueError("raw chunk master keys must contain exactly 32 bytes")
    return HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=None,
        info=KEY_DERIVATION_DOMAIN,
    ).derive(master_key)


def _header(
    binding: RawCollectionChunkBinding,
    *,
    key_id: str,
    nonce: bytes,
) -> dict[str, object]:
    return {
        "aad_version": AAD_VERSION,
        "account_generation": binding.account_generation,
        "alg": ENVELOPE_ALGORITHM,
        "chunk_index": binding.chunk_index,
        "collection_id": str(binding.collection_id),
        "content_type": binding.content_type,
        "key_id": key_id,
        "manifest_sha256": binding.manifest_sha256,
        "nonce_b64url": _encode_base64url(nonce),
        "object_id": str(binding.object_id),
        "plaintext_length": binding.plaintext_length,
        "plaintext_sha256": binding.plaintext_sha256,
        "privacy_subject_hmac": binding.privacy_subject_hmac,
        "purpose": binding.purpose,
        "schema": ENVELOPE_SCHEMA,
        "walk_id": str(binding.walk_id),
    }


def _aad(header_bytes: bytes) -> bytes:
    return (
        AAD_DOMAIN
        + ENVELOPE_MAGIC
        + struct.pack(">I", len(header_bytes))
        + header_bytes
    )


def encrypt_raw_collection_chunk(
    plaintext: bytes,
    *,
    binding: RawCollectionChunkBinding,
    key_id: str,
    master_key: bytes,
    nonce: bytes | None = None,
) -> EncryptedRawCollectionChunk:
    if not isinstance(plaintext, bytes) or not 0 < len(plaintext) <= MAX_PLAINTEXT_BYTES:
        raise ValueError("raw chunk plaintext size is invalid")
    observed_sha256 = hashlib.sha256(plaintext).hexdigest()
    if (
        len(plaintext) != binding.plaintext_length
        or not hmac.compare_digest(observed_sha256, binding.plaintext_sha256)
    ):
        raise ValueError("raw chunk plaintext differs from its declared binding")
    if not isinstance(key_id, str) or _KEY_ID_PATTERN.fullmatch(key_id) is None:
        raise ValueError("raw chunk key id is invalid")
    nonce = os.urandom(NONCE_BYTES) if nonce is None else nonce
    if not isinstance(nonce, bytes) or len(nonce) != NONCE_BYTES:
        raise ValueError("raw chunk nonce must contain exactly 12 bytes")
    header_bytes = _canonical_json(_header(binding, key_id=key_id, nonce=nonce))
    if len(header_bytes) > MAX_HEADER_BYTES:
        raise ValueError("raw chunk envelope header is too large")
    ciphertext_and_tag = AESGCM(
        derive_raw_collection_chunk_key(master_key)
    ).encrypt(nonce, plaintext, _aad(header_bytes))
    envelope = (
        ENVELOPE_MAGIC
        + struct.pack(">I", len(header_bytes))
        + header_bytes
        + ciphertext_and_tag
    )
    return EncryptedRawCollectionChunk(
        binding=binding,
        key_id=key_id,
        nonce=nonce,
        header_bytes=header_bytes,
        envelope=envelope,
        envelope_sha256=hashlib.sha256(envelope).hexdigest(),
    )


def _binding_from_header(header: dict[str, Any]) -> RawCollectionChunkBinding:
    try:
        return RawCollectionChunkBinding(
            privacy_subject_hmac=header["privacy_subject_hmac"],
            account_generation=header["account_generation"],
            collection_id=header["collection_id"],
            object_id=header["object_id"],
            chunk_index=header["chunk_index"],
            manifest_sha256=header["manifest_sha256"],
            purpose=header["purpose"],
            walk_id=header["walk_id"],
            content_type=header["content_type"],
            plaintext_length=header["plaintext_length"],
            plaintext_sha256=header["plaintext_sha256"],
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise RawCollectionChunkFormatError(
            "raw chunk envelope binding is invalid"
        ) from exc


def parse_raw_collection_chunk_envelope(
    envelope: bytes,
    *,
    expected_binding: RawCollectionChunkBinding | None = None,
) -> ParsedRawCollectionChunkEnvelope:
    minimum_size = len(ENVELOPE_MAGIC) + 4 + 2 + TAG_BYTES
    if (
        not isinstance(envelope, bytes)
        or not minimum_size <= len(envelope) <= MAX_ENVELOPE_BYTES
    ):
        raise RawCollectionChunkFormatError("raw chunk envelope size is invalid")
    if envelope[: len(ENVELOPE_MAGIC)] != ENVELOPE_MAGIC:
        raise RawCollectionChunkFormatError(
            "raw chunk envelope version is unsupported"
        )
    header_length = struct.unpack(
        ">I",
        envelope[len(ENVELOPE_MAGIC) : len(ENVELOPE_MAGIC) + 4],
    )[0]
    if not 0 < header_length <= MAX_HEADER_BYTES:
        raise RawCollectionChunkFormatError(
            "raw chunk envelope header size is invalid"
        )
    header_start = len(ENVELOPE_MAGIC) + 4
    header_end = header_start + header_length
    if header_end + TAG_BYTES > len(envelope):
        raise RawCollectionChunkFormatError("raw chunk envelope is truncated")
    header_bytes = envelope[header_start:header_end]
    try:
        header = json.loads(
            header_bytes.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_object_pairs,
            parse_constant=_reject_nonfinite_json_constant,
        )
    except (UnicodeError, ValueError, TypeError, json.JSONDecodeError) as exc:
        raise RawCollectionChunkFormatError(
            "raw chunk envelope header is invalid"
        ) from exc
    if not isinstance(header, dict):
        raise RawCollectionChunkFormatError(
            "raw chunk envelope header is not canonical"
        )
    try:
        canonical_header = _canonical_json(header)
    except (UnicodeError, ValueError, TypeError) as exc:
        raise RawCollectionChunkFormatError(
            "raw chunk envelope header is invalid"
        ) from exc
    if set(header) != _HEADER_KEYS or canonical_header != header_bytes:
        raise RawCollectionChunkFormatError(
            "raw chunk envelope header is not canonical"
        )
    if (
        header.get("schema") != ENVELOPE_SCHEMA
        or header.get("alg") != ENVELOPE_ALGORITHM
        or type(header.get("aad_version")) is not int
        or header.get("aad_version") != AAD_VERSION
    ):
        raise RawCollectionChunkFormatError(
            "raw chunk envelope algorithm or version is unsupported"
        )
    key_id = header.get("key_id")
    if not isinstance(key_id, str) or _KEY_ID_PATTERN.fullmatch(key_id) is None:
        raise RawCollectionChunkFormatError(
            "raw chunk envelope key id is invalid"
        )
    nonce = _decode_nonce(header.get("nonce_b64url"))
    binding = _binding_from_header(header)
    if expected_binding is not None and binding != expected_binding:
        raise RawCollectionChunkFormatError(
            "raw chunk envelope is bound to another binding"
        )
    ciphertext_and_tag = envelope[header_end:]
    if len(ciphertext_and_tag) != binding.plaintext_length + TAG_BYTES:
        raise RawCollectionChunkFormatError(
            "raw chunk envelope ciphertext size is invalid"
        )
    return ParsedRawCollectionChunkEnvelope(
        binding=binding,
        key_id=key_id,
        nonce=nonce,
        header_bytes=header_bytes,
        ciphertext_and_tag=ciphertext_and_tag,
    )


def decrypt_raw_collection_chunk(
    envelope: bytes,
    *,
    expected_binding: RawCollectionChunkBinding,
    master_key: bytes,
) -> DecryptedRawCollectionChunk:
    parsed = parse_raw_collection_chunk_envelope(
        envelope,
        expected_binding=expected_binding,
    )
    try:
        plaintext = AESGCM(
            derive_raw_collection_chunk_key(master_key)
        ).decrypt(
            parsed.nonce,
            parsed.ciphertext_and_tag,
            _aad(parsed.header_bytes),
        )
    except InvalidTag as exc:
        raise RawCollectionChunkAuthenticationError(
            "raw chunk envelope authentication failed"
        ) from exc
    if (
        len(plaintext) != parsed.binding.plaintext_length
        or not hmac.compare_digest(
            hashlib.sha256(plaintext).hexdigest(),
            parsed.binding.plaintext_sha256,
        )
    ):
        raise RawCollectionChunkAuthenticationError(
            "raw chunk envelope authentication failed"
        )
    return DecryptedRawCollectionChunk(
        binding=parsed.binding,
        content=plaintext,
        key_id=parsed.key_id,
        plaintext_sha256=parsed.binding.plaintext_sha256,
    )


__all__ = [
    "AAD_DOMAIN",
    "AAD_VERSION",
    "ENVELOPE_ALGORITHM",
    "ENVELOPE_MAGIC",
    "ENVELOPE_VERSION",
    "EncryptedRawCollectionChunk",
    "KEY_DERIVATION_DOMAIN",
    "MAX_ENVELOPE_BYTES",
    "MAX_PLAINTEXT_BYTES",
    "NONCE_BYTES",
    "ParsedRawCollectionChunkEnvelope",
    "RawCollectionChunkAuthenticationError",
    "RawCollectionChunkBinding",
    "RawCollectionChunkCryptoError",
    "RawCollectionChunkFormatError",
    "decrypt_raw_collection_chunk",
    "derive_raw_collection_chunk_key",
    "encrypt_raw_collection_chunk",
    "parse_raw_collection_chunk_envelope",
]
