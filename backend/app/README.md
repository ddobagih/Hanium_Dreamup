# Backend Application

백엔드 애플리케이션의 조립 지점과 공유 domain 계약을 둔다.

## 파일 역할

| 파일/폴더 | 역할 |
|---|---|
| `main.py` | FastAPI, CORS, router 조립 |
| `config.py` | `.env` 및 환경 변수 파싱 |
| `schemas.py` | HTTP 요청/응답 Pydantic schema |
| `models.py` | SQLAlchemy `reports` model |
| `database.py` | engine, session dependency |
| `uploads.py` | 이미지 MIME, 확장자, signature, 크기, EXIF 처리 |
| `detector.py` | legacy v1 YOLO `.pt` adapter |
| `field_test_security.py` | 기본 ON·fail-closed인 field/admin 분리 route middleware |
| `api/` | endpoint 계층 |
| `services/` | 재사용 가능한 정책과 provider 계층 |

## 요청 흐름

```text
HTTP request
  -> api router: parse/validate and map errors
  -> service or detector: policy/provider work
  -> database/filesystem when persistence is required
  -> Pydantic response
```

v1과 v2는 같은 `reports` 테이블을 사용하지만 class id 의미와 허용 정책이 다르다. v2 `model_class_id`를 v1 전역 class id로 해석하지 않는다.

호환용 legacy `POST /reports`는 server가 `manual_legacy`, `legacy_v1_unverified`, `performance_excluded=true`로 재분류하여 성능·기관 export 근거에서 제외한다. detect health의 model path는 절대경로가 아닌 파일명만 반환한다.

field와 admin 권한은 서로 대체할 수 없는 분리 role이다. field는 detect/navigation/report 생성·중복 ID 요약만, admin은 목록·상태·export·upload·health를 사용한다. 보안은 기본 ON이고 field/staging/production에서는 비활성화할 수 없으며, 서로 다른 24자 이상 token이 없으면 시작을 거부한다. 명시적 로컬 개발에서만 비활성화할 수 있다.

Next gateway는 계정별 actor ID를 포함한 HttpOnly session을 발급하고, 짧은 수명의 HMAC actor assertion과 함께 backend에 전달한다. 다만 이 구성은 현장·staging용 운영 경계이며, 조직 IdP·중앙 RBAC·다중 호스트 audit 저장소를 대신하지는 않는다.
