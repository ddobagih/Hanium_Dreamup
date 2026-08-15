from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path

import pytest

from scripts import build_walksafe_fp022_gap_backlog_r028_20260814 as subject


ROOT = Path(__file__).resolve().parents[1]


def _write(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)


def _inputs() -> tuple[dict, dict, dict[Path, bytes]]:
    raw_by_path = {
        path: (ROOT / path).read_bytes() for path in subject.R027_INPUT_PATHS
    }
    return (
        subject.strict_json_bytes(raw_by_path[subject.R027_GAP_JSON_REL], "R027 gap"),
        subject.strict_json_bytes(
            raw_by_path[subject.R027_BACKLOG_JSON_REL], "R027 backlog"
        ),
        raw_by_path,
    )


def _evidence() -> tuple[bytes, bytes]:
    implementation = subject.sealed(
        {
            "schema_version": "walksafe.fp022-navigation-implementation-record.v1",
            "document_id": "WS-FP022-NAVIGATION-IMPLEMENTATION-20260814-001",
            "goal_id": subject.GOAL_ID,
            "policy_id": subject.POLICY_ID,
            "gap_id": subject.GAP_ID,
            "kind": "IMPLEMENTATION_RECORD",
            "status": "PASS",
            "credit_scope": "REPOSITORY_INTERNAL_ONLY",
            "observed_at": "2026-08-14T12:00:00+09:00",
            "completion_boundary": subject.completion_boundary(),
        },
        "implementation_record_content_sha256",
    )
    implementation_raw = subject.json_text(implementation).encode()
    verification = subject.sealed(
        {
            "schema_version": "walksafe.fp022-verification-result.v1",
            "document_id": "WS-FP022-NAVIGATION-VERIFICATION-20260814-001",
            "goal_id": subject.GOAL_ID,
            "kind": "VERIFICATION_RESULT",
            "status": "PASS",
            "credit_scope": "REPOSITORY_INTERNAL_ONLY",
            "observed_at": "2026-08-14T12:00:03+09:00",
            "implementation_record_sha256": subject.bytes_sha256(
                implementation_raw
            ),
            "completion_boundary": subject.completion_boundary(),
        },
        "verification_result_content_sha256",
    )
    return implementation_raw, subject.json_text(verification).encode()


def _build() -> tuple[dict[Path, str], dict, dict]:
    gap, backlog, raw_by_path = _inputs()
    implementation_raw, verification_raw = _evidence()
    return (
        subject.build_documents(
            gap,
            backlog,
            predecessor_raw_by_path=raw_by_path,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
        ),
        gap,
        backlog,
    )


def test_r028_changes_only_gap031_and_fp022_planning_state() -> None:
    first, predecessor_gap, predecessor_backlog = _build()
    second, _, _ = _build()
    assert first == second
    assert tuple(first) == subject.OUTPUT_PATHS

    gap = json.loads(first[subject.R028_GAP_JSON_REL])
    backlog = json.loads(first[subject.R028_BACKLOG_JSON_REL])
    subject.verify_seal(gap, "report_content_sha256", "R028 gap")
    subject.verify_seal(backlog, "backlog_content_sha256", "R028 backlog")

    before_by_id = {row["gap_id"]: row for row in predecessor_gap["assessments"]}
    after_by_id = {row["gap_id"]: row for row in gap["assessments"]}
    assert len(before_by_id) == len(after_by_id) == 68
    assert all(
        after_by_id[gap_id] == row
        for gap_id, row in before_by_id.items()
        if gap_id != subject.GAP_ID
    )
    assert before_by_id[subject.GAP_ID]["status"] == "CONFLICTING"
    assert after_by_id[subject.GAP_ID]["status"] == "PARTIAL"
    assert after_by_id[subject.GAP_ID]["formal_test_status"] == "NOT_RUN"
    assert after_by_id[subject.GAP_ID]["planned_test_ids"] == list(
        subject.FORMAL_TEST_IDS
    )
    assert after_by_id[subject.GAP_ID]["waived"] is False
    assert gap["summary"]["status_counts"] == subject.EXPECTED_AFTER_COUNTS
    assert gap["summary"]["implemented_and_formally_verified_count"] == 0
    assert gap["summary"]["release_status"] == "NOT_ELIGIBLE"
    assert gap["metadata"]["prepared_at"] == "2026-08-14T12:00:04+09:00"
    assert gap["authorization_boundary"] == predecessor_gap[
        "authorization_boundary"
    ]

    before_actions = {
        row["source_policy_id"]: row
        for row in predecessor_backlog["next_action_sequence"]
    }
    after_actions = {
        row["source_policy_id"]: row for row in backlog["next_action_sequence"]
    }
    assert all(
        after_actions[policy_id] == row
        for policy_id, row in before_actions.items()
        if policy_id != subject.POLICY_ID
    )
    assert before_actions[subject.POLICY_ID]["status"] == "CONFLICTING"
    assert after_actions[subject.POLICY_ID]["status"] == "PARTIAL"
    assert after_actions[subject.NEXT_POLICY_ID]["order"] == 25
    assert after_actions[subject.NEXT_POLICY_ID]["status"] == "CONFLICTING"
    assert backlog["next_single_action"] == {
        "action": after_actions[subject.NEXT_POLICY_ID]["action"],
        "epic_id": "EPIC-04",
        "gap_id": subject.NEXT_GAP_ID,
        "priority_rank": 25,
        "source_policy_id": subject.NEXT_POLICY_ID,
        "status": "PLANNED_NEXT",
        "work_item_id": "WS-GOAL-EPIC-04-FP-023-R001",
    }

    before_epics = {row["epic_id"]: row for row in predecessor_backlog["epics"]}
    after_epics = {row["epic_id"]: row for row in backlog["epics"]}
    assert all(
        after_epics[epic_id] == row
        for epic_id, row in before_epics.items()
        if epic_id != "EPIC-04"
    )
    assert before_epics["EPIC-04"]["current_status"] == "PLANNED"
    assert after_epics["EPIC-04"]["current_status"] == "IN_PROGRESS"
    assert after_epics["EPIC-04"]["deferred_release_gate_ids"] == before_epics[
        "EPIC-04"
    ]["deferred_release_gate_ids"]
    assert after_epics["EPIC-12"] == before_epics["EPIC-12"]
    assert backlog["authorization_boundary"] == predecessor_backlog[
        "authorization_boundary"
    ]


def test_r028_catalog_binds_only_current_result_raw_sha_and_length() -> None:
    outputs, predecessor_gap, _ = _build()
    implementation_raw, verification_raw = _evidence()
    gap = json.loads(outputs[subject.R028_GAP_JSON_REL])
    added = gap["evidence_catalog"][len(predecessor_gap["evidence_catalog"]) :]
    assert added == [
        {
            "completion_boundary": subject.completion_boundary(),
            "evidence_id": subject.EVIDENCE_ID,
            "evidence_type": "INTERNAL_IMPLEMENTATION_AND_AUTOMATED_VERIFICATION",
            "raw_result_bindings": [
                {
                    "byte_length": len(implementation_raw),
                    "kind": "IMPLEMENTATION_RECORD",
                    "path": subject.IMPLEMENTATION_REL.as_posix(),
                    "sha256": subject.bytes_sha256(implementation_raw),
                },
                {
                    "byte_length": len(verification_raw),
                    "kind": "VERIFICATION_RESULT",
                    "path": subject.VERIFICATION_REL.as_posix(),
                    "sha256": subject.bytes_sha256(verification_raw),
                },
            ],
            "status": "PASS",
            "title": "FP-022 repository-internal navigation implementation evidence",
        }
    ]
    encoded = json.dumps(added, ensure_ascii=False)
    assert "successor-trace" not in encoded
    assert "SUCCESSOR_TRACE" not in encoded

    boundary = added[0]["completion_boundary"]
    assert boundary["formal_test_status"] == "NOT_RUN"
    assert boundary["formal_test_credit_delta"] == 0
    assert boundary["actual_device_status"] == "NOT_RUN"
    assert boundary["device_credit_delta"] == 0
    assert boundary["external_review_status"] == "NOT_RUN"
    assert boundary["external_credit_delta"] == 0
    assert boundary["production_deployment_status"] == "NOT_RUN"
    assert boundary["deployment_credit_delta"] == 0
    assert boundary["release_status"] == "NOT_ELIGIBLE"
    assert boundary["release_credit_delta"] == 0


def test_r027_four_file_pins_and_document_raw_binding_fail_closed() -> None:
    gap, backlog, raw_by_path = _inputs()
    implementation_raw, verification_raw = _evidence()
    assert {
        path: {
            "sha256": subject.bytes_sha256(raw_by_path[path]),
            "byte_length": len(raw_by_path[path]),
        }
        for path in subject.R027_INPUT_PATHS
    } == subject.EXPECTED_R027_BINDING_BY_PATH

    for changed_path in subject.R027_INPUT_PATHS:
        changed = dict(raw_by_path)
        changed[changed_path] += b"changed\n"
        with pytest.raises(subject.BuildError, match="canonical R027 predecessor bytes differ"):
            subject.build_documents(
                gap,
                backlog,
                predecessor_raw_by_path=changed,
                implementation_raw=implementation_raw,
                verification_raw=verification_raw,
            )

    object_only_tamper = deepcopy(gap)
    object_only_tamper["assessments"][0]["status"] = "IMPLEMENTED"
    with pytest.raises(subject.BuildError, match="document/raw binding differs"):
        subject.build_documents(
            object_only_tamper,
            backlog,
            predecessor_raw_by_path=raw_by_path,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
        )


def test_build_outputs_reads_current_evidence_fail_closed_and_writes_nothing(
    tmp_path: Path,
) -> None:
    gap, backlog, predecessor_raw = _inputs()
    implementation_raw, verification_raw = _evidence()
    expected = subject.build_documents(
        gap,
        backlog,
        predecessor_raw_by_path=predecessor_raw,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
    )
    for path, raw in predecessor_raw.items():
        _write(tmp_path, path, raw)

    with pytest.raises(subject.BuildError, match="required source missing"):
        subject.build_outputs(tmp_path)
    _write(tmp_path, subject.IMPLEMENTATION_REL, implementation_raw)
    with pytest.raises(subject.BuildError, match="required source missing"):
        subject.build_outputs(tmp_path)
    _write(tmp_path, subject.VERIFICATION_REL, verification_raw)

    assert subject.build_outputs(tmp_path) == expected
    assert all(not (tmp_path / path).exists() for path in subject.OUTPUT_PATHS)


def test_result_identity_credit_and_canonical_json_fail_closed() -> None:
    gap, backlog, predecessor_raw = _inputs()
    implementation_raw, verification_raw = _evidence()
    implementation = subject.strict_json_bytes(implementation_raw, "implementation")
    verification = subject.strict_json_bytes(verification_raw, "verification")

    forged = deepcopy(implementation)
    forged["credit_scope"] = "FORMAL"
    forged = subject.sealed(forged, "implementation_record_content_sha256")
    forged_raw = subject.json_text(forged).encode()
    with pytest.raises(subject.BuildError, match="implementation identity or credit boundary differs"):
        subject.build_documents(
            gap,
            backlog,
            predecessor_raw_by_path=predecessor_raw,
            implementation_raw=forged_raw,
            verification_raw=verification_raw,
        )

    forged = deepcopy(verification)
    forged["completion_boundary"]["release_credit_delta"] = 1
    forged = subject.sealed(forged, "verification_result_content_sha256")
    forged_raw = subject.json_text(forged).encode()
    with pytest.raises(subject.BuildError, match="verification identity or credit boundary differs"):
        subject.build_documents(
            gap,
            backlog,
            predecessor_raw_by_path=predecessor_raw,
            implementation_raw=implementation_raw,
            verification_raw=forged_raw,
        )

    noncanonical_raw = json.dumps(implementation, ensure_ascii=False).encode()
    with pytest.raises(subject.BuildError, match="implementation JSON is noncanonical"):
        subject.build_documents(
            gap,
            backlog,
            predecessor_raw_by_path=predecessor_raw,
            implementation_raw=noncanonical_raw,
            verification_raw=verification_raw,
        )


def test_outputs_are_canonical_and_write_is_add_only(tmp_path: Path) -> None:
    outputs, _, _ = _build()
    for path in (subject.R028_GAP_JSON_REL, subject.R028_BACKLOG_JSON_REL):
        value = subject.strict_json_bytes(outputs[path].encode(), path.as_posix())
        assert outputs[path] == subject.json_text(value)
    for path in (subject.R028_GAP_MD_REL, subject.R028_BACKLOG_MD_REL):
        assert outputs[path].endswith("\n")

    subject.write_or_check_outputs(tmp_path, outputs, write=True)
    subject.write_or_check_outputs(tmp_path, outputs, write=False)
    before = {
        path: ((tmp_path / path).read_bytes(), (tmp_path / path).stat().st_ino)
        for path in subject.OUTPUT_PATHS
    }
    subject.write_or_check_outputs(tmp_path, outputs, write=True)
    assert before == {
        path: ((tmp_path / path).read_bytes(), (tmp_path / path).stat().st_ino)
        for path in subject.OUTPUT_PATHS
    }

    changed = dict(outputs)
    changed[subject.R028_GAP_MD_REL] += "changed\n"
    with pytest.raises(subject.BuildError, match="existing output differs"):
        subject.write_or_check_outputs(tmp_path, changed, write=False)
