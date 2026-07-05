# Android 실기기 overlay/depth 체크리스트 - 2026-06-01

## 목적

이 체크리스트는 Android native ARCore/TFLite 앱에서 **화면 bbox overlay, camera preview, ARCore depth sample, `N보` 표시가 같은 객체를 가리키는지** 실기기에서 확인하기 위한 기록 양식이다.

## 기준 APK

```text
Repo: /home/ddobagi/Code/hanium-dreamup
APK: /home/ddobagi/Code/hanium-dreamup/apps/android/app/build/outputs/apk/debug/app-debug.apk
SHA-256: 1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9
```

설치 예시:

```bash
cd /home/ddobagi/Code/hanium-dreamup
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

또는 절대 경로:

```bash
adb install -r /home/ddobagi/Code/hanium-dreamup/apps/android/app/build/outputs/apk/debug/app-debug.apk
```

## 현재 좌표계 주의

- 2026-06-02 기준 overlay는 GL frame에서 `Frame.transformCoordinates2d(IMAGE_PIXELS -> VIEW)` 결과를 계산해 `DebugBboxOverlayView`에 전달하는 경로가 우선이다.
- depth sampling은 ARCore mapper가 있으면 `IMAGE_PIXELS -> TEXTURE_NORMALIZED` 경로를 우선 사용하고, mapper가 없으면 `identity` fallback으로 표시된다.
- 따라서 P0 관찰이 좋아 보여도 이것은 **실기기 관찰 기록**일 뿐이며, bbox/depth/`N보` 정합 PASS는 아래 항목을 직접 채워야 한다.
- metadata log의 `overlay_transform_path`, `depth_transform_path`, `fallback_reason`을 함께 확인해 identity fallback을 실제 PASS처럼 해석하지 않는다.

## 안전 기준

- 이 체크리스트 수행 중 RGB/YUV frame, depth raw, confidence image, screenshot을 저장/export/upload하지 않는다.
- 실기기 관찰자는 화면에 보이는 텍스트와 육안 관찰만 기록한다.
- GPS/report upload/TTS/haptic/navigation 연결이 필요 없는 overlay/depth 관찰을 먼저 수행한다. 해당 기능을 함께 확인하려면 별도 항목으로 기록하고, 자동 신고/외부 전송으로 확대하지 않는다.
- 장시간 반복 실행이나 외부 API 호출은 하지 않는다.

## 결과 판정 규칙

| 결과 | 의미 |
|---|---|
| PASS | 해당 항목을 실기기에서 직접 관찰했고 기대 동작과 맞다. |
| PARTIAL | 일부 위치/거리/회전에서만 맞거나 의심이 남는다. |
| FAIL | 명확한 좌표 밀림, 반전, depth 불일치, stale 잔존이 있다. |
| BLOCKED | 기기/환경/권한/ARCore availability 문제로 관찰하지 못했다. |

## P0-1. 설치/기본 실행

| ID | [단계] | 검증/기록 기준 | evidence_required | observed_by | result | notes |
|---|---|---|---|---|---|---|
| P0-1-1 | 기준 APK를 설치한다. | 설치 명령과 성공/실패 메시지를 기록한다. | adb install output |  |  |  |
| P0-1-2 | 앱을 실행하고 ARCore camera preview가 보이는지 확인한다. | preview가 멈추지 않고 화면이 갱신된다. | visual observation |  |  |  |
| P0-1-3 | detector/debug 텍스트가 보이는지 확인한다. | class, confidence, `1보` 또는 depth summary가 표시된다. | UI text |  |  |  |
| P0-1-4 | 프레임 드랍이 재발하는지 본다. | 30초 이상 움직일 때 preview가 심하게 끊기지 않는다. | visual observation |  |  |  |

## P0-2. overlay 위치 정합

관찰 대상은 먼저 `person` 또는 큰 단일 물체로 한다. 점자블록은 field에서 가능할 때 별도 기록한다.

| ID | [단계] | 검증/기록 기준 | evidence_required | observed_by | result | notes |
|---|---|---|---|---|---|---|
| P0-2-1 | 객체를 화면 중앙에 둔다. | bbox가 객체 위에 대략 맞는다. | visual observation |  |  |  |
| P0-2-2 | 객체를 화면 좌측에 둔다. | 좌우 반전/가로 offset이 없다. | visual observation |  |  |  |
| P0-2-3 | 객체를 화면 우측에 둔다. | 좌우 반전/가로 offset이 없다. | visual observation |  |  |  |
| P0-2-4 | 객체를 화면 상단에 둔다. | 상하 반전/세로 offset이 없다. | visual observation |  |  |  |
| P0-2-5 | 객체를 화면 하단에 둔다. | 상하 반전/세로 offset이 없다. | visual observation |  |  |  |
| P0-2-6 | 객체가 화면 가장자리로 일부 잘리게 둔다. | bbox clipping이 이상하게 튀지 않는다. | visual observation |  |  |  |

기록할 증상:

- 좌우 반전 여부
- 상하 반전 여부
- bbox가 전체적으로 한쪽으로 밀리는지
- preview crop/letterbox 때문에 bbox 크기가 맞지 않는지
- bbox가 화면 밖으로 튀는지

## P0-3. 회전/방향 전환

| ID | [단계] | 검증/기록 기준 | evidence_required | observed_by | result | notes |
|---|---|---|---|---|---|---|
| P0-3-1 | portrait 상태에서 중앙 객체 bbox를 본다. | portrait 기준 P0-2 중앙과 동일하게 맞는다. | visual observation |  |  |  |
| P0-3-2 | 기기를 90도 회전하거나 landscape로 전환한다. | bbox가 화면 밖으로 튀거나 축이 바뀌지 않는다. | visual observation |  |  |  |
| P0-3-3 | 회전 후 좌/우/상/하 중 한 위치를 다시 본다. | 회전 후에도 반전/offset이 새로 생기지 않는다. | visual observation |  |  |  |
| P0-3-4 | 회전 후 preview와 debug 텍스트가 멈추지 않는지 본다. | frame update와 detector update가 계속된다. | UI text + visual observation |  |  |  |

## P0-4. depth/`N보` 거리 추세

실측 도구가 없으면 0.5m/1m/2m는 대략 거리로 적고, 정확도 PASS가 아니라 추세 확인으로만 쓴다.

| ID | [단계] | 검증/기록 기준 | evidence_required | observed_by | result | notes |
|---|---|---|---|---|---|---|
| P0-4-1 | 단일 객체를 약 0.5m에 둔다. | depth median 또는 `N보`가 가까운 값으로 표시된다. | UI text |  |  |  |
| P0-4-2 | 같은 객체를 약 1m에 둔다. | 0.5m보다 depth/보 수가 커진다. | UI text |  |  |  |
| P0-4-3 | 같은 객체를 약 2m에 둔다. | 1m보다 depth/보 수가 커진다. | UI text |  |  |  |
| P0-4-4 | 객체 뒤 배경이 멀리 있는 상황에서 본다. | bbox 내부 객체 depth가 배경 depth로 튀지 않는다. | UI text + visual observation |  |  |  |
| P0-4-5 | 객체를 좌/우 위치에 둔 채 거리 변화를 본다. | 가장자리에서도 거리 추세가 깨지지 않는다. | UI text |  |  |  |

기록할 값:

```text
거리 후보: 0.5m / 1m / 2m
class:
confidence:
depth source: Raw / Full / pseudo / unknown
median depth:
sample count:
valid ratio:
표시된 보 수:
관찰 증상:
```

## P0-5. stale guard 확인

| ID | [단계] | 검증/기록 기준 | evidence_required | observed_by | result | notes |
|---|---|---|---|---|---|---|
| P0-5-1 | 객체를 화면에서 치우거나 detector가 끊기는 상황을 만든다. | detection age가 증가하거나 stale 문구가 보인다. | UI text |  |  |  |
| P0-5-2 | `age>2500ms` 또는 stale 상태를 확인한다. | 오래된 detection이 depth pipeline 입력에서 제외된다. | UI text |  |  |  |
| P0-5-3 | stale 상태에서 이전 객체의 `N보` 안내가 계속 남는지 본다. | 오래된 depth/보 수가 계속 사용자 판단 근거로 남지 않는다. | UI text + visual observation |  |  |  |

## P0-6. metadata-only log 확인

| ID | [단계] | 검증/기록 기준 | evidence_required | observed_by | result | notes |
|---|---|---|---|---|---|---|
| P0-6-1 | debug detail에 capture log summary가 보이는지 확인한다. | `capture:`로 시작하는 최근 frame summary가 보인다. | UI text |  |  |  |
| P0-6-2 | top detection bbox와 best depth bbox를 비교한다. | 같은 class/bbox 계열인지 기록한다. | UI text |  |  |  |
| P0-6-3 | depth source/sample count/valid ratio를 기록한다. | sample count가 0이거나 낮은 경우를 구분한다. | UI text |  |  |  |
| P0-6-4 | session stop 또는 앱 재시작 후 stale log가 남는지 본다. | 이전 session의 capture log가 계속 표시되지 않는다. | UI text |  |  |  |

## P0-7. server debug log 확인 선택 항목

화면 공유 없이 Codex/개발자가 metadata를 확인해야 할 때만 수행한다. 절차는 `docs/android/android_server_debug_log_runbook_20260601.md`를 따른다.

| ID | [단계] | 검증/기록 기준 | evidence_required | observed_by | result | notes |
|---|---|---|---|---|---|---|
| P0-7-1 | local/dev backend를 `ANDROID_DEBUG_LOG_ENABLED=true`로 실행한다. | 운영 배포가 아니라 local/dev임을 기록한다. | terminal command |  |  |  |
| P0-7-2 | `adb reverse tcp:8000 tcp:8000`를 설정한다. | 실기기가 `127.0.0.1:8000`로 host backend에 접근 가능하다. | adb output |  |  |  |
| P0-7-3 | 앱에서 `서버 로그 켜기`를 누른다. | UI에 `server-log=on`이 보인다. | UI text |  |  |  |
| P0-7-4 | `/android/debug/depth-logs/recent` 또는 JSONL tail을 확인한다. | image/depth raw/GPS 없이 metadata entry만 저장된다. | server JSON/text |  |  |  |

## 최종 요약 양식

```text
기기 모델:
Android 버전:
ARCore Depth 지원 여부:
APK sha256:
관찰 시간:
관찰자:

Overlay 결과: PASS / PARTIAL / FAIL / BLOCKED
Depth 추세 결과: PASS / PARTIAL / FAIL / BLOCKED
Stale guard 결과: PASS / PARTIAL / FAIL / BLOCKED
Metadata log 결과: PASS / PARTIAL / FAIL / BLOCKED
Frame drop 재발: 없음 / 약간 / 심함 / 확인 불가

주요 증상:
다음 조치:
```

## PASS로 쓰면 안 되는 항목

- APK 설치 성공만으로 overlay/depth PASS라고 쓰는 것.
- bbox가 화면에 나타난다는 사실만으로 좌표 정합 PASS라고 쓰는 것.
- `1보` 표시가 뜬다는 사실만으로 거리 정확도 PASS라고 쓰는 것.
- static RGB 평가 결과를 ARCore depth 또는 실기기 `N보` 근거로 쓰는 것.
- screenshot/image/depth raw를 승인 없이 저장해 evidence로 쓰는 것.
