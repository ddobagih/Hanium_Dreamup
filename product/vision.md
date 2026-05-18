# Product Vision

## 한 줄 컨셉

- WalkSafe Assist는 시각장애인·저시력 보행자가 스마트폰을 착용형 카메라처럼 사용하고, 카메라·GPS·IMU로 보행 위험을 감지해 TTS/진동으로 안내하며 위치·이미지 기반 신고 데이터를 축적하는 PWA다. 근거: `README.md`, `PROJECT_PLAN.md`, `2026 ICT 한이음 드림업 프로젝트 개요서.pdf`, `2026년 한이음 드림업 프로젝트 수행계획서.pdf`

## 문제 / 기회

- 어떤 문제를 해결하는가:
  - 보행 중 화면 확인이 어려운 시각장애인·저시력 보행자에게 점자블록 파손, 방치 킥보드/자전거, 공사 장애물, 노면 파임 같은 위험을 짧은 음성·진동으로 전달한다. 근거: `PROJECT_PLAN.md`, `docs/ui_feature_inventory.md`, `docs/neck_worn_phone_test_checklist.md`
  - 기존 지도/내비게이션은 차량·비장애인 중심이라 보행로의 실시간 물리 상태와 동적 장애물 정보를 제공하기 어렵다. 근거: `2026 ICT 한이음 드림업 프로젝트 개요서.pdf` p.1, `2026년 한이음 드림업 프로젝트 수행계획서.pdf` p.1/p.3
  - 위험 현장의 카메라 이미지, 위치 품질, 탐지 metadata를 신고 데이터로 남겨 운영자가 검토·처리할 수 있게 한다. 근거: `docs/api_reference.md`, `docs/report_operations.md`, `docs/pwa_backend_status.md`
- 지금 이 프로젝트가 필요한 이유:
  - PDF에는 보건복지부 2023년 조사 기준 점자블록 적정 설치율이 `45.7%`라고 기재되어 있다. 원출처는 별도 검증 전이므로 “PDF 기재 근거”로만 사용한다. 근거: `2026 ICT 한이음 드림업 프로젝트 개요서.pdf` p.1, `2026년 한이음 드림업 프로젝트 수행계획서.pdf` p.1/p.3
  - 한국 보행 환경 기준의 위험 객체와 점자블록 품질 문제를 다루며, 해외 공개 데이터는 smoke/pretrain 후보로만 사용한다. 근거: `docs/korean_data_strategy.md`, `docs/model_training_status.md`
  - 현재 PWA·백엔드·모델·음성 프로토타입이 각각 존재하므로, fake/server/model/voice/field 근거를 분리해 다음 검증과 기능 확장을 진행할 수 있다. 근거: `docs/current_status.md`, `plans/catchup/2026-05-18-audit-final.md`

## 현재 MVP와 PDF 원안의 범위

| 구분 | 내용 | 상태/주의 | 근거 |
|---|---|---|---|
| 현재 MVP | 보행 위험 감지, TTS/진동 알림, 신고 생성, `/admin` 검토, `server(.pt)` 우선 smoke | 실폰 field, PostGIS runtime, voice E2E는 아직 미완료 | `docs/current_status.md`, `product/decisions.md` |
| PDF 핵심 원안 | 3~5초 이동 궤적 ROI 기반 위험 선별, 목적지/경로 안내, 정밀 측위 신고, MLOps 자동 고도화 | 제품 장기 비전으로 반영하되 현재 완료로 쓰지 않음. 목적지/경로/지도 API는 MVP 이후 Kakao Map API로 붙일 예정 | `2026 ICT 한이음 드림업 프로젝트 개요서.pdf` p.1~p.3, `2026년 한이음 드림업 프로젝트 수행계획서.pdf` p.3~p.6, 사용자 확인 2026-05-18 |
| 실용화 목표 | PWA 배포 및 실서비스 도메인 연동, 최종 보고서·소스코드·배포 URL | 10월 외부 연동은 demo/mock 우선. 실제 도메인/배포/AWS/외부 API는 별도 승인과 C 작업 필요 | `2026년 한이음 드림업 프로젝트 수행계획서.pdf` p.5~p.6, 사용자 위임 2026-05-18 |

## 대상 사용자

| 사용자 | 상황 | 핵심 니즈 | 근거 |
|---|---|---|---|
| 시각장애인·저시력 보행자 | 사원증처럼 휴대폰을 목에 걸고 화면을 계속 보지 못함 | 짧고 즉시 행동 가능한 TTS/진동 위험 안내, 음성 조작, 신고 성공/실패 인지 | 사용자 확인 2026-05-18, `PROJECT_PLAN.md`, `DESIGN.md`, PDF 2개 |
| 보행 인프라 운영자/관리자 | 신고 이미지를 검토하고 상태를 변경함 | 신고 목록/필터/상세, 위치 품질, 검토 플래그, `new -> reviewed -> resolved` 처리 | `docs/ui_feature_inventory.md`, `docs/report_operations.md`, `docs/pwa_backend_status.md` |
| 지자체·복지관·교통약자 지원 기관 | 보행 위험 개선 근거와 베타 테스트 피드백을 활용함 | 실제 안전 검증과 데모/fake 근거의 구분, 보수 우선순위/히트맵 후보 | PDF 2개, `docs/current_status.md` |
| 개발/데모 담당자 | fake/server detector와 backend/API를 연결해 통합 흐름을 검증함 | `DetectionEvent` 계약 유지, source 분리, headless/실기기 검증 구분 | `docs/inference_contract.md`, `docs/model_placeholder_systems.md` |

## 내가 원하는 최종 모습

- [ ] 보행자 홈 `/`는 마케팅 랜딩이 아니라 카메라 기반 보행 보조 화면으로 시작한다. 근거: `DESIGN.md`, `docs/ui_feature_inventory.md`
- [ ] 위험 상태, 음성 안내 상태, 신고 가능 여부, 위치/방향 상태가 화면을 보지 않아도 TTS/진동/스크린리더로 이해된다. 근거: `docs/figma_make_accessibility_review.md`, `docs/neck_worn_phone_test_checklist.md`
- [ ] 3~5초 이동 궤적 ROI 안의 실제 충돌 위험 객체만 안내하는 방향으로 IMU/GPS/비전 데이터를 결합한다. 현재는 장기 목표이며 완료 근거가 없다. 근거: PDF 2개
- [ ] fake detector는 데모/API 흐름 검증용으로만 남고, 실제 탐지 결과는 `source: "server"` 또는 향후 `source: "onnx"`로 분리된다. 근거: `docs/model_placeholder_systems.md`, `docs/inference_contract.md`
- [ ] 신고 데이터는 이미지, 위치 품질, 검토 플래그, 중복 후보, 상태 이력을 운영자가 확인할 수 있다. 장기적으로 GeoJSON/히트맵/공공 활용을 검토한다. 근거: PDF 2개, `docs/api_reference.md`, `docs/report_operations.md`
- [ ] 모델은 한국 보행 환경 validation/test와 실폰/착용형 field smoke를 통과한 근거만 제품 성능으로 사용한다. 근거: `docs/korean_data_strategy.md`, `docs/model_training_status.md`, `plans/catchup/2026-05-18-audit-final.md`

## 핵심 사용 흐름

1. 보행자가 Android Chrome/PWA에서 `/`를 열고 카메라·위치 권한을 허용한다. 근거: `docs/ui_feature_inventory.md`, `docs/neck_worn_phone_test_checklist.md`
2. fake 또는 server detector가 `DetectionEvent`를 만들고, 앱은 bbox·위험 상태·TTS·진동으로 알린다. 근거: `docs/inference_contract.md`, `docs/pwa_backend_status.md`
3. 향후 IMU 이동 벡터를 사용해 3~5초 ROI 내 충돌 가능 객체만 선별 안내한다. 현재는 완료 근거가 없는 PDF 원안 기능이다. 근거: PDF 2개
4. 보행자가 `현재 위험 신고`를 실행하면 카메라 프레임과 metadata가 multipart로 전송되고, 중복 후보는 모달이 아니라 advisory로 표시된다. 근거: `docs/frontend_handoff_without_model.md`, `docs/api_reference.md`
5. 운영자는 `/admin`에서 신고 목록·필터·상세·이미지·위치 품질·검토 플래그를 확인하고 상태를 변경한다. 근거: `docs/ui_feature_inventory.md`, `docs/report_operations.md`
6. 모델/음성 검증자는 fake, server, model metric, STT/TTS, 실폰 field 결과를 서로 다른 근거로 기록한다. 근거: `docs/execution/2026-05-18_integration_field_report.md`, `plans/catchup/2026-05-18-audit-final.md`

## 차별점 / 재미 요소 / 중요한 경험

- 화면 주시보다 TTS, 진동, 스크린리더, 큰 터치 영역을 우선하는 보행 중 사용 경험. 근거: `DESIGN.md`, `docs/figma_make_accessibility_review.md`
- PDF 원안의 핵심 차별점은 “보이는 모든 객체”가 아니라 사용자의 예상 이동 궤적에 들어오는 위험만 선별하는 것이다. 근거: PDF 2개
- 중복 신고 후보가 있어도 보행자를 멈추는 모달을 띄우지 않고 advisory와 운영자 검토 정보로 넘긴다. 근거: `docs/frontend_handoff_without_model.md`, `docs/report_operations.md`
- 한국 점자블록과 보도 환경을 기준으로 데이터/검증을 분리한다. 근거: `docs/korean_data_strategy.md`, `docs/model_training_status.md`
- fake/server/onnx source를 계약으로 분리해 데모와 실제 모델 근거를 섞지 않는다. 근거: `docs/inference_contract.md`, `docs/model_placeholder_systems.md`

## 하지 않을 것 / 당분간 보류

- 일반 차량, 사람, 신호등, 횡단보도, 상점 간판, OCR 텍스트 탐지는 v1 범위에서 제외한다. 근거: `PROJECT_PLAN.md`
- Kakao Map API 기반 목적지/경로 안내는 MVP 이후에 붙인다. 현재 위험 감지·신고 MVP와는 분리한다. 근거: 사용자 확인 2026-05-18, PDF 2개
- 지자체 민원 시스템 실제 API 연동, AWS/S3 실제 업로드, Cloud STT/TTS 실제 사용, 완전 무중단 모델 배포, 월 1회 자동 재학습, 안내견 로봇/자율주행 휠체어/스마트 안경/흰 지팡이 센서 모듈 연동은 후순위 또는 C 작업이다. 근거: `PROJECT_PLAN.md`, PDF 2개, 사용자 위임 2026-05-18
- 유료/클라우드 STT/TTS API와 음성 모델 파인튜닝은 현재 하지 않는다. PDF에는 Whisper/Google Cloud 후보가 있으나 현재 결정은 local/free 우선이다. 근거: `docs/voice_stt_tts_status.md`, `docs/local_stt_tts_ai_prompt.md`, PDF 2개
- fake detector 결과를 정확도, 지연시간, 실제 보행 안전 판단 근거로 쓰지 않는다. 근거: `docs/model_placeholder_systems.md`, `docs/report_operations.md`

## 제약과 전제

- 기술 제약:
  - 현재 v2 모델은 class `0: damaged_tactile_block` 중심 baseline이며 4-class 서비스 성능 근거가 아니다. 근거: `docs/model_training_status.md`, `docs/model_v2_status.md`
  - 시연/MVP detector mode는 `server(.pt)` 우선, `fake` demo fallback으로 결정됐다. ONNX full metric equivalence는 통과했지만 browser/ONNX Runtime Web latency 근거는 없다. 근거: `docs/model_integration_plan.md`, `docs/execution/2026-05-17_model_data_mlops.md`
  - PDF에는 YOLOv11과 YOLOv8/v9가 혼재되어 있으므로 발표/문서 표기 전 기준 모델 버전 정리가 필요하다. 근거: `2026 ICT 한이음 드림업 프로젝트 개요서.pdf` p.1~p.3, `2026년 한이음 드림업 프로젝트 수행계획서.pdf` p.1/p.4
  - PWA server detector mode headless smoke는 실폰 착용형 field 성능 근거가 아니다. 근거: `docs/current_status.md`, `docs/execution/2026-05-18_integration_field_report.md`
- 환경 제약:
  - Android 실폰 접속 방식은 ADB reverse이고, 공식 field test 폼팩터는 사원증형 휴대폰 목걸이다. PWA 설치/offline, TalkBack, 브라우저/실폰 마이크 E2E는 아직 완료 근거가 없다. 근거: 사용자 확인 2026-05-18, `docs/current_status.md`, `plans/catchup/2026-05-18-audit-final.md`
  - PDF 원안은 가슴 고정용 마운트/바디캠을 반복하지만 최신 제품 결정은 사원증형 휴대폰 목걸이다. 발표/보고서에는 PDF 원안과 실제 검증 폼팩터 차이를 기록한다. 근거: PDF 2개, 사용자 확인 2026-05-18, `docs/neck_worn_phone_test_checklist.md`
  - PostGIS runtime은 2026-05-15 실제 DB PASS 근거가 있지만, disposable DB 기준 최신 reports HTTP smoke, duplicate/radius, row/upload 동작은 DB/TCP 접근 가능한 세션에서 재검증이 필요하다. 근거: `daylog/2026-05-15.md`, `plans/catchup/2026-05-18-audit-final.md`
  - `/` 사용률이 99% 수준으로 기록되어 대형 inference/학습은 용량 gate가 필요하다. 근거: `docs/execution/2026-05-18_model_data_mlops.md`
- 운영/비용/계정 제약:
  - 데이터셋 이미지/라벨, AI Hub zip, `runs/`, `.pt`, `.onnx`, 음성 샘플/출력/로그는 GitHub에 올리지 않는다. 근거: `README.md`, `docs/current_status.md`, `model/README.md`
  - PDF의 AWS EC2/S3, GitHub Actions, CDN/Blue-Green, Kakao Map API, 공공데이터 API, Cloud STT/TTS, 지자체 연계는 계정·비용·secret·개인정보·외부기관 의존성이 있어 MVP 이후 또는 C 작업으로 분리한다. 10월 외부 연동은 demo/mock 우선이다. 근거: PDF 2개, 사용자 확인/위임 2026-05-18, `plans/catchup/2026-05-18-audit-final.md`

## 근거 문서

| 근거 파일 | 반영한 내용 | 신뢰도 |
|---|---|---|
| `2026 ICT 한이음 드림업 프로젝트 개요서.pdf` | 공식 문제 배경, 주제/기술/성과목표, 3~5초 ROI, Voice-Interactive 내비게이션, MLOps 원안 | 높음: 단 원출처 수치 별도 검증 전 |
| `2026년 한이음 드림업 프로젝트 수행계획서.pdf` | 추진 일정, 정량 목표, 산출물, 적용 기술, release/도메인/최종 보고 기준 | 높음: 단 현재 구현 상태와 범위 차이 있음 |
| `README.md` | 프로젝트 목표, 4개 탐지 대상, 현재 완료/미완료 상태, 로컬 산출물 Git 제외 원칙 | 높음 |
| `PROJECT_PLAN.md` | 원래 구현 범위, 사용자, 최종 산출물, 후순위/제외 범위 | 중간: 일부 체크리스트는 최신 상태와 충돌 |
| `DESIGN.md` | 모바일 우선, 보행 중 인지 부하 최소화, 접근성/터치/색상 원칙 | 높음 |
| `docs/current_status.md` | 2026-05-18 최신 상태, 영역별 완료/남은 일 | 높음 |
| `docs/pwa_backend_status.md` | PWA/백엔드/server mode/fake detector 현재 상태 | 높음 |
| `docs/inference_contract.md` | `DetectionEvent`, source, upload/중복 신고 계약 | 높음 |
| `docs/model_training_status.md` | v1/v2 데이터셋, v2 지표, v3/v4 필요성 | 높음 |
| `docs/voice_stt_tts_status.md` | 최신 STT/TTS 판단과 미완료 E2E | 높음 |
| `plans/catchup/2026-05-18-audit-final.md` | B/C/진전 없음, 자동 A 없음 | 높음 |
