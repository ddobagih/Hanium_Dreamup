package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate

/**
 * Legacy and unified output-index contracts. Class order must remain identical to the exported
 * model metadata; changing labels here without re-exporting the model silently relabels detections.
 */
object TwoModelClassMap {
    val customTactileClasses = listOf(
        "normal_tactile_block",
        "damaged_tactile_block",
        "tactile_damage_area",
    )

    val cocoAllowlist = setOf(
        "person",
        "car",
        "bus",
        "truck",
        "bicycle",
        "motorcycle",
        "traffic light",
        "bench",
    )

    val cocoClasses = listOf(
        "person",
        "bicycle",
        "car",
        "motorcycle",
        "airplane",
        "bus",
        "train",
        "truck",
        "boat",
        "traffic light",
        "fire hydrant",
        "stop sign",
        "parking meter",
        "bench",
        "bird",
        "cat",
        "dog",
        "horse",
        "sheep",
        "cow",
        "elephant",
        "bear",
        "zebra",
        "giraffe",
        "backpack",
        "umbrella",
        "handbag",
        "tie",
        "suitcase",
        "frisbee",
        "skis",
        "snowboard",
        "sports ball",
        "kite",
        "baseball bat",
        "baseball glove",
        "skateboard",
        "surfboard",
        "tennis racket",
        "bottle",
        "wine glass",
        "cup",
        "fork",
        "knife",
        "spoon",
        "bowl",
        "banana",
        "apple",
        "sandwich",
        "orange",
        "broccoli",
        "carrot",
        "hot dog",
        "pizza",
        "donut",
        "cake",
        "chair",
        "couch",
        "potted plant",
        "bed",
        "dining table",
        "toilet",
        "tv",
        "laptop",
        "mouse",
        "remote",
        "keyboard",
        "cell phone",
        "microwave",
        "oven",
        "toaster",
        "sink",
        "refrigerator",
        "book",
        "clock",
        "vase",
        "scissors",
        "teddy bear",
        "hair drier",
        "toothbrush",
    )

    val unifiedCocoClasses = listOf(
        "person",
        "bicycle",
        "car",
        "motorcycle",
        "bus",
        "truck",
        "traffic light",
    )

    val unifiedTactileClasses = listOf(
        "normal_tactile_block",
        "damaged_tactile_block",
        "crosswalk",
        "curb_step",
        "uneven_sidewalk",
        "e_scooter_obstruction",
    )

    val unifiedCustomClasses = unifiedTactileClasses

    val unifiedWalksafeClasses = unifiedCocoClasses + unifiedCustomClasses

    val unifiedWalksafeAllowlist = unifiedWalksafeClasses.toSet()

    fun customTactileClassName(classId: Int): String? = customTactileClasses.getOrNull(classId)

    fun cocoClassName(classId: Int): String? = cocoClasses.getOrNull(classId)

    fun isAllowedCocoClass(className: String): Boolean = className.lowercase() in cocoAllowlist

    fun unifiedWalksafeClassName(classId: Int): String? = unifiedWalksafeClasses.getOrNull(classId)

    fun isAllowedUnifiedWalksafeClass(className: String): Boolean {
        return className.lowercase() in unifiedWalksafeAllowlist
    }

    fun isAndroidDepthCandidate(candidate: DetectionCandidate): Boolean {
        val className = candidate.className.lowercase()
        return className in unifiedWalksafeAllowlist || className in customTactileClasses || className in cocoAllowlist
    }
}
