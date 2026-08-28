# Backend Migrations

PostgreSQL/PostGIS schema 변경을 관리한다.

- `env.py`는 `backend.app.models.Base` metadata와 migration 전용 DB 설정을 Alembic에 연결한다. development/test는 `DATABASE_URL` fallback을 허용하지만 field/staging/production은 repository `backend/.env`를 읽지 않고 `WALKSAFE_MIGRATION_DATABASE_URL`과 별도 `WALKSAFE_RUNTIME_DATABASE_ROLE`을 요구한다.
- `versions/`는 순서가 보존되어야 하는 migration revision을 둔다. 현행 단일 head는 `202608250002`이며, 전용 계정 삭제 워커의 최소 DB 권한과 서버 항목 완료 보호를 후속 revision으로 적용한다.
- 워커 로그인은 `walksafe_account_deletion_worker`의 직접 구성원이어야 하며 membership은 정확히 `WITH ADMIN FALSE, INHERIT TRUE, SET FALSE`로 부여한다. 기본 `SET TRUE`, 역할 재위임, runtime·receipt-purger 겸임, 추가 ACL은 시작 검사에서 거부한다.
- 현재 초기 revision은 PostGIS extension, `reports` 테이블, 일반 인덱스와 GiST 위치 인덱스를 만든다.

```bash
python -m alembic -c backend/alembic.ini upgrade head
python -m alembic -c backend/alembic.ini current
```

운영 DB에서 downgrade나 reset을 실행하지 않는다. 로컬 초기화 절차는 `docs/backend/backend_db_reset.md`를 따른다.
