# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS lane note (2026-05-17)

## 최근 진행 근거
- `docs/voice_stt_tts_status.md` (작성 기준일 2026-05-14): 로컬 voice server API는 `/health`, `/speech/intent`, `/speech/stt`, `/speech/tts`; STT는 `faster-whisper medium`, TTS는 `Qwen3-TTS 0.6B CustomVoice`. 실제 사람 음성 known intent 7/7 성공, 평균 0.457s, p95 0.572s 기록.
- `docs/execution/2026-05-14_voice_contract_implementation.md` (2026-05-14): `get_current_location` intent, CORS, `/speech/stt` 업로드 검증, 오류 계약이 구현됨. py_compile, intent 회귀, STT 오류 계약 검증 통과.
- `docs/execution/2026-05-14_pwa_stt_implementation.md` (2026-05-14): PWA `MediaRecorder` 녹음, `NEXT_PUBLIC_VOICE_API_BASE`, `/speech/stt` multipart 업로드, 8개 intent handler가 구현됨. `npm run lint`, `npm run typecheck` 통과 기록.
- `docs/execution/2026-05-15_voice_validation.md` (2026-05-15): 임시 voice server에서 `scripts/check_voice_contract.py` PASS. `/health`, 7개 intent, `/speech/stt` empty/unsupported 오류 계약 확인. 브라우저/실폰 마이크 E2E와 `/speech/tts`는 미실행.
- `docs/execution/2026-05-15_runtime_followup_after_reset.md` (2026-05-15): `sox`, `libsox-fmt-all` 설치 후 TTS 단일 문구 `안전하게 이동하세요.` 1차 생성, 2차 `x-voice-cached:true` 확인. 실제 위험/운영 7개 문구 cache 완료 근거는 아님.
- `plans/.work/2026-05-16/execute-r1/voice-stt-tts.md` 및 `daylog/2026-05-16.md` (2026-05-16): `scripts/test_stt.py --dry-run-intents` 8개 PASS, 실제 사람 음성 샘플 8개 재평가 PASS, `지금어디야.m4a -> get_current_location` 확인. 평균 2.219s, p95 2.481s 기록.
- `apps/web/app/page.tsx`, `apps/web/lib/voice-api.ts` (확인 2026-05-16): PWA는 5초 녹음, 6초 STT timeout, confidence threshold 0.7, `unknown`/low confidence 재발화, `create_report`는 `handleReport()` 호출 구조.
- `outputs/voice/cache` (확인 2026-05-16): cache WAV는 단일 문구 `qwen3_tts_cd250a528361467f9b26.wav`만 확인됨.
- `plans/daily/2026-05-17.md` (확인 2026-05-16): 기존 파일 없음.

## 내일 목표 후보
- 브라우저/실폰 마이크 E2E를 최우선으로 확인한다: 실제 녹음 → `/speech/stt` → intent → PWA UI action.
- Android 접속 방식을 고정한다: ADB reverse, LAN IP, HTTPS 중 하나를 선택하고 `NEXT_PUBLIC_VOICE_API_BASE`와 `VOICE_CORS_ORIGINS`를 맞춘다.
- TTS 위험/운영 7개 문구 cache를 생성하고 2회 요청 cache hit를 확인한다.
- voice-off, `repeat_last`, voice server down, unknown/low confidence fallback 회귀를 확인한다.
- `서울역으로 안내해줘` 같은 자연어 목적지 표현을 `set_destination`으로 확장할지 제품 결정을 받는다.

## 상세 체크리스트 초안
- [ ] voice server health 확인 → 검증: `GET /health`가 `status=ok`, `server=voice`, `stt_model=medium`, TTS cache dir를 반환
- [ ] voice 계약 smoke 재실행 → 검증: `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001` PASS
- [ ] PWA와 voice base 연결 확인 → 검증: `NEXT_PUBLIC_VOICE_API_BASE` 기준 브라우저 Network에서 `/speech/stt` 요청과 CORS 오류 없음 기록
- [ ] 데스크톱 브라우저 마이크 E2E → 검증: `음성 꺼`, `음성 켜`, `다시 말해줘`, `신고해`, `목적지 서울역으로 설정해`, `길 안내 시작해`, `지금 어디야`의 transcript/intent/confidence/UI action 기록
- [ ] 실폰 voice 접속 방식 확정 → 검증: Android Chrome에서 web `3000`과 voice `9001` 접근, 마이크 권한 프롬프트, 실제 origin/CORS 설정 기록
- [ ] 실폰 핵심 명령 확인 → 검증: `음성 꺼/켜`, `신고해`, `지금 어디야`가 실제 UI 상태 변화 또는 신고 flow로 이어지는지 기록
- [ ] TTS cache 대상 문구 확정 → 검증: PWA `RISK_ALERTS` 4개와 `신고 저장 완료`, `목적지를 다시 말씀해 주세요.`, `다시 말씀해 주세요.` 목록 고정
- [ ] TTS 7개 문구 cache 생성 및 hit 확인 → 검증: 같은 문구를 `use_cache=true`로 2회 요청해 두 번째 응답 `X-Voice-Cached: true`, `outputs/voice/cache/*.wav` 존재 확인
- [ ] TTS 청취 평가 → 검증: 휴대폰 스피커 기준 명료도, 속도, 시작 지연, 주변 소음 구분 가능 여부 기록
- [ ] `speechEnabled=false` 회귀 확인 → 검증: 위험 안내와 `repeat_last`가 음성을 재생하지 않고 필요한 경우 진동/상태 문구만 남는지 확인
- [ ] voice server down fallback 확인 → 검증: voice server 미실행 상태에서 명령 UI 오류가 앱 전체를 막지 않고, 위험 경고는 브라우저 TTS 또는 진동으로 유지됨
- [ ] 목적지 자연어 표현 결정 반영 → 검증: 채택 시 `서울역으로 안내해줘`가 `set_destination`으로 분류되고 PWA destination state에 반영됨; 미채택 시 unknown fallback 문서화

## 리스크/확인 필요
- 브라우저/실폰에서 실제 마이크 오디오를 `/speech/stt`로 보낸 E2E 완료 근거가 아직 없다.
- Android에서 `127.0.0.1:9001`은 PC voice server가 아닐 수 있어 ADB reverse/LAN/HTTPS 접속 방식 확정이 필요하다.
- TTS cache는 단일 문구 smoke만 완료됐고, 위험 안내 4개와 운영 문구 3개 cache는 미완료다.
- Qwen3-TTS 동적 생성은 기존 평균 2초 이상이라 보행 중 위험 안내 primary path로 두면 안 된다.
- `서울역으로 안내해줘`는 2026-05-15 기록상 `unknown`이다. 목적지 표현 확장 여부는 제품 판단 필요.
- 원본 음성, 생성 WAV, CSV, 로그는 로컬 산출물이므로 GitHub 업로드 대상이 아니다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이번 작업은 voice 관련 문서, 최신 daylog, 현재 코드/산출물 확인으로 범위가 좁고 최종 산출물이 단일 lane note라 병렬 분리 이득이 작았다.