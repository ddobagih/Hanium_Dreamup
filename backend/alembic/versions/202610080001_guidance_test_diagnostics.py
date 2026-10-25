"""Opt-in guidance test diagnostics and one actor rate group; no RAW/report changes."""
from alembic import op
from sqlalchemy import text
revision = "202610080001"
down_revision = "202609300009"
branch_labels = None
depends_on = None

CREATE_STATEMENTS = (
    """CREATE TABLE guidance_diagnostic_preferences (
	privacy_subject_hmac VARCHAR(64) NOT NULL,
	account_generation BIGINT NOT NULL,
	enabled BOOLEAN NOT NULL,
	revision BIGINT NOT NULL,
	updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
	PRIMARY KEY (privacy_subject_hmac, account_generation),
	CONSTRAINT ck_guidance_diag_preference_revision CHECK (account_generation > 0 AND revision > 0)
)""",
    """CREATE TABLE guidance_diagnostic_sessions (
	session_id VARCHAR(36) NOT NULL,
	privacy_subject_hmac VARCHAR(64) NOT NULL,
	account_generation BIGINT NOT NULL,
	build_id VARCHAR(64) NOT NULL,
	created_at TIMESTAMP WITH TIME ZONE NOT NULL,
	last_received_at TIMESTAMP WITH TIME ZONE NOT NULL,
	PRIMARY KEY (session_id),
	FOREIGN KEY(privacy_subject_hmac, account_generation) REFERENCES guidance_diagnostic_preferences (privacy_subject_hmac, account_generation),
	CONSTRAINT ck_guidance_diag_session_generation CHECK (account_generation > 0)
)""",
    """CREATE INDEX ix_guidance_diag_session_owner ON guidance_diagnostic_sessions (privacy_subject_hmac, account_generation)""",
    """CREATE INDEX ix_guidance_diag_session_retention ON guidance_diagnostic_sessions (last_received_at)""",
    """CREATE TABLE guidance_diagnostic_batches (
	session_id VARCHAR(36) NOT NULL,
	batch_id VARCHAR(36) NOT NULL,
	payload_sha256 VARCHAR(64) NOT NULL,
	payload_bytes BIGINT NOT NULL,
	event_count BIGINT NOT NULL,
	events JSONB NOT NULL,
	received_at TIMESTAMP WITH TIME ZONE NOT NULL,
	PRIMARY KEY (session_id, batch_id),
	FOREIGN KEY(session_id) REFERENCES guidance_diagnostic_sessions (session_id) ON DELETE CASCADE,
	CONSTRAINT ck_guidance_diag_batch_bytes CHECK (payload_bytes > 0 AND payload_bytes <= 262144),
	CONSTRAINT ck_guidance_diag_batch_count CHECK (event_count > 0 AND event_count <= 100)
)""",
    """CREATE INDEX ix_guidance_diag_batch_retention ON guidance_diagnostic_batches (received_at)""",
)
PREDEPLOY_GUARD = r"""DO $diagnostic_guard$
DECLARE old_groups text[] := ARRAY['report', 'navigation', 'detect', 'export', 'admin_read', 'privacy', 'raw_collection', 'account_enrollment_global', 'account_enrollment_ip', 'account_enrollment_email', 'account_authentication_global', 'account_authentication_email']; actual_groups text[]; definition text; skeleton text;
BEGIN
  SELECT pg_get_expr(c.conbin, c.conrelid) INTO definition
  FROM pg_constraint c
  WHERE c.conrelid = 'public.actor_rate_limit_events'::regclass
    AND c.conname = 'ck_actor_rate_limit_events_group' AND c.contype = 'c' AND c.convalidated;
  IF definition IS NULL THEN RAISE EXCEPTION 'diagnostic predecessor constraint missing'; END IF;
  SELECT array_agg(matches[1] ORDER BY matches[1]) INTO actual_groups
  FROM regexp_matches(definition, $diag_groups$'([a-z_]+)'$diag_groups$, 'g') AS matches;
  skeleton := regexp_replace(regexp_replace(regexp_replace(definition,
    $diag_literals$'(?:''|[^'])*'$diag_literals$, '?', 'g'),
    $diag_casts$::\s*(?:character\s+varying|text)\s*(?:\[\s*\])?$diag_casts$, '', 'gi'),
    $diag_space$[\s()]$diag_space$, '', 'g');
  IF skeleton IS DISTINCT FROM 'rate_group=ANYARRAY[?,?,?,?,?,?,?,?,?,?,?,?]'
     OR actual_groups IS DISTINCT FROM ARRAY(SELECT unnest(old_groups) ORDER BY 1) THEN
    RAISE EXCEPTION 'diagnostic predecessor group set differs';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='walksafe_backend_runtime' AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolcanlogin) THEN
    RAISE EXCEPTION 'diagnostic runtime group missing or privileged';
  END IF;
  IF to_regclass('public.guidance_diagnostic_preferences') IS NOT NULL
     OR to_regclass('public.guidance_diagnostic_sessions') IS NOT NULL
     OR to_regclass('public.guidance_diagnostic_batches') IS NOT NULL THEN
    RAISE EXCEPTION 'diagnostic tables already present: inspect before reapplying';
  END IF;
END; $diagnostic_guard$"""
RATE_GROUP_CHECK = "ALTER TABLE public.actor_rate_limit_events ADD CONSTRAINT ck_actor_rate_limit_events_group CHECK (rate_group IN ('report', 'navigation', 'detect', 'export', 'admin_read', 'privacy', 'raw_collection', 'account_enrollment_global', 'account_enrollment_ip', 'account_enrollment_email', 'account_authentication_global', 'account_authentication_email', 'diagnostic'))"
PRIVILEGES = (
 "REVOKE ALL ON guidance_diagnostic_preferences, guidance_diagnostic_sessions, guidance_diagnostic_batches FROM PUBLIC",
 "GRANT SELECT, INSERT, UPDATE, DELETE ON guidance_diagnostic_preferences, guidance_diagnostic_sessions, guidance_diagnostic_batches TO walksafe_backend_runtime",
)

def upgrade():
    op.execute("SET LOCAL search_path=public")
    op.execute(text(PREDEPLOY_GUARD.replace(":", r"\:")))
    for statement in CREATE_STATEMENTS:
        op.execute(statement)
    op.execute("ALTER TABLE public.actor_rate_limit_events DROP CONSTRAINT ck_actor_rate_limit_events_group")
    op.execute(RATE_GROUP_CHECK)
    for statement in PRIVILEGES:
        op.execute(statement)

def downgrade():
    # No historical account/event rows are deleted to force a downgrade.
    op.execute("ALTER TABLE public.actor_rate_limit_events DROP CONSTRAINT ck_actor_rate_limit_events_group")
    op.execute("ALTER TABLE public.actor_rate_limit_events ADD CONSTRAINT ck_actor_rate_limit_events_group CHECK (rate_group IN ('report', 'navigation', 'detect', 'export', 'admin_read', 'privacy', 'raw_collection', 'account_enrollment_global', 'account_enrollment_ip', 'account_enrollment_email', 'account_authentication_global', 'account_authentication_email'))")
    for name in ("guidance_diagnostic_batches", "guidance_diagnostic_sessions", "guidance_diagnostic_preferences"):
        op.drop_table(name)
