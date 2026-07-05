from __future__ import annotations

from pathlib import Path
import sys

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from backend.app.services.yolo_inference_adapter import (  # noqa: E402
    CUSTOM_TACTILE_CLASS_MAP,
    DEFAULT_COCO_ALLOWLIST,
    ultralytics_result_to_raw_detections,
)
from model.two_model_runtime import filter_and_merge_detections  # noqa: E402


class FakeBox:
    def __init__(self, *, cls, conf, xyxyn) -> None:
        self.cls = cls
        self.conf = conf
        self.xyxyn = xyxyn


class FakeResult:
    def __init__(self, *, boxes=None, names=None) -> None:
        self.boxes = boxes
        self.names = names


class FakeIndexableBoxes:
    def __init__(self, boxes) -> None:
        self._boxes = boxes

    def __len__(self) -> int:
        return len(self._boxes)

    def __getitem__(self, index: int):
        return self._boxes[index]


def test_custom_tactile_result_maps_classes_to_raw_detections() -> None:
    result = FakeResult(
        boxes=[
            FakeBox(cls=[0], conf=[0.8], xyxyn=[[0.1, 0.2, 0.3, 0.4]]),
            FakeBox(cls=[1], conf=[0.7], xyxyn=[[0.2, 0.3, 0.5, 0.6]]),
            FakeBox(cls=[2], conf=[0.9], xyxyn=[[0.0, 0.1, 1.0, 0.9]]),
        ]
    )

    detections = ultralytics_result_to_raw_detections(
        result,
        model_key="custom_tactile",
        source_model="checkpoint/custom.pt",
    )

    assert CUSTOM_TACTILE_CLASS_MAP == {
        0: "normal_tactile_block",
        1: "damaged_tactile_block",
        2: "tactile_damage_area",
    }
    assert [detection["class_name"] for detection in detections] == [
        "normal_tactile_block",
        "damaged_tactile_block",
        "tactile_damage_area",
    ]
    first_detection = dict(detections[0])
    bbox = first_detection.pop("bbox")
    assert bbox == pytest.approx((0.1, 0.2, 0.2, 0.2))
    assert first_detection == {
        "model_key": "custom_tactile",
        "source_model": "checkpoint/custom.pt",
        "model_class_id": 0,
        "class_name": "normal_tactile_block",
        "category": "tactile",
        "confidence": 0.8,
        "bbox_xyxy": (0.1, 0.2, 0.3, 0.4),
    }


def test_coco_result_uses_result_names_and_runtime_allowlist() -> None:
    result = FakeResult(
        names={0: "person", 1: "dog", 2: "traffic light"},
        boxes=[
            FakeBox(cls=[0], conf=[0.81], xyxyn=[[0.1, 0.2, 0.3, 0.4]]),
            FakeBox(cls=[1], conf=[0.99], xyxyn=[[0.2, 0.2, 0.4, 0.4]]),
            FakeBox(cls=[2], conf=[0.65], xyxyn=[[-0.1, 0.0, 1.2, 0.5]]),
        ],
    )

    detections = ultralytics_result_to_raw_detections(
        result,
        model_key="coco_general",
        source_model="yolo-coco.pt",
    )

    assert "dog" not in DEFAULT_COCO_ALLOWLIST
    assert [detection["class_name"] for detection in detections] == ["person", "traffic light"]
    assert detections[0]["category"] == "general_obstacle"
    assert detections[0]["bbox"] == pytest.approx((0.1, 0.2, 0.2, 0.2))
    assert detections[0]["bbox_xyxy"] == (0.1, 0.2, 0.3, 0.4)
    assert detections[1]["bbox"] == (0.0, 0.0, 1.0, 0.5)
    assert detections[1]["bbox_xyxy"] == (0.0, 0.0, 1.0, 0.5)


def test_unified_result_keeps_general_and_tactile_classes() -> None:
    result = FakeResult(
        names={
            0: "person",
            2: "car",
            7: "normal_tactile_block",
            8: "damaged_tactile_block",
            12: "e_scooter_obstruction",
            13: "dog",
        },
        boxes=[
            FakeBox(cls=[0], conf=[0.81], xyxyn=[[0.1, 0.2, 0.3, 0.4]]),
            FakeBox(cls=[2], conf=[0.7], xyxyn=[[0.2, 0.2, 0.4, 0.4]]),
            FakeBox(cls=[7], conf=[0.65], xyxyn=[[0.3, 0.3, 0.5, 0.5]]),
            FakeBox(cls=[8], conf=[0.9], xyxyn=[[0.4, 0.4, 0.7, 0.7]]),
            FakeBox(cls=[12], conf=[0.88], xyxyn=[[0.1, 0.1, 0.2, 0.2]]),
            FakeBox(cls=[13], conf=[0.99], xyxyn=[[0.0, 0.0, 0.2, 0.2]]),
        ],
    )

    detections = ultralytics_result_to_raw_detections(
        result,
        model_key="unified_walksafe",
        source_model="unified.pt",
    )

    assert [detection["class_name"] for detection in detections] == [
        "person",
        "car",
        "normal_tactile_block",
        "damaged_tactile_block",
        "e_scooter_obstruction",
    ]
    assert [detection["category"] for detection in detections] == [
        "vulnerable_road_user",
        "vehicle",
        "tactile_normal",
        "tactile_damage",
        "obstruction",
    ]
    assert "dog" not in {detection["class_name"] for detection in detections}


def test_empty_or_missing_boxes_return_empty_list() -> None:
    assert ultralytics_result_to_raw_detections(FakeResult(boxes=None), model_key="custom_tactile") == []
    assert ultralytics_result_to_raw_detections(FakeResult(boxes=[]), model_key="coco_general") == []


def test_malformed_boxes_are_skipped_safely() -> None:
    result = FakeResult(
        boxes=[
            FakeBox(cls=[2], conf=[0.9], xyxyn=[[0.4, 0.4, 0.2, 0.8]]),
            FakeBox(cls=[2], conf=None, xyxyn=[[0.1, 0.1, 0.2, 0.2]]),
            FakeBox(cls=[99], conf=[0.9], xyxyn=[[0.1, 0.1, 0.2, 0.2]]),
            FakeBox(cls=[2], conf=[0.9], xyxyn=[[0.1, 0.1, 0.2]]),
            FakeBox(cls=[2], conf=[1.2], xyxyn=[[0.1, 0.1, 0.2, 0.2]]),
        ]
    )

    detections = ultralytics_result_to_raw_detections(result, model_key="custom_tactile")

    assert len(detections) == 1
    detection = dict(detections[0])
    bbox = detection.pop("bbox")
    assert bbox == pytest.approx((0.1, 0.1, 0.1, 0.1))
    assert detection == {
        "model_key": "custom_tactile",
        "source_model": "YOLO26s custom",
        "model_class_id": 2,
        "class_name": "tactile_damage_area",
        "category": "tactile",
        "confidence": 1.0,
        "bbox_xyxy": (0.1, 0.1, 0.2, 0.2),
    }


def test_coco_can_receive_explicit_name_map_without_hardcoded_class_ids() -> None:
    result = FakeResult(
        boxes=[FakeBox(cls=[42], conf=[0.5], xyxyn=[[0.3, 0.3, 0.6, 0.6]])]
    )

    detections = ultralytics_result_to_raw_detections(
        result,
        model_key="coco_general",
        class_name_by_id={42: "bench"},
    )

    assert detections[0]["model_class_id"] == 42
    assert detections[0]["class_name"] == "bench"


def test_adapter_output_can_feed_two_model_runtime_filter() -> None:
    custom_result = FakeResult(
        boxes=[FakeBox(cls=[2], conf=[0.9], xyxyn=[[0.1, 0.2, 0.3, 0.5]])]
    )
    coco_result = FakeResult(
        names={0: "person"},
        boxes=[FakeBox(cls=[0], conf=[0.8], xyxyn=[[0.2, 0.3, 0.6, 0.9]])],
    )

    custom_detections = ultralytics_result_to_raw_detections(
        custom_result,
        model_key="custom_tactile",
    )
    coco_detections = ultralytics_result_to_raw_detections(
        coco_result,
        model_key="coco_general",
    )

    filtered = filter_and_merge_detections(custom_detections, coco_detections)

    assert [detection.model_key for detection in filtered] == ["custom_tactile", "coco_general"]
    assert filtered[0].bbox == pytest.approx((0.1, 0.2, 0.2, 0.3))
    assert filtered[1].bbox == pytest.approx((0.2, 0.3, 0.4, 0.6))


def test_indexable_ultralytics_boxes_are_iterated() -> None:
    result = FakeResult(
        boxes=FakeIndexableBoxes(
            [
                FakeBox(cls=[1], conf=[0.88], xyxyn=[[0.1, 0.2, 0.4, 0.6]]),
            ]
        )
    )

    detections = ultralytics_result_to_raw_detections(result, model_key="custom_tactile")

    assert len(detections) == 1
    assert detections[0]["class_name"] == "damaged_tactile_block"
    assert detections[0]["bbox"] == pytest.approx((0.1, 0.2, 0.3, 0.4))
