# 기기점검 판정 기준 엄격화 — 설계

> 상태: `DRAFT` · 팀 검토 전 · **어느 값도 실기기로 측정하지 않았다**<br>
> 기준일: 2026-09-08 · 기준 커밋: `37dd42c`<br>
> 근거 요구: `RQ-FP-009-001` (`DRAFT` · `NOT_APPROVED` · `TC-FP-009-01…04` 전부 `NOT_RUN`)

이 문서는 설계이며 요구 충족 판정이나 시험 결과가 아니다. 아래 "변경할 값"은 목표 수치일 뿐
측정으로 확인한 값이 아니다. 실기기가 연결되지 않은 상태에서 작성했으므로, 구현과 검증은
측정이 가능해진 뒤에 수행한다.

## 1. 무엇을 해결하는가

기기점검을 어지간한 기기가 통과한다. 2026-09-07 실기기 실행(SM-A716S, Android 13)에서
`device_check_result_tier_v1=FULL`, 제한 기능 0개로 통과했지만, 그 결과가 실제로 증명한 것은
"장치가 존재하고 죽지 않았다"에 가깝다.

점검 5개 항목을 실측 타당성으로 판정한 결과는 다음과 같다.

| 항목 | 실측 여부 | 판정 |
|---|---|---|
| 호출어 | 사용자 실제 발화 + Vosk confidence 평균 ≥ 0.60 | 타당 |
| 한국어 TTS | 실제 WAV 합성, 오프라인 음성만 허용 | 거의 타당 — 산출물 검증만 약함 |
| GPS fix | 실제 fix 획득 | 임계값이 용도에 맞지 않음 |
| 카메라 파이프라인 | 실제 CameraX + 실제 detector | 표본·기준 부족 |
| 기기 자원 | 시스템 불리언 플래그 읽기 | 실측 아님 |

실측이라 부를 수 있는 것은 호출어와 TTS 둘뿐이다. GPS·카메라·자원의 품질 판정은 전부
보행 시작 게이트로 미뤄져 있다.

이 구조는 미승인 기기를 `LIMITED`로 떨어뜨리던 동안에는 견딜 만했다. 미승인 기기를 전면
차단하기로 한 이상(절 2), 기기점검이 "이 기기로 걸어도 되는가"의 최종 판정이 되므로
현재 기준으로는 그 질문에 답하지 못한다.

## 2. 확정된 정책 — 미승인 기기 전면 차단

실측 승인된 기기가 아니면 **재점검 외 어떤 기능도 열지 않는다.** 현재 코드가 미승인 기기를
`LIMITED`로 떨어뜨리는 것은 임의 시험을 위한 임시 상태이며, 제품 기준이 아니다.

재점검만은 미승인 상태에서도 허용한다. 이 예외가 절 5의 부트스트랩을 성립시킨다.

`RQ-FP-009-001`이 못박은 3분류(전체 기능 / 거리 제한 / 사용 불가) 자체는 바뀌지 않는다.
바뀌는 것은 미승인 기기가 어느 분류에 놓이는가이다.

## 3. 판정 계층 — 측정은 앱이, 승인은 사람이

| 계층 | 질문 | 언제 | 수행 |
|---|---|---|---|
| ① 승인 | 이 수치면 걸어도 되는가 | 기종당 1회 | 사람 |
| ② 기기점검 | 이 단말이 어떤 수치를 내는가 | 로그인 후 1회 | 앱 |
| ③ 보행 게이트 | 지금 걸어도 되는가 | 매 보행 | 앱 |

②가 ①의 계측기다. 별도의 현장 측정 캠페인이 아니라, ②를 조이는 작업이 곧 ①의 입력을
만드는 작업이다. 따라서 **②가 ①보다 앞선다.**

자동화할 수 없는 것은 측정이 아니라 위험 수용 판단이다. 서버 스키마의 `approved_by`·
`approved_at`도 측정 필드가 아니라 결재 필드다.

발열 지속·배터리 소모·조도 편차를 사람이 재야 한다고 보지 않는다. `WalkRuntimeSafetyCoordinator`가
보행 중 발열을, `CameraFrameQualityPolicy`가 프레임마다 조도를 이미 관측한다.

## 4. 변경할 값

각 행의 근거는 "이미 이 저장소 어딘가에서 쓰이는 값"이다. 근거 없는 새 숫자는 절 5로 분리했다.

### 4.1 GPS fix — 100m → 15m, 그리고 차단 신호로 승격

```kotlin
// 현재: DeviceCheckLocationFixPolicy.kt:32
const val MAX_ACCURACY_METERS = 100f
```

같은 저장소가 보행에는 15m를 요구한다.

```kotlin
// WalkSafeEnvironmentProfiles.kt:60
maximumGpsHorizontalAccuracyMeters = 15.0
```

100m는 횡단보도 하나, 건물 한 채를 구분하지 못한다. 실내 Wi-Fi 기반 fused 위치로도 통과하므로
"GPS 칩이 답을 준다"는 것 외에 아무것도 증명하지 못한다. `MAX_AGE_MS = 30_000L`은 유지한다.

**차단 승격이 함께 필요하다.** 현재 `locationFix`는 `PostLoginDeviceCheckObservation`에 담기지만
`hasPendingAutomaticProbe`의 신호 목록에 없고 `evaluate`의 차단 검사에도 없다. 관측만 되고
판정에 관여하지 않는다. 2026-09-07 실행에서 화면에 위치 `제한`이 뜬 채로 결과가 `FULL`이었던
이유가 이것이다.

### 4.2 카메라 파이프라인 — 5프레임 중 1장 → 10프레임 중 80%

```kotlin
// 현재: MainActivity.kt:36767
const val POST_LOGIN_CAMERA_PIPELINE_MAX_FRAMES = 5   // 1장만 성공하면 통과
```

표본 구조를 `RuntimeMetricPreflightPolicy`에서 빌려온다.

```kotlin
MIN_DISTINCT_FRAMES = 10        MIN_OBSERVATION_SPAN_MS = 1_000
MIN_PASSING_PERCENT = 80        MAX_DURATION_MS = 10_000
```

1장 통과는 "카메라가 한 번 열렸다"만 증명한다. 보행 중 실시간 위험 감지가 목적이므로
지속 처리량을 봐야 한다.

밝기·가림·흔들림·장착각도를 보는 `CameraFrameQualityPolicy`는 **③에 남긴다.** 그것은 기기의
영구 능력이 아니라 지금 이 순간의 환경이다. 주머니 속 캄캄한 프레임으로 점검을 통과시키지
않으려면 최소 밝기만 ②에서 함께 보는 방안을 검토하되, 실내 점검을 막지 않는 값이어야 한다.

### 4.3 발열 — CRITICAL → SEVERE

```kotlin
// 현재: AndroidWalkSessionResourceProbe.kt
val thermalBelowCritical = thermalStatus?.let { it < PowerManager.THERMAL_STATUS_CRITICAL }
val thermalThrottled = thermalStatus?.let { it >= PowerManager.THERMAL_STATUS_SEVERE }  // 계산만 하고 미사용
```

Android 열 단계는 `NONE→LIGHT→MODERATE→SEVERE→CRITICAL→EMERGENCY→SHUTDOWN`이다. `CRITICAL`은
기기 강제종료 직전이므로, 현재 기준은 이미 심한 스로틀링(`SEVERE`)에서도 통과시킨다. 스로틀링
상태에서 잰 카메라·추론 수치는 이 기기의 능력을 대표하지 못한다.

`thermalThrottled`가 이미 계산돼 있으므로 판정에 연결하기만 하면 된다.

이때 `SEVERE`는 차단이 아니라 **측정 거부**로 다룬다. 발열은 기기의 영구 특성이 아니라 현재
상태이므로, "이 기기는 부적합"이 아니라 "지금은 측정할 수 없으니 식은 뒤 다시"가 맞는 안내다.

### 4.4 TTS 산출물 — 존재 → 최소 길이

```kotlin
// 현재: AndroidKoreanTextToSpeechSynthesisProbe.kt:226
completedOutput.isFile && completedOutput.length() > 0L
```

1바이트면 통과한다. WAV 헤더만 있고 오디오가 없어도, 0.05초짜리 잡음이어도 통과한다.

프로브 문장은 `"기기 점검 안내입니다."`로 고정돼 있으므로 최소 재생시간을 도출할 수 있다.
바이트 하한은 엔진 샘플레이트에 따라 달라지므로, 파일 크기보다 **WAV 헤더를 파싱한 재생시간**
쪽이 엔진 간 이식성이 높다. 구체값은 실기기 측정 후 확정한다.

이 항목은 2026-09-07 실기기에서 실제로 효과가 확인된 경로다. 삼성 TTS가 `setLanguage(ko)`로는
사용 가능이라 답하면서 Voice 객체를 0개 노출하는 것을 이 프로브가 잡아냈다.

### 4.5 호출어 — 유지

변경하지 않는다. 사용자 실제 발화를 요구하고, final 결과에 단어별 confidence 평균 ≥ 0.60을
적용하며(`VoskTranscript.kt:47`), `WakePhraseCommandExtractor`로 지칭 여부까지 확인한다.
마이크·오프라인 모델·한국어 인식이 한 경로로 동시에 증명되는 유일한 항목이다.

### 4.6 승인 기기 게이트 — OFF → ON

```kotlin
// 현재: MainActivity.kt:23028
approvedDeviceProfileRequired = false,

// ApprovedDeviceProfile.kt:39
const val REGISTRY_REVISION = "20260722-r001-empty"
val production: List<ApprovedDeviceProfile> = emptyList()
```

절 2에 따라 켠다. 다만 지금 켜면 승인 기기가 0대이므로 전원이 차단된다. 순서는 절 5에 있다.

## 5. 값을 정할 수 없는 항목 — 추론 지연 상한

```kotlin
// WalkRuntimeSafetyCoordinator.kt:255
val productionThresholdProfile: ApprovedWalkRuntimeSafetyThresholdProfile? = null
```

`maximumInferenceLatencyMs` 판정 로직은 있으나 승인된 값이 없다. 테스트에만 `200L`이 있고
런타임에는 `null`이라 적용되지 않는다.

현재 detector 통과 기준에는 지연 상한이 아예 없다.

```kotlin
// DeviceCheckDetectorExecutionPolicy.kt:7
if (timing.yuvDecodeMs == null || timing.yuvDecodeMs < 0L) return false
if (timing.totalMs == null || timing.totalMs < 0L) return false
```

시간이 0 이상이기만 하면 통과하므로 프레임당 3초가 걸려도 통과한다.

이 값은 측정으로 나오지 않는다. 보행 속도, 위험 인지 거리, 사용자 반응 시간에서 역산해야 하는
안전 설계값이다. 기기가 300ms를 내는 것은 사실이지만 300ms로 충분한지는 판단이다.

`[NOT_APPROVED: 추론 지연 상한]`으로 남기고, 정해지기 전에는 이 항목을 통과 조건으로 넣지 않는다.
같은 profile의 `maximumFrameAgeMs`도 함께 비어 있으며 같은 시점에 정한다.

### 5.1 비어 있음을 조용하지 않게 만든다 — 2026-09-08 구현됨

임계값이 `null`이면 `WalkRuntimeSafetyCoordinator`는 `NOT_CONFIGURED`를 반환하고,
`safetyOutputsAllowed`가 `true`가 되어 출력을 허용한다. 이 fail-open은 의도된 동작이며
시험이 잠그고 있다.

```kotlin
// WalkRuntimeSafetyCoordinatorTest.kt:30
fun productionProfileAllowsSafeTestFlowWithoutApprovedTimingThresholds()
    inferenceLatencyMs = Long.MAX_VALUE  →  safetyOutputsAllowed == true
```

승인하지 않은 숫자를 임의로 강제하지 않겠다는 판단이므로 이 동작 자체는 바꾸지 않는다.
12개 정지 사유 중 나머지 10개는 profile과 무관하게 계속 latch된다
(`unconfiguredProfileStillLatchesThresholdIndependentUnsafeCauses`).

문제는 fail-open이 아니라 **그 상태가 아무 신호도 내지 않는다**는 점이었다. `configured`
속성이 있으나 제품 코드에서 아무도 읽지 않고, 빌드도 경고하지 않는다. 같은 "승인 전" 상태를
저장소가 세 곳에서 다르게 처리하고 있었다.

| 대상 | 승인 전 동작 | release 차단 |
|---|---|---|
| Gateway origin | — | 빌드 실패 |
| 환경 profile | `BuildConfig.DEBUG`일 때만 test candidate | 자동 |
| 안전 임계값 | 조용히 통과 | **없었음** |

세 번째를 앞의 둘과 같은 규칙으로 맞췄다. `validateWalkSafeRuntimeSafetyThresholds`가
`preReleaseBuild`에 걸려, `productionThresholdProfile`이 `null`인 동안 release 빌드가 실패한다.
가드는 런타임이 읽는 바로 그 선언을 읽으므로 값이 채워지면 자동으로 풀린다. debug 빌드는
영향받지 않는다 — 임계값을 정하려면 현장 시험이 선행해야 하기 때문이다.

## 5.2 구현 순서

절 4의 값 변경은 실기기가 연결된 시점에 한 번에 수행한다. 판정 로직만 고치고 실제 기기에서
확인하지 못하면, 조인 기준이 정상 기기를 배제하는지 알 수 없기 때문이다. GPS 15m와 카메라
10프레임은 특히 실내 점검 가능 여부를 함께 봐야 한다.

2026-09-08 시점에 구현한 것은 절 5.1의 release 차단뿐이다. 이것만 먼저 넣은 이유는 값에
의존하지 않고, debug 흐름을 건드리지 않으며, 값을 정하기 전에 넣어두는 편이 나중에 출시를
시도하는 사람에게 이 논의를 강제하기 때문이다.

## 6. 승인 게이트를 켜는 순서

게이트를 먼저 켜면 아무도 점검을 통과하지 못해 승인할 자료도 못 모은다. 순서를 뒤집는다.

1. **②를 먼저 조인다** (절 4). 게이트는 아직 OFF.
2. 조인 기준으로 보유 기기를 점검하고 결과를 수집한다. 저장된 결과에서 바로 꺼낼 수 있다.
   ```
   adb shell run-as kr.co.hanium.dreamup.walksafe cat shared_prefs/walksafe.xml
   ```
   `PostLoginDeviceCheckResultBinding`이 제조사·모델·SDK·빌드 지문·앱 버전 등 24개 필드를
   이미 기록한다.
3. 사람이 그 자료를 검토하고 기종을 승인 목록에 등재한다.
4. 등재된 기기에서 점검이 통과하는 것을 확인한 뒤 게이트를 켠다.

미승인 상태에서도 점검이 도는 것이 2단계를 성립시킨다(절 2).

### 승인 목록을 어디에 둘 것인가

`current`에는 서버 matrix 배관이 없다. 백엔드·게이트웨이·관리자 앱 어디에도 관련 코드가 없고,
앱 쪽 파서(`2026-09-04` 설계 3단계)는 다른 브랜치에만 있다.

당분간 **컴파일된 목록**으로 시작할 것을 제안한다. 승인 기기가 0대인 지금 병목은 전달 방식이
아니라 승인 자체이고, 자격 검증 단계에서는 어차피 테스터에게 debug APK를 직접 설치한다.
Kotlin 상수 하나를 고치는 편이 백엔드·게이트웨이·관리자 UI와 `AD-06` 재인증을 세우는 것보다
싸다. 개발 기계(macOS)에서 백엔드·게이트웨이 검증이 불가능하다는 제약도 그대로다.

목록이 스토어 배포보다 빨리 바뀌기 시작하면 서버 전달로 올린다. 그때 `2026-09-04` 설계의
1·2·4단계를 수행한다. 결과 결속에 `approvedDeviceProfileRegistryRevision`과
`RegistrySha256`이 이미 들어 있어, 목록이 바뀌면 저장된 점검 결과가 자동 무효화되고
재점검을 요구한다.

**전달 실패 규칙을 다시 판정해야 한다.** 3단계 구현은 "전달 실패 시 마지막 값 유지"를
잠갔는데, 그 근거는 "비우면 모든 기기가 `LIMITED`로 떨어진다"였다. 절 2에서는 같은 코드가
"모든 기기가 차단된다"가 된다. 같은 규칙이 전혀 다른 무게를 갖는다.

## 7. 이번 범위 밖

- **거리 실측을 점검으로 편입하는 일.** 현재 점검은 Depth API 지원 여부만 확인한다
  (`evidence=DEPTH_API_SUPPORT_ONLY`, 정책 `20260905-support-only-v1`). 실제 거리 측정은
  `RuntimeMetricPreflight`가 보행 시작 때 수행한다. 사용자 결정으로 뒤로 미룬다.
- **진동을 요구항목으로 되돌리는 일.** `37dd42c`가 시작 차단 사유에서 기능 제한으로 강등했고
  의도를 밝힌 주석이 있다. 별도로 판단한다.
- **TTS 엔진 폴백.** 시스템 기본 엔진만 보는 탓에, 오프라인 한국어 음성 5개를 가진 Google TTS가
  같은 기기에 설치돼 있어도 삼성 TTS가 기본이면 차단된다(2026-09-07 SM-A716S 실측). 제품
  결함일 수 있으나 판정 기준과는 다른 문제다.
- **정식 시험 279건, 출시 gate 5개.** 이 설계의 구현을 요구 충족으로 주장하지 않는다.

## 8. 검증

각 단계에서 실패하는 시험을 먼저 쓴다. 순수 판정 로직은 JVM 단위 시험으로, 배선은 소스 정적
시험으로 확인한다(기존 `MainActivity*StaticTest` 선례).

| 대상 | 방식 | 실기기 필요 |
|---|---|---|
| GPS 15m 경계·차단 승격 | JVM 단위 | 아니오 |
| 카메라 표본 규칙 | JVM 단위 | 아니오 |
| 발열 SEVERE 측정 거부 | JVM 단위 | 아니오 |
| TTS 최소 길이 | JVM 단위 | 아니오 |
| 미승인 기기가 재점검 외 전 기능 차단 | JVM 단위 + 정적 | 아니오 |
| 실제 기기가 조인 기준을 통과하는가 | 실행 | **예** |
| 조인 기준의 실기기 수치 | 측정 | **예** |

macOS 전체 스위트에서 `AndroidReportQueueStoreTest` 12건 실패는 기존 기준선이다. 새 실패만
자기 변경 탓으로 본다.

## 9. 관련 기록

- 서버 기기 지원 matrix 설계: `2026-09-04-device-support-matrix-design.md`. 그 설계는 미승인
  기기가 `LIMITED`로 떨어지는 전제로 쓰였다. 절 2가 그 전제를 바꾼다.
- 기기 스펙 판정 설계: `2026-09-07-device-spec-verdict-design.md`.
- 2026-09-07 실기기 실행에서 확인한 사실: ARCore는 `com.google.ar.core.depth` feature를 선언하지
  않으면서 `isDepthModeSupported(AUTOMATIC)`는 `true`를 반환한다(거짓 부정). 삼성 TTS는
  `setLanguage(ko)`가 사용 가능을 반환하면서 Voice 객체를 0개 노출한다(거짓 긍정). 단일 API
  응답을 기기 능력의 증거로 삼지 않는다.
