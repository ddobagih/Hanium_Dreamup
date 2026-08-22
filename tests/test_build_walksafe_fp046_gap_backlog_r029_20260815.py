from __future__ import annotations

from pathlib import Path

import pytest

from scripts import build_walksafe_fp046_gap_backlog_r029_20260815 as subject
from scripts import build_walksafe_fp046_gap_backlog_r029_candidate_20260815 as candidate


ROOT = Path(__file__).resolve().parents[1]


def _write(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)


def _stage_candidate_inputs(root: Path) -> None:
    for path in candidate.R028_INPUT_PATHS + candidate.CURRENT_SOURCE_PATHS:
        _write(root, path, (ROOT / path).read_bytes())


def _candidate_bindings(outputs: dict[Path, str]) -> dict[Path, dict[str, int | str]]:
    return {
        path: {
            "sha256": subject.bytes_sha256(text.encode("utf-8")),
            "byte_length": len(text.encode("utf-8")),
        }
        for path, text in outputs.items()
    }


def test_build_is_side_effect_free_and_maps_exact_candidate_bytes(tmp_path: Path) -> None:
    _stage_candidate_inputs(tmp_path)

    outputs, evidence = subject.build_outputs_and_source_evidence(tmp_path)
    candidate_outputs = candidate.build_outputs(tmp_path)

    assert tuple(outputs) == subject.CANONICAL_OUTPUT_PATHS
    assert all(not (tmp_path / path).exists() for path in subject.CANONICAL_OUTPUT_PATHS)
    assert all(not (tmp_path / path).exists() for path in candidate.OUTPUT_PATHS)
    assert _candidate_bindings(candidate_outputs) == subject.EXPECTED_CANDIDATE_OUTPUT_BINDINGS
    for candidate_path, canonical_path in subject.CANDIDATE_TO_CANONICAL.items():
        assert outputs[canonical_path].encode("utf-8") == candidate_outputs[
            candidate_path
        ].encode("utf-8")

    assert evidence["candidate_only_paths"] == [
        path.as_posix() for path in subject.CANDIDATE_ONLY_PATHS
    ]
    assert evidence["discovery_boundary"] == {
        "canonical_application_status": "NOT_APPLIED",
        "publication_scope": "CANDIDATE_ONLY_SOURCE_EVIDENCE",
    }
    evidence_bindings = {
        Path(row["path"]): {
            "sha256": row["sha256"],
            "byte_length": row["byte_length"],
        }
        for row in evidence["candidate_output_bindings"]
    }
    assert evidence_bindings == subject.EXPECTED_CANDIDATE_OUTPUT_BINDINGS
    assert {row["canonical_path"] for row in evidence["canonical_mappings"]} == {
        path.as_posix() for path in subject.CANONICAL_OUTPUT_PATHS
    }


def test_add_only_temp_root_write_then_exact_check(tmp_path: Path) -> None:
    _stage_candidate_inputs(tmp_path)
    outputs = subject.build_outputs(tmp_path)

    assert subject.main(["--root", str(tmp_path), "--write"]) == 0
    assert {
        path: (tmp_path / path).read_text(encoding="utf-8")
        for path in subject.CANONICAL_OUTPUT_PATHS
    } == outputs
    assert subject.main(["--root", str(tmp_path), "--check"]) == 0
    assert all(not (tmp_path / path).exists() for path in candidate.OUTPUT_PATHS)


def test_add_only_write_rejects_any_existing_target_without_overwrite(
    tmp_path: Path,
) -> None:
    _stage_candidate_inputs(tmp_path)
    outputs = subject.build_outputs(tmp_path)
    subject.write_or_check_outputs(tmp_path, outputs, write=True)
    before = {
        path: (tmp_path / path).read_bytes() for path in subject.CANONICAL_OUTPUT_PATHS
    }

    with pytest.raises(subject.BuildError, match="add-only canonical target already exists"):
        subject.write_or_check_outputs(tmp_path, outputs, write=True)

    assert before == {
        path: (tmp_path / path).read_bytes() for path in subject.CANONICAL_OUTPUT_PATHS
    }


def test_candidate_discovery_and_input_tampering_fail_closed(tmp_path: Path) -> None:
    _stage_candidate_inputs(tmp_path)
    candidate_outputs = candidate.build_outputs(tmp_path)

    tampered_candidate = dict(candidate_outputs)
    tampered_candidate[candidate.R029_GAP_MD_REL] += "tampered\n"
    with pytest.raises(subject.BuildError, match="candidate output bytes differ"):
        subject.validate_candidate_outputs(tmp_path, tampered_candidate)

    tampered_discovery = dict(candidate_outputs)
    tampered_discovery[candidate.DISCOVERY_JSON_REL] += "\n"
    with pytest.raises(subject.BuildError, match="candidate output bytes differ"):
        subject.validate_candidate_outputs(tmp_path, tampered_discovery)

    source_path = tmp_path / candidate.RATE_LIMIT_MIGRATION_REL
    source_path.write_bytes(source_path.read_bytes() + b"\n# tampered\n")
    with pytest.raises(
        subject.BuildError, match="canonical regression source bytes differ"
    ):
        subject.build_outputs(tmp_path)


def test_exact_paths_hashes_and_candidate_only_discovery_boundary(tmp_path: Path) -> None:
    _stage_candidate_inputs(tmp_path)
    outputs, evidence = subject.build_outputs_and_source_evidence(tmp_path)

    assert tuple(subject.CANDIDATE_TO_CANONICAL) == (
        candidate.R029_GAP_JSON_REL,
        candidate.R029_GAP_MD_REL,
        candidate.R029_BACKLOG_JSON_REL,
        candidate.R029_BACKLOG_MD_REL,
    )
    assert tuple(subject.CANDIDATE_TO_CANONICAL.values()) == (
        Path(
            "docs/control/audits/"
            "walksafe-implementation-gap-analysis-20260815-r029.json"
        ),
        Path(
            "docs/control/audits/"
            "walksafe-implementation-gap-analysis-20260815-r029.md"
        ),
        Path(
            "docs/control/audits/"
            "walksafe-implementation-remediation-backlog-20260815-r029.json"
        ),
        Path(
            "docs/control/audits/"
            "walksafe-implementation-remediation-backlog-20260815-r029.md"
        ),
    )
    assert set(subject.CANDIDATE_ONLY_PATHS).isdisjoint(outputs)
    assert {
        row["candidate_path"] for row in evidence["canonical_mappings"]
    }.isdisjoint({path.as_posix() for path in subject.CANDIDATE_ONLY_PATHS})
    for candidate_path, canonical_path in subject.CANDIDATE_TO_CANONICAL.items():
        raw = outputs[canonical_path].encode("utf-8")
        assert {
            "sha256": subject.bytes_sha256(raw),
            "byte_length": len(raw),
        } == subject.EXPECTED_CANDIDATE_OUTPUT_BINDINGS[candidate_path]
