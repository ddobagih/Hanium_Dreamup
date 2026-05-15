# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS catch-up lane note (2026-05-15)

## 확인한 근거

- `plans/daily/2026-05-14.md`: 현재 작업 트리에서 확인되지 않음. 대체 근거로 `docs/voice_stt_tts_3day_execution_plan.md`의 2026-05-14 체크리스트와 `plans/.work/2026-05-14/daily/voice-stt-tts.md`를 확인.
- `daylog/`: 비어 있음. `daylog/2026-05-14.md`, `daylog/2026-05-15.md` 없음.
- `docs/execution/2026-05-15*.md`: 없음.
- `docs/execution/2026-05-14_voice_contract_implementation.md`: voice CORS, STT 업로드 검증, `get_current_location` intent 구현 및 경량 검증 통과.
- `docs/execution/2026-05-14_pwa_stt_implementation.md`: PWA `MediaRecorder`, `NEXT_PUBLIC_VOICE_API_BASE`, `/speech/stt` 업로드, intent handler 구현. `npm run lint`, `npm run typecheck` 통과.
- `docs/execution/2026-05-14_next_step_parallel.md`: 실제 voice server 기동 후 `scripts/check_voice_contract.py` 통과. 브라우저 마이크 E2E는 수동 대기.
- `docs/voice_stt_tts_status.md`: 실제 사람 음성 known intent 7/7 성공. 단, `get_current_location` 추가 후 CSV 재생성은 필요.
- 현재 `outputs/voice/cache`에는 `.gitkeep`만 있어 TTS cache WAV 생성 근거 없음.
- 이 작업은 읽기 전용 조사이므로 daylog는 작성하지 않음.

## 완료로 판단한 항목

- voice server CORS 및 `/speech/stt` 오류 계약 정리 완료.
- `/speech/stt` empty/unsupported/too large/RuntimeError 오류 계약 경량 검증 완료.
- `get_current_location` intent schema 추가 완료.
- PWA `NEXT_PUBLIC_VOICE_API_BASE` 및 `/speech/stt` multipart `audio` 업로드 client 구현 완료.
- PWA `MediaRecorder` 기반 음성 명령 UI와 8개 intent handler 구현 완료.
- voice contract smoke 완료: `/health`, `/speech/intent`, `/speech/stt` validation error 통과.
- 기존 STT/TTS 로컬 산출물 확인 완료: 실제 사람 음성 known intent 7/7, 기존 TTS WAV 6/6 생성 결과 존재.

## 미완료 작업 후보

- [ ] 브라우저 마이크 실제 E2E → 이유/근거: 문서에 수동 검증 대기로 남아 있음. 실제 음성 업로드로 transcript/intent/UI state를 확인한 기록 없음.
- [ ] 실폰 voice 접속 방식 검증 → 이유/근거: Android에서 `127.0.0.1:9001`은 PC voice server를 가리키지 않을 수 있어 ADB reverse 또는 LAN IP 확인 필요.
- [ ] `get_current_location` 추가 후 실제 사람 음성 CSV 재생성 → 이유/근거: 기존 CSV는 `지금어디야.m4a`가 manual review/unknown이던 시점 결과.
- [ ] TTS cache WAV 생성 → 이유/근거: `outputs/voice/cache`에 `.gitkeep`만 존재.
- [ ] TTS cache hit 확인 → 이유/근거: `/speech/tts use_cache=true` 2회 요청 및 `X-Voice-Cached: true` 확인 기록 없음.
- [ ] TTS 청취 평가 → 이유/근거: 명료도, 속도, 휴대폰 스피커, 주변 소음 평가 기록 없음.
- [ ] `speechEnabled=false` 및 `repeat_last` 회귀 확인 → 이유/근거: 문서상 확인 필요. 현재 코드상 반복 안내가 음성 꺼짐 상태에서도 말할 가능성이 있어 보임.
- [ ] 위험 안내 fallback 정책 확인 → 이유/근거: voice server 미실행 시 PWA가 브라우저 `speechSynthesis`/진동으로 충분히 동작하는지 실행 기록 없음.

## 오늘 catch-up 후보 스케줄

- [ ] voice server 실행과 계약 smoke 재확인 → 검증: `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001` 통과.
- [ ] PWA dev server와 voice base 연결 확인 → 검증: 브라우저에서 CORS 오류 없이 `NEXT_PUBLIC_VOICE_API_BASE`의 `/speech/stt` 호출 가능.
- [ ] 브라우저 마이크 E2E 확인 → 검증: `음성 꺼`, `음성 켜`, `다시 말해줘`, `신고해`, `목적지 서울역으로 설정해`, `길 안내 시작해`, `지금 어디야`의 transcript/intent/confidence/UI state 기록.
- [ ] 실제 사람 음성 CSV 재생성 → 검증: `지금어디야.m4a`가 `get_current_location`으로 분류되고 평균/p95 지연 기록 갱신.
- [ ] TTS cache 대상 문구 확정 → 검증: PWA `RISK_ALERTS` 4개와 `신고 저장 완료`, `목적지를 다시 말씀해 주세요.`, `다시 말씀해 주세요.` 목록 확정.
- [ ] `/speech/tts` cache WAV 생성 → 검증: 첫 요청 생성, 두 번째 요청 `X-Voice-Cached: true`, `outputs/voice/cache/*.wav` 존재.
- [ ] 음성 꺼짐 회귀 확인 → 검증: `speechEnabled=false` 후 위험 경고와 `repeat_last`가 의도치 않게 음성을 재생하지 않음.
- [ ] TTS 청취 평가 작성 → 검증: 문구별 명료도, 속도, 시작 지연, 휴대폰 스피커 적합성 기록.

## 확인 필요

- 요청된 `plans/daily/2026-05-14.md`가 없어서 원 계획표 체크박스 기준 완료 대조는 불완전함.
- `docs/execution/2026-05-14_summary.md`는 STT/PWA 미구현이라고 적지만, 이후 `voice_contract_implementation`, `pwa_stt_implementation`, `next_step_parallel`에서 구현/검증 기록이 생김. 최신 근거는 후자 기준으로 봐야 함.
- 2026-05-15 새벽 작업 daylog 또는 실행 문서가 없어 5월 15일 추가 완료분은 확인되지 않음.
- PWA STT timeout 기준이 문서 권장 5초와 코드 업로드 timeout 6000ms로 약간 다름. 의도된 분리인지 확인 필요.
- 실폰 테스트 시 `9001` voice server 접근 방식과 CORS origin을 먼저 고정해야 함.

## 병렬 에이전트 활용 메모

하위/병렬 에이전트는 사용하지 않음. 범위가 문서/로그 대조 중심이라 단일 에이전트가 계획표 대체 근거, 실행 문서, 현재 산출물 상태를 함께 확인함.