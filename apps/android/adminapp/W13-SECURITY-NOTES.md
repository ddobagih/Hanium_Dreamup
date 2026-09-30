# W13 Android 관리자 보안 변경 경계

- 대상: 2026-09-17 종합분석 S18 및 W13. Android 관리자 앱만 수정한다.
- S18-15~17: 로컬 잠금과 불변 UI 스냅샷을 직렬 네트워크 작업 잠금에서 분리한다. 화면 이탈은 서버 세션 폐기가 아니다. 기존 세션 재개에는 기기 인증과 해당 세대의 서버 상태·현재 세션·기기 재확인이 필요하다. 이전 작업의 재인증은 재사용하지 않는다.
- S18-18: Activity 종료 시 로컬 권한은 즉시 차단한다. 이미 진행 중인 HTTP 요청은 서버에서 처리됐을 수 있으며 자동 재전송·자동 폐기하지 않는다. HTTP 클라이언트의 메모리 결속 정리는 실행 중 작업이 끝난 후 수행될 수 있다. 전체 요청 취소·deadline은 이번 변경의 완료 범위가 아니다.
- S18-12: 오류 응답도 성공 응답과 동일한 엄격 JSON 검사 후 해석한다.
- S18-26: 외부 보관 확인은 사용자의 진술 기록이며 실제 백업 복원 검증이 아니라는 문구로 수정한다.
- S18-27: 같은 프로세스·세션의 보관 확인 재시도는 미확정 참조값을 재사용한다. 프로세스 종료를 넘는 보존은 추가 계약이 필요하다.

## 보존하는 기존 계약

- SAF 생성/기존 문서 선택과 기기 인증 화면은 의도적 전환으로 구분한다. SAF 복귀의 세션·신고 revision·패키지 증명·해시 확인은 유지한다.
- 로컬 잠금은 세션 변경으로 처리하지 않으므로 확정된 PATCH의 GET-only 복구 정보와 수동 전달 시도 식별자를 초기화하지 않는다.
- 서버에서 확정된 변경 응답은 수명주기 이탈 때문에 미확정 오류로 바꾸지 않는다. 화면 콜백 적용과 서버 처리 사실은 구분한다.
- 기기키, 계정, 사용자 데이터, release 운영 비활성 플래그를 변경하지 않는다. 자동 기관 전송도 추가하지 않는다.

## 미해결·외부 의존

- S18-1~3: Keystore 사용자 인증 강제·보안 수준·키 교체는 서버 등록 전환과 관리 단말 정책이 필요하다. 이번 기기 인증 UI는 키 자체의 인증 강제나 하드웨어 보안 증명이 아니다.
- S18-4,19~23,36,43,48: 시계 오차 계약, 전체 요청 deadline, 가변 인증 버퍼, 비동기 Keystore/이미지 디코딩, 초기화 오류 구분, 검수 시도 내구성, 최신 이력 UX, Activity 책임 분리는 남는다.
- S18-5~11,13~14,24~25,28~35,37~41,44~47: 서버·운영·정책·배포 또는 별도 담당 검토가 필요하다. 이 앱 변경만으로 해결했다고 판단하지 않는다.
- S18-38,42: 검수·수동 전달·기관 확인의 구분과 실제 증거 수준은 유지한다. 새 기관 사실 확인 기능을 구현한 것은 아니다.

## 시험과 실기기 경계

- 기본 회귀: `ANDROID_HOME=/home/ddobagi/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :adminapp:testDebugUnitTest --rerun-tasks --no-build-cache --no-daemon` (`apps/android`에서 실행).
- 신규 `AdminLocalSessionLifecycleTest`: 느린 인증/갱신과 UI 조회·잠금, 오래된 로그인/복구/재인증, 종료 시 차단, 서버 폐기 후 재개 거부, SAF 세션 결속, 보관 참조값 재시도, 오류 JSON 및 Activity 연결 검증.
- 기존 `AdminReportWave5Test`: 확정된 상태 변경의 GET-only 복구, SAF 길이·해시·서버 증명 및 stale generation 반례를 함께 실행한다.
- 실행 결과는 부모 통합 보고에서 취합한다. 문서에 명령이 있다는 사실은 PASS 증거가 아니다.
- 실기기 미확인: API 26/최신 Android의 PIN·패턴·비밀번호/생체인증 화면, 인증 취소·홈·화면 회전·프로세스 종료, SAF 선택 중 홈 이동 후 복귀, 서버 세션 원격 폐기, 장시간 느린 통신과 화면 응답성. 기기 잠금이 없으면 세션 재개는 fail-closed다.
- 최종 APK 빌드·설치·운영 서버·PostgreSQL·전체 분실 복구 훈련은 부모 및 해당 담당의 별도 확인 범위다.

## 승인 후 종료 경쟁 보정과 실제 실행: 2026-09-17 18:10 KST

- 사용자가 추가 재현·수정을 명시 승인했다. 앞선 `LocalOperation.close()` 보정 승인 대기 상태는 해제됐으며, 해당 경쟁 구간을 실제 재현한 뒤 수정했다.
- 수정 전 실제 로그인 종료를 JDI로 첫 세대 판정 직후 정지했다. 그 사이 화면 이탈·재진입을 수행하면 `retained=false`인데도 오래된 인증으로 `refresh()`가 성공하여 접근이 다시 열렸다. 운영 코드에 시험용 훅을 추가하지 않았다.
- 별도 단위 반례 5개 중 2개도 수정 전에 실패했다. 지연된 로그아웃 정리가 후속 요청의 기존 인증 사용을 막지 못했고, 새 로그인 완료 시 새 세션까지 지우는 경우였다.
- 보정: 최종 세대 재검사·상태 공개·작업 잠금 해제를 동일한 짧은 수명주기 임계구역으로 결속한다. 정리가 필요하면 클라이언트 정리는 임계구역 밖에서 실행하고 다시 세대를 검사한다. 미처리 정리는 다음 작업의 실행 전에 처리하며, 이미 처리된 정리를 늦게 중복 적용하지 않는다.
- 재개 시 기기 인증·서버 재확인이 성공해도 최종 작업 종료 전에는 로컬 잠금을 해제하지 않는다. 최종 스냅샷 공개와 잠금 해제를 함께 수행한다. 인증 취소 시 보관된 관리자 ID·세션·기기 목록·재인증 상태는 공개 스냅샷에 나타나지 않는다.
- 보정 후 같은 JDI 반례: `lifecycle_monitor_held=true`, `BACKGROUND retained=true`, `RESULT PASS`. 배경 전환 전에 원자적으로 공개된 세션은 보존하되 잠금 뒤에 숨겨지고, 명시적인 확인·서버 재검증 후 같은 세션으로 재개된다.
- 최종 소스에서 전체 관리자 단위시험을 `--rerun-tasks --no-build-cache --no-daemon`으로 재실행했다. **43 suites, 302 tests, failures/errors/skipped 모두 0, Gradle 23 tasks 실제 실행**. XML 실행 시각은 `2026-09-17T09:10:44.518Z`부터 `09:10:45.484Z`다.
- 포함된 회귀: `AdminLocalOperationCloseTest` 5개, `AdminLocalSessionLifecycleTest` 13개, `AdminReportWave5Test` 18개. 느린 클라이언트 정리 중 비차단 스냅샷·즉시 로컬 잠금, 취소 상태 은폐, SAF 세션 결속과 확정된 PATCH의 GET-only 복구가 포함된다.

### 대상 소스와 재현 명령

- 제품 소스 변경: `src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java`만 추가 보정했다. SHA-256: `9968808e944b6a93bbd1b3b09b80885a2bcdb40b7f65f0f0fbdde966cea51d3a`.
- 신규 시험: `src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminLocalOperationCloseTest.java`, 같은 경로의 `AdminLocalOperationCloseRaceProbe.java`, `tests/close-race/CloseRaceDebugger.java`.
- `apps/android`에서 위 전체 단위시험으로 테스트 클래스를 컴파일한 후 다음 명령으로 실제 종료 경계 반례를 재현할 수 있다. 호스트 JDK 21의 JDI를 사용하며 APK·폰·운영 서버는 사용하지 않는다.

```bash
mkdir -p adminapp/build/w13-close-race
/usr/lib/jvm/java-21-openjdk-amd64/bin/javac --add-modules jdk.jdi -d adminapp/build/w13-close-race adminapp/tests/close-race/CloseRaceDebugger.java
timeout 25s /usr/lib/jvm/java-21-openjdk-amd64/bin/java --add-modules jdk.jdi -cp adminapp/build/w13-close-race CloseRaceDebugger
```

### 실행 근거와 남은 범위

- 수정 전: `build/w13-close-race-red-debugger.log`, `build/w13-close-race-red-unit.log`.
- 수정 후: `build/w13-close-race-green-debugger.log`, `build/w13-close-race-final-unit.log`, `build/test-results/testDebugUnitTest/TEST-*.xml`.
- S18-15~18 중 이번 로컬 수명주기·종료 경쟁 보정과 JVM 회귀는 수행했다. HTTP 전체 deadline·실행 중 통신 강제 취소, 실기기 UI/Keystore 동작, 서버 세션의 실제 운영 폐기까지 검증한 것은 아니다.
- 앞선 S18-12·26 수정은 보존하고 전체 회귀에 포함했다. S18-27은 여전히 같은 프로세스·세션 범위다. 위 미해결·외부 의존 목록을 닫지 않는다.
- Android API 26/최신 기기의 인증 취소·홈 이동·회전·프로세스 종료·SAF 복귀, 실제 원격 세션 폐기, 최종 APK·운영 PostgreSQL·전체 분실 복구는 미검증이다. 자체 회귀시험은 독립 검토를 대신하지 않는다.
- 이번 보정에서도 일반 앱·서버·폰 DB·설치 앱·계정 데이터를 변경하지 않았다. `adb`, APK 설치, 폰 DB 조작, `git`을 실행하지 않았다. 최종 W13 통합·전체 작업 완료 판정과 daylog 취합은 부모 Root 담당이다.


## S18-1~48 항목 대조 및 후속 UI 변경: 2026-09-17

이 절과 `/home/ddobagi/Hanium_chat_review_2026-09-17/progress-slices/S18.jsonl`이 앞선 잔여 목록의 최신 대조 상태다. 앞선 실행 로그/해시/302 결과는 과거 실행 버전의 사실로 보존하며 후속 소스 검증으로 확장하지 않는다.

### 항목별 상태

- 48개를 개별 기록: DONE 4, PARTIAL 10, OPEN 26, EXTERNAL_VALIDATION 8, NOT_APPLICABLE 0.
- DONE은 S18-12(엄격 오류 파싱), 16(지연 인증 결과 세대 결속), 17(snapshot 비차단), 45(문서 정합성)이다. S18 전체 완료가 아니다.
- P1 잔여인 S18-1/3(인증 키·회전), 4(시계 계약), 6(보안 API 기기 증명), 29(분실 처리 재인증), 35(검토 revision), 36(검토 멱등 영속 복구), 40/41(전달 정정·과거 제출 관찰)은 계약/정책 의존과 코드 잔여를 구분해 OPEN으로 기록했다. 서버 단독 항목도 backend 담당 결과 없이 완료 처리하지 않았다.
- S18-36은 `AdminReviewDecisionAttempt`가 메모리에만 존재하며 `AdminOperationsApi.ReviewHistoryItem`에 `decision_id`가 없어 현재 GET으로 정확한 시도를 확정할 수 없다. 영속 잠금만 추가해 해제 불가능하게 만들거나, 새 ID 자동 재전송으로 우회하지 않았다.
- S18-26/28/30/32/37/42/44/46/47의 외부 운영·기기·DB·기관 증거를 앱 코드/JVM 성공으로 대신하지 않는다. 실제 백업 검증/정기 정책/기관 수신 사실을 새로 만들지 않았다.

### 이번 실제 적용

- `AdminBoundaryActivity.java`: S18-21 키 준비를 worker로 이동하고 겹친 Activity의 초기 키 생성을 직렬화했다. 준비 중 UI는 잠기며 완료 시 Activity 종료/정지 상태를 확인한다.
- S18-22: 기존 worker에서 이미지 크기 검사·sample decode를 수행한다. 임시 배열과 실패 bitmap 정리를 유지하고, UI 채택 전에 foreground/local lock/report/content/session/device/expiry/generation을 재검사한다.
- S18-23: 키 준비 실패와 client/화면 상태 초기화 실패를 구분한 고정 안내. 원문 예외는 노출하지 않는다.
- S18-8/38: 직전 TOTP 재사용 금지 안내와 검토·처리·전달의 상태 구분 문구. replay 거부/서버 상태 전이 정책은 변경하지 않았다.
- `README.md`: 전체 구현 패널과 FP-008 하위 API 목록을 구분하고 키 회전 미구현·custody reference 수명·기존 시험/새 미검증/실기기 경계를 정정했다.
- `src/test/java/kr/co/hanium/dreamup/walksafe/admin/AdminUiThreadBoundaryStaticTest.java`: 아래 6개 회귀시험 추가. 정적 배치/보안 경계 시험이며 Android 실행 시험이 아니다.
- `keyPreparationRunsOnWorkerAndOverlappingActivitiesSerializeCreation`
- `initializationRejectsDestroyedActivityAndKeepsStoppedActivityLocked`
- `keyFailureAndClientInitializationFailureHaveSeparateSafeMessages`
- `imageDecodePrecedesUiDispatchAndRetainsBoundedMutablePixelCleanup`
- `uiOnlyAdoptsCurrentForegroundEvidenceAndDestroysStalePixels`
- `totpReplayAndManualWorkflowStatesRemainExplicitWithoutClaimingProof`

### 검증 및 보존 경계

- 기존 실제 실행: 302 tests / 43 suites, 실패·오류·skip 0, 23 tasks 실행. `build/w13-close-race-final-unit.log`와 red/green JDI 근거를 그대로 보존했다.
- 이번 후속 소스의 단위시험/컴파일/APK: **NOT_RUN**. 부모의 직렬 Gradle 통합 지시에 따라 별도 Gradle/컴파일/설치/adb를 실행하지 않았다. 302개 및 기존 APK는 새 Activity 수정의 통과 근거가 아니다.
- 부모 실행 요청 명령(이 worker는 실행하지 않음): `ANDROID_HOME=/home/ddobagi/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :adminapp:testDebugUnitTest :adminapp:assembleDebug --rerun-tasks --no-build-cache --no-daemon --max-workers=1`, 작업 디렉터리 `/home/ddobagi/Hanium_Dreamup/apps/android`.
- 기존 `AdminLocalSessionLifecycleTest` 13개, `AdminLocalOperationCloseTest` 5개, SAF/확정 mutation recovery/원본 증거 회귀를 포함한 전체 admin unit을 마지막 소스로 재실행해야 한다. 신규 6개가 포함되어도 실행 로그 없이는 308 통과를 주장하지 않는다.
- 실기기 필수: 느린 최초 KeyStore 초기화 중 회전/홈/종료, 큰 원본 decode 중 홈/회전/만료/세션 변경, 기기 인증 취소 후 상태 은폐, SAF 선택/취소/복귀, 확정된 pending delivery/status GET 복구.
- `AdminSecurityController.LocalOperation.close` 경쟁 보정·비차단 snapshot·즉시 로컬 잠금·SAF 예외는 변경하지 않았다. 운영 API/서버/일반 앱/키 정책/release flag/기존 폰 DB를 변경하지 않았다.
- DAYLOG/AGENTS/원문/기존 CSV trace와 기존 test/APK 산출물은 수정하지 않았다. 전체 통합·최종 완료 판정과 daylog 취합은 Root 담당이다.
