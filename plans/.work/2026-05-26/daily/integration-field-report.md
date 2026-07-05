# Hanium Dreamup / WalkSafe Assist - Integration/Field Test/Report lane note (2026-05-27)

## 최근 진행 근거
- 2026-05-26 `daylog/2026-05-26.md`: 원본 checkout은 `behind 10` + dirty 상태이고, PM feature commit `1320a07`은 원본에 통합/푸시되지 않았다.
- 2026-05-26 `/home/ddobagi/PM/status/2026-05-26-features.md`: 신규 slice는 `/detect/v2` adapter seam, `/reports` v2 metadata filter, PWA Wake Lock, opt-in local TTS fallback.
- 2026-05-26 `/home/ddobagi/PM/status/2026-05-26-verify.md`, PM verify report: Hanium verify `fail`, `push_ready=false`; 원인은 `useWakeLock.ts:71` lint 실패와 Docker/PostGIS 권한 차단.
- 2026-05-26 `product/*`: product 문서 5개 존재, `/home/ddobagi/PM/status/product-audit/2026-05-26.json` 기준 heuristic score 100. 2026-05-26 product-audit markdown/report는 확인되지 않음.
- 2026-05-25 `/home/ddobagi/PM/status/2026-05-25-automation-metrics.md`: 최신 확인 가능한 automation metrics는 `push_ready=0`, `pushed=0`; 2026-05-26 metrics markdown/json은 확인되지 않음.
- 2026-05-25 `daylog/2026-05-25.md`: Android `SM_S931N` + ADB reverse smoke, fake-v2/server-v2 분리, TMAP route/POI live smoke 일부 PASS. TalkBack, 실제 보행 field, 실폰 STT, 장시간 착용 검증은 미완료.
- 2026-05-24/25 `docs/execution/*stage1_detect_v2_image_smoke*`: Stage1 positive 이미지 smoke 근거는 있으나, 5/25 단일 실폰 스크린샷 real ASGI smoke는 `detections_count=0`; field accuracy 근거로 쓰면 안 됨.
- 2026-05-26 `plans/features/2026-05-26_feature_followup_implementation_list.md`: #12 Stage1 detect→report→export trace, #13 Admin GeoJSON export, #16 검수 workflow가 Integration/Ops 다음 후보로 정리됨.
- `plans/daily/2026-05-27.md`는 현재 없음. 프로젝트 루트 `AGENTS.md`도 없어서 사용자 제공 AGENTS 지침을 적용함.

## 내일 목표 후보
- P0. 5/26 PM feature batch 인수 gate: Wake Lock lint 실패와 PostGIS blocked를 분리하고, `1320a07`을 push-ready가 아니라 “검증 실패/대기”로 명확히 기록한다.
- P0. Integration evidence trace v1: Stage1 또는 dry-run payload 기준으로 `/detect/v2 -> /reports/v2 -> admin/status -> CSV/JSON/GeoJSON export -> cleanup`을 같은 report ID/trace ID로 묶는 문서·스크립트 slice.
- P1. Demo/report evidence bundle: mock route guide, server-v2 상태, fake/demo 분리, reviewed-only export, public agency manual submission 흐름을 한 문서에서 재현 가능하게 정리.
- P1. Android field rehearsal checklist 갱신: 5/25 실폰 smoke 근거를 반영하되 TalkBack/실보행/실폰 STT/장시간 착용은 BLOCKED로 분리.
- P1. Admin 검수 workflow 초안: `reviewed` 후보 export, fake/demo 제외, 중복/위치품질/review_flags 포함 기준을 자동 제출이 아닌 수동 제출 준비로 고정.
- P2. Release evidence registry 초안: product done-criteria의 Static/Integration/Headless/Device/Model/Release 등급별 증거 경로와 미검증 항목을 한 표로 정리.

## 상세 체크리스트 초안
- [ ] 5/26 feature batch gate 정리 → 검증: PM commit `1320a07`, verify report, lint 실패 파일/라인, PostGIS skip/block 사유를 표로 기록
- [ ] Wake Lock verify blocker를 PWA lane에 인계하고 Integration 판정 갱신 → 검증: lint 재실행 결과 없으면 `push_ready=false` 유지
- [ ] Stage1/dry-run trace artifact 모드 설계 → 검증: report ID, payload hash, source/model_key/threshold, export URL이 JSON/Markdown에 남는지 확인
- [ ] `--require-server-source` / fake trace 분리 정책 추가 후보화 → 검증: fake payload는 server evidence trace에서 실패하거나 demo-only로 표기
- [ ] reports export evidence bundle 작성 → 검증: CSV/JSON/GeoJSON shape, `demo_filter`, reviewed 후보, 위치 없음 `geometry:null` 기준 확인
- [ ] Android field rehearsal matrix 작성 → 검증: ADB/Chrome/카메라/GPS/heading/TTS/진동/STT/TalkBack/목걸이 각 항목 PASS/BLOCKED 분리
- [ ] demo runbook thin slice 작성 → 검증: 외부 배포/secret 없이 로컬 mock route + Stage1 payload + admin export 흐름 재현 가능
- [ ] public agency manual export 기준 정리 → 검증: 외부 민원 POST 없음, reviewed 이상, 중복/오탐/위치품질 검수 기준 포함
- [ ] evidence registry 초안 갱신 → 검증: fake/mock/headless/model metric/device field/release 근거가 서로 다른 등급으로 기록됨
- [ ] 다음 daylog 입력 요약 준비 → 검증: 실제 실행한 명령만 PASS, 미실행 field/DB/voice 항목은 BLOCKED/PENDING으로 남김

## 리스크/확인 필요
- 원본 checkout dirty + behind 10. safe alternative: reset/강제 정리 없이 PM worktree와 원본 checkout 근거를 분리 기록.
- PM `1320a07` verify 실패. safe alternative: 기능 완료가 아니라 검증 실패 상태로 계획에 반영하고, push/release 근거로 쓰지 않음.
- Docker/PostGIS 권한 없음. safe alternative: serializer fixture, dry-run policy, ASGI/direct handler로 PARTIAL 기록; no-skip DB PASS 금지.
- Android/TalkBack/실폰 STT/실보행/장시간 착용 미확인. safe alternative: rehearsal checklist와 mock/fixture만 작성; Device E2E PASS 금지.
- Stage1 image smoke는 저장 이미지/ASGI 근거. safe alternative: field accuracy, 1초 지연, 실제 보행 안전 근거로 확대하지 않음.
- TMAP live/API/약관. safe alternative: mock route와 짧은 smoke 결과만 기록하고 원본 응답 장기 저장/반복 live 호출 금지.
- 운영 DB, secret, AWS/S3, Cloud STT/TTS, 지자체 API, 외부 배포, 공개 데이터셋은 승인 전 실행 계획에서 제외.

## 병렬 에이전트 활용 메모
- 별도 하위 에이전트는 사용하지 않았다.
- 이유: 이번 작업은 단일 lane note 산출이며 최종 판단을 하나로 통합해야 하고 파일 수정이 없다.
- 대신 product/docs/daylog/PM feature/verify/audit/metrics 확인은 독립 읽기 작업으로 병렬 shell 조회했고, 결론은 이 note에 통합했다.