# WalkSafe 애플리케이션

현재 제품은 서로 분리된 Android 사용자 앱과 Android 관리자 앱입니다. Web/PWA는 역사 참고와 410 경계 회귀만 보존하며 제품 구현·완료·출시 근거로 사용하지 않습니다.

| 경로 | 역할 | 상태 |
|---|---|---|
| [`android/app`](android/app) | 카메라·기기 내 TFLite·길안내·음성/진동·신고를 제공하는 사용자 앱 | `CURRENT_PRODUCT`, 정식 시험 전 |
| [`android/adminapp`](android/adminapp) | 관리자 인증·신고 검토·수동 기관 전달 사실 기록을 담당하는 별도 앱 | `CURRENT_PRODUCT`, debug 설치·cold start 확인, 사설 배포·관리자 실기능 E2E `NOT_RUN` |
| [`android-gateway`](android-gateway) | 세션·보행 원장은 로컬 종결하고, 길찾기·신고는 Backend에 중계하며, 동의·계정 삭제는 로컬 내구 상태와 Backend 동기화를 함께 적용 | `SUPPORT`, 운영 배포 `NOT_RUN` |
| [`web`](web) | 과거 Web/PWA UI·관리자·API와 all-request 410 경계 | `LEGACY_REFERENCE_ONLY` |

전체 요청 흐름과 수정 시 함께 볼 계약은 [코드 가이드](../docs/guides/code-guide.md), 제품 정책은 [승인 기준선](../docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json)을 따릅니다. Gateway 경로 수와 method는 고정 숫자 설명 대신 [OpenAPI](android-gateway/openapi.json)를 기준으로 봅니다.

Web 회귀 결과는 Android 기기·현장·출시 PASS로 사용할 수 없습니다.
