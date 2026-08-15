from __future__ import annotations

from copy import deepcopy
from pathlib import Path

import pytest

from scripts import build_walksafe_phase1_exact257_successor_r016_20260813 as subject


ROOT = Path(__file__).resolve().parents[1]


def _predecessor() -> tuple[dict[Path, bytes], dict[Path, dict]]:
    raw = {path: (ROOT / path).read_bytes() for path in subject.PREDECESSOR_PATHS}
    documents = {
        path: subject.trace.strict_json_bytes(raw[path], path.as_posix())
        for path in subject.PREDECESSOR_PATHS
    }
    return raw, documents


def _synthetic_bindings() -> list[dict]:
    bindings = [
        {"binding_id": "R016-SRC-001", "subject_role": "NPC_V2_CORRECTION_IMPLEMENTATION_RESULT"},
        {"binding_id": "R016-SRC-002", "subject_role": "NPC_V2_CORRECTION_VERIFICATION_RESULT"},
        {"binding_id": "R016-SRC-003", "subject_role": "GAP008_R027_V2_CORRECTION_SUCCESSOR"},
    ]
    for index, (artifact_id, path) in enumerate(subject.TARGET_ARTIFACT_PATHS, start=4):
        bindings.append(
            {
                "binding_id": f"R016-SRC-{index:03d}",
                "subject_role": f"NPC_{artifact_id}_V2_CORRECTION_PHYSICAL_SUCCESSOR",
                "path": path.as_posix(),
                "sha256": f"{index:x}" * 64,
                "byte_length": index,
            }
        )
    return bindings


def test_r016_preserves_251_rows_and_adds_only_exact_six_v2_progress() -> None:
    raw, documents = _predecessor()
    subject.validate_predecessor_packet(documents, raw)
    predecessor = documents[subject.R015_LEDGER_REL]
    predecessor_bindings = [
        {"binding_id": f"R016-PRE-{index:03d}"}
        for index in range(1, 4)
    ]
    source_bindings = _synthetic_bindings()

    ledger = subject.build_ledger(
        predecessor,
        predecessor_bindings,
        source_bindings,
    )
    before = {row["artifact_type_code"]: row for row in predecessor["records"]}
    after = {row["artifact_type_code"]: row for row in ledger["records"]}
    changed = [artifact_id for artifact_id in before if before[artifact_id] != after[artifact_id]]

    assert set(changed) == set(subject.TARGET_ARTIFACT_IDS)
    assert len(before) - len(changed) == 251
    assert ledger["summaries"] == predecessor["summaries"]
    assert ledger["authorization_boundary"] == predecessor["authorization_boundary"]
    application = ledger[
        "r016_npc_single_admin_recovery_gap008_r027_exact6_v2_correction_application"
    ]
    assert application["status_delta_count"] == 0
    assert application["credit_delta_count"] == 0
    assert application["zero_credits"] == subject.ZERO_CREDITS
    for artifact_id in subject.TARGET_ARTIFACT_IDS:
        assert after[artifact_id]["queue_route"] == before[artifact_id]["queue_route"]
        assert after[artifact_id]["artifact_closure"] == before[artifact_id]["artifact_closure"]
        assert after[artifact_id]["claim_boundary"] == before[artifact_id]["claim_boundary"]
        observation = after[artifact_id]["progress_axes"]["internal_validation"]["observations"][-1]
        assert observation["evidence_schema"] == "V2_CORRECTION_ONLY"
        assert observation["credit_count"] == 0
        assert observation["actual_recovery_drill_status"] == "NOT_RUN"
        assert observation["release_status"] == "NOT_ELIGIBLE"

    sealed = subject.r015_builder.r014_builder.seal_json(
        ledger,
        subject.R016_LEDGER_REL,
    )
    subject.r015_builder.r014_builder.verify_nonself(
        subject.trace.strict_json_bytes(sealed, "R016 ledger"),
        subject.R016_LEDGER_REL,
    )


def test_r016_rejects_self_sealed_forged_r027() -> None:
    gap = {
        "metadata": {
            "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260813-027"
        },
        "assessments": [],
        "summary": {"headline": "rebuilt R027"},
    }
    rebuilt_gap = subject.trace._seal(gap, "report_content_sha256")
    forged = deepcopy(rebuilt_gap)
    forged["summary"]["headline"] = "self-sealed forged R027"
    forged = subject.trace._seal(forged, "report_content_sha256")
    forged_raw = subject.trace.json_text(forged).encode("utf-8")
    rebuilt = {
        path: subject.trace.json_text(rebuilt_gap)
        for path in subject.gap_builder.OUTPUT_PATHS
    }

    with pytest.raises(subject.BuildError, match="public deep rebuild exact bytes"):
        subject._require_r027_rebuild_exact(forged, forged_raw, rebuilt)


def test_r016_rejects_self_sealed_forged_exact_six_v2_marker() -> None:
    documents = {}
    raw = {}
    validated = {}
    for _, path in subject.TARGET_ARTIFACT_PATHS:
        expected = {"physical_path": path.as_posix()}
        subject.artifact_builder.v1._projection_seal(
            expected,
            subject.artifact_builder.SEAL_FIELD_BY_PATH[path],
        )
        expected_text = subject.trace.json_text(expected)
        documents[path] = deepcopy(expected)
        raw[path] = expected_text.encode("utf-8")
        validated[path] = expected_text
    target = subject.artifact_builder.RTM_REL
    forged = deepcopy(documents[target])
    forged["physical_path"] = "self-sealed-forged-path"
    subject.artifact_builder.v1._projection_seal(
        forged,
        subject.artifact_builder.SEAL_FIELD_BY_PATH[target],
    )
    forged_raw = subject.trace.json_text(forged).encode("utf-8")
    documents[target] = forged
    raw[target] = forged_raw

    with pytest.raises(subject.BuildError, match="deep-validated exact bytes"):
        subject._require_exact_six_deep_validation_exact(
            documents,
            raw,
            validated,
        )


def test_r016_packet_build_is_byte_deterministic_and_does_not_publish(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output_root = ROOT / subject.R016_DIR_REL
    before = {
        path.relative_to(output_root): path.read_bytes()
        for path in output_root.rglob("*")
        if path.is_file()
    } if output_root.exists() else {}
    predecessor_raw, predecessor_documents = _predecessor()
    producer_documents = {
        "implementation": {"document_id": "SYNTHETIC-V2-IMPLEMENTATION"},
        "verification": {"document_id": "SYNTHETIC-V2-VERIFICATION"},
        "gap": {"document_id": "SYNTHETIC-R027-GAP"},
    }
    producer_raw = {
        name: subject.trace.json_text(document).encode("utf-8")
        for name, document in producer_documents.items()
    }
    artifact_documents = {
        path: {"physical_path": path.as_posix()}
        for _, path in subject.TARGET_ARTIFACT_PATHS
    }
    artifact_raw = {
        path: subject.trace.json_text(document).encode("utf-8")
        for path, document in artifact_documents.items()
    }
    monkeypatch.setattr(subject, "validate_sources", lambda *_args, **_kwargs: None)

    def build() -> dict[Path, bytes]:
        return subject.build_documents(
            predecessor_documents,
            predecessor_raw,
            producer_documents["implementation"],
            producer_documents["verification"],
            producer_documents["gap"],
            artifact_documents,
            root=ROOT,
            implementation_raw=producer_raw["implementation"],
            verification_raw=producer_raw["verification"],
            gap_raw=producer_raw["gap"],
            artifact_raw=artifact_raw,
        )

    first = build()
    second = build()
    assert first == second
    assert set(first) == set(subject.OUTPUT_PATHS)
    for path, raw in first.items():
        document = subject.trace.strict_json_bytes(raw, path.as_posix())
        subject.r015_builder.r014_builder.verify_nonself(document, path)
    after = {
        path.relative_to(output_root): path.read_bytes()
        for path in output_root.rglob("*")
        if path.is_file()
    } if output_root.exists() else {}
    assert after == before


def test_r016_check_fails_closed_before_packet_publication(
    capsys: pytest.CaptureFixture[str], tmp_path: Path,
) -> None:
    assert not (tmp_path / subject.R016_DIR_REL).exists()
    assert subject.main(["--root", str(tmp_path), "--check"]) == 1
    assert "required source missing" in capsys.readouterr().out
    assert not (tmp_path / subject.R016_DIR_REL).exists()


def test_r016_write_is_add_only_and_idempotent_in_fixture_root(tmp_path: Path) -> None:
    outputs = {
        subject.R016_LEDGER_REL: subject.trace.json_text({"value": 1}).encode("utf-8"),
        subject.R016_EVIDENCE_REL: subject.trace.json_text({"value": 2}).encode("utf-8"),
        subject.R016_RECEIPT_REL: subject.trace.json_text({"value": 3}).encode("utf-8"),
    }
    subject.write_or_check_outputs(tmp_path, outputs, write=True)
    identities = {
        path: (tmp_path / path).stat()
        for path in subject.OUTPUT_PATHS
    }
    subject.write_or_check_outputs(tmp_path, outputs, write=True)
    subject.write_or_check_outputs(tmp_path, outputs, write=False)
    for path, expected in outputs.items():
        observed = (tmp_path / path).stat()
        assert (observed.st_dev, observed.st_ino) == (
            identities[path].st_dev,
            identities[path].st_ino,
        )
        assert (tmp_path / path).read_bytes() == expected

    different = dict(outputs)
    different[subject.R016_LEDGER_REL] = subject.trace.json_text({"value": 4}).encode(
        "utf-8"
    )
    with pytest.raises(subject.BuildError, match="existing output differs"):
        subject.write_or_check_outputs(tmp_path, different, write=True)
    assert (tmp_path / subject.R016_LEDGER_REL).read_bytes() == outputs[
        subject.R016_LEDGER_REL
    ]


def test_r016_rejects_tampered_r015_predecessor() -> None:
    raw, documents = _predecessor()
    tampered = dict(raw)
    tampered[subject.R015_EVIDENCE_REL] += b" "
    with pytest.raises(subject.BuildError, match="R015 predecessor SHA-256 differs"):
        subject.validate_predecessor_packet(documents, tampered)


def test_r016_writer_rejects_symlink_with_full_output_inventory(
    tmp_path: Path,
) -> None:
    outputs = {
        path: subject.trace.json_text({"path": path.as_posix()}).encode("utf-8")
        for path in subject.OUTPUT_PATHS
    }
    victim = tmp_path / "victim.json"
    victim.write_bytes(b"victim\n")
    target = tmp_path / subject.R016_LEDGER_REL
    target.parent.mkdir(parents=True)
    target.symlink_to(victim)

    with pytest.raises(subject.BuildError, match="safely open|symlink"):
        subject.write_or_check_outputs(tmp_path, outputs, write=True)

    assert victim.read_bytes() == b"victim\n"
    assert not (tmp_path / subject.R016_EVIDENCE_REL).exists()
    assert not (tmp_path / subject.R016_RECEIPT_REL).exists()
