"""Runtime verification for the administrator report integrity boundary."""

from __future__ import annotations

from sqlalchemy import text


ADMIN_REPORT_INTEGRITY_HEAD = "202608300005"
ADMIN_REPORT_INTEGRITY_LEGACY_REVISIONS = frozenset({"202608300004"})
ADMIN_REPORT_INTEGRITY_COMPATIBLE_REVISIONS = frozenset(
    {
        ADMIN_REPORT_INTEGRITY_HEAD,
        "202608300006",
        "202609010001",
        "202609010002",
    }
)

_BOUNDARY_SQL = text(
    r"""
    /* walksafe_admin_report_integrity_boundary */
    WITH RECURSIVE owner_role AS (
      SELECT oid, rolsuper, rolcreaterole, rolcreatedb, rolcanlogin,
             rolinherit, rolreplication, rolbypassrls
        FROM pg_catalog.pg_roles
       WHERE rolname = 'walksafe_report_evidence_owner'
    ),
    runtime_role AS (
      SELECT oid, rolsuper, rolcreaterole, rolcreatedb, rolcanlogin,
             rolinherit, rolreplication, rolbypassrls
        FROM pg_catalog.pg_roles
       WHERE rolname = 'walksafe_backend_runtime'
    ),
    worker_role_contract(role_name, expected_inherit) AS (
      VALUES
        ('walksafe_account_deletion_worker', false),
        ('walksafe_report_deletion_worker', false),
        ('walksafe_report_restore_worker', false),
        ('walksafe_training_lifecycle_worker', true)
    ),
    worker_roles AS (
      SELECT role.oid, role.rolsuper, role.rolcreaterole, role.rolcreatedb,
             role.rolcanlogin, role.rolinherit, role.rolreplication,
             role.rolbypassrls, contract.expected_inherit
        FROM worker_role_contract AS contract
        JOIN pg_catalog.pg_roles AS role
          ON role.rolname = contract.role_name
    ),
    owner_memberships AS (
      SELECT membership.member, membership.admin_option,
             membership.inherit_option, membership.set_option
        FROM pg_catalog.pg_auth_members AS membership
        JOIN owner_role ON owner_role.oid = membership.roleid
    ),
    owner_inheritors(role_oid) AS (
      SELECT membership.member
        FROM pg_catalog.pg_auth_members AS membership
        JOIN owner_role ON owner_role.oid = membership.roleid
      UNION
      SELECT membership.member
        FROM pg_catalog.pg_auth_members AS membership
        JOIN owner_inheritors AS inherited
          ON membership.roleid = inherited.role_oid
    ),
    protected_parent_roles(role_oid) AS (
      SELECT membership.roleid
        FROM pg_catalog.pg_auth_members AS membership
       WHERE membership.member IN (
               SELECT oid FROM runtime_role
               UNION ALL
               SELECT oid FROM worker_roles
             )
      UNION
      SELECT membership.roleid
        FROM pg_catalog.pg_auth_members AS membership
        JOIN protected_parent_roles AS inherited
          ON membership.member = inherited.role_oid
    ),
    target_tables(name) AS (
      VALUES
        ('public.admin_report_mutation_claims'),
        ('public.report_original_access_grants'),
        ('public.report_original_access_audits'),
        ('public.report_review_decisions'),
        ('public.report_delivery_packages'),
        ('public.report_institution_delivery_events')
    ),
    runtime_ingest_tables(name) AS (
      VALUES
        ('public.reports'),
        ('public.report_image_objects')
    ),
    runtime_dependency_tables(name) AS (
      VALUES
        ('public.admin_device_proof_challenges'),
        ('public.admin_security_reconfirmations'),
        ('public.privacy_consent_events'),
        ('public.account_deletion_tombstones'),
        ('public.report_content_revisions'),
        ('public.report_export_audits'),
        ('public.admin_security_controls'),
        ('public.admin_security_sessions')
    ),
    runtime_dependency_insert_tables(name) AS (
      VALUES
        ('public.admin_device_proof_challenges'),
        ('public.privacy_consent_events'),
        ('public.account_deletion_tombstones'),
        ('public.report_content_revisions'),
        ('public.report_export_audits')
    ),
    migration_owned_tables(name) AS (
      VALUES
        ('public.reports'),
        ('public.report_image_objects'),
        ('public.admin_device_proof_challenges'),
        ('public.admin_security_reconfirmations'),
        ('public.privacy_consent_events'),
        ('public.account_deletion_tombstones'),
        ('public.report_content_revisions'),
        ('public.report_export_audits'),
        ('public.admin_security_controls'),
        ('public.walksafe_recovery_custody_capabilities'),
        ('public.admin_security_sessions'),
        ('public.report_original_access_grants'),
        ('public.report_original_access_audits'),
        ('public.report_review_decisions'),
        ('public.report_delivery_packages'),
        ('public.report_institution_delivery_events')
    ),
    owner_select_tables(name) AS (
      VALUES
        ('public.reports'),
        ('public.report_image_objects'),
        ('public.report_original_access_grants'),
        ('public.report_original_access_audits'),
        ('public.report_review_decisions'),
        ('public.admin_device_proof_challenges'),
        ('public.admin_security_reconfirmations'),
        ('public.privacy_consent_events'),
        ('public.account_deletion_tombstones'),
        ('public.report_content_revisions'),
        ('public.admin_report_mutation_claims'),
        ('public.report_delivery_packages'),
        ('public.report_institution_delivery_events'),
        ('public.report_export_audits')
    ),
    owner_insert_tables(name) AS (
      VALUES
        ('public.report_original_access_grants'),
        ('public.report_original_access_audits'),
        ('public.report_review_decisions'),
        ('public.admin_report_mutation_claims'),
        ('public.report_delivery_packages'),
        ('public.report_institution_delivery_events')
    ),
    owner_update_columns(table_name, column_name) AS (
      VALUES
        ('public.reports', 'updated_at'),
        ('public.report_original_access_grants', 'consumed_at'),
        ('public.report_original_access_grants', 'access_granted_at'),
        ('public.report_original_access_grants', 'review_decision_id'),
        ('public.report_original_access_grants', 'review_bound_at')
    ),
    acl_tables(name) AS (
      SELECT name FROM migration_owned_tables
      UNION ALL
      SELECT 'public.admin_report_mutation_claims'
    ),
    allowed_table_acl(table_name, role_name, privilege_type) AS (
      VALUES
        ('public.reports', 'walksafe_backend_runtime', 'SELECT'),
        ('public.reports', 'walksafe_backend_runtime', 'INSERT'),
        ('public.reports', 'walksafe_backend_runtime', 'UPDATE'),
        ('public.reports', 'walksafe_report_evidence_owner', 'SELECT'),
        ('public.reports', 'walksafe_account_deletion_worker', 'SELECT'),
        ('public.reports', 'walksafe_account_deletion_worker', 'DELETE'),
        ('public.reports', 'walksafe_report_deletion_worker', 'DELETE'),
        ('public.report_image_objects', 'walksafe_backend_runtime', 'SELECT'),
        ('public.report_image_objects', 'walksafe_backend_runtime', 'INSERT'),
        ('public.report_image_objects', 'walksafe_report_evidence_owner', 'SELECT'),
        ('public.report_image_objects', 'walksafe_account_deletion_worker', 'SELECT'),
        ('public.admin_device_proof_challenges', 'walksafe_backend_runtime', 'SELECT'),
        ('public.admin_device_proof_challenges', 'walksafe_backend_runtime', 'INSERT'),
        ('public.admin_device_proof_challenges', 'walksafe_report_evidence_owner', 'SELECT'),
        ('public.admin_security_reconfirmations', 'walksafe_backend_runtime', 'SELECT'),
        ('public.admin_security_reconfirmations', 'walksafe_report_evidence_owner', 'SELECT'),
        ('public.privacy_consent_events', 'walksafe_backend_runtime', 'SELECT'),
        ('public.privacy_consent_events', 'walksafe_backend_runtime', 'INSERT'),
        ('public.privacy_consent_events', 'walksafe_report_evidence_owner', 'SELECT'),
        ('public.privacy_consent_events', 'walksafe_account_deletion_worker', 'SELECT'),
        ('public.privacy_consent_events', 'walksafe_training_lifecycle_worker', 'SELECT'),
        ('public.account_deletion_tombstones', 'walksafe_backend_runtime', 'SELECT'),
        ('public.account_deletion_tombstones', 'walksafe_backend_runtime', 'INSERT'),
        ('public.account_deletion_tombstones', 'walksafe_report_evidence_owner', 'SELECT'),
        ('public.account_deletion_tombstones', 'walksafe_account_deletion_worker', 'SELECT'),
        ('public.account_deletion_tombstones', 'walksafe_training_lifecycle_worker', 'SELECT'),
        ('public.report_content_revisions', 'walksafe_backend_runtime', 'SELECT'),
        ('public.report_content_revisions', 'walksafe_backend_runtime', 'INSERT'),
        ('public.report_content_revisions', 'walksafe_report_evidence_owner', 'SELECT'),
        ('public.report_export_audits', 'walksafe_backend_runtime', 'SELECT'),
        ('public.report_export_audits', 'walksafe_backend_runtime', 'INSERT'),
        ('public.report_export_audits', 'walksafe_report_evidence_owner', 'SELECT'),
        ('public.admin_security_controls', 'walksafe_backend_runtime', 'SELECT'),
        ('public.admin_security_sessions', 'walksafe_backend_runtime', 'SELECT'),
        ('public.report_original_access_grants', 'walksafe_backend_runtime', 'SELECT'),
        ('public.report_original_access_grants', 'walksafe_report_evidence_owner', 'SELECT'),
        ('public.report_original_access_grants', 'walksafe_report_evidence_owner', 'INSERT'),
        ('public.report_original_access_audits', 'walksafe_backend_runtime', 'SELECT'),
        ('public.report_original_access_audits', 'walksafe_report_evidence_owner', 'SELECT'),
        ('public.report_original_access_audits', 'walksafe_report_evidence_owner', 'INSERT'),
        ('public.report_review_decisions', 'walksafe_backend_runtime', 'SELECT'),
        ('public.report_review_decisions', 'walksafe_report_evidence_owner', 'SELECT'),
        ('public.report_review_decisions', 'walksafe_report_evidence_owner', 'INSERT'),
        ('public.admin_report_mutation_claims', 'walksafe_backend_runtime', 'SELECT'),
        ('public.report_delivery_packages', 'walksafe_backend_runtime', 'SELECT'),
        ('public.report_delivery_packages', 'walksafe_report_evidence_owner', 'SELECT'),
        ('public.report_delivery_packages', 'walksafe_report_evidence_owner', 'INSERT'),
        ('public.report_institution_delivery_events', 'walksafe_backend_runtime', 'SELECT'),
        ('public.report_institution_delivery_events', 'walksafe_report_evidence_owner', 'SELECT'),
        ('public.report_institution_delivery_events', 'walksafe_report_evidence_owner', 'INSERT')
    ),
    allowed_column_acl(table_name, column_name, role_name, privilege_type) AS (
      VALUES
        ('public.reports', 'updated_at', 'walksafe_report_evidence_owner', 'UPDATE'),
        ('public.reports', 'id', 'walksafe_report_deletion_worker', 'SELECT'),
        ('public.reports', 'image_path', 'walksafe_report_deletion_worker', 'SELECT'),
        ('public.reports', 'privacy_subject_hmac', 'walksafe_report_deletion_worker', 'SELECT'),
        ('public.reports', 'account_generation', 'walksafe_report_deletion_worker', 'SELECT'),
        ('public.reports', 'id', 'walksafe_report_restore_worker', 'SELECT'),
        ('public.reports', 'image_path', 'walksafe_report_restore_worker', 'SELECT'),
        ('public.reports', 'image_content_type', 'walksafe_report_restore_worker', 'SELECT'),
        ('public.reports', 'privacy_subject_hmac', 'walksafe_report_restore_worker', 'SELECT'),
        ('public.reports', 'account_generation', 'walksafe_report_restore_worker', 'SELECT'),
        ('public.report_image_objects', 'report_id', 'walksafe_report_deletion_worker', 'SELECT'),
        ('public.report_image_objects', 'storage_name', 'walksafe_report_deletion_worker', 'SELECT'),
        ('public.report_image_objects', 'report_id', 'walksafe_report_restore_worker', 'SELECT'),
        ('public.report_image_objects', 'storage_name', 'walksafe_report_restore_worker', 'SELECT'),
        ('public.report_image_objects', 'content_type', 'walksafe_report_restore_worker', 'SELECT'),
        ('public.report_image_objects', 'envelope_size', 'walksafe_report_restore_worker', 'SELECT'),
        ('public.report_image_objects', 'envelope_sha256', 'walksafe_report_restore_worker', 'SELECT'),
        ('public.report_original_access_grants', 'consumed_at', 'walksafe_report_evidence_owner', 'UPDATE'),
        ('public.report_original_access_grants', 'access_granted_at', 'walksafe_report_evidence_owner', 'UPDATE'),
        ('public.report_original_access_grants', 'review_decision_id', 'walksafe_report_evidence_owner', 'UPDATE'),
        ('public.report_original_access_grants', 'review_bound_at', 'walksafe_report_evidence_owner', 'UPDATE'),
        ('public.admin_device_proof_challenges', 'consumed_at', 'walksafe_backend_runtime', 'UPDATE'),
        ('public.report_institution_delivery_events', 'id', 'walksafe_report_deletion_worker', 'SELECT'),
        ('public.report_institution_delivery_events', 'package_id', 'walksafe_report_deletion_worker', 'SELECT'),
        ('public.report_institution_delivery_events', 'report_id', 'walksafe_report_deletion_worker', 'SELECT'),
        ('public.report_institution_delivery_events', 'revision', 'walksafe_report_deletion_worker', 'SELECT'),
        ('public.report_institution_delivery_events', 'status', 'walksafe_report_deletion_worker', 'SELECT'),
        ('public.report_institution_delivery_events', 'institution', 'walksafe_report_deletion_worker', 'SELECT'),
        ('public.report_institution_delivery_events', 'observed_at', 'walksafe_report_deletion_worker', 'SELECT')
    ),
    target_functions(signature) AS (
      VALUES
        ('public.walksafe_issue_report_original_evidence_grant_v3('
         'uuid,uuid,uuid,uuid,text,uuid,text,text,text,text,bigint,integer,'
         'uuid,bytea,text,text,text)'),
        ('public.walksafe_prepare_report_original_evidence_access_v3('
         'uuid,text,text,uuid,text,text,text)'),
        ('public.walksafe_complete_report_original_evidence_access_v3('
         'uuid,uuid,uuid,text,uuid,text,text,bigint,text,smallint,text,bigint,'
         'text,text,text,text)'),
        ('public.walksafe_record_report_original_evidence_failure('
         'uuid,uuid,uuid,text,uuid,text,text,text,text,text,text,smallint,'
         'boolean,timestamptz,text,text)'),
        ('public.walksafe_append_report_review_decision_v3('
         'uuid,uuid,bigint,text,text,text,uuid,boolean,boolean,boolean,uuid,'
         'text,uuid,text,uuid,uuid,bytea,text,text)'),
        ('public.walksafe_create_report_delivery_package_v3('
         'uuid,uuid,bigint,text,uuid,bigint,bigint,uuid,uuid,text,text,text,'
         'bigint,bigint,bigint,text,uuid,text,uuid,timestamptz,uuid,bytea,'
         'text,text,text)'),
        ('public.walksafe_append_report_delivery_event_v3('
         'uuid,uuid,bigint,bigint,uuid,text,text,text,text,text,text,text,'
         'timestamptz,text,uuid,text,uuid,uuid,bytea,text,text)')
    ),
    owner_only_functions(signature) AS (
      VALUES
        ('public.walksafe_claim_admin_report_mutation('
         'uuid,uuid,text,uuid,text,uuid,text,text,text,uuid,bytea,text,text,text)'),
        ('public.walksafe_enforce_runtime_report_ingest_integrity()')
    ),
    old_runtime_functions(signature) AS (
      VALUES
        ('public.walksafe_issue_report_original_evidence_grant('
         'uuid,uuid,uuid,uuid,text,uuid,text,text,text,text,bigint,'
         'timestamptz,timestamptz,text,text)'),
        ('public.walksafe_prepare_report_original_evidence_access('
         'uuid,text,text,uuid,text,timestamptz,text,text)'),
        ('public.walksafe_complete_report_original_evidence_access('
         'uuid,uuid,uuid,text,uuid,text,text,bigint,text,smallint,text,bigint,'
         'text,timestamptz,text,text)'),
        ('public.walksafe_append_report_review_decision('
         'uuid,uuid,bigint,text,text,text,uuid,boolean,boolean,boolean,uuid,'
         'text,uuid,text,uuid,timestamptz,text,text)')
    ),
    whitespace_functions(signature) AS (
      VALUES
        ('public.walksafe_python_strip(text)'),
        ('public.walksafe_python_split_join(text)')
    ),
    session_lock_function(signature) AS (
      VALUES
        ('public.walksafe_lock_admin_original_access_session('
         'text,uuid,text,timestamptz,text,text)')
    ),
    session_dependency_function(signature) AS (
      VALUES
        ('public.walksafe_assert_admin_totp_capability(text,text,text)')
    ),
    expected_function_definitions(name, definition_sha256, result, helper) AS (
      VALUES
        ('walksafe_append_report_delivery_event_v3',
         '7db784b1fa5f37f6c13f4c8a1100ab332606e15c65bb925743a7eb71f0cb0954',
         'TABLE(result_status text, event_id uuid, event_revision bigint)', false),
        ('walksafe_append_report_review_decision',
         '21f28f5e68d48e0ab9566d3f837c978ce3e147fee0c7d35131af7fc22f691c2d',
         'TABLE(result_status text, decision_id uuid, decision_revision bigint)', false),
        ('walksafe_append_report_review_decision_v3',
         '80a983dd5bb8fd3b65d102573e58167e7c26de39a7b5b8ff440b545a08c10035',
         'TABLE(result_status text, decision_id uuid, decision_revision bigint)', false),
        ('walksafe_assert_admin_totp_capability',
         'c6a95b20292cd519a5a346edbbbf9753b0158bbceb1264d9dc70d72d1d6e5b87',
         'boolean', false),
        ('walksafe_claim_admin_report_mutation',
         '59ae7764df9427c0876c793ae41cdab1944ba31bcc93ce0d659a5b5aa25888d6',
         'uuid', false),
        ('walksafe_complete_report_original_evidence_access',
         '8a8f9e3e642e03407e001c6c398d2fa66bff7725699f4515f5b92ab89bc28c06',
         'boolean', false),
        ('walksafe_complete_report_original_evidence_access_v3',
         '84884ccc7e7e42e28e68b08333eb4f625d2bcb8bf12f822018301f02237a4f0e',
         'boolean', false),
        ('walksafe_create_report_delivery_package_v3',
         '87ae58277921d0f89162d1aa529c01f906adc68b34fe44987dcf6577bcb4b388',
         'TABLE(result_status text, package_id uuid, package_revision bigint)', false),
        ('walksafe_enforce_runtime_report_ingest_integrity',
         '37d67daddfca6121cc6cde66b0375561f49395b3f0c33a4afe757dfe7c2ff013',
         'trigger', false),
        ('walksafe_issue_report_original_evidence_grant',
         'aa41bfc2ea428cce55918d939a75227745068560b3fdaf46c8d985bc11a89234',
         'TABLE(grant_id uuid, content_revision bigint, expires_at timestamp '
         'with time zone, latitude double precision, longitude double '
         'precision, accuracy_m double precision, resource_path text, '
         'content_type text, image_sha256 text, image_byte_count bigint)', false),
        ('walksafe_issue_report_original_evidence_grant_v3',
         '85b3139dcba433dca2387ae6a7d715b5c739a259591f58f06eec3b177705cc6f',
         'TABLE(grant_id uuid, content_revision bigint, expires_at timestamp '
         'with time zone, latitude double precision, longitude double '
         'precision, accuracy_m double precision, resource_path text, '
         'content_type text, image_sha256 text, image_byte_count bigint)', false),
        ('walksafe_lock_admin_original_access_session',
         '9c65b0a6958d4c6ebadd5cb76336c627130c88a48b1226f69bdc7d8a349d537d',
         'boolean', false),
        ('walksafe_prepare_report_original_evidence_access',
         'b7857b06461d568df0d3adf6847b6ef35d2839c9a48fc05a9872716d565b30f6',
         'TABLE(access_status text, reason_code text, consume_grant boolean, '
         'grant_id uuid, purpose text, content_revision bigint, expires_at '
         'timestamp with time zone, storage_name text, envelope_size bigint, '
         'envelope_sha256 text, key_id text, envelope_version smallint, '
         'content_type text, plaintext_sha256 text, plaintext_size bigint)', false),
        ('walksafe_prepare_report_original_evidence_access_v3',
         'c98252fd466b827bdfc86f91ce0df4265665d657213bb20a2d82dd9c6801fed0',
         'TABLE(access_status text, reason_code text, consume_grant boolean, '
         'grant_id uuid, purpose text, content_revision bigint, expires_at '
         'timestamp with time zone, storage_name text, envelope_size bigint, '
         'envelope_sha256 text, key_id text, envelope_version smallint, '
         'content_type text, plaintext_sha256 text, plaintext_size bigint)', false),
        ('walksafe_python_split_join',
         '258d669556fb012b3a83d52077eb50877c0c6f900475ef55043309698ca19e0b',
         'text', true),
        ('walksafe_python_strip',
         'f094e958da9d89355d4eacac724ac4a47fdc520108bc79e5645692b05d4cfff5',
         'text', true),
        ('walksafe_record_report_original_evidence_failure',
         '8bff69f70aa548c09df20fe11605b0bb3af5aba66b0be9514831ef1b0071b193',
         'boolean', false)
    ),
    resolved_functions AS (
      SELECT target.signature,
             pg_catalog.to_regprocedure(target.signature) AS oid
        FROM target_functions AS target
    ),
    resolved_owner_only_functions AS (
      SELECT target.signature,
             pg_catalog.to_regprocedure(target.signature) AS oid
        FROM owner_only_functions AS target
    ),
    resolved_old_runtime_functions AS (
      SELECT target.signature,
             pg_catalog.to_regprocedure(target.signature) AS oid
        FROM old_runtime_functions AS target
    ),
    resolved_whitespace_functions AS (
      SELECT target.signature,
             pg_catalog.to_regprocedure(target.signature) AS oid
        FROM whitespace_functions AS target
    ),
    resolved_session_lock_function AS (
      SELECT target.signature,
             pg_catalog.to_regprocedure(target.signature) AS oid
        FROM session_lock_function AS target
    ),
    resolved_session_dependency_function AS (
      SELECT target.signature,
             pg_catalog.to_regprocedure(target.signature) AS oid
        FROM session_dependency_function AS target
    ),
    resolved_all_functions AS (
      SELECT signature, oid FROM resolved_functions
      UNION ALL
      SELECT signature, oid FROM resolved_owner_only_functions
      UNION ALL
      SELECT signature, oid FROM resolved_old_runtime_functions
      UNION ALL
      SELECT signature, oid FROM resolved_whitespace_functions
      UNION ALL
      SELECT signature, oid FROM resolved_session_lock_function
      UNION ALL
      SELECT signature, oid FROM resolved_session_dependency_function
    )
    SELECT
      (SELECT pg_catalog.count(*) = 1
              AND pg_catalog.bool_and(
                NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb
                AND NOT rolcanlogin AND NOT rolinherit
                AND NOT rolreplication AND NOT rolbypassrls
              )
         FROM owner_role)
      AND (SELECT pg_catalog.count(*) = 1
                   AND pg_catalog.bool_and(
                     NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb
                     AND NOT rolcanlogin AND rolinherit
                     AND NOT rolreplication AND NOT rolbypassrls
                   )
             FROM runtime_role)
      AND (SELECT pg_catalog.count(*) = 4
                   AND pg_catalog.bool_and(
                     NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb
                     AND NOT rolcanlogin
                     AND rolinherit = expected_inherit
                     AND NOT rolreplication AND NOT rolbypassrls
                   )
             FROM worker_roles)
      AND NOT EXISTS (SELECT 1 FROM protected_parent_roles)
      AND (SELECT pg_catalog.count(*) = 1
                   AND pg_catalog.bool_and(
                     NOT admin_option AND inherit_option AND set_option
                   )
             FROM owner_memberships)
      AND NOT EXISTS (
        SELECT 1
          FROM owner_inheritors
         WHERE role_oid NOT IN (SELECT member FROM owner_memberships)
      )
      AND NOT EXISTS (
        SELECT 1
          FROM pg_catalog.pg_auth_members AS membership
          JOIN owner_role ON owner_role.oid = membership.member
      )
      AND EXISTS (
        SELECT 1
          FROM pg_catalog.pg_namespace AS namespace
          CROSS JOIN owner_role
          CROSS JOIN LATERAL pg_catalog.aclexplode(
            coalesce(
              namespace.nspacl,
              pg_catalog.acldefault('n', namespace.nspowner)
            )
          ) AS privilege
         WHERE namespace.nspname = 'public'
           AND privilege.grantee = owner_role.oid
           AND privilege.privilege_type = 'USAGE'
           AND NOT privilege.is_grantable
           AND NOT pg_catalog.has_schema_privilege(
                 owner_role.oid, namespace.oid, 'CREATE'
               )
      )
      AND EXISTS (
        SELECT 1
          FROM pg_catalog.pg_namespace AS namespace
          CROSS JOIN runtime_role
          CROSS JOIN LATERAL pg_catalog.aclexplode(
            coalesce(
              namespace.nspacl,
              pg_catalog.acldefault('n', namespace.nspowner)
            )
          ) AS privilege
         WHERE namespace.nspname = 'public'
           AND privilege.grantee = runtime_role.oid
           AND privilege.privilege_type = 'USAGE'
           AND NOT privilege.is_grantable
           AND NOT pg_catalog.has_schema_privilege(
                 runtime_role.oid, namespace.oid, 'CREATE'
               )
      )
      AND NOT EXISTS (
        SELECT 1
          FROM migration_owned_tables AS target
          JOIN pg_catalog.pg_class AS relation
            ON relation.oid = pg_catalog.to_regclass(target.name)
          CROSS JOIN owner_memberships
         WHERE relation.relowner <> owner_memberships.member
      )
      AND NOT EXISTS (
        SELECT 1
          FROM owner_select_tables AS target
          CROSS JOIN owner_role
         WHERE NOT pg_catalog.has_table_privilege(
               owner_role.oid, target.name, 'SELECT'
             )
      )
      AND NOT EXISTS (
        SELECT 1
          FROM target_tables AS target
          CROSS JOIN runtime_role
         WHERE NOT pg_catalog.has_table_privilege(
               runtime_role.oid, target.name, 'SELECT'
             )
      )
      AND NOT EXISTS (
        SELECT 1
          FROM runtime_ingest_tables AS target
          CROSS JOIN runtime_role
         WHERE NOT pg_catalog.has_table_privilege(
                 runtime_role.oid, target.name, 'SELECT'
               )
            OR NOT pg_catalog.has_table_privilege(
                 runtime_role.oid, target.name, 'INSERT'
               )
      )
      AND (SELECT pg_catalog.bool_and(
                    pg_catalog.has_table_privilege(
                      runtime_role.oid, 'public.reports', 'UPDATE'
                    )
                  )
             FROM runtime_role)
      AND NOT EXISTS (
        SELECT 1
          FROM runtime_dependency_tables AS target
          CROSS JOIN runtime_role
         WHERE NOT pg_catalog.has_table_privilege(
               runtime_role.oid, target.name, 'SELECT'
             )
      )
      AND NOT EXISTS (
        SELECT 1
          FROM runtime_dependency_insert_tables AS target
          CROSS JOIN runtime_role
         WHERE NOT pg_catalog.has_table_privilege(
               runtime_role.oid, target.name, 'INSERT'
             )
      )
      AND (SELECT pg_catalog.bool_and(
                    pg_catalog.has_column_privilege(
                      runtime_role.oid,
                      'public.admin_device_proof_challenges',
                      'consumed_at',
                      'UPDATE'
                    )
                  )
             FROM runtime_role)
      AND NOT EXISTS (
        SELECT 1
          FROM owner_insert_tables AS target
          CROSS JOIN owner_role
         WHERE NOT pg_catalog.has_table_privilege(
               owner_role.oid, target.name, 'INSERT'
             )
      )
      AND NOT EXISTS (
        SELECT 1
          FROM owner_update_columns AS target
          CROSS JOIN owner_role
         WHERE NOT pg_catalog.has_column_privilege(
               owner_role.oid,
               target.table_name,
               target.column_name,
               'UPDATE'
             )
      )
      AND NOT EXISTS (
        SELECT 1
          FROM migration_owned_tables AS target
          CROSS JOIN owner_role
         WHERE pg_catalog.has_table_privilege(
                 owner_role.oid, target.name, 'UPDATE'
               )
            OR pg_catalog.has_table_privilege(
                 owner_role.oid, target.name, 'DELETE'
               )
            OR pg_catalog.has_table_privilege(
                 owner_role.oid, target.name, 'TRUNCATE'
               )
            OR (
              target.name IN ('public.reports', 'public.report_image_objects')
              AND pg_catalog.has_table_privilege(
                    owner_role.oid, target.name, 'INSERT'
                  )
            )
      )
      AND NOT EXISTS (
        SELECT 1
          FROM migration_owned_tables AS target
          JOIN pg_catalog.pg_attribute AS attribute
            ON attribute.attrelid = pg_catalog.to_regclass(target.name)
           AND attribute.attnum > 0
           AND NOT attribute.attisdropped
          CROSS JOIN owner_role
         WHERE (
                 pg_catalog.has_column_privilege(
                   owner_role.oid,
                   attribute.attrelid,
                   attribute.attname,
                   'UPDATE'
                 )
                 AND NOT EXISTS (
                   SELECT 1
                     FROM owner_update_columns AS allowed
                    WHERE allowed.table_name = target.name
                      AND allowed.column_name = attribute.attname
                 )
               )
            OR (
                 target.name IN (
                   'public.reports', 'public.report_image_objects'
                 )
                 AND pg_catalog.has_column_privilege(
                   owner_role.oid,
                   attribute.attrelid,
                   attribute.attname,
                   'INSERT'
                 )
               )
      )
      AND NOT EXISTS (
        SELECT 1
          FROM target_tables AS target
          CROSS JOIN runtime_role
         WHERE pg_catalog.has_table_privilege(
                 runtime_role.oid, target.name, 'INSERT'
               )
            OR pg_catalog.has_table_privilege(
                 runtime_role.oid, target.name, 'UPDATE'
               )
            OR pg_catalog.has_table_privilege(
                 runtime_role.oid, target.name, 'DELETE'
               )
            OR pg_catalog.has_table_privilege(
                 runtime_role.oid, target.name, 'TRUNCATE'
               )
            OR pg_catalog.has_any_column_privilege(
                 runtime_role.oid, target.name, 'INSERT'
               )
            OR pg_catalog.has_any_column_privilege(
                 runtime_role.oid, target.name, 'UPDATE'
               )
      )
      AND NOT EXISTS (
        SELECT 1
          FROM acl_tables AS target
          JOIN pg_catalog.pg_class AS relation
            ON relation.oid = pg_catalog.to_regclass(target.name)
          CROSS JOIN LATERAL pg_catalog.aclexplode(
            coalesce(
              relation.relacl,
              pg_catalog.acldefault('r', relation.relowner)
            )
          ) AS privilege
          LEFT JOIN pg_catalog.pg_roles AS grantee
            ON grantee.oid = privilege.grantee
         WHERE privilege.grantee <> relation.relowner
           AND (
             privilege.is_grantable
             OR NOT EXISTS (
               SELECT 1
                 FROM allowed_table_acl AS allowed
                WHERE allowed.table_name = target.name
                  AND allowed.role_name = grantee.rolname
                  AND allowed.privilege_type = privilege.privilege_type
             )
           )
      )
      AND NOT EXISTS (
        SELECT 1
          FROM acl_tables AS target
          JOIN pg_catalog.pg_attribute AS attribute
            ON attribute.attrelid = pg_catalog.to_regclass(target.name)
           AND attribute.attnum > 0
           AND NOT attribute.attisdropped
          JOIN pg_catalog.pg_class AS relation
            ON relation.oid = attribute.attrelid
          CROSS JOIN LATERAL pg_catalog.aclexplode(attribute.attacl) AS privilege
          LEFT JOIN pg_catalog.pg_roles AS grantee
            ON grantee.oid = privilege.grantee
         WHERE privilege.grantee <> relation.relowner
           AND (
             privilege.is_grantable
             OR NOT EXISTS (
               SELECT 1
                 FROM allowed_column_acl AS allowed
                WHERE allowed.table_name = target.name
                  AND allowed.column_name = attribute.attname
                  AND allowed.role_name = grantee.rolname
                  AND allowed.privilege_type = privilege.privilege_type
             )
           )
      )
      AND NOT EXISTS (
        SELECT 1
          FROM target_tables AS target
          JOIN pg_catalog.pg_class AS relation
            ON relation.oid = pg_catalog.to_regclass(target.name)
          CROSS JOIN owner_role
          CROSS JOIN LATERAL pg_catalog.aclexplode(
            coalesce(
              relation.relacl,
              pg_catalog.acldefault('r', relation.relowner)
            )
          ) AS privilege
         WHERE privilege.privilege_type IN (
                 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE'
               )
           AND (
             privilege.grantee NOT IN (relation.relowner, owner_role.oid)
             OR (
               privilege.grantee = owner_role.oid
               AND privilege.is_grantable
             )
           )
      )
      AND NOT EXISTS (
        SELECT 1
          FROM target_tables AS target
          JOIN pg_catalog.pg_attribute AS attribute
            ON attribute.attrelid = pg_catalog.to_regclass(target.name)
           AND attribute.attnum > 0
           AND NOT attribute.attisdropped
          JOIN pg_catalog.pg_class AS relation
            ON relation.oid = attribute.attrelid
          CROSS JOIN owner_role
          CROSS JOIN LATERAL pg_catalog.aclexplode(attribute.attacl) AS privilege
         WHERE privilege.privilege_type IN ('INSERT', 'UPDATE')
           AND (
             privilege.grantee NOT IN (relation.relowner, owner_role.oid)
             OR (
               privilege.grantee = owner_role.oid
               AND privilege.is_grantable
             )
           )
      )
      AND NOT EXISTS (
        SELECT 1
          FROM pg_catalog.pg_class AS relation
          CROSS JOIN runtime_role
          CROSS JOIN LATERAL pg_catalog.aclexplode(
            coalesce(
              relation.relacl,
              pg_catalog.acldefault('r', relation.relowner)
            )
          ) AS privilege
          LEFT JOIN pg_catalog.pg_roles AS grantee
            ON grantee.oid = privilege.grantee
         WHERE relation.oid IN (
                 'public.reports'::regclass,
                 'public.report_image_objects'::regclass
               )
           AND privilege.privilege_type IN (
                 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE'
               )
           AND privilege.grantee <> relation.relowner
           AND NOT (
             NOT privilege.is_grantable
             AND (
               (
                 relation.oid = 'public.reports'::regclass
                 AND (
                   (
                     privilege.privilege_type IN ('INSERT', 'UPDATE')
                     AND privilege.grantee = runtime_role.oid
                   )
                   OR (
                     privilege.privilege_type = 'DELETE'
                     AND grantee.rolname IN (
                       'walksafe_account_deletion_worker',
                       'walksafe_report_deletion_worker'
                     )
                   )
                 )
               )
               OR (
                 relation.oid = 'public.report_image_objects'::regclass
                 AND privilege.privilege_type = 'INSERT'
                 AND privilege.grantee = runtime_role.oid
               )
             )
           )
      )
      AND NOT EXISTS (
        SELECT 1
          FROM pg_catalog.pg_attribute AS attribute
          JOIN pg_catalog.pg_class AS relation
            ON relation.oid = attribute.attrelid
          CROSS JOIN owner_role
          CROSS JOIN LATERAL pg_catalog.aclexplode(attribute.attacl) AS privilege
         WHERE relation.oid IN (
                 'public.reports'::regclass,
                 'public.report_image_objects'::regclass
               )
           AND attribute.attnum > 0
           AND NOT attribute.attisdropped
           AND privilege.privilege_type IN ('INSERT', 'UPDATE')
           AND privilege.grantee <> relation.relowner
           AND NOT (
             relation.oid = 'public.reports'::regclass
             AND attribute.attname = 'updated_at'
             AND privilege.privilege_type = 'UPDATE'
             AND privilege.grantee = owner_role.oid
             AND NOT privilege.is_grantable
           )
      )
      AND EXISTS (
        SELECT 1
          FROM pg_catalog.pg_class AS claim_table
          CROSS JOIN owner_role
         WHERE claim_table.oid =
               'public.admin_report_mutation_claims'::regclass
           AND claim_table.relowner = owner_role.oid
      )
      AND EXISTS (
        SELECT 1
          FROM pg_catalog.pg_constraint AS claim_primary_key
         WHERE claim_primary_key.conrelid =
               'public.admin_report_mutation_claims'::regclass
           AND claim_primary_key.conname =
               'admin_report_mutation_claims_pkey'
           AND claim_primary_key.contype = 'p'
           AND claim_primary_key.convalidated
           AND NOT claim_primary_key.condeferrable
           AND NOT claim_primary_key.condeferred
           AND claim_primary_key.conkey = ARRAY[(
                 SELECT attribute.attnum
                   FROM pg_catalog.pg_attribute AS attribute
                  WHERE attribute.attrelid = claim_primary_key.conrelid
                    AND attribute.attname = 'challenge_id'
                    AND NOT attribute.attisdropped
               )]::smallint[]
      )
      AND EXISTS (
        SELECT 1
          FROM pg_catalog.pg_constraint AS reconfirmation_unique
         WHERE reconfirmation_unique.conrelid =
               'public.admin_report_mutation_claims'::regclass
           AND reconfirmation_unique.conname =
               'admin_report_mutation_claims_reconfirmation_id_key'
           AND reconfirmation_unique.contype = 'u'
           AND reconfirmation_unique.convalidated
           AND NOT reconfirmation_unique.condeferrable
           AND NOT reconfirmation_unique.condeferred
           AND reconfirmation_unique.conkey = ARRAY[(
                 SELECT attribute.attnum
                   FROM pg_catalog.pg_attribute AS attribute
                  WHERE attribute.attrelid = reconfirmation_unique.conrelid
                    AND attribute.attname = 'reconfirmation_id'
                    AND NOT attribute.attisdropped
               )]::smallint[]
      )
      AND NOT EXISTS (
        SELECT required.name
          FROM (VALUES
            (
              'ck_admin_report_mutation_claim_resource_type',
              'CHECK (resource_type = ANY (ARRAY['
              '''original_access_grant''::text, ''review_decision''::text, '
              '''delivery_package''::text, ''delivery_event''::text]))'
            ),
            (
              'ck_admin_report_mutation_claim_action',
              'CHECK (action = ANY (ARRAY['
              '''report.original.grant''::text, '
              '''report.review.decide''::text, '
              '''admin.report.delivery_package.create''::text, '
              '''report.delivery.create''::text]))'
            )
          ) AS required(name, definition)
         WHERE NOT EXISTS (
           SELECT 1
             FROM pg_catalog.pg_constraint AS claim_check
            WHERE claim_check.conrelid =
                  'public.admin_report_mutation_claims'::regclass
              AND claim_check.conname = required.name
              AND claim_check.contype = 'c'
              AND claim_check.convalidated
              AND pg_catalog.pg_get_constraintdef(claim_check.oid, true) =
                  required.definition
         )
      )
      AND EXISTS (
        SELECT 1
          FROM pg_catalog.pg_index AS resource_unique
          JOIN pg_catalog.pg_class AS resource_index
            ON resource_index.oid = resource_unique.indexrelid
         WHERE resource_unique.indrelid =
               'public.admin_report_mutation_claims'::regclass
           AND resource_index.relname =
               'uq_admin_report_mutation_claim_resource'
           AND resource_unique.indisunique
           AND NOT resource_unique.indisprimary
           AND resource_unique.indisvalid
           AND resource_unique.indisready
           AND resource_unique.indislive
           AND resource_unique.indnkeyatts = 2
           AND resource_unique.indnatts = 2
           AND resource_unique.indpred IS NULL
           AND resource_unique.indexprs IS NULL
           AND pg_catalog.pg_get_indexdef(resource_unique.indexrelid) =
               'CREATE UNIQUE INDEX uq_admin_report_mutation_claim_resource '
               'ON public.admin_report_mutation_claims USING btree '
               '(resource_type, resource_id)'
      )
      AND NOT EXISTS (
        SELECT required.name
          FROM (VALUES
            (
              'fk_admin_report_mutation_claim_challenge',
              'challenge_id',
              'public.admin_device_proof_challenges',
              'id'
            ),
            (
              'fk_admin_report_mutation_claim_reconfirmation',
              'reconfirmation_id',
              'public.admin_security_reconfirmations',
              'id'
            )
          ) AS required(name, source_column, target_table, target_column)
         WHERE NOT EXISTS (
           SELECT 1
             FROM pg_catalog.pg_constraint AS claim_foreign_key
            WHERE claim_foreign_key.conrelid =
                  'public.admin_report_mutation_claims'::regclass
              AND claim_foreign_key.conname = required.name
              AND claim_foreign_key.contype = 'f'
              AND claim_foreign_key.convalidated
              AND NOT claim_foreign_key.condeferrable
              AND NOT claim_foreign_key.condeferred
              AND claim_foreign_key.confupdtype = 'a'
              AND claim_foreign_key.confdeltype = 'r'
              AND claim_foreign_key.confmatchtype = 's'
              AND claim_foreign_key.confrelid =
                  pg_catalog.to_regclass(required.target_table)
              AND claim_foreign_key.conkey = ARRAY[(
                    SELECT attribute.attnum
                      FROM pg_catalog.pg_attribute AS attribute
                     WHERE attribute.attrelid = claim_foreign_key.conrelid
                       AND attribute.attname = required.source_column
                       AND NOT attribute.attisdropped
                  )]::smallint[]
              AND claim_foreign_key.confkey = ARRAY[(
                    SELECT attribute.attnum
                      FROM pg_catalog.pg_attribute AS attribute
                     WHERE attribute.attrelid = claim_foreign_key.confrelid
                       AND attribute.attname = required.target_column
                       AND NOT attribute.attisdropped
                  )]::smallint[]
         )
      )
      AND EXISTS (
        SELECT 1
          FROM pg_catalog.pg_constraint AS evidence_constraint
         WHERE evidence_constraint.conrelid =
               'public.report_review_decisions'::regclass
           AND evidence_constraint.conname =
               'ck_report_review_decisions_evidence_grant'
           AND evidence_constraint.contype = 'c'
           AND evidence_constraint.convalidated
           AND pg_catalog.pg_get_constraintdef(
                 evidence_constraint.oid, true
               ) =
               'CHECK (decision::text = ''APPROVED''::text '
               'AND evidence_grant_id IS NOT NULL OR decision::text <> '
               '''APPROVED''::text AND evidence_grant_id IS NULL)'
      )
      AND EXISTS (
        SELECT 1
          FROM pg_catalog.pg_trigger AS ingest_trigger
         WHERE ingest_trigger.tgrelid = 'public.reports'::regclass
           AND ingest_trigger.tgname = 'reports_runtime_ingest_integrity'
           AND NOT ingest_trigger.tgisinternal
           AND ingest_trigger.tgenabled = 'O'
           AND ingest_trigger.tgtype = 7
           AND ingest_trigger.tgqual IS NULL
           AND ingest_trigger.tgnargs = 0
           AND ingest_trigger.tgfoid = pg_catalog.to_regprocedure(
                 'public.walksafe_enforce_runtime_report_ingest_integrity()'
               )
      )
      AND (SELECT pg_catalog.count(*) = 7
             FROM resolved_functions
            WHERE oid IS NOT NULL)
      AND NOT EXISTS (
        SELECT 1
          FROM resolved_functions AS resolved
          LEFT JOIN pg_catalog.pg_proc AS function ON function.oid = resolved.oid
          CROSS JOIN owner_role
          CROSS JOIN runtime_role
         WHERE resolved.oid IS NULL
            OR function.proowner <> owner_role.oid
            OR function.prosecdef IS DISTINCT FROM true
            OR function.proconfig IS DISTINCT FROM
               ARRAY['search_path=pg_catalog, pg_temp']::text[]
            OR NOT pg_catalog.has_function_privilege(
                 owner_role.oid, resolved.oid, 'EXECUTE'
               )
            OR NOT pg_catalog.has_function_privilege(
                 runtime_role.oid, resolved.oid, 'EXECUTE'
               )
            OR EXISTS (
              SELECT 1
                FROM pg_catalog.aclexplode(
                  coalesce(
                    function.proacl,
                    pg_catalog.acldefault('f', function.proowner)
                  )
                ) AS privilege
               WHERE privilege.privilege_type = 'EXECUTE'
                 AND (
                   privilege.grantee NOT IN (function.proowner, runtime_role.oid)
                   OR (
                     privilege.grantee = runtime_role.oid
                     AND privilege.is_grantable
                   )
                 )
            )
      )
      AND (SELECT pg_catalog.count(*) = 2
             FROM resolved_owner_only_functions
            WHERE oid IS NOT NULL)
      AND NOT EXISTS (
        SELECT 1
          FROM resolved_owner_only_functions AS resolved
          LEFT JOIN pg_catalog.pg_proc AS function ON function.oid = resolved.oid
          CROSS JOIN owner_role
          CROSS JOIN runtime_role
         WHERE resolved.oid IS NULL
            OR function.proowner <> owner_role.oid
            OR function.prosecdef IS DISTINCT FROM true
            OR function.proconfig IS DISTINCT FROM
               ARRAY['search_path=pg_catalog, pg_temp']::text[]
            OR NOT pg_catalog.has_function_privilege(
                 owner_role.oid, resolved.oid, 'EXECUTE'
               )
            OR pg_catalog.has_function_privilege(
                 runtime_role.oid, resolved.oid, 'EXECUTE'
               )
            OR EXISTS (
              SELECT 1
                FROM pg_catalog.aclexplode(
                  coalesce(
                    function.proacl,
                    pg_catalog.acldefault('f', function.proowner)
                  )
                ) AS privilege
               WHERE privilege.privilege_type = 'EXECUTE'
                 AND privilege.grantee <> function.proowner
            )
      )
      AND (SELECT pg_catalog.count(*) = 4
             FROM resolved_old_runtime_functions
            WHERE oid IS NOT NULL)
      AND NOT EXISTS (
        SELECT 1
          FROM resolved_old_runtime_functions AS resolved
          LEFT JOIN pg_catalog.pg_proc AS function ON function.oid = resolved.oid
          CROSS JOIN owner_role
          CROSS JOIN runtime_role
         WHERE resolved.oid IS NULL
            OR function.proowner <> owner_role.oid
            OR function.prosecdef IS DISTINCT FROM true
            OR function.proconfig IS DISTINCT FROM
               ARRAY['search_path=pg_catalog, pg_temp']::text[]
            OR NOT pg_catalog.has_function_privilege(
                 owner_role.oid, resolved.oid, 'EXECUTE'
               )
            OR pg_catalog.has_function_privilege(
                 runtime_role.oid, resolved.oid, 'EXECUTE'
               )
            OR EXISTS (
              SELECT 1
                FROM pg_catalog.aclexplode(
                  coalesce(
                    function.proacl,
                    pg_catalog.acldefault('f', function.proowner)
                  )
                ) AS privilege
               WHERE privilege.privilege_type = 'EXECUTE'
                 AND privilege.grantee <> function.proowner
            )
      )
      AND (SELECT pg_catalog.count(*) = 2
             FROM resolved_whitespace_functions
            WHERE oid IS NOT NULL)
      AND NOT EXISTS (
        SELECT 1
          FROM resolved_whitespace_functions AS resolved
          LEFT JOIN pg_catalog.pg_proc AS function ON function.oid = resolved.oid
          CROSS JOIN owner_role
          CROSS JOIN runtime_role
         WHERE resolved.oid IS NULL
            OR function.proowner <> owner_role.oid
            OR function.prosecdef IS DISTINCT FROM false
            OR function.proconfig IS DISTINCT FROM
               ARRAY['search_path=pg_catalog, pg_temp']::text[]
            OR NOT pg_catalog.has_function_privilege(
                 owner_role.oid, resolved.oid, 'EXECUTE'
               )
            OR pg_catalog.has_function_privilege(
                 runtime_role.oid, resolved.oid, 'EXECUTE'
               )
            OR EXISTS (
              SELECT 1
                FROM pg_catalog.aclexplode(
                  coalesce(
                    function.proacl,
                    pg_catalog.acldefault('f', function.proowner)
                  )
                ) AS privilege
               WHERE privilege.privilege_type = 'EXECUTE'
                 AND privilege.grantee <> function.proowner
            )
      )
      AND (SELECT pg_catalog.count(*) = 1
             FROM resolved_session_lock_function
            WHERE oid IS NOT NULL)
      AND NOT EXISTS (
        SELECT 1
          FROM resolved_session_lock_function AS resolved
          LEFT JOIN pg_catalog.pg_proc AS function ON function.oid = resolved.oid
          CROSS JOIN owner_role
          CROSS JOIN runtime_role
          CROSS JOIN owner_memberships
         WHERE resolved.oid IS NULL
            OR function.proowner <> owner_memberships.member
            OR function.prosecdef IS DISTINCT FROM true
            OR function.proconfig IS DISTINCT FROM
               ARRAY['search_path=pg_catalog, pg_temp']::text[]
            OR NOT pg_catalog.has_function_privilege(
                 function.proowner, resolved.oid, 'EXECUTE'
               )
            OR NOT pg_catalog.has_function_privilege(
                 owner_role.oid, resolved.oid, 'EXECUTE'
               )
            OR NOT pg_catalog.has_function_privilege(
                 runtime_role.oid, resolved.oid, 'EXECUTE'
               )
            OR EXISTS (
              SELECT 1
                FROM pg_catalog.aclexplode(
                  coalesce(
                    function.proacl,
                    pg_catalog.acldefault('f', function.proowner)
                  )
                ) AS privilege
               WHERE privilege.privilege_type = 'EXECUTE'
                 AND (
                   privilege.grantee NOT IN (
                     function.proowner, owner_role.oid, runtime_role.oid
                   )
                   OR (
                     privilege.grantee IN (owner_role.oid, runtime_role.oid)
                     AND privilege.is_grantable
                   )
                 )
            )
      )
      AND (SELECT pg_catalog.count(*) = 1
             FROM resolved_session_dependency_function
            WHERE oid IS NOT NULL)
      AND NOT EXISTS (
        SELECT 1
          FROM resolved_session_dependency_function AS resolved
          LEFT JOIN pg_catalog.pg_proc AS function ON function.oid = resolved.oid
          CROSS JOIN runtime_role
          CROSS JOIN owner_memberships
         WHERE resolved.oid IS NULL
            OR function.proowner <> owner_memberships.member
            OR function.prosecdef IS DISTINCT FROM true
            OR function.proconfig IS DISTINCT FROM
               ARRAY['search_path=pg_catalog, pg_temp']::text[]
            OR NOT pg_catalog.has_function_privilege(
                 function.proowner, resolved.oid, 'EXECUTE'
               )
            OR NOT pg_catalog.has_function_privilege(
                 runtime_role.oid, resolved.oid, 'EXECUTE'
               )
            OR EXISTS (
              SELECT 1
                FROM pg_catalog.aclexplode(
                  coalesce(
                    function.proacl,
                    pg_catalog.acldefault('f', function.proowner)
                  )
                ) AS privilege
               WHERE privilege.privilege_type = 'EXECUTE'
                 AND (
                   privilege.grantee NOT IN (
                     function.proowner, runtime_role.oid
                   )
                   OR (
                     privilege.grantee = runtime_role.oid
                     AND privilege.is_grantable
                   )
                 )
            )
      )
      AND (SELECT pg_catalog.count(*) = 17
             FROM resolved_all_functions
            WHERE oid IS NOT NULL)
      AND NOT EXISTS (
        SELECT 1
          FROM resolved_all_functions AS resolved
          LEFT JOIN pg_catalog.pg_proc AS function ON function.oid = resolved.oid
          LEFT JOIN pg_catalog.pg_language AS language
            ON language.oid = function.prolang
          LEFT JOIN expected_function_definitions AS expected
            ON expected.name = function.proname
         WHERE resolved.oid IS NULL
            OR expected.name IS NULL
            OR pg_catalog.encode(
                 pg_catalog.sha256(
                   pg_catalog.convert_to(
                     pg_catalog.pg_get_functiondef(resolved.oid), 'UTF8'
                   )
                 ),
                 'hex'
               ) <> expected.definition_sha256
            OR pg_catalog.pg_get_function_result(resolved.oid) <> expected.result
            OR function.prokind <> 'f'
            OR language.lanname <> CASE
                 WHEN expected.helper THEN 'sql' ELSE 'plpgsql'
               END
            OR function.provolatile <> CASE
                 WHEN expected.helper THEN 'i' ELSE 'v'
               END
            OR function.proisstrict <> expected.helper
            OR function.proparallel <> CASE
                 WHEN expected.helper THEN 's' ELSE 'u'
               END
      ) AS boundary_ready
    """
)


def admin_report_integrity_boundary_state(
    executor,
    *,
    revision: str | None = None,
) -> bool | None:
    """Return ``None`` only for a known predecessor; reject unknown revisions."""

    current_revision = revision
    if current_revision is None:
        current_revision = executor.execute(
            text("SELECT version_num FROM public.alembic_version")
        ).scalar_one_or_none()
    if current_revision in ADMIN_REPORT_INTEGRITY_LEGACY_REVISIONS:
        return None
    if current_revision not in ADMIN_REPORT_INTEGRITY_COMPATIBLE_REVISIONS:
        return False
    return bool(executor.execute(_BOUNDARY_SQL).scalar_one())


__all__ = [
    "ADMIN_REPORT_INTEGRITY_HEAD",
    "ADMIN_REPORT_INTEGRITY_LEGACY_REVISIONS",
    "ADMIN_REPORT_INTEGRITY_COMPATIBLE_REVISIONS",
    "admin_report_integrity_boundary_state",
]
