export type TwoModelKey = "custom_tactile" | "coco_general";

export type KnownTwoModelClassName =
  | "tactile_damage_area"
  | "damaged_tactile_block"
  | "car"
  | "bus"
  | "truck"
  | "motorcycle"
  | "person"
  | "bicycle"
  | "traffic light"
  | "normal_tactile_block"
  | "bench";

export type TwoModelClassName = KnownTwoModelClassName | (string & {});

export type KnownTwoModelCategory =
  | "tactile_damage"
  | "tactile_normal"
  | "vehicle"
  | "vulnerable_road_user"
  | "traffic_signal"
  | "street_furniture"
  | "unknown";

export type TwoModelCategory = KnownTwoModelCategory | (string & {});

export type NormalizedBBoxV2 = {
  x: number;
  y: number;
  width: number;
  height: number;
};

export type GpsFixV2 = {
  latitude: number;
  longitude: number;
  accuracy_m?: number | null;
};

export type DetectionDistanceSource = "sensor_depth" | "manual_fixture" | "model_estimate" | "unknown";

export type TwoModelDetection = {
  schema_version: "detect.v2";
  model_key: TwoModelKey;
  source_model: string;
  model_class_id: number;
  class_name: TwoModelClassName;
  category: TwoModelCategory;
  confidence: number;
  bbox: NormalizedBBoxV2;
  distance_m?: number | null;
  distance_source?: DetectionDistanceSource | null;
  distance_confidence?: number | null;
  threshold_used: number;
  captured_at: string;
  gps?: GpsFixV2 | null;
  heading?: number | null;
};
