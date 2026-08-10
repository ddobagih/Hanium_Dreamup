from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts import apply_walksafe_fp046_goal_completed_seq54_55_20260810 as apply
from scripts import check_walksafe_project_continuation_v2_4 as contract


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]


def _source_bytes() -> bytes:
    return (REPOSITORY_ROOT / apply.CHECKPOINT_RELATIVE).read_bytes()


def _source() -> dict[str, object]:
    return json.loads(_source_bytes())


def _gap() -> dict[str, object]:
    return {
        "metadata": {
            "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260810-025",
            "version": "r025",
        },
        "summary": {
            "status_counts": {
                "COVERED": 12,
                "PARTIAL": 43,
                "MISSING": 9,
                "CONFLICTING": 4,
            }
        },
        "implementation_snapshot": {
            "release_status": "NOT_ELIGIBLE",
            "formal_test_status": "NOT_RUN",
        },
        "assessments": [
            {
                "source_policy_id": "FP-046",
                "gap_id": "GAP-055",
                "status": "PARTIAL",
            }
        ],
        "reassessment_scope": {
            "next_adjacent_gap": {
                "source_policy_id": apply.NEXT_POLICY_ID,
                "gap_id": apply.NEXT_GAP_ID,
                "priority_rank": apply.NEXT_PRIORITY_RANK,
            }
        },
    }


def _backlog() -> dict[str, object]:
    return {
        "metadata": {
            "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260810-025"
        },
        "epics": [{"epic_id": "EPIC-03", "current_status": "IN_PROGRESS"}],
        "next_single_action": {
            "epic_id": "EPIC-03",
            "source_policy_id": apply.NEXT_POLICY_ID,
            "gap_id": apply.NEXT_GAP_ID,
            "priority_rank": apply.NEXT_PRIORITY_RANK,
            "status": "PLANNED_NEXT",
            "action": apply.NEXT_ACTION,
        },
    }


def _evidence(source: dict[str, object]) -> apply.CompletionEvidence:
    bindings_by_role: dict[str, dict[str, str]] = {}
    documents_by_role: dict[str, dict[str, object]] = {
        "IMPLEMENTATION_GAP": _gap(),
        "IMPLEMENTATION_BACKLOG": _backlog(),
    }
    existing = {
        row["role"]: row
        for row in source["canonical_bindings"]
        if isinstance(row, dict)
    }
    for index, spec in enumerate(apply.ARTIFACT_SPECS, start=1):
        bindings_by_role[spec.role] = {
            "role": spec.role,
            "document_id": spec.document_id,
            "path": spec.path.as_posix(),
            "file_sha256": f"{index:064x}",
        }
        if spec.role not in documents_by_role:
            documents_by_role[spec.role] = {
                "document_id": spec.document_id,
            }
        if spec.role != apply.COMPLETION_ROLE:
            assert spec.role in existing

    managed = {
        Path("scripts/apply_walksafe_fp046_goal_completed_seq54_55_20260810.py"):
        hashlib.sha256(
            (
                REPOSITORY_ROOT
                / "scripts/apply_walksafe_fp046_goal_completed_seq54_55_20260810.py"
            ).read_bytes()
        ).hexdigest(),
        Path("tests/test_walksafe_fp046_goal_completed_seq54_55_20260810.py"):
        hashlib.sha256(
            (
                REPOSITORY_ROOT
                / "tests/test_walksafe_fp046_goal_completed_seq54_55_20260810.py"
            ).read_bytes()
        ).hexdigest(),
    }
    completion = documents_by_role[apply.COMPLETION_ROLE]
    completion["completion_boundary"] = copy.deepcopy(
        apply.EXPECTED_COMPLETION_BOUNDARY
    )
    return apply.CompletionEvidence(
        documents_by_role=documents_by_role,
        bindings_by_role=bindings_by_role,
        receipt=completion,
        update_occurred_at="2026-08-10T00:00:00+09:00",
        completion_occurred_at="2026-08-10T00:00:01+09:00",
        physical_sha256_by_path={},
        final_managed_sha256_by_path=managed,
        authorized_delta_by_path={},
        added_managed_paths=tuple(sorted(managed, key=Path.as_posix)),
    )


def _runtime(
    _root: Path,
    checkpoint: dict[str, object],
    ready: list[str],
) -> tuple[dict[str, object], dict[str, object]]:
    state = checkpoint["goal_execution"]
    return (
        {"schema_version": "test.v1", "ready": copy.deepcopy(ready)},
        {
            "schema_version": "test.v1",
            "status": state["status_by_goal"][apply.GOAL_ID],
        },
    )


def _write(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)


def _trace_fixture(root: Path) -> None:
    for relative in (
        apply.fp046_trace.GOAL_REL,
        apply.fp046_trace.START_GATE_REL,
        apply.fp046_trace.START_GATE_REPOSITORY_STATE_REL,
    ):
        _write(root, relative, (REPOSITORY_ROOT / relative).read_bytes())
    for index, group in enumerate(
        apply.fp046_trace.IMPLEMENTATION_SOURCE_GROUPS,
        start=1,
    ):
        for offset, path in enumerate(group.paths, start=1):
            _write(root, Path(path), f"source-{index}-{offset}\n".encode())
    for relative in apply.fp046_trace.BACKEND_TRANSITIVE_INPUT_SHA256_BY_PATH:
        _write(root, relative, (REPOSITORY_ROOT / relative).read_bytes())

    authority = apply.fp046_trace.validate_authority(root)
    lane_raw: dict[str, bytes] = {}
    observations: dict[str, dict[str, object]] = {}
    for index, lane in enumerate(apply.fp046_trace.LANES, start=1):
        started_at = f"2026-08-10T01:0{index}:00+09:00"
        ended_at = f"2026-08-10T01:0{index}:30+09:00"
        runner_summary = {
            "ANDROID_CONSENT_DELETION": (
                "JUnit tests=981 failures=0 errors=0 skipped=0"
            ),
            "GATEWAY_PRIVACY_LEDGER": (
                "Node tests=88 pass=88 fail=0; typecheck=PASS; build=PASS"
            ),
            "BACKEND_PRIVACY_POSTGRES": "57 passed in 1.0s",
            "RETENTION_BACKUP_DELETION": "92 passed in 1.0s",
        }[lane.lane_id]
        raw = (
            f"WALKSAFE_FP046_COMMAND {lane.expected_command}\n"
            f"WALKSAFE_FP046_STARTED_AT {started_at}\n"
            f"{runner_summary}\n"
            f"WALKSAFE_FP046_SUMMARY passed={lane.expected_passed} "
            "failed=0 errors=0 skipped=0\n"
            "WALKSAFE_FP046_EXIT_CODE 0\n"
            f"WALKSAFE_FP046_ENDED_AT {ended_at}\n"
        ).encode()
        lane_raw[lane.lane_id] = raw
        observations[lane.lane_id] = {
            "schema_version": "walksafe.fp046-internal-lane-observation.v1",
            "lane_id": lane.lane_id,
            "status": "PASS",
            "command": lane.expected_command,
            "exit_code": 0,
            "started_at": started_at,
            "ended_at": ended_at,
            "raw_output_sha256": apply.sha256_bytes(raw),
            "raw_output_byte_length": len(raw),
            "metrics": {
                "result_format": "INTERNAL_TEST_SUMMARY_V1",
                "passed": lane.expected_passed,
                "failed": 0,
                "errors": 0,
                "skipped": 0,
            },
            "evidence_boundary": apply.fp046_trace.lane_evidence_boundary(),
        }
    outputs = apply.fp046_trace.build_pre_review_outputs(
        root=root,
        lane_observations=observations,
        lane_raw_outputs=lane_raw,
        authority=authority,
    )
    for relative, content in outputs.items():
        _write(root, relative, content.encode())


class WalkSafeFp046GoalCompletedSeq5455Test(unittest.TestCase):
    def test_r025_paths_are_exact_builder_constants(self) -> None:
        self.assertEqual(apply.GAP_PATH, apply.fp046_r025.R025_GAP_JSON_REL)
        self.assertEqual(
            apply.BACKLOG_PATH,
            apply.fp046_r025.R025_BACKLOG_JSON_REL,
        )
        self.assertIn("20260810-r025", apply.GAP_PATH.as_posix())
        self.assertIn("20260810-r025", apply.BACKLOG_PATH.as_posix())

    def test_result_authority_binds_103_current_sources_and_four_lanes(self) -> None:
        canonical_path_count = sum(
            len(group.paths)
            for group in apply.fp046_trace.IMPLEMENTATION_SOURCE_GROUPS
        )
        self.assertEqual(canonical_path_count, 103)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _trace_fixture(root)
            bound = apply._validate_result_path_authority(root)
            self.assertEqual(len(bound), canonical_path_count)

            first = apply.fp046_trace.LANES[0]
            original_log = (root / first.log_rel).read_bytes()
            (root / first.log_rel).write_bytes(b"tampered\n")
            with self.assertRaisesRegex(
                apply.CompletionApplyError,
                "physical artifact binding|raw-output binding",
            ):
                apply._validate_result_path_authority(root)
            (root / first.log_rel).write_bytes(original_log)

            source_path = next(iter(bound))
            (root / source_path).write_bytes(b"changed-after-validation\n")
            with self.assertRaisesRegex(
                apply.CompletionApplyError,
                "current file binding",
            ):
                apply._validate_result_path_authority(root)

    def test_predecessor_seq53_raw_bytes_and_tail_are_pinned(self) -> None:
        raw = _source_bytes()
        source = _source()
        apply.require_exact_source(raw, source)
        self.assertEqual(len(raw), apply.SOURCE_CHECKPOINT_BYTE_COUNT)
        self.assertEqual(apply.sha256_bytes(raw), apply.SOURCE_CHECKPOINT_RAW_SHA256)
        history = source["goal_execution"]["transition_history"]
        self.assertEqual(len(history), 53)
        self.assertEqual(history[-1]["event_sha256"], apply.SOURCE_START_EVENT_SHA256)

        tampered = copy.deepcopy(source)
        tampered["goal_execution"]["transition_history"][0]["event_id"] += "-tampered"
        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "source byte count|raw SHA-256",
        ):
            apply.require_exact_source(apply.json_bytes(tampered), tampered)

    def test_start_gate_runtime_closure_binds_six_inputs(self) -> None:
        closure = apply._collect_gate_closure(
            REPOSITORY_ROOT,
            apply.START_GATE_BINDING,
            repository_state_path=apply.START_GATE_REPOSITORY_STATE_PATH,
            repository_state_sha256=apply.START_GATE_REPOSITORY_STATE_SHA256,
            label="FP046 initial gate",
        )
        receipt = json.loads(
            (REPOSITORY_ROOT / Path(apply.START_GATE_BINDING["path"])).read_bytes()
        )
        runtime_paths = [Path(row["path"]) for row in receipt["runtime_bindings"]]
        self.assertEqual(len(runtime_paths), 6)
        self.assertTrue(set(runtime_paths) <= set(closure))

    def test_seq54_55_projection_preserves_prior_events_and_stops_before_seq56(self) -> None:
        source = _source()
        evidence = _evidence(source)
        expected_snapshot = apply._snapshot_hashes_from_digests(
            evidence.final_managed_sha256_by_path
        )
        with tempfile.TemporaryDirectory() as directory:
            projected, update, completion = apply.project_seq54_55(
                Path(directory),
                source,
                evidence,
                runtime_deriver=_runtime,
                snapshot_hasher=lambda _root, _paths: expected_snapshot,
            )
        apply.validate_exact_projection(source, projected, evidence)

        history = projected["goal_execution"]["transition_history"]
        self.assertEqual(history[:53], source["goal_execution"]["transition_history"])
        self.assertEqual([row["sequence"] for row in history[-2:]], [54, 55])
        self.assertEqual(update["event_type"], "CANONICAL_BINDINGS_UPDATED")
        self.assertEqual(completion["event_type"], "GOAL_COMPLETED")
        self.assertEqual(completion["subject_goal_id"], apply.GOAL_ID)
        self.assertEqual(
            projected["goal_execution"]["status_by_goal"][apply.GOAL_ID],
            "COMPLETE_AT_TARGET",
        )
        self.assertNotIn(
            apply.NEXT_GOAL_ID,
            projected["goal_execution"]["status_by_goal"],
        )
        self.assertEqual(projected["current_work"]["work_item_id"], apply.NEXT_WORK_ITEM_ID)

    def test_r025_pointer_and_completion_boundary_remain_internal_only(self) -> None:
        apply._validate_next_pointer(_gap(), _backlog())
        boundary = apply.EXPECTED_COMPLETION_BOUNDARY
        self.assertEqual(boundary["formal_test_status"], "NOT_RUN")
        self.assertEqual(boundary["actual_device_status"], "NOT_RUN")
        self.assertEqual(boundary["production_deployment_status"], "NOT_RUN")
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")
        self.assertEqual(boundary["release_credit_count"], 0)

    def test_final_production_pins_match_live_bytes_and_fail_closed_on_drift(self) -> None:
        self.assertEqual(
            set(apply.PINNED_PRODUCTION_SHA256_BY_ROLE),
            set(apply.SPEC_BY_ROLE),
        )
        self.assertEqual(
            set(apply.PINNED_PRODUCTION_SHA256_BY_PATH),
            set(apply.ADDITIONAL_OUTPUT_PIN_PATHS),
        )
        for role, spec in apply.SPEC_BY_ROLE.items():
            self.assertEqual(
                hashlib.sha256((REPOSITORY_ROOT / spec.path).read_bytes()).hexdigest(),
                apply.PINNED_PRODUCTION_SHA256_BY_ROLE[role],
            )
        for relative, expected in apply.PINNED_PRODUCTION_SHA256_BY_PATH.items():
            self.assertEqual(
                hashlib.sha256((REPOSITORY_ROOT / relative).read_bytes()).hexdigest(),
                expected,
            )

        invalid_role_pins = dict(apply.PINNED_PRODUCTION_SHA256_BY_ROLE)
        invalid_role_pins["IMPLEMENTATION_GAP"] = apply.PENDING_FINAL_ARTIFACT_SHA256
        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "PENDING or invalid",
        ):
            apply._resolve_sha256_pins(invalid_role_pins)
        with self.assertRaisesRegex(
            apply.CompletionApplyError,
            "path set differs",
        ):
            apply._resolve_path_sha256_pins(
                {},
                production={},
                expected_paths=apply.ADDITIONAL_OUTPUT_PIN_PATHS,
                label="FP046 output",
            )

    def test_controlled_checker_delta_pins_match_live_bytes(self) -> None:
        self.assertEqual(len(apply.CONTROLLED_CHECKER_DELTA_SHA256_BY_PATH), 8)
        for relative, expected in apply.CONTROLLED_CHECKER_DELTA_SHA256_BY_PATH.items():
            self.assertEqual(
                hashlib.sha256((REPOSITORY_ROOT / relative).read_bytes()).hexdigest(),
                expected,
            )

    def test_backend_transitive_input_pins_are_in_completion_closure(self) -> None:
        expected = apply.fp046_trace.BACKEND_TRANSITIVE_INPUT_SHA256_BY_PATH
        self.assertEqual(len(expected), 28)
        self.assertTrue(
            {relative.as_posix() for relative in expected}.isdisjoint(
                apply.fp046_trace.BACKEND_MANIFEST_DRIFT_PATHS
            )
        )
        self.assertTrue(
            set(expected).isdisjoint(apply.fp046_r014.EXPECTED_R013_SHA256_BY_PATH)
        )
        for relative, digest in expected.items():
            self.assertEqual(apply.TRANSITIVE_INPUT_SHA256_BY_PATH[relative], digest)
            self.assertEqual(
                hashlib.sha256((REPOSITORY_ROOT / relative).read_bytes()).hexdigest(),
                digest,
            )

    def test_parser_rejects_abbreviated_or_transition_expanding_modes(self) -> None:
        with self.assertRaises(SystemExit):
            apply.parse_args(["--pre"])
        with self.assertRaises(SystemExit):
            apply.parse_args(["--write", "--preflight"])
        parsed = apply.parse_args(["--preflight"])
        self.assertTrue(parsed.preflight)
        self.assertFalse(parsed.write)


if __name__ == "__main__":
    unittest.main()
