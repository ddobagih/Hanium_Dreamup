# Android Gateway 코드 지도

상태: `SUPPORT`

기준일: 2026-09-30. [인수인계 시작 문서](../../handoff/2026-09-30/README.md), [현재 상태·검증 범위](../../handoff/2026-09-30/status.md), [Part B 분담](../../handoff/2026-09-30/part-b.md)을 먼저 확인합니다.

Gateway는 Android 사용자 앱의 독립 Node.js 22 서비스입니다. field walk은 로컬 원장으로 관리하며 Backend 계정·기기 세션의 현재성은 Backend와 확인합니다. 가입·로그인·navigation·report·원본 수집은 Backend에 중계하고, 통합 동의·계정 삭제는 로컬 내구 상태와 Backend 동기화를 함께 적용합니다. 실제 HTTP 계약은 [`openapi.json`](../../../apps/android-gateway/openapi.json), 경로·method·auth·body·query·deadline·family 판정은 [`route-registry.ts`](../../../apps/android-gateway/src/route-registry.ts), dispatch는 [`routes.ts`](../../../apps/android-gateway/src/routes.ts)를 확인합니다. adapter·telemetry·RAW relay도 같은 registry를 사용합니다.

[`apps/android-gateway/README.md`](../../../apps/android-gateway/README.md)는 현재 계약·설정·실행 안내입니다. 현재 OpenAPI와 registry는 가입·세션·보행·음성·길찾기·신고 권리·원본 collection·통합 동의·계정 삭제를 포함하며 `/privacy/rights` GET 설명에 공개 HTML 변형도 기술합니다. 경로 수를 고정된 문서 숫자로 사용하지 않고 실제 계약과 대조합니다.

## 코드 책임

| 영역 | 현재 진입점 |
|---|---|
| 서버·route 조립 | [`server.ts`](../../../apps/android-gateway/server.ts), [`route-registry.ts`](../../../apps/android-gateway/src/route-registry.ts), [`routes.ts`](../../../apps/android-gateway/src/routes.ts), [`node-adapter.ts`](../../../apps/android-gateway/src/node-adapter.ts) |
| Backend 연결·가입 | [`backend.ts`](../../../apps/android-gateway/src/backend.ts), [`account-relay.ts`](../../../apps/android-gateway/src/account-relay.ts) |
| 원본·음성 중계 | [`raw-collection-relay.ts`](../../../apps/android-gateway/src/raw-collection-relay.ts), [`speech-relay.ts`](../../../apps/android-gateway/src/speech-relay.ts) |
| 요청 관찰 | [`telemetry.ts`](../../../apps/android-gateway/src/telemetry.ts) |
| 인증·세션 | [`auth.ts`](../../../apps/android-gateway/src/auth.ts), [`field-long-session.ts`](../../../apps/android-gateway/src/field-long-session.ts) |
| 보행 기록 | [`field-walk-ledger.ts`](../../../apps/android-gateway/src/field-walk-ledger.ts) |
| 통합 동의·권리 | [`integrated-consent.ts`](../../../apps/android-gateway/src/integrated-consent.ts), [`privacy-rights.ts`](../../../apps/android-gateway/src/privacy-rights.ts) |
| 계정 삭제 | [`privacy-deletion-v2.ts`](../../../apps/android-gateway/src/privacy-deletion-v2.ts) |
| 파일 잠금·요청 제한 | [`exclusive-file-lock.ts`](../../../apps/android-gateway/src/exclusive-file-lock.ts), [`request-body.ts`](../../../apps/android-gateway/src/request-body.ts) |
| 계약·회귀 | [`openapi.json`](../../../apps/android-gateway/openapi.json), [`test/`](../../../apps/android-gateway/test/) |

## 배포 경계

[`deploy/README.md`](../../../deploy/README.md)와 같은 경로의 nginx 예제는 `DRAFT_DEPLOYMENT_EXAMPLE / NOT_APPLIED`입니다. 공개 허용 경로는 실제 nginx 예제와 Gateway OpenAPI·registry를 함께 대조하며 나머지 경로의 404 경계를 확인합니다. 계정 삭제 status·device-evidence location은 16~128자의 `[A-Za-z0-9_-]` request ID만 허용하며 본문 상한은 요청 4 KiB, 상태 1 KiB, 기기증거 16 KiB입니다. 이 정적 예제와 내부 검증은 실제 nginx 적용·외부 배포·실기기 연결 증거가 아닙니다.

## 계약 확인

앞의 세 명령은 Gateway 자체 검사이고 마지막 명령은 Gateway consumer가 의존하는 Backend provider OpenAPI·walking-route fixture의 currentness 검사입니다.

```bash
npm --prefix apps/android-gateway ci
npm --prefix apps/android-gateway run typecheck
npm --prefix apps/android-gateway test
PYTHONPATH=. "${PYTHON_BIN:-python3}" -B scripts/generate_walksafe_openapi.py --check
```

경로·method·schema를 이 문서의 숫자나 설명만 보고 구현하지 않습니다. OpenAPI와 `routes.ts`, Android consumer, Backend provider를 같은 변경 단위로 확인합니다. 로컬 계약 검사가 통과해도 운영 배포·실기기 연결·정식 시험 PASS를 뜻하지 않습니다.
