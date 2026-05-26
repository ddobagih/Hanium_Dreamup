# WalkSafe Sensor Local Log Schema

- 목적: DeviceMotion/ROI/보폭 calibration을 local/headless/field에서 같은 형태로 기록하기 위한 최소 schema.
- 주의: 이 schema는 로컬 evidence용이다. 사용자 동의 없이 외부 전송하지 않는다.

## Schema

```json
{
  "schema_version": "walksafe.sensor_log.v1",
  "captured_at": "2026-05-26T00:00:00.000Z",
  "source": "devicemotion|gps|route_progress|manual_fixture",
  "device_motion": {
    "sample_count": 40,
    "permission_state": "granted|denied|unsupported|unknown",
    "acceleration_mps2": { "x": 0.0, "y": 0.0, "z": 0.0 },
    "rotation_rate_dps": { "alpha": 0.0, "beta": 0.0, "gamma": 0.0 }
  },
  "gps": {
    "latitude": 37.5665,
    "longitude": 126.978,
    "accuracy_m": 8.5,
    "speed_mps": 1.1,
    "heading_deg": 180
  },
  "route_context": {
    "route_id": "local-fixture",
    "off_route_state": "on_route|candidate|confirmed|unknown",
    "distance_to_route_m": 4.2,
    "progress_ratio": 0.42
  },
  "calibration": {
    "mode": "dry_run|field",
    "step_length_m": 0.62,
    "confidence": 0.76,
    "outlier_count": 2,
    "notes": "mock 또는 마스킹된 현장 메모"
  }
}
```

## Privacy rules

- 전화번호, 이름, raw audio, secret, 원본 이미지 경로를 포함하지 않는다.
- 좌표는 보고서/발표 evidence에서는 목적에 맞게 rounding/mock 좌표를 우선 사용한다.
- 외부 업로드/공유는 별도 승인 전 금지한다.
- 실제 field calibration 완료 주장은 Android 실기기 로그와 수동 절차 기록이 있을 때만 한다.
