"""accept current integrated-consent v1.2 events (owner decision R3)

Owner decision 3(b) moves RAW and automatic-report transfer to after the walk
has explicitly ended. The RAW and automatic-reporting disclosures changed in
substance, so their item versions and the document version become 1.2.0
(Root decision R3, 2026-09-29); mobile-network and training items keep their
versions. Earlier 1.0/1.1 rows stay valid evidence, but only a 1.2 event is
current: the latest 1.1 event of an account returns RECONSENT_REQUIRED and
runtime report ingest requires a current (1.2) consent.

- ck_privacy_consent_approved_versions accepts the 1.0, 1.1 and 1.2 sets.
- walksafe_validate_privacy_consent_event accepts only 1.2 inserts.
- walksafe_enforce_runtime_report_ingest_integrity requires a 1.2 consent.

Both functions are rewritten from their installed definitions by exact
fragment replacement (like 202609280002 / 202609300005), so owner, ACL,
SECURITY DEFINER and search_path are kept and a downgrade restores the
predecessor definitions byte-for-byte.

Downgrade never deletes or refuses v1.2 evidence: it restores both v1.1
function definitions byte-for-byte (a v1.2 event is then stale for the
predecessor, which fails closed and asks again), and it drops the v1.2 arm of
the CHECK only while no v1.2 event exists. Shared-database downgrade tests of
earlier revisions therefore keep reaching their own guards.

Revision ID: 202609300006
Revises: 202609300005
Create Date: 2026-09-30
"""

from __future__ import annotations

from alembic import op


revision = "202609300006"
down_revision = "202609300005"
branch_labels = None
depends_on = None


_V1_ITEMS = (
    '{"automatic_reporting": "FP-013-AUTO-1.0.0", '
    '"mobile_network_transfer": "FP-013-MOBILE-1.0.0", '
    '"raw_source_collection": "FP-013-RAW-1.0.0", '
    '"training_reuse": "FP-013-TRAINING-1.0.0"}'
)
_V11_ITEMS = (
    '{"automatic_reporting": "FP-013-AUTO-1.1.0", '
    '"mobile_network_transfer": "FP-013-MOBILE-1.0.0", '
    '"raw_source_collection": "FP-013-RAW-1.1.0", '
    '"training_reuse": "FP-013-TRAINING-1.1.0"}'
)
_V12_ITEMS = (
    '{"automatic_reporting": "FP-013-AUTO-1.2.0", '
    '"mobile_network_transfer": "FP-013-MOBILE-1.0.0", '
    '"raw_source_collection": "FP-013-RAW-1.2.0", '
    '"training_reuse": "FP-013-TRAINING-1.1.0"}'
)

_VALIDATOR_SIGNATURE = "public.walksafe_validate_privacy_consent_event()"
_REPORT_INGEST_TRIGGER_SIGNATURE = (
    "public.walksafe_enforce_runtime_report_ingest_integrity()"
)

# (v1.1 fragment, v1.2 fragment, occurrences in the installed definition)
_VALIDATOR_FRAGMENTS = (
    ("'FP-013-1.1.0'", "'FP-013-1.2.0'", 1),
    ("FP-013-AUTO-1.1.0", "FP-013-AUTO-1.2.0", 2),
    ("FP-013-RAW-1.1.0", "FP-013-RAW-1.2.0", 2),
)
_REPORT_INGEST_FRAGMENTS = (
    ("v_consent.policy_version <> 'FP-013-1.1.0'",
     "v_consent.policy_version <> 'FP-013-1.2.0'", 1),
    ('"automatic_reporting":"FP-013-AUTO-1.1.0"',
     '"automatic_reporting":"FP-013-AUTO-1.2.0"', 1),
    ('"raw_source_collection":"FP-013-RAW-1.1.0"',
     '"raw_source_collection":"FP-013-RAW-1.2.0"', 1),
)


def _rewrite_function(
    signature: str,
    fragments: tuple[tuple[str, str, int], ...],
) -> None:
    """Replace each fragment exactly `count` times in the installed definition."""

    checks = []
    replacements = "v_definition"
    for index, (old, new, count) in enumerate(fragments):
        checks.append(
            f"""
          IF (
              pg_catalog.length(v_definition)
              - pg_catalog.length(pg_catalog.replace(v_definition, $old{index}${old}$old{index}$, ''))
            ) <> {count} * pg_catalog.length($old{index}${old}$old{index}$)
            OR pg_catalog.strpos(v_definition, $new{index}${new}$new{index}$) <> 0
          THEN
            RAISE EXCEPTION 'integrated consent function predecessor is unexpected: {signature}';
          END IF;"""
        )
        replacements = (
            f"pg_catalog.replace({replacements}, "
            f"$old{index}${old}$old{index}$, $new{index}${new}$new{index}$)"
        )
    op.execute(
        f"""
        DO $migration$
        DECLARE
          v_definition text;
        BEGIN
          SELECT pg_catalog.pg_get_functiondef('{signature}'::regprocedure)
            INTO v_definition;
          IF v_definition IS NULL THEN
            RAISE EXCEPTION 'integrated consent function is missing: {signature}';
          END IF;{''.join(checks)}
          EXECUTE {replacements};
        END
        $migration$
        """
    )


def _reversed(
    fragments: tuple[tuple[str, str, int], ...],
) -> tuple[tuple[str, str, int], ...]:
    return tuple((new, old, count) for old, new, count in fragments)


def _replace_version_constraint(expression: str) -> None:
    op.drop_constraint(
        "ck_privacy_consent_approved_versions",
        "privacy_consent_events",
        type_="check",
    )
    op.create_check_constraint(
        "ck_privacy_consent_approved_versions",
        "privacy_consent_events",
        expression,
    )


def _v10_v11_expression() -> str:
    return (
        "(policy_version = 'FP-013-1.0.0' AND item_versions = "
        f"'{_V1_ITEMS}'::jsonb) OR "
        "(policy_version = 'FP-013-1.1.0' AND item_versions = "
        f"'{_V11_ITEMS}'::jsonb)"
    )


def upgrade() -> None:
    op.execute("LOCK TABLE public.privacy_consent_events IN ACCESS EXCLUSIVE MODE")
    _replace_version_constraint(
        _v10_v11_expression()
        + " OR (policy_version = 'FP-013-1.2.0' AND item_versions = "
        f"'{_V12_ITEMS}'::jsonb)"
    )
    _rewrite_function(_VALIDATOR_SIGNATURE, _VALIDATOR_FRAGMENTS)
    _rewrite_function(_REPORT_INGEST_TRIGGER_SIGNATURE, _REPORT_INGEST_FRAGMENTS)


def downgrade() -> None:
    op.execute("LOCK TABLE public.privacy_consent_events IN ACCESS EXCLUSIVE MODE")
    _rewrite_function(
        _REPORT_INGEST_TRIGGER_SIGNATURE,
        _reversed(_REPORT_INGEST_FRAGMENTS),
    )
    _rewrite_function(_VALIDATOR_SIGNATURE, _reversed(_VALIDATOR_FRAGMENTS))
    op.execute(
        f"""
        DO $migration$
        BEGIN
          IF NOT EXISTS (
            SELECT 1 FROM public.privacy_consent_events
             WHERE policy_version = 'FP-013-1.2.0'
          ) THEN
            ALTER TABLE public.privacy_consent_events
              DROP CONSTRAINT ck_privacy_consent_approved_versions;
            ALTER TABLE public.privacy_consent_events
              ADD CONSTRAINT ck_privacy_consent_approved_versions
              CHECK ({_v10_v11_expression()});
          END IF;
        END
        $migration$
        """
    )
