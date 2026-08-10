from __future__ import annotations

import json
import struct
import uuid

import pytest

from backend.app.services.report_image_crypto import (
    ENVELOPE_MAGIC,
    ReportImageAuthenticationError,
    ReportImageFormatError,
    decrypt_report_image,
    encrypt_report_image,
    parse_report_image_envelope,
)


KEY = bytes(range(32))
OTHER_KEY = bytes(reversed(range(32)))


def test_aes256_gcm_envelope_round_trip_hides_plain_image_signature() -> None:
    report_id = uuid.uuid4()
    plaintext = b"\xff\xd8\xffprivate-report-image"

    encrypted = encrypt_report_image(
        plaintext,
        report_id=report_id,
        content_type="image/jpeg",
        key_id="report-image-key-v1",
        key=KEY,
    )

    assert encrypted.envelope.startswith(ENVELOPE_MAGIC)
    assert not encrypted.envelope.startswith(b"\xff\xd8\xff")
    assert plaintext not in encrypted.envelope
    assert decrypt_report_image(
        encrypted.envelope,
        expected_report_id=report_id,
        key=KEY,
    ).content == plaintext


def test_envelope_uses_fresh_96_bit_nonce_for_each_write() -> None:
    report_id = uuid.uuid4()
    first = encrypt_report_image(
        b"one",
        report_id=report_id,
        content_type="image/png",
        key_id="key-v1",
        key=KEY,
    )
    second = encrypt_report_image(
        b"two",
        report_id=report_id,
        content_type="image/png",
        key_id="key-v1",
        key=KEY,
    )

    assert len(first.nonce) == 12
    assert len(second.nonce) == 12
    assert first.nonce != second.nonce


@pytest.mark.parametrize("key", [b"", b"a" * 16, b"a" * 24, b"a" * 31, b"a" * 33])
def test_encryption_rejects_non_aes256_keys(key: bytes) -> None:
    with pytest.raises(ValueError, match="exactly 32"):
        encrypt_report_image(
            b"image",
            report_id=uuid.uuid4(),
            content_type="image/jpeg",
            key_id="key-v1",
            key=key,
        )


def test_wrong_key_ciphertext_and_tag_fail_authentication() -> None:
    report_id = uuid.uuid4()
    encrypted = encrypt_report_image(
        b"authenticated-image",
        report_id=report_id,
        content_type="image/webp",
        key_id="key-v1",
        key=KEY,
    )

    with pytest.raises(ReportImageAuthenticationError):
        decrypt_report_image(
            encrypted.envelope,
            expected_report_id=report_id,
            key=OTHER_KEY,
        )

    tampered = bytearray(encrypted.envelope)
    tampered[-1] ^= 1
    with pytest.raises(ReportImageAuthenticationError):
        decrypt_report_image(bytes(tampered), expected_report_id=report_id, key=KEY)


def test_report_id_is_bound_in_header_and_aad() -> None:
    first_report_id = uuid.uuid4()
    second_report_id = uuid.uuid4()
    encrypted = encrypt_report_image(
        b"bound-image",
        report_id=first_report_id,
        content_type="image/jpeg",
        key_id="key-v1",
        key=KEY,
    )

    with pytest.raises(ReportImageFormatError, match="another report"):
        decrypt_report_image(
            encrypted.envelope,
            expected_report_id=second_report_id,
            key=KEY,
        )

    header_length = struct.unpack(">I", encrypted.envelope[8:12])[0]
    header = json.loads(encrypted.envelope[12 : 12 + header_length])
    header["report_id"] = str(second_report_id)
    changed_header = json.dumps(header, separators=(",", ":"), sort_keys=True).encode()
    assert len(changed_header) == header_length
    rebound = encrypted.envelope[:8] + struct.pack(">I", header_length) + changed_header + encrypted.envelope[12 + header_length :]
    with pytest.raises(ReportImageAuthenticationError):
        decrypt_report_image(rebound, expected_report_id=second_report_id, key=KEY)


@pytest.mark.parametrize(
    "payload",
    [
        b"\xff\xd8\xffplain-jpeg",
        b"\x89PNG\r\n\x1a\nplain-png",
        b"",
        ENVELOPE_MAGIC,
        ENVELOPE_MAGIC[:-1] + b"\x02" + b"\x00" * 64,
    ],
)
def test_plaintext_truncated_and_unknown_versions_have_no_fallback(payload: bytes) -> None:
    with pytest.raises(ReportImageFormatError):
        parse_report_image_envelope(payload)


def test_header_must_be_exact_canonical_json_without_duplicate_keys() -> None:
    report_id = uuid.uuid4()
    duplicate_header = (
        b'{"aad_version":1,"aad_version":1,"alg":"A256GCM",'
        b'"content_type":"image/jpeg","key_id":"key-v1",'
        b'"nonce_b64url":"AAAAAAAAAAAAAAAA","plaintext_length":1,'
        b'"plaintext_sha256":"' + b"0" * 64 + b'","report_id":"'
        + str(report_id).encode()
        + b'","schema":"walksafe.report-image.envelope.v1"}'
    )
    envelope = ENVELOPE_MAGIC + struct.pack(">I", len(duplicate_header)) + duplicate_header + b"x" * 17

    with pytest.raises(ReportImageFormatError):
        parse_report_image_envelope(envelope)
