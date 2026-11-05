# Android 사용자 앱 코드 지도

상태: `CURRENT_PRODUCT`

기준일: 2026-09-30. [인수인계 시작 문서](../../handoff/2026-09-30/README.md), [현재 상태·검증 범위](../../handoff/2026-09-30/status.md), [Part A](../../handoff/2026-09-30/part-a.md), [Part B](../../handoff/2026-09-30/part-b.md)를 먼저 확인합니다.

진입점은 [`MainActivity.kt`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt), runtime 정본은 버전 관리되는 Gradle 설정과 asset config입니다. [Android README](../../../apps/android/README.md)는 현재 소스 안내와 표시된 과거 기록을 함께 담습니다. 과거 절대 APK 경로·dated hash·smoke 결과는 해당 버전의 이력으로만 사용합니다. 이 문서는 패키지를 찾기 위한 안내이며 기능 정책과 완료 상태를 새로 정하지 않습니다.

`MainActivity`는 로그인·동의·보행 상태와 센서·카메라·추론·음성·길안내·출력 연결을 조립합니다. Part A가 이 파일의 단일 수정 담당이며 Part B가 연결 변경을 필요로 하면 Part A에 요청합니다. 세부 담당 항목과 인수 조건은 위 분담 문서를 따릅니다.

## 패키지 책임

| 경로 | 책임 |
|---|---|
| [패키지 루트](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/) | 화면 조립, 권한·카메라·탐지·길안내·신고 연결 |
| [`depth/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/depth/) | ARCore depth, 좌표 변환, 거리·추적·TTC·위험 정책 |
| [`inference/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/) | runtime config, 이미지 전처리, TFLite 추론과 출력 파싱 |
| [`device/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/) | 지원기기·센서·카메라·배터리·시작 안전 gate |
| [`feedback/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/) | TTS·TalkBack·진동 우선순위와 전달 확인 |
| [`navigation/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/) | GPS·방향·걸음, TMAP 경로, 이탈·도착·점자블록 안내 |
| [`network/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/) | Gateway 로그인·세션·동의·계정 삭제와 전송 정책 |
| [`report/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/) | 신고 후보·동의·cooldown·업로드 |
| [`session/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/) | 최초 실행·교육·권한·보행 생명주기, 통합 동의·Backend 권한 재확인·CAS journal |
| [`security/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/security/) | AndroidKeyStore 기반 민감 설정 암호화 |
| [`fieldlog/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/) | debug 현장 session의 암호화 metadata-only 기록 |
| [`debuglog/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/debuglog/) | debug source set 진단 전송; release는 no-op |

Android가 직접 사용하는 모델 선택·class·tensor·hash는 [`two_model_runtime.json`](../../../apps/android/app/src/main/assets/model-config/two_model_runtime.json), 서버 요청 표면은 [Gateway OpenAPI](../../../apps/android-gateway/openapi.json)를 기준으로 봅니다.

새 clone에서는 [Vosk 모델 준비 스크립트](../../../scripts/prepare_vosk_ko_small_model.py)를 먼저 실행합니다. JDK·SDK·로컬 설정은 [개발 환경 가이드](../development-environment-guide.md#android-사용자관리자-앱), 앱별 실행·기기시험은 [Android 앱별 테스트 가이드](../android-apps-testing-guide.md)를 따릅니다. 저장소 루트에서 debug APK를 만들고 시험 전용 단말에 설치할 때는 현재 checkout 기준 상대경로를 사용합니다.

```bash
python3 scripts/prepare_vosk_ko_small_model.py
(cd apps/android && ./gradlew :app:assembleDebug --no-daemon)
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

통합 동의의 저장된 확인서 복원은 [`IntegratedConsentPolicy.kt`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/IntegratedConsentPolicy.kt)의 현재 Backend 권한 확인과 분리되며, 서버 재확인 전에는 전송을 허용하지 않습니다. CAS 충돌의 거부 선택 보존과 production V2 압축 journal은 [`RejectedConsentCasJournalCodec.kt`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/RejectedConsentCasJournalCodec.kt), 실제 prefs 저장·HTTP 연결은 `MainActivity`에서 확인합니다. 현재 연동 근거는 JVM 클라이언트→Gateway→Backend이며 실제 Activity·encrypted prefs 프로세스 재시작·최신 APK 설치는 미검증입니다.

## 샘플 이미지 성능 점검 연결 (2026-11-02)

- 기존 기기점검의 `PostLoginDeviceCheckController`가 권한·기한·진행·저장을 소유합니다. 거리 지원 조회를 끝내고 `MainActivity.startDeviceCompositeCameraCheck` → `AndroidJointRuntimeCheck`를 실행합니다. 초기 CPU 모델 성공을 진입 조건으로 삼지 않으며, 객체 시험은 내장 이미지4장만 사용합니다. 물체 촬영·휴대폰 이동·별도 설정 성능점검 절차는 없습니다.
- `JointRuntimeSearchPolicy`는 GPU/GPU를 먼저 실제 측정하고 혼합·CPU1/CPU2 조합과 서로 다른 처리 간격을 탐색합니다. 각 trial은 새 immutable owner scope를 받고 `JointRuntimeSampleHost`의 운영 proxy를 사용합니다. 이전 trial의 Java consumer·모델·FD·자식 종료 확인 전에는 다음 trial로 넘어가지 않습니다. `:fixture_runtime`은 debug의 명시적 레거시 진단에만 남으며 운영 점검에서는 사용하지 않습니다.
- 잠정 성능 자료 뒤 CPU1 참조를 한 번 확보하고, `JointRuntimeCheckedSelection.Claim`이 참조와 이미지의 단독 소유권을 Main에 넘깁니다. 최종 후보는 실제 두 모델을 새로 준비해4장 raw/역할/mask를 엄격히 비교하고, 같은 PID·모델 ID로10초 이상 공동 관측한 뒤에만 `ValidatedJointRuntimeChoice`와 `JointRuntimeFinalQualification`을 발급합니다. 잠정 선택을 검증된 선택으로 위조하지 않습니다. 최종 후보가 실패하면 이번 시도는 실패합니다.
- `JointRuntimeChoicePolicy` v3는 정확성·완료율·간격·800ms 유효시간에 더해 두 모델의 샘플 P95가600ms 이하여야 합니다. 200ms의 공학적 운영 여유이며 카메라·ARCore·화면 성능 보장은 아닙니다. REFERENCE10초·BENCHMARK2초·WARMUP15초와 호출자 절대 기한을 함께 적용하고, raw 반환 여부는 목적과 별도로 명시합니다.
- 최종 두 실체는 기존 primary/auxiliary 소유자에 채택되어 유지됩니다. 정상 Main↔Camera 인계는 old consumer 배출 → PARK → 동일 모델 ID의 epoch+1 양도 → dormant wrapper 두 개 부착 → registry 채택 → native 확인·활성화 순서입니다. 왕복 시 Main의 host ID는 유지하되 opaque owner scope를 교체해 이전 producer의 재진입을 막습니다. 보행도 검증된 pair를 사용하며 보조 모델을 독립 생성하지 않습니다.
- `ParentDirectBufferArena`와 `JointRuntimeMemoryLedger`는 backing을 재사용하고, 프레임·참조·이미지·mapping의 소유 한도를 검사합니다. managed heap에는 최소32MiB 또는25% 여유를 남기며 알려진 direct/shared buffer 상한을 따릅니다. 768 bilinear scratch는 최초 실제 할당 전 한 번 예약하고 할당 완료 즉시 pending에서 제외합니다. 후속 primary 프레임은 실제 신규128,000B만 예약하며 scratch 소유는 serial consumer 배출·캐시 정리 후 반환합니다. GC나 null 대입을 실제 해제로 간주하지 않습니다. GPU 드라이버·native 전체 RAM을 이 원장이 측정한다고 표현하지 않습니다.
- 실제 영상에서는 기존 frame·geometry·거리 freshness gate와 측정한 cadence floor를 유지합니다. IPC·전처리·후처리·UI/Depth 지연에 따라 입력을 늦추고, primary 과부하 때 보조 admission을 보류합니다. 새로운 정상 primary 결과4회와 필요한 온도·부하 회복을 확인해야 재개합니다. CPU/GPU 사용률을 예약하거나 고정했다고 표시하지 않습니다.

### 저장·취소·복구 계약

- `AndroidDeviceCheckAuthority`의 별도 AEAD 저장소가 시도·선택·결과·native LOAD/CLOSE journal·무효화를 같은 revision에 결속합니다. LOAD_INTENT 저장 확인 전 native OPEN을 전송하지 않습니다. 기존 계정·동의·교육 저장소와 옛 키를 삭제하거나 재사용하지 않습니다.
- 모든 필수 결과와 실제 pair 검증을 원래5분 안에 단일 결정으로 수용하면 `SAVING`으로 넘어갑니다. 이 상태는 통과가 아닙니다. 별도15초 저장 확인에서 durable ACK·현재 actor/session/source·현재 두 모델 증명이 모두 맞아야 FULL/LIMITED를 표시합니다. 늦은 timer나 성공 callback으로 기한을 늘리지 않습니다.
- 저장 결과는 Committed/Rejected/NotPublished/Indeterminate로 구분합니다. 대기 만료를 디스크 미갱신으로 단정하지 않으며, 미확정 I/O 정산과 새 파일 readback 전에는 자동 복원과 새 저장을 막습니다. Depth/TTS 결과 갱신도 같은 비동기 revision 계약을 사용합니다.
- 읽기·시작·취소·성공 저장·결과 갱신·정산·native journal은 같은 writer ticket의 완료 규칙을 사용합니다. `QUEUED→RUNNING→DONE`과 `QUEUED/RUNNING→UNCONFIRMED` 중 한 terminal 전이만 허용합니다. 만료가 이기면 같은 ticket 잠금 안에서 Core fence를 먼저 설치합니다. 대기 중 만료는 I/O 실행 권한을 회수하지만, 실행 중 만료의 실제 종료는 writer가 반환한 뒤 별도로 확인합니다. 정상 완료는 후속 callback 전에 timer를 취소하고, 이미 실행을 시작한 만료 callback도 완료 ticket을 되돌리지 못합니다. 외부 journal 오류의 명시적 `publicationUnconfirmed`는 synthetic marker·완료 작업에도 별도의 저장 차단을 발행할 수 있으며, watchdog과 구분합니다. future callback은 ticket 잠금 밖에서 실행합니다.
- 최초 저장소 읽기 실패는 사용자 재점검 동작에서만 같은 actor/session/binding으로 비동기 재조회합니다. 같은 조회는 합류하며 완료 전에는 새 시도를 허용하지 않습니다. 명시 재조회는 자동 복원 알림을 억제하고 캡처한 동작 한 번만 이어갑니다. 미확정 writer·schema 오류·native 회수 대기는 이 경로로 우회하지 않습니다. 본 adapter가 발행한 명령·읽기의 실제 authority 응답으로 확인한 exact operation은 첫 정산 읽기가 실패해도 명시 동작에서 정산만 다시 시도할 수 있습니다. 실제 writer 합류와 새 읽기를 거치며, 이전 계정의 정산 성공이 현재 계정의 점검 권한이 되지는 않습니다. 정산 뒤 현재 binding의 별도 읽기가 양성이어야 새 시도를 허용합니다. 캐시에서만 관찰한 임의 operation ID를 새 정산 권한으로 사용하지 않습니다. 시작 기록 저장 중 취소된 뒤 늦게 발급된 ref의 보상 취소도 일반 취소와 같은 `cancelIssuedAttempt` → `track` 경로를 사용합니다. 정리용 취소의 저장 거절·불확정·실제 writer 지연을 추적하며, 정산은 원 ref만 대상으로 하고 새 점검·모델 권한은 발급하지 않습니다. 발행한 정산 응답이 다음 canonical operation을 가리키면 이전 pending을 새 번호로 이관합니다. 이미 그 번호를 정산 중인 요청·진단 flow는 유지하며, 실제 읽기가 아닌 정산 응답을 읽기 결과로 오인해 자동 정산을 연쇄 실행하지 않습니다. 다음 명시 동작이 새 operation의 실제 writer 종료에 합류하고 현재 binding으로 별도 읽기를 확인해야 새 시도를 허용합니다. `AUTHORITY_RECONCILE/RETAINED/SUPERSEDED`는 이 대기 이관을 뜻하며, 이전 번호가 잔여 대기로 남지 않는지 시험합니다.
- 늦은 A→R 정산 응답보다 R의 실제 정산이 먼저 끝나면 R을 다시 pending에 넣지 않습니다. 해당 대기 요청의 수명 안에 확인한 exact 정산 receipt만 최대256개 보관하며, 현재 canonical fence가 다시 같은 번호라면 옛 receipt보다 우선합니다. 다른 현재 작업의 차단도 해제하지 않습니다. 저장 정산 여부와 원 응답을 분리하여 옛 `Blocked`를 `Ready`로 바꾸지 않고, 명시 동작은 여전히 현재 actor/session/binding의 별도 읽기를 확인합니다. `AUTHORITY_RECONCILE/STALE/SUPERSEDED`는 이미 정산된 후속 작업을 가리키는 늦은 응답을 뜻합니다.
- 이전 primary 종료 실패 뒤에도 정확한 cohort의 정리 증거를 관찰합니다. local release·보조 consumer·실제 child 종료·journal 정산·registry 회수가 모두 확인되면 실패한 준비의 budget만 반환합니다. 새 PASS·자동 모델 재시작은 없으며, 새 명시 시도가 별도 권한으로 quiesced host를 재개합니다. 증거 없는 local 실패는 차단을 유지합니다. `DeviceRuntimeLeaseTeardown`은 예약 직후부터 원 claim의 파괴 후 정리를 장착하고, host 배출 뒤 원 owner를 조회해 EXIT를 요청합니다. UI callback이 폐기돼도 실제 종료·정산·consumer 회수 확인이 끝나야 원 claim을 반환합니다. 이전 snapshot은 stale 완료·재개 허가만으로 지우지 않으며, 실제 `reservePreparedPair`가 quiescence를 소비한 뒤 정확한 같은 snapshot만 해제합니다.
- 과거 COMMITTED와 현재 pair 준비 증명은 별개입니다. 앱 재시작은 같은 코드·모델·기기 binding의 기록을 읽어 실제 두 모델을 새로 준비한 후 복원합니다. 화면 인계 동안 과거 기록은 보존하지만 현재 화면의 기능 gate는 닫습니다. 실제 실행 실패·미확인 회수는 명시 재점검 상태로 끝냅니다.
- `WALKSAFE_RUNTIME_BUILD_ID`는 운영 소스·자원·모델·variant 설정·의존성 설정 내용으로 생성합니다. 같은 debug Git 표지만으로 다른 코드를 같은 점검 결과로 복원하지 않습니다. 기존 release source SHA 검증은 유지합니다.
- 실제 native 생성·추론·종료는 비공개 `:vision_runtime`의 두 owner 스레드에서 수행합니다. 부모는 전처리·파서·mask decode·listener를 담당합니다. hard fault는 같은 cohort의 두 모델을 퇴역시키고 실제 child death·mapping/FD 회수 전 재할당을 막습니다. 타 모델이 남은 상태의 부분 CPU fallback을 허용하지 않습니다. API31 미만은 native 준비 전에 지원 조건에서 거절합니다.
- 정상 operation 결과, Java consumer 배출, 모델별 close, cohort 전체 실제 종료는 서로 다른 증거입니다. `VisionConsumerRegistry`, `VisionPairConsumerTransfer`, `VisionOwnerRetirement`, `CameraRuntimeHandoff`가 해당 경계를 연결합니다. Source/target 미도착·취소·지연 ACK는 원래 기한과 정확한 lease/claim만 정리합니다. 실제 종료 미확인은 실패 안내와 재할당 차단을 함께 유지합니다.
- `VisionParentReleaseTracker`는 native 미시작과 부모의 연결 정리를 구분합니다. SDK 연결을 시도했다면 false·예외 결과도 정상 unbind와 수락된 작업의 실제 `finally` 배출을 확인합니다. `actualReleasedFuture`는 물리 회수이며 `whenAvailable`은 여기에 journal 정산까지 합류합니다. 후보·재점검은 원래 기한 안에서 후자를 기다리고, 화면 파괴의 물리 정리를 저장 대기와 혼동하지 않습니다.
- 원 prepare가 미확정인 회수는 `settleNativeOpenRelease`의 cleanup 전용 권한으로 정산합니다. 원 source·lease·명령·프로세스와 실제 writer 종료, 새 파일 읽기를 확인하며, 기록 부재는 양성 `NO_NATIVE_START`에만 허용합니다. 정산이 새 PASS나 모델 실행 권한을 발급하지 않습니다. 미정산 capture는 상한 안에서 보존하고 재점검 진입은 기존 pending cleanup만 재확인합니다.
- 정확한 owner별 완료 회수 이력은16개로 제한합니다. 과거 owner의 완료 receipt는 재점검을 위한 증거일 뿐 새 owner의 할당이나 EXIT 권한이 아닙니다. 실제 모델의 생성/종료가 멈추면 자식 종료를 요청하되 GPU/커널 전체 복구를 보장하지 않습니다.
- prepared primary 종료 실패는 같은 detector의 로컬 host·모델 receipt와 해당 preparation owner의 exact retirement snapshot으로 추적합니다. `hostReleasedFuture`는 직렬 close가 캐시를 비우고 factory backing 반환을 확인한 증거이며 native 종료와 별개입니다. factory는 반환 진행 중이거나 로컬 반환 실패가 있으면 host 양성을 주지 않습니다. 기존 owner의 quiesced cleanup·동일 cohort 실제 종료·allocationAvailable까지 확인한 뒤 해당 실패 기록만 정산하고, 재개와 다음 예약은 별도의 현재 재점검 권한을 요구합니다. 검증 실패가 먼저 close를 요청한 뒤 exact rollback이 정상 no-op으로 끝나도 같은 detector·동일 receipt인 미결 기록에만 snapshot을 보강합니다. 알 수 없는 로컬 실패·다른 receipt·새 실패 기록은 과거 양성으로 해제하지 않습니다.
- 이전 회수를 기다리는 홈 화면도 `observeRuntimeCompletion`을 사용합니다. 전역 future에는 약한 화면 참조와 식별자만 남고 UI continuation은 화면 내부 map에 저장합니다. `onDestroy`가 map을 비운 뒤 늦게 회수가 끝나도 폐기한 UI 작업은 실행하지 않습니다. 화면의 수명이 끝났다는 이유로 회수 claim을 완료시키지는 않습니다.
- 거리 지원 조회는 `:metric_support`의 Session 생성 → AUTOMATIC 지원 API → close만 사용하며 resume·촬영 프레임은 없습니다. 실제 회수와 journal 정산 뒤 모델 시험을 시작합니다. 명시적 false와 설치/권한/정지/미확인 오류를 구분합니다. TTS 합성은 실제 청취, 진동 요청은 체감 확인, GPS 지원 정보는 현재 위치 품질의 증거가 아닙니다.
- 거리 조회의 부모 작업도 `MetricParentExecution`의 공용 실행기 4개로 제한합니다. bind/조회·unbind·EXIT와 조정을 분리하고 임시 스레드를 만들지 않습니다. `MetricReleaseJournal`은 정상·늦은 회수를 한 번 정산하며 실패한 옛 결과를 다시 발행하지 않습니다.

### 기기점검 화면 상태

- `NativePreviewPresentationPolicy`는 권한 확인·실행·결과 저장을 진행 중으로 분류합니다. `NativeScreenPresenter`는 이 동안 시작 버튼을 숨기고 `MainActivity`가 캡처한 단계별 진행 안내를 표시합니다. 저장 대기는 통과로 표시하지 않습니다.
- 호출어 시험의 활성화·준비/듣기 문구는 `DeviceCheckPresenter`가 결정합니다. 화면은 현재 점검이 RUNNING이며 호출어 결과가 PENDING일 때만 시험 버튼을 표시합니다. 이전 화면의 버튼 가시성에 의존하지 않으므로 화면을 다시 열어도 필요한 시험을 할 수 있고, 권한 확인·저장·시험 완료 상태에는 버튼을 노출하지 않습니다.
- 실패한 점검의 미확인 신호는 `미완료`로 표시합니다. 전달된 통과·제한 신호는 그대로 표시하고, 이 단계에서 측정하지 않는 현재 위치 수신은 `보행 전 확인`으로 구분합니다. 실제 위치 품질 검사는 보행 전 검사를 따릅니다.

### 원인 추적 로그와 복구 시험

- debug APK의 `WalkSafeDeviceCheck` Logcat과 기존 `cache/runtime-diagnostics/events.log`, `events.previous.log`에 같은 고정 형식 메타데이터를 기록합니다. `runtime_build_id`·`source_commit`으로 코드 버전을 구분하고, 파일의 `run_id`·`elapsed_ms`·생성된 `flow_id`·session/attempt 숫자로 단계 전후를 연결합니다. Logcat은 `threadtime`의 프로세스 ID와 `elapsed_ms`로 실행을 구분합니다. actor/owner/operation 원문 ID, 이미지·음성·좌표·예외 원문은 받지 않습니다. release 수집·원격 전송은 추가하지 않습니다.
- 로그는 `stage`·`outcome`·`reason`·`state`·`failure`를 구분합니다. `SAVING`은 저장 확인 대기이며 `PUBLISH/SUCCEEDED`와 다릅니다. `NATIVE_RELEASE` 완료 뒤 `ALLOCATION_AVAILABLE` 대기가 남으면 물리 회수 후 저장 정산 대기입니다. `HOST_RESUME/ACQUIRED`는 재개 허가만 받은 상태이고, 실제 receiver가 허가를 소비한 뒤에만 `HOST_RESUME/SUCCEEDED`를 남깁니다.
- 기존 단일 worker·queue64·128KiB 파일2개 상한을 재사용합니다. sink 예외·파일 실패·포화는 기능을 중단시키지 않으며, 로그는 best-effort이므로 기록 누락을 성공 증거로 해석하지 않습니다. 파일 prefix의 `dropped_total`로 누락을 확인합니다.
- `DeviceRuntimeLeaseRecoveryTest`는 파괴를 각 대기 단계에 끼워 넣어 원 claim 정산·다음 단일 획득을 검증합니다. 실제 Primary Lifecycle 회귀는 초기 drain 전 파괴와 재개 허가/예약 소비를 검증합니다. SavedResults 회귀는 실제 writer 지연, 정산 읽기 재실패, 계정·binding 변경, native/schema 차단과 실제 응답의 다른 operation ID를 검증합니다.

필터한 Logcat과 로컬 파일은 다음처럼 수집할 수 있습니다. 다른 기기가 연결되어 있으면 `adb -s <대상>`으로 대상을 지정합니다. 파일은 앱 캐시라서 운영체제가 지울 수 있습니다.

```bash
adb logcat -d -v threadtime -s WalkSafeDeviceCheck:I WalkSafeJointRuntime:I '*:S' > device-check-logcat.txt
adb exec-out run-as kr.co.hanium.dreamup.walksafe cat cache/runtime-diagnostics/events.log > device-check-events.log
adb exec-out run-as kr.co.hanium.dreamup.walksafe cat cache/runtime-diagnostics/events.previous.log > device-check-events.previous.log
```

## 변경 시 확인

- `network`, `report`, `navigation` 변경은 Gateway route·OpenAPI와 Backend consumer까지 확인합니다.
- `inference`, `depth`, `device` 변경은 모델 config, asset 계약, 안전중지·fallback 테스트를 함께 확인합니다.
- `session`, `security` 변경은 계정 전환·재시작·권한 철회·삭제·민감 저장 거부 경로를 포함합니다.
- `feedback` 변경은 TTS/TalkBack 완료 확인과 진동 정책을 실제 기기 검증과 구분합니다.

자동검사와 빌드 명령은 [테스트 가이드](../testing-guide.md)를 사용합니다. JVM·정적 테스트는 ARCore·카메라·GPS·TTS·진동·야외 보행의 실제 기기 PASS를 뜻하지 않습니다.
