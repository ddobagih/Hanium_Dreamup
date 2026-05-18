# Product Roadmap

## 운영 모드

- 기본 모드: `feature-growth`
- 보조 판단: `integration-validation` 성격이 강하다. 근거 문서상 release/store/submission 마감보다 실폰·PostGIS·voice·model 검증 막힘 해소가 우선이다. 근거: `plans/catchup/2026-05-18-audit-final.md`, `docs/current_status.md`
- PDF 성과목표는 `실용화`와 `프로그레시브 웹앱 배포 및 실서비스 도메인 연동`이다. 이는 M3 release gate로 반영하되, 외부 연동은 demo/mock 우선으로 처리한다. 실제 도메인/AWS/외부 API/지자체 연계는 별도 승인과 C 작업 없이는 실행하지 않는다. 근거: PDF 2개, 사용자 위임 2026-05-18
- 권장 비율:
  - 신규 기능: 60%
  - 검증/안정화: 25%
  - 유지보수/문서: 15%
- B/C/진전 없음으로 막힌 비율은 별도 보고한다.

## 단계별 목표

| 단계 | 목표 | 대표 기능 | 완료 기준 | 상태 |
|---|---|---|---|---|
| M0 기준선 | 현재 동작 상태와 근거 분리 | PWA 정적 검증, backend no-DB/ASGI, v2 model metric, voice local sample, fake/server source 정책 | fake/server/model/voice/field 근거가 문서에서 분리되고 `git diff --check` 통과 | 부분 완료: `plans/catchup/2026-05-18-audit-final.md`, `daylog/2026-05-18.md` |
| M1 핵심 경험 | 사원증형 휴대폰 목걸이 착용 상태에서 위험 인지와 신고 흐름을 체감 | Android fake/server mode smoke, TTS/진동, GPS/heading, 신고, `/admin` 상태 변경 | 안전한 통제 환경에서 ADB reverse 실폰/착용형 smoke 기록, fake와 server 근거 분리, 신고 ID 기준 `/admin` 확인 | blocked_env: ADB reverse/기기 접근 필요. 근거: 사용자 확인 2026-05-18, `docs/neck_worn_phone_test_checklist.md` |
| M2 확장 기능 | PDF 원안의 핵심 차별점을 PoC로 검증 | IMU 3~5초 ROI, GeoJSON/히트맵, STT 브라우저/실폰 E2E, TTS HTTP fallback, 후순위 Kakao Map API 연동 준비 | 새 기능별 검증 등급 기록, fake/headless/실기기 근거 분리. 목적지/경로/지도 API는 MVP 제외 | planned/blocked: PDF 2개, 사용자 확인 2026-05-18, `docs/model_training_status.md`, `docs/voice_stt_tts_status.md` |
| M3 출시/제출/운영 | 외부에 보여도 과대해석 없는 실용화 후보 | PWA demo/release URL, 최종 보고서, 소스코드, 테스트 보고서, 성능 검증 데이터, 외부 연동 mock | 실제 운영 DB/secret/배포/계정/스토리지는 별도 승인 후 C 작업. 기본은 demo/mock release evidence | blocked_C/env: PDF 2개, 사용자 위임 2026-05-18, `plans/catchup/2026-05-18-audit-final.md` |

## PDF 추진일정과 현재 M 단계 매핑

| PDF 일정 | PDF 산출물 | 현재 product 매핑 | 상태 |
|---|---|---|---|
| 4월 | 요구사항 명세서, 기술 스택 선정 | M0 기준선 | 과거 기준선, 현재 문서로 재정리 중 |
| 4~5월 | 데이터셋 조사서, Docker 환경 구성 | M0/M2 model-data | 일부 완료, PostGIS runtime은 blocked_env |
| 5~6월 | 시스템 설계서, DB ERD, Figma 프로토타입, 접근성 체크리스트 | M0/M1 UX·API 기준선 | 문서 다수 존재, 실폰/TalkBack 미완료 |
| 6~7월 | 경량 YOLO 모델, 브라우저 추론 모듈 | M2 model/browser | v2 `.pt` baseline 있음, browser ONNX latency 미실행 |
| 7~8월 | PWA v0.9, FastAPI 서버 v0.9 | M1 핵심 경험 | PWA/backend 구현 일부 완료, runtime 검증 blocked |
| 8~9월 | CI/CD 파이프라인, IMU 퓨전 모듈 | M2/M3 확장 | C 작업/확인 필요 |
| 9~10월 | 테스트 보고서, 성능 검증 데이터 | M3 release | 실환경 검증 전 |
| 10월 | 최종 보고서, 소스코드, 배포 URL | M3 release | 도메인/배포 결정 필요 |

## 이번 주 우선순위

1. 공통 runtime gate를 먼저 고정한다: `git status`, env, 포트, Docker/PostGIS, backend/web/voice, Android 접속 방식을 PASS/BLOCKED로 분리한다. 근거: `plans/daily/2026-05-19.md`, `plans/weekly/2026-W21.md`
2. 사용자 결정사항을 반영해 Android는 ADB reverse, PostGIS는 disposable DB, detector는 `server(.pt)` 우선 + `fake` fallback으로 검증한다. 근거: 사용자 확인 2026-05-18, `product/decisions.md`
3. PDF 원안 중 현재 구현 범위를 넘는 기능은 장기 비전/P2/C로 분리한다: 목적지 경로 안내와 Kakao Map API 연동은 MVP 이후, AWS/S3·지자체 연계·월 1회 재학습·15% 성능 향상은 C/운영 목표로 둔다.
4. 확인 없이 과대해석하기 쉬운 항목을 완료로 쓰지 않는다: fake 안전 근거, headless field 근거, v2 4-class 성능, voice local sample의 실폰 E2E 대체.

## 다음 기능 후보

| 우선순위 | 기능 | 왜 지금 필요한가 | 예상 범위 | B/C 여부 |
|---|---|---|---|---|
| P0 | Android 착용형 fake/server smoke | 핵심 사용 전제가 사원증형 휴대폰 목걸이이므로 GUI/headless만으로는 제품 경험을 판단할 수 없음 | 목걸이 착용 상태에서 ADB reverse 접속, 기기/Chrome 기록, 카메라 각도/TTS/진동/GPS/신고 `/admin` 기록 | env |
| P0 | PostGIS reports no-skip + HTTP smoke | 신고 데이터 제품 가치의 핵심 저장/조회/처리 흐름 검증 필요 | disposable DB로 reports test, duplicate/radius, upload 동작 확인 | env: Docker/PostGIS 접근 |
| P0 | 브라우저/실폰 마이크 STT E2E | hands-free 신고/음성 제어가 제품 차별점이나 현재 로컬 샘플 근거만 있음 | voice HTTP health, CORS, `신고해/음성 켜·꺼/다시 말해줘/지금 어디야` UI action 기록 | env. 목적지/경로 intent는 P2 |
| P0 | server detector `source=server` 실폰/통제 입력 재확인 | fake demo에서 실제 model path로 넘어가기 위한 최소 통합 근거 | `server(.pt)` 우선 모드에서 `/detect/health ready`, bbox, `metadata.source=server`, 신고 저장 확인 | env |
| P1 | IMU 3~5초 ROI PoC | PDF 핵심 차별점인 인지 과부하 방지 기능의 최소 검증 | Android 센서 로그 수집, fixture 기반 ROI 계산, ROI 내/외 객체 필터링 문서화 | B/env |
| P1 | TTS HTTP cache/fallback/청취 평가 | direct-call cache hit만으로는 PWA 음성 안내 품질을 완료로 볼 수 없음 | 7문구 2회 HTTP 요청, `X-Voice-Cached`, voice-off/repeat/server-down, 스피커 청취 | env |
| P1 | PWA 설치/offline/TalkBack 수동 점검 | 시각장애인/저시력 사용자 경험의 핵심 안정성 | ADB reverse 접속 기준 Android Chrome 설치, offline shell, TalkBack 순서/aria-live 확인 | env |
| P1 | GIS/GeoJSON/히트맵 thin slice | PDF 원안의 지자체/공공 활용 가치를 demo 수준으로 검증 | disposable DB의 신고 좌표를 GeoJSON으로 export하고 단순 heatmap/mock 화면 기준 정의 | C 전 단계, env |
| P2 | Kakao Map API 기반 목적지·경로 안내 | PDF의 Voice-Interactive 내비게이션을 MVP 이후 후순위로 붙임 | route request/response schema, mock route, Kakao Map API adapter 후보, 음성 intent 연결 기준 | P2 후순위, 실제 key/계정은 구현 시점 확인 |
| P2 | MLOps/data upload skeleton | 월 1회 재학습/15% 향상 목표 전 단계 | IndexedDB queue, opt-in 동의, local-only manifest, upload adapter interface | C/B: cloud/storage/privacy |

## 검증/안정화 후보

| 우선순위 | 검증 항목 | 필요한 환경 | 막힘 | 대체 가능 여부 |
|---|---|---|---|---|
| P0 | PWA 정적 회귀 | Node/npm local | 현재 반복 PASS 근거 있음 | 가능: lint/typecheck/build |
| P0 | backend ASGI `/detect` smoke | `.venv`, local model artifact | DB 없이 일부만 가능 | 가능: no-DB ASGI smoke |
| P0 | PostGIS runtime/reports HTTP smoke | Docker/PostGIS, local TCP, disposable DB | 2026-05-15 PASS 근거는 있으나 2026-05-18 재검증은 Docker socket/DB/TCP 제한 반복 | 부분 가능: ASGI no-DB만 가능 |
| P0 | Android 착용형 field smoke | Android 기기, ADB reverse, Chrome 권한, 사원증형 휴대폰 목걸이 | ADB/기기 접근 필요 | 대체 불가: headless는 field 근거 아님 |
| P1 | Voice HTTP/CORS/mic E2E | voice server 9001, browser/phone mic | loopback/브라우저/실폰 환경 부재 | 부분 가능: local audio script만 가능 |
| P1 | IMU ROI/정밀 측위 | DeviceMotion/Orientation 로그, GPS, 통제 경로 | 실기기/위치 환경 필요 | 부분 가능: fixture 계산 |
| P1 | browser/ONNX Runtime Web latency | 모바일 또는 브라우저 환경 | 미실행, 대형 자동화 위험 | 대체 불가: CPU ONNX는 browser 근거 아님 |
| M3 | release/domain gate | 도메인, HTTPS, storage, secret, 배포 승인 | 사용자 결정/계정 필요 | local demo만 가능 |

## release 모드 전환 조건

- 아래 조건이 동시에 만족되기 전까지는 `release`가 아니라 `feature-growth` 또는 `stabilize`로 둔다.
  1. Android 착용형 smoke에서 카메라/GPS/TTS/진동/신고가 PASS.
  2. PostGIS disposable DB 기준 reports no-skip/HTTP smoke PASS.
  3. `server(.pt)` 또는 확정 runtime에서 `source=server` 신고 저장과 `/admin` 조회 PASS.
  4. PWA 설치/offline/TalkBack 기본 검증 PASS.
  5. 배포 도메인, HTTPS, secret, storage, 외부 API 사용 여부가 결정됨.
  6. 최종 보고서/소스코드/배포 URL 제출 기준이 문서화됨.

## B/C/중단 처리 정책

- B 사용자 확인 필요: 사용자의 제품 결정, 계정, secret, 경로, 환경, 제출 기준이 필요한 항목.
- C 위험/대형 작업: 운영 데이터, 배포, destructive QA, 대형 파일/LFS, secret rotation, Play Console, 비용/권한 영향이 큰 항목.
- 진전 없음/중단: 같은 환경 문제로 반복 실패해 자동 재시도 가치가 낮은 항목.

## 근거 문서

| 근거 파일 | 반영한 내용 |
|---|---|
| `2026 ICT 한이음 드림업 프로젝트 개요서.pdf` | 공식 성과목표, 3~5초 ROI, Voice-Interactive 내비게이션, MLOps 원안 |
| `2026년 한이음 드림업 프로젝트 수행계획서.pdf` | 추진 일정, 정량 목표, 최종 산출물, 적용 기술 |
| `docs/current_status.md` | 영역별 현재 상태와 미완료 field/runtime 항목 |
| `plans/catchup/2026-05-18-audit-final.md` | B/C/진전 없음, 자동 A 없음 |
| `plans/daily/2026-05-19.md` | 다음 runtime gate와 작은 slice 후보 |
| `docs/execution/2026-05-18_integration_field_report.md` | PWA 정적/ASGI 검증과 loopback/ADB/Docker 제한 |
| `docs/execution/2026-05-18_model_data_mlops.md` | v3 manifest, hard-negative, 용량/대형 작업 제한 |
| `docs/voice_stt_tts_status.md` | voice local 검증과 HTTP/PWA/실폰 미완료 |
