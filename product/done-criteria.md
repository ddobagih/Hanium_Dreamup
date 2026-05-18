# Done Criteria

## 완료로 인정하는 기준

- 구현 완료만으로는 `done`이 아니다.
- 실제 실행, 테스트, smoke, 스크린샷, 로그, 리포트 중 하나 이상의 근거가 있어야 한다.
- 실행하지 않은 항목은 `미검증`, `BLOCKED`, `확인 필요`로 남긴다.
- fake, headless, local audio, model metric, 실폰 field, release 검증은 서로 다른 등급으로 기록한다.
- PDF 원안 기능은 현재 구현 여부와 분리한다. PDF에 적힌 목표는 근거가 아니라 목표다.
- 모든 완료 판단에는 근거 파일 경로를 남긴다.

## 기능 완료 기준 템플릿

| 기능/흐름 | 완료 조건 | 필수 검증 | 증거 파일/로그 | 상태 |
|---|---|---|---|---|
| 보행자 PWA 카메라/탐지 UI | 카메라 fallback, bbox overlay, 위험 상태, detector mode, GPS/heading, TTS/진동이 현재 source에 맞게 표시됨 | `npm run lint`, `npm run typecheck`, `npm run build`, 실폰 smoke는 별도 | `docs/execution/2026-05-18_integration_field_report.md`, 향후 실폰 기록 | 정적 PASS, 실폰 미완료 |
| 위험 알림 TTS/진동 | 4개 위험 문구, 6초 쿨다운, 음성 off 강한 진동, 신고 성공/실패/유사 신고 패턴이 구분됨 | 사원증형 휴대폰 목걸이 + ADB reverse 기반 Android 실폰/착용형 smoke | `docs/neck_worn_phone_test_checklist.md`, 향후 field report | blocked_env |
| IMU 3~5초 ROI/궤적 예측 | 센서 로그에서 진행 방향/속도 추정, 3~5초 ROI 계산, ROI 내/외 객체 안내 차이 기록 | DeviceMotion/Orientation 로그, fixture 계산, 실기기 통제 경로 | 향후 sensor/field execution | 미완료 |
| 신고 생성 | `DetectionEvent` snapshot과 카메라 이미지가 multipart로 저장되고, 중복 후보는 advisory로 표시됨 | disposable DB 기반 PostGIS reports no-skip, HTTP smoke | `docs/api_reference.md`, `daylog/2026-05-15.md`, 향후 backend execution | 5/15 PASS, 최신 재검증 blocked_env |
| 정밀 측위/인프라 맵핑 | 원본 GPS, 위치 품질, 향후 보정 좌표/센서 metadata를 구분 저장하고 지도/GeoJSON으로 조회 가능 | PostGIS radius/cluster, GeoJSON export, 지도/heatmap thin slice | 향후 GIS execution | 일부 구현, 정밀 보정 미완료 |
| `/admin` 신고 운영 | 목록/필터/상세/이미지/위치 품질/review_flags/status patch가 신고 ID 기준으로 확인됨 | backend runtime + GUI/API smoke | `docs/ui_feature_inventory.md`, 향후 field report | blocked_env |
| server detector mode | `server(.pt)` 우선 모드에서 `/detect/health ready`, `/detect` 200, PWA 신고 `source=server` 저장 | ASGI smoke + headless/PWA + 실폰/통제 입력 분리 | `daylog/2026-05-18.md`, `docs/pwa_backend_status.md` | headless/ASGI 부분 PASS, field 미완료 |
| 모델 v2 baseline | test split metric, ONNX equivalence, latency, hard-negative FP 결과가 기록됨 | model eval scripts, CSV/JSON summary | `docs/model_training_status.md`, `docs/execution/2026-05-17_model_data_mlops.md` | class 0 baseline PASS |
| 모델 v3/v4 데이터 보강 | v3 후보 manifest, privacy_status, split_policy, class `1..3` 한국 GT 계획이 기록됨 | manifest row count, contact sheet/CSV 검수 | `docs/execution/2026-05-18_model_data_mlops.md` | v3 계획/manifest PASS, 새 학습 미완료 |
| STT 음성 명령 | 브라우저/실폰 마이크에서 transcript, intent, confidence, UI action이 확인됨 | voice HTTP health/CORS + desktop/phone mic E2E | `docs/voice_stt_tts_status.md`, 향후 voice execution | local audio PASS, E2E 미완료 |
| 대화형 음성 내비게이션 | 목적지 설정, 경로 조회, 단계별 안내, 재탐색/주변 정보 질의가 화면 조작 없이 동작 | mock route contract, 향후 Kakao Map API adapter, mic E2E, TTS 안내 | 향후 navigation execution | P2 후순위 |
| TTS HTTP/fallback/청취 | HTTP 2회 요청 cache header, server-down/voice-off/repeat fallback, 휴대폰 스피커 청취가 기록됨 | HTTP/CORS/E2E/수동 청취 | 향후 voice execution | direct-call cache만 PASS |
| PWA 설치/offline/TalkBack | Android Chrome standalone, offline shell fallback, TalkBack 읽기 순서와 aria-live가 확인됨 | 실폰/수동 접근성 점검 | `docs/figma_make_accessibility_review.md`, 향후 field report | 미완료 |
| MLOps 자동 고도화 | 사용자 동의, IndexedDB/local queue, upload adapter, 재학습 실행, 성능 비교, model rollback/dynamic load가 기록됨 | local skeleton 또는 cloud 승인 후 CI/CD smoke | 향후 MLOps execution | C/B 보류 |
| Release/실용화 | PWA demo/release URL, 최종 보고서, 소스코드, 테스트 보고서, 성능 검증 데이터가 제출 가능 | release gate, 배포 smoke, 보고서 evidence checklist, 외부 연동 mock 증거 | 향후 release/runbook | blocked_C/env |
| 운영/배포 보안 | 인증/권한/CORS/rate limit/storage/domain/secret이 승인되고 rollback이 정의됨 | Release gate, 배포 smoke | 향후 release/runbook | blocked_C/확인 필요 |

## PDF 정량 목표 처리

| 목표 | 완료로 쓰기 위한 조건 | 현재 상태 |
|---|---|---|
| 객체 인식 정확도 90% 이상 | 어떤 metric인지 정의하고, 한국 test/field 기준으로 재현 가능한 리포트 필요 | 미달/미확인. v2 mAP50-95는 별도 지표 |
| 경보 지연 1초 이내 | 카메라 입력→탐지→TTS/진동 시작까지 측정 방식과 기기 지정 필요 | 실폰 field 미완료 |
| 월 1회 이상 재학습 | 데이터 수집 동의, pipeline, 모델 버전, rollback, 성능 비교 필요 | C/B 보류 |
| 초기 대비 인식 성능 15% 이상 향상 | baseline metric과 비교 metric 정의 필요 | 미완료 |
| 단위 테스트 커버리지 60% 이상 | coverage 도구와 대상 범위 정의 후 CI 기록 필요 | 미확인 |
| 최종 보고서/소스코드/배포 URL | release evidence checklist와 제출 기준 충족 | M3 보류 |

## 검증 등급

| 등급 | 의미 | 예시 |
|---|---|---|
| Static | 코드/문서/설정 정적 확인 | `git diff --check`, `node --check`, py_compile, manifest row count |
| Unit | 단위 테스트 | unit test, schema validation, coverage |
| Integration | 서버/DB/API 통합 | API smoke, PostGIS reports no-skip, voice HTTP contract, migration dry-run |
| Headless E2E | 브라우저/fixture 기반 사용자 흐름 | PWA server-mode fixture, ASGI `.pt` smoke |
| Device E2E | 실기기/GUI 사용자 흐름 | Android 착용형 카메라, GPS/heading, TTS/진동, mic, TalkBack |
| Model Eval | 모델/데이터 검증 | test split mAP, accuracy 정의, latency, hard-negative FP, curation manifest |
| GIS/Ops | 공간 데이터 운영 검증 | PostGIS radius/cluster, GeoJSON export, heatmap, status 처리율 |
| Release | 배포/제출/운영 | domain, auth, storage, rollback, monitoring, release artifact |

## 일일 보고 비율

매일 계획/보고에 아래 비율을 포함한다. 현재 product roadmap 기본값은 `feature-growth`다.

| 항목 | 비율 | 근거 |
|---|---:|---|
| 신규 기능 개발 | 60% | `product/roadmap.md`, feature-growth 기본값 |
| 기존 기능 검증/안정화 | 25% | 실폰/PostGIS/voice/model 검증 막힘이 많음. 근거: `plans/catchup/2026-05-18-audit-final.md` |
| 유지보수/문서 | 15% | product 문서, stale 문서, PDF 원안과 현재 MVP 범위 정리 필요 |
| B/C/진전 없음으로 막힘 | 별도 보고 | DB/TCP/voice/env/cloud/API/C 작업은 자동 진행 금지 |

## 완료 금지 조건

다음은 완료로 쓰지 않는다.

- fake/mock 결과를 실제 운영 검증처럼 표현
- headless smoke를 실기기/GUI 검증처럼 표현
- 기존 PASS를 최신 변경 이후 PASS처럼 재사용
- 사용자가 정해야 할 값/계정/경로/배포 여부를 임의 결정
- 운영 DB/secret/Play Console/destructive QA를 승인 없이 실행. PostGIS 검증은 disposable DB 우선
- `source=fake` 신고를 모델 정확도, 지연시간, 실제 보행 안전 판단, 실제 사용자 field 근거로 사용
- `.pt` server adapter ASGI/headless PASS를 Android 착용형 camera field 성능으로 사용
- v2 class `0` 점자블록 baseline을 4-class 서비스 성능으로 사용
- PDF의 “정확도 90%”를 현재 v2 metric 달성으로 표현하거나, `mAP 0.9`와 같은 의미로 사용
- PDF의 “경보 지연 1초 이내”를 실폰 측정 없이 완료로 표현
- STT 실제 사람 음성 8/8 성공을 브라우저/실폰 마이크 E2E 완료로 표현
- TTS 7문구 direct-call cache hit를 HTTP cache header/fallback/휴대폰 스피커 청취 완료로 표현
- service worker 기본 캐시를 PWA 설치/offline/TalkBack 수동 검증 완료로 표현
- VL1+VS1 hard-negative 200장 FP 평가를 recall/mAP 또는 positive detection 성능 근거로 표현
- contact sheet 빠른 triage를 정식 개인정보/위치정보 비식별 검수로 표현
- AWS/S3, Kakao Map API, 지자체 API, Cloud STT/TTS, 공공 데이터셋 공개, Blue-Green 배포를 구현/검증 없이 실제 운영 완료로 표현
- 월 1회 자동 재학습 또는 15% 성능 향상을 pipeline/metric 없이 완료로 표현

## 기능별 증거 우선순위

| 기능 영역 | 1순위 증거 | 2순위 증거 | 완료 판정 주의 |
|---|---|---|---|
| PWA/UX | Android 실폰 수동 기록, 스크린샷/로그 | lint/typecheck/build | 정적 PASS는 field PASS가 아님 |
| Backend/PostGIS | no-skip pytest, HTTP smoke, disposable DB 기록 | ASGI no-DB smoke | DB skip이 있으면 runtime 완료 아님 |
| Detector | `source=server` PWA 신고와 `/admin` 확인 | ASGI `.pt` smoke | fake와 server 성능 분리 |
| IMU/ROI | 실기기 센서 로그와 ROI 계산 리포트 | fixture 계산 | 센서 수집만으로 충돌 필터링 완료 아님 |
| Model | 한국 test/validation metric, latency, failure review | dry-run/smoke | class 0과 4-class, accuracy와 mAP 구분 |
| Voice | 브라우저/실폰 mic + UI action | local audio script | local sample은 E2E가 아님 |
| Navigation | route contract + Kakao Map API 또는 mock route 기반 경로 안내 E2E | mock route | 목적지 intent만으로 내비게이션 완료 아님. MVP 범위 아님 |
| GIS/Ops | GeoJSON/heatmap/radius/cluster 조회 | DB row 확인 | 실제 지자체 연계와 구분 |
| Accessibility | TalkBack/aria-live/터치 타깃 수동 기록 | 디자인 문서/DevTools | 문서 기준만으로 완료 아님 |
| MLOps/Release | 승인된 env/secret/storage/domain + rollback 또는 명시적 demo/mock release evidence | local runbook | 실제 외부 연동은 별도 승인 전 실행 금지 |
