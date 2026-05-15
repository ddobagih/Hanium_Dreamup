# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS lane note (2026-05-16)

## 최근 진행 근거
- `docs/voice_stt_tts_status.md` (2026-05-14): faster-whisper medium 실제 사람 음성 known intent 7/7 성공, 평균 0.457s, p95 0.572s. Qwen3-TTS 0.6B CustomVoice는 6/6 생성 성공, 평균 2.377s.
- `docs/execution/2026-05-14_voice_contract_implementation.md` (2026-05-14): `voice/server.py` CORS, `/speech/stt` 업로드 검증, `get_current_location` intent 추가. py_compile, intent 회귀, 오류 계약 테스트 통과 기록.
- `docs/execution/2026-05-14_pwa_stt_implementation.md` (2026-05-14): PWA `MediaRecorder`, `NEXT_PUBLIC_VOICE_API_BASE`, `/speech/stt` multipart 업로드, 8개 intent handler 구현. `npm run lint`, `npm run typecheck` 통과 기록.
- `docs/execution/2026-05-14_next_step_parallel.md` (2026-05-14): voice server 기동 후 `scripts/check_voice_contract.py` 통과. 브라우저 마이크 E2E는 수동 검증 대기.
- `plans/daily/2026-05-15.md`, `plans/catchup/2026-05-15.md` (2026-05-15): 5/15 핵심 미완료 후보는 브라우저/실폰 마이크 E2E, TTS cache hit, 실폰 접속 방식, `get_current_location` 반영 후 CSV 재생성.
- `daylog/2026-05-15.md` (2026-05-15): 2026-05-15 execution 문서 없음, 브라우저/실폰 검증과 TTS cache hit는 새로 실행하지 않았다고 기록.
- 현재 소스 확인 (2026-05-15): `voice/intents.py`, `voice/server.py`, `apps/web/lib/voice-api.ts`, `apps/web/app/page.tsx`에 voice 계약과 PWA 녹음 UI가 반영되어 있음. `outputs/voice/cache`에는 `.gitkeep`만 있어 캐시 WAV 근거는 없음.
- `plans/daily/2026-05-16.md`는 현재 저장소에서 확인되지 않음.

## 내일 목표 후보
1. 브라우저 마이크 E2E를 우선 확인한다: 녹음 → `/speech/stt` → intent → PWA 상태 변화.
2. 실폰 접속 방식을 고정한다: ADB reverse 또는 LAN IP와 `VOICE_CORS_ORIGINS` 설정.
3. `get_current_location` 추가 후 실제 사람 음성 CSV를 재생성한다.
4. TTS cache WAV 생성과 cache hit를 확인하고, 위험 안내 primary path는 브라우저 TTS/진동 유지로 고정한다.
5. `speechEnabled=false`, `repeat_last`, unknown/low confidence fallback 회귀를 확인한다.

## 상세 체크리스트 초안
- [ ] voice server 실행 환경 확인 → 검증: `GET /health`가 `status=ok`, `server=voice`, `stt_model=medium` 반환
- [ ] voice 계약 smoke 재실행 → 검증: `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001` 통과
- [ ] PWA dev server와 voice base 연결 → 검증: `NEXT_PUBLIC_VOICE_API_BASE` 기준 `/speech/stt` 호출 시 CORS 오류 없음
- [ ] 데스크톱 브라우저 마이크 E2E → 검증: `음성 꺼`, `음성 켜`, `다시 말해줘`, `신고해`, `목적지 서울역으로 설정해`, `길 안내 시작해`, `지금 어디야`의 transcript/intent/confidence/UI 상태 기록
- [ ] 실폰 voice 접속 방식 확정 → 검증: Android Chrome에서 web `3000`과 voice `9001` 접근, 마이크 권한 프롬프트, CORS origin 기록
- [ ] 실폰 마이크 핵심 명령 확인 → 검증: `음성 꺼/켜`, `신고해`, `지금 어디야`가 실제 UI 상태 변화로 이어짐
- [ ] 실제 사람 음성 CSV 재생성 → 검증: `samples/voice/stt/myvoice/지금어디야.m4a`가 `get_current_location`으로 분류되고 평균/p95 지연 갱신
- [ ] TTS cache 대상 문구 확정 → 검증: PWA `RISK_ALERTS` 4개 문구와 `신고 저장 완료`, `목적지를 다시 말씀해 주세요.`, `다시 말씀해 주세요.` 목록 고정
- [ ] `/speech/tts` cache hit 확인 → 검증: 같은 문구를 `use_cache=true`로 2회 요청해 두 번째 응답 `X-Voice-Cached: true`, `outputs/voice/cache/*.wav` 존재 확인
- [ ] TTS 청취 평가 → 검증: 문구별 명료도, 속도, 시작 지연, 휴대폰 스피커 적합성 기록
- [ ] 음성 꺼짐/반복 안내 회귀 확인 → 검증: `speechEnabled=false` 후 위험 안내와 `repeat_last`가 의도치 않게 음성을 재생하지 않음
- [ ] 음성 서버 장애 fallback 확인 → 검증: voice server 미실행 상태에서도 PWA 위험 경고는 브라우저 `speechSynthesis` 또는 진동으로 유지됨

## 리스크/확인 필요
- 브라우저/실폰에서 실제 오디오를 `/speech/stt`로 보낸 E2E 실행 기록이 아직 없다.
- Android에서 `127.0.0.1:9001`은 PC voice server가 아닐 수 있으므로 ADB reverse 또는 LAN IP가 필요하다.
- LAN IP 사용 시 `VOICE_CORS_ORIGINS`에 실제 PWA origin을 추가해야 할 수 있다.
- `outputs/voice/cache`가 비어 있어 TTS cache 완료로 볼 수 없다.
- Qwen3-TTS 동적 생성은 평균 2초 이상이라 보행 중 위험 안내 primary path로 두면 안 된다.
- 기존 실제 음성 CSV는 `get_current_location` 추가 전 결과라 `지금어디야.m4a`가 아직 `unknown`으로 남아 있다.
- 코드상 `repeat_last`가 음성 꺼짐 상태에서도 저장 메시지를 말할 가능성이 있어 회귀 확인 또는 수정이 필요하다.
- PWA 녹음 최대 길이는 5초, 업로드 timeout은 6초로 구현되어 있어 문서상 5초 실패 처리 기준과 의도 차이를 확인해야 한다.
- 원본 음성, 생성 WAV, CSV, 로그는 로컬 산출물이므로 GitHub 업로드 대상이 아니다.

## 병렬 에이전트 활용 메모
- 사용하지 않음. 이번 범위는 voice 관련 문서, 코드, 산출물 상태 대조로 충분히 좁고 최종 산출물이 단일 lane note라 병렬 분리 이득이 작았다.
- daylog는 작성하지 않음. 파일 변경 없이 계획 근거를 조사해 merge 에이전트용 lane note 본문만 작성하는 작업이기 때문이다.