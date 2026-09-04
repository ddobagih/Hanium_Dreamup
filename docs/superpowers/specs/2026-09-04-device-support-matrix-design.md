# 기기 지원 matrix 서버 전달 — 설계

> 상태: `DRAFT` · 팀 검토 전<br>
> 기준일: 2026-09-04 · 기준 커밋: `052cf4b`<br>
> 근거 요구: `RQ-FP-009-001` (`DRAFT` · `NOT_APPROVED` · `TC-FP-009-01…04` 전부 `NOT_RUN`)

이 문서는 설계이며 요구 충족 판정이나 시험 결과가 아니다. 근거 요구가 아직 승인 전이므로
구현 착수 전에 팀 승인이 필요한 항목을 마지막 절에 따로 모았다.

## 1. 무엇을 해결하는가

`RQ-FP-009-001`은 역할을 이렇게 나눈다.

- 휴대전화가 하는 일: 기기 capability 검사
- **서버가 하는 일: 지원 matrix와 최소 버전 정책**
- 외부서비스가 하는 일: ARCore·기기 제조사 기능

휴대전화 몫은 구현돼 있다. 서버 몫이 없다.

```
OpenAPI 76개 경로 중 기기 지원 관련   0개
게이트웨이 9개 라우트 중              0개

// apps/android/app/.../device/ApprovedDeviceProfile.kt
object WalkSafeApprovedDeviceProfiles {
    const val REGISTRY_REVISION = "20260722-r001-empty"
    val production: List<ApprovedDeviceProfile> = emptyList()
}
```

지원 기기 목록이 앱 상수로 박혀 있고 그마저 비어 있다. 기기를 하나 추가하려면 앱을 다시
배포해야 한다. `WS-01`의 다음 할 일이 "지원 기기와 밝기·날씨·장착 조건을 실측해 승인한다"이므로,
앞으로 현장 시험을 하면서 목록이 계속 바뀐다. 그 빈도로 스토어 배포를 반복할 수 없다.

## 2. 따르는 선례

새 전달 경로를 만들지 않는다. 서버 정책값을 앱에 내려주는 통로가 이미 있다.

```
backend   GET /internal/capacity
            { version, observed_at, expires_at, level, reason }
   ↓
gateway   세션 status 응답이 200 이고 authenticated:true 일 때만
            { ...body, capacity } 로 얹는다        (server-capacity.ts:475)
            백엔드 실패 시 원 응답을 그대로 흘린다
   ↓
app       GatewayCapacityParser.fromSessionStatus(statusJson)
            키 집합·형식이 어긋나면 Malformed
```

앱이 별도 호출을 하지 않는다는 점이 핵심이다. 이미 하는 세션 상태 확인에 정책이 얹혀 온다.
따라서 "언제 받는가", "못 받으면 어떻게 하는가"를 새로 정하지 않고 세션 경로의 규칙을 물려받는다.

`capacity` 상태는 요청마다 파일을 다시 읽으므로 서버 재시작이 필요 없다
(`capacity_state.py`의 `current()` → `_read_current()`).

## 3. 데이터

### 테이블 `approved_device_profiles`

| 열 | 형 | 비고 |
|---|---|---|
| `id` | uuid pk | |
| `profile_id` | varchar unique | 앱의 `ApprovedDeviceProfile.profileId` |
| `version` | varchar | 프로필 개정 |
| `manufacturer` | varchar | |
| `model` | varchar | |
| `device` | varchar | |
| `min_sdk` | int | |
| `max_sdk` | int | |
| `state` | varchar | `ACTIVE` / `RETIRED` |
| `approved_by` | varchar | admin actor id |
| `approved_at` | timestamptz | |
| `created_at` / `updated_at` | timestamptz | |

레지스트리 `revision`과 `sha256`은 저장하지 않고 **조회 시 계산**한다. 앱의
`registryContentSha256`가 `profileId` 정렬 후 필드를 줄바꿈으로 이어 해시하는 방식이므로
서버가 같은 규칙을 재현하면 양쪽 값이 대조 가능하다. 저장하면 두 값이 어긋날 수 있다.

Alembic 마이그레이션은 현재 head `202609010003` 뒤에 놓는다.

### 읽기 — 사용자 앱

```
GET /internal/device-support-matrix

{ "schema_version": "walksafe.device-support-matrix.v1",
  "version": 3,
  "observed_at": "2026-09-04T00:00:00Z",
  "expires_at": "2026-09-04T00:05:00Z",
  "registry_revision": "20260904-r002",
  "registry_sha256": "…",
  "minimum_sdk": 26,
  "profiles": [
    { "profile_id": "…", "version": "1",
      "manufacturer": "samsung", "model": "SM-G981N",
      "device": "x1s", "min_sdk": 30, "max_sdk": 34 } ] }
```

`capacity`와 같이 `ConfigDict(extra="forbid")` pydantic 응답으로 고정한다.

### 쓰기 — 관리자 앱

```
GET    /admin/device-profiles
POST   /admin/device-profiles
PATCH  /admin/device-profiles/{profile_id}     state 변경(은퇴)
```

기존 관리자 API 패턴을 따른다 — `AdminSessionIdentity` 인증, `admin_operation_audits` 감사 기록.

## 4. 앱 쪽 주입

주입 지점이 이미 있다. 새 구조를 만들지 않는다.

```kotlin
// ApprovedDeviceProfile.kt:65
fun match(
    identity: WalkSafeDeviceIdentity,
    profiles: List<ApprovedDeviceProfile> = WalkSafeApprovedDeviceProfiles.production,
): ApprovedDeviceProfileMatch
```

기본 인자를 서버에서 받은 목록으로 바꾸면 된다.

`AndroidStartupCapabilityProbe`가 생성 시점에 `match()`를 한 번만 계산하므로, matrix를 늦게
받으면 반영되지 않는다. 세션 상태가 갱신될 때 다시 계산해야 한다. 같은 클래스의
`metricDistanceAvailable`이 `var` + `onCapabilityChanged()` 통지로 처리하고 있으므로 그 패턴을 따른다.

받은 matrix는 `AndroidDeviceCheckResultStore` 옆에 캐시한다. 세션 상태에 matrix가 없는 응답이
와도(게이트웨이가 백엔드에 못 닿음) 마지막 값을 쓴다. `capacity`가 실패 시 원 응답을 그대로
흘리는 것과 같은 이유다.

### 결과 결속이 자동으로 무효화된다

`PostLoginDeviceCheckResultBinding`의 `approvedDeviceProfileRegistryRevision`과
`RegistrySha256`이 이제 서버 값이 된다. matrix가 바뀌면 지문이 달라져 저장된 점검 결과가
무효가 되고 재점검이 필요해진다. 이는 의도한 동작이다.

## 5. 단계

각 단계는 독립적으로 검증 가능하고, 앞 단계가 통과해야 다음으로 간다.

1. **백엔드** — 테이블·마이그레이션·서비스·읽기 엔드포인트·관리자 엔드포인트·OpenAPI 정본 갱신
2. **게이트웨이** — 세션 status에 `device_support_matrix` 얹기
3. **사용자 앱** — 파서·재계산·캐시. `approvedDeviceProfileRequired`는 `false` 유지
4. **관리자 앱** — 기존 패널 7개 옆에 `AdminDeviceProfilePanel`
5. **게이트 켜기** — `approvedDeviceProfileRequired = true`. **이번 범위 밖**

5단계를 분리하는 이유: 승인된 기기가 현재 0대다. 배선이 검증되기 전에 켜면 모든 기기가
차단된다. 등록된 기기가 생기고 matrix 전달이 확인된 뒤에 별도 작업으로 켠다.

게이트를 켜면 미등록 기기는 차단이 아니라 `LIMITED`로 떨어진다
(`metricDistanceCannotYieldFullWithoutAnApprovedVersionedDeviceProfile` 테스트가 이미 그 동작을
잠그고 있다). `RQ-FP-009-001`의 3분류와 일치한다.

## 6. 시험

| 층 | 방식 | 선례 |
|---|---|---|
| 백엔드 | pytest + Postgres 통합 | `test_accounts_postgres_integration.py` |
| 계약 | OpenAPI 정본 대조 | `test_openapi_contract.py` |
| 게이트웨이 | node 테스트 | `npm --prefix apps/android-gateway test` |
| 앱 파서 | JVM 단위 — 키 불일치·형식 위반 거부 | `GatewayCapacity` 파서 |
| 앱 배선 | 소스 파싱 정적 테스트 | `MainActivity*StaticTest` |

각 단계에서 실패하는 테스트를 먼저 쓴다.

`contracts/walksafe.openapi.json` 갱신을 빠뜨리면 `test_openapi_contract.py`가 깨진다.
`/internal/capacity`가 이미 정본에 있으므로 내부 경로도 등재 대상이다.

## 7. 팀 승인이 필요한 열린 항목

- **`AC-FP-009-05` 신설.** 현재 인수조건 `AC-FP-009-01…04`는 전체 기능 시작, 거리 제한 안내,
  카메라·음성 없을 때 차단, 과거 웹판 접근 차단을 다룬다. **서버 matrix를 검증하는 인수조건이
  없다.** 요구 본문에는 서버 몫이 적혀 있는데 인수조건으로 내려오지 않았다.
- **`RQ-FP-009-001` 승인.** 현재 `DRAFT` · `NOT_APPROVED`다. 승인 전 구현을 완료로 주장하지 않는다.
- **최소 버전 정책의 값.** 요구는 "최소 버전 정책"을 서버 몫으로 두지만 값을 정하지 않았다.
  현재 앱은 런타임에서 `Build.VERSION_CODES.S`(API 31, Android 12) 이상을 요구하고 실패 문구도
  이와 일치한다. 다만 `build.gradle.kts`의 `minSdk`는 26이라 Android 8~11 기기에도 설치는 되고
  기기 점검에서 막힌다. 설치 자체를 막을지(`minSdk` 상향), 지금처럼 설치 후 사유를 설명할지는
  matrix에 `minimum_sdk`를 넣기 전에 확정이 필요하다.
- **기기 등록이 고위험 작업인가.** 지원 범위를 바꾸는 일이므로 `AD-06`(상태변경·내보내기·삭제 전
  재인증) 대상으로 볼지 정해야 한다.

## 8. 관련 기록

- `AC-FP-009-03`("카메라나 음성 기능이 없는 기기에서 시작을 막고 부족한 기능을 설명")의 음성 쪽은
  커밋 `052cf4b`가 구현했다. `RQ-FP-027-001`에 따라 오프라인 한국어 음성이 없으면 차단한다.
  실기기 검증은 아직 하지 않았다.
- 요구-구현 대조 전체는 `052cf4b` 시점 기준으로 별도 정리돼 있다.
