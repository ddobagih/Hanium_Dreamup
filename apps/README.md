# Applications

사용자와 운영자가 직접 실행하는 애플리케이션을 모은다.

| 경로 | 역할 | 현재 위치 |
|---|---|---|
| `android/app` | 가까운 위험·TMAP 큰 방향·손상 점자블록 신고를 제공하는 일반 사용자용 Android 앱 | 정식 제품 후보, 구현·검증 중 |
| `android/adminapp` | 신고 검수·기관 전달·감사 업무를 위한 별도 Android 관리자 앱 경계 | EPIC-01 관리자 인증·복구 구현 전까지 기능 잠금 |
| `android-gateway/` | 사용자 앱의 4개 API를 Backend에 안전하게 중계하는 독립 Gateway | 내부 구현·검증 완료, 배포·실기기 연결 `NOT_RUN` |
| `web/` | 과거 Web/PWA UI·관리자·API 참고 코드 | `LEGACY_REFERENCE_ONLY`, Next 런타임 전체 `410`, 외부 실행·정식 배포 금지 |

Web 회귀 결과를 Android Device·현장·출시 PASS로 사용하지 않는다. 현재 제품 경계는 `configs/walksafe_product_boundary_20260722.json`, 전체 정책은 `docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json`을 따른다.
