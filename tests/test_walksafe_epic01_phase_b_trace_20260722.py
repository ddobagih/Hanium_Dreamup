from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
import hashlib
import importlib.util
from io import StringIO
import json
from pathlib import Path
import unittest
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts/build_walksafe_epic01_phase_b_trace_20260722.py"
HISTORICAL_PHASE_B = (
    REPO_ROOT
    / "docs/control/execution/walksafe-epic-01-phase-b-admin-auth-recovery-implementation-record-20260722.json"
)
HISTORICAL_GAP_R002 = (
    REPO_ROOT
    / "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r002.json"
)
HISTORICAL_CANONICAL_PREIMAGES = {
    "docs/deliverables/00-control/artifact-change-log.json": (
        "c4b63a01c3ae30efecb0f08c21678057626af10ed8cfef258ddf90136d0aca1c"
    ),
    "docs/deliverables/00-control/artifact-register.json": (
        "c4a5259744febc9cc442785be1748a0e0da88dbc86ef6459f1f6ee74fcaf62b6"
    ),
}
HISTORICAL_TRACE_TEST_SHA256 = (
    "5e594cc4c752c24473cfea167f266ec714bb6470e8b5ce087f8d1712d0e3a5f4"
)
HISTORICAL_TRACE_TEST_BYTES = 8988
HISTORICAL_TRACE_TEST_LOGICAL_PATH = (
    "tests/test_walksafe_epic01_phase_b_trace_20260722.py"
)
HISTORICAL_TRACE_TEST_BLOB_PATH = (
    "docs/control/history/source-preimages/sha256/"
    f"{HISTORICAL_TRACE_TEST_SHA256}.py"
)
SOURCE_PREIMAGE_MANIFEST = (
    REPO_ROOT
    / "docs/control/history/source-preimages/source-preimage-manifest-20260802-r001.json"
)
SOURCE_PREIMAGE_MANIFEST_SHA256 = (
    "220bdce7135ecf19148af6c312771131a261cd1a1655f1c02e0b19f8ab32515f"
)


def _historical_input(path: Path) -> Path:
    for relative, digest in HISTORICAL_CANONICAL_PREIMAGES.items():
        if path.resolve() != (REPO_ROOT / relative).resolve():
            continue
        preimage = (
            REPO_ROOT
            / "docs/control/history/canonical-preimages/sha256"
            / f"{digest}.json"
        )
        if hashlib.sha256(preimage.read_bytes()).hexdigest() != digest:
            raise RuntimeError(f"historical preimage hash mismatch: {relative}")
        return preimage
    return path


def _historical_trace_test_input() -> Path:
    manifest = json.loads(SOURCE_PREIMAGE_MANIFEST.read_text(encoding="utf-8"))
    payload = dict(manifest)
    manifest_seal = payload.pop("manifest_content_sha256", None)
    canonical = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    if (
        manifest_seal != SOURCE_PREIMAGE_MANIFEST_SHA256
        or hashlib.sha256(canonical).hexdigest() != manifest_seal
    ):
        raise RuntimeError("source preimage manifest seal mismatch")

    matches = [
        entry
        for entry in manifest["entries"]
        if entry.get("logical_path") == HISTORICAL_TRACE_TEST_LOGICAL_PATH
    ]
    if len(matches) != 1:
        raise RuntimeError("historical Phase B test manifest entry is not unique")
    entry = matches[0]
    expected = {
        "logical_path": HISTORICAL_TRACE_TEST_LOGICAL_PATH,
        "historical_sha256": HISTORICAL_TRACE_TEST_SHA256,
        "historical_bytes": HISTORICAL_TRACE_TEST_BYTES,
        "blob_path": HISTORICAL_TRACE_TEST_BLOB_PATH,
        "intended_use": "HISTORICAL_REPLAY_ONLY",
    }
    if {key: entry.get(key) for key in expected} != expected:
        raise RuntimeError("historical Phase B test manifest entry differs")

    preimage = REPO_ROOT / entry["blob_path"]
    if preimage.stat().st_size != HISTORICAL_TRACE_TEST_BYTES:
        raise RuntimeError("historical Phase B test preimage size mismatch")
    if (
        hashlib.sha256(preimage.read_bytes()).hexdigest()
        != HISTORICAL_TRACE_TEST_SHA256
    ):
        raise RuntimeError("historical Phase B test preimage hash mismatch")
    return preimage


def _load_builder():
    spec = importlib.util.spec_from_file_location("walksafe_epic01_phase_b_trace", SCRIPT_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load Phase B trace builder")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class WalkSafeEpic01PhaseBTraceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.builder = _load_builder()
        live_file_sha256 = cls.builder._file_sha256
        live_load_json = cls.builder._load_json
        live_file_binding = cls.builder._file_binding
        historical_phase_b = live_load_json(HISTORICAL_PHASE_B)
        historical_gap = live_load_json(HISTORICAL_GAP_R002)
        cls.builder._verify_seal(historical_phase_b, "record_content_sha256")
        cls.builder._verify_seal(historical_gap, "report_content_sha256")
        historical_snapshot = historical_phase_b["implementation_snapshot"]
        historical_evidence = {
            item["evidence_id"]: item
            for item in historical_gap["evidence_catalog"]
            if item.get("kind") == "CONTROLLED_IMPLEMENTATION_PATH_SET"
        }

        def historical_file_sha256(path: Path) -> str:
            return live_file_sha256(_historical_input(path))

        def historical_load_json(path: Path) -> dict:
            return live_load_json(_historical_input(path))

        def historical_file_binding(
            name: str,
            path: Path,
            *,
            immutable: bool = False,
        ) -> dict:
            if path.resolve() == cls.builder.TRACE_TEST_PATH.resolve():
                result = live_file_binding(
                    name,
                    _historical_trace_test_input(),
                    immutable=immutable,
                )
                result["path"] = path.relative_to(REPO_ROOT).as_posix()
                return result
            result = live_file_binding(
                name,
                _historical_input(path),
                immutable=immutable,
            )
            result["path"] = path.relative_to(REPO_ROOT).as_posix()
            return result

        def historical_implementation_snapshot() -> dict:
            cls.builder._verify_seal(historical_snapshot, "snapshot_sha256")
            return deepcopy(historical_snapshot)

        def historical_path_group_evidence(
            evidence_id: str,
            claim: str,
            paths: list[str],
        ) -> dict:
            result = historical_evidence[evidence_id]
            cls.builder._require(result["claim"] == claim, "historical evidence claim differs")
            cls.builder._require(
                [item["path"] for item in result["files"]] == paths,
                "historical evidence path set differs",
            )
            cls.builder._require(
                result["path_set_content_sha256"]
                == cls.builder._object_sha256(result["files"]),
                "historical evidence content seal differs",
            )
            return deepcopy(result)

        for attribute, replacement in (
            ("_file_sha256", historical_file_sha256),
            ("_load_json", historical_load_json),
            ("_file_binding", historical_file_binding),
            ("_implementation_snapshot", historical_implementation_snapshot),
            ("_path_group_evidence", historical_path_group_evidence),
        ):
            patcher = mock.patch.object(cls.builder, attribute, replacement)
            patcher.start()
            cls.addClassCleanup(patcher.stop)
        cls.outputs = cls.builder.build_outputs()
        cls.rendered = cls.builder.render_outputs(cls.outputs)

    def test_generated_files_are_current_and_deterministic(self) -> None:
        stdout = StringIO()
        stderr = StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            returncode = self.builder.main(["--check"])
        self.assertEqual(returncode, 0, stderr.getvalue())
        self.assertIn("GAP-012=PARTIAL", stdout.getvalue())
        self.assertEqual(
            self.rendered,
            self.builder.render_outputs(self.builder.build_outputs()),
        )

    def test_builder_writes_only_new_phase_b_and_r002_outputs(self) -> None:
        paths = {path.relative_to(REPO_ROOT).as_posix() for path in self.rendered}
        self.assertEqual(len(paths), 10)
        self.assertIn(
            "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r002.json",
            paths,
        )
        self.assertIn(
            "docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r002.json",
            paths,
        )
        self.assertNotIn(
            "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r001.json",
            paths,
        )
        self.assertNotIn(
            "docs/deliverables/00-control/artifact-register.json",
            paths,
        )

    def test_immutable_baseline_and_r001_inputs_remain_exact(self) -> None:
        for path, expected in self.builder.IMMUTABLE_SHA256.items():
            actual = hashlib.sha256(_historical_input(path).read_bytes()).hexdigest()
            self.assertEqual(actual, expected, path)

    def test_phase_b_record_is_internal_and_epic_remains_in_progress(self) -> None:
        record = self.outputs["phase_b"]
        self.assertEqual(
            record["metadata"]["record_id"],
            "WS-EPIC-01-PHASE-B-ADMIN-AUTH-RECOVERY-IMPLEMENTATION-20260722-001",
        )
        self.assertEqual(record["trace"]["epic_status"], "IN_PROGRESS")
        self.assertFalse(record["authority_boundary"]["claims_epic_implementation_ready"])
        self.assertFalse(record["authority_boundary"]["claims_formal_test_pass"])
        self.assertFalse(record["authority_boundary"]["claims_recovery_drill_pass"])
        self.assertFalse(record["authority_boundary"]["claims_release_eligible"])
        self.assertEqual(record["release_boundary"]["formal_tests_total"], 279)
        self.assertEqual(record["release_boundary"]["formal_tests_passed"], 0)
        self.assertEqual(record["release_boundary"]["remaining_gate_status"], "NOT_RUN")
        self.assertFalse(record["release_boundary"]["remaining_gates_waived"])
        self.assertEqual(record["release_boundary"]["release_status"], "NOT_ELIGIBLE")

    def test_recovery_drill_is_only_a_draft_procedure(self) -> None:
        drill = self.outputs["drill"]
        self.assertEqual(drill["metadata"]["status"], "DRAFT_PROCEDURE_NOT_EXECUTED")
        self.assertEqual(drill["execution"]["status"], "NOT_RUN")
        self.assertIsNone(drill["execution"]["result"])
        self.assertEqual(drill["execution"]["evidence_files"], [])
        self.assertFalse(drill["authority_boundary"]["gate_closed"])
        self.assertFalse(drill["authority_boundary"]["gate_waived"])
        self.assertEqual(drill["release_boundary"]["gate_status"], "NOT_RUN")
        self.assertFalse(drill["release_boundary"]["waived"])
        self.assertEqual(drill["release_boundary"]["release_status"], "NOT_ELIGIBLE")
        self.assertEqual([step["order"] for step in drill["steps"]], list(range(1, 10)))

    def test_r002_changes_only_two_focused_assessments(self) -> None:
        r001 = json.loads(self.builder.GAP_R001.read_text(encoding="utf-8"))
        r002 = self.outputs["gap"]
        before = {item["gap_id"]: item for item in r001["assessments"]}
        after = {item["gap_id"]: item for item in r002["assessments"]}
        self.assertEqual(len(after), 68)
        self.assertEqual(after["GAP-012"]["status"], "PARTIAL")
        self.assertEqual(after["GAP-068"]["status"], "BLOCKED")
        self.assertEqual(
            {gap_id for gap_id in before if before[gap_id] != after[gap_id]},
            {"GAP-012", "GAP-068"},
        )
        self.assertEqual(r002["reassessment_scope"]["carried_forward_gap_count"], 66)
        self.assertFalse(
            r002["reassessment_scope"]["inherited_evidence"]["revalidated_in_r002"]
        )

    def test_r002_does_not_overstate_formal_or_release_state(self) -> None:
        report = self.outputs["gap"]
        self.assertEqual(report["coverage"]["planned_test_count"], 279)
        self.assertEqual(report["coverage"]["planned_test_not_run_count"], 279)
        self.assertTrue(
            all(item["formal_test_status"] == "NOT_RUN" for item in report["assessments"])
        )
        self.assertEqual(report["summary"]["implemented_and_formally_verified_count"], 0)
        self.assertNotIn("IMPLEMENTED", report["summary"]["status_counts"])
        self.assertEqual(report["summary"]["status_counts"]["BLOCKED"], 5)
        self.assertEqual(report["authorization_boundary"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(report["authorization_boundary"]["remaining_gates_waived"])
        evidence_ids = {item["evidence_id"] for item in report["evidence_catalog"]}
        self.assertTrue(
            all(set(item["evidence_ids"]) <= evidence_ids for item in report["assessments"])
        )

    def test_r002_backlog_keeps_epic01_open_and_selects_runtime_preflight(self) -> None:
        backlog = self.outputs["backlog"]
        epics = {item["epic_id"]: item for item in backlog["epics"]}
        self.assertEqual(epics["EPIC-01"]["current_status"], "IN_PROGRESS")
        self.assertEqual(
            epics["EPIC-01"]["phase_b_policy_status"],
            {
                "source_policy_id": "FP-003",
                "gap_id": "GAP-012",
                "status": "PARTIAL",
                "formal_test_status": "NOT_RUN",
                "recovery_gate_status": "NOT_RUN",
            },
        )
        self.assertTrue(
            all(item["current_status"] == "PLANNED" for key, item in epics.items() if key != "EPIC-01")
        )
        self.assertEqual(
            backlog["next_single_action"]["work_item_id"],
            "EPIC-01-RUNTIME-METRIC-PREFLIGHT",
        )
        self.assertEqual(backlog["authorization_boundary"]["release_status"], "NOT_ELIGIBLE")

    def test_active_overlay_is_minimal_and_does_not_promote_dev18(self) -> None:
        overlay = self.outputs["overlay"]
        self.assertEqual(
            [item["artifact_code"] for item in overlay["events"]],
            list(self.builder.ACTIVE_ARTIFACT_CODES),
        )
        self.assertTrue(
            all(
                item["lifecycle_status_before"] == item["lifecycle_status_after"] == "ACTIVE"
                and not item["approval_state_changed"]
                for item in overlay["events"]
            )
        )
        self.assertEqual(overlay["draft_observations"][0]["artifact_code"], "DEV-18")
        self.assertEqual(overlay["draft_observations"][0]["lifecycle_status"], "DRAFT")
        self.assertFalse(overlay["draft_observations"][0]["state_change"])
        self.assertFalse(overlay["authority_boundary"]["canonical_active_files_modified_by_builder"])

    def test_every_top_level_content_hash_rejects_tampering(self) -> None:
        cases = (
            ("phase_b", "record_content_sha256"),
            ("drill", "protocol_content_sha256"),
            ("gap", "report_content_sha256"),
            ("backlog", "backlog_content_sha256"),
            ("overlay", "overlay_content_sha256"),
        )
        for name, key in cases:
            with self.subTest(name=name):
                self.builder._verify_seal(self.outputs[name], key)
                tampered = deepcopy(self.outputs[name])
                tampered["schema_version"] += ".tampered"
                with self.assertRaises(self.builder.TraceBuildError):
                    self.builder._verify_seal(tampered, key)


if __name__ == "__main__":
    unittest.main()
