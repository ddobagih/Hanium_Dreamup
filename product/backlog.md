# Feature Backlog

## 작성 규칙

- 기능은 사용자 가치가 보이는 단위로 적는다.
- 바로 구현할 때는 0.5~1일 안에 끝낼 수 있는 slice로 쪼갠다.
- 기능마다 완료 기준과 검증 방법을 반드시 둔다.
- 근거 없는 기능은 `아이디어/확인 필요`로 둔다.
- PDF 원안 기능은 출처가 있어도 현재 구현 범위를 넘으면 P1/P2/C 또는 확인 필요로 둔다.

## P0 - 핵심 기능

| ID | 기능 | 사용자 가치 | 첫 slice | 완료 기준 | 검증 | 상태 | 근거 |
|---|---|---|---|---|---|---|---|
| P0-001 | 공통 runtime gate 기록 | 막힌 환경을 반복 시도하지 않고 다음 실행 가능 범위를 명확히 함 | `git status`, env, 포트, Docker/PostGIS, backend/web/voice, Android 접속 방식을 PASS/BLOCKED 표로 기록 | gate 결과가 문서화되고 blocked 항목이 B/C/중단으로 분류됨 | Static/문서 확인 | planned | `plans/daily/2026-05-19.md`, `plans/catchup/2026-05-18-audit-final.md` |
| P0-002 | Android 착용형 fake/server smoke | 실제 사용 전제인 화면 미주시 TTS/진동·카메라 각도·신고 흐름 확인 | 사원증처럼 휴대폰을 목에 걸고 ADB reverse로 접속한 뒤 기기/Chrome/권한/착용 방식을 기록 | fake와 server 결과를 분리해 카메라, GPS/heading, TTS/진동, 신고 ID, `/admin` 확인 기록 | E2E/실기기 | blocked_env | `docs/neck_worn_phone_test_checklist.md`, PDF 2개, 사용자 확인 2026-05-18, `docs/current_status.md` |
| P0-003 | PostGIS reports no-skip + HTTP smoke | 신고 저장/조회/중복/상태 변경이 제품의 핵심 데이터 흐름임 | 2026-05-15 PASS 근거와 별도로 disposable DB에서 최신 `backend/tests/test_reports.py -q -rs` no-skip 재실행 | reports tests skip 없이 PASS, HTTP smoke, duplicate/radius, upload 동작 기록 | Integration | blocked_env | `daylog/2026-05-15.md`, `plans/catchup/2026-05-18-audit-final.md`, `docs/api_reference.md` |
| P0-004 | server detector `source=server` 실폰/통제 입력 재확인 | fake가 아닌 실제 `.pt` 경로로 보행자 신고 흐름을 검증 | `server(.pt)` 우선 모드에서 `/detect/health ready` 후 known-positive 또는 통제 입력으로 PWA 신고 저장 | bbox 표시, `source=server`, `metadata.source=server`, 신고 저장과 `/admin` 조회 확인 | E2E/headless+실기기 분리 | blocked_env | `docs/pwa_backend_status.md`, `docs/model_integration_plan.md`, `docs/execution/2026-05-18_integration_field_report.md` |
| P0-005 | 브라우저/실폰 마이크 STT E2E | 화면 조작 없이 신고·반복·음성 on/off를 실행하는 핵심 접근성 가치 | voice server HTTP health/CORS 확인 후 데스크톱 또는 실폰 마이크 4개 핵심 명령 실행 | transcript, intent, confidence, UI action, 실패/권한 상태 기록 | E2E/실기기 | blocked_env | `docs/voice_stt_tts_status.md`, PDF 2개, `plans/catchup/2026-05-18-audit-final.md` |
| P0-006 | v3 model-data 작은 curation slice | v2 점자블록 baseline의 FP/미탐 후보를 줄여 안전 안내 품질 개선 | 디스크 gate 후 이미지 저장 off로 streaming failure sampler 50~100장 smoke | CSV row count, bucket, privacy_status, split_policy 기록; 대량 이미지 저장 없음 | Static/model eval smoke | planned | `docs/execution/2026-05-18_model_data_mlops.md`, `docs/model_training_status.md` |

## P1 - 중요하지만 P0 이후

| ID | 기능 | 사용자 가치 | 첫 slice | 완료 기준 | 검증 | 상태 | 근거 |
|---|---|---|---|---|---|---|---|
| P1-001 | IMU 3~5초 ROI/궤적 예측 PoC | 보이는 모든 객체가 아니라 실제 충돌 가능성이 높은 위험만 안내해 인지 과부하를 줄임 | Android DeviceMotion/Orientation 로그 저장 + fixture 기반 ROI 계산 문서화 | ROI 내/외 객체 필터링 결과와 한계가 기록됨 | Device E2E/Static | blocked_env | PDF 2개 |
| P1-002 | TTS HTTP cache/fallback/청취 평가 | 위험 안내가 실제 브라우저/휴대폰 스피커에서 들을 만한지 확인 | 7문구를 HTTP로 2회 요청하고 server-down/voice-off/repeat fallback 확인 | 두 번째 `X-Voice-Cached: true`, WAV 존재, fallback 상태, 청취 평가 기록 | Integration/E2E | blocked_env | `docs/voice_stt_tts_status.md`, `plans/catchup/2026-05-18-audit-final.md` |
| P1-003 | PWA 설치/offline/TalkBack 수동 점검 | 저신호·스크린리더 환경에서 기본 shell과 위험/신고 상태 접근성 확보 | ADB reverse 접속 기준 Android Chrome에서 설치, offline shell, TalkBack 읽기 순서 확인 | standalone/offline fallback/TalkBack 결과가 PASS/FAIL/BLOCKED로 기록됨 | E2E/실기기 | blocked_env | PDF 2개, `docs/current_status.md`, `docs/figma_make_accessibility_review.md` |
| P1-004 | GIS/GeoJSON/히트맵 thin slice | 누적 신고를 운영자가 취약 지점으로 이해할 수 있게 함 | disposable DB 신고 좌표를 GeoJSON으로 export하고 단순 heatmap/mock 기준 정의 | GeoJSON sample, radius/cluster 또는 mock heatmap 기준 기록 | Integration/Static | blocked_env | `2026년 한이음 드림업 프로젝트 수행계획서.pdf` p.4/p.6 |
| P1-005 | class `1..3` 한국 GT 확보 계획 실행 준비 | 점자블록 외 킥보드/공사물/포트홀까지 실제 서비스 범위 확장 | class별 positive/negative 후보와 제외 기준을 데이터 manifest로 세분화 | 수집 후보, privacy/location audit, split policy가 문서화됨 | Static/문서+데이터 검토 | planned | `docs/korean_data_strategy.md`, `data_sources/manifests/korean_dataset_candidates.md` |
| P1-006 | fake 신고 데이터 운영 분리 정책 강화 | 데모 데이터가 모델 성능·실제 안전 근거로 섞이지 않게 함 | `/admin` 필터/문구/보고 기준에서 `fake_source` 분리 확인 | fake 신고는 API/UI 검증용으로만 표시되고 성능 집계 제외 | Static/GUI/API smoke | todo | `docs/report_operations.md`, `docs/model_placeholder_systems.md` |
| P1-007 | 최종 보고/시연 evidence 구조 | 10월 산출물 요구에 맞춰 테스트 보고서·성능 데이터·배포 URL 근거를 모음 | `docs/execution` 결과를 release evidence checklist로 매핑 | 최종 보고서 후보 목차와 증거 파일 목록이 작성됨 | Static/문서 | todo | `2026년 한이음 드림업 프로젝트 수행계획서.pdf` p.5~p.6 |

## P2 - 나중에 / 아이디어

| ID | 기능 | 사용자 가치 | 보류 이유 | 확인 필요 |
|---|---|---|---|---|
| P2-001 | 목적지 설정·경로 안내·주변 정보 질의 | 보행자가 화면 조작 없이 배리어프리 경로 안내를 받을 수 있음 | 현재 핵심은 위험 감지·신고이며 지도/API는 나중에 붙이는 후순위 기능 | 결정: MVP 제외, P2 후순위. 향후 Kakao Map API 우선. 근거: 사용자 확인 2026-05-18, PDF 2개, `docs/voice_stt_tts_status.md` |
| P2-002 | Kakao Map API 기반 지도/경로 연동 | 횡단보도·버스정류장·POI 기반 안전 경로 안내 | MVP 이후 후순위이며 API key, 약관, 비용, 장애 대응 결정 필요 | 결정: Kakao Map API 우선 후보. 실제 key/계정/비용은 구현 시점에 확인. 근거: 사용자 확인 2026-05-18, PDF 2개 |
| P2-003 | 지자체 민원 시스템/안전신문고 실제 API 연동 | 신고 데이터가 공공 처리 시스템으로 이어짐 | 외부 기관/API/권한/개인정보/운영 책임 필요 | 결정: 10월 범위는 demo/mock/export 우선, 실제 연동은 C 작업. 근거: 사용자 위임 2026-05-18, PDF 2개, `PROJECT_PLAN.md` |
| P2-004 | MLOps/data upload skeleton | 현장 데이터로 모델을 지속 개선하는 PDF 원안의 기반 | 이미지·센서 로그 동의, storage, 비용, 비식별 정책 필요 | IndexedDB/S3/대체 storage, opt-in 범위. 근거: PDF 2개 |
| P2-005 | browser/ONNX Runtime Web 추론 | 서버 없이 브라우저에서 탐지 가능성 확보 | 현재 기본은 `server(.pt)` 우선이며 ONNX는 browser latency 미실행 | 측정 기기, 목표 latency, 기본 runtime 재결정. 근거: `docs/model_integration_plan.md`, PDF 2개 |
| P2-006 | 실서비스 도메인/HTTPS release | PDF 성과목표인 실용화와 배포 URL 제출에 필요 | 도메인, 배포 계정, secret, storage, 운영 정책 필요 | 결정: demo/mock 우선으로 release evidence를 만들고, 실제 도메인/secret/storage는 승인 후 C 작업. 근거: 사용자 위임 2026-05-18, PDF 2개 |
| P2-007 | 공개 데이터셋/사회적 영향 지표 | 신고 데이터가 지자체·연구기관·복지 스타트업에 활용될 수 있음 | 개인정보/위치정보/라이선스/공개 범위 위험 | 공개 여부, 보관 기간, 비식별 기준, 참여 지자체 수/처리 건수/보수 반영률 지표. 근거: `2026년 한이음 드림업 프로젝트 수행계획서.pdf` p.6 |

## 오늘/다음 실행 후보

| 후보 | 유형 | 예상 변경 범위 | 검증 방법 | 막힘 |
|---|---|---|---|---|
| 공통 runtime gate 문서화 | 검증/안정화 | 실행 문서 또는 계획 문서 1개 | env/포트/Docker/PostGIS/voice/Android PASS/BLOCKED 표 | 없음: 단, 실제 runtime 실행은 환경에 따라 blocked |
| PostGIS reports no-skip 재검증 | 검증/안정화 | disposable DB 기반 backend test 실행 | `pytest backend/tests/test_reports.py -q -rs`, disposable DB 사용 기록 | env: Docker/PostGIS 접근 |
| Android 착용형 smoke | 신규 기능 검증 | 사원증형 휴대폰 목걸이 + ADB reverse 기반 수동 테스트 기록 | 기기/Chrome/권한/착용 방식/source/신고 ID 기록 | env: ADB/기기 접근 |
| voice HTTP/mic E2E | 신규 기능 검증 | voice/PWA 실행 기록 | `/health`, CORS, transcript/intent/UI action | env: loopback/브라우저/실폰 |
| IMU ROI fixture PoC | 신규 기능 | 센서 로그/계산 문서 또는 작은 스크립트 | ROI 계산 결과, 과대해석 금지 문구 | env: 실기기 로그 필요 |
| streaming failure sampler 50~100장 smoke | 신규 기능/model-data | model/data script 또는 실행 문서 | CSV row count, 메모리/디스크 gate, 이미지 저장 없음 | C 전 단계: 디스크 99% 기록 |

## 백로그 상태 값

- `todo`: 아직 시작 전
- `planned`: 일/주간 계획에 들어감
- `in_progress`: 구현 중
- `blocked_B`: 사용자 확인 필요
- `blocked_C`: 위험/대형 작업이라 승인 필요
- `blocked_env`: 환경 문제로 중단
- `done`: 완료 기준과 검증 근거 있음
- `dropped`: 명시적으로 제외
