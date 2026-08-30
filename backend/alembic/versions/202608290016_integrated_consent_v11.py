"""accept current integrated-consent v1.1 events

Revision ID: 202608290016
Revises: 202608290015
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op


revision = "202608290016"
down_revision = "202608290015"
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


def _install_validator(
    *,
    policy_version: str,
    automatic_reporting_version: str,
    raw_source_collection_version: str,
    training_reuse_version: str,
) -> None:
    op.execute(
        f"""
        CREATE OR REPLACE FUNCTION public.walksafe_validate_privacy_consent_event()
        RETURNS trigger AS $$
        DECLARE
          expected_client_revision bigint;
          expected_subject_revision bigint;
          latest_installation_time timestamptz;
          latest_subject_time timestamptz;
          database_recorded_at timestamptz;
          canonical_payload text;
          expected_receipt text;
        BEGIN
          IF NEW.policy_version <> '{policy_version}'
             OR NEW.item_versions <> '{{"automatic_reporting": "{automatic_reporting_version}",
               "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
               "raw_source_collection": "{raw_source_collection_version}",
               "training_reuse": "{training_reuse_version}"}}'::jsonb THEN
            RAISE EXCEPTION 'privacy consent event policy version is not current'
              USING ERRCODE = '23514';
          END IF;
          PERFORM pg_advisory_xact_lock(
            hashtextextended(
              E'walksafe-privacy-subject-v2\\n' || NEW.privacy_subject_hmac ||
              E'\\n' || NEW.account_generation::text,
              0
            )
          );
          SELECT COALESCE(max(client_revision), 0) + 1, max(recorded_at)
            INTO expected_client_revision, latest_installation_time
          FROM public.privacy_consent_events
          WHERE privacy_subject_hmac = NEW.privacy_subject_hmac
            AND account_generation = NEW.account_generation
            AND installation_subject_hmac = NEW.installation_subject_hmac;
          SELECT COALESCE(max(subject_revision), 0) + 1, max(recorded_at)
            INTO expected_subject_revision, latest_subject_time
          FROM public.privacy_consent_events
          WHERE privacy_subject_hmac = NEW.privacy_subject_hmac
            AND account_generation = NEW.account_generation;
          database_recorded_at := clock_timestamp();
          IF NEW.client_revision <> expected_client_revision
             OR NEW.subject_revision <> expected_subject_revision
             OR NEW.recorded_at > database_recorded_at
             OR (latest_installation_time IS NOT NULL
                 AND NEW.recorded_at < latest_installation_time)
             OR (latest_subject_time IS NOT NULL
                 AND NEW.recorded_at < latest_subject_time)
             OR EXISTS (
               SELECT 1 FROM public.account_deletion_tombstones
               WHERE privacy_subject_hmac = NEW.privacy_subject_hmac
                 AND account_generation = NEW.account_generation
             ) THEN
            RAISE EXCEPTION 'privacy consent event revision or chronology is invalid'
              USING ERRCODE = '23514';
          END IF;
          canonical_payload :=
            '{{"account_generation":' || NEW.account_generation::text ||
            ',"automatic_reporting":' || NEW.automatic_reporting::text ||
            ',"client_revision":' || NEW.client_revision::text ||
            ',"installation_subject_hmac":' ||
              to_jsonb(NEW.installation_subject_hmac)::text ||
            ',"item_versions":{{"automatic_reporting":"{automatic_reporting_version}",' ||
              '"mobile_network_transfer":"FP-013-MOBILE-1.0.0",' ||
              '"raw_source_collection":"{raw_source_collection_version}",' ||
              '"training_reuse":"{training_reuse_version}"}}' ||
            ',"mobile_network_transfer":' || NEW.mobile_network_transfer::text ||
            ',"policy_version":' || to_jsonb(NEW.policy_version)::text ||
            ',"privacy_subject_hmac":' || to_jsonb(NEW.privacy_subject_hmac)::text ||
            ',"raw_source_collection":' || NEW.raw_source_collection::text ||
            ',"request_id":' || to_jsonb(NEW.request_id)::text ||
            ',"subject_revision":' || NEW.subject_revision::text ||
            ',"training_reuse":' || NEW.training_reuse::text || '}}';
          expected_receipt := encode(
            pg_catalog.sha256(
              convert_to('walksafe/privacy-consent-event/v2', 'UTF8') ||
              decode('00', 'hex') || convert_to(canonical_payload, 'UTF8')
            ),
            'hex'
          );
          IF NEW.receipt_sha256 <> expected_receipt THEN
            RAISE EXCEPTION 'privacy consent receipt provenance is invalid'
              USING ERRCODE = '23514';
          END IF;
          NEW.recorded_at := database_recorded_at;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql VOLATILE
        SET search_path = pg_catalog, pg_temp
        """
    )


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


def upgrade() -> None:
    op.execute("LOCK TABLE public.privacy_consent_events IN ACCESS EXCLUSIVE MODE")
    _replace_version_constraint(
        "(policy_version = 'FP-013-1.0.0' AND item_versions = "
        f"'{_V1_ITEMS}'::jsonb) OR "
        "(policy_version = 'FP-013-1.1.0' AND item_versions = "
        f"'{_V11_ITEMS}'::jsonb)"
    )
    _install_validator(
        policy_version="FP-013-1.1.0",
        automatic_reporting_version="FP-013-AUTO-1.1.0",
        raw_source_collection_version="FP-013-RAW-1.1.0",
        training_reuse_version="FP-013-TRAINING-1.1.0",
    )


def downgrade() -> None:
    op.execute("LOCK TABLE public.privacy_consent_events IN ACCESS EXCLUSIVE MODE")
    op.execute(
        """
        DO $$
        BEGIN
          IF EXISTS (
            SELECT 1
            FROM public.privacy_consent_events
            WHERE NOT (
              policy_version = 'FP-013-1.0.0'
              AND item_versions = '{"automatic_reporting": "FP-013-AUTO-1.0.0",
                "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
                "raw_source_collection": "FP-013-RAW-1.0.0",
                "training_reuse": "FP-013-TRAINING-1.0.0"}'::jsonb
            )
          ) THEN
            RAISE EXCEPTION 'cannot downgrade integrated consent while v1.1 evidence exists'
              USING ERRCODE = '55000';
          END IF;
        END;
        $$
        """
    )
    _replace_version_constraint(
        "policy_version = 'FP-013-1.0.0' AND item_versions = "
        f"'{_V1_ITEMS}'::jsonb"
    )
    _install_validator(
        policy_version="FP-013-1.0.0",
        automatic_reporting_version="FP-013-AUTO-1.0.0",
        raw_source_collection_version="FP-013-RAW-1.0.0",
        training_reuse_version="FP-013-TRAINING-1.0.0",
    )
