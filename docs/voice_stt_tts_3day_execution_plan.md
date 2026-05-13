# STT/TTS 3-Day Execution Plan

작성일: 2026-05-13 KST
실행 기간: 2026-05-14 ~ 2026-05-16 KST
담당 범위: STT/TTS 로컬 음성 서버, PWA 음성 명령 연동 계약, TTS 안내 캐시, 실폰 종단 테스트 기준

## 기준 자료

- 프로젝트 개요서/수행계획서 PDF: 시각장애인 보행 지원 PWA, STT/TTS 기반 양방향 음성 인터페이스, 목적지 설정/경로 탐색/주변 정보 질의, 실시간 위험 안내, 인프라 신고가 핵심 요구사항이다.
- 현재 문서: `docs/voice_stt_tts_status.md`, `docs/local_voice_server_plan.md`, `docs/stt_tts_current_status.md`, `docs/pwa_backend_status.md`, `docs/current_status.md`
- 현재 코드: `voice/`, `scripts/test_stt.py`, `scripts/test_tts.py`, `apps/web/app/page.tsx`
- 제약: 대형 모델 신규 다운로드 금지, 음성 서버 장시간 실행 금지, 원본 음성/생성 WAV/CSV는 로컬 산출물로만 유지

## 현재 상태

### 음성 런타임

- 음성 전용 가상환경 `.venv-voice`가 존재한다.
- 로컬 음성 서버 초안은 `voice/server.py`에 있다.
- 권장 포트는 `9001`이며 이미지/신고 백엔드 포트 `8000`과 분리한다.
- 현재 API 초안:
  - `GET /health`
  - `POST /speech/intent`
  - `POST /speech/stt`
  - `POST /speech/tts`
- 유료/클라우드 STT/TTS API는 사용하지 않는다.
- 현재 판단상 파인튜닝은 하지 않는다.

### STT

- 구현: `voice/stt.py`
- 모델: `faster-whisper medium`
- CUDA 사용 가능 시 `float16`, CPU fallback 시 `int8`
- 언어 기본값: `ko`
- 테스트 스크립트: `scripts/test_stt.py`
- 샘플 위치: `samples/voice/stt/`

실제 사람 음성 테스트 결과:

| 항목 | 값 |
| --- | ---: |
| 총 파일 | 8 |
| 현재 schema상 known intent 파일 | 7 |
| 성공 | 7 |
| 실패 | 0 |
| manual review | 1 |
| known-intent accuracy | 100.00% |
| 평균 지연 | 0.457 sec |
| p95 지연 | 0.572 sec |

`지금어디야.m4a`는 현재 intent schema에 위치 질의 intent가 없어 `needs_manual_review`로 남아 있다. PDF 요구사항의 "주변 정보 질의"와 제품 흐름을 고려하면 3일 계획 안에서 `get_current_location` 지원 여부를 확정한다.

### TTS

- 구현: `voice/tts.py`
- 현재 모델: `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice`
- optional 후보: `Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice`
- 기본 mode: `custom`
- 기본 안내 지시문: "차분하고 명확한 한국어 보행 안전 안내 음성. 너무 빠르지 않게 말하세요."
- 테스트 스크립트: `scripts/test_tts.py`
- 출력 위치: `outputs/voice/tts/`
- 캐시 위치: `outputs/voice/cache/`

검증 결과:

| 항목 | 값 |
| --- | ---: |
| TTS 생성 | 6/6 성공 |
| 평균 생성 시간 | 2.377 sec |
| 최대 생성 시간 | 2.950 sec |
| sample rate | 24000 Hz |
| CUDA memory probe | 약 2153 MiB |

위험 경고는 보행 중 매번 생성하지 않는다. 정적 위험 문구는 `outputs/voice/cache`에 미리 생성한 WAV 또는 브라우저 `speechSynthesis` fallback을 우선한다.

### PWA 음성 사용 지점

현재 PWA는 STT와 연결되어 있지 않다.

- `apps/web/app/page.tsx`의 `speak()`는 브라우저 Web Speech API `speechSynthesis`만 사용한다.
- 위험 감지 시 `RISK_ALERTS` 문구를 한국어 TTS로 말하고, 6초 쿨다운을 둔다.
- 음성 꺼짐 상태에서는 더 강한 진동 fallback을 쓴다.
- 카메라 권한 요청은 `audio: false`라 마이크 녹음 UI가 아직 없다.
- 현재 음성 버튼은 안내 on/off 토글이며, 음성 명령 녹음 버튼이 아니다.

## Intent Schema

3일 동안 PWA와 음성 서버가 공유할 최소 schema는 아래로 고정한다.

| intent | slots | PWA 동작 | 상태 |
| --- | --- | --- | --- |
| `create_report` | `{}` | 현재 탐지 위험 신고 버튼과 같은 흐름 실행 | 현재 지원 |
| `voice_on` | `{}` | `speechEnabled=true`, 짧은 확인 안내 | 현재 지원 |
| `voice_off` | `{}` | `speechEnabled=false`, 진동 확인 | 현재 지원 |
| `repeat_last` | `{}` | 마지막 위험/상태 안내 재생 | 현재 지원, PWA state 추가 필요 |
| `set_destination` | `{ "destination": string }` | 목적지 state 저장, 경로 API 미연결 시 "목적지 저장됨" 안내 | 현재 지원 |
| `start_navigation` | `{}` | 목적지가 있으면 안내 시작 상태, 없으면 목적지 요청 안내 | 현재 지원 |
| `get_current_location` | `{}` | 현재 GPS 좌표/정확도/방향을 짧게 안내 | 2026-05-14 결정/추가 후보 |
| `unknown` | `{}` | 재발화 요청, 위험 상황이면 기존 UI 유지 | 현재 지원 |

`get_current_location`은 실제 사람 음성에 이미 등장했고 수행계획서의 주변 정보 질의 요구와도 맞는다. 단, 2026-05-14에 프론트/백엔드 담당자와 이름을 확정한 뒤 `voice/intents.py`와 PWA handler에 반영한다.

## API Contract for PWA

### `POST /speech/stt`

요청:

```text
multipart/form-data
audio: Blob/File
```

응답:

```json
{
  "transcript": "목적지 서울역으로 설정해",
  "intent": "set_destination",
  "confidence": 0.92,
  "score": 0.92,
  "slots": {"destination": "서울역"},
  "language": "ko",
  "duration_sec": 0.572,
  "model": "medium",
  "segments": []
}
```

PWA 처리 규칙:

- `intent === "unknown"` 또는 `confidence < 0.7`: 명령을 실행하지 않고 "다시 말씀해 주세요" 안내
- STT HTTP 오류 또는 timeout: 브라우저 TTS/진동 fallback 유지, 화면 버튼 조작 가능 상태 유지
- 녹음 길이 목표: 1~4초, 최대 5초
- 업로드 MIME 후보: `audio/webm`, `audio/mp4`, `audio/wav`
- 마이크 권한 실패: 명령 UI는 비활성화하고 기존 터치 버튼/위험 TTS는 유지

### `POST /speech/tts`

요청:

```json
{"text":"전방에 점자블록 파손이 있습니다.", "use_cache": true}
```

응답:

```text
audio/wav
```

응답 헤더:

- `X-Voice-Model`
- `X-Voice-Cached`
- `X-Voice-Mode`
- `X-Voice-Generation-Seconds`

PWA 처리 규칙:

- 위험 경고는 기본적으로 서버 TTS 실시간 생성에 의존하지 않는다.
- 캐시 WAV가 있으면 재생하고, 실패하면 `speechSynthesis`를 사용한다.
- `speechEnabled=false`이면 음성 재생은 하지 않고 강한 진동만 수행한다.
- 동적 문구는 데모 전까지 "목적지를 다시 말씀해 주세요", "신고가 저장되었습니다"처럼 제한한다.

## 2026-05-14 Checklist: Schema and PWA STT Wiring

목표: 현재 로컬 음성 서버 계약을 PWA에서 호출할 수 있는 형태로 고정하고, 마이크 녹음 UI를 최소 동작 수준으로 연결한다.

- [ ] 프론트/백엔드 담당자와 음성 서버 주소 환경변수 이름을 확정한다. 후보: `NEXT_PUBLIC_VOICE_API_BASE=http://127.0.0.1:9001`
- [ ] PWA에서 마이크 권한 요청은 카메라와 분리한다. 기존 `getUserMedia({ audio: false, video })` 흐름은 건드리지 않는다.
- [ ] 음성 명령 버튼/상태를 기존 음성 안내 토글과 구분한다. 예: "명령 듣기", "듣는 중", "처리 중", "다시 말씀해 주세요"
- [ ] `MediaRecorder`로 1~4초 명령을 녹음하고 `audio/webm` Blob을 만든다.
- [ ] Blob을 `POST /speech/stt`의 `audio` multipart field로 전송한다.
- [ ] STT 응답을 PWA intent handler로 라우팅한다.
- [ ] `create_report`는 기존 `handleReport()`와 같은 검증/신고 흐름을 호출한다.
- [ ] `voice_on`, `voice_off`는 기존 `speechEnabled` state를 변경한다.
- [ ] `repeat_last`를 위해 마지막 안내 문구를 `lastSpokenMessageRef` 같은 ref로 보관한다.
- [ ] `set_destination`은 `destination` state에 저장하고 경로 API 미연결 상태에서는 확인 안내만 한다.
- [ ] `start_navigation`은 목적지 없음/있음 상태를 나눠 안내한다.
- [ ] `get_current_location` 추가 여부를 결정한다. 추가한다면 `voice/intents.py`, 문서, PWA handler를 같은 이름으로 맞춘다.
- [ ] `unknown`은 명령 실행 없이 재발화 안내와 짧은 진동으로 끝낸다.
- [ ] STT timeout 기준을 정한다. 권장: 5초 이내 실패 처리.
- [ ] 브라우저가 `MediaRecorder`를 지원하지 않을 때 터치 버튼과 기존 Web Speech API TTS만 남기는 fallback을 구현한다.
- [ ] 로컬 확인은 서버 장시간 실행 없이 짧은 smoke 수준으로만 한다.

완료 기준:

- [ ] PWA에서 녹음한 짧은 명령이 `/speech/stt`로 전송된다.
- [ ] `음성 꺼`, `음성 켜`, `신고해`, `다시 말해줘`가 화면 state 변화 또는 기존 handler 실행으로 이어진다.
- [ ] 실패/unknown 상태에서 위험 알림과 신고 버튼이 막히지 않는다.

## 2026-05-15 Checklist: TTS Cache and Fallback

목표: 보행 중 위험 안내가 서버 TTS 지연에 묶이지 않도록 캐시/브라우저 fallback 정책을 고정한다.

- [ ] 캐시 대상 문구를 PWA `RISK_ALERTS`와 맞춘다.
- [ ] 최소 캐시 문구:
  - [ ] "점자블록 파손. 발밑 주의."
  - [ ] "전방 장애물. 천천히 이동."
  - [ ] "공사 장애물. 우회하세요."
  - [ ] "노면 파임. 발밑 주의."
  - [ ] "신고 저장 완료"
  - [ ] "목적지를 다시 말씀해 주세요."
  - [ ] "다시 말씀해 주세요."
- [ ] 캐시 파일명/키 정책을 `voice/tts.py`의 hash 기반 `_cache_path()`와 충돌 없이 문서화한다.
- [ ] `/speech/tts`는 `use_cache=true`를 기본으로 유지한다.
- [ ] PWA는 캐시 WAV 재생 실패 시 `speechSynthesis`로 즉시 fallback한다.
- [ ] `speechEnabled=false`일 때 서버 TTS 요청을 보내지 않는다.
- [ ] 같은 위험 클래스 반복 안내는 기존 6초 쿨다운을 유지한다.
- [ ] 강한 위험 신호 문구는 캐시 후보를 별도로 둘지, 기존 문구+브라우저 TTS로 처리할지 결정한다.
- [ ] TTS 청취 평가 기준을 만든다: 명료도, 속도, 시작 지연, 휴대폰 스피커 음량, 주변 소음에서 구분 가능 여부.
- [ ] Qwen3-TTS 1.7B는 이 3일 계획에서 필수 비교 대상이 아니다. 신규 다운로드가 필요하면 보류한다.

완료 기준:

- [ ] 위험 경고의 primary path와 fallback path가 명확하다.
- [ ] 캐시 WAV가 없거나 재생 실패해도 사용자는 브라우저 TTS 또는 진동으로 경고를 받는다.
- [ ] TTS 동적 생성은 데모 필수 경로에서 제외된다.

## 2026-05-16 Checklist: Real Phone End-to-End Test

목표: 실폰에서 카메라, GPS/방향, 위험 안내, STT 명령, 신고 흐름을 한 번에 검증한다.

사전 조건:

- [ ] 같은 네트워크 또는 ADB reverse로 PWA가 백엔드 `8000`과 음성 서버 `9001`에 접근 가능하다.
- [ ] 음성 서버는 테스트 시간에만 짧게 실행한다.
- [ ] 새 대형 모델 다운로드가 발생하지 않도록 이미 존재하는 `.venv-voice`와 캐시된 모델만 사용한다.
- [ ] 실외 보행 전에는 실내/정지 상태에서 먼저 권한과 명령 흐름을 확인한다.

권한/기기 체크:

- [ ] 카메라 권한 허용
- [ ] 위치 권한 허용
- [ ] 마이크 권한 허용
- [ ] PWA 설치 또는 모바일 브라우저 접속
- [ ] 화면 잠금/절전 상태에서 테스트하지 않는다.

명령 테스트:

- [ ] "음성 꺼" -> 화면 상태가 음성 꺼짐으로 바뀌고 이후 위험은 강한 진동만 수행
- [ ] "음성 켜" -> 화면 상태가 음성 켜짐으로 바뀌고 확인 안내 재생
- [ ] "다시 말해줘" -> 마지막 안내 반복
- [ ] "신고해" 또는 "현재 위험 신고해" -> 현재 위험이 있으면 기존 신고 흐름 실행
- [ ] "목적지 서울역으로 설정해" -> destination slot이 "서울역"으로 들어오고 확인 안내
- [ ] "길 안내 시작해" -> 목적지 없음/있음 상태별 안내 확인
- [ ] "지금 어디야" -> `get_current_location`을 채택한 경우 GPS/정확도/방향 안내, 미채택 시 unknown fallback

오류 fallback 테스트:

- [ ] 음성 서버 미실행 상태에서 명령 버튼을 눌러도 앱 전체가 멈추지 않는다.
- [ ] STT timeout 시 "다시 말씀해 주세요" 또는 화면 오류 상태 후 기존 터치 UI가 유지된다.
- [ ] 마이크 권한 거부 시 음성 명령만 비활성화되고 카메라/신고/TTS 경고는 유지된다.
- [ ] `unknown` intent는 신고나 목적지 변경 같은 파괴적 동작을 실행하지 않는다.
- [ ] `confidence < 0.7`이면 실행하지 않고 재발화를 요청한다.
- [ ] 음성 꺼짐 상태에서도 위험 탐지는 강한 진동 fallback으로 전달된다.
- [ ] 브라우저 TTS 미지원 시 진동과 화면 상태 문구만으로 최소 동작한다.

측정 기록:

- [ ] 명령별 STT 왕복 시간
- [ ] intent 성공/실패
- [ ] 실패 transcript
- [ ] 휴대폰 마이크/주변 소음 조건
- [ ] TTS 또는 캐시 WAV 시작 지연
- [ ] 신고 성공/실패와 duplicate advisory 여부
- [ ] GPS 정확도와 방향값 유무

완료 기준:

- [ ] 실폰에서 최소 6개 supported intent 중 5개 이상이 한 번의 재시도 이내 성공한다.
- [ ] 위험 안내는 서버 TTS 실패와 무관하게 브라우저 TTS 또는 진동으로 전달된다.
- [ ] 신고 명령은 기존 신고 버튼과 동일한 검증 조건을 따른다.
- [ ] 실폰 테스트 결과를 `docs/voice_stt_tts_status.md` 또는 별도 테스트 기록에 요약할 준비가 끝난다.

## Risk and Decision Log

- `faster-whisper medium`은 현재 실제 사람 음성 known-intent 기준 100%라 모델 변경보다 PWA 녹음 품질 검증이 우선이다.
- PWA 마이크 녹음은 브라우저/OS별 MIME 차이가 있을 수 있다. 서버는 현재 파일 확장자 suffix로 임시 파일을 만들기 때문에 `webm`, `m4a`, `wav`를 우선 확인한다.
- Qwen3-TTS는 한 문장 생성에 평균 2초 이상 걸렸으므로 보행 위험 경고의 실시간 필수 경로로 두지 않는다.
- `get_current_location`은 요구사항에는 맞지만 현재 schema에 없다. 2026-05-14에 채택 여부를 결정하지 못하면 unknown으로 유지하고 데모 범위에서 제외한다.
- 음성 서버가 죽거나 모델 로딩이 실패해도 PWA의 카메라, 위험 경고, 신고 버튼은 독립적으로 계속 동작해야 한다.
- 원본 음성 샘플, 생성 WAV, CSV, 로그는 GitHub에 올리지 않는다.

## Handoff Notes

- 프론트 담당자는 `apps/web/app/page.tsx`의 기존 `speak()`, `speechEnabled`, `handleReport()`를 재사용하되 STT 명령 녹음 state를 별도 추가한다.
- 백엔드 담당자는 기존 신고 API와 음성 서버를 혼합하지 않는다. 음성 서버는 로컬 프로토타입 포트 `9001`로 분리한다.
- 모델 담당자의 YOLO/ONNX 작업과 음성 서버는 독립이다. STT/TTS 계획 때문에 모델 파일이나 데이터셋을 이동하지 않는다.
- 데모 전에는 "음성 명령 실패 시 터치 UI와 진동 fallback으로 복구 가능"을 합격 기준에 포함한다.
