"""Reconfirmation issuer accepts every HIGH administrator operation.

classify_admin_operation marks seven administrator app operations HIGH, but
walksafe_issue_admin_reconfirmation never listed them, so on PostgreSQL their
reauthentication failed with 22023 (reported as 503) and the protected work
could never run (W20A-R2):

- admin.raw_collection.list          GET   /admin/raw-collections/quarantine
- admin.raw_collection.purpose_decide POST /admin/raw-collections/{id}/decisions
- admin.raw_collection.legal_hold    POST  /admin/raw-collections/{id}/legal-holds
- admin.report.status.update         PATCH /admin/reports/{id}/status
- admin.report.delivery_package.create POST /admin/reports/{id}/delivery-packages
- admin.report_request.status.update PATCH /admin/report-requests/{id}/status
- admin.incident.status.update       PATCH /admin/incidents/{id}/status

Each block binds the exact method and the same path regex as the Python
classifier (backend/app/services/admin_security.py); no existing block is
widened. Only this fragment of the installed function is rewritten, and the
downgrade removes exactly the same bytes.

Revision ID: 202609280002
Revises: 202609280001
Create Date: 2026-09-28
"""

from __future__ import annotations

from alembic import op


revision = "202609280002"
down_revision = "202609280001"
branch_labels = None
depends_on = None

_ISSUE_RECONFIRMATION_SIGNATURE = (
    "public.walksafe_issue_admin_reconfirmation("
    "uuid,text,uuid,text,text,text,text,text,timestamptz,timestamptz,"
    "bigint,bigint,text,text)"
)
# The device.report_lost block added by 202609280001 is the insertion anchor.
_ALLOWLIST_ANCHOR = """              OR (p_action = 'device.report_lost'
                AND p_method = 'POST'
                AND p_path ~
                  '^/admin/security/devices/[A-Za-z0-9][A-Za-z0-9._:-]{7,127}/report-lost$')
"""
_UUID = (
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
)
_HIGH_RISK_ADMIN_ALLOWLIST = f"""              OR (p_action = 'admin.raw_collection.list'
                AND p_method = 'GET'
                AND p_path = '/admin/raw-collections/quarantine')
              OR (p_action = 'admin.raw_collection.purpose_decide'
                AND p_method = 'POST'
                AND p_path ~
                  '^/admin/raw-collections/{_UUID}/decisions$')
              OR (p_action = 'admin.raw_collection.legal_hold'
                AND p_method = 'POST'
                AND p_path ~
                  '^/admin/raw-collections/{_UUID}/legal-holds$')
              OR (p_action = 'admin.report.status.update'
                AND p_method = 'PATCH'
                AND p_path ~
                  '^/admin/reports/{_UUID}/status$')
              OR (p_action = 'admin.report.delivery_package.create'
                AND p_method = 'POST'
                AND p_path ~
                  '^/admin/reports/{_UUID}/delivery-packages$')
              OR (p_action = 'admin.report_request.status.update'
                AND p_method = 'PATCH'
                AND p_path ~
                  '^/admin/report-requests/{_UUID}/status$')
              OR (p_action = 'admin.incident.status.update'
                AND p_method = 'PATCH'
                AND p_path ~
                  '^/admin/incidents/{_UUID}/status$')
"""


def _replace_issue_allowlist(old: str, new: str) -> None:
    # Rewrite only the exact allowlist fragment of the installed function so
    # every other byte of the predecessor definition (owner, ACL, SECURITY
    # DEFINER, search_path) is preserved by CREATE OR REPLACE.
    op.execute(
        f"""
        DO $migration$
        DECLARE
          v_definition text;
          v_old text := $old${old}$old$;
          v_new text := $new${new}$new$;
        BEGIN
          SELECT pg_catalog.pg_get_functiondef(
                   '{_ISSUE_RECONFIRMATION_SIGNATURE}'::regprocedure
                 )
            INTO v_definition;
          -- The fragment must occur exactly once in the installed definition.
          IF v_definition IS NULL
            OR (
              pg_catalog.length(v_definition)
              - pg_catalog.length(pg_catalog.replace(v_definition, v_old, ''))
            ) <> pg_catalog.length(v_old)
          THEN
            RAISE EXCEPTION
              'administrator reconfirmation allowlist predecessor is unexpected';
          END IF;
          EXECUTE pg_catalog.replace(v_definition, v_old, v_new);
        END
        $migration$
        """
    )


def upgrade() -> None:
    _replace_issue_allowlist(
        _ALLOWLIST_ANCHOR,
        _HIGH_RISK_ADMIN_ALLOWLIST + _ALLOWLIST_ANCHOR,
    )


def downgrade() -> None:
    _replace_issue_allowlist(
        _HIGH_RISK_ADMIN_ALLOWLIST + _ALLOWLIST_ANCHOR,
        _ALLOWLIST_ANCHOR,
    )
