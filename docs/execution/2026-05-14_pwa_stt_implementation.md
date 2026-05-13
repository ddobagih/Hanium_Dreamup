# 2026-05-14 PWA STT Implementation

## Scope

- Implemented PWA-side voice command recording and `/speech/stt` upload only.
- Touched frontend files only: `apps/web/app/page.tsx`, `apps/web/lib/voice-api.ts`.
- Did not edit YOLO/model files, datasets, raw archives, backend Python, or voice Python files.

## Environment

The web app reads:

```bash
NEXT_PUBLIC_VOICE_API_BASE=http://127.0.0.1:9001
```

This is now listed in `apps/web/.env.example`. If the variable is unset, the PWA falls back to `http://127.0.0.1:9001` for local development. The frontend sends recorded audio to:

```text
${NEXT_PUBLIC_VOICE_API_BASE}/speech/stt
```

as multipart/form-data field `audio`.

## UI Flow

1. The existing camera, detection, voice-warning toggle, and report button remain in place.
2. The assist panel now includes a compact voice command status tile.
3. The new voice command button starts a `MediaRecorder` microphone recording.
4. Tapping again, or waiting up to 5 seconds, stops recording and uploads the blob to `/speech/stt`.
5. The UI shows transcript, intent, confidence, status, and server/API errors.
6. Supported client-side intents:
   - `create_report`
   - `voice_on`
   - `voice_off`
   - `repeat_last`
   - `set_destination`
   - `start_navigation`
   - `get_current_location`
   - `unknown`

## Verification

From `apps/web`:

```bash
npm run lint
npm run typecheck
```

Result: both commands passed.

Manual end-to-end microphone/STT verification still requires the local voice server to be running and reachable from the browser at `NEXT_PUBLIC_VOICE_API_BASE`.
