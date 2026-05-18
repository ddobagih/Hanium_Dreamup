# Product Decisions

## 결정된 사항

| 날짜 | 결정 | 이유 | 영향 범위 | 근거 |
|---|---|---|---|---|
| 2026-05-18 | 제품 기본 방향은 시각장애인·저시력 보행자용 착용형 스마트폰 PWA이며, 화면보다 TTS/진동/스크린리더를 우선한다 | 보행 중 화면 주시가 어렵고 PDF/문서가 착용형·음성 우선 UI를 반복 명시 | PWA UX, field test, done criteria | `DESIGN.md`, PDF 2개, `docs/neck_worn_phone_test_checklist.md` |
| 2026-05-18 | `source=fake`는 UI/API/운영 흐름 검증용이며 성능·안전 근거로 쓰지 않는다 | fake detector는 실제 모델 결과가 아니므로 과대해석 위험 | 보고, 관리자 필터, 모델 성능 문서 | `docs/model_placeholder_systems.md`, `docs/report_operations.md` |
| 2026-05-18 | v2 모델은 class `0: damaged_tactile_block` baseline으로만 본다 | 한국 GT class `1..3`가 없어 4-class 서비스 성능을 산출할 수 없음 | 모델 roadmap, 발표 문구, backlog | `docs/model_training_status.md`, `docs/model_v2_status.md` |
| 2026-05-18 | 현재 운영 모드는 `feature-growth`로 둔다 | release/store/submission 마감보다 실폰·PostGIS·voice·model 검증과 신규 slice가 우선 | roadmap, 일일 보고 비율 | `docs/current_status.md`, `plans/catchup/2026-05-18-audit-final.md` |
| 2026-05-18 | Android 실폰 검증 접속 방식은 ADB reverse로 한다 | 로컬 backend/web/voice를 휴대폰 Chrome에서 안정적으로 접근시키기 위함 | Android 착용형 smoke, PWA 설치/offline/TalkBack, 실폰 mic E2E | 사용자 확인: 2026-05-18 |
| 2026-05-18 | PostGIS runtime smoke는 disposable DB를 우선 사용한다 | 테스트 row/upload cleanup 실수와 운영 데이터 영향 위험을 줄이기 위함 | reports no-skip, HTTP smoke, duplicate/radius 검증 | 사용자 확인: 2026-05-18 |
| 2026-05-18 | 시연/MVP detector mode는 `server(.pt)` 우선, `fake`는 demo fallback으로 둔다 | `.pt` server adapter smoke 근거가 있고 browser ONNX latency 근거는 아직 없기 때문 | PWA detector mode, 보고 문구, fake/source 분리 | 사용자 확인: 2026-05-18 |
| 2026-05-18 | PDF의 `정확도 90% 이상`, `경보 지연 1초 이내`는 목표로 기록하되 현재 완료 기준으로 쓰지 않는다 | 현재 v2 mAP와 실폰 지연 근거가 목표에 미달/미확인이고 metric 정의가 필요함 | done criteria, 발표 문구 | `2026년 한이음 드림업 프로젝트 수행계획서.pdf` p.5, `docs/model_training_status.md` |
| 2026-05-18 | PDF의 `PWA 실서비스 도메인 연동`은 M3 release 목표로 둔다 | 공식 성과목표이나 도메인/배포/secret/storage 결정이 필요함 | roadmap, C 작업 | PDF 2개 |
| 2026-05-18 | 목적지 설정·경로 안내·공공데이터/지도 API 연동은 MVP에서 제외하고 나중에 붙인다 | 현재 핵심은 위험 감지·신고이며, 지도/API는 후순위로 붙여도 핵심 MVP와 결합도가 낮음 | backlog P2, roadmap M2/M3, voice intent | 사용자 확인: 2026-05-18 |
| 2026-05-18 | 향후 지도/API 연동은 Kakao Map API를 우선 후보로 둔다 | 사용자가 후속 지도 연동 API로 Kakao Map API를 지정함 | P2-001, P2-002, release C 작업 | 사용자 확인: 2026-05-18 |
| 2026-05-18 | 공식 field test 착용 방식은 사원증처럼 휴대폰을 목에 거는 목걸이 방식으로 한다 | 실제 사용/시연 폼팩터를 사용자가 확정함 | Android 착용형 smoke, 카메라 각도, IMU ROI, done criteria | 사용자 확인: 2026-05-18 |
| 2026-05-18 | 10월 성과목표의 외부 연동은 demo/mock 우선으로 처리한다 | 사용자가 위임했고, 실제 AWS/S3·Cloud STT/TTS·지자체 연동은 비용/secret/개인정보/외부기관 의존성이 크기 때문 | M3 release, C 작업, 발표 문구 | 사용자 위임: 2026-05-18 |

## 우선 질문

- 현재 product 범위 결정을 위해 추가로 물어볼 질문은 없다.
- 남은 막힘은 주로 실행 환경이다: ADB/실기기, Docker/PostGIS/TCP, loopback/브라우저/voice runtime.

## B - 사용자 확인 필요

- 현재 우선 B 항목 없음.
- 아래 항목은 2026-05-18 사용자 결정으로 해소됨.

| ID | 결정 | 관련 기능/검증 |
|---|---|---|
| B-004 | 목적지 설정·경로 안내·공공데이터/지도 API 연동은 MVP 제외, P2 후순위. 향후 Kakao Map API 우선 | P2-001, P2-002 |
| B-005 | 공식 field test 착용 방식은 사원증형 휴대폰 목걸이 | P0-002, P1-001 |
| B-006 | 실서비스 도메인/AWS/S3/지도 API/Cloud STT/TTS/지자체 연동은 demo/mock 우선. 실제 외부 연동은 C 작업으로 보류 | P2-002~P2-007 |

## C - 위험/대형 작업

| ID | 작업 | 위험 | 필요한 승인/환경 | rollback/cleanup | 현재 상태 |
|---|---|---|---|---|---|
| C-001 | PostGIS schema migration: constraint, `updated_at`, status history | DB migration과 기존 데이터 영향 | DB 접근, migration 승인, 테스트 DB | migration rollback, test DB reset | 보류 |
| C-002 | VL1+VS1 전체 1,038장 hard-negative inference | 대용량 복사/디스크 사용, 시간 비용 | 디스크 gate, 이미지 저장 정책 | 생성 산출물 삭제 기준 | 보류 |
| C-003 | full failure sampling 재실행과 새 학습 | exit code `137` 재발, 대형 runs/datasets 증가 | `/` 용량 확보, streaming/checkpoint 설계 | runs/datasets cleanup 기준 | 보류 |
| C-004 | browser/ONNX Runtime Web latency 측정 자동화 | 브라우저/기기별 변동, 자동화 비용 | 측정 기기, 브라우저, 목표 latency | 결과 문서와 임시 산출물 cleanup | 보류 |
| C-005 | class `1..3` 한국 GT 수집/라벨링과 4-class metric 산출 | 개인정보/위치정보, 라벨 비용, 데이터 권리 | 데이터 출처/약관/비식별 승인 | 원본 local-only, manifest 관리 | 보류 |
| C-006 | 인증/권한, CORS 운영 정책, rate limit, 외부 storage, 배포 구성 | secret/비용/운영 노출/권한 문제 | 도메인, 계정, secret, 배포 승인 | secret rotation, storage cleanup, rollback | 보류 |
| C-007 | AWS EC2/S3, CDN, Blue-Green, GitHub Actions 기반 MLOps 자동 배포 | 비용, secret, 개인정보, 운영 복잡도 | AWS 계정/비용 한도/리전/보안 정책 | bucket cleanup, model rollback, secret rotation | 보류 |
| C-008 | 지자체 민원/안전신문고 실제 API 연계 | 외부기관 의존, 개인정보/법적 책임, 오신고 처리 | 기관/API/인증/제출 형식 승인 | mock 전환, 전송 중단, 테스트 데이터 폐기 | 보류 |
| C-009 | 공개 데이터셋/사회적 영향 지표 공개 | 위치정보/초상/차량번호/라이선스 위험 | 비식별 정책, 동의, 공개 범위 승인 | 공개 철회, 원본 삭제/비공개 | 보류 |
| C-010 | Cloud STT/TTS 또는 유료 API 사용 | 비용, API key, 개인정보 전송, 장애 대응 | 계정/비용/약관/키 관리 | local fallback, key revoke | 보류 |

## 진전 없음 / 중단

| 항목 | 원인 | 반복 횟수/기간 | 자동 재시도 여부 | 해소 조건 |
|---|---|---|---|---|
| PostGIS/Alembic/reports runtime, HTTP smoke, duplicate/radius, row/upload cleanup | Docker socket 권한, DB/TCP 제한 | 2026-05-17~2026-05-18 반복 기록 | no | DB/TCP 가능한 세션과 disposable DB 확보 |
| server-mode headless E2E | `127.0.0.1:8000/health` loopback 접근 `Operation not permitted` | 2026-05-17~2026-05-18 반복 기록 | no | loopback 허용 세션 또는 실행 환경 변경 |
| Android 착용형 field test | `adb` 없음, 장비 접근 미확보 | 2026-05-17~2026-05-18 반복 기록 | no | ADB reverse 가능한 장비 접근 확보 |
| fake 신고 `/admin` 운영 흐름과 server `source=server` 신고 저장 재확인 | backend/PostGIS/실폰 runtime 미확보 | 2026-05-18 기록 | no | DB/backend/web/Android runtime gate 통과 |
| Voice HTTP/CORS/mic/TTS header | loopback/브라우저/실폰 환경 부재 | 2026-05-18 기록 | no | voice server 접근, browser/phone mic 세션 확보 |

## 나중에 재검토할 가정

- 추정: 현재 roadmap 운영 모드는 release보다 `feature-growth`가 맞다. release/store/submission 마감 근거가 생기면 바꾼다.
- 추정: 다음 자동화의 가장 작은 시작점은 runtime gate 기록이다. 단, 실제 기능 검증은 env 해소가 필요하다.
- v2 `best.pt`는 backend ready artifact로 유지하되, browser/ONNX Runtime Web latency 근거가 생기면 onnx 기본값을 재검토한다.
- 목적지 설정·경로 안내는 MVP에서 제외하고 P2로 보류한다. 후속 구현 시 Kakao Map API를 우선 후보로 둔다.
- PDF의 월 1회 재학습과 초기 대비 15% 성능 향상은 MLOps 운영 목표이지 현재 모델 완료 기준이 아니다.

## 문서 충돌

| 충돌 | 최신 해석 | 근거 |
|---|---|---|
| `/detect`가 placeholder/adapter 미구현으로 남은 오래된 표현 | 최신 상태는 `.pt` adapter와 PWA server mode headless smoke 완료. 다만 실폰 field 성능은 미완료 | `docs/model_integration_plan.md`, `docs/pwa_backend_status.md`, `docs/current_status.md` |
| STT 실제 음성 지연 수치가 오래된 요약과 최신 보정에서 다름 | 최신 근거는 2026-05-17/18 실제 음성 8/8, 평균 약 1.8~1.9초, p95 약 2.0초 범위 | `docs/voice_stt_tts_status.md`, `daylog/2026-05-18.md`; 오래된 `docs/stt_tts_current_status.md`는 주의 |
| v2 test split이 일부 계획 문서에는 미완료로 남음 | 최신 상태 문서 기준 test split 별도 검증 완료. 다만 목표 mAP 0.9와 4-class 서비스 성능은 미달/미확인 | `docs/model_training_status.md`, `docs/model_v2_status.md` |
| PostGIS reports 검증 상태가 PASS와 BLOCKED로 모두 보임 | 2026-05-15에는 실제 DB smoke/cleanup PASS 근거가 있으나, 2026-05-18 최신 재검증은 Docker/DB/TCP 제한으로 blocked_env로 본다 | `daylog/2026-05-15.md`, `docs/execution/2026-05-18_integration_field_report.md` |
| PDF의 YOLO 버전 표기가 `YOLOv11`과 `YOLOv8/v9`로 혼재 | 현재 product 문서는 실제 산출물과 코드 기준으로 `.pt`/YOLO baseline을 우선하고, 발표 전 모델 버전 표기 정리 필요 | PDF 2개, `docs/model_training_status.md` |
| PDF는 가슴 마운트/바디캠, 현재 제품 결정은 사원증형 휴대폰 목걸이 | 최신 제품 결정은 목걸이 방식이다. PDF 원안과 다르므로 발표/보고서에는 폼팩터 변경 사유와 실제 검증 착용 방식을 기록 | PDF 2개, 사용자 확인 2026-05-18, `docs/neck_worn_phone_test_checklist.md` |
| PDF는 OpenAI/Google Cloud STT/TTS 후보, 현재 문서는 local/free 우선 | 10월 외부 연동은 demo/mock 우선이며, 유료/클라우드 API 실제 사용은 C 작업으로 보류 | PDF 2개, 사용자 위임 2026-05-18, `docs/voice_stt_tts_status.md` |
| `docs/ui_feature_inventory.md`의 server/STT 미구현 표현 일부 | UI 기능 목록은 유효하지만, server mode와 STT upload/UI 상태는 최신 상태 문서로 보정해 읽어야 함 | `docs/current_status.md`, `docs/pwa_backend_status.md`, `docs/voice_stt_tts_status.md` |

## 과대해석 방지 보고

- 완료로 쓰지 않은 항목:
  - PDF의 장기 비전 전체를 현재 MVP 완료 범위로 쓰지 않음.
  - fake detector 결과를 실제 안전 판단, 정확도, latency, field 성능 근거로 쓰지 않음.
  - server detector headless smoke를 Android 실폰/착용형 field 성능으로 쓰지 않음.
  - v2 class 0 baseline을 4-class 서비스 모델 또는 정확도 90% 달성으로 쓰지 않음.
  - `정확도 90%`와 `mAP 0.9`를 같은 의미로 쓰지 않음. metric 정의 필요.
  - STT local sample 8/8 성공을 브라우저/실폰 마이크 E2E 완료로 쓰지 않음.
  - TTS direct-call cache hit를 HTTP cache header/fallback/휴대폰 스피커 청취 완료로 쓰지 않음.
  - service worker 기본 캐시를 PWA 설치/offline/TalkBack 완료로 쓰지 않음.
  - 월 1회 재학습/15% 성능 향상, AWS/S3, Kakao Map API, 지자체 연계, 공개 데이터셋을 현재 완료로 쓰지 않음.
- 출처 없는 신규 기능 수 / 백로그 기능 항목 수 / 비율: 0 / 20 / 0%.
- B/C/진전 없음 항목: 우선 B 0개, C 10개, 진전 없음 5개.
- 다음 자동화가 신규 기능 개발로 착수해도 되는 첫 slice:
  - 확인 없이 가능한 첫 slice는 “공통 runtime gate 기록”이다.
  - 사용자 결정 반영 후 실제 기능 검증 첫 slice는 `ADB reverse 기반 Android 착용형 smoke` 또는 `disposable DB 기반 PostGIS reports no-skip` 중 하나로 시작한다.
