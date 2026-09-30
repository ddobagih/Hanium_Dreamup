# Report-image key rotation with replacement workers

## Scope and current support

This is the required maintenance procedure for S20-51. It documents the current
fail-closed implementation; it does not add hot reload or claim zero downtime.
It applies to the report-image keyring, including raw objects that use the same
manager. Account OTP/HMAC keys and database encryption keys are separate
boundaries and must not be rotated or reused as part of this procedure.

A worker captures a provider snapshot when ReportImageKeyManager is constructed.
Each encryption/decryption key lookup and synchronization reloads the provider
and rejects a changed manifest. A changed provider can therefore make every old
worker unavailable at once. Merely starting some new workers before draining
the old ones is not a supported zero-downtime rotation.

The local key readiness check verifies provider availability, snapshot identity,
and provider authority. It does not by itself prove that the keyring generation
was accepted by PostgreSQL. The existing startup reconciliation must also finish
successfully. Do not use manager.readiness(), /health, or a load-balanced response
from one worker as permission to admit every worker.

## Required order

| Phase | Admission and worker state | Required evidence before proceeding |
| --- | --- | --- |
| Prepare | Existing generation still serves normally | Current accepted generation and canonical manifest; candidate chain, key states, authority and capacity checked |
| Quiesce | Hard admission gate closed; old workers and background key consumers drained and stopped | No new work admitted and no old transactions/key-consuming jobs left in flight |
| Publish | Gate remains closed; no old worker may return to service | New provider snapshot installed completely with its original authority boundary intact |
| Bind | Cold replacement worker remains outside traffic | Normal startup reconciliation and DB generation transaction completed, not merely provider readiness |
| Qualify | Gate remains closed | Each intended worker has the expected generation and successful full /ready checks; controlled application checks accepted |
| Admit | Only qualified replacement workers receive traffic | Old generation instances remain stopped; generation, rollout and readiness evidence recorded |

Use the deployment's existing admission/drain mechanism. This document does not
invent an administrative endpoint, bypass authentication, or authorize production
commands. A readiness cache or an arbitrary sleep is not a drain barrier. Stop
and account for background tasks that encrypt, decrypt, export, purge or reconcile
the same storage before publication. Keep cheap liveness available through the
existing controlled operations path without admitting business traffic.

## Prepare the candidate without changing the serving provider

1. Assign one rotation owner and a maintenance window. Record the accepted DB
   generation, current canonical manifest, active key ID, intended new key ID,
   instance inventory, and the authoritative source of those observations.
2. Construct exactly generation g + 1 with previous_manifest_sha256 equal to the
   currently accepted canonical manifest. Use the parser's normalized manifest
   value, not a hash of arbitrarily formatted JSON file bytes.
3. Add one fresh AES-256 key under a new ID as active. Change the previous active
   key to decrypt-only and retain its original material and fingerprint. Preserve
   all existing IDs and state history. Exactly one key must be active, and material
   must not be reused under a different ID.
4. Keep the candidate outside the serving provider until quiescence. Validate its
   complete shape, chain and authority using the existing approved tooling and
   controlled access. Do not print key material, private-file contents, or KMS
   responses into shell history, tickets, logs or this repository.
5. Check the 64-slot limit before the maintenance window. Do not delete history to
   make room. Normal retired/destroyed transitions remain blocked by
   RETIREMENT_BLOCKED_BACKUP_PROOF_NOT_RUN; rotation is not retirement proof.
   Do not label a healthy old key compromised to bypass that restriction.
6. Account for storage/backups that still require decrypt-only keys. Rotation does
   not rewrite existing ciphertext or prove that retained backups no longer need
   an old key.

## Publish only after quiescence

For secret_file in field, staging and production, preserve a normalized absolute
path outside the upload root, root-owned non-writable ancestry, and a single-link
regular file owned by root and readable by the service's expected group. The
current accepted file modes are 0440 or 0640. No symlinks, writable ancestry,
service-owned production key file, environment-variable secret material or
PostgreSQL secret storage may replace this boundary.

Use the authorized secret-deployment mechanism to stage a complete private file,
make data and directory publication durable, and replace the serving file
atomically without weakening directory authority. Never rewrite the serving file
in place while consumers are running. A race detected by the provider remains a
failure, not a reason to relax its identity checks.

For kms_agent, stage and activate the snapshot through the authorized agent
operations path while workers are stopped. Preserve root-authoritative endpoint
ancestry, the separate configured peer UID, exact framing, and the existing
bounded exchange. Do not replace a refused KMS response with a local plaintext
fallback. Agent operations and real credentials are not executed by this change.

Keep the admission gate closed throughout publication and startup. Publication
success alone is not rollout success.

## Cold startup, readiness, then admission

1. Start a replacement worker against the published provider. A new manager must
   capture the new snapshot; mutating private fields or reusing an old manager is
   not supported.
2. Let the existing startup reconciliation call manager.synchronize(db) inside
   its normal transaction and locking boundary. It accepts the next valid
   generation or the exact already-accepted manifest, and rejects chain rollback,
   skipped/reused generations, invalid state transitions and removed key IDs.
   Do not insert/delete keyring event rows manually or bypass a failed lifespan.
3. If startup fails after the generation may have committed, leave the candidate
   provider in place and the gate closed. Determine stored history through the
   approved operations path; retry a cold startup with the exact same candidate.
   An exact committed manifest is idempotent. Do not manufacture a higher
   generation for every retry or infer rollback from a lost response.
4. Check each replacement instance, not repeated requests to an arbitrary
   load-balancer target. Require successful startup, the expected generation in
   report_image_encryption, and the complete existing /ready contract. Its other
   security, DB, storage and feature checks remain enforced.
5. Perform controlled application checks under existing authorization: new
   ciphertext uses the new active ID and an existing image using a retained
   decrypt-only key can still be read with its normal purpose-limited access.
   Do not create a new raw-data exemption or reuse a consumed viewing token.
6. Admit only these qualified workers, then restore ordinary traffic. Record
   per-instance startup/readiness observations and whether the gate was reopened.
   Keep old workers stopped; they do not hot-reload the new snapshot.

## Failure and rollback rules

| Observation | Required action |
| --- | --- |
| Provider missing, malformed, authority-invalid or unavailable | Keep traffic closed; repair the provider publication/authority, not the validation rules |
| Old worker reports report_image_keyring_restart_required | Keep it drained and replace it; do not clear the check or keep retrying business requests |
| Provider check is ready but startup rejects generation chain | Keep the worker out of service; local provider readiness cannot override DB history |
| Generation commit response is lost or any later startup step fails | Preserve the same candidate, retain evidence, determine stored state and retry exact startup safely |
| Previous manifest restored after a committed newer generation | Keep traffic closed; the older chain cannot pass synchronization even if its local provider check is ready |
| Old ciphertext cannot be decrypted | Do not delete ciphertext or old key history; inspect retained material/state and storage evidence under approved access |
| Full readiness still fails | Do not admit on liveness alone or bypass unrelated checks; resolve or explicitly handle that failed dependency |

Return to the exact old provider is permitted only when the operator has positive
evidence that no newer generation committed and no object/job used the candidate.
If either fact is uncertain, do not attempt a blind rollback. Once a new generation
is committed, retain it or prepare a valid forward generation that preserves all
required keys and history. Never reactivate a decrypt-only key or remove a key ID
to simulate rollback.

A viewing response lost after its purpose-limited grant was consumed is a separate
authorization recovery: request a new authorized grant under the existing policy.
Do not reactivate the old token or claim the server observed client receipt.

## Evidence and implementation references

Record only non-secret generation/manifest/key-ID metadata, deployment change ID,
rotation owner, drain and startup outcomes, each qualified worker, readiness
observations, application-check outcomes, and the decision to admit or keep closed.
Record a failure, its correction, and each retry separately.

Relevant implementation:
- /home/ddobagi/Hanium_Dreamup/backend/app/services/report_image_keys.py:
  provider authority, captured snapshot, fail-closed use, generation/state validation.
- /home/ddobagi/Hanium_Dreamup/backend/app/main.py:
  manager creation, startup reconciliation, and manager injection into health.
- /home/ddobagi/Hanium_Dreamup/backend/app/api/health.py:
  encryption readiness and the existing combined /ready contract.
- /home/ddobagi/Hanium_Dreamup/backend/app/services/report_image_crypto.py:
  authenticated envelopes retaining the key ID used for each stored image.

The local regression in
/home/ddobagi/Hanium_Dreamup/backend/tests/test_report_image_key_rotation_lifecycle.py
uses real private temporary secret-file publication, real managers/parser/AES-GCM,
and the real synchronization function. Only DB history persistence and commit
fault outcomes are simulated. Multiple manager instances represent worker
snapshots; they are not an actual process rollout or load-balancer test.

These regressions do not prove production ownership/ACLs, PostgreSQL locking or
durability, real KMS behavior, deployment drain, backup recoverability, actual
application grant recovery, or zero downtime. This runbook is a procedure, not a
receipt that an operational rotation was performed.
