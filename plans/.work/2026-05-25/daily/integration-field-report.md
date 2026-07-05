# Hanium Dreamup / WalkSafe Assist - Integration/Field Test/Report lane note (2026-05-26)

## 최근 진행 근거
- 2026-05-25 `product/vision.md`, `product/roadmap.md`, `product/backlog.md`, `product/done-criteria.md`, `product/decisions.md`: 운영 모드는 `feature-growth`, 핵심은 착용형 PWA에서 TTS/진동/신고/export/field 근거를 분리하는 것.
- 2026-05-24 `docs/current_status.md`, `docs/walksafe-v2/*`: v2 기준은 `/detect/v2`, `/reports/v2`, `/reports/export`, `/navigation/walking`; TMAP이 기본 provider, Kakao는 권한 확보 후 fallback 후보.
- 2026-05-24 `docs/execution/2026-05-24_stage1_detect_v2_image_smoke.md`: Stage1 YOLO26s real `/detect/v2` ASGI smoke에서 damage-positive 5/5장 `damaged_tactile_block` 감지. 실폰 field 근거는 아님.
- 2026-05-24 `daylog/2026-05-24.md`: detect → report → status → export trace가 fake payload와 Stage1 payload 양쪽에서 PASS로 기록됨. CSV/JSON/GeoJSON export와 admin export URL 회귀도 확인됨.
- 2026-05-25 `daylog/2026-05-25.md`: clean worktree `0ee4be0`은 원격 최신과 일치하나 원본 checkout은 `behind 10`, dirty 상태. PM feature commit `82ec532`은 별도 worktree에 있고 verify blocked.
- 2026-05-25 `/home/ddobagi/PM/status/2026-05-25-features.md`: 신규 slice 완료 후보는 GeoJSON export/admin URL, mock navigation timing fixture, motion ROI fixture/helper, voice intent schema endpoint.
- 2026-05-25 `/home/ddobagi/PM/status/2026-05-25-verify.md`: Hanium verify status `blocked`, `push_ready: False`, attempts 3. 원인: bubblewrap mount quota 오류.
- 2026-05-25 `/home/ddobagi/PM/reports/product-audit/2026-05-25/hanium-dreamup.md`: product 점수 91/100. 보강 포인트는 v2 canonical summary와 legacy detector 용어 drift 정리.
- 2026-05-24 `/home/ddobagi/PM/status/2026-05-24-automation-metrics.md`: feature local commit 2개, verify fail 2개, push_ready 0개. 2026-05-25 automation metrics 파일은 확인되지 않음.
- 현재 프로젝트 경로 기준 `plans/daily/2026-05-26.md`, `plans/features`, `plans/verify`는 확인되지 않음. 프로젝트 루트 `AGENTS.md`도 없음.

## 내일 목표 후보
- P0. worktree/source-of-truth gate 확정: 원본 dirty checkout, clean worktree `0ee4be0`, PM feature worktree `82ec532` 중 내일 검증·계획 기준을 먼저 분리한다.
- P0. PM feature batch verify 복구: quota 해소 후 GeoJSON export, navigation fixture, motion ROI, voice intent schema를 같은 worktree에서 재검증한다.
- P0. Stage1 detect → reports/v2 → admin/status → CSV/JSON/GeoJSON export evidence trace를 report ID 기준으로 재생성하거나 최신 PASS 근거를 검증 대장에 묶는다.
- P1. 실폰 없이 가능한 demo runbook thin slice: mock route guide + Stage1 damage payload + admin export + public agency manual export 흐름을 한 번에 설명 가능한 스크립트/체크리스트로 정리한다.
- P1. Android 목걸이 착용 controlled smoke: ADB reverse 가능한 기기가 있으면 카메라 각도, GPS/heading, TTS/진동, `server-v2` source 분리를 기록한다.
- P1. voice 신고 통합 evidence: `/speech/intents`와 `신고해` intent가 `trigger=voice`, `auto_reported=false` 신고 흐름으로 이어지는지 fixture 또는 HTTP로 확인한다.
- P2. product/report 문구 보강: “fake-v2는 데모 전용”, “server-v2 장애 시 fake 자동 fallback 금지”, “Stage1 smoke는 field accuracy 아님”을 demo/report evidence 기준에 반영한다.

## 상세 체크리스트 초안
- [ ] source-of-truth gate 작성 → 검증: `git status`, `rev-list`, PM worktree commit/marker 경로, current checkout의 `plans/features` 부재를 PASS/BLOCKED 표로 기록
- [ ] PM feature verify 재시도 조건 확인 → 검증: quota 오류 해소 여부와 marker `plans/verify/ready/2026-05-25-hanium-dreamup-feature-batch.json` 읽기 가능 여부 확인
- [ ] GeoJSON/admin export 회귀 → 검증: `FeatureCollection`, `geometry: null`, 필터/format/query 보존, CSV 기존 URL 회귀 확인
- [ ] Stage1 report trace 재생성 → 검증: `damaged_tactile_block` 1건이 `/reports/v2` 저장, `new -> reviewed -> resolved`, CSV/JSON/GeoJSON export, cleanup까지 report ID로 연결
- [ ] mock navigation demo trace 작성 → 검증: `prepare10`, `soon3`, `now` cue와 위험 TTS 우선순위 억제가 fixture에서 재현됨
- [ ] motion ROI fixture를 field 근거와 분리 → 검증: 3초/5초 horizon, heading/speed/bbox center 판정은 Static/fixture로만 표기하고 TTS/진동 활성화 없음 확인
- [ ] voice intent/report trace 확인 → 검증: `/speech/intents` schema와 `/speech/intent` classifier drift 없음, 가능하면 `신고해` → report metadata `trigger=voice`
- [ ] Android field gate 실행 가능성 확인 → 검증: 기기명, Chrome, ADB reverse, 권한, 목걸이 착용 방식 기록. 없으면 BLOCKED와 fixture 대안 기록
- [ ] public agency export kit 초안 → 검증: reviewed 후보, 중복/오탐/위치품질/review_flags 포함. 외부 민원 자동 POST 없음 명시
- [ ] evidence registry/daylog 입력 요약 준비 → 검증: 실제 실행한 검증만 PASS, PM verify blocked와 미검증 field 항목은 별도 표기

## 리스크/확인 필요
- 원본 checkout은 dirty이고 원격보다 10커밋 뒤처짐. safe alternative: 읽기 전용 diff/상태 기록 후, 검증은 clean/PM worktree 기준으로 분리한다.
- PM feature commit `82ec532`은 verify blocked라 push-ready가 아님. safe alternative: 기능은 “검증 대기”로만 반영하고 완료/배포 근거로 쓰지 않는다.
- Docker/PostGIS/local TCP가 없으면 reports runtime PASS 불가. safe alternative: ASGI/direct handler, serializer fixture, disposable DB 가능 세션 대기.
- Android/ADB/TalkBack/실폰 mic가 없으면 Device E2E PASS 불가. safe alternative: mock route, fixture, 정적 접근성 점검으로만 표기.
- TMAP key와 약관은 live smoke와 장기 저장을 분리해야 함. safe alternative: mock route fixture, TMAP 원본 응답 장기 저장 금지.
- 운영 DB, secret, AWS/S3, Cloud STT/TTS, 지자체 API, 외부 배포, 공개 데이터셋은 승인 전 실행 계획에서 제외한다.

## 병렬 에이전트 활용 메모
- 별도 하위 에이전트는 사용하지 않았다.
- 이유: 이번 작업은 단일 lane note 작성이며 파일 수정이 없고, 최종 판단을 하나로 통합해야 해서 하위 에이전트 분할 이득이 작았다.
- 대신 product/daylog/docs/PM 산출물 확인은 독립 읽기 작업으로 병렬 조회했고, 결론은 이 note에 통합했다.