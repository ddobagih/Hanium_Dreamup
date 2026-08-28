# Android Gateway 코드 지도

상태: `SUPPORT`

Gateway는 Android 사용자 앱의 독립 Node.js 22 서비스입니다. field session·field walk은 Gateway-local로 종결하고, navigation·report는 Backend에 중계하며, 통합 동의·계정 삭제는 로컬 내구 상태와 Backend 동기화를 함께 적용합니다. 실제 계약 정본은 [`openapi.json`](../../../apps/android-gateway/openapi.json), route 조립은 [`routes.ts`](../../../apps/android-gateway/src/routes.ts)입니다.

[`apps/android-gateway/README.md`](../../../apps/android-gateway/README.md)는 완료 증거에 hash로 결속된 과거 snapshot입니다. 그 문서의 “네 개 API”와 `/privacy/rights`가 OpenAPI 밖이라는 설명은 현행 계약이 아닙니다. 현재 OpenAPI에는 9개 path와 13개 operation이 있으며 field walk, 통합 동의·권리, 계정 삭제 경로를 포함합니다.

## 코드 책임

| 영역 | 현재 진입점 |
|---|---|
| 서버·route 조립 | [`server.ts`](../../../apps/android-gateway/server.ts), [`routes.ts`](../../../apps/android-gateway/src/routes.ts) |
| Backend 연결 | [`backend.ts`](../../../apps/android-gateway/src/backend.ts) |
| 인증·세션 | [`auth.ts`](../../../apps/android-gateway/src/auth.ts), [`field-long-session.ts`](../../../apps/android-gateway/src/field-long-session.ts) |
| 보행 기록 | [`field-walk-ledger.ts`](../../../apps/android-gateway/src/field-walk-ledger.ts) |
| 통합 동의·권리 | [`integrated-consent.ts`](../../../apps/android-gateway/src/integrated-consent.ts), [`privacy-rights.ts`](../../../apps/android-gateway/src/privacy-rights.ts) |
| 계정 삭제 | [`privacy-deletion-v2.ts`](../../../apps/android-gateway/src/privacy-deletion-v2.ts) |
| 파일 잠금·요청 제한 | [`exclusive-file-lock.ts`](../../../apps/android-gateway/src/exclusive-file-lock.ts), [`request-body.ts`](../../../apps/android-gateway/src/request-body.ts) |
| 계약·회귀 | [`openapi.json`](../../../apps/android-gateway/openapi.json), [`test/`](../../../apps/android-gateway/test/) |

## 배포 경계

[`deploy/README.md`](../../../deploy/README.md)와 같은 경로의 nginx 예제는 `DRAFT_DEPLOYMENT_EXAMPLE / NOT_APPLIED`입니다. 현행 예제는 기존 다섯 `/api/*` 경로, 계정 삭제 3경로와 `/privacy/rights`를 Gateway OpenAPI에 맞춰 열되 나머지는 404로 닫습니다. 계정 삭제 status·device-evidence location은 16~128자의 `[A-Za-z0-9_-]` request ID만 허용하며 본문 상한은 요청 4 KiB, 상태 1 KiB, 기기증거 16 KiB입니다. 이 정적 예제와 내부 검증은 실제 nginx 적용·외부 배포·실기기 연결 증거가 아닙니다.

## 계약 확인

앞의 세 명령은 Gateway 자체 검사이고 마지막 명령은 Gateway consumer가 의존하는 Backend provider OpenAPI·walking-route fixture의 currentness 검사입니다.

```bash
npm --prefix apps/android-gateway ci
npm --prefix apps/android-gateway run typecheck
npm --prefix apps/android-gateway test
PYTHONPATH=. "${PYTHON_BIN:-python3}" -B scripts/generate_walksafe_openapi.py --check
```

경로·method·schema를 이 문서의 숫자나 설명만 보고 구현하지 않습니다. OpenAPI와 `routes.ts`, Android consumer, Backend provider를 같은 변경 단위로 확인합니다. 로컬 계약 검사가 통과해도 운영 배포·실기기 연결·정식 시험 PASS를 뜻하지 않습니다.
