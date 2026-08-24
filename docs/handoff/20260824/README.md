# 2026-08-24 전달 묶음

이 브랜치(`agent/fp023-off-route-choice-20260824`)의 코드 변경과 그 배경을 설명하는 문서다.
`current` 위에 13개 커밋이 올라가 있고, 원격 `current`는 건드리지 않았다.

## 무엇부터 볼까

| 순서 | 문서 | 내용 |
| --- | --- | --- |
| 1 | `handoff-to-c.html` | **먼저 읽을 것.** 확인·수정 요청 6묶음. 각 항목마다 증상·확인한 것·고치는 방법·확인 방법과 재현 명령 |
| 2 | `defect-gateway-crash.html` | 이 브랜치의 `Survive an unreachable gateway instead of crashing` 커밋 배경. P0 크래시 2건 |
| 3 | `onboarding-spec.html` | 첫 실행 1~3단계 디자인 결정. 이 브랜치의 디자인 9개 커밋 근거 |
| 4 | `figma-brief.html` | 위 디자인을 받기 위해 Figma에 넘긴 입력 |
| 5 | `onboarding-spec-4to8.html` | 첫 실행 4~8단계 설계 노트. **코드 변경 없음**, 착수 전 결정 사항 정리 |
| 6 | `figma-brief-4to8.html` | 4~8단계 Figma 입력 |

## 이 브랜치의 코드 변경

13개 커밋, 앱 소스·테스트 10개 파일 (`+860 / -25`). 통제 산출물은 포함하지 않았다.

- `Offer all three approved off-route choices` — FP-023 7단계. 이탈 시 3선택(새 경로 / 위치 다시 확인 / 길안내 종료)
- `Pin the route direction responsibility split` — GAP-007. 네 책임 분리를 고정하는 회귀 증거만 추가, production 코드 변경 없음
- `Survive an unreachable gateway instead of crashing` — P0 크래시 2건. 실행자 블록이 `RuntimeException`만 잡아 `IOException`이 빠져나갔다
- 나머지 10개 — 첫 실행 1~3단계 디자인

단위 테스트 **1,026건 전부 통과** (실패 0 · 오류 0 · 건너뜀 0).

## 먼저 알아야 할 것 두 가지

**1. 이 브랜치는 FP-022가 봉인한 파일 7개를 바꾼다.**
`WS-GOAL-EPIC-04-FP-022-R001`의 `final_content_manifest`가 22개 파일을 바이트로 봉인하는데
그중 7개(`MainActivity.kt`, `RouteNavigator.kt`, `AndroidVoiceCommand.kt`와 관련 테스트 4개)를 바꾼다.
FP-023이 `RouteNavigator.kt`를 바꾸지 않고 구현될 수는 없으므로 절차의 빈칸으로 보인다.
자세한 내용과 필요한 결정은 `handoff-to-c.html` ②를 볼 것.

**2. macOS에서는 이 브랜치가 그대로 빌드되지 않는다.**
`aapt2-osx.jar`가 `verification-metadata.xml`에 봉인돼 있지 않다.
그 수정은 로컬 전용이라 일부러 뺐다(통제 checkpoint를 함께 건드리기 때문).
Linux·CI에서는 영향이 없다. 맥에서 빌드하려면 별도로 요청할 것.

## 확인 명령

```bash
# 이 브랜치가 current 대비 무엇을 바꿨는지
git diff current..agent/fp023-off-route-choice-20260824 --stat

# 단위 테스트
cd apps/android && ./gradlew :app:testDebugUnitTest
```
