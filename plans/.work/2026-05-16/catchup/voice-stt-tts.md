# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS catch-up lane note (2026-05-16)

## 확인한 근거

- `plans/daily/2026-05-15.md`: Voice STT/TTS 항목은 모두 `[ ]` 상태.
- `daylog/2026-05-15.md`: voice contract PASS, TTS cache 초기 실패 후 `sox` 설치 뒤 cache hit 확인 기록.
- `docs/execution/2026-05-15_voice_validation.md`: voice server `/health`, 7개 intent, `/speech/stt` validation error 계약 PASS. 브라우저/실폰 마이크 E2E와 `/speech/tts`는 미실행.
- `docs/execution/2026-05-15_voice_cache_followup.md`: `/speech/tts` 첫 시도 HTTP 500, cache WAV 0개.
- `docs/execution/2026-05-15_runtime_followup_after_reset.md`: `sox`, `libsox-fmt-all` 설치 후 `/speech/tts` 1차 `x-voice-cached:false`, 2차 `x-voice-cached:true`, cache WAV 생성.
- `docs/execution/2026-05-15_parallel_validation_summary.md`: 실제 STT 음성 전사, 브라우저/실폰 마이크 E2E, TTS cache hit가 당시 미실행으로 분류됨.
- `daylog/2026-05-16.md`, `docs/execution/2026-05-16*.md`: 존재하지 않음.
- 읽기 전용 문서 조사만 수행했으므로 daylog는 새로 작성하지 않음.

## 완료로 판단한 항목

- voice server 실행과 계약 smoke: 완료. `scripts/check_voice_contract.py` 기준 `/health`, supported intent, `/speech/stt` 오류 계약 PASS.
- STT/TTS 처리 방식 확인: 완료. 외부 API가 아니라 로컬 `faster-whisper medium`, 로컬 voice server, 로컬 Qwen TTS cache 기준으로 기록됨.
- `/speech/tts` cache WAV 생성 smoke: 부분 완료. `안전하게 이동하세요.` 단일 문구에서 cache WAV `outputs/voice/cache/qwen3_tts_cd250a528361467f9b26.wav` 생성 및 2차 cache hit 확인.

## 미완료 작업 후보

- [ ] PWA dev server와 voice base 연결 확인 → 이유/근거: 5/15 문서에서 `NEXT_PUBLIC_VOICE_API_BASE` 브라우저 연결, CORS 확인 근거 없음.
- [ ] 브라우저 마이크 E2E 확인 → 이유/근거: voice validation/cache 문서가 브라우저/실폰 마이크 검증 미수행을 명시.
- [ ] 실폰 voice 접속 방식 확인 → 이유/근거: Android 기기 미연결, ADB reverse/LAN IP 중 하나를 고정한 기록 없음.
- [ ] 실제 사람 음성 CSV 재생성 → 이유/근거: `samples/voice/stt/myvoice/지금어디야.m4a` 재평가 및 평균/p95 CSV 갱신 근거 없음.
- [ ] TTS cache 대상 문구 확정 → 이유/근거: PWA `RISK_ALERTS` 4개 위험 문구와 신고/목적지/재발화 문구 목록 확정 기록 없음.
- [ ] 위험 안내 fallback 확인 → 이유/근거: voice server 미실행 상태의 `speechSynthesis`/진동 fallback 검증 근거 없음.
- [ ] `speechEnabled=false` 회귀 확인 → 이유/근거: 음성 꺼짐 후 위험 경고와 `repeat_last` 무음/진동 동작 검증 근거 없음.
- [ ] TTS 청취 평가 → 이유/근거: 문구별 명료도, 속도, 시작 지연, 휴대폰 스피커 음량 평가표 없음.
- [ ] 신고 버튼과 음성 신고 비교 → 이유/근거: PWA server report E2E는 있으나 `create_report` intent를 브라우저 녹음으로 실행해 같은 신고 흐름을 탔다는 근거 없음.

## 오늘 catch-up 후보 스케줄

- [ ] PWA voice base 연결 smoke → 검증: voice `9001`, web `3000` 기동 후 브라우저 Network에서 `/speech/stt` 요청과 CORS 오류 없음 기록.
- [ ] 데스크톱 브라우저 마이크 STT E2E → 검증: 7개 supported intent 발화별 transcript, intent, score/confidence, UI action 기록.
- [ ] 실폰 접속 방식 고정 → 검증: ADB reverse 권장 또는 HTTPS/LAN IP 중 하나로 Android에서 web/voice 접근, 마이크 권한 프롬프트, `/health` 접근 성공/실패 기록.
- [ ] 실제 음성 샘플 CSV 재생성 → 검증: `지금어디야.m4a`가 `get_current_location`으로 분류되는지와 평균/p95 지연을 최신 CSV에 반영.
- [ ] TTS 문구 목록 확정 및 batch cache 생성 → 검증: 대상 문구별 1차 생성, 2차 `X-Voice-Cached: true`, `outputs/voice/cache/*.wav` 존재 기록.
- [ ] fallback/voice-off 회귀 확인 → 검증: voice server down, `speechEnabled=false`, `repeat_last` 케이스에서 음성/진동/상태 문구가 의도대로 동작하는지 기록.
- [ ] TTS 청취 평가 → 검증: 휴대폰 스피커 기준 명료도, 속도, 시작 지연, 주변 소음 구분 가능 여부 표 작성.

## 확인 필요

- `서울역으로 안내해줘`는 5/15 voice validation에서 `unknown`으로 기록됨. 자연어 목적지 표현을 `set_destination`으로 확장할지 결정 필요.
- TTS cache hit은 단일 smoke 문구만 완료로 볼 수 있음. 실제 위험 안내 문구 전체 cache/fallback 정책 완료로 보기는 어려움.
- 실폰 테스트는 “이전에 수행” 메모가 있으나 2026-05-15 계획 항목의 완료 근거로 쓸 수 있는 재검증 기록은 없음.
- 2026-05-16 새벽 작업 문서가 없어 5/16 반영분은 확인 불가.

## 병렬 에이전트 활용 메모

- 이번 lane note 작성에는 하위/병렬 에이전트를 새로 사용하지 않음.
- 참고 문서상 2026-05-15 검증 자체는 PWA/backend/voice/model 병렬 lane으로 수행됐고, 그중 voice 결론은 contract PASS, 마이크 E2E 미실행, TTS cache는 후속 runtime 문서에서 단일 문구 hit 확인으로 갱신됨.