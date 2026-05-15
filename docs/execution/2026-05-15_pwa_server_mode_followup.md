# 2026-05-15 PWA Server-Mode Follow-up

## Scope

- PWA static validation for server detector build wiring.
- No app code changes kept.
- No Android, browser runtime, or manual accessibility/PWA behavior claims.

## Baseline git status

Command:

```bash
git status --short
```

Result before validation:

```text
 M daylog/2026-05-15.md
?? docs/execution/2026-05-15_backend_validation.md
?? docs/execution/2026-05-15_disk_cleanup_candidates.md
?? docs/execution/2026-05-15_installed_program_usage_candidates.md
?? docs/execution/2026-05-15_low_risk_cleanup_result.md
?? docs/execution/2026-05-15_model_validation.md
?? docs/execution/2026-05-15_obs_removal_attempt.md
?? docs/execution/2026-05-15_parallel_validation_summary.md
?? docs/execution/2026-05-15_pwa_validation.md
?? docs/execution/2026-05-15_voice_validation.md
?? docs/execution/2026-05-15_wine_obs_snap_cleanup_attempt.md
?? docs/execution/2026-05-15_wine_obs_snap_cleanup_result.md
```

`apps/web/next-env.d.ts` had no diff before validation.

## Commands and results

```bash
cd apps/web && npm run lint && npm run typecheck
```

Result: PASS, exit code 0.

- `npm run lint`: `eslint app lib types --max-warnings=0` completed successfully.
- `npm run typecheck`: `tsc --noEmit` completed successfully.

```bash
cd apps/web && NEXT_PUBLIC_DETECTOR_MODE=server NEXT_PUBLIC_API_BASE_URL=http://127.0.0.1:8000 npm run build
```

Result: PASS, exit code 0.

- Next.js `16.2.6` with Turbopack.
- Build completed production optimization and TypeScript.
- Static routes emitted: `/`, `/_not-found`, `/admin`.

## Generated churn handling

`next build` dirtied `apps/web/next-env.d.ts`:

```diff
-import "./.next/dev/types/routes.d.ts";
+import "./.next/types/routes.d.ts";
```

Because the file was clean before this lane and the change was generated build churn, it was restored with:

```bash
git checkout -- apps/web/next-env.d.ts
```

Post-restore check: `git diff -- apps/web/next-env.d.ts` produced no output.

## Runtime checks still pending

- Browser runtime server-mode check with `NEXT_PUBLIC_DETECTOR_MODE=server` and reachable backend/model.
- `/detect/health` and `/detect` runtime request/response verification.
- Camera permission, frame capture, server bbox rendering, and report payload source verification.
- PWA install/offline behavior and accessibility checks.
- Android/real-device validation.
