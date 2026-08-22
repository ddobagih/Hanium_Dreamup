from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path

import pytest

from scripts import build_walksafe_fp046_gap_backlog_r029_candidate_20260815 as subject
from scripts import check_walksafe_goal_graph as generic_goal_graph


ROOT = Path(__file__).resolve().parents[1]


def _write(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)


def _inputs() -> tuple[dict, dict, dict[Path, bytes], dict[Path, bytes]]:
    r028_raw_by_path = {
        path: (ROOT / path).read_bytes() for path in subject.R028_INPUT_PATHS
    }
    current_source_raw_by_path = {
        path: (ROOT / path).read_bytes() for path in subject.CURRENT_SOURCE_PATHS
    }
    return (
        subject.strict_json_bytes(
            r028_raw_by_path[subject.R028_GAP_JSON_REL], "R028 gap"
        ),
        subject.strict_json_bytes(
            r028_raw_by_path[subject.R028_BACKLOG_JSON_REL], "R028 backlog"
        ),
        r028_raw_by_path,
        current_source_raw_by_path,
    )


def _bindings(raw_by_path: dict[Path, bytes]) -> dict[Path, dict[str, int | str]]:
    return {
        path: {
            "sha256": subject.bytes_sha256(raw),
            "byte_length": len(raw),
        }
        for path, raw in raw_by_path.items()
    }


def _build() -> tuple[dict[Path, str], dict, dict, dict[Path, bytes], dict[Path, bytes]]:
    gap, backlog, r028_raw_by_path, current_source_raw_by_path = _inputs()
    return (
        subject.build_documents(
            gap,
            backlog,
            r028_raw_by_path=r028_raw_by_path,
            current_source_raw_by_path=current_source_raw_by_path,
        ),
        gap,
        backlog,
        r028_raw_by_path,
        current_source_raw_by_path,
    )


def _generic_changed_subjects(
    root: Path,
    *,
    r028_raw_by_path: dict[Path, bytes],
    gap_text: str,
    backlog_text: str,
) -> tuple[list[str], dict[str, list[str]]]:
    for path in subject.R028_INPUT_PATHS:
        _write(root, path, r028_raw_by_path[path])
    _write(root, subject.R029_GAP_JSON_REL, gap_text.encode("utf-8"))
    _write(root, subject.R029_BACKLOG_JSON_REL, backlog_text.encode("utf-8"))

    def binding(role: str, document_id: str, path: Path) -> dict[str, str]:
        return {
            "role": role,
            "document_id": document_id,
            "path": path.as_posix(),
            "file_sha256": generic_goal_graph.sha256_file(root / path),
        }

    return generic_goal_graph.canonical_changed_subject_ids_by_role(
        root,
        changed_roles=["IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"],
        bindings_before={
            "IMPLEMENTATION_GAP": binding(
                "IMPLEMENTATION_GAP",
                "WS-IMPLEMENTATION-GAP-ANALYSIS-20260814-028",
                subject.R028_GAP_JSON_REL,
            ),
            "IMPLEMENTATION_BACKLOG": binding(
                "IMPLEMENTATION_BACKLOG",
                "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260814-028",
                subject.R028_BACKLOG_JSON_REL,
            ),
        },
        bindings_after={
            "IMPLEMENTATION_GAP": binding(
                "IMPLEMENTATION_GAP",
                "WS-IMPLEMENTATION-GAP-ANALYSIS-20260815-029",
                subject.R029_GAP_JSON_REL,
            ),
            "IMPLEMENTATION_BACKLOG": binding(
                "IMPLEMENTATION_BACKLOG",
                "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260815-029",
                subject.R029_BACKLOG_JSON_REL,
            ),
        },
    )


def test_r029_candidate_preserves_all_non_fp046_gap055_subjects(
    tmp_path: Path,
) -> None:
    first, predecessor_gap, predecessor_backlog, r028_raw, source_raw = _build()
    second, _, _, _, _ = _build()
    assert first == second
    assert subject.build_outputs(ROOT) == first
    assert tuple(first) == subject.OUTPUT_PATHS
    assert {
        path: {
            "sha256": subject.bytes_sha256(r028_raw[path]),
            "byte_length": len(r028_raw[path]),
        }
        for path in subject.R028_INPUT_PATHS
    } == subject.EXPECTED_R028_BINDING_BY_PATH
    assert _bindings(source_raw) == subject.EXPECTED_CURRENT_SOURCE_BINDING_BY_PATH

    gap = json.loads(first[subject.R029_GAP_JSON_REL])
    backlog = json.loads(first[subject.R029_BACKLOG_JSON_REL])
    discovery = json.loads(first[subject.DISCOVERY_JSON_REL])
    subject.verify_seal(gap, "report_content_sha256", "R029 gap")
    subject.verify_seal(backlog, "backlog_content_sha256", "R029 backlog")
    subject.verify_seal(discovery, "discovery_content_sha256", "discovery")
    assert {
        "canonical_application_status",
        "approval_status",
        "operational_application_boundary",
    }.isdisjoint(gap)
    assert {
        "canonical_application_status",
        "approval_status",
        "operational_application_boundary",
    }.isdisjoint(backlog)

    assert discovery["candidate_status"] == "DISCOVERED_NOT_CANONICALLY_APPLIED"
    assert discovery["canonical_application_status"] == "NOT_APPLIED"
    assert discovery["approval_status"] == "NOT_REQUESTED"
    assert discovery["approval_claimed"] is False
    assert discovery["external_independence_claimed"] is False
    assert discovery["operational_application_boundary"] == (
        subject.operational_application_boundary()
    )
    assert discovery["impact_scope"] == {
        "changed_subjects": [
            {"source_policy_id": subject.POLICY_ID, "gap_id": subject.GAP_ID}
        ],
        "derived_aggregate_subject": {
            "epic_id": "EPIC-03",
            "fields": ["current_status", "current_status_reason"],
        },
    }
    assert discovery["proposed_next_single_action"] == subject._next_single_action()
    assert [binding["path"] for binding in discovery["source_bindings"]] == [
        path.as_posix() for path in subject.CURRENT_SOURCE_PATHS
    ]
    assert [assertion["status"] for assertion in discovery["reproduction_assertions"]] == [
        "UNEXECUTED_CURRENT_REGRESSION_ASSERTION",
        "UNEXECUTED_CURRENT_REGRESSION_ASSERTION",
    ]
    assert {assertion["assertion_id"] for assertion in discovery["reproduction_assertions"]} == {
        "ASSERT-FP046-PRIVACY-RATE-GROUP-CHECK-001",
        "ASSERT-FP046-NGINX-DELETION-INGRESS-001",
    }
    assert discovery["completion_boundary"] == subject.zero_credit_boundary()

    before_by_id = {row["gap_id"]: row for row in predecessor_gap["assessments"]}
    after_by_id = {row["gap_id"]: row for row in gap["assessments"]}
    assert len(before_by_id) == len(after_by_id) == 68
    assert all(
        after_by_id[gap_id] == row
        for gap_id, row in before_by_id.items()
        if gap_id != subject.GAP_ID
    )
    target = after_by_id[subject.GAP_ID]
    assert target["status"] == "PARTIAL"
    assert target["formal_test_status"] == "NOT_RUN"
    assert target["planned_test_ids"] == list(subject.FORMAL_TEST_IDS)
    assert target["waived"] is False
    assert target["regression_reopen"]["status"] == "REOPEN_REQUIRED"
    assert target["regression_reopen"]["reopen_required"] is True
    assert target["regression_reopen"]["next_action"] == {
        "status": "PLANNED",
        "goal_successor_id": subject.SUCCESSOR_GOAL_ID,
        "action": (
            "FP-046/GAP-055의 privacy rate_group CHECK와 account deletion nginx "
            "ingress 회귀를 수정하고 현재 회귀를 검증한다."
        ),
    }
    assert target["regression_reopen"]["completion_boundary"] == (
        subject.canonical_reopen_zero_credit_boundary()
    )
    assert "REGRESSION_DISCOVERY_CANDIDATE_ONLY" not in first[
        subject.R029_GAP_JSON_REL
    ]
    assert "REGRESSION_DISCOVERY_CANDIDATE_ONLY" not in first[
        subject.R029_BACKLOG_JSON_REL
    ]
    assert gap["summary"]["status_counts"] == subject.EXPECTED_STATUS_COUNTS
    assert gap["summary"]["implemented_and_formally_verified_count"] == 0
    assert gap["summary"]["release_status"] == "NOT_ELIGIBLE"
    assert gap["reassessment_scope"] == {
        "mode": "FOCUSED_EPIC03_FP046_GAP055_REGRESSION_REASSESSMENT_WITH_R028_CARRY_FORWARD",
        "directly_reassessed_gap_ids": [subject.GAP_ID],
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": [subject.GAP_ID],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [
            row["gap_id"]
            for row in gap["assessments"]
            if row["gap_id"] != subject.GAP_ID
        ],
    }

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
    assert after_actions[subject.POLICY_ID]["status"] == "REOPEN_REQUIRED"
    assert "regression_reopen" not in after_actions[subject.POLICY_ID]
    before_epics = {row["epic_id"]: row for row in predecessor_backlog["epics"]}
    after_epics = {row["epic_id"]: row for row in backlog["epics"]}
    assert all(
        after_epics[epic_id] == row
        for epic_id, row in before_epics.items()
        if epic_id != "EPIC-03"
    )
    assert after_epics["EPIC-03"]["current_status"] == "PLANNED"
    assert "regression_reopen_aggregate" not in after_epics["EPIC-03"]
    assert backlog["next_single_action"] == predecessor_backlog["next_single_action"]
    assert backlog["source_predecessor"] == {
        "path": subject.R028_BACKLOG_JSON_REL.as_posix(),
        "file_sha256": subject.EXPECTED_R028_BINDING_BY_PATH[
            subject.R028_BACKLOG_JSON_REL
        ]["sha256"],
        "preserved_unchanged": True,
    }

    errors, subjects = _generic_changed_subjects(
        tmp_path,
        r028_raw_by_path=r028_raw,
        gap_text=first[subject.R029_GAP_JSON_REL],
        backlog_text=first[subject.R029_BACKLOG_JSON_REL],
    )
    assert errors == []
    assert subjects["IMPLEMENTATION_GAP"] == [subject.POLICY_ID, subject.GAP_ID]
    assert [
        subject_id
        for subject_id in subjects["IMPLEMENTATION_BACKLOG"]
        if subject_id != "*"
    ] == [subject.POLICY_ID]
    assert subjects["IMPLEMENTATION_BACKLOG"] == ["*", subject.POLICY_ID]

    legacy_provenance_backlog = deepcopy(backlog)
    legacy_provenance_backlog["source_predecessor"] = deepcopy(
        predecessor_backlog["source_predecessor"]
    )
    legacy_provenance_backlog["backlog_content_sha256"] = subject.object_sha256(
        {
            key: value
            for key, value in legacy_provenance_backlog.items()
            if key != "backlog_content_sha256"
        }
    )
    assert {
        key
        for key in set(backlog) | set(legacy_provenance_backlog)
        if backlog.get(key) != legacy_provenance_backlog.get(key)
    } == {"source_predecessor", "backlog_content_sha256"}
    legacy_errors, legacy_subjects = _generic_changed_subjects(
        tmp_path,
        r028_raw_by_path=r028_raw,
        gap_text=first[subject.R029_GAP_JSON_REL],
        backlog_text=subject.json_text(legacy_provenance_backlog),
    )
    assert legacy_errors == [
        "canonical IMPLEMENTATION_BACKLOG: "
        "implementation Backlog predecessor binding differs"
    ]
    assert legacy_subjects["IMPLEMENTATION_GAP"] == [subject.POLICY_ID, subject.GAP_ID]
    assert legacy_subjects["IMPLEMENTATION_BACKLOG"] == [subject.POLICY_ID]


def test_r029_fails_closed_on_each_r028_predecessor_pin_and_object_drift() -> None:
    gap, backlog, r028_raw, source_raw = _inputs()
    for changed_path in subject.R028_INPUT_PATHS:
        changed = dict(r028_raw)
        changed[changed_path] += b"changed\n"
        with pytest.raises(
            subject.BuildError,
            match="canonical R028 predecessor bytes differ",
        ):
            subject.build_documents(
                gap,
                backlog,
                r028_raw_by_path=changed,
                current_source_raw_by_path=source_raw,
            )

    forged_gap = deepcopy(gap)
    forged_gap["assessments"][0]["status"] = "IMPLEMENTED"
    with pytest.raises(
        subject.BuildError,
        match="R028 gap document/raw binding differs",
    ):
        subject.build_documents(
            forged_gap,
            backlog,
            r028_raw_by_path=r028_raw,
            current_source_raw_by_path=source_raw,
        )


@pytest.mark.parametrize(
    ("path", "replacement", "message"),
    [
        (
            subject.RATE_LIMIT_MIGRATION_REL,
            (
                b"rate_group IN ('report', 'navigation', 'detect', 'export', 'admin_read')",
                b"rate_group IN ('report', 'navigation', 'detect', 'export', 'admin_read', 'privacy')",
            ),
            "privacy migration CHECK does not omit privacy",
        ),
        (
            subject.NGINX_GATEWAY_REL,
            (
                b"    # No health, Web/PWA, admin, legacy API, backend, or catch-all proxy is\n",
                b"    location = /privacy/account-deletions { proxy_pass http://127.0.0.1:8081; }\n"
                b"    # No health, Web/PWA, admin, legacy API, backend, or catch-all proxy is\n",
            ),
            "nginx deletion ingress unexpectedly exists",
        ),
        (
            subject.FIELD_TEST_SECURITY_REL,
            (b'"privacy": 12,', b'"privacy": 13,'),
            "privacy runtime rate_group assertion differs",
        ),
    ],
)
def test_r029_semantic_regression_assertions_fail_first(
    path: Path,
    replacement: tuple[bytes, bytes],
    message: str,
) -> None:
    gap, backlog, r028_raw, source_raw = _inputs()
    before, after = replacement
    assert before in source_raw[path]
    changed_source = dict(source_raw)
    changed_source[path] = changed_source[path].replace(before, after, 1)
    with pytest.raises(subject.BuildError, match=message):
        subject.build_documents(
            gap,
            backlog,
            r028_raw_by_path=r028_raw,
            current_source_raw_by_path=changed_source,
            expected_current_source_binding_by_path=_bindings(changed_source),
        )


def test_build_outputs_only_reads_inputs_until_add_only_writer_is_requested(
    tmp_path: Path,
) -> None:
    expected, _, _, r028_raw, source_raw = _build()
    for path, raw in {**r028_raw, **source_raw}.items():
        _write(tmp_path, path, raw)

    assert subject.build_outputs(tmp_path) == expected
    assert all(not (tmp_path / path).exists() for path in subject.OUTPUT_PATHS)

    subject.write_or_check_outputs(tmp_path, expected, write=True)
    assert subject.main(["--root", str(tmp_path), "--check"]) == 0
    before = {
        path: ((tmp_path / path).read_bytes(), (tmp_path / path).stat().st_ino)
        for path in subject.OUTPUT_PATHS
    }
    subject.write_or_check_outputs(tmp_path, expected, write=True)
    assert before == {
        path: ((tmp_path / path).read_bytes(), (tmp_path / path).stat().st_ino)
        for path in subject.OUTPUT_PATHS
    }

    changed = dict(expected)
    changed[subject.DISCOVERY_MD_REL] += "changed\n"
    with pytest.raises(subject.BuildError, match="existing output differs"):
        subject.write_or_check_outputs(tmp_path, changed, write=False)
