# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS lane note (2026-05-26)

## 최근 진행 근거
- `product/vision.md`, `product/roadmap.md`, `product/done-criteria.md` (2026-05-18 기준): WalkSafe는 화면보다 TTS/진동/스크린리더/음성 명령을 우선하며, local audio·HTTP·브라우저/실폰 mic·Device E2E 근거를 분리해야 한다.
- `docs/current_status.md` (2026-05-24): Voice는 `create_report`, 상태 반복, 위치 확인, 목적지/길 안내 준비 intent 흐름이 있고, 실폰 마이크 E2E와 TTS 품질/캐시/폴백 검증은 남아 있다.
- `docs/voice_stt_tts_status.md`, `docs/stt_tts_current_status.md` (2026-05-23): 실제 사람 음성 8개 known intent 성공, 평균 1.807초, p95 2.048초, intent regression 44개 통과. 단 브라우저/실폰 mic, HTTP/CORS, TTS 청취는 완료 근거가 아니다.
- `docs/walksafe-v2/voice_command_strategy.md` (2026-05-24): 최신 voice source of truth. `create_report`, `voice_on/off`, `repeat_last`, `get_current_location`, `set_destination`, `start_navigation`을 지원하며 음성 요청 신고는 자동 신고보다 우선한다.
- `docs/execution/2026-05-19_voice_stt_tts.md`: voice py_compile, direct handler/ASGI 계약, CORS preflight, TTS cache direct-call은 PASS. 실제 loopback HTTP와 browser/phone mic는 BLOCKED/PENDING.
- `daylog/2026-05-24.md`: TMAP 보행 길안내 proxy, PWA voice navigation 연결, route timing smoke가 전진했다. 실제 길안내 TTS 품질, TalkBack, 실폰 mic는 미검증이다.
- `/home/ddobagi/PM/status/2026-05-25-features.md`, PM worktree `plans/features/2026-05-25-execution.md`: 5/25 feature batch에서 `VOICE-INTENT-SCHEMA-001`(`/speech/intents` schema endpoint + contract drift check)이 local safe slice로 구현됐다.
- `/home/ddobagi/PM/status/2026-05-25-verify.md`, PM verify report (2026-05-25): Hanium verify는 sandbox mount quota 오류로 `blocked`, `push_ready: False`. 따라서 5/25 voice schema slice는 “검증 대기/통합 전”으로 본다.
- `/home/ddobagi/PM/status/2026-05-25-product-audit.md`: Hanium product 품질 점수 91. v2 canonical summary와 legacy detector/voice 용어 drift 보강 필요.
- 프로젝트 내부 `plans/features`, `plans/verify/ready`, `plans/verify/reports`는 현재 빈 디렉터리이며, 기존 `plans/daily/2026-05-26.md`는 없음.

## 내일 목표 후보
- 1순위: `VOICE-TTS-CATALOG-002` TTS phrase catalog + cache-status dry-run endpoint. 실제 Qwen 생성/다운로드 없이 위험 안내, 음성 신고 결과, 길안내 준비 문구와 cache 상태를 고정한다.
- 2순위: `VOICE-RECORDING-FALLBACK-006` PWA 음성 녹음 오류/fallback 상태 세분화. 권한 거부, timeout, 서버 미연결, 빈 녹음, low confidence를 구분한다.
- 3순위: 5/25 `VOICE-INTENT-SCHEMA-001` 검증/통합 gate. 현재 checkout과 PM feature worktree 차이를 확인하고 schema/classifier drift smoke를 먼저 통과시킨다.
- 4순위: `VOICE-NATURAL-DEST-004` 자연어 목적지 rule. “서울역으로 안내해줘/가자”는 `set_destination`까지만 처리하고 자동 route start는 하지 않는다.
- 5순위: hands-free v2 신고 fixture trace. `신고해` intent가 PWA에서 `/reports/v2 trigger=voice`, `auto_reported=false` 정책으로 이어지는지 fixture 또는 HTTP 가능한 범위에서 확인한다.
- 6순위: `VOICE-SAMPLES-MANIFEST-005` local-only 음성 샘플 manifest schema. 실제 음성 파일은 추가하지 않고 수집/평가 메타데이터 형식만 정한다.

## 상세 체크리스트 초안
- [ ] 5/25 voice schema slice 통합 상태 확인 → 검증: 현재 checkout `voice/intents.py`, `voice/server.py`, `scripts/check_voice_contract.py`와 PM feature worktree 차이를 확인하고 `/speech/intents` 존재 여부 기록.
- [ ] intent schema smoke 실행 → 검증: `py_compile` 및 `intent_schema/classify_intent` direct smoke, 가능 시 `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 5`.
- [ ] TTS phrase catalog 설계/구현 → 검증: 위험 안내, 음성 요청 신고 완료/실패, 위치 확인, 목적지 저장, 길안내 준비 문구가 catalog에 있고 기존 `/speech/tts` 생성 동작은 변경 없음.
- [ ] `/speech/tts/cache-status` dry-run 추가 후보 → 검증: 모델 로드 없이 `cached`, `cache_key`, path suffix만 반환하고 WAV 생성/다운로드 없음.
- [ ] PWA 녹음 fallback 상태 세분화 → 검증: 권한 거부, STT timeout, 서버 미연결, 빈 녹음, unknown/low confidence가 서로 다른 짧은 메시지와 진동 패턴을 가짐.
- [ ] 자연어 목적지 rule 추가 여부 확정 → 검증: “서울역으로 안내해줘”, “서울역으로 가자”는 `set_destination`으로만 분류되고 `create_report`/`start_navigation` 오탐 없음.
- [ ] hands-free 신고 fixture trace → 검증: mock `VoiceSttResponse(intent=create_report)`가 PWA action을 호출하고 v2 신고 정책상 `trigger=voice`, `auto_reported=false`, cooldown 우회로 기록됨.
- [ ] sample manifest example 작성 → 검증: `local_path`, `expected_intent`, `speaker_tag`, `device`, `noise_profile`, `privacy_status` 필드만 정의하고 실제 음성 파일은 추가하지 않음.
- [ ] voice evidence 문서화 → 검증: HTTP/direct/browser/phone/TTS/cache/navigation을 PASS/PARTIAL/BLOCKED로 분리해 `docs/execution/2026-05-26_voice_stt_tts.md` 후보에 남김.

## 리스크/확인 필요
- 5/25 PM feature commit `82ec532`는 verify blocked 상태라 현재 checkout에 완료로 가정하면 안 된다. safe alternative: 파일 diff와 direct smoke로 통합 가능 여부만 확인.
- 현재 checkout은 upstream보다 10커밋 behind이고 dirty/untracked 파일이 많다. safe alternative: voice 관련 파일 단위로만 범위를 좁히고 `git add .`, reset, push는 계획에 넣지 않는다.
- 브라우저/실폰 mic, Android ADB, TalkBack, 휴대폰 스피커 청취는 장비 의존이다. safe alternative는 direct intent handler, ASGI/TestClient, mock `VoiceSttResponse`, desktop mic다.
- Qwen TTS 신규 생성, 대형 모델 다운로드, Cloud STT/TTS, 유료 API, secret 사용은 C 작업이다. safe alternative는 catalog/cache-status dry-run과 기존 cache inventory 확인이다.
- 목적지 자연어 확장은 자동 길안내 시작까지 묶지 않는다. safe alternative는 목적지 slot 저장까지만 처리하고 TMAP/Kakao live 호출은 하지 않는 것이다.
- 원본 음성, 생성 WAV, STT/TTS CSV/log, 모델 cache는 local-only이며 Git 추적 대상으로 추가하지 않는다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이유: 이번 작업은 lane note 작성을 위한 read-only 근거 수집이며, voice 관련 문서·코드·PM 산출물 범위가 좁아 단일 에이전트가 병렬 shell 조회로 통합했다.