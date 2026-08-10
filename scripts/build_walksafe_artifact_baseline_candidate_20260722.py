#!/usr/bin/env python3
"""Build the reviewed 2026-07-22 WalkSafe artifact approval candidate.

The generator is intentionally fail-closed.  It writes no output until the
content-readiness audit, the FP-035 correction candidate, and a passing
independent review record all bind the current source bytes.  Generating this
package is not an approval and never changes a live artifact state.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
from html import escape
import json
from pathlib import Path
import sys
from typing import Any, Iterable

try:
    from scripts import build_walksafe_artifact_baseline_candidate_20260721 as legacy
except ModuleNotFoundError:  # Direct execution places the scripts directory on sys.path.
    import build_walksafe_artifact_baseline_candidate_20260721 as legacy


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
LEGACY_GENERATOR_PATH = REPO_ROOT / "scripts" / "build_walksafe_artifact_baseline_candidate_20260721.py"
CONTROL_ROOT = REPO_ROOT / "docs" / "control"
DELIVERABLE_ROOT = REPO_ROOT / "docs" / "deliverables"
OUTPUT_ROOT = CONTROL_ROOT / "baseline-candidates"

REGISTER_PATH = DELIVERABLE_ROOT / "00-control" / "artifact-register.json"
CHANGE_LOG_PATH = DELIVERABLE_ROOT / "00-control" / "artifact-change-log.json"
READINESS_AUDIT_PATH = (
    CONTROL_ROOT / "audits" / "walksafe-artifact-content-readiness-audit-20260722-r001.json"
)
INDEPENDENT_REVIEW_PATH = (
    CONTROL_ROOT / "audits" / "walksafe-artifact-independent-review-record-20260722-r001.json"
)
FP035_CORRECTION_PATH = (
    CONTROL_ROOT
    / "decision-interview"
    / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"
)
POLICY_APPROVAL_PATH = (
    CONTROL_ROOT
    / "baselines"
    / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
)
POLICY_MANIFEST_PATH = (
    CONTROL_ROOT
    / "baselines"
    / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
)
PREVIOUS_CANDIDATE_PATH = (
    CONTROL_ROOT / "baselines" / "walksafe-artifact-baseline-candidate-20260721-r001.json"
)

CANDIDATE_PATH = OUTPUT_ROOT / "walksafe-artifact-baseline-candidate-20260722-r001.json"
REVIEW_MD_PATH = OUTPUT_ROOT / "walksafe-artifact-baseline-candidate-review-20260722-r001.md"
REVIEW_HTML_PATH = OUTPUT_ROOT / "walksafe-artifact-baseline-candidate-review-20260722-r001.html"

SCHEMA_VERSION = "walksafe.artifact-baseline-approval-candidate.v2"
CANDIDATE_ID = "WS-ARTIFACT-BASELINE-CANDIDATE-20260722-001"
CANDIDATE_VERSION = "1.0.0"
PREPARED_AT = "2026-07-22T18:00:00+09:00"
PREVIOUS_CANDIDATE_ID = "WS-ARTIFACT-BASELINE-CANDIDATE-20260721-001"
PREVIOUS_CANDIDATE_EXPECTED_FILE_SHA256 = "af0d9ca906cd40a3ca1aa65fa9e34d5a0733add4563e6299ef5a0c9147aa3683"
FP035_CANDIDATE_ID = "WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001"
INDEPENDENT_REVIEW_ID = "WS-ARTIFACT-INDEPENDENT-REVIEW-20260722-001"

TRACK_VERSIONED = "VERSIONED_CONTENT_BASELINE_CANDIDATE"
TRACK_ACTIVE = "ACTIVE_OPENING_SNAPSHOT_CANDIDATE"
TRACK_EVIDENCE = "EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED"
TRACK_PLANNED = "PLANNED_NOT_RUN_NOT_APPROVED"
TRACK_ORDER = (TRACK_VERSIONED, TRACK_ACTIVE, TRACK_EVIDENCE, TRACK_PLANNED)
EXPECTED_COUNTS = {
    TRACK_VERSIONED: 102,
    TRACK_ACTIVE: 27,
    TRACK_EVIDENCE: 53,
    TRACK_PLANNED: 75,
}
FP035_DEPENDENT_CODES = {"REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"}
EXPECTED_GATE_IDS = {
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
}


class CandidateError(RuntimeError):
    """The source set is contradictory or cannot form an approval candidate."""


class InputNotReadyError(CandidateError):
    """A required reviewed input has not been produced or passed yet."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise CandidateError(message)


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def _object_sha256(value: Any) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _relative(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _source_binding(name: str, path: Path) -> dict[str, Any]:
    _require(path.is_file(), f"missing source: {_relative(path)}")
    return {
        "name": name,
        "path": _relative(path),
        "byte_length": path.stat().st_size,
        "sha256": _file_sha256(path),
    }


def _verify_content_hash(payload: dict[str, Any], field: str, label: str) -> None:
    _require(field in payload, f"{label} has no {field}")
    body = {key: value for key, value in payload.items() if key != field}
    _require(payload[field] == _object_sha256(body), f"{label} content hash differs")


def _binding_items(value: Any) -> Iterable[dict[str, Any]]:
    """Yield path/SHA bindings from either a list or a named mapping."""
    if isinstance(value, list):
        for item in value:
            if isinstance(item, dict) and isinstance(item.get("path"), str):
                yield item
    elif isinstance(value, dict):
        for name, item in value.items():
            if isinstance(item, dict) and isinstance(item.get("path"), str):
                yield {"name": name, **item}


def _verify_bound_files(bindings: Any, label: str) -> None:
    items = list(_binding_items(bindings))
    _require(bool(items), f"{label} has no file bindings")
    for item in items:
        path = REPO_ROOT / item["path"]
        _require(path.is_file(), f"{label} binding is missing: {item['path']}")
        _require(item.get("sha256") == _file_sha256(path), f"{label} binding is stale: {item['path']}")
        if "byte_length" in item:
            _require(item["byte_length"] == path.stat().st_size, f"{label} byte length differs: {item['path']}")


def _validate_readiness_audit(audit: dict[str, Any]) -> None:
    _verify_content_hash(audit, "audit_content_sha256", "readiness audit")
    _require(
        audit.get("source_binding_sha256") == _object_sha256(audit.get("source_bindings")),
        "readiness audit source binding hash differs",
    )
    _verify_bound_files(audit["source_bindings"], "readiness audit")
    metadata = audit.get("metadata", {})
    _require(
        metadata.get("audit_id") == "WS-ARTIFACT-CONTENT-READINESS-AUDIT-20260722-001",
        "unexpected readiness audit ID",
    )
    _require(
        metadata.get("status") == "COMPLETED_PENDING_INDEPENDENT_REVIEW",
        "readiness audit is not the completed pre-review snapshot",
    )
    boundary = audit.get("authorization_boundary", {})
    _require(boundary.get("audit_is_approval") is False, "readiness audit claims approval")
    _require(boundary.get("artifact_state_changed_by_audit") is False, "readiness audit changed state")
    _require(boundary.get("previous_candidate_applied") is False, "old candidate was applied")
    _require(boundary.get("fp035_correction_effective_now") is False, "FP-035 correction already claims effect")
    _require(boundary.get("remaining_gates_are_waived") is False, "readiness audit waives gates")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "readiness audit release boundary differs")

    summary = audit.get("readiness_summary", {})
    counts = summary.get("counts", {})
    expected_readiness = {
        "READY_FOR_BUNDLED_CONTENT_APPROVAL": 124,
        "READY_WITH_FP035_CORRECTION_DEPENDENCY": 5,
        "EVIDENCE_OR_EXTERNAL_VALUE_PENDING": 53,
        "PLANNED_NOT_RUN": 75,
    }
    _require(counts == expected_readiness, f"readiness counts differ: {counts}")
    _require(summary.get("approval_candidate_count") == 129, "approval candidate count differs")
    _require(summary.get("versioned_content_baseline_candidate_count") == 102, "versioned count differs")
    _require(summary.get("active_opening_snapshot_candidate_count") == 27, "Active count differs")
    _require(summary.get("not_approval_candidate_count") == 128, "not-approved count differs")
    _require(
        set(summary.get("fp035_correction_dependent_codes", [])) == FP035_DEPENDENT_CODES,
        "FP-035 dependent artifact set differs",
    )
    assessments = audit.get("artifact_assessments", [])
    _require(len(assessments) == 257, "readiness audit does not contain 257 assessments")
    _require(len({item.get("display_code") for item in assessments}) == 257, "readiness codes are not unique")


def _validate_fp035_correction(candidate: dict[str, Any]) -> None:
    _verify_content_hash(candidate, "candidate_content_sha256", "FP-035 correction candidate")
    _require(
        candidate.get("source_binding_sha256") == _object_sha256(candidate.get("source_bindings")),
        "FP-035 source binding hash differs",
    )
    _verify_bound_files(candidate["source_bindings"], "FP-035 correction candidate")
    metadata = candidate.get("metadata", {})
    _require(metadata.get("candidate_id") == FP035_CANDIDATE_ID, "unexpected FP-035 candidate ID")
    _require(metadata.get("candidate_version") == "1.0.0", "unexpected FP-035 candidate record version")
    _require(metadata.get("approval_status") == "NOT_APPROVED", "FP-035 candidate already claims approval")
    _require(metadata.get("effective_status") == "NOT_EFFECTIVE", "FP-035 candidate already claims effect")
    planned = candidate.get("planned_effective_policy", {})
    _require(
        planned.get("baseline_id_after_explicit_approval") == "PB-WALKSAFE-FEATURE-POLICY-1.0.1"
        and planned.get("baseline_version_after_explicit_approval") == "1.0.1",
        "FP-035 candidate does not establish planned policy 1.0.1",
    )
    _require(planned.get("state_change_now") is False, "FP-035 candidate changes policy now")
    _require(planned.get("must_be_approved_with_affected_artifacts") is True, "FP-035 is not bundled")
    correction = candidate.get("correction", {})
    _require(set(correction.get("affected_artifact_codes", [])) == FP035_DEPENDENT_CODES, "FP-035 direct set differs")
    boundary = candidate.get("authorization_boundary", {})
    _require(boundary.get("candidate_generation_is_approval") is False, "FP-035 generation claims approval")
    _require(boundary.get("policy_1_0_0_is_superseded_now") is False, "policy 1.0.0 already claims supersession")
    _require(boundary.get("affected_artifacts_are_approved_now") is False, "affected artifacts already claim approval")
    _require(boundary.get("remaining_gates_are_waived") is False, "FP-035 candidate waives gates")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "FP-035 release boundary differs")
    _require(
        boundary.get("mobile_network_branch_implementation_status")
        == "FROZEN_PENDING_EXACT_BUNDLED_APPROVAL",
        "mobile-network implementation branch is not frozen",
    )
    _require(
        boundary.get("mobile_network_branch_formal_test_status")
        == "NOT_RUN_BLOCKED_PENDING_EXACT_BUNDLED_APPROVAL",
        "mobile-network formal test branch is not blocked",
    )


def _review_bindings(review: dict[str, Any]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    result.extend(_binding_items(review.get("source_bindings")))
    for item in review.get("reviews", []):
        if isinstance(item, dict):
            result.extend(_binding_items(item.get("source_bindings")))
    return result


def _validate_independent_review(review: dict[str, Any]) -> None:
    _verify_content_hash(review, "content_sha256", "independent review")
    _require(
        review.get("schema_version") == "walksafe.artifact-independent-review-record.v1",
        "independent review schema differs",
    )
    metadata = review.get("metadata", {})
    _require(metadata.get("review_record_id") == INDEPENDENT_REVIEW_ID, "independent review ID differs")
    _require(metadata.get("document_version") == "1.0.0", "independent review version differs")
    _require(metadata.get("review_status") == "PASS", "independent review has not passed")
    _require(
        metadata.get("approval_status") == "NOT_AN_ARTIFACT_APPROVAL",
        "independent review incorrectly claims artifact approval",
    )
    scope = review.get("scope", {})
    _require(scope.get("artifact_count") == 257, "independent review artifact count differs")
    _require(scope.get("candidate_count") == 129, "independent review candidate count differs")
    _require(scope.get("pending_count") == 128, "independent review pending count differs")
    reviews = review.get("reviews", [])
    _require(isinstance(reviews, list) and len(reviews) >= 3, "at least three independent reviews are required")
    review_ids: set[str] = set()
    for item in reviews:
        _require(isinstance(item, dict), "independent review row is not an object")
        review_id = item.get("review_id")
        _require(isinstance(review_id, str) and review_id, "independent review row has no ID")
        _require(review_id not in review_ids, f"duplicate independent review ID: {review_id}")
        review_ids.add(review_id)
        _require(
            item.get("reviewer_type") == "PEER_AGENT_TECHNICAL_REVIEW",
            f"unexpected reviewer type: {review_id}",
        )
        _require(item.get("independent_from_authored_scope") is True, f"review is not independent: {review_id}")
        _require(item.get("result") == "PASS", f"review did not pass: {review_id}")
        _require(bool(item.get("reviewed_scope")), f"reviewed scope is empty: {review_id}")
        _require(isinstance(item.get("checks_run"), list) and item["checks_run"], f"checks are empty: {review_id}")
        _require(list(_binding_items(item.get("source_bindings"))), f"source bindings are empty: {review_id}")
    final = review.get("final_result", {})
    _require(final.get("status") == "PASS", "independent final result did not pass")
    for severity in ("open_critical", "open_high", "open_medium"):
        _require(final.get(severity) == 0, f"independent review has {severity}")
    _require(final.get("all_required_reviews_passed") is True, "required reviews did not all pass")
    _require(isinstance(review.get("resolved_findings"), list), "resolved findings is not a list")
    limitations_text = json.dumps(review.get("limitations"), ensure_ascii=False)
    _require("승인" in limitations_text, "review limitations omit the human-approval boundary")
    _require("외부" in limitations_text and "전문" in limitations_text, "review limitations omit external expert review")
    _require("실행" in limitations_text and "증거" in limitations_text, "review limitations omit execution evidence")

    bindings = _review_bindings(review)
    _require(bool(bindings), "independent review has no source bindings")
    _verify_bound_files(bindings, "independent review")
    audit_relative = _relative(READINESS_AUDIT_PATH)
    audit_sha = _file_sha256(READINESS_AUDIT_PATH)
    _require(
        any(item.get("path") == audit_relative and item.get("sha256") == audit_sha for item in bindings),
        "independent review does not bind the current readiness audit",
    )


def _validate_previous_candidate(previous: dict[str, Any]) -> None:
    _verify_content_hash(previous, "candidate_record_content_sha256", "previous candidate")
    _require(
        _file_sha256(PREVIOUS_CANDIDATE_PATH) == PREVIOUS_CANDIDATE_EXPECTED_FILE_SHA256,
        "previous unapproved candidate archive bytes changed",
    )
    metadata = previous.get("metadata", {})
    _require(metadata.get("candidate_id") == PREVIOUS_CANDIDATE_ID, "unexpected previous candidate ID")
    _require(metadata.get("candidate_version") == "1.0.0", "unexpected previous candidate version")
    _require(metadata.get("approval_status") == "NOT_APPROVED", "previous candidate claims approval")
    _require(metadata.get("approver") is None and metadata.get("approved_at") is None, "previous candidate has approval metadata")


def _validate_policy_sources(approval: dict[str, Any], manifest: dict[str, Any]) -> None:
    payload = approval.get("approved_baseline_payload", {})
    _require(payload.get("baseline_id") == "PB-WALKSAFE-FEATURE-POLICY-1.0.0", "policy approval ID differs")
    _require(payload.get("baseline_version") == "1.0.0", "policy approval version differs")
    boundary = approval.get("approval_boundary", payload.get("approval_boundary", {}))
    _require(boundary.get("content_approval_status") == "APPROVED", "policy content is not approved")
    _require(boundary.get("baseline_status") == "APPROVED", "policy baseline is not approved")
    _require(boundary.get("remaining_gates_are_waived") is False, "policy approval waives gates")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "policy approval release boundary differs")
    manifest_payload = manifest.get("baseline_payload", {})
    _require(manifest_payload.get("baseline_id") == "PB-WALKSAFE-FEATURE-POLICY-1.0.0", "policy manifest ID differs")
    _require(manifest_payload.get("baseline_version") == "1.0.0", "policy manifest version differs")
    gates = approval.get("remaining_gates", payload.get("remaining_gates", []))
    _require({gate.get("id") for gate in gates} == EXPECTED_GATE_IDS, "policy gate set differs")
    _require(all(gate.get("status") == "NOT_RUN" for gate in gates), "a policy gate claims execution")


def _load_inputs() -> dict[str, dict[str, Any]]:
    if not INDEPENDENT_REVIEW_PATH.is_file():
        raise InputNotReadyError(
            "independent review record is not available: " + _relative(INDEPENDENT_REVIEW_PATH)
        )
    paths = {
        "artifact_register": REGISTER_PATH,
        "artifact_change_log": CHANGE_LOG_PATH,
        "readiness_audit": READINESS_AUDIT_PATH,
        "independent_review": INDEPENDENT_REVIEW_PATH,
        "fp035_correction_candidate": FP035_CORRECTION_PATH,
        "policy_approval": POLICY_APPROVAL_PATH,
        "policy_manifest": POLICY_MANIFEST_PATH,
        "previous_unapproved_candidate": PREVIOUS_CANDIDATE_PATH,
    }
    for path in paths.values():
        _require(path.is_file(), f"missing required input: {_relative(path)}")
    inputs = {name: legacy.load_strict_json(path) for name, path in paths.items()}
    _validate_readiness_audit(inputs["readiness_audit"])
    _validate_independent_review(inputs["independent_review"])
    _validate_fp035_correction(inputs["fp035_correction_candidate"])
    _validate_previous_candidate(inputs["previous_unapproved_candidate"])
    _validate_policy_sources(inputs["policy_approval"], inputs["policy_manifest"])
    register = inputs["artifact_register"]
    _verify_content_hash(register, "content_sha256", "DOC-01")
    _verify_content_hash(inputs["artifact_change_log"], "content_sha256", "DOC-05")
    _require(register.get("summary", {}).get("artifact_type_count") == 257, "DOC-01 does not contain 257 artifacts")
    _require(register.get("summary", {}).get("approved_artifact_count") == 0, "DOC-01 already claims approval")
    _require(register.get("authorization_boundary", {}).get("release_status") == "NOT_ELIGIBLE", "DOC-01 release differs")
    return inputs


def preflight() -> dict[str, Any]:
    """Validate immutable input conditions without building or writing output."""
    inputs = _load_inputs()
    return {
        "status": "READY",
        "candidate_id": CANDIDATE_ID,
        "input_count": len(inputs),
        "independent_review_id": INDEPENDENT_REVIEW_ID,
    }


def _primary_units(register_rows: list[dict[str, Any]]) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    anchor_units, preambles = legacy._artifact_anchor_units(register_rows)
    units: dict[str, dict[str, Any]] = dict(anchor_units)
    for row in register_rows:
        code = row["display_code"]
        if code in units:
            continue
        path = legacy._artifact_path(row)
        if path is None:
            continue
        raw = path.read_bytes()
        units[code] = {
            "unit_kind": "WHOLE_FILE_ARTIFACT",
            "path": _relative(path),
            "anchor": None,
            "byte_length": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest(),
        }
    return units, preambles


def _track_for(assessment: dict[str, Any]) -> str:
    if assessment.get("approval_track") == "VERSIONED_CONTENT_BASELINE":
        return TRACK_VERSIONED
    if assessment.get("approval_track") == "ACTIVE_OPENING_SNAPSHOT":
        return TRACK_ACTIVE
    if assessment.get("readiness") == "EVIDENCE_OR_EXTERNAL_VALUE_PENDING":
        return TRACK_EVIDENCE
    if assessment.get("readiness") == "PLANNED_NOT_RUN":
        return TRACK_PLANNED
    raise CandidateError(f"unsupported readiness classification: {assessment.get('display_code')}")


def _related_file_bindings(
    row: dict[str, Any], compound: dict[str, Any]
) -> list[dict[str, Any]]:
    roles_by_path: dict[str, set[str]] = defaultdict(set)
    for component in compound["normative_components"] + compound["integrity_only_components"]:
        roles_by_path[component["path"]].add(component["role"])
    bindings: list[dict[str, Any]] = []
    for relative, roles in sorted(roles_by_path.items()):
        path = REPO_ROOT / relative
        _require(path.is_file(), f"related file is missing: {row['display_code']} -> {relative}")
        versions = legacy._declared_versions(path, [row])
        bindings.append(
            {
                "path": relative,
                "byte_length": path.stat().st_size,
                "file_sha256": _file_sha256(path),
                "declared_versions": versions,
                "version_label": ", ".join(versions) if versions else "HASH_BOUND_NOT_SEPARATELY_VERSIONED",
                "component_roles": sorted(roles),
            }
        )
    _require(bool(bindings), f"candidate has no related file binding: {row['display_code']}")
    return bindings


def _target_state(row: dict[str, Any], track: str) -> dict[str, Any]:
    if track == TRACK_VERSIONED:
        return {
            "lifecycle_status": "APPROVED_BASELINED",
            "approval_status": "APPROVED",
            "baseline_status": "BASELINED",
            "approved_document_version": "1.0.0",
            "planned_baseline_id": f"CB-WALKSAFE-{row['display_code']}-1.0.0",
            "materialized_now": False,
        }
    if track == TRACK_ACTIVE:
        return {
            "lifecycle_status": "ACTIVE",
            "approval_status": "APPROVED_OPENING_SNAPSHOT",
            "baseline_status": "ACTIVE_CONTINUOUSLY_UPDATED",
            "approved_snapshot_version": "1.0.0",
            "planned_snapshot_id": f"{row['artifact_instance_id']}-SNAPSHOT-001",
            "materialized_now": False,
        }
    if track == TRACK_PLANNED:
        return {
            "lifecycle_status": "PLANNED",
            "approval_status": "NOT_APPROVED",
            "execution_status": "NOT_RUN",
            "materialized_now": False,
        }
    return {
        "lifecycle_status": "DRAFT",
        "approval_status": "NOT_APPROVED",
        "pending_status": "EVIDENCE_OR_EXTERNAL_VALUE_PENDING",
        "materialized_now": False,
    }


def _build_dispositions(
    audit: dict[str, Any], register: dict[str, Any]
) -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]]]:
    register_rows = register["artifacts"]
    row_by_code = {row["display_code"]: row for row in register_rows}
    assessments = audit["artifact_assessments"]
    _require(set(row_by_code) == {item["display_code"] for item in assessments}, "audit/register code sets differ")
    units, preambles = _primary_units(register_rows)
    generated = legacy._generated_path_components(register_rows)
    dispositions: list[dict[str, Any]] = []
    for assessment in assessments:
        code = assessment["display_code"]
        row = row_by_code[code]
        track = _track_for(assessment)
        approval_proposed = track in {TRACK_VERSIONED, TRACK_ACTIVE}
        _require(assessment.get("approval_proposed") is approval_proposed, f"approval flag differs: {code}")
        current_unit = units.get(code)
        audited_unit = assessment.get("content_unit")
        if assessment.get("content_unit_deferred_to_final_candidate"):
            _require(code in {"DOC-01", "DOC-05"}, f"unexpected deferred content unit: {code}")
            _require(audited_unit is None and current_unit is not None, f"deferred unit cannot be finalized: {code}")
        elif audited_unit is not None:
            _require(audited_unit == current_unit, f"audited content unit is stale: {code}")

        compound = None
        related_bindings: list[dict[str, Any]] = []
        if approval_proposed:
            _require(current_unit is not None, f"approval candidate has no primary content unit: {code}")
            compound = legacy._compound_approval_unit(code, current_unit, generated[code])
            related_bindings = _related_file_bindings(row, compound)
        upstream = [value.removeprefix("DLV-") for value in row["trace"]["upstream_types"]]
        dispositions.append(
            {
                "display_code": code,
                "artifact_type_code": assessment["artifact_type_code"],
                "artifact_instance_id": row["artifact_instance_id"],
                "title": assessment["title"],
                "category": assessment["category"],
                "applicability": assessment["applicability"],
                "activation_condition": assessment["activation_condition"],
                "track": track,
                "readiness": assessment["readiness"],
                "readiness_reason": assessment["readiness_reason"],
                "approval_proposed": approval_proposed,
                "requires_fp035_phase_0": code in FP035_DEPENDENT_CODES,
                "current_state": {
                    "lifecycle_status": assessment["current_lifecycle_status"],
                    "approval_status": assessment["current_approval_status"],
                    "document_version": row["version"]["document_version"],
                },
                "target_state_after_exact_owner_approval": _target_state(row, track),
                "upstream_artifact_codes": upstream,
                "canonical_or_planned_path": assessment["canonical_or_planned_path"],
                "coverage_anchor": assessment["coverage_anchor"],
                "primary_content_unit": current_unit,
                "compound_approval_unit": compound,
                "related_file_bindings": related_bindings,
                "pending_completion_contract": assessment["pending_completion_contract"],
                "implementation_conformance_assessed": False,
                "execution_completion_claimed": False,
                "release_completion_claimed": False,
            }
        )
    return dispositions, preambles


def _build_file_inventory(dispositions: list[dict[str, Any]]) -> list[dict[str, Any]]:
    aggregate: dict[str, dict[str, Any]] = {}
    for item in dispositions:
        if not item["approval_proposed"]:
            continue
        for binding in item["related_file_bindings"]:
            relative = binding["path"]
            if relative not in aggregate:
                aggregate[relative] = {
                    "path": relative,
                    "byte_length": binding["byte_length"],
                    "file_sha256": binding["file_sha256"],
                    "declared_versions": set(binding["declared_versions"]),
                    "covered_candidate_codes": set(),
                    "component_roles": set(),
                }
            current = aggregate[relative]
            _require(current["file_sha256"] == binding["file_sha256"], f"inconsistent file hash: {relative}")
            current["declared_versions"].update(binding["declared_versions"])
            current["covered_candidate_codes"].add(item["display_code"])
            current["component_roles"].update(binding["component_roles"])
    inventory: list[dict[str, Any]] = []
    for index, relative in enumerate(sorted(aggregate), 1):
        item = aggregate[relative]
        versions = sorted(item["declared_versions"])
        inventory.append(
            {
                "file_id": f"APPROVAL-FILE-{index:03d}",
                "path": relative,
                "byte_length": item["byte_length"],
                "file_sha256": item["file_sha256"],
                "declared_versions": versions,
                "version_label": ", ".join(versions) if versions else "HASH_BOUND_NOT_SEPARATELY_VERSIONED",
                "covered_candidate_codes": sorted(item["covered_candidate_codes"]),
                "component_roles": sorted(item["component_roles"]),
            }
        )
    _require(bool(inventory), "approval file inventory is empty")
    return inventory


def _approval_phases(dispositions: list[dict[str, Any]]) -> list[dict[str, Any]]:
    candidates = {item["display_code"] for item in dispositions if item["approval_proposed"]}
    row_by_code = {item["display_code"]: item for item in dispositions}
    remaining = set(candidates)
    completed: set[str] = set()
    phases: list[dict[str, Any]] = [
        {
            "phase": 0,
            "phase_kind": "FP035_CORRECTION_AND_POLICY_1_0_1",
            "approval_object": FP035_CANDIDATE_ID,
            "directly_affected_artifact_codes": sorted(FP035_DEPENDENT_CODES),
            "logical_effect_after_exact_owner_approval": "STAGE_POLICY_BASELINE_1.0.1_NOT_LIVE",
            "live_state_transition_allowed": False,
            "failure_effect": "ABORT_ALL_STAGED_EVENTS_AND_KEEP_POLICY_1.0.0_EFFECTIVE",
        }
    ]
    while remaining:
        ready = sorted(
            code
            for code in remaining
            if (set(row_by_code[code]["upstream_artifact_codes"]) & candidates) <= completed
        )
        _require(bool(ready), "candidate dependency graph is cyclic")
        external = sorted(
            {
                upstream
                for code in ready
                for upstream in row_by_code[code]["upstream_artifact_codes"]
                if upstream not in candidates
            }
        )
        phases.append(
            {
                "phase": len(phases),
                "phase_kind": "ARTIFACT_LOGICAL_APPROVAL_STAGE",
                "artifact_codes": ready,
                "required_predecessor_candidate_codes": sorted(
                    {
                        upstream
                        for code in ready
                        for upstream in row_by_code[code]["upstream_artifact_codes"]
                        if upstream in candidates
                    }
                ),
                "non_candidate_upstream_codes_disclosed_by_readiness_audit": external,
                "requires_phase_0": bool(FP035_DEPENDENT_CODES & set(ready)),
                "live_state_transition_allowed": False,
                "failure_effect": "ABORT_ALL_STAGED_EVENTS_WITHOUT_ANY_LIVE_TRANSITION",
            }
        )
        completed.update(ready)
        remaining.difference_update(ready)
    _require(completed == candidates, "approval phases do not cover all candidates")
    return phases


def _classification_summary(dispositions: list[dict[str, Any]], inventory: list[dict[str, Any]]) -> dict[str, Any]:
    counts = Counter(item["track"] for item in dispositions)
    _require(dict(counts) == EXPECTED_COUNTS, f"classification counts differ: {dict(counts)}")
    return {
        **EXPECTED_COUNTS,
        "artifact_count": 257,
        "approval_candidate_count": 129,
        "versioned_content_baseline_candidate_count": 102,
        "active_opening_snapshot_candidate_count": 27,
        "not_approved_count": 128,
        "evidence_or_external_pending_count": 53,
        "planned_not_run_count": 75,
        "fp035_dependent_candidate_count": 5,
        "approval_file_count": len(inventory),
    }


def _required_approval_statement(target: dict[str, Any], source: dict[str, Any]) -> str:
    return (
        f"승인 후보 {CANDIDATE_ID} 버전 {CANDIDATE_VERSION}, 승인대상 지문 {target['approval_target_sha256']}, "
        f"분류 지문 {target['classification_binding_sha256']}, 파일집합 지문 {target['file_set_binding_sha256']}, "
        f"단계순서 지문 {target['phase_order_binding_sha256']}, 입력집합 지문 {target['source_binding_sha256']}를 근거로 "
        f"기존 미승인 후보 {PREVIOUS_CANDIDATE_ID} 버전 1.0.0 파일 지문 {source['previous_file_sha256']}은 승인·적용하지 않은 채 "
        "기록으로 보존하고 후보 선택 관계에서만 새 후보로 대체하며, 0단계에서 "
        f"FP-035 정정 후보 {FP035_CANDIDATE_ID} 파일 지문 {source['fp035_file_sha256']}을 정확히 승인하여 "
        "정책 기준선 1.0.1을 논리적으로 성립시킨 뒤, 의존성 단계 순서에 따라 버전형 내용 기준선 102개와 계속 갱신형 Active 최초본 27개를 합한 129개 복합 승인단위를 승인하고, "
        "외부값·실행근거 대기 53개와 Planned/NOT_RUN 75개를 합한 128개는 승인하지 않으며, 모든 단계는 하나의 불변 snapshot에서 live 전환 없이 준비하고 어느 단계든 실패하면 전부 폐기한 후 전 단계 성공 뒤 별도 반영 절차에서만 상태를 한 번 전환하고, "
        "이 승인으로 현행 구현 적합성·시험·배포·운영·인수·종료 완료를 주장하지 않으며 5개 gate는 NOT_RUN·미면제, 출시는 NOT_ELIGIBLE로 유지하는 것을 승인합니다."
    )


def build_candidate() -> dict[str, Any]:
    inputs = _load_inputs()
    audit = inputs["readiness_audit"]
    register = inputs["artifact_register"]
    correction = inputs["fp035_correction_candidate"]
    previous = inputs["previous_unapproved_candidate"]

    dispositions, preambles = _build_dispositions(audit, register)
    inventory = _build_file_inventory(dispositions)
    summary = _classification_summary(dispositions, inventory)
    phases = _approval_phases(dispositions)

    source_paths = {
        "generator": GENERATOR_PATH,
        "legacy_compound_unit_helper": LEGACY_GENERATOR_PATH,
        "final_doc01_artifact_register": REGISTER_PATH,
        "final_doc05_artifact_change_log": CHANGE_LOG_PATH,
        "content_readiness_audit": READINESS_AUDIT_PATH,
        "independent_review_record": INDEPENDENT_REVIEW_PATH,
        "fp035_correction_candidate": FP035_CORRECTION_PATH,
        "approved_policy_1_0_0_record": POLICY_APPROVAL_PATH,
        "approved_policy_1_0_0_manifest": POLICY_MANIFEST_PATH,
        "previous_unapproved_candidate": PREVIOUS_CANDIDATE_PATH,
    }
    source_bindings = [_source_binding(name, path) for name, path in sorted(source_paths.items())]
    source_binding_sha256 = _object_sha256(source_bindings)
    classification_binding_sha256 = _object_sha256(dispositions)
    file_set_binding_sha256 = _object_sha256(inventory)
    preamble_binding_sha256 = _object_sha256(preambles)
    phase_order_binding_sha256 = _object_sha256(phases)

    gates = audit["remaining_gates"]
    _require({gate["id"] for gate in gates} == EXPECTED_GATE_IDS, "audit gate set differs")
    _require(all(gate["status"] == "NOT_RUN" for gate in gates), "an audit gate claims execution")

    predecessor = {
        "candidate_id": PREVIOUS_CANDIDATE_ID,
        "candidate_version": previous["metadata"]["candidate_version"],
        "path": _relative(PREVIOUS_CANDIDATE_PATH),
        "file_sha256": _file_sha256(PREVIOUS_CANDIDATE_PATH),
        "approval_status": "NOT_APPROVED",
        "was_applied": False,
        "preservation": "PRESERVE_UNCHANGED_AS_UNAPPROVED_HISTORICAL_CANDIDATE",
        "supersession_scope": "CANDIDATE_SELECTION_ONLY_NOT_AN_ARTIFACT_STATE_TRANSITION",
    }
    fp035_phase_0 = {
        "candidate_id": FP035_CANDIDATE_ID,
        "candidate_record_version": correction["metadata"]["candidate_version"],
        "path": _relative(FP035_CORRECTION_PATH),
        "file_sha256": _file_sha256(FP035_CORRECTION_PATH),
        "candidate_content_sha256": correction["candidate_content_sha256"],
        "current_approval_status": "NOT_APPROVED",
        "current_effective_status": "NOT_EFFECTIVE",
        "planned_policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "planned_policy_baseline_version": "1.0.1",
        "approval_phase": 0,
        "same_exact_owner_statement_required": True,
    }
    atomic_boundary = {
        "candidate_generation_is_approval": False,
        "candidate_generation_changes_live_state": False,
        "precondition": "VERIFY_AND_FREEZE_EVERY_BOUND_PATH_AND_SHA_ONCE_BEFORE_PHASE_0",
        "during_phases": "LOGICAL_STAGING_ONLY_NO_DOC01_DOC05_OR_OTHER_LIVE_FILE_TRANSITION",
        "on_any_failure": "DISCARD_ALL_STAGED_EVENTS_AND_KEEP_ALL_LIVE_STATES_UNCHANGED",
        "after_all_phases_succeed": "A_SEPARATE_APPROVAL_APPLICATION_STEP_MAY_MATERIALIZE_ONCE",
        "post_success_order": [
            "WRITE_IMMUTABLE_PRETRANSITION_MANIFEST",
            "WRITE_APPROVAL_RECORD_BOUND_TO_EXACT_OWNER_SENTENCE",
            "MATERIALIZE_POLICY_1.0.1_AND_129_ARTIFACT_STATES_ATOMICALLY",
            "UPDATE_DOC01_AND_DOC05_ONCE",
            "WRITE_POSTTRANSITION_VERIFICATION_MANIFEST",
        ],
        "implementation_conformance_assessed": False,
        "execution_or_test_completion_claimed": False,
        "release_or_handover_approval_claimed": False,
    }

    target_body = {
        "candidate_id": CANDIDATE_ID,
        "candidate_version": CANDIDATE_VERSION,
        "previous_candidate_binding": predecessor,
        "fp035_phase_0_binding": fp035_phase_0,
        "independent_review_id": INDEPENDENT_REVIEW_ID,
        "classification_summary": summary,
        "classification_binding_sha256": classification_binding_sha256,
        "file_set_binding_sha256": file_set_binding_sha256,
        "common_preamble_binding_sha256": preamble_binding_sha256,
        "phase_order_binding_sha256": phase_order_binding_sha256,
        "source_binding_sha256": source_binding_sha256,
        "atomic_transition_boundary": atomic_boundary,
        "remaining_gate_count": 5,
        "remaining_gates_are_waived": False,
        "release_status": "NOT_ELIGIBLE",
    }
    approval_target_sha256 = _object_sha256(target_body)
    approval_target = {**target_body, "approval_target_sha256": approval_target_sha256}
    source_digest_values = {
        "previous_file_sha256": predecessor["file_sha256"],
        "fp035_file_sha256": fp035_phase_0["file_sha256"],
    }
    statement = _required_approval_statement(approval_target, source_digest_values)

    candidate: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "metadata": {
            "candidate_id": CANDIDATE_ID,
            "candidate_version": CANDIDATE_VERSION,
            "controlled_revision": 1,
            "prepared_at": PREPARED_AT,
            "lifecycle_status": "READY_FOR_EXACT_OWNER_APPROVAL",
            "approval_status": "NOT_APPROVED",
            "effective_status": "NOT_EFFECTIVE",
            "approved_at": None,
            "approver": None,
            "supersedes_candidate_id": PREVIOUS_CANDIDATE_ID,
            "supersession_scope": "CANDIDATE_SELECTION_ONLY",
        },
        "source_bindings": source_bindings,
        "source_binding_sha256": source_binding_sha256,
        "predecessor_candidate": predecessor,
        "fp035_phase_0": fp035_phase_0,
        "authority_boundary": {
            "generation_is_approval": False,
            "generation_changes_artifact_state": False,
            "previous_candidate_is_approved_or_applied": False,
            "fp035_correction_is_approved_or_effective_now": False,
            "current_implementation_used_as_policy_basis": False,
            "implementation_conformance_assessed": False,
            "execution_or_test_completion_claimed": False,
            "remaining_gates_are_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "classification_summary": summary,
        "artifact_dispositions": dispositions,
        "classification_binding_sha256": classification_binding_sha256,
        "approval_file_inventory": inventory,
        "file_set_binding_sha256": file_set_binding_sha256,
        "common_preamble_units": preambles,
        "common_preamble_binding_sha256": preamble_binding_sha256,
        "approval_phases": phases,
        "phase_order_binding_sha256": phase_order_binding_sha256,
        "atomic_transition_boundary": atomic_boundary,
        "remaining_gates": gates,
        "approval_target": approval_target,
        "approval_target_sha256": approval_target_sha256,
        "required_owner_approval_statement": statement,
        "required_owner_approval_statement_sha256": hashlib.sha256(statement.encode("utf-8")).hexdigest(),
    }
    candidate["candidate_record_content_sha256"] = _object_sha256(candidate)
    validate_candidate(candidate, verify_files=True)
    return candidate


def validate_candidate(candidate: dict[str, Any], *, verify_files: bool) -> None:
    _require(candidate.get("schema_version") == SCHEMA_VERSION, "candidate schema differs")
    metadata = candidate.get("metadata", {})
    _require(metadata.get("candidate_id") == CANDIDATE_ID, "candidate ID differs")
    _require(metadata.get("candidate_version") == CANDIDATE_VERSION, "candidate version differs")
    _require(metadata.get("approval_status") == "NOT_APPROVED", "candidate claims approval")
    _require(metadata.get("effective_status") == "NOT_EFFECTIVE", "candidate claims effect")
    _require(metadata.get("approver") is None and metadata.get("approved_at") is None, "candidate invents approver")
    _require(metadata.get("supersession_scope") == "CANDIDATE_SELECTION_ONLY", "supersession scope differs")
    _require(candidate["source_binding_sha256"] == _object_sha256(candidate["source_bindings"]), "source hash differs")
    _require(
        candidate["classification_binding_sha256"] == _object_sha256(candidate["artifact_dispositions"]),
        "classification hash differs",
    )
    _require(candidate["file_set_binding_sha256"] == _object_sha256(candidate["approval_file_inventory"]), "file set hash differs")
    _require(
        candidate["common_preamble_binding_sha256"] == _object_sha256(candidate["common_preamble_units"]),
        "preamble hash differs",
    )
    _require(candidate["phase_order_binding_sha256"] == _object_sha256(candidate["approval_phases"]), "phase hash differs")
    target_body = {key: value for key, value in candidate["approval_target"].items() if key != "approval_target_sha256"}
    _require(candidate["approval_target_sha256"] == _object_sha256(target_body), "approval target hash differs")
    _require(
        candidate["approval_target"]["approval_target_sha256"] == candidate["approval_target_sha256"],
        "nested approval target hash differs",
    )
    record_body = {key: value for key, value in candidate.items() if key != "candidate_record_content_sha256"}
    _require(candidate["candidate_record_content_sha256"] == _object_sha256(record_body), "candidate content hash differs")

    rows = candidate["artifact_dispositions"]
    _require(len(rows) == len({row["display_code"] for row in rows}) == 257, "candidate rows are not 257 unique rows")
    counts = Counter(row["track"] for row in rows)
    _require(dict(counts) == EXPECTED_COUNTS, f"candidate row counts differ: {dict(counts)}")
    candidate_rows = [row for row in rows if row["approval_proposed"]]
    pending_rows = [row for row in rows if not row["approval_proposed"]]
    _require(len(candidate_rows) == 129 and len(pending_rows) == 128, "candidate/pending split differs")
    for row in candidate_rows:
        compound = row.get("compound_approval_unit")
        _require(isinstance(compound, dict), f"missing compound unit: {row['display_code']}")
        compound_body = {key: value for key, value in compound.items() if key != "compound_sha256"}
        _require(compound["compound_sha256"] == _object_sha256(compound_body), f"compound hash differs: {row['display_code']}")
        _require(bool(row.get("related_file_bindings")), f"missing related file bindings: {row['display_code']}")
        _require(
            all(binding.get("file_sha256") and binding.get("version_label") for binding in row["related_file_bindings"]),
            f"related file version/SHA differs: {row['display_code']}",
        )
    for row in pending_rows:
        _require(row.get("compound_approval_unit") is None, f"pending row has approval unit: {row['display_code']}")
        _require(row.get("related_file_bindings") == [], f"pending row has approval file bindings: {row['display_code']}")
        _require(
            row["target_state_after_exact_owner_approval"]["approval_status"] == "NOT_APPROVED",
            f"pending row would be approved: {row['display_code']}",
        )
    fp_codes = {row["display_code"] for row in rows if row["requires_fp035_phase_0"]}
    _require(fp_codes == FP035_DEPENDENT_CODES, "FP-035 phase-0 row set differs")
    _require(candidate["approval_phases"][0]["phase"] == 0, "phase 0 is missing")
    phased_codes = [
        code
        for phase in candidate["approval_phases"][1:]
        for code in phase["artifact_codes"]
    ]
    _require(len(phased_codes) == len(set(phased_codes)) == 129, "approval phases do not uniquely cover 129 candidates")
    _require(candidate["atomic_transition_boundary"]["candidate_generation_changes_live_state"] is False, "generation changes live state")
    _require(candidate["authority_boundary"]["remaining_gates_are_waived"] is False, "candidate waives gates")
    _require(candidate["authority_boundary"]["release_status"] == "NOT_ELIGIBLE", "release boundary differs")
    _require({gate["id"] for gate in candidate["remaining_gates"]} == EXPECTED_GATE_IDS, "candidate gate set differs")
    _require(all(gate["status"] == "NOT_RUN" for gate in candidate["remaining_gates"]), "candidate gate status differs")
    statement = candidate["required_owner_approval_statement"]
    source = {
        "previous_file_sha256": candidate["predecessor_candidate"]["file_sha256"],
        "fp035_file_sha256": candidate["fp035_phase_0"]["file_sha256"],
    }
    _require(statement == _required_approval_statement(candidate["approval_target"], source), "approval statement differs")
    _require("\n" not in statement and statement.endswith("."), "approval statement is not one copyable sentence")
    _require(statement.count("승인합니다.") == 1, "approval statement does not have one terminal approval clause")
    _require(
        candidate["required_owner_approval_statement_sha256"]
        == hashlib.sha256(statement.encode("utf-8")).hexdigest(),
        "approval statement hash differs",
    )
    if verify_files:
        for binding in candidate["source_bindings"]:
            path = REPO_ROOT / binding["path"]
            _require(path.is_file(), f"candidate source is missing: {binding['path']}")
            _require(binding["sha256"] == _file_sha256(path), f"candidate source is stale: {binding['path']}")
        for item in candidate["approval_file_inventory"]:
            path = REPO_ROOT / item["path"]
            _require(path.is_file(), f"approval file is missing: {item['path']}")
            _require(item["file_sha256"] == _file_sha256(path), f"approval file is stale: {item['path']}")


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def _render_markdown(candidate: dict[str, Any], candidate_file_sha256: str) -> str:
    summary = candidate["classification_summary"]
    lines = [
        "# WalkSafe 2026-07-22 산출물 기준선 승인 후보",
        "",
        "> 이 문서를 만들거나 읽는 것은 승인이 아닙니다. 아래 정확한 한 문장을 사용자가 별도로 승인하기 전에는 어떤 상태도 바뀌지 않습니다.",
        "",
        "## 한눈에 보기",
        "",
        f"- 버전형 내용 기준선 후보: **{summary['versioned_content_baseline_candidate_count']}개**",
        f"- 계속 갱신형 Active 최초본 후보: **{summary['active_opening_snapshot_candidate_count']}개**",
        f"- 이번 승인 후보 합계: **{summary['approval_candidate_count']}개**",
        f"- 외부값·실행근거 대기: **{summary['evidence_or_external_pending_count']}개**",
        f"- Planned/NOT_RUN 유지: **{summary['planned_not_run_count']}개**",
        f"- 이번에 승인하지 않는 합계: **{summary['not_approved_count']}개**",
        "- 남은 gate: **5개, 전부 NOT_RUN·미면제**",
        "- 출시 상태: **NOT_ELIGIBLE**",
        "",
        "## 이전 후보와 FP-035",
        "",
        f"- `{PREVIOUS_CANDIDATE_ID}`는 승인·적용되지 않았습니다. 원본을 보존하고 후보 선택 관계에서만 이 후보가 뒤를 잇습니다.",
        f"- `{FP035_CANDIDATE_ID}`는 현재 NOT_APPROVED/NOT_EFFECTIVE입니다. 아래 승인문이 승인될 때 0단계에서 정확한 정정 오버레이와 정책 1.0.1을 먼저 논리적으로 성립시킵니다.",
        "- FP-035 직접 의존 산출물은 REQ-03, REQ-06, DES-04, DES-13, DES-20 다섯 개입니다.",
        "",
        "## 원자적 처리 경계",
        "",
        "모든 경로와 SHA-256을 한 번 검증해 불변 snapshot으로 고른 뒤 각 단계는 live 파일을 수정하지 않고 논리적으로만 준비합니다. 한 단계라도 실패하면 준비 사건 전부를 버립니다. 전 단계가 성공해야 별도 반영 절차가 정책·129개 상태를 한 번 전환하고 DOC-01·DOC-05를 한 번만 갱신할 수 있습니다.",
        "",
        "## 정확한 승인문",
        "",
        candidate["required_owner_approval_statement"],
        "",
        "## 핵심 지문",
        "",
        f"- 승인대상: `{candidate['approval_target_sha256']}`",
        f"- 후보 JSON 파일: `{candidate_file_sha256}`",
        f"- 분류: `{candidate['classification_binding_sha256']}`",
        f"- 파일집합: `{candidate['file_set_binding_sha256']}`",
        f"- 단계순서: `{candidate['phase_order_binding_sha256']}`",
        "",
        "## 단계 요약",
        "",
        "| 단계 | 의미 | 대상 수 | live 전환 |",
        "|---:|---|---:|---|",
    ]
    for phase in candidate["approval_phases"]:
        if phase["phase"] == 0:
            meaning = "FP-035 정정 + 정책 1.0.1"
            count = 1
        else:
            meaning = "의존성 순서에 따른 산출물 논리 승인"
            count = len(phase["artifact_codes"])
        lines.append(f"| {phase['phase']} | {meaning} | {count} | 허용 안 함 |")
    lines.extend(
        [
            "",
            "## 257개 전수 분류",
            "",
            "| ID | 산출물 | 분류 | 현재 → 승인 후/유지 | 정본 | 복합단위 SHA-256 |",
            "|---|---|---|---|---|---|",
        ]
    )
    for row in candidate["artifact_dispositions"]:
        target = row["target_state_after_exact_owner_approval"]
        digest = row["compound_approval_unit"]["compound_sha256"] if row["compound_approval_unit"] else "승인 대상 아님"
        lines.append(
            f"| {row['display_code']} | {row['title']} | {row['track']} | "
            f"{row['current_state']['lifecycle_status']} → {target['lifecycle_status']} | "
            f"`{row['canonical_or_planned_path']}` | `{digest}` |"
        )
    lines.append("")
    return "\n".join(lines)


def _render_html(candidate: dict[str, Any], candidate_file_sha256: str) -> str:
    summary = candidate["classification_summary"]
    labels = {
        TRACK_VERSIONED: "버전형 기준선 후보",
        TRACK_ACTIVE: "Active 최초본 후보",
        TRACK_EVIDENCE: "외부값·실행근거 대기",
        TRACK_PLANNED: "Planned/NOT_RUN",
    }
    cards = "".join(
        f'<article class="card"><strong>{summary[key]}</strong><span>{escape(labels[key])}</span></article>'
        for key in TRACK_ORDER
    )
    options = "".join(
        f'<option value="{key}">{escape(labels[key])} · {summary[key]}개</option>'
        for key in TRACK_ORDER
    )
    phase_rows = []
    for phase in candidate["approval_phases"]:
        if phase["phase"] == 0:
            targets = "FP-035 정정 후보 → 정책 1.0.1"
            predecessor = "승인 전 고정 source 검증"
        else:
            targets = ", ".join(phase["artifact_codes"])
            predecessor = ", ".join(phase["required_predecessor_candidate_codes"]) or "후보 내부 선행 없음"
        phase_rows.append(
            f"<tr><td>{phase['phase']}</td><td>{escape(targets)}</td><td>{escape(predecessor)}</td><td>없음 — 논리 준비만</td></tr>"
        )
    rows: list[str] = []
    for row in candidate["artifact_dispositions"]:
        target = row["target_state_after_exact_owner_approval"]
        compound = row["compound_approval_unit"]
        digest = compound["compound_sha256"] if compound else "승인 대상 아님"
        detail = ""
        if compound:
            files = "".join(
                f"<li><code>{escape(item['path'])}</code><br><small>버전 {escape(item['version_label'])}</small><br><code>{item['file_sha256']}</code></li>"
                for item in row["related_file_bindings"]
            )
            detail = f"<details><summary>복합 승인단위와 파일 지문</summary><p><code>{digest}</code></p><ul>{files}</ul></details>"
        search = " ".join([row["display_code"], row["title"], row["category"], row["readiness_reason"]]).lower()
        rows.append(
            f'<tr data-category="{row["category"]}" data-track="{row["track"]}" data-search="{escape(search)}">'
            f'<td><strong>{row["display_code"]}</strong><br><small>{escape(row["category"])}</small></td>'
            f'<td>{escape(row["title"])}<details><summary>왜 이 분류인가</summary><p>{escape(row["readiness_reason"])}</p></details></td>'
            f'<td><span class="pill {row["track"]}">{escape(labels[row["track"]])}</span></td>'
            f'<td>{escape(row["current_state"]["lifecycle_status"])} → {escape(target["lifecycle_status"])}</td>'
            f'<td><code>{escape(row["canonical_or_planned_path"])}</code>{detail}</td></tr>'
        )
    data_json = json.dumps(candidate, ensure_ascii=False).replace("</", "<\\/")
    return f"""<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="icon" href="data:,">
  <title>WalkSafe 2026-07-22 산출물 승인 후보</title>
  <style>
    :root{{--ink:#172033;--muted:#5b6474;--line:#d8dfeb;--bg:#f4f7fb;--card:#fff;--brand:#2459cf;--danger:#a32929}}
    *{{box-sizing:border-box}}html{{scroll-behavior:smooth}}body{{margin:0;background:var(--bg);color:var(--ink);font:16px/1.55 system-ui,-apple-system,"Noto Sans KR",sans-serif}}
    main{{max-width:1500px;margin:auto;padding:1.1rem}}nav{{display:flex;gap:.8rem;flex-wrap:wrap;padding:.8rem;background:#e9effb;border-radius:.7rem}}a{{color:var(--brand)}}h1{{font-size:clamp(1.5rem,3vw,2.3rem)}}.notice,.panel{{background:var(--card);border:1px solid var(--line);border-radius:.75rem;padding:1rem;margin:1rem 0}}.notice strong{{color:var(--danger)}}
    .cards{{display:grid;grid-template-columns:repeat(4,1fr);gap:.7rem}}.card{{background:#fff;border:1px solid var(--line);padding:.9rem;border-radius:.7rem}}.card strong{{display:block;font-size:1.7rem}}.card span,small{{color:var(--muted)}}
    .filters{{display:grid;grid-template-columns:2fr 1fr 1fr;gap:.7rem;position:sticky;top:0;background:#fff;border:1px solid var(--line);padding:.8rem;border-radius:.7rem;z-index:3}}label{{font-weight:700}}input,select,textarea,button{{font:inherit}}input,select{{width:100%;min-height:2.7rem;margin-top:.25rem;padding:.45rem;border:1px solid #aab5c5;border-radius:.4rem;background:#fff}}
    textarea{{width:100%;min-height:15rem;padding:.8rem;border:1px solid #9faec2;border-radius:.5rem;background:#fbfcff;resize:vertical}}button{{min-height:2.7rem;padding:.55rem 1rem;border:0;border-radius:.45rem;background:var(--brand);color:#fff;cursor:pointer}}.status{{margin-left:.6rem;color:var(--muted)}}
    .table-wrap{{overflow:auto;background:#fff;border:1px solid var(--line);border-radius:.7rem}}table{{width:100%;border-collapse:collapse;min-width:1050px}}th,td{{text-align:left;vertical-align:top;padding:.65rem;border-bottom:1px solid var(--line)}}th{{background:#edf2fb;position:sticky;top:5.4rem;z-index:1}}code{{font-size:.75rem;word-break:break-all}}.pill{{display:inline-block;padding:.2rem .5rem;border-radius:999px;white-space:nowrap;background:#e9edf4}}.VERSIONED_CONTENT_BASELINE_CANDIDATE{{background:#d9f3e2}}.ACTIVE_OPENING_SNAPSHOT_CANDIDATE{{background:#dceaff}}.EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED{{background:#fff0c9}}.PLANNED_NOT_RUN_NOT_APPROVED{{background:#ececec}}
    @media(max-width:900px){{.cards{{grid-template-columns:repeat(2,1fr)}}.filters{{grid-template-columns:1fr;position:static}}th{{top:0}}}}
  </style>
</head>
<body><main>
  <h1 id="top">WalkSafe 257개 산출물 승인 후보</h1>
  <p>정책 질문을 다시 받는 화면이 아니라, 이미 확정된 정책과 작성된 문서 가운데 지금 한 번에 승인할 수 있는 내용과 아직 승인하면 안 되는 증거를 분리한 검토서입니다.</p>
  <nav aria-label="문서 안 이동"><a href="#summary">요약</a><a href="#sequence">처리 순서</a><a href="#approval">승인문</a><a href="#artifacts">257개 목록</a></nav>
  <section class="notice"><strong>아직 승인되지 않았습니다.</strong> 이 파일 생성, 검색, 선택, 복사는 승인이나 상태 전환을 일으키지 않습니다. 입력 요소는 form 안에 있지 않으며 선택해도 페이지가 이동하거나 제출되지 않습니다.</section>
  <section id="summary" class="panel"><h2>한눈에 보기</h2><div class="cards">{cards}</div><p><b>승인 후보:</b> 102 + 27 = 129개 · <b>승인 안 함:</b> 53 + 75 = 128개 · <b>출시:</b> NOT_ELIGIBLE</p></section>
  <section class="panel"><h2>이전 후보와 FP-035</h2><p>2026-07-21 후보는 승인되거나 적용되지 않았습니다. 파일을 그대로 보존하며 새 후보가 <em>후보 선택 관계에서만</em> 뒤를 잇습니다.</p><p>FP-035 정정 후보도 지금은 NOT_APPROVED/NOT_EFFECTIVE입니다. 아래 정확한 승인문이 승인되면 0단계에서 정책 1.0.1을 먼저 논리적으로 성립시키고, 그 뒤 관련 문서를 순서대로 처리합니다.</p></section>
  <section id="sequence" class="panel"><h2>왜 단계가 필요한가</h2><p>앞 문서를 기준으로 뒤 문서를 승인해야 하기 때문입니다. 하지만 단계 중에는 실제 파일 상태를 바꾸지 않습니다. 하나라도 실패하면 준비한 사건을 전부 버리고 원래 상태를 유지합니다.</p><div class="table-wrap"><table><thead><tr><th>단계</th><th>논리 대상</th><th>먼저 끝나야 하는 후보</th><th>단계 중 live 전환</th></tr></thead><tbody>{''.join(phase_rows)}</tbody></table></div></section>
  <section id="approval" class="panel"><h2>한 번만 사용할 정확한 승인문</h2><p>아래 문장은 현재 path와 SHA-256에만 유효합니다. source가 하나라도 바뀌면 후보를 다시 생성해야 합니다.</p><label for="approvalText">채팅에 그대로 보낼 한 문장</label><textarea id="approvalText" readonly>{escape(candidate['required_owner_approval_statement'])}</textarea><button id="copyApproval" type="button">승인문 복사</button><span id="copyStatus" class="status" role="status" aria-live="polite"></span><p><b>승인대상:</b> <code>{candidate['approval_target_sha256']}</code><br><b>후보 JSON 파일:</b> <code>{candidate_file_sha256}</code><br><b>파일집합:</b> <code>{candidate['file_set_binding_sha256']}</code><br><b>분류:</b> <code>{candidate['classification_binding_sha256']}</code></p></section>
  <section id="artifacts"><h2>257개 전수 분류</h2><p>복합 승인단위는 해당 문서 구간과 그 ID에 연결된 구조화 파일을 함께 결속합니다. 파일 전체 SHA와 선언 버전을 행 안에서 확인할 수 있습니다.</p>
    <div class="filters"><label>검색<input id="search" type="search" autocomplete="off" placeholder="예: 객체 탐지, REQ-03, 운영"></label><label>범주<select id="category"><option value="">전체 범주</option>{''.join(f'<option value="{v}">{v}</option>' for v in ['DOC','MGT','DSC','REQ','DES','DEV','TST','SEC','AIML','REL','OPS','WS','CLS'])}</select></label><label>분류<select id="track"><option value="">전체 분류</option>{options}</select></label></div>
    <p id="resultCount" role="status" aria-live="polite">257개 표시 중</p><div class="table-wrap"><table><thead><tr><th>ID</th><th>산출물·근거</th><th>분류</th><th>현재 → 이후</th><th>파일·SHA</th></tr></thead><tbody id="rows">{''.join(rows)}</tbody></table></div>
  </section><p><a href="#top">맨 위로</a></p>
</main>
<script id="candidateData" type="application/json">{data_json}</script>
<script>
(() => {{
  const search=document.getElementById('search'); const category=document.getElementById('category'); const track=document.getElementById('track'); const rows=[...document.querySelectorAll('#rows tr')]; const result=document.getElementById('resultCount');
  function filterRows(){{const q=search.value.trim().toLowerCase();let n=0;for(const row of rows){{const show=(!q||row.dataset.search.includes(q))&&(!category.value||row.dataset.category===category.value)&&(!track.value||row.dataset.track===track.value);row.hidden=!show;if(show)n++;}}result.textContent=`${{n}}개 표시 중`;}}
  search.addEventListener('input',filterRows); category.addEventListener('change',filterRows); track.addEventListener('change',filterRows);
  document.getElementById('copyApproval').addEventListener('click',async()=>{{const field=document.getElementById('approvalText');try{{await navigator.clipboard.writeText(field.value);document.getElementById('copyStatus').textContent='복사했습니다.';}}catch(_){{field.focus();field.select();const ok=document.execCommand('copy');document.getElementById('copyStatus').textContent=ok?'복사했습니다.':'자동 복사에 실패했습니다. 선택된 문장을 직접 복사해 주세요.';}}}});
}})();
</script></body></html>
"""


def build_outputs() -> dict[Path, bytes]:
    candidate = build_candidate()
    candidate_bytes = _json_bytes(candidate)
    candidate_file_sha256 = hashlib.sha256(candidate_bytes).hexdigest()
    return {
        CANDIDATE_PATH: candidate_bytes,
        REVIEW_MD_PATH: _render_markdown(candidate, candidate_file_sha256).encode("utf-8"),
        REVIEW_HTML_PATH: _render_html(candidate, candidate_file_sha256).encode("utf-8"),
    }


def _write_or_check(outputs: dict[Path, bytes], *, check: bool) -> None:
    stale: list[str] = []
    for path, content in outputs.items():
        if check:
            if not path.is_file() or path.read_bytes() != content:
                stale.append(_relative(path))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    if stale:
        raise CandidateError("stale or missing generated outputs: " + ", ".join(stale))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify generated outputs without writing")
    parser.add_argument("--preflight", action="store_true", help="validate final inputs without writing outputs")
    args = parser.parse_args()
    try:
        if args.preflight:
            result = preflight()
            print(f"READY {result['candidate_id']}; independent_review={result['independent_review_id']}")
            return 0
        outputs = build_outputs()
        _write_or_check(outputs, check=args.check)
        candidate = json.loads(outputs[CANDIDATE_PATH])
        action = "verified" if args.check else "generated"
        print(
            f"{action} {CANDIDATE_ID}; versioned=102, active=27, candidates=129, "
            "evidence_pending=53, planned_not_run=75, not_approved=128, "
            "approval=NOT_APPROVED, release=NOT_ELIGIBLE"
        )
        return 0
    except InputNotReadyError as exc:
        print(f"WAITING: {exc}", file=sys.stderr)
        return 2
    except (CandidateError, json.JSONDecodeError, UnicodeDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
