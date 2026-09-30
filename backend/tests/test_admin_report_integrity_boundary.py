from __future__ import annotations

import importlib

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


def test_cycle_guard_downgrade_restores_exact_predecessor_definition(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    predecessor = importlib.import_module(
        "backend.alembic.versions.202608300004_admin_original_evidence_acl"
    )
    cycle_guard = importlib.import_module(
        "backend.alembic.versions.202609240001_admin_report_duplicate_cycle_guard"
    )
    statements: list[str] = []
    monkeypatch.setattr(predecessor.op, "execute", statements.append)
    predecessor._install_review_function()
    expected = statements.pop().replace("CREATE FUNCTION", "CREATE OR REPLACE FUNCTION", 1)

    cycle_guard.downgrade()

    assert len(statements) == 1
    assert statements[0].strip() == expected.strip()


@pytest.mark.parametrize(
    ("revision", "expected"),
    [
        ("202608300004", None),
        ("202608300003", False),
        ("202608299999", False),
        ("202609240003", False),
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
        "202609010003",
        "202609210001",
        "202609240001",
        "202609240002",
        "202609280001",
        "202609280002",
        "202609290001",
        "202609290002",
        "202609300001",
        "202609300002",
        "202609300003",
        "202609300004",
        "202609300005",
        "202609300006",
        "202609300007",
        "202609300008",
        "202609300009",
    }


@pytest.mark.parametrize(
    ("revision", "expected_definition_sha256", "expected_review_sha256"),
    [
        (
            "202609010002",
            "80a983dd5bb8fd3b65d102573e58167e7c26de39a7b5b8ff440b545a08c10035",
            "21f28f5e68d48e0ab9566d3f837c978ce3e147fee0c7d35131af7fc22f691c2d",
        ),
        (
            "202609010003",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "21f28f5e68d48e0ab9566d3f837c978ce3e147fee0c7d35131af7fc22f691c2d",
        ),
        (
            "202609210001",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "21f28f5e68d48e0ab9566d3f837c978ce3e147fee0c7d35131af7fc22f691c2d",
        ),
        (
            "202609240001",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
        ),
        (
            "202609240002",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
        ),
        (
            "202609280001",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
        ),
        (
            "202609280002",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
        ),
        (
            "202609290001",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
        ),
        (
            "202609290002",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
        ),
        (
            "202609300001",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
        ),
        (
            "202609300002",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
        ),
        (
            "202609300003",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
        ),
        (
            "202609300004",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
        ),
        (
            "202609300005",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
        ),
        (
            "202609300006",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
        ),
        (
            "202609300007",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
        ),
        (
            "202609300008",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
        ),
        (
            "202609300009",
            "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
            "c2fb02f571021324c9ced411354abca78ea8a25b01590c3b68c27ffc51e6cb37",
        ),
    ],
)
@pytest.mark.parametrize("boundary_ready", [True, False])
def test_known_successor_queries_its_exact_integrity_boundary(
    revision: str,
    expected_definition_sha256: str,
    expected_review_sha256: str,
    boundary_ready: bool,
) -> None:
    queries: list[str] = []

    class BoundaryExecutor:
        def execute(self, statement):
            queries.append(str(statement))
            assert "walksafe_admin_report_integrity_boundary" in str(statement)
            assert expected_definition_sha256 in str(statement)
            assert expected_review_sha256 in str(statement)
            review_hashes = {
                "21f28f5e68d48e0ab9566d3f837c978ce3e147fee0c7d35131af7fc22f691c2d",
                "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
                "c2fb02f571021324c9ced411354abca78ea8a25b01590c3b68c27ffc51e6cb37",
            }
            assert all(
                other_hash not in str(statement)
                for other_hash in review_hashes - {expected_review_sha256}
            )
            if revision == "202609300009":
                assert "report_review_duplicate_representative" in str(statement)
                assert "dab50537fbfc918e37ab8f6e693b1ea9868d421dc6c75c92131ebd1d331a43b3" in str(statement)

            class Result:
                @staticmethod
                def scalar_one() -> bool:
                    return boundary_ready

            return Result()

    assert (
        admin_report_integrity_boundary_state(
            BoundaryExecutor(),
            revision=revision,
        )
        is boundary_ready
    )
    assert len(queries) == 1


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
