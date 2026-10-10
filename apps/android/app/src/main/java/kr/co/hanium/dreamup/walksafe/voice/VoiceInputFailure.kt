package kr.co.hanium.dreamup.walksafe.voice

// App-owned RecognitionListener error: Android errors are positive; keep silencing distinct.
internal const val SPEECH_ERROR_INPUT_SILENCED = -10_001
internal const val SPEECH_ERROR_INPUT_ROUTE_CHANGED = -10_002
internal const val MICROPHONE_INPUT_ROUTE_CHANGED_GUIDANCE =
    "마이크 연결이 바뀌어 음성 입력을 중단했습니다. 음성 명령 버튼을 눌러 처음부터 다시 말씀해 주세요."

internal const val MICROPHONE_INPUT_SILENCED_GUIDANCE =
    "휴대전화가 마이크 입력을 차단해 음성을 들을 수 없습니다. 휴대전화 설정의 마이크 사용을 확인하고, 통화나 다른 앱의 녹음을 마친 뒤 다시 시도해 주세요."
