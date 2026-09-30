# Android debug storage migration

The opt-in debug endpoints now use `android_debug.sqlite3`. The old depth and
capture JSONL files are read only once for import and are never rewritten or
deleted by the new version. The import rows and `legacy_import_complete` marker
commit in the same SQLite transaction. An interrupted import rolls back and is
retried on the next start. Timestamps, including timezone offsets and
microseconds, remain in the stored JSON; UTC microseconds are indexed for expiry.

## Cutover

1. Stop **all** processes running the old backend, including workers and replicas.
   Keep debug traffic blocked until the migration is complete. The old binary
   does not participate in the new file lock and must not run concurrently.
2. Back up the whole `ANDROID_DEBUG_LOG_DIR` offline, preserving `0600` files,
   `0700` directories, ownership, both JSONL files, and `frame_captures/`.
   Keep the backup outside the live directory. Do not move or truncate the
   originals. Confirm the backup is restorable before proceeding.
3. Deploy the new version with the same `ANDROID_DEBUG_LOG_DIR`. With debug
   logging enabled only in a non-deployment environment, make one authorized
   `/android/debug/depth-logs/recent` request while traffic remains blocked.
   This creates the private database, imports both JSONL streams, and commits
   the marker. A large import can take time; do not start the old binary to
   serve requests while it runs. If import fails on malformed or unsafe legacy
   data, stop and investigate the offline copy rather than editing live JSONL.
4. Confirm the request succeeds and the expected recent depth and capture
   records are available, then reopen debug traffic. The new code checks the
   legacy files' identity, size, modification, and change time on each request
   without reading their contents. Any change fails closed; stop all writers and
   reconcile the backup before continuing. This check does not make an old
   concurrently running binary safe: step 1 is mandatory.

The database, images, and directories must remain service-owned and private.
Symlinked storage paths are refused. SQLite uses its synchronous rollback
journal and a cross-process lock on the database file. Expiry removes only
indexed rows older than the configured UTC cutoff; image deletion is retried
after restart for UUID-named orphan captures. Original JSONL and backup files
are not subject to the new expiry process.

## Rollback boundary

Before the **first new write**, stop the new version and restore the old binary;
the unchanged JSONL files remain the old version's source of truth. After the
first new write, they no longer contain all records. Do **not** simply restart
the old binary or delete the database: that would lose new depth logs and
capture references. Stop traffic, preserve the SQLite database and image tree,
export/reconcile the new records with a reviewed procedure, and only then
decide whether rollback is acceptable. No automatic reverse migration is
provided.
