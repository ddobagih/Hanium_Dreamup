package kr.co.hanium.dreamup.walksafe.ui

/** Shared display keys and order for the device-check producer and native rows. */
internal object DeviceCheckItems {
    const val DISTANCE_API = "거리 API 지원"
    const val OBJECT_RECOGNITION = "객체 인식(내장 이미지)"
    const val LOCATION_SERVICE = "GPS·위치 서비스"
    const val LOCATION_FIX = "현재 위치 수신"
    const val KOREAN_TTS = "한국어 음성 합성"
    const val WAKE_PHRASE = "기기 내 호출어 인식"
    const val DEVICE_RESOURCES = "기기 상태"

    val orderedLabels: List<String> = listOf(
        DISTANCE_API,
        OBJECT_RECOGNITION,
        LOCATION_SERVICE,
        LOCATION_FIX,
        KOREAN_TTS,
        WAKE_PHRASE,
        DEVICE_RESOURCES,
    )
}
