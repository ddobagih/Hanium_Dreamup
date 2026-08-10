# Backend Tests

백엔드 테스트는 실행 조건이 다른 두 범주로 나뉜다.

## 순수 계약 테스트

탐지 adapter, 업로드 검증, TMAP navigation 정규화, 신고 정책과 Android debug API를 fake 객체 또는 임시 디렉터리로 검증한다. 실제 YOLO weight와 외부 TMAP 호출은 필요하지 않다.

## PostGIS 통합 테스트

`test_reports.py`와 `test_reports_v2.py`는 `WALKSAFE_TEST_DATABASE_URL`로 지정한 전용 PostGIS에 연결하고 Alembic migration을 적용한다. URL은 `postgresql+psycopg`를 사용하고 데이터베이스 이름에 `test`가 포함되어야 한다. DB가 없거나 접속할 수 없으면 skip하지 않고 실패한다.

```bash
docker compose up -d db
DATABASE_URL="$WALKSAFE_TEST_DATABASE_URL" .venv/bin/python -m alembic -c backend/alembic.ini upgrade head
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. WALKSAFE_TEST_DATABASE_URL="$WALKSAFE_TEST_DATABASE_URL" \
  .venv/bin/python -m pytest -p no:cacheprovider backend/tests -q
```

테스트 업로드는 세션마다 생성되는 임시 디렉터리를 사용한다. 실제 운영 upload 디렉터리와 혼용하지 않는다.
