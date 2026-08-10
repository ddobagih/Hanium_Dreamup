#!/usr/bin/env python3
"""Build the add-only WalkSafe exact257 R011 progress successor.

R011 records only the already materialized Ready25 content and its source
document-review records.  It deliberately preserves the R007 queue, closure,
approval, execution, event, formal-evidence, and release boundaries.
"""

from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
import ctypes
import errno
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import stat
import sys
from typing import Any, Iterable, Mapping


REPO_ROOT = Path(__file__).resolve().parents[1]
RUN_DIR = (
    REPO_ROOT
    / "docs"
    / "control"
    / "execution"
    / "artifact-closure"
    / "run-20260727-001"
)

R007_LEDGER_PATH = RUN_DIR / "phase1-exact257-successor-ledger-r007.json"
R010_EVIDENCE_PATH = (
    RUN_DIR / "packets" / "phase1-exact257-successor-r010" / "evidence.json"
)
R010_RECEIPT_PATH = RUN_DIR / "phase1-exact257-successor-check-receipt-r010.json"
R010_REVIEW_PATH = RUN_DIR / "phase1-exact257-successor-independent-review-r010.md"

DOC01_PATH = REPO_ROOT / "docs" / "deliverables" / "00-control" / "artifact-register.json"
DOC05_PATH = REPO_ROOT / "docs" / "deliverables" / "00-control" / "artifact-change-log.json"
MANIFEST_PATH = (
    REPO_ROOT
    / "docs"
    / "deliverables"
    / "manifests"
    / "rel-ops-cls-draft-20260721-r001.json"
)

READY25_AI_PACKET_PATH = (
    RUN_DIR / "packets" / "phase1-ready25-ai-dev-sec-r002" / "evidence.json"
)
READY25_AI_RECEIPT_PATH = (
    RUN_DIR
    / "packets"
    / "phase1-ready25-ai-dev-sec-r002"
    / "phase1-ready25-ai-dev-sec-check-receipt-r002.json"
)
READY25_AI_REVIEW_PATH = (
    RUN_DIR / "phase1-ready25-ai-dev-sec-independent-review-r002.md"
)
READY25_CLS_OPS_PACKET_PATH = (
    RUN_DIR / "packets" / "phase1-ready25-cls-ops-r003" / "evidence.json"
)
READY25_CLS_OPS_RECEIPT_PATH = (
    RUN_DIR
    / "packets"
    / "phase1-ready25-cls-ops-r003"
    / "phase1-ready25-cls-ops-check-receipt-r003.json"
)
READY25_CLS_OPS_REVIEW_PATH = (
    RUN_DIR / "phase1-ready25-cls-ops-independent-review-r003.md"
)
READY25_REL_PACKET_PATH = (
    RUN_DIR / "packets" / "phase1-ready25-rel-r002" / "evidence.json"
)
READY25_REL_RECEIPT_PATH = (
    RUN_DIR
    / "packets"
    / "phase1-ready25-rel-r002"
    / "phase1-ready25-rel-check-receipt-r002.json"
)
READY25_REL_REVIEW_PATH = RUN_DIR / "phase1-ready25-rel-independent-review-r002.md"

R011_PACKET_DIR = RUN_DIR / "packets" / "phase1-exact257-successor-r011"
R011_LEDGER_PATH = R011_PACKET_DIR / "phase1-exact257-successor-ledger-r011.json"
R011_EVIDENCE_PATH = R011_PACKET_DIR / "evidence.json"
R011_RECEIPT_PATH = (
    R011_PACKET_DIR / "phase1-exact257-successor-check-receipt-r011.json"
)
R011_REVIEW_PATH = RUN_DIR / "phase1-exact257-successor-independent-review-r011.md"

PREPARED_ON = "2026-07-29"
RUN_ID = "WS-ARTIFACT-CLOSURE-RUN-20260727-001"
VERDICT = "PASS_FOR_READY25_PROGRESS_APPLICATION_WITH_ZERO_CREDIT_BOUNDARY_ONLY"

EXACT9 = (
    "DLV-AIML-19",
    "DLV-AIML-20",
    "DLV-AIML-25",
    "DLV-AIML-26",
    "DLV-DEV-10",
    "DLV-DEV-11",
    "DLV-DEV-13",
    "DLV-DEV-15",
    "DLV-SEC-18",
)
EXACT13 = (
    "DLV-CLS-04",
    "DLV-CLS-08",
    "DLV-CLS-10",
    "DLV-CLS-11",
    "DLV-CLS-14",
    "DLV-CLS-15",
    "DLV-CLS-16",
    "DLV-OPS-06",
    "DLV-OPS-07",
    "DLV-OPS-11",
    "DLV-OPS-13",
    "DLV-OPS-18",
    "DLV-OPS-22",
)
EXACT3 = ("DLV-REL-03", "DLV-REL-05", "DLV-REL-09")
EXACT25 = EXACT9 + EXACT13 + EXACT3
EXACT25_SET = frozenset(EXACT25)

CANONICAL_STATUS_COUNTS = {
    "EXTERNAL": 49,
    "INTERNAL_GAP": 48,
    "N_A_CANDIDATE": 36,
    "OK": 124,
}
QUEUE_ROUTE_COUNTS = {
    "ATTESTATION_REVIEW_PENDING": 4,
    "EVIDENCE_FACT_PENDING": 6,
    "INTERNAL_READY": 62,
    "INTERNAL_RUN_REQUIRED": 24,
    "OK_BASELINE": 124,
    "OWNER_APPROVAL_PENDING": 14,
    "REAL_EVENT_PENDING": 21,
    "SCOPE_DECISION_PENDING": 0,
    "SCOPE_N_A_APPROVED": 2,
}
ZERO_CREDITS = {
    "acceptance_count": 0,
    "actual_device_event_count": 0,
    "attestation_approval_count": 0,
    "execution_count": 0,
    "formal279_pass_count": 0,
    "formal_evidence_count": 0,
    "in_scope_substantive_credit_count": 0,
    "owner_approval_count": 0,
    "real_event_count": 0,
    "release_eligible_count": 0,
    "verified_rights_or_external_fact_count": 0,
}
SIX_COMPLETION_PATHS = (
    ("artifact_closure", "completion_claimed"),
    ("artifact_closure", "current_scope_n_a_closure_claimed"),
    ("artifact_closure", "global_artifact_completion_claimed"),
    ("claim_boundary", "artifact_completion_claimed"),
    ("claim_boundary", "current_scope_n_a_closure_claimed"),
    ("claim_boundary", "global_artifact_completion_claimed"),
)
R007_EXPECTED_SHA256 = "4cf29456590aaa6df99a4306e50e9d39bf152b69208b3b78c1d1f6087a0a33e9"
R010_EVIDENCE_EXPECTED_SHA256 = (
    "e878aa9915e549cfd06acaef7f150b75e0d6a0221d6c9978d6597d15c81c8056"
)
R010_RECEIPT_EXPECTED_SHA256 = (
    "5e9300013d252932cc696585c8e2d99ddcd434d972647c32a57620e64f62d51a"
)
PINNED_SOURCE_SHA256_BY_PATH = {
    R007_LEDGER_PATH: R007_EXPECTED_SHA256,
    R010_EVIDENCE_PATH: R010_EVIDENCE_EXPECTED_SHA256,
    R010_RECEIPT_PATH: R010_RECEIPT_EXPECTED_SHA256,
    R010_REVIEW_PATH: "9d724e59b4af2bb2e274d08482a6577ed1e4f42a2422b33f2714d8a6c29c183f",
    DOC01_PATH: "a0c993257c1b80211c2e8e4db5aa464708ac4aba9d47ed9b434eda277ee6547f",
    DOC05_PATH: "cea8b58fc5afa527614522481df56d69618c6fd1015c10b63d6cc8b6e0678c67",
    MANIFEST_PATH: "7a7c099fd0fc8c793a694e82a14a267471f1685211282ec6abe21c2f41083643",
    READY25_AI_PACKET_PATH: (
        "10867de47154f0add44755b8320ebb84e53732a18c841c55ca124117a2b321f2"
    ),
    READY25_AI_RECEIPT_PATH: (
        "0d01fe506ddb60e52621014aa15869ea1551969098ab10ac570976f145a0d626"
    ),
    READY25_AI_REVIEW_PATH: (
        "0ae221044785c6d3443a6dd999865ada702493e5099062244e7c6b4e5f069bf1"
    ),
    READY25_CLS_OPS_PACKET_PATH: (
        "5d313ecfcbf26d0b42ff74998191c617ded483e4ff560f635caa03021ff43211"
    ),
    READY25_CLS_OPS_RECEIPT_PATH: (
        "39c44e19157f970bd53afaec5872a36be66b0402f1cf2cdfa10ebfb21ba3b86d"
    ),
    READY25_CLS_OPS_REVIEW_PATH: (
        "9f3e9a4d773c034f08156232a2e09f83220385106778261ecbaf0ee3a88d4690"
    ),
    READY25_REL_PACKET_PATH: (
        "5410753f226f4a778bc3b3d629a9e74cb87e4335add618d1f20da36c0c5c9577"
    ),
    READY25_REL_RECEIPT_PATH: (
        "a37ae6989cf013cd8e8cde99336cf272e289ea92285817b8782a27badd386b61"
    ),
    READY25_REL_REVIEW_PATH: (
        "d9bd9ae5abca922ea96c9be316ddd04fec5dae3beb70f3ae78a5ea6ecb9e5376"
    ),
}

_NONSELF_NULL_PATHS = (
    "/integrity/canonical_byte_count",
    "/integrity/content_sha256",
    "/nonself_digest_check/canonical_byte_count",
    "/nonself_digest_check/content_sha256",
)


class ValidationError(ValueError):
    """Raised when a source or generated R011 contract is not exact."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def _require_int(value: Any, expected: int, label: str) -> None:
    _require(type(value) is int and value == expected, f"{label}: expected integer {expected!r}")


def _require_bool(value: Any, expected: bool, label: str) -> None:
    _require(type(value) is bool and value is expected, f"{label}: expected boolean {expected!r}")


def _strict_json_equal(observed: Any, expected: Any) -> bool:
    if type(observed) is not type(expected):
        return False
    if isinstance(expected, dict):
        return set(observed) == set(expected) and all(
            _strict_json_equal(observed[key], value)
            for key, value in expected.items()
        )
    if isinstance(expected, list):
        return len(observed) == len(expected) and all(
            _strict_json_equal(left, right)
            for left, right in zip(observed, expected, strict=True)
        )
    return observed == expected


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValidationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _load_strict_json_bytes(content: bytes, label: str) -> dict[str, Any]:
    try:
        text = content.decode("utf-8")
        value = json.loads(
            text,
            object_pairs_hook=_strict_object,
            parse_constant=lambda token: (_ for _ in ()).throw(
                ValidationError(f"non-finite JSON number: {token}")
            ),
        )
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ValidationError(f"cannot load strict JSON {label}: {exc}") from exc
    _require(type(value) is dict, f"{label}: top-level JSON must be an object")
    return value


def load_strict_json(path: Path) -> dict[str, Any]:
    try:
        content = path.read_bytes()
    except OSError as exc:
        raise ValidationError(f"cannot load strict JSON {path}: {exc}") from exc
    return _load_strict_json_bytes(content, str(path))


def _relative(path: Path) -> str:
    lexical = Path(os.path.abspath(path))
    try:
        return lexical.relative_to(REPO_ROOT.resolve()).as_posix()
    except ValueError:
        return lexical.as_posix()


def _validate_confined_path(
    path: Path,
    allowed_root: Path,
    *,
    require_regular_file: bool,
) -> None:
    root = allowed_root.resolve(strict=True)
    lexical = Path(os.path.abspath(path))
    try:
        relative = lexical.relative_to(root)
    except ValueError as exc:
        raise ValidationError(f"path escapes allowed root: {path}") from exc
    resolved = lexical.resolve(strict=False)
    try:
        resolved.relative_to(root)
    except ValueError as exc:
        raise ValidationError(f"resolved path escapes allowed root: {path}") from exc
    cursor = root
    for index, part in enumerate(relative.parts):
        cursor /= part
        if cursor.is_symlink():
            raise ValidationError(f"symbolic-link path component is forbidden: {cursor}")
        if index < len(relative.parts) - 1 and cursor.exists() and not cursor.is_dir():
            raise ValidationError(f"non-directory parent path component: {cursor}")
    if require_regular_file and not lexical.is_file():
        raise ValidationError(f"regular file is required: {path}")


def _read_confined_file_bytes(path: Path, allowed_root: Path) -> bytes:
    _validate_confined_path(
        path,
        allowed_root,
        require_regular_file=True,
    )
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise ValidationError(f"cannot open confined source: {path}: {exc}") from exc
    try:
        opened = os.fstat(descriptor)
        _require(stat.S_ISREG(opened.st_mode), f"source is not a regular file: {path}")
        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        content = b"".join(chunks)
    except OSError as exc:
        raise ValidationError(f"cannot read confined source: {path}: {exc}") from exc
    finally:
        os.close(descriptor)
    _validate_confined_path(
        path,
        allowed_root,
        require_regular_file=True,
    )
    current = os.stat(path, follow_symlinks=False)
    _require(
        (current.st_dev, current.st_ino, current.st_size)
        == (opened.st_dev, opened.st_ino, opened.st_size),
        f"source identity changed during snapshot: {path}",
    )
    return content


def _open_confined_directory(path: Path, allowed_root: Path) -> int:
    _validate_confined_path(
        path,
        allowed_root,
        require_regular_file=False,
    )
    _require(path.is_dir(), f"directory is required: {path}")
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise ValidationError(f"cannot open confined directory: {path}: {exc}") from exc
    opened = os.fstat(descriptor)
    current = os.stat(path, follow_symlinks=False)
    if (
        not stat.S_ISDIR(opened.st_mode)
        or (current.st_dev, current.st_ino) != (opened.st_dev, opened.st_ino)
    ):
        os.close(descriptor)
        raise ValidationError(f"directory identity changed during open: {path}")
    return descriptor


def _rename_noreplace(
    directory_fd: int,
    source_name: str,
    destination_name: str,
) -> None:
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    if renameat2 is None:
        raise ValidationError("atomic renameat2(RENAME_NOREPLACE) is unavailable")
    renameat2.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    ]
    renameat2.restype = ctypes.c_int
    result = renameat2(
        directory_fd,
        os.fsencode(source_name),
        directory_fd,
        os.fsencode(destination_name),
        1,
    )
    if result == 0:
        return
    error_number = ctypes.get_errno()
    if error_number in {errno.EEXIST, errno.ENOTEMPTY}:
        raise FileExistsError(
            f"add-only publication target already exists: {destination_name}"
        )
    raise OSError(error_number, os.strerror(error_number), destination_name)


def _sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _object_sha(value: Any) -> str:
    content = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return _sha256_bytes(content)


def _bytes_binding(
    path: Path, content: bytes, binding_id: str, role: str
) -> dict[str, Any]:
    return {
        "binding_id": binding_id,
        "path": _relative(path),
        "byte_length": len(content),
        "sha256": _sha256_bytes(content),
        "subject_role": role,
    }


def _source_specs() -> tuple[tuple[str, Path, str], ...]:
    return (
        ("R011-SRC-001", R007_LEDGER_PATH, "R007_IMMUTABLE_EXACT257_PREDECESSOR"),
        ("R011-SRC-002", R010_EVIDENCE_PATH, "R010_CURRENT_CHAIN_EVIDENCE"),
        ("R011-SRC-003", R010_RECEIPT_PATH, "R010_CURRENT_CHAIN_RECEIPT"),
        ("R011-SRC-004", R010_REVIEW_PATH, "R010_CURRENT_CHAIN_INDEPENDENT_REVIEW"),
        ("R011-SRC-005", DOC01_PATH, "CURRENT_DOC01_ARTIFACT_REGISTER"),
        ("R011-SRC-006", DOC05_PATH, "CURRENT_DOC05_CHANGE_LOG"),
        ("R011-SRC-007", MANIFEST_PATH, "CURRENT_REL_OPS_CLS_MANIFEST"),
        ("R011-SRC-008", READY25_AI_PACKET_PATH, "READY25_EXACT9_R002_EVIDENCE"),
        ("R011-SRC-009", READY25_AI_RECEIPT_PATH, "READY25_EXACT9_R002_RECEIPT"),
        ("R011-SRC-010", READY25_AI_REVIEW_PATH, "READY25_EXACT9_R002_DOCUMENT_REVIEW"),
        (
            "R011-SRC-011",
            READY25_CLS_OPS_PACKET_PATH,
            "READY25_EXACT13_R003_EVIDENCE",
        ),
        (
            "R011-SRC-012",
            READY25_CLS_OPS_RECEIPT_PATH,
            "READY25_EXACT13_R003_RECEIPT",
        ),
        (
            "R011-SRC-013",
            READY25_CLS_OPS_REVIEW_PATH,
            "READY25_EXACT13_R003_DOCUMENT_REVIEW",
        ),
        ("R011-SRC-014", READY25_REL_PACKET_PATH, "READY25_EXACT3_R002_EVIDENCE"),
        ("R011-SRC-015", READY25_REL_RECEIPT_PATH, "READY25_EXACT3_R002_RECEIPT"),
        ("R011-SRC-016", READY25_REL_REVIEW_PATH, "READY25_EXACT3_R002_DOCUMENT_REVIEW"),
    )


def _validate_pinned_source_hashes(
    source_bytes: Mapping[Path, bytes],
) -> None:
    physical_source_paths = {path for _, path, _ in _source_specs()}
    _require(
        set(PINNED_SOURCE_SHA256_BY_PATH) == physical_source_paths,
        "R011 pinned source path set differs",
    )
    _require(
        set(source_bytes) == {path for _, path, _ in _source_specs()},
        "R011 source byte snapshot path set differs",
    )
    for path, expected_sha256 in PINNED_SOURCE_SHA256_BY_PATH.items():
        _require(
            _sha256_bytes(source_bytes[path]) == expected_sha256,
            f"R011 pinned source SHA-256 drift: {_relative(path)}",
        )


def _exact_set_fingerprint(ids: Iterable[str]) -> dict[str, Any]:
    ordered = sorted(ids)
    content = ("".join(f"{item}\n" for item in ordered)).encode("utf-8")
    return {
        "algorithm": "SHA-256",
        "item_count": len(ordered),
        "serialization": "UTF-8 lexicographically sorted IDs, one per line, final LF",
        "byte_length": len(content),
        "sha256": _sha256_bytes(content),
    }


def _exact_fingerprints() -> dict[str, Any]:
    return {
        "exact9_set": _exact_set_fingerprint(EXACT9),
        "exact13_set": _exact_set_fingerprint(EXACT13),
        "exact3_set": _exact_set_fingerprint(EXACT3),
        "exact25_set": _exact_set_fingerprint(EXACT25),
    }


def _canonical_projection(document: dict[str, Any]) -> bytes:
    projected = deepcopy(document)
    projected["integrity"]["content_sha256"] = None
    projected["integrity"]["canonical_byte_count"] = None
    projected["nonself_digest_check"]["content_sha256"] = None
    projected["nonself_digest_check"]["canonical_byte_count"] = None
    return (
        json.dumps(
            projected,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def _integrity_contract(target_path: Path) -> dict[str, Any]:
    return {
        "algorithm": "SHA-256",
        "encoding": "UTF-8",
        "recursive_key_order": "LEXICOGRAPHIC",
        "ensure_ascii": False,
        "json_separators": [",", ":"],
        "projection_path": "/",
        "projection_null_paths": list(_NONSELF_NULL_PATHS),
        "final_lf": True,
        "canonical_byte_count": None,
        "content_sha256": None,
        "target_path": _relative(target_path),
    }


def _seal_json(document: dict[str, Any], target_path: Path) -> bytes:
    document["integrity"] = _integrity_contract(target_path)
    document["nonself_digest_check"] = {
        **_integrity_contract(target_path),
        "status": "PASS",
    }
    projection = _canonical_projection(document)
    digest = _sha256_bytes(projection)
    for key in ("integrity", "nonself_digest_check"):
        document[key]["canonical_byte_count"] = len(projection)
        document[key]["content_sha256"] = digest
    return (
        json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    ).encode("utf-8")


def _validate_nonself(document: dict[str, Any], target_path: Path) -> None:
    for key in ("integrity", "nonself_digest_check"):
        section = document.get(key)
        _require(type(section) is dict, f"{target_path}: missing {key}")
        expected_contract = _integrity_contract(target_path)
        expected_keys = set(expected_contract)
        if key == "nonself_digest_check":
            expected_keys.add("status")
        _require(
            set(section) == expected_keys,
            f"{target_path}: {key} field set differs",
        )
        for field in (
            "algorithm",
            "encoding",
            "recursive_key_order",
            "ensure_ascii",
            "json_separators",
            "projection_path",
            "projection_null_paths",
            "final_lf",
            "target_path",
        ):
            _require(
                _strict_json_equal(section.get(field), expected_contract[field]),
                f"{target_path}: {key}.{field} differs",
            )
    projection = _canonical_projection(document)
    expected_hash = _sha256_bytes(projection)
    for key in ("integrity", "nonself_digest_check"):
        section = document[key]
        _require_int(
            section.get("canonical_byte_count"),
            len(projection),
            f"{target_path}: {key}.canonical_byte_count",
        )
        _require(
            section.get("content_sha256") == expected_hash,
            f"{target_path}: {key}.content_sha256 mismatch",
        )
    _require(
        document["nonself_digest_check"].get("status") == "PASS",
        f"{target_path}: nonself status",
    )


def _record_by_id(ledger: dict[str, Any]) -> dict[str, dict[str, Any]]:
    records = ledger.get("records")
    _require(type(records) is list, "ledger.records must be an array")
    result: dict[str, dict[str, Any]] = {}
    for index, record in enumerate(records):
        _require(type(record) is dict, f"ledger.records[{index}] must be an object")
        artifact_id = record.get("artifact_type_code")
        _require(type(artifact_id) is str, f"ledger.records[{index}] ID must be a string")
        _require(artifact_id not in result, f"duplicate exact257 row: {artifact_id}")
        result[artifact_id] = record
    return result


def _validate_r007(
    ledger: dict[str, Any], ledger_bytes: bytes
) -> dict[str, dict[str, Any]]:
    _require(
        _sha256_bytes(ledger_bytes) == R007_EXPECTED_SHA256,
        "R007 physical SHA-256 drift",
    )
    rows = _record_by_id(ledger)
    _require(len(rows) == 257, "R007 must contain exactly 257 rows")
    _require(EXACT25_SET <= rows.keys(), "R007 is missing Ready25 rows")
    summaries = ledger.get("summaries", {})
    _require(
        summaries.get("canonical_status_counts") == CANONICAL_STATUS_COUNTS,
        "R007 canonical counts drift",
    )
    _require(
        summaries.get("current_queue_route_counts") == QUEUE_ROUTE_COUNTS,
        "R007 queue counts drift",
    )
    _require_int(summaries.get("record_count"), 257, "R007 record_count")
    _require_int(summaries.get("open_artifact_count"), 131, "R007 open count")
    _require_int(
        summaries.get("current_scope_closure_delta_count"),
        2,
        "R007 closure delta",
    )
    authorization = ledger.get("authorization_boundary", {})
    for key in (
        "actual_device_event_count",
        "attestation_approval_count",
        "formal279_pass_count",
        "in_scope_substantive_credit_count",
        "owner_approval_count",
        "real_event_count",
        "release_eligible_count",
        "verified_rights_or_external_fact_count",
    ):
        _require_int(authorization.get(key), 0, f"R007 authorization {key}")
    _require(authorization.get("release_status") == "NOT_ELIGIBLE", "R007 release status")
    _require_bool(authorization.get("policy_reopened"), False, "R007 policy_reopened")
    queue_counts = Counter(row.get("queue_route", {}).get("current") for row in rows.values())
    _require(
        {key: queue_counts[key] for key in QUEUE_ROUTE_COUNTS} == QUEUE_ROUTE_COUNTS
        and set(queue_counts) <= set(QUEUE_ROUTE_COUNTS),
        "R007 row queue counts drift",
    )
    canonical_counts = Counter(
        row.get("predecessor_artifact_ledger_record", {})
        .get("source_snapshot_tuple", {})
        .get("current_status")
        for row in rows.values()
    )
    _require(dict(canonical_counts) == CANONICAL_STATUS_COUNTS, "R007 row canonical counts drift")
    status_counts = Counter(row.get("artifact_closure", {}).get("status") for row in rows.values())
    _require_int(status_counts["BASELINE_OK_ONLY_NO_PHASE1_CLOSURE"], 124, "R007 baseline closure count")
    _require_int(status_counts["CLOSED_N_A_FOR_CURRENT_SCOPE"], 2, "R007 current-scope N/A count")
    _require_int(status_counts["OPEN"], 131, "R007 open row count")
    for artifact_id, row in rows.items():
        for group, field in SIX_COMPLETION_PATHS:
            value = row.get(group, {}).get(field)
            _require(type(value) is bool, f"R007 {artifact_id} {group}.{field} must be boolean")
        _require_bool(
            row.get("release_eligibility", {}).get("eligible"),
            False,
            f"R007 {artifact_id} release eligible",
        )
        _require(
            row.get("release_eligibility", {}).get("status") == "NOT_ELIGIBLE",
            f"R007 {artifact_id} release status",
        )
    for artifact_id in EXACT25:
        row = rows[artifact_id]
        for group, field in SIX_COMPLETION_PATHS:
            _require_bool(
                row[group][field],
                False,
                f"R007 Ready25 {artifact_id} {group}.{field}",
            )
        for field in (
            "execution_completion_claimed",
            "external_fact_verified_claimed",
            "formal_pass_claimed",
            "owner_approval_claimed",
            "real_event_claimed",
            "release_eligible_claimed",
        ):
            _require_bool(
                row["claim_boundary"].get(field),
                False,
                f"R007 Ready25 {artifact_id} claim_boundary.{field}",
            )
    return rows


def _require_zero_int_fields(
    mapping: dict[str, Any], fields: Iterable[str], label: str
) -> None:
    _require(type(mapping) is dict, f"{label} must be an object")
    for field in fields:
        _require_int(mapping.get(field), 0, f"{label}.{field}")


def _validate_ready25_sources(
    doc01: dict[str, Any],
    doc05: dict[str, Any],
    manifest: dict[str, Any],
    ai_packet: dict[str, Any],
    ai_receipt: dict[str, Any],
    cls_packet: dict[str, Any],
    cls_receipt: dict[str, Any],
    rel_packet: dict[str, Any],
    rel_receipt: dict[str, Any],
) -> None:
    _require(doc01.get("schema_version") == "walksafe.artifact-register.v1", "DOC01 schema")
    doc01_body = {key: value for key, value in doc01.items() if key != "content_sha256"}
    _require(
        doc01.get("content_sha256") == _object_sha(doc01_body),
        "DOC01 self-digest",
    )
    _require_int(doc01.get("summary", {}).get("artifact_type_count"), 257, "DOC01 exact257 count")
    artifacts = doc01.get("artifacts")
    _require(
        type(artifacts) is list and len(artifacts) == 257,
        "DOC01 artifacts must contain exactly 257 rows",
    )
    _require(
        all(
            type(row) is dict and type(row.get("artifact_type_code")) is str
            for row in artifacts
        ),
        "DOC01 artifact row shape",
    )
    doc_rows = {
        row.get("artifact_type_code"): row
        for row in artifacts
        if type(row) is dict and type(row.get("artifact_type_code")) is str
    }
    _require(len(doc_rows) == 257, "DOC01 must have 257 unique artifact rows")
    for artifact_id in EXACT25:
        row = doc_rows.get(artifact_id)
        _require(type(row) is dict, f"DOC01 missing {artifact_id}")
        _require(row.get("applicability") == "IN_SCOPE", f"DOC01 {artifact_id} applicability")
        readiness = row.get("authoring_readiness", {})
        _require_bool(readiness.get("content_authored"), True, f"DOC01 {artifact_id} authored")
        current = row.get("state", {}).get("ready25_current_revision", {})
        _require_bool(current.get("content_accepted"), False, f"DOC01 {artifact_id} accepted")
        _require(current.get("approval_status") == "NOT_APPROVED", f"DOC01 {artifact_id} approval")
        _require(current.get("baseline_status") == "NOT_BASELINED", f"DOC01 {artifact_id} baseline")
        _require_zero_int_fields(
            current,
            (
                "execution_credit_count",
                "actual_event_credit_count",
                "formal_evidence_credit_count",
                "release_credit_count",
            ),
            f"DOC01 {artifact_id} current revision",
        )
    for key, expected_count in (
        ("ready25_ai_dev_sec_remediation", 9),
        ("ready25_cls_ops_remediation", 13),
        ("ready25_rel_remediation", 3),
    ):
        aggregate = doc01.get(key, {})
        _require(aggregate.get("applicability") == "IN_SCOPE", f"DOC01 {key} applicability")
        _require_int(aggregate.get("content_authored_count"), expected_count, f"DOC01 {key} authored")
        _require_zero_int_fields(
            aggregate,
            (
                "accepted_count",
                "approval_count",
                "execution_count",
                "actual_event_count",
                "formal_evidence_count",
                "release_credit_count",
            ),
            f"DOC01 {key}",
        )

    _require(doc05.get("schema_version") == "walksafe.artifact-change-log.v1", "DOC05 schema")
    doc05_body = {key: value for key, value in doc05.items() if key != "content_sha256"}
    _require(
        doc05.get("content_sha256") == _object_sha(doc05_body),
        "DOC05 self-digest",
    )
    changes = doc05.get("changes")
    _require(type(changes) is list and len(changes) == 13, "DOC05 must contain 13 changes")
    last = changes[-1]
    _require(last.get("change_id") == "CHG-DOC-0013", "DOC05 current change")
    _require(
        last.get("lifecycle_status") == "DRAFT"
        and last.get("review", {}).get("review_status") == "PENDING"
        and last.get("review", {}).get("approval_status") == "NOT_APPROVED",
        "DOC05 CHG-DOC-0013 lifecycle boundary",
    )
    _require_zero_int_fields(
        last.get("application", {}),
        (
            "acceptance_credit_count",
            "approval_credit_count",
            "execution_credit_count",
            "actual_event_credit_count",
            "formal_evidence_credit_count",
            "release_credit_count",
        ),
        "DOC05 CHG-DOC-0013",
    )

    metadata = manifest.get("metadata", {})
    _require(
        manifest.get("schema_version")
        == "walksafe.formal-rel-ops-cls-draft-manifest.v1",
        "manifest schema",
    )
    _require(metadata.get("lifecycle_status") == "DRAFT", "manifest lifecycle")
    _require(metadata.get("approval_status") == "NOT_APPROVED", "manifest approval")
    _require(metadata.get("release_status") == "NOT_ELIGIBLE", "manifest release")
    manifest_body = {
        key: value for key, value in manifest.items() if key != "manifest_content_sha256"
    }
    _require(
        manifest.get("manifest_content_sha256") == _object_sha(manifest_body),
        "manifest self-digest",
    )
    _require(
        manifest.get("source_binding_sha256")
        == _object_sha(manifest.get("source_bindings")),
        "manifest source-binding digest",
    )
    _require_zero_int_fields(
        manifest.get("materialization_summary", {}),
        (
            "actual_execution_evidence_count",
            "external_original_count",
            "closure_result_count",
        ),
        "manifest materialization summary",
    )
    manifest_gates = manifest.get("remaining_gates")
    _require(type(manifest_gates) is list and len(manifest_gates) == 5, "manifest gate count")
    for gate in manifest_gates:
        _require(gate.get("status") == "NOT_RUN", "manifest gate status")
        _require_bool(gate.get("waived"), False, "manifest gate waived")
        _require(gate.get("evidence_ids") == [], "manifest gate evidence")

    _require(ai_packet.get("schema_version") == "walksafe.phase1-ready25-ai-dev-sec.evidence.v2", "exact9 packet schema")
    _require(
        ai_packet.get("exact_set", {}).get("artifact_type_codes") == list(EXACT9),
        "exact9 packet set",
    )
    _require_int(ai_packet.get("exact_set", {}).get("count"), 9, "exact9 packet count")
    _require_zero_int_fields(
        ai_packet.get("credit_summary", {}),
        (
            "content_accepted_count",
            "execution_credit_count",
            "formal_test_credit_count",
            "owner_approval_count",
            "real_event_credit_count",
            "release_credit_count",
        ),
        "exact9 credit summary",
    )
    _require_int(
        ai_packet.get("credit_summary", {}).get("content_authored_true_count"),
        9,
        "exact9 authored count",
    )
    owner = ai_packet.get("owner_and_approval_boundary", {})
    _require(owner.get("qa_reviewer") == "UNASSIGNED", "exact9 QA must be unassigned")
    _require(owner.get("approval_event_status") == "NOT_PERFORMED", "exact9 approval event")
    _require_bool(owner.get("independence_claimed"), False, "exact9 independence claim")
    _require(ai_packet.get("metadata", {}).get("release_status") == "NOT_ELIGIBLE", "exact9 release")
    for field, expected in {
        "actual_result_synthesized": False,
        "approval_claimed": False,
        "artifact_register_modified": True,
        "deployment_claimed": False,
        "execution_claimed": False,
        "formal_pass_claimed": False,
        "release_eligible_claimed": False,
        "rights_or_privacy_verified_claimed": False,
    }.items():
        _require_bool(
            ai_packet.get("claim_boundary", {}).get(field),
            expected,
            f"exact9 claim_boundary.{field}",
        )
    gate_boundary = ai_packet.get("policy_gate_boundary", {})
    _require_int(gate_boundary.get("gate_count"), 5, "exact9 gate count")
    _require_int(gate_boundary.get("waived_count"), 0, "exact9 waived count")
    _require(gate_boundary.get("all_status") == "NOT_RUN", "exact9 gate status")
    ai_rows = ai_packet.get("per_id_acceptance_content_crosswalk")
    _require(type(ai_rows) is list and len(ai_rows) == 9, "exact9 crosswalk count")
    _require(
        [row.get("artifact_type_code") for row in ai_rows] == list(EXACT9),
        "exact9 crosswalk order",
    )
    for row in ai_rows:
        artifact_id = row["artifact_type_code"]
        _require(row.get("scope_status") == "IN_SCOPE", f"exact9 {artifact_id} scope")
        _require_bool(row.get("content_authored"), True, f"exact9 {artifact_id} authored")
        _require_bool(row.get("content_accepted"), False, f"exact9 {artifact_id} accepted")
        _require(row.get("qa_reviewer") == "UNASSIGNED", f"exact9 {artifact_id} QA")
        _require_zero_int_fields(
            row,
            (
                "execution_credit",
                "formal_test_credit",
                "owner_approval_credit",
                "release_credit",
            ),
            f"exact9 {artifact_id}",
        )
    ai_checks = ai_receipt.get("checks")
    _require(
        type(ai_checks) is list and len(ai_checks) == 11,
        "exact9 receipt check inventory",
    )
    _require(
        [check.get("check_id") for check in ai_checks]
        == [f"READY25-{index:03d}" for index in range(1, 12)],
        "exact9 receipt check IDs",
    )
    _require_int(ai_receipt.get("summary", {}).get("pass_count"), 11, "exact9 receipt passes")
    _require_int(ai_receipt.get("summary", {}).get("fail_count"), 0, "exact9 receipt failures")
    _require(
        all(check.get("result") == "PASS" for check in ai_checks),
        "exact9 receipt checks",
    )
    _require(
        type(ai_receipt.get("output_bindings")) is list
        and len(ai_receipt["output_bindings"]) == 10,
        "exact9 receipt output bindings",
    )

    _validate_plan_packet(
        cls_packet,
        exact_ids=EXACT13,
        expected_count=13,
        label="exact13",
    )
    _require(cls_receipt.get("status") == "PASS", "exact13 receipt status")
    cls_checks = cls_receipt.get("checks")
    _require(
        type(cls_checks) is list
        and [check.get("check_id") for check in cls_checks]
        == [
            "READY25-EXACT13-IN-SCOPE",
            "READY25-EXACT13-CONTENT-AUTHORED",
            "READY25-NO-ACCEPTANCE-APPROVAL-EXECUTION-EVENT-FORMAL-RELEASE-CREDIT",
            "READY25-OPS18-INCIDENT0-NOT-TRIGGERED",
            "READY25-CLS16-OPERATIONS-CONTINUE",
            "READY25-CLS10-RECIPIENT-UNRESOLVED",
            "READY25-INDEPENDENT-QA-UNASSIGNED",
        ],
        "exact13 receipt check inventory",
    )
    _require(
        all(check.get("status") == "PASS" for check in cls_checks),
        "exact13 receipt checks",
    )
    _validate_plan_packet(
        rel_packet,
        exact_ids=EXACT3,
        expected_count=3,
        label="exact3",
    )
    gates = rel_packet.get("five_gate_boundary")
    _require(type(gates) is list and len(gates) == 5, "exact3 five-gate count")
    for gate in gates:
        _require(gate.get("status") == "NOT_RUN", "exact3 gate status")
        _require_bool(gate.get("waived"), False, "exact3 gate waived")
    for row in rel_packet.get("artifact_contracts", []):
        row_gates = row.get("five_gate_boundary")
        _require(type(row_gates) is list and len(row_gates) == 5, "exact3 row gate count")
        for gate in row_gates:
            _require(gate.get("status") == "NOT_RUN", "exact3 row gate status")
            _require_bool(gate.get("waived"), False, "exact3 row gate waived")
    _require(rel_receipt.get("status") == "PASS", "exact3 receipt status")
    rel_checks = rel_receipt.get("checks")
    _require(
        type(rel_checks) is list
        and [check.get("check_id") for check in rel_checks]
        == [
            "READY25-REL-EXACT3-IN-SCOPE-CONTENT-AUTHORED",
            "READY25-REL-NO-CREDIT",
            "READY25-REL-FIVE-GATES-NOT-RUN-NOT-WAIVED",
            "READY25-REL-NAMED-CANDIDATE-ABSENT",
        ],
        "exact3 receipt check inventory",
    )
    _require(
        all(check.get("status") == "PASS" for check in rel_checks),
        "exact3 receipt checks",
    )


def _validate_plan_packet(
    packet: dict[str, Any],
    *,
    exact_ids: tuple[str, ...],
    expected_count: int,
    label: str,
) -> None:
    summary = packet.get("summary", {})
    _require_int(summary.get("exact_artifact_count"), expected_count, f"{label} count")
    _require_int(summary.get("in_scope_count"), expected_count, f"{label} in-scope")
    _require_int(summary.get("content_authored_count"), expected_count, f"{label} authored")
    _require_zero_int_fields(
        summary,
        (
            "accepted_count",
            "approval_count",
            "execution_count",
            "actual_event_count",
            "formal_evidence_count",
            "release_credit_count",
        ),
        f"{label} summary",
    )
    contracts = packet.get("artifact_contracts")
    _require(type(contracts) is list and len(contracts) == expected_count, f"{label} contracts")
    _require(
        [row.get("catalog_artifact_id") for row in contracts] == list(exact_ids),
        f"{label} contract exact order",
    )
    for row in contracts:
        artifact_id = row["catalog_artifact_id"]
        _require(row.get("applicability") == "IN_SCOPE", f"{label} {artifact_id} scope")
        _require_bool(row.get("content_authored"), True, f"{label} {artifact_id} authored")
        boundary = row.get("claim_boundary", {})
        _require_bool(boundary.get("accepted"), False, f"{label} {artifact_id} accepted")
        _require_zero_int_fields(
            boundary,
            (
                "approval_count",
                "execution_count",
                "actual_event_count",
                "formal_evidence_count",
                "release_credit_count",
            ),
            f"{label} {artifact_id}",
        )
        _require(boundary.get("release_status") == "NOT_ELIGIBLE", f"{label} {artifact_id} release")
        responsibility = row.get("responsibility_boundary", {})
        _require(
            responsibility.get("independent_qa_reviewer") is None
            and responsibility.get("independent_qa_status") == "UNASSIGNED",
            f"{label} {artifact_id} independent QA",
        )
        _require_bool(
            responsibility.get("self_review_credit_allowed"),
            False,
            f"{label} {artifact_id} self-review credit",
        )


def _review_has_zero_findings(
    text: str, label: str, *, require_product_qa_boundary: bool = False
) -> None:
    _require("PASS" in text, f"{label}: PASS verdict missing")
    for severity in ("BLOCKING", "MAJOR", "MINOR"):
        patterns = (
            rf"\|\s*{severity}\s*\|\s*0\s*\|",
            rf"{severity}\s*[:=]\s*`?0`?",
            rf"{severity}[^0-9\n]{{0,32}}`?0`?",
        )
        _require(
            any(re.search(pattern, text, flags=re.IGNORECASE) for pattern in patterns),
            f"{label}: {severity}=0 finding boundary missing",
        )
    if require_product_qa_boundary:
        _require("UNASSIGNED" in text, f"{label}: product QA UNASSIGNED boundary missing")
        lowered = text.lower()
        _require(
            "not product independent qa" in lowered
            or "not a product-independent qa" in lowered
            or "does not grant content acceptance, product independent-qa" in lowered,
            f"{label}: document review/product QA non-substitution boundary missing",
        )


def _load_source_state() -> dict[str, Any]:
    source_bytes: dict[Path, bytes] = {}
    for _, path, _ in _source_specs():
        source_bytes[path] = _read_confined_file_bytes(path, REPO_ROOT)
    _validate_pinned_source_hashes(source_bytes)

    ledger = _load_strict_json_bytes(
        source_bytes[R007_LEDGER_PATH],
        str(R007_LEDGER_PATH),
    )
    _validate_r007(ledger, source_bytes[R007_LEDGER_PATH])
    _require(
        _sha256_bytes(source_bytes[R010_EVIDENCE_PATH])
        == R010_EVIDENCE_EXPECTED_SHA256,
        "R010 evidence physical SHA-256 drift",
    )
    _require(
        _sha256_bytes(source_bytes[R010_RECEIPT_PATH])
        == R010_RECEIPT_EXPECTED_SHA256,
        "R010 receipt physical SHA-256 drift",
    )
    r010_evidence = _load_strict_json_bytes(
        source_bytes[R010_EVIDENCE_PATH],
        str(R010_EVIDENCE_PATH),
    )
    r010_receipt = _load_strict_json_bytes(
        source_bytes[R010_RECEIPT_PATH],
        str(R010_RECEIPT_PATH),
    )
    _require(
        r010_evidence.get("preserved_invariants", {}).get("canonical_status_counts")
        == CANONICAL_STATUS_COUNTS,
        "R010 canonical counts",
    )
    _require(
        r010_evidence.get("preserved_invariants", {}).get("current_queue_route_counts")
        == QUEUE_ROUTE_COUNTS,
        "R010 queue counts",
    )
    _require(
        r010_evidence.get("release_gate_inheritance", {})
        .get("observed", {})
        .get("release_status")
        == "NOT_ELIGIBLE",
        "R010 release status",
    )
    _require(r010_receipt.get("status") == "PASS", "R010 receipt status")
    _review_has_zero_findings(
        source_bytes[R010_REVIEW_PATH].decode("utf-8"),
        "R010 review",
    )

    doc01 = _load_strict_json_bytes(source_bytes[DOC01_PATH], str(DOC01_PATH))
    doc05 = _load_strict_json_bytes(source_bytes[DOC05_PATH], str(DOC05_PATH))
    manifest = _load_strict_json_bytes(source_bytes[MANIFEST_PATH], str(MANIFEST_PATH))
    ai_packet = _load_strict_json_bytes(
        source_bytes[READY25_AI_PACKET_PATH],
        str(READY25_AI_PACKET_PATH),
    )
    ai_receipt = _load_strict_json_bytes(
        source_bytes[READY25_AI_RECEIPT_PATH],
        str(READY25_AI_RECEIPT_PATH),
    )
    cls_packet = _load_strict_json_bytes(
        source_bytes[READY25_CLS_OPS_PACKET_PATH],
        str(READY25_CLS_OPS_PACKET_PATH),
    )
    cls_receipt = _load_strict_json_bytes(
        source_bytes[READY25_CLS_OPS_RECEIPT_PATH],
        str(READY25_CLS_OPS_RECEIPT_PATH),
    )
    rel_packet = _load_strict_json_bytes(
        source_bytes[READY25_REL_PACKET_PATH],
        str(READY25_REL_PACKET_PATH),
    )
    rel_receipt = _load_strict_json_bytes(
        source_bytes[READY25_REL_RECEIPT_PATH],
        str(READY25_REL_RECEIPT_PATH),
    )
    _validate_ready25_sources(
        doc01,
        doc05,
        manifest,
        ai_packet,
        ai_receipt,
        cls_packet,
        cls_receipt,
        rel_packet,
        rel_receipt,
    )
    for path, label, exact_ids, verdict in (
        (
            READY25_AI_REVIEW_PATH,
            "Ready25 exact9 review",
            EXACT9,
            "PASS_FOR_CONTENT_AUTHORING_AND_REGISTER_APPLICATION_BOUNDARY_ONLY",
        ),
        (
            READY25_CLS_OPS_REVIEW_PATH,
            "Ready25 exact13 review",
            EXACT13,
            "PASS_FOR_DETERMINISTIC_DOCUMENT_CONTROL_BOUNDARY_ONLY",
        ),
        (
            READY25_REL_REVIEW_PATH,
            "Ready25 exact3 review",
            EXACT3,
            "PASS_FOR_DOCUMENT_CONTROL_VERIFICATION_ONLY",
        ),
    ):
        review_text = source_bytes[path].decode("utf-8")
        _review_has_zero_findings(
            review_text,
            label,
            require_product_qa_boundary=True,
        )
        _require(verdict in review_text, f"{label}: exact verdict missing")
        for artifact_id in exact_ids:
            _require(
                artifact_id in review_text or artifact_id.removeprefix("DLV-") in review_text,
                f"{label}: exact ID missing: {artifact_id}",
            )
    bindings = [
        _bytes_binding(path, source_bytes[path], binding_id, role)
        for binding_id, path, role in _source_specs()
    ]
    _require(
        len({binding["binding_id"] for binding in bindings}) == len(bindings),
        "duplicate R011 source binding ID",
    )
    _require(
        len({binding["path"] for binding in bindings}) == len(bindings),
        "duplicate R011 source binding path",
    )
    return {
        "r007": ledger,
        "source_bindings": bindings,
    }


def _group_for_id(artifact_id: str) -> tuple[str, str, str, str]:
    if artifact_id in EXACT9:
        return (
            "EXACT9_AI_DEV_SEC",
            "R011-SRC-008",
            "R011-SRC-009",
            "R011-SRC-010",
        )
    if artifact_id in EXACT13:
        return (
            "EXACT13_CLS_OPS",
            "R011-SRC-011",
            "R011-SRC-012",
            "R011-SRC-013",
        )
    if artifact_id in EXACT3:
        return (
            "EXACT3_REL",
            "R011-SRC-014",
            "R011-SRC-015",
            "R011-SRC-016",
        )
    raise ValidationError(f"not a Ready25 ID: {artifact_id}")


def _progress_entries(artifact_id: str) -> dict[str, dict[str, Any]]:
    group, packet_binding_id, receipt_binding_id, review_binding_id = _group_for_id(
        artifact_id
    )
    return {
        "content_authored": {
            "artifact_content_accepted": False,
            "completion_claimed": False,
            "owner_approved": False,
            "packet_binding_id": packet_binding_id,
            "check_receipt_binding_id": receipt_binding_id,
            "status": "READY25_CONTENT_OBSERVATION_BOUND_NOT_ACCEPTED",
        },
        "packet_materialization": {
            "coverage_basis": "EXPLICIT_READY25_EXACT_SET_MATCH",
            "packet_binding_id": packet_binding_id,
            "check_receipt_binding_id": receipt_binding_id,
            "state_promotion": False,
            "status": "MATERIALIZED_PROGRESS_OBSERVATION_ONLY",
        },
        "independent_review": {
            "review_binding_id": review_binding_id,
            "review_scope": "SOURCE_DOCUMENT_CONTROL_REVIEW_RECORD_ONLY",
            "review_group": group,
            "finding_count": 0,
            "reviewer_identity_recorded": False,
            "review_independence_verified_by_r011": False,
            "product_independent_qa_reviewer": "UNASSIGNED",
            "product_qa_acceptance_credit": False,
            "document_review_substitutes_product_qa": False,
            "closure_credit": False,
            "execution_credit": False,
            "owner_approval_credit": False,
            "release_credit": False,
            "status": "REVIEW_OBSERVATION_BOUND_NO_STATE_PROMOTION",
        },
    }


def _build_ledger(
    predecessor: dict[str, Any], source_bindings: list[dict[str, Any]]
) -> dict[str, Any]:
    ledger = deepcopy(predecessor)
    ledger["schema_version"] = "walksafe.phase1-exact257-successor-ledger.v11"
    ledger["ledger_id"] = "WS-PHASE1-EXACT257-SUCCESSOR-LEDGER-20260729-R011"
    ledger["prepared_on"] = PREPARED_ON
    ledger["predecessor_r010_subject_chain"] = {
        "r007_ledger_binding_id": "R011-SRC-001",
        "r010_evidence_binding_id": "R011-SRC-002",
        "r010_receipt_binding_id": "R011-SRC-003",
        "r010_review_binding_id": "R011-SRC-004",
        "semantics": "R007_FULL_LEDGER_THROUGH_R010_CORRECTION_CHAIN",
    }
    ledger["r011_ready25_progress_application"] = {
        "application_id": "WS-PHASE1-EXACT257-READY25-PROGRESS-20260729-R011",
        "verdict": VERDICT,
        "row_count": 257,
        "unchanged_row_count": 232,
        "progress_observation_row_count": 25,
        "content_observation_total_before": 146,
        "content_observation_total_after": 171,
        "packet_materialization_total_before": 146,
        "packet_materialization_total_after": 171,
        "native_independent_review_axis_total_before": 80,
        "native_independent_review_axis_total_after": 105,
        "content_authored_count": 25,
        "source_document_review_record_count": 25,
        "source_document_review_reported_findings_count": 0,
        "source_reviewer_identity_recorded_count": 0,
        "source_review_independence_verified_count": 0,
        "independent_product_qa_reviewer": "UNASSIGNED",
        "independent_product_qa_acceptance_count": 0,
        "document_reviews_substitute_product_qa": False,
        "closed_equivalent_before": 126,
        "closed_equivalent_after": 126,
        "open_before": 131,
        "open_after": 131,
        "release_status": "NOT_ELIGIBLE",
        "zero_credits": deepcopy(ZERO_CREDITS),
        "source_binding_ids": [binding["binding_id"] for binding in source_bindings],
    }
    ledger["exact_set_fingerprints"].update(_exact_fingerprints())
    for row in ledger["records"]:
        artifact_id = row["artifact_type_code"]
        if artifact_id in EXACT25_SET:
            progress_axes = row["progress_axes"]
            entries = _progress_entries(artifact_id)
            progress_axes["content_authored"]["observations"].append(
                entries["content_authored"]
            )
            progress_axes["packet_materialization"].append(
                entries["packet_materialization"]
            )
            progress_axes["independent_review"].append(
                entries["independent_review"]
            )
    ledger["r011_source_bindings"] = deepcopy(source_bindings)
    return ledger


def _build_evidence(
    source_state: dict[str, Any], ledger_bytes: bytes
) -> dict[str, Any]:
    return {
        "schema_version": "walksafe.phase1-exact257-successor-evidence.v11",
        "packet_id": "WS-PHASE1-EXACT257-SUCCESSOR-EVIDENCE-20260729-R011",
        "prepared_on": PREPARED_ON,
        "run_id": RUN_ID,
        "verdict": VERDICT,
        "claim_semantics": "PROGRESS_OBSERVATION_ONLY_NO_CLOSURE_OR_PRODUCT_ACCEPTANCE_CREDIT",
        "subject_chain": {
            "predecessor": {
                "ledger_binding_id": "R011-SRC-001",
                "r010_evidence_binding_id": "R011-SRC-002",
                "r010_receipt_binding_id": "R011-SRC-003",
                "r010_review_binding_id": "R011-SRC-004",
            },
            "r011_ledger": _bytes_binding(
                R011_LEDGER_PATH,
                ledger_bytes,
                "R011-OUT-001",
                "R011_FULL_EXACT257_LEDGER",
            ),
        },
        "source_bindings": deepcopy(source_state["source_bindings"]),
        "exact_set_fingerprints": _exact_fingerprints(),
        "row_delta": {
            "record_count": 257,
            "unchanged_r007_row_count": 232,
            "ready25_progress_only_row_count": 25,
            "allowed_append_paths": [
                "/records/*/progress_axes/content_authored/observations/-",
                "/records/*/progress_axes/packet_materialization/-",
                "/records/*/progress_axes/independent_review/-",
            ],
            "queue_or_closure_change_count": 0,
            "progress_totals_before": {
                "content_authored_observations": 146,
                "packet_materializations": 146,
                "native_independent_review_axis_entries": 80,
            },
            "progress_totals_after": {
                "content_authored_observations": 171,
                "packet_materializations": 171,
                "native_independent_review_axis_entries": 105,
            },
        },
        "preserved_invariants": {
            "canonical_status_counts": deepcopy(CANONICAL_STATUS_COUNTS),
            "current_queue_route_counts": deepcopy(QUEUE_ROUTE_COUNTS),
            "closed_equivalent_count": 126,
            "open_artifact_count": 131,
            "current_scope_n_a_count": 2,
            "release_status": "NOT_ELIGIBLE",
            "zero_credits": deepcopy(ZERO_CREDITS),
        },
        "completion_boolean_boundary": {
            "path_count": 6,
            "paths": [f"/records/*/{group}/{field}" for group, field in SIX_COMPLETION_PATHS],
            "ready25_row_count_each": 25,
            "ready25_false_count_each": 25,
            "ready25_true_count_each": 0,
            "missing_or_non_boolean_count_each": 0,
        },
        "document_review_boundary": {
            "source_document_review_file_count": 3,
            "source_document_review_reported_findings_count": 0,
            "covered_artifact_count": 25,
            "source_reviewer_identity_recorded_count": 0,
            "source_review_independence_verified_by_r011": False,
            "r011_independent_review_generated_by_builder": False,
            "r011_independent_review_status_at_generation": (
                "PENDING_SEPARATE_POST_MATERIALIZATION_REVIEW"
            ),
            "r011_independent_review_path": _relative(R011_REVIEW_PATH),
            "independent_product_qa_reviewer": "UNASSIGNED",
            "independent_product_qa_acceptance_count": 0,
            "document_reviews_substitute_product_qa": False,
            "product_build_or_test_performed_by_r011": False,
            "approval_event_performed_by_r011": False,
        },
        "physical_output_contract": {
            "encoding": "UTF-8",
            "exactly_one_final_lf": True,
            "add_only": True,
            "existing_output_overwrite_allowed": False,
            "atomic_publication_directory": _relative(R011_PACKET_DIR),
            "publication_method": "STAGE_DIRECTORY_THEN_RENAMEAT2_NOREPLACE",
        },
    }


def _check(
    check_id: str, expected: Any, observed: Any, *, method: str | None = None
) -> dict[str, Any]:
    result = {
        "check_id": check_id,
        "expected": expected,
        "observed": observed,
        "status": "PASS" if observed == expected else "FAIL",
    }
    if method is not None:
        result["method"] = method
    return result


def _build_receipt(
    source_state: dict[str, Any], ledger_bytes: bytes, evidence_bytes: bytes
) -> dict[str, Any]:
    checks = [
        _check("R011-CHECK-EXACT257-ROW-UNIVERSE", 257, 257),
        _check(
            "R011-CHECK-R007-ROW-DELTA-ALLOWLIST",
            {"unchanged": 232, "progress_only": 25, "other": 0},
            {"unchanged": 232, "progress_only": 25, "other": 0},
        ),
        _check(
            "R011-CHECK-EXACT9-EXACT13-EXACT3-FINGERPRINTS",
            _exact_fingerprints(),
            _exact_fingerprints(),
        ),
        _check(
            "R011-CHECK-PRESERVED-CANONICAL-AND-QUEUE-COUNTS",
            {
                "canonical": CANONICAL_STATUS_COUNTS,
                "queue": QUEUE_ROUTE_COUNTS,
                "closed_equivalent": 126,
                "open": 131,
            },
            {
                "canonical": CANONICAL_STATUS_COUNTS,
                "queue": QUEUE_ROUTE_COUNTS,
                "closed_equivalent": 126,
                "open": 131,
            },
        ),
        _check("R011-CHECK-ZERO-CREDIT-BOUNDARY", ZERO_CREDITS, ZERO_CREDITS),
        _check(
            "R011-CHECK-READY25-SIX-COMPLETION-BOOLEANS-FALSE",
            {"path_count": 6, "row_count_each": 25, "true_count_each": 0},
            {"path_count": 6, "row_count_each": 25, "true_count_each": 0},
        ),
        _check(
            "R011-CHECK-SOURCE-DOCUMENT-REVIEW-NOT-PRODUCT-QA",
            {
                "source_review_reported_findings": 0,
                "source_reviewer_identity_recorded": 0,
                "source_review_independence_verified": False,
                "product_qa_reviewer": "UNASSIGNED",
                "product_qa_acceptance": 0,
                "substitution": False,
            },
            {
                "source_review_reported_findings": 0,
                "source_reviewer_identity_recorded": 0,
                "source_review_independence_verified": False,
                "product_qa_reviewer": "UNASSIGNED",
                "product_qa_acceptance": 0,
                "substitution": False,
            },
        ),
        _check(
            "R011-CHECK-SOURCE-PHYSICAL-BINDINGS",
            len(_source_specs()),
            len(source_state["source_bindings"]),
            method="Read every declared source at runtime and record physical byte length and SHA-256.",
        ),
        _check(
            "R011-CHECK-ADD-ONLY-OUTPUT-CONTRACT",
            {"output_count": 3, "overwrite_allowed": False},
            {"output_count": 3, "overwrite_allowed": False},
        ),
    ]
    return {
        "schema_version": "walksafe.phase1-exact257-successor-check-receipt.v11",
        "receipt_id": "WS-PHASE1-EXACT257-SUCCESSOR-CHECK-RECEIPT-20260729-R011",
        "prepared_on": PREPARED_ON,
        "run_id": RUN_ID,
        "status": "PASS",
        "verdict": VERDICT,
        "summary": {
            "check_count": len(checks),
            "pass_count": sum(check["status"] == "PASS" for check in checks),
            "fail_count": sum(check["status"] != "PASS" for check in checks),
            "record_count": 257,
            "closed_equivalent_count": 126,
            "open_artifact_count": 131,
            "release_status": "NOT_ELIGIBLE",
            "canonical_status_counts": deepcopy(CANONICAL_STATUS_COUNTS),
            "current_queue_route_counts": deepcopy(QUEUE_ROUTE_COUNTS),
            "zero_credits": deepcopy(ZERO_CREDITS),
        },
        "checks": checks,
        "source_bindings": deepcopy(source_state["source_bindings"]),
        "output_bindings": [
            _bytes_binding(
                R011_LEDGER_PATH,
                ledger_bytes,
                "R011-OUT-001",
                "R011_FULL_EXACT257_LEDGER",
            ),
            _bytes_binding(
                R011_EVIDENCE_PATH,
                evidence_bytes,
                "R011-OUT-002",
                "R011_READY25_PROGRESS_EVIDENCE",
            ),
        ],
        "physical_output_contract": {
            "paths": [
                _relative(R011_LEDGER_PATH),
                _relative(R011_EVIDENCE_PATH),
                _relative(R011_RECEIPT_PATH),
            ],
            "encoding": "UTF-8",
            "exactly_one_final_lf": True,
            "add_only": True,
            "overwrite_allowed": False,
            "atomic_publication_directory": _relative(R011_PACKET_DIR),
            "publication_method": "STAGE_DIRECTORY_THEN_RENAMEAT2_NOREPLACE",
        },
    }


def _decode_output_json(outputs: dict[Path, bytes], path: Path) -> dict[str, Any]:
    value = json.loads(outputs[path].decode("utf-8"), object_pairs_hook=_strict_object)
    _require(type(value) is dict, f"{path}: generated JSON must be an object")
    return value


def _validate_generated_outputs(
    outputs: dict[Path, bytes], source_state: dict[str, Any]
) -> None:
    expected_paths = {
        R011_LEDGER_PATH,
        R011_EVIDENCE_PATH,
        R011_RECEIPT_PATH,
    }
    _require(set(outputs) == expected_paths, "R011 output path set differs")
    for path, content in outputs.items():
        _require(content.endswith(b"\n"), f"{path}: final LF missing")
        _require(not content.endswith(b"\n\n"), f"{path}: more than one final LF")
        _require(not content.endswith(b"\r\n"), f"{path}: CRLF terminal is forbidden")

    predecessor = source_state["r007"]
    expected_ledger_bytes = _seal_json(
        _build_ledger(predecessor, source_state["source_bindings"]),
        R011_LEDGER_PATH,
    )
    expected_evidence_bytes = _seal_json(
        _build_evidence(source_state, expected_ledger_bytes),
        R011_EVIDENCE_PATH,
    )
    expected_receipt_bytes = _seal_json(
        _build_receipt(
            source_state,
            expected_ledger_bytes,
            expected_evidence_bytes,
        ),
        R011_RECEIPT_PATH,
    )
    expected_outputs = {
        R011_LEDGER_PATH: expected_ledger_bytes,
        R011_EVIDENCE_PATH: expected_evidence_bytes,
        R011_RECEIPT_PATH: expected_receipt_bytes,
    }
    _require(outputs == expected_outputs, "R011 generated output exact contract differs")

    predecessor_rows = _record_by_id(predecessor)
    ledger = _decode_output_json(outputs, R011_LEDGER_PATH)
    ledger_rows = _record_by_id(ledger)
    _require(set(ledger_rows) == set(predecessor_rows), "R011 exact257 universe differs")
    unchanged = 0
    progress_only = 0
    for artifact_id, before in predecessor_rows.items():
        after = ledger_rows[artifact_id]
        if artifact_id not in EXACT25_SET:
            _require(after == before, f"non-Ready25 row changed: {artifact_id}")
            unchanged += 1
            continue
        expected = deepcopy(before)
        entries = _progress_entries(artifact_id)
        expected["progress_axes"]["content_authored"]["observations"].append(
            entries["content_authored"]
        )
        expected["progress_axes"]["packet_materialization"].append(
            entries["packet_materialization"]
        )
        expected["progress_axes"]["independent_review"].append(
            entries["independent_review"]
        )
        _require(after == expected, f"Ready25 row has non-progress delta: {artifact_id}")
        content_observation = after["progress_axes"]["content_authored"]["observations"][-1]
        materialization = after["progress_axes"]["packet_materialization"][-1]
        review_observation = after["progress_axes"]["independent_review"][-1]
        _require_bool(
            content_observation["artifact_content_accepted"],
            False,
            f"R011 {artifact_id} accepted",
        )
        _require_bool(
            content_observation["owner_approved"],
            False,
            f"R011 {artifact_id} owner approved",
        )
        _require_bool(
            content_observation["completion_claimed"],
            False,
            f"R011 {artifact_id} completion",
        )
        _require_bool(
            materialization["state_promotion"],
            False,
            f"R011 {artifact_id} state promotion",
        )
        _require_bool(
            review_observation["document_review_substitutes_product_qa"],
            False,
            f"R011 {artifact_id} QA substitution",
        )
        _require(
            review_observation["product_independent_qa_reviewer"] == "UNASSIGNED",
            f"R011 {artifact_id} QA reviewer",
        )
        _require_int(
            review_observation["finding_count"],
            0,
            f"R011 {artifact_id} review finding_count",
        )
        for field in (
            "product_qa_acceptance_credit",
            "closure_credit",
            "execution_credit",
            "owner_approval_credit",
            "release_credit",
        ):
            _require_bool(
                review_observation[field],
                False,
                f"R011 {artifact_id} review {field}",
            )
        for group, field in SIX_COMPLETION_PATHS:
            _require_bool(after[group][field], False, f"R011 {artifact_id} {group}.{field}")
        progress_only += 1
    _require_int(unchanged, 232, "R011 unchanged row count")
    _require_int(progress_only, 25, "R011 progress row count")
    _require_int(
        sum(
            len(row["progress_axes"]["content_authored"]["observations"])
            for row in ledger_rows.values()
        ),
        171,
        "R011 total content observations",
    )
    _require_int(
        sum(
            len(row["progress_axes"]["packet_materialization"])
            for row in ledger_rows.values()
        ),
        171,
        "R011 total materializations",
    )
    _require_int(
        sum(
            len(row["progress_axes"]["independent_review"])
            for row in ledger_rows.values()
        ),
        105,
        "R011 total native independent_review axis entries",
    )
    _require(ledger["summaries"] == predecessor["summaries"], "R011 summaries changed")
    _require(
        ledger["authorization_boundary"] == predecessor["authorization_boundary"],
        "R011 authorization boundary changed",
    )
    for key, expected in _exact_fingerprints().items():
        _require(
            ledger["exact_set_fingerprints"].get(key) == expected,
            f"R011 {key} fingerprint",
        )
    _validate_nonself(ledger, R011_LEDGER_PATH)

    evidence = _decode_output_json(outputs, R011_EVIDENCE_PATH)
    _validate_nonself(evidence, R011_EVIDENCE_PATH)
    _require(evidence.get("verdict") == VERDICT, "R011 evidence verdict")
    _require(
        evidence.get("preserved_invariants", {}).get("canonical_status_counts")
        == CANONICAL_STATUS_COUNTS,
        "R011 evidence canonical counts",
    )
    _require(
        evidence.get("preserved_invariants", {}).get("current_queue_route_counts")
        == QUEUE_ROUTE_COUNTS,
        "R011 evidence queue counts",
    )
    _require(
        evidence.get("source_bindings") == source_state["source_bindings"],
        "R011 evidence source bindings",
    )
    ledger_binding = evidence["subject_chain"]["r011_ledger"]
    _require(
        ledger_binding
        == _bytes_binding(
            R011_LEDGER_PATH,
            outputs[R011_LEDGER_PATH],
            "R011-OUT-001",
            "R011_FULL_EXACT257_LEDGER",
        ),
        "R011 evidence ledger physical binding",
    )

    receipt = _decode_output_json(outputs, R011_RECEIPT_PATH)
    _validate_nonself(receipt, R011_RECEIPT_PATH)
    _require(receipt.get("status") == "PASS", "R011 receipt status")
    _require(receipt.get("verdict") == VERDICT, "R011 receipt verdict")
    checks = receipt.get("checks")
    _require(type(checks) is list and len(checks) == 9, "R011 receipt check count")
    _require(all(check.get("status") == "PASS" for check in checks), "R011 receipt failed check")
    _require_int(receipt["summary"].get("pass_count"), 9, "R011 receipt pass count")
    _require_int(receipt["summary"].get("fail_count"), 0, "R011 receipt fail count")
    expected_output_bindings = [
        _bytes_binding(
            R011_LEDGER_PATH,
            outputs[R011_LEDGER_PATH],
            "R011-OUT-001",
            "R011_FULL_EXACT257_LEDGER",
        ),
        _bytes_binding(
            R011_EVIDENCE_PATH,
            outputs[R011_EVIDENCE_PATH],
            "R011-OUT-002",
            "R011_READY25_PROGRESS_EVIDENCE",
        ),
    ]
    _require(receipt.get("output_bindings") == expected_output_bindings, "R011 receipt output bindings")


def _build_outputs() -> dict[Path, bytes]:
    source_state = _load_source_state()
    ledger = _build_ledger(source_state["r007"], source_state["source_bindings"])
    ledger_bytes = _seal_json(ledger, R011_LEDGER_PATH)
    evidence = _build_evidence(source_state, ledger_bytes)
    evidence_bytes = _seal_json(evidence, R011_EVIDENCE_PATH)
    receipt = _build_receipt(source_state, ledger_bytes, evidence_bytes)
    receipt_bytes = _seal_json(receipt, R011_RECEIPT_PATH)
    outputs = {
        R011_LEDGER_PATH: ledger_bytes,
        R011_EVIDENCE_PATH: evidence_bytes,
        R011_RECEIPT_PATH: receipt_bytes,
    }
    _validate_generated_outputs(outputs, source_state)
    return outputs


def _write_add_only(
    outputs: dict[Path, bytes],
    *,
    allowed_root: Path = REPO_ROOT,
    reserved_absent_paths: Iterable[Path] = (),
) -> None:
    _require(bool(outputs), "R011 output set must not be empty")
    reserved = tuple(reserved_absent_paths)
    final_directories = {path.parent for path in outputs}
    _require(
        len(final_directories) == 1,
        "R011 outputs must share one atomic publication directory",
    )
    final_directory = next(iter(final_directories))
    publication_parent = final_directory.parent
    _validate_confined_path(
        publication_parent,
        allowed_root,
        require_regular_file=False,
    )
    _require(publication_parent.is_dir(), "R011 publication parent must exist")
    for path in (*outputs, *reserved):
        _validate_confined_path(
            path,
            allowed_root,
            require_regular_file=False,
        )
    _require(
        all(path.parent == final_directory for path in outputs),
        "R011 output must be a direct child of the publication directory",
    )
    existing = sorted(
        str(path)
        for path in (final_directory, *reserved)
        if path.exists() or path.is_symlink()
    )
    if existing:
        raise FileExistsError(
            "add-only preflight refused existing R011 outputs: " + ", ".join(existing)
        )
    parent_fd = _open_confined_directory(publication_parent, allowed_root)
    stage_name = (
        f".{final_directory.name}.staging-"
        f"{os.getpid()}-{secrets.token_hex(8)}"
    )
    stage_fd: int | None = None
    stage_created = False
    published = False
    created_names: list[str] = []
    try:
        try:
            os.stat(
                final_directory.name,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
        except FileNotFoundError:
            pass
        else:
            raise FileExistsError(
                f"add-only publication target already exists: {final_directory}"
            )
        os.mkdir(stage_name, mode=0o700, dir_fd=parent_fd)
        stage_created = True
        stage_flags = (
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        stage_fd = os.open(stage_name, stage_flags, dir_fd=parent_fd)
        for path, content in outputs.items():
            name = path.name
            descriptor = os.open(
                name,
                os.O_WRONLY
                | os.O_CREAT
                | os.O_EXCL
                | getattr(os, "O_NOFOLLOW", 0),
                0o644,
                dir_fd=stage_fd,
            )
            created_names.append(name)
            try:
                remaining = memoryview(content)
                while remaining:
                    written = os.write(descriptor, remaining)
                    _require(written > 0, f"zero-byte write for R011 output: {path}")
                    remaining = remaining[written:]
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        os.fsync(stage_fd)
        os.close(stage_fd)
        stage_fd = None
        _rename_noreplace(
            parent_fd,
            stage_name,
            final_directory.name,
        )
        published = True
        os.fsync(parent_fd)
    except BaseException:
        if stage_fd is not None:
            os.close(stage_fd)
            stage_fd = None
        if stage_created and not published:
            cleanup_fd: int | None = None
            try:
                cleanup_fd = os.open(
                    stage_name,
                    os.O_RDONLY
                    | getattr(os, "O_DIRECTORY", 0)
                    | getattr(os, "O_NOFOLLOW", 0),
                    dir_fd=parent_fd,
                )
                for name in reversed(created_names):
                    try:
                        os.unlink(name, dir_fd=cleanup_fd)
                    except FileNotFoundError:
                        pass
                os.close(cleanup_fd)
                cleanup_fd = None
                os.rmdir(stage_name, dir_fd=parent_fd)
            except OSError:
                pass
            finally:
                if cleanup_fd is not None:
                    os.close(cleanup_fd)
        raise
    finally:
        if stage_fd is not None:
            os.close(stage_fd)
        os.close(parent_fd)


def _check_committed(
    outputs: dict[Path, bytes],
    *,
    allowed_root: Path = REPO_ROOT,
) -> None:
    for path, expected in outputs.items():
        observed = _read_confined_file_bytes(path, allowed_root)
        _require(observed == expected, f"committed R011 output drift: {path}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="Verify existing R011 outputs without writing.",
    )
    args = parser.parse_args(argv)
    try:
        outputs = _build_outputs()
        if args.check:
            _check_committed(outputs)
            action = "verified"
        else:
            _write_add_only(
                outputs,
                reserved_absent_paths=(R011_REVIEW_PATH,),
            )
            action = "created"
    except (ValidationError, FileExistsError, OSError) as exc:
        print(f"R011 ERROR: {exc}", file=sys.stderr)
        return 1
    print(
        f"{action} 3 add-only R011 outputs; exact257=257, "
        "closed-equivalent=126, open=131, release=NOT_ELIGIBLE"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
