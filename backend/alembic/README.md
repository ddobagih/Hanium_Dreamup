# Backend Migrations

PostgreSQL/PostGIS schema 변경을 관리한다.

- `env.py`는 `backend.app.models.Base` metadata와 migration 전용 DB 설정을 Alembic에 연결한다. development/test는 `DATABASE_URL` fallback을 허용하지만 field/staging/production은 repository `backend/.env`를 읽지 않고 `WALKSAFE_MIGRATION_DATABASE_URL`과 별도 `WALKSAFE_RUNTIME_DATABASE_ROLE`을 요구한다.
- `versions/`는 순서가 보존되어야 하는 migration revision을 둔다. 현행 단일 head는 `202608150002`이며, 적용된 `202608120001`을 수정하지 않고 후속 revision에서 관리자 device-proof challenge UPDATE를 `consumed_at` 단일 컬럼으로 제한하고 복구 후보 TOTP startup·만료 proof·정리를 결속한다.
- 현재 초기 revision은 PostGIS extension, `reports` 테이블, 일반 인덱스와 GiST 위치 인덱스를 만든다.

```bash
python -m alembic -c backend/alembic.ini upgrade head
python -m alembic -c backend/alembic.ini current
```

운영 DB에서 downgrade나 reset을 실행하지 않는다. 로컬 초기화 절차는 `docs/backend/backend_db_reset.md`를 따른다.
