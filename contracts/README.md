# WalkSafe HTTP contracts

`walksafe.openapi.json` is generated from the backend routes and the same role/actor policy used by the authorization middleware. Its administrator operations deliberately use the deployment-shape database-backed Bearer session plus the four Android context headers; fixture credentials used while rendering are not runtime credentials. `fixtures/walking-route-v1.json` is serialized through the backend Pydantic request/response models and is consumed by both client parser tests.

Administrator delivery recovery contracts are currently anchored on `walksafe.admin-report-detail.v2`, JSON `POST /admin/reports/{report_id}/delivery-packages` with exact content/review CAS, and `GET /admin/reports/{report_id}/delivery-packages/{package_revision}/proof` for minimal v2 package recovery metadata only.

Regenerate and verify it with:

```bash
PYTHONPATH=. python scripts/generate_walksafe_openapi.py
PYTHONPATH=. python scripts/generate_walksafe_openapi.py --check
```

Client contract tests must consume examples derived from this schema or backend models instead of maintaining an independent payload shape.
