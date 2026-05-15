# 2026-05-16 Backend/PostGIS/API catch-up

범위: `backend/app`, `backend/tests`, reports/detect API, 업로드 검증, 중복 신고, DB 마이그레이션 backlog.

## 확인한 입력

- `plans/catchup/2026-05-16.md`
- `plans/daily/2026-05-15.md`
- `plans/.work/2026-05-16/catchup/backend-postgis-api.md`
- `daylog/2026-05-15.md`
- `README.md`
- 저장소 내부 `AGENTS.md`: 없음. 사용자 메시지의 AGENTS 지침을 적용.

## 수행한 작업

- `backend/tests/test_reports.py`의 report 생성 helper를 이미지 파일명/bytes/content-type을 받을 수 있게 확장했다.
- `/reports` read/list smoke 빈틈을 보강했다.
  - `GET /reports?limit=1`
  - `created_to` 미래/과거 필터
- `/reports` 성공 업로드 matrix를 보강했다.
  - JPEG
  - PNG
  - WebP
- 동일 신고의 상태 순차 전환 테스트를 추가했다.
  - `new -> reviewed -> resolved`
  - 최종 단건 조회에서 `resolved` 확인
- report upload negative smoke에 `empty_image` 오류 코드를 추가했다.

## 변경 파일

- `backend/tests/test_reports.py`
- `docs/execution/2026-05-16_backend_postgis_api.md`

## 검증

실행:

```bash
.venv/bin/python -m py_compile backend/tests/test_reports.py
```

결과: PASS.

실행:

```bash
timeout 120s .venv/bin/python -m pytest backend/tests/test_reports.py -q -rs
```

결과: `17 skipped in 0.30s`.

사유:

```text
PostGIS test database is not reachable: (psycopg.OperationalError) connection is bad: no error details available
```

추가 확인:

```bash
docker compose ps db
```

결과: 현재 codex sandbox에서 Docker socket 접근 권한이 없어 실패.

```text
permission denied while trying to connect to the docker API at unix:///var/run/docker.sock
```

전체 backend suite 확인 시도:

```bash
timeout 120s .venv/bin/python -m pytest backend/tests -q -rs
```

결과: timeout `124`.

분리 확인:

```bash
timeout 60s .venv/bin/python -m pytest backend/tests/test_detect.py -q -rs
timeout 20s .venv/bin/python -m pytest backend/tests/test_detect.py::test_detect_health_is_explicitly_unavailable -vv -s
```

결과: timeout `124`. 현재 sandbox에서는 단순 FastAPI `TestClient` 요청도 응답 없이 timeout된다. 이번 변경은 `test_reports.py`에 한정되며, reports 테스트는 PostGIS 접속 단계에서 skip되어 DB row/upload 파일은 새로 생성되지 않았다.

## 문서 상태 충돌 목록

최신 5/15 실행 근거와 충돌하거나 적용 범위 축소가 필요한 backend 관련 문서:

- `README.md`: PWA가 fake detector로 동작하고 모델 미구현 대체 시스템을 별도 관리한다고 설명한다. 기본/fake mode 설명으로는 유효하지만, 5/15 기준 server mode E2E와 `/detect` adapter ready smoke 근거를 함께 갱신해야 한다.
- `docs/current_status.md`: 백엔드 `/detect/health`를 placeholder로 설명한다. 모델 env 미설정 상태에는 유효하지만, `MODEL_ARTIFACT_PATH` 설정 시 ready 가능 상태를 반영해야 한다.
- `docs/pwa_backend_status.md`: `/detect/health`, `/detect`를 placeholder로 설명하고 `POST /detect`는 모델 연결 전 `503`이라고 설명한다. 모델 미설정 조건으로 범위를 좁혀야 한다.
- `docs/model_integration_plan.md`: `model_adapter_not_implemented` 전제를 둔 계획 문서다. 5/14 adapter 구현과 5/15 E2E 완료 링크를 추가해야 한다.
- `docs/frontend_handoff_without_model.md`: 모델 없는 handoff 문서로는 유효하지만, server detector mode가 추가됐으므로 적용 범위를 모델 env 미설정/미사용 시로 제한해야 한다.
- `docs/backend_3day_execution_plan.md`: `/detect` placeholder, adapter 미구현, 실제 추론 미수행을 현재 정상 상태로 설명하는 부분은 과거 계획 기준이다. 최신 상태 문서에서는 adapter 구현 완료와 model env 필요 조건을 반영해야 한다.

## DB migration backlog

이번 catch-up에서 새 migration은 만들지 않았다. 후속 PR 후보:

- `status`, `source`, `class_name` DB check constraint 또는 enum 도입.
- DB 직접 update에도 동작하는 `updated_at` trigger 도입.
- status 변경 이력 테이블 도입 여부 결정.
- `/reports`, `/uploads`, status patch의 인증/권한, rate limit, 외부 스토리지 정책 분리.

## 미완료/확인 필요

- PostGIS가 접근 가능한 세션에서 `backend/tests/test_reports.py`를 재실행해야 한다.
- FastAPI `TestClient` timeout은 현재 sandbox 제약으로 보이며, Docker/PostGIS 접근 가능한 일반 개발 세션에서 전체 `backend/tests`를 재검증해야 한다.
- `GET /reports?limit=1` 실제 HTTP smoke, JPEG/PNG/WebP 실제 DB row 생성, `created_to`, `new -> reviewed -> resolved`, `empty_image`는 테스트 코드로 보강했지만 이번 세션에서는 DB 접속 불가로 runtime PASS를 확인하지 못했다.
- daylog는 사용자 지시에 따라 수정하지 않았다. merge 에이전트가 최종 실행 note를 통합해야 한다.

## 병렬 에이전트 활용 메모

- 사용하지 않음.
- backend lane의 남은 작업이 단일 테스트 파일과 단일 실행 문서로 충분히 좁아 직접 처리했다.
