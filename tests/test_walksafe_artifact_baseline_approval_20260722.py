from __future__ import annotations

from collections import Counter
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import unittest

from scripts import build_walksafe_artifact_baseline_approval_20260722 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_artifact_baseline_approval_20260722.py"


def _documents() -> tuple[dict[Path, bytes], dict[Path, dict]]:
    outputs = builder.validate_published_outputs()
    return outputs, {path: json.loads(content) for path, content in outputs.items()}


def test_exact_owner_statement_is_byte_bound_without_normalization() -> None:
    candidate = builder.candidate_builder.legacy.load_strict_json(builder.CANDIDATE_PATH)
    intake = builder.candidate_builder.legacy.load_strict_json(builder.APPROVAL_INTAKE_PATH)
    raw = builder.APPROVAL_SOURCE_PATH.read_bytes()
    assert raw == (candidate["required_owner_approval_statement"] + "\n").encode()
    assert hashlib.sha256(raw[:-1]).hexdigest() == builder.EXPECTED_STATEMENT_SHA256
    assert intake["normalization_policy"]["whitespace"] == "NONE"


def test_snapshot_replays_doc01_and_doc05_and_freezes_all_257() -> None:
    outputs, docs = _documents()
    builder.validate_output_bytes(outputs)
    snapshot = docs[builder.PRE_SNAPSHOT_PATH]
    for key in ("doc01", "doc05"):
        frozen = snapshot["frozen_documents"][key]
        assert hashlib.sha256(builder._json_bytes(frozen["object"])).hexdigest() == frozen["file_sha256"]
    assert snapshot["classification_summary"]["artifact_count"] == 257


def test_approval_partition_policy_and_control_plan_are_exact() -> None:
    _, docs = _documents()
    approval = docs[builder.APPROVAL_RECORD_PATH]
    policy = docs[builder.POLICY_101_PATH]
    transition = docs[builder.STATE_TRANSITION_PATH]
    assert len(approval["approved_artifacts"]) == 129
    assert len(approval["withheld_artifacts"]) == 128
    assert policy["metadata"]["baseline_version"] == "1.0.1"
    assert policy["composition"]["approved_feature_id"] == "FP-035"
    assert Counter(row["approval_decision"] for row in transition["artifact_transitions"]) == Counter(
        {"APPROVED": 129, "NOT_APPROVED_PRESERVED": 128}
    )
    plan = transition["control_update_plan"]
    assert plan["doc01"]["post_document_version"] == "1.0.1"
    assert plan["doc05"]["post_document_version"] == "1.0.1"
    assert plan["doc01"]["exactly_once"] is True and plan["doc05"]["exactly_once"] is True
    assert {gate["status"] for gate in transition["remaining_gates"]} == {"NOT_RUN"}
    assert transition["transaction_boundary"]["release_status"] == "NOT_ELIGIBLE"


def test_time_evidence_does_not_treat_candidate_declared_time_as_event_time() -> None:
    _, docs = _documents()
    boundary = docs[builder.APPROVAL_RECORD_PATH]["time_evidence_boundary"]
    assert boundary["candidate_declared_prepared_at_basis"] == "DETERMINISTIC_DOCUMENT_TIMESTAMP_NOT_EVENT_EVIDENCE"
    assert boundary["source_event_timestamp"] is None
    assert boundary["cryptographic_timestamp_available"] is False


def test_tampered_output_is_rejected_and_current_package_checks() -> None:
    outputs = builder.validate_published_outputs()
    altered = dict(outputs)
    value = json.loads(altered[builder.STATE_TRANSITION_PATH])
    value["artifact_transitions"][0]["approval_decision"] = "NOT_APPROVED_PRESERVED"
    altered[builder.STATE_TRANSITION_PATH] = builder._json_bytes(value)
    with unittest.TestCase().assertRaises(builder.ApprovalApplicationError):
        builder.validate_output_bytes(altered)
    completed = subprocess.run(
        [sys.executable, "-B", str(SCRIPT_PATH), "--check"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 0, completed.stdout + completed.stderr
    assert "control commit pending" in completed.stdout


def load_tests(loader: unittest.TestLoader, tests: unittest.TestSuite, pattern: str | None):
    suite = unittest.TestSuite()
    for name, value in sorted(globals().items()):
        if name.startswith("test_") and callable(value):
            suite.addTest(unittest.FunctionTestCase(value, description=name))
    return suite
