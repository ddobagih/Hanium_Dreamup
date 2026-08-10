# EPIC-01 Phase D Legacy Web 공식 경로 기술 폐쇄 기록

- 문서 ID: `WS-EPIC-01-PHASE-D-LEGACY-WEB-CLOSURE-IMPLEMENTATION-20260723-001`
- 버전: `0.1.0`
- 상태: `INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS`
- EPIC: `EPIC-01 IN_PROGRESS`
- 통제 구현 경로: **34개**

## 내부 구현한 폐쇄

- **LEGACY_UI_RUNTIME_GONE_WITH_EXACT_BFF_ALLOWLIST** — 공식 npm dev/start는 127.0.0.1:3000에만 결속되고 Next 요청 경계는 Android가 사용하는 정확한 4개 API 외 모든 UI·PWA·관리자·과거 API 경로를 410 no-store로 닫는다.
- **WEB_RELEASE_AND_FULL_RC_ENTRYPOINTS_FAIL_CLOSED** — CI의 Web release 생성·업로드를 제거하고 과거 Web release/full-RC build·validate·Web product-quality CLI는 산출물이나 다른 부작용 전에 코드 78로 종료한다.
- **PUBLIC_LAUNCHERS_AND_DEPLOY_INPUTS_FAIL_CLOSED** — 두 Cloudflare/field launcher는 부작용 전에 코드 78로 종료하고 systemd·nginx·환경 예시는 활성 지시문이 전혀 없는 DO NOT INSTALL 주석 자료로 남긴다.
- **TRANSITIONAL_ANDROID_BFF_EXCEPTION_EXPLICIT** — Android가 실제 참조하는 4개 route만 loopback 회귀·전환형 BFF 예외로 유지하며 독립 Android API gateway 추출은 완료로 주장하지 않는다.

## 유지하는 전환형 Android BFF 예외

- `/api/field-session`
- `/api/navigation/walking`
- `/api/navigation/destinations/search`
- `/api/reports/v2`

이 예외는 `127.0.0.1:3000` loopback 회귀·계약 확인에만 허용된다. 독립 Android API gateway 추출은 **NOT_COMPLETED**다.

## 주장하지 않는 것

임의 수동 Next 실행을 운영체제 수준에서 불가능하게 만들었다고 주장하지 않는다. 과거 외부 URL·DNS·Cloudflare 자원의 폐기 또는 접근불가 probe와 기존 설치·캐시 PWA 비활성 확인도 실행하지 않았다. 실제 기기와 정식 시험도 실행하지 않았다.

## 공개 미결사항

- `EPIC-01-NEXT-BFF-EXTRACTION` / **OPEN_NEXT** — Android가 사용하는 4개 Next API route를 독립 Android API gateway로 추출하고 Android endpoint를 전환해야 한다.
- `PHASE-D-ARBITRARY-MANUAL-NEXT-BYPASS` / **LIMITATION_OPEN** — 공식 npm/runner는 loopback으로 제한했지만 저장소 코드를 임의 명령으로 직접 실행하는 행위 자체를 운영체제 수준에서 금지했다는 주장은 하지 않는다.
- `PHASE-D-HISTORICAL-EXTERNAL-URL-DECOMMISSION` / **NOT_RUN** — 과거 외부 URL·DNS·Cloudflare 자원이 실제로 폐기되거나 접근 불가인지 외부 probe를 실행하지 않았다.
- `PHASE-D-CACHED-PWA-DEACTIVATION` / **NOT_RUN** — 과거에 설치됐거나 브라우저 캐시에 남은 PWA가 실제 사용자 기기에서 비활성화됐는지 확인하지 않았다.
- `PHASE-C-PRODUCTION-PROFILE-EMPTY` / **OPEN** — 운영 승인 지정 기기 프로필은 여전히 0개다.
- `PHASE-C-ACTUAL-DEVICE-NOT-RUN` / **NOT_RUN** — 실제 지정 휴대전화 검증을 실행하지 않았다.
- `PHASE-C-FORMAL-TESTS-NOT-RUN` / **NOT_RUN** — 정식 시험 279개는 모두 미실행이다.

정식 시험 **279/279 NOT_RUN**, 5개 gate **NOT_RUN·미면제**, 출시는 **NOT_ELIGIBLE**이다.

## 다음 한 가지 작업

`EPIC-01-NEXT-BFF-EXTRACTION` — 전환형 Next BFF 4개 route를 독립 Android API gateway로 추출하고 Android endpoint를 전환한다.

내용 지문: `d0a3bb442613b3a66ace4cbac00f624674c6c26d7e49b74742c160b1af139d79`
