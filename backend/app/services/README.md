# Backend Services

Router에서 분리된 탐지, 관리자 보안, 개인정보 lifecycle, 신고 저장과 외부 provider 규칙을 둔다. `api/`는 입력과 HTTP 오류를 변환하고 이 폴더의 service가 재사용 가능한 정책·원자적 저장 작업을 수행한다.

| 모듈 | 책임 |
|---|---|
| `actor_rate_limit.py` | worker·replica가 공유하는 PostgreSQL actor rate limit과 test/development memory 구현 |
| `admin_security.py` | 비밀번호·TOTP, 관리자 session, 재확인, 복구와 감사 정책 |
| `admin_device_proof.py` | P-256 device key 등록, single-use challenge와 요청 서명 검증 |
| `admin_report_workflow.py` | append-only 검토 결정과 수동 기관 전달 event 전이 |
| `detect_v2.py` | fake/YOLO provider 선택, unified 우선 및 legacy fallback runtime |
| `inference_process.py` | deadline과 재시작 경계를 가진 detector subprocess |
| `yolo_inference_adapter.py` | Ultralytics 결과를 정규화된 v2 detection으로 변환 |
| `report_policy.py` | v2 신고 허용 class/model/GPS 규칙 |
| `report_serialization.py` | DB report를 API response와 review flag로 변환 |
| `report_read_audit.py` | 신고·이미지 읽기의 actor/purpose 검증과 durable 감사 기록 |
| `duplicates.py` | PostGIS 반경 및 시간창 기반 중복 후보 조회 |
| `report_image_crypto.py` | 신고 이미지의 versioned AES-256-GCM envelope |
| `report_image_keys.py` | secret-file/KMS-agent keyring 로드와 회전 상태 검증 |
| `report_original_access.py` | 목적 제한·단일 사용 원본 access grant 발급과 소비 |
| `report_storage.py` | 암호화 이미지와 DB commit 경계의 journal·startup reconciliation |
| `privacy_lifecycle.py` | 가명화 동의, account generation fence와 계정 삭제 항목 상태 |
| `tmap_pedestrian.py` | TMAP POI/보행 경로 요청과 앱 schema 정규화 |
| `walking_route_sanity.py` | provider 응답의 endpoint·거리·geometry fail-closed 검사 |

## 주요 진입점

- `backend/app/main.py` 시작 시 privacy HMAC binding, report storage reconciliation과 detector lifecycle을 조립한다.
- `api/detect.py`는 `inference_process.py`와 `detect_v2.py`를 호출한다.
- `api/reports.py`, `api/uploads.py`는 report 정책·암호화·감사·저장 service를 사용한다.
- `api/admin_security.py`와 관리자 report route는 관리자 보안·device proof·workflow service를 사용한다.
- `api/privacy.py`는 `privacy_lifecycle.py`, `api/navigation.py`는 TMAP adapter와 route sanity 검사를 사용한다.

## 불변조건

- unified 탐지는 768 입력과 `unified_walksafe` 13-class order를 사용하며 readiness warmup에서 실제 checkpoint class map을 대조한다.
- legacy custom/COCO 탐지는 각 모델의 class id 공간을 유지한다.
- 일반 객체와 `tactile_damage_area`는 `/reports/v2` 저장 대상이 아니다.
- 외부 provider 응답은 그대로 노출하지 않고 `backend.app.schemas`의 앱 전용 schema로 변환한다.
- 관리자 고위험 mutation은 database-bound session, 재확인과 device proof 검증을 우회하지 않는다.
- 신고 원본은 평문 fallback 없이 암호화 envelope로 저장하며 key material을 DB, upload directory나 환경변수에 넣지 않는다.
- 계정 삭제 tombstone과 generation fence를 단순 DB 오류나 재시도로 완화하지 않는다.
- 중복 후보는 검토 신호일 뿐 자동 병합·기관 제출 결정이 아니다.

## 검증

저장소 root에서 전용 test PostGIS를 지정하고 변경한 service에 해당하는 `backend/tests/test_*.py`를 실행한다. 전체 backend 회귀 명령은 `backend/README.md`가 기준이다.

예를 들어 모델·경로 service의 DB 비의존 회귀는 다음과 같다.

```bash
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. \
  python3 -m pytest -p no:cacheprovider \
  backend/tests/test_detect_v2.py \
  backend/tests/test_yolo_inference_adapter.py \
  backend/tests/test_navigation_routes.py -q
```

단위 테스트 통과는 실제 모델 성능, TMAP 운영 연결, KMS, backup restore, 다중 replica 또는 현장 배포 완료를 뜻하지 않는다.
