# EPIC-01 Phase E 독립 Android API Gateway 추출 기록

- 문서 ID: `WS-EPIC-01-PHASE-E-ANDROID-GATEWAY-IMPLEMENTATION-20260723-001`
- 버전: `0.1.0`
- 상태: `INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS`
- EPIC: `EPIC-01 IN_PROGRESS`
- 통제 구현 경로: **41개**
- 제거 확인 Next route: **4개**

## 내부 구현한 경계

- **INDEPENDENT_NODE_ANDROID_GATEWAY** — 정확한 4개 Android API 경로를 담당하는 Node 22 단일 프로세스 Gateway를 Next와 분리했고, 미등록 경로는 404 no-store로 닫는다.
- **ANDROID_ENDPOINT_CUTOVER** — Android debug 기본 origin을 127.0.0.1:8081로 전환했고 release는 승인된 정확한 HTTPS root origin 하나만 허용한다.
- **LEGACY_WEB_ZERO_RUNTIME_ALLOWLIST** — Legacy Web runtime 허용목록을 0개로 만들고 과거 Next route 4개를 제거했으며 모든 Legacy runtime 요청을 410으로 닫는다.
- **CI_AND_DRAFT_DEPLOYMENT_CONTRACT** — CI에 gateway 회귀를 연결하고 loopback 단일 프로세스·정확한 4경로 TLS ingress 배치 예시를 만들었지만 실제 적용·배포 증거로 주장하지 않는다.

## 독립 Gateway 공개 경로

- `/api/field-session`
- `/api/navigation/walking`
- `/api/navigation/destinations/search`
- `/api/reports/v2`

Legacy Web runtime allowlist는 **0개**이고 Next/Web fallback은 없다. Android debug 기본 origin은 `http://127.0.0.1:8081`, release는 승인된 정확한 HTTPS root origin 하나다.

## 주장하지 않는 것

배포 예시는 적용하지 않았다. 실제 설치·외부 TLS·운영 계정·실기기 연결·과거 외부 URL 폐기·캐시 PWA 비활성·정식 시험 완료를 주장하지 않는다.

## 공개 미결사항

- `EPIC-01-PURPOSE-SURFACES` / **OPEN_NEXT** — 첫 화면·동의·사용자 안내·출시 설명에 승인된 제품 목적과 안전 한계를 일관되게 표시해야 한다.
- `EPIC-01-NO-DESTINATION-HAZARD-CONFORMANCE` / **OPEN** — 목적지 미설정 상태의 위험 안내 경계와 사용자 흐름을 승인 정책에 맞춰야 한다.
- `PHASE-E-GATEWAY-DEPLOYMENT` / **NOT_RUN** — 배포 파일은 예시뿐이며 Gateway 설치·서비스 시작·TLS ingress 반영·smoke test를 하지 않았다.
- `PHASE-E-ACTUAL-DEVICE-CONNECTIVITY` / **NOT_RUN** — 실제 Android 기기에서 외부 HTTPS Gateway와 네 경로 연결을 검증하지 않았다.
- `PHASE-D-HISTORICAL-EXTERNAL-URL-DECOMMISSION` / **NOT_RUN** — 과거 외부 URL·DNS·Cloudflare 자원의 실제 폐기 또는 접근불가를 확인하지 않았다.
- `PHASE-D-CACHED-PWA-DEACTIVATION` / **NOT_RUN** — 기존 설치·브라우저 캐시 PWA의 실제 사용자 기기 비활성 여부를 확인하지 않았다.
- `PHASE-E-FORMAL-TESTS` / **NOT_RUN** — 정식 시험 279개는 모두 미실행이다.

정식 시험 **279/279 NOT_RUN**, 5개 gate **NOT_RUN·미면제**, 출시는 **NOT_ELIGIBLE**이다.

## 다음 한 가지 작업

`EPIC-01-PURPOSE-SURFACES` — Android 첫 화면·동의·사용설명·릴리스 설명의 목적문과 안전 한계를 승인 정책에 맞게 구현·정합화한다.

내용 지문: `ae9e65a2ab75f6de267a3406c32eb91d5c8e81fdc251f7a96d8d5be3b0009257`
