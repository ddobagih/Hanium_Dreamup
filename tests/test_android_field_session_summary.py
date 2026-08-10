import importlib.util
import io
import json
import os
import stat
import sys
import tarfile
from pathlib import Path

import pytest


SCRIPT = Path(__file__).parents[1] / "scripts" / "summarize_android_field_sessions_20260710.py"
SPEC = importlib.util.spec_from_file_location("android_field_summary", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

PULL_SCRIPT = Path(__file__).parents[1] / "scripts" / "pull_android_field_sessions_20260710.py"
PULL_SPEC = importlib.util.spec_from_file_location("android_field_pull", PULL_SCRIPT)
assert PULL_SPEC is not None and PULL_SPEC.loader is not None
PULL_MODULE = importlib.util.module_from_spec(PULL_SPEC)
sys.modules["summarize_android_field_sessions_20260710"] = MODULE
PULL_SPEC.loader.exec_module(PULL_MODULE)

SOURCE_COMMIT = "1" * 40
APK_SHA256 = "2" * 64
MODEL_CONFIG_SHA256 = "3" * 64


def aead_envelope(*, ciphertext: str = "MDEyMzQ1Njc4OWFiY2RlZg==") -> dict:
    return {
        "envelope_version": 1,
        "key_version": 1,
        "iv": "MDEyMzQ1Njc4OWFi",
        "ciphertext": ciphertext,
    }


def tar_archive_bytes(entries: dict[str, str]) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:") as archive:
        directories = {"field_sessions"}
        for name in entries:
            parts = Path(name).parts
            directories.update("/".join(parts[:index]) for index in range(2, len(parts)))
        for directory in sorted(directories, key=lambda value: (value.count("/"), value)):
            member = tarfile.TarInfo(directory)
            member.type = tarfile.DIRTYPE
            member.size = 0
            archive.addfile(member)
        for name, value in entries.items():
            payload = value.encode("utf-8")
            member = tarfile.TarInfo(name)
            member.size = len(payload)
            archive.addfile(member, io.BytesIO(payload))
    return buffer.getvalue()


def tar_archive_sequence(entries: list[tuple[str, str, str]]) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:") as archive:
        for name, value, kind in entries:
            member = tarfile.TarInfo(name)
            if kind == "directory":
                member.type = tarfile.DIRTYPE
                member.size = 0
                archive.addfile(member)
            else:
                payload = value.encode("utf-8")
                member.size = len(payload)
                archive.addfile(member, io.BytesIO(payload))
    return buffer.getvalue()


def write_session(
    root: Path,
    record: dict | list[dict],
    *,
    session_id: str = "field-test",
    started_at: int = 1000,
    ended_at: int | None = None,
    provenance: dict | None = None,
) -> Path:
    """Write a legacy/history-only plaintext fixture."""

    session = root / "field_sessions" / session_id
    session.mkdir(parents=True)
    records = record if isinstance(record, list) else [record]
    record_times = [
        item.get("recorded_at_epoch_ms")
        for item in records
        if type(item.get("recorded_at_epoch_ms")) is int
    ]
    resolved_ended_at = ended_at if ended_at is not None else max([started_at + 1000, *record_times])
    (session / "manifest.json").write_text(
        json.dumps(
            {
                "schema_version": "android.field_session.v1",
                "session_id": session_id,
                "status": "completed",
                "started_at_epoch_ms": started_at,
                "ended_at_epoch_ms": resolved_ended_at,
                "device": {"model": "Test", "android_version": "16", "app_version_name": "0.1.0"},
                "provenance": provenance
                or {
                    "source_commit": SOURCE_COMMIT,
                    "apk_sha256": APK_SHA256,
                    "model_config_sha256": MODEL_CONFIG_SHA256,
                },
            }
        ),
        encoding="utf-8",
    )
    (session / "records-0001.jsonl").write_text(
        "".join(json.dumps(item) + "\n" for item in records),
        encoding="utf-8",
    )
    return session


def write_encrypted_session(root: Path) -> tuple[Path, bytes, bytes]:
    session = root / "field_sessions" / "field-test"
    session.mkdir(parents=True)
    manifest_bytes = json.dumps(aead_envelope(), separators=(",", ":")).encode("utf-8")
    record_bytes = (
        json.dumps(aead_envelope(ciphertext="cmVjb3JkLWNpcGhlcnRleHQtdGFn"), separators=(",", ":"))
        + "\n"
    ).encode("utf-8")
    (session / "manifest.json").write_bytes(manifest_bytes)
    (session / "records-0001.jsonl").write_bytes(record_bytes)
    return session, manifest_bytes, record_bytes


def test_detects_exact_versioned_aead_envelope() -> None:
    assert MODULE.is_versioned_aead_envelope(aead_envelope()) is True
    assert MODULE.is_versioned_aead_envelope({**aead_envelope(), "extra": "not-exact"}) is False
    assert MODULE.is_versioned_aead_envelope({**aead_envelope(), "envelope_version": 2}) is False


def test_encrypted_field_log_summary_is_unavailable_and_writes_nothing(tmp_path: Path) -> None:
    session, manifest_before, records_before = write_encrypted_session(tmp_path)
    output = tmp_path / "summary"

    summary, exit_code = MODULE.write_summary(tmp_path, output, strict=True)

    assert exit_code == 1
    assert summary["summary_available"] is False
    assert summary["input_format"] == MODULE.INPUT_FORMAT_AEAD_ENVELOPE
    assert summary["integrity"]["encrypted_aead_envelope_detected"] is True
    assert summary["integrity"]["strict_failure_reasons"] == [
        MODULE.ENCRYPTED_SUMMARY_UNAVAILABLE_REASON
    ]
    assert "sessions" not in summary
    assert "records" not in summary
    assert not output.exists()
    assert (session / "manifest.json").read_bytes() == manifest_before
    assert (session / "records-0001.jsonl").read_bytes() == records_before


def test_pull_preserves_encrypted_tar_verbatim_without_extracting(tmp_path: Path) -> None:
    envelope = json.dumps(aead_envelope(), separators=(",", ":"))
    archive_bytes = tar_archive_bytes(
        {
            "field_sessions/field-test/manifest.json": envelope,
            "field_sessions/field-test/records-0001.jsonl": envelope + "\n",
        }
    )
    output = tmp_path / "pull"
    output.mkdir()

    raw_archive = PULL_MODULE.materialize_field_archive(archive_bytes, output)

    assert raw_archive == output / PULL_MODULE.ENCRYPTED_RAW_ARCHIVE_NAME
    assert raw_archive.read_bytes() == archive_bytes
    assert stat.S_IMODE(raw_archive.stat().st_mode) == 0o600
    assert not (output / "field_sessions").exists()


@pytest.mark.parametrize(
    "malformed_envelope",
    [
        {**aead_envelope(), "unexpected": "field"},
        {key: value for key, value in aead_envelope().items() if key != "ciphertext"},
        {**aead_envelope(), "envelope_version": 999},
    ],
)
def test_malformed_current_envelope_is_unavailable_never_legacy(
    tmp_path: Path,
    malformed_envelope: dict,
) -> None:
    session = tmp_path / "field_sessions" / "field-test"
    session.mkdir(parents=True)
    (session / "manifest.json").write_text(json.dumps(malformed_envelope), encoding="utf-8")
    output = tmp_path / "summary"

    summary, exit_code = MODULE.write_summary(tmp_path, output, strict=True)

    assert exit_code == 1
    assert summary["summary_available"] is False
    assert summary["input_format"] == MODULE.INPUT_FORMAT_AEAD_ENVELOPE_MALFORMED
    assert summary["integrity"]["encrypted_current_malformed"] is True
    assert summary["integrity"]["strict_failure_reasons"] == [
        MODULE.ENCRYPTED_CURRENT_MALFORMED_REASON
    ]
    assert not output.exists()


def test_duplicate_member_current_envelope_is_malformed_unavailable(tmp_path: Path) -> None:
    session = tmp_path / "field_sessions" / "field-test"
    session.mkdir(parents=True)
    (session / "manifest.json").write_text(
        '{"envelope_version":1,"key_version":1,"iv":"a","iv":"b","ciphertext":"c"}',
        encoding="utf-8",
    )

    summary, exit_code = MODULE.write_summary(tmp_path, tmp_path / "summary", strict=True)

    assert exit_code == 1
    assert summary["summary_available"] is False
    assert summary["input_format"] == MODULE.INPUT_FORMAT_AEAD_ENVELOPE_MALFORMED
    assert not (tmp_path / "summary").exists()


def test_pull_preserves_malformed_current_tar_verbatim_without_extracting(tmp_path: Path) -> None:
    malformed = json.dumps({**aead_envelope(), "extra": True}, separators=(",", ":"))
    archive_bytes = tar_archive_bytes(
        {"field_sessions/field-test/manifest.json": malformed}
    )
    output = tmp_path / "pull"
    output.mkdir()

    raw_archive = PULL_MODULE.materialize_field_archive(archive_bytes, output)

    assert raw_archive is not None
    assert raw_archive.read_bytes() == archive_bytes
    assert not (output / "field_sessions").exists()


def test_pull_never_extracts_legacy_plaintext_history(tmp_path: Path) -> None:
    archive_bytes = tar_archive_bytes(
        {
            "field_sessions/field-test/manifest.json": '{"legacy":"history"}',
            "field_sessions/field-test/records-0001.jsonl": '{"legacy":"record"}\n',
        }
    )
    output = tmp_path / "pull-legacy"
    output.mkdir()

    raw_archive = PULL_MODULE.materialize_field_archive(archive_bytes, output)

    assert raw_archive.read_bytes() == archive_bytes
    assert stat.S_IMODE(raw_archive.stat().st_mode) == 0o600
    assert not (output / "field_sessions").exists()


@pytest.mark.parametrize(
    "name",
    [
        "field_sessions/field-test/records-extra.jsonl",
        "field_sessions/field-test/encrypted-dump.json",
    ],
)
def test_pull_rejects_unknown_files_even_when_theyContainAead(
    tmp_path: Path,
    name: str,
) -> None:
    archive_bytes = tar_archive_bytes(
        {
            "field_sessions/field-test/manifest.json": json.dumps(aead_envelope()),
            name: json.dumps(aead_envelope()),
        }
    )
    output = tmp_path / "pull-unknown"
    output.mkdir()

    with pytest.raises(RuntimeError, match="unexpected archive member"):
        PULL_MODULE.materialize_field_archive(archive_bytes, output)

    assert not (output / PULL_MODULE.ENCRYPTED_RAW_ARCHIVE_NAME).exists()


def test_legacy_manifest_rejects_recursive_duplicate_json_member(tmp_path: Path) -> None:
    session = write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "legacy_fixture",
        },
    )
    (session / "manifest.json").write_text(
        '{"schema_version":"android.field_session.v1","session_id":"field-test",'
        '"status":"completed","started_at_epoch_ms":1000,"ended_at_epoch_ms":2000,'
        '"provenance":{"source_commit":"a","source_commit":"b"}}',
        encoding="utf-8",
    )

    summary, exit_code = MODULE.write_summary(
        tmp_path, tmp_path / "summary", strict=True
    )

    assert exit_code == 1
    assert summary["integrity"]["malformed_or_unknown_record_count"] == 1
    assert summary["integrity"]["errors"] == ["input_error"]


def test_legacy_record_rejects_recursive_duplicate_json_member(tmp_path: Path) -> None:
    session = write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "legacy_fixture",
        },
    )
    (session / "records-0001.jsonl").write_text(
        '{"schema_version":"android.field_record.v1","record_type":"event",'
        '"recorded_at_epoch_ms":1500,"event_name":"legacy_fixture",'
        '"fields":{"safe":1,"safe":2}}\n',
        encoding="utf-8",
    )

    summary, exit_code = MODULE.write_summary(
        tmp_path, tmp_path / "summary", strict=True
    )

    assert exit_code == 1
    assert summary["integrity"]["malformed_or_unknown_record_count"] == 1
    assert "malformed_or_unknown_record" in summary["integrity"]["strict_failure_reasons"]


def test_pull_rejects_duplicate_normalized_tar_member(tmp_path: Path) -> None:
    archive_bytes = tar_archive_sequence(
        [
            ("field_sessions/field-test/manifest.json", "{}", "file"),
            ("field_sessions/field-test/./manifest.json", "{}", "file"),
        ]
    )
    output = tmp_path / "pull"
    output.mkdir()

    with pytest.raises(RuntimeError, match="duplicate normalized archive member"):
        PULL_MODULE.materialize_field_archive(archive_bytes, output)

    assert not (output / "field_sessions").exists()


def test_pull_rejects_tar_file_directory_collision(tmp_path: Path) -> None:
    archive_bytes = tar_archive_sequence(
        [
            ("field_sessions/field-test", "not-a-directory", "file"),
            ("field_sessions/field-test/manifest.json", "{}", "file"),
        ]
    )
    output = tmp_path / "pull"
    output.mkdir()

    with pytest.raises(RuntimeError, match="file/directory collision"):
        PULL_MODULE.materialize_field_archive(archive_bytes, output)

    assert not (output / "field_sessions").exists()


def test_duplicate_session_id_is_rejected(tmp_path: Path) -> None:
    record = {
        "schema_version": "android.field_record.v1",
        "record_type": "event",
        "recorded_at_epoch_ms": 1500,
        "event_name": "legacy_fixture",
    }
    write_session(tmp_path, record, session_id="same")
    second = write_session(tmp_path, record, session_id="other")
    manifest_path = second / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["session_id"] = "same"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    summary, exit_code = MODULE.write_summary(tmp_path, tmp_path / "summary")

    assert exit_code == 1
    assert summary["integrity"]["duplicate_session_id_count"] == 1
    assert summary["integrity"]["duplicate_session_ids"] == []
    assert '"same"' not in json.dumps(summary)


@pytest.mark.parametrize("key", ["access_token", "vendor_session_token", "client_secret"])
def test_unknown_sensitive_token_fields_fail_closed(tmp_path: Path, key: str) -> None:
    write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "legacy_fixture",
            "fields": {key: "must-not-be-credited"},
        },
    )

    summary, exit_code = MODULE.write_summary(tmp_path, tmp_path / "summary")

    assert exit_code == 1
    assert summary["integrity"]["privacy_violation_count"] == 1


def test_summary_rejects_source_symlink_and_hardlink(tmp_path: Path) -> None:
    session = write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "legacy_fixture",
        },
    )
    manifest = session / "manifest.json"
    hardlink = session / "manifest-hardlink.json"
    hardlink.hardlink_to(manifest)

    with pytest.raises(MODULE.UnsafeEvidencePath, match="single-link"):
        MODULE.write_summary(tmp_path, tmp_path / "summary")

    hardlink.unlink()
    actual = tmp_path / "actual-source"
    (tmp_path / "field_sessions").rename(actual)
    (tmp_path / "field_sessions").symlink_to(actual, target_is_directory=True)
    with pytest.raises(MODULE.UnsafeEvidencePath, match="symlink"):
        MODULE.write_summary(tmp_path / "field_sessions", tmp_path / "summary")


def test_summary_rejects_output_symlink(tmp_path: Path) -> None:
    source = tmp_path / "source"
    write_session(
        source,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "legacy_fixture",
        },
    )
    actual = tmp_path / "actual-output"
    actual.mkdir()
    output = tmp_path / "summary"
    output.symlink_to(actual, target_is_directory=True)

    with pytest.raises(MODULE.UnsafeEvidencePath, match="output symlink"):
        MODULE.write_summary(source, output)


def test_encrypted_unavailable_atomically_replaces_stale_summary(tmp_path: Path) -> None:
    source = tmp_path / "source"
    write_encrypted_session(source)
    output = tmp_path / "summary"
    output.mkdir()
    json_path = output / "field_session_summary.json"
    markdown_path = output / "field_session_summary.md"
    json_path.write_text('{"summary_available":true}\n', encoding="utf-8")
    markdown_path.write_text("PASS stale\n", encoding="utf-8")

    summary, exit_code = MODULE.write_summary(source, output, strict=True)

    assert exit_code == 1
    replaced = json.loads(json_path.read_text(encoding="utf-8"))
    assert replaced["summary_available"] is False
    assert replaced["integrity"]["summary_unavailable_reason"] == (
        MODULE.ENCRYPTED_SUMMARY_UNAVAILABLE_REASON
    )
    assert "NOT_RUN" in markdown_path.read_text(encoding="utf-8")
    assert stat.S_IMODE(json_path.stat().st_mode) == 0o600
    assert summary["summary_available"] is False


def test_plaintext_fixture_summary_is_explicitly_legacy_history_only(tmp_path: Path) -> None:
    write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "legacy_fixture",
        },
    )

    summary, exit_code = MODULE.write_summary(tmp_path, tmp_path / "summary")

    assert exit_code == 1
    assert summary["summary_available"] is True
    assert summary["input_format"] == MODULE.INPUT_FORMAT_LEGACY_PLAINTEXT_HISTORY_ONLY
    assert summary["integrity"]["legacy_plaintext_history_only"] is True
    assert summary["integrity"]["evidence_eligibility"] == (
        MODULE.LEGACY_HISTORY_ONLY_NOT_ELIGIBLE
    )
    assert summary["integrity"]["release_credit"] == 0
    markdown = (tmp_path / "summary" / "field_session_summary.md").read_text(encoding="utf-8")
    assert "LEGACY/HISTORY ONLY" in markdown
    assert "NO EVIDENCE CREDIT" in markdown


@pytest.mark.parametrize(
    "unexpected_name",
    ["records-extra.jsonl", "encrypted-dump.json", "notes.txt"],
)
def test_summary_rejects_unknown_session_file_layout(
    tmp_path: Path,
    unexpected_name: str,
) -> None:
    session = write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "legacy_fixture",
        },
    )
    (session / unexpected_name).write_text(json.dumps(aead_envelope()), encoding="utf-8")

    with pytest.raises(MODULE.UnsafeEvidencePath, match="unexpected input entry"):
        MODULE.write_summary(tmp_path, tmp_path / "summary")

    assert not (tmp_path / "summary").exists()


def test_summary_rejects_unknown_aead_sibling_of_field_sessions(
    tmp_path: Path,
) -> None:
    source = tmp_path / "wrapper"
    write_session(
        source,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "legacy_fixture",
        },
    )
    (source / "encrypted-dump.json").write_text(
        json.dumps(aead_envelope()), encoding="utf-8"
    )
    output = tmp_path / "summary"

    with pytest.raises(MODULE.UnsafeEvidencePath, match="exact field_sessions"):
        MODULE.write_summary(source, output)

    assert not output.exists()


@pytest.mark.parametrize(
    "reserved_key",
    ["Envelope_Version", "Key_Version", "IV", "CipherText"],
)
def test_case_variant_aead_key_is_malformed_current_never_legacy(
    tmp_path: Path,
    reserved_key: str,
) -> None:
    session = tmp_path / "field_sessions" / "field-test"
    session.mkdir(parents=True)
    (session / "manifest.json").write_text(
        json.dumps({reserved_key: "reserved-current-member"}), encoding="utf-8"
    )
    output = tmp_path / "summary"

    summary, exit_code = MODULE.write_summary(tmp_path, output, strict=True)

    assert exit_code == 1
    assert summary["summary_available"] is False
    assert summary["input_format"] == MODULE.INPUT_FORMAT_AEAD_ENVELOPE_MALFORMED
    assert summary["integrity"]["encrypted_current_malformed"] is True
    assert not output.exists()


@pytest.mark.parametrize("link_kind", ["symlink", "hardlink"])
def test_summary_rejects_linked_record_input(tmp_path: Path, link_kind: str) -> None:
    session = write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "legacy_fixture",
        },
    )
    record = session / "records-0001.jsonl"
    replacement = session / "records-0002.jsonl"
    if link_kind == "symlink":
        replacement.symlink_to(record.name)
    else:
        replacement.hardlink_to(record)

    with pytest.raises(MODULE.UnsafeEvidencePath, match="regular single-link"):
        MODULE.write_summary(tmp_path, tmp_path / "summary")


def test_summary_rejects_source_and_output_ancestor_symlinks(tmp_path: Path) -> None:
    real = tmp_path / "real"
    write_session(
        real,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "legacy_fixture",
        },
    )
    source_alias = tmp_path / "source-alias"
    source_alias.symlink_to(real, target_is_directory=True)
    with pytest.raises(MODULE.UnsafeEvidencePath, match="source ancestor"):
        MODULE.write_summary(source_alias, tmp_path / "summary")

    output_parent = tmp_path / "output-parent"
    output_parent.mkdir()
    output_alias = tmp_path / "output-alias"
    output_alias.symlink_to(output_parent, target_is_directory=True)
    with pytest.raises(MODULE.UnsafeEvidencePath, match="output ancestor"):
        MODULE.write_summary(real, output_alias / "summary")


def test_summary_rejects_output_overlapping_evidence_root(tmp_path: Path) -> None:
    session = write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "legacy_fixture",
        },
    )

    with pytest.raises(MODULE.UnsafeEvidencePath, match="overlap"):
        MODULE.write_summary(tmp_path / "field_sessions", session / "summary")


def test_summary_uses_bounded_fd_reads_without_path_read_text(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "legacy_fixture",
        },
    )

    def reject_path_read(*_args: object, **_kwargs: object) -> str:
        raise AssertionError("input paths must not be reopened with Path.read_text")

    monkeypatch.setattr(Path, "read_text", reject_path_read)
    summary = MODULE.summarize(tmp_path)

    assert summary["integrity"]["session_count"] == 1


def test_summary_final_cas_rejects_same_inode_rewrite_with_restored_mtime(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    session = write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "legacy_fixture",
        },
    )
    record_path = session / "records-0001.jsonl"
    original_payload = record_path.read_bytes()
    replacement_payload = original_payload.replace(b"legacy_fixture", b"legacy_fixturE")
    assert len(replacement_payload) == len(original_payload)
    original_stat = record_path.stat()
    original_reader = MODULE._read_bounded_file
    mutated = False

    def rewrite_after_manifest(
        parent_fd: int,
        name: str,
        *,
        max_bytes: int,
        label: str,
        **kwargs: object,
    ) -> bytes:
        nonlocal mutated
        payload = original_reader(
            parent_fd,
            name,
            max_bytes=max_bytes,
            label=label,
            **kwargs,
        )
        if label == "manifest" and not mutated:
            record_path.write_bytes(replacement_payload)
            os.utime(
                record_path,
                ns=(original_stat.st_atime_ns, original_stat.st_mtime_ns),
            )
            rewritten = record_path.stat()
            assert rewritten.st_ino == original_stat.st_ino
            assert rewritten.st_size == original_stat.st_size
            assert rewritten.st_mtime_ns == original_stat.st_mtime_ns
            assert rewritten.st_ctime_ns != original_stat.st_ctime_ns
            mutated = True
        return payload

    monkeypatch.setattr(MODULE, "_read_bounded_file", rewrite_after_manifest)
    output = tmp_path / "summary"

    with pytest.raises(MODULE.UnsafeEvidencePath, match="changed after snapshot"):
        MODULE.write_summary(tmp_path, output)

    assert mutated is True
    assert not output.exists()


def test_summary_rejects_oversized_manifest_before_json_parse(tmp_path: Path) -> None:
    session = write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "legacy_fixture",
        },
    )
    (session / "manifest.json").write_bytes(b"{" + b"x" * MODULE.MAX_MANIFEST_BYTES)

    with pytest.raises(MODULE.EvidenceInputError, match="size limit"):
        MODULE.write_summary(tmp_path, tmp_path / "summary")


def test_summary_never_projects_arbitrary_raw_values_to_json_markdown_or_stdout(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    secret = "RAW-SENTINEL-NEVER-PROJECT-7f8c2d"
    source = tmp_path / secret / "field_sessions"
    session = source / secret
    session.mkdir(parents=True)
    (session / "manifest.json").write_text(
        json.dumps(
            {
                "schema_version": "android.field_session.v1",
                "session_id": secret,
                "status": secret,
                "started_at_epoch_ms": 1000,
                "ended_at_epoch_ms": 2000,
                "device": {
                    "model": secret,
                    "android_version": secret,
                    "app_version_name": secret,
                },
                "provenance": {
                    "source_commit": secret,
                    "apk_sha256": secret,
                    "model_config_sha256": secret,
                },
            }
        ),
        encoding="utf-8",
    )
    records = [
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1400,
            "event_name": secret,
            "fields": {"access_token": secret, "model": secret},
        },
        {
            "schema_version": "android.field_record.v1",
            "record_type": "telemetry",
            "recorded_at_epoch_ms": 1500,
            "runtime": {
                "navigation_state": secret,
                "report_candidate_state": secret,
            },
            "depth_debug": {
                "top_detection_class_name": secret,
                "best_depth_class_name": secret,
                "best_depth_source": secret,
                "stale_reason": secret,
                "detector_model_key": secret,
                "detector_loaded_model_key": secret,
            },
        },
        camera_event(
            "camera_non_metric_session_started",
            {
                "loaded_model": secret,
                "model_fallback_used": False,
                "metric": False,
                "reports_allowed": False,
                "reason": secret,
                "state": secret,
            },
            1600,
        ),
    ]
    (session / "records-0001.jsonl").write_text(
        "".join(json.dumps(record) + "\n" for record in records),
        encoding="utf-8",
    )
    output = tmp_path / "summary"

    summary, exit_code = MODULE.write_summary(
        source,
        output,
        strict=True,
        strict_mode=MODULE.STRICT_MODE_CAMERA_NON_METRIC,
    )

    assert exit_code == 1
    assert secret not in json.dumps(summary, ensure_ascii=False)
    assert secret not in (output / "field_session_summary.json").read_text(encoding="utf-8")
    assert secret not in (output / "field_session_summary.md").read_text(encoding="utf-8")

    cli_output = tmp_path / "cli-summary"
    monkeypatch.setattr(
        sys,
        "argv",
        [str(SCRIPT), str(source), "--output", str(cli_output)],
    )
    assert MODULE.main() == 1
    captured = capsys.readouterr()
    assert secret not in captured.out
    assert secret not in captured.err


def test_summarizes_detector_depth_and_privacy_limited_runtime(tmp_path: Path) -> None:
    write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "telemetry",
            "recorded_at_epoch_ms": 1500,
            "runtime": {
                "step_count": 12,
                "navigation_state": "navigation=gps_trusted",
                "trusted_location_available": True,
                "location_accuracy_m": 4.5,
                "device_gate_allows_alerts": True,
                "device_gate_allows_reports": False,
            },
            "depth_debug": {
                "detection_count": 1,
                "top_detection_class_name": "person",
                "best_depth_source": "ARCORE_RAW_DEPTH",
                "best_depth_risk_distance_m": 1.2,
                "detect_duration_ms": 90,
                "detector_model_inference_ms": 40,
            },
        },
    )

    summary = MODULE.summarize(tmp_path)

    assert summary["integrity"]["session_count"] == 1
    assert summary["integrity"]["privacy_violation_count"] == 0
    assert summary["records"]["telemetry"] == 1
    assert summary["detector"]["top_classes"] == {"person": 1}
    assert summary["detector"]["detect_duration_ms"]["p50"] == 90
    assert summary["detector"]["unified_inference_ms"]["p50"] == 40
    assert summary["depth"]["frames_with_metric_arcore_depth"] == 1
    assert summary["runtime"]["trusted_location_frames"] == 1


def test_flags_forbidden_exact_coordinate_key(tmp_path: Path) -> None:
    write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "bad_fixture",
            "fields": {"latitude": 37.5},
        },
    )

    summary = MODULE.summarize(tmp_path)

    assert summary["integrity"]["privacy_violation_count"] == 1
    assert summary["integrity"]["privacy_violations"] == ["forbidden_field"]


def test_summary_rejects_empty_wrapper_source_without_output(tmp_path: Path) -> None:
    output = tmp_path / "summary"

    with pytest.raises(MODULE.UnsafeEvidencePath, match="exact field_sessions"):
        MODULE.write_summary(tmp_path, output)

    assert not output.exists()


def test_summarizes_legacy_coco_and_custom_inference_without_unified_timing(tmp_path: Path) -> None:
    write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "telemetry",
            "recorded_at_epoch_ms": 1500,
            "runtime": {},
            "depth_debug": {
                "detection_count": 0,
                "detect_duration_ms": 95,
                "detector_coco_inference_ms": 30,
                "detector_custom_inference_ms": 50,
                "detector_model_fallback_used": True,
            },
        },
    )

    detector = MODULE.summarize(tmp_path)["detector"]

    assert detector["detect_duration_ms"]["p50"] == 95
    assert detector["unified_inference_ms"]["count"] == 0
    assert detector["legacy_coco_inference_ms"]["p50"] == 30
    assert detector["legacy_custom_inference_ms"]["p50"] == 50


def test_strict_rejects_session_without_telemetry(tmp_path: Path) -> None:
    write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "event",
            "recorded_at_epoch_ms": 1500,
            "event_name": "arcore_session_started",
        },
    )

    summary, exit_code = MODULE.write_summary(tmp_path, tmp_path / "summary", strict=True)

    assert exit_code == 1
    assert "zero_telemetry" in summary["integrity"]["strict_failure_reasons"]


def test_strict_rejects_telemetry_without_arcore_start(tmp_path: Path) -> None:
    write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "telemetry",
            "recorded_at_epoch_ms": 1500,
            "runtime": {},
            "depth_debug": {"detector_loaded_model_key": "legacy_two_model"},
        },
    )

    summary, exit_code = MODULE.write_summary(tmp_path, tmp_path / "summary", strict=True)

    assert exit_code == 1
    assert "missing_arcore_session_started" in summary["integrity"]["strict_failure_reasons"]


def test_strict_accepts_arcore_telemetry_with_loaded_model(tmp_path: Path) -> None:
    write_session(
        tmp_path,
        [
            {
                "schema_version": "android.field_record.v1",
                "record_type": "event",
                "recorded_at_epoch_ms": 1400,
                "event_name": "arcore_session_started",
            },
            {
                "schema_version": "android.field_record.v1",
                "record_type": "telemetry",
                "recorded_at_epoch_ms": 1500,
                "runtime": {},
                "depth_debug": {"detector_loaded_model_key": "legacy_two_model"},
            },
        ],
    )

    summary, exit_code = MODULE.write_summary(tmp_path, tmp_path / "summary", strict=True)

    assert exit_code == 1
    assert summary["integrity"]["evidence_scope"] == MODULE.EVIDENCE_SCOPE_ARCORE_METRIC
    assert summary["integrity"]["arcore_unsupported_verified"] is False
    assert summary["integrity"]["strict_failure_reasons"] == []


CAMERA_START_FIELDS = {
    "loaded_model": "unified_walksafe",
    "model_fallback_used": False,
    "metric": False,
    "reports_allowed": False,
    "reason": "debug_forced_supported",
    "state": "SUPPORTED_INSTALLED",
}
CAMERA_FRAME_FIELDS = {
    "loaded_model": "unified_walksafe",
    "model_fallback_used": False,
    "metric": False,
    "reports_allowed": False,
    "state": "detector_succeeded",
}
CAMERA_SAMPLE_FIELDS = {
    "elapsed_realtime_ms": 100_000,
    "inference_ms": 480,
    "detection_count": 2,
    "capability_tier": "CAMERA_IMU_NON_METRIC",
    "camera_permission_granted": True,
    "camera_fallback_running": True,
    "detector_available": True,
    "imu_fresh": True,
    "tmap_route_active": True,
    "metric": False,
    "reports_allowed": False,
    "state": "detector_succeeded",
}
ABSENT = object()


def camera_event(name: str, fields: object, recorded_at: int) -> dict:
    return {
        "schema_version": "android.field_record.v1",
        "record_type": "event",
        "recorded_at_epoch_ms": recorded_at,
        "event_name": name,
        "fields": fields,
    }


def write_camera_strict(
    tmp_path: Path,
    *,
    start_fields: object = CAMERA_START_FIELDS,
    frame_fields: object = CAMERA_FRAME_FIELDS,
    advisory_fields: object = ABSENT,
    sample_records: list[dict] | None = None,
    session_ended_at: int | None = None,
    require_advisory: bool = False,
    require_arcore_unsupported: bool = False,
) -> tuple[dict, int]:
    records = [camera_event("camera_non_metric_session_started", start_fields, 1400)]
    if frame_fields is not ABSENT:
        records.append(camera_event("camera_non_metric_frame_analyzed", frame_fields, 1450))
    if advisory_fields is not ABSENT:
        records.append(camera_event("camera_non_metric_advisory_emitted", advisory_fields, 1500))
    records.extend(sample_records or [])
    write_session(tmp_path, records, ended_at=session_ended_at)
    return MODULE.write_summary(
        tmp_path,
        tmp_path / "summary",
        strict=True,
        strict_mode=MODULE.STRICT_MODE_CAMERA_NON_METRIC,
        require_camera_advisory=require_advisory,
        require_arcore_unsupported=require_arcore_unsupported,
    )


def camera_sample_records(
    *,
    count: int = 61,
    start_at: int = 2_000,
    elapsed_start_ms: int = 100_000,
    interval_ms: int = 15_000,
    fields: dict = CAMERA_SAMPLE_FIELDS,
) -> list[dict]:
    return [
        camera_event(
            "camera_non_metric_inference_sample",
            {
                **fields,
                "elapsed_realtime_ms": elapsed_start_ms + index * interval_ms,
                "inference_ms": fields["inference_ms"] + index,
            },
            start_at + index * interval_ms,
        )
        for index in range(count)
    ]


def test_camera_non_metric_strict_accepts_start_and_analyzed_frame_without_arcore_telemetry(tmp_path: Path) -> None:
    summary, exit_code = write_camera_strict(tmp_path)

    assert exit_code == 1
    assert summary["records"]["telemetry"] == 0
    assert summary["events"].get("arcore_session_started", 0) == 0
    assert summary["events"]["camera_non_metric_frame_analyzed"] == 1
    assert summary["integrity"]["evidence_scope"] == MODULE.EVIDENCE_SCOPE_FORCED_SUPPORTED_FUNCTIONAL
    assert summary["integrity"]["arcore_unsupported_verified"] is False
    assert summary["integrity"]["strict_failure_reasons"] == []
    markdown = (tmp_path / "summary" / "field_session_summary.md").read_text(encoding="utf-8")
    assert "- evidence scope: FORCED_SUPPORTED_FUNCTIONAL" in markdown
    assert "- ARCore 실제 미지원 검증: False" in markdown


@pytest.mark.parametrize(
    ("fields", "expected_reason"),
    [
        (
            {
                "model_fallback_used": False,
                "metric": False,
                "reports_allowed": False,
            },
            "invalid_camera_non_metric_loaded_model_contract",
        ),
        (
            {
                "loaded_model": "unified_walksafe",
                "model_fallback_used": "false",
                "metric": False,
                "reports_allowed": False,
            },
            "invalid_camera_non_metric_model_fallback_contract",
        ),
        (
            {
                "loaded_model": "unified_walksafe",
                "model_fallback_used": False,
                "metric": True,
                "reports_allowed": False,
            },
            "invalid_camera_non_metric_metric_contract",
        ),
        (
            {
                "loaded_model": "unified_walksafe",
                "model_fallback_used": False,
                "metric": False,
                "reports_allowed": True,
            },
            "invalid_camera_non_metric_reports_allowed_contract",
        ),
        (None, "invalid_camera_non_metric_loaded_model_contract"),
    ],
)
def test_camera_non_metric_strict_rejects_malformed_start_contract(
    tmp_path: Path,
    fields: dict | None,
    expected_reason: str,
) -> None:
    summary, exit_code = write_camera_strict(tmp_path, start_fields=fields)

    assert exit_code == 1
    assert expected_reason in summary["integrity"]["strict_failure_reasons"]


def test_camera_non_metric_strict_rejects_missing_analyzed_frame(tmp_path: Path) -> None:
    summary, exit_code = write_camera_strict(tmp_path, frame_fields=ABSENT)

    assert exit_code == 1
    assert "missing_camera_non_metric_frame_analyzed" in summary["integrity"]["strict_failure_reasons"]


@pytest.mark.parametrize(
    ("fields", "expected_reason"),
    [
        (
            {
                "model_fallback_used": False,
                "metric": False,
                "reports_allowed": False,
                "state": "detector_succeeded",
            },
            "invalid_camera_non_metric_frame_loaded_model_contract",
        ),
        (
            {
                "loaded_model": "unified_walksafe",
                "model_fallback_used": "false",
                "metric": False,
                "reports_allowed": False,
                "state": "detector_succeeded",
            },
            "invalid_camera_non_metric_frame_model_fallback_contract",
        ),
        (
            {
                "loaded_model": "unified_walksafe",
                "model_fallback_used": False,
                "metric": True,
                "reports_allowed": False,
                "state": "detector_succeeded",
            },
            "invalid_camera_non_metric_frame_metric_contract",
        ),
        (
            {
                "loaded_model": "unified_walksafe",
                "model_fallback_used": False,
                "metric": False,
                "reports_allowed": True,
                "state": "detector_succeeded",
            },
            "invalid_camera_non_metric_frame_reports_allowed_contract",
        ),
        (
            {
                "loaded_model": "unified_walksafe",
                "model_fallback_used": False,
                "metric": False,
                "reports_allowed": False,
                "state": "detector_failed",
            },
            "invalid_camera_non_metric_frame_state_contract",
        ),
        (None, "invalid_camera_non_metric_frame_loaded_model_contract"),
    ],
)
def test_camera_non_metric_strict_rejects_malformed_analyzed_frame(
    tmp_path: Path,
    fields: dict | None,
    expected_reason: str,
) -> None:
    summary, exit_code = write_camera_strict(tmp_path, frame_fields=fields)

    assert exit_code == 1
    assert expected_reason in summary["integrity"]["strict_failure_reasons"]


def test_camera_advisory_strict_validates_emitted_contract(tmp_path: Path) -> None:
    summary, exit_code = write_camera_strict(
        tmp_path,
        advisory_fields={
            "direction": "CENTER",
            "loaded_model": "unified_walksafe",
            "model_fallback_used": False,
            "metric": False,
            "tmap_authoritative": True,
            "reports_allowed": False,
        },
        require_advisory=True,
    )

    assert exit_code == 1
    assert summary["integrity"]["strict_failure_reasons"] == []


def test_camera_advisory_strict_rejects_missing_emitted_event(tmp_path: Path) -> None:
    summary, exit_code = write_camera_strict(tmp_path, require_advisory=True)

    assert exit_code == 1
    assert "missing_camera_non_metric_advisory_emitted" in summary["integrity"]["strict_failure_reasons"]


def test_camera_advisory_strict_rejects_unsafe_emitted_contract(tmp_path: Path) -> None:
    summary, exit_code = write_camera_strict(
        tmp_path,
        advisory_fields={
            "direction": "FORWARD",
            "loaded_model": "unified_walksafe",
            "model_fallback_used": False,
            "metric": True,
            "tmap_authoritative": False,
            "reports_allowed": True,
        },
        require_advisory=True,
    )

    assert exit_code == 1
    reasons = summary["integrity"]["strict_failure_reasons"]
    assert "invalid_camera_non_metric_advisory_direction_contract" in reasons
    assert "invalid_camera_non_metric_advisory_metric_contract" in reasons
    assert "invalid_camera_non_metric_advisory_tmap_contract" in reasons
    assert "invalid_camera_non_metric_advisory_reports_allowed_contract" in reasons


@pytest.mark.parametrize(
    "start_fields",
    [
        {
            **CAMERA_START_FIELDS,
            "reason": "arcore_availability_unsupported",
            "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
        },
        {
            **CAMERA_START_FIELDS,
            "reason": "arcore_session_incompatible",
            "state": "SUPPORTED_INSTALLED",
        },
    ],
)
def test_arcore_unsupported_gate_accepts_only_definitive_runtime_origins(
    tmp_path: Path,
    start_fields: dict,
) -> None:
    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields=start_fields,
        sample_records=camera_sample_records(),
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert summary["integrity"]["arcore_unsupported_required"] is True
    assert summary["integrity"]["evidence_scope"] == MODULE.EVIDENCE_SCOPE_ARCORE_UNSUPPORTED_FIELD
    assert summary["integrity"]["arcore_unsupported_verified"] is True
    assert summary["integrity"]["strict_failure_reasons"] == []
    selected = summary["camera_non_metric"]["strict_selected_session_samples"]
    assert selected["sample_count"] == 61
    assert selected["sample_span_ms"] == 900_000
    assert selected["max_sample_gap_ms"] == 15_000
    assert selected["inference_ms"]["p50"] == 510
    assert selected["inference_ms"]["p95"] == 537


def test_arcore_unsupported_gate_accepts_route_inactive_camera_samples(
    tmp_path: Path,
) -> None:
    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields={
            **CAMERA_START_FIELDS,
            "reason": "arcore_availability_unsupported",
            "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
        },
        sample_records=camera_sample_records(
            fields={**CAMERA_SAMPLE_FIELDS, "tmap_route_active": False}
        ),
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert summary["integrity"]["strict_failure_reasons"] == []
    contracts = summary["sessions"][0]["strict_evidence"]["camera_non_metric"][
        "inference_sample_contracts"
    ]
    assert contracts
    assert all(sample["capability_tier"] == "CAMERA_IMU_NON_METRIC" for sample in contracts)
    assert all(sample["tmap_route_active"] is False for sample in contracts)


@pytest.mark.parametrize(
    ("sample_records", "expected_reason"),
    [
        (camera_sample_records(count=59, interval_ms=16_000), "camera_non_metric_sample_count_below_field_minimum"),
        (camera_sample_records(count=61, interval_ms=14_000), "camera_non_metric_sample_span_below_field_minimum"),
        (
            [
                {
                    **record,
                    "fields": {
                        **record["fields"],
                        "elapsed_realtime_ms": (
                            record["fields"]["elapsed_realtime_ms"] - (14_001 if index == 30 else 0)
                        ),
                    },
                }
                for index, record in enumerate(camera_sample_records())
            ],
            "invalid_camera_non_metric_sample_elapsed_sequence",
        ),
        (
            [
                {
                    **record,
                    "fields": {
                        **record["fields"],
                        "elapsed_realtime_ms": (
                            record["fields"]["elapsed_realtime_ms"] + (16_001 if index >= 30 else 0)
                        ),
                    },
                }
                for index, record in enumerate(camera_sample_records())
            ],
            "camera_non_metric_sample_gap_above_field_maximum",
        ),
    ],
)
def test_arcore_unsupported_gate_requires_sustained_camera_samples(
    tmp_path: Path,
    sample_records: list[dict],
    expected_reason: str,
) -> None:
    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields={
            **CAMERA_START_FIELDS,
            "reason": "arcore_availability_unsupported",
            "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
        },
        sample_records=sample_records,
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert expected_reason in summary["integrity"]["strict_failure_reasons"]


def test_arcore_unsupported_gate_rejects_duplicate_elapsed_samples_that_fake_count(tmp_path: Path) -> None:
    unique_samples = camera_sample_records(count=31, interval_ms=30_000)
    last = unique_samples[-1]
    duplicate_samples = [
        {
            **last,
            "recorded_at_epoch_ms": last["recorded_at_epoch_ms"] + index + 1,
            "fields": {**last["fields"], "inference_ms": 600 + index},
        }
        for index in range(29)
    ]

    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields={
            **CAMERA_START_FIELDS,
            "reason": "arcore_availability_unsupported",
            "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
        },
        sample_records=[*unique_samples, *duplicate_samples],
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert "invalid_camera_non_metric_sample_elapsed_sequence" in summary["integrity"]["strict_failure_reasons"]


def test_arcore_unsupported_gate_rejects_sample_timestamp_outside_completed_manifest(tmp_path: Path) -> None:
    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields={
            **CAMERA_START_FIELDS,
            "reason": "arcore_availability_unsupported",
            "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
        },
        sample_records=camera_sample_records(),
        session_ended_at=100_000,
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert "invalid_camera_non_metric_sample_recorded_at_contract" in summary["integrity"][
        "strict_failure_reasons"
    ]


@pytest.mark.parametrize(
    ("field", "value"),
    [("inference_ms", 510.0), ("detection_count", 2.0)],
)
def test_arcore_unsupported_gate_rejects_non_integer_numeric_payload(
    tmp_path: Path,
    field: str,
    value: float,
) -> None:
    samples = camera_sample_records()
    samples[30] = {
        **samples[30],
        "fields": {**samples[30]["fields"], field: value},
    }

    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields={
            **CAMERA_START_FIELDS,
            "reason": "arcore_availability_unsupported",
            "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
        },
        sample_records=samples,
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert "invalid_camera_non_metric_inference_sample_contract" in summary["integrity"][
        "strict_failure_reasons"
    ]


@pytest.mark.parametrize("elapsed_realtime_ms", [True, 550_000.0])
def test_arcore_unsupported_gate_rejects_non_integer_elapsed_realtime(
    tmp_path: Path,
    elapsed_realtime_ms: object,
) -> None:
    samples = camera_sample_records()
    samples[30] = {
        **samples[30],
        "fields": {**samples[30]["fields"], "elapsed_realtime_ms": elapsed_realtime_ms},
    }

    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields={
            **CAMERA_START_FIELDS,
            "reason": "arcore_availability_unsupported",
            "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
        },
        sample_records=samples,
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert "invalid_camera_non_metric_sample_elapsed_contract" in summary["integrity"][
        "strict_failure_reasons"
    ]


@pytest.mark.parametrize("recorded_at", [True, 2_000.0])
def test_arcore_unsupported_gate_rejects_non_integer_sample_epoch_timestamp(
    tmp_path: Path,
    recorded_at: object,
) -> None:
    samples = camera_sample_records()
    samples[30] = {**samples[30], "recorded_at_epoch_ms": recorded_at}

    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields={
            **CAMERA_START_FIELDS,
            "reason": "arcore_availability_unsupported",
            "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
        },
        sample_records=samples,
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert "invalid_camera_non_metric_sample_recorded_at_contract" in summary["integrity"][
        "strict_failure_reasons"
    ]


def test_arcore_unsupported_gate_rejects_decreasing_epoch_timestamps(tmp_path: Path) -> None:
    samples = camera_sample_records()
    samples[30] = {**samples[30], "recorded_at_epoch_ms": samples[29]["recorded_at_epoch_ms"] - 1}

    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields={
            **CAMERA_START_FIELDS,
            "reason": "arcore_availability_unsupported",
            "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
        },
        sample_records=samples,
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert "invalid_camera_non_metric_sample_recorded_at_contract" in summary["integrity"][
        "strict_failure_reasons"
    ]


def test_arcore_unsupported_gate_rejects_unknown_record_schema(tmp_path: Path) -> None:
    samples = [
        {**record, "schema_version": "android.field_record.v999"}
        for record in camera_sample_records()
    ]

    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields={
            **CAMERA_START_FIELDS,
            "reason": "arcore_availability_unsupported",
            "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
        },
        sample_records=samples,
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert "malformed_or_unknown_record" in summary["integrity"]["strict_failure_reasons"]


def test_arcore_unsupported_gate_rejects_unknown_manifest_schema(tmp_path: Path) -> None:
    records = [
        camera_event(
            "camera_non_metric_session_started",
            {
                **CAMERA_START_FIELDS,
                "reason": "arcore_availability_unsupported",
                "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
            },
            1_400,
        ),
        camera_event("camera_non_metric_frame_analyzed", CAMERA_FRAME_FIELDS, 1_450),
        *camera_sample_records(),
    ]
    session = write_session(tmp_path, records)
    manifest_path = session / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["schema_version"] = "android.field_session.v999"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    summary, exit_code = MODULE.write_summary(
        tmp_path,
        tmp_path / "summary",
        strict=True,
        strict_mode=MODULE.STRICT_MODE_CAMERA_NON_METRIC,
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert "malformed_or_unknown_record" in summary["integrity"]["strict_failure_reasons"]


@pytest.mark.parametrize("status", ["active", "unknown"])
def test_arcore_unsupported_gate_requires_completed_bounded_session(
    tmp_path: Path,
    status: str,
) -> None:
    records = [
        camera_event(
            "camera_non_metric_session_started",
            {
                **CAMERA_START_FIELDS,
                "reason": "arcore_availability_unsupported",
                "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
            },
            1_400,
        ),
        camera_event("camera_non_metric_frame_analyzed", CAMERA_FRAME_FIELDS, 1_450),
        *camera_sample_records(),
    ]
    session = write_session(tmp_path, records)
    manifest_path = session / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["status"] = status
    manifest["started_at_epoch_ms"] = 2_000_000
    manifest.pop("ended_at_epoch_ms", None)
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    summary, exit_code = MODULE.write_summary(
        tmp_path,
        tmp_path / "summary",
        strict=True,
        strict_mode=MODULE.STRICT_MODE_CAMERA_NON_METRIC,
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    reasons = summary["integrity"]["strict_failure_reasons"]
    assert "arcore_unsupported_session_not_completed" in reasons
    assert "invalid_camera_non_metric_sample_recorded_at_contract" in reasons
    assert summary["integrity"]["arcore_unsupported_verified"] is False


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("elapsed_realtime_ms", 2**63),
        ("inference_ms", 10**1000),
        ("detection_count", 2**31),
    ],
)
def test_arcore_unsupported_gate_rejects_values_outside_android_integer_ranges(
    tmp_path: Path,
    field: str,
    value: int,
) -> None:
    samples = camera_sample_records()
    samples[30] = {
        **samples[30],
        "fields": {**samples[30]["fields"], field: value},
    }

    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields={
            **CAMERA_START_FIELDS,
            "reason": "arcore_availability_unsupported",
            "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
        },
        sample_records=samples,
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert "invalid_camera_non_metric_inference_sample_contract" in summary["integrity"][
        "strict_failure_reasons"
    ]


def test_arcore_unsupported_gate_rejects_epoch_outside_android_long_range(tmp_path: Path) -> None:
    samples = camera_sample_records()
    samples[30] = {**samples[30], "recorded_at_epoch_ms": 2**63}

    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields={
            **CAMERA_START_FIELDS,
            "reason": "arcore_availability_unsupported",
            "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
        },
        sample_records=samples,
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert "invalid_camera_non_metric_sample_recorded_at_contract" in summary["integrity"][
        "strict_failure_reasons"
    ]


@pytest.mark.parametrize(
    "fields",
    [
        {**CAMERA_SAMPLE_FIELDS, "capability_tier": "CAMERA_IMU_NON_METRIC", "imu_fresh": False},
        {
            **CAMERA_SAMPLE_FIELDS,
            "capability_tier": "TMAP_ONLY",
            "camera_permission_granted": True,
            "camera_fallback_running": True,
            "detector_available": True,
            "imu_fresh": True,
            "tmap_route_active": True,
        },
    ],
)
def test_arcore_unsupported_gate_rejects_inconsistent_tier_and_gate_snapshot(
    tmp_path: Path,
    fields: dict,
) -> None:
    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields={
            **CAMERA_START_FIELDS,
            "reason": "arcore_availability_unsupported",
            "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
        },
        sample_records=camera_sample_records(fields=fields),
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert "invalid_camera_non_metric_inference_sample_contract" in summary["integrity"][
        "strict_failure_reasons"
    ]


def test_arcore_unsupported_gate_accepts_exact_continuity_boundaries(tmp_path: Path) -> None:
    samples = camera_sample_records(count=60)
    elapsed_values = [100_000, 101_000] + [101_000 + index * 15_500 for index in range(1, 59)]
    samples = [
        {
            **record,
            "fields": {**record["fields"], "elapsed_realtime_ms": elapsed_values[index]},
        }
        for index, record in enumerate(samples)
    ]

    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields={
            **CAMERA_START_FIELDS,
            "reason": "arcore_availability_unsupported",
            "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
        },
        sample_records=samples,
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    selected = summary["camera_non_metric"]["strict_selected_session_samples"]
    assert selected["sample_count"] == 60
    assert selected["sample_span_ms"] == 900_000
    assert selected["min_sample_gap_ms"] == 1_000


def test_camera_sample_summary_uses_only_the_selected_strict_session(tmp_path: Path) -> None:
    write_session(
        tmp_path,
        [
            camera_event("camera_non_metric_session_started", CAMERA_START_FIELDS, 1_400),
            camera_event("camera_non_metric_frame_analyzed", CAMERA_FRAME_FIELDS, 1_450),
            *camera_sample_records(count=4, start_at=1_500, interval_ms=20_000),
        ],
        session_id="field-old",
        started_at=1_000,
    )
    new_samples = [
        camera_event(
            "camera_non_metric_inference_sample",
            {**CAMERA_SAMPLE_FIELDS, "elapsed_realtime_ms": 10_000, "inference_ms": 10},
            3_000,
        ),
        camera_event(
            "camera_non_metric_inference_sample",
            {**CAMERA_SAMPLE_FIELDS, "elapsed_realtime_ms": 12_000, "inference_ms": 20},
            5_000,
        ),
        camera_event(
            "camera_non_metric_inference_sample",
            {**CAMERA_SAMPLE_FIELDS, "elapsed_realtime_ms": 15_000, "inference_ms": 100},
            8_000,
        ),
    ]
    write_session(
        tmp_path,
        [
            camera_event("camera_non_metric_session_started", CAMERA_START_FIELDS, 2_400),
            camera_event("camera_non_metric_frame_analyzed", CAMERA_FRAME_FIELDS, 2_450),
            *new_samples,
        ],
        session_id="field-new",
        started_at=2_000,
    )

    summary, exit_code = MODULE.write_summary(
        tmp_path,
        tmp_path / "summary",
        strict=True,
        strict_mode=MODULE.STRICT_MODE_CAMERA_NON_METRIC,
    )

    assert exit_code == 1
    assert summary["integrity"]["strict_selected_session_sha256"] == MODULE._domain_hash(
        "session", "field-new"
    )
    selected = summary["camera_non_metric"]["strict_selected_session_samples"]
    assert selected["sample_count"] == 3
    assert selected["sample_span_ms"] == 5_000
    assert selected["max_sample_gap_ms"] == 3_000
    assert selected["inference_ms"]["p50"] == 20
    assert selected["inference_ms"]["p95"] == 100


@pytest.mark.parametrize(
    ("start_fields", "expected_scope"),
    [
        (CAMERA_START_FIELDS, MODULE.EVIDENCE_SCOPE_FORCED_SUPPORTED_FUNCTIONAL),
        (
            {
                **CAMERA_START_FIELDS,
                "reason": "arcore_depth_unsupported",
                "state": "SUPPORTED_INSTALLED",
            },
            MODULE.EVIDENCE_SCOPE_DEPTH_UNSUPPORTED_FUNCTIONAL,
        ),
        (
            {
                **CAMERA_START_FIELDS,
                "reason": "arcore_availability_unsupported",
                "state": "UNKNOWN_CHECKING",
            },
            MODULE.EVIDENCE_SCOPE_CAMERA_NON_METRIC_UNKNOWN,
        ),
        (
            {
                **CAMERA_START_FIELDS,
                "reason": "arcore_session_incompatible",
                "state": "UNKNOWN_ERROR",
            },
            MODULE.EVIDENCE_SCOPE_CAMERA_NON_METRIC_UNKNOWN,
        ),
        (
            {
                "loaded_model": "unified_walksafe",
                "model_fallback_used": False,
                "metric": False,
                "reports_allowed": False,
            },
            MODULE.EVIDENCE_SCOPE_CAMERA_NON_METRIC_UNKNOWN,
        ),
    ],
)
def test_arcore_unsupported_gate_rejects_forced_depth_unknown_and_missing_origins(
    tmp_path: Path,
    start_fields: dict,
    expected_scope: str,
) -> None:
    summary, exit_code = write_camera_strict(
        tmp_path,
        start_fields=start_fields,
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert summary["integrity"]["evidence_scope"] == expected_scope
    assert summary["integrity"]["arcore_unsupported_verified"] is False
    assert "arcore_unsupported_not_verified" in summary["integrity"]["strict_failure_reasons"]
    assert not any("sample_" in reason for reason in summary["integrity"]["strict_failure_reasons"])


def test_arcore_unsupported_gate_rejects_mixed_actual_and_forced_starts(tmp_path: Path) -> None:
    write_session(
        tmp_path,
        [
            camera_event(
                "camera_non_metric_session_started",
                {
                    **CAMERA_START_FIELDS,
                    "reason": "arcore_availability_unsupported",
                    "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
                },
                1400,
            ),
            camera_event("camera_non_metric_frame_analyzed", CAMERA_FRAME_FIELDS, 1450),
            camera_event("camera_non_metric_session_started", CAMERA_START_FIELDS, 1500),
        ],
    )

    summary, exit_code = MODULE.write_summary(
        tmp_path,
        tmp_path / "summary",
        strict=True,
        strict_mode=MODULE.STRICT_MODE_CAMERA_NON_METRIC,
        require_arcore_unsupported=True,
    )

    assert exit_code == 1
    assert summary["integrity"]["evidence_scope"] == MODULE.EVIDENCE_SCOPE_CAMERA_NON_METRIC_MIXED
    assert summary["integrity"]["arcore_unsupported_verified"] is False
    assert "arcore_unsupported_not_verified" in summary["integrity"]["strict_failure_reasons"]


def test_arcore_unsupported_gate_is_invalid_for_arcore_metric_mode(tmp_path: Path) -> None:
    write_session(
        tmp_path,
        camera_event("arcore_session_started", {}, 1400),
    )
    summary = MODULE.summarize(tmp_path)

    with pytest.raises(ValueError, match="only valid in camera-non-metric"):
        MODULE.strict_failure_reasons(summary, require_arcore_unsupported=True)


def test_camera_strict_uses_only_latest_session_and_ignores_older_invalid_contract(tmp_path: Path) -> None:
    write_session(
        tmp_path,
        camera_event("camera_non_metric_session_started", None, 1400),
        session_id="field-old-invalid",
        started_at=1000,
        provenance={
            "source_commit": "unverified",
            "apk_sha256": "old-invalid-apk",
            "model_config_sha256": "old-invalid-model-config",
        },
    )
    write_session(
        tmp_path,
        [
            camera_event("camera_non_metric_session_started", CAMERA_START_FIELDS, 2400),
            camera_event("camera_non_metric_frame_analyzed", CAMERA_FRAME_FIELDS, 2450),
        ],
        session_id="field-new-valid",
        started_at=2000,
    )

    summary, exit_code = MODULE.write_summary(
        tmp_path,
        tmp_path / "summary",
        strict=True,
        strict_mode=MODULE.STRICT_MODE_CAMERA_NON_METRIC,
    )

    assert exit_code == 1
    assert summary["integrity"]["strict_selected_session_sha256"] == MODULE._domain_hash(
        "session", "field-new-valid"
    )
    assert summary["integrity"]["strict_failure_reasons"] == []


def test_camera_strict_does_not_join_start_and_advisory_across_sessions(tmp_path: Path) -> None:
    write_session(
        tmp_path,
        camera_event("camera_non_metric_session_started", CAMERA_START_FIELDS, 1400),
        session_id="field-old-start",
        started_at=1000,
    )
    write_session(
        tmp_path,
        camera_event(
            "camera_non_metric_advisory_emitted",
            {
                "direction": "CENTER",
                "loaded_model": "unified_walksafe",
                "model_fallback_used": False,
                "metric": False,
                "tmap_authoritative": True,
                "reports_allowed": False,
            },
            2400,
        ),
        session_id="field-new-advisory",
        started_at=2000,
    )

    summary, exit_code = MODULE.write_summary(
        tmp_path,
        tmp_path / "summary",
        strict=True,
        strict_mode=MODULE.STRICT_MODE_CAMERA_NON_METRIC,
        require_camera_advisory=True,
    )

    assert exit_code == 1
    assert summary["integrity"]["strict_selected_session_sha256"] == MODULE._domain_hash(
        "session", "field-new-advisory"
    )
    assert "missing_camera_non_metric_session_started" in summary["integrity"]["strict_failure_reasons"]


def test_camera_strict_does_not_join_analyzed_frame_across_sessions(tmp_path: Path) -> None:
    write_session(
        tmp_path,
        camera_event("camera_non_metric_frame_analyzed", CAMERA_FRAME_FIELDS, 1400),
        session_id="field-old-frame",
        started_at=1000,
    )
    write_session(
        tmp_path,
        camera_event("camera_non_metric_session_started", CAMERA_START_FIELDS, 2400),
        session_id="field-new-start",
        started_at=2000,
    )

    summary, exit_code = MODULE.write_summary(
        tmp_path,
        tmp_path / "summary",
        strict=True,
        strict_mode=MODULE.STRICT_MODE_CAMERA_NON_METRIC,
    )

    assert exit_code == 1
    assert summary["integrity"]["strict_selected_session_sha256"] == MODULE._domain_hash(
        "session", "field-new-start"
    )
    assert "missing_camera_non_metric_frame_analyzed" in summary["integrity"]["strict_failure_reasons"]


def test_arcore_strict_does_not_join_start_and_telemetry_across_sessions(tmp_path: Path) -> None:
    write_session(
        tmp_path,
        camera_event("arcore_session_started", {}, 1400),
        session_id="field-old-start",
        started_at=1000,
    )
    write_session(
        tmp_path,
        {
            "schema_version": "android.field_record.v1",
            "record_type": "telemetry",
            "recorded_at_epoch_ms": 2500,
            "runtime": {},
            "depth_debug": {"detector_loaded_model_key": "unified_walksafe"},
        },
        session_id="field-new-telemetry",
        started_at=2000,
    )

    summary, exit_code = MODULE.write_summary(tmp_path, tmp_path / "summary", strict=True)

    assert exit_code == 1
    assert "missing_arcore_session_started" in summary["integrity"]["strict_failure_reasons"]


@pytest.mark.parametrize(
    ("provenance", "expected_reason"),
    [
        (
            {
                "source_commit": "unverified",
                "apk_sha256": APK_SHA256,
                "model_config_sha256": MODEL_CONFIG_SHA256,
            },
            "invalid_source_commit_provenance",
        ),
        (
            {
                "source_commit": SOURCE_COMMIT,
                "apk_sha256": "bad-apk",
                "model_config_sha256": MODEL_CONFIG_SHA256,
            },
            "invalid_apk_sha256_provenance",
        ),
        (
            {
                "source_commit": SOURCE_COMMIT,
                "apk_sha256": APK_SHA256,
                "model_config_sha256": "bad-model-config",
            },
            "invalid_model_config_sha256_provenance",
        ),
    ],
)
def test_strict_rejects_invalid_selected_session_provenance(
    tmp_path: Path,
    provenance: dict,
    expected_reason: str,
) -> None:
    write_session(
        tmp_path,
        [
            camera_event("camera_non_metric_session_started", CAMERA_START_FIELDS, 1400),
            camera_event("camera_non_metric_frame_analyzed", CAMERA_FRAME_FIELDS, 1450),
        ],
        provenance=provenance,
    )

    summary, exit_code = MODULE.write_summary(
        tmp_path,
        tmp_path / "summary",
        strict=True,
        strict_mode=MODULE.STRICT_MODE_CAMERA_NON_METRIC,
    )

    assert exit_code == 1
    assert expected_reason in summary["integrity"]["strict_failure_reasons"]


def test_strict_expected_provenance_accepts_match_and_rejects_mismatch(tmp_path: Path) -> None:
    source = tmp_path / "source"
    write_session(
        source,
        [
            camera_event("camera_non_metric_session_started", CAMERA_START_FIELDS, 1400),
            camera_event("camera_non_metric_frame_analyzed", CAMERA_FRAME_FIELDS, 1450),
        ],
    )

    matching, matching_exit = MODULE.write_summary(
        source,
        tmp_path / "matching",
        strict=True,
        strict_mode=MODULE.STRICT_MODE_CAMERA_NON_METRIC,
        expected_source_commit=SOURCE_COMMIT.upper(),
        expected_apk_sha256=APK_SHA256.upper(),
    )
    mismatching, mismatching_exit = MODULE.write_summary(
        source,
        tmp_path / "mismatching",
        strict=True,
        strict_mode=MODULE.STRICT_MODE_CAMERA_NON_METRIC,
        expected_source_commit="4" * 40,
        expected_apk_sha256="5" * 64,
    )

    assert matching_exit == 1
    assert matching["integrity"]["strict_failure_reasons"] == []
    assert mismatching_exit == 1
    assert "source_commit_mismatch" in mismatching["integrity"]["strict_failure_reasons"]
    assert "apk_sha256_mismatch" in mismatching["integrity"]["strict_failure_reasons"]


def test_camera_advisory_strict_requires_event_time_runtime_model(tmp_path: Path) -> None:
    summary, exit_code = write_camera_strict(
        tmp_path,
        advisory_fields={
            "direction": "CENTER",
            "metric": False,
            "tmap_authoritative": True,
            "reports_allowed": False,
        },
        require_advisory=True,
    )

    assert exit_code == 1
    reasons = summary["integrity"]["strict_failure_reasons"]
    assert "invalid_camera_non_metric_advisory_loaded_model_contract" in reasons
    assert "invalid_camera_non_metric_advisory_model_fallback_contract" in reasons


def test_camera_advisory_strict_accepts_event_time_fallback_runtime_model(tmp_path: Path) -> None:
    summary, exit_code = write_camera_strict(
        tmp_path,
        advisory_fields={
            "direction": "LEFT",
            "loaded_model": "legacy_two_model",
            "model_fallback_used": True,
            "metric": False,
            "tmap_authoritative": True,
            "reports_allowed": False,
        },
        require_advisory=True,
    )

    assert exit_code == 1
    assert summary["integrity"]["strict_failure_reasons"] == []
