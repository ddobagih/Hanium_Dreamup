# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS lane note (2026-05-27)

## 최근 진행 근거
- `product/vision.md`, `product/roadmap.md`, `product/done-criteria.md` (2026-05-18): WalkSafe는 화면보다 TTS/진동/스크린리더/음성 명령을 우선하며, local audio/HTTP/browser mic/phone mic/Device E2E 근거를 분리해야 한다.
- `docs/voice_stt_tts_status.md`, `docs/stt_tts_current_status.md` (2026-05-25): `/speech/intent`, `/speech/stt` 응답에 `action`, `should_execute`, `reason`, `prompt`, `confidence`가 포함됨. unknown/저신뢰/목적지 slot 누락은 재질문 정책. 기록상 voice intent/TTS policy regression 50개 통과.
- `docs/walksafe-v2/voice_command_strategy.md` (2026-05-24): 최신 voice 전략은 STT → 정규화 → intent/slot → 실행/재질문이며, 음성 요청 신고는 자동 신고보다 우선하고 완료/실패 TTS가 필요하다.
- `daylog/2026-05-25.md`: TTS fallback/listening 충돌 완화, 위험 안내 우선순위 helper, TMAP live smoke, `scripts/check_voice_tts_http_cache_20260525.py` 추가 기록. 실폰 mic와 스피커 청취는 미검증.
- `daylog/2026-05-26.md`: PM feature scheduler commit `1320a07`에서 opt-in local TTS fallback, Wake Lock, detect adapter seam, reports filter가 구현됐으나 verify는 `npm run lint` 실패로 `push_ready: False`.
- `/home/ddobagi/PM/reports/verify/2026-05-26/hanium-dreamup/2026-05-26-hanium-dreamup-feature-batch-report.md`: verify 상태 `fail`; `npm run typecheck`와 `git diff --check`는 통과, lint는 `useWakeLock.ts:71`에서 실패, PostGIS no-skip은 Docker 권한으로 차단.
- `plans/features/2026-05-26_feature_followup_implementation_list.md`: voice 관련 후속 후보로 TTS phrase catalog, `/speech/tts/cache-status`, no-op NLU fallback, raw audio 없는 telemetry, sample manifest schema가 정리됨.
- 현재 checkout 조사 기준: `voice/intents.py`, `voice/server.py`, `voice/phrases.py`, `tests/test_voice_tts.py`에 `/speech/intents`, `/speech/phrases`, `/speech/tts/cache-status`, phrase catalog 흔적이 있음. 단 dirty tree 기준이라 검증 완료로 보지 않는다.
- 기존 `plans/daily/2026-05-27.md`, 프로젝트 내부 `plans/verify`는 확인되지 않았다. PM verify/status 산출물은 `/home/ddobagi/PM/...` 기준으로 확인했다.

## 내일 목표 후보
- 1순위: voice source-of-truth/검증 gate 정리. PM `1320a07`, 현재 dirty checkout, 원격 기준을 분리하고 voice intent/TTS regression drift를 먼저 닫는다.
- 2순위: PWA 녹음 fallback 상태 세분화. 권한 거부, STT timeout, 서버 미연결, 빈 녹음, unknown/low confidence를 서로 다른 UI/TTS/진동 상태로 만든다.
- 3순위: hands-free v2 신고 fixture trace. `create_report` intent가 `trigger=voice`, `auto_reported=false`, 자동 신고 cooldown 우회 정책으로 이어지는지 확인한다.
- 4순위: TTS phrase catalog/cache-status 인수 검증. 모델 로드·WAV 생성 없이 catalog completeness와 cache dry-run을 고정한다.
- 5순위: raw audio 없는 voice telemetry/sample manifest schema. transcript, normalized, intent, confidence, reason, outcome 중심으로 설계하고 실제 음성 파일은 추가하지 않는다.
- 6순위: 환경 가능 시 HTTP/CORS/browser mic smoke. 불가하면 direct handler, TestClient, mock `VoiceSttResponse`로 PARTIAL 근거만 남긴다.

## 상세 체크리스트 초안
- [ ] voice 기준 worktree 분리 → 검증: 원본 checkout, PM feature worktree `1320a07`, 현재 dirty voice 파일 차이를 표로 기록.
- [ ] intent/TTS regression gate → 검증: `py_compile` 후 가능하면 `tests/test_voice_intents.py tests/test_voice_tts.py -q`; 불가 시 direct classifier smoke.
- [ ] `재탐색` intent 기대값 drift 확인 → 검증: `start_navigation`/`reroute_navigation` 테스트 기대값이 정책과 일치하는지 정리.
- [ ] PWA 녹음 fallback 세분화 → 검증: permission denied, abort timeout, server down, empty audio, `should_execute=false` fixture별 메시지/진동 확인.
- [ ] hands-free 신고 fixture → 검증: mock `VoiceSttResponse(intent=create_report)`가 PWA `onCreateReport`를 호출하고 v2 신고 payload 정책과 맞는지 확인.
- [ ] TTS phrase catalog/cache-status 인수 → 검증: `/speech/phrases`, `/speech/tts/cache-status`가 모델 로드 없이 phrase id, cached, cache key, suffix를 반환.
- [ ] opt-in local TTS fallback 경로 확인 → 검증: local TTS 성공/timeout/non-audio 실패에서 browser `speechSynthesis` fallback이 유지되는지 mock test.
- [ ] sample manifest schema 초안 → 검증: `local_path`, `expected_intent`, `speaker_tag`, `device`, `noise_profile`, `privacy_status` 필드만 정의하고 원본 audio 추가 없음.
- [ ] voice evidence 문서화 → 검증: direct/HTTP/browser mic/phone mic/TTS cache/navigation intent를 PASS/PARTIAL/BLOCKED로 분리.

## 리스크/확인 필요
- PM 2026-05-26 feature batch는 verify fail 상태다. lint 실패가 voice 자체는 아니지만 PWA 전체 검증을 막으므로 push-ready 근거로 쓰면 안 된다.
- 현재 checkout은 dirty/untracked가 많다. voice 계획은 파일 단위로 좁히고 `git add .`, reset, push는 포함하지 않는다.
- `tests/test_voice_intents.py`에 `재탐색` 기대값 중복/충돌 가능성이 보인다. 실행 전 단정하지 말고 첫 gate에서 확인한다.
- 브라우저/실폰 mic, Android ADB, TalkBack, 휴대폰 스피커 청취는 장비 의존이다. safe alternative는 direct handler, TestClient, mock MediaRecorder/VoiceSttResponse다.
- Qwen TTS 신규 생성, 대형 모델 다운로드, Cloud STT/TTS, 유료 API, secret 사용은 C 작업이다. safe alternative는 phrase catalog, cache-status dry-run, 기존 cache/fallback WAV 확인이다.
- PostGIS가 필요한 실제 신고 저장 trace는 DB 환경에 막힐 수 있다. safe alternative는 PWA action fixture와 backend schema/report policy dry-run이다.
- 원본 음성, 생성 WAV, STT/TTS CSV/log는 local-only다. manifest/schema만 Git 대상 후보로 둔다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이유: 이번 작업은 lane note 작성을 위한 read-only 조사이고 최종 파일을 직접 수정하지 않는다. 문서·코드·PM 산출물은 병렬 shell 조회로만 나눠 확인하고 단일 판단으로 통합했다.