from __future__ import annotations

from copy import deepcopy

from scripts import check_walksafe_gitleaks_report as subject


def policy_for(findings: list[dict[str, object]]) -> dict[str, object]:
    return {
        "schema_version": "walksafe.gitleaks-reviewed-findings.v1",
        "finding_count": len(findings),
        "canonical_findings_sha256": subject.report_sha256(findings),
    }


def test_exact_reviewed_finding_set_passes() -> None:
    findings = [
        {
            "RuleID": "generic-api-key",
            "File": "tests/fixture.py",
            "StartLine": 7,
            "Fingerprint": "tests/fixture.py:generic-api-key:7",
            "Secret": "known-invalid-fixture",
        }
    ]

    assert subject.check_report(findings, policy_for(findings)) == ()


def test_same_fingerprint_with_changed_value_is_rejected() -> None:
    reviewed = [
        {
            "RuleID": "generic-api-key",
            "File": "tests/fixture.py",
            "StartLine": 7,
            "Fingerprint": "tests/fixture.py:generic-api-key:7",
            "Secret": "known-invalid-fixture",
        }
    ]
    changed = deepcopy(reviewed)
    changed[0]["Secret"] = "different-value-at-the-same-line"

    errors = subject.check_report(changed, policy_for(reviewed))

    assert len(errors) == 1
    assert "canonical finding-set SHA-256 differs" in errors[0]


def test_added_or_removed_findings_are_rejected() -> None:
    reviewed = [{"RuleID": "fixture", "File": "a", "Secret": "one"}]
    changed = [*reviewed, {"RuleID": "fixture", "File": "b", "Secret": "two"}]

    errors = subject.check_report(changed, policy_for(reviewed))

    assert len(errors) == 2
    assert "finding count differs" in errors[0]
    assert "canonical finding-set SHA-256 differs" in errors[1]


def test_malformed_report_is_rejected_without_rendering_values() -> None:
    errors = subject.check_report(["sensitive-value"], policy_for([]))

    assert errors == ("Gitleaks report must be a list of JSON objects",)
    assert "sensitive-value" not in errors[0]
