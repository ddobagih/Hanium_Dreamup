# Privacy / Data Retention Evidence Checklist

- 최종 갱신: 2026-07-10 KST
- 관련 항목: Android native camera/depth metadata, report/export, fake/demo 분리, local settings/contacts, PWA/offline
- 주의: 이 체크리스트는 검증 계획이다. 체크되지 않은 항목은 `미검증`이며, 삭제/외부 전송/secret 작업은 별도 승인 전 실행하지 않는다.

## 1. Android camera/depth

| 체크 | 항목 | 필요한 evidence | 상태 |
|---|---|---|---|
| [x] | 기본 APK는 debug overlay를 표시하되 field session에 이미지/깊이 원본을 저장하지 않음 | Static/code review | 부분 PASS: field session은 allowlist metadata-only. 별도 debug 1장 HTTP capture와 제품 신고 이미지는 다른 경로 |
| [x] | TFLite model asset은 Git 기본 추적 대상이 아님 | `.gitignore`, file status | PASS: `*.tflite` local artifact |
| [x] | metadata-only capture log는 이미지 없이 frameTs/detection/depth summary만 저장 | Unit/static | 부분 PASS: `MetadataCaptureLogTest` |
| [x] | 메모리 capture buffer는 ARCore session stop 시 clear | Unit/Device | Unit PASS, Device 확인 필요. 명시 opt-in field session JSONL은 재시작 생존이 목적이라 별도 관리 |
| [x] | field session JSONL은 정확한 좌표·사용자 입력·이미지·음성을 제외하고 명시 시작/종료·회전을 지원함 | Unit/static | Unit PASS: `PersistentFieldSessionLogTest`; 실기기 회수는 미검증 |
| [x] | server debug log endpoint는 image/depth/GPS 없이 metadata allowlist만 받음 | Backend unit/schema | PASS: `backend/tests/test_android_debug_logs.py` |
| [x] | server debug log upload는 기본 비활성이고 `/reports/v2`와 분리됨 | Static/unit | PASS: `ANDROID_DEBUG_LOG_ENABLED=false` 기본값 |
| [ ] | 이미지/깊이 파일 export는 opt-in/승인 전 비활성 | Static/UX | 미구현, 승인 필요 |

## 2. 로컬 설정/연락처

| 체크 | 항목 | 필요한 evidence | 상태 |
|---|---|---|---|
| [x] | 보호자 연락처는 local-only로 저장되고 report/STT/detect payload로 전송되지 않음 | Unit/static payload grep | PASS: 2026-05-26 safe-slice |
| [x] | 전화번호 validation과 마스킹 helper가 있음 | Unit fixture | 부분 PASS |
| [x] | 실제 전화/SMS 발신은 기본 비활성이고 승인 전 실행하지 않음 | Static grep/문구 확인 | PASS/C-gate |

## 3. 신고/탐지 payload 최소화

| 체크 | 항목 | 필요한 evidence | 상태 |
|---|---|---|---|
| [x] | v2 report raw payload는 allowlist 필드만 저장함 | Unit/Integration bad extra field test | PASS: `backend/tests/test_reports_v2.py` |
| [x] | metadata 크기 제한이 있고 과대 payload는 413/422로 거절함 | Integration/schema test | PASS |
| [x] | raw audio, 보호자 연락처, secret이 저장되지 않음 | Unit/static grep + payload fixture | 부분 PASS |
| [x] | 이미지 업로드 EXIF 제거/재인코딩 정책이 있음 | Fixture test | PASS. 얼굴/차량번호 시각적 모자이크는 제품 report 원본에 적용하지 않음 |
| [x] | `source=fake/demo`는 성능/field/model evidence에서 제외됨 | Unit/export/model report fixture | 부분 PASS |
| [x] | Android source metadata allowlist가 `/reports/v2` 코드 계약에 반영됨 | Static/API fixture | 부분 PASS: `source=android`와 depth/report metadata allowlist는 구현됨. release auth/gate trust는 미완료 |

## 4. 동의/보존/삭제 gate

| 체크 | 항목 | 필요한 evidence | 상태 |
|---|---|---|---|
| [x] | report retention policy 문서에 보존 기간, 책임자, 삭제 승인 절차가 있음 | Static document review | PASS: `docs/operations/data_retention_policy.md` |
| [x] | retention script는 기본 dry-run이며 삭제 없이 만료 후보만 출력함 | Static/Unit dry-run test | PASS |
| [x] | 실제 삭제/초기화는 사용자 승인 없이는 실행하지 않음 | 운영 원칙 문구 확인 | PASS |
| [ ] | 실제 report row와 upload image를 180일 후 자동 삭제하는 scheduler/job이 있음 | Backend/ops test | 미구현. 현재는 dry-run만 있음 |
| [ ] | Android field session 파일 보존 기간과 앱 내 삭제 UX가 정의됨 | 정책 문서 | 앱 전용 파일 저장은 구현됐으나 자동 보존 기간·앱 내 삭제 UX는 미구현. ADB 회수 도구는 원본을 삭제하지 않음 |

## 5. export/upload/cache 보호

| 체크 | 항목 | 필요한 evidence | 상태 |
|---|---|---|---|
| [x] | report/export/upload 응답은 `Cache-Control: no-store`를 반환함 | Integration header test | PASS |
| [x] | public/redacted export mode는 좌표 rounding과 `image_path` 제외를 적용함 | Integration/GeoJSON snapshot | PASS |
| [x] | CSV injection 방어가 적용됨 | Unit/CSV fixture | PASS |
| [x] | fake/demo 포함 export는 filename/header/UI badge로 명확히 표시됨 | Integration/Headless fixture | PASS |
| [x] | 운영자 내부 export는 reviewed/fake 제외/redacted/unified-legacy tactile 조건을 필터로 분리할 수 있음 | GIS/Ops policy test | PASS. 기관 제출 기능은 제품 범위 아님 |

## 6. PWA/offline 저장소

Web/PWA는 주 사용자 경로지만 PWA 등록은 기본 OFF이고 offline은 app shell/static cache와 미연결 queue helper 수준이다. 아래 PASS를 완전 offline·모바일 Field·Release 근거로 확대하지 않으며 Android 연구 Device evidence와도 분리한다.

| 체크 | 항목 | 필요한 evidence | 상태 |
|---|---|---|---|
| [x] | offline report queue는 opt-in이고 TTL/용량 제한이 있음 | Unit/queue fixture | PASS |
| [x] | API/업로드 응답은 service worker cache에 저장되지 않음 | Static/Headless SW fetch fixture | PASS |
| [x] | PWA 설치 상태와 service worker update 상태를 표시함 | Unit/static fixture | PASS |

## 7. evidence 작성 시 개인정보 마스킹

- 제품 report image 원본은 사용자 결정에 따라 얼굴/차량번호 모자이크를 하지 않는다.
- 단, 외부 공유 evidence와 공개 문서 스크린샷은 제품 원본 report 저장 정책과 별개로 다룬다.
- 로그에는 실제 전화번호, 주소, GPS 원본, 이미지 경로, secret을 그대로 붙이지 않는다.
- 좌표가 필요한 경우 검증 목적에 맞게 rounding/mock 좌표를 우선 사용한다.
- 스크린샷에는 연락처/주소/이미지 식별 정보가 보이면 마스킹한다.
- Android Device evidence에는 APK hash, 기기/OS, 관찰 절차를 남기되 사람 얼굴/차량번호/주소가 보이면 마스킹한다.
- 외부 제출, public dataset 공개, cloud upload는 `Release` 또는 `C-gate`로 분리한다.
