# Hanium Dreamup / WalkSafe Assist - Integration/Field Test/Report lane note (2026-05-25)

## 최근 진행 근거
- 2026-05-18 `product/vision.md`, `product/roadmap.md`, `product/decisions.md`: 제품 방향은 목걸이형 스마트폰 PWA, TTS/진동/음성 우선, 운영 모드는 `feature-growth`. 외부 연동은 demo/mock 우선.
- 2026-05-23 `docs/current_status.md`, `docs/walksafe-v2/*`: 최신 v2 기준은 `/detect/v2`, `/reports/v2`, `/reports/export`, `fake-v2`/`server-v2`, tactile 3-class + COCO general 분리.
- 2026-05-23 `docs/execution/2026-05-23_reviewed_yolo26s_final_selection.md`: reviewed YOLO26s Stage1 `best.pt`가 MVP/backend integration 후보. threshold 0.50 기준 image-level F1 `0.922151`; field 성능 근거는 아님.
- 2026-05-23 `daylog/2026-05-23.md`: `/detect/v2` real YOLO ASGI smoke는 adapter fix 후 성공. `damaged_tactile_block`, `tactile_damage_area` 탐지 확인. 단 plumbing smoke로만 기록.
- 2026-05-23 `daylog/2026-05-23.md`: `/reports` v2 metadata filter, `/reports/export`, admin export/model health UI, voice intent regression 포함 `110 tests` PASS 기록.
- 2026-05-24 `daylog/2026-05-24.md`, `docs/walksafe-v2/navigation_integration_policy.md`: TMAP 보행 길안내 proxy와 voice/navigation 연결 확인. 실제 TMAP API smoke는 `TMAP_APP_KEY` 근거 없어 미실행.
- 2026-05-19 `docs/execution/2026-05-19_integration_field_report.md`: Android 목걸이 field, PostGIS HTTP trace, voice browser/phone E2E, `/admin` runtime 운영 흐름은 여전히 BLOCKED/PENDING.
- `plans/features`, `plans/verify/ready`, `plans/verify/reports`: 디렉터리는 있으나 파일 없음. `PM` 디렉터리 없음. `plans/daily/2026-05-25.md`도 없음.

## 내일 목표 후보
- 1순위: Stage1 `server-v2` 후보를 기준으로 `detect -> reports/v2 -> admin -> export` report ID evidence trace를 만든다.
- 2순위: 공공기관 직접 제출이 아니라 `/reports/export` 기반 수동 제출 준비 kit를 정리한다.
- 3순위: TMAP 길안내는 mock/health/단위 테스트 근거로 데모 흐름에 연결하고, 실제 key/API 호출은 승인 전 보류한다.
- 4순위: Android 목걸이 smoke를 가능하면 실행하되, `fake-v2`와 `server-v2` 근거를 분리한다.
- 5순위: voice `신고해 -> create_report -> /reports/v2 trigger=voice` 통합 trace를 실제 mic 또는 fixture 등급으로 분리한다.

## 상세 체크리스트 초안
- [ ] runtime/worktree/scheduler gate 작성 → 검증: `git status`, upstream behind, 미추적 산출물, `plans/features`, `plans/verify`, `PM` 부재를 기록
- [ ] `server-v2` evidence trace template 작성 → 검증: `/detect/v2`, `/reports/v2`, `/reports/{id}`, `/admin`, status patch, `/reports/export`, cleanup 필드가 report ID 기준으로 연결됨
- [ ] Stage1 후보 real detect smoke 가능 시 실행 → 검증: `DETECT_V2_MODE=yolo`, health ready, `model_key`, `source_model`, `threshold_used`, bbox 응답 기록
- [ ] reports/admin runtime trace 가능 시 실행 → 검증: GPS 포함 `damaged_tactile_block` 저장, `new -> reviewed -> resolved`, CSV/JSON export, row/upload cleanup
- [ ] DB/HTTP가 막히면 safe alternative 수행 → 검증: ASGI/fixture trace로 schema와 metadata만 확인하고 Integration/Field PASS로 쓰지 않음
- [ ] public agency export kit 작성 → 검증: `reviewed` 후보, 중복/오탐/위치품질/review flags 포함, 외부 민원 자동 POST 없음
- [ ] TMAP 길안내 demo trace 정리 → 검증: `backend/tests/test_navigation_routes.py`, mock route, `start_navigation` intent 연결; 실제 API key 없으면 BLOCKED
- [ ] Android 목걸이 smoke 가능 시 실행 → 검증: 기기명, ADB reverse, 권한, 카메라 각도, GPS/heading, TTS/진동, source 분리 기록
- [ ] voice 신고 통합 확인 → 검증: mic E2E 가능 시 transcript/intent/confidence/report ID, 불가 시 `tests/test_voice_intents.py`와 fixture action만 PARTIAL
- [ ] 2026-05-25 integration execution/daylog 근거 남김 → 검증: 실제 실행한 검증만 PASS, 미실행은 BLOCKED/PENDING으로 표기

## 리스크/확인 필요
- `TMAP_APP_KEY`와 외부 API 호출은 secret/외부 의존성이므로 자동 실행 계획에 넣지 않는다. 대안은 MockTransport/단위 테스트/health-only.
- Android 실폰, TalkBack, 목걸이 착용 검증은 아직 없다. headless나 ASGI 결과를 field 근거로 대체하지 않는다.
- PostGIS/HTTP runtime이 막히면 report 저장·상태변경·cleanup PASS로 쓰지 않는다. 대안은 disposable DB 가능 세션 또는 fixture trace.
- `product/*`에는 Kakao/API 후순위 표현이 남아 있고, 최신 v2 문서는 TMAP 기본 provider를 말한다. source of truth 충돌을 실행 문서에 명시해야 한다.
- 브랜치는 upstream보다 9커밋 뒤처져 있고 미추적 산출물이 많다. `git add .`, 대형 산출물 staging, 임의 reset 금지.
- Stage1 모델 수치는 model eval 근거이며 실폰 latency/field safety 근거가 아니다.

## 병렬 에이전트 활용 메모
- 병렬 에이전트 2개를 사용했다.
- Agent 1: `product/*.md`, `README.md`, `docs/walksafe-v2/*`에서 제품 목표, source of truth, safe alternative 후보를 수집했다.
- Agent 2: 최근 daylog, 2026-05-24 계획, execution 문서, scheduler/PM 산출물 부재를 확인했다.
- 통합 결론: 2026-05-25는 기존 BLOCKED 항목 반복보다 Stage1 `server-v2`, `/reports/export`, TMAP mock 길안내를 묶은 데모/보고 evidence trace를 우선한다.