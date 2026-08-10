#!/usr/bin/env python3
"""Read the release identity from WalkSafe BuildConfig in an APK DEX set."""

from __future__ import annotations

import hashlib
import re
import struct
from typing import Mapping
import zlib


BUILD_CONFIG_DESCRIPTOR = b"Lkr/co/hanium/dreamup/walksafe/BuildConfig;"
DEX_NAME = re.compile(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex")
FULL_SHA = re.compile(r"[0-9a-f]{40}")
NO_INDEX = 0xFFFFFFFF
PUBLIC_STATIC_FINAL = 0x19
STRING_DESCRIPTOR = b"Ljava/lang/String;"


class DexBindingError(ValueError):
    pass


def _dex_number(name: str) -> int:
    if name == "classes.dex":
        return 1
    matched = DEX_NAME.fullmatch(name)
    if matched is None:
        raise DexBindingError(f"Android APK has a noncanonical root DEX name: {name}")
    try:
        return int(name[7:-4])
    except ValueError as exc:
        raise DexBindingError(f"Android APK DEX index is invalid: {name}") from exc


class _DexReader:
    def __init__(self, name: str, payload: bytes) -> None:
        self.name = name
        self.payload = payload
        self._strings: dict[int, bytes] = {}
        self._validate_header()
        self.string_ids_size = self._u32(56)
        self.string_ids_off = self._u32(60)
        self.type_ids_size = self._u32(64)
        self.type_ids_off = self._u32(68)
        self.field_ids_size = self._u32(80)
        self.field_ids_off = self._u32(84)
        self.class_defs_size = self._u32(96)
        self.class_defs_off = self._u32(100)
        self._table(self.string_ids_off, self.string_ids_size, 4, "string_ids")
        self._table(self.type_ids_off, self.type_ids_size, 4, "type_ids")
        self._table(self.field_ids_off, self.field_ids_size, 8, "field_ids")
        self._table(self.class_defs_off, self.class_defs_size, 32, "class_defs")

    def _fail(self, detail: str) -> DexBindingError:
        return DexBindingError(f"Android DEX {self.name} is invalid: {detail}")

    def _range(self, offset: int, size: int, detail: str) -> None:
        if offset < 0 or size < 0 or offset > len(self.payload) - size:
            raise self._fail(detail)

    def _u16(self, offset: int) -> int:
        self._range(offset, 2, "truncated uint16")
        return struct.unpack_from("<H", self.payload, offset)[0]

    def _u32(self, offset: int) -> int:
        self._range(offset, 4, "truncated uint32")
        return struct.unpack_from("<I", self.payload, offset)[0]

    def _table(self, offset: int, count: int, width: int, label: str) -> None:
        if count == 0:
            if offset != 0:
                raise self._fail(f"empty {label} has a nonzero offset")
            return
        if offset == 0 or count > len(self.payload) // width:
            raise self._fail(f"invalid {label} bounds")
        self._range(offset, count * width, f"truncated {label}")

    def _uleb128(self, offset: int) -> tuple[int, int]:
        value = 0
        for shift in range(0, 35, 7):
            self._range(offset, 1, "truncated uleb128")
            byte = self.payload[offset]
            offset += 1
            value |= (byte & 0x7F) << shift
            if byte < 0x80:
                if value > NO_INDEX:
                    raise self._fail("uleb128 exceeds uint32")
                return value, offset
        raise self._fail("uleb128 is too long")

    def _validate_header(self) -> None:
        if len(self.payload) < 112:
            raise self._fail("header is truncated")
        magic = self.payload[:8]
        if magic != b"dex\n038\0":
            raise self._fail("magic/version is unsupported")
        if self._u32(32) != len(self.payload) or self._u32(36) != 112:
            raise self._fail("file or header size differs from the payload")
        if self._u32(40) != 0x12345678:
            raise self._fail("endian tag is unsupported")
        if self.payload[12:32] != hashlib.sha1(self.payload[32:]).digest():
            raise self._fail("SHA-1 signature differs from the payload")
        if self._u32(8) != (zlib.adler32(self.payload[12:]) & NO_INDEX):
            raise self._fail("Adler-32 checksum differs from the payload")
        data_size = self._u32(104)
        data_off = self._u32(108)
        if data_size == 0 or data_off < 112 or data_off + data_size != len(self.payload):
            raise self._fail("data section does not close the file")
        self.data_off = data_off
        self.data_end = data_off + data_size
        map_off = self._u32(52)
        if map_off < data_off or map_off >= len(self.payload):
            raise self._fail("map offset is outside the data section")

    def _mutf8_utf16_size(self, value: bytes) -> int:
        cursor = 0
        units = 0
        while cursor < len(value):
            first = value[cursor]
            cursor += 1
            if 0x01 <= first <= 0x7F:
                units += 1
                continue
            if 0xC0 <= first <= 0xDF:
                if cursor >= len(value) or value[cursor] & 0xC0 != 0x80:
                    raise self._fail("string data has invalid modified UTF-8")
                second = value[cursor]
                cursor += 1
                code_unit = ((first & 0x1F) << 6) | (second & 0x3F)
                if code_unit < 0x80 and code_unit != 0:
                    raise self._fail("string data has overlong modified UTF-8")
                units += 1
                continue
            if 0xE0 <= first <= 0xEF:
                if (
                    cursor + 1 >= len(value)
                    or value[cursor] & 0xC0 != 0x80
                    or value[cursor + 1] & 0xC0 != 0x80
                ):
                    raise self._fail("string data has invalid modified UTF-8")
                second = value[cursor]
                third = value[cursor + 1]
                cursor += 2
                code_unit = (
                    ((first & 0x0F) << 12)
                    | ((second & 0x3F) << 6)
                    | (third & 0x3F)
                )
                if code_unit < 0x800:
                    raise self._fail("string data has overlong modified UTF-8")
                units += 1
                continue
            raise self._fail("string data has invalid modified UTF-8")
        return units

    def _string(self, index: int) -> bytes:
        if index < 0 or index >= self.string_ids_size:
            raise self._fail("string index is out of range")
        cached = self._strings.get(index)
        if cached is not None:
            return cached
        data_off = self._u32(self.string_ids_off + index * 4)
        if data_off < self.data_off or data_off >= self.data_end:
            raise self._fail("string data offset is outside the data section")
        utf16_size, cursor = self._uleb128(data_off)
        if cursor >= self.data_end:
            raise self._fail("string data starts outside the data section")
        terminator = self.payload.find(b"\0", cursor, self.data_end)
        if terminator < 0:
            raise self._fail("string data is not terminated")
        value = self.payload[cursor:terminator]
        if self._mutf8_utf16_size(value) != utf16_size:
            raise self._fail("string modified UTF-8 length differs from utf16_size")
        self._strings[index] = value
        return value

    def _type_descriptor(self, index: int) -> bytes:
        if index < 0 or index >= self.type_ids_size:
            raise self._fail("type index is out of range")
        return self._string(self._u32(self.type_ids_off + index * 4))

    def _encoded_value(self, offset: int) -> tuple[tuple[str, object], int]:
        self._range(offset, 1, "truncated encoded value")
        header = self.payload[offset]
        offset += 1
        value_type = header & 0x1F
        value_arg = header >> 5
        max_args = {
            0x00: 0,
            0x02: 1,
            0x03: 1,
            0x04: 3,
            0x06: 7,
            0x10: 3,
            0x11: 7,
            0x15: 3,
            0x16: 3,
            0x17: 3,
            0x18: 3,
            0x19: 3,
            0x1A: 3,
            0x1B: 3,
        }
        if value_type in max_args:
            if value_arg > max_args[value_type]:
                raise self._fail("encoded value width is invalid")
            width = value_arg + 1
            self._range(offset, width, "truncated encoded value payload")
            raw = int.from_bytes(self.payload[offset : offset + width], "little")
            offset += width
            if value_type == 0x17:
                if raw >= self.string_ids_size:
                    raise self._fail("encoded string index is out of range")
                return ("string", self._string(raw)), offset
            return ("scalar", raw), offset
        if value_type == 0x1E:
            if value_arg != 0:
                raise self._fail("encoded null has a value argument")
            return ("null", None), offset
        if value_type == 0x1F:
            if value_arg > 1:
                raise self._fail("encoded boolean is invalid")
            return ("boolean", bool(value_arg)), offset
        raise self._fail("BuildConfig has an unsupported encoded static value")

    def _build_config_fields(self, class_def_off: int) -> dict[bytes, tuple[bytes, int, str, object]]:
        class_idx = self._u32(class_def_off)
        class_data_off = self._u32(class_def_off + 24)
        static_values_off = self._u32(class_def_off + 28)
        if class_data_off == 0 or static_values_off == 0:
            raise self._fail("BuildConfig has no class data or static values")

        cursor = class_data_off
        static_count, cursor = self._uleb128(cursor)
        _instance_count, cursor = self._uleb128(cursor)
        _direct_count, cursor = self._uleb128(cursor)
        _virtual_count, cursor = self._uleb128(cursor)
        if static_count > self.field_ids_size:
            raise self._fail("BuildConfig static field count is invalid")

        static_fields: list[tuple[int, int]] = []
        previous = 0
        for position in range(static_count):
            difference, cursor = self._uleb128(cursor)
            access_flags, cursor = self._uleb128(cursor)
            if position > 0 and difference == 0:
                raise self._fail("BuildConfig static field indexes are not increasing")
            field_idx = difference if position == 0 else previous + difference
            if field_idx >= self.field_ids_size:
                raise self._fail("BuildConfig static field index is out of range")
            static_fields.append((field_idx, access_flags))
            previous = field_idx

        value_count, value_cursor = self._uleb128(static_values_off)
        if value_count > static_count:
            raise self._fail("BuildConfig has more values than static fields")
        values: list[tuple[str, object]] = []
        for _ in range(value_count):
            value, value_cursor = self._encoded_value(value_cursor)
            values.append(value)

        result: dict[bytes, tuple[bytes, int, str, object]] = {}
        for position, (field_idx, access_flags) in enumerate(static_fields):
            field_off = self.field_ids_off + field_idx * 8
            declaring_class = self._u16(field_off)
            type_idx = self._u16(field_off + 2)
            name = self._string(self._u32(field_off + 4))
            if declaring_class != class_idx or name in result:
                raise self._fail("BuildConfig static field ownership is invalid")
            kind, value = values[position] if position < len(values) else ("default", None)
            result[name] = (self._type_descriptor(type_idx), access_flags, kind, value)
        return result

    def build_config_fields(self) -> dict[bytes, tuple[bytes, int, str, object]] | None:
        matches: list[int] = []
        for index in range(self.class_defs_size):
            class_def_off = self.class_defs_off + index * 32
            class_idx = self._u32(class_def_off)
            if self._type_descriptor(class_idx) == BUILD_CONFIG_DESCRIPTOR:
                matches.append(class_def_off)
        if len(matches) > 1:
            raise self._fail("WalkSafe BuildConfig is defined more than once")
        return None if not matches else self._build_config_fields(matches[0])


def _require_field(
    fields: Mapping[bytes, tuple[bytes, int, str, object]],
    name: bytes,
    descriptor: bytes,
    kind: str,
    expected: object,
    *,
    binding: str,
) -> None:
    field = fields.get(name)
    if field != (descriptor, PUBLIC_STATIC_FINAL, kind, expected):
        raise DexBindingError(
            f"Android WalkSafe BuildConfig field {name.decode()} is not {binding}-bound"
        )


def _validate_walksafe_build_binding(
    dex_payloads: Mapping[str, bytes],
    source_commit: str,
    *,
    debug: bool | None,
    build_type: str,
    build_marker: str,
) -> str:
    """Return the unique DEX defining an exact WalkSafe BuildConfig variant."""

    if FULL_SHA.fullmatch(source_commit) is None:
        raise DexBindingError("Android source commit must be lowercase 40-hex")
    if not build_type or not build_type.isascii() or not build_marker or not build_marker.isascii():
        raise DexBindingError("Android build type and marker must be non-empty ASCII")
    names = sorted(dex_payloads, key=_dex_number)
    expected_names = ["classes.dex", *[f"classes{index}.dex" for index in range(2, len(names) + 1)]]
    if names != expected_names:
        raise DexBindingError("Android APK DEX names must be canonical and contiguous")

    matches: list[tuple[str, dict[bytes, tuple[bytes, int, str, object]]]] = []
    for name in names:
        if not isinstance(dex_payloads[name], bytes):
            raise DexBindingError(f"Android DEX {name} payload is not bytes")
        fields = _DexReader(name, dex_payloads[name]).build_config_fields()
        if fields is not None:
            matches.append((name, fields))
    if len(matches) != 1:
        raise DexBindingError("Android APK must define exactly one WalkSafe BuildConfig across its DEX files")

    name, fields = matches[0]
    binding = f"{build_type} build"
    if debug is not None:
        _require_field(fields, b"DEBUG", b"Z", "boolean", debug, binding=binding)
    _require_field(
        fields,
        b"BUILD_TYPE",
        STRING_DESCRIPTOR,
        "string",
        build_type.encode("ascii"),
        binding=binding,
    )
    _require_field(
        fields,
        b"WALKSAFE_SOURCE_COMMIT",
        STRING_DESCRIPTOR,
        "string",
        source_commit.encode("ascii"),
        binding=binding,
    )
    _require_field(
        fields,
        b"WALKSAFE_BUILD_MARKER",
        STRING_DESCRIPTOR,
        "string",
        build_marker.encode("ascii"),
        binding=binding,
    )
    return name


def validate_walksafe_release_binding(
    dex_payloads: Mapping[str, bytes],
    source_commit: str,
    release_marker: str = "walksafe-release-v1",
) -> str:
    """Return the unique DEX defining an exact release-bound WalkSafe BuildConfig."""

    return _validate_walksafe_build_binding(
        dex_payloads,
        source_commit,
        debug=False,
        build_type="release",
        build_marker=release_marker,
    )


def validate_walksafe_debug_binding(
    dex_payloads: Mapping[str, bytes],
    source_commit: str,
    debug_marker: str = "walksafe-debug-v1",
) -> str:
    """Return the unique DEX defining an exact debug field-build WalkSafe BuildConfig."""

    return _validate_walksafe_build_binding(
        dex_payloads,
        source_commit,
        # AGP debug BuildConfig initializes DEBUG through Boolean.parseBoolean("true")
        # in <clinit>, so the encoded static value is false. The release gate separately
        # verifies effective android:debuggable=true with the pinned APK analyzer.
        debug=None,
        build_type="debug",
        build_marker=debug_marker,
    )
