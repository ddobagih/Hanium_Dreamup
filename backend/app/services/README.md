# Backend Services

Router에서 분리된 탐지, 신고 정책, 중복 판정, 외부 provider 변환을 둔다.

| 모듈 | 책임 |
|---|---|
| `detect_v2.py` | fake/YOLO provider 선택, unified 우선 및 legacy fallback runtime |
| `yolo_inference_adapter.py` | Ultralytics 결과를 정규화된 v2 detection으로 변환 |
| `report_policy.py` | v2 신고 허용 class/model/GPS 규칙 |
| `report_serialization.py` | DB report를 API response와 review flag로 변환 |
| `duplicates.py` | PostGIS 반경 및 시간창 기반 중복 후보 조회 |
| `tmap_pedestrian.py` | TMAP POI/보행 경로 요청과 앱 schema 정규화 |

## 불변조건

- unified 탐지는 768 입력과 `unified_walksafe` 13-class order를 사용하며 readiness warmup에서 실제 checkpoint class map을 대조한다.
- legacy custom/COCO 탐지는 각 모델의 class id 공간을 유지한다.
- 일반 객체와 `tactile_damage_area`는 `/reports/v2` 저장 대상이 아니다.
- 외부 provider 응답은 그대로 노출하지 않고 `backend.app.schemas`의 앱 전용 schema로 변환한다.
