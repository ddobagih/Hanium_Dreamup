# WalkSafe HTTP contracts

`walksafe.openapi.json` is generated from the backend routes and the same role/actor policy used by the authorization middleware. Its administrator operations deliberately use the deployment-shape database-backed Bearer session plus the four Android context headers; fixture credentials used while rendering are not runtime credentials. `fixtures/walking-route-v1.json` is serialized through the backend Pydantic request/response models and is consumed by both client parser tests.

Administrator delivery recovery contracts are currently anchored on `walksafe.admin-report-detail.v2`, JSON `POST /admin/reports/{report_id}/delivery-packages` with exact content/review CAS, and `GET /admin/reports/{report_id}/delivery-packages/{package_revision}/proof` for minimal v2 package recovery metadata only.

Regenerate and verify it with:

```bash
PYTHONPATH=. python scripts/generate_walksafe_openapi.py
PYTHONPATH=. python scripts/generate_walksafe_openapi.py --check
```

Client contract tests must consume examples derived from this schema or backend models instead of maintaining an independent payload shape.

가입 사전 조회는 `GET /account-signup-policy`, 설치 삭제 증거 키 등록은 `POST /privacy/installation-evidence-key`로 제공한다. 키 등록 본문은 `installation_id`와 canonical Base64url P-256 SPKI DER `public_key`만 허용하고 성공 응답은 204다. 키 등록과 기본 삭제 영수증, 요청 시 선택하는 `device-verification.v1` 조회는 서로 다른 계약이며 동적인 기기 조회를 canonical 영속 본문에 넣지 않는다. 이메일 공통 입력은 `test-vectors/account-email.json`의 53개 벡터를 사용하고 Backend는 Gateway가 정규화한 ASCII wire 형식만 받는다.
