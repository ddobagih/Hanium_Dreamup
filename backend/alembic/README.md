# Backend Migrations

PostgreSQL/PostGIS schema 변경을 관리한다.

- `env.py`는 `backend.app.models.Base` metadata와 `DATABASE_URL`을 Alembic에 연결한다.
- `versions/`는 순서가 보존되어야 하는 migration revision을 둔다.
- 현재 초기 revision은 PostGIS extension, `reports` 테이블, 일반 인덱스와 GiST 위치 인덱스를 만든다.

```bash
python -m alembic -c backend/alembic.ini upgrade head
python -m alembic -c backend/alembic.ini current
```

운영 DB에서 downgrade나 reset을 실행하지 않는다. 로컬 초기화 절차는 `docs/backend/backend_db_reset.md`를 따른다.
