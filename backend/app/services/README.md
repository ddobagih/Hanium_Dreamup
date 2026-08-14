# Backend Services

Router에서 분리된 탐지, 관리자 보안, 개인정보 lifecycle, 신고 저장과 외부 provider 규칙을 둔다. `api/`는 입력과 HTTP 오류를 변환하고 이 폴더의 service가 재사용 가능한 정책·원자적 저장 작업을 수행한다.

| 모듈 | 책임 |
|---|---|
| `actor_rate_limit.py` | worker·replica가 공유하는 PostgreSQL actor rate limit과 test/development memory 구현 |
| `admin_security.py` | singleton 관리자 control, 비밀번호·TOTP, 독립 issuer key DB 결속, session, 재확인, 복구·custody·분실 기기 폐기와 감사 정책 |
| `admin_credential_issuer_key.py` | root/service 소유 private issuer-key 파일의 경로·mode·link·identity·canonical 형식 검증 |
| `admin_device_proof.py` | P-256 device key 등록, single-use challenge, 요청 서명과 폐기 키 재사용 거부 |
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
- 관리자 고위험 mutation은 database-bound session과 재확인을 우회하지 않으며, custody·분실 신고처럼 route-scoped device proof가 지정된 작업은 요청 서명도 우회하지 않는다.
- DB check·unique는 관리자 control을 최대 한 행으로 제한하고, trusted provisioning과 보호 작업은 정확히 한 행을 요구한다. 0행·복수행 또는 custody 상태·시각·참조 digest·자료 종류·저장 위치·별도 백업 확인의 불일치는 store unavailable로 fail-closed 한다.
- custody service는 canonical 32바이트 opaque 참조를 검증한 뒤 SHA-256과 최소 metadata·서버 시각만 저장·감사하며 원문 복구자료·opaque 참조를 남기지 않는다.
- `walksafe_backend_runtime` DB 자격증명만으로는 private custody capability를 읽거나 바꿀 수 없고 session·재확인·복구 credential row를 직접 생성하거나 identity·token·expiry를 다시 쓸 수도 없다. 정상 credential 발급·복구 전이는 애플리케이션 TOTP seed를 private capability에 대조하는 `SECURITY DEFINER` 함수만 사용하고, session 갱신은 단조 `last_seen_at`, step-up 소거, 일회성 revoke로 제한한다. offline gate의 최초 소비와 fence 내부 재검증도 같은 private capability를 확인한다. seed는 DB 사용자·일반 SQL 실행 주체와 분리해 주입하며 SQL bind parameter 로깅은 비활성화하거나 반드시 마스킹한다.
- session·재확인·복구·custody·분실 기기 mutation용 `SECURITY DEFINER` 함수는 TOTP뿐 아니라 private capability의 issuer-key SHA-256도 일치해야 실행된다. 기존 control의 최초 issuer 결속은 owner-only 함수와 CLI로만 가능하며 같은 키에만 멱등이다. readiness는 probe마다 파일을 다시 읽고 startup은 불일치 시 serving 전에 실패한다.
- 이 경계는 DB 런타임 자격증명 단독 탈취를 방어한다. 애플리케이션 프로세스와 TOTP seed가 함께 탈취된 경우는 이 경계 밖이며 별도 secret rotation·incident response가 필요하다.
- DB constraint trigger는 custody column이 바뀐 transaction에 상태에 맞는 성공 audit가 없으면 commit을 거부한다.
- 장치 증명이 켜진 복구 시작은 대상 `device_id`의 기존 active 신뢰 기기키가 정확히 하나일 때 그 키만 보존하고, 다른 active device key와 모든 관리자 session을 폐기한 뒤 custody를 `UNATTESTED`로 초기화한다. 복구 중 프로비저닝은 같은 recovery advisory lock 아래 active transaction의 대상 기기만 허용한다. 복구 완료는 보존된 대상 키의 request-bound proof를 요구하며, custody가 다시 `ATTESTED`가 되기 전에는 출시 승인·권한 변경·데이터 삭제를 포함한 고위험 gate를 통과시키지 않는다.
- 분실 기기 신고는 현재 호출 기기를 거부하고 대상 기기의 active 공개키와 모든 미폐기 session을 하나의 transaction에서 폐기·감사한다.
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

단위 테스트 통과는 실제 모델 성능, TMAP 운영 연결, KMS, backup restore, 다중 replica 또는 현장 배포 완료를 뜻하지 않는다. 실제 관리자 기기·custody·복구훈련·독립 검토·정식 시험과 release gate는 모두 `NOT_RUN`(미면제), 출시는 `NOT_ELIGIBLE` 경계를 유지한다.
