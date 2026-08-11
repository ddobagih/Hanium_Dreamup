# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS lane note (2026-05-25)

## 최근 진행 근거
- `product/vision.md`, `product/done-criteria.md`, `product/roadmap.md` (2026-05-18 기준): 제품 방향은 화면보다 TTS/진동/스크린리더/음성 명령 우선. STT 음성 명령은 local audio PASS이나 브라우저/실폰 mic E2E 미완료.
- `docs/voice_stt_tts_status.md`, `docs/stt_tts_current_status.md` (2026-05-23): 실제 사람 음성 8/8 known intent 성공, 평균 1.807초, p95 2.048초, intent regression 44개 통과. 단 HTTP/CORS, 브라우저/실폰 mic, TTS cache/fallback/청취는 미완료.
- `docs/walksafe-v2/voice_command_strategy.md` (2026-05-24): 최신 voice source of truth. `create_report`, `voice_on/off`, `repeat_last`, `get_current_location`, `set_destination`, `start_navigation` 지원. 음성 요청 신고는 자동 신고보다 우선하고 완료/실패 TTS 필요.
- `docs/walksafe-v2/navigation_integration_policy.md`, `docs/current_status.md`, `daylog/2026-05-24.md` (2026-05-24): TMAP 보행 길안내 proxy와 `useVoiceCommands -> useNavigationGuidance` 연결 확인. 실제 TMAP API smoke는 `TMAP_APP_KEY` 부재로 미실행.
- `daylog/2026-05-22.md`: `useVoiceCommands.ts` 분리, v2 `create_report`가 `server-v2`에서 `/reports/v2 trigger=voice`로 연결됨. 자동 신고는 조용히 저장, 음성 요청 신고만 짧은 TTS.
- `docs/execution/2026-05-19_voice_stt_tts.md`: py_compile, direct handler/ASGI health/intent/CORS/TTS cache 계약 PASS. 실제 loopback HTTP는 sandbox socket 제한으로 BLOCKED, mic E2E는 PENDING.
- `plans/daily/2026-05-24.md`: 5/24 voice 목표는 HTTP/CORS gate, hands-free 신고 trace, mic E2E, TTS fallback/cache 확인이었음.
- `plans/daily/2026-05-25.md`: 확인 가능한 기존 파일 없음.
- `plans/features`, `plans/verify/ready`, `plans/verify/reports`: 디렉터리는 있으나 파일 없음. `PM/` 디렉터리도 없어 product audit, verify status, automation metrics 산출물은 반영할 내용 없음.
- 저장소 루트 `AGENTS.md`: 파일 없음. 사용자 제공 AGENTS 지침 적용.

## 내일 목표 후보
- 1순위: 음성 길안내 feature slice. 실제 TMAP key 없이 `set_destination`/`start_navigation`을 configured destination 또는 mock route 기준으로 검증해, 화면 조작 없이 “목적지 저장 → 길안내 시작 → TTS 안내” 흐름을 전진시킨다.
- 2순위: hands-free v2 신고 trace. `신고해` → `create_report` → PWA intent handler → `/reports/v2 trigger=voice`를 fixture/HTTP 가능한 범위에서 report ID 기준으로 남긴다.
- 3순위: voice HTTP/CORS runtime gate. `/health`, `/speech/intent`, `/speech/stt` validation contract를 실제 HTTP로 시도하고, 막히면 ASGI/direct 근거와 분리한다.
- 4순위: TTS cache/fallback 확인. 신규 다운로드/Cloud API 없이 기존 cache WAV, `X-Voice-Cached`, `speechSynthesis`, `speechEnabled=false`, `repeat_last`, server-down fallback을 확인한다.
- 5순위: intent 표현 확장 판단. `서울역으로 안내해줘` 같은 자연어 목적지 표현을 `set_destination` 또는 `set_destination + start_navigation` 중 어디까지 처리할지 matrix와 regression 후보로 정리한다.
- 6순위: local-only intent telemetry 초안. raw audio 저장 없이 transcript, intent, confidence, failure reason만 개발용 익명 통계로 남기는 test seam을 설계한다.

## 상세 체크리스트 초안
- [ ] voice/web/navigation runtime gate 작성 → 검증: `.venv-voice`, voice `9001`, web `3000`, backend `8000`, `NEXT_PUBLIC_VOICE_API_BASE`, `VOICE_CORS_ORIGINS`, `NEXT_PUBLIC_WALKSAFE_DESTINATION_LAT/LNG/NAME`, `TMAP_APP_KEY` 상태를 PASS/BLOCKED로 기록.
- [ ] 음성 길안내 mock/configured destination slice 확인 → 검증: `목적지 서울역으로 설정해`, `길 안내 시작해`가 목적지 state, `/navigation/walking` mock 또는 missing-key 오류, TTS/voice message로 이어지는지 기록.
- [ ] 자연어 목적지 matrix 작성 → 검증: `목적지 서울역으로 설정해`, `서울역으로 안내해줘`, `서울역으로 가자`, `길 안내 시작해`의 현재 intent/희망 intent/실행 가능 범위를 표로 정리하고 regression 추가 후보 표시.
- [ ] hands-free v2 신고 trace 확인 → 검증: `신고해` intent가 `server-v2` tactile damage 대상에서 `/reports/v2 trigger=voice`, `auto_reported=false`, cooldown 우회 정책을 따르는지 fixture 또는 HTTP trace로 확인.
- [ ] 실제 HTTP contract smoke 시도 → 검증: `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 5` PASS 또는 socket/loopback 차단 사유 기록.
- [ ] PWA CORS 확인 → 검증: 가능 시 브라우저 Network 또는 OPTIONS/POST 응답에서 `/speech/stt` CORS 허용 확인. 불가 시 ASGI CORS 결과를 PARTIAL로 분리.
- [ ] desktop mic E2E 가능 시 실행 → 검증: `신고해`, `음성 켜`, `음성 꺼`, `다시 말해줘`, `지금 어디야`, `길 안내 시작해`의 transcript, intent, confidence, UI action 기록.
- [ ] TTS cache/fallback 확인 → 검증: cache WAV 존재, `/speech/tts` 2회 요청 또는 direct-call의 cached 여부, server-down/timeout, `speechEnabled=false`, `repeat_last` fallback이 앱을 막지 않는지 기록.
- [ ] raw audio 없는 intent telemetry 설계 → 검증: 저장 필드가 transcript/normalized/intent/confidence/failure reason 수준인지 확인하고, 원본 음성·WAV·CSV는 local-only 원칙 유지.
- [ ] Voice evidence 문서화 → 검증: `docs/execution/2026-05-25_voice_stt_tts.md` 후보에 HTTP/direct/browser/phone/TTS/cache/navigation을 PASS/FAIL/BLOCKED/PARTIAL로 분리.

## 리스크/확인 필요
- `product/*` 일부는 Kakao Map API를 MVP 이후로 둔 표현이 남아 있지만, 2026-05-24 v2 canonical 문서는 TMAP proxy 구현을 source of truth로 본다. 내일 계획은 `docs/current_status.md`와 `docs/walksafe-v2/navigation_integration_policy.md` 기준으로 보정한다.
- `TMAP_APP_KEY`, Kakao key, Cloud STT/TTS, 외부 배포, 운영 DB/secret은 C 작업이다. safe alternative는 MockTransport, configured destination, fixture route, local ASGI/direct handler다.
- Android 실폰/ADB/TalkBack/휴대폰 mic/스피커 청취는 장비 의존이다. safe alternative는 desktop mic, MediaRecorder fixture, direct intent handler지만 Device E2E로 쓰면 안 된다.
- loopback/socket 제한이 재발할 수 있다. 실제 HTTP 실패 시 ASGI/direct PASS와 HTTP BLOCKED를 분리한다.
- `서울역으로 안내해줘` 확장은 편리하지만 자동 길안내 시작까지 실행할지 확인 필요다. slot만 저장하는 보수적 대안부터 검증한다.
- 원본 음성 샘플, 생성 WAV, STT/TTS CSV/log는 GitHub에 올리지 않는다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이유: 이번 작업은 최종 lane note 작성을 위한 read-only 문서/코드 근거 수집이며, voice 관련 확인 범위가 `docs/voice*`, `docs/walksafe-v2/*voice*`, `useVoiceCommands`, 최근 daylog로 좁혀져 단일 에이전트가 병렬 shell 조회만으로 통합 가능했다.