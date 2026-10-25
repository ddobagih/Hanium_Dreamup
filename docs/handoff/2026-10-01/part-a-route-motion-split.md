# A 총괄 잔여120개 추가 분할 — 담당 전체 DONE까지 지속

기준: 2026-10-01 08:30 KST, 원장 rev126. 사용자 직접 요청: “지금 하나의 세션이 120개 맡았잖아. 이 세션 또 2개로 분할할래. 이번에는 done까지 진행하라는 거 빼먹지 말고”. 기존 총괄의120개만60+60으로 나누고 인식50개·B188개·완료19개·원문 성공 조건은 바꾸지 않는다.

## 역할과 종료 조건

- 기존 세션 「한이음 21개 분야 개선 현황 파악」(`01a0dc57-85bd-7aa1-b23e-e02463cd8c1b`): 보행·출력·준비60개 담당과 A 전체189개에 대한 통합·원장 판정·공유 앱 시험 책임 유지.
- 새 세션 「한이음 A 경로·위치·이동 60개 DONE까지」(`01a0f4a8-576c-7442-8d4e-2e27f592302c`): 경로·방향·위치·정지/이동60개 담당. 앱에서 별도 세션이며 대화·Goal을 자동 공유하지 않는다.
- 기존 인식 세션 「한이음 A 인식·카메라·추적 개선」(`01a0f309-8e1c-7413-b647-0f4b469a4ffd`): 인식50개와 기존 파일 소유를 그대로 유지한다.
- **양쪽60개 세션 모두 담당 전체가 원문 성공 조건을 충족하여 실제 DONE으로 통합 판정될 때까지 계속 진행한다.** 첫 묶음·계획·시험 일부·인계 완료만으로 세션 작업을 종료하지 않는다. 가능한 다음 항목이 있으면 이어간다.
- 실제 기기/현장/외부 증거가 필수인 항목은 합성 시험으로 DONE 처리하지 않는다. 필요한 행동과 조건을 보고하되 독립적으로 가능한 다른 항목을 계속한다. 지속 Goal의 blocked/paused/complete 변경은 해당 도구 규칙을 따른다.
- 새 세션은 배정된60개 완료를 objective로 지속 Goal을 생성하고 get_goal 결과를 자신의 status에 기록한다. token budget은 사용자가 요청하지 않았으므로 설정하지 않는다. 목표가 이미 있으면 중복 생성하지 않는다.
- 기존 총괄은 현재 Goal을 조회한다. A 전체 목표는 그대로이며 own60개 완료 뒤에도 두 담당의 실제 연결·필수 검증·원장 판정이 남으면 전체 Goal을 완료하지 않는다. 기존 paused Goal의 재개가 제공된 도구로 불가능하면 내부 저장소를 조작하거나 미완료 Goal을 가짜 complete로 닫지 말고 정확한 상태·사용자 필요 조작을 기록한다. 담당 DONE까지 진행 지시는 metadata와 구분해 실제 가능한 작업을 계속한다.

## 정확한 담당 ID — 기존 세션60개

- S03: S03-1, S03-2, S03-8, S03-14, S03-15, S03-19, S03-23, S03-24, S03-25, S03-26, S03-27
- S13: S13-5, S13-7, S13-9, S13-12, S13-13, S13-14, S13-17, S13-18, S13-22, S13-24, S13-25, S13-26, S13-27, S13-28, S13-30, S13-31, S13-32, S13-33, S13-34, S13-35, S13-36, S13-37, S13-38
- S14: S14-2, S14-5, S14-9, S14-10, S14-11, S14-14, S14-15, S14-16, S14-17, S14-19, S14-21, S14-23, S14-24, S14-26, S14-27, S14-30, S14-31, S14-35, S14-39, S14-40, S14-41, S14-43, S14-44, S14-45, S14-46, S14-48

## 정확한 담당 ID — 새 경로·위치 세션60개

- S07: S07-3, S07-11, S07-12, S07-22, S07-25, S07-26, S07-31, S07-38, S07-43
- S08: S08-7, S08-9, S08-10, S08-11, S08-14, S08-15, S08-20, S08-28, S08-31, S08-33, S08-34, S08-36
- S09: S09-15, S09-16, S09-30, S09-33, S09-38, S09-39, S09-41, S09-52, S09-57
- ADD-MOTION: ADD-MOTION-001, ADD-MOTION-005, ADD-MOTION-006, ADD-MOTION-007, ADD-MOTION-008, ADD-MOTION-009, ADD-MOTION-010, ADD-MOTION-011, ADD-MOTION-013, ADD-MOTION-020, ADD-MOTION-024, ADD-MOTION-027, ADD-MOTION-032, ADD-MOTION-033, ADD-MOTION-035, ADD-MOTION-037, ADD-MOTION-044, ADD-MOTION-045, ADD-MOTION-048, ADD-MOTION-051, ADD-MOTION-056, ADD-MOTION-058, ADD-MOTION-060, ADD-MOTION-063, ADD-MOTION-064, ADD-MOTION-066, ADD-MOTION-067, ADD-MOTION-068, ADD-MOTION-069, ADD-MOTION-070

두 집합은 중복0·합계120이며 인식50개·DONE19개와도 겹치지 않는다. 시작 source는 기존 A189 고정목록과 rev126 최신 완료19개다. 후속 canonical 변경은 기존 총괄의 실제 판정을 따른다.

## 실제 저장소와 파일 소유

실제 경로는 `/home/ddobagi/Hanium_Dreamup`, 원본 사용자 앱 `apps/android`다. `temp` HEAD `2e574472` 이후 로컬 WIP를 보존한다. 새 worktree/checkout/reset/stash/일괄 commit/push를 수행하지 않는다.

- 새 담당의 이양 경로: 사용자 앱 `navigation/positioning/**`, `navigation/Route*.kt` 단 `RouteInstructionSpeechDelivery.kt` 제외; `Walking*.kt`, `LocationPolicy.kt`, `AndroidStepTracker.kt`, `AccelerometerStepPeakDetector.kt`, `AndroidEarthOrientationTracker.kt`, `StepLengthEstimator.kt`, `UserMotionEstimate.kt`, `AndroidDestinationSearchLocationSource.kt`, `BackendWalkingRouteClient.kt`, `EncryptedRouteSnapshotStore.kt`, `PendingExplicitRouteStart.kt`와 대응 시험. 모든 `MainActivity*.kt` 시험 및 기존 인식 담당 소유 시험은 제외.
- 기존 총괄 유지: `MainActivity.kt`와 Main 시험, `device/**`, A 소유 보행 `session/**`, `feedback/**`, `RouteInstructionSpeechDelivery.kt`, `WalkSessionVoiceControlPolicy.kt`, `GuidanceStopConfirmationPolicy.kt`, `CurrentTrackedFrameEvidence.kt`, `strings.xml` 및 대응 시험. Route cue 발화/ACK helper는 총괄 소유, 새 담당의 Navigator 연결 요청은 계약으로 합의한다.
- 인식 담당의 `inference/**`, `depth/**`, 지정 Tactile/Crosswalk/CameraTest와 그 시험은 양쪽 모두 읽기 전용이다. `RouteNavigatorCrosswalkOverviewTest.kt`는 기존 인식 소유이므로 새 Route 시험 prefix에도 불구하고 제외한다.
- B의 voice/account/network/report/rawcollection/security/fieldlog/debuglog·최초 실행/동의/인증/삭제 session·관리자/Gateway/Backend·공통 build/manifest/model/asset/검사도구 경계와 기존 navigation 예외는 그대로 따른다.
- 예상하지 못한 교차 파일은 직접 수정하지 않고 해당 소유자에게 최소 계약/패치/시험을 인계한다. 같은 파일은 한 번에 한 작성자만 유지한다.

## 현재 작성자 종료와 이양 절차

1. 기존 총괄은 새 담당60개 범위의 자신의/하위 현재 쓰기와 공유 시험을 확인한다. 이미 시작한 반례·최소 수정은 WIP/원출력과 함께 보존하며 이전 작성자의 실제 쓰기 종료를 확인한다.
2. 기존 총괄만 `/home/ddobagi/Hanium_Dreamup/work/part-a-route-motion-20261001/root-handoff.md`에 **ROUTE_MOTION_WRITERS_ENDED**와 **ROUTE_MOTION_OWNERSHIP_RELEASED**를 기록한다. 실제 종료한 작성자·파일·남은 현재 반례·공유 빌드 여부를 짧게 남긴다. 종료하지 않은 파일은 예외로 표시하고 넘기지 않는다.
3. 새 담당은 이 실제 종료/이양 확인 전 제품·시험을 수정하지 않는다. 원문·코드·기존 근거를 읽고 반례 설계를 준비한다. 확인한 후 이양 경로만 쓰고 자신의 status에 정확한 쓰기 파일을 기록한다.
4. 기존 총괄/하위는 이양된 경로를 이후 읽기 전용으로 유지한다. shared Gradle/컴파일 전 세 세션의 제품·시험 쓰기 종료와 대상 동결을 확인한다.

## 첫 묶음과 검증·인계

- 새 담당은 기존 총괄이 준비 중인 ADD-MOTION-007 실제 GNSS ID/receipt 보존, ADD-MOTION-008 실제 step 입력, S09-52 날짜변경선/좌표 규약 반례·WIP를 먼저 이어받는다. 동일 반례를 재사용하고 완료된 부분을 다시 구현하지 않는다. 기존 총괄은 S13-5 위험 악화 선점, S14-41 준비 상태/발열 제한, S03 잔여를 계속 맡는다.
- S09-52의 Tactile 파일 연결은 인식 담당의 기존 쓰기와 조율하며 Main 연결은 기존 총괄만 쓴다. 임계값·승인 정책·원문 완료 기준을 임의로 완화하지 않는다.
- 원문 → 현행 반례 → 최소 구현 → 동일 직접 시험 → 필요한 다른 인스턴스 검토 → 실제 연결 → 원문별 판정. 사용자 범위 안의 가역 수정·재검증은 질문 없이 진행한다.
- 모든 공유 Gradle/앱 빌드는 기존 총괄 단독. 새 담당은 정확한 시험·예상 결과·쓰기 종료/동결을 status에 기록하고, 대기 중에는 다른 독립 원문을 읽기 조사한다. 동결한 파일을 시험 종료 전 다시 쓰지 않는다.
- 새 담당 단독 공유 상태: `/home/ddobagi/Hanium_Dreamup/work/part-a-route-motion-20261001/status.md`. 기존 총괄은 묶음 시작·통합 시 이 파일과 인식 status를 읽고 요청 시험·Main 연결·원장 판정을 처리한다.
- canonical CSV/요약 JSON·A 전체 계획·공통 daylog·기존 두 세션 문서·이 문서는 설정 인계 이후 기존 총괄 단독 작성. 새 담당은 완료 후보와 직접 근거·필수 잔여를 자신의 status에 제출한다.
- 세 세션 모두 다른 대화로 임의 메시지 전송 권한을 새로 얻지 않는다. 사용자 승인 없는 메시지 대신 파일 인계를 사용한다. 초기 분담·재개 전달은 사용자의 이번 직접 요청을 받은 설정 세션이 수행한다.
- 과거 중지 기록은 이력이며 최신 “DONE까지 진행” 요청이 현재 범위의 실행 기준이다. 기존 Kiro 자동화 PAUSED와 과거 control/Goal graph/checkpoint/정식 시험·출시 경계는 그대로 둔다. 설정 자체를 기능 DONE으로 세지 않는다.

## 총괄의 실제 이양·Goal 확인 — 08:43

- Root가기존두작성자의제품/시험쓰기종료를확인하고root-handoff.md에실제이양두marker/정확한WIP/Root34원출력을기록했다. 이후이양범위를직접수정하지않는다.
- 사용자가기존pausedGoal을제거했다고알린뒤get_goal=null을확인,새60개+전체A통합목표를create_goal/get_goal로status=active확인했다. tokenbudget미설정,실제Goal과턴상태를구분했다.
- RootMain의ADD007원Location.latitude/longitude/elapsedRealtimeNanos전달은추가연결했다. 새담당이typedprovenance/ref/TTL시험과독립검토를인계하면Root가공유시험에포함한다. 현재GREEN/전체DONE근거는아직없다.

## rev127 진행 갱신 — 09:20 KST

- 원문 로컬 완료 S03-26/S14-41/S13-24 추가3개. 고정 배정 총괄60 중3DONE·잔여57, 경로60·인식50 잔여 유지. 전체A22DONE/P112/U55·잔여167. 이 문서 첫 분담 목록은 고정이며 완료 ID를 제거하거나 다른 작성자에게 넘기지 않는다.
- 지속Goal ACTIVE, Root36 실제 새16RED 최소수정 진행. Source37열·다른939행·B 보존, rev127-result.json 및 최신daylog 참조.

## rev128 현재 진행 — 2026-10-01

- 고정 배정목록60+60+50는삭제하지않는다. 최초완료19와추가완료8을합쳐 A189 DONE27/P109/U53·잔여162이며 현재총괄56/경로59/인식47.
- 새완료5: 총괄 S14-43, 경로 S09-52, 인식 S11-27/S12-30/S10-32. 원문 성공기준·실제Root37/38 결과·별도review 대조 후 원장rev128 통합. 단말필수항목은완료에포함하지않음. Goalactive,각담당전체DONE까지계속.

## rev129 현재 진행 — 2026-10-01

- 원문 로컬 완료6개: S03-14 최소기능 재평가, S13-22 명시종료취소·상위정리, S14-10 최초/추가원인·정리/복구같은상태·안내갱신, S14-14 최종시작/재개권한결합, S14-15 기능집합실제소비, S10-31 전21클래스·실제배경 별도회귀세트. 원문37열·다른934행을보존했다.
- A189 DONE33/P105/U51·잔여156, 현재총괄51/경로59/인식46. 전체942 DONE586/P260/U84/해당없음12. 고정배정목록/시작snapshot은유지. work/part-a-completion-20260930/rev129-result.json에판정/남은경계.
- Root40 actual410/7FAIL→41 actual270/3FAIL→42 actual92/3FAIL의각신규반례·기존fixture를분리했다. 원confidence2·decisionage3은41 같은GREEN, actual권한formatter·disabled합성2반례는42 같은GREEN. 다른AccessStatic3fixture는42b38/38GREEN. 다른기능/정식전체시험통과로확대하지않음.
- S13-36/S14-17은구성개선후PARTIAL유지: 실제최종합성기기화면과GPS건강/profile 잔여, 열린ACTIVE권한패널의후속SAFE_STOP stale표시P2를계속수정한다. 모델 회귀시험PASS와baseline target18/21·negative오탐1/3을분리보존.
- 공유 assembleDebug/compileDebugAndroidTestKotlin 성공, bypassOFF APK96581ae9…/453663614bytes·기존receipt일치. 마지막연결기기0/설치·실기기·현장미수행/출시NOT_ELIGIBLE. GoalACTIVE,각담당전체DONE까지후속지속.

## rev130 현재 진행 — 2026-10-01 12:18

- A189 DONE38/P101/U50·잔여151, 총괄49/경로59/인식43. 전체942 DONE591/P256/U83/해당없음12. 고정배정·시작snapshot·B188 보존.
- 원문localDONE5: S10-9/S12-10/S12-18/S14-23/S14-24. 47/48 실제관련검증/독립최종검토와새앱빌드근거로통합. rawcache순서P2·외부hold/native소유·ordinary정리실패 후속은계속; 전체Goalactive. 상세 rev130-result.json·A계획·daylog.

## rev131 현재 진행 — 2026-10-01 12:48

- A189 DONE41/P99/U49·잔여148, 총괄47/경로59/인식42. 전체942 DONE594/P254/U82/N/A12. 고정배정목록/시작snapshot/B188 보존. S10-15/S13-31/S14-27 원문localDONE3 실제검증/독립수용/새빌드로통합.
- 실제49원20반례같은GREEN,50oldsourcefixture3은50b불변5PASS+50c1PASS해소. crosswalk 참고 실제Main연결과 ordinary 자원정리 실패 원반례부터후속작성. Goalactive/기기필수범위미완료유지,첫묶음종료0.

## rev132 현재 진행 — 2026-10-01 14:16

- A189 DONE43/P99/U47·잔여146, 총괄47/경로59/인식40. 전체942 DONE596/P254/U80/N/A12. 고정배정목록·시작snapshot/B188 보존. S10-18/S12-22 원문localDONE2를실제계측/현재consumer·독립검토·최신앱빌드로통합.
- 현재239GREEN과native효과FakeRuntime를실기기PASS로확대하지않음. whole S14-9와S13-9는필수잔여가있어PARTIAL유지. S09-16/S11-34/S12-32 전체원문후속판정은별도. Goalactive/첫묶음종료0, 다음56전전writer재동결/Root공유빌드1개.


### rev133 실제 완료 통합 — 2026-10-01T16:59:22.749074+09:00

- 전체942: DONE599/PARTIAL253/미정합78/해당없음12. A189: DONE46/PARTIAL98/미정합45·잔여143, 총괄46/경로58/인식39. 고정 소유 ID와 시작 snapshot은 그대로다.
- S09-16·S11-34·S13-9 원문 로컬 DONE3개 추가. S09-38 미정합→PARTIAL 정합은 완료 증가가 아니며, S14-9 fused 직접17GREEN/독립 수용은 부분 개선이다. 원문37열·다른937행·B188개 보존, 근거는 work/part-a-completion-20260930/rev133-result.json 및 최신 외부 원장·요약에 기록했다.
- Root59c SOURCE1GREEN·최신 assembleDebug/AndroidTest sourcecompile SUCCESS, APK11248447/receipt일치/bypassOFF. 설치·실제Activity·Native·물리/현장 미검증, 정식279/5출시gate 상태 변경 없음. Goal active이며 전체 완료까지 계속한다.
- Root60: Main 보정 원 callback receipt 반례(단독 readiness_fix 작성)와 AR candidate/destroy 읽기 조사. 경로 나침반 원7개 최소 수정 검증, 인식 late-loss 점 소유권 원반례를 병렬 수행. 다음 공유 Gradle 전에 모든 제품·시험 작성 종료를 확인한다.

## rev134 및 전환 마무리 — 2026-10-01T18:03:47.311594+09:00

- A189 DONE47/P98/U44·잔여142, 총괄46/경로57/인식39. S08-15 실제 DONE 추가, 고정 소유 ID·시작 snapshot·B188 보존. 근거는 rev134-result.json·A 계획·daylog다.
- 최신 직접 사용자 지시로 기존 세션은 이미 착수한 좁은 묶음만 마무리하고 새 총괄1개·개발3개 세션에 인계한다. 실제 작성 종료/최종 원장/소유권 이양은 공통 part-a-two-session.md 최신 기록을 따른다. 전체 A 원문 DONE 목표는 유지한다.

## rev135 최종 원장 판정과 기존 묶음 종료 — 2026-10-01T18:41:46.127860+09:00

- A189 DONE48/P101/U40·잔여141, 총괄46/경로56/인식39. ADD-MOTION-009 원문 기준 DONE1 추가, S08-10/S11-10/22/26 U→P정합4는 실제 완료 증가가 아니다. 고정 목록/시작 snapshot/B188 보존. source37열·다른935행 보존은 rev135-result.json에서 확인한다.
- 기존 착수 기능/직접 시험/독립 검토/최신68 앱 빌드 완료. 새 총괄에게 원장·공통기록·판정 책임, 새 보행·출력 개발에 Main 소유권을 넘길 준비 단계다. 실제 LEGACY_ALL_WRITERS_ENDED/COMMON_OWNERSHIP_RELEASED는 공통 인계 마지막 기록만 따른다.

## 기존 소유권 이양 준비 확정 — 2026-10-01T18:50:33.050869+09:00

- 기존 세 세션·하위제품/시험/리뷰 작성 모두 종료·예외0,RootGoalpaused실제확인. 최신rev135 A48/P101/U40·잔여141/Root46·경로56·인식39. 새4세션은 [전환계약](four-session-transition/README.md)과 sessions.json 역할ID/Goal을 따르며 고정원목록·B·시작 snapshot을 보존한다. 공통 release는 part-a-two-session 마지막 항목에서 확인하고 설정 담당의 flag 해제 후 시작한다. 이 문서는 최종 이양 뒤 추가 쓰기0다.
