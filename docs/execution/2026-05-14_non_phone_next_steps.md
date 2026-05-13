# 2026-05-14 Non-Phone Next Steps

작업 위치: `/home/ddobagi/Code/hanium-dreamup`

범위:

- 실폰 검증 없이 로컬 브라우저, 로컬 voice 서버, FastAPI 백엔드에서 진행 가능한 다음 작업만 정리한다.
- 앱/백엔드/voice 코드는 이번 작업에서 수정하지 않았다.
- 다른 에이전트가 데이터셋, 모델 정리, 학습 산출물 검증을 병렬로 진행한다는 전제로, 모델 산출물 자체를 이동하거나 Git에 추가하지 않는다.

## 현재 코드 기준 결론

### PWA

- `apps/web/app/page.tsx`는 후면 카메라를 `getUserMedia({ audio: false, video: ... })`로 열고 있다.
- 현재 음성 관련 UI는 음성 안내 on/off 토글이다. 음성 명령 녹음 버튼은 없다.
- `speechSynthesis` 기반 브라우저 TTS와 Vibration API fallback은 이미 있다.
- `MediaRecorder`, `NEXT_PUBLIC_VOICE_API_BASE`, `/speech/stt` 호출, voice intent handler는 아직 없다.
- 신고 흐름은 `handleReport()`에 모여 있고, `create_report` intent는 이 함수를 재사용해야 한다.
- `repeat_last`, `set_destination`, `start_navigation`, `get_current_location` 처리를 위한 `lastSpokenMessageRef`, `destination`, `navigationActive` 상태는 아직 없다.

### Voice/STT

- `voice/server.py`는 로컬 voice API를 제공한다.
  - `GET /health`
  - `POST /speech/intent`
  - `POST /speech/stt`
  - `POST /speech/tts`
- `/speech/stt`는 multipart field 이름을 `audio`로 받는다.
- `/speech/stt` 응답은 `transcript`, `intent`, `confidence`, `score`, `slots`, `language`, `duration_sec`, `model`, `segments`를 반환한다.
- `voice/intents.py`의 현재 intent는 `set_destination`, `create_report`, `voice_off`, `voice_on`, `repeat_last`, `start_navigation`, `unknown`이다.
- 실제 사람 음성 `지금 어디야?` 샘플은 현재 `unknown`으로 남아 있으므로, 위치 안내를 지원하려면 `get_current_location`을 voice와 PWA 양쪽에 같은 이름으로 추가해야 한다.
- 현재 `voice/server.py`에는 CORS 설정이 없다. PWA dev server `http://localhost:3000` 또는 `http://127.0.0.1:3000`에서 `http://127.0.0.1:9001`로 직접 호출하려면 voice 서버 CORS가 먼저 필요하다.

### Backend `/detect`

- `backend/app/config.py`는 `MODEL_ARTIFACT_PATH`만 모델 관련 설정으로 읽는다.
- `backend/app/detector.py`는 모델 파일 존재 여부만 보고, 실제 adapter는 아직 구현하지 않는다.
- `MODEL_ARTIFACT_PATH`가 없으면 `/detect/health`는 `model_not_configured`를 반환한다.
- `MODEL_ARTIFACT_PATH` 파일이 있어도 현재는 `model_adapter_not_implemented`를 반환한다.
- `/detect` 요청 계약은 이미 고정되어 있다.
  - multipart part: `context`, `image`
  - `context`: `DetectContext` JSON 문자열
  - `image`: 신고 업로드와 같은 이미지 검증 정책
  - 성공 응답: `DetectResponse(model_status="ready", model_version, detections)`
- `/detect` 성공 시 각 detection은 `ReportMetadata` 검증을 통과해야 하고 `source`는 `server`여야 한다.

## PWA STT 연결 설계

### 환경변수

다음 PR에서 `apps/web/.env.example`에 추가한다.

```env
NEXT_PUBLIC_VOICE_API_BASE=http://127.0.0.1:9001
```

계약:

- 브라우저 빌드 시점 공개 변수이므로 서버 비밀값을 넣지 않는다.
- 값이 없으면 로컬 개발 기본값 `http://127.0.0.1:9001`을 사용하거나, 명시적으로 음성 명령 UI만 disabled 처리한다. 둘 중 하나를 PR에서 고정한다.
- 실폰에서는 `127.0.0.1`이 휴대폰 자신을 가리키므로, 실폰 검증 단계에서 LAN IP 또는 ADB reverse 전략을 별도로 정한다. 이번 문서는 실폰 검증을 다루지 않는다.

### Voice API client

다음 PR에서 `apps/web/lib/voice-api.ts` 같은 얇은 client를 추가한다.

요청:

- URL: `${NEXT_PUBLIC_VOICE_API_BASE}/speech/stt`
- method: `POST`
- body: `FormData`
- field: `audio`
- filename: MIME에 맞춰 `command.webm`, `command.m4a`, `command.wav` 중 하나
- `cache: "no-store"`
- timeout: `AbortController`로 6초 내 실패 처리

응답 타입:

```ts
type SpeechSttResponse = {
  transcript: string;
  intent: VoiceIntent;
  confidence: number;
  score: number;
  slots: Record<string, unknown>;
  language: string | null;
  duration_sec: number;
  model: string;
  segments: unknown[];
};
```

`confidence`와 `score`는 현재 서버가 같은 값을 반환하지만, PWA는 `confidence ?? score ?? 0` 순서로 읽는 fallback을 둔다.

### MediaRecorder 흐름

현재 카메라 stream은 `audio: false`이므로 마이크 권한은 음성 명령 버튼에서 별도 요청한다.

권장 흐름:

1. `navigator.mediaDevices?.getUserMedia`와 `window.MediaRecorder` 지원 여부를 확인한다.
2. 지원하지 않으면 음성 명령만 disabled 처리하고 기존 카메라, 신고, 위험 TTS/진동은 유지한다.
3. 마이크 권한 요청:

```ts
navigator.mediaDevices.getUserMedia({
  audio: {
    echoCancellation: true,
    noiseSuppression: true,
    autoGainControl: true
  },
  video: false
});
```

4. MIME 선택 우선순위:
   - `audio/webm;codecs=opus`
   - `audio/webm`
   - `audio/mp4`
   - 브라우저 기본값
5. 녹음은 1회 탭으로 시작하고, 두 번째 탭 또는 최대 5초에서 stop한다.
6. 실제 명령 목표 길이는 1~4초로 둔다.
7. stop 후 모든 audio track을 정리한다.
8. Blob이 비어 있으면 `/speech/stt`를 호출하지 않고 재발화 안내만 한다.
9. 업로드 중에는 중복 호출을 막고, 실패해도 기존 터치 버튼 상태는 유지한다.

### Intent handler

공통 실행 기준:

- `intent === "unknown"`이면 아무 파괴적 동작도 실행하지 않는다.
- `confidence < 0.7`이면 아무 동작도 실행하지 않고 "다시 말씀해 주세요"를 안내한다.
- STT 응답 처리 중 앱 상태가 바뀌었을 수 있으므로 `create_report`는 기존 `canReport`와 `handleReport()` 검증을 그대로 통과해야 한다.

Intent별 처리:

| intent | PWA 처리 |
| --- | --- |
| `create_report` | 현재 `handleReport()` 호출. 위험 없음, 카메라 미준비, 전송 중 상태는 기존 버튼과 같은 메시지를 사용 |
| `voice_on` | `speechEnabled=true`, 짧은 확인 안내, 약한 진동 |
| `voice_off` | `speechEnabled=false`, 음성 대신 짧은 진동 확인. 이후 위험은 기존 silent vibration 패턴 사용 |
| `repeat_last` | `lastSpokenMessageRef`에 저장된 마지막 안내를 다시 재생. 없으면 재발화 요청 |
| `set_destination` | `slots.destination` 문자열을 `destination` state에 저장하고 "목적지 저장됨" 안내. 경로 API는 아직 호출하지 않음 |
| `start_navigation` | `destination`이 있으면 `navigationActive=true`; 없으면 "목적지를 먼저 말씀해 주세요" 안내 |
| `get_current_location` | 채택 시 GPS/정확도/방향을 짧게 안내. GPS가 없으면 위치 권한 필요 안내 |
| `unknown` | 재발화 요청. 신고, 목적지 변경, navigation 상태 변경 금지 |

`get_current_location`은 다음 PR에서 채택하는 쪽을 권장한다. 실제 사람 음성 샘플에 이미 `지금 어디야?`가 있고, PWA는 현재 `gps`, `gpsError`, `heading` 상태를 갖고 있어 실폰 없이도 브라우저 권한 mock 또는 수동 위치 권한 환경에서 handler 단위 테스트가 가능하다.

### Fallback

| 상황 | 처리 |
| --- | --- |
| `NEXT_PUBLIC_VOICE_API_BASE` 미설정 | 음성 명령 disabled 또는 로컬 기본값 사용. PR에서 하나로 고정 |
| voice 서버 미실행/네트워크 실패 | "다시 말씀해 주세요" 또는 화면 상태 오류 후 기존 터치 UI 유지 |
| CORS 실패 | voice 서버 CORS PR로 해결. PWA는 네트워크 실패와 동일하게 처리 |
| `/speech/stt` 503 | STT 일시 사용 불가로 처리. 위험 TTS/진동과 신고 버튼은 유지 |
| timeout | 녹음/업로드 상태 해제, 재발화 안내, 기존 UI 유지 |
| 마이크 권한 거부 | 음성 명령만 비활성화. 카메라 stream에는 영향 주지 않음 |
| `MediaRecorder` 미지원 | 음성 명령만 비활성화. Web Speech TTS와 진동은 유지 |
| `confidence < 0.7` | 아무 intent도 실행하지 않음 |
| `unknown` | 아무 intent도 실행하지 않음 |
| `speechSynthesis` 미지원 | 화면 상태와 진동만 사용 |
| `speechEnabled=false` | 명령 확인음도 최소화하고 진동 중심으로 처리. 위험 경고는 기존 강한 진동 fallback 유지 |

현재 service worker는 GET 요청만 cache fallback을 걸고 있다. `/speech/stt`는 POST라 캐시되지 않으며, PWA client는 `cache: "no-store"`를 명시한다.

## Backend `/detect` adapter 전 계약

### `MODEL_ARTIFACT_PATH`

현재 v2 후보:

```text
runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt
```

로컬 절대 경로 예:

```env
MODEL_ARTIFACT_PATH=/home/ddobagi/Code/hanium-dreamup/runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt
```

주의:

- `runs/`, `.pt`, `.onnx`는 Git 추적 대상이 아니다.
- adapter PR은 모델 파일을 커밋하지 않고 env로 주입한다.
- 백엔드 프로세스가 읽을 수 없는 경로면 `/detect/health`는 `unavailable`과 명시적 reason을 반환해야 한다.
- ONNX export가 완료되면 같은 env에 `.onnx` 경로를 넣을 수 있지만, adapter 구현 PR은 지원 포맷을 하나로 먼저 좁히는 것이 낫다.

### model version

API에 반환할 문자열은 사람이 읽을 수 있고 artifact를 역추적할 수 있어야 한다.

권장 초기값:

```text
walksafe-kr-tactile-v2-full-20260514-best-02a6be87
```

근거:

- run: `walksafe_kr_tactile_v2_full`
- artifact: `best.pt`
- freeze date: `2026-05-14`
- SHA256 prefix: `02a6be87`

계약:

- `/detect/health`가 ready일 때 `model_version`을 반환한다.
- `/detect` 성공 응답도 같은 `model_version`을 반환한다.
- adapter 내부 로그나 문서에는 가능하면 full SHA256을 남긴다.
- model version은 threshold를 포함하지 않는다. threshold는 실행 설정으로 분리해 기록한다.

### class order

백엔드, 프론트, v2 dataset의 클래스 순서는 같다.

| class_id | class_name |
| ---: | --- |
| 0 | `damaged_tactile_block` |
| 1 | `parked_kickboard_bicycle` |
| 2 | `construction_obstacle` |
| 3 | `pothole` |

계약:

- adapter는 모델 metadata 또는 `data.yaml`의 class order가 위 순서와 다르면 ready가 되면 안 된다.
- `/detect` 결과의 `class_name`은 backend `CLASS_NAMES[class_id]`로 매핑한다.
- 모델이 다른 이름을 내보내더라도 API 응답은 위 canonical name만 사용한다.
- class mismatch는 추론 중 보정하지 말고 startup/load 단계에서 실패시키는 편이 안전하다.

### threshold

현재 문서화된 초기 후보:

| 목적 | confidence threshold |
| --- | ---: |
| API 연결 smoke | `0.15` |
| 데모/신고 후보 | `0.35` |
| 자동 위험 안내 후보 | `0.50` 이상에서 false positive 확인 후 결정 |

adapter PR 기본값 후보:

```env
DETECT_CONFIDENCE_THRESHOLD=0.35
DETECT_NMS_IOU_THRESHOLD=0.7
```

계약:

- `/detect`는 threshold 미만 detection을 반환하지 않는다.
- 빈 결과는 오류가 아니라 `model_status: "ready"`, `detections: []`로 반환한다.
- `LOW_CONFIDENCE_THRESHOLD=0.7`은 현재 신고 운영 review flag 기준이다. detector threshold와 혼동하지 않는다.
- smoke PR에서는 `DETECT_CONFIDENCE_THRESHOLD=0.15`로 낮춰 wiring을 확인할 수 있어야 한다.
- 자동 TTS/진동 경고를 실제 위험 안내로 켤 기준은 `0.50` 이상에서 false positive를 본 뒤 PWA 쪽 정책으로 따로 확정한다.
- threshold 값은 `model_version`에 넣지 않고, `/detect/health` 확장 또는 로그/문서에 별도로 남긴다. API schema 확장은 별도 PR로 판단한다.

### `/detect` 성공 응답 세부 규칙

- `captured_at`: `context.captured_at`이 있으면 사용, 없으면 서버 UTC now.
- `gps`, `heading`: `context` 값을 그대로 전달.
- `source`: 항상 `server`.
- `bbox`: 원본 업로드 이미지 기준 normalized `x`, `y`, `width`, `height`.
- `confidence`: `0..1`.
- NMS 후 confidence 내림차순 정렬.
- 각 item은 `ReportMetadata`로 검증한 뒤 `DetectResponse`에 넣는다.
- 모델 로드 실패는 `/detect/health`에서 `unavailable` reason으로 노출하고, `/detect`는 현재와 같은 `503 model_unavailable` 계열로 유지한다.

## 바로 다음 PR 단위

### PR 1: voice 서버 CORS와 STT 오류 계약 정리

목표:

- PWA dev server가 `http://127.0.0.1:9001/speech/stt`를 브라우저에서 호출할 수 있게 한다.

범위:

- `voice/server.py`
- 필요 시 voice 문서

내용:

- `VOICE_CORS_ORIGINS=http://localhost:3000,http://127.0.0.1:3000` 추가.
- FastAPI `CORSMiddleware` 추가.
- `/speech/stt` 503 응답은 기존 유지.
- 빈 파일, 미지원 content type 같은 입력 오류를 명시할지 결정한다.
- `GET /health`에 CORS origin을 굳이 노출할 필요는 없다.

검증:

- voice 서버 기동 후 browser-origin preflight 또는 curl OPTIONS 확인.
- 기존 `POST /speech/intent`와 `POST /speech/stt` 응답 shape 유지.

### PR 2: PWA STT client와 MediaRecorder 녹음 UI

목표:

- 실폰 없이 데스크톱 브라우저에서 짧은 음성 명령을 녹음해 `/speech/stt`로 보낸다.

범위:

- `apps/web/.env.example`
- `apps/web/lib/voice-api.ts`
- `apps/web/app/page.tsx`
- 필요 시 타입 파일

내용:

- `NEXT_PUBLIC_VOICE_API_BASE` 추가.
- `SpeechSttResponse`, `VoiceIntent` 타입 추가.
- MediaRecorder 지원/권한/녹음/timeout/upload 상태 추가.
- 마이크 권한 실패 시 기존 카메라/신고/위험 안내는 유지.
- 아직 intent 실행은 최소로 두고, 응답 transcript/intent/confidence를 상태에 보관하는 선에서 PR을 끊을 수 있다.

검증:

- `npm run lint`
- `npm run typecheck`
- voice 서버 미실행 상태에서 앱이 멈추지 않는지 확인.
- voice 서버 실행 상태에서 `/speech/stt` 호출 성공.

### PR 3: PWA voice intent handler 연결

목표:

- STT 결과를 기존 PWA 동작으로 연결한다.

범위:

- `apps/web/app/page.tsx`
- 필요 시 작은 hook/helper

내용:

- `confidence < 0.7`과 `unknown` guard.
- `create_report` -> 기존 `handleReport()`.
- `voice_on`, `voice_off` -> `speechEnabled` 상태.
- `repeat_last` -> `lastSpokenMessageRef`.
- `set_destination` -> `destination` state.
- `start_navigation` -> `navigationActive` state.
- `get_current_location` 채택 시 GPS/정확도/방향 안내.

검증:

- handler 단위로 낮은 confidence, unknown이 destructive action을 실행하지 않는지 확인.
- `create_report`가 기존 버튼과 같은 disabled 조건을 따르는지 확인.

### PR 4: voice intent schema에 `get_current_location` 추가

목표:

- 실제 사람 음성 샘플 `지금 어디야?`를 지원 intent로 편입한다.

범위:

- `voice/intents.py`
- `scripts/test_stt.py` 기대값 또는 테스트 manifest가 있다면 해당 파일
- voice 상태 문서

내용:

- `지금어디야`, `현재위치`, `위치알려줘`, `어디야` 계열을 `get_current_location`으로 매핑.
- score는 다른 짧은 명령과 비슷한 보수적 값으로 둔다.
- PWA PR 3과 intent 이름을 정확히 맞춘다.

검증:

- `samples/voice/stt/myvoice/지금어디야.m4a`가 known-intent 성공으로 바뀌는지 확인.
- 기존 7개 known intent가 깨지지 않는지 확인.

### PR 5: `/detect` adapter 설정 계약 freeze

목표:

- 실제 adapter 구현 전에 모델 담당자와 백엔드가 같은 env/model version/class order/threshold를 보게 한다.

범위:

- `backend/.env.example`
- `backend/app/config.py`
- `docs/backend_environment.md`
- `docs/model_integration_plan.md` 또는 별도 짧은 계약 문서
- 테스트는 설정 파싱 중심

내용:

- `MODEL_VERSION` 또는 adapter 내부 version 산출 방식을 확정한다.
- `DETECT_CONFIDENCE_THRESHOLD`, `DETECT_NMS_IOU_THRESHOLD`를 추가할지 확정한다.
- class order assertion 기준을 문서화한다.
- default threshold는 데모/신고 후보 `0.35`, smoke override는 `0.15`로 둔다.

검증:

- env 미설정 시 기존 `/detect/health` 동작 유지.
- threshold 파싱 오류가 명확한 reason으로 드러나는지 확인.

### PR 6: `/detect` adapter 1차 구현

목표:

- `MODEL_ARTIFACT_PATH`의 모델을 로드하고 `DetectResponse`를 반환한다.

범위:

- `backend/app/detector.py`
- `backend/requirements.txt`
- `backend/tests/test_detect.py`
- 필요 시 adapter helper 파일

전제:

- 지원 artifact 포맷을 PT 또는 ONNX 중 하나로 먼저 고정한다.
- 모델 파일은 Git에 넣지 않고 로컬 env로만 주입한다.

내용:

- lazy load 또는 startup load 경계 구현.
- 이미지 bytes decode, RGB/resize/normalize.
- 모델 출력 bbox를 원본 이미지 기준 normalized bbox로 변환.
- NMS와 threshold 적용.
- class id/name 매핑 및 `ReportMetadata` 검증.
- 빈 탐지 결과는 `detections: []`.

검증:

- 모델 파일 없음: `model_not_configured`.
- 파일 있음 but adapter/load 실패: 명시적 unavailable reason.
- 성공 fixture 또는 monkeypatch adapter로 `DetectResponse` shape 테스트.
- 실제 모델 smoke는 로컬 산출물이 있는 환경에서만 별도 실행.

### PR 7: PWA server detector wiring 준비

목표:

- `NEXT_PUBLIC_DETECTOR_MODE=server`일 때 카메라 프레임을 `/detect`로 보내고, 성공 결과를 기존 `DetectionEvent` 상태로 연결한다.

범위:

- `apps/web/lib/detect-api.ts`
- `apps/web/app/page.tsx`
- 타입 파일

내용:

- `/detect/health` 확인 후 ready일 때만 server mode 실행.
- `model_unavailable`이면 현재처럼 모델 대기 또는 fake fallback 정책을 명확히 표시.
- `/detect` 빈 결과는 위험 없음으로 처리.
- `source: "server"` 결과만 신고 payload로 저장.

검증:

- 백엔드 adapter 없이 `model_unavailable` fallback 확인.
- mock/stub 서버 또는 테스트 double로 성공 response shape 처리 확인.

## 우선순위

1. PR 1: voice 서버 CORS와 STT 오류 계약 정리
2. PR 2: PWA STT client와 MediaRecorder 녹음 UI
3. PR 3: PWA voice intent handler 연결
4. PR 5: `/detect` adapter 설정 계약 freeze
5. PR 4: `get_current_location` intent 추가
6. PR 6: `/detect` adapter 1차 구현
7. PR 7: PWA server detector wiring 준비

실폰 없이 가장 빨리 위험을 줄이는 순서는 voice CORS, PWA 녹음 업로드, intent guard다. `/detect` adapter는 모델 artifact 포맷과 threshold 계약을 먼저 고정한 뒤 구현 PR로 들어가는 편이 안전하다.
