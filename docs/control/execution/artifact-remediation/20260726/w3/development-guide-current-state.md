# W3 개발 가이드 현재 상태 후속 보충본

- 문서 ID: `WS-ARTIFACT-REMEDIATION-W3-DEVELOPMENT-GUIDE-CURRENT-STATE-20260726-001`
- 성격: 기존 정본을 바꾸지 않는 `ADD_ONLY_SUCCESSOR_SUPPLEMENT`
- 대상: `DLV-DEV-02`, `DLV-DEV-03`, `DLV-DEV-04`, `DLV-DEV-05`, `DLV-DEV-06`, `DLV-DEV-08`
- source commit: `null` (현재 dirty-worktree는 아래 exact path/SHA로 결속)
- W3 실행: `PASS_INTERNAL_CURRENT_SOURCE_WITH_EXTERNAL_BOUNDARIES`
- 정식시험: `279/279 NOT_RUN`
- 출시 gate: `5/5 NOT_RUN`, `5/5 unwaived`
- 출시: `NOT_ELIGIBLE`
- JSON 내용 지문: `5eddc7f8fbb84ff02caf9b93773dd07be42c5d78e0cac2da325c33f6278a3072`

## 판독·승인 경계

기존 `docs/deliverables/05-implementation/developer-guide.md`는 0.1.0 Draft 문구와 오래된 모듈 지도를 유지한다. artifact register의 일부 후속 상태와 이 문구가 충돌하므로, 현재 DEV-02/03/04/05/06/08 내용은 이 보충본을 우선 판독한다. 이는 승인·기준선·상태를 바꾸지 않으며 W1/W2 successor와 2026-07-21/22 불변 기록을 수정하지 않는다.

## 현행 모듈 맵

| 모듈 | 위치 | 실제 책임 | 아직 주장하지 않는 것 |
|---|---|---|---|
| Android 사용자 앱 `:app` | `apps/android/app` | 보행 세션, 단말 카메라·센서·TFLite, 음성·진동·UI, 통제 연동 | 실기기·센서·성능·현장 PASS |
| Android 관리자 앱 `:adminapp` | `apps/android/adminapp` | PASSWORD+TOTP, 복구·세션 폐기, 고위험 재인증, API origin | 실제 관리자 단말·분실복구·production PASS |
| Android Gateway | `apps/android-gateway` | 공개 API, field 세션, 동의·개인정보, backend proxy, 원장 잠금 | install/build/start/test·production PASS |
| FastAPI Backend | `backend` | 보안 API, TMAP 중계, 신고, PostGIS, 서버 추론 adapter | 외부 provider·동시성·production PASS |
| PostgreSQL/PostGIS | `docker-compose.yml`, `backend/alembic` | 공간/보안/감사 persistence와 migration | 이미지 cache·migration·restore PASS |
| 모델·설정 | `model`, `configs`, Android assets | 후보 registry, TFLite/config identity, local promotion·rollback | 모델 승인·독립평가·기기 성능·배포 적격 |

`apps/web`은 현행 Android 제품 모듈이 아니라 `LEGACY_REFERENCE_ONLY`이다. 모델 상태는 오래된 README 문구보다 exact asset·registry·runtime config를 우선하되, registry의 `deployment_eligible=false` 차단을 유지한다.

## 지원 도구체인과 정확한 경계

| 범위 | 저장소 고정값 | 설치 확인 |
|---|---|---|
| Node/Gateway | Node `v22.23.1`, npm `10.9.8`, engine `>=22 <23`, TypeScript `6.0.3` | Gateway build 내부 PASS. 관측 `v22.22.1`/npm `9.2.0`은 exact lock과 다름 |
| Android | Gradle `9.3.1`, AGP `9.1.0`, JVM `21`, SDK `36/36/26`, Kotlin stdlib `2.2.10` | 내부 assemble/lint PASS; 실기기·formal은 `NOT_RUN` |
| Backend | FastAPI `0.128.8`, Uvicorn `0.39.0`, SQLAlchemy `2.0.49`, psycopg `3.2.13`, pytest `8.4.2` | compile·DB-free 33·PostGIS 연속 2회 내부 PASS; Python 지원범위 미고정 |
| Model | Ultralytics `8.4.48`, ONNX `1.21.0`, ONNX Runtime `1.26.0` | compile 내부 PASS; 품질·독립평가·Python 지원범위 미완료 |
| DB | `postgis/postgis:16-3.5` digest 고정 | fresh migration/schema/동일 DB 연속 2회 내부 PASS; production·Compose CLI 고정 미완료 |

submission Python `3.14.4`/pip `26.1.1` lock은 산출물 도구용이며 backend/model runtime 지원 근거로 사용하지 않는다.

## Offline-first bootstrap·run·test·cleanup

완전한 최초 오프라인 bootstrap은 `NOT_ESTABLISHED`다. npm/pip/Docker 최초 설치에는 통제된 network 또는 사전 cache/wheel/image가 필요할 수 있다. 설치 후 lock을 확인하고 Android의 명시된 `--offline` 명령을 사용한다. 아래는 저장소에 존재하는 경로와 script만 가리킨다. 최종 W3 근거는 이 가운데 명명된 내부 build/lint/typecheck/compile/migration/integration 범위를 실행하거나 exact unchanged source 결과로 재사용했다. 모든 행·완전 오프라인 bootstrap·exact locked toolchain이 실행된 것은 아니다.

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r backend/requirements.lock
cp backend/.env.example backend/.env
docker compose up -d db
python -m alembic -c backend/alembic.ini upgrade head
PYTHONPATH=. python -m uvicorn backend.app.main:app --reload --port 8000
```

```bash
cd apps/android-gateway && npm ci
cd apps/android-gateway && npm run build
cd apps/android-gateway && npm start
cd apps/android-gateway && npm run typecheck
cd apps/android-gateway && npm test
```

```bash
cd apps/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon
cd apps/android && ./gradlew :adminapp:testDebugUnitTest :adminapp:assembleDebug :adminapp:lintDebug --offline --no-daemon
```

```bash
scripts/run_walksafe_test_layers_20260711.sh validate
scripts/run_walksafe_test_layers_20260711.sh unit
scripts/run_walksafe_test_layers_20260711.sh functional
scripts/run_walksafe_test_layers_20260711.sh integration
scripts/run_walksafe_test_layers_20260711.sh active-session-control
scripts/run_walksafe_test_layers_20260711.sh all
```

`functional`, `integration`, `all`은 이름만 기록한 `WALKSAFE_TEST_DATABASE_URL`이 필요하고 Node 경로는 `WALKSAFE_NODE_BIN_DIR`에 결속한다. cleanup은 foreground server의 orderly interrupt, `docker compose stop db`, `./gradlew --stop`, 증거 결속 뒤 `npm run clean` 순서다. DB volume·build report·backup·과거 증거는 routine cleanup에서 삭제하지 않는다.

## 최종 W3 내부 증거

- `evidence-20260726-003`: Android user/admin assemble, Gateway build, module/lock/fixture/source snapshot, SBOM과 provenance를 exact dirty-worktree content에 결속했다.
- `aux-execution-20260726-005`: Android lint, Gateway typecheck, backend/model compile, SPDX 2.3·CycloneDX 1.6 local official-schema 검증이 내부 PASS다. license 결과는 metadata inventory이며 법률 승인이 아니다.
- `integration-run-20260726-004`: backend DB-free 33건, fresh PostGIS migration/schema parity, 동일 single-use DB exact PostgreSQL node 연속 2회, cleanup/repeatability가 내부 PASS다. Android user 728·admin 38·Gateway 62 결과는 unchanged exact source로 재사용했다.
- 내부 command receipt는 11개, 실패는 0이다. 이는 formal 279, 실기기, Android→Gateway→Backend→PostGIS cross-process, 실제 network/TMAP, signing, deploy·production·release PASS가 아니다.
- 관측 Gateway Node `v22.22.1`/npm `9.2.0`과 evidence Python `3.14.6`은 각각 별도 exact lock 값과 다르므로 toolchain closure는 열어 둔다.

## 환경변수와 secret

이 문서는 변수 이름만 기록하고 값은 포함하지 않는다. 전체 이름 목록과 모듈별 분류는 JSON의 `environment_and_secret_contract.catalog`에 있다.

- secret 취급 이름: `DATABASE_URL`, `POSTGRES_PASSWORD`, `TMAP_APP_KEY`, `WALKSAFE_ADMIN_TOKEN`, `WALKSAFE_ADMIN_TOTP_SECRET`, `WALKSAFE_FIELD_TEST_TOKEN`, `WALKSAFE_GATEWAY_SESSION_SECRET`
- 실제 `.env`, credential, token, TOTP seed, password는 문서·fixture·manifest·lock·screenshot·Git에 저장하지 않는다.
- server/provider secret을 Android BuildConfig, APK/AAB asset 또는 source에 내장하지 않는다.
- log/evidence에는 authorization, 정확 위치, 원본 image/audio, password, TOTP, recovery code, token, raw exception을 남기지 않는다.
- 노출 의심 시 먼저 폐기·회전하고 비밀값 없는 새 incident/review record를 append한다.

## 언어별 규칙

- Kotlin: JVM 21/Android 설정을 따르고 보행 session과 coroutine/camera/sensor/location/upload 생명주기를 묶는다. UNKNOWN·누락 상태는 성공으로 추정하지 않는다.
- Java: 관리자 auth/recovery/high-risk 상태 전이를 명시하고 기본 거부한다. ID·password·TOTP·복구코드·token·device identifier·raw exception을 log하지 않는다.
- TypeScript: Node 22 범위와 TS 6.0.3 계약을 따르고 parsing/auth/privacy/proxy/persistence를 typed·fail-closed로 둔다. lock은 보호 mutation 전체 수명 동안 유지한다.
- Python: 4칸 들여쓰기, 명시적 경계 type, 짧은 함수를 사용한다. FastAPI validation/service/SQLAlchemy transaction을 분리하고 적용 migration은 수정하지 않고 새 Alembic revision으로 append한다.

Android lint, Gateway typecheck, backend/model compile과 명명된 내부 build·integration 결과만 최종 W3 영수증 범위에서 PASS다. formatter, formal 279, 실기기·cross-process·production 범위는 PASS로 주장하지 않는다.

## Append-only review와 W1/W2 successor

- 실행·review마다 새 record를 만들고 과거 결과·실패·reviewer 결정을 덮어쓰지 않는다.
- subject path/SHA, command, 환경 등급, 시각, 결과, finding, 미해결 위험을 기록한다.
- 1인 self-review는 `SELF_REVIEW`로 쓰며 독립·외부 검토 gate를 닫지 않는다.
- W1 compact decision/requirements와 W2 architecture/design/interface successor는 각 범위에서 predecessor보다 우선하지만 승인·기준선 변경이나 downstream gap 종료를 뜻하지 않는다.
- integration local validation은 내부 PASS지만 W3 개발 가이드 최종 독립검토와 전체 wave status delta는 아직 완료 근거로 결속하지 않았다.

## 산출물별 단일 disposition

| 산출물 | 현재 내용 | 단일 disposition의 open gap |
|---|---|---|
| `DLV-DEV-02` | `CURRENT_STATE_CONTENT_WITH_FINAL_W3_INTERNAL_EVIDENCE` | `W3-DEV-GUIDE-GAP-001`, `W3-DEV-GUIDE-GAP-006` |
| `DLV-DEV-03` | `CURRENT_STATE_CONTENT_WITH_FINAL_W3_INTERNAL_EVIDENCE` | `W3-DEV-GUIDE-GAP-001`, `W3-DEV-GUIDE-GAP-002`, `W3-DEV-GUIDE-GAP-006` |
| `DLV-DEV-04` | `CURRENT_STATE_CONTENT_WITH_FINAL_W3_INTERNAL_EVIDENCE` | `W3-DEV-GUIDE-GAP-001`, `W3-DEV-GUIDE-GAP-002`, `W3-DEV-GUIDE-GAP-004`, `W3-DEV-GUIDE-GAP-007`, `W3-DEV-GUIDE-GAP-008`, `W3-DEV-GUIDE-GAP-009` |
| `DLV-DEV-05` | `CURRENT_STATE_CONTENT_WITH_FINAL_W3_INTERNAL_EVIDENCE` | `W3-DEV-GUIDE-GAP-001`, `W3-DEV-GUIDE-GAP-003`, `W3-DEV-GUIDE-GAP-005`, `W3-DEV-GUIDE-GAP-008` |
| `DLV-DEV-06` | `CURRENT_STATE_CONTENT_WITH_FINAL_W3_INTERNAL_EVIDENCE` | `W3-DEV-GUIDE-GAP-001`, `W3-DEV-GUIDE-GAP-003`, `W3-DEV-GUIDE-GAP-004`, `W3-DEV-GUIDE-GAP-005` |
| `DLV-DEV-08` | `CURRENT_STATE_CONTENT_WITH_FINAL_W3_INTERNAL_EVIDENCE` | `W3-DEV-GUIDE-GAP-001`, `W3-DEV-GUIDE-GAP-002`, `W3-DEV-GUIDE-GAP-005`, `W3-DEV-GUIDE-GAP-006`, `W3-DEV-GUIDE-GAP-007`, `W3-DEV-GUIDE-GAP-008`, `W3-DEV-GUIDE-GAP-009` |


각 산출물은 위 `gap_disposition` 객체 하나만 가진다. gap의 소유자, 목표 wave `W3`~`W9`, 종료조건과 예상 근거 경로는 JSON `open_gaps`에 있다.

## 검증·출시 경계

- W3 execution: `PASS_INTERNAL_CURRENT_SOURCE_WITH_EXTERNAL_BOUNDARIES`; 명명된 내부 범위만 PASS
- formal test: `279/279 NOT_RUN`
- database 내부 migration/schema/repeatability: `PASS`; actual device·cross-process network·TMAP·model quality/provider: `NOT_RUN`
- release gates: `GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-SERVER-CAPACITY-STATE-TO-PHONE, GATE-UNREDACTED-RAW-INDEPENDENT-REVIEW, GATE-CLOUD-STORAGE-COST-VALIDATION, GATE-SINGLE-ADMIN-RECOVERY-DRILL` 모두 `NOT_RUN`, `waived=false`
- release: `NOT_ELIGIBLE`

## 지문 계약

JSON의 `/integrity/content_fingerprint/value`를 `null`로 두고 전체 객체를 UTF-8, `ensure_ascii=false`, key 정렬, compact separator로 직렬화한 뒤 후행 LF를 붙여 SHA-256을 계산한다. 현재 값은 `5eddc7f8fbb84ff02caf9b93773dd07be42c5d78e0cac2da325c33f6278a3072`다.
