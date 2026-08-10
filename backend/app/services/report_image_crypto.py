"""Authenticated, versioned storage envelope for report images.

Only the post-validation image bytes accepted by ``backend.app.uploads`` enter
this format.  This module deliberately has no legacy/plaintext decoder.
"""

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
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


ENVELOPE_MAGIC = b"WSRI\x00\x00\x00\x01"
ENVELOPE_SCHEMA = "walksafe.report-image.envelope.v1"
ENVELOPE_ALGORITHM = "A256GCM"
ENVELOPE_VERSION = 1
AAD_VERSION = 1
NONCE_BYTES = 12
TAG_BYTES = 16
MAX_HEADER_BYTES = 4096
MAX_PLAINTEXT_BYTES = 32 * 1024 * 1024
MAX_ENVELOPE_BYTES = MAX_PLAINTEXT_BYTES + MAX_HEADER_BYTES + len(ENVELOPE_MAGIC) + 4 + TAG_BYTES
_HEADER_KEYS = {
    "aad_version",
    "alg",
    "content_type",
    "key_id",
    "nonce_b64url",
    "plaintext_length",
    "plaintext_sha256",
    "report_id",
    "schema",
}
_CONTENT_TYPES = frozenset({"image/jpeg", "image/png", "image/webp"})
_KEY_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_AAD_REPORT_BINDING = b"\x00walksafe-report-id\x00"


class ReportImageCryptoError(RuntimeError):
    """Base class for report image envelope failures."""


class ReportImageFormatError(ReportImageCryptoError):
    """The stored object is not an exact supported envelope."""


class ReportImageAuthenticationError(ReportImageCryptoError):
    """The ciphertext, key, nonce, or AAD did not authenticate."""


@dataclass(frozen=True)
class ParsedReportImageEnvelope:
    report_id: uuid.UUID
    key_id: str
    nonce: bytes
    content_type: str
    plaintext_length: int
    plaintext_sha256: str
    header_bytes: bytes
    ciphertext_and_tag: bytes


@dataclass(frozen=True)
class EncryptedReportImage:
    report_id: uuid.UUID
    key_id: str
    nonce: bytes
    content_type: str
    plaintext_length: int
    plaintext_sha256: str
    envelope: bytes
    envelope_sha256: str


@dataclass(frozen=True)
class DecryptedReportImage:
    content: bytes
    content_type: str
    plaintext_sha256: str


def _canonical_json(value: dict[str, Any]) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def _encode_base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _decode_base64url(value: object, *, expected_bytes: int) -> bytes:
    if not isinstance(value, str) or "=" in value:
        raise ReportImageFormatError("report image envelope contains invalid base64url")
    try:
        decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (binascii.Error, ValueError) as exc:
        raise ReportImageFormatError("report image envelope contains invalid base64url") from exc
    if len(decoded) != expected_bytes or _encode_base64url(decoded) != value:
        raise ReportImageFormatError("report image envelope contains non-canonical base64url")
    return decoded


def _reject_duplicate_object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _validated_key(key: bytes) -> bytes:
    if not isinstance(key, bytes) or len(key) != 32:
        raise ValueError("report image encryption keys must contain exactly 32 bytes")
    return key


def _validated_report_id(value: uuid.UUID | str) -> uuid.UUID:
    try:
        report_id = value if isinstance(value, uuid.UUID) else uuid.UUID(value)
    except (AttributeError, TypeError, ValueError) as exc:
        raise ReportImageFormatError("report image envelope report id is invalid") from exc
    return report_id


def _aad(header_bytes: bytes, report_id: uuid.UUID) -> bytes:
    return (
        ENVELOPE_MAGIC
        + struct.pack(">I", len(header_bytes))
        + header_bytes
        + _AAD_REPORT_BINDING
        + report_id.bytes
    )


def encrypt_report_image(
    plaintext: bytes,
    *,
    report_id: uuid.UUID,
    content_type: str,
    key_id: str,
    key: bytes,
    nonce: bytes | None = None,
) -> EncryptedReportImage:
    """Encrypt one image into an envelope bound to its report UUID."""

    if not isinstance(plaintext, bytes) or not 0 < len(plaintext) <= MAX_PLAINTEXT_BYTES:
        raise ValueError("report image plaintext size is invalid")
    if content_type not in _CONTENT_TYPES:
        raise ValueError("report image content type is unsupported")
    if _KEY_ID_PATTERN.fullmatch(key_id) is None:
        raise ValueError("report image key id is invalid")
    key = _validated_key(key)
    nonce = os.urandom(NONCE_BYTES) if nonce is None else nonce
    if not isinstance(nonce, bytes) or len(nonce) != NONCE_BYTES:
        raise ValueError("report image nonce must contain exactly 12 bytes")

    plaintext_sha256 = hashlib.sha256(plaintext).hexdigest()
    header = {
        "aad_version": AAD_VERSION,
        "alg": ENVELOPE_ALGORITHM,
        "content_type": content_type,
        "key_id": key_id,
        "nonce_b64url": _encode_base64url(nonce),
        "plaintext_length": len(plaintext),
        "plaintext_sha256": plaintext_sha256,
        "report_id": str(report_id),
        "schema": ENVELOPE_SCHEMA,
    }
    header_bytes = _canonical_json(header)
    if len(header_bytes) > MAX_HEADER_BYTES:
        raise ValueError("report image envelope header is too large")
    ciphertext_and_tag = AESGCM(key).encrypt(
        nonce,
        plaintext,
        _aad(header_bytes, report_id),
    )
    envelope = ENVELOPE_MAGIC + struct.pack(">I", len(header_bytes)) + header_bytes + ciphertext_and_tag
    return EncryptedReportImage(
        report_id=report_id,
        key_id=key_id,
        nonce=nonce,
        content_type=content_type,
        plaintext_length=len(plaintext),
        plaintext_sha256=plaintext_sha256,
        envelope=envelope,
        envelope_sha256=hashlib.sha256(envelope).hexdigest(),
    )


def parse_report_image_envelope(
    envelope: bytes,
    *,
    expected_report_id: uuid.UUID | None = None,
) -> ParsedReportImageEnvelope:
    """Strictly parse v1; unsupported or plaintext inputs are rejected."""

    minimum_size = len(ENVELOPE_MAGIC) + 4 + 2 + TAG_BYTES
    if not isinstance(envelope, bytes) or not minimum_size <= len(envelope) <= MAX_ENVELOPE_BYTES:
        raise ReportImageFormatError("report image envelope size is invalid")
    if envelope[: len(ENVELOPE_MAGIC)] != ENVELOPE_MAGIC:
        raise ReportImageFormatError("report image envelope version is unsupported")
    header_length = struct.unpack(">I", envelope[len(ENVELOPE_MAGIC) : len(ENVELOPE_MAGIC) + 4])[0]
    if not 0 < header_length <= MAX_HEADER_BYTES:
        raise ReportImageFormatError("report image envelope header size is invalid")
    header_start = len(ENVELOPE_MAGIC) + 4
    header_end = header_start + header_length
    if header_end + TAG_BYTES > len(envelope):
        raise ReportImageFormatError("report image envelope is truncated")
    header_bytes = envelope[header_start:header_end]
    try:
        header = json.loads(
            header_bytes.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_object_pairs,
        )
    except (UnicodeError, ValueError, TypeError, json.JSONDecodeError) as exc:
        raise ReportImageFormatError("report image envelope header is invalid") from exc
    if not isinstance(header, dict) or set(header) != _HEADER_KEYS or _canonical_json(header) != header_bytes:
        raise ReportImageFormatError("report image envelope header is not canonical")
    if (
        header.get("schema") != ENVELOPE_SCHEMA
        or header.get("alg") != ENVELOPE_ALGORITHM
        or header.get("aad_version") != AAD_VERSION
    ):
        raise ReportImageFormatError("report image envelope algorithm or version is unsupported")
    raw_report_id = header.get("report_id")
    report_id = _validated_report_id(raw_report_id)
    if not isinstance(raw_report_id, str) or str(report_id) != raw_report_id:
        raise ReportImageFormatError("report image envelope report id is not canonical")
    if expected_report_id is not None and report_id != expected_report_id:
        raise ReportImageFormatError("report image envelope is bound to another report")
    key_id = header.get("key_id")
    if not isinstance(key_id, str) or _KEY_ID_PATTERN.fullmatch(key_id) is None:
        raise ReportImageFormatError("report image envelope key id is invalid")
    content_type = header.get("content_type")
    if content_type not in _CONTENT_TYPES:
        raise ReportImageFormatError("report image envelope content type is unsupported")
    plaintext_length = header.get("plaintext_length")
    if (
        not isinstance(plaintext_length, int)
        or isinstance(plaintext_length, bool)
        or not 0 < plaintext_length <= MAX_PLAINTEXT_BYTES
    ):
        raise ReportImageFormatError("report image envelope plaintext size is invalid")
    plaintext_sha256 = header.get("plaintext_sha256")
    if not isinstance(plaintext_sha256, str) or _SHA256_PATTERN.fullmatch(plaintext_sha256) is None:
        raise ReportImageFormatError("report image envelope plaintext digest is invalid")
    nonce = _decode_base64url(header.get("nonce_b64url"), expected_bytes=NONCE_BYTES)
    ciphertext_and_tag = envelope[header_end:]
    if len(ciphertext_and_tag) != plaintext_length + TAG_BYTES:
        raise ReportImageFormatError("report image envelope ciphertext size is invalid")
    return ParsedReportImageEnvelope(
        report_id=report_id,
        key_id=key_id,
        nonce=nonce,
        content_type=content_type,
        plaintext_length=plaintext_length,
        plaintext_sha256=plaintext_sha256,
        header_bytes=header_bytes,
        ciphertext_and_tag=ciphertext_and_tag,
    )


def decrypt_report_image(
    envelope: bytes,
    *,
    expected_report_id: uuid.UUID,
    key: bytes,
) -> DecryptedReportImage:
    """Authenticate the whole envelope before returning any plaintext."""

    parsed = parse_report_image_envelope(envelope, expected_report_id=expected_report_id)
    key = _validated_key(key)
    try:
        plaintext = AESGCM(key).decrypt(
            parsed.nonce,
            parsed.ciphertext_and_tag,
            _aad(parsed.header_bytes, expected_report_id),
        )
    except InvalidTag as exc:
        raise ReportImageAuthenticationError("report image envelope authentication failed") from exc
    if len(plaintext) != parsed.plaintext_length or not hmac.compare_digest(
        hashlib.sha256(plaintext).hexdigest(),
        parsed.plaintext_sha256,
    ):
        raise ReportImageAuthenticationError("report image envelope authentication failed")
    return DecryptedReportImage(
        content=plaintext,
        content_type=parsed.content_type,
        plaintext_sha256=parsed.plaintext_sha256,
    )
