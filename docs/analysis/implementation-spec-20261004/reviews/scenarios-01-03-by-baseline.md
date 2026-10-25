# SC01–03와 구조도 독립 검토

검토자 baseline_audit의 읽기 전용 보고를 Root가 기록했다. 제품 실행·시험·빌드·기기·외부서비스 호출 및 검토자의 파일 수정 없음. 기준은 2026-10-04 10:33:40 KST, commit 2e5744725a8c033a1817f3cc8a8ec679bc15ac9d + 고정 WIP다.

## 판정

문서 지적 5건 및 수정 후속 지적 1건을 Root가 문서에 반영했고 검토자가 해당 변경을 다시 읽어 모두 해결 확인했다. 아래 범위에서 남은 중요 수정 요청 없음. 전체 구현 완료나 실행 성공을 의미하지 않는다.

|ID|수정·재확인|코드 근거|
|---|---|---|
|SC01-01|기기 검사 → FP004 교육·연습 → readiness 순서|FirstRunOnboardingPolicy.kt1035–1042, MainActivity.kt13215–13218|
|SC02-01|일반 교체 실패의 기존 경로 유지와 archive 실패·countable provider 연속2실패 안전중단 구분|MainActivity.kt46637–46648,46765–46800|
|SC03-01|utterance 등록/REQUESTED → 현재성 → audio focus → dispatch/START 재검사|AndroidFeedbackActuator.kt1399–1467,275–295|
|SC03-02|음성 SUPPRESSED/UNAVAILABLE과 진동 허용을 독립 경로로 표현|AndroidFeedbackActuator.kt483–523|
|ARCH-01|Gateway→Voice에 voice활성·설정/인증·P-D08-025 추가|apps/android-gateway/src/config.ts86–89,speech-relay.ts209–213|
|SC03-FOLLOWUP|진동 수락 뒤 종료 해석 예약은 음성실패/미수락 조건. 요청순서 speak반환→vibrate와 비동기재생 중첩 구분|MainActivity.kt36826–36877,AndroidFeedbackActuator.kt501–523|

## 실제 확인 범위

- SC01–03 본문 및 sc-01/02/03.mmd, architecture.mmd.
- 집중 코드 대조: F-D01-012, F-D02-001/005/018/023/025/026, F-D03-023/024/027/028, F-D05-007, F-D07-009/019, P-D08-025 관련 순서·기한·현재성·실패·정리.
- 본문의 고유 기능43·정책56개 정의 존재 확인. 이는99개 항목의 전체 코드 검토가 아니다.
- 실제 대조한 소스15개 manifest SHA 일치.
- 관리자 Backend 직접·운영잠금·Web410은 AdminEndpointPolicy.java9–25,adminapp/build.gradle.kts35–93,apps/web/proxy.ts3–15 대조.

## 검토 버전

- 05-scenarios.md: 5dab63666f297f5fd4a3a3658ccdb0ec1aa1605f52822bd329426595ab3e1b68
- sc-01.mmd: 1484c152d6258736ff73cddd64bfc80f36324d538d6e92dfe59772eb9ab51f9b
- sc-02.mmd: e93313f5a409e812d454fa985e3ff06a8d9715d0742da21af003248a4abf8650
- sc-03.mmd: d34f05ceba5edfbcdc39fe5a78995c0e18b8e739a8c1548703c583829c02bf42
- architecture.mmd: 44730cf5d4b81c28e6e69f4d534ce88fc8be9097c8c220ca1d04375e51cc3eb3

## 미검토

SC04–07, 렌더된SVG 시각품질, 모든 기능 전체분기, 실제기기·서버·외부서비스 동작은 이 검토 범위 밖이다. Root의 렌더/표시 검사는 별도 기록이다.
