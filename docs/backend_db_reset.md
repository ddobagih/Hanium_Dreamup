# Backend DB Reset

작성 기준일: 2026-05-12

## 목적

로컬 PostGIS 개발 DB를 초기화해야 할 때 사용할 절차를 정리한다. 이 문서는 로컬 개발 환경 기준이며, 운영 데이터에는 적용하지 않는다.

## 현재 DB 구성

| 항목 | 값 |
| --- | --- |
| Docker 서비스 | `db` |
| 컨테이너 | `walksafe-postgis` |
| 이미지 | `postgis/postgis:16-3.5` |
| DB | `walksafe` |
| 사용자 | `walksafe` |
| Docker volume | `walksafe-postgis-data` |

## 마이그레이션만 다시 적용

DB 볼륨을 유지하고 스키마만 최신으로 맞출 때 사용한다.

```bash
docker compose up -d db
source .venv/bin/activate
python -m alembic -c backend/alembic.ini upgrade head
```

## 신고 데이터만 비우기

DB 스키마와 Docker volume은 유지하고 `reports` 테이블 데이터만 삭제한다.

```bash
docker compose exec db psql -U walksafe -d walksafe -c "TRUNCATE reports"
```

업로드 이미지까지 비우려면 로컬 파일도 별도로 삭제한다.

```bash
find backend/uploads -type f ! -name .gitkeep -delete
```

## 완전 초기화

DB volume까지 삭제한다. 기존 로컬 DB 데이터가 모두 사라진다.

```bash
docker compose down -v
docker compose up -d db
source .venv/bin/activate
python -m alembic -c backend/alembic.ini upgrade head
```

업로드 이미지까지 맞춰 초기화하려면 다음도 실행한다.

```bash
find backend/uploads -type f ! -name .gitkeep -delete
```

## 초기화 후 확인

```bash
docker compose ps
python -m alembic -c backend/alembic.ini current
```

API 서버를 실행한 뒤 다음 응답을 확인한다.

```bash
curl http://127.0.0.1:8000/health
```
