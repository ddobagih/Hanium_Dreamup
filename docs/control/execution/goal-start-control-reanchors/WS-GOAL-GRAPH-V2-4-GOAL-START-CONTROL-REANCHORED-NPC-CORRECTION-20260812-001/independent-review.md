# NPC start-control correction independent review

- Review ID: `WS-GOAL-GRAPH-V2-4-NPC-START-CONTROL-CORRECTION-REVIEW-20260812-001`
- Reviewed at: `2026-08-12T23:18:01+09:00`
- Reviewed authorization: `authorization.md`
- Authorization SHA-256: `d12ce6bc2b741cb583ea3ddefae2ea3b09852d215cf49f488c4e4550fc0deb8f`
- Authorization byte count: `5273`
- Planned event ID: `WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-NPC-CORRECTION-20260812-001`
- Planned sequence: `59`
- Finding count: `BLOCKING 0 / MAJOR 0 / MINOR 0`
- Verdict: `PASS_FOR_EXACT_ZERO_CREDIT_CORRECTION`

## Independent findings

The source checkpoint independently matches SHA-256 `55b2a209679ddb9573abfeb97b0b24112151884e756133a4faef34879257d2d4`, 1770409 bytes, regular mode `0600`, link count 1. Its tail is sequence 58, SHA-256 `929ff8b16ec13ad9bd697148f6cee600e4491e627339fdf4d2aa325b6a64c38b`; the target remains `READY`, R002 remains bound, and no Goal is `IN_PROGRESS`.

The failed attempt has only logs 1–7. Logs 1–6 passed. Log 7, SHA-256 `d6a2657c3848d292eea440adeb3afc68f210ae3bcc0a60c0a0eb1d1b888bb05b`, proves a count-specific versus generic inventory-message assertion mismatch. No eighth log or success receipt exists. The attempt therefore grants no successful-gate or start credit.

The integrated correction is narrow: the continuation checker recognizes immutable seq58 and correction seq59 separately, preserves nine-check FP008/FP046 and eight-check NPC inventory wording, and provides lineage for a later seq60 start. The current test registry and generated catalogs register the new correction/start cohort. Independent physical hashing reproduced every final digest in the authorization table exactly.

The event remains append-only `GOAL_START_CONTROL_REANCHORED`, `READY -> READY`. It preserves sequence 57 readiness, sequence 58 repository/control history, R001→R002 supersession, all Goal status, current-work, canonical, artifact, completion, formal-test and release state. Its previous transition is sequence 58, not sequence 57.

## Adversarial boundary

Publication must reject any source/tail drift; missing failed-gate evidence; changed integrated digest; add-only path mismatch; unsafe/symlink/hardlink path; stale branch/head/base; non-reproducible managed snapshot; authority/review drift; missing or surplus event field; altered contract binding; status/credit drift; event self-seal failure; public-validator failure; retained-input drift; CAS drift; or non-regular/non-`0600`/multi-link output.

The self-referential correction script and authority documents cannot safely pin their own bytes within themselves. The authorization correctly closes this without a hash cycle by requiring exact path membership, regular files, retained input handles/bytes, the final managed content-set hash, document event bindings, and CAS. A new gate must use a new event directory and never overwrite the failed attempt.

## Verdict

`PASS_FOR_EXACT_ZERO_CREDIT_CORRECTION`. This permits only exact preparation and conditional publication of sequence 59. It does not authorize product implementation, external action, actual-device testing, deployment, signing, release, completion, the next gate, or sequence-60 start. Any integration change after this review makes it stale and must be re-reviewed.
