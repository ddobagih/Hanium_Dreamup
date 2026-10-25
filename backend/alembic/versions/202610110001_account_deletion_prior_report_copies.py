"""Allow deletion worker to count prior report copies without recipient details."""
from alembic import op

revision = "202610110001"
down_revision = "202610080001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        "GRANT SELECT (privacy_subject_hmac, account_generation, external_copy_count) "
        "ON TABLE public.report_deletion_tombstones TO walksafe_account_deletion_worker"
    )
    op.execute(
        "GRANT SELECT (id, privacy_subject_hmac, account_generation, external_copy_count) "
        "ON TABLE public.report_deletion_tombstones TO walksafe_report_evidence_owner"
    )
    op.execute(
        "GRANT SELECT (id, deletion_tombstone_id) ON TABLE "
        "public.report_deletion_external_copy_states TO walksafe_report_evidence_owner"
    )
    op.execute(
        "GRANT SELECT (id, external_copy_state_id, revision, state) ON TABLE "
        "public.report_deletion_external_copy_events TO walksafe_report_evidence_owner"
    )
    op.execute("""
        CREATE FUNCTION public.walksafe_account_prior_report_copy_inventory(
            p_subject text, p_generation bigint
        ) RETURNS TABLE(total_count bigint, pending_count bigint, consistent boolean)
        LANGUAGE sql STABLE SECURITY DEFINER
        SET search_path = pg_catalog
        AS $inventory$
            WITH copies AS (
                SELECT tombstone.id, tombstone.external_copy_count,
                       count(copy.id) AS inventoried,
                       count(copy.id) FILTER (
                           WHERE latest.state IS DISTINCT FROM 'REPLY_DELETION_CONFIRMED'
                       ) AS pending
                  FROM public.report_deletion_tombstones AS tombstone
                  LEFT JOIN public.report_deletion_external_copy_states AS copy
                    ON copy.deletion_tombstone_id = tombstone.id
                  LEFT JOIN LATERAL (
                       SELECT event.state
                         FROM public.report_deletion_external_copy_events AS event
                        WHERE event.external_copy_state_id = copy.id
                        ORDER BY event.revision DESC, event.id DESC LIMIT 1
                  ) AS latest ON true
                 WHERE tombstone.privacy_subject_hmac = p_subject
                   AND tombstone.account_generation = p_generation
                 GROUP BY tombstone.id, tombstone.external_copy_count
            )
            SELECT coalesce(sum(external_copy_count), 0)::bigint,
                   coalesce(sum(pending), 0)::bigint,
                   coalesce(bool_and(external_copy_count = inventoried), true)
              FROM copies
        $inventory$
    """)
    signature = "public.walksafe_account_prior_report_copy_inventory(text, bigint)"
    op.execute(f"ALTER FUNCTION {signature} OWNER TO walksafe_report_evidence_owner")
    op.execute(f"REVOKE ALL ON FUNCTION {signature} FROM PUBLIC")
    op.execute(f"GRANT EXECUTE ON FUNCTION {signature} TO walksafe_account_deletion_worker")


def downgrade() -> None:
    op.execute("DROP FUNCTION IF EXISTS public.walksafe_account_prior_report_copy_inventory(text, bigint)")
    op.execute(
        "REVOKE SELECT (id, privacy_subject_hmac, account_generation, external_copy_count) "
        "ON TABLE public.report_deletion_tombstones FROM walksafe_report_evidence_owner"
    )
    op.execute(
        "REVOKE SELECT (id, deletion_tombstone_id) ON TABLE "
        "public.report_deletion_external_copy_states FROM walksafe_report_evidence_owner"
    )
    op.execute(
        "REVOKE SELECT (id, external_copy_state_id, revision, state) ON TABLE "
        "public.report_deletion_external_copy_events FROM walksafe_report_evidence_owner"
    )
    op.execute(
        "REVOKE SELECT (privacy_subject_hmac, account_generation, external_copy_count) "
        "ON TABLE public.report_deletion_tombstones FROM walksafe_account_deletion_worker"
    )
