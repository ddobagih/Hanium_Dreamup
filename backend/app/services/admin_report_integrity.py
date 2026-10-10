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
        "202610070001",
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
         '2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249',
         'TABLE(result_status text, decision_id uuid, decision_revision bigint)', false),
        ('walksafe_assert_admin_totp_capability',
         'c6a95b20292cd519a5a346edbbbf9753b0158bbceb1264d9dc70d72d1d6e5b87',
         'boolean', false),
        ('walksafe_claim_admin_report_mutation',
         '71951738802e93f6ec8d6aaca97b4f1fbfe88e7c408793b77e84275b62cd8d7d',
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

_PRE_DECISION_IDEMPOTENCY_BOUNDARY_SQL = text(
    _BOUNDARY_SQL.text.replace(
        "2b68a3968fa183f5c2682246c8048c9220ffa802ca55b97f782905bc08d26249",
        "80a983dd5bb8fd3b65d102573e58167e7c26de39a7b5b8ff440b545a08c10035",
    ).replace(
        "71951738802e93f6ec8d6aaca97b4f1fbfe88e7c408793b77e84275b62cd8d7d",
        "59ae7764df9427c0876c793ae41cdab1944ba31bcc93ce0d659a5b5aa25888d6",
    )
)

# 202609240001 changes this pg_get_functiondef result; 202609240002 keeps it.
_DUPLICATE_CYCLE_BOUNDARY_SQL = text(
    _BOUNDARY_SQL.text.replace(
        "21f28f5e68d48e0ab9566d3f837c978ce3e147fee0c7d35131af7fc22f691c2d",
        "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
    )
)


# 202609280001 adds the v4 review (expected_review_revision) and delivery
# (follow-up observation) runtime functions; v3 stays executable.
_REVIEW_DELIVERY_V4_BOUNDARY_SQL = text(
    _DUPLICATE_CYCLE_BOUNDARY_SQL.text.replace(
        "        ('public.walksafe_append_report_delivery_event_v3('\n"
        "         'uuid,uuid,bigint,bigint,uuid,text,text,text,text,text,text,text,'\n"
        "         'timestamptz,text,uuid,text,uuid,uuid,bytea,text,text)')\n"
        "    ),\n",
        "        ('public.walksafe_append_report_delivery_event_v3('\n"
        "         'uuid,uuid,bigint,bigint,uuid,text,text,text,text,text,text,text,'\n"
        "         'timestamptz,text,uuid,text,uuid,uuid,bytea,text,text)'),\n"
        "        ('public.walksafe_append_report_review_decision_v4('\n"
        "         'uuid,uuid,bigint,bigint,text,text,text,uuid,boolean,boolean,'\n"
        "         'boolean,uuid,text,uuid,text,uuid,uuid,bytea,text,text)'),\n"
        "        ('public.walksafe_append_report_delivery_event_v4('\n"
        "         'uuid,uuid,bigint,bigint,uuid,text,text,text,text,text,text,text,'\n"
        "         'timestamptz,text,uuid,text,uuid,uuid,bytea,text,text)')\n"
        "    ),\n",
        1,
    ).replace(
        "    expected_function_definitions(name, definition_sha256, result, helper) AS (\n"
        "      VALUES\n",
        "    expected_function_definitions(name, definition_sha256, result, helper) AS (\n"
        "      VALUES\n"
        "        ('walksafe_append_report_delivery_event_v4',\n"
        "         'cd591e5b30b9f2d15adce31e283834a24df63883f4f96ebdf4ff6b58f9d7e421',\n"
        "         'TABLE(result_status text, event_id uuid, event_revision bigint)', false),\n"
        "        ('walksafe_append_report_review_decision_v4',\n"
        "         '84e0cbbf23cc5c3bb5df8b3cfd1be5be111ef12fbf0658e953ce0a4f1beaf84d',\n"
        "         'TABLE(result_status text, decision_id uuid, decision_revision bigint)', false),\n",
        1,
    ).replace(
        "      AND (SELECT pg_catalog.count(*) = 7\n"
        "             FROM resolved_functions\n",
        "      AND (SELECT pg_catalog.count(*) = 9\n"
        "             FROM resolved_functions\n",
        1,
    ).replace(
        "      AND (SELECT pg_catalog.count(*) = 17\n"
        "             FROM resolved_all_functions\n",
        "      AND (SELECT pg_catalog.count(*) = 19\n"
        "             FROM resolved_all_functions\n",
        1,
    )
)
# 202609280002 only widens the reconfirmation issuer allowlist and 202609290001
# and 202609290002 only change administrator device keys, and 202609300001/
# 202609300002/202609300003/202609300004 only change privacy deletion evidence,
# the privacy HMAC keyring, and installation evidence keys, so all keep the
# 202609280001 report function boundary.
ADMIN_REPORT_REVIEW_DELIVERY_V4_REVISIONS = frozenset(
    {
        "202609280001",
        "202609280002",
        "202609290001",
        "202609290002",
        "202609300001",
        "202609300002",
        "202609300003",
        "202609300004",
    }
)

_DELIVERY_EVENT_ARGS_SQL = (
    "         'uuid,uuid,bigint,bigint,uuid,text,text,text,text,text,text,text,'\n"
    "         'timestamptz,text,uuid,text,uuid,uuid,bytea,text,text)'"
)


_CLAIM_PRIMARY_KEY_ANCHOR = (
    "      AND EXISTS (\n"
    "        SELECT 1\n"
    "          FROM pg_catalog.pg_constraint AS claim_primary_key\n"
)


def _correction_key_check_sql(alias: str, name: str, contype: str, column: str) -> str:
    return (
        "      AND EXISTS (\n"
        "        SELECT 1\n"
        f"          FROM pg_catalog.pg_constraint AS {alias}\n"
        f"         WHERE {alias}.conrelid =\n"
        "               'public.report_institution_delivery_corrections'::regclass\n"
        f"           AND {alias}.conname = '{name}'\n"
        f"           AND {alias}.contype = '{contype}'\n"
        f"           AND {alias}.convalidated\n"
        f"           AND NOT {alias}.condeferrable\n"
        f"           AND NOT {alias}.condeferred\n"
        f"           AND {alias}.conkey = ARRAY[(\n"
        "                 SELECT attribute.attnum\n"
        "                   FROM pg_catalog.pg_attribute AS attribute\n"
        f"                  WHERE attribute.attrelid = {alias}.conrelid\n"
        f"                    AND attribute.attname = '{column}'\n"
        "                    AND NOT attribute.attisdropped\n"
        "               )]::smallint[]\n"
        "      )\n"
    )


# tgtype 58 = BEFORE (2) | DELETE (8) | UPDATE (16) | TRUNCATE (32), FOR EACH STATEMENT.
_DELIVERY_CORRECTION_KEY_CHECKS = (
    _correction_key_check_sql(
        "correction_primary_key", "pk_report_delivery_corrections", "p",
        "corrects_event_id",
    )
    + _correction_key_check_sql(
        "correction_challenge_unique", "uq_report_delivery_correction_challenge", "u",
        "proof_challenge_id",
    )
    + _correction_key_check_sql(
        "correction_reconfirmation_unique",
        "uq_report_delivery_correction_reconfirmation", "u", "reconfirmation_id",
    )
    + "      AND EXISTS (\n"
    "        SELECT 1\n"
    "          FROM pg_catalog.pg_trigger AS correction_trigger\n"
    "         WHERE correction_trigger.tgrelid =\n"
    "               'public.report_institution_delivery_corrections'::regclass\n"
    "           AND correction_trigger.tgname =\n"
    "               'report_institution_delivery_corrections_append_only'\n"
    "           AND NOT correction_trigger.tgisinternal\n"
    "           AND correction_trigger.tgenabled = 'O'\n"
    "           AND correction_trigger.tgtype = 58\n"
    "           AND correction_trigger.tgqual IS NULL\n"
    "           AND correction_trigger.tgnargs = 0\n"
    "           AND correction_trigger.tgfoid = pg_catalog.to_regprocedure(\n"
    "                 'public.walksafe_reject_audit_mutation()'\n"
    "               )\n"
    "      )\n"
)


def _replace_once(source: str, old: str, new: str) -> str:
    if source.count(old) != 1:
        raise RuntimeError("administrator report boundary template is unexpected")
    return source.replace(old, new, 1)


def _delivery_correction_boundary_sql() -> str:
    source = _REVIEW_DELIVERY_V4_BOUNDARY_SQL.text
    for anchor in (
        "    ),\n    runtime_ingest_tables(name) AS (\n",
        "    ),\n    owner_select_tables(name) AS (\n",
        "    ),\n    owner_update_columns(table_name, column_name) AS (\n",
    ):
        source = _replace_once(
            source,
            "        ('public.report_institution_delivery_events')\n" + anchor,
            "        ('public.report_institution_delivery_events'),\n"
            "        ('public.report_institution_delivery_corrections')\n" + anchor,
        )
    source = _replace_once(
        source,
        "        ('public.report_institution_delivery_events'),\n"
        "        ('public.report_export_audits')\n",
        "        ('public.report_institution_delivery_events'),\n"
        "        ('public.report_institution_delivery_corrections'),\n"
        "        ('public.report_export_audits')\n",
    )
    source = _replace_once(
        source,
        "        ('public.report_institution_delivery_events', "
        "'walksafe_report_evidence_owner', 'INSERT')\n"
        "    ),\n    allowed_column_acl(",
        "        ('public.report_institution_delivery_events', "
        "'walksafe_report_evidence_owner', 'INSERT'),\n"
        "        ('public.report_institution_delivery_corrections', "
        "'walksafe_backend_runtime', 'SELECT'),\n"
        "        ('public.report_institution_delivery_corrections', "
        "'walksafe_report_evidence_owner', 'SELECT'),\n"
        "        ('public.report_institution_delivery_corrections', "
        "'walksafe_report_evidence_owner', 'INSERT')\n"
        "    ),\n    allowed_column_acl(",
    )
    # The correction-unaware v3/v4 delivery functions move from the runtime
    # set to the owner-only predecessor set; v5 and the correction replace them.
    source = _replace_once(
        source,
        "        ('public.walksafe_append_report_delivery_event_v3('\n"
        + _DELIVERY_EVENT_ARGS_SQL + "),\n"
        "        ('public.walksafe_append_report_review_decision_v4('",
        "        ('public.walksafe_append_report_review_decision_v4('",
    )
    source = _replace_once(
        source,
        "        ('public.walksafe_append_report_delivery_event_v4('\n"
        + _DELIVERY_EVENT_ARGS_SQL + ")\n"
        "    ),\n    owner_only_functions(signature) AS (\n",
        "        ('public.walksafe_append_report_delivery_event_v5('\n"
        + _DELIVERY_EVENT_ARGS_SQL + "),\n"
        "        ('public.walksafe_correct_report_delivery_event('\n"
        "         'uuid,uuid,uuid,bigint,text,text,text,uuid,text,uuid,uuid,bytea,'\n"
        "         'text,text,text)')\n"
        "    ),\n    owner_only_functions(signature) AS (\n",
    )
    source = _replace_once(
        source,
        "         'text,uuid,text,uuid,timestamptz,text,text)')\n"
        "    ),\n    whitespace_functions(signature) AS (\n",
        "         'text,uuid,text,uuid,timestamptz,text,text)'),\n"
        "        ('public.walksafe_append_report_delivery_event_v3('\n"
        + _DELIVERY_EVENT_ARGS_SQL + "),\n"
        "        ('public.walksafe_append_report_delivery_event_v4('\n"
        + _DELIVERY_EVENT_ARGS_SQL + ")\n"
        "    ),\n    whitespace_functions(signature) AS (\n",
    )
    source = _replace_once(
        source,
        "    expected_function_definitions(name, definition_sha256, result, helper) AS (\n"
        "      VALUES\n",
        "    expected_function_definitions(name, definition_sha256, result, helper) AS (\n"
        "      VALUES\n"
        "        ('walksafe_append_report_delivery_event_v5',\n"
        "         'f30d3777378a39256c8c9d1483d698eb800d2bc3e5de57fcbbe3cd4215115e00',\n"
        "         'TABLE(result_status text, event_id uuid, event_revision bigint)', false),\n"
        "        ('walksafe_correct_report_delivery_event',\n"
        "         'b9abd98fba709af773a6be45c81d889d3e17cf4bef6078c80d205229ecd831f6',\n"
        "         'TABLE(result_status text, correction_id uuid, corrected_event_id uuid)', false),\n",
    )
    source = _replace_once(
        source,
        "      AND (SELECT pg_catalog.count(*) = 4\n"
        "             FROM resolved_old_runtime_functions\n",
        "      AND (SELECT pg_catalog.count(*) = 6\n"
        "             FROM resolved_old_runtime_functions\n",
    )
    # S18-40-F5: one correction per event (PK), one correction per device
    # proof and per reconfirmation (UNIQUE), and the append-only trigger are
    # pinned like the mutation-claim keys, so owner-side drift fails closed.
    source = _replace_once(
        source,
        _CLAIM_PRIMARY_KEY_ANCHOR,
        _DELIVERY_CORRECTION_KEY_CHECKS + _CLAIM_PRIMARY_KEY_ANCHOR,
    )
    return _replace_once(
        source,
        "      AND (SELECT pg_catalog.count(*) = 19\n"
        "             FROM resolved_all_functions\n",
        "      AND (SELECT pg_catalog.count(*) = 21\n"
        "             FROM resolved_all_functions\n",
    )


# 202609300005 (S18-40) adds the append-only delivery correction table, the
# correction function and delivery v5 (transition base skips corrected
# events), and revokes runtime EXECUTE on the correction-unaware v3/v4.
_DELIVERY_CORRECTION_BOUNDARY_SQL = text(_delivery_correction_boundary_sql())
ADMIN_REPORT_DELIVERY_CORRECTION_REVISIONS = frozenset({"202609300005"})

# 202609300006 (owner decision R3) rewrites only the integrated-consent version
# literals of the runtime report ingest trigger (FP-013 1.1.0 -> 1.2.0).
_CONSENT_V12_BOUNDARY_SQL = text(
    _replace_once(
        _DELIVERY_CORRECTION_BOUNDARY_SQL.text,
        "37d67daddfca6121cc6cde66b0375561f49395b3f0c33a4afe757dfe7c2ff013",
        "bdb19747b1347dceccd00698dfb88c26e59dfb77b1cdcf4d53baf278bd56d3e2",
    )
)
ADMIN_REPORT_CONSENT_V12_REVISIONS = frozenset({"202609300006"})
# 202609300007 (S1617-59) lets the account deletion worker read only the copy
# identity columns (id, report_id, package_id) of delivery events.
_ACCOUNT_COPY_INVENTORY_BOUNDARY_SQL = text(
    _replace_once(
        _CONSENT_V12_BOUNDARY_SQL.text,
        "('public.report_institution_delivery_events', 'observed_at', "
        "'walksafe_report_deletion_worker', 'SELECT')\n    ),",
        "('public.report_institution_delivery_events', 'observed_at', "
        "'walksafe_report_deletion_worker', 'SELECT'),\n"
        "        ('public.report_institution_delivery_events', 'id', "
        "'walksafe_account_deletion_worker', 'SELECT'),\n"
        "        ('public.report_institution_delivery_events', 'report_id', "
        "'walksafe_account_deletion_worker', 'SELECT'),\n"
        "        ('public.report_institution_delivery_events', 'package_id', "
        "'walksafe_account_deletion_worker', 'SELECT')\n    ),",
    )
)
# 202609300008 (S18-5) only widens an attempt CHECK outside the boundary.
ADMIN_REPORT_ACCOUNT_COPY_INVENTORY_REVISIONS = frozenset({"202609300007", "202609300008"})

# 202609300009 changes the inner review function to require direct links to
# representative reports, while preserving its original cycle guard.
_DUPLICATE_REPRESENTATIVE_BOUNDARY_SQL = text(
    _replace_once(
        _replace_once(
            _ACCOUNT_COPY_INVENTORY_BOUNDARY_SQL.text,
            "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924",
            "c2fb02f571021324c9ced411354abca78ea8a25b01590c3b68c27ffc51e6cb37",
        ),
        "      ) AS boundary_ready\n    ",
        """      )
      AND EXISTS (
        SELECT 1
          FROM pg_catalog.pg_trigger AS duplicate_trigger
          JOIN pg_catalog.pg_proc AS duplicate_function
            ON duplicate_function.oid = duplicate_trigger.tgfoid
          JOIN pg_catalog.pg_language AS duplicate_language
            ON duplicate_language.oid = duplicate_function.prolang
         WHERE duplicate_trigger.tgname = 'report_review_duplicate_representative'
           AND duplicate_trigger.tgrelid =
               'public.report_review_decisions'::regclass
           AND duplicate_trigger.tgtype = 7
           AND duplicate_trigger.tgenabled = 'O'
           AND NOT duplicate_trigger.tgisinternal
           AND duplicate_trigger.tgnargs = 0
           AND duplicate_trigger.tgqual IS NULL
           AND duplicate_function.oid = pg_catalog.to_regprocedure(
               'public.walksafe_enforce_duplicate_representative()'
           )
           AND duplicate_function.proowner = (
               SELECT oid FROM pg_catalog.pg_roles
                WHERE rolname = 'walksafe_report_evidence_owner'
           )
           AND duplicate_function.prosecdef
           AND duplicate_function.proconfig =
               ARRAY['search_path=pg_catalog, pg_temp']::text[]
           AND duplicate_function.prokind = 'f'
           AND duplicate_function.provolatile = 'v'
           AND NOT duplicate_function.proisstrict
           AND duplicate_function.proparallel = 'u'
           AND duplicate_language.lanname = 'plpgsql'
           AND pg_catalog.pg_get_function_result(duplicate_function.oid) =
               'trigger'
           AND pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
               pg_catalog.pg_get_functiondef(duplicate_function.oid), 'UTF8'
           )), 'hex') =
               'dab50537fbfc918e37ab8f6e693b1ea9868d421dc6c75c92131ebd1d331a43b3'
           AND NOT EXISTS (
               SELECT 1 FROM pg_catalog.aclexplode(coalesce(
                   duplicate_function.proacl,
                   pg_catalog.acldefault('f', duplicate_function.proowner)
               )) AS privilege
                WHERE privilege.privilege_type = 'EXECUTE'
                  AND privilege.grantee <> duplicate_function.proowner
           )
      ) AS boundary_ready
    """,
    )
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
    if current_revision in {"202609300009", "202610070001"}:
        boundary_sql = _DUPLICATE_REPRESENTATIVE_BOUNDARY_SQL
    elif current_revision in ADMIN_REPORT_ACCOUNT_COPY_INVENTORY_REVISIONS:
        boundary_sql = _ACCOUNT_COPY_INVENTORY_BOUNDARY_SQL
    elif current_revision in ADMIN_REPORT_CONSENT_V12_REVISIONS:
        boundary_sql = _CONSENT_V12_BOUNDARY_SQL
    elif current_revision in ADMIN_REPORT_DELIVERY_CORRECTION_REVISIONS:
        boundary_sql = _DELIVERY_CORRECTION_BOUNDARY_SQL
    elif current_revision in ADMIN_REPORT_REVIEW_DELIVERY_V4_REVISIONS:
        boundary_sql = _REVIEW_DELIVERY_V4_BOUNDARY_SQL
    elif current_revision in {"202609240001", "202609240002"}:
        boundary_sql = _DUPLICATE_CYCLE_BOUNDARY_SQL
    elif current_revision in {"202609010003", "202609210001"}:
        boundary_sql = _BOUNDARY_SQL
    else:
        boundary_sql = _PRE_DECISION_IDEMPOTENCY_BOUNDARY_SQL
    return bool(executor.execute(boundary_sql).scalar_one())


__all__ = [
    "ADMIN_REPORT_INTEGRITY_HEAD",
    "ADMIN_REPORT_INTEGRITY_LEGACY_REVISIONS",
    "ADMIN_REPORT_INTEGRITY_COMPATIBLE_REVISIONS",
    "ADMIN_REPORT_REVIEW_DELIVERY_V4_REVISIONS",
    "ADMIN_REPORT_DELIVERY_CORRECTION_REVISIONS",
    "ADMIN_REPORT_CONSENT_V12_REVISIONS",
    "ADMIN_REPORT_ACCOUNT_COPY_INVENTORY_REVISIONS",
    "admin_report_integrity_boundary_state",
]
