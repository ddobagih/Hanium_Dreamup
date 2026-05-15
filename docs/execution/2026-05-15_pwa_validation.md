# 2026-05-15 PWA/Accessibility Validation Lane

## Scope

- Read/validate only; no app code changes kept.
- Result document: `docs/execution/2026-05-15_pwa_validation.md`.
- Manual phone/PWA/accessibility checks were not assumed.

## Baseline workspace

```bash
git status --short --untracked-files=all
```

Result: no output; working tree was clean before validation.

Validation environment:

- `NEXT_PUBLIC_DETECTOR_MODE=<unset>`; PWA code defaults to `fake`.
- `NEXT_PUBLIC_API_BASE_URL=<unset>`; detector API client defaults to `http://localhost:8000`.

## Static validation commands

```bash
cd apps/web && npm run lint && npm run typecheck && npm run build
```

Result: PASS.

- `npm run lint`: `eslint app lib types --max-warnings=0` completed with exit code 0.
- `npm run typecheck`: `tsc --noEmit` completed with exit code 0.
- `npm run build`: `next build` completed with exit code 0.
  - Next.js `16.2.6` with Turbopack.
  - Build output included static routes `/`, `/_not-found`, `/admin`.

Note: `next build` rewrote `apps/web/next-env.d.ts` from dev route types to production route types. Because this lane must not keep app code changes and the pre-validation status was clean, that generated change was restored.

## ADB check

```bash
if command -v adb >/dev/null 2>&1; then adb devices; else echo "adb not found"; fi
```

Result: `adb` was available, but no Android device was listed.

Observed output:

```text
* daemon not running; starting now at tcp:5037
* daemon started successfully
List of devices attached
```

Android device connected: NO.

## PWA detector mode wiring inspection

Relevant static wiring:

- `apps/web/app/page.tsx`
  - `DETECTOR_MODE = process.env.NEXT_PUBLIC_DETECTOR_MODE ?? "fake"`.
  - `fake` mode starts the fake detection interval only when `DETECTOR_MODE === "fake"` and the camera is ready.
  - `server` mode starts server detection only when `DETECTOR_MODE === "server"` and the camera is ready.
  - Server mode calls `fetchDetectHealth()`, captures a camera frame, then calls `detectFrame(...)` on a 2800 ms interval.
- `apps/web/lib/detector.ts`
  - `createFakeDetection(...)` emits `source: "fake"`.
- `apps/web/lib/detect-api.ts`
  - `fetchDetectHealth()` calls `/detect/health`.
  - `detectFrame(...)` posts image/context to `/detect` and maps accepted detections to `source: "server"`.
- `apps/web/types/inference.ts`
  - `DetectorSource` includes `"fake" | "onnx" | "server"`.

Static validation coverage conclusion:

- `lint`, `typecheck`, and `build` cover the fake/server source files and imports at static compile level.
- This run did not execute browser runtime paths for fake or server mode.
- Because `NEXT_PUBLIC_DETECTOR_MODE` was unset during validation, the effective build-time default is `fake`; server mode still needs a separate runtime/manual check with `NEXT_PUBLIC_DETECTOR_MODE=server`, camera permission, and a ready backend/model endpoint.
- Static validation does not prove Android phone, camera, GPS, heading, TTS, vibration, PWA install/offline shell, or accessibility behavior.

## Blockers and not-executed checks

- Android device was not connected, so no real-phone validation was executed.
- No manual accessibility checks were executed, including TalkBack, keyboard/focus order, touch target, visible label, color/contrast, or screen reader announcements.
- No browser E2E was executed for camera permission, fake bbox readability, reporting, `/admin`, PWA install, or offline shell.
- No backend/model server was started; `/detect/health` and `/detect` were not called at runtime.
- Server detector mode was inspected statically only.

## Parent follow-up

- Connect Android device and run explicit manual PWA/accessibility checklist.
- If server detector validation is required, run the PWA with `NEXT_PUBLIC_DETECTOR_MODE=server` and a reachable backend/model, then verify `/detect/health`, `/detect`, bbox rendering, and report payload source.
- Decide whether to keep documenting `next build` changes to `apps/web/next-env.d.ts` as expected generated churn or adjust the workflow to avoid dirtying the tree.
