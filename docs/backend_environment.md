# Backend Environment and CORS

작성 기준일: 2026-05-12

## 목적

FastAPI/PostGIS 백엔드 실행에 필요한 환경 변수와 CORS 설정을 한 곳에 정리한다. 실제 값은 `backend/.env`에 두고, 예시는 `backend/.env.example`을 기준으로 유지한다.

## 기본 실행 순서

```bash
docker compose up -d db
source .venv/bin/activate
python -m pip install -r backend/requirements.txt
cp backend/.env.example backend/.env
python -m alembic -c backend/alembic.ini upgrade head
python -m uvicorn backend.app.main:app --reload --port 8000
```

## 환경 변수

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `DATABASE_URL` | `postgresql+psycopg://walksafe:walksafe@localhost:5432/walksafe` | SQLAlchemy DB URL |
| `UPLOAD_DIR` | `backend/uploads` | 신고 이미지 저장 폴더 |
| `MAX_UPLOAD_BYTES` | `8388608` | 업로드 이미지 최대 크기 |
| `ALLOWED_IMAGE_CONTENT_TYPES` | `image/jpeg,image/png,image/webp` | 허용할 이미지 MIME |
| `MODEL_ARTIFACT_PATH` | 빈 값 | 실제 모델 파일 경로 |
| `CORS_ORIGINS` | `http://localhost:3000,http://127.0.0.1:3000` | 허용할 프론트엔드 origin 목록 |

## CORS 설정

`CORS_ORIGINS`는 쉼표로 구분한다.

```env
CORS_ORIGINS=http://localhost:3000,http://127.0.0.1:3000
```

외부 프론트엔드가 다른 포트에서 실행되면 해당 origin을 추가한다.

```env
CORS_ORIGINS=http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173
```

운영 또는 발표 환경에서는 `*` 대신 실제 프론트엔드 주소만 넣는다.

## 업로드 설정

현재 서버가 실제로 처리하는 MIME은 다음 3개다.

```text
image/jpeg
image/png
image/webp
```

`ALLOWED_IMAGE_CONTENT_TYPES`에 다른 MIME을 추가해도 코드에서 지원하지 않으면 업로드가 거부된다. HEIC, GIF 같은 형식을 받으려면 `backend/app/uploads.py`의 확장자, 저장 suffix, 파일 헤더 검증을 함께 추가해야 한다.

서버는 원본 파일명을 저장 파일명으로 사용하지 않는다. 원본 파일명은 확장자와 MIME의 명백한 불일치를 확인하는 데만 사용한다.

## 정적 이미지 경로

업로드된 이미지는 FastAPI에서 `/uploads`로 제공된다.

예:

```text
/uploads/0e6d9c2a-0c35-4c08-8f4b-2c0e2c9ef111.jpg
```

로컬 저장 위치를 바꾸려면 `UPLOAD_DIR`을 수정한다.
