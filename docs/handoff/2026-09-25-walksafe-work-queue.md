# WalkSafe 다음 컴퓨터 작업 목록 및 파일 소유권 (2026-09-25)

이 목록을 실행하기 전에 `docs/handoff/WALKSAFE-TRANSFER-START-HERE.md`의 이전·복원 확인을 먼저 마친다.

이 목록은 장기간의 942행 전체를 다시 쓴 문서가 아니라 현재 우선 작업이다. 각 ID의 원문, 역사 상태와 보강 요구는 `docs/handoff/2026-09-25-walksafe-provenance-map.md`의 자료 지도에서 추적한다.

새 문제 탐색보다 아래 **이미 시작한 변경의 안전한 수습**이 먼저다. 각 항목은 단일 작성자, 별도 검토자, 선택 시험 순서로 진행한다. 현 작업공간의 다른 작성자 수정은 보존한다.

## 우선순위 1: 실패 또는 확인된 위험이 있는 변경

| 항목 | 다음 조치와 성공 기준 | 단일 제품 소유 범위 |
| --- | --- | --- |
| S20-11 런타임 신고저널 복구 | readiness에서 inventory 오류가 `report_storage_recovery_unavailable`로 뭉개지는 바깥 예외 경계를 확인한다. 기존 사유/잠금 단언을 안전하게 유지하고 첫 실패를 해결한 뒤 신규3+인접 readiness/reconciliation을 순서대로 재실행한다. 끊어진 symlink를 journal 존재 판정에서 놓치지 않는지도 확인한다. 16건/1초는 강제 I/O 상한이 아님을 문서화한다. 동시 commit 대기 실패는 원인이 입증될 때만 수정한다. | `backend/app/services/report_storage.py`, `report_storage_recovery.py`, `backend/app/api/health.py`, `backend/app/main.py`; 직접 시험은 별도 단일 담당 |
| Gateway Backend 계정 세션 현재성 | `backend-session-current-route`의 report-v2 두 `201` 대 `422`를 fixture 정책과 제품 흐름으로 분리한다. 요청 취소가 현재성 조회 fetch에도 전파되지 않는 P2 자원 사용 위험을 처리하고 직접 회귀를 만든다. typecheck/build와 16건 전부 통과, 독립 재검토 전까지 HOLD. RAW 사전검사가 원장/업로드 전에 끝나는 기존 경계는 보존한다. | `apps/android-gateway/src/routes.ts`; 관련 직접 시험은 별도 단일 담당 |
| S15-24 쿨다운 | 전진 벽시계 점프 P1과 재시작/용량 P2를 해결하기 전 배포 판정 금지. 수동 보고 우회와 기존 사용자/범주/거리 정책 보존. | `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportCooldownPolicy.kt`와 직접 시험, 경로는 새 컴퓨터에서 확인 |
| S11-2 깊이 샘플링 | 행별 샘플 열 인덱스가 달라질 때 인접 판정 P1을 직접 반증·수정한다. 시험 컴파일 메모리 실패를 제품 PASS로 취급하지 않는다. 실거리/실기기 안전성은 별도. | `DepthSampler.kt`와 직접 시험, 정확 경로는 새 컴퓨터에서 확인 |

## 우선순위 2: 국소 통과 뒤 남은 통합 경계

- S01-7 OTP: 수정된 직접 시험 5 passed. 실제 트랜잭션 롤백/SMTP 모호 송달과 원문 재시도 계약을 독립 확인한다. `backend/app/services/accounts.py` 단일 담당.
- S20-09 신고 이미지 키: 선택 시험 8 passed. PostgreSQL 두 세션의 동일 세대 공유 잠금/세대 변경 배타 잠금과 오래된 키 선택 금지를 검증한다. `backend/app/services/report_image_keys.py` 단일 담당.
- 원시 청크 키 세대: 오프라인 2 passed. 실제 PostgreSQL 세대 변경 경쟁을 S20-09와 구별해서 확인한다. `backend/app/services/raw_collection_storage.py` 단일 담당.
- S18-18 관리자 로컬 로그아웃: 강제 실행한 선택 클래스 통과. 독립 검토에서 잘린 `AdminSecurityController.java` 중간 본문을 별도 검토자가 한 번에 온전히 확보해 모든 in-flight credential 대입/정리 경로를 확인한다. Java `String` 메모리 제로화 주장 금지.
- S06-10 음성 후보: 선택 JVM 회귀 통과. 실제 기기에서 후보 수/페이지 발화와 TTS 타이밍 확인이 남았다.
- S02-18 Foreground Service: 현재 서비스 startup try/cleanup 존재. caller 및 provider의 복구 상태 계약을 확인하기 전 stale Stop 플래그만 제거하지 않는다.
- S01-28 감사 원장 상한: 설계만 완료. 자동 아카이브는 원장·암호화 유지보수·백업/키회전의 다중 파일 계약을 먼저 정해야 한다. 상한 숫자만 늘리지 않는다.

## 별도 보류 및 범위 관리

- S13-40, S09-19, S19-40, S16-25는 당일 작업일지의 독립 검토/시험 결함을 기준으로 별도 담당을 정한다. 수정이 안전하다는 근거 없이 기존 패치를 완료로 표시하지 않는다.
- S07-2의 원문상 잘못된 중간 좌표 건너뛰기 동작은 현재 TMAP 코드에서 거부되는 것으로 읽기 확인됐다. 제품 수정 0건이며 직접 회귀는 아직 작성·실행하지 않았다.
- S20-28, S20-10, S20-39 등 이전 국소 PASS는 각각 원문 전체 완료와 분리한다. 대형 재검토보다 원문 수용 기준의 남은 접점을 먼저 좁힌다.
- 942행 CSV의 `DONE 52/PARTIAL 36/공란 854`는 날짜형 파일 집계이고, 이 인계에서 정본 상태를 변경한 수치가 아니다.

## 다음 Root의 첫 30분

1. 저장소 전체와 원문·증거가 이전됐는지, 기존 미커밋 변경이 보존됐는지 확인한다. 원본을 삭제하거나 무조건 reset/clean하지 않는다.
2. 새 컴퓨터의 Android SDK/Node/Python 환경과 가용 메모리를 확인한다. 첫 시험은 문서의 좁은 명령만 사용한다.
3. S20-11 및 Gateway 두 HOLD의 실패를 그대로 재현·분류한다. 기존 실패를 고치기 전에 전체 suite나 정본 DONE 갱신을 하지 않는다.
4. 각 파일에 단일 담당자를 지정하고, 인접 회귀·독립 검토가 통과할 때까지 기존 WIP를 보존한다.
