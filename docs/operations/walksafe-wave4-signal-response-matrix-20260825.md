# WalkSafe Wave 4 signal → response 연결표

| 항목 | 현재 계약 |
|---|---|
| 상태 | `DRAFT / REPOSITORY_ONLY / NOT_APPROVED` |
| 적용 범위 | Wave 4 저장소 후보의 readiness, Gateway 5xx telemetry, 서버 용량 상태, 백업 검증 실패 |
| 운영 경계 | 중앙 dashboard·alert 전달·on-call 연결과 실제 발생·해제 임계값은 `NOT_CONFIGURED`; Wave 5에서 대상 환경과 담당자를 정한 뒤 검증한다. |
| 실행 주장 | 이 표는 대응 초안이며 실제 경보 발화, 장애 대응, 백업·복원 또는 운영 승인 증거가 아니다. |

## 신호별 대응

| 신호 | 즉시 안전 동작 | 확인·복구 | 해제 조건 | 현재 경계 |
|---|---|---|---|---|
| Backend `/ready`가 `503`과 `status=not_ready` 반환 | 해당 인스턴스를 새 요청 라우팅에 넣지 않거나 계속 제외한다. 준비되지 않은 상태를 재시작 반복으로 숨기지 않는다. | 응답의 실패한 check 이름과 제한된 reason을 기준으로 DB revision·privacy CHECK, upload root, detector, navigation, 암호화·저장소 의존성을 확인한다. query·본문·token·비밀 경로는 대응 기록에 복사하지 않는다. | 원인을 수정한 **같은 후보**가 `/ready` `200`을 반환하고 대상 환경의 재진입 검증을 통과한다. 연속 성공 횟수·관찰 시간은 `NOT_CONFIGURED`. | 중앙 alert·라우팅 자동제어·on-call은 Wave 5 `NOT_CONFIGURED`. |
| `walksafe.gateway.request.completed`에서 `is_5xx=true`가 지속 관찰됨 | 신규 canary·공개 범위 확대를 멈춘다. 영향 인스턴스는 준비 상태와 승인된 복귀 절차가 확인될 때까지 확대하지 않는다. | `correlation_id`, `route_template`, `http_status`, bounded `latency_ms`, `capacity_level`만으로 Gateway→Backend 의존성을 좁힌다. `/ready`와 loopback 전용 Gateway health를 함께 확인하며 query·좌표·본문·token·계정·삭제 ID를 수집하지 않는다. | 원인이 제거된 같은 후보에서 health/readiness가 정상이고 해당 route의 정상 완료 이벤트가 다시 확인된다. “지속”의 수치 임계값과 alert clear 시간은 `NOT_CONFIGURED`. | 현재는 bounded telemetry 입력만 있다. 집계기·SLI/SLO·fire→delivery→clear·on-call은 Wave 5 `NOT_CONFIGURED / NOT_RUN`. |
| 용량 상태 누락·잘못된 형식·만료: Backend `/internal/capacity` `503`, Gateway `UNKNOWN`/snapshot 생략, Android `MISSING`·`MALFORMED`·`EXPIRED` | 상태를 추정하거나 이전 snapshot의 TTL을 늘리지 않는다. Android는 새 raw 수집 세션과 자동 report 후보를 보류한다. 이 신호만으로 진행 중인 안전 기능을 중단하지 않으며 사용자의 명시적 안전 report는 허용한다. | Backend 용량 설정 3종의 완전성, 측정 대상 filesystem·private version state 권한, 측정 실패와 시스템 시각을 확인한다. version 파일을 수동 편집하지 않는다. | 현재 시각 기준 미만료이며 기존보다 높은 version·뒤로 가지 않는 `observed_at`의 snapshot이 Backend→Gateway→Android에 전달된 뒤에만 보류를 다시 판단한다. | TTL·측정 주기의 운영 승인값과 alert 임계값은 `NOT_CONFIGURED`; 단일 host local filesystem 후보에만 해당한다. |
| 용량 `ADMIN_ONLY_WARNING`(70% 이상) 또는 `PAUSE_NEW_FIELD_TEST_PARTICIPANTS`(85% 이상) | 70%는 제한된 관리자 경고 입력으로 취급한다. 85%는 신규 참가자 제한 신호를 유지한다. | 사용량 증가 원인을 확인하고 승인된 retention·백업 절차로만 공간을 회수한다. 원본이나 audit 자료를 수동 삭제하지 않는다. | 새로 측정된 더 높은 version이 낮은 단계로 복구되고 만료되지 않았음을 확인한다. | 참가자 등록 pipeline은 미연결이므로 85% 자동 집행 완료를 주장하지 않는다. 중앙 경보는 `NOT_CONFIGURED`. |
| 용량 `HOLD_NEW_RAW_COLLECTION_SESSIONS`(95% 이상) | 새 raw 수집 세션 시작을 보류한다. 이미 진행 중인 세션과 안전 기능을 이 신호만으로 강제 종료하지 않는다. | filesystem, 측정기, 보존 정책 실행 상태를 확인하고 승인된 방식으로 공간을 확보한다. | 새로 측정된 더 높은 version이 95% 미만 단계이며 TTL 안에 있을 때만 신규 raw 시작을 다시 허용한다. | 생산 raw pipeline 전체와 휴대전화 장기 queue 정책 완료를 의미하지 않는다. |
| 용량 full: `HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES`(100% 이상) | 95% 동작에 더해 자동 report 후보를 보류한다. 명시적 사용자 안전 report는 허용한다. | 추가 쓰기를 확대하지 않고 승인된 retention·백업·용량 증설 절차를 선택한다. 원인과 조치 중 민감한 경로·실제 사용률 값은 외부 로그에 싣지 않는다. | 새로 측정된 더 높은 version이 100% 미만 단계이며 TTL 안에 있을 때만 해당 후보 보류를 다시 판단한다. | 학습 후보 pipeline은 미연결이므로 그 자동 집행 완료를 주장하지 않는다. 증설·alert·on-call은 Wave 5 `NOT_CONFIGURED`. |
| 백업 manifest·artifact 서명 또는 digest 검증 실패 | 해당 세트를 신뢰·복원·삭제 근거로 사용하지 않는다. 검증을 우회하거나 기존 파일을 다시 서명해 정상으로 만들지 않는다. | 원본 세트를 읽기 전용 증거로 보존하고 signer fingerprint, key-control binding, artifact hash와 파일 권한을 승인된 기준과 대조한다. 원인을 수정한 뒤 새 backup을 생성한다. | 새 서명 backup이 검증을 통과하고 격리 restore의 count·hash·참조·권한 검증까지 통과한다. | Wave 4 로컬 격리 restore는 현재 `NOT_RUN`; 운영 키·offsite 저장소·실대상 검증은 Wave 5 범위다. |
| 서명된 backup `created_at`이 최대 age보다 오래됨 | stale backup으로 restore를 시작하지 않는다. age 정책 값을 임시로 늘려 통과시키지 않는다. | 시스템 시각과 승인된 age 정책을 확인하고 fresh quiesce 승인 아래 새 signed backup을 만든다. | 승인된 max age 안의 새 backup이 서명·무결성 검증과 격리 restore를 통과한다. | 코드상 범위 검사는 있으나 운영 max age 값은 `NOT_CONFIGURED / NOT_APPROVED`. |
| 서명된 backup `created_at`이 허용 future skew를 초과함 | 시각 무결성 문제로 간주해 backup·restore 진행을 중단한다. skew 허용치를 임시로 넓히지 않는다. | backup host·DB·검증 host의 UTC 시각과 시간 동기화 상태를 먼저 교정한 뒤 새 signed backup을 만든다. | 교정된 시각에서 새 backup이 승인된 future skew·age·서명·무결성 검증과 격리 restore를 통과한다. | 운영 future-skew 값과 시간원 경보는 Wave 5 `NOT_CONFIGURED`; 실제 복원은 `NOT_RUN`. |

## 공통 기록 규칙

- 같은 release candidate·환경·시각에 신호, 제한된 원인 분류, 조치, 재검증 결과를 결속한다.
- 로그와 대응 기록에는 query, 좌표, 본문, token, 계정·삭제 ID, 비밀값, 실제 filesystem 경로를 넣지 않는다.
- 운영자가 보류를 수동 우회하거나 자료를 임의 삭제하지 않는다. 복구되지 않으면 후보 확대를 멈추고 승인된 rollback 또는 Wave 5 책임자 판단으로 넘긴다.

근거: [Wave 4·5 계획](../planning/walksafe-security-server-operations-next-plan-20260825.html), [Backend readiness](../../backend/app/api/health.py), [Backend capacity](../../backend/app/services/capacity_state.py), [Gateway telemetry](../../apps/android-gateway/src/telemetry.ts), [Android capacity admission](../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayCapacity.kt), [backup integrity](../../scripts/walksafe_backup_integrity.py).
