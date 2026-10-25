# 센서 재생 스크립트 기준 결과와 새 골 인계

기준 시각: 2026-10-09 03:48 KST. 이 세션은 시험 스크립트와 프롬프트 제작 담당이다. **제품 수정은 하지 않았고, 전체 정상 안내 흐름이 해결되었다고 판단하지 않는다.**

## 실행 결과

```bash
python3 scripts/replay_guidance_sensors.py --output /home/ddobagi/.local/state/walksafe-development/guidance-sensor-replay-20261009/root-baseline-handoff
```

- 실제 현재 제품 코드 대상: 38개 센서 데이터 사례 + 7개 READY 시작 행동 시험 = **45개 실행, 42 PASS, 3 실패, error/skip 0**. 최종 상태 `RED_REQUIRES_TRIAGE`, 종료 코드 1.
- 선택된 38개 입력 모두 trace를 생성했다. JUnit 보고서는 이번 실행의 별도 경로를 사용했다(`standard_report_path_fallback=false`). 제품과 하네스의 실행 중 변경은 없었다. Gradle 실행·기대 시험 수·trace ID 검사는 통과했다.
- 제품 소스 ID: `3a35993505fa37e096357d0dec05c4baf7f679d6f5c10b926f95877699fe93fa`. main/debug Java·Kotlin 및 app build 설정 식별자이며 APK 해시가 아니다.
- 입력 SHA-256: `5481493ac3019bde0fc642589b0cdf1cdeaaef3b3f55f9cb25ee4032a3aed92b`.
- [실행 요약](/home/ddobagi/.local/state/walksafe-development/guidance-sensor-replay-20261009/root-baseline-handoff/summary.json), [원 실행 로그](/home/ddobagi/.local/state/walksafe-development/guidance-sensor-replay-20261009/root-baseline-handoff/gradle.log), [38개 trace](/home/ddobagi/.local/state/walksafe-development/guidance-sensor-replay-20261009/root-baseline-handoff/traces), [JUnit XML](/home/ddobagi/.local/state/walksafe-development/guidance-sensor-replay-20261009/root-baseline-handoff/junit).

## 남은 실패와 첫 분석

| 사례 | 현재 관측 | 후속 판정·작업 |
| --- | --- | --- |
| `gps_persistent_accuracy_auto_recovery` | 최초 BAD 1,000ms부터 현재 GPS 3초×3회 유예 후 지속 문제 대응. 새 정상 입력 후 11,100ms에는 GPS PASS·ACTIVE·경로 존재·position pause 해제 상태지만 새 ROUTE REQUEST 없음. speech retry 하한은 12,000ms. | `UNTRIAGED_ASSERTION`. 12,000ms 이후 영구 무재개라고 결론내릴 근거는 없다. 실제 정상 회복 시각과 일반 실패 backoff의 관계를 사용자 자동 재개 요구와 대조하고 실제 Main 스케줄러까지 연결해 판정한다. 기대 시각을 조용히 늘려 녹색으로 만들지 않는다. |
| `gps_persistent_missing_auto_recovery` | 같은 11,100ms 새 음성 요청 기대가 12,000ms 재시도 하한 이전에 실패. | 위와 동일한 미분류 분석 대상. 원 입력·기대·trace 보존. |
| `permission_revoked` | 실제 `applyObservedPermissionStateChange`를 거쳐 기존 utterance 취소 후 `android.util.Log.d` JVM 미지원에서 중단. trace에 `INPUT_ADAPTER_GAP`, JUnit 요약에는 `UNTRIAGED_EXECUTION`. | 플랫폼 입력 어댑터를 연결하여 권한 취소 전체 경계 검증을 마친다. 예외를 삼키거나 native stop만 강제 호출해서 성공으로 대체하지 않는다. 이 결과로 권한 전체 흐름 PASS를 주장할 수 없다. |

## 최초 실패 보존과 시험 장치 교정

- `root-baseline-all/`에 최초 45개 실행의 4실패와 로그·trace를 보존했다. 초기 init script의 보고서 경로가 Android Gradle 설정에 덮여 runner가 시험 0개로 판단하여 안전하게 exit 3을 냈다. 그 실행 시작 후 생성된 정확한 두 클래스의 JUnit XML만 보존·대조하여 45/4로 기록했다. 이전 판정은 `summary-before-report-path-fix.json`에 남았다. 최종 실행은 Gradle `projectsEvaluated`에서 결과 경로를 지정하고 실제 전용 XML을 확인했다.
- 최초 `tts_error_retry`는 ERROR 500ms + 실제 retry interval 2,000ms보다 이른 2,000ms에 검사한 잘못된 기대였다. 목적을 유지하여 2,800ms로 최소 교정했으며 최종 실행 PASS. GPS 정상 회복의 별도 자동 재개 문제와 구별한다.
- 이전 worker의 명령 반전 실패는 실제 유효한 새 6시 안내까지 금지하던 과도한 fixture 기대였다. 기존 utterance 취소·늦은 콜백 무효화는 유지하면서 새 유효한 안내를 허용하도록 교정했다. 원 실패는 private 결과에 보존했다.
- SDK stop 시 예약된 자동 START/DONE/ERROR를 제거하도록 시험 장치를 고쳤다. 명시적으로 입력한 늦은 콜백은 실제 registry로 전달하고 이전 native 활성 상태를 되살리지 않는다. 추가 queued START·반전·late START 시험에서도 확인했다.
- 교정·별도 선택 9개 시험의 원 근거는 [worker 인계](/home/ddobagi/.local/state/walksafe-development/guidance-sensor-replay-20261009/worker-private/review-corrected/receipt-final.json)에 있다. 최종 전체 실행과 별도 시험 수를 합산하지 않는다.

## 검증 범위와 골의 필수 공백

- `ADMITTED_WALK_HEADING_OBSERVATION`: 이미 승인된 보행을 전제한 실제 Main 조립의 위치/방향/경로/음성 코드. GPS는 실제 PS 입력 함수로 전달하지만 heading은 몸 방향 변환 이후 관측이다. 일부 300ms 재평가는 `POLL_MODEL`이다. 실제 Main 스케줄러 등록을 검증했다고 주장하지 않는다.
- `READY_START_CONTROLLER`: 실제 Entry·TTS DONE+3초·Confirmation·Admission·Gateway ACK·Activation을 연결한 7개 시험. 센서 준비 capture 일부 통제, PS binding 없는 초기 admission이다.
- **`FULL_HOME_TO_ARRIVAL=false`**. 두 부분의 성공을 단일 HOME→도착 성공으로 합산하지 않는다. 실제 readiness producers, 같은 PS binding/계정/clock/run, 안내 시작 전부터 연속 센서 입력, 경로 응답·저장 ACK, 첫 시계 방향·미터 안내, 합류·회전·도착 확인·종료를 한 흐름으로 연결해야 한다.
- 원 rotation-vector/gravity/magnetic/accelerometer와 몸 방향 변환 시험, 실제 스케줄러 자동 재개, 권한 SDK 경계도 후속 골의 필수 작업이다. 구체적인 방법·완료 조건은 [GOAL_PROMPT.md](/home/ddobagi/Hanium_Dreamup/docs/analysis/guidance-sensor-replay-20261009/GOAL_PROMPT.md)에 있다.
- 실제 기기·청취·카메라 성능·보행·현장 도착은 미검증. 이 세션에서 APK 빌드·기기·API/서비스 변경은 하지 않았다. 기존 공개 APK를 이 시험 결과로 개선되었다고 표기하지 않는다.

## 최종 인계 점검

- 독립 검토에서 일반 비단언 예외를 fixture로 단정하던 한 줄을 `UNTRIAGED_EXECUTION`으로 교정했다. 행동·기대 변경은 없다. 최종 harness SHA는 `aaed8b3897a9d11278603106d36793a15eb1df6d6127bf0399e1061410e0c116`, 교정 후 전체 45개 결과는 위와 동일하다. 직전 `root-baseline-final` 결과도 보존했다.
- 빈 사례 선택·잘못된 정규식·기존 결과 폴더는 exit 4, 실제 공유 lock을 보유한 통제된 상태의 두 번째 실행은 Gradle을 시작하지 않고 exit 3인 것을 확인했다. 첫 busy 시험은 먼저 실행이 끝나 lock이 비어 있던 준비 오류였으며 정상 전체 실행으로 끝났다. 원 기록을 보존하고 lock을 명시적으로 잡은 setup으로 다시 검사했다. [실행기 거절 검사](/home/ddobagi/.local/state/walksafe-development/guidance-sensor-replay-20261009/runner-negative-checks.json). 이 별도 실행 수를 기준 시험 수에 합산하지 않는다.

## 담당 종료와 다음 행동

- Root는 입력 JSON·실행기·문서·공유 Gradle 담당, 시험 worker는 sensor harness/test 담당이었다. READY 시험은 읽기 전용 검토자의 private 후보를 Root가 정확한 bytes로 반입했다. 모든 공유 쓰기와 실행은 새 세션 생성 전에 종료한다.
- 새 세션에서 직접 `get_goal`/`create_goal`로 목표를 설정하고 현재 기준을 재현한 뒤, 실패 분석·필수 전체 흐름 연결·최소 제품 수정·같은 사례 재검증·관련 회귀·독립 검토를 반복한다. 사용자에게 다시 작업 승인 요청을 하지 않는다. 미분류 실패나 필수 검증 공백이 남은 상태는 골 완료가 아니다.
