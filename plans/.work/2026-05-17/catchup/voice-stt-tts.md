# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS catch-up lane note (2026-05-17)

## 확인한 근거

- `plans/daily/2026-05-16.md`: Voice STT/TTS 항목은 모두 `[ ]` 상태.
- `plans/.work/2026-05-16/execute-r1/voice-stt-tts.md`: STT dry-run, 실제 음성 샘플 재평가, `repeat_last` 수정 근거 확인.
- `daylog/2026-05-16.md`: Voice STT 일부 완료, 브라우저/실폰 마이크 E2E와 TTS 7문구 cache 미완료로 기록.
- `plans/catchup/2026-05-16-audit-final.md`: “Voice STT 일부 완료”, “TTS 7개 문구 cache 완료 근거 없음”으로 감사 결론 확인.
- `docs/execution/2026-05-16_integration_field_report.md`: PWA voice UI 코드는 있으나 Android/브라우저 수동 검증은 미완료로 분리.
- `outputs/voice/stt_myvoice_results_2026-05-16.csv`: 실제 음성 8개 success, `지금어디야.m4a -> get_current_location`.
- `daylog/2026-05-17.md`, `docs/execution/*2026-05-17*`: 파일 없음.

## 완료로 판단한 항목

- STT intent schema dry-run: 8개 intent PASS, `지금 어디야 -> get_current_location` 포함.
- 실제 사람 음성 샘플 재평가: 8/8 success, 평균 `2.219s`, p95 `2.481s`.
- `speechEnabled=false` 상태의 `repeat_last` 음성 재생 방지 코드 수정: lint/typecheck 및 정적 검증 PASS.
- Voice contract baseline: 5/15 HTTP 서버 기준 `/health`, `/speech/intent`, `/speech/stt` 오류 계약 PASS. 5/16에는 직접 함수 호출 기준 확인만 있음.
- TTS cache는 단일 문구 `안전하게 이동하세요.` 1개 cache hit 근거만 있음. 7개 대상 문구 완료로 보지 않음.

## 미완료 작업 후보

- [ ] PWA dev server와 voice base 연결 → 이유/근거: 브라우저 Network에서 `NEXT_PUBLIC_VOICE_API_BASE` 기반 `/speech/stt` 호출 및 CORS 확인 근거 없음.
- [ ] 데스크톱 브라우저 마이크 E2E → 이유/근거: 실제 마이크 발화 transcript/intent/confidence/UI action 기록 없음.
- [ ] 실폰 voice 접속 및 핵심 명령 확인 → 이유/근거: Android web `3000`, voice `9001`, 마이크 권한, 실폰 UI 변화 기록 없음.
- [ ] TTS 7개 위험/운영 문구 cache hit → 이유/근거: batch 생성이 반환되지 않았고 `X-Voice-Cached: true` 근거는 단일 기존 문구뿐.
- [ ] TTS 청취 평가 → 이유/근거: 휴대폰 스피커 기준 명료도, 속도, 시작 지연 평가 없음.
- [ ] `speechEnabled=false`/`repeat_last` 런타임 회귀 → 이유/근거: 코드 수정은 있으나 브라우저/실폰 동작 검증 없음.
- [ ] voice server down fallback → 이유/근거: 서버 미실행 시 PWA 명령 UI, 브라우저 TTS/진동 유지 확인 없음.
- [ ] 음성 신고와 버튼 신고 비교 → 이유/근거: `create_report` intent가 실제 녹음 UI에서 같은 신고 흐름을 타는지 검증 없음.
- [ ] Voice 결과 문서 작성 → 이유/근거: `docs/execution/2026-05-16_voice_stt_tts.md` 없음.

## 오늘 catch-up 후보 스케줄

- [ ] voice 서버 기동/계약 재확인 → 검증: `GET /health`, `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001` PASS 기록.
- [ ] PWA voice base/CORS smoke → 검증: web `3000`, voice `9001` 기동 후 브라우저 Network에서 `/speech/stt` 요청과 CORS 오류 없음 기록.
- [ ] 데스크톱 마이크 STT E2E → 검증: 7개 supported intent별 transcript, intent, confidence, UI action 기록.
- [ ] Android 실폰 voice E2E → 검증: 접속 방식, 기기/브라우저 버전, 마이크 권한, `음성 꺼/켜`, `신고해`, `지금 어디야` UI 변화 기록.
- [ ] TTS 7문구 cache 생성/hit → 검증: 각 문구 2회 요청, 두 번째 `X-Voice-Cached: true`, `outputs/voice/cache/*.wav` 존재 기록.
- [ ] fallback/voice-off 회귀 → 검증: voice server down, `speechEnabled=false`, `repeat_last`에서 음성/진동/상태 문구 동작 기록.
- [ ] Voice 실행 문서 작성 → 검증: `docs/execution/2026-05-17_voice_stt_tts.md`에 통과/실패/대기/확인 필요 분리.

## 확인 필요

- `서울역으로 안내해줘`는 현재 `unknown` 근거가 있음. `set_destination` 확장 여부는 제품 판단 필요.
- 5/16 HTTP localhost smoke는 sandbox 제약으로 제한됨. 최신 실제 서버 검증은 5/17에 다시 남기는 편이 안전함.
- `get_current_location` intent의 PWA UI 기대 동작을 명확히 해야 함.
- TTS 대상 문구는 실행 시점의 PWA `RISK_ALERTS`와 운영 문구에서 다시 고정해야 함.
- 2026-05-17 daylog/execution 문서가 없어 새벽 작업 완료 근거는 확인되지 않음.

## 병렬 에이전트 활용 메모

- 이번 확인에는 신규 하위/병렬 에이전트를 사용하지 않음.
- 기존 r1 Voice 실행 note와 audit 문서의 결론을 통합함.
- 읽기 전용 조사 및 lane note 출력 요청이므로 daylog는 새로 작성하지 않음.