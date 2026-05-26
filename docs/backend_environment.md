# Backend Environment and CORS

작성 기준일: 2026-05-24

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
| `MODEL_VERSION` | 빈 값 | v1 `/detect` model version 표시 |
| `MODEL_CLASS_ORDER` | `damaged_tactile_block,parked_kickboard_bicycle,construction_obstacle,pothole` | v1 `/detect` class order |
| `MODEL_CONFIDENCE_THRESHOLD` | `0.35` | v1 `/detect` confidence threshold |
| `MODEL_IOU_THRESHOLD` | `0.7` | v1 `/detect` NMS IoU threshold |
| `MODEL_IMAGE_SIZE` | `640` | v1 `/detect` image size |
| `DETECT_V2_MODE` | `fake` | v2 provider mode: `fake`, `yolo`, `real` |
| `DETECT_V2_CUSTOM_TACTILE_MODEL_PATH` | 빈 값 | v2 custom tactile YOLO checkpoint |
| `DETECT_V2_COCO_MODEL_PATH` | 빈 값 | v2 COCO helper model |
| `DETECT_V2_RUNTIME_CONFIG_PATH` | 빈 값 | v2 threshold/runtime config |
| `WALKING_ROUTE_PROVIDER` | `tmap_pedestrian` | 보행 route provider. `tmap_pedestrian` 또는 `kakao_mobility` |
| `TMAP_APP_KEY` | 빈 값 | TMAP 보행자 경로안내 appKey. Git에 커밋하지 않는다 |
| `TMAP_PEDESTRIAN_ROUTE_URL` | `https://apis.openapi.sk.com/tmap/routes/pedestrian` | TMAP 보행자 경로안내 endpoint |
| `TMAP_PEDESTRIAN_API_VERSION` | `1` | TMAP 보행자 경로안내 API version query |
| `TMAP_TIMEOUT_SECONDS` | `4.0` | TMAP 보행자 경로안내 요청 timeout |
| `TMAP_PEDESTRIAN_SPEED_KMH` | `4.0` | TMAP 보행자 경로 요청 기본 보행 속도(km/h) |
| `KAKAO_MOBILITY_REST_API_KEY` | 빈 값 | Kakao Mobility Walking Directions API REST key. fallback용, Git에 커밋하지 않는다 |
| `KAKAO_MOBILITY_WALKING_DIRECTIONS_URL` | `https://apis-navi.kakaomobility.com/affiliate/walking/v1/directions` | Kakao 보행 길찾기 endpoint |
| `KAKAO_MOBILITY_SERVICE_NAME` | `walksafe` | Kakao 요청 `service` header 값 |
| `KAKAO_MOBILITY_TIMEOUT_SECONDS` | `4.0` | Kakao 보행 길찾기 요청 timeout |
| `CORS_ORIGINS` | `http://localhost:3000,http://127.0.0.1:3000` | 허용할 프론트엔드 origin 목록 |

## TMAP 보행 길안내 설정

`/navigation/walking`은 기본적으로 서버가 TMAP 보행자 경로안내 API를 호출하는 proxy endpoint다. TMAP appKey는 백엔드 `.env`에만 둔다.

```env
WALKING_ROUTE_PROVIDER=tmap_pedestrian
TMAP_APP_KEY=티맵_APP_KEY
```

실제 provider 연결 smoke:

```bash
python3 scripts/check_tmap_pedestrian_route_smoke_20260524.py
```

Kakao Mobility 권한/제휴가 확보되면 fallback provider로 전환할 수 있다.

```env
WALKING_ROUTE_PROVIDER=kakao_mobility
KAKAO_MOBILITY_REST_API_KEY=카카오_REST_API_KEY
KAKAO_MOBILITY_SERVICE_NAME=walksafe
```

프론트 테스트용 목적지 좌표는 `apps/web/.env.local` 같은 프론트 env에 둔다. 이 값은 브라우저 번들에 포함되므로 secret을 넣지 않는다.

```env
NEXT_PUBLIC_WALKSAFE_DESTINATION_LAT=
NEXT_PUBLIC_WALKSAFE_DESTINATION_LNG=
NEXT_PUBLIC_WALKSAFE_DESTINATION_NAME=
```

## v2 Stage1 후보 로컬 예시

2026-05-23 KST 기준 reviewed YOLO26s MVP/backend integration 후보는 Stage1 `best.pt`다.

```env
DETECT_V2_MODE=yolo
DETECT_V2_CUSTOM_TACTILE_MODEL_PATH=/home/ddobagi/Code/hanium-dreamup/runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt
DETECT_V2_COCO_MODEL_PATH=/home/ddobagi/Code/hanium-dreamup/yolo26n.pt
DETECT_V2_RUNTIME_CONFIG_PATH=/home/ddobagi/Code/hanium-dreamup/configs/walksafe_two_model_runtime_stage1_mvp_20260523.json
```

경로/config readiness만 확인하려면 아래 health-only smoke를 사용한다.

```bash
bash scripts/check_detect_v2_stage1_candidate_health_20260523.sh
```

이 smoke는 YOLO weight를 로드하거나 inference를 실행하지 않는다.

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
