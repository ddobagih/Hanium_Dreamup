# 간소화 안내 UI

복귀 기준 커밋: 6b45adc (간소화 이전).
화면 문구·기호는 실제로 컴파일된 CompactGuidancePresentationPolicy를 Java에서 호출해 추출했습니다. 상태별 입력·출력은 rendered-states.json에 있습니다.
색상·버튼·크기는 MainActivity 스타일을 재현했습니다. Android 런타임 캡처가 아니며 시스템 폰트의 기호 모양, 텍스트 패딩, 줄바꿈은 실제 기기와 차이가 있습니다. 너비 412dp·글자 배율 1배, 목적지·경로·거리·감지 결과는 샘플입니다.
기존 TTS 경로는 변경하지 않았고 전체 안내 문구는 TalkBack contentDescription에 유지합니다. 모든 상태가 자동으로 상세 TTS를 재생한다고 보장하는 변경은 아닙니다.
