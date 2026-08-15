from __future__ import annotations

from copy import deepcopy
import json
import os
from pathlib import Path

import pytest

from scripts import build_walksafe_npc_single_admin_recovery_artifact_trace_correction_v2_20260813 as subject


ROOT = Path(__file__).resolve().parents[1]


def _v1_raw() -> dict[Path, bytes]:
    result: dict[Path, bytes] = {}
    for path in subject.OUTPUT_PATHS:
        raw = (ROOT / path).read_bytes()
        value = json.loads(raw)
        if subject.MARKER_FIELD in value:
            value.pop(subject.MARKER_FIELD)
            if path == subject.DOC05_REL:
                changes = value["changes"]
                assert changes[-1]["change_id"] == subject.CHANGE_ID
                changes.pop()
                if isinstance(value.get("summary"), dict):
                    value["summary"]["change_count"] = len(changes)
                    value["summary"]["last_change_id"] = changes[-1]["change_id"]
            subject.v1._projection_seal(value, subject.SEAL_FIELD_BY_PATH[path])
            raw = subject.json_text(value).encode()
        assert subject.bytes_sha256(raw) == subject.EXPECTED_V1_SHA256_BY_PATH[path]
        result[path] = raw
    return result


def _producer_inputs() -> tuple[dict[Path, bytes], tuple[dict, dict, dict, dict]]:
    manifest = {
        "files": [
            {
                "path": "backend/app/services/admin_security.py",
                "sha256": "a" * 64,
                "byte_count": 10,
            }
        ],
        "file_count": 1,
        "path_set_sha256": "b" * 64,
        "content_set_sha256": "c" * 64,
    }
    manifest["manifest_content_sha256"] = subject.trace.object_sha256(manifest)
    implementation = subject.trace._seal(
        {
            "schema_version": "1.0",
            "document_id": "TEST-NPC-V2-IMPLEMENTATION",
            "kind": "IMPLEMENTATION_RECORD",
            "evidence_schema": "V2_CORRECTION_ONLY",
            "goal_id": subject.GOAL_ID,
            "work_item_id": subject.trace.WORK_ITEM_ID,
            "policy_id": subject.trace.POLICY_ID,
            "gap_id": subject.trace.GAP_ID,
            "status": "PASS_INTERNAL",
            "observed_at": "2026-08-13T07:30:00+09:00",
            "scope_kind": "EXACT_ORDERED_NPC_SINGLE_ADMIN_RECOVERY_FINAL_CONTENT_MANIFEST",
            "final_content_manifest": manifest,
            "completion_boundary": subject.trace.completion_boundary(),
        },
        "implementation_record_content_sha256",
    )
    implementation_raw = subject.json_text(implementation).encode()
    verification = subject.trace._seal(
        {
            "schema_version": "1.0",
            "document_id": "TEST-NPC-V2-VERIFICATION",
            "kind": "VERIFICATION_RESULT",
            "evidence_schema": "V2_CORRECTION_ONLY",
            "goal_id": subject.GOAL_ID,
            "status": "PASS_INTERNAL",
            "observed_at": "2026-08-13T07:30:00+09:00",
            "implementation_record_sha256": subject.bytes_sha256(implementation_raw),
            "completion_boundary": subject.trace.completion_boundary(),
        },
        "verification_result_content_sha256",
    )
    verification_raw = subject.json_text(verification).encode()
    gap = subject.trace._seal(
        {
            "schema_version": "walksafe.implementation-gap-analysis.v1",
            "metadata": {
                "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260813-027"
            },
            "source_predecessor": {
                "path": subject.r027.R026_GAP_JSON_REL.as_posix(),
                "file_sha256": subject.r027.EXPECTED_R026_SHA256_BY_PATH[
                    subject.r027.R026_GAP_JSON_REL
                ],
                "preserved_unchanged": True,
            },
            "npc_single_admin_recovery_evidence_correction": {
                "predecessor_current_internal_evidence_disposition": "SUPERSEDED_BY_V2_CORRECTION_ONLY",
                "formal_device_drill_external_deployment_approval_release_credit_promoted": False,
            },
            "assessments": [
                {
                    "gap_id": subject.trace.GAP_ID,
                    "status": "PARTIAL",
                    "formal_test_status": "NOT_RUN",
                },
                {
                    "gap_id": "GAP-068",
                    "status": "BLOCKED",
                    "formal_test_status": "NOT_RUN",
                },
            ],
            "summary": {
                "status_counts": deepcopy(subject.r027.EXPECTED_STATUS_COUNTS),
                "implemented_and_formally_verified_count": 0,
                "release_status": "NOT_ELIGIBLE",
            },
        },
        "report_content_sha256",
    )
    gap_raw = subject.json_text(gap).encode()
    gap_markdown_raw = b"# synthetic R027 gap\n"
    backlog = {
        "schema_version": "walksafe.implementation-remediation-backlog.v1",
        "metadata": {
            "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260813-027"
        },
    }
    backlog_raw = subject.json_text(backlog).encode()
    backlog_markdown_raw = b"# synthetic R027 backlog\n"
    review = {
        "schema_version": "walksafe.npc-single-admin-recovery.review-result.v2",
        "document_id": "TEST-NPC-REVIEW-R001",
        "goal_id": subject.GOAL_ID,
        "round_id": subject.r027.R001_ROUND_ID,
        "decision": "REJECTED",
    }
    review_raw = subject.json_text(review).encode()
    raw = {
        subject.trace.V2_IMPLEMENTATION_REL: implementation_raw,
        subject.trace.V2_VERIFICATION_REL: verification_raw,
        subject.R027_GAP_REL: gap_raw,
        subject.r027.R027_GAP_MD_REL: gap_markdown_raw,
        subject.r027.R027_BACKLOG_JSON_REL: backlog_raw,
        subject.r027.R027_BACKLOG_MD_REL: backlog_markdown_raw,
        subject.R001_REJECTED_REVIEW_REL: review_raw,
        subject.BUILDER_REL: (ROOT / subject.BUILDER_REL).read_bytes(),
    }
    return raw, (implementation, verification, gap, review)


def _materialize(root: Path) -> dict[Path, bytes]:
    root.mkdir()
    predecessor = _v1_raw()
    live_raw, _ = _producer_inputs()
    for path, raw in {**predecessor, **live_raw}.items():
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
    return predecessor


def _build_direct() -> tuple[dict[Path, bytes], dict[Path, str]]:
    predecessor_raw = _v1_raw()
    predecessors = {
        path: subject.trace.strict_json_bytes(raw, path.as_posix())
        for path, raw in predecessor_raw.items()
    }
    live_raw, values = _producer_inputs()
    implementation, verification, gap, review = values
    outputs = subject.build_documents(
        predecessors,
        predecessor_raw,
        implementation,
        verification,
        gap,
        review,
        implementation_raw=live_raw[subject.trace.V2_IMPLEMENTATION_REL],
        verification_raw=live_raw[subject.trace.V2_VERIFICATION_REL],
        gap_raw=live_raw[subject.R027_GAP_REL],
        review_raw=live_raw[subject.R001_REJECTED_REVIEW_REL],
        generator_sha256=subject.bytes_sha256(live_raw[subject.BUILDER_REL]),
        r027_raw_by_path={
            path: live_raw[path] for path in subject.R027_OUTPUT_PATHS
        },
    )
    return predecessor_raw, outputs


def _publish_fixture(root: Path) -> None:
    _materialize(root)
    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(subject, "_deep_validate_live_inputs", lambda *_: None)
        subject.write_successor(root)


def _inject_coordinated_self_sealed_field(root: Path) -> None:
    rtm_path = root / subject.RTM_REL
    rtm = json.loads(rtm_path.read_bytes())
    rtm[subject.MARKER_FIELD]["attacker_controlled"] = True
    subject.v1._projection_seal(rtm, subject.SEAL_FIELD_BY_PATH[subject.RTM_REL])
    rtm_raw = subject.json_text(rtm).encode()
    rtm_path.write_bytes(rtm_raw)

    doc01_path = root / subject.DOC01_REL
    doc01 = json.loads(doc01_path.read_bytes())
    doc01[subject.MARKER_FIELD]["bound_corrected_outputs"][
        subject.RTM_REL.as_posix()
    ] = {
        "sha256": subject.bytes_sha256(rtm_raw),
        "byte_length": len(rtm_raw),
    }
    subject.v1._projection_seal(
        doc01, subject.SEAL_FIELD_BY_PATH[subject.DOC01_REL]
    )
    doc01_path.write_bytes(subject.json_text(doc01).encode())


def test_exact_six_v2_correction_is_add_only_and_keeps_all_credit_not_run() -> None:
    predecessor_raw, outputs = _build_direct()
    successor_raw = {path: raw.encode() for path, raw in outputs.items()}
    successors = {path: json.loads(raw) for path, raw in outputs.items()}
    live_raw, values = _producer_inputs()
    expected_bindings = subject._input_bindings(
        *values,
        implementation_raw=live_raw[subject.trace.V2_IMPLEMENTATION_REL],
        verification_raw=live_raw[subject.trace.V2_VERIFICATION_REL],
        gap_raw=live_raw[subject.R027_GAP_REL],
        review_raw=live_raw[subject.R001_REJECTED_REVIEW_REL],
        r027_raw_by_path={
            path: live_raw[path] for path in subject.R027_OUTPUT_PATHS
        },
    )
    subject.validate_correction_documents(
        successors, successor_raw, expected_bindings=expected_bindings
    )

    for path in subject.OUTPUT_PATHS:
        before = json.loads(predecessor_raw[path])
        after = successors[path]
        assert after[subject.V1_MARKER_FIELD] == before[subject.V1_MARKER_FIELD]
        marker = after[subject.MARKER_FIELD]
        assert {
            row["path"] for row in marker["input_bindings"]
        } >= {path.as_posix() for path in subject.R027_OUTPUT_PATHS}
        assert marker["trace_boundary"]["formal_test_status"] == "NOT_RUN"
        assert marker["trace_boundary"]["actual_device_status"] == "NOT_RUN"
        assert marker["trace_boundary"]["actual_recovery_drill_status"] == "NOT_RUN"
        assert marker["trace_boundary"]["external_evidence_status"] == "NOT_RUN"
        assert marker["trace_boundary"]["production_deployment_status"] == "NOT_RUN"
        assert marker["trace_boundary"]["release_status"] == "NOT_ELIGIBLE"
        assert marker["trace_boundary"]["release_credit_count"] == 0
    assert successors[subject.DOC05_REL]["changes"][-1]["change_id"] == subject.CHANGE_ID


def test_writer_is_idempotent_from_v1_and_v2_live_stages(tmp_path: Path) -> None:
    root = tmp_path / "idempotent"
    _materialize(root)
    expected = subject._build_from_raw(
        {path: (root / path).read_bytes() for path in subject.OUTPUT_PATHS},
        {path: (root / path).read_bytes() for path in subject.LIVE_INPUT_PATHS},
    )
    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(subject, "_deep_validate_live_inputs", lambda *_: None)
        subject.write_successor(root)
    first = {path: (root / path).read_bytes() for path in subject.OUTPUT_PATHS}
    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(subject, "_deep_validate_live_inputs", lambda *_: None)
        assert subject.check_successor(root) == expected
        assert subject.build_outputs(root) == expected
        subject.write_successor(root)
    assert {path: (root / path).read_bytes() for path in subject.OUTPUT_PATHS} == first
    assert not (root / subject.TRANSACTION_JOURNAL_REL).exists()


def test_r027_reads_reviewed_v1_bytes_after_exact_v2_publication(
    tmp_path: Path,
) -> None:
    root = tmp_path / "historical-review"
    predecessor = _materialize(root)
    review = {
        "schema_version": "walksafe.npc-single-admin-recovery.review-result.v2",
        "goal_id": subject.GOAL_ID,
        "round_id": subject.r027.R001_ROUND_ID,
        "decision": "REJECTED",
        "reviewed_evidence_manifest": [
            {
                "path": path.as_posix(),
                "sha256": subject.bytes_sha256(predecessor[path]),
            }
            for path in subject.OUTPUT_PATHS
        ],
    }
    review_raw = subject.json_text(review).encode()
    (root / subject.R001_REJECTED_REVIEW_REL).write_bytes(review_raw)
    review_subject = {
        "reviewed_consumer_bindings": [
            {"path": path.as_posix()} for path in subject.OUTPUT_PATHS
        ]
    }
    for path, raw in (
        (subject.trace.REVIEW_SUBJECT_REL, subject.json_text(review_subject).encode()),
        (subject.trace.IMPLEMENTATION_REL, b"{}\n"),
        (subject.trace.VERIFICATION_REL, b"{}\n"),
        (subject.trace.SUCCESSOR_REL, b"{}\n"),
    ):
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)

    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(subject, "_deep_validate_live_inputs", lambda *_: None)
        subject.write_successor(root)

    loaded = subject.r027._load_r001_review_inputs(root)
    assert dict(loaded.reviewed_evidence_raw_by_path) == predecessor
    assert subject.project_r001_reviewed_v1_artifacts(root) == predecessor


@pytest.mark.parametrize(
    "operation", ["build_outputs", "check_successor", "write_successor"]
)
def test_all_entry_points_reject_coordinated_self_sealed_field_insertion(
    tmp_path: Path, operation: str
) -> None:
    root = tmp_path / operation
    _publish_fixture(root)
    _inject_coordinated_self_sealed_field(root)
    forged_raw = {
        path: (root / path).read_bytes() for path in subject.OUTPUT_PATHS
    }
    live_raw = {
        path: (root / path).read_bytes() for path in subject.LIVE_INPUT_PATHS
    }
    subject.validate_correction_documents(
        subject._parse_six(forged_raw),
        forged_raw,
        expected_bindings=subject._expected_bindings_from_live(live_raw),
    )

    with pytest.raises(subject.BuildError, match="fixed-v1 exact rebuild"):
        with pytest.MonkeyPatch.context() as patch:
            patch.setattr(subject, "_deep_validate_live_inputs", lambda *_: None)
            getattr(subject, operation)(root)

    assert {
        path: (root / path).read_bytes() for path in subject.OUTPUT_PATHS
    } == forged_raw


def test_all_entry_points_reject_doc01_credit_boundary_reseal(tmp_path: Path) -> None:
    root = tmp_path / "doc01-credit"
    _publish_fixture(root)
    path = root / subject.DOC01_REL
    doc01 = json.loads(path.read_bytes())
    doc01["authorization_boundary"]["formal_deliverables_completed"] = True
    doc01["authorization_boundary"]["release_status"] = "ELIGIBLE"
    subject.v1._projection_seal(
        doc01, subject.SEAL_FIELD_BY_PATH[subject.DOC01_REL]
    )
    path.write_bytes(subject.json_text(doc01).encode())
    forged_raw = {
        relative: (root / relative).read_bytes()
        for relative in subject.OUTPUT_PATHS
    }

    for operation in (
        subject.build_outputs,
        subject.check_successor,
        subject.write_successor,
    ):
        with pytest.raises(subject.BuildError, match="v1 predecessor SHA-256 differs"):
            with pytest.MonkeyPatch.context() as patch:
                patch.setattr(subject, "_deep_validate_live_inputs", lambda *_: None)
                operation(root)

    assert {
        relative: (root / relative).read_bytes()
        for relative in subject.OUTPUT_PATHS
    } == forged_raw


def test_writer_rejects_symlink_target_without_touching_referent(tmp_path: Path) -> None:
    root = tmp_path / "symlink"
    _materialize(root)
    target = root / subject.DOC05_REL
    victim = root / "victim.json"
    victim_raw = b'{"outside":"must-remain"}\n'
    victim.write_bytes(victim_raw)
    target.unlink()
    target.symlink_to(victim)

    with pytest.raises(subject.BuildError, match="cannot safely open|file authority"):
        with pytest.MonkeyPatch.context() as patch:
            patch.setattr(subject, "_deep_validate_live_inputs", lambda *_: None)
            subject.write_successor(root)

    assert target.is_symlink()
    assert victim.read_bytes() == victim_raw


def test_live_input_mutation_invalidates_all_six_bindings(tmp_path: Path) -> None:
    root = tmp_path / "input-mutation"
    _materialize(root)
    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(subject, "_deep_validate_live_inputs", lambda *_: None)
        subject.write_successor(root)
    path = root / subject.R001_REJECTED_REVIEW_REL
    review = json.loads(path.read_bytes())
    review["extra"] = "changed-after-publication"
    path.write_bytes(subject.json_text(review).encode())

    with pytest.raises(subject.BuildError, match="fixed-v1 exact rebuild"):
        with pytest.MonkeyPatch.context() as patch:
            patch.setattr(subject, "_deep_validate_live_inputs", lambda *_: None)
            subject.check_successor(root)
    with pytest.raises(subject.BuildError, match="fixed-v1 exact rebuild"):
        with pytest.MonkeyPatch.context() as patch:
            patch.setattr(subject, "_deep_validate_live_inputs", lambda *_: None)
            subject.write_successor(root)


def test_deep_validator_rejects_self_sealed_forged_v2_producer(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = tmp_path / "forged-producer"
    _materialize(root)
    raw = {path: (root / path).read_bytes() for path in subject.LIVE_INPUT_PATHS}
    implementation = json.loads(raw[subject.trace.V2_IMPLEMENTATION_REL])
    verification = json.loads(raw[subject.trace.V2_VERIFICATION_REL])
    implementation["observed_at"] = "2026-08-13T07:31:00+09:00"
    implementation.pop("implementation_record_content_sha256")
    implementation = subject.trace._seal(
        implementation, "implementation_record_content_sha256"
    )
    implementation_raw = subject.json_text(implementation).encode()
    verification["implementation_record_sha256"] = subject.bytes_sha256(
        implementation_raw
    )
    verification["observed_at"] = implementation["observed_at"]
    verification.pop("verification_result_content_sha256")
    verification = subject.trace._seal(
        verification, "verification_result_content_sha256"
    )
    raw[subject.trace.V2_IMPLEMENTATION_REL] = implementation_raw
    raw[subject.trace.V2_VERIFICATION_REL] = subject.json_text(verification).encode()
    rebuilt = {
        subject.trace.V2_IMPLEMENTATION_REL: subject.json_text(
            {**implementation, "observed_at": "different-deep-rebuild"}
        ),
        subject.trace.V2_VERIFICATION_REL: raw[
            subject.trace.V2_VERIFICATION_REL
        ].decode(),
    }
    monkeypatch.setattr(subject.trace, "read_bytes", lambda *_: b"{}\n")
    monkeypatch.setattr(subject.trace, "build_results_outputs", lambda *_args, **_kwargs: rebuilt)

    with pytest.raises(subject.BuildError, match="do not reproduce"):
        subject._deep_validate_live_inputs(root, raw)


def test_deep_validator_rejects_self_sealed_forged_r027(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = tmp_path / "forged-r027"
    _materialize(root)
    raw = {path: (root / path).read_bytes() for path in subject.LIVE_INPUT_PATHS}
    implementation = json.loads(raw[subject.trace.V2_IMPLEMENTATION_REL])
    verification = json.loads(raw[subject.trace.V2_VERIFICATION_REL])
    rebuilt_v2 = {
        subject.trace.V2_IMPLEMENTATION_REL: raw[
            subject.trace.V2_IMPLEMENTATION_REL
        ].decode(),
        subject.trace.V2_VERIFICATION_REL: raw[
            subject.trace.V2_VERIFICATION_REL
        ].decode(),
    }
    monkeypatch.setattr(subject.trace, "read_bytes", lambda *_: b"{}\n")
    monkeypatch.setattr(
        subject.trace,
        "build_results_outputs",
        lambda *_args, **_kwargs: rebuilt_v2,
    )
    monkeypatch.setattr(
        subject.r027,
        "build_outputs",
        lambda *_: {
            path: (
                subject.json_text(
                    {
                        "schema_version": "walksafe.implementation-gap-analysis.v1",
                        "forged": True,
                    }
                )
                if path == subject.R027_GAP_REL
                else raw[path].decode()
            )
            for path in subject.R027_OUTPUT_PATHS
        },
    )

    with pytest.raises(subject.BuildError, match="stored R027 output does not reproduce"):
        subject._deep_validate_live_inputs(root, raw)


def test_deep_validator_delegates_to_trace_and_r027_reproducers(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = tmp_path / "deep-valid"
    _materialize(root)
    raw = {path: (root / path).read_bytes() for path in subject.LIVE_INPUT_PATHS}
    rebuilt_v2 = {
        subject.trace.V2_IMPLEMENTATION_REL: raw[
            subject.trace.V2_IMPLEMENTATION_REL
        ].decode(),
        subject.trace.V2_VERIFICATION_REL: raw[
            subject.trace.V2_VERIFICATION_REL
        ].decode(),
    }
    calls = {"trace": 0, "r027": 0}

    def rebuild_trace(*_args: object, **_kwargs: object) -> dict[Path, str]:
        calls["trace"] += 1
        return rebuilt_v2

    def rebuild_r027(*_args: object, **_kwargs: object) -> dict[Path, str]:
        calls["r027"] += 1
        return {path: raw[path].decode() for path in subject.R027_OUTPUT_PATHS}

    monkeypatch.setattr(subject.trace, "read_bytes", lambda *_: b"{}\n")
    monkeypatch.setattr(subject.trace, "build_results_outputs", rebuild_trace)
    monkeypatch.setattr(subject.r027, "build_outputs", rebuild_r027)

    subject._deep_validate_live_inputs(root, raw)
    assert calls == {"trace": 1, "r027": 1}


def test_deep_validator_rejects_partial_r027_output_inventory(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = tmp_path / "partial-r027"
    _materialize(root)
    raw = {path: (root / path).read_bytes() for path in subject.LIVE_INPUT_PATHS}
    rebuilt_v2 = {
        subject.trace.V2_IMPLEMENTATION_REL: raw[
            subject.trace.V2_IMPLEMENTATION_REL
        ].decode(),
        subject.trace.V2_VERIFICATION_REL: raw[
            subject.trace.V2_VERIFICATION_REL
        ].decode(),
    }
    monkeypatch.setattr(subject.trace, "read_bytes", lambda *_: b"{}\n")
    monkeypatch.setattr(
        subject.trace,
        "build_results_outputs",
        lambda *_args, **_kwargs: rebuilt_v2,
    )
    monkeypatch.setattr(
        subject.r027,
        "build_outputs",
        lambda *_: {subject.R027_GAP_REL: raw[subject.R027_GAP_REL].decode()},
    )

    with pytest.raises(subject.BuildError, match="four-output inventory differs"):
        subject._deep_validate_live_inputs(root, raw)


def test_completed_transaction_preserves_recovery_files_when_r027_cohort_drifts(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = tmp_path / "completed-but-unverified"
    _materialize(root)
    original_recover = subject._recover_locked

    def crash_before_completed_recovery(*args: object, **kwargs: object) -> str:
        if (root / subject.TRANSACTION_JOURNAL_REL).exists():
            raise RuntimeError("simulated stop before completed recovery")
        return original_recover(*args, **kwargs)

    with monkeypatch.context() as patch:
        patch.setattr(subject, "_deep_validate_live_inputs", lambda *_: None)
        patch.setattr(subject, "_recover_locked", crash_before_completed_recovery)
        with pytest.raises(RuntimeError, match="stop before completed recovery"):
            subject.write_successor(root)

    assert (root / subject.TRANSACTION_JOURNAL_REL).is_file()
    assert all(
        subject.MARKER_FIELD in json.loads((root / path).read_bytes())
        for path in subject.OUTPUT_PATHS
    )
    expected_r027 = {
        path: (root / path).read_bytes().decode()
        for path in subject.R027_OUTPUT_PATHS
    }
    drifted = subject.r027.R027_BACKLOG_MD_REL
    (root / drifted).write_text("# attacker-controlled backlog\n", encoding="utf-8")
    live_raw = {
        path: (root / path).read_bytes() for path in subject.LIVE_INPUT_PATHS
    }
    rebuilt_v2 = {
        subject.trace.V2_IMPLEMENTATION_REL: live_raw[
            subject.trace.V2_IMPLEMENTATION_REL
        ].decode(),
        subject.trace.V2_VERIFICATION_REL: live_raw[
            subject.trace.V2_VERIFICATION_REL
        ].decode(),
    }

    with monkeypatch.context() as patch:
        patch.setattr(subject.trace, "read_bytes", lambda *_: b"{}\n")
        patch.setattr(
            subject.trace,
            "build_results_outputs",
            lambda *_args, **_kwargs: rebuilt_v2,
        )
        patch.setattr(subject.r027, "build_outputs", lambda *_: expected_r027)
        with pytest.raises(subject.BuildError, match="stored R027 output does not reproduce"):
            subject.write_successor(root)

    assert (root / subject.TRANSACTION_JOURNAL_REL).is_file()
    for path in subject.OUTPUT_PATHS:
        assert (root / subject._transaction_member(path, "backup")).is_file()


def test_exchange_cas_preserves_foreign_target_and_retains_journal(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = tmp_path / "race"
    predecessor = _materialize(root)
    raced = subject.RTM_REL
    foreign_raw = b'{"foreign":"target-race"}\n'
    original = subject.v1._exchange_cas
    injected = False

    def inject(*args: object, **kwargs: object) -> None:
        nonlocal injected
        label = str(kwargs.get("label"))
        if not injected and label.endswith(str(raced)):
            injected = True
            foreign = (root / raced).with_name(f".{raced.name}.foreign")
            foreign.write_bytes(foreign_raw)
            os.replace(foreign, root / raced)
        original(*args, **kwargs)

    monkeypatch.setattr(subject.v1, "_exchange_cas", inject)
    monkeypatch.setattr(subject, "_deep_validate_live_inputs", lambda *_: None)
    with pytest.raises(subject.BuildError, match="rollback retained|CAS"):
        subject.write_successor(root)

    assert injected
    assert (root / raced).read_bytes() == foreign_raw
    assert all(
        (root / path).read_bytes() == predecessor[path]
        for path in subject.OUTPUT_PATHS
        if path != raced
    )
    assert (root / subject.TRANSACTION_JOURNAL_REL).is_file()


def test_rerun_recovers_interrupted_partial_commit_then_publishes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = tmp_path / "interrupted"
    _materialize(root)
    original_exchange = subject.v1._exchange_cas
    original_recover = subject._recover_locked
    calls = 0

    def fail_third(*args: object, **kwargs: object) -> None:
        nonlocal calls
        calls += 1
        if calls == 3:
            raise OSError("simulated interruption")
        original_exchange(*args, **kwargs)

    def abandon(*args: object, **kwargs: object) -> str:
        if kwargs.get("force_rollback") is True:
            raise RuntimeError("simulated process stopped before recovery")
        return original_recover(*args, **kwargs)

    with monkeypatch.context() as patch:
        patch.setattr(subject.v1, "_exchange_cas", fail_third)
        patch.setattr(subject, "_recover_locked", abandon)
        patch.setattr(subject, "_deep_validate_live_inputs", lambda *_: None)
        with pytest.raises(RuntimeError, match="stopped before recovery"):
            subject.write_successor(root)
    assert (root / subject.TRANSACTION_JOURNAL_REL).is_file()

    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(subject, "_deep_validate_live_inputs", lambda *_: None)
        subject.write_successor(root)
        subject.check_successor(root)
    assert not (root / subject.TRANSACTION_JOURNAL_REL).exists()
    for path in subject.OUTPUT_PATHS:
        assert not (root / subject._transaction_member(path, "stage")).exists()
        assert not (root / subject._transaction_member(path, "backup")).exists()
