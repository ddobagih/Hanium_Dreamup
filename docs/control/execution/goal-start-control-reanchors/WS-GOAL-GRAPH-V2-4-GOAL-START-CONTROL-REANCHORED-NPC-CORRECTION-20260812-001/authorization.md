# NPC start-control correction authorization

- Authorization ID: `WS-GOAL-GRAPH-V2-4-NPC-START-CONTROL-CORRECTION-AUTHORIZATION-20260812-001`
- Recorded at: `2026-08-12T23:15:00+09:00`
- Planned event type: `GOAL_START_CONTROL_REANCHORED`
- Planned event ID: `WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-NPC-CORRECTION-20260812-001`
- Planned sequence: `59`
- Target Goal: `WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001`
- Status: `AUTHORIZED_FOR_EXACT_ZERO_CREDIT_CORRECTION`

## Decision and source

Sequence 59 may append one `READY -> READY`, zero-credit correction event. It corrects only the current continuation-checker/gate compatibility discovered by failed start-gate attempt `WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-SINGLE-ADMIN-RECOVERY-20260812-001`. It neither overwrites that evidence nor claims that check 8 or `GOAL_STARTED` occurred.

The bound source is `docs/control/walksafe-project-continuation-checkpoint.json`, SHA-256 `55b2a209679ddb9573abfeb97b0b24112151884e756133a4faef34879257d2d4`, `1770409` bytes. Its tail is sequence 58, SHA-256 `929ff8b16ec13ad9bd697148f6cee600e4491e627339fdf4d2aa325b6a64c38b`; the target is `READY`, no Goal is `IN_PROGRESS`, and R002 remains the active successor. Repository source context is branch `current`, base `f0093863e82bfc80d9f11915cef33a51d44b8730`, head `ca0898d56eaa45b947b9f513a2bcdbfcb5bc5a0c`, 639 paths, path SHA `fcf3627f3beb8675930c990fa9ac336f48012e95b19bf9c78dbf6f26bf4add10`, content SHA `279899fa496301f3c5339fa2f61e43983e25d224e964348ef67c5b76b995c5a3`.

## Failure evidence

Checks 1–6 passed. Check 7 failed because the checker emitted generic inventory wording while its frozen FP008 regression expected count-specific wording. The log `07-ROOT_NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REGRESSION.log` is SHA-256 `d6a2657c3848d292eea440adeb3afc68f210ae3bcc0a60c0a0eb1d1b888bb05b`, 2324 bytes. Check 8 and a success receipt do not exist. This grants no test/start credit.

## Exact integrated control cohort

These existing or already-seq58-managed files must have the exact final SHA-256 shown. The continuation checker itself contains the authorization/review bindings, so its final digest is enforced by the publisher allowlist and deliberately omitted from this table to avoid a checker↔authority hash cycle.

| Path | SHA-256 |
|---|---|
| `scripts/generate_repository_catalogs.py` | `ed08e07baefd6a014fb4bb529642e371614dc5f7472ffee0a848f0091c66971e` |
| `scripts/run_walksafe_test_layers_current.sh` | `f1a19f459ae5cd2cfeacd08bb5c64e64218f746f4ced8940ce3a2e8454f542f7` |
| `tests/test_repository_catalogs.py` | `f2f49c4849df32b26d955c611c989e67573c0904bec5bac26c519f6cc6ef1d28` |
| `docs/catalogs/repository-paths.json` | `45a5cb576f209af5e5123513712d19bae14436e4bb50e71320cfe7c53d446f76` |
| `docs/catalogs/scripts.json` | `22137678e8edc2d1dfdb4cc8a3ed01f203c4aebd84dd1ff010e3d5b8ab0c4985` |
| `docs/catalogs/tests.json` | `96a6fa4526706119997d8ae556bf0f2c13ab8b2cfa8bcf3a5d7a997ed3912668` |
| `scripts/run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py` | `6253f8ca5d54556ff5a0106c2243f0eb2b5a76ef8fc6a65f85491e439e70079d` |
| `tests/test_run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py` | `a371a08fc6936cac855f89e3aebbc82c37805931ed4c5f1ebc89bce349e3ad7f` |
| `scripts/apply_walksafe_npc_single_admin_recovery_goal_started_seq60_20260812.py` | `974538366bda49b087423f805edf182bfe3588d6dc2e1609e99efb5c4b951c4e` |
| `tests/test_apply_walksafe_npc_single_admin_recovery_goal_started_seq60_20260812.py` | `6a67190bb90f23e5130d6103b2084043fd910836c05bb142373f5b7269143147` |
| `tests/test_apply_walksafe_npc_goal_start_control_correction_seq59_20260812.py` | `553d5dfc73329469ee6754e2e2558c0293139e1ed546275fca73d8ba56395a2a` |

The correction apply script and these authorization/review documents are self-referential publication inputs, so they are controlled by exact add-only presence, regular-file/path safety, retained-byte cohort capture, managed-snapshot content hash, event bindings for the documents, and CAS; they are deliberately not self-hashed inside their own source allowlist.

## Event and publication boundary

The event uses the same exact field set as sequence 58. `source_ready_event_binding` remains sequence 57. `contract_supersession` remains exact R001→R002 with reason `CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED`. `previous_event_sha256` is the sequence 58 SHA above. Evidence refs are exactly `FAILED_START_GATE_001_CORRECTION`, `GOAL_START_CONTROL_CORRECTION_AUTHORIZATION`, `GOAL_START_CONTROL_CORRECTION_INDEPENDENT_REVIEW`, and `INITIAL_START_GATE_CONTRACT_SUCCESSOR`.

All status and credit deltas are zero; `implementation_start_authorized=false`; formal tests remain `279/279 NOT_RUN`; five release gates remain unwaived; release is `NOT_ELIGIBLE`.

The publisher must enforce the exact integrated cohort, exact add-only path set, `required ∪ live` reproducible snapshot, branch/head/base ancestry, authority/review bindings, failed evidence, unchanged control projection, event self-seal, public candidate validators, retained input bytes, CAS source bytes, final regular-file mode `0600`, and link count 1. Any mismatch fails closed. Actual publication, a new gate, and sequence-60 start remain separate actions.
