# WalkSafe WP001 bootstrap draft R008 independent boundary review R001

```text
review_id = WS-WALKSAFE-WP001-BOOTSTRAP-DRAFT-R008-R001-INDEPENDENT-BOUNDARY-REVIEW-R001
review_type = INDEPENDENT_PUBLICATION_PATH_SANDBOX_RUNTIME_MANIFEST_BOUNDARY_REVIEW
reviewer_agent = /root/wp001_boundary_review
reviewer_session = /root/wp001_boundary_review@2026-08-02T06:22:29+09:00
independence_attestation = 다른 WP001 E2 source review 결과를 읽지 않고 frozen source와 normative controls만 정적으로 검수했다.
target_draft_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r008-r001
target_evidence_root = /home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r008-r001
target_draft_seal_file_sha256 = db763531df2bee5f5a581bc06f7a9a367eb36c5dd1d965afdeb0f4e8d6a12c51
target_draft_manifest_sha256 = a7a64ddc93dace6cd4116fd58821ab240b639a55156989bb1450bf0feecc0397
target_draft_post_sha256 = a305729aa86beefb979a81f7e25cf5aa8fa92857967c3646d8cebddd4b80300d
target_verifier_sha256 = b9fcec59c4499ac25a7e5072c371c840c904bf018dc4f1e9c1f5f3cb10796245
target_roadmap_sha256 = af375aac6e3a7d0849103cf073e75e9ce96a57ec1dbcb0a69be8e828584b8ed4
verdict = REVISION_REQUIRED
blocking = 6
major = 4
minor = 2
```

## Method and scope

- Candidate exact-nine/evidence exact-four inventories, modes, owners, link counts, sizes and SHA-256 values were compared with the detached manifest, seal, `draft-post.json`, and `E1.COMPLETE` anchors.
- The four Python files were read with line numbers and parsed with `ast.parse` only. No candidate was executed, imported, byte-compiled, or pycompiled.
- R008 and its inherited R005-R007 controls were read for the declared threat model and acceptance contract. Host runtime metadata, ELF dependency declarations, and the fixed tar member-name inventory were inspected read-only. No network was used.
- The other WP001 E2 source review was not opened. Current R008 plan-review identities/verdict fields were checked only because they are frozen normative inputs.
- This review counts only reproducible violations inside the declared model. Malicious concurrent same-UID/root mutation is out of scope; an exact-looking root without an external success receipt is intentionally incomplete; current source non-execution and zero progress/credit are required, not defects.

## Blocking findings

### B-01 — Host fixed-FD map is consumed in the reverse direction

`run-readonly-sandbox.py:318-345` builds maps as `fixed_fd -> actual_open_fd`, and `:675-678` passes that shape unchanged to `run_bwrap()`. But `duplicate_map()` at `:544-547` interprets every item as `old_fd -> new_fd`; `run_bwrap()` calls it at `:549-560` and then executes FD 200. A normal clean descriptor table therefore attempts `fcntl()` on closed FD 200 and fails with `EBADF`. If an ambient FD happens to occupy 200-224, the code can duplicate an unverified object instead of the pinned input. The child-side map at `:487-492` uses the opposite, correct shape and confirms the intended direction.

- Successor closure predicate: every verified actual FD maps to its declared fixed destination; immediately before fd-exec, each fixed FD has the same `(dev, ino)` as the verified source, and ambient occupants cannot be selected.
- Verification class: `STATIC_REVIEW` plus later `SANDBOX_EXECUTION` with both a sanitized descriptor table and hostile ambient occupants in 200-224.

### B-02 — Frozen `libm.so.6` mode contradicts both the host and normative closure

`run-readonly-sandbox.py:50-52` and `runtime-closure.json:1` require mode `0755` for `/usr/lib/x86_64-linux-gnu/libm.so.6`. The pinned host file is mode `0644`, 1,198,376 bytes, with the declared SHA-256, and R005 line 220 also fixes `0644`. Because `verify_regular_expected()` compares the mode at `run-readonly-sandbox.py:310-315`, `open_runtime()` fails before Bubblewrap can start.

- Successor closure predicate: code, runtime closure, normative table, and pinned host FD all agree on mode `0644`, size, and SHA-256.
- Verification class: `STATIC_REVIEW` plus later host preflight success and a wrong-mode negative fixture.

### B-03 — Projection producer and both readers use incompatible canonical JSON policies

`build-projection.py:117-120` emits projection JSON with `ensure_ascii=True`. The controller canonicalizer at `run-readonly-sandbox.py:101-103,268-279,588-603` and independent verifier canonicalizer at `verify-bootstrap.py:76-103,446-452` require `ensure_ascii=False` bytes. This is not hypothetical for the fixed input: tar member 2332 is `docs/submission/drafts/워크세이프_산출물_소개서_20260731.docx`, which enters the tar/Git/physical inventories at `build-projection.py:491-521`. The producer emits `\uXXXX` escapes, while both readers reserialize literal UTF-8 and reject the valid producer output as noncanonical.

- Successor closure predicate: producer, controller, and verifier serialize the same projection object to byte-identical canonical JSON, including the fixed Korean basename.
- Verification class: `STATIC_REVIEW` plus later sandbox acceptance of the fixed non-ASCII input and rejection of the opposite escaping policy.

### B-04 — Repository control inputs are incorrectly required to be mode `0600`

The general `regular()` helper at `verify-bootstrap.py:151-160` hard-codes `0600/1000:1000/nlink1`; `verify_controls()` applies it to every `CURRENT+HISTORY` file at `:186-197`. R008 lines 58-60 bind literal files under the repository control directory. All 15 frozen files are regular `0664`, uid/gid `1000/1000`, nlink 1, and their sizes and hashes match the verifier constants. The inherited `0600` rule applies to draft/evidence outputs (R005 lines 71-95 and R006 lines 76-85), not repository control documents. Consequently, correctly bound `verify-draft` and `verify-e1` invocations always fail.

- Successor closure predicate: metadata policy is caller-specific; repository controls accept their frozen `0664` identities while draft, final, evidence, and backup files retain their respective exact policies. No chmod or copied control surrogate is permitted.
- Verification class: `STATIC_REVIEW`; later verifier fixtures should accept the literal controls and reject a hash-, owner-, type-, link-, or mode-mismatched substitute.

### B-05 — Verifier reads can combine metadata/SHA from one version with bytes from another

`verify-bootstrap.py:111-133` scans, then rereads; `regular()` at `:151-160` performs another outer scan and returns that first `info` with bytes from the later read. The scans are never compared. The final check at `:131-132` uses a fresh current mode on both sides and omits mtime/ctime, and there is no post-read pathname-to-FD alias check after `:154-155`. Benign drift can therefore yield version-A metadata/SHA with version-B parsed bytes, or leave the accepted FD detached from the literal pathname. This violates the in-scope pre/use/post CAS requirement in R005 lines 34-38.

- Successor closure predicate: one bounded read produces bytes, digest, and metadata under equal pre/post full stat keys, followed by a post-read no-follow pathname identity check; any drift fails closed.
- Verification class: `STATIC_REVIEW` plus later in-place and atomic-replacement drift fixtures.

### B-06 — No independent oracle closes the exact projection inventory and manifest

The producer records detailed tar, Git, and worktree physical inventories at `build-projection.py:491-526`. Yet `verify-bootstrap.py:446-478` checks only branch/status, the missing set, untracked count 2550, absence of ignored paths, and three policy fields. It never compares the exact untracked paths to tar paths, independently walks the `.git`-excluded physical tree, or validates the declared inventory rows/digests. The controller at `run-readonly-sandbox.py:598-613` compares `repository_tree_digest` only if present, but the producer emits no such field. Thus a path substitution hidden by `--untracked-files=normal`, an extra empty directory, or forged inventory declarations can receive PASS despite R005 lines 267-279.

- Successor closure predicate: controller and independent verifier recompute and compare the exact tar-to-Git path relation and the declared physical file/directory/type/mode/nlink/hash inventory; no optional absent digest may bypass comparison.
- Verification class: `STATIC_REVIEW` plus later path-substitution, empty-directory, hardlink, special-file, and mount-crossing negative fixtures.

## Major findings

### M-01 — Frozen Git argv contract does not describe the commands the builder records

`runtime-closure.json:1` declares clone paths `/inputs/repository-history.bundle` and `/work/projection/repo`, and status `-C /work/projection/repo`. `build-projection.py:115,160-162,226-231,579-587` instead constructs `/proc/self/fd/<dynamic-fd>` paths for clone and every `-C`, then records those argv values. There is no normalization rule, so the frozen closure and actual projection manifest cannot be byte-exact descriptions of the same commands.

- Successor closure predicate: declared clone/status argv and recorded argv are byte-identical, or both sides apply one frozen, role-based FD normalization whose output is independently checked.
- Verification class: `STATIC_REVIEW` plus later exact comparison with `command_observations[].argv`.

### M-02 — Source-input/runtime semantic bindings are mostly unchecked

`verify-bootstrap.py:200-218` checks the runtime schema string, top-level source keys, current/history arrays, closure size/hash, and only `future_output_count`. It does not validate the source manifest's backup contract, `runtime.apply_patch`, the remaining prohibition counters/roles, or the structure and values of `runtime-closure.json`. R005 lines 281-285 require those backup/runtime/apply-patch bindings, not merely a hash link to an uninterpreted object.

- Successor closure predicate: exact schemas and values for backup, apply-patch, runtime mounts/FD map/environment/Git/output contracts, and all prohibitions are independently matched to frozen constants and physical identities.
- Verification class: `STATIC_REVIEW` plus later malformed-nested-manifest rejection fixtures.

### M-03 — E1 recovery verification spot-checks rather than reconstructs the canonical evidence contract

`verify-bootstrap.py:227-270` omits authorization status/scope, draft-post schema/root/status/NUL-name digest, observation creation order and roles, argv/patch/output/Bubblewrap identities, source static-execution claim, and every `deltas` field. It can therefore accept evidence that contradicts R006 lines 94-115 and 139-150 even after the control-mode defect is fixed.

- Successor closure predicate: authorization, draft-post, observation, and marker schemas have exact keys and values, and every required zero delta/prior invocation identity is recomputed or matched to a frozen external observation without self/future claims.
- Verification class: `STATIC_REVIEW` plus later one-field-at-a-time evidence mutation fixtures.

### M-04 — Negative oracle does not enforce all required invariants

`verify-bootstrap.py:514-545` never snapshots or rechecks `pass_fd`, does not recheck the sentinel after the second run, omits output-root metadata from `tree_snapshot()`, and does not require failure output to assert `INCOMPLETE_UNTRUSTED` with zero success receipts. These omissions are weaker than the negative postconditions in R005 lines 272-279.

- Successor closure predicate: protected/preexisting objects and output-root metadata are unchanged after both runs, failure status and zero-receipt claims are parsed exactly, allowed output remains an exact permitted subset, and rerun changes nothing.
- Verification class: `STATIC_REVIEW` plus later pass-FD mutation, second-run sentinel mutation, root chmod, and malformed-failure-receipt fixtures.

## Minor findings

### m-01 — Projection-manifest open failure masks its defined failure class

In `build-projection.py:528-540`, `fd` is assigned inside `try` but unconditionally closed in `finally`. If `os.open()` fails, `UnboundLocalError` masks the original error and becomes `E99:INTERNAL`; write/fsync errors can likewise bypass the intended `E_MANIFEST` classification.

- Successor closure predicate: open/write/fsync/fstat/close failures clean up only initialized FDs and all terminate under the frozen manifest-I/O failure class.
- Verification class: `STATIC_REVIEW` plus later open/ENOSPC/fsync fault injection.

### m-02 — Publication post-write I/O errors escape `PUBLISH_IO`

The path post-check at `publish-source.py:176-177` and final directory fsync at `:209` are outside local `OSError` conversion, while close failures at `:173-175` are ignored. They can produce `INTERNAL` or overlook a close error after publication side effects instead of the declared `PUBLISH_IO` failure.

- Successor closure predicate: every output open/write/fsync/fstat/pathstat/close failure is fail-closed and reported under the frozen publication-I/O class.
- Verification class: `STATIC_REVIEW` plus later syscall fault injection and exact error JSON/return-code checks.

## Confirmed non-findings and deferred bindings

- The current draft exact-nine, payload-seven manifest entries, detached seal, modes/owners/nlinks, evidence exact-four, and the three requested target hashes are internally consistent. All four candidate Python files pass static AST parsing.
- `publish-source.py` uses literal single-component names, pinned dirfds, `O_NOFOLLOW`, `O_EXCL`, source `nlink=1`, canonical manifest equality, file fsync, and final directory fsync. A partial or even exact-looking root without the external publication receipt remains intentionally `INCOMPLETE_UNTRUSTED`; no rollback or resume is required.
- No finding is assigned for post-close mutation by a malicious concurrent same-UID/root writer because R005 explicitly excludes that actor.
- Exact future output roots, caller FD bindings, reviewed seal, source-review identities, receipt capture, and execution authorization must be frozen by the separately reviewed publication/projection plan as README lines 13-16 require. Their current deferral is not counted as a source defect.
- Current source execution count zero, absent final publication, zero canonical/product/formal/device/Gate/release/progress delta, and `NOT_ELIGIBLE` are the required R008 state, not omissions.

## Verdict

`REVISION_REQUIRED`. Six blocking findings preclude `PASS`. The sealed R008 source must not be executed or published; closure requires an add-only successor and fresh independent review under the roadmap's fail-successor rule.
