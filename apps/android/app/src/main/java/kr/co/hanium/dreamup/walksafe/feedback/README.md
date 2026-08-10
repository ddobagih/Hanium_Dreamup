# User Feedback

이 패키지는 위험/길안내 결과를 Android TTS와 haptic으로 전달하고 반복 알림을 억제한다.

- `WalkSafeFeedbackPolicy`: metric source, confidence, 3프레임 이상이면서 700ms 이상인 track 안정성, stale 상태와 cooldown을 검사한다.
- `AndroidFeedbackActuator`: TTS, 위험 진동, 길안내 진행음을 실제 기기 API로 출력한다.
- TTS 초기화 중에는 첫 위험·상호작용·길안내를 우선순위별 1개로 제한해 보관하고 성공 시 위험부터 flush한다. 초기화 실패·한국어 미지원·close에서는 대기 음성을 폐기하고 상태를 구분한다.

TalkBack touch exploration이 켜져 있으면 앱 TTS를 중복 재생하지 않고 explicit accessibility announcement와 위험 진동을 사용한다. 500ms 상태 화면은 live region이 아니며 위험·상호작용·길안내의 중복/우선순위 창을 따로 적용한다. 현재 진동 패턴은 STOP 단계만 강제 출력한다.

JVM policy 테스트는 실제 스피커 음량, TalkBack 순서, 착용 상태의 진동 체감을 검증하지 않는다.
