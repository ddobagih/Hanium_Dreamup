# Privacy / Data Retention Evidence Checklist

- 기준 문서: `plans/features/2026-05-26_feature_followup_implementation_list.md`
- 관련 항목: #15 fake/demo 분리, #17 긴급 연락처, #18 초기 설정/동의, 공통 개인정보/데이터 보존, 공통 PWA/offline
- 주의: 이 체크리스트는 검증 계획이다. 체크되지 않은 항목은 `미검증`이며, 삭제/외부 전송/secret 작업은 별도 승인 전 실행하지 않는다.

## 1. 로컬 설정/연락처

| 체크 | 항목 | 필요한 evidence | 상태 |
|---|---|---|---|
| [x] | 보호자 연락처는 local-only로 저장되고 report/STT/detect payload로 전송되지 않음 | Unit/static payload grep | PASS: `scripts/check_frontend_settings_privacy_20260526.sh` |
| [x] | 전화번호 validation과 오류 메시지가 한국어로 명확함 | Unit fixture | 부분 PASS: validation fixture 통과, DOM 문구는 추가 확인 필요 |
| [x] | 연락처 삭제/초기화가 가능하고 삭제 후 reload에도 남지 않음 | Static/UI wiring | 부분 PASS: clear action wiring, reload mock은 추가 확인 필요 |
| [x] | 화면 표시 시 연락처가 마스킹됨 | Unit fixture | PASS: `maskGuardianPhone` fixture |
| [x] | 복수 긴급 연락처는 최대 3개 local-only 구조로 저장됨 | Unit fixture | PASS: `settings-privacy.test.ts` schema v3/multiple contacts |
| [x] | 실제 전화/SMS 발신은 기본 비활성이고 승인 전 실행하지 않음 | Static grep/문구 확인 | PASS/C-gate: 발신 구현 없음, 승인 전 실행 금지 |

## 2. 신고/탐지 payload 최소화

| 체크 | 항목 | 필요한 evidence | 상태 |
|---|---|---|---|
| [x] | v2 report raw payload는 allowlist 필드만 저장함 | Unit/Integration bad extra field test | PASS: `backend/tests/test_reports_v2.py` |
| [x] | metadata 크기 제한이 있고 과대 payload는 413/422로 거절함 | Integration/schema test | PASS: `backend/tests/test_reports_v2.py` |
| [x] | raw audio, 보호자 연락처, secret, 불필요한 device identifier가 저장되지 않음 | Unit/static grep + payload fixture | 부분 PASS: guardian no-send grep, raw audio/secret 저장 없음. device identifier grep은 추가 필요 |
| [x] | 이미지 업로드 EXIF 제거/재인코딩 정책이 있음 | Fixture test | PASS: `backend/tests/test_uploads.py::test_strip_image_metadata_removes_jpeg_exif_when_decodable` |
| [x] | `source=fake/demo`는 성능/field/model evidence에서 제외됨 | Unit/export/model report fixture | 부분 PASS: `performance_excluded`, `data_origin=demo`, export filename/header/UI badge. 모델 성능 리포트 자동 집계 제외는 추가 필요 |

## 3. 동의/보존/삭제 gate

| 체크 | 항목 | 필요한 evidence | 상태 |
|---|---|---|---|
| [x] | 이미지 증거/GPS 포함 동의 UI와 consent version을 기록함 | DOM + payload fixture | 부분 PASS: local settings consent version 저장. report payload consent fixture는 추가 필요 |
| [x] | report retention policy 문서에 보존 기간, 책임자, 삭제 승인 절차가 있음 | Static document review | PASS: `docs/data_retention_policy.md` |
| [x] | retention script는 기본 dry-run이며 삭제 없이 만료 후보만 출력함 | Static/Unit dry-run test | PASS: `scripts/check_report_retention_dry_run.py` fixture 실행 |
| [x] | 실제 삭제/초기화는 사용자 승인 없이는 실행하지 않음 | 운영 원칙 문구 확인 | PASS: retention policy/script 모두 destructive_action=false, `--execute-delete` 차단 |

## 4. export/upload/cache 보호

| 체크 | 항목 | 필요한 evidence | 상태 |
|---|---|---|---|
| [x] | report/export/upload 응답은 `Cache-Control: no-store`를 반환함 | Integration header test | PASS: `backend/tests/test_reports_v2.py`, upload static response |
| [x] | public/redacted export mode는 좌표 rounding과 `image_path` 제외를 적용함 | Integration/GeoJSON snapshot | PASS: `backend/tests/test_reports_v2.py` |
| [x] | CSV injection 방어가 적용됨 | Unit/CSV fixture | PASS: formula-like `source_model` CSV escape fixture |
| [x] | fake/demo 포함 export는 filename/header/UI badge로 명확히 표시됨 | Integration/Headless fixture | PASS: demo-aware filename/header, row/detail badge wiring |
| [x] | 기관 제출 후보 export는 reviewed 이상, fake 제외, custom tactile 조건을 분리함 | GIS/Ops policy test | PASS: `scripts/check_frontend_admin_report_summary_policy_20260525.sh` |

## 5. PWA/offline 저장소

| 체크 | 항목 | 필요한 evidence | 상태 |
|---|---|---|---|
| [x] | offline report queue는 opt-in이고 TTL/용량 제한이 있음 | Unit/queue fixture | PASS: `offline-report-queue-policy.test.ts`, opt-in/TTL/capacity/manual retry |
| [x] | API/업로드 응답은 service worker cache에 저장되지 않음 | Static/Headless SW fetch fixture | PASS: `scripts/check_frontend_accessibility_static.py`, `node --check apps/web/public/sw.js` |
| [x] | PWA 설치 상태와 service worker update 상태를 표시함 | Unit/static fixture | PASS: `scripts/check_frontend_pwa_policy_20260526.sh` |
| [x] | offline 상태에서는 외부 요청 전 사용자에게 즉시 안내함 | Headless navigator.onLine mock | 부분 PASS: report/detect/voice client preflight와 offline banner. DOM mock은 추가 필요 |

## 6. evidence 작성 시 개인정보 마스킹

- 로그에는 실제 전화번호, 주소, GPS 원본, 이미지 경로, secret을 그대로 붙이지 않는다.
- 좌표가 필요한 경우 검증 목적에 맞게 rounding/mock 좌표를 우선 사용한다.
- 스크린샷에는 연락처/주소/이미지 식별 정보가 보이면 마스킹한다.
- 외부 기관 제출, public dataset 공개, cloud upload는 `Release` 또는 `C-gate`로 분리한다.
