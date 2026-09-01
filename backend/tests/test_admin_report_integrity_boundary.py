from __future__ import annotations

import pytest

import backend.app.services.admin_report_delivery_package as delivery_package
from backend.app.services.admin_report_integrity import (
    ADMIN_REPORT_INTEGRITY_COMPATIBLE_REVISIONS,
    ADMIN_REPORT_INTEGRITY_HEAD,
    ADMIN_REPORT_INTEGRITY_LEGACY_REVISIONS,
    admin_report_integrity_boundary_state,
)
import backend.app.services.admin_report_workflow as report_workflow
import backend.app.services.report_original_access as original_access


@pytest.mark.parametrize(
    ("revision", "expected"),
    [
        ("202608300004", None),
        ("202608300003", False),
        ("202608299999", False),
        ("future-branch", False),
    ],
)
def test_only_known_predecessors_may_use_legacy_compatibility(
    revision: str | None,
    expected: bool | None,
) -> None:
    class NoQueryExecutor:
        def execute(self, _statement):
            raise AssertionError("non-head revisions must not query the boundary")

    assert (
        admin_report_integrity_boundary_state(
            NoQueryExecutor(),
            revision=revision,
        )
        is expected
    )

    assert ADMIN_REPORT_INTEGRITY_HEAD == "202608300005"
    assert ADMIN_REPORT_INTEGRITY_LEGACY_REVISIONS == {"202608300004"}
    assert ADMIN_REPORT_INTEGRITY_COMPATIBLE_REVISIONS == {
        "202608300005",
        "202608300006",
        "202609010001",
        "202609010002",
    }


def test_known_successor_still_queries_the_head005_boundary() -> None:
    class BoundaryExecutor:
        def execute(self, statement):
            assert "walksafe_admin_report_integrity_boundary" in str(statement)

            class Result:
                @staticmethod
                def scalar_one() -> bool:
                    return True

            return Result()

    assert (
        admin_report_integrity_boundary_state(
            BoundaryExecutor(),
            revision="202609010001",
        )
        is True
    )


@pytest.mark.parametrize(
    ("module", "helper_name"),
    [
        (original_access, "_secured_original_evidence_dml_required"),
        (report_workflow, "_secured_review_dml_required"),
        (report_workflow, "_secured_delivery_event_dml_required"),
        (delivery_package, "_secured_delivery_package_dml_required"),
    ],
)
def test_head005_forces_v3_without_consulting_direct_dml(
    monkeypatch: pytest.MonkeyPatch,
    module,
    helper_name: str,
) -> None:
    monkeypatch.setattr(module, "_is_postgresql_session", lambda _db: True)
    monkeypatch.setattr(
        module,
        "admin_report_integrity_boundary_state",
        lambda _db: True,
    )

    assert getattr(module, helper_name)(object()) is True


@pytest.mark.parametrize(
    ("module", "helper_name", "error_type", "error_code"),
    [
        (
            original_access,
            "_secured_original_evidence_dml_required",
            original_access.ReportOriginalAccessError,
            "report_original_access_audit_unavailable",
        ),
        (
            report_workflow,
            "_secured_review_dml_required",
            report_workflow.AdminReportWorkflowError,
            "admin_report_workflow_unavailable",
        ),
        (
            report_workflow,
            "_secured_delivery_event_dml_required",
            report_workflow.AdminReportWorkflowError,
            "admin_report_workflow_unavailable",
        ),
        (
            delivery_package,
            "_secured_delivery_package_dml_required",
            report_workflow.AdminReportWorkflowError,
            "delivery_package_store_unavailable",
        ),
    ],
)
def test_head005_acl_drift_fails_closed_before_legacy_dml(
    monkeypatch: pytest.MonkeyPatch,
    module,
    helper_name: str,
    error_type: type[Exception],
    error_code: str,
) -> None:
    monkeypatch.setattr(module, "_is_postgresql_session", lambda _db: True)
    monkeypatch.setattr(
        module,
        "admin_report_integrity_boundary_state",
        lambda _db: False,
    )

    with pytest.raises(error_type) as rejected:
        getattr(module, helper_name)(object())

    assert rejected.value.code == error_code
    assert rejected.value.status_code == 503
