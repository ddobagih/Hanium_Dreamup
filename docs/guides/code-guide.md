# 코드 가이드

이 문서는 팀원이 현재 코드를 찾기 위한 **탐색 지도**다. 기능 정책, API schema, 모델 설정을 이 문서에 다시 정의하지 않는다. 값이 충돌하면 아래에 연결한 정책 기준선, current code map, 실제 source, OpenAPI와 runtime config를 따른다. hash로 봉인된 과거 모듈 README는 현행 계약으로 사용하지 않는다.

## 상태 분류

| 상태 | 의미 |
|---|---|
| `CURRENT_PRODUCT` | 현재 제품인 Android 사용자 앱 또는 별도 Android 관리자 앱 |
| `SUPPORT` | 현재 제품의 API, 데이터·모델, 계약, 배포 또는 검증을 지원하는 코드 |
| `LEGACY_REFERENCE` | 재현·회귀 참고용이며 새 기능을 구현하거나 배포할 대상이 아닌 코드 |

제품 경계의 정본은 [제품 경계 설정](../../configs/walksafe_product_boundary_20260722.json)과 [승인 정책 기준선](../control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json)이다.

## 코드 지도

| 상태 | 영역 | 역할 | 먼저 볼 문서·진입점 |
|---|---|---|---|
| `CURRENT_PRODUCT` | Android 사용자 앱 | 보행 생명주기, 온디바이스 탐지, 길안내, 음성·진동, 신고 | [현재 코드 지도](code/android-user.md), [결속된 과거 Android README](../../apps/android/README.md), [MainActivity.kt](../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt) |
| `CURRENT_PRODUCT` | Android 관리자 앱 | 관리자 인증·복구, 장치 증명, 신고 검토와 수동 전달 기록 | [상세 코드 지도](code/android-admin.md), [관리자 앱 README](../../apps/android/adminapp/README.md), [AdminBoundaryActivity.java](../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java) |
| `SUPPORT` | Android Gateway | 세션·보행 원장을 로컬 종결하고, navigation·report를 Backend에 중계하며, 동의·삭제에 로컬·Backend 혼합 제어를 적용 | [현재 코드 지도](code/android-gateway.md), [결속된 과거 Gateway README](../../apps/android-gateway/README.md), [server.ts](../../apps/android-gateway/server.ts), [routes.ts](../../apps/android-gateway/src/routes.ts), [Gateway OpenAPI](../../apps/android-gateway/openapi.json) |
| `SUPPORT` | Backend | FastAPI 조립, 정책·도메인 서비스, DB·파일 저장, 관리자 API | [Backend README](../../backend/README.md), [main.py](../../backend/app/main.py), [API 계층 지도](../../backend/app/api/README.md), [서비스 계층 지도](../../backend/app/services/README.md) |
| `SUPPORT` | 모델·데이터 | 학습·검증 helper, 후보 registry, 데이터 변환·provenance 자료 | [모델 README](../../model/README.md), [모델 registry](../../model/registry/walksafe-model-registry.json), [데이터 소스 README](../../data_sources/README.md), [데이터·AI 가이드](data-ai-guide.md) |
| `SUPPORT` | 계약·설정·배포 | Backend 계약 생성, runtime 설정, 배포 예제와 운영 전제 | [계약 README](../../contracts/README.md), [Backend OpenAPI](../../contracts/walksafe.openapi.json), [설정 README](../../configs/README.md), [배포 README](../../deploy/README.md) |
| `LEGACY_REFERENCE` | Voice prototype | 음성 intent·TTS의 독립 prototype과 회귀 참고 자료. Android 제품 runtime으로 보지 않음 | [Voice README](../../voice/README.md) |
| `SUPPORT` | Tooling | 생성·검사·이관 스크립트와 제품·제어 회귀 테스트 | [스크립트 가이드](../../scripts/README.md), [테스트 지도](../../tests/README.md), [CI workflow](../../.github/workflows/quality.yml) |
| `LEGACY_REFERENCE` | Web/PWA | 과거 UI·관리자·API 참고 코드. 현행 제품 기능 추가·배포 금지 | [Legacy Web README](../../apps/web/README.md) |

`voice/`는 실행 가능한 prototype이지만 제품 경계에는 포함되지 않는다. `apps/web/` 테스트가 통과해도 Android 실기기·현장·출시 근거로 올리지 않는다.

`apps/android/README.md`와 `apps/android-gateway/README.md`는 완료 증거에 hash로 결속된 과거 snapshot이라 직접 고치지 않는다. Android README의 절대 APK 경로와 Gateway README의 “네 개 API”·`/privacy/rights` 설명은 현행 계약이 아니다. 현재 Android 빌드·설치는 [Android 사용자 앱 코드 지도](code/android-user.md), Gateway 공개 표면은 [Gateway 코드 지도](code/android-gateway.md)와 OpenAPI를 사용한다.

## 요청 흐름

### 사용자 앱

```text
Android 사용자 기능
  -> Android network/navigation/report client
  -> Android Gateway의 공개 경로
  -> Gateway 인증·동의·전송 정책
  -> [Gateway-local] field session·field walk
  -> [Gateway·Backend 혼합] 통합 동의·계정 삭제의 로컬 내구 상태·Backend 동기화
  -> [Backend-bound] navigation·report API
       -> Backend service
       -> DB·보호 파일 또는 외부 provider
```

사용자 앱의 외부 HTTP 표면은 [Gateway OpenAPI](../../apps/android-gateway/openapi.json), Gateway가 호출하는 서버 표면은 [Backend OpenAPI](../../contracts/walksafe.openapi.json)가 정본이다. 구현 위치는 [Gateway routes.ts](../../apps/android-gateway/src/routes.ts), [Backend API 디렉터리](../../backend/app/api/)와 [서비스 디렉터리](../../backend/app/services/)에서 찾는다.

### 관리자 앱

```text
Android 관리자 화면
  -> 관리자 security/operations client
  -> Backend 관리자·신고 API
  -> 인증·장치 증명·업무 정책 service
  -> DB·감사 이력
```

관리자 앱은 Gateway를 경유하지 않는다. 진입점은 [AdminBoundaryActivity.java](../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java), HTTP 구현은 [AdminSecurityHttpClient.java](../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java)와 [AdminOperationsHttpClient.java](../../apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOperationsHttpClient.java)다.

## 변경할 때 함께 볼 계약

| 변경 대상 | 함께 확인할 항목 |
|---|---|
| 사용자 앱 API 호출 | Android 호출부, [Gateway OpenAPI](../../apps/android-gateway/openapi.json), `routes.ts`, Gateway 계약 테스트, 대응 Backend schema·route |
| Backend API | Pydantic schema·API·service, [Backend OpenAPI](../../contracts/walksafe.openapi.json), [계약 생성 절차](../../contracts/README.md), Gateway/Android consumer 테스트 |
| 관리자 API·보안 | 관리자 HTTP client, Backend `admin_security`·`reports` route/service, OpenAPI, 장치 증명·재인증 테스트 |
| 모델·class·threshold | [Android runtime config](../../apps/android/app/src/main/assets/model-config/two_model_runtime.json), [Backend runtime 설정](../../configs/walksafe_unified_epoch270_field_20260711.json), registry·deployment, tensor/class/hash 검사 |
| DB·저장 형식 | Backend model·schema·service와 Alembic migration, API 계약, 기존 데이터 호환·rollback 검증 |
| 운영 환경변수·배치 | 실제 설정 source, [개발 환경 가이드](development-environment-guide.md), `deploy/` 예제, 보안·release 경계. `docs/backend/backend_environment.md`와 날짜형·hash 결속 README는 역사 snapshot으로 구분 |

API를 바꿀 때는 구현만 먼저 고치지 않는다. consumer와 provider 양쪽, 두 OpenAPI 중 해당 계약, 생성·검사 스크립트, 회귀 테스트를 같은 변경 단위로 맞춘다. 생성된 계약을 손으로 수정하지 않고 [계약 생성 절차](../../contracts/README.md)를 사용한다.

## 최소 작업 순서

1. 대상 상태가 `CURRENT_PRODUCT` 또는 `SUPPORT`인지 확인한다. `LEGACY_REFERENCE`에는 현행 기능을 추가하지 않는다.
2. 현재 코드 지도와 진입점, 연결된 OpenAPI·lock·runtime config를 읽고 README가 역사 snapshot인지 구분한다.
3. 실패를 재현하거나 계약을 고정하는 테스트를 먼저 정한다.
4. 필요한 파일만 수정하고 consumer/provider 계약을 함께 갱신한다.
5. 모듈 테스트와 저장소 계층 검사를 실행한다. 내부 PASS를 실기기·현장·정식 시험 PASS로 표현하지 않는다.
