#!/usr/bin/env python3
"""Validate the complete WalkSafe DOC~TST Draft/Planned deliverable set.

This validator checks structure, readiness boundaries, and traceability. It
never promotes a Draft, closes a verification gate, or treats planned evidence
as an executed result.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
from typing import Any

try:
    from scripts import build_walksafe_effective_decision_register_alignment_20260721 as alignment_builder
    from scripts import build_walksafe_feature_policy_baseline_approval_20260721 as approval_builder
except ModuleNotFoundError:  # Direct execution from scripts/.
    import build_walksafe_effective_decision_register_alignment_20260721 as alignment_builder
    import build_walksafe_feature_policy_baseline_approval_20260721 as approval_builder


REPO_ROOT = Path(__file__).resolve().parents[1]
DELIVERABLE_ROOT = REPO_ROOT / "docs" / "deliverables"
CONTROL_ROOT = REPO_ROOT / "docs" / "control"

POLICY_PATH = (
    CONTROL_ROOT
    / "decision-interview"
    / "walksafe-feature-policy-comprehensive-draft.json"
)
POLICY_MANIFEST_PATH = (
    CONTROL_ROOT
    / "baselines"
    / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
)
POLICY_APPROVAL_PATH = (
    CONTROL_ROOT
    / "baselines"
    / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
)
ALIGNED_DECISIONS_PATH = (
    CONTROL_ROOT
    / "decision-interview"
    / "walksafe-effective-decision-register-aligned-20260721-r001.json"
)
CURRENT_DECISIONS_PATH = (
    CONTROL_ROOT
    / "decision-interview"
    / "walksafe-effective-decision-register-current-20260726-r001.json"
)
POLICY_1_0_1_MANIFEST_PATH = (
    CONTROL_ROOT
    / "baselines"
    / "walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json"
)
FP035_CORRECTION_CANDIDATE_PATH = (
    CONTROL_ROOT
    / "decision-interview"
    / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"
)
ARTIFACT_APPROVAL_PATH = (
    CONTROL_ROOT
    / "baselines"
    / "walksafe-artifact-baseline-approval-20260722-r001.json"
)
APPLICATION_RECEIPT_PATH = (
    CONTROL_ROOT
    / "baselines"
    / "walksafe-artifact-baseline-application-receipt-20260722-r001.json"
)
TEMPORAL_SUPPLEMENT_PATH = (
    CONTROL_ROOT
    / "baselines"
    / "walksafe-artifact-baseline-application-temporal-provenance-supplement-20260722-r001.json"
)
ARTIFACT_REGISTER_PATH = DELIVERABLE_ROOT / "00-control" / "artifact-register.json"
RTM_PATH = DELIVERABLE_ROOT / "03-requirements" / "rtm.json"
DESIGN_TRACE_PATH = DELIVERABLE_ROOT / "04-design" / "design-traceability-register.json"
TEST_CASES_PATH = DELIVERABLE_ROOT / "06-testing" / "registers" / "test-cases.json"
MODULE_REGISTER_PATH = DELIVERABLE_ROOT / "05-implementation" / "module-register.json"
CHANGE_REQUEST_REGISTER_PATH = DELIVERABLE_ROOT / "01-management" / "registers" / "change-requests.json"
TEST_METRICS_PATH = DELIVERABLE_ROOT / "06-testing" / "registers" / "metrics.json"
SOFTWARE_TEST_REPORT_PATH = DELIVERABLE_ROOT / "06-testing" / "software-test-report.md"
TRACE_INTEGRATION_REPORT_PATH = (
    DELIVERABLE_ROOT
    / "traceability"
    / "req-des-tst-integration-report-20260721-r001.json"
)
ROOT_README_PATH = DELIVERABLE_ROOT / "README.md"

FP035_NETWORK_ISSUE_ID = "ISS-POLICY-FP035-NETWORK-001"
FP035_CORRECTION_CANDIDATE_ID = "WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001"
FP035_REQUIRED_ACTIVATION_EVENT = "EXACT_NEW_BUNDLED_OWNER_APPROVAL_STATEMENT"
FP035_APPROVAL_BLOCKERS = [FP035_CORRECTION_CANDIDATE_ID, FP035_REQUIRED_ACTIVATION_EVENT]
FP035_DIRECT_DESIGN_IDS = {"DES-04", "DES-13", "DES-20"}
FP035_RELATED_DESIGN_IDS = {"DES-09"}
EXPECTED_TRACE_REPORT_ID = "WS-REQ-DES-TST-TRACE-INTEGRATION-20260721-001"
REQUIREMENT_DOCUMENT_PATH = "docs/deliverables/03-requirements/system-requirements.md"

EXPECTED_CATEGORY_COUNTS = {
    "DOC": 5,
    "MGT": 18,
    "DSC": 15,
    "REQ": 19,
    "DES": 27,
    "DEV": 21,
    "TST": 23,
}
EXPECTED_GATE_IDS = {
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
}
EXPECTED_CONDITIONAL_CODES = {
    "MGT-08",
    "DSC-03",
    "DSC-04",
    "DSC-05",
    "DEV-10",
    "DEV-11",
    "DEV-13",
    "DEV-15",
    "TST-10",
    "TST-12",
    "TST-13",
    "TST-15",
    "TST-16",
    "TST-17",
}
EXPECTED_MANIFEST_NAMES = {
    "management-discovery-draft-20260721-r001.json",
    "requirements-draft-20260721-r001.json",
    "design-draft-20260721-r001.json",
    "dev-test-draft-20260721-r001.json",
}
EXPECTED_TRACE_REPORT_BINDING_PATHS = {
    "generator": "scripts/build_walksafe_trace_integration_report_20260721.py",
    "fp035_correction_candidate": "docs/control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json",
    "policy_payload": "docs/control/decision-interview/walksafe-feature-policy-comprehensive-draft.json",
    "policy_approval_record": "docs/control/baselines/walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json",
    "policy_baseline_manifest": "docs/control/baselines/walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json",
    "requirements_rtm": "docs/deliverables/03-requirements/rtm.json",
    "requirements_manifest": "docs/deliverables/manifests/requirements-draft-20260721-r001.json",
    "requirements_document": "docs/deliverables/03-requirements/system-requirements.md",
    "design_traceability_register": "docs/deliverables/04-design/design-traceability-register.json",
    "design_manifest": "docs/deliverables/manifests/design-draft-20260721-r001.json",
    "design_architecture_document": "docs/deliverables/04-design/software-architecture.md",
    "design_interface_data_document": "docs/deliverables/04-design/interface-and-data-design.md",
    "design_user_experience_document": "docs/deliverables/04-design/user-experience-and-accessibility-design.md",
    "design_security_operations_document": "docs/deliverables/04-design/security-and-operations-design.md",
    "test_cases": "docs/deliverables/06-testing/registers/test-cases.json",
    "module_register": "docs/deliverables/05-implementation/module-register.json",
    "dev_test_manifest": "docs/deliverables/manifests/dev-test-draft-20260721-r001.json",
}
EXPECTED_CANONICAL_PATHS = {
    "docs/deliverables/00-control/document-control-manual.md",
    "docs/deliverables/00-control/artifact-register.json",
    "docs/deliverables/00-control/artifact-change-log.json",
    "docs/deliverables/01-management/project-charter.md",
    "docs/deliverables/01-management/project-management-plan.md",
    "docs/deliverables/01-management/project-control-registers.md",
    "docs/deliverables/02-discovery/discovery-evidence-and-analysis.md",
    "docs/deliverables/02-discovery/product-definition.md",
    "docs/deliverables/02-discovery/product-registers.md",
    "docs/deliverables/03-requirements/system-requirements.md",
    "docs/deliverables/03-requirements/acceptance-specification.md",
    "docs/deliverables/03-requirements/requirements-traceability.md",
    "docs/deliverables/04-design/software-architecture.md",
    "docs/deliverables/04-design/interface-and-data-design.md",
    "docs/deliverables/04-design/user-experience-and-accessibility-design.md",
    "docs/deliverables/04-design/security-and-operations-design.md",
    "docs/deliverables/05-implementation/developer-guide.md",
    "docs/deliverables/05-implementation/implementation-configuration.md",
    "docs/deliverables/05-implementation/implementation-quality-record.md",
    "docs/deliverables/06-testing/test-plan.md",
    "docs/deliverables/06-testing/test-quality-report.md",
}


class FormalDeliverableValidationError(ValueError):
    """Raised when a formal Draft boundary or trace contract differs."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise FormalDeliverableValidationError(message)


def _object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        _require(key not in value, f"duplicate JSON key: {key}")
        value[key] = item
    return value


def _reject_constant(value: str) -> None:
    raise FormalDeliverableValidationError(f"non-standard JSON number: {value}")


def _load_json(path: Path) -> dict[str, Any]:
    _require(path.is_file(), f"missing JSON file: {_rel(path)}")
    raw = path.read_bytes()
    _require(not raw.startswith(b"\xef\xbb\xbf"), f"UTF-8 BOM is not allowed: {_rel(path)}")
    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_object_pairs,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise FormalDeliverableValidationError(f"invalid JSON: {_rel(path)}: {exc}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {_rel(path)}")
    return value


def _rel(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _object_sha256(value: Any) -> str:
    data = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def _validate_binding(binding: dict[str, Any], label: str) -> None:
    _require(isinstance(binding, dict), f"invalid source binding: {label}")
    raw_path = binding.get("path")
    _require(isinstance(raw_path, str) and raw_path, f"source binding path is missing: {label}")
    relative = Path(raw_path)
    _require(not relative.is_absolute() and ".." not in relative.parts, f"source path escapes repository: {label}")
    path = REPO_ROOT / relative
    _require(path.is_file(), f"bound source is missing: {raw_path}")
    _require(_sha256(path) == binding.get("sha256"), f"bound source hash differs: {raw_path}")
    if "byte_length" in binding:
        _require(path.stat().st_size == binding["byte_length"], f"bound source size differs: {raw_path}")


def _validate_object_digest(document: dict[str, Any], key: str, label: str) -> None:
    digest = document.get(key)
    _require(isinstance(digest, str), f"content digest is missing: {label}")
    without_digest = dict(document)
    without_digest.pop(key)
    _require(_object_sha256(without_digest) == digest, f"content digest differs: {label}")


def _expected_requirement_link(requirement_id: str) -> dict[str, str]:
    return {
        "requirement_id": requirement_id,
        "path": REQUIREMENT_DOCUMENT_PATH,
        "anchor": requirement_id,
        "target": f"{REQUIREMENT_DOCUMENT_PATH}#{requirement_id}",
        "trace_status": "DRAFT_NOT_APPROVED",
    }


def _expected_design_link(
    design_id: str,
    design_paths: dict[str, str],
    *,
    trace_status: str = "DRAFT_NOT_APPROVED",
) -> dict[str, str]:
    _require(design_id in design_paths, f"unknown design ID in declared link: {design_id}")
    path = design_paths[design_id]
    anchor = design_id.lower()
    return {
        "design_id": design_id,
        "path": path,
        "anchor": anchor,
        "target": f"{path}#{anchor}",
        "trace_status": trace_status,
    }


def _validate_gates(gates: Any, label: str) -> None:
    _require(isinstance(gates, list) and len(gates) == 5, f"five gates are required: {label}")
    _require({item.get("id") for item in gates} == EXPECTED_GATE_IDS, f"gate IDs differ: {label}")
    for gate in gates:
        _require(gate.get("status") == "NOT_RUN", f"gate is not NOT_RUN: {label}/{gate.get('id')}")
        if "waived" in gate:
            _require(gate["waived"] is False, f"gate was waived: {label}/{gate.get('id')}")


def _validate_draft_manifest(path: Path) -> dict[str, Any]:
    manifest = _load_json(path)
    metadata = manifest.get("metadata")
    _require(isinstance(metadata, dict), f"manifest metadata is missing: {_rel(path)}")
    _require(metadata.get("lifecycle_status") in {"DRAFT", "IN_REVIEW"}, f"unsafe manifest lifecycle: {_rel(path)}")
    _require(metadata.get("approval_status") == "NOT_APPROVED", f"manifest claims approval: {_rel(path)}")
    _require(metadata.get("release_status") == "NOT_ELIGIBLE", f"manifest release differs: {_rel(path)}")

    boundary = manifest.get("authorization_boundary", {})
    _require(isinstance(boundary, dict), f"manifest boundary is missing: {_rel(path)}")
    _require(boundary.get("formal_deliverables_approved") is not True, f"formal approval claimed: {_rel(path)}")
    _require(boundary.get("implementation_completion_claimed") is not True, f"implementation completion claimed: {_rel(path)}")
    _require(boundary.get("test_completion_claimed") is not True, f"test completion claimed: {_rel(path)}")
    _require(boundary.get("remaining_gates_waived") is False, f"gate boundary differs: {_rel(path)}")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", f"release boundary differs: {_rel(path)}")
    _validate_gates(manifest.get("remaining_gates"), _rel(path))

    bindings = manifest.get("source_bindings")
    _require(isinstance(bindings, dict) and bindings, f"manifest source bindings are missing: {_rel(path)}")
    for name, binding in bindings.items():
        _validate_binding(binding, f"{_rel(path)}#{name}")

    generated = manifest.get("generated_files")
    _require(isinstance(generated, list) and generated, f"manifest generated files are missing: {_rel(path)}")
    seen_paths: set[str] = set()
    for entry in generated:
        _require(isinstance(entry, dict), f"invalid generated file entry: {_rel(path)}")
        raw_path = entry.get("path")
        _require(isinstance(raw_path, str) and raw_path.startswith("docs/deliverables/"), f"generated path is out of scope: {raw_path}")
        _require(raw_path not in seen_paths, f"duplicate generated path in manifest: {raw_path}")
        seen_paths.add(raw_path)
        generated_path = REPO_ROOT / raw_path
        _require(generated_path.is_file(), f"generated file is missing: {raw_path}")
        _require(_sha256(generated_path) == entry.get("sha256"), f"generated hash differs: {raw_path}")
        if "byte_length" in entry:
            _require(generated_path.stat().st_size == entry["byte_length"], f"generated size differs: {raw_path}")

    content_digest = manifest.get("manifest_content_sha256")
    _require(isinstance(content_digest, str), f"manifest content digest is missing: {_rel(path)}")
    without_digest = dict(manifest)
    without_digest.pop("manifest_content_sha256")
    _require(_object_sha256(without_digest) == content_digest, f"manifest content digest differs: {_rel(path)}")
    return manifest


def _validate_artifact_register() -> dict[str, Any]:
    register = _load_json(ARTIFACT_REGISTER_PATH)
    summary = register.get("summary", {})
    _require(summary.get("artifact_type_count") == 257, "master artifact count must be 257")
    scope_summary = register.get("scope_summaries", {}).get("formal_0_to_6", {})
    _require(scope_summary.get("artifact_type_count") == 128, "0~6 artifact count must be 128")
    _require(scope_summary.get("bundle_count") == 21, "0~6 bundle count must be 21")
    _require(summary.get("approved_artifact_count") == 0, "formal Drafts must not be approved")
    _require(scope_summary.get("materialized_artifact_count") == 105, "0~6 materialized type count differs")
    _require(
        scope_summary.get("lifecycle_status_counts") == {"DRAFT": 105, "PLANNED": 23},
        "0~6 lifecycle counts differ",
    )
    _require(
        {key: summary.get("category_counts", {}).get(key) for key in EXPECTED_CATEGORY_COUNTS}
        == EXPECTED_CATEGORY_COUNTS,
        "0~6 category counts differ",
    )

    all_artifacts = register.get("artifacts")
    _require(isinstance(all_artifacts, list) and len(all_artifacts) == 257, "master artifact register rows differ")
    artifacts = [
        item for item in all_artifacts if item.get("category") in EXPECTED_CATEGORY_COUNTS
    ]
    _require(len(artifacts) == 128, "0~6 artifact register rows differ")
    codes = [item.get("display_code") for item in artifacts]
    _require(len(set(codes)) == 128, "artifact display codes must be unique")
    conditional_codes = {item["display_code"] for item in artifacts if item.get("applicability") == "CONDITIONAL"}
    _require(conditional_codes == EXPECTED_CONDITIONAL_CODES, "conditional artifact set differs")

    canonical_paths: set[str] = set()
    for item in artifacts:
        code = item["display_code"]
        state = item.get("state", {})
        lifecycle = state.get("lifecycle_status")
        _require(lifecycle in {"DRAFT", "PLANNED"}, f"unexpected artifact lifecycle: {code}")
        version = item.get("version", {})
        _require(version.get("baseline_id") is None, f"unapproved artifact claims a baseline: {code}")
        dates = item.get("dates", {})
        _require(dates.get("approved_at") is None, f"unapproved artifact has an approval date: {code}")
        change = item.get("change_control", {})
        _require(change.get("waiver_id") is None, f"artifact has an unapproved waiver: {code}")
        if item.get("applicability") == "CONDITIONAL":
            _require(item.get("activation_result") == "PENDING_EVALUATION", f"conditional result differs: {code}")
            _require(item.get("n_a_reason") is None, f"conditional N/A reason is premature: {code}")

        location = item.get("location", {})
        raw_path = location.get("canonical_path")
        if lifecycle == "PLANNED":
            _require(state.get("verification_status") == "NOT_RUN", f"Planned evidence claims verification: {code}")
            _require(version.get("document_version") is None, f"Planned evidence claims a version: {code}")
            _require(raw_path is None, f"Planned evidence claims a current canonical file: {code}")
            _require(item.get("integrity", {}).get("sha256") is None, f"Planned evidence claims a current hash: {code}")
            _require(
                isinstance(location.get("planned_canonical_path"), str)
                and bool(location["planned_canonical_path"]),
                f"Planned evidence has no intended location: {code}",
            )
            continue
        _require(isinstance(raw_path, str) and raw_path, f"canonical path is missing: {code}")
        canonical_paths.add(raw_path)
        path = REPO_ROOT / raw_path
        _require(path.is_file(), f"canonical file is missing: {code}/{raw_path}")
        expected_hash = item.get("integrity", {}).get("sha256")
        if path != ARTIFACT_REGISTER_PATH:
            _require(expected_hash == _sha256(path), f"canonical hash differs: {code}/{raw_path}")

    _require(
        canonical_paths == EXPECTED_CANONICAL_PATHS,
        "current canonical path set differs from the 21 materialized 0~6 files",
    )
    return register


def _validate_traceability() -> dict[str, int]:
    policy = _load_json(POLICY_PATH)
    rtm = _load_json(RTM_PATH)
    test_register = _load_json(TEST_CASES_PATH)
    aligned = _load_json(ALIGNED_DECISIONS_PATH)

    source_ids = {
        *[item["id"] for item in policy.get("features", [])],
        *[item["id"] for item in policy.get("common_policies", [])],
        *[item["id"] for item in policy.get("remaining_gates", [])],
    }
    _require(len(source_ids) == 68, "policy source requirement count must be 68")

    requirements = rtm.get("requirements")
    _require(isinstance(requirements, list) and len(requirements) == 68, "RTM requirement count differs")
    requirement_ids = {item.get("requirement_id") for item in requirements}
    _require(len(requirement_ids) == 68, "RTM requirement IDs must be unique")
    _require({item.get("source_policy_id") for item in requirements} == source_ids, "RTM source coverage differs")
    issues = rtm.get("bound_policy_correction_candidates")
    _require(
        isinstance(issues, list)
        and len(issues) == 1
        and issues[0].get("issue_id") == FP035_NETWORK_ISSUE_ID
        and issues[0].get("correction_candidate_id") == FP035_CORRECTION_CANDIDATE_ID
        and issues[0].get("correction_candidate_approval_status") == "NOT_APPROVED"
        and issues[0].get("correction_candidate_effective_status") == "NOT_EFFECTIVE",
        "RTM must expose the pending FP-035 correction candidate",
    )
    _require(
        rtm.get("coverage", {}).get("open_source_policy_issue_count") == 0
        and rtm.get("coverage", {}).get("not_effective_policy_correction_candidate_count") == 1,
        "RTM correction-candidate coverage differs",
    )
    for item in requirements:
        source_id = item.get("source_policy_id")
        _require(item.get("lifecycle_status") == "DRAFT", f"requirement is not Draft: {item.get('requirement_id')}")
        _require(item.get("approval_status") == "NOT_APPROVED", f"requirement claims approval: {item.get('requirement_id')}")
        _require(item.get("verification_status") == "NOT_RUN", f"requirement verification differs: {item.get('requirement_id')}")
        _require(item.get("verification_completion_claimed") is False, f"requirement claims verification: {item.get('requirement_id')}")
        design_trace = item.get("design_trace", {})
        _require(
            design_trace.get("status") == "DRAFT_DESIGN_LINKS_DECLARED",
            f"requirement design declaration differs: {item.get('requirement_id')}",
        )
        _require(
            design_trace.get("link_validation_status") == "SEE_EXTERNAL_INTEGRATION_REPORT"
            and design_trace.get("integration_report_path") == _rel(TRACE_INTEGRATION_REPORT_PATH),
            f"requirement integration-report link differs: {item.get('requirement_id')}",
        )
        _require(
            isinstance(design_trace.get("links"), list) and design_trace["links"],
            f"requirement has no declared design link: {item.get('requirement_id')}",
        )
        if source_id == "FP-035":
            _require(
                item.get("source_issue_ids") == []
                and item.get("approval_blockers") == FP035_APPROVAL_BLOCKERS
                and item.get("execution_blockers") == FP035_APPROVAL_BLOCKERS
                and item.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT
                and item.get("authoring_readiness") == "ALLOWED"
                and item.get("formal_branch_implementation_and_test_readiness")
                == "BLOCKED_PENDING_BUNDLED_APPROVAL",
                "FP-035 requirement correction dependency differs",
            )
            acceptance = item.get("acceptance_conditions", [])
            _require(
                acceptance
                and all(
                    row.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT
                    for row in acceptance
                )
                and any(
                    row.get("execution_readiness") == "BLOCKED_PENDING_BUNDLED_APPROVAL"
                    for row in acceptance
                ),
                "FP-035 acceptance conditions do not preserve the bundled-approval boundary",
            )

    acceptance_by_test: dict[str, tuple[str, str]] = {}
    decision_refs: set[str] = set()
    for requirement in requirements:
        decision_refs.update(requirement.get("decision_refs", []))
        for acceptance in requirement.get("acceptance_conditions", []):
            test_id = acceptance.get("planned_test_id")
            value = (requirement["requirement_id"], acceptance.get("acceptance_condition_id"))
            _require(isinstance(test_id, str) and test_id not in acceptance_by_test, f"duplicate planned test: {test_id}")
            acceptance_by_test[test_id] = value
    _require(len(acceptance_by_test) == 279, "acceptance condition count must be 279")

    tests = test_register.get("test_cases")
    _require(isinstance(tests, list) and len(tests) == 279, "test case count must be 279")
    tests_by_id = {item.get("test_case_id"): item for item in tests}
    _require(len(tests_by_id) == 279, "test case IDs must be unique")
    _require(set(tests_by_id) == set(acceptance_by_test), "acceptance/test ID sets differ")
    _require({item.get("source_policy_id") for item in tests} == source_ids, "test source coverage differs")
    for test_id, test in tests_by_id.items():
        expected_requirement, expected_acceptance = acceptance_by_test[test_id]
        _require(test.get("requirement_id") == expected_requirement, f"test requirement trace differs: {test_id}")
        _require(test.get("acceptance_condition_id") == expected_acceptance, f"test acceptance trace differs: {test_id}")
        _require(test.get("execution_status") == "NOT_RUN", f"test was marked executed: {test_id}")
        _require(test.get("result") is None, f"test has a result without execution: {test_id}")
        _require(test.get("evidence_ids") == [], f"test has formal evidence without execution: {test_id}")

    fp035_tests = [item for item in tests if item.get("source_policy_id") == "FP-035"]
    _require(len(fp035_tests) == 4, "FP-035 must have four blocked planned tests")
    for test in fp035_tests:
        test_id = test.get("test_case_id")
        _require(
            test.get("source_issue_ids") == [FP035_NETWORK_ISSUE_ID]
            and test.get("policy_correction_candidate_refs") == [FP035_CORRECTION_CANDIDATE_ID]
            and test.get("approval_blockers") == FP035_APPROVAL_BLOCKERS
            and test.get("execution_blockers") == FP035_APPROVAL_BLOCKERS
            and test.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT,
            f"FP-035 test blocker differs: {test_id}",
        )
        _require(
            test.get("approval_readiness") == "BLOCKED_PENDING_BUNDLED_APPROVAL"
            and test.get("execution_readiness") == "BLOCKED_PENDING_BUNDLED_APPROVAL",
            f"FP-035 test readiness differs: {test_id}",
        )

    aligned_ids = {item.get("decision_id") for item in aligned.get("decisions", [])}
    _require(len(aligned_ids) == 135, "aligned decision count must be 135")
    _require(decision_refs == aligned_ids, "RTM decision coverage differs")
    _validate_gates(rtm.get("remaining_gates"), "requirements RTM")
    return {
        "source_requirement_count": len(source_ids),
        "requirement_count": len(requirements),
        "acceptance_condition_count": len(acceptance_by_test),
        "test_case_count": len(tests),
        "aligned_decision_count": len(aligned_ids),
        "fp035_blocked_test_case_count": len(fp035_tests),
        "open_policy_issue_count": 0,
        "pending_policy_correction_candidate_count": len(issues),
    }


def _validate_design_trace() -> dict[str, int]:
    trace = _load_json(DESIGN_TRACE_PATH)
    metadata = trace.get("metadata", {})
    _require(metadata.get("lifecycle_status") == "DRAFT", "design trace is not Draft")
    _require(metadata.get("approval_status") == "NOT_APPROVED", "design trace claims approval")
    _require(metadata.get("verification_status") == "NOT_RUN", "design trace verification differs")
    _require(metadata.get("release_status") == "NOT_ELIGIBLE", "design trace release differs")
    boundary = trace.get("authorization_boundary", {})
    _require(boundary.get("design_bundle_approved") is False, "design bundle claims approval")
    _require(boundary.get("design_conformance_claimed") is False, "design conformance is overstated")
    _require(boundary.get("implementation_conformance_claimed") is False, "implementation conformance is overstated")
    _require(boundary.get("test_completion_claimed") is False, "design trace claims test completion")
    _require(boundary.get("remaining_gates_waived") is False, "design trace waives gates")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "design release boundary differs")

    records = trace.get("records")
    _require(isinstance(records, list) and len(records) == 27, "design record count must be 27")
    expected_ids = {f"DES-{number:02d}" for number in range(1, 28)}
    _require({item.get("design_id") for item in records} == expected_ids, "DES-01..27 coverage differs")
    issues = trace.get("bound_policy_correction_candidates")
    _require(
        isinstance(issues, list)
        and len(issues) == 1
        and issues[0].get("issue_id") == FP035_NETWORK_ISSUE_ID
        and issues[0].get("correction_candidate_id") == FP035_CORRECTION_CANDIDATE_ID
        and issues[0].get("correction_candidate_approval_status") == "NOT_APPROVED"
        and issues[0].get("correction_candidate_effective_status") == "NOT_EFFECTIVE",
        "design trace must expose the pending FP-035 correction candidate",
    )
    _require(
        trace.get("coverage", {}).get("open_source_policy_issue_count") == 0
        and trace.get("coverage", {}).get("not_effective_policy_correction_candidate_count") == 1,
        "design trace correction-candidate coverage differs",
    )
    policy = _load_json(POLICY_PATH)
    feature_ids = {item["id"] for item in policy["features"]}
    common_ids = {item["id"] for item in policy["common_policies"]}
    covered_features = {value for item in records for value in item.get("policy_feature_refs", [])}
    covered_common = {value for item in records for value in item.get("common_policy_refs", [])}
    covered_gates = {value for item in records for value in item.get("remaining_gate_refs", [])}
    _require(covered_features == feature_ids, "design feature coverage differs")
    _require(covered_common == common_ids, "design common-policy coverage differs")
    _require(covered_gates == EXPECTED_GATE_IDS, "design gate coverage differs")
    for item in records:
        _require(item.get("lifecycle_status") == "DRAFT", f"design record is not Draft: {item.get('design_id')}")
        _require(item.get("approval_status") == "NOT_APPROVED", f"design record claims approval: {item.get('design_id')}")
        _require(item.get("verification_status") == "NOT_RUN", f"design record verification differs: {item.get('design_id')}")
        _require(item.get("implementation_conformance_status") == "NOT_ASSESSED", f"design conformance differs: {item.get('design_id')}")
        _require(item.get("test_completion_claimed") is False, f"design record claims test completion: {item.get('design_id')}")
        design_id = item["design_id"]
        direct = design_id in FP035_DIRECT_DESIGN_IDS
        related = design_id in FP035_RELATED_DESIGN_IDS
        _require(
            item.get("source_issue_refs") == [],
            f"design source issue trace differs: {design_id}",
        )
        _require(
            item.get("approval_blockers") == (FP035_APPROVAL_BLOCKERS if direct else [])
            and item.get("execution_blockers") == (FP035_APPROVAL_BLOCKERS if direct else []),
            f"design approval blockers differ: {design_id}",
        )
        _require(
            item.get("approval_readiness")
            == ("BLOCKED_PENDING_BUNDLED_APPROVAL" if direct else "DRAFT_REVIEW_REQUIRED"),
            f"design approval readiness differs: {design_id}",
        )
        _require(
            item.get("design_decision_status")
            == ("DRAFT_POLICY_CORRECTION_CANDIDATE_NOT_EFFECTIVE" if direct else "DRAFT_NOT_APPROVED"),
            f"design decision status differs: {design_id}",
        )
        _require(
            item.get("related_dependency_status")
            == ("RELATED_DOWNSTREAM_DEPENDENCY" if related else None),
            f"design related dependency differs: {design_id}",
        )
        _require(
            item.get("related_policy_correction_candidate_refs")
            == ([FP035_CORRECTION_CANDIDATE_ID] if related else []),
            f"design related correction reference differs: {design_id}",
        )

    blocked_design_ids = [
        item["design_id"] for item in records
        if item.get("approval_blockers") == FP035_APPROVAL_BLOCKERS
    ]
    _require(set(blocked_design_ids) == FP035_DIRECT_DESIGN_IDS, "FP-035 blocked design set differs")
    coverage = trace.get("coverage", {})
    _require(
        coverage.get("fp035_direct_impact_design_count") == 3
        and set(coverage.get("fp035_direct_impact_design_ids", [])) == FP035_DIRECT_DESIGN_IDS
        and coverage.get("fp035_related_downstream_design_count") == 1
        and set(coverage.get("fp035_related_downstream_design_ids", [])) == FP035_RELATED_DESIGN_IDS,
        "design FP-035 direct/related coverage differs",
    )

    tests = _load_json(TEST_CASES_PATH).get("test_cases", [])
    for test in tests:
        candidate_refs = set(test.get("design_candidate_reference_ids", []))
        _require(candidate_refs <= expected_ids, f"test has an unknown design candidate: {test.get('test_case_id')}")

    requirement_ids = {
        item["requirement_id"] for item in _load_json(RTM_PATH).get("requirements", [])
    }
    modules = _load_json(MODULE_REGISTER_PATH).get("modules", [])
    _require(isinstance(modules, list) and modules, "module register is empty")
    for module in modules:
        _require(
            set(module.get("requirement_refs", [])) <= requirement_ids,
            f"module has an unknown requirement: {module.get('module_id')}",
        )
        _require(
            set(module.get("design_candidate_refs", [])) <= expected_ids,
            f"module has an unknown design candidate: {module.get('module_id')}",
        )

    requirements = trace.get("requirements_snapshot", {})
    _require(requirements.get("expected_specific_requirement_count") == 68, "design requirement target count differs")
    _require(requirements.get("matched_specific_requirement_count") == 68, "design requirement matches differ")
    _require(requirements.get("missing_specific_requirement_ids") == [], "design has missing requirement references")
    for index, binding in enumerate(requirements.get("bindings", [])):
        _validate_binding(binding, f"design requirements snapshot/{index}")
    _validate_gates(trace.get("remaining_gates"), "design trace")
    return {
        "design_record_count": len(records),
        "policy_blocked_design_count": 0,
        "fp035_direct_impact_design_count": len(blocked_design_ids),
        "fp035_related_downstream_design_count": len(FP035_RELATED_DESIGN_IDS),
        "feature_count": len(covered_features),
        "common_policy_count": len(covered_common),
        "gate_count": len(covered_gates),
    }


def _validate_fp035_cross_layer_blockers() -> dict[str, int]:
    changes = _load_json(CHANGE_REQUEST_REGISTER_PATH)
    requests = changes.get("requests", [])
    change = next(
        (item for item in requests if item.get("change_request_id") == "CR-0002"),
        None,
    )
    _require(isinstance(change, dict), "FP-035 change request CR-0002 is missing")
    scope = set(change.get("scope", []))
    _require(
        change.get("direct_artifact_codes")
        == ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"]
        and FP035_DIRECT_DESIGN_IDS.issubset(scope),
        "CR-0002 direct artifact scope differs",
    )
    _require(
        change.get("correction_candidate_id") == FP035_CORRECTION_CANDIDATE_ID
        and change.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT,
        "CR-0002 correction-candidate binding differs",
    )
    _require(
        {"MOD-ANDROID-USER", "MOD-BACKEND"}.issubset(scope),
        "CR-0002 omits an affected implementation module",
    )
    _require(change.get("approval_status") == "NOT_APPROVED", "CR-0002 was approved without policy reapproval")

    module_register = _load_json(MODULE_REGISTER_PATH)
    modules = {
        item.get("module_id"): item
        for item in module_register.get("modules", [])
    }
    affected_module_ids = {"MOD-ANDROID-USER", "MOD-BACKEND"}
    _require(affected_module_ids.issubset(modules), "an FP-035 implementation module is missing")
    for module_id in affected_module_ids:
        module = modules[module_id]
        _require("FP-035" in module.get("policy_refs", []), f"FP-035 policy link missing: {module_id}")
        _require(
            module.get("source_issue_ids") == [FP035_NETWORK_ISSUE_ID]
            and module.get("policy_correction_candidate_refs") == [FP035_CORRECTION_CANDIDATE_ID]
            and module.get("implementation_blockers") == FP035_APPROVAL_BLOCKERS
            and module.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT
            and module.get("mobile_data_branch_status") == "BLOCKED_PENDING_BUNDLED_APPROVAL"
            and module.get("mobile_network_branch_implementation_status")
            == "FROZEN_PENDING_EXACT_BUNDLED_APPROVAL"
            and module.get("mobile_network_branch_formal_test_status")
            == "NOT_RUN_BLOCKED_PENDING_EXACT_BUNDLED_APPROVAL",
            f"FP-035 implementation freeze differs: {module_id}",
        )

    metrics = _load_json(TEST_METRICS_PATH)
    _require(metrics.get("execution_metrics", {}).get("blocked") == 0, "pre-execution metrics claim an executed block")
    pre_execution = metrics.get("pre_execution_policy_blockers", {})
    _require(
        pre_execution.get("blocked_test_case_count") == 4
        and pre_execution.get("source_issue_id") == FP035_NETWORK_ISSUE_ID,
        "FP-035 pre-execution test blockers differ",
    )
    test_report = SOFTWARE_TEST_REPORT_PATH.read_text(encoding="utf-8")
    _require(
        "FP-035 정규화 지시 포착·묶음 승인 대기 | 4" in test_report,
        "TST-20 hides FP-035 pre-execution blockers",
    )
    return {
        "fp035_change_request_blocked_design_count": len(FP035_DIRECT_DESIGN_IDS),
        "fp035_related_downstream_design_count": len(FP035_RELATED_DESIGN_IDS),
        "fp035_blocked_module_count": len(affected_module_ids),
        "fp035_pre_execution_blocked_test_count": 4,
    }


def _validate_declared_trace_links() -> dict[str, int]:
    """Validate the live Draft link declarations before trusting the leaf report."""

    rtm = _load_json(RTM_PATH)
    design_trace = _load_json(DESIGN_TRACE_PATH)
    test_register = _load_json(TEST_CASES_PATH)
    module_register = _load_json(MODULE_REGISTER_PATH)

    requirements = rtm.get("requirements", [])
    requirements_by_id = {item.get("requirement_id"): item for item in requirements}
    _require(len(requirements_by_id) == 68 and None not in requirements_by_id, "declared-link requirement set differs")

    design_records = design_trace.get("records", [])
    designs_by_id = {item.get("design_id"): item for item in design_records}
    expected_design_ids = {f"DES-{number:02d}" for number in range(1, 28)}
    _require(set(designs_by_id) == expected_design_ids, "declared-link design set differs")
    design_paths = {
        design_id: record.get("document_path")
        for design_id, record in designs_by_id.items()
    }
    _require(
        all(isinstance(path, str) and (REPO_ROOT / path).is_file() for path in design_paths.values()),
        "a declared design document path is missing",
    )

    reverse_requirement_edges: set[tuple[str, str]] = set()
    for design_id, record in designs_by_id.items():
        requirement_refs = record.get("planned_specific_requirement_refs")
        links = record.get("planned_specific_requirement_links")
        _require(isinstance(requirement_refs, list), f"design requirement refs are missing: {design_id}")
        _require(isinstance(links, list), f"design requirement links are missing: {design_id}")
        _require(len(requirement_refs) == len(set(requirement_refs)), f"duplicate design requirement ref: {design_id}")
        _require(set(requirement_refs) <= set(requirements_by_id), f"unknown design requirement ref: {design_id}")
        expected_links = [
            {
                "requirement_id": requirement_id,
                "target": f"{REQUIREMENT_DOCUMENT_PATH}#{requirement_id}",
                "source_status": "DRAFT_FILE_PRESENT_NOT_BASELINED",
            }
            for requirement_id in requirement_refs
        ]
        _require(links == expected_links, f"design-to-requirement links differ: {design_id}")
        reverse_requirement_edges.update((requirement_id, design_id) for requirement_id in requirement_refs)

    declared_requirement_edges: set[tuple[str, str]] = set()
    for requirement_id, requirement in requirements_by_id.items():
        design_declaration = requirement.get("design_trace", {})
        links = design_declaration.get("links")
        _require(isinstance(links, list) and links, f"requirement design links are missing: {requirement_id}")
        design_ids = [item.get("design_id") for item in links]
        _require(len(design_ids) == len(set(design_ids)), f"duplicate requirement design link: {requirement_id}")
        _require(set(design_ids) <= expected_design_ids, f"unknown requirement design link: {requirement_id}")
        _require(
            links == [_expected_design_link(design_id, design_paths) for design_id in design_ids],
            f"requirement-to-design link structure differs: {requirement_id}",
        )
        declared_requirement_edges.update((requirement_id, design_id) for design_id in design_ids)

    _require(
        declared_requirement_edges == reverse_requirement_edges,
        "requirement/design forward and reverse edge sets differ",
    )
    _require(
        {design_id for _, design_id in declared_requirement_edges} == expected_design_ids,
        "requirement/design links do not cover all 27 design records",
    )

    test_cases = test_register.get("test_cases")
    _require(isinstance(test_cases, list) and len(test_cases) == 279, "declared-link test set differs")
    for case in test_cases:
        test_id = case.get("test_case_id")
        requirement_id = case.get("requirement_id")
        _require(requirement_id in requirements_by_id, f"test has an unknown requirement: {test_id}")
        _require(
            case.get("requirement_reference") == _expected_requirement_link(requirement_id),
            f"test requirement link differs: {test_id}",
        )
        direct_ids = case.get("design_reference_ids")
        expected_direct_ids = [
            item["design_id"]
            for item in requirements_by_id[requirement_id]["design_trace"]["links"]
        ]
        _require(direct_ids == expected_direct_ids, f"test direct design edge differs: {test_id}")
        _require(
            case.get("design_references")
            == [_expected_design_link(design_id, design_paths) for design_id in direct_ids],
            f"test direct design links differ: {test_id}",
        )
        _require(
            all(
                (requirement_id, design_id) in reverse_requirement_edges
                for design_id in direct_ids
            ),
            f"test direct design edge lacks a DES reverse reference: {test_id}",
        )
        candidate_ids = case.get("design_candidate_reference_ids")
        _require(
            isinstance(candidate_ids, list) and set(candidate_ids) <= expected_design_ids,
            f"test design candidates differ: {test_id}",
        )
        _require(set(candidate_ids) <= set(direct_ids), f"test verification focus exceeds direct design scope: {test_id}")
        _require(
            case.get("design_candidate_references")
            == [
                _expected_design_link(
                    design_id,
                    design_paths,
                    trace_status="VERIFICATION_FOCUS_ONLY_NOT_TRACE_EDGE",
                )
                for design_id in candidate_ids
            ],
            f"test design candidate links differ: {test_id}",
        )
        _require(
            case.get("design_candidate_role") == "VERIFICATION_FOCUS_ONLY_NOT_TRACE_EDGE"
            and case.get("design_candidate_links_are_trace_edges") is False,
            f"test verification-focus boundary differs: {test_id}",
        )
        if candidate_ids:
            _require(case.get("design_candidate_empty_reason") is None, f"non-empty verification focus has an empty reason: {test_id}")
        else:
            empty_reason = case.get("design_candidate_empty_reason")
            _require(isinstance(empty_reason, str) and bool(empty_reason.strip()), f"empty verification focus has no reason: {test_id}")
        _require(
            case.get("trace_validation_status") == "DRAFT_REQ_DES_LINKS_DECLARED"
            and case.get("link_validation_status") == "SEE_EXTERNAL_INTEGRATION_REPORT"
            and case.get("integration_report_path") == _rel(TRACE_INTEGRATION_REPORT_PATH),
            f"test integration-report declaration differs: {test_id}",
        )

    modules = module_register.get("modules")
    _require(isinstance(modules, list) and modules, "declared-link module set is empty")
    for module in modules:
        module_id = module.get("module_id")
        requirement_refs = module.get("requirement_refs")
        _require(
            isinstance(requirement_refs, list) and set(requirement_refs) <= set(requirements_by_id),
            f"module requirement refs differ: {module_id}",
        )
        _require(
            module.get("requirement_links")
            == [_expected_requirement_link(requirement_id) for requirement_id in requirement_refs],
            f"module requirement links differ: {module_id}",
        )
        design_refs = module.get("design_refs")
        _require(
            isinstance(design_refs, list) and design_refs and set(design_refs) <= expected_design_ids,
            f"module design refs differ: {module_id}",
        )
        _require(
            module.get("design_links")
            == [_expected_design_link(design_id, design_paths) for design_id in design_refs],
            f"module design links differ: {module_id}",
        )
        _require(
            module.get("design_candidate_refs") == design_refs,
            f"module design candidate refs differ: {module_id}",
        )
        _require(
            module.get("requirement_trace_status") == "DRAFT_REQUIREMENT_LINKS_DECLARED"
            and module.get("design_trace_status") == "DRAFT_DESIGN_LINKS_DECLARED"
            and module.get("link_validation_status") == "SEE_EXTERNAL_INTEGRATION_REPORT"
            and module.get("integration_report_path") == _rel(TRACE_INTEGRATION_REPORT_PATH),
            f"module integration-report declaration differs: {module_id}",
        )

    return {
        "requirement_design_trace_count": len(requirements),
        "requirement_design_edge_count": len(declared_requirement_edges),
        "acceptance_test_trace_count": len(test_cases),
        "test_design_trace_count": len(test_cases),
        "module_trace_count": len(modules),
    }


def _validate_trace_integration_report() -> dict[str, Any]:
    """Validate the post-generation, hash-bound REQ↔DES↔DEV/TST leaf report."""

    report = _load_json(TRACE_INTEGRATION_REPORT_PATH)
    _require(
        report.get("schema_version") == "walksafe.req-des-tst-trace-integration-report.v1",
        "trace integration report schema differs",
    )
    metadata = report.get("metadata", {})
    expected_metadata = {
        "report_id": EXPECTED_TRACE_REPORT_ID,
        "version": "0.2.0",
        "lifecycle_status": "DRAFT",
        "approval_status": "NOT_APPROVED",
        "structural_validation_status": "STRUCTURAL_TRACE_VALIDATION_PASS",
        "verification_scope": "ID_PATH_HASH_STRUCTURE_ONLY",
        "formal_test_execution_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
    }
    for key, expected in expected_metadata.items():
        _require(metadata.get(key) == expected, f"trace integration report metadata differs: {key}")

    bindings = report.get("source_bindings")
    _require(isinstance(bindings, dict), "trace integration report source bindings are missing")
    _require(
        {name: binding.get("path") for name, binding in bindings.items()}
        == EXPECTED_TRACE_REPORT_BINDING_PATHS,
        "trace integration report source binding paths differ",
    )
    for name, binding in bindings.items():
        _validate_binding(binding, f"trace integration report/{name}")
    _require(
        report.get("source_binding_sha256") == _object_sha256(bindings),
        "trace integration report source-binding digest differs",
    )
    _validate_object_digest(
        report,
        "report_content_sha256",
        _rel(TRACE_INTEGRATION_REPORT_PATH),
    )

    boundary = report.get("authorization_boundary", {})
    _require(boundary.get("formal_approval_count") == 0, "trace report claims a formal approval")
    _require(boundary.get("remaining_gates_waived") is False, "trace report waives remaining gates")
    _require(boundary.get("release_authorized") is False, "trace report authorizes release")
    _require(
        boundary.get("structural_validation_is_not_test_execution_or_approval") is True,
        "trace report overstates structural validation",
    )

    open_issues = report.get("open_policy_issues")
    _require(
        isinstance(open_issues, list)
        and len(open_issues) == 1
        and open_issues[0].get("issue_id") == FP035_NETWORK_ISSUE_ID
        and open_issues[0].get("status")
        == "CORRECTION_CANDIDATE_BOUND_NOT_APPROVED_NOT_EFFECTIVE"
        and open_issues[0].get("correction_candidate_id") == FP035_CORRECTION_CANDIDATE_ID
        and open_issues[0].get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT,
        "trace report must expose the pending FP-035 correction candidate",
    )
    _validate_gates(report.get("remaining_gates"), "trace integration report")

    rtm = _load_json(RTM_PATH)
    design_trace = _load_json(DESIGN_TRACE_PATH)
    test_register = _load_json(TEST_CASES_PATH)
    module_register = _load_json(MODULE_REGISTER_PATH)

    requirements = rtm.get("requirements", [])
    requirements_by_id = {item["requirement_id"]: item for item in requirements}
    design_records = design_trace.get("records", [])
    designs_by_id = {item["design_id"]: item for item in design_records}
    reverse_by_requirement: dict[str, set[str]] = {
        requirement_id: set() for requirement_id in requirements_by_id
    }
    for design_id, design in designs_by_id.items():
        for requirement_id in design.get("planned_specific_requirement_refs", []):
            reverse_by_requirement[requirement_id].add(design_id)

    requirement_records = report.get("requirement_design_trace")
    _require(
        isinstance(requirement_records, list) and len(requirement_records) == 68,
        "trace report requirement/design record count differs",
    )
    requirement_report_by_id = {
        item.get("requirement_id"): item for item in requirement_records
    }
    _require(
        set(requirement_report_by_id) == set(requirements_by_id),
        "trace report requirement/design ID coverage differs",
    )
    requirement_design_edge_count = 0
    for requirement_id, source in requirements_by_id.items():
        record = requirement_report_by_id[requirement_id]
        declared_ids = [
            item.get("design_id") for item in source.get("design_trace", {}).get("links", [])
        ]
        reverse_ids = sorted(reverse_by_requirement[requirement_id])
        _require(record.get("source_policy_id") == source.get("source_policy_id"), f"trace report requirement source differs: {requirement_id}")
        _require(record.get("declared_design_ids") == declared_ids, f"trace report declared design IDs differ: {requirement_id}")
        _require(record.get("reverse_design_ids") == reverse_ids, f"trace report reverse design IDs differ: {requirement_id}")
        _require(record.get("edge_count") == len(declared_ids), f"trace report requirement edge count differs: {requirement_id}")
        _require(record.get("status") == "STRUCTURAL_LINK_VALIDATED", f"trace report requirement status differs: {requirement_id}")
        requirement_design_edge_count += len(declared_ids)

    acceptance_by_id: dict[str, tuple[str, str, str]] = {}
    for requirement in requirements:
        for acceptance in requirement.get("acceptance_conditions", []):
            acceptance_id = acceptance["acceptance_condition_id"]
            acceptance_by_id[acceptance_id] = (
                acceptance["planned_test_id"],
                requirement["requirement_id"],
                requirement["source_policy_id"],
            )
    acceptance_records = report.get("acceptance_test_trace")
    _require(
        isinstance(acceptance_records, list) and len(acceptance_records) == 279,
        "trace report acceptance/test record count differs",
    )
    acceptance_report_by_id = {
        item.get("acceptance_condition_id"): item for item in acceptance_records
    }
    _require(
        set(acceptance_report_by_id) == set(acceptance_by_id),
        "trace report acceptance/test ID coverage differs",
    )
    for acceptance_id, (test_id, requirement_id, source_policy_id) in acceptance_by_id.items():
        record = acceptance_report_by_id[acceptance_id]
        _require(record.get("test_case_id") == test_id, f"trace report acceptance test differs: {acceptance_id}")
        _require(record.get("requirement_id") == requirement_id, f"trace report acceptance requirement differs: {acceptance_id}")
        _require(record.get("source_policy_id") == source_policy_id, f"trace report acceptance source differs: {acceptance_id}")
        _require(record.get("status") == "STRUCTURAL_LINK_VALIDATED", f"trace report acceptance status differs: {acceptance_id}")

    tests = test_register.get("test_cases", [])
    tests_by_id = {item["test_case_id"]: item for item in tests}
    test_records = report.get("test_design_trace")
    _require(
        isinstance(test_records, list) and len(test_records) == 279,
        "trace report test/design record count differs",
    )
    test_report_by_id = {item.get("test_case_id"): item for item in test_records}
    _require(set(test_report_by_id) == set(tests_by_id), "trace report test/design ID coverage differs")
    direct_test_design_edge_count = 0
    verification_focus_candidate_reference_count = 0
    verification_focus_empty_test_case_count = 0
    for test_id, source in tests_by_id.items():
        record = test_report_by_id[test_id]
        direct_ids = source.get("design_reference_ids", [])
        candidate_ids = source.get("design_candidate_reference_ids", [])
        _require(record.get("requirement_id") == source.get("requirement_id"), f"trace report test requirement differs: {test_id}")
        _require(record.get("direct_design_ids") == direct_ids, f"trace report direct design IDs differ: {test_id}")
        _require(record.get("candidate_design_ids") == candidate_ids, f"trace report candidate design IDs differ: {test_id}")
        _require(record.get("candidate_role") == "VERIFICATION_FOCUS_ONLY_NOT_TRACE_EDGE", f"trace report candidate role differs: {test_id}")
        _require(record.get("candidate_links_are_trace_edges") is False, f"trace report candidate edge boundary differs: {test_id}")
        _require(record.get("candidate_empty_reason") == source.get("design_candidate_empty_reason"), f"trace report candidate empty reason differs: {test_id}")
        _require(record.get("direct_reverse_validated") is True, f"trace report direct reverse validation differs: {test_id}")
        _require(record.get("status") == "DIRECT_REVERSE_AND_VERIFICATION_FOCUS_VALIDATED", f"trace report test/design status differs: {test_id}")
        direct_test_design_edge_count += len(direct_ids)
        verification_focus_candidate_reference_count += len(candidate_ids)
        verification_focus_empty_test_case_count += not candidate_ids

    modules = module_register.get("modules", [])
    modules_by_id = {item["module_id"]: item for item in modules}
    module_records = report.get("module_trace")
    _require(
        isinstance(module_records, list) and len(module_records) == len(modules),
        "trace report module record count differs",
    )
    module_report_by_id = {item.get("module_id"): item for item in module_records}
    _require(set(module_report_by_id) == set(modules_by_id), "trace report module ID coverage differs")
    module_requirement_edge_count = 0
    module_design_edge_count = 0
    for module_id, source in modules_by_id.items():
        record = module_report_by_id[module_id]
        requirement_ids = source.get("requirement_refs", [])
        design_ids = source.get("design_refs", [])
        _require(record.get("requirement_ids") == requirement_ids, f"trace report module requirement IDs differ: {module_id}")
        _require(record.get("design_ids") == design_ids, f"trace report module design IDs differ: {module_id}")
        _require(record.get("status") == "DECLARED_TARGETS_VALIDATED", f"trace report module status differs: {module_id}")
        module_requirement_edge_count += len(requirement_ids)
        module_design_edge_count += len(design_ids)

    fp035_tests = [item for item in tests if item.get("source_policy_id") == "FP-035"]
    coverage = report.get("coverage", {})
    expected_coverage = {
        "source_binding_count": len(bindings),
        "requirement_count": len(requirements),
        "feature_requirement_count": sum(item.get("source_kind") == "FEATURE_POLICY" for item in requirements),
        "common_policy_requirement_count": sum(item.get("source_kind") == "COMMON_POLICY" for item in requirements),
        "remaining_gate_requirement_count": sum(item.get("source_kind") == "REMAINING_GATE" for item in requirements),
        "requirement_design_edge_count": requirement_design_edge_count,
        "acceptance_condition_count": len(acceptance_by_id),
        "test_case_count": len(tests),
        "direct_test_design_edge_count": direct_test_design_edge_count,
        "verification_focus_candidate_reference_count": verification_focus_candidate_reference_count,
        "verification_focus_empty_test_case_count": verification_focus_empty_test_case_count,
        "design_artifact_count": len(design_records),
        "module_count": len(modules),
        "module_requirement_edge_count": module_requirement_edge_count,
        "module_design_edge_count": module_design_edge_count,
        "blocked_fp035_test_case_count": len(fp035_tests),
        "fp035_general_policy_trace_design_count": 16,
        "fp035_direct_affected_design_count": 3,
        "fp035_related_downstream_design_count": 1,
        "fp035_direct_affected_module_count": 1,
        "fp035_related_downstream_module_count": 1,
        "pending_fp035_correction_candidate_count": 1,
        "remaining_gate_count": 5,
        "formal_approval_count": 0,
        "formal_test_execution_count": 0,
        "open_policy_issue_count": 1,
    }
    _require(coverage == expected_coverage, "trace integration report coverage differs")
    _require(verification_focus_empty_test_case_count == 48, "verification-focus empty test count differs")

    return {
        "trace_report_id": metadata["report_id"],
        "trace_report_status": metadata["structural_validation_status"],
        "trace_report_source_binding_count": len(bindings),
        "requirement_design_trace_count": len(requirement_records),
        "requirement_design_edge_count": requirement_design_edge_count,
        "acceptance_test_trace_count": len(acceptance_records),
        "test_design_trace_count": len(test_records),
        "direct_test_design_edge_count": direct_test_design_edge_count,
        "verification_focus_candidate_reference_count": verification_focus_candidate_reference_count,
        "verification_focus_empty_test_case_count": verification_focus_empty_test_case_count,
        "module_trace_count": len(module_records),
    }


def _validate_current_decision_register(
    current: dict[str, Any] | None = None,
) -> dict[str, Any]:
    current = current if current is not None else _load_json(CURRENT_DECISIONS_PATH)
    _validate_object_digest(
        current,
        "register_content_sha256",
        "current effective decision register",
    )
    _require(
        current.get("schema_version")
        == "walksafe.effective-decision-register-composition.v1",
        "current decision register schema differs",
    )
    metadata = current.get("metadata", {})
    _require(
        metadata
        == {
            "register_id": "WS-EFFECTIVE-DECISION-REGISTER-CURRENT-20260726-001",
            "register_version": "1.0.1",
            "controlled_revision": 2,
            "as_of": "2026-07-26",
            "lifecycle_status": "ACTIVE",
            "supersession_status": "CURRENT_COMPOSITE",
            "materialization_status": "MATERIALIZED_FROM_APPROVED_POLICY",
        },
        "current decision register metadata differs",
    )

    source_bindings = current.get("source_bindings")
    expected_source_paths_and_hashes = {
        "base_aligned_register": (
            ALIGNED_DECISIONS_PATH,
            "a86cde7f95e9ebe90aea47d350c2be61149a75c8974d05f122e91108f9c8015f",
        ),
        "fp035_pre_activation_candidate": (
            FP035_CORRECTION_CANDIDATE_PATH,
            "7298e024cbb88e8fda5e27dfd9e97f7ebf23250a7adf4ea6185c406adb4ed742",
        ),
        "effective_policy_manifest": (
            POLICY_1_0_1_MANIFEST_PATH,
            "b6f5b850a3983b8059b85d93dd07864520219d31fa65a65d740b6bab78231308",
        ),
        "bound_artifact_approval": (
            ARTIFACT_APPROVAL_PATH,
            "6ad3e60286c24a8519aa92dcd68a1fa7e4f64759da6d84f6817812b6d85d258b",
        ),
        "committed_application_receipt": (
            APPLICATION_RECEIPT_PATH,
            "002355b92c9862a9fbdc443a48b28657975f91fb58caf3504a28df5eb1f524bb",
        ),
        "temporal_provenance_supplement": (
            TEMPORAL_SUPPLEMENT_PATH,
            "99d657d9dde3117b3bdd9ce62f5dcbf26adba38338f46c45e38ed993c5ca7f2c",
        ),
    }
    _require(
        isinstance(source_bindings, dict)
        and set(source_bindings) == set(expected_source_paths_and_hashes),
        "current decision source binding set differs",
    )
    _require(
        current.get("source_binding_sha256") == _object_sha256(source_bindings),
        "current decision source binding digest differs",
    )
    for name, (path, expected_hash) in expected_source_paths_and_hashes.items():
        binding = source_bindings[name]
        _require(
            binding.get("path") == _rel(path)
            and binding.get("sha256") == expected_hash,
            f"current decision source identity differs: {name}",
        )
        _validate_binding(binding, f"current effective decision register#{name}")

    base = _load_json(ALIGNED_DECISIONS_PATH)
    candidate = _load_json(FP035_CORRECTION_CANDIDATE_PATH)
    policy_manifest = _load_json(POLICY_1_0_1_MANIFEST_PATH)
    artifact_approval = _load_json(ARTIFACT_APPROVAL_PATH)
    receipt = _load_json(APPLICATION_RECEIPT_PATH)
    temporal = _load_json(TEMPORAL_SUPPLEMENT_PATH)
    base_binding = source_bindings["base_aligned_register"]
    candidate_binding = source_bindings["fp035_pre_activation_candidate"]
    manifest_binding = source_bindings["effective_policy_manifest"]
    approval_binding = source_bindings["bound_artifact_approval"]
    receipt_binding = source_bindings["committed_application_receipt"]
    temporal_binding = source_bindings["temporal_provenance_supplement"]

    _require(
        base_binding.get("register_content_sha256")
        == base.get("register_content_sha256")
        == "8bf2620a4ff3d4468f46db1fd6f9a6faab86c2c34eb95456478dfd07160020c2",
        "base register content binding differs",
    )
    _require(
        base_binding.get("decision_binding_sha256")
        == base.get("decision_binding_sha256")
        == "d20171efde70a11fd31f53496e007e009879f9406516583d7f65a473eb0d8e34",
        "base decision binding differs",
    )
    base_decisions = base.get("decisions")
    _require(
        isinstance(base_decisions, list) and len(base_decisions) == 135,
        "base decision count differs",
    )
    canonical_ids = {
        item.get("canonical_decision_id") for item in base_decisions
    }
    base_edge_count = sum(
        len(item.get("affected_feature_ids", [])) for item in base_decisions
    )
    _require(
        len(canonical_ids) == 135 and base_edge_count == 428,
        "base decision coverage differs",
    )

    candidate_metadata = candidate.get("metadata", {})
    _require(
        candidate_binding.get("candidate_id")
        == candidate_metadata.get("candidate_id")
        == FP035_CORRECTION_CANDIDATE_ID,
        "FP-035 candidate identity differs",
    )
    _require(
        candidate_binding.get("candidate_content_sha256")
        == candidate.get("candidate_content_sha256")
        == "1918e10fd03d74fc3a7c292b077f1da51027bcdc7b9945f6c22c0acafc877a9d",
        "FP-035 candidate content binding differs",
    )
    _require(
        candidate_binding.get("correction_binding_sha256")
        == candidate.get("correction_binding_sha256")
        == "5f44ee6d1baae95e57e318946d4b8f0c2bed857a8bd3b9cdc16805a7ae80fcfd",
        "FP-035 correction binding differs",
    )
    _require(
        candidate_metadata.get("approval_status")
        == candidate_binding.get("embedded_approval_status")
        == "NOT_APPROVED"
        and candidate_metadata.get("effective_status")
        == candidate_binding.get("embedded_effective_status")
        == "NOT_EFFECTIVE"
        and candidate_binding.get("status_interpretation")
        == "PRE_ACTIVATION_SNAPSHOT_ONLY",
        "FP-035 candidate pre-activation interpretation differs",
    )

    policy_metadata = policy_manifest.get("metadata", {})
    _require(
        manifest_binding.get("baseline_id")
        == policy_metadata.get("baseline_id")
        == "PB-WALKSAFE-FEATURE-POLICY-1.0.1"
        and manifest_binding.get("baseline_version")
        == policy_metadata.get("baseline_version")
        == "1.0.1"
        and policy_metadata.get("baseline_status") == "BASELINED"
        and policy_metadata.get("effective_status")
        == "EFFECTIVE_BY_BUNDLED_OWNER_APPROVAL",
        "effective policy 1.0.1 status differs",
    )
    _require(
        manifest_binding.get("content_sha256")
        == policy_manifest.get("content_sha256")
        == "8fdeab58e8c901a29f4380de373cdfd2b67350cd43dafa765246b4f6c9f77244",
        "effective policy manifest content binding differs",
    )
    _require(
        approval_binding.get("approval_record_id")
        == artifact_approval.get("metadata", {}).get("approval_record_id")
        == "WS-ARTIFACT-BASELINE-APPROVAL-20260722-001"
        and approval_binding.get("content_sha256")
        == artifact_approval.get("content_sha256")
        == "f0d83889eb784a7407ed11ab4de8ea360a6f44bbcd113ada984812a550d6a437"
        and approval_binding.get("policy_phase_0_decision")
        == artifact_approval.get("policy_phase_0", {}).get("decision")
        == "APPROVED",
        "bound FP-035 approval differs",
    )
    _require(
        receipt_binding.get("receipt_id")
        == receipt.get("metadata", {}).get("receipt_id")
        == "WS-ARTIFACT-BASELINE-APPLICATION-RECEIPT-20260722-001"
        and receipt_binding.get("content_sha256")
        == receipt.get("content_sha256")
        == "85a34a21c50a3b0d6ecc15f21bcfa3051c5b408b3f248dcd2a4e22bd90e52090"
        and receipt_binding.get("transaction_status")
        == receipt.get("metadata", {}).get("transaction_status")
        == "COMMITTED",
        "committed approval receipt differs",
    )
    _require(
        temporal_binding.get("content_sha256")
        == temporal.get("content_sha256")
        == "218b920087fd2d0831025ea96eef61ace35ecdde36c565bd6f17e97993ed9452"
        and temporal_binding.get("exact_effective_time")
        == temporal.get("effective_boundary", {}).get("exact_effective_time")
        == "NOT_CAPTURED",
        "temporal provenance boundary differs",
    )

    _require(
        current.get("effective_policy")
        == {
            "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "baseline_version": "1.0.1",
            "composition": "PB-WALKSAFE-FEATURE-POLICY-1.0.0 + exact FP-035 correction overlay",
            "activation_status": "EFFECTIVE_BY_VALID_COMMITTED_RECEIPT",
        },
        "current effective policy projection differs",
    )
    expected_precedence = {
        "state_precedence": [
            "COMMITTED_RECEIPT",
            "POLICY_1.0.1_MANIFEST",
            "BOUND_ARTIFACT_APPROVAL_POLICY_PHASE_0",
            "PRE_ACTIVATION_CANDIDATE_SNAPSHOT",
        ],
        "candidate_status_interpretation": "PRE_ACTIVATION_SNAPSHOT_ONLY",
        "merge_rule_version": "walksafe.exact-canonical-id-field-replacement.v1",
        "merge_rule": "COPY_BASE_RECORD_AND_REPLACE_DECLARED_FIELDS_ONLY",
        "failure_mode": "FAIL_CLOSED_NO_SILENT_FALLBACK",
        "does_not_rewrite_existing_immutable_records": True,
    }
    _require(
        current.get("precedence") == expected_precedence,
        "current decision precedence differs",
    )

    replacements = current.get("replacement_decisions")
    _require(
        isinstance(replacements, list) and len(replacements) == 1,
        "exactly one current decision replacement is required",
    )
    replacement = replacements[0]
    _validate_object_digest(
        replacement,
        "content_sha256",
        "CD-UPLOAD-NETWORK replacement",
    )
    _require(
        current.get("replacement_binding_sha256") == _object_sha256(replacements),
        "current decision replacement binding differs",
    )
    target = next(
        (
            item
            for item in base_decisions
            if item.get("canonical_decision_id") == "CD-UPLOAD-NETWORK"
        ),
        None,
    )
    _require(isinstance(target, dict), "base CD-UPLOAD-NETWORK is missing")
    expected_target = {
        "decision_id": target.get("decision_id"),
        "canonical_decision_id": target.get("canonical_decision_id"),
        "decision_version": target.get("decision_version"),
        "content_sha256": target.get("content_sha256"),
    }
    _require(
        expected_target
        == replacement.get("target")
        == {
            "decision_id": "DEC-UPLOAD-NETWORK",
            "canonical_decision_id": "CD-UPLOAD-NETWORK",
            "decision_version": "1.0.0",
            "content_sha256": "c8b6d5f6899dd2cc827c99d31ac5da71ef5af057e4968f38ff908e97b05901ba",
        },
        "CD-UPLOAD-NETWORK replacement target differs",
    )
    _require(
        replacement.get("replacement_mode")
        == "COPY_BASE_THEN_REPLACE_DECLARED_FIELDS",
        "CD-UPLOAD-NETWORK replacement mode differs",
    )
    replacement_fields = replacement.get("replacement_fields", {})
    _require(
        replacement_fields.get("decision_version") == "1.0.1"
        and replacement_fields.get("policy_alignment_status")
        == "ALIGNED_TO_EFFECTIVE_POLICY_BASELINE_1.0.1",
        "CD-UPLOAD-NETWORK replacement version or alignment differs",
    )
    expected_clause_refs = target.get("effective_value", {}).get(
        "policy_clause_refs"
    )
    _require(
        replacement_fields.get("effective_value")
        == {
            "policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "policy_baseline_version": "1.0.1",
            "composition": "PB-WALKSAFE-FEATURE-POLICY-1.0.0 + exact FP-035 correction overlay",
            "policy_clause_refs": expected_clause_refs,
        },
        "CD-UPLOAD-NETWORK effective value differs",
    )
    replacement_approval = replacement_fields.get("baseline_approval_ref", {})
    _require(
        replacement_approval
        == {
            "approval_record_id": "WS-ARTIFACT-BASELINE-APPROVAL-20260722-001",
            "approval_record_content_sha256": "f0d83889eb784a7407ed11ab4de8ea360a6f44bbcd113ada984812a550d6a437",
            "approval_scope": "EXACT_FP035_CORRECTION_OVERLAY_IN_BUNDLED_APPROVAL",
            "policy_phase_0_decision": "APPROVED",
            "commit_receipt_id": "WS-ARTIFACT-BASELINE-APPLICATION-RECEIPT-20260722-001",
            "separate_decision_register_reapproval_claimed": False,
        },
        "CD-UPLOAD-NETWORK approval binding differs",
    )

    correction = candidate.get("correction", {})
    correction_scope = replacement.get("correction_scope", {})
    _require(
        correction_scope.get("changed_feature_id")
        == correction.get("feature_id")
        == "FP-035"
        and correction_scope.get("changed_policy_clause_refs")
        == [correction.get("policy_clause_id")]
        == ["FP-035-POLICY-001"]
        and correction_scope.get("normative_rule")
        == correction.get("normative_rule"),
        "FP-035 replacement correction scope differs",
    )
    _require(
        correction_scope.get("unchanged_policy_clause_refs")
        == [
            "FP-036-POLICY-001",
            "FP-044-POLICY-001",
            "FP-045-POLICY-001",
        ]
        and correction_scope.get("directly_affected_artifact_codes")
        == correction.get("affected_artifact_codes")
        and correction_scope.get("required_related_record_codes")
        == [
            item.get("artifact_code")
            for item in correction.get("required_related_records", [])
        ],
        "FP-035 replacement impact scope differs",
    )
    preserved = replacement.get("preserved_base_assertions", {})
    _require(
        preserved
        == {
            "affected_feature_ids": target.get("affected_feature_ids"),
            "base_affected_artifact_type_ids": target.get(
                "affected_artifact_type_ids"
            ),
            "canonical_decision_id_changed": False,
            "decision_feature_edges_changed": False,
        },
        "CD-UPLOAD-NETWORK preserved base assertions differ",
    )
    fp035_gate_ids = [
        gate["id"]
        for gate in policy_manifest.get("remaining_gates", [])
        if "FP-035" in gate.get("affected_feature_ids", [])
    ]
    _require(
        replacement.get("verification_tracking")
        == {
            "status": "TRACKED_BY_EFFECTIVE_POLICY_GATES",
            "completion_claimed": False,
            "gate_ids": fp035_gate_ids,
        },
        "FP-035 replacement verification boundary differs",
    )

    expected_coverage = {
        "base_decision_count": 135,
        "base_unique_canonical_decision_count": 135,
        "base_decision_feature_edge_count": 428,
        "replacement_count": 1,
        "new_canonical_decision_count": 0,
        "effective_decision_count": 135,
        "effective_unique_canonical_decision_count": 135,
        "effective_decision_feature_edge_count": 428,
        "changed_feature_edge_count": 1,
        "unchanged_feature_edge_count": 427,
    }
    _require(
        current.get("coverage") == expected_coverage,
        "current decision coverage differs",
    )
    expected_boundary = {
        "source_policy_baseline_status": "BASELINED_1.0.1",
        "policy_alignment_status": "COMPLETE",
        "correction_approval_status": "APPROVED_AS_EXACT_OVERLAY",
        "application_status": "COMMITTED",
        "register_lifecycle_status": "ACTIVE",
        "register_update_authority": "DERIVED_CONTROL_UPDATE_FROM_APPROVED_POLICY",
        "separate_register_reapproval_claimed": False,
        "implementation_conformance_assessed": False,
        "test_completion_claimed": False,
        "remaining_gates_are_waived": False,
        "release_status": "NOT_ELIGIBLE",
    }
    _require(
        current.get("approval_boundary") == expected_boundary,
        "current decision approval or release boundary differs",
    )
    _require(
        current.get("temporal_boundary")
        == {
            "effective_event": "VALID_COMMITTED_RECEIPT_EXISTENCE",
            "exact_effective_time": "NOT_CAPTURED",
            "backdated_materialization_claimed": False,
        },
        "current decision temporal boundary differs",
    )
    _validate_gates(
        current.get("remaining_gates"),
        "current effective decision register",
    )

    expected_composite_basis = {
        "base_register_file_sha256": base_binding["sha256"],
        "base_decision_binding_sha256": base_binding[
            "decision_binding_sha256"
        ],
        "replacement_binding_sha256": current[
            "replacement_binding_sha256"
        ],
        "merge_rule_version": expected_precedence["merge_rule_version"],
    }
    _require(
        current.get("composite_binding_basis") == expected_composite_basis,
        "current decision composite binding basis differs",
    )
    _require(
        current.get("composite_decision_binding_sha256")
        == _object_sha256(expected_composite_basis),
        "current decision composite binding digest differs",
    )
    return {
        "current_effective_policy_baseline_version": "1.0.1",
        "current_effective_decision_count": 135,
        "current_decision_replacement_count": 1,
        "current_effective_decision_feature_edge_count": 428,
    }


def validate_repository() -> dict[str, Any]:
    policy_manifest = _load_json(POLICY_MANIFEST_PATH)
    policy_approval = _load_json(POLICY_APPROVAL_PATH)
    aligned_decisions = _load_json(ALIGNED_DECISIONS_PATH)
    try:
        approval_builder.validate_approval_record(policy_approval)
        approval_builder.validate_baseline_manifest(policy_manifest, policy_approval)
        alignment_sources = alignment_builder._load_and_validate_sources()
        alignment_builder.validate_alignment(aligned_decisions, alignment_sources)
    except (
        approval_builder.BaselineApprovalError,
        alignment_builder.DecisionAlignmentError,
        OSError,
        KeyError,
        TypeError,
        ValueError,
    ) as exc:
        raise FormalDeliverableValidationError(
            f"approved policy or aligned decision integrity differs: {exc}"
        ) from exc
    current_decision_summary = _validate_current_decision_register()
    establishment = policy_manifest.get("establishment_boundary", {})
    _require(policy_manifest.get("metadata", {}).get("lifecycle_status") == "BASELINED", "policy baseline is not established")
    _require(establishment.get("content_approval_status") == "APPROVED", "policy content is not approved")
    _require(establishment.get("remaining_gates_are_waived") is False, "policy baseline waives gates")
    _require(establishment.get("release_status") == "NOT_ELIGIBLE", "policy baseline release differs")
    _validate_gates(policy_manifest.get("remaining_gates"), "policy baseline manifest")

    manifest_dir = DELIVERABLE_ROOT / "manifests"
    manifest_paths = [manifest_dir / name for name in sorted(EXPECTED_MANIFEST_NAMES)]
    _require(
        all(path.is_file() for path in manifest_paths),
        "one or more required 0~6 formal Draft manifests are missing",
    )
    manifests = [_validate_draft_manifest(path) for path in manifest_paths]
    register = _validate_artifact_register()
    trace_summary = _validate_traceability()
    design_summary = _validate_design_trace()
    fp035_cross_layer_summary = _validate_fp035_cross_layer_blockers()
    declared_trace_summary = _validate_declared_trace_links()
    report_summary = _validate_trace_integration_report()
    for key in (
        "requirement_design_trace_count",
        "requirement_design_edge_count",
        "acceptance_test_trace_count",
        "test_design_trace_count",
        "module_trace_count",
    ):
        _require(
            declared_trace_summary[key] == report_summary[key],
            f"live declarations and leaf trace report differ: {key}",
        )

    readme = ROOT_README_PATH.read_text(encoding="utf-8")
    for required_text in (
        "전체 유형 **257개**",
        "실제 Draft/In Review 연결 **182개**, 아직 Planned **75개**",
        "승인된 정식 산출물 **0개**",
        "출시 **NOT_ELIGIBLE**",
    ):
        _require(required_text in readme, f"top-level README status is stale: {required_text}")

    return {
        "status": "PASS",
        "artifact_type_count": 128,
        "required_count": 114,
        "conditional_count": 14,
        "bundle_count": 21,
        "draft_artifact_count": 105,
        "planned_artifact_count": 23,
        "formal_manifest_count": len(manifests),
        **trace_summary,
        **design_summary,
        **fp035_cross_layer_summary,
        **report_summary,
        **current_decision_summary,
        "open_unwaived_gate_count": 5,
        "approved_formal_artifact_count": 0,
        "release_status": "NOT_ELIGIBLE",
        "validation_scope": "STRUCTURE_AND_TRACE_ONLY",
    }


def main() -> int:
    try:
        print(json.dumps(validate_repository(), ensure_ascii=False, indent=2))
        return 0
    except FormalDeliverableValidationError as exc:
        print(f"formal 0~6 validation failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
