# EPIC-01 Phase G 목적지 미선택 위험안내 정합화 기록

- 기록 ID: `WS-EPIC-01-PHASE-G-NO-DESTINATION-HAZARD-IMPLEMENTATION-20260723-001`
- 상태: **INTERNAL_VERIFICATION_PASS_EPIC_IMPLEMENTATION_READY**
- 통제 경로: **15개**
- EPIC-01: **IMPLEMENTATION_READY**, 단 `COMPLETE`·출시 완료 아님

## 내부 구현 경계

- 목적지·활성 경로가 없어도 일반 위험과 CameraX low 제한 경고는 각 안전 gate를 통과하면 작동한다.
- normal tactile local guidance는 신뢰할 수 있는 TMAP 경로가 없으면 차단한다.
- `tmap_route_active`는 현장 관측값이며 CameraX 허용조건이 아니다.
- CameraX 제한 경고는 거리·걸음·STOP/high·조향·신고·진동·안전보장 권한이 없다.

## 요구사항 통제 해석

승인 상위 결정 CD-STARTUP-DETECTION과 FP-017의 목적지 없는 위험 경고를 REQ-03·REQ-06의 FP-020 현재 경로 표현보다 우선 적용한다.

현재 경로 표현은 경로와 관련된 행동·방향 안내에만 적용하며 일반 위험 관측·경고의 필수 gate로 사용하지 않는다.

승인 기준선 파일은 수정하지 않았고, `REQ-16`·`DES-06` Active 사건과 후속 정식 요구사항 revision에 연결한다.

## 검증·출시 경계

Android JVM 382/382, assembleDebug, lintDebug와 지정 Python 249/249는 내부 회귀다. 실제 기기·정식 시험은 `NOT_RUN`이다.
정식 시험 **279/279 NOT_RUN**, 5개 gate **NOT_RUN·미면제**, 출시는 **NOT_ELIGIBLE**이다.

다음 작업: `EPIC-02-FP017-WALK-SESSION-LIFECYCLE` — 준비→보행 중→일시정지→재검사→사용자 확인 후 재개 상태기계를 만들고 잠금·홈 버튼·통화·앱 전환 뒤 사용자 확인 전 자동 재개를 막는다.

내용 지문: `ecbc96fd988652ea2b7dd3f46d9266d9b3c38c3c7468ddeacb8e43e25c9fce9c`
