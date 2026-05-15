# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS lane note (2026-05-15)

## 최근 진행 근거
- `docs/voice_stt_tts_status.md` (2026-05-14): faster-whisper medium 기준 실제 사람 음성 known intent 7/7 성공, 평균 0.457s, p95 0.572s. TTS는 Qwen3 0.6B CustomVoice 6/6 생성 성공, 평균 2.377s.
- `docs/execution/2026-05-14_voice_contract_implementation.md` (2026-05-14): `voice/server.py` CORS, `/speech/stt` 업로드 검증, `get_current_location` intent 추가. py_compile, intent 회귀, 오류 계약 테스트 통과 기록.
- `docs/execution/2026-05-14_pwa_stt_implementation.md` (2026-05-14): PWA `MediaRecorder` 녹음, `NEXT_PUBLIC_VOICE_API_BASE`, `/speech/stt` multipart 업로드, 8개 intent handler 구현. `npm run lint`, `npm run typecheck` 통과 기록.
- `docs/execution/2026-05-14_voice_pwa_smoke.md` (2026-05-14): `scripts/check_voice_contract.py`로 `/health`, `/speech/intent`, `/speech/stt` 오류 계약을 실제 STT 추론 없이 확인하는 절차 정리. 브라우저 마이크 E2E는 수동 대기.
- `docs/voice_stt_tts_3day_execution_plan.md` (2026-05-13): 2026-05-15 목표는 TTS cache/fallback 고정, TTS 청취 평가, PWA 녹음 연결 검증.
- 현재 소스 확인 (2026-05-14): `voice/intents.py`, `voice/server.py`, `apps/web/lib/voice-api.ts`, `apps/web/app/page.tsx`에 `get_current_location`, CORS/업로드 검증, PWA 녹음 UI와 intent handler가 반영되어 있음.
- 현재 산출물 확인 (2026-05-14): `outputs/voice/cache`에는 `.gitkeep`만 있고 캐시 WAV는 아직 없음. 기존 생성 WAV는 `outputs/voice/tts`에 있음.
- 프로젝트 경로에 별도 `AGENTS.md`, `daylog` 파일, `plans/daily/2026-05-15.md`는 확인되지 않음. 최근 기록은 `docs/execution` 기준으로 판단.

## 내일 목표 후보
1. PWA 음성 명령 실제 E2E 검증: 로컬 voice server와 PWA dev server를 함께 띄우고 브라우저 마이크로 supported intent를 확인한다.
2. TTS cache/fallback 경로 고정: PWA `RISK_ALERTS`와 동일한 짧은 위험 문구를 캐시 대상으로 확정하고, 서버 TTS 지연이 위험 안내 primary path가 되지 않게 한다.
3. `get_current_location` 반영 후 실제 사람 음성 CSV 재생성: `지금어디야.m4a`가 `get_current_location`으로 잡히는지 최신 결과를 남긴다.
4. TTS 청취 평가: 캐시 후보 WAV를 사람이 들어보고 명료도, 속도, 시작 지연, 휴대폰 스피커 적합성을 기록한다.
5. 실폰/ADB 또는 LAN 접근 조건 정리: Android에서 `127.0.0.1:9001`을 직접 쓸 수 없으므로 ADB reverse, LAN IP, CORS origin 중 테스트 방식을 정한다.

## 상세 체크리스트 초안
- [ ] voice server 실행 후 `scripts/check_voice_contract.py` 실행 → 검증: `/health`, 7개 `/speech/intent`, `/speech/stt` validation error 계약 통과
- [ ] PWA dev server에서 마이크 명령 E2E 확인 → 검증: `음성 꺼`, `음성 켜`, `다시 말해줘`, `신고해`, `목적지 서울역으로 설정해`, `길 안내 시작해`, `지금 어디야` 각각 transcript/intent/confidence와 화면 상태 기록
- [ ] `samples/voice/stt/myvoice` 재평가 → 검증: `지금어디야.m4a`가 `get_current_location`으로 분류되고, 최신 CSV에 평균/p95 지연 기록
- [ ] TTS cache 대상 문구를 `apps/web/app/page.tsx`의 `RISK_ALERTS`와 맞춤 → 검증: 최소 4개 위험 문구와 `신고 저장 완료`, `목적지를 다시 말씀해 주세요`, `다시 말씀해 주세요` 목록 확정
- [ ] `/speech/tts` `use_cache=true`로 캐시 WAV 생성 → 검증: 첫 요청은 생성, 두 번째 요청은 `X-Voice-Cached: true`, `outputs/voice/cache/*.wav` 존재 확인
- [ ] 위험 안내 fallback 확인 → 검증: voice server 미실행 상태에서도 PWA 위험 경고가 브라우저 `speechSynthesis` 또는 진동으로 전달됨
- [ ] `speechEnabled=false` 회귀 확인 → 검증: 음성 꺼짐 후 위험 경고와 `repeat_last`가 의도치 않게 음성을 재생하지 않는지 확인
- [ ] TTS 청취 평가표 작성 → 검증: 문구별 명료도, 속도, 시작 지연, 주변 소음 구분 가능 여부 기록

## 리스크/확인 필요
- 브라우저 마이크로 `/speech/stt`를 실제 호출한 E2E는 아직 실행 기록이 없다.
- `outputs/voice/cache`가 비어 있어 2026-05-15 TTS cache 목표는 아직 미완료 상태다.
- 기존 TTS 생성 WAV 문구와 PWA `RISK_ALERTS` 짧은 문구가 일부 다르다.
- Qwen3-TTS는 평균 2초 이상 걸려 보행 중 위험 경고 실시간 생성 경로로 두면 안 된다.
- 실폰에서는 `NEXT_PUBLIC_VOICE_API_BASE=http://127.0.0.1:9001`이 휴대폰 자신을 가리키므로 ADB reverse 또는 LAN IP 설정이 필요하다.
- 코드상 `repeat_last`는 음성 꺼짐 상태에서도 저장된 메시지를 말할 가능성이 있어 확인 또는 수정이 필요하다.
- 원본 음성, 생성 WAV, CSV, 로그는 로컬 산출물이므로 GitHub 업로드 대상이 아니다.