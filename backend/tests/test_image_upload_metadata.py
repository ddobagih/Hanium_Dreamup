"""Real image codecs and upload boundary regressions; no external services."""

from __future__ import annotations

import asyncio
import hashlib
import io
import struct
from types import SimpleNamespace

from fastapi import HTTPException, UploadFile
from PIL import Image, ImageCms, PngImagePlugin
import pytest
from starlette.datastructures import Headers

from backend.app import uploads


_NOTE = "private-source-camera-and-location"
_PNG_RENDERING_CHUNKS = {b"IHDR", b"PLTE", b"tRNS", b"IDAT", b"IEND"}


def _png_chunks(content: bytes) -> list[bytes]:
    assert content.startswith(b"\x89PNG\r\n\x1a\n")
    chunks = []
    offset = 8
    while offset < len(content):
        length = int.from_bytes(content[offset:offset + 4], "big")
        chunks.append(content[offset + 4:offset + 8])
        offset += length + 12
        assert offset <= len(content)
    assert offset == len(content) and chunks[-1] == b"IEND"
    return chunks


def _webp_chunks(content: bytes) -> list[bytes]:
    assert content[:4] == b"RIFF" and content[8:12] == b"WEBP"
    assert int.from_bytes(content[4:8], "little") + 8 == len(content)
    chunks = []
    offset = 12
    while offset < len(content):
        chunks.append(content[offset:offset + 4])
        length = int.from_bytes(content[offset + 4:offset + 8], "little")
        offset += 8 + length + length % 2
        assert offset <= len(content)
    assert offset == len(content)
    return chunks


def _metadata_image(content_type: str) -> tuple[bytes, bytes]:
    profile = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
    exif = Image.Exif()
    exif[270] = _NOTE
    exif[274] = 6
    output = io.BytesIO()
    with Image.new("RGB", (24, 16), (31, 79, 143)) as source:
        for x in range(source.width):
            source.putpixel((x, x % source.height), (x * 9, 201, 17))
        options = {"icc_profile": profile, "exif": exif.tobytes()}
        if content_type == "image/png":
            metadata = PngImagePlugin.PngInfo()
            metadata.add_text("source_note", _NOTE)
            metadata.add_text("compressed_note", _NOTE, zip=True)
            metadata.add_itxt("XML:com.adobe.xmp", "<xmp>" + _NOTE + "</xmp>")
            metadata.add(b"gAMA", struct.pack(">I", 45455))
            metadata.add(b"vpAg", _NOTE.encode("ascii"))
            source.save(output, "PNG", pnginfo=metadata, dpi=(300, 360), **options)
        elif content_type == "image/jpeg":
            source.save(
                output, "JPEG", quality=95, dpi=(300, 360),
                comment=_NOTE.encode("ascii"), **options,
            )
        else:
            assert content_type == "image/webp"
            source.save(
                output, "WEBP", lossless=True,
                xmp=("<xmp>" + _NOTE + "</xmp>").encode("ascii"), **options,
            )
    return output.getvalue(), profile


def _read_upload(content: bytes, content_type: str, *, validate_original=None):
    async def read():
        upload = UploadFile(
            io.BytesIO(content),
            filename="source" + uploads.image_suffix(content_type),
            headers=Headers({"content-type": content_type}),
        )
        try:
            return await uploads.read_image_upload(
                upload,
                SimpleNamespace(
                    max_upload_bytes=1024 * 1024,
                    allowed_image_content_types={"image/png", "image/jpeg", "image/webp"},
                ),
                validate_original=validate_original,
            )
        finally:
            await upload.close()
    return asyncio.run(read())


@pytest.mark.parametrize("content_type", ("image/png", "image/jpeg", "image/webp"))
def test_real_upload_discards_source_metadata_after_original_transport_validation(
    content_type: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original, profile = _metadata_image(content_type)
    with Image.open(io.BytesIO(original)) as source:
        source.load()
        assert source.info["icc_profile"] == profile
        assert source.getexif()[270] == _NOTE
        assert source.getexif()[274] == 6
        if content_type == "image/png":
            assert source.info["source_note"] == source.info["compressed_note"] == _NOTE
            assert _NOTE in source.info["XML:com.adobe.xmp"]
            assert "dpi" in source.info and "gamma" in source.info
            assert {b"iCCP", b"eXIf", b"tEXt", b"zTXt", b"iTXt", b"gAMA", b"pHYs", b"vpAg"} <= set(_png_chunks(original))
        elif content_type == "image/jpeg":
            assert source.info["comment"] == _NOTE.encode("ascii")
            assert source.info["dpi"] == (300, 360)
        else:
            assert _NOTE.encode("ascii") in source.info["xmp"]
            assert {b"ICCP", b"EXIF", b"XMP "} <= set(_webp_chunks(original))

    events = []
    sanitizer = uploads.strip_image_metadata

    def validate(content):
        events.append(("original", hashlib.sha256(content).hexdigest()))

    def sanitize(content, mime):
        events.append(("sanitize", hashlib.sha256(content).hexdigest()))
        return sanitizer(content, mime)

    monkeypatch.setattr(uploads, "strip_image_metadata", sanitize)
    sanitized, returned_type = _read_upload(
        original, content_type, validate_original=validate,
    )
    original_digest = hashlib.sha256(original).hexdigest()
    assert events == [("original", original_digest), ("sanitize", original_digest)]
    assert hashlib.sha256(sanitized).hexdigest() != original_digest
    assert returned_type == content_type
    assert uploads.matches_image_signature(sanitized, content_type)
    with Image.open(io.BytesIO(sanitized)) as cleaned:
        cleaned.load()
        assert cleaned.size == (16, 24)
        assert not cleaned.getexif()
        assert not {
            "icc_profile", "exif", "xmp", "comment", "dpi", "gamma",
            "source_note", "compressed_note", "XML:com.adobe.xmp",
        }.intersection(cleaned.info)
        if content_type == "image/png":
            assert set(_png_chunks(sanitized)) <= _PNG_RENDERING_CHUNKS
            assert cleaned.info == {}
        elif content_type == "image/webp":
            assert set(_webp_chunks(sanitized)) <= {b"VP8 ", b"VP8L", b"VP8X", b"ALPH"}
        else:
            # A freshly encoded JFIF header is structural, not retained source DPI.
            assert set(cleaned.info) <= {"jfif", "jfif_version", "jfif_unit", "jfif_density"}
            assert cleaned.info["jfif_unit"] == 0


@pytest.mark.parametrize(
    ("orientation", "expected_size", "expected_landmarks"),
    (
        (6, (10, 20), {(9, 0): (255, 0, 0), (9, 19): (0, 255, 0),
                       (0, 0): (0, 0, 255), (0, 19): (255, 255, 0)}),
        (2, (20, 10), {(19, 0): (255, 0, 0), (0, 0): (0, 255, 0),
                       (19, 9): (0, 0, 255), (0, 9): (255, 255, 0)}),
    ),
)
def test_exif_orientation_is_applied_to_pixels_before_metadata_is_removed(
    orientation: int,
    expected_size: tuple[int, int],
    expected_landmarks: dict[tuple[int, int], tuple[int, int, int]],
) -> None:
    exif = Image.Exif()
    exif[274] = orientation
    output = io.BytesIO()
    with Image.new("RGB", (20, 10), (12, 34, 56)) as source:
        source.putpixel((0, 0), (255, 0, 0))
        source.putpixel((19, 0), (0, 255, 0))
        source.putpixel((0, 9), (0, 0, 255))
        source.putpixel((19, 9), (255, 255, 0))
        source.save(output, "PNG", exif=exif.tobytes())

    sanitized = uploads.strip_image_metadata(output.getvalue(), "image/png")
    with Image.open(io.BytesIO(sanitized)) as cleaned:
        cleaned.load()
        assert cleaned.size == expected_size
        assert not cleaned.getexif()
        for position, color in expected_landmarks.items():
            assert cleaned.getpixel(position) == color


@pytest.mark.parametrize("mode", ("P", "1", "L", "LA", "RGB", "RGBA", "I;16"))
def test_png_rendering_pixels_palette_and_transparency_survive_without_ancillary_metadata(
    mode: str,
) -> None:
    output = io.BytesIO()
    with Image.new(mode, (8, 4)) as source:
        if mode == "P":
            source.putpalette([component for index in range(256) for component in (index, 255 - index, index // 2)])
        for y in range(4):
            for x in range(8):
                index = y * 8 + x
                value = {
                    "P": index % 3,
                    "1": 255 if index % 2 else 0,
                    "L": index * 7,
                    "LA": (index * 7, (index * 17) % 256),
                    "RGB": (index * 7, 41, 67),
                    "RGBA": (index * 7, 41, 67, (index * 17) % 256),
                    "I;16": index * 2000,
                }[mode]
                source.putpixel((x, y), value)
        transparency = {
            "P": bytes((0, 64, 255)), "1": 0, "L": 70,
            "RGB": (70, 41, 67), "I;16": 20000,
        }
        metadata = PngImagePlugin.PngInfo()
        metadata.add_text("private_note", _NOTE)
        options = {"transparency": transparency[mode]} if mode in transparency else {}
        source.save(output, "PNG", pnginfo=metadata, **options)
    original = output.getvalue()
    with Image.open(io.BytesIO(original)) as source:
        source.load()
        assert source.info["private_note"] == _NOTE
        before = (source.mode, source.size, source.tobytes(), source.convert("RGBA").tobytes())
        palette = source.getpalette() if mode == "P" else None
        transparent = source.info.get("transparency")
    sanitized, mime = _read_upload(original, "image/png")
    assert mime == "image/png"
    with Image.open(io.BytesIO(sanitized)) as cleaned:
        cleaned.load()
        assert (cleaned.mode, cleaned.size, cleaned.tobytes(), cleaned.convert("RGBA").tobytes()) == before
        assert cleaned.getpalette() == palette
        assert cleaned.info == ({"transparency": transparent} if transparent is not None else {})
        assert set(_png_chunks(sanitized)) <= _PNG_RENDERING_CHUNKS
        assert (b"tRNS" in _png_chunks(sanitized)) == (transparent is not None)


def test_original_receipt_rejection_still_precedes_any_decode(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original, _ = _metadata_image("image/png")
    observed = []
    error = HTTPException(status_code=409, detail={"code": "original_receipt_mismatch"})

    def reject(content):
        observed.append(content)
        raise error

    def unexpected_decode(*_args):
        raise AssertionError("a rejected original must not be decoded")

    monkeypatch.setattr(uploads, "strip_image_metadata", unexpected_decode)
    with pytest.raises(HTTPException) as rejected:
        _read_upload(original, "image/png", validate_original=reject)
    assert rejected.value is error
    assert observed == [original]


@pytest.mark.parametrize(
    ("content_type", "content"),
    (
        ("image/png", b"\x89PNG\r\n\x1a\n"),
        ("image/jpeg", b"\xff\xd8\xff"),
        ("image/webp", b"RIFF\x04\x00\x00\x00WEBP"),
    ),
)
def test_signature_only_input_still_fails_closed_as_invalid_image(
    content_type: str, content: bytes,
) -> None:
    assert uploads.matches_image_signature(content, content_type)
    with pytest.raises(HTTPException) as rejected:
        _read_upload(content, content_type)
    assert rejected.value.status_code == 400
    assert rejected.value.detail == {
        "code": "invalid_image_content",
        "message": "image must be decodable and sanitizable",
    }


def test_image_dimension_limit_still_rejects_before_reencoding(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    content, _ = _metadata_image("image/png")
    monkeypatch.setattr(uploads, "MAX_IMAGE_DIMENSION", 8)
    with pytest.raises(HTTPException) as rejected:
        _read_upload(content, "image/png")
    assert rejected.value.status_code == 413
    assert rejected.value.detail == {
        "code": "image_dimensions_too_large",
        "max_dimension": 8,
        "max_pixels": uploads.MAX_IMAGE_PIXELS,
    }
