# D04 디버그 위치시험 추가분 독립 검토 — D11 ops_support

- 대상: 새 F/P-D04-031~038, 해당 기능도 8개와 debug overview/sequence/state 4개. 최초 D04 검토 8기능과 별도의 추가 검토다.
- 작성자 `position_motion`과 다른 검토자다. Root가 최종 SHA를 전달했고 작성자도 동결 상태를 확인했다. 검토자는 이 보고서만 작성하며 D04 JSON/Mermaid와 제품 코드는 수정하지 않았다.
- source 기준: `/tmp/walksafe-spec-20261004-source`, 2026-10-04T10:33:40+09:00, `2e5744725a8c033a1817f3cc8a8ec679bc15ac9d` + 당시 WIP.
- 최초 JSON SHA256: `075187e0f01bac6bd932fc1ed5800581e5983eecd0425e2bf8177e4bc6268720`. 최초 판독 시작/끝에서 같은 bytes였다.
- 검토 형태: Kotlin/XML/명세/Mermaid와 선택 testcase 본문의 정적 대조. 제품·Gradle·기기·GNSS·파일 export 동작을 실행하지 않았다. Mermaid 렌더/시각 검증은 Root 담당이다.
- 최종 판정: 새 8기능의 주요 수치·입력·수명주기·파일 경계를 비교했고 최초 발견7그룹과 재검토에서 발견한 추가 상태투영1건을 모두 수정본에서 해결 확인했다. 선택범위의 남은 수정 요청은 없다. 제품 실행 또는 D04 전체 정확성의 보증은 아니다.

## 코드 경로 표기

- 아래 `Activity`/`OutdoorPositionTestActivity.kt`: `apps/android/app/src/debug/java/kr/co/hanium/dreamup/walksafe/fieldtest/OutdoorPositionTestActivity.kt`.
- `Recorder`/`OutdoorRawGnssRecorder.kt`: `apps/android/app/src/debug/java/kr/co/hanium/dreamup/walksafe/fieldtest/OutdoorRawGnssRecorder.kt`.
- `Writer`/`BoundedRawGnssWriter.kt`: `apps/android/app/src/debug/java/kr/co/hanium/dreamup/walksafe/fieldtest/BoundedRawGnssWriter.kt`.
- `Formatter`/`OutdoorRawGnssFormatter.kt`: `apps/android/app/src/debug/java/kr/co/hanium/dreamup/walksafe/fieldtest/OutdoorRawGnssFormatter.kt`.
- Manifest: `apps/android/app/src/debug/AndroidManifest.xml`. 시험 파일의 공통 디렉터리는 `apps/android/app/src/testDebug/java/kr/co/hanium/dreamup/walksafe/fieldtest/`다.
- 도식 경로는 이 산출물 루트의 `diagrams/` 아래이며, 줄 번호는 위 고정 source에만 대응한다.

## 발견사항

| ID | 종류/영향 | 대상 | 코드 근거와 수정 요청 | 상태 |
|---|---|---|---|---|
| RV-D04DBG-D11-01 | 문서 오류 / 중간 | F031~036 preconditions, 새 기능 actors | 공통 fine/activity 권한·mount/storage checkbox 문구를 F034 종료와 F035 완료본 export에도 전제한 것은 실제와 다르다. `Activity:569-594,631-713`은 이 조건을 요구하지 않는다. F036 Raw 자체 `Recorder:82-101`은 fine만 검사하며 activity/mount/storage는 caller의 수집 gate다. actors MainActivity도 debug Activity/Raw/formatter caller로 구분해야 한다. | 작성자 수정 후 해결 확인 |
| RV-D04DBG-D11-02 | 문서 오류 / 낮음 | F031 termination.timeout | “내부 타이머 없음”은 `Activity:145-163,323`의 main Handler heartbeat 1초 재게시와 모순된다. 전체 작업 timeout 부재와 periodic heartbeat 존재를 구별해야 한다. | 작성자 수정 후 해결 확인 |
| RV-D04DBG-D11-03 | 문서 과장 / 중간 | F037 steps5 | “원자 move”는 `Recorder:72`가 `Files.move(stagingFile, sessionFile)`을 호출하는 것보다 강한 보장이다. ATOMIC_MOVE/별도 crash-atomic 계약이 없으므로 “최종 파일명으로 이동 요청”으로 좁혀야 한다. flush/close/move 성공 뒤 STOPPED라는 순서는 맞다. | 작성자 수정 후 해결 확인 |
| RV-D04DBG-D11-04 | 문서 오류 / 중간 | F037 기능도·recovery | `Writer:63-72` 중복 start는 false만 반환하고 상태를 바꾸지 않는다. `Writer:88-116`의 stale append도 false만 반환한다. 최초 도식은 이들을 ERROR/STOPPED 종료상태와 섞는다. stale/중복 반환과 overflow STOPPING→승인 큐 drain→ERROR를 구분해야 한다. terminal writer는 새 입력으로 복구되지 않으므로 recovery 일반문구도 새 instance 필요와 .part 보존으로 좁혀야 한다. | 작성자 수정 후 해결 확인 |
| RV-D04DBG-D11-05 | 문서 누락 / 중간 | F034 기능도 | Raw close 실패여도 trace.stop을 시도하고 trace 실패만 pendingFinalization으로 보존하여 명시 retry한다(`Activity:595-629`). 최초 그림의 한 처리 상자→완료 또는 pending은 해당 실패/복구 분기를 충분히 표현하지 않는다. Raw 결과·trace 결과·재시도와 새 수집 차단을 명시해야 한다. | 작성자 수정 후 해결 확인 |
| RV-D04DBG-D11-06 | 문서 오류 / 낮음 | F033 기능도, debug recording state | ready/lease 부재는 조용한 return이고 정지·freshness 불충족만 3초 안내한다(`Activity:547-567`). 하나의 gate→안내로 합치지 않아야 한다. recording 상태도의 “늦은 generation→Stopping”도 current session에 영향을 주지 않는 stale completion 무시와 다르다(`Activity:381-393`, `RecordingReadiness:72-77`). | 작성자 수정 후 해결 확인 |
| RV-D04DBG-D11-07 | 명칭 명료화 / 낮음 | F038 기능도 | JSON 입력은 `Raw` marker+27 named columns라고 맞게 설명하지만 도식 “Raw 27열”은 총 셀 수를 혼동시킨다. `Formatter:39-48,101-112`는 marker 포함 28 CSV values다. 도식도 동일 표기로 통일 요청. | 작성자 수정 후 해결 확인 |

| RV-D04DBG-D11-08 | 수정본의 문서 오류 / 낮음 | F036 states.transitions | 중간판617의 실제 enum 전이는 writer header flush만으로 Raw RECORDING이 되는 듯했다. `Recorder:235-236`은 writer RECORDING이라도 registered=false면 Raw STARTING이며 `133-136`에서 native 등록까지 성공해야 RECORDING이다. 최종판36df에서 이 구분을 명시했다. | 작성자 추가 수정 후 해결 확인 |

- 이 검토의 발견은 문서 오류/과장/누락이다. 신규 제품 결함 재현을 주장하지 않는다.
- 작성자가 이미 명시한 R-D04-006(debug motion 상태가 coordinator unified motion/speed로 전달되지 않음)은 같은 코드 연결을 확인했으나 기존 이슈로 유지한다. 실제 센서/현장 성능 판정으로 확대하지 않는다.

## 기능별 대조

### F/P-D04-031 화면과 preview

- debug manifest의 exported MAIN/LAUNCHER와 orientation/screenSize configChanges를 확인했다. release/Main과 별도 source-set 진입점이라는 설명이 맞다.
- onCreate는 private noBackup root·별도 KeyStore prefix·로컬 UUID scope generation0·256MiB trace recorder를 준비하고 완료목록을 읽는다. “저장 안 함” preview는 신규 측정행을 저장하지 않는다는 의미이고 초기 디렉터리/scope 생성도 없다는 뜻은 아니다.
- 버튼 gate의 storageReady/권한/장착/동의/stopping/exporting/pendingFinalization, GPS disabled의 설정 화면 이동, 자동 재시작 없음, FLP 1000/500ms·maxAge0·fine, 모든 result.locations 순회와 generation guard를 대조했다.
- fixReady는 age0..5000ms, nonmock, finite 좌표와 finite accuracy0..30이다. 상태표시는 추정 반경이며 실제 오차/PPK 성공을 보증하지 않는다.
- 판독: `OutdoorPositionTestActivity.kt:56-326,328-332,727-773`; debug `AndroidManifest.xml:1-23`. RV-01/02와 actor 정정이 필요했다.

### F/P-D04-032 기록 readiness와 센서 입력

- Raw 미관측이면 기다리기/제한 수락 dialog, fresh fix·권한·저장동의·lease/pending 조건, 별도 coordinator와 로컬 calibration/history 초기화, trace lease를 먼저 얻는 순서를 확인했다.
- Raw start completion은 recorder identity/session/lease/collecting 확인과 readiness token 재검증 뒤 step reset→totalSteps0→tracker start→ready를 수행한다. stale completion은 새 session의 steps를 reset하지 않는다.
- accepted GNSS는 INITIALIZED/REINITIALIZED/APPLIED이며 DOWNWEIGHTED는 latest reference/보정 accepted로 다루지 않는다. latest reference는 nonmock/fresh/새 관측시각일 때만 바뀐다. history64개/5초, calibration motion freshness2초, GPS course와 chest 기준 age100ms를 확인했다.
- trace는 실제 측정시각 필터값과 replay 후속 sensor행을 분리하고 경로 매칭 결과를 만들지 않는다. step calibration은 HIGH STEP_DETECTOR 여부를 넘기며 profile 저장 API를 호출하지 않는다.
- debug motion callback은 Activity stationary만 갱신한다. heartbeat가 ZUPT를 호출해도 unified motion/speed 미연결이라는 기존 R-D04-006 한계를 보존한다.
- 판독: `OutdoorPositionTestActivity.kt:61-85,145-163,285-307,328-545`. 제품 coordinator/calibration/Android sensor wrapper 내부 전수 검토는 이번 추가범위에 포함하지 않았다.

### F/P-D04-033 checkpoint

- ready와 lease 확인 후 device STATIONARY, motion receipt age0..1500ms, candidate duration>=1500ms를 검사한다. 화면의 “3초 이상”은 안내이며 코드의 duration gate와 구분되어 있다.
- UTC anchor가 없으면 MONOTONIC_ONLY/UTC null, marker는 CP001부터 시작하고 recorder.mark 성공에만 ordinal이 증가한다. 참좌표를 만드는 기능이 아니다.
- 저장 실패는 stopEverything으로 이어진다. RV-06은 ready 부재의 조용한 return과 stationarity 실패의 안내를 나누는 도식 수정이다.
- 판독: `OutdoorPositionTestActivity.kt:285-296,523-537,547-567`.

### F/P-D04-034 종료와 finalization

- stopping 재진입 반환, readiness invalidation과 lease detach·generation 증가를 먼저 수행한다. heartbeat와 FLP callback 참조, step/motion/quality/orientation, calibration/history, KEEP_SCREEN_ON을 정리한다.
- FLP remove Task의 성공 ack를 보존/기다리지 않는다는 문서 한계가 맞다. Raw close callback이 온 뒤 trace.stop을 호출한다. Raw 실패에도 trace 종료를 시도한다.
- trace 실패는 pendingFinalization으로 새 preview를 막고 사용자 retry에서 같은 lease.stop을 다시 시도한다. Raw 실패는 확정 txt 대신 private .part를 보존하고 export하지 않는다.
- onPause는 collecting 또는 lease가 있을 때 종료하고 onResume은 상태표시만 한다. onDestroy의 exportExecutor.shutdown은 이미 제출된 IO 즉시 취소가 아니다.
- 판독: `OutdoorPositionTestActivity.kt:569-629,715-725,755-767`. D06 recorder의 crash 복구/helper 내부는 여기서 다시 감사하지 않았다. RV-01/05 수정이 필요했다.

### F/P-D04-035 완료기록과 SAF export

- COMPLETED session만 선택하며 Raw 파일은 확정 `.txt` 존재를 확인한다. 새 export는 active lease/stopping/unclosed Raw/exporting/pendingExport를 차단하지만 권한·checkbox를 재요구하지 않는다.
- pending request에는 session/file/title을 고정한다. saved state는 session/Raw 여부를 보존하고 재생성 때 현재 완료목록/실제 파일을 재검사한다.
- picker 취소는 원본을 남기고 pending만 해제한다. 성공 URI는 single executor에서 `wt` output/use로 Raw copy 또는 D06 trace.export를 실행한다. 실패하면 외부 target delete를 시도하고 실패시 부분파일 안내를 한다. 로컬 완료본을 삭제하지 않는다.
- onDestroy 후 작업은 끝날 수 있고 UI render는 isDestroyed로 억제한다. SAF provider의 live 동작과 실제 output/delete 결과는 미검증이다.
- 판독: `OutdoorPositionTestActivity.kt:184-194,631-713,722-725`. RV-01의 공통 전제 정정이 필요했다.

### F/P-D04-036 Raw GNSS recorder

- canonical final/staging path를 private noBackup 내부로 제한하고 기존 final을 덮지 않으며 staging CREATE_NEW를 사용한다. 파일 자체는 이 클래스에서 암호화하지 않는다.
- fine 권한 확인→writer header flush 완료→현재 token→native register 성공 후에만 start(true)다. API31+ fullTracking request/main executor, 이전 main Handler, registration 실패/unsupported/location disabled→writer.fail 경로를 확인했다.
- Raw clock와 receiver fields의 nullable 값, ADR bit1, code null/blank/UNKNOWN, UTC fallback 카운트를 batch에 넣는다. 빈 epoch와 stale generation은 기록하지 않는다.
- stop은 writer admission부터 무효화하고 unregister한 후 실제 close 결과를 기다린다. publish는 AtomicBoolean으로 main 메시지를 합치고 warning은 error/drop→missingFullBias→zero measurement→zero ADR→missingCode→UTC fallback 우선이다.
- unregister는 listener 참조부터 제거하고 runCatching으로 예외를 삼키며 성공 ack/재시도 handle을 보존하지 않는다는 한계가 맞다.
- 판독: `OutdoorRawGnssRecorder.kt:1-256` 전체. Android native 등록의 실제 기기 성공·관측 연속성은 미검증이다. RV-01/03 정정이 필요했다.

### F/P-D04-037 bounded writer

- 단일 IO thread와 admission monitor 분리, immutable bytes copy, volatile snapshot을 확인했다. 이미 write/flush 중인 batch도 pending16개/1MiB에 포함한다. file ceiling64MiB는 F036에서 넘기며 writer는 footer256byte를 예약한다.
- admitted batch의 write+flush가 끝난 뒤에만 pending 감소와 측정/epoch/ADR 카운트를 올린다. queue/file overflow는 새 batch 측정수를 drop에 합산하고 stop generation을 바꾸며 승인된 큐는 drain한다.
- I/O 예외는 남은 pendingMeasurements를 dropped에 넣고 queue를 비운다. finally flush/close와 error없을 때 publish를 수행한 뒤 closed/STOPPED 또는 ERROR와 onClosed를 게시한다. sink IO timeout은 없고 stop 자체는 nonblocking이다.
- 중복 start와 stale append의 반환, terminal instance 재시작 불가를 별도로 그려야 한다. 파일 이동의 crash 원자성 보장은 코드에 없고 publish callback 성공 여부만 검사한다.
- 판독: `BoundedRawGnssWriter.kt:1-240` 전체, `OutdoorRawGnssRecorder.kt:54-75,254` 연결. RV-03/04 수정이 필요했다.
- 시험 기대: `BoundedRawGnssWriterTest.kt:89-107`는 overflow 누락5개·INCOMPLETE footer·ERROR·미게시를, `131-158`은 blocked close/publication 동안 STOPPING/closedfalse를 assertion한다. 해당 본문만 읽었고 SlowSink/공통 helper 전체 및 실행은 미검증이다.

### F/P-D04-038 formatter

- header의 27 named fields와 row의 `Raw` marker, nullable empty, comma/CR/LF→space 처리를 대조했다.
- FullBias와 leap0..64, finite offset/bias, subtractExact 성공이면 GPS epoch+floor millisecond-remainder 보정−reported leap을 사용한다. 조건실패는 measured elapsed0..callback elapsed에 따라 DEVICE_UTC_PROJECTED 또는 DEVICE_UTC_CALLBACK으로 나뉜다.
- 없는 leap/code/carrier를 발명하지 않는다. outer epoch 합을 일반 Long으로 계산하므로 모든 극단값 overflow를 보장하지 않는다는 문서 한계도 맞다. 지속 상태·자원·취소가 없는 순수 변환이다.
- 판독: `OutdoorRawGnssFormatter.kt:1-116` 전체. RV-07 표기 정리 요청.
- 시험 기대: `OutdoorRawGnssFormatterTest.kt:22-28`는 빈 receiver field와 DEVICE_UTC_PROJECTED/observed callback UTC 보존을 assertion한다. fixture/helper 및 실제 시험은 미검증이다.

## 검토 범위와 한계

- 실제 읽은 Kotlin body: Activity56~773(앞선 import1~55는 미검토), Recorder1~256, Writer1~240, Formatter1~116. debug Manifest1~23. 합계4개 Kotlin 소스의 주요 본문을 읽었으며 외부 호출 대상 전체를 읽었다는 뜻은 아니다.
- 읽은 도식: `D04-F031.mmd`부터 `D04-F038.mmd`, `D04-debug-state-recording.mmd`, `D04-debug-state-rawwriter.mmd`, `D04-debug-sequence.mmd`, `D04-debug-overview.mmd`.
- F001~030 기존영역과 기존 D03 교차검토 결과는 이번에 재검토하지 않았다. 실제 지상 오차, GNSS/ADR/PPK 가능성, 백그라운드/화면회전/프로세스사망·권한취소, 파일 provider 취소·삭제, UI 접근성/시각결과는 실행하지 않았다.
- raw native callback과 file sink의 실제 fault가 발생한 시험을 수행하지 않았다. 소스의 상태기계와 testcase 기대만 기록한다. 전체 제품 완료 판정은 Root가 통합한다.

## 수정본 재확인

- 중간 수정본 JSON SHA256 `617e940449272cac0c4947b3eef7e1d4b86b2b360bdc14d598e9090f9fe59b10`을 읽기 전후 동일하게 확인했다. 새8기능의 actors/preconditions/termination 및 F036~038 상태/단계를 읽고, F033/F034/F037/F038 기능도와 recording 상태도를 소스에 재대조했다. 최초7그룹 수정은 확인했다.
- 중간판의 F036 실제 Raw 상태투영 문구에서 writer와 recorder의 STARTING/RECORDING 경계를 추가로 구분하도록 요청했다(RV-08). 작성자가 단일 JSON 문구를 고쳤고 최종판을 다시 읽어 `header flush AND native registered=true` 조건을 확인했다. 도식은 중간판과 동일하다고 작성자가 확인했다.
- 최종 JSON SHA256: `36df95299248655de7ec1022731dc08fb8100b14cf80552bb5fefc9f10451841`. 최종 재판독 전후 동일했다.
- writer가 보고한 Mermaid parser 통과는 작성자 검사 보고다. 검토자가 Mermaid 렌더나 Android 실행을 했다는 뜻이 아니며, Root의 통합 렌더/완료 판정은 별도다.

### 최종 추가분 도식 SHA256


- `D04-F031.mmd`: `43ac044a1f8738a07277c91299f61919d586d59aa859f7328caa23549556cde9`.
- `D04-F032.mmd`: `68a3b9d5d3d32322c123eb535d006a402927110de6e31af36cad1d3b094fc8cc`.
- `D04-F033.mmd`: `7cb1e22e84ff631c4dfe1a3e1fe2acdbc9b73b4735dd7dc45889399286ef94d8`.
- `D04-F034.mmd`: `4260750107d9a30d664766d6a39d5b24660341e730db4103b56f36f970f52ea3`.
- `D04-F035.mmd`: `6ec6bcf7b9c081b64f2a139f7b8611ae9a059417b4874f46f7b77574b3a9f810`.
- `D04-F036.mmd`: `74b689c707f01c0be11f95dd6c1d5d0cc2d3b3fce4c27451e3d33f95f19bb462`.
- `D04-F037.mmd`: `6f118799f39f53dd498b569a655175349225c0c51a8fe98c3a671262a0a313b6`.
- `D04-F038.mmd`: `bdcacc1f52b0b5967e755346934bcc8ffe05406e1c809bcc24913e9a1fa4db87`.
- `D04-debug-state-recording.mmd`: `d1d9d1e44bb5b62136a2ccd3fd5929905ed0ec9f38ecce9ef0fda93a6947c548`.
- `D04-debug-state-rawwriter.mmd`: `97ef986deb592c5acc33f377cd96b6bc6fbe43faa6f3b3fcbe75f5d5e0863c08`.
- `D04-debug-sequence.mmd`: `0251cc939b7c6d9aaa81781b9ae39b529ae142c9d7362d45a0196d1930eff2cc`.
- `D04-debug-overview.mmd`: `0bac08225204d268249f0b64a3fcf65e133593210c866b844ee2396250f9f15e`.
