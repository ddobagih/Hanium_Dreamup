# Backend API Routers

이 폴더는 HTTP transport 계층을 담당한다. 요청 parsing, dependency 연결, status code와 오류 payload 변환까지만 처리하고 탐지·중복·provider 규칙은 `../services/`에 둔다.

| 모듈 | endpoint 영역 |
|---|---|
| `health.py` | 프로세스 health |
| `detect.py` | v1/v2 이미지 탐지 |
| `navigation.py` | 목적지 검색과 보행 경로 |
| `reports.py` | 신고 생성, 조회, 검수 상태, 요약, export |
| `uploads.py` | 저장된 신고 이미지 조회 |
| `android_debug.py` | local/dev Android depth log와 frame capture |

API router 앞의 `FieldTestSecurityMiddleware`가 역할을 분리한다. 신고 생성·탐지·길안내·일반 readiness는 field 세션에, `/reports` 조회·상태 변경·export·`/uploads/*`·`/android/debug/*`는 admin 세션에만 허용된다. actor가 필요한 작업은 gateway가 서명한 짧은 수명의 actor assertion도 검증한다. 알 수 없는 신규 route는 보안 기능이 켜진 환경에서 admin 전용으로 fail-closed 된다.

field/staging/production의 actor rate limit은 PostgreSQL transaction advisory lock과 짧은 수명의 event table을 사용해 worker·replica 사이에서 원자적으로 공유한다. DB accounting이 실패하면 해당 요청은 `503`으로 fail-closed 된다. `memory` 저장소는 development/test에서만 허용된다. 다중 replica 배포에서는 PostgreSQL뿐 아니라 `UPLOAD_DIR`도 모든 replica가 같은 내구성 저장소를 보도록 구성해야 한다. Android debug router는 `ANDROID_DEBUG_LOG_ENABLED=false`가 기본이다.
