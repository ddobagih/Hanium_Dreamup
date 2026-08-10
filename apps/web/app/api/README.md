# Web API Boundary

App Router route handler가 browser의 same-origin 요청을 FastAPI backend로 전달한다.

- `_gateway-auth.ts`: field/admin token을 검증하고 raw token 대신 HMAC 파생 HttpOnly session cookie를 관리
- `_backend.ts`: backend/voice base URL, field/admin gateway와 공통 proxy helper
- `field-session`, `admin-session`: HTTPS 브라우저가 입력한 token을 HttpOnly session으로 교환
- `detect/*`: v1·v2 detector와 health proxy
- `navigation/*`, `speech/*`: 도보 경로·목적지 검색과 STT same-origin proxy
- `reports/*`: 목록, 요약, 상태, 중복, export와 v2 생성 proxy
- `uploads/*`: backend upload 조회 proxy
- `walksafe-test-log`: 별도 enable/token/consent/size/retention gate가 있는 개발용 저장 endpoint

현장시험 token이 설정되면 보행 API는 field 또는 admin session, 목록·상태·export·upload는 admin session만 허용한다. raw token은 `NEXT_PUBLIC_*`에 두지 않는다. gateway는 임시 현장시험 보호이며 운영 인증/RBAC를 대체하지 않는다. 응답을 임의로 성공 처리하지 말고 status/body를 보존한다. `walksafe-test-log`를 운영 신고나 모델 데이터 자동 수집 경로로 재사용하지 않는다.
