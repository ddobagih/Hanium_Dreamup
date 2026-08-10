#!/usr/bin/env python3
"""Align the immutable 135-decision candidate to approved policy baseline 1.0.0."""

from __future__ import annotations

import argparse
import copy
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from typing import Any

try:
    from scripts import build_walksafe_feature_policy_baseline_review_20260721 as review_builder
except ModuleNotFoundError:  # Direct execution from scripts/.
    import build_walksafe_feature_policy_baseline_review_20260721 as review_builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
INTERVIEW_DIR = REPO_ROOT / "docs" / "control" / "decision-interview"
BASELINE_DIR = REPO_ROOT / "docs" / "control" / "baselines"
SOURCE_DIR = INTERVIEW_DIR / "source-records"

APPROVAL_GENERATOR_PATH = REPO_ROOT / "scripts" / "build_walksafe_feature_policy_baseline_approval_20260721.py"
PREDECESSOR_REGISTER_PATH = INTERVIEW_DIR / "walksafe-effective-decision-register.json"
POLICY_DOCUMENT_PATH = INTERVIEW_DIR / "walksafe-feature-policy-comprehensive-draft.json"
POLICY_HTML_PATH = INTERVIEW_DIR / "walksafe-feature-policy-comprehensive-draft-20260720.html"
REVIEW_RESOLUTION_PATH = INTERVIEW_DIR / "walksafe-feature-policy-baseline-review-resolution-20260721-r001.json"
ANSWERS_PATH = SOURCE_DIR / "walksafe-feature-policy-baseline-review-20260720-r001-answers.json"
APPROVAL_RECORD_PATH = BASELINE_DIR / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
BASELINE_MANIFEST_PATH = BASELINE_DIR / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
OUTPUT_PATH = INTERVIEW_DIR / "walksafe-effective-decision-register-aligned-20260721-r001.json"

EXPECTED_SOURCE_STATUS_COUNTS = {
    "PENDING_EVIDENCE": 2,
    "PENDING_EXPERT_REVIEW": 16,
    "PENDING_MEASUREMENT": 21,
    "PENDING_PROPOSAL": 54,
    "PENDING_SOURCE_NORMALIZATION": 5,
    "RESOLVED": 37,
}

TRANSITIONS = {
    "RESOLVED": "CONFIRMED_IN_BASELINE",
    "PENDING_SOURCE_NORMALIZATION": "SUPERSEDED_BY_APPROVED_POLICY_BUNDLE",
    "PENDING_PROPOSAL": "ADOPTED_OR_REPLACED_BY_BASELINE_BUNDLE",
    "PENDING_MEASUREMENT": "RECLASSIFIED_TO_POLICY_EVIDENCE_AND_REMAINING_GATES",
    "PENDING_EXPERT_REVIEW": "RECLASSIFIED_TO_POLICY_EVIDENCE_AND_REMAINING_GATES",
    "PENDING_EVIDENCE": "RECLASSIFIED_TO_POLICY_EVIDENCE_AND_REMAINING_GATES",
}

DECISION_FIELDS = {
    "decision_id",
    "register_order",
    "canonical_decision_id",
    "decision_version",
    "title",
    "decision_statement",
    "value_type",
    "selected_value",
    "selected_label",
    "impact_summary",
    "rationale",
    "disposition_rationale",
    "responsibility_type",
    "previous_disposition",
    "lifecycle_status",
    "resolution_status",
    "normalization_status",
    "owner_role",
    "record_owner_role",
    "reviewer_roles",
    "approver_role",
    "source_refs",
    "supersedes_decision_ids",
    "supersedes_source_refs",
    "affected_feature_ids",
    "affected_artifact_type_ids",
    "affected_bundle_ids",
    "open_details",
    "normalization_source_refs",
    "verification_gate",
    "approved_by",
    "approved_at",
    "content_sha256",
}

ALIGNED_DECISION_FIELDS = {
    "decision_id",
    "register_order",
    "canonical_decision_id",
    "decision_version",
    "title",
    "prior_state",
    "effective_value_type",
    "effective_value",
    "mapping_granularity",
    "baseline_confirmation_refs",
    "policy_resolution_status",
    "policy_alignment_status",
    "formal_register_update_status",
    "legacy_transition",
    "verification_tracking",
    "affected_feature_ids",
    "affected_artifact_type_ids",
    "affected_bundle_ids",
    "legacy_source_refs",
    "baseline_approval_ref",
    "content_sha256",
}


class DecisionAlignmentError(ValueError):
    """Raised when policy approval or decision alignment is not trustworthy."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise DecisionAlignmentError(message)


def load_strict_json(path: Path) -> dict[str, Any]:
    return review_builder.load_strict_json(path)


def _strict_object(value: Any, label: str, fields: set[str]) -> dict[str, Any]:
    _require(isinstance(value, dict), f"{label} must be an object")
    _require(set(value) == fields, f"{label} fields differ from the schema")
    return value


def _strict_int(value: Any, label: str, minimum: int = 0) -> int:
    _require(isinstance(value, int) and not isinstance(value, bool), f"{label} must be an integer")
    _require(value >= minimum, f"{label} must be at least {minimum}")
    return value


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _object_sha256(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _source_binding(path: Path) -> dict[str, str]:
    _require(path.is_file(), f"required source is missing: {path.relative_to(REPO_ROOT)}")
    return {"path": str(path.relative_to(REPO_ROOT)), "sha256": _file_sha256(path)}


def _load_approval_builder() -> Any:
    _require(APPROVAL_GENERATOR_PATH.is_file(), "policy baseline approval generator is missing")
    module_name = "walksafe_baseline_approval_builder_for_alignment"
    spec = importlib.util.spec_from_file_location(module_name, APPROVAL_GENERATOR_PATH)
    _require(spec is not None and spec.loader is not None, "cannot load policy baseline approval generator")
    sys.modules.setdefault(
        "build_walksafe_feature_policy_baseline_review_20260721",
        review_builder,
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


def _validate_approval_artifacts(
    approval: dict[str, Any],
    manifest: dict[str, Any],
    resolution: dict[str, Any],
    document: dict[str, Any],
    answers: dict[str, Any],
) -> None:
    approval_builder = _load_approval_builder()
    approval_builder.validate_approval_record(approval, resolution)
    approval_builder.validate_baseline_manifest(manifest, approval)
    expected_approval = approval_builder.build_approval_record()
    expected_manifest = approval_builder.build_baseline_manifest(expected_approval)
    _require(approval == expected_approval, "stored approval record differs from the approval evidence")
    _require(manifest == expected_manifest, "stored baseline manifest differs from the approval record")
    _require(APPROVAL_RECORD_PATH.read_bytes() == approval_builder._json_bytes(expected_approval), "approval record bytes are stale")
    _require(BASELINE_MANIFEST_PATH.read_bytes() == approval_builder._json_bytes(expected_manifest), "baseline manifest bytes are stale")

    approved = approval.get("approved_baseline_payload")
    _require(isinstance(approved, dict), "approved baseline payload is missing")
    _require(approval.get("approved_baseline_payload_sha256") == _object_sha256(approved), "approved baseline payload hash differs")
    _require(manifest.get("baseline_payload") == approved, "manifest baseline payload differs from the approval record")
    _require(manifest.get("baseline_payload_sha256") == _object_sha256(approved), "manifest baseline payload hash differs")
    _require(approved.get("baseline_id") == "PB-WALKSAFE-FEATURE-POLICY-1.0.0", "approved baseline ID differs")
    _require(approved.get("baseline_version") == "1.0.0", "approved baseline version differs")
    _require(approved.get("scope") == "POLICY_BASELINE_ONLY", "approved baseline scope differs")
    _require(approved.get("approval_target") == resolution["handoff"]["approval_target"], "approval target differs from the review resolution")
    _require(approved.get("review_summary") == resolution["review_summary"], "approved review summary differs")
    _require(approved.get("reviewed_policy") == resolution["reviewed_policy"], "approved policy binding differs")
    _require(approved.get("remaining_gates") == resolution["remaining_gates"], "approved gates differ")
    _require(approved.get("gate_binding_sha256") == _object_sha256(resolution["remaining_gates"]), "approved gate hash differs")
    review_binding = approved.get("review_resolution")
    _require(isinstance(review_binding, dict), "approved review resolution binding is missing")
    _require(review_binding.get("resolution_id") == resolution["metadata"]["resolution_id"], "approved resolution ID differs")
    _require(review_binding.get("resolution_version") == resolution["metadata"]["resolution_version"], "approved resolution version differs")
    _require(review_binding.get("file_sha256") == _file_sha256(REVIEW_RESOLUTION_PATH), "approved resolution file hash differs")
    _require(review_binding.get("content_sha256") == resolution["resolution_content_sha256"], "approved resolution content hash differs")
    _require(approved["approval_target"].get("document_id") == document["metadata"]["document_id"], "approved document ID differs")
    _require(approved["approval_target"].get("document_version") == document["metadata"]["document_version"], "approved document version differs")
    _require(approved["approval_target"].get("document_content_sha256") == document["document_content_sha256"], "approved document content hash differs")
    _require(approved["approval_target"].get("decision_binding_sha256") == resolution["decision_binding_sha256"], "approved decisions differ")
    _require(approved["approval_target"].get("controlled_answer_sha256") == _file_sha256(ANSWERS_PATH), "approved answers differ")
    _require(
        manifest.get("approval_binding", {}).get("approval_record_file_sha256") == _file_sha256(APPROVAL_RECORD_PATH),
        "manifest approval record file hash differs",
    )
    _require(
        manifest.get("approval_binding", {}).get("approval_record_content_sha256") == approval["approval_record_content_sha256"],
        "manifest approval record content hash differs",
    )
    _require(answers.get("baseline_status") == "NOT_APPROVED", "controlled review answers were rewritten as approval")
    boundary = approval.get("approval_boundary")
    _require(
        isinstance(boundary, dict)
        and boundary.get("content_approval_status") == "APPROVED"
        and boundary.get("baseline_status") == "APPROVED"
        and boundary.get("baseline_approval_recorded") is True
        and boundary.get("formal_deliverables_authorized") is True
        and boundary.get("formal_deliverable_generation_status") == "NOT_RUN"
        and boundary.get("implementation_completion_claimed") is False
        and boundary.get("test_completion_claimed") is False
        and boundary.get("remaining_gates_are_waived") is False
        and boundary.get("release_status") == "NOT_ELIGIBLE",
        "approval boundary permits unsupported completion or release",
    )
    establishment = manifest.get("establishment_boundary")
    _require(
        isinstance(establishment, dict)
        and establishment.get("baseline_status") == "BASELINED"
        and establishment.get("verification_status") == "NOT_RUN"
        and establishment.get("remaining_gates_are_waived") is False
        and establishment.get("release_status") == "NOT_ELIGIBLE",
        "manifest establishment boundary differs",
    )
    _require(manifest.get("remaining_gates") == approval.get("remaining_gates"), "manifest gates differ from approval")
    _require(approval.get("remaining_gates") == resolution["remaining_gates"], "approval gates differ from the resolution")
    _require(approval.get("gate_binding_sha256") == _object_sha256(resolution["remaining_gates"]), "approval gate binding differs")
    _require(manifest.get("gate_binding_sha256") == _object_sha256(resolution["remaining_gates"]), "manifest gate binding differs")


def _validate_predecessor_register(register: dict[str, Any], feature_ids: list[str]) -> None:
    _strict_object(
        register,
        "predecessor register",
        {
            "schema_version",
            "artifact_type_code",
            "artifact_instance_id",
            "candidate_id",
            "document_version",
            "as_of",
            "lifecycle_status",
            "approval_boundary",
            "source_bindings",
            "input_binding_sha256",
            "decisions",
            "queue_summary",
            "approval_blockers",
            "coverage",
        },
    )
    _require(register.get("schema_version") == "walksafe.effective-decision-register.v1", "predecessor schema differs")
    _require(register.get("artifact_type_code") == "DLV-MGT-15", "predecessor artifact type differs")
    _require(register.get("artifact_instance_id") == "ART-MGT-DECISIONS-001", "predecessor artifact instance differs")
    _require(register.get("document_version") == "0.1.0", "predecessor version differs")
    _require(register.get("lifecycle_status") == "IN_REVIEW", "predecessor lifecycle differs")
    boundary = register.get("approval_boundary")
    _require(isinstance(boundary, dict), "predecessor approval boundary is missing")
    _require(boundary.get("baseline_status") == "NOT_APPROVED", "predecessor already claims baseline approval")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "predecessor allows release")
    _require(boundary.get("baseline_approval_recorded") is False, "predecessor records approval")

    bindings = register.get("source_bindings")
    _require(isinstance(bindings, list) and len(bindings) == 13, "predecessor source bindings differ")
    source_ids: set[str] = set()
    for index, binding in enumerate(bindings):
        current = _strict_object(
            binding,
            f"predecessor source binding {index}",
            {"id", "path", "sha256", "source_class", "artifact_form", "evidence_classification", "adoption_trust"},
        )
        source_id = current.get("id")
        _require(isinstance(source_id, str) and source_id not in source_ids, "predecessor source ID is invalid or duplicated")
        source_ids.add(source_id)
        relative = current.get("path")
        _require(isinstance(relative, str) and not Path(relative).is_absolute(), f"predecessor source path is invalid: {source_id}")
        source_path = (REPO_ROOT / relative).resolve()
        _require(source_path.is_relative_to(REPO_ROOT.resolve()), f"predecessor source escapes repository: {source_id}")
        source_hash = current.get("sha256")
        _require(
            isinstance(source_hash, str)
            and len(source_hash) == 64
            and all(character in "0123456789abcdef" for character in source_hash),
            f"predecessor source hash is invalid: {source_id}",
        )
    _require(
        register.get("input_binding_sha256") == _object_sha256({item["id"]: item["sha256"] for item in bindings}),
        "predecessor input binding hash differs",
    )

    decisions = register.get("decisions")
    _require(isinstance(decisions, list) and len(decisions) == 135, "predecessor decision count differs")
    _require([item.get("register_order") for item in decisions] == list(range(1, 136)), "predecessor decision order differs")
    decision_ids: list[str] = []
    canonical_ids: list[str] = []
    known_features = set(feature_ids)
    for index, decision in enumerate(decisions):
        current = _strict_object(decision, f"predecessor decision {index}", DECISION_FIELDS)
        _strict_int(current.get("register_order"), f"predecessor decision order {index}", minimum=1)
        decision_id = current.get("decision_id")
        canonical_id = current.get("canonical_decision_id")
        _require(isinstance(decision_id, str) and decision_id.startswith("DEC-"), f"invalid decision ID at {index}")
        _require(isinstance(canonical_id, str) and canonical_id.startswith("CD-"), f"invalid canonical ID at {index}")
        decision_ids.append(decision_id)
        canonical_ids.append(canonical_id)
        _require(current.get("decision_version") == "0.1.0", f"predecessor decision version differs: {decision_id}")
        _require(current.get("lifecycle_status") == "IN_REVIEW", f"predecessor decision lifecycle differs: {decision_id}")
        _require(current.get("approved_by") is None and current.get("approved_at") is None, f"predecessor decision is approved: {decision_id}")
        _require(current.get("resolution_status") in EXPECTED_SOURCE_STATUS_COUNTS, f"unknown predecessor status: {decision_id}")
        affected = current.get("affected_feature_ids")
        _require(isinstance(affected, list) and bool(affected), f"predecessor decision has no features: {decision_id}")
        _require(len(affected) == len(set(affected)) and set(affected) <= known_features, f"predecessor decision feature refs differ: {decision_id}")
        expected_content = _object_sha256(
            {key: value for key, value in current.items() if key not in {"approved_by", "approved_at", "content_sha256"}}
        )
        _require(current.get("content_sha256") == expected_content, f"predecessor decision hash differs: {decision_id}")
    _require(len(decision_ids) == len(set(decision_ids)) == 135, "predecessor decision IDs are duplicated")
    _require(len(canonical_ids) == len(set(canonical_ids)) == 135, "predecessor canonical IDs are duplicated")
    _require(dict(sorted(Counter(item["resolution_status"] for item in decisions).items())) == EXPECTED_SOURCE_STATUS_COUNTS, "predecessor status counts differ")
    _require(sum(len(item["affected_feature_ids"]) for item in decisions) == 428, "predecessor feature edges differ")
    coverage = register.get("coverage")
    _require(isinstance(coverage, dict), "predecessor coverage is missing")
    _require(coverage.get("actual_decisions") == coverage.get("expected_canonical_decisions") == 135, "predecessor coverage differs")
    _require(coverage.get("missing_decision_ids") == [] and coverage.get("duplicate_decision_ids") == [], "predecessor reports missing decisions")


def _validate_policy_graph(
    document: dict[str, Any],
    register: dict[str, Any],
    resolution: dict[str, Any],
    answers: dict[str, Any],
) -> dict[str, Any]:
    common_ids, feature_ids = review_builder._validate_document(document)
    review_builder._validate_html_binding(document)
    summary = review_builder._validate_answers(answers, document, common_ids, feature_ids)
    _require(summary["item_count"] == 63 and summary["confirmed"] == 63, "final policy review is not 63/63 confirmed")
    _require(summary["revision_requested"] == summary["held"] == summary["non_empty_note_count"] == 0, "final policy review has unresolved input")
    _require([item["id"] for item in answers["items"]] == [*common_ids, *feature_ids], "final confirmation order differs")
    _validate_predecessor_register(register, feature_ids)

    decisions = register["decisions"]
    catalog = document.get("decision_catalog")
    _require(isinstance(catalog, list) and len(catalog) == 135, "decision catalog count differs")
    _require([item.get("decision_id") for item in catalog] == [item["decision_id"] for item in decisions], "decision catalog order differs")
    _require([item.get("canonical_decision_id") for item in catalog] == [item["canonical_decision_id"] for item in decisions], "canonical catalog order differs")
    for source, item in zip(decisions, catalog):
        _require(item.get("title") == source["title"], f"decision catalog title differs: {source['decision_id']}")
        _require(item.get("register_statement_before_review") == source["decision_statement"], f"decision catalog prior statement differs: {source['decision_id']}")
        _require(item.get("register_status_before_review") == source["resolution_status"], f"decision catalog prior status differs: {source['decision_id']}")
        _require(item.get("affected_feature_ids") == source["affected_feature_ids"], f"decision catalog features differ: {source['decision_id']}")
        _require(item.get("feature_policy_refs") == [f"{feature_id}-POLICY-001" for feature_id in source["affected_feature_ids"]], f"decision catalog policy refs differ: {source['decision_id']}")
        _require(item.get("mapping_granularity") == "FEATURE_POLICY_BUNDLE", f"decision mapping is not bundle-level: {source['decision_id']}")
        _require(item.get("formal_register_update_status") == "NOT_APPLIED", f"source catalog was already updated: {source['decision_id']}")
    _require(sum(len(item["affected_feature_ids"]) for item in catalog) == 428, "decision catalog edge count differs")

    features = document.get("features")
    _require(isinstance(features, list) and [item.get("id") for item in features] == feature_ids, "feature policy coverage differs")
    for feature in features:
        expected_decisions = {
            item["decision_id"] for item in decisions if feature["id"] in item["affected_feature_ids"]
        }
        _require(set(feature["traceability"]["decision_ids"]) == expected_decisions, f"reverse decision trace differs: {feature['id']}")

    common = document.get("common_policies")
    _require(isinstance(common, list) and len(common) == 9, "common policy count differs")
    _require([item.get("id") for item in common] == common_ids, "common policy order differs")
    _require(sum(len(item["affected_feature_ids"]) for item in common) == 144, "common policy feature edges differ")
    _require(all(set(item["affected_feature_ids"]) <= set(feature_ids) for item in common), "common policy has an unknown feature")

    application_log = document.get("review_application_log")
    _require(isinstance(application_log, list) and len(application_log) == 76, "review application count differs")
    expected_review_ids = {
        *[f"GP-{index:02d}" for index in range(1, 8)],
        *[f"SP-{index:02d}" for index in range(1, 16)],
        *feature_ids,
    }
    _require({item.get("review_id") for item in application_log} == expected_review_ids, "review application IDs differ")
    _require(Counter(item.get("decision") for item in application_log) == {"accept": 54, "revise": 22}, "review application decisions differ")
    _require(all(item.get("application_status") == "APPLIED_TO_REVIEW_DRAFT" for item in application_log), "review application was not applied")
    known_policy_refs = {
        *[item["policy_clause_id"] for item in features],
        *[item["id"] for item in document["reviewed_cross_feature_policies"]],
    }
    _require(all(item.get("active_policy_refs") and set(item["active_policy_refs"]) <= known_policy_refs for item in application_log), "review application has an unknown policy ref")

    document_gates = document.get("remaining_gates")
    enriched_gates = resolution.get("remaining_gates")
    _require(isinstance(document_gates, list) and isinstance(enriched_gates, list), "remaining gates are missing")
    _require(len(document_gates) == len(enriched_gates) == 5, "remaining gate count differs")
    _require([item["id"] for item in document_gates] == [item["id"] for item in enriched_gates], "remaining gate order differs")
    _require(all(item.get("status") == "NOT_RUN" for item in document_gates + enriched_gates), "remaining gate was closed")
    _require(sum(len(item["affected_feature_ids"]) for item in enriched_gates) == 42, "remaining gate feature edges differ")
    expected_gate_features = {item["id"]: item["affected_feature_ids"] for item in document_gates}
    actual_gate_features = {
        gate_id: [
            feature["id"]
            for feature in features
            if gate_id in {item["id"] for item in feature["remaining_gates"]}
        ]
        for gate_id in expected_gate_features
    }
    _require(actual_gate_features == expected_gate_features, "feature-to-gate reverse trace differs")

    details = [(feature["id"], item) for feature in features for item in feature["detailed_decisions"]]
    detail_ids = [item["id"] for _, item in details]
    _require(len(details) == len(set(detail_ids)) == 134, "detailed policy decision count or IDs differ")
    detail_counts = Counter(item["status"] for _, item in details)
    _require(detail_counts == {"POLICY_DECIDED": 29, "POLICY_METHOD_DECIDED_EVIDENCE_PENDING": 105}, "detailed decision states differ")
    _require(all(item.get("interim_rule") for _, item in details if item["status"] == "POLICY_METHOD_DECIDED_EVIDENCE_PENDING"), "evidence-pending detail lacks an interim rule")
    _require(document["summary"].get("implementation_revalidation_feature_count") == 50, "implementation revalidation count differs")
    _require(document["summary"].get("remaining_policy_conflict_count") == 0, "approved policy reports a conflict")
    _require(document["coverage"].get("policy_conflicts_remaining") == [], "approved policy retains a conflict")
    _require(all(feature["policy_state"]["policy_conflict_status"] == "CALCULATED_NONE" for feature in features), "feature policy conflict remains")

    return {
        "common_ids": common_ids,
        "feature_ids": feature_ids,
        "summary": summary,
        "details": details,
    }


def _load_and_validate_sources() -> dict[str, Any]:
    _require(APPROVAL_RECORD_PATH.is_file(), "policy baseline approval record is missing")
    _require(BASELINE_MANIFEST_PATH.is_file(), "policy baseline manifest is missing")
    resolution = load_strict_json(REVIEW_RESOLUTION_PATH)
    review_builder.validate_resolution(resolution)
    document = load_strict_json(POLICY_DOCUMENT_PATH)
    answers = load_strict_json(ANSWERS_PATH)
    register = load_strict_json(PREDECESSOR_REGISTER_PATH)
    approval = load_strict_json(APPROVAL_RECORD_PATH)
    manifest = load_strict_json(BASELINE_MANIFEST_PATH)
    graph = _validate_policy_graph(document, register, resolution, answers)
    _validate_approval_artifacts(approval, manifest, resolution, document, answers)
    return {
        "resolution": resolution,
        "document": document,
        "answers": answers,
        "register": register,
        "approval": approval,
        "manifest": manifest,
        "graph": graph,
    }


def _transition_summary(register: dict[str, Any]) -> list[dict[str, Any]]:
    counts = Counter(item["resolution_status"] for item in register["decisions"])
    return [
        {
            "prior_resolution_status": status,
            "decision_count": counts[status],
            "policy_alignment_result": TRANSITIONS[status],
            "verification_completion_claimed": False,
        }
        for status in EXPECTED_SOURCE_STATUS_COUNTS
    ]


def _build_aligned_decisions(sources: dict[str, Any]) -> list[dict[str, Any]]:
    register = sources["register"]
    document = sources["document"]
    approval = sources["approval"]
    catalog_by_id = {item["decision_id"]: item for item in document["decision_catalog"]}
    gate_ids_by_feature: dict[str, list[str]] = {feature_id: [] for feature_id in sources["graph"]["feature_ids"]}
    for gate in sources["resolution"]["remaining_gates"]:
        for feature_id in gate["affected_feature_ids"]:
            gate_ids_by_feature[feature_id].append(gate["id"])

    aligned: list[dict[str, Any]] = []
    for prior in register["decisions"]:
        catalog = catalog_by_id[prior["decision_id"]]
        bundle_gate_refs = [
            {"feature_id": feature_id, "gate_ids": gate_ids_by_feature[feature_id]}
            for feature_id in prior["affected_feature_ids"]
            if gate_ids_by_feature[feature_id]
        ]
        record: dict[str, Any] = {
            "decision_id": prior["decision_id"],
            "register_order": prior["register_order"],
            "canonical_decision_id": prior["canonical_decision_id"],
            "decision_version": "1.0.0",
            "title": prior["title"],
            "prior_state": {
                "register_path": str(PREDECESSOR_REGISTER_PATH.relative_to(REPO_ROOT)),
                "register_document_version": register["document_version"],
                "decision_version": prior["decision_version"],
                "decision_content_sha256": prior["content_sha256"],
                "resolution_status": prior["resolution_status"],
                "responsibility_type": prior["responsibility_type"],
                "normalization_status": prior["normalization_status"],
                "verification_gate_kind": prior["verification_gate"]["kind"],
                "verification_gate_status": prior["verification_gate"]["status"],
            },
            "effective_value_type": "POLICY_CLAUSE_SET",
            "effective_value": {
                "policy_baseline_id": approval["approved_baseline_payload"]["baseline_id"],
                "policy_baseline_version": approval["approved_baseline_payload"]["baseline_version"],
                "policy_clause_refs": catalog["feature_policy_refs"],
            },
            "mapping_granularity": "FEATURE_POLICY_BUNDLE",
            "baseline_confirmation_refs": prior["affected_feature_ids"],
            "policy_resolution_status": "BASELINED",
            "policy_alignment_status": "ALIGNED_TO_APPROVED_POLICY_BASELINE",
            "formal_register_update_status": "APPLIED",
            "legacy_transition": TRANSITIONS[prior["resolution_status"]],
            "verification_tracking": {
                "status": "TRACKED_BY_REFERENCED_POLICY_BUNDLES",
                "completion_claimed": False,
                "relation_to_gate_refs": "TRANSITIVE_FEATURE_POLICY_BUNDLE_ONLY",
                "feature_policy_gate_refs": bundle_gate_refs,
            },
            "affected_feature_ids": prior["affected_feature_ids"],
            "affected_artifact_type_ids": prior["affected_artifact_type_ids"],
            "affected_bundle_ids": prior["affected_bundle_ids"],
            "legacy_source_refs": {
                "source_refs": prior["source_refs"],
                "supersedes_source_refs": prior["supersedes_source_refs"],
                "normalization_source_refs": prior["normalization_source_refs"],
            },
            "baseline_approval_ref": {
                "approval_record_id": approval["metadata"]["approval_record_id"],
                "approval_record_version": approval["metadata"]["approval_record_version"],
                "approval_record_content_sha256": approval["approval_record_content_sha256"],
                "approval_scope": "POLICY_BASELINE_ONLY",
                "individual_decision_approval_claimed": False,
            },
        }
        record["content_sha256"] = _object_sha256(record)
        aligned.append(record)
    return aligned


def _compose_alignment(sources: dict[str, Any]) -> dict[str, Any]:
    register = sources["register"]
    document = sources["document"]
    resolution = sources["resolution"]
    approval = sources["approval"]
    manifest = sources["manifest"]
    graph = sources["graph"]
    aligned_decisions = _build_aligned_decisions(sources)
    source_bindings = {
        "generator": _source_binding(GENERATOR_PATH),
        "predecessor_register": _source_binding(PREDECESSOR_REGISTER_PATH),
        "approved_policy_json": _source_binding(POLICY_DOCUMENT_PATH),
        "approved_policy_html": _source_binding(POLICY_HTML_PATH),
        "baseline_review_resolution": _source_binding(REVIEW_RESOLUTION_PATH),
        "controlled_review_answers": _source_binding(ANSWERS_PATH),
        "policy_baseline_approval_record": _source_binding(APPROVAL_RECORD_PATH),
        "policy_baseline_manifest": _source_binding(BASELINE_MANIFEST_PATH),
    }
    common_policy_bindings = [
        {
            "id": item["id"],
            "json_pointer": f"/common_policies/{index}",
            "object_sha256": _object_sha256(item),
            "affected_feature_ids": item["affected_feature_ids"],
            "source_review_ids": item["source_review_ids"],
            "baseline_confirmation_ref": item["id"],
        }
        for index, item in enumerate(document["common_policies"])
    ]
    policy_decided_refs = [
        f"{feature_id}#{detail['id']}"
        for feature_id, detail in graph["details"]
        if detail["status"] == "POLICY_DECIDED"
    ]
    evidence_pending_refs = [
        f"{feature_id}#{detail['id']}"
        for feature_id, detail in graph["details"]
        if detail["status"] == "POLICY_METHOD_DECIDED_EVIDENCE_PENDING"
    ]
    alignment: dict[str, Any] = {
        "schema_version": "walksafe.effective-decision-register-aligned.v1",
        "metadata": {
            "register_id": "WS-EFFECTIVE-DECISION-REGISTER-ALIGNED-20260721-001",
            "register_version": "1.0.0",
            "controlled_revision": 1,
            "artifact_type_code": "DLV-MGT-15",
            "artifact_instance_id": register["artifact_instance_id"],
            "as_of": "2026-07-21",
            "lifecycle_status": "IN_REVIEW",
            "supersession_status": "PROPOSED",
        },
        "approval_boundary": {
            "source_policy_baseline_status": "BASELINE_APPROVED",
            "policy_alignment_status": "COMPLETE",
            "artifact_approval_status": "NOT_APPROVED",
            "formal_deliverable_generation_authorized": True,
            "formal_deliverable_generation_status": "IN_PROGRESS",
            "remaining_gates_are_waived": False,
            "implementation_completion_claimed": False,
            "test_completion_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": source_bindings,
        "source_binding_sha256": _object_sha256(source_bindings),
        "predecessor_binding": {
            "path": str(PREDECESSOR_REGISTER_PATH.relative_to(REPO_ROOT)),
            "file_sha256": _file_sha256(PREDECESSOR_REGISTER_PATH),
            "artifact_instance_id": register["artifact_instance_id"],
            "candidate_id": register["candidate_id"],
            "document_version": register["document_version"],
            "decision_set_sha256": _object_sha256(register["decisions"]),
            "superseded_by_this_candidate": False,
        },
        "approved_baseline_binding": {
            "baseline_id": manifest["metadata"]["baseline_id"],
            "baseline_version": manifest["metadata"]["baseline_version"],
            "manifest_content_sha256": manifest["manifest_content_sha256"],
            "approval_record_id": approval["metadata"]["approval_record_id"],
            "approval_record_content_sha256": approval["approval_record_content_sha256"],
            "policy_document_id": document["metadata"]["document_id"],
            "policy_document_version": document["metadata"]["document_version"],
            "policy_document_content_sha256": document["document_content_sha256"],
            "review_resolution_id": resolution["metadata"]["resolution_id"],
            "review_resolution_version": resolution["metadata"]["resolution_version"],
            "review_resolution_file_sha256": _file_sha256(REVIEW_RESOLUTION_PATH),
            "review_resolution_content_sha256": resolution["resolution_content_sha256"],
            "decision_binding_sha256": resolution["decision_binding_sha256"],
            "controlled_answer_sha256": _file_sha256(ANSWERS_PATH),
        },
        "review_confirmation": {
            "item_count": 63,
            "common_policy_count": 9,
            "feature_policy_count": 54,
            "confirmed": 63,
            "revision_requested": 0,
            "held": 0,
            "confirmation_ids": [item["id"] for item in sources["answers"]["items"]],
            "decision_records_sha256": resolution["decision_binding_sha256"],
        },
        "review_application_binding": {
            "record_count": 76,
            "accept_count": 54,
            "revise_count": 22,
            "source_status": "APPLIED_TO_REVIEW_DRAFT",
            "baseline_inclusion_status": "INCLUDED_IN_APPROVED_POLICY_BASELINE",
            "application_log_sha256": _object_sha256(document["review_application_log"]),
        },
        "common_policy_bindings": common_policy_bindings,
        "common_policy_binding_sha256": _object_sha256(common_policy_bindings),
        "remaining_gates": copy.deepcopy(resolution["remaining_gates"]),
        "remaining_gate_binding_sha256": _object_sha256(resolution["remaining_gates"]),
        "evidence_work_summary": {
            "detailed_decision_count": 134,
            "policy_decided_count": 29,
            "evidence_pending_count": 105,
            "policy_decided_refs": policy_decided_refs,
            "evidence_pending_refs": evidence_pending_refs,
            "detail_ref_binding_sha256": _object_sha256(
                {"policy_decided_refs": policy_decided_refs, "evidence_pending_refs": evidence_pending_refs}
            ),
            "implementation_revalidation_feature_count": 50,
            "verification_completion_claimed": False,
        },
        "transition_summary": _transition_summary(register),
        "decisions": aligned_decisions,
        "decision_binding_sha256": _object_sha256(aligned_decisions),
        "coverage": {
            "expected_decision_count": 135,
            "actual_decision_count": len(aligned_decisions),
            "unique_canonical_decision_count": len({item["canonical_decision_id"] for item in aligned_decisions}),
            "decision_feature_edge_count": sum(len(item["affected_feature_ids"]) for item in aligned_decisions),
            "confirmed_feature_policy_count": 54,
            "confirmed_common_policy_count": 9,
            "common_policy_feature_edge_count": sum(len(item["affected_feature_ids"]) for item in document["common_policies"]),
            "review_application_count": len(document["review_application_log"]),
            "remaining_gate_count": len(resolution["remaining_gates"]),
            "remaining_gate_feature_edge_count": sum(len(item["affected_feature_ids"]) for item in resolution["remaining_gates"]),
            "policy_conflict_count": document["summary"]["remaining_policy_conflict_count"],
            "missing_decision_ids": [],
            "duplicate_decision_ids": [],
            "orphan_feature_refs": [],
            "unconfirmed_policy_refs": [],
        },
    }
    alignment["register_content_sha256"] = _object_sha256(alignment)
    return alignment


def validate_alignment(alignment: dict[str, Any], sources: dict[str, Any] | None = None) -> None:
    if sources is None:
        sources = _load_and_validate_sources()
    _strict_object(
        alignment,
        "aligned register",
        {
            "schema_version",
            "metadata",
            "approval_boundary",
            "source_bindings",
            "source_binding_sha256",
            "predecessor_binding",
            "approved_baseline_binding",
            "review_confirmation",
            "review_application_binding",
            "common_policy_bindings",
            "common_policy_binding_sha256",
            "remaining_gates",
            "remaining_gate_binding_sha256",
            "evidence_work_summary",
            "transition_summary",
            "decisions",
            "decision_binding_sha256",
            "coverage",
            "register_content_sha256",
        },
    )
    _require(alignment.get("schema_version") == "walksafe.effective-decision-register-aligned.v1", "aligned register schema differs")
    _require(alignment.get("source_binding_sha256") == _object_sha256(alignment["source_bindings"]), "aligned source binding hash differs")
    _require(alignment.get("common_policy_binding_sha256") == _object_sha256(alignment["common_policy_bindings"]), "common policy binding hash differs")
    _require(alignment.get("remaining_gate_binding_sha256") == _object_sha256(alignment["remaining_gates"]), "remaining gate binding hash differs")
    decisions = alignment.get("decisions")
    _require(isinstance(decisions, list) and len(decisions) == 135, "aligned decision count differs")
    for index, decision in enumerate(decisions):
        current = _strict_object(decision, f"aligned decision {index}", ALIGNED_DECISION_FIELDS)
        _require(current.get("content_sha256") == _object_sha256({key: value for key, value in current.items() if key != "content_sha256"}), f"aligned decision hash differs: {current.get('decision_id')}")
        _require(current.get("policy_resolution_status") == "BASELINED", f"decision is not policy-baselined: {current.get('decision_id')}")
        _require(current.get("formal_register_update_status") == "APPLIED", f"decision alignment is not applied: {current.get('decision_id')}")
        _require(current["verification_tracking"].get("completion_claimed") is False, f"decision claims verification completion: {current.get('decision_id')}")
    _require(alignment.get("decision_binding_sha256") == _object_sha256(decisions), "aligned decision binding hash differs")
    boundary = alignment.get("approval_boundary")
    _require(isinstance(boundary, dict), "aligned approval boundary is missing")
    _require(boundary.get("source_policy_baseline_status") == "BASELINE_APPROVED", "source policy baseline is not approved")
    _require(boundary.get("artifact_approval_status") == "NOT_APPROVED", "aligned artifact claims approval")
    _require(boundary.get("remaining_gates_are_waived") is False, "aligned register waives gates")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "aligned register allows release")
    _require(all(item.get("status") == "NOT_RUN" for item in alignment["remaining_gates"]), "aligned register closes a remaining gate")
    expected_content = _object_sha256({key: value for key, value in alignment.items() if key != "register_content_sha256"})
    _require(alignment.get("register_content_sha256") == expected_content, "aligned register content hash differs")
    expected = _compose_alignment(sources)
    _require(alignment == expected, "aligned register differs from the approved policy transformation")


def build_alignment() -> dict[str, Any]:
    sources = _load_and_validate_sources()
    alignment = _compose_alignment(sources)
    validate_alignment(alignment, sources)
    return alignment


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if the aligned register is missing or stale")
    args = parser.parse_args(argv)
    try:
        alignment = build_alignment()
        content = _json_bytes(alignment)
        if args.check:
            _require(OUTPUT_PATH.is_file(), "aligned decision register is missing")
            _require(OUTPUT_PATH.read_bytes() == content, "aligned decision register is stale")
        else:
            OUTPUT_PATH.write_bytes(content)
    except (DecisionAlignmentError, review_builder.BaselineReviewError, OSError, KeyError, TypeError, ValueError) as exc:
        print(f"WalkSafe decision register alignment failed: {exc}", file=sys.stderr)
        return 1
    if args.check:
        print("WalkSafe decision register alignment check passed: 135 decisions, 428 edges, release NOT_ELIGIBLE")
    else:
        print(OUTPUT_PATH.relative_to(REPO_ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
